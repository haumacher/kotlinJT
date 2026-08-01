package de.haumacher.kotlinjt.codec

/**
 * The XZ container decoder behind JT v10 segment-wide "LZMA" compression. Clause 12.2.5
 * specifies LZMA via XZ Utils (`lzma_easy_encoder` / `lzma_stream_decoder` — the `.xz`
 * entry points of liblzma), and every real v10 stream observed begins with the `.xz`
 * stream magic; the payload filter is LZMA2 (see DESIGN.md for the byte evidence).
 *
 * Pure Kotlin, platform-free: XZ stream framing per the XZ file format specification,
 * LZMA2/LZMA1 decoding in [lzma2Decode]. Strictness policy:
 * - structure, header CRC32s, the index and the footer are fully verified;
 * - block check types none/CRC32/CRC64 are verified (CRC64 is what XZ Utils writes by
 *   default and what the real JT streams carry); the defined-but-unseen SHA-256 check is
 *   consumed without verification (the XZ spec sizes all 16 check IDs precisely so that
 *   decoders can skip the ones they do not implement) — its verification's time comes with
 *   the first real stream that uses it;
 * - reserved check IDs, reserved flags, non-LZMA2 filter chains and multi-filter chains
 *   refuse ([XzException.unsupported] distinguishes "well-formed but not implemented"
 *   from corruption).
 */

private val CRC32_TABLE: IntArray =
    IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        c
    }

internal fun crc32(
    data: ByteArray,
    from: Int = 0,
    to: Int = data.size,
): Int {
    var crc = -1
    for (i in from until to) {
        crc = CRC32_TABLE[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv()
}

/** ECMA-182 reflected polynomial — the CRC64 variant XZ uses. */
private val CRC64_TABLE: LongArray =
    LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) {
            c = if (c and 1L != 0L) (c ushr 1) xor -0x3693a86a2878f0beL else c ushr 1
        }
        c
    }

internal fun crc64(
    data: ByteArray,
    from: Int = 0,
    to: Int = data.size,
): Long {
    var crc = -1L
    for (i in from until to) {
        crc = CRC64_TABLE[((crc xor data[i].toLong()) and 0xFF).toInt()] xor (crc ushr 8)
    }
    return crc.inv()
}

/** Bytes of the check field per check ID — Table of the XZ format specification §2.1.1.2. */
private val CHECK_SIZES = intArrayOf(0, 4, 4, 4, 8, 8, 8, 16, 16, 16, 32, 32, 32, 64, 64, 64)

private const val CHECK_NONE = 0
private const val CHECK_CRC32 = 1
private const val CHECK_CRC64 = 4
private const val CHECK_SHA256 = 10

/** The check IDs the XZ specification defines; all others are reserved. */
private val DEFINED_CHECKS = setOf(CHECK_NONE, CHECK_CRC32, CHECK_CRC64, CHECK_SHA256)

private const val FILTER_LZMA2 = 0x21L

private class XzReader(val data: ByteArray) {
    var pos = 0

    fun u8(): Int {
        if (pos >= data.size) throw XzException("xz stream truncated at offset $pos")
        return data[pos++].toInt() and 0xFF
    }

    fun u32le(): Long {
        var value = 0L
        for (i in 0 until 4) value = value or (u8().toLong() shl (8 * i))
        return value
    }

    fun u64le(): Long {
        var value = 0L
        for (i in 0 until 8) value = value or (u8().toLong() shl (8 * i))
        return value
    }

    /** XZ variable-length integer: 7 bits per byte, little-endian, at most 9 bytes. */
    fun vli(): Long {
        var value = (u8()).toLong()
        if (value and 0x80L == 0L) return value
        value = value and 0x7FL
        for (i in 1 until 9) {
            val b = u8()
            value = value or ((b and 0x7F).toLong() shl (7 * i))
            if (b and 0x80 == 0) {
                if (b == 0) throw XzException("non-minimal xz variable-length integer")
                return value
            }
        }
        throw XzException("xz variable-length integer longer than 9 bytes")
    }

