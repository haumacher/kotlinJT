package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes

/**
 * The wire-format generations the LSG codecs distinguish. The v10 reference documents only
 * the v10.0 layouts; the v9 deltas and the 10.5 deltas are established against real fixtures
 * and recorded in DESIGN.md (v9: the NetAllied 9.5 file; 10.5: the NIST NX file — deltas
 * 23–26). Where a layout is *not* established for a generation, the type is carried opaquely
 * (with a named note) — never guessed.
 */
enum class LsgGeneration {
    V9,
    V10,

    /** JT 10.5+: the v10 layouts plus the fixture-established 10.5 deltas. */
    V10_5,
    ;

    companion object {
        fun of(version: JtVersion): LsgGeneration =
            when {
                version.major < 10 -> V9
                version.major == 10 && version.minor < 5 -> V10
                else -> V10_5
            }
    }
}

/** Mutable context of one document decode: the generation and the collected notes. */
internal class LsgDecodeContext(
    val generation: LsgGeneration,
    val notes: MutableList<LoadNote>,
)

/**
 * Version Number fields are I16 in JT 9 and one byte (U8/I8) in JT 10 — the pervasive
 * generational delta, fixture-verified across the node, attribute and property atom families.
 */
private fun ByteReader.readVersionNumber(generation: LsgGeneration): Int =
    when (generation) {
        LsgGeneration.V9 -> readI16().toInt()
        LsgGeneration.V10, LsgGeneration.V10_5 -> readU8().toInt()
    }

private fun ByteWriter.writeVersionNumber(
    generation: LsgGeneration,
    value: Int,
) {
    when (generation) {
        LsgGeneration.V9 -> writeI16(value.toShort())
        LsgGeneration.V10, LsgGeneration.V10_5 -> writeU8(value.toUByte())
    }
}

/** Wire width of a Version Number field in [generation] — needed to budget an element body. */
private fun versionNumberWidth(generation: LsgGeneration): Int = if (generation == LsgGeneration.V9) 2 else 1

/**
 * The trailing I32 the 10.5 generation appends to every element carrying Base Attribute
 * Data — after the type-specific fields, observed −1 throughout (DESIGN.md delta 24).
 */
private fun ByteReader.readAttributeTail(generation: LsgGeneration): Int? = if (generation == LsgGeneration.V10_5) readI32() else null

private fun ByteWriter.writeAttributeTail(
    generation: LsgGeneration,
    tail: Int?,
) {
    if (generation == LsgGeneration.V10_5) writeI32(tail ?: -1)
}

/** Reads an I32 element count that is about to size a list; bounds it against the input. */
private fun ByteReader.readCount(
    what: String,
    bytesPerEntry: Int,
): Int {
    val count = readI32()
    if (count < 0 || count > remaining / bytesPerEntry) {
        throw JtFormatException("$what count $count does not fit the remaining $remaining bytes")
    }
    return count
}

private fun ByteReader.readVecF32Bounded(): List<Float> {
    val count = readCount("VecF32", 4)
    return List(count) { readF32() }
}

// ---------------------------------------------------------------------------
// Shared data collections
// ---------------------------------------------------------------------------

private fun readBaseNodeData(
    r: ByteReader,
    g: LsgGeneration,
): BaseNodeData {
    val version = r.readVersionNumber(g)
    val nodeFlags = r.readU32()
    val count = r.readCount("attribute object id", 4)
    return BaseNodeData(version, nodeFlags, List(count) { r.readI32() })
}

private fun writeBaseNodeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: BaseNodeData,
) {
    w.writeVersionNumber(g, data.version)
    w.writeU32(data.nodeFlags)
    w.writeI32(data.attributeObjectIds.size)
    for (id in data.attributeObjectIds) w.writeI32(id)
}

private fun readGroupNodeData(
    r: ByteReader,
    g: LsgGeneration,
): GroupNodeData {
    val base = readBaseNodeData(r, g)
    val version = r.readVersionNumber(g)
    val count = r.readCount("child node object id", 4)
    return GroupNodeData(base, version, List(count) { r.readI32() })
}

private fun writeGroupNodeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: GroupNodeData,
) {
    writeBaseNodeData(w, g, data.base)
    w.writeVersionNumber(g, data.version)
    w.writeI32(data.childNodeObjectIds.size)
    for (id in data.childNodeObjectIds) w.writeI32(id)
}

private fun readMetaDataNodeData(
    r: ByteReader,
    g: LsgGeneration,
): MetaDataNodeData = MetaDataNodeData(readGroupNodeData(r, g), r.readVersionNumber(g))

private fun writeMetaDataNodeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: MetaDataNodeData,
) {
    writeGroupNodeData(w, g, data.group)
    w.writeVersionNumber(g, data.version)
}

private fun readLodNodeData(
    r: ByteReader,
    g: LsgGeneration,
): LodNodeData {
    val group = readGroupNodeData(r, g)
    val version = r.readVersionNumber(g)
    // JT 9 carries a reserved VecF32 and a reserved I32 here that JT 10 dropped
    // (fixture-verified against the Range LOD nodes of the real 9.5 file).
    return if (g == LsgGeneration.V9) {
        LodNodeData(group, version, r.readVecF32Bounded(), r.readI32())
    } else {
        LodNodeData(group, version)
    }
}

private fun writeLodNodeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: LodNodeData,
) {
    writeGroupNodeData(w, g, data.group)
    w.writeVersionNumber(g, data.version)
    if (g == LsgGeneration.V9) {
        w.writeVecF32(data.reservedVector)
        w.writeI32(data.reservedField)
    }
}

private fun readBaseShapeData(
    r: ByteReader,
    g: LsgGeneration,
): BaseShapeData {
    val base = readBaseNodeData(r, g)
    val version = r.readVersionNumber(g)
    // JT 9 stores a reserved bounding box before the untransformed one (fixture-verified:
    // the real 9.5 file repeats the box twice); JT 10 (Figure 36) has only one.
    val reservedBBox = if (g == LsgGeneration.V9) r.readBBoxF32() else null
    return BaseShapeData(
        base,
        version,
        reservedBBox,
        r.readBBoxF32(),
        r.readF32(),
        r.readCountRange(),
        r.readCountRange(),
        r.readCountRange(),
        r.readU32(),
        r.readF32(),
    )
}

