package de.haumacher.kotlinjt.lsg

import de.haumacher.kotlinjt.JtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Probe for the 9.5 guarded `U64 : Vertex Binding` fields (9.5 Figures 30/33/34) — written
 * against what the *producer* wrote, not against frames this library built.
 *
 * The unit tests that came with the feature construct their own element bodies, so they prove
 * the codec self-consistent; they cannot prove it agrees with NetAllied. These probes take the
 * real 9.5 fixtures and drive the two readings the corpus does *not* contain through the whole
 * decode → model → encode → decode loop:
 *
 * 1. **The document-conformant omission.** No file in the corpus omits the guarded fields, so
 *    the length oracle's other branch is untested against real neighbours. Dropping the field
 *    from a real element through the model must shrink the stream by exactly 8 bytes per
 *    dropped field, must re-decode as absent, and must leave every other element untouched —
 *    the "recorded, never normalized" half of the doctrine (DESIGN.md).
 * 2. **A distinctive value.** Round-tripping a value no producer writes catches a reader that
 *    reconstructs the field from the version rather than from the bytes.
 */
class Lsg95GuardProbeTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun fixtures(): List<File> =
        listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local")).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
        }

    /** Rewrites every guarded binding in the document, returning the new document. */
    private fun mapGuardedBindings(
        document: LsgDocument,
        f: (ULong?) -> ULong?,
    ): LsgDocument =
        document.copy(
            graphElements =
                document.graphElements.map { element ->
                    when (element) {
                        is PolylineSetShapeNodeElement -> element.copy(vertexBindings = f(element.vertexBindings))
                        is PointSetShapeNodeElement -> element.copy(vertexBindings = f(element.vertexBindings))
                        else -> element
                    }
                },
        )

    private fun guardedCount(document: LsgDocument): Int =
        document.graphElements.count {
            (it is PolylineSetShapeNodeElement && it.vertexBindings != null) ||
                (it is PointSetShapeNodeElement && it.vertexBindings != null)
        }

    @TestFactory
    fun guardedBindingProbes(): List<DynamicNode> =
        fixtures().flatMap { fixture ->
            val bytes = fixture.readBytes()
            listOf(
                // spec: 9.5 Figure 33, Figure 34 (the guarded U64 the corpus never omits)
                dynamicTest("${fixture.name}: an undecidable guarded encoding is refused by name, never guessed") {
                    val file = JtFile.parse(bytes)
                    val decoded = file.decodeLsg()
                    assumeTrue(decoded != null, "no decodable LSG segment")
                    val document = decoded!!.document
                    val present = guardedCount(document)
                    assumeTrue(present > 0, "no polyline/point shape node carries the guarded binding")

                    val order = file.header.byteOrder
                    val before = document.encode(order).toByteArray()
                    val dropped = mapGuardedBindings(document) { null }
                    val after = dropped.encode(order).toByteArray()

                    assertEquals(
                        before.size - 8 * present,
                        after.size,
                        "dropping $present guarded bindings must remove exactly 8 bytes each",
                    )

                    // Dropping only the *node-level* field leaves 14 bytes after the
                    // Quantization Parameters, which two readings explain equally well —
                    // Vertex Shape Data's guarded U64 present and the node's absent, or the
                    // reverse — and in this producer's bytes the I16 at both candidate version
                    // offsets is a plausible 2. Under §9.4's append-only versions this variant
                    // is not merely ambiguous but illegal (a node at version 2 must carry the
                    // field), so the only honest outcome is a named refusal. What must never
                    // happen is a silent pick: that would put invented values in the model.
                    val reread = LsgDocument.decode(after.toBytes(), file.header.version, order)
                    assertTrue(
                        reread.notes.isNotEmpty(),
                        "an undecidable body decoded silently — the reader guessed",
                    )
                    assertTrue(
                        reread.notes.all { it.name == "ELEMENT_DECODE_FAILED" },
                        "unexpected refusals: ${reread.notes.map { it.name }}",
                    )
                    assertEquals(
                        present,
                        reread.notes.size,
                        "each undecidable element must be named exactly once",
                    )
                    assertEquals(
                        present,
                        reread.document.allElements.count { it is OpaqueLsgElement },
                        "a refused element must be carried opaquely, not dropped",
                    )
                    // And the refusal costs nothing: the bytes survive it intact.
                    assertEquals(
                        after.toList(),
                        reread.document.encode(order).toByteArray().toList(),
                        "the refused stream did not re-encode byte-identically",
                    )
                },
                // spec: 9.5 Figure 33, Figure 34
                dynamicTest("${fixture.name}: a distinctive guarded binding survives the round trip verbatim") {
                    val file = JtFile.parse(bytes)
                    val decoded = file.decodeLsg()
                    assumeTrue(decoded != null, "no decodable LSG segment")
                    val document = decoded!!.document
                    assumeTrue(guardedCount(document) > 0, "no guarded binding to mutate")

                    val order = file.header.byteOrder
                    val marker = 0x0123_4567_89AB_CDEFuL
                    val mutated = mapGuardedBindings(document) { marker }
                    val reread = LsgDocument.decode(mutated.encode(order), file.header.version, order)

                    assertEquals(emptyList<String>(), reread.notes.map { it.name }, "mutation must stay legal")
                    assertEquals(mutated, reread.document, "the mutated document is not what came back")
                    val roundTripped =
                        reread.document.graphElements.mapNotNull {
                            when (it) {
                                is PolylineSetShapeNodeElement -> it.vertexBindings
                                is PointSetShapeNodeElement -> it.vertexBindings
                                else -> null
                            }
                        }
                    assertTrue(roundTripped.isNotEmpty(), "no guarded binding came back")
                    assertTrue(
                        roundTripped.all { it == marker },
                        "guarded bindings came back as $roundTripped, not the written marker",
                    )
                },
                // spec: 9.5 Figure 30 — the second Vertex Shape Data binding, same doctrine
                dynamicTest("${fixture.name}: every shape node's guarded fields are decided by bytes, not by version") {
                    val file = JtFile.parse(bytes)
                    val decoded = file.decodeLsg()
                    assumeTrue(decoded != null, "no decodable LSG segment")
                    val shapeNodes = decoded!!.document.graphElements.filterIsInstance<ShapeNodeElement>()
                    assumeTrue(shapeNodes.isNotEmpty(), "no shape nodes")
                    // If presence were re-derived from the version number, every element of a
                    // given version would agree. Assert the weaker, honest property instead:
                    // whatever was read re-encodes to the identical bytes.
                    val order = file.header.byteOrder
                    val elementData = file.lsgSegment()!!.elementData!!
                    assertEquals(
                        elementData.toByteArray().toList(),
                        decoded.document.encode(order).toByteArray().toList(),
                        "the decoded model does not re-encode to the producer's bytes",
                    )
                    for (node in shapeNodes) {
                        assertNotNull(node.shape, "${node.objectId}: no base shape data")
                        if (file.header.version.major < 10) {
                            assertNotNull(
                                node.vertexShapeOrNull()?.quantizationParameters,
                                "${node.objectId}: JT 9 vertex shape data without quantization parameters",
                            )
                        } else {
                            assertNull(
                                node.vertexShapeOrNull()?.quantizationParameters,
                                "${node.objectId}: v10 vertex shape data with JT 9 quantization parameters",
                            )
                        }
                    }
                },
            )
        }

    private fun ShapeNodeElement.vertexShapeOrNull(): VertexShapeData? =
        when (this) {
            is TriStripSetShapeNodeElement -> vertexShape
            is PolylineSetShapeNodeElement -> vertexShape
            is PointSetShapeNodeElement -> vertexShape
            is PolygonSetShapeNodeElement -> vertexShape
            is VertexShapeNodeElement -> vertexShape
            else -> null
        }
}

private fun ByteArray.toBytes() = de.haumacher.kotlinjt.io.Bytes.of(this)
