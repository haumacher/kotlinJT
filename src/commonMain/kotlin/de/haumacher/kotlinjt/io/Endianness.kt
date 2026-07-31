package de.haumacher.kotlinjt.io

/**
 * The byte order of multi-byte values in a JT file, selected by the Byte Order byte of the
 * File Header (JT v10 reference, 5.1.1): `0` — least significant byte first, `1` — most
 * significant byte first.
 */
enum class Endianness(val headerByte: Int) {
    LITTLE_ENDIAN(0),
    BIG_ENDIAN(1),
    ;

    companion object {
        fun fromHeaderByte(value: Int): Endianness? =
            when (value) {
                0 -> LITTLE_ENDIAN
                1 -> BIG_ENDIAN
                else -> null
            }
    }
}
