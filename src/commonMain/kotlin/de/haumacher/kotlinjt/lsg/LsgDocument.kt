package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.JtSegment
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.LoadNote
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.codec.zlibDeflate
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.toBytes

/**
 * The typed document model of an LSG segment's element data (§6, Figure 20): the graph
 * element list, the property atom list — each closed by the end-of-elements marker — and the
 * Property Table. Layer 1's losslessness guarantee is at the element-stream level:
 * [decode] followed by [encode] reproduces the inflated element data byte-identically, for
 * well-formed and damaged streams alike (undecodable content is carried opaquely in
 * [OpaqueLsgElement]s and [trailing], each refusal named by a note).
 */
data class LsgDocument(
    val generation: LsgGeneration,
    val graphElements: List<LsgElement>,
    /** Whether the graph element list was closed by the end-of-elements marker. */
    val graphElementsTerminated: Boolean,
    val propertyAtoms: List<LsgElement>,
    /** Whether the property atom list was closed by the end-of-elements marker. */
    val propertyAtomsTerminated: Boolean,
    /** The typed Property Table, `null` when missing or unparseable (a note says which). */
    val propertyTable: PropertyTable?,
    /** Bytes after the recognized structure, preserved verbatim (empty on healthy streams). */
    val trailing: Bytes,
) {
    /** All elements of both lists, in stream order. */
    val allElements: List<LsgElement> get() = graphElements + propertyAtoms

    /** Serializes the document back to element-stream bytes — the exact inverse of [decode]. */
    fun encode(order: Endianness): Bytes {
        val writer = ByteWriter(order)
        for (element in graphElements) {
            encodeElementFrame(writer, generation, element)
        }
        if (graphElementsTerminated) {
            encodeEndOfElements(writer)
        }
        if (propertyAtoms.isNotEmpty() || propertyAtomsTerminated) {
            for (element in propertyAtoms) {
                encodeElementFrame(writer, generation, element)
            }
            if (propertyAtomsTerminated) {
                encodeEndOfElements(writer)
            }
        }
        propertyTable?.let { writePropertyTable(writer, it) }
        writer.writeBytes(trailing)
        return writer.toByteArray().toBytes()
    }

    companion object {
        /**
         * Decodes the (already inflated) element data of an LSG segment. Never throws for
         * content problems: whatever does not decode is carried opaquely and named by a note
         * in the result.
         */
        fun decode(
            elementData: Bytes,
            version: JtVersion,
            order: Endianness,
        ): LsgDecodeResult {
            val generation = LsgGeneration.of(version)
            val notes = mutableListOf<LoadNote>()
            val ctx = LsgDecodeContext(generation, notes)
            val bytes = elementData.toByteArray()
            val reader = ByteReader(bytes, order)

            val (graph, graphTerminated) = walkList(reader, ctx, "LSG graph element")
            var atoms = emptyList<LsgElement>()
            var atomsTerminated = false
            var table: PropertyTable? = null
            var trailing = Bytes.EMPTY

            if (!graphTerminated) {
                if (reader.remaining > 0) {
                    notes.add(
                        LoadNote.LsgStructureUnrecognized(
                            "graph element list breaks off at offset ${reader.position} of ${bytes.size}",
                        ),
                    )
                    trailing = Bytes.of(bytes, reader.position, bytes.size)
                } else {
                    notes.add(LoadNote.LsgStructureUnrecognized("graph element list is not terminated"))
                }
            } else if (reader.remaining == 0) {
                notes.add(LoadNote.LsgStructureUnrecognized("stream ends before the property atom list"))
            } else {
                val (atomList, terminated) = walkList(reader, ctx, "LSG property atom")
                atoms = atomList
                atomsTerminated = terminated
                if (!terminated) {
                    if (reader.remaining > 0) {
                        notes.add(
                            LoadNote.LsgStructureUnrecognized(
                                "property atom list breaks off at offset ${reader.position} of ${bytes.size}",
                            ),
                        )
                        trailing = Bytes.of(bytes, reader.position, bytes.size)
                    } else {
                        notes.add(LoadNote.LsgStructureUnrecognized("property atom list is not terminated"))
                    }
                } else if (reader.remaining == 0) {
                    notes.add(LoadNote.PropertyTableMissing("stream ends after the property atom list"))
                } else {
                    val tableStart = reader.position
                    try {
                        table = readPropertyTable(reader)
                        if (reader.remaining != 0) {
                            throw JtFormatException("${reader.remaining} bytes follow the Property Table")
                        }
                    } catch (e: JtFormatException) {
                        table = null
                        notes.add(LoadNote.PropertyTableUnrecognized(e.message ?: "parse failed"))
                        trailing = Bytes.of(bytes, tableStart, bytes.size)
                    }
                }
            }

            val document =
                LsgDocument(
                    generation,
                    graph,
                    graphTerminated,
                    atoms,
                    atomsTerminated,
                    table,
                    trailing,
                )
            return LsgDecodeResult(document, notes)
        }

        private fun walkList(
            reader: ByteReader,
            ctx: LsgDecodeContext,
            what: String,
        ): Pair<List<LsgElement>, Boolean> {
            val elements = mutableListOf<LsgElement>()
            while (true) {
                val location = "$what at offset ${reader.position}"
                when (val result = decodeElementFrame(reader, ctx, location)) {
                    is FrameResult.Element -> elements.add(result.element)
                    FrameResult.EndMarker -> return elements to true
                    FrameResult.Invalid -> return elements to false
                }
            }
        }
    }
}

