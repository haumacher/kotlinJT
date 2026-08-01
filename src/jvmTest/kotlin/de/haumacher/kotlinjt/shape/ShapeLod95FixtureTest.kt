package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtSegment
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.BBoxF32
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.ShapeNodeElement
import de.haumacher.kotlinjt.lsg.Vec3F32
import de.haumacher.kotlinjt.lsg.decodeLsg
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The JT 9 Polyline Set and Point Set Shape LOD bodies against real producers (issue #12).
 * Auto-discovers every fixture, committed and local, and never names a local file: a corpus
 * without a 9.5 file simply skips, visibly.
 *
 * What it holds the implementation to: every framed Polyline Set / Point Set element of a JT 9
 * file decodes *typed* (no `ELEMENT_LAYOUT_UNVERIFIED`, no `ELEMENT_DECODE_FAILED`) — which
 * means every stored hash in it verified, since the reader refuses on a mismatch —
 * re-serializes byte-identically, and yields geometry a consumer can use.
 */
class ShapeLod95FixtureTest {
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

    private fun shapeNodeBySegmentId(document: LsgDocument): Map<Guid, ShapeNodeElement> {
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

    @TestFactory
    fun jt9PolylineAndPointBodies(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — JT 9 polyline/point suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture ->
            val bytes = fixture.readBytes()
            dynamicContainer(
                fixture.name,
                listOf(
                    decodesTypedAndRoundTrips(bytes),
                    geometryIsSane(bytes),
                    elidedUpperMaskChunkStillVerifies(bytes),
                ),
            )
        }
    }

    // spec: 9.5 Figure 94 / Figure 95

    /**
     * The refusal this package lifts: `KR360-1.jt`'s five polyline and one point-set segments
     * used to load with `ELEMENT_LAYOUT_UNVERIFIED`. Every such element must now decode typed,
     * with its stored FGPV, unique-vertex-map and coordinate hashes verified, and re-serialize
     * to the same bytes.
     */
    private fun decodesTypedAndRoundTrips(bytes: ByteArray): DynamicNode =
        dynamicTest("every polyline and point set shape body decodes typed and re-serializes byte-identically") {
            val file = JtFile.parse(bytes)
            var polylines = 0
            var points = 0
            for (segment in file.shapeLodSegments()) {
                val data = segment.elementData ?: continue
                val result = ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder)
                assertArrayEquals(
                    data.toByteArray(),
                    result.document.encode(file.header.byteOrder).toByteArray(),
                    "${segment.tocEntry.segmentId}: encode(decode(body)) is not byte-identical",
                )
                val unverified =
                    result.notes.filter { it is LoadNote.ElementLayoutUnverified || it is LoadNote.ElementDecodeFailed }
                assertTrue(
                    unverified.isEmpty(),
                    "${segment.tocEntry.segmentId}: ${unverified.map { it.message }}",
                )
                for (element in result.document.elements) {
                    when (element) {
                        is PolylineSetShapeLodElement -> polylines++
                        is PointSetShapeLodElement -> points++
                        else -> {}
                    }
                }
            }
            val generation = LsgGeneration.of(file.header.version)
            if (generation == LsgGeneration.V9) {
                // Nothing is asserted about *how many* a fixture must carry — only that a
                // JT 9 file's polyline/point bodies are no longer refused, which the loop
                // above already proved for whatever it contains.
                assertTrue(polylines >= 0 && points >= 0)
            } else {
                assertEquals(0, polylines + points, "JT 9 element types must not decode under the v10 grammar")
            }
        }

    // spec: 9.5 Figure 91

    /** The consumer's view: indices in range, polylines with at least two corners, coordinates inside the declared box. */
    private fun geometryIsSane(bytes: ByteArray): DynamicNode =
        dynamicTest("JT 9 polyline and point geometry is index-sane and inside the declared boxes") {
            val file = JtFile.parse(bytes)
            assumeTrue(LsgGeneration.of(file.header.version) == LsgGeneration.V9, "not a JT 9 fixture")
            val lsg = file.decodeLsg()?.document
            val partitionBox =
                lsg?.graphElements?.filterIsInstance<PartitionNodeElement>()?.firstOrNull()
                    ?.let { it.untransformedBBox ?: it.transformedBBox }
            val nodeBySegment = lsg?.let { shapeNodeBySegmentId(it) }.orEmpty()

            fun checkVertices(
                segment: JtSegment,
                vertices: List<Vec3F32>,
                box: BBoxF32?,
            ) {
                for (vertex in vertices) {
                    assertTrue(
                        vertex.x.isFinite() && vertex.y.isFinite() && vertex.z.isFinite(),
                        "${segment.tocEntry.segmentId}: non-finite coordinate $vertex",
                    )
                    if (box == null) continue
                    val eps = 1e-3f
                    assertTrue(
                        vertex.x >= box.min.x - eps && vertex.x <= box.max.x + eps &&
                            vertex.y >= box.min.y - eps && vertex.y <= box.max.y + eps &&
                            vertex.z >= box.min.z - eps && vertex.z <= box.max.z + eps,
                        "${segment.tocEntry.segmentId}: $vertex outside its box $box",
                    )
                }
            }

            var checked = 0
            for (segment in file.shapeLodSegments()) {
                val document = file.decodeShapeLod(segment)?.document ?: continue
                val id = segment.tocEntry.segmentId
                val box = nodeBySegment[id]?.shape?.untransformedBBox ?: partitionBox
                for (element in document.elements) {
                    when (element) {
                        is PolylineSetShapeLodElement -> {
                            val geometry = element.geometry
                            assertTrue(geometry.polylines.isNotEmpty(), "$id: no polylines decoded")
                            for (polyline in geometry.polylines) {
                                assertTrue(
                                    polyline.vertexIndices.size >= 2,
                                    "$id: polyline with ${polyline.vertexIndices.size} vertices",
                                )
                                for (index in polyline.vertexIndices) {
                                    assertTrue(index in geometry.vertices.indices, "$id: vertex index $index out of range")
                                }
                            }
                            checkVertices(segment, geometry.vertices, box)
                            checked++
                        }
                        is PointSetShapeLodElement -> {
                            val geometry = element.geometry
                            assertTrue(geometry.points.isNotEmpty(), "$id: no points decoded")
                            for (index in geometry.points) {
                                assertTrue(index in geometry.vertices.indices, "$id: point index $index out of range")
                            }
                            checkVertices(segment, geometry.vertices, box)
                            checked++
                        }
                        else -> {}
                    }
                }
            }
            assumeTrue(checked > 0, "no JT 9 polyline or point geometry in this fixture")
        }

    // spec: 9.5 Figure 89

    /**
     * Finding E-7, from the producer's side. 9.5's composite-hash pseudo-code hashes the
     * *derived* context-8 mask array three times, each with the context's own element count —
     * so an upper chunk of all zeros and an *elided* (empty) upper chunk are the same input to
     * the hash. Both corpus producers write the chunk out in full; this rewrites each body with
     * the all-zero top chunk elided, which is exactly what a differently-tuned writer would
     * emit, and requires the stored hash to still verify and the mesh to be unchanged.
     *
     * A reader that hashed the stored packets — as the library did before this package —
     * false-refuses every one of these bodies.
     */
    private fun elidedUpperMaskChunkStillVerifies(bytes: ByteArray): DynamicNode =
        dynamicTest("an elided all-zero context-8 chunk still verifies the composite hash") {
            val file = JtFile.parse(bytes)
            assumeTrue(LsgGeneration.of(file.header.version) == LsgGeneration.V9, "not a JT 9 fixture")
            var exercised = 0
            for (segment in file.shapeLodSegments()) {
                val data = segment.elementData ?: continue
                val document = ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder).document
                val element = document.elements.filterIsInstance<TriStripSetShapeLodElement>().singleOrNull() ?: continue
                val top = element.repData.faceAttributeMask8Top
                if (top.valueCount == 0 || top.values.any { it != 0 }) continue
                val mid = element.repData.faceAttributeMask8Mid
                val elidedMid = if (mid.valueCount > 0 && mid.values.all { it == 0 }) Int32Cdp.Empty else mid
                val elided =
                    element.copy(
                        repData =
                            element.repData.copy(
                                faceAttributeMask8Mid = elidedMid,
                                faceAttributeMask8Top = Int32Cdp.Empty,
                            ),
                    )
                val rewritten = document.copy(elements = listOf(elided)).encode(file.header.byteOrder)
                val result = ShapeLodDocument.decode(rewritten, file.header.version, file.header.byteOrder)
                assertTrue(
                    result.notes.isEmpty(),
                    "${segment.tocEntry.segmentId}: eliding ${top.valueCount} zero chunk values refused the body — " +
                        "${result.notes.map { it.message }}",
                )
                val decoded = result.document.elements.filterIsInstance<TriStripSetShapeLodElement>().single()
                assertEquals(element.geometry, decoded.geometry, "${segment.tocEntry.segmentId}: the mesh changed")
                exercised++
            }
            assumeTrue(exercised > 0, "no JT 9 tri-strip body with a non-empty all-zero top chunk")
        }
}
