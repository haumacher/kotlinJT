package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid

/**
 * One element of the LSG segment (§6): either a typed, immutable mirror of a spec data
 * collection, or — the backstop one level below opaque segments — an [OpaqueLsgElement]
 * carrying the raw bytes of a type the model does not decode. Decoding failures never lose
 * bytes and never escape as exceptions: they fall back to the opaque form with a named note.
 */
sealed class LsgElement {
    /** The Object Type ID (Annex A) this element was framed with. */
    abstract val objectTypeId: Guid
}

/**
 * An element carried opaquely: unknown Object Type ID, a type without an established wire
 * layout for the file's version, or a failed decode of a known type (each case named by a
 * load note). [body] preserves everything after the Object Type ID byte-faithfully.
 */
data class OpaqueLsgElement(
    override val objectTypeId: Guid,
    /** The Object Base Type byte as scanned, `null` when the body is empty. */
    val objectBaseType: Int?,
    /** The element body after the 16 Object Type ID bytes, preserved verbatim. */
    val body: Bytes,
) : LsgElement()

/** A fully decoded element; [objectId] is the identifier other elements reference it by. */
sealed class TypedLsgElement : LsgElement() {
    abstract val objectId: Int
}

// ---------------------------------------------------------------------------
// Shared data collections
// ---------------------------------------------------------------------------

/** Base Node Data (Figure 22): flags and the attribute references of a node. */
data class BaseNodeData(
    val version: Int,
    val nodeFlags: UInt,
    val attributeObjectIds: List<Int>,
)

/** Group Node Data (Figure 26): base node data plus the ordered child references. */
data class GroupNodeData(
    val base: BaseNodeData,
    val version: Int,
    val childNodeObjectIds: List<Int>,
)

/** Meta Data Node Data (Figure 30). */
data class MetaDataNodeData(
    val group: GroupNodeData,
    val version: Int,
)

/**
 * LOD Node Data (Figure 32). JT 9 carries two reserved fields (a VecF32 and an I32) that
 * JT 10 dropped — they are kept in the model so both generations re-encode byte-identically
 * (fixture-verified, see DESIGN.md).
 */
data class LodNodeData(
    val group: GroupNodeData,
    val version: Int,
    /** Reserved VecF32 field, on the wire in JT 9 only. */
    val reservedVector: List<Float> = emptyList(),
    /** Reserved I32 field, on the wire in JT 9 only. */
    val reservedField: Int = 0,
)

/**
 * Base Shape Data (Figure 36). JT 9 carries a reserved leading BBoxF32 that JT 10 dropped
 * ([reservedBBox], fixture-verified); it is `null` for JT 10 elements.
 */
data class BaseShapeData(
    val base: BaseNodeData,
    val version: Int,
    /** Reserved bounding box, on the wire in JT 9 only. */
    val reservedBBox: BBoxF32?,
    val untransformedBBox: BBoxF32,
    val area: Float,
    val vertexCountRange: CountRange,
    val nodeCountRange: CountRange,
    val polygonCountRange: CountRange,
    val size: UInt,
    val compressionLevel: Float,
)

/**
 * Quantization Parameters as JT 9 stores them inside Vertex Shape Data (four U8 fields);
 * absent from the JT 10 vertex shape node layout.
 */
data class QuantizationParameters(
    val bitsPerVertex: Int,
    val normalBitsFactor: Int,
    val bitsPerTextureCoord: Int,
    val bitsPerColour: Int,
)

/**
 * Vertex Shape Data (Figure 39). JT 10 stores the version and one U64 vertex binding field.
 * JT 9 additionally stores [quantizationParameters] and — for version 2 — a second U64
 * binding field ([vertexBindings2]); both are `null` for JT 10 elements (see DESIGN.md for
 * the byte evidence and its limits).
 */
