package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.BBoxF32
import de.haumacher.kotlinjt.lsg.BaseAttributeData
import de.haumacher.kotlinjt.lsg.BaseNodeData
import de.haumacher.kotlinjt.lsg.BasePropertyAtomData
import de.haumacher.kotlinjt.lsg.BaseShapeData
import de.haumacher.kotlinjt.lsg.CountRange
import de.haumacher.kotlinjt.lsg.ElementPropertyTable
import de.haumacher.kotlinjt.lsg.GeometricTransformAttributeElement
import de.haumacher.kotlinjt.lsg.GroupNodeData
import de.haumacher.kotlinjt.lsg.GroupNodeElement
import de.haumacher.kotlinjt.lsg.InstanceNodeElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LodNodeData
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.LsgElement
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.MaterialAttributeElement
import de.haumacher.kotlinjt.lsg.MetaDataNodeData
import de.haumacher.kotlinjt.lsg.Mx4F64
import de.haumacher.kotlinjt.lsg.NodeElement
import de.haumacher.kotlinjt.lsg.PartNodeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.PolylineSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.PropertyAtomElement
import de.haumacher.kotlinjt.lsg.PropertyEntry
import de.haumacher.kotlinjt.lsg.PropertyTable
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.Rgba
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.TriStripSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.Vec3F32
import de.haumacher.kotlinjt.lsg.VertexShapeData
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.Mat4
import de.haumacher.kotlinjt.scene.Material
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.PolylineSet
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.Vec3
import de.haumacher.kotlinjt.shape.ShapeLodDocument
import de.haumacher.kotlinjt.shape.ShapeLodElement
import kotlin.math.sqrt

/** One authored data segment: its identity, its Table 6 type and its complete payload bytes. */
internal class AuthoredSegment(
    val segmentId: Guid,
    val typeCode: Int,
    val payload: Bytes,
)

/** The authored LSG document plus the shape LOD segments its shape nodes point at. */
internal class AuthoredLsg(
    val document: LsgDocument,
    val shapeSegments: List<AuthoredSegment>,
)

/**
 * Authoring of the §6 Logical Scene Graph from a Layer 2 [Scene] — the inverse of
 * `readScene`'s walk, following the §13.9 part convention (Figure 160: Partition → placement
 * nodes → Part → Range LOD → per-tier shapes) and the §13.8 property conventions
 * (`JT_PROP_NAME`, `JT_PROP_MEASUREMENT_UNITS`, `JT_LLPROP_SHAPEIMPL`).
 *
 * ## What is representable
 *
 * The scene → LSG → scene round trip is exact for every scene the Layer 2 collapse can
 * produce. Two scene shapes the collapse would *not* reproduce are refused with a clear
 * [JtWriteException] instead of being written into a file that reads back differently:
 *
 * 1. a node carrying geometry **and** children (the collapse puts geometry only on leaf-ish
 *    nodes: it would come back on an extra unnamed child);
 * 2. a child node that the collapse would splice out or absorb — an unnamed child with an
 *    identity transform, no material and no geometry (spliced), or a *sole* unnamed child with
 *    an identity transform (absorbed into its parent);
 * 3. a node carrying meshes **and** polyline sets: the scene is one node per body (issue #13),
 *    and a tier holding a tri-strip shape next to a polyline shape reads back as two sibling
 *    nodes, not as one node holding both.
 *
 * Both refusals name the offending path. Lifting them needs a Layer 2 read-side rule, not a
 * writer change — recorded in DESIGN.md's deferral table.
 */
