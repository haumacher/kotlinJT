package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid

/**
 * The parsed JT file version, taken from the 80-character version string
 * `"Version M.n Comment"` of the File Header.
 */
data class JtVersion(val major: Int, val minor: Int) {
    /**
     * JT 10 widened the File Header's TOC Offset to U64 and the TOC entry's Segment Offset to
     * U64; JT 8/9 store both as I32 (verified against a real 9.5 file — see DESIGN.md).
     */
    val wideOffsets: Boolean get() = major >= 10

    override fun toString(): String = "$major.$minor"
}

/**
 * The File Header (clause 5.1.1): the 80-byte version string, the byte order of everything
 * that follows, and the location of the TOC.
 *
 * [versionBytes] preserves the version string byte-exactly; [emptyField] is preserved as read
 * (clause 4.3: rewriting a file keeps empty-field values). In JT 10 files a trailing GUID
 * follows the LSG segment ID when the empty field is non-zero ([trailingGuid]); JT 8/9
 * headers end after [lsgSegmentId].
 */
data class FileHeader(
    val versionBytes: Bytes,
    val version: JtVersion,
    val byteOrder: Endianness,
    val emptyField: Int,
    val tocOffset: Long,
    val lsgSegmentId: Guid,
    val trailingGuid: Guid?,
) {
    /** The version string as text, without the trailing padding and translation-detection bytes. */
    val versionString: String
        get() {
            val sb = StringBuilder(versionBytes.size)
            for (i in 0 until versionBytes.size) {
                sb.append((versionBytes[i].toInt() and 0xFF).toChar())
            }
            return sb.toString().trimEnd(' ', '\n', '\r')
        }

    /** The size of this header in bytes; the first data segment may start here. */
    val headerLength: Int
        get() = VERSION_LENGTH + 1 + 4 + (if (version.wideOffsets) 8 else 4) + 16 + (if (trailingGuid != null) 16 else 0)

    fun writeTo(writer: ByteWriter) {
        writer.writeBytes(versionBytes)
        writer.writeU8(byteOrder.headerByte.toUByte())
        writer.writeI32(emptyField)
        if (version.wideOffsets) {
            writer.writeU64(tocOffset.toULong())
        } else {
            writer.writeI32(tocOffset.toInt())
        }
        writer.writeGuid(lsgSegmentId)
        trailingGuid?.let { writer.writeGuid(it) }
    }

    companion object {
        const val VERSION_LENGTH: Int = 80

        private val VERSION_PATTERN = Regex("""Version\s+(\d+)\.(\d+)""")

        fun parseVersion(versionString: String): JtVersion? {
            val match = VERSION_PATTERN.find(versionString) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            return JtVersion(major, minor)
        }

        /**
         * Reads the header from the start of [bytes]. Throws [JtFormatException] when the
         * image cannot be a JT file (too short, unparseable version, invalid byte-order byte).
         */
        fun parse(bytes: ByteArray): FileHeader {
            if (bytes.size < VERSION_LENGTH + 1) {
                throw JtFormatException("file of ${bytes.size} bytes is shorter than the JT file header")
            }
            val versionBytes = Bytes.of(bytes, 0, VERSION_LENGTH)
            val versionString =
                buildString {
                    for (i in 0 until VERSION_LENGTH) append((bytes[i].toInt() and 0xFF).toChar())
                }
            val version =
                parseVersion(versionString)
                    ?: throw JtFormatException(
                        "version string does not match 'Version M.n': ${versionString.trimEnd()}",
                    )
            val orderByte = bytes[VERSION_LENGTH].toInt() and 0xFF
            val byteOrder =
                Endianness.fromHeaderByte(orderByte)
                    ?: throw JtFormatException("invalid byte-order byte $orderByte (expected 0 or 1)")
            val reader = ByteReader(bytes, byteOrder, VERSION_LENGTH + 1)
            val emptyField = reader.readI32()
            val tocOffset =
                if (version.wideOffsets) {
                    reader.readU64().toLong()
                } else {
                    reader.readI32().toLong()
                }
            val lsgSegmentId = reader.readGuid()
            val trailingGuid =
                if (version.wideOffsets && emptyField != 0) {
                    reader.readGuid()
                } else {
                    null
                }
            return FileHeader(versionBytes, version, byteOrder, emptyField, tocOffset, lsgSegmentId, trailingGuid)
        }
    }
}
