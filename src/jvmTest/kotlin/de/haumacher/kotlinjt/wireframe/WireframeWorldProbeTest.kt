package de.haumacher.kotlinjt.wireframe

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.lsgSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.sqrt

/**
 * Probe review for the §8–§10 delivery (issue #10): the wireframe curves are decoded as
 * *numbers*, and the package's own count identities (edge counts, weight counts, knot-length
 * formulas) cannot see a frame shift inside the F64 payloads — valid-looking counts can frame
 * garbage floats. NURBS mathematics and the model's own world can:
 *
 * - a NURBS curve needs at least `degree + 1` control points, strictly positive finite
 *   weights, and a non-decreasing knot sequence;
 * - the curve lies in the convex hull of its control points, so every control point of a
 *   model-coordinate-space curve must land inside the partition's declared world box
 *   (expanded by one diagonal) — the same §6 seam the transform, PMI and writer probes stand on;
 * - every Edge's curve index must address the curve list in bounds, and the per-Edge vectors
 *   (tags, CAD tags) must agree with the declared Edge Count.
 */
class WireframeWorldProbeTest {
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

    @TestFactory
    fun wireframeCurvesAreValidNurbsInTheModelsWorld(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — wireframe probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicTest("${fixture.name}: every wireframe curve is a valid NURBS inside the world box") {
                val file = JtFile.parse(fixture.readBytes())
                val reps =
                    file.wireframeSegments()
                        .mapNotNull { file.decodeWireframe(it)?.document }
                        .flatMap { it.reps }
                assumeTrue(reps.isNotEmpty(), "fixture carries no wireframe reps — probe not applicable")

                val lsg = LsgDocument.decode(file.lsgSegment()!!.elementData!!, file.header.version, file.header.byteOrder)
                val box = lsg.document.graphElements.filterIsInstance<PartitionNodeElement>().first().transformedBBox
                val diagonal =
                    sqrt(
                        (
                            (box.max.x - box.min.x) * (box.max.x - box.min.x) +
                                (box.max.y - box.min.y) * (box.max.y - box.min.y) +
                                (box.max.z - box.min.z) * (box.max.z - box.min.z)
                        ).toDouble(),
                    )
                val margin = diagonal + 1e-6

                var curvesChecked = 0
                var pointsChecked = 0
                var edgesChecked = 0
                for (rep in reps) {
                    val curves = rep.curves
                    assertEquals(rep.mcsCurveCount, curves.size, "curve list disagrees with the declared MCS Curve Count")
                    for ((index, curve) in curves.withIndex()) {
                        assertTrue(curve.degree >= 1, "curve $index has degree ${curve.degree}")
                        assertTrue(
                            curve.controlPointCount >= curve.degree + 1,
                            "curve $index (degree ${curve.degree}) has only ${curve.controlPointCount} control points — " +
                                "a NURBS needs at least degree + 1",
                        )
                        assertEquals(
                            curve.controlPointCount * 3,
                            curve.coordinates.size,
                            "curve $index is not 3-dimensional",
                        )
                        for (weight in curve.weights) {
                            assertTrue(
                                weight.isFinite() && weight > 0.0,
                                "curve $index carries a non-positive or non-finite weight $weight",
                            )
                        }
                        var previous = Double.NEGATIVE_INFINITY
                        for (knot in curve.storedKnotValues) {
                            assertTrue(knot.isFinite(), "curve $index carries a non-finite knot $knot")
                            assertTrue(
                                knot >= previous,
                                "curve $index has a decreasing knot sequence ($knot after $previous) — " +
                                    "the F64 payload is frame-shifted",
                            )
                            previous = knot
                        }
                        var i = 0
                        while (i < curve.coordinates.size) {
                            val x = curve.coordinates[i]
                            val y = curve.coordinates[i + 1]
                            val z = curve.coordinates[i + 2]
                            assertTrue(
                                x.isFinite() && y.isFinite() && z.isFinite(),
                                "curve $index control point ($x, $y, $z) is not finite",
                            )
                            assertTrue(
                                x >= box.min.x - margin && x <= box.max.x + margin &&
                                    y >= box.min.y - margin && y <= box.max.y + margin &&
                                    z >= box.min.z - margin && z <= box.max.z + margin,
                                "curve $index control point ($x, $y, $z) escapes the world box $box by more " +
                                    "than a diagonal — MCS curves must lie in model space",
                            )
                            pointsChecked++
                            i += 3
                        }
                        curvesChecked++
                    }
                    val indices = rep.mcsCurveIndices?.values ?: emptyList()
                    assertEquals(rep.edgeCount, indices.size, "Edge count disagrees with the MCS Curve Indices vector")
                    for (curveIndex in indices) {
                        assertTrue(
                            curveIndex in 0 until rep.mcsCurveCount,
                            "edge references curve $curveIndex of ${rep.mcsCurveCount}",
                        )
                        edgesChecked++
                    }
                    val tags = rep.edgeTags?.values ?: emptyList()
                    assertEquals(rep.edgeCount, tags.size, "Edge count disagrees with the Edge Tags vector")
                    if (rep.edgeCadTags.isNotEmpty()) {
                        assertEquals(rep.edgeCount, rep.edgeCadTags.size, "CAD tags disagree with the Edge count")
                    }
                }
                assumeTrue(curvesChecked > 0, "wireframe reps carry no curves — probe not applicable")
                println(
                    "PROBE wireframe-world: ${reps.size} reps — $curvesChecked NURBS curves valid, " +
                        "$pointsChecked control points inside the world box, $edgesChecked edges indexed in bounds",
                )
            }
        }
    }
}
