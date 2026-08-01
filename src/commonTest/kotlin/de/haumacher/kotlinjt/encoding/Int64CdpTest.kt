package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The Int64 Compressed Data Packet (§12.1.2, Figures 135–137). Hand-built byte vectors per the
 * spec's own rules — including its "low-order 32 bits first" convention for every scalar wider
 * than 32 bits — each decode paired with a byte-identical re-encode. The real-producer evidence
 * for this packet lives in `WireframeFixtureTest`, whose five bodies carry 12 arithmetic, 2
 * bitlength and 1 move-to-front Int64 packet.
 */
class Int64CdpTest {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    private fun roundTrip(
        bytes: ByteArray,
        externallyCompressed: Boolean = false,
    ): Int64Cdp {
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val cdp = Int64Cdp.read(reader, externallyCompressed = externallyCompressed)
        assertEquals(bytes.size, reader.position, "packet must consume exactly its bytes")
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        cdp.encode(writer)
        assertContentEquals(bytes, writer.toByteArray(), "encode(decode(packet)) must be byte-identical")
        return cdp
    }

    /** A null-CODEC Int64 packet: the values as plain 64-bit words, low-order word first. */
    private fun ByteWriter.writeNullPacket(values: List<Long>) {
        writeI32(values.size)
        writeU8(0u)
        writeI32(64 * values.size)
        for (value in values) {
            writeI32((value and 0xFFFFFFFFL).toInt())
            writeI32((value shr 32).toInt())
        }
    }

    // spec: Figure 135
    @Test
    fun emptyPacketIsJustTheCount() {
        assertIs<Int64Cdp.Empty>(roundTrip(bytesOf { writeI32(0) }))
    }

    // spec: Figure 135
    @Test
    fun nullCodecCarriesPlainSixtyFourBitWords() {
        val values = listOf(7L, -2L, 0x0123_4567_89AB_CDEFL, Long.MIN_VALUE)
        val cdp = roundTrip(bytesOf { writeNullPacket(values) })
        assertIs<Int64Cdp.NullCodec>(cdp)
        assertEquals(values, cdp.values)
    }

    // spec: Figure 135, Annex B `BitLengthCodec3T<Int64>`
    @Test
    fun bitlengthFixedWidthReadsWholeSixtyFourBitMinAndMax() {
        // Mode tag 0; min = 4 and max = 7 written as full 64-bit values ("Simply write out all
        // the bits for 64 bit" — Annex B `nibblerEmit(Int64)`); then four 2-bit fields biased
        // by min: 1, 0, 1, 3 => [5, 4, 5, 7].
        val bytes =
            bytesOf {
                writeI32(4)
                writeU8(1u)
                writeI32(137)
                writeI32(0x00000002)
                writeI32(0x00000000)
                writeI32(0x00000003)
                writeI32(0x80000000.toInt())
                writeI32(0x23800000)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int64Cdp.Bitlength>(cdp)
        assertEquals(listOf(5L, 4L, 5L, 7L), cdp.values)
    }

    /**
     * §12.1.1's degenerate arithmetic case: "all values may be written as 'out of band' when
     * the Codec cannot perform any useful compression. In this case, the encoded CodeText Length
     * field will be 0, and the I32: Out-of-Band Value Count will be equal to I32: Value Count."
     * This is also the shortest way to exercise the Figure 136 probability context by hand.
     *
     * spec: Figures 135, 136, 137
     */
    @Test
    fun arithmeticWithEmptyCodeTextTakesEveryValueOutOfBandNested() {
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(3u)
                writeI32(0)
                // Context: 1 entry, occurrence-count bits 6, value bits 7, min value 0 (64 bits,
                // low-order word first), entry = escape with occurrence 1 and value 0.
                writeBytes(ESCAPE_ONLY_CONTEXT)
                writeNullPacket(listOf(11L, -12L))
            }
        val cdp = roundTrip(bytes)
        assertIs<Int64Cdp.Arithmetic>(cdp)
        assertEquals(listOf(11L, -12L), cdp.values)
        assertIs<Int64OutOfBand.Nested>(cdp.outOfBand)
        assertEquals(0L, cdp.probabilityContext.minValue)
        assertEquals(1, cdp.probabilityContext.entries.size)
    }

