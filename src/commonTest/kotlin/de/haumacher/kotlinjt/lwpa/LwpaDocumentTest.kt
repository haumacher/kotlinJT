package de.haumacher.kotlinjt.lwpa

import de.haumacher.kotlinjt.JtVersion
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

/**
 * The §9 per-figure contract (Figures 99–101). **No fixture carries a JT LWPA segment**, so every
 * assertion here is spec-derived — which is exactly why the decoder is strict: the two
 * `VecI32{Int32CDP}` vectors must have `Analytic Surface Count` entries, the surface types must be
 * Table 100 values, and the element body must consume to its declared length. A producer that
 * contradicts any of it gets an opaque carry with a named note, never a misread.
 *
 * Figure 102 (Analytic Surface Creation) is a flow chart, not a byte layout: it says how many
 * numbers each surface *consumes* from the four arrays. Consuming them is a projection this
 * library does not build yet — see the deferral table in DESIGN.md.
 */
class LwpaDocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v105 = JtVersion(10, 5)
    private val baseTypeJtBase = 9

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

    // spec: Figure 99, Figure 100, Figure 101, Table 100
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

    @Test
    fun aSurfaceTypeOutsideTableOneHundredRefuses() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(1u)
                    writeU32(1u)
                    nullInt32(listOf(0))
                    nullInt32(listOf(6)) // Table 100 reserves 6 and 7 — no layout is defined
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                    vecF64(emptyList())
                }
            val bytes = segment(order, frame)
            val result = LwpaDocument.decode(bytes.toBytes(), v105, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
            assertIs<OpaqueLwpaElement>(result.document.elements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

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
            val result = LwpaDocument.decode(segment(order, frame).toBytes(), v105, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        }

    @Test
    fun moreAnalyticSurfacesThanSurfacesRefuses() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(1u)
                    writeU32(2u)
                }
            val result = LwpaDocument.decode(segment(order, frame).toBytes(), v105, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        }

    /**
     * The v9.5 reference lists segment type 24 in its Table 3 but documents no LWPA *element* at
     * all, so the JT 9 generation carries the body opaquely with a named note.
     */
    @Test
    fun aJt9BodyIsCarriedOpaquelyBecauseNoV9ElementIsDocumented() =
        forBothOrders { order ->
            val frame =
                lwpaFrame(order) {
                    writeU8(1u)
                    writeU32(0u)
                    writeU32(0u)
                }
            val bytes = segment(order, frame)
            val result = LwpaDocument.decode(bytes.toBytes(), v9, order)
            assertEquals(listOf("ELEMENT_LAYOUT_UNVERIFIED"), result.notes.map { it.name })
            assertIs<OpaqueLwpaElement>(result.document.elements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }

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
