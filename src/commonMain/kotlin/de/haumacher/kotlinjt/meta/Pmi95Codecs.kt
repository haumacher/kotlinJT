package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.readVec3F32
import de.haumacher.kotlinjt.lsg.writeVec3F32

/**
 * The reader and writer of the **JT 9.5 PMI element family** (9.5 §7.2.6.2, Figures 136–170).
 * See [Pmi95ManagerMetaDataElement] for why this is a second codec beside the v10 one rather
 * than a set of version branches inside it.
 *
 * Every guard in this file keys off the element's own `I16 PMI Version Number`, which is why it
 * is threaded through as [Pmi95Gates] rather than re-read: 9.5's prose says "Version Number"
 * where six figures mean *PMI* Version Number (§7.2.6.2.1.1.1.1, and again at Figures 147, 148,
 * 153, 156 and 162), and the element `Version Number` only ever takes 1 or 2, which makes those
 * "> 4" / "> 5" readings unsatisfiable. The figures are right; the prose is loose shorthand. The
 * one place plain `Version Number` genuinely means the element version is Figure 136's own tail
 * guard `Version Number > 1`.
 */
internal class Pmi95Gates(
    val pmiVersion: Int,
    val textPolylineForm: Pmi95TextPolylineForm,
) {
    /** Figure 140's Symbol Valid Flag and Figure 147's polyline type array. */
    val pmiVersionAbove4: Boolean get() = pmiVersion > 4

    /** Figures 153/156: the spot weld's and measurement point's four vectors. */
    val pmiVersionAtLeast4: Boolean get() = pmiVersion >= 4

    /** Figure 159's Design Group attribute block. */
    val pmiVersionAtLeast3: Boolean get() = pmiVersion >= 3

    /** Figures 136/148/162: model views, generic entities, note URL flag, association owners. */
    val pmiVersionAbove5: Boolean get() = pmiVersion > 5

    /** Figures 166/168: the generic entity's User Flags and the property atom's Hidden Flag. */
    val pmiVersionAbove6: Boolean get() = pmiVersion > 6

    /** Figure 136: the CAD Tags Flag and everything it gates. */
    val pmiVersionAbove7: Boolean get() = pmiVersion > 7
}

/** What a 9.5 PMI Manager decode has to report beyond the element itself. */
internal class Pmi95Decode(
    val element: Pmi95ManagerMetaDataElement,
    /** Why an embedded Compressed CAD Tag Data collection was kept verbatim; `null` when none. */
    val cadTagRefusal: String?,
    /** How many PolygonData elements set TextureBinding — see [readPmi95PolygonData]. */
    val textureBindingElements: Int,
)

private class Pmi95Report {
    var cadTagRefusal: String? = null
    var textureBindingElements = 0
}

// ---------------------------------------------------------------------------
// PMI Base Data / 2D Data / 3D Data (Figures 139-147, 154)
// ---------------------------------------------------------------------------

