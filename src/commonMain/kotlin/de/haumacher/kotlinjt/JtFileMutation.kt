package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid

/**
 * Replaces the payload of the segment named [segmentId] and re-lays the file out: every
 * region keeps its order, offsets and the TOC (offsets, the changed length, the header's TOC
 * offset) are recomputed, and the result is re-parsed so all derived views are consistent.
 *
 * This is the Layer 1 write seam for modified segments: unmodified segments re-emit their raw
 * payloads byte-identically, the modified one gets a fresh, legal layout — equality of the
 * result is asserted at the model level, not the byte level (DESIGN.md).
 *
 * A mutation API, not a load path: files with overlapping regions or duplicated segment ids
 * are refused with [IllegalArgumentException] — nothing here ever runs on unvalidated input.
 */
fun JtFile.withSegmentPayload(
    segmentId: Guid,
    payload: Bytes,
): JtFile {
    require(regions.none { it.shadowed }) { "cannot re-layout a file with overlapping regions" }
    require(segments.count { it.tocEntry.segmentId == segmentId } == 1) {
        "segment $segmentId must exist exactly once"
    }

    // Pass 1: assign new offsets in region order.
    fun regionLength(region: FileRegion): Long =
        when {
            region is FileRegion.SegmentRegion && region.segment.tocEntry.segmentId == segmentId ->
                24L + payload.size
            else -> region.length
        }

    var cursor = header.headerLength.toLong()
    val newOffsets = mutableMapOf<Guid, Long>()
    var newTocOffset = -1L
    for (region in regions) {
        when (region) {
            is FileRegion.SegmentRegion -> newOffsets[region.segment.tocEntry.segmentId] = cursor
            is FileRegion.TocRegion -> newTocOffset = cursor
            is FileRegion.GapRegion -> {}
        }
        cursor += regionLength(region)
    }
    check(newTocOffset >= 0) { "file without a TOC region" }

    // Pass 2: write the new image.
    val writer = ByteWriter(header.byteOrder, cursor.toInt())
    header.copy(tocOffset = newTocOffset).writeTo(writer)
    for (region in regions) {
        when (region) {
            is FileRegion.SegmentRegion -> {
                val segment = region.segment
                if (segment.tocEntry.segmentId == segmentId) {
                    writer.writeGuid(segment.headerId)
                    writer.writeI32(segment.typeCode)
                    writer.writeI32(24 + payload.size)
                    writer.writeBytes(payload)
                } else {
                    segment.writeTo(writer)
                }
            }
            is FileRegion.TocRegion -> {
                writer.writeI32(toc.entries.size)
                for (entry in toc.entries) {
                    val updated =
                        entry.copy(
                            offset = newOffsets[entry.segmentId] ?: entry.offset,
                            length = if (entry.segmentId == segmentId) 24L + payload.size else entry.length,
                        )
                    updated.writeTo(writer, header.version)
                }
            }
            is FileRegion.GapRegion -> writer.writeBytes(region.bytes)
        }
    }
    check(writer.size.toLong() == cursor) { "re-layout drift: wrote ${writer.size}, expected $cursor" }
    return JtFile.parse(writer.toByteArray())
}
