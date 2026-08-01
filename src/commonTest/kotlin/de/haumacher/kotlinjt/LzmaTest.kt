package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.codec.CodecResult
import de.haumacher.kotlinjt.codec.LzmaCodec
import de.haumacher.kotlinjt.codec.XzException
import de.haumacher.kotlinjt.codec.xzDecompress
import de.haumacher.kotlinjt.io.toBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// spec: §12.2.5 — LZMA compression (XZ Utils' .xz container, LZMA2 filter)

/**
 * The pure-Kotlin XZ/LZMA2 decoder against known vectors written by the reference
 * implementation (liblzma via Python's `lzma` / the `xz` tool), plus the hostile-input
 * paths. The vectors are committed as hex constants so both platforms prove they read the
 * same wire format; the real-producer streams are covered by the fixture battery.
 */
class LzmaTest {
    private val message = "kotlinJT reads the installed base's v10 files: LZMA is xz on the wire"

    /** liblzma, level 6, CHECK_CRC64 — the XZ Utils default and what real JT streams use. */
    private val crc64Vector =
        (
            "fd377a585a000004e6d6b4460200210116000000742fe5a30100446b6f746c696e4a54207265616473207468" +
                "6520696e7374616c6c656420626173652773207631302066696c65733a204c5a4d4120697320787a206f6e20" +
                "746865207769726500000000d1239fedaf571bc900015d452d2325291fb6f37d010000000004595a"
        ).hexToBytes()

    /** liblzma, level 6, CHECK_CRC32. */
    private val crc32Vector =
        (
            "fd377a585a0000016922de360200210116000000742fe5a30100446b6f746c696e4a54207265616473207468" +
                "6520696e7374616c6c656420626173652773207631302066696c65733a204c5a4d4120697320787a206f6e20" +
                "74686520776972650000000060347cea0001594529e6494d9042990d010000000001595a"
        ).hexToBytes()

    /** liblzma, level 6, CHECK_NONE. */
    private val noCheckVector =
        (
            "fd377a585a000000ff12d9410200210116000000742fe5a30100446b6f746c696e4a54207265616473207468" +
                "6520696e7374616c6c656420626173652773207631302066696c65733a204c5a4d4120697320787a206f6e20" +
                "7468652077697265000000000001554525a9fce106729e7a010000000000595a"
        ).hexToBytes()

    /** liblzma, level 6, CHECK_SHA256 — defined by the XZ spec, consumed unverified here. */
    private val sha256Vector =
        (
            "fd377a585a00000ae1fb0ca10200210116000000742fe5a30100446b6f746c696e4a54207265616473207468" +
                "6520696e7374616c6c656420626173652773207631302066696c65733a204c5a4d4120697320787a206f6e20" +
                "74686520776972650000000064f522363a661da5a21da25e65f09970058c39d48a94ee55a868966303355584" +
                "00017545878d7874189b4b9a01000000000a595a"
        ).hexToBytes()

    /** liblzma: the empty input, CHECK_CRC64 — a stream with zero blocks. */
    private val emptyVector = "fd377a585a000004e6d6b446000000001cdf44211fb6f37d010000000004595a".hexToBytes()

    /** Hand-assembled per the XZ spec: one uncompressed LZMA2 chunk (control 0x01), CRC64. */
    private val uncompressedChunkVector =
        (
            "fd377a585a000004e6d6b4460200210100000000372797d601001573746f7265642c206e6f7420636f6d70" +
                "7265737365640000" + "0018ba8ce536786a9300012e16560955df1fb6f37d010000000004595a"
        ).hexToBytes()

    /** `xz --block-size=1024` over 2360 bytes: three blocks, one index listing them all. */
    private val multiBlockVector =
        (
            "fd377a585a000004e6d6b4460200210116000000742fe5a3e003ff00415d00369d49bd02f8d176af0cb5abb4" +
                "ddebea9d59215ab2362513a0f76a57f816c19f1cec8359e773ecc55cae7a317d35afd24210a9e28f8acdfa6f" +
                "393705298c2e960000000000cfa41a255d6a510a0200210116000000742fe5a3e003ff00405d00371908e00d" +
                "f79ecb9c724ed8e1b0971aa9bab430cbbc1a75bc730f00558445002c3baa0c52c06ed9414ba725cc69a9a382" +
                "85a48ea6b9eae2f7dadd672b1c9e0000d17086fa7804a06e0200210116000000742fe5a3e00137003b5d0031" +
                "1b0a4221b041d4eced34f1b988f695b026cce900659d755f29a659aeb10b9d7d1aaaec5cd32811c45d61ac83" +
                "11590e3834300ac59a01fbbc20000000e2432cf191526a5500035d80085c800857b802008790797e14173b30" +
                "030000000004595a"
        ).hexToBytes()

