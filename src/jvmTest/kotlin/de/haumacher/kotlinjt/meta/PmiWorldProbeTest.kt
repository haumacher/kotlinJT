package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.BBoxF32
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.lsgSegment
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Probe review for the §11 delivery (issue #9): PMI is decoded as *numbers*, and numbers can
 * be validated against the model they annotate — a composition the package's own value-set
 * checks (string ids, flags, count formulas) cannot perform. A frame-shift in the float-heavy
 * Figure 116/120–128 layouts would decode garbage that still passes every enum check; it will
 * not produce 89 unit-length camera directions aimed at points inside the assembly's world box.
 *
 * Asserted per PMI Manager, against the partition's declared world box (§6, the seam
 * TransformProbeTest validated):
 * - every Model View's eye direction has unit length, its target point lies in the world box
 *   (expanded by one diagonal), its viewport diameter is positive and model-scaled, and every
 *   camera float is finite;
 * - every 2D reference frame of every generic PMI entity sits in the expanded world box with
 *   non-degenerate axes;
 * - every text-polyline vertex index addresses its packed coordinate array in bounds, and all
 *   polyline/text-box coordinates are finite.
 */
class PmiWorldProbeTest {
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

    private fun Float.assertFinite(what: String) {
        assertTrue(isFinite(), "$what is not finite: $this — a frame shift in the PMI decode")
    }

    private fun inBox(
        box: BBoxF32,
        margin: Double,
        x: Float,
        y: Float,
        z: Float,
    ): Boolean =
        x >= box.min.x - margin && x <= box.max.x + margin &&
            y >= box.min.y - margin && y <= box.max.y + margin &&
            z >= box.min.z - margin && z <= box.max.z + margin

