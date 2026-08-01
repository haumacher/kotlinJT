package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.Mat4
import de.haumacher.kotlinjt.scene.Material
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.PolylineSet
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.Vec3
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comparison tolerance for single-precision values. Zero would be right on the JVM, where
 * every scene value is already a narrowed `Float`; on Kotlin/JS a `Float` is a `Double` until
 * something narrows it, so a literal `0.8f` in a test scene differs from the exact
 * single-precision value the file round trip produces. The wire itself is bit-exact — the
 * lossless arrays store raw float bits — so this tolerance covers the platform's number
 * representation, not the format.
 */
private const val EPSILON = 1e-6f

private fun assertClose(
    expected: Float,
    actual: Float,
    what: String,
) {
    assertTrue(abs(expected - actual) <= EPSILON * maxOf(1f, abs(expected)), "$what: $expected vs $actual")
}

private fun assertClose(
    expected: Vec3,
    actual: Vec3,
    what: String,
) {
    assertClose(expected.x, actual.x, "$what.x")
    assertClose(expected.y, actual.y, "$what.y")
    assertClose(expected.z, actual.z, "$what.z")
}

/**
 * Scene equivalence as the writer promises it: everything the [Scene] model carries survives
 * writing and re-reading, with one documented difference — mesh vertices are **re-indexed**.
 * The writer's topology (one component per triangle, DESIGN.md) stores three coordinates per
 * triangle, so `positions`/`normals` come back as per-corner arrays. What must be identical is
 * therefore the *resolved* geometry: for every triangle, in order, the three corner positions
 * and the three corner normals as values.
 *
 * Materials are compared with a tolerance: the scene's roughness is a `sqrt` of the JT
 * shininess, and the writer's inverse (`2/roughness² − 2`) travels through two single-precision
 * roundings.
 */
fun assertSceneEquivalent(
    expected: Scene,
    actual: Scene,
    message: String = "",
) {
    assertEquals(expected.units, actual.units, "$message: units")
    assertEquals(emptyList(), actual.notes.map { it.name }, "$message: the re-read scene must be note-free")
    assertNodeEquivalent(expected.root, actual.root, if (message.isEmpty()) "/" else "$message /")
}

