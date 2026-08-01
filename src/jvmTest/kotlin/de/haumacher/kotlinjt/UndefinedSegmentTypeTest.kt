package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.TypedLsgElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
 * **Segment types Table 6 does not define.** NX 10.5 writes two of them, and this suite is the
 * committed record of everything the bytes prove about them — no more (see
 * [UndefinedSegmentTypes] and DESIGN.md deltas 38 and 39):
 *
 * - the type code is *named*, not "UNKNOWN", and still carries its `UNKNOWN_SEGMENT_TYPE` note;
 * - the payload is a well-formed Logical-Element-Header-Compressed stream that decompresses and
 *   frames a terminated element list ending in the Figure-78 empty Property Table;
 * - the element type GUIDs are recorded: type 23 frames one element of a type *no* table of
 *   either reference lists, type 31 frames String Property Atom Elements (Figure 71, Annex A);
 * - the LSG reference (or its absence) is recorded;
 * - every payload byte survives parse → whole-file re-encode byte-identically.
 *
 * What a type-23 segment *means* stays undecided: nothing here interprets its body.
 */
class UndefinedSegmentTypeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    @TestFactory
    fun undefinedSegmentTypes(): List<DynamicNode> {
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — undefined-segment-type suite SKIPPED (0 fixtures)") {
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
                // spec: Table 6 (which defines neither type)
                dynamicTest("undefined types are named, noted and looked at — never anonymous") {
                    val file = JtFile.parse(bytes)
                    val undefined = file.segments.filter { it.kind == null }
                    assumeTrue(undefined.isNotEmpty(), "this fixture writes no segment type outside Table 6")
                    val perType = undefined.groupingBy { it.typeCode }.eachCount()
                    println("${fixture.name}: undefined segment types $perType")
                    // Every one of them is still a named refusal at Layer 0.
                    val noted =
                        file.notes.filterIsInstance<LoadNote.UnknownSegmentType>().map { it.segmentId }.toSet()
                    for (segment in undefined) {
                        val id = segment.tocEntry.segmentId
                        assertTrue(id in noted, "$id: an undefined segment type without a named note")
                        assertEquals(
                            "undefined type ${segment.typeCode}",
                            UndefinedSegmentTypes.labelFor(segment.typeCode),
                            "$id: the inventory label must name the type",
                        )
                        assertNull(SegmentKind.fromCode(segment.typeCode), "$id: Table 6 defines this type after all")
                    }
                },
                // spec: Figure 19 (Logical Element Header Compressed), Figure 78
                dynamicTest("their payloads are compressed element streams that decompress and frame cleanly") {
                    val file = JtFile.parse(bytes)
                    val undefined = file.segments.filter { it.kind == null }
                    assumeTrue(undefined.isNotEmpty(), "this fixture writes no segment type outside Table 6")
                    for (segment in undefined) {
                        val id = segment.tocEntry.segmentId
                        val compression = segment.compression
                        assertNotNull(compression, "$id: the payload is not a recognizable compressed element stream")
                        // Tables 8/9: flag 3 / algorithm 3 = LZMA, what the 10.5 producer uses.
                        assertEquals(3u, compression!!.flag, "$id: compression flag")
                        assertEquals(3, compression.algorithmCode, "$id: compression algorithm")
                        val elementData = segment.elementData
                        assertNotNull(elementData, "$id: element data did not decompress")
                        val scan = segment.elements!!
                        assertTrue(scan.lists.isNotEmpty(), "$id: no element list")
                        assertTrue(scan.lists.first().terminated, "$id: element list not terminated")
                        assertArrayEquals(
                            byteArrayOf(1, 0, 0, 0, 0, 0),
                            scan.trailing.toByteArray(),
                            "$id: the six-byte empty Property Table is missing after the elements",
                        )
                    }
                },
                dynamicTest("the element types they frame are recorded exactly as the bytes have them") {
                    val file = JtFile.parse(bytes)
                    val undefined = file.segments.filter { it.kind == null }
                    assumeTrue(undefined.isNotEmpty(), "this fixture writes no segment type outside Table 6")
                    for (segment in undefined) {
                        val id = segment.tocEntry.segmentId
                        val elements = segment.elements!!.lists.flatMap { it.elements }.filter { !it.isEndMarker }
                        assertTrue(elements.isNotEmpty(), "$id: no framed elements")
                        val types = elements.map { it.objectTypeId }.distinct()
                        when (segment.typeCode) {
                            UndefinedSegmentTypes.FERIT_SEGMENT_TYPE -> {
                                assertEquals(1, elements.size, "$id: expected exactly one element")
                                assertEquals(
                                    listOf(UndefinedSegmentTypes.FERIT_ELEMENT),
                                    types,
                                    "$id: unexpected element type in a type-23 segment",
                                )
                                assertNull(
                                    ObjectTypeIds.nameOf(types.single()),
                                    "$id: the type-23 element GUID turned up in Annex A after all — " +
                                        "decoding it is now fair game if a documented figure describes its layout",
                                )
                                assertEquals(9, elements.single().objectBaseType, "$id: Table 7 base type")
                            }
                            UndefinedSegmentTypes.TRANSLATOR_PROPERTY_SEGMENT_TYPE -> {
                                assertEquals(
                                    listOf(ObjectTypeIds.STRING_PROPERTY_ATOM),
                                    types,
                                    "$id: unexpected element type in a type-31 segment",
                                )
                                assertTrue(elements.size >= 2, "$id: expected key/value string atom pairs")
                                assertEquals(0, elements.size % 2, "$id: string atoms do not pair up")
                            }
                            else ->
                                error("$id: a new undefined segment type ${segment.typeCode} — record it before asserting")
                        }
                    }
                },
                dynamicTest("their LSG reference, or its absence, is recorded") {
                    val file = JtFile.parse(bytes)
                    val undefined = file.segments.filter { it.kind == null }
                    assumeTrue(undefined.isNotEmpty(), "this fixture writes no segment type outside Table 6")
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG in this fixture")
                    val table = lsg!!.propertyTable
                    assumeTrue(table != null, "no property table in this fixture's LSG")
                    val atomsById = lsg.propertyAtoms.filterIsInstance<TypedLsgElement>().associateBy { it.objectId }
                    val references = mutableMapOf<de.haumacher.kotlinjt.io.Guid, Pair<String, Int>>()
                    val ownerOf = mutableMapOf<de.haumacher.kotlinjt.io.Guid, Int>()
                    for (elementTable in table!!.tables) {
                        for (entry in elementTable.entries) {
                            val value = atomsById[entry.valuePropertyAtomObjectId]
                            if (value !is LateLoadedPropertyAtomElement) continue
                            val key = (atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement)?.value ?: continue
                            references[value.segmentId] = key to value.segmentType
                            ownerOf[value.segmentId] = elementTable.elementObjectId
                        }
                    }
                    // The owners of the XT B-Rep references, for the type-23 coincidence below.
                    val xtOwners =
                        references.filterValues { it.first == "JT_LLPROP_XTBREP" }.keys.mapNotNull { ownerOf[it] }.toSet()
                    for (segment in undefined) {
                        val id = segment.tocEntry.segmentId
                        val reference = references[id]
                        when (segment.typeCode) {
                            UndefinedSegmentTypes.FERIT_SEGMENT_TYPE -> {
                                assertNotNull(reference, "$id: no late-loaded reference points at this type-23 segment")
                                assertEquals("JT_LLPROP_FERIT", reference!!.first, "$id: unexpected reference key")
                                assertEquals(23, reference.second, "$id: the reference declares another segment type")
                                // The one structural fact the bytes give away about type 23: the
                                // node that references it also references an XT B-Rep segment.
                                assertTrue(
                                    ownerOf[id] in xtOwners,
                                    "$id: the JT_LLPROP_FERIT owner carries no JT_LLPROP_XTBREP reference — " +
                                        "the observed pairing with XT B-Rep no longer holds",
                                )
                            }
                            UndefinedSegmentTypes.TRANSLATOR_PROPERTY_SEGMENT_TYPE ->
                                assertNull(reference, "$id: a type-31 segment is now referenced — record what by")
                            else -> error("$id: unrecorded undefined segment type ${segment.typeCode}")
                        }
                    }
                },
                dynamicTest("their payloads survive parse to whole-file re-encode byte-identically") {
                    val file = JtFile.parse(bytes)
                    val undefined = file.segments.filter { it.kind == null }
                    assumeTrue(undefined.isNotEmpty(), "this fixture writes no segment type outside Table 6")
                    val preserved = undefined.sumOf { it.payload.size.toLong() }
                    assertTrue(preserved > 0, "no payload bytes at all")
                    assertArrayEquals(bytes, file.serialize(), "whole-file re-serialization is not byte-identical")
                    println("${fixture.name}: ${undefined.size} undefined-type segments, $preserved payload bytes preserved")
                },
            ),
        )
    }
}
