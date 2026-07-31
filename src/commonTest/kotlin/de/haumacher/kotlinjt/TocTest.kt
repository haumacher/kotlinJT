package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertEquals

// spec: Figure 12 — TOC Segment, Figure 13 — TOC Entry data collections (§5.3)
class TocTest {
    @Test
    fun v9EntryIs28BytesWithI32Offsets() {
        for (order in Endianness.entries) {
            val entry = TocEntry(testGuid(7), 4711, 815, (7u shl 24) or 0x17u)
            val writer = ByteWriter(order)
            entry.writeTo(writer, JtVersion(9, 5))
            val bytes = writer.toByteArray()
            assertEquals(28, bytes.size)
            val back = TocEntry.read(ByteReader(bytes, order), JtVersion(9, 5))
            assertEquals(entry, back)
            assertEquals(7, back.typeCode)
        }
    }

    @Test
    fun v10EntryIs32BytesWithU64Offset() {
        for (order in Endianness.entries) {
            val entry = TocEntry(testGuid(8), 0x2_0000_0010L, 0x8000_0000L, 1u shl 24)
            val writer = ByteWriter(order)
            entry.writeTo(writer, JtVersion(10, 0))
            val bytes = writer.toByteArray()
            assertEquals(32, bytes.size)
            val back = TocEntry.read(ByteReader(bytes, order), JtVersion(10, 0))
            assertEquals(entry, back)
            assertEquals(1, back.typeCode)
        }
    }

    @Test
    fun typeCodeIsBits24To31() {
        val entry = TocEntry(testGuid(1), 0, 0, 0xFF00_0017u)
        assertEquals(0xFF, entry.typeCode)
    }
}
