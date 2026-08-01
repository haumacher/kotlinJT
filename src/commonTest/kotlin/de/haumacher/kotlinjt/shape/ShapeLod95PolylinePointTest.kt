package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.Vec3F32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JT 9 (9.5 Rev-D) Polyline Set and Point Set Shape LOD element bodies — 9.5 Figures 94
 * and 95 over the inherited Figures 84/85/86/87/91/92 — built here from the document rather
 * than copied from the v10 reader, because the two differ in six field-level places and the
 * two that decide correctness are invisible in a byte count:
 *
 * * the three index lists are `VecI32{Int32CDP2}` with **no** predictor (v10: `{…, Lag1}`), and
 * * `if Polyline Shape` / `if (bLineStrip)` excludes the Point Set from the face-group count,
 *   the face-group array **and** the face-group term of the FGPV hash (v10 deleted the guard).
 *
 * Each test builds a body whose stored hashes are the ones a conformant 9.5 producer would
 * write, and each is paired with the reading that must *not* be accepted.
 */
class ShapeLod95PolylinePointTest {
    private val v9 = JtVersion(9, 5)
    private val order = Endianness.LITTLE_ENDIAN

    /** A unit square: the four unique coordinates every body below carries. */
    private val square =
        listOf(Vec3F32(0f, 0f, 0f), Vec3F32(1f, 0f, 0f), Vec3F32(1f, 1f, 0f), Vec3F32(0f, 1f, 0f))

    /**
     * Five vertex-list entries, deliberately longer than the four residuals the Lag1 predictor
     * primes verbatim: read NULL they are `[0,1,2,2,3]`, read Lag1 they would be `[0,1,2,2,5]`
     * — an index outside the four vertex records. The predictor delta is therefore observable
     * in this body, not merely in its hash.
     */
    private val vertexList = listOf(0, 1, 2, 2, 3)
    private val primitiveList = listOf(0, 3, 5)
    private val faceGroupList = listOf(0, 1, 2)

    // --- wire builders (9.5 §8.1.2 Int32CDP2, null CODEC — one 32-bit word per value) ---

    private fun ByteWriter.writeNullPacket(values: List<Int>) {
        writeI32(values.size)
        if (values.isEmpty()) return
        writeU8(0u)
        writeI32(values.size * 32)
        for (value in values) writeI32(value)
    }

    /** The same packet carrying Lag1 residuals, so the reader's predictor choice is visible. */
    private fun ByteWriter.writeLag1Packet(values: List<Int>) =
        writeNullPacket(List(values.size) { if (it < 4) values[it] else values[it] - values[it - 1] })

    /** Compressed Vertex Coordinate Array, JT 9 lossless form: exponent + mantissa per component. */
    private fun ByteWriter.writeLosslessCoordinates(coordinates: List<Vec3F32>) {
        writeI32(coordinates.size)
        writeU8(3u)
        repeat(3) {
            writeF32(0f)
            writeF32(0f)
            writeU8(0u)
        }
        val components =
            listOf(
                coordinates.map { it.x.toRawBits() },
                coordinates.map { it.y.toRawBits() },
                coordinates.map { it.z.toRawBits() },
            )
        var hash = 0
        for (bits in components) {
            writeLag1Packet(bits.map { it ushr 23 })
            writeLag1Packet(bits.map { it and 0x7FFFFF })
            for (word in bits) hash = JtHash.hash32(intArrayOf(word), hash)
        }
        writeI32(hash)
    }

    private fun fgpvHash(vararg lists: List<Int>): Int {
        var hash = 0
        for (list in lists) hash = JtHash.hash32(list.toIntArray(), hash)
        return hash
    }

    /**
     * A complete JT 9 body for either element type. [polyline] drives 9.5 Figure 91's
     * `if Polyline Shape` guard over the face-group count, the face-group array and the
     * face-group term of the hash; [storedFgpvHash] overrides what a conformant producer would
     * write, so the guard can be attacked; [auxiliary] adds Figure 92's V2 extension.
     */
    private fun body(
        objectId: Int,
        polyline: Boolean,
        storedFgpvHash: Int? = null,
        auxiliary: Boolean = true,
        auxiliaryBindings: ULong = 0x2UL,
        trailing: ByteArray = ByteArray(0),
    ): ByteArray =
        ByteWriter(order).apply {
            writeU8(4u) // Object Base Type (Table 4: Shape LOD)
            writeI32(objectId)
            writeI16(1) // Fig. 83 Base Shape LOD Data version — I16 in JT 9, U8 in v10
            writeI16(1) // Fig. 85 Vertex Shape LOD Data version (no nested LEH in JT 9)
            writeU64(0x2UL) // Table 48: 3-component vertex coordinates, no normals
            writeI16(2) // Fig. 86 TopoMesh LOD Data version
            writeI32(objectId) // Fig. 86 Vertex Records Object ID
            writeI16(if (auxiliary) 2 else 1) // Fig. 87: >= 2 selects Rep Data V2
            if (polyline) writeI32(faceGroupList.size - 1)
            writeI32(primitiveList.size - 1)
            writeI32(vertexList.size)
            if (polyline) writeNullPacket(faceGroupList)
            writeNullPacket(primitiveList)
            writeNullPacket(vertexList)
            writeI32(
                storedFgpvHash
                    ?: if (polyline) fgpvHash(faceGroupList, primitiveList, vertexList) else fgpvHash(primitiveList, vertexList),
            )
            writeU64(0x2UL)
            repeat(4) { writeU8(0u) } // Quantization Parameters
            writeI32(square.size) // I32 Number of Vertex Records
            writeI32(square.size) // I32 Number of Unique Vertex Coordinates — 9.5-only
            val lengths = List(square.size) { 1 }
            writeNullPacket(lengths)
            writeI32(JtHash.hash32(lengths.toIntArray(), 0))
            writeLosslessCoordinates(square)
            if (auxiliary) {
                writeI16(1) // Fig. 92 Version Number
                writeU64(auxiliaryBindings) // Fig. 92 Vertex Bindings
            }
            writeBytes(trailing)
            writeI16(1) // the element's own version (Fig. 94 / 95) — I16 in JT 9
        }.toByteArray()

