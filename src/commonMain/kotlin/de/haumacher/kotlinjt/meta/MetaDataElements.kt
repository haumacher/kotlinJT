package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.encoding.CompressedCadTagData
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.Vec3F32

/**
 * One element of a Meta Data segment (§11, Table 6 type 4) or of a PMI Data segment (type 3,
 * which Annex H says is parsed "exactly the same as a PMI Manager Meta Data Element"): a
 * typed, immutable mirror of the spec's data collections, or an [OpaqueMetaDataElement]
 * carrying raw bytes with a named note.
 *
 * As in the LSG and Shape LOD models, an element body either parses completely — byte-exact
 * re-serialization guaranteed — or is carried opaquely. The two exceptions are *named*
 * partial carries whose unconsumed bytes are preserved verbatim, so re-serialization stays a
 * projection: an unrecognized Property Value Type (see [MetaPropertyValue.Unrecognized]) and
 * the undocumented block NX 10.5 writes after a PMI Manager's fonts (see
 * [PmiManagerMetaDataElement.undocumentedTail]).
 */
sealed class MetaDataElement {
    /** The Object Type ID (Annex A) this element was framed with. */
    abstract val objectTypeId: Guid
}

/**
 * A meta data element carried opaquely: an unknown type, a type without an established wire
 * layout for the file's generation, or a failed decode. [body] preserves everything after the
 * Object Type ID byte-faithfully.
 */
data class OpaqueMetaDataElement(
    override val objectTypeId: Guid,
    /** The Object Base Type byte as scanned, `null` when the body is empty. */
    val objectBaseType: Int?,
    /** The element body after the 16 Object Type ID bytes, preserved verbatim. */
    val body: Bytes,
) : MetaDataElement()

/** A fully decoded meta data element; [objectId] is what property atoms reference. */
sealed class TypedMetaDataElement : MetaDataElement() {
    abstract val objectId: Int
}

// ---------------------------------------------------------------------------
// Property Proxy Meta Data Element (§11.1, Figures 108/109)
// ---------------------------------------------------------------------------

/**
 * A date value of the property bag (Figure 109): six I16 fields, stored exactly as the wire
 * carries them. Deliberately *not* projected onto a platform date type — `commonMain` stays
 * platform-free, and the spec fixes no calendar, time zone or validity rules for these
 * fields (the LSG's Date Property Atom carries the same six numbers).
 */
data class JtDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/** One typed value of a Property Proxy property bag (Table 53). */
sealed class MetaPropertyValue {
    /** Property Value Type 0 ("Unknown"): the key is on the wire, no value follows. */
    object None : MetaPropertyValue() {
        override fun toString(): String = "None"
    }

    /** Property Value Type 1: an MbString value. */
    data class Text(val value: String) : MetaPropertyValue()

    /** Property Value Type 2: an I32 value. */
    data class Integer(val value: Int) : MetaPropertyValue()

    /** Property Value Type 3: an F32 value. */
    data class Real(val value: Float) : MetaPropertyValue()

    /** Property Value Type 4: a Date Property Value (Figure 109). */
    data class Date(val value: JtDate) : MetaPropertyValue()

    /**
     * A Property Value Type outside Table 53. Its length is unknown, so the property list
     * cannot continue past it: [remainder] carries every remaining byte of the element body
     * verbatim (including any bag terminator), and a named note reports the type code. The
     * keys decoded before it stay available — nothing is lost and nothing is guessed.
     */
    data class Unrecognized(
        val typeCode: Int,
        val remainder: Bytes,
    ) : MetaPropertyValue()
}

/** One key/value pair of a Property Proxy property bag (Figure 108). */
data class MetaProperty(
    val key: String,
    val value: MetaPropertyValue,
)

/**
 * Property Proxy Meta Data Element (Figure 108): the late-loaded property bag of a Meta Data
 * Node or Part Node, referenced through a `JT_LLPROP_METADATA` Late Loaded Property Atom.
 *
 * The bag is an ordered list — duplicate keys are legal on the wire and preserved as read;
 * [propertyMap] is the convenience projection for consumers that want lookup (first wins).
 */
data class PropertyProxyMetaDataElement(
    override val objectId: Int,
    val version: Int,
    val properties: List<MetaProperty>,
    /**
     * Whether the bag ended with the empty-key terminator Figure 108's loop condition
     * demands. `false` only when an [MetaPropertyValue.Unrecognized] value swallowed the
     * remainder.
     */
    val terminated: Boolean = true,
) : TypedMetaDataElement() {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.PROPERTY_PROXY_META_DATA_ELEMENT

    /** First value per key, in wire order — the lookup view of [properties]. */
    val propertyMap: Map<String, MetaPropertyValue>
        get() {
            val result = LinkedHashMap<String, MetaPropertyValue>()
            for (property in properties) {
                if (!result.containsKey(property.key)) result[property.key] = property.value
            }
            return result
        }
}

