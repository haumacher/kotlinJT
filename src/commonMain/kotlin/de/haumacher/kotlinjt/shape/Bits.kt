package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFormatException

/**
 * A bit reader over an array of 32-bit CodeText words, most-significant bit first within each
 * word — the bit order of the JT entropy codecs (the words themselves are read from the file
 * in file byte order, so this reader is endianness-free). Fixture-verified against the JT 9.5
 * bitlength and arithmetic streams.
 */
internal class WordBitReader(
    private val words: IntArray,
) {
    private var wordIndex = 0
    private var buffer = 0
    private var bufferedBits = 0

    /** Number of bits handed out so far. */
    var consumed: Int = 0
        private set

    fun readBit(): Int = readUnsigned(1)

    /** Reads [count] bits (0..32) as an unsigned value in an Int (32 bits keep the raw word). */
    fun readUnsigned(count: Int): Int {
        if (count == 0) return 0
        if (count < 0 || count > 32) throw JtFormatException("bit count $count out of range")
        var result = 0
        var need = count
        while (need > 0) {
            if (bufferedBits == 0) {
                if (wordIndex >= words.size) {
                    throw JtFormatException("CodeText exhausted: needs more than ${words.size * 32} bits")
                }
                buffer = words[wordIndex]
                wordIndex += 1
                bufferedBits = 32
            }
            val take = if (need < bufferedBits) need else bufferedBits
            result = if (take == 32) buffer else (result shl take) or ((buffer ushr (32 - take)) and ((1 shl take) - 1))
            buffer = if (take == 32) 0 else buffer shl take
            bufferedBits -= take
            need -= take
        }
        consumed += count
        return result
    }

    /** Reads [count] bits (0..32) as a sign-extended value. */
    fun readSigned(count: Int): Int {
        if (count == 0) return 0
        val raw = readUnsigned(count)
        return (raw shl (32 - count)) shr (32 - count)
    }
}

/**
 * A bit reader over the plain byte stream, most-significant bit first within each byte — the
 * bit order of the probability context tables, which are byte-aligned blocks ("Alignment
 * Bits" pad the last byte). Endianness-free by construction.
 */
internal class ByteBitReader(
    private val bytes: ByteArray,
) {
    private var byteIndex = 0
    private var buffer = 0
    private var bufferedBits = 0

    /** Number of whole bytes this reader has touched (including a partially read last byte). */
    val bytesTouched: Int get() = byteIndex

    fun readUnsigned(count: Int): Int {
        if (count == 0) return 0
        if (count < 0 || count > 32) throw JtFormatException("bit count $count out of range")
        var result = 0
        var need = count
        while (need > 0) {
            if (bufferedBits == 0) {
                if (byteIndex >= bytes.size) {
                    throw JtFormatException("bit stream exhausted at byte $byteIndex")
                }
                buffer = bytes[byteIndex].toInt() and 0xFF
                byteIndex += 1
                bufferedBits = 8
            }
            val take = if (need < bufferedBits) need else bufferedBits
            result = (result shl take) or ((buffer ushr (8 - take)) and ((1 shl take) - 1))
            buffer = (buffer shl take) and 0xFF
            bufferedBits -= take
            need -= take
        }
        return result
    }

    fun readSigned(count: Int): Int {
        if (count == 0) return 0
        val raw = readUnsigned(count)
        return (raw shl (32 - count)) shr (32 - count)
    }
}
