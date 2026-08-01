package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guarded `U64` vertex-binding fields of 9.5 Figures 30, 33 and 34, and the doctrine that
 * governs them (`DESIGN.md`, *Lenient when reading, strict when writing*).
 *
 * Each figure draws the field inside a box labelled `Version Number == 1`. §9.4 makes local
 * versions append-only — a version-1 reader must be able to read the version-1 prefix of a
 * version-2 body — so the guard names the version the field *belongs to*, and the field is on
 * the wire from that version upwards. Rather than hard-code any version test, the reader
 * resolves presence from the element body's remaining length; these tests pin every branch of
 * that resolution, and pin that the writer emits exactly what the model holds.
 */
class Lsg95ShapeNodeGuardTest {
    private fun polylineFrame(
        order: Endianness,
        generation: LsgGeneration,
        version: Int = 2,
        vertexShapeGuarded: ULong?,
        nodeGuarded: ULong?,
    ): ByteArray =
        lsgFrame(order, ObjectTypeIds.POLYLINE_SET_SHAPE_NODE, 2, 25) {
            writeTestVertexShapeData(generation, guardedBindings = vertexShapeGuarded)
            writeTestVersionNumber(generation, version)
            writeF32(0f)
            nodeGuarded?.let { writeU64(it) }
        }

    private fun pointSetFrame(
        order: Endianness,
        generation: LsgGeneration,
        version: Int = 2,
        vertexShapeGuarded: ULong?,
        nodeGuarded: ULong?,
    ): ByteArray =
        lsgFrame(order, ObjectTypeIds.POINT_SET_SHAPE_NODE, 2, 30) {
            writeTestVertexShapeData(generation, guardedBindings = vertexShapeGuarded)
            writeTestVersionNumber(generation, version)
            writeF32(0f)
            nodeGuarded?.let { writeU64(it) }
        }

    // spec: 9.5 Figure 30
    // spec: 9.5 Figure 33

