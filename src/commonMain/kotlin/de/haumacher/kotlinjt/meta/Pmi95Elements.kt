package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.Vec3F32

/**
 * The **JT 9.5 PMI element family** (9.5 §7.2.6.2, Figures 136–170) — a second, older PMI wire
 * format that happens to share the v10 Object Type ID and segment kinds, not a version branch
 * inside [PmiManagerMetaDataElement].
 *
 * v10 **deleted** the typed-PMI-entity family. 9.5 Figure 136 reads, unconditionally and before
 * anything else, a *PMI Entities* block (Figure 137) that is a fixed sequence of thirteen typed
 * collections — Dimension, Note, Datum Feature Symbol, Datum Target, Feature Control Frame,
 * Line Weld, Spot Weld, Surface Finish, Measurement Point, Locator, Reference Geometry, Design
 * Group, Coordinate System. v10's Figure 110 has no such box: Design Group Entities is the only
 * survivor, promoted to a top-level sibling, and the other twelve are absent from v10's
 * normative text entirely, their semantics folded into *Generic PMI Entities* (which grew from
 * two 9.5 pages to eleven and gained sixteen entity-type codes). The corroboration is v10's own
 * CAD-tag index formula, which still sums the same fifteen entity counts — twelve of which v10
 * no longer defines anywhere.
 *
 * Nine further structural differences separate the two managers (see DESIGN.md, *The JT 9.5 PMI
 * element family*): a three-field vs two-field prologue, Design Groups' position, three
 * `PMI Version Number` gates v10 has none of, the font block's position inside a
 * `Version Number > 1` gate, the font name's string type, the character set's element width, the
 * model-view property list's location, and v10's two trailing collections. Sharing one codec
 * with a flag would be a trap: PMI Associations, for instance, permute five `I32` words, so a
 * v10-order read of a 9.5 association consumes exactly the right byte count and yields silently
 * wrong semantics.
 *
 * Everything here is **spec-derived**: no fixture in the corpus carries a JT 9 PMI Manager.
 * `Pmi95FixtureTest` is the auto-discovering hook that turns the first one into acceptance and
 * skips visibly until then.
 */
data class Pmi95ManagerMetaDataElement(
    override val objectId: Int,
    /** `I16 Version Number` of Figure 136 — 0x0001 and 0x0002 are the documented values. */
    val version: Int,
    /**
     * `I16 PMI Version Number` (3…8): the generation of the *PMI content*, which every guard in
     * this family keys off. 9.5 §7.2.6.2 keeps it separate from [version] because re-exported
     * PMI is never migrated — a JT 9.5 file can carry version-3 PMI.
     */
    val pmiVersion: Int,
    /** `I16 Reserved Field`, preserved as read. */
    val reservedField: Int,
    /** Figure 137's thirteen typed collections, always on the wire. */
    val entities: Pmi95Entities,
    val associations: List<Pmi95Association>,
    val userAttributes: List<PmiUserAttribute>,
    /** The central string repository every String ID of this element indexes into. */
    val stringTable: List<String>,
    /** Figure 136 gates Model Views on `PMI Version Number > 5`; `null` = not on the wire. */
    val modelViews: List<Pmi95ModelView>?,
    /** Gated on `PMI Version Number > 5` together with [modelViews]; `null` = not on the wire. */
    val genericEntities: List<Pmi95GenericEntity>?,
    /** `U32 CAD Tags Flag`, gated on `PMI Version Number > 7`; `null` = not on the wire. */
    val cadTagsFlag: Int?,
    /** Present exactly when [cadTagsFlag] is 1. */
    val cadTagData: Pmi95CadTagData?,
    /** Figure 136's `Version Number > 1` block; `null` = not on the wire. */
    val tail: Pmi95ManagerTail?,
    /**
     * Which of Figure 145's two forms this body's zero-length text polylines were written in.
     * Recorded, never normalized — the writer reproduces what was read (see
     * [Pmi95TextPolylineForm]).
     */
    val textPolylineForm: Pmi95TextPolylineForm,
) : TypedMetaDataElement() {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT

    /** [stringTable] lookup honoring the "−1 means no string" convention of every String ID. */
    fun string(stringId: Int): String? = if (stringId < 0 || stringId >= stringTable.size) null else stringTable[stringId]

    /**
     * §7.2.6.2.7's CAD Tag Index Count formula: one index per entity of all fifteen PMI kinds
     * that support CAD tags, in the order the section lists them. 9.5 can satisfy the formula
     * literally; the v10 reader sums only the three kinds v10 still defines.
     */
    val cadTagIndexCount: Int
        get() =
            entities.lineWelds.size + entities.spotWelds.size + entities.surfaceFinishes.size +
                entities.measurementPoints.size + entities.referenceGeometry.size +
                entities.datumTargets.size + entities.featureControlFrames.size +
                entities.locators.size + entities.dimensions.size +
                entities.datumFeatureSymbols.size + entities.notes.size +
                (modelViews?.size ?: 0) + entities.designGroups.size +
                entities.coordinateSystems.size + (genericEntities?.size ?: 0)
}

