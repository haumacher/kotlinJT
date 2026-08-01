package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.encodeLsgSegmentPayload
import de.haumacher.kotlinjt.scene.Scene

/**
 * A scene this writer cannot turn into a JT file that reads back as the same scene. Every
 * refusal names what is wrong and what to change; the writer never emits a file that would
 * silently mean something else (see [LsgAuthor] for the representability rules).
 */
class JtWriteException(
    message: String,
) : IllegalArgumentException(message)

/** The file format version this writer emits — one version, read broadly (issue #1). */
private val WRITTEN_VERSION = JtVersion(10, 0)

/** The 80-byte version string of clause 5.1.1, exactly as Figure 11 prescribes it. */
internal const val WRITER_VERSION_LINE: String = "Version 10.0 JT kotlinJT"

/**
 * Writes [scene] as a JT file image (clause 5): the file header, one Logical Scene Graph
 * segment authored from the scene's structure, names, transforms, materials and units, and one
 * Shape LOD segment per mesh and per LOD tier, followed by the TOC.
 *
 * Everything is written in the **simplest encodings the reference permits** (issue #1's
 * version/codec policy — decisions recorded in DESIGN.md): file version 10.0, no segment-wide
 * compression (Table 8/9 know only LZMA and "none" in v10, and this library has no LZMA
 * encoder yet), the null CODEC for every compressed data packet, and lossless binary-float
 * vertex coordinates and normals. The result is deterministic: the same scene always produces
 * the same bytes.
 *
 * @throws JtWriteException when the scene cannot be represented faithfully — undeclared units,
 *   a node carrying both geometry and children, or a child node the Layer 2 read collapse would
 *   splice out. The message names the offending path.
 */
fun writeJt(
    scene: Scene,
    byteOrder: Endianness = Endianness.LITTLE_ENDIAN,
): ByteArray {
    val authored = LsgAuthor(scene, byteOrder).build()
    val lsgPayload = encodeLsgSegmentPayload(authored.document.encode(byteOrder), WRITTEN_VERSION, byteOrder)
    val lsgSegmentId = writerGuid(0)
    val segments =
        listOf(AuthoredSegment(lsgSegmentId, SegmentKind.LOGICAL_SCENE_GRAPH.code, lsgPayload)) + authored.shapeSegments

    // Layout: header, segments in order, TOC last (clause 5.1: the TOC may sit anywhere the
    // header's offset points at; the end keeps offsets computable in one pass).
    val headerLength = 80 + 1 + 4 + 8 + 16
    var offset = headerLength.toLong()
    val offsets = ArrayList<Long>(segments.size)
    for (segment in segments) {
        offsets.add(offset)
        offset += SEGMENT_HEADER_SIZE + segment.payload.size
    }
    val tocOffset = offset

    val writer = ByteWriter(byteOrder, (tocOffset + 4 + segments.size * TOC_ENTRY_SIZE).toInt())
    writer.writeBytes(versionStringBytes(WRITER_VERSION_LINE))
    writer.writeU8(byteOrder.headerByte.toUByte())
    // Clause 4.3: the empty field of a freshly written file is zero, so no trailing GUID.
    writer.writeI32(0)
    writer.writeU64(tocOffset.toULong())
    writer.writeGuid(lsgSegmentId)
    check(writer.size == headerLength) { "header drift: wrote ${writer.size}, expected $headerLength" }

    for (segment in segments) {
        // spec: Figure 15 — Segment Header: GUID, I32 type, I32 total segment length.
        writer.writeGuid(segment.segmentId)
        writer.writeI32(segment.typeCode)
        writer.writeI32(SEGMENT_HEADER_SIZE + segment.payload.size)
        writer.writeBytes(segment.payload)
    }
    check(writer.size.toLong() == tocOffset) { "segment layout drift: wrote ${writer.size}, expected $tocOffset" }

    // spec: Figure 12/13 — the TOC: entry count, then one entry per segment.
    writer.writeI32(segments.size)
    for ((index, segment) in segments.withIndex()) {
        writer.writeGuid(segment.segmentId)
        writer.writeU64(offsets[index].toULong())
        writer.writeU32((SEGMENT_HEADER_SIZE + segment.payload.size).toUInt())
        writer.writeU32(segment.typeCode.toUInt() shl 24)
    }
    return writer.toByteArray()
}

/**
 * Writes [scene] and parses the result back into the Layer 0 model — the form tests use to
 * inspect what was written (segment inventory, notes, hashes) without re-reading a file.
 */
fun writeJtFile(
    scene: Scene,
    byteOrder: Endianness = Endianness.LITTLE_ENDIAN,
): JtFile = JtFile.parse(writeJt(scene, byteOrder))

/** Figure 15: GUID + I32 type + I32 length. */
private const val SEGMENT_HEADER_SIZE = 24

/** Figure 13: GUID + U64 offset + U32 length + U32 attributes. */
private const val TOC_ENTRY_SIZE = 32

/**
 * The 80-character version string of clause 5.1.1: the version line padded with spaces to 75
 * characters, then the five ASCII/binary translation detection bytes `' ' \n \r \n ' '`.
 */
internal fun versionStringBytes(versionLine: String): ByteArray {
    require(versionLine.length <= 75) { "version line '$versionLine' exceeds 75 characters" }
    val padded = versionLine.padEnd(75, ' ') + " \n\r\n "
    return ByteArray(80) { padded[it].code.toByte() }
}

/**
 * The segment identity of the [index]th authored segment. Deterministic by design: identical
 * scenes must produce identical files (golden pinning), which random GUIDs would prevent.
 * Segment ids only need to be unique within their file, which a per-file index guarantees.
 */
internal fun writerGuid(index: Int): Guid =
    Guid(
        (index + 1).toUInt(),
        KOTLINJT_GUID_MARKER,
        WRITER_GUID_REVISION,
        // "kotlinJT" — the writer's fingerprint in every segment id it mints.
        byteArrayOf(0x6B, 0x6F, 0x74, 0x6C, 0x69, 0x6E, 0x4A, 0x54).toBytes(),
    )

private const val KOTLINJT_GUID_MARKER: UShort = 0x4B4Au
private const val WRITER_GUID_REVISION: UShort = 0x0001u
