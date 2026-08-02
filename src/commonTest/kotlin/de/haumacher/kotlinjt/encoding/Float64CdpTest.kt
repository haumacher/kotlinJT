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
import kotlin.test.assertTrue

/**
 * The **Float64 Compressed Data Packet** of 9.5 §8.1.3 — the codec every JT 9 NURBS weight,
 * control point and knot vector goes through, and which has no v10 counterpart at all (v10
 * replaced it with `Int64CDP`, a different mechanism).
 *
 * Framing is built here from Figure 224 and Figures 225/226; the arithmetic CodeText comes from
 * `CdpTestSupport`'s encoder, which `Int32CdpMk1Test` pins against the fixture-verified decoder
 * first. No fixture in the corpus carries a Float64 packet.
 */
class Float64CdpTest {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    private fun roundTrip(bytes: ByteArray): Float64Cdp {
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val packet = Float64Cdp.read(reader)
        assertEquals(bytes.size, reader.position, "packet must consume exactly its bytes")
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        packet.encode(writer)
        assertContentEquals(bytes, writer.toByteArray(), "encode(decode(packet)) must be byte-identical")
        return packet
    }

    /** Figure 225/226: a table count, an entry count, then flat 20-byte entries. */
    private fun ByteWriter.writeContexts(tables: List<List<Float64ProbabilityContexts.Entry>>) {
        writeI32(tables.size)
        for (table in tables) {
            writeI32(table.size)
            for (entry in table) {
                writeI32(entry.symbol)
                writeI32(entry.occurrenceCount)
                writeF64(entry.associatedValue)
                writeI32(entry.reservedField)
            }
        }
    }

    private fun entry(
        symbol: Int,
        occurrenceCount: Int,
        associatedValue: Double,
        reservedField: Int = 0,
    ) = Float64ProbabilityContexts.Entry(symbol, occurrenceCount, associatedValue, reservedField)

    // spec: 9.5 Figure 224
    @Test
    fun nullCodecHoldsTwoCodeTextWordsPerValue() {
        // Nothing but the codec byte and the VecU32: no value range, no out-of-band data, no
        // CodeText Length, no Value Element Count — so the word count is the value count.
        // The word order within a value is v10 §12.1.2's low-word-first rule, applied by
        // analogy; see the class documentation of Float64Cdp.NullCodec.
        val values = listOf(1.0, -0.5, 1234.5678)
        val words =
            values.flatMap { value ->
                val bits = value.toRawBits()
                listOf((bits and 0xFFFFFFFFL).toInt(), (bits ushr 32).toInt())
            }
        val packet =
            roundTrip(
                bytesOf {
                    writeU8(0u)
                    writeVecU32(words)
                },
            )
        assertIs<Float64Cdp.NullCodec>(packet)
        assertEquals(values, packet.values)
    }

    // spec: 9.5 Figure 224
    @Test
    fun aNullCodecWithAnOddWordCountIsRefused() {
        val failure =
            assertFailsWith<JtFormatException> {
                Float64Cdp.read(
                    ByteReader(
                        bytesOf {
                            writeU8(0u)
                            writeVecU32(listOf(1, 2, 3))
                        },
                        Endianness.LITTLE_ENDIAN,
                    ),
                )
            }
        assertTrue(failure.message!!.contains("two-word"), failure.message)
    }

