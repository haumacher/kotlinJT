package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.encoding.CompressedCadTagData
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.readVec3F32
import de.haumacher.kotlinjt.lsg.writeVec3F32

/**
 * Table 7's Object Base Type of every meta data element: 9 ("JtBase"). Both the Property
 * Proxy and the PMI Manager elements of the NIST fixture carry it.
 */
private const val BASE_TYPE_JT_BASE = 9

/**
 * Version Number fields are I16 in JT 9 and one byte in JT 10 — the pervasive generational
 * delta (DESIGN.md delta 6). The v9.5 reference's own Figure 134 confirms it for the Property
 * Proxy element.
 */
internal fun ByteReader.readMetaVersionNumber(generation: LsgGeneration): Int =
    when (generation) {
        LsgGeneration.V9 -> readI16().toInt()
        LsgGeneration.V10, LsgGeneration.V10_5 -> readU8().toInt()
    }

internal fun ByteWriter.writeMetaVersionNumber(
    generation: LsgGeneration,
    value: Int,
) {
    when (generation) {
        LsgGeneration.V9 -> writeI16(value.toShort())
        LsgGeneration.V10, LsgGeneration.V10_5 -> writeU8(value.toUByte())
    }
}

/** Reads an element header: the Object Base Type byte plus the I32 object id. */
private fun ByteReader.readMetaElementHeader(): Int {
    val baseType = readU8().toInt()
    if (baseType != BASE_TYPE_JT_BASE) {
        throw JtFormatException("object base type $baseType is not the meta data base type $BASE_TYPE_JT_BASE")
    }
    return readI32()
}

private fun ByteWriter.writeMetaElementHeader(objectId: Int) {
    writeU8(BASE_TYPE_JT_BASE.toUByte())
    writeI32(objectId)
}

/** Reads a count that is about to size a list, bounding it against the remaining input. */
private fun ByteReader.readCount(
    what: String,
    bytesPerEntry: Int,
): Int {
    val count = readI32()
    if (count < 0 || count.toLong() * bytesPerEntry > remaining) {
        throw JtFormatException("$what count $count does not fit the remaining $remaining bytes")
    }
    return count
}

private fun ByteReader.readVecI32(): List<Int> {
    val count = readCount("VecI32", 4)
    return List(count) { readI32() }
}

private fun ByteWriter.writeVecI32(values: List<Int>) {
    writeI32(values.size)
    for (value in values) writeI32(value)
}

private fun ByteReader.readVecI16(): List<Short> {
    val count = readCount("VecI16", 2)
    return List(count) { readI16() }
}

private fun ByteWriter.writeVecI16(values: List<Short>) {
    writeI32(values.size)
    for (value in values) writeI16(value)
}

private fun ByteReader.readVecU16(): List<Int> {
    val count = readCount("VecU16", 2)
    return List(count) { readU16().toInt() }
}

private fun ByteWriter.writeVecU16(values: List<Int>) {
    writeI32(values.size)
    for (value in values) writeU16(value.toUShort())
}

private fun ByteReader.readVecF32(): List<Float> {
    val count = readCount("VecF32", 4)
    return List(count) { readF32() }
}

private fun ByteWriter.writeVecF32(values: List<Float>) {
    writeI32(values.size)
    for (value in values) writeF32(value)
}

// ---------------------------------------------------------------------------
// Property Proxy Meta Data Element (Figures 108/109)
// ---------------------------------------------------------------------------

// spec: Figure 109
internal fun ByteReader.readJtDate(): JtDate =
    JtDate(
        readI16().toInt(),
        readI16().toInt(),
        readI16().toInt(),
        readI16().toInt(),
        readI16().toInt(),
        readI16().toInt(),
    )

internal fun ByteWriter.writeJtDate(date: JtDate) {
    writeI16(date.year.toShort())
    writeI16(date.month.toShort())
    writeI16(date.day.toShort())
    writeI16(date.hour.toShort())
    writeI16(date.minute.toShort())
    writeI16(date.second.toShort())
}

/**
 * The outcome of a Property Proxy decode that met an unrecognized Property Value Type: the
 * type code, so the caller can name it in a note.
 */
