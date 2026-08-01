package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.io.Bytes
import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.BBoxF32
import de.haumacher.kotlinjt.lsg.BaseAttributeData
import de.haumacher.kotlinjt.lsg.BaseNodeData
import de.haumacher.kotlinjt.lsg.BasePropertyAtomData
import de.haumacher.kotlinjt.lsg.BaseShapeData
import de.haumacher.kotlinjt.lsg.CountRange
import de.haumacher.kotlinjt.lsg.ElementPropertyTable
import de.haumacher.kotlinjt.lsg.GeometricTransformAttributeElement
import de.haumacher.kotlinjt.lsg.GroupNodeData
import de.haumacher.kotlinjt.lsg.GroupNodeElement
import de.haumacher.kotlinjt.lsg.InstanceNodeElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.LodNodeData
import de.haumacher.kotlinjt.lsg.LsgDocument
import de.haumacher.kotlinjt.lsg.LsgElement
import de.haumacher.kotlinjt.lsg.LsgGeneration
import de.haumacher.kotlinjt.lsg.MaterialAttributeElement
import de.haumacher.kotlinjt.lsg.MetaDataNodeData
import de.haumacher.kotlinjt.lsg.MetaDataNodeElement
import de.haumacher.kotlinjt.lsg.Mx4F64
import de.haumacher.kotlinjt.lsg.PartNodeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.PropertyAtomElement
import de.haumacher.kotlinjt.lsg.PropertyEntry
import de.haumacher.kotlinjt.lsg.PropertyTable
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.Rgba
import de.haumacher.kotlinjt.lsg.TriStripSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.Vec3F32
import de.haumacher.kotlinjt.lsg.VertexShapeData
import de.haumacher.kotlinjt.shape.PolylineGeometry
import de.haumacher.kotlinjt.shape.TriStripGeometry

/**
 * A hand-built LSG document for scene extraction tests: JT 10 generation data collections
 * composed directly from the Layer 1 model, plus the property atoms and the property table
 * that carry names, units and late-loaded shape references.
 */
class SceneLsgBuilder {
    private val graphElements = mutableListOf<LsgElement>()
    private val atoms = mutableListOf<PropertyAtomElement>()
    private val tables = mutableListOf<ElementPropertyTable>()
    private var nextAtomId = 1000

    val testBox = BBoxF32(Vec3F32(-10f, -10f, -10f), Vec3F32(10f, 10f, 10f))

    fun baseNode(attributeIds: List<Int> = emptyList()) = BaseNodeData(1, 0u, attributeIds)

    private fun group(
        children: List<Int>,
        attributeIds: List<Int> = emptyList(),
    ) = GroupNodeData(baseNode(attributeIds), 1, children)

    fun partition(
        objectId: Int,
        children: List<Int>,
        attributeIds: List<Int> = emptyList(),
    ): PartitionNodeElement =
        PartitionNodeElement(
            objectId, group(children, attributeIds), null, 0, "", testBox,
            0f, CountRange(0, 0), CountRange(0, 0), CountRange(0, 0), null,
        ).also { graphElements.add(it) }

    fun metaDataNode(
        objectId: Int,
        children: List<Int>,
        attributeIds: List<Int> = emptyList(),
    ): MetaDataNodeElement =
        MetaDataNodeElement(objectId, MetaDataNodeData(group(children, attributeIds), 1))
            .also { graphElements.add(it) }

    fun groupNode(
        objectId: Int,
        children: List<Int>,
        attributeIds: List<Int> = emptyList(),
    ): GroupNodeElement = GroupNodeElement(objectId, group(children, attributeIds)).also { graphElements.add(it) }

    fun instance(
        objectId: Int,
        child: Int,
        attributeIds: List<Int> = emptyList(),
    ): InstanceNodeElement = InstanceNodeElement(objectId, baseNode(attributeIds), 1, child).also { graphElements.add(it) }

    fun part(
        objectId: Int,
        children: List<Int>,
        attributeIds: List<Int> = emptyList(),
    ): PartNodeElement =
        PartNodeElement(objectId, MetaDataNodeData(group(children, attributeIds), 1), 1, 0)
            .also { graphElements.add(it) }

    fun rangeLod(
        objectId: Int,
        tiers: List<Int>,
        attributeIds: List<Int> = emptyList(),
    ): RangeLodNodeElement =
        RangeLodNodeElement(
            objectId,
            LodNodeData(group(tiers, attributeIds), 1),
            1,
            emptyList(),
            Vec3F32(0f, 0f, 0f),
        ).also { graphElements.add(it) }

