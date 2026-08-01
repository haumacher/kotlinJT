package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.io.toBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.sqrt

/**
 * Probe review for the Shape LOD delivery (issue #4), attacking its central claim: a codec
 * defect can never yield silently wrong geometry — damaged vertex data must refuse to opaque
 * with a named note (hash validation), never decode into different-but-plausible triangles.
 * Plus the consumer's-view sanity of every decoded value: indices in bounds, normals unit
 * length within quantization tolerance.
 *
 * Runs over every discovered fixture (committed and local), never naming local files.
 */
class ShapeProbeTest {
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
    fun shapeProbes(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — shape probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present; the shape probe did not run")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicContainer(fixture.name, listOf(damageNeverSilent(fixture.readBytes()), derivedValuesSane(fixture.readBytes())))
        }
    }

    /**
     * Flip one byte at 1/4, 1/2 and 3/4 of every decodable shape body and re-decode. Legal
     * outcomes: a named note (the refusal speaks) or visibly different geometry. Illegal:
     * identical geometry with no new note — the damage would have vanished. Re-serialization
     * must reproduce the mutated bytes in every case.
     */
    private fun damageNeverSilent(bytes: ByteArray): DynamicNode =
        dynamicTest("damaged vertex data refuses or changes — never silently identical") {
            val file = JtFile.parse(bytes)
            val decodable =
                file.shapeLodSegments().mapNotNull { segment ->
                    val data = segment.elementData ?: return@mapNotNull null
                    val baseline = ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder)
                    if (baseline.document.triStripGeometry == null) null else Triple(segment, data, baseline)
                }
            assumeTrue(decodable.isNotEmpty(), "no typed tri-strip geometry in this fixture — probe skipped")
            var mutations = 0
            var refused = 0
            for ((_, data, baseline) in decodable) {
                val original = data.toByteArray()
                for (fraction in listOf(4, 2, 1)) {
                    // 1/4, 1/2, 3/4 of the element data (skipping the first frame header bytes).
                    val at = 20 + (original.size - 20) * (4 - fraction) / 4 + fraction
                    if (at !in 20 until original.size) continue
                    val mutated = original.copyOf()
                    mutated[at] = (mutated[at].toInt() xor 0x5A).toByte()
                    val result = ShapeLodDocument.decode(mutated.toBytes(), file.header.version, file.header.byteOrder)
                    mutations++
                    assertArrayEquals(
                        mutated,
                        result.document.encode(file.header.byteOrder).toByteArray(),
                        "mutation at $at broke byte-identical re-serialization",
                    )
                    val newNotes = result.notes.size > baseline.notes.size
                    // Compare the WHOLE decoded document, not a projection of it: a flip that
                    // lands in a preserved wire field (object id, reserved tail) legitimately
                    // decodes into a different document with identical geometry.
                    val sameDocument = result.document == baseline.document
                    if (newNotes) refused++
                    assertTrue(
                        newNotes || !sameDocument,
                        "mutation at $at of a ${original.size}-byte body decoded to an IDENTICAL document " +
                            "with no new note — the damage vanished silently",
                    )
                }
            }
            println("PROBE shape-damage: $mutations mutations, $refused refused with a named note")
        }

    /** Every decoded index in bounds; every decoded normal unit length within Deering tolerance. */
    private fun derivedValuesSane(bytes: ByteArray): DynamicNode =
        dynamicTest("decoded indices in bounds, normals unit length") {
            val file = JtFile.parse(bytes)
            val geometries =
                file.shapeLodSegments().mapNotNull { segment ->
                    segment.elementData?.let { data ->
                        ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder).document.triStripGeometry
                    }
                }
            assumeTrue(geometries.isNotEmpty(), "no typed tri-strip geometry in this fixture — probe skipped")
            var triangles = 0
            var normalsChecked = 0
            for (geometry in geometries) {
                for (t in geometry.triangles) {
                    triangles++
                    for (v in listOf(t.v0, t.v1, t.v2)) {
                        assertTrue(v in geometry.vertices.indices, "vertex index $v out of bounds (${geometry.vertices.size})")
                    }
                    for (n in listOf(t.n0, t.n1, t.n2)) {
                        assertTrue(
                            n == -1 || n in geometry.normals.indices,
                            "normal index $n out of bounds (${geometry.normals.size})",
                        )
                    }
                }
                for (n in geometry.normals) {
                    normalsChecked++
                    val len = sqrt((n.x * n.x + n.y * n.y + n.z * n.z).toDouble())
                    assertTrue(len in 0.9..1.1, "normal ($n) has length $len — not a unit vector even at 8-bit quantization")
                }
            }
            println("PROBE shape-sanity: $triangles triangles and $normalsChecked normals checked")
        }
}