// ---------------------------------------------------------------------------
// PMI Manager Meta Data Element (§11.2, Figures 110-131)
// ---------------------------------------------------------------------------

/** One attribute value of a PMI Design Group (Figure 112, Table 54). */
sealed class PmiDesignGroupAttributeValue {
    /** Attribute Type 1. */
    data class Integer(val value: Int) : PmiDesignGroupAttributeValue()

    /** Attribute Type 2. */
    data class Double(val value: kotlin.Double) : PmiDesignGroupAttributeValue()

    /** Attribute Type 3: an index into the PMI String Table (−1 = no string). */
    data class StringId(val stringId: Int) : PmiDesignGroupAttributeValue()
}

/** Design Group Attribute (Figure 112): a group property with label and description. */
data class PmiDesignGroupAttribute(
    val value: PmiDesignGroupAttributeValue,
    /** Index into the PMI String Table, −1 = no string. */
    val labelStringId: Int,
    /** Index into the PMI String Table, −1 = no string. */
    val descriptionStringId: Int,
)

/** One entry of PMI Design Group Entities (Figure 111). */
data class PmiDesignGroup(
    /** Index into the PMI String Table, −1 = no string. */
    val nameStringId: Int,
    val attributes: List<PmiDesignGroupAttribute>,
)

/**
 * One entry of PMI Associations (Figure 113). Source and destination are the packed I32s of
 * Table 55 — carried as read; [sourceEntityType] and friends expose the bit fields without
 * committing to a value set (Table 55's list is open-ended: the fixture writes types the
 * v10.0 table does not name).
 */
data class PmiAssociation(
    val sourceData: Int,
    /** Index into the PMI String Table, −1 = the entity lives on this node's own segment. */
    val sourceOwningEntityStringId: Int,
    /** Table 56 reason code, carried as read (the value set is open). */
    val reasonCode: Int,
    val destinationData: Int,
    /** Index into the PMI String Table, −1 = the entity lives on this node's own segment. */
    val destinationOwningEntityStringId: Int,
) {
    /** Bits 0–23 of [sourceData]: the entity identifier or index (Table 55). */
    val sourceEntityId: Int get() = sourceData and 0xFFFFFF

    /** Bits 24–30 of [sourceData]: the PMI / B-Rep entity type (Table 55). */
    val sourceEntityType: Int get() = (sourceData shr 24) and 0x7F

    /** Bit 31 of [sourceData]: the Indirect Identifier Flag (Table 55). */
    val sourceIndirect: Boolean get() = (sourceData shr 31) and 1 == 1

    val destinationEntityId: Int get() = destinationData and 0xFFFFFF

    val destinationEntityType: Int get() = (destinationData shr 24) and 0x7F

    val destinationIndirect: Boolean get() = (destinationData shr 31) and 1 == 1
}

/** One entry of PMI User Attributes (Figure 114): a pair of PMI String Table indices. */
data class PmiUserAttribute(
    val keyStringId: Int,
    val valueStringId: Int,
)

/** Key PMI Property Atom (Figure 118): the encoded string plus the Table 59 hidden flag. */
data class PmiPropertyAtom(
    val value: String,
    /** Table 59: 0 = not hidden, 1 = hidden. */
    val hiddenFlag: Int,
)

/** PMI Property (Figure 117): a key/value pair of [PmiPropertyAtom]s. */
data class PmiProperty(
    val key: PmiPropertyAtom,
    val value: PmiPropertyAtom,
)

/** One entry of PMI Model Views (Figure 116): a stored camera plus its properties. */
data class PmiModelView(
    val eyeDirection: Vec3F32,
    /** Camera rotation angle about [eyeDirection], in degrees. */
    val angle: Float,
    val eyePosition: Vec3F32,
    val targetPoint: Vec3F32,
    /** X/Y/Z rotation angles of the model's axes, in degrees. */
    val viewAngle: Vec3F32,
    val viewportDiameter: Float,
    /** F32 Empty Field, preserved as read (clause 13 Empty Field). */
    val emptyFieldF32: Float,
    /** I32 Empty Field, preserved as read. */
    val emptyFieldI32: Int,
    /** Table 57: 1 = this is the active Model View. */
    val activeFlag: Int,
    val viewId: Int,
    /** Index into the PMI String Table, −1 = no string. */
    val viewNameStringId: Int,
    val properties: List<PmiProperty>,
)

