package de.haumacher.kotlinjt.lsg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// spec: §6.1.1 — Node Elements, Figures 21–45, decoded and re-serialized against hand-built
// byte sequences in both byte orders and — where the JT 9 layout is established against the
// real fixture (DESIGN.md) — in both format generations.
class LsgNodeElementCodecTest {
    // spec: Figure 21
    // spec: Figure 22
    @Test
    fun baseNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.BASE_NODE, 0, 7) {
                        writeTestBaseNodeData(generation, version = 1, flags = 1u, attributeIds = listOf(40, 41))
                    }
                val element = roundTripTyped(bytes, order, generation) as BaseNodeElement
                assertEquals(7, element.objectId)
                assertEquals(1u, element.baseNode.nodeFlags)
                assertEquals(listOf(40, 41), element.baseNode.attributeObjectIds)
            }
        }

    // spec: Figure 23
    // spec: Figure 24
    @Test
    fun partitionNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.PARTITION_NODE, 1, 0) {
                        writeTestGroupNodeData(generation, children = listOf(38))
                        // 10.5 inserts a version number here (DESIGN.md delta 23).
                        if (generation == LsgGeneration.V10_5) writeU8(1u)
                        writeI32(1) // partition flags: untransformed box present
                        writeI32(4) // MbString "part"
                        for (ch in "part") writeU16(ch.code.toUShort())
                        repeat(6) { writeF32(2f) } // transformed bbox
                        writeF32(100f) // area
                        writeI32(10)
                        writeI32(20) // vertex count range (Figure 24)
                        writeI32(1)
                        writeI32(2) // node count range
                        writeI32(3)
                        writeI32(4) // polygon count range
                        repeat(6) { writeF32(5f) } // untransformed bbox
                    }
                val element = roundTripTyped(bytes, order, generation) as PartitionNodeElement
                assertEquals("part", element.fileName)
                assertEquals(CountRange(10, 20), element.vertexCountRange)
                assertNotNull(element.untransformedBBox)
            }
        }

    // spec: Figure 23
    @Test
    fun partitionNodeWithoutUntransformedBox() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.PARTITION_NODE, 1, 0) {
                    writeTestGroupNodeData(LsgGeneration.V10)
                    writeI32(0) // partition flags: bit 0 off
                    writeI32(0) // empty file name
                    repeat(6) { writeF32(2f) }
                    writeF32(100f)
                    repeat(6) { writeI32(1) }
                }
            val element = roundTripTyped(bytes, order, LsgGeneration.V10) as PartitionNodeElement
            assertNull(element.untransformedBBox)
        }

    // 9.5 Figure 14 (p.36, read from the rendered page) puts `BBoxF32 : Reserved Field` on the
    // main path and reaches `BBoxF32 : Transformed BBox` only through the branch guarded
    // `(Partition Flags & 0x00000001) == 0`. Exactly one box is on the wire either way, so the
    // byte count matches v10 in both branches and only the *identity* differs — the reason this
    // went unnoticed. The middle box here is deliberately the empty-box sentinel a real 9.5
    // producer writes into the reserved slot: whichever field claims it must not be an extent.
    // spec: 9.5 Figure 14
    @Test
    fun partitionNodeMiddleBoxIsReservedInJt9WhenBitZeroIsSet() =
        forBothOrders { order ->
            fun frame(
                flags: Int,
                generation: LsgGeneration = LsgGeneration.V9,
            ) = lsgFrame(order, ObjectTypeIds.PARTITION_NODE, 1, 0) {
                writeTestGroupNodeData(generation)
                writeI32(flags)
                writeI32(0) // empty file name
                repeat(3) { writeF32(Float.MAX_VALUE) }
                repeat(3) { writeF32(-Float.MAX_VALUE) } // the empty-box sentinel
                writeF32(100f)
                repeat(6) { writeI32(1) } // three count ranges
                if (flags and 1 != 0) {
                    writeF32(-1f)
                    writeF32(-2f)
                    writeF32(-3f)
                    writeF32(1f)
                    writeF32(2f)
                    writeF32(3f) // untransformed bbox: the real extent
                }
            }

            val big = f32(Float.MAX_VALUE)
            val sentinel = BBoxF32(Vec3F32(big, big, big), Vec3F32(-big, -big, -big))
            val extent = BBoxF32(Vec3F32(-1f, -2f, -3f), Vec3F32(1f, 2f, 3f))

            // Bit 0 set: the middle box is the Reserved Field, and the declared extent is the
            // trailing untransformed box — never the sentinel.
            val withBit = roundTripTyped(frame(1), order, LsgGeneration.V9) as PartitionNodeElement
            assertEquals(sentinel, withBit.reservedBBox)
            assertNull(withBit.transformedBBox)
            assertEquals(extent, withBit.untransformedBBox)
            assertEquals(extent, withBit.extentBBox)

            // Bit 0 clear: the branch is taken, the same bytes are the Transformed BBox, and
            // no untransformed box follows.
            val withoutBit = roundTripTyped(frame(0), order, LsgGeneration.V9) as PartitionNodeElement
            assertNull(withoutBit.reservedBBox)
            assertEquals(sentinel, withoutBit.transformedBBox)
            assertNull(withoutBit.untransformedBBox)

            // v10 Figure 23 has no reserved field: the same bytes are the transformed box even
            // with bit 0 set.
            val v10 =
                roundTripTyped(frame(1, LsgGeneration.V10), order, LsgGeneration.V10) as PartitionNodeElement
            assertNull(v10.reservedBBox)
            assertEquals(sentinel, v10.transformedBBox)
        }

    // spec: Figure 25
    // spec: Figure 26
    @Test
    fun groupNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.GROUP_NODE, 1, 41) {
                        writeTestGroupNodeData(generation, children = listOf(1, 2, 3))
                    }
                val element = roundTripTyped(bytes, order, generation) as GroupNodeElement
                assertEquals(listOf(1, 2, 3), element.group.childNodeObjectIds)
            }
        }

    // spec: Figure 27
    @Test
    fun instanceNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.INSTANCE_NODE, 0, 26) {
                        writeTestBaseNodeData(generation)
                        writeTestVersionNumber(generation)
                        writeI32(2) // child node object id
                    }
                val element = roundTripTyped(bytes, order, generation) as InstanceNodeElement
                assertEquals(2, element.childNodeObjectId)
            }
        }

    // spec: Figure 28
    @Test
    fun partNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.PART_NODE, 1, 4) {
                        writeTestGroupNodeData(generation, children = listOf(39))
                        writeTestVersionNumber(generation) // meta data node data version
                        writeTestVersionNumber(generation) // part node version
                        writeI32(0) // empty field
                    }
                val element = roundTripTyped(bytes, order, generation) as PartNodeElement
                assertEquals(listOf(39), element.metaData.group.childNodeObjectIds)
                assertEquals(0, element.emptyField)
            }
        }

    // spec: Figure 29
    // spec: Figure 30
    @Test
    fun metaDataNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.META_DATA_NODE, 1, 38) {
                        writeTestGroupNodeData(generation, children = listOf(26))
                        writeTestVersionNumber(generation)
                    }
                val element = roundTripTyped(bytes, order, generation) as MetaDataNodeElement
                assertEquals(listOf(26), element.metaData.group.childNodeObjectIds)
            }
        }

    // spec: Figure 31
    // spec: Figure 32
    @Test
    fun lodNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.LOD_NODE, 1, 5) {
                        writeTestGroupNodeData(generation, children = listOf(6, 7))
                        writeTestVersionNumber(generation)
                        if (generation == LsgGeneration.V9) {
                            // The reserved VecF32 + I32 fields JT 10 dropped (DESIGN.md).
                            writeI32(1)
                            writeF32(0.5f)
                            writeI32(0)
                        }
                    }
                val element = roundTripTyped(bytes, order, generation) as LodNodeElement
                assertEquals(listOf(6, 7), element.lod.group.childNodeObjectIds)
            }
        }

    // spec: Figure 33
    @Test
    fun rangeLodNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.RANGE_LOD_NODE, 1, 39) {
                        writeTestGroupNodeData(generation, children = listOf(41))
                        writeTestVersionNumber(generation) // LOD node data version
                        if (generation == LsgGeneration.V9) {
                            writeI32(0) // reserved VecF32 (empty)
                            writeI32(0) // reserved field
                        }
                        writeTestVersionNumber(generation) // range LOD version
                        writeI32(2) // range limits VecF32
                        writeF32(10f)
                        writeF32(100f)
                        writeF32(1f)
                        writeF32(2f)
                        writeF32(3f) // centre
                    }
                val element = roundTripTyped(bytes, order, generation) as RangeLodNodeElement
                assertEquals(listOf(10f, 100f), element.rangeLimits)
                assertEquals(Vec3F32(1f, 2f, 3f), element.centre)
            }
        }

    // spec: Figure 34
    @Test
    fun switchNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.SWITCH_NODE, 1, 9) {
                        writeTestGroupNodeData(generation, children = listOf(1, 2))
                        writeTestVersionNumber(generation)
                        writeI32(1) // selected child (U32 on the wire)
                    }
                val element = roundTripTyped(bytes, order, generation) as SwitchNodeElement
                assertEquals(1, element.selectedChild)
            }
        }

    // spec: Figure 35
    // spec: Figure 36
    @Test
    fun baseShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.BASE_SHAPE_NODE, 2, 11) {
                        writeTestBaseShapeData(generation)
                    }
                val element = roundTripTyped(bytes, order, generation) as BaseShapeNodeElement
                assertEquals(42.5f, element.shape.area)
                assertEquals(CountRange(10, 20), element.shape.vertexCountRange)
                assertEquals(BBoxF32(Vec3F32(-1f, -2f, -3f), Vec3F32(1f, 2f, 3f)), element.shape.untransformedBBox)
                assertEquals(generation == LsgGeneration.V9, element.shape.reservedBBox != null)
            }
        }

    // spec: Figure 38
    // spec: Figure 39
    @Test
    fun vertexShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.VERTEX_SHAPE_NODE, 2, 12) {
                        writeTestVertexShapeData(generation)
                    }
                val element = roundTripTyped(bytes, order, generation) as VertexShapeNodeElement
                assertEquals(0x3uL, element.vertexShape.vertexBindings)
                if (generation == LsgGeneration.V9) {
                    assertEquals(QuantizationParameters(12, 3, 10, 8), element.vertexShape.quantizationParameters)
                    assertEquals(0x3uL, element.vertexShape.vertexBindings2)
                } else {
                    assertNull(element.vertexShape.quantizationParameters)
                    assertNull(element.vertexShape.vertexBindings2)
                }
            }
        }

    // spec: §6.1.1.10.3 — Tri-Strip Set Shape Node Element (Vertex Shape Data only)
    @Test
    fun triStripSetShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE, 2, 40) {
                        writeTestVertexShapeData(generation)
                    }
                val element = roundTripTyped(bytes, order, generation) as TriStripSetShapeNodeElement
                assertEquals(40, element.objectId)
            }
        }

    // spec: Figure 40
    // spec: 9.5 Figure 33
    @Test
    fun polylineSetShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val v9 = generation == LsgGeneration.V9
                val bytes =
                    lsgFrame(order, ObjectTypeIds.POLYLINE_SET_SHAPE_NODE, 2, 13) {
                        writeTestVertexShapeData(generation)
                        writeTestVersionNumber(generation)
                        writeF32(0.5f) // area factor
                        // 9.5 Figure 33 ends the element with a guarded U64; v10 Figure 40 does not.
                        if (v9) writeU64(0x7uL)
                    }
                val element = roundTripTyped(bytes, order, generation) as PolylineSetShapeNodeElement
                assertEquals(0.5f, element.areaFactor)
                assertEquals(if (v9) 0x7uL else null, element.vertexBindings)
            }
        }

    // spec: Figure 41
    // spec: 9.5 Figure 34
    @Test
    fun pointSetShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.POINT_SET_SHAPE_NODE, 2, 14) {
                        writeTestVertexShapeData(generation)
                        writeTestVersionNumber(generation)
                        writeF32(1f) // area factor
                        // Both documents draw the guarded U64 here, so every generation has it.
                        writeU64(0x5uL)
                    }
                val element = roundTripTyped(bytes, order, generation) as PointSetShapeNodeElement
                assertEquals(0x5uL, element.vertexBindings)
            }
        }

    // spec: Figure 42
    @Test
    fun polygonSetShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.POLYGON_SET_SHAPE_NODE, 2, 15) {
                        writeTestVertexShapeData(generation)
                    }
                val element = roundTripTyped(bytes, order, generation) as PolygonSetShapeNodeElement
                assertEquals(15, element.objectId)
            }
        }

    // spec: Figure 43
    @Test
    fun nullShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.NULL_SHAPE_NODE, 2, 16) {
                        writeTestBaseShapeData(generation)
                        writeTestVersionNumber(generation)
                    }
                val element = roundTripTyped(bytes, order, generation) as NullShapeNodeElement
                assertEquals(16, element.objectId)
            }
        }

    // spec: Figure 44
    // spec: Figure 45
    // spec: 9.5 Figure 37
    // spec: 9.5 Figure 38
    @Test
    fun primitiveSetShapeNodeElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val v9 = generation == LsgGeneration.V9
                val bytes =
                    lsgFrame(order, ObjectTypeIds.PRIMITIVE_SET_SHAPE_NODE, 2, 17) {
                        writeTestBaseShapeData(generation)
                        writeTestVersionNumber(generation)
                        // 9.5 Figure 37 splits the eight binding bytes into two I32 fields;
                        // v10 Figure 44 fuses them into one U64 at the same offset.
                        if (v9) {
                            writeI32(1) // texture coord binding: per vertex
                            writeI32(0) // colour binding: none
                        } else {
                            writeU64(0x1uL) // vertex bindings
                        }
                        writeI32(1) // tex coord gen type: isotropic
                        writeTestVersionNumber(generation)
                        writeU8(16u) // bits per vertex (9.5 Figure 38 / v10 Figure 45)
                        writeU8(8u) // bits per colour
                    }
                val element = roundTripTyped(bytes, order, generation) as PrimitiveSetShapeNodeElement
                assertEquals(1, element.texCoordGenType)
                assertEquals(PrimitiveSetQuantizationParameters(16, 8), element.quantization)
                if (v9) {
                    assertNull(element.vertexBindings, "JT 9 has no fused U64")
                    assertEquals(1, element.textureCoordBinding)
                    assertEquals(0, element.colourBinding)
                } else {
                    assertEquals(0x1uL, element.vertexBindings)
                    assertNull(element.textureCoordBinding, "JT 10 has no split bindings")
                    assertNull(element.colourBinding)
                }
            }
        }
}