/**
 * Reads a Property Table (Figure 78) — the structure both the LSG segment and the shape LOD
 * segments carry after their element lists (DESIGN.md observation on the 6-byte shape tail).
 */
internal fun readPropertyTable(r: ByteReader): PropertyTable {
    val version = r.readI16().toInt()
    val count = r.readI32()
    // Each table needs at least the element object id and the terminating key.
    if (count < 0 || count > r.remaining / 8) {
        throw JtFormatException("Element Property Table count $count does not fit the remaining ${r.remaining} bytes")
    }
    val tables =
        List(count) {
            val elementObjectId = r.readI32()
            val entries = mutableListOf<PropertyEntry>()
            while (true) {
                val key = r.readI32()
                if (key == 0) break
                entries.add(PropertyEntry(key, r.readI32()))
            }
            ElementPropertyTable(elementObjectId, entries)
        }
    return PropertyTable(version, tables)
}

/** Serializes a Property Table — the exact inverse of [readPropertyTable]. */
internal fun writePropertyTable(
    w: ByteWriter,
    table: PropertyTable,
) {
    w.writeI16(table.version.toShort())
    w.writeI32(table.tables.size)
    for (elementTable in table.tables) {
        w.writeI32(elementTable.elementObjectId)
        for (entry in elementTable.entries) {
            w.writeI32(entry.keyPropertyAtomObjectId)
            w.writeI32(entry.valuePropertyAtomObjectId)
        }
        w.writeI32(0)
    }
}

/** The outcome of an LSG document decode: the document plus the named refusals, if any. */
data class LsgDecodeResult(
    val document: LsgDocument,
    val notes: List<LoadNote>,
)

/** The file's LSG segment: the one the header names, or the first of the LSG kind. */
fun JtFile.lsgSegment(): JtSegment? =
    segments.firstOrNull { it.tocEntry.segmentId == header.lsgSegmentId && it.kind == SegmentKind.LOGICAL_SCENE_GRAPH }
        ?: segments.firstOrNull { it.kind == SegmentKind.LOGICAL_SCENE_GRAPH }

/**
 * Decodes the file's LSG segment into the typed document model; `null` when the file has no
 * LSG segment or Layer 0 could not produce its element data (its notes say why).
 */
fun JtFile.decodeLsg(): LsgDecodeResult? {
    val segment = lsgSegment() ?: return null
    val elementData = segment.elementData ?: return null
    return LsgDocument.decode(elementData, header.version, header.byteOrder)
}

/**
 * Wraps LSG element data in the segment-wide compression fields (clause 5.1.3.2.2), producing
 * a complete LSG segment payload: JT 9 deflates with ZLIB (flag 2, algorithm 2 — the v9
 * generation's codec); JT 10 stores plainly (algorithm 1), the writer's simplest legal
 * encoding until an LZMA encoder has a fixture to prove itself against.
 */
fun encodeLsgSegmentPayload(
    elementData: Bytes,
    version: JtVersion,
    order: Endianness,
): Bytes {
    val writer = ByteWriter(order)
    if (LsgGeneration.of(version) == LsgGeneration.V9) {
        val deflated = zlibDeflate(elementData.toByteArray())
        writer.writeU32(2u)
        writer.writeI32(1 + deflated.size)
        writer.writeU8(2u)
        writer.writeBytes(deflated)
    } else {
        writer.writeU32(0u)
        writer.writeI32(1 + elementData.size)
        writer.writeU8(1u)
        writer.writeBytes(elementData)
    }
    return writer.toByteArray().toBytes()
}
