package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Endianness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The JT 9 attribute layouts that differ from v10 in *meaning* rather than in length — the
 * class of defect no byte count can catch (`docs/spec95-analysis/C-lsg-attributes.md`,
 * findings F2, F7 and F9).
 *
 * Every frame here is hand-built: no fixture in the corpus carries a light, a texture or a
 * geometric transform, so the assertions are about what the *figures* say and what the model
 * therefore has to remember. They are written so that reading a JT 9 body with the v10 field
 * types fails them — a length check alone would not.
 */
class Lsg95AttributeLayoutTest {
    private val v9 = LsgGeneration.V9
    private val v10 = LsgGeneration.V10

    // --- 9.5 Figures 53–56: Shadow Parameters moved between the generations (F9) ---

    // 9.5 Figure 54 (p.84) ends Base Light Data at `F32 : Shadow Opacity`; the alpha-factor
    // pair v10 Figure 57 keeps there is a separate collection (9.5 Figure 55) hanging off the
    // *element*, behind the branch guarded `Version Number == 2`. A version-1 light therefore
    // has no alpha factors at all, and must not gain a pair when it is written back.
    // spec: 9.5 Figure 53
    // spec: 9.5 Figure 54
    // spec: 9.5 Figure 55
    @Test
    fun infiniteLightV9CarriesShadowParametersOnTheElementFromVersionTwo() =
        forBothOrders { order ->
            fun frame(
                version: Int,
                shadow: Boolean,
            ) = lsgFrame(order, ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE, 3, 33) {
                writeTestBaseLightData(v9)
                writeI16(version.toShort())
                writeF32(0f)
                writeF32(0f)
                writeF32(-1f) // DirF32 : Direction
                if (shadow) {
                    writeF32(0.25f) // Non-shadow Alpha Factor (9.5 Figure 55)
                    writeF32(0.75f) // Shadow Alpha Factor
                }
            }

            val v1 = roundTripTyped(frame(1, shadow = false), order, v9) as InfiniteLightAttributeElement
            assertEquals(1, v1.version)
            assertEquals(Vec3F32(0f, 0f, -1f), v1.direction)
            assertEquals(f32(0.75f), v1.baseLight.shadowOpacity)
            assertNull(v1.baseLight.shadowParameters, "9.5 Base Light Data ends at Shadow Opacity")
            assertNull(v1.shadowParameters, "a version-1 9.5 light has no Shadow Parameters")

            val v2 = roundTripTyped(frame(2, shadow = true), order, v9) as InfiniteLightAttributeElement
            assertNull(v2.baseLight.shadowParameters, "9.5 keeps the pair off Base Light Data in every version")
            assertEquals(ShadowParameters(f32(0.25f), f32(0.75f)), v2.shadowParameters)
            assertEquals(Vec3F32(0f, 0f, -1f), v2.direction, "the direction must not absorb the alpha factors")
        }

    // spec: 9.5 Figure 56
    @Test
    fun pointLightV9CarriesShadowParametersAfterSpotIntensity() =
        forBothOrders { order ->
            fun frame(shadow: Boolean) =
                lsgFrame(order, ObjectTypeIds.POINT_LIGHT_ATTRIBUTE, 3, 34) {
                    writeTestBaseLightData(v9)
                    writeI16(2)
                    repeat(4) { writeF32(2f) } // HCoordF32 : Position
                    writeF32(1f)
                    writeF32(0.1f)
                    writeF32(0.01f) // Attenuation Coefficients (9.5 Figure 58)
                    writeF32(45f) // Spread Angle
                    writeF32(1f)
                    writeF32(0f)
                    writeF32(0f) // DirF32 : Spot Direction
                    writeI32(2) // I32 : Spot Intensity
                    if (shadow) {
                        writeF32(0.25f)
                        writeF32(0.75f)
                    }
                }

            val withTail = roundTripTyped(frame(shadow = true), order, v9) as PointLightAttributeElement
            assertEquals(f32(45f), withTail.spreadAngle)
            assertEquals(2, withTail.spotIntensity)
            assertEquals(ShadowParameters(f32(0.25f), f32(0.75f)), withTail.shadowParameters)
            assertNull(withTail.baseLight.shadowParameters)

            val withoutTail = roundTripTyped(frame(shadow = false), order, v9) as PointLightAttributeElement
            assertNull(withoutTail.shadowParameters)
            assertEquals(2, withoutTail.spotIntensity)
        }