private fun writeBaseShapeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: BaseShapeData,
) {
    writeBaseNodeData(w, g, data.base)
    w.writeVersionNumber(g, data.version)
    if (g == LsgGeneration.V9) {
        w.writeBBoxF32(data.reservedBBox ?: data.untransformedBBox)
    }
    w.writeBBoxF32(data.untransformedBBox)
    w.writeF32(data.area)
    w.writeCountRange(data.vertexCountRange)
    w.writeCountRange(data.nodeCountRange)
    w.writeCountRange(data.polygonCountRange)
    w.writeU32(data.size)
    w.writeF32(data.compressionLevel)
}

/**
 * Vertex Shape Data plus the presence decision for the *enclosing* element's own guarded
 * `U64: Vertex Bindings`. Both are settled by one look at the body's remaining length, and
 * the look has to happen inside the Vertex Shape Data read — that is where the last
 * variable-width field (Base Node Data's attribute list) is already behind us.
 */
private class VertexShapeRead(
    val data: VertexShapeData,
    /** Whether the enclosing shape node's guarded `U64` is on the wire. */
    val shapeNodeBindings: Boolean,
)

/**
 * Resolves the guarded `U64` fields' presence from the bytes that are actually left in the
 * element body — the lenient-read half of the doctrine (DESIGN.md, "Lenient when reading,
 * strict when writing").
 *
 * [r] stands right after the last unconditional field preceding the first guarded `U64`
 * (Quantization Parameters in JT 9, the first Vertex Binding in JT 10). [fixedTailBytes] is
 * the width of the unconditional fields the enclosing element still writes after its Vertex
 * Shape Data — 0 for the elements that end with it, Version Number + Area Factor for the
 * polyline and point sets — and [nodeGuard] says whether that element ends in a guarded `U64`
 * of its own (9.5 Figs. 33/34, v10 Fig. 41).
 *
 * Returns *(Vertex Shape Data field present, shape node field present)*. When the remaining
 * length admits exactly one combination it is the answer outright; when it admits two (the
 * mixed cases, both fields being 8 bytes wide) the Version Number at the candidate offset
 * breaks the tie, and if that does not discriminate either the read is refused rather than
 * guessed — leniency stops where the evidence does. When the length admits no combination at
 * all the append-only reading is used — every guarded field present, see "Local version
 * guards mean `>= N`" — and the frame's strict length check then names the failure.
 */
private fun resolveGuardedBindings(
    r: ByteReader,
    g: LsgGeneration,
    vertexShapeGuard: Boolean,
    fixedTailBytes: Int,
    nodeGuard: Boolean,
): Pair<Boolean, Boolean> {
    val remaining = r.remaining
    val shapeOptions = if (vertexShapeGuard) listOf(true, false) else listOf(false)
    val nodeOptions = if (nodeGuard) listOf(true, false) else listOf(false)
    val fits =
        shapeOptions.flatMap { inShapeData -> nodeOptions.map { inNode -> inShapeData to inNode } }
            .filter { (inShapeData, inNode) ->
                (if (inShapeData) 8 else 0) + fixedTailBytes + (if (inNode) 8 else 0) == remaining
            }
    if (fits.size == 1) return fits.single()
    if (fits.isEmpty()) return vertexShapeGuard to nodeGuard
    // Both mixed readings fit the length. The one whose Version Number lands on a documented
    // value wins; if that does not discriminate either, the bytes genuinely do not say which
    // field is on the wire, and a guess would put an invented decomposition into a lossless
    // model. Refuse instead — the frame carries the element opaquely with a named note.
    val plausible = fits.filter { (inShapeData, _) -> plausibleVersionAt(r, g, if (inShapeData) 8 else 0) }
    if (plausible.size == 1) return plausible.single()
    throw JtFormatException(
        "the $remaining trailing bytes of this element body fit ${fits.size} readings of the guarded " +
            "U64 vertex binding fields, and the version numbers at the candidate offsets do not discriminate them",
    )
}

/**
 * Peeks the enclosing shape node's Version Number [offset] bytes ahead without consuming it,
 * and tests it against the value set the figures document (9.5 Figs. 33/34: `0x0002` is the
 * highest valid value).
 */
private fun plausibleVersionAt(
    r: ByteReader,
    g: LsgGeneration,
    offset: Int,
): Boolean {
    val mark = r.position
    return try {
        r.position = mark + offset
        r.readVersionNumber(g) in 1..2
    } catch (_: JtFormatException) {
        false
    } finally {
        r.position = mark
    }
}

private fun readVertexShapeData(
    r: ByteReader,
    g: LsgGeneration,
    fixedTailBytes: Int = 0,
    nodeGuard: Boolean = false,
): VertexShapeRead {
    val shape = readBaseShapeData(r, g)
    val version = r.readVersionNumber(g)
    val bindings = r.readU64()
    return if (g == LsgGeneration.V9) {
        // 9.5 Figure 30: Quantization Parameters, then a second `U64 : Vertex Binding` that
        // belongs to local version 1 and is therefore present from version 1 upwards.
        val quantization =
            QuantizationParameters(
                r.readU8().toInt(),
                r.readU8().toInt(),
                r.readU8().toInt(),
                r.readU8().toInt(),
            )
        val (inShapeData, inNode) = resolveGuardedBindings(r, g, true, fixedTailBytes, nodeGuard)
        val bindings2 = if (inShapeData) r.readU64() else null
        VertexShapeRead(VertexShapeData(shape, version, bindings, quantization, bindings2), inNode)
    } else {
        // v10 Figure 39 stops after the first binding; only the enclosing node may add one.
        val (_, inNode) = resolveGuardedBindings(r, g, false, fixedTailBytes, nodeGuard)
        VertexShapeRead(VertexShapeData(shape, version, bindings, null, null), inNode)
    }
}

private fun writeVertexShapeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: VertexShapeData,
) {
    writeBaseShapeData(w, g, data.shape)
    w.writeVersionNumber(g, data.version)
    w.writeU64(data.vertexBindings)
    if (g == LsgGeneration.V9) {
        val quantization = data.quantizationParameters ?: QuantizationParameters(0, 0, 0, 0)
        w.writeU8(quantization.bitsPerVertex.toUByte())
        w.writeU8(quantization.normalBitsFactor.toUByte())
        w.writeU8(quantization.bitsPerTextureCoord.toUByte())
        w.writeU8(quantization.bitsPerColour.toUByte())
        // Presence is a model fact: emit Figure 30's guarded field exactly when it was read.
        // Re-deriving it from the version number invents eight bytes for a body that legally
        // omits them.
        data.vertexBindings2?.let { w.writeU64(it) }
    }
}

private fun readBaseAttributeData(
    r: ByteReader,
    g: LsgGeneration,
): BaseAttributeData {
    val version = r.readVersionNumber(g)
    val stateFlags = r.readU8().toInt()
    val inhibit = r.readU32()
    // Field Final Flags are a JT 10 addition (fixture-verified absent in 9.5).
    val final = if (g != LsgGeneration.V9) r.readU32() else null
    return BaseAttributeData(version, stateFlags, inhibit, final)
}

