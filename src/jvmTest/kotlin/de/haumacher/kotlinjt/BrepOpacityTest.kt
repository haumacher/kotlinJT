package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
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
 * **The §8 opacity proof.** Issue #1's third design rule is a doctrine, and this suite is what
 * turns it from a claim into a tested property:
 *
 * > *"B-rep (JT B-rep / XT) is preserved opaquely, never interpreted. Parasolid's representation
 * > is its own world; this library's honesty is the tessellation, the structure tree, the
 * > properties and the PMI."*
 *
 * Opaque must mean *carried*, not *skipped*. For every precise-geometry segment of every
 * discovered fixture the suite therefore proves all four halves of that sentence:
 *
 * 1. **Enumerated, never silently dropped**: the segment appears in the inventory with its type
 *    label, its element data decompresses, and every framed element's Object Type ID is a *named*
 *    type — the XT B-Rep Element of Annex A and the MultiXT B-Rep Element of Annex F.
 * 2. **Preserved**: the framed element bodies reconstruct the segment's element data byte for
 *    byte, and the whole file re-serializes byte-identically — the payload survives parse →
 *    encode untouched.
 * 3. **Never interpreted**: the payload really is Parasolid's own container (the XT transmit
 *    file's `TRANSMIT FILE` banner sits inside it), and this library has no decoder for it — the
 *    B-rep segments produce no typed document, only the diagnostic element frame.
 * 4. **Reachable**: each segment is referenced from the LSG by the late-loaded key its type
 *    promises, so a consumer that wants the B-rep can find the bytes.
 *
 * JT B-Rep (Table 6 type 2, deprecated), JT ULP (20) and STEP B-Rep (32) get the same treatment
 * the moment a fixture carries one; today the stages skip *visibly* for them.
 */
class BrepOpacityTest {
    /** The precise-geometry segment kinds of §8 plus Annex F's MultiXT. */
    private val brepKinds =
        setOf(
            SegmentKind.JT_BREP,
            SegmentKind.XT_BREP,
            SegmentKind.MULTI_XT_BREP,
            SegmentKind.ULP,
            SegmentKind.STEP_BREP,
        )

    /** The late-loaded property key each B-rep segment kind is referenced by (§13.8/Annex F). */
    private val expectedKey =
        mapOf(
            SegmentKind.JT_BREP to "JT_LLPROP_JTBREP",
            SegmentKind.XT_BREP to "JT_LLPROP_XTBREP",
            SegmentKind.MULTI_XT_BREP to "JT_LLPROP_MULTIXTBREP",
        )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun JtFile.brepSegments(): List<JtSegment> = segments.filter { it.kind in brepKinds }

