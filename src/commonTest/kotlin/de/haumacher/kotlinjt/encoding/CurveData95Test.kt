package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.shape.Predictor
import de.haumacher.kotlinjt.shape.packResiduals
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JT 9 form of the NURBS collections — 9.5 §8.1.13 (Figure 236), §8.1.14 (Figure 237) and
 * §8.1.15 (Figure 238) with its three sub-collections. Box for box these are the same figures
 * v10 prints as §12.1.13–§12.1.15; what differs is entirely what fills the boxes, and none of it
 * is visible in the bytes:
 *
 * | | 9.5 | v10 |
 * |---|---|---|
 * | entity index / weight index predictor | `Stride1` | `Lag1` |
 * | the five per-curve vectors' predictor | `Lag1` | NULL |
 * | Int32 packet | Mk. 1 (§8.1.1) | third generation (§12.1.1) |
 * | F64 packet | `Float64CDP` (§8.1.3), natively `F64` | `Int64CDP` + bitwise reinterpretation |
 * | fifth per-curve vector | *NURBS Curve **Reserved** Fields* | *NURBS Curve **Empty** Fields* |
 *
 * These collections are reachable only from element types this library still carries opaquely
 * (the JT 9 Wireframe Rep Element and the JT B-Rep segment), so what is tested here is the
 * collection, hand-built from the figures — not a file.
 */
class CurveData95Test {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    /** A `VecI32{Int32CDP, <predictor>}` field carrying [values], as a Mk. 1 Null CODEC packet. */
    private fun ByteWriter.writeMk1Vector(
        values: List<Int>,
        predictor: Predictor,
    ) {
        writeU8(0u)
        writeVecU32(packResiduals(values, predictor))
    }

    /** A `VecF64{Float64CDP, NULL}` field carrying [values], as a Null CODEC packet. */
    private fun ByteWriter.writeFloat64Vector(values: List<Double>) {
        writeU8(0u)
        writeVecU32(
            values.flatMap { value ->
                val bits = value.toRawBits()
                listOf((bits and 0xFFFFFFFFL).toInt(), (bits ushr 32).toInt())
            },
        )
    }

    private val curveCount = 5
    private val degrees = List(curveCount) { 1 }
    private val pointCounts = List(curveCount) { 2 }
    private val coordinates = List(curveCount * 2 * 3) { it * 0.25 }
    private val knotValues = listOf(0.0, 1.0)

    /** Curve 2 has a non-trivial knot vector of Table 68 category 1 (even count, `[x1:x2]`). */
    private fun curveDataBytes(): ByteArray =
        bytesOf {
            // §8.1.15.1 -> §8.1.13: the four exist flags, then one index vector per set flag.
            writeI32(4)
            writeI32(0)
            writeI32(1)
            writeI32(0)
            writeI32(0)
            writeMk1Vector(listOf(2), Predictor.STRIDE1)
            // The five per-curve vectors, Lag1-predicted.
            writeMk1Vector(List(curveCount) { CompressedCurveData.CURVE_BASE_TYPE_NURBS }, Predictor.LAG1)
            writeMk1Vector(degrees, Predictor.LAG1)
            writeMk1Vector(pointCounts, Predictor.LAG1)
            writeMk1Vector(List(curveCount) { 3 }, Predictor.LAG1)
            writeMk1Vector(List(curveCount) { 0 }, Predictor.LAG1)
            // §8.1.15.2 -> §8.1.14: no rational curve, so no weight is stored.
            writeI32(0)
            writeMk1Vector(emptyList(), Predictor.STRIDE1)
            writeFloat64Vector(emptyList())
            // §8.1.15.3 control points, then the knot vectors.
            writeFloat64Vector(coordinates)
            writeFloat64Vector(knotValues)
        }

