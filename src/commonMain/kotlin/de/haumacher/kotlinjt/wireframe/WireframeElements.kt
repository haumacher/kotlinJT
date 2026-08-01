package de.haumacher.kotlinjt.wireframe

import de.haumacher.kotlinjt.encoding.CompressedCadTagData
import de.haumacher.kotlinjt.encoding.CompressedCurveData
import de.haumacher.kotlinjt.encoding.Int32Vector
import de.haumacher.kotlinjt.encoding.NurbsCurve
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.ObjectTypeIds

/** One element of a Wireframe segment's element list (§10, Figure 103). */
sealed interface WireframeElement {
    /** The Object Type ID GUID from the element's Logical Element Header. */
    val objectTypeId: Guid
}

/** A Wireframe element whose body decoded into typed fields. */
sealed interface TypedWireframeElement : WireframeElement {
    /** The I32 Object ID of the element header (Figure 18). */
    val objectId: Int
}

/**
 * A Wireframe element carried verbatim: the type GUID is outside Annex A, its wire layout is
 * not established for this file's generation, or the body did not parse. A named
 * [de.haumacher.kotlinjt.LoadNote] always says which — and [body] keeps every byte, so
 * re-serialization stays a projection.
 */
data class OpaqueWireframeElement(
    override val objectTypeId: Guid,
    /** The Object Base Type byte the body starts with, `null` for an empty body. */
    val scannedBaseType: Int?,
    val body: Bytes,
) : WireframeElement

/**
 * **Wireframe Rep Element** (§10.1, Figure 104): a part's precise 3D wireframe — one
 * topological Edge list mapped onto a list of model-coordinate-space NURBS curves, plus the
 * CAD system's persistent Edge identifiers.
 *
 * Layout notes, all fixture-established against the five Wireframe Rep bodies of the NIST 10.5
 * file (exact byte consumption on every one of them, plus the count cross-checks inside
 * [CompressedCurveData]):
 *
 * - The **Version Number is one byte**, as §10.1's field description says — Figure 104's box
 *   contradicts it with `I16`. Reading two bytes misaligns the Edge Count in all five bodies.
 * - The two `VecI32` vectors use the **NULL predictor**, which is exactly the correction
 *   Revision B of the reference records ("Lag1 is replaced by NULL in two places"); the JT 9.5
 *   generation's Figure 130 does use Lag1.
 * - Edge Tag Counter and CAD Tags Flag follow the curve data, not precede it.
 */
data class WireframeRepElement(
    override val objectId: Int,
    /** U8 Version Number (1 in every fixture body). */
    val version: Int,
    /** I32 Edge Count: the number of topological Edge entities. */
    val edgeCount: Int,
    /** I32 MCS Curve Count: the number of distinct model-space curves. */
    val mcsCurveCount: Int,
    /** The MCS curve index of each Edge; `null` when [edgeCount] is 0 (no vectors on the wire). */
    val mcsCurveIndices: Int32Vector?,
    /** The identifier tag of each Edge; `null` when [edgeCount] is 0. */
    val edgeTags: Int32Vector?,
    /** The curve geometry (Figure 105); `null` when [mcsCurveCount] is 0. */
    val mcsCurves: CompressedCurveData?,
    /** I32 Edge Tag Counter: the next free Edge tag value. */
    val edgeTagCounter: Int,
    /** U32 CAD Tags Flag; 1 means [cadTagData] is present. */
    val cadTagsFlag: UInt,
    /** Wireframe Rep CAD Tag Data (Figure 106), present exactly when [cadTagsFlag] is 1. */
    val cadTagData: CompressedCadTagData?,
) : TypedWireframeElement {
    override val objectTypeId: Guid get() = ObjectTypeIds.WIREFRAME_REP_ELEMENT

    /** The NURBS curves of this rep, projected per curve (empty when there are none). */
    val curves: List<NurbsCurve> get() = mcsCurves?.curves ?: emptyList()

    /** The CAD tag of each Edge, in Edge order; empty when no CAD tag data is stored. */
    val edgeCadTags: List<Long> get() = cadTagData?.tags?.tags ?: emptyList()
}
