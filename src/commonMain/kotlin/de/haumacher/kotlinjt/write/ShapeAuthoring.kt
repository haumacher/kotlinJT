package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.QuantizationParameters
import de.haumacher.kotlinjt.lsg.Vec3F32
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.PolylineSet
import de.haumacher.kotlinjt.shape.CompressedVertexCoordinateArray
import de.haumacher.kotlinjt.shape.CompressedVertexNormalArray
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.JtHash
import de.haumacher.kotlinjt.shape.NestedElementHeader
import de.haumacher.kotlinjt.shape.PointQuantizerData
import de.haumacher.kotlinjt.shape.PolylineGeometry
import de.haumacher.kotlinjt.shape.PolylineSetShapeLodElementV10
import de.haumacher.kotlinjt.shape.Predictor
import de.haumacher.kotlinjt.shape.ShapeLodElement
import de.haumacher.kotlinjt.shape.TopoMeshCompressedRepData
import de.haumacher.kotlinjt.shape.TopoMeshLodData
import de.haumacher.kotlinjt.shape.TopologicallyCompressedRepDataV10
import de.haumacher.kotlinjt.shape.TopologicallyCompressedVertexRecords
import de.haumacher.kotlinjt.shape.TriStripGeometry
import de.haumacher.kotlinjt.shape.TriStripSetShapeLodElementV10
import de.haumacher.kotlinjt.shape.UniformQuantizerData
import de.haumacher.kotlinjt.shape.encodeShapeElementFrame
import de.haumacher.kotlinjt.shape.packResiduals

/**
 * Authoring of §7 Shape LOD element bodies in the v10 wire format, with the simplest encodings
 * the reference permits (issue #1's codec policy): the **null CODEC** for every Int32CDP
 * (Table 64 value 0 — the values as plain 32-bit words) and **lossless binary float** vertex
 * coordinates and normals (§12.1.3/§12.1.4 with zero quantization bits). Every stored hash is
 * computed with [JtHash], so the reader's verification passes by construction rather than by
 * luck.
 *
 * ## Why triangles are written as topologically compressed data at all
 *
 * §7.1.4.1.2.2 is explicit: TopoMesh Compressed Rep Data (the cheap face-group/primitive/
 * vertex index lists that [polylineElement] uses) "is used when the shape type is
 * Polyline Set Shape Node Element, or Point Set Shape Node Element. For Tri-Strip Set Shape
 * Node Element and Polygon Set Shape Node Element, please refer to Topologically Compressed
 * Rep Data" — Figure 85 branches on exactly that. There is no simpler legal representation of
 * triangles in JT 10: the dual-mesh topology coder is the only one.
 *
 * ## The topology this writer emits (recorded in DESIGN.md)
 *
 * Rather than implementing the Annex D *encoder* (whose traversal, split faces and boundary
 * cover faces would have to mirror the decoder's heuristics exactly), the writer emits the
 * simplest topology the coder can express: **one connected component per triangle**, closed
 * by exactly one cover face — the mirror of the triangle itself. §7.1.4.1.3.1 blesses that
 * mechanism ("a value of 1 if the dual face is a cover face that was added to artificially
 * close the original mesh"), and the decoder drops those faces again, so the reader sees
 * exactly the triangles it was given, in the given order. The cost is deliberate and
 * recorded: vertices are not shared between triangles, so a mesh stores three coordinates per
 * triangle. Vertex-sharing (a full Annex D encoder) is a named future extension.
 */
internal object ShapeAuthoring {
    /** Base Shape LOD / Vertex Shape LOD / TopoMesh / element version numbers (all 1 in v10). */
    private const val VERSION = 1

    /** Table 48: bit 2 = 3-component vertex coordinates. */
    private const val BINDING_COORDINATES: ULong = 0x2UL

    /** Table 48: bit 4 = per-vertex normals. */
    private const val BINDING_NORMALS: ULong = 0x8UL

    /** Lossless: zero quantization bits in every field (§12.2.1). */
    private val LOSSLESS_QUANTIZATION = QuantizationParameters(0, 0, 0, 0)

    /** The vertex bindings a mesh needs: coordinates, plus normals when the mesh has them. */
    fun meshBindings(mesh: Mesh): ULong = BINDING_COORDINATES or if (mesh.normals.isEmpty()) 0UL else BINDING_NORMALS

    /** A polyline set binds vertex coordinates only (what the installed base writes, too). */
    val polylineBindings: ULong get() = BINDING_COORDINATES

    // -----------------------------------------------------------------------
    // Int32CDP packets
    // -----------------------------------------------------------------------

