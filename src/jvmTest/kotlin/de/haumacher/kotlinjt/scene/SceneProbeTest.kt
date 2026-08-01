package de.haumacher.kotlinjt.scene

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.abs

/**
 * Probe review for the scene façade delivery (issue #7), attacking two claims no test has
 * touched:
 *
 * 1. **Winding consistency.** Tri-strip unrolling with wrong alternation parity produces
 *    flipped triangles — invisible to counts, hashes, and bbox checks, fatal to any renderer.
 *    A closed axis-aligned box makes it measurable: the mesh's signed volume must equal its
 *    bbox volume in magnitude (consistent winding) instead of collapsing toward zero
 *    (alternating flips), and its sign must be uniform per mesh.
 *
 * 2. **The naive exporter's walk.** The scene claims to be consumable by a glTF-shaped
 *    consumer as-is: every index valid, material channels in range, transforms affine.
 */
class SceneProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf("fixtures", "fixtures-local")
            .flatMap { File(repoRoot(), it).listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.toList().orEmpty() }
            .sortedBy { it.name }

    private fun walk(
        node: SceneNode,
        visit: (SceneNode) -> Unit,
    ) {
        visit(node)
        node.children.forEach { walk(it, visit) }
    }

    @TestFactory
    fun sceneProbes(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — scene probe SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.flatMap { fixture ->
            listOf(
                dynamicTest("${fixture.name}: closed boxes carry their full bbox volume — winding is consistent") {
                    val scene = readScene(fixture.readBytes())
                    var boxesChecked = 0
                    walk(scene.root) { node ->
                        for (mesh in node.meshes) {
                            if (!isBboxCornerBox(mesh)) continue
                            boxesChecked++
                            val volume = signedVolume(mesh)
                            val bbox = bboxVolume(mesh)
                            assertTrue(
                                abs(abs(volume) - bbox) <= bbox * 0.01,
                                "box mesh in node '${node.name}': |signed volume| ${abs(volume)} vs bbox volume $bbox — " +
                                    "collapsed volume means inconsistent triangle winding from strip unrolling",
                            )
                        }
                    }
                    assumeTrue(boxesChecked > 0, "no closed 8-corner box mesh in this fixture — winding probe not applicable")
                    println("PROBE scene-winding: $boxesChecked box meshes at full bbox volume")
                },
                dynamicTest("${fixture.name}: a naive glTF-shaped exporter finds nothing to trip over") {
                    val scene = readScene(fixture.readBytes())
                    var meshes = 0
                    var nodes = 0
                    walk(scene.root) { node ->
                        nodes++
                        val t = node.transform.values
                        assertTrue(
                            t[3] == 0.0 && t[7] == 0.0 && t[11] == 0.0 && t[15] == 1.0,
                            "node '${node.name}': transform is not affine (last column ${t[3]}, ${t[7]}, ${t[11]}, ${t[15]})",
                        )
                        node.material?.let { m ->
                            for (c in listOf(m.baseColor.r, m.baseColor.g, m.baseColor.b, m.baseColor.a)) {
                                assertTrue(c in 0f..1f, "node '${node.name}': colour channel $c out of [0,1]")
                            }
                            assertTrue(m.roughness in 0f..1f, "node '${node.name}': roughness ${m.roughness} out of [0,1]")
                            assertTrue(m.metallic in 0f..1f, "node '${node.name}': metallic ${m.metallic} out of [0,1]")
                        }
                        for (mesh in node.meshes) {
                            meshes++
                            for (tri in mesh.triangles) {
                                for (v in listOf(tri.v0, tri.v1, tri.v2)) {
                                    assertTrue(v in mesh.positions.indices, "position index $v out of bounds in '${node.name}'")
                                }
                                for (n in listOf(tri.n0, tri.n1, tri.n2)) {
                                    assertTrue(
                                        n == -1 || n in mesh.normals.indices,
                                        "normal index $n out of bounds in '${node.name}'",
                                    )
                                }
                            }
                        }
                    }
                    assertTrue(meshes > 0 || scene.notes.isNotEmpty(), "a scene with no meshes and no notes explains nothing")
                    println("PROBE scene-export: $nodes nodes, $meshes meshes, all consumable as-is")
                },
            )
        }
    }

    /** True for a closed axis-aligned box: 8 unique positions that are exactly its bbox corners, 12 triangles. */
    private fun isBboxCornerBox(mesh: Mesh): Boolean {
        if (mesh.positions.size != 8 || mesh.triangles.size != 12) return false
        val xs = mesh.positions.map { it.x }.distinct().sorted()
        val ys = mesh.positions.map { it.y }.distinct().sorted()
        val zs = mesh.positions.map { it.z }.distinct().sorted()
        if (xs.size != 2 || ys.size != 2 || zs.size != 2) return false
        val corners = mesh.positions.map { Triple(it.x, it.y, it.z) }.toSet()
        return corners.size == 8
    }

    private fun bboxVolume(mesh: Mesh): Double {
        val xs = mesh.positions.map { it.x.toDouble() }
        val ys = mesh.positions.map { it.y.toDouble() }
        val zs = mesh.positions.map { it.z.toDouble() }
        return (xs.max() - xs.min()) * (ys.max() - ys.min()) * (zs.max() - zs.min())
    }

    /** Divergence-theorem signed volume: Σ v0·(v1×v2)/6 over all triangles of a closed mesh. */
    private fun signedVolume(mesh: Mesh): Double {
        var sum = 0.0
        for (t in mesh.triangles) {
            val a = mesh.positions[t.v0]
            val b = mesh.positions[t.v1]
            val c = mesh.positions[t.v2]
            sum += (
                a.x.toDouble() * (b.y.toDouble() * c.z - b.z.toDouble() * c.y) +
                    a.y.toDouble() * (b.z.toDouble() * c.x - b.x.toDouble() * c.z) +
                    a.z.toDouble() * (b.x.toDouble() * c.y - b.y.toDouble() * c.x)
            ) / 6.0
        }
        return sum
    }
}
