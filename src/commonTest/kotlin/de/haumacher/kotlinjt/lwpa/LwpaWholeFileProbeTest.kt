package de.haumacher.kotlinjt.lwpa

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.TestFileAssembler
import de.haumacher.kotlinjt.compressionWrapper
import de.haumacher.kotlinjt.endOfElementsBytes
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.testGuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe for the 9.5 LWPA element **inside a whole file**, rather than as a bare element body.
 *
 * The delivered tests decode hand-built frames directly, which is the only way to reach a type
 * no fixture carries — but it skips everything between the file and the element: the TOC entry's
 * type code, the segment header, the ZLIB wrapper 9.5 mandates for this segment type, the
 * element-list terminator, and the Layer 0 re-serialization that the losslessness guarantee
 * actually rests on. A decoder that is right about Figure 215 and wrong about any of those still
 * fails on the first real LWPA file, and no element-level test would say so.
 *
 * So these assemble a complete synthetic 9.5 image with an LWPA segment in it and require the
 * whole stack to agree: named type, clean parse, typed decode, and byte-identical re-emission.
 */
class LwpaWholeFileProbeTest {
    private val baseTypeJtBase = 9

    /** A minimal well-formed 9.5 LWPA element: version 1, no surfaces, guard off. */
    private fun lwpaElement(order: Endianness): ByteArray {
        val body = ByteWriter(order)
        body.writeU8(baseTypeJtBase.toUByte())
        body.writeI32(0)
        // spec: 9.5 Figure 215 — I16 version, then two I32 counts; the analytic block is
        // guarded on Analytic Surface Count > 0, which is 0 here.
        body.writeI16(1)
        body.writeI32(0)
        body.writeI32(0)
        val bodyBytes = body.toByteArray()

        val frame = ByteWriter(order)
        frame.writeI32(16 + bodyBytes.size)
        frame.writeGuid(ObjectTypeIds.JT_LWPA_ELEMENT)
        frame.writeBytes(bodyBytes)
        // Real segments close the element list and then carry the empty Property Table
        // (I16 version 1, I32 count 0) that every producer in the corpus writes.
        val table = ByteWriter(order)
        table.writeI16(1)
        table.writeI32(0)
        return frame.toByteArray() + endOfElementsBytes(order) + table.toByteArray()
    }

    private fun stored(
        order: Endianness,
        body: ByteArray,
    ): ByteArray = compressionWrapper(order, flag = 0u, algorithm = 1, body = body)

    private fun buildFile(order: Endianness): ByteArray {
        val lsgId: Guid = testGuid(1)
        val lwpaId: Guid = testGuid(2)
        return TestFileAssembler(order, de.haumacher.kotlinjt.JtVersion(9, 5), lsgId)
            // Table 6 marks both types as carrying compressed element data, so the
            // payload is a compression wrapper; flag 0 / algorithm 1 is the stored form the
            // library's own writer emits.
            .addSegment(lsgId, SegmentKind.LOGICAL_SCENE_GRAPH.code, stored(order, endOfElementsBytes(order)))
            .addSegment(lwpaId, SegmentKind.LWPA.code, stored(order, lwpaElement(order)))
            .build()
    }

    // spec: 9.5 §7.2.9 (Figure 214) / Figure 215 — the segment, not just the element
    @Test
    fun anLwpaSegmentIsNamedParsedAndDecodedInAWholeFile() {
        for (order in listOf(Endianness.LITTLE_ENDIAN, Endianness.BIG_ENDIAN)) {
            val bytes = buildFile(order)
            val file = JtFile.parse(bytes)

            assertEquals(emptyList(), file.notes.map { it.name }, "$order: the file did not parse cleanly")
            val segment = file.segments.single { it.kind == SegmentKind.LWPA }
            // Annex A / Table 11: LWPA is segment type 24, and the type must be *named*.
            assertEquals(24, segment.typeCode, "$order: LWPA is segment type 24")
            assertNotNull(segment.elementData, "$order: the LWPA element data was not decoded")

            val result = LwpaDocument.decode(segment.elementData!!, file.header.version, file.header.byteOrder)
            assertEquals(emptyList(), result.notes.map { it.name }, "$order: the LWPA element did not decode typed")
            assertTrue(
                result.document.elements.any { it is JtLwpaElement },
                "$order: no typed JT LWPA Element came back, got ${result.document.elements.map { it::class.simpleName }}",
            )

            // Layer 0's guarantee, which the element-level tests cannot reach.
            assertTrue(
                bytes.contentEquals(file.serialize()),
                "$order: a file carrying an LWPA segment did not re-serialize byte-identically",
            )
            // And Layer 1's, through the element stream.
            assertTrue(
                segment.elementData!!.toByteArray()
                    .contentEquals(result.document.encode(file.header.byteOrder).toByteArray()),
                "$order: encode(decode(LWPA element stream)) drifted",
            )
        }
    }
}
