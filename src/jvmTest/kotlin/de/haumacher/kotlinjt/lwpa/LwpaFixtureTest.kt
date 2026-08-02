package de.haumacher.kotlinjt.lwpa

import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.TypedLsgElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The real-file acceptance for the JT LWPA segment — **the hook that fires the day the corpus
 * grows a fixture carrying one**. Today no fixture in `fixtures/` or `fixtures-local/` has an
 * LWPA segment in either generation, so every battery below skips *visibly*; the whole LWPA
 * decode is spec-derived (v10 §9 Figures 99–101, JT 9.5 §7.2.9 Figures 214–216) and this file is
 * what turns the first real LWPA file into acceptance rather than a manual look.
 *
 * It asserts what a spec-derived decoder most needs a real producer to confirm:
 *
 * - the segments decode typed with zero notes and re-encode byte-identically, one JT LWPA Element
 *   per segment, closed by the Figure-78 property table;
 * - the two `VecI32` vectors have exactly `Analytic Surface Count` entries, every surface index
 *   addresses a surface below `Surface Count`, and every type code is in the *Supported Surface
 *   Type* table;
 * - **the four `VecF64` arrays balance against the Analytic Surface Creation chart** (9.5
 *   Figure 217 == v10 Figure 102): every surface consumes one point (3 coordinates) and two unit
 *   vectors (6 axis values), a cylinder or sphere one radius, a cone one radius and one radian,
 *   a torus two radii. This identity is deliberately **not** enforced by the decoder — it is a
 *   derivation from a flow chart no fixture has ever exercised, and turning it into a refusal
 *   would reject files on the strength of a reading rather than of evidence. Here it can only
 *   fail loudly and teach us something;
 * - any late-loaded atom pointing at an LWPA segment declares segment type 24. The *key string*
 *   is not asserted: the `JT_LLPROP_*` names are producer conventions read off fixtures, and
 *   there is no fixture to read this one off.
 *
 * Fixture files are never named in committed code (issue #1's fixture policy).
 */
class LwpaFixtureTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, ".git").exists()) return dir
            dir = dir.parentFile ?: error("repository root not found above ${System.getProperty("user.dir")}")
        }
    }

    /** Every late-loaded reference of the LSG: segment GUID -> (key string, declared type). */
    private fun lateLoadedReferences(document: LsgDocument): Map<Guid, Pair<String, Int>> {
        val table = document.propertyTable ?: return emptyMap()
        val atomsById = document.propertyAtoms.filterIsInstance<TypedLsgElement>().associateBy { it.objectId }
        val result = mutableMapOf<Guid, Pair<String, Int>>()
        for (elementTable in table.tables) {
            for (entry in elementTable.entries) {
                val value = atomsById[entry.valuePropertyAtomObjectId]
                if (value !is LateLoadedPropertyAtomElement) continue
                val key = (atomsById[entry.keyPropertyAtomObjectId] as? StringPropertyAtomElement)?.value ?: continue
                result[value.segmentId] = key to value.segmentType
            }
        }
        return result
    }

    @TestFactory
    fun lwpaFixtures(): List<DynamicNode> {
        val directories = listOf(File(repoRoot(), "fixtures"), File(repoRoot(), "fixtures-local"))
        val fixtures =
            directories.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jt") }?.sortedBy { it.name }.orEmpty()
            }
        if (fixtures.isEmpty()) {
            return listOf(
                dynamicTest("no *.jt fixtures — LWPA real-file suite SKIPPED (0 fixtures)") {
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
                // spec: Figure 99, Figure 100 / 9.5 Figure 214, 9.5 Figure 215
                dynamicTest("LWPA segments decode typed with zero notes and round-trip byte-identically") {
                    val file = JtFile.parse(bytes)
                    val segments = file.lwpaSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable LWPA segments in this fixture")
                    var elements = 0
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val result = file.decodeLwpa(segment)!!
                        assertEquals(emptyList<String>(), result.notes.map { it.name }, "$id: unexpected notes")
                        assertTrue(
                            result.document.elements.all { it is JtLwpaElement },
                            "$id: an LWPA element was carried opaquely",
                        )
                        elements += result.document.analyticReps.size
                        assertNotNull(result.document.propertyTable, "$id: no trailing Property Table")
                        assertEquals(0, result.document.trailing.size, "$id: bytes left over")
                        assertArrayEquals(
                            segment.elementData!!.toByteArray(),
                            result.document.encode(file.header.byteOrder).toByteArray(),
                            "$id: element-stream round-trip is not byte-identical",
                        )
                    }
                    assertEquals(segments.size, elements, "each LWPA segment holds exactly one JT LWPA Element")
                },
                // spec: Figure 101 / 9.5 Figure 216
                dynamicTest("surface indices and types are coherent with the declared counts") {
                    val file = JtFile.parse(bytes)
                    val segments = file.lwpaSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable LWPA segments in this fixture")
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        for (element in file.decodeLwpa(segment)!!.document.analyticReps) {
                            val geometry = element.geometry ?: continue
                            val analytic = element.analyticSurfaceCount.toInt()
                            assertEquals(analytic, geometry.surfaceIndices.size, "$id: index count")
                            assertEquals(analytic, geometry.surfaceTypes.size, "$id: type count")
                            for (index in geometry.surfaceIndices.values) {
                                assertTrue(
                                    index >= 0 && index.toUInt() < element.surfaceCount,
                                    "$id: analytic surface index $index is outside [0, ${element.surfaceCount})",
                                )
                            }
                            assertTrue(geometry.types.none { it == null }, "$id: a surface type is outside the table")
                        }
                    }
                },
                // spec: 9.5 Figure 217 (== v10 Figure 102)
                dynamicTest("the four VecF64 arrays balance against the Analytic Surface Creation chart") {
                    val file = JtFile.parse(bytes)
                    val segments = file.lwpaSegments().filter { it.elementData != null }
                    assumeTrue(segments.isNotEmpty(), "no decodable LWPA segments in this fixture")
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        for (element in file.decodeLwpa(segment)!!.document.analyticReps) {
                            val geometry = element.geometry ?: continue
                            val types = geometry.types
                            val surfaces = types.size
                            val radii =
                                types.sumOf { type ->
                                    when (type) {
                                        AnalyticSurfaceType.CYLINDER, AnalyticSurfaceType.CONE,
                                        AnalyticSurfaceType.SPHERE,
                                        -> 1L
                                        AnalyticSurfaceType.TORUS -> 2L
                                        else -> 0L
                                    }
                                }.toInt()
                            val radians = types.count { it == AnalyticSurfaceType.CONE }
                            assertEquals(3 * surfaces, geometry.coordinates.size, "$id: Coordinate Array (one point each)")
                            assertEquals(6 * surfaces, geometry.axes.size, "$id: Axis Array (axis + x_axis each)")
                            assertEquals(radii, geometry.radii.size, "$id: Radius Array")
                            assertEquals(radians, geometry.radians.size, "$id: Radian Array (cone semi-angles)")
                        }
                    }
                },
                dynamicTest("every referenced LWPA segment is declared as segment type 24") {
                    val file = JtFile.parse(bytes)
                    val segments = file.lwpaSegments()
                    assumeTrue(segments.isNotEmpty(), "no LWPA segments in this fixture")
                    val lsg = file.decodeLsg()?.document
                    assumeTrue(lsg != null, "no decodable LSG segment to resolve references against")
                    val references = lateLoadedReferences(lsg!!)
                    for (segment in segments) {
                        val id = segment.tocEntry.segmentId
                        val reference = references[id] ?: continue
                        assertEquals(24, reference.second, "$id: referenced by '${reference.first}' with the wrong type")
                    }
                },
            ),
        )
    }
}