    // spec: 9.5 Figure 224, Figure 226
    @Test
    fun arithmeticDecodesAssociatedValuesAndSpendsOutOfBandValuesOnEscapes() {
        val entries =
            listOf(
                entry(0, 3, 2.5),
                entry(1, 2, -7.25),
                entry(-2, 1, 0.0, reservedField = 0),
            )
        val symbols = listOf(0, 1, 0, 2, 0, 1)
        val code = encodeSymbols(listOf(entries.map { TestEntry(it.symbol, it.occurrenceCount, 0) }), symbols)
        val bytes =
            bytesOf {
                writeU8(3u)
                writeContexts(listOf(entries))
                writeF64(-7.25)
                writeF64(2.5)
                writeI32(1)
                writeF64(99.5)
                writeI32(code.bitCount)
                writeI32(6)
                writeVecU32(code.toWords())
            }
        val packet = roundTrip(bytes)
        assertIs<Float64Cdp.Arithmetic>(packet)
        assertEquals(listOf(2.5, -7.25, 2.5, 99.5, 2.5, -7.25), packet.values)
        assertEquals(-7.25, packet.valueRangeMin)
        assertEquals(2.5, packet.valueRangeMax)
        assertEquals(listOf(99.5), packet.outOfBandValues)
        assertEquals(null, packet.symbolCount, "a single-table packet writes no Symbol Count")
    }

    /**
     * §9.3's reserved field, in a table entry the document assigns no value to: it must come back
     * exactly as it went in, or a rewrite of a file that was read is no longer the same file.
     *
     * spec: 9.5 Figure 226, §9.3
     */
    @Test
    fun theReservedFieldOfEveryContextEntryIsCarriedVerbatim() {
        val entries = listOf(entry(-2, 1, 0.0, reservedField = 0x0BADF00D))
        val bytes =
            bytesOf {
                writeU8(3u)
                writeContexts(listOf(entries))
                writeF64(0.0)
                writeF64(0.0)
                writeI32(2)
                writeF64(11.0)
                writeF64(12.0)
                writeI32(0)
                writeI32(2)
                writeVecU32(emptyList())
            }
        val packet = roundTrip(bytes)
        assertIs<Float64Cdp.Arithmetic>(packet)
        assertEquals(0x0BADF00D, packet.probabilityContexts.tables[0].entries[0].reservedField)
    }

    // spec: 9.5 §8.1.3 (p.263)
    @Test
    fun anAllOutOfBandPacketCopiesTheRawVecF64() {
        // "the encoded I32 : CodeText Length field will be 0, and the I32 : Out-Of-Band Value
        // Count will be equal to I32 : Value Element Count."
        val entries = listOf(entry(-2, 1, 0.0))
        val bytes =
            bytesOf {
                writeU8(3u)
                writeContexts(listOf(entries))
                writeF64(1.0)
                writeF64(3.0)
                writeI32(3)
                writeF64(1.0)
                writeF64(2.0)
                writeF64(3.0)
                writeI32(0)
                writeI32(3)
                writeVecU32(emptyList())
            }
        assertEquals(listOf(1.0, 2.0, 3.0), roundTrip(bytes).values)
    }

    /**
     * Figure 225 allows two tables and Figure 224 writes a Symbol Count for them, but Figure 226
     * has **no Next Context field** — the Int32 Mk. 1 entry's fourth box, and the only thing that
     * could drive a switch between tables. So a two-table packet with a live CodeText has no
     * documented decode and is refused; with CodeText Length 0 nothing is decoded at all and the
     * same packet stays readable.
     *
     * spec: 9.5 Figure 225, Figure 226
     */
    @Test
    fun twoTablesAreRefusedWhenTheyWouldHaveToBeSwitchedBetween() {
        fun packet(codeTextLength: Int) =
            bytesOf {
                writeU8(3u)
                writeContexts(listOf(listOf(entry(-2, 1, 0.0)), listOf(entry(0, 1, 5.0))))
                writeF64(0.0)
                writeF64(0.0)
                writeI32(1)
                writeF64(8.0)
                writeI32(codeTextLength)
                writeI32(1)
                writeI32(1)
                writeVecU32(if (codeTextLength == 0) emptyList() else listOf(0))
            }
        val failure = assertFailsWith<JtFormatException> { Float64Cdp.read(ByteReader(packet(16), Endianness.LITTLE_ENDIAN)) }
        assertTrue(failure.message!!.contains("Next Context"), failure.message)

        val readable = roundTrip(packet(0))
        assertEquals(listOf(8.0), readable.values)
        assertIs<Float64Cdp.Arithmetic>(readable)
        assertEquals(1, readable.symbolCount, "two tables put a Symbol Count on the wire")
    }