internal class UnrecognizedValueType(
    val key: String,
    val typeCode: Int,
)

// spec: Figure 108
internal fun readPropertyProxyMetaDataElement(
    r: ByteReader,
    generation: LsgGeneration,
    onUnrecognized: (UnrecognizedValueType) -> Unit,
): PropertyProxyMetaDataElement {
    val objectId = r.readMetaElementHeader()
    val version = r.readMetaVersionNumber(generation)
    val properties = mutableListOf<MetaProperty>()
    var terminated = false
    while (true) {
        val key = r.readMbString()
        if (key.isEmpty()) {
            terminated = true
            break
        }
        val typeCode = r.readU8().toInt()
        val value =
            when (typeCode) {
                0 -> MetaPropertyValue.None
                1 -> MetaPropertyValue.Text(r.readMbString())
                2 -> MetaPropertyValue.Integer(r.readI32())
                3 -> MetaPropertyValue.Real(r.readF32())
                4 -> MetaPropertyValue.Date(r.readJtDate())
                else -> MetaPropertyValue.Unrecognized(typeCode, r.readBytes(r.remaining).toBytes())
            }
        properties.add(MetaProperty(key, value))
        if (value is MetaPropertyValue.Unrecognized) {
            onUnrecognized(UnrecognizedValueType(key, typeCode))
            break
        }
    }
    return PropertyProxyMetaDataElement(objectId, version, properties, terminated)
}

internal fun writePropertyProxyMetaDataElement(
    w: ByteWriter,
    generation: LsgGeneration,
    element: PropertyProxyMetaDataElement,
) {
    w.writeMetaElementHeader(element.objectId)
    w.writeMetaVersionNumber(generation, element.version)
    for (property in element.properties) {
        w.writeMbString(property.key)
        when (val value = property.value) {
            MetaPropertyValue.None -> w.writeU8(0u)
            is MetaPropertyValue.Text -> {
                w.writeU8(1u)
                w.writeMbString(value.value)
            }
            is MetaPropertyValue.Integer -> {
                w.writeU8(2u)
                w.writeI32(value.value)
            }
            is MetaPropertyValue.Real -> {
                w.writeU8(3u)
                w.writeF32(value.value)
            }
            is MetaPropertyValue.Date -> {
                w.writeU8(4u)
                w.writeJtDate(value.value)
            }
            is MetaPropertyValue.Unrecognized -> {
                w.writeU8(value.typeCode.toUByte())
                w.writeBytes(value.remainder)
            }
        }
    }
    if (element.terminated) w.writeMbString("")
}

// ---------------------------------------------------------------------------
// PMI Manager Meta Data Element (Figures 110-131)
// ---------------------------------------------------------------------------

/**
 * The wire width of the Key PMI Property Atom's Hidden Flag (Figure 118). The reference says
 * U32; the NIST 10.5 bytes say one byte (DESIGN.md delta 32). Files declaring 10.0–10.4 keep
 * the documented layout until a fixture contradicts it — the same policy the 10.5 LSG deltas
 * follow.
 */
private fun ByteReader.readHiddenFlag(generation: LsgGeneration): Int =
    if (generation == LsgGeneration.V10_5) readU8().toInt() else readU32().toInt()

private fun ByteWriter.writeHiddenFlag(
    generation: LsgGeneration,
    value: Int,
) {
    if (generation == LsgGeneration.V10_5) writeU8(value.toUByte()) else writeU32(value.toUInt())
}

/** Validates a String ID against the PMI String Table: −1 (no string) or a valid index. */
private fun checkStringId(
    stringId: Int,
    stringCount: Int,
    what: String,
) {
    if (stringId < -1 || stringId >= stringCount) {
        throw JtFormatException("$what String ID $stringId is outside [-1, $stringCount)")
    }
}

// spec: Figure 118
private fun readPmiPropertyAtom(
    r: ByteReader,
    g: LsgGeneration,
): PmiPropertyAtom {
    val value = r.readMbString()
    val hidden = r.readHiddenFlag(g)
    if (hidden != 0 && hidden != 1) {
        throw JtFormatException("PMI Property Atom Hidden Flag $hidden is not a Table 59 value")
    }
    return PmiPropertyAtom(value, hidden)
}

