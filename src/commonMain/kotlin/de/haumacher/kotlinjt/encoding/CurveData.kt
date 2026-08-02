package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.Predictor

/**
 * The knot-vector categories of Table 68, in the order their exist flags appear in
 * *Compressed Entity List for Non-Trivial Knot Vector* (Figure 148). The category decides how
 * many values a curve's knot vector stores — the formula §12.1.13 prints, which is what
 * [CompressedCurveData] validates its knot vector length against.
 */
enum class KnotType(val index: Int) {
    /** Even count, `[0:1]` range, interior knots in adjacent pairs. */
    EVEN_COUNT_UNIT_RANGE(0),

    /** Even count, `[x1:x2]` range, interior knots in adjacent pairs. */
    EVEN_COUNT_ARBITRARY_RANGE(1),

    /** Odd count, `[0:1]` range. */
    ODD_COUNT_UNIT_RANGE(2),

    /** Odd count, `[x1:x2]` range. */
    ODD_COUNT_ARBITRARY_RANGE(3),

    ;

    /**
     * The number of *stored* knot values for a curve of this category (§12.1.13's
     * reconstruction sketch), given its knot count and clamping multiplicity.
     */
    fun storedValueCount(
        knotCount: Int,
        clamping: Int,
    ): Int {
        val interior = knotCount - 2 * clamping
        if (interior < 0) throw JtFormatException("knot count $knotCount is below the clamping $clamping")
        return when (this) {
            EVEN_COUNT_UNIT_RANGE -> interior / 2
            EVEN_COUNT_ARBITRARY_RANGE -> interior / 2 + 2
            ODD_COUNT_UNIT_RANGE -> interior
            ODD_COUNT_ARBITRARY_RANGE -> interior + 2
        }
    }

    companion object {
        fun ofIndex(index: Int): KnotType =
            entries.firstOrNull { it.index == index }
                ?: throw JtFormatException("knot type index $index is outside Table 68")
    }
}

/**
 * *Compressed Entity List for Non-Trivial Knot Vector* (v10 §12.1.13 Figure 148 == 9.5 §8.1.13
 * Figure 236): a four-entry flag vector (Table 68) followed by one entity-index list per set
 * flag. The flag vector's length is fixed at four by the spec ("Currently there are four knot
 * vector types, so this Entities of Knot Type Exist Flags vector should be of length four") and
 * validated as such.
 *
 * The two generations draw the same boxes and differ only in what fills them: v10 writes
 * `VecI32{Int32CDP, Lag1}` (its third-generation packet), 9.5 `VecI32{Int32CDP, `**`Stride1`**`}`
 * with the Mk. 1 packet of §8.1.1. [read] and [read95] are the two entry points; nothing in the
 * bytes chooses between them.
 */
data class NonTrivialKnotVectorEntityList(
    /** The exist flags exactly as read (four entries in every conforming file). */
    val existFlags: List<Int>,
    /** The entity index list per set flag, in flag order. */
    val entityIndices: List<Entry>,
) {
    /** One knot-type category with the entity indices that belong to it. */
    data class Entry(
        val knotType: KnotType,
        val indices: IntVectorField,
    )

    fun encode(w: ByteWriter) {
        w.writeI32(existFlags.size)
        for (flag in existFlags) w.writeI32(flag)
        for (entry in entityIndices) entry.indices.encode(w)
    }

    companion object {
        // spec: Figure 148
        internal fun read(
            r: ByteReader,
            externallyCompressed: Boolean,
        ): NonTrivialKnotVectorEntityList =
            read(r) {
                Int32Vector(Int32Cdp.readV10(it, externallyCompressed = externallyCompressed), Predictor.LAG1)
            }

        // spec: 9.5 Figure 236
        internal fun read95(r: ByteReader): NonTrivialKnotVectorEntityList =
            read(r) { Int32VectorMk1(Int32CdpMk1.read(it), Predictor.STRIDE1) }

        private fun read(
            r: ByteReader,
            indices: (ByteReader) -> IntVectorField,
        ): NonTrivialKnotVectorEntityList {
            val flagCount = r.readI32()
            if (flagCount != 4) {
                throw JtFormatException("Entities of Knot Type Exist Flags has $flagCount entries, Table 68 defines four")
            }
            val flags = List(flagCount) { r.readI32() }
            val entries = mutableListOf<Entry>()
            for ((index, flag) in flags.withIndex()) {
                when (flag) {
                    0 -> Unit
                    1 -> entries.add(Entry(KnotType.ofIndex(index), indices(r)))
                    else -> throw JtFormatException("Knot Type Exist Flag[$index] is $flag, Table 68 defines 0 and 1")
                }
            }
            return NonTrivialKnotVectorEntityList(flags, entries)
        }
    }
}