    /**
     * Two of the four CODEC types Figure 224 lists have no layout anywhere in the document. The
     * reader says so by name instead of inventing one.
     *
     * spec: 9.5 Figure 224
     */
    @Test
    fun theBitlengthAndChopperCodecsAreRefusedByName() {
        val bitlength =
            assertFailsWith<JtFormatException> {
                Float64Cdp.read(ByteReader(byteArrayOf(1, 0, 0, 0, 0), Endianness.LITTLE_ENDIAN))
            }
        assertTrue(bitlength.message!!.contains("Bitlength") && bitlength.message!!.contains("Int32-valued"), bitlength.message)
        val chopper =
            assertFailsWith<JtFormatException> {
                Float64Cdp.read(ByteReader(byteArrayOf(4, 0, 0, 0, 0), Endianness.LITTLE_ENDIAN))
            }
        assertTrue(chopper.message!!.contains("Chopper"), chopper.message)
        val unknown =
            assertFailsWith<JtFormatException> {
                Float64Cdp.read(ByteReader(byteArrayOf(5, 0, 0, 0, 0), Endianness.LITTLE_ENDIAN))
            }
        assertTrue(unknown.message!!.contains("Figure 224"), unknown.message)
    }

    /**
     * The Float64 packet and v10's `Int64CDP` occupy the same slot in the same collections one
     * generation apart, so a reader that picks the wrong one has no wire signal to catch itself
     * with — exactly the Mk. 1/Mk. 2 hazard, in the float column.
     *
     * spec: 9.5 §8.1.3, v10 §12.1.2
     */
    @Test
    fun theFloat64PacketAndTheInt64PacketRefuseEachOthersBytes() {
        val float64 =
            bytesOf {
                writeU8(0u)
                writeVecU32(listOf(0, 0x3FF00000))
            }
        val int64 =
            bytesOf {
                writeI32(1)
                writeU8(0u)
                writeI32(64)
                writeI32(0)
                writeI32(0x3FF00000)
            }
        assertEquals(listOf(1.0), Float64Cdp.read(ByteReader(float64, Endianness.LITTLE_ENDIAN)).values)
        assertEquals(listOf(1.0), Float64Vector(Int64Cdp.read(ByteReader(int64, Endianness.LITTLE_ENDIAN))).values)

        assertFailsWith<JtFormatException> { Int64Cdp.read(ByteReader(float64, Endianness.LITTLE_ENDIAN)) }
        assertFailsWith<JtFormatException> { Float64Cdp.read(ByteReader(int64, Endianness.LITTLE_ENDIAN)) }
    }

    // spec: 9.5 Figure 225
    @Test
    fun malformedContextsRefuseInsteadOfMisreading() {
        // A table count outside {1, 2}.
        assertFailsWith<JtFormatException> {
            Float64Cdp.read(
                ByteReader(
                    bytesOf {
                        writeU8(3u)
                        writeI32(0)
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
        // An entry count that does not fit the remaining bytes.
        assertFailsWith<JtFormatException> {
            Float64Cdp.read(
                ByteReader(
                    bytesOf {
                        writeU8(3u)
                        writeI32(1)
                        writeI32(1_000_000)
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
        // A decoded value count that disagrees with Value Element Count.
        assertFailsWith<JtFormatException> {
            Float64Cdp.read(
                ByteReader(
                    bytesOf {
                        writeU8(3u)
                        writeContexts(listOf(listOf(entry(-2, 1, 0.0))))
                        writeF64(0.0)
                        writeF64(0.0)
                        writeI32(1)
                        writeF64(8.0)
                        writeI32(0)
                        writeI32(4)
                        writeVecU32(emptyList())
                    },
                    Endianness.LITTLE_ENDIAN,
                ),
            )
        }
    }
}
