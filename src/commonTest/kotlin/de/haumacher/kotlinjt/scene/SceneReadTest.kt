package de.haumacher.kotlinjt.scene

import de.haumacher.kotlinjt.io.Guid
import de.haumacher.kotlinjt.lsg.Rgba
import de.haumacher.kotlinjt.testGuid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Scene extraction on hand-built Layer 1 structures: the §13.9 part convention collapse,
 * name and unit conventions, attribute accumulation, LOD policies, instancing identity, and
 * the honesty notes for everything the scene cannot represent.
 */
class SceneReadTest {
    private val segmentA = testGuid(0xA)
    private val segmentB = testGuid(0xB)

    private fun geometryResolver(vararg sources: Pair<Guid, ShapeSource>): (Guid) -> ShapeSource {
        val map = sources.toMap()
        return { id -> map[id] ?: ShapeSource.Unavailable("no segment $id in the synthetic file") }
    }

    private fun decoded(geometry: de.haumacher.kotlinjt.shape.TriStripGeometry) = ShapeSource.Decoded(geometry, null, emptyList())

    /** The §13.9 Figure 160 convention: Part → Range LOD → per-tier Group → Shape. */
    private fun SceneLsgBuilder.partConvention(
        instanceId: Int,
        name: String,
        segments: List<Guid>,
        instanceAttributes: List<Int> = emptyList(),
        shapeAttributes: List<Int> = emptyList(),
        partAttributes: List<Int> = emptyList(),
    ): Int {
        val base = instanceId * 100
        val shapeIds = segments.indices.map { base + 3 + 2 * it }
        val groupIds = segments.indices.map { base + 4 + 2 * it }
        instance(instanceId, base + 1, instanceAttributes)
        property(instanceId, "JT_PROP_NAME", name)
        part(base + 1, listOf(base + 2), partAttributes)
        rangeLod(base + 2, groupIds)
        for (i in segments.indices) {
            groupNode(groupIds[i], listOf(shapeIds[i]))
            triStripShape(shapeIds[i], shapeAttributes)
            shapeSegment(shapeIds[i], segments[i], segmentType = 7 + i)
        }
        return instanceId
    }

