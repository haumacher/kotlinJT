package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Guid

/**
 * One TOC entry (clause 5.1.2): maps a segment GUID to its absolute position in the file.
 *
 * Wire widths differ by file version: JT 10 stores the offset as U64 and the length as U32
 * (32-byte entry); JT 8/9 store offset and length as I32 (28-byte entry) — see DESIGN.md.
 */
data class TocEntry(
    val segmentId: Guid,
    val offset: Long,
    val length: Long,
    val attributes: UInt,
) {
    /** Segment type, from bits 24–31 of the attributes word (Table 5). */
    val typeCode: Int get() = (attributes shr 24).toInt()

    fun writeTo(
        writer: ByteWriter,
        version: JtVersion,
    ) {
        writer.writeGuid(segmentId)
        if (version.wideOffsets) {
            writer.writeU64(offset.toULong())
            writer.writeU32(length.toUInt())
        } else {
            writer.writeI32(offset.toInt())
            writer.writeI32(length.toInt())
        }
        writer.writeU32(attributes)
    }

    companion object {
        fun read(
            reader: ByteReader,
            version: JtVersion,
        ): TocEntry {
            val segmentId = reader.readGuid()
            val offset: Long
            val length: Long
            if (version.wideOffsets) {
                offset = reader.readU64().toLong()
                length = reader.readU32().toLong()
            } else {
                offset = reader.readI32().toLong()
                length = reader.readI32().toLong()
            }
            val attributes = reader.readU32()
            return TocEntry(segmentId, offset, length, attributes)
        }
    }
}

/**
 * The TOC Segment (clause 5.1.2): an entry count followed by one entry per individually
 * addressable data segment, kept in file order for byte-faithful re-serialization.
 */
data class Toc(
    val offset: Long,
    val entries: List<TocEntry>,
) {
    fun lengthInFile(version: JtVersion): Int = 4 + entries.size * (if (version.wideOffsets) 32 else 28)

    fun writeTo(
        writer: ByteWriter,
        version: JtVersion,
    ) {
        writer.writeI32(entries.size)
        for (entry in entries) {
            entry.writeTo(writer, version)
        }
    }

    companion object {
        /**
         * Reads the TOC at [tocOffset]. Throws [JtFormatException] when the TOC is unusable —
         * without it, no segment in the file can be located.
         */
        fun parse(
            bytes: ByteArray,
            tocOffset: Long,
            version: JtVersion,
            header: FileHeader,
        ): Toc {
            if (tocOffset < 0 || tocOffset + 4 > bytes.size) {
                throw JtFormatException("TOC offset $tocOffset outside the ${bytes.size}-byte file")
            }
            val reader = ByteReader(bytes, header.byteOrder, tocOffset.toInt())
            val entryCount = reader.readI32()
            if (entryCount < 0) {
                throw JtFormatException("negative TOC entry count $entryCount")
            }
            val entrySize = if (version.wideOffsets) 32 else 28
            if (tocOffset + 4 + entryCount.toLong() * entrySize > bytes.size) {
                throw JtFormatException(
                    "TOC with $entryCount entries at offset $tocOffset exceeds the ${bytes.size}-byte file",
                )
            }
            val entries = (0 until entryCount).map { TocEntry.read(reader, version) }
            return Toc(tocOffset, entries)
        }
    }
}
