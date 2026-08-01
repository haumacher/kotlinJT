package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.io.Guid
import kotlin.test.Test
import kotlin.test.assertEquals

// spec: §6.2 — Property Atom Elements, Figures 69–77, both byte orders, both generations
// (the atom family's JT 9 layout — I16 version numbers — is fixture-verified, DESIGN.md).
class LsgPropertyCodecTest {
    // spec: Figure 69
    // spec: Figure 70
    @Test
    fun basePropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.BASE_PROPERTY_ATOM, 5, 50) {
                        writeTestBasePropertyAtomData(generation, stateFlags = 3u)
                    }
                val element = roundTripTyped(bytes, order, generation) as BasePropertyAtomElement
                assertEquals(3u, element.baseAtom.stateFlags)
            }
        }

    // spec: Figure 71
    @Test
    fun stringPropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.STRING_PROPERTY_ATOM, 5, 51) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeI32(5) // MbString "JT_äö"
                        for (ch in "JT_äö") writeU16(ch.code.toUShort())
                    }
                val element = roundTripTyped(bytes, order, generation) as StringPropertyAtomElement
                assertEquals("JT_äö", element.value)
            }
        }

    // spec: Figure 72
    @Test
    fun integerPropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.INTEGER_PROPERTY_ATOM, 5, 52) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeI32(-42)
                    }
                val element = roundTripTyped(bytes, order, generation) as IntegerPropertyAtomElement
                assertEquals(-42, element.value)
            }
        }

    // spec: Figure 73
    @Test
    fun floatingPointPropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.FLOATING_POINT_PROPERTY_ATOM, 5, 53) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeF32(2.5f)
                    }
                val element = roundTripTyped(bytes, order, generation) as FloatingPointPropertyAtomElement
                assertEquals(2.5f, element.value)
            }
        }

    // spec: Figure 74
    @Test
    fun jtObjectReferencePropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.JT_OBJECT_REFERENCE_PROPERTY_ATOM, 6, 54) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeI32(77)
                    }
                val element = roundTripTyped(bytes, order, generation) as JtObjectReferencePropertyAtomElement
                assertEquals(77, element.referencedObjectId)
            }
        }

    // spec: Figure 75
    @Test
    fun datePropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.DATE_PROPERTY_ATOM, 5, 55) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeI16(2020)
                        writeI16(10)
                        writeI16(8)
                        writeI16(15)
                        writeI16(50)
                        writeI16(8)
                        // 10.5 appends an F32 (observed −4.0 — DESIGN.md delta 26).
                        if (generation == LsgGeneration.V10_5) writeF32(-4f)
                    }
                val element = roundTripTyped(bytes, order, generation) as DatePropertyAtomElement
                assertEquals(2020, element.year)
                assertEquals(10, element.month)
                assertEquals(8, element.second)
                assertEquals(if (generation == LsgGeneration.V10_5) -4f else null, element.trailingField)
            }
        }

    // spec: Figure 76
    @Test
    fun lateLoadedPropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val segmentId = de.haumacher.kotlinjt.testGuid(0x1234, 5, 6, 7)
                val bytes =
                    lsgFrame(order, ObjectTypeIds.LATE_LOADED_PROPERTY_ATOM, 8, 56) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeGuid(segmentId)
                        writeI32(7) // segment type: Shape LOD0
                        writeI32(79) // payload object id
                        // 10.5 drops the reserved field (DESIGN.md delta 25).
                        if (generation != LsgGeneration.V10_5) writeI32(1) // reserved
                    }
                val element = roundTripTyped(bytes, order, generation) as LateLoadedPropertyAtomElement
                assertEquals(segmentId, element.segmentId)
                assertEquals(7, element.segmentType)
                assertEquals(79, element.payloadObjectId)
                assertEquals(if (generation == LsgGeneration.V10_5) null else 1, element.reserved)
            }
        }

    // spec: Figure 77
    @Test
    fun vector4fPropertyAtomElement() =
        forBothOrders { order ->
            for (generation in LsgGeneration.entries) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.VECTOR4F_PROPERTY_ATOM, 5, 57) {
                        writeTestBasePropertyAtomData(generation)
                        writeTestVersionNumber(generation)
                        writeF32(1f)
                        writeF32(2f)
                        writeF32(3f)
                        writeF32(4f)
                    }
                val element = roundTripTyped(bytes, order, generation) as Vector4fPropertyAtomElement
                assertEquals(Vec4F32(1f, 2f, 3f, 4f), element.value)
            }
        }

    // spec: §A — the Annex A table resolves every §6 element type GUID by name
    @Test
    fun annexAResolvesLsgTypes() {
        assertEquals("Partition Node Element", ObjectTypeIds.nameOf(ObjectTypeIds.PARTITION_NODE))
        assertEquals("Tri-Strip Set Shape Node Element", ObjectTypeIds.nameOf(ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE))
        assertEquals("Material Attribute Element", ObjectTypeIds.nameOf(ObjectTypeIds.MATERIAL_ATTRIBUTE))
        assertEquals("Late Loaded Property Atom Element", ObjectTypeIds.nameOf(ObjectTypeIds.LATE_LOADED_PROPERTY_ATOM))
        assertEquals("Tri-Strip Set Shape LOD Element", ObjectTypeIds.nameOf(ObjectTypeIds.TRI_STRIP_SET_SHAPE_LOD_ELEMENT))
        assertEquals("XT B-Rep Element", ObjectTypeIds.nameOf(ObjectTypeIds.XT_BREP_ELEMENT))
        assertEquals(null, ObjectTypeIds.nameOf(de.haumacher.kotlinjt.testGuid(1)))
        assertEquals("End-Of-Elements", ObjectTypeIds.nameOf(Guid.END_OF_ELEMENTS))
        // Every LSG codec is reachable from the table, and dispatches to its own type id.
        for ((guid, _) in ObjectTypeIds.all) {
            val codec = LsgElementCodecs.byTypeId(guid) ?: continue
            assertEquals(guid, codec.typeId)
        }
    }

    // spec: §A — every typed element type declared by the model dispatches through Annex A
    @Test
    fun annexACoversAllLsgCodecs() {
        val lsgTypeIds =
            listOf(
                ObjectTypeIds.BASE_NODE, ObjectTypeIds.GROUP_NODE, ObjectTypeIds.INSTANCE_NODE,
                ObjectTypeIds.LOD_NODE, ObjectTypeIds.META_DATA_NODE, ObjectTypeIds.NULL_SHAPE_NODE,
                ObjectTypeIds.PART_NODE, ObjectTypeIds.PARTITION_NODE, ObjectTypeIds.RANGE_LOD_NODE,
                ObjectTypeIds.SWITCH_NODE, ObjectTypeIds.BASE_SHAPE_NODE, ObjectTypeIds.POINT_SET_SHAPE_NODE,
                ObjectTypeIds.POLYGON_SET_SHAPE_NODE, ObjectTypeIds.POLYLINE_SET_SHAPE_NODE,
                ObjectTypeIds.PRIMITIVE_SET_SHAPE_NODE, ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE,
                ObjectTypeIds.VERTEX_SHAPE_NODE, ObjectTypeIds.DRAW_STYLE_ATTRIBUTE,
                ObjectTypeIds.GEOMETRIC_TRANSFORM_ATTRIBUTE, ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE,
                ObjectTypeIds.LIGHT_SET_ATTRIBUTE, ObjectTypeIds.LINESTYLE_ATTRIBUTE,
                ObjectTypeIds.MATERIAL_ATTRIBUTE, ObjectTypeIds.POINT_LIGHT_ATTRIBUTE,
                ObjectTypeIds.POINTSTYLE_ATTRIBUTE, ObjectTypeIds.TEXTURE_IMAGE_ATTRIBUTE,
                ObjectTypeIds.TEXTURE_COORDINATE_GENERATOR_ATTRIBUTE, ObjectTypeIds.MAPPING_PLANE,
                ObjectTypeIds.MAPPING_CYLINDER, ObjectTypeIds.MAPPING_SPHERE, ObjectTypeIds.MAPPING_TRIPLANAR,
                ObjectTypeIds.BASE_PROPERTY_ATOM, ObjectTypeIds.DATE_PROPERTY_ATOM,
                ObjectTypeIds.INTEGER_PROPERTY_ATOM, ObjectTypeIds.FLOATING_POINT_PROPERTY_ATOM,
                ObjectTypeIds.LATE_LOADED_PROPERTY_ATOM, ObjectTypeIds.JT_OBJECT_REFERENCE_PROPERTY_ATOM,
                ObjectTypeIds.STRING_PROPERTY_ATOM, ObjectTypeIds.VECTOR4F_PROPERTY_ATOM,
            )
        for (typeId in lsgTypeIds) {
            assertEquals(typeId, LsgElementCodecs.byTypeId(typeId)?.typeId, "codec missing for ${ObjectTypeIds.nameOf(typeId)}")
        }
    }
}