/**
 * *Compressed Control Point Weights Data* (v10 §12.1.14 Figure 149 == 9.5 §8.1.14 Figure 237).
 * Only the control points whose weight is not 1 store a value, so [weightIndices] says which —
 * "JT file loaders/readers can infer that the Weight Value is 1 for Control Points that don't
 * have a Weight value stored", identical prose in both generations.
 *
 * Generation delta: v10 `VecI32{Int32CDP, Lag1}` + `VecF64{Int64CDP, NULL}` (with the bitwise
 * `I64`→`F64` reinterpretation the consumer applies); 9.5
 * `VecI32{Int32CDP, `**`Stride1`**`}` + `VecF64{`**`Float64CDP`**`, NULL}`, natively `F64`.
 */
data class CompressedControlPointWeights(
    /** I32 Weights Count: the total number of weights (one per rational control point). */
    val weightsCount: Int,
    val weightIndices: IntVectorField,
    val weightValues: DoubleVectorField,
) {
    /**
     * The weight of every one of the [weightsCount] weighted control points, with the
     * unstored ones filled in as 1.0 exactly as §12.1.14 instructs.
     */
    val weights: List<Double> by lazy {
        val out = MutableList(weightsCount) { 1.0 }
        for ((i, index) in weightIndices.values.withIndex()) out[index] = weightValues.values[i]
        out
    }

    fun encode(w: ByteWriter) {
        w.writeI32(weightsCount)
        weightIndices.encode(w)
        weightValues.encode(w)
    }

    companion object {
        // spec: Figure 149
        internal fun read(
            r: ByteReader,
            externallyCompressed: Boolean,
        ): CompressedControlPointWeights =
            read(
                r,
                { Int32Vector(Int32Cdp.readV10(it, externallyCompressed = externallyCompressed), Predictor.LAG1) },
                { Float64Vector(Int64Cdp.read(it, externallyCompressed = externallyCompressed)) },
            )

        // spec: 9.5 Figure 237
        internal fun read95(r: ByteReader): CompressedControlPointWeights =
            read(
                r,
                { Int32VectorMk1(Int32CdpMk1.read(it), Predictor.STRIDE1) },
                { Float64CdpVector(Float64Cdp.read(it)) },
            )

        private fun read(
            r: ByteReader,
            indexVector: (ByteReader) -> IntVectorField,
            valueVector: (ByteReader) -> DoubleVectorField,
        ): CompressedControlPointWeights {
            val count = r.readI32()
            if (count < 0) throw JtFormatException("Weights Count $count is negative")
            val indices = indexVector(r)
            val values = valueVector(r)
            if (indices.size != values.size) {
                throw JtFormatException("${indices.size} weight indices for ${values.size} weight values")
            }
            var previous = -1
            for (index in indices.values) {
                if (index <= previous || index >= count) {
                    throw JtFormatException("weight index $index is not ascending within [0, $count)")
                }
                previous = index
            }
            return CompressedControlPointWeights(count, indices, values)
        }
    }
}

