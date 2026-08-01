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
import kotlin.test.assertNull

/**
 * The third-generation Int32 Compressed Data Packet (v10 reference §12.1.1, Figure 132) —
 * the wire format the NIST 10.5 fixture's shape bodies carry (issue #6; the JT 9 "Mk. 2"
 * packet is [Int32CdpTest]'s subject). Hand-built byte vectors, cross-checked against the
 * Annex B reference algorithms; every decode is paired with a byte-identical re-encode.
 */
class Int32CdpV10Test {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    private fun roundTrip(bytes: ByteArray): Int32Cdp {
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val cdp = Int32Cdp.readV10(reader)
        assertEquals(bytes.size, reader.position, "packet must consume exactly its bytes")
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        cdp.encode(writer)
        assertContentEquals(bytes, writer.toByteArray(), "encode(decode(packet)) must be byte-identical")
        return cdp
    }

    // spec: Figure 132
    @Test
    fun emptyPacketIsJustTheCount() {
        val cdp = roundTrip(bytesOf { writeI32(0) })
        assertIs<Int32Cdp.Empty>(cdp)
    }

    // spec: Figure 132
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

    // spec: §12.2.2 (Annex B BitLengthCodec, fixed-width mode)
    @Test
    fun bitlengthFixedWidthDecodesWithNibbledMinMax() {
        // Mode tag 0; min = 4 and max = 7 as 4-bit nibbles with continue bits; then
        // seven 2-bit fields biased by min. Vector: [5, 4, 5, 5, 7, 4, 6].
        val bytes =
            bytesOf {
                writeI32(7)
                writeU8(1u)
                writeI32(25)
                writeI32(0x21C8B900)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Bitlength>(cdp)
        assertEquals(listOf(5, 4, 5, 5, 7, 4, 6), cdp.values)
    }

    // spec: §12.2.2 (Annex B BitLengthCodec, variable-width mode)
    @Test
    fun bitlengthVariableWidthDecodesWithFourBitBlocks() {
        // Mode tag 1; mean 100 nibbled; then blocks of (4-bit width deltas, 4-bit run
        // length, signed fields + mean). Vector: [100, 101, 99, 130, 70, 100].
        val bytes =
            bytesOf {
                writeI32(6)
                writeU8(1u)
                writeI32(55)
                writeI32(0xA58463A1.toInt())
                writeI32(0x3D160800)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Bitlength>(cdp)
        assertEquals(listOf(100, 101, 99, 130, 70, 100), cdp.values)
    }

    // spec: Figure 133
    @Test
    fun arithmeticCodecDecodesWithEscapeAndNestedOutOfBand() {
        // Context: value 7 (occurrence 3), value 9 (occurrence 2), escape (occurrence 1);
        // encoded sequence 7 9 7 <escape->42> 7 9. CodeText and context bits generated with
        // the Annex B reference encoder.
        val bytes =
            bytesOf {
                writeI32(6)
                writeU8(3u)
                writeI32(26)
                writeI32(0x52C00000)
                // Probability context: count 3, occ bits 6, value bits 7, min 0; entries
                // (7, occ 3), (9, occ 2), (escape, occ 1); aligned to 13 bytes.
                writeBytes(
                    byteArrayOf(0x00, 0x03, 0x18, 0x38, 0x00, 0x00, 0x00, 0x00, 0x30, 0xE0.toByte(), 0x84.toByte(), 0xC1.toByte(), 0x00),
                )
                // Out-of-band packet (present because the context has an escape entry).
                writeI32(1)
                writeU8(0u)
                writeI32(32)
                writeI32(42)
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.ArithmeticV10>(cdp)
        assertEquals(listOf(7, 9, 7, 42, 7, 9), cdp.values)
        assertEquals(3, cdp.probabilityContext.entries.size)
        assertEquals(listOf(42), cdp.outOfBand?.values)
    }

    // spec: Figure 132
    @Test
    fun arithmeticWithoutEscapeCarriesNoOutOfBandPacket() {
        // Context: value 7 (occurrence 3), value 9 (occurrence 2), no escape entry — the
        // out-of-band packet is absent from the wire (fixture-established, DESIGN.md).
        val bytes =
            bytesOf {
                writeI32(5)
                writeU8(3u)
                writeI32(22)
                writeI32(0x6C000000)
                writeBytes(byteArrayOf(0x00, 0x02, 0x18, 0x38, 0x00, 0x00, 0x00, 0x00, 0x30, 0xE0.toByte(), 0x84.toByte(), 0x80.toByte()))
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.ArithmeticV10>(cdp)
        assertEquals(listOf(7, 9, 7, 7, 9), cdp.values)
        assertNull(cdp.outOfBand)
    }

    // spec: §12.1.1 (Move-to-Front pseudo-CODEC)
    @Test
    fun moveToFrontReplaysTheRecencyWindow() {
        // Offsets: escape (−1) pulls the next window value to the front; other offsets
        // reuse and front the cached entry. Sequence: 7 9 7 7 9 3 9.
        fun ByteWriter.nullPacket(values: List<Int>) {
            writeI32(values.size)
            writeU8(0u)
            writeI32(values.size * 32)
            for (v in values) writeI32(v)
        }
        val bytes =
            bytesOf {
                writeI32(7)
                writeU8(5u)
                nullPacket(listOf(7, 9, 3)) // window values
                nullPacket(listOf(-1, -1, 1, 0, 1, -1, 1)) // window offsets
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.MoveToFront>(cdp)
        assertEquals(listOf(7, 9, 7, 7, 9, 3, 9), cdp.values)
    }

    // spec: Figure 132
    @Test
    fun chopperRecombinesMsbAndLsbFields() {
        // Values 0x012, 0x034, 0x056 biased by 10, span 9 bits, chopped at 4.
        val values = listOf(0x012, 0x034, 0x056)
        val bias = 10
        val span = 9
        val chop = 4
        val shift = span - chop
        val bytes =
            bytesOf {
                writeI32(3)
                writeU8(4u)
                writeU8(chop.toUByte())
                writeI32(bias)
                writeU8(span.toUByte())
                writeI32(3)
                writeU8(0u)
                writeI32(96)
                for (v in values) writeI32((v - bias) shr shift)
                writeI32(3)
                writeU8(0u)
                writeI32(96)
                for (v in values) writeI32((v - bias) and ((1 shl shift) - 1))
            }
        val cdp = roundTrip(bytes)
        assertIs<Int32Cdp.Chopper>(cdp)
        assertEquals(values, cdp.values)
    }

    // spec: §12.1.1 ("The number of Chop Bits is always greater than 0")
    @Test
    fun chopperWithZeroChopBitsRefuses() {
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(4u)
                writeU8(0u)
                writeI32(2)
                writeU8(0u)
                writeI32(64)
                writeI32(1)
                writeI32(2)
            }
        assertFailsWith<JtFormatException> { Int32Cdp.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }

    // spec: Table 64 (CODEC type 2 is an illegal value)
    @Test
    fun illegalCodecTypeRefuses() {
        val bytes =
            bytesOf {
                writeI32(1)
                writeU8(2u)
            }
        assertFailsWith<JtFormatException> { Int32Cdp.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }

    // spec: Table 64 (Move-to-Front is a v10 CODEC — the JT 9 packet has no type 5)
    @Test
    fun moveToFrontInTheJt9GenerationRefuses() {
        val bytes =
            bytesOf {
                writeI32(1)
                writeU8(5u)
            }
        assertFailsWith<JtFormatException> { Int32Cdp.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }

    // spec: §12.1.1 ("the maximum recursion depth may not exceed eight")
    @Test
    fun nestingDeeperThanEightRefuses() {
        // Ten nested move-to-front packets exceed the depth limit before any leaf decodes.
        val bytes =
            bytesOf {
                repeat(10) {
                    writeI32(1)
                    writeU8(5u)
                }
                writeI32(0)
            }
        assertFailsWith<JtFormatException> { Int32Cdp.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }

    @Test
    fun moveToFrontOffsetOutsideTheWindowRefuses() {
        fun ByteWriter.nullPacket(values: List<Int>) {
            writeI32(values.size)
            writeU8(0u)
            writeI32(values.size * 32)
            for (v in values) writeI32(v)
        }
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(5u)
                nullPacket(listOf(7))
                nullPacket(listOf(-1, 3)) // offset 3 with a 1-entry window
            }
        assertFailsWith<JtFormatException> { Int32Cdp.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
    }
}
