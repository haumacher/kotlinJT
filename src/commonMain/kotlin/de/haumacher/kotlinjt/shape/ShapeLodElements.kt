package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.lsg.BBoxF32
import de.haumacher.kotlinjt.lsg.QuantizationParameters
import de.haumacher.kotlinjt.lsg.Vec3F32

/**
 * One element of a Shape LOD segment (§7): a typed, immutable mirror of the spec data
 * collections, or an [OpaqueShapeLodElement] carrying raw bytes with a named note. As in the
 * LSG model there is deliberately no half-decoded element — a body either parses completely
 * (byte-exact re-serialization guaranteed) or is carried opaquely.
 */
sealed class ShapeLodElement {
    /** The Object Type ID (Annex A) this element was framed with. */
    abstract val objectTypeId: Guid
}

/**
 * A Shape LOD element carried opaquely: unknown type, a type without an established wire
 * layout for its generation (point/polygon/primitive sets; polyline in JT 9), or a failed
 * decode. [body] preserves everything after the Object Type ID byte-faithfully.
 */
data class OpaqueShapeLodElement(
    override val objectTypeId: Guid,
    /** The Object Base Type byte as scanned, `null` when the body is empty. */
    val objectBaseType: Int?,
    /** The element body after the 16 Object Type ID bytes, preserved verbatim. */
    val body: Bytes,
) : ShapeLodElement()

/** A fully decoded Shape LOD element; [objectId] is what late-loaded property atoms reference. */
sealed class TypedShapeLodElement : ShapeLodElement() {
    abstract val objectId: Int
}

// ---------------------------------------------------------------------------
// Shared data collections
// ---------------------------------------------------------------------------

/**
 * TopoMesh LOD Data (Figure 88): the version and the object id under which other elements
 * reference this element's vertex records.
 */
data class TopoMeshLodData(
    val version: Int,
    val vertexRecordsObjectId: Int,
)

/**
 * Topologically Compressed Vertex Records (Figure 93): the unique vertex coordinates plus the
 * per-corner attribute records (normals, and in richer files colours/texture coordinates),
 * written in topology-encoder visit order. Shared by both generations — the arrays inside
 * differ per generation, the record structure does not.
 */
data class TopologicallyCompressedVertexRecords(
    val vertexBindings: ULong,
    val quantizationParameters: QuantizationParameters,
    val numberOfTopologicalVertices: Int,
    /** Only on the wire when there are topological vertices. */
    val numberOfVertexAttributes: Int?,
    /** Present when the bindings declare vertex coordinates. */
    val coordinates: CompressedVertexCoordinateArray?,
    /** Present when the bindings declare normals. */
    val normals: CompressedVertexNormalArray?,
    /** Present when the bindings declare per-vertex flags (Table 48 bit 7; v10 fixture only). */
    val vertexFlags: CompressedVertexFlagArray? = null,
)

/**
 * Topologically Compressed Rep Data (Figure 92; JT 9 wire deltas in DESIGN.md): the topology
 * coder's symbol streams plus the vertex records. The composite hash is verified at decode
 * time — a corrupt stream refuses the typed decode instead of producing a broken mesh.
 */
data class TopologicallyCompressedRepData(
    /** Face degree symbols, one packet per compression context (dual faces = primal vertices). */
    val faceDegrees: List<Int32Cdp>,
    /** Vertex valence symbols (dual vertices = primal faces; 3 for triangles). */
    val vertexValences: Int32Cdp,
    /** Face group number per dual vertex, parallel to the valences. */
    val vertexGroups: Int32Cdp,
    /** Cover-face flags per dual vertex (Lag1-predicted on the wire). */
    val vertexFlags: Int32Cdp,
    /** Face attribute masks, one packet per context; JT 9 stores 30-bit chunks. */
    val faceAttributeMasks: List<Int32Cdp>,
    /** JT 9 only: the next 30 bits of the 8th context's masks. */
    val faceAttributeMask8Mid: Int32Cdp,
    /** JT 9 only: the top 4 bits of the 8th context's masks. */
    val faceAttributeMask8Top: Int32Cdp,
    /** High-degree (> 64) attribute masks, adjoined end-to-end (VecU32). */
    val highDegreeFaceAttributeMasks: List<Int>,
    /** Split face offsets (Lag1-predicted on the wire). */
    val splitFaceSymbols: Int32Cdp,
    /** Split face queue positions. */
    val splitFacePositions: Int32Cdp,
    /** The stored composite hash over all symbol streams (verified at decode). */
    val compositeHash: Int,
    val vertexRecords: TopologicallyCompressedVertexRecords,
) {
    init {
        require(faceDegrees.size == 8) { "8 face degree contexts expected" }
        require(faceAttributeMasks.size == 8) { "8 face attribute mask contexts expected" }
    }
}

