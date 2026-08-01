package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.toBytes

/**
 * The Int32 Compressed Data Packet in both wire generations: the JT 9 "Mk. 2" packet (JT 9.5
 * reference §8.1.2, fixture-verified — [read]) and the third-generation v10 packet (v10
 * reference §12.1.1 Figure 132, verified against the NIST 10.5 fixture — [readV10]); see
 * DESIGN.md for the deltas. The model preserves every wire field, so [encode] reproduces the
 * packet byte-identically without re-running an entropy coder; [values] carries the decoded
 * symbol values (the residuals before any predictor unpacking — the containing field applies
 * its predictor via [unpackResiduals]).
 */
sealed class Int32Cdp {
    /** The I32 Value Count field: the number of values the packet decodes to. */
    abstract val valueCount: Int

    /** The decoded symbol values (residuals); size == [valueCount]. */
    abstract val values: List<Int>

    /** Serializes the packet — the exact inverse of [read]. */
    abstract fun encode(w: ByteWriter)

    /** The empty packet: Value Count 0, no further fields on the wire. */
    object Empty : Int32Cdp() {
        override val valueCount: Int get() = 0
        override val values: List<Int> get() = emptyList()

        override fun encode(w: ByteWriter) {
            w.writeI32(0)
        }

        override fun toString(): String = "Int32Cdp.Empty"
    }

