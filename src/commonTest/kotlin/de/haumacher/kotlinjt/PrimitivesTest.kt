package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// spec: §4.2 Data Types

/**
 * The primitive data types of the JT v10 reference, clause 4, decoded from and encoded to
 * hand-built byte sequences following the spec's byte-order rules — both byte orders.
 */
class PrimitivesTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun reader(
        order: Endianness,
        vararg values: Int,
    ) = ByteReader(bytes(*values), order)

    @Test
    fun readU8() {
        assertEquals(0xFEu.toUByte(), reader(Endianness.LITTLE_ENDIAN, 0xFE).readU8())
        assertEquals(0xFEu.toUByte(), reader(Endianness.BIG_ENDIAN, 0xFE).readU8())
    }

    @Test
    fun readU16() {
        assertEquals(0x1234u.toUShort(), reader(Endianness.LITTLE_ENDIAN, 0x34, 0x12).readU16())
        assertEquals(0x1234u.toUShort(), reader(Endianness.BIG_ENDIAN, 0x12, 0x34).readU16())
    }

    @Test
    fun readI16Negative() {
        // -2 = 0xFFFE two's complement
        assertEquals((-2).toShort(), reader(Endianness.LITTLE_ENDIAN, 0xFE, 0xFF).readI16())
        assertEquals((-2).toShort(), reader(Endianness.BIG_ENDIAN, 0xFF, 0xFE).readI16())
    }

    @Test
    fun readI32() {
        assertEquals(0x12345678, reader(Endianness.LITTLE_ENDIAN, 0x78, 0x56, 0x34, 0x12).readI32())
        assertEquals(0x12345678, reader(Endianness.BIG_ENDIAN, 0x12, 0x34, 0x56, 0x78).readI32())
        assertEquals(-1, reader(Endianness.LITTLE_ENDIAN, 0xFF, 0xFF, 0xFF, 0xFF).readI32())
    }

    @Test
    fun readU32() {
        assertEquals(0xDEADBEEFu, reader(Endianness.LITTLE_ENDIAN, 0xEF, 0xBE, 0xAD, 0xDE).readU32())
        assertEquals(0xDEADBEEFu, reader(Endianness.BIG_ENDIAN, 0xDE, 0xAD, 0xBE, 0xEF).readU32())
    }

    @Test
    fun readI64() {
        val le = reader(Endianness.LITTLE_ENDIAN, 0xF0, 0xDE, 0xBC, 0x9A, 0x78, 0x56, 0x34, 0x12)
        assertEquals(0x123456789ABCDEF0L, le.readI64())
        val be = reader(Endianness.BIG_ENDIAN, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0)
        assertEquals(0x123456789ABCDEF0L, be.readI64())
    }

    @Test
    fun readU64() {
        val le = reader(Endianness.LITTLE_ENDIAN, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(ULong.MAX_VALUE, le.readU64())
    }

    @Test
    fun readF32() {
        // IEEE 754: 1.0f = 0x3F800000
        assertEquals(1.0f, reader(Endianness.LITTLE_ENDIAN, 0x00, 0x00, 0x80, 0x3F).readF32())
        assertEquals(1.0f, reader(Endianness.BIG_ENDIAN, 0x3F, 0x80, 0x00, 0x00).readF32())
        // -2.5f = 0xC0200000
        assertEquals(-2.5f, reader(Endianness.BIG_ENDIAN, 0xC0, 0x20, 0x00, 0x00).readF32())
    }

    @Test
    fun readF64() {
        // IEEE 754: 1.0 = 0x3FF0000000000000
        val le = reader(Endianness.LITTLE_ENDIAN, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F)
        assertEquals(1.0, le.readF64())
        val be = reader(Endianness.BIG_ENDIAN, 0x3F, 0xF0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(1.0, be.readF64())
    }

    @Test
    fun readString() {
        // String: I32 count, then single-byte characters.
        assertEquals("AB", reader(Endianness.LITTLE_ENDIAN, 0x02, 0x00, 0x00, 0x00, 0x41, 0x42).readString())
        assertEquals("AB", reader(Endianness.BIG_ENDIAN, 0x00, 0x00, 0x00, 0x02, 0x41, 0x42).readString())
        assertEquals("", reader(Endianness.LITTLE_ENDIAN, 0x00, 0x00, 0x00, 0x00).readString())
    }

    @Test
    fun readMbString() {
        // MbString: I32 count, then U16 characters — the characters follow the byte order too.
        val le = reader(Endianness.LITTLE_ENDIAN, 0x02, 0x00, 0x00, 0x00, 0x41, 0x00, 0xE4, 0x00)
        assertEquals("Aä", le.readMbString())
        val be = reader(Endianness.BIG_ENDIAN, 0x00, 0x00, 0x00, 0x02, 0x00, 0x41, 0x00, 0xE4)
        assertEquals("Aä", be.readMbString())
    }

    @Test
    fun readGuid() {
        // The spec's example GUID {3F2504E0-4F89-11D3-9A0C-0305E82C3301} (clause 4, Table 4):
        // U32 and both U16 follow the byte order, the eight tail bytes do not.
        val expected =
            Guid(
                0x3F2504E0u,
                0x4F89u,
                0x11D3u,
                bytes(0x9A, 0x0C, 0x03, 0x05, 0xE8, 0x2C, 0x33, 0x01).toBytes(),
            )
        val le =
            reader(
                Endianness.LITTLE_ENDIAN,
                0xE0, 0x04, 0x25, 0x3F, 0x89, 0x4F, 0xD3, 0x11,
                0x9A, 0x0C, 0x03, 0x05, 0xE8, 0x2C, 0x33, 0x01,
            )
        assertEquals(expected, le.readGuid())
        val be =
            reader(
                Endianness.BIG_ENDIAN,
                0x3F, 0x25, 0x04, 0xE0, 0x4F, 0x89, 0x11, 0xD3,
                0x9A, 0x0C, 0x03, 0x05, 0xE8, 0x2C, 0x33, 0x01,
            )
        assertEquals(expected, be.readGuid())
        assertEquals("{3F2504E0-4F89-11D3-9A0C-0305E82C3301}", expected.toString())
    }

    @Test
    fun readPastEndFails() {
        assertFailsWith<JtFormatException> { reader(Endianness.LITTLE_ENDIAN, 0x01).readI32() }
        assertFailsWith<JtFormatException> { reader(Endianness.LITTLE_ENDIAN).readU8() }
        // Negative string count is a clean error, not an allocation attempt.
        assertFailsWith<JtFormatException> {
            reader(Endianness.LITTLE_ENDIAN, 0xFF, 0xFF, 0xFF, 0xFF, 0x41).readString()
        }
        assertFailsWith<JtFormatException> {
            reader(Endianness.LITTLE_ENDIAN, 0xFF, 0xFF, 0xFF, 0xFF, 0x41).readMbString()
        }
    }

    @Test
    fun writeReadRoundTripBothOrders() {
        for (order in Endianness.entries) {
            val writer = ByteWriter(order)
            writer.writeU8(0x7Fu)
            writer.writeI16(-12345)
            writer.writeU16(54321u)
            writer.writeI32(-123456789)
            writer.writeU32(3123456789u)
            writer.writeI64(-1234567890123456789L)
            writer.writeU64(12345678901234567890uL)
            // Exactly representable in binary32: on Kotlin/JS a Float is a JS double, so a
            // value like 3.14f would come back as the widened double of its float32 bits.
            writer.writeF32(2.5f)
            writer.writeF64(-2.718281828)
            writer.writeString("kotlinJT")
            writer.writeMbString("JTé")
            writer.writeGuid(Guid.END_OF_ELEMENTS)
            val reader = ByteReader(writer.toByteArray(), order)
            assertEquals(0x7Fu.toUByte(), reader.readU8())
            assertEquals((-12345).toShort(), reader.readI16())
            assertEquals(54321u.toUShort(), reader.readU16())
            assertEquals(-123456789, reader.readI32())
            assertEquals(3123456789u, reader.readU32())
            assertEquals(-1234567890123456789L, reader.readI64())
            assertEquals(12345678901234567890uL, reader.readU64())
            assertEquals(2.5f, reader.readF32())
            assertEquals(-2.718281828, reader.readF64())
            assertEquals("kotlinJT", reader.readString())
            assertEquals("JTé", reader.readMbString())
            assertEquals(Guid.END_OF_ELEMENTS, reader.readGuid())
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun writeProducesSpecBytes() {
        val le = ByteWriter(Endianness.LITTLE_ENDIAN)
        le.writeU16(0x1234u)
        le.writeI32(0x12345678)
        assertContentEquals(bytes(0x34, 0x12, 0x78, 0x56, 0x34, 0x12), le.toByteArray())

        val be = ByteWriter(Endianness.BIG_ENDIAN)
        be.writeU16(0x1234u)
        be.writeI32(0x12345678)
        assertContentEquals(bytes(0x12, 0x34, 0x12, 0x34, 0x56, 0x78), be.toByteArray())
    }
}
