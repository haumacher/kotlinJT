package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid
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
 * A Shape LOD element carried opaquely: unknown type, a generation without an established
 * wire layout (all v10 shape bodies today — their fixture condition is the LZMA package), or
 * a failed decode. [body] preserves everything after the Object Type ID byte-faithfully.
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
 * written in topology-encoder visit order.
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

// ---------------------------------------------------------------------------
// The decoded geometry surface
// ---------------------------------------------------------------------------

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
    val geometry: TriStripGeometry,
) : TypedShapeLodElement() {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT
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