    // spec: §13.9 (LSG Part Structure, Figure 160 — the convention collapses to named parts)
    @Test
    fun partConventionCollapsesToNamedPartsUnderTheRoot() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.property(0, "JT_PROP_NAME", "assembly.asm;0;1:")
        b.metaDataNode(1, listOf(10, 20))
        b.partConvention(10, "left.part;0;1:", listOf(segmentA))
        b.partConvention(20, "right.part;0;2:", listOf(segmentB))
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(segmentA to decoded(testTriangleGeometry()), segmentB to decoded(testTriangleGeometry(2f))),
            )

        assertEquals(emptyList(), scene.notes, "a fully decodable synthetic scene must be note-free")
        assertEquals("assembly.asm", scene.root.name)
        // The nameless metadata holder is spliced away; the two named parts sit under the root.
        assertEquals(listOf("left.part", "right.part"), scene.root.children.map { it.name })
        for (child in scene.root.children) {
            assertEquals(1, child.meshes.size, "one mesh per LOD")
            assertEquals(1, child.meshes[0].triangles.size)
            assertEquals(3, child.meshes[0].positions.size)
            assertEquals(emptyList(), child.children)
        }
    }

    // spec: §13.8 (Table 79 — JT_PROP_NAME encoded form "Name;version;instance:")
    @Test
    fun nameEncodingIsDecodedAndPlainNamesPassVerbatim() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.property(0, "JT_PROP_NAME", "NIST style name.asm;42;16777094:")
        b.metaDataNode(1, emptyList())
        b.property(1, "JT_PROP_NAME", "plain name")
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals("NIST style name.asm", scene.root.name)
        assertEquals("plain name", scene.root.children.single().name)
    }

    @Test
    fun instancedPartsShareTheirMeshObjects() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1, 2))
        // Two instances of the same part subtree, each with its own transform.
        val t1 = b.transform(50, translation(5.0, 0.0, 0.0))
        val t2 = b.transform(51, translation(0.0, 5.0, 0.0))
        b.instance(1, 100, listOf(t1.objectId))
        b.property(1, "JT_PROP_NAME", "first;0;1:")
        b.instance(2, 100, listOf(t2.objectId))
        b.property(2, "JT_PROP_NAME", "second;0;2:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102))
        b.groupNode(102, listOf(103))
        b.triStripShape(103)
        b.shapeSegment(103, segmentA)
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver(segmentA to decoded(testTriangleGeometry())))

        assertEquals(emptyList(), scene.notes)
        val (first, second) = scene.root.children
        assertEquals("first", first.name)
        assertEquals("second", second.name)
        assertEquals(translation(5.0, 0.0, 0.0), first.transform.values)
        assertEquals(translation(0.0, 5.0, 0.0), second.transform.values)
        // The shared part is converted once: the mesh is the same object, not a copy.
        assertSame(first.meshes.single(), second.meshes.single())
    }

    // spec: §13.8 (JT_PROP_MEASUREMENT_UNITS: required units convention, mixed-case accepted)
    @Test
    fun unitsAreReadCaseInsensitively() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.metaDataNode(1, emptyList())
        b.property(1, "JT_PROP_MEASUREMENT_UNITS", "Millimeters")
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals(LengthUnit.MILLIMETERS, scene.units)
        assertEquals(emptyList(), scene.notes)
    }

    @Test
    fun unitsAreUnspecifiedWhenTheFileDeclaresNone() {
        val b = SceneLsgBuilder()
        b.partition(0, emptyList())
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals(LengthUnit.UNSPECIFIED, scene.units)
        assertEquals(emptyList(), scene.notes, "declaring nothing is not an error — the units field says so explicitly")
    }

    @Test
    fun unrecognizedUnitsYieldANamedNote() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.metaDataNode(1, emptyList())
        b.property(1, "JT_PROP_MEASUREMENT_UNITS", "furlongs")
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals(LengthUnit.UNSPECIFIED, scene.units)
        assertEquals(listOf("SCENE_UNITS_UNRECOGNIZED"), scene.notes.map { it.name })
    }

    @Test
    fun conflictingUnitsYieldMixedNoteAndUnspecified() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1, 2))
        b.metaDataNode(1, emptyList())
        b.property(1, "JT_PROP_MEASUREMENT_UNITS", "millimeters")
        b.metaDataNode(2, emptyList())
        b.property(2, "JT_PROP_MEASUREMENT_UNITS", "inches")
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals(LengthUnit.UNSPECIFIED, scene.units)
        assertEquals(listOf("SCENE_UNITS_MIXED"), scene.notes.map { it.name })
    }

    @Test
    fun undecodableGeometryLeavesTheNodeAndANamedNote() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1, 2))
        b.partConvention(1, "good;0;1:", listOf(segmentA))
        b.partConvention(2, "broken;0;2:", listOf(segmentB))
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(
                    segmentA to decoded(testTriangleGeometry()),
                    segmentB to ShapeSource.Decoded(null, null, listOf("ELEMENT_DECODE_FAILED")),
                ),
            )

        // The failed part does not silently vanish: its named node stays, empty, and the
        // failure is a note that names the node and the underlying refusal.
        assertEquals(listOf("good", "broken"), scene.root.children.map { it.name })
        assertEquals(0, scene.root.children[1].meshes.size)
        assertEquals(listOf("SCENE_GEOMETRY_UNAVAILABLE"), scene.notes.map { it.name })
        assertTrue("ELEMENT_DECODE_FAILED" in scene.notes[0].message, "the note names the underlying refusal")
        assertTrue("broken" in scene.notes[0].message, "the note names the affected node")
    }

    @Test
    fun missingSegmentsYieldTheUnavailableNote() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.partConvention(1, "orphan;0;1:", listOf(segmentA))
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals(listOf("SCENE_GEOMETRY_UNAVAILABLE"), scene.notes.map { it.name })
        assertEquals(listOf("orphan"), scene.root.children.map { it.name })
    }

    @Test
    fun allLodsCarriesOneMeshPerTierFinestFirst() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.partConvention(1, "part;0;1:", listOf(segmentA, segmentB))
        val fine = testTriangleGeometry()
        val coarse =
            testTriangleGeometry(5f).let {
                it.copy(triangles = it.triangles + it.triangles[0])
            }
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(segmentA to decoded(fine), segmentB to decoded(coarse)),
            )
        val part = scene.root.children.single()
        assertEquals(2, part.meshes.size)
        assertEquals(1, part.meshes[0].triangles.size, "tier order: finest (LOD0) first")
        assertEquals(2, part.meshes[1].triangles.size)
    }

    @Test
    fun finestOnlyKeepsExactlyTheFinestDecodedTier() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.partConvention(1, "part;0;1:", listOf(segmentA, segmentB))
        val scene =
            buildScene(
                b.build(),
                LodPolicy.FINEST_ONLY,
                geometryResolver(segmentA to decoded(testTriangleGeometry()), segmentB to decoded(testTriangleGeometry(5f))),
            )
        val part = scene.root.children.single()
        assertEquals(1, part.meshes.size)
        assertEquals(0f, part.meshes[0].positions[0].x, "the finest tier's mesh is the one kept")
    }

    @Test
    fun finestOnlyFallsBackToTheFinestDecodableTierWithANote() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.partConvention(1, "part;0;1:", listOf(segmentA, segmentB))
        val scene =
            buildScene(
                b.build(),
                LodPolicy.FINEST_ONLY,
                geometryResolver(
                    segmentA to ShapeSource.Unavailable("segment lost"),
                    segmentB to decoded(testTriangleGeometry(5f)),
                ),
            )
        val part = scene.root.children.single()
        assertEquals(1, part.meshes.size)
        assertEquals(5f, part.meshes[0].positions[0].x, "the coarser tier substitutes")
        assertEquals(listOf("SCENE_GEOMETRY_UNAVAILABLE"), scene.notes.map { it.name }, "the lost finer tier is not hidden")
    }

    // spec: §13.9 (LSG Attribute Accumulation Semantics: materials accumulate by replacement)
    @Test
    fun materialReplacementTheShapeMaterialWinsOverThePartMaterial() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1, 2))
        val partMaterial = b.material(60, Rgba(1f, 0f, 0f, 1f))
        val shapeMaterial = b.material(61, Rgba(0f, 1f, 0f, 0.5f), shininess = 126f)
        b.partConvention(1, "shaped;0;1:", listOf(segmentA), shapeAttributes = listOf(61), partAttributes = listOf(60))
        b.partConvention(2, "inherited;0;2:", listOf(segmentB), partAttributes = listOf(60))
        check(partMaterial.objectId == 60 && shapeMaterial.objectId == 61)
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(segmentA to decoded(testTriangleGeometry()), segmentB to decoded(testTriangleGeometry())),
            )

        val shaped = assertNotNull(scene.root.children[0].material)
        assertEquals(Color(0f, 1f, 0f, 0.5f), shaped.baseColor, "the lower (shape) material replaces the part's")
        // The recorded Phong→PBR mapping: roughness = sqrt(2 / (2 + shininess)).
        assertTrue(abs(shaped.roughness - 0.125f) < 1e-6f)
        assertEquals(0f, shaped.metallic)

        val inherited = scene.root.children[1]
        assertEquals(Color(1f, 0f, 0f, 1f), assertNotNull(inherited.material).baseColor)
    }

    // spec: §6.1.2.1.1 (Table 15 — Accumulation Ignore flag 0x04)
    @Test
    fun ignoredAttributesDoNotAccumulate() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.material(60, Rgba(1f, 0f, 0f, 1f), stateFlags = 8 or 0x04)
        b.partConvention(1, "part;0;1:", listOf(segmentA), shapeAttributes = listOf(60))
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver(segmentA to decoded(testTriangleGeometry())))
        assertNull(scene.root.children.single().material, "an ignored material must not reach the scene")
        assertEquals(emptyList(), scene.notes, "honoring the ignore flag is conforming, not a refusal")
    }

    // spec: §6.1.2.1.1 (Table 15 — force/final flags beyond the modelled semantics are noted)
    @Test
    fun forceAndFinalFlagsYieldTheSemanticsNote() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.material(60, Rgba(1f, 0f, 0f, 1f), stateFlags = 8 or 0x02, fieldFinalFlags = 1u)
        b.partConvention(1, "part;0;1:", listOf(segmentA), shapeAttributes = listOf(60))
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver(segmentA to decoded(testTriangleGeometry())))
        assertEquals(listOf("SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED"), scene.notes.map { it.name })
    }

    @Test
    fun polylineGeometryLandsInThePolylinesList() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.partConvention(1, "wire;0;1:", listOf(segmentA))
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(segmentA to ShapeSource.Decoded(null, testPolylineGeometry(), emptyList())),
            )
        val wire = scene.root.children.single()
        assertEquals(0, wire.meshes.size)
        assertEquals(1, wire.polylines.size)
        assertEquals(listOf(listOf(0, 1, 2)), wire.polylines[0].lines)
        assertEquals(3, wire.polylines[0].positions.size)
        assertEquals(emptyList(), scene.notes, "polyline-only parts are first-class, not a refusal")
    }

    @Test
    fun transformsInsideALodTierAreBakedIntoTheGeometry() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        // Part convention, but the tier group carries a transform — it cannot become a
        // scene node (the tier is one mesh), so it must be baked into the vertices.
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102))
        val tilt = b.transform(60, translation(10.0, 0.0, 0.0))
        b.groupNode(102, listOf(103), listOf(tilt.objectId))
        b.triStripShape(103)
        b.shapeSegment(103, segmentA)
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver(segmentA to decoded(testTriangleGeometry())))

        val part = scene.root.children.single()
        assertEquals(Mat4.IDENTITY, part.transform, "the tier-internal transform is not on the node")
        assertEquals(Vec3(10f, 0f, 0f), part.meshes.single().positions[0], "it is baked into the vertices")
        assertEquals(Vec3(0f, 0f, 1f), part.meshes.single().normals[0], "translation leaves normals untouched")
    }

    @Test
    fun rotationsBakedIntoATierRotateTheNormals() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102))
        // Rotate 90° about X (row-vector convention): y → z, z → -y.
        val rotation =
            b.transform(
                60,
                listOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, -1.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
        b.groupNode(102, listOf(103), listOf(rotation.objectId))
        b.triStripShape(103)
        b.shapeSegment(103, segmentA)
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver(segmentA to decoded(testTriangleGeometry())))

        val normal = scene.root.children.single().meshes.single().normals[0]
        assertTrue(
            abs(normal.x) < 1e-6f && abs(normal.y + 1f) < 1e-6f && abs(normal.z) < 1e-6f,
            "normal (0,0,1) rotates to (0,-1,0), got $normal",
        )
    }

    // spec: §13.9 (a LOD tier is a Group Node: several shapes at one level of detail)
    @Test
    fun multipleShapesInOneTierBecomeSiblingNodesUnderThePart() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102))
        b.groupNode(102, listOf(103, 104))
        b.triStripShape(103)
        b.shapeSegment(103, segmentA)
        b.triStripShape(104)
        b.shapeSegment(104, segmentB)
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(segmentA to decoded(testTriangleGeometry()), segmentB to decoded(testTriangleGeometry(3f))),
            )
        // The two shapes are two bodies, so they are two nodes — not one merged mesh.
        val part = scene.root.children.single()
        assertEquals("part", part.name)
        assertEquals(emptyList(), part.meshes, "the part groups the bodies; it carries none itself")
        assertEquals(2, part.children.size)
        for (child in part.children) {
            val mesh = child.meshes.single()
            assertEquals(3, mesh.positions.size)
            assertEquals(1, mesh.triangles.size)
            for (t in mesh.triangles) {
                for (index in listOf(t.v0, t.v1, t.v2)) assertTrue(index in mesh.positions.indices)
                for (index in listOf(t.n0, t.n1, t.n2)) assertTrue(index in mesh.normals.indices)
            }
        }
        assertEquals(0f, part.children[0].meshes.single().positions[0].x, "tier order is the file's shape order")
        assertEquals(3f, part.children[1].meshes.single().positions[0].x)
        assertEquals(emptyList(), scene.notes, "nothing was abstracted away, so nothing is noted")
    }

    // spec: §13.9 (a shape's material is its own; the scene never has to pick between shapes)
    @Test
    fun eachShapeOfATierKeepsItsOwnMaterial() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.material(60, Rgba(1f, 0f, 0f, 1f))
        b.material(61, Rgba(0f, 0f, 1f, 1f))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102))
        b.groupNode(102, listOf(103, 104))
        b.triStripShape(103, listOf(60))
        b.shapeSegment(103, segmentA)
        b.triStripShape(104, listOf(61))
        b.shapeSegment(104, segmentB)
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(segmentA to decoded(testTriangleGeometry()), segmentB to decoded(testTriangleGeometry(3f))),
            )
        assertEquals(emptyList(), scene.notes, "two shapes with two materials are two nodes, not an ambiguity")
        val part = scene.root.children.single()
        assertEquals(
            listOf(Color(1f, 0f, 0f, 1f), Color(0f, 0f, 1f, 1f)),
            part.children.map { assertNotNull(it.material).baseColor },
        )
    }

    // spec: §13.9 (Range LOD: the tiers are alternatives, so a shape's tiers are its own ladder)
    @Test
    fun everyShapeOfAMultiTierPartKeepsItsOwnLodLadderAndMaterial() {
        val segments = (0 until 6).map { testGuid(0x20 + it) }
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.material(60, Rgba(1f, 0f, 0f, 1f))
        b.material(61, Rgba(0f, 0f, 1f, 1f))
        b.material(62, Rgba(0f, 1f, 0f, 1f))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        // Two tiers of three shapes each: shape *j* of every tier is one body's LOD ladder.
        b.rangeLod(101, listOf(102, 103))
        b.groupNode(102, listOf(110, 111, 112))
        b.groupNode(103, listOf(120, 121, 122))
        for (tier in 0 until 2) {
            for (shape in 0 until 3) {
                val id = 110 + 10 * tier + shape
                b.triStripShape(id, listOf(60 + shape))
                b.shapeSegment(id, segments[3 * tier + shape], segmentType = 7 + tier)
            }
        }
        val fine = testTriangleGeometry()
        val coarse = testTriangleGeometry(5f)
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(*segments.mapIndexed { i, id -> id to decoded(if (i < 3) fine else coarse) }.toTypedArray()),
            )

        assertEquals(emptyList(), scene.notes)
        val part = scene.root.children.single()
        assertEquals(3, part.children.size, "three bodies, three nodes")
        for ((index, body) in part.children.withIndex()) {
            assertEquals(2, body.meshes.size, "each body carries its own two tiers, finest first")
            assertEquals(0f, body.meshes[0].positions[0].x)
            assertEquals(5f, body.meshes[1].positions[0].x)
            assertEquals(
                listOf(Color(1f, 0f, 0f, 1f), Color(0f, 0f, 1f, 1f), Color(0f, 1f, 0f, 1f))[index],
                assertNotNull(body.material).baseColor,
            )
        }
    }

    @Test
    fun finestOnlyKeepsOneTierOfEveryBodyOfAMultiShapeTier() {
        val segments = (0 until 4).map { testGuid(0x30 + it) }
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102, 103))
        b.groupNode(102, listOf(110, 111))
        b.groupNode(103, listOf(120, 121))
        for (tier in 0 until 2) {
            for (shape in 0 until 2) {
                val id = 110 + 10 * tier + shape
                b.triStripShape(id)
                b.shapeSegment(id, segments[2 * tier + shape], segmentType = 7 + tier)
            }
        }
        val scene =
            buildScene(
                b.build(),
                LodPolicy.FINEST_ONLY,
                geometryResolver(
                    *segments.mapIndexed { i, id -> id to decoded(testTriangleGeometry(if (i < 2) 0f else 5f)) }
                        .toTypedArray(),
                ),
            )
        val part = scene.root.children.single()
        assertEquals(2, part.children.size)
        for (body in part.children) {
            assertEquals(1, body.meshes.size, "FINEST_ONLY keeps one tier per body, not one body")
            assertEquals(0f, body.meshes[0].positions[0].x)
        }
    }

    @Test
    fun aCoarserTierWithMoreShapesThanTheFinerOneIsNoted() {
        val segments = (0 until 3).map { testGuid(0x40 + it) }
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        // One shape in the finest tier, two in the coarse one: nothing states which coarse
        // shape stands for the fine one, so the positional pairing is named, not silent.
        b.rangeLod(101, listOf(102, 103))
        b.groupNode(102, listOf(110))
        b.groupNode(103, listOf(120, 121))
        b.triStripShape(110)
        b.shapeSegment(110, segments[0])
        b.triStripShape(120)
        b.shapeSegment(120, segments[1], segmentType = 8)
        b.triStripShape(121)
        b.shapeSegment(121, segments[2], segmentType = 8)
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(*segments.map { it to decoded(testTriangleGeometry()) }.toTypedArray()),
            )
        assertEquals(listOf("SCENE_LOD_TIERS_UNALIGNED"), scene.notes.map { it.name })
        assertTrue("[1, 2]" in scene.notes[0].message, "the note states the shapes per tier: ${scene.notes[0].message}")
        val part = scene.root.children.single()
        assertEquals(2, part.children.size)
        assertEquals(2, part.children[0].meshes.size, "the paired body keeps both tiers")
        assertEquals(1, part.children[1].meshes.size, "the unpaired coarse body is carried, not dropped")
    }

    @Test
    fun aFinerTierWithMoreShapesThanTheCoarseOneIsOrdinary() {
        val segments = (0 until 3).map { testGuid(0x50 + it) }
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        b.instance(1, 100)
        b.property(1, "JT_PROP_NAME", "part;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102, 103))
        b.groupNode(102, listOf(110, 111))
        b.groupNode(103, listOf(120))
        b.triStripShape(110)
        b.shapeSegment(110, segments[0])
        b.triStripShape(111)
        b.shapeSegment(111, segments[1])
        b.triStripShape(120)
        b.shapeSegment(120, segments[2], segmentType = 8)
        val scene =
            buildScene(
                b.build(),
                LodPolicy.ALL_LODS,
                geometryResolver(*segments.map { it to decoded(testTriangleGeometry()) }.toTypedArray()),
            )
        assertEquals(emptyList(), scene.notes, "a body that simply stops at a coarser level is not a misalignment")
        val part = scene.root.children.single()
        assertEquals(listOf(2, 1), part.children.map { it.meshes.size })
    }

    @Test
    fun unresolvableReferencesAreNotedAsIncompleteStructure() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1, 999))
        b.metaDataNode(1, emptyList())
        b.property(1, "JT_PROP_NAME", "present")
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver())
        assertEquals(listOf("present"), scene.root.children.map { it.name })
        assertEquals(listOf("SCENE_STRUCTURE_INCOMPLETE"), scene.notes.map { it.name })
        assertTrue("999" in scene.notes[0].message)
    }

    @Test
    fun nodeTransformsStayLocalAndComposeByTheRowVectorConvention() {
        val b = SceneLsgBuilder()
        b.partition(0, listOf(1))
        val outer = b.transform(60, translation(0.0, 0.0, 5.0))
        b.groupNode(1, listOf(2), listOf(outer.objectId))
        b.property(1, "JT_PROP_NAME", "carrier")
        val inner = b.transform(61, translation(1.0, 0.0, 0.0))
        b.instance(2, 100, listOf(inner.objectId))
        b.property(2, "JT_PROP_NAME", "leaf;0;1:")
        b.part(100, listOf(101))
        b.rangeLod(101, listOf(102))
        b.groupNode(102, listOf(103))
        b.triStripShape(103)
        b.shapeSegment(103, segmentA)
        val scene = buildScene(b.build(), LodPolicy.ALL_LODS, geometryResolver(segmentA to decoded(testTriangleGeometry())))

        val carrier = scene.root.children.single()
        val leaf = carrier.children.single()
        assertEquals(translation(0.0, 0.0, 5.0), carrier.transform.values, "transforms stay local, not accumulated")
        assertEquals(translation(1.0, 0.0, 0.0), leaf.transform.values)
        // World composition: local · parentWorld carries the origin to (1, 0, 5).
        val world = leaf.transform * carrier.transform * scene.root.transform
        assertEquals(Vec3(1f, 0f, 5f), world.transformPoint(Vec3(0f, 0f, 0f)))
    }

    private fun translation(
        x: Double,
        y: Double,
        z: Double,
    ): List<Double> =
        listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            x, y, z, 1.0,
        )
}