    /** CODEC type 0: the values stored as plain 32-bit words in the CodeText. */
    data class NullCodec(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        override val values: List<Int>,
    ) : Int32Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_NULL.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
        }
    }

    /** CODEC type 1: the Bitlength CODEC (§12.2.2; wire format per DESIGN.md delta). */
    data class Bitlength(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        override val values: List<Int>,
    ) : Int32Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_BITLENGTH.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
        }
    }

    /** CODEC type 3: the Arithmetic CODEC (§12.2.3) with its probability context and out-of-band values. */
    data class Arithmetic(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        val probabilityContext: Int32ProbabilityContext,
        /** The nested packet carrying the out-of-band values (always on the wire, possibly [Empty]). */
        val outOfBand: Int32Cdp,
        override val values: List<Int>,
    ) : Int32Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_ARITHMETIC.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
            w.writeBytes(probabilityContext.raw)
            outOfBand.encode(w)
        }
    }

    /** CODEC type 4 with Chop Bits 0: the packet defers to a nested re-chopped packet. */
    data class ChopperPassthrough(
        override val valueCount: Int,
        val nested: Int32Cdp,
    ) : Int32Cdp() {
        override val values: List<Int> get() = nested.values

        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_CHOPPER.toUByte())
            w.writeU8(0u)
            nested.encode(w)
        }
    }

    /**
     * CODEC type 3 in the v10 generation (Figure 132/133): the Arithmetic CODEC with the
     * restructured probability context. [outOfBand] is `null` exactly when the context has
     * no escape entry — the fixture-established presence rule (DESIGN.md).
     */
    data class ArithmeticV10(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        val probabilityContext: Int32ProbabilityContextV10,
        /** The nested out-of-band packet; on the wire only when the context has an escape entry. */
        val outOfBand: Int32Cdp?,
        override val values: List<Int>,
    ) : Int32Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_ARITHMETIC.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
            w.writeBytes(probabilityContext.raw)
            outOfBand?.encode(w)
        }
    }

    /**
     * CODEC type 5 in the v10 generation: the Move-to-Front pseudo-CODEC (§12.1.1). The
     * decoder replays a 16-entry recency window: an offset of −1 pulls the next value from
     * [windowValues] to the window front; any other offset reuses (and fronts) the window
     * entry. The escape encoding (−1) and the window discipline are fixture-established —
     * the spec prose names neither (DESIGN.md).
     */
    data class MoveToFront(
        override val valueCount: Int,
        val windowValues: Int32Cdp,
        val windowOffsets: Int32Cdp,
        override val values: List<Int>,
    ) : Int32Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_MOVE_TO_FRONT.toUByte())
            windowValues.encode(w)
            windowOffsets.encode(w)
        }
    }

    /** CODEC type 4: the Chopper pseudo-CODEC splitting values into MSB/LSB bit fields. */
    data class Chopper(
        override val valueCount: Int,
        val chopBits: Int,
        val valueBias: Int,
        val valueSpanBits: Int,
        val msbData: Int32Cdp,
        val lsbData: Int32Cdp,
        override val values: List<Int>,
    ) : Int32Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_CHOPPER.toUByte())
            w.writeU8(chopBits.toUByte())
            w.writeI32(valueBias)
            w.writeU8(valueSpanBits.toUByte())
            msbData.encode(w)
            lsbData.encode(w)
        }
    }

    companion object {
        internal const val CODEC_NULL = 0
        internal const val CODEC_BITLENGTH = 1
        internal const val CODEC_ARITHMETIC = 3
        internal const val CODEC_CHOPPER = 4
        internal const val CODEC_MOVE_TO_FRONT = 5

        /** The Move-to-Front recency window size (§12.1.1: "16 in this case"). */
        private const val MTF_WINDOW_SIZE = 16

        /** The maximum nesting depth (§12.1.1: "may not exceed eight"; the JT 9 limit is 3). */
        private const val MAX_DEPTH = 8

        /**
         * Reads one packet in the JT 9 generation's wire format, decoding its values
         * strictly: a malformed packet throws [JtFormatException] (the element-level decode
         * turns that into an opaque carry with a named note).
         */
        fun read(
            r: ByteReader,
            depth: Int = 0,
        ): Int32Cdp {
            if (depth > MAX_DEPTH) throw JtFormatException("Int32CDP nesting deeper than $MAX_DEPTH")
            val count = r.readI32()
            if (count < 0) throw JtFormatException("Int32CDP value count $count is negative")
            if (count == 0) return Empty
            return when (val codec = r.readU8().toInt()) {
                CODEC_CHOPPER -> readChopper(r, count, depth)
                CODEC_NULL, CODEC_BITLENGTH, CODEC_ARITHMETIC -> readCodeTextCodec(r, count, codec, depth)
                else -> throw JtFormatException("Int32CDP CODEC type $codec is not in the JT 9 value set {0,1,3,4}")
            }
        }

        private fun readChopper(
            r: ByteReader,
            count: Int,
            depth: Int,
        ): Int32Cdp {
            val chopBits = r.readU8().toInt()
            if (chopBits == 0) {
                val nested = read(r, depth + 1)
                if (nested.valueCount != count) {
                    throw JtFormatException("chopper passthrough decodes ${nested.valueCount} values, expected $count")
                }
                return ChopperPassthrough(count, nested)
            }
            val bias = r.readI32()
            val span = r.readU8().toInt()
            val msb = read(r, depth + 1)
            val lsb = read(r, depth + 1)
            if (msb.valueCount != count || lsb.valueCount != count) {
                throw JtFormatException(
                    "chopped data size mismatch: ${msb.valueCount} MSB / ${lsb.valueCount} LSB values, expected $count",
                )
            }
            val shift = span - chopBits
            if (shift < 0 || shift > 31) throw JtFormatException("chopper span $span / chop $chopBits out of range")
            val values =
                List(count) { i ->
                    (lsb.values[i] or (msb.values[i] shl shift)) + bias
                }
            return Chopper(count, chopBits, bias, span, msb, lsb, values)
        }

        private fun readCodeTextCodec(
            r: ByteReader,
            count: Int,
            codec: Int,
            depth: Int,
        ): Int32Cdp {
            val (codeTextLength, codeText) = readCodeText(r)
            val wordCount = codeText.size
            return when (codec) {
                CODEC_NULL -> {
                    if (count > wordCount) {
                        throw JtFormatException("null CODEC has $wordCount words for $count values")
                    }
                    NullCodec(count, codeTextLength, codeText, codeText.subList(0, count))
                }
                CODEC_BITLENGTH -> {
                    val values = decodeBitlength(codeText.toIntArray(), codeTextLength, count)
                    Bitlength(count, codeTextLength, codeText, values)
                }
                else -> {
                    val context = Int32ProbabilityContext.read(r)
                    val outOfBand = read(r, depth + 1)
                    val values =
                        decodeArithmetic(
                            codeText.toIntArray(),
                            count,
                            context.entries.map { ArithmeticEntry(it.symbol == -2, it.occurrenceCount, it.associatedValue) },
                            outOfBand.values,
                        )
                    Arithmetic(count, codeTextLength, codeText, context, outOfBand, values)
                }
            }
        }

        private fun readCodeText(r: ByteReader): Pair<Int, List<Int>> {
            val codeTextLength = r.readI32()
            if (codeTextLength < 0) throw JtFormatException("CodeText length $codeTextLength is negative")
            val wordCount = (codeTextLength + 31) / 32
            if (wordCount > r.remaining / 4) {
                throw JtFormatException("CodeText of $wordCount words does not fit the remaining ${r.remaining} bytes")
            }
            return codeTextLength to List(wordCount) { r.readI32() }
        }

        /**
         * Reads one packet in the v10 generation's wire format (Figure 132, third-generation
         * Int32CDP — verified against the NIST 10.5 fixture, DESIGN.md). Decoding is strict:
         * a malformed packet throws [JtFormatException] (the element-level decode turns that
         * into an opaque carry with a named note).
         */
        fun readV10(
            r: ByteReader,
            depth: Int = 0,
        ): Int32Cdp {
            if (depth > MAX_DEPTH) throw JtFormatException("Int32CDP nesting deeper than $MAX_DEPTH")
            val count = r.readI32()
            if (count < 0) throw JtFormatException("Int32CDP value count $count is negative")
            if (count == 0) return Empty
            return when (val codec = r.readU8().toInt()) {
                CODEC_CHOPPER -> readChopperV10(r, count, depth)
                CODEC_MOVE_TO_FRONT -> readMoveToFront(r, count, depth)
                CODEC_NULL, CODEC_BITLENGTH, CODEC_ARITHMETIC -> readCodeTextCodecV10(r, count, codec, depth)
                else -> throw JtFormatException("Int32CDP CODEC type $codec is not in the v10 value set {0,1,3,4,5}")
            }
        }

        private fun readCodeTextCodecV10(
            r: ByteReader,
            count: Int,
            codec: Int,
            depth: Int,
        ): Int32Cdp {
            val (codeTextLength, codeText) = readCodeText(r)
            val wordCount = codeText.size
            return when (codec) {
                CODEC_NULL -> {
                    if (count > wordCount) {
                        throw JtFormatException("null CODEC has $wordCount words for $count values")
                    }
                    NullCodec(count, codeTextLength, codeText, codeText.subList(0, count))
                }
                CODEC_BITLENGTH -> {
                    val values = decodeBitlengthV10(codeText.toIntArray(), codeTextLength, count)
                    Bitlength(count, codeTextLength, codeText, values)
                }
                else -> {
                    val context = Int32ProbabilityContextV10.read(r)
                    // The out-of-band packet is on the wire only when the context can emit an
                    // escape symbol — fixture-established (DESIGN.md; Figure 132 does not
                    // condition the field).
                    val outOfBand = if (context.hasEscape) readV10(r, depth + 1) else null
                    val values =
                        if (codeTextLength == 0) {
                            // §12.1.1: with CodeText Length 0 all values are out-of-band.
                            outOfBand?.values ?: emptyList()
                        } else {
                            decodeArithmetic(
                                codeText.toIntArray(),
                                count,
                                context.entries.map { ArithmeticEntry(it.isEscape, it.occurrenceCount, it.associatedValue) },
                                outOfBand?.values ?: emptyList(),
                            )
                        }
                    if (values.size != count) {
                        throw JtFormatException("arithmetic CODEC decoded ${values.size} values, expected $count")
                    }
                    ArithmeticV10(count, codeTextLength, codeText, context, outOfBand, values)
                }
            }
        }

        private fun readChopperV10(
            r: ByteReader,
            count: Int,
            depth: Int,
        ): Int32Cdp {
            val chopBits = r.readU8().toInt()
            if (chopBits == 0) {
                // §12.1.1 says Chop Bits is always > 0; a zero would be the JT 9 passthrough
                // form, unverified for v10 — refuse rather than guess.
                throw JtFormatException("v10 chopper with 0 chop bits (spec requires > 0)")
            }
            val bias = r.readI32()
            val span = r.readU8().toInt()
            val msb = readV10(r, depth + 1)
            val lsb = readV10(r, depth + 1)
            if (msb.valueCount != count || lsb.valueCount != count) {
                throw JtFormatException(
                    "chopped data size mismatch: ${msb.valueCount} MSB / ${lsb.valueCount} LSB values, expected $count",
                )
            }
            val shift = span - chopBits
            if (shift < 0 || shift > 31) throw JtFormatException("chopper span $span / chop $chopBits out of range")
            val values =
                List(count) { i ->
                    (lsb.values[i] or (msb.values[i] shl shift)) + bias
                }
            return Chopper(count, chopBits, bias, span, msb, lsb, values)
        }

        private fun readMoveToFront(
            r: ByteReader,
            count: Int,
            depth: Int,
        ): Int32Cdp {
            val windowValues = readV10(r, depth + 1)
            val windowOffsets = readV10(r, depth + 1)
            if (windowOffsets.valueCount != count) {
                throw JtFormatException("move-to-front offsets decode ${windowOffsets.valueCount} values, expected $count")
            }
            val window = ArrayDeque<Int>()
            var valueIndex = 0
            val values = ArrayList<Int>(count)
            for (offset in windowOffsets.values) {
                val value =
                    if (offset == -1) {
                        if (valueIndex >= windowValues.valueCount) {
                            throw JtFormatException("move-to-front escape without a matching window value")
                        }
                        val v = windowValues.values[valueIndex]
                        valueIndex += 1
                        window.addFirst(v)
                        if (window.size > MTF_WINDOW_SIZE) window.removeLast()
                        v
                    } else {
                        if (offset < 0 || offset >= window.size) {
                            throw JtFormatException("move-to-front offset $offset outside the ${window.size}-entry window")
                        }
                        val v = window.removeAt(offset)
                        window.addFirst(v)
                        v
                    }
                values.add(value)
            }
            if (valueIndex != windowValues.valueCount) {
                throw JtFormatException("move-to-front left ${windowValues.valueCount - valueIndex} window values unconsumed")
            }
            return MoveToFront(count, windowValues, windowOffsets, values)
        }
    }
}

