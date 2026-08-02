package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.lsg.DatePropertyAtomElement
import de.haumacher.kotlinjt.lsg.FloatingPointPropertyAtomElement
import de.haumacher.kotlinjt.lsg.IntegerPropertyAtomElement
import de.haumacher.kotlinjt.lsg.JtObjectReferencePropertyAtomElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.Vector4fPropertyAtomElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer's local version numbers against v10 §13 *Version numbers* (issue #15).
 *
 * §13 gives `0x01` as the default — "all references to version number in this document shall be
 * this value unless noted otherwise here" — then lists the exceptions. The property atoms are
 * the exception this writer got wrong: all eight atom types are `0x02`, and the 10.5 producer
 * writes 2.
 *
 * Nothing else pinned this. `golden-candidates/` is gitignored (goldens are frozen only after
 * external validation), so no committed artefact holds the authored bytes — and a constant with
 * no test is a constant that drifts.
 */
class WriterLocalVersionTest {
    private fun assemblyScene(): Scene =
        millimeterScene(
            SceneNode(
                "assembly",
                de.haumacher.kotlinjt.scene.Mat4.IDENTITY,
                emptyList(),
                emptyList(),
                null,
                listOf(
                    part("left.part", unitCubeMesh()),
                    part("right.part", unitCubeMesh(Vec3(2f, 0f, 0f))),
                ),
            ),
        )

    /** Every authored property atom, as (type name, its own local version). */
    private fun atomVersions(scene: Scene): List<Pair<String, Int>> =
        writeJtFile(scene).decodeLsg()!!.document.propertyAtoms.mapNotNull { atom ->
            when (atom) {
                is StringPropertyAtomElement -> "String" to atom.version
                is IntegerPropertyAtomElement -> "Integer" to atom.version
                is FloatingPointPropertyAtomElement -> "FloatingPoint" to atom.version
                is JtObjectReferencePropertyAtomElement -> "JtObjectReference" to atom.version
                is DatePropertyAtomElement -> "Date" to atom.version
                is LateLoadedPropertyAtomElement -> "LateLoaded" to atom.version
                is Vector4fPropertyAtomElement -> "Vector4f" to atom.version
                else -> null
            }
        }

    // spec: §13 (Version numbers — the "0x02" list)
    @Test
    fun everyAuthoredPropertyAtomDeclaresLocalVersionTwo() {
        val versions = atomVersions(assemblyScene())
        assertTrue(versions.isNotEmpty(), "the authored file carries no property atoms to check")
        assertEquals(
            emptyList(),
            versions.filter { it.second != 2 },
            "v10 §13 lists all eight property atom types under \"0x02\"; these were authored otherwise",
        )
    }

    // spec: §13 — Base Property Atom Data travels with the atom that owns it
    @Test
    fun theBasePropertyAtomDataVersionMatchesTheAtomVersion() {
        val document = writeJtFile(assemblyScene()).decodeLsg()!!.document
        val bases = document.propertyAtoms.mapNotNull { (it as? StringPropertyAtomElement)?.baseAtom?.version }
        assertTrue(bases.isNotEmpty(), "no string atoms were authored")
        assertEquals(
            emptyList(),
            bases.filter { it != 2 },
            "Base Property Atom Data must carry the same local version as its atom",
        )
    }

    // The change must not disturb what §13 leaves at the default.
    // spec: §13 (Version numbers — the "0x01" default)
    @Test
    fun nodesAndAttributesStayAtTheDefaultVersion() {
        val document = writeJtFile(assemblyScene()).decodeLsg()!!.document
        val partition = document.graphElements.filterIsInstance<PartitionNodeElement>().single()
        assertEquals(1, partition.group.base.version, "Base Node Data is not among §13's exceptions")
        assertEquals(1, partition.group.version, "Group Node Data is not among §13's exceptions")
    }
}