    // The v10 placement is the mirror image: in Base Light Data, unconditional, no tail.
    // spec: Figure 57
    @Test
    fun infiniteLightV10KeepsShadowParametersInBaseLightData() =
        forBothOrders { order ->
            val bytes =
                lsgFrame(order, ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE, 3, 33) {
                    writeTestBaseLightData(v10)
                    writeU8(1u)
                    writeF32(0f)
                    writeF32(0f)
                    writeF32(-1f)
                }
            val element = roundTripTyped(bytes, order, v10) as InfiniteLightAttributeElement
            assertEquals(ShadowParameters(f32(1f), f32(0.5f)), element.baseLight.shadowParameters)
            assertNull(element.shadowParameters, "v10 has no element-level Shadow Parameters")
        }

    // --- 9.5 Figure 61: Element Value is F32, not F64 (F2a) ---

    private fun transformFrame(
        order: Endianness,
        generation: LsgGeneration,
        mask: Int,
        values: List<Float>,
        width: TransformValueWidth,
    ) = lsgFrame(order, ObjectTypeIds.GEOMETRIC_TRANSFORM_ATTRIBUTE, 3, 37) {
        writeTestBaseAttributeData(generation)
        writeTestVersionNumber(generation)
        writeU16(mask.toUShort())
        for (value in values) {
            if (width == TransformValueWidth.F32) writeF32(value) else writeF64(value.toDouble())
        }
    }

    // 9.5 Figure 61 (p.91) types the stored matrix elements `F32` in the figure box and in the
    // prose heading; v10 Figure 63 types them `F64`. A 16-element matrix read the v10 way would
    // over-read a 9.5 body by 64 bytes — but the *value* is wrong long before the length is:
    // `0.1f` read as half of an `F64` is not 0.1.
    // spec: 9.5 Figure 61
    @Test
    fun geometricTransformV9StoresF32ElementValues() =
        forBothOrders { order ->
            // Translation (elements 12, 13, 14 → mask bits 3, 2, 1) plus m00 (bit 15).
            val mask = 0x800E
            val values = listOf(0.1f, 10.5f, -20.25f, 30f)
            val element =
                roundTripTyped(
                    transformFrame(order, v9, mask, values, TransformValueWidth.F32),
                    order,
                    v9,
                ) as GeometricTransformAttributeElement
            assertEquals(TransformValueWidth.F32, element.valueWidth)
            assertEquals(mask, element.storedValuesMask)
            assertEquals(f32(0.1f).toDouble(), element.matrix.values[0], "the F32 on the wire, widened — not reinterpreted")
            assertEquals(listOf(10.5, -20.25, 30.0), element.matrix.values.subList(12, 15))
            assertEquals(1.0, element.matrix.values[5], "unstored elements stay identity")
        }

    // Both widths are resolved from the body — `popcount(mask) × 4` against `× 8` — and the
    // width that was read is a model fact, so the off-document combinations round-trip too
    // ("lenient when reading, strict when writing", DESIGN.md).
    // spec: 9.5 Figure 61
    // spec: Figure 63
    @Test
    fun geometricTransformValueWidthComesFromTheBodyNotTheGeneration() =
        forBothOrders { order ->
            val mask = 0x8000
            val f64InV9 =
                roundTripTyped(
                    transformFrame(order, v9, mask, listOf(2.5f), TransformValueWidth.F64),
                    order,
                    v9,
                ) as GeometricTransformAttributeElement
            assertEquals(TransformValueWidth.F64, f64InV9.valueWidth)
            assertEquals(2.5, f64InV9.matrix.values[0])

            val f32InV10 =
                roundTripTyped(
                    transformFrame(order, v10, mask, listOf(2.5f), TransformValueWidth.F32),
                    order,
                    v10,
                ) as GeometricTransformAttributeElement
            assertEquals(TransformValueWidth.F32, f32InV10.valueWidth)
            assertEquals(2.5, f32InV10.matrix.values[0])

            // With nothing stored the two readings coincide; each generation keeps its own
            // documented width so that the strict writer stays on the document.
            for (generation in listOf(v9, v10)) {
                val empty =
                    roundTripTyped(
                        transformFrame(order, generation, 0, emptyList(), TransformValueWidth.F32),
                        order,
                        generation,
                    ) as GeometricTransformAttributeElement
                assertEquals(
                    if (generation == v9) TransformValueWidth.F32 else TransformValueWidth.F64,
                    empty.valueWidth,
                )
                assertEquals(IDENTITY_MATRIX, empty.matrix.values)
            }
        }

