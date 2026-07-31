package de.haumacher.kotlinjt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.security.MessageDigest

/**
 * The acceptance authority (issue #1 amendment): auto-discovers every `*.jt` under
 * `fixtures-local/` at the repository root and runs the standard battery — clean parse with
 * zero notes beyond the recorded expectation, inventory matching the sidecar
 * `<name>.expected.json` (created on first run for human review), and byte-identical
 * re-serialization.
 *
 * Fixture files are IP-encumbered customer data: they are gitignored, their names are never
 * written into committed test code, and the sidecars stay next to them, equally local.
 * When no fixtures exist the suite skips VISIBLY with a count — a skipped suite is not
 * silence.
 */
class FixtureDiscoveryTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @TestFactory
    fun localFixtures(): List<DynamicNode> {
        val fixturesDir = File(repoRoot(), "fixtures-local")
        val fixtures =
            fixturesDir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }
                ?.sortedBy { it.name }
                .orEmpty()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures under ${fixturesDir.path} — real-file suite SKIPPED (0 fixtures)") {
                    println("FIXTURE SUITE SKIPPED: 0 local fixtures in ${fixturesDir.path}")
                    assumeTrue(false, "no local fixtures present; the real-file acceptance suite did not run")
                },
            )
        }
        println("FIXTURE SUITE: ${fixtures.size} local fixture(s) discovered in ${fixturesDir.path}")
        return fixtures.map { fixture -> fixtureBattery(fixture) }
    }

    private fun fixtureBattery(fixture: File): DynamicNode {
        val bytes = fixture.readBytes()
        return dynamicContainer(
            fixture.name,
            listOf(
                dynamicTest("parses with zero unexpected notes") {
                    val file = JtFile.parse(bytes)
                    val sidecar = File(fixture.parentFile, fixture.name + ".expected.json")
                    if (sidecar.isFile) {
                        // The note names recorded in the sidecar are the expectation;
                        // anything beyond them is a regression.
                        val expectedNotes = Regex("\"noteNames\": \\[([^\\]]*)]").find(sidecar.readText())
                        val expected =
                            expectedNotes?.groupValues?.get(1)
                                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                                .orEmpty()
                        assertEquals(expected, file.notes.map { it.name }, "unexpected load notes: ${file.notes}")
                    } else {
                        assertEquals(
                            emptyList<String>(),
                            file.notes.map { it.name },
                            "first parse of a new fixture must be note-free (or record its notes in the sidecar)",
                        )
                    }
                },
                dynamicTest("inventory matches sidecar expectations") {
                    val file = JtFile.parse(bytes)
                    val json = file.inventoryJson(::sha256)
                    val sidecar = File(fixture.parentFile, fixture.name + ".expected.json")
                    if (!sidecar.isFile) {
                        sidecar.writeText(json)
                        println("SIDECAR CREATED for review: ${sidecar.path}")
                        println(file.inventory())
                    } else {
                        assertEquals(sidecar.readText(), json, "segment inventory drifted from ${sidecar.name}")
                    }
                },
                dynamicTest("re-serializes byte-identically") {
                    val file = JtFile.parse(bytes)
                    assertArrayEquals(bytes, file.serialize(), "Layer 0 losslessness violated")
                },
            ),
        )
    }
}
