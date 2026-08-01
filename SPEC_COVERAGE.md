# Spec coverage ledger — JT v10 File Format Reference Rev-C

Generated mechanically from the spec's table of contents; maintained by hand from here on.
This ledger exists so that **every normative unit of the spec has an explicit fate** — decoded,
carried opaquely, or n/a with a reason. It tracks *decode depth*, not losslessness: Layer 0's
opaque-blob backstop guarantees losslessness regardless of what is decoded (issue #1).

**Unit of account:** the *data-collection figure* (a normative byte layout — one decoder, one
serializer, at least one committed test each). Sections without figures (conventions, semantics)
are tracked at section granularity with behavioral tests.

**Status vocabulary** (used in both Read and Write columns):
- `—` not started
- `opaque` carried byte-faithfully as a blob, not decoded (lossless by construction)
- `partial` decoding exists but incomplete — the Notes column must say what is missing
- `done` decoded/serialized, with the test(s) named in Evidence
- `n/a: <reason>` nothing to implement, or excluded by doctrine — the reason must state the
  condition under which its time comes (no permanent non-goals)

**Discipline** (enforced by the working method):
1. Every agent brief names the ledger entries it is expected to flip.
2. `done` requires at least one committed test tagged `// spec: Figure N` (or `// spec: §x.y`)
   named in the Evidence column; the probe review checks the diff of this file against the delivery.
3. Entries are never deleted. Version deltas (v8/v9 vs v10) discovered against real files are
   recorded in the Notes column and in DESIGN.md.
4. The writer targets v10 with the simplest legal encodings — for many codec figures the honest
   final state is Read `done` / Write `n/a: writer emits the simple encoding`.


## §1 Intellectual Property License Terms

*Prefilled:* **n/a: license text — nothing to implement**


## §2 Scope

*Prefilled:* **n/a: scope prose — nothing to implement**


## §3 Terms, definitions and abbreviated terms

*Prefilled:* **n/a: terminology — nothing to implement**


## §4 Notational conventions

*Prefilled:* **n/a: notation used to read the spec itself — nothing to implement** — except
§4.2 Data Types, which is normative and tracked below.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Data Types (p.13) | done | done | PrimitivesTest | both byte orders, hand-built bytes per spec rules |

## §5 File Format

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| File Format (p.18) | done | done | SyntheticFileRoundTripTest, FixtureDiscoveryTest, Layer0ProbeTest | chapter row; Write = byte-faithful re-serialization (authoring writer is milestone 2) |
| File Structure (p.18) | done | done | SyntheticFileRoundTripTest | region model: segments + TOC + preserved gaps cover the file |
| File Header (p.18) | done | done | FileHeaderTest | v9 delta: I32 TOC offset, no trailing GUID (DESIGN.md, fixture-verified) |
| TOC Segment (p.20) | done | done | TocTest | v9 delta: 28-byte entries vs v10 32-byte (DESIGN.md) |
| Data Segment (p.21) | done | done | SyntheticFileRoundTripTest, HostileInputTest | hostile variants produce named notes, stay byte-faithful |
| Data Segments (p.26) | partial | partial | ElementScanTest, LsgDocumentTest, ShapeLodDocumentTest | framing scanned everywhere (incl. the 59 LZMA-inflated NIST streams); LSG element bodies decoded (§6, issue #3; v10.5 cross-producer via issue #5); shape LOD bodies decoded in both generations (§7 — JT 9 via issue #4, v10 via issue #6); meta data / PMI bodies stay opaque until the §11 package |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 10 — JT File Structure (p.18) | done | done | SyntheticFileRoundTripTest | |
| Fig. 11 — File Header data collection (p.19) | done | done | FileHeaderTest | v9: I32 TOC offset @85; verified against real 9.5 fixture |
| Fig. 12 — TOC Segment data collection (p.20) | done | done | TocTest | |
| Fig. 13 — TOC Entry data collection (p.21) | done | done | TocTest | v9 28-byte / v10 32-byte entry |
| Fig. 14 — Data Segment data collection (p.22) | done | done | SyntheticFileRoundTripTest | |
| Fig. 15 — Segment Header data collection (p.22) | done | done | SyntheticFileRoundTripTest, HostileInputTest | id/type/length mismatches → named notes |
| Fig. 16 — Data collection (p.24) | done | done | ZlibTest, SyntheticFileRoundTripTest | ZLIB (v9) and LZMA flag 3/algorithm 3 (v10.5 NIST, 59 segments) fixture-verified; flag∉{2,3} layout spec-derived, unverified against a real file — note fallback in place |
| Fig. 17 — Logical Element Header data collection (p.24) | done | done | ElementScanTest | |
| Fig. 18 — Element Header data collection (p.24) | done | done | ElementScanTest | |
| Fig. 19 — Logical Element Header Compressed data collection (p.25) | done | done | ElementScanTest, FixtureDiscoveryTest | scanned in inflated LSG of the real fixture (67 elements) |


## §6 LSG Segment

All §6 rows below share the version caveat established in DESIGN.md: v10 layouts per the
reference, v9 layouts only where fixture-verified; the non-material attribute elements are
opaque-with-note (`ELEMENT_LAYOUT_UNVERIFIED`) in v9 files. Since issue #5 a third
generation exists: **JT 10.5** deviates from the v10.0 reference in deltas 23–26
(DESIGN.md), established against the NIST fixture's LSG — 1 211 elements, all typed,
stream round-trip byte-identical, cross-producer coherence probes green (LsgProbeTest). Write = re-serialization of the
typed model, byte-identical to the decoded stream (authoring writer is milestone 2).

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| LSG Segment (p.28) | done | done | LsgDocumentTest, LsgSyntheticFileTest, FixtureDiscoveryTest | v9 fixture: 66 graph elements + 41 atoms + 40-entry property table; NIST 10.5: 267 graph elements + 944 atoms + 135-entry property table — all typed in both, stream round-trip byte-identical |
| Graph Elements (p.28) | done | done | LsgNodeElementCodecTest, LsgAttributeElementCodecTest | unknown/undecodable elements → opaque + named note (LsgDocumentTest) |
| Node Elements (p.29) | done | done | LsgNodeElementCodecTest | v9 deltas 6–9 in DESIGN.md, fixture-verified |
| Attribute Elements (p.49) | done | done | LsgAttributeElementCodecTest, Lsg105GenerationTest | v10 complete; 10.5 trailing I32 on the family (delta 24 — fixture-verified for material/transform/linestyle, family-rule-derived for the rest); v9: material only — others opaque-by-policy with note |
| Property Atom Elements (p.83) | done | done | LsgPropertyCodecTest | both generations |
| Base Property Atom Element (p.83) | done | done | LsgPropertyCodecTest.basePropertyAtomElement | |
| String Property Atom Element (p.84) | done | done | LsgPropertyCodecTest.stringPropertyAtomElement | fixture-verified (28 in the real file) |
| Integer Property Atom Element (p.84) | done | done | LsgPropertyCodecTest.integerPropertyAtomElement | fixture-verified (NIST 10.5: 17 atoms) |
| Floating Point Property Atom Element (p.85) | done | done | LsgPropertyCodecTest.floatingPointPropertyAtomElement | fixture-verified (NIST 10.5: 13 atoms) |
| JT Object Reference Property Atom Element (p.86) | done | done | LsgPropertyCodecTest.jtObjectReferencePropertyAtomElement | spec-derived, not yet fixture-verified; base type 6 (Table 7) |
| Date Property Atom Element (p.86) | done | done | LsgPropertyCodecTest.datePropertyAtomElement | fixture-verified both generations; 10.5 appends an undocumented F32 (delta 26) |
| Late Loaded Property Atom Element (p.88) | done | done | LsgPropertyCodecTest.lateLoadedPropertyAtomElement | fixture-verified (v9: 12, NIST 10.5: 105 references); 10.5 drops the Reserved I32 (delta 25) |
| Vector4f Property Atom Element (p.89) | done | done | LsgPropertyCodecTest.vector4fPropertyAtomElement | GUID missing from Table A.1 (spec inconsistency, recorded in ObjectTypeIds) |
| Property Table (p.90) | done | done | LsgDocumentTest, FixtureDiscoveryTest | fixture: 40 element tables, zero leftover bytes |
| Element Property Table (p.91) | done | done | LsgDocumentTest | |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 20 — LSG Segment data collection (p.28) | done | done | LsgDocumentTest.wellFormedDocumentDecodesAndRoundTrips | figure's 2nd list box garbled in the PDF; fixture confirms property atoms |
| Fig. 21 — Base Node Element data collection (p.29) | done | done | LsgNodeElementCodecTest.baseNodeElement | spec-derived, not yet fixture-verified |
| Fig. 22 — Base Node Data collection (p.29) | done | done | LsgNodeElementCodecTest.baseNodeElement | fixture-verified (all 66 graph elements) |
| Fig. 23 — Partition Node Element data collection (p.31) | done | done | LsgNodeElementCodecTest.partitionNodeElement, Lsg105GenerationTest.partitionBitZeroWithoutStoredBoxDecodes | fixture-verified incl. flag-bit-0 conditional box; 10.5: inserted version byte, bit 0 set without the box (delta 23) |
| Fig. 24 — Vertex Count Range data collection (p.32) | done | done | LsgNodeElementCodecTest.partitionNodeElement | |
| Fig. 25 — Group Node Element data collection (p.33) | done | done | LsgNodeElementCodecTest.groupNodeElement | fixture-verified |
| Fig. 26 — Group Node Data collection (p.34) | done | done | LsgNodeElementCodecTest.groupNodeElement | fixture-verified |
| Fig. 27 — Instance Node Element data collection (p.35) | done | done | LsgNodeElementCodecTest.instanceNodeElement | fixture-verified |
| Fig. 28 — Part Node Element data collection (p.35) | done | done | LsgNodeElementCodecTest.partNodeElement | fixture-verified |
| Fig. 29 — Meta Data Node Element data collection (p.36) | done | done | LsgNodeElementCodecTest.metaDataNodeElement | fixture-verified |
| Fig. 30 — Meta Data Node Data collection (p.36) | done | done | LsgNodeElementCodecTest.metaDataNodeElement | fixture-verified |
| Fig. 31 — LOD Node Element data collection (p.37) | done | done | LsgNodeElementCodecTest.lodNodeElement | spec-derived; v9 reserved fields fixture-verified via Range LOD |
| Fig. 32 — LOD Node Data collection (p.37) | done | done | LsgNodeElementCodecTest.lodNodeElement | v9 delta 7 in DESIGN.md |
| Fig. 33 — Range LOD Node Element data collection (p.38) | done | done | LsgNodeElementCodecTest.rangeLodNodeElement | fixture-verified (12 in the real file) |
| Fig. 34 — Switch Node Element data collection (p.39) | done | done | LsgNodeElementCodecTest.switchNodeElement | spec-derived, not yet fixture-verified |
| Fig. 35 — Base Shape Node Element data collection (p.40) | done | done | LsgNodeElementCodecTest.baseShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 36 — Base Shape Data collection (p.40) | done | done | LsgNodeElementCodecTest.baseShapeNodeElement | fixture-verified via tri-strip nodes; v9 delta 8 |
| Fig. 38 — Vertex Shape Node Element data collection (p.42) | done | done | LsgNodeElementCodecTest.vertexShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 39 — Vertex Shape Data collection (p.43) | done | done | LsgNodeElementCodecTest.vertexShapeNodeElement, .triStripSetShapeNodeElement | fixture-verified by consumption; v9 delta 9 and its evidence limit |
| Fig. 40 — Polyline Set Shape Node Element data collection (p.44) | done | done | LsgNodeElementCodecTest.polylineSetShapeNodeElement | fixture-verified (NIST 10.5: 15 nodes) |
| Fig. 41 — Point Set Shape Node Element data collection (p.45) | done | done | LsgNodeElementCodecTest.pointSetShapeNodeElement | spec-derived; the version==1 conditional binding treated as v10-only |
| Fig. 42 — Polygon Set Shape Node Element data collection (p.46) | done | done | LsgNodeElementCodecTest.polygonSetShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 43 — NULL Shape Node Element data collection (p.46) | done | done | LsgNodeElementCodecTest.nullShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 44 — Primitive Set Shape Node Element data collection (p.47) | done | done | LsgNodeElementCodecTest.primitiveSetShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 45 — Primitive Set Quantization Parameters data collection (p.48) | done | done | LsgNodeElementCodecTest.primitiveSetShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 46 — Base Attribute Data collection (p.49) | done | done | LsgAttributeElementCodecTest.materialAttributeElement, .materialAttributeElementV9, Lsg105GenerationTest | v9 delta 10: no field-final flags, fixture-verified; 10.5: attribute elements gain a trailing I32 (delta 24, NIST: 88 elements) |
| Fig. 47 — Material Attribute Element data collection (p.51) | done | done | LsgAttributeElementCodecTest.materialAttributeElement, .materialAttributeElementV9, Lsg105GenerationTest.materialAttributeCarriesTheTrailingField | fixture-verified all three generations (NIST 10.5: 37 elements); v9 delta 11, 10.5 delta 24 |
| Fig. 48 — Texture Image Attribute Element data collection (p.54) | done | done | LsgAttributeElementCodecTest.textureImageAttributeElementExternal | v10 only; spec-derived, not yet fixture-verified |
| Fig. 49 — Texture Vers-1 Data collection (p.55) | done | done | LsgAttributeElementCodecTest.textureImageAttributeElementExternal, .textureImageAttributeElementInline | v10 only; spec-derived, not yet fixture-verified |
| Fig. 50 — Texture Environment data collection (p.58) | done | done | LsgAttributeElementCodecTest.textureImageAttributeElementExternal | v10 only; spec-derived, not yet fixture-verified |
| Fig. 51 — Texture Coord Generation Parameters data collection (p.61) | done | done | LsgAttributeElementCodecTest.textureImageAttributeElementExternal | v10 only; spec-derived, not yet fixture-verified |
| Fig. 52 — Inline Texture Image Data collection (p.62) | done | done | LsgAttributeElementCodecTest.textureImageAttributeElementInline | v10 only; spec-derived, not yet fixture-verified |
| Fig. 53 — Image Format Description data collection (p.63) | done | done | LsgAttributeElementCodecTest.textureImageAttributeElementInline | v10 only; spec-derived, not yet fixture-verified |
| Fig. 54 — Draw Style Attribute Element data collection (p.65) | done | done | LsgAttributeElementCodecTest.drawStyleAttributeElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 55 — Light Set Attribute Element data collection (p.67) | done | done | LsgAttributeElementCodecTest.lightSetAttributeElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 56 — Infinite Light Attribute Element data collection (p.68) | done | done | LsgAttributeElementCodecTest.infiniteLightAttributeElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 57 — Base Light Data collection (p.69) | done | done | LsgAttributeElementCodecTest.infiniteLightAttributeElement, .pointLightAttributeElement | figure garbled in the PDF (stray header box); read as base attribute data first — recorded in DESIGN.md |
| Fig. 58 — Point Light Attribute Element data collection (p.71) | done | done | LsgAttributeElementCodecTest.pointLightAttributeElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 59 — Spread Angle value with respect to the light cone (p.72) | n/a | n/a | | illustrative drawing, no byte layout |
| Fig. 60 — Attenuation Coefficients data collection (p.73) | done | done | LsgAttributeElementCodecTest.pointLightAttributeElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 61 — Linestyle Attribute Element data collection (p.74) | done | done | LsgAttributeElementCodecTest.linestyleAttributeElement, Lsg105GenerationTest.linestyleCarriesTheTrailingField | v10.5 fixture-verified (NIST: 15 elements, delta 24); v9 layout not established |
| Fig. 62 — Pointstyle Attribute Element data collection (p.75) | done | done | LsgAttributeElementCodecTest.pointstyleAttributeElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 63 — Geometric Transform Attribute Element data collection (p.76) | done | done | LsgAttributeElementCodecTest.geometricTransformAttributeElement, Lsg105GenerationTest.geometricTransformCarriesTheTrailingField | sparse mask-driven storage, full matrix in the model; v10.5 fixture-verified (NIST: 36 elements, delta 24) |
| Fig. 64 — Texture Coordinate Generator Attribute Element data collection (p.78) | done | done | LsgAttributeElementCodecTest.textureCoordinateGeneratorWithMappingPlane | nested mapping-surface element; alien surface stays lossless (LsgDocumentTest) |
| Fig. 65 — Mapping Plane Element data collection (p.79) | done | done | LsgAttributeElementCodecTest.textureCoordinateGeneratorWithMappingPlane | v10 only; spec-derived, not yet fixture-verified |
| Fig. 66 — Mapping Cylinder Element data collection (p.80) | done | done | LsgAttributeElementCodecTest.mappingCylinderElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 67 — Mapping Sphere Element data collection (p.81) | done | done | LsgAttributeElementCodecTest.mappingSphereElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 68 — Mapping TriPlanar Element data collection (p.82) | done | done | LsgAttributeElementCodecTest.mappingTriPlanarElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 69 — Base Property Atom Element data collection (p.83) | done | done | LsgPropertyCodecTest.basePropertyAtomElement | spec-derived, not yet fixture-verified |
| Fig. 70 — Base Property Atom Data collection (p.83) | done | done | LsgPropertyCodecTest.basePropertyAtomElement | fixture-verified (all 41 atoms) |
| Fig. 71 — String Property Atom Element data collection (p.84) | done | done | LsgPropertyCodecTest.stringPropertyAtomElement | fixture-verified (v9: 28, NIST 10.5: 796) |
| Fig. 72 — Integer Property Atom Element data collection (p.85) | done | done | LsgPropertyCodecTest.integerPropertyAtomElement | fixture-verified (NIST 10.5) |
| Fig. 73 — Floating Point Property Atom Element data collection (p.85) | done | done | LsgPropertyCodecTest.floatingPointPropertyAtomElement | fixture-verified (NIST 10.5) |
| Fig. 74 — JT Object Reference Property Atom Element data collection (p.86) | done | done | LsgPropertyCodecTest.jtObjectReferencePropertyAtomElement | spec-derived, not yet fixture-verified |
| Fig. 75 — Date Property Atom Element data collection (p.87) | done | done | LsgPropertyCodecTest.datePropertyAtomElement | fixture-verified; 10.5 delta 26 (trailing F32, NIST: −4.0 ≙ the timestamps' UTC offset) |
| Fig. 76 — Late Loaded Property Atom Element data collection (p.88) | done | done | LsgPropertyCodecTest.lateLoadedPropertyAtomElement | fixture-verified; 10.5 drops the "always ≥ 1" Reserved I32 (delta 25) |
| Fig. 77 — Vector4f Property Atom Element data collection (p.89) | done | done | LsgPropertyCodecTest.vector4fPropertyAtomElement | spec-derived, not yet fixture-verified |
| Fig. 78 — Property Table data collection (p.90) | done | done | LsgDocumentTest.wellFormedDocumentDecodesAndRoundTrips | fixture-verified (v9: 40 tables, NIST 10.5: 135); also identifies the shape segments' 6-byte tail (DESIGN.md) |
| Fig. 79 — Element Property Table data collection (p.91) | done | done | LsgDocumentTest.wellFormedDocumentDecodesAndRoundTrips | fixture-verified |


## §7 Shape LOD Segment

Both wire generations are implemented and fixture-verified where a fixture exists: the
**JT 9 generation** against the 9.5 file's 12 tri-strip bodies (issue #4) and the **v10
generation** against the NIST 10.5 file's 39 bodies — 24 tri-strip + 15 polyline, all 117
stored hashes verified at decode (issue #6, DESIGN.md deltas 27–31). Types without any
fixture (point/polygon/primitive set; polyline in JT 9) stay opaque-with-note — never
guessed. Write = byte-identical re-serialization of the typed model (authoring writer is
milestone 2).

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Shape LOD Segment (p.92) | done | done | ShapeLodDocumentTest, FixtureDiscoveryTest | all 51 fixture bodies (12 v9 + 39 v10) typed + byte-identical round-trip; unfixtured element types opaque-with-note |
| Shape LOD Element (p.92) | done | done | ShapeLodDocumentTest | element framing + strict-or-opaque dispatch, both generations |
| Tri-Strip Set Shape LOD Element (p.92) | done | done | ShapeLodDocumentTest.triStripSetElementDecodesTheTetrahedron, .v10TriStripSetElementDecodesTheTetrahedron, FixtureDiscoveryTest | both generations fixture-verified incl. decoded triangles/normals (v9 delta 14; v10 delta 27) |
| Polyline Set Shape LOD Element (p.93) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified (15 NIST bodies, FGPV/length/coordinate hashes); JT 9 layout unestablished — no v9 fixture carries one, opaque-with-note |
| Point Set Shape LOD Element (p.93) | opaque | opaque | ShapeLodDocumentTest.unestablishedV10ShapeTypesStayOpaqueWithNote | no fixture carries one; named note |
| Polygon Set LOD Element (p.94) | opaque | opaque | ShapeLodDocumentTest (opaque paths) | as above |
| Null Shape LOD Element (p.107) | partial | partial | ShapeLodDocumentTest.nullShapeLodElementDecodesInV9 | both generations spec-derived (I16 vs U8 version), not yet fixture-verified — neither fixture carries one |
| Primitive Set Shape Element (p.107) | opaque | opaque | ShapeLodDocumentTest (opaque paths) | no fixture carries one; named note |
| Lossless Compressed Primitive Set Data (p.109) | opaque | opaque |  | inside the opaque Primitive Set body |
| Lossy Quantized Primitive Set Data (p.111) | opaque | opaque |  | inside the opaque Primitive Set body |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 81 — Tri-Strip Set Shape LOD Element data collection (p.92) | done | done | ShapeLodDocumentTest.triStripSetElementDecodesTheTetrahedron, .v10TriStripSetElementDecodesTheTetrahedron, FixtureDiscoveryTest | v9 layout delta 14 (reserved 12-byte tail); v10 layout incl. the nested element header (delta 27) — both fixture-verified |
| Fig. 82 — Polyline Set Shape LOD Element data collection (p.93) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified; JT 9 opaque-with-note (no fixture) |
| Fig. 83 — Point Set Shape LOD Element data collection (p.94) | opaque | opaque | ShapeLodDocumentTest.unestablishedV10ShapeTypesStayOpaqueWithNote | see section row |
| Fig. 84 — Polygon Set LOD Element data collection (p.94) | opaque | opaque |  | see section row |
| Fig. 85 — Vertex Shape LOD Data collection (p.95) | done | done | ShapeLodDocumentTest (both tetrahedra), FixtureDiscoveryTest | v9: I16 version + U64 bindings; v10: I8 version + U64 bindings + the nested Logical Element Header whose type GUIDs are absent from Annex A (delta 27) — both fixture-verified |
| Fig. 86 — Base Shape LOD Data collection (p.97) | done | done | ShapeLodDocumentTest (both tetrahedra) | I16 version in v9, I8 in v10 — both fixture-verified |
| Fig. 87 — TopoMesh Compressed LOD Data collection (p.97) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified (the polyline container); JT 9 with the polyline element |
| Fig. 88 — TopoMesh LOD Data collection (p.98) | done | done | ShapeLodDocumentTest (both tetrahedra) | v9: I16 version + I32 object id; v10: U8 + U32 — both fixture-verified |
| Fig. 89 — TopoMesh Compressed Rep Data data collection (p.99) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified: FGPV + unique-length hashes validated, index lists carry count+1 terminators; JT 9 with the polyline element |
| Fig. 90 — Quantization Parameters data collection (p.101) | done | done | ShapeLodDocumentTest, FixtureDiscoveryTest | 4×U8, identical in both generations; delta 21 (v9 normal bits factor is not authoritative); in v10 factor == the packed Deering per-angle bits in all 39 bodies (DESIGN.md) |
| Fig. 91 — TopoMesh Topologically Compressed LOD Data collection (p.102) | done | done | ShapeLodDocumentTest (both tetrahedra) | I16 version in v9, U8 in v10 — both fixture-verified |
| Fig. 92 — Topologically Compressed Rep Data Collection (p.103) | done | done | ShapeLodDocumentTest (tetrahedra + corrupt-stream refusals), FixtureDiscoveryTest | both generations fixture-verified incl. composite-hash validation and the Annex-D topology decode; the 8th mask context is 30/30/4 in v9 (delta 20) and 32/32 in v10 (figure-accurate) |
| Fig. 93 — Topologically Compressed Vertex Records data collection (p.106) | done | done | ShapeLodDocumentTest (both tetrahedra), FixtureDiscoveryTest | both generations fixture-verified incl. v10 per-vertex flag arrays; colours/texcoords/aux bindings refuse with note (no fixture) |
| Fig. 94 — Null Shape LOD Element data collection (p.107) | partial | partial | ShapeLodDocumentTest.nullShapeLodElementDecodesInV9 | both generations spec-derived, not yet fixture-verified |
| Fig. 96 — Lossless Compressed Primitive Set Data collection (p.109) | opaque | opaque |  | inside the opaque Primitive Set body |
| Fig. 97 — Lossy Quantized Primitive Set Data collection (p.111) | opaque | opaque |  | inside the opaque Primitive Set body |
| Fig. 98 — Compressed params1 data collection (p.113) | opaque | opaque |  | inside the opaque Primitive Set body |


## §8 Precise Geometry Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Precise Geometry Segment (p.115) | — | — |  |  |
| JT B-Rep Element (deprecated) (p.115) | — | — |  |  |
| XT B-Rep Element (p.115) | — | — |  |  |
| JT ULP Segment (p.115) | — | — |  |  |


## §9 JT LWPA Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| JT LWPA Segment (p.115) | — | — |  |  |
| JT LWPA Element (p.116) | — | — |  |  |
| Analytic Surface Geometry (p.117) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 99 — JT LWPA Segment data collection (p.116) | — | — |  |  |
| Fig. 100 — JT LWPA Element data collection (p.116) | — | — |  |  |
| Fig. 101 — Analytic Surface Geometry data collection (p.117) | — | — |  |  |
| Fig. 102 — Analytic Surface Creation (p.119) | — | — |  |  |


## §10 Wireframe Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Wireframe Segment (p.120) | — | — |  |  |
| Wireframe Rep Element (p.120) | — | — |  |  |
| Wireframe MCS Curves Geometric Data (p.122) | — | — |  |  |
| Wireframe Rep CAD Tag Data (p.122) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 104 — Wireframe Rep Element data collection (p.121) | — | — |  |  |
| Fig. 105 — Wireframe MCS Curves Geometric Data collection (p.122) | — | — |  |  |


## §11 Meta Data Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Meta Data Segment (p.123) | — | — |  |  |
| Property Proxy Meta Data Element (p.123) | — | — |  |  |
| Date Property Value (p.125) | — | — |  |  |
| PMI Manager Meta Data Element (p.126) | — | — |  |  |
| PMI Design Group Entities (p.128) | — | — |  |  |
| PMI Associations (p.130) | — | — |  |  |
| PMI User Attributes (p.133) | — | — |  |  |
| PMI String Table (p.133) | — | — |  |  |
| PMI Model Views (p.134) | — | — |  |  |
| Generic PMI Entities (p.139) | — | — |  |  |
| PMI CAD Tag Data (p.149) | — | — |  |  |
| PMI Polygon Data (p.150) | — | — |  |  |
| PMI Properties (p.153) | — | — |  |  |
| PMI Model View Sort Orders (p.154) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 106 — Wireframe Rep CAD Tag Data collection (p.123) | — | — |  |  |
| Fig. 107 — Meta Data Segment data collection (p.123) | — | — |  |  |
| Fig. 109 — Date Property Value data collection (p.125) | — | — |  |  |
| Fig. 110 — PMI Manager Meta Data Element data collection (p.127) | — | — |  |  |
| Fig. 111 — PMI Design Group Entities data collection (p.128) | — | — |  |  |
| Fig. 112 — Design Group Attribute data collection (p.129) | — | — |  |  |
| Fig. 113 — PMI Associations data collection (p.131) | — | — |  |  |
| Fig. 114 — PMI User Attributes data collection (p.133) | — | — |  |  |
| Fig. 115 — PMI String Table data collection (p.134) | — | — |  |  |
| Fig. 116 — PMI Model Views data collection (p.135) | — | — |  |  |
| Fig. 117 — PMI Property data collection (p.137) | — | — |  |  |
| Fig. 118 — Key PMI Property Atom data collection (p.138) | — | — |  |  |
| Fig. 120 — PMI 2D Data collection (p.142) | — | — |  |  |
| Fig. 121 — PMI Base Data collection (p.142) | — | — |  |  |
| Fig. 122 — 2D-Reference Frame data collection (p.143) | — | — |  |  |
| Fig. 123 — 2D Text Data collection (p.144) | — | — |  |  |
| Fig. 124 — Text Box data collection (p.145) | — | — |  |  |
| Fig. 125 — Constructing Text Polylines from data arrays (p.146) | — | — |  |  |
| Fig. 126 — Text Polyline Data collection (p.146) | — | — |  |  |
| Fig. 127 — Constructing Non-Text Polylines from packed 2D data arrays (p.147) | — | — |  |  |
| Fig. 128 — Non-Text Polyline Data collection (p.148) | — | — |  |  |
| Fig. 129 — PMI CAD Tag Data collection (p.149) | — | — |  |  |
| Fig. 130 — PMI Polygon Data (p.151) | — | — |  |  |


## §12 Data Compression and Encoding

Both wire generations of the shape codecs are implemented and fixture-verified: the **JT 9
generation** (issue #4 — 12 real bodies, 506 packets) and the **v10 generation** (issue #6 —
39 NIST bodies, ~1 300 packets: bitlength both modes, arithmetic with the Figure-133
context, chopper, Move-to-Front, packed Deering codes; every stored hash validated at
decode). DESIGN.md deltas 15–21 record the JT 9 differences, deltas 27–31 the places where
the NIST bytes contradict or complete the v10 text.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Data Compression and Encoding (p.154) | partial | partial | Int32CdpTest, Int32CdpV10Test, VertexArrayTest, FixtureDiscoveryTest | chapter row: both generations of the §7 codecs done; Int64CDP and the curve/B-rep structures pending their first consumer |
| Common Compression Data Collection Formats (p.155) | partial | partial | Int32CdpTest, Int32CdpV10Test, VertexArrayTest | as above |
| Int32 Compressed Data Packet (p.155) | done | done | Int32CdpTest, Int32CdpV10Test, FixtureDiscoveryTest | JT 9 "Mk. 2" packet (delta 15) and the v10 third-generation packet (Figure 132) incl. Move-to-Front (delta 31) and the escape-conditional out-of-band packet (delta 28); v10 chopper-with-0-chop-bits refuses (spec forbids it) |
| Int64 Compressed Data Packet (p.161) | — | — |  | no §7 structure needs it; first consumer is the B-rep/curve data (§12.1.13+) |
| Compressed Vertex Coordinate Array (p.164) | done | done | VertexArrayTest (both generations, lossless + quantized + hash refusals), FixtureDiscoveryTest | JT 9: exp/mant pairs (delta 19); v10: one binary/code packet per component, hashed per component array (delta 29) — stored hashes verified at decode |
| Compressed Vertex Normal Array (p.165) | done | done | VertexArrayTest (both generations incl. packed Deering + NULL-predictor pin), FixtureDiscoveryTest | JT 9: sextant/octant/theta/psi packets (delta 19); v10: one packed Deering code array, NULL predictor (deltas 29/30) |
| Compressed Vertex Texture Coordinate Array (p.167) | — | — |  | no fixture declares texture bindings; typed decode refuses with a named note until one does |
| Compressed Vertex Colour Array (p.168) | — | — |  | as above (colour bindings) |
| Compressed Vertex Flag Array (p.170) | done | done | VertexArrayTest.vertexFlagArrayRoundTripsAndValidatesTheCount, FixtureDiscoveryTest | v10 fixture-verified (all 39 NIST bodies bind vertex flags); the figure defines no stored hash — count validation only |
| Compressed Auxiliary Fields Array (p.170) | — | — |  | as above (auxiliary field binding) |
| Point Quantizer Data (p.174) | done | done | VertexArrayTest.pointQuantizerIsThreeUniformQuantizers, FixtureDiscoveryTest | identical in both generations |
| Texture Quantizer Data (p.175) | — | — |  | with the texture coordinate array |
| Colour Quantizer Data (p.175) | — | — |  | with the colour array |
| Uniform Quantizer Data (p.177) | done | done | VertexArrayTest.uniformQuantizerRoundTripsAndDequantizes, FixtureDiscoveryTest | identical in both generations |
| Compressed Entity List for Non-Trivial Knot Vector (p.177) | — | — |  | B-rep/wireframe curve data — outside §7 |
| Compressed Control Point Weights Data (p.180) | — | — |  | as above |
| Compressed Curve Data (p.181) | — | — |  | as above |
| Compressed CAD Tag Data (p.185) | — | — |  | as above |
| Encoding Algorithms (p.186) | done | n/a: writer emits the simple encodings | Int32CdpTest, Int32CdpV10Test, VertexArrayTest | decode side: both generations fixture-verified |
| Uniform Data Quantization (p.186) | done | n/a: writer emits lossless data | VertexArrayTest.uniformQuantizerRoundTripsAndDequantizes, FixtureDiscoveryTest | inverse implemented; since issue #6 fixture-exercised (the NIST LOD1/2 coordinates are quantized at 9–17 bits and land inside their shape nodes' boxes) |
| Bitlength CODEC (p.186) | done | n/a: writer emits the null CODEC | Int32CdpTest.bitlengthFixedWidthDecodes, .bitlengthVariableWidthDecodes, Int32CdpV10Test.bitlengthFixedWidthDecodesWithNibbledMinMax, .bitlengthVariableWidthDecodesWithFourBitBlocks | JT 9 wire format fixture-established (delta 17 — **neither spec's prose matches that wire**); v10 nibbler/4-bit-block variant per Annex B, verified on 696 NIST packets |
| Arithmetic CODEC (p.188) | done | n/a: writer emits the null CODEC | Int32CdpTest.arithmeticCodecDecodesWithEscapeAndOutOfBand, Int32CdpV10Test (escape + escapeless vectors), FixtureDiscoveryTest | one shared 16-bit decoder core; JT 9 contexts (delta 16) and v10 Figure-133 contexts, incl. the escape-conditional out-of-band packet (delta 28) |
| Deering Normal CODEC (p.193) | done | n/a: writer emits lossless normals | VertexArrayTest.deeringCodeConversionMatchesTheReference, .v10QuantizedNormalArrayUnpacksPackedDeeringCodes, FixtureDiscoveryTest | JT 9: four code packets; v10: packed `[sextant:3][octant:3][theta:n][psi:n]` codes (Annex B unpackCode) — hashes verified on all 24 NIST tri-strip normal arrays |
| LZMA compression (p.195) | done | n/a: writer emits ZLIB or none (issue #1 codec policy) | LzmaTest, SyntheticFileRoundTripTest.v10RoundTripWithLzmaDecoding, FixtureDiscoveryTest | pure-Kotlin `.xz`/LZMA2 decoder in commonMain — the wire container §12.2.5 names via XZ Utils, byte-verified on all 59 NIST streams (DESIGN.md); corrupt streams → COMPRESSED_DATA_CORRUPT, unimplemented xz features → UNSUPPORTED_COMPRESSION |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 131 — PMI Model View Sort Orders data collection (p.154) | — | — |  |  |
| Fig. 132 — Int32 Compressed Data Packet data collection (p.156) | done | done | Int32CdpTest, Int32CdpV10Test (all codec paths + hostile inputs), FixtureDiscoveryTest | JT 9 "Mk. 2" (delta 15) and v10 third-generation wire formats, both fixture-verified; out-of-band packet conditional on the escape entry (delta 28) |
| Fig. 133 — Int32 Probability Context (p.159) | done | done | Int32CdpTest.arithmeticCodecDecodesWithEscapeAndOutOfBand, Int32CdpV10Test | JT 9 table (delta 16: symbol field, escape −2) and v10 table (escape flag, 7-bit value width) |
| Fig. 134 — Int32 Probability Context Table Entry data collection (p.160) | done | done | Int32CdpV10Test (escape + escapeless vectors) | as Fig. 133 |
| Fig. 135 — Int64 Compressed Data Packet data collection (p.161) | — | — |  | first consumer is B-rep/curve data |
| Fig. 136 — Int64 Probability Context data collection (p.163) | — | — |  | as above |
| Fig. 137 — Int64 Probability Context Table Entry data collection (p.163) | — | — |  | as above |
| Fig. 138 — Compressed Vertex Coordinate Array data collection (p.164) | done | done | VertexArrayTest (both generations, hash refusals), FixtureDiscoveryTest | JT 9 layout (delta 19); v10 layout with per-array lossless hashing (delta 29) |
| Fig. 139 — Compressed Vertex Normal Array data collection (p.166) | done | done | VertexArrayTest (both generations incl. the NULL-predictor pin), FixtureDiscoveryTest | JT 9 layout (delta 19); v10 packed Deering codes, NULL predictor (delta 30) |
| Fig. 140 — Compressed Vertex Texture Coordinate Array data collection (p.167) | — | — |  | no fixture declares the binding; refuses with note |
| Fig. 141 — Compressed Vertex Colour Array data collection (p.169) | — | — |  | as above |
| Fig. 142 — Compressed Vertex Flag Array data collection (p.170) | done | done | VertexArrayTest.vertexFlagArrayRoundTripsAndValidatesTheCount, FixtureDiscoveryTest | v10 fixture-verified (Table 48 bit 7 on every NIST body) |
| Fig. 143 — Compressed Auxiliary Fields Array data collection (p.171) | — | — |  | as above |
| Fig. 144 — Point Quantizer Data collection (p.174) | done | done | VertexArrayTest.pointQuantizerIsThreeUniformQuantizers, FixtureDiscoveryTest | identical in both generations |
| Fig. 145 — Texture Quantizer Data collection (p.175) | — | — |  | with the texture coordinate array |
| Fig. 146 — Colour Quantizer Data collection (p.176) | — | — |  | with the colour array |
| Fig. 147 — Uniform Quantizer Data collection (p.177) | done | done | VertexArrayTest.uniformQuantizerRoundTripsAndDequantizes, FixtureDiscoveryTest | identical in both generations |
| Fig. 148 — Compressed Entity List for Non-Trivial Knot Vector data collection (p.178) | — | — |  |  |
| Fig. 149 — Compressed Control Point Weights Data collection (p.180) | — | — |  |  |
| Fig. 150 — Compressed Curve Data collection (p.182) | — | — |  |  |
| Fig. 151 — Non-Trivial Knot Vector NURBS Curve Indices data collection (p.184) | — | — |  |  |
| Fig. 152 — NURBS Curve Control Point Weights data collection (p.184) | — | — |  |  |
| Fig. 153 — NURBS Curve Control Points data collection (p.184) | — | — |  |  |
| Fig. 154 — Compressed CAD Tag Data collection (p.185) | — | — |  |  |
| Fig. 155 — Sextant Coding on the Sphere (p.194) | n/a | n/a |  | illustrative drawing, no byte layout (the coding itself is the Deering CODEC row) |


## §13 Common Data Conventions and Constructs

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Common Data Conventions and Constructs (p.196) | — | — |  |  |
| Late-Loading Data (p.196) | done | — | SceneReadTest, SceneSyntheticFileTest, SceneFixtureTest, FixtureDiscoveryTest | shape geometry resolved via Late Loaded Property Atoms by segment GUID + shape segment type (delta 13: object ids do not associate) |
| TOC Segment Location (p.196) | — | — |  |  |
| Bit Fields (p.196) | — | — |  |  |
| Empty Field (p.196) | — | — |  |  |
| Local version numbers (p.196) | — | — |  |  |
| Version numbers (p.196) | — | — |  |  |
| Hash Value (p.197) | — | — |  |  |
| Scene graph construction (p.197) | done | — | SceneReadTest, SceneFixtureTest, SceneNistFixtureTest | the Layer 2 walk: partition root, instance sharing (shared scene objects), group/metadata structure; write side comes with `writeJt` |
| Metadata Conventions (p.198) | partial | — | SceneReadTest, SceneFixtureTest | the name/units conventions are interpreted (rows below); all other properties are carried as Layer 1 atoms, uninterpreted |
| Property Key Naming Conventions (p.198) | done | — | SceneReadTest (hidden vs. visible key forms), SceneFixtureTest | hidden `key` and visible `key::` accepted as one key, case-sensitive otherwise |
| PMI Properties (p.199) | — | — |  | with the PMI package (§11) |
| CAD Properties (p.199) | partial | — | SceneReadTest (units battery), SceneFixtureTest, SceneNistFixtureTest | JT_PROP_MEASUREMENT_UNITS (the required property) interpreted case-insensitively per the spec's own producer note — both fixtures write "Millimeters"; the other CAD properties pass through as Layer 1 atoms |
| Tessellation Properties (p.201) | — | — |  | carried raw at Layer 1 (Chordal/Angular/SegLength observed on the NIST fixture) |
| Miscellaneous Properties (p.202) | partial | — | SceneReadTest.nameEncodingIsDecodedAndPlainNamesPassVerbatim, SceneNistFixtureTest | JT_PROP_NAME incl. the `Name;version;instance:` encoded form interpreted (both fixtures use it); the other miscellaneous keys pass through |
| The SUBNODE property and Reference Sets (p.203) | — | — |  | observed on the NIST fixture, carried raw; interpretation's time comes with a consumer that needs reference sets |
| LSG Attribute Accumulation Semantics (p.207) | partial | — | SceneReadTest (replacement, ignore flag, force/final note), TransformProbeTest, SceneFixtureTest (world bounds) | transforms multiply, materials replace, Ignore flag honored; force/final/field-inhibit produce a named note instead of a guess — both fixtures use plain accumulation (stateFlags 8 everywhere) |
| LSG Part Structure (p.208) | done | — | SceneReadTest.partConventionCollapsesToNamedPartsUnderTheRoot, SceneFixtureTest, SceneNistFixtureTest | the Figure 160 convention collapses to one named part node with per-tier meshes |
| Range LOD Node Alternative Rep Selection (p.208) | partial | — | SceneReadTest (LodPolicy tests), SceneFixtureTest (descending tiers) | tier order (finest first) interpreted by LodPolicy; the eye-distance selection strategy is viewer runtime behavior, not a file property |
| B-Rep Face Group Associations (p.208) | — | — |  | face groups are preserved at Layer 1 (TriStripGeometry.Triangle.faceGroup); the association semantics wait for B-rep interpretation, which is out of scope by doctrine |
| Watermark Image (p.209) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 156 — Assembly node with SUBNODE (p.203) | — | — |  | with the SUBNODE row above |
| Fig. 157 — Assembly node without SUBNODE (p.204) | — | — |  | as above |
| Fig. 158 — Displaying Nodes that have SUBNODE properties (p.204) | — | — |  | as above |
| Fig. 159 — CAD Component with Reference sets (p.205) | — | — |  | as above |
| Fig. 160 — JT Format Convention for Modeling each Part in LSG (p.208) | done | — | SceneReadTest.partConventionCollapsesToNamedPartsUnderTheRoot, SceneNistFixtureTest | Part → Range LOD → per-tier Group → Shape, verified on both fixtures; the collapse folds it into one named part node |


## Annex A — Object Type Identifiers

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Object Type Identifier table (GUID → element type) (p.211) | done | done | LsgPropertyCodecTest.annexAResolvesLsgTypes, .annexACoversAllLsgCodecs | full table in ObjectTypeIds (all segment kinds); LSG codec dispatch + inventory naming; Vector4f atom GUID added from §6.2 (missing from Table A.1) |


## Annex B — Coding Algorithms: An Implementation

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Sample bit-length / arithmetic decoder source (p.215) | done | n/a: reference source, decode side only | Int32CdpTest, Int32CdpV10Test, VertexArrayTest | arithmetic decoder, predictor unpacking, Deering unpackCode/conversion and the nibbler bitlength implemented per the reference and fixture-verified (issue #6); the JT 9 bitlength wire differs from the annex (DESIGN.md delta 17) |


## Annex C — Hashing: An Implementation

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Hash function implementation (p.239) | done | done | JtHashTest, FixtureDiscoveryTest | hash32 + hash16 (Jenkins lookup2); all 153 stored shape hashes verified at decode (36 across the 9.5 fixture's 12 bodies, 117 across the NIST fixture's 39) |


## Annex D — Polygon Mesh Topology Coder

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Polygon mesh topology coder (p.242) | done | n/a: writer's time comes with the authoring milestone | ShapeLodDocumentTest (both tetrahedra), FixtureDiscoveryTest | decoder (dual VFMesh reconstruction) shared by both generations; fixture-verified: all 12 v9 and 24 v10 tri-strip bodies decode complete meshes consistent with the LSG-declared ranges |


## Annex E — (deprecated) JT B-Rep Segment

*Prefilled:* **opaque by doctrine (issue #1 rule 3): carried losslessly, never interpreted**

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| E.1.1 Topological Entity Counts (p.263) | opaque | opaque |  |  |
| E.1.2 Geometric Entity Counts (p.264) | opaque | opaque |  |  |
| E.1.3 Topology Data (p.265) | opaque | opaque |  |  |
| E.1.4 Geometric Data (p.274) | opaque | opaque |  |  |
| E.1.5 Topological Entity Tag Counters (p.283) | opaque | opaque |  |  |
| E.1.6 B-Rep CAD Tag Data (p.284) | opaque | opaque |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 162 — JT B-Rep Element data collection (p.262) | opaque | opaque |  |  |
| Fig. 163 — Topological Entity Counts data collection (p.263) | opaque | opaque |  |  |
| Fig. 164 — Geometric Entity Counts data collection (p.264) | opaque | opaque |  |  |
| Fig. 165 — Topology Data collection (p.265) | opaque | opaque |  |  |
| Fig. 166 — Regions Topology Data collection (p.266) | opaque | opaque |  |  |
| Fig. 167 — Shells Topology Data collection (p.267) | opaque | opaque |  |  |
| Fig. 168 — Trim Loop example in parameter Space - One Face with 2 Holes (p.268) | opaque | opaque |  |  |
| Fig. 169 — Faces Topology Data collection (p.268) | opaque | opaque |  |  |
| Fig. 170 — Loops Topology Data collection (p.270) | opaque | opaque |  |  |
| Fig. 171 — CoEdges Topology Data collection (p.271) | opaque | opaque |  |  |
| Fig. 172 — Edges Topology Data collection (p.272) | opaque | opaque |  |  |
| Fig. 173 — Vertices Topology Data collection (p.273) | opaque | opaque |  |  |
| Fig. 174 — Geometric Data collection (p.274) | opaque | opaque |  |  |
| Fig. 175 — Surfaces Geometric Data collection (p.275) | opaque | opaque |  |  |
| Fig. 176 — Non-Trivial Knot Vector NURBS Surface Indices data collection (p.276) | opaque | opaque |  |  |
| Fig. 177 — NURBS Surface Degree data collection (p.277) | opaque | opaque |  |  |
| Fig. 178 — NURBS Surface Control Point Counts data collection (p.277) | opaque | opaque |  |  |
| Fig. 179 — NURBS Surface Control Point Weights data collection (p.278) | opaque | opaque |  |  |
| Fig. 180 — NURBS Surface Control Points data collection (p.278) | opaque | opaque |  |  |
| Fig. 181 — NURBS Surface Knot Vectors data collection (p.278) | opaque | opaque |  |  |
| Fig. 182 — PCS Curves Geometric Data collection (p.279) | opaque | opaque |  |  |
| Fig. 183 — Trivial PCS Curves data collection (p.280) | opaque | opaque |  |  |
| Fig. 185 — MCS Curves Geometric Data collection (p.282) | opaque | opaque |  |  |
| Fig. 186 — Point Geometric Data collection (p.283) | opaque | opaque |  |  |
| Fig. 187 — Topological Entity Tag Counters data collection (p.283) | opaque | opaque |  |  |
| Fig. 188 — B-Rep CAD Tag Data collection (p.284) | opaque | opaque |  |  |


## Annex F — XT B-Rep data segment

*Prefilled:* **opaque by doctrine (issue #1 rule 3): carried losslessly, never interpreted**

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| XT B-Rep Element (p.285) | opaque | opaque |  |  |
| F.1.1 XT B-Rep Data (p.286) | opaque | opaque |  |  |
| F.1.2 Integer Attribute Data (p.286) | opaque | opaque |  |  |
| F.1.3 MultiXT B-Rep Segment (p.287) | opaque | opaque |  |  |
| XT B-Rep Data Segment Description (p.289) | opaque | opaque |  |  |
| F.2.1 Logical Layout (p.289) | opaque | opaque |  |  |
| F.2.2 Physical Layout (p.293) | opaque | opaque |  |  |
| F.2.3 Model Structure (p.294) | opaque | opaque |  |  |
| F.2.4 Schema Definition (p.300) | opaque | opaque |  |  |
| F.2.5 Node Types (p.357) | opaque | opaque |  |  |
| F.2.6 Node Classes (p.358) | opaque | opaque |  |  |
| F.2.7 System Attribute Definitions (p.359) | opaque | opaque |  |  |
| XT Moniker Attributes (p.365) | opaque | opaque |  |  |
| F.3.1 Moniker IDs (p.366) | opaque | opaque |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 189 — XT B-Rep Element data collection (p.285) | opaque | opaque |  |  |
| Fig. 190 — Integer Attribute Data collection (p.287) | opaque | opaque |  |  |
| Fig. 191 — MultiXT B-Rep Element data collection (p.288) | opaque | opaque |  |  |
| Fig. 192 — Split a face (p.367) | opaque | opaque |  |  |
| Fig. 193 — Merge faces (p.368) | opaque | opaque |  |  |


## Annex G — JT ULP Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| JT ULP Element (p.370) | — | — |  |  |
| G.1.1 Topology Data (p.372) | — | — |  |  |
| G.1.2 Geometric Data (p.389) | — | — |  |  |
| G.1.3 Material Attribute Element Properties (p.413) | — | — |  |  |
| G.1.4 Information Recovery (p.414) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 194 — JT ULP Segment data collection (p.370) | — | — |  |  |
| Fig. 195 — JT ULP Element data collection (p.371) | — | — |  |  |
| Fig. 196 — Topology Data collection (p.372) | — | — |  |  |
| Fig. 197 — Topological Entity Counts data collection (p.373) | — | — |  |  |
| Fig. 198 — Combined Predictor Type data collection (p.374) | — | — |  |  |
| Fig. 199 — Regions Topology Data collection (p.375) | — | — |  |  |
| Fig. 200 — Shells Topology Data collection (p.376) | — | — |  |  |
| Fig. 201 — Faces Topology Data collection (p.377) | — | — |  |  |
| Fig. 202 — Loops Topology Data collection (p.380) | — | — |  |  |
| Fig. 203 — CoEdges Topology Data collection (p.382) | — | — |  |  |
| Fig. 204 — Sample Model with Randomly Assigned Edge Indices (p.383) | — | — |  |  |
| Fig. 205 — Sample Model with Sequentially Assigned Edge Indices (p.383) | — | — |  |  |
| Fig. 206 — Surface Domain Classification (p.385) | — | — |  |  |
| Fig. 207 — Edges Topology Data collection (p.387) | — | — |  |  |
| Fig. 208 — Geometric Data collection (p.389) | — | — |  |  |
| Fig. 209 — Geometric Entity Counts (p.390) | — | — |  |  |
| Fig. 210 — Degree Table data collection (p.391) | — | — |  |  |
| Fig. 211 — Recover Nurbs Degree (p.392) | — | — |  |  |
| Fig. 212 — Number of Control Points Table data collection (p.393) | — | — |  |  |
| Fig. 213 — Recover Number of Control Points (p.394) | — | — |  |  |
| Fig. 214 — Dimension Table data collection (p.395) | — | — |  |  |
| Fig. 215 — Recover Dimension (p.396) | — | — |  |  |
| Fig. 216 — 3D Unit Vector Table data collection (p.397) | — | — |  |  |
| Fig. 217 — Recover Dimension (p.398) | — | — |  |  |
| Fig. 218 — 2D Unit Vector Table data collection (p.399) | — | — |  |  |
| Fig. 219 — Recover 2D Unit Vector (p.399) | — | — |  |  |
| Fig. 220 — 3D MCS Point Table data collection (p.400) | — | — |  |  |
| Fig. 221 — Recover 3D MCS Points (p.402) | — | — |  |  |
| Fig. 222 — Knot Vector Table data collection (p.403) | — | — |  |  |
| Fig. 223 — Recover Knot Vectors (p.404) | — | — |  |  |
| Fig. 224 — 1D MCS Table data collection (p.406) | — | — |  |  |
| Fig. 225 — Recover 1D MCS Table (p.408) | — | — |  |  |
| Fig. 226 — PCS Value Table data collection (p.409) | — | — |  |  |
| Fig. 227 — Recover PCS Value Table (p.410) | — | — |  |  |
| Fig. 228 — Radian Table data collection (p.411) | — | — |  |  |
| Fig. 229 — Recover Radian Table (p.411) | — | — |  |  |
| Fig. 231 — Recover Weight Table (p.413) | — | — |  |  |
| Fig. 232 — Material Attribute Element Properties (p.414) | — | — |  |  |
| Fig. 233 — Information Recovery (p.415) | — | — |  |  |
| Fig. 234 — PCS Curve Recovery from Surface Domain (p.416) | — | — |  |  |
| Fig. 235 — MCS Curve Recovery (p.417) | — | — |  |  |
| Fig. 236 — MCS Curve Recovery from Surface Geometry (p.418) | — | — |  |  |


## Annex H — (deprecated) PMI Data Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| (deprecated) PMI data segment (p.419) | — | — |  |  |


## Annex I — Procedural Geometry: Evaluation and Approximation

*Prefilled:* **n/a: procedural-geometry evaluation math — needed only if Annex-I surfaces are tessellated by us; revisit if a real file requires it**

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Introduction & Scope (p.421) | n/a | n/a |  |  |
| Notation (p.421) | n/a | n/a |  |  |
| Pseudocode (p.421) | n/a | n/a |  |  |
| Intersection Curve (p.421) | n/a | n/a |  |  |
| Intersection Curve Basics (p.421) | n/a | n/a |  |  |
| Populating Chart Points (p.423) | n/a | n/a |  |  |
| Computing a Point & Tangent on an Intersection Curve (p.429) | n/a | n/a |  |  |
| Approximating an Intersection Curve (p.431) | n/a | n/a |  |  |
| Rolling-Ball Blend Surface (p.440) | n/a | n/a |  |  |
| Computing a Point on a Blend Surface (p.440) | n/a | n/a |  |  |
| Approximating a Blend Surface (p.445) | n/a | n/a |  |  |
| Blend Surface Questions and Answers (p.450) | n/a | n/a |  |  |
| Annex Bibliography (p.453) | n/a | n/a |  |  |


## Annex J — PMI Properties

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Bibliography (p.562) | — | — |  |  |

