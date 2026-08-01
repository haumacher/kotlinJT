package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.codec.zlibDeflate
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// spec: Figure 10 — JT File Structure, Figure 14 — Data Segment, Figure 15 — Segment Header (§5.1, §5.4)

/**
 * The committed half of the Layer 0 losslessness guarantee: parse → re-serialize is
 * byte-identical, on synthetic images of both file generations and both byte orders.
 * (The real-producer half runs in the JVM fixture-discovery suite.)
 */
class SyntheticFileRoundTripTest {
    private fun shapePayload(order: Endianness): ByteArray {
        val writer = ByteWriter(order)
        writer.writeBytes(elementBytes(order, testGuid(0x10DD10AB, 0x2AC8, 0x11D1, 0x9B), 4, ByteArray(40) { it.toByte() }))
        writer.writeBytes(endOfElementsBytes(order))
        writer.writeBytes(byteArrayOf(1, 0, 0, 0, 0, 0))
        return writer.toByteArray()
    }

    private fun lsgElementData(order: Endianness): ByteArray {
        val writer = ByteWriter(order)
        writer.writeBytes(elementBytes(order, testGuid(0x10DD103E, 0x2AC8, 0x11D1, 0x9B), 1, ByteArray(30) { (it * 3).toByte() }))
        writer.writeBytes(endOfElementsBytes(order))
        writer.writeBytes(elementBytes(order, testGuid(0x10DD106E, 0x2AC8, 0x11D1, 0x9B), 5, ByteArray(10)))
        writer.writeBytes(endOfElementsBytes(order))
        writer.writeBytes(byteArrayOf(1, 0, 2, 0, 0, 0, 7, 7))
        return writer.toByteArray()
    }

    private fun v9File(order: Endianness): ByteArray {
        val lsgId = testGuid(0x35, 0x23, 0x48, 0xE1)
        val plain = lsgElementData(order)
        val zlibPayload = compressionWrapper(order, flag = 2u, algorithm = 2, body = zlibDeflate(plain))
        return TestFileAssembler(order, JtVersion(9, 5), lsgId)
            .addSegment(testGuid(0x29, 0x23, 0x48, 0xE1), 7, shapePayload(order))
            .addSegment(lsgId, 1, zlibPayload)
            .build()
    }

    @Test
    fun v9RoundTripLittleEndian() = v9RoundTrip(Endianness.LITTLE_ENDIAN)

    @Test
    fun v9RoundTripBigEndian() = v9RoundTrip(Endianness.BIG_ENDIAN)

    private fun v9RoundTrip(order: Endianness) {
        val bytes = v9File(order)
        val file = JtFile.parse(bytes)

        assertEquals(emptyList(), file.notes, "a well-formed file loads silently: ${file.notes}")
        assertEquals(JtVersion(9, 5), file.header.version)
        assertEquals(order, file.header.byteOrder)
        assertEquals(2, file.segments.size)

        val shape = file.segments[0]
        assertEquals(SegmentKind.SHAPE_LOD0, shape.kind)
        assertNull(shape.compression)
        assertNotNull(shape.elementData)
        val shapeScan = assertNotNull(shape.elements)
        assertEquals(1, shapeScan.lists.size)
        assertEquals(2, shapeScan.lists[0].elements.size)
        assertEquals(6, shapeScan.trailing.size)

        val lsg = file.segments[1]
        assertEquals(SegmentKind.LOGICAL_SCENE_GRAPH, lsg.kind)
        val compression = assertNotNull(lsg.compression)
        assertEquals(2u, compression.flag)
        assertEquals(2, compression.algorithmCode)
        // The inflated view is the plain element data...
        assertContentEquals(lsgElementData(order), assertNotNull(lsg.elementData).toByteArray())
        // ...and the raw payload stays byte-faithful next to it.
        assertEquals(9 + compression.bodyLength, lsg.payload.size)
        val lsgScan = assertNotNull(lsg.elements)
        assertEquals(2, lsgScan.lists.size)
        assertEquals(8, lsgScan.trailing.size)

        assertContentEquals(bytes, file.serialize(), "Layer 0 losslessness: parse → serialize must be byte-identical")
    }

