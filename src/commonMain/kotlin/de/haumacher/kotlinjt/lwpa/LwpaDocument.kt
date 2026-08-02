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

/**
 * Object Base Type of a JT LWPA Element: 9 ("JtBase"), as for every §8–§11 element — v10
 * Table 7 and JT 9.5 Table 4 (p.31) agree on the code, the name and the "none" data format,
 * so the same base-type check governs both generations.
 */
private const val BASE_TYPE_JT_BASE = 9

/**
 * The analytic surface types of the *Supported Surface Type* table — v10 numbers it Table 100
 * (Annex G, JT ULP) and §9 borrows it; JT 9.5 leaves it unnumbered (p.210, inside §7.2.7) and
 * §7.2.9.1.1 borrows it the same way. **The two tables are value-for-value identical**
 * (0 Nurbs, 1 Plane, 2 Cylinder, 3 Cone, 4 Sphere, 5 Torus, 6/7 Reserved), so one enum serves
 * both generations. 6 and 7 are reserved; a value outside the table refuses the decode rather
 * than letting [AnalyticSurfaceGeometry]'s consumption rules run on an unknown type.
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
 * **Analytic Surface Geometry** (v10 §9.1.1 Figure 101 / JT 9.5 §7.2.9.1.1 Figure 216): the
 * analytic surfaces of a part's B-Rep and their mapping back onto the original B-Rep surface
 * list. Four plain `VecF64` arrays hold the numbers; the *Analytic Surface Creation* flow chart
 * (9.5 Figure 217; v10 Figure 102 — the same chart box for box) says how many of each an individual
 * surface consumes — a cylinder a point, an axis and a radius; a torus two radii; and so on.
 *
 * **The two figures are the same collection with one delta.** Member list, order, predictors
 * (`Lag1` on the indices, `NULL` on the types) and the four plain `VecF64` arrays are identical;
 * 9.5 names the integer codec `Int32CDP2` (the Mk. 2 packet of its §8.1.2) where v10 names it
 * `Int32CDP` (the third-generation packet of its §12.1.1). Those are different framings, so the
 * decode dispatches on the generation — see [read].
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
        // spec: Figure 101 (v10) / 9.5 Figure 216
        internal fun read(
            r: ByteReader,
            analyticSurfaceCount: Int,
            generation: LsgGeneration,
            externallyCompressed: Boolean,
        ): AnalyticSurfaceGeometry {
            fun packet() =
                when (generation) {
                    // 9.5 Figure 216: `Int32CDP2` — the Mk. 2 packet of 9.5 §8.1.2. It has no
                    // out-of-band branch to select, which is why nothing is passed here.
                    LsgGeneration.V9 -> Int32Cdp.read(r)
                    // v10 Figure 101: `Int32CDP` — the third-generation packet of §12.1.1.
                    LsgGeneration.V10, LsgGeneration.V10_5 ->
                        Int32Cdp.readV10(r, externallyCompressed = externallyCompressed)
                }

            val indices = Int32Vector(packet(), Predictor.LAG1)
            val types = Int32Vector(packet(), Predictor.NONE)
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
 * **JT LWPA Element** (v10 §9.1 Figure 100 / JT 9.5 §7.2.9.1 Figure 215): "light weight precise
 * analytic data" — the analytic surfaces (plane, cylinder, cone, sphere, torus) of a part's
 * B-Rep without any topology, curve or point data. Typically under 2 % of the B-Rep's size on
 * disk.
 *
 * **Both generations decode.** The figures draw the same four fields in the same order under the
 * same single `Analytic Surface Count > 0` guard; the deltas are widths and one codec:
 *
 * | field | JT 9.5 Figure 215 | v10 Figure 100 | on the wire |
 * |---|---|---|---|
 * | Version Number | `I16` | `U8` | **one byte apart** — everything after it misaligns |
 * | Surface Count | `I32` | `U32` | four bytes in both; only the sign of the high bit differs |
 * | Analytic Surface Count | `I32` | `U32` | four bytes in both; likewise |
 * | Analytic Surface Geometry | Figure 216, `Int32CDP2` | Figure 101, `Int32CDP` | different packet framings |
 *
 * So exactly one of the three "width" deltas moves a byte boundary, and it moves every later
 * field: a 9.5 element read the v10 way desynchronizes at the Surface Count. The counts are kept
 * as [UInt] for both generations because the wire width is the same; the 9.5 read refuses a
 * negative value by name rather than laundering it into a large unsigned one.
 *
 * **No fixture carries an LWPA segment**, in either generation. Both decoders are therefore
 * *spec-derived*, and they are implemented rather than deferred because the figures leave nothing
 * to infer: the two `VecI32` fields have their length fixed by `Analytic Surface Count`, and the
 * four `VecF64` arrays are plain count-plus-values vectors written "in binary form" — no
 * quantization, no predictor, no hash. The strict full-consumption check on the element body
 * turns any wrong derivation into an opaque carry with a named note, never a misread.
 */
data class JtLwpaElement(
    val objectId: Int,
    /** Version Number: `I16` in the JT 9 generation, `U8` in v10. Only value 1 is defined in either. */
    val version: Int,
    /** Surface Count (`I32` in 9.5, `U32` in v10): one entry per surface of the B-Rep, analytic or not. */
    val surfaceCount: UInt,
    /** Analytic Surface Count (`I32` in 9.5, `U32` in v10): how many of them are analytic. */
    val analyticSurfaceCount: UInt,
    /** The analytic surfaces; `null` when [analyticSurfaceCount] is 0 (nothing on the wire). */
    val geometry: AnalyticSurfaceGeometry?,
) : LwpaElement {
    override val objectTypeId: Guid get() = ObjectTypeIds.JT_LWPA_ELEMENT
}