    private fun elementData(
        typeId: Guid,
        body: ByteArray,
    ): ByteArray =
        ByteWriter(order).apply {
            writeI32(16 + body.size)
            writeGuid(typeId)
            writeBytes(body)
            writeI32(16)
            writeGuid(Guid.END_OF_ELEMENTS)
            writeI16(1)
            writeI32(0)
        }.toByteArray()

    private fun roundTrip(bytes: ByteArray): ShapeLodDecodeResult {
        val result = ShapeLodDocument.decode(bytes.toBytes(), v9, order)
        assertContentEquals(bytes, result.document.encode(order).toByteArray(), "encode(decode(body)) must be byte-identical")
        return result
    }

    // spec: 9.5 Figure 94
    @Test
    fun polylineSetShapeLodDecodesTheSquareOutline() {
        val bytes = elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, body(78, polyline = true))
        val result = roundTrip(bytes)
        assertEquals(emptyList(), result.notes.map { it.name })
        val element = assertIs<PolylineSetShapeLodElement>(result.document.elements.single())
        assertEquals(78, element.objectId)
        assertEquals(1, element.baseShapeLodVersion)
        assertEquals(1, element.vertexShapeLodVersion)
        assertEquals(2, element.topoMesh.version)
        assertEquals(2, element.compressedLodVersion)
        assertEquals(1, element.version)

        // spec: 9.5 Figure 91 — the face-group section is on the wire for a polyline shape.
        val section = assertNotNull(element.repData.faceGroupSection)
        assertEquals(2, section.numberOfIndices)
        assertEquals(4, element.repData.numberOfUniqueVertexCoordinates)

