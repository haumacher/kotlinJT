package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.codec.CodecResult
import de.haumacher.kotlinjt.codec.SegmentCodecs
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes

/**
 * One contiguous region of the physical file layout. The regions of a parsed file cover
 * `[headerLength, fileSize)` completely; re-serialization emits them back in offset order,
 * which is what makes Layer 0 losslessness structural rather than accidental.
 */
sealed class FileRegion {
    abstract val offset: Long
    abstract val length: Long

    /**
     * A region whose bytes lie inside another region (hostile or duplicated TOC entries).
     * Shadowed regions are parsed and inspectable but not part of the serialized layout —
     * their bytes are emitted by the region that contains them.
     */
    abstract val shadowed: Boolean

    data class SegmentRegion(
        val segment: JtSegment,
        override val shadowed: Boolean,
    ) : FileRegion() {
        override val offset: Long get() = segment.offset
        override val length: Long get() = segment.length
    }

    data class TocRegion(
        val toc: Toc,
        val version: JtVersion,
        override val shadowed: Boolean,
    ) : FileRegion() {
        override val offset: Long get() = toc.offset
        override val length: Long get() = toc.lengthInFile(version).toLong()
    }

    /** Bytes no TOC entry explains, preserved verbatim (a named note points at them). */
    data class GapRegion(
        override val offset: Long,
        val bytes: Bytes,
    ) : FileRegion() {
        override val length: Long get() = bytes.size.toLong()
        override val shadowed: Boolean get() = false
    }
}

/**
 * A JT file at Layer 0: the physical format — header, TOC, segments with byte-faithful
 * payloads, and the named refusals collected while parsing.
 *
 * The guarantee of this layer: [JtFile.parse] followed by [serialize] reproduces the input
 * byte-identically, for well-formed and hostile files alike.
 */
