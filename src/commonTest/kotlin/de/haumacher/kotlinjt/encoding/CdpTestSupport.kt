package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.io.ByteWriter

/**
 * Hand-building tools for the two 9.5-only compressed-data-packet families. **No fixture in the
 * corpus carries either packet**, so every byte vector in these tests is built here, from the
 * document's own figures and from Appendix C's decoder source.
 *
 * Read the evidence classes honestly:
 *
 * - a *framing* assertion built with [BitWriter] and the packet builders below is evidence
 *   about the figure — the bytes are laid out from Figure 218 / 219 / 224 / 225 / 226 and the
 *   reader is required to walk them and put them back;
 * - a *decode* assertion whose CodeText came out of [ArithmeticEncoder] proves only that the
 *   library's decoder and this file's encoder agree. That is a regression net, not conformance.
 *   Wherever a stronger claim is available it is used instead: `Int32CdpMk1Test` decodes a
 *   CodeText that was cross-checked against the reference algorithm for the Mk. 2 packet, and
 *   `Int32CdpMk1FixtureTest` re-frames real fixture packets.
 */
class BitWriter {
    private val bytes = mutableListOf<Byte>()
    private var buffer = 0
    private var filled = 0

    /** The number of bits written so far — a packet's `I32 CodeText Length`. */
    var bitCount: Int = 0
        private set

    fun writeBit(bit: Int): BitWriter {
        buffer = (buffer shl 1) or (bit and 1)
        filled += 1
        bitCount += 1
        if (filled == 8) {
            bytes.add(buffer.toByte())
            buffer = 0
            filled = 0
        }
        return this
    }

    /** Writes the low [count] bits of [value], most significant first. */
    fun writeBits(
        value: Int,
        count: Int,
    ): BitWriter {
        for (i in count - 1 downTo 0) writeBit((value ushr i) and 1)
        return this
    }

    /** The bits so far, zero-padded to whole bytes — the "Alignment Bits" of Figure 219. */
    fun toBytes(): ByteArray {
        val out = bytes.toMutableList()
        if (filled > 0) out.add((buffer shl (8 - filled)).toByte())
        return out.toByteArray()
    }

    /** The bits so far as `U32` CodeText words, zero-padded to a whole word. */
    fun toWords(): List<Int> {
        val padded = toBytes().toMutableList()
        while (padded.size % 4 != 0) padded.add(0)
        return (0 until padded.size / 4).map { i ->
            (padded[4 * i].toInt() and 0xFF shl 24) or
                (padded[4 * i + 1].toInt() and 0xFF shl 16) or
                (padded[4 * i + 2].toInt() and 0xFF shl 8) or
                (padded[4 * i + 3].toInt() and 0xFF)
        }
    }
}

/**
 * A 16-bit arithmetic *encoder* — the inverse of the `ArithmeticDecoder` core (9.5 Appendix C
 * §3.1/§3.2's `removeSymbolFromStream` run backwards, with the classic pending-bit treatment of
 * the underflow squeeze). It exists only to manufacture CodeText for tests; the library never
 * encodes an arithmetic stream, it re-serializes the one it read.
 *
 * `Int32CdpMk1Test.theTestEncoderAgreesWithTheFixtureVerifiedDecoder` pins it against the
 * already fixture-verified Mk. 2 decode path, so a bug here shows up as that test failing rather
 * than as a false pass elsewhere.
 */
class ArithmeticEncoder {
    private val bits = BitWriter()
    private var low = 0
    private var high = 0xFFFF
    private var pending = 0

    /** Encodes one symbol occupying `[cumulativeLow, cumulativeLow + count)` of [total]. */
    fun encode(
        cumulativeLow: Int,
        count: Int,
        total: Int,
    ) {
        val range = high - low + 1
        high = low + range * (cumulativeLow + count) / total - 1
        low = low + range * cumulativeLow / total
        while (true) {
            if ((high xor low).inv() and 0x8000 != 0) {
                emit(high ushr 15 and 1)
            } else if (low and 0x4000 != 0 && high and 0x4000 == 0) {
                pending += 1
                low = low and 0x3FFF
                high = high or 0x4000
            } else {
                break
            }
            low = (low shl 1) and 0xFFFF
            high = ((high shl 1) or 1) and 0xFFFF
        }
    }

    /** Flushes the register and returns the finished CodeText. */
    fun finish(): BitWriter {
        pending += 1
        emit(if (low and 0x4000 != 0) 1 else 0)
        return bits
    }

    private fun emit(bit: Int) {
        bits.writeBit(bit)
        while (pending > 0) {
            bits.writeBit(1 - bit)
            pending -= 1
        }
    }
}

/** One probability context table entry as the test builders take it. */
data class TestEntry(
    val symbol: Int,
    val occurrenceCount: Int,
    val associatedValue: Int,
    val nextContext: Int = 0,
)

/**
 * Encodes [symbols] (indices into [tables], starting in table 0 and following each entry's
 * `nextContext`) into a CodeText, exactly as the Mk. 1 driver will decode it.
 */
fun encodeSymbols(
    tables: List<List<TestEntry>>,
    symbols: List<Int>,
): BitWriter {
    val encoder = ArithmeticEncoder()
    var table = 0
    for (index in symbols) {
        val entries = tables[table]
        val total = entries.sumOf { it.occurrenceCount }
        val cumulative = entries.take(index).sumOf { it.occurrenceCount }
        encoder.encode(cumulative, entries[index].occurrenceCount, total)
        table = entries[index].nextContext
    }
    return encoder.finish()
}

/**
 * Builds the `Int32 Probability Contexts` collection of Figure 219 for [tables], choosing the
 * narrowest field widths that hold the values — which is what a producer does, and what makes
 * the width fields worth testing at all.
 */
fun int32ProbabilityContexts1(tables: List<List<TestEntry>>): ByteArray {
    val bits = BitWriter()
    bits.writeBits(tables.size, 8)
    val allEntries = tables.flatten()
    val minValue = allEntries.minOf { it.associatedValue }
    val symbolBits = widthOf(allEntries.maxOf { it.symbol + 2 })
    val occurrenceBits = widthOf(allEntries.maxOf { it.occurrenceCount })
    val valueBits = widthOf(allEntries.maxOf { it.associatedValue - minValue })
    val nextContextBits = widthOf(allEntries.maxOf { it.nextContext })
    for ((index, entries) in tables.withIndex()) {
        bits.writeBits(entries.size, 32)
        bits.writeBits(symbolBits, 6)
        bits.writeBits(occurrenceBits, 6)
        if (index == 0) bits.writeBits(valueBits, 6)
        bits.writeBits(nextContextBits, 6)
        if (index == 0) bits.writeBits(minValue, 32)
        for (entry in entries) {
            bits.writeBits(entry.symbol + 2, symbolBits)
            bits.writeBits(entry.occurrenceCount, occurrenceBits)
            bits.writeBits(entry.associatedValue - minValue, valueBits)
            bits.writeBits(entry.nextContext, nextContextBits)
        }
    }
    return bits.toBytes()
}

private fun widthOf(maximum: Int): Int {
    var width = 0
    var rest = maximum
    while (rest != 0) {
        width += 1
        rest = rest ushr 1
    }
    return width
}

/** `VecU32` — an `I32` word count followed by the words (p.21). */
fun ByteWriter.writeVecU32(words: List<Int>) {
    writeI32(words.size)
    for (word in words) writeI32(word)
}
