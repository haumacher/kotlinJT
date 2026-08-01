package de.haumacher.kotlinjt.codec

/**
 * Failure of an XZ/LZMA decode: corrupt or truncated stream — or, when [unsupported] is set,
 * a well-formed stream using a feature this decoder does not implement (a non-LZMA2 filter
 * chain, a reserved check type). The distinction feeds the two refusal notes of [LzmaCodec].
 */
class XzException(message: String, val unsupported: Boolean = false) : Exception(message)

private const val MASK32 = 0xFFFFFFFFL
private const val PROB_INIT: Short = 1024 // 2048 / 2, the LZMA reference initial probability

/**
 * The growing uncompressed-output buffer, doubling as the LZMA match dictionary: matches copy
 * from the already-written history, bounded below by [dictStart] (moved forward on every
 * LZMA2 dictionary reset — a match may never reach behind the last reset).
 */
internal class LzmaOutput(initialCapacity: Int = 1 shl 12) {
    private var buffer = ByteArray(initialCapacity)

    /** Bytes written so far. */
    var position: Int = 0
        private set

    /** The dictionary boundary: matches may not reference bytes before this position. */
    var dictStart: Int = 0
        private set

    fun resetDictionary() {
        dictStart = position
    }

    private fun ensure(extra: Int) {
        if (position + extra <= buffer.size) return
        var size = buffer.size
        while (position + extra > size) size *= 2
        buffer = buffer.copyOf(size)
    }

    fun putByte(value: Int) {
        ensure(1)
        buffer[position++] = value.toByte()
    }

    fun putBytes(
        source: ByteArray,
        from: Int,
        count: Int,
    ) {
        ensure(count)
        source.copyInto(buffer, position, from, from + count)
        position += count
    }

    /** The byte [distance] + 1 positions back from the write position, as an unsigned int. */
    fun byteBack(distance: Int): Int = buffer[position - distance - 1].toInt() and 0xFF

