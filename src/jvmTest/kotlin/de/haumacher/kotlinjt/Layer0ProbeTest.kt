package de.haumacher.kotlinjt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe review for the Layer 0 delivery (issue #2): damage sweeps over the real-producer
 * fixtures, composing the region model, the load notes, and the losslessness guarantee in
 * ways the implementation was not written against.
 *
 * The contract probed: for ANY input, [JtFile.parse] either throws [JtFormatException]
 * (the documented refusal for an unreadable header or TOC) or returns a file that
 * re-serializes byte-identically — hostile input included — and never stays silent about
 * damage. No other exception may escape the API.
 */
class Layer0ProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        File(repoRoot(), "fixtures-local")
            .listFiles { f -> f.isFile && f.name.endsWith(".jt") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Parse that only lets the documented refusal through; anything else fails the probe. */
    private fun parseOrRefuse(bytes: ByteArray): JtFile? =
        try {
            JtFile.parse(bytes)
        } catch (refusal: JtFormatException) {
            null
        }

    @TestFactory
    fun damageSweeps(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — Layer 0 probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no local fixtures present; the damage-sweep probe did not run")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicContainer(
                fixture.name,
                listOf(
                    truncationSweep(fixture.readBytes()),
                    byteFlipSweep(fixture.readBytes()),
                    compressedCorruptionSpeaks(fixture.readBytes()),
                ),
            )
        }
    }

    /**
     * Cut the file at boundary-straddling points and a prime-step sweep. Every prefix must
     * either be refused cleanly or round-trip byte-identically WITH at least one note —
     * a truncated file that parses silently would be a lie.
     */
    private fun truncationSweep(original: ByteArray): DynamicNode =
        dynamicTest("truncation sweep") {
            val cuts =
                (sortedSetOf(0, 1, 79, 80, 84, 85, 88, 104, original.size - 1) + (0 until original.size step 997)).filter {
                    it in 0 until original.size
                }
            var refused = 0
            var parsed = 0
            for (cut in cuts) {
                val prefix = original.copyOf(cut)
                val file =
                    parseOrRefuse(prefix) ?: run {
                        refused++
                        null
                    } ?: continue
                parsed++
                assertTrue(
                    file.notes.isNotEmpty(),
                    "a file truncated at $cut of ${original.size} bytes parsed without a single note — silence must mean success",
                )
                assertArrayEquals(prefix, file.serialize(), "truncation at $cut broke byte-identical re-serialization")
            }
            println("PROBE truncation: ${cuts.size} cuts — $refused refused cleanly, $parsed parsed with notes")
        }

    /**
     * Flip one byte in the middle of every segment's on-disk range. The mutated image must
     * parse without any exception and re-serialize to exactly the mutated bytes — Layer 0
     * must never silently normalize what it read.
     */
    private fun byteFlipSweep(original: ByteArray): DynamicNode =
        dynamicTest("segment byte-flip sweep") {
            val baseline = JtFile.parse(original)
            var flips = 0
            for (segment in baseline.segments) {
                val at = (segment.tocEntry.offset + segment.tocEntry.length / 2).toInt()
                val mutated = original.copyOf()
                mutated[at] = (mutated[at].toInt() xor 0x5A).toByte()
                val file = parseOrRefuse(mutated) ?: continue
                assertArrayEquals(
                    mutated,
                    file.serialize(),
                    "byte flip at $at (segment ${segment.tocEntry.segmentId}) broke byte-identical re-serialization",
                )
                flips++
            }
            assertTrue(flips > 0, "no segment survived parsing after mutation — sweep proved nothing")
            println("PROBE byte-flip: $flips mutated images round-tripped byte-identically")
        }

    /**
     * Corrupt the compressed body of every ZLIB segment. Refusals must speak: the mutated
     * parse must either carry a new note or decode to different element data — identical
     * silence over corrupted bytes would drop damage on the floor.
     */
    private fun compressedCorruptionSpeaks(original: ByteArray): DynamicNode =
        dynamicTest("compressed corruption speaks") {
            val baseline = JtFile.parse(original)
            val zlibSegments = baseline.segments.filter { it.compression?.algorithmCode == 2 }
            assumeTrue(zlibSegments.isNotEmpty(), "fixture has no ZLIB segment; corruption probe not applicable")
            for (segment in zlibSegments) {
                // 24-byte segment header + 9 compression fields, then the compressed body.
                val bodyStart = segment.tocEntry.offset.toInt() + 24 + 9
                val at = bodyStart + (segment.tocEntry.length.toInt() - 24 - 9) / 2
                val mutated = original.copyOf()
                mutated[at] = (mutated[at].toInt() xor 0x5A).toByte()
                val file = parseOrRefuse(mutated) ?: continue
                val decodedDiffers =
                    file.segments.first { it.tocEntry.segmentId == segment.tocEntry.segmentId }
                        .elementData?.toByteArray()?.contentEquals(segment.elementData!!.toByteArray()) == false
                assertTrue(
                    file.notes.map { it.name } != baseline.notes.map { it.name } || decodedDiffers,
                    "corrupted ZLIB body in segment ${segment.tocEntry.segmentId} " +
                        "parsed with unchanged notes and unchanged data — the damage vanished silently",
                )
                assertArrayEquals(mutated, file.serialize(), "corrupted ZLIB segment broke byte-identical re-serialization")
            }
            println("PROBE zlib-corruption: ${zlibSegments.size} segment(s) corrupted, damage acknowledged")
        }
}
