package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.shape.ByteBitReader
import de.haumacher.kotlinjt.shape.WordBitReader
import de.haumacher.kotlinjt.shape.decodeArithmeticSymbolIndices

/**
 * The **Int64 Compressed Data Packet** (§12.1.2, Figures 135–137): the 64-bit sibling of
 * `Int32Cdp`. §12.1.2 states the relationship exactly — "Int64CDP shares the same encoding and
 * compression logic as Int32CDP, except the data being compressed consists of an array of
 * Int64 numbers instead of Int32 numbers" — plus one rule of its own: *"Any scalar field that
 * is longer than 32 bits is written with the low-order 32 bits first in the stream, then
 * followed by the remaining bits"*, which is what Annex B's `GetUnsignedBits(UInt64&, n)`
 * implements and what every 64-bit read below follows.
 *
 * As with the Int32 packet, **every wire field is preserved** (codec byte, CodeText words,
 * probability-context bytes, nested packets), so [encode] is a projection and never re-runs an
 * entropy coder. [values] carries the decoded 64-bit symbols; the containing field decides
 * what they mean (the NURBS collections of §12.1.9–§12.1.16 reinterpret them as `F64` bit
 * patterns, `Int64CDP` never does).
 *
 * Fixture-verified against the five Wireframe Rep bodies of the NIST 10.5 file: 12 arithmetic
 * packets (8 of them with an escape symbol), 2 bitlength packets and 1 move-to-front packet.
 * The null and chopper codecs are spec-derived (Figure 135 fixes their layout exactly as the
 * fixture-verified Int32 forms) — no fixture carries one.
 */
sealed class Int64Cdp {
    /** The I32 Value Count field: the number of values the packet decodes to. */
    abstract val valueCount: Int

    /** The decoded 64-bit symbol values; size == [valueCount]. */
    abstract val values: List<Long>

    /** Serializes the packet — the exact inverse of [read]. */
    abstract fun encode(w: ByteWriter)

    /** The empty packet: Value Count 0, no further fields on the wire. */
    object Empty : Int64Cdp() {
        override val valueCount: Int get() = 0
        override val values: List<Long> get() = emptyList()

        override fun encode(w: ByteWriter) {
            w.writeI32(0)
        }

        override fun toString(): String = "Int64Cdp.Empty"
    }

