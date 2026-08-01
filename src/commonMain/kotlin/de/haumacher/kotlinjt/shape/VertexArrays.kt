package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.lsg.Vec3F32
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Uniform Quantizer Data (Figure 147): a scalar quantizer whose range is divided into levels
 * of equal spacing. [dequantize] applies the inverse of the §12.2.1 Uniform Data Quantization
 * algorithm; the quantizer parameters stay exposed so consumers can see exactly what
 * precision the data carries.
 */
data class UniformQuantizerData(
    val min: Float,
    val max: Float,
    val numberOfBits: Int,
) {
    /** The inverse of the §12.2.1 encoder: `code * (max - min) / maxCode + min`. */
    fun dequantize(code: Int): Float {
        if (numberOfBits == 0) return min
        val maxCode = if (numberOfBits < 32) (1L shl numberOfBits) - 1 else 0xFFFFFFFFL
        val value = min + (code.toLong() and 0xFFFFFFFFL).toDouble() * (max - min).toDouble() / maxCode.toDouble()
        return value.toFloat()
    }

    fun write(w: ByteWriter) {
        w.writeF32(min)
        w.writeF32(max)
        w.writeU8(numberOfBits.toUByte())
    }

    companion object {
        fun read(r: ByteReader): UniformQuantizerData = UniformQuantizerData(r.readF32(), r.readF32(), r.readU8().toInt())
    }
}

/** Point Quantizer Data (Figure 144): one uniform quantizer per coordinate axis. */
data class PointQuantizerData(
    val x: UniformQuantizerData,
    val y: UniformQuantizerData,
    val z: UniformQuantizerData,
) {
    fun write(w: ByteWriter) {
        x.write(w)
        y.write(w)
        z.write(w)
    }

    companion object {
        fun read(r: ByteReader): PointQuantizerData =
            PointQuantizerData(UniformQuantizerData.read(r), UniformQuantizerData.read(r), UniformQuantizerData.read(r))
    }
}

/**
 * Compressed Vertex Coordinate Array (v10 §12.1.3, Figure 138; JT 9 wire format per the 9.5
 * reference §8.1.4, fixture-verified — DESIGN.md). The JT 9 lossless path stores an
 * exponent+mantissa packet pair per component; the quantized path stores one code packet per
 * component. The stored hash is verified at decode, so a codec defect can never yield
 * silently wrong coordinates.
 */