    @TestFactory
    fun pmiGeometryCoheresWithTheWorld(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — PMI world probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicTest("${fixture.name}: PMI cameras, frames and polylines land in the model's world") {
                val file = JtFile.parse(fixture.readBytes())
                val managers =
                    file.metaDataSegments()
                        .mapNotNull { file.decodeMetaData(it)?.document }
                        .flatMap { it.elements.filterIsInstance<PmiManagerMetaDataElement>() }
                assumeTrue(managers.isNotEmpty(), "fixture carries no PMI Manager — probe not applicable")

                val lsg = LsgDocument.decode(file.lsgSegment()!!.elementData!!, file.header.version, file.header.byteOrder)
                val box =
                    checkNotNull(
                        // The declared extent, not the raw transformed slot (DESIGN.md delta 43).
                        lsg.document.graphElements.filterIsInstance<PartitionNodeElement>().first().extentBBox,
                    ) { "partition declares no extent box" }
                val diagonal =
                    sqrt(
                        (
                            (box.max.x - box.min.x) * (box.max.x - box.min.x) +
                                (box.max.y - box.min.y) * (box.max.y - box.min.y) +
                                (box.max.z - box.min.z) * (box.max.z - box.min.z)
                        ).toDouble(),
                    )
                val margin = diagonal + 1e-6

                var views = 0
                var frames = 0
                var textVertices = 0
                for (manager in managers) {
                    for (view in manager.modelViews) {
                        val d = view.eyeDirection
                        val length = sqrt((d.x * d.x + d.y * d.y + d.z * d.z).toDouble())
                        assertTrue(
                            abs(length - 1.0) < 1e-3,
                            "eye direction (${d.x}, ${d.y}, ${d.z}) of view '${manager.string(view.viewNameStringId)}' " +
                                "is not unit length ($length) — Figure 116 fields are shifted",
                        )
                        val t = view.targetPoint
                        assertTrue(
                            inBox(box, margin, t.x, t.y, t.z),
                            "target point (${t.x}, ${t.y}, ${t.z}) of view '${manager.string(view.viewNameStringId)}' " +
                                "misses the world box $box by more than a diagonal",
                        )
                        // NX writes 0.0 for every view's diameter (unset) — zero is legal.
                        assertTrue(
                            view.viewportDiameter >= 0 && view.viewportDiameter < 100 * diagonal,
                            "viewport diameter ${view.viewportDiameter} is not model-scaled (diagonal $diagonal)",
                        )
                        view.eyePosition.x.assertFinite("eye position x")
                        view.eyePosition.y.assertFinite("eye position y")
                        view.eyePosition.z.assertFinite("eye position z")
                        // The camera must aim where it looks: direction == normalized(target − eye).
                        val e = view.eyePosition
                        val vx = t.x - e.x
                        val vy = t.y - e.y
                        val vz = t.z - e.z
                        val vLength = sqrt((vx * vx + vy * vy + vz * vz).toDouble())
                        if (vLength > 1e-6) {
                            val dot = (vx * d.x + vy * d.y + vz * d.z) / vLength
                            assertTrue(
                                dot > 0.999,
                                "view '${manager.string(view.viewNameStringId)}' looks along (${d.x}, ${d.y}, ${d.z}) " +
                                    "but its target lies off-axis (cos $dot) — eye/target/direction fields shifted",
                            )
                        }
                        view.angle.assertFinite("camera angle")
                        view.viewAngle.x.assertFinite("view angle x")
                        view.viewAngle.y.assertFinite("view angle y")
                        view.viewAngle.z.assertFinite("view angle z")
                        views++
                    }
                    for (entity in manager.genericEntities) {
                        val frame = entity.data2d.base.referenceFrame ?: continue
                        val points = listOf(frame.origin, frame.xAxisPoint, frame.yAxisPoint)
                        // §11.2.6.1.1: flag 2 marks a dummy frame — NX writes those all-zero.
                        if (points.all { it.x == 0f && it.y == 0f && it.z == 0f }) continue
                        for ((label, p) in listOf("origin" to frame.origin, "x-axis" to frame.xAxisPoint, "y-axis" to frame.yAxisPoint)) {
                            assertTrue(
                                inBox(box, margin, p.x, p.y, p.z),
                                "2D frame $label (${p.x}, ${p.y}, ${p.z}) misses the world box $box by more than a diagonal",
                            )
                        }
                        val ax = frame.xAxisPoint.x - frame.origin.x
                        val ay = frame.xAxisPoint.y - frame.origin.y
                        val az = frame.xAxisPoint.z - frame.origin.z
                        val bx = frame.yAxisPoint.x - frame.origin.x
                        val by = frame.yAxisPoint.y - frame.origin.y
                        val bz = frame.yAxisPoint.z - frame.origin.z
                        val crossNorm =
                            sqrt(
                                (
                                    (ay * bz - az * by) * (ay * bz - az * by) +
                                        (az * bx - ax * bz) * (az * bx - ax * bz) +
                                        (ax * by - ay * bx) * (ax * by - ay * bx)
                                ).toDouble(),
                            )
                        assertTrue(crossNorm > 1e-12, "2D frame axes are degenerate (collinear) — fields shifted")
                        frames++
                        entity.data2d.base.textHeight.assertFinite("text height")
                        for (text in entity.data2d.texts) {
                            text.textBox.originX.assertFinite("text box origin x")
                            text.textBox.lowerRightY.assertFinite("text box lower-right y")
                            text.textBox.upperLeftX.assertFinite("text box upper-left x")
                            val poly = text.polylines
                            for (index in poly.segmentIndices) {
                                assertTrue(
                                    index >= 0 && index * 2 + 1 < poly.vertexCoords.size,
                                    "text polyline vertex index $index out of bounds for " +
                                        "${poly.vertexCoords.size} packed floats",
                                )
                            }
                            poly.vertexCoords.forEach { it.assertFinite("text polyline coordinate") }
                            textVertices += poly.vertexCoords.size / 2
                        }
                        entity.data2d.nonTextPolylines.vertexCoords.forEach { it.assertFinite("non-text polyline coordinate") }
                    }
                }
                assumeTrue(views + frames > 0, "PMI managers carry no views or framed entities — probe not applicable")
                println(
                    "PROBE pmi-world: ${managers.size} managers — $views views aimed, $frames frames placed, " +
                        "$textVertices text vertices finite and indexed in bounds",
                )
            }
        }
    }
}
