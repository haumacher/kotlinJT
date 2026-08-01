package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.JtSegment
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.ObjectTypeIds
import de.haumacher.kotlinjt.lsg.PropertyTable
import de.haumacher.kotlinjt.lsg.QuantizationParameters
import de.haumacher.kotlinjt.lsg.readBBoxF32
import de.haumacher.kotlinjt.lsg.readPropertyTable
import de.haumacher.kotlinjt.lsg.writeBBoxF32
import de.haumacher.kotlinjt.lsg.writePropertyTable

/**
 * The typed document model of a Shape LOD segment's element data (§7, Figure 80): the
 * element list closed by the end-of-elements marker, followed by the (usually empty) Property
 * Table identified in DESIGN.md. The losslessness guarantee is the same seam as the LSG
 * model: [decode] followed by [encode] reproduces the element data byte-identically, for
 * decoded and opaquely carried elements alike.
 */
data class ShapeLodDocument(
    val generation: LsgGeneration,
    val elements: List<ShapeLodElement>,
    /** Whether the element list was closed by the end-of-elements marker. */
    val elementsTerminated: Boolean,
    /** The trailing Property Table (Figure 78), `null` when missing (a note says why). */
    val propertyTable: PropertyTable?,
    /** Bytes after the recognized structure, preserved verbatim (empty on healthy streams). */
    val trailing: Bytes,
) {
    /** The tri-strip geometry of this LOD, `null` when no tri-strip element decoded typed. */
    val triStripGeometry: TriStripGeometry?
        get() = elements.filterIsInstance<TriStripGeometryCarrier>().firstOrNull()?.geometry

    /** The polyline geometry of this LOD, `null` when no polyline element decoded typed. */
    val polylineGeometry: PolylineGeometry?
        get() = elements.filterIsInstance<PolylineGeometryCarrier>().firstOrNull()?.geometry

    /** The point geometry of this LOD, `null` when no point set element decoded typed. */
    val pointGeometry: PointGeometry?
        get() = elements.filterIsInstance<PointGeometryCarrier>().firstOrNull()?.geometry

    /** Serializes the document back to element-stream bytes — the exact inverse of [decode]. */
    fun encode(order: Endianness): Bytes {
        val writer = ByteWriter(order)
        for (element in elements) {
            encodeShapeElementFrame(writer, generation, element)
        }
        if (elementsTerminated) {
            writer.writeI32(16)
            writer.writeGuid(Guid.END_OF_ELEMENTS)
        }
        propertyTable?.let { writePropertyTable(writer, it) }
        writer.writeBytes(trailing)
        return writer.toByteArray().toBytes()
    }

    companion object {
        /**
         * Decodes the element data of a Shape LOD segment. Never throws for content
         * problems: whatever does not decode is carried opaquely and named by a note.
         */
        fun decode(
            elementData: Bytes,
            version: JtVersion,
            order: Endianness,
        ): ShapeLodDecodeResult {
            val generation = LsgGeneration.of(version)
            val notes = mutableListOf<LoadNote>()
            val bytes = elementData.toByteArray()
            val reader = ByteReader(bytes, order)

            val elements = mutableListOf<ShapeLodElement>()
            var terminated = false
            while (true) {
                val location = "shape LOD element at offset ${reader.position}"
                val start = reader.position
                if (reader.remaining < 4 + 16) break
                val length = reader.readI32()
                if (length < 16 || length > reader.remaining) {
                    reader.position = start
                    break
                }
                val typeId = reader.readGuid()
                if (typeId == Guid.END_OF_ELEMENTS && length == 16) {
                    terminated = true
                    break
                }
                val body = reader.readBytes(length - 16)
                elements.add(decodeShapeElementBody(typeId, body, generation, order, notes, location))
            }

            var table: PropertyTable? = null
            var trailing = Bytes.EMPTY
            if (!terminated) {
                notes.add(
                    LoadNote.ShapeLodStructureUnrecognized(
                        "element list breaks off at offset ${reader.position} of ${bytes.size}",
                    ),
                )
                trailing = Bytes.of(bytes, reader.position, bytes.size)
            } else if (reader.remaining == 0) {
                notes.add(LoadNote.PropertyTableMissing("shape LOD stream ends after the element list"))
            } else {
                val tableStart = reader.position
                try {
                    table = readPropertyTable(reader)
                    if (reader.remaining != 0) {
                        throw JtFormatException("${reader.remaining} bytes follow the Property Table")
                    }
                } catch (e: JtFormatException) {
                    table = null
                    notes.add(LoadNote.PropertyTableUnrecognized(e.message ?: "parse failed"))
                    trailing = Bytes.of(bytes, tableStart, bytes.size)
                }
            }
            return ShapeLodDecodeResult(
                ShapeLodDocument(generation, elements, terminated, table, trailing),
                notes,
            )
        }
    }
}

/** The outcome of a Shape LOD document decode: the document plus the named refusals, if any. */
data class ShapeLodDecodeResult(
    val document: ShapeLodDocument,
    val notes: List<LoadNote>,
)

/**
 * Decodes the element data of [segment] into the typed Shape LOD document model; `null` when
 * the segment is not a shape segment or Layer 0 produced no element data (its notes say why).
 */
fun JtFile.decodeShapeLod(segment: JtSegment): ShapeLodDecodeResult? {
    if (!segment.isShapeLodSegment) return null
    val elementData = segment.elementData ?: return null
    return ShapeLodDocument.decode(elementData, header.version, header.byteOrder)
}

/** Whether this segment is a Shape or Shape LODn segment (Table 6 types 6-16). */
val JtSegment.isShapeLodSegment: Boolean
    get() {
        val kind = this.kind ?: return false
        return kind.code in SegmentKind.SHAPE.code..SegmentKind.SHAPE_LOD9.code
    }

/** All shape LOD segments of the file, in TOC order. */
fun JtFile.shapeLodSegments(): List<JtSegment> = segments.filter { it.isShapeLodSegment }

// ---------------------------------------------------------------------------
// Element codecs
// ---------------------------------------------------------------------------

/** Table 4 of the 9.5 reference / Table 7: the Object Base Type of Shape LOD elements. */
private const val BASE_TYPE_SHAPE_LOD = 4

private fun decodeShapeElementBody(
    typeId: Guid,
    body: ByteArray,
    generation: LsgGeneration,
    order: Endianness,
    notes: MutableList<LoadNote>,
    location: String,
): ShapeLodElement {
    val scannedBaseType = if (body.isNotEmpty()) body[0].toInt() and 0xFF else null

    fun opaque(): ShapeLodElement = OpaqueShapeLodElement(typeId, scannedBaseType, body.toBytes())

    val typeName = ObjectTypeIds.nameOf(typeId)
    if (typeName == null) {
        notes.add(LoadNote.UnknownElementType(typeId, location))
        return opaque()
    }
    // Wire layouts established per generation: the JT 9 layouts against the 9.5 fixture,
    // the v10 layouts against the NIST 10.5 fixture (DESIGN.md). Everything else is carried
    // opaquely — never guessed.
    val decoder: ((ByteReader) -> TypedShapeLodElement)? =
        when (generation) {
            LsgGeneration.V9 ->
                when (typeId) {
                    ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT -> ::readTriStripSetShapeLod
                    ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT -> ::readPolylineSetShapeLod
                    ObjectTypeIds.POINT_SET_SHAPE_LOD_ELEMENT -> ::readPointSetShapeLod
                    ObjectTypeIds.NULL_SHAPE_LOD_ELEMENT -> ::readNullShapeLod
                    else -> null
                }
            LsgGeneration.V10, LsgGeneration.V10_5 ->
                when (typeId) {
                    ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT -> ::readTriStripSetShapeLodV10
                    ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT -> ::readPolylineSetShapeLodV10
                    ObjectTypeIds.NULL_SHAPE_LOD_ELEMENT -> ::readNullShapeLodV10
                    else -> null
                }
        }
    if (decoder == null) {
        notes.add(LoadNote.ElementLayoutUnverified(typeId, typeName, generation.name, location))
        return opaque()
    }
    return try {
        val sub = ByteReader(body, order)
        val element = decoder(sub)
        if (sub.remaining != 0) {
            throw JtFormatException("${sub.remaining} bytes of the element body were not consumed")
        }
        element
    } catch (e: JtFormatException) {
        notes.add(LoadNote.ElementDecodeFailed(typeId, typeName, location, e.message ?: "decode failed"))
        opaque()
    }
}