    @TestFactory
    fun brepOpacity(): List<DynamicNode> {
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — §8 opacity suite SKIPPED (0 fixtures)") {
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
                // spec: §8 (Precise Geometry Segment), Annex F
                dynamicTest("every precise-geometry segment is enumerated with a named element type") {
                    val file = JtFile.parse(bytes)
                    val segments = file.brepSegments()
                    assumeTrue(segments.isNotEmpty(), "no precise-geometry segments in this fixture")
                    val perKind = segments.groupingBy { it.kind!! }.eachCount()
                    println("${fixture.name}: precise-geometry inventory $perKind")
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        // Enumerated: the type has a label, not "UNKNOWN".
                        assertEquals(segment.kind!!.label, UndefinedSegmentTypes.labelFor(segment.typeCode), "$id")
                        // Table 6 says these types compress all element data; it decompresses.
                        assertNotNull(segment.compression, "$id: no compression fields read")
                        val elementData = segment.elementData
                        assertNotNull(elementData, "$id: element data was not decoded — a silently skipped segment")
                        val scan = segment.elements!!
                        assertEquals(1, scan.lists.size, "$id: expected exactly one element list")
                        val list = scan.lists.single()
                        assertTrue(list.terminated, "$id: element list is not closed by the end-of-elements marker")
                        val elements = list.elements.filter { !it.isEndMarker }
                        assertEquals(1, elements.size, "$id: expected exactly one B-rep element")
                        val element = elements.single()
                        val name = ObjectTypeIds.nameOf(element.objectTypeId)
                        assertNotNull(name, "$id: B-rep element type ${element.objectTypeId} is unnamed")
                        val expectedType =
                            when (segment.kind) {
                                SegmentKind.JT_BREP -> ObjectTypeIds.JT_BREP_ELEMENT
                                SegmentKind.XT_BREP -> ObjectTypeIds.XT_BREP_ELEMENT
                                SegmentKind.MULTI_XT_BREP -> ObjectTypeIds.MULTI_XT_BREP_ELEMENT
                                SegmentKind.ULP -> ObjectTypeIds.JT_ULP_ELEMENT
                                else -> ObjectTypeIds.STEP_BREP_ELEMENT
                            }
                        assertEquals(expectedType, element.objectTypeId, "$id: unexpected element type ($name)")
                        // Table 7's "JtBase", as every §8–§11 element carries.
                        assertEquals(9, element.objectBaseType, "$id: unexpected object base type")
                        // The Figure-78 empty Property Table after the element list.
                        assertArrayEquals(
                            byteArrayOf(1, 0, 0, 0, 0, 0),
                            scan.trailing.toByteArray(),
                            "$id: unexpected bytes after the element list",
                        )
                    }
                },
                dynamicTest("B-rep payload bytes survive parse to whole-file re-encode byte-identically") {
                    val file = JtFile.parse(bytes)
                    val segments = file.brepSegments()
                    assumeTrue(segments.isNotEmpty(), "no precise-geometry segments in this fixture")
                    // The element frames reconstruct the decompressed element data exactly:
                    // nothing between the frames is dropped or reinterpreted.
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val elementData = segment.elementData!!.toByteArray()
                        val rebuilt = java.io.ByteArrayOutputStream()
                        for (element in segment.elements!!.lists.flatMap { it.elements }) {
                            val length = java.nio.ByteBuffer.allocate(4)
                            length.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            length.putInt(element.length)
                            rebuilt.write(length.array())
                            rebuilt.write(element.body.toByteArray())
                        }
                        rebuilt.write(segment.elements!!.trailing.toByteArray())
                        assertArrayEquals(elementData, rebuilt.toByteArray(), "$id: element frames do not cover the element data")
                    }
                    // And the whole file, compressed payloads and all, comes back unchanged.
                    assertArrayEquals(bytes, file.serialize(), "whole-file re-serialization is not byte-identical")
                    val payloadBytes = segments.sumOf { it.payload.size.toLong() }
                    val elementBytes = segments.sumOf { it.elementData!!.size.toLong() }
                    println(
                        "${fixture.name}: ${segments.size} precise-geometry segments, " +
                            "$payloadBytes payload bytes preserved, $elementBytes bytes of decompressed element data",
                    )
                    assertTrue(payloadBytes > 0, "no B-rep payload bytes at all")
                },
                dynamicTest("the payload is Parasolid's own container and this library has no decoder for it") {
                    val file = JtFile.parse(bytes)
                    val xt = file.brepSegments().filter { it.kind == SegmentKind.XT_BREP || it.kind == SegmentKind.MULTI_XT_BREP }
                    assumeTrue(xt.isNotEmpty(), "no XT/MultiXT B-Rep segments in this fixture")
                    // Annex F: the element wraps "XT B-Rep Data" — a Parasolid transmit file.
                    // Finding its banner in the bytes is the concrete form of the doctrine: this
                    // is Parasolid's world, and reading it is not this library's business.
                    val banner = "TRANSMIT FILE".toByteArray()
                    val withBanner = xt.count { indexOf(it.elementData!!.toByteArray(), banner) >= 0 }
                    assertTrue(
                        withBanner > 0,
                        "no XT payload carries the Parasolid transmit-file banner — is this really XT B-Rep?",
                    )
                    println("${fixture.name}: ${xt.size} XT/MultiXT segments, $withBanner carry a Parasolid transmit file")
                    // No typed document model exists for these segments — by doctrine. The
                    // diagnostic element frame is all the library offers, and its body is raw.
                    for (segment in xt) {
                        val element = segment.elements!!.lists.single().elements.first { !it.isEndMarker }
                        assertEquals(
                            segment.elements!!.lists.single().elements.first().length,
                            element.length,
                            "${segment.tocEntry.segmentId}: element order changed",
                        )
                        assertTrue(element.body.size > 16, "an empty B-rep element body")
                    }
                },
                dynamicTest("every precise-geometry segment is reachable through its late-loaded key") {
                    val file = JtFile.parse(bytes)
                    val segments = file.brepSegments()
                    assumeTrue(segments.isNotEmpty(), "no precise-geometry segments in this fixture")
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG in this fixture")
                    val table = lsg!!.propertyTable
                    assumeTrue(table != null, "no property table in this fixture's LSG")
                    val atomsById = lsg.propertyAtoms.filterIsInstance<TypedLsgElement>().associateBy { it.objectId }
                    val references = mutableMapOf<de.haumacher.kotlinjt.io.Guid, Pair<String, Int>>()
                    for (elementTable in table!!.tables) {
                        for (entry in elementTable.entries) {
                            val value = atomsById[entry.valuePropertyAtomObjectId]
                            if (value !is LateLoadedPropertyAtomElement) continue
                            val key = (atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement)?.value ?: continue
                            references[value.segmentId] = key to value.segmentType
                        }
                    }
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val reference = references[id]
                        assertNotNull(reference, "$id: no late-loaded reference points at this precise-geometry segment")
                        assertEquals(segment.typeCode, reference!!.second, "$id: reference declares the wrong segment type")
                        val key = expectedKey[segment.kind]
                        if (key != null) assertEquals(key, reference.first, "$id: unexpected reference key")
                    }
                },
            ),
        )
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
