package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.io.ByteReader
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **JT 9.5 PMI element family** per-figure contract (9.5 §7.2.6.2, Figures 136–170): bytes
 * built to follow each figure decode to the typed model with no unexpected notes and serialize
 * back byte-identically.
 *
 * Everything here is spec-derived — no fixture in the corpus carries a JT 9 PMI Manager, which
 * is exactly why the tests are built figure by figure and why several of them assert *meaning*
 * rather than length: the deltas that separate 9.5 from v10 in this chapter are dominated by
 * permutations of same-width words, which no byte count can catch.
 */
class Pmi95DocumentTest {
    private val v9 = JtVersion(9, 5)
    private val v105 = JtVersion(10, 5)

    /** Object Base Type 9 ("JtBase") — what every §7.2.6 element body starts with. */
    private val baseTypeJtBase = 9

    // ------------------------------------------------------------------
    // Byte builders, one per figure
    // ------------------------------------------------------------------

    private fun ByteWriter.vecI32(values: List<Int>) {
        writeI32(values.size)
        for (v in values) writeI32(v)
    }

    private fun ByteWriter.vecF32(values: List<Float>) {
        writeI32(values.size)
        for (v in values) writeF32(v)
    }

    /** 9.5 Figure 141: three CoordF32. */
    private fun ByteWriter.referenceFrame(seed: Float) {
        for (i in 0 until 9) writeF32(seed + i)
    }

    /** 9.5 Figure 140. [symbolValid] is written exactly when PMI Version Number > 4. */
    private fun ByteWriter.baseData(
        userLabel: Int,
        frameFlag: Int,
        symbolValid: Int?,
    ) {
        writeI32(userLabel)
        writeU8(frameFlag.toUByte())
        if (frameFlag != 0) referenceFrame(userLabel.toFloat())
        writeF32(2.5f)
        symbolValid?.let { writeU8(it.toUByte()) }
    }

    /** 9.5 Figures 142/143/145. [emptyVectorForm] writes delta 36's off-document empty VecF32. */
    private fun ByteWriter.text2d(
        stringId: Int,
        indices: List<Short>,
        coords: List<Float>,
        emptyVectorForm: Boolean = false,
    ) {
        writeI32(stringId)
        writeI32(1) // Font = Simplex
        writeI32(0) // I32 Reserved Field
        writeF32(0f) // F32 Reserved Field
        for (i in 0 until 6) writeF32(i.toFloat()) // Text Box
        writeI32(indices.size)
        for (index in indices) writeI16(index)
        if (indices.isNotEmpty() || emptyVectorForm) vecF32(coords)
    }

    /** 9.5 Figure 147. [types] is written exactly when PMI Version Number > 4. */
    private fun ByteWriter.nonTextPolylines(
        indices: List<Short>,
        types: List<Short>?,
        coords: List<Float>,
    ) {
        writeI32(indices.size)
        for (index in indices) writeI16(index)
        types?.let {
            writeI32(it.size)
            for (type in it) writeI16(type)
        }
        vecF32(coords)
    }

    /** 9.5 Figure 139. */
    private fun ByteWriter.data2d(
        pmiVersion: Int,
        userLabel: Int,
        textStringId: Int = -1,
        emptyVectorForm: Boolean = false,
    ) {
        baseData(userLabel, frameFlag = 2, symbolValid = if (pmiVersion > 4) 1 else null)
        writeI32(1)
        text2d(textStringId, listOf(0, 2), listOf(1f, 2f, 3f, 4f), emptyVectorForm)
        nonTextPolylines(listOf(0, 3), if (pmiVersion > 4) listOf(4) else null, listOf(1f, 2f, 3f, 4f, 5f, 6f))
    }

    /** 9.5 Figure 154 — the 9.5-only PMI 3D Data collection. */
    private fun ByteWriter.data3d(
        pmiVersion: Int,
        userLabel: Int,
        stringId: Int = -1,
        dimensionality: Int = 3,
    ) {
        baseData(userLabel, frameFlag = 0, symbolValid = if (pmiVersion > 4) 1 else null)
        writeI32(stringId)
        writeI16(dimensionality.toShort())
        writeI32(2)
        writeI16(0)
        writeI16(2)
        vecF32(listOf(0f, 0f, 0f, 1f, 1f, 1f))
    }

    /** 9.5 Figure 170 — inline bindings, `vNumVerts` length is the element count. */
    private fun ByteWriter.polygonData(vertexCounts: List<Int>) {
        writeI16(1)
        writeI32(0)
        vecI32(vertexCounts)
        for (count in vertexCounts.filter { it > 0 }) {
            writeI32(0) // NormalBinding
            writeI32(1) // ColorBinding
            writeI32(0) // TextureBinding
            writeI32(2) // PolygonDimension
            vecI32(listOf(0, 1))
            vecI32(listOf(0, count))
            vecI32(List(count) { it })
            vecF32(List(count * 2) { it.toFloat() })
            vecF32(List(count * 2) { it.toFloat() }) // Colors, because ColorBinding == 1
        }
    }