internal fun encodeShapeElementFrame(
    w: ByteWriter,
    generation: LsgGeneration,
    element: ShapeLodElement,
) {
    val bodyWriter = ByteWriter(w.order)
    when (element) {
        is OpaqueShapeLodElement -> bodyWriter.writeBytes(element.body)
        is TriStripSetShapeLodElement -> {
            check(generation == LsgGeneration.V9) { "JT 9 tri-strip element in a $generation document" }
            writeTriStripSetShapeLod(bodyWriter, element)
        }
        is TriStripSetShapeLodElementV10 -> {
            check(generation != LsgGeneration.V9) { "v10 tri-strip element in a JT 9 document" }
            writeTriStripSetShapeLodV10(bodyWriter, element)
        }
        is PolylineSetShapeLodElementV10 -> {
            check(generation != LsgGeneration.V9) { "v10 polyline element in a JT 9 document" }
            writePolylineSetShapeLodV10(bodyWriter, element)
        }
        is PolylineSetShapeLodElement -> {
            check(generation == LsgGeneration.V9) { "JT 9 polyline element in a $generation document" }
            writePolylineSetShapeLod(bodyWriter, element)
        }
        is PointSetShapeLodElement -> {
            check(generation == LsgGeneration.V9) { "JT 9 point set element in a $generation document" }
            writePointSetShapeLod(bodyWriter, element)
        }
        is NullShapeLodElement -> writeNullShapeLod(bodyWriter, element, generation)
    }
    val body = bodyWriter.toByteArray()
    w.writeI32(16 + body.size)
    w.writeGuid(element.objectTypeId)
    w.writeBytes(body)
}

private fun readElementHeader(r: ByteReader): Int {
    val baseType = r.readU8().toInt()
    if (baseType != BASE_TYPE_SHAPE_LOD) {
        throw JtFormatException("object base type $baseType is not the Shape LOD base type $BASE_TYPE_SHAPE_LOD")
    }
    return r.readI32()
}

// --- Null Shape LOD Element (Figure 94; JT 9 layout per the 9.5 reference §7.2.2.1.6) ---

private fun readNullShapeLod(r: ByteReader): NullShapeLodElement {
    val objectId = readElementHeader(r)
    return NullShapeLodElement(objectId, r.readI16().toInt(), r.readBBoxF32())
}

/** The v10 layout (Figure 94: U8 version). Spec-derived; no fixture carries one. */
private fun readNullShapeLodV10(r: ByteReader): NullShapeLodElement {
    val objectId = readElementHeader(r)
    return NullShapeLodElement(objectId, r.readU8().toInt(), r.readBBoxF32())
}

private fun writeNullShapeLod(
    w: ByteWriter,
    element: NullShapeLodElement,
    generation: LsgGeneration,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    if (generation == LsgGeneration.V9) {
        w.writeI16(element.version.toShort())
    } else {
        w.writeU8(element.version.toUByte())
    }
    w.writeBBoxF32(element.untransformedBBox)
}

// --- Tri-Strip Set Shape LOD Element (Figure 81; JT 9 layout fixture-verified) ---

private fun readTriStripSetShapeLod(r: ByteReader): TriStripSetShapeLodElement {
    val objectId = readElementHeader(r)
    val baseShapeLodVersion = r.readI16().toInt()
    val vertexShapeLodVersion = r.readI16().toInt()
    val bindings = r.readU64()
    val topoMesh = TopoMeshLodData(r.readI16().toInt(), r.readI32())
    val topoVersion = r.readI16().toInt()
    val repData = readTopologicallyCompressedRepData(r)
    val auxiliary = readAuxiliaryVertexFieldData(r)
    val version = r.readI16().toInt()
    val geometry = buildTriStripGeometry(repData)
    return TriStripSetShapeLodElement(
        objectId, baseShapeLodVersion, vertexShapeLodVersion, bindings, topoMesh,
        topoVersion, repData, auxiliary, version, geometry,
    )
}

private fun writeTriStripSetShapeLod(
    w: ByteWriter,
    element: TriStripSetShapeLodElement,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    w.writeI16(element.baseShapeLodVersion.toShort())
    w.writeI16(element.vertexShapeLodVersion.toShort())
    w.writeU64(element.vertexBindings)
    w.writeI16(element.topoMesh.version.toShort())
    w.writeI32(element.topoMesh.vertexRecordsObjectId)
    w.writeI16(element.topologicallyCompressedVersion.toShort())
    writeTopologicallyCompressedRepData(w, element.repData)
    writeAuxiliaryVertexFieldData(w, element.auxiliaryVertexFields)
    w.writeI16(element.version.toShort())
}

// --- The Figure-92 auxiliary-vertex-field extension (shared by every JT 9 shape body) ---

/** `I16 : Version Number` + `U64 : Vertex Bindings` — the extension without an aux field list. */
private const val AUXILIARY_VERTEX_FIELD_PREFIX_BYTES = 2 + 8

/** The element's own trailing `I16 : Version Number`, the only field after the extension. */
private const val ELEMENT_VERSION_BYTES = 2

/**
 * Reads the Figure-92 *TopoMesh Compressed Rep Data V2* extension, `null` when the body ends
 * without it.
 *
 * Presence comes from the framed body's remaining length, not from a version comparison: the
 * element's own trailing version is the only field that follows, so the body has either
 * [ELEMENT_VERSION_BYTES] bytes left (no extension) or ten more (the extension without an
 * auxiliary field list). The two readings can never both fit, so nothing is guessed — and the
 * length is the *only* evidence for the topologically compressed container, whose Figure 88
 * draws no version branch at all (DESIGN.md delta 14). The container version is preserved in
 * the model but deliberately not used as a gate: a producer that writes version 1 with the
 * extension, or version 2 without it, still round-trips byte-exactly.
 */
private fun readAuxiliaryVertexFieldData(r: ByteReader): AuxiliaryVertexFieldData? {
    if (r.remaining == ELEMENT_VERSION_BYTES) return null
    if (r.remaining < ELEMENT_VERSION_BYTES + AUXILIARY_VERTEX_FIELD_PREFIX_BYTES) {
        throw JtFormatException(
            "${r.remaining} bytes trail the compressed representation; neither the bare element " +
                "version ($ELEMENT_VERSION_BYTES) nor the Figure 92 extension " +
                "(${ELEMENT_VERSION_BYTES + AUXILIARY_VERTEX_FIELD_PREFIX_BYTES})",
        )
    }
    val data = AuxiliaryVertexFieldData(r.readI16().toInt(), r.readU64())
    if (r.remaining != ELEMENT_VERSION_BYTES) {
        throw JtFormatException(
            "auxiliary vertex field list (Figure 92) occupies ${r.remaining - ELEMENT_VERSION_BYTES} " +
                "bytes; its layout is documented but exercised by no fixture" +
                if (data.declaresAuxiliaryFields) " (bindings 0x${data.vertexBindings.toString(16)})" else "",
        )
    }
    if (data.declaresAuxiliaryFields) {
        throw JtFormatException(
            "vertex bindings 0x${data.vertexBindings.toString(16)} declare auxiliary vertex fields, " +
                "but no auxiliary field list follows",
        )
    }
    return data
}