/**
 * *Compressed Curve Data* (§12.1.15, Figure 150): the compressed NURBS curve list shared by
 * JT B-Rep and the Wireframe Rep. Table 69 defines exactly one curve base type (NURBS), so a
 * body declaring anything else refuses the typed decode instead of reading a layout the spec
 * does not describe.
 *
 * Every stored count is cross-validated at decode: the per-curve vectors all have
 * `curveCount` entries; the control point vector holds exactly `spatialDimension` coordinates
 * per control point (rational curves store *non-homogeneous* coordinates, so a rational and a
 * non-rational curve of equal control point count store equally many coordinates); the weight
 * count equals the summed control point count of the rational curves; and the knot vector holds
 * exactly the number of values Table 68's category formula predicts for the curves listed in
 * [nonTrivialKnotVectors]. All four hold to the value on every Wireframe Rep body of the NIST
 * fixture — which is what makes this layout fixture-established rather than merely plausible.
 */
data class CompressedCurveData(
    val nonTrivialKnotVectors: NonTrivialKnotVectorEntityList,
    val curveBaseTypes: IntVectorField,
    val degrees: IntVectorField,
    val controlPointCounts: IntVectorField,
    val controlPointDimensionality: IntVectorField,
    /**
     * `NURBS Curve Empty Fields` (v10) / `NURBS Curve Reserved Fields` (9.5) — the same box
     * renamed: one reserved entry per curve, preserved as read.
     */
    val emptyFields: IntVectorField,
    val controlPointWeights: CompressedControlPointWeights,
    val controlPoints: DoubleVectorField,
    val knotVectors: DoubleVectorField,
    /** 2 for UV (parameter-space) curves, 3 for MCS/XYZ curves — Tables 70 and 71. */
    val spatialDimension: Int,
) {
    /** The number of curves in the list. */
    val curveCount: Int get() = curveBaseTypes.size

    /**
     * The per-curve projection: degree, control point coordinates (in
     * [spatialDimension]-tuples), weights (1.0 where the wire stores none) and the *stored*
     * knot values with their Table 68 category.
     */
    val curves: List<NurbsCurve> by lazy { buildCurves() }

    fun encode(w: ByteWriter) {
        nonTrivialKnotVectors.encode(w)
        curveBaseTypes.encode(w)
        degrees.encode(w)
        controlPointCounts.encode(w)
        controlPointDimensionality.encode(w)
        emptyFields.encode(w)
        controlPointWeights.encode(w)
        controlPoints.encode(w)
        knotVectors.encode(w)
    }

    private fun buildCurves(): List<NurbsCurve> {
        val knotTypeOf = mutableMapOf<Int, KnotType>()
        for (entry in nonTrivialKnotVectors.entityIndices) {
            for (index in entry.indices.values) knotTypeOf[index] = entry.knotType
        }
        val coords = controlPoints.values
        val allWeights = controlPointWeights.weights
        var coordinateCursor = 0
        var weightCursor = 0
        var knotCursor = 0
        return List(curveCount) { curve ->
            val degree = degrees.values[curve]
            val points = controlPointCounts.values[curve]
            val rational = controlPointDimensionality.values[curve] == spatialDimension + 1
            val coordinates = coords.subList(coordinateCursor, coordinateCursor + points * spatialDimension).toList()
            coordinateCursor += points * spatialDimension
            val weights =
                if (rational) {
                    allWeights.subList(weightCursor, weightCursor + points).toList().also { weightCursor += points }
                } else {
                    List(points) { 1.0 }
                }
            val knotType = knotTypeOf[curve]
            val storedKnots =
                if (knotType == null) {
                    emptyList()
                } else {
                    val stored = knotType.storedValueCount(points + degree + 1, degree + 1)
                    knotVectors.values.subList(knotCursor, knotCursor + stored).toList().also { knotCursor += stored }
                }
            NurbsCurve(degree, coordinates, weights, knotType, storedKnots)
        }
    }

    companion object {
        /**
         * Reads the collection for [curveCount] curves. [uvCurves] selects the dimensionality
         * table: Table 70 (2 = non-rational, 3 = rational) for parameter-space curves,
         * Table 71 (3 / 4) for model-space (XYZ) curves.
         *
         * spec: Figure 150
         */
        fun read(
            r: ByteReader,
            curveCount: Int,
            externallyCompressed: Boolean,
            uvCurves: Boolean = false,
        ): CompressedCurveData =
            read(
                r,
                curveCount,
                uvCurves,
                { NonTrivialKnotVectorEntityList.read(it, externallyCompressed) },
                { Int32Vector(Int32Cdp.readV10(it, externallyCompressed = externallyCompressed), Predictor.NONE) },
                { CompressedControlPointWeights.read(it, externallyCompressed) },
                { Float64Vector(Int64Cdp.read(it, externallyCompressed = externallyCompressed)) },
            )

        /**
         * The same collection in the JT 9 generation (9.5 §8.1.15, Figure 238). Four deltas
         * against [read], all of them in what fills the boxes rather than in the boxes: the five
         * per-curve vectors are `VecI32{Int32CDP, `**`Lag1`**`}` where v10 writes them with the
         * NULL predictor; the packet is the **Mk. 1** one of §8.1.1; the fifth vector is named
         * *NURBS Curve Reserved Fields*; and the control points and knot vectors are
         * `VecF64{`**`Float64CDP`**`, NULL}`, natively `F64` with no reinterpretation step.
         *
         * There is no `externallyCompressed` parameter: 9.5 §8 has no external-compression
         * branch anywhere (see DESIGN.md delta 37) — that rule is v10's.
         *
         * spec: 9.5 Figure 238
         */
        fun read95(
            r: ByteReader,
            curveCount: Int,
            uvCurves: Boolean = false,
        ): CompressedCurveData =
            read(
                r,
                curveCount,
                uvCurves,
                { NonTrivialKnotVectorEntityList.read95(it) },
                { Int32VectorMk1(Int32CdpMk1.read(it), Predictor.LAG1) },
                { CompressedControlPointWeights.read95(it) },
                { Float64CdpVector(Float64Cdp.read(it)) },
            )

        private fun read(
            r: ByteReader,
            curveCount: Int,
            uvCurves: Boolean,
            knotList: (ByteReader) -> NonTrivialKnotVectorEntityList,
            perCurveVector: (ByteReader) -> IntVectorField,
            weightsData: (ByteReader) -> CompressedControlPointWeights,
            valueVector: (ByteReader) -> DoubleVectorField,
        ): CompressedCurveData {
            val spatialDimension = if (uvCurves) 2 else 3
            val knots = knotList(r)

            fun vector(name: String): IntVectorField {
                val v = perCurveVector(r)
                if (v.size != curveCount) {
                    throw JtFormatException("$name has ${v.size} entries for $curveCount curves")
                }
                return v
            }
            val baseTypes = vector("Curve Base Types")
            for (type in baseTypes.values) {
                if (type != CURVE_BASE_TYPE_NURBS) {
                    throw JtFormatException("Curve Base Type $type is outside Table 69 (only NURBS = 1 is defined)")
                }
            }
            val degrees = vector("NURBS Curve Degrees")
            val pointCounts = vector("NURBS Curve Control Point Counts")
            val dimensionality = vector("NURBS Curve Control Point Dimensionality")
            val emptyFields = vector("NURBS Curve Empty Fields")
            for (curve in 0 until curveCount) {
                val degree = degrees.values[curve]
                val points = pointCounts.values[curve]
                val dimension = dimensionality.values[curve]
                if (degree < 1) throw JtFormatException("curve $curve declares degree $degree")
                if (points < 1) throw JtFormatException("curve $curve declares $points control points")
                if (dimension != spatialDimension && dimension != spatialDimension + 1) {
                    throw JtFormatException(
                        "curve $curve declares control point dimensionality $dimension, " +
                            "outside {$spatialDimension, ${spatialDimension + 1}}",
                    )
                }
            }
            val weights = weightsData(r)
            val expectedWeights =
                (0 until curveCount).sumOf { curve ->
                    if (dimensionality.values[curve] == spatialDimension + 1) pointCounts.values[curve] else 0
                }
            if (weights.weightsCount != expectedWeights) {
                throw JtFormatException(
                    "Weights Count ${weights.weightsCount} is not the summed control point count " +
                        "$expectedWeights of the rational curves",
                )
            }
            val controlPoints = valueVector(r)
            val expectedCoordinates = pointCounts.values.sumOf { it } * spatialDimension
            if (controlPoints.size != expectedCoordinates) {
                throw JtFormatException(
                    "Control Points holds ${controlPoints.size} coordinates, expected $expectedCoordinates " +
                        "($spatialDimension per control point)",
                )
            }
            val knotVectors = valueVector(r)
            var expectedKnotValues = 0
            val seen = mutableSetOf<Int>()
            for (entry in knots.entityIndices) {
                for (index in entry.indices.values) {
                    if (index < 0 || index >= curveCount) {
                        throw JtFormatException("non-trivial knot vector curve index $index is outside [0, $curveCount)")
                    }
                    if (!seen.add(index)) {
                        throw JtFormatException("curve $index appears in two knot type lists")
                    }
                    val degree = degrees.values[index]
                    expectedKnotValues += entry.knotType.storedValueCount(pointCounts.values[index] + degree + 1, degree + 1)
                }
            }
            if (knotVectors.size != expectedKnotValues) {
                throw JtFormatException(
                    "Knot Vectors holds ${knotVectors.size} values, expected $expectedKnotValues " +
                        "for the ${seen.size} non-trivial knot vector curves (Table 68 formula)",
                )
            }
            return CompressedCurveData(
                knots,
                baseTypes,
                degrees,
                pointCounts,
                dimensionality,
                emptyFields,
                weights,
                controlPoints,
                knotVectors,
                spatialDimension,
            )
        }

        /** Table 69: the only defined Curve Base Type. */
        const val CURVE_BASE_TYPE_NURBS = 1
    }
}

