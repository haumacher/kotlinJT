package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// spec: Figure 11 — File Header data collection (§5.2)
class FileHeaderTest {
    private fun headerBytes(
        order: Endianness,
        version: String,
        emptyField: Int,
        tocOffsetWide: Boolean,
        tocOffset: Long,
        trailing: Boolean = false,
    ): ByteArray {
        val writer = ByteWriter(order)
        writer.writeBytes(versionBytes(version))
        writer.writeU8(order.headerByte.toUByte())
        writer.writeI32(emptyField)
        if (tocOffsetWide) writer.writeU64(tocOffset.toULong()) else writer.writeI32(tocOffset.toInt())
        writer.writeGuid(testGuid(1, 2, 3, 4))
        if (trailing) writer.writeGuid(testGuid(9, 9, 9, 9))
        return writer.toByteArray()
    }

    @Test
    fun parseV9LittleEndian() {
        val bytes = headerBytes(Endianness.LITTLE_ENDIAN, "Version 9.5 JT test", 0, tocOffsetWide = false, tocOffset = 4711)
        val header = FileHeader.parse(bytes)
        assertEquals(JtVersion(9, 5), header.version)
        assertEquals(Endianness.LITTLE_ENDIAN, header.byteOrder)
        assertEquals(0, header.emptyField)
        assertEquals(4711L, header.tocOffset)
        assertEquals(testGuid(1, 2, 3, 4), header.lsgSegmentId)
        assertNull(header.trailingGuid)
        assertEquals(105, header.headerLength)
        assertEquals("Version 9.5 JT test", header.versionString)
    }

    @Test
    fun parseV9BigEndian() {
        val bytes = headerBytes(Endianness.BIG_ENDIAN, "Version 8.1 JT test", 0, tocOffsetWide = false, tocOffset = 815)
        val header = FileHeader.parse(bytes)
        assertEquals(JtVersion(8, 1), header.version)
        assertEquals(Endianness.BIG_ENDIAN, header.byteOrder)
        assertEquals(815L, header.tocOffset)
    }

    @Test
    fun parseV10WideTocOffset() {
        val bytes =
            headerBytes(Endianness.LITTLE_ENDIAN, "Version 10.0 JT test", 0, tocOffsetWide = true, tocOffset = 0x1_0000_0001L)
        val header = FileHeader.parse(bytes)
        assertEquals(JtVersion(10, 0), header.version)
        assertEquals(0x1_0000_0001L, header.tocOffset)
        assertNull(header.trailingGuid)
        assertEquals(109, header.headerLength)
    }

    @Test
    fun parseV10TrailingGuidWhenEmptyFieldNotZero() {
        val bytes =
            headerBytes(
                Endianness.LITTLE_ENDIAN,
                "Version 10.0 JT test",
                1,
                tocOffsetWide = true,
                tocOffset = 200,
                trailing = true,
            )
        val header = FileHeader.parse(bytes)
        assertEquals(testGuid(9, 9, 9, 9), header.trailingGuid)
        assertEquals(125, header.headerLength)
    }

    @Test
    fun writeReproducesBytes() {
        for (order in Endianness.entries) {
            for (wide in listOf(false, true)) {
                val versionLine = if (wide) "Version 10.0 JT test" else "Version 9.5 JT test"
                val bytes = headerBytes(order, versionLine, 0, wide, 12345)
                val header = FileHeader.parse(bytes)
                val writer = ByteWriter(order)
                header.writeTo(writer)
                assertEquals(bytes.toList(), writer.toByteArray().toList())
            }
        }
    }

    @Test
    fun refusesGarbage() {
        // Too short.
        assertFailsWith<JtFormatException> { FileHeader.parse(ByteArray(10)) }
        // No parseable version string.
        assertFailsWith<JtFormatException> {
            FileHeader.parse(ByteArray(120) { 'x'.code.toByte() })
        }
        // Invalid byte-order byte.
        val bad = headerBytes(Endianness.LITTLE_ENDIAN, "Version 9.5 JT test", 0, false, 105)
        bad[80] = 2
        assertFailsWith<JtFormatException> { FileHeader.parse(bad) }
    }

    @Test
    fun versionStringParsing() {
        assertEquals(JtVersion(10, 0), FileHeader.parseVersion("Version 10.0 JT DM 8.0.5.0"))
        assertEquals(JtVersion(9, 5), FileHeader.parseVersion("Version 9.5 JT  NetAllied JTWriter R14"))
        assertNull(FileHeader.parseVersion("not a JT file"))
    }
}
