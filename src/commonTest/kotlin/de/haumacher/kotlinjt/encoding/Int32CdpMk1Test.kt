package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.shape.Int32Cdp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The **Int32 Compressed Data Packet Mk. 1** of 9.5 §8.1.1 — the packet the JT B-Rep topology
 * streams and the whole NURBS curve machinery are written with, and which nothing in the byte
 * stream distinguishes from the Mk. 2 packet `Int32Cdp` reads.
 *
 * Every byte vector below is hand-built from Figure 218 (framing), Figure 219/220 (the
 * probability contexts) and Appendix C §2.1 / §3.1 (the two codecs' decoders); no fixture in the
 * corpus carries a Mk. 1 packet. Each decode is paired with a byte-identical re-encode, and the
 * two generations are fed each other's bytes to prove neither misreads the other in silence.
 */
class Int32CdpMk1Test {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    private fun roundTrip(bytes: ByteArray): Int32CdpMk1 {
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val packet = Int32CdpMk1.read(reader)
        assertEquals(bytes.size, reader.position, "packet must consume exactly its bytes")
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        packet.encode(writer)
        assertContentEquals(bytes, writer.toByteArray(), "encode(decode(packet)) must be byte-identical")
        return packet
    }

    // spec: 9.5 Figure 218
    @Test
    fun nullCodecCarriesItsOwnWordCountAndNoValueElementCount() {
        // Mk. 1's Null CODEC packet is four bytes shorter than the same data in Mk. 2: there is
        // no leading Value Count and no CodeText Length — the VecU32's own count is the value
        // count.
        val bytes =
            bytesOf {
                writeU8(0u)
                writeVecU32(listOf(7, -2, 123456))
            }
        val packet = roundTrip(bytes)
        assertIs<Int32CdpMk1.NullCodec>(packet)
        assertEquals(listOf(7, -2, 123456), packet.values)
        assertEquals(3, packet.valueCount)
    }

    // spec: 9.5 Figure 218
    @Test
    fun theEmptyNullCodecPacketIsTwoWordsAndDecodesToNothing() {
        // Mk. 1 has no "Value Count 0" short form at all (Figure 218 has no Value Count): the
        // shortest legal packet is a Null CODEC with an empty CodeText vector.
        val packet =
            roundTrip(
                bytesOf {
                    writeU8(0u)
                    writeVecU32(emptyList())
                },
            )
        assertEquals(emptyList(), packet.values)
    }

    // spec: 9.5 Appendix C §2.1
    @Test
    fun bitlengthWalksThePrefixCodeWithTwoBitSteps() {
        // Width 0 -> 4 by two increment bits ('1','1') and a terminator ('0'), then four 4-bit
        // signed fields, each preceded by a "same width" prefix bit.
        val code = BitWriter()
        code.writeBit(1).writeBit(1).writeBit(1).writeBit(0)
        code.writeBits(5, 4)
        code.writeBit(0).writeBits(6, 4)
        code.writeBit(0).writeBits(-3 and 0xF, 4)
        code.writeBit(0).writeBits(0, 4)
        val bytes =
            bytesOf {
                writeU8(1u)
                writeI32(code.bitCount)
                writeI32(4)
                writeVecU32(code.toWords())
            }
        val packet = roundTrip(bytes)
        assertIs<Int32CdpMk1.Bitlength>(packet)
        assertEquals(listOf(5, 6, -3, 0), packet.values)
    }

    // spec: 9.5 Appendix C §2.1
    @Test
    fun bitlengthZeroWidthFieldsEmitZerosWithoutConsumingBits() {
        // The corner case the reference closes with: a zero-width field emits a zero and
        // advances no bit, so the third "same width" prefix bit leaves the decoder in the
        // accumulate state with nothing left to read — and the closing clause ("If the last
        // symbol was zero and the current bit length is also zero, then the above loop
        // terminated before actually decoding the last zero-valued symbol") emits it. Three
        // prefix bits therefore decode to three zeros, two of them inside the loop and one
        // after it.
        val code = BitWriter()
        code.writeBit(0).writeBit(0).writeBit(0)
        val bytes =
            bytesOf {
                writeU8(1u)
                writeI32(code.bitCount)
                writeI32(3)
                writeVecU32(code.toWords())
            }
        val packet = roundTrip(bytes)
        assertEquals(listOf(0, 0, 0), packet.values)
    }

    /**
     * The strongest evidence available for the Mk. 1 arithmetic path: the CodeText and the
     * histogram are lifted verbatim from `Int32CdpTest.arithmeticCodecDecodesWithEscapeAndOutOfBand`,
     * whose byte vector was cross-checked against the reference algorithm and whose decode is
     * exercised by 506 fixture packets. Re-framed as a Mk. 1 packet — different field order,
     * different context layout, a `Next Context` field per entry, an explicit out-of-band count
     * — the *same bits* must produce the *same values*.
     *
     * spec: 9.5 Figure 218, Appendix C §3.1
     */
    @Test
    fun arithmeticDecodesTheSameCodeTextAsTheMk2PacketDoes() {
        val codeText = listOf(0x52C00000)
        val entries =
            listOf(
                TestEntry(symbol = 0, occurrenceCount = 3, associatedValue = 7),
                TestEntry(symbol = 1, occurrenceCount = 2, associatedValue = 9),
                TestEntry(symbol = -2, occurrenceCount = 1, associatedValue = 0),
            )
        val bytes =
            bytesOf {
                writeU8(3u)
                writeBytes(int32ProbabilityContexts1(listOf(entries)))
                writeI32(1)
                // The out-of-band packet is itself a Mk. 1 packet.
                writeU8(0u)
                writeVecU32(listOf(42))
                writeI32(24)
                writeI32(6)
                writeVecU32(codeText)
            }
        val packet = roundTrip(bytes)
        assertIs<Int32CdpMk1.Arithmetic>(packet)
        assertEquals(listOf(7, 9, 7, 42, 7, 9), packet.values)
        assertEquals(1, packet.probabilityContexts.tables.size)
        assertEquals(null, packet.symbolCount, "a single-table packet writes no Symbol Count")
        assertEquals(listOf(42), packet.outOfBand?.values)

        // And the same values out of the Mk. 2 packet the vector came from, so the claim
        // "same bits, same values" is checked rather than asserted.
        val mk2 =
            byteArrayOf(
                6, 0, 0, 0, 3, 24, 0, 0, 0, 0, 0, -64, 82, 0, 3, 12,
                49, 64, 0, 0, 0, 19, 59, 73, 4, 0, 1, 0, 0, 0, 0, 32,
                0, 0, 0, 42, 0, 0, 0,
            )
        assertEquals(packet.values, Int32Cdp.read(ByteReader(mk2, Endianness.LITTLE_ENDIAN)).values)
    }

    // Pins the test-side encoder against the fixture-verified decoder before anything relies on it.
    // spec: 9.5 Appendix C §3.2
    @Test
    fun theTestEncoderAgreesWithTheFixtureVerifiedDecoder() {
        val entries =
            listOf(
                TestEntry(symbol = 0, occurrenceCount = 3, associatedValue = 7),
                TestEntry(symbol = 1, occurrenceCount = 2, associatedValue = 9),
                TestEntry(symbol = -2, occurrenceCount = 1, associatedValue = 0),
            )
        val symbols = listOf(0, 1, 0, 2, 0, 1)
        val code = encodeSymbols(listOf(entries), symbols)
        val mk2 =
            bytesOf {
                writeI32(6)
                writeU8(3u)
                writeI32(code.bitCount)
                for (word in code.toWords()) writeI32(word)
                // The Mk. 2 context of Figure 222: no table count, no next context, U32{16}
                // entry count, 6+6+6 widths, U32{32} min value.
                val bits = BitWriter()
                bits.writeBits(3, 16)
                bits.writeBits(3, 6)
                bits.writeBits(3, 6)
                bits.writeBits(5, 6)
                bits.writeBits(0, 32)
                for (entry in entries) {
                    bits.writeBits(entry.symbol + 2, 3)
                    bits.writeBits(entry.occurrenceCount, 3)
                    bits.writeBits(entry.associatedValue, 5)
                }
                writeBytes(bits.toBytes())
                writeI32(1)
                writeU8(0u)
                writeI32(32)
                writeI32(42)
            }
        assertEquals(listOf(7, 9, 7, 42, 7, 9), Int32Cdp.read(ByteReader(mk2, Endianness.LITTLE_ENDIAN)).values)
    }

    /**
     * The two-table form, and with it the two fields Mk. 2 does not have: `I32 Symbol Count` and
     * the per-entry `Next Context`. The escape in table 1 is the document's own subtlety — "Only
     * if the Codec is using Probability Context Table 0 when it receives an Escape symbol does it
     * emit a Value from the 'Out-Of-Band' data array" — so seven symbols produce six values, and
     * Value Element Count is what says so.
     *
     * The CodeText is manufactured by this file's encoder, so what it proves is that the driver
     * switches context and skips the table-1 escape, not that a producer writes it this way.
     *
     * spec: 9.5 Figure 218, Figure 220, Appendix C §3.1
     */
    @Test
    fun twoTablesSwitchContextPerSymbolAndOnlyTableZeroSpendsOutOfBandValues() {
        val table0 =
            listOf(
                TestEntry(symbol = 0, occurrenceCount = 4, associatedValue = 10, nextContext = 1),
                TestEntry(symbol = -2, occurrenceCount = 1, associatedValue = 0, nextContext = 0),
            )
        val table1 =
            listOf(
                TestEntry(symbol = 0, occurrenceCount = 3, associatedValue = 20, nextContext = 0),
                TestEntry(symbol = -2, occurrenceCount = 1, associatedValue = 0, nextContext = 1),
            )
        val tables = listOf(table0, table1)
        // t0:value10 -> t1:value20 -> t0:escape(spends OOB) -> t0:value10 -> t1:escape(spends
        // nothing) -> t1:value20 -> t0:value10. Seven symbols, six values, one out-of-band value.
        val symbols = listOf(0, 0, 1, 0, 1, 0, 0)
        val code = encodeSymbols(tables, symbols)
        val bytes =
            bytesOf {
                writeU8(3u)
                writeBytes(int32ProbabilityContexts1(tables))
                writeI32(1)
                writeU8(0u)
                writeVecU32(listOf(-77))
                writeI32(code.bitCount)
                writeI32(6)
                writeI32(symbols.size)
                writeVecU32(code.toWords())
            }
        val packet = roundTrip(bytes)
        assertIs<Int32CdpMk1.Arithmetic>(packet)
        assertEquals(listOf(10, 20, -77, 10, 20, 10), packet.values)
        assertEquals(7, packet.symbolCount)
        assertEquals(2, packet.probabilityContexts.tables.size)
    }

    // spec: 9.5 §8.1.1 (p.253)
    @Test
    fun anAllOutOfBandPacketCopiesTheOutOfBandArray() {
        // "the encoded I32 : CodeText Length field will be 0, and the I32 : Out-Of-Band Value
        // Count will be equal to I32 : Value Element Count" — no codec is invoked at all.
        val entries = listOf(TestEntry(symbol = -2, occurrenceCount = 1, associatedValue = 0))
        val bytes =
            bytesOf {
                writeU8(3u)
                writeBytes(int32ProbabilityContexts1(listOf(entries)))
                writeI32(3)
                writeU8(0u)
                writeVecU32(listOf(4, 5, 6))
                writeI32(0)
                writeI32(3)
                writeVecU32(emptyList())
            }
        val packet = roundTrip(bytes)
        assertEquals(listOf(4, 5, 6), packet.values)
    }

    // spec: 9.5 Figure 218
    @Test
    fun anArithmeticPacketWithoutOutOfBandDataOmitsTheNestedPacket() {
        val entries =
            listOf(
                TestEntry(symbol = 0, occurrenceCount = 3, associatedValue = 7),
                TestEntry(symbol = 1, occurrenceCount = 1, associatedValue = 9),
            )
        val code = encodeSymbols(listOf(entries), listOf(0, 1, 0))
        val bytes =
            bytesOf {
                writeU8(3u)
                writeBytes(int32ProbabilityContexts1(listOf(entries)))
                writeI32(0)
                writeI32(code.bitCount)
                writeI32(3)
                writeVecU32(code.toWords())
            }
        val packet = roundTrip(bytes)
        assertIs<Int32CdpMk1.Arithmetic>(packet)
        assertEquals(null, packet.outOfBand, "Out-Of-Band Value Count 0 means no nested packet")
        assertEquals(listOf(7, 9, 7), packet.values)
    }

    // spec: 9.5 Figure 218
    @Test
    fun theChopperCodecIsRefusedByNameBecauseFigure218DrawsNoFieldsForIt() {
        val failure =
            assertFailsWith<JtFormatException> {
                Int32CdpMk1.read(ByteReader(byteArrayOf(4, 0, 0, 0, 0), Endianness.LITTLE_ENDIAN))
            }
        assertTrue(
            failure.message!!.contains("Chopper") && failure.message!!.contains("Mk. 2"),
            "the refusal must name the codec and where its layout actually lives: ${failure.message}",
        )
    }

    /**
     * The failure mode this package exists to prevent: 9.5 binds Mk. 1 and Mk. 2 *statically, per
     * field*, so a reader that picks the wrong one has no wire signal to catch itself with. Both
     * directions must fail loudly rather than produce a plausible-looking value list.
     *
     * spec: 9.5 §8.1.1, §8.1.2
     */
    @Test
    fun eachGenerationRefusesTheOtherGenerationsPacket() {
        val mk1 =
            bytesOf {
                writeU8(0u)
                writeVecU32(listOf(11, 22, 33))
            }
        val mk2 =
            bytesOf {
                writeI32(3)
                writeU8(0u)
                writeI32(96)
                writeI32(11)
                writeI32(22)
                writeI32(33)
            }
        // Sanity: each reads its own.
        assertEquals(listOf(11, 22, 33), Int32CdpMk1.read(ByteReader(mk1, Endianness.LITTLE_ENDIAN)).values)
        assertEquals(listOf(11, 22, 33), Int32Cdp.read(ByteReader(mk2, Endianness.LITTLE_ENDIAN)).values)

        // Mk. 2's reader on Mk. 1 bytes: the codec byte lands in the Value Count word, so the
        // packet claims a wildly wrong length and refuses.
        assertFailsWith<JtFormatException> { Int32Cdp.read(ByteReader(mk1, Endianness.LITTLE_ENDIAN)) }
        // Mk. 1's reader on Mk. 2 bytes: the low byte of the Value Count is read as a CODEC
        // type. Here that byte is 3, so the reader walks into a probability context that is not
        // there and refuses on the table count — a named refusal, just not the pointed one.
        val misread = assertFailsWith<JtFormatException> { Int32CdpMk1.read(ByteReader(mk2, Endianness.LITTLE_ENDIAN)) }
        assertTrue(
            misread.message!!.contains("Probability Context Table Count"),
            "the refusal must say what it choked on: ${misread.message}",
        )
        // When the low byte of the Value Count is not a legal CODEC type at all — the common
        // case, since only 4 of 256 byte values are — the refusal names the other reader.
        val wrongCodec =
            assertFailsWith<JtFormatException> {
                Int32CdpMk1.read(
                    ByteReader(
                        bytesOf {
                            writeI32(5)
                            writeU8(0u)
                        },
                        Endianness.LITTLE_ENDIAN,
                    ),
                )
            }
        assertTrue(
            wrongCodec.message!!.contains("Int32Cdp"),
            "the refusal must point at the other generation's reader: ${wrongCodec.message}",
        )
    }

    // spec: 9.5 Figure 219
    @Test
    fun malformedPacketsRefuseInsteadOfMisreading() {
        // A probability context claiming three tables.
        assertFailsWith<JtFormatException> {
            Int32CdpMk1.read(ByteReader(byteArrayOf(3, 3, 0, 0, 0, 0), Endianness.LITTLE_ENDIAN))
        }
        // A CodeText vector longer than the input.
        assertFailsWith<JtFormatException> {
            Int32CdpMk1.read(
                ByteReader(
                    bytesOf {
                        writeU8(0u)
                        writeI32(1_000_000)
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
        // A bitlength packet whose Value Element Count disagrees with what the stream decodes to.
        val code = BitWriter().writeBit(0)
        assertFailsWith<JtFormatException> {
            Int32CdpMk1.read(
                ByteReader(
                    bytesOf {
                        writeU8(1u)
                        writeI32(code.bitCount)
                        writeI32(9)
                        writeVecU32(code.toWords())
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
        // A CodeText Length that exceeds the stored words.
        assertFailsWith<JtFormatException> {
            Int32CdpMk1.read(
                ByteReader(
                    bytesOf {
                        writeU8(1u)
                        writeI32(200)
                        writeI32(1)
                        writeVecU32(listOf(0))
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
    }

    // spec: 9.5 Figure 220
    @Test
    fun aNextContextOutsideTheTableListIsRefused() {
        val entries = listOf(TestEntry(symbol = 0, occurrenceCount = 1, associatedValue = 1, nextContext = 1))
        val bytes =
            bytesOf {
                writeU8(3u)
                writeBytes(int32ProbabilityContexts1(listOf(entries)))
                writeI32(0)
                writeI32(0)
                writeI32(0)
                writeVecU32(emptyList())
            }
        val failure = assertFailsWith<JtFormatException> { Int32CdpMk1.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN)) }
        assertTrue(failure.message!!.contains("Next Context"), failure.message)
    }
}
