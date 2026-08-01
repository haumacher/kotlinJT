package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds one element frame by hand: I32 length, Object Type ID, Object Base Type,
 * I32 Object ID, then the body written by [body] following the spec figure's field order.
 */
fun lsgFrame(
    order: Endianness,
    typeId: Guid,
    baseType: Int,
    objectId: Int,
    body: ByteWriter.() -> Unit = {},
): ByteArray {
    val bodyWriter = ByteWriter(order)
    bodyWriter.writeU8(baseType.toUByte())
    bodyWriter.writeI32(objectId)
    bodyWriter.body()
    val bodyBytes = bodyWriter.toByteArray()
    val writer = ByteWriter(order)
    writer.writeI32(16 + bodyBytes.size)
    writer.writeGuid(typeId)
    writer.writeBytes(bodyBytes)
    return writer.toByteArray()
}

/** Decodes a single element frame, returning the element and the notes it produced. */
internal fun decodeSingle(
    bytes: ByteArray,
    order: Endianness,
    generation: LsgGeneration,
): Pair<LsgElement, List<LoadNote>> {
    val notes = mutableListOf<LoadNote>()
    val reader = ByteReader(bytes, order)
    val result = decodeElementFrame(reader, LsgDecodeContext(generation, notes), "test element")
    assertTrue(result is FrameResult.Element, "expected an element frame, got $result")
    assertEquals(0, reader.remaining, "frame did not consume all bytes")
    return result.element to notes
}

/** Serializes a single element back to frame bytes. */
internal fun encodeSingle(
    element: LsgElement,
    order: Endianness,
    generation: LsgGeneration,
): ByteArray {
    val writer = ByteWriter(order)
    encodeElementFrame(writer, generation, element)
    return writer.toByteArray()
}

/**
 * The per-figure contract: the hand-built [bytes] decode without notes to a typed element,
 * and the element serializes back byte-identically. Returns the element for field asserts.
 */
internal fun roundTripTyped(
    bytes: ByteArray,
    order: Endianness,
    generation: LsgGeneration,
): LsgElement {
    val (element, notes) = decodeSingle(bytes, order, generation)
    assertEquals(emptyList(), notes, "typed decode must be note-free")
    assertTrue(element !is OpaqueLsgElement, "expected a typed element, got opaque")
    assertContentEquals(bytes, encodeSingle(element, order, generation), "encode(decode(bytes)) drifted")
    return element
}

/**
 * Normalizes a literal to true float32 precision. On Kotlin/JS a `Float` is backed by a
 * Double, so a literal like `0.2f` differs from the same value after an F32 wire round-trip;
 * pushing it through the raw bits makes expectations platform-independent.
 */
fun f32(value: Float): Float = Float.fromBits(value.toRawBits())

/** Runs [block] once per byte order — layouts must hold under both (clause 5.1.1). */
fun forBothOrders(block: (Endianness) -> Unit) {
    block(Endianness.LITTLE_ENDIAN)
    block(Endianness.BIG_ENDIAN)
}

// --- Hand-written data collections, factored per spec figure ---

/** Base Node Data fields (Figure 22), with the generation's version-number width. */
internal fun ByteWriter.writeTestBaseNodeData(
    generation: LsgGeneration,
    version: Int = 1,
    flags: UInt = 0u,
    attributeIds: List<Int> = emptyList(),
) {
    if (generation == LsgGeneration.V9) writeI16(version.toShort()) else writeU8(version.toUByte())
    writeU32(flags)
    writeI32(attributeIds.size)
    for (id in attributeIds) writeI32(id)
}

/** Group Node Data fields (Figure 26). */
internal fun ByteWriter.writeTestGroupNodeData(
    generation: LsgGeneration,
    children: List<Int> = emptyList(),
    attributeIds: List<Int> = emptyList(),
) {
    writeTestBaseNodeData(generation, attributeIds = attributeIds)
    if (generation == LsgGeneration.V9) writeI16(1) else writeU8(1u)
    writeI32(children.size)
    for (id in children) writeI32(id)
}

/** Base Shape Data fields (Figure 36); JT 9 carries the extra reserved box. */
internal fun ByteWriter.writeTestBaseShapeData(generation: LsgGeneration) {
    writeTestBaseNodeData(generation)
    if (generation == LsgGeneration.V9) writeI16(1) else writeU8(1u)
    if (generation == LsgGeneration.V9) {
        repeat(6) { writeF32(9f) } // reserved bounding box (v9 only)
    }
    writeF32(-1f)
    writeF32(-2f)
    writeF32(-3f)
    writeF32(1f)
    writeF32(2f)
    writeF32(3f) // untransformed bbox
    writeF32(42.5f) // area
    writeI32(10)
    writeI32(20) // vertex count range
    writeI32(1)
    writeI32(1) // node count range
    writeI32(5)
    writeI32(6) // polygon count range
    writeU32(0u) // size
    writeF32(0f) // compression level
}