    /** 9.5 Figure 168: the Hidden Flag exists only for PMI Version Number > 6. */
    private fun ByteWriter.propertyAtom(
        pmiVersion: Int,
        value: String,
        hidden: Int,
    ) {
        writeMbString(value)
        if (pmiVersion > 6) writeU32(hidden.toUInt())
    }

    /**
     * A complete JT 9.5 PMI Manager body (Figure 136) with every one of Figure 137's thirteen
     * collections populated at the count [counts] gives it, in the figure's own order.
     */
    private fun managerBody(
        order: Endianness,
        version: Int = 2,
        pmiVersion: Int = 8,
        counts: List<Int> = List(13) { 1 },
        strings: List<String> = listOf("PMI", "Group", "Sección"),
        modelViewCount: Int = 1,
        genericCount: Int = 1,
        cadTagsFlag: Int? = null,
        fontCount: Int = 1,
        emptyVectorForm: Boolean = false,
        cadTagBlock: (ByteWriter.() -> Unit)? = null,
    ): ByteArray {
        val w = ByteWriter(order)
        w.writeU8(baseTypeJtBase.toUByte())
        w.writeI32(42)
        w.writeI16(version.toShort())
        w.writeI16(pmiVersion.toShort())
        w.writeI16(-1)

        fun collection2d(
            index: Int,
            label: Int,
        ) {
            w.writeI32(counts[index])
            repeat(counts[index]) { w.data2d(pmiVersion, label + it, textStringId = 0, emptyVectorForm = emptyVectorForm) }
        }

        // Figure 137, in order: the thirteen typed collections.
        collection2d(0, 100) // Dimension
        w.writeI32(counts[1]) // Note
        repeat(counts[1]) {
            w.data2d(pmiVersion, 200 + it, textStringId = 0, emptyVectorForm = emptyVectorForm)
            if (pmiVersion > 5) w.writeU32(1u)
        }
        collection2d(2, 300) // Datum Feature Symbol
        collection2d(3, 400) // Datum Target
        collection2d(4, 500) // Feature Control Frame
        collection2d(5, 600) // Line Weld
        w.writeI32(counts[6]) // Spot Weld
        repeat(counts[6]) {
            w.data3d(pmiVersion, 700 + it)
            if (pmiVersion >= 4) for (i in 0 until 12) w.writeF32(i.toFloat())
        }
        collection2d(7, 800) // Surface Finish
        w.writeI32(counts[8]) // Measurement Point
        repeat(counts[8]) {
            w.data3d(pmiVersion, 900 + it)
            if (pmiVersion >= 4) for (i in 0 until 12) w.writeF32(i.toFloat())
        }
        collection2d(9, 1000) // Locator
        w.writeI32(counts[10]) // Reference Geometry
        repeat(counts[10]) { w.data3d(pmiVersion, 1100 + it) }
        w.writeI32(counts[11]) // Design Group
        repeat(counts[11]) {
            w.writeI32(1)
            if (pmiVersion >= 3) {
                w.writeI32(1)
                w.writeI32(3) // Attribute Type 3 = String
                w.writeI32(0)
                w.writeI32(1)
                w.writeI32(-1)
            }
        }
        w.writeI32(counts[12]) // Coordinate System
        repeat(counts[12]) {
            w.writeI32(1)
            for (i in 0 until 9) w.writeF32(i.toFloat())
        }

        // Figure 162: Source, Destination, Reason, then the two gated owners.
        w.writeI32(1)
        w.writeI32(SOURCE_DATA)
        w.writeI32(DESTINATION_DATA)
        w.writeI32(REASON_CODE)
        if (pmiVersion > 5) {
            w.writeI32(SOURCE_OWNER)
            w.writeI32(DESTINATION_OWNER)
        }

        // Figure 163: user attributes.
        w.writeI32(1)
        w.writeI32(0)
        w.writeI32(1)

        // Figure 164: single-byte Strings, not MbStrings.
        w.writeI32(strings.size)
        for (s in strings) w.writeString(s)

        if (pmiVersion > 5) {
            // Figure 165: eleven fields, no property list.
            w.writeI32(modelViewCount)
            repeat(modelViewCount) {
                for (i in 0 until 3) w.writeF32(i.toFloat()) // Eye Direction
                w.writeF32(30f) // Angle
                for (i in 0 until 9) w.writeF32(i.toFloat()) // Eye Position, Target, View Angle
                w.writeF32(0f) // Viewport Diameter
                w.writeF32(0f) // F32 Reserved Field
                w.writeI32(0) // I32 Reserved Field
                w.writeI32(1) // Active Flag
                w.writeI32(7) // View ID
                w.writeI32(0) // View Name String ID
            }
            // Figure 166.
            w.writeI32(genericCount)
            repeat(genericCount) {
                w.data2d(pmiVersion, 2000 + it, textStringId = 0, emptyVectorForm = emptyVectorForm)
                w.writeI32(1)
                w.propertyAtom(pmiVersion, "PMITextSize", 0)
                w.propertyAtom(pmiVersion, "12", 1)
                w.writeI32(0)
                w.writeI32(1)
                w.writeU16(0x0114u)
                w.writeU16(0x0001u)
                if (pmiVersion > 6) w.writeU16(1u)
            }
        }
        if (pmiVersion > 7) {
            w.writeU32((cadTagsFlag ?: 0).toUInt())
            if (cadTagsFlag == 1) cadTagBlock!!(w)
        }
        if (version > 1) {
            repeat(if (pmiVersion > 5) modelViewCount else 0) {
                w.propertyAtom(pmiVersion, "modelViewStyle", 0)
                w.propertyAtom(pmiVersion, "ShadedWithEdges", 0)
            }
            w.polygonData(listOf(0, 3))
            w.writeI32(fontCount)
            repeat(fontCount) {
                w.writeString("Simplex")
                w.vecI32(listOf(65, 66))
                w.polygonData(listOf(4, 4))
            }
        }
        return w.toByteArray()
    }

