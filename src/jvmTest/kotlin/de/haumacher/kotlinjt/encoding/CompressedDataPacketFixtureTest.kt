package de.haumacher.kotlinjt.encoding

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.JtFormatException
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.io.ByteReader
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.shape.Int32Cdp
import de.haumacher.kotlinjt.shape.ShapeLodDocument
import de.haumacher.kotlinjt.shape.shapeLodSegments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.IdentityHashMap

/**
 * The real-file acceptance for the two 9.5-only compressed-data-packet families — **the hook
 * that fires the day the corpus grows a file carrying one**, and, in the meantime, the strongest
 * evidence the corpus can give about codecs it does not contain.
 *
 * Today no fixture holds a JT B-Rep, a JT ULP or a JT 9 Wireframe segment, so no fixture holds
 * an Int32 CDP Mk. 1 or a Float64 CDP packet; both decoders are spec-derived (9.5 §8.1.1 /
 * §8.1.3 with Appendix C §2.1 / §3.1) and the first battery below skips *visibly* until a file
 * arrives.
 *
 * The second and third batteries do not wait. The corpus is full of **Mk. 2** arithmetic packets
 * — real entropy-coded CodeText, written by real producers, whose decode is verified by stored
 * hashes — and the two generations share a decoder core. So every such packet is re-framed into
 * the Mk. 1 layout and required to decode to the same values, and then offered to the Mk. 1
 * reader in its *own* framing and required not to be read as if it belonged there. That tests
 * the Mk. 1 arithmetic driver, the probability-context reader and the generation split against
 * data no one in this project produced.
 *
 * Fixture files are never named in committed code (issue #1's fixture policy).
 */
class CompressedDataPacketFixtureTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf("fixtures", "fixtures-local")
            .flatMap { File(repoRoot(), it).listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.toList().orEmpty() }
            .sortedBy { it.name }

    @TestFactory
    fun compressedDataPackets(): List<DynamicNode> {
        val fixtures = fixtures()
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — CDP real-file suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture ->
            val bytes = fixture.readBytes()
            dynamicContainer(
                fixture.name,
                listOf(
                    mk1AndFloat64CarryingSegments(bytes),
                    everyArithmeticPacketDecodesTheSameWhenReframedAsMk1(bytes),
                    noMk2PacketIsSilentlyReadAsMk1(bytes),
                ),
            )
        }
    }

    /**
     * The hook. A segment of one of these kinds is written with `Int32CDP` Mk. 1 and — for the
     * curve payloads — `Float64CDP` throughout, so the day one appears the codecs of this package
     * acquire their first real bytes and the elements that contain them stop being an honest
     * deferral. Until then this skips visibly, naming what the corpus is missing.
     *
     * The opacity doctrine for these segments is `BrepOpacityTest`'s job and is not repeated
     * here; what this asserts is the precondition this package cares about — that the segment
     * reaches element framing at all, so there is something for a Mk. 1 reader to be pointed at.
     *
     * spec: 9.5 §7.2.3 (JT B-Rep), §7.2.8 (ULP), §7.2.5 Figure 130 (JT 9 Wireframe Rep)
     */
    private fun mk1AndFloat64CarryingSegments(bytes: ByteArray): DynamicNode =
        dynamicTest("segments written with Mk. 1 / Float64 packets reach element framing") {
            val file = JtFile.parse(bytes)
            val generation = LsgGeneration.of(file.header.version)
            val candidates =
                file.segments.filter { segment ->
                    when (segment.kind) {
                        SegmentKind.JT_BREP, SegmentKind.ULP -> true
                        SegmentKind.WIREFRAME -> generation == LsgGeneration.V9
                        else -> false
                    }
                }
            assumeTrue(
                candidates.isNotEmpty(),
                "no JT B-Rep, ULP or JT 9 Wireframe segment — the Mk. 1 and Float64 codecs have no fixture yet",
            )
            for (segment in candidates) {
                assertTrue(
                    segment.elementData != null,
                    "${segment.tocEntry.segmentId}: a ${segment.kind?.label} segment's element data was not decoded",
                )
            }
        }

    /**
     * The differential test. Every Mk. 2 arithmetic packet in the fixture is rebuilt as a Mk. 1
     * packet — different field order, a table-count byte, a `Next Context` field per entry, an
     * explicit out-of-band count, a `VecU32` CodeText with its own length word — around *the
     * same CodeText bits and the same histogram*. The Mk. 1 reader must produce the same values.
     *
     * What it proves: the Mk. 1 arithmetic driver and probability-context reader agree with the
     * fixture-verified Mk. 2 path on real entropy-coded data. What it does not prove: that a
     * producer frames a Mk. 1 packet the way Figure 218 is read here — only a Mk. 1 file can.
     *
     * spec: 9.5 Figure 218, Figure 219, Appendix C §3.1
     */
    private fun everyArithmeticPacketDecodesTheSameWhenReframedAsMk1(bytes: ByteArray): DynamicNode =
        dynamicTest("every arithmetic packet decodes identically when re-framed as an Int32 CDP Mk. 1") {
            val packets = arithmeticPackets(bytes)
            assumeTrue(packets.isNotEmpty(), "no arithmetic Int32 packets in this fixture")
            var reframed = 0
            for (packet in packets) {
                val mk1 = reframeAsMk1(packet) ?: continue
                val reader = ByteReader(mk1, Endianness.LITTLE_ENDIAN)
                val decoded = Int32CdpMk1.read(reader)
                assertEquals(mk1.size, reader.position, "the re-framed packet must consume exactly its bytes")
                assertEquals(packet.values, decoded.values, "Mk. 1 and Mk. 2 disagree on the same CodeText")
                val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
                decoded.encode(writer)
                assertTrue(mk1.contentEquals(writer.toByteArray()), "the re-framed packet must re-encode byte-identically")
                reframed += 1
            }
            assertTrue(reframed > 0, "no packet could be re-framed — the differential test proved nothing")
        }

    /**
     * The failure mode this package exists to prevent, on real bytes: 9.5 binds the two Int32
     * packet generations statically, per field, so nothing in the stream catches a reader that
     * picked the wrong one. No Mk. 2 packet in the corpus may be read as a Mk. 1 packet that both
     * consumes exactly its bytes *and* yields its values — that combination is what "silently
     * misread" means.
     *
     * The corpus turns out to give the strong form: **every** arithmetic Mk. 2 packet in all
     * three fixtures is refused outright, and refused with a named [JtFormatException] rather
     * than by running off the end of an array — the two things the doctrine asks of a reader
     * handed input it cannot make sense of.
     *
     * spec: 9.5 §8.1.1, §8.1.2
     */
    private fun noMk2PacketIsSilentlyReadAsMk1(bytes: ByteArray): DynamicNode =
        dynamicTest("no Mk. 2 packet is silently readable as a Mk. 1 packet") {
            val packets = arithmeticPackets(bytes)
            assumeTrue(packets.isNotEmpty(), "no arithmetic Int32 packets in this fixture")
            var refused = 0
            for (packet in packets) {
                val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
                packet.encode(writer)
                val mk2 = writer.toByteArray()
                val reader = ByteReader(mk2, Endianness.LITTLE_ENDIAN)
                // Only JtFormatException is caught on purpose: anything else escaping the
                // reader — an index out of bounds, an arithmetic overflow — is a refusal that
                // does not speak, and must fail this test rather than be counted.
                val misread =
                    try {
                        val decoded = Int32CdpMk1.read(reader)
                        reader.position == mk2.size && decoded.values == packet.values
                    } catch (e: JtFormatException) {
                        refused += 1
                        false
                    }
                assertFalse(misread, "a Mk. 2 packet of ${packet.valueCount} values read cleanly as a Mk. 1 packet")
            }
            assertEquals(packets.size, refused, "every Mk. 2 packet must be refused by name, not merely fail to match")
        }

    /**
     * Rebuilds [packet] in the Mk. 1 layout of Figure 218 around its own CodeText and histogram,
     * or returns `null` when the histogram cannot be expressed in a Mk. 1 context (an associated
     * value range wider than 32 bits).
     */
    private fun reframeAsMk1(packet: Int32Cdp): ByteArray? {
        val entries: List<TestEntry>
        val codeText: List<Int>
        val codeTextLength: Int
        val outOfBand: List<Int>
        when (packet) {
            is Int32Cdp.Arithmetic -> {
                entries =
                    packet.probabilityContext.entries.map {
                        TestEntry(it.symbol, it.occurrenceCount, it.associatedValue)
                    }
                codeText = packet.codeText
                codeTextLength = packet.codeTextLength
                outOfBand = packet.outOfBand.values
            }
            is Int32Cdp.ArithmeticV10 -> {
                entries =
                    packet.probabilityContext.entries.mapIndexed { index, entry ->
                        TestEntry(if (entry.isEscape) -2 else index, entry.occurrenceCount, entry.associatedValue)
                    }
                codeText = packet.codeText
                codeTextLength = packet.codeTextLength
                outOfBand = packet.outOfBand?.values.orEmpty()
            }
            else -> return null
        }
        if (entries.isEmpty()) return null
        val minValue = entries.minOf { it.associatedValue.toLong() }
        val maxValue = entries.maxOf { it.associatedValue.toLong() }
        if (maxValue - minValue > Int.MAX_VALUE) return null
        val writer = ByteWriter(Endianness.LITTLE_ENDIAN)
        writer.writeU8(3u)
        writer.writeBytes(int32ProbabilityContexts1(listOf(entries)))
        writer.writeI32(outOfBand.size)
        if (outOfBand.isNotEmpty()) {
            writer.writeU8(0u)
            writer.writeVecU32(outOfBand)
        }
        writer.writeI32(codeTextLength)
        writer.writeI32(packet.valueCount)
        writer.writeVecU32(codeText)
        return writer.toByteArray()
    }

    /** Every arithmetic Int32 packet reachable from the fixture's decoded shape LOD bodies. */
    private fun arithmeticPackets(bytes: ByteArray): List<Int32Cdp> {
        val file = JtFile.parse(bytes)
        val found = mutableListOf<Int32Cdp>()
        for (segment in file.shapeLodSegments()) {
            val data = segment.elementData ?: continue
            val result =
                try {
                    ShapeLodDocument.decode(data, file.header.version, file.header.byteOrder)
                } catch (e: JtFormatException) {
                    continue
                }
            for (element in result.document.elements) collectPackets(element, found, IdentityHashMap())
        }
        return found.filter { it is Int32Cdp.Arithmetic || it is Int32Cdp.ArithmeticV10 }
    }

    /**
     * Walks the decoded element graph collecting every packet, nested ones included. Reflection
     * rather than a hand-written traversal on purpose: the point is to reach *all* of a real
     * file's packets, not the ones a test author remembered.
     */
    private fun collectPackets(
        value: Any?,
        into: MutableList<Int32Cdp>,
        seen: IdentityHashMap<Any, Any>,
    ) {
        if (value == null) return
        if (value is Int32Cdp) into.add(value)
        when (value) {
            is String, is Number, is Boolean, is Char, is Enum<*>, is ByteArray, is IntArray, is DoubleArray -> return
            else -> {}
        }
        if (seen.put(value, value) != null) return
        when (value) {
            is Iterable<*> -> {
                for (item in value) collectPackets(item, into, seen)
                return
            }
            is Map<*, *> -> {
                for (entry in value.entries) {
                    collectPackets(entry.key, into, seen)
                    collectPackets(entry.value, into, seen)
                }
                return
            }
            is Array<*> -> {
                for (item in value) collectPackets(item, into, seen)
                return
            }
            else -> {}
        }
        val type = value.javaClass
        if (!type.name.startsWith("de.haumacher.kotlinjt")) return
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (field.type.isPrimitive) continue
                try {
                    field.isAccessible = true
                    collectPackets(field.get(value), into, seen)
                } catch (e: ReflectiveOperationException) {
                    continue
                } catch (e: RuntimeException) {
                    continue
                }
            }
            current = current.superclass
        }
    }
}
