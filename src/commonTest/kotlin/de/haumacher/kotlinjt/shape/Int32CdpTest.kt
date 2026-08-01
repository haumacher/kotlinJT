package de.haumacher.kotlinjt.shape

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
 * The Int32 Compressed Data Packet in the JT 9 generation's wire format ("Mk. 2" of the JT
 * 9.5 reference §8.1.2 — the v10 packet of Figure 132 is a different wire format, deferred to
 * its first decodable v10 fixture; see DESIGN.md and the ledger). Hand-built byte vectors,
 * independently cross-checked against the reference decoders; every decode is paired with a
 * byte-identical re-encode.
 */
class Int32CdpTest {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    private fun roundTrip(bytes: ByteArray): Int32Cdp {
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val cdp = Int32Cdp.read(reader)
        assertEquals(bytes.size, reader.position, "packet must consume exactly its bytes")
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        cdp.encode(writer)
        assertContentEquals(bytes, writer.toByteArray(), "encode(decode(packet)) must be byte-identical")
        return cdp
    }

    // spec: §12.1.1
    @Test
    fun emptyPacketIsJustTheCount() {
        val cdp = roundTrip(bytesOf { writeI32(0) })
        assertIs<Int32Cdp.Empty>(cdp)
        assertEquals(emptyList(), cdp.values)
    }

    // spec: §12.1.1
    @Test
    fun nullCodecCarriesPlainWords() {
        val bytes =
            bytesOf {
                writeI32(3)
                writeU8(0u)
                writeI32(96)
                writeI32(7)
                writeI32(-2)
                writeI32(123456)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.NullCodec>(cdp)
        assertEquals(listOf(7, -2, 123456), cdp.values)
    }

    // spec: §12.2.2
    @Test
    fun bitlengthFixedWidthDecodes() {
        // Mode tag 0, 6+6-bit min/max widths, signed min/max, then unsigned fields + min.
        val bytes =
            bytesOf {
                writeI32(7)
                writeU8(1u)
                writeI32(35)
                writeI32(0x08223a2e)
                writeI32(0x40000000)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Bitlength>(cdp)
        assertEquals(listOf(5, 4, 5, 5, 7, 4, 6), cdp.values)
    }

    // spec: §12.2.2
    @Test
    fun bitlengthVariableWidthDecodes() {
        // Mode tag 1, 32-bit mean (100), 3+3-bit field/run widths, runs of signed fields.
        val bytes =
            bytesOf {
                writeI32(6)
                writeU8(1u)
                writeI32(83)
                writeI32(0x80000032.toInt())
                writeI32(0x3648a765)
                writeI32(0xbd100000.toInt())
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Bitlength>(cdp)
        assertEquals(listOf(100, 101, 99, 130, 70, 100), cdp.values)
    }

    // spec: §12.2.3
    @Test
    fun arithmeticCodecDecodesWithEscapeAndOutOfBand() {
        // Context: value 7 (occurrence 3), value 9 (occurrence 2), escape (occurrence 1);
        // encoded sequence 7 9 7 <escape->42> 7 9. Vector cross-checked against the Annex B
        // reference algorithm.
        val bytes =
            byteArrayOf(
                6, 0, 0, 0, 3, 24, 0, 0, 0, 0, 0, -64, 82, 0, 3, 12,
                49, 64, 0, 0, 0, 19, 59, 73, 4, 0, 1, 0, 0, 0, 0, 32,
                0, 0, 0, 42, 0, 0, 0,
            )
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Arithmetic>(cdp)
        assertEquals(listOf(7, 9, 7, 42, 7, 9), cdp.values)
        assertEquals(3, cdp.probabilityContext.entries.size)
        assertEquals(listOf(42), cdp.outOfBand.values)
    }

    // spec: §12.1.1
    @Test
    fun chopperRecombinesMsbAndLsbFields() {
        // Values 0x012, 0x034, 0x056 biased by 10, span 9 bits, chopped at 4:
        // MSB = value >> 5, LSB = value & 0x1F.
        val values = listOf(0x012, 0x034, 0x056)
        val bias = 10
        val span = 9
        val chop = 4
        val bytes =
            bytesOf {
                writeI32(3)
                writeU8(4u)
                writeU8(chop.toUByte())
                writeI32(bias)
                writeU8(span.toUByte())
                // MSB packet (null codec)
                writeI32(3)
                writeU8(0u)
                writeI32(96)
                for (v in values) writeI32((v - bias) shr (span - chop))
                // LSB packet (null codec)
                writeI32(3)
                writeU8(0u)
                writeI32(96)
                for (v in values) writeI32((v - bias) and ((1 shl (span - chop)) - 1))
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Chopper>(cdp)
        assertEquals(values, cdp.values)
    }

    // spec: §12.1.1
    @Test
    fun chopperWithZeroChopBitsDefersToNestedPacket() {
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(4u)
                writeU8(0u)
                writeI32(2)
                writeU8(0u)
                writeI32(64)
                writeI32(11)
                writeI32(22)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.ChopperPassthrough>(cdp)
        assertEquals(listOf(11, 22), cdp.values)
    }

    @Test
    fun malformedPacketsRefuseInsteadOfMisreading() {
        // Unknown codec byte.
        assertFailsWith<JtFormatException> {
            Int32Cdp.read(ByteReader(bytesOf { writeI32(1) } + byteArrayOf(2), Endianness.LITTLE_ENDIAN))
        }
        // CodeText longer than the input.
        assertFailsWith<JtFormatException> {
            Int32Cdp.read(
                ByteReader(
                    bytesOf {
                        writeI32(1)
                        writeU8(1u)
                        writeI32(1_000_000)
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
        // Negative count.
        assertFailsWith<JtFormatException> {
            Int32Cdp.read(ByteReader(bytesOf { writeI32(-5) }, Endianness.LITTLE_ENDIAN))
        }
    }

    // spec: Table 2
    @Test
    fun predictorsUnpackResiduals() {
        // The first four residuals are primers; from the fifth on the predictor applies.
        assertEquals(listOf(1, 2, 3, 4, 5), unpackResiduals(listOf(1, 2, 3, 4, 5), Predictor.NONE))
        assertEquals(listOf(1, 2, 3, 4, 9), unpackResiduals(listOf(1, 2, 3, 4, 5), Predictor.LAG1))
        assertEquals(listOf(1, 2, 3, 4, 8), unpackResiduals(listOf(1, 2, 3, 4, 5), Predictor.LAG2))
        assertEquals(listOf(1, 2, 3, 4, 4 xor 5), unpackResiduals(listOf(1, 2, 3, 4, 5), Predictor.XOR1))
        assertEquals(listOf(0, 0, 0, 0, 4), unpackResiduals(listOf(0, 0, 0, 0, 0), Predictor.RAMP))
        // Stride1: predicted = v1 + (v1 - v2) = 4 + 1 = 5.
        assertEquals(listOf(1, 2, 3, 4, 10), unpackResiduals(listOf(1, 2, 3, 4, 5), Predictor.STRIDE1))
    }
}
