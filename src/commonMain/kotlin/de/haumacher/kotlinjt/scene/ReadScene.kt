package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.AttributeElement
import de.haumacher.kotlinjt.lsg.BaseAttributeData
import de.haumacher.kotlinjt.lsg.GeometricTransformAttributeElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LodNodeElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.MaterialAttributeElement
import de.haumacher.kotlinjt.lsg.NodeElement
import de.haumacher.kotlinjt.lsg.NullShapeNodeElement
import de.haumacher.kotlinjt.lsg.OpaqueLsgElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.PropertyAtomElement
import de.haumacher.kotlinjt.lsg.PropertyEntry
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.ShapeNodeElement
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.childObjectIds
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.shape.PolylineGeometry
import de.haumacher.kotlinjt.shape.ShapeLodDocument
import de.haumacher.kotlinjt.shape.TriStripGeometry
import de.haumacher.kotlinjt.shape.shapeLodSegments
import kotlin.math.sqrt

/**
 * Reads the format-agnostic scene from a parsed JT file: the LSG walk with attribute
 * accumulation (transforms and materials per the §13.9 semantics), node names per the
 * JT_PROP_NAME convention, units per JT_PROP_MEASUREMENT_UNITS, and the decoded shape LOD
 * geometry resolved through the late-loaded property atoms. Never throws for content
 * problems — everything the scene cannot represent is a named note on the [Scene].
 */
fun JtFile.readScene(lodPolicy: LodPolicy = LodPolicy.ALL_LODS): Scene {
    val decoded =
        decodeLsg()
            ?: return Scene(
                LengthUnit.UNSPECIFIED,
                EMPTY_NODE,
                listOf(LoadNote.SceneStructureUnavailable("no decodable LSG segment (the file's own notes say why)")),
            )
    val segmentsById = shapeLodSegments().associateBy { it.tocEntry.segmentId }
    return buildScene(decoded.document, lodPolicy) { segmentId ->
        val segment =
            segmentsById[segmentId]
                ?: return@buildScene ShapeSource.Unavailable("no shape LOD segment $segmentId in the TOC")
        val elementData =
            segment.elementData
                ?: return@buildScene ShapeSource.Unavailable("segment $segmentId has no decodable element data")
        val result = ShapeLodDocument.decode(elementData, header.version, header.byteOrder)
        ShapeSource.Decoded(
            result.document.triStripGeometry,
            result.document.polylineGeometry,
            result.notes.map { it.name },
        )
    }
}

/** Parses [bytes] as a JT file and reads its scene; see [JtFile.readScene]. */
fun readScene(
    bytes: ByteArray,
    lodPolicy: LodPolicy = LodPolicy.ALL_LODS,
): Scene = JtFile.parse(bytes).readScene(lodPolicy)

private val EMPTY_NODE = SceneNode("", Mat4.IDENTITY, emptyList(), emptyList(), null, emptyList())

/** What the shape resolver found behind one late-loaded segment reference. */
internal sealed class ShapeSource {
    /** The segment decoded; either geometry may be `null`, [noteNames] say what refused. */
    data class Decoded(
        val triangles: TriStripGeometry?,
        val polylines: PolylineGeometry?,
        val noteNames: List<String>,
    ) : ShapeSource()

    /** The segment is missing or produced no element data. */
    data class Unavailable(
        val detail: String,
    ) : ShapeSource()
}

/**
 * Builds the scene from a decoded LSG document and a shape resolver — the seam that lets
 * synthetic tests drive the walk with hand-built documents and geometry.
 */
internal fun buildScene(
    document: LsgDocument,
    lodPolicy: LodPolicy,
    resolveShape: (Guid) -> ShapeSource,
): Scene = SceneBuilder(document, lodPolicy, resolveShape).build()