/**
 * Figure 136's `Version Number > 1` block: one PMI Property per Model View, the manager's own
 * polygon block, and the font definitions. In v10 this material moved — the per-view properties
 * into Figure 116's own loop, the fonts onto the unconditional spine — which is why 9.5 keeps it
 * as a distinct, gated record rather than as fields of the element.
 */
data class Pmi95ManagerTail(
    /** Exactly one property per Model View (Figure 136's `I32 : Model View Count` loop label). */
    val modelViewProperties: List<Pmi95Property>,
    val polygonData: Pmi95PolygonData,
    val fonts: List<Pmi95Font>,
)

/**
 * PMI Entities (Figure 137): the thirteen typed collections, in the fixed order the figure's
 * arrows draw (left column top-to-bottom, then right column) — which is also the order of
 * §7.2.6.2.1.1 … §7.2.6.2.1.13. All thirteen are unconditional; an absent kind is a zero count.
 */
data class Pmi95Entities(
    /** Figure 138. */
    val dimensions: List<Pmi952dData>,
    /** Figure 148. */
    val notes: List<Pmi95Note>,
    /** Figure 149. */
    val datumFeatureSymbols: List<Pmi952dData>,
    /** Figure 150. */
    val datumTargets: List<Pmi952dData>,
    /** Figure 151. */
    val featureControlFrames: List<Pmi952dData>,
    /** Figure 152. */
    val lineWelds: List<Pmi952dData>,
    /** Figure 153. */
    val spotWelds: List<Pmi95SpotWeld>,
    /** Figure 155. */
    val surfaceFinishes: List<Pmi952dData>,
    /** Figure 156. */
    val measurementPoints: List<Pmi95MeasurementPoint>,
    /** Figure 157. */
    val locators: List<Pmi952dData>,
    /** Figure 158. */
    val referenceGeometry: List<Pmi953dData>,
    /** Figure 159. */
    val designGroups: List<Pmi95DesignGroup>,
    /** Figure 161. */
    val coordinateSystems: List<Pmi95CoordinateSystem>,
)

/**
 * PMI Base Data (Figure 140): what every 2D and 3D PMI entity carries. Two guards v10's
 * Figure 121 does not draw — the reference frame's `2D-Frame Flag != 0` (v10 keeps it in prose
 * only) and [symbolValidFlag]'s `PMI Version Number > 4`, which v10 has no counterpart for at
 * all.
 */
data class Pmi95BaseData(
    val userLabel: Int,
    /**
     * 2D-Frame Flag: 0 = no frame stored, non-zero = [referenceFrame] follows. Value 2 means the
     * frame is "dummy (i.e. all zeros)" per §7.2.6.2.1.1.1.1 — carried, never interpreted (the
     * v10 fixture writes 2 with fully populated frames).
     */
    val frameFlag: Int,
    val referenceFrame: Pmi2dReferenceFrame?,
    val textHeight: Float,
    /** `U8 Symbol Valid Flag`, gated on `PMI Version Number > 4`; `null` = not on the wire. */
    val symbolValidFlag: Int?,
)

/**
 * Non-Text Polyline Data (Figure 147). Three deltas from v10's Figure 128: the segment index is
 * `I16` (v10 `I32`), the type array is gated on `PMI Version Number > 4` (unconditional in v10),
 * and 9.5 has **no** Polyline Width Count / Polyline Width arrays at all.
 */