private fun writePmiPropertyAtom(
    w: ByteWriter,
    g: LsgGeneration,
    atom: PmiPropertyAtom,
) {
    w.writeMbString(atom.value)
    w.writeHiddenFlag(g, atom.hiddenFlag)
}

// spec: Figure 117
private fun readPmiProperty(
    r: ByteReader,
    g: LsgGeneration,
): PmiProperty = PmiProperty(readPmiPropertyAtom(r, g), readPmiPropertyAtom(r, g))

private fun writePmiProperty(
    w: ByteWriter,
    g: LsgGeneration,
    property: PmiProperty,
) {
    writePmiPropertyAtom(w, g, property.key)
    writePmiPropertyAtom(w, g, property.value)
}

// spec: Figures 111/112
private fun readPmiDesignGroups(r: ByteReader): List<PmiDesignGroup> {
    val count = r.readCount("PMI Design Group", 8)
    return List(count) {
        val nameStringId = r.readI32()
        val attributeCount = r.readCount("Design Group Attribute", 12)
        val attributes =
            List(attributeCount) {
                val type = r.readI32()
                val value =
                    when (type) {
                        1 -> PmiDesignGroupAttributeValue.Integer(r.readI32())
                        2 -> PmiDesignGroupAttributeValue.Double(r.readF64())
                        3 -> PmiDesignGroupAttributeValue.StringId(r.readI32())
                        else -> throw JtFormatException("Design Group Attribute Type $type is not a Table 54 value")
                    }
                PmiDesignGroupAttribute(value, r.readI32(), r.readI32())
            }
        PmiDesignGroup(nameStringId, attributes)
    }
}

private fun writePmiDesignGroups(
    w: ByteWriter,
    groups: List<PmiDesignGroup>,
) {
    w.writeI32(groups.size)
    for (group in groups) {
        w.writeI32(group.nameStringId)
        w.writeI32(group.attributes.size)
        for (attribute in group.attributes) {
            when (val value = attribute.value) {
                is PmiDesignGroupAttributeValue.Integer -> {
                    w.writeI32(1)
                    w.writeI32(value.value)
                }
                is PmiDesignGroupAttributeValue.Double -> {
                    w.writeI32(2)
                    w.writeF64(value.value)
                }
                is PmiDesignGroupAttributeValue.StringId -> {
                    w.writeI32(3)
                    w.writeI32(value.stringId)
                }
            }
            w.writeI32(attribute.labelStringId)
            w.writeI32(attribute.descriptionStringId)
        }
    }
}

// spec: Figure 113
private fun readPmiAssociations(r: ByteReader): List<PmiAssociation> {
    val count = r.readCount("PMI Association", 20)
    return List(count) {
        PmiAssociation(r.readI32(), r.readI32(), r.readI32(), r.readI32(), r.readI32())
    }
}

private fun writePmiAssociations(
    w: ByteWriter,
    associations: List<PmiAssociation>,
) {
    w.writeI32(associations.size)
    for (a in associations) {
        w.writeI32(a.sourceData)
        w.writeI32(a.sourceOwningEntityStringId)
        w.writeI32(a.reasonCode)
        w.writeI32(a.destinationData)
        w.writeI32(a.destinationOwningEntityStringId)
    }
}

// spec: Figure 114
private fun readPmiUserAttributes(r: ByteReader): List<PmiUserAttribute> {
    val count = r.readCount("PMI User Attribute", 8)
    return List(count) { PmiUserAttribute(r.readI32(), r.readI32()) }
}

private fun writePmiUserAttributes(
    w: ByteWriter,
    attributes: List<PmiUserAttribute>,
) {
    w.writeI32(attributes.size)
    for (a in attributes) {
        w.writeI32(a.keyStringId)
        w.writeI32(a.valueStringId)
    }
}

// spec: Figure 115
private fun readPmiStringTable(r: ByteReader): List<String> {
    val count = r.readCount("PMI String", 4)
    return List(count) { r.readMbString() }
}

private fun writePmiStringTable(
    w: ByteWriter,
    strings: List<String>,
) {
    w.writeI32(strings.size)
    for (s in strings) w.writeMbString(s)
}

