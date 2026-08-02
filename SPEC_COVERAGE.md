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
5. The Write column's `done` means the layout is **serialized** — which for most figures started
   out as byte-faithful re-serialization of a decoded model. Where the Layer 2 writer
   (`writeJt`, issue #8) additionally **authors** a figure from a scene, with no decoded original
   to project, the Notes column says so explicitly (*"Write: authored by `writeJt`"*). Figures the
   writer never emits keep their re-serialization status — that distinction is the point of the
   note.


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
| File Format (p.18) | done | done | SyntheticFileRoundTripTest, FixtureDiscoveryTest, Layer0ProbeTest, WriteJtTest | chapter row; Write = byte-faithful re-serialization *and* authoring: `writeJt` mints header, segments and TOC from a scene (issue #8) |
| File Structure (p.18) | done | done | SyntheticFileRoundTripTest | region model: segments + TOC + preserved gaps cover the file | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| File Header (p.18) | done | done | FileHeaderTest | v9 delta: I32 TOC offset, no trailing GUID (DESIGN.md, fixture-verified) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| TOC Segment (p.20) | done | done | TocTest | v9 delta: 28-byte entries vs v10 32-byte (DESIGN.md) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Data Segment (p.21) | done | done | SyntheticFileRoundTripTest, HostileInputTest | hostile variants produce named notes, stay byte-faithful | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Data Segments (p.26) | partial | partial | ElementScanTest, LsgDocumentTest, ShapeLodDocumentTest, WireframeDocumentTest, LwpaDocumentTest, BrepOpacityTest, UndefinedSegmentTypeTest | framing scanned everywhere; LSG element bodies decoded (§6, issue #3; v10.5 cross-producer via issue #5); shape LOD bodies decoded in both generations (§7 — JT 9 via issue #4, v10 via issue #6); meta data / PMI bodies decoded (§11, issue #9); wireframe bodies decoded and LWPA spec-derived (§10/§9, issue #10). **Every remaining segment family now has an honest final fate**: B-rep is `opaque` by doctrine with a proof (BrepOpacityTest), and the segment types Table 6 does not define (NX 10.5 writes 23 and 31) are named, decompressed, element-framed and preserved verbatim (UndefinedSegmentTypeTest). `partial` remains only because the *undefined* types' element bodies are deliberately not interpreted |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 10 — JT File Structure (p.18) | done | done | SyntheticFileRoundTripTest | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 11 — File Header data collection (p.19) | done | done | FileHeaderTest | v9: I32 TOC offset @85; verified against real 9.5 fixture | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 12 — TOC Segment data collection (p.20) | done | done | TocTest | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 13 — TOC Entry data collection (p.21) | done | done | TocTest | v9 28-byte / v10 32-byte entry | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 14 — Data Segment data collection (p.22) | done | done | SyntheticFileRoundTripTest | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 15 — Segment Header data collection (p.22) | done | done | SyntheticFileRoundTripTest, HostileInputTest | id/type/length mismatches → named notes | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 16 — Data collection (p.24) | done | done | ZlibTest, SyntheticFileRoundTripTest | ZLIB (v9) and LZMA flag 3/algorithm 3 (v10.5 NIST, 59 segments) fixture-verified; flag∉{2,3} layout spec-derived, unverified against a real file — note fallback in place | **Write: authored by `writeJt`** — Table 8/9 leave only LZMA and "none" in v10, so the writer stores plainly (flag 0, algorithm 1); WriteJtTest.writtenLsgSegmentIsStoredNotCompressed.
| Fig. 17 — Logical Element Header data collection (p.24) | done | done | ElementScanTest | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 18 — Element Header data collection (p.24) | done | done | ElementScanTest | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 19 — Logical Element Header Compressed data collection (p.25) | done | done | ElementScanTest, FixtureDiscoveryTest | scanned in inflated LSG of the real fixture (67 elements) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).


## §6 LSG Segment

All §6 rows below share the version caveat established in DESIGN.md: v10 layouts per the
reference, v9 layouts only where fixture-verified; the non-material attribute elements are
opaque-with-note (`ELEMENT_LAYOUT_UNVERIFIED`) in v9 files. Since issue #5 a third
generation exists: **JT 10.5** deviates from the v10.0 reference in deltas 23–26
(DESIGN.md), established against the NIST fixture's LSG — 1 211 elements, all typed,
stream round-trip byte-identical, cross-producer coherence probes green (LsgProbeTest). Write = re-serialization of the
typed model, byte-identical to the decoded stream — plus, since issue #8, **authoring**: the
element types marked below are constructed from a Layer 2 scene by `writeJt` (partition, group,
instance, part, range LOD, tri-strip and polyline shape nodes, material and geometric transform
attributes, string and late-loaded property atoms, the property table).

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| LSG Segment (p.28) | done | done | LsgDocumentTest, LsgSyntheticFileTest, FixtureDiscoveryTest | v9 fixture: 66 graph elements + 41 atoms + 40-entry property table; NIST 10.5: 267 graph elements + 944 atoms + 135-entry property table — all typed in both, stream round-trip byte-identical | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Graph Elements (p.28) | done | done | LsgNodeElementCodecTest, LsgAttributeElementCodecTest | unknown/undecodable elements → opaque + named note (LsgDocumentTest) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Node Elements (p.29) | done | done | LsgNodeElementCodecTest | v9 deltas 6–9 in DESIGN.md, fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Attribute Elements (p.49) | done | done | LsgAttributeElementCodecTest, Lsg105GenerationTest | v10 complete; 10.5 trailing I32 on the family (delta 24 — fixture-verified for material/transform/linestyle, family-rule-derived for the rest); v9: material only — others opaque-by-policy with note | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Property Atom Elements (p.83) | done | done | LsgPropertyCodecTest | both generations | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Base Property Atom Element (p.83) | done | done | LsgPropertyCodecTest.basePropertyAtomElement | |
| String Property Atom Element (p.84) | done | done | LsgPropertyCodecTest.stringPropertyAtomElement | fixture-verified (28 in the real file) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Integer Property Atom Element (p.84) | done | done | LsgPropertyCodecTest.integerPropertyAtomElement | fixture-verified (NIST 10.5: 17 atoms) |
| Floating Point Property Atom Element (p.85) | done | done | LsgPropertyCodecTest.floatingPointPropertyAtomElement | fixture-verified (NIST 10.5: 13 atoms) |
| JT Object Reference Property Atom Element (p.86) | done | done | LsgPropertyCodecTest.jtObjectReferencePropertyAtomElement | spec-derived, not yet fixture-verified; base type 6 (Table 7) |
| Date Property Atom Element (p.86) | done | done | LsgPropertyCodecTest.datePropertyAtomElement | fixture-verified both generations; 10.5 appends an undocumented F32 (delta 26) |
| Late Loaded Property Atom Element (p.88) | done | done | LsgPropertyCodecTest.lateLoadedPropertyAtomElement | fixture-verified (v9: 12, NIST 10.5: 105 references); 10.5 drops the Reserved I32 (delta 25) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Vector4f Property Atom Element (p.89) | done | done | LsgPropertyCodecTest.vector4fPropertyAtomElement | GUID missing from Table A.1 (spec inconsistency, recorded in ObjectTypeIds) |
| Property Table (p.90) | done | done | LsgDocumentTest, FixtureDiscoveryTest | fixture: 40 element tables, zero leftover bytes | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Element Property Table (p.91) | done | done | LsgDocumentTest | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 20 — LSG Segment data collection (p.28) | done | done | LsgDocumentTest.wellFormedDocumentDecodesAndRoundTrips | figure's 2nd list box garbled in the PDF; fixture confirms property atoms | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 21 — Base Node Element data collection (p.29) | done | done | LsgNodeElementCodecTest.baseNodeElement | spec-derived, not yet fixture-verified |
| Fig. 22 — Base Node Data collection (p.29) | done | done | LsgNodeElementCodecTest.baseNodeElement | fixture-verified (all 66 graph elements) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 23 — Partition Node Element data collection (p.31) | done | done | LsgNodeElementCodecTest.partitionNodeElement, Lsg105GenerationTest.partitionBitZeroWithoutStoredBoxDecodes | fixture-verified incl. flag-bit-0 conditional box; 10.5: inserted version byte, bit 0 set without the box (delta 23) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 24 — Vertex Count Range data collection (p.32) | done | done | LsgNodeElementCodecTest.partitionNodeElement | | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 25 — Group Node Element data collection (p.33) | done | done | LsgNodeElementCodecTest.groupNodeElement | fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 26 — Group Node Data collection (p.34) | done | done | LsgNodeElementCodecTest.groupNodeElement | fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 27 — Instance Node Element data collection (p.35) | done | done | LsgNodeElementCodecTest.instanceNodeElement | fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 28 — Part Node Element data collection (p.35) | done | done | LsgNodeElementCodecTest.partNodeElement | fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 29 — Meta Data Node Element data collection (p.36) | done | done | LsgNodeElementCodecTest.metaDataNodeElement | fixture-verified |
| Fig. 30 — Meta Data Node Data collection (p.36) | done | done | LsgNodeElementCodecTest.metaDataNodeElement | fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 31 — LOD Node Element data collection (p.37) | done | done | LsgNodeElementCodecTest.lodNodeElement | spec-derived; v9 reserved fields fixture-verified via Range LOD |
| Fig. 32 — LOD Node Data collection (p.37) | done | done | LsgNodeElementCodecTest.lodNodeElement | v9 delta 7 in DESIGN.md | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 33 — Range LOD Node Element data collection (p.38) | done | done | LsgNodeElementCodecTest.rangeLodNodeElement | fixture-verified (12 in the real file) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 34 — Switch Node Element data collection (p.39) | done | done | LsgNodeElementCodecTest.switchNodeElement | spec-derived, not yet fixture-verified |
| Fig. 35 — Base Shape Node Element data collection (p.40) | done | done | LsgNodeElementCodecTest.baseShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 36 — Base Shape Data collection (p.40) | done | done | LsgNodeElementCodecTest.baseShapeNodeElement | fixture-verified via tri-strip nodes; v9 delta 8 | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 38 — Vertex Shape Node Element data collection (p.42) | done | done | LsgNodeElementCodecTest.vertexShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 39 — Vertex Shape Data collection (p.43) | done | done | LsgNodeElementCodecTest.vertexShapeNodeElement, .triStripSetShapeNodeElement | fixture-verified by consumption; v9 delta 9 and its evidence limit | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 40 — Polyline Set Shape Node Element data collection (p.44) | done | done | LsgNodeElementCodecTest.polylineSetShapeNodeElement | fixture-verified (NIST 10.5: 15 nodes) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 41 — Point Set Shape Node Element data collection (p.45) | done | done | LsgNodeElementCodecTest.pointSetShapeNodeElement | spec-derived; the version==1 conditional binding treated as v10-only |
| Fig. 42 — Polygon Set Shape Node Element data collection (p.46) | done | done | LsgNodeElementCodecTest.polygonSetShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 43 — NULL Shape Node Element data collection (p.46) | done | done | LsgNodeElementCodecTest.nullShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 44 — Primitive Set Shape Node Element data collection (p.47) | done | done | LsgNodeElementCodecTest.primitiveSetShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 45 — Primitive Set Quantization Parameters data collection (p.48) | done | done | LsgNodeElementCodecTest.primitiveSetShapeNodeElement | spec-derived, not yet fixture-verified |
| Fig. 46 — Base Attribute Data collection (p.49) | done | done | LsgAttributeElementCodecTest.materialAttributeElement, .materialAttributeElementV9, Lsg105GenerationTest | v9 delta 10: no field-final flags, fixture-verified; 10.5: attribute elements gain a trailing I32 (delta 24, NIST: 88 elements) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 47 — Material Attribute Element data collection (p.51) | done | done | LsgAttributeElementCodecTest.materialAttributeElement, .materialAttributeElementV9, Lsg105GenerationTest.materialAttributeCarriesTheTrailingField | fixture-verified all three generations (NIST 10.5: 37 elements); v9 delta 11, 10.5 delta 24 | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
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
| Fig. 63 — Geometric Transform Attribute Element data collection (p.76) | done | done | LsgAttributeElementCodecTest.geometricTransformAttributeElement, Lsg105GenerationTest.geometricTransformCarriesTheTrailingField | sparse mask-driven storage, full matrix in the model; v10.5 fixture-verified (NIST: 36 elements, delta 24) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 64 — Texture Coordinate Generator Attribute Element data collection (p.78) | done | done | LsgAttributeElementCodecTest.textureCoordinateGeneratorWithMappingPlane | nested mapping-surface element; alien surface stays lossless (LsgDocumentTest) |
| Fig. 65 — Mapping Plane Element data collection (p.79) | done | done | LsgAttributeElementCodecTest.textureCoordinateGeneratorWithMappingPlane | v10 only; spec-derived, not yet fixture-verified |
| Fig. 66 — Mapping Cylinder Element data collection (p.80) | done | done | LsgAttributeElementCodecTest.mappingCylinderElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 67 — Mapping Sphere Element data collection (p.81) | done | done | LsgAttributeElementCodecTest.mappingSphereElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 68 — Mapping TriPlanar Element data collection (p.82) | done | done | LsgAttributeElementCodecTest.mappingTriPlanarElement | v10 only; spec-derived, not yet fixture-verified |
| Fig. 69 — Base Property Atom Element data collection (p.83) | done | done | LsgPropertyCodecTest.basePropertyAtomElement | spec-derived, not yet fixture-verified |
| Fig. 70 — Base Property Atom Data collection (p.83) | done | done | LsgPropertyCodecTest.basePropertyAtomElement | fixture-verified (all 41 atoms) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 71 — String Property Atom Element data collection (p.84) | done | done | LsgPropertyCodecTest.stringPropertyAtomElement | fixture-verified (v9: 28, NIST 10.5: 796) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 72 — Integer Property Atom Element data collection (p.85) | done | done | LsgPropertyCodecTest.integerPropertyAtomElement | fixture-verified (NIST 10.5) |
| Fig. 73 — Floating Point Property Atom Element data collection (p.85) | done | done | LsgPropertyCodecTest.floatingPointPropertyAtomElement | fixture-verified (NIST 10.5) |
| Fig. 74 — JT Object Reference Property Atom Element data collection (p.86) | done | done | LsgPropertyCodecTest.jtObjectReferencePropertyAtomElement | spec-derived, not yet fixture-verified |
| Fig. 75 — Date Property Atom Element data collection (p.87) | done | done | LsgPropertyCodecTest.datePropertyAtomElement | fixture-verified; 10.5 delta 26 (trailing F32, NIST: −4.0 ≙ the timestamps' UTC offset) |
| Fig. 76 — Late Loaded Property Atom Element data collection (p.88) | done | done | LsgPropertyCodecTest.lateLoadedPropertyAtomElement | fixture-verified; 10.5 drops the "always ≥ 1" Reserved I32 (delta 25) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 77 — Vector4f Property Atom Element data collection (p.89) | done | done | LsgPropertyCodecTest.vector4fPropertyAtomElement | spec-derived, not yet fixture-verified |
| Fig. 78 — Property Table data collection (p.90) | done | done | LsgDocumentTest.wellFormedDocumentDecodesAndRoundTrips | fixture-verified (v9: 40 tables, NIST 10.5: 135); also identifies the shape segments' 6-byte tail (DESIGN.md) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 79 — Element Property Table data collection (p.91) | done | done | LsgDocumentTest.wellFormedDocumentDecodesAndRoundTrips | fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).


## §7 Shape LOD Segment

Both wire generations are implemented and fixture-verified where a fixture exists: the
**JT 9 generation** against the 9.5 file's 12 tri-strip bodies (issue #4) and the **v10
generation** against the NIST 10.5 file's 39 bodies — 24 tri-strip + 15 polyline, all 117
stored hashes verified at decode (issue #6, DESIGN.md deltas 27–31). Types without any
fixture (point/polygon/primitive set; polyline in JT 9) stay opaque-with-note — never
guessed. Write = byte-identical re-serialization of the typed model — plus, since issue #8,
**authoring** of the v10 tri-strip and polyline bodies from a Layer 2 scene: the null CODEC for
every packet, lossless binary-float coordinates and normals, valid stored hashes, and a topology
of one closed component per triangle (§7.1.4.1.3.1's cover-face mechanism — DESIGN.md).

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Shape LOD Segment (p.92) | done | done | ShapeLodDocumentTest, FixtureDiscoveryTest | all 51 fixture bodies (12 v9 + 39 v10) typed + byte-identical round-trip; unfixtured element types opaque-with-note | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Shape LOD Element (p.92) | done | done | ShapeLodDocumentTest | element framing + strict-or-opaque dispatch, both generations | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Tri-Strip Set Shape LOD Element (p.92) | done | done | ShapeLodDocumentTest.triStripSetElementDecodesTheTetrahedron, .v10TriStripSetElementDecodesTheTetrahedron, FixtureDiscoveryTest | both generations fixture-verified incl. decoded triangles/normals (v9 delta 14; v10 delta 27) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Polyline Set Shape LOD Element (p.93) | done | done | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, ShapeLod95PolylinePointTest.polylineSetShapeLodDecodesTheSquareOutline, .theIndexListsAreNullPredictedNotLag1, ShapeLod95FixtureTest | both generations fixture-verified: v10 on 15 NIST bodies, JT 9 on the five 9.5 bodies (issue #12) — 9.5 Fig. 91's index lists are NULL-predicted where v10's are Lag1 | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Point Set Shape LOD Element (p.93) | partial | partial | ShapeLod95PolylinePointTest.pointSetShapeLodDecodesWithoutAFaceGroupSection, .thePointSetFgpvHashOmitsTheFaceGroupTerm, ShapeLod95FixtureTest, ShapeLodDocumentTest.unestablishedV10ShapeTypesStayOpaqueWithNote | JT 9 fixture-verified (9.5 Fig. 95; `if Polyline Shape` puts no face-group section on the wire and no face-group term in the FGPV hash); v10 still opaque-with-note — no v10 fixture carries one |
| Polygon Set LOD Element (p.94) | opaque | opaque | ShapeLodDocumentTest (opaque paths) | as above |
| Null Shape LOD Element (p.107) | partial | partial | ShapeLodDocumentTest.nullShapeLodElementDecodesInV9 | both generations spec-derived (I16 vs U8 version), not yet fixture-verified — neither fixture carries one |
| Primitive Set Shape Element (p.107) | opaque | opaque | ShapeLodDocumentTest (opaque paths) | no fixture carries one; named note |
| Lossless Compressed Primitive Set Data (p.109) | opaque | opaque |  | inside the opaque Primitive Set body |
| Lossy Quantized Primitive Set Data (p.111) | opaque | opaque |  | inside the opaque Primitive Set body |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 81 — Tri-Strip Set Shape LOD Element data collection (p.92) | done | done | ShapeLodDocumentTest.triStripSetElementDecodesTheTetrahedron, .v10TriStripSetElementDecodesTheTetrahedron, FixtureDiscoveryTest | v9 layout delta 14 (reserved 12-byte tail); v10 layout incl. the nested element header (delta 27) — both fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 82 — Polyline Set Shape LOD Element data collection (p.93) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified; JT 9 opaque-with-note (no fixture) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 83 — Point Set Shape LOD Element data collection (p.94) | opaque | opaque | ShapeLodDocumentTest.unestablishedV10ShapeTypesStayOpaqueWithNote | see section row |
| Fig. 84 — Polygon Set LOD Element data collection (p.94) | opaque | opaque |  | see section row |
| Fig. 85 — Vertex Shape LOD Data collection (p.95) | done | done | ShapeLodDocumentTest (both tetrahedra), FixtureDiscoveryTest | v9: I16 version + U64 bindings; v10: I8 version + U64 bindings + the nested Logical Element Header whose type GUIDs are absent from Annex A (delta 27) — both fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 86 — Base Shape LOD Data collection (p.97) | done | done | ShapeLodDocumentTest (both tetrahedra) | I16 version in v9, I8 in v10 — both fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 87 — TopoMesh Compressed LOD Data collection (p.97) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified (the polyline container); JT 9 with the polyline element | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 88 — TopoMesh LOD Data collection (p.98) | done | done | ShapeLodDocumentTest (both tetrahedra) | v9: I16 version + I32 object id; v10: U8 + U32 — both fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 89 — TopoMesh Compressed Rep Data data collection (p.99) | partial | partial | ShapeLodDocumentTest.v10PolylineSetElementDecodesTheSquareOutline, FixtureDiscoveryTest | v10 fixture-verified: FGPV + unique-length hashes validated, index lists carry count+1 terminators; JT 9 with the polyline element | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 90 — Quantization Parameters data collection (p.101) | done | done | ShapeLodDocumentTest, FixtureDiscoveryTest | 4×U8, identical in both generations; delta 21 (v9 normal bits factor is not authoritative); in v10 factor == the packed Deering per-angle bits in all 39 bodies (DESIGN.md) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 91 — TopoMesh Topologically Compressed LOD Data collection (p.102) | done | done | ShapeLodDocumentTest (both tetrahedra) | I16 version in v9, U8 in v10 — both fixture-verified | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 92 — Topologically Compressed Rep Data Collection (p.103) | done | done | ShapeLodDocumentTest (tetrahedra + corrupt-stream refusals), FixtureDiscoveryTest | both generations fixture-verified incl. composite-hash validation and the Annex-D topology decode; the 8th mask context is 30/30/4 in v9 (delta 20) and 32/32 in v10 (figure-accurate) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 93 — Topologically Compressed Vertex Records data collection (p.106) | done | done | ShapeLodDocumentTest (both tetrahedra), FixtureDiscoveryTest | both generations fixture-verified incl. v10 per-vertex flag arrays; colours/texcoords/aux bindings refuse with note (no fixture) | **Write: authored by `writeJt`** (WriteJtTest, WriteFixtureRewriteTest).
| Fig. 94 — Null Shape LOD Element data collection (p.107) | partial | partial | ShapeLodDocumentTest.nullShapeLodElementDecodesInV9 | both generations spec-derived, not yet fixture-verified |
| Fig. 96 — Lossless Compressed Primitive Set Data collection (p.109) | opaque | opaque |  | inside the opaque Primitive Set body |
| Fig. 97 — Lossy Quantized Primitive Set Data collection (p.111) | opaque | opaque |  | inside the opaque Primitive Set body |
| Fig. 98 — Compressed params1 data collection (p.113) | opaque | opaque |  | inside the opaque Primitive Set body |


## §8 Precise Geometry Segment

**`opaque` here is a doctrine, not a gap** — issue #1's third design rule: *"B-rep (JT B-rep / XT)
is preserved opaquely, never interpreted. Parasolid's representation is its own world; this
library's honesty is the tessellation, the structure tree, the properties and the PMI."* Since
issue #10 that is a *proven* property rather than a claim: `BrepOpacityTest` enumerates every
precise-geometry segment of every fixture, records its element type GUID, and asserts that the
payload survives parse → whole-file re-encode byte-identically — 9 segments and 583 617 payload
bytes on the NIST 10.5 file, 8 of them carrying a Parasolid `TRANSMIT FILE` container. Opaque
means *carried*, never skipped; the rows below say `opaque`, never `n/a`.

The Write column is `n/a` throughout: `writeJt` authors no precise geometry (a file without it is
legal). Re-serialization of a *read* file is byte-faithful at Layer 0 and covered by the same test.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Precise Geometry Segment (p.115) | opaque | n/a: `writeJt` authors no precise geometry | BrepOpacityTest, FixtureDiscoveryTest | chapter row. Every §8 segment kind is enumerated, decompressed, element-framed and preserved verbatim; none is interpreted. Its time comes only if issue #1 rule 3 is reversed — which would be a doctrine change, not a package |
| JT B-Rep Element (deprecated) (p.115) | opaque | n/a: deprecated, "read only for application creation" (§8.1) | BrepOpacityTest (kind covered, skips visibly) | no fixture carries one (Table 6 type 2). Annex E documents it; the doctrine keeps it opaque regardless |
| XT B-Rep Element (p.115) | opaque | n/a: `writeJt` authors no precise geometry | BrepOpacityTest | fixture-exercised: 8 NIST segments, all one `XT B-Rep Element` (Annex A GUID) with Object Base Type 9, all ending in the Figure-78 empty property table; 7 of the 8 payloads contain a Parasolid transmit file |
| MultiXT B-Rep Element (Annex F.1.3) | opaque | n/a: `writeJt` authors no precise geometry | BrepOpacityTest | **row added** (discipline 3: nothing deleted): Table 6 defines segment type 30 and Annex F documents its element, but Annex A lists neither — the GUID is recorded in `ObjectTypeIds.MULTI_XT_BREP_ELEMENT` with that inconsistency noted. One NIST segment, 196 081 payload bytes, referenced by `JT_LLPROP_MULTIXTBREP` |
| JT ULP Segment (p.115) | opaque | n/a: `writeJt` authors no precise geometry | BrepOpacityTest (kind covered, skips visibly) | no fixture carries one (Table 6 type 20). Annex G documents it; opaque by the same doctrine — the honest fate, not a non-goal |
| STEP B-Rep Element (Table 6 type 32) | opaque | n/a: `writeJt` authors no precise geometry | BrepOpacityTest (kind covered, skips visibly) | **row added**: Table 6 and Annex A both define type 32, §8 does not describe it. No fixture carries one; opaque by the same doctrine |


## §9 JT LWPA Segment

**No fixture carries a JT LWPA segment**, so every row below is **spec-derived** — decoded rather
than deferred because Figures 100 and 101 leave nothing to infer: the two `VecI32{Int32CDP}`
vectors have their length fixed by `Analytic Surface Count`, and the four `VecF64` arrays are plain
count-plus-values vectors written "in binary form" (no quantizer, no predictor, no hash). The
element decode is strict and fully consuming, so a producer that contradicts the derivation gets an
opaque carry with a named note (`LWPA_STRUCTURE_UNRECOGNIZED` / `ELEMENT_DECODE_FAILED`), never a
misread.

**The JT 9 generation decodes too, as of package P7.** This paragraph used to say that the v9.5
reference "lists segment type 24 in its Table 3 but documents no LWPA *element* at all". **That was
false.** JT 9.5 Rev-D §7.2.9.1 gives the element its Object Type ID, its prose and **Figure 215**,
and Annex A Table 11 lists it under *Types Stored Within JT LWPA Segment (Segment Type = 24)* with
the same GUID this library already carried. The JT 9 layout is Figure 215 + Figure 216: `I16`
Version Number where v10 has `U8` (the one delta that moves a byte boundary), `I32` counts where
v10 has `U32` (four bytes either way; only the sign of the high bit differs), and the Mk. 2
`Int32CDP2` packet where v10 uses its third-generation `Int32CDP`. Nothing in the element reaches
`Float64CDP` or the Mk. 1 packet — its four F64 arrays are bare `VecF64`. The 9.5 rows live in
`SPEC_COVERAGE_95.md` package G section E.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| JT LWPA Segment (p.115) | done | n/a: `writeJt` authors no LWPA | LwpaDocumentTest | element list + the Figure-78 trailer, same seam as §7/§10/§11; spec-derived, no fixture |
| JT LWPA Element (p.116) | done | n/a: `writeJt` authors no LWPA | LwpaDocumentTest.lwpaElementWithAnalyticSurfacesDecodes, .lwpaElementWithoutAnalyticSurfacesStopsAfterTheCounts | spec-derived; the JT 9 layout decodes too (9.5 Fig. 215 — see `SPEC_COVERAGE_95.md` package G) |
| Analytic Surface Geometry (p.117) | done | n/a: `writeJt` authors no LWPA | LwpaDocumentTest.lwpaElementWithAnalyticSurfacesDecodes, .aSurfaceTypeOutsideTheSupportedTableRefuses | spec-derived; surface types validated against Table 100 (0..5; 6/7 reserved), whose 9.5 twin is value-identical |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 99 — JT LWPA Segment data collection (p.116) | done | n/a: `writeJt` authors no LWPA | LwpaDocumentTest | spec-derived |
| Fig. 100 — JT LWPA Element data collection (p.116) | done | n/a: `writeJt` authors no LWPA | LwpaDocumentTest.lwpaElementWithAnalyticSurfacesDecodes, .moreAnalyticSurfacesThanSurfacesRefuses | spec-derived; the `Analytic Surface Count > 0` conditional is pinned both ways |
| Fig. 101 — Analytic Surface Geometry data collection (p.117) | done | n/a: `writeJt` authors no LWPA | LwpaDocumentTest.lwpaElementWithAnalyticSurfacesDecodes, .anIndexVectorContradictingTheAnalyticCountRefuses | spec-derived; `VecF64` = plain count + values (§4.2 Symbols table) |
| Fig. 102 — Analytic Surface Creation (p.119) | n/a | n/a | | flow chart, not a byte layout: it says how many numbers each surface type *consumes* from the four arrays. Building that projection is a recorded deferral — its time comes with a consumer that needs analytic surfaces (DESIGN.md). 9.5 Figure 217 is the same chart box for box, so the deferral has two citations |


## §10 Wireframe Segment

Read-side complete for the **v10 generation**, fixture-verified against the five Wireframe segments
of the NIST 10.5 file: 197 edges over 197 NURBS curves with 197 CAD tags, all decoding typed with
**zero notes** and byte-identical element-stream round-trip. Every count the wire declares is
cross-validated at decode (edge count vs. both index vectors, three coordinates per control point,
weight count vs. the rational curves' control point sum, and the knot vector length against
Table 68's own formula) — which is what makes the layout established rather than plausible. Deltas
37 and 40 in DESIGN.md record where the bytes correct or complete the 10.0 text.

The **JT 9 generation is opaque-by-policy** (`ELEMENT_LAYOUT_UNVERIFIED`): the v9.5 reference's
Figure 130 is a different wire format (I16 version, Lag1-predicted index vectors, JT 9 "Mk. 2" CDPs
throughout the curve data) and no v9 fixture carries a Wireframe segment. The **Write column is
`n/a` throughout**: `writeJt` authors no wireframe segments (a file without them is legal, and the
Layer 2 scene has no NURBS concept). Re-serialization of a read document *is* byte-identical.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Wireframe Segment (p.120) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest, WireframeFixtureTest | element list + the Figure-78 trailer (empty in all five fixture segments); unterminated streams and unknown element types produce named notes and keep their bytes |
| Wireframe Rep Element (p.120) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | v10 fixture-verified (5 bodies); v9 opaque-with-note. Version Number is U8, not Figure 104's `I16` (delta 40) |
| Wireframe MCS Curves Geometric Data (p.122) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | one Compressed Curve Data collection (Fig. 150); "currently only NURBS Curve types are supported", enforced against Table 69 |
| Wireframe Rep CAD Tag Data (p.122) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.sixtyFourBitCadTagsDecodeFromTheInt64Vector, .undecodableCadTagVectorsAreKeptVerbatimWithANote, WireframeFixtureTest | one Compressed CAD Tag Data collection (Fig. 154), decoded; §10.1.2's "one CAD tag per Edge" validated on all five bodies |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 103 — Wireframe Segment data collection (p.120) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.anUnterminatedElementListKeepsItsRemainderVerbatim, WireframeFixtureTest | **row added** (discipline 3): the mechanical TOC extraction missed it — the reference prints "Figure 103 —Wireframe" without the space after the em dash |
| Fig. 104 — Wireframe Rep Element data collection (p.121) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, .emptyWireframeRepElementSkipsBothConditionalBlocks, .withoutCadTagsFlagNoTagCollectionIsRead, WireframeFixtureTest | fixture-verified incl. both conditional blocks; U8 (not I16) version and NULL (not Lag1) predictors — the latter is Revision B's own correction |
| Fig. 105 — Wireframe MCS Curves Geometric Data collection (p.122) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | a pass-through to Compressed Curve Data (Fig. 150) |
| Fig. 106 — Wireframe Rep CAD Tag Data collection (p.123) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest.sixtyFourBitCadTagsDecodeFromTheInt64Vector, WireframeFixtureTest | a pass-through to Compressed CAD Tag Data (Fig. 154). The generator filed a duplicate of this row under §11 — see the pointer there |


## §11 Meta Data Segment

Read-side complete for the **v10 generation** against the NIST 10.5 fixture's 44 §11 segments —
30 Meta Data (one Property Proxy element each) and 14 PMI Data (one PMI Manager each, Table 6
type 3, which Annex H says to parse identically). All decode typed with byte-identical
round-trip; every layout claim below is fixture-verified unless the Notes column says
otherwise. Deltas 32–36 in DESIGN.md record where the 10.5 bytes contradict or complete the
10.0 text. The **v9 fixture carries no §11 segment at all**, so v9 rests on the v9.5
reference: the Property Proxy element decodes (its Figure 134 is the v10 layout with an I16
version — delta 6), the PMI Manager does not (a different structure; opaque-with-note).

Two figure rows are **added** here that the mechanical TOC extraction missed — *Fig. 108
Property Proxy Meta Data Element* and *Fig. 119 Generic PMI Entity*, both printed without the
space after the em dash that the generator keyed on ("Figure 108 —Property…"). Nothing was
deleted (discipline 3); *Fig. 131 PMI Model View Sort Orders* stays where the generator filed
it, under §12, with a pointer back here.

The **Write column is `—` throughout by design**: `writeJt` authors no meta data segments (a
file without them is legal), so no §11 figure is *authored*. Where a row says `done` the write
side is the byte-identical re-serialization of the decoded model, which is what the round-trip
assertions prove; the authoring deferral and its condition are in DESIGN.md's table.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Meta Data Segment (p.123) | done | done | MetaDataDocumentTest, MetaDataFixtureTest, FixtureDiscoveryTest | chapter row; 44 fixture segments typed, element-stream round-trip byte-identical, each closing with the Figure-78 empty property table. Write: re-serialization only — `writeJt` emits no meta data |
| Property Proxy Meta Data Element (p.123) | done | done | MetaDataDocumentTest.propertyProxyCarriesEveryTable53ValueType, MetaDataFixtureTest | fixture-verified (30 bags, 151 properties, 21 keys); decodes in all three generations (v9 per the v9.5 Figure 134 + delta 6); an unknown Table 53 value type keeps the decoded prefix and the raw remainder with a named note |
| Date Property Value (p.125) | done | done | MetaDataDocumentTest.propertyProxyCarriesEveryTable53ValueType | spec-derived: no fixture bag carries a Date value (the LSG's Date Property Atom does). Modelled as `JtDate`'s six raw I16 fields — `commonMain` stays platform-free |
| PMI Manager Meta Data Element (p.126) | partial | partial | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | everything Figure 110 documents through the font block decodes and is fixture-verified on all 14 managers; the block NX 10.5 writes *after* the fonts is undocumented (delta 33) and carried verbatim with `PMI_MANAGER_TAIL_UNDOCUMENTED`. v9 layout unestablished (v9.5 Figure 136 differs; no fixture) — opaque-with-note |
| PMI Design Group Entities (p.128) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips | spec-derived: all 14 fixture managers declare zero design groups, so only the count is fixture-verified; the group/attribute layout comes from Figures 111/112 and is exercised by the hand-built test |
| PMI Associations (p.130) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified (1 288 associations across the 14 managers); Table 55's packed words exposed as bit fields, Table 55/56 value sets *not* validated — the fixture writes types those tables omit |
| PMI User Attributes (p.133) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips | spec-derived: the fixture declares zero user attributes (count fixture-verified) |
| PMI String Table (p.133) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified; every String ID of every sub-collection is validated against it at decode |
| PMI Model Views (p.134) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified (89 model views, each with its own PMI Property list) |
| Generic PMI Entities (p.139) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified (403 entities: dimensions, notes, sections, reference geometry, part transforms — including populated 2D frames, text entities and non-text polylines) |
| PMI CAD Tag Data (p.149) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | index list fixture-verified and its count validated against the §11.2.7 formula on all 14 managers; the nested Compressed CAD Tag Data is fully decoded since the Int64 CDP landed (issue #10) — all 14 managers' tag vectors decode. The tag *count* is deliberately unconstrained: NX writes more tags than indices in ten of the fourteen (delta 41) |
| PMI Polygon Data (p.150) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified for the manager's own block and for all five fonts' glyph outlines (one PolygonData element per character); colour/texture bindings never occur in the fixture — their conditional arrays are spec-derived |
| PMI Properties (p.153) | opaque | opaque | MetaDataDocumentTest.undocumentedBytesAfterTheFontBlockAreNamedAndPreserved | the *segment-level* property list sits inside the undocumented tail (delta 33) — the PMI Property collection itself is `done` (Fig. 117/118) via model views and generic entities. Its time comes with a fixture whose Property Count is non-zero |
| PMI Model View Sort Orders (p.154) | opaque | opaque | MetaDataDocumentTest.undocumentedBytesAfterTheFontBlockAreNamedAndPreserved | inside the same undocumented tail; all 14 fixture managers would have to declare a non-zero count to pin its position |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 106 — Wireframe Rep CAD Tag Data collection (p.123) | done | n/a: `writeJt` authors no wireframe | WireframeDocumentTest, WireframeFixtureTest | the generator filed this §10 figure under §11; kept in place (discipline 3) with its fate mirrored from the §10 table, where the row now lives properly |
| Fig. 107 — Meta Data Segment data collection (p.123) | done | done | MetaDataDocumentTest (structure + hostile paths), MetaDataFixtureTest | element list + the Figure-78 trailer; unterminated streams and unknown element types produce named notes and keep their bytes |
| Fig. 108 — Property Proxy Meta Data Element data collection (p.124) | done | done | MetaDataDocumentTest.propertyProxyCarriesEveryTable53ValueType, .duplicateBagKeysArePreservedInOrder, .unknownPropertyValueTypeKeepsTheDecodedPrefixAndTheRawRemainder | fixture-verified in all three generations' shared layout; duplicate keys preserved in wire order |
| Fig. 109 — Date Property Value data collection (p.125) | done | done | MetaDataDocumentTest.propertyProxyCarriesEveryTable53ValueType | spec-derived (no fixture bag carries a Date) |
| Fig. 110 — PMI Manager Meta Data Element data collection (p.127) | partial | partial | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, .undocumentedBytesAfterTheFontBlockAreNamedAndPreserved, MetaDataFixtureTest | figure order confirmed against the bytes through the font block; the post-font block is undocumented (delta 33) |
| Fig. 111 — PMI Design Group Entities data collection (p.128) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips | spec-derived beyond the count (fixture declares zero) |
| Fig. 112 — Design Group Attribute data collection (p.129) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips | all three Table 54 value types; spec-derived (no fixture attribute) |
| Fig. 113 — PMI Associations data collection (p.131) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified |
| Fig. 114 — PMI User Attributes data collection (p.133) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips | spec-derived beyond the count (fixture declares zero) |
| Fig. 115 — PMI String Table data collection (p.134) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest, .aStringIdOutsideTheStringTableRefuses | fixture-verified; out-of-range String IDs refuse the typed decode |
| Fig. 116 — PMI Model Views data collection (p.135) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified (89 views) |
| Fig. 117 — PMI Property data collection (p.137) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified (thousands of key/value atoms on views and generic entities) |
| Fig. 118 — Key PMI Property Atom data collection (p.138) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, .aHiddenFlagOutsideTable59Refuses, MetaDataFixtureTest | **delta 32**: the Hidden Flag is one byte in 10.5, not the documented U32; 10.0–10.4 keep the U32. Non-Table-59 values refuse |
| Fig. 119 — Generic PMI Entity data collection (p.139) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified (403 entities); Table 60 entity/parent types carried as read — the fixture writes values the table omits |
| Fig. 120 — PMI 2D Data collection (p.142) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | **delta 35**: the figure's unlabeled last box is Non-Text Polyline Data — fixture-confirmed |
| Fig. 121 — PMI Base Data collection (p.142) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified; 2D-Frame Flag 2 comes with a *populated* frame in the fixture, so the flag is preserved and not interpreted |
| Fig. 122 — 2D-Reference Frame data collection (p.143) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified |
| Fig. 123 — 2D Text Data collection (p.144) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified; Table 62 font values carried as read (the fixture writes −1) |
| Fig. 124 — Text Box data collection (p.145) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified |
| Fig. 125 — Constructing Text Polylines from data arrays (p.146) | n/a | n/a |  | illustrative drawing of Fig. 126's arrays, no byte layout |
| Fig. 126 — Text Polyline Data collection (p.146) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | **delta 36**: the coordinate VecF32 is on the wire even when the index count is 0 |
| Fig. 127 — Constructing Non-Text Polylines from packed 2D data arrays (p.147) | n/a | n/a |  | illustrative drawing of Fig. 128's arrays, no byte layout |
| Fig. 128 — Non-Text Polyline Data collection (p.148) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified; the 2D-vs-3D packing of the coordinates is left to the consumer (the flat array is the model) |
| Fig. 129 — PMI CAD Tag Data collection (p.149) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, .aCadTagIndexCountThatContradictsTheEntityCountsRefuses, MetaDataFixtureTest | index list fixture-verified and validated against the §11.2.7 count formula; the nested Compressed CAD Tag Data is decoded (Fig. 154 row) |
| Fig. 130 — PMI Polygon Data (p.151) | done | done | MetaDataDocumentTest.pmiManagerDecodesEverySubCollectionAndRoundTrips, MetaDataFixtureTest | fixture-verified incl. the parallel binding/dimension vectors and empty elements; colour/texture arrays spec-derived (no fixture binds them) |


## §12 Data Compression and Encoding

Both wire generations of the shape codecs are implemented and fixture-verified: the **JT 9
generation** (issue #4 — 12 real bodies, 506 packets) and the **v10 generation** (issue #6 —
39 NIST bodies, ~1 300 packets: bitlength both modes, arithmetic with the Figure-133
context, chopper, Move-to-Front, packed Deering codes; every stored hash validated at
decode). DESIGN.md deltas 15–21 record the JT 9 differences, deltas 27–31 the places where
the NIST bytes contradict or complete the v10 text.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Data Compression and Encoding (p.154) | partial | partial | Int32CdpTest, Int32CdpV10Test, Int64CdpTest, VertexArrayTest, FixtureDiscoveryTest | chapter row: both generations of the §7 codecs done; Int64CDP and the NURBS curve / CAD tag collections done since issue #10; the texture/colour/auxiliary vertex arrays wait for a fixture that binds them |
| Common Compression Data Collection Formats (p.155) | partial | partial | Int32CdpTest, Int32CdpV10Test, Int64CdpTest, VertexArrayTest | as above |
| Int32 Compressed Data Packet (p.155) | done | done | Int32CdpTest, Int32CdpV10Test, FixtureDiscoveryTest | JT 9 "Mk. 2" packet (delta 15) and the v10 third-generation packet (Figure 132) incl. Move-to-Front (delta 31) and the escape-conditional out-of-band packet (delta 28); v10 chopper-with-0-chop-bits refuses (spec forbids it) |
| Int64 Compressed Data Packet (p.161) | done | done | Int64CdpTest (every codec + hostile inputs), WireframeFixtureTest | landed with §10 (issue #10), its first consumer. Fixture-verified codecs: arithmetic (12 packets, 8 with an escape), bitlength (2), move-to-front (1); null and chopper are spec-derived (Figure 135 fixes them exactly as the fixture-verified Int32 forms). Write = byte-identical re-serialization of the preserved packet |
| Compressed Vertex Coordinate Array (p.164) | done | done | VertexArrayTest (both generations, lossless + quantized + hash refusals), FixtureDiscoveryTest | JT 9: exp/mant pairs (delta 19); v10: one binary/code packet per component, hashed per component array (delta 29) — stored hashes verified at decode |
| Compressed Vertex Normal Array (p.165) | done | done | VertexArrayTest (both generations incl. packed Deering + NULL-predictor pin), FixtureDiscoveryTest | JT 9: sextant/octant/theta/psi packets (delta 19); v10: one packed Deering code array, NULL predictor (deltas 29/30) |
| Compressed Vertex Texture Coordinate Array (p.167) | opaque | opaque | ShapeLodDocumentTest (binding refusal battery) | refuses with a named note — no fixture declares texture bindings; its time comes with a fixture that binds them |
| Compressed Vertex Colour Array (p.168) | opaque | opaque | ShapeLodDocumentTest (binding refusal battery) | as the texture array (colour bindings) |
| Compressed Vertex Flag Array (p.170) | done | done | VertexArrayTest.vertexFlagArrayRoundTripsAndValidatesTheCount, FixtureDiscoveryTest | v10 fixture-verified (all 39 NIST bodies bind vertex flags); the figure defines no stored hash — count validation only |
| Compressed Auxiliary Fields Array (p.170) | opaque | opaque | ShapeLodDocumentTest (binding refusal battery) | as the texture array (auxiliary field binding) |
| Point Quantizer Data (p.174) | done | done | VertexArrayTest.pointQuantizerIsThreeUniformQuantizers, FixtureDiscoveryTest | identical in both generations |
| Texture Quantizer Data (p.175) | opaque | opaque |  | with the texture coordinate array |
| Colour Quantizer Data (p.175) | opaque | opaque |  | with the colour array |
| Uniform Quantizer Data (p.177) | done | done | VertexArrayTest.uniformQuantizerRoundTripsAndDequantizes, FixtureDiscoveryTest | identical in both generations |
| Compressed Entity List for Non-Trivial Knot Vector (p.177) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, .knotTypeStoredValueCountsFollowTheTableSixtyEightFormula, WireframeFixtureTest | fixture-verified via the wireframe curve data; knot types 0 and 2 occur in the NIST bodies, 1 and 3 are spec-derived. Write = byte-identical re-serialization |
| Compressed Control Point Weights Data (p.180) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | fixture-verified (204 rational control points across the five bodies); §12.1.14's "unstored weight is 1.0" rule implemented and pinned |
| Compressed Curve Data (p.181) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, .aCurveBaseTypeOutsideTableSixtyNineRefuses, WireframeFixtureTest | fixture-verified for **MCS (XYZ) curves** (Table 71) on 197 NIST curves; the UV-curve dimensionality of Table 70 is a parameter of the same decoder, spec-derived until a JT B-Rep consumer exists |
| Compressed CAD Tag Data (p.185) | done | done | WireframeDocumentTest.sixtyFourBitCadTagsDecodeFromTheInt64Vector, .undecodableCadTagVectorsAreKeptVerbatimWithANote, WireframeFixtureTest, MetaDataFixtureTest | fully decoded since issue #10, in both its consumers: 5 wireframe reps (197 type-1 tags) and all 14 PMI managers. Data Length convention per delta 34; both tag vectors are always on the wire, empty-packet where the type does not occur (delta 40). Undecodable vectors fall back to verbatim bytes with `CAD_TAG_VECTORS_UNRECOGNIZED` |
| Encoding Algorithms (p.186) | done | n/a: writer emits the simple encodings | Int32CdpTest, Int32CdpV10Test, VertexArrayTest | decode side: both generations fixture-verified |
| Uniform Data Quantization (p.186) | done | n/a: writer emits lossless data | VertexArrayTest.uniformQuantizerRoundTripsAndDequantizes, FixtureDiscoveryTest | inverse implemented; since issue #6 fixture-exercised (the NIST LOD1/2 coordinates are quantized at 9–17 bits and land inside their shape nodes' boxes) |
| Bitlength CODEC (p.186) | done | n/a: writer emits the null CODEC | Int32CdpTest.bitlengthFixedWidthDecodes, .bitlengthVariableWidthDecodes, Int32CdpV10Test.bitlengthFixedWidthDecodesWithNibbledMinMax, .bitlengthVariableWidthDecodesWithFourBitBlocks | JT 9 wire format fixture-established (delta 17 — **neither spec's prose matches that wire**); v10 nibbler/4-bit-block variant per Annex B, verified on 696 NIST packets |
| Arithmetic CODEC (p.188) | done | n/a: writer emits the null CODEC | Int32CdpTest.arithmeticCodecDecodesWithEscapeAndOutOfBand, Int32CdpV10Test (escape + escapeless vectors), FixtureDiscoveryTest | one shared 16-bit decoder core; JT 9 contexts (delta 16) and v10 Figure-133 contexts, incl. the escape-conditional out-of-band packet (delta 28) |
| Deering Normal CODEC (p.193) | done | n/a: writer emits lossless normals | VertexArrayTest.deeringCodeConversionMatchesTheReference, .v10QuantizedNormalArrayUnpacksPackedDeeringCodes, FixtureDiscoveryTest | JT 9: four code packets; v10: packed `[sextant:3][octant:3][theta:n][psi:n]` codes (Annex B unpackCode) — hashes verified on all 24 NIST tri-strip normal arrays |
| LZMA compression (p.195) | done | n/a: writer emits ZLIB or none (issue #1 codec policy) | LzmaTest, SyntheticFileRoundTripTest.v10RoundTripWithLzmaDecoding, FixtureDiscoveryTest | pure-Kotlin `.xz`/LZMA2 decoder in commonMain — the wire container §12.2.5 names via XZ Utils, byte-verified on all 59 NIST streams (DESIGN.md); corrupt streams → COMPRESSED_DATA_CORRUPT, unimplemented xz features → UNSUPPORTED_COMPRESSION |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 131 — PMI Model View Sort Orders data collection (p.154) | opaque | opaque | MetaDataDocumentTest.undocumentedBytesAfterTheFontBlockAreNamedAndPreserved | a §11 figure (the TOC filed it here); it sits inside the PMI Manager's undocumented post-font block — DESIGN.md delta 33 |
| Fig. 132 — Int32 Compressed Data Packet data collection (p.156) | done | done | Int32CdpTest, Int32CdpV10Test (all codec paths, both out-of-band forms, hostile inputs), FixtureDiscoveryTest, WireframeFixtureTest | JT 9 "Mk. 2" (delta 15) and v10 third-generation wire formats, both fixture-verified; out-of-band data conditional on the escape entry (delta 28) and its *form* on the figure's external-compression branch (delta 37) | **Write: authored by `writeJt`** — the null CODEC (Table 64 value 0) is the packet form the writer emits (WriteJtTest.authoredTopologyIsOneClosedComponentPerTriangle).
| Fig. 133 — Int32 Probability Context (p.159) | done | done | Int32CdpTest.arithmeticCodecDecodesWithEscapeAndOutOfBand, Int32CdpV10Test | JT 9 table (delta 16: symbol field, escape −2) and v10 table (escape flag, 7-bit value width) |
| Fig. 134 — Int32 Probability Context Table Entry data collection (p.160) | done | done | Int32CdpV10Test (escape + escapeless vectors) | as Fig. 133 |
| Fig. 135 — Int64 Compressed Data Packet data collection (p.161) | done | done | Int64CdpTest (null / bitlength / arithmetic in both out-of-band forms / chopper / move-to-front + refusals), WireframeFixtureTest | §12.1.2's "low-order 32 bits first" rule applied to every 64-bit field; out-of-band form follows the external-compression branch (delta 37) |
| Fig. 136 — Int64 Probability Context data collection (p.163) | done | done | Int64CdpTest.arithmeticWithEmptyCodeTextTakesEveryValueOutOfBandNested, .aContextWithTwoEscapeEntriesRefuses, WireframeFixtureTest | fixture-verified on 12 packets; U64 min value read low-order word first, associated values stored unsigned relative to it |
| Fig. 137 — Int64 Probability Context Table Entry data collection (p.163) | done | done | Int64CdpTest (escape + escapeless contexts), WireframeFixtureTest | as Fig. 136 |
| Fig. 138 — Compressed Vertex Coordinate Array data collection (p.164) | done | done | VertexArrayTest (both generations, hash refusals), FixtureDiscoveryTest | JT 9 layout (delta 19); v10 layout with per-array lossless hashing (delta 29) | **Write: authored by `writeJt`** on the lossless path (zero quantization bits, raw float bits, per-array hashes).
| Fig. 139 — Compressed Vertex Normal Array data collection (p.166) | done | done | VertexArrayTest (both generations incl. the NULL-predictor pin), FixtureDiscoveryTest | JT 9 layout (delta 19); v10 packed Deering codes, NULL predictor (delta 30) | **Write: authored by `writeJt`** on the lossless path (zero quantization bits, raw float bits, per-array hashes).
| Fig. 140 — Compressed Vertex Texture Coordinate Array data collection (p.167) | opaque | opaque |  | no fixture declares the binding; refuses with note |
| Fig. 141 — Compressed Vertex Colour Array data collection (p.169) | opaque | opaque |  | as Fig. 140 |
| Fig. 142 — Compressed Vertex Flag Array data collection (p.170) | done | done | VertexArrayTest.vertexFlagArrayRoundTripsAndValidatesTheCount, FixtureDiscoveryTest | v10 fixture-verified (Table 48 bit 7 on every NIST body) |
| Fig. 143 — Compressed Auxiliary Fields Array data collection (p.171) | opaque | opaque |  | as Fig. 140 |
| Fig. 144 — Point Quantizer Data collection (p.174) | done | done | VertexArrayTest.pointQuantizerIsThreeUniformQuantizers, FixtureDiscoveryTest | identical in both generations | **Write: authored by `writeJt`** on the lossless path (zero quantization bits, raw float bits, per-array hashes).
| Fig. 145 — Texture Quantizer Data collection (p.175) | opaque | opaque |  | with the texture coordinate array |
| Fig. 146 — Colour Quantizer Data collection (p.176) | opaque | opaque |  | with the colour array |
| Fig. 147 — Uniform Quantizer Data collection (p.177) | done | done | VertexArrayTest.uniformQuantizerRoundTripsAndDequantizes, FixtureDiscoveryTest | identical in both generations | **Write: authored by `writeJt`** on the lossless path (zero quantization bits, raw float bits, per-array hashes).
| Fig. 148 — Compressed Entity List for Non-Trivial Knot Vector data collection (p.178) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | four-entry flag vector validated against Table 68; index lists are Lag1-predicted |
| Fig. 149 — Compressed Control Point Weights Data collection (p.180) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | fixture-verified; weight indices validated as ascending and in range |
| Fig. 150 — Compressed Curve Data collection (p.182) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, .aCurveBaseTypeOutsideTableSixtyNineRefuses, WireframeFixtureTest | fixture-verified on 197 curves; four independent count cross-checks make the field order exact |
| Fig. 151 — Non-Trivial Knot Vector NURBS Curve Indices data collection (p.184) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | a pass-through to Fig. 148 |
| Fig. 152 — NURBS Curve Control Point Weights data collection (p.184) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | a pass-through to Fig. 149 |
| Fig. 153 — NURBS Curve Control Points data collection (p.184) | done | done | WireframeDocumentTest.wireframeRepElementWithCurvesAndCadTagsDecodes, WireframeFixtureTest | fixture-verified: exactly three F64 coordinates per control point, non-homogeneous (rational curves keep their weights in Fig. 152) |
| Fig. 154 — Compressed CAD Tag Data collection (p.185) | done | done | WireframeDocumentTest.sixtyFourBitCadTagsDecodeFromTheInt64Vector, .undecodableCadTagVectorsAreKeptVerbatimWithANote, WireframeFixtureTest, MetaDataFixtureTest | framing fixture-verified on all 14 PMI managers (delta 34) and all 5 wireframe reps; the tag vectors decode since the Int64 CDP landed (issue #10). Type-2 (64-bit) tags are spec-derived — no fixture writes one |
| Fig. 155 — Sextant Coding on the Sphere (p.194) | n/a | n/a |  | illustrative drawing, no byte layout (the coding itself is the Deering CODEC row) |


## §13 Common Data Conventions and Constructs

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Common Data Conventions and Constructs (p.196) | partial | partial | (chapter row — see the rows below) | interpreted: late-loading, empty/hash fields, versions, scene construction, key naming, units, names, part structure, LOD selection, accumulation; carried raw with a consumer-condition: tessellation hints, SUBNODE/reference sets, watermark, PMI property semantics |
| Late-Loading Data (p.196) | done | done | SceneReadTest, SceneSyntheticFileTest, SceneFixtureTest, FixtureDiscoveryTest, WriteJtTest.namesAndUnitsFollowTheSection138Conventions | shape geometry resolved via Late Loaded Property Atoms by segment GUID + shape segment type (delta 13: object ids do not associate); the writer mints exactly that association  |
| TOC Segment Location (p.196) | done | done | TocTest, FileHeaderTest, WriteJtTest.writtenHeaderFollowsFigure11 | the reader honors the header offset wherever it points; the installed base itself splits on the §13.1 SHOULD (NX 10.5 writes the TOC at offset 109, right after the header; the NetAllied 9.5 writer at the file end) — `writeJt` writes it last, which has real-producer precedent. If JT2Go rejects a candidate, this placement is the first suspect |
| Bit Fields (p.196) | done | done | FixtureDiscoveryTest (byte-identical re-serialization), WriteJtTest | undefined bits are never interpreted and survive read → write verbatim; authored elements set every reserved bit to zero by construction |
| Empty Field (p.196) | done | done | FileHeaderTest, WriteJtTest.writtenHeaderFollowsFigure11 | preserved verbatim on read (clause 4.3); the writer emits zero, so no trailing header GUID follows  |
| Local version numbers (p.196) | done | done | LsgNodeElementCodecTest, WriteJtTest | read per generation (I16 in v9, U8/I8 in v10 — delta 6); the writer emits version 1 for every authored element  |
| Version numbers (p.196) | done | done | LsgNodeElementCodecTest, Lsg105GenerationTest, WriteJtTest | the global 0x01 convention with the listed 0x02 exceptions, read per generation (I16 v9 / U8 v10 — delta 6); the writer emits version 1 for every authored element |
| Hash Value (p.197) | done | done | VertexArrayTest, ShapeLodDocumentTest, WriteJtTest, WriteFixtureRewriteTest | every stored shape hash is verified at decode (Annex C row) and computed by the writer — an authored body whose hash disagreed would refuse to decode  |
| Scene graph construction (p.197) | done | done | SceneReadTest, SceneFixtureTest, SceneNistFixtureTest, WriteJtTest, WriteFixtureRewriteTest | the Layer 2 walk: partition root, instance sharing (shared scene objects), group/metadata structure; `writeJt` is its inverse — instanced subtrees round-trip as shared objects  |
| Metadata Conventions (p.198) | partial | partial | SceneReadTest, SceneFixtureTest, WriteJtTest | the name/units conventions are interpreted and authored (rows below); all other properties are carried as Layer 1 atoms, uninterpreted and not invented on write  |
| Property Key Naming Conventions (p.198) | done | done | SceneReadTest (hidden vs. visible key forms), SceneFixtureTest, WriteJtTest | hidden `key` and visible `key::` accepted as one key, case-sensitive otherwise; the writer emits the hidden form  |
| PMI Properties (p.199) | partial | partial | MetaDataFixtureTest, MetaDataDocumentTest | the PMI Property key/value atoms are read verbatim at Layer 1 (§11, issue #9); the Annex's semantic table (anchor points, colours, transformation matrices encoded into the value strings) is deliberately *not* parsed — that interpretation's time comes with a consumer, see DESIGN.md's deferral table |
| CAD Properties (p.199) | partial | partial | SceneReadTest (units battery), SceneFixtureTest, SceneNistFixtureTest, WriteJtTest.undeclaredUnitsAreRefused | JT_PROP_MEASUREMENT_UNITS (the required property) interpreted case-insensitively per the spec's own producer note — both fixtures write "Millimeters", which is what the writer emits; a scene without units is refused rather than defaulted; the other CAD properties pass through as Layer 1 atoms  |
| Tessellation Properties (p.201) | opaque | opaque | FixtureDiscoveryTest (property atoms + re-serialization) | carried raw at Layer 1 (Chordal/Angular/SegLength observed on the NIST fixture); the writer does not invent tessellation hints. Interpretation's time comes with a consumer that tunes tessellation |
| Miscellaneous Properties (p.202) | partial | partial | SceneReadTest.nameEncodingIsDecodedAndPlainNamesPassVerbatim, SceneNistFixtureTest, WriteJtTest | JT_PROP_NAME incl. the `Name;version;instance:` encoded form interpreted (both fixtures use it); the writer emits the plain name form; the other miscellaneous keys pass through  |
| The SUBNODE property and Reference Sets (p.203) | opaque | opaque | FixtureDiscoveryTest (property atoms + re-serialization) | observed on the NIST fixture, carried raw; interpretation's time comes with a consumer that needs reference sets |
| LSG Attribute Accumulation Semantics (p.207) | partial | partial | SceneReadTest (replacement, ignore flag, force/final note), TransformProbeTest, SceneFixtureTest (world bounds), WriteJtTest | transforms multiply, materials replace, Ignore flag honored; force/final/field-inhibit produce a named note instead of a guess — both fixtures use plain accumulation (stateFlags 8 everywhere), which is what the writer emits  |
| LSG Part Structure (p.208) | done | done | SceneReadTest.partConventionCollapsesToNamedPartsUnderTheRoot, SceneFixtureTest, SceneNistFixtureTest, WriteJtTest | the Figure 160 convention collapses to one named part node with per-tier meshes; `writeJt` emits the convention back (Part → Range LOD → per-tier shapes)  |
| Range LOD Node Alternative Rep Selection (p.208) | partial | partial | SceneReadTest (LodPolicy tests), SceneFixtureTest (descending tiers), WriteJtTest.everyLodTierBecomesItsOwnShapeSegment | tier order (finest first) interpreted by LodPolicy and written back as child order with one Shape LODn segment per tier; the eye-distance selection strategy is viewer runtime behavior, not a file property — the writer emits empty range limits, as the installed base does  |
| B-Rep Face Group Associations (p.208) | opaque | opaque | ShapeLodDocumentTest (faceGroup preserved per triangle) | face groups are preserved at Layer 1 (TriStripGeometry.Triangle.faceGroup); the association semantics wait for B-rep interpretation — a doctrine reversal (issue #1 rule 3) is the user's call |
| Watermark Image (p.209) | opaque | opaque |  | a convention over texture attributes + property atoms, both decoded at Layer 1 (§6); the display semantics are viewer behavior and no fixture carries one — its time comes with a consumer that renders watermarks |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 156 — Assembly node with SUBNODE (p.203) | opaque | opaque |  | with the SUBNODE row above |
| Fig. 157 — Assembly node without SUBNODE (p.204) | opaque | opaque |  | as Fig. 156 |
| Fig. 158 — Displaying Nodes that have SUBNODE properties (p.204) | opaque | opaque |  | as Fig. 156 |
| Fig. 159 — CAD Component with Reference sets (p.205) | opaque | opaque |  | as Fig. 156 |
| Fig. 160 — JT Format Convention for Modeling each Part in LSG (p.208) | done | done | SceneReadTest.partConventionCollapsesToNamedPartsUnderTheRoot, SceneNistFixtureTest, WriteJtTest | Part → Range LOD → per-tier Group → Shape, verified on both fixtures; the collapse folds it into one named part node, and `writeJt` unfolds it again  |


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
| Hash function implementation (p.239) | done | done | JtHashTest, FixtureDiscoveryTest | hash32 + hash16 (Jenkins lookup2); all 153 stored shape hashes verified at decode (36 across the 9.5 fixture's 12 bodies, 117 across the NIST fixture's 39) | **Write: authored by `writeJt`** — every authored shape body's composite, coordinate, normal and FGPV hashes are computed here, and the reader's verification is what proves them (WriteFixtureRewriteTest).


## Annex D — Polygon Mesh Topology Coder

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Polygon mesh topology coder (p.242) | done | n/a: writer's time comes with the authoring milestone | ShapeLodDocumentTest (both tetrahedra), FixtureDiscoveryTest | decoder (dual VFMesh reconstruction) shared by both generations; fixture-verified: all 12 v9 and 24 v10 tri-strip bodies decode complete meshes consistent with the LSG-declared ranges |


## Annex E — (deprecated) JT B-Rep Segment

*Prefilled:* **opaque by doctrine (issue #1 rule 3): carried losslessly, never interpreted** —
and since issue #10 *proven* so: `BrepOpacityTest` enumerates every precise-geometry segment of
every fixture, names its element type, and asserts the payload survives parse → whole-file re-encode
byte-identically. No fixture carries a (deprecated) JT B-Rep segment; the doctrine holds regardless
of that, so these rows would stay `opaque` even with one.

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
| Fig. 162 — JT B-Rep Element data collection (p.262) | opaque | opaque | BrepOpacityTest (kind covered; skips visibly — no fixture) | the element type is named by `ObjectTypeIds.JT_BREP_ELEMENT`; its body is never read |
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

*Prefilled:* **opaque by doctrine (issue #1 rule 3): carried losslessly, never interpreted** —
and since issue #10 *proven* so on real bytes: the NIST 10.5 fixture's 8 XT and 1 MultiXT segments
are enumerated, decompressed, element-framed and preserved verbatim by `BrepOpacityTest`, which also
finds the Parasolid `TRANSMIT FILE` container inside them. Annex F.1.3's MultiXT element GUID is
recorded in `ObjectTypeIds` (Annex A omits it) so the element is *named* rather than anonymous —
naming is not interpreting.

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| XT B-Rep Element (p.285) | opaque | opaque | BrepOpacityTest | 8 NIST segments enumerated and preserved; element GUID from Annex A |
| F.1.1 XT B-Rep Data (p.286) | opaque | opaque |  |  |
| F.1.2 Integer Attribute Data (p.286) | opaque | opaque |  |  |
| F.1.3 MultiXT B-Rep Segment (p.287) | opaque | opaque | BrepOpacityTest | 1 NIST segment; element GUID recorded in `ObjectTypeIds` because Annex A omits segment type 30 entirely |
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
| Fig. 189 — XT B-Rep Element data collection (p.285) | opaque | opaque | BrepOpacityTest | framing preserved; fields never read |
| Fig. 190 — Integer Attribute Data collection (p.287) | opaque | opaque |  |  |
| Fig. 191 — MultiXT B-Rep Element data collection (p.288) | opaque | opaque | BrepOpacityTest | as above |
| Fig. 192 — Split a face (p.367) | opaque | opaque |  |  |
| Fig. 193 — Merge faces (p.368) | opaque | opaque |  |  |


## Annex G — JT ULP Segment

*Prefilled:* **opaque by doctrine (issue #1 rule 3)** — ULP is the "semi-precise geometric Boundary
Representation" of §8.3, i.e. B-rep, so the same rule covers it: carried losslessly, never
interpreted. No fixture carries a ULP segment either; `BrepOpacityTest` covers the segment kind and
skips visibly until one does. The one piece of Annex G this library *does* use is Table 100, the
analytic surface type set §9 borrows for LWPA (see the §9 rows).

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| JT ULP Element (p.370) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| G.1.1 Topology Data (p.372) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| G.1.2 Geometric Data (p.389) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| G.1.3 Material Attribute Element Properties (p.413) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| G.1.4 Information Recovery (p.414) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 194 — JT ULP Segment data collection (p.370) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 195 — JT ULP Element data collection (p.371) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 196 — Topology Data collection (p.372) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 197 — Topological Entity Counts data collection (p.373) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 198 — Combined Predictor Type data collection (p.374) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 199 — Regions Topology Data collection (p.375) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 200 — Shells Topology Data collection (p.376) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 201 — Faces Topology Data collection (p.377) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 202 — Loops Topology Data collection (p.380) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 203 — CoEdges Topology Data collection (p.382) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 204 — Sample Model with Randomly Assigned Edge Indices (p.383) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 205 — Sample Model with Sequentially Assigned Edge Indices (p.383) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 206 — Surface Domain Classification (p.385) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 207 — Edges Topology Data collection (p.387) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 208 — Geometric Data collection (p.389) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 209 — Geometric Entity Counts (p.390) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 210 — Degree Table data collection (p.391) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 211 — Recover Nurbs Degree (p.392) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 212 — Number of Control Points Table data collection (p.393) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 213 — Recover Number of Control Points (p.394) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 214 — Dimension Table data collection (p.395) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 215 — Recover Dimension (p.396) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 216 — 3D Unit Vector Table data collection (p.397) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 217 — Recover Dimension (p.398) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 218 — 2D Unit Vector Table data collection (p.399) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 219 — Recover 2D Unit Vector (p.399) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 220 — 3D MCS Point Table data collection (p.400) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 221 — Recover 3D MCS Points (p.402) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 222 — Knot Vector Table data collection (p.403) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 223 — Recover Knot Vectors (p.404) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 224 — 1D MCS Table data collection (p.406) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 225 — Recover 1D MCS Table (p.408) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 226 — PCS Value Table data collection (p.409) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 227 — Recover PCS Value Table (p.410) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 228 — Radian Table data collection (p.411) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 229 — Recover Radian Table (p.411) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 231 — Recover Weight Table (p.413) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 232 — Material Attribute Element Properties (p.414) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 233 — Information Recovery (p.415) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 234 — PCS Curve Recovery from Surface Domain (p.416) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 235 — MCS Curve Recovery (p.417) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |
| Fig. 236 — MCS Curve Recovery from Surface Geometry (p.418) | opaque | opaque |  | opaque by doctrine (issue #1 rule 3); no fixture carries a ULP segment |


## Annex H — (deprecated) PMI Data Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| (deprecated) PMI data segment (p.419) | done | done | MetaDataDocumentTest, MetaDataFixtureTest | Annex H's own instruction — "a PMI Data Segment should be treated exactly the same as a PMI Manager Meta Data Element" — is what `MetaDataDocument` implements: the same model serves Table 6 types 3 and 4. Fixture-verified on the NIST file's 14 type-3 segments, which the current Siemens writer still emits despite the deprecation (issue #9) |


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
| Bibliography (p.562) | n/a: bibliography — nothing to implement | n/a: bibliography — nothing to implement |  |  |