data class CompressedVertexCoordinateArray(
    val uniqueVertexCount: Int,
    val numberComponents: Int,
    val quantizer: PointQuantizerData,
    /** Wire packets: lossless — exp/mant pairs per component; quantized — one per component. */
    val packets: List<Int32Cdp>,
    val vertexCoordinateHash: Int,
    /** The decoded unique vertex coordinates (exact floats on the lossless path). */
    val coordinates: List<Vec3F32>,
) {
    val isLossless: Boolean get() = quantizer.x.numberOfBits == 0

    fun write(w: ByteWriter) {
        w.writeI32(uniqueVertexCount)
        w.writeU8(numberComponents.toUByte())
        quantizer.write(w)
        for (packet in packets) packet.encode(w)
        w.writeI32(vertexCoordinateHash)
    }

    companion object {
        fun read(r: ByteReader): CompressedVertexCoordinateArray {
            val count = r.readI32()
            if (count < 0) throw JtFormatException("unique vertex count $count is negative")
            val components = r.readU8().toInt()
            if (components != 3) {
                throw JtFormatException("vertex coordinate array with $components components; the spec allows only 3")
            }
            val quantizer = PointQuantizerData.read(r)
            val quantizers = listOf(quantizer.x, quantizer.y, quantizer.z)
            if (quantizers.any { it.numberOfBits != quantizer.x.numberOfBits }) {
                throw JtFormatException("point quantizer components disagree on the number of bits")
            }
            val packets = mutableListOf<Int32Cdp>()
            val componentValues = mutableListOf<List<Int>>()
            val lossless = quantizer.x.numberOfBits == 0
            var hash = 0
            for (component in 0 until 3) {
                if (lossless) {
                    val (expPacket, exponents) = readInt32CdpValues(r, Predictor.LAG1)
                    val (mantPacket, mantissae) = readInt32CdpValues(r, Predictor.LAG1)
                    packets.add(expPacket)
                    packets.add(mantPacket)
                    if (exponents.size != count || mantissae.size != count) {
                        throw JtFormatException(
                            "coordinate component $component decodes to ${exponents.size}/${mantissae.size} values, expected $count",
                        )
                    }
                    val bitsArray = IntArray(count) { (exponents[it] shl 23) or mantissae[it] }
                    componentValues.add(bitsArray.toList())
                    for (word in bitsArray) hash = JtHash.hash32(intArrayOf(word), hash)
                } else {
                    val (packet, codes) = readInt32CdpValues(r, Predictor.LAG1)
                    packets.add(packet)
                    if (codes.size != count) {
                        throw JtFormatException("coordinate component $component decodes to ${codes.size} codes, expected $count")
                    }
                    componentValues.add(codes)
                    hash = JtHash.hash32(codes.toIntArray(), hash)
                }
            }
            val storedHash = r.readI32()
            if (storedHash != hash) {
                throw JtFormatException("vertex coordinate hash mismatch: stored $storedHash, computed $hash")
            }
            val coordinates =
                List(count) { i ->
                    if (lossless) {
                        Vec3F32(
                            Float.fromBits(componentValues[0][i]),
                            Float.fromBits(componentValues[1][i]),
                            Float.fromBits(componentValues[2][i]),
                        )
                    } else {
                        Vec3F32(
                            quantizer.x.dequantize(componentValues[0][i]),
                            quantizer.y.dequantize(componentValues[1][i]),
                            quantizer.z.dequantize(componentValues[2][i]),
                        )
                    }
                }
            return CompressedVertexCoordinateArray(count, components, quantizer, packets, storedHash, coordinates)
        }
    }
}

/**
 * Compressed Vertex Normal Array (v10 §12.1.4, Figure 139; JT 9 wire format per the 9.5
 * reference §8.1.5, fixture-verified — DESIGN.md). Quantized normals are Deering-coded as
 * four code packets (sextant, octant, theta, psi — the JT 9 delta against v10's single packed
 * code array); lossless normals are exponent+mantissa pairs per component. The stored hash is
 * verified at decode.
 */
