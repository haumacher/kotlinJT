package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.Guid

/**
 * A named refusal. Whenever the parser meets content it cannot fully decode, it keeps the raw
 * bytes and records exactly one of these — never an exception through the public API, never
 * silence. Silence always means success.
 *
 * Every note has a stable [name] (the identifier bug reports and fixture expectation files
 * key on) and a human-readable [message] with the location context.
 */
sealed class LoadNote {
    /** Stable machine-readable identifier of the note kind. */
    abstract val name: String

    /** Human-readable description including location context. */
    abstract val message: String

    // Final: the note subtypes are data classes, and this rendering — not the generated
    // field dump — is the diagnostic contract.
    final override fun toString(): String = "$name: $message"

    /** A segment uses a compression algorithm the library knows of but does not decode yet. */
    data class UnsupportedCompression(
        val segmentId: Guid,
        val algorithmCode: Int,
        val algorithm: String,
    ) : LoadNote() {
        override val name: String get() = "UNSUPPORTED_COMPRESSION"
        override val message: String
            get() = "segment $segmentId is compressed with $algorithm (algorithm code $algorithmCode); payload kept raw"
    }

    /** A segment declares a compression algorithm code outside the specified value set. */
    data class UnknownCompressionAlgorithm(
        val segmentId: Guid,
        val algorithmCode: Int,
    ) : LoadNote() {
        override val name: String get() = "UNKNOWN_COMPRESSION_ALGORITHM"
        override val message: String
            get() = "segment $segmentId declares unknown compression algorithm code $algorithmCode; payload kept raw"
    }

    /** The declared compressed data does not decode (e.g. a corrupt ZLIB stream). */
    data class CompressedDataCorrupt(
        val segmentId: Guid,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "COMPRESSED_DATA_CORRUPT"
        override val message: String get() = "segment $segmentId compressed data does not decode: $detail; payload kept raw"
    }

    /** The segment-wide compression header contradicts the segment size. */
    data class CompressionHeaderInconsistent(
        val segmentId: Guid,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "COMPRESSION_HEADER_INCONSISTENT"
        override val message: String get() = "segment $segmentId compression header is inconsistent: $detail; payload kept raw"
    }

    /** A segment type code outside the specified table; the payload is carried opaquely. */
    data class UnknownSegmentType(
        val segmentId: Guid,
        val typeCode: Int,
    ) : LoadNote() {
        override val name: String get() = "UNKNOWN_SEGMENT_TYPE"
        override val message: String get() = "segment $segmentId has unknown segment type $typeCode; payload kept raw"
    }

    /** A TOC entry that points outside the file; no segment could be read for it. */
    data class SegmentOutOfBounds(
        val segmentId: Guid,
        val offset: Long,
        val length: Long,
        val fileSize: Int,
    ) : LoadNote() {
        override val name: String get() = "SEGMENT_OUT_OF_BOUNDS"
        override val message: String
            get() = "TOC entry $segmentId points at [$offset, ${offset + length}) outside the $fileSize-byte file"
    }

    /** A TOC entry too short to hold even a segment header. */
    data class SegmentTooShort(
        val segmentId: Guid,
        val offset: Long,
        val length: Long,
    ) : LoadNote() {
        override val name: String get() = "SEGMENT_TOO_SHORT"
        override val message: String
            get() = "TOC entry $segmentId at offset $offset spans only $length bytes, less than a segment header"
    }

    /** The GUID inside the segment header differs from the TOC entry's GUID. */
    data class SegmentIdMismatch(
        val tocId: Guid,
        val headerId: Guid,
        val offset: Long,
    ) : LoadNote() {
        override val name: String get() = "SEGMENT_ID_MISMATCH"
        override val message: String get() = "segment at offset $offset: TOC names $tocId but the segment header names $headerId"
    }

    /** The length inside the segment header differs from the TOC entry's length. */
    data class SegmentLengthMismatch(
        val segmentId: Guid,
        val tocLength: Long,
        val headerLength: Long,
    ) : LoadNote() {
        override val name: String get() = "SEGMENT_LENGTH_MISMATCH"
        override val message: String
            get() = "segment $segmentId: TOC length $tocLength but segment header declares $headerLength"
    }

