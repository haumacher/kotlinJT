package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes

/**
 * Segment type codes that real producers write and **Table 6 does not define**. NX 10.5 writes
 * two of them into the NIST fixture — eight segments of type 23 and one of type 31 — and no
 * revision of the JT reference at hand (v10.0 Rev C Table 6, v9.5 Rev A Table 3) lists either.
 *
 * Nothing here claims to know what such a segment *means*. What it does is stop them from being
 * anonymous: [labelFor] gives the inventory an honest name instead of "UNKNOWN", and the two
 * GUIDs below record the element types their bodies frame — established from the bytes, with the
 * evidence written down in DESIGN.md and pinned by `UndefinedSegmentTypeTest`.
 *
 * Their payloads are always preserved verbatim; the named [LoadNote.UnknownSegmentType] stays.
 */
object UndefinedSegmentTypes {
    private fun guid(
        d1: Long,
        d2: Int,
        d3: Int,
        vararg tail: Int,
    ): Guid {
        require(tail.size == 8)
        return Guid(d1.toUInt(), d2.toUShort(), d3.toUShort(), ByteArray(8) { tail[it].toByte() }.toBytes())
    }

    /**
     * The single element type the eight type-23 segments of the NIST fixture frame. The GUID
     * appears in no table of either reference. Every one of the eight bodies is LZMA-compressed,
     * frames exactly one element of this type with Object Base Type 9 ("JtBase") and object id 0,
     * and ends in the six-byte empty Property Table of Figure 78. In the LSG each is referenced
     * by a Late Loaded Property Atom keyed `JT_LLPROP_FERIT`, sitting on exactly the eight Part
     * Nodes that also carry a `JT_LLPROP_XTBREP` reference — so a type-23 segment accompanies XT
     * B-Rep, and that is the whole of what the bytes prove. The body is carried opaquely.
     */
    val FERIT_ELEMENT: Guid = guid(0xca7e6f89, 0x97c8, 0x47f0, 0x9f, 0xca, 0x16, 0x99, 0x0c, 0xfb, 0xe2, 0x17)

    /** Segment type of the segments framing a [FERIT_ELEMENT] (referenced by `JT_LLPROP_FERIT`). */
    const val FERIT_SEGMENT_TYPE: Int = 23

    /**
     * Segment type of the NIST fixture's single type-31 segment. Unlike type 23 its content is
     * fully documented — the body frames 14 *String Property Atom Elements* (Figure 71, Annex A),
     * key/value pairs naming the producing tool chain (`JT_PROP_JTOPENTOOLKIT`,
     * `JT_PROP_XT_TOOLKIT`, `JT_PROP_PARASOLID…`, `JT_PROP_DIRECTMODEL…`, `JT_PROP_BODYSHOP_…`)
     * with their versions. No Late Loaded Property Atom references it: the segment stands alone
     * in the TOC. What the *segment type* is for stays undocumented, so the payload is preserved
     * verbatim and nothing is interpreted.
     */
    const val TRANSLATOR_PROPERTY_SEGMENT_TYPE: Int = 31

    /** All undefined segment type codes observed in a real producer's output, with their evidence. */
    val observed: Map<Int, String> =
        mapOf(
            FERIT_SEGMENT_TYPE to "undefined type 23 (NX 10.5; JT_LLPROP_FERIT, accompanies XT B-Rep)",
            TRANSLATOR_PROPERTY_SEGMENT_TYPE to "undefined type 31 (NX 10.5; String Property Atoms naming the tool chain)",
        )

    /**
     * The honest label for a segment type code: Table 6's name where it defines one, otherwise
     * "undefined type N" — never "UNKNOWN".
     */
    fun labelFor(code: Int): String = SegmentKind.fromCode(code)?.label ?: "undefined type $code"
}