    // spec: 9.5 Figure 236, Figure 237, Figure 238
    @Test
    fun theJt9CurveCollectionDecodesTypedAndReEncodesByteIdentically() {
        val bytes = curveDataBytes()
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val data = CompressedCurveData.read95(reader, curveCount)
        assertEquals(bytes.size, reader.position, "the collection must consume exactly its bytes")

        assertEquals(curveCount, data.curveCount)
        assertEquals(degrees, data.degrees.values)
        assertEquals(pointCounts, data.controlPointCounts.values)
        assertEquals(coordinates, data.controlPoints.values)
        assertEquals(knotValues, data.knotVectors.values)
        assertEquals(listOf(2), data.nonTrivialKnotVectors.entityIndices.single().indices.values)
        assertEquals(KnotType.EVEN_COUNT_ARBITRARY_RANGE, data.nonTrivialKnotVectors.entityIndices.single().knotType)

        // The per-curve projection: only curve 2 carries stored knots, everyone's weights are 1.
        assertEquals(listOf(null, null, KnotType.EVEN_COUNT_ARBITRARY_RANGE, null, null), data.curves.map { it.knotType })
        assertEquals(knotValues, data.curves[2].storedKnotValues)
        assertTrue(data.curves.all { it.weights == listOf(1.0, 1.0) })
        assertEquals(coordinates.take(6), data.curves[0].coordinates)

        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        data.encode(writer)
        assertContentEquals(bytes, writer.toByteArray(), "encode(decode(collection)) must be byte-identical")
    }

    /**
     * The predictor is part of the field's identity, not a detail: the same bytes read with v10's
     * NULL predictor would give a different value list. Here the five per-curve vectors are
     * `Lag1`, so the wire holds residuals — and reading the residuals raw is visibly wrong.
     *
     * spec: 9.5 Figure 238
     */
    @Test
    fun theFivePerCurveVectorsAreLag1PredictedOnTheWire() {
        // Five identical base types encode as 1,1,1,1,0 under Lag1: the fifth is a residual.
        val reader = ByteReader(curveDataBytes(), Endianness.LITTLE_ENDIAN)
        val data = CompressedCurveData.read95(reader, curveCount)
        assertEquals(List(curveCount) { 1 }, data.curveBaseTypes.values)
        val raw = (data.curveBaseTypes as Int32VectorMk1).packet.values
        assertEquals(listOf(1, 1, 1, 1, 0), raw, "the wire carries residuals; the predictor is what makes them values")
    }

    /**
     * The cross-generation hazard for a whole collection rather than a single packet: 9.5 and
     * v10 draw §8.1.15 / §12.1.15 identically, so a reader that picks the wrong generation walks
     * the same boxes with the wrong codecs. It must fail rather than produce a curve list.
     *
     * spec: 9.5 Figure 238, v10 Figure 150
     */
    @Test
    fun theV10ReaderRefusesTheJt9Collection() {
        assertFailsWith<JtFormatException> {
            CompressedCurveData.read(ByteReader(curveDataBytes(), Endianness.LITTLE_ENDIAN), curveCount, false)
        }
    }

    // spec: 9.5 Figure 238
    @Test
    fun aStoredCountThatDoesNotAddUpIsRefused() {
        val bytes =
            bytesOf {
                writeI32(4)
                repeat(4) { writeI32(0) }
                writeMk1Vector(List(curveCount) { CompressedCurveData.CURVE_BASE_TYPE_NURBS }, Predictor.LAG1)
                writeMk1Vector(degrees, Predictor.LAG1)
                writeMk1Vector(pointCounts, Predictor.LAG1)
                writeMk1Vector(List(curveCount) { 3 }, Predictor.LAG1)
                writeMk1Vector(List(curveCount) { 0 }, Predictor.LAG1)
                writeI32(0)
                writeMk1Vector(emptyList(), Predictor.STRIDE1)
                writeFloat64Vector(emptyList())
                // One coordinate short of the 30 the control point counts predict.
                writeFloat64Vector(coordinates.dropLast(1))
                writeFloat64Vector(emptyList())
            }
        val failure =
            assertFailsWith<JtFormatException> {
                CompressedCurveData.read95(ByteReader(bytes, Endianness.LITTLE_ENDIAN), curveCount)
            }
        assertTrue(failure.message!!.contains("Control Points"), failure.message)
    }
}