internal class LsgAuthor(
    private val scene: Scene,
    private val order: Endianness,
) {
    private val graph = mutableListOf<LsgElement>()
    private val atoms = mutableListOf<PropertyAtomElement>()
    private val tableOrder = mutableListOf<Int>()
    private val tableEntries = mutableMapOf<Int, MutableList<PropertyEntry>>()
    private val stringAtomIds = mutableMapOf<String, Int>()
    private val shapeSegments = mutableListOf<AuthoredSegment>()

    /** One id space for nodes, attributes and property atoms — object ids are file-global. */
    private var nextObjectId = 1

    /** Identity-keyed memo of the emitted node definitions: shared scene nodes stay shared. */
    private val definedNodes = mutableListOf<SceneNode>()
    private val definedIds = mutableListOf<Int>()

    /** Identity-keyed reference counts, so only genuinely shared nodes get instance nodes. */
    private val countedNodes = mutableListOf<SceneNode>()
    private val referenceCounts = mutableListOf<Int>()

    // Aggregates for the partition node's declared ranges (Figure 23).
    private var totalVertices = 0
    private var totalPolygons = 0
    private var totalArea = 0f
    private val worldBox = BoxAccumulator()

    fun build(): AuthoredLsg {
        // spec: §13.8 (Table 77) — units are a declaration, never a default (issue #1 rule 4).
        if (scene.units == LengthUnit.UNSPECIFIED) {
            throw JtWriteException(
                "the scene declares no length unit; JT files state their units explicitly " +
                    "(JT_PROP_MEASUREMENT_UNITS, §13.8) — set Scene.units before writing",
            )
        }
        countReferences(scene.root)
        validate(scene.root, "/")
        accumulate(scene.root, Mat4.IDENTITY)

        val rootAttributes = attributesOf(scene.root)
        val rootId = nextObjectId++
        // spec: 9.5 §9.8 / Figure 245 (v10 §13) — the reference does not mandate a hierarchy,
        // but names the convention translators follow and warns that "some JT enabled
        // applications may assume [it] exists": Part Node → Range LOD Node → Group per tier →
        // Shape Nodes. Geometry never hangs off the Partition itself. `validate` has already
        // rejected geometry-plus-children, so a geometry-bearing root has no children to lose.
        // The part is nameless and untransformed on purpose: that is exactly the shape the
        // Layer 2 read collapse absorbs back into the root, making read → write → read the
        // identity for a scene the reader would never have produced but a caller may build.
        val children =
            if (scene.root.hasGeometry) {
                val partId = nextObjectId++
                val lodId = lodStructure(scene.root)
                graph.add(
                    PartNodeElement(
                        partId,
                        MetaDataNodeData(
                            GroupNodeData(BaseNodeData(NODE_VERSION, 0u, emptyList()), NODE_VERSION, listOf(lodId)),
                            NODE_VERSION,
                        ),
                        NODE_VERSION,
                        0,
                    ),
                )
                listOf(partId)
            } else {
                childReferences(scene.root)
            }
        val nodeCount = graph.count { it is NodeElement } + 1
        val partition =
            PartitionNodeElement(
                rootId,
                GroupNodeData(BaseNodeData(NODE_VERSION, 0u, rootAttributes), NODE_VERSION, children),
                // The version number the 10.5 producer inserts is not part of the 10.0 wire.
                null,
                // Figure 23/Table 11: bit 0 announces the untransformed box; the simplest legal
                // partition stores neither the bit nor the box.
                0,
                "",
                worldBox.toBBox(),
                totalArea,
                CountRange(totalVertices, totalVertices),
                CountRange(nodeCount, nodeCount),
                CountRange(totalPolygons, totalPolygons),
                null,
            )
        graph.add(0, partition)
        name(rootId, scene.root.name)
        // spec: §13.8 (Table 77 — the unit declaration; producers write it capitalized)
        property(rootId, "JT_PROP_MEASUREMENT_UNITS", unitName(scene.units))

        val document =
            LsgDocument(
                LsgGeneration.V10,
                graph.toList(),
                true,
                atoms.toList(),
                true,
                PropertyTable(
                    PROPERTY_TABLE_VERSION,
                    tableOrder.map { ElementPropertyTable(it, tableEntries.getValue(it).toList()) },
                ),
                Bytes.EMPTY,
            )
        return AuthoredLsg(document, shapeSegments.toList())
    }

    // -----------------------------------------------------------------------
    // Representability
    // -----------------------------------------------------------------------

    private val SceneNode.hasGeometry: Boolean get() = meshes.isNotEmpty() || polylines.isNotEmpty()

    private fun validate(
        node: SceneNode,
        path: String,
    ) {
        if (node.hasGeometry && node.children.isNotEmpty()) {
            throw JtWriteException(
                "$path: a node carrying geometry and children is not representable in the §13.9 part " +
                    "convention this writer emits — move the geometry into a named child node",
            )
        }
        if (node.meshes.isNotEmpty() && node.polylines.isNotEmpty()) {
            throw JtWriteException(
                "$path: a node carrying both meshes and polyline sets is not a single body — the scene " +
                    "reads back with one node per body (issue #13), so this would return as two sibling " +
                    "nodes; give the mesh and the wireframe a node each",
            )
        }
        if (node.meshes.size > MAX_LOD_TIERS || node.polylines.size > MAX_LOD_TIERS) {
            throw JtWriteException(
                "$path: ${maxOf(node.meshes.size, node.polylines.size)} LOD tiers exceed the " +
                    "$MAX_LOD_TIERS shape LOD segment types of Table 6",
            )
        }
        val single = node.children.singleOrNull()
        if (single != null && single.name.isEmpty() && single.transform == Mat4.IDENTITY) {
            throw JtWriteException(
                "$path: a sole unnamed child with an identity transform is absorbed into its parent " +
                    "when the scene is read back — give it a name or a transform",
            )
        }
        for (child in node.children) {
            if (child.name.isEmpty() && child.transform == Mat4.IDENTITY && child.material == null && !child.hasGeometry) {
                throw JtWriteException(
                    "$path: an unnamed, transform-free, material-free child without geometry is spliced " +
                        "out when the scene is read back — it cannot be written faithfully",
                )
            }
        }
        for ((index, child) in node.children.withIndex()) {
            validate(child, "$path${child.name.ifEmpty { "#$index" }}/")
        }
    }

    private fun countReferences(node: SceneNode) {
        val index = countedNodes.indexOfFirst { it === node }
        if (index >= 0) {
            referenceCounts[index] = referenceCounts[index] + 1
            return
        }
        countedNodes.add(node)
        referenceCounts.add(1)
        node.children.forEach(::countReferences)
    }

    /** Sums the counts and world-space bounds the partition node declares. */
    private fun accumulate(
        node: SceneNode,
        parentTransform: Mat4,
    ) {
        val world = node.transform * parentTransform
        for (mesh in node.meshes) {
            totalVertices += mesh.triangles.size * 3
            totalPolygons += mesh.triangles.size
            totalArea += surfaceArea(mesh)
            for (triangle in mesh.triangles) {
                for (corner in listOf(triangle.v0, triangle.v1, triangle.v2)) {
                    mesh.positions.getOrNull(corner)?.let { worldBox.add(world.transformPoint(it)) }
                }
            }
        }
        for (set in node.polylines) {
            totalVertices += set.lines.sumOf { it.size }
            for (position in set.positions) worldBox.add(world.transformPoint(position))
        }
        node.children.forEach { accumulate(it, world) }
    }

    // -----------------------------------------------------------------------
    // Nodes
    // -----------------------------------------------------------------------

    /** The child object ids of [node]: shared children go through an instance node. */
    private fun childReferences(node: SceneNode): List<Int> =
        node.children.map { child ->
            val definition = define(child)
            val shared = referenceCounts[countedNodes.indexOfFirst { it === child }] > 1
            if (!shared) {
                definition
            } else {
                // spec: §6.1.1.4 / §13.9 — an instance node is the format's sharing mechanism;
                // carrying no attributes of its own, it is transparent to the scene walk.
                val instanceId = nextObjectId++
                graph.add(InstanceNodeElement(instanceId, BaseNodeData(NODE_VERSION, 0u, emptyList()), NODE_VERSION, definition))
                instanceId
            }
        }

    /** Emits (once per identity) the definition of [node] and returns its object id. */
    private fun define(node: SceneNode): Int {
        val known = definedNodes.indexOfFirst { it === node }
        if (known >= 0) return definedIds[known]

        val objectId = nextObjectId++
        definedNodes.add(node)
        definedIds.add(objectId)

        val attributes = attributesOf(node)
        val element =
            if (node.hasGeometry) {
                // spec: §13.9 (Figure 160) — geometry hangs off a Part Node via a Range LOD node.
                val lodId = lodStructure(node)
                PartNodeElement(
                    objectId,
                    MetaDataNodeData(
                        GroupNodeData(BaseNodeData(NODE_VERSION, 0u, attributes), NODE_VERSION, listOf(lodId)),
                        NODE_VERSION,
                    ),
                    NODE_VERSION,
                    0,
                )
            } else {
                GroupNodeElement(
                    objectId,
                    GroupNodeData(BaseNodeData(NODE_VERSION, 0u, attributes), NODE_VERSION, childReferences(node)),
                )
            }
        graph.add(element)
        name(objectId, node.name)
        return objectId
    }

    /** The Range LOD node holding one tier per mesh/polyline entry, finest first. */
    private fun lodStructure(node: SceneNode): Int {
        val tierCount = maxOf(node.meshes.size, node.polylines.size)
        val tiers =
            (0 until tierCount).map { tier ->
                val shapes = mutableListOf<Int>()
                node.meshes.getOrNull(tier)?.let { shapes.add(meshShape(it, tier)) }
                node.polylines.getOrNull(tier)?.let { shapes.add(polylineShape(it, tier)) }
                if (shapes.size == 1) {
                    shapes[0]
                } else {
                    val groupId = nextObjectId++
                    graph.add(
                        GroupNodeElement(
                            groupId,
                            GroupNodeData(BaseNodeData(NODE_VERSION, 0u, emptyList()), NODE_VERSION, shapes),
                        ),
                    )
                    groupId
                }
            }
        val lodId = nextObjectId++
        // spec: §6.1.1.8 (Range LOD Node Element) — the installed base writes empty range
        // limits and lets the viewer pick a tier, which is what the scene model can express.
        graph.add(
            RangeLodNodeElement(
                lodId,
                LodNodeData(GroupNodeData(BaseNodeData(NODE_VERSION, 0u, emptyList()), NODE_VERSION, tiers), NODE_VERSION),
                NODE_VERSION,
                emptyList(),
                Vec3F32(0f, 0f, 0f),
            ),
        )
        return lodId
    }

    // -----------------------------------------------------------------------
    // Shape nodes and their late-loaded segments
    // -----------------------------------------------------------------------

    private fun meshShape(
        mesh: Mesh,
        tier: Int,
    ): Int {
        val objectId = nextObjectId++
        val element = ShapeAuthoring.triStripElement(SHAPE_ELEMENT_OBJECT_ID, mesh, order)
        val payload = shapeSegmentPayload(element)
        val box = boundsOf(element.geometry.vertices)
        val vertexCount = element.geometry.vertices.size
        val polygonCount = element.geometry.triangles.size
        graph.add(
            TriStripSetShapeNodeElement(
                objectId,
                VertexShapeData(
                    BaseShapeData(
                        BaseNodeData(NODE_VERSION, 0u, emptyList()),
                        NODE_VERSION,
                        null,
                        box,
                        surfaceArea(mesh),
                        CountRange(vertexCount, vertexCount),
                        CountRange(1, 1),
                        CountRange(polygonCount, polygonCount),
                        // Figure 36's Size is the *in memory* size of the LOD element, explicitly
                        // unrelated to its on-disk size; zero is the spec's "unknown".
                        0u,
                        0f,
                    ),
                    NODE_VERSION,
                    ShapeAuthoring.meshBindings(mesh),
                    null,
                    null,
                ),
            ),
        )
        lateLoadedShape(objectId, tier, payload)
        return objectId
    }

    private fun polylineShape(
        set: PolylineSet,
        tier: Int,
    ): Int {
        val objectId = nextObjectId++
        val element = ShapeAuthoring.polylineElement(SHAPE_ELEMENT_OBJECT_ID, set, order)
        val payload = shapeSegmentPayload(element)
        val box = boundsOf(set.positions.map { Vec3F32(it.x, it.y, it.z) })
        val vertexCount = set.lines.sumOf { it.size }
        graph.add(
            PolylineSetShapeNodeElement(
                objectId,
                VertexShapeData(
                    BaseShapeData(
                        BaseNodeData(NODE_VERSION, 0u, emptyList()),
                        NODE_VERSION,
                        null,
                        box,
                        0f,
                        CountRange(vertexCount, vertexCount),
                        CountRange(1, 1),
                        CountRange(0, 0),
                        0u,
                        0f,
                    ),
                    NODE_VERSION,
                    ShapeAuthoring.polylineBindings,
                    null,
                    null,
                ),
                NODE_VERSION,
                POLYLINE_AREA_FACTOR,
            ),
        )
        lateLoadedShape(objectId, tier, payload)
        return objectId
    }

    /** The complete element data of a shape LOD segment: the element, the marker, Figure 78's table. */
    private fun shapeSegmentPayload(element: ShapeLodElement): Bytes =
        ShapeLodDocument(
            LsgGeneration.V10,
            listOf(element),
            true,
            PropertyTable(PROPERTY_TABLE_VERSION, emptyList()),
            Bytes.EMPTY,
        ).encode(order)

    /**
     * Registers a shape LOD segment and the Late Loaded Property Atom that ties it to its shape
     * node — the association readers resolve by segment GUID (§13.1; DESIGN.md delta 13).
     */
    private fun lateLoadedShape(
        shapeNodeId: Int,
        tier: Int,
        payload: Bytes,
    ) {
        val typeCode = SHAPE_LOD0_SEGMENT_TYPE + tier
        val segmentId = writerGuid(1 + shapeSegments.size)
        shapeSegments.add(AuthoredSegment(segmentId, typeCode, payload))
        val atomId = nextObjectId++
        atoms.add(
            LateLoadedPropertyAtomElement(
                atomId,
                BasePropertyAtomData(ATOM_VERSION, 0u),
                ATOM_VERSION,
                segmentId,
                typeCode,
                0,
                // Figure 76: "guaranteed to always be greater than or equal to 1".
                1,
            ),
        )
        addEntry(shapeNodeId, PropertyEntry(stringAtom("JT_LLPROP_SHAPEIMPL"), atomId))
    }

    // -----------------------------------------------------------------------
    // Attributes
    // -----------------------------------------------------------------------

    private fun attributesOf(node: SceneNode): List<Int> {
        val ids = mutableListOf<Int>()
        if (node.transform != Mat4.IDENTITY) ids.add(transformAttribute(node.transform))
        node.material?.let { ids.add(materialAttribute(it)) }
        return ids
    }

    /** spec: §6.1.2.11 (Figure 63) — the full 4×4 matrix, every element stored. */
    private fun transformAttribute(transform: Mat4): Int {
        val objectId = nextObjectId++
        graph.add(
            GeometricTransformAttributeElement(
                objectId,
                baseAttribute(),
                ATTRIBUTE_VERSION,
                ALL_MATRIX_ELEMENTS_STORED,
                Mx4F64(transform.values),
            ),
        )
        return objectId
    }

    /**
     * spec: §6.1.2.2 (Figure 47) — the inverse of the scene's Phong → PBR mapping recorded in
     * DESIGN.md: diffuse colour and alpha are the base colour, and the Blinn-Phong exponent is
     * recovered from the roughness as `2/roughness² − 2` (the inverse of
     * `roughness = sqrt(2/(2 + shininess))`), clamped to the legal exponent range. Ambient and
     * specular follow the diffuse colour the way the installed base writes them; metallic has
     * no JT counterpart and is not invented into one.
     */
    private fun materialAttribute(material: Material): Int {
        val objectId = nextObjectId++
        val colour = material.baseColor
        graph.add(
            MaterialAttributeElement(
                objectId,
                baseAttribute(),
                ATTRIBUTE_VERSION,
                // Table 18: blending off, no vertex-colour override, zero blend factors.
                0,
                Rgba(colour.r, colour.g, colour.b, 1f),
                Rgba(colour.r, colour.g, colour.b, colour.a),
                Rgba(SPECULAR_LEVEL, SPECULAR_LEVEL, SPECULAR_LEVEL, 1f),
                Rgba(0f, 0f, 0f, 1f),
                shininessOf(material.roughness),
                0f,
                1f,
            ),
        )
        return objectId
    }

    private fun baseAttribute(): BaseAttributeData =
        // Table 15: state flag 8 = persistable, which is what both fixtures' attributes carry;
        // no accumulation force, no inhibited or final fields (the scene models none).
        BaseAttributeData(ATTRIBUTE_VERSION, 8, 0u, 0u, null)

    // -----------------------------------------------------------------------
    // Properties (§13.8)
    // -----------------------------------------------------------------------

    private fun name(
        objectId: Int,
        name: String,
    ) {
        if (name.isEmpty()) return
        // spec: §13.8 (Table 79) — the name is written plainly; readers that recognize the
        // `Name;version;instance:` form pass anything else through verbatim.
        property(objectId, "JT_PROP_NAME", name)
    }

    private fun property(
        objectId: Int,
        key: String,
        value: String,
    ) {
        addEntry(objectId, PropertyEntry(stringAtom(key), stringAtom(value)))
    }

    /** String atoms are interned: identical keys and values share one atom, as producers do. */
    private fun stringAtom(value: String): Int =
        stringAtomIds.getOrPut(value) {
            val objectId = nextObjectId++
            atoms.add(StringPropertyAtomElement(objectId, BasePropertyAtomData(ATOM_VERSION, 0u), ATOM_VERSION, value))
            objectId
        }

    private fun addEntry(
        objectId: Int,
        entry: PropertyEntry,
    ) {
        val entries = tableEntries.getOrPut(objectId) { mutableListOf<PropertyEntry>().also { tableOrder.add(objectId) } }
        entries.add(entry)
    }

    private fun unitName(unit: LengthUnit): String {
        val name = unit.jtName ?: throw JtWriteException("unit $unit has no JT name")
        // The installed base capitalizes the value ("Millimeters"); readers accept both cases
        // (§13.8's own note), so the writer follows the producers.
        return name.replaceFirstChar { it.uppercaseChar() }
    }

    // -----------------------------------------------------------------------
    // Geometry helpers
    // -----------------------------------------------------------------------

    private class BoxAccumulator {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY
        private var empty = true

        fun add(point: Vec3) {
            empty = false
            if (point.x < minX) minX = point.x
            if (point.y < minY) minY = point.y
            if (point.z < minZ) minZ = point.z
            if (point.x > maxX) maxX = point.x
            if (point.y > maxY) maxY = point.y
            if (point.z > maxZ) maxZ = point.z
        }

        fun toBBox(): BBoxF32 =
            if (empty) {
                BBoxF32(Vec3F32(0f, 0f, 0f), Vec3F32(0f, 0f, 0f))
            } else {
                BBoxF32(Vec3F32(minX, minY, minZ), Vec3F32(maxX, maxY, maxZ))
            }
    }

    private fun boundsOf(points: List<Vec3F32>): BBoxF32 {
        val box = BoxAccumulator()
        for (point in points) box.add(Vec3(point.x, point.y, point.z))
        return box.toBBox()
    }

    private fun surfaceArea(mesh: Mesh): Float {
        var area = 0.0
        for (triangle in mesh.triangles) {
            val a = mesh.positions.getOrNull(triangle.v0) ?: continue
            val b = mesh.positions.getOrNull(triangle.v1) ?: continue
            val c = mesh.positions.getOrNull(triangle.v2) ?: continue
            val ux = (b.x - a.x).toDouble()
            val uy = (b.y - a.y).toDouble()
            val uz = (b.z - a.z).toDouble()
            val vx = (c.x - a.x).toDouble()
            val vy = (c.y - a.y).toDouble()
            val vz = (c.z - a.z).toDouble()
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            area += 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
        }
        return area.toFloat()
    }

    companion object {
        /** Local version numbers of the v10 element bodies this writer emits (all "1"). */
        private const val NODE_VERSION = 1
        private const val ATTRIBUTE_VERSION = 1
        private const val ATOM_VERSION = 1

        /** Figure 78: the Property Table's own version number. */
        private const val PROPERTY_TABLE_VERSION = 1

        /** Figure 63's stored-values mask with all sixteen matrix elements on the wire. */
        private const val ALL_MATRIX_ELEMENTS_STORED = 0xFFFF

        /** Table 6: Shape LOD0; tier *n* uses type `7 + n` up to LOD9. */
        private const val SHAPE_LOD0_SEGMENT_TYPE = 7
        private const val MAX_LOD_TIERS = 10

        /** Shape LOD elements are their segment's only object; the installed base numbers them 0. */
        private const val SHAPE_ELEMENT_OBJECT_ID = 0

        /** Figure 40's area factor, with the value the installed base writes. */
        private const val POLYLINE_AREA_FACTOR = 0.01f

        /** The specular level the installed base pairs with a diffuse material. */
        private const val SPECULAR_LEVEL = 0.35f

        /**
         * The inverse of the scene's `roughness = sqrt(2 / (2 + shininess))` mapping. Roughness 0
         * would demand an infinite exponent, so the result is clamped to the range Phong
         * exponents are written in.
         */
        fun shininessOf(roughness: Float): Float {
            val clamped = roughness.toDouble().coerceIn(MIN_ROUGHNESS, 1.0)
            return (2.0 / (clamped * clamped) - 2.0).coerceIn(0.0, MAX_SHININESS).toFloat()
        }

        /** The roughness that maps to [MAX_SHININESS]: `sqrt(2 / (2 + 128))`. */
        private val MIN_ROUGHNESS: Double = sqrt(2.0 / (2.0 + 128.0))
        private const val MAX_SHININESS = 128.0
    }
}
