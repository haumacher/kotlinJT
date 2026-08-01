package de.haumacher.kotlinjt.wireframe

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.encoding.KnotType
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.forBothOrders
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The §10 per-figure contract: hand-built bytes following Figures 103–106 (and the §12
 * collections they pull in — Figures 148–154) decode to the typed model with no notes and
 * serialize back byte-identically. Every refusal path is pinned too: an unknown element type, a
 * JT 9 body whose layout is a different wire format, a contradicting count, undecodable CAD tag
 * vectors, a missing terminator.
 *
 * The real-producer evidence for the same decoders is `WireframeFixtureTest` (the five Wireframe
 * segments of the NIST 10.5 fixture).
 */
class WireframeDocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v105 = JtVersion(10, 5)

    /** Object Base Type 9 ("JtBase", Table 7) — what every Wireframe Rep body starts with. */
    private val baseTypeJtBase = 9

    // ------------------------------------------------------------------
    // Byte-level helpers: the wire forms this package has to read
    // ------------------------------------------------------------------

    /** A null-CODEC Int32CDP: `count`, codec 0, CodeText length, then the values as words. */
    private fun ByteWriter.nullInt32(values: List<Int>) {
        writeI32(values.size)
        writeU8(0u)
        writeI32(32 * values.size)
        for (value in values) writeI32(value)
    }

    /** A null-CODEC Int64CDP: the values as 64-bit words, low-order word first (§12.1.2). */
    private fun ByteWriter.nullInt64(values: List<Long>) {
        writeI32(values.size)
        writeU8(0u)
        writeI32(64 * values.size)
        for (value in values) {
            writeI32((value and 0xFFFFFFFFL).toInt())
            writeI32((value shr 32).toInt())
        }
    }

    private fun ByteWriter.nullFloat64(values: List<Double>) = nullInt64(values.map { it.toRawBits() })

    private fun ByteWriter.emptyPacket() = writeI32(0)

    /**
     * One non-rational NURBS curve with a trivial knot vector — the smallest legal Compressed
     * Curve Data (Figure 150) — over [points] control points of three coordinates each.
     */
    private fun ByteWriter.oneCurve(
        degree: Int,
        points: List<Double>,
    ) {
        writeI32(4) // Entities of Knot Type Exist Flags: none set (trivial knot vector)
        writeI32(0)
        writeI32(0)
        writeI32(0)
        writeI32(0)
        nullInt32(listOf(1)) // Curve Base Types (Table 69)
        nullInt32(listOf(degree))
        nullInt32(listOf(points.size / 3)) // Control Point Counts
        nullInt32(listOf(3)) // Dimensionality (Table 71: non-rational XYZ)
        nullInt32(listOf(0)) // Empty Fields
        writeI32(0) // Weights Count
        emptyPacket()
        emptyPacket()
        nullFloat64(points)
        emptyPacket() // Knot Vectors
    }

    private fun wireframeFrame(
        order: Endianness,
        typeId: Guid = ObjectTypeIds.WIREFRAME_REP_ELEMENT,
        objectId: Int = 0,
        body: ByteWriter.() -> Unit,
    ): ByteArray {
        val bodyWriter = ByteWriter(order)
        bodyWriter.writeU8(baseTypeJtBase.toUByte())
        bodyWriter.writeI32(objectId)
        bodyWriter.body()
        val bodyBytes = bodyWriter.toByteArray()
        val writer = ByteWriter(order)
        writer.writeI32(16 + bodyBytes.size)
        writer.writeGuid(typeId)
        writer.writeBytes(bodyBytes)
        return writer.toByteArray()
    }

    private fun segment(
        order: Endianness,
        vararg frames: ByteArray,
        terminate: Boolean = true,
        propertyTable: Boolean = true,
    ): ByteArray {
        val writer = ByteWriter(order)
        for (frame in frames) writer.writeBytes(frame)
        if (terminate) {
            writer.writeI32(16)
            writer.writeGuid(Guid.END_OF_ELEMENTS)
        }
        if (propertyTable) {
            // The Figure-78 empty Property Table every real producer writes after its elements.
            writer.writeI16(1)
            writer.writeI32(0)
        }
        return writer.toByteArray()
    }

    private fun roundTrip(
        bytes: ByteArray,
        order: Endianness,
        version: JtVersion = v105,
    ): WireframeDocument {
        val result = WireframeDocument.decode(bytes.toBytes(), version, order)
        assertEquals(emptyList(), result.notes, "typed decode must be note-free")
        assertContentEquals(bytes, result.document.encode(order).toByteArray(), "encode(decode(bytes)) drifted")
        return result.document
    }

    // ------------------------------------------------------------------
    // The full Wireframe Rep Element
    // ------------------------------------------------------------------

    /**
     * Two edges over two model-space NURBS curves — one non-rational straight line, one rational
     * degree-2 curve with five control points and a non-trivial (odd-count, `[0:1]`) knot vector —
     * plus a CAD tag per edge. Exercises Figures 104, 105, 106, 148, 149, 150, 152, 153 and 154 in
     * one body.
     *
     * spec: Figure 104, Figure 105, Figure 106
     */
    @Test
    fun wireframeRepElementWithCurvesAndCadTagsDecodes() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order, objectId = 42) {
                    writeU8(1u) // U8 Version Number — §10.1's prose, not Figure 104's I16 box
                    writeI32(2) // Edge Count
                    writeI32(2) // MCS Curve Count
                    nullInt32(listOf(0, 1)) // MCS Curve Indices (NULL predictor, Revision B)
                    nullInt32(listOf(10, 11)) // Edge Tags
                    // --- Wireframe MCS Curves Geometric Data = one Compressed Curve Data ---
                    writeI32(4) // Entities of Knot Type Exist Flags (Table 68)
                    writeI32(0)
                    writeI32(0)
                    writeI32(1) // curve 1 has an "Odd Count [0:1] Range" knot vector
                    writeI32(0)
                    nullInt32(listOf(1)) // Entity Index Codes (Lag1; primers only here)
                    nullInt32(listOf(1, 1)) // Curve Base Types (Table 69: NURBS)
                    nullInt32(listOf(1, 2)) // Degrees
                    nullInt32(listOf(2, 5)) // Control Point Counts
                    nullInt32(listOf(3, 4)) // Dimensionality (Table 71: non-rational, rational)
                    nullInt32(listOf(0, 0)) // Empty Fields
                    writeI32(5) // Weights Count = the rational curve's control point count
                    nullInt32(listOf(0, 2)) // Weight Indices (ascending, Lag1 primers)
                    nullFloat64(listOf(0.5, 0.25)) // Weight Values
                    nullFloat64(CONTROL_POINTS) // 21 = (2 + 5) points x 3 coordinates
                    nullFloat64(listOf(0.25, 0.75)) // Knot Vectors: 8 knots - 2*3 clamping = 2
                    writeI32(12) // Edge Tag Counter
                    writeU32(1u) // CAD Tags Flag
                    writeCadTagData(order, types = listOf(1, 1), type1 = listOf(1001, 1002))
                }
            val document = roundTrip(segment(order, frame), order)
            val rep = assertIs<WireframeRepElement>(document.elements.single())
            assertEquals(42, rep.objectId)
            assertEquals(1, rep.version)
            assertEquals(2, rep.edgeCount)
            assertEquals(2, rep.mcsCurveCount)
            assertEquals(listOf(0, 1), rep.mcsCurveIndices?.values)
            assertEquals(listOf(10, 11), rep.edgeTags?.values)
            assertEquals(12, rep.edgeTagCounter)
            assertEquals(listOf(1001L, 1002L), rep.edgeCadTags)

            val curves = rep.curves
            assertEquals(2, curves.size)
            assertEquals(1, curves[0].degree)
            assertEquals(2, curves[0].controlPointCount)
            assertEquals(listOf(1.0, 1.0), curves[0].weights, "a non-rational curve's weights are all 1")
            assertNull(curves[0].knotType, "curve 0 has a trivial knot vector")
            assertEquals(emptyList(), curves[0].storedKnotValues)
            assertEquals(2, curves[1].degree)
            assertEquals(5, curves[1].controlPointCount)
            // §12.1.14: unstored weights are 1.0; indices 0 and 2 carry the stored ones.
            assertEquals(listOf(0.5, 1.0, 0.25, 1.0, 1.0), curves[1].weights)
            assertEquals(KnotType.ODD_COUNT_UNIT_RANGE, curves[1].knotType)
            assertEquals(listOf(0.25, 0.75), curves[1].storedKnotValues)
            assertEquals(8, curves[1].knotCount)
            assertContentEquals(CONTROL_POINTS.subList(6, 21).toDoubleArray(), curves[1].coordinates.toDoubleArray())
        }

    /**
     * A rep with no edges and no curves: Figure 104's two conditional blocks are both absent.
     *
     * spec: Figure 104
     */
    @Test
    fun emptyWireframeRepElementSkipsBothConditionalBlocks() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    writeU32(0u)
                }
            val document = roundTrip(segment(order, frame), order)
            val rep = assertIs<WireframeRepElement>(document.elements.single())
            assertNull(rep.mcsCurveIndices)
            assertNull(rep.edgeTags)
            assertNull(rep.mcsCurves)
            assertNull(rep.cadTagData)
            assertEquals(emptyList(), rep.curves)
        }

    /**
     * CAD Tags Flag 0: Figure 106's collection is not on the wire.
     *
     * spec: Figure 104
     */
    @Test
    fun withoutCadTagsFlagNoTagCollectionIsRead() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(1)
                    writeI32(1)
                    nullInt32(listOf(0))
                    nullInt32(listOf(7))
                    oneCurve(1, listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 0.0))
                    writeI32(8)
                    writeU32(0u)
                }
            val document = roundTrip(segment(order, frame), order)
            val rep = assertIs<WireframeRepElement>(document.elements.single())
            assertNull(rep.cadTagData)
            assertEquals(emptyList(), rep.edgeCadTags)
        }

    /**
     * 64-bit CAD tags (Table 72 type 2) travel in the Int64 vector.
     *
     * spec: Figure 154, Table 72
     */
    @Test
    fun sixtyFourBitCadTagsDecodeFromTheInt64Vector() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(2)
                    writeI32(1)
                    nullInt32(listOf(0, 0))
                    nullInt32(listOf(1, 2))
                    oneCurve(1, listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
                    writeI32(0)
                    writeU32(1u)
                    writeCadTagData(
                        order,
                        types = listOf(2, 1),
                        type1 = listOf(77),
                        type2 = listOf(0x0000_0100_0000_0001L),
                    )
                }
            val document = roundTrip(segment(order, frame), order)
            val rep = assertIs<WireframeRepElement>(document.elements.single())
            // Tags come back in CAD Tag Types order: the 64-bit one first, then the 32-bit one.
            assertEquals(listOf(0x0000_0100_0000_0001L, 77L), rep.edgeCadTags)
            assertEquals(2, rep.cadTagData?.tagCount)
        }

    // ------------------------------------------------------------------
    // Refusals — every one named, every one lossless
    // ------------------------------------------------------------------

    @Test
    fun anUnknownElementTypeIsCarriedOpaquelyWithANote() =
        forBothOrders { order ->
            val alien = Guid(0xDEADBEEFu, 0x1234u, 0x5678u, ByteArray(8) { it.toByte() }.toBytes())
            val frame = wireframeFrame(order, typeId = alien) { writeI32(1234) }
            val bytes = segment(order, frame)
            val result = WireframeDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("UNKNOWN_ELEMENT_TYPE"), result.notes.map { it.name })
            val opaque = assertIs<OpaqueWireframeElement>(result.document.elements.single())
            assertEquals(alien, opaque.objectTypeId)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    /**
     * The JT 9 generation's Wireframe Rep Element is a *different* wire format (the v9.5
     * reference's Figure 130: I16 version, Lag1-predicted index vectors, JT 9 "Mk. 2" CDPs) and no
     * v9 fixture carries one — so it is carried opaquely with a named note, never guessed.
     */
    @Test
    fun aJt9BodyIsCarriedOpaquelyBecauseItsLayoutIsUnverified() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    writeU32(0u)
                }
            val bytes = segment(order, frame)
            val result = WireframeDocument.decode(bytes.toBytes(), v9, order)
            assertEquals(listOf("ELEMENT_LAYOUT_UNVERIFIED"), result.notes.map { it.name })
            assertIs<OpaqueWireframeElement>(result.document.elements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    @Test
    fun anEdgeCountContradictingTheIndexVectorRefusesTheTypedDecode() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(3) // three edges...
                    writeI32(1)
                    nullInt32(listOf(0, 0)) // ...but only two indices
                    nullInt32(listOf(1, 2))
                    writeI32(0)
                    writeU32(0u)
                }
            val bytes = segment(order, frame)
            val result = WireframeDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
            assertIs<OpaqueWireframeElement>(result.document.elements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    @Test
    fun aCurveBaseTypeOutsideTableSixtyNineRefuses() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(0)
                    writeI32(1)
                    writeI32(4)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    nullInt32(listOf(2)) // Curve Base Type 2 - Table 69 defines only 1
                    nullInt32(listOf(1))
                    nullInt32(listOf(2))
                    nullInt32(listOf(3))
                    nullInt32(listOf(0))
                    writeI32(0)
                    emptyPacket()
                    emptyPacket()
                    nullFloat64(listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
                    emptyPacket()
                    writeI32(0)
                    writeU32(0u)
                }
            val result = WireframeDocument.decode(segment(order, frame).toBytes(), v105, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        }

    /**
     * Undecodable CAD tag vectors are the one place this package keeps *partial* structure: the
     * collection's own Data Length pins the extent, so the framing decodes, the coded bytes stay
     * verbatim, and `CAD_TAG_VECTORS_UNRECOGNIZED` names it.
     *
     * spec: Figure 154
     */
    @Test
    fun undecodableCadTagVectorsAreKeptVerbatimWithANote() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(1)
                    writeI32(1)
                    nullInt32(listOf(0))
                    nullInt32(listOf(5))
                    oneCurve(1, listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
                    writeI32(0)
                    writeU32(1u)
                    writeU8(1u)
                    writeI32(8 + 5)
                    writeI32(1)
                    writeBytes(byteArrayOf(9, 9, 9, 9, 9))
                }
            val bytes = segment(order, frame)
            val result = WireframeDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("CAD_TAG_VECTORS_UNRECOGNIZED"), result.notes.map { it.name })
            val rep = assertIs<WireframeRepElement>(result.document.elements.single())
            assertNull(rep.cadTagData?.tags)
            assertEquals(5, rep.cadTagData?.codedData?.size)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: Figure 103
    @Test
    fun anUnterminatedElementListKeepsItsRemainderVerbatim() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    writeU32(0u)
                }
            val bytes = segment(order, frame, terminate = false, propertyTable = false) + byteArrayOf(1, 2, 3)
            val result = WireframeDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("WIREFRAME_STRUCTURE_UNRECOGNIZED"), result.notes.map { it.name })
            assertEquals(3, result.document.trailing.size)
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    // spec: Figure 78
    @Test
    fun aStreamEndingAfterTheElementListSaysThePropertyTableIsMissing() =
        forBothOrders { order ->
            val frame =
                wireframeFrame(order) {
                    writeU8(1u)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    writeU32(0u)
                }
            val bytes = segment(order, frame, propertyTable = false)
            val result = WireframeDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("PROPERTY_TABLE_MISSING"), result.notes.map { it.name })
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

    @Test
    fun knotTypeStoredValueCountsFollowTheTableSixtyEightFormula() {
        // §12.1.13's own switch, for a degree-3 curve with 6 control points (10 knots, clamp 4).
        assertEquals(1, KnotType.EVEN_COUNT_UNIT_RANGE.storedValueCount(10, 4))
        assertEquals(3, KnotType.EVEN_COUNT_ARBITRARY_RANGE.storedValueCount(10, 4))
        assertEquals(2, KnotType.ODD_COUNT_UNIT_RANGE.storedValueCount(10, 4))
        assertEquals(4, KnotType.ODD_COUNT_ARBITRARY_RANGE.storedValueCount(10, 4))
        assertTrue(KnotType.entries.map { it.index } == listOf(0, 1, 2, 3))
    }

    // ------------------------------------------------------------------

    /**
     * Compressed CAD Tag Data (Figure 154). Both tag vectors are written even when the type does
     * not occur — as an empty packet, which is what the NIST bodies do (see the class docs of
     * `CompressedCadTagData`). The Data Length spans the field itself plus everything after it
     * (delta 34).
     */
    private fun ByteWriter.writeCadTagData(
        order: Endianness,
        types: List<Int>,
        type1: List<Int> = emptyList(),
        type2: List<Long> = emptyList(),
    ) {
        val coded = ByteWriter(order)
        coded.nullInt32(types)
        if (type1.isEmpty()) coded.emptyPacket() else coded.nullInt32(type1)
        if (type2.isEmpty()) coded.emptyPacket() else coded.nullInt64(type2)
        val codedBytes = coded.toByteArray()
        writeU8(1u)
        writeI32(8 + codedBytes.size)
        writeI32(1)
        writeBytes(codedBytes)
    }

    private companion object {
        /** Two control points for curve 0, five for curve 1 — three coordinates each. */
        val CONTROL_POINTS =
            listOf(
                0.0, 0.0, 0.0, 10.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.5, 1.0, 3.0, 0.0, 1.0, 4.0, -0.5, 1.0,
            )
    }
}
