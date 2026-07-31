package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid

/**
 * The segment-wide compression fields read from the first element position of a compressible
 * segment (clause 5.1.3.2.2, Logical Element Header Compressed): U32 Compression Flag,
 * I32 Compressed Data Length (which includes the algorithm byte), U8 Compression Algorithm.
 */
data class CompressionHeader(
    val flag: UInt,
    val compressedDataLength: Int,
    val algorithmCode: Int,
) {
    /** The number of data bytes following the algorithm byte. */
    val bodyLength: Int get() = compressedDataLength - 1

    /** Size of these fields on the wire. */
    val headerSize: Int get() = 9
}

/**
 * One data segment (clause 5.1.3): the segment header fields plus the payload.
 *
 * [payload] is the raw byte-faithful truth — always present, always what re-serialization
 * emits. [elementData] is the decoded view (inflated where segment-wide ZLIB applies, the
 * plain payload where no compression applies); it is `null` exactly when a named [LoadNote]
 * on the file says why. [elements] is the diagnostic framing of [elementData].
 */
data class JtSegment(
    val tocEntry: TocEntry,
    /** GUID from the segment header (normally equal to [TocEntry.segmentId]). */
    val headerId: Guid,
    /** I32 segment type from the segment header. */
    val typeCode: Int,
    /** I32 segment length from the segment header, preserved as read. */
    val declaredLength: Int,
    /** The segment type, `null` for codes outside the specified table. */
    val kind: SegmentKind?,
    /** Raw bytes after the 24-byte segment header. Byte-faithful, always available. */
    val payload: Bytes,
    /** Segment-wide compression fields; `null` where the segment type is not compressible. */
    val compression: CompressionHeader?,
    /** Decoded element data, `null` when decoding was refused (see the file's notes). */
    val elementData: Bytes?,
    /** Diagnostic element framing of [elementData], `null` when there is no decoded view. */
    val elements: ElementScan?,
) {
    val offset: Long get() = tocEntry.offset
    val length: Long get() = tocEntry.length

    /** The size of the segment header on the wire: GUID + I32 type + I32 length. */
    val headerSize: Int get() = 24

    /** A short human-readable description of the compression state. */
    val compressionLabel: String
        get() =
            when {
                compression == null -> "-"
                else -> {
                    val codecLabel =
                        when (compression.algorithmCode) {
                            1 -> "none"
                            2 -> "zlib"
                            3 -> "lzma"
                            else -> "algorithm ${compression.algorithmCode}"
                        }
                    "$codecLabel (flag ${compression.flag}, ${compression.compressedDataLength} bytes)"
                }
            }

    fun writeTo(writer: ByteWriter) {
        writer.writeGuid(headerId)
        writer.writeI32(typeCode)
        writer.writeI32(declaredLength)
        writer.writeBytes(payload)
    }
}
