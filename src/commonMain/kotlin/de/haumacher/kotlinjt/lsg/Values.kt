package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter

/**
 * The composite value types of the JT v10 reference, clause 4 (Data Types), as they appear in
 * §6 element bodies: coordinates, directions, bounding boxes, colours, planes and matrices.
 * All are immutable values with structural equality — the currency of the Layer 1 model.
 */
data class Vec3F32(
    val x: Float,
    val y: Float,
    val z: Float,
)

/** A 4-component F32 vector: HCoordF32 positions, Vector4f property values. */
data class Vec4F32(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
)

/** An axis-aligned bounding box of two CoordF32 corners (BBoxF32). */
data class BBoxF32(
    val min: Vec3F32,
    val max: Vec3F32,
)

/** A min/max count pair (Figure 24 — Vertex Count Range and its reuses). */
data class CountRange(
    val min: Int,
    val max: Int,
)

/** An RGBA colour value (4 × F32). */
data class Rgba(
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
)

/** A plane equation `ax + by + cz + d = 0` (PlaneF32). */
data class PlaneF32(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
)

/** A 4×4 F32 matrix in row-major storage order (Mx4F32). */
data class Mx4F32(
    val values: List<Float>,
) {
    init {
        require(values.size == 16) { "Mx4F32 needs 16 values, got ${values.size}" }
    }
}

/** A 4×4 F64 matrix in row-major storage order (Mx4F64). */
data class Mx4F64(
    val values: List<Double>,
) {
    init {
        require(values.size == 16) { "Mx4F64 needs 16 values, got ${values.size}" }
    }
}

internal fun ByteReader.readVec3F32(): Vec3F32 = Vec3F32(readF32(), readF32(), readF32())

internal fun ByteWriter.writeVec3F32(value: Vec3F32) {
    writeF32(value.x)
    writeF32(value.y)
    writeF32(value.z)
}

internal fun ByteReader.readVec4F32(): Vec4F32 = Vec4F32(readF32(), readF32(), readF32(), readF32())

internal fun ByteWriter.writeVec4F32(value: Vec4F32) {
    writeF32(value.x)
    writeF32(value.y)
    writeF32(value.z)
    writeF32(value.w)
}

internal fun ByteReader.readBBoxF32(): BBoxF32 = BBoxF32(readVec3F32(), readVec3F32())

internal fun ByteWriter.writeBBoxF32(value: BBoxF32) {
    writeVec3F32(value.min)
    writeVec3F32(value.max)
}

internal fun ByteReader.readCountRange(): CountRange = CountRange(readI32(), readI32())

internal fun ByteWriter.writeCountRange(value: CountRange) {
    writeI32(value.min)
    writeI32(value.max)
}

internal fun ByteReader.readRgba(): Rgba = Rgba(readF32(), readF32(), readF32(), readF32())

internal fun ByteWriter.writeRgba(value: Rgba) {
    writeF32(value.r)
    writeF32(value.g)
    writeF32(value.b)
    writeF32(value.a)
}

internal fun ByteReader.readPlaneF32(): PlaneF32 = PlaneF32(readF32(), readF32(), readF32(), readF32())

internal fun ByteWriter.writePlaneF32(value: PlaneF32) {
    writeF32(value.a)
    writeF32(value.b)
    writeF32(value.c)
    writeF32(value.d)
}

internal fun ByteReader.readMx4F32(): Mx4F32 = Mx4F32(List(16) { readF32() })

internal fun ByteWriter.writeMx4F32(value: Mx4F32) {
    for (v in value.values) writeF32(v)
}

internal fun ByteReader.readMx4F64(): Mx4F64 = Mx4F64(List(16) { readF64() })

internal fun ByteWriter.writeMx4F64(value: Mx4F64) {
    for (v in value.values) writeF64(v)
}

/** VecF32: an I32 count followed by that many F32 values (clause 4, Table 4). */
internal fun ByteReader.readVecF32(): List<Float> {
    val count = readI32()
    if (count < 0) throw de.haumacher.kotlinjt.JtFormatException("negative VecF32 count $count at offset $position")
    return List(count) { readF32() }
}

internal fun ByteWriter.writeVecF32(values: List<Float>) {
    writeI32(values.size)
    for (v in values) writeF32(v)
}