data class VertexShapeData(
    val shape: BaseShapeData,
    val version: Int,
    val vertexBindings: ULong,
    /** On the wire in JT 9 only. */
    val quantizationParameters: QuantizationParameters?,
    /**
     * 9.5 Figure 30's second `U64 : Vertex Binding`, on the wire in JT 9 only. The figure
     * guards it with `Version Number == 1`, which under §9.4's append-only local versions
     * means "belongs to local version 1", i.e. present from version 1 upwards — but presence
     * is resolved from the body's remaining length and recorded here, never re-derived from
     * the version on write.
     */
    val vertexBindings2: ULong?,
)

/**
 * Base Attribute Data (v10 Figure 46 / 9.5 Figure 39). JT 10 added the Field Final Flags word;
 * JT 9 attributes carry `null` there (9.5 Figure 39, p.55, has three fields and no fourth).
 */
data class BaseAttributeData(
    val version: Int,
    /**
     * State Flags — the same byte in both generations, but bit `0x01` means opposite things:
     * 9.5 §7.2.1.1.2.1.1 (p.55) assigns it the attribute-wide **Accumulation Final** flag,
     * while v10 Table 15 declares it **Unused**, having moved per-field finals into
     * [fieldFinalFlags]. Read it through [accumulationFinal], never directly. Bits `0x02`
     * Force, `0x04` Ignore and `0x08` Persistable are identical in both.
     */
    val stateFlags: Int,
    /**
     * Field Inhibit Flags — one `U32` with the same layout in both generations, but the
     * per-element *bit assignments* differ: 9.5 p.60 gives the Material element a
     * "Diffuse Color and Alpha (Legacy)" row at bit 1 that v10 Table 16 lacks, so v10's
     * assignments for bits 1–8 are 9.5's shifted down by one (the same shape of shift affects
     * the Texture Image element: 9.5 puts Internal Compression Level on bit 8, v10 on bit 7).
     * The bits are therefore carried verbatim and never interpreted here; any interpretation
     * has to branch on the generation.
     */
    val fieldInhibitFlags: UInt,
    /** On the wire in JT 10 only. */
    val fieldFinalFlags: UInt?,
    /**
     * The JT 10.5 generation appends an I32 to every element carrying Base Attribute Data —
     * at the *end* of the element body, after the type-specific fields (observed −1 across
     * all 88 attribute elements of the 10.5 fixture; not documented by the v10.0 reference —
     * DESIGN.md delta 24). `null` in the V9/V10 generations.
     */
    val reservedTail: Int? = null,
) {
    /**
     * Whether this attribute declares its accumulation *final* through the attribute-wide
     * State Flags bit — a JT 9 concept only. v10 Table 15 declares bit `0x01` Unused and
     * expresses finality per field in [fieldFinalFlags], so reading the bit on a v10 attribute
     * would invent a meaning the document does not give it. The generation is legible from the
     * model itself: [fieldFinalFlags] is non-null exactly for the generations that carry the
     * word (9.5 Figure 39 has no such field).
     */
    val accumulationFinal: Boolean
        get() = fieldFinalFlags == null && (stateFlags and 0x01) != 0
}

/** Base Property Atom Data (Figure 70). */
data class BasePropertyAtomData(
    val version: Int,
    val stateFlags: UInt,
)

// ---------------------------------------------------------------------------
// Node elements (§6.1.1)
// ---------------------------------------------------------------------------

/** A node of the LSG graph; every node carries [BaseNodeData] somewhere in its collections. */
sealed class NodeElement : TypedLsgElement() {
    abstract val baseNode: BaseNodeData
}

/**
 * The ordered child node references of this node — group-family nodes list them in their
 * Group Node Data; an instance node references exactly one shared child (§13.9: "not
 * fundamentally different from a Group Node Element having only one child"); leaves none.
 */