private fun writeAuxiliaryVertexFieldData(
    w: ByteWriter,
    data: AuxiliaryVertexFieldData?,
) {
    if (data == null) return
    w.writeI16(data.version.toShort())
    w.writeU64(data.vertexBindings)
}

// --- Topologically Compressed Rep Data (v10 Figure 92 = 9.5 Figure 89) ---

/**
 * Vertex Bindings bits (Table 48) beyond coordinates (bits 1-3) and normals (bit 4): colours,
 * texture coordinates, per-vertex flags and auxiliary fields. Their JT 9 vertex array layouts
 * are not established against any fixture — a shape declaring them refuses the typed decode.
 */
private val UNSUPPORTED_BINDING_MASK: ULong = 0xFUL.inv()

/**
 * The three 30/30/4-bit projections of the JT 9 8th attribute-mask context (9.5 Figure 89),
 * each lengthened to that context's own element count.
 *
 * The wire carries three packets, but the spec's composite hash (p.116) and the mask assembly
 * are both defined over the *derived* `VecU64` of context-8 masks: all three chunks are hashed
 * with count `anAttrMasks[7]`, whatever the packets themselves carry. Both corpus producers
 * happen to write mid/top packets of exactly that length, but a producer eliding an all-zero
 * upper chunk as an empty packet would compute the same hash over N zeros while a reader that
 * hashed the stored packets computed it over an empty array — Jenkins mixes the length in, so
 * the values differ and a conformant body would be **false-refused** (finding E-7). Deriving
 * the chunks here makes the two readings one.
 */
private fun context8Chunks(
    low: Int32Cdp,
    mid: Int32Cdp,
    top: Int32Cdp,
): Triple<IntArray, IntArray, IntArray> {
    val count = low.valueCount
    if (mid.valueCount > count || top.valueCount > count) {
        throw JtFormatException(
            "face attribute mask context 8 holds $count masks but its upper chunks carry " +
                "${mid.valueCount} / ${top.valueCount} values",
        )
    }
    val lowValues = low.values
    val midValues = mid.values
    val topValues = top.values
    return Triple(
        IntArray(count) { lowValues[it] and 0x3FFFFFFF },
        IntArray(count) { if (it < midValues.size) midValues[it] and 0x3FFFFFFF else 0 },
        IntArray(count) { if (it < topValues.size) topValues[it] and 0xF else 0 },
    )
}

private fun readTopologicallyCompressedRepData(r: ByteReader): TopologicallyCompressedRepData {
    val faceDegreePackets = List(8) { Int32Cdp.read(r) }
    val valencePacket = Int32Cdp.read(r)
    val groupPacket = Int32Cdp.read(r)
    val flagPacket = Int32Cdp.read(r)
    val maskPackets = List(8) { Int32Cdp.read(r) }
    val mask8Mid = Int32Cdp.read(r)
    val mask8Top = Int32Cdp.read(r)
    val highDegreeCount = r.readI32()
    if (highDegreeCount < 0 || highDegreeCount > r.remaining / 4) {
        throw JtFormatException("high-degree mask count $highDegreeCount does not fit the remaining ${r.remaining} bytes")
    }
    val highDegreeMasks = List(highDegreeCount) { r.readI32() }
    val splitFacePacket = Int32Cdp.read(r)
    val splitPositionPacket = Int32Cdp.read(r)
    val storedHash = r.readI32()

    val flags = unpackResiduals(flagPacket.values, Predictor.LAG1)
    val splitFaces = unpackResiduals(splitFacePacket.values, Predictor.LAG1)

    // Composite hash (9.5 §7.2.2.1.2.5, p.116) — verified so a codec defect or corrupt stream
    // refuses the decode instead of producing a broken mesh. The pseudo-code hashes the
    // *derived* mask arrays, not the stored packets: every context is masked to its 30 low
    // bits, and context 8's three projections are each hashed with the context's own element
    // count. Masking contexts 0-6 is provably a no-op (their context index is
    // min(7, degree - 2), so they hold rings of degree 2-8 — masks of at most 8 bits); it is
    // applied anyway so the code reads as the document does. The context-8 lengthening is not
    // cosmetic: see [context8Chunks].
    val (mask8Low, mask8MidChunk, mask8TopChunk) = context8Chunks(maskPackets[7], mask8Mid, mask8Top)
    var hash = 0
    for (packet in faceDegreePackets) hash = JtHash.hash32(packet.values.toIntArray(), hash)
    hash = JtHash.hash32(valencePacket.values.toIntArray(), hash)
    hash = JtHash.hash32(groupPacket.values.toIntArray(), hash)
    hash = JtHash.hash16(IntArray(flags.size) { flags[it] and 0xFFFF }, hash)
    for (i in 0 until 7) {
        val values = maskPackets[i].values
        hash = JtHash.hash32(IntArray(values.size) { values[it] and 0x3FFFFFFF }, hash)
    }
    hash = JtHash.hash32(mask8Low, hash)
    hash = JtHash.hash32(mask8MidChunk, hash)
    hash = JtHash.hash32(mask8TopChunk, hash)
    hash = JtHash.hash32(highDegreeMasks.toIntArray(), hash)
    hash = JtHash.hash32(splitFaces.toIntArray(), hash)
    hash = JtHash.hash32(splitPositionPacket.values.toIntArray(), hash)
    if (hash != storedHash) {
        throw JtFormatException("topology composite hash mismatch: stored $storedHash, computed $hash")
    }

    val vertexRecords = readTopologicallyCompressedVertexRecords(r)
    return TopologicallyCompressedRepData(
        faceDegreePackets, valencePacket, groupPacket, flagPacket, maskPackets,
        mask8Mid, mask8Top, highDegreeMasks, splitFacePacket, splitPositionPacket,
        storedHash, vertexRecords,
    )
}

private fun writeTopologicallyCompressedRepData(
    w: ByteWriter,
    data: TopologicallyCompressedRepData,
) {
    for (packet in data.faceDegrees) packet.encode(w)
    data.vertexValences.encode(w)
    data.vertexGroups.encode(w)
    data.vertexFlags.encode(w)
    for (packet in data.faceAttributeMasks) packet.encode(w)
    data.faceAttributeMask8Mid.encode(w)
    data.faceAttributeMask8Top.encode(w)
    w.writeI32(data.highDegreeFaceAttributeMasks.size)
    for (word in data.highDegreeFaceAttributeMasks) w.writeI32(word)
    data.splitFaceSymbols.encode(w)
    data.splitFacePositions.encode(w)
    w.writeI32(data.compositeHash)
    writeTopologicallyCompressedVertexRecords(w, data.vertexRecords)
}