// spec: Figure 116
private fun readPmiModelViews(
    r: ByteReader,
    g: LsgGeneration,
    stringCount: Int,
): List<PmiModelView> {
    val count = r.readCount("PMI Model View", 80)
    return List(count) {
        val eyeDirection = r.readVec3F32()
        val angle = r.readF32()
        val eyePosition = r.readVec3F32()
        val targetPoint = r.readVec3F32()
        val viewAngle = r.readVec3F32()
        val viewportDiameter = r.readF32()
        val emptyF32 = r.readF32()
        val emptyI32 = r.readI32()
        val activeFlag = r.readI32()
        val viewId = r.readI32()
        val viewNameStringId = r.readI32()
        checkStringId(viewNameStringId, stringCount, "Model View Name")
        val propertyCount = r.readCount("PMI Model View Property", 10)
        val properties = List(propertyCount) { readPmiProperty(r, g) }
        PmiModelView(
            eyeDirection,
            angle,
            eyePosition,
            targetPoint,
            viewAngle,
            viewportDiameter,
            emptyF32,
            emptyI32,
            activeFlag,
            viewId,
            viewNameStringId,
            properties,
        )
    }
}

private fun writePmiModelViews(
    w: ByteWriter,
    g: LsgGeneration,
    views: List<PmiModelView>,
) {
    w.writeI32(views.size)
    for (view in views) {
        w.writeVec3F32(view.eyeDirection)
        w.writeF32(view.angle)
        w.writeVec3F32(view.eyePosition)
        w.writeVec3F32(view.targetPoint)
        w.writeVec3F32(view.viewAngle)
        w.writeF32(view.viewportDiameter)
        w.writeF32(view.emptyFieldF32)
        w.writeI32(view.emptyFieldI32)
        w.writeI32(view.activeFlag)
        w.writeI32(view.viewId)
        w.writeI32(view.viewNameStringId)
        w.writeI32(view.properties.size)
        for (property in view.properties) writePmiProperty(w, g, property)
    }
}

// spec: Figure 122
private fun readPmi2dReferenceFrame(r: ByteReader): Pmi2dReferenceFrame =
    Pmi2dReferenceFrame(r.readVec3F32(), r.readVec3F32(), r.readVec3F32())

private fun writePmi2dReferenceFrame(
    w: ByteWriter,
    frame: Pmi2dReferenceFrame,
) {
    w.writeVec3F32(frame.origin)
    w.writeVec3F32(frame.xAxisPoint)
    w.writeVec3F32(frame.yAxisPoint)
}

// spec: Figure 124
private fun readPmiTextBox(r: ByteReader): PmiTextBox =
    PmiTextBox(r.readF32(), r.readF32(), r.readF32(), r.readF32(), r.readF32(), r.readF32())

private fun writePmiTextBox(
    w: ByteWriter,
    box: PmiTextBox,
) {
    w.writeF32(box.originX)
    w.writeF32(box.originY)
    w.writeF32(box.lowerRightX)
    w.writeF32(box.lowerRightY)
    w.writeF32(box.upperLeftX)
    w.writeF32(box.upperLeftY)
}

// spec: Figure 126
private fun readPmiTextPolylineData(r: ByteReader): PmiTextPolylineData = PmiTextPolylineData(r.readVecI16(), r.readVecF32())

private fun writePmiTextPolylineData(
    w: ByteWriter,
    data: PmiTextPolylineData,
) {
    w.writeVecI16(data.segmentIndices)
    w.writeVecF32(data.vertexCoords)
}

// spec: Figure 128
private fun readPmiNonTextPolylineData(r: ByteReader): PmiNonTextPolylineData =
    PmiNonTextPolylineData(r.readVecI32(), r.readVecI16(), r.readVecI16(), r.readVecF32())

private fun writePmiNonTextPolylineData(
    w: ByteWriter,
    data: PmiNonTextPolylineData,
) {
    w.writeVecI32(data.segmentIndices)
    w.writeVecI16(data.types)
    w.writeVecI16(data.widths)
    w.writeVecF32(data.vertexCoords)
}

