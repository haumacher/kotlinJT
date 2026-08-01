package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.LoadNote

// spec: §13.8 (Table 77 — CAD Properties, JT_PROP_MEASUREMENT_UNITS value set)

/**
 * The Layer 2 scene model (issue #1's sketch): a format-agnostic scene — named nodes, local
 * transforms, indexed-triangle meshes (one entry per decoded LOD, finest first), simple
 * materials, units explicit. Nothing in here is JT-specific; this is the seam a glTF writer,
 * a viewer, or the ConstructIt sibling project consumes.
 *
 * Honesty contract: [notes] name everything the scene could not represent faithfully —
 * geometry that did not decode, unrecognized or conflicting unit declarations, attribute
 * semantics the extraction does not model. Silence means the scene is a faithful view.
 */
data class Scene(
    /** The length unit of all coordinates; [LengthUnit.UNSPECIFIED] when the file declares none. */
    val units: LengthUnit,
    val root: SceneNode,
    /** Named refusals of the scene extraction; empty means nothing was abstracted dishonestly. */
    val notes: List<LoadNote>,
)

/**
 * One node of the scene tree. [transform] is the node's *local* transform; a node's world
 * transform is `local · parentWorld` under the row-vector convention (see [Mat4]). [material]
 * is the material in effect for this node's own meshes; `null` means the parent's material is
 * inherited (materials replace down the tree, nearest non-null on the path wins).
 *
 * **One node per body, one list entry per LOD.** A node's geometry is a *single* body — one
 * shape of the source model — and [meshes] / [polylines] are that one body at successively
 * coarser levels of detail, finest first. Two bodies are two nodes, so two materials never
 * have to be merged into one; a part built from several bodies is a node with several
 * geometry-bearing children. Rendering a scene at level *i* therefore means taking entry *i*
 * (or the last, where a node has fewer) of every node — never several entries of one node.
 *
 * Instanced subtrees are shared: two scene paths reaching the same underlying node hold the
 * *same* [SceneNode] (and [Mesh]) object. All types here are immutable, so sharing is safe;
 * equality stays structural.
 */
data class SceneNode(
    /** The node's name, decoded per the JT_PROP_NAME convention; empty when unnamed. */
    val name: String,
    val transform: Mat4,
    /** This node's body as one triangle mesh per decoded LOD, finest first (see [LodPolicy]). */
    val meshes: List<Mesh>,
    /** This node's body as one polyline set per decoded LOD — wireframe bodies land here. */
    val polylines: List<PolylineSet>,
    val material: Material?,
    val children: List<SceneNode>,
)

/**
 * An indexed triangle mesh. Positions and normals are separately indexed (OBJ-style): each
 * triangle corner names a position index and a normal index, so flat- and smooth-shaded
 * regions share positions without duplicating them. Consumers needing a single index (glTF)
 * re-index; nothing is lost here.
 */
data class Mesh(
    val positions: List<Vec3>,
    /** Unique normals; empty when the source geometry binds none (then all `n*` are -1). */
    val normals: List<Vec3>,
    val triangles: List<Triangle>,
) {
    /** One triangle: three position indices and three normal indices (-1 without normals). */
    data class Triangle(
        val v0: Int,
        val v1: Int,
        val v2: Int,
        val n0: Int,
        val n1: Int,
        val n2: Int,
    )
}

/** An indexed polyline set: shared positions and one index run per polyline. */
data class PolylineSet(
    val positions: List<Vec3>,
    /** Each polyline as an ordered list of position indices (at least two per line). */
    val lines: List<List<Int>>,
)

/**
 * A simple PBR-ish material. The JT source is a Phong material; the mapping (recorded in
 * DESIGN.md) is: [baseColor] = diffuse colour + alpha; [roughness] = `sqrt(2 / (2 +
 * shininess))` (the standard Blinn-Phong exponent → microfacet roughness conversion),
 * clamped to [0, 1]; [metallic] = 0 — classic JT materials carry no metalness concept, and
 * inventing one from specular chroma would be a guess. Ambient/specular/emission/reflectivity
 * remain visible at Layer 1.
 */