data class Pmi95NonTextPolylineData(
    val segmentIndices: List<Short>,
    /** Table of §7.2.6.2.1.1.1.3 polyline types; `null` = the gated pair is not on the wire. */
    val types: List<Short>?,
    /**
     * Vertex coordinates packed `[XY][XY]…` for every entity kind except Generic PMI Entities,
     * where §7.2.6.2.1.1.1.3 says they are already `[XYZ][XYZ]…`. The flat array is kept as the
     * wire carries it; no interpretation is imposed here.
     */
    val vertexCoords: List<Float>,
)

/** PMI 2D Data (Figure 139): the data format common to all 2D based PMI entities. */
data class Pmi952dData(
    val base: Pmi95BaseData,
    val texts: List<Pmi2dText>,
    val nonTextPolylines: Pmi95NonTextPolylineData,
)

/**
 * PMI 3D Data (Figure 154) — a 9.5-only collection with no v10 counterpart. Where v10 infers
 * the coordinate dimensionality from "is this a Generic PMI Entity", 9.5 writes it down.
 *
 * Unlike Figure 145's text polylines, the index loop here carries **no `> 0` guard**.
 */
data class Pmi953dData(
    val base: Pmi95BaseData,
    /** Index into the PMI String Table, −1 = no string. */
    val stringId: Int,
    /** 2 = `[XY][XY]…`, 3 = `[XYZ][XYZ]…`; carried as read. */
    val polylineDimensionality: Int,
    val segmentIndices: List<Short>,
    val vertexCoords: List<Float>,
)

/** One entry of PMI Note Entities (Figure 148). */
data class Pmi95Note(
    val data2d: Pmi952dData,
    /** `U32 URL Flag`, gated on `PMI Version Number > 5`; `null` = not on the wire. */
    val urlFlag: Int?,
)

/** The four vectors Figure 153 gates on `PMI Version Number >= 4`. */
data class Pmi95SpotWeldGeometry(
    val weldPoint: Vec3F32,
    val approachDirection: Vec3F32,
    val clampingDirection: Vec3F32,
    val normalDirection: Vec3F32,
)

/** One entry of PMI Spot Weld Entities (Figure 153). */
data class Pmi95SpotWeld(
    val data3d: Pmi953dData,
    /** `null` when `PMI Version Number < 4` put the four vectors off the wire. */
    val geometry: Pmi95SpotWeldGeometry?,
)

/** The four vectors Figure 156 gates on `PMI Version Number >= 4`. */
data class Pmi95MeasurementPointGeometry(
    val location: Vec3F32,
    val measurementDirection: Vec3F32,
    val coordinateDirection: Vec3F32,
    val normalDirection: Vec3F32,
)

/** One entry of PMI Measurement Point Entities (Figure 156). */
data class Pmi95MeasurementPoint(
    val data3d: Pmi953dData,
    /** `null` when `PMI Version Number < 4` put the four vectors off the wire. */
    val geometry: Pmi95MeasurementPointGeometry?,
)

/**
 * One entry of PMI Design Group Entities (Figure 159). v10's Figure 111 reads the attribute
 * block unconditionally; 9.5 gates it on `PMI Version Number >= 3`, so a group of an older PMI
 * generation is four bytes where the v10 codec would read eight.
 */
data class Pmi95DesignGroup(
    /** Index into the PMI String Table, −1 = no string. */
    val nameStringId: Int,
    /** `null` when `PMI Version Number < 3` put the attribute block off the wire. */
    val attributes: List<PmiDesignGroupAttribute>?,
)

/** One entry of PMI Coordinate System Entities (Figure 161) — 9.5-only, 40 bytes each. */
data class Pmi95CoordinateSystem(
    /** Index into the PMI String Table, −1 = no string. */
    val nameStringId: Int,
    val origin: Vec3F32,
    val xAxisPoint: Vec3F32,
    val yAxisPoint: Vec3F32,
)

/**
 * One entry of PMI Associations (Figure 162). Same five `I32` words as v10's Figure 113 in a
 * **different order** — 9.5 writes Source, Destination, Reason, then the two owners; v10 writes
 * Source, Source Owner, Reason, Destination, Destination Owner — and 9.5 gates the owners on
 * `PMI Version Number > 5`. Because every word is an `I32`, reading one the other way consumes
 * exactly the right number of bytes and produces silently wrong meaning; nothing but an
 * assertion on semantics can catch it.
 */