private fun readTopologicallyCompressedVertexRecords(r: ByteReader): TopologicallyCompressedVertexRecords {
    val bindings = r.readU64()
    val quantization =
        QuantizationParameters(
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
        )
    val topologicalVertexCount = r.readI32()
    if (topologicalVertexCount < 0) throw JtFormatException("negative topological vertex count $topologicalVertexCount")
    if (topologicalVertexCount == 0) {
        return TopologicallyCompressedVertexRecords(bindings, quantization, 0, null, null, null)
    }
    val attributeCount = r.readI32()
    if (bindings and UNSUPPORTED_BINDING_MASK != 0UL) {
        throw JtFormatException(
            "vertex bindings 0x${bindings.toString(16)} declare colours, texture coordinates, flags or " +
                "auxiliary fields; their JT 9 layout is not established",
        )
    }
    val coordinates = if (bindings and 0x7UL != 0UL) CompressedVertexCoordinateArray.read(r) else null
    val normals = if (bindings and 0x8UL != 0UL) CompressedVertexNormalArray.read(r) else null
    if (coordinates != null && coordinates.uniqueVertexCount != topologicalVertexCount) {
        throw JtFormatException(
            "coordinate array carries ${coordinates.uniqueVertexCount} vertices, " +
                "vertex records declare $topologicalVertexCount",
        )
    }
    return TopologicallyCompressedVertexRecords(
        bindings,
        quantization,
        topologicalVertexCount,
        attributeCount,
        coordinates,
        normals,
    )
}

private fun writeTopologicallyCompressedVertexRecords(
    w: ByteWriter,
    records: TopologicallyCompressedVertexRecords,
) {
    w.writeU64(records.vertexBindings)
    w.writeU8(records.quantizationParameters.bitsPerVertex.toUByte())
    w.writeU8(records.quantizationParameters.normalBitsFactor.toUByte())
    w.writeU8(records.quantizationParameters.bitsPerTextureCoord.toUByte())
    w.writeU8(records.quantizationParameters.bitsPerColour.toUByte())
    w.writeI32(records.numberOfTopologicalVertices)
    if (records.numberOfTopologicalVertices == 0) return
    w.writeI32(records.numberOfVertexAttributes ?: 0)
    records.coordinates?.write(w)
    records.normals?.write(w)
    records.vertexFlags?.write(w)
}

// --- v10 element bodies (wire layouts NIST-10.5-verified; DESIGN.md) ---

private fun readNestedElementHeader(r: ByteReader): NestedElementHeader {
    val elementLength = r.readI32()
    // The nested element spans from its Object Type ID to the end of the rep data; the
    // outer element's trailing U8 version is the only byte after it.
    val expected = r.remaining - 1
    if (elementLength != expected) {
        throw JtFormatException("nested element length $elementLength does not span the remaining body ($expected)")
    }
    return NestedElementHeader(elementLength, r.readGuid(), r.readU8().toInt(), r.readI32())
}

private fun writeNestedElementHeader(
    w: ByteWriter,
    header: NestedElementHeader,
) {
    w.writeI32(header.elementLength)
    w.writeGuid(header.objectTypeId)
    w.writeU8(header.objectBaseType.toUByte())
    w.writeI32(header.objectId)
}

private fun readTriStripSetShapeLodV10(r: ByteReader): TriStripSetShapeLodElementV10 {
    val objectId = readElementHeader(r)
    val baseShapeLodVersion = r.readU8().toInt()
    val vertexShapeLodVersion = r.readU8().toInt()
    val bindings = r.readU64()
    val nestedHeader = readNestedElementHeader(r)
    val topoMesh = TopoMeshLodData(r.readU8().toInt(), r.readI32())
    val topoVersion = r.readU8().toInt()
    val repData = readTopologicallyCompressedRepDataV10(r)
    val version = r.readU8().toInt()
    val geometry = buildTriStripGeometryV10(repData)
    return TriStripSetShapeLodElementV10(
        objectId, baseShapeLodVersion, vertexShapeLodVersion, bindings, nestedHeader,
        topoMesh, topoVersion, repData, version, geometry,
    )
}

private fun writeTriStripSetShapeLodV10(
    w: ByteWriter,
    element: TriStripSetShapeLodElementV10,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    w.writeU8(element.baseShapeLodVersion.toUByte())
    w.writeU8(element.vertexShapeLodVersion.toUByte())
    w.writeU64(element.vertexBindings)
    writeNestedElementHeader(w, element.nestedHeader)
    w.writeU8(element.topoMesh.version.toUByte())
    w.writeI32(element.topoMesh.vertexRecordsObjectId)
    w.writeU8(element.topologicallyCompressedVersion.toUByte())
    writeTopologicallyCompressedRepDataV10(w, element.repData)
    w.writeU8(element.version.toUByte())
}

private fun readTopologicallyCompressedRepDataV10(r: ByteReader): TopologicallyCompressedRepDataV10 {
    val faceDegreePackets = List(8) { Int32Cdp.readV10(r) }
    val valencePacket = Int32Cdp.readV10(r)
    val groupPacket = Int32Cdp.readV10(r)
    val flagPacket = Int32Cdp.readV10(r)
    val maskPackets = List(8) { Int32Cdp.readV10(r) }
    val mask8Msb = Int32Cdp.readV10(r)
    val highDegreeCount = r.readI32()
    if (highDegreeCount < 0 || highDegreeCount > r.remaining / 4) {
        throw JtFormatException("high-degree mask count $highDegreeCount does not fit the remaining ${r.remaining} bytes")
    }
    val highDegreeMasks = List(highDegreeCount) { r.readI32() }
    val splitFacePacket = Int32Cdp.readV10(r)
    val splitPositionPacket = Int32Cdp.readV10(r)
    val storedHash = r.readI32()

    val flags = unpackResiduals(flagPacket.values, Predictor.LAG1)
    val splitFaces = unpackResiduals(splitFacePacket.values, Predictor.LAG1)

    // Composite hash (Figure 92's pseudo-code): the v10 8th mask context hashes 32 LSBs then
    // 32 MSBs (the JT 9 generation chunked 30/30/4 instead — DESIGN.md delta 20).
    var hash = 0
    for (packet in faceDegreePackets) hash = JtHash.hash32(packet.values.toIntArray(), hash)
    hash = JtHash.hash32(valencePacket.values.toIntArray(), hash)
    hash = JtHash.hash32(groupPacket.values.toIntArray(), hash)
    hash = JtHash.hash16(IntArray(flags.size) { flags[it] and 0xFFFF }, hash)
    for (i in 0 until 8) hash = JtHash.hash32(maskPackets[i].values.toIntArray(), hash)
    hash = JtHash.hash32(mask8Msb.values.toIntArray(), hash)
    hash = JtHash.hash32(highDegreeMasks.toIntArray(), hash)
    hash = JtHash.hash32(splitFaces.toIntArray(), hash)
    hash = JtHash.hash32(splitPositionPacket.values.toIntArray(), hash)
    if (hash != storedHash) {
        throw JtFormatException("topology composite hash mismatch: stored $storedHash, computed $hash")
    }

    val vertexRecords = readTopologicallyCompressedVertexRecordsV10(r)
    return TopologicallyCompressedRepDataV10(
        faceDegreePackets, valencePacket, groupPacket, flagPacket, maskPackets,
        mask8Msb, highDegreeMasks, splitFacePacket, splitPositionPacket,
        storedHash, vertexRecords,
    )
}

private fun writeTopologicallyCompressedRepDataV10(
    w: ByteWriter,
    data: TopologicallyCompressedRepDataV10,
) {
    for (packet in data.faceDegrees) packet.encode(w)
    data.vertexValences.encode(w)
    data.vertexGroups.encode(w)
    data.vertexFlags.encode(w)
    for (packet in data.faceAttributeMasks) packet.encode(w)
    data.faceAttributeMask8Msb.encode(w)
    w.writeI32(data.highDegreeFaceAttributeMasks.size)
    for (word in data.highDegreeFaceAttributeMasks) w.writeI32(word)
    data.splitFaceSymbols.encode(w)
    data.splitFacePositions.encode(w)
    w.writeI32(data.compositeHash)
    writeTopologicallyCompressedVertexRecords(w, data.vertexRecords)
}

