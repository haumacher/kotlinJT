package de.haumacher.kotlinjt.io

import de.haumacher.kotlinjt.JtFormatException

/**
 * A positioned reader over an in-memory byte image, decoding the primitive data types of the
 * JT v10 reference, clause 4 (Data Types), in a caller-selected [Endianness].
 *
 * Reading past the end or reading a structurally impossible value (e.g. a negative string
 * count) throws [JtFormatException]; the parser catches it at well-defined boundaries and
 * turns it into a named load note or a clean top-level error — it never escapes the public
 * parse API for recoverable, segment-local problems.
 */
class ByteReader(
    private val data: ByteArray,
    val order: Endianness,
    var position: Int = 0,
) {
    val size: Int get() = data.size
    val remaining: Int get() = data.size - position

    private fun need(count: Int) {
        if (count < 0 || position + count > data.size) {
            throw JtFormatException(
                "attempt to read $count bytes at offset $position of ${data.size}",
            )
        }
    }

    private fun nextByte(): Int {
        val value = data[position].toInt() and 0xFF
        position += 1
        return value
    }

    fun readU8(): UByte {
        need(1)
        return nextByte().toUByte()
    }

    /** UChar is the same wire format as U8 (clause 4, Table 3). */
    fun readUChar(): UByte = readU8()

    fun readI16(): Short {
        need(2)
        val b0 = nextByte()
        val b1 = nextByte()
        return when (order) {
            Endianness.LITTLE_ENDIAN -> ((b1 shl 8) or b0).toShort()
            Endianness.BIG_ENDIAN -> ((b0 shl 8) or b1).toShort()
        }
    }

    fun readU16(): UShort = readI16().toUShort()

    fun readI32(): Int {
        need(4)
        val b0 = nextByte()
        val b1 = nextByte()
        val b2 = nextByte()
        val b3 = nextByte()
        return when (order) {
            Endianness.LITTLE_ENDIAN -> (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            Endianness.BIG_ENDIAN -> (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    fun readU32(): UInt = readI32().toUInt()

    fun readI64(): Long {
        need(8)
        val low: Int
        val high: Int
        when (order) {
            Endianness.LITTLE_ENDIAN -> {
                low = readI32()
                high = readI32()
            }
            Endianness.BIG_ENDIAN -> {
                high = readI32()
                low = readI32()
            }
        }
        return (high.toLong() shl 32) or (low.toLong() and 0xFFFF_FFFFL)
    }

    fun readU64(): ULong = readI64().toULong()

    fun readF32(): Float = Float.fromBits(readI32())

    fun readF64(): Double = Double.fromBits(readI64())

    fun readBytes(count: Int): ByteArray {
        need(count)
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    /** GUID: one U32, two U16 (byte-order dependent) and eight U8 (byte-order independent). */
    fun readGuid(): Guid = Guid(readU32(), readU16(), readU16(), readBytes(8).toBytes())

    /** String: I32 count followed by that many single-byte characters (ISO 8859-1). */
    fun readString(): String {
        val count = readI32()
        if (count < 0) throw JtFormatException("negative String character count $count at offset $position")
        need(count)
        val sb = StringBuilder(count)
        repeat(count) { sb.append(nextByte().toChar()) }
        return sb.toString()
    }

    /** MbString: I32 count followed by that many U16 characters (UTF-16 code units). */
    fun readMbString(): String {
        val count = readI32()
        if (count < 0) throw JtFormatException("negative MbString character count $count at offset $position")
        need(count * 2)
        val sb = StringBuilder(count)
        repeat(count) { sb.append(readU16().toInt().toChar()) }
        return sb.toString()
    }
}
