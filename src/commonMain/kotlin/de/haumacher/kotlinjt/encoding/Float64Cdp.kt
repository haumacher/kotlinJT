package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.shape.ArithmeticDecoder

/**
 * The **Float64 Compressed Data Packet** — JT 9.5 reference §8.1.3, Figure 224 (p.264). A
 * 9.5-only codec: v10 replaced it with the `Int64CDP` of §12.1.2, which is a *different
 * mechanism* and not this one renamed.
 *
 * | | Float64CDP (this class) | v10 `Int64Cdp` |
 * |---|---|---|
 * | symbol domain | `F64` values, natively — the context stores `F64 Associated Value` | `I64`, bit-reinterpreted to `F64` by the consumer |
 * | leading field | `U8 CODEC Type` | `I32 Value Count` |
 * | value range | `F64 Value Range Min` / `Max` on the wire | none |
 * | out-of-band | `I32` count + a **raw `VecF64`**, always — "*the Float64 Compressed Data Packet simply writes out the 'out-of-band data' array with no additional encoding attempted*" (p.263) | a nested packet, or count + raw when externally compressed |
 * | context | plain byte-aligned `I32` counts and flat 20-byte entries, a list of 1–2 tables | one bit-packed table with a `U64{64}` Min Value |
 * | chopper / move-to-front | absent | present |
 *
 * Which 9.5 fields use it: `VecF64{Float64CDP, NULL}` — the NURBS control point weights
 * (§8.1.14), control points (§8.1.15.3) and knot vectors (§8.1.15), i.e. every precise-geometry
 * float a JT 9 B-Rep or Wireframe Rep carries.
 *
 * All wire fields are preserved, so [encode] is a projection.
 *
 * **Evidence.** Framing from Figure 224 and its field prose (pp. 263–265), rendered from the
 * PDF; contexts from Figures 225/226 (p.266); the arithmetic driver is the fixture-verified
 * `ArithmeticDecoder` core with this packet's own symbol→value mapping. **No fixture in the
 * corpus carries a Float64 packet** — see `Int32CdpMk1FixtureTest` for the hook that fires when
 * one arrives. Two of the four CODEC types Figure 224 lists have no layout in the document at
 * all and are refused by name rather than guessed; see [read].
 */
sealed class Float64Cdp {
    /** The decoded values. */
    abstract val values: List<Double>

    /** The number of values the packet decodes to. */
    val valueCount: Int get() = values.size

    /** Serializes the packet — the exact inverse of [read]. */
    abstract fun encode(w: ByteWriter)