data class CompressedVertexNormalArray(
    val normalCount: Int,
    val numberComponents: Int,
    val quantizationBits: Int,
    /** Wire packets: quantized — sextant/octant/theta/psi; lossless — exp/mant pairs. */
    val packets: List<Int32Cdp>,
    val vertexNormalHash: Int,
    /** The decoded normals (Deering-dequantized, or exact floats on the lossless path). */
    val normals: List<Vec3F32>,
) {
    fun write(w: ByteWriter) {
        w.writeI32(normalCount)
        w.writeU8(numberComponents.toUByte())
        w.writeU8(quantizationBits.toUByte())
        for (packet in packets) packet.encode(w)
        w.writeI32(vertexNormalHash)
    }

    companion object {
        fun read(r: ByteReader): CompressedVertexNormalArray {
            val count = r.readI32()
            if (count < 0) throw JtFormatException("normal count $count is negative")
            val components = r.readU8().toInt()
            if (components != 3) {
                throw JtFormatException("normal array with $components components; normals are always 3-component")
            }
            val quantizationBits = r.readU8().toInt()
            val packets = mutableListOf<Int32Cdp>()
            var hash = 0
            val normals: List<Vec3F32>
            if (quantizationBits == 0) {
                val componentBits = mutableListOf<IntArray>()
                for (component in 0 until 3) {
                    val (expPacket, exponents) = readInt32CdpValues(r, Predictor.NONE)
                    val (mantPacket, mantissae) = readInt32CdpValues(r, Predictor.NONE)
                    packets.add(expPacket)
                    packets.add(mantPacket)
                    if (exponents.size != count || mantissae.size != count) {
                        throw JtFormatException(
                            "normal component $component decodes to ${exponents.size}/${mantissae.size} values, expected $count",
                        )
                    }
                    val bitsArray = IntArray(count) { (exponents[it] shl 23) or mantissae[it] }
                    componentBits.add(bitsArray)
                    for (word in bitsArray) hash = JtHash.hash32(intArrayOf(word), hash)
                }
                normals =
                    List(count) { i ->
                        Vec3F32(
                            Float.fromBits(componentBits[0][i]),
                            Float.fromBits(componentBits[1][i]),
                            Float.fromBits(componentBits[2][i]),
                        )
                    }
            } else {
                if (quantizationBits > 13) {
                    throw JtFormatException("Deering quantization bits $quantizationBits exceed the maximum of 13")
                }
                val codes = mutableListOf<List<Int>>()
                for (name in listOf("sextant", "octant", "theta", "psi")) {
                    val (packet, values) = readInt32CdpValues(r, Predictor.NONE)
                    packets.add(packet)
                    if (values.size != count) {
                        throw JtFormatException("normal $name codes decode to ${values.size} values, expected $count")
                    }
                    codes.add(values)
                    hash = JtHash.hash32(values.toIntArray(), hash)
                }
                normals =
                    List(count) { i ->
                        deeringCodeToVector(codes[0][i], codes[1][i], codes[2][i], codes[3][i], quantizationBits)
                    }
            }
            val storedHash = r.readI32()
            if (storedHash != hash) {
                throw JtFormatException("vertex normal hash mismatch: stored $storedHash, computed $hash")
            }
            return CompressedVertexNormalArray(count, components, quantizationBits, packets, storedHash, normals)
        }
    }
}

/**
 * The Deering Normal CODEC decode (§12.2.4; reference source in Annex B / 9.5 Appendix C §4):
 * converts a (sextant, octant, theta, psi) code quadruple back to a unit vector.
 */
internal fun deeringCodeToVector(
    sextant: Int,
    octant: Int,
    thetaCode: Int,
    psiCode: Int,
    numberOfBits: Int,
): Vec3F32 {
    val psiMax = 0.615479709
    val bitRange = 1 shl numberOfBits
    // For sextants 1, 3 and 5 the theta code is incremented before dequantization.
    val theta = asin(tan(psiMax * (bitRange - (thetaCode + (sextant and 1))) / bitRange))
    val psi = psiMax * (psiCode.toDouble() / bitRange)
    val cosTheta = cos(theta)
    val sinTheta = sin(theta)
    val cosPsi = cos(psi)
    val sinPsi = sin(psi)
    val xx = (cosTheta * cosPsi).toFloat()
    val yy = sinPsi.toFloat()
    val zz = (sinTheta * cosPsi).toFloat()
    var x = xx
    var y = yy
    var z = zz
    when (sextant) {
        0 -> {}
        1 -> {
            // Mirror about the x = z plane.
            z = xx
            x = zz
        }
        2 -> {
            // Rotate clockwise.
            z = xx
            x = yy
            y = zz
        }
        3 -> {
            // Mirror about the x = y plane.
            y = xx
            x = yy
        }
        4 -> {
            // Rotate counter-clockwise.
            y = xx
            z = yy
            x = zz
        }
        5 -> {
            // Mirror about the y = z plane.
            z = yy
            y = zz
        }
        else -> throw JtFormatException("Deering sextant code $sextant out of range")
    }
    if (octant and 0x4 == 0) x = -x
    if (octant and 0x2 == 0) y = -y
    if (octant and 0x1 == 0) z = -z
    return Vec3F32(x, y, z)
}
