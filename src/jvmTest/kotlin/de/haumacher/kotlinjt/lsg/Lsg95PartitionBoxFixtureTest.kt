package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
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
 * The real-file half of the Partition Node bounding-box identity (9.5 Figure 14, p.36 —
 * `docs/spec95-analysis/B-lsg-nodes.md` finding 2): the box between File Name and Area is the
 * *Reserved Field* in JT 9 when Partition Flags bit 0 is set, and the Transformed BBox only on
 * the branch where that bit is clear. Both readings consume the same bytes, so only an
 * assertion about *meaning* can tell them apart — here, that the box the model presents as an
 * extent is a real box and not the inverted `±FLT_MAX` empty-box sentinel the 9.5 producers
 * write into the reserved slot.
 *
 * Stated over element types and flag bits, never over a file name — the local fixtures are
 * IP-encumbered and gitignored, so the suite auto-discovers them and skips visibly when none
 * are present.
 */
class Lsg95PartitionBoxFixtureTest {
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
    fun partitionBoundingBoxes(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — partition bounding box suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present; the partition bounding box suite did not run")
                },
            )
        }
        return fixtures.map { fixture -> dynamicContainer(fixture.name, checks(fixture.readBytes())) }
    }

    private fun partitions(bytes: ByteArray): Pair<LsgGeneration, List<PartitionNodeElement>>? {
        val file = JtFile.parse(bytes)
        val data = file.lsgSegment()?.elementData ?: return null
        val result = LsgDocument.decode(data, file.header.version, file.header.byteOrder)
        return LsgGeneration.of(file.header.version) to
            result.document.graphElements.filterIsInstance<PartitionNodeElement>()
    }

    private fun checks(bytes: ByteArray): List<DynamicNode> =
        listOf(
            // spec: 9.5 Figure 14
            // spec: Figure 23
            dynamicTest("the box the model calls transformed is never the empty-box sentinel") {
                val (_, partitions) = partitions(bytes) ?: fail("no decodable LSG segment in this fixture")
                assumeTrue(partitions.isNotEmpty(), "no partition node in this fixture")
                for (partition in partitions) {
                    val extent = partition.extentBBox
                    assertNotNull(extent, "partition ${partition.objectId} declares no extent bounding box")
                    assertTrue(
                        extent!!.min.x <= extent.max.x && extent.min.y <= extent.max.y && extent.min.z <= extent.max.z,
                        "partition ${partition.objectId}: the declared extent $extent is inverted — that is the " +
                            "reserved field's empty-box sentinel, not a bounding box",
                    )
                    partition.transformedBBox?.let { box ->
                        assertTrue(
                            box.min.x <= box.max.x && box.min.y <= box.max.y && box.min.z <= box.max.z,
                            "partition ${partition.objectId}: the transformed box $box is inverted",
                        )
                    }
                }
            },
            // spec: 9.5 Figure 14
            dynamicTest("which field holds the middle box follows the generation and flag bit 0") {
                val (generation, partitions) = partitions(bytes) ?: fail("no decodable LSG segment in this fixture")
                assumeTrue(partitions.isNotEmpty(), "no partition node in this fixture")
                for (partition in partitions) {
                    val bitZero = partition.partitionFlags and 1 != 0
                    if (generation == LsgGeneration.V9 && bitZero) {
                        assertNotNull(partition.reservedBBox, "9.5 Figure 14 puts the reserved field in that slot")
                        assertNull(partition.transformedBBox, "a 9.5 partition with bit 0 set stores no transformed box")
                    } else {
                        assertNull(partition.reservedBBox, "only 9.5 with bit 0 set has a reserved bounding box")
                        assertNotNull(partition.transformedBBox, "the transformed box is unconditional here")
                    }
                }
            },
        )

    private fun fail(message: String): Nothing {
        assumeTrue(false, message)
        error(message)
    }
}