/**
 * The model counterpart of [writeTestBaseShapeData] — the same fields, value for value, so a
 * strict-write test can assert that a hand-built model serializes to the hand-built bytes.
 */
internal fun testBaseShapeData(generation: LsgGeneration): BaseShapeData =
    BaseShapeData(
        BaseNodeData(1, 0u, emptyList()),
        1,
        if (generation == LsgGeneration.V9) BBoxF32(Vec3F32(9f, 9f, 9f), Vec3F32(9f, 9f, 9f)) else null,
        BBoxF32(Vec3F32(-1f, -2f, -3f), Vec3F32(1f, 2f, 3f)),
        42.5f,
        CountRange(10, 20),
        CountRange(1, 1),
        CountRange(5, 6),
        0u,
        0f,
    )

/** The model counterpart of [writeTestVertexShapeData]. */
internal fun testVertexShapeData(
    generation: LsgGeneration,
    version: Int = if (generation == LsgGeneration.V9) 2 else 1,
    bindings: ULong = 0x3u,
    guardedBindings: ULong? = if (generation == LsgGeneration.V9) bindings else null,
): VertexShapeData =
    VertexShapeData(
        testBaseShapeData(generation),
        version,
        bindings,
        if (generation == LsgGeneration.V9) QuantizationParameters(12, 3, 10, 8) else null,
        guardedBindings,
    )

/**
 * Vertex Shape Data fields (9.5 Figure 30 / v10 Figure 39); JT 9 adds the Quantization
 * Parameters and the guarded second `U64 : Vertex Binding`.
 *
 * [guardedBindings] is the value of that second field, or `null` to leave it off the wire —
 * the encoding a producer writes if it reads Figure 30's `Version Number == 1` literally.
 * The default follows the append-only reading of §9.4 (present from version 1 upwards).
 */
internal fun ByteWriter.writeTestVertexShapeData(
    generation: LsgGeneration,
    version: Int = if (generation == LsgGeneration.V9) 2 else 1,
    bindings: ULong = 0x3u,
    guardedBindings: ULong? = if (generation == LsgGeneration.V9) bindings else null,
) {
    writeTestBaseShapeData(generation)
    if (generation == LsgGeneration.V9) writeI16(version.toShort()) else writeU8(version.toUByte())
    writeU64(bindings)
    if (generation == LsgGeneration.V9) {
        writeU8(12u)
        writeU8(3u)
        writeU8(10u)
        writeU8(8u) // quantization parameters
        guardedBindings?.let { writeU64(it) }
    }
}

/** Base Attribute Data fields (Figure 46); the final-flags word is v10-only. */
internal fun ByteWriter.writeTestBaseAttributeData(
    generation: LsgGeneration,
    stateFlags: Int = 8,
    inhibit: UInt = 0u,
    final: UInt = 0u,
) {
    if (generation == LsgGeneration.V9) writeI16(1) else writeU8(1u)
    writeU8(stateFlags.toUByte())
    writeU32(inhibit)
    if (generation != LsgGeneration.V9) writeU32(final)
}

/**
 * The trailing I32 the 10.5 generation appends to attribute elements — written at the *end*
 * of the element body, after the type-specific fields (DESIGN.md delta 24).
 */
internal fun ByteWriter.writeTestAttributeTail(
    generation: LsgGeneration,
    tail: Int = -1,
) {
    if (generation == LsgGeneration.V10_5) writeI32(tail)
}

/** Base Property Atom Data fields (Figure 70). */
internal fun ByteWriter.writeTestBasePropertyAtomData(
    generation: LsgGeneration,
    stateFlags: UInt = 0u,
) {
    if (generation == LsgGeneration.V9) writeI16(1) else writeU8(1u)
    writeU32(stateFlags)
}

internal fun ByteWriter.writeTestVersionNumber(
    generation: LsgGeneration,
    version: Int = 1,
) {
    if (generation == LsgGeneration.V9) writeI16(version.toShort()) else writeU8(version.toUByte())
}

/** Base Light Data fields (Figure 57). */
internal fun ByteWriter.writeTestBaseLightData(generation: LsgGeneration) {
    writeTestBaseAttributeData(generation)
    writeTestVersionNumber(generation)
    repeat(4) { writeF32(0.1f) } // ambient
    repeat(4) { writeF32(0.2f) } // diffuse
    repeat(4) { writeF32(0.3f) } // specular
    writeF32(1.5f) // brightness
    writeI32(2) // coord system
    writeU8(1u) // shadow caster flag
    writeF32(0.75f) // shadow opacity
    writeF32(1f) // non-shadow alpha factor
    writeF32(0.5f) // shadow alpha factor
}