private fun writeBaseAttributeData(
    w: ByteWriter,
    g: LsgGeneration,
    data: BaseAttributeData,
) {
    w.writeVersionNumber(g, data.version)
    w.writeU8(data.stateFlags.toUByte())
    w.writeU32(data.fieldInhibitFlags)
    if (g != LsgGeneration.V9) {
        w.writeU32(data.fieldFinalFlags ?: 0u)
    }
}

private fun readBasePropertyAtomData(
    r: ByteReader,
    g: LsgGeneration,
): BasePropertyAtomData = BasePropertyAtomData(r.readVersionNumber(g), r.readU32())

private fun writeBasePropertyAtomData(
    w: ByteWriter,
    g: LsgGeneration,
    data: BasePropertyAtomData,
) {
    w.writeVersionNumber(g, data.version)
    w.writeU32(data.stateFlags)
}

private fun readBaseLightData(
    r: ByteReader,
    g: LsgGeneration,
): BaseLightData =
    BaseLightData(
        readBaseAttributeData(r, g),
        r.readVersionNumber(g),
        r.readRgba(),
        r.readRgba(),
        r.readRgba(),
        r.readF32(),
        r.readI32(),
        r.readU8().toInt(),
        r.readF32(),
        r.readF32(),
        r.readF32(),
    )

private fun writeBaseLightData(
    w: ByteWriter,
    g: LsgGeneration,
    data: BaseLightData,
) {
    writeBaseAttributeData(w, g, data.baseAttribute)
    w.writeVersionNumber(g, data.version)
    w.writeRgba(data.ambientColour)
    w.writeRgba(data.diffuseColour)
    w.writeRgba(data.specularColour)
    w.writeF32(data.brightness)
    w.writeI32(data.coordSystem)
    w.writeU8(data.shadowCasterFlag.toUByte())
    w.writeF32(data.shadowOpacity)
    w.writeF32(data.nonShadowAlphaFactor)
    w.writeF32(data.shadowAlphaFactor)
}

// ---------------------------------------------------------------------------
// Element codecs
// ---------------------------------------------------------------------------

/**
 * The decoder/serializer of one Annex A element type. [objectBaseType] is the Object Base
 * Type byte the type is framed with (Table 7); a mismatching byte on the wire refuses the
 * typed decode (the element is then carried opaquely, byte-faithful, with a named note).
 */
internal abstract class LsgElementCodec(
    val typeId: Guid,
    val typeName: String,
    val objectBaseType: Int,
    /** `false` for types whose JT 9 wire layout is not established — V9 carries them opaquely. */
    val v9Layout: Boolean = true,
) {
    fun decodableIn(generation: LsgGeneration): Boolean = generation != LsgGeneration.V9 || v9Layout

    abstract fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): TypedLsgElement

    abstract fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    )
}

private object BaseNodeCodec : LsgElementCodec(ObjectTypeIds.BASE_NODE, "Base Node Element", 0) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = BaseNodeElement(objectId, readBaseNodeData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeBaseNodeData(w, g, (element as BaseNodeElement).baseNode)
}

private object PartitionNodeCodec : LsgElementCodec(ObjectTypeIds.PARTITION_NODE, "Partition Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PartitionNodeElement {
        val group = readGroupNodeData(r, ctx.generation)
        // JT 10.5 inserts a version number the v10.0 reference does not show (delta 23).
        val version = if (ctx.generation == LsgGeneration.V10_5) r.readVersionNumber(ctx.generation) else null
        val flags = r.readI32()
        val fileName = r.readMbString()
        val transformedBBox = r.readBBoxF32()
        val area = r.readF32()
        val vertexCountRange = r.readCountRange()
        val nodeCountRange = r.readCountRange()
        val polygonCountRange = r.readCountRange()
        // Figure 23: the untransformed box follows exactly when flags bit 0 is set. The 10.5
        // producer observed sets the bit *without* the box (delta 23); since the box is the
        // final field, its presence is decided by the remaining length — the strict
        // full-consumption check turns every other combination into an opaque carry.
        val untransformedBBox = if (flags and 1 != 0 && r.remaining >= 24) r.readBBoxF32() else null
        return PartitionNodeElement(
            objectId, group, version, flags, fileName, transformedBBox, area,
            vertexCountRange, nodeCountRange, polygonCountRange, untransformedBBox,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PartitionNodeElement
        writeGroupNodeData(w, g, element.group)
        if (g == LsgGeneration.V10_5) {
            w.writeVersionNumber(g, element.version ?: 1)
        }
        w.writeI32(element.partitionFlags)
        w.writeMbString(element.fileName)
        w.writeBBoxF32(element.transformedBBox)
        w.writeF32(element.area)
        w.writeCountRange(element.vertexCountRange)
        w.writeCountRange(element.nodeCountRange)
        w.writeCountRange(element.polygonCountRange)
        element.untransformedBBox?.let { w.writeBBoxF32(it) }
    }
}

private object GroupNodeCodec : LsgElementCodec(ObjectTypeIds.GROUP_NODE, "Group Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = GroupNodeElement(objectId, readGroupNodeData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeGroupNodeData(w, g, (element as GroupNodeElement).group)
}

private object InstanceNodeCodec : LsgElementCodec(ObjectTypeIds.INSTANCE_NODE, "Instance Node Element", 0) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): InstanceNodeElement {
        val base = readBaseNodeData(r, ctx.generation)
        return InstanceNodeElement(objectId, base, r.readVersionNumber(ctx.generation), r.readI32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as InstanceNodeElement
        writeBaseNodeData(w, g, element.baseNode)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.childNodeObjectId)
    }
}

private object PartNodeCodec : LsgElementCodec(ObjectTypeIds.PART_NODE, "Part Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PartNodeElement {
        val metaData = readMetaDataNodeData(r, ctx.generation)
        return PartNodeElement(objectId, metaData, r.readVersionNumber(ctx.generation), r.readI32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PartNodeElement
        writeMetaDataNodeData(w, g, element.metaData)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.emptyField)
    }
}

private object MetaDataNodeCodec : LsgElementCodec(ObjectTypeIds.META_DATA_NODE, "Meta Data Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = MetaDataNodeElement(objectId, readMetaDataNodeData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeMetaDataNodeData(w, g, (element as MetaDataNodeElement).metaData)
}