    /** CODEC type 0: the values stored as plain 64-bit words in the CodeText. */
    data class NullCodec(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        override val values: List<Long>,
    ) : Int64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_NULL.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
        }
    }

    /** CODEC type 1: the Bitlength CODEC over 64-bit values (Annex B `BitLengthCodec3T<Int64>`). */
    data class Bitlength(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        override val values: List<Long>,
    ) : Int64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_BITLENGTH.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
        }
    }

    /**
     * CODEC type 3: the Arithmetic CODEC with the Int64 probability context (Figure 136).
     * [outOfBand] is `null` exactly when the context carries no escape entry, and its form
     * follows Figure 135's branch on external compression — the same two rules the Int32
     * packet obeys (DESIGN.md deltas 28 and 37).
     */
    data class Arithmetic(
        override val valueCount: Int,
        val codeTextLength: Int,
        val codeText: List<Int>,
        val probabilityContext: Int64ProbabilityContext,
        val outOfBand: Int64OutOfBand?,
        override val values: List<Long>,
    ) : Int64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_ARITHMETIC.toUByte())
            w.writeI32(codeTextLength)
            for (word in codeText) w.writeI32(word)
            w.writeBytes(probabilityContext.raw)
            outOfBand?.encode(w)
        }
    }

    /** CODEC type 4: the Chopper pseudo-CODEC splitting 64-bit values into MSB/LSB bit fields. */
    data class Chopper(
        override val valueCount: Int,
        val chopBits: Int,
        val valueBias: Long,
        val valueSpanBits: Int,
        val msbData: Int64Cdp,
        val lsbData: Int64Cdp,
        override val values: List<Long>,
    ) : Int64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_CHOPPER.toUByte())
            w.writeU8(chopBits.toUByte())
            writeI64LowWordFirst(w, valueBias)
            w.writeU8(valueSpanBits.toUByte())
            msbData.encode(w)
            lsbData.encode(w)
        }
    }

    /** CODEC type 5: the Move-to-Front pseudo-CODEC over a 16-entry recency window. */
    data class MoveToFront(
        override val valueCount: Int,
        val windowValues: Int64Cdp,
        val windowOffsets: Int64Cdp,
        override val values: List<Long>,
    ) : Int64Cdp() {
        override fun encode(w: ByteWriter) {
            w.writeI32(valueCount)
            w.writeU8(CODEC_MOVE_TO_FRONT.toUByte())
            windowValues.encode(w)
            windowOffsets.encode(w)
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

        /** The maximum nesting depth (§12.1.1: "may not exceed eight"). */
        private const val MAX_DEPTH = 8

        /**
         * Reads one Int64CDP. Decoding is strict: a malformed packet throws
         * [JtFormatException], which the element-level decode turns into an opaque carry with
         * a named note. [externallyCompressed] selects Figure 135's out-of-band branch — see
         * `Int32Cdp.readV10`.
         */
        fun read(
            r: ByteReader,
            depth: Int = 0,
            externallyCompressed: Boolean = false,
        ): Int64Cdp {
            if (depth > MAX_DEPTH) throw JtFormatException("Int64CDP nesting deeper than $MAX_DEPTH")
            val count = r.readI32()
            if (count < 0) throw JtFormatException("Int64CDP value count $count is negative")
            if (count == 0) return Empty
            return when (val codec = r.readU8().toInt()) {
                CODEC_CHOPPER -> readChopper(r, count, depth, externallyCompressed)
                CODEC_MOVE_TO_FRONT -> readMoveToFront(r, count, depth, externallyCompressed)
                CODEC_NULL, CODEC_BITLENGTH, CODEC_ARITHMETIC ->
                    readCodeTextCodec(r, count, codec, depth, externallyCompressed)
                else -> throw JtFormatException("Int64CDP CODEC type $codec is not in the value set {0,1,3,4,5}")
            }
        }

        private fun readCodeTextCodec(
            r: ByteReader,
            count: Int,
            codec: Int,
            depth: Int,
            externallyCompressed: Boolean,
        ): Int64Cdp {
            val codeTextLength = r.readI32()
            if (codeTextLength < 0) throw JtFormatException("CodeText length $codeTextLength is negative")
            val wordCount = (codeTextLength + 31) / 32
            if (wordCount > r.remaining / 4) {
                throw JtFormatException("CodeText of $wordCount words does not fit the remaining ${r.remaining} bytes")
            }
            val codeText = List(wordCount) { r.readI32() }
            return when (codec) {
                CODEC_NULL -> {
                    if (count.toLong() * 2 > wordCount) {
                        throw JtFormatException("null CODEC has $wordCount words for $count 64-bit values")
                    }
                    // Low-order word first, as every Int64 field of §12.1.2 is written.
                    val values =
                        List(count) { i ->
                            (codeText[2 * i].toLong() and 0xFFFFFFFFL) or (codeText[2 * i + 1].toLong() shl 32)
                        }
                    NullCodec(count, codeTextLength, codeText, values)
                }
                CODEC_BITLENGTH -> {
                    val values = decodeBitlength64(codeText.toIntArray(), codeTextLength, count)
                    Bitlength(count, codeTextLength, codeText, values)
                }
                else -> {
                    val context = Int64ProbabilityContext.read(r)
                    val outOfBand =
                        if (!context.hasEscape) {
                            null
                        } else if (externallyCompressed) {
                            Int64OutOfBand.Raw.read(r)
                        } else {
                            Int64OutOfBand.Nested(read(r, depth + 1, externallyCompressed = false))
                        }
                    val oob = outOfBand?.values ?: emptyList()
                    val values =
                        if (codeTextLength == 0) {
                            // §12.1.1: with CodeText Length 0 all values are out-of-band.
                            oob
                        } else {
                            val symbols =
                                decodeArithmeticSymbolIndices(
                                    codeText.toIntArray(),
                                    count,
                                    context.entries.map { it.occurrenceCount },
                                )
                            var oobIndex = 0
                            val decoded = ArrayList<Long>(count)
                            for (index in symbols) {
                                val entry = context.entries[index]
                                if (entry.isEscape) {
                                    if (oobIndex >= oob.size) {
                                        throw JtFormatException("arithmetic escape symbol without a matching out-of-band value")
                                    }
                                    decoded.add(oob[oobIndex])
                                    oobIndex += 1
                                } else {
                                    decoded.add(entry.associatedValue)
                                }
                            }
                            if (oobIndex != oob.size) {
                                throw JtFormatException(
                                    "arithmetic stream left ${oob.size - oobIndex} out-of-band values unconsumed",
                                )
                            }
                            decoded
                        }
                    if (values.size != count) {
                        throw JtFormatException("arithmetic CODEC decoded ${values.size} values, expected $count")
                    }
                    Arithmetic(count, codeTextLength, codeText, context, outOfBand, values)
                }
            }
        }

        private fun readChopper(
            r: ByteReader,
            count: Int,
            depth: Int,
            externallyCompressed: Boolean,
        ): Int64Cdp {
            val chopBits = r.readU8().toInt()
            if (chopBits <= 0) throw JtFormatException("Int64CDP chopper with $chopBits chop bits (spec requires > 0)")
            val bias = readI64LowWordFirst(r)
            val span = r.readU8().toInt()
            val msb = read(r, depth + 1, externallyCompressed)
            val lsb = read(r, depth + 1, externallyCompressed)
            if (msb.valueCount != count || lsb.valueCount != count) {
                throw JtFormatException(
                    "chopped data size mismatch: ${msb.valueCount} MSB / ${lsb.valueCount} LSB values, expected $count",
                )
            }
            val shift = span - chopBits
            if (shift < 0 || shift > 63) throw JtFormatException("chopper span $span / chop $chopBits out of range")
            val values = List(count) { i -> (lsb.values[i] or (msb.values[i] shl shift)) + bias }
            return Chopper(count, chopBits, bias, span, msb, lsb, values)
        }

        private fun readMoveToFront(
            r: ByteReader,
            count: Int,
            depth: Int,
            externallyCompressed: Boolean,
        ): Int64Cdp {
            val windowValues = read(r, depth + 1, externallyCompressed)
            val windowOffsets = read(r, depth + 1, externallyCompressed)
            if (windowOffsets.valueCount != count) {
                throw JtFormatException("move-to-front offsets decode ${windowOffsets.valueCount} values, expected $count")
            }
            val window = ArrayDeque<Long>()
            var valueIndex = 0
            val values = ArrayList<Long>(count)
            for (offset in windowOffsets.values) {
                val value =
                    if (offset == -1L) {
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
                        val v = window.removeAt(offset.toInt())
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
 * The out-of-band values of an Int64 Arithmetic packet, in either of the two forms Figure 135
 * branches between (see `Int32OutOfBand` for the branch itself).
 */
sealed class Int64OutOfBand {
    abstract val values: List<Long>

    abstract fun encode(w: ByteWriter)

    /** The nested-packet form (segments whose element data is not compressed as a whole). */
    data class Nested(val packet: Int64Cdp) : Int64OutOfBand() {
        override val values: List<Long> get() = packet.values

        override fun encode(w: ByteWriter) = packet.encode(w)
    }

    /** The `I32 Out-of-Band Value Count` + `VecI64` form (externally compressed segments). */
    data class Raw(override val values: List<Long>) : Int64OutOfBand() {
        override fun encode(w: ByteWriter) {
            w.writeI32(values.size)
            for (value in values) writeI64LowWordFirst(w, value)
        }

        companion object {
            internal fun read(r: ByteReader): Raw {
                val count = r.readI32()
                if (count < 0 || count.toLong() * 8 > r.remaining) {
                    throw JtFormatException("out-of-band value count $count does not fit the remaining ${r.remaining} bytes")
                }
                return Raw(List(count) { readI64LowWordFirst(r) })
            }
        }
    }
}

/**
 * The Int64 Probability Context (Figures 136/137): `U32{16}` entry count, `U32{6}` occurrence
 * count bits, `U32{7}` value bits, `U64{64}` min value, then one entry per table row (1-bit
 * escape flag, occurrence count, associated value − min), byte-aligned. Identical to the
 * Int32 context except for the two 64-bit widths — which, per §12.1.2, are read low-order
 * word first. [raw] preserves the block byte-exactly including its alignment bits.
 */
data class Int64ProbabilityContext(
    /** The context block as read, byte-exact including alignment bits. */
    val raw: Bytes,
    val minValue: Long,
    val entries: List<Entry>,
) {
    /** One table entry; at most one entry carries [isEscape]. */
    data class Entry(
        val isEscape: Boolean,
        val occurrenceCount: Int,
        val associatedValue: Long,
    )

    /** Whether the table can emit the escape symbol (decides the out-of-band field's presence). */
    val hasEscape: Boolean = entries.any { it.isEscape }

    /** The sum of all occurrence counts (the `scale` of the arithmetic decoder). */
    val totalCount: Int = entries.sumOf { it.occurrenceCount }

    companion object {
        /** Reads the bit-packed table; strict — malformed tables throw [JtFormatException]. */
        fun read(r: ByteReader): Int64ProbabilityContext {
            val start = r.position
            val remaining = r.readBytes(r.remaining)
            val bits = ByteBitReader(remaining)
            val entryCount = bits.readUnsigned(16)
            val occurrenceBits = bits.readUnsigned(6)
            val valueBits = bits.readUnsigned(7)
            val minValue = bits.readSigned64(64)
            if (entryCount.toLong() * (1 + occurrenceBits + valueBits) > remaining.size.toLong() * 8) {
                throw JtFormatException("probability context entries do not fit the remaining bytes")
            }
            var escapes = 0
            val entries =
                List(entryCount) {
                    val isEscape = bits.readUnsigned(1) == 1
                    if (isEscape) escapes += 1
                    // The stored field is the value *minus* Min Value, hence unsigned — the
                    // same reading the fixture-verified Int32 context uses.
                    Entry(
                        isEscape,
                        bits.readUnsigned(occurrenceBits),
                        bits.readUnsigned64(valueBits) + minValue,
                    )
                }
            if (escapes > 1) throw JtFormatException("probability context with $escapes escape entries (at most one is legal)")
            val consumed = bits.bytesTouched
            r.position = start + consumed
            return Int64ProbabilityContext(remaining.copyOfRange(0, consumed).toBytes(), minValue, entries)
        }
    }
}

/**
 * Decodes an Int64 Bitlength CODEC stream. The grammar is the fixture-verified v10 Int32 one
 * (Annex B `BitLengthCodec3T`) with `ValueType = Int64`, which changes exactly two things:
 * `nibblerGet(Int64&)` writes/reads **all 64 bits** rather than nibbling (Annex B says so
 * explicitly: "Simply write out all the bits for 64 bit"), and the data fields are read as
 * 64-bit values. The fixed-width field width stays `bitsize(UInt32(max − min))` — the cast is
 * the reference encoder's own, so decoder and encoder agree.
 */
internal fun decodeBitlength64(
    codeText: IntArray,
    codeTextLength: Int,
    valueCount: Int,
): List<Long> {
    val bits = WordBitReader(codeText)
    val out = ArrayList<Long>(valueCount)

    fun ensureBudget() {
        if (bits.consumed > codeTextLength) {
            throw JtFormatException("bitlength stream overran its $codeTextLength declared bits")
        }
    }

    if (bits.readBit() == 0) {
        // Fixed-width mode.
        val min = bits.readSigned64(64)
        val max = bits.readSigned64(64)
        var fieldWidth = 0
        var rest = (max - min).toInt()
        while (rest != 0) {
            fieldWidth++
            rest = rest ushr 1
        }
        repeat(valueCount) {
            out.add(bits.readUnsigned64(fieldWidth) + min)
            ensureBudget()
        }
    } else {
        // Variable-width mode: cBlkValBits = cBlkLenBits = 4 (Annex B constants).
        val mean = bits.readSigned64(64)
        val maxDecrement = -8
        val maxIncrement = 7
        var fieldWidth = 0
        while (out.size < valueCount) {
            while (true) {
                val change = bits.readSigned(4)
                fieldWidth += change
                if (change != maxDecrement && change != maxIncrement) break
            }
            if (fieldWidth < 0 || fieldWidth > 64) {
                throw JtFormatException("bitlength field width drifted to $fieldWidth")
            }
            val runLength = bits.readUnsigned(4)
            repeat(runLength) {
                if (out.size < valueCount) {
                    out.add(if (fieldWidth > 0) bits.readSigned64(fieldWidth) + mean else mean)
                }
            }
            ensureBudget()
        }
    }
    return out
}

/**
 * Reads an `I64` scalar the way §12.1.2 prescribes for Int64CDP: low-order 32 bits first, then
 * the remaining bits. In a little-endian file (what the installed base writes) this is
 * identical to a plain `I64`; in a big-endian one the two words swap — spec-derived, since no
 * big-endian fixture exists.
 */
internal fun readI64LowWordFirst(r: ByteReader): Long {
    val low = r.readI32().toLong() and 0xFFFFFFFFL
    val high = r.readI32().toLong()
    return (high shl 32) or low
}

/** The exact inverse of [readI64LowWordFirst]. */
internal fun writeI64LowWordFirst(
    w: ByteWriter,
    value: Long,
) {
    w.writeI32((value and 0xFFFFFFFFL).toInt())
    w.writeI32((value shr 32).toInt())
}