    // --- 9.5 Figure 48: Shared Image Flag is U8, not U32 (F2b) ---

    // The flag sits three bytes before `I16 : Mipmaps Count`, so the wrong width does not
    // shorten the block — it walks the mipmap loop out of step. The width is resolved by
    // parsing the element's image list under each candidate and keeping the one that consumes
    // the body exactly, and it is recorded per image so the re-encode is a projection.
    // spec: 9.5 Figure 48
    // spec: Figure 53
    @Test
    fun sharedImageFlagWidthIsResolvedFromTheImageList() =
        forBothOrders { order ->
            val texels = byteArrayOf(1, 2, 3, 4, 5, 6)

            fun frame(width: SharedImageFlagWidth) =
                lsgFrame(order, ObjectTypeIds.TEXTURE_IMAGE_ATTRIBUTE, 3, 30) {
                    writeTestBaseAttributeData(v10)
                    writeU8(1u) // version
                    writeI32(2) // texture type
                    repeat(8) { writeI32(0) } // Texture Environment I32 fields
                    repeat(8) { writeF32(0f) } // blend + border colour
                    repeat(16) { writeF32(0f) } // texture transform
                    repeat(4) { writeI32(0) } // coord generation modes
                    repeat(16) { writeF32(0f) } // reference planes
                    writeI32(0) // texture channel
                    writeI32(0) // tex coord channel
                    writeU32(0u) // empty field
                    writeU8(1u) // inline image storage flag
                    writeI32(1) // image count
                    // Image Format Description (9.5 Figure 48 / v10 Figure 53)
                    writeU32(1u) // pixel format
                    writeU32(3u) // pixel data type
                    writeI16(2) // dimensionality
                    writeI16(1) // row alignment
                    writeI16(4) // width
                    writeI16(1) // height
                    writeI16(1) // depth
                    writeI16(0) // border texels
                    if (width == SharedImageFlagWidth.U8) writeU8(1u) else writeU32(1u) // shared image flag
                    writeI16(1) // mipmaps count
                    writeI32(texels.size) // total image data size
                    writeI32(texels.size) // mipmap image byte count
                    writeBytes(texels)
                }

            for (width in SharedImageFlagWidth.entries) {
                val element = roundTripTyped(frame(width), order, v10) as TextureImageAttributeElement
                val image = element.textureData.inlineImages.single()
                assertEquals(width, image.format.sharedImageFlagWidth)
                assertEquals(1u, image.format.sharedImageFlag)
                assertEquals(4, image.format.width, "a misread flag walks the rest of the block out of step")
                assertEquals(1, image.format.mipmapsCount)
                assertEquals(texels.size, image.mipmapImages.single().size)
            }
        }

    // --- 9.5 p.55 vs v10 Table 15: State Flags bit 0x01 (F7) ---