    fun require(
        count: Int,
        what: String,
    ) {
        if (pos + count > data.size) throw XzException("xz stream truncated: $what")
    }
}

private class BlockSizes(val unpaddedSize: Long, val uncompressedSize: Long)

/**
 * Decompresses one complete `.xz` stream (with optional trailing zero padding) to its plain
 * bytes. Throws [XzException] on corrupt/truncated input or on well-formed input using
 * features outside this decoder (see the class note above).
 */
fun xzDecompress(data: ByteArray): ByteArray {
    val r = XzReader(data)

    // Stream header: magic, flags, CRC32 of the flags.
    r.require(12, "stream header")
    val magic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    for (i in 0 until 6) {
        if (data[i] != magic[i]) throw XzException("not an xz stream: bad magic bytes")
    }
    r.pos = 6
    val flagsFirst = r.u8()
    val flagsSecond = r.u8()
    if (flagsFirst != 0 || flagsSecond and 0xF0 != 0) {
        throw XzException("reserved xz stream flag bits set")
    }
    val checkType = flagsSecond and 0x0F
    if (checkType !in DEFINED_CHECKS) {
        throw XzException("reserved xz check id $checkType", unsupported = true)
    }
    if (r.u32le() != (crc32(data, 6, 8).toLong() and 0xFFFFFFFFL)) {
        throw XzException("xz stream header CRC32 mismatch")
    }

    val out = LzmaOutput()
    val blocks = mutableListOf<BlockSizes>()

    // Blocks, until the index indicator byte (0x00) appears where a block header would start.
    while (true) {
        r.require(1, "block header or index")
        if (data[r.pos].toInt() == 0) break
        blocks.add(decodeBlock(r, checkType, out))
    }

    // Index: indicator, record count, one record per block, padding, CRC32.
    val indexStart = r.pos
    r.u8() // the 0x00 indicator
    val recordCount = r.vli()
    if (recordCount != blocks.size.toLong()) {
        throw XzException("xz index lists $recordCount blocks, stream has ${blocks.size}")
    }
    for (block in blocks) {
        if (r.vli() != block.unpaddedSize) throw XzException("xz index unpadded size mismatch")
        if (r.vli() != block.uncompressedSize) throw XzException("xz index uncompressed size mismatch")
    }
    while ((r.pos - indexStart) % 4 != 0) {
        if (r.u8() != 0) throw XzException("non-zero xz index padding")
    }
    val indexSize = r.pos - indexStart
    if (r.u32le() != (crc32(data, indexStart, r.pos - 4).toLong() and 0xFFFFFFFFL)) {
        throw XzException("xz index CRC32 mismatch")
    }

    // Stream footer: CRC32 of (backward size, flags), backward size, flags, footer magic.
    r.require(12, "stream footer")
    val footerCrc = r.u32le()
    val footerBodyStart = r.pos
    val backwardSize = (r.u32le() + 1) * 4
    if (backwardSize != (indexSize + 4).toLong()) {
        throw XzException("xz stream footer backward size does not match the index size")
    }
    val footerFlagsFirst = r.u8()
    val footerFlagsSecond = r.u8()
    if (footerFlagsFirst != flagsFirst || footerFlagsSecond != flagsSecond) {
        throw XzException("xz stream footer flags differ from the stream header flags")
    }
    if (r.u8() != 'Y'.code || r.u8() != 'Z'.code) {
        throw XzException("bad xz stream footer magic")
    }
    if (footerCrc != (crc32(data, footerBodyStart, footerBodyStart + 6).toLong() and 0xFFFFFFFFL)) {
        throw XzException("xz stream footer CRC32 mismatch")
    }

    // Only zero padding (in multiples of four) may follow a stream.
    val paddingStart = r.pos
    while (r.pos < data.size) {
        if (r.u8() != 0) throw XzException("trailing bytes after the xz stream")
    }
    if ((r.pos - paddingStart) % 4 != 0) {
        throw XzException("xz stream padding is not a multiple of four bytes")
    }

    return out.toByteArray()
}