    @Test
    fun v10RoundTripWithCorruptLzmaNote() {
        val order = Endianness.LITTLE_ENDIAN
        val lsgId = testGuid(0x35, 0x23, 0x48, 0xE1)
        // A v10-style LSG segment flagged LZMA (flag 3, algorithm 3) whose body is not an
        // xz stream: decoding must refuse with the named note, keep the raw bytes, and
        // still round-trip byte-identically.
        val lzmaPayload = compressionWrapper(order, flag = 3u, algorithm = 3, body = ByteArray(50) { it.toByte() })
        val bytes =
            TestFileAssembler(order, JtVersion(10, 0), lsgId)
                .addSegment(testGuid(0x29, 0x23, 0x48, 0xE1), 7, shapePayload(order))
                .addSegment(lsgId, 1, lzmaPayload)
                .build()

        val file = JtFile.parse(bytes)
        assertEquals(listOf("COMPRESSED_DATA_CORRUPT"), file.notes.map { it.name })
        val lsg = file.segments[1]
        assertEquals(3, assertNotNull(lsg.compression).algorithmCode)
        assertNull(lsg.elementData, "no decoded view for a refused codec")
        assertEquals(59, lsg.payload.size)
        assertContentEquals(bytes, file.serialize())
    }

    // spec: §12.2.5 — segment-wide LZMA (the .xz container) on the decodeCompressible path
    @Test
    fun v10RoundTripWithLzmaDecoding() {
        val order = Endianness.LITTLE_ENDIAN
        val lsgId = testGuid(0x36, 0x23, 0x48, 0xE1)
        // liblzma-written .xz of a minimal element stream (one end-of-elements frame):
        // an LSG segment compressed the way the real 10.5 producer compresses it.
        val plain = byteArrayOf(0x10, 0, 0, 0) + ByteArray(16) { 0xFF.toByte() }
        val xz =
            (
                "fd377a585a000004e6d6b4460200210116000000742fe5a3e0001300095d00080033605a0afd4000000000" +
                    "0068392c2a2230e78400012514b1b1afd21fb6f37d010000000004595a"
            ).let { hex -> ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
        val bytes =
            TestFileAssembler(order, JtVersion(10, 5), lsgId)
                .addSegment(lsgId, 1, compressionWrapper(order, flag = 3u, algorithm = 3, body = xz))
                .build()

        val file = JtFile.parse(bytes)
        assertEquals(emptyList(), file.notes, "a decodable LZMA segment loads silently")
        val lsg = file.segments[0]
        assertEquals(3, assertNotNull(lsg.compression).algorithmCode)
        assertContentEquals(plain, assertNotNull(lsg.elementData).toByteArray())
        assertContentEquals(bytes, file.serialize())
    }

    @Test
    fun v10RoundTripBigEndianWithTrailingHeaderGuid() {
        val order = Endianness.BIG_ENDIAN
        val lsgId = testGuid(0x35, 0x23, 0x48, 0xE1)
        val plain = lsgElementData(order)
        val bytes =
            TestFileAssembler(
                order,
                JtVersion(10, 0),
                lsgId,
                emptyField = 1,
                trailingGuid = testGuid(0xAA, 0xBB, 0xCC, 0xDD),
            )
                .addSegment(lsgId, 1, compressionWrapper(order, 2u, 2, zlibDeflate(plain)))
                .build()

        val file = JtFile.parse(bytes)
        assertEquals(emptyList(), file.notes)
        assertEquals(testGuid(0xAA, 0xBB, 0xCC, 0xDD), file.header.trailingGuid)
        assertEquals(125, file.header.headerLength)
        assertContentEquals(plain, assertNotNull(file.segments[0].elementData).toByteArray())
        assertContentEquals(bytes, file.serialize())
    }

    @Test
    fun uncompressedStorageInsideCompressibleSegment() {
        // Algorithm code 1 inside a compressible segment type: fields present, body plain.
        val order = Endianness.LITTLE_ENDIAN
        val lsgId = testGuid(0x35, 0x23, 0x48, 0xE1)
        val plain = lsgElementData(order)
        val bytes =
            TestFileAssembler(order, JtVersion(9, 5), lsgId)
                .addSegment(lsgId, 1, compressionWrapper(order, 1u, 1, plain))
                .build()
        val file = JtFile.parse(bytes)
        assertEquals(emptyList(), file.notes)
        val lsg = file.segments[0]
        assertEquals(1, assertNotNull(lsg.compression).algorithmCode)
        assertContentEquals(plain, assertNotNull(lsg.elementData).toByteArray())
        assertContentEquals(bytes, file.serialize())
    }

    @Test
    fun inventoryIsHumanReadable() {
        val file = JtFile.parse(v9File(Endianness.LITTLE_ENDIAN))
        val inventory = file.inventory()
        assertTrue(inventory.contains("version 9.5"))
        assertTrue(inventory.contains("Shape LOD0"))
        assertTrue(inventory.contains("Logical Scene Graph"))
        assertTrue(inventory.contains("zlib"))
        assertTrue(inventory.contains("notes: none"))

        val json = file.inventoryJson()
        assertTrue(json.contains("\"noteNames\": []"))
        assertTrue(json.contains("\"type\": 7"))
        assertTrue(json.contains("\"type\": 1"))
        // Deterministic: rendering twice yields the same string.
        assertEquals(json, file.inventoryJson())
    }
}