/**
 * Topologically Compressed Rep Data in the v10 wire format (Figure 92, NIST-10.5-verified —
 * DESIGN.md): against the JT 9 layout, the 8th attribute-mask context stores 32 + 32 bits
 * (one LSB packet in the list plus [faceAttributeMask8Msb]) instead of 30 + 30 + 4. The
 * composite hash is verified at decode time — a corrupt stream refuses the typed decode
 * instead of producing a broken mesh.
 */
data class TopologicallyCompressedRepDataV10(
    /** Face degree symbols, one packet per compression context (dual faces = primal vertices). */
    val faceDegrees: List<Int32Cdp>,
    /** Vertex valence symbols (dual vertices = primal faces; 3 for triangles). */
    val vertexValences: Int32Cdp,
    /** Face group number per dual vertex, parallel to the valences. */
    val vertexGroups: Int32Cdp,
    /** Cover-face flags per dual vertex (Lag1-predicted on the wire). */
    val vertexFlags: Int32Cdp,
    /** Face attribute masks (32 LSBs), one packet per context. */
    val faceAttributeMasks: List<Int32Cdp>,
    /** The 32 MSBs of the 8th context's masks. */
    val faceAttributeMask8Msb: Int32Cdp,
    /** High-degree (> 64) attribute masks, adjoined end-to-end (VecU32). */
    val highDegreeFaceAttributeMasks: List<Int>,
    /** Split face offsets (Lag1-predicted on the wire). */
    val splitFaceSymbols: Int32Cdp,
    /** Split face queue positions. */
    val splitFacePositions: Int32Cdp,
    /** The stored composite hash over all symbol streams (verified at decode). */
    val compositeHash: Int,
    val vertexRecords: TopologicallyCompressedVertexRecords,
) {
    init {
        require(faceDegrees.size == 8) { "8 face degree contexts expected" }
        require(faceAttributeMasks.size == 8) { "8 face attribute mask contexts expected" }
    }
}

/**
 * TopoMesh Compressed Rep Data (Figure 89, v10; NIST-10.5-verified): the non-topological
 * shape path used by polyline (and point) sets — face-group/primitive/vertex index lists
 * plus the unique vertex records. Both stored hashes (FGPV, unique-vertex-length list) are
 * verified at decode.
 */
data class TopoMeshCompressedRepData(
    /** The declared index counts; the index CDPs carry count + 1 values (trailing terminator). */
    val numberOfFaceGroupListIndices: Int,
    val numberOfPrimitiveListIndices: Int,
    val numberOfVertexListIndices: Int,
    /** Primitive-list positions where each face group starts (Lag1 on the wire). */
    val faceGroupListIndices: Int32Cdp,
    /** Vertex-list positions where each primitive starts (Lag1 on the wire). */
    val primitiveListIndices: Int32Cdp,
    /** Per-corner indices into the vertex records (Lag1 on the wire). */
    val vertexListIndices: Int32Cdp,
    /** Stored hash over the three unpacked index lists (verified at decode). */
    val fgpvListIndicesHash: Int,
    val vertexBindings: ULong,
    val quantizationParameters: QuantizationParameters,
    val numberOfVertexRecords: Int,
    /** Per unique coordinate: how many vertex records share it; on the wire when records > 0. */
    val uniqueVertexLengths: Int32Cdp?,
    /** Stored hash over the unpacked length list (verified at decode). */
    val uniqueVertexListMapHash: Int?,
    /** Present when the bindings declare vertex coordinates (one entry per unique coordinate). */
    val coordinates: CompressedVertexCoordinateArray?,
    /** Present when the bindings declare normals (one entry per vertex record). */
    val normals: CompressedVertexNormalArray?,
    /** Present when the bindings declare per-vertex flags (one entry per vertex record). */
    val vertexFlags: CompressedVertexFlagArray?,
)