private object LodNodeCodec : LsgElementCodec(ObjectTypeIds.LOD_NODE, "LOD Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = LodNodeElement(objectId, readLodNodeData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeLodNodeData(w, g, (element as LodNodeElement).lod)
}

private object RangeLodNodeCodec : LsgElementCodec(ObjectTypeIds.RANGE_LOD_NODE, "Range LOD Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): RangeLodNodeElement {
        val lod = readLodNodeData(r, ctx.generation)
        return RangeLodNodeElement(
            objectId,
            lod,
            r.readVersionNumber(ctx.generation),
            r.readVecF32Bounded(),
            r.readVec3F32(),
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as RangeLodNodeElement
        writeLodNodeData(w, g, element.lod)
        w.writeVersionNumber(g, element.version)
        w.writeVecF32(element.rangeLimits)
        w.writeVec3F32(element.centre)
    }
}

private object SwitchNodeCodec : LsgElementCodec(ObjectTypeIds.SWITCH_NODE, "Switch Node Element", 1) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): SwitchNodeElement {
        val group = readGroupNodeData(r, ctx.generation)
        return SwitchNodeElement(objectId, group, r.readVersionNumber(ctx.generation), r.readI32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as SwitchNodeElement
        writeGroupNodeData(w, g, element.group)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.selectedChild)
    }
}

private object BaseShapeNodeCodec : LsgElementCodec(ObjectTypeIds.BASE_SHAPE_NODE, "Base Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = BaseShapeNodeElement(objectId, readBaseShapeData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeBaseShapeData(w, g, (element as BaseShapeNodeElement).shape)
}

private object VertexShapeNodeCodec : LsgElementCodec(ObjectTypeIds.VERTEX_SHAPE_NODE, "Vertex Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = VertexShapeNodeElement(objectId, readVertexShapeData(r, ctx.generation).data)

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeVertexShapeData(w, g, (element as VertexShapeNodeElement).vertexShape)
}

private object TriStripSetShapeNodeCodec :
    LsgElementCodec(ObjectTypeIds.TRI_STRIP_SET_SHAPE_NODE, "Tri-Strip Set Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = TriStripSetShapeNodeElement(objectId, readVertexShapeData(r, ctx.generation).data)

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeVertexShapeData(w, g, (element as TriStripSetShapeNodeElement).vertexShape)
}

private object PolylineSetShapeNodeCodec :
    LsgElementCodec(ObjectTypeIds.POLYLINE_SET_SHAPE_NODE, "Polyline Set Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PolylineSetShapeNodeElement {
        val g = ctx.generation
        // 9.5 Figure 33 ends the element with a guarded `U64: Vertex Bindings`; v10 Figure 40
        // has no such field at all, so only the JT 9 layout can carry one.
        val read = readVertexShapeData(r, g, versionNumberWidth(g) + 4, g == LsgGeneration.V9)
        val version = r.readVersionNumber(g)
        val areaFactor = r.readF32()
        val bindings = if (read.shapeNodeBindings) r.readU64() else null
        return PolylineSetShapeNodeElement(objectId, read.data, version, areaFactor, bindings)
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PolylineSetShapeNodeElement
        writeVertexShapeData(w, g, element.vertexShape)
        w.writeVersionNumber(g, element.version)
        w.writeF32(element.areaFactor)
        element.vertexBindings?.let { w.writeU64(it) }
    }
}

private object PointSetShapeNodeCodec :
    LsgElementCodec(ObjectTypeIds.POINT_SET_SHAPE_NODE, "Point Set Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PointSetShapeNodeElement {
        val g = ctx.generation
        // 9.5 Figure 34 and v10 Figure 41 agree field for field, including the guarded
        // `U64: Vertex Bindings` — so the guard applies in every generation.
        val read = readVertexShapeData(r, g, versionNumberWidth(g) + 4, true)
        val version = r.readVersionNumber(g)
        val areaFactor = r.readF32()
        val bindings = if (read.shapeNodeBindings) r.readU64() else null
        return PointSetShapeNodeElement(objectId, read.data, version, areaFactor, bindings)
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PointSetShapeNodeElement
        writeVertexShapeData(w, g, element.vertexShape)
        w.writeVersionNumber(g, element.version)
        w.writeF32(element.areaFactor)
        element.vertexBindings?.let { w.writeU64(it) }
    }
}

private object PolygonSetShapeNodeCodec :
    LsgElementCodec(ObjectTypeIds.POLYGON_SET_SHAPE_NODE, "Polygon Set Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = PolygonSetShapeNodeElement(objectId, readVertexShapeData(r, ctx.generation).data)

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeVertexShapeData(w, g, (element as PolygonSetShapeNodeElement).vertexShape)
}

private object NullShapeNodeCodec : LsgElementCodec(ObjectTypeIds.NULL_SHAPE_NODE, "NULL Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): NullShapeNodeElement {
        val shape = readBaseShapeData(r, ctx.generation)
        return NullShapeNodeElement(objectId, shape, r.readVersionNumber(ctx.generation))
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as NullShapeNodeElement
        writeBaseShapeData(w, g, element.shape)
        w.writeVersionNumber(g, element.version)
    }
}

private object PrimitiveSetShapeNodeCodec :
    LsgElementCodec(ObjectTypeIds.PRIMITIVE_SET_SHAPE_NODE, "Primitive Set Shape Node Element", 2) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PrimitiveSetShapeNodeElement {
        val g = ctx.generation
        val shape = readBaseShapeData(r, g)
        val version = r.readVersionNumber(g)
        // 9.5 Figure 37 carries `I32 : Texture Coord Binding` and `I32 : Color Binding` where
        // v10 Figure 44 fuses both into one `U64 : Vertex Bindings`. Same eight bytes, two
        // different decompositions — reading the v10 one in JT 9 records a wrong model.
        val v9 = g == LsgGeneration.V9
        val vertexBindings = if (v9) null else r.readU64()
        val textureCoordBinding = if (v9) r.readI32() else null
        val colourBinding = if (v9) r.readI32() else null
        val texCoordGenType = r.readI32()
        val version2 = r.readVersionNumber(g)
        return PrimitiveSetShapeNodeElement(
            objectId,
            shape,
            version,
            vertexBindings,
            textureCoordBinding,
            colourBinding,
            texCoordGenType,
            version2,
            PrimitiveSetQuantizationParameters(r.readU8().toInt(), r.readU8().toInt()),
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PrimitiveSetShapeNodeElement
        writeBaseShapeData(w, g, element.shape)
        w.writeVersionNumber(g, element.version)
        if (g == LsgGeneration.V9) {
            w.writeI32(element.textureCoordBinding ?: 0)
            w.writeI32(element.colourBinding ?: 0)
        } else {
            w.writeU64(element.vertexBindings ?: 0u)
        }
        w.writeI32(element.texCoordGenType)
        w.writeVersionNumber(g, element.version2)
        w.writeU8(element.quantization.bitsPerVertex.toUByte())
        w.writeU8(element.quantization.bitsPerColour.toUByte())
    }
}

