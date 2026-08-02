package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.shape.ArithmeticDecoder
import de.haumacher.kotlinjt.shape.ByteBitReader
import de.haumacher.kotlinjt.shape.WordBitReader

/**
 * The **Int32 Compressed Data Packet Mk. 1** — JT 9.5 reference §8.1.1, Figure 218 (p.254).
 *
 * This is a *different packet* from the Mk. 2 one `shape.Int32Cdp` reads, not a variant of it,
 * and **nothing in the byte stream distinguishes them**. 9.5 pp. 19–20 bind the two statically,
 * per field, through the figure notation itself: a box reading `VecI32{Int32CDP, …}` is this
 * packet (§8.1.1), a box reading `VecI32{Int32CDP2, …}` is `shape.Int32Cdp.read` (§8.1.2). The
 * *call site* decides; a caller that guesses is a bug, which is why the two are separate types
 * with no common supertype and no auto-detecting entry point.
 *
 * Where the two differ on the wire (Figure 218 against Figure 221):
 *
 * | | Mk. 1 (this class) | Mk. 2 (`shape.Int32Cdp`) |
 * |---|---|---|
 * | leading field | `U8 CODEC Type` — **no Value Count**, so no empty-packet form | `I32 Value Count` |
 * | value count | `I32 Value Element Count`, *after* the out-of-band data, absent for the Null CODEC | the leading `I32 Value Count` |
 * | probability context | a **list** of 1–2 tables with a `U8` count and a per-entry `Next Context` | one table, no count, no next context |
 * | out-of-band | gated on an explicit `I32 Out-Of-Band Value Count > 0` | an unconditional nested packet |
 * | CodeText | a real `VecU32` — its **own** `I32` word count (p.21) | a bare word run sized from CodeText Length |
 * | two-table form | `I32 Symbol Count` distinct from Value Element Count | absent |
 * | chopper | undocumented (Figure 218 draws no chopper fields) — refused by name | CODEC 4 with Chop Bits / bias / span |
 *
 * Which 9.5 fields use it: every JT B-Rep topology stream (§7.2.3.1), the §8.1.13–§8.1.15 NURBS
 * curve and knot-vector machinery, and the §7.2.2.2.2.1 ULP `params1` codes. The Shape LOD
 * segment, the Wireframe Rep Element's own index vectors, the JT LWPA element and the §8.1.16
 * CAD tag vectors are all `Int32CDP2` and must **not** come here.
 *
 * As with every packet in this library, all wire fields are preserved, so [encode] is a
 * projection and never re-runs an entropy coder. [values] carries the decoded symbol values
 * (the residuals before any predictor unpacking — see [Int32VectorMk1]).
 *
 * **Evidence.** Framing from Figure 218 and its field prose (pp. 253–255), rendered from the
 * PDF rather than the text extraction; the probability-context layout from Figure 219/220
 * (pp. 256–257); the bitlength grammar from Appendix C §2.1 `BitLengthCodec::decode` (pp.
 * 320–322); the arithmetic driver from Appendix C §3.1 `ArithmeticCodec::decode` (pp. 325–326),
 * whose renormalization core is the fixture-verified `ArithmeticDecoder` shared with Mk. 2.
 * **No fixture in the corpus carries a Mk. 1 packet** — see `Int32CdpMk1FixtureTest` for the
 * hook that fires when one arrives.
 */
sealed class Int32CdpMk1 {
    /** The decoded symbol values (residuals). */
    abstract val values: List<Int>

    /** The number of values the packet decodes to. */
    val valueCount: Int get() = values.size

    /** Serializes the packet — the exact inverse of [read]. */
    abstract fun encode(w: ByteWriter)

    /**
     * CODEC type 0: the values are the CodeText words themselves. The Null CODEC packet carries
     * *no* CodeText Length and *no* Value Element Count ("This data field is only present if
     * CODEC Type is not equal to Null CODEC", p.255), so the `VecU32`'s own count is the value
     * count.
     */
    data class NullCodec(
        val codeText: List<Int>,
    ) : Int32CdpMk1() {
        override val values: List<Int> get() = codeText

        override fun encode(w: ByteWriter) {
            w.writeU8(CODEC_NULL.toUByte())
            writeCodeText(w, codeText)
        }
    }

    /** CODEC type 1: the Bitlength CODEC in its Mk. 1 prefix-code form (App. C §2.1). */
    data class Bitlength(
        val codeTextLength: Int,
        val valueElementCount: Int,
        val codeText: List<Int>,
        override val values: List<Int>,
    ) : Int32CdpMk1() {
        override fun encode(w: ByteWriter) {
            w.writeU8(CODEC_BITLENGTH.toUByte())
            w.writeI32(codeTextLength)
            w.writeI32(valueElementCount)
            writeCodeText(w, codeText)
        }
    }

