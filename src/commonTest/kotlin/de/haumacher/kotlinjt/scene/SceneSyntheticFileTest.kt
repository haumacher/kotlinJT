package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.TestFileAssembler
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.lsg.encodeLsgSegmentPayload
import de.haumacher.kotlinjt.testGuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end-to-end honesty pin on a complete synthetic file: [readScene] over the public API,
 * with a shape segment whose bytes do not decode — the affected part must appear in the
 * scene with a named note, never silently vanish. Also pins the no-LSG refusal.
 */
class SceneSyntheticFileTest {
    private val lsgId = testGuid(1)
    private val shapeId = testGuid(2)

    private fun syntheticFile(shapePayload: ByteArray): ByteArray {
        val order = Endianness.LITTLE_ENDIAN
        val version = JtVersion(10, 0)
        val builder = SceneLsgBuilder()
        builder.partition(0, listOf(1))
        builder.property(0, "JT_PROP_NAME", "synthetic.asm;0;1:")
        builder.metaDataNode(1, listOf(2))
        builder.property(1, "JT_PROP_MEASUREMENT_UNITS", "millimeters")
        builder.instance(2, 100)
        builder.property(2, "JT_PROP_NAME", "the part;0;1:")
        builder.part(100, listOf(101))
        builder.rangeLod(101, listOf(102))
        builder.groupNode(102, listOf(103))
        builder.triStripShape(103)
        builder.shapeSegment(103, shapeId)
        val lsgPayload = encodeLsgSegmentPayload(builder.build().encode(order), version, order)
        return TestFileAssembler(order, version, lsgId)
            .addSegment(lsgId, SegmentKind.LOGICAL_SCENE_GRAPH.code, lsgPayload.toByteArray())
            .addSegment(shapeId, SegmentKind.SHAPE_LOD0.code, shapePayload)
            .build()
    }

    @Test
    fun anUndecodableShapeSegmentYieldsTheNodeWithANoteNeverSilence() {
        // The shape segment holds bytes that are no element stream at all.
        val scene = readScene(syntheticFile(ByteArray(64) { 0x5A }))

        assertEquals("synthetic.asm", scene.root.name)
        assertEquals(LengthUnit.MILLIMETERS, scene.units)
        // The part did not vanish: its named node is present, without geometry.
        val part = scene.root.children.single()
        assertEquals("the part", part.name)
        assertEquals(0, part.meshes.size)
        // And the refusal is named, locating both the node and the segment.
        assertEquals(listOf("SCENE_GEOMETRY_UNAVAILABLE"), scene.notes.map { it.name })
        assertTrue("the part" in scene.notes[0].message)
    }

    @Test
    fun aFileWithoutAnLsgSegmentRefusesWithTheStructureNote() {
        val order = Endianness.LITTLE_ENDIAN
        val bytes =
            TestFileAssembler(order, JtVersion(10, 0), lsgId)
                .addSegment(shapeId, SegmentKind.SHAPE_LOD0.code, ByteArray(32))
                .build()
        val scene = readScene(bytes)
        assertEquals(LengthUnit.UNSPECIFIED, scene.units)
        assertEquals(emptyList(), scene.root.children)
        assertEquals(listOf("SCENE_STRUCTURE_UNAVAILABLE"), scene.notes.map { it.name })
    }
}