/**
 * Vertex Bindings bits (Table 48) the v10 decode supports: 2/3/4-component coordinates
 * (bits 1-3), normals (bit 4) and per-vertex flags (bit 7). Colours, texture coordinates and
 * auxiliary fields have no fixture — a shape declaring them refuses the typed decode.
 */
private val UNSUPPORTED_BINDING_MASK_V10: ULong = 0x4FUL.inv()

private fun readTopologicallyCompressedVertexRecordsV10(r: ByteReader): TopologicallyCompressedVertexRecords {
    val bindings = r.readU64()
    val quantization =
        QuantizationParameters(
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
        )
    val topologicalVertexCount = r.readI32()
    if (topologicalVertexCount < 0) throw JtFormatException("negative topological vertex count $topologicalVertexCount")
    if (topologicalVertexCount == 0) {
        return TopologicallyCompressedVertexRecords(bindings, quantization, 0, null, null, null, null)
    }
    val attributeCount = r.readI32()
    if (bindings and UNSUPPORTED_BINDING_MASK_V10 != 0UL) {
        throw JtFormatException(
            "vertex bindings 0x${bindings.toString(16)} declare colours, texture coordinates or " +
                "auxiliary fields; their v10 layout is not established",
        )
    }
    val coordinates = if (bindings and 0x7UL != 0UL) CompressedVertexCoordinateArray.readV10(r) else null
    val normals = if (bindings and 0x8UL != 0UL) CompressedVertexNormalArray.readV10(r) else null
    val vertexFlags = if (bindings and 0x40UL != 0UL) CompressedVertexFlagArray.read(r) else null
    if (coordinates != null && coordinates.uniqueVertexCount != topologicalVertexCount) {
        throw JtFormatException(
            "coordinate array carries ${coordinates.uniqueVertexCount} vertices, " +
                "vertex records declare $topologicalVertexCount",
        )
    }
    return TopologicallyCompressedVertexRecords(
        bindings,
        quantization,
        topologicalVertexCount,
        attributeCount,
        coordinates,
        normals,
        vertexFlags,
    )
}

private fun readPolylineSetShapeLodV10(r: ByteReader): PolylineSetShapeLodElementV10 {
    val objectId = readElementHeader(r)
    val baseShapeLodVersion = r.readU8().toInt()
    val vertexShapeLodVersion = r.readU8().toInt()
    val bindings = r.readU64()
    val nestedHeader = readNestedElementHeader(r)
    val topoMesh = TopoMeshLodData(r.readU8().toInt(), r.readI32())
    val compressedLodVersion = r.readU8().toInt()
    val repData = readTopoMeshCompressedRepData(r)
    val version = r.readU8().toInt()
    val geometry = buildPolylineGeometry(repData)
    return PolylineSetShapeLodElementV10(
        objectId, baseShapeLodVersion, vertexShapeLodVersion, bindings, nestedHeader,
        topoMesh, compressedLodVersion, repData, version, geometry,
    )
}

private fun writePolylineSetShapeLodV10(
    w: ByteWriter,
    element: PolylineSetShapeLodElementV10,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    w.writeU8(element.baseShapeLodVersion.toUByte())
    w.writeU8(element.vertexShapeLodVersion.toUByte())
    w.writeU64(element.vertexBindings)
    writeNestedElementHeader(w, element.nestedHeader)
    w.writeU8(element.topoMesh.version.toUByte())
    w.writeI32(element.topoMesh.vertexRecordsObjectId)
    w.writeU8(element.compressedLodVersion.toUByte())
    writeTopoMeshCompressedRepData(w, element.repData)
    w.writeU8(element.version.toUByte())
}

private fun readTopoMeshCompressedRepData(r: ByteReader): TopoMeshCompressedRepData {
    val faceGroupCount = r.readI32()
    val primitiveCount = r.readI32()
    val vertexCount = r.readI32()
    if (faceGroupCount < 0 || primitiveCount < 0 || vertexCount < 0) {
        throw JtFormatException("negative index count in TopoMesh Compressed Rep Data")
    }
    val (faceGroupPacket, faceGroups) = readInt32CdpValuesV10(r, Predictor.LAG1)
    val (primitivePacket, primitives) = readInt32CdpValuesV10(r, Predictor.LAG1)
    val (vertexPacket, vertexIndices) = readInt32CdpValuesV10(r, Predictor.LAG1)
    // Figure 89's hash pseudo-code: the face group and primitive lists carry count + 1
    // entries (the trailing terminator), the vertex list exactly count.
    if (faceGroups.size != faceGroupCount + 1) {
        throw JtFormatException("face group list carries ${faceGroups.size} indices, declared $faceGroupCount + 1")
    }
    if (primitives.size != primitiveCount + 1) {
        throw JtFormatException("primitive list carries ${primitives.size} indices, declared $primitiveCount + 1")
    }
    if (vertexIndices.size != vertexCount) {
        throw JtFormatException("vertex list carries ${vertexIndices.size} indices, declared $vertexCount")
    }
    val storedFgpvHash = r.readI32()
    var hash = JtHash.hash32(faceGroups.toIntArray(), 0)
    hash = JtHash.hash32(primitives.toIntArray(), hash)
    hash = JtHash.hash32(vertexIndices.toIntArray(), hash)
    if (hash != storedFgpvHash) {
        throw JtFormatException("FGPV list indices hash mismatch: stored $storedFgpvHash, computed $hash")
    }
    val bindings = r.readU64()
    val quantization =
        QuantizationParameters(
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
        )
    val recordCount = r.readI32()
    if (recordCount < 0) throw JtFormatException("negative vertex record count $recordCount")
    if (recordCount == 0) {
        return TopoMeshCompressedRepData(
            faceGroupCount, primitiveCount, vertexCount,
            faceGroupPacket, primitivePacket, vertexPacket, storedFgpvHash,
            bindings, quantization, 0, null, null, null, null, null,
        )
    }
    val lengthsPacket = Int32Cdp.readV10(r)
    val storedLengthsHash = r.readI32()
    val lengthsHash = JtHash.hash32(lengthsPacket.values.toIntArray(), 0)
    if (lengthsHash != storedLengthsHash) {
        throw JtFormatException("unique vertex length list hash mismatch: stored $storedLengthsHash, computed $lengthsHash")
    }
    if (bindings and UNSUPPORTED_BINDING_MASK_V10 != 0UL) {
        throw JtFormatException(
            "vertex bindings 0x${bindings.toString(16)} declare colours, texture coordinates or " +
                "auxiliary fields; their v10 layout is not established",
        )
    }
    val coordinates = if (bindings and 0x7UL != 0UL) CompressedVertexCoordinateArray.readV10(r) else null
    val normals = if (bindings and 0x8UL != 0UL) CompressedVertexNormalArray.readV10(r) else null
    val vertexFlags = if (bindings and 0x40UL != 0UL) CompressedVertexFlagArray.read(r) else null
    return TopoMeshCompressedRepData(
        faceGroupCount, primitiveCount, vertexCount,
        faceGroupPacket, primitivePacket, vertexPacket, storedFgpvHash,
        bindings, quantization, recordCount, lengthsPacket, storedLengthsHash,
        coordinates, normals, vertexFlags,
    )
}