val NodeElement.childObjectIds: List<Int>
    get() =
        when (this) {
            is InstanceNodeElement -> listOf(childNodeObjectId)
            is PartitionNodeElement -> group.childNodeObjectIds
            is GroupNodeElement -> group.childNodeObjectIds
            is SwitchNodeElement -> group.childNodeObjectIds
            is LodNodeElement -> lod.group.childNodeObjectIds
            is RangeLodNodeElement -> lod.group.childNodeObjectIds
            is PartNodeElement -> metaData.group.childNodeObjectIds
            is MetaDataNodeElement -> metaData.group.childNodeObjectIds
            is BaseNodeElement, is ShapeNodeElement -> emptyList()
        }

/** Base Node Element (Figure 21). */
data class BaseNodeElement(
    override val objectId: Int,
    override val baseNode: BaseNodeData,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.BASE_NODE
}

/**
 * Partition Node Element (v10 Figure 23 / 9.5 Figure 14): an external-file reference or the
 * LSG root.
 *
 * Exactly one `BBoxF32` sits between the file name and the area, and *which* one it is differs
 * by generation (DESIGN.md delta 43): v10 always stores the Transformed BBox; 9.5 Figure 14
 * puts a `BBoxF32 : Reserved Field` on the main path and reaches
 * `BBoxF32 : Transformed BBox` only through the branch guarded
 * `(Partition Flags & 0x00000001) == 0`. The byte count is the same either way, so the two
 * readings are indistinguishable by length — which is why the model discriminates them:
 * [reservedBBox] and [transformedBBox] are never both set, and never both absent.
 */
data class PartitionNodeElement(
    override val objectId: Int,
    val group: GroupNodeData,
    /** JT 10.5 inserts a version number before the flags (DESIGN.md delta 23); else `null`. */
    val version: Int?,
    val partitionFlags: Int,
    val fileName: String,
    /**
     * The NCS-aligned transformed geometry extent — on the wire in v10 unconditionally, and in
     * 9.5 only when [partitionFlags] bit 0 is *clear*. `null` when the file stored
     * [reservedBBox] in this slot instead.
     */
    val transformedBBox: BBoxF32?,
    val area: Float,
    val vertexCountRange: CountRange,
    val nodeCountRange: CountRange,
    val polygonCountRange: CountRange,
    /**
     * Present when bit 0 of [partitionFlags] is set — except that the JT 10.5 producer
     * observed (Siemens DM 9.8) sets the bit without storing the box (DESIGN.md delta 23).
     */
    val untransformedBBox: BBoxF32?,
    /**
     * 9.5 Figure 14's `BBoxF32 : Reserved Field`, stored in the transformed box's slot when
     * [partitionFlags] bit 0 is set — "reserved for future JT format expansion", and in both
     * 9.5 fixtures the empty-box sentinel (`min = +FLT_MAX`, `max = −FLT_MAX`), which is not
     * an extent and must never be read as one. Always `null` in the JT 10 generations, which
     * have no such field. Declared last so that JT 10 construction sites need not name it.
     */
    val reservedBBox: BBoxF32? = null,
) : NodeElement() {
    init {
        // Bit 0 announces the box (Figure 23/Table 11) — but the 10.5 producer observed
        // sets the bit without storing it (DESIGN.md delta 23), so only the reverse
        // direction is an invariant: a stored box requires the announcing bit.
        require(untransformedBBox == null || partitionFlags and 1 != 0) {
            "an untransformed bounding box requires partition flag bit 0"
        }
        require((reservedBBox == null) != (transformedBBox == null)) {
            "a partition node stores exactly one middle bounding box: the reserved field (9.5 " +
                "Figure 14, partition flag bit 0 set) or the transformed box"
        }
        require(reservedBBox == null || partitionFlags and 1 != 0) {
            "9.5 Figure 14 reaches the reserved bounding box only with partition flag bit 0 set"
        }
    }

    /** The single box the wire carries between File Name and Area, whatever its identity. */
    internal val middleBBox: BBoxF32 get() = reservedBBox ?: transformedBBox!!

    /**
     * The box a consumer may read as this partition's declared geometry extent: the
     * Transformed BBox where the file stores one, else the Untransformed BBox that 9.5 stores
     * in its place when the reserved field occupies the transformed slot. `null` when the node
     * declares no extent at all. Never the reserved field — that one is not an extent.
     */
    val extentBBox: BBoxF32? get() = transformedBBox ?: untransformedBBox

    override val objectTypeId: Guid get() = ObjectTypeIds.PARTITION_NODE
    override val baseNode: BaseNodeData get() = group.base
}

