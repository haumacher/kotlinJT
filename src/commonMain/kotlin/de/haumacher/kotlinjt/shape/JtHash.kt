package de.haumacher.kotlinjt.shape

/**
 * The JT hash function (v10 Annex C; identical in the JT 9.5 reference, Appendix D): Bob
 * Jenkins' lookup2, word variant (`hash2`). Shape LOD data carries hash values over the
 * decoded symbol arrays — the decoder verifies them, turning any codec defect into a named
 * refusal instead of silently wrong geometry.
 */
internal object JtHash {
    private const val GOLDEN_RATIO: Int = 0x9e3779b9.toInt()

    /** Hashes an array of 32-bit words with a seed (the previous hash value). */
    fun hash32(
        words: IntArray,
        seed: Int = 0,
        from: Int = 0,
        count: Int = words.size - from,
    ): Int {
        var a = GOLDEN_RATIO
        var b = GOLDEN_RATIO
        var c = seed
        var i = from
        var rem = count
        while (rem >= 3) {
            a += words[i]
            b += words[i + 1]
            c += words[i + 2]
            // mix(a, b, c)
            a -= b
            a -= c
            a = a xor (c ushr 13)
            b -= c
            b -= a
            b = b xor (a shl 8)
            c -= a
            c -= b
            c = c xor (b ushr 13)
            a -= b
            a -= c
            a = a xor (c ushr 12)
            b -= c
            b -= a
            b = b xor (a shl 16)
            c -= a
            c -= b
            c = c xor (b ushr 5)
            a -= b
            a -= c
            a = a xor (c ushr 3)
            b -= c
            b -= a
            b = b xor (a shl 10)
            c -= a
            c -= b
            c = c xor (b ushr 15)
            i += 3
            rem -= 3
        }
        c += count
        if (rem == 2) b += words[i + 1]
        if (rem >= 1) a += words[i]
        a -= b
        a -= c
        a = a xor (c ushr 13)
        b -= c
        b -= a
        b = b xor (a shl 8)
        c -= a
        c -= b
        c = c xor (b ushr 13)
        a -= b
        a -= c
        a = a xor (c ushr 12)
        b -= c
        b -= a
        b = b xor (a shl 16)
        c -= a
        c -= b
        c = c xor (b ushr 5)
        a -= b
        a -= c
        a = a xor (c ushr 3)
        b -= c
        b -= a
        b = b xor (a shl 10)
        c -= a
        c -= b
        c = c xor (b ushr 15)
        return c
    }

    /** Hashes an array of 16-bit values (`hash3`/`hash16` of the reference). */
    fun hash16(
        shorts: IntArray,
        seed: Int = 0,
    ): Int {
        var a = GOLDEN_RATIO
        var b = GOLDEN_RATIO
        var c = seed
        var i = 0
        var rem = shorts.size
        while (rem >= 6) {
            a += shorts[i] + (shorts[i + 1] shl 16)
            b += shorts[i + 2] + (shorts[i + 3] shl 16)
            c += shorts[i + 4] + (shorts[i + 5] shl 16)
            a -= b
            a -= c
            a = a xor (c ushr 13)
            b -= c
            b -= a
            b = b xor (a shl 8)
            c -= a
            c -= b
            c = c xor (b ushr 13)
            a -= b
            a -= c
            a = a xor (c ushr 12)
            b -= c
            b -= a
            b = b xor (a shl 16)
            c -= a
            c -= b
            c = c xor (b ushr 5)
            a -= b
            a -= c
            a = a xor (c ushr 3)
            b -= c
            b -= a
            b = b xor (a shl 10)
            c -= a
            c -= b
            c = c xor (b ushr 15)
            i += 6
            rem -= 6
        }
        c += shorts.size
        if (rem == 5) c += shorts[i + 4] shl 16
        if (rem >= 4) b += shorts[i + 3] shl 16
        if (rem >= 3) b += shorts[i + 2]
        if (rem >= 2) a += shorts[i + 1] shl 16
        if (rem >= 1) a += shorts[i]
        a -= b
        a -= c
        a = a xor (c ushr 13)
        b -= c
        b -= a
        b = b xor (a shl 8)
        c -= a
        c -= b
        c = c xor (b ushr 13)
        a -= b
        a -= c
        a = a xor (c ushr 12)
        b -= c
        b -= a
        b = b xor (a shl 16)
        c -= a
        c -= b
        c = c xor (b ushr 5)
        a -= b
        a -= c
        a = a xor (c ushr 3)
        b -= c
        b -= a
        b = b xor (a shl 10)
        c -= a
        c -= b
        c = c xor (b ushr 15)
        return c
    }
}