    /** A null-CODEC packet carrying [values] verbatim; the empty packet for an empty list. */
    fun nullPacket(values: List<Int>): Int32Cdp =
        if (values.isEmpty()) {
            Int32Cdp.Empty
        } else {
            Int32Cdp.NullCodec(values.size, values.size * 32, values, values)
        }

    /** A null-CODEC packet carrying the Lag1 residuals of [values] (Figure 92's predicted fields). */
    private fun lag1Packet(values: List<Int>): Int32Cdp = nullPacket(packResiduals(values, Predictor.LAG1))

    // -----------------------------------------------------------------------
    // Tri-Strip Set Shape LOD Element (Figure 81)
    // -----------------------------------------------------------------------

    /**
     * The authored tri-strip element plus the geometry a reader will decode from it — the
     * writer's own statement of what it wrote, which the round-trip tests assert against.
     */
    fun triStripElement(
        objectId: Int,
        mesh: Mesh,
        order: Endianness,
    ): TriStripSetShapeLodElementV10 {
        val triangles = mesh.triangles
        if (triangles.isEmpty()) {
            throw JtWriteException("a mesh without triangles cannot be written as a Tri-Strip Set Shape LOD")
        }
        val withNormals = mesh.normals.isNotEmpty()
        val coordinates = ArrayList<Vec3F32>(triangles.size * 3)
        val normals = ArrayList<Vec3F32>(if (withNormals) triangles.size * 3 else 0)
        for (triangle in triangles) {
            for (corner in listOf(triangle.v0, triangle.v1, triangle.v2)) {
                val position =
                    mesh.positions.getOrNull(corner)
                        ?: throw JtWriteException("triangle corner index $corner outside the ${mesh.positions.size} mesh positions")
                coordinates.add(Vec3F32(position.x, position.y, position.z))
            }
            if (withNormals) {
                for (index in listOf(triangle.n0, triangle.n1, triangle.n2)) {
                    val normal =
                        mesh.normals.getOrNull(index)
                            ?: throw JtWriteException(
                                "triangle corner normal index $index outside the ${mesh.normals.size} mesh normals " +
                                    "(a mesh either binds a normal on every corner or on none)",
                            )
                    normals.add(Vec3F32(normal.x, normal.y, normal.z))
                }
            }
        }

        val repData = topology(triangles.size, withNormals, coordinates, normals)
        val geometry =
            TriStripGeometry(
                coordinates,
                normals,
                List(triangles.size) { t ->
                    val base = t * 3
                    TriStripGeometry.Triangle(
                        base,
                        base + 1,
                        base + 2,
                        if (withNormals) base else -1,
                        if (withNormals) base + 1 else -1,
                        if (withNormals) base + 2 else -1,
                        0,
                    )
                },
            )

        // Figure 85's nested Logical Element Header spans the TopoMesh collection; its length
        // is measured by encoding once with a placeholder (a fixed-width field, so the
        // measurement is exact — DESIGN.md delta 27 for the field's extent).
        val provisional =
            TriStripSetShapeLodElementV10(
                objectId,
                VERSION,
                VERSION,
                meshBindings(mesh),
                NestedElementHeader(0, NestedElementHeader.TOPO_MESH_TOPOLOGICALLY_COMPRESSED_LOD_DATA_TYPE_ID, JT_BASE_TYPE, 1),
                TopoMeshLodData(VERSION, 1),
                VERSION,
                repData,
                VERSION,
                geometry,
            )
        return provisional.copy(nestedHeader = provisional.nestedHeader.copy(elementLength = nestedElementLength(provisional, order)))
    }

