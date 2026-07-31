package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtVersion
import de.haumacher.kotlinjt.TestFileAssembler
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.testGuid
import de.haumacher.kotlinjt.withSegmentPayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

// spec: Figure 20 — the LSG segment inside complete synthetic files, both generations:
// decode through Layer 0, model-level mutation, re-layout, model equality. The committed
// counterpart of the fixture battery (regression pinning, not conformance proof).
class LsgSyntheticFileTest {
    private fun lsgElementData(
        order: Endianness,
        generation: LsgGeneration,
        partName: String,
    ): ByteArray {
        val w = ByteWriter(order)
        w.writeBytes(
            lsgFrame(order, ObjectTypeIds.PARTITION_NODE, 1, 0) {
                writeTestGroupNodeData(generation, children = listOf(1))
                writeI32(0) // partition flags
                writeI32(0) // empty file name
                repeat(6) { writeF32(0f) } // transformed bbox
                writeF32(0f) // area
                repeat(6) { writeI32(0) } // count ranges
            },
        )
        w.writeBytes(
            lsgFrame(order, ObjectTypeIds.PART_NODE, 1, 1) {
                writeTestGroupNodeData(generation)
                writeTestVersionNumber(generation) // meta data version
                writeTestVersionNumber(generation) // part version
                writeI32(0) // empty field
            },
        )
        w.writeI32(16)
        w.writeGuid(Guid.END_OF_ELEMENTS)
        w.writeBytes(
            lsgFrame(order, ObjectTypeIds.STRING_PROPERTY_ATOM, 5, 2) {
                writeTestBasePropertyAtomData(generation)
                writeTestVersionNumber(generation)
                writeI32(partName.length)
                for (ch in partName) writeU16(ch.code.toUShort())
            },
        )
        w.writeI32(16)
        w.writeGuid(Guid.END_OF_ELEMENTS)
        // Property table: part node 1 → (key 2 / value 2 stands in for a name pair)
        w.writeI16(1)
        w.writeI32(1)
        w.writeI32(1)
        w.writeI32(2)
        w.writeI32(2)
        w.writeI32(0)
        return w.toByteArray()
    }

    private fun syntheticFile(
        order: Endianness,
        version: JtVersion,
        partName: String,
    ): Pair<ByteArray, Guid> {
        val lsgId = testGuid(1, 1, 1, 1)
        val otherId = testGuid(2, 2, 2, 2)
        val generation = LsgGeneration.of(version)
        val payload = encodeLsgSegmentPayload(lsgElementData(order, generation, partName).toBytes(), version, order)
        val shapePayload =
            de.haumacher.kotlinjt.elementBytes(order, testGuid(9, 9, 9, 9), 4, byteArrayOf(1, 2, 3, 4)) +
                de.haumacher.kotlinjt.endOfElementsBytes(order)
        val bytes =
            TestFileAssembler(order, version, lsgId)
                .addSegment(lsgId, 1, payload.toByteArray())
                .addSegment(otherId, 7, shapePayload)
                .build()
        return bytes to lsgId
    }

    // spec: Figure 20
    @Test
    fun syntheticLsgDecodesThroughTheFullStack() =
        forBothOrders { order ->
            for (version in listOf(JtVersion(9, 5), JtVersion(10, 0))) {
                val (bytes, _) = syntheticFile(order, version, "wheel")
                val file = JtFile.parse(bytes)
                assertEquals(emptyList(), file.notes, "synthetic file must parse note-free")
                val result = assertNotNull(file.decodeLsg())
                assertEquals(emptyList(), result.notes)
                val document = result.document
                assertEquals(2, document.graphElements.size)
                assertIs<PartitionNodeElement>(document.graphElements[0])
                assertIs<PartNodeElement>(document.graphElements[1])
                val atom = assertIs<StringPropertyAtomElement>(document.propertyAtoms.single())
                assertEquals("wheel", atom.value)
                assertEquals(1, document.propertyTable?.tables?.size)
                // Model-level losslessness on the element stream.
                assertContentEquals(
                    file.lsgSegment()?.elementData?.toByteArray(),
                    document.encode(order).toByteArray(),
                )
            }
        }

    // spec: §6 — a modified LSG re-layouts into a legal file, asserted by model equality
    @Test
    fun mutationProducesALegalModelEqualFile() =
        forBothOrders { order ->
            for (version in listOf(JtVersion(9, 5), JtVersion(10, 0))) {
                val (bytes, lsgId) = syntheticFile(order, version, "wheel")
                val file = JtFile.parse(bytes)
                val document = assertNotNull(file.decodeLsg()).document
                val atom = document.propertyAtoms.single() as StringPropertyAtomElement
                val mutated = document.copy(propertyAtoms = listOf(atom.copy(value = "wheel front left")))
                val payload = encodeLsgSegmentPayload(mutated.encode(order), version, order)
                val newFile = file.withSegmentPayload(lsgId, payload)
                assertEquals(emptyList(), newFile.notes, "the mutated file must be legal")
                val reDecoded = assertNotNull(newFile.decodeLsg())
                assertEquals(emptyList(), reDecoded.notes)
                assertEquals(mutated, reDecoded.document, "model equality after mutation + re-layout")
                // The untouched segment re-emits its raw payload.
                assertEquals(
                    file.segments.first { it.tocEntry.segmentId != lsgId }.payload,
                    newFile.segments.first { it.tocEntry.segmentId != lsgId }.payload,
                )
            }
        }
}
