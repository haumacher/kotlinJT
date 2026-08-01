package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.Predictor

/**
 * *Compressed CAD Tag Data* (§12.1.16, Figure 154): the CAD system's persistent identifiers for
 * a list of entities. "What constitutes a CAD Tag is outside the scope of the JT File format" —
 * so the tags themselves are numbers here, never interpreted.
 *
 * Two facts about the collection are fixture-established rather than documented:
 *
 * 1. **`I32 Data Length` counts from the Data Length field itself** (DESIGN.md delta 34):
 *    `offset(Data Length) + Data Length` lands exactly on the field after the collection.
 * 2. **Both tag vectors are always on the wire**, as an empty packet where the type does not
 *    occur. Figure 154's prose says `CAD Tags Type-1` / `Type-2` are "only present if there are
 *    Type-1/Type-2 CAD Tags in the CAD Tag Types vector"; all five Wireframe Rep bodies of the
 *    NIST fixture carry only type-1 tags and still write the four zero bytes of an empty Int64
 *    packet — without which the Data Length overshoots the collection by exactly four bytes.
 *
 * [tags] is `null` when the coded vectors did not decode; then [codedData] holds them verbatim
 * and the caller has named a note. Either way [encode] reproduces the collection byte for byte.
 */
data class CompressedCadTagData(
    /** U8 Version Number of the CADTag element. */
    val version: Int,
    /** The inner I32 Version Number of the collection. */
    val innerVersion: Int,
    /** The decoded tag vectors, or `null` when they were carried verbatim instead. */
    val tags: CadTagVectors?,
    /** The coded vectors verbatim; empty exactly when [tags] is present. */
    val codedData: Bytes,
) {
    /** The number of CAD tags this collection describes, `null` when the vectors are opaque. */
    val tagCount: Int? get() = tags?.tagTypes?.size

    fun encode(w: ByteWriter) {
        w.writeU8(version.toUByte())
        val body = ByteWriter(w.order)
        if (tags != null) tags.encode(body) else body.writeBytes(codedData)
        val bodyBytes = body.toByteArray()
        // Delta 34: the length spans the field itself, the inner version and the coded bytes.
        w.writeI32(8 + bodyBytes.size)
        w.writeI32(innerVersion)
        w.writeBytes(bodyBytes)
    }

    companion object {
        /**
         * Reads the collection. [expectedTagCount] is the count the containing structure
         * declares (§10: "there will be a CAD Tag for every Edge in the Wireframe Rep") or
         * `null` where the container does not fix it. When the coded vectors do not decode the
         * bytes are kept verbatim and [onOpaque] is called with the reason — the collection's
         * own Data Length makes the extent exact, so nothing can be lost either way.
         *
         * spec: Figure 154
         */
        fun read(
            r: ByteReader,
            expectedTagCount: Int?,
            externallyCompressed: Boolean,
            onOpaque: (String) -> Unit,
        ): CompressedCadTagData {
            val version = r.readU8().toInt()
            val dataLength = r.readI32()
            if (dataLength < 8 || dataLength - 4 > r.remaining) {
                throw JtFormatException("Compressed CAD Tag Data Length $dataLength does not fit the remaining ${r.remaining} bytes")
            }
            val innerVersion = r.readI32()
            val coded = r.readBytes(dataLength - 8)
            return try {
                val sub = ByteReader(coded, r.order)
                val tags = CadTagVectors.read(sub, expectedTagCount, externallyCompressed)
                if (sub.remaining != 0) {
                    throw JtFormatException("${sub.remaining} coded bytes of the CAD tag vectors were not consumed")
                }
                CompressedCadTagData(version, innerVersion, tags, Bytes.EMPTY)
            } catch (e: JtFormatException) {
                onOpaque(e.message ?: "CAD tag vectors did not decode")
                CompressedCadTagData(version, innerVersion, null, coded.toBytes())
            }
        }
    }
}

/**
 * The three coded vectors of Figure 154: one type identifier per tag (Table 72), then the
 * 32-bit tags and the 64-bit tags, each in the order they occur in [tagTypes].
 */
data class CadTagVectors(
    val tagTypes: Int32Vector,
    val type1Tags: Int32Vector,
    val type2Tags: Int64Vector,
) {
    /** Every tag as a 64-bit value, in [tagTypes] order (type-1 tags widened). */
    val tags: List<Long> by lazy {
        var i1 = 0
        var i2 = 0
        tagTypes.values.map { type ->
            if (type == TAG_TYPE_32_BIT) type1Tags.values[i1++].toLong() else type2Tags.values[i2++]
        }
    }

    fun encode(w: ByteWriter) {
        tagTypes.encode(w)
        type1Tags.encode(w)
        type2Tags.encode(w)
    }

    companion object {
        /** Table 72: 32-bit integer CAD tag. */
        const val TAG_TYPE_32_BIT = 1

        /** Table 72: 64-bit integer CAD tag. */
        const val TAG_TYPE_64_BIT = 2

        internal fun read(
            r: ByteReader,
            expectedTagCount: Int?,
            externallyCompressed: Boolean,
        ): CadTagVectors {
            val types = Int32Vector(Int32Cdp.readV10(r, externallyCompressed = externallyCompressed), Predictor.NONE)
            if (expectedTagCount != null && types.size != expectedTagCount) {
                throw JtFormatException("CAD Tag Types has ${types.size} entries, expected $expectedTagCount")
            }
            var type1 = 0
            var type2 = 0
            for (type in types.values) {
                when (type) {
                    TAG_TYPE_32_BIT -> type1 += 1
                    TAG_TYPE_64_BIT -> type2 += 1
                    else -> throw JtFormatException("CAD Tag Type $type is outside Table 72")
                }
            }
            val tags1 = Int32Vector(Int32Cdp.readV10(r, externallyCompressed = externallyCompressed), Predictor.NONE)
            val tags2 = Int64Vector(Int64Cdp.read(r, externallyCompressed = externallyCompressed))
            if (tags1.size != type1 || tags2.size != type2) {
                throw JtFormatException(
                    "CAD tag vectors hold ${tags1.size} type-1 and ${tags2.size} type-2 tags, " +
                        "but the type vector declares $type1 and $type2",
                )
            }
            return CadTagVectors(types, tags1, tags2)
        }
    }
}