/** Group Node Element (Figure 25). */
data class GroupNodeElement(
    override val objectId: Int,
    val group: GroupNodeData,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.GROUP_NODE
    override val baseNode: BaseNodeData get() = group.base
}

/** Instance Node Element (Figure 27): a shared reference to another node. */
data class InstanceNodeElement(
    override val objectId: Int,
    override val baseNode: BaseNodeData,
    val version: Int,
    val childNodeObjectId: Int,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.INSTANCE_NODE
}

/** Part Node Element (Figure 28): the root of one part's structure. */
data class PartNodeElement(
    override val objectId: Int,
    val metaData: MetaDataNodeData,
    val version: Int,
    val emptyField: Int,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.PART_NODE
    override val baseNode: BaseNodeData get() = metaData.group.base
}

/** Meta Data Node Element (Figure 29): a late-loaded metadata reference holder. */
data class MetaDataNodeElement(
    override val objectId: Int,
    val metaData: MetaDataNodeData,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.META_DATA_NODE
    override val baseNode: BaseNodeData get() = metaData.group.base
}

/** LOD Node Element (Figure 31): alternate representations as children. */
data class LodNodeElement(
    override val objectId: Int,
    val lod: LodNodeData,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.LOD_NODE
    override val baseNode: BaseNodeData get() = lod.group.base
}

/** Range LOD Node Element (Figure 33): alternate representations with distance ranges. */
data class RangeLodNodeElement(
    override val objectId: Int,
    val lod: LodNodeData,
    val version: Int,
    val rangeLimits: List<Float>,
    val centre: Vec3F32,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.RANGE_LOD_NODE
    override val baseNode: BaseNodeData get() = lod.group.base
}

/** Switch Node Element (Figure 34): group children with a selected index. */
data class SwitchNodeElement(
    override val objectId: Int,
    val group: GroupNodeData,
    val version: Int,
    /** U32 on the wire; -1 selects no child. */
    val selectedChild: Int,
) : NodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.SWITCH_NODE
    override val baseNode: BaseNodeData get() = group.base
}

/** A leaf node carrying or referencing geometric shape data (§6.1.1.10). */
sealed class ShapeNodeElement : NodeElement() {
    abstract val shape: BaseShapeData

    override val baseNode: BaseNodeData get() = shape.base
}

/** Base Shape Node Element (Figure 35). */
data class BaseShapeNodeElement(
    override val objectId: Int,
    override val shape: BaseShapeData,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.BASE_SHAPE_NODE
}

/** Vertex Shape Node Element (Figure 38). */
data class VertexShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.VERTEX_SHAPE_NODE
    override val shape: BaseShapeData get() = vertexShape.shape
}

/** Tri-Strip Set Shape Node Element (§6.1.1.10.3). */
data class TriStripSetShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE
    override val shape: BaseShapeData get() = vertexShape.shape
}

/** Polyline Set Shape Node Element (v10 Figure 40 / 9.5 Figure 33). */
data class PolylineSetShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
    val version: Int,
    val areaFactor: Float,
    /**
     * 9.5 Figure 33's guarded `U64: Vertex Bindings`, which v10 Figure 40 does not have at
     * all. `null` when the element body does not carry it — the writer then emits none.
     */
    val vertexBindings: ULong? = null,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.POLYLINE_SET_SHAPE_NODE
    override val shape: BaseShapeData get() = vertexShape.shape
}