/**
 * The Int32 Probability Context of the JT 9 generation (JT 9.5 reference §8.1.2.1; the v10
 * table of §12.1.1 differs — see DESIGN.md): a byte-aligned bit block holding the symbol
 * histogram of the Arithmetic CODEC. [raw] preserves the block byte-exactly (including the
 * alignment bits), so re-serialization never reconstructs the bit packing.
 */
data class Int32ProbabilityContext(
    /** The context block as read, byte-exact including alignment bits. */
    val raw: Bytes,
    val minValue: Int,
    val entries: List<Entry>,
) {
    /** One table entry; `symbol == -2` marks the escape symbol for out-of-band values. */
    data class Entry(
        val symbol: Int,
        val occurrenceCount: Int,
        val associatedValue: Int,
    )

    /** The sum of all occurrence counts (the `scale` of the arithmetic decoder). */
    val totalCount: Int = entries.sumOf { it.occurrenceCount }

    companion object {
        /** Reads the bit-packed table: 16-bit entry count, 6+6+6-bit field widths, 32-bit min value. */
        fun read(r: ByteReader): Int32ProbabilityContext {
            val start = r.position
            val remaining = r.readBytes(r.remaining)
            val bits = ByteBitReader(remaining)
            val entryCount = bits.readUnsigned(16)
            val symbolBits = bits.readUnsigned(6)
            val occurrenceBits = bits.readUnsigned(6)
            val valueBits = bits.readUnsigned(6)
            val minValue = bits.readSigned(32)
            if (entryCount * (symbolBits + occurrenceBits + valueBits) > remaining.size * 8) {
                throw JtFormatException("probability context entries do not fit the remaining bytes")
            }
            val entries =
                List(entryCount) {
                    Entry(
                        bits.readUnsigned(symbolBits) - 2,
                        bits.readUnsigned(occurrenceBits),
                        bits.readUnsigned(valueBits) + minValue,
                    )
                }
            val consumed = bits.bytesTouched
            r.position = start + consumed
            return Int32ProbabilityContext(
                remaining.copyOfRange(0, consumed).toBytes(),
                minValue,
                entries,
            )
        }
    }
}