    fun triStripShape(
        objectId: Int,
        attributeIds: List<Int> = emptyList(),
    ): TriStripSetShapeNodeElement =
        TriStripSetShapeNodeElement(
            objectId,
            VertexShapeData(
                BaseShapeData(
                    baseNode(attributeIds), 1, null, testBox, 0f,
                    CountRange(0, 0), CountRange(1, 1), CountRange(0, 0), 0u, 0f,
                ),
                1,
                0x3u,
                null,
                null,
            ),
        ).also { graphElements.add(it) }

    fun material(
        objectId: Int,
        diffuse: Rgba,
        shininess: Float = 30f,
        stateFlags: Int = 8,
        fieldInhibitFlags: UInt = 0u,
        fieldFinalFlags: UInt = 0u,
    ): MaterialAttributeElement =
        MaterialAttributeElement(
            objectId,
            BaseAttributeData(1, stateFlags, fieldInhibitFlags, fieldFinalFlags),
            1, 0,
            Rgba(0f, 0f, 0f, 1f), diffuse, Rgba(0f, 0f, 0f, 1f), Rgba(0f, 0f, 0f, 1f),
            shininess, 0f, 1f,
        ).also { graphElements.add(it) }

    fun transform(
        objectId: Int,
        values: List<Double>,
        stateFlags: Int = 8,
    ): GeometricTransformAttributeElement =
        GeometricTransformAttributeElement(
            objectId,
            BaseAttributeData(1, stateFlags, 0u, 0u),
            1,
            0xFFFF,
            Mx4F64(values),
        ).also { graphElements.add(it) }

    /** Attaches a string property to an element (key/value atoms are created on the fly). */
    fun property(
        elementObjectId: Int,
        key: String,
        value: String,
    ) {
        val keyAtom = stringAtom(key)
        val valueAtom = stringAtom(value)
        addEntry(elementObjectId, PropertyEntry(keyAtom, valueAtom))
    }

    /** Attaches a late-loaded shape segment reference to a shape node. */
    fun shapeSegment(
        elementObjectId: Int,
        segmentId: Guid,
        segmentType: Int = 7,
    ) {
        val keyAtom = stringAtom("JT_LLPROP_SHAPEIMPL")
        val valueAtom = nextAtomId++
        atoms.add(
            LateLoadedPropertyAtomElement(valueAtom, BasePropertyAtomData(1, 0u), 1, segmentId, segmentType, 0, 1),
        )
        addEntry(elementObjectId, PropertyEntry(keyAtom, valueAtom))
    }

    private fun stringAtom(value: String): Int {
        val id = nextAtomId++
        atoms.add(de.haumacher.kotlinjt.lsg.StringPropertyAtomElement(id, BasePropertyAtomData(1, 0u), 1, value))
        return id
    }

    private fun addEntry(
        elementObjectId: Int,
        entry: PropertyEntry,
    ) {
        val index = tables.indexOfFirst { it.elementObjectId == elementObjectId }
        if (index >= 0) {
            tables[index] = tables[index].copy(entries = tables[index].entries + entry)
        } else {
            tables.add(ElementPropertyTable(elementObjectId, listOf(entry)))
        }
    }

    fun build(): LsgDocument =
        LsgDocument(
            LsgGeneration.V10,
            graphElements.toList(),
            true,
            atoms.toList(),
            true,
            PropertyTable(1, tables.toList()),
            Bytes.EMPTY,
        )
}

/** A minimal one-triangle geometry whose vertices sit inside the builder's test box. */
fun testTriangleGeometry(offset: Float = 0f): TriStripGeometry =
    TriStripGeometry(
        vertices =
            listOf(
                Vec3F32(offset, 0f, 0f),
                Vec3F32(offset + 1f, 0f, 0f),
                Vec3F32(offset, 1f, 0f),
            ),
        normals = listOf(Vec3F32(0f, 0f, 1f)),
        triangles = listOf(TriStripGeometry.Triangle(0, 1, 2, 0, 0, 0, 0)),
    )

/** A minimal two-segment polyline geometry. */
fun testPolylineGeometry(): PolylineGeometry =
    PolylineGeometry(
        vertices = listOf(Vec3F32(0f, 0f, 0f), Vec3F32(1f, 0f, 0f), Vec3F32(1f, 1f, 0f)),
        polylines = listOf(PolylineGeometry.Polyline(listOf(0, 1, 2), 0)),
    )
