package de.haumacher.kotlinjt.io

/**
 * A JT GUID (clause 4, Table 4): a 128-bit identifier stored as one U32, two U16 and eight U8.
 * The first three components follow the file byte order; the eight tail bytes do not.
 */
data class Guid(
    val data1: UInt,
    val data2: UShort,
    val data3: UShort,
    val data4: Bytes,
) {
    init {
        require(data4.size == 8) { "GUID tail must be 8 bytes, got ${data4.size}" }
    }

    /** Canonical text form, e.g. `{10DD10AB-2AC8-11D1-9B6B-0080C7BB5997}`. */
    override fun toString(): String {
        val tail = data4.toHex().uppercase()
        return "{${hex(data1.toLong(), 8)}-${hex(data2.toLong(), 4)}-${hex(data3.toLong(), 4)}-" +
            "${tail.substring(0, 4)}-${tail.substring(4)}}"
    }

    companion object {
        private fun hex(
            value: Long,
            width: Int,
        ): String = value.toString(16).uppercase().padStart(width, '0')

        /**
         * The end-of-elements marker `{FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF}` that terminates
         * an element list inside a data segment (clause 5.1.3.2).
         */
        val END_OF_ELEMENTS: Guid =
            Guid(
                0xFFFF_FFFFu,
                0xFFFFu,
                0xFFFFu,
                ByteArray(8) { 0xFF.toByte() }.toBytes(),
            )
    }
}