    /**
     * The lenient read of the producer's bytes: the shape of every Polyline Set node the
     * NetAllied JT 9.5 writer emits — local version 2 with both guarded fields present, which
     * the append-only rule makes the *conformant* encoding.
     */
    @Test
    fun polylineSetReadsBothGuardedFieldsAtVersionTwo() =
        forBothOrders { order ->
            val bytes = polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = 2u, nodeGuarded = 0u)
            val element = roundTripTyped(bytes, order, LsgGeneration.V9) as PolylineSetShapeNodeElement
            assertEquals(2, element.version)
            assertEquals(0f, element.areaFactor)
            assertEquals(2uL, element.vertexShape.vertexBindings2)
            assertEquals(0uL, element.vertexBindings)
        }

    // spec: 9.5 Figure 33

    /** The other lenient branch: a producer that reads `== 1` literally and omits both fields. */
    @Test
    fun polylineSetReadsTheEncodingThatOmitsBothGuardedFields() =
        forBothOrders { order ->
            val bytes = polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = null, nodeGuarded = null)
            val element = roundTripTyped(bytes, order, LsgGeneration.V9) as PolylineSetShapeNodeElement
            assertNull(element.vertexShape.vertexBindings2)
            assertNull(element.vertexBindings)
        }

    // spec: 9.5 Figure 30
    // spec: 9.5 Figure 33

    /**
     * The two mixed encodings are the same body length; the Version Number at the candidate
     * offset decides which of the two 8-byte fields is on the wire.
     */
    @Test
    fun polylineSetResolvesTheMixedEncodingsByTheVersionNumber() =
        forBothOrders { order ->
            val onlyShapeData = polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = 7u, nodeGuarded = null)
            val onlyNode = polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = null, nodeGuarded = 9u)
            assertEquals(onlyShapeData.size, onlyNode.size, "the mixed encodings must be indistinguishable by length")

            val first = roundTripTyped(onlyShapeData, order, LsgGeneration.V9) as PolylineSetShapeNodeElement
            assertEquals(7uL, first.vertexShape.vertexBindings2)
            assertNull(first.vertexBindings)

            val second = roundTripTyped(onlyNode, order, LsgGeneration.V9) as PolylineSetShapeNodeElement
            assertNull(second.vertexShape.vertexBindings2)
            assertEquals(9uL, second.vertexBindings)
        }

    // spec: 9.5 Figure 33

    /**
     * Where the length fits two readings and the version numbers do not discriminate them,
     * the reader refuses instead of guessing: a named note, the bytes carried opaquely.
     * Leniency stops where the evidence does.
     */
    @Test
    fun polylineSetRefusesAnUndecidableMixedEncoding() =
        forBothOrders { order ->
            // This guarded value reads `1` as an I16 at its own front in either byte order,
            // so a plausible Version Number sits at both candidate offsets.
            val bytes =
                polylineFrame(order, LsgGeneration.V9, version = 1, vertexShapeGuarded = 0x0001_0000_0000_0001uL, nodeGuarded = null)
            val (element, notes) = decodeSingle(bytes, order, LsgGeneration.V9)
            assertTrue(element is OpaqueLsgElement, "an undecidable body must not decode typed")
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), notes.map { it.name })
            assertContentEquals(bytes, encodeSingle(element, order, LsgGeneration.V9), "opaque bytes must survive")
        }

    // spec: 9.5 Figure 30
    // spec: 9.5 Figure 33

    /**
     * The strict write: presence is a model fact. A document-conformant body that omits the
     * guarded fields must not gain sixteen invented bytes on re-serialization — the writer
     * used to re-derive both from the version number.
     */
    @Test
    fun polylineSetWriterEmitsExactlyWhatTheModelHolds() =
        forBothOrders { order ->
            val absent =
                PolylineSetShapeNodeElement(
                    25,
                    testVertexShapeData(LsgGeneration.V9, version = 2, guardedBindings = null),
                    2,
                    0f,
                    null,
                )
            assertContentEquals(
                polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = null, nodeGuarded = null),
                encodeSingle(absent, order, LsgGeneration.V9),
                "a model without the guarded fields must serialize without them",
            )

            val present = absent.copy(vertexShape = testVertexShapeData(LsgGeneration.V9, guardedBindings = 2u), vertexBindings = 0u)
            assertContentEquals(
                polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = 2u, nodeGuarded = 0u),
                encodeSingle(present, order, LsgGeneration.V9),
                "a model holding the guarded fields must serialize with them",
            )
        }

    // spec: 9.5 Figure 34
    @Test
    fun pointSetReadsBothGuardedFieldsAtVersionTwo() =
        forBothOrders { order ->
            val bytes = pointSetFrame(order, LsgGeneration.V9, vertexShapeGuarded = 2u, nodeGuarded = 0u)
            val element = roundTripTyped(bytes, order, LsgGeneration.V9) as PointSetShapeNodeElement
            assertEquals(2uL, element.vertexShape.vertexBindings2)
            assertEquals(0uL, element.vertexBindings)
        }

    // spec: 9.5 Figure 34
    @Test
    fun pointSetReadsTheEncodingThatOmitsBothGuardedFields() =
        forBothOrders { order ->
            val bytes = pointSetFrame(order, LsgGeneration.V9, vertexShapeGuarded = null, nodeGuarded = null)
            val element = roundTripTyped(bytes, order, LsgGeneration.V9) as PointSetShapeNodeElement
            assertNull(element.vertexShape.vertexBindings2)
            assertNull(element.vertexBindings)
        }

    // spec: 9.5 Figure 34
    @Test
    fun pointSetWriterEmitsExactlyWhatTheModelHolds() =
        forBothOrders { order ->
            val absent =
                PointSetShapeNodeElement(
                    30,
                    testVertexShapeData(LsgGeneration.V9, version = 2, guardedBindings = null),
                    2,
                    0f,
                    null,
                )
            assertContentEquals(
                pointSetFrame(order, LsgGeneration.V9, vertexShapeGuarded = null, nodeGuarded = null),
                encodeSingle(absent, order, LsgGeneration.V9),
            )
        }

    // spec: 9.5 Figure 34

    /**
     * The `>= N` widening on the JT 10 path, which v10 Figure 41 guards the same way: a
     * version-2 Point Set body that carries the field used to leave eight bytes unconsumed.
     */
    @Test
    fun v10PointSetCarriesTheGuardedFieldAboveVersionOne() =
        forBothOrders { order ->
            for (generation in listOf(LsgGeneration.V10, LsgGeneration.V10_5)) {
                val withField = pointSetFrame(order, generation, version = 2, vertexShapeGuarded = null, nodeGuarded = 0x5u)
                val element = roundTripTyped(withField, order, generation) as PointSetShapeNodeElement
                assertEquals(2, element.version)
                assertEquals(0x5uL, element.vertexBindings)

                val withoutField = pointSetFrame(order, generation, version = 2, vertexShapeGuarded = null, nodeGuarded = null)
                val omitted = roundTripTyped(withoutField, order, generation) as PointSetShapeNodeElement
                assertNull(omitted.vertexBindings)
            }
        }

    // spec: 9.5 Figure 30
    // spec: 9.5 Figure 32

    /**
     * Vertex Shape Data ends the Tri-Strip Set body, so its guarded field is decided by 0 vs 8
     * remaining bytes outright — and the writer follows the model, not the version.
     */
    @Test
    fun triStripSetResolvesTheGuardedFieldFromTheBodyLength() =
        forBothOrders { order ->
            for (guarded in listOf(null, 0uL)) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE, 2, 40) {
                        writeTestVertexShapeData(LsgGeneration.V9, guardedBindings = guarded)
                    }
                val element = roundTripTyped(bytes, order, LsgGeneration.V9) as TriStripSetShapeNodeElement
                assertEquals(guarded, element.vertexShape.vertexBindings2)

                val model = TriStripSetShapeNodeElement(40, testVertexShapeData(LsgGeneration.V9, guardedBindings = guarded))
                assertContentEquals(bytes, encodeSingle(model, order, LsgGeneration.V9), "the writer must follow the model")
            }
        }

    // spec: 9.5 Figure 30

    /** The same for the Polygon Set node, whose body also ends with Vertex Shape Data. */
    @Test
    fun polygonSetResolvesTheGuardedFieldFromTheBodyLength() =
        forBothOrders { order ->
            for (guarded in listOf(null, 4uL)) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.POLYGON_SET_SHAPE_NODE, 2, 41) {
                        writeTestVertexShapeData(LsgGeneration.V9, guardedBindings = guarded)
                    }
                val element = roundTripTyped(bytes, order, LsgGeneration.V9) as PolygonSetShapeNodeElement
                assertEquals(guarded, element.vertexShape.vertexBindings2)
            }
        }

    // spec: 9.5 Figure 33

    /** A body eight bytes longer than any legal reading is still a named refusal, never a guess. */
    @Test
    fun aBodyNoReadingExplainsIsRefusedByName() =
        forBothOrders { order ->
            val frame = polylineFrame(order, LsgGeneration.V9, vertexShapeGuarded = 2u, nodeGuarded = 0u)
            // Grow the declared frame length by four bytes of surplus body: 26 trailing bytes
            // fit none of the 6/14/22 readings, so the append-only fallback under-consumes and
            // the frame's strict length check names the failure.
            val grown = ByteWriter(order)
            grown.writeI32(frame.size)
            grown.writeBytes(frame.copyOfRange(4, frame.size))
            grown.writeI32(0)
            val (element, notes) = decodeSingle(grown.toByteArray(), order, LsgGeneration.V9)
            assertTrue(element is OpaqueLsgElement)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), notes.map { it.name })
        }
}
