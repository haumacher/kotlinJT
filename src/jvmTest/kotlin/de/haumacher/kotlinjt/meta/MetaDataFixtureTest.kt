package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.TypedLsgElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The real-file acceptance for §11 (issue #9): every discovered fixture's Meta Data and PMI
 * Data segments decode typed-or-noted, re-encode byte-identically, and — the cross-model
 * check — every `JT_LLPROP_METADATA` / `JT_LLPROP_PMI` reference in the LSG's property table
 * resolves to a segment that really carries the element type the key promises.
 *
 * Fixture files are never named in committed code (issue #1's fixture policy); when no fixture
 * carries §11 segments the affected stages skip *visibly*.
 */
class MetaDataFixtureTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    /** Every late-loaded reference of the LSG: segment GUID -> (key string, declared type). */
    private fun lateLoadedReferences(document: LsgDocument): Map<Guid, Pair<String, Int>> {
        val table = document.propertyTable ?: return emptyMap()
        val atomsById = document.propertyAtoms.filterIsInstance<TypedLsgElement>().associateBy { it.objectId }
        val result = mutableMapOf<Guid, Pair<String, Int>>()
        for (elementTable in table.tables) {
            for (entry in elementTable.entries) {
                val value = atomsById[entry.valuePropertyAtomObjectId]
                if (value !is LateLoadedPropertyAtomElement) continue
                val key = (atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement)?.value ?: continue
                result[value.segmentId] = key to value.segmentType
            }
        }
        return result
    }

    @TestFactory
    fun metaDataFixtures(): List<DynamicNode> {
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — §11 real-file suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture -> battery(fixture) }
    }

    private fun battery(fixture: File): DynamicNode {
        val bytes = fixture.readBytes()
        return dynamicContainer(
            fixture.name,
            listOf(
                // spec: Figure 107
                dynamicTest("meta data segments decode typed-or-noted and round-trip byte-identically") {
                    val file = JtFile.parse(bytes)
                    val segments = file.metaDataSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable meta data / PMI segments in this fixture")
                    var typed = 0
                    var opaque = 0
                    for (segment in segments) {
                        val elementData = segment.elementData!!
                        val result = MetaDataDocument.decode(elementData, file.header.version, file.header.byteOrder)
                        val id = segment.tocEntry.segmentId
                        for (element in result.document.elements) {
                            if (element is OpaqueMetaDataElement) opaque++ else typed++
                        }
                        // Zero silent refusals: every opaque element is covered by a note.
                        assertTrue(
                            result.document.elements.count { it is OpaqueMetaDataElement } <= result.notes.size,
                            "$id: opaque elements without a note — a silent refusal",
                        )
                        assertArrayEquals(
                            elementData.toByteArray(),
                            result.document.encode(file.header.byteOrder).toByteArray(),
                            "$id: encode(decode(meta data stream)) drifted",
                        )
                        // Figure 107 + the Figure-78 trailer both producers write.
                        assertTrue(result.document.elementsTerminated, "$id: element list not terminated")
                        assertNotNull(result.document.propertyTable, "$id: no trailing Property Table")
                        assertEquals(0, result.document.trailing.size, "$id: unexplained trailing bytes")
                    }
                    println("META DATA: ${segments.size} segments, $typed typed elements, $opaque opaque")
                    assertEquals(0, opaque, "every §11 element of a real producer's file decodes typed")
                },
                // spec: Figure 108
                dynamicTest("property bags resolve against the LSG's JT_LLPROP_METADATA references") {
                    val file = JtFile.parse(bytes)
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG segment in this fixture")
                    val references = lateLoadedReferences(lsg!!)
                    val metaReferences = references.filterValues { it.first.startsWith("JT_LLPROP_METADATA") }
                    assumeTrue(metaReferences.isNotEmpty(), "no JT_LLPROP_METADATA references in this fixture")
                    var bags = 0
                    var properties = 0
                    for ((segmentId, keyAndType) in metaReferences) {
                        assertEquals(
                            SegmentKind.META_DATA.code,
                            keyAndType.second,
                            "a JT_LLPROP_METADATA atom must declare segment type 4",
                        )
                        val segment = file.segments.firstOrNull { it.tocEntry.segmentId == segmentId }
                        assertNotNull(segment, "JT_LLPROP_METADATA names segment $segmentId, which the TOC does not hold")
                        assertEquals(SegmentKind.META_DATA, segment!!.kind)
                        val document = file.decodeMetaData(segment)?.document
                        assertNotNull(document, "segment $segmentId did not decode")
                        val proxies = document!!.propertyProxies
                        assertTrue(
                            proxies.isNotEmpty(),
                            "segment $segmentId is referenced as meta data but carries no Property Proxy element",
                        )
                        for (proxy in proxies) {
                            bags++
                            properties += proxy.properties.size
                            assertTrue(proxy.terminated, "$segmentId: property bag without its terminator")
                            for (property in proxy.properties) {
                                assertTrue(property.key.isNotEmpty(), "$segmentId: empty key inside the bag")
                                assertTrue(
                                    property.value !is MetaPropertyValue.Unrecognized,
                                    "$segmentId: unrecognized property value type in a real producer's bag",
                                )
                            }
                        }
                    }
                    println("PROPERTY BAGS: ${metaReferences.size} references, $bags bags, $properties properties")
                },
                // spec: Figure 110
                dynamicTest("PMI managers resolve against the LSG's JT_LLPROP_PMI references") {
                    val file = JtFile.parse(bytes)
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG segment in this fixture")
                    val references = lateLoadedReferences(lsg!!).filterValues { it.first.startsWith("JT_LLPROP_PMI") }
                    assumeTrue(references.isNotEmpty(), "no JT_LLPROP_PMI references in this fixture")
                    var managers = 0
                    for ((segmentId, keyAndType) in references) {
                        assertEquals(SegmentKind.PMI_DATA.code, keyAndType.second, "JT_LLPROP_PMI declares segment type 3")
                        val segment = file.segments.firstOrNull { it.tocEntry.segmentId == segmentId }
                        assertNotNull(segment, "JT_LLPROP_PMI names segment $segmentId, which the TOC does not hold")
                        val document = file.decodeMetaData(segment!!)?.document
                        assertNotNull(document, "segment $segmentId did not decode")
                        for (pmi in document!!.pmiManagers) {
                            managers++
                            // §11.2.7's CAD Tag Index Count formula is validated at decode; assert
                            // the coherence the decode does *not* enforce.
                            for (association in pmi.associations) {
                                assertTrue(
                                    association.sourceOwningEntityStringId == -1 ||
                                        pmi.string(association.sourceOwningEntityStringId) != null,
                                    "$segmentId: association names a string outside the PMI String Table",
                                )
                            }
                            for (view in pmi.modelViews) {
                                assertTrue(
                                    view.viewNameStringId == -1 || pmi.string(view.viewNameStringId) != null,
                                    "$segmentId: model view names a string outside the PMI String Table",
                                )
                                for (property in view.properties) {
                                    assertTrue(property.key.hiddenFlag in 0..1, "$segmentId: hidden flag outside Table 59")
                                }
                            }
                            // Figure 154's tag vectors decode since the Int64 CDP landed
                            // (issue #10): every tag type is a Table 72 value and the tag list
                            // has one entry per type entry. The tag *count* is deliberately not
                            // tied to the CAD Tag Index Count — NX writes more tags than
                            // indices in most bodies (see the codec's comment).
                            val cad = pmi.cadTagData
                            if (pmi.cadTagsFlag == 1) {
                                assertNotNull(cad, "$segmentId: CAD Tags Flag 1 without CAD Tag Data")
                                val vectors = cad!!.compressed.tags
                                assertNotNull(vectors, "$segmentId: CAD tag vectors did not decode")
                                for (type in vectors!!.tagTypes.values) {
                                    assertTrue(type == 1 || type == 2, "$segmentId: CAD tag type $type outside Table 72")
                                }
                                assertEquals(
                                    vectors.tagTypes.size,
                                    vectors.tags.size,
                                    "$segmentId: one tag per CAD Tag Types entry",
                                )
                                assertEquals(0, cad.compressed.codedData.size, "$segmentId: coded bytes left over")
                            }
                            for (font in pmi.fonts) {
                                assertEquals(
                                    font.characterSet.size,
                                    font.glyphs.elements.size,
                                    "$segmentId: font \"${font.name}\" defines glyphs for a different number of characters",
                                )
                            }
                        }
                    }
                    println("PMI MANAGERS: ${references.size} references, $managers managers")
                    assertTrue(managers > 0, "a JT_LLPROP_PMI reference must resolve to a PMI Manager element")
                },
            ),
        )
    }
}
