package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The real-file half of the guarded-vertex-binding work (issue #11): what the producers in
 * the corpus actually write for 9.5 Figures 30, 33, 34 and their v10 counterparts.
 *
 * The assertions are stated over element *types*, never over a file name — the local fixtures
 * are IP-encumbered and gitignored, so the suite auto-discovers them and skips visibly when
 * none are present.
 */
class Lsg95ShapeNodeFixtureTest {
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

    /** The four element types whose bodies carry Vertex Shape Data (9.5 Figs. 32–35). */
    private val vertexShapeNodeTypes =
        setOf(
            ObjectTypeIds.VERTEX_SHAPE_NODE,
            ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE,
            ObjectTypeIds.POLYLINE_SET_SHAPE_NODE,
            ObjectTypeIds.POINT_SET_SHAPE_NODE,
            ObjectTypeIds.POLYGON_SET_SHAPE_NODE,
        )

    @TestFactory
    fun shapeNodeGuardedFields(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — shape node guard suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present; the 9.5 shape node guard suite did not run")
                },
            )
        }
        return fixtures.map { fixture -> dynamicContainer(fixture.name, checks(fixture.readBytes())) }
    }

    private fun checks(bytes: ByteArray): List<DynamicNode> =
        listOf(
            // spec: 9.5 Figure 30
            // spec: 9.5 Figure 33
            // spec: 9.5 Figure 34
            dynamicTest("no shape node carrying Vertex Shape Data is refused") {
                val file = JtFile.parse(bytes)
                val data = file.lsgSegment()?.elementData
                assumeTrue(data != null, "no decodable LSG segment in this fixture")
                val result = LsgDocument.decode(data!!, file.header.version, file.header.byteOrder)
                val refused =
                    result.document.allElements.filterIsInstance<OpaqueLsgElement>()
                        .filter { it.objectTypeId in vertexShapeNodeTypes }
                assertEquals(
                    emptyList<OpaqueLsgElement>(),
                    refused,
                    "shape nodes carried opaquely; notes were ${result.notes}",
                )
            },
            // spec: 9.5 Figure 30
            // spec: 9.5 Figure 33
            // spec: 9.5 Figure 34
            dynamicTest("the guarded U64 fields are present exactly where the generation's figures draw them") {
                val file = JtFile.parse(bytes)
                val data = file.lsgSegment()?.elementData
                assumeTrue(data != null, "no decodable LSG segment in this fixture")
                val generation = LsgGeneration.of(file.header.version)
                val result = LsgDocument.decode(data!!, file.header.version, file.header.byteOrder)
                val vertexShapes =
                    result.document.graphElements.mapNotNull { element ->
                        when (element) {
                            is VertexShapeNodeElement -> element.vertexShape
                            is TriStripSetShapeNodeElement -> element.vertexShape
                            is PolylineSetShapeNodeElement -> element.vertexShape
                            is PointSetShapeNodeElement -> element.vertexShape
                            is PolygonSetShapeNodeElement -> element.vertexShape
                            else -> null
                        }
                    }
                assumeTrue(vertexShapes.isNotEmpty(), "no Vertex Shape Data in this fixture")
                for (shape in vertexShapes) {
                    if (generation == LsgGeneration.V9) {
                        // 9.5 Figure 30: Quantization Parameters are unconditional, and the
                        // second binding belongs to local version 1 — so from version 1 up,
                        // which every real 9.5 element satisfies.
                        assertNotNull(shape.quantizationParameters, "JT 9 Vertex Shape Data without Quantization Parameters")
                        assertNotNull(shape.vertexBindings2, "JT 9 Vertex Shape Data without Figure 30's second binding")
                        assertEquals(
                            shape.vertexBindings,
                            shape.vertexBindings2,
                            "Figure 30 draws the same field twice; the producers write the same value twice",
                        )
                    } else {
                        // v10 Figure 39 stops after the first binding.
                        assertNull(shape.quantizationParameters, "JT 10 Vertex Shape Data with Quantization Parameters")
                        assertNull(shape.vertexBindings2, "JT 10 Vertex Shape Data with a second binding")
                    }
                }
                for (element in result.document.graphElements) {
                    when (element) {
                        // 9.5 Figure 33 has the guarded U64; v10 Figure 40 has no such field.
                        is PolylineSetShapeNodeElement ->
                            if (generation == LsgGeneration.V9) {
                                assertNotNull(element.vertexBindings, "9.5 polyline set without Figure 33's guarded binding")
                            } else {
                                assertNull(element.vertexBindings, "v10 Figure 40 has no vertex bindings field")
                            }
                        // Both documents draw it for the point set.
                        is PointSetShapeNodeElement ->
                            assertNotNull(element.vertexBindings, "point set without the guarded binding")
                        else -> {}
                    }
                }
            },
            // spec: 9.5 Figure 33
            // spec: 9.5 Figure 34
            dynamicTest("every wireframe and point shape node reaches the model typed") {
                val file = JtFile.parse(bytes)
                val data = file.lsgSegment()?.elementData
                assumeTrue(data != null, "no decodable LSG segment in this fixture")
                val result = LsgDocument.decode(data!!, file.header.version, file.header.byteOrder)
                val framed =
                    result.document.allElements.count {
                        (it as? OpaqueLsgElement)?.objectTypeId == ObjectTypeIds.POLYLINE_SET_SHAPE_NODE ||
                            (it as? OpaqueLsgElement)?.objectTypeId == ObjectTypeIds.POINT_SET_SHAPE_NODE ||
                            it is PolylineSetShapeNodeElement || it is PointSetShapeNodeElement
                    }
                assumeTrue(framed > 0, "no polyline or point set shape nodes in this fixture")
                val typed =
                    result.document.graphElements.count { it is PolylineSetShapeNodeElement || it is PointSetShapeNodeElement }
                assertEquals(framed, typed, "some polyline / point set shape nodes did not decode typed")
                assertTrue(
                    result.notes.none { it.name == "ELEMENT_DECODE_FAILED" },
                    "typed shape nodes must not leave decode failures behind: ${result.notes}",
                )
            },
        )
}