/**
 * One curve of a [CompressedCurveData] list, projected out of the parallel wire vectors.
 *
 * [storedKnotValues] is what the wire carries, together with the Table 68 [knotType] it was
 * stored under; assembling the full knot vector from them is deliberately **not** done here.
 * §12.1.13 prints the interior-filling step of its reconstruction sketch only for the
 * `[x1:x2]` categories, and for a *trivial* knot vector (no [knotType], the majority in the
 * NIST fixture) the reference gives only the two defining cases and no reconstruction at all —
 * so a full vector would be part inference. Its time comes with a consumer that evaluates
 * curves; see the deferral table in DESIGN.md.
 */
data class NurbsCurve(
    val degree: Int,
    /** Control point coordinates, `spatialDimension` values per point, non-homogeneous. */
    val coordinates: List<Double>,
    /** One weight per control point; 1.0 where the wire stores none (§12.1.14). */
    val weights: List<Double>,
    /** The Table 68 knot category, `null` for a curve with a trivial knot vector. */
    val knotType: KnotType?,
    /** The knot values stored for this curve; empty when [knotType] is `null`. */
    val storedKnotValues: List<Double>,
) {
    /** The number of control points. */
    val controlPointCount: Int get() = weights.size

    /** The full knot vector's length, `controlPointCount + degree + 1`. */
    val knotCount: Int get() = controlPointCount + degree + 1

    /** Whether any control point carries a weight other than 1.0. */
    val isRational: Boolean get() = weights.any { it != 1.0 }
}