/**
 * The Int32 Probability Context of the v10 generation (Figure 133/134): U32{16} entry count,
 * U32{6} occurrence-count bits, U32{7} value bits, U32{32} min value, then entries of
 * (1-bit escape flag, occurrence count, associated value − min), byte-aligned. [raw]
 * preserves the block byte-exactly (including alignment bits), so re-serialization never
 * reconstructs the bit packing. Verified against the NIST 10.5 fixture (DESIGN.md).
 */
data class Int32ProbabilityContextV10(
    /** The context block as read, byte-exact including alignment bits. */
    val raw: Bytes,
    val minValue: Int,
    val entries: List<Entry>,
) {
    /** One table entry; at most one entry carries [isEscape]. */
    data class Entry(
        val isEscape: Boolean,
        val occurrenceCount: Int,
        val associatedValue: Int,
    )

    /** Whether the table can emit the escape symbol (decides the out-of-band packet's presence). */
    val hasEscape: Boolean = entries.any { it.isEscape }

    /** The sum of all occurrence counts (the `scale` of the arithmetic decoder). */
    val totalCount: Int = entries.sumOf { it.occurrenceCount }

    companion object {
        /** Reads the bit-packed table; strict — malformed tables throw [JtFormatException]. */
        fun read(r: ByteReader): Int32ProbabilityContextV10 {
            val start = r.position
            val remaining = r.readBytes(r.remaining)
            val bits = ByteBitReader(remaining)
            val entryCount = bits.readUnsigned(16)
            val occurrenceBits = bits.readUnsigned(6)
            val valueBits = bits.readUnsigned(7)
            val minValue = bits.readSigned(32)
            if (entryCount.toLong() * (1 + occurrenceBits + valueBits) > remaining.size.toLong() * 8) {
                throw JtFormatException("probability context entries do not fit the remaining bytes")
            }
            var escapes = 0
            val entries =
                List(entryCount) {
                    val isEscape = bits.readUnsigned(1) == 1
                    if (isEscape) escapes += 1
                    Entry(
                        isEscape,
                        bits.readUnsigned(occurrenceBits),
                        bits.readUnsigned(valueBits) + minValue,
                    )
                }
            if (escapes > 1) throw JtFormatException("probability context with $escapes escape entries (at most one is legal)")
            val consumed = bits.bytesTouched
            r.position = start + consumed
            return Int32ProbabilityContextV10(
                remaining.copyOfRange(0, consumed).toBytes(),
                minValue,
                entries,
            )
        }
    }
}

