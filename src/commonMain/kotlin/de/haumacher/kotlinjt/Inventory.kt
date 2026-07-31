package de.haumacher.kotlinjt

/**
 * The forensic layer's first answer: every segment with GUID, type, offset, length and
 * compression, human-readable. This is what bug triage runs first on every reported file.
 */
fun JtFile.inventory(): String {
    val sb = StringBuilder()
    sb.append("JT file: version ").append(header.version)
        .append(", ").append(if (header.byteOrder.headerByte == 0) "little-endian" else "big-endian")
        .append(", ").append(fileSize).append(" bytes\n")
    sb.append("TOC at ").append(toc.offset).append(" (").append(toc.entries.size).append(" entries), LSG segment ")
        .append(header.lsgSegmentId).append('\n')
    sb.append("idx  offset    length    type                           compression                    elements\n")
    var index = 0
    for (region in regions) {
        when (region) {
            is FileRegion.SegmentRegion -> {
                val segment = region.segment
                val kindLabel = segment.kind?.label ?: "UNKNOWN"
                sb.append(index.toString().padStart(3)).append("  ")
                    .append(segment.offset.toString().padEnd(8)).append("  ")
                    .append(segment.length.toString().padEnd(8)).append("  ")
                    .append("${segment.typeCode} $kindLabel".padEnd(29)).append("  ")
                    .append(segment.compressionLabel.padEnd(29)).append("  ")
                    .append(describeElements(segment))
                if (region.shadowed) sb.append("  [SHADOWED]")
                sb.append('\n')
                sb.append("     ").append(segment.tocEntry.segmentId).append('\n')
                index++
            }
            is FileRegion.TocRegion -> {
                sb.append("     TOC region at ").append(region.offset)
                    .append(", ").append(region.length).append(" bytes")
                if (region.shadowed) sb.append("  [SHADOWED]")
                sb.append('\n')
            }
            is FileRegion.GapRegion -> {
                sb.append("     unmapped region at ").append(region.offset)
                    .append(", ").append(region.length).append(" bytes (preserved)\n")
            }
        }
    }
    if (notes.isEmpty()) {
        sb.append("notes: none\n")
    } else {
        sb.append("notes:\n")
        for (note in notes) {
            sb.append("  - ").append(note).append('\n')
        }
    }
    return sb.toString()
}

private fun describeElements(segment: JtSegment): String {
    val scan = segment.elements ?: return "opaque"
    val lists =
        scan.lists.joinToString("+") { list ->
            val n = list.elements.count { !it.isEndMarker }
            if (list.terminated) "$n" else "$n?"
        }
    val trailing = if (scan.trailing.isEmpty()) "" else ", trailing ${scan.trailing.size} B"
    return "lists [$lists]$trailing"
}

/**
 * A canonical JSON rendering of the inventory, used as the content of fixture expectation
 * sidecar files. Deterministic: the same file always renders the same string. [hash] can add
 * a content digest per payload (supplied by the platform test code, e.g. SHA-256 on the JVM).
 */
fun JtFile.inventoryJson(hash: ((ByteArray) -> String)? = null): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"fileSize\": ").append(fileSize).append(",\n")
    sb.append("  \"version\": \"").append(header.version).append("\",\n")
    sb.append("  \"byteOrder\": \"").append(header.byteOrder.name).append("\",\n")
    sb.append("  \"tocOffset\": ").append(toc.offset).append(",\n")
    sb.append("  \"lsgSegmentId\": \"").append(header.lsgSegmentId).append("\",\n")
    sb.append("  \"noteNames\": [")
    sb.append(notes.joinToString(", ") { "\"${it.name}\"" })
    sb.append("],\n")
    sb.append("  \"segments\": [\n")
    val segmentRegions = regions.filterIsInstance<FileRegion.SegmentRegion>()
    for ((i, region) in segmentRegions.withIndex()) {
        val segment = region.segment
        sb.append("    {")
        sb.append("\"guid\": \"").append(segment.tocEntry.segmentId).append("\", ")
        sb.append("\"type\": ").append(segment.typeCode).append(", ")
        sb.append("\"typeName\": \"").append(segment.kind?.label ?: "UNKNOWN").append("\", ")
        sb.append("\"offset\": ").append(segment.offset).append(", ")
        sb.append("\"length\": ").append(segment.length).append(", ")
        sb.append("\"compression\": ")
        val compression = segment.compression
        if (compression == null) {
            sb.append("null")
        } else {
            sb.append("{\"flag\": ").append(compression.flag)
                .append(", \"algorithm\": ").append(compression.algorithmCode)
                .append(", \"dataLength\": ").append(compression.compressedDataLength).append("}")
        }
        sb.append(", \"elementLists\": ")
        val scan = segment.elements
        if (scan == null) {
            sb.append("null, \"trailingBytes\": null")
        } else {
            sb.append("[").append(scan.lists.joinToString(", ") { it.elements.size.toString() }).append("]")
            sb.append(", \"trailingBytes\": ").append(scan.trailing.size)
        }
        if (hash != null) {
            sb.append(", \"payloadSha\": \"").append(hash(segment.payload.toByteArray())).append("\"")
        }
        if (region.shadowed) {
            sb.append(", \"shadowed\": true")
        }
        sb.append("}")
        if (i < segmentRegions.size - 1) sb.append(",")
        sb.append('\n')
    }
    sb.append("  ]\n")
    sb.append("}\n")
    return sb.toString()
}