    /**
     * CODEC type 3: the Arithmetic CODEC with its list of probability context tables and, when
     * the packet declares any, an out-of-band value packet — itself a Mk. 1 packet.
     */
    data class Arithmetic(
        val probabilityContexts: Int32ProbabilityContexts1,
        val outOfBandValueCount: Int,
        /** The nested out-of-band packet; on the wire exactly when [outOfBandValueCount] > 0. */
        val outOfBand: Int32CdpMk1?,
        val codeTextLength: Int,
        val valueElementCount: Int,
        /** `I32 Symbol Count`; on the wire exactly when the packet carries two context tables. */
        val symbolCount: Int?,
        val codeText: List<Int>,
        override val values: List<Int>,
    ) : Int32CdpMk1() {
        override fun encode(w: ByteWriter) {
            w.writeU8(CODEC_ARITHMETIC.toUByte())
            probabilityContexts.encode(w)
            w.writeI32(outOfBandValueCount)
            outOfBand?.encode(w)
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
         * The maximum nesting depth. §8.1.2 states the JT 9 rule for the Mk. 2 packet — "For JT
         * v9 files, the maximum recursion depth may not exceed three" (p.258) — and §8.1.1
         * states none of its own; three is taken as the generation's rule, with one level of
         * slack so a conforming file is never refused for a limit the document only implies.
         */
        private const val MAX_DEPTH = 4

        /**
         * Reads one Mk. 1 packet. Decoding is strict: a malformed packet throws
         * [JtFormatException], which the element-level decode turns into an opaque carry with a
         * named note.
         *
         * spec: 9.5 Figure 218
         */
        fun read(
            r: ByteReader,
            depth: Int = 0,
        ): Int32CdpMk1 {
            if (depth > MAX_DEPTH) throw JtFormatException("Int32CDP Mk. 1 nesting deeper than $MAX_DEPTH")
            return when (val codec = r.readU8().toInt()) {
                CODEC_NULL -> NullCodec(readCodeText(r))
                CODEC_BITLENGTH -> readBitlength(r)
                CODEC_ARITHMETIC -> readArithmetic(r, depth)
                CODEC_CHOPPER ->
                    throw JtFormatException(
                        "Int32CDP Mk. 1 declares the Chopper CODEC (4), for which Figure 218 draws no fields; " +
                            "§8.1.2 introduces the Chopper with the Mk. 2 packet",
                    )
                else ->
                    throw JtFormatException(
                        "Int32CDP Mk. 1 CODEC type $codec is not in the value set {0,1,3,4} of Figure 218 " +
                            "(a Mk. 2 packet starts with an I32 Value Count and must be read as Int32Cdp)",
                    )
            }
        }

        private fun readBitlength(r: ByteReader): Bitlength {
            val codeTextLength = readCodeTextLength(r)
            val valueElementCount = r.readI32()
            if (valueElementCount < 0) throw JtFormatException("Value Element Count $valueElementCount is negative")
            val codeText = readCodeText(r)
            requireCodeTextFits(codeTextLength, codeText.size)
            val values = decodeBitlengthMk1(codeText.toIntArray(), codeTextLength)
            if (values.size != valueElementCount) {
                throw JtFormatException(
                    "Mk. 1 bitlength CODEC decoded ${values.size} values, expected $valueElementCount",
                )
            }
            return Bitlength(codeTextLength, valueElementCount, codeText, values)
        }

        private fun readArithmetic(
            r: ByteReader,
            depth: Int,
        ): Arithmetic {
            val contexts = Int32ProbabilityContexts1.read(r)
            val outOfBandValueCount = r.readI32()
            if (outOfBandValueCount < 0) {
                throw JtFormatException("Out-Of-Band Value Count $outOfBandValueCount is negative")
            }
            val outOfBand = if (outOfBandValueCount > 0) read(r, depth + 1) else null
            if (outOfBand != null && outOfBand.valueCount != outOfBandValueCount) {
                throw JtFormatException(
                    "out-of-band packet decodes ${outOfBand.valueCount} values, " +
                        "expected $outOfBandValueCount",
                )
            }
            val codeTextLength = readCodeTextLength(r)
            val valueElementCount = r.readI32()
            if (valueElementCount < 0) throw JtFormatException("Value Element Count $valueElementCount is negative")
            val symbolCount = if (contexts.tables.size > 1) r.readI32() else null
            if (symbolCount != null && symbolCount < 0) {
                throw JtFormatException("Symbol Count $symbolCount is negative")
            }
            val codeText = readCodeText(r)
            requireCodeTextFits(codeTextLength, codeText.size)
            val outOfBandValues = outOfBand?.values ?: emptyList()
            val values =
                if (codeTextLength == 0) {
                    // §8.1.1 (p.253): "In some cases, all values may be written as 'out of band'
                    // … the encoded I32 : CodeText Length field will be 0, and the I32 :
                    // Out-Of-Band Value Count will be equal to I32 : Value Element Count. The
                    // implied action in this case is to merely copy the Out-Of-Band value data
                    // into the output Value Element array instead of invoking the Codec."
                    outOfBandValues
                } else {
                    decodeArithmeticMk1(
                        codeText.toIntArray(),
                        symbolCount ?: valueElementCount,
                        contexts,
                        outOfBandValues,
                    )
                }
            if (values.size != valueElementCount) {
                throw JtFormatException(
                    "Mk. 1 arithmetic CODEC decoded ${values.size} values, expected $valueElementCount",
                )
            }
            return Arithmetic(
                contexts,
                outOfBandValueCount,
                outOfBand,
                codeTextLength,
                valueElementCount,
                symbolCount,
                codeText,
                values,
            )
        }

        private fun readCodeTextLength(r: ByteReader): Int {
            val codeTextLength = r.readI32()
            if (codeTextLength < 0) throw JtFormatException("CodeText length $codeTextLength is negative")
            return codeTextLength
        }

        /** `VecU32 : CodeText` — p.21: "a vector … starts with an I32 that defines the count of following U32". */
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

        private fun requireCodeTextFits(
            codeTextLength: Int,
            wordCount: Int,
        ) {
            if (codeTextLength > wordCount.toLong() * 32) {
                throw JtFormatException(
                    "CodeText Length $codeTextLength exceeds the $wordCount stored CodeText words",
                )
            }
        }
    }
}

/**
 * The **Int32 Probability Contexts** collection of the Mk. 1 packet (9.5 §8.1.1.1, Figure 219,
 * p.256): a `U8` count of one or two tables, each a bit-packed histogram, with one run of
 * alignment bits closing the whole collection.
 *
 * The first table in the list carries `Number Value Bits` and `Min Value`; a second table does
 * not and reuses the first's ("Note that Number Value Bits is only specified in the JT file for
 * the first Probability Context Table. If a second Probability Context Table is present, the
 * Number Value Bits from the first should be used for the second as well", p.256 — the same
 * necessarily holds for Min Value, which Figure 219 likewise draws only in the first-table
 * branch).
 *
 * [raw] preserves the block byte-exactly, including the alignment bits, so re-serialization
 * never reconstructs the bit packing.
 *
 * spec: 9.5 Figure 219, Figure 220
 */
data class Int32ProbabilityContexts1(
    /** The collection as read, byte-exact including the trailing alignment bits. */
    val raw: Bytes,
    val tables: List<Table>,
) {
    /** One probability context table. */
    data class Table(
        val symbolBits: Int,
        val occurrenceCountBits: Int,
        val valueBits: Int,
        val nextContextBits: Int,
        val minValue: Int,
        val entries: List<Entry>,
    ) {
        /** The sum of all occurrence counts (the `scale` of the arithmetic decoder). */
        val totalCount: Int = entries.sumOf { it.occurrenceCount }
    }

    /**
     * One table entry (Figure 220). [symbol] is stored biased by +2 and is reported unbiased,
     * so [ESCAPE_SYMBOL] (−2, App. C §1.1/§1.2 `CEBEscape`) marks the out-of-band placeholder;
     * [associatedValue] is stored minus the table's Min Value and is reported unbiased;
     * [nextContext] selects the table the *next* symbol is decoded against.
     */
    data class Entry(
        val symbol: Int,
        val occurrenceCount: Int,
        val associatedValue: Int,
        val nextContext: Int,
    )

    fun encode(w: ByteWriter) = w.writeBytes(raw)

    companion object {
        fun read(r: ByteReader): Int32ProbabilityContexts1 {
            val start = r.position
            val remaining = r.readBytes(r.remaining)
            val bits = ByteBitReader(remaining)
            val tableCount = bits.readUnsigned(8)
            if (tableCount != 1 && tableCount != 2) {
                throw JtFormatException(
                    "Probability Context Table Count $tableCount is outside {1, 2} (§8.1.1.1)",
                )
            }
            val availableBits = remaining.size.toLong() * 8
            var valueBits = 0
            var minValue = 0
            val tables =
                List(tableCount) { table ->
                    val entryCount = bits.readUnsigned(32).toLong() and 0xFFFFFFFFL
                    val symbolBits = bits.readUnsigned(6)
                    val occurrenceCountBits = bits.readUnsigned(6)
                    if (table == 0) {
                        valueBits = bits.readUnsigned(6)
                    }
                    val nextContextBits = bits.readUnsigned(6)
                    if (table == 0) {
                        minValue = bits.readSigned(32)
                    }
                    val entryBits = (symbolBits + occurrenceCountBits + valueBits + nextContextBits).toLong()
                    if (entryCount > MAX_CONTEXT_ENTRIES) {
                        // The arithmetic coder's total occurrence count is a 16-bit scale, so a
                        // table with more than 65535 entries has entries that can never be
                        // selected — it is corrupt, not merely large. Without this bound a
                        // zero-width table (all four field widths 0) would ask for an
                        // arbitrarily long entry list out of no bytes at all.
                        throw JtFormatException(
                            "probability context table $table declares $entryCount entries, " +
                                "beyond the $MAX_CONTEXT_ENTRIES a 16-bit occurrence scale can address",
                        )
                    }
                    if (entryCount * entryBits > availableBits) {
                        throw JtFormatException(
                            "probability context table $table declares $entryCount entries " +
                                "($entryBits bits each), more than the ${remaining.size} bytes left",
                        )
                    }
                    val entries =
                        List(entryCount.toInt()) {
                            Entry(
                                bits.readUnsigned(symbolBits) - SYMBOL_BIAS,
                                bits.readUnsigned(occurrenceCountBits),
                                bits.readUnsigned(valueBits) + minValue,
                                bits.readUnsigned(nextContextBits),
                            )
                        }
                    for (entry in entries) {
                        if (entry.nextContext >= tableCount) {
                            throw JtFormatException(
                                "Next Context ${entry.nextContext} is not below the " +
                                    "Probability Context Table Count $tableCount",
                            )
                        }
                    }
                    Table(symbolBits, occurrenceCountBits, valueBits, nextContextBits, minValue, entries)
                }
            val consumed = bits.bytesTouched
            r.position = start + consumed
            return Int32ProbabilityContexts1(remaining.copyOfRange(0, consumed).toBytes(), tables)
        }
    }
}

/**
 * The escape symbol of both Int32 probability-context generations: App. C §1.2's
 * `CEBEscape = -2`, and §8.1.1.1.1's "*it will become '−2', its true symbol value, after
 * subtracting '2' from the read in '0' value*".
 */
internal const val ESCAPE_SYMBOL = -2

/** The `+2` bias every Int32 probability context stores its symbols with (§8.1.1.1.1, p.257). */
private const val SYMBOL_BIAS = 2

/** The largest entry count a 16-bit arithmetic occurrence scale can address. */
private const val MAX_CONTEXT_ENTRIES = 0xFFFFL

/**
 * The Mk. 1 arithmetic driver — App. C §3.1 `ArithmeticCodec::decode` (pp. 325–326). It differs
 * from the Mk. 2 driver in exactly two ways, both of which are why §8.1.1 carries a Symbol Count
 * field that Mk. 2 does not:
 *
 * 1. the loop runs over `pDriver->numSymbolsToRead()` — Symbol Count when two tables are in
 *    play, Value Element Count otherwise — and the probability context of the *next* symbol is
 *    `pCntxEntry->iNextCntx`, read from the entry just decoded (the decode starts in table 0);
 * 2. an escape symbol emits an out-of-band value **only while table 0 is in use**: "Only if the
 *    Codec is using Probability Context Table 0 when it receives an Escape symbol does it emit a
 *    Value from the 'Out-Of-Band' data array. Because of this subtlety, the number of Symbols
 *    decoded can be larger than the number of Values produced" (p.255).
 *
 * Rule 2 is the one place §8.1.1 leaves genuinely under-specified: it says what an escape in a
 * *non*-zero context does not do, and never says what it does instead, so this driver emits
 * nothing for it — the only reading under which "Symbols decoded can be larger than … Values
 * produced" is true at all. The caller's `values.size == Value Element Count` check is what
 * keeps a wrong reading from passing silently; see DESIGN.md.
 */
private fun decodeArithmeticMk1(
    codeText: IntArray,
    symbolCount: Int,
    contexts: Int32ProbabilityContexts1,
    outOfBand: List<Int>,
): List<Int> {
    val histograms = contexts.tables.map { table -> IntArray(table.entries.size) { table.entries[it].occurrenceCount } }
    val totals = histograms.map { ArithmeticDecoder.totalOf(it) }
    val decoder = ArithmeticDecoder(codeText)
    var table = 0
    var outOfBandIndex = 0
    val out = ArrayList<Int>(symbolCount)
    repeat(symbolCount) {
        val entries = contexts.tables[table].entries
        val entry = entries[decoder.decodeSymbolIndex(histograms[table], totals[table])]
        if (entry.symbol == ESCAPE_SYMBOL) {
            if (table == 0) {
                if (outOfBandIndex >= outOfBand.size) {
                    throw JtFormatException("arithmetic escape symbol without a matching out-of-band value")
                }
                out.add(outOfBand[outOfBandIndex])
                outOfBandIndex += 1
            }
        } else {
            out.add(entry.associatedValue)
        }
        table = entry.nextContext
    }
    if (outOfBandIndex != outOfBand.size) {
        throw JtFormatException(
            "arithmetic stream left ${outOfBand.size - outOfBandIndex} out-of-band values unconsumed",
        )
    }
    return out
}

/**
 * The Mk. 1 Bitlength CODEC — App. C §2.1 `BitLengthCodec::decode` (pp. 320–322), which is also
 * the scheme §8.2.2's prose describes (p.287). It is a *different codec* from the Mk. 2
 * bitlength stream (App. C §2.2, `shape.decodeBitlength`): there is no mode tag, no min/max or
 * mean header and no run lengths. Instead the stream is a walk over a prefix code:
 *
 * - state 0 — a `0` bit means "keep the current field width", a `1` bit starts a width change;
 * - state 1 — a run of increment (`1`) / decrement (`0`) bits, each moving the width by
 *   `cStepBits = 2`, terminated by the first bit that differs from the one before it;
 * - state 2 — one signed field of the current width, sign-extended, emitted as a value.
 *
 * The loop is bounded by the *CodeText Length in bits*, not by a value count — which is why a
 * zero-width field emits a zero without consuming a bit, and why the reference's closing "if the
 * last symbol was zero and the current bit length is also zero" clause emits one final zero.
 * Both corner cases are reproduced here.
 */
internal fun decodeBitlengthMk1(
    codeText: IntArray,
    codeTextLength: Int,
): List<Int> {
    val stepBits = 2
    val bits = WordBitReader(codeText)
    val out = ArrayList<Int>()
    var state = 0
    var fieldWidth = 0
    var lastIncrementBit = NO_LAST_INCREMENT_BIT
    while (bits.consumed < codeTextLength) {
        if (state == STATE_ACCUMULATE) {
            // A field that would run past the declared CodeText Length is never emitted: the
            // reference clips its per-chunk budget to `nTotalBits - nBits` and leaves the
            // partial accumulation unflushed.
            if (bits.consumed + fieldWidth > codeTextLength) break
            out.add(bits.readSigned(fieldWidth))
            state = STATE_PREFIX
        } else {
            val bit = bits.readBit()
            if (state == STATE_PREFIX) {
                if (bit == 0) {
                    state = STATE_ACCUMULATE
                } else {
                    state = STATE_ADJUST_WIDTH
                    lastIncrementBit = NO_LAST_INCREMENT_BIT
                }
            } else {
                if (lastIncrementBit != NO_LAST_INCREMENT_BIT && (bit xor lastIncrementBit) != 0) {
                    // The terminator is the first bit that contradicts the previous one.
                    state = STATE_ACCUMULATE
                    lastIncrementBit = NO_LAST_INCREMENT_BIT
                } else {
                    fieldWidth += if (bit == 1) stepBits else -stepBits
                    if (fieldWidth < 0 || fieldWidth > 32) {
                        throw JtFormatException("Mk. 1 bitlength field width drifted to $fieldWidth")
                    }
                    lastIncrementBit = bit
                }
            }
        }
    }
    if (state == STATE_ACCUMULATE && fieldWidth == 0) {
        // "If the last symbol was zero and the current bit length is also zero, then the above
        // loop terminated before actually decoding the last zero-valued symbol."
        out.add(0)
    }
    return out
}

private const val STATE_PREFIX = 0
private const val STATE_ADJUST_WIDTH = 1
private const val STATE_ACCUMULATE = 2

/** The reference's `uLastIncBit = 2` sentinel: no increment/decrement bit has been seen yet. */
private const val NO_LAST_INCREMENT_BIT = 2
