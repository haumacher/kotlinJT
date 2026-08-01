package de.haumacher.kotlinjt.shape

import kotlin.test.Test
import kotlin.test.assertEquals

// spec: Annex C

/**
 * The JT hash function (Annex C: Bob Jenkins' lookup2, word variant). Expected values were
 * computed independently from the reference C source; the fixture battery additionally
 * verifies the hashes stored in every real shape body against this implementation.
 */
class JtHashTest {
    @Test
    fun hash32MatchesReferenceVectors() {
        assertEquals(-1119235827, JtHash.hash32(intArrayOf()))
        assertEquals(-453826757, JtHash.hash32(intArrayOf(1, 2, 3)))
        assertEquals(192918773, JtHash.hash32(intArrayOf(0xdeadbeef.toInt()), 42))
        assertEquals(2045506597, JtHash.hash32(IntArray(12) { it + 1 }))
    }

    @Test
    fun hash32Chaining() {
        // Seeding with the previous hash is how the composite hashes combine streams.
        val once = JtHash.hash32(intArrayOf(4, 5, 6), JtHash.hash32(intArrayOf(1, 2, 3)))
        val again = JtHash.hash32(intArrayOf(4, 5, 6), JtHash.hash32(intArrayOf(1, 2, 3)))
        assertEquals(once, again)
    }

    @Test
    fun hash16MatchesReferenceVectors() {
        assertEquals(-1119235827, JtHash.hash16(intArrayOf()))
        assertEquals(-1013477286, JtHash.hash16(intArrayOf(1, 2, 3, 4, 5, 6, 7)))
    }
}
