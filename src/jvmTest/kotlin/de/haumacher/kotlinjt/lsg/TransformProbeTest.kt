package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.shape.ShapeLodDocument
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe review for the v10 shape delivery (issue #6): the first test that COMPOSES decoded
 * transforms with decoded geometry — the seam Layer 2 will stand on. For every shape segment,
 * the accumulated node-path transform (root → shape, spec §13.8 accumulation, row-vector
 * convention: p' = p·M, translation in elements 12–14) carries the decoded local vertices
 * into world space, which must land inside the partition's declared transformed (world) bbox.
 * A wrong matrix decode, a wrong accumulation order, or wrong geometry would all blow the box.
 *
 * Tolerance: the declared box is expanded by 0.5% of its diagonal plus a small absolute
 * epsilon — producer boxes are authoritative but float-rounded.
 */
class TransformProbeTest {
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

    /** p' = p·M for a row-major 4×4 with translation in m[12..14]; w assumed affine. */
    private fun apply(
        m: DoubleArray,
        p: DoubleArray,
    ): DoubleArray =
        doubleArrayOf(
            p[0] * m[0] + p[1] * m[4] + p[2] * m[8] + m[12],
            p[0] * m[1] + p[1] * m[5] + p[2] * m[9] + m[13],
            p[0] * m[2] + p[1] * m[6] + p[2] * m[10] + m[14],
        )

    /** A·B in row-major convention (apply A first, then B). */
    private fun multiply(
        a: DoubleArray,
        b: DoubleArray,
    ): DoubleArray {
        val out = DoubleArray(16)
        for (r in 0..3) {
            for (c in 0..3) {
                var s = 0.0
                for (k in 0..3) s += a[r * 4 + k] * b[k * 4 + c]
                out[r * 4 + c] = s
            }
        }
        return out
    }

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun nodeMatrix(
        document: LsgDocument,
        node: NodeElement,
    ): DoubleArray? {
        val attributes = document.graphElements.filterIsInstance<AttributeElement>().associateBy { it.objectId }
        for (id in node.baseNode.attributeObjectIds) {
            val attribute = attributes[id]
            if (attribute is GeometricTransformAttributeElement) {
                return attribute.matrix.values.map { it }.toDoubleArray()
            }
        }
        return null
    }

    @TestFactory
    fun worldSpaceContainment(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — transform probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicTest("${fixture.name}: accumulated transforms carry every shape into the partition's world box") {
                val file = JtFile.parse(fixture.readBytes())
                val elementData = file.lsgSegment()?.elementData
                assumeTrue(elementData != null, "LSG not decodable — probe skipped")
                val document = LsgDocument.decode(elementData!!, file.header.version, file.header.byteOrder).document

                val partition = document.graphElements.filterIsInstance<PartitionNodeElement>().firstOrNull()
                assumeTrue(partition != null, "no partition node — probe not applicable")
                // The declared extent, not the raw transformed slot: 9.5 Figure 14 puts a
                // reserved field there when partition flag bit 0 is set (DESIGN.md delta 43).
                val box = checkNotNull(partition!!.extentBBox) { "partition declares no extent box" }
                val diagonal =
                    kotlin.math.sqrt(
                        (
                            (box.max.x - box.min.x) * (box.max.x - box.min.x) +
                                (box.max.y - box.min.y) * (box.max.y - box.min.y) +
                                (box.max.z - box.min.z) * (box.max.z - box.min.z)
                        ).toDouble(),
                    )
                val eps = diagonal * 0.005 + 1e-6

                // Accumulated matrix per node: A(child) = M(child)·A(parent), root has A = M(root) or I.
                val nodesById = document.graphElements.filterIsInstance<NodeElement>().associateBy { it.objectId }
                val accumulated = mutableMapOf<Int, DoubleArray>()

                fun accumulate(
                    id: Int,
                    parent: DoubleArray,
                ) {
                    val node = nodesById[id] ?: return
                    val own = nodeMatrix(document, node)
                    val acc = if (own != null) multiply(own, parent) else parent
                    accumulated[id] = acc
                    val children =
                        when (node) {
                            is InstanceNodeElement -> listOf(node.childNodeObjectId)
                            is PartitionNodeElement -> node.group.childNodeObjectIds
                            is GroupNodeElement -> node.group.childNodeObjectIds
                            is SwitchNodeElement -> node.group.childNodeObjectIds
                            is LodNodeElement -> node.lod.group.childNodeObjectIds
                            is RangeLodNodeElement -> node.lod.group.childNodeObjectIds
                            is PartNodeElement -> node.metaData.group.childNodeObjectIds
                            is MetaDataNodeElement -> node.metaData.group.childNodeObjectIds
                            else -> emptyList()
                        }
                    for (child in children) accumulate(child, acc)
                }
                accumulate(partition.objectId, identity)

                // Late-loaded atom -> owning shape node, as the battery resolves it.
                val atomOwner = mutableMapOf<Int, Int>()
                document.propertyTable?.tables?.forEach {
                        t ->
                    t.entries.forEach { atomOwner[it.valuePropertyAtomObjectId] = t.elementObjectId }
                }
                val shapeNodeBySegment =
                    document.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>()
                        .mapNotNull { atom -> atomOwner[atom.objectId]?.let { atom.segmentId to it } }
                        .toMap()

                var verticesChecked = 0L
                var shapesChecked = 0
                for (segment in file.shapeLodSegments()) {
                    val data = segment.elementData ?: continue
                    val nodeId = shapeNodeBySegment[segment.tocEntry.segmentId] ?: continue
                    val matrix = accumulated[nodeId] ?: continue
                    val geometry =
                        ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder)
                            .document.triStripGeometry ?: continue
                    shapesChecked++
                    for (v in geometry.vertices) {
                        val w = apply(matrix, doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()))
                        assertTrue(
                            w[0] >= box.min.x - eps && w[0] <= box.max.x + eps &&
                                w[1] >= box.min.y - eps && w[1] <= box.max.y + eps &&
                                w[2] >= box.min.z - eps && w[2] <= box.max.z + eps,
                            "world vertex (${w[0]}, ${w[1]}, ${w[2]}) of segment ${segment.tocEntry.segmentId} " +
                                "escapes the partition box $box (eps $eps) — transform decode, accumulation " +
                                "order, or geometry is wrong",
                        )
                        verticesChecked++
                    }
                }
                assumeTrue(shapesChecked > 0, "no shape resolved to a transformed node — probe not applicable")
                println("PROBE transform: $shapesChecked shapes, $verticesChecked world-space vertices inside the partition box")
            }
        }
    }
}
