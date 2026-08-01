package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.lsg.GroupNodeElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LodNodeElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.MetaDataNodeElement
import de.haumacher.kotlinjt.lsg.OpaqueLsgElement
import de.haumacher.kotlinjt.lsg.PartNodeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.ShapeNodeElement
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.SwitchNodeElement
import de.haumacher.kotlinjt.lsg.TypedLsgElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.lsg.encodeLsgSegmentPayload
import de.haumacher.kotlinjt.lsg.lsgSegment
import de.haumacher.kotlinjt.meta.MetaDataDocument
import de.haumacher.kotlinjt.meta.OpaqueMetaDataElement
import de.haumacher.kotlinjt.meta.metaDataSegments
import de.haumacher.kotlinjt.shape.OpaqueShapeLodElement
import de.haumacher.kotlinjt.shape.PolylineGeometry
import de.haumacher.kotlinjt.shape.ShapeLodDocument
import de.haumacher.kotlinjt.shape.TriStripGeometry
import de.haumacher.kotlinjt.shape.decodeShapeLod
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.security.MessageDigest

/**
 * The acceptance authority (issue #1 amendment): auto-discovers every `*.jt` under
 * `fixtures-local/` at the repository root and runs the standard battery — clean parse with
 * zero notes beyond the recorded expectation, inventory matching the sidecar
 * `<name>.expected.json` (created on first run for human review), and byte-identical
 * re-serialization.
 *
 * Fixture files are IP-encumbered customer data: they are gitignored, their names are never
 * written into committed test code, and the sidecars stay next to them, equally local.
 * When no fixtures exist the suite skips VISIBLY with a count — a skipped suite is not
 * silence.
 */
class FixtureDiscoveryTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /**
     * Resolves each late-loaded shape segment to the LSG shape node that references it:
     * late-loaded atom -> owning element via the property table -> shape node.
     */
    private fun shapeNodeBySegmentId(document: LsgDocument): Map<de.haumacher.kotlinjt.io.Guid, ShapeNodeElement> {
        val table = document.propertyTable ?: return emptyMap()
        val atomOwner = mutableMapOf<Int, Int>()
        for (elementTable in table.tables) {
            for (entry in elementTable.entries) {
                atomOwner[entry.valuePropertyAtomObjectId] = elementTable.elementObjectId
            }
        }
        val shapeNodesById = document.graphElements.filterIsInstance<ShapeNodeElement>().associateBy { it.objectId }
        return document.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>()
            .mapNotNull { atom ->
                val owner = atomOwner[atom.objectId] ?: return@mapNotNull null
                val node = shapeNodesById[owner] ?: return@mapNotNull null
                atom.segmentId to node
            }.toMap()
    }

    /** The parent node id of every child node id, from the LSG's child lists. */
    private fun parentByNodeId(document: LsgDocument): Map<Int, Int> {
        val parentOf = mutableMapOf<Int, Int>()
        for (element in document.graphElements) {
            if (element !is TypedLsgElement) continue
            val children =
                when (element) {
                    is GroupNodeElement -> element.group.childNodeObjectIds
                    is PartitionNodeElement -> element.group.childNodeObjectIds
                    is PartNodeElement -> element.metaData.group.childNodeObjectIds
                    is MetaDataNodeElement -> element.metaData.group.childNodeObjectIds
                    is LodNodeElement -> element.lod.group.childNodeObjectIds
                    is RangeLodNodeElement -> element.lod.group.childNodeObjectIds
                    is SwitchNodeElement -> element.group.childNodeObjectIds
                    else -> emptyList()
                }
            for (child in children) parentOf[child] = element.objectId
        }
        return parentOf
    }

    /** Index/normal sanity plus the LSG-declared count ranges for a triangle mesh. */
    private fun checkTriStripGeometry(
        segment: JtSegment,
        geometry: TriStripGeometry,
        generation: LsgGeneration,
        declared: de.haumacher.kotlinjt.lsg.BaseShapeData?,
    ) {
        val id = segment.tocEntry.segmentId
        assertTrue(geometry.triangles.isNotEmpty(), "$id: no triangles decoded")
        for (triangle in geometry.triangles) {
            for (index in listOf(triangle.v0, triangle.v1, triangle.v2)) {
                assertTrue(index in geometry.vertices.indices, "vertex index $index out of range")
            }
            if (geometry.normals.isNotEmpty()) {
                for (index in listOf(triangle.n0, triangle.n1, triangle.n2)) {
                    assertTrue(index in geometry.normals.indices, "normal index $index out of range")
                }
            }
        }
        for (normal in geometry.normals) {
            val length = kotlin.math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
            assertTrue(kotlin.math.abs(length - 1f) < 1e-3, "non-unit normal $normal")
        }
        if (declared == null) return
        // No vertex-count assertion here, deliberately. The LSG's Vertex Count Range is NOT
        // normatively related to any count in the LOD body: 9.5 says nothing tying the two
        // together (SPEC_COVERAGE_95.md, finding E-13). Empirically the NetAllied producer
        // writes `triangles + 2 * strips` — the strip-corner count of a strip representation
        // the compressed body does not store — which exceeds the vertex-record count only
        // when records are shared. KR360-1.jt's {…33} is the counter-example: 125 face groups
        // of a repeated glyph share almost nothing, so 4455 records sit above a declared 3003
        // while the file's own topology, coordinate and normal hashes all verify.
        val polygonRange = declared.polygonCountRange
        if (polygonRange.max > 0) {
            // NX 10.5 declares more polygons than the topological decode yields (the strip
            // form's degenerate triangles are counted); the NetAllied 9.5 fixture declares
            // the exact triangle count. Assert each generation's actual truth.
            assertTrue(
                geometry.triangles.size <= polygonRange.max,
                "$id: ${geometry.triangles.size} triangles exceed the LSG-declared range $polygonRange",
            )
            if (generation == LsgGeneration.V9) {
                assertTrue(
                    geometry.triangles.size >= polygonRange.min,
                    "$id: ${geometry.triangles.size} triangles below the LSG-declared range $polygonRange",
                )
            }
        }
    }

    /** Index sanity plus the LSG-declared count ranges for a polyline set. */
    private fun checkPolylineGeometry(
        segment: JtSegment,
        geometry: PolylineGeometry,
        declared: de.haumacher.kotlinjt.lsg.BaseShapeData?,
    ) {
        val id = segment.tocEntry.segmentId
        assertTrue(geometry.polylines.isNotEmpty(), "$id: no polylines decoded")
        for (polyline in geometry.polylines) {
            assertTrue(polyline.vertexIndices.size >= 2, "$id: polyline with ${polyline.vertexIndices.size} vertices")
            for (index in polyline.vertexIndices) {
                assertTrue(index in geometry.vertices.indices, "$id: vertex index $index out of range")
            }
        }
        if (declared == null) return
        // A corpus convention, not a spec rule: 9.5 ties no LOD count to the LSG's Vertex
        // Count Range (SPEC_COVERAGE_95.md, finding E-13). It holds exactly for all 15 NIST
        // polyline LODs, so it stays as decode-drift regression cover — but a future fixture
        // that breaks it is evidence about its producer, not a defect.
        val corners = geometry.polylines.sumOf { it.vertexIndices.size }
        val vertexRange = declared.vertexCountRange
        if (vertexRange.max > 0) {
            assertTrue(
                corners in vertexRange.min..vertexRange.max,
                "$id: $corners polyline corners outside the LSG-declared range $vertexRange",
            )
        }
    }

    /** Walks up to the nearest LOD-holding ancestor (Range LOD / LOD node) of a shape node. */
    private fun lodAncestorOf(
        nodeId: Int,
        parentOf: Map<Int, Int>,
        document: LsgDocument,
    ): Int? {
        val lodNodes =
            document.graphElements.filter { it is LodNodeElement || it is RangeLodNodeElement }
                .filterIsInstance<TypedLsgElement>().map { it.objectId }.toSet()
        var current: Int? = parentOf[nodeId]
        var guard = 0
        while (current != null && guard++ < 64) {
            if (current in lodNodes) return current
            current = parentOf[current]
        }
        return null
    }

    @TestFactory
    fun localFixtures(): List<DynamicNode> {
        // Both fixture tiers run the same battery: the committed public spine under
        // `fixtures/` and the IP-encumbered local suite under `fixtures-local/`.
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures under ${directories.joinToString { it.path }} — real-file suite SKIPPED (0 fixtures)") {
                    println("FIXTURE SUITE SKIPPED: 0 fixtures in ${directories.joinToString { it.path }}")
                    assumeTrue(false, "no fixtures present; the real-file acceptance suite did not run")
                },
            )
        }
        println("FIXTURE SUITE: ${fixtures.size} fixture(s) discovered in ${directories.joinToString { it.path }}")
        return fixtures.map { fixture -> fixtureBattery(fixture) }
    }

    private fun fixtureBattery(fixture: File): DynamicNode {
        val bytes = fixture.readBytes()
        return dynamicContainer(
            fixture.name,
            listOf(
                dynamicTest("parses with zero unexpected notes") {
                    val file = JtFile.parse(bytes)
                    val sidecar = File(fixture.parentFile, fixture.name + ".expected.json")
                    if (sidecar.isFile) {
                        // The note names recorded in the sidecar are the expectation;
                        // anything beyond them is a regression.
                        val expectedNotes = Regex("\"noteNames\": \\[([^\\]]*)]").find(sidecar.readText())
                        val expected =
                            expectedNotes?.groupValues?.get(1)
                                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                                .orEmpty()
                        assertEquals(expected, file.notes.map { it.name }, "unexpected load notes: ${file.notes}")
                    } else {
                        assertEquals(
                            emptyList<String>(),
                            file.notes.map { it.name },
                            "first parse of a new fixture must be note-free (or record its notes in the sidecar)",
                        )
                    }
                },
                dynamicTest("inventory matches sidecar expectations") {
                    val file = JtFile.parse(bytes)
                    val json = file.inventoryJson(::sha256)
                    val sidecar = File(fixture.parentFile, fixture.name + ".expected.json")
                    if (!sidecar.isFile) {
                        sidecar.writeText(json)
                        println("SIDECAR CREATED for review: ${sidecar.path}")
                        println(file.inventory())
                    } else {
                        assertEquals(sidecar.readText(), json, "segment inventory drifted from ${sidecar.name}")
                    }
                },
                dynamicTest("re-serializes byte-identically") {
                    val file = JtFile.parse(bytes)
                    assertArrayEquals(bytes, file.serialize(), "Layer 0 losslessness violated")
                },
                dynamicTest("LSG decodes typed-or-noted and round-trips byte-identically") {
                    val file = JtFile.parse(bytes)
                    val elementData = file.lsgSegment()?.elementData
                    assumeTrue(elementData != null, "no decodable LSG segment in this fixture")
                    val result = LsgDocument.decode(elementData!!, file.header.version, file.header.byteOrder)
                    // Zero unnamed refusals: every opaque element is covered by a note.
                    val opaque = result.document.allElements.count { it is OpaqueLsgElement }
                    assertTrue(
                        opaque <= result.notes.size,
                        "$opaque opaque elements but only ${result.notes.size} notes — a silent refusal",
                    )
                    assertArrayEquals(
                        elementData.toByteArray(),
                        result.document.encode(file.header.byteOrder).toByteArray(),
                        "Layer 1 losslessness violated: encode(decode(elementStream)) drifted",
                    )
                },
                dynamicTest("shape LOD bodies decode typed-or-noted and round-trip byte-identically") {
                    val file = JtFile.parse(bytes)
                    val decodable = file.shapeLodSegments().filter { it.elementData != null }
                    assumeTrue(decodable.isNotEmpty(), "no decodable shape LOD segments in this fixture")
                    for (segment in decodable) {
                        val elementData = segment.elementData!!
                        val result = ShapeLodDocument.decode(elementData, file.header.version, file.header.byteOrder)
                        // Zero unnamed refusals: every opaque element is covered by a note.
                        val opaque = result.document.elements.count { it is OpaqueShapeLodElement }
                        assertTrue(
                            opaque <= result.notes.size,
                            "${segment.tocEntry.segmentId}: $opaque opaque elements, ${result.notes.size} notes — a silent refusal",
                        )
                        assertArrayEquals(
                            elementData.toByteArray(),
                            result.document.encode(file.header.byteOrder).toByteArray(),
                            "${segment.tocEntry.segmentId}: encode(decode(shape body)) drifted",
                        )
                    }
                },
                dynamicTest("meta data / PMI bodies decode typed-or-noted and round-trip byte-identically") {
                    val file = JtFile.parse(bytes)
                    val decodable = file.metaDataSegments().filter { it.elementData != null }
                    assumeTrue(decodable.isNotEmpty(), "no decodable meta data / PMI segments in this fixture")
                    for (segment in decodable) {
                        val elementData = segment.elementData!!
                        val result = MetaDataDocument.decode(elementData, file.header.version, file.header.byteOrder)
                        // Zero unnamed refusals: every opaque element is covered by a note.
                        val opaque = result.document.elements.count { it is OpaqueMetaDataElement }
                        assertTrue(
                            opaque <= result.notes.size,
                            "${segment.tocEntry.segmentId}: $opaque opaque elements, ${result.notes.size} notes — a silent refusal",
                        )
                        assertArrayEquals(
                            elementData.toByteArray(),
                            result.document.encode(file.header.byteOrder).toByteArray(),
                            "${segment.tocEntry.segmentId}: encode(decode(meta data body)) drifted",
                        )
                    }
                },
                dynamicTest("decoded shape geometry is sane and consistent with the LSG") {
                    val file = JtFile.parse(bytes)
                    val generation = LsgGeneration.of(file.header.version)
                    val documents =
                        file.shapeLodSegments().mapNotNull { segment ->
                            file.decodeShapeLod(segment)?.let { segment to it.document }
                        }
                    val typed =
                        documents.mapNotNull { (segment, document) ->
                            val tri = document.triStripGeometry
                            val poly = document.polylineGeometry
                            if (tri == null && poly == null) null else Triple(segment, tri, poly)
                        }
                    assumeTrue(typed.isNotEmpty(), "no typed shape geometry in this fixture")

                    val lsg = file.decodeLsg()?.document
                    val partitionBox =
                        lsg?.graphElements?.filterIsInstance<PartitionNodeElement>()?.firstOrNull()
                            ?.let { it.untransformedBBox ?: it.transformedBBox }
                    val nodeBySegment = lsg?.let { shapeNodeBySegmentId(it) }.orEmpty()

                    fun checkVertices(
                        segment: JtSegment,
                        vertices: List<de.haumacher.kotlinjt.lsg.Vec3F32>,
                        nodeBox: de.haumacher.kotlinjt.lsg.BBoxF32?,
                    ) {
                        // Shape coordinates live in the owning node's local space; the
                        // partition box is a world-space fallback for files whose nodes
                        // carry no transforms (the shape node cannot be resolved).
                        val box = nodeBox ?: partitionBox
                        for (vertex in vertices) {
                            assertTrue(
                                vertex.x.isFinite() && vertex.y.isFinite() && vertex.z.isFinite(),
                                "${segment.tocEntry.segmentId}: non-finite coordinate $vertex",
                            )
                            val eps = 1e-3f
                            if (box != null) {
                                assertTrue(
                                    vertex.x >= box.min.x - eps && vertex.x <= box.max.x + eps &&
                                        vertex.y >= box.min.y - eps && vertex.y <= box.max.y + eps &&
                                        vertex.z >= box.min.z - eps && vertex.z <= box.max.z + eps,
                                    "${segment.tocEntry.segmentId}: $vertex outside its shape node's / partition's box $box",
                                )
                            }
                        }
                    }

                    for ((segment, tri, poly) in typed) {
                        val node = nodeBySegment[segment.tocEntry.segmentId]
                        val declared = node?.shape
                        if (tri != null) {
                            checkTriStripGeometry(segment, tri, generation, declared)
                            checkVertices(segment, tri.vertices, declared?.untransformedBBox)
                        }
                        if (poly != null) {
                            checkPolylineGeometry(segment, poly, declared)
                            checkVertices(segment, poly.vertices, declared?.untransformedBBox)
                        }
                    }
                },
                dynamicTest("primitive counts descend across each part's LOD tiers") {
                    val file = JtFile.parse(bytes)
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG segment in this fixture")
                    val nodeBySegment = shapeNodeBySegmentId(lsg!!)
                    val parentOf = parentByNodeId(lsg)
                    // Per LOD ancestor: the (tier, primitive count) pairs of its decoded LODs.
                    val groups = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
                    for (segment in file.shapeLodSegments()) {
                        val kind = segment.kind ?: continue
                        val tier = kind.code - SegmentKind.SHAPE_LOD0.code
                        if (tier < 0) continue
                        val node = nodeBySegment[segment.tocEntry.segmentId] ?: continue
                        val ancestor = lodAncestorOf(node.objectId, parentOf, lsg) ?: continue
                        val document = file.decodeShapeLod(segment)?.document ?: continue
                        // The comparable primitive count: triangles, or polyline corners.
                        val count =
                            document.triStripGeometry?.triangles?.size
                                ?: document.polylineGeometry?.polylines?.sumOf { it.vertexIndices.size }
                                ?: continue
                        groups.getOrPut(ancestor) { mutableListOf() }.add(tier to count)
                    }
                    // A tier is a Group Node and may hold many shapes — KR360-1.jt hangs all
                    // eleven of its coloured shapes off one tier. So the comparable quantity
                    // per tier is the SUM over its shapes, and a part with several shapes in
                    // one tier is not a duplicate-tier defect.
                    val byTier =
                        groups.mapValues { (_, entries) ->
                            entries.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
                        }
                    val multiTier = byTier.values.filter { it.size > 1 }.map { it.toList() }
                    assumeTrue(multiTier.isNotEmpty(), "no part with more than one decoded LOD tier in this fixture")
                    var checked = 0
                    for (tiers in multiTier) {
                        val sorted = tiers.sortedBy { it.first }
                        for (i in 1 until sorted.size) {
                            // Strictly descending — what the NIST data actually shows on
                            // every tier boundary of all 13 parts (issue #6).
                            assertTrue(
                                sorted[i].second < sorted[i - 1].second,
                                "LOD${sorted[i].first} has ${sorted[i].second} primitives, not fewer than " +
                                    "LOD${sorted[i - 1].first}'s ${sorted[i - 1].second}",
                            )
                            checked++
                        }
                    }
                    println("LOD DESCENT: ${multiTier.size} parts, $checked tier boundaries strictly descending")
                },
                dynamicTest("a model-level LSG mutation yields a legal, model-equal file") {
                    val file = JtFile.parse(bytes)
                    val segment = file.lsgSegment()
                    val decoded = file.decodeLsg()
                    assumeTrue(segment != null && decoded != null, "no decodable LSG segment in this fixture")
                    val document = decoded!!.document
                    val atomIndex = document.propertyAtoms.indexOfFirst { it is StringPropertyAtomElement }
                    assumeTrue(atomIndex >= 0, "no string property atom to mutate")
                    val atom = document.propertyAtoms[atomIndex] as StringPropertyAtomElement
                    val mutated =
                        document.copy(
                            propertyAtoms =
                                document.propertyAtoms.toMutableList()
                                    .also { it[atomIndex] = atom.copy(value = atom.value + "~probe") },
                        )
                    val payload =
                        encodeLsgSegmentPayload(
                            mutated.encode(file.header.byteOrder),
                            file.header.version,
                            file.header.byteOrder,
                        )
                    val newFile = file.withSegmentPayload(segment!!.tocEntry.segmentId, payload)
                    assertEquals(
                        file.notes.map { it.name },
                        newFile.notes.map { it.name },
                        "the mutated file must be as legal as the original",
                    )
                    val reDecoded = newFile.decodeLsg()
                    assertEquals(mutated, reDecoded?.document, "model equality after mutation + re-layout")
                    assertEquals(
                        decoded.notes.map { it.name },
                        reDecoded?.notes.orEmpty().map { it.name },
                    )
                    // Every other segment re-emits its raw payload untouched.
                    for ((before, after) in file.segments.zip(newFile.segments)) {
                        if (before.tocEntry.segmentId != segment.tocEntry.segmentId) {
                            assertEquals(before.payload, after.payload, "unmodified segment payload drifted")
                        }
                    }
                },
            ),
        )
    }
}
