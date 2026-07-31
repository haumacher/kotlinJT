package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe review for the Layer 1 delivery (issue #3): the consumer's view of the decoded LSG.
 * The delivery proved decode/encode losslessness; this probe asserts what a consumer will
 * actually stand on — that the typed graph is *referentially coherent* and that Layer 1's
 * cross-references land on Layer 0 facts (late-loaded atoms → TOC entries).
 *
 * Runs over every discovered fixture (committed `fixtures/` and local `fixtures-local/`),
 * never naming a local file; skips visibly where an LSG is not decodable.
 */
class LsgProbeTest {
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

    private fun childIds(node: NodeElement): List<Int> =
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

    @TestFactory
    fun graphCoherence(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — LSG probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present; the LSG coherence probe did not run")
                },
            )
        }
        return fixtures.map { fixture ->
            dynamicContainer(fixture.name, probes(fixture.readBytes()))
        }
    }

    private fun decoded(bytes: ByteArray): LsgDocument? {
        val file = JtFile.parse(bytes)
        val elementData = file.lsgSegment()?.elementData ?: return null
        return LsgDocument.decode(elementData, file.header.version, file.header.byteOrder).document
    }

    private fun probes(bytes: ByteArray): List<DynamicNode> =
        listOf(
            dynamicTest("every reference resolves; exactly one root partition") {
                val doc = decoded(bytes)
                assumeTrue(doc != null, "LSG not decodable in this fixture (e.g. unsupported compression) — probe skipped")
                val nodes = doc!!.graphElements.filterIsInstance<NodeElement>()
                val attributes = doc.graphElements.filterIsInstance<AttributeElement>()
                assumeTrue(nodes.isNotEmpty(), "no typed nodes decoded — probe not applicable")

                val nodeIds = nodes.map { it.objectId }.toSet()
                val attributeIds = attributes.map { it.objectId }.toSet()
                assertEquals(nodes.size, nodeIds.size, "duplicate node object ids")

                for (node in nodes) {
                    for (child in childIds(node)) {
                        assertTrue(child in nodeIds, "node ${node.objectId} references child $child which does not exist")
                    }
                    for (attr in node.baseNode.attributeObjectIds) {
                        assertTrue(attr in attributeIds, "node ${node.objectId} references attribute $attr which does not exist")
                    }
                }

                val referenced = nodes.flatMap { childIds(it) }.toSet()
                val roots = nodes.filter { it.objectId !in referenced }
                assertEquals(1, roots.size, "expected exactly one root, got ${roots.map { it.objectId }}")
                assertTrue(roots.single() is PartitionNodeElement, "root is ${roots.single()::class.simpleName}, not a partition")

                // Reachability: the tree hangs together — every node reachable from the root.
                val reachable = mutableSetOf<Int>()
                val byId = nodes.associateBy { it.objectId }

                fun walk(id: Int) {
                    if (!reachable.add(id)) return
                    byId[id]?.let { n -> childIds(n).forEach(::walk) }
                }
                walk(roots.single().objectId)
                assertEquals(nodeIds, reachable, "nodes unreachable from the root: ${nodeIds - reachable}")

                // No orphan attributes: each one is worn by at least one node.
                val wornAttributes = nodes.flatMap { it.baseNode.attributeObjectIds }.toSet()
                assertEquals(attributeIds, wornAttributes intersect attributeIds, "orphan attributes: ${attributeIds - wornAttributes}")
            },
            dynamicTest("property table references only existing elements and atoms") {
                val doc = decoded(bytes)
                assumeTrue(doc?.propertyTable != null, "no decoded property table — probe skipped")
                val elementIds = doc!!.graphElements.filterIsInstance<TypedLsgElement>().map { it.objectId }.toSet()
                val atomIds = doc.propertyAtoms.filterIsInstance<PropertyAtomElement>().map { it.objectId }.toSet()
                for (table in doc.propertyTable!!.tables) {
                    assertTrue(
                        table.elementObjectId in elementIds,
                        "property table targets element ${table.elementObjectId} which does not exist",
                    )
                    for (entry in table.entries) {
                        assertTrue(entry.keyPropertyAtomObjectId in atomIds, "dangling key atom ${entry.keyPropertyAtomObjectId}")
                        assertTrue(entry.valuePropertyAtomObjectId in atomIds, "dangling value atom ${entry.valuePropertyAtomObjectId}")
                    }
                }
            },
            dynamicTest("late-loaded atoms point at real TOC segments of the declared type") {
                val file = JtFile.parse(bytes)
                val elementData = file.lsgSegment()?.elementData
                assumeTrue(elementData != null, "LSG not decodable — probe skipped")
                val doc = LsgDocument.decode(elementData!!, file.header.version, file.header.byteOrder).document
                val lateLoaded = doc.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>()
                assumeTrue(lateLoaded.isNotEmpty(), "no late-loaded atoms — probe not applicable")
                val tocById = file.toc.entries.associateBy { it.segmentId }
                for (atom in lateLoaded) {
                    val entry = tocById[atom.segmentId]
                    assertTrue(entry != null, "late-loaded atom ${atom.objectId} references segment ${atom.segmentId} absent from the TOC")
                    assertEquals(
                        atom.segmentType,
                        entry!!.typeCode,
                        "late-loaded atom ${atom.objectId} declares segment type ${atom.segmentType} but the TOC says ${entry.typeCode}",
                    )
                }
            },
        )
}