/**
 * The typed document model of a JT LWPA segment's element data (v10 §9 Figure 99 / JT 9.5 §7.2.9
 * Figure 214 — both draw the segment as Segment Header plus JT LWPA Element): the element list
 * closed by the end-of-elements marker, plus the Figure-78 Property Table that every real
 * producer writes after its elements. Same seam, same losslessness guarantee as the LSG, shape
 * LOD, meta data and wireframe documents.
 *
 * [generation] selects the element layout on both the read and the write side, so a document is
 * re-serialized in the dialect it was read in.
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
                is JtLwpaElement -> writeJtLwpaElement(bodyWriter, element, generation)
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

/** All JT LWPA segments of the file (v10 Table 6 / JT 9.5 Table 3, type 24 in both), in TOC order. */
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
    // Both generations document the element: v10 §9.1 Figure 100 and JT 9.5 §7.2.9.1 Figure 215
    // (also listed in 9.5 Annex A Table 11 under segment type 24, with this very GUID). Any
    // *other* named type turning up in an LWPA segment has no documented place there.
    if (typeId != ObjectTypeIds.JT_LWPA_ELEMENT) {
        notes.add(LoadNote.ElementLayoutUnverified(typeId, typeName, generation.name, location))
        return opaque()
    }
    return try {
        val sub = ByteReader(body, order)
        val element = readJtLwpaElement(sub, generation, externallyCompressed)
        if (sub.remaining != 0) {
            throw JtFormatException("${sub.remaining} bytes of the element body were not consumed")
        }
        element
    } catch (e: JtFormatException) {
        notes.add(LoadNote.ElementDecodeFailed(typeId, typeName, location, e.message ?: "decode failed"))
        opaque()
    }
}

/**
 * Version Number is `I16` in the JT 9 generation and one byte in v10 — the pervasive generational
 * delta, and here the only one that moves a byte boundary (9.5 Figure 215 vs v10 Figure 100).
 */
private fun ByteReader.readLwpaVersion(generation: LsgGeneration): Int =
    when (generation) {
        LsgGeneration.V9 -> readI16().toInt()
        LsgGeneration.V10, LsgGeneration.V10_5 -> readU8().toInt()
    }

private fun ByteWriter.writeLwpaVersion(
    generation: LsgGeneration,
    value: Int,
) {
    when (generation) {
        LsgGeneration.V9 -> writeI16(value.toShort())
        LsgGeneration.V10, LsgGeneration.V10_5 -> writeU8(value.toUByte())
    }
}

/**
 * The two counts: `I32` in 9.5 Figure 215, `U32` in v10 Figure 100 — four bytes either way, so
 * the model keeps a [UInt] and the generation only decides whether the high bit is a sign. A 9.5
 * producer writing a negative count is refused by name; nothing here launders it into 4 billion
 * surfaces.
 */
private fun ByteReader.readLwpaCount(
    generation: LsgGeneration,
    what: String,
): UInt =
    when (generation) {
        LsgGeneration.V9 -> {
            val value = readI32()
            if (value < 0) throw JtFormatException("$what $value is negative (9.5 Figure 215 writes it as I32)")
            value.toUInt()
        }
        LsgGeneration.V10, LsgGeneration.V10_5 -> readU32()
    }

// spec: Figure 100 (v10) / 9.5 Figure 215
internal fun readJtLwpaElement(
    r: ByteReader,
    generation: LsgGeneration,
    externallyCompressed: Boolean,
): JtLwpaElement {
    val baseType = r.readU8().toInt()
    if (baseType != BASE_TYPE_JT_BASE) {
        throw JtFormatException("object base type $baseType is not the LWPA base type $BASE_TYPE_JT_BASE")
    }
    val objectId = r.readI32()
    val version = r.readLwpaVersion(generation)
    val surfaceCount = r.readLwpaCount(generation, "Surface Count")
    val analyticSurfaceCount = r.readLwpaCount(generation, "Analytic Surface Count")
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
            AnalyticSurfaceGeometry.read(r, analyticSurfaceCount.toInt(), generation, externallyCompressed)
        } else {
            null
        }
    return JtLwpaElement(objectId, version, surfaceCount, analyticSurfaceCount, geometry)
}

internal fun writeJtLwpaElement(
    w: ByteWriter,
    element: JtLwpaElement,
    generation: LsgGeneration,
) {
    w.writeU8(BASE_TYPE_JT_BASE.toUByte())
    w.writeI32(element.objectId)
    w.writeLwpaVersion(generation, element.version)
    // `I32` (9.5) and `U32` (v10) are the same four bytes; the read refused any 9.5 value whose
    // high bit was set, so writing the unsigned form back reproduces the input exactly.
    w.writeU32(element.surfaceCount)
    w.writeU32(element.analyticSurfaceCount)
    if (element.analyticSurfaceCount > 0u) {
        checkNotNull(element.geometry) { "LWPA element with analytic surfaces but no geometry" }.encode(w)
    }
}

/**
 * `VecF64`: an I32 count followed by that many `F64`s — the composite type of v10's Symbols table
 * (§4.2) and of JT 9.5's Table 2, identically defined, and used *bare* here (no CODEC, no
 * predictor) in both figures. This is why the 9.5 LWPA element needs no `Float64CDP`.
 */
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