    private fun frame(
        order: Endianness,
        body: ByteArray,
    ): ByteArray {
        val w = ByteWriter(order)
        w.writeI32(16 + body.size)
        w.writeGuid(ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT)
        w.writeBytes(body)
        w.writeI32(16)
        w.writeGuid(Guid.END_OF_ELEMENTS)
        w.writeI16(1)
        w.writeI32(0)
        return w.toByteArray()
    }

    private fun decode95(
        order: Endianness,
        body: ByteArray,
    ): MetaDataDecodeResult = MetaDataDocument.decode(frame(order, body).toBytes(), v9, order)

    /** Decodes and asserts the standard contract: no notes, byte-identical re-serialization. */
    private fun roundTrip(
        order: Endianness,
        body: ByteArray,
        expectedNotes: List<String> = emptyList(),
    ): Pmi95ManagerMetaDataElement {
        val bytes = frame(order, body)
        val result = MetaDataDocument.decode(bytes.toBytes(), v9, order)
        assertEquals(expectedNotes, result.notes.map { it.name }, "notes: ${result.notes.map { it.message }}")
        assertContentEquals(bytes, result.document.encode(order).toByteArray(), "encode(decode(body)) drifted")
        return assertIs(result.document.elements.single())
    }

    // ------------------------------------------------------------------
    // Figure 136 / Figure 137 — the framing and the thirteen collections
    // ------------------------------------------------------------------

    // spec: 9.5 Figure 136, 9.5 Figure 137
    @Test
    fun figure136DecodesEveryCollectionAndRoundTrips() {
        forBothOrders { order ->
            val pmi = roundTrip(order, managerBody(order))
            assertEquals(42, pmi.objectId)
            assertEquals(2, pmi.version)
            assertEquals(8, pmi.pmiVersion)
            assertEquals(-1, pmi.reservedField)
            assertEquals(listOf("PMI", "Group", "Sección"), pmi.stringTable)
            assertEquals(1, pmi.associations.size)
            assertEquals(1, pmi.userAttributes.size)
            assertEquals(1, pmi.modelViews?.size)
            assertEquals(1, pmi.genericEntities?.size)
            assertEquals(0, pmi.cadTagsFlag)
            assertNull(pmi.cadTagData)
            val tail = assertNotNull(pmi.tail, "Version Number 2 must carry Figure 136's tail")
            assertEquals(1, tail.modelViewProperties.size, "one PMI Property per Model View")
            assertEquals(listOf(0, 3), tail.polygonData.vertexCounts)
            assertEquals(1, tail.fonts.size)
            assertEquals("Simplex", tail.fonts.single().name)
            assertEquals(listOf(65, 66), tail.fonts.single().characterSet)
        }
    }

    /**
     * The order of Figure 137's thirteen boxes is the whole content of the figure, and the
     * collections share a layout, so a permutation reads the *same bytes* into different
     * collections. Distinct counts per collection are what makes the order assertable.
     *
     * spec: 9.5 Figure 137
     */
    @Test
    fun theThirteenTypedCollectionsAreReadInFigure137Order() {
        val order = Endianness.LITTLE_ENDIAN
        val counts = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
        val pmi = roundTrip(order, managerBody(order, counts = counts))
        val entities = pmi.entities
        assertEquals(
            counts,
            listOf(
                entities.dimensions.size,
                entities.notes.size,
                entities.datumFeatureSymbols.size,
                entities.datumTargets.size,
                entities.featureControlFrames.size,
                entities.lineWelds.size,
                entities.spotWelds.size,
                entities.surfaceFinishes.size,
                entities.measurementPoints.size,
                entities.locators.size,
                entities.referenceGeometry.size,
                entities.designGroups.size,
                entities.coordinateSystems.size,
            ),
            "Figure 137's collections must be read in the figure's own order",
        )
        // The per-entity user labels the builder writes make the order checkable a second way.
        assertEquals(100, entities.dimensions.first().base.userLabel)
        assertEquals(200, entities.notes.first().data2d.base.userLabel)
        assertEquals(700, entities.spotWelds.first().data3d.base.userLabel)
        assertEquals(1100, entities.referenceGeometry.first().base.userLabel)
        // §7.2.6.2.7's formula: one CAD Tag index per entity of all fifteen supporting kinds.
        assertEquals(counts.sum() + 1 + 1, pmi.cadTagIndexCount)
    }