private class SceneBuilder(
    private val document: LsgDocument,
    private val lodPolicy: LodPolicy,
    private val resolveShape: (Guid) -> ShapeSource,
) {
    private val notes = mutableListOf<LoadNote>()
    private val structureDetails = mutableListOf<String>()
    private val semanticsDetails = mutableListOf<String>()

    private val nodesById =
        document.graphElements.filterIsInstance<NodeElement>().associateBy { it.objectId }
    private val attributesById =
        document.graphElements.filterIsInstance<AttributeElement>().associateBy { it.objectId }
    private val atomsById =
        document.propertyAtoms.filterIsInstance<PropertyAtomElement>().associateBy { it.objectId }
    private val propertiesByElement: Map<Int, List<PropertyEntry>> =
        document.propertyTable?.tables?.associate { it.elementObjectId to it.entries }.orEmpty()

    /** Conversion is memoized per LSG node, so instanced subtrees share their scene objects. */
    private val converted = mutableMapOf<Int, SceneNode?>()
    private val inProgress = mutableSetOf<Int>()

    /** One parent per node (the first referencing one), for locating notes by named ancestor. */
    private val parentOf: Map<Int, Int> =
        buildMap {
            for (node in nodesById.values) {
                for (child in node.childObjectIds) {
                    if (child !in keys) put(child, node.objectId)
                }
            }
        }

    /**
     * The name to locate a note by: the node's own name, or the nearest named ancestor's —
     * per the part convention the name sits on the instance/part above the shape node.
     */
    private fun nearestName(objectId: Int): String {
        var current: Int? = objectId
        var guard = 0
        while (current != null && guard++ < 64) {
            val name = nodeName(current)
            if (name.isNotEmpty()) return name
            current = parentOf[current]
        }
        return ""
    }

    fun build(): Scene {
        val opaque = document.allElements.count { it is OpaqueLsgElement }
        if (opaque > 0) {
            structureDetails.add("$opaque LSG element(s) are carried opaquely (see the LSG decode notes)")
        }
        // spec: §13.9 (scene graph construction: the partition node is the LSG root)
        val rootElement =
            document.graphElements.filterIsInstance<PartitionNodeElement>().firstOrNull()
                ?: run {
                    val referenced = nodesById.values.flatMap { it.childObjectIds }.toSet()
                    nodesById.values.firstOrNull { it.objectId !in referenced }
                }
        val root =
            if (rootElement == null) {
                notes.add(LoadNote.SceneStructureUnavailable("the LSG contains no partition or root node"))
                EMPTY_NODE
            } else {
                convert(rootElement.objectId) ?: EMPTY_NODE
            }
        val units = resolveUnits()
        if (structureDetails.isNotEmpty()) {
            notes.add(LoadNote.SceneStructureIncomplete(structureDetails.joinToString("; ")))
        }
        if (semanticsDetails.isNotEmpty()) {
            notes.add(LoadNote.SceneAttributeSemanticsUnsupported(semanticsDetails.joinToString("; ")))
        }
        return Scene(units, root, notes)
    }

    // --- Units (§13.8: JT_PROP_MEASUREMENT_UNITS) ---

    // spec: §13.8 (CAD Properties — JT_PROP_MEASUREMENT_UNITS is the required unit declaration)
    private fun resolveUnits(): LengthUnit {
        val declared =
            propertiesByElement.values.flatten().mapNotNull { entry ->
                val key = atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement
                if (key?.value?.removeSuffix("::") != "JT_PROP_MEASUREMENT_UNITS") return@mapNotNull null
                (atomsById[entry.valuePropertyAtomObjectId] as? StringPropertyAtomElement)?.value
            }.distinct()
        val recognized = mutableListOf<LengthUnit>()
        for (value in declared) {
            val unit = LengthUnit.parse(value)
            if (unit == null) {
                notes.add(LoadNote.SceneUnitsUnrecognized(value))
            } else if (unit !in recognized) {
                recognized.add(unit)
            }
        }
        return when {
            recognized.size == 1 -> recognized[0]
            recognized.size > 1 -> {
                notes.add(LoadNote.SceneUnitsMixed(declared))
                LengthUnit.UNSPECIFIED
            }
            else -> LengthUnit.UNSPECIFIED
        }
    }

    // --- Properties (§13.8 key conventions) ---

    // spec: §13.8 (Property Key Naming Conventions — hidden "key" vs. visible "key::")

    /** Looks up a string property by key, accepting the hidden and the visible ("::") form. */
    private fun stringProperty(
        elementObjectId: Int,
        key: String,
    ): String? =
        propertiesByElement[elementObjectId].orEmpty().firstNotNullOfOrNull { entry ->
            val keyAtom = atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement
            if (keyAtom?.value?.removeSuffix("::") != key) return@firstNotNullOfOrNull null
            (atomsById[entry.valuePropertyAtomObjectId] as? StringPropertyAtomElement)?.value
        }

    // spec: §13.8 (Table 79 — Miscellaneous Properties, JT_PROP_NAME encoded form)

    /**
     * The node's display name per the JT_PROP_NAME convention. Part/assembly/instance names
     * use the encoded form `Name;version;instance:` (Table 79); the name component is
     * extracted, any other value is taken verbatim. Unnamed nodes yield "".
     */
    private fun nodeName(objectId: Int): String {
        val raw = stringProperty(objectId, "JT_PROP_NAME") ?: return ""
        return NAME_ENCODING.matchEntire(raw)?.groupValues?.get(1) ?: raw
    }

    // --- Attribute accumulation (§13.9 semantics on this node) ---

    private class LocalAttributes(
        val transform: Mat4,
        val material: Material?,
    )

    // spec: §13.9 (LSG Attribute Accumulation Semantics; §6.1.2.1.1 Table 15 state flags)

    /**
     * The transform and material this node introduces. Same-type attributes accumulate in
     * list order: transforms multiply (the row-vector convention the transform probe
     * validated: a later/lower matrix pre-multiplies), materials replace. The Accumulation
     * Ignore flag is honored; force/final/field-inhibit flags are beyond the modelled
     * semantics and produce a named note instead of silent misrepresentation.
     */
    private fun localAttributes(node: NodeElement): LocalAttributes {
        var transform = Mat4.IDENTITY
        var material: Material? = null
        for (id in node.baseNode.attributeObjectIds) {
            val attribute = attributesById[id]
            if (attribute == null) {
                structureDetails.add("attribute reference #$id of node #${node.objectId} does not resolve to a decoded attribute")
                continue
            }
            when (attribute) {
                is GeometricTransformAttributeElement -> {
                    if (checkSemantics(attribute.baseAttribute, "transform #$id")) continue
                    transform = Mat4(attribute.matrix.values) * transform
                }
                is MaterialAttributeElement -> {
                    if (checkSemantics(attribute.baseAttribute, "material #$id")) continue
                    material = mapMaterial(attribute)
                }
                else -> {
                    // Lights, styles, textures: deliberately outside the scene model.
                }
            }
        }
        return LocalAttributes(transform, material)
    }

    /** Returns `true` when the attribute is to be ignored; records unmodelled flag usage. */
    private fun checkSemantics(
        base: BaseAttributeData,
        what: String,
    ): Boolean {
        if (base.stateFlags and 0x02 != 0) semanticsDetails.add("$what sets the Accumulation Force flag")
        if (base.fieldInhibitFlags != 0u) semanticsDetails.add("$what sets field inhibit flags")
        if ((base.fieldFinalFlags ?: 0u) != 0u) semanticsDetails.add("$what sets field final flags")
        return base.stateFlags and 0x04 != 0
    }

    /** The Phong → PBR mapping recorded in DESIGN.md; see [Material]. */
    private fun mapMaterial(attribute: MaterialAttributeElement): Material {
        val diffuse = attribute.diffuseColourAndAlpha
        val roughness = sqrt(2.0 / (2.0 + attribute.shininess)).coerceIn(0.0, 1.0)
        return Material(
            baseColor = Color(diffuse.r, diffuse.g, diffuse.b, diffuse.a),
            roughness = roughness.toFloat(),
            metallic = 0f,
        )
    }

    // --- The walk ---

    private fun convert(objectId: Int): SceneNode? {
        converted[objectId]?.let { return it }
        if (objectId in converted) return null
        val node = nodesById[objectId]
        if (node == null) {
            structureDetails.add("child reference #$objectId does not resolve to a decoded node")
            converted[objectId] = null
            return null
        }
        if (!inProgress.add(objectId)) {
            structureDetails.add("node #$objectId is part of a reference cycle; the repeated occurrence is dropped")
            return null
        }
        val result =
            when (node) {
                is LodNodeElement, is RangeLodNodeElement -> convertLodNode(node)
                is ShapeNodeElement -> convertShapeNode(node)
                else -> convertGroupLike(node)
            }
        inProgress.remove(objectId)
        converted[objectId] = result
        return result
    }

    // spec: §13.9 (LSG Part Structure, Figure 160 — the part convention this collapse folds)

    /**
     * A structural node: convert the children, then collapse pass-through structure — a
     * child that contributes nothing the scene models (no name, identity transform, no
     * material, no geometry) is replaced by its children, and a sole nameless
     * transform-free child is absorbed (its geometry and material move up). The collapse
     * loses nothing the Scene promises to carry; it is what turns the §13.9 part convention
     * (Part → LOD → Group → Shape) into one named part node per part.
     */
    private fun convertGroupLike(node: NodeElement): SceneNode {
        val local = localAttributes(node)
        val children = node.childObjectIds.mapNotNull { convert(it) }
        val spliced = children.flatMap { child -> if (child.isPassThrough) child.children else listOf(child) }
        var meshes = emptyList<Mesh>()
        var polylines = emptyList<PolylineSet>()
        var material = local.material
        var resultChildren = spliced
        val only = spliced.singleOrNull()
        if (only != null && only.name.isEmpty() && only.transform == Mat4.IDENTITY) {
            meshes = only.meshes
            polylines = only.polylines
            material = only.material ?: material
            resultChildren = only.children
        }
        return SceneNode(nodeName(node.objectId), local.transform, meshes, polylines, material, resultChildren)
    }

    private val SceneNode.isPassThrough: Boolean
        get() =
            name.isEmpty() && transform == Mat4.IDENTITY && material == null &&
                meshes.isEmpty() && polylines.isEmpty()

    /** A shape node reached outside any LOD node: a single-LOD leaf. */
    private fun convertShapeNode(node: ShapeNodeElement): SceneNode {
        val local = localAttributes(node)
        val collector = TierCollector()
        collectShapeGeometry(node, Mat4.IDENTITY, local.material, collector)
        val (mesh, meshMaterial) = mergeMeshes(collector, "shape node #${node.objectId}")
        return SceneNode(
            nodeName(node.objectId),
            local.transform,
            listOfNotNull(mesh),
            listOfNotNull(mergePolylines(collector)),
            meshMaterial ?: local.material,
            emptyList(),
        )
    }

    // spec: §13.9 (Range LOD Node Alternative Rep Selection — child order is tier order)

    /**
     * A LOD node: each child subtree is one alternative representation, finest first
     * (§13.9 range LOD selection order). The tiers become the mesh/polyline lists — this is
     * where "one entry per LOD" comes from; the node itself is nameless in the part
     * convention and gets absorbed by its part.
     */
    private fun convertLodNode(node: NodeElement): SceneNode {
        val local = localAttributes(node)
        val meshes = mutableListOf<Mesh>()
        val polylines = mutableListOf<PolylineSet>()
        var material: Material? = null
        for (tierId in node.childObjectIds) {
            val collector = TierCollector()
            collectTier(tierId, Mat4.IDENTITY, local.material, collector)
            val (mesh, meshMaterial) = mergeMeshes(collector, "LOD tier node #$tierId")
            val poly = mergePolylines(collector)
            if (mesh != null) {
                if (lodPolicy == LodPolicy.ALL_LODS || meshes.isEmpty()) meshes.add(mesh)
                if (material == null) {
                    material = meshMaterial
                } else if (meshMaterial != null && meshMaterial != material) {
                    notes.add(
                        LoadNote.SceneMaterialAmbiguous(
                            "LOD tiers of node #${node.objectId} carry different materials; the finest tier's is used",
                        ),
                    )
                }
            }
            if (poly != null && (lodPolicy == LodPolicy.ALL_LODS || polylines.isEmpty())) polylines.add(poly)
        }
        return SceneNode(nodeName(node.objectId), local.transform, meshes, polylines, material ?: local.material, emptyList())
    }

    // --- Geometry collection within one LOD tier ---

    private class CollectedMesh(
        val geometry: TriStripGeometry,
        val transform: Mat4,
        val material: Material?,
    )

    private class CollectedPolylines(
        val geometry: PolylineGeometry,
        val transform: Mat4,
    )

    private class TierCollector {
        val meshes = mutableListOf<CollectedMesh>()
        val polylines = mutableListOf<CollectedPolylines>()
    }

    /**
     * Walks one LOD tier subtree, accumulating transforms (`A(child) = M(child) · A(parent)`)
     * and materials (replacement) into the collected geometry — inner structure below the
     * tier split cannot become scene nodes (the tier is one mesh), so its attributes are
     * folded into the geometry itself.
     */
    private fun collectTier(
        objectId: Int,
        parentTransform: Mat4,
        inheritedMaterial: Material?,
        out: TierCollector,
    ) {
        val node = nodesById[objectId]
        if (node == null) {
            structureDetails.add("child reference #$objectId inside a LOD tier does not resolve to a decoded node")
            return
        }
        val local = localAttributes(node)
        val transform = local.transform * parentTransform
        val material = local.material ?: inheritedMaterial
        when (node) {
            is ShapeNodeElement -> collectShapeGeometry(node, transform, material, out)
            is LodNodeElement, is RangeLodNodeElement -> {
                structureDetails.add("nested LOD node #$objectId inside a LOD tier; only its finest alternative is used")
                node.childObjectIds.firstOrNull()?.let { collectTier(it, transform, material, out) }
            }
            else -> node.childObjectIds.forEach { collectTier(it, transform, material, out) }
        }
    }

    /** Resolves a shape node's late-loaded geometry; every failure mode is a named note. */
    private fun collectShapeGeometry(
        node: ShapeNodeElement,
        transform: Mat4,
        material: Material?,
        out: TierCollector,
    ) {
        // spec: §13.1 (Late-Loading Data: shape geometry lives in its own segment, referenced
        // by a Late Loaded Property Atom whose segment type is a Shape LOD type)
        val segmentIds =
            propertiesByElement[node.objectId].orEmpty().mapNotNull { entry ->
                (atomsById[entry.valuePropertyAtomObjectId] as? LateLoadedPropertyAtomElement)
                    ?.takeIf { it.segmentType in SHAPE_SEGMENT_TYPES }
                    ?.segmentId
            }
        if (segmentIds.isEmpty()) {
            if (node !is NullShapeNodeElement) {
                notes.add(
                    LoadNote.SceneGeometryUnavailable(
                        nearestName(node.objectId),
                        node.objectId,
                        null,
                        "the node has no late-loaded shape segment reference",
                    ),
                )
            }
            return
        }
        for (segmentId in segmentIds) {
            when (val source = resolveShape(segmentId)) {
                is ShapeSource.Unavailable ->
                    notes.add(
                        LoadNote.SceneGeometryUnavailable(nearestName(node.objectId), node.objectId, segmentId, source.detail),
                    )
                is ShapeSource.Decoded -> {
                    if (source.triangles == null && source.polylines == null) {
                        val why =
                            if (source.noteNames.isEmpty()) {
                                "the segment decoded but carries no supported geometry"
                            } else {
                                "the geometry did not decode (${source.noteNames.joinToString()})"
                            }
                        notes.add(
                            LoadNote.SceneGeometryUnavailable(nearestName(node.objectId), node.objectId, segmentId, why),
                        )
                    }
                    source.triangles?.let { out.meshes.add(CollectedMesh(it, transform, material)) }
                    source.polylines?.let { out.polylines.add(CollectedPolylines(it, transform)) }
                }
            }
        }
    }

    // --- Merging collected geometry into one Mesh / PolylineSet per tier ---

    /** Merges the collected triangle geometry; differing materials produce a named note. */
    private fun mergeMeshes(
        collector: TierCollector,
        where: String,
    ): Pair<Mesh?, Material?> {
        val collected = collector.meshes
        if (collected.isEmpty()) return null to null
        val materials = collected.map { it.material }.distinct()
        if (materials.size > 1) {
            notes.add(
                LoadNote.SceneMaterialAmbiguous(
                    "$where merges ${collected.size} shapes with ${materials.size} different materials; the first is used",
                ),
            )
        }
        val positions = mutableListOf<Vec3>()
        val normals = mutableListOf<Vec3>()
        val triangles = mutableListOf<Mesh.Triangle>()
        for (part in collected) {
            val positionBase = positions.size
            val normalBase = normals.size
            positions.addAll(bakePoints(part.geometry.vertices, part.transform))
            normals.addAll(bakeNormals(part.geometry.normals, part.transform))
            for (t in part.geometry.triangles) {
                triangles.add(
                    Mesh.Triangle(
                        t.v0 + positionBase,
                        t.v1 + positionBase,
                        t.v2 + positionBase,
                        if (t.n0 < 0) -1 else t.n0 + normalBase,
                        if (t.n1 < 0) -1 else t.n1 + normalBase,
                        if (t.n2 < 0) -1 else t.n2 + normalBase,
                    ),
                )
            }
        }
        return Mesh(positions, normals, triangles) to materials.firstNotNullOfOrNull { it }
    }

    private fun mergePolylines(collector: TierCollector): PolylineSet? {
        val collected = collector.polylines
        if (collected.isEmpty()) return null
        val positions = mutableListOf<Vec3>()
        val lines = mutableListOf<List<Int>>()
        for (part in collected) {
            val base = positions.size
            positions.addAll(bakePoints(part.geometry.vertices, part.transform))
            for (line in part.geometry.polylines) {
                lines.add(line.vertexIndices.map { it + base })
            }
        }
        return PolylineSet(positions, lines)
    }

    private fun bakePoints(
        vertices: List<de.haumacher.kotlinjt.lsg.Vec3F32>,
        transform: Mat4,
    ): List<Vec3> =
        if (transform == Mat4.IDENTITY) {
            vertices.map { Vec3(it.x, it.y, it.z) }
        } else {
            vertices.map { transform.transformPoint(Vec3(it.x, it.y, it.z)) }
        }

    /** Normals transform with the inverse transpose of the 3×3 part, then renormalize. */
    private fun bakeNormals(
        normals: List<de.haumacher.kotlinjt.lsg.Vec3F32>,
        transform: Mat4,
    ): List<Vec3> {
        if (transform == Mat4.IDENTITY) return normals.map { Vec3(it.x, it.y, it.z) }
        val n = normalMatrix(transform)
        if (n == null) {
            structureDetails.add("a degenerate transform prevents normal transformation; normals kept untransformed")
            return normals.map { Vec3(it.x, it.y, it.z) }
        }
        return normals.map { v ->
            val x = v.x * n[0] + v.y * n[3] + v.z * n[6]
            val y = v.x * n[1] + v.y * n[4] + v.z * n[7]
            val z = v.x * n[2] + v.y * n[5] + v.z * n[8]
            val length = sqrt(x * x + y * y + z * z)
            if (length > 0) Vec3((x / length).toFloat(), (y / length).toFloat(), (z / length).toFloat()) else Vec3(v.x, v.y, v.z)
        }
    }

    /** The inverse transpose of the upper-left 3×3, or `null` when singular. */
    private fun normalMatrix(m: Mat4): DoubleArray? {
        val v = m.values
        val a = v[0]
        val b = v[1]
        val c = v[2]
        val d = v[4]
        val e = v[5]
        val f = v[6]
        val g = v[8]
        val h = v[9]
        val i = v[10]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (det == 0.0 || !det.isFinite()) return null
        val s = 1.0 / det
        // Inverse via adjugate, then transposed: inverseTranspose[row][col] = adjugate[col][row] / det.
        return doubleArrayOf(
            (e * i - f * h) * s, (f * g - d * i) * s, (d * h - e * g) * s,
            (c * h - b * i) * s, (a * i - c * g) * s, (b * g - a * h) * s,
            (b * f - c * e) * s, (c * d - a * f) * s, (a * e - b * d) * s,
        )
    }

    companion object {
        /** Segment types 6–16: Shape and Shape LOD0–LOD9 (Table 6). */
        private val SHAPE_SEGMENT_TYPES = 6..16

        /** `Name;version;instance:` — the JT_PROP_NAME encoded form (Table 79). */
        private val NAME_ENCODING = Regex("^(.*);\\d+;\\d+:$")
    }
}