/** 2D-Reference Frame (Figure 122): the plane a 2D PMI entity is displayed on. */
data class Pmi2dReferenceFrame(
    val origin: Vec3F32,
    val xAxisPoint: Vec3F32,
    val yAxisPoint: Vec3F32,
)

/** Text Box (Figure 124): the 2D box a text fits in, in 2D-Reference Frame coordinates. */
data class PmiTextBox(
    val originX: Float,
    val originY: Float,
    val lowerRightX: Float,
    val lowerRightY: Float,
    val upperLeftX: Float,
    val upperLeftY: Float,
)

/**
 * Text Polyline Data (Figure 126 == 9.5 Figure 145): polyline segments of a text representation.
 *
 * Both references draw the `Polyline Segment Index Count > 0` guard around the index loop **and**
 * the coordinate vector, so a document-conformant producer writes neither when there are no
 * segments; NX 10.5 writes the empty vector regardless (DESIGN.md delta 36). [vertexCoords] is
 * therefore `null` for the figure's form and an (empty) list for the producer's — the model
 * remembers which it saw, so re-serialization stays byte-identical for both.
 */
data class PmiTextPolylineData(
    /** Vertex indices into [vertexCoords] (multiply by 2 for the packed 2D array). */
    val segmentIndices: List<Short>,
    /** 2D vertex coordinates, packed `[XY][XY]…`; `null` when the guard kept them off the wire. */
    val vertexCoords: List<Float>?,
)

/**
 * Non-Text Polyline Data (Figure 128): the non-text polylines of a PMI entity. The packed
 * coordinates are 3D for Generic PMI Entities and 2D for every other entity type (§11.2.6.1.3)
 * — the model keeps the flat array the wire carries, so no interpretation is imposed here.
 */
data class PmiNonTextPolylineData(
    val segmentIndices: List<Int>,
    /** Table 63 polyline types, one per segment run. */
    val types: List<Short>,
    val widths: List<Short>,
    val vertexCoords: List<Float>,
)

/** 2D Text Data (Figure 123): one text primitive of a 2D PMI entity. */
data class Pmi2dText(
    /** Index into the PMI String Table, −1 = no string. */
    val stringId: Int,
    /** Table 62 font identifier, carried as read (the fixture writes −1 = unset). */
    val font: Int,
    /** I32 Empty Field, preserved as read. */
    val emptyFieldI32: Int,
    /** F32 Empty Field, preserved as read. */
    val emptyFieldF32: Float,
    val textBox: PmiTextBox,
    val polylines: PmiTextPolylineData,
)

/** PMI Base Data (Figure 121): what every 2D and 3D PMI entity carries. */
data class PmiBaseData(
    val userLabel: Int,
    /**
     * 2D-Frame Flag: 0 = no frame stored, non-zero = [referenceFrame] follows. Value 2 means
     * the frame is "dummy" per §11.2.6.1.1 — NX 10.5 writes 2 with a *populated* frame on
     * every entity of the NIST fixture, so the flag is preserved rather than interpreted.
     */
    val frameFlag: Int,
    val referenceFrame: Pmi2dReferenceFrame?,
    val textHeight: Float,
    /** Non-zero = the PMI entity is valid. */
    val symbolValidFlag: Int,
)

/**
 * PMI 2D Data (Figure 120): the base data, the text entities, and the non-text polylines.
 *
 * Figure 120's last box is *unlabeled* in the reference PDF; §11.2.6.1.3 places Non-Text
 * Polyline Data inside PMI 2D Data, and the fixture's bytes confirm it sits exactly there
 * (recorded in DESIGN.md).
 */
data class Pmi2dData(
    val base: PmiBaseData,
    val texts: List<Pmi2dText>,
    val nonTextPolylines: PmiNonTextPolylineData,
)

/** One entry of Generic PMI Entities (Figure 119). */
data class GenericPmiEntity(
    val data2d: Pmi2dData,
    val properties: List<PmiProperty>,
    /** Index into the PMI String Table, −1 = no string. */
    val entityTypeNameStringId: Int,
    /** Index into the PMI String Table, −1 = no string. */
    val parentTypeNameStringId: Int,
    /** Table 60 entity type, carried as read (the fixture writes values the table omits). */
    val entityType: Int,
    /** Table 60 parent type, carried as read. */
    val parentType: Int,
    /** Table 61 user flags. */
    val userFlags: Int,
)

