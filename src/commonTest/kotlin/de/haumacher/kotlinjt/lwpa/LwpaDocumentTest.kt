package de.haumacher.kotlinjt.lwpa

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.forBothOrders
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The JT LWPA per-figure contract in **both** wire generations: v10 §9 Figures 99–101 and JT 9.5
 * §7.2.9 Figures 214–217. **No fixture carries a JT LWPA segment**, in either generation, so every
 * assertion here is spec-derived — which is exactly why the decoder is strict: the two `VecI32`
 * vectors must have `Analytic Surface Count` entries, the surface types must be *Supported Surface
 * Type* values, and the element body must consume to its declared length. A producer that
 * contradicts any of it gets an opaque carry with a named note, never a misread.
 *
 * Because there is no fixture, **round-trip is the strongest proof available** and every frame
 * built here is asserted `encode(decode(frame))`-identical, including both states of the one
 * `Analytic Surface Count > 0` guard.
 *
 * The Analytic Surface Creation flow chart (9.5 Figure 217 == v10 Figure 102, identical box for
 * box) is not a byte layout: it says how many numbers each surface *consumes* from the four arrays.
 * Consuming them is a projection this library does not build yet — see the deferral table in
 * DESIGN.md.
 */
class LwpaDocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v105 = JtVersion(10, 5)
    private val baseTypeJtBase = 9

    /** An `Int32CDP`/`Int32CDP2` packet in its CODEC-0 form — byte-identical in both generations. */
    private fun ByteWriter.nullInt32(values: List<Int>) {
        writeI32(values.size)
        writeU8(0u)
        writeI32(32 * values.size)
        for (value in values) writeI32(value)
    }

    private fun ByteWriter.vecF64(values: List<Double>) {
        writeI32(values.size)
        for (value in values) writeF64(value)
    }

    private fun lwpaFrame(
        order: Endianness,
        typeId: Guid = ObjectTypeIds.JT_LWPA_ELEMENT,
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
    ): ByteArray {
        val writer = ByteWriter(order)
        for (frame in frames) writer.writeBytes(frame)
        writer.writeI32(16)
        writer.writeGuid(Guid.END_OF_ELEMENTS)
        writer.writeI16(1)
        writer.writeI32(0)
        return writer.toByteArray()
    }

    private fun roundTrip(
        bytes: ByteArray,
        order: Endianness,
        version: JtVersion = v105,
    ): LwpaDocument {
        val result = LwpaDocument.decode(bytes.toBytes(), version, order)
        assertEquals(emptyList(), result.notes, "typed decode must be note-free")
        assertContentEquals(bytes, result.document.encode(order).toByteArray(), "encode(decode(bytes)) drifted")
        return result.document
    }

    /** Decodes and asserts the element was refused by name, carried opaquely and re-encoded exactly. */
    private fun assertRefusedByName(
        bytes: ByteArray,
        order: Endianness,
        version: JtVersion,
        note: String,
    ) {
        val result = LwpaDocument.decode(bytes.toBytes(), version, order)
        assertEquals(listOf(note), result.notes.map { it.name })
        assertIs<OpaqueLwpaElement>(result.document.elements.single())
        assertContentEquals(bytes, result.document.encode(order).toByteArray(), "an opaque carry must be byte-exact")
    }

    // -----------------------------------------------------------------------
    // v10 generation — §9, Figures 99–101
    // -----------------------------------------------------------------------

    // spec: Figure 99, Figure 100, Figure 101
    @Test
    fun lwpaElementWithAnalyticSurfacesDecodes() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order, objectId = 7) {
                    writeU8(1u) // U8 Version Number
                    writeU32(5u) // Surface Count: five B-Rep surfaces...
                    writeU32(3u) // Analytic Surface Count: ...three of them analytic
                    nullInt32(listOf(0, 2, 4)) // Analytic Surface Indices (Lag1; primers only)
                    nullInt32(listOf(1, 2, 5)) // Analytic Surface Type: plane, cylinder, torus
                    vecF64(listOf(0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)) // Coordinate Array
                    vecF64(listOf(0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)) // Axis Array
                    vecF64(listOf(2.5, 10.0, 1.0)) // Radius Array
                    vecF64(listOf(0.5)) // Radian Array
                }
            val document = roundTrip(segment(order, frame), order)
            val element = assertIs<JtLwpaElement>(document.elements.single())
            assertEquals(7, element.objectId)
            assertEquals(1, element.version)
            assertEquals(5u, element.surfaceCount)
            assertEquals(3u, element.analyticSurfaceCount)
            val geometry = assertIs<AnalyticSurfaceGeometry>(element.geometry)
            assertEquals(listOf(0, 2, 4), geometry.surfaceIndices.values)
            assertEquals(
                listOf(AnalyticSurfaceType.PLANE, AnalyticSurfaceType.CYLINDER, AnalyticSurfaceType.TORUS),
                geometry.types,
            )
            assertEquals(9, geometry.coordinates.size)
            assertEquals(listOf(2.5, 10.0, 1.0), geometry.radii)
            assertEquals(listOf(0.5), geometry.radians)
        }

    /**
     * Figure 100's conditional: with no analytic surfaces the geometry collection is absent.
     *
     * spec: Figure 100
     */
    @Test
    fun lwpaElementWithoutAnalyticSurfacesStopsAfterTheCounts() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(12u)
                    writeU32(0u)
                }
            val document = roundTrip(segment(order, frame), order)
            val element = assertIs<JtLwpaElement>(document.elements.single())
            assertNull(element.geometry)
            assertEquals(12u, element.surfaceCount)
        }

    // spec: Figure 101
    @Test
    fun aSurfaceTypeOutsideTheSupportedTableRefuses() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(1u)
                    writeU32(1u)
                    nullInt32(listOf(0))
                    nullInt32(listOf(6)) // the table reserves 6 and 7 — no layout is defined
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                }
            assertRefusedByName(segment(order, frame), order, v105, "ELEMENT_DECODE_FAILED")
        }

    // spec: Figure 101
    @Test
    fun anIndexVectorContradictingTheAnalyticCountRefuses() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(3u)
                    writeU32(2u)
                    nullInt32(listOf(0)) // one index for two analytic surfaces
                    nullInt32(listOf(1, 1))
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                }
            assertRefusedByName(segment(order, frame), order, v105, "ELEMENT_DECODE_FAILED")
        }

    // spec: Figure 100
    @Test
    fun moreAnalyticSurfacesThanSurfacesRefuses() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(1u)
                    writeU32(2u)
                }
            assertRefusedByName(segment(order, frame), order, v105, "ELEMENT_DECODE_FAILED")
        }

    // -----------------------------------------------------------------------
    // JT 9 generation — 9.5 §7.2.9, Figures 214–216
    // -----------------------------------------------------------------------

    /**
     * A conformant JT 9.5 element, field for field: `I16` version, two `I32` counts, and the
     * Figure-216 geometry. The v10 reader would take one byte for the version and desynchronize
     * from the Surface Count on — see [aJt9BodyReadTheV10WayIsRefusedByName], which feeds the
     * *same bytes* to the other generation.
     *
     * spec: 9.5 Figure 214, 9.5 Figure 215, 9.5 Figure 216
     */
    @Test
    fun jt9LwpaElementWithAnalyticSurfacesDecodes() =
        forBothOrders { order ->
            val frame = jt9FrameWithThreeSurfaces(order)
            val document = roundTrip(segment(order, frame), order, v9)
            val element = assertIs<JtLwpaElement>(document.elements.single())
            assertEquals(11, element.objectId)
            assertEquals(1, element.version)
            assertEquals(5u, element.surfaceCount)
            assertEquals(3u, element.analyticSurfaceCount)
            val geometry = assertIs<AnalyticSurfaceGeometry>(element.geometry)
            assertEquals(listOf(0, 2, 4), geometry.surfaceIndices.values)
            assertEquals(
                listOf(AnalyticSurfaceType.PLANE, AnalyticSurfaceType.CYLINDER, AnalyticSurfaceType.TORUS),
                geometry.types,
            )
            assertEquals(coordinateArrayForThreeSurfaces, geometry.coordinates)
            assertEquals(axisArrayForThreeSurfaces, geometry.axes)
            assertEquals(listOf(2.5, 10.0, 1.0), geometry.radii)
            assertEquals(emptyList(), geometry.radians)

            // The chart's consumption arithmetic, on the one frame built to satisfy it:
            // 3 surfaces × 1 point, × 2 unit vectors; cylinder 1 radius + torus 2; no cone.
            // spec: 9.5 Figure 217
            assertEquals(3 * 3, geometry.coordinates.size)
            assertEquals(3 * 6, geometry.axes.size)
            assertEquals(1 + 2, geometry.radii.size)
            assertEquals(0, geometry.radians.size)
        }

    /**
     * Three analytic surfaces — a plane, a cylinder and a torus — with the four arrays sized
     * exactly as the Analytic Surface Creation chart consumes them: one point (3 coordinates) and
     * two unit vectors (6 axis values) per surface, one radius for the cylinder, two for the
     * torus, and **no radian at all** because only a cone reads that array. `LwpaFixtureTest`
     * asserts the same identity on the first real LWPA file the corpus acquires.
     */
    private fun jt9FrameWithThreeSurfaces(order: Endianness): ByteArray =
        lwpaFrame(order, objectId = 11) {
            writeI16(1) // I16 Version Number — two bytes, not one
            writeI32(5) // I32 Surface Count
            writeI32(3) // I32 Analytic Surface Count
            nullInt32(listOf(0, 2, 4)) // Int32CDP2, Lag1 (fewer than four values: primers only)
            nullInt32(listOf(1, 2, 5)) // Int32CDP2, NULL: plane, cylinder, torus
            vecF64(coordinateArrayForThreeSurfaces)
            vecF64(axisArrayForThreeSurfaces)
            vecF64(listOf(2.5, 10.0, 1.0)) // cylinder radius; torus major and minor
            vecF64(emptyList()) // no cone, so no semi-angle
        }

    /** One point per surface: plane, cylinder, torus. */
    private val coordinateArrayForThreeSurfaces =
        listOf(
            0.0, 0.0, 0.0,
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
        )

    /** Two unit vectors (`axis`, then `x_axis`) per surface, in the same order. */
    private val axisArrayForThreeSurfaces =
        listOf(
            0.0, 0.0, 1.0, 1.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0, 1.0, 0.0,
        )

    /**
     * Figure 215's one conditional, in its off state: with no analytic surfaces the element body
     * is base type, object id, `I16` version and two `I32` counts and stops — eleven bytes.
     *
     * spec: 9.5 Figure 215
     */
    @Test
    fun jt9LwpaElementWithoutAnalyticSurfacesStopsAfterTheCounts() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeI16(1)
                    writeI32(12)
                    writeI32(0)
                }
            // I32 length + GUID + base type + object id + I16 version + two I32 counts, and stop.
            assertEquals(4 + 16 + 1 + 4 + 2 + 4 + 4, frame.size, "Figure 215's off-state frame is exactly 35 bytes")
            val document = roundTrip(segment(order, frame), order, v9)
            val element = assertIs<JtLwpaElement>(document.elements.single())
            assertNull(element.geometry)
            assertEquals(12u, element.surfaceCount)
        }

    /**
     * The `I16` is read as **one number of two bytes**, not as a `U8` plus the first byte of the
     * Surface Count. The frame writes version `0x0101`, whose two bytes are equal, so the byte
     * *order* cannot mask a `U8` misread — only a genuine 16-bit read yields 257.
     *
     * 9.5 says only version 1 is defined; the reader is deliberately lenient about the value
     * (nothing in Figure 215 is guarded on it), which is what lets this width probe exist.
     *
     * spec: 9.5 Figure 215
     */
    @Test
    fun jt9VersionNumberIsOneI16NotAReinterpretedBytePair() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeI16(0x0101)
                    writeI32(4)
                    writeI32(0)
                }
            val document = roundTrip(segment(order, frame), order, v9)
            val element = assertIs<JtLwpaElement>(document.elements.single())
            assertEquals(257, element.version, "a U8 read would report 1 and shift every later field")
            assertEquals(4u, element.surfaceCount, "the Surface Count must still land on its own four bytes")
        }

    /**
     * The width delta is *material*: the very bytes [jt9LwpaElementWithAnalyticSurfacesDecodes]
     * decodes cleanly are refused by name when the v10 layout is applied to them. Without the
     * generation dispatch the library would either misread them or need a fixture to notice.
     *
     * spec: 9.5 Figure 215
     */
    @Test
    fun aJt9BodyReadTheV10WayIsRefusedByName() =
        forBothOrders { order ->
            assertRefusedByName(segment(order, jt9FrameWithThreeSurfaces(order)), order, v105, "ELEMENT_DECODE_FAILED")
        }

    /**
     * Figure 216's codec really is the Mk. 2 packet of 9.5 §8.1.2, not v10's third-generation
     * `Int32CDP`. The two agree byte for byte on the CODEC-0 form, so this frame uses the
     * **Bitlength** CODEC, whose bit grammar differs: 9.5 spends 6+6 bits on the widths of a
     * signed min/max pair, v10 nibble-encodes them. The same 25 bits therefore mean
     * `[1, 2, 4]` to the Mk. 2 decoder and nothing coherent to the v10 one.
     *
     * spec: 9.5 Figure 216
     */
    @Test
    fun jt9AnalyticSurfaceVectorsUseTheMk2Packet() =
        forBothOrders { order ->
            // Fixed-width mode: mode bit 0 | minBits=2 | maxBits=4 | min=1 | max=4 | 00 01 11,
            // i.e. field width bitlength(4-1)=2 and the values 1, 2, 4 biased by min.
            // 0 000010 000100 01 0100 00 01 11, zero-padded to one 32-bit word.
            val bitlengthWord = 0x04228380
            val frame =
                lwpaFrame(order) {
                    writeI16(1)
                    writeI32(3)
                    writeI32(3)
                    nullInt32(listOf(0, 1, 2)) // Analytic Surface Indices, plainly encoded
                    // Analytic Surface Type through the Bitlength CODEC.
                    writeI32(3) // Value Count
                    writeU8(1u) // CODEC type 1: Bitlength
                    writeI32(25) // CodeText Length in bits
                    writeI32(bitlengthWord)
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                }
            val bytes = segment(order, frame)

            val document = roundTrip(bytes, order, v9)
            val geometry = assertIs<AnalyticSurfaceGeometry>(assertIs<JtLwpaElement>(document.elements.single()).geometry)
            assertEquals(
                listOf(AnalyticSurfaceType.PLANE, AnalyticSurfaceType.CYLINDER, AnalyticSurfaceType.SPHERE),
                geometry.types,
                "the Mk. 2 bitlength grammar decodes this CodeText to 1, 2, 4",
            )

            // The v10 packet reader cannot make sense of the same bits — and says so by name
            // rather than inventing three surface types.
            assertRefusedByName(bytes, order, v105, "ELEMENT_DECODE_FAILED")
        }

    /**
     * `Lag1` on the Analytic Surface Indices is applied, not merely recorded: the predictor only
     * bites past the four primers, so this frame carries five surfaces. Residuals
     * `0, 2, 4, 6, 1` unpack to indices `0, 2, 4, 6, 7`.
     *
     * spec: 9.5 Figure 216
     */
    @Test
    fun jt9AnalyticSurfaceIndicesAreLag1Predicted() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeI16(1)
                    writeI32(9)
                    writeI32(5)
                    nullInt32(listOf(0, 2, 4, 6, 1))
                    nullInt32(listOf(1, 1, 1, 1, 1))
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                }
            val document = roundTrip(segment(order, frame), order, v9)
            val geometry = assertIs<AnalyticSurfaceGeometry>(assertIs<JtLwpaElement>(document.elements.single()).geometry)
            assertEquals(listOf(0, 2, 4, 6, 7), geometry.surfaceIndices.values)
            assertEquals(listOf(0, 2, 4, 6, 1), geometry.surfaceIndices.packet.values, "the wire residuals are preserved")
        }

    /**
     * A body one byte short of Figure 215 is refused **by name** and carried verbatim — it does
     * not throw out of the API, and it does not decode a truncated element.
     *
     * spec: 9.5 Figure 215
     */
    @Test
    fun aJt9BodyOneByteShortIsRefusedByName() =
        forBothOrders { order ->
            val full = jt9FrameWithThreeSurfaces(order)
            // Drop the last byte of the element body and shrink the frame's length field to match,
            // so the element list still frames cleanly and only the *body* is short.
            val shortBody = full.copyOfRange(20, full.size - 1)
            val writer = ByteWriter(order)
            writer.writeI32(16 + shortBody.size)
            writer.writeGuid(ObjectTypeIds.JT_LWPA_ELEMENT)
            writer.writeBytes(shortBody)
            assertRefusedByName(segment(order, writer.toByteArray()), order, v9, "ELEMENT_DECODE_FAILED")
        }

    /**
     * The mirror of the short body: a byte too many. Figure 215 has no trailing field, so the
     * element must consume its body exactly.
     *
     * spec: 9.5 Figure 215
     */
    @Test
    fun aJt9BodyWithATrailingByteIsRefusedByName() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeI16(1)
                    writeI32(2)
                    writeI32(0)
                    writeU8(0u) // nothing in Figure 215 follows the counts when the guard is off
                }
            assertRefusedByName(segment(order, frame), order, v9, "ELEMENT_DECODE_FAILED")
        }

    /**
     * 9.5 writes the counts as `I32`; a negative one is refused by name rather than laundered
     * into four billion surfaces by the [UInt] the model keeps.
     *
     * spec: 9.5 Figure 215
     */
    @Test
    fun aJt9NegativeSurfaceCountIsRefusedByName() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeI16(1)
                    writeI32(-1)
                    writeI32(0)
                }
            val result = LwpaDocument.decode(segment(order, frame).toBytes(), v9, order)
            val note = assertIs<LoadNote.ElementDecodeFailed>(result.notes.single())
            assertContains(note.detail, "Surface Count -1 is negative", message = "the note must say what was wrong")
        }

    // -----------------------------------------------------------------------
    // Segment framing
    // -----------------------------------------------------------------------

    /**
     * A *named* element type that has no documented place in an LWPA segment — 9.5 Annex A
     * Table 11 and v10 Annex A both list exactly one type under segment type 24 — is carried
     * opaquely with the layout-unverified note, in either generation.
     *
     * spec: 9.5 Figure 214
     */
    @Test
    fun aForeignNamedElementTypeIsCarriedOpaquelyWithANote() =
        forBothOrders { order ->
            val frame = lwpaFrame(order, typeId = ObjectTypeIds.WIREFRAME_REP_ELEMENT) { writeI32(99) }
            assertRefusedByName(segment(order, frame), order, v9, "ELEMENT_LAYOUT_UNVERIFIED")
            assertRefusedByName(segment(order, frame), order, v105, "ELEMENT_LAYOUT_UNVERIFIED")
        }

    // spec: Figure 99
    @Test
    fun anUnknownElementTypeIsCarriedOpaquelyWithANote() =
        forBothOrders { order ->
            val alien = Guid(0xDEADBEEFu, 0x1234u, 0x5678u, ByteArray(8) { it.toByte() }.toBytes())
            val bytes = segment(order, lwpaFrame(order, typeId = alien) { writeI32(99) })
            val result = LwpaDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("UNKNOWN_ELEMENT_TYPE"), result.notes.map { it.name })
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }
}
