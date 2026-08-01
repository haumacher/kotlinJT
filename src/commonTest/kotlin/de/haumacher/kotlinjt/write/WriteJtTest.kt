package de.haumacher.kotlinjt.write

import de.haumacher.kotlinjt.FileHeader
import de.haumacher.kotlinjt.JtFile
import de.haumacher.kotlinjt.SegmentKind
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.lsg.GeometricTransformAttributeElement
import de.haumacher.kotlinjt.lsg.InstanceNodeElement
import de.haumacher.kotlinjt.lsg.LateLoadedPropertyAtomElement
import de.haumacher.kotlinjt.lsg.MaterialAttributeElement
import de.haumacher.kotlinjt.lsg.PartitionNodeElement
import de.haumacher.kotlinjt.lsg.RangeLodNodeElement
import de.haumacher.kotlinjt.lsg.StringPropertyAtomElement
import de.haumacher.kotlinjt.lsg.TriStripSetShapeNodeElement
import de.haumacher.kotlinjt.lsg.decodeLsg
import de.haumacher.kotlinjt.scene.Color
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.LodPolicy
import de.haumacher.kotlinjt.scene.Mat4
import de.haumacher.kotlinjt.scene.Material
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.readScene
import de.haumacher.kotlinjt.shape.NestedElementHeader
import de.haumacher.kotlinjt.shape.TriStripSetShapeLodElementV10
import de.haumacher.kotlinjt.shape.decodeShapeLod
import de.haumacher.kotlinjt.shape.shapeLodSegments
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Layer 2 write side (issue #8): `writeJt(scene)` authors a JT 10.0 file, and reading it
 * back yields the scene it was given. Every test here is the full loop — author, parse,
 * `readScene` — because the reader is the only honest judge of what the writer wrote; the
 * scene-level assertions live in [assertSceneEquivalent] (which documents the one deliberate
 * difference: mesh vertices are re-indexed per triangle).
 */
class WriteJtTest {
    private val cubeScene: Scene
        get() =
            millimeterScene(
                SceneNode(
                    "assembly.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(part("cube.prt", unitCubeMesh())),
                ),
            )

    /** Writes [scene], asserts the Layer 0 standards, and returns the parsed file. */
    private fun writeAndParse(
        scene: Scene,
        byteOrder: Endianness = Endianness.LITTLE_ENDIAN,
    ): Pair<ByteArray, JtFile> {
        val bytes = writeJt(scene, byteOrder)
        val file = JtFile.parse(bytes)
        assertEquals(emptyList(), file.notes.map { it.name }, "an authored file must parse without notes")
        assertContentEquals(bytes, file.serialize(), "an authored file must re-serialize byte-identically")
        return bytes to file
    }

    // spec: Figure 92 (Topologically Compressed Rep Data — the authored topology)

    /**
     * The base case: a closed unit cube with per-face normals survives writing and re-reading
     * with bit-exact coordinates (the lossless vertex arrays), triangle order and winding.
     */
    @Test
    fun unitCubeSceneRoundTrips() {
        val scene = cubeScene
        val (bytes, file) = writeAndParse(scene)
        assertSceneEquivalent(scene, file.readScene(), "unit cube")
        // Sanity on the file's shape: header + LSG + one Shape LOD0 segment + TOC.
        assertEquals(2, file.segments.size)
        assertEquals(SegmentKind.LOGICAL_SCENE_GRAPH, file.segments[0].kind)
        assertEquals(SegmentKind.SHAPE_LOD0, file.segments[1].kind)
        assertTrue(bytes.size in 2_000..4_000, "the unit cube's file is ${bytes.size} bytes")
    }

    // spec: §7.1.4.1.3.1 (Vertex Flags: 1 marks a cover face added to close the mesh)

    /**
     * The authored topology, inspected at Layer 1: one component per triangle, each closed by
     * a single cover face, so a mesh stores two dual vertices and three degree-2 dual faces per
     * triangle and no split faces at all.
     */
    @Test
    fun authoredTopologyIsOneClosedComponentPerTriangle() {
        val (_, file) = writeAndParse(cubeScene)
        val segment = file.shapeLodSegments().single()
        val result = assertNotNull(file.decodeShapeLod(segment))
        assertEquals(emptyList(), result.notes.map { it.name }, "the authored shape body must decode typed")
        val element = assertIs<TriStripSetShapeLodElementV10>(result.document.elements.single())
        val triangles = 12
        val repData = element.repData
        assertEquals(2 * triangles, repData.vertexValences.valueCount)
        assertTrue(repData.vertexValences.values.all { it == 3 })
        assertEquals(triangles, repData.faceDegrees[1].valueCount, "the first face of each component is context 1")
        assertEquals(2 * triangles, repData.faceDegrees[0].valueCount)
        assertTrue((2..7).all { repData.faceDegrees[it].valueCount == 0 }, "no other degree context is used")
        assertEquals(3 * triangles, repData.faceAttributeMasks[0].valueCount)
        assertEquals(0, repData.splitFaceSymbols.valueCount)
        assertEquals(0, repData.splitFacePositions.valueCount)
        assertEquals(emptyList(), repData.highDegreeFaceAttributeMasks)
        assertEquals(3 * triangles, repData.vertexRecords.numberOfTopologicalVertices)
        assertEquals(3 * triangles, repData.vertexRecords.numberOfVertexAttributes)
        // Table 48: 3-component coordinates (bit 2) and normals (bit 4), nothing else.
        assertEquals(0xAUL, element.vertexBindings)
        // §12.1.3/§12.1.4: zero quantization bits is what marks the arrays lossless.
        assertEquals(true, repData.vertexRecords.coordinates?.isLossless)
        assertEquals(0, repData.vertexRecords.normals?.quantizationBits)
    }

    // spec: Figure 85 (the nested Logical Element Header wrapping the TopoMesh collection)
    @Test
    fun authoredShapeElementCarriesTheNestedTopoMeshHeader() {
        val (_, file) = writeAndParse(cubeScene)
        val result = assertNotNull(file.decodeShapeLod(file.shapeLodSegments().single()))
        val element = assertIs<TriStripSetShapeLodElementV10>(result.document.elements.single())
        assertEquals(
            NestedElementHeader.TOPO_MESH_TOPOLOGICALLY_COMPRESSED_LOD_DATA_TYPE_ID,
            element.nestedHeader.objectTypeId,
        )
        assertEquals(9, element.nestedHeader.objectBaseType, "Table 7: JtBase")
        // The length must span the nested element exactly — the decoder validates it, so a
        // mis-measured field would have refused the decode above.
        assertTrue(element.nestedHeader.elementLength > 0)
    }

    // spec: Figure 11 (File Header data collection)

    /** The 80-byte version string with its five ASCII/binary translation detection bytes. */
    @Test
    fun writtenHeaderFollowsFigure11() {
        val (bytes, file) = writeAndParse(cubeScene)
        assertEquals("Version 10.0 JT kotlinJT", file.header.versionString.trim())
        assertEquals(10, file.header.version.major)
        assertEquals(0, file.header.version.minor)
        assertEquals(' '.code.toByte(), bytes[75])
        assertEquals('\n'.code.toByte(), bytes[76])
        assertEquals('\r'.code.toByte(), bytes[77])
        assertEquals('\n'.code.toByte(), bytes[78])
        assertEquals(' '.code.toByte(), bytes[79])
        assertEquals(0, file.header.emptyField, "a freshly written file has an empty empty-field")
        assertEquals(null, file.header.trailingGuid, "no trailing GUID follows a zero empty field")
        assertEquals(FileHeader.VERSION_LENGTH + 1 + 4 + 8 + 16, file.header.headerLength)
        assertEquals(file.segments.first().tocEntry.segmentId, file.header.lsgSegmentId)
    }

    // spec: Figure 13 (TOC Entry) / Table 5 (segment attributes) / Table 6 (segment types)
    @Test
    fun writtenTocLocatesEverySegment() {
        val (bytes, file) = writeAndParse(cubeScene)
        assertEquals(file.segments.size, file.toc.entries.size)
        for (entry in file.toc.entries) {
            val segment = file.segments.single { it.tocEntry.segmentId == entry.segmentId }
            assertEquals(entry.offset, segment.offset)
            assertEquals(entry.length, segment.length)
            assertEquals(segment.typeCode, entry.typeCode, "Table 5: the type lives in bits 24-31")
            assertTrue(entry.offset + entry.length <= bytes.size)
        }
        assertEquals(file.toc.offset, file.header.tocOffset)
    }

    // spec: Table 8 / Table 9 (compression flag and algorithm values)

    /**
     * In JT 10 the only algorithms Table 9 defines are "none" and LZMA — ZLIB is a JT 9
     * generation value — so the writer stores the LSG segment plainly until an LZMA encoder
     * exists (recorded in DESIGN.md).
     */
    @Test
    fun writtenLsgSegmentIsStoredNotCompressed() {
        val (_, file) = writeAndParse(cubeScene)
        val compression = assertNotNull(file.segments.first().compression)
        assertEquals(1, compression.algorithmCode, "Table 9: 1 = no compression")
        assertTrue(compression.flag != 3u, "Table 8: only flag 3 means LZMA is on")
        // Shape LOD segments are not compressible at all (Table 6), so they carry no fields.
        assertEquals(null, file.segments[1].compression)
    }

    // spec: §6.1.1.4 (Instance Node Element) / §13.9 (LSG part structure)

    /**
     * A two-part assembly whose two placements share one part: the writer emits one part
     * definition plus instance nodes, and the re-read scene shares the same objects again
     * (identity, which the Layer 2 contract lets consumers exploit).
     */
    @Test
    fun sharedPartIsWrittenAsAnInstanceAndStaysShared() {
        val shared = part("bolt.prt", unitCubeMesh(), Material(Color(0.8f, 0.1f, 0.1f, 1f), 0.4f, 0f))
        val plate = part("plate.prt", coarseCubeMesh(), Material(Color(0.2f, 0.4f, 0.9f, 0.5f), 0.9f, 0f))
        val scene =
            millimeterScene(
                SceneNode(
                    "assembly.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(
                        SceneNode("bolt#1", translation(10.0, 0.0, 0.0), emptyList(), emptyList(), null, listOf(shared)),
                        SceneNode("bolt#2", translation(0.0, 20.0, 0.0), emptyList(), emptyList(), null, listOf(shared)),
                        plate,
                    ),
                ),
            )
        val (_, file) = writeAndParse(scene)
        val back = file.readScene()
        assertSceneEquivalent(scene, back, "two-part assembly")

        // The sharing is real on both sides of the round trip.
        val first = back.root.children[0].children.single()
        val second = back.root.children[1].children.single()
        assertSame(first, second, "both placements must reach the same part object")

        // The LSG says how: one instance node per placement, one part definition.
        val document = assertNotNull(file.decodeLsg()).document
        assertEquals(2, document.graphElements.count { it is InstanceNodeElement })
        assertEquals(
            2,
            document.graphElements.count { it is RangeLodNodeElement },
            "two distinct parts — the shared one is defined once",
        )
        assertEquals(2, file.shapeLodSegments().size, "the shared part's geometry is written once")
    }

    // spec: Table 6 (Shape LOD0-LOD9 segment types)

    /** One shape segment per mesh per tier, typed by its tier — finest first. */
    @Test
    fun everyLodTierBecomesItsOwnShapeSegment() {
        val tiers = listOf(unitCubeMesh(), coarseCubeMesh(), coarseCubeMesh())
        val scene =
            millimeterScene(
                SceneNode(
                    "assembly.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(SceneNode("part.prt", Mat4.IDENTITY, tiers, emptyList(), null, emptyList())),
                ),
            )
        val (_, file) = writeAndParse(scene)
        assertEquals(
            listOf(SegmentKind.SHAPE_LOD0, SegmentKind.SHAPE_LOD1, SegmentKind.SHAPE_LOD2),
            file.shapeLodSegments().map { it.kind },
        )
        assertSceneEquivalent(scene, file.readScene(LodPolicy.ALL_LODS), "three tiers")
        // The finest tier alone under the other policy.
        val finest = file.readScene(LodPolicy.FINEST_ONLY)
        assertEquals(1, finest.root.children.single().meshes.size)
    }

    // spec: Figure 82 / Figure 89 (Polyline Set Shape LOD Element, TopoMesh Compressed Rep Data)

    /**
     * Wireframe parts take the non-topological representation §7.1.4.1.2.2 assigns to polyline
     * sets: face-group/primitive/vertex index lists over unique vertex records.
     */
    @Test
    fun polylinePartRoundTrips() {
        val scene =
            millimeterScene(
                SceneNode(
                    "assembly.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(
                        SceneNode("curves.prt", Mat4.IDENTITY, emptyList(), listOf(testPolylines()), null, emptyList()),
                    ),
                ),
            )
        val (_, file) = writeAndParse(scene)
        assertSceneEquivalent(scene, file.readScene(), "polyline part")
        val back = file.readScene().root.children.single()
        assertEquals(listOf(3, 2), back.polylines.single().lines.map { it.size })
    }

    // spec: Table 48 (Vertex Shape LOD Bindings — normals are optional)
    @Test
    fun meshWithoutNormalsRoundTrips() {
        val cube = unitCubeMesh()
        val flat =
            Mesh(
                cube.positions,
                emptyList(),
                cube.triangles.map { Mesh.Triangle(it.v0, it.v1, it.v2, -1, -1, -1) },
            )
        val scene = millimeterScene(SceneNode("a.asm", Mat4.IDENTITY, emptyList(), emptyList(), null, listOf(part("p.prt", flat))))
        val (_, file) = writeAndParse(scene)
        assertSceneEquivalent(scene, file.readScene(), "mesh without normals")
        val result = assertNotNull(file.decodeShapeLod(file.shapeLodSegments().single()))
        val element = assertIs<TriStripSetShapeLodElementV10>(result.document.elements.single())
        assertEquals(0x2UL, element.vertexBindings, "coordinates only")
        assertEquals(0, element.repData.vertexRecords.numberOfVertexAttributes)
    }

    // spec: §13.8 (JT_PROP_NAME, JT_PROP_MEASUREMENT_UNITS, JT_LLPROP_SHAPEIMPL)

    /** The property conventions the installed base uses, as the writer emits them. */
    @Test
    fun namesAndUnitsFollowTheSection138Conventions() {
        val (_, file) = writeAndParse(cubeScene)
        val document = assertNotNull(file.decodeLsg()).document
        val strings = document.propertyAtoms.filterIsInstance<StringPropertyAtomElement>().map { it.value }
        assertTrue("JT_PROP_NAME" in strings)
        assertTrue("JT_PROP_MEASUREMENT_UNITS" in strings)
        assertTrue("Millimeters" in strings, "producers capitalize the unit value; readers accept both")
        assertTrue("assembly.asm" in strings)
        assertTrue("cube.prt" in strings)
        assertTrue("JT_LLPROP_SHAPEIMPL" in strings)

        // The late-loaded atom points at the shape segment by GUID and declares its type.
        val atom = document.propertyAtoms.filterIsInstance<LateLoadedPropertyAtomElement>().single()
        assertEquals(file.segments[1].tocEntry.segmentId, atom.segmentId)
        assertEquals(SegmentKind.SHAPE_LOD0.code, atom.segmentType)
        assertEquals(1, atom.reserved, "Figure 76: the v10.0 reserved field is >= 1")

        // The partition is the root, and the shape node hangs off a part via a Range LOD node.
        val partition = document.graphElements.filterIsInstance<PartitionNodeElement>().single()
        assertEquals(0, partition.partitionFlags, "no untransformed box, so no announcing bit")
        assertEquals(null, partition.untransformedBBox)
        assertEquals(1, document.graphElements.count { it is TriStripSetShapeNodeElement })
    }

    // spec: Figure 47 (Material Attribute Element)

    /**
     * The material inverse: the scene's roughness comes back from the Phong shininess the
     * writer computes as `2/roughness² − 2`, and the diffuse colour and alpha carry the base
     * colour unchanged.
     */
    @Test
    fun materialIsWrittenAsThePhongInverse() {
        val material = Material(Color(0.25f, 0.5f, 0.75f, 0.5f), 0.3f, 0f)
        val scene =
            millimeterScene(
                SceneNode("a.asm", Mat4.IDENTITY, emptyList(), emptyList(), null, listOf(part("p.prt", unitCubeMesh(), material))),
            )
        val (_, file) = writeAndParse(scene)
        val document = assertNotNull(file.decodeLsg()).document
        val element = document.graphElements.filterIsInstance<MaterialAttributeElement>().single()
        val expectedShininess = 2.0 / (0.3 * 0.3) - 2.0
        assertTrue(
            abs(element.shininess - expectedShininess) < 1e-3,
            "shininess ${element.shininess} should invert roughness 0.3 to $expectedShininess",
        )
        assertEquals(0.25f, element.diffuseColourAndAlpha.r)
        assertEquals(0.5f, element.diffuseColourAndAlpha.a)
        assertEquals(0, element.dataFlags, "Table 18: blending off, no overrides")
        assertEquals(8, element.baseAttribute.stateFlags, "Table 15: persistable only")
        // And the scene sees the roughness it started with.
        val back = file.readScene().root.children.single().material
        assertTrue(abs(assertNotNull(back).roughness - 0.3f) < 1e-5f)
        assertEquals(sqrt(2.0 / (2.0 + element.shininess)).toFloat(), back.roughness)
    }

    // spec: Figure 63 (Geometric Transform Attribute Element)
    @Test
    fun onlyNonIdentityTransformsBecomeAttributes() {
        val placed = SceneNode("placed", translation(1.0, 2.0, 3.0), emptyList(), emptyList(), null, listOf(part("p.prt", unitCubeMesh())))
        val scene = millimeterScene(SceneNode("a.asm", Mat4.IDENTITY, emptyList(), emptyList(), null, listOf(placed)))
        val (_, file) = writeAndParse(scene)
        val document = assertNotNull(file.decodeLsg()).document
        val transforms = document.graphElements.filterIsInstance<GeometricTransformAttributeElement>()
        assertEquals(1, transforms.size, "the identity transforms of the root and the part are not written")
        assertEquals(0xFFFF, transforms.single().storedValuesMask)
        assertEquals(placed.transform.values, transforms.single().matrix.values)
        assertSceneEquivalent(scene, file.readScene(), "placed part")
    }

    @Test
    fun bigEndianFileRoundTrips() {
        val scene = cubeScene
        val (_, file) = writeAndParse(scene, Endianness.BIG_ENDIAN)
        assertEquals(Endianness.BIG_ENDIAN, file.header.byteOrder)
        assertSceneEquivalent(scene, file.readScene(), "big-endian cube")
    }

    /** Determinism: golden pinning is only possible if identical scenes produce identical bytes. */
    @Test
    fun writingTheSameSceneTwiceProducesTheSameBytes() {
        assertContentEquals(writeJt(cubeScene), writeJt(cubeScene))
    }

    @Test
    fun writeJtFileReturnsTheParsedImage() {
        val file = writeJtFile(cubeScene)
        assertEquals(emptyList(), file.notes.map { it.name })
        assertEquals(2, file.segments.size)
    }

    // --- Refusals: a scene the writer cannot represent is never written silently ---

    // spec: §13.8 (Table 77 — the unit declaration is not optional in this model)
    @Test
    fun undeclaredUnitsAreRefused() {
        val scene = Scene(LengthUnit.UNSPECIFIED, cubeScene.root, emptyList())
        val error = assertFailsWith<JtWriteException> { writeJt(scene) }
        assertTrue(error.message!!.contains("JT_PROP_MEASUREMENT_UNITS"), error.message)
    }

    @Test
    fun geometryTogetherWithChildrenIsRefused() {
        val node =
            SceneNode("both", Mat4.IDENTITY, listOf(unitCubeMesh()), emptyList(), null, listOf(part("child.prt", coarseCubeMesh())))
        val error = assertFailsWith<JtWriteException> { writeJt(millimeterScene(node)) }
        assertTrue(error.message!!.contains("geometry and children"), error.message)
    }

    @Test
    fun aSoleUnnamedChildThatWouldBeAbsorbedIsRefused() {
        val scene =
            millimeterScene(
                SceneNode("a.asm", Mat4.IDENTITY, emptyList(), emptyList(), null, listOf(part("", unitCubeMesh()))),
            )
        val error = assertFailsWith<JtWriteException> { writeJt(scene) }
        assertTrue(error.message!!.contains("absorbed"), error.message)
    }

    @Test
    fun aPassThroughChildIsRefused() {
        val scene =
            millimeterScene(
                SceneNode(
                    "a.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(
                        SceneNode("", Mat4.IDENTITY, emptyList(), emptyList(), null, listOf(part("p.prt", unitCubeMesh()))),
                        part("q.prt", coarseCubeMesh()),
                    ),
                ),
            )
        val error = assertFailsWith<JtWriteException> { writeJt(scene) }
        assertTrue(error.message!!.contains("spliced"), error.message)
    }

    @Test
    fun anEmptyMeshIsRefused() {
        val scene =
            millimeterScene(
                SceneNode(
                    "a.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(part("p.prt", Mesh(emptyList(), emptyList(), emptyList()))),
                ),
            )
        val error = assertFailsWith<JtWriteException> { writeJt(scene) }
        assertTrue(error.message!!.contains("without triangles"), error.message)
    }

    @Test
    fun cornersWithoutNormalsInANormalBoundMeshAreRefused() {
        val cube = unitCubeMesh()
        val mixed =
            Mesh(
                cube.positions,
                cube.normals,
                listOf(cube.triangles[0], cube.triangles[1].copy(n1 = -1)),
            )
        val scene = millimeterScene(SceneNode("a.asm", Mat4.IDENTITY, emptyList(), emptyList(), null, listOf(part("p.prt", mixed))))
        val error = assertFailsWith<JtWriteException> { writeJt(scene) }
        assertTrue(error.message!!.contains("normal index"), error.message)
    }

    // spec: Table 6 (only Shape LOD0-LOD9 exist)
    @Test
    fun moreThanTenLodTiersAreRefused() {
        val scene =
            millimeterScene(
                SceneNode(
                    "a.asm",
                    Mat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(SceneNode("p.prt", Mat4.IDENTITY, List(11) { coarseCubeMesh() }, emptyList(), null, emptyList())),
                ),
            )
        val error = assertFailsWith<JtWriteException> { writeJt(scene) }
        assertTrue(error.message!!.contains("LOD tiers"), error.message)
    }

    /** A scene that is nothing but a named root is a legal (if boring) JT file. */
    @Test
    fun emptySceneWritesAStructureOnlyFile() {
        val scene = millimeterScene(SceneNode("empty.asm", Mat4.IDENTITY, emptyList(), emptyList(), null, emptyList()))
        val (_, file) = writeAndParse(scene)
        assertEquals(1, file.segments.size, "an LSG segment alone")
        assertSceneEquivalent(scene, file.readScene(), "structure-only scene")
    }
}