    /**
     * CODEC type 0: the values sit in the CodeText as raw bit patterns, two `U32` words each.
     *
     * A Null-CODEC packet carries nothing but the codec byte and the `VecU32`: Value Range,
     * out-of-band data, CodeText Length and Value Element Count are all "*only present if CODEC
     * Type is not equal to 'Null CODEC'*" (p.265). So the word count is the only thing that can
     * say how many values there are, which is itself the evidence that a value occupies exactly
     * two words.
     *
     * **The word order within a value is an inference, not a citation** — §8.1.3 never states
     * it. It is taken from v10 §12.1.2, this packet's own successor: "*Any scalar field that is
     * longer than 32 bits is written with the low-order 32 bits first in the stream*", which is
     * also what `Int64Cdp`'s Null CODEC does and what a little-endian file (all the corpus has
     * ever held) makes indistinguishable from a plain `F64`.
     */
    data class NullCodec(
        val codeText: List<Int>,
        override val values: List<Double>,
    ) : Float64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeU8(CODEC_NULL.toUByte())
            writeCodeText(w, codeText)
        }
    }

    /** CODEC type 3: the Arithmetic CODEC over `F64` symbols. */
    data class Arithmetic(
        val probabilityContexts: Float64ProbabilityContexts,
        val valueRangeMin: Double,
        val valueRangeMax: Double,
        /** The out-of-band values, always a raw `VecF64` — never a nested packet (p.263). */
        val outOfBandValues: List<Double>,
        val codeTextLength: Int,
        val valueElementCount: Int,
        /** `I32 Symbol Count`; on the wire exactly when the packet carries two context tables. */
        val symbolCount: Int?,
        val codeText: List<Int>,
        override val values: List<Double>,
    ) : Float64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeU8(CODEC_ARITHMETIC.toUByte())
            probabilityContexts.encode(w)
            w.writeF64(valueRangeMin)
            w.writeF64(valueRangeMax)
            w.writeI32(outOfBandValues.size)
            for (value in outOfBandValues) w.writeF64(value)
            w.writeI32(codeTextLength)
            w.writeI32(valueElementCount)
            symbolCount?.let { w.writeI32(it) }
            writeCodeText(w, codeText)
        }
    }

    companion object {
        internal const val CODEC_NULL = 0
        internal const val CODEC_BITLENGTH = 1
        internal const val CODEC_ARITHMETIC = 3
        internal const val CODEC_CHOPPER = 4

        /**
         * Reads one Float64 packet. Decoding is strict: a malformed packet throws
         * [JtFormatException], which the element-level decode turns into an opaque carry with a
         * named note.
         *
         * Figure 224's CODEC table lists four types, but the document gives a layout for only
         * two of them:
         *
         * - **Bitlength (1)** — Appendix C §2.1's `BitLengthCodec` decodes `Int32` symbols and
         *   §8.1.3 defines no 64-bit form and no reinterpretation step (its context stores
         *   native `F64` values, so unlike v10's `Int64CDP` there is nothing to reinterpret).
         *   A bitlength stream here has no documented meaning; refused by name.
         * - **Chopper (4)** — Figure 224 draws no Chop Bits / Value Bias / span fields, and
         *   §8.1.2 introduces the Chopper as what the *Int32 Mk. 2* packet brings to the table
         *   (p.258). Refused by name.
         *
         * spec: 9.5 Figure 224
         */
        fun read(r: ByteReader): Float64Cdp {
            return when (val codec = r.readU8().toInt()) {
                CODEC_NULL -> readNull(r)
                CODEC_ARITHMETIC -> readArithmetic(r)
                CODEC_BITLENGTH ->
                    throw JtFormatException(
                        "Float64CDP declares the Bitlength CODEC (1); Appendix C §2.1's BitLengthCodec is " +
                            "Int32-valued and §8.1.3 defines no 64-bit form or reinterpretation step",
                    )
                CODEC_CHOPPER ->
                    throw JtFormatException(
                        "Float64CDP declares the Chopper CODEC (4), for which Figure 224 draws no fields; " +
                            "§8.1.2 introduces the Chopper with the Int32 Mk. 2 packet",
                    )
                else ->
                    throw JtFormatException(
                        "Float64CDP CODEC type $codec is not in the value set {0,1,3,4} of Figure 224",
                    )
            }
        }

        private fun readNull(r: ByteReader): NullCodec {
            val codeText = readCodeText(r)
            if (codeText.size % 2 != 0) {
                throw JtFormatException(
                    "Float64CDP Null CODEC holds ${codeText.size} CodeText words, " +
                        "not a whole number of two-word F64 values",
                )
            }
            val values =
                List(codeText.size / 2) { i ->
                    Double.fromBits((codeText[2 * i].toLong() and 0xFFFFFFFFL) or (codeText[2 * i + 1].toLong() shl 32))
                }
            return NullCodec(codeText, values)
        }

        private fun readArithmetic(r: ByteReader): Arithmetic {
            val contexts = Float64ProbabilityContexts.read(r)
            val valueRangeMin = r.readF64()
            val valueRangeMax = r.readF64()
            val outOfBandCount = r.readI32()
            if (outOfBandCount < 0 || outOfBandCount.toLong() * 8 > r.remaining) {
                throw JtFormatException(
                    "Out-Of-Band Value Count $outOfBandCount does not fit the remaining ${r.remaining} bytes",
                )
            }
            val outOfBandValues = List(outOfBandCount) { r.readF64() }
            val codeTextLength = r.readI32()
            if (codeTextLength < 0) throw JtFormatException("CodeText length $codeTextLength is negative")
            val valueElementCount = r.readI32()
            if (valueElementCount < 0) throw JtFormatException("Value Element Count $valueElementCount is negative")
            val symbolCount = if (contexts.tables.size > 1) r.readI32() else null
            if (symbolCount != null && symbolCount < 0) {
                throw JtFormatException("Symbol Count $symbolCount is negative")
            }
            val codeText = readCodeText(r)
            if (codeTextLength > codeText.size.toLong() * 32) {
                throw JtFormatException(
                    "CodeText Length $codeTextLength exceeds the ${codeText.size} stored CodeText words",
                )
            }
            val values =
                if (codeTextLength == 0) {
                    // §8.1.3 (p.263), word for word the sentence §8.1.1 and §8.1.2 carry: with a
                    // CodeText Length of 0 every value is out of band and the implied action is
                    // "to merely copy the Out-Of-Band value data into the output Value Element
                    // array instead of invoking the Codec".
                    outOfBandValues
                } else {
                    if (contexts.tables.size > 1) {
                        // Figure 226 has no Next Context field — the Int32 Mk. 1 entry's fourth
                        // field, and the only thing that could drive a switch between tables. So
                        // a two-table Float64 packet with a live CodeText has no documented
                        // decode; refuse rather than invent one. (With CodeText Length 0 above
                        // it stays readable, because no symbol is decoded at all.)
                        throw JtFormatException(
                            "Float64CDP declares ${contexts.tables.size} probability context tables, but " +
                                "Figure 226 defines no Next Context field to switch between them",
                        )
                    }
                    decodeArithmeticFloat64(codeText.toIntArray(), valueElementCount, contexts.tables[0], outOfBandValues)
                }
            if (values.size != valueElementCount) {
                throw JtFormatException(
                    "Float64CDP arithmetic CODEC decoded ${values.size} values, expected $valueElementCount",
                )
            }
            return Arithmetic(
                contexts,
                valueRangeMin,
                valueRangeMax,
                outOfBandValues,
                codeTextLength,
                valueElementCount,
                symbolCount,
                codeText,
                values,
            )
        }

        /** `VecU32 : CodeText` — an `I32` word count followed by that many `U32`s (p.21). */
        private fun readCodeText(r: ByteReader): List<Int> {
            val wordCount = r.readI32()
            if (wordCount < 0 || wordCount.toLong() * 4 > r.remaining) {
                throw JtFormatException("CodeText of $wordCount words does not fit the remaining ${r.remaining} bytes")
            }
            return List(wordCount) { r.readI32() }
        }

        private fun writeCodeText(
            w: ByteWriter,
            codeText: List<Int>,
        ) {
            w.writeI32(codeText.size)
            for (word in codeText) w.writeI32(word)
        }
    }
}