    /**
     * The Topologically Compressed Rep Data (Figure 92) of the per-triangle component topology
     * documented above: for triangle `t` the coder visits the triangle (dual vertex `2t`,
     * valence 3), its three corner vertices (dual faces `3t…3t+2`, each of degree 2 — the
     * triangle and its cover face), then the cover face (dual vertex `2t+1`, flagged 1). The
     * face degree of the first face of each component falls into context 1, the other two into
     * context 0 (the valence/known-degree rule of Annex D); degree-2 faces take attribute mask
     * context 0. No face is ever split, and no mask needs more than one bit, so the split and
     * high-degree streams stay empty.
     */
    private fun topology(
        triangleCount: Int,
        withNormals: Boolean,
        coordinates: List<Vec3F32>,
        normals: List<Vec3F32>,
    ): TopologicallyCompressedRepDataV10 {
        val valences = ArrayList<Int>(triangleCount * 2)
        val groups = ArrayList<Int>(triangleCount * 2)
        val flags = ArrayList<Int>(triangleCount * 2)
        val degreesContext0 = ArrayList<Int>(triangleCount * 2)
        val degreesContext1 = ArrayList<Int>(triangleCount)
        val masksContext0 = ArrayList<Int>(triangleCount * 3)
        val mask = if (withNormals) 1 else 0
        repeat(triangleCount) {
            valences.add(3)
            valences.add(3)
            groups.add(0)
            groups.add(0)
            flags.add(0)
            flags.add(1)
            degreesContext1.add(2)
            degreesContext0.add(2)
            degreesContext0.add(2)
            masksContext0.add(mask)
            masksContext0.add(mask)
            masksContext0.add(mask)
        }

        val faceDegrees =
            List(8) { context ->
                when (context) {
                    0 -> nullPacket(degreesContext0)
                    1 -> nullPacket(degreesContext1)
                    else -> Int32Cdp.Empty
                }
            }
        val faceAttributeMasks = List(8) { context -> if (context == 0) nullPacket(masksContext0) else Int32Cdp.Empty }
        val valencePacket = nullPacket(valences)
        val groupPacket = nullPacket(groups)
        val flagPacket = lag1Packet(flags)
        val splitFaceSymbols = Int32Cdp.Empty
        val splitFacePositions = Int32Cdp.Empty
        val mask8Msb = Int32Cdp.Empty

        // §7.1.4.1.3.1's hash pseudo-code, in the reader's exact order.
        var hash = 0
        for (packet in faceDegrees) hash = JtHash.hash32(packet.values.toIntArray(), hash)
        hash = JtHash.hash32(valencePacket.values.toIntArray(), hash)
        hash = JtHash.hash32(groupPacket.values.toIntArray(), hash)
        hash = JtHash.hash16(IntArray(flags.size) { flags[it] and 0xFFFF }, hash)
        for (packet in faceAttributeMasks) hash = JtHash.hash32(packet.values.toIntArray(), hash)
        hash = JtHash.hash32(mask8Msb.values.toIntArray(), hash)
        // The high-degree mask array: empty here, but the hash still mixes its (zero) length in,
        // exactly as the pseudo-code and the reader do — skipping the call would change the hash.
        hash = JtHash.hash32(IntArray(0), hash)
        hash = JtHash.hash32(splitFaceSymbols.values.toIntArray(), hash)
        hash = JtHash.hash32(splitFacePositions.values.toIntArray(), hash)

        val attributeCount = if (withNormals) triangleCount * 3 else 0
        val records =
            TopologicallyCompressedVertexRecords(
                BINDING_COORDINATES or if (withNormals) BINDING_NORMALS else 0UL,
                LOSSLESS_QUANTIZATION,
                coordinates.size,
                attributeCount,
                coordinateArray(coordinates),
                if (withNormals) normalArray(normals) else null,
                null,
            )
        return TopologicallyCompressedRepDataV10(
            faceDegrees,
            valencePacket,
            groupPacket,
            flagPacket,
            faceAttributeMasks,
            mask8Msb,
            emptyList(),
            splitFaceSymbols,
            splitFacePositions,
            hash,
            records,
        )
    }

    // -----------------------------------------------------------------------
    // Polyline Set Shape LOD Element (Figure 82 / Figure 89)
    // -----------------------------------------------------------------------

    /** The authored polyline element: the non-topological rep the spec assigns to polylines. */
    fun polylineElement(
        objectId: Int,
        set: PolylineSet,
        order: Endianness,
    ): PolylineSetShapeLodElementV10 {
        if (set.lines.isEmpty()) {
            throw JtWriteException("a polyline set without lines cannot be written as a Polyline Set Shape LOD")
        }
        val vertexIndices = ArrayList<Int>()
        val primitiveIndices = ArrayList<Int>(set.lines.size + 1)
        primitiveIndices.add(0)
        for (line in set.lines) {
            if (line.size < 2) throw JtWriteException("a polyline needs at least two points, got ${line.size}")
            for (index in line) {
                if (index !in set.positions.indices) {
                    throw JtWriteException("polyline index $index outside the ${set.positions.size} positions")
                }
                vertexIndices.add(index)
            }
            primitiveIndices.add(vertexIndices.size)
        }
        // One face group holding every polyline: the Layer 2 scene carries no group structure.
        val faceGroupIndices = listOf(0, set.lines.size)

        var fgpvHash = JtHash.hash32(faceGroupIndices.toIntArray(), 0)
        fgpvHash = JtHash.hash32(primitiveIndices.toIntArray(), fgpvHash)
        fgpvHash = JtHash.hash32(vertexIndices.toIntArray(), fgpvHash)

        // One vertex record per unique coordinate (Figure 89's unique-vertex length list).
        val uniqueLengths = List(set.positions.size) { 1 }
        val lengthsPacket = nullPacket(uniqueLengths)
        val lengthsHash = JtHash.hash32(lengthsPacket.values.toIntArray(), 0)

        val repData =
            TopoMeshCompressedRepData(
                faceGroupIndices.size - 1,
                primitiveIndices.size - 1,
                vertexIndices.size,
                lag1Packet(faceGroupIndices),
                lag1Packet(primitiveIndices),
                lag1Packet(vertexIndices),
                fgpvHash,
                polylineBindings,
                LOSSLESS_QUANTIZATION,
                set.positions.size,
                lengthsPacket,
                lengthsHash,
                coordinateArray(set.positions.map { Vec3F32(it.x, it.y, it.z) }),
                null,
                null,
            )
        val provisional =
            PolylineSetShapeLodElementV10(
                objectId,
                VERSION,
                VERSION,
                polylineBindings,
                NestedElementHeader(0, NestedElementHeader.TOPO_MESH_COMPRESSED_LOD_DATA_TYPE_ID, JT_BASE_TYPE, 1),
                TopoMeshLodData(VERSION, 1),
                VERSION,
                repData,
                VERSION,
                PolylineGeometry(
                    set.positions.map { Vec3F32(it.x, it.y, it.z) },
                    set.lines.map { PolylineGeometry.Polyline(it, 0) },
                ),
            )
        return provisional.copy(nestedHeader = provisional.nestedHeader.copy(elementLength = nestedElementLength(provisional, order)))
    }

