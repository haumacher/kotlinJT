package de.haumacher.kotlinjt.io

/**
 * A growable byte sink encoding the primitive data types of the JT v10 reference, clause 4,
 * in a caller-selected [Endianness]. The write-side mirror of [ByteReader].
 */
class ByteWriter(
    val order: Endianness,
    initialCapacity: Int = 64,
) {
    private var data = ByteArray(if (initialCapacity > 0) initialCapacity else 64)
    var size: Int = 0
        private set

    private fun ensure(count: Int) {
        val needed = size + count
        if (needed > data.size) {
            var newSize = data.size * 2
            while (newSize < needed) newSize *= 2
            data = data.copyOf(newSize)
        }
    }

    private fun put(value: Int) {
        data[size] = value.toByte()
        size += 1
    }

    fun writeU8(value: UByte) {
        ensure(1)
        put(value.toInt())
    }

    fun writeUChar(value: UByte) = writeU8(value)

    fun writeI16(value: Short) {
        ensure(2)
        val v = value.toInt()
        when (order) {
            Endianness.LITTLE_ENDIAN -> {
                put(v)
                put(v ushr 8)
            }
            Endianness.BIG_ENDIAN -> {
                put(v ushr 8)
                put(v)
            }
        }
    }

    fun writeU16(value: UShort) = writeI16(value.toShort())

    fun writeI32(value: Int) {
        ensure(4)
        when (order) {
            Endianness.LITTLE_ENDIAN -> {
                put(value)
                put(value ushr 8)
                put(value ushr 16)
                put(value ushr 24)
            }
            Endianness.BIG_ENDIAN -> {
                put(value ushr 24)
                put(value ushr 16)
                put(value ushr 8)
                put(value)
            }
        }
    }

    fun writeU32(value: UInt) = writeI32(value.toInt())

    fun writeI64(value: Long) {
        val low = value.toInt()
        val high = (value ushr 32).toInt()
        when (order) {
            Endianness.LITTLE_ENDIAN -> {
                writeI32(low)
                writeI32(high)
            }
            Endianness.BIG_ENDIAN -> {
                writeI32(high)
                writeI32(low)
            }
        }
    }

    fun writeU64(value: ULong) = writeI64(value.toLong())

    fun writeF32(value: Float) = writeI32(value.toRawBits())

    fun writeF64(value: Double) = writeI64(value.toRawBits())

    fun writeBytes(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    fun writeBytes(bytes: Bytes) = writeBytes(bytes.toByteArray())

    fun writeGuid(guid: Guid) {
        writeU32(guid.data1)
        writeU16(guid.data2)
        writeU16(guid.data3)
        writeBytes(guid.data4)
    }

    /** String: I32 count followed by single-byte characters. Characters must be <= 0xFF. */
    fun writeString(value: String) {
        writeI32(value.length)
        ensure(value.length)
        for (ch in value) {
            require(ch.code <= 0xFF) { "String character out of single-byte range: ${ch.code}" }
            put(ch.code)
        }
    }

    /** MbString: I32 count followed by U16 characters. */
    fun writeMbString(value: String) {
        writeI32(value.length)
        for (ch in value) {
            writeU16(ch.code.toUShort())
        }
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}