// ---------------------------------------------------------------------------
// The decoded geometry surface
// ---------------------------------------------------------------------------

/** A typed shape LOD element that decodes to a triangle mesh (both generations). */
interface TriStripGeometryCarrier {
    val geometry: TriStripGeometry
}

/**
 * The honest geometry surface of a tri-strip set LOD — what Layer 2 stands on. [vertices]
 * are the unique coordinates exactly as decoded (bit-exact floats on the lossless path;
 * dequantized values whose precision is visible through the element's quantizer parameters
 * otherwise). Triangles reference vertices and normals by index; cover faces the coder added
 * to close open meshes are already removed.
 */
data class TriStripGeometry(
    val vertices: List<Vec3F32>,
    /** Unique normal records; empty when the shape binds no normals. */
    val normals: List<Vec3F32>,
    val triangles: List<Triangle>,
) {
    /** One triangle: vertex indices, per-corner normal indices (-1 without normals), face group. */
    data class Triangle(
        val v0: Int,
        val v1: Int,
        val v2: Int,
        val n0: Int,
        val n1: Int,
        val n2: Int,
        val faceGroup: Int,
    )
}

/**
 * The decoded geometry of a polyline set LOD: unique vertex coordinates (already smeared to
 * vertex-record space via the unique-length list) and polylines as index runs with their
 * face group.
 */
data class PolylineGeometry(
    /** One coordinate per vertex record. */
    val vertices: List<Vec3F32>,
    val polylines: List<Polyline>,
) {
    /** One polyline: ordered vertex-record indices plus the owning face group. */
    data class Polyline(
        val vertexIndices: List<Int>,
        val faceGroup: Int,
    )
}

// ---------------------------------------------------------------------------
// Element types
// ---------------------------------------------------------------------------

/**
 * Tri-Strip Set Shape LOD Element (§7.1.1, Figure 81) in the JT 9 generation's wire layout
 * (fixture-verified; deltas and the trailing reserved fields in DESIGN.md). [geometry] is the
 * decoded triangle mesh — derived deterministically from the wire data at decode time.
 */
data class TriStripSetShapeLodElement(
    override val objectId: Int,
    /** Base Shape LOD Data version (Figure 86; I16 in JT 9). */
    val baseShapeLodVersion: Int,
    /** Vertex Shape LOD Data version (Figure 85; I16 in JT 9). */
    val vertexShapeLodVersion: Int,
    /** Vertex Bindings (Table 48). */
    val vertexBindings: ULong,
    val topoMesh: TopoMeshLodData,
    /** TopoMesh Topologically Compressed LOD Data version (Figure 91; I16 in JT 9). */
    val topologicallyCompressedVersion: Int,
    val repData: TopologicallyCompressedRepData,
    /** Trailing reserved I16 of the JT 9 layout (semantics unconfirmed; value 1 in the fixture). */
    val reservedVersion: Int,
    /** Trailing U64 of the JT 9 layout, repeating the vertex bindings in the fixture. */
    val reservedBindings: ULong,
    /** The element's own version number (Figure 81; trailing I16 in JT 9). */
    val version: Int,
    override val geometry: TriStripGeometry,
) : TypedShapeLodElement(), TriStripGeometryCarrier {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT
}

/**
 * The nested Logical Element Header (Figure 17/18) that the v10 Vertex Shape LOD Data wraps
 * around its TopoMesh LOD collection (Figure 85). Every field is preserved verbatim; the
 * element length is additionally validated against the actual extent at decode. The type
 * GUIDs NX writes here are absent from Annex A — fixture-established (DESIGN.md):
 * [TOPO_MESH_TOPOLOGICALLY_COMPRESSED_LOD_DATA_TYPE_ID] on tri-strip elements,
 * [TOPO_MESH_COMPRESSED_LOD_DATA_TYPE_ID] on polyline elements, base type 9 ("JtBase").
 */
