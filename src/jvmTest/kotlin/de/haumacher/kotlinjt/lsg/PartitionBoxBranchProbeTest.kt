package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe for 9.5 Figure 14's partition box **branch** — the arm no fixture takes.
 *
 * Every file in the corpus sets Partition Flags bit 0, so the middle box is always the Reserved
 * Field and the `(flags & 1) == 0` arm — the one where that same slot *is* the Transformed BBox
 * — is never exercised. That arm is precisely where the identity swap this package fixed lives,
 * so a test that only sees the taken branch cannot distinguish the fix from the bug it replaced.
 *
 * These drive the untaken arm by flipping the flag on a real producer's partition and pushing it
 * back through encode → decode, so the branch is exercised with genuine neighbouring bytes
 * rather than a hand-built frame. They also pin the invariant across the generation seam, which
 * a single-generation test cannot see.
 */
class PartitionBoxBranchProbeTest {
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

    private fun BBoxF32.isFinite(): Boolean = listOf(min.x, min.y, min.z, max.x, max.y, max.z).all { it.isFinite() }

    @TestFactory
    fun probes(): List<DynamicNode> =
        fixtures().flatMap { fixture ->
            val bytes = fixture.readBytes()
            listOf(
                // spec: 9.5 Figure 14 — exactly one of the two boxes is on the wire
                dynamicTest("${fixture.name}: the partition carries exactly one middle box, and it is the documented one") {
                    val file = JtFile.parse(bytes)
                    val decoded = file.decodeLsg()
                    assumeTrue(decoded != null, "no decodable LSG segment")
                    val partition =
                        decoded!!.document.graphElements.filterIsInstance<PartitionNodeElement>().singleOrNull()
                    assumeTrue(partition != null, "no single partition node")

                    val reserved = partition!!.reservedBBox
                    val transformed = partition.transformedBBox
                    assertTrue(
                        (reserved == null) != (transformed == null),
                        "exactly one of reservedBBox / transformedBBox must be set, got " +
                            "reserved=$reserved transformed=$transformed",
                    )
                    if (file.header.version.major >= 10) {
                        // v10 Figure 23 has no reserved field at all.
                        assertNull(reserved, "a v10 partition must not carry a reserved box")
                        assertNotNull(transformed, "a v10 partition's middle box is the transformed box")
                    } else {
                        val bitZeroSet = (partition.partitionFlags and 1) != 0
                        assertEquals(
                            bitZeroSet,
                            reserved != null,
                            "9.5 Fig. 14: the middle box is the Reserved Field exactly when bit 0 is set " +
                                "(flags=${partition.partitionFlags})",
                        )
                    }
                    // Whichever box the file actually declares its extent with must be usable —
                    // the sentinel that used to occupy this field made every downstream
                    // containment check vacuous rather than failing.
                    val extent = partition.transformedBBox ?: partition.untransformedBBox
                    if (extent != null) {
                        assertTrue(extent.isFinite(), "the declared extent box is not finite: $extent")
                    }
                },
                // spec: 9.5 Figure 14 — the arm the corpus never takes
                dynamicTest("${fixture.name}: clearing Partition Flags bit 0 moves the box to the transformed slot") {
                    val file = JtFile.parse(bytes)
                    assumeTrue(file.header.version.major < 10, "the branch is JT 9 only")
                    val decoded = file.decodeLsg()
                    assumeTrue(decoded != null, "no decodable LSG segment")
                    val document = decoded!!.document
                    val index = document.graphElements.indexOfFirst { it is PartitionNodeElement }
                    assumeTrue(index >= 0, "no partition node")
                    val partition = document.graphElements[index] as PartitionNodeElement
                    assumeTrue(partition.reservedBBox != null, "this partition already takes the untaken arm")

                    // Bit 0 couples two things in Fig. 14: it announces the untransformed box
                    // *and* selects what the middle slot means. Clearing it therefore moves the
                    // middle box into the transformed slot and removes the untransformed box —
                    // a partition declares its extent one way or the other, never both.
                    val box = partition.reservedBBox!!
                    val flipped =
                        partition.copy(
                            partitionFlags = partition.partitionFlags and 1.inv(),
                            reservedBBox = null,
                            transformedBBox = box,
                            untransformedBBox = null,
                        )
                    val mutated =
                        document.copy(
                            graphElements = document.graphElements.toMutableList().also { it[index] = flipped },
                        )
                    val order = file.header.byteOrder
                    val reread = LsgDocument.decode(mutated.encode(order), file.header.version, order)

                    assertEquals(emptyList<String>(), reread.notes.map { it.name }, "the flipped partition must stay legal")
                    val back = reread.document.graphElements.filterIsInstance<PartitionNodeElement>().single()
                    assertNull(back.reservedBBox, "bit 0 clear must not yield a reserved box")
                    assertEquals(box, back.transformedBBox, "the box did not come back in the transformed slot")
                    assertEquals(mutated, reread.document, "encode→decode of the flipped document is not the identity")
                },
            )
        }
}
