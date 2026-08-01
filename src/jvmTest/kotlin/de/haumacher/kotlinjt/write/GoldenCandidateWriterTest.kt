package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.inventory
import de.haumacher.kotlinjt.scene.Color
import de.haumacher.kotlinjt.scene.LodPolicy
import de.haumacher.kotlinjt.scene.Mat4
import de.haumacher.kotlinjt.scene.Material
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.readScene
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Produces the **golden candidates**: files this library's writer authored, staged in
 * `golden-candidates/` for the external validation the fixture policy demands (the amendment on
 * issue #1 — writer output freezes as a committed golden only after JT2Go opens it). The
 * directory is gitignored except for its README; nothing here is a conformance claim, only a
 * candidate plus the proof that this library reads it back as the scene it wrote.
 */
class GoldenCandidateWriterTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun candidateDir(): File = File(repoRoot(), "golden-candidates").also { it.mkdirs() }

    private fun stage(
        name: String,
        scene: Scene,
        policy: LodPolicy = LodPolicy.ALL_LODS,
    ) {
        val bytes = writeJt(scene)
        val file = JtFile.parse(bytes)
        assertEquals(emptyList(), file.notes.map { it.name }, "$name: candidates parse cleanly")
        assertSceneEquivalent(scene, file.readScene(policy), name)
        File(candidateDir(), name).writeBytes(bytes)
        File(candidateDir(), "$name.inventory.txt").writeText(file.inventory())
    }

    @Test
    fun stagesTheSyntheticCandidates() {
        stage(
            "unit-cube.jt",
            millimeterScene(
                SceneNode(
                    "unit cube.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(part("cube.prt", unitCubeMesh(), Material(Color(0.7f, 0.7f, 0.7f, 1f), 0.35f, 0f))),
                ),
            ),
        )

        val bolt = part("bolt.prt", unitCubeMesh(), Material(Color(0.8f, 0.15f, 0.1f, 1f), 0.4f, 0f))
        val plate =
            SceneNode(
                "plate.prt",
                Mat4.IDENTITY,
                listOf(unitCubeMesh(), coarseCubeMesh()),
                listOf(testPolylines()),
                Material(Color(0.2f, 0.4f, 0.9f, 1f), 0.8f, 0f),
                emptyList(),
            )
        stage(
            "two-part-assembly.jt",
            millimeterScene(
                SceneNode(
                    "two part assembly.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(
                        SceneNode("bolt#1", translation(20.0, 0.0, 0.0), emptyList(), emptyList(), null, listOf(bolt)),
                        SceneNode("bolt#2", translation(0.0, 30.0, 5.0), emptyList(), emptyList(), null, listOf(bolt)),
                        plate,
                    ),
                ),
            ),
        )
    }

    @Test
    fun stagesTheFixtureRewrites() {
        val fixtures =
            listOf("fixtures", "fixtures-local")
                .flatMap { File(repoRoot(), it).listFiles().orEmpty().toList() }
                .filter { it.isFile && it.name.endsWith(".jt") }
        for (fixture in fixtures) {
            val base = fixture.name.removeSuffix(".jt")
            for (policy in listOf(LodPolicy.FINEST_ONLY, LodPolicy.ALL_LODS)) {
                val scene = JtFile.parse(fixture.readBytes()).readScene(policy)
                if (scene.notes.isNotEmpty()) continue
                val suffix = if (policy == LodPolicy.FINEST_ONLY) "finest" else "all-lods"
                stage("$base-rewrite-$suffix.jt", scene, policy)
            }
        }
    }
}