private object MaterialAttributeCodec : LsgElementCodec(ObjectTypeIds.MATERIAL_ATTRIBUTE, "Material Attribute Element", 3) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): MaterialAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val dataFlags = r.readU16().toInt()
        if (ctx.generation == LsgGeneration.V9 && dataFlags and 0x000F != 0) {
            // The v10 inhibit table hints at "Common RGB Value" compact colour storage in
            // earlier generations; its v9 wire layout is not established. Refuse rather
            // than misread — the element is then carried opaquely with a named note.
            throw JtFormatException(
                "JT9 material data flags 0x${dataFlags.toString(16)} indicate compact colour storage; layout not established",
            )
        }
        val ambient = r.readRgba()
        val diffuse = r.readRgba()
        val specular = r.readRgba()
        val emission = r.readRgba()
        val shininess = r.readF32()
        val reflectivity = if (ctx.generation != LsgGeneration.V9 || version >= 2) r.readF32() else null
        val bumpiness = if (ctx.generation != LsgGeneration.V9) r.readF32() else null
        val tail = r.readAttributeTail(ctx.generation)
        return MaterialAttributeElement(
            objectId, base.copy(reservedTail = tail), version, dataFlags, ambient, diffuse, specular, emission,
            shininess, reflectivity, bumpiness,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as MaterialAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeU16(element.dataFlags.toUShort())
        w.writeRgba(element.ambientColour)
        w.writeRgba(element.diffuseColourAndAlpha)
        w.writeRgba(element.specularColour)
        w.writeRgba(element.emissionColour)
        w.writeF32(element.shininess)
        if (g != LsgGeneration.V9 || element.version >= 2) {
            w.writeF32(element.reflectivity ?: 0f)
        }
        if (g != LsgGeneration.V9) {
            w.writeF32(element.bumpiness ?: 1f)
        }
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private object DrawStyleAttributeCodec :
    LsgElementCodec(ObjectTypeIds.DRAW_STYLE_ATTRIBUTE, "Draw Style Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): DrawStyleAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val dataFlags = r.readU8().toInt()
        return DrawStyleAttributeElement(objectId, base.copy(reservedTail = r.readAttributeTail(ctx.generation)), version, dataFlags)
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as DrawStyleAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeU8(element.dataFlags.toUByte())
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private object LightSetAttributeCodec :
    LsgElementCodec(ObjectTypeIds.LIGHT_SET_ATTRIBUTE, "Light Set Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): LightSetAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val count = r.readCount("light object id", 4)
        val ids = List(count) { r.readI32() }
        return LightSetAttributeElement(objectId, base.copy(reservedTail = r.readAttributeTail(ctx.generation)), version, ids)
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as LightSetAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.lightObjectIds.size)
        for (id in element.lightObjectIds) w.writeI32(id)
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private object InfiniteLightAttributeCodec :
    LsgElementCodec(ObjectTypeIds.INFINITE_LIGHT_ATTRIBUTE, "Infinite Light Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): InfiniteLightAttributeElement {
        val baseLight = readBaseLightData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val direction = r.readVec3F32()
        val tail = r.readAttributeTail(ctx.generation)
        return InfiniteLightAttributeElement(
            objectId,
            baseLight.copy(baseAttribute = baseLight.baseAttribute.copy(reservedTail = tail)),
            version,
            direction,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as InfiniteLightAttributeElement
        writeBaseLightData(w, g, element.baseLight)
        w.writeVersionNumber(g, element.version)
        w.writeVec3F32(element.direction)
        w.writeAttributeTail(g, element.baseLight.baseAttribute.reservedTail)
    }
}

private object PointLightAttributeCodec :
    LsgElementCodec(ObjectTypeIds.POINT_LIGHT_ATTRIBUTE, "Point Light Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PointLightAttributeElement {
        val baseLight = readBaseLightData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val position = r.readVec4F32()
        val attenuation = AttenuationCoefficients(r.readF32(), r.readF32(), r.readF32())
        val spreadAngle = r.readF32()
        val spotDirection = r.readVec3F32()
        val spotIntensity = r.readI32()
        val tail = r.readAttributeTail(ctx.generation)
        return PointLightAttributeElement(
            objectId,
            baseLight.copy(baseAttribute = baseLight.baseAttribute.copy(reservedTail = tail)),
            version,
            position,
            attenuation,
            spreadAngle,
            spotDirection,
            spotIntensity,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PointLightAttributeElement
        writeBaseLightData(w, g, element.baseLight)
        w.writeVersionNumber(g, element.version)
        w.writeVec4F32(element.position)
        w.writeF32(element.attenuation.constant)
        w.writeF32(element.attenuation.linear)
        w.writeF32(element.attenuation.quadratic)
        w.writeF32(element.spreadAngle)
        w.writeVec3F32(element.spotDirection)
        w.writeI32(element.spotIntensity)
        w.writeAttributeTail(g, element.baseLight.baseAttribute.reservedTail)
    }
}

private object LinestyleAttributeCodec :
    LsgElementCodec(ObjectTypeIds.LINESTYLE_ATTRIBUTE, "Linestyle Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): LinestyleAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val dataFlags = r.readU8().toInt()
        val lineWidth = r.readF32()
        return LinestyleAttributeElement(
            objectId,
            base.copy(reservedTail = r.readAttributeTail(ctx.generation)),
            version,
            dataFlags,
            lineWidth,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as LinestyleAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeU8(element.dataFlags.toUByte())
        w.writeF32(element.lineWidth)
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private object PointstyleAttributeCodec :
    LsgElementCodec(ObjectTypeIds.POINTSTYLE_ATTRIBUTE, "Pointstyle Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): PointstyleAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val dataFlags = r.readU8().toInt()
        val pointSize = r.readF32()
        return PointstyleAttributeElement(
            objectId,
            base.copy(reservedTail = r.readAttributeTail(ctx.generation)),
            version,
            dataFlags,
            pointSize,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as PointstyleAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeU8(element.dataFlags.toUByte())
        w.writeF32(element.pointSize)
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

/** Row-major identity matrix, the unstored-element default of Figure 63. */
private val IDENTITY_4X4: List<Double> =
    listOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

private object GeometricTransformAttributeCodec :
    LsgElementCodec(ObjectTypeIds.GEOMETRIC_TRANSFORM_ATTRIBUTE, "Geometric Transform Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): GeometricTransformAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val mask = r.readU16().toInt()
        val values = IDENTITY_4X4.toMutableList()
        var bits = mask
        for (i in 0 until 16) {
            if (bits and 0x8000 != 0) {
                values[i] = r.readF64()
            }
            bits = bits shl 1
        }
        val tail = r.readAttributeTail(ctx.generation)
        return GeometricTransformAttributeElement(objectId, base.copy(reservedTail = tail), version, mask, Mx4F64(values))
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as GeometricTransformAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeU16(element.storedValuesMask.toUShort())
        var bits = element.storedValuesMask
        for (i in 0 until 16) {
            if (bits and 0x8000 != 0) {
                w.writeF64(element.matrix.values[i])
            }
            bits = bits shl 1
        }
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private fun readMappingSurfaceData(
    r: ByteReader,
    g: LsgGeneration,
): MappingSurfaceData = MappingSurfaceData(r.readVersionNumber(g), r.readMx4F64(), r.readI32())

private fun writeMappingSurfaceData(
    w: ByteWriter,
    g: LsgGeneration,
    data: MappingSurfaceData,
) {
    w.writeVersionNumber(g, data.version)
    w.writeMx4F64(data.matrix)
    w.writeI32(data.coordSystem)
}

private class MappingSurfaceCodec(
    typeId: Guid,
    typeName: String,
    private val construct: (Int, MappingSurfaceData) -> TypedLsgElement,
    private val dataOf: (TypedLsgElement) -> MappingSurfaceData,
) : LsgElementCodec(typeId, typeName, 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = construct(objectId, readMappingSurfaceData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeMappingSurfaceData(w, g, dataOf(element))
}

private val MappingPlaneCodec =
    MappingSurfaceCodec(
        ObjectTypeIds.MAPPING_PLANE,
        "Mapping Plane Element",
        ::MappingPlaneElement,
    ) { (it as MappingPlaneElement).data }

private val MappingCylinderCodec =
    MappingSurfaceCodec(
        ObjectTypeIds.MAPPING_CYLINDER,
        "Mapping Cylinder Element",
        ::MappingCylinderElement,
    ) { (it as MappingCylinderElement).data }

private val MappingSphereCodec =
    MappingSurfaceCodec(
        ObjectTypeIds.MAPPING_SPHERE,
        "Mapping Sphere Element",
        ::MappingSphereElement,
    ) { (it as MappingSphereElement).data }

private val MappingTriPlanarCodec =
    MappingSurfaceCodec(
        ObjectTypeIds.MAPPING_TRIPLANAR,
        "Mapping TriPlanar Element",
        ::MappingTriPlanarElement,
    ) { (it as MappingTriPlanarElement).data }

private object TextureCoordinateGeneratorAttributeCodec :
    LsgElementCodec(
        ObjectTypeIds.TEXTURE_COORDINATE_GENERATOR_ATTRIBUTE,
        "Texture Coordinate Generator Attribute Element",
        3,
        v9Layout = false,
    ) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): TextureCoordinateGeneratorAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val channel = r.readI32()
        val surface =
            when (val nested = decodeElementFrame(r, ctx, "mapping surface of texture coordinate generator")) {
                is FrameResult.Element -> nested.element
                else -> throw JtFormatException("mapping surface of the texture coordinate generator is not a valid element frame")
            }
        // 10.5 tail placement after the nested element is derived, not fixture-verified —
        // the strict length check turns a wrong derivation into an opaque carry.
        val tail = r.readAttributeTail(ctx.generation)
        return TextureCoordinateGeneratorAttributeElement(objectId, base.copy(reservedTail = tail), version, channel, surface)
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as TextureCoordinateGeneratorAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.texCoordChannel)
        encodeElementFrame(w, g, element.mappingSurface)
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private fun readTextureEnvironment(r: ByteReader): TextureEnvironment =
    TextureEnvironment(
        r.readI32(),
        r.readI32(),
        r.readI32(),
        r.readI32(),
        r.readI32(),
        r.readI32(),
        r.readI32(),
        r.readI32(),
        r.readRgba(),
        r.readRgba(),
        r.readMx4F32(),
    )

private fun writeTextureEnvironment(
    w: ByteWriter,
    value: TextureEnvironment,
) {
    w.writeI32(value.borderMode)
    w.writeI32(value.mipmapMagnificationFilter)
    w.writeI32(value.mipmapMinificationFilter)
    w.writeI32(value.sDimenWrapMode)
    w.writeI32(value.tDimenWrapMode)
    w.writeI32(value.rDimenWrapMode)
    w.writeI32(value.blendType)
    w.writeI32(value.internalCompressionLevel)
    w.writeRgba(value.blendColour)
    w.writeRgba(value.borderColour)
    w.writeMx4F32(value.textureTransform)
}

private fun readImageFormatDescription(r: ByteReader): ImageFormatDescription =
    ImageFormatDescription(
        r.readU32(),
        r.readU32(),
        r.readI16().toInt(),
        r.readI16().toInt(),
        r.readI16().toInt(),
        r.readI16().toInt(),
        r.readI16().toInt(),
        r.readI16().toInt(),
        r.readU32(),
        r.readI16().toInt(),
    )

private fun writeImageFormatDescription(
    w: ByteWriter,
    value: ImageFormatDescription,
) {
    w.writeU32(value.pixelFormat)
    w.writeU32(value.pixelDataType)
    w.writeI16(value.dimensionality.toShort())
    w.writeI16(value.rowAlignment.toShort())
    w.writeI16(value.width.toShort())
    w.writeI16(value.height.toShort())
    w.writeI16(value.depth.toShort())
    w.writeI16(value.numberBorderTexels.toShort())
    w.writeU32(value.sharedImageFlag)
    w.writeI16(value.mipmapsCount.toShort())
}

private fun readInlineTextureImage(r: ByteReader): InlineTextureImage {
    val format = readImageFormatDescription(r)
    val totalSize = r.readI32()
    if (format.mipmapsCount < 0) throw JtFormatException("negative mipmaps count ${format.mipmapsCount}")
    val mipmaps =
        List(format.mipmapsCount) {
            val byteCount = r.readI32()
            if (byteCount < 0) throw JtFormatException("negative mipmap image byte count $byteCount")
            r.readBytes(byteCount).toBytes()
        }
    return InlineTextureImage(format, totalSize, mipmaps)
}

private fun writeInlineTextureImage(
    w: ByteWriter,
    value: InlineTextureImage,
) {
    writeImageFormatDescription(w, value.format)
    w.writeI32(value.totalImageDataSize)
    for (mipmap in value.mipmapImages) {
        w.writeI32(mipmap.size)
        w.writeBytes(mipmap)
    }
}

private object TextureImageAttributeCodec :
    LsgElementCodec(ObjectTypeIds.TEXTURE_IMAGE_ATTRIBUTE, "Texture Image Attribute Element", 3, v9Layout = false) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): TextureImageAttributeElement {
        val base = readBaseAttributeData(r, ctx.generation)
        val version = r.readVersionNumber(ctx.generation)
        val textureType = r.readI32()
        val environment = readTextureEnvironment(r)
        val coordGen =
            TextureCoordGenerationParameters(
                List(4) { r.readI32() },
                List(4) { r.readPlaneF32() },
            )
        val textureChannel = r.readI32()
        val texCoordChannel = r.readI32()
        val emptyField = r.readU32()
        val inlineFlag = r.readU8().toInt()
        val imageCount = r.readCount("texture image", 1)
        val inlineImages: List<InlineTextureImage>
        val externalNames: List<String>
        if (inlineFlag == 1) {
            inlineImages = List(imageCount) { readInlineTextureImage(r) }
            externalNames = emptyList()
        } else {
            inlineImages = emptyList()
            externalNames = List(imageCount) { r.readMbString() }
        }
        return TextureImageAttributeElement(
            objectId,
            base.copy(reservedTail = r.readAttributeTail(ctx.generation)),
            version,
            TextureVers1Data(
                textureType, environment, coordGen, textureChannel, texCoordChannel,
                emptyField, inlineFlag, inlineImages, externalNames,
            ),
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as TextureImageAttributeElement
        writeBaseAttributeData(w, g, element.baseAttribute)
        w.writeVersionNumber(g, element.version)
        val data = element.textureData
        w.writeI32(data.textureType)
        writeTextureEnvironment(w, data.environment)
        for (mode in data.coordGenerationParameters.texCoordGenModes) w.writeI32(mode)
        for (plane in data.coordGenerationParameters.texCoordReferencePlanes) w.writePlaneF32(plane)
        w.writeI32(data.textureChannel)
        w.writeI32(data.texCoordChannel)
        w.writeU32(data.emptyField)
        w.writeU8(data.inlineImageStorageFlag.toUByte())
        if (data.inlineImageStorageFlag == 1) {
            w.writeI32(data.inlineImages.size)
            for (image in data.inlineImages) writeInlineTextureImage(w, image)
        } else {
            w.writeI32(data.externalStorageNames.size)
            for (name in data.externalStorageNames) w.writeMbString(name)
        }
        w.writeAttributeTail(g, element.baseAttribute.reservedTail)
    }
}

private object BasePropertyAtomCodec : LsgElementCodec(ObjectTypeIds.BASE_PROPERTY_ATOM, "Base Property Atom Element", 5) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ) = BasePropertyAtomElement(objectId, readBasePropertyAtomData(r, ctx.generation))

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) = writeBasePropertyAtomData(w, g, (element as BasePropertyAtomElement).baseAtom)
}

private object StringPropertyAtomCodec : LsgElementCodec(ObjectTypeIds.STRING_PROPERTY_ATOM, "String Property Atom Element", 5) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): StringPropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return StringPropertyAtomElement(objectId, base, r.readVersionNumber(ctx.generation), r.readMbString())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as StringPropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeMbString(element.value)
    }
}

