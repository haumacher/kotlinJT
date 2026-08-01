package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.lsg.Vec3F32
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The compressed vertex arrays and their quantization structures (§12.1.3/§12.1.4, Figures
 * 138/139/144/147; JT 9 wire deltas in DESIGN.md). Hand-built byte vectors; every decode is
 * paired with a byte-identical re-encode.
 */
class VertexArrayTest {
    private fun bytesOf(build: ByteWriter.() -> Unit): ByteArray = ByteWriter(Endianness.LITTLE_ENDIAN).apply(build).toByteArray()

    private fun ByteWriter.writeNullCdp(values: List<Int>) {
        if (values.isEmpty()) {
            writeI32(0)
            return
        }
        writeI32(values.size)
        writeU8(0u)
        writeI32(values.size * 32)
        for (v in values) writeI32(v)
    }

    // spec: Figure 147
    @Test
    fun uniformQuantizerRoundTripsAndDequantizes() {
        val bytes =
            bytesOf {
                writeF32(-2f)
                writeF32(6f)
                writeU8(3u)
            }
        val quantizer = UniformQuantizerData.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        assertEquals(UniformQuantizerData(-2f, 6f, 3), quantizer)
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        quantizer.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
        // spec: §12.2.1 — the inverse of the uniform quantization: 0 -> min, maxCode -> max.
        assertEquals(-2f, quantizer.dequantize(0))
        assertEquals(6f, quantizer.dequantize(7))
        assertTrue(abs(quantizer.dequantize(3) - (-2f + 3f * 8f / 7f)) < 1e-6)
    }

