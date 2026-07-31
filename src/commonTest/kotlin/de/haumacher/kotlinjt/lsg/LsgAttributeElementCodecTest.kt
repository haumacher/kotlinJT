package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.io.toBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// spec: §6.1.2 — Attribute Elements, Figures 46–68, against hand-built byte sequences.
// The material attribute additionally covers the fixture-verified JT 9 layout; all other
// attribute types decode typed in JT 10 only (JT 9 layouts not established, DESIGN.md).
class LsgAttributeElementCodecTest {
    private val v10 = LsgGeneration.V10

    // spec: Figure 46
    // spec: Figure 47
    @Test
    fun materialAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.MATERIAL_ATTRIBUTE, 3, 27) {
                    writeTestBaseAttributeData(v10, stateFlags = 8, inhibit = 3u, final = 1u)
                    writeU8(1u) // version
                    writeU16(0x39a0u) // data flags
                    repeat(4) { writeF32(0.1f) } // ambient
                    repeat(4) { writeF32(0.2f) } // diffuse + alpha
                    repeat(4) { writeF32(0.3f) } // specular
                    repeat(4) { writeF32(0.4f) } // emission
                    writeF32(64f) // shininess
                    writeF32(0.5f) // reflectivity
                    writeF32(1f) // bumpiness
                }
            val element = roundTripTyped(bytes, order, v10) as MaterialAttributeElement
            assertEquals(0x39a0, element.dataFlags)
            assertEquals(Rgba(f32(0.2f), f32(0.2f), f32(0.2f), f32(0.2f)), element.diffuseColourAndAlpha)
            assertEquals(64f, element.shininess)
            assertEquals(0.5f, element.reflectivity)
            assertEquals(1f, element.bumpiness)
            assertEquals(1u, element.baseAttribute.fieldFinalFlags)
        }

    // spec: §6.1.2.2 — the JT 9 material layout (no field-final flags, no bumpiness;
    // reflectivity from local version 2 on) — fixture-verified, DESIGN.md
    @Test
    fun materialAttributeElementV9() =
        forBothOrders { order ->
            for (version in 1..2) {
                val bytes =
                    lsgFrame(order, ObjectTypeIds.MATERIAL_ATTRIBUTE, 3, 27) {
                        writeTestBaseAttributeData(LsgGeneration.V9)
                        writeI16(version.toShort())
                        writeU16(0x39a0u)
                        repeat(16) { writeF32(0.25f) } // four RGBA colours
                        writeF32(0f) // shininess
                        if (version >= 2) writeF32(0.5f) // reflectivity
                    }
                val element = roundTripTyped(bytes, order, LsgGeneration.V9) as MaterialAttributeElement
                assertNull(element.baseAttribute.fieldFinalFlags)
                assertNull(element.bumpiness)
                assertEquals(if (version >= 2) 0.5f else null, element.reflectivity)
            }
        }

    // spec: Figure 48
    // spec: Figure 49
    // spec: Figure 50
    // spec: Figure 51
    @Test
    fun textureImageAttributeElementExternal() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.TEXTURE_IMAGE_ATTRIBUTE, 3, 30) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u) // version
                    writeI32(2) // texture type: 2D post-lit
                    // Texture Environment (Figure 50)
                    writeI32(0) // border mode
                    writeI32(2) // mipmap magnification filter
                    writeI32(4) // mipmap minification filter
                    writeI32(2) // s wrap
                    writeI32(2) // t wrap
                    writeI32(0) // r wrap
                    writeI32(2) // blend type
                    writeI32(0) // internal compression level
                    repeat(4) { writeF32(0f) } // blend colour
                    repeat(4) { writeF32(1f) } // border colour
                    repeat(16) { writeF32(if (it % 5 == 0) 1f else 0f) } // texture transform
                    // Texture Coord Generation Parameters (Figure 51)
                    repeat(4) { writeI32(0) } // gen modes
                    repeat(16) { writeF32(0f) } // four reference planes
                    writeI32(0) // texture channel
                    writeI32(0) // tex coord channel
                    writeU32(0u) // empty field
                    writeU8(0u) // inline image storage flag: external
                    writeI32(1) // image count
                    writeI32(7) // MbString "tex.png"
                    for (ch in "tex.png") writeU16(ch.code.toUShort())
                }
            val element = roundTripTyped(bytes, order, v10) as TextureImageAttributeElement
            assertEquals(2, element.textureData.textureType)
            assertEquals(listOf("tex.png"), element.textureData.externalStorageNames)
            assertEquals(4, element.textureData.environment.mipmapMinificationFilter)
        }

    // spec: Figure 52
    // spec: Figure 53
    @Test
    fun textureImageAttributeElementInline() =
        forBothOrders { order ->
            val texels = byteArrayOf(1, 2, 3, 4, 5, 6)
            val bytes =
                lsgFrame(order, ObjectTypeIds.TEXTURE_IMAGE_ATTRIBUTE, 3, 30) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u)
                    writeI32(2) // texture type
                    repeat(8) { writeI32(0) } // environment I32 fields
                    repeat(8) { writeF32(0f) } // blend + border colour
                    repeat(16) { writeF32(0f) } // texture transform
                    repeat(4) { writeI32(0) } // gen modes
                    repeat(16) { writeF32(0f) } // reference planes
                    writeI32(0) // texture channel
                    writeI32(0) // tex coord channel
                    writeU32(0u) // empty field
                    writeU8(1u) // inline image storage flag
                    writeI32(1) // image count
                    // Image Format Description (Figure 53)
                    writeU32(1u) // pixel format: RGB
                    writeU32(3u) // pixel data type: U8
                    writeI16(2) // dimensionality
                    writeI16(1) // row alignment
                    writeI16(2) // width
                    writeI16(1) // height
                    writeI16(1) // depth
                    writeI16(0) // border texels
                    writeU32(0u) // shared image flag
                    writeI16(1) // mipmaps count
                    // Inline Texture Image Data (Figure 52)
                    writeI32(texels.size) // total image data size
                    writeI32(texels.size) // mipmap image byte count
                    writeBytes(texels)
                }
            val element = roundTripTyped(bytes, order, v10) as TextureImageAttributeElement
            val image = element.textureData.inlineImages.single()
            assertEquals(2, image.format.width)
            assertEquals(texels.toBytes(), image.mipmapImages.single())
        }

    // spec: Figure 54
    @Test
    fun drawStyleAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.DRAW_STYLE_ATTRIBUTE, 3, 31) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u) // version
                    writeU8(0x0Bu) // data flags: cull + two-sided + lighting
                }
            val element = roundTripTyped(bytes, order, v10) as DrawStyleAttributeElement
            assertEquals(0x0B, element.dataFlags)
        }

    // spec: Figure 55
    @Test
    fun lightSetAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.LIGHT_SET_ATTRIBUTE, 3, 32) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u)
                    writeI32(2) // light count
                    writeI32(33)
                    writeI32(34)
                }
            val element = roundTripTyped(bytes, order, v10) as LightSetAttributeElement
            assertEquals(listOf(33, 34), element.lightObjectIds)
        }

    // spec: Figure 56
    // spec: Figure 57
    @Test
    fun infiniteLightAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE, 3, 33) {
                    writeTestBaseLightData(v10)
                    writeU8(1u) // version
                    writeF32(0f)
                    writeF32(0f)
                    writeF32(-1f) // direction
                }
            val element = roundTripTyped(bytes, order, v10) as InfiniteLightAttributeElement
            assertEquals(Vec3F32(0f, 0f, -1f), element.direction)
            assertEquals(2, element.baseLight.coordSystem)
            assertEquals(0.75f, element.baseLight.shadowOpacity)
        }

    // spec: Figure 58
    // spec: Figure 60
    @Test
    fun pointLightAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.POINT_LIGHT_ATTRIBUTE, 3, 34) {
                    writeTestBaseLightData(v10)
                    writeU8(1u) // version
                    repeat(4) { writeF32(2f) } // position (HCoordF32)
                    writeF32(1f) // constant attenuation (Figure 60)
                    writeF32(0.1f) // linear attenuation
                    writeF32(0.01f) // quadratic attenuation
                    writeF32(45f) // spread angle
                    writeF32(1f)
                    writeF32(0f)
                    writeF32(0f) // spot direction
                    writeI32(2) // spot intensity
                }
            val element = roundTripTyped(bytes, order, v10) as PointLightAttributeElement
            assertEquals(45f, element.spreadAngle)
            assertEquals(AttenuationCoefficients(f32(1f), f32(0.1f), f32(0.01f)), element.attenuation)
        }

    // spec: Figure 61
    @Test
    fun linestyleAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.LINESTYLE_ATTRIBUTE, 3, 35) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u)
                    writeU8(0x11u) // dash + antialiasing
                    writeF32(2f) // line width
                }
            val element = roundTripTyped(bytes, order, v10) as LinestyleAttributeElement
            assertEquals(0x11, element.dataFlags)
            assertEquals(2f, element.lineWidth)
        }

    // spec: Figure 62
    @Test
    fun pointstyleAttributeElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.POINTSTYLE_ATTRIBUTE, 3, 36) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u)
                    writeU8(0x10u) // antialiasing
                    writeF32(3f) // point size
                }
            val element = roundTripTyped(bytes, order, v10) as PointstyleAttributeElement
            assertEquals(3f, element.pointSize)
        }

    // spec: Figure 63
    @Test
    fun geometricTransformAttributeElement() =
        forBothOrders { order ->
            // Store translation (elements 12, 13, 14 → mask bits 3, 2, 1) and m00 (bit 15).
            val mask = 0x800E
            val bytes =
                lsgFrame(order, ObjectTypeIds.GEOMETRIC_TRANSFORM_ATTRIBUTE, 3, 37) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u)
                    writeU16(mask.toUShort())
                    writeF64(2.0) // m00
                    writeF64(10.0) // m30
                    writeF64(20.0) // m31
                    writeF64(30.0) // m32
                }
            val element = roundTripTyped(bytes, order, v10) as GeometricTransformAttributeElement
            assertEquals(mask, element.storedValuesMask)
            assertEquals(2.0, element.matrix.values[0])
            assertEquals(1.0, element.matrix.values[5]) // unstored: identity
            assertEquals(listOf(10.0, 20.0, 30.0), element.matrix.values.subList(12, 15))
        }

    // spec: Figure 64
    // spec: Figure 65
    @Test
    fun textureCoordinateGeneratorWithMappingPlane() =
        forBothOrders { order ->
            val nested =
                lsgFrame(order, ObjectTypeIds.MAPPING_PLANE, 3, 90) {
                    writeU8(1u) // version
                    repeat(16) { writeF64(it.toDouble()) } // mapping plane matrix
                    writeI32(2) // coordinate system
                }
            val bytes =
                lsgFrame(order, ObjectTypeIds.TEXTURE_COORDINATE_GENERATOR_ATTRIBUTE, 3, 38) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u)
                    writeI32(0) // texture coord channel
                    writeBytes(nested)
                }
            val element = roundTripTyped(bytes, order, v10) as TextureCoordinateGeneratorAttributeElement
            val plane = element.mappingSurface as MappingPlaneElement
            assertEquals(90, plane.objectId)
            assertEquals(2, plane.data.coordSystem)
        }

    // spec: Figure 66
    @Test
    fun mappingCylinderElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.MAPPING_CYLINDER, 3, 91) {
                    writeU8(1u)
                    repeat(16) { writeF64(1.5) }
                    writeI32(3)
                }
            val element = roundTripTyped(bytes, order, v10) as MappingCylinderElement
            assertEquals(3, element.data.coordSystem)
        }

    // spec: Figure 67
    @Test
    fun mappingSphereElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.MAPPING_SPHERE, 3, 92) {
                    writeU8(1u)
                    repeat(16) { writeF64(0.5) }
                    writeI32(0)
                }
            val element = roundTripTyped(bytes, order, v10) as MappingSphereElement
            assertEquals(0, element.data.coordSystem)
        }

    // spec: Figure 68
    @Test
    fun mappingTriPlanarElement() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.MAPPING_TRIPLANAR, 3, 93) {
                    writeU8(1u)
                    repeat(16) { writeF64(2.5) }
                    writeI32(1)
                }
            val element = roundTripTyped(bytes, order, v10) as MappingTriPlanarElement
            assertEquals(1, element.data.coordSystem)
        }

    // spec: Figure 59 has no byte layout (an illustrative light-cone drawing) — nothing to test.
}
