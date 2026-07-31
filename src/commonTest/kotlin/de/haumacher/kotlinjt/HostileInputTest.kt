package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hostile and truncated input: every failure is either a clean [JtFormatException] at the
 * top (nothing parseable) or a *named* load note on a parsed file whose raw bytes are fully
 * preserved — parse → serialize stays byte-identical even for damaged files.
 */
class HostileInputTest {
    private val order = Endianness.LITTLE_ENDIAN
    private val version = JtVersion(9, 5)
    private val lsgId = testGuid(0x35)

    private fun assembler() = TestFileAssembler(order, version, lsgId)

    private fun shapePayload(): ByteArray {
        val e = elementBytes(order, testGuid(1), 4, byteArrayOf(1, 2, 3))
        val eoe = endOfElementsBytes(order)
        return e + eoe
    }

    private fun assertRoundTrip(
        bytes: ByteArray,
        file: JtFile,
    ) {
        assertContentEquals(bytes, file.serialize(), "even a damaged file must re-serialize byte-identically")
    }

    @Test
    fun unparseableImagesFailCleanly() {
        assertFailsWith<JtFormatException> { JtFile.parse(ByteArray(0)) }
        assertFailsWith<JtFormatException> { JtFile.parse(ByteArray(50)) }
        assertFailsWith<JtFormatException> { JtFile.parse(ByteArray(200) { 'x'.code.toByte() }) }
    }

    @Test
    fun truncatedFileFailsCleanly() {
        val bytes = assembler().addSegment(testGuid(1), 7, shapePayload()).build()
        // Cut into the TOC: the TOC becomes unusable, a clean top-level error.
        assertFailsWith<JtFormatException> { JtFile.parse(bytes.copyOf(bytes.size - 10)) }
        // Cut into the header: same.
        assertFailsWith<JtFormatException> { JtFile.parse(bytes.copyOf(90)) }
    }