private fun writeTopoMeshCompressedRepData(
    w: ByteWriter,
    data: TopoMeshCompressedRepData,
) {
    w.writeI32(data.numberOfFaceGroupListIndices)
    w.writeI32(data.numberOfPrimitiveListIndices)
    w.writeI32(data.numberOfVertexListIndices)
    data.faceGroupListIndices.encode(w)
    data.primitiveListIndices.encode(w)
    data.vertexListIndices.encode(w)
    w.writeI32(data.fgpvListIndicesHash)
    w.writeU64(data.vertexBindings)
    w.writeU8(data.quantizationParameters.bitsPerVertex.toUByte())
    w.writeU8(data.quantizationParameters.normalBitsFactor.toUByte())
    w.writeU8(data.quantizationParameters.bitsPerTextureCoord.toUByte())
    w.writeU8(data.quantizationParameters.bitsPerColour.toUByte())
    w.writeI32(data.numberOfVertexRecords)
    if (data.numberOfVertexRecords == 0) return
    data.uniqueVertexLengths?.encode(w)
    data.uniqueVertexListMapHash?.let { w.writeI32(it) }
    data.coordinates?.write(w)
    data.normals?.write(w)
    data.vertexFlags?.write(w)
}

// --- JT 9 polyline and point set element bodies (9.5 Figures 94/95; KR360-1-verified) ---

private fun readPolylineSetShapeLod(r: ByteReader): PolylineSetShapeLodElement {
    val objectId = readElementHeader(r)
    val baseShapeLodVersion = r.readI16().toInt()
    val vertexShapeLodVersion = r.readI16().toInt()
    val bindings = r.readU64()
    val topoMesh = TopoMeshLodData(r.readI16().toInt(), r.readI32())
    val compressedLodVersion = r.readI16().toInt()
    val repData = readTopoMeshCompressedRepDataV1(r, polylineShape = true)
    val auxiliary = readAuxiliaryVertexFieldData(r)
    val version = r.readI16().toInt()
    return PolylineSetShapeLodElement(
        objectId, baseShapeLodVersion, vertexShapeLodVersion, bindings, topoMesh,
        compressedLodVersion, repData, auxiliary, version, buildPolylineGeometryV1(repData),
    )
}

private fun writePolylineSetShapeLod(
    w: ByteWriter,
    element: PolylineSetShapeLodElement,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    w.writeI16(element.baseShapeLodVersion.toShort())
    w.writeI16(element.vertexShapeLodVersion.toShort())
    w.writeU64(element.vertexBindings)
    w.writeI16(element.topoMesh.version.toShort())
    w.writeI32(element.topoMesh.vertexRecordsObjectId)
    w.writeI16(element.compressedLodVersion.toShort())
    writeTopoMeshCompressedRepDataV1(w, element.repData)
    writeAuxiliaryVertexFieldData(w, element.auxiliaryVertexFields)
    w.writeI16(element.version.toShort())
}

private fun readPointSetShapeLod(r: ByteReader): PointSetShapeLodElement {
    val objectId = readElementHeader(r)
    val baseShapeLodVersion = r.readI16().toInt()
    val vertexShapeLodVersion = r.readI16().toInt()
    val bindings = r.readU64()
    val topoMesh = TopoMeshLodData(r.readI16().toInt(), r.readI32())
    val compressedLodVersion = r.readI16().toInt()
    val repData = readTopoMeshCompressedRepDataV1(r, polylineShape = false)
    val auxiliary = readAuxiliaryVertexFieldData(r)
    val version = r.readI16().toInt()
    return PointSetShapeLodElement(
        objectId, baseShapeLodVersion, vertexShapeLodVersion, bindings, topoMesh,
        compressedLodVersion, repData, auxiliary, version, buildPointGeometry(repData),
    )
}

private fun writePointSetShapeLod(
    w: ByteWriter,
    element: PointSetShapeLodElement,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    w.writeI16(element.baseShapeLodVersion.toShort())
    w.writeI16(element.vertexShapeLodVersion.toShort())
    w.writeU64(element.vertexBindings)
    w.writeI16(element.topoMesh.version.toShort())
    w.writeI32(element.topoMesh.vertexRecordsObjectId)
    w.writeI16(element.compressedLodVersion.toShort())
    writeTopoMeshCompressedRepDataV1(w, element.repData)
    writeAuxiliaryVertexFieldData(w, element.auxiliaryVertexFields)
    w.writeI16(element.version.toShort())
}

/**
 * TopoMesh Compressed Rep Data V1 (9.5 Figure 91). [polylineShape] selects the figure's
 * `if Polyline Shape` guard, which covers the face-group count, the face-group index list and
 * — per the `if (bLineStrip)` line of the p.119 hash pseudo-code — the face-group term of the
 * FGPV hash. v10's Figure 89 dropped all three guards; reusing its reader for a 9.5 point set
 * would refuse a conformant file.
 *
 * All three index lists are `VecI32{Int32CDP2}` with **no** predictor here, where v10
 * annotates `{Int32CDP, Lag1}` — the delta the stored hash arbitrates.
 */
private fun readTopoMeshCompressedRepDataV1(
    r: ByteReader,
    polylineShape: Boolean,
): TopoMeshCompressedRepDataV1 {
    val faceGroupCount = if (polylineShape) r.readI32() else null
    val primitiveCount = r.readI32()
    val vertexCount = r.readI32()
    if ((faceGroupCount ?: 0) < 0 || primitiveCount < 0 || vertexCount < 0) {
        throw JtFormatException("negative index count in TopoMesh Compressed Rep Data V1")
    }
    val faceGroupSection =
        if (faceGroupCount == null) {
            null
        } else {
            val (packet, indices) = readInt32CdpValues(r, Predictor.NONE)
            if (indices.size != faceGroupCount + 1) {
                throw JtFormatException("face group list carries ${indices.size} indices, declared $faceGroupCount + 1")
            }
            FaceGroupListSection(faceGroupCount, packet)
        }
    val (primitivePacket, primitives) = readInt32CdpValues(r, Predictor.NONE)
    val (vertexPacket, vertexIndices) = readInt32CdpValues(r, Predictor.NONE)
    if (primitives.size != primitiveCount + 1) {
        throw JtFormatException("primitive list carries ${primitives.size} indices, declared $primitiveCount + 1")
    }
    if (vertexIndices.size != vertexCount) {
        throw JtFormatException("vertex list carries ${vertexIndices.size} indices, declared $vertexCount")
    }
    val storedFgpvHash = r.readI32()
    var hash = 0
    if (faceGroupSection != null) hash = JtHash.hash32(faceGroupSection.indices.values.toIntArray(), hash)
    hash = JtHash.hash32(primitives.toIntArray(), hash)
    hash = JtHash.hash32(vertexIndices.toIntArray(), hash)
    if (hash != storedFgpvHash) {
        throw JtFormatException("FGPV list indices hash mismatch: stored $storedFgpvHash, computed $hash")
    }
    val bindings = r.readU64()
    val quantization =
        QuantizationParameters(
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
            r.readU8().toInt(),
        )
    val recordCount = r.readI32()
    if (recordCount < 0) throw JtFormatException("negative vertex record count $recordCount")
    if (recordCount == 0) {
        return TopoMeshCompressedRepDataV1(
            faceGroupSection, primitiveCount, vertexCount, primitivePacket, vertexPacket,
            storedFgpvHash, bindings, quantization, 0, null, null, null, null, null,
        )
    }
    // 9.5-only: v10's Figure 89 has no such field.
    val uniqueCoordinateCount = r.readI32()
    if (uniqueCoordinateCount < 0) throw JtFormatException("negative unique coordinate count $uniqueCoordinateCount")
    val (lengthsPacket, lengths) = readInt32CdpValues(r, Predictor.NONE)
    if (lengths.size != uniqueCoordinateCount) {
        throw JtFormatException(
            "unique vertex length list carries ${lengths.size} entries, declared $uniqueCoordinateCount",
        )
    }
    val storedLengthsHash = r.readI32()
    val lengthsHash = JtHash.hash32(lengths.toIntArray(), 0)
    if (lengthsHash != storedLengthsHash) {
        throw JtFormatException("unique vertex length list hash mismatch: stored $storedLengthsHash, computed $lengthsHash")
    }
    if (bindings and UNSUPPORTED_BINDING_MASK != 0UL) {
        throw JtFormatException(
            "vertex bindings 0x${bindings.toString(16)} declare colours, texture coordinates, flags or " +
                "auxiliary fields; their JT 9 layout is not established",
        )
    }
    val coordinates = if (bindings and 0x7UL != 0UL) CompressedVertexCoordinateArray.read(r) else null
    val normals = if (bindings and 0x8UL != 0UL) CompressedVertexNormalArray.read(r) else null
    if (coordinates != null && coordinates.uniqueVertexCount != uniqueCoordinateCount) {
        throw JtFormatException(
            "coordinate array carries ${coordinates.uniqueVertexCount} unique coordinates, " +
                "the rep data declares $uniqueCoordinateCount",
        )
    }
    return TopoMeshCompressedRepDataV1(
        faceGroupSection, primitiveCount, vertexCount, primitivePacket, vertexPacket,
        storedFgpvHash, bindings, quantization, recordCount, uniqueCoordinateCount,
        lengthsPacket, storedLengthsHash, coordinates, normals,
    )
}