    // spec: 9.5 Figure 136
    @Test
    fun elementVersionOneStopsBeforeTheTailBlock() {
        forBothOrders { order ->
            val pmi = roundTrip(order, managerBody(order, version = 1))
            assertEquals(1, pmi.version)
            assertNull(pmi.tail, "Figure 136 gates the property/polygon/font block on Version Number > 1")
        }
    }

    // ------------------------------------------------------------------
    // F-5 — PMI Associations (9.5 Figure 162 vs v10 Figure 113)
    // ------------------------------------------------------------------

    /**
     * The defect this package exists to prevent. 9.5 Figure 162 writes Source, **Destination**,
     * Reason, Source Owner, Destination Owner; v10 Figure 113 writes Source, **Source Owner**,
     * Reason, Destination, Destination Owner. All five are `I32`, so both readings consume
     * exactly twenty bytes and no length check, byte count or round-trip can tell them apart —
     * only the meaning differs. This asserts the meaning.
     *
     * spec: 9.5 Figure 162
     */
    @Test
    fun associationsFollowTheNinePointFiveFieldOrderNotTheV10One() {
        val order = Endianness.LITTLE_ENDIAN
        val pmi = roundTrip(order, managerBody(order))
        val association = pmi.associations.single()
        assertEquals(SOURCE_DATA, association.sourceData)
        assertEquals(DESTINATION_DATA, association.destinationData)
        assertEquals(REASON_CODE, association.reasonCode)
        assertEquals(SOURCE_OWNER, association.sourceOwningEntityStringId)
        assertEquals(DESTINATION_OWNER, association.destinationOwningEntityStringId)

        // What the v10 permutation would have made of the very same twenty bytes: the second
        // word becomes the source owner, the fourth the destination data. Same byte count,
        // different meaning — which is why the assertions above are on semantics.
        val w = ByteWriter(order)
        w.writeI32(1)
        w.writeI32(SOURCE_DATA)
        w.writeI32(DESTINATION_DATA)
        w.writeI32(REASON_CODE)
        w.writeI32(SOURCE_OWNER)
        w.writeI32(DESTINATION_OWNER)
        val words = w.toByteArray()
        val r = ByteReader(words, order)
        r.readI32()
        val asV10 =
            PmiAssociation(r.readI32(), r.readI32(), r.readI32(), r.readI32(), r.readI32())
        assertEquals(24, words.size, "both readings consume the same bytes")
        assertEquals(SOURCE_DATA, asV10.sourceData, "only the first word survives the permutation")
        assertEquals(REASON_CODE, asV10.reasonCode, "the third word coincides by accident")
        // The two that do not: what 9.5 calls the destination entity the v10 order files as the
        // source's owning part, and what 9.5 calls the source's owner it files as the
        // destination entity. Both are I32 String-ID-shaped, so nothing downstream complains.
        assertEquals(DESTINATION_DATA, asV10.sourceOwningEntityStringId, "the v10 order misreads Destination Data")
        assertEquals(SOURCE_OWNER, asV10.destinationData, "the v10 order misreads the Source Owner")
        assertNotEquals(association.destinationData, asV10.destinationData)
        assertNotEquals(association.sourceOwningEntityStringId, asV10.sourceOwningEntityStringId)
    }

    // spec: 9.5 Figure 162
    @Test
    fun associationOwnersAreOffTheWireBelowPmiVersionSix() {
        val order = Endianness.LITTLE_ENDIAN
        val long = managerBody(order, pmiVersion = 8)
        val short = managerBody(order, pmiVersion = 5)
        val pmi = roundTrip(order, short)
        val association = pmi.associations.single()
        assertNull(association.sourceOwningEntityStringId)
        assertNull(association.destinationOwningEntityStringId)
        assertEquals(SOURCE_DATA, association.sourceData)
        assertEquals(DESTINATION_DATA, association.destinationData)
        assertTrue(short.size < long.size, "the gated owners must actually shrink the body")
    }

    // ------------------------------------------------------------------
    // F-8 — PMI strings are single-byte (9.5 Figure 164, §7.1.1)
    // ------------------------------------------------------------------

    /**
     * 9.5 writes `String : PMI String` (§7.1.1: `I32 Count + Count × U8`) where v10's Figure 115
     * writes `MbString` (`Count × U16`). Read the v10 way, a 9.5 string table consumes twice the
     * bytes of every string and the element desynchronizes immediately.
     *
     * spec: 9.5 Figure 164
     */
    @Test
    fun theStringTableAndFontNamesAreSingleByteStrings() {
        val order = Endianness.LITTLE_ENDIAN
        val pmi = roundTrip(order, managerBody(order))
        assertEquals(listOf("PMI", "Group", "Sección"), pmi.stringTable)
        assertEquals("Simplex", pmi.tail!!.fonts.single().name)

        // The table's own bytes: three counted single-byte strings, 3 + 5 + 7 characters.
        val w = ByteWriter(order)
        w.writeI32(3)
        for (s in pmi.stringTable) w.writeString(s)
        assertEquals(4 + (4 + 3) + (4 + 5) + (4 + 7), w.toByteArray().size)
        val asMbString = ByteReader(w.toByteArray(), order)
        assertEquals(3, asMbString.readI32())
        assertNotEquals(
            "PMI",
            asMbString.readMbString(),
            "an MbString read of a 9.5 PMI string must not accidentally agree",
        )
    }

