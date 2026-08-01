package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.scene.LodPolicy
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.readScene
import de.haumacher.kotlinjt.shape.decodeShapeLod
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real-producer acceptance of the writer (issue #8): every discovered fixture — the
 * committed public tier under `fixtures/` and the IP-encumbered `fixtures-local/` tier — is
 * read as a scene, written back out by [writeJt], and read again. The rewritten file must be a
 * clean JT file by the Layer 0/1 standards (no notes, byte-identical re-serialization, every
 * shape body decoding typed with valid stored hashes) and must yield the same scene: same tree,
 * names, transforms, materials, units, and the same instancing.
 *
 * Fixtures are discovered, never named (the local tier carries customer part numbers). With no
 * fixture present the test skips visibly.
 */
class WriteFixtureRewriteTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf("fixtures", "fixtures-local")
            .flatMap { File(repoRoot(), it).listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.endsWith(".jt") }
            .sortedBy { it.name }

    // spec: §13.9 (the whole scene round trip) / §7 (authored shape bodies with valid hashes)
    @Test
    fun everyFixtureRewritesToTheSameScene() {
        val fixtures = fixtures()
        assumeTrue(fixtures.isNotEmpty(), "no JT fixtures discovered (0 files in fixtures/ and fixtures-local/)")
        for (fixture in fixtures) {
            for (policy in listOf(LodPolicy.FINEST_ONLY, LodPolicy.ALL_LODS)) {
                val label = "${fixture.name} [$policy]"
                val original = JtFile.parse(fixture.readBytes()).readScene(policy)
                assumeTrue(original.notes.isEmpty(), "$label: the fixture itself must read cleanly")

                val bytes = writeJt(original)
                val written = JtFile.parse(bytes)
                assertEquals(emptyList(), written.notes.map { it.name }, "$label: rewritten file must parse without notes")
                assertContentEquals(bytes, written.serialize(), "$label: rewritten file must re-serialize byte-identically")
                for (segment in written.shapeLodSegments()) {
                    val result = assertNotNull(written.decodeShapeLod(segment), "$label: shape segment decodes")
                    assertEquals(
                        emptyList(),
                        result.notes.map { it.name },
                        "$label: every authored shape body decodes typed (its stored hashes verify)",
                    )
                }
                assertSceneEquivalent(original, written.readScene(policy), label)
                assertEquals(
                    sharedPaths(original),
                    sharedPaths(written.readScene(policy)),
                    "$label: instanced subtrees must stay shared",
                )
            }
        }
    }

    // spec: Table 6 (one Shape LOD segment per shape per tier)
    @Test
    fun rewrittenInventoryMatchesTheSceneContent() {
        val fixtures = fixtures()
        assumeTrue(fixtures.isNotEmpty(), "no JT fixtures discovered (0 files in fixtures/ and fixtures-local/)")
        for (fixture in fixtures) {
            val scene = JtFile.parse(fixture.readBytes()).readScene(LodPolicy.ALL_LODS)
            assumeTrue(scene.notes.isEmpty(), "${fixture.name}: the fixture itself must read cleanly")
            val written = writeJtFile(scene)
            val geometryEntries = distinctNodes(scene).sumOf { it.meshes.size + it.polylines.size }
            assertEquals(
                1 + geometryEntries,
                written.segments.size,
                "${fixture.name}: one LSG segment plus one shape segment per mesh/polyline tier",
            )
            assertTrue(written.segments.first().kind?.code == 1)
        }
    }

    /** All distinct scene nodes (by identity — instanced subtrees are one node). */
    private fun distinctNodes(scene: Scene): List<SceneNode> {
        val seen = IdentityHashMap<SceneNode, Unit>()
        val out = mutableListOf<SceneNode>()

        fun walk(node: SceneNode) {
            if (seen.put(node, Unit) != null) return
            out.add(node)
            node.children.forEach(::walk)
        }
        walk(scene.root)
        return out
    }

    /**
     * The scene's sharing structure as path sets: every node reached by more than one path with
     * the paths that reach it. Two scenes with the same instancing have the same structure.
     */
    private fun sharedPaths(scene: Scene): List<List<String>> {
        val paths = IdentityHashMap<SceneNode, MutableList<String>>()

        fun walk(
            node: SceneNode,
            path: String,
        ) {
            val known = paths.getOrPut(node) { mutableListOf() }
            known.add(path)
            if (known.size > 1) return
            for ((index, child) in node.children.withIndex()) {
                walk(child, "$path${child.name.ifEmpty { "#$index" }}/")
            }
        }
        walk(scene.root, "/")
        return paths.values.filter { it.size > 1 }.map { it.sorted() }.sortedBy { it.first() }
    }
}
