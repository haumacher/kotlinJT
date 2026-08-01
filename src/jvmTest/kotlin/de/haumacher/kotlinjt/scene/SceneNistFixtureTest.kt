package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.JtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The issue #7 acceptance, pinned exactly on the committed NIST 10.5 fixture (the public
 * real-producer spine — this file may be named in code, unlike the local tier): 13 parts,
 * 8 tri-strip parts with 3 strictly descending LOD meshes, 5 polyline-only parts with 3
 * polyline sets, shared instances, explicit millimeter units, and a note-free scene.
 */
class SceneNistFixtureTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun scene(): Scene? {
        val fixture = File(repoRoot(), "fixtures/nist-mtc-crada-assembly.jt")
        if (!fixture.isFile) return null
        return JtFile.parse(fixture.readBytes()).readScene()
    }

    // spec: §13.9 (scene graph construction over the whole fixture); §13.8 (names, units)
    @Test
    fun nistAssemblyReadsAsTheExpectedScene() {
        val scene = scene()
        assumeTrue(scene != null, "committed NIST fixture not present")
        scene!!

        assertEquals(LengthUnit.MILLIMETERS, scene.units, "NX declares JT_PROP_MEASUREMENT_UNITS = Millimeters")
        assertEquals(emptyList<String>(), scene.notes.map { it.name }, "a fully decodable file yields a note-free scene")
        assertEquals("NIST mtc crada assembly.asm", scene.root.name)
        assertEquals(38, scene.root.children.size)

        val unique = mutableListOf<SceneNode>()
        val seen = IdentityHashMap<SceneNode, Unit>()

        fun walk(node: SceneNode) {
            if (seen.put(node, Unit) != null) return
            unique.add(node)
            node.children.forEach(::walk)
        }
        walk(scene.root)

        // 13 parts: 8 tri-strip parts x 3 LOD meshes, 5 polyline-only parts x 3 LOD sets.
        val meshParts = unique.filter { it.meshes.isNotEmpty() }
        val polylineParts = unique.filter { it.polylines.isNotEmpty() }
        assertEquals(8, meshParts.size)
        assertEquals(5, polylineParts.size)
        assertTrue(meshParts.all { it.meshes.size == 3 }, "every tri-strip part has 3 LOD tiers")
        assertTrue(polylineParts.all { it.polylines.size == 3 }, "every polyline part has 3 LOD tiers")
        for (part in meshParts + polylineParts) {
            assertTrue(part.name.isNotEmpty(), "every part is named")
        }
        for (part in meshParts) {
            assertTrue(
                part.meshes[0].triangles.size > part.meshes[1].triangles.size &&
                    part.meshes[1].triangles.size > part.meshes[2].triangles.size,
                "${part.name}: triangle counts must descend strictly across the tiers",
            )
        }

        // The hex nut, pinned to the digit (24 unique meshes overall, instances shared).
        val hexNutInstances = scene.root.children.filter { it.name == "90591A141 HEX NUT.asm" }
        assertEquals(10, hexNutInstances.size)
        val nutParts = hexNutInstances.map { it.children.single() }
        assertEquals(listOf(4010, 2056, 1212), nutParts[0].meshes.map { it.triangles.size })
        for (other in nutParts.drop(1)) {
            assertSame(nutParts[0], other, "instances of one part share the same scene object")
        }
        assertTrue(hexNutInstances.all { it.transform != Mat4.IDENTITY }, "each instance carries its own placement")
        assertEquals(24, unique.flatMap { it.meshes }.size, "8 parts x 3 tiers, shared instances counted once")
        assertEquals(15, unique.flatMap { it.polylines }.size)

        // The recorded material mapping on the nut: diffuse grey, shininess 15.
        val material = nutParts[0].material
        assertNotNull(material)
        material!!
        assertTrue(abs(material.baseColor.r - 0.69411767f) < 1e-6f)
        assertEquals(1f, material.baseColor.a)
        assertTrue(abs(material.roughness - sqrt(2.0 / 17.0).toFloat()) < 1e-6f, "roughness = sqrt(2/(2+shininess))")
        assertEquals(0f, material.metallic)
    }
}
