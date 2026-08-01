package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.toBytes

/**
 * The Int32 Compressed Data Packet of the JT 9 generation (v10 reference §12.1.1 documents
 * the v10 successor; the wire format below is the "Mk. 2" packet of the JT 9.5 reference
 * §8.1.2, fixture-verified — see DESIGN.md for the deltas). The model preserves every wire
 * field, so [encode] reproduces the packet byte-identically without re-running an entropy
 * coder; [values] carries the decoded symbol values (the residuals before any predictor
 * unpacking — the containing field applies its predictor via [unpackResiduals]).
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

        /** The maximum nesting depth (chopper/out-of-band recursion; the JT 9 limit is 3). */
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
            val codeTextLength = r.readI32()
            if (codeTextLength < 0) throw JtFormatException("CodeText length $codeTextLength is negative")
            val wordCount = (codeTextLength + 31) / 32
            if (wordCount > r.remaining / 4) {
                throw JtFormatException("CodeText of $wordCount words does not fit the remaining ${r.remaining} bytes")
            }
            val codeText = List(wordCount) { r.readI32() }
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
                    val values = decodeArithmetic(codeText.toIntArray(), codeTextLength, count, context, outOfBand.values)
                    Arithmetic(count, codeTextLength, codeText, context, outOfBand, values)
                }
            }
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

/** Convenience: reads a packet and applies [predictor] to its values. */
internal fun readInt32CdpValues(
    r: ByteReader,
    predictor: Predictor,
): Pair<Int32Cdp, List<Int>> {
    val cdp = Int32Cdp.read(r)
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

// ---------------------------------------------------------------------------
// Arithmetic CODEC (§12.2.3)
// ---------------------------------------------------------------------------

/**
 * Decodes a JT 9 generation Arithmetic CODEC stream (reference source: v10 Annex B / JT 9.5
 * Appendix C §3): a 16-bit integer arithmetic decoder over the probability context; escape
 * symbols pull the next out-of-band value.
 */
internal fun decodeArithmetic(
    codeText: IntArray,
    codeTextLength: Int,
    valueCount: Int,
    context: Int32ProbabilityContext,
    outOfBand: List<Int>,
): List<Int> {
    val entries = context.entries
    if (entries.isEmpty()) throw JtFormatException("arithmetic CODEC with an empty probability context")
    val total = context.totalCount
    if (total <= 0 || total > 0xFFFF) throw JtFormatException("arithmetic probability context total count $total out of range")

    if (codeTextLength < 16) throw JtFormatException("arithmetic CodeText of $codeTextLength bits is shorter than the initial code")
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
        var entry: Int32ProbabilityContext.Entry? = null
        for (e in entries) {
            if (rescaled < cumulative + e.occurrenceCount) {
                entry = e
                break
            }
            cumulative += e.occurrenceCount
        }
        val hit = entry ?: throw JtFormatException("arithmetic symbol lookup failed for rescaled count $rescaled")

        if (hit.symbol == -2) {
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