    /**
     * The same packet in an *externally compressed* segment: Figure 135 branches the out-of-band
     * field to an `I32` count plus plain `VecI64` values there — which is the form every
     * Wireframe Rep body of the NIST fixture uses (DESIGN.md delta 37).
     *
     * spec: Figure 135
     */
    @Test
    fun arithmeticOutOfBandIsCountPlusPlainValuesWhenTheSegmentIsCompressed() {
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(3u)
                writeI32(0)
                writeBytes(ESCAPE_ONLY_CONTEXT)
                writeI32(2)
                writeI32(11)
                writeI32(0)
                writeI32(-12)
                writeI32(-1)
            }
        val cdp = roundTrip(bytes, externallyCompressed = true)
        assertIs<Int64Cdp.Arithmetic>(cdp)
        assertEquals(listOf(11L, -12L), cdp.values)
        assertIs<Int64OutOfBand.Raw>(cdp.outOfBand)
    }

    // spec: Figure 135
    @Test
    fun chopperRecombinesMsbAndLsbFields() {
        // Chop 4 of 8 significant bits, bias 1000: value = (lsb | msb << 4) + 1000.
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(4u)
                writeU8(4u)
                writeI32(1000)
                writeI32(0)
                writeU8(8u)
                writeNullPacket(listOf(2L, 3L))
                writeNullPacket(listOf(1L, 15L))
            }
        val cdp = roundTrip(bytes)
        assertIs<Int64Cdp.Chopper>(cdp)
        assertEquals(listOf(1000L + 0x21, 1000L + 0x3F), cdp.values)
        assertEquals(1000L, cdp.valueBias)
    }

    // spec: Figure 135, §12.1.1 (Move-to-Front pseudo-CODEC)
    @Test
    fun moveToFrontReplaysTheRecencyWindow() {
        // Offsets: −1 pulls the next window value, any other offset reuses (and fronts) the
        // window entry. Values 5, 9 then offset 1 (the older entry, 5) then 0 (5 again).
        val bytes =
            bytesOf {
                writeI32(4)
                writeU8(5u)
                writeNullPacket(listOf(5L, 9L))
                writeNullPacket(listOf(-1L, -1L, 1L, 0L))
            }
        val cdp = roundTrip(bytes)
        assertIs<Int64Cdp.MoveToFront>(cdp)
        assertEquals(listOf(5L, 9L, 5L, 5L), cdp.values)
    }

    @Test
    fun anUndefinedCodecRefusesInsteadOfGuessing() {
        val bytes =
            bytesOf {
                writeI32(1)
                writeU8(2u)
            }
        assertFailsWith<JtFormatException> { Int64Cdp.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }

    @Test
    fun aContextWithTwoEscapeEntriesRefuses() {
        // Two escape entries would make the out-of-band order ambiguous; Figure 137 allows one.
        val bytes =
            bytesOf {
                writeI32(1)
                writeU8(3u)
                writeI32(0)
                writeBytes(TWO_ESCAPE_CONTEXT)
            }
        assertFailsWith<JtFormatException> { Int64Cdp.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }

    @Test
    fun aTruncatedOutOfBandVectorRefuses() {
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(3u)
                writeI32(0)
                writeBytes(ESCAPE_ONLY_CONTEXT)
                writeI32(1000)
            }
        assertFailsWith<JtFormatException> {
            Int64Cdp.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN), externallyCompressed = true)
        }
    }

    private companion object {
        /** Figure 136/137: one escape entry, occurrence 1, value 0, min 0 — 107 bits, 14 bytes. */
        val ESCAPE_ONLY_CONTEXT =
            byteArrayOf(0x00, 0x01, 0x18, 0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x10, 0x00)

        /** The same table with two escape entries — illegal. */
        val TWO_ESCAPE_CONTEXT =
            byteArrayOf(0x00, 0x02, 0x18, 0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x10, 0x08, 0x20, 0x00)
    }
}
