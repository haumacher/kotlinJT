package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.testGuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// spec: Figure 20 — LSG Segment data collection: graph element list, property atom list,
// property table — plus the opaque backstop and the structural refusal paths.
class LsgDocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v10 = JtVersion(10, 0)

    private fun endMarker(order: Endianness): ByteArray {
        val w = ByteWriter(order)
        w.writeI32(16)
        w.writeGuid(Guid.END_OF_ELEMENTS)
        return w.toByteArray()
    }

    private fun stream(
        order: Endianness,
        vararg parts: ByteArray,
    ): ByteArray {
        val w = ByteWriter(order)
        for (part in parts) w.writeBytes(part)
        return w.toByteArray()
    }

    private fun groupElement(
        order: Endianness,
        generation: LsgGeneration,
        objectId: Int,
        children: List<Int> = emptyList(),
    ): ByteArray =
        lsgFrame(order, ObjectTypeIds.GROUP_NODE, 1, objectId) {
            writeTestGroupNodeData(generation, children = children)
        }

    private fun stringAtom(
        order: Endianness,
        generation: LsgGeneration,
        objectId: Int,
        value: String,
    ): ByteArray =
        lsgFrame(order, ObjectTypeIds.STRING_PROPERTY_ATOM, 5, objectId) {
            writeTestBasePropertyAtomData(generation)
            writeTestVersionNumber(generation)
            writeI32(value.length)
            for (ch in value) writeU16(ch.code.toUShort())
        }

    // spec: Figure 78
    // spec: Figure 79
    private fun propertyTable(order: Endianness): ByteArray {
        val w = ByteWriter(order)
        w.writeI16(1) // version
        w.writeI32(1) // element property table count
        w.writeI32(1) // element object id
        w.writeI32(2) // key atom
        w.writeI32(3) // value atom
        w.writeI32(0) // terminator
        return w.toByteArray()
    }

    // spec: Figure 20
    @Test
    fun wellFormedDocumentDecodesAndRoundTrips() =
        forBothOrders { order ->
            for ((version, generation) in listOf(v9 to LsgGeneration.V9, v10 to LsgGeneration.V10)) {
                val bytes =
                    stream(
                        order,
                        groupElement(order, generation, 1, children = listOf(2)),
                        groupElement(order, generation, 2),
                        endMarker(order),
                        stringAtom(order, generation, 2, "name"),
                        stringAtom(order, generation, 3, "value"),
                        endMarker(order),
                        propertyTable(order),
                    )
                val result = LsgDocument.decode(bytes.toBytes(), version, order)
                assertEquals(emptyList(), result.notes)
                val document = result.document
                assertEquals(generation, document.generation)
                assertEquals(2, document.graphElements.size)
                assertEquals(2, document.propertyAtoms.size)
                assertTrue(document.graphElementsTerminated)
                assertTrue(document.propertyAtomsTerminated)
                assertEquals(0, document.trailing.size)
                val table = document.propertyTable
                assertEquals(1, table?.version)
                assertEquals(listOf(ElementPropertyTable(1, listOf(PropertyEntry(2, 3)))), table?.tables)
                assertContentEquals(bytes, document.encode(order).toByteArray(), "document round-trip drifted")
            }
        }

    // spec: §6.1 — an element with a GUID outside Annex A is carried opaquely with a note
    @Test
    fun unknownElementTypeIsOpaqueWithNoteAndByteFaithful() =
        forBothOrders { order ->
            val alien =
                lsgFrame(order, testGuid(0xBAD, 1, 2, 3), 9, 99) {
                    writeI32(12345)
                }
            val bytes =
                stream(
                    order,
                    groupElement(order, LsgGeneration.V10, 1),
                    alien,
                    endMarker(order),
                    endMarker(order),
                    propertyTable(order),
                )
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            assertEquals(listOf("UNKNOWN_ELEMENT_TYPE"), result.notes.map { it.name })
            val opaque = assertIs<OpaqueLsgElement>(result.document.graphElements[1])
            assertEquals(9, opaque.objectBaseType)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: §6.1 — a known type with a corrupt body falls back to opaque, never half-decoded
    @Test
    fun decodeFailureFallsBackToOpaqueWithNote() =
        forBothOrders { order ->
            // A group node whose child count promises more data than the element holds.
            val corrupt =
                lsgFrame(order, ObjectTypeIds.GROUP_NODE, 1, 1) {
                    writeTestBaseNodeData(LsgGeneration.V10)
                    writeU8(1u)
                    writeI32(1000) // child count with no children following
                }
            val bytes = stream(order, corrupt, endMarker(order), endMarker(order), propertyTable(order))
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
            assertIs<OpaqueLsgElement>(result.document.graphElements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: §6.1 — trailing unconsumed body bytes refuse the typed decode (no silent surplus)
    @Test
    fun surplusBodyBytesRefuseTheTypedDecode() =
        forBothOrders { order ->
            val padded =
                lsgFrame(order, ObjectTypeIds.GROUP_NODE, 1, 1) {
                    writeTestGroupNodeData(LsgGeneration.V10)
                    writeI32(0xDEAD) // surplus
                }
            val bytes = stream(order, padded, endMarker(order), endMarker(order), propertyTable(order))
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: §6.1.2 — types without an established JT 9 layout stay opaque in JT 9 files
    @Test
    fun v9UnverifiedAttributeLayoutStaysOpaque() =
        forBothOrders { order ->
            val drawStyle =
                lsgFrame(order, ObjectTypeIds.DRAW_STYLE_ATTRIBUTE, 3, 1) {
                    writeBytes(byteArrayOf(1, 0, 8, 0, 0, 0, 0, 1, 0, 3))
                }
            val bytes = stream(order, drawStyle, endMarker(order), endMarker(order), propertyTable(order))
            val result = LsgDocument.decode(bytes.toBytes(), v9, order)
            assertEquals(listOf("ELEMENT_LAYOUT_UNVERIFIED"), result.notes.map { it.name })
            assertIs<OpaqueLsgElement>(result.document.graphElements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
            // The same type decodes typed in a v10 stream.
            val v10DrawStyle =
                lsgFrame(order, ObjectTypeIds.DRAW_STYLE_ATTRIBUTE, 3, 1) {
                    writeTestBaseAttributeData(LsgGeneration.V10)
                    writeU8(1u)
                    writeU8(3u)
                }
            roundTripTyped(v10DrawStyle, order, LsgGeneration.V10)
        }

    // spec: Figure 20 — an unterminated graph list is a named structural refusal
    @Test
    fun unterminatedStreamIsNotedAndPreserved() =
        forBothOrders { order ->
            val bytes = groupElement(order, LsgGeneration.V10, 1)
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            assertEquals(listOf("LSG_STRUCTURE_UNRECOGNIZED"), result.notes.map { it.name })
            assertEquals(false, result.document.graphElementsTerminated)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: Figure 20 — garbage after the graph list is preserved verbatim with a note
    @Test
    fun garbageAfterGraphListIsPreserved() =
        forBothOrders { order ->
            val garbage = byteArrayOf(3, 1, 4, 1, 5)
            val bytes = stream(order, groupElement(order, LsgGeneration.V10, 1), endMarker(order), garbage)
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            // The garbage is not a valid frame: the atom list ends unterminated before it.
            assertEquals(listOf("LSG_STRUCTURE_UNRECOGNIZED"), result.notes.map { it.name })
            assertEquals(garbage.size, result.document.trailing.size)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: Figure 78 — a missing property table is a named refusal, not silence
    @Test
    fun missingPropertyTableIsNoted() =
        forBothOrders { order ->
            val bytes = stream(order, groupElement(order, LsgGeneration.V10, 1), endMarker(order), endMarker(order))
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            assertEquals(listOf("PROPERTY_TABLE_MISSING"), result.notes.map { it.name })
            assertEquals(null, result.document.propertyTable)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: Figure 78 — an unparseable property table tail is preserved verbatim with a note
    @Test
    fun corruptPropertyTableIsPreserved() =
        forBothOrders { order ->
            val corruptTable = byteArrayOf(1, 0, 50, 0) // truncated mid-count
            val bytes =
                stream(order, groupElement(order, LsgGeneration.V10, 1), endMarker(order), endMarker(order), corruptTable)
            val result = LsgDocument.decode(bytes.toBytes(), v10, order)
            assertEquals(listOf("PROPERTY_TABLE_UNRECOGNIZED"), result.notes.map { it.name })
            assertEquals(corruptTable.size, result.document.trailing.size)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: §6.1.2.11 — a nested mapping surface of an unknown type keeps the whole
    // texture coordinate generator byte-faithful through the opaque fallback
    @Test
    fun texCoordGeneratorWithAlienMappingSurfaceStaysLossless() =
        forBothOrders { order ->
            val alienSurface = lsgFrame(order, testGuid(77, 7, 7, 7), 3, 90) { writeI32(1) }
            val texGen =
                lsgFrame(order, ObjectTypeIds.TEXTURE_COORDINATE_GENERATOR_ATTRIBUTE, 3, 38) {
                    writeTestBaseAttributeData(LsgGeneration.V10)
                    writeU8(1u)
                    writeI32(0)
                    writeBytes(alienSurface)
                }
            val (element, notes) = decodeSingle(texGen, order, LsgGeneration.V10)
            // The nested unknown type is noted; the parent decodes around it.
            assertEquals(listOf("UNKNOWN_ELEMENT_TYPE"), notes.map { it.name })
            val typed = assertIs<TextureCoordinateGeneratorAttributeElement>(element)
            assertIs<OpaqueLsgElement>(typed.mappingSurface)
            assertContentEquals(texGen, encodeSingle(element, order, LsgGeneration.V10))
        }
}
