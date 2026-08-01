package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.scene.readScene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probes for the 9.5 polyline and point set LOD bodies, composed across the seams the feature's
 * own tests do not cross.
 *
 * The delivered tests prove the codec against synthetic frames and against the fixtures' stored
 * hashes — both necessary, both inside Layer 1. These go outward instead:
 *
 * 1. **The consumer's view.** A wireframe body that decodes but never reaches the Layer 2
 *    façade has not fixed anything a caller can see. The scene is what the sibling project
 *    consumes, so the polylines must arrive there and the geometry-unavailable refusals for
 *    those segments must be gone.
 * 2. **Hostile input.** Every one of these bodies is now decoded rather than carried opaquely,
 *    which is exactly when a truncation stops being harmless. A short body must produce a named
 *    note, must keep its bytes, and must not throw through the public API.
 */
class ShapeLod95ProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local")).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
        }

    @TestFactory
    fun probes(): List<DynamicNode> =
        fixtures().flatMap { fixture ->
            val bytes = fixture.readBytes()
            listOf(
                // spec: 9.5 Figure 94, Figure 95 — decoded wireframe must reach the façade
                dynamicTest("${fixture.name}: every decoded polyline reaches the Layer 2 scene") {
                    val file = JtFile.parse(bytes)
                    // Layer 1 truth: how many polylines did the shape bodies yield?
                    val layer1 =
                        file.shapeLodSegments().sumOf { segment ->
                            file.decodeShapeLod(segment)?.document?.polylineGeometry?.polylines?.size ?: 0
                        }
                    assumeTrue(layer1 > 0, "no decoded polyline geometry in this fixture")

                    val scene = file.readScene()

                    fun countIn(node: de.haumacher.kotlinjt.scene.SceneNode): Int =
                        node.polylines.sumOf { it.lines.size } + node.children.sumOf { countIn(it) }
                    val layer2 = countIn(scene.root)

                    // The scene may still refuse *point* geometry — the Layer 2 model has no
                    // point concept yet, and that deferral is named rather than silent, which
                    // is the doctrine working. It must not widen: a refusal covering anything
                    // else means decoded geometry is being dropped at the façade.
                    val pointSegments =
                        file.shapeLodSegments()
                            .filter { file.decodeShapeLod(it)?.document?.pointGeometry != null }
                            .map { it.tocEntry.segmentId.toString() }
                            .toSet()
                    val unavailable = scene.notes.filter { it.name == "SCENE_GEOMETRY_UNAVAILABLE" }
                    for (note in unavailable) {
                        assertTrue(
                            pointSegments.any { note.message.contains(it) },
                            "geometry that Layer 1 decoded is refused at Layer 2 for something other " +
                                "than the point-set deferral: $note",
                        )
                    }
                    assertEquals(
                        layer1,
                        layer2,
                        "Layer 1 decoded $layer1 polylines but the scene shows $layer2 — the façade is dropping wireframe",
                    )
                },
                // spec: 9.5 Figure 94, Figure 95 — a decoded body is a body that can be truncated
                dynamicTest("${fixture.name}: a truncated wireframe body is refused by name, never thrown") {
                    val file = JtFile.parse(bytes)
                    val wireframe =
                        file.shapeLodSegments().filter { segment ->
                            val document = file.decodeShapeLod(segment)?.document
                            document?.polylineGeometry != null || document?.pointGeometry != null
                        }
                    assumeTrue(wireframe.isNotEmpty(), "no decoded polyline/point bodies in this fixture")

                    for (segment in wireframe) {
                        val original = segment.elementData!!.toByteArray()
                        // Two shapes of damage: a body one byte short, and one cut in half.
                        for (cut in listOf(1, original.size / 2)) {
                            val truncated = Bytes.of(original.copyOf(original.size - cut))
                            val result =
                                try {
                                    ShapeLodDocument.decode(truncated, file.header.version, file.header.byteOrder)
                                } catch (e: Throwable) {
                                    throw AssertionError(
                                        "${segment.tocEntry.segmentId} truncated by $cut threw ${e::class.simpleName}" +
                                            " through the public API: ${e.message}",
                                        e,
                                    )
                                }
                            assertTrue(
                                result.notes.isNotEmpty(),
                                "${segment.tocEntry.segmentId} truncated by $cut decoded silently — a silent partial load",
                            )
                            // Refusal must not cost bytes: what came in comes back out.
                            assertEquals(
                                truncated.toByteArray().toList(),
                                result.document.encode(file.header.byteOrder).toByteArray().toList(),
                                "${segment.tocEntry.segmentId} truncated by $cut did not re-encode byte-identically",
                            )
                        }
                    }
                },
            )
        }
}