    /** The type code inside the segment header differs from the one in the TOC attributes. */
    data class SegmentTypeMismatch(
        val segmentId: Guid,
        val tocTypeCode: Int,
        val headerTypeCode: Int,
    ) : LoadNote() {
        override val name: String get() = "SEGMENT_TYPE_MISMATCH"
        override val message: String
            get() = "segment $segmentId: TOC attributes say type $tocTypeCode but the segment header says $headerTypeCode"
    }

    /** Two file regions overlap; the shadowed one is not part of the serialized layout. */
    data class SegmentRegionOverlap(
        val segmentId: Guid,
        val offset: Long,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SEGMENT_REGION_OVERLAP"
        override val message: String get() = "segment $segmentId at offset $offset overlaps another file region: $detail"
    }

    /** Bytes between mapped regions that no TOC entry explains; preserved verbatim. */
    data class UnmappedRegion(
        val offset: Long,
        val length: Long,
    ) : LoadNote() {
        override val name: String get() = "UNMAPPED_REGION"
        override val message: String
            get() = "$length bytes at offset $offset are not reachable from the TOC; preserved verbatim"
    }

    /** A decoded segment payload that should contain elements but yields no element frame. */
    data class ElementStreamUnrecognized(
        val segmentId: Guid,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "ELEMENT_STREAM_UNRECOGNIZED"
        override val message: String get() = "segment $segmentId payload does not start with a valid element frame: $detail"
    }

    /** An element with an Object Type ID outside Annex A; carried opaquely, byte-faithful. */
    data class UnknownElementType(
        val objectTypeId: Guid,
        val location: String,
    ) : LoadNote() {
        override val name: String get() = "UNKNOWN_ELEMENT_TYPE"
        override val message: String get() = "$location: element type $objectTypeId is not in the Annex A table; carried opaquely"
    }

    /**
     * A known element type whose wire layout for the file's format generation is not
     * established (the v10 reference documents only v10; v9 layouts are used only where
     * verified against real files — DESIGN.md). Carried opaquely rather than guessed.
     */
    data class ElementLayoutUnverified(
        val objectTypeId: Guid,
        val typeName: String,
        val generation: String,
        val location: String,
    ) : LoadNote() {
        override val name: String get() = "ELEMENT_LAYOUT_UNVERIFIED"
        override val message: String
            get() = "$location: $typeName has no established $generation wire layout; carried opaquely"
    }

    /** A known element type whose body did not decode; carried opaquely, byte-faithful. */
    data class ElementDecodeFailed(
        val objectTypeId: Guid,
        val typeName: String,
        val location: String,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "ELEMENT_DECODE_FAILED"
        override val message: String get() = "$location: $typeName did not decode ($detail); carried opaquely"
    }

    /** An LSG element stream that does not have the Figure 20 structure. */
    data class LsgStructureUnrecognized(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "LSG_STRUCTURE_UNRECOGNIZED"
        override val message: String get() = "LSG element stream deviates from the Figure 20 structure: $detail"
    }

    /** A Shape LOD element stream that does not have the Figure 80 structure. */
    data class ShapeLodStructureUnrecognized(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SHAPE_LOD_STRUCTURE_UNRECOGNIZED"
        override val message: String get() = "shape LOD element stream deviates from the Figure 80 structure: $detail"
    }

    /** A Meta Data / PMI Data element stream that does not have the Figure 107 structure. */
    data class MetaDataStructureUnrecognized(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "META_DATA_STRUCTURE_UNRECOGNIZED"
        override val message: String get() = "meta data element stream deviates from the Figure 107 structure: $detail"
    }

    /** A Wireframe element stream that does not have the Figure 103 structure. */
    data class WireframeStructureUnrecognized(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "WIREFRAME_STRUCTURE_UNRECOGNIZED"
        override val message: String get() = "wireframe element stream deviates from the Figure 103 structure: $detail"
    }