    /** liblzma with a delta+LZMA2 filter chain — well-formed, outside this decoder. */
    private val deltaFilterVector =
        (
            "fd377a585a000004e6d6b44602010301002101167920c4ee0100446b0405f8fd05dc0acc52f3fc030fad54f4" +
                "fdbb49050501ed0b00f9ffbc42ff12f2c24cad56bbfff0460303f90ec7e62c0ef3f4df490aad5802a64fffb2" +
                "54f4fdbb57f209f300000000d1239fedaf571bc900015d452d2325291fb6f37d010000000004595a"
        ).hexToBytes()

    @Test
    fun decodesTheReferenceEncodersStreams() {
        assertEquals(message, xzDecompress(crc64Vector).decodeToString())
        assertEquals(message, xzDecompress(crc32Vector).decodeToString())
        assertEquals(message, xzDecompress(noCheckVector).decodeToString())
    }

    @Test
    fun decodesTheEmptyStream() {
        assertContentEquals(ByteArray(0), xzDecompress(emptyVector))
    }

    @Test
    fun decodesUncompressedChunks() {
        assertEquals("stored, not compressed", xzDecompress(uncompressedChunkVector).decodeToString())
    }

    @Test
    fun decodesMultiBlockStreamsAndVerifiesTheIndex() {
        val expected = "multi-block xz: the index must list every block precisely. ".repeat(40)
        assertEquals(expected, xzDecompress(multiBlockVector).decodeToString())
    }

    @Test
    fun consumesSha256CheckedStreamsUnverified() {
        // SHA-256 is a defined check id; the spec sizes it so decoders can pass over it.
        assertEquals(message, xzDecompress(sha256Vector).decodeToString())
    }

    @Test
    fun acceptsTrailingStreamPadding() {
        assertEquals(message, xzDecompress(crc64Vector + ByteArray(4)).decodeToString())
    }

    @Test
    fun refusesNonLzma2FilterChainsAsUnsupported() {
        val e = assertFailsWith<XzException> { xzDecompress(deltaFilterVector) }
        assertTrue(e.unsupported, "a well-formed foreign filter chain must refuse as unsupported, not corrupt")
    }

    @Test
    fun corruptStreamsThrowWithoutUnsupportedFlag() {
        fun assertCorrupt(bytes: ByteArray) {
            val e = assertFailsWith<XzException> { xzDecompress(bytes) }
            assertTrue(!e.unsupported, "corruption must not be flagged unsupported: ${e.message}")
        }
        assertCorrupt(ByteArray(0))
        assertCorrupt(byteArrayOf(1, 2, 3, 4))
        // Bad magic.
        assertCorrupt(crc64Vector.copyOf().also { it[0] = 0x42 })
        // Truncation at every structural boundary.
        assertCorrupt(crc64Vector.copyOf(11))
        assertCorrupt(crc64Vector.copyOf(crc64Vector.size / 2))
        assertCorrupt(crc64Vector.copyOf(crc64Vector.size - 1))
        // A flipped payload byte: the block CRC64 check must catch it.
        assertCorrupt(crc64Vector.copyOf().also { it[40] = (it[40] + 1).toByte() })
        // A flipped stream-header CRC.
        assertCorrupt(crc64Vector.copyOf().also { it[8] = (it[8].toInt() xor 0x01).toByte() })
        // Trailing garbage after the stream.
        assertCorrupt(crc64Vector + byteArrayOf(0, 0, 0, 1))
    }

    @Test
    fun lzmaCodecDecodesAndRefusesWithNamedNotes() {
        val decoded = assertIs<CodecResult.Decoded>(LzmaCodec.decode(testGuid(1), crc64Vector.toBytes()))
        assertEquals(message, decoded.data.toByteArray().decodeToString())

        val corrupt = assertIs<CodecResult.Refused>(LzmaCodec.decode(testGuid(1), byteArrayOf(0).toBytes()))
        assertEquals("COMPRESSED_DATA_CORRUPT", corrupt.note.name)

        val unsupported = assertIs<CodecResult.Refused>(LzmaCodec.decode(testGuid(1), deltaFilterVector.toBytes()))
        assertEquals("UNSUPPORTED_COMPRESSION", unsupported.note.name)
    }

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0)
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
