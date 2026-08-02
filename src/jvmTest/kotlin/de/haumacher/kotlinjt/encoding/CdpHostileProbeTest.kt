package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Hostile-input probe for the two newly-added packet families.
 *
 * The delivered tests prove the codecs against the reference's own decoder source and, for
 * Mk. 1, differentially against 663 real Mk. 2 packets re-framed into the Mk. 1 layout. Both are
 * strong, and both feed the readers *well-formed* input. Nothing yet asks what happens when the
 * input is not well-formed — which is the state a new decoder actually meets first, because the
 * whole point of these two families is that no file in the corpus contains one, so the first
 * real packet may well arrive truncated, mis-framed, or belonging to the other generation.
 *
 * Two properties, on every packet the corpus can supply:
 *
 * 1. **Truncation is named, never thrown.** A reader that walks off the end of a bit stream is
 *    the classic way an exception escapes the public API. Every prefix of a real packet must
 *    either decode or raise [JtFormatException] — never an index, arithmetic or cast error.
 * The reverse cross-family check — Mk. 1 bytes offered to the Mk. 2 reader — is deliberately
 * absent rather than written as a skip: no file in the corpus contains a Mk. 1 or Float64 packet,
 * and a test that can only ever skip is noise dressed as coverage. The delivered
 * `CompressedDataPacketFixtureTest` covers the direction the corpus *can* supply (real Mk. 2
 * bytes refused by the Mk. 1 reader), and its auto-discovering corpus hook is what will fire the
 * reverse the day a real packet arrives.
 */
class CdpHostileProbeTest {
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

    /** The raw bytes of every shape-LOD element body in the file — packet-bearing by construction. */
    private fun packetBearingBodies(file: JtFile): List<ByteArray> = file.shapeLodSegments().mapNotNull { it.elementData?.toByteArray() }

    private fun assertNamedOrDecoded(
        label: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: JtFormatException) {
            return // named refusal: the contract
        } catch (e: Throwable) {
            throw AssertionError("$label: ${e::class.simpleName} escaped instead of a named JtFormatException", e)
        }
    }

    @TestFactory
    fun probes(): List<DynamicNode> =
        fixtures().flatMap { fixture ->
            val bytes = fixture.readBytes()
            listOf(
                // spec: 9.5 §8.1.1, §8.1.3 — a new decoder's first real input may be damaged
                dynamicTest("${fixture.name}: truncated bodies are refused by name by both new readers") {
                    val file = JtFile.parse(bytes)
                    val bodies = packetBearingBodies(file)
                    assumeTrue(bodies.isNotEmpty(), "no packet-bearing bodies in this fixture")
                    val order = file.header.byteOrder
                    for (body in bodies.take(6)) {
                        // Prefixes chosen to land inside every framing field of both layouts.
                        for (size in listOf(1, 2, 5, 9, 13, 21, body.size / 3, body.size - 1)) {
                            if (size <= 0 || size >= body.size) continue
                            val prefix = body.copyOf(size)
                            assertNamedOrDecoded("Mk1 on a $size-byte prefix") {
                                Int32CdpMk1.read(ByteReader(prefix, order))
                            }
                            assertNamedOrDecoded("Float64 on a $size-byte prefix") {
                                Float64Cdp.read(ByteReader(prefix, order))
                            }
                        }
                    }
                },
            )
        }
}