/** Point Set Shape Node Element (v10 Figure 41 / 9.5 Figure 34). */
data class PointSetShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
    val version: Int,
    val areaFactor: Float,
    /**
     * The guarded `U64: Vertex Bindings` both documents draw after the Area Factor. `null`
     * when the element body does not carry it — the writer then emits none.
     */
    val vertexBindings: ULong?,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.POINT_SET_SHAPE_NODE
    override val shape: BaseShapeData get() = vertexShape.shape
}

/** Polygon Set Shape Node Element (Figure 42). */
data class PolygonSetShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.POLYGON_SET_SHAPE_NODE
    override val shape: BaseShapeData get() = vertexShape.shape
}

/** NULL Shape Node Element (Figure 43): a placeholder without geometry. */
data class NullShapeNodeElement(
    override val objectId: Int,
    override val shape: BaseShapeData,
    val version: Int,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.NULL_SHAPE_NODE
}

/** Primitive Set Quantization Parameters (Figure 45). */
data class PrimitiveSetQuantizationParameters(
    val bitsPerVertex: Int,
    val bitsPerColour: Int,
)

/** Primitive Set Shape Node Element (v10 Figure 44 / 9.5 Figure 37). */
data class PrimitiveSetShapeNodeElement(
    override val objectId: Int,
    override val shape: BaseShapeData,
    val version: Int,
    /** v10 Figure 44's fused `U64: Vertex Bindings`; `null` in JT 9, which splits it in two. */
    val vertexBindings: ULong?,
    /** 9.5 Figure 37's `I32 : Texture Coord Binding` (0 = None, 1 = Per Vertex); `null` in JT 10. */
    val textureCoordBinding: Int?,
    /** 9.5 Figure 37's `I32 : Color Binding` (0 = None, 1 = Per Vertex); `null` in JT 10. */
    val colourBinding: Int?,
    val texCoordGenType: Int,
    val version2: Int,
    val quantization: PrimitiveSetQuantizationParameters,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.PRIMITIVE_SET_SHAPE_NODE
}

// ---------------------------------------------------------------------------
// Attribute elements (§6.1.2)
// ---------------------------------------------------------------------------

/** Graphical state attached to nodes and inherited down the LSG (§6.1.2). */
sealed class AttributeElement : TypedLsgElement()

/** Material Attribute Element (Figure 47). JT 9 has no bumpiness; version 1 no reflectivity. */
data class MaterialAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val dataFlags: Int,
    val ambientColour: Rgba,
    val diffuseColourAndAlpha: Rgba,
    val specularColour: Rgba,
    val emissionColour: Rgba,
    val shininess: Float,
    /** On the wire in JT 10 always, in JT 9 when [version] >= 2. */
    val reflectivity: Float?,
    /** On the wire in JT 10 only. */
    val bumpiness: Float?,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.MATERIAL_ATTRIBUTE
}

/** Draw Style Attribute Element (Figure 54). */
data class DrawStyleAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val dataFlags: Int,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.DRAW_STYLE_ATTRIBUTE
}

/** Light Set Attribute Element (Figure 55). */
data class LightSetAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val lightObjectIds: List<Int>,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.LIGHT_SET_ATTRIBUTE
}

/**
 * Shadow Parameters (9.5 Figure 55, p.85): the matched pair of alpha factors that govern how
 * a shadow-casting light tints the areas it does and does not illuminate.
 *
 * The pair exists in both generations but hangs off different collections: v10 Figure 57 puts
 * it *inside* Base Light Data, unconditionally ([BaseLightData.shadowParameters]); 9.5 attaches
 * it to the light **element**, after the element's own payload and only from element version 2
 * ([InfiniteLightAttributeElement.shadowParameters],
 * [PointLightAttributeElement.shadowParameters]).
 */
data class ShadowParameters(
    val nonShadowAlphaFactor: Float,
    val shadowAlphaFactor: Float,
)