/** One non-empty PolygonData element of a PMI Polygon Data block (Figure 130). */
data class PmiPolygonDataElement(
    /** The element's `vNumVerts` entry, as read. */
    val vertexCount: Int,
    /** Colour binding: 1 = [colours] present. */
    val colourBinding: Int,
    /** Normal binding: 1 = [normals] present. */
    val normalBinding: Int,
    /** Texture binding: 1 = [textureCoords] present. */
    val textureBinding: Int,
    /** Coordinates per vertex. */
    val polygonDimension: Int,
    /** `[PrimIndex, PrimType]` tuples. */
    val primitiveTypes: List<Int>,
    /** Offsets into [vertexIndices]; one extra entry closes the last primitive. */
    val primitiveIndices: List<Int>,
    val vertexIndices: List<Int>,
    val vertices: List<Float>,
    val normals: List<Float>?,
    val colours: List<Float>?,
    val textureCoords: List<Float>?,
)

/**
 * PMI Polygon Data (Figure 130): the polygonal primitives of a PMI segment — used both for
 * the manager's own polygon block and for each font's glyph definitions.
 *
 * The parallel `vNumVerts` / `vBindings` / `vPolygonDimensions` vectors are kept as read
 * (their entries index the non-empty elements, as the figure's own example spells out) and
 * the per-element data is decoded into [elements], one per non-zero `vNumVerts` entry.
 */
data class PmiPolygonData(
    val version: Int,
    val vertexCounts: List<Int>,
    val bindings: List<Int>,
    val polygonDimensions: List<Int>,
    val elements: List<PmiPolygonDataElement>,
)

/**
 * PMI CAD Tag Data (Figure 129): one CAD Tag index per PMI entity, plus the
 * [CompressedCadTagData] collection of Figure 154 — decoded since the Int64 CDP landed with
 * issue #10; a collection whose coded vectors do not decode keeps them verbatim with a named
 * note.
 */
data class PmiCadTagData(
    val indices: List<Int>,
    val compressed: CompressedCadTagData,
)

/** One font of a PMI Manager (Figure 110): a name, a character set and the glyph outlines. */
data class PmiFont(
    val name: String,
    /** The U16 character identifiers whose glyphs [glyphs] defines, in order. */
    val characterSet: List<Int>,
    val glyphs: PmiPolygonData,
)

/** One entry of PMI Model View Sort Orders (Figure 131). */
data class PmiModelViewSortOrder(
    val keyStringId: Int,
    val valueStringId: Int,
)

/**
 * PMI Manager Meta Data Element (Figure 110): the Product and Manufacturing Information of a
 * part or assembly. The NIST 10.5 fixture carries 14 of them, all inside *PMI Data* segments
 * (Table 6 type 3) which Annex H says to parse exactly like this element.
 *
 * Everything Figure 110 documents up to and including the font block is decoded and verified
 * by exact byte consumption plus cross-checks (the CAD Tag index count equals the sum of the
 * entity counts; every String ID resolves into [stringTable]). What NX 10.5 writes *after*
 * the fonts is documented by neither the v10.0 nor the v9.5 reference; it is carried verbatim
 * in [undocumentedTail] with a named note rather than guessed — see DESIGN.md.
 */
data class PmiManagerMetaDataElement(
    override val objectId: Int,
    val version: Int,
    /** I16 Empty Field, preserved as read (the fixture writes −1). */
    val emptyField: Int,
    val designGroups: List<PmiDesignGroup>,
    val associations: List<PmiAssociation>,
    val userAttributes: List<PmiUserAttribute>,
    /** The central string repository every String ID of this element indexes into. */
    val stringTable: List<String>,
    val modelViews: List<PmiModelView>,
    val genericEntities: List<GenericPmiEntity>,
    val polygonData: PmiPolygonData,
    /** 1 = [cadTagData] is present. */
    val cadTagsFlag: Int,
    val cadTagData: PmiCadTagData?,
    val fonts: List<PmiFont>,
    /**
     * Bytes after the font block, preserved verbatim. Figure 110 places a Property Count,
     * PMI Properties and PMI Model View Sort Orders here; NX 10.5 writes a block whose shape
     * matches none of that (DESIGN.md delta 32), so the bytes are named, kept and reported
     * by a note instead of being read as something they may not be. Empty means the element
     * ended exactly where Figure 110 says it should.
     */
    val undocumentedTail: Bytes,
) : TypedMetaDataElement() {
    override val objectTypeId: Guid get() = de.haumacher.kotlinjt.lsg.ObjectTypeIds.PMI_MANAGER_META_DATA_ELEMENT

    /** [stringTable] lookup honoring the "−1 means no string" convention of every String ID. */
    fun string(stringId: Int): String? = if (stringId < 0 || stringId >= stringTable.size) null else stringTable[stringId]
}