    // spec: Figure 144
    @Test
    fun pointQuantizerIsThreeUniformQuantizers() {
        val bytes =
            bytesOf {
                writeF32(0f)
                writeF32(1f)
                writeU8(8u)
                writeF32(-1f)
                writeF32(1f)
                writeU8(8u)
                writeF32(10f)
                writeF32(20f)
                writeU8(8u)
            }
        val quantizer = PointQuantizerData.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        assertEquals(8, quantizer.y.numberOfBits)
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        quantizer.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    // spec: Figure 138
    @Test
    fun losslessCoordinateArrayReconstructsExactFloats() {
        // JT 9 lossless path: an exponent+mantissa packet pair per component
        // (float bits = (exp << 23) | mantissa), verified by the stored hash.
        val coords = listOf(Vec3F32(0f, -2.5f, 1e-3f), Vec3F32(1f, 3.25f, -7e8f))
        var hash = 0
        for (component in 0 until 3) {
            for (v in coords) {
                val bits = componentOf(v, component).toRawBits()
                hash = JtHash.hash32(intArrayOf(bits), hash)
            }
        }
        val bytes =
            bytesOf {
                writeI32(coords.size)
                writeU8(3u)
                for (component in 0 until 3) {
                    val values = coords.map { componentOf(it, component) }
                    writeF32(values.min())
                    writeF32(values.max())
                    writeU8(0u)
                }
                for (component in 0 until 3) {
                    writeNullCdp(coords.map { componentOf(it, component).toRawBits() ushr 23 })
                    writeNullCdp(coords.map { componentOf(it, component).toRawBits() and 0x7FFFFF })
                }
                writeI32(hash)
            }
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val array = CompressedVertexCoordinateArray.read(reader)
        assertEquals(bytes.size, reader.position)
        assertTrue(array.isLossless)
        // Bit-level comparison: on Kotlin/JS a Float literal is not rounded to F32 precision,
        // so value equality would compare against a double — the raw bits are the claim.
        assertEquals(
            coords.map { listOf(it.x.toRawBits(), it.y.toRawBits(), it.z.toRawBits()) },
            array.coordinates.map { listOf(it.x.toRawBits(), it.y.toRawBits(), it.z.toRawBits()) },
            "lossless coordinates must be bit-exact",
        )
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    // spec: Figure 138
    @Test
    fun quantizedCoordinateArrayDequantizesWithinTheQuantizer() {
        val codes = listOf(listOf(0, 255), listOf(128, 64), listOf(255, 0))
        var hash = 0
        for (component in codes) hash = JtHash.hash32(component.toIntArray(), hash)
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(3u)
                writeF32(0f)
                writeF32(10f)
                writeU8(8u)
                writeF32(-5f)
                writeF32(5f)
                writeU8(8u)
                writeF32(100f)
                writeF32(200f)
                writeU8(8u)
                for (component in codes) writeNullCdp(component)
                writeI32(hash)
            }
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val array = CompressedVertexCoordinateArray.read(reader)
        assertEquals(bytes.size, reader.position)
        assertEquals(0f, array.coordinates[0].x)
        assertEquals(10f, array.coordinates[1].x)
        assertEquals(200f, array.coordinates[0].z)
        assertTrue(abs(array.coordinates[0].y - 0.0196f) < 1e-3)
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    @Test
    fun coordinateHashMismatchRefusesTheDecode() {
        val bytes =
            bytesOf {
                writeI32(1)
                writeU8(3u)
                repeat(3) {
                    writeF32(0f)
                    writeF32(1f)
                    writeU8(0u)
                }
                repeat(3) {
                    writeNullCdp(listOf(0))
                    writeNullCdp(listOf(0))
                }
                writeI32(12345) // wrong hash
            }
        assertFailsWith<JtFormatException> {
            CompressedVertexCoordinateArray.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        }
    }

    // spec: §12.2.4
    @Test
    fun deeringCodeConversionMatchesTheReference() {
        val v = deeringCodeToVector(2, 5, 3, 1, 4)
        assertTrue(abs(v.x - 0.038457997f) < 1e-6)
        assertTrue(abs(v.y - -0.5459986f) < 1e-6)
        assertTrue(abs(v.z - 0.8369029f) < 1e-6)
        // Every Deering code decodes to a unit vector.
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        assertTrue(abs(length - 1f) < 1e-5)
    }

    // spec: Figure 139
    @Test
    fun quantizedNormalArrayDecodesDeeringCodes() {
        val sextants = listOf(2, 0)
        val octants = listOf(5, 7)
        val thetas = listOf(3, 0)
        val psis = listOf(1, 0)
        var hash = 0
        for (codes in listOf(sextants, octants, thetas, psis)) {
            hash = JtHash.hash32(codes.toIntArray(), hash)
        }
        val bytes =
            bytesOf {
                writeI32(2)
                writeU8(3u)
                writeU8(4u)
                writeNullCdp(sextants)
                writeNullCdp(octants)
                writeNullCdp(thetas)
                writeNullCdp(psis)
                writeI32(hash)
            }
        val reader = ByteReader(bytes, Endianness.LITTLE_ENDIAN)
        val array = CompressedVertexNormalArray.read(reader)
        assertEquals(bytes.size, reader.position)
        assertEquals(2, array.normals.size)
        assertEquals(deeringCodeToVector(2, 5, 3, 1, 4), array.normals[0])
        for (normal in array.normals) {
            val length = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
            assertTrue(abs(length - 1f) < 1e-5, "normal must be unit length, got $length")
        }
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    // spec: Figure 138
    @Test
    fun v10LosslessCoordinateArrayStoresBinaryFloatsPerComponent() {
        // v10 lossless path: ONE binary-float packet per component (Lag1), hashed per whole
        // component array — not per value (DESIGN.md delta 29).
        val vertices = listOf(Vec3F32(1.5f, -2.25f, 3.75f), Vec3F32(0.5f, 8f, -1f))
        var hash = 0
        for (component in 0 until 3) {
            val bits = IntArray(vertices.size) { componentOf(vertices[it], component).toRawBits() }
            hash = JtHash.hash32(bits, hash)
        }
        val bytes =
            bytesOf {
                writeI32(vertices.size)
                writeU8(3u)
                repeat(3) {
                    writeF32(0f)
                    writeF32(0f)
                    writeU8(0u)
                }
                for (component in 0 until 3) {
                    writeNullCdp(vertices.map { componentOf(it, component).toRawBits() })
                }
                writeI32(hash)
            }
        val array = CompressedVertexCoordinateArray.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        assertEquals(vertices, array.coordinates)
        assertTrue(array.isLossless)
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    // spec: Figure 139
    @Test
    fun v10QuantizedNormalArrayUnpacksPackedDeeringCodes() {
        // v10 quantized normals: one packet of packed codes [sextant:3][octant:3][theta:n][psi:n]
        // (Annex B unpackCode), NULL predictor, hashed as one array.
        val quantBits = 8
        val quads = listOf(listOf(0, 7, 10, 20), listOf(3, 5, 100, 200), listOf(5, 1, 255, 0))
        val codes =
            quads.map { (s, o, t, p) ->
                (s shl (2 * quantBits + 3)) or (o shl (2 * quantBits)) or (t shl quantBits) or p
            }
        val hash = JtHash.hash32(codes.toIntArray(), 0)
        val bytes =
            bytesOf {
                writeI32(codes.size)
                writeU8(3u)
                writeU8(quantBits.toUByte())
                writeNullCdp(codes)
                writeI32(hash)
            }
        val array = CompressedVertexNormalArray.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        assertEquals(3, array.normals.size)
        for ((i, quad) in quads.withIndex()) {
            val expected = deeringCodeToVector(quad[0], quad[1], quad[2], quad[3], quantBits)
            assertEquals(expected, array.normals[i], "packed code $i must unpack to the reference quadruple")
            val n = array.normals[i]
            assertTrue(abs(sqrt(n.x * n.x + n.y * n.y + n.z * n.z) - 1f) < 1e-3, "non-unit normal $n")
        }
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    // spec: Figure 139
    @Test
    fun v10LosslessNormalArrayUsesTheNullPredictor() {
        // The §12.1.4 prose names Lag1 but the bytes use NULL (DESIGN.md delta 30): the
        // stored words ARE the float bits, so a vector needing Lag1 would decode wrong.
        val normals = listOf(Vec3F32(0f, 0f, 1f), Vec3F32(1f, 0f, 0f), Vec3F32(0f, -1f, 0f), Vec3F32(0f, 0f, -1f), Vec3F32(0f, 1f, 0f))
        var hash = 0
        for (component in 0 until 3) {
            hash = JtHash.hash32(IntArray(normals.size) { componentOf(normals[it], component).toRawBits() }, hash)
        }
        val bytes =
            bytesOf {
                writeI32(normals.size)
                writeU8(3u)
                writeU8(0u)
                for (component in 0 until 3) {
                    writeNullCdp(normals.map { componentOf(it, component).toRawBits() })
                }
                writeI32(hash)
            }
        val array = CompressedVertexNormalArray.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        assertEquals(normals, array.normals)
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())
    }

    // spec: Figure 142
    @Test
    fun vertexFlagArrayRoundTripsAndValidatesTheCount() {
        val flags = listOf(0, 1, 1, 0, 1)
        val bytes =
            bytesOf {
                writeU32(flags.size.toUInt())
                writeNullCdp(flags)
            }
        val array = CompressedVertexFlagArray.read(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        assertEquals(flags, array.flags)
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        array.write(writer)
        assertContentEquals(bytes, writer.toByteArray())

        val mismatch =
            bytesOf {
                writeU32(7u) // declares 7 flags, the packet carries 5
                writeNullCdp(flags)
            }
        assertFailsWith<JtFormatException> {
            CompressedVertexFlagArray.read(ByteReader(mismatch, Endianness.LITTLE_ENDIAN))
        }
    }

    // spec: Figure 138
    @Test
    fun v10CoordinateHashMismatchRefusesTheDecode() {
        val bytes =
            bytesOf {
                writeI32(1)
                writeU8(3u)
                repeat(3) {
                    writeF32(0f)
                    writeF32(0f)
                    writeU8(0u)
                }
                repeat(3) { writeNullCdp(listOf(1f.toRawBits())) }
                writeI32(12345) // wrong hash
            }
        assertFailsWith<JtFormatException> {
            CompressedVertexCoordinateArray.readV10(ByteReader(bytes, Endianness.LITTLE_ENDIAN))
        }
    }

    private fun componentOf(
        v: Vec3F32,
        component: Int,
    ): Float =
        when (component) {
            0 -> v.x
            1 -> v.y
            else -> v.z
        }
}