private fun decodeBlock(
    r: XzReader,
    checkType: Int,
    out: LzmaOutput,
): BlockSizes {
    val data = r.data
    val headerStart = r.pos
    val headerSize = (r.u8() + 1) * 4
    r.require(headerSize - 1, "block header")
    val flags = r.u8()
    if (flags and 0x3C != 0) throw XzException("reserved xz block header flag bits set")
    val filterCount = (flags and 0x03) + 1
    val declaredCompressedSize = if (flags and 0x40 != 0) r.vli() else null
    val declaredUncompressedSize = if (flags and 0x80 != 0) r.vli() else null

    var dictSize = 0L
    for (i in 0 until filterCount) {
        val filterId = r.vli()
        val propsSize = r.vli()
        if (filterCount != 1 || filterId != FILTER_LZMA2 || propsSize != 1L) {
            throw XzException(
                "xz filter chain not supported: filter id 0x${filterId.toString(16)} " +
                    "($filterCount filters); only a single LZMA2 filter is implemented",
                unsupported = true,
            )
        }
        dictSize = lzma2DictSize(r.u8())
    }
    while (r.pos < headerStart + headerSize - 4) {
        if (r.u8() != 0) throw XzException("non-zero xz block header padding")
    }
    if (r.u32le() != (crc32(data, headerStart, headerStart + headerSize - 4).toLong() and 0xFFFFFFFFL)) {
        throw XzException("xz block header CRC32 mismatch")
    }

    // Block data: the LZMA2 chunk sequence.
    val dataStart = r.pos
    val outStart = out.position
    r.pos = lzma2Decode(data, r.pos, data.size, dictSize, out)
    val compressedSize = (r.pos - dataStart).toLong()
    val uncompressedSize = (out.position - outStart).toLong()
    if (declaredCompressedSize != null && declaredCompressedSize != compressedSize) {
        throw XzException("xz block compressed size $compressedSize contradicts the declared $declaredCompressedSize")
    }
    if (declaredUncompressedSize != null && declaredUncompressedSize != uncompressedSize) {
        throw XzException("xz block uncompressed size $uncompressedSize contradicts the declared $declaredUncompressedSize")
    }

    // Block padding to a multiple of four, then the check value.
    while ((r.pos - dataStart) % 4 != 0) {
        if (r.u8() != 0) throw XzException("non-zero xz block padding")
    }
    val checkSize = CHECK_SIZES[checkType]
    r.require(checkSize, "block check")
    when (checkType) {
        CHECK_CRC32 -> {
            val stored = r.u32le()
            val actual = crc32(out.slice(outStart)).toLong() and 0xFFFFFFFFL
            if (stored != actual) throw XzException("xz block CRC32 check mismatch")
        }
        CHECK_CRC64 -> {
            val stored = r.u64le()
            if (stored != crc64(out.slice(outStart))) throw XzException("xz block CRC64 check mismatch")
        }
        else -> r.pos += checkSize // none: empty; SHA-256: consumed unverified (see the file note)
    }

    return BlockSizes(headerSize + compressedSize + checkSize, uncompressedSize)
}

/** LZMA2 dictionary size from its one-byte encoding (XZ format specification §5.3.1). */
private fun lzma2DictSize(props: Int): Long {
    if (props and 0xC0 != 0) throw XzException("reserved bits set in the LZMA2 properties byte")
    val bits = props and 0x3F
    if (bits > 40) throw XzException("invalid LZMA2 dictionary size code $bits")
    if (bits == 40) return 0xFFFFFFFFL
    return (2L or (bits and 1).toLong()) shl (bits / 2 + 11)
}
