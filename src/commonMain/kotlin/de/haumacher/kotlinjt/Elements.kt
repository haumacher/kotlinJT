package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid

/**
 * One framed element (clause 5.1.3.2): an I32 length followed by that many bytes, beginning
 * with the Object Type ID GUID and — except for the end-of-elements marker — the object base
 * type byte. Everything after that is object data, carried raw; interpreting it is Layer 1's
 * job.
 */
data class JtElement(
    /** Offset of the element's length field within the segment's element data. */
    val offsetInData: Int,
    /** Value of the I32 length field: the number of bytes following the field. */
    val length: Int,
    val objectTypeId: Guid,
    /** The Object Base Type byte, absent on the 16-byte end-of-elements marker. */
    val objectBaseType: Int?,
    /** The [length] bytes following the length field, including the GUID. Byte-faithful. */
    val body: Bytes,
) {
    val isEndMarker: Boolean get() = objectTypeId == Guid.END_OF_ELEMENTS
}

/** A run of elements, [terminated] when it ends with the end-of-elements marker. */
data class ElementList(
    val elements: List<JtElement>,
    val terminated: Boolean,
)

/**
 * The diagnostic element framing of a segment's element data. Segment data may hold several
 * element lists back to back (e.g. an LSG segment's graph elements followed by its property
 * atoms), each closed by the end-of-elements marker, followed by data that is not
 * element-framed at all (e.g. the LSG property table) — exposed here as [trailing].
 *
 * This is a *view*: re-serialization never goes through it, so a scan that stops early can
 * never lose bytes.
 */
data class ElementScan(
    val lists: List<ElementList>,
    /** Offset within the element data where framing stopped. */
    val trailingOffset: Int,
    /** The bytes after the last recognized frame; empty when framing consumed everything. */
    val trailing: Bytes,
) {
    val elementCount: Int get() = lists.sumOf { it.elements.size }
}

/** The minimum value of an element length field: the 16 GUID bytes of the end marker. */
private const val MIN_ELEMENT_LENGTH = 16

/**
 * Scans [data] as a sequence of element lists. A frame is recognized when its length field
 * is at least [MIN_ELEMENT_LENGTH] and lies fully inside the data; the first unrecognizable
 * position ends the scan and starts [ElementScan.trailing].
 */
fun scanElements(
    data: Bytes,
    order: Endianness,
): ElementScan {
    val bytes = data.toByteArray()
    val reader = ByteReader(bytes, order)
    val lists = mutableListOf<ElementList>()
    val current = mutableListOf<JtElement>()
    var position = 0
    while (bytes.size - position >= 4 + MIN_ELEMENT_LENGTH) {
        reader.position = position
        val length = reader.readI32()
        if (length < MIN_ELEMENT_LENGTH || position + 4 + length > bytes.size) break
        val objectTypeId = reader.readGuid()
        val baseType = if (length >= MIN_ELEMENT_LENGTH + 1) bytes[position + 4 + 16].toInt() and 0xFF else null
        val body = Bytes.of(bytes, position + 4, position + 4 + length)
        val element = JtElement(position, length, objectTypeId, baseType, body)
        current.add(element)
        position += 4 + length
        if (element.isEndMarker) {
            lists.add(ElementList(current.toList(), terminated = true))
            current.clear()
        }
    }
    if (current.isNotEmpty()) {
        lists.add(ElementList(current.toList(), terminated = false))
    }
    return ElementScan(lists, position, Bytes.of(bytes, position, bytes.size))
}