private object IntegerPropertyAtomCodec : LsgElementCodec(ObjectTypeIds.INTEGER_PROPERTY_ATOM, "Integer Property Atom Element", 5) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): IntegerPropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return IntegerPropertyAtomElement(objectId, base, r.readVersionNumber(ctx.generation), r.readI32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as IntegerPropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.value)
    }
}

private object FloatingPointPropertyAtomCodec :
    LsgElementCodec(ObjectTypeIds.FLOATING_POINT_PROPERTY_ATOM, "Floating Point Property Atom Element", 5) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): FloatingPointPropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return FloatingPointPropertyAtomElement(objectId, base, r.readVersionNumber(ctx.generation), r.readF32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as FloatingPointPropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeF32(element.value)
    }
}

private object JtObjectReferencePropertyAtomCodec :
    LsgElementCodec(ObjectTypeIds.JT_OBJECT_REFERENCE_PROPERTY_ATOM, "JT Object Reference Property Atom Element", 6) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): JtObjectReferencePropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return JtObjectReferencePropertyAtomElement(objectId, base, r.readVersionNumber(ctx.generation), r.readI32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as JtObjectReferencePropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeI32(element.referencedObjectId)
    }
}

private object DatePropertyAtomCodec : LsgElementCodec(ObjectTypeIds.DATE_PROPERTY_ATOM, "Date Property Atom Element", 5) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): DatePropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return DatePropertyAtomElement(
            objectId,
            base,
            r.readVersionNumber(ctx.generation),
            r.readI16().toInt(),
            r.readI16().toInt(),
            r.readI16().toInt(),
            r.readI16().toInt(),
            r.readI16().toInt(),
            r.readI16().toInt(),
            // 10.5 appends an F32 the v10.0 reference does not document (DESIGN.md delta 26).
            if (ctx.generation == LsgGeneration.V10_5) r.readF32() else null,
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as DatePropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeI16(element.year.toShort())
        w.writeI16(element.month.toShort())
        w.writeI16(element.day.toShort())
        w.writeI16(element.hour.toShort())
        w.writeI16(element.minute.toShort())
        w.writeI16(element.second.toShort())
        if (g == LsgGeneration.V10_5) {
            w.writeF32(element.trailingField ?: 0f)
        }
    }
}

