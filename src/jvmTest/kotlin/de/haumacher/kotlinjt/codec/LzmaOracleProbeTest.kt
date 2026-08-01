package de.haumacher.kotlinjt.codec

import de.haumacher.kotlinjt.JtFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files

/**
 * Probe review for the LZMA delivery (issue #5): an INDEPENDENT ORACLE. Every xz stream our
 * pure-Kotlin decoder inflates from a fixture is also decoded by the system `xz` binary
 * (liblzma — the implementation the spec itself names), and the outputs must match byte for
 * byte. A home-grown range decoder that merely satisfies its own hashes could still be wrong;
 * agreeing with liblzma on every real stream is the strongest correctness statement available.
 *
 * Skips visibly when no `xz` binary or no LZMA-bearing fixture is present.
 */
class LzmaOracleProbeTest {
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

    private fun xzAvailable(): Boolean =
        try {
            ProcessBuilder("xz", "--version").start().waitFor() == 0
        } catch (notInstalled: Exception) {
            false
        }

    private fun xzDecode(stream: ByteArray): ByteArray {
        val input = Files.createTempFile("probe", ".xz")
        try {
            Files.write(input, stream)
            val process =
                ProcessBuilder("xz", "--decompress", "--stdout", "--force", input.toString())
                    .redirectErrorStream(false)
                    .start()
            val out = process.inputStream.readBytes()
            assertTrue(process.waitFor() == 0, "the xz oracle itself refused a stream our decoder accepted")
            return out
        } finally {
            Files.deleteIfExists(input)
        }
    }

    @TestFactory
    fun oracle(): List<DynamicNode> {
        if (!xzAvailable()) {
            return listOf(
                dynamicTest("xz binary not installed — LZMA oracle probe SKIPPED") {
                    assumeTrue(false, "no xz binary; the liblzma oracle comparison did not run")
                },
            )
        }
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — LZMA oracle probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicTest("${fixture.name}: every decoded xz stream matches liblzma byte-for-byte") {
                val file = JtFile.parse(fixture.readBytes())
                val lzmaSegments =
                    file.segments.filter { it.compression?.algorithmCode == 3 && it.elementData != null }
                assumeTrue(lzmaSegments.isNotEmpty(), "no decoded LZMA segments in this fixture — oracle not applicable")
                var bytesCompared = 0L
                for (segment in lzmaSegments) {
                    val body =
                        segment.payload.slice(9, 9 + segment.compression!!.bodyLength).toByteArray()
                    val oracle = xzDecode(body)
                    val ours = segment.elementData!!.toByteArray()
                    assertArrayEquals(
                        oracle,
                        ours,
                        "segment ${segment.tocEntry.segmentId}: our decoder and liblzma disagree",
                    )
                    bytesCompared += ours.size
                }
                println("PROBE lzma-oracle: ${lzmaSegments.size} streams, $bytesCompared inflated bytes, all identical to liblzma")
            }
        }
    }
}