    /** A JT LWPA element stream that does not have the Figure 99 structure. */
    data class LwpaStructureUnrecognized(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "LWPA_STRUCTURE_UNRECOGNIZED"
        override val message: String get() = "LWPA element stream deviates from the Figure 99 structure: $detail"
    }

    /**
     * The entropy-coded vectors inside a Compressed CAD Tag Data collection (Figure 154) did not
     * decode. The collection's own Data Length pins its extent, so the coded bytes are kept
     * verbatim next to the refusal and re-serialization stays byte-identical.
     */
    data class CadTagVectorsUnrecognized(
        val location: String,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "CAD_TAG_VECTORS_UNRECOGNIZED"
        override val message: String
            get() = "$location: Compressed CAD Tag Data vectors did not decode ($detail); coded bytes preserved verbatim"
    }

    /**
     * A Property Proxy property carries a Property Value Type outside Table 53. Its length is
     * unknown, so the property bag cannot continue past it: the keys decoded so far stay in
     * the model and every remaining byte is preserved verbatim next to the refusal.
     */
    data class MetaPropertyValueTypeUnknown(
        val location: String,
        val key: String,
        val typeCode: Int,
    ) : LoadNote() {
        override val name: String get() = "META_PROPERTY_VALUE_TYPE_UNKNOWN"
        override val message: String
            get() =
                "$location: property \"$key\" declares Property Value Type $typeCode, which Table 53 does not define; " +
                    "the rest of the bag is preserved verbatim"
    }

    /**
     * A PMI Manager Meta Data Element carries bytes after its font block that Figure 110 does
     * not account for (NX 10.5 writes such a block — DESIGN.md delta 32). They are preserved
     * verbatim rather than read as the Property Count / PMI Properties / Model View Sort
     * Orders the figure places there, which they demonstrably are not.
     */
    data class PmiManagerTailUndocumented(
        val location: String,
        val byteCount: Int,
    ) : LoadNote() {
        override val name: String get() = "PMI_MANAGER_TAIL_UNDOCUMENTED"
        override val message: String
            get() = "$location: $byteCount bytes after the PMI font block are not documented by Figure 110; preserved verbatim"
    }

    /**
     * A JT 9.5 PMI Manager writes Figure 145's `VecF32 Polyline Vertex Coords` where the
     * `Polyline Segment Index Count` is zero and the figure's guard excludes it — the form NX
     * writes in v10 (DESIGN.md delta 36). Accepted, resolved from the body's exact extent rather
     * than assumed, and recorded on the element so re-serialization stays byte-identical.
     */
    data class PmiTextPolylineVectorOffDocument(
        val location: String,
    ) : LoadNote() {
        override val name: String get() = "PMI_TEXT_POLYLINE_VECTOR_OFF_DOCUMENT"
        override val message: String
            get() =
                "$location: text polylines with a zero segment index count still carry an empty " +
                    "VecF32 that 9.5 Figure 145's guard excludes; accepted and recorded"
    }

    /**
     * A JT 9.5 PMI Polygon Data element sets `TextureBinding`, where Figure 170 and its own
     * prose disagree about what follows: the figure labels the box `I16 : Reserved Field`, p.201
     * says `VecF32: Texture Coords`. The prose is followed (it is internally consistent and
     * matches v10's Figure 130) and the deviation is named, so the first fixture to exercise the
     * branch settles it instead of passing silently.
     */
    data class PmiPolygonTextureBindingUnsettled(
        val location: String,
        val elementCount: Int,
    ) : LoadNote() {
        override val name: String get() = "PMI_POLYGON_TEXTURE_BINDING_UNSETTLED"
        override val message: String
            get() =
                "$location: $elementCount JT 9.5 PolygonData element(s) set TextureBinding, where Figure 170 " +
                    "(I16 Reserved Field) and §7.2.6.2.8's prose (VecF32 Texture Coords) disagree; the prose is followed"
    }

