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
    /** On the wire in JT 9 only, when [version] >= 2. */
    val vertexBindings2: ULong?,
)

/**
 * Base Attribute Data (Figure 46). JT 10 added the Field Final Flags word; JT 9 attributes
 * carry `null` there (fixture-verified).
 */
data class BaseAttributeData(
    val version: Int,
    val stateFlags: Int,
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
)

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

/** Partition Node Element (Figure 23): an external-file reference or the LSG root. */
data class PartitionNodeElement(
    override val objectId: Int,
    val group: GroupNodeData,
    /** JT 10.5 inserts a version number before the flags (DESIGN.md delta 23); else `null`. */
    val version: Int?,
    val partitionFlags: Int,
    val fileName: String,
    val transformedBBox: BBoxF32,
    val area: Float,
    val vertexCountRange: CountRange,
    val nodeCountRange: CountRange,
    val polygonCountRange: CountRange,
    /**
     * Present when bit 0 of [partitionFlags] is set — except that the JT 10.5 producer
     * observed (Siemens DM 9.8) sets the bit without storing the box (DESIGN.md delta 23).
     */
    val untransformedBBox: BBoxF32?,
) : NodeElement() {
    init {
        // Bit 0 announces the box (Figure 23/Table 11) — but the 10.5 producer observed
        // sets the bit without storing it (DESIGN.md delta 23), so only the reverse
        // direction is an invariant: a stored box requires the announcing bit.
        require(untransformedBBox == null || partitionFlags and 1 != 0) {
            "an untransformed bounding box requires partition flag bit 0"
        }
    }

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

/** Polyline Set Shape Node Element (Figure 40). */
data class PolylineSetShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
    val version: Int,
    val areaFactor: Float,
) : ShapeNodeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.POLYLINE_SET_SHAPE_NODE
    override val shape: BaseShapeData get() = vertexShape.shape
}

/** Point Set Shape Node Element (Figure 41). */
data class PointSetShapeNodeElement(
    override val objectId: Int,
    val vertexShape: VertexShapeData,
    val version: Int,
    val areaFactor: Float,
    /** JT 10 stores an extra U64 binding field when [version] == 1; otherwise `null`. */
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

/** Primitive Set Shape Node Element (Figure 44). */
data class PrimitiveSetShapeNodeElement(
    override val objectId: Int,
    override val shape: BaseShapeData,
    val version: Int,
    val vertexBindings: ULong,
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
 * Base Light Data (Figure 57). The figure's box drawing is garbled in the reference (it shows
 * a stray element-header box); read here as base attribute data followed by the documented
 * fields, per the attribute-element convention — spec-derived, not yet fixture-verified.
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
    val nonShadowAlphaFactor: Float,
    val shadowAlphaFactor: Float,
)

/** Infinite Light Attribute Element (Figure 56). */
data class InfiniteLightAttributeElement(
    override val objectId: Int,
    val baseLight: BaseLightData,
    val version: Int,
    val direction: Vec3F32,
) : AttributeElement() {
    override val objectTypeId: Guid get() = ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE
}

/** Attenuation Coefficients (Figure 60). */
data class AttenuationCoefficients(
    val constant: Float,
    val linear: Float,
    val quadratic: Float,
)

/** Point Light Attribute Element (Figure 58). */
data class PointLightAttributeElement(
    override val objectId: Int,
    val baseLight: BaseLightData,
    val version: Int,
    val position: Vec4F32,
    val attenuation: AttenuationCoefficients,
    val spreadAngle: Float,
    val spotDirection: Vec3F32,
    val spotIntensity: Int,
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
 * Geometric Transform Attribute Element (Figure 63): a 4×4 homogeneous transform, stored
 * sparsely. [matrix] is the full row-major matrix; [storedValuesMask] records which of its
 * elements are on the wire (bit 15 = first element), so re-encoding is byte-identical.
 */
data class GeometricTransformAttributeElement(
    override val objectId: Int,
    val baseAttribute: BaseAttributeData,
    val version: Int,
    val storedValuesMask: Int,
    val matrix: Mx4F64,
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

/** Image Format Description (Figure 53). */
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