private object LateLoadedPropertyAtomCodec :
    LsgElementCodec(ObjectTypeIds.LATE_LOADED_PROPERTY_ATOM, "Late Loaded Property Atom Element", 8) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): LateLoadedPropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return LateLoadedPropertyAtomElement(
            objectId,
            base,
            r.readVersionNumber(ctx.generation),
            r.readGuid(),
            r.readI32(),
            r.readI32(),
            // 10.5 drops the Reserved field Figure 76 documents (DESIGN.md delta 25).
            if (ctx.generation == LsgGeneration.V10_5) null else r.readI32(),
        )
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as LateLoadedPropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeGuid(element.segmentId)
        w.writeI32(element.segmentType)
        w.writeI32(element.payloadObjectId)
        if (g != LsgGeneration.V10_5) {
            // Figure 76: "guaranteed to always be greater than or equal to 1".
            w.writeI32(element.reserved ?: 1)
        }
    }
}

private object Vector4fPropertyAtomCodec :
    LsgElementCodec(ObjectTypeIds.VECTOR4F_PROPERTY_ATOM, "Vector4f Property Atom Element", 5) {
    override fun decode(
        r: ByteReader,
        ctx: LsgDecodeContext,
        objectId: Int,
    ): Vector4fPropertyAtomElement {
        val base = readBasePropertyAtomData(r, ctx.generation)
        return Vector4fPropertyAtomElement(objectId, base, r.readVersionNumber(ctx.generation), r.readVec4F32())
    }

    override fun encode(
        w: ByteWriter,
        g: LsgGeneration,
        element: TypedLsgElement,
    ) {
        element as Vector4fPropertyAtomElement
        writeBasePropertyAtomData(w, g, element.baseAtom)
        w.writeVersionNumber(g, element.version)
        w.writeVec4F32(element.value)
    }
}

