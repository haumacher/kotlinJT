package de.haumacher.kotlinjt.wireframe

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.encoding.CompressedCurveData
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.TypedLsgElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The real-file acceptance for §10 (issue #10): every discovered fixture's Wireframe segments
 * decode typed with zero notes, re-encode byte-identically, and satisfy the cross-model checks
 * that make the layout *established* rather than plausible —
 *
 * - the declared Edge Count equals the length of both `VecI32` vectors, and every MCS curve index
 *   addresses a real curve;
 * - the control point vector holds exactly three coordinates per control point, the weight count
 *   equals the summed control point count of the rational curves, and the knot vector holds
 *   exactly the number of values Table 68's category formula predicts (all three are enforced by
 *   the decoder — this suite additionally asserts that the fixture really exercises them);
 * - §10.1.2's rule holds: with CAD Tag Data present there is one CAD tag per Edge;
 * - every Wireframe segment is referenced by a `JT_LLPROP_WFREP` late-loaded atom declaring
 *   segment type 18.
 *
 * Fixture files are never named in committed code (issue #1's fixture policy); a fixture without
 * Wireframe segments skips *visibly*.
 */
class WireframeFixtureTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    /** Every late-loaded reference of the LSG: segment GUID -> (key string, declared type). */
    private fun lateLoadedReferences(document: LsgDocument): Map<Guid, Pair<String, Int>> {
        val table = document.propertyTable ?: return emptyMap()
        val atomsById = document.propertyAtoms.filterIsInstance<TypedLsgElement>().associateBy { it.objectId }
        val result = mutableMapOf<Guid, Pair<String, Int>>()
        for (elementTable in table.tables) {
            for (entry in elementTable.entries) {
                val value = atomsById[entry.valuePropertyAtomObjectId]
                if (value !is LateLoadedPropertyAtomElement) continue
                val key = (atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement)?.value ?: continue
                result[value.segmentId] = key to value.segmentType
            }
        }
        return result
    }

    @TestFactory
    fun wireframeFixtures(): List<DynamicNode> {
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — §10 real-file suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture -> battery(fixture) }
    }

    private fun battery(fixture: File): DynamicNode {
        val bytes = fixture.readBytes()
        return dynamicContainer(
            fixture.name,
            listOf(
                // spec: Figure 103, Figure 104
                dynamicTest("wireframe segments decode typed with zero notes and round-trip byte-identically") {
                    val file = JtFile.parse(bytes)
                    val segments = file.wireframeSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable Wireframe segments in this fixture")
                    var reps = 0
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val result = file.decodeWireframe(segment)!!
                        assertEquals(emptyList<String>(), result.notes.map { it.name }, "$id: unexpected notes")
                        assertTrue(
                            result.document.elements.all { it is WireframeRepElement },
                            "$id: a wireframe element was carried opaquely",
                        )
                        reps += result.document.reps.size
                        // The Figure-78 empty property table every producer writes after the elements.
                        assertNotNull(result.document.propertyTable, "$id: no trailing Property Table")
                        assertEquals(0, result.document.propertyTable!!.tables.size, "$id: property table not empty")
                        assertEquals(0, result.document.trailing.size, "$id: bytes left over")
                        assertArrayEquals(
                            segment.elementData!!.toByteArray(),
                            result.document.encode(file.header.byteOrder).toByteArray(),
                            "$id: element-stream round-trip is not byte-identical",
                        )
                    }
                    assertEquals(segments.size, reps, "each Wireframe segment holds exactly one Wireframe Rep Element")
                },
                // spec: Figure 104, Figure 105, Figure 150
                dynamicTest("edge/curve/tag counts are internally coherent") {
                    val file = JtFile.parse(bytes)
                    val segments = file.wireframeSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable Wireframe segments in this fixture")
                    var totalEdges = 0
                    var totalCurves = 0
                    var rationalCurves = 0
                    var nonTrivialKnotCurves = 0
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        for (rep in file.decodeWireframe(segment)!!.document.reps) {
                            totalEdges += rep.edgeCount
                            totalCurves += rep.mcsCurveCount
                            assertEquals(rep.edgeCount, rep.mcsCurveIndices?.size ?: 0, "$id: MCS curve index count")
                            assertEquals(rep.edgeCount, rep.edgeTags?.size ?: 0, "$id: edge tag count")
                            for (index in rep.mcsCurveIndices?.values.orEmpty()) {
                                assertTrue(index in 0 until rep.mcsCurveCount, "$id: MCS curve index $index out of range")
                            }
                            if (rep.cadTagsFlag == 1u) {
                                // §10.1.2: "there will be a CAD Tag for every Edge in the Wireframe Rep".
                                assertEquals(rep.edgeCount, rep.cadTagData?.tagCount, "$id: one CAD tag per edge")
                                assertEquals(rep.edgeCount, rep.edgeCadTags.size, "$id: CAD tag list length")
                            }
                            val curves = rep.mcsCurves ?: continue
                            assertEquals(rep.mcsCurveCount, curves.curveCount, "$id: curve count")
                            assertEquals(
                                curves.controlPointCounts.values.sum() * 3,
                                curves.controlPoints.size,
                                "$id: three coordinates per control point",
                            )
                            for (type in curves.curveBaseTypes.values) {
                                assertEquals(CompressedCurveData.CURVE_BASE_TYPE_NURBS, type, "$id: curve base type")
                            }
                            for (curve in curves.curves) {
                                assertTrue(curve.degree >= 1, "$id: degree ${curve.degree}")
                                assertEquals(curve.controlPointCount, curve.weights.size, "$id: one weight per control point")
                                assertEquals(
                                    curve.controlPointCount * 3,
                                    curve.coordinates.size,
                                    "$id: coordinate count of a single curve",
                                )
                                if (curve.isRational) rationalCurves++
                                val knotType = curve.knotType
                                if (knotType != null) {
                                    nonTrivialKnotCurves++
                                    assertEquals(
                                        knotType.storedValueCount(curve.knotCount, curve.degree + 1),
                                        curve.storedKnotValues.size,
                                        "$id: stored knot value count of a single curve",
                                    )
                                }
                            }
                        }
                    }
                    assertTrue(totalEdges > 0 && totalCurves > 0, "the fixture carries no wireframe geometry at all")
                    // The evidence has to cover the interesting branches, not just the trivial ones.
                    assertTrue(rationalCurves > 0, "no rational curve exercised the weight collection")
                    assertTrue(nonTrivialKnotCurves > 0, "no curve exercised the non-trivial knot vector collection")
                },
                dynamicTest("every wireframe segment is referenced by a JT_LLPROP_WFREP atom") {
                    val file = JtFile.parse(bytes)
                    val segments = file.wireframeSegments()
                    assumeTrue(segments.isNotEmpty(), "no Wireframe segments in this fixture")
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG in this fixture")
                    val references = lateLoadedReferences(lsg!!)
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val reference = references[id]
                        assertNotNull(reference, "$id: no late-loaded reference points at this Wireframe segment")
                        assertEquals("JT_LLPROP_WFREP", reference!!.first, "$id: unexpected reference key")
                        assertEquals(18, reference.second, "$id: reference declares the wrong segment type")
                    }
                },
                dynamicTest("curve coordinates and weights are plausible for the model they belong to") {
                    val file = JtFile.parse(bytes)
                    val segments = file.wireframeSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable Wireframe segments in this fixture")
                    val partition =
                        file.decodeLsg()?.document?.graphElements
                            ?.filterIsInstance<de.haumacher.kotlinjt.lsg.PartitionNodeElement>()?.firstOrNull()
                    assumeTrue(partition != null, "no partition node to take a world box from")
                    // The declared extent, not the raw transformed slot (DESIGN.md delta 43).
                    val box = checkNotNull(partition!!.extentBBox) { "partition declares no extent box" }
                    // Wireframe reps are stored in part-local coordinates and the fixtures place
                    // parts with translations, so the check is a generous sanity bound: no
                    // coordinate may be wilder than ten times the model's own extent.
                    val extent =
                        maxOf(
                            (box.max.x - box.min.x).toDouble(),
                            (box.max.y - box.min.y).toDouble(),
                            (box.max.z - box.min.z).toDouble(),
                        )
                    assertTrue(extent > 0.0, "degenerate world bounding box")
                    for (segment in segments) {
                        for (rep in file.decodeWireframe(segment)!!.document.reps) {
                            val curves = rep.mcsCurves ?: continue
                            for (value in curves.controlPoints.values) {
                                assertTrue(
                                    value.isFinite() && kotlin.math.abs(value) <= 10.0 * extent,
                                    "${segment.tocEntry.segmentId}: control point coordinate $value is not plausible " +
                                        "for a model of extent $extent",
                                )
                            }
                            for (weight in curves.controlPointWeights.weights) {
                                assertTrue(weight > 0.0 && weight <= 1.0e6, "implausible NURBS weight $weight")
                            }
                        }
                    }
                },
            ),
        )
    }
}