data class JtFile(
    val header: FileHeader,
    val toc: Toc,
    /** All file regions in offset order, shadowed ones included. */
    val regions: List<FileRegion>,
    val notes: List<LoadNote>,
    val fileSize: Long,
) {
    /** All parsed segments in file-offset order, shadowed ones included. */
    val segments: List<JtSegment>
        get() = regions.filterIsInstance<FileRegion.SegmentRegion>().map { it.segment }

    fun serialize(): ByteArray {
        val writer = ByteWriter(header.byteOrder, fileSize.toInt().coerceAtLeast(64))
        header.writeTo(writer)
        check(writer.size == header.headerLength) {
            "header serialization drift: wrote ${writer.size}, expected ${header.headerLength}"
        }
        for (region in regions) {
            if (region.shadowed) continue
            check(writer.size.toLong() == region.offset) {
                "layout drift at region ${region.offset}: writer is at ${writer.size}"
            }
            when (region) {
                is FileRegion.SegmentRegion -> region.segment.writeTo(writer)
                is FileRegion.TocRegion -> region.toc.writeTo(writer, region.version)
                is FileRegion.GapRegion -> writer.writeBytes(region.bytes)
            }
        }
        check(writer.size.toLong() == fileSize) {
            "file serialization drift: wrote ${writer.size}, expected $fileSize"
        }
        return writer.toByteArray()
    }

    companion object {
        /**
         * Parses a complete JT file image. Throws [JtFormatException] only when the image
         * cannot be a JT file at all (unreadable header or TOC); every segment-local problem
         * becomes a named [LoadNote] with the raw bytes preserved.
         */
        fun parse(bytes: ByteArray): JtFile {
            val header = FileHeader.parse(bytes)
            val version = header.version
            val toc = Toc.parse(bytes, header.tocOffset, version, header)
            val notes = mutableListOf<LoadNote>()

            val mapped = mutableListOf<FileRegion>()
            mapped.add(FileRegion.TocRegion(toc, version, shadowed = false))
            for (entry in toc.entries) {
                val segment = parseSegment(bytes, header, entry, notes) ?: continue
                mapped.add(FileRegion.SegmentRegion(segment, shadowed = false))
            }

            // Lay the mapped regions out over the file: regions starting inside an already
            // covered range are shadowed; every uncovered range becomes a preserved gap.
            val sorted = mapped.sortedWith(compareBy({ it.offset }, { -it.length }))
            val regions = mutableListOf<FileRegion>()
            var cursor = header.headerLength.toLong()
            for (region in sorted) {
                if (region.offset < cursor) {
                    val id =
                        when (region) {
                            is FileRegion.SegmentRegion -> region.segment.tocEntry.segmentId
                            is FileRegion.TocRegion -> header.lsgSegmentId
                            is FileRegion.GapRegion -> error("gaps are not mapped regions")
                        }
                    val what = if (region is FileRegion.TocRegion) "the TOC" else "segment $id"
                    notes.add(
                        LoadNote.SegmentRegionOverlap(
                            id,
                            region.offset,
                            "$what starts at ${region.offset} inside the region ending at $cursor",
                        ),
                    )
                    regions.add(shadow(region))
                    continue
                }
                if (region.offset > cursor) {
                    val gap = Bytes.of(bytes, cursor.toInt(), region.offset.toInt())
                    notes.add(LoadNote.UnmappedRegion(cursor, gap.size.toLong()))
                    regions.add(FileRegion.GapRegion(cursor, gap))
                }
                regions.add(region)
                cursor = region.offset + region.length
            }
            if (cursor < bytes.size) {
                val gap = Bytes.of(bytes, cursor.toInt(), bytes.size)
                notes.add(LoadNote.UnmappedRegion(cursor, gap.size.toLong()))
                regions.add(FileRegion.GapRegion(cursor, gap))
            }

            return JtFile(header, toc, regions, notes, bytes.size.toLong())
        }

        private fun shadow(region: FileRegion): FileRegion =
            when (region) {
                is FileRegion.SegmentRegion -> region.copy(shadowed = true)
                is FileRegion.TocRegion -> region.copy(shadowed = true)
                is FileRegion.GapRegion -> region
            }

        private fun parseSegment(
            bytes: ByteArray,
            header: FileHeader,
            entry: TocEntry,
            notes: MutableList<LoadNote>,
        ): JtSegment? {
            if (entry.offset < 0 || entry.length < 0 || entry.offset + entry.length > bytes.size) {
                notes.add(LoadNote.SegmentOutOfBounds(entry.segmentId, entry.offset, entry.length, bytes.size))
                return null
            }
            if (entry.length < 24) {
                notes.add(LoadNote.SegmentTooShort(entry.segmentId, entry.offset, entry.length))
                return null
            }
            val reader = ByteReader(bytes, header.byteOrder, entry.offset.toInt())
            val headerId = reader.readGuid()
            val typeCode = reader.readI32()
            val declaredLength = reader.readI32()
            if (headerId != entry.segmentId) {
                notes.add(LoadNote.SegmentIdMismatch(entry.segmentId, headerId, entry.offset))
            }
            if (declaredLength.toLong() != entry.length) {
                notes.add(LoadNote.SegmentLengthMismatch(entry.segmentId, entry.length, declaredLength.toLong()))
            }
            if (typeCode != entry.typeCode) {
                notes.add(LoadNote.SegmentTypeMismatch(entry.segmentId, entry.typeCode, typeCode))
            }
            val payload = Bytes.of(bytes, entry.offset.toInt() + 24, (entry.offset + entry.length).toInt())
            val kind = SegmentKind.fromCode(typeCode)
            if (kind == null) {
                notes.add(LoadNote.UnknownSegmentType(entry.segmentId, typeCode))
                // A type Table 6 does not define still gets its contents *looked at*: the
                // probe below only accepts what it can verify (a well-formed compression
                // header whose codec decodes into a terminated element list), and it adds no
                // note of its own — the unknown type is already named. Nothing is guessed and
                // re-serialization still emits the raw payload either way.
                val probed = probeUndefinedType(header, entry, payload)
                return JtSegment(
                    entry,
                    headerId,
                    typeCode,
                    declaredLength,
                    null,
                    payload,
                    probed?.first,
                    probed?.second,
                    probed?.let { scanElements(it.second, header.byteOrder) },
                )
            }

            var compression: CompressionHeader? = null
            val elementData: Bytes?
            if (kind.compressible) {
                elementData = decodeCompressible(header, entry, payload, notes) { compression = it }
            } else {
                elementData = payload
            }

            val elements = elementData?.let { scanElements(it, header.byteOrder) }
            if (elementData != null && !elementData.isEmpty() && elements != null && elements.lists.isEmpty()) {
                notes.add(
                    LoadNote.ElementStreamUnrecognized(
                        entry.segmentId,
                        "${elementData.size} bytes of element data without a recognizable frame",
                    ),
                )
            }
            return JtSegment(entry, headerId, typeCode, declaredLength, kind, payload, compression, elementData, elements)
        }

        /**
         * Looks inside a segment whose type Table 6 does not define. Returns the compression
         * header and decoded element data only when *every* check passes: the payload starts
         * with a well-formed Logical-Element-Header-Compressed field triple (Table 8/9 flag and
         * algorithm, declared length filling the payload exactly), the registered codec decodes
         * it, and the result frames at least one properly terminated element list. Anything else
         * yields `null` and the segment stays exactly as opaque as before — silently, because
         * the segment's type is already named by `UNKNOWN_SEGMENT_TYPE` and a failed probe adds
         * no information. NX 10.5's undefined types 23 and 31 pass it; see DESIGN.md.
         */
        private fun probeUndefinedType(
            header: FileHeader,
            entry: TocEntry,
            payload: Bytes,
        ): Pair<CompressionHeader, Bytes>? {
            if (payload.size < 9) return null
            val reader = ByteReader(payload.toByteArray(), header.byteOrder)
            val flag = reader.readU32()
            val compressedDataLength = reader.readI32()
            val algorithmCode = reader.readU8().toInt()
            if (flag != 2u && flag != 3u) return null
            if (algorithmCode != 2 && algorithmCode != 3) return null
            val compression = CompressionHeader(flag, compressedDataLength, algorithmCode)
            if (compression.bodyLength < 0 || 9 + compression.bodyLength != payload.size) return null
            val codec = SegmentCodecs.byAlgorithmCode(algorithmCode) ?: return null
            val decoded =
                when (val result = codec.decode(entry.segmentId, payload.slice(9, payload.size))) {
                    is CodecResult.Decoded -> result.data
                    is CodecResult.Refused -> return null
                }
            val scan = scanElements(decoded, header.byteOrder)
            if (scan.lists.isEmpty() || !scan.lists.first().terminated) return null
            return compression to decoded
        }

        private fun decodeCompressible(
            header: FileHeader,
            entry: TocEntry,
            payload: Bytes,
            notes: MutableList<LoadNote>,
            onHeader: (CompressionHeader) -> Unit,
        ): Bytes? {
            if (payload.size < 9) {
                notes.add(
                    LoadNote.CompressionHeaderInconsistent(
                        entry.segmentId,
                        "payload of ${payload.size} bytes cannot hold the compression fields",
                    ),
                )
                return null
            }
            val reader = ByteReader(payload.toByteArray(), header.byteOrder)
            val flag = reader.readU32()
            val compressedDataLength = reader.readI32()
            val algorithmCode = reader.readU8().toInt()
            val compression = CompressionHeader(flag, compressedDataLength, algorithmCode)
            onHeader(compression)
            if (compression.bodyLength < 0 || 9 + compression.bodyLength > payload.size) {
                notes.add(
                    LoadNote.CompressionHeaderInconsistent(
                        entry.segmentId,
                        "declared data length $compressedDataLength does not fit the ${payload.size}-byte payload",
                    ),
                )
                return null
            }
            val extra = payload.size - 9 - compression.bodyLength
            if (extra > 0) {
                notes.add(
                    LoadNote.CompressionHeaderInconsistent(
                        entry.segmentId,
                        "declared data length $compressedDataLength leaves $extra undeclared trailing bytes",
                    ),
                )
            }
            val body = payload.slice(9, 9 + compression.bodyLength)
            val codec = SegmentCodecs.byAlgorithmCode(algorithmCode)
            if (codec == null) {
                notes.add(LoadNote.UnknownCompressionAlgorithm(entry.segmentId, algorithmCode))
                return null
            }
            return when (val result = codec.decode(entry.segmentId, body)) {
                is CodecResult.Decoded -> result.data
                is CodecResult.Refused -> {
                    notes.add(result.note)
                    null
                }
            }
        }
    }
}
