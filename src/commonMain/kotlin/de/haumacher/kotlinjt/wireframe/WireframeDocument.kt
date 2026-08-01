package de.haumacher.kotlinjt.wireframe

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.JtSegment
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.encoding.CompressedCadTagData
import de.haumacher.kotlinjt.encoding.CompressedCurveData
import de.haumacher.kotlinjt.encoding.Int32Vector
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
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.Predictor

/**
 * Table 7's Object Base Type of a Wireframe Rep Element: 9 ("JtBase") in all five NIST bodies.
 */
private const val BASE_TYPE_JT_BASE = 9

/**
 * The typed document model of a Wireframe segment's element data (§10, Figure 103): the element
 * list closed by the end-of-elements marker, followed by the Figure-78 Property Table that
 * every real producer writes after its elements — empty (the six bytes `01 00 00 00 00 00`) in
 * all five Wireframe segments of the NIST fixture, exactly as in its shape LOD, meta data and
 * PMI segments.
 *
 * The losslessness seam is the one `LsgDocument`, `ShapeLodDocument` and `MetaDataDocument`
 * use: [decode] followed by [encode] reproduces the element data byte-identically, for decoded
 * and opaquely carried elements alike.
 */
data class WireframeDocument(
    val generation: LsgGeneration,
    val elements: List<WireframeElement>,
    /** Whether the element list was closed by the end-of-elements marker. */
    val elementsTerminated: Boolean,
    /** The trailing Property Table (Figure 78), `null` when missing (a note says why). */
    val propertyTable: PropertyTable?,
    /** Bytes after the recognized structure, preserved verbatim (empty on healthy streams). */
    val trailing: Bytes,
) {
    /** The wireframe reps of this segment, in element order. */
    val reps: List<WireframeRepElement> get() = elements.filterIsInstance<WireframeRepElement>()

    /** Serializes the document back to element-stream bytes — the exact inverse of [decode]. */
    fun encode(order: Endianness): Bytes {
        val writer = ByteWriter(order)
        for (element in elements) {
            val bodyWriter = ByteWriter(order)
            when (element) {
                is OpaqueWireframeElement -> bodyWriter.writeBytes(element.body)
                is WireframeRepElement -> writeWireframeRepElement(bodyWriter, element)
            }
            val body = bodyWriter.toByteArray()
            writer.writeI32(16 + body.size)
            writer.writeGuid(element.objectTypeId)
            writer.writeBytes(body)
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
         * Decodes the (already decompressed) element data of a Wireframe segment. Never throws
         * for content problems: whatever does not decode is carried opaquely and named by a
         * note.
         *
         * [externallyCompressed] tells the CDP readers which out-of-band form Figure 132/135
         * put on the wire; §10 says the Wireframe segment type "supports LZMA compression on
         * all element data", and every real body is compressed, so the default is `true`.
         */
        fun decode(
            elementData: Bytes,
            version: JtVersion,
            order: Endianness,
            externallyCompressed: Boolean = true,
        ): WireframeDecodeResult {
            val generation = LsgGeneration.of(version)
            val notes = mutableListOf<LoadNote>()
            val bytes = elementData.toByteArray()
            val reader = ByteReader(bytes, order)

            val elements = mutableListOf<WireframeElement>()
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
                val location = "wireframe element at offset $start"
                elements.add(
                    decodeWireframeElementBody(typeId, body, generation, order, externallyCompressed, notes, location),
                )
            }

            var table: PropertyTable? = null
            var trailing = Bytes.EMPTY
            if (!terminated) {
                notes.add(
                    LoadNote.WireframeStructureUnrecognized(
                        "element list breaks off at offset ${reader.position} of ${bytes.size}",
                    ),
                )
                trailing = Bytes.of(bytes, reader.position, bytes.size)
            } else if (reader.remaining == 0) {
                notes.add(LoadNote.PropertyTableMissing("wireframe stream ends after the element list"))
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
            return WireframeDecodeResult(
                WireframeDocument(generation, elements, terminated, table, trailing),
                notes,
            )
        }
    }
}

/** The outcome of a wireframe document decode: the document plus the named refusals, if any. */
data class WireframeDecodeResult(
    val document: WireframeDocument,
    val notes: List<LoadNote>,
)

/** All Wireframe segments of the file (Table 6 type 18), in TOC order. */
fun JtFile.wireframeSegments(): List<JtSegment> = segments.filter { it.kind == SegmentKind.WIREFRAME }

/**
 * Decodes the element data of [segment] into the typed wireframe document model; `null` when
 * the segment is not a Wireframe segment or Layer 0 produced no element data (its notes say
 * why). The out-of-band form follows the segment's *actual* compression state, so a stored
 * (algorithm 1) wireframe segment reads correctly too.
 */
fun JtFile.decodeWireframe(segment: JtSegment): WireframeDecodeResult? {
    if (segment.kind != SegmentKind.WIREFRAME) return null
    val elementData = segment.elementData ?: return null
    val compressed = (segment.compression?.algorithmCode ?: 1) != 1
    return WireframeDocument.decode(elementData, header.version, header.byteOrder, compressed)
}

// ---------------------------------------------------------------------------
// Element codecs
// ---------------------------------------------------------------------------

private fun decodeWireframeElementBody(
    typeId: Guid,
    body: ByteArray,
    generation: LsgGeneration,
    order: Endianness,
    externallyCompressed: Boolean,
    notes: MutableList<LoadNote>,
    location: String,
): WireframeElement {
    val scannedBaseType = if (body.isNotEmpty()) body[0].toInt() and 0xFF else null

    fun opaque(): WireframeElement = OpaqueWireframeElement(typeId, scannedBaseType, body.toBytes())

    val typeName = ObjectTypeIds.nameOf(typeId)
    if (typeName == null) {
        notes.add(LoadNote.UnknownElementType(typeId, location))
        return opaque()
    }
    if (typeId != ObjectTypeIds.WIREFRAME_REP_ELEMENT) {
        notes.add(LoadNote.ElementLayoutUnverified(typeId, typeName, generation.name, location))
        return opaque()
    }
    // The JT 9 generation's Wireframe Rep Element is a different wire format: the v9.5
    // reference's Figure 130 has an I16 version, Lag1-predicted index vectors and the JT 9
    // ("Mk. 2") CDP packets throughout the curve data. No v9 fixture carries a Wireframe
    // segment, so that layout stays unverified — and unguessed.
    if (generation == LsgGeneration.V9) {
        notes.add(LoadNote.ElementLayoutUnverified(typeId, typeName, generation.name, location))
        return opaque()
    }
    val localNotes = mutableListOf<LoadNote>()
    return try {
        val sub = ByteReader(body, order)
        val element =
            readWireframeRepElement(sub, externallyCompressed) {
                localNotes.add(LoadNote.CadTagVectorsUnrecognized(location, it))
            }
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

// spec: Figure 104
internal fun readWireframeRepElement(
    r: ByteReader,
    externallyCompressed: Boolean,
    onOpaqueCadTags: (String) -> Unit,
): WireframeRepElement {
    val baseType = r.readU8().toInt()
    if (baseType != BASE_TYPE_JT_BASE) {
        throw JtFormatException("object base type $baseType is not the wireframe base type $BASE_TYPE_JT_BASE")
    }
    val objectId = r.readI32()
    // §10.1's field description says U8; Figure 104's box says I16. The bytes of all five NIST
    // bodies say U8 — with I16 the Edge Count misaligns and nothing consumes to length.
    val version = r.readU8().toInt()
    val edgeCount = r.readI32()
    val mcsCurveCount = r.readI32()
    if (edgeCount < 0) throw JtFormatException("Edge Count $edgeCount is negative")
    if (mcsCurveCount < 0) throw JtFormatException("MCS Curve Count $mcsCurveCount is negative")

    var mcsCurveIndices: Int32Vector? = null
    var edgeTags: Int32Vector? = null
    if (edgeCount > 0) {
        // Revision B of the reference replaced Lag1 with NULL "in two places" — these two.
        mcsCurveIndices =
            Int32Vector(Int32Cdp.readV10(r, externallyCompressed = externallyCompressed), Predictor.NONE)
        edgeTags = Int32Vector(Int32Cdp.readV10(r, externallyCompressed = externallyCompressed), Predictor.NONE)
        if (mcsCurveIndices.size != edgeCount) {
            throw JtFormatException("MCS Curve Indices holds ${mcsCurveIndices.size} entries for $edgeCount edges")
        }
        if (edgeTags.size != edgeCount) {
            throw JtFormatException("Edge Tags holds ${edgeTags.size} entries for $edgeCount edges")
        }
        for (index in mcsCurveIndices.values) {
            if (index < 0 || index >= mcsCurveCount) {
                throw JtFormatException("MCS curve index $index is outside [0, $mcsCurveCount)")
            }
        }
    }

    // Wireframe MCS Curves Geometric Data (Figure 105) is exactly one Compressed Curve Data
    // collection; §10.1.1 says "currently only NURBS Curve types are supported", and these are
    // model-space (XYZ) curves, so Table 71 governs their dimensionality.
    val curves =
        if (mcsCurveCount > 0) {
            CompressedCurveData.read(r, mcsCurveCount, externallyCompressed, uvCurves = false)
        } else {
            null
        }

    val edgeTagCounter = r.readI32()
    val cadTagsFlag = r.readU32()
    // §10.1.2: "If Wireframe Rep CAD Tag Data collection is present, there will be a CAD Tag
    // for every Edge in the Wireframe Rep" — the count this validates against.
    val cadTagData =
        when (cadTagsFlag.toInt()) {
            0 -> null
            1 -> CompressedCadTagData.read(r, edgeCount, externallyCompressed, onOpaqueCadTags)
            else -> throw JtFormatException("CAD Tags Flag $cadTagsFlag is neither 0 nor 1")
        }
    return WireframeRepElement(
        objectId,
        version,
        edgeCount,
        mcsCurveCount,
        mcsCurveIndices,
        edgeTags,
        curves,
        edgeTagCounter,
        cadTagsFlag,
        cadTagData,
    )
}

internal fun writeWireframeRepElement(
    w: ByteWriter,
    element: WireframeRepElement,
) {
    w.writeU8(BASE_TYPE_JT_BASE.toUByte())
    w.writeI32(element.objectId)
    w.writeU8(element.version.toUByte())
    w.writeI32(element.edgeCount)
    w.writeI32(element.mcsCurveCount)
    if (element.edgeCount > 0) {
        checkNotNull(element.mcsCurveIndices) { "wireframe rep with edges but no MCS curve indices" }.encode(w)
        checkNotNull(element.edgeTags) { "wireframe rep with edges but no edge tags" }.encode(w)
    }
    if (element.mcsCurveCount > 0) {
        checkNotNull(element.mcsCurves) { "wireframe rep with curves but no curve data" }.encode(w)
    }
    w.writeI32(element.edgeTagCounter)
    w.writeU32(element.cadTagsFlag)
    element.cadTagData?.encode(w)
}
