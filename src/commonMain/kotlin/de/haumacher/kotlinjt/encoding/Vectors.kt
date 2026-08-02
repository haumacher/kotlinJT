package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.Predictor
import de.haumacher.kotlinjt.shape.unpackResiduals

/**
 * An `Int32` vector field of some compressed-data-packet generation: the values the field
 * decodes to, and the ability to put the field back on the wire byte-identically.
 *
 * The interface exists so a collection whose *shape* is generation-independent — the NURBS
 * machinery of §8.1.13–§8.1.15, identical box for box in 9.5 and v10 — can be modelled once
 * while each generation supplies its own packet type. It deliberately exposes no packet:
 * choosing the generation is the reading call site's job, and everything downstream sees values.
 */
sealed interface IntVectorField {
    /** The field's values, predictor applied. */
    val values: List<Int>

    val size: Int

    fun encode(w: ByteWriter)
}

/** The same, for an `F64` vector field. */
sealed interface DoubleVectorField {
    val values: List<Double>

    val size: Int

    fun encode(w: ByteWriter)
}

/**
 * A `VecI32{Int32CDP2, <predictor>}` field — the JT 9 **Mk. 2** packet of 9.5 §8.1.2 and the
 * third-generation v10 packet of §12.1.1, both of which `Int32Cdp` reads. The packet sits here
 * exactly as it was on the wire, plus the primal values after the declared predictor has been
 * unpacked; keeping the packet is what makes [encode] a projection rather than a re-encode.
 *
 * A `VecI32{Int32CDP, …}` field of a 9.5 figure is **not** this type — see [Int32VectorMk1].
 */
data class Int32Vector(
    val packet: Int32Cdp,
    val predictor: Predictor,
) : IntVectorField {
    override val values: List<Int> by lazy { unpackResiduals(packet.values, predictor) }

    override val size: Int get() = packet.valueCount

    override fun encode(w: ByteWriter) = packet.encode(w)
}

/**
 * A `VecI32{Int32CDP, <predictor>}` field of a 9.5 figure — the **Mk. 1** packet of §8.1.1.
 * Nothing in the bytes distinguishes it from [Int32Vector]; the figure's notation does, which
 * is why the two are separate types (see [Int32CdpMk1]).
 */
data class Int32VectorMk1(
    val packet: Int32CdpMk1,
    val predictor: Predictor,
) : IntVectorField {
    override val values: List<Int> by lazy { unpackResiduals(packet.values, predictor) }

    override val size: Int get() = packet.valueCount

    override fun encode(w: ByteWriter) = packet.encode(w)
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
) : DoubleVectorField {
    override val values: List<Double> by lazy { packet.values.map { Double.fromBits(it) } }

    override val size: Int get() = packet.valueCount

    override fun encode(w: ByteWriter) = packet.encode(w)
}

/**
 * A `VecF64{Float64CDP, NULL}` field of a 9.5 figure — the natively `F64` packet of §8.1.3.
 * There is no bit-reinterpretation step here, because there is nothing to reinterpret: the
 * packet's symbols *are* doubles. The v10 form of the same field is [Float64Vector].
 */
data class Float64CdpVector(
    val packet: Float64Cdp,
) : DoubleVectorField {
    override val values: List<Double> get() = packet.values

    override val size: Int get() = packet.valueCount

    override fun encode(w: ByteWriter) = packet.encode(w)
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