/**
 * The predictor algorithms applied to CDP values (v10 reference Table 2; the JT 9.5
 * reference's Appendix C lists the full set). The wire carries residuals; [unpackResiduals]
 * recovers the primal values, priming with the first four residuals verbatim.
 */
enum class Predictor {
    NONE,
    LAG1,
    LAG2,
    XOR1,
    XOR2,
    STRIDE1,
    STRIDE2,
    STRIP_INDEX,
    RAMP,
}

/** Recovers primal values from predictor residuals (reference: Annex B / 9.5 Appendix C). */
fun unpackResiduals(
    residuals: List<Int>,
    predictor: Predictor,
): List<Int> {
    if (predictor == Predictor.NONE) return residuals
    val out = IntArray(residuals.size)
    for (i in residuals.indices) {
        if (i < 4) {
            // The first four values are just primers.
            out[i] = residuals[i]
        } else {
            val predicted =
                when (predictor) {
                    Predictor.LAG1, Predictor.XOR1 -> out[i - 1]
                    Predictor.LAG2, Predictor.XOR2 -> out[i - 2]
                    Predictor.STRIDE1 -> out[i - 1] + (out[i - 1] - out[i - 2])
                    Predictor.STRIDE2 -> out[i - 2] + (out[i - 2] - out[i - 4])
                    Predictor.STRIP_INDEX -> {
                        val d = out[i - 2] - out[i - 4]
                        out[i - 2] + if (d > -8 && d < 8) d else 2
                    }
                    Predictor.RAMP -> i
                    Predictor.NONE -> 0
                }
            out[i] =
                when (predictor) {
                    Predictor.XOR1, Predictor.XOR2 -> residuals[i] xor predicted
                    else -> residuals[i] + predicted
                }
        }
    }
    return out.toList()
}

