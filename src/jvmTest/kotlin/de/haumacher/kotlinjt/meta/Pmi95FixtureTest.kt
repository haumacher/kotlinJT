package de.haumacher.kotlinjt.meta

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.lsg.LsgGeneration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The real-file acceptance for the **JT 9.5 PMI element family** — *the hook that fires the day
 * the corpus grows a fixture carrying one*. Today no fixture in `fixtures/` or `fixtures-local/`
 * has a JT 9 Meta Data or PMI Data segment with a PMI Manager (the 9.5 files carry no §7.2.6
 * segment at all), so every battery below skips *visibly*; the whole 9.5 PMI decode is
 * spec-derived from Figures 136–170 and this file is what turns the first real one into
 * acceptance rather than a manual look.
 *
 * It asserts what a spec-derived decoder most needs a real producer to confirm:
 *
 * - the managers decode typed and re-encode byte-identically, and the only notes they may raise
 *   are the three the design names — the CAD-tag refusal, the Figure-170 texture-binding
 *   conflict and Figure 145's off-document empty vector;
 * - the version pair is inside its documented sets (element 1–2, PMI 3–8) and **the guards that
 *   those versions imply actually match what was decoded** — the single strongest check
 *   available without a second implementation, because every gated field's presence is recorded
 *   in the model rather than re-derived;
 * - §7.2.6.2.7's CAD Tag Index Count formula holds: the fifteen entity counts sum to the index
 *   count a manager that carries CAD tags declares;
 * - every String ID resolves into the PMI String Table, and every PMI string round-trips through
 *   the single-byte `String` codec (a `MbString` reading would have desynchronized long before);
 * - the polygon blocks are self-consistent: `vNumVerts` has one entry per element, each element's
 *   optional arrays are present exactly when its inline binding says so, and each font defines
 *   one PolygonData element per character identifier.
 *
 * Fixture files are never named in committed code (issue #1's fixture policy).
 */
class Pmi95FixtureTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    /** Every JT 9 §7.2.6 segment of [file] that decoded at least one 9.5 PMI Manager. */
    private fun managers(file: JtFile): List<Pair<String, Pmi95ManagerMetaDataElement>> {
        if (LsgGeneration.of(file.header.version) != LsgGeneration.V9) return emptyList()
        return file.metaDataSegments().flatMap { segment ->
            val result = file.decodeMetaData(segment) ?: return@flatMap emptyList()
            result.document.pmi95Managers.map { segment.tocEntry.segmentId.toString() to it }
        }
    }

    @TestFactory
    fun pmi95Fixtures(): List<DynamicNode> {
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — JT 9.5 PMI real-file suite SKIPPED (0 fixtures)") {
                    assumeTrue(false, "no fixtures present")
                },
            )
        }
        return fixtures.map { fixture -> battery(fixture) }
    }

    private fun battery(fixture: File): DynamicNode {
        val bytes = fixture.readBytes()
        return dynamicContainer(
            fixture.name,
            listOf(
                // spec: 9.5 Figure 133, 9.5 Figure 136
                dynamicTest("JT 9 PMI managers decode typed and round-trip byte-identically") {
                    val file = JtFile.parse(bytes)
                    assumeTrue(
                        LsgGeneration.of(file.header.version) == LsgGeneration.V9,
                        "not a JT 9 file",
                    )
                    val segments = file.metaDataSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable JT 9 §7.2.6 segments in this fixture")
                    var found = 0
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val result = file.decodeMetaData(segment)!!
                        found += result.document.pmi95Managers.size
                        assumeTrue(
                            result.document.pmi95Managers.isNotEmpty() ||
                                result.document.elements.none { it is OpaqueMetaDataElement },
                            "$id: carries no PMI Manager",
                        )
                        assertTrue(
                            result.notes.map { it.name }.all {
                                it in
                                    setOf(
                                        "CAD_TAG_VECTORS_UNRECOGNIZED",
                                        "PMI_POLYGON_TEXTURE_BINDING_UNSETTLED",
                                        "PMI_TEXT_POLYLINE_VECTOR_OFF_DOCUMENT",
                                    )
                            },
                        ) { "$id: unexpected notes ${result.notes.map { n -> n.message }}" }
                        assertArrayEquals(
                            segment.elementData!!.toByteArray(),
                            result.document.encode(file.header.byteOrder).toByteArray(),
                            "$id: element-stream round-trip is not byte-identical",
                        )
                    }
                    assumeTrue(found > 0, "no JT 9.5 PMI Manager in this fixture")
                    println("PROBE pmi95: ${fixture.name} — $found JT 9.5 PMI Manager(s)")
                },
                // spec: 9.5 Figure 136 (the version pair and every guard it drives)
                dynamicTest("the version pair is documented and every gated field agrees with it") {
                    val found = managers(JtFile.parse(bytes))
                    assumeTrue(found.isNotEmpty(), "no JT 9.5 PMI Manager in this fixture")
                    for ((id, pmi) in found) {
                        assertTrue(pmi.version in 1..2) { "$id: element Version Number ${pmi.version}" }
                        assertTrue(pmi.pmiVersion in 3..8) { "$id: PMI Version Number ${pmi.pmiVersion}" }
                        assertEquals(pmi.version > 1, pmi.tail != null, "$id: Figure 136's Version Number > 1 block")
                        assertEquals(pmi.pmiVersion > 5, pmi.modelViews != null, "$id: Model Views gate")
                        assertEquals(pmi.pmiVersion > 5, pmi.genericEntities != null, "$id: Generic PMI Entities gate")
                        assertEquals(pmi.pmiVersion > 7, pmi.cadTagsFlag != null, "$id: CAD Tags Flag gate")
                        for (association in pmi.associations) {
                            assertEquals(
                                pmi.pmiVersion > 5,
                                association.sourceOwningEntityStringId != null,
                                "$id: Figure 162's owner gate",
                            )
                        }
                        for (entity in pmi.genericEntities.orEmpty()) {
                            assertEquals(pmi.pmiVersion > 6, entity.userFlags != null, "$id: Figure 166's User Flags gate")
                            for (property in entity.properties) {
                                assertEquals(
                                    pmi.pmiVersion > 6,
                                    property.key.hiddenFlag != null,
                                    "$id: Figure 168's Hidden Flag gate",
                                )
                            }
                        }
                        for (data in all2dData(pmi)) {
                            assertEquals(pmi.pmiVersion > 4, data.base.symbolValidFlag != null, "$id: Figure 140's gate")
                            assertEquals(pmi.pmiVersion > 4, data.nonTextPolylines.types != null, "$id: Figure 147's gate")
                        }
                        for (weld in pmi.entities.spotWelds) {
                            assertEquals(pmi.pmiVersion >= 4, weld.geometry != null, "$id: Figure 153's gate")
                        }
                        for (point in pmi.entities.measurementPoints) {
                            assertEquals(pmi.pmiVersion >= 4, point.geometry != null, "$id: Figure 156's gate")
                        }
                        for (group in pmi.entities.designGroups) {
                            assertEquals(pmi.pmiVersion >= 3, group.attributes != null, "$id: Figure 159's gate")
                        }
                    }
                },
                // spec: 9.5 Figure 169 (§7.2.6.2.7's fifteen-count formula)
                dynamicTest("the CAD Tag Index Count is the sum of all fifteen entity counts") {
                    val found = managers(JtFile.parse(bytes))
                    val withTags = found.filter { it.second.cadTagData != null }
                    assumeTrue(withTags.isNotEmpty(), "no JT 9.5 PMI Manager carries CAD tags")
                    for ((id, pmi) in withTags) {
                        assertEquals(pmi.cadTagIndexCount, pmi.cadTagData!!.indices.size, "$id: §7.2.6.2.7 formula")
                    }
                },
                // spec: 9.5 Figure 164
                dynamicTest("every String ID resolves and every PMI string is single-byte") {
                    val found = managers(JtFile.parse(bytes))
                    assumeTrue(found.isNotEmpty(), "no JT 9.5 PMI Manager in this fixture")
                    for ((id, pmi) in found) {
                        for (s in pmi.stringTable) {
                            assertTrue(s.all { it.code <= 0xFF }) { "$id: '$s' is not representable as a 9.5 String" }
                        }
                        for (view in pmi.modelViews.orEmpty()) {
                            assertTrue(view.viewNameStringId == -1 || pmi.string(view.viewNameStringId) != null) {
                                "$id: Model View name String ID ${view.viewNameStringId} does not resolve"
                            }
                        }
                        for (attribute in pmi.userAttributes) {
                            assertTrue(attribute.keyStringId == -1 || pmi.string(attribute.keyStringId) != null) {
                                "$id: user attribute key String ID does not resolve"
                            }
                        }
                    }
                },
                // spec: 9.5 Figure 170
                dynamicTest("polygon blocks and font glyph counts are self-consistent") {
                    val found = managers(JtFile.parse(bytes))
                    val tails = found.mapNotNull { (id, pmi) -> pmi.tail?.let { id to it } }
                    assumeTrue(tails.isNotEmpty(), "no JT 9.5 PMI Manager carries Figure 136's tail")
                    for ((id, tail) in tails) {
                        for (data in listOf(tail.polygonData) + tail.fonts.map { it.glyphs }) {
                            assertEquals(
                                data.vertexCounts.count { it > 0 },
                                data.elements.size,
                                "$id: one PolygonData element per non-zero vNumVerts entry",
                            )
                            for (element in data.elements) {
                                assertEquals(element.normalBinding == 1, element.normals != null, "$id: NormalBinding")
                                assertEquals(element.colourBinding == 1, element.colours != null, "$id: ColorBinding")
                                assertEquals(
                                    element.textureBinding == 1,
                                    element.textureCoords != null,
                                    "$id: TextureBinding",
                                )
                                assertEquals(
                                    element.vertexCount * element.polygonDimension,
                                    element.vertices.size,
                                    "$id: Vertices is vNumVerts × PolygonDimension",
                                )
                            }
                        }
                        for (font in tail.fonts) {
                            assertEquals(
                                font.characterSet.size,
                                font.glyphs.vertexCounts.size,
                                "$id: font '${font.name}' defines one glyph per character identifier",
                            )
                        }
                    }
                },
            ),
        )
    }

    /** Every PMI 2D Data record a manager holds, across all thirteen collections and generics. */
    private fun all2dData(pmi: Pmi95ManagerMetaDataElement): List<Pmi952dData> {
        val e = pmi.entities
        return e.dimensions + e.notes.map { it.data2d } + e.datumFeatureSymbols + e.datumTargets +
            e.featureControlFrames + e.lineWelds + e.surfaceFinishes + e.locators +
            pmi.genericEntities.orEmpty().map { it.data2d }
    }
}