// spec: Figure 123
private fun readPmi2dText(
    r: ByteReader,
    stringCount: Int,
): Pmi2dText {
    val stringId = r.readI32()
    checkStringId(stringId, stringCount, "2D Text")
    return Pmi2dText(stringId, r.readI32(), r.readI32(), r.readF32(), readPmiTextBox(r), readPmiTextPolylineData(r))
}

private fun writePmi2dText(
    w: ByteWriter,
    text: Pmi2dText,
) {
    w.writeI32(text.stringId)
    w.writeI32(text.font)
    w.writeI32(text.emptyFieldI32)
    w.writeF32(text.emptyFieldF32)
    writePmiTextBox(w, text.textBox)
    writePmiTextPolylineData(w, text.polylines)
}

// spec: Figure 121
private fun readPmiBaseData(r: ByteReader): PmiBaseData {
    val userLabel = r.readI32()
    val frameFlag = r.readU8().toInt()
    val frame = if (frameFlag != 0) readPmi2dReferenceFrame(r) else null
    return PmiBaseData(userLabel, frameFlag, frame, r.readF32(), r.readU8().toInt())
}

private fun writePmiBaseData(
    w: ByteWriter,
    data: PmiBaseData,
) {
    w.writeI32(data.userLabel)
    w.writeU8(data.frameFlag.toUByte())
    data.referenceFrame?.let { writePmi2dReferenceFrame(w, it) }
    w.writeF32(data.textHeight)
    w.writeU8(data.symbolValidFlag.toUByte())
}

// spec: Figure 120
private fun readPmi2dData(
    r: ByteReader,
    stringCount: Int,
): Pmi2dData {
    val base = readPmiBaseData(r)
    val textCount = r.readCount("2D Text Data", 44)
    val texts = List(textCount) { readPmi2dText(r, stringCount) }
    return Pmi2dData(base, texts, readPmiNonTextPolylineData(r))
}

private fun writePmi2dData(
    w: ByteWriter,
    data: Pmi2dData,
) {
    writePmiBaseData(w, data.base)
    w.writeI32(data.texts.size)
    for (text in data.texts) writePmi2dText(w, text)
    writePmiNonTextPolylineData(w, data.nonTextPolylines)
}

// spec: Figure 119
private fun readGenericPmiEntities(
    r: ByteReader,
    g: LsgGeneration,
    stringCount: Int,
): List<GenericPmiEntity> {
    val count = r.readCount("Generic PMI Entity", 32)
    return List(count) {
        val data2d = readPmi2dData(r, stringCount)
        val propertyCount = r.readCount("Generic PMI Entity Property", 10)
        val properties = List(propertyCount) { readPmiProperty(r, g) }
        val entityTypeNameStringId = r.readI32()
        val parentTypeNameStringId = r.readI32()
        checkStringId(entityTypeNameStringId, stringCount, "Generic PMI Entity Type Name")
        checkStringId(parentTypeNameStringId, stringCount, "Generic PMI Parent Type Name")
        GenericPmiEntity(
            data2d,
            properties,
            entityTypeNameStringId,
            parentTypeNameStringId,
            r.readU16().toInt(),
            r.readU16().toInt(),
            r.readU16().toInt(),
        )
    }
}

private fun writeGenericPmiEntities(
    w: ByteWriter,
    g: LsgGeneration,
    entities: List<GenericPmiEntity>,
) {
    w.writeI32(entities.size)
    for (entity in entities) {
        writePmi2dData(w, entity.data2d)
        w.writeI32(entity.properties.size)
        for (property in entity.properties) writePmiProperty(w, g, property)
        w.writeI32(entity.entityTypeNameStringId)
        w.writeI32(entity.parentTypeNameStringId)
        w.writeU16(entity.entityType.toUShort())
        w.writeU16(entity.parentType.toUShort())
        w.writeU16(entity.userFlags.toUShort())
    }
}

