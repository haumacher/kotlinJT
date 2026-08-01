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
        get() = elements.filterIsInstance<TriStripSetShapeLodElement>().firstOrNull()?.geometry

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
    // The v10 generation's shape element bodies are hidden behind LZMA in every available
    // v10 file; their layouts are established together with that codec — never guessed here.
    val decoder: ((ByteReader) -> TypedShapeLodElement)? =
        if (generation == LsgGeneration.V9) {
            when (typeId) {
                ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT -> ::readTriStripSetShapeLod
                ObjectTypeIds.NULL_SHAPE_LOD_ELEMENT -> ::readNullShapeLod
                else -> null
            }
        } else {
            null
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
        is TriStripSetShapeLodElement -> writeTriStripSetShapeLod(bodyWriter, element)
        is NullShapeLodElement -> writeNullShapeLod(bodyWriter, element)
    }
    check(generation == LsgGeneration.V9 || element is OpaqueShapeLodElement) {
        "typed shape LOD elements encode in the JT 9 generation only"
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

private fun writeNullShapeLod(
    w: ByteWriter,
    element: NullShapeLodElement,
) {
    w.writeU8(BASE_TYPE_SHAPE_LOD.toUByte())
    w.writeI32(element.objectId)
    w.writeI16(element.version.toShort())
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
    val reservedVersion = r.readI16().toInt()
    val reservedBindings = r.readU64()
    val version = r.readI16().toInt()
    val geometry = buildTriStripGeometry(repData)
    return TriStripSetShapeLodElement(
        objectId, baseShapeLodVersion, vertexShapeLodVersion, bindings, topoMesh,
        topoVersion, repData, reservedVersion, reservedBindings, version, geometry,
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
    w.writeI16(element.reservedVersion.toShort())
    w.writeU64(element.reservedBindings)
    w.writeI16(element.version.toShort())
}

// --- Topologically Compressed Rep Data (Figure 92) ---

/**
 * Vertex Bindings bits (Table 48) beyond coordinates (bits 1-3) and normals (bit 4): colours,
 * texture coordinates, per-vertex flags and auxiliary fields. Their JT 9 vertex array layouts
 * are not established against any fixture — a shape declaring them refuses the typed decode.
 */
private val UNSUPPORTED_BINDING_MASK: ULong = 0xFUL.inv()

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

    // Composite hash (Annex C hashing over the decoded symbol streams) — verified so a codec
    // defect or corrupt stream refuses the decode instead of producing a broken mesh.
    var hash = 0
    for (packet in faceDegreePackets) hash = JtHash.hash32(packet.values.toIntArray(), hash)
    hash = JtHash.hash32(valencePacket.values.toIntArray(), hash)
    hash = JtHash.hash32(groupPacket.values.toIntArray(), hash)
    hash = JtHash.hash16(IntArray(flags.size) { flags[it] and 0xFFFF }, hash)
    for (i in 0 until 7) hash = JtHash.hash32(maskPackets[i].values.toIntArray(), hash)
    hash = JtHash.hash32(IntArray(maskPackets[7].values.size) { maskPackets[7].values[it] and 0x3FFFFFFF }, hash)
    hash = JtHash.hash32(mask8Mid.values.toIntArray(), hash)
    hash = JtHash.hash32(mask8Top.values.toIntArray(), hash)
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
    val mask8Count = repData.faceAttributeMasks[7].valueCount
    if (repData.faceAttributeMask8Mid.valueCount != 0 && repData.faceAttributeMask8Mid.valueCount != mask8Count) {
        throw JtFormatException("face attribute mask mid chunk is not parallel to context 8")
    }
    if (repData.faceAttributeMask8Top.valueCount != 0 && repData.faceAttributeMask8Top.valueCount != mask8Count) {
        throw JtFormatException("face attribute mask top chunk is not parallel to context 8")
    }
    val masks =
        List(8) { context ->
            val low = repData.faceAttributeMasks[context].values
            if (context < 7) {
                low.map { it.toLong() and 0xFFFFFFFFL }
            } else {
                // JT 9 splits the 8th context's 64-bit masks into 30 + 30 + 4 bit chunks.
                val mid = repData.faceAttributeMask8Mid.values
                val top = repData.faceAttributeMask8Top.values
                List(low.size) { i ->
                    var mask = low[i].toLong() and 0x3FFFFFFFL
                    if (i < mid.size) mask = mask or ((mid[i].toLong() and 0x3FFFFFFFL) shl 30)
                    if (i < top.size) mask = mask or ((top[i].toLong() and 0xFL) shl 60)
                    mask
                }
            }
        }
    val mesh =
        TopologyDecoder(
            repData.faceDegrees.map { it.values },
            valences,
            groups,
            flags,
            masks,
            repData.highDegreeFaceAttributeMasks,
            unpackResiduals(repData.splitFaceSymbols.values, Predictor.LAG1),
            repData.splitFacePositions.values,
        ).decode()

    val records = repData.vertexRecords
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