    @Test
    fun tocEntryOutOfBoundsIsANamedNote() {
        val bytes =
            assembler()
                .addSegment(testGuid(1), 7, shapePayload())
                .addTocEntry(testGuid(0xBAD), offset = 1_000_000, length = 100, typeCode = 7)
                .build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("SEGMENT_OUT_OF_BOUNDS"), file.notes.map { it.name })
        assertEquals(1, file.segments.size)
        assertRoundTrip(bytes, file)
    }

    @Test
    fun tocEntryTooShortIsANamedNote() {
        // An entry claiming 10 bytes cannot hold a segment header; its bytes are preserved
        // through the gap mechanism, so both notes appear.
        val bytes =
            assembler()
                .addSegment(testGuid(1), 7, shapePayload())
                .addTocEntry(testGuid(0xBAD), offset = 105, length = 10, typeCode = 7)
                .build()
        val file = JtFile.parse(bytes)
        assertTrue(file.notes.any { it.name == "SEGMENT_TOO_SHORT" }, "${file.notes}")
        assertRoundTrip(bytes, file)
    }

    @Test
    fun unknownSegmentTypeIsOpaqueWithNamedNote() {
        val bytes = assembler().addSegment(testGuid(1), 99, shapePayload()).build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("UNKNOWN_SEGMENT_TYPE"), file.notes.map { it.name })
        val segment = file.segments[0]
        assertNull(segment.kind)
        assertNull(segment.elementData, "unknown types are opaque")
        assertEquals(shapePayload().size, segment.payload.size)
        assertRoundTrip(bytes, file)
    }

    @Test
    fun unknownCompressionAlgorithmIsANamedNote() {
        val payload = compressionWrapper(order, flag = 2u, algorithm = 7, body = ByteArray(20))
        val bytes = assembler().addSegment(lsgId, 1, payload).build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("UNKNOWN_COMPRESSION_ALGORITHM"), file.notes.map { it.name })
        assertNull(file.segments[0].elementData)
        assertRoundTrip(bytes, file)
    }

    @Test
    fun corruptZlibStreamIsANamedNote() {
        val payload = compressionWrapper(order, flag = 2u, algorithm = 2, body = ByteArray(20) { 0x42 })
        val bytes = assembler().addSegment(lsgId, 1, payload).build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("COMPRESSED_DATA_CORRUPT"), file.notes.map { it.name })
        assertNull(file.segments[0].elementData)
        assertEquals(payload.size, file.segments[0].payload.size, "raw bytes stay available")
        assertRoundTrip(bytes, file)
    }

    @Test
    fun oversizedCompressedLengthIsANamedNote() {
        // Declared compressed data length larger than the payload.
        val payload = compressionWrapper(order, flag = 2u, algorithm = 2, body = ByteArray(10))
        // Patch the I32 length field (payload offset 4) to something impossible.
        payload[4] = 0x7F
        payload[5] = 0x00
        payload[6] = 0x00
        payload[7] = 0x00
        val bytes = assembler().addSegment(lsgId, 1, payload).build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("COMPRESSION_HEADER_INCONSISTENT"), file.notes.map { it.name })
        assertRoundTrip(bytes, file)
    }

    @Test
    fun compressiblePayloadTooShortForCompressionFieldsIsANamedNote() {
        val bytes = assembler().addSegment(lsgId, 1, byteArrayOf(1, 2, 3)).build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("COMPRESSION_HEADER_INCONSISTENT"), file.notes.map { it.name })
        assertRoundTrip(bytes, file)
    }

    @Test
    fun gapBetweenRegionsIsPreservedWithNamedNote() {
        val bytes =
            assembler()
                .addSegment(testGuid(1), 7, shapePayload())
                .addSegment(testGuid(2), 7, shapePayload(), gapBefore = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0, 1, 2))
                .build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("UNMAPPED_REGION"), file.notes.map { it.name })
        assertTrue(file.regions.any { it is FileRegion.GapRegion && it.length == 5L })
        assertRoundTrip(bytes, file)
    }

    @Test
    fun duplicateTocEntryIsShadowedWithNamedNote() {
        val payload = shapePayload()
        val bytes =
            assembler()
                .addSegment(testGuid(1), 7, payload)
                .addTocEntry(testGuid(1), offset = 105, length = 24L + payload.size, typeCode = 7)
                .build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("SEGMENT_REGION_OVERLAP"), file.notes.map { it.name })
        assertEquals(2, file.segments.size)
        assertEquals(1, file.regions.count { it is FileRegion.SegmentRegion && it.shadowed })
        assertRoundTrip(bytes, file)
    }

    @Test
    fun headerFieldMismatchesAreNamedNotes() {
        val bytes =
            assembler()
                .addSegment(
                    testGuid(1),
                    7,
                    shapePayload(),
                    headerGuid = testGuid(2),
                    headerType = 8,
                    declaredLengthDelta = 5,
                )
                .build()
        val file = JtFile.parse(bytes)
        val names = file.notes.map { it.name }.toSet()
        assertEquals(setOf("SEGMENT_ID_MISMATCH", "SEGMENT_TYPE_MISMATCH", "SEGMENT_LENGTH_MISMATCH"), names)
        assertRoundTrip(bytes, file)
    }

    @Test
    fun unrecognizableElementStreamIsANamedNote() {
        // A shape segment whose payload is not element-framed at all.
        val bytes = assembler().addSegment(testGuid(1), 7, ByteArray(30) { 0x01 }).build()
        val file = JtFile.parse(bytes)
        assertEquals(listOf("ELEMENT_STREAM_UNRECOGNIZED"), file.notes.map { it.name })
        assertRoundTrip(bytes, file)
    }

    @Test
    fun everyNoteIsNamed() {
        // The mechanism itself: no note subtype may report a blank name or message.
        val payload = compressionWrapper(order, flag = 2u, algorithm = 7, body = ByteArray(5))
        val bytes =
            assembler()
                .addSegment(testGuid(1), 99, shapePayload())
                .addSegment(lsgId, 1, payload, gapBefore = ByteArray(3))
                .addTocEntry(testGuid(0xBAD), offset = 999_999, length = 50, typeCode = 7)
                .build()
        val file = JtFile.parse(bytes)
        assertTrue(file.notes.isNotEmpty())
        for (note in file.notes) {
            assertTrue(note.name.isNotBlank())
            assertTrue(note.message.isNotBlank())
            assertTrue(note.toString().contains(note.name))
        }
        assertRoundTrip(bytes, file)
    }
}