    /** An LSG stream that ends after its element lists without a Property Table. */
    data class PropertyTableMissing(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "PROPERTY_TABLE_MISSING"
        override val message: String get() = "LSG stream carries no Property Table: $detail"
    }

    /** LSG trailing bytes that do not parse as a Property Table; preserved verbatim. */
    data class PropertyTableUnrecognized(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "PROPERTY_TABLE_UNRECOGNIZED"
        override val message: String
            get() = "LSG bytes after the element lists do not parse as a Property Table: $detail; preserved verbatim"
    }

    // --- Layer 2 scene extraction notes (readScene) ---

    /** No scene can be built at all: the file has no decodable LSG, or the LSG has no root. */
    data class SceneStructureUnavailable(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SCENE_STRUCTURE_UNAVAILABLE"
        override val message: String get() = "no scene structure could be extracted: $detail"
    }

    /**
     * The scene tree was built, but parts of the LSG did not contribute: opaque elements,
     * unresolvable references, cycles. The scene may be missing structure those held.
     */
    data class SceneStructureIncomplete(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SCENE_STRUCTURE_INCOMPLETE"
        override val message: String get() = "the scene may be missing structure: $detail"
    }

    /** A shape node whose geometry could not be resolved; its scene node stays, empty. */
    data class SceneGeometryUnavailable(
        val nodeName: String,
        val nodeObjectId: Int,
        val segmentId: Guid?,
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SCENE_GEOMETRY_UNAVAILABLE"
        override val message: String
            get() =
                "geometry of shape node #$nodeObjectId" +
                    (if (nodeName.isNotEmpty()) " (\"$nodeName\")" else "") +
                    (segmentId?.let { " in segment $it" } ?: "") +
                    " is not in the scene: $detail"
    }

    /** A JT_PROP_MEASUREMENT_UNITS value outside the Table 77 value set. */
    data class SceneUnitsUnrecognized(
        val value: String,
    ) : LoadNote() {
        override val name: String get() = "SCENE_UNITS_UNRECOGNIZED"
        override val message: String
            get() = "JT_PROP_MEASUREMENT_UNITS value \"$value\" is not a Table 77 unit; ignored for the scene's units"
    }

    /** Different nodes declare different units; a single scene units field cannot hold both. */
    data class SceneUnitsMixed(
        val values: List<String>,
    ) : LoadNote() {
        override val name: String get() = "SCENE_UNITS_MIXED"
        override val message: String
            get() = "the file declares conflicting measurement units $values; the scene's units are UNSPECIFIED"
    }

    /**
     * Attributes use accumulation semantics the scene extraction does not model (force /
     * final / field-inhibit flags); the scene's transforms or materials may differ from a
     * fully conforming traversal.
     */
    data class SceneAttributeSemanticsUnsupported(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED"
        override val message: String
            get() = "attribute accumulation flags beyond the modelled semantics: $detail; the scene applies plain accumulation"
    }

    /** One scene node drew from shapes with differing materials; one of them was chosen. */
    data class SceneMaterialAmbiguous(
        val detail: String,
    ) : LoadNote() {
        override val name: String get() = "SCENE_MATERIAL_AMBIGUOUS"
        override val message: String get() = "conflicting materials merged into one scene node: $detail"
    }

    /**
     * A LOD node whose alternative representations hold different numbers of shapes. The scene
     * pairs the shapes of the tiers by position (one scene node per shape, its meshes being
     * that shape across the tiers), and nothing states which shape of a coarse tier stands for
     * which shape of the fine one — so where the counts differ, a node's mesh list can skip a
     * tier, and picking "LOD *i*" uniformly across the part may mix tiers.
     */
    data class SceneLodTiersUnaligned(
        val nodeObjectId: Int,
        val shapesPerTier: List<Int>,
    ) : LoadNote() {
        override val name: String get() = "SCENE_LOD_TIERS_UNALIGNED"
        override val message: String
            get() =
                "the LOD tiers of node #$nodeObjectId hold different numbers of shapes " +
                    "($shapesPerTier); the scene pairs them by position, so one node's mesh list " +
                    "may skip a tier"
    }
}