// spec: 9.5 Figure 140
private fun readPmi95BaseData(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi95BaseData {
    val userLabel = r.readI32()
    val frameFlag = r.readU8().toInt()
    val frame = if (frameFlag != 0) readPmi2dReferenceFrame(r) else null
    val textHeight = r.readF32()
    val symbolValid = if (g.pmiVersionAbove4) r.readU8().toInt() else null
    return Pmi95BaseData(userLabel, frameFlag, frame, textHeight, symbolValid)
}

private fun writePmi95BaseData(
    w: ByteWriter,
    data: Pmi95BaseData,
) {
    w.writeI32(data.userLabel)
    w.writeU8(data.frameFlag.toUByte())
    data.referenceFrame?.let { writePmi2dReferenceFrame(w, it) }
    w.writeF32(data.textHeight)
    data.symbolValidFlag?.let { w.writeU8(it.toUByte()) }
}

// spec: 9.5 Figure 147
private fun readPmi95NonTextPolylineData(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi95NonTextPolylineData {
    val indexCount = r.readCount("Non-Text Polyline Segment Index", 2)
    val indices = List(indexCount) { r.readI16() }
    val types =
        if (g.pmiVersionAbove4) {
            val typeCount = r.readCount("Polyline Type", 2)
            List(typeCount) { r.readI16() }
        } else {
            null
        }
    return Pmi95NonTextPolylineData(indices, types, r.readVecF32())
}

private fun writePmi95NonTextPolylineData(
    w: ByteWriter,
    data: Pmi95NonTextPolylineData,
) {
    w.writeI32(data.segmentIndices.size)
    for (index in data.segmentIndices) w.writeI16(index)
    data.types?.let { types ->
        w.writeI32(types.size)
        for (type in types) w.writeI16(type)
    }
    w.writeVecF32(data.vertexCoords)
}

/** The smallest 2D Text Data record: 40 bytes of scalars plus a zero index count. */
private const val MIN_2D_TEXT_BYTES = 44

/** The smallest PMI 2D Data record: base data, a zero text count, a minimal non-text block. */
private const val MIN_2D_DATA_BYTES = 25

/** The smallest PMI 3D Data record: base data, string id, dimensionality, two zero counts. */
private const val MIN_3D_DATA_BYTES = 27

// spec: 9.5 Figure 139
private fun readPmi952dData(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi952dData {
    val base = readPmi95BaseData(r, g)
    val textCount = r.readCount("2D Text Data", MIN_2D_TEXT_BYTES)
    val texts = List(textCount) { readPmi2dText(r, g.textPolylineForm) }
    return Pmi952dData(base, texts, readPmi95NonTextPolylineData(r, g))
}

private fun writePmi952dData(
    w: ByteWriter,
    data: Pmi952dData,
) {
    writePmi95BaseData(w, data.base)
    w.writeI32(data.texts.size)
    for (text in data.texts) writePmi2dText(w, text)
    writePmi95NonTextPolylineData(w, data.nonTextPolylines)
}

// spec: 9.5 Figure 154
private fun readPmi953dData(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi953dData {
    val base = readPmi95BaseData(r, g)
    val stringId = r.readI32()
    val dimensionality = r.readI16().toInt()
    val indexCount = r.readCount("PMI 3D Polyline Segment Index", 2)
    val indices = List(indexCount) { r.readI16() }
    return Pmi953dData(base, stringId, dimensionality, indices, r.readVecF32())
}

private fun writePmi953dData(
    w: ByteWriter,
    data: Pmi953dData,
) {
    writePmi95BaseData(w, data.base)
    w.writeI32(data.stringId)
    w.writeI16(data.polylineDimensionality.toShort())
    w.writeI32(data.segmentIndices.size)
    for (index in data.segmentIndices) w.writeI16(index)
    w.writeVecF32(data.vertexCoords)
}

// ---------------------------------------------------------------------------
// PMI Entities (Figures 137-161)
// ---------------------------------------------------------------------------

private fun read2dCollection(
    r: ByteReader,
    g: Pmi95Gates,
    what: String,
): List<Pmi952dData> {
    val count = r.readCount(what, MIN_2D_DATA_BYTES)
    return List(count) { readPmi952dData(r, g) }
}

private fun write2dCollection(
    w: ByteWriter,
    entities: List<Pmi952dData>,
) {
    w.writeI32(entities.size)
    for (entity in entities) writePmi952dData(w, entity)
}

// spec: 9.5 Figure 137
private fun readPmi95Entities(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi95Entities {
    val dimensions = read2dCollection(r, g, "PMI Dimension")
    // spec: 9.5 Figure 148
    val noteCount = r.readCount("PMI Note", MIN_2D_DATA_BYTES)
    val notes =
        List(noteCount) {
            val data2d = readPmi952dData(r, g)
            Pmi95Note(data2d, if (g.pmiVersionAbove5) r.readU32().toInt() else null)
        }
    val datumFeatureSymbols = read2dCollection(r, g, "PMI Datum Feature Symbol")
    val datumTargets = read2dCollection(r, g, "PMI Datum Target")
    val featureControlFrames = read2dCollection(r, g, "PMI Feature Control Frame")
    val lineWelds = read2dCollection(r, g, "PMI Line Weld")
    // spec: 9.5 Figure 153
    val spotWeldCount = r.readCount("PMI Spot Weld", MIN_3D_DATA_BYTES)
    val spotWelds =
        List(spotWeldCount) {
            val data3d = readPmi953dData(r, g)
            val geometry =
                if (g.pmiVersionAtLeast4) {
                    Pmi95SpotWeldGeometry(r.readVec3F32(), r.readVec3F32(), r.readVec3F32(), r.readVec3F32())
                } else {
                    null
                }
            Pmi95SpotWeld(data3d, geometry)
        }
    val surfaceFinishes = read2dCollection(r, g, "PMI Surface Finish")
    // spec: 9.5 Figure 156
    val measurementPointCount = r.readCount("PMI Measurement Point", MIN_3D_DATA_BYTES)
    val measurementPoints =
        List(measurementPointCount) {
            val data3d = readPmi953dData(r, g)
            val geometry =
                if (g.pmiVersionAtLeast4) {
                    Pmi95MeasurementPointGeometry(r.readVec3F32(), r.readVec3F32(), r.readVec3F32(), r.readVec3F32())
                } else {
                    null
                }
            Pmi95MeasurementPoint(data3d, geometry)
        }
    val locators = read2dCollection(r, g, "PMI Locator")
    // spec: 9.5 Figure 158
    val referenceGeometryCount = r.readCount("PMI Reference Geometry", MIN_3D_DATA_BYTES)
    val referenceGeometry = List(referenceGeometryCount) { readPmi953dData(r, g) }
    // spec: 9.5 Figures 159/160
    val designGroupCount = r.readCount("PMI Design Group", 4)
    val designGroups =
        List(designGroupCount) {
            val nameStringId = r.readI32()
            val attributes =
                if (g.pmiVersionAtLeast3) {
                    val attributeCount = r.readCount("Design Group Attribute", 12)
                    List(attributeCount) { readPmiDesignGroupAttribute(r) }
                } else {
                    null
                }
            Pmi95DesignGroup(nameStringId, attributes)
        }
    // spec: 9.5 Figure 161
    val coordinateSystemCount = r.readCount("PMI Coordinate System", 40)
    val coordinateSystems =
        List(coordinateSystemCount) {
            Pmi95CoordinateSystem(r.readI32(), r.readVec3F32(), r.readVec3F32(), r.readVec3F32())
        }
    return Pmi95Entities(
        dimensions,
        notes,
        datumFeatureSymbols,
        datumTargets,
        featureControlFrames,
        lineWelds,
        spotWelds,
        surfaceFinishes,
        measurementPoints,
        locators,
        referenceGeometry,
        designGroups,
        coordinateSystems,
    )
}

private fun writePmi95Entities(
    w: ByteWriter,
    entities: Pmi95Entities,
) {
    write2dCollection(w, entities.dimensions)
    w.writeI32(entities.notes.size)
    for (note in entities.notes) {
        writePmi952dData(w, note.data2d)
        note.urlFlag?.let { w.writeU32(it.toUInt()) }
    }
    write2dCollection(w, entities.datumFeatureSymbols)
    write2dCollection(w, entities.datumTargets)
    write2dCollection(w, entities.featureControlFrames)
    write2dCollection(w, entities.lineWelds)
    w.writeI32(entities.spotWelds.size)
    for (weld in entities.spotWelds) {
        writePmi953dData(w, weld.data3d)
        weld.geometry?.let {
            w.writeVec3F32(it.weldPoint)
            w.writeVec3F32(it.approachDirection)
            w.writeVec3F32(it.clampingDirection)
            w.writeVec3F32(it.normalDirection)
        }
    }
    write2dCollection(w, entities.surfaceFinishes)
    w.writeI32(entities.measurementPoints.size)
    for (point in entities.measurementPoints) {
        writePmi953dData(w, point.data3d)
        point.geometry?.let {
            w.writeVec3F32(it.location)
            w.writeVec3F32(it.measurementDirection)
            w.writeVec3F32(it.coordinateDirection)
            w.writeVec3F32(it.normalDirection)
        }
    }
    write2dCollection(w, entities.locators)
    w.writeI32(entities.referenceGeometry.size)
    for (geometry in entities.referenceGeometry) writePmi953dData(w, geometry)
    w.writeI32(entities.designGroups.size)
    for (group in entities.designGroups) {
        w.writeI32(group.nameStringId)
        group.attributes?.let { attributes ->
            w.writeI32(attributes.size)
            for (attribute in attributes) writePmiDesignGroupAttribute(w, attribute)
        }
    }
    w.writeI32(entities.coordinateSystems.size)
    for (system in entities.coordinateSystems) {
        w.writeI32(system.nameStringId)
        w.writeVec3F32(system.origin)
        w.writeVec3F32(system.xAxisPoint)
        w.writeVec3F32(system.yAxisPoint)
    }
}

// ---------------------------------------------------------------------------
// Associations, user attributes, string table, model views, generic entities
// ---------------------------------------------------------------------------

// spec: 9.5 Figure 162
private fun readPmi95Associations(
    r: ByteReader,
    g: Pmi95Gates,
): List<Pmi95Association> {
    val count = r.readCount("PMI Association", 12)
    return List(count) {
        val sourceData = r.readI32()
        val destinationData = r.readI32()
        val reasonCode = r.readI32()
        val sourceOwner = if (g.pmiVersionAbove5) r.readI32() else null
        val destinationOwner = if (g.pmiVersionAbove5) r.readI32() else null
        Pmi95Association(sourceData, destinationData, reasonCode, sourceOwner, destinationOwner)
    }
}

private fun writePmi95Associations(
    w: ByteWriter,
    associations: List<Pmi95Association>,
) {
    w.writeI32(associations.size)
    for (a in associations) {
        w.writeI32(a.sourceData)
        w.writeI32(a.destinationData)
        w.writeI32(a.reasonCode)
        a.sourceOwningEntityStringId?.let { w.writeI32(it) }
        a.destinationOwningEntityStringId?.let { w.writeI32(it) }
    }
}

/**
 * PMI String Table (9.5 Figure 164). The one delta that would desynchronize silently: 9.5 says
 * `String : PMI String`, and §7.1.1 defines `String` as `I32 Count + Count × U8`, where v10's
 * Figure 115 says `MbString` (`I32 Count + Count × U16`). Every PMI string in a JT 9.5 file is
 * single-byte.
 *
 * spec: 9.5 Figure 164
 */
private fun readPmi95StringTable(r: ByteReader): List<String> {
    val count = r.readCount("PMI String", 4)
    return List(count) { r.readString() }
}

private fun writePmi95StringTable(
    w: ByteWriter,
    strings: List<String>,
) {
    w.writeI32(strings.size)
    for (s in strings) w.writeString(s)
}

// spec: 9.5 Figure 168
private fun readPmi95PropertyAtom(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi95PropertyAtom {
    val value = r.readMbString()
    val hidden = if (g.pmiVersionAbove6) r.readU32().toInt() else null
    if (hidden != null && hidden != 0 && hidden != 1) {
        throw JtFormatException("PMI Property Atom Hidden Flag $hidden is not a §7.2.6.2.6.1.1 value")
    }
    return Pmi95PropertyAtom(value, hidden)
}

private fun writePmi95PropertyAtom(
    w: ByteWriter,
    atom: Pmi95PropertyAtom,
) {
    w.writeMbString(atom.value)
    atom.hiddenFlag?.let { w.writeU32(it.toUInt()) }
}

// spec: 9.5 Figure 167
private fun readPmi95Property(
    r: ByteReader,
    g: Pmi95Gates,
): Pmi95Property = Pmi95Property(readPmi95PropertyAtom(r, g), readPmi95PropertyAtom(r, g))

private fun writePmi95Property(
    w: ByteWriter,
    property: Pmi95Property,
) {
    writePmi95PropertyAtom(w, property.key)
    writePmi95PropertyAtom(w, property.value)
}

// spec: 9.5 Figure 165
private fun readPmi95ModelViews(
    r: ByteReader,
    stringCount: Int,
): List<Pmi95ModelView> {
    val count = r.readCount("PMI Model View", 76)
    return List(count) {
        val eyeDirection = r.readVec3F32()
        val angle = r.readF32()
        val eyePosition = r.readVec3F32()
        val targetPoint = r.readVec3F32()
        val viewAngle = r.readVec3F32()
        val viewportDiameter = r.readF32()
        val reservedF32 = r.readF32()
        val reservedI32 = r.readI32()
        val activeFlag = r.readI32()
        val viewId = r.readI32()
        val viewNameStringId = r.readI32()
        checkStringId(viewNameStringId, stringCount, "Model View Name")
        Pmi95ModelView(
            eyeDirection,
            angle,
            eyePosition,
            targetPoint,
            viewAngle,
            viewportDiameter,
            reservedF32,
            reservedI32,
            activeFlag,
            viewId,
            viewNameStringId,
        )
    }
}

private fun writePmi95ModelViews(
    w: ByteWriter,
    views: List<Pmi95ModelView>,
) {
    w.writeI32(views.size)
    for (view in views) {
        w.writeVec3F32(view.eyeDirection)
        w.writeF32(view.angle)
        w.writeVec3F32(view.eyePosition)
        w.writeVec3F32(view.targetPoint)
        w.writeVec3F32(view.viewAngle)
        w.writeF32(view.viewportDiameter)
        w.writeF32(view.reservedF32)
        w.writeI32(view.reservedI32)
        w.writeI32(view.activeFlag)
        w.writeI32(view.viewId)
        w.writeI32(view.viewNameStringId)
    }
}

// spec: 9.5 Figure 166
private fun readPmi95GenericEntities(
    r: ByteReader,
    g: Pmi95Gates,
    stringCount: Int,
): List<Pmi95GenericEntity> {
    val count = r.readCount("Generic PMI Entity", MIN_2D_DATA_BYTES + 16)
    return List(count) {
        val data2d = readPmi952dData(r, g)
        val propertyCount = r.readCount("Generic PMI Entity Property", 8)
        val properties = List(propertyCount) { readPmi95Property(r, g) }
        val entityTypeNameStringId = r.readI32()
        val parentTypeNameStringId = r.readI32()
        checkStringId(entityTypeNameStringId, stringCount, "Generic PMI Entity Type Name")
        checkStringId(parentTypeNameStringId, stringCount, "Generic PMI Parent Type Name")
        val entityType = r.readU16().toInt()
        val parentType = r.readU16().toInt()
        val userFlags = if (g.pmiVersionAbove6) r.readU16().toInt() else null
        Pmi95GenericEntity(
            data2d,
            properties,
            entityTypeNameStringId,
            parentTypeNameStringId,
            entityType,
            parentType,
            userFlags,
        )
    }
}

private fun writePmi95GenericEntities(
    w: ByteWriter,
    entities: List<Pmi95GenericEntity>,
) {
    w.writeI32(entities.size)
    for (entity in entities) {
        writePmi952dData(w, entity.data2d)
        w.writeI32(entity.properties.size)
        for (property in entity.properties) writePmi95Property(w, property)
        w.writeI32(entity.entityTypeNameStringId)
        w.writeI32(entity.parentTypeNameStringId)
        w.writeU16(entity.entityType.toUShort())
        w.writeU16(entity.parentType.toUShort())
        entity.userFlags?.let { w.writeU16(it.toUShort()) }
    }
}

// ---------------------------------------------------------------------------
// PMI Polygon Data (Figure 170) and the fonts
// ---------------------------------------------------------------------------

/**
 * PMI Polygon Data (9.5 Figure 170). Two readings of the figure are impossible to satisfy at
 * once and the prose settles both: the `NormalBinding == 1` box is labelled `VecF32: Vertices`
 * where p.201 says *Normals* (same width either way — a labelling slip), and the
 * `TextureBinding == 1` box is labelled `I16 : Reserved Field` where p.201 says
 * `VecF32: Texture Coords` sized "number of vertices multiplied by 2". The prose is internally
 * consistent and matches v10's Figure 130; the figure's `I16` is not. The prose is followed and
 * every element that actually sets TextureBinding is **counted and named** by
 * `PMI_POLYGON_TEXTURE_BINDING_UNSETTLED`, so the first fixture that exercises the branch
 * settles it rather than passing silently.
 *
 * spec: 9.5 Figure 170
 */
private fun readPmi95PolygonData(
    r: ByteReader,
    report: Pmi95Report,
): Pmi95PolygonData {
    val version = r.readI16().toInt()
    val reservedField = r.readI32()
    val vertexCounts = r.readVecI32()
    val elements =
        vertexCounts.filter { it > 0 }.map { vertexCount ->
            val normalBinding = r.readI32()
            val colourBinding = r.readI32()
            val textureBinding = r.readI32()
            val dimension = r.readI32()
            if (textureBinding == 1) report.textureBindingElements++
            PmiPolygonDataElement(
                vertexCount = vertexCount,
                colourBinding = colourBinding,
                normalBinding = normalBinding,
                textureBinding = textureBinding,
                polygonDimension = dimension,
                primitiveTypes = r.readVecI32(),
                primitiveIndices = r.readVecI32(),
                vertexIndices = r.readVecI32(),
                vertices = r.readVecF32(),
                normals = if (normalBinding == 1) r.readVecF32() else null,
                colours = if (colourBinding == 1) r.readVecF32() else null,
                textureCoords = if (textureBinding == 1) r.readVecF32() else null,
            )
        }
    return Pmi95PolygonData(version, reservedField, vertexCounts, elements)
}

private fun writePmi95PolygonData(
    w: ByteWriter,
    data: Pmi95PolygonData,
) {
    w.writeI16(data.version.toShort())
    w.writeI32(data.reservedField)
    w.writeVecI32(data.vertexCounts)
    for (element in data.elements) {
        w.writeI32(element.normalBinding)
        w.writeI32(element.colourBinding)
        w.writeI32(element.textureBinding)
        w.writeI32(element.polygonDimension)
        w.writeVecI32(element.primitiveTypes)
        w.writeVecI32(element.primitiveIndices)
        w.writeVecI32(element.vertexIndices)
        w.writeVecF32(element.vertices)
        element.normals?.let { w.writeVecF32(it) }
        element.colours?.let { w.writeVecF32(it) }
        element.textureCoords?.let { w.writeVecF32(it) }
    }
}

// spec: 9.5 Figure 136 (the font block)
private fun readPmi95Fonts(
    r: ByteReader,
    report: Pmi95Report,
): List<Pmi95Font> {
    val count = r.readCount("PMI font", 18)
    return List(count) {
        val name = r.readString()
        val characterSet = r.readVecI32()
        Pmi95Font(name, characterSet, readPmi95PolygonData(r, report))
    }
}

private fun writePmi95Fonts(
    w: ByteWriter,
    fonts: List<Pmi95Font>,
) {
    w.writeI32(fonts.size)
    for (font in fonts) {
        w.writeString(font.name)
        w.writeVecI32(font.characterSet)
        writePmi95PolygonData(w, font.glyphs)
    }
}

// ---------------------------------------------------------------------------
// PMI CAD Tag Data (Figure 169 + 9.5 §8.1.16 Figure 242)
// ---------------------------------------------------------------------------

/** `I16 Version`, `I32 Data Length`, `I32 inner Version`, `I32 CAD Tag Count` (Figure 242). */
private const val CAD_TAG_DATA_HEADER_BYTES = 12

// spec: 9.5 Figure 169
private fun readPmi95CadTagData(
    r: ByteReader,
    expectedIndexCount: Int,
    report: Pmi95Report,
): Pmi95CadTagData {
    val count = r.readCount("CAD Tag Index", 4)
    if (count != expectedIndexCount) {
        throw JtFormatException(
            "CAD Tag Index Count $count is not the summed PMI entity count $expectedIndexCount (§7.2.6.2.7 formula)",
        )
    }
    val indices = List(count) { r.readI32() }
    val version = r.readI16().toInt()
    // DESIGN.md delta 34: the Data Length spans the field itself through the end of the
    // collection. 9.5 §8.1.16 words the field exactly as v10's Figure 154 does, and it is the
    // only extent signal the coded body has — a wrong reading cannot pass silently, because a
    // JT 9.5 PMI Manager ends at its font loop and the framed body must be consumed exactly.
    val dataLength = r.readI32()
    if (dataLength < CAD_TAG_DATA_HEADER_BYTES || dataLength - 4 > r.remaining) {
        throw JtFormatException("Compressed CAD Tag Data Length $dataLength does not fit the remaining ${r.remaining} bytes")
    }
    val innerVersion = r.readI32()
    val cadTagCount = r.readI32()
    val coded = r.readBytes(dataLength - CAD_TAG_DATA_HEADER_BYTES)
    if (cadTagCount > 0) {
        report.cadTagRefusal =
            "JT 9.5 §8.1.16 Figure 242 differs from the v10 collection (I16 version, a CAD Tag Count gate, " +
            "Int32CDP2/Lag1 vectors and two Int32 halves for the 64-bit tags); $cadTagCount tags kept as " +
            "${coded.size} coded bytes"
    } else if (coded.isNotEmpty()) {
        report.cadTagRefusal =
            "Compressed CAD Tag Data declares 0 CAD tags but carries ${coded.size} bytes past its header"
    }
    return Pmi95CadTagData(indices, version, innerVersion, cadTagCount, coded.toBytes())
}

private fun writePmi95CadTagData(
    w: ByteWriter,
    data: Pmi95CadTagData,
) {
    w.writeVecI32(data.indices)
    w.writeI16(data.version.toShort())
    w.writeI32(CAD_TAG_DATA_HEADER_BYTES + data.codedData.size)
    w.writeI32(data.innerVersion)
    w.writeI32(data.cadTagCount)
    w.writeBytes(data.codedData)
}

// ---------------------------------------------------------------------------
// The element
// ---------------------------------------------------------------------------

/** 9.5 §7.2.6.2: the documented `I16 Version Number` values of the element itself. */
private val ELEMENT_VERSIONS = 1..2

/** 9.5 §7.2.6.2: the documented `I16 PMI Version Number` values. */
private val PMI_VERSIONS = 3..8

/**
 * Reads a JT 9.5 PMI Manager Meta Data Element from [r], which must be positioned at the start
 * of the element body and bounded to it.
 *
 * Figure 145's zero-count ambiguity is resolved by **arbitration, not assumption**: 9.5's
 * element ends at its font loop, so exact consumption of the framed body decides which of the
 * two forms the producer wrote. [Pmi95TextPolylineForm.FIGURE] is tried first and
 * [Pmi95TextPolylineForm.EMPTY_VECTOR] only if it does not account for the body; the winner is
 * recorded on the element so re-serialization is byte-identical either way.
 *
 * spec: 9.5 Figure 136
 */
internal fun readPmi95ManagerMetaDataElement(r: ByteReader): Pmi95Decode {
    val start = r.position
    var firstFailure: JtFormatException? = null
    for (form in Pmi95TextPolylineForm.values()) {
        r.position = start
        val report = Pmi95Report()
        try {
            val element = readPmi95Manager(r, form, report)
            if (r.remaining != 0) {
                throw JtFormatException(
                    "${r.remaining} bytes of the element body were not consumed (Figure 136 ends at the font loop)",
                )
            }
            return Pmi95Decode(element, report.cadTagRefusal, report.textureBindingElements)
        } catch (e: JtFormatException) {
            if (firstFailure == null) firstFailure = e
        }
    }
    throw firstFailure ?: JtFormatException("JT 9.5 PMI Manager did not decode")
}

private fun readPmi95Manager(
    r: ByteReader,
    form: Pmi95TextPolylineForm,
    report: Pmi95Report,
): Pmi95ManagerMetaDataElement {
    val objectId = r.readMetaElementHeader()
    val version = r.readI16().toInt()
    if (version !in ELEMENT_VERSIONS) {
        throw JtFormatException("PMI Manager Version Number $version is not a §7.2.6.2 value ($ELEMENT_VERSIONS)")
    }
    val pmiVersion = r.readI16().toInt()
    if (pmiVersion !in PMI_VERSIONS) {
        throw JtFormatException("PMI Version Number $pmiVersion is not a §7.2.6.2 value ($PMI_VERSIONS)")
    }
    val gates = Pmi95Gates(pmiVersion, form)
    val reservedField = r.readI16().toInt()
    val entities = readPmi95Entities(r, gates)
    val associations = readPmi95Associations(r, gates)
    val userAttributes = readPmiUserAttributes(r)
    val stringTable = readPmi95StringTable(r)
    val stringCount = stringTable.size
    checkEntityStringIds(entities, stringCount)
    for (association in associations) {
        association.sourceOwningEntityStringId?.let { checkStringId(it, stringCount, "Association Source Owning Entity") }
        association.destinationOwningEntityStringId?.let {
            checkStringId(it, stringCount, "Association Destination Owning Entity")
        }
    }
    for (attribute in userAttributes) {
        checkStringId(attribute.keyStringId, stringCount, "User Attribute Key")
        checkStringId(attribute.valueStringId, stringCount, "User Attribute Value")
    }
    val modelViews = if (gates.pmiVersionAbove5) readPmi95ModelViews(r, stringCount) else null
    val genericEntities = if (gates.pmiVersionAbove5) readPmi95GenericEntities(r, gates, stringCount) else null
    var cadTagsFlag: Int? = null
    var cadTagData: Pmi95CadTagData? = null
    if (gates.pmiVersionAbove7) {
        cadTagsFlag = r.readU32().toInt()
        if (cadTagsFlag == 1) {
            val expected =
                entities.lineWelds.size + entities.spotWelds.size + entities.surfaceFinishes.size +
                    entities.measurementPoints.size + entities.referenceGeometry.size +
                    entities.datumTargets.size + entities.featureControlFrames.size +
                    entities.locators.size + entities.dimensions.size +
                    entities.datumFeatureSymbols.size + entities.notes.size +
                    (modelViews?.size ?: 0) + entities.designGroups.size +
                    entities.coordinateSystems.size + (genericEntities?.size ?: 0)
            cadTagData = readPmi95CadTagData(r, expected, report)
        }
    }
    val tail =
        if (version > 1) {
            val properties = List(modelViews?.size ?: 0) { readPmi95Property(r, gates) }
            val polygonData = readPmi95PolygonData(r, report)
            Pmi95ManagerTail(properties, polygonData, readPmi95Fonts(r, report))
        } else {
            null
        }
    return Pmi95ManagerMetaDataElement(
        objectId,
        version,
        pmiVersion,
        reservedField,
        entities,
        associations,
        userAttributes,
        stringTable,
        modelViews,
        genericEntities,
        cadTagsFlag,
        cadTagData,
        tail,
        form,
    )
}

internal fun writePmi95ManagerMetaDataElement(
    w: ByteWriter,
    element: Pmi95ManagerMetaDataElement,
) {
    w.writeMetaElementHeader(element.objectId)
    w.writeI16(element.version.toShort())
    w.writeI16(element.pmiVersion.toShort())
    w.writeI16(element.reservedField.toShort())
    writePmi95Entities(w, element.entities)
    writePmi95Associations(w, element.associations)
    writePmiUserAttributes(w, element.userAttributes)
    writePmi95StringTable(w, element.stringTable)
    element.modelViews?.let { writePmi95ModelViews(w, it) }
    element.genericEntities?.let { writePmi95GenericEntities(w, it) }
    element.cadTagsFlag?.let { w.writeU32(it.toUInt()) }
    element.cadTagData?.let { writePmi95CadTagData(w, it) }
    element.tail?.let { tail ->
        for (property in tail.modelViewProperties) writePmi95Property(w, property)
        writePmi95PolygonData(w, tail.polygonData)
        writePmi95Fonts(w, tail.fonts)
    }
}

/** Every String ID Figure 137's thirteen collections can carry, against the PMI String Table. */
private fun checkEntityStringIds(
    entities: Pmi95Entities,
    stringCount: Int,
) {
    fun check2d(data: Pmi952dData) {
        for (text in data.texts) checkStringId(text.stringId, stringCount, "2D Text")
    }

    // PMI 3D Data carries no 2D Text Data, so its String ID is the only one it can hold.
    fun check3d(data: Pmi953dData) = checkStringId(data.stringId, stringCount, "PMI 3D Data")

    for (
    list in
    listOf(
        entities.dimensions,
        entities.datumFeatureSymbols,
        entities.datumTargets,
        entities.featureControlFrames,
        entities.lineWelds,
        entities.surfaceFinishes,
        entities.locators,
    )
    ) {
        for (data in list) check2d(data)
    }
    for (note in entities.notes) check2d(note.data2d)
    for (weld in entities.spotWelds) check3d(weld.data3d)
    for (point in entities.measurementPoints) check3d(point.data3d)
    for (geometry in entities.referenceGeometry) check3d(geometry)
    for (group in entities.designGroups) {
        checkStringId(group.nameStringId, stringCount, "Design Group Name")
        for (attribute in group.attributes.orEmpty()) {
            checkStringId(attribute.labelStringId, stringCount, "Design Group Attribute Label")
            checkStringId(attribute.descriptionStringId, stringCount, "Design Group Attribute Description")
            val value = attribute.value
            if (value is PmiDesignGroupAttributeValue.StringId) {
                checkStringId(value.stringId, stringCount, "Design Group Attribute Value")
            }
        }
    }
    for (system in entities.coordinateSystems) {
        checkStringId(system.nameStringId, stringCount, "Coordinate System Name")
    }
}
