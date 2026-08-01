package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.write.writeJt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe for the per-shape scene split, driven through the **writer** rather than the reader.
 *
 * Splitting a merged tier into one node per body is only worth something if the fidelity it
 * recovers survives the seam it was recovered for. The delivered tests prove the split against
 * Layer 1 — triangle conservation and per-colour totals — which is the read half. This asks the
 * other half: author the split scene back out through `writeJt`, read it again, and require the
 * material partition to come back intact.
 *
 * That composition is where a scene model and a writer built against different assumptions show
 * it: a writer that flattens siblings, or drops one node's material, loses exactly what the
 * split just won, and no read-side assertion would notice.
 */
class SceneMaterialRoundTripProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local")).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
        }

    /** Triangle count per effective material, resolving inheritance down the tree. */
    private fun trianglesByMaterial(
        node: SceneNode,
        inherited: Material?,
        into: MutableMap<Material?, Int>,
    ) {
        val effective = node.material ?: inherited
        val triangles = node.meshes.firstOrNull()?.triangles?.size ?: 0
        if (triangles > 0) into[effective] = (into[effective] ?: 0) + triangles
        for (child in node.children) trianglesByMaterial(child, effective, into)
    }

    private fun profile(scene: Scene): Map<Material?, Int> =
        mutableMapOf<Material?, Int>().also { trianglesByMaterial(scene.root, null, it) }

    @TestFactory
    fun probes(): List<DynamicNode> =
        fixtures().map { fixture ->
            val bytes = fixture.readBytes()
            dynamicTest("${fixture.name}: the per-material triangle partition survives write → read") {
                val original = JtFile.parse(bytes).readScene()
                val before = profile(original)
                assumeTrue(before.isNotEmpty(), "no triangle geometry in this fixture's scene")
                // Only interesting where the split actually recovered something.
                assumeTrue(before.size > 1, "this fixture's scene uses a single material")

                val written =
                    try {
                        writeJt(original)
                    } catch (e: Throwable) {
                        throw AssertionError(
                            "the scene this library just read cannot be written back: ${e.message}",
                            e,
                        )
                    }
                val reread = JtFile.parse(written).readScene()
                val after = profile(reread)

                assertEquals(
                    before.values.sum(),
                    after.values.sum(),
                    "triangles were lost or duplicated across write → read",
                )
                assertEquals(
                    before.size,
                    after.size,
                    "the scene carried ${before.size} distinct materials in and ${after.size} came back",
                )
                // The partition itself, not merely its size: each material must still own the
                // same number of triangles.
                assertEquals(
                    before.values.sorted(),
                    after.values.sorted(),
                    "the per-material triangle partition changed shape across write → read",
                )
                val lostColours = before.keys.mapNotNull { it?.baseColor }.toSet() - after.keys.mapNotNull { it?.baseColor }.toSet()
                assertTrue(lostColours.isEmpty(), "colours did not survive the round trip: $lostColours")
            }
        }
}
