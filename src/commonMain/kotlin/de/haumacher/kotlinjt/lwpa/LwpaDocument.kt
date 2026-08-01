package de.haumacher.kotlinjt.lwpa

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.JtSegment
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.SegmentKind
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

/** Table 7's Object Base Type of a JT LWPA Element: 9 ("JtBase"), as for every §8–§11 element. */
private const val BASE_TYPE_JT_BASE = 9

/**
 * The analytic surface types of Table 100 (the reference points §9's *Analytic Surface Type*
 * field at the ULP table). 6 and 7 are reserved; a value outside the table refuses the decode
 * rather than letting [AnalyticSurfaceGeometry]'s consumption rules run on an unknown type.
 */
enum class AnalyticSurfaceType(val code: Int) {
    NURBS(0),
    PLANE(1),
    CYLINDER(2),
    CONE(3),
    SPHERE(4),
    TORUS(5),
    ;

    companion object {
        fun ofCode(code: Int): AnalyticSurfaceType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * **Analytic Surface Geometry** (§9.1.1, Figure 101): the analytic surfaces of a part's B-Rep
 * and their mapping back onto the original B-Rep surface list. Four plain `VecF64` arrays hold
 * the numbers; Figure 102's flow chart says how many of each an individual surface consumes
 * (a cylinder a point, an axis and a radius; a torus two radii; and so on).
 */
data class AnalyticSurfaceGeometry(
    /** The index of each analytic surface in the original B-Rep surface list. */
    val surfaceIndices: Int32Vector,
    /** The Table 100 type code of each analytic surface. */
    val surfaceTypes: Int32Vector,
    val coordinates: List<Double>,
    val axes: List<Double>,
    val radii: List<Double>,
    val radians: List<Double>,
) {
    /** The Table 100 types, `null` for a code the table does not define. */
    val types: List<AnalyticSurfaceType?> get() = surfaceTypes.values.map { AnalyticSurfaceType.ofCode(it) }

    fun encode(w: ByteWriter) {
        surfaceIndices.encode(w)
        surfaceTypes.encode(w)
        w.writeVecF64(coordinates)
        w.writeVecF64(axes)
        w.writeVecF64(radii)
        w.writeVecF64(radians)
    }

    companion object {
        // spec: Figure 101
        internal fun read(
            r: ByteReader,
            analyticSurfaceCount: Int,
            externallyCompressed: Boolean,
        ): AnalyticSurfaceGeometry {
            val indices =
                Int32Vector(Int32Cdp.readV10(r, externallyCompressed = externallyCompressed), Predictor.LAG1)
            val types =
                Int32Vector(Int32Cdp.readV10(r, externallyCompressed = externallyCompressed), Predictor.NONE)
            if (indices.size != analyticSurfaceCount) {
                throw JtFormatException("Analytic Surface Indices holds ${indices.size} entries for $analyticSurfaceCount surfaces")
            }
            if (types.size != analyticSurfaceCount) {
                throw JtFormatException("Analytic Surface Type holds ${types.size} entries for $analyticSurfaceCount surfaces")
            }
            for (code in types.values) {
                if (AnalyticSurfaceType.ofCode(code) == null) {
                    throw JtFormatException("analytic surface type $code is outside Table 100 (0..5; 6 and 7 are reserved)")
                }
            }
            return AnalyticSurfaceGeometry(
                indices,
                types,
                r.readVecF64(),
                r.readVecF64(),
                r.readVecF64(),
                r.readVecF64(),
            )
        }
    }
}

/** One element of a JT LWPA segment's element list (§9, Figure 99). */
sealed interface LwpaElement {
    val objectTypeId: Guid
}

/** An LWPA element carried verbatim; a named note always says why. */
data class OpaqueLwpaElement(
    override val objectTypeId: Guid,
    val scannedBaseType: Int?,
    val body: Bytes,
) : LwpaElement

/**
 * **JT LWPA Element** (§9.1, Figure 100): "light weight precise analytic data" — the analytic
 * surfaces (plane, cylinder, cone, sphere, torus) of a part's B-Rep without any topology, curve
 * or point data. Typically under 2 % of the B-Rep's size on disk.
 *
 * **No fixture carries an LWPA segment.** This decoder is therefore *spec-derived*, and it is
 * implemented rather than deferred because Figures 100 and 101 leave nothing to infer: the two
 * `VecI32{Int32CDP}` fields have their length fixed by `Analytic Surface Count`, and the four
 * `VecF64` arrays are plain count-plus-values vectors written "in binary form" — no
 * quantization, no predictor, no hash. The strict full-consumption check on the element body
 * turns any wrong derivation into an opaque carry with a named note, never a misread.
 */
data class JtLwpaElement(
    val objectId: Int,
    /** U8 Version Number. */
    val version: Int,
    /** U32 Surface Count: one entry per surface of the B-Rep, analytic or not. */
    val surfaceCount: UInt,
    /** U32 Analytic Surface Count: how many of them are analytic. */
    val analyticSurfaceCount: UInt,
    /** The analytic surfaces; `null` when [analyticSurfaceCount] is 0 (nothing on the wire). */
    val geometry: AnalyticSurfaceGeometry?,
) : LwpaElement {
    override val objectTypeId: Guid get() = ObjectTypeIds.JT_LWPA_ELEMENT
}

/**
 * The typed document model of a JT LWPA segment's element data (§9, Figure 99): the element list
 * closed by the end-of-elements marker, plus the Figure-78 Property Table that every real
 * producer writes after its elements. Same seam, same losslessness guarantee as the LSG, shape
 * LOD, meta data and wireframe documents.
 */
data class LwpaDocument(
    val generation: LsgGeneration,
    val elements: List<LwpaElement>,
    val elementsTerminated: Boolean,
    val propertyTable: PropertyTable?,
    val trailing: Bytes,
) {
    /** The LWPA elements of this segment, in element order. */
    val analyticReps: List<JtLwpaElement> get() = elements.filterIsInstance<JtLwpaElement>()

    fun encode(order: Endianness): Bytes {
        val writer = ByteWriter(order)
        for (element in elements) {
            val bodyWriter = ByteWriter(order)
            when (element) {
                is OpaqueLwpaElement -> bodyWriter.writeBytes(element.body)
                is JtLwpaElement -> writeJtLwpaElement(bodyWriter, element)
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
        /** Decodes the (already decompressed) element data of a JT LWPA segment. Never throws for content. */
        fun decode(
            elementData: Bytes,
            version: JtVersion,
            order: Endianness,
            externallyCompressed: Boolean = true,
        ): LwpaDecodeResult {
            val generation = LsgGeneration.of(version)
            val notes = mutableListOf<LoadNote>()
            val bytes = elementData.toByteArray()
            val reader = ByteReader(bytes, order)

            val elements = mutableListOf<LwpaElement>()
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
                val location = "LWPA element at offset $start"
                elements.add(decodeLwpaElementBody(typeId, body, generation, order, externallyCompressed, notes, location))
            }

            var table: PropertyTable? = null
            var trailing = Bytes.EMPTY
            if (!terminated) {
                notes.add(
                    LoadNote.LwpaStructureUnrecognized(
                        "element list breaks off at offset ${reader.position} of ${bytes.size}",
                    ),
                )
                trailing = Bytes.of(bytes, reader.position, bytes.size)
            } else if (reader.remaining == 0) {
                notes.add(LoadNote.PropertyTableMissing("LWPA stream ends after the element list"))
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
            return LwpaDecodeResult(LwpaDocument(generation, elements, terminated, table, trailing), notes)
        }
    }
}

/** The outcome of an LWPA document decode: the document plus the named refusals, if any. */
data class LwpaDecodeResult(
    val document: LwpaDocument,
    val notes: List<LoadNote>,
)

/** All JT LWPA segments of the file (Table 6 type 24), in TOC order. */
fun JtFile.lwpaSegments(): List<JtSegment> = segments.filter { it.kind == SegmentKind.LWPA }

/**
 * Decodes the element data of [segment] into the typed LWPA document model; `null` when the
 * segment is not an LWPA segment or Layer 0 produced no element data.
 */
fun JtFile.decodeLwpa(segment: JtSegment): LwpaDecodeResult? {
    if (segment.kind != SegmentKind.LWPA) return null
    val elementData = segment.elementData ?: return null
    val compressed = (segment.compression?.algorithmCode ?: 1) != 1
    return LwpaDocument.decode(elementData, header.version, header.byteOrder, compressed)
}

// ---------------------------------------------------------------------------
// Element codec
// ---------------------------------------------------------------------------

private fun decodeLwpaElementBody(
    typeId: Guid,
    body: ByteArray,
    generation: LsgGeneration,
    order: Endianness,
    externallyCompressed: Boolean,
    notes: MutableList<LoadNote>,
    location: String,
): LwpaElement {
    val scannedBaseType = if (body.isNotEmpty()) body[0].toInt() and 0xFF else null

    fun opaque(): LwpaElement = OpaqueLwpaElement(typeId, scannedBaseType, body.toBytes())

    val typeName = ObjectTypeIds.nameOf(typeId)
    if (typeName == null) {
        notes.add(LoadNote.UnknownElementType(typeId, location))
        return opaque()
    }
    // The v9.5 reference documents no LWPA element at all (the segment type exists in its
    // Table 3, the element does not), so only the v10 generation decodes; V9 carries opaquely.
    if (typeId != ObjectTypeIds.JT_LWPA_ELEMENT || generation == LsgGeneration.V9) {
        notes.add(LoadNote.ElementLayoutUnverified(typeId, typeName, generation.name, location))
        return opaque()
    }
    return try {
        val sub = ByteReader(body, order)
        val element = readJtLwpaElement(sub, externallyCompressed)
        if (sub.remaining != 0) {
            throw JtFormatException("${sub.remaining} bytes of the element body were not consumed")
        }
        element
    } catch (e: JtFormatException) {
        notes.add(LoadNote.ElementDecodeFailed(typeId, typeName, location, e.message ?: "decode failed"))
        opaque()
    }
}

// spec: Figure 100
internal fun readJtLwpaElement(
    r: ByteReader,
    externallyCompressed: Boolean,
): JtLwpaElement {
    val baseType = r.readU8().toInt()
    if (baseType != BASE_TYPE_JT_BASE) {
        throw JtFormatException("object base type $baseType is not the LWPA base type $BASE_TYPE_JT_BASE")
    }
    val objectId = r.readI32()
    val version = r.readU8().toInt()
    val surfaceCount = r.readU32()
    val analyticSurfaceCount = r.readU32()
    if (analyticSurfaceCount > surfaceCount) {
        throw JtFormatException(
            "Analytic Surface Count $analyticSurfaceCount exceeds the Surface Count $surfaceCount",
        )
    }
    if (analyticSurfaceCount > Int.MAX_VALUE.toUInt()) {
        throw JtFormatException("Analytic Surface Count $analyticSurfaceCount is not addressable")
    }
    val geometry =
        if (analyticSurfaceCount > 0u) {
            AnalyticSurfaceGeometry.read(r, analyticSurfaceCount.toInt(), externallyCompressed)
        } else {
            null
        }
    return JtLwpaElement(objectId, version, surfaceCount, analyticSurfaceCount, geometry)
}

internal fun writeJtLwpaElement(
    w: ByteWriter,
    element: JtLwpaElement,
) {
    w.writeU8(BASE_TYPE_JT_BASE.toUByte())
    w.writeI32(element.objectId)
    w.writeU8(element.version.toUByte())
    w.writeU32(element.surfaceCount)
    w.writeU32(element.analyticSurfaceCount)
    if (element.analyticSurfaceCount > 0u) {
        checkNotNull(element.geometry) { "LWPA element with analytic surfaces but no geometry" }.encode(w)
    }
}

/** `VecF64` (the Symbols table of §4.2): an I32 count followed by that many `F64`s. */
private fun ByteReader.readVecF64(): List<Double> {
    val count = readI32()
    if (count < 0 || count.toLong() * 8 > remaining) {
        throw JtFormatException("VecF64 count $count does not fit the remaining $remaining bytes")
    }
    return List(count) { readF64() }
}

private fun ByteWriter.writeVecF64(values: List<Double>) {
    writeI32(values.size)
    for (value in values) writeF64(value)
}