data class NestedElementHeader(
    /** I32 Element Length: bytes from the Object Type ID to the end of the nested element. */
    val elementLength: Int,
    val objectTypeId: Guid,
    val objectBaseType: Int,
    val objectId: Int,
) {
    companion object {
        private fun guid(
            d1: Long,
            d2: Int,
            d3: Int,
            vararg tail: Int,
        ): Guid = Guid(d1.toUInt(), d2.toUShort(), d3.toUShort(), ByteArray(8) { tail[it].toByte() }.toBytes())

        /** The nested type id of TopoMesh Topologically Compressed LOD Data (not in Annex A). */
        val TOPO_MESH_TOPOLOGICALLY_COMPRESSED_LOD_DATA_TYPE_ID: Guid =
            guid(0xF830A5AD, 0xBE4C, 0x4FBC, 0x9B, 0x5F, 0xB9, 0x26, 0x92, 0x78, 0xD2, 0xE1)

        /** The nested type id of TopoMesh Compressed LOD Data (not in Annex A). */
        val TOPO_MESH_COMPRESSED_LOD_DATA_TYPE_ID: Guid =
            guid(0x11C12D32, 0x38F9, 0x45BA, 0x93, 0xBA, 0x66, 0xF9, 0xD5, 0x38, 0xDD, 0xFB)
    }
}

/**
 * Tri-Strip Set Shape LOD Element in the v10 wire layout (Figure 81/85/91/92, verified
 * against the NIST 10.5 fixture — DESIGN.md): element header, two I8 versions, U64 bindings,
 * the nested Logical Element Header, TopoMesh LOD Data (U8 version + U32 object id), the
 * Topologically Compressed LOD version, the rep data, and the element's trailing U8 version.
 * [geometry] is the decoded triangle mesh — derived deterministically at decode time and
 * validated against the stored hashes.
 */
data class TriStripSetShapeLodElementV10(
    override val objectId: Int,
    /** Base Shape LOD Data version (Figure 86; I8 in v10). */
    val baseShapeLodVersion: Int,
    /** Vertex Shape LOD Data version (Figure 85; I8 in v10). */
    val vertexShapeLodVersion: Int,
    /** Vertex Bindings (Table 48). */
    val vertexBindings: ULong,
    /** The nested Logical Element Header wrapping the TopoMesh collection. */
    val nestedHeader: NestedElementHeader,
    val topoMesh: TopoMeshLodData,
    /** TopoMesh Topologically Compressed LOD Data version (Figure 91; U8 in v10). */
    val topologicallyCompressedVersion: Int,
    val repData: TopologicallyCompressedRepDataV10,
    /** The element's own version number (Figure 81; trailing U8 in v10). */
    val version: Int,
    override val geometry: TriStripGeometry,
) : TypedShapeLodElement(), TriStripGeometryCarrier {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT
}

/**
 * Polyline Set Shape LOD Element in the v10 wire layout (Figure 82/85/87/89, verified
 * against the NIST 10.5 fixture — DESIGN.md): like the tri-strip element but wrapping
 * TopoMesh Compressed LOD Data (the non-topological rep). [geometry] is derived at decode
 * time and validated against the stored hashes.
 */
data class PolylineSetShapeLodElementV10(
    override val objectId: Int,
    /** Base Shape LOD Data version (Figure 86; I8 in v10). */
    val baseShapeLodVersion: Int,
    /** Vertex Shape LOD Data version (Figure 85; I8 in v10). */
    val vertexShapeLodVersion: Int,
    /** Vertex Bindings (Table 48). */
    val vertexBindings: ULong,
    /** The nested Logical Element Header wrapping the TopoMesh collection. */
    val nestedHeader: NestedElementHeader,
    val topoMesh: TopoMeshLodData,
    /** TopoMesh Compressed LOD Data version (Figure 87; U8 in v10). */
    val compressedLodVersion: Int,
    val repData: TopoMeshCompressedRepData,
    /** The element's own version number (Figure 82; trailing U8 in v10). */
    val version: Int,
    val geometry: PolylineGeometry,
) : TypedShapeLodElement() {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.POLYLINE_SET_SHAPE_LOD_ELEMENT
}

/**
 * Null Shape LOD Element (§7.1.5, Figure 94): the bounding-box-only pseudo geometry of a NULL
 * shape node. Spec-derived for both generations (version is I16 in JT 9 per the 9.5
 * reference, U8 in v10), not yet fixture-verified.
 */
data class NullShapeLodElement(
    override val objectId: Int,
    val version: Int,
    val untransformedBBox: BBoxF32,
) : TypedShapeLodElement() {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.NULL_SHAPE_LOD_ELEMENT
}