/** The codec registry — one entry per §6 element type of Annex A. */
internal object LsgElementCodecs {
    private val codecs: Map<Guid, LsgElementCodec> =
        listOf(
            BaseNodeCodec,
            PartitionNodeCodec,
            GroupNodeCodec,
            InstanceNodeCodec,
            PartNodeCodec,
            MetaDataNodeCodec,
            LodNodeCodec,
            RangeLodNodeCodec,
            SwitchNodeCodec,
            BaseShapeNodeCodec,
            VertexShapeNodeCodec,
            TriStripSetShapeNodeCodec,
            PolylineSetShapeNodeCodec,
            PointSetShapeNodeCodec,
            PolygonSetShapeNodeCodec,
            NullShapeNodeCodec,
            PrimitiveSetShapeNodeCodec,
            MaterialAttributeCodec,
            DrawStyleAttributeCodec,
            LightSetAttributeCodec,
            InfiniteLightAttributeCodec,
            PointLightAttributeCodec,
            LinestyleAttributeCodec,
            PointstyleAttributeCodec,
            GeometricTransformAttributeCodec,
            TextureCoordinateGeneratorAttributeCodec,
            TextureImageAttributeCodec,
            MappingPlaneCodec,
            MappingCylinderCodec,
            MappingSphereCodec,
            MappingTriPlanarCodec,
            BasePropertyAtomCodec,
            StringPropertyAtomCodec,
            IntegerPropertyAtomCodec,
            FloatingPointPropertyAtomCodec,
            JtObjectReferencePropertyAtomCodec,
            DatePropertyAtomCodec,
            LateLoadedPropertyAtomCodec,
            Vector4fPropertyAtomCodec,
        ).associateBy { it.typeId }

    fun byTypeId(typeId: Guid): LsgElementCodec? = codecs[typeId]
}

// ---------------------------------------------------------------------------
// Element framing
// ---------------------------------------------------------------------------

/** The outcome of reading one element frame position. */
internal sealed class FrameResult {
    /** A decoded element — typed, or opaque with a note already recorded. */
    class Element(val element: LsgElement) : FrameResult()

    /** The 16-byte end-of-elements marker. */
    object EndMarker : FrameResult()

    /** No valid frame at this position; nothing was consumed. */
    object Invalid : FrameResult()
}

/**
 * Reads one element frame from [r]: the I32 length, the Object Type ID, and the typed decode
 * of the body — falling back to [OpaqueLsgElement] with a named note on every failure path.
 * On [FrameResult.Invalid] the reader is restored; otherwise it stands after the frame.
 */
internal fun decodeElementFrame(
    r: ByteReader,
    ctx: LsgDecodeContext,
    location: String,
): FrameResult {
    val start = r.position
    if (r.remaining < 4 + 16) return FrameResult.Invalid
    val length = r.readI32()
    if (length < 16 || length > r.remaining) {
        r.position = start
        return FrameResult.Invalid
    }
    val typeId = r.readGuid()
    if (typeId == Guid.END_OF_ELEMENTS && length == 16) {
        return FrameResult.EndMarker
    }
    val body = r.readBytes(length - 16)
    val scannedBaseType = if (body.isNotEmpty()) body[0].toInt() and 0xFF else null

    fun opaque(): FrameResult = FrameResult.Element(OpaqueLsgElement(typeId, scannedBaseType, body.toBytes()))

    val codec = LsgElementCodecs.byTypeId(typeId)
    if (codec == null) {
        ctx.notes.add(LoadNote.UnknownElementType(typeId, location))
        return opaque()
    }
    if (!codec.decodableIn(ctx.generation)) {
        ctx.notes.add(LoadNote.ElementLayoutUnverified(typeId, codec.typeName, ctx.generation.name, location))
        return opaque()
    }
    return try {
        val sub = ByteReader(body, r.order)
        val baseType = sub.readU8().toInt()
        if (baseType != codec.objectBaseType) {
            throw JtFormatException("object base type $baseType does not match ${codec.objectBaseType} of ${codec.typeName}")
        }
        val objectId = sub.readI32()
        val element = codec.decode(sub, ctx, objectId)
        if (sub.remaining != 0) {
            throw JtFormatException("${sub.remaining} bytes of the element body were not consumed")
        }
        FrameResult.Element(element)
    } catch (e: JtFormatException) {
        ctx.notes.add(LoadNote.ElementDecodeFailed(typeId, codec.typeName, location, e.message ?: "decode failed"))
        opaque()
    }
}

/** Serializes one element frame; the exact inverse of [decodeElementFrame]. */
internal fun encodeElementFrame(
    w: ByteWriter,
    g: LsgGeneration,
    element: LsgElement,
) {
    when (element) {
        is OpaqueLsgElement -> {
            w.writeI32(16 + element.body.size)
            w.writeGuid(element.objectTypeId)
            w.writeBytes(element.body)
        }
        is TypedLsgElement -> {
            val codec =
                LsgElementCodecs.byTypeId(element.objectTypeId)
                    ?: error("no codec for element type ${element.objectTypeId}")
            val bodyWriter = ByteWriter(w.order)
            bodyWriter.writeU8(codec.objectBaseType.toUByte())
            bodyWriter.writeI32(element.objectId)
            codec.encode(bodyWriter, g, element)
            val body = bodyWriter.toByteArray()
            w.writeI32(16 + body.size)
            w.writeGuid(element.objectTypeId)
            w.writeBytes(body)
        }
    }
}

/** Serializes the 16-byte end-of-elements marker frame. */
internal fun encodeEndOfElements(w: ByteWriter) {
    w.writeI32(16)
    w.writeGuid(Guid.END_OF_ELEMENTS)
}
