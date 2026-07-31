package de.haumacher.kotlinjt.codec

/** Failure of a zlib operation: corrupt or truncated stream, bad parameters. */
class ZlibException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Inflates a complete zlib stream (RFC 1950 framing, as written by JT producers for
 * segment-wide ZLIB compression). Throws [ZlibException] on corrupt or truncated input.
 */
expect fun zlibInflate(data: ByteArray): ByteArray

/**
 * Deflates [data] into a zlib stream at the given compression [level] (0–9).
 * Throws [ZlibException] on bad parameters.
 */
expect fun zlibDeflate(
    data: ByteArray,
    level: Int,
): ByteArray

fun zlibDeflate(data: ByteArray): ByteArray = zlibDeflate(data, DEFAULT_DEFLATE_LEVEL)

/**
 * The default matches both `java.util.zip` and pako, and — observed on the reference
 * fixture — the level real producers use (see DESIGN.md).
 */
const val DEFAULT_DEFLATE_LEVEL: Int = 6