private fun writeTopoMeshCompressedRepDataV1(
    w: ByteWriter,
    data: TopoMeshCompressedRepDataV1,
) {
    data.faceGroupSection?.let { w.writeI32(it.numberOfIndices) }
    w.writeI32(data.numberOfPrimitiveListIndices)
    w.writeI32(data.numberOfVertexListIndices)
    data.faceGroupSection?.indices?.encode(w)
    data.primitiveListIndices.encode(w)
    data.vertexListIndices.encode(w)
    w.writeI32(data.fgpvListIndicesHash)
    w.writeU64(data.vertexBindings)
    w.writeU8(data.quantizationParameters.bitsPerVertex.toUByte())
    w.writeU8(data.quantizationParameters.normalBitsFactor.toUByte())
    w.writeU8(data.quantizationParameters.bitsPerTextureCoord.toUByte())
    w.writeU8(data.quantizationParameters.bitsPerColour.toUByte())
    w.writeI32(data.numberOfVertexRecords)
    if (data.numberOfVertexRecords == 0) return
    data.numberOfUniqueVertexCoordinates?.let { w.writeI32(it) }
    data.uniqueVertexLengths?.encode(w)
    data.uniqueVertexListMapHash?.let { w.writeI32(it) }
    data.coordinates?.write(w)
    data.normals?.write(w)
}

// --- geometry extraction ---

private fun buildTriStripGeometry(repData: TopologicallyCompressedRepData): TriStripGeometry {
    val valences = repData.vertexValences.values
    val groups = repData.vertexGroups.values
    val flags = unpackResiduals(repData.vertexFlags.values, Predictor.LAG1)
    if (groups.size != valences.size || flags.size != valences.size) {
        throw JtFormatException(
            "vertex groups (${groups.size}) and flags (${flags.size}) are not parallel to the valences (${valences.size})",
        )
    }
    // JT 9 splits the 8th context's 64-bit masks into 30 + 30 + 4 bit chunks; the same derived
    // arrays the composite hash is computed over reassemble them.
    val (low8, mid8, top8) =
        context8Chunks(repData.faceAttributeMasks[7], repData.faceAttributeMask8Mid, repData.faceAttributeMask8Top)
    val masks =
        List(8) { context ->
            if (context < 7) {
                repData.faceAttributeMasks[context].values.map { it.toLong() and 0xFFFFFFFFL }
            } else {
                List(low8.size) { i ->
                    (low8[i].toLong() and 0x3FFFFFFFL) or
                        ((mid8[i].toLong() and 0x3FFFFFFFL) shl 30) or
                        ((top8[i].toLong() and 0xFL) shl 60)
                }
            }
        }
    return buildTrianglesFromTopology(
        repData.faceDegrees.map { it.values },
        valences,
        groups,
        flags,
        masks,
        repData.highDegreeFaceAttributeMasks,
        unpackResiduals(repData.splitFaceSymbols.values, Predictor.LAG1),
        repData.splitFacePositions.values,
        repData.vertexRecords,
    )
}

private fun buildTriStripGeometryV10(repData: TopologicallyCompressedRepDataV10): TriStripGeometry {
    val valences = repData.vertexValences.values
    val groups = repData.vertexGroups.values
    val flags = unpackResiduals(repData.vertexFlags.values, Predictor.LAG1)
    if (groups.size != valences.size || flags.size != valences.size) {
        throw JtFormatException(
            "vertex groups (${groups.size}) and flags (${flags.size}) are not parallel to the valences (${valences.size})",
        )
    }
    val mask8Count = repData.faceAttributeMasks[7].valueCount
    if (repData.faceAttributeMask8Msb.valueCount != 0 && repData.faceAttributeMask8Msb.valueCount != mask8Count) {
        throw JtFormatException("face attribute mask MSB chunk is not parallel to context 8")
    }
    val masks =
        List(8) { context ->
            val low = repData.faceAttributeMasks[context].values
            if (context < 7) {
                low.map { it.toLong() and 0xFFFFFFFFL }
            } else {
                // v10 splits the 8th context's 64-bit masks into 32 + 32 bit chunks.
                val msb = repData.faceAttributeMask8Msb.values
                List(low.size) { i ->
                    var mask = low[i].toLong() and 0xFFFFFFFFL
                    if (i < msb.size) mask = mask or ((msb[i].toLong() and 0xFFFFFFFFL) shl 32)
                    mask
                }
            }
        }
    return buildTrianglesFromTopology(
        repData.faceDegrees.map { it.values },
        valences,
        groups,
        flags,
        masks,
        repData.highDegreeFaceAttributeMasks,
        unpackResiduals(repData.splitFaceSymbols.values, Predictor.LAG1),
        repData.splitFacePositions.values,
        repData.vertexRecords,
    )
}

/** The generation-independent core: Annex D topology decode + triangle extraction. */
private fun buildTrianglesFromTopology(
    faceDegreeSymbols: List<List<Int>>,
    valences: List<Int>,
    groups: List<Int>,
    flags: List<Int>,
    masks: List<List<Long>>,
    highDegreeMasks: List<Int>,
    splitFaceSymbols: List<Int>,
    splitFacePositions: List<Int>,
    records: TopologicallyCompressedVertexRecords,
): TriStripGeometry {
    val mesh =
        TopologyDecoder(
            faceDegreeSymbols,
            valences,
            groups,
            flags,
            masks,
            highDegreeMasks,
            splitFaceSymbols,
            splitFacePositions,
        ).decode()

    if (mesh.faces.size != records.numberOfTopologicalVertices) {
        throw JtFormatException(
            "topology decode produced ${mesh.faces.size} unique vertices, " +
                "vertex records declare ${records.numberOfTopologicalVertices}",
        )
    }
    val storedAttributes = records.numberOfVertexAttributes ?: 0
    if (mesh.attributeCount > storedAttributes) {
        throw JtFormatException(
            "attribute masks reference ${mesh.attributeCount} records, only $storedAttributes stored",
        )
    }
    val vertices =
        records.coordinates?.coordinates
            ?: throw JtFormatException("tri-strip set without vertex coordinates")
    val normals = records.normals?.normals.orEmpty()
    if (normals.isNotEmpty() && normals.size < mesh.attributeCount) {
        throw JtFormatException("normal array carries ${normals.size} records, masks reference ${mesh.attributeCount}")
    }

    val triangles = mutableListOf<TriStripGeometry.Triangle>()
    for ((dualIndex, dualVertex) in mesh.vertices.withIndex()) {
        if (dualVertex.flags and 1 == 1) continue // a cover face added to close the mesh
        if (dualVertex.valence != 3) {
            throw JtFormatException("primal face $dualIndex has degree ${dualVertex.valence} in a tri-strip set")
        }
        val corners = IntArray(3)
        val normalIndices = IntArray(3) { -1 }
        for (k in 0 until 3) {
            val primalVertex = dualVertex.faces[k]
            corners[k] = primalVertex
            if (normals.isNotEmpty()) {
                val slot = mesh.faces[primalVertex].vts.indexOf(dualIndex)
                if (slot < 0) throw JtFormatException("triangle $dualIndex is not on the ring of its vertex $primalVertex")
                val attr = mesh.attributeAt(primalVertex, slot)
                if (attr < 0 || attr >= normals.size) {
                    throw JtFormatException("normal record $attr out of range for triangle $dualIndex")
                }
                normalIndices[k] = attr
            }
        }
        triangles.add(
            TriStripGeometry.Triangle(
                corners[0],
                corners[1],
                corners[2],
                normalIndices[0],
                normalIndices[1],
                normalIndices[2],
                dualVertex.group,
            ),
        )
    }
    return TriStripGeometry(vertices, normals, triangles)
}