    // spec: 9.5 Figure 136 (VecI32 Character Set)
    @Test
    fun fontCharacterSetsAreVecI32NotVecU16() {
        val order = Endianness.LITTLE_ENDIAN
        val pmi = roundTrip(order, managerBody(order))
        val font = pmi.tail!!.fonts.single()
        assertEquals(listOf(65, 66), font.characterSet)
        // Two identifiers at four bytes each plus the count, where v10's VecU16 would be 8.
        val w = ByteWriter(order)
        w.vecI32(font.characterSet)
        assertEquals(12, w.toByteArray().size)
    }

    // ------------------------------------------------------------------
    // F-10 — three encodings of the Hidden Flag
    // ------------------------------------------------------------------

    /**
     * 9.5 Figure 168 gates the Hidden Flag on `PMI Version Number > 6`, so a JT 9.5 file can
     * hold a property atom with **no** flag at all — the third state beside NX 10.5's one byte
     * (delta 32) and the documented v10 `U32`. `null` is what records it.
     *
     * spec: 9.5 Figure 168
     */
    @Test
    fun theHiddenFlagIsAbsentAtPmiVersionSixAndPresentAtSeven() {
        val order = Endianness.LITTLE_ENDIAN
        val without = roundTrip(order, managerBody(order, pmiVersion = 6))
        val property = without.genericEntities!!.single().properties.single()
        assertNull(property.key.hiddenFlag, "PMI Version 6 puts the Hidden Flag off the wire")
        assertNull(property.value.hiddenFlag)
        assertEquals("PMITextSize", property.key.value)
        assertEquals("12", property.value.value)

        val with = roundTrip(order, managerBody(order, pmiVersion = 8))
        val flagged = with.genericEntities!!.single().properties.single()
        assertEquals(0, flagged.key.hiddenFlag)
        assertEquals(1, flagged.value.hiddenFlag)

        // Four bytes per atom, eight per property, and the flag is what separates the two
        // bodies at this position — a byte count alone would not name the field.
        assertTrue(
            managerBody(order, pmiVersion = 8).size > managerBody(order, pmiVersion = 6).size,
        )
    }