        val geometry = assertNotNull(result.document.polylineGeometry)
        assertEquals(square, geometry.vertices)
        assertEquals(
            listOf(
                PolylineGeometry.Polyline(listOf(0, 1, 2), 0),
                PolylineGeometry.Polyline(listOf(2, 3), 1),
            ),
            geometry.polylines,
        )
    }

    // spec: 9.5 Figure 91

    /**
     * The delta that a copy of the v10 reader would inherit silently: 9.5 annotates the three
     * index lists `{Int32CDP2}` with no predictor, v10 `{Int32CDP, Lag1}`. Here the same bytes
     * carry `[0,1,2,2,3]` NULL and `[0,1,2,2,5]` under Lag1 — a hash mismatch *and* an index
     * past the end of the vertex records.
     */
    @Test
    fun theIndexListsAreNullPredictedNotLag1() {
        assertEquals(listOf(0, 1, 2, 2, 3), unpackResiduals(vertexList, Predictor.NONE))
        assertEquals(listOf(0, 1, 2, 2, 5), unpackResiduals(vertexList, Predictor.LAG1))

        val lag1Hash =
            fgpvHash(
                unpackResiduals(faceGroupList, Predictor.LAG1),
                unpackResiduals(primitiveList, Predictor.LAG1),
                unpackResiduals(vertexList, Predictor.LAG1),
            )
        val nullHash = fgpvHash(faceGroupList, primitiveList, vertexList)
        assertTrue(nullHash != lag1Hash, "the two predictors must be distinguishable on this body")

        // A body whose producer hashed the Lag1 reading is refused, by name, not misread.
        val bytes = elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, body(78, polyline = true, storedFgpvHash = lag1Hash))
        val result = roundTrip(bytes)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertIs<OpaqueShapeLodElement>(result.document.elements.single())
    }

    // spec: 9.5 Figure 95
    @Test
    fun pointSetShapeLodDecodesWithoutAFaceGroupSection() {
        val bytes = elementData(ObjectTypeIds.POINT_SET_SHAPE_LOD_ELEMENT, body(79, polyline = false))
        val result = roundTrip(bytes)
        assertEquals(emptyList(), result.notes.map { it.name })
        val element = assertIs<PointSetShapeLodElement>(result.document.elements.single())
        assertEquals(79, element.objectId)
        assertNull(element.repData.faceGroupSection, "9.5's `if Polyline Shape` excludes the point set")

        val geometry = assertNotNull(result.document.pointGeometry)
        assertEquals(square, geometry.vertices)
        assertEquals(listOf(0, 1, 2, 2, 3), geometry.points)
        assertNull(result.document.polylineGeometry)
    }

    // spec: 9.5 Figure 95

    /**
     * 9.5's FGPV pseudo-code guards its face-group term with `if (bLineStrip)`; v10 deleted the
     * guard. A reader that kept v10's unguarded formula computes a different hash over the very
     * same point-set bytes — and would refuse a conformant file. This pins the guard from both
     * sides: the two-term hash decodes, the three-term one does not.
     */
    @Test
    fun thePointSetFgpvHashOmitsTheFaceGroupTerm() {
        val twoTerm = fgpvHash(primitiveList, vertexList)
        val threeTerm = fgpvHash(faceGroupList, primitiveList, vertexList)
        assertTrue(twoTerm != threeTerm, "the guard must be observable on this body")

        val guarded = elementData(ObjectTypeIds.POINT_SET_SHAPE_LOD_ELEMENT, body(79, polyline = false, storedFgpvHash = twoTerm))
        assertEquals(emptyList(), roundTrip(guarded).notes.map { it.name })

        val unguarded =
            elementData(ObjectTypeIds.POINT_SET_SHAPE_LOD_ELEMENT, body(79, polyline = false, storedFgpvHash = threeTerm))
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), roundTrip(unguarded).notes.map { it.name })
    }

    // spec: 9.5 Figure 95

    /** The point set's wire layout is not the polyline's: the same bytes cannot be both. */
    @Test
    fun aPointSetBodyIsNotReadableAsAPolylineSet() {
        val pointBody = body(79, polyline = false)
        val result = roundTrip(elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, pointBody))
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        val polylineBody = body(78, polyline = true)
        val reverse = roundTrip(elementData(ObjectTypeIds.POINT_SET_SHAPE_LOD_ELEMENT, polylineBody))
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), reverse.notes.map { it.name })
    }

    // spec: 9.5 Figure 92

    /**
     * Figure 92's extension is read from the framed body's remaining length, never from the
     * container version: a body ending two bytes after the coordinate array has none, one
     * ending twelve bytes after it has the version + bindings pair. Both round-trip, and the
     * model's nullability — not a recomputation — decides what the writer emits.
     */
    @Test
    fun theAuxiliaryVertexFieldExtensionIsPresenceNotVersion() {
        val withTail = roundTrip(elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, body(78, polyline = true)))
        val present = assertIs<PolylineSetShapeLodElement>(withTail.document.elements.single())
        val auxiliary = assertNotNull(present.auxiliaryVertexFields)
        assertEquals(1, auxiliary.version)
        assertEquals(0x2UL, auxiliary.vertexBindings)
        assertTrue(!auxiliary.declaresAuxiliaryFields)

        val without =
            roundTrip(elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, body(78, polyline = true, auxiliary = false)))
        val bare = assertIs<PolylineSetShapeLodElement>(without.document.elements.single())
        assertNull(bare.auxiliaryVertexFields)
        assertEquals(1, bare.compressedLodVersion)
        assertEquals(present.geometry, bare.geometry)
    }

    // spec: 9.5 Figure 92

    /**
     * The auxiliary field list itself (Figure 92's `if auxiliary vertex field binding` branch)
     * is documented but exercised by no fixture in the corpus. A body that carries one refuses
     * by name and keeps its bytes — it is never half-decoded.
     */
    @Test
    fun anAuxiliaryFieldListRefusesByName() {
        val declared =
            body(78, polyline = true, auxiliaryBindings = 0x2UL or AuxiliaryVertexFieldData.AUXILIARY_VERTEX_FIELD_BINDING)
        val result = roundTrip(elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, declared))
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(
            result.notes.single().message.contains("auxiliary"),
            "the refusal must name the auxiliary vertex fields, got: ${result.notes.single().message}",
        )
    }

    // spec: 9.5 Figure 92

    /** A trailing run that fits neither reading is refused, never split into a guess. */
    @Test
    fun anUnaccountableTrailingRunRefuses() {
        val odd = body(78, polyline = true, auxiliary = false, trailing = byteArrayOf(1, 2, 3, 4))
        val result = roundTrip(elementData(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, odd))
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
    }

    // spec: 9.5 Figure 89

    /**
     * The lengthening that finding E-7 is about: the composite hash mixes the element count in,
     * so an all-zero chunk written as an empty packet and one written as N zeros are the same
     * derived array but *not* the same hash of the stored packets. Hashing the stored packets
     * would false-refuse the first producer.
     */
    @Test
    fun aZeroChunkAndAnElidedChunkHashDifferentlyWhenNotLengthened() {
        for (n in 1..4) {
            assertTrue(
                JtHash.hash32(IntArray(n), 0) != JtHash.hash32(IntArray(0), 0),
                "$n zeros must not hash like an empty array — that is why the chunks are lengthened",
            )
        }
    }
}
