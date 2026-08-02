package de.haumacher.kotlinjt.meta

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
import de.haumacher.kotlinjt.lsg.readPropertyTable
import de.haumacher.kotlinjt.lsg.writePropertyTable

/**
 * The typed document model of a Meta Data segment's element data (§11, Figure 107): the
 * element list closed by the end-of-elements marker, followed by the Figure-78 Property Table
 * every real producer writes after its elements (empty in all 44 meta data / PMI segments of
 * the NIST fixture, exactly as in the shape LOD segments).
 *
 * The same model serves *PMI Data* segments (Table 6 type 3): Annex H says a PMI Data Segment
 * "should be treated exactly the same as a PMI Manager Meta Data Element", and the NIST 10.5
 * file confirms it — its 14 type-3 segments each frame one PMI Manager Meta Data Element.
 *
 * The losslessness guarantee is the seam the LSG and Shape LOD models use: [decode] followed
 * by [encode] reproduces the element data byte-identically, for decoded and opaquely carried
 * elements alike.
 */
data class MetaDataDocument(
    val generation: LsgGeneration,
    val elements: List<MetaDataElement>,
    /** Whether the element list was closed by the end-of-elements marker. */
    val elementsTerminated: Boolean,
    /** The trailing Property Table (Figure 78), `null` when missing (a note says why). */
    val propertyTable: PropertyTable?,
    /** Bytes after the recognized structure, preserved verbatim (empty on healthy streams). */
    val trailing: Bytes,
) {
    /** The property bags of this segment, in element order. */
    val propertyProxies: List<PropertyProxyMetaDataElement>
        get() = elements.filterIsInstance<PropertyProxyMetaDataElement>()

    /** The v10 PMI managers of this segment, in element order. */
    val pmiManagers: List<PmiManagerMetaDataElement>
        get() = elements.filterIsInstance<PmiManagerMetaDataElement>()

    /** The JT 9.5 PMI managers of this segment, in element order (9.5 Figure 136). */
    val pmi95Managers: List<Pmi95ManagerMetaDataElement>
        get() = elements.filterIsInstance<Pmi95ManagerMetaDataElement>()

    /** Serializes the document back to element-stream bytes — the exact inverse of [decode]. */
    fun encode(order: Endianness): Bytes {
        val writer = ByteWriter(order)
        for (element in elements) {
            encodeMetaElementFrame(writer, generation, element)
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
         * Decodes the (already decompressed) element data of a Meta Data or PMI Data segment.
         * Never throws for content problems: whatever does not decode is carried opaquely and
         * named by a note.
         */
        fun decode(
            elementData: Bytes,
            version: JtVersion,
            order: Endianness,
            externallyCompressed: Boolean = true,
        ): MetaDataDecodeResult {
            val generation = LsgGeneration.of(version)
            val notes = mutableListOf<LoadNote>()
            val bytes = elementData.toByteArray()
            val reader = ByteReader(bytes, order)

            val elements = mutableListOf<MetaDataElement>()
            var terminated = false
            while (true) {
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
                val location = "meta data element at offset $start"
                elements.add(
                    decodeMetaElementBody(typeId, body, generation, order, externallyCompressed, notes, location),
                )
            }

            var table: PropertyTable? = null
            var trailing = Bytes.EMPTY
            if (!terminated) {
                notes.add(
                    LoadNote.MetaDataStructureUnrecognized(
                        "element list breaks off at offset ${reader.position} of ${bytes.size}",
                    ),
                )
                trailing = Bytes.of(bytes, reader.position, bytes.size)
            } else if (reader.remaining == 0) {
                notes.add(LoadNote.PropertyTableMissing("meta data stream ends after the element list"))
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
            return MetaDataDecodeResult(
                MetaDataDocument(generation, elements, terminated, table, trailing),
                notes,
            )
        }
    }
}

/** The outcome of a meta data document decode: the document plus the named refusals, if any. */
data class MetaDataDecodeResult(
    val document: MetaDataDocument,
    val notes: List<LoadNote>,
)

/** Whether this segment holds §11 elements: Meta Data (Table 6 type 4) or PMI Data (type 3). */
val JtSegment.isMetaDataSegment: Boolean
    get() = kind == SegmentKind.META_DATA || kind == SegmentKind.PMI_DATA

/** All Meta Data and PMI Data segments of the file, in TOC order. */
fun JtFile.metaDataSegments(): List<JtSegment> = segments.filter { it.isMetaDataSegment }

/**
 * Decodes the element data of [segment] into the typed meta data document model; `null` when
 * the segment is not a §11 segment or Layer 0 produced no element data (its notes say why).
 */
fun JtFile.decodeMetaData(segment: JtSegment): MetaDataDecodeResult? {
    if (!segment.isMetaDataSegment) return null
    val elementData = segment.elementData ?: return null
    val compressed = (segment.compression?.algorithmCode ?: 1) != 1
    return MetaDataDocument.decode(elementData, header.version, header.byteOrder, compressed)
}

// ---------------------------------------------------------------------------
// Element codecs
// ---------------------------------------------------------------------------

private fun decodeMetaElementBody(
    typeId: Guid,
    body: ByteArray,
    generation: LsgGeneration,
    order: Endianness,
    externallyCompressed: Boolean,
    notes: MutableList<LoadNote>,
    location: String,
): MetaDataElement {
    val scannedBaseType = if (body.isNotEmpty()) body[0].toInt() and 0xFF else null

    fun opaque(): MetaDataElement = OpaqueMetaDataElement(typeId, scannedBaseType, body.toBytes())

    val typeName = ObjectTypeIds.nameOf(typeId)
    if (typeName == null) {
        notes.add(LoadNote.UnknownElementType(typeId, location))
        return opaque()
    }
    val localNotes = mutableListOf<LoadNote>()
    // Wire layouts established per generation. The Property Proxy element's layout is
    // documented identically by the v10 (Figure 108) and v9.5 (Figure 134) references — only
    // the version field's width differs (DESIGN.md delta 6), so it decodes in all three
    // generations. The PMI Manager is *two* elements sharing one Object Type ID: the v10
    // Figure 110 structure and the older, larger JT 9.5 Figure 136 one, which v10 deleted the
    // typed-entity half of. Each generation gets its own codec — see Pmi95Elements.kt.
    val decoder: ((ByteReader) -> TypedMetaDataElement)? =
        when (typeId) {
            ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT -> { r ->
                readPropertyProxyMetaDataElement(r, generation) {
                    localNotes.add(LoadNote.MetaPropertyValueTypeUnknown(location, it.key, it.typeCode))
                }
            }
            ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT ->
                if (generation == LsgGeneration.V9) {
                    { r ->
                        val decoded = readPmi95ManagerMetaDataElement(r)
                        decoded.cadTagRefusal?.let { localNotes.add(LoadNote.CadTagVectorsUnrecognized(location, it)) }
                        if (decoded.textureBindingElements > 0) {
                            localNotes.add(
                                LoadNote.PmiPolygonTextureBindingUnsettled(location, decoded.textureBindingElements),
                            )
                        }
                        if (decoded.element.textPolylineForm == Pmi95TextPolylineForm.EMPTY_VECTOR) {
                            localNotes.add(LoadNote.PmiTextPolylineVectorOffDocument(location))
                        }
                        decoded.element
                    }
                } else {
                    { r ->
                        readPmiManagerMetaDataElement(
                            r,
                            generation,
                            externallyCompressed,
                            { localNotes.add(LoadNote.PmiManagerTailUndocumented(location, it.byteCount)) },
                            { localNotes.add(LoadNote.CadTagVectorsUnrecognized(location, it)) },
                        )
                    }
                }
            else -> null
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
        notes.addAll(localNotes)
        element
    } catch (e: JtFormatException) {
        notes.add(LoadNote.ElementDecodeFailed(typeId, typeName, location, e.message ?: "decode failed"))
        opaque()
    }
}

internal fun encodeMetaElementFrame(
    w: ByteWriter,
    generation: LsgGeneration,
    element: MetaDataElement,
) {
    val bodyWriter = ByteWriter(w.order)
    when (element) {
        is OpaqueMetaDataElement -> bodyWriter.writeBytes(element.body)
        is PropertyProxyMetaDataElement -> writePropertyProxyMetaDataElement(bodyWriter, generation, element)
        is PmiManagerMetaDataElement -> {
            check(generation != LsgGeneration.V9) { "v10 PMI Manager element in a JT 9 document" }
            writePmiManagerMetaDataElement(bodyWriter, generation, element)
        }
        is Pmi95ManagerMetaDataElement -> {
            check(generation == LsgGeneration.V9) { "JT 9.5 PMI Manager element in a JT 10 document" }
            writePmi95ManagerMetaDataElement(bodyWriter, element)
        }
    }
    val body = bodyWriter.toByteArray()
    w.writeI32(16 + body.size)
    w.writeGuid(element.objectTypeId)
    w.writeBytes(body)
}