/**
 * The exact inverse of [unpackResiduals]: turns primal values into the residuals the wire
 * carries. The writer needs it wherever a field is declared predicted (Figure 92's
 * `VecI32{Int32CDP, Lag1}` fields, the binary vertex coordinate arrays); `pack` followed by
 * `unpack` is the identity for every predictor by construction.
 */
fun packResiduals(
    values: List<Int>,
    predictor: Predictor,
): List<Int> {
    if (predictor == Predictor.NONE) return values
    val out = IntArray(values.size)
    for (i in values.indices) {
        if (i < 4) {
            // The first four values are just primers.
            out[i] = values[i]
        } else {
            val predicted =
                when (predictor) {
                    Predictor.LAG1, Predictor.XOR1 -> values[i - 1]
                    Predictor.LAG2, Predictor.XOR2 -> values[i - 2]
                    Predictor.STRIDE1 -> values[i - 1] + (values[i - 1] - values[i - 2])
                    Predictor.STRIDE2 -> values[i - 2] + (values[i - 2] - values[i - 4])
                    Predictor.STRIP_INDEX -> {
                        val d = values[i - 2] - values[i - 4]
                        values[i - 2] + if (d > -8 && d < 8) d else 2
                    }
                    Predictor.RAMP -> i
                    Predictor.NONE -> 0
                }
            out[i] =
                when (predictor) {
                    Predictor.XOR1, Predictor.XOR2 -> values[i] xor predicted
                    else -> values[i] - predicted
                }
        }
    }
    return out.toList()
}

/** Convenience: reads a JT 9 packet and applies [predictor] to its values. */
internal fun readInt32CdpValues(
    r: ByteReader,
    predictor: Predictor,
): Pair<Int32Cdp, List<Int>> {
    val cdp = Int32Cdp.read(r)
    return cdp to unpackResiduals(cdp.values, predictor)
}

/** Convenience: reads a v10 packet and applies [predictor] to its values. */
internal fun readInt32CdpValuesV10(
    r: ByteReader,
    predictor: Predictor,
): Pair<Int32Cdp, List<Int>> {
    val cdp = Int32Cdp.readV10(r)
    return cdp to unpackResiduals(cdp.values, predictor)
}

// ---------------------------------------------------------------------------
// Bitlength CODEC (§12.2.2)
// ---------------------------------------------------------------------------

/**
 * Decodes a JT 9 generation Bitlength CODEC stream (fixture-verified wire format, DESIGN.md):
 * a one-bit mode tag selects fixed-width (6+6-bit widths of a signed min/max pair, then
 * unsigned fields of `bitlength(max - min)` bits biased by min) or variable-width (32-bit
 * signed mean, 3+3-bit field/run widths, then runs of signed fields biased by the mean).
 */
