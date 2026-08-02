package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Cross-generation probe for the 9.5 PMI element family.
 *
 * v10 deleted the typed-entity family, so 9.5 and v10 PMI are two different element families
 * sharing a segment type — and the corpus can only supply one of them. The delivered tests build
 * 9.5 bodies by hand and check them against the 9.5 reader; the risk they cannot see is the one
 * this project has now hit three times: **one generation's reader quietly absorbing the other's
 * bytes** because the field widths happen to line up.
 *
 * The NIST 10.5 fixture carries real PMI, so it can answer the half the corpus does have. A v10
 * PMI body offered to the 9.5 reader must be refused by name — never decoded into a 9.5 model
 * that looks plausible — and every prefix of it must be refused rather than throwing out of the
 * public API. Truncation matters more than usual here: the 9.5 element is a long chain of gated
 * collections, so a short body is exactly the shape a mis-read gate produces.
 */
class Pmi95GenerationProbeTest {
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
                // spec: 9.5 Figure 136 vs v10 Figure 110 — two families, one segment type
                dynamicTest("${fixture.name}: a v10 PMI body is never silently decoded as 9.5") {
                    val file = JtFile.parse(bytes)
                    assumeTrue(file.header.version.major >= 10, "this probe needs a v10 fixture")
                    val segments = file.metaDataSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable meta data segments")

                    val order = file.header.byteOrder
                    var checked = 0
                    for (segment in segments) {
                        val data = segment.elementData!!
                        val asV10 = MetaDataDocument.decode(data, file.header.version, order)
                        // Reading the same bytes as JT 9 must not produce a clean, different
                        // document: either it refuses by name, or — if it happens to frame —
                        // it must not claim to be typed 9.5 PMI.
                        val asV9 = MetaDataDocument.decode(data, JtVersion(9, 5), order)
                        checked++
                        val v9Typed = asV9.document.elements.count { it !is OpaqueMetaDataElement }
                        val v10Typed = asV10.document.elements.count { it !is OpaqueMetaDataElement }
                        if (v10Typed > 0 && v9Typed > 0) {
                            assertTrue(
                                asV9.notes.isNotEmpty(),
                                "${segment.tocEntry.segmentId}: v10 PMI decoded typed as 9.5 with no note — " +
                                    "the generations are absorbing each other",
                            )
                        }
                        // Whatever it decided, the bytes survive it.
                        assertEquals(
                            data.toByteArray().toList(),
                            asV9.document.encode(order).toByteArray().toList(),
                            "${segment.tocEntry.segmentId}: the JT 9 reading did not re-encode byte-identically",
                        )
                    }
                    assertTrue(checked > 0)
                },
                // spec: 9.5 Figure 136 — a chain of gated collections meets damage badly
                dynamicTest("${fixture.name}: truncated meta data bodies are named, never thrown") {
                    val file = JtFile.parse(bytes)
                    val segments = file.metaDataSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable meta data segments")
                    val order = file.header.byteOrder

                    for (segment in segments.take(4)) {
                        val body = segment.elementData!!.toByteArray()
                        for (size in listOf(1, 4, 17, 33, body.size / 2, body.size - 1)) {
                            if (size <= 0 || size >= body.size) continue
                            val prefix = de.haumacher.kotlinjt.io.Bytes.of(body.copyOf(size))
                            for (version in listOf(file.header.version, JtVersion(9, 5))) {
                                val result =
                                    try {
                                        MetaDataDocument.decode(prefix, version, order)
                                    } catch (e: Throwable) {
                                        throw AssertionError(
                                            "${segment.tocEntry.segmentId} truncated to $size as $version threw " +
                                                "${e::class.simpleName} through the public API: ${e.message}",
                                            e,
                                        )
                                    }
                                assertTrue(
                                    result.notes.isNotEmpty(),
                                    "${segment.tocEntry.segmentId} truncated to $size as $version decoded silently",
                                )
                                assertEquals(
                                    prefix.toByteArray().toList(),
                                    result.document.encode(order).toByteArray().toList(),
                                    "${segment.tocEntry.segmentId} truncated to $size as $version lost bytes",
                                )
                            }
                        }
                    }
                },
            )
        }
}
