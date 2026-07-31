package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes

/** A test GUID from four small numbers; the tail encodes [d] repeated. */
fun testGuid(
    a: Int,
    b: Int = 0,
    c: Int = 0,
    d: Int = 0,
): Guid = Guid(a.toUInt(), b.toUShort(), c.toUShort(), ByteArray(8) { d.toByte() }.toBytes())

/** The 80-byte version string of clause 5.1.1: padded to 75 chars plus ` \n\r\n `. */
fun versionBytes(versionLine: String): ByteArray {
    require(versionLine.length <= 75) { "version line too long" }
    val padded = versionLine.padEnd(75, ' ') + " \n\r\n "
    check(padded.length == 80)
    return ByteArray(80) { padded[it].code.toByte() }
}

/** Encodes one element frame: I32 length, Object Type ID, base type byte, object data. */
fun elementBytes(
    order: Endianness,
    typeId: Guid,
    baseType: Int,
    data: ByteArray,
): ByteArray {
    val writer = ByteWriter(order)
    writer.writeI32(16 + 1 + data.size)
    writer.writeGuid(typeId)
    writer.writeU8(baseType.toUByte())
    writer.writeBytes(data)
    return writer.toByteArray()
}

/** Encodes the 16-byte end-of-elements marker frame. */
fun endOfElementsBytes(order: Endianness): ByteArray {
    val writer = ByteWriter(order)
    writer.writeI32(16)
    writer.writeGuid(Guid.END_OF_ELEMENTS)
    return writer.toByteArray()
}

/** Wraps plain element data in the segment-wide compression fields (clause 5.1.3.2.2). */
fun compressionWrapper(
    order: Endianness,
    flag: UInt,
    algorithm: Int,
    body: ByteArray,
): ByteArray {
    val writer = ByteWriter(order)
    writer.writeU32(flag)
    writer.writeI32(1 + body.size)
    writer.writeU8(algorithm.toUByte())
    writer.writeBytes(body)
    return writer.toByteArray()
}

/**
 * Assembles complete synthetic JT file images — well-formed by default, hostile on demand
 * through the mismatch knobs — for both file versions and both byte orders.
 */
class TestFileAssembler(
    private val order: Endianness,
    private val version: JtVersion,
    private val lsgSegmentId: Guid,
    private val emptyField: Int = 0,
    private val trailingGuid: Guid? = null,
) {
    private class Part(
        val gapBefore: ByteArray,
        val tocGuid: Guid,
        val tocType: Int,
        val headerGuid: Guid,
        val headerType: Int,
        val payload: ByteArray,
        val declaredLengthDelta: Int,
    )

    private val parts = mutableListOf<Part>()
    private val extraEntries = mutableListOf<TocEntry>()

    fun addSegment(
        guid: Guid,
        typeCode: Int,
        payload: ByteArray,
        gapBefore: ByteArray = ByteArray(0),
        headerGuid: Guid = guid,
        headerType: Int = typeCode,
        declaredLengthDelta: Int = 0,
    ): TestFileAssembler {
        parts.add(Part(gapBefore, guid, typeCode, headerGuid, headerType, payload, declaredLengthDelta))
        return this
    }

    /** An additional raw TOC entry, e.g. one pointing outside the file. */
    fun addTocEntry(
        guid: Guid,
        offset: Long,
        length: Long,
        typeCode: Int,
    ): TestFileAssembler {
        extraEntries.add(TocEntry(guid, offset, length, (typeCode.toUInt() shl 24)))
        return this
    }

    fun headerLength(): Int = 80 + 1 + 4 + (if (version.wideOffsets) 8 else 4) + 16 + (if (trailingGuid != null) 16 else 0)

    fun build(versionLine: String = "Version ${version.major}.${version.minor} JT kotlinJT synthetic"): ByteArray {
        var offset = headerLength().toLong()
        val entries = mutableListOf<TocEntry>()
        val segmentBytes = ByteWriter(order)
        for (part in parts) {
            segmentBytes.writeBytes(part.gapBefore)
            offset += part.gapBefore.size
            val length = 24L + part.payload.size
            entries.add(TocEntry(part.tocGuid, offset, length, (part.tocType.toUInt() shl 24)))
            segmentBytes.writeGuid(part.headerGuid)
            segmentBytes.writeI32(part.headerType)
            segmentBytes.writeI32(part.payload.size + 24 + part.declaredLengthDelta)
            segmentBytes.writeBytes(part.payload)
            offset += length
        }
        entries.addAll(extraEntries)
        val tocOffset = offset

        val writer = ByteWriter(order)
        writer.writeBytes(versionBytes(versionLine))
        writer.writeU8(order.headerByte.toUByte())
        writer.writeI32(emptyField)
        if (version.wideOffsets) {
            writer.writeU64(tocOffset.toULong())
        } else {
            writer.writeI32(tocOffset.toInt())
        }
        writer.writeGuid(lsgSegmentId)
        trailingGuid?.let { writer.writeGuid(it) }
        check(writer.size == headerLength())
        writer.writeBytes(segmentBytes.toByteArray())
        writer.writeI32(entries.size)
        for (entry in entries) {
            entry.writeTo(writer, version)
        }
        return writer.toByteArray()
    }
}