/**
 * Base Light Data (v10 Figure 57 / 9.5 Figure 54).
 *
 * **Where this collection starts is `spec unclear` in both generations** and the library rests
 * on the attribute-element convention, not on a figure or a fixture: v10 Figure 57's second box
 * is labelled "Logical Element Header Compressed" where Base Attribute Data belongs (and sits
 * *after* the version number), while 9.5 Figure 54 omits the Base Attribute Data box
 * altogether. Both drawings are corrupt in the same slot, no fixture in the corpus carries a
 * light, and the two candidate placements have the same width — so nothing on the wire can
 * settle it. The library reads Base Attribute Data first, as every other attribute element
 * does; the presence of the collection is not in doubt, only its position.
 */
data class BaseLightData(
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val ambientColour: Rgba,
    val diffuseColour: Rgba,
    val specularColour: Rgba,
    val brightness: Float,
    val coordSystem: Int,
    val shadowCasterFlag: Int,
    val shadowOpacity: Float,
    /**
     * The v10 Figure 57 placement of the alpha-factor pair — unconditional there, and `null`
     * for the JT 9 generation, whose Figure 54 ends at Shadow Opacity and carries the pair on
     * the element instead.
     */
    val shadowParameters: ShadowParameters?,
)

/** Infinite Light Attribute Element (v10 Figure 56 / 9.5 Figure 53). */
data class InfiniteLightAttributeElement(
    override val objectId: Int,
    val baseLight: BaseLightData,
    val version: Int,
    val direction: Vec3F32,
    /**
     * 9.5 Figure 53's guarded tail — the Shadow Parameters collection the figure mislabels
     * "Shadow Opacity" (its own caption points at §7.2.1.1.2.6.2 Shadow Parameters), gated
     * `Version Number == 2` and therefore present from element version 2 upwards. Presence is
     * a model fact resolved from the body's remaining length, never re-derived from the
     * version on write, so a version-1 light cannot round-trip as a version-2 one. Always
     * `null` in the JT 10 generations, where the pair lives in Base Light Data.
     */
    val shadowParameters: ShadowParameters? = null,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE
}

/** Attenuation Coefficients (Figure 60). */
data class AttenuationCoefficients(
    val constant: Float,
    val linear: Float,
    val quadratic: Float,
)

/** Point Light Attribute Element (v10 Figure 58 / 9.5 Figure 56). */
data class PointLightAttributeElement(
    override val objectId: Int,
    val baseLight: BaseLightData,
    val version: Int,
    val position: Vec4F32,
    val attenuation: AttenuationCoefficients,
    val spreadAngle: Float,
    val spotDirection: Vec3F32,
    val spotIntensity: Int,
    /** 9.5 Figure 56's guarded tail — see [InfiniteLightAttributeElement.shadowParameters]. */
    val shadowParameters: ShadowParameters? = null,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.POINT_LIGHT_ATTRIBUTE
}

/** Linestyle Attribute Element (Figure 61). */
data class LinestyleAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val dataFlags: Int,
    val lineWidth: Float,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.LINESTYLE_ATTRIBUTE
}

/** Pointstyle Attribute Element (Figure 62). */
data class PointstyleAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val dataFlags: Int,
    val pointSize: Float,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.POINTSTYLE_ATTRIBUTE
}

/**
 * The wire width of one stored Geometric Transform matrix element: `F32` in 9.5 Figure 61
 * (p.91 — figure box and prose heading alike), `F64` in v10 Figure 63. Widening an `F32` into
 * the model's `Double` is exact but not reversible without knowing which width was read, so the
 * element records it.
 */
enum class TransformValueWidth(val bytes: Int) {
    F32(4),
    F64(8),
}

/**
 * Geometric Transform Attribute Element (v10 Figure 63 / 9.5 Figure 61): a 4×4 homogeneous
 * transform, stored sparsely. [matrix] is the full row-major matrix; [storedValuesMask] records
 * which of its elements are on the wire (bit 15 = first element), so re-encoding is
 * byte-identical.
 */