data class Pmi95Association(
    val sourceData: Int,
    val destinationData: Int,
    /** §7.2.6.2.2 reason code, carried as read (the value set is open). */
    val reasonCode: Int,
    /**
     * Index into the PMI String Table, −1 = the entity lives on this node's own segment;
     * `null` = `PMI Version Number < 6` put the field off the wire.
     */
    val sourceOwningEntityStringId: Int?,
    /** As [sourceOwningEntityStringId], for the destination. */
    val destinationOwningEntityStringId: Int?,
) {
    /** Bits 0–23 of [sourceData]: the entity identifier or index (§7.2.6.2.2's bit table). */
    val sourceEntityId: Int get() = sourceData and 0xFFFFFF

    /** Bits 24–30 of [sourceData]: the PMI / B-Rep entity type. */
    val sourceEntityType: Int get() = (sourceData shr 24) and 0x7F

    /** Bit 31 of [sourceData]: the Indirect Identifier Flag. */
    val sourceIndirect: Boolean get() = (sourceData shr 31) and 1 == 1

    val destinationEntityId: Int get() = destinationData and 0xFFFFFF

    val destinationEntityType: Int get() = (destinationData shr 24) and 0x7F

    val destinationIndirect: Boolean get() = (destinationData shr 31) and 1 == 1
}

/**
 * PMI Property Atom (Figure 168): the encoded `MbString` value plus the Hidden Flag — which 9.5
 * gates on `PMI Version Number > 6`. Three encodings therefore coexist across the library:
 * absent (9.5, PMI version ≤ 6), one byte (NX 10.5, DESIGN.md delta 32) and `U32` (documented
 * v10). A PMI Property is two atoms and atoms sit inside every generic entity, so getting this
 * wrong desynchronizes the largest collection in the element.
 */
data class Pmi95PropertyAtom(
    val value: String,
    /** 0 = not hidden, 1 = hidden; `null` = the flag is not on the wire. */
    val hiddenFlag: Int?,
)

/** PMI Property (Figure 167): a key/value pair of [Pmi95PropertyAtom]s. */
data class Pmi95Property(
    val key: Pmi95PropertyAtom,
    val value: Pmi95PropertyAtom,
)

/**
 * One entry of PMI Model Views (Figure 165): the same eleven fields as v10's Figure 116, and
 * **nothing else** — v10 appends `I32 Property Count` + PMI Properties inside its per-view loop,
 * where 9.5 keeps one property per view in the Figure 136 tail instead
 * ([Pmi95ManagerTail.modelViewProperties]).
 */
data class Pmi95ModelView(
    val eyeDirection: Vec3F32,
    /** Camera rotation angle about [eyeDirection], in degrees. */
    val angle: Float,
    val eyePosition: Vec3F32,
    val targetPoint: Vec3F32,
    /** X/Y/Z rotation angles of the model's axes, in degrees. */
    val viewAngle: Vec3F32,
    val viewportDiameter: Float,
    /** `F32 Reserved Field`, preserved as read. */
    val reservedF32: Float,
    /** `I32 Reserved Field`, preserved as read. */
    val reservedI32: Int,
    /** 1 = this is the active Model View. */
    val activeFlag: Int,
    val viewId: Int,
    /** Index into the PMI String Table, −1 = no string. */
    val viewNameStringId: Int,
)

/** One entry of Generic PMI Entities (Figure 166). */
data class Pmi95GenericEntity(
    val data2d: Pmi952dData,
    val properties: List<Pmi95Property>,
    /** Index into the PMI String Table, −1 = no string. */
    val entityTypeNameStringId: Int,
    /** Index into the PMI String Table, −1 = no string. */
    val parentTypeNameStringId: Int,
    /** §7.2.6.2.6 entity type, carried as read (v10's Table 60 is a superset of 9.5's list). */
    val entityType: Int,
    /** Parent type, carried as read. */
    val parentType: Int,
    /** `U16 User Flags`, gated on `PMI Version Number > 6`; `null` = not on the wire. */
    val userFlags: Int?,
)