/**
 * Derives the polyline geometry from TopoMesh Compressed Rep Data: unique coordinates are
 * smeared to vertex-record space via the unique-length list, the primitive list slices the
 * vertex list into polylines, and the face group list assigns each polyline its group. All
 * index structure is validated strictly — an inconsistency refuses the typed decode.
 */
private fun buildPolylineGeometry(repData: TopoMeshCompressedRepData): PolylineGeometry =
    buildPolylineGeometry(
        faceGroups = unpackResiduals(repData.faceGroupListIndices.values, Predictor.LAG1),
        primitives = unpackResiduals(repData.primitiveListIndices.values, Predictor.LAG1),
        vertexIndices = unpackResiduals(repData.vertexListIndices.values, Predictor.LAG1),
        vertices =
            smearUniqueCoordinates(
                repData.coordinates?.coordinates,
                repData.uniqueVertexLengths?.values,
                repData.numberOfVertexRecords,
                "polyline set",
            ),
    )

/** The JT 9 twin: the index lists are NULL-predicted, so the packet values are the indices. */
private fun buildPolylineGeometryV1(repData: TopoMeshCompressedRepDataV1): PolylineGeometry =
    buildPolylineGeometry(
        faceGroups =
            repData.faceGroupSection?.indices?.values
                ?: throw JtFormatException("polyline set without a face group list"),
        primitives = repData.primitiveListIndices.values,
        vertexIndices = repData.vertexListIndices.values,
        vertices =
            smearUniqueCoordinates(
                repData.coordinates?.coordinates,
                repData.uniqueVertexLengths?.values,
                repData.numberOfVertexRecords,
                "polyline set",
            ),
    )

/**
 * The decoded point cloud of a JT 9 Point Set Shape LOD (9.5 Figure 95). The primitive list
 * tiles the vertex list exactly as it does for polylines — a point set simply has no face
 * groups to assign the primitives to — and the tiling is validated so a malformed body refuses
 * the typed decode instead of yielding points that reference nothing.
 */
private fun buildPointGeometry(repData: TopoMeshCompressedRepDataV1): PointGeometry {
    val primitives = repData.primitiveListIndices.values
    val vertexIndices = repData.vertexListIndices.values
    val vertices =
        smearUniqueCoordinates(
            repData.coordinates?.coordinates,
            repData.uniqueVertexLengths?.values,
            repData.numberOfVertexRecords,
            "point set",
        )
    if (primitives.isEmpty() || primitives.first() != 0 || primitives.last() != vertexIndices.size) {
        throw JtFormatException("primitive list indices do not tile the ${vertexIndices.size}-entry vertex list")
    }
    for (p in 0 until primitives.size - 1) {
        if (primitives[p] > primitives[p + 1]) {
            throw JtFormatException("primitive $p spans invalid vertex range [${primitives[p]}, ${primitives[p + 1]})")
        }
    }
    val points =
        List(vertexIndices.size) { k ->
            val index = vertexIndices[k]
            if (index !in vertices.indices) {
                throw JtFormatException("vertex list index $index outside the ${vertices.size} vertex records")
            }
            index
        }
    return PointGeometry(vertices, points)
}

/**
 * Smears the unique vertex coordinates into vertex-record space via the unique-length list
 * (9.5 Figure 91's prose: the list "should sum to the number of vertex records", and the
 * coordinate array "is therefore parallel to" it).
 */
private fun smearUniqueCoordinates(
    coordinates: List<de.haumacher.kotlinjt.lsg.Vec3F32>?,
    lengths: List<Int>?,
    numberOfVertexRecords: Int,
    what: String,
): List<de.haumacher.kotlinjt.lsg.Vec3F32> {
    if (coordinates == null) throw JtFormatException("$what without vertex coordinates")
    if (lengths == null) throw JtFormatException("$what without a unique vertex length list")
    if (lengths.size != coordinates.size) {
        throw JtFormatException("unique vertex length list has ${lengths.size} entries for ${coordinates.size} unique coordinates")
    }
    val vertices = ArrayList<de.haumacher.kotlinjt.lsg.Vec3F32>(numberOfVertexRecords)
    for ((unique, length) in lengths.withIndex()) {
        if (length < 0) throw JtFormatException("negative unique vertex length $length")
        repeat(length) { vertices.add(coordinates[unique]) }
    }
    if (vertices.size != numberOfVertexRecords) {
        throw JtFormatException(
            "unique vertex lengths sum to ${vertices.size}, $numberOfVertexRecords vertex records declared",
        )
    }
    return vertices
}

/** The generation-independent core: the index lists already unpacked to primal values. */
private fun buildPolylineGeometry(
    faceGroups: List<Int>,
    primitives: List<Int>,
    vertexIndices: List<Int>,
    vertices: List<de.haumacher.kotlinjt.lsg.Vec3F32>,
): PolylineGeometry {
    if (primitives.isEmpty() || primitives.first() != 0 || primitives.last() != vertexIndices.size) {
        throw JtFormatException("primitive list indices do not tile the ${vertexIndices.size}-entry vertex list")
    }
    val polylineCount = primitives.size - 1
    if (faceGroups.isEmpty() || faceGroups.first() != 0 || faceGroups.last() != polylineCount) {
        throw JtFormatException("face group list indices do not tile the $polylineCount polylines")
    }
    val groupOfPolyline = IntArray(polylineCount)
    for (group in 0 until faceGroups.size - 1) {
        val from = faceGroups[group]
        val to = faceGroups[group + 1]
        if (from > to || from < 0 || to > polylineCount) {
            throw JtFormatException("face group $group spans invalid polyline range [$from, $to)")
        }
        for (p in from until to) groupOfPolyline[p] = group
    }
    val polylines =
        List(polylineCount) { p ->
            val from = primitives[p]
            val to = primitives[p + 1]
            if (from > to) throw JtFormatException("primitive $p spans invalid vertex range [$from, $to)")
            val indices =
                List(to - from) { k ->
                    val index = vertexIndices[from + k]
                    if (index !in vertices.indices) {
                        throw JtFormatException("vertex list index $index outside the ${vertices.size} vertex records")
                    }
                    index
                }
            PolylineGeometry.Polyline(indices, groupOfPolyline[p])
        }
    return PolylineGeometry(vertices, polylines)
}