/**
 * The **Float64 Probability Contexts** collection (9.5 §8.1.3.1, Figure 225, p.266): an `I32`
 * count of one or two tables, each an `I32` entry count followed by its entries. Unlike both
 * Int32 context generations this is **not bit-packed** — no field widths, no Min Value, no
 * alignment bits — so it re-serializes field by field rather than as a preserved block.
 *
 * spec: 9.5 Figure 225, Figure 226
 */
data class Float64ProbabilityContexts(
    val tables: List<Table>,
) {
    /** One probability context table. */
    data class Table(
        val entries: List<Entry>,
    ) {
        /** The sum of all occurrence counts (the `scale` of the arithmetic decoder). */
        val totalCount: Int = entries.sumOf { it.occurrenceCount }
    }

    /**
     * One table entry (Figure 226): 20 flat bytes. [symbol] is stored **unbiased** here — unlike
     * the Int32 tables' `+2` — so the escape placeholder is literally `−2` on the wire.
     * [reservedField] carries §9.3's reserved `I32` verbatim, since the document assigns it no
     * value and a rewrite must reproduce what was read.
     */
    data class Entry(
        val symbol: Int,
        val occurrenceCount: Int,
        val associatedValue: Double,
        val reservedField: Int,
    )

    fun encode(w: ByteWriter) {
        w.writeI32(tables.size)
        for (table in tables) {
            w.writeI32(table.entries.size)
            for (entry in table.entries) {
                w.writeI32(entry.symbol)
                w.writeI32(entry.occurrenceCount)
                w.writeF64(entry.associatedValue)
                w.writeI32(entry.reservedField)
            }
        }
    }

    companion object {
        /** One entry is `I32` + `I32` + `F64` + `I32` = 20 bytes. */
        private const val ENTRY_BYTES = 20

        fun read(r: ByteReader): Float64ProbabilityContexts {
            val tableCount = r.readI32()
            if (tableCount != 1 && tableCount != 2) {
                throw JtFormatException(
                    "Probability Context Table Count $tableCount is outside {1, 2} (§8.1.3.1)",
                )
            }
            val tables =
                List(tableCount) { table ->
                    val entryCount = r.readI32()
                    if (entryCount < 0 || entryCount.toLong() * ENTRY_BYTES > r.remaining) {
                        throw JtFormatException(
                            "probability context table $table declares $entryCount entries, " +
                                "more than the ${r.remaining} bytes left",
                        )
                    }
                    Table(
                        List(entryCount) {
                            Entry(r.readI32(), r.readI32(), r.readF64(), r.readI32())
                        },
                    )
                }
            return Float64ProbabilityContexts(tables)
        }
    }
}

/**
 * The Float64 arithmetic driver: the shared 16-bit [ArithmeticDecoder] core with this packet's
 * own symbol→value mapping — a non-escape entry contributes its `F64 Associated Value`, the
 * escape entry (symbol `−2`, §8.1.3.1.1) the next raw out-of-band value.
 *
 * Single-context only, by construction: Figure 226 carries no Next Context field, so the loop
 * runs over Value Element Count exactly as `ArithmeticCodec2::decode` does.
 */
private fun decodeArithmeticFloat64(
    codeText: IntArray,
    valueElementCount: Int,
    table: Float64ProbabilityContexts.Table,
    outOfBand: List<Double>,
): List<Double> {
    val histogram = IntArray(table.entries.size) { table.entries[it].occurrenceCount }
    val total = ArithmeticDecoder.totalOf(histogram)
    val decoder = ArithmeticDecoder(codeText)
    var outOfBandIndex = 0
    val out = ArrayList<Double>(valueElementCount)
    repeat(valueElementCount) {
        val entry = table.entries[decoder.decodeSymbolIndex(histogram, total)]
        if (entry.symbol == ESCAPE_SYMBOL) {
            if (outOfBandIndex >= outOfBand.size) {
                throw JtFormatException("arithmetic escape symbol without a matching out-of-band value")
            }
            out.add(outOfBand[outOfBandIndex])
            outOfBandIndex += 1
        } else {
            out.add(entry.associatedValue)
        }
    }
    if (outOfBandIndex != outOfBand.size) {
        throw JtFormatException(
            "arithmetic stream left ${outOfBand.size - outOfBandIndex} out-of-band values unconsumed",
        )
    }
    return out
}