    // The same byte, opposite meanings: 9.5 §7.2.1.1.2.1.1 assigns bit `0x01` the attribute-wide
    // Accumulation Final flag, v10 Table 15 declares it Unused (v10 expresses finality per field
    // in the Field Final Flags word that JT 9 does not have). The model discriminates the two by
    // the presence of that word.
    // spec: 9.5 §7.2.1.1.2.1.1
    @Test
    fun accumulationFinalIsAJt9ReadingOfStateFlagsBitZero() =
        forBothOrders { order ->
            fun material(generation: LsgGeneration) =
                lsgFrame(order, ObjectTypeIds.MATERIAL_ATTRIBUTE, 3, 27) {
                    writeTestBaseAttributeData(generation, stateFlags = 0x09)
                    // 9.5 Figure 42 gates Reflectivity on local version 2; v10 Figure 47 has
                    // it unconditionally and knows version 1 only.
                    writeTestVersionNumber(generation, version = if (generation == LsgGeneration.V9) 2 else 1)
                    writeU16(0x39a0u)
                    repeat(16) { writeF32(0.25f) }
                    writeF32(30f) // shininess
                    writeF32(0.5f) // reflectivity
                    if (generation != LsgGeneration.V9) writeF32(1f) // bumpiness
                }

            val jt9 = roundTripTyped(material(v9), order, v9) as MaterialAttributeElement
            assertNull(jt9.baseAttribute.fieldFinalFlags, "9.5 Figure 39 has no Field Final Flags")
            assertEquals(true, jt9.baseAttribute.accumulationFinal)

            val jt10 = roundTripTyped(material(v10), order, v10) as MaterialAttributeElement
            assertNotNull(jt10.baseAttribute.fieldFinalFlags)
            assertEquals(
                false,
                jt10.baseAttribute.accumulationFinal,
                "v10 Table 15 declares bit 0x01 Unused — reading it would invent a meaning",
            )
        }

    // The Layer-2 consequence of the same inversion: attribute accumulation is not modelled, so
    // every accumulation flag the scene cannot honour has to be *named* (issue #1, "refusals
    // speak"). A JT 9 attribute declaring its accumulation final was silently ignored, because
    // the check only knew v10's per-field word.
    // spec: 9.5 §7.2.1.1.2.1.1
    // spec: §13.9
    @Test
    fun theSceneNamesTheJt9AccumulationFinalFlagItCannotHonour() {
        fun sceneNotes(
            generation: LsgGeneration,
            stateFlags: Int,
            fieldFinalFlags: UInt?,
        ): List<String> {
            val material =
                MaterialAttributeElement(
                    2,
                    BaseAttributeData(1, stateFlags, 0u, fieldFinalFlags),
                    1, 0,
                    Rgba(0f, 0f, 0f, 1f), Rgba(1f, 1f, 1f, 1f), Rgba(0f, 0f, 0f, 1f), Rgba(0f, 0f, 0f, 1f),
                    30f, 0f, if (generation == LsgGeneration.V9) null else 1f,
                )
            val box = BBoxF32(Vec3F32(-1f, -1f, -1f), Vec3F32(1f, 1f, 1f))
            val partition =
                PartitionNodeElement(
                    1,
                    GroupNodeData(BaseNodeData(1, 0u, listOf(2)), 1, emptyList()),
                    null, 0, "", box, 0f, CountRange(0, 0), CountRange(0, 0), CountRange(0, 0), null,
                )
            val document =
                LsgDocument(generation, listOf(partition, material), true, emptyList(), true, null, Bytes.EMPTY)
            return de.haumacher.kotlinjt.scene
                .buildScene(document, de.haumacher.kotlinjt.scene.LodPolicy.ALL_LODS) {
                    de.haumacher.kotlinjt.scene.ShapeSource.Unavailable("no shapes in this document")
                }.notes.map { it.name }
        }

        assertEquals(
            listOf("SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED"),
            sceneNotes(LsgGeneration.V9, stateFlags = 0x09, fieldFinalFlags = null),
            "a JT 9 attribute declaring accumulation final must not be silently ignored",
        )
        assertEquals(
            emptyList(),
            sceneNotes(LsgGeneration.V10, stateFlags = 0x09, fieldFinalFlags = 0u),
            "in v10 the same bit is Unused — noting it would invent a refusal",
        )
    }

    private companion object {
        val IDENTITY_MATRIX =
            listOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
    }
}