    fun copyMatch(
        distance: Int,
        length: Int,
    ) {
        ensure(length)
        var src = position - distance - 1
        repeat(length) {
            buffer[position++] = buffer[src++]
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(position)

    /** A copy of the range `[from, position)` — used to verify a block's check value. */
    fun slice(from: Int): ByteArray = buffer.copyOfRange(from, position)
}

/**
 * The LZMA binary range decoder over one bounded input window (in LZMA2, one chunk's
 * compressed bytes). Ported from the normative decoder of the LZMA specification (the
 * public-domain LZMA SDK's `lzma-specification.txt`), which §12.2.5 adopts via XZ Utils.
 */
internal class RangeDecoder(
    private val data: ByteArray,
    start: Int,
    private val end: Int,
) {
    private var pos = start
    private var range = MASK32
    private var code = 0L

    init {
        if (end - pos < 5) throw XzException("LZMA chunk too short for the range coder initialization")
        if (data[pos].toInt() != 0) throw XzException("LZMA range coder initialization byte is not zero")
        pos++
        repeat(4) { code = (code shl 8) or nextByte() }
    }

    /** The input position after all bytes consumed so far. */
    val consumedTo: Int get() = pos

    private fun nextByte(): Long {
        if (pos >= end) throw XzException("LZMA chunk truncated: range coder ran out of input")
        return (data[pos++].toInt() and 0xFF).toLong()
    }

    private fun normalize() {
        if (range < (1L shl 24)) {
            range = (range shl 8) and MASK32
            code = ((code shl 8) or nextByte()) and MASK32
        }
    }

    fun decodeBit(
        probs: ShortArray,
        index: Int,
    ): Int {
        val prob = probs[index].toInt()
        val bound = (range ushr 11) * prob
        return if (code < bound) {
            probs[index] = (prob + ((2048 - prob) ushr 5)).toShort()
            range = bound
            normalize()
            0
        } else {
            probs[index] = (prob - (prob ushr 5)).toShort()
            code -= bound
            range -= bound
            normalize()
            1
        }
    }

    fun decodeDirectBits(count: Int): Long {
        var result = 0L
        repeat(count) {
            range = range ushr 1
            code = (code - range) and MASK32
            if (code and 0x80000000L != 0L) {
                // The subtraction wrapped: the bit is 0, undo.
                code = (code + range) and MASK32
                result = result shl 1
            } else {
                result = (result shl 1) or 1L
            }
            normalize()
        }
        return result
    }

    fun decodeBitTree(
        probs: ShortArray,
        numBits: Int,
    ): Int {
        var m = 1
        repeat(numBits) { m = (m shl 1) or decodeBit(probs, m) }
        return m - (1 shl numBits)
    }

    fun decodeBitTreeReverse(
        probs: ShortArray,
        offset: Int,
        numBits: Int,
    ): Int {
        var m = 1
        var symbol = 0
        for (i in 0 until numBits) {
            val bit = decodeBit(probs, offset + m)
            m = (m shl 1) or bit
            symbol = symbol or (bit shl i)
        }
        return symbol
    }
}

/** The LZMA match/rep length decoder (2 + a three-range bit-tree code). */
private class LengthDecoder {
    val choice = ShortArray(2) { PROB_INIT }
    val low = Array(16) { ShortArray(8) { PROB_INIT } }
    val mid = Array(16) { ShortArray(8) { PROB_INIT } }
    val high = ShortArray(256) { PROB_INIT }

    fun decode(
        rc: RangeDecoder,
        posState: Int,
    ): Int =
        when {
            rc.decodeBit(choice, 0) == 0 -> 2 + rc.decodeBitTree(low[posState], 3)
            rc.decodeBit(choice, 1) == 0 -> 10 + rc.decodeBitTree(mid[posState], 3)
            else -> 18 + rc.decodeBitTree(high, 8)
        }
}

/**
 * The LZMA1 decoder proper: literal/match state machine and probability model, decoding into
 * [out]. State, rep distances and probabilities persist across LZMA2 chunks until the chunk
 * control byte orders a reset — exactly the LZMA2 contract.
 */
internal class LzmaDecoder(private val out: LzmaOutput, private val dictSize: Long) {
    private var lc = 0
    private var lp = 0
    private var pb = 0

    private var state = 0
    private var rep0 = 0
    private var rep1 = 0
    private var rep2 = 0
    private var rep3 = 0

    private lateinit var literalProbs: ShortArray
    private val isMatch = ShortArray(12 shl 4)
    private val isRep = ShortArray(12)
    private val isRepG0 = ShortArray(12)
    private val isRepG1 = ShortArray(12)
    private val isRepG2 = ShortArray(12)
    private val isRep0Long = ShortArray(12 shl 4)
    private val posSlotProbs = Array(4) { ShortArray(64) }
    private val specPosProbs = ShortArray(115)
    private val alignProbs = ShortArray(16)
    private val lenDecoder = LengthDecoder()
    private val repLenDecoder = LengthDecoder()

    /** Applies a props byte (`(pb * 5 + lp) * 9 + lc`, Table of §12.2.5's LZMA lineage). */
    fun setProps(props: Int) {
        if (props >= 9 * 5 * 5) throw XzException("invalid LZMA properties byte $props")
        lc = props % 9
        lp = (props / 9) % 5
        pb = props / (9 * 5)
        if (lc + lp > 4) throw XzException("unsupported LZMA properties: lc=$lc lp=$lp (lc + lp > 4)")
        literalProbs = ShortArray(0x300 shl (lc + lp))
        resetState()
    }

    fun resetState() {
        state = 0
        rep0 = 0
        rep1 = 0
        rep2 = 0
        rep3 = 0
        isMatch.fill(PROB_INIT)
        isRep.fill(PROB_INIT)
        isRepG0.fill(PROB_INIT)
        isRepG1.fill(PROB_INIT)
        isRepG2.fill(PROB_INIT)
        isRep0Long.fill(PROB_INIT)
        for (probs in posSlotProbs) probs.fill(PROB_INIT)
        specPosProbs.fill(PROB_INIT)
        alignProbs.fill(PROB_INIT)
        literalProbs.fill(PROB_INIT)
        resetLengthDecoder(lenDecoder)
        resetLengthDecoder(repLenDecoder)
    }

    private fun resetLengthDecoder(decoder: LengthDecoder) {
        decoder.choice.fill(PROB_INIT)
        for (probs in decoder.low) probs.fill(PROB_INIT)
        for (probs in decoder.mid) probs.fill(PROB_INIT)
        decoder.high.fill(PROB_INIT)
    }

    private fun decodeLiteral(rc: RangeDecoder) {
        val totalPos = out.position - out.dictStart
        val prevByte = if (totalPos == 0) 0 else out.byteBack(0)
        val litState = ((totalPos and ((1 shl lp) - 1)) shl lc) + (prevByte ushr (8 - lc))
        val base = 0x300 * litState
        var symbol = 1
        if (state >= 7) {
            var matchByte = out.byteBack(rep0)
            do {
                val matchBit = (matchByte ushr 7) and 1
                matchByte = matchByte shl 1
                val bit = rc.decodeBit(literalProbs, base + ((1 + matchBit) shl 8) + symbol)
                symbol = (symbol shl 1) or bit
                if (matchBit != bit) {
                    while (symbol < 0x100) symbol = (symbol shl 1) or rc.decodeBit(literalProbs, base + symbol)
                    break
                }
            } while (symbol < 0x100)
        } else {
            while (symbol < 0x100) symbol = (symbol shl 1) or rc.decodeBit(literalProbs, base + symbol)
        }
        out.putByte(symbol and 0xFF)
        state =
            if (state < 4) {
                0
            } else if (state < 10) {
                state - 3
            } else {
                state - 6
            }
    }

    private fun decodeDistance(
        rc: RangeDecoder,
        len: Int,
    ): Long {
        val lenToPosState = if (len - 2 < 4) len - 2 else 3
        val posSlot = rc.decodeBitTree(posSlotProbs[lenToPosState], 6)
        if (posSlot < 4) return posSlot.toLong()
        val numDirectBits = (posSlot ushr 1) - 1
        var dist = ((2 or (posSlot and 1)).toLong()) shl numDirectBits
        if (posSlot < 14) {
            dist += rc.decodeBitTreeReverse(specPosProbs, (dist - posSlot).toInt(), numDirectBits)
        } else {
            dist += rc.decodeDirectBits(numDirectBits - 4) shl 4
            dist += rc.decodeBitTreeReverse(alignProbs, 0, 4)
        }
        return dist and MASK32
    }

    /** Decodes one LZMA2 chunk: exactly [unpackedSize] output bytes from [rc]. */
    fun decodeChunk(
        rc: RangeDecoder,
        unpackedSize: Int,
    ) {
        val outLimit = out.position + unpackedSize
        while (out.position < outLimit) {
            val posState = (out.position - out.dictStart) and ((1 shl pb) - 1)
            if (rc.decodeBit(isMatch, (state shl 4) + posState) == 0) {
                decodeLiteral(rc)
                continue
            }
            val len: Int
            if (rc.decodeBit(isRep, state) == 0) {
                rep3 = rep2
                rep2 = rep1
                rep1 = rep0
                len = lenDecoder.decode(rc, posState)
                state = if (state < 7) 7 else 10
                val dist = decodeDistance(rc, len)
                if (dist == MASK32) {
                    throw XzException("LZMA end-of-stream marker inside a sized LZMA2 chunk")
                }
                rep0 = dist.toInt()
            } else {
                if (rc.decodeBit(isRepG0, state) == 0) {
                    if (rc.decodeBit(isRep0Long, (state shl 4) + posState) == 0) {
                        // Short rep: a single byte at the last distance.
                        state = if (state < 7) 9 else 11
                        checkDistance(rep0)
                        out.putByte(out.byteBack(rep0))
                        continue
                    }
                } else {
                    val dist: Int
                    if (rc.decodeBit(isRepG1, state) == 0) {
                        dist = rep1
                    } else {
                        if (rc.decodeBit(isRepG2, state) == 0) {
                            dist = rep2
                        } else {
                            dist = rep3
                            rep3 = rep2
                        }
                        rep2 = rep1
                    }
                    rep1 = rep0
                    rep0 = dist
                }
                len = repLenDecoder.decode(rc, posState)
                state = if (state < 7) 8 else 11
            }
            checkDistance(rep0)
            if (out.position + len > outLimit) {
                throw XzException("LZMA match crosses the declared chunk boundary")
            }
            out.copyMatch(rep0, len)
        }
    }

    private fun checkDistance(distance: Int) {
        val available = out.position - out.dictStart
        val dist = distance.toLong() and MASK32
        if (dist >= dictSize || dist + 1 > available) {
            throw XzException("LZMA match distance ${dist + 1} exceeds the available dictionary ($available bytes)")
        }
    }
}

/**
 * Decodes one LZMA2 chunk sequence (the block data of an XZ block using filter 0x21) from
 * `data[start until end)` into [out], returning the position just after the end-of-payload
 * control byte. Chunk framing per the XZ format specification §5.3.
 */
internal fun lzma2Decode(
    data: ByteArray,
    start: Int,
    end: Int,
    dictSize: Long,
    out: LzmaOutput,
): Int {
    var pos = start
    var needDictReset = true
    var needProps = true
    var needStateReset = false
    val decoder = LzmaDecoder(out, dictSize)

    fun u8(): Int {
        if (pos >= end) throw XzException("LZMA2 chunk sequence truncated")
        return data[pos++].toInt() and 0xFF
    }

    fun u16be(): Int = (u8() shl 8) or u8()

    while (true) {
        val control = u8()
        when {
            control == 0x00 -> return pos
            control == 0x01 || control == 0x02 -> {
                if (control == 0x01) {
                    out.resetDictionary()
                    needDictReset = false
                } else if (needDictReset) {
                    throw XzException("first LZMA2 chunk does not reset the dictionary")
                }
                val size = u16be() + 1
                if (pos + size > end) throw XzException("LZMA2 uncompressed chunk truncated")
                out.putBytes(data, pos, size)
                pos += size
                // An uncompressed chunk invalidates the LZMA state; the format requires the
                // next LZMA chunk to reset it.
                needStateReset = true
            }
            control >= 0x80 -> {
                val unpackedSize = ((control and 0x1F) shl 16) + u16be() + 1
                val packedSize = u16be() + 1
                val reset = (control ushr 5) and 3
                if (reset >= 2) {
                    decoder.setProps(u8())
                    needProps = false
                } else if (needProps) {
                    throw XzException("first LZMA2 chunk carries no properties byte")
                } else if (reset == 1) {
                    decoder.resetState()
                } else if (needStateReset) {
                    throw XzException("LZMA2 chunk after an uncompressed chunk does not reset the LZMA state")
                }
                needStateReset = false
                if (reset == 3) {
                    out.resetDictionary()
                    needDictReset = false
                } else if (needDictReset) {
                    throw XzException("first LZMA2 chunk does not reset the dictionary")
                }
                if (pos + packedSize > end) throw XzException("LZMA2 compressed chunk truncated")
                val rc = RangeDecoder(data, pos, pos + packedSize)
                decoder.decodeChunk(rc, unpackedSize)
                if (rc.consumedTo != pos + packedSize) {
                    throw XzException(
                        "LZMA2 chunk under-consumed: ${pos + packedSize - rc.consumedTo} compressed bytes left over",
                    )
                }
                pos += packedSize
            }
            else -> throw XzException("invalid LZMA2 chunk control byte 0x${control.toString(16)}")
        }
    }
}
