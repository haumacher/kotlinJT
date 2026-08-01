package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.AttributeElement
import de.haumacher.kotlinjt.lsg.CountRange
import de.haumacher.kotlinjt.lsg.GeometricTransformAttributeElement
import de.haumacher.kotlinjt.lsg.GroupNodeElement
import de.haumacher.kotlinjt.lsg.InstanceNodeElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LodNodeElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.MetaDataNodeElement
import de.haumacher.kotlinjt.lsg.NodeElement
import de.haumacher.kotlinjt.lsg.PartNodeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.PolylineSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.SwitchNodeElement
import de.haumacher.kotlinjt.lsg.TriStripSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.lsgSegment
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.LodPolicy
import de.haumacher.kotlinjt.scene.Mat4
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.readScene
import de.haumacher.kotlinjt.shape.ShapeLodDocument
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe review for the writer delivery (issue #8) — three compositions the package's own
 * acceptance never exercised:
 *
 * 1. **Fixed point**: `writeJt(readScene(writeJt(scene)))` must reproduce the first write
 *    byte for byte. Scene equality (the delivery's oracle) tolerates any lossy-but-symmetric
 *    drift; a byte-identical second write does not — it proves the written file decodes to
 *    exactly the scene that produced it AND that authoring is canonical.
 * 2. **Declared-metadata honesty at Layer 1**: the scene projection cannot see the partition's
 *    world box, the shape nodes' untransformed boxes, or the declared count ranges — but a
 *    JT consumer culls and schedules loads by them. Every world-space vertex of the written
 *    file must land in the partition's transformed box (via the same root→shape accumulation
 *    TransformProbeTest validated on producer files), every local vertex in its shape node's
 *    untransformed box, every per-LOD vertex/triangle count inside the node's declared range.
 * 3. **Mutation round-trip**: a scene edited through the Layer 2 model (rename + transform
 *    change on one geometry-bearing node) writes and re-reads with exactly that difference —
 *    and nothing else — relative to the unmutated rewrite.
 */
class WriterProbeTest {
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

    private fun noFixtures(): List<DynamicNode> =
        listOf(
            dynamicTest("no *.jt fixtures — writer probe SKIPPED (0 fixtures)") {
                assumeTrue(false, "no fixtures present")
            },
        )

    // Probe 1: the write is a fixed point of write→read→write, per fixture and LOD policy.

    @TestFactory
    fun writeIsAFixedPoint(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) return noFixtures()
        return fixtures.flatMap { fixture ->
            LodPolicy.entries.map { policy ->
                dynamicTest("${fixture.name} ($policy): second write is byte-identical to the first") {
                    val scene = readScene(fixture.readBytes(), policy)
                    assumeTrue(scene.units != LengthUnit.UNSPECIFIED, "fixture declares no units — writeJt refuses")
                    val first = writeJt(scene)
                    val second = writeJt(readScene(first, policy))
                    assertEquals(first.size, second.size, "second write changed size — authoring is not canonical")
                    assertTrue(first.contentEquals(second)) {
                        "write→read→write drifted at byte " +
                            first.indices.first { first[it] != second[it] } +
                            " of ${first.size} — the written file does not decode to the scene that produced it"
                    }
                    println("PROBE fixed point: ${fixture.name} ($policy) stable at ${first.size} bytes")
                }
            }
        }
    }

    // Probe 2: the written file's declared boxes and count ranges match its actual geometry.

    /** p' = p·M for a row-major 4×4 with translation in m[12..14]; w assumed affine. */
    private fun apply(
        m: DoubleArray,
        p: DoubleArray,
    ): DoubleArray =
        doubleArrayOf(
            p[0] * m[0] + p[1] * m[4] + p[2] * m[8] + m[12],
            p[0] * m[1] + p[1] * m[5] + p[2] * m[9] + m[13],
            p[0] * m[2] + p[1] * m[6] + p[2] * m[10] + m[14],
        )

    /** A·B in row-major convention (apply A first, then B). */
    private fun multiply(
        a: DoubleArray,
        b: DoubleArray,
    ): DoubleArray {
        val out = DoubleArray(16)
        for (r in 0..3) {
            for (c in 0..3) {
                var s = 0.0
                for (k in 0..3) s += a[r * 4 + k] * b[k * 4 + c]
                out[r * 4 + c] = s
            }
        }
        return out
    }

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun nodeMatrix(
        document: LsgDocument,
        node: NodeElement,
    ): DoubleArray? {
        val attributes = document.graphElements.filterIsInstance<AttributeElement>().associateBy { it.objectId }
        for (id in node.baseNode.attributeObjectIds) {
            val attribute = attributes[id]
            if (attribute is GeometricTransformAttributeElement) {
                return attribute.matrix.values.map { it }.toDoubleArray()
            }
        }
        return null
    }

    @TestFactory
    fun declaredMetadataMatchesAuthoredGeometry(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) return noFixtures()
        return fixtures.map { fixture ->
            dynamicTest("${fixture.name} rewrite: declared boxes and count ranges hold the authored geometry") {
                val scene = readScene(fixture.readBytes(), LodPolicy.ALL_LODS)
                assumeTrue(scene.units != LengthUnit.UNSPECIFIED, "fixture declares no units — writeJt refuses")
                val file = JtFile.parse(writeJt(scene))
                val elementData = file.lsgSegment()?.elementData
                assertTrue(elementData != null, "written file has no decodable LSG")
                val document = LsgDocument.decode(elementData!!, file.header.version, file.header.byteOrder).document

                val partition = document.graphElements.filterIsInstance<PartitionNodeElement>().single()
                val world = partition.transformedBBox
                val diagonal =
                    kotlin.math.sqrt(
                        (
                            (world.max.x - world.min.x) * (world.max.x - world.min.x) +
                                (world.max.y - world.min.y) * (world.max.y - world.min.y) +
                                (world.max.z - world.min.z) * (world.max.z - world.min.z)
                        ).toDouble(),
                    )
                val eps = diagonal * 0.005 + 1e-6

                val nodesById = document.graphElements.filterIsInstance<NodeElement>().associateBy { it.objectId }
                val accumulated = mutableMapOf<Int, DoubleArray>()

                fun accumulate(
                    id: Int,
                    parent: DoubleArray,
                ) {
                    val node = nodesById[id] ?: return
                    val own = nodeMatrix(document, node)
                    val acc = if (own != null) multiply(own, parent) else parent
                    accumulated[id] = acc
                    val children =
                        when (node) {
                            is InstanceNodeElement -> listOf(node.childNodeObjectId)
                            is PartitionNodeElement -> node.group.childNodeObjectIds
                            is GroupNodeElement -> node.group.childNodeObjectIds
                            is SwitchNodeElement -> node.group.childNodeObjectIds
                            is LodNodeElement -> node.lod.group.childNodeObjectIds
                            is RangeLodNodeElement -> node.lod.group.childNodeObjectIds
                            is PartNodeElement -> node.metaData.group.childNodeObjectIds
                            is MetaDataNodeElement -> node.metaData.group.childNodeObjectIds
                            else -> emptyList()
                        }
                    for (child in children) accumulate(child, acc)
                }
                accumulate(partition.objectId, identity)

                val atomOwner = mutableMapOf<Int, Int>()
                document.propertyTable?.tables?.forEach {
                        t ->
                    t.entries.forEach { atomOwner[it.valuePropertyAtomObjectId] = t.elementObjectId }
                }
                val shapeNodeBySegment =
                    document.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>()
                        .mapNotNull { atom -> atomOwner[atom.objectId]?.let { atom.segmentId to it } }
                        .toMap()

                var segmentsChecked = 0
                for (segment in file.shapeLodSegments()) {
                    val data = segment.elementData ?: continue
                    val nodeId = shapeNodeBySegment[segment.tocEntry.segmentId]
                    assertTrue(nodeId != null, "written shape segment ${segment.tocEntry.segmentId} owned by no node")
                    val node = nodesById[nodeId!!]
                    val matrix = accumulated[nodeId]
                    assertTrue(node != null && matrix != null, "shape node $nodeId unreachable from the partition")
                    val shapeData =
                        when (node) {
                            is TriStripSetShapeNodeElement -> node.vertexShape.shape
                            is PolylineSetShapeNodeElement -> node.vertexShape.shape
                            else -> error("segment owner $nodeId is not a shape node: $node")
                        }
                    val decoded = ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder).document
                    val geometryVertices =
                        decoded.triStripGeometry?.vertices ?: decoded.polylineGeometry?.vertices ?: emptyList()
                    assertTrue(geometryVertices.isNotEmpty(), "written shape segment decodes to no geometry")

                    val local = shapeData.untransformedBBox
                    for (v in geometryVertices) {
                        assertTrue(
                            v.x >= local.min.x - eps && v.x <= local.max.x + eps &&
                                v.y >= local.min.y - eps && v.y <= local.max.y + eps &&
                                v.z >= local.min.z - eps && v.z <= local.max.z + eps,
                            "local vertex (${v.x}, ${v.y}, ${v.z}) escapes the written untransformed box $local",
                        )
                        val w = apply(matrix!!, doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()))
                        assertTrue(
                            w[0] >= world.min.x - eps && w[0] <= world.max.x + eps &&
                                w[1] >= world.min.y - eps && w[1] <= world.max.y + eps &&
                                w[2] >= world.min.z - eps && w[2] <= world.max.z + eps,
                            "world vertex (${w[0]}, ${w[1]}, ${w[2]}) escapes the written partition box $world",
                        )
                    }

                    // Count-range convention (established against the producer's own NIST file,
                    // where declared counts exceed unique vertex records): the declared vertex
                    // count is the PRIMITIVE-CONSUMED count — sum of line lengths for polylines,
                    // sum of strip lengths for tri-strips — not the unique record count. The
                    // writer's tri rep shares nothing, so consumed == records == 3·triangles;
                    // an Annex D encoder with vertex sharing must revisit this assertion.
                    val vertexRange = shapeData.vertexCountRange
                    decoded.triStripGeometry?.let {
                        assertEquals(
                            CountRange(it.triangles.size * 3, it.triangles.size * 3),
                            vertexRange,
                            "declared tri vertex range is not the consumed count",
                        )
                        assertEquals(geometryVertices.size, it.triangles.size * 3, "written tri rep grew vertex sharing")
                        assertEquals(
                            CountRange(it.triangles.size, it.triangles.size),
                            shapeData.polygonCountRange,
                            "declared polygon range is not the triangle count",
                        )
                    }
                    decoded.polylineGeometry?.let {
                        val consumed = it.polylines.sumOf { line -> line.vertexIndices.size }
                        assertEquals(
                            CountRange(consumed, consumed),
                            vertexRange,
                            "declared polyline vertex range is not the consumed (line-vertex sum) count",
                        )
                        assertEquals(
                            CountRange(0, 0),
                            shapeData.polygonCountRange,
                            "polylines declare a non-zero polygon range",
                        )
                    }
                    segmentsChecked++
                }
                assertTrue(segmentsChecked > 0, "written file resolved no shape segments to nodes")
                println("PROBE metadata: ${fixture.name} rewrite — $segmentsChecked segments honest about boxes and counts")
            }
        }
    }

    // Probe 3: an edit through the scene model round-trips as exactly that edit.

    private data class Mutation(
        val node: SceneNode,
        val renamed: String,
    )

    /** Replaces the first geometry-bearing node (depth-first) with a renamed, moved copy. */
    private fun mutate(node: SceneNode): Pair<SceneNode, Mutation?> {
        if (node.meshes.isNotEmpty() || node.polylines.isNotEmpty()) {
            val moved =
                node.copy(
                    name = node.name + " (probe-mutated)",
                    transform = node.transform * TRANSLATION,
                )
            return moved to Mutation(node, moved.name)
        }
        val children = node.children.toMutableList()
        for (i in children.indices) {
            val (child, mutation) = mutate(children[i])
            if (mutation != null) {
                children[i] = child
                return node.copy(children = children) to mutation
            }
        }
        return node to null
    }

    /** The trees must be equal except at the mutated node, which must carry exactly the edit. */
    private fun assertDiffersOnlyAtMutation(
        base: SceneNode,
        edited: SceneNode,
        mutation: Mutation,
        path: String,
    ) {
        if (base == edited) return
        assertEquals(mutation.renamed, edited.name, "unexpected difference at $path — only the mutated node may differ")
        assertEquals(mutation.node.name + " (probe-mutated)", edited.name, "rename lost at $path")
        assertEquals(base.meshes, edited.meshes, "meshes drifted at the mutated node $path")
        assertEquals(base.polylines, edited.polylines, "polylines drifted at the mutated node $path")
        assertEquals(base.material, edited.material, "material drifted at the mutated node $path")
        assertEquals(base.children, edited.children, "children drifted at the mutated node $path")
        val expected = (base.transform * TRANSLATION).values
        for (i in 0 until 16) {
            assertTrue(
                kotlin.math.abs(edited.transform.values[i] - expected[i]) < 1e-5,
                "transform element $i drifted at $path: ${edited.transform.values[i]} vs $expected",
            )
        }
    }

    @TestFactory
    fun sceneEditRoundTripsAsExactlyThatEdit(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) return noFixtures()
        return fixtures.map { fixture ->
            dynamicTest("${fixture.name}: rename + move of one node survives write→read; nothing else changes") {
                val scene = readScene(fixture.readBytes(), LodPolicy.FINEST_ONLY)
                assumeTrue(scene.units != LengthUnit.UNSPECIFIED, "fixture declares no units — writeJt refuses")
                val (mutatedRoot, mutation) = mutate(scene.root)
                assumeTrue(mutation != null, "no geometry-bearing node — probe not applicable")

                val base = readScene(writeJt(scene), LodPolicy.FINEST_ONLY)
                val edited = readScene(writeJt(scene.copy(root = mutatedRoot)), LodPolicy.FINEST_ONLY)
                assertEquals(base.units, edited.units, "units drifted under mutation")
                assertEquals(base.notes, edited.notes, "notes drifted under mutation")

                fun walk(
                    a: SceneNode,
                    b: SceneNode,
                    path: String,
                ) {
                    if (a == b) return
                    if (b.name == mutation!!.renamed) {
                        assertDiffersOnlyAtMutation(a, b, mutation, path)
                        return
                    }
                    assertEquals(a.name, b.name, "name drifted at $path")
                    assertEquals(a.transform, b.transform, "transform drifted at $path")
                    assertEquals(a.meshes, b.meshes, "meshes drifted at $path")
                    assertEquals(a.polylines, b.polylines, "polylines drifted at $path")
                    assertEquals(a.material, b.material, "material drifted at $path")
                    assertEquals(a.children.size, b.children.size, "child count drifted at $path")
                    for (i in a.children.indices) walk(a.children[i], b.children[i], "$path/${a.children[i].name}[$i]")
                }
                walk(base.root, edited.root, "/")
                println("PROBE mutation: ${fixture.name} — edit round-tripped surgically")
            }
        }
    }

    private companion object {
        /** Exactly float-representable offsets, so the edit survives any F32 wire narrowing. */
        val TRANSLATION =
            Mat4(
                listOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    10.0, -5.0, 2.5, 1.0,
                ),
            )
    }
}
