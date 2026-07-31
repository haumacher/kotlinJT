package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.codec.CodecResult
import de.haumacher.kotlinjt.codec.LzmaCodec
import de.haumacher.kotlinjt.codec.SegmentCodecs
import de.haumacher.kotlinjt.codec.ZlibCodec
import de.haumacher.kotlinjt.codec.ZlibException
import de.haumacher.kotlinjt.codec.zlibDeflate
import de.haumacher.kotlinjt.codec.zlibInflate
import de.haumacher.kotlinjt.io.toBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

// spec: Figure 16 — segment compression fields (§5.4)

/** Exercises the zlib `expect`/`actual` seam on every platform the tests run on. */
class ZlibTest {
    @Test
    fun roundTrip() {
        val data = ByteArray(10_000) { (it * 31 % 251).toByte() }
        assertContentEquals(data, zlibInflate(zlibDeflate(data)))
        assertContentEquals(ByteArray(0), zlibInflate(zlibDeflate(ByteArray(0))))
    }

    @Test
    fun inflatesKnownStream() {
        // zlib-compressed "kotlinJT Layer 0: bytes are the truth" (level 6), fixed bytes so
        // both platform engines prove they read the same wire format.
        val stream =
            byteArrayOf(
                0x78, 0x9C.toByte(), 0xCB.toByte(), 0xCE.toByte(), 0x2F, 0xC9.toByte(), 0xC9.toByte(),
                0xCC.toByte(), 0xF3.toByte(), 0x0A, 0x51, 0xF0.toByte(), 0x49, 0xAC.toByte(), 0x4C,
                0x2D, 0x52, 0x30, 0xB0.toByte(), 0x52, 0x48, 0xAA.toByte(), 0x2C, 0x49, 0x2D, 0x56,
                0x48, 0x2C, 0x4A, 0x55, 0x28, 0xC9.toByte(), 0x00, 0xE2.toByte(), 0xA2.toByte(),
                0xD2.toByte(), 0x92.toByte(), 0x0C, 0x00, 0xF8.toByte(), 0x7E, 0x0D, 0x2E,
            )
        val expected = "kotlinJT Layer 0: bytes are the truth"
        val inflated = zlibInflate(stream)
        assertEquals(expected, inflated.map { it.toInt().toChar() }.joinToString(""))
    }

    @Test
    fun corruptStreamThrowsZlibException() {
        assertFailsWith<ZlibException> { zlibInflate(byteArrayOf(1, 2, 3, 4)) }
        assertFailsWith<ZlibException> { zlibInflate(ByteArray(0)) }
        // Truncated: a valid stream cut short.
        val full = zlibDeflate(ByteArray(1000) { it.toByte() })
        assertFailsWith<ZlibException> { zlibInflate(full.copyOf(full.size / 2)) }
    }

    @Test
    fun invalidLevelThrows() {
        assertFailsWith<ZlibException> { zlibDeflate(ByteArray(1), 42) }
    }

    @Test
    fun zlibCodecRefusesCorruptDataWithNamedNote() {
        val result = ZlibCodec.decode(testGuid(1), byteArrayOf(1, 2, 3).toBytes())
        val refusal = assertIs<CodecResult.Refused>(result)
        assertEquals("COMPRESSED_DATA_CORRUPT", refusal.note.name)
    }

    @Test
    fun lzmaCodecIsANamedFutureExtension() {
        val result = LzmaCodec.decode(testGuid(1), byteArrayOf(0).toBytes())
        val refusal = assertIs<CodecResult.Refused>(result)
        assertEquals("UNSUPPORTED_COMPRESSION", refusal.note.name)
    }

    @Test
    fun registryDispatchesOnAlgorithmCode() {
        assertEquals("none", SegmentCodecs.byAlgorithmCode(1)?.label)
        assertEquals("zlib", SegmentCodecs.byAlgorithmCode(2)?.label)
        assertEquals("lzma", SegmentCodecs.byAlgorithmCode(3)?.label)
        assertNull(SegmentCodecs.byAlgorithmCode(7))
    }
}