data class GeometricTransformAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val storedValuesMask: Int,
    val matrix: Mx4F64,
    /**
     * Which width the stored values were read at — resolved from the body's remaining length
     * (`popcount(mask) × 4` vs `× 8`), not from the generation, and emitted back unchanged.
     * With `storedValuesMask == 0` the two readings coincide and this is the generation's
     * documented width.
     */
    val valueWidth: TransformValueWidth = TransformValueWidth.F64,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.GEOMETRIC_TRANSFORM_ATTRIBUTE
}

/** Texture Coordinate Generator Attribute Element (Figure 64). */
data class TextureCoordinateGeneratorAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val texCoordChannel: Int,
    /** The nested Mapping Surface element — one of the Mapping elements, or opaque. */
    val mappingSurface: LsgElement,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.TEXTURE_COORDINATE_GENERATOR_ATTRIBUTE
}

/** The shared body of the four Mapping elements (Figures 65–68). */
data class MappingSurfaceData(
    val version: Int,
    val matrix: Mx4F64,
    val coordSystem: Int,
)

/** Mapping Plane Element (Figure 65). */
data class MappingPlaneElement(
    override val objectId: Int,
    val data: MappingSurfaceData,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.MAPPING_PLANE
}

/** Mapping Cylinder Element (Figure 66). */
data class MappingCylinderElement(
    override val objectId: Int,
    val data: MappingSurfaceData,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.MAPPING_CYLINDER
}

/** Mapping Sphere Element (Figure 67). */
data class MappingSphereElement(
    override val objectId: Int,
    val data: MappingSurfaceData,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.MAPPING_SPHERE
}

/** Mapping TriPlanar Element (Figure 68). */
data class MappingTriPlanarElement(
    override val objectId: Int,
    val data: MappingSurfaceData,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.MAPPING_TRIPLANAR
}

// --- Texture Image Attribute Element (§6.1.2.3) ---

/** Texture Environment (Figure 50). */
data class TextureEnvironment(
    val borderMode: Int,
    val mipmapMagnificationFilter: Int,
    val mipmapMinificationFilter: Int,
    val sDimenWrapMode: Int,
    val tDimenWrapMode: Int,
    val rDimenWrapMode: Int,
    val blendType: Int,
    val internalCompressionLevel: Int,
    val blendColour: Rgba,
    val borderColour: Rgba,
    val textureTransform: Mx4F32,
)

/** Texture Coord Generation Parameters (Figure 51): four modes and four reference planes. */
data class TextureCoordGenerationParameters(
    val texCoordGenModes: List<Int>,
    val texCoordReferencePlanes: List<PlaneF32>,
) {
    init {
        require(texCoordGenModes.size == 4) { "four generation modes required" }
        require(texCoordReferencePlanes.size == 4) { "four reference planes required" }
    }
}

/**
 * The wire width of Image Format Description's Shared Image Flag: `U8` in 9.5 Figure 48 (p.72,
 * figure box and p.73 prose alike), `U32` in v10 Figure 53. Three bytes, immediately before
 * `I16 : Mipmaps Count` — a misread here does not shorten the block, it corrupts the mipmap
 * loop that follows.
 */
enum class SharedImageFlagWidth(val bytes: Int) {
    U8(1),
    U32(4),
}

/** Image Format Description (v10 Figure 53 / 9.5 Figure 48). */
data class ImageFormatDescription(
    val pixelFormat: UInt,
    val pixelDataType: UInt,
    val dimensionality: Int,
    val rowAlignment: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val numberBorderTexels: Int,
    val sharedImageFlag: UInt,
    val mipmapsCount: Int,
    /**
     * Which width [sharedImageFlag] was read at — resolved by parsing the element's image list
     * under each candidate and keeping the one that consumes the body exactly, and emitted back
     * unchanged.
     */
    val sharedImageFlagWidth: SharedImageFlagWidth = SharedImageFlagWidth.U32,
)

/** Inline Texture Image Data (Figure 52): format plus the per-mipmap texel byte blocks. */
data class InlineTextureImage(
    val format: ImageFormatDescription,
    val totalImageDataSize: Int,
    val mipmapImages: List<Bytes>,
)

