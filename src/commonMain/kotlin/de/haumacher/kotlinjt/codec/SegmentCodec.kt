package de.haumacher.kotlinjt.codec

import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid

/**
 * The one interface all segment-wide compression algorithms sit behind (Table 9 of the JT
 * v10 reference; the v9 table names ZLIB where v10 names LZMA — codes are shared).
 *
 * Decoding never throws through this interface: a codec that cannot produce the plain bytes
 * returns a [CodecResult.Refused] carrying a *named* [LoadNote]; the raw bytes stay available
 * on the segment regardless.
 */
interface SegmentCodec {
    /** The value of the U8 Compression Algorithm field this codec is registered for. */
    val algorithmCode: Int

    /** Human-readable algorithm name for diagnostics ("none", "zlib", "lzma"). */
    val label: String

    /** Decodes [body] (the bytes following the Compression Algorithm field) to plain bytes. */
    fun decode(
        segmentId: Guid,
        body: Bytes,
    ): CodecResult
}

sealed class CodecResult {
    /** The plain (decoded) bytes. */
    data class Decoded(val data: Bytes) : CodecResult()

    /** Decoding is not possible; [note] says why. The raw bytes remain the segment's truth. */
    data class Refused(val note: LoadNote) : CodecResult()
}

/** Algorithm code 1: data stored uncompressed. */
object NoneCodec : SegmentCodec {
    override val algorithmCode: Int get() = 1
    override val label: String get() = "none"

    override fun decode(
        segmentId: Guid,
        body: Bytes,
    ): CodecResult = CodecResult.Decoded(body)
}

/** Algorithm code 2: ZLIB, the segment-wide compression of JT 8/9 files. */
object ZlibCodec : SegmentCodec {
    override val algorithmCode: Int get() = 2
    override val label: String get() = "zlib"

    override fun decode(
        segmentId: Guid,
        body: Bytes,
    ): CodecResult =
        try {
            CodecResult.Decoded(Bytes.of(zlibInflate(body.toByteArray())))
        } catch (e: ZlibException) {
            CodecResult.Refused(LoadNote.CompressedDataCorrupt(segmentId, e.message ?: "zlib error"))
        }
}

/**
 * Algorithm code 3: LZMA, the segment-wide compression of JT 10 files (clause 12.2.5).
 * On the wire this is the `.xz` container with an LZMA2 filter — clause 12.2.5 specifies
 * LZMA via XZ Utils' `.xz` entry points, and every real v10 stream observed carries the
 * `.xz` magic (byte evidence in DESIGN.md). Decode-only: the writer keeps emitting
 * ZLIB/none per the issue #1 version/codec policy.
 */
object LzmaCodec : SegmentCodec {
    override val algorithmCode: Int get() = 3
    override val label: String get() = "lzma"

    override fun decode(
        segmentId: Guid,
        body: Bytes,
    ): CodecResult =
        try {
            CodecResult.Decoded(Bytes.of(xzDecompress(body.toByteArray())))
        } catch (e: XzException) {
            CodecResult.Refused(
                if (e.unsupported) {
                    LoadNote.UnsupportedCompression(segmentId, algorithmCode, "LZMA (${e.message})")
                } else {
                    LoadNote.CompressedDataCorrupt(segmentId, e.message ?: "xz/lzma error")
                },
            )
        }
}

/** The codec registry keyed by the U8 Compression Algorithm field. */
object SegmentCodecs {
    private val codecs: Map<Int, SegmentCodec> =
        listOf(NoneCodec, ZlibCodec, LzmaCodec).associateBy { it.algorithmCode }

    fun byAlgorithmCode(code: Int): SegmentCodec? = codecs[code]
}