// spec: Figure 130
private fun readPmiPolygonData(r: ByteReader): PmiPolygonData {
    val version = r.readU8().toInt()
    val elementCount = r.readCount("PolygonData element", 4)
    val vertexCounts = r.readVecI32()
    if (vertexCounts.size != elementCount) {
        throw JtFormatException("vNumVerts holds ${vertexCounts.size} entries, not the declared $elementCount")
    }
    val bindings = r.readVecI32()
    val dimensions = r.readVecI32()
    val nonEmpty = vertexCounts.count { it > 0 }
    if (bindings.size != 3 * nonEmpty) {
        throw JtFormatException("vBindings holds ${bindings.size} entries, not 3 per each of $nonEmpty non-empty elements")
    }
    if (dimensions.size != nonEmpty) {
        throw JtFormatException("vPolygonDimensions holds ${dimensions.size} entries, not one per each of $nonEmpty")
    }
    var index = 0
    val elements =
        vertexCounts.filter { it > 0 }.map { vertexCount ->
            val colourBinding = bindings[3 * index]
            val normalBinding = bindings[3 * index + 1]
            val textureBinding = bindings[3 * index + 2]
            val dimension = dimensions[index]
            index++
            PmiPolygonDataElement(
                vertexCount,
                colourBinding,
                normalBinding,
                textureBinding,
                dimension,
                r.readVecI32(),
                r.readVecI32(),
                r.readVecI32(),
                r.readVecF32(),
                if (normalBinding == 1) r.readVecF32() else null,
                if (colourBinding == 1) r.readVecF32() else null,
                if (textureBinding == 1) r.readVecF32() else null,
            )
        }
    return PmiPolygonData(version, vertexCounts, bindings, dimensions, elements)
}

private fun writePmiPolygonData(
    w: ByteWriter,
    data: PmiPolygonData,
) {
    w.writeU8(data.version.toUByte())
    w.writeI32(data.vertexCounts.size)
    w.writeVecI32(data.vertexCounts)
    w.writeVecI32(data.bindings)
    w.writeVecI32(data.polygonDimensions)
    for (element in data.elements) {
        w.writeVecI32(element.primitiveTypes)
        w.writeVecI32(element.primitiveIndices)
        w.writeVecI32(element.vertexIndices)
        w.writeVecF32(element.vertices)
        element.normals?.let { w.writeVecF32(it) }
        element.colours?.let { w.writeVecF32(it) }
        element.textureCoords?.let { w.writeVecF32(it) }
    }
}

// spec: Figure 154

/**
 * Compressed CAD Tag Data (Figure 154). The Data Length field the figure documents as "a
 * loader may use this to compute the end position" counts from the field itself to the end of
 * the collection — established on the NIST bodies, where it lands exactly on the next field
 * in all 14.
 */
private fun writeCompressedCadTagData(
    w: ByteWriter,
    data: CompressedCadTagData,
) = data.encode(w)

// spec: Figure 129
private fun readPmiCadTagData(
    r: ByteReader,
    expectedIndexCount: Int,
    externallyCompressed: Boolean,
    onCadTagsOpaque: (String) -> Unit,
): PmiCadTagData {
    val count = r.readCount("CAD Tag Index", 4)
    if (count != expectedIndexCount) {
        throw JtFormatException(
            "CAD Tag Index Count $count is not the summed PMI entity count $expectedIndexCount (§11.2.7 formula)",
        )
    }
    val indices = List(count) { r.readI32() }
    // The tag count is deliberately *not* constrained here. §11.2.7's formula governs the CAD
    // Tag *Index* Count (validated above); the number of tags in the collection is a different
    // number — NX 10.5 writes more tags than indices in ten of the NIST fixture's fourteen PMI
    // managers (twice as many in most of them), which matches §12.1.16's own statement that
    // "exactly what CAD entity types have CAD Tags ... is defined by users of this data
    // collection". Constraining it would refuse bodies that are perfectly well-formed.
    return PmiCadTagData(indices, CompressedCadTagData.read(r, null, externallyCompressed, onCadTagsOpaque))
}

private fun writePmiCadTagData(
    w: ByteWriter,
    data: PmiCadTagData,
) {
    w.writeVecI32(data.indices)
    writeCompressedCadTagData(w, data.compressed)
}

private fun readPmiFonts(r: ByteReader): List<PmiFont> {
    val count = r.readCount("PMI font", 9)
    return List(count) {
        val name = r.readMbString()
        val characterSet = r.readVecU16()
        PmiFont(name, characterSet, readPmiPolygonData(r))
    }
}

