package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// spec: §6 — the JT 10.5 wire deltas against the v10.0 reference, established from the
// NIST 10.5 fixture and recorded as DESIGN.md deltas 23–26. Hand-built byte sequences
// mirror the real producer's layouts; the fixture battery pins the real bytes.

/** The 10.5 generation deltas: layouts the v10.0 reference does not document. */
class Lsg105GenerationTest {
    private val v105 = LsgGeneration.V10_5

    @Test
    fun generationSelectionSplitsAtTenFive() {
        assertEquals(LsgGeneration.V9, LsgGeneration.of(JtVersion(8, 1)))
        assertEquals(LsgGeneration.V9, LsgGeneration.of(JtVersion(9, 5)))
        assertEquals(LsgGeneration.V10, LsgGeneration.of(JtVersion(10, 0)))
        assertEquals(LsgGeneration.V10, LsgGeneration.of(JtVersion(10, 2)))
        assertEquals(LsgGeneration.V10_5, LsgGeneration.of(JtVersion(10, 5)))
        assertEquals(LsgGeneration.V10_5, LsgGeneration.of(JtVersion(11, 0)))
    }

    // spec: Figure 46, Figure 47 — plus the 10.5 trailing I32 (delta 24)
    @Test
    fun materialAttributeCarriesTheTrailingField() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.MATERIAL_ATTRIBUTE, 3, 44) {
                    writeTestBaseAttributeData(v105, stateFlags = 8)
                    writeU8(1u) // version
                    writeU16(0x3980u) // data flags (the NIST value)
                    repeat(16) { writeF32(0.25f) } // four RGBA colours
                    writeF32(35f) // shininess
                    writeF32(0f) // reflectivity
                    writeF32(1f) // bumpiness
                    writeTestAttributeTail(v105)
                }
            val element = roundTripTyped(bytes, order, v105) as MaterialAttributeElement
            assertEquals(-1, element.baseAttribute.reservedTail)
            assertEquals(35f, element.shininess)
        }

    // spec: Figure 63 — plus the 10.5 trailing I32 (delta 24)
    @Test
    fun geometricTransformCarriesTheTrailingField() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.GEOMETRIC_TRANSFORM_ATTRIBUTE, 3, 45) {
                    writeTestBaseAttributeData(v105)
                    writeU8(1u) // version
                    writeU16(0x8000u) // stored values mask: element [0] only
                    writeF64(2.5)
                    writeTestAttributeTail(v105)
                }
            val element = roundTripTyped(bytes, order, v105) as GeometricTransformAttributeElement
            assertEquals(-1, element.baseAttribute.reservedTail)
            assertEquals(2.5, element.matrix.values[0])
            assertEquals(1.0, element.matrix.values[5], "unstored cells default to identity")
        }

    // spec: Figure 61 — plus the 10.5 trailing I32 (delta 24)
    @Test
    fun linestyleCarriesTheTrailingField() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.LINESTYLE_ATTRIBUTE, 3, 46) {
                    writeTestBaseAttributeData(v105)
                    writeU8(1u) // version
                    writeU8(0u) // data flags
                    writeF32(2f) // line width
                    writeTestAttributeTail(v105)
                }
            val element = roundTripTyped(bytes, order, v105) as LinestyleAttributeElement
            assertEquals(-1, element.baseAttribute.reservedTail)
            assertEquals(2f, element.lineWidth)
        }

    // spec: Figure 23 — the 10.5 version byte, and flags bit 0 set without the box (delta 23)
    @Test
    fun partitionBitZeroWithoutStoredBoxDecodes() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.PARTITION_NODE, 1, 0) {
                    writeTestGroupNodeData(v105, children = listOf(1))
                    writeU8(1u) // the 10.5 version number
                    writeI32(1) // flags: bit 0 set — yet no box stored (the NIST producer)
                    writeI32(0) // empty file name
                    repeat(6) { writeF32(2f) } // transformed bbox
                    writeF32(100f) // area
                    repeat(6) { writeI32(1) } // three count ranges
                }
            val element = roundTripTyped(bytes, order, v105) as PartitionNodeElement
            assertEquals(1, element.version)
            assertEquals(1, element.partitionFlags)
            assertNull(element.untransformedBBox)
        }
}
