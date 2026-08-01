package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LodNodeElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.NodeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.ShapeNodeElement
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.childObjectIds
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.shape.decodeShapeLod
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.IdentityHashMap

/**
 * The Layer 2 acceptance battery (issue #7): [readScene] on every discovered fixture, with
 * all expectations computed independently from the Layer 1 model — units from the LSG's own
 * property atoms, geometry counts from the decoded shape segments, LOD tier counts from the
 * LSG part grouping, world bounds from the partition's declared box. Committed code never
 * names a local fixture file.
 */
class SceneFixtureTest {
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

    // --- Independent Layer 1 expectations ---

    private class Layer1View(
        file: JtFile,
    ) {
        val lsg: LsgDocument? = file.decodeLsg()?.document
        val lsgNoteNames: List<String> = file.decodeLsg()?.notes?.map { it.name }.orEmpty()

        /** Values of JT_PROP_MEASUREMENT_UNITS across the property table, distinct. */
        val declaredUnits: List<String> =
            lsg?.let { document ->
                val atoms =
                    document.propertyAtoms.filterIsInstance<de.haumacher.kotlinjt.lsg.PropertyAtomElement>()
                        .associateBy { it.objectId }
                document.propertyTable?.tables.orEmpty().flatMap { table ->
                    table.entries.mapNotNull { entry ->
                        val key = atoms[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement
                        if (key?.value?.removeSuffix("::") != "JT_PROP_MEASUREMENT_UNITS") return@mapNotNull null
                        (atoms[entry.valuePropertyAtomObjectId] as? StringPropertyAtomElement)?.value
                    }
                }.distinct()
            }.orEmpty()

        val nodesById: Map<Int, NodeElement> =
            lsg?.graphElements?.filterIsInstance<NodeElement>()?.associateBy { it.objectId }.orEmpty()

        /** Late-loaded shape segments per shape node, in Shape-LOD segment-type order. */
        val segmentsByShapeNode: Map<Int, List<de.haumacher.kotlinjt.io.Guid>> =
            lsg?.let { document ->
                val atoms =
                    document.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>().associateBy { it.objectId }
                val shapeIds = document.graphElements.filterIsInstance<ShapeNodeElement>().map { it.objectId }.toSet()
                document.propertyTable?.tables.orEmpty()
                    .filter { it.elementObjectId in shapeIds }
                    .associate { table ->
                        table.elementObjectId to
                            table.entries.mapNotNull { atoms[it.valuePropertyAtomObjectId] }
                                .filter { it.segmentType in 6..16 }
                                .sortedBy { it.segmentType }
                                .map { it.segmentId }
                    }
            }.orEmpty()

        /** Shape-node object id per referenced shape segment GUID. */
        val shapeNodeBySegment: Map<de.haumacher.kotlinjt.io.Guid, Int> =
            lsg?.let { document ->
                val atomOwner = mutableMapOf<Int, Int>()
                document.propertyTable?.tables?.forEach { t ->
                    t.entries.forEach { atomOwner[it.valuePropertyAtomObjectId] = t.elementObjectId }
                }
                val shapeIds = document.graphElements.filterIsInstance<ShapeNodeElement>().map { it.objectId }.toSet()
                document.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>()
                    .mapNotNull { atom ->
                        val owner = atomOwner[atom.objectId] ?: return@mapNotNull null
                        if (owner !in shapeIds) return@mapNotNull null
                        atom.segmentId to owner
                    }.toMap()
            }.orEmpty()

        val parentOf: Map<Int, Int> =
            lsg?.graphElements?.filterIsInstance<NodeElement>()?.flatMap { node ->
                node.childObjectIds.map { it to node.objectId }
            }?.toMap().orEmpty()

        val lodNodes: Set<Int> =
            lsg?.graphElements?.filter { it is LodNodeElement || it is RangeLodNodeElement }
                ?.filterIsInstance<de.haumacher.kotlinjt.lsg.TypedLsgElement>()?.map { it.objectId }?.toSet().orEmpty()

        /** The nearest LOD/Range-LOD ancestor of a node — the "part" grouping key. */
        fun lodAncestor(nodeId: Int): Int? {
            var current = parentOf[nodeId]
            var guard = 0
            while (current != null && guard++ < 64) {
                if (current in lodNodes) return current
                current = parentOf[current]
            }
            return null
        }

        /**
         * The shape nodes of one LOD node's tiers, in the LSG's own child order: one list per
         * tier (alternative representation), and position *j* within a tier is the shape slot
         * the scene turns into one node.
         */
        fun tiersOf(lodNodeId: Int): List<List<Int>> {
            fun shapesUnder(
                id: Int,
                depth: Int,
            ): List<Int> {
                val node = nodesById[id] ?: return emptyList()
                if (node is ShapeNodeElement) return listOf(id)
                if (depth > 16) return emptyList()
                // A nested LOD node contributes only its finest alternative (as the scene does).
                val children = if (id in lodNodes) node.childObjectIds.take(1) else node.childObjectIds
                return children.flatMap { shapesUnder(it, depth + 1) }
            }
            return nodesById[lodNodeId]?.childObjectIds?.map { shapesUnder(it, 0) }.orEmpty()
        }
    }

    /** Every node that is named or has a named ancestor — i.e. that a consumer can locate. */
    private fun namedNodes(root: SceneNode): Set<SceneNode> {
        val located = java.util.Collections.newSetFromMap(IdentityHashMap<SceneNode, Boolean>())

        fun walk(
            node: SceneNode,
            underName: Boolean,
        ) {
            val here = underName || node.name.isNotEmpty()
            if (here) located.add(node)
            node.children.forEach { walk(it, here) }
        }
        walk(root, false)
        return located
    }

    /** All scene nodes, deduplicated by identity (instanced subtrees are shared objects). */
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

    @TestFactory
    fun sceneBattery(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — scene battery SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture -> fixtureScene(fixture) }
    }