/** Texture Vers-1 Data (Figure 49). */
data class TextureVers1Data(
    val textureType: Int,
    val environment: TextureEnvironment,
    val coordGenerationParameters: TextureCoordGenerationParameters,
    val textureChannel: Int,
    val texCoordChannel: Int,
    val emptyField: UInt,
    val inlineImageStorageFlag: Int,
    /** Inline images when the storage flag is 1. */
    val inlineImages: List<InlineTextureImage>,
    /** External storage names when the storage flag is 0. */
    val externalStorageNames: List<String>,
)

/** Texture Image Attribute Element (Figure 48). */
data class TextureImageAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val textureData: TextureVers1Data,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.TEXTURE_IMAGE_ATTRIBUTE
}

// ---------------------------------------------------------------------------
// Property atom elements (§6.2)
// ---------------------------------------------------------------------------

/** A key or value object of the Property Table (§6.2). */
sealed class PropertyAtomElement : TypedLsgElement() {
    abstract val baseAtom: BasePropertyAtomData
}

/** Base Property Atom Element (Figure 69). */
data class BasePropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.BASE_PROPERTY_ATOM
}

/** String Property Atom Element (Figure 71). */
data class StringPropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val value: String,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.STRING_PROPERTY_ATOM
}

/** Integer Property Atom Element (Figure 72). */
data class IntegerPropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val value: Int,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.INTEGER_PROPERTY_ATOM
}

/** Floating Point Property Atom Element (Figure 73). */
data class FloatingPointPropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val value: Float,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.FLOATING_POINT_PROPERTY_ATOM
}

/** JT Object Reference Property Atom Element (Figure 74). */
data class JtObjectReferencePropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val referencedObjectId: Int,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.JT_OBJECT_REFERENCE_PROPERTY_ATOM
}

/** Date Property Atom Element (Figure 75). Month is 0-based per the spec. */
data class DatePropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    /**
     * The JT 10.5 generation appends an F32 the v10.0 reference does not document. The
     * observed value (−4.0 on every atom of the 10.5 fixture) is consistent with a UTC
     * offset in hours for the stored timestamps (NIST, US EDT) — semantics unconfirmed,
     * carried verbatim (DESIGN.md delta 26). `null` in the V9/V10 generations.
     */
    val trailingField: Float? = null,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.DATE_PROPERTY_ATOM
}

/** Late Loaded Property Atom Element (Figure 76): a reference into another data segment. */
data class LateLoadedPropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val segmentId: Guid,
    val segmentType: Int,
    val payloadObjectId: Int,
    /**
     * The v10.0 reference documents this I32 as "always ≥ 1"; the JT 10.5 generation drops
     * the field entirely (DESIGN.md delta 25). `null` exactly in V10_5.
     */
    val reserved: Int?,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.LATE_LOADED_PROPERTY_ATOM
}

/** Vector4f Property Atom Element (Figure 77). */
data class Vector4fPropertyAtomElement(
    override val objectId: Int,
    override val baseAtom: BasePropertyAtomData,
    val version: Int,
    val value: Vec4F32,
) : PropertyAtomElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.VECTOR4F_PROPERTY_ATOM
}

// ---------------------------------------------------------------------------
// Property table (§6.3)
// ---------------------------------------------------------------------------

/** One key/value pair of an Element Property Table (Figure 79). */
data class PropertyEntry(
    val keyPropertyAtomObjectId: Int,
    val valuePropertyAtomObjectId: Int,
) {
    init {
        require(keyPropertyAtomObjectId != 0) { "key object id 0 is the table terminator" }
    }
}

/** The Element Property Table of one element (Figure 79). */
data class ElementPropertyTable(
    val elementObjectId: Int,
    val entries: List<PropertyEntry>,
)

/** The Property Table connecting elements with their property atoms (Figure 78). */
data class PropertyTable(
    val version: Int,
    val tables: List<ElementPropertyTable>,
)