    // -----------------------------------------------------------------------
    // Vertex arrays (§12.1.3 / §12.1.4, lossless paths)
    // -----------------------------------------------------------------------

    /**
     * A lossless Compressed Vertex Coordinate Array (Figure 138): one Lag1-predicted null-CODEC
     * packet of raw float bits per component, hashed per whole component array (DESIGN.md
     * delta 29 — the reference's per-value pseudo-code does not match what real files store).
     * The quantizer is written with zero bits (which is what marks the array lossless) and the
     * component ranges, which no reader consumes but which stay honest about the data.
     */
    fun coordinateArray(coordinates: List<Vec3F32>): CompressedVertexCoordinateArray {
        val components =
            listOf(
                coordinates.map { it.x },
                coordinates.map { it.y },
                coordinates.map { it.z },
            )
        var hash = 0
        val packets = ArrayList<Int32Cdp>(3)
        for (component in components) {
            val bits = component.map { it.toRawBits() }
            hash = JtHash.hash32(bits.toIntArray(), hash)
            packets.add(lag1Packet(bits))
        }
        val quantizer =
            PointQuantizerData(
                losslessQuantizer(components[0]),
                losslessQuantizer(components[1]),
                losslessQuantizer(components[2]),
            )
        return CompressedVertexCoordinateArray(coordinates.size, 3, quantizer, packets, hash, coordinates)
    }

    private fun losslessQuantizer(values: List<Float>): UniformQuantizerData =
        UniformQuantizerData(values.minOrNull() ?: 0f, values.maxOrNull() ?: 0f, 0)

    /**
     * A lossless Compressed Vertex Normal Array (Figure 139): one null-CODEC packet of raw
     * float bits per component with the NULL predictor (DESIGN.md delta 30 — the figure, not
     * the prose, is what real files follow), hashed per component array.
     */
    fun normalArray(normals: List<Vec3F32>): CompressedVertexNormalArray {
        val components =
            listOf(
                normals.map { it.x },
                normals.map { it.y },
                normals.map { it.z },
            )
        var hash = 0
        val packets = ArrayList<Int32Cdp>(3)
        for (component in components) {
            val bits = component.map { it.toRawBits() }
            hash = JtHash.hash32(bits.toIntArray(), hash)
            packets.add(nullPacket(bits))
        }
        return CompressedVertexNormalArray(normals.size, 3, 0, packets, hash, normals)
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /** Table 7: the "JtBase" Object Base Type of the nested TopoMesh element (DESIGN.md delta 27). */
    private const val JT_BASE_TYPE = 9

    /**
     * The nested element's I32 Element Length: everything after that field except the outer
     * element's trailing version byte. Measured by encoding the element once — the field's own
     * width is fixed, so the measurement does not depend on the placeholder value.
     */
    private fun nestedElementLength(
        element: ShapeLodElement,
        order: Endianness,
    ): Int {
        val writer = ByteWriter(order)
        encodeShapeElementFrame(writer, LsgGeneration.V10, element)
        // frame = I32 length + GUID(16) + body; the body's fields before the nested length are
        // base type (1), object id (4), two versions (2) and the U64 bindings (8).
        val bodySize = writer.size - 4 - 16
        return bodySize - (1 + 4 + 1 + 1 + 8) - 4 - 1
    }
}
