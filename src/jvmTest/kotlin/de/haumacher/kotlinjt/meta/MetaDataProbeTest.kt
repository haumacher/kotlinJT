package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.codec.zlibDeflate
import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.toBytes
import de.haumacher.kotlinjt.scene.readScene
import de.haumacher.kotlinjt.withSegmentPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Probes that compose the §11 decoder with the layers around it, on real-producer files: the
 * whole-file mutation path (Layer 0 re-layout + Layer 1 model equality), the damage response,
 * and the Layer 2 seam's independence from meta data.
 */
class MetaDataProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixturesWithMetaData(): List<File> =
        listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
            .flatMap { it.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { f -> f.name }.orEmpty() }
            .filter { JtFile.parse(it.readBytes()).metaDataSegments().any { s -> s.elementData != null } }

    /** Wraps meta data element bytes in the segment-wide fields: stored, the simplest legal form. */
    private fun storedMetaPayload(
        elementData: ByteArray,
        order: de.haumacher.kotlinjt.io.Endianness,
        zlib: Boolean,
    ): de.haumacher.kotlinjt.io.Bytes {
        val writer = ByteWriter(order)
        if (zlib) {
            val deflated = zlibDeflate(elementData)
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

    /**
     * A model-level edit of a property bag survives a whole-file rewrite: the file stays as
     * legal as the original, every other segment keeps its raw payload, and the re-read meta
     * data document equals the edited model exactly.
     */
    @Test
    fun editingAPropertyBagYieldsALegalModelEqualFile() {
        val fixtures = fixturesWithMetaData()
        assumeTrue(fixtures.isNotEmpty(), "no fixture carries meta data segments")
        for (fixture in fixtures) {
            val bytes = fixture.readBytes()
            val file = JtFile.parse(bytes)
            val segment =
                file.metaDataSegments().firstOrNull { s ->
                    s.kind == SegmentKind.META_DATA &&
                        file.decodeMetaData(s)?.document?.propertyProxies?.any { it.properties.isNotEmpty() } == true
                }
            assumeTrue(segment != null, "${fixture.name}: no meta data segment with a non-empty bag")
            val decoded = file.decodeMetaData(segment!!)!!
            val document = decoded.document
            val index = document.elements.indexOfFirst { it is PropertyProxyMetaDataElement }
            val proxy = document.elements[index] as PropertyProxyMetaDataElement
            val first = proxy.properties.first()
            val edited =
                document.copy(
                    elements =
                        document.elements.toMutableList().also {
                            it[index] =
                                proxy.copy(
                                    properties =
                                        proxy.properties.toMutableList().also { props ->
                                            props[0] = first.copy(value = MetaPropertyValue.Text("probe value"))
                                        } + MetaProperty("ProbeKey::", MetaPropertyValue.Date(JtDate(2026, 8, 1, 0, 0, 0))),
                                )
                        },
                )
            val payload =
                storedMetaPayload(
                    edited.encode(file.header.byteOrder).toByteArray(),
                    file.header.byteOrder,
                    zlib = file.header.version.major < 10,
                )
            val rewritten = file.withSegmentPayload(segment.tocEntry.segmentId, payload)
            assertEquals(
                file.notes.map { it.name },
                rewritten.notes.map { it.name },
                "${fixture.name}: the mutated file must be as legal as the original",
            )
            val reSegment = rewritten.segments.first { it.tocEntry.segmentId == segment.tocEntry.segmentId }
            val reDecoded = rewritten.decodeMetaData(reSegment)
            assertNotNull(reDecoded, "${fixture.name}: the rewritten meta data segment did not decode")
            assertEquals(edited, reDecoded!!.document, "${fixture.name}: model equality after mutation + re-layout")
            assertEquals(decoded.notes.map { it.name }, reDecoded.notes.map { it.name })
            val reProxy = reDecoded.document.propertyProxies.first()
            assertEquals(MetaPropertyValue.Text("probe value"), reProxy.properties.first().value)
            assertEquals(
                MetaPropertyValue.Date(JtDate(2026, 8, 1, 0, 0, 0)),
                reProxy.propertyMap["ProbeKey::"],
                "${fixture.name}: the appended Date property survives the wire",
            )
            // Every other segment re-emits its raw payload untouched.
            for ((before, after) in file.segments.zip(rewritten.segments)) {
                if (before.tocEntry.segmentId != segment.tocEntry.segmentId) {
                    assertEquals(before.payload, after.payload, "${fixture.name}: unmodified segment payload drifted")
                }
            }
        }
    }

    /**
     * Damage inside a meta data body never produces "identical document and no note": the
     * decode either lands on a *different* document or names a refusal — and the bytes come
     * back whole either way.
     */
    @Test
    fun aFlippedByteIsEitherVisibleInTheModelOrNamedInANote() {
        val fixtures = fixturesWithMetaData()
        assumeTrue(fixtures.isNotEmpty(), "no fixture carries meta data segments")
        var checked = 0
        for (fixture in fixtures) {
            val file = JtFile.parse(fixture.readBytes())
            for (segment in file.metaDataSegments().filter { it.elementData != null }) {
                val original = segment.elementData!!.toByteArray()
                val reference = MetaDataDocument.decode(original.toBytes(), file.header.version, file.header.byteOrder)
                // Probe a handful of positions spread across the body, skipping the frame header.
                val positions = (0 until 8).map { 24 + it * (original.size / 9) }.filter { it < original.size }
                for (position in positions) {
                    val damaged = original.copyOf()
                    damaged[position] = (damaged[position].toInt() xor 0x5A).toByte()
                    val result = MetaDataDocument.decode(damaged.toBytes(), file.header.version, file.header.byteOrder)
                    // Losslessness first: whatever we made of it, the bytes survive.
                    assertTrue(
                        damaged.contentEquals(result.document.encode(file.header.byteOrder).toByteArray()),
                        "${segment.tocEntry.segmentId}@$position: a damaged body did not re-serialize verbatim",
                    )
                    if (result.notes.map { it.name } == reference.notes.map { it.name }) {
                        assertNotEquals(
                            reference.document,
                            result.document,
                            "${segment.tocEntry.segmentId}@$position: a flipped byte produced an identical document " +
                                "with no new note — a silent misread",
                        )
                    }
                    checked++
                }
            }
        }
        println("META DATA DAMAGE PROBE: $checked flipped bytes, each visible or named")
    }

    /**
     * The Layer 2 seam is untouched by this package: a scene reads the same before and after
     * §11 became typed, and meta data segments contribute no scene notes. Layer 1 holds the
     * properties; the scene abstracts them, by design (issue #9 keeps §11 out of Layer 2).
     */
    @Test
    fun theSceneFacadeIsUnaffectedByTypedMetaData() {
        val fixtures = fixturesWithMetaData()
        assumeTrue(fixtures.isNotEmpty(), "no fixture carries meta data segments")
        for (fixture in fixtures) {
            val bytes = fixture.readBytes()
            val scene = readScene(bytes)
            assertTrue(
                scene.notes.none { it.name.startsWith("META_") || it.name.startsWith("PMI_") },
                "${fixture.name}: meta data refusals must not leak into the scene: ${scene.notes}",
            )
            // What Layer 1 holds and Layer 2 deliberately does not: the property bags.
            val file = JtFile.parse(bytes)
            val bags =
                file.metaDataSegments().mapNotNull { file.decodeMetaData(it)?.document }
                    .flatMap { it.propertyProxies }
            assertTrue(bags.isNotEmpty(), "${fixture.name}: expected property bags at Layer 1")
            // The units the scene *does* interpret come from the LSG property table, not from
            // the late-loaded bags — even though the bags repeat them.
            val unitKeys = bags.flatMap { it.properties }.filter { it.key.startsWith("JT_PROP_MEASUREMENT_UNITS") }
            if (unitKeys.isNotEmpty()) {
                assertTrue(
                    unitKeys.all { it.value is MetaPropertyValue.Text },
                    "${fixture.name}: a units property in a bag is a string value",
                )
            }
        }
    }
}