    private fun fixtureScene(fixture: File): DynamicNode {
        val bytes = fixture.readBytes()
        return dynamicContainer(
            fixture.name,
            listOf(
                // spec: §13.8 (JT_PROP_MEASUREMENT_UNITS — units explicit in the scene)
                dynamicTest("units are explicit and match the file's own declaration") {
                    val file = JtFile.parse(bytes)
                    val view = Layer1View(file)
                    assumeTrue(view.lsg != null, "no decodable LSG")
                    val scene = file.readScene()
                    val expected = view.declaredUnits.mapNotNull { LengthUnit.parse(it) }.distinct()
                    when {
                        expected.size == 1 -> assertEquals(expected[0], scene.units, "the declared unit must reach the scene")
                        expected.isEmpty() && view.declaredUnits.isEmpty() ->
                            assertEquals(LengthUnit.UNSPECIFIED, scene.units, "no declaration must be explicit, never a default")
                        else -> assertEquals(LengthUnit.UNSPECIFIED, scene.units)
                    }
                },
                dynamicTest("silence means success: a fully decodable file yields a note-free scene") {
                    val file = JtFile.parse(bytes)
                    val view = Layer1View(file)
                    assumeTrue(view.lsg != null, "no decodable LSG")
                    val allGeometryDecodes =
                        view.shapeNodeBySegment.keys.all { id ->
                            val segment = file.shapeLodSegments().firstOrNull { it.tocEntry.segmentId == id }
                            val document = segment?.let { file.decodeShapeLod(it)?.document }
                            document?.triStripGeometry != null || document?.polylineGeometry != null
                        }
                    assumeTrue(view.lsgNoteNames.isEmpty() && allGeometryDecodes, "fixture has known refusals; honesty covered elsewhere")
                    val scene = file.readScene()
                    assertEquals(emptyList<String>(), scene.notes.map { it.name }, "unexpected scene notes: ${scene.notes}")
                },
                // spec: §13.9 (scene graph construction — every decoded body reaches the scene once)
                dynamicTest("every decoded shape body appears exactly once as scene geometry") {
                    val file = JtFile.parse(bytes)
                    val view = Layer1View(file)
                    assumeTrue(view.lsg != null, "no decodable LSG")
                    var triBodies = 0
                    var polyBodies = 0
                    for (segment in file.shapeLodSegments()) {
                        if (segment.tocEntry.segmentId !in view.shapeNodeBySegment) continue
                        val document = file.decodeShapeLod(segment)?.document ?: continue
                        if (document.triStripGeometry != null) triBodies++
                        if (document.polylineGeometry != null) polyBodies++
                    }
                    assumeTrue(triBodies + polyBodies > 0, "no decodable shape geometry in this fixture")
                    val scene = file.readScene()
                    val nodes = uniqueNodes(scene.root)
                    val meshes = IdentityHashMap<Mesh, Unit>()
                    val polylineSets = IdentityHashMap<PolylineSet, Unit>()
                    nodes.forEach { node ->
                        node.meshes.forEach { meshes[it] = Unit }
                        node.polylines.forEach { polylineSets[it] = Unit }
                    }
                    assertEquals(triBodies, meshes.size, "each decoded tri-strip body is one scene mesh")
                    assertEquals(polyBodies, polylineSets.size, "each decoded polyline body is one scene polyline set")
                },
                // spec: §13.9 (LSG Part Structure — one scene node per shape, one entry per tier)
                dynamicTest("geometry-bearing nodes are locatable and carry one entry per decoded LOD tier") {
                    val file = JtFile.parse(bytes)
                    val view = Layer1View(file)
                    assumeTrue(view.lsg != null, "no decodable LSG")
                    // Expected shape slots: for every LOD node, position j across its tiers is
                    // one body, and that body's entry count is the number of tiers in which it
                    // decoded. Computed from the LSG's own child order plus the decoded bodies.
                    val decodedBySegment =
                        file.shapeLodSegments().associate { segment ->
                            val document = file.decodeShapeLod(segment)?.document
                            segment.tocEntry.segmentId to Pair(document?.triStripGeometry != null, document?.polylineGeometry != null)
                        }

                    fun kindsOf(shapeNodeId: Int): Pair<Int, Int> {
                        var tri = 0
                        var poly = 0
                        for (segmentId in view.segmentsByShapeNode[shapeNodeId].orEmpty()) {
                            val decoded = decodedBySegment[segmentId] ?: continue
                            if (decoded.first) tri++
                            if (decoded.second) poly++
                        }
                        return tri to poly
                    }

                    val expectedMeshCounts = mutableListOf<Int>()
                    val expectedPolyCounts = mutableListOf<Int>()
                    for (lodNodeId in view.lodNodes) {
                        val tiers = view.tiersOf(lodNodeId)
                        val slots = tiers.maxOfOrNull { it.size } ?: 0
                        for (slot in 0 until slots) {
                            var meshes = 0
                            var polylines = 0
                            for (tier in tiers) {
                                val shapeId = tier.getOrNull(slot) ?: continue
                                val (tri, poly) = kindsOf(shapeId)
                                meshes += tri
                                polylines += poly
                            }
                            if (meshes > 0) expectedMeshCounts.add(meshes)
                            if (polylines > 0) expectedPolyCounts.add(polylines)
                        }
                    }
                    assumeTrue(
                        expectedMeshCounts.isNotEmpty() || expectedPolyCounts.isNotEmpty(),
                        "no LOD-grouped geometry in this fixture",
                    )
                    val scene = file.readScene()
                    val nodes = uniqueNodes(scene.root)
                    val meshBearing = nodes.filter { it.meshes.isNotEmpty() }
                    val polyBearing = nodes.filter { it.polylines.isNotEmpty() }
                    assertEquals(expectedMeshCounts.size, meshBearing.size, "one mesh-bearing scene node per shape slot")
                    assertEquals(expectedPolyCounts.size, polyBearing.size, "one polyline-bearing scene node per shape slot")
                    // A body the file leaves unnamed stays unnamed — but it must be locatable:
                    // the part it belongs to is named, and it hangs under that name.
                    val named = namedNodes(scene.root)
                    for (node in meshBearing + polyBearing) {
                        assertTrue(node in named, "a geometry-bearing node with no named node on its path")
                    }
                    assertEquals(
                        expectedMeshCounts.sorted(),
                        meshBearing.map { it.meshes.size }.sorted(),
                        "meshes per node must match each body's decoded tier count",
                    )
                    assertEquals(
                        expectedPolyCounts.sorted(),
                        polyBearing.map { it.polylines.size }.sorted(),
                        "polyline sets per node must match each body's decoded tier count",
                    )
                    // Tier order: finest first — triangle counts strictly descend.
                    for (node in meshBearing) {
                        for (i in 1 until node.meshes.size) {
                            assertTrue(
                                node.meshes[i].triangles.size < node.meshes[i - 1].triangles.size,
                                "${node.name}: LOD $i has ${node.meshes[i].triangles.size} triangles, " +
                                    "not fewer than LOD ${i - 1}'s ${node.meshes[i - 1].triangles.size}",
                            )
                        }
                    }
                },
                dynamicTest("mesh indices are valid in every scene mesh") {
                    val file = JtFile.parse(bytes)
                    val scene = file.readScene()
                    val nodes = uniqueNodes(scene.root)
                    assumeTrue(nodes.any { it.meshes.isNotEmpty() }, "no meshes in this fixture's scene")
                    for (node in nodes) {
                        for (mesh in node.meshes) {
                            for (t in mesh.triangles) {
                                for (index in listOf(t.v0, t.v1, t.v2)) {
                                    assertTrue(index in mesh.positions.indices, "position index $index out of range")
                                }
                                for (index in listOf(t.n0, t.n1, t.n2)) {
                                    assertTrue(
                                        index == -1 || index in mesh.normals.indices,
                                        "normal index $index out of range",
                                    )
                                }
                            }
                        }
                        for (set in node.polylines) {
                            for (line in set.lines) {
                                assertTrue(line.size >= 2, "polyline with ${line.size} points")
                                for (index in line) {
                                    assertTrue(index in set.positions.indices, "polyline index $index out of range")
                                }
                            }
                        }
                    }
                },
                // spec: §13.9 (attribute accumulation — world transforms carry meshes into the partition box)
                dynamicTest("world-space scene bounds stay inside the partition's declared box") {
                    val file = JtFile.parse(bytes)
                    val view = Layer1View(file)
                    val partition = view.lsg?.graphElements?.filterIsInstance<PartitionNodeElement>()?.firstOrNull()
                    assumeTrue(partition != null, "no partition node")
                    val box = partition!!.transformedBBox
                    val diagonal =
                        kotlin.math.sqrt(
                            (
                                (box.max.x - box.min.x) * (box.max.x - box.min.x) +
                                    (box.max.y - box.min.y) * (box.max.y - box.min.y) +
                                    (box.max.z - box.min.z) * (box.max.z - box.min.z)
                            ).toDouble(),
                        )
                    val eps = (diagonal * 0.005 + 1e-6).toFloat()
                    val scene = file.readScene()
                    var checked = 0L

                    fun inBox(p: Vec3): Boolean =
                        p.x >= box.min.x - eps && p.x <= box.max.x + eps &&
                            p.y >= box.min.y - eps && p.y <= box.max.y + eps &&
                            p.z >= box.min.z - eps && p.z <= box.max.z + eps

                    fun walk(
                        node: SceneNode,
                        parentWorld: Mat4,
                    ) {
                        val world = node.transform * parentWorld
                        for (mesh in node.meshes) {
                            for (p in mesh.positions) {
                                val w = world.transformPoint(p)
                                assertTrue(inBox(w), "world vertex $w of \"${node.name}\" escapes the partition box $box (eps $eps)")
                                checked++
                            }
                        }
                        for (set in node.polylines) {
                            for (p in set.positions) {
                                val w = world.transformPoint(p)
                                assertTrue(inBox(w), "world polyline point $w of \"${node.name}\" escapes the partition box $box")
                                checked++
                            }
                        }
                        node.children.forEach { walk(it, world) }
                    }
                    walk(scene.root, Mat4.IDENTITY)
                    assumeTrue(checked > 0, "no geometry to check")
                    println("SCENE BOUNDS: $checked world-space points inside the partition box")
                },
                dynamicTest("FINEST_ONLY keeps exactly the finest mesh of ALL_LODS") {
                    val file = JtFile.parse(bytes)
                    val all = file.readScene(LodPolicy.ALL_LODS)
                    val finest = file.readScene(LodPolicy.FINEST_ONLY)

                    fun zipWalk(
                        a: SceneNode,
                        b: SceneNode,
                    ) {
                        assertEquals(a.name, b.name)
                        assertEquals(a.meshes.take(1), b.meshes, "FINEST_ONLY must be the first (finest) mesh of ALL_LODS")
                        assertEquals(a.polylines.take(1), b.polylines)
                        assertEquals(a.children.size, b.children.size)
                        a.children.zip(b.children).forEach { (x, y) -> zipWalk(x, y) }
                    }
                    zipWalk(all.root, finest.root)
                    assertEquals(all.notes.map { it.name }, finest.notes.map { it.name })
                },
            ),
        )
    }
}