internal fun decodeBitlength(
    codeText: IntArray,
    codeTextLength: Int,
    valueCount: Int,
): List<Int> {
    val bits = WordBitReader(codeText)
    val out = ArrayList<Int>(valueCount)

    fun ensureBudget() {
        if (bits.consumed > codeTextLength) {
            throw JtFormatException("bitlength stream overran its $codeTextLength declared bits")
        }
    }

    if (bits.readBit() == 0) {
        // Fixed-width mode.
        val minBits = bits.readUnsigned(6)
        val maxBits = bits.readUnsigned(6)
        val min = bits.readSigned(minBits)
        val max = bits.readSigned(maxBits)
        val range = max - min
        if (range <= 0) {
            repeat(valueCount) { out.add(min) }
        } else {
            var fieldWidth = 0
            var rest = range
            while (rest != 0) {
                fieldWidth++
                rest = rest ushr 1
            }
            repeat(valueCount) {
                out.add(bits.readUnsigned(fieldWidth) + min)
                ensureBudget()
            }
        }
    } else {
        // Variable-width mode.
        val mean = bits.readSigned(32)
        val fieldWidthBits = bits.readUnsigned(3)
        val runLengthBits = bits.readUnsigned(3)
        if (fieldWidthBits == 0) throw JtFormatException("bitlength variable mode with zero field-width bits")
        val maxDecrement = -(1 shl (fieldWidthBits - 1))
        val maxIncrement = (1 shl (fieldWidthBits - 1)) - 1
        var fieldWidth = 0
        while (out.size < valueCount) {
            while (true) {
                val change = bits.readSigned(fieldWidthBits)
                fieldWidth += change
                if (change != maxDecrement && change != maxIncrement) break
            }
            if (fieldWidth < 0 || fieldWidth > 32) {
                throw JtFormatException("bitlength field width drifted to $fieldWidth")
            }
            val runLength = bits.readUnsigned(runLengthBits)
            repeat(runLength) {
                if (out.size < valueCount) {
                    out.add(if (fieldWidth > 0) bits.readSigned(fieldWidth) + mean else mean)
                }
            }
            ensureBudget()
        }
    }
    return out
}

/**
 * Decodes a v10 generation Bitlength CODEC stream (§12.2.2; reference decoder in Annex B,
 * `BitLengthCodec`; verified against the NIST 10.5 fixture): a one-bit mode tag selects
 * fixed-width (nibble-encoded signed min/max pair, then unsigned `bitlength(max − min)`-bit
 * fields biased by min) or variable-width (nibble-encoded signed mean, then runs prefixed by
 * 4-bit field-width deltas and a 4-bit run length, fields signed and biased by the mean).
 * The JT 9 wire grammar differs — see [decodeBitlength] and DESIGN.md delta 17.
 */
