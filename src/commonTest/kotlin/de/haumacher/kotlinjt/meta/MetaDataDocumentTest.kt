package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.f32
import de.haumacher.kotlinjt.lsg.forBothOrders
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The §11 per-figure contract: hand-built bytes following each spec figure decode to the typed
 * model with no notes and serialize back byte-identically. Every layout claim is either
 * fixture-verified (see MetaDataFixtureTest, which drives the same decoders over the NIST
 * 10.5 file's 44 meta data / PMI segments) or explicitly marked spec-derived here.
 */
class MetaDataDocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v10 = JtVersion(10, 0)
    private val v105 = JtVersion(10, 5)

    /** Object Base Type 9 ("JtBase", Table 7) — what every §11 element body starts with. */
    private val baseTypeJtBase = 9

    private fun metaFrame(
        order: Endianness,
        typeId: Guid,
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

    private fun endOfElements(order: Endianness): ByteArray {
        val writer = ByteWriter(order)
        writer.writeI32(16)
        writer.writeGuid(Guid.END_OF_ELEMENTS)
        return writer.toByteArray()
    }

    /** A null-CODEC Int32CDP: count, codec 0, CodeText length, then the values as words. */
    private fun ByteWriter.writeNullInt32Packet(values: List<Int>) {
        writeI32(values.size)
        writeU8(0u)
        writeI32(32 * values.size)
        for (value in values) writeI32(value)
    }

    /** The Figure-78 empty Property Table every real producer writes after its elements. */
    private fun emptyPropertyTable(order: Endianness): ByteArray {
        val writer = ByteWriter(order)
        writer.writeI16(1)
        writer.writeI32(0)
        return writer.toByteArray()
    }

    private fun segment(
        order: Endianness,
        vararg frames: ByteArray,
    ): ByteArray {
        val writer = ByteWriter(order)
        for (frame in frames) writer.writeBytes(frame)
        writer.writeBytes(endOfElements(order))
        writer.writeBytes(emptyPropertyTable(order))
        return writer.toByteArray()
    }

    private fun decode(
        bytes: ByteArray,
        order: Endianness,
        version: JtVersion,
    ): MetaDataDecodeResult = MetaDataDocument.decode(bytes.toBytes(), version, order)

    /** The standard assertion: decodes note-free and re-encodes byte-identically. */
    private fun roundTrip(
        bytes: ByteArray,
        order: Endianness,
        version: JtVersion,
    ): MetaDataDocument {
        val result = decode(bytes, order, version)
        assertEquals(emptyList(), result.notes, "typed decode must be note-free")
        assertContentEquals(bytes, result.document.encode(order).toByteArray(), "encode(decode(bytes)) drifted")
        return result.document
    }

    // ------------------------------------------------------------------
    // Property Proxy Meta Data Element
    // ------------------------------------------------------------------

    // spec: Figure 108, Figure 109, Table 53
    @Test
    fun propertyProxyCarriesEveryTable53ValueType() {
        forBothOrders { order ->
            for (version in listOf(v9, v10, v105)) {
                val generation = LsgGeneration.of(version)
                val bytes =
                    segment(
                        order,
                        metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT, objectId = 7) {
                            if (generation == LsgGeneration.V9) writeI16(1) else writeU8(1u)
                            writeMbString("Unknown::")
                            writeU8(0u)
                            writeMbString("Name::")
                            writeU8(1u)
                            writeMbString("bracket")
                            writeMbString("LAYER::")
                            writeU8(2u)
                            writeI32(-42)
                            writeMbString("CAD_DENSITY::")
                            writeU8(3u)
                            writeF32(7.85e-6f)
                            writeMbString("Created::")
                            writeU8(4u)
                            writeI16(2026)
                            writeI16(8)
                            writeI16(1)
                            writeI16(13)
                            writeI16(9)
                            writeI16(47)
                            writeMbString("")
                        },
                    )
                val document = roundTrip(bytes, order, version)
                val proxy = assertIs<PropertyProxyMetaDataElement>(document.elements.single())
                assertEquals(7, proxy.objectId)
                assertEquals(1, proxy.version)
                assertTrue(proxy.terminated)
                assertEquals(
                    listOf("Unknown::", "Name::", "LAYER::", "CAD_DENSITY::", "Created::"),
                    proxy.properties.map { it.key },
                )
                assertEquals(MetaPropertyValue.None, proxy.properties[0].value)
                assertEquals(MetaPropertyValue.Text("bracket"), proxy.properties[1].value)
                assertEquals(MetaPropertyValue.Integer(-42), proxy.properties[2].value)
                assertEquals(MetaPropertyValue.Real(f32(7.85e-6f)), proxy.properties[3].value)
                assertEquals(MetaPropertyValue.Date(JtDate(2026, 8, 1, 13, 9, 47)), proxy.properties[4].value)
                assertEquals(
                    MetaPropertyValue.Text("bracket"),
                    proxy.propertyMap["Name::"],
                    "propertyMap projects the bag for lookup",
                )
                assertEquals(0, document.propertyTable?.tables?.size)
            }
        }
    }

    // spec: Figure 108
    @Test
    fun duplicateBagKeysArePreservedInOrder() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                    writeU8(1u)
                    writeMbString("k")
                    writeU8(1u)
                    writeMbString("first")
                    writeMbString("k")
                    writeU8(1u)
                    writeMbString("second")
                    writeMbString("")
                },
            )
        val proxy = assertIs<PropertyProxyMetaDataElement>(roundTrip(bytes, order, v105).elements.single())
        assertEquals(
            listOf(MetaPropertyValue.Text("first"), MetaPropertyValue.Text("second")),
            proxy.properties.map { it.value },
            "the wire order of duplicate keys is preserved",
        )
        assertEquals(MetaPropertyValue.Text("first"), proxy.propertyMap["k"], "the lookup projection takes the first")
    }

    // spec: Table 53
    @Test
    fun unknownPropertyValueTypeKeepsTheDecodedPrefixAndTheRawRemainder() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                    writeU8(1u)
                    writeMbString("Name::")
                    writeU8(1u)
                    writeMbString("ok")
                    writeMbString("Alien::")
                    writeU8(9u) // outside Table 53: length unknown
                    writeI32(0x0BADF00D)
                    writeMbString("")
                },
            )
        val result = decode(bytes, order, v105)
        assertEquals(listOf("META_PROPERTY_VALUE_TYPE_UNKNOWN"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("Alien::"))
        val proxy = assertIs<PropertyProxyMetaDataElement>(result.document.elements.single())
        assertEquals(MetaPropertyValue.Text("ok"), proxy.properties[0].value, "keys before the alien type survive")
        val unknown = assertIs<MetaPropertyValue.Unrecognized>(proxy.properties[1].value)
        assertEquals(9, unknown.typeCode)
        assertEquals(8, unknown.remainder.size, "the I32 value plus the bag terminator are preserved verbatim")
        assertTrue(!proxy.terminated, "the bag never reached its terminator")
        assertContentEquals(
            bytes,
            result.document.encode(order).toByteArray(),
            "a named partial carry still re-serializes byte-identically",
        )
    }

    // ------------------------------------------------------------------
    // PMI Manager Meta Data Element
    // ------------------------------------------------------------------

    /**
     * A complete PMI Manager body exercising every Figure 110 sub-collection, written by hand
     * in the figure's field order. [tail] appends the undocumented block NX 10.5 writes.
     */
    private fun pmiManagerBody(
        order: Endianness,
        generation: LsgGeneration,
        tail: ByteArray = ByteArray(0),
    ): ByteArray =
        metaFrame(order, ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT, objectId = 3) {
            writeU8(2u) // Version Number
            writeI16(-1) // Empty Field
            // --- PMI Design Group Entities (Figure 111) with all three Table 54 attributes
            writeI32(1)
            writeI32(0) // group name string id -> "top"
            writeI32(3)
            writeI32(1)
            writeI32(17)
            writeI32(1)
            writeI32(2) // integer attribute
            writeI32(2)
            writeF64(2.5)
            writeI32(1)
            writeI32(-1) // double attribute
            writeI32(3)
            writeI32(2)
            writeI32(1)
            writeI32(2) // string attribute
            // --- PMI Associations (Figure 113)
            writeI32(1)
            writeI32(0x92000001.toInt())
            writeI32(-1)
            writeI32(10)
            writeI32(0x91000008.toInt())
            writeI32(1)
            // --- PMI User Attributes (Figure 114)
            writeI32(1)
            writeI32(1)
            writeI32(2)
            // --- PMI String Table (Figure 115)
            writeI32(3)
            writeMbString("top")
            writeMbString("MVStyle")
            writeMbString("PMI")
            // --- PMI Model Views (Figure 116)
            writeI32(1)
            writeF32(0f)
            writeF32(0f)
            writeF32(-1f) // eye direction
            writeF32(0f) // angle
            writeF32(0f)
            writeF32(0f)
            writeF32(347.5f) // eye position
            writeF32(0f)
            writeF32(0f)
            writeF32(0f) // target point
            writeF32(0f)
            writeF32(0f)
            writeF32(0f) // view angle
            writeF32(12.5f) // viewport diameter
            writeF32(0f) // F32 empty field
            writeI32(0) // I32 empty field
            writeI32(1) // active flag
            writeI32(23) // view id
            writeI32(0) // view name string id
            writeI32(1) // property count
            writePmiPropertyAtom(generation, "modelViewStyle", 0)
            writePmiPropertyAtom(generation, "ShadedWithEdges", 1)
            // --- Generic PMI Entities (Figure 119)
            writeI32(1)
            // PMI 2D Data -> PMI Base Data (Figure 121) with a stored 2D-Reference Frame
            writeI32(4) // user label
            writeU8(2u) // 2D-frame flag
            writeF32(1f)
            writeF32(2f)
            writeF32(3f) // frame origin
            writeF32(4f)
            writeF32(5f)
            writeF32(6f) // frame x-axis point
            writeF32(7f)
            writeF32(8f)
            writeF32(9f) // frame y-axis point
            writeF32(4.889f) // text height
            writeU8(1u) // symbol valid flag
            writeI32(1) // text entity count
            // 2D Text Data (Figure 123)
            writeI32(2) // string id
            writeI32(-1) // font
            writeI32(0) // I32 empty field
            writeF32(0f) // F32 empty field
            writeF32(-1f)
            writeF32(-2f)
            writeF32(3f)
            writeF32(4f)
            writeF32(5f)
            writeF32(6f) // text box (Figure 124)
            writeI32(2) // Text Polyline Data (Figure 126): index count
            writeI16(0)
            writeI16(2)
            writeI32(4)
            writeF32(1f)
            writeF32(2f)
            writeF32(3f)
            writeF32(4f)
            // Non-Text Polyline Data (Figure 128)
            writeI32(2)
            writeI32(0)
            writeI32(3)
            writeI32(1)
            writeI16(4)
            writeI32(1)
            writeI16(2)
            writeI32(6)
            repeat(6) { writeF32(it.toFloat()) }
            writeI32(1) // property count
            writePmiPropertyAtom(generation, "style", 0)
            writePmiPropertyAtom(generation, "ShadedWithEdges", 0)
            writeI32(1) // entity type name string id -> "MVStyle"
            writeI32(2) // parent type name string id -> "PMI"
            writeU16(0x0310u) // entity type (a value Table 60 omits — carried, not validated)
            writeU16(0x0001u) // parent type
            writeU16(0u) // user flags
            // --- PMI Polygon Data (Figure 130): one empty and one populated element
            writePmiPolygonData()
            // --- CAD Tags Flag + PMI CAD Tag Data (Figure 129)
            writeU32(1u)
            writeI32(3) // design groups (1) + model views (1) + generic entities (1)
            writeI32(0)
            writeI32(1)
            writeI32(2)
            // Compressed CAD Tag Data (Figure 154): three 32-bit tags, null CODEC throughout.
            // Both tag vectors are always written — the Type-2 one as an empty packet.
            writeU8(1u) // Compressed CAD Tag Data version
            writeI32(8 + 21 + 21 + 4) // data length: this field, the inner version, the vectors
            writeI32(1) // inner version
            writeNullInt32Packet(listOf(1, 1, 1)) // CAD Tag Types (Table 72: 32-bit)
            writeNullInt32Packet(listOf(4711, 4712, 4713)) // CAD Tags Type-1
            writeI32(0) // CAD Tags Type-2: the empty packet
            // --- Fonts
            writeI32(1)
            writeMbString("glyphs-1")
            writeI32(2)
            writeU16(65u)
            writeU16(66u)
            writePmiPolygonData()
            writeBytes(tail)
        }

    private fun ByteWriter.writePmiPropertyAtom(
        generation: LsgGeneration,
        value: String,
        hidden: Int,
    ) {
        writeMbString(value)
        if (generation == LsgGeneration.V10_5) writeU8(hidden.toUByte()) else writeU32(hidden.toUInt())
    }

    /** PMI Polygon Data (Figure 130): two elements, the first empty, the second with normals. */
    private fun ByteWriter.writePmiPolygonData() {
        writeU8(1u) // version
        writeI32(2) // PolygonData element count
        writeI32(2)
        writeI32(0)
        writeI32(4) // vNumVerts = [0, 4] — the first element is empty
        writeI32(3)
        writeI32(0)
        writeI32(1)
        writeI32(0) // vBindings: colour, normal, texture of the one non-empty element
        writeI32(1)
        writeI32(2) // vPolygonDimensions
        // The one non-empty element's arrays
        writeI32(2)
        writeI32(0)
        writeI32(8) // PrimTypes
        writeI32(2)
        writeI32(0)
        writeI32(4) // PrimIndices
        writeI32(4)
        writeI32(0)
        writeI32(1)
        writeI32(2)
        writeI32(3) // VertIndices
        writeI32(8)
        repeat(8) { writeF32(it.toFloat()) } // Vertices
        writeI32(8)
        repeat(8) { writeF32(-it.toFloat()) } // Normals (normalBinding == 1)
    }

    // spec: Figure 110, Figures 111-131
    @Test
    fun pmiManagerDecodesEverySubCollectionAndRoundTrips() {
        forBothOrders { order ->
            for (version in listOf(v10, v105)) {
                val generation = LsgGeneration.of(version)
                val bytes = segment(order, pmiManagerBody(order, generation))
                val document = roundTrip(bytes, order, version)
                val pmi = assertIs<PmiManagerMetaDataElement>(document.elements.single())
                assertEquals(3, pmi.objectId)
                assertEquals(2, pmi.version)
                assertEquals(-1, pmi.emptyField)
                assertEquals(listOf("top", "MVStyle", "PMI"), pmi.stringTable)

                // Figure 111/112: one group with an integer, a double and a string attribute.
                val group = pmi.designGroups.single()
                assertEquals("top", pmi.string(group.nameStringId))
                assertEquals(
                    listOf(
                        PmiDesignGroupAttributeValue.Integer(17),
                        PmiDesignGroupAttributeValue.Double(2.5),
                        PmiDesignGroupAttributeValue.StringId(2),
                    ),
                    group.attributes.map { it.value },
                )

                // Figure 113 / Table 55: the packed source and destination words.
                val association = pmi.associations.single()
                assertEquals(1, association.sourceEntityId)
                assertEquals(0x12, association.sourceEntityType)
                assertTrue(association.sourceIndirect)
                assertEquals(10, association.reasonCode)
                assertEquals(8, association.destinationEntityId)
                assertEquals(0x11, association.destinationEntityType)

                assertEquals(PmiUserAttribute(1, 2), pmi.userAttributes.single())

                // Figure 116 + Figure 117/118.
                val view = pmi.modelViews.single()
                assertEquals(23, view.viewId)
                assertEquals(1, view.activeFlag)
                assertEquals("top", pmi.string(view.viewNameStringId))
                assertEquals(f32(347.5f), view.eyePosition.z)
                assertEquals(
                    PmiProperty(PmiPropertyAtom("modelViewStyle", 0), PmiPropertyAtom("ShadedWithEdges", 1)),
                    view.properties.single(),
                )

                // Figure 119-128.
                val entity = pmi.genericEntities.single()
                assertEquals(0x0310, entity.entityType)
                assertEquals("MVStyle", pmi.string(entity.entityTypeNameStringId))
                assertEquals("PMI", pmi.string(entity.parentTypeNameStringId))
                assertEquals(2, entity.data2d.base.frameFlag)
                assertEquals(f32(1f), entity.data2d.base.referenceFrame?.origin?.x)
                val text = entity.data2d.texts.single()
                assertEquals("PMI", pmi.string(text.stringId))
                assertEquals(listOf<Short>(0, 2), text.polylines.segmentIndices)
                assertEquals(4, text.polylines.vertexCoords?.size)
                assertEquals(listOf(0, 3), entity.data2d.nonTextPolylines.segmentIndices)
                assertEquals(listOf<Short>(4), entity.data2d.nonTextPolylines.types)
                assertEquals(6, entity.data2d.nonTextPolylines.vertexCoords.size)

                // Figure 130: the empty element contributes no per-element arrays.
                assertEquals(listOf(0, 4), pmi.polygonData.vertexCounts)
                val polygonElement = pmi.polygonData.elements.single()
                assertEquals(4, polygonElement.vertexCount)
                assertEquals(1, polygonElement.normalBinding)
                assertEquals(8, polygonElement.normals?.size)
                assertNull(polygonElement.colours)
                assertNull(polygonElement.textureCoords)

                // Figure 129 + Figure 154.
                assertEquals(1, pmi.cadTagsFlag)
                assertEquals(listOf(0, 1, 2), pmi.cadTagData?.indices)
                assertEquals(listOf(1, 1, 1), pmi.cadTagData?.compressed?.tags?.tagTypes?.values)
                assertEquals(listOf(4711L, 4712L, 4713L), pmi.cadTagData?.compressed?.tags?.tags)
                assertEquals(0, pmi.cadTagData?.compressed?.codedData?.size)

                val font = pmi.fonts.single()
                assertEquals("glyphs-1", font.name)
                assertEquals(listOf(65, 66), font.characterSet)
                assertEquals(1, font.glyphs.elements.size)

                assertEquals(0, pmi.undocumentedTail.size, "a Figure-110-complete body has no tail")
            }
        }
    }

    // spec: Figure 110
    @Test
    fun undocumentedBytesAfterTheFontBlockAreNamedAndPreserved() {
        val order = Endianness.LITTLE_ENDIAN
        val tail = ByteArray(16) { (it * 3).toByte() }
        val bytes = segment(order, pmiManagerBody(order, LsgGeneration.V10_5, tail))
        val result = decode(bytes, order, v105)
        assertEquals(listOf("PMI_MANAGER_TAIL_UNDOCUMENTED"), result.notes.map { it.name })
        val pmi = assertIs<PmiManagerMetaDataElement>(result.document.elements.single())
        assertContentEquals(tail, pmi.undocumentedTail.toByteArray())
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: Figure 129
    @Test
    fun aCadTagIndexCountThatContradictsTheEntityCountsRefuses() {
        val order = Endianness.LITTLE_ENDIAN
        // Same body, but the CAD Tag Index Count claims 2 where the §11.2.7 formula says 3.
        val body =
            pmiManagerBody(order, LsgGeneration.V10_5).let { bytes ->
                val marker =
                    ByteWriter(order).apply {
                        writeI32(3)
                        writeI32(0)
                        writeI32(1)
                        writeI32(2)
                    }.toByteArray()
                val at = bytes.indexOfSubList(marker)
                assertTrue(at > 0, "the CAD tag index block must be findable")
                bytes.copyOf().also { it[at] = 2 }
            }
        val result = decode(segment(order, body), order, v105)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("CAD Tag Index Count"))
        assertIs<OpaqueMetaDataElement>(result.document.elements.single())
        assertContentEquals(
            segment(order, body),
            result.document.encode(order).toByteArray(),
            "a refused element still re-serializes byte-identically",
        )
    }

    // spec: Figure 118, Table 59
    @Test
    fun aHiddenFlagOutsideTable59Refuses() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT) {
                    writeU8(2u)
                    writeI16(-1)
                    writeI32(0) // design groups
                    writeI32(0) // associations
                    writeI32(0) // user attributes
                    writeI32(1)
                    writeMbString("view") // string table
                    writeI32(1) // one model view
                    repeat(15) { writeF32(0f) }
                    writeI32(0)
                    writeI32(0)
                    writeI32(1)
                    writeI32(0)
                    writeI32(1) // one property
                    writeMbString("k")
                    writeU8(7u) // not a Table 59 value
                    writeMbString("v")
                    writeU8(0u)
                },
            )
        val result = decode(bytes, order, v105)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("Hidden Flag"))
    }

    // spec: Figure 115
    @Test
    fun aStringIdOutsideTheStringTableRefuses() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT) {
                    writeU8(2u)
                    writeI16(-1)
                    writeI32(0)
                    writeI32(1)
                    writeI32(0)
                    writeI32(0)
                    writeI32(0)
                    writeI32(5) // an association naming string 5 …
                    writeI32(0)
                    writeI32(0)
                    writeI32(0) // user attributes
                    writeI32(1)
                    writeMbString("only") // … but the table holds one string
                },
            )
        val result = decode(bytes, order, v105)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("String ID"))
    }

    // spec: 9.5 Figure 136
    @Test
    fun aV9PmiManagerBodyThatIsNotFigure136IsNotGuessed() {
        val order = Endianness.LITTLE_ENDIAN
        // Figure 136's prologue and then nothing: the thirteen typed collections of Figure 137
        // are unconditional, so this body cannot be a JT 9.5 PMI Manager. It is named and
        // carried verbatim rather than half-read.
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT) {
                    writeI16(1)
                    writeI16(8)
                    writeI16(0)
                },
            )
        val result = decode(bytes, order, v9)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        val opaque = assertIs<OpaqueMetaDataElement>(result.document.elements.single())
        assertEquals(9, opaque.objectBaseType)
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: 9.5 Figure 136
    @Test
    fun aV9PmiManagerWithAnUndocumentedVersionNumberRefusesByName() {
        val order = Endianness.LITTLE_ENDIAN
        // §7.2.6.2 names 1 and 2 for the element and 3…8 for the PMI content. Outside those,
        // the guards that shape the whole body are unknown — refuse rather than pick a layout.
        for (prologue in listOf(listOf(3, 8), listOf(1, 2))) {
            val bytes =
                segment(
                    order,
                    metaFrame(order, ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT) {
                        writeI16(prologue[0].toShort())
                        writeI16(prologue[1].toShort())
                        writeI16(0)
                    },
                )
            val result = decode(bytes, order, v9)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
            assertTrue(result.notes.single().message.contains("Version Number"))
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }
    }

    // spec: Figure 107
    @Test
    fun anUnknownElementTypeIsCarriedOpaquely() {
        val order = Endianness.LITTLE_ENDIAN
        val alien = Guid(0xDEADBEEFu, 1u, 2u, ByteArray(8) { it.toByte() }.toBytes())
        val bytes =
            segment(
                order,
                metaFrame(order, alien) { writeBytes(byteArrayOf(1, 2, 3)) },
            )
        val result = decode(bytes, order, v105)
        assertEquals(listOf("UNKNOWN_ELEMENT_TYPE"), result.notes.map { it.name })
        assertIs<OpaqueMetaDataElement>(result.document.elements.single())
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: Figure 107
    @Test
    fun aStreamWithoutTheEndMarkerKeepsItsBytesAndSaysSo() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                writeU8(1u)
                writeMbString("")
            } + byteArrayOf(7, 7, 7)
        val result = decode(bytes, order, v105)
        assertEquals(listOf("META_DATA_STRUCTURE_UNRECOGNIZED"), result.notes.map { it.name })
        assertEquals(3, result.document.trailing.size)
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: Figure 78
    @Test
    fun aStreamEndingAfterItsElementsReportsTheMissingPropertyTable() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                writeU8(1u)
                writeMbString("")
            } + endOfElements(order)
        val result = decode(bytes, order, v105)
        assertEquals(listOf("PROPERTY_TABLE_MISSING"), result.notes.map { it.name })
        assertNull(result.document.propertyTable)
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: Figure 78
    @Test
    fun bytesAfterTheElementsThatAreNotAPropertyTableArePreserved() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                writeU8(1u)
                writeMbString("")
            } + endOfElements(order) +
                ByteWriter(order).apply {
                    writeI16(1)
                    writeI32(1000) // a table count that cannot fit
                }.toByteArray()
        val result = decode(bytes, order, v105)
        assertEquals(listOf("PROPERTY_TABLE_UNRECOGNIZED"), result.notes.map { it.name })
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: Figure 108
    @Test
    fun aTruncatedBodyRefusesInsteadOfInventingFields() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                    writeU8(1u)
                    writeI32(4) // an MbString claiming four characters …
                    writeU16(65u) // … with only one on the wire
                },
            )
        val result = decode(bytes, order, v105)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    // spec: Figure 108
    @Test
    fun trailingBytesInsideAnElementBodyRefuse() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            segment(
                order,
                metaFrame(order, ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT) {
                    writeU8(1u)
                    writeMbString("")
                    writeBytes(byteArrayOf(9, 9))
                },
            )
        val result = decode(bytes, order, v105)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("not consumed"))
    }

    private fun ByteArray.indexOfSubList(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}