/**
 * PMI Polygon Data (Figure 170) — a wholly different shape from v10's Figure 130. 9.5 opens
 * `I16 Version Number` + `I32 Reserved Field` (v10: `U8` + an element count), derives the
 * element count from `vNumVerts`' own length, and writes the three bindings and the polygon
 * dimension **inline per element** in the order Normal → Color → Texture, where v10 hoists them
 * into parallel `vBindings` (Color → Normal → Texture) and `vPolygonDimensions` vectors.
 *
 * Reachable twice per manager: the tail's own block and each font's glyph outlines.
 */
data class Pmi95PolygonData(
    val version: Int,
    /** `I32 Reserved Field`, preserved as read. */
    val reservedField: Int,
    /** `vNumVerts`: one entry per PolygonData element; zero entries carry no further data. */
    val vertexCounts: List<Int>,
    /** One entry per non-zero [vertexCounts] entry, in order. */
    val elements: List<PmiPolygonDataElement>,
)

/**
 * One font of a JT 9.5 PMI Manager (Figure 136): a **single-byte** `String` name and a `VecI32`
 * character set — v10 raised both to `MbString` and `VecU16`.
 */
data class Pmi95Font(
    val name: String,
    /** The character identifiers whose glyphs [glyphs] defines, in order. */
    val characterSet: List<Int>,
    val glyphs: Pmi95PolygonData,
)

/**
 * PMI CAD Tag Data (Figure 169) with its embedded §8.1.16 *Compressed CAD Tag Data* (9.5
 * Figure 242) **framed but not decoded**.
 *
 * 9.5's Figure 242 is not v10's Figure 154: it opens `I16 Version Number` where v10 has `U8`,
 * adds an `I32 CAD Tag Count` that gates the whole body, codes its vectors with `Int32CDP2`
 * under a `Lag1` predictor rather than the v10 third-generation `Int32CDP` with none, and splits
 * the 64-bit tags into two `Int32CDP2` halves (Figure 243) instead of an `Int64CDP`. That
 * collection belongs to the compression package, so the coded bytes are kept verbatim behind the
 * collection's own `Data Length` — exact extent, nothing guessed — and a
 * `CAD_TAG_VECTORS_UNRECOGNIZED` note names the refusal. Its time comes when the compression
 * package implements 9.5 §8.1.16, or when a fixture arrives that needs the tags.
 */
data class Pmi95CadTagData(
    /** One CAD Tag index per PMI entity, in the fifteen-count order of §7.2.6.2.7. */
    val indices: List<Int>,
    /** `I16 Version Number` of the CADTag element (Figure 242). */
    val version: Int,
    /** The inner `I32 Version Number` of the Compressed CAD Tag Data collection. */
    val innerVersion: Int,
    /** `I32 CAD Tag Count`; 0 puts the coded vectors off the wire entirely. */
    val cadTagCount: Int,
    /** The coded vectors verbatim — empty exactly when [cadTagCount] is 0. */
    val codedData: Bytes,
)

/**
 * Which form of Figure 145 a producer wrote where the `Polyline Segment Index Count` is zero.
 *
 * The figure's guard rectangle encloses **both** the index loop and `VecF32 Polyline Vertex
 * Coords` (9.5 Figure 145 and v10 Figure 126 agree, read from both page images), so a
 * document-conformant producer writes neither. NX 10.5 writes the empty `VecF32` anyway — its
 * empty 2D Text Data records are a fixed 48 bytes, 40 of scalars plus *two* zero counts
 * (DESIGN.md delta 36). Both are accepted; which one was seen is recorded on the element so
 * re-serialization stays byte-identical.
 *
 * The 9.5 reader resolves it by arbitration rather than by assumption: the JT 9.5 manager ends
 * at its font loop (nothing follows, unlike the v10 element's undocumented tail), so exact
 * consumption of the framed body is a decisive oracle. [FIGURE] is tried first; [EMPTY_VECTOR]
 * only when the figure's reading does not account for the body, and then a named note says so.
 */
enum class Pmi95TextPolylineForm {
    /** Figure 145 as drawn: a zero index count puts both arrays off the wire. */
    FIGURE,

    /** The producer form of DESIGN.md delta 36: the empty `VecF32` is written regardless. */
    EMPTY_VECTOR,
}
