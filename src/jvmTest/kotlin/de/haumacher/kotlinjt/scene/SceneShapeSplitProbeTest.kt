package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.MaterialAttributeElement
import de.haumacher.kotlinjt.lsg.ShapeNodeElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.shape.decodeShapeLod
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.IdentityHashMap

/**
 * Probes for the one-node-per-body scene (issue #13), composed against Layer 1 as the oracle —
 * the split must move geometry around without creating, losing or recolouring any of it:
 *
 * 1. **Conservation.** Every triangle Layer 1 decodes is in the scene exactly once. Splitting
 *    one merged mesh into eleven must not lose or duplicate a single triangle, and the
 *    identity-deduplicated walk makes instancing part of the claim.
 * 2. **Colour arrives whole.** Where the file puts a material on a shape node, the triangles
 *    drawn in that colour at Layer 1 are exactly the triangles drawn in that colour at Layer 2
 *    — the assertion the merge could not satisfy, since it kept one material of eleven.
 * 3. **The ambiguity note is spent.** With one node per body, no fixture in the corpus needs
 *    `SCENE_MATERIAL_AMBIGUOUS`; it can only fire where one body's LOD tiers disagree.
 */
class SceneShapeSplitProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf("fixtures", "fixtures-local")
            .flatMap { File(repoRoot(), it).listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.toList().orEmpty() }
            .sortedBy { it.name }

    /** Every scene node once, by identity — instanced subtrees are one object, counted once. */
    private fun uniqueNodes(root: SceneNode): List<SceneNode> {
        val seen = IdentityHashMap<SceneNode, Unit>()
        val result = mutableListOf<SceneNode>()

        fun walk(node: SceneNode) {
            if (seen.put(node, Unit) != null) return
            result.add(node)
            node.children.forEach(::walk)
        }
        walk(root)
        return result
    }

    /**
     * Triangles per shape node at Layer 1: the decoded tri-strip bodies of every shape segment
     * the LSG's property table hands that node.
     */
    private class Layer1Bodies(
        val file: JtFile,
    ) {
        val document = file.decodeLsg()?.document

        val trianglesByShapeNode: Map<Int, Int> =
            document?.let { lsg ->
                val atoms =
                    lsg.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>().associateBy { it.objectId }
                val decoded =
                    file.shapeLodSegments().associate { segment ->
                        segment.tocEntry.segmentId to (file.decodeShapeLod(segment)?.document?.triStripGeometry?.triangles?.size ?: 0)
                    }
                val shapeIds = lsg.graphElements.filterIsInstance<ShapeNodeElement>().map { it.objectId }.toSet()
                lsg.propertyTable?.tables.orEmpty()
                    .filter { it.elementObjectId in shapeIds }
                    .associate { table ->
                        table.elementObjectId to
                            table.entries.mapNotNull { atoms[it.valuePropertyAtomObjectId] }
                                .filter { it.segmentType in 6..16 }
                                .sumOf { decoded[it.segmentId] ?: 0 }
                    }
            }.orEmpty()

        /** The diffuse colour of the material a shape node carries *directly*, if any. */
        val colourByShapeNode: Map<Int, Color> =
            document?.let { lsg ->
                val materials = lsg.graphElements.filterIsInstance<MaterialAttributeElement>().associateBy { it.objectId }
                lsg.graphElements.filterIsInstance<ShapeNodeElement>().mapNotNull { shape ->
                    val material =
                        shape.baseNode.attributeObjectIds.firstNotNullOfOrNull { materials[it] } ?: return@mapNotNull null
                    val diffuse = material.diffuseColourAndAlpha
                    shape.objectId to Color(diffuse.r, diffuse.g, diffuse.b, diffuse.a)
                }.toMap()
            }.orEmpty()
    }

    @TestFactory
    fun probes(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — shape-split probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.flatMap { fixture ->
            val bytes = fixture.readBytes()
            listOf(
                // spec: §13.9 (the LSG's shapes are the scene's bodies — all of them, once)
                dynamicTest("${fixture.name}: the scene holds every decoded triangle exactly once") {
                    val file = JtFile.parse(bytes)
                    val layer1 = Layer1Bodies(file)
                    assumeTrue(layer1.document != null, "no decodable LSG")
                    val expected = layer1.trianglesByShapeNode.values.sum()
                    assumeTrue(expected > 0, "no decoded tri-strip geometry in this fixture")
                    val scene = file.readScene(LodPolicy.ALL_LODS)
                    val actual = uniqueNodes(scene.root).sumOf { node -> node.meshes.sumOf { it.triangles.size } }
                    assertEquals(expected, actual, "Layer 1 decoded $expected triangles; the scene shows $actual")
                    println("PROBE conservation: ${fixture.name} — $actual triangles, none lost, none duplicated")
                },
                // spec: §6.1.2.2 (Material Attribute) + §13.9 (accumulation: the shape's wins)
                dynamicTest("${fixture.name}: each shape's own colour reaches the scene with all of its triangles") {
                    val file = JtFile.parse(bytes)
                    val layer1 = Layer1Bodies(file)
                    assumeTrue(layer1.document != null, "no decodable LSG")
                    val expected = mutableMapOf<Color, Int>()
                    for ((shapeId, triangles) in layer1.trianglesByShapeNode) {
                        if (triangles == 0) continue
                        val colour = layer1.colourByShapeNode[shapeId] ?: continue
                        expected.merge(colour, triangles, Int::plus)
                    }
                    assumeTrue(expected.isNotEmpty(), "this fixture puts no material on its shape nodes")
                    val scene = file.readScene(LodPolicy.ALL_LODS)
                    val actual = mutableMapOf<Color, Int>()
                    for (node in uniqueNodes(scene.root)) {
                        val triangles = node.meshes.sumOf { it.triangles.size }
                        if (triangles == 0) continue
                        val colour = node.material?.baseColor ?: continue
                        actual.merge(colour, triangles, Int::plus)
                    }
                    assertEquals(
                        expected.keys.sortedBy { it.toString() },
                        actual.keys.sortedBy { it.toString() },
                        "the scene's mesh colours are not the file's shape colours",
                    )
                    for ((colour, triangles) in expected) {
                        assertEquals(triangles, actual[colour], "triangles drawn in $colour")
                    }
                    println("PROBE colour: ${fixture.name} — ${expected.size} shape materials, each with all its triangles")
                },
                dynamicTest("${fixture.name}: no material has to be thrown away") {
                    val scene = readScene(bytes)
                    val ambiguous = scene.notes.filter { it.name == "SCENE_MATERIAL_AMBIGUOUS" }
                    assertTrue(ambiguous.isEmpty()) {
                        "one node still merges conflicting materials: ${ambiguous.map { it.message }}"
                    }
                },
            )
        }
    }
}
