package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.BBoxF32
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.Vec3F32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Shape LOD segment document model (§7): synthetic tri-strip elements carrying a unit
 * tetrahedron in both wire generations (JT 9 and v10 — hand-built bytes covering the
 * complete Figure 81 chain down to the vertex records), a v10 polyline element, plus the
 * hostile paths (truncation, unknown types, cross-generation bodies, corrupt hashes), each
 * of which must carry opaquely with a named note and stay byte-identical.
 */
class ShapeLodDocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v10 = JtVersion(10, 0)
    private val order = Endianness.LITTLE_ENDIAN

    /**
     * A complete Tri-Strip Set Shape LOD element body (JT 9 wire layout, DESIGN.md): a unit
     * tetrahedron — 4 vertices, 4 triangles, no normals — with correct composite and
     * coordinate hashes. Independently verified against the reference decoding pipeline.
     */
    private val tetraBody =
        byteArrayOf(
            4, 77, 0, 0, 0, 1, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0,
            0, 1, 0, 77, 0, 0, 0, 1, 0, 3, 0, 0, 0, 0, 96, 0,
            0, 0, 3, 0, 0, 0, 3, 0, 0, 0, 3, 0, 0, 0, 1, 0,
            0, 0, 0, 32, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 3, 0, 0, 0,
            3, 0, 0, 0, 3, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0,
            0, -128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, -62, -115, 64, 80, 2, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4,
            0, 0, 0, 3, 0, 0, 0, 0, 0, 0, -128, 63, 0, 0, 0, 0,
            0, 0, 0, -128, 63, 0, 0, 0, 0, 0, 0, 0, -128, 63, 0, 4,
            0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 127, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 0, 4, 0, 0, 0,
            0, -128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 46, -127, -2, -98, 1, 0, 2, 0, 0, 0, 0,
            0, 0, 0, 1, 0,
        )

    /**
     * A complete Tri-Strip Set Shape LOD element body in the v10 wire layout (issue #6): the
     * same unit tetrahedron with correct v10 composite and coordinate hashes, the nested
     * Logical Element Header and the trailing U8 version. Independently verified against the
     * reference decoding pipeline.
     */
    private val tetraBodyV10 =
        byteArrayOf(
            4, 77, 0, 0, 0, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 108,
            1, 0, 0, -83, -91, 48, -8, 76, -66, -68, 79, -101, 95, -71, 38, -110,
            120, -46, -31, 9, 1, 0, 0, 0, 1, 77, 0, 0, 0, 1, 3, 0,
            0, 0, 0, 96, 0, 0, 0, 3, 0, 0, 0, 3, 0, 0, 0, 3,
            0, 0, 0, 1, 0, 0, 0, 0, 32, 0, 0, 0, 3, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0,
            0, 3, 0, 0, 0, 3, 0, 0, 0, 3, 0, 0, 0, 3, 0, 0,
            0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 106, 21, 122, 74, 2, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0,
            4, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, -128, 63, 0, 0, 0,
            0, 0, 0, 0, -128, 63, 0, 0, 0, 0, 0, 0, 0, -128, 63, 0,
            4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0, 0, -128,
            63, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, -128, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -128, 63, 0, 0,
            0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, -128, 63, -120, 90, -32, -24, 1,
        )

    /**
     * A complete Polyline Set Shape LOD element body (v10 wire layout, issue #6): two
     * polylines over a unit square — [0,1,2] and [2,3] — in two face groups, with correct
     * FGPV, unique-length and coordinate hashes.
     */
    private val polylineBodyV10 =
        byteArrayOf(
            4, 78, 0, 0, 0, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 14,
            1, 0, 0, 50, 45, -63, 17, -7, 56, -70, 69, -109, -70, 102, -7, -43,
            56, -35, -5, 9, 1, 0, 0, 0, 1, 78, 0, 0, 0, 1, 2, 0,
            0, 0, 2, 0, 0, 0, 5, 0, 0, 0, 3, 0, 0, 0, 0, 96,
            0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3,
            0, 0, 0, 0, 96, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0,
            5, 0, 0, 0, 5, 0, 0, 0, 0, -96, 0, 0, 0, 0, 0, 0,
            0, 1, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0,
            0, -86, -7, -128, -90, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 4, 0, 0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 1, 0,
            0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 83, -72,
            119, 64, 4, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, -128, 63, 0,
            0, 0, 0, 0, 0, 0, -128, 63, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0,
            0, -128, 63, 0, 0, -128, 63, 0, 0, 0, 0, 4, 0, 0, 0, 0,
            -128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -128, 63,
            0, 0, -128, 63, 4, 0, 0, 0, 0, -128, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -23, -126, 103,
            -79, 1,
        )

    private fun elementData(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(order).apply(build).toByteArray()

    private fun ByteWriter.writeFrame(
        typeId: Guid,
        body: ByteArray,
    ) {
        writeI32(16 + body.size)
        writeGuid(typeId)
        writeBytes(body)
    }

    private fun ByteWriter.writeEndMarker() {
        writeI32(16)
        writeGuid(Guid.END_OF_ELEMENTS)
    }

    private fun ByteWriter.writeEmptyPropertyTable() {
        writeI16(1)
        writeI32(0)
    }

    private fun roundTrip(
        bytes: ByteArray,
        version: JtVersion,
    ): ShapeLodDecodeResult {
        val result = ShapeLodDocument.decode(bytes.toBytes(), version, order)
        assertContentEquals(
            bytes,
            result.document.encode(order).toByteArray(),
            "encode(decode(elementData)) must be byte-identical",
        )
        return result
    }

    // spec: Figure 81
    @Test
    fun triStripSetElementDecodesTheTetrahedron() {
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, tetraBody)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v9)
        assertEquals(emptyList(), result.notes.map { it.name })
        val element = assertIs<TriStripSetShapeLodElement>(result.document.elements.single())
        assertEquals(77, element.objectId)
        assertEquals(0x2UL, element.vertexBindings)
        assertEquals(1, element.version)

        // spec: Figure 93 — the vertex records carry the unique coordinates exactly.
        val geometry = assertNotNull(result.document.triStripGeometry)
        assertEquals(
            listOf(Vec3F32(0f, 0f, 0f), Vec3F32(1f, 0f, 0f), Vec3F32(0f, 1f, 0f), Vec3F32(0f, 0f, 1f)),
            geometry.vertices,
        )
        assertEquals(emptyList(), geometry.normals)

        // spec: Figure 92 — the topology decoder reconstructs the four triangles.
        assertEquals(4, geometry.triangles.size)
        assertEquals(
            listOf(
                listOf(0, 1, 2),
                listOf(2, 1, 3),
                listOf(2, 3, 0),
                listOf(3, 1, 0),
            ),
            geometry.triangles.map { listOf(it.v0, it.v1, it.v2) },
        )
        assertTrue(geometry.triangles.all { it.faceGroup == 0 })
        assertTrue(geometry.triangles.all { it.n0 == -1 && it.n1 == -1 && it.n2 == -1 })

        // spec: Figure 88 — the vertex records object id of the TopoMesh LOD data.
        assertEquals(77, element.topoMesh.vertexRecordsObjectId)
        // spec: Figure 90 — the quantization parameters travel with the vertex records.
        assertEquals(0, element.repData.vertexRecords.quantizationParameters.bitsPerVertex)
        // spec: Figure 78 — the trailing empty property table of every shape segment.
        val table = assertNotNull(result.document.propertyTable)
        assertEquals(0, table.tables.size)
    }

    // spec: Figure 81
    @Test
    fun v10TriStripSetElementDecodesTheTetrahedron() {
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, tetraBodyV10)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v10)
        assertEquals(emptyList(), result.notes.map { it.name })
        val element = assertIs<TriStripSetShapeLodElementV10>(result.document.elements.single())
        assertEquals(77, element.objectId)
        assertEquals(0x2UL, element.vertexBindings)
        assertEquals(1, element.version)

        // spec: Figure 85 — the nested Logical Element Header inside Vertex Shape LOD Data.
        assertEquals(
            NestedElementHeader.TOPO_MESH_TOPOLOGICALLY_COMPRESSED_LOD_DATA_TYPE_ID,
            element.nestedHeader.objectTypeId,
        )
        assertEquals(9, element.nestedHeader.objectBaseType)

        // spec: Figure 92/93 — the same tetrahedron the JT 9 body decodes to.
        val geometry = assertNotNull(result.document.triStripGeometry)
        assertEquals(
            listOf(Vec3F32(0f, 0f, 0f), Vec3F32(1f, 0f, 0f), Vec3F32(0f, 1f, 0f), Vec3F32(0f, 0f, 1f)),
            geometry.vertices,
        )
        assertEquals(4, geometry.triangles.size)
        assertEquals(
            listOf(
                listOf(0, 1, 2),
                listOf(2, 1, 3),
                listOf(2, 3, 0),
                listOf(3, 1, 0),
            ),
            geometry.triangles.map { listOf(it.v0, it.v1, it.v2) },
        )
    }

    // spec: Figure 82
    @Test
    fun v10PolylineSetElementDecodesTheSquareOutline() {
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT, polylineBodyV10)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v10)
        assertEquals(emptyList(), result.notes.map { it.name })
        val element = assertIs<PolylineSetShapeLodElementV10>(result.document.elements.single())
        assertEquals(78, element.objectId)
        assertEquals(
            NestedElementHeader.TOPO_MESH_COMPRESSED_LOD_DATA_TYPE_ID,
            element.nestedHeader.objectTypeId,
        )

        // spec: Figure 89 — index lists slice the vertex records into polylines.
        val geometry = assertNotNull(result.document.polylineGeometry)
        assertEquals(
            listOf(Vec3F32(0f, 0f, 0f), Vec3F32(1f, 0f, 0f), Vec3F32(1f, 1f, 0f), Vec3F32(0f, 1f, 0f)),
            geometry.vertices,
        )
        assertEquals(
            listOf(
                PolylineGeometry.Polyline(listOf(0, 1, 2), 0),
                PolylineGeometry.Polyline(listOf(2, 3), 1),
            ),
            geometry.polylines,
        )
    }

    // spec: Figure 92
    @Test
    fun v10CorruptCompositeHashRefusesWithNote() {
        val corrupt = tetraBodyV10.copyOf()
        // The composite hash sits directly before the vertex records' U64 bindings.
        val hashOffset = 248
        corrupt[hashOffset] = (corrupt[hashOffset].toInt() xor 0x11).toByte()
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, corrupt)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v10)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertIs<OpaqueShapeLodElement>(result.document.elements.single())
    }

    // spec: Figure 92
    @Test
    fun corruptTopologyStreamRefusesWithNoteAndStaysByteIdentical() {
        val corrupt = tetraBody.copyOf()
        corrupt[100] = 9 // a valence word inside the CodeText: composite hash must catch it
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, corrupt)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v9)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertIs<OpaqueShapeLodElement>(result.document.elements.single())
    }

    // spec: Figure 94
    @Test
    fun nullShapeLodElementDecodesInV9() {
        // JT 9 layout per the 9.5 reference (I16 version); spec-derived, not yet fixture-verified.
        val body =
            ByteWriter(order).apply {
                writeU8(4u)
                writeI32(5)
                writeI16(1)
                writeF32(-1f)
                writeF32(-2f)
                writeF32(-3f)
                writeF32(1f)
                writeF32(2f)
                writeF32(3f)
            }.toByteArray()
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.NULL_SHAPE_LOD_ELEMENT, body)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v9)
        assertEquals(emptyList(), result.notes.map { it.name })
        val element = assertIs<NullShapeLodElement>(result.document.elements.single())
        assertEquals(BBoxF32(Vec3F32(-1f, -2f, -3f), Vec3F32(1f, 2f, 3f)), element.untransformedBBox)
    }

    // spec: Figure 80
    @Test
    fun unknownElementTypeCarriesOpaquelyWithNote() {
        val alien = Guid(0xDEADBEEFu, 0x1234u, 0x5678u, ByteArray(8) { it.toByte() }.toBytes())
        val bytes =
            elementData {
                writeFrame(alien, byteArrayOf(1, 2, 3))
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v9)
        assertEquals(listOf("UNKNOWN_ELEMENT_TYPE"), result.notes.map { it.name })
        assertIs<OpaqueShapeLodElement>(result.document.elements.single())
    }

    @Test
    fun jt9BodyUnderV10RulesRefusesWithNote() {
        // The v10 tri-strip layout (issue #6) differs from the JT 9 one; a JT 9 body read
        // under the v10 grammar must refuse to opaque with a named note, never misread.
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, tetraBody)
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v10)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertIs<OpaqueShapeLodElement>(result.document.elements.single())
    }

    @Test
    fun unestablishedV10ShapeTypesStayOpaqueWithNote() {
        // Point/polygon/primitive set layouts have no v10 fixture — carried opaquely, never
        // guessed (deferral table in DESIGN.md).
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.POINT_SET_SHAPE_LOD_ELEMENT, byteArrayOf(4, 0, 0, 0, 0, 1, 1))
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v10)
        assertEquals(listOf("ELEMENT_LAYOUT_UNVERIFIED"), result.notes.map { it.name })
        assertIs<OpaqueShapeLodElement>(result.document.elements.single())
    }

    @Test
    fun missingEndMarkerYieldsStructureNoteAndPreservesBytes() {
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.NULL_SHAPE_LOD_ELEMENT, byteArrayOf(4, 1, 0, 0, 0))
                writeBytes(byteArrayOf(1, 2, 3))
            }
        val result = roundTrip(bytes, v9)
        assertTrue(result.notes.any { it.name == "SHAPE_LOD_STRUCTURE_UNRECOGNIZED" })
    }

    @Test
    fun truncatedElementBodyRefusesWithNote() {
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, tetraBody.copyOfRange(0, 40))
                writeEndMarker()
                writeEmptyPropertyTable()
            }
        val result = roundTrip(bytes, v9)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
    }

    @Test
    fun missingPropertyTableIsNoted() {
        val bytes =
            elementData {
                writeFrame(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT, tetraBody)
                writeEndMarker()
            }
        val result = roundTrip(bytes, v9)
        assertEquals(listOf("PROPERTY_TABLE_MISSING"), result.notes.map { it.name })
    }
}