data class Material(
    val baseColor: Color,
    val roughness: Float,
    val metallic: Float,
)

/** An RGBA colour, each channel in [0, 1]. */
data class Color(
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
)

/** A 3-component single-precision vector — the currency of mesh data. */
data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * A 4×4 double-precision matrix, row-major storage, **row-vector convention**: a point
 * transforms as `p' = p · M`, translation lives in elements 12–14. Composition down a scene
 * path is `world(child) = child.transform * world(parent)` — the convention the fixture
 * probes validated for JT and the same flat-array layout glTF uses for its (column-major,
 * column-vector) matrices, so arrays interchange without transposition.
 */
data class Mat4(
    val values: List<Double>,
) {
    init {
        require(values.size == 16) { "Mat4 needs 16 values, got ${values.size}" }
    }

    /** Matrix product `this · other`: apply `this` first, then `other` (row-vector convention). */
    operator fun times(other: Mat4): Mat4 {
        val a = values
        val b = other.values
        val out = ArrayList<Double>(16)
        for (r in 0..3) {
            for (c in 0..3) {
                var s = 0.0
                for (k in 0..3) s += a[r * 4 + k] * b[k * 4 + c]
                out.add(s)
            }
        }
        return Mat4(out)
    }

    /** Transforms a point: `p' = p · M`, with full homogeneous divide. */
    fun transformPoint(p: Vec3): Vec3 {
        val x = p.x.toDouble()
        val y = p.y.toDouble()
        val z = p.z.toDouble()
        val m = values
        val w = x * m[3] + y * m[7] + z * m[11] + m[15]
        val f = if (w != 0.0 && w != 1.0) 1.0 / w else 1.0
        return Vec3(
            ((x * m[0] + y * m[4] + z * m[8] + m[12]) * f).toFloat(),
            ((x * m[1] + y * m[5] + z * m[9] + m[13]) * f).toFloat(),
            ((x * m[2] + y * m[6] + z * m[10] + m[14]) * f).toFloat(),
        )
    }

    companion object {
        val IDENTITY =
            Mat4(
                listOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
    }
}

/**
 * The length unit of a scene's coordinates. The names are the value set of the
 * JT_PROP_MEASUREMENT_UNITS convention (Table 77); [UNSPECIFIED] is the honest answer for a
 * file that declares nothing — never a silent default.
 */
enum class LengthUnit(
    /** Conversion factor to meters; `null` for [UNSPECIFIED]. */
    val metersPerUnit: Double?,
    internal val jtName: String?,
) {
    MICROMETERS(1e-6, "micrometers"),
    MILLIMETERS(1e-3, "millimeters"),
    CENTIMETERS(1e-2, "centimeters"),
    DECIMETERS(1e-1, "decimeters"),
    METERS(1.0, "meters"),
    KILOMETERS(1e3, "kilometers"),
    MILS(2.54e-5, "mils"),
    INCHES(2.54e-2, "inches"),
    FEET(0.3048, "feet"),
    YARDS(0.9144, "yards"),
    MILES(1609.344, "miles"),

    /** The file declares no (recognizable) unit — explicit, so nothing can assume one. */
    UNSPECIFIED(null, null),
    ;

    companion object {
        /**
         * Parses a JT_PROP_MEASUREMENT_UNITS value. Case-insensitive: the spec's own note
         * records that producers write mixed case ("Millimeters" — both fixtures do) and
         * tells implementers to accept it.
         */
        fun parse(value: String): LengthUnit? {
            val trimmed = value.trim()
            return entries.firstOrNull { it.jtName?.equals(trimmed, ignoreCase = true) == true }
        }
    }
}

/** Which LODs [readScene] carries into the scene. */
enum class LodPolicy {
    /** Every decoded LOD of every body, finest first — one list entry per tier it appears in. */
    ALL_LODS,

    /** Only the finest decoded LOD of each body (failed finer tiers are noted, not hidden). */
    FINEST_ONLY,
}
