package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.PartNodeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.TriStripSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.scene.Color
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.Mat4
import de.haumacher.kotlinjt.scene.Material
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.Vec3
import de.haumacher.kotlinjt.scene.readScene
import de.haumacher.kotlinjt.shape.shapeLodSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scene whose **root node** carries the geometry (issue #16).
 *
 * `readScene` never produces such a scene — its collapse pushes a sole nameless part into the
 * partition, so geometry always lands below the root — which is why no fixture round-trip could
 * reach this. A caller assembling a scene in memory reaches it immediately, and that is the
 * shape the sibling project hands to [writeJt].
 *
 * The authored hierarchy is asserted, not merely the triangle count: 9.5 §9.8 (Figure 245) and
 * v10 §13 do not *mandate* a node hierarchy, but they name the convention translators follow and
 * warn that "some JT enabled applications may assume [it] exists" — Part Node → Range LOD Node →
 * Shape Node. The writer's half of the doctrine is to be strict, so it follows the convention
 * rather than hanging shapes off the Partition.
 */
class RootGeometryAuthoringTest {
    private fun oneTriangle(): Mesh =
        Mesh(
            positions = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
            normals = listOf(Vec3(0f, 0f, 1f)),
            triangles = listOf(Mesh.Triangle(0, 1, 2, 0, 0, 0)),
        )

    private fun rootGeometryScene(): Scene =
        Scene(
            units = LengthUnit.MILLIMETERS,
            root =
                SceneNode(
                    name = "lonely.part",
                    transform = Mat4.IDENTITY,
                    meshes = listOf(oneTriangle()),
                    polylines = emptyList(),
                    material = Material(Color(1f, 0f, 0f, 1f), roughness = 0.5f, metallic = 0f),
                    children = emptyList(),
                ),
            notes = emptyList(),
        )

    // spec: 9.5 §9.8 (Figure 245) — the part-structure convention
    @Test
    fun rootGeometryIsAuthoredUnderAPartNode() {
        val file = writeJtFile(rootGeometryScene())
        val lsg = file.decodeLsg()
        assertEquals(emptyList(), lsg?.notes?.map { it.name }, "the authored LSG does not decode cleanly")
        val graph = lsg!!.document.graphElements

        val partition = graph.filterIsInstance<PartitionNodeElement>().single()
        val parts = graph.filterIsInstance<PartNodeElement>()
        assertEquals(1, parts.size, "root geometry must produce exactly one Part Node")
        assertEquals(
            listOf(parts.single().objectId),
            partition.group.childNodeObjectIds,
            "the Partition's only child must be the Part Node, not a shape",
        )
        val lod = graph.filterIsInstance<RangeLodNodeElement>().single()
        assertEquals(
            listOf(lod.objectId),
            parts.single().metaData.group.childNodeObjectIds,
            "Figure 245: the Part Node's child is the Range LOD Node",
        )
        assertTrue(
            graph.filterIsInstance<TriStripSetShapeNodeElement>().isNotEmpty(),
            "no shape node was authored at all — the geometry was dropped",
        )
        assertTrue(
            file.shapeLodSegments().isNotEmpty(),
            "no shape LOD segment was written — the geometry was dropped",
        )
    }

    // spec: 9.5 §9.8 — and the nameless part must collapse back on read
    @Test
    fun rootGeometrySurvivesWriteThenRead() {
        val scene = rootGeometryScene()
        val reread = JtFile.parse(writeJt(scene)).readScene()

        assertEquals(emptyList(), reread.notes.map { it.name }, "reading back the authored file is not silent")
        assertEquals(LengthUnit.MILLIMETERS, reread.units)
        // The nameless Part is absorbed by the read collapse, so the geometry comes back on the
        // root exactly as it went in.
        assertEquals("lonely.part", reread.root.name)
        assertEquals(1, reread.root.meshes.size, "the mesh did not come back on the root")
        assertEquals(
            scene.root.meshes.single().triangles.size,
            reread.root.meshes.single().triangles.size,
            "triangle count changed across write → read",
        )
        assertEquals(
            scene.root.material?.baseColor,
            reread.root.material?.baseColor,
            "the root's material did not survive",
        )
    }

    // The declared metadata must describe what was actually authored, not what was intended.
    @Test
    fun theDeclaredCountsMatchTheAuthoredGeometry() {
        val file = writeJtFile(rootGeometryScene())
        val partition = file.decodeLsg()!!.document.graphElements.filterIsInstance<PartitionNodeElement>().single()
        assertTrue(
            partition.polygonCountRange.max >= 1,
            "the partition declares ${partition.polygonCountRange} polygons for one authored triangle",
        )
        assertTrue(
            partition.vertexCountRange.max >= 3,
            "the partition declares ${partition.vertexCountRange} vertices for one authored triangle",
        )
    }
}