    // spec: 9.5 Figure 168
    @Test
    fun aHiddenFlagOutsideItsValueSetRefusesByName() {
        val order = Endianness.LITTLE_ENDIAN
        val body = managerBody(order)
        // Rewrite the generic entity's key atom flag (the first U32 that reads 0 after the
        // "PMITextSize" MbString) to 2, which §7.2.6.2.6.1.1 does not define.
        val marker = ByteWriter(order).also { it.writeMbString("PMITextSize") }.toByteArray()
        val at = body.indexOfSlice(marker)
        assertTrue(at >= 0, "the builder must have written the property key")
        val patched = body.copyOf()
        patched[at + marker.size + if (order == Endianness.LITTLE_ENDIAN) 0 else 3] = 2
        val result = decode95(order, patched)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("Hidden Flag"))
        assertIs<OpaqueMetaDataElement>(result.document.elements.single())
    }

    // ------------------------------------------------------------------
    // F-4 — Figure 145's guard, and the producer form beside it
    // ------------------------------------------------------------------

    /**
     * A text with no polyline segments: the figure's guard puts *both* arrays off the wire, so
     * the coordinate vector is `null` rather than an empty list, and the body is four bytes
     * shorter than the producer form. Silence means the document was matched.
     *
     * spec: 9.5 Figure 145
     */
    @Test
    fun aZeroSegmentTextPolylineFollowsTheFigureByDefault() {
        val order = Endianness.LITTLE_ENDIAN
        val w = ByteWriter(order)
        w.text2d(stringId = -1, indices = emptyList(), coords = emptyList())
        assertEquals(44, w.toByteArray().size, "the figure's form: 40 scalar bytes plus one zero count")

        val body = emptyTextManagerBody(order, emptyVectorForm = false)
        val pmi = roundTrip(order, body)
        val text = pmi.entities.dimensions.single().texts.single()
        assertEquals(emptyList<Short>(), text.polylines.segmentIndices)
        assertNull(text.polylines.vertexCoords, "Figure 145's guard encloses the VecF32 too")
        assertEquals(Pmi95TextPolylineForm.FIGURE, pmi.textPolylineForm)
    }

    /**
     * The same body written the way NX writes v10 (delta 36) — with the empty `VecF32` present.
     * The reader accepts it, resolves the form from the body's exact extent rather than from an
     * assumption, records which form it saw, and *says so*: leniency is never silence.
     *
     * spec: 9.5 Figure 145
     */
    @Test
    fun theProducerFormIsAcceptedRecordedAndNamed() {
        val order = Endianness.LITTLE_ENDIAN
        val figure = emptyTextManagerBody(order, emptyVectorForm = false)
        val producer = emptyTextManagerBody(order, emptyVectorForm = true)
        assertEquals(figure.size + 4 * 2, producer.size, "one extra empty VecF32 per text entity")

        val pmi = roundTrip(order, producer, listOf("PMI_TEXT_POLYLINE_VECTOR_OFF_DOCUMENT"))
        assertEquals(Pmi95TextPolylineForm.EMPTY_VECTOR, pmi.textPolylineForm)
        val text = pmi.entities.dimensions.single().texts.single()
        assertEquals(emptyList<Float>(), text.polylines.vertexCoords, "present and empty, not absent")
    }

    /**
     * A body with only *populated* text polylines reads the same under both forms, so the
     * arbitration must not invent a deviation: the figure's form wins and no note is emitted.
     *
     * spec: 9.5 Figure 145
     */
    @Test
    fun theArbitrationStaysSilentWhenBothFormsAgree() {
        forBothOrders { order ->
            val pmi = roundTrip(order, managerBody(order))
            assertEquals(Pmi95TextPolylineForm.FIGURE, pmi.textPolylineForm)
            val text = pmi.entities.dimensions.single().texts.single()
            assertEquals(listOf<Short>(0, 2), text.polylines.segmentIndices)
            assertEquals(listOf(1f, 2f, 3f, 4f), text.polylines.vertexCoords)
        }
    }

    /** A JT 9.5 manager whose three text entities carry no polyline segments. */
    private fun emptyTextManagerBody(
        order: Endianness,
        emptyVectorForm: Boolean,
    ): ByteArray {
        val w = ByteWriter(order)
        w.writeU8(baseTypeJtBase.toUByte())
        w.writeI32(1)
        w.writeI16(2)
        w.writeI16(8)
        w.writeI16(0)
        // Dimension: one 2D entity whose single text has no polylines.
        w.writeI32(1)
        w.baseData(1, frameFlag = 0, symbolValid = 1)
        w.writeI32(1)
        w.text2d(0, emptyList(), emptyList(), emptyVectorForm)
        w.nonTextPolylines(emptyList(), emptyList(), emptyList())
        for (i in 0 until 12) w.writeI32(0) // the other twelve collections are empty
        w.writeI32(0) // associations
        w.writeI32(0) // user attributes
        w.writeI32(1)
        w.writeString("PMI")
        w.writeI32(0) // model views
        // One generic entity, again with an empty text, so all three texts share the form.
        w.writeI32(1)
        w.baseData(2, frameFlag = 0, symbolValid = 1)
        w.writeI32(1)
        w.text2d(0, emptyList(), emptyList(), emptyVectorForm)
        w.nonTextPolylines(emptyList(), emptyList(), emptyList())
        w.writeI32(0)
        w.writeI32(-1)
        w.writeI32(-1)
        w.writeU16(0x0114u)
        w.writeU16(0x0001u)
        w.writeU16(0u)
        w.writeU32(0u) // CAD Tags Flag
        // The tail: no model views, so no properties; one font with one empty text glyph run.
        w.polygonData(listOf(0))
        w.writeI32(1)
        w.writeString("F")
        w.vecI32(emptyList())
        w.writeI16(1)
        w.writeI32(0)
        w.vecI32(listOf(0))
        // A third text, inside the font-free part of the body, is unnecessary; keep two and pad
        // the expected size accordingly.
        return w.toByteArray()
    }

    // ------------------------------------------------------------------
    // F-12 — PMI Polygon Data (9.5 Figure 170)
    // ------------------------------------------------------------------

    /**
     * 9.5 writes `I16 Version` + `I32 Reserved Field`, derives the element count from
     * `vNumVerts`' own length, and puts the three bindings and the dimension **inline per
     * element** in the order Normal → Color → Texture. v10 hoists them into parallel
     * `vBindings` (Color → Normal → Texture) and `vPolygonDimensions` vectors after a declared
     * element count. Nothing about the two layouts lines up.
     *
     * spec: 9.5 Figure 170
     */
    @Test
    fun polygonDataIsTheNinePointFiveShapeWithInlineBindings() {
        forBothOrders { order ->
            val pmi = roundTrip(order, managerBody(order))
            val data = pmi.tail!!.polygonData
            assertEquals(1, data.version)
            assertEquals(0, data.reservedField)
            assertEquals(listOf(0, 3), data.vertexCounts, "vNumVerts' length is the element count")
            val element = data.elements.single()
            assertEquals(3, element.vertexCount)
            assertEquals(0, element.normalBinding)
            assertEquals(1, element.colourBinding)
            assertEquals(0, element.textureBinding)
            assertEquals(2, element.polygonDimension)
            assertNull(element.normals)
            assertEquals(6, element.colours?.size)
            assertNull(element.textureCoords)
            // Empty elements contribute no per-element data, exactly as §7.2.6.2.8 says.
            assertEquals(1, data.elements.size)
        }
    }

    /**
     * Figure 170 labels the `TextureBinding == 1` box `I16 : Reserved Field`; §7.2.6.2.8's prose
     * says `VecF32: Texture Coords`. The prose is followed — it is self-consistent and agrees
     * with v10 — and the deviation is *named*, so the first fixture to take the branch settles
     * it instead of passing silently.
     *
     * spec: 9.5 Figure 170
     */
    @Test
    fun theTextureBindingBranchFollowsTheProseAndSaysSo() {
        val order = Endianness.LITTLE_ENDIAN
        val w = ByteWriter(order)
        w.writeU8(baseTypeJtBase.toUByte())
        w.writeI32(1)
        w.writeI16(2)
        w.writeI16(8)
        w.writeI16(0)
        for (i in 0 until 13) w.writeI32(0)
        w.writeI32(0) // associations
        w.writeI32(0) // user attributes
        w.writeI32(0) // strings
        w.writeI32(0) // model views
        w.writeI32(0) // generic entities
        w.writeU32(0u) // CAD Tags Flag
        // The tail's polygon block: one element with TextureBinding set.
        w.writeI16(1)
        w.writeI32(0)
        w.vecI32(listOf(2))
        w.writeI32(0) // NormalBinding
        w.writeI32(0) // ColorBinding
        w.writeI32(1) // TextureBinding
        w.writeI32(2) // PolygonDimension
        w.vecI32(listOf(0, 1))
        w.vecI32(listOf(0, 2))
        w.vecI32(listOf(0, 1))
        w.vecF32(listOf(0f, 0f, 1f, 1f))
        w.vecF32(listOf(0f, 0f, 1f, 1f)) // Texture Coords, two per vertex
        w.writeI32(0) // fonts
        val pmi = roundTrip(order, w.toByteArray(), listOf("PMI_POLYGON_TEXTURE_BINDING_UNSETTLED"))
        assertEquals(listOf(0f, 0f, 1f, 1f), pmi.tail!!.polygonData.elements.single().textureCoords)
    }

    // ------------------------------------------------------------------
    // The version gates of Figures 140, 147, 148, 153, 156, 159, 166
    // ------------------------------------------------------------------

    // spec: 9.5 Figures 140, 147, 148, 153, 156, 159, 166
    @Test
    fun everyPmiVersionGateChangesTheWireLayout() {
        val order = Endianness.LITTLE_ENDIAN
        val v3 = roundTrip(order, managerBody(order, pmiVersion = 3))
        val v8 = roundTrip(order, managerBody(order, pmiVersion = 8))

        // Figure 140: U8 Symbol Valid Flag, PMI Version Number > 4.
        assertNull(v3.entities.dimensions.single().base.symbolValidFlag)
        assertEquals(1, v8.entities.dimensions.single().base.symbolValidFlag)
        // Figure 147: the polyline type array, PMI Version Number > 4.
        assertNull(v3.entities.dimensions.single().data2dTypes())
        assertEquals(listOf<Short>(4), v8.entities.dimensions.single().data2dTypes())
        // Figure 148: U32 URL Flag, PMI Version Number > 5.
        assertNull(v3.entities.notes.single().urlFlag)
        assertEquals(1, v8.entities.notes.single().urlFlag)
        // Figures 153/156: the four vectors, PMI Version Number >= 4.
        assertNull(v3.entities.spotWelds.single().geometry)
        assertNotNull(v8.entities.spotWelds.single().geometry)
        assertNull(v3.entities.measurementPoints.single().geometry)
        assertNotNull(v8.entities.measurementPoints.single().geometry)
        // Figure 159: the Design Group attribute block, PMI Version Number >= 3 — always taken
        // in a conforming file, because 3 is the lowest documented PMI version.
        assertEquals(1, v3.entities.designGroups.single().attributes?.size)
        assertEquals(1, v8.entities.designGroups.single().attributes?.size)
        // Figure 136: Model Views and Generic PMI Entities, PMI Version Number > 5.
        assertNull(v3.modelViews)
        assertNull(v3.genericEntities)
        assertNotNull(v8.modelViews)
        // Figure 166: U16 User Flags, PMI Version Number > 6.
        assertEquals(1, v8.genericEntities!!.single().userFlags)
        assertNull(roundTrip(order, managerBody(order, pmiVersion = 6)).genericEntities!!.single().userFlags)
        // Figure 136: the CAD Tags Flag, PMI Version Number > 7.
        assertNull(v3.cadTagsFlag)
        assertEquals(0, v8.cadTagsFlag)
    }

    private fun Pmi952dData.data2dTypes(): List<Short>? = nonTextPolylines.types

    // spec: 9.5 Figure 165
    @Test
    fun modelViewsCarryNoPropertyListOfTheirOwn() {
        val order = Endianness.LITTLE_ENDIAN
        val pmi = roundTrip(order, managerBody(order, modelViewCount = 2))
        assertEquals(2, pmi.modelViews?.size)
        val view = pmi.modelViews!!.first()
        assertEquals(30f, view.angle)
        assertEquals(1, view.activeFlag)
        assertEquals(7, view.viewId)
        assertEquals("PMI", pmi.string(view.viewNameStringId))
        // 9.5 keeps one property per view in the Figure 136 tail, not inside the view record.
        assertEquals(2, pmi.tail!!.modelViewProperties.size)
        assertEquals("modelViewStyle", pmi.tail!!.modelViewProperties.first().key.value)
    }

    // ------------------------------------------------------------------
    // PMI CAD Tag Data (9.5 Figure 169 + §8.1.16 Figure 242)
    // ------------------------------------------------------------------

    /**
     * The indices and the collection framing decode; the coded vectors do not, because 9.5's
     * Figure 242 is not v10's Figure 154. They are kept verbatim behind the collection's own
     * `Data Length` and the refusal is named — nothing guessed, nothing lost.
     *
     * spec: 9.5 Figure 169
     */
    @Test
    fun cadTagDataIsFramedAndNamedRatherThanDecoded() {
        val order = Endianness.LITTLE_ENDIAN
        val coded = byteArrayOf(9, 8, 7, 6, 5)
        val body =
            managerBody(order, cadTagsFlag = 1) {
                // Figure 169: the index count must equal §7.2.6.2.7's fifteen-count sum.
                writeI32(15)
                repeat(15) { writeI32(it) }
                // Figure 242: I16 version, Data Length (from the field itself), inner version,
                // CAD Tag Count, then the coded vectors.
                writeI16(1)
                writeI32(12 + coded.size)
                writeI32(1)
                writeI32(3)
                writeBytes(coded)
            }
        val pmi = roundTrip(order, body, listOf("CAD_TAG_VECTORS_UNRECOGNIZED"))
        val tags = assertNotNull(pmi.cadTagData)
        assertEquals(1, pmi.cadTagsFlag)
        assertEquals(15, tags.indices.size)
        assertEquals(3, tags.cadTagCount)
        assertContentEquals(coded, tags.codedData.toByteArray())
    }

    // spec: 9.5 Figure 169
    @Test
    fun aCadTagIndexCountOutsideTheFormulaRefusesByName() {
        val order = Endianness.LITTLE_ENDIAN
        val body =
            managerBody(order, cadTagsFlag = 1) {
                writeI32(14) // one short of the fifteen-count sum
                repeat(14) { writeI32(it) }
                writeI16(1)
                writeI32(12)
                writeI32(1)
                writeI32(0)
            }
        val result = decode95(order, body)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("§7.2.6.2.7"))
        assertContentEquals(frame(order, body), result.document.encode(order).toByteArray())
    }

    // ------------------------------------------------------------------
    // Cross-generation guards
    // ------------------------------------------------------------------

    /**
     * A JT 9.5 body handed to the v10 reader: the two prologues differ (`I16 + I16 + I16` vs
     * `U8 + I16`) and the collections that follow have nothing in common, so it must never
     * decode into a v10 manager.
     *
     * spec: 9.5 Figure 136 against Figure 110
     */
    @Test
    fun aNinePointFiveBodyIsNotReadAsAV10Manager() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes = frame(order, managerBody(order))
        val asV10 = MetaDataDocument.decode(bytes.toBytes(), v105, order)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), asV10.notes.map { it.name })
        assertIs<OpaqueMetaDataElement>(asV10.document.elements.single())
        assertContentEquals(bytes, asV10.document.encode(order).toByteArray(), "the refusal still carries the bytes")
    }

    /**
     * Hostile input: a body cut short mid-collection, and one whose Dimension Count claims more
     * entities than the body can hold. Both must be *named* and carried verbatim — never a
     * partial element, never an exception out of the API.
     *
     * spec: 9.5 Figure 136
     */
    @Test
    fun truncatedAndOverlongBodiesAreNamedAndCarriedVerbatim() {
        val order = Endianness.LITTLE_ENDIAN
        val full = managerBody(order)
        for (body in listOf(full.copyOf(full.size / 2), full.copyOf(11))) {
            val bytes = frame(order, body)
            val result = MetaDataDocument.decode(bytes.toBytes(), v9, order)
            assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
            assertIs<OpaqueMetaDataElement>(result.document.elements.single())
            assertContentEquals(bytes, result.document.encode(order).toByteArray())
        }
        // A Dimension Count of 0x7FFFFFFF: bounded against the body, refused by name, not by
        // an out-of-memory allocation.
        val patched = full.copyOf()
        val at = 5 + 6 // the base type byte, the object id, and Figure 136's three I16s
        for (i in 0 until 4) patched[at + i] = if (i == 3) 0x7F else 0xFF.toByte()
        val bytes = frame(order, patched)
        val result = MetaDataDocument.decode(bytes.toBytes(), v9, order)
        assertEquals(listOf("ELEMENT_DECODE_FAILED"), result.notes.map { it.name })
        assertTrue(result.notes.single().message.contains("does not fit"))
        assertContentEquals(bytes, result.document.encode(order).toByteArray())
    }

    companion object {
        // Distinct, recognizable words so a permutation of Figure 162's five I32s is visible.
        private const val SOURCE_DATA = 0x0A123456
        private const val DESTINATION_DATA = 0x0B654321
        private const val REASON_CODE = 13
        private const val SOURCE_OWNER = 1
        private const val DESTINATION_OWNER = 2
    }
}

private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
    outer@ for (start in 0..size - needle.size) {
        for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
        return start
    }
    return -1
}