private fun assertNodeEquivalent(
    expected: SceneNode,
    actual: SceneNode,
    path: String,
) {
    assertEquals(expected.name, actual.name, "$path: name")
    assertEquals(expected.transform, actual.transform, "$path: transform")
    assertMaterialEquivalent(expected.material, actual.material, path)
    assertEquals(expected.meshes.size, actual.meshes.size, "$path: LOD mesh count")
    assertEquals(expected.polylines.size, actual.polylines.size, "$path: LOD polyline count")
    for (tier in expected.meshes.indices) {
        assertMeshEquivalent(expected.meshes[tier], actual.meshes[tier], "$path meshes[$tier]")
    }
    for (tier in expected.polylines.indices) {
        assertPolylinesEquivalent(expected.polylines[tier], actual.polylines[tier], "$path polylines[$tier]")
    }
    assertEquals(expected.children.size, actual.children.size, "$path: child count")
    for (index in expected.children.indices) {
        val child = expected.children[index]
        assertNodeEquivalent(child, actual.children[index], "$path${child.name.ifEmpty { "#$index" }}/")
    }
}

private fun assertMaterialEquivalent(
    expected: Material?,
    actual: Material?,
    path: String,
) {
    if (expected == null || actual == null) {
        assertEquals(expected, actual, "$path: material")
        return
    }
    assertClose(expected.baseColor.r, actual.baseColor.r, "$path: base colour red")
    assertClose(expected.baseColor.g, actual.baseColor.g, "$path: base colour green")
    assertClose(expected.baseColor.b, actual.baseColor.b, "$path: base colour blue")
    assertClose(expected.baseColor.a, actual.baseColor.a, "$path: base colour alpha")
    assertEquals(expected.metallic, actual.metallic, "$path: metallic")
    assertTrue(
        abs(expected.roughness - actual.roughness) < 1e-5f,
        "$path: roughness ${expected.roughness} vs ${actual.roughness}",
    )
}

private fun assertMeshEquivalent(
    expected: Mesh,
    actual: Mesh,
    path: String,
) {
    assertEquals(expected.triangles.size, actual.triangles.size, "$path: triangle count")
    for ((index, triangle) in expected.triangles.withIndex()) {
        val actualTriangle = actual.triangles[index]
        val expectedCorners = corners(expected, triangle)
        val actualCorners = corners(actual, actualTriangle)
        for (corner in 0 until 3) {
            assertClose(expectedCorners[corner], actualCorners[corner], "$path triangle $index corner $corner position")
        }
        val expectedNormals = cornerNormals(expected, triangle)
        val actualNormals = cornerNormals(actual, actualTriangle)
        for (corner in 0 until 3) {
            val one = expectedNormals[corner]
            val other = actualNormals[corner]
            if (one == null || other == null) {
                assertEquals(one, other, "$path triangle $index corner $corner: normal binding")
            } else {
                assertClose(one, other, "$path triangle $index corner $corner normal")
            }
        }
    }
}

private fun corners(
    mesh: Mesh,
    triangle: Mesh.Triangle,
): List<Vec3> = listOf(mesh.positions[triangle.v0], mesh.positions[triangle.v1], mesh.positions[triangle.v2])

private fun cornerNormals(
    mesh: Mesh,
    triangle: Mesh.Triangle,
): List<Vec3?> =
    listOf(triangle.n0, triangle.n1, triangle.n2).map { index ->
        if (index < 0) null else mesh.normals[index]
    }

private fun assertPolylinesEquivalent(
    expected: PolylineSet,
    actual: PolylineSet,
    path: String,
) {
    assertEquals(expected.lines.size, actual.lines.size, "$path: polyline count")
    for ((index, line) in expected.lines.withIndex()) {
        val expectedPoints = line.map { expected.positions[it] }
        val actualPoints = actual.lines[index].map { actual.positions[it] }
        assertEquals(expectedPoints.size, actualPoints.size, "$path polyline $index: point count")
        for (point in expectedPoints.indices) {
            assertClose(expectedPoints[point], actualPoints[point], "$path polyline $index point $point")
        }
    }
}

// ---------------------------------------------------------------------------
// Synthetic scenes
// ---------------------------------------------------------------------------

/** A closed unit cube: 8 shared positions, 6 face normals, 12 triangles. */
fun unitCubeMesh(offset: Vec3 = Vec3(0f, 0f, 0f)): Mesh {
    val positions =
        listOf(
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(1f, 1f, 0f),
            Vec3(0f, 1f, 0f),
            Vec3(0f, 0f, 1f),
            Vec3(1f, 0f, 1f),
            Vec3(1f, 1f, 1f),
            Vec3(0f, 1f, 1f),
        ).map { Vec3(it.x + offset.x, it.y + offset.y, it.z + offset.z) }
    val normals =
        listOf(
            Vec3(0f, 0f, -1f),
            Vec3(0f, 0f, 1f),
            Vec3(0f, -1f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(0f, 1f, 0f),
            Vec3(-1f, 0f, 0f),
        )
    val triangles = mutableListOf<Mesh.Triangle>()

    fun quad(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        normal: Int,
    ) {
        triangles.add(Mesh.Triangle(a, b, c, normal, normal, normal))
        triangles.add(Mesh.Triangle(a, c, d, normal, normal, normal))
    }
    quad(0, 2, 1, 3, 0)
    quad(4, 5, 6, 7, 1)
    quad(0, 1, 5, 4, 2)
    quad(1, 2, 6, 5, 3)
    quad(2, 3, 7, 6, 4)
    quad(3, 0, 4, 7, 5)
    return Mesh(positions, normals, triangles)
}

/** A coarser stand-in for a further LOD tier: the cube's two bottom triangles. */
fun coarseCubeMesh(): Mesh {
    val cube = unitCubeMesh()
    return Mesh(cube.positions, cube.normals, cube.triangles.take(2))
}

/** A two-segment polyline over three points. */
fun testPolylines(): PolylineSet =
    PolylineSet(
        positions = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 1f, 0f)),
        lines = listOf(listOf(0, 1, 2), listOf(2, 3)),
    )

/** A translation matrix in the scene's row-vector convention (translation in elements 12–14). */
fun translation(
    x: Double,
    y: Double,
    z: Double,
): Mat4 =
    Mat4(
        listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            x, y, z, 1.0,
        ),
    )

/** A leaf part node with one mesh tier. */
fun part(
    name: String,
    mesh: Mesh,
    material: Material? = null,
    transform: Mat4 = Mat4.IDENTITY,
): SceneNode = SceneNode(name, transform, listOf(mesh), emptyList(), material, emptyList())

/** A scene with explicit millimeter units around [root]. */
fun millimeterScene(root: SceneNode): Scene = Scene(LengthUnit.MILLIMETERS, root, emptyList())
