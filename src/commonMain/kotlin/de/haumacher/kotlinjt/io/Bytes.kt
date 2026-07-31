package de.haumacher.kotlinjt.io

/**
 * An immutable byte string. The raw-bytes currency of Layer 0: segment payloads, opaque
 * regions and GUID tails are carried as [Bytes] so that model classes can be immutable data
 * classes with value equality.
 */
class Bytes private constructor(private val data: ByteArray) {
    val size: Int get() = data.size

    fun isEmpty(): Boolean = data.isEmpty()

    operator fun get(index: Int): Byte = data[index]

    /** A defensive copy of the content. */
    fun toByteArray(): ByteArray = data.copyOf()

    fun slice(
        fromIndex: Int,
        toIndex: Int,
    ): Bytes {
        require(fromIndex in 0..toIndex && toIndex <= data.size) {
            "invalid slice [$fromIndex, $toIndex) of $size bytes"
        }
        return Bytes(data.copyOfRange(fromIndex, toIndex))
    }

    fun toHex(): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "Bytes($size)"

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"

        val EMPTY: Bytes = Bytes(ByteArray(0))

        fun of(data: ByteArray): Bytes = if (data.isEmpty()) EMPTY else Bytes(data.copyOf())

        fun of(
            data: ByteArray,
            fromIndex: Int,
            toIndex: Int,
        ): Bytes = if (fromIndex == toIndex) EMPTY else Bytes(data.copyOfRange(fromIndex, toIndex))
    }
}

fun ByteArray.toBytes(): Bytes = Bytes.of(this)
