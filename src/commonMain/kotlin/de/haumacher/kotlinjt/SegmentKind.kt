package de.haumacher.kotlinjt

/**
 * The segment types of the JT v10 reference, Table 6 — together with whether the type
 * supports segment-wide compression of all its element data ("Compression" column). The
 * v9.5 table is a subset with the same codes and the same compression column; the delta is
 * only which algorithm the compressed types use (ZLIB in v9, LZMA in v10 — see DESIGN.md).
 */
enum class SegmentKind(val code: Int, val label: String, val compressible: Boolean) {
    LOGICAL_SCENE_GRAPH(1, "Logical Scene Graph", true),
    JT_BREP(2, "JT B-Rep", true),
    PMI_DATA(3, "PMI Data", true),
    META_DATA(4, "Meta Data", true),
    SHAPE(6, "Shape", false),
    SHAPE_LOD0(7, "Shape LOD0", false),
    SHAPE_LOD1(8, "Shape LOD1", false),
    SHAPE_LOD2(9, "Shape LOD2", false),
    SHAPE_LOD3(10, "Shape LOD3", false),
    SHAPE_LOD4(11, "Shape LOD4", false),
    SHAPE_LOD5(12, "Shape LOD5", false),
    SHAPE_LOD6(13, "Shape LOD6", false),
    SHAPE_LOD7(14, "Shape LOD7", false),
    SHAPE_LOD8(15, "Shape LOD8", false),
    SHAPE_LOD9(16, "Shape LOD9", false),
    XT_BREP(17, "XT B-Rep", true),
    WIREFRAME(18, "Wireframe Representation", true),
    ULP(20, "ULP", true),
    LWPA(24, "LWPA", true),
    MULTI_XT_BREP(30, "MultiXT B-Rep", true),
    STEP_BREP(32, "STEP B-Rep", true),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): SegmentKind? = byCode[code]
    }
}
