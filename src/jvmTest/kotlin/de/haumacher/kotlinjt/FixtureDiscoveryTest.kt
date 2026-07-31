package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.OpaqueLsgElement
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.lsg.encodeLsgSegmentPayload
import de.haumacher.kotlinjt.lsg.lsgSegment
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        // Both fixture tiers run the same battery: the committed public spine under
        // `fixtures/` and the IP-encumbered local suite under `fixtures-local/`.
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures under ${directories.joinToString { it.path }} — real-file suite SKIPPED (0 fixtures)") {
                    println("FIXTURE SUITE SKIPPED: 0 fixtures in ${directories.joinToString { it.path }}")
                    assumeTrue(false, "no fixtures present; the real-file acceptance suite did not run")
                },
            )
        }
        println("FIXTURE SUITE: ${fixtures.size} fixture(s) discovered in ${directories.joinToString { it.path }}")
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
                dynamicTest("LSG decodes typed-or-noted and round-trips byte-identically") {
                    val file = JtFile.parse(bytes)
                    val elementData = file.lsgSegment()?.elementData
                    assumeTrue(elementData != null, "no decodable LSG segment in this fixture")
                    val result = LsgDocument.decode(elementData!!, file.header.version, file.header.byteOrder)
                    // Zero unnamed refusals: every opaque element is covered by a note.
                    val opaque = result.document.allElements.count { it is OpaqueLsgElement }
                    assertTrue(
                        opaque <= result.notes.size,
                        "$opaque opaque elements but only ${result.notes.size} notes — a silent refusal",
                    )
                    assertArrayEquals(
                        elementData.toByteArray(),
                        result.document.encode(file.header.byteOrder).toByteArray(),
                        "Layer 1 losslessness violated: encode(decode(elementStream)) drifted",
                    )
                },
                dynamicTest("a model-level LSG mutation yields a legal, model-equal file") {
                    val file = JtFile.parse(bytes)
                    val segment = file.lsgSegment()
                    val decoded = file.decodeLsg()
                    assumeTrue(segment != null && decoded != null, "no decodable LSG segment in this fixture")
                    val document = decoded!!.document
                    val atomIndex = document.propertyAtoms.indexOfFirst { it is StringPropertyAtomElement }
                    assumeTrue(atomIndex >= 0, "no string property atom to mutate")
                    val atom = document.propertyAtoms[atomIndex] as StringPropertyAtomElement
                    val mutated =
                        document.copy(
                            propertyAtoms =
                                document.propertyAtoms.toMutableList()
                                    .also { it[atomIndex] = atom.copy(value = atom.value + "~probe") },
                        )
                    val payload =
                        encodeLsgSegmentPayload(
                            mutated.encode(file.header.byteOrder),
                            file.header.version,
                            file.header.byteOrder,
                        )
                    val newFile = file.withSegmentPayload(segment!!.tocEntry.segmentId, payload)
                    assertEquals(
                        file.notes.map { it.name },
                        newFile.notes.map { it.name },
                        "the mutated file must be as legal as the original",
                    )
                    val reDecoded = newFile.decodeLsg()
                    assertEquals(mutated, reDecoded?.document, "model equality after mutation + re-layout")
                    assertEquals(
                        decoded.notes.map { it.name },
                        reDecoded?.notes.orEmpty().map { it.name },
                    )
                    // Every other segment re-emits its raw payload untouched.
                    for ((before, after) in file.segments.zip(newFile.segments)) {
                        if (before.tocEntry.segmentId != segment.tocEntry.segmentId) {
                            assertEquals(before.payload, after.payload, "unmodified segment payload drifted")
                        }
                    }
                },
            ),
        )
    }
}