private fun writePmiFonts(
    w: ByteWriter,
    fonts: List<PmiFont>,
) {
    w.writeI32(fonts.size)
    for (font in fonts) {
        w.writeMbString(font.name)
        w.writeVecU16(font.characterSet)
        writePmiPolygonData(w, font.glyphs)
    }
}

/** The outcome of a PMI Manager decode that met undocumented trailing bytes. */
internal class UndocumentedTail(
    val byteCount: Int,
)

// spec: Figure 110
internal fun readPmiManagerMetaDataElement(
    r: ByteReader,
    generation: LsgGeneration,
    externallyCompressed: Boolean,
    onUndocumentedTail: (UndocumentedTail) -> Unit,
    onCadTagsOpaque: (String) -> Unit,
): PmiManagerMetaDataElement {
    val objectId = r.readMetaElementHeader()
    val version = r.readU8().toInt()
    val emptyField = r.readI16().toInt()
    val designGroups = readPmiDesignGroups(r)
    val associations = readPmiAssociations(r)
    val userAttributes = readPmiUserAttributes(r)
    val stringTable = readPmiStringTable(r)
    val stringCount = stringTable.size
    for (group in designGroups) {
        checkStringId(group.nameStringId, stringCount, "Design Group Name")
        for (attribute in group.attributes) {
            checkStringId(attribute.labelStringId, stringCount, "Design Group Attribute Label")
            checkStringId(attribute.descriptionStringId, stringCount, "Design Group Attribute Description")
            val value = attribute.value
            if (value is PmiDesignGroupAttributeValue.StringId) {
                checkStringId(value.stringId, stringCount, "Design Group Attribute Value")
            }
        }
    }
    for (association in associations) {
        checkStringId(association.sourceOwningEntityStringId, stringCount, "Association Source Owning Entity")
        checkStringId(association.destinationOwningEntityStringId, stringCount, "Association Destination Owning Entity")
    }
    for (attribute in userAttributes) {
        checkStringId(attribute.keyStringId, stringCount, "User Attribute Key")
        checkStringId(attribute.valueStringId, stringCount, "User Attribute Value")
    }
    val modelViews = readPmiModelViews(r, generation, stringCount)
    val genericEntities = readGenericPmiEntities(r, generation, stringCount)
    val polygonData = readPmiPolygonData(r)
    val cadTagsFlag = r.readU32().toInt()
    // The §11.2.7 formula: one CAD Tag index per entity of every PMI kind that supports them.
    // In the v10 wire only design groups, model views and generic entities exist.
    val expectedIndexCount = designGroups.size + modelViews.size + genericEntities.size
    val cadTagData =
        if (cadTagsFlag == 1) {
            readPmiCadTagData(r, expectedIndexCount, externallyCompressed, onCadTagsOpaque)
        } else {
            null
        }
    val fonts = readPmiFonts(r)
    val tail = r.readBytes(r.remaining).toBytes()
    if (tail.size > 0) onUndocumentedTail(UndocumentedTail(tail.size))
    return PmiManagerMetaDataElement(
        objectId,
        version,
        emptyField,
        designGroups,
        associations,
        userAttributes,
        stringTable,
        modelViews,
        genericEntities,
        polygonData,
        cadTagsFlag,
        cadTagData,
        fonts,
        tail,
    )
}

internal fun writePmiManagerMetaDataElement(
    w: ByteWriter,
    generation: LsgGeneration,
    element: PmiManagerMetaDataElement,
) {
    w.writeMetaElementHeader(element.objectId)
    w.writeU8(element.version.toUByte())
    w.writeI16(element.emptyField.toShort())
    writePmiDesignGroups(w, element.designGroups)
    writePmiAssociations(w, element.associations)
    writePmiUserAttributes(w, element.userAttributes)
    writePmiStringTable(w, element.stringTable)
    writePmiModelViews(w, generation, element.modelViews)
    writeGenericPmiEntities(w, generation, element.genericEntities)
    writePmiPolygonData(w, element.polygonData)
    w.writeU32(element.cadTagsFlag.toUInt())
    element.cadTagData?.let { writePmiCadTagData(w, it) }
    writePmiFonts(w, element.fonts)
    w.writeBytes(element.undocumentedTail)
}