internal fun decodeBitlengthV10(
    codeText: IntArray,
    codeTextLength: Int,
    valueCount: Int,
): List<Int> {
    val bits = WordBitReader(codeText)
    val out = ArrayList<Int>(valueCount)

    fun ensureBudget() {
        if (bits.consumed > codeTextLength) {
            throw JtFormatException("bitlength stream overran its $codeTextLength declared bits")
        }
    }

    // Annex B `nibblerGet`: 4-bit nibbles LSB-first, each followed by a continue bit; the
    // result is sign-extended from the nibble width.
    fun nibblerGet(): Int {
        var value = 0
        var nibbles = 0
        do {
            if (nibbles >= 8) throw JtFormatException("bitlength nibble run exceeds 32 bits")
            value = value or (bits.readUnsigned(4) shl (nibbles * 4))
            nibbles += 1
        } while (bits.readBit() == 1)
        val width = nibbles * 4
        return if (width < 32) (value shl (32 - width)) shr (32 - width) else value
    }

    if (bits.readBit() == 0) {
        // Fixed-width mode.
        val min = nibblerGet()
        val max = nibblerGet()
        // Annex B `bitsize(UInt32(max - min))`.
        var fieldWidth = 0
        var rest = max - min
        while (rest != 0) {
            fieldWidth++
            rest = rest ushr 1
        }
        repeat(valueCount) {
            out.add(bits.readUnsigned(fieldWidth) + min)
            ensureBudget()
        }
    } else {
        // Variable-width mode: cBlkValBits = cBlkLenBits = 4 (Annex B constants).
        val mean = nibblerGet()
        val maxDecrement = -8
        val maxIncrement = 7
        var fieldWidth = 0
        while (out.size < valueCount) {
            while (true) {
                val change = bits.readSigned(4)
                fieldWidth += change
                if (change != maxDecrement && change != maxIncrement) break
            }
            if (fieldWidth < 0 || fieldWidth > 32) {
                throw JtFormatException("bitlength field width drifted to $fieldWidth")
            }
            val runLength = bits.readUnsigned(4)
            repeat(runLength) {
                if (out.size < valueCount) {
                    out.add(if (fieldWidth > 0) bits.readSigned(fieldWidth) + mean else mean)
                }
            }
            ensureBudget()
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// Arithmetic CODEC (§12.2.3)
// ---------------------------------------------------------------------------

/**
 * One probability-context entry in the form the arithmetic decoder consumes — the shared
 * shape of the JT 9 table (symbol −2 marks the escape) and the v10 table (explicit flag).
 */
internal data class ArithmeticEntry(
    val isEscape: Boolean,
    val occurrenceCount: Int,
    val associatedValue: Int,
)

/**
 * Decodes an Arithmetic CODEC stream (reference source: v10 Annex B / JT 9.5 Appendix C §3):
 * a 16-bit integer arithmetic decoder over the probability context; escape symbols pull the
 * next out-of-band value. The decoder core is identical in both generations — only the
 * context's wire format differs (DESIGN.md delta 16 vs Figure 133).
 */
internal fun decodeArithmetic(
    codeText: IntArray,
    valueCount: Int,
    entries: List<ArithmeticEntry>,
    outOfBand: List<Int>,
): List<Int> {
    if (entries.isEmpty()) throw JtFormatException("arithmetic CODEC with an empty probability context")
    val total = entries.sumOf { it.occurrenceCount }
    if (total <= 0 || total > 0xFFFF) throw JtFormatException("arithmetic probability context total count $total out of range")

    if (codeText.isEmpty()) throw JtFormatException("arithmetic CodeText is shorter than the initial 16-bit code")
    val bits = WordBitReader(codeText)
    var code = bits.readUnsigned(16)
    var low = 0
    var high = 0xFFFF
    var oobIndex = 0

    // The declared length is padded up to whole words; the final symbol's renormalization may
    // read into that zero padding, but never past the stored words.
    fun nextBit(): Int = if (bits.consumed < codeText.size * 32) bits.readBit() else 0

    val out = ArrayList<Int>(valueCount)
    while (out.size < valueCount) {
        val rescaled = (((code - low + 1) * total - 1) / (high - low + 1))
        var cumulative = 0
        var entry: ArithmeticEntry? = null
        for (e in entries) {
            if (rescaled < cumulative + e.occurrenceCount) {
                entry = e
                break
            }
            cumulative += e.occurrenceCount
        }
        val hit = entry ?: throw JtFormatException("arithmetic symbol lookup failed for rescaled count $rescaled")

        if (hit.isEscape) {
            if (oobIndex >= outOfBand.size) {
                throw JtFormatException("arithmetic escape symbol without a matching out-of-band value")
            }
            out.add(outOfBand[oobIndex])
            oobIndex += 1
        } else {
            out.add(hit.associatedValue)
        }

        // Remove the symbol from the stream (16-bit renormalization).
        val range = high - low + 1
        high = low + range * (cumulative + hit.occurrenceCount) / total - 1
        low = low + range * cumulative / total
        while (true) {
            if ((high xor low).inv() and 0x8000 != 0) {
                // Top bits match: shift them out.
            } else if (low and 0x4000 != 0 && high and 0x4000 == 0) {
                // Underflow threatens: drop the second-most-significant bit.
                code = code xor 0x4000
                low = low and 0x3FFF
                high = high or 0x4000
            } else {
                break
            }
            low = (low shl 1) and 0xFFFF
            high = ((high shl 1) or 1) and 0xFFFF
            code = ((code shl 1) or nextBit()) and 0xFFFF
        }
    }
    if (oobIndex != outOfBand.size) {
        throw JtFormatException("arithmetic stream left ${outOfBand.size - oobIndex} out-of-band values unconsumed")
    }
    return out
}
