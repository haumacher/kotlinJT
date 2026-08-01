package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.Predictor
import de.haumacher.kotlinjt.shape.unpackResiduals

/**
 * A `VecI32{Int32CDP, <predictor>}` field: the packet exactly as it sits on the wire plus the
 * primal values after the declared predictor has been unpacked. Keeping the packet is what
 * makes [encode] a projection rather than a re-encode.
 */
data class Int32Vector(
    val packet: Int32Cdp,
    val predictor: Predictor,
) {
    /** The field's values, predictor applied. */
    val values: List<Int> by lazy { unpackResiduals(packet.values, predictor) }

    val size: Int get() = packet.valueCount

    fun encode(w: ByteWriter) = packet.encode(w)
}

/**
 * A `VecF64{Int64CDP, NULL}` field: the packet as read, plus its values reinterpreted as `F64`
 * bit patterns — which is what every NURBS collection of §12.1 says to do ("each deserialized
 * 64 bit integer number should be converted to bit wise equivalent 64 bit floating number").
 * The raw 64-bit symbols stay available through [packet], so nothing is lost to the
 * reinterpretation.
 */
data class Float64Vector(
    val packet: Int64Cdp,
) {
    /** The field's values as doubles. */
    val values: List<Double> by lazy { packet.values.map { Double.fromBits(it) } }

    val size: Int get() = packet.valueCount

    fun encode(w: ByteWriter) = packet.encode(w)
}

/**
 * A `VecI64{Int64CDP, NULL}` field whose values *are* integers (no `F64` reinterpretation) —
 * what the CAD tag vectors of Figure 154 carry.
 */
data class Int64Vector(
    val packet: Int64Cdp,
) {
    /** The field's values as 64-bit integers. */
    val values: List<Long> get() = packet.values

    val size: Int get() = packet.valueCount

    fun encode(w: ByteWriter) = packet.encode(w)
}
