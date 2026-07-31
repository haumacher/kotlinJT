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
| Data Segments (p.26) | partial | partial | ElementScanTest | framing scanned; element *bodies* uninterpreted until Layer 1 (#1 milestone 1) |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 10 — JT File Structure (p.18) | done | done | SyntheticFileRoundTripTest | |
| Fig. 11 — File Header data collection (p.19) | done | done | FileHeaderTest | v9: I32 TOC offset @85; verified against real 9.5 fixture |
| Fig. 12 — TOC Segment data collection (p.20) | done | done | TocTest | |
| Fig. 13 — TOC Entry data collection (p.21) | done | done | TocTest | v9 28-byte / v10 32-byte entry |
| Fig. 14 — Data Segment data collection (p.22) | done | done | SyntheticFileRoundTripTest | |
| Fig. 15 — Segment Header data collection (p.22) | done | done | SyntheticFileRoundTripTest, HostileInputTest | id/type/length mismatches → named notes |
| Fig. 16 — Data collection (p.24) | done | done | ZlibTest, SyntheticFileRoundTripTest | ZLIB fixture-verified; flag≠2 layout spec-derived, unverified against a real file — note fallback in place |
| Fig. 17 — Logical Element Header data collection (p.24) | done | done | ElementScanTest | |
| Fig. 18 — Element Header data collection (p.24) | done | done | ElementScanTest | |
| Fig. 19 — Logical Element Header Compressed data collection (p.25) | done | done | ElementScanTest, FixtureDiscoveryTest | scanned in inflated LSG of the real fixture (67 elements) |


## §6 LSG Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| LSG Segment (p.28) | — | — |  |  |
| Graph Elements (p.28) | — | — |  |  |
| Node Elements (p.29) | — | — |  |  |
| Attribute Elements (p.49) | — | — |  |  |
| Property Atom Elements (p.83) | — | — |  |  |
| Base Property Atom Element (p.83) | — | — |  |  |
| String Property Atom Element (p.84) | — | — |  |  |
| Integer Property Atom Element (p.84) | — | — |  |  |
| Floating Point Property Atom Element (p.85) | — | — |  |  |
| JT Object Reference Property Atom Element (p.86) | — | — |  |  |
| Date Property Atom Element (p.86) | — | — |  |  |
| Late Loaded Property Atom Element (p.88) | — | — |  |  |
| Vector4f Property Atom Element (p.89) | — | — |  |  |
| Property Table (p.90) | — | — |  |  |
| Element Property Table (p.91) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 20 — LSG Segment data collection (p.28) | — | — |  |  |
| Fig. 21 — Base Node Element data collection (p.29) | — | — |  |  |
| Fig. 22 — Base Node Data collection (p.29) | — | — |  |  |
| Fig. 23 — Partition Node Element data collection (p.31) | — | — |  |  |
| Fig. 24 — Vertex Count Range data collection (p.32) | — | — |  |  |
| Fig. 25 — Group Node Element data collection (p.33) | — | — |  |  |
| Fig. 26 — Group Node Data collection (p.34) | — | — |  |  |
| Fig. 27 — Instance Node Element data collection (p.35) | — | — |  |  |
| Fig. 28 — Part Node Element data collection (p.35) | — | — |  |  |
| Fig. 29 — Meta Data Node Element data collection (p.36) | — | — |  |  |
| Fig. 30 — Meta Data Node Data collection (p.36) | — | — |  |  |
| Fig. 31 — LOD Node Element data collection (p.37) | — | — |  |  |
| Fig. 32 — LOD Node Data collection (p.37) | — | — |  |  |
| Fig. 33 — Range LOD Node Element data collection (p.38) | — | — |  |  |
| Fig. 34 — Switch Node Element data collection (p.39) | — | — |  |  |
| Fig. 35 — Base Shape Node Element data collection (p.40) | — | — |  |  |
| Fig. 36 — Base Shape Data collection (p.40) | — | — |  |  |
| Fig. 38 — Vertex Shape Node Element data collection (p.42) | — | — |  |  |
| Fig. 39 — Vertex Shape Data collection (p.43) | — | — |  |  |
| Fig. 40 — Polyline Set Shape Node Element data collection (p.44) | — | — |  |  |
| Fig. 41 — Point Set Shape Node Element data collection (p.45) | — | — |  |  |
| Fig. 42 — Polygon Set Shape Node Element data collection (p.46) | — | — |  |  |
| Fig. 43 — NULL Shape Node Element data collection (p.46) | — | — |  |  |
| Fig. 44 — Primitive Set Shape Node Element data collection (p.47) | — | — |  |  |
| Fig. 45 — Primitive Set Quantization Parameters data collection (p.48) | — | — |  |  |
| Fig. 46 — Base Attribute Data collection (p.49) | — | — |  |  |
| Fig. 47 — Material Attribute Element data collection (p.51) | — | — |  |  |
| Fig. 48 — Texture Image Attribute Element data collection (p.54) | — | — |  |  |
| Fig. 49 — Texture Vers-1 Data collection (p.55) | — | — |  |  |
| Fig. 50 — Texture Environment data collection (p.58) | — | — |  |  |
| Fig. 51 — Texture Coord Generation Parameters data collection (p.61) | — | — |  |  |
| Fig. 52 — Inline Texture Image Data collection (p.62) | — | — |  |  |
| Fig. 53 — Image Format Description data collection (p.63) | — | — |  |  |
| Fig. 54 — Draw Style Attribute Element data collection (p.65) | — | — |  |  |
| Fig. 55 — Light Set Attribute Element data collection (p.67) | — | — |  |  |
| Fig. 56 — Infinite Light Attribute Element data collection (p.68) | — | — |  |  |
| Fig. 57 — Base Light Data collection (p.69) | — | — |  |  |
| Fig. 58 — Point Light Attribute Element data collection (p.71) | — | — |  |  |
| Fig. 59 — Spread Angle value with respect to the light cone (p.72) | — | — |  |  |
| Fig. 60 — Attenuation Coefficients data collection (p.73) | — | — |  |  |
| Fig. 61 — Linestyle Attribute Element data collection (p.74) | — | — |  |  |
| Fig. 62 — Pointstyle Attribute Element data collection (p.75) | — | — |  |  |
| Fig. 63 — Geometric Transform Attribute Element data collection (p.76) | — | — |  |  |
| Fig. 64 — Texture Coordinate Generator Attribute Element data collection (p.78) | — | — |  |  |
| Fig. 65 — Mapping Plane Element data collection (p.79) | — | — |  |  |
| Fig. 66 — Mapping Cylinder Element data collection (p.80) | — | — |  |  |
| Fig. 67 — Mapping Sphere Element data collection (p.81) | — | — |  |  |
| Fig. 68 — Mapping TriPlanar Element data collection (p.82) | — | — |  |  |
| Fig. 69 — Base Property Atom Element data collection (p.83) | — | — |  |  |
| Fig. 70 — Base Property Atom Data collection (p.83) | — | — |  |  |
| Fig. 71 — String Property Atom Element data collection (p.84) | — | — |  |  |
| Fig. 72 — Integer Property Atom Element data collection (p.85) | — | — |  |  |
| Fig. 73 — Floating Point Property Atom Element data collection (p.85) | — | — |  |  |
| Fig. 74 — JT Object Reference Property Atom Element data collection (p.86) | — | — |  |  |
| Fig. 75 — Date Property Atom Element data collection (p.87) | — | — |  |  |
| Fig. 76 — Late Loaded Property Atom Element data collection (p.88) | — | — |  |  |
| Fig. 77 — Vector4f Property Atom Element data collection (p.89) | — | — |  |  |
| Fig. 78 — Property Table data collection (p.90) | — | — |  |  |
| Fig. 79 — Element Property Table data collection (p.91) | — | — |  |  |


## §7 Shape LOD Segment

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Shape LOD Segment (p.92) | — | — |  |  |
| Shape LOD Element (p.92) | — | — |  |  |
| Tri-Strip Set Shape LOD Element (p.92) | — | — |  |  |
| Polyline Set Shape LOD Element (p.93) | — | — |  |  |
| Point Set Shape LOD Element (p.93) | — | — |  |  |
| Polygon Set LOD Element (p.94) | — | — |  |  |
| Null Shape LOD Element (p.107) | — | — |  |  |
| Primitive Set Shape Element (p.107) | — | — |  |  |
| Lossless Compressed Primitive Set Data (p.109) | — | — |  |  |
| Lossy Quantized Primitive Set Data (p.111) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 81 — Tri-Strip Set Shape LOD Element data collection (p.92) | — | — |  |  |
| Fig. 82 — Polyline Set Shape LOD Element data collection (p.93) | — | — |  |  |
| Fig. 83 — Point Set Shape LOD Element data collection (p.94) | — | — |  |  |
| Fig. 84 — Polygon Set LOD Element data collection (p.94) | — | — |  |  |
| Fig. 85 — Vertex Shape LOD Data collection (p.95) | — | — |  |  |
| Fig. 86 — Base Shape LOD Data collection (p.97) | — | — |  |  |
| Fig. 87 — TopoMesh Compressed LOD Data collection (p.97) | — | — |  |  |
| Fig. 88 — TopoMesh LOD Data collection (p.98) | — | — |  |  |
| Fig. 89 — TopoMesh Compressed Rep Data data collection (p.99) | — | — |  |  |
| Fig. 90 — Quantization Parameters data collection (p.101) | — | — |  |  |
| Fig. 91 — TopoMesh Topologically Compressed LOD Data collection (p.102) | — | — |  |  |
| Fig. 92 — Topologically Compressed Rep Data Collection (p.103) | — | — |  |  |
| Fig. 93 — Topologically Compressed Vertex Records data collection (p.106) | — | — |  |  |
| Fig. 94 — Null Shape LOD Element data collection (p.107) | — | — |  |  |
| Fig. 96 — Lossless Compressed Primitive Set Data collection (p.109) | — | — |  |  |
| Fig. 97 — Lossy Quantized Primitive Set Data collection (p.111) | — | — |  |  |
| Fig. 98 — Compressed params1 data collection (p.113) | — | — |  |  |


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

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Data Compression and Encoding (p.154) | — | — |  |  |
| Common Compression Data Collection Formats (p.155) | — | — |  |  |
| Int32 Compressed Data Packet (p.155) | — | — |  |  |
| Int64 Compressed Data Packet (p.161) | — | — |  |  |
| Compressed Vertex Coordinate Array (p.164) | — | — |  |  |
| Compressed Vertex Normal Array (p.165) | — | — |  |  |
| Compressed Vertex Texture Coordinate Array (p.167) | — | — |  |  |
| Compressed Vertex Colour Array (p.168) | — | — |  |  |
| Compressed Vertex Flag Array (p.170) | — | — |  |  |
| Compressed Auxiliary Fields Array (p.170) | — | — |  |  |
| Point Quantizer Data (p.174) | — | — |  |  |
| Texture Quantizer Data (p.175) | — | — |  |  |
| Colour Quantizer Data (p.175) | — | — |  |  |
| Uniform Quantizer Data (p.177) | — | — |  |  |
| Compressed Entity List for Non-Trivial Knot Vector (p.177) | — | — |  |  |
| Compressed Control Point Weights Data (p.180) | — | — |  |  |
| Compressed Curve Data (p.181) | — | — |  |  |
| Compressed CAD Tag Data (p.185) | — | — |  |  |
| Encoding Algorithms (p.186) | — | — |  |  |
| Uniform Data Quantization (p.186) | — | — |  |  |
| Bitlength CODEC (p.186) | — | — |  |  |
| Arithmetic CODEC (p.188) | — | — |  |  |
| Deering Normal CODEC (p.193) | — | — |  |  |
| LZMA compression (p.195) | — | n/a: writer emits ZLIB or none | | named refusal UNSUPPORTED_COMPRESSION in place; decoding needed once a real v10 file arrives (deferral table in DESIGN.md) |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 131 — PMI Model View Sort Orders data collection (p.154) | — | — |  |  |
| Fig. 132 — Int32 Compressed Data Packet data collection (p.156) | — | — |  |  |
| Fig. 133 — Int32 Probability Context (p.159) | — | — |  |  |
| Fig. 134 — Int32 Probability Context Table Entry data collection (p.160) | — | — |  |  |
| Fig. 135 — Int64 Compressed Data Packet data collection (p.161) | — | — |  |  |
| Fig. 136 — Int64 Probability Context data collection (p.163) | — | — |  |  |
| Fig. 137 — Int64 Probability Context Table Entry data collection (p.163) | — | — |  |  |
| Fig. 138 — Compressed Vertex Coordinate Array data collection (p.164) | — | — |  |  |
| Fig. 139 — Compressed Vertex Normal Array data collection (p.166) | — | — |  |  |
| Fig. 140 — Compressed Vertex Texture Coordinate Array data collection (p.167) | — | — |  |  |
| Fig. 141 — Compressed Vertex Colour Array data collection (p.169) | — | — |  |  |
| Fig. 142 — Compressed Vertex Flag Array data collection (p.170) | — | — |  |  |
| Fig. 143 — Compressed Auxiliary Fields Array data collection (p.171) | — | — |  |  |
| Fig. 144 — Point Quantizer Data collection (p.174) | — | — |  |  |
| Fig. 145 — Texture Quantizer Data collection (p.175) | — | — |  |  |
| Fig. 146 — Colour Quantizer Data collection (p.176) | — | — |  |  |
| Fig. 147 — Uniform Quantizer Data collection (p.177) | — | — |  |  |
| Fig. 148 — Compressed Entity List for Non-Trivial Knot Vector data collection (p.178) | — | — |  |  |
| Fig. 149 — Compressed Control Point Weights Data collection (p.180) | — | — |  |  |
| Fig. 150 — Compressed Curve Data collection (p.182) | — | — |  |  |
| Fig. 151 — Non-Trivial Knot Vector NURBS Curve Indices data collection (p.184) | — | — |  |  |
| Fig. 152 — NURBS Curve Control Point Weights data collection (p.184) | — | — |  |  |
| Fig. 153 — NURBS Curve Control Points data collection (p.184) | — | — |  |  |
| Fig. 154 — Compressed CAD Tag Data collection (p.185) | — | — |  |  |
| Fig. 155 — Sextant Coding on the Sphere (p.194) | — | — |  |  |


## §13 Common Data Conventions and Constructs

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Common Data Conventions and Constructs (p.196) | — | — |  |  |
| Late-Loading Data (p.196) | — | — |  |  |
| TOC Segment Location (p.196) | — | — |  |  |
| Bit Fields (p.196) | — | — |  |  |
| Empty Field (p.196) | — | — |  |  |
| Local version numbers (p.196) | — | — |  |  |
| Version numbers (p.196) | — | — |  |  |
| Hash Value (p.197) | — | — |  |  |
| Scene graph construction (p.197) | — | — |  |  |
| Metadata Conventions (p.198) | — | — |  |  |
| Property Key Naming Conventions (p.198) | — | — |  |  |
| PMI Properties (p.199) | — | — |  |  |
| CAD Properties (p.199) | — | — |  |  |
| Tessellation Properties (p.201) | — | — |  |  |
| Miscellaneous Properties (p.202) | — | — |  |  |
| The SUBNODE property and Reference Sets (p.203) | — | — |  |  |
| LSG Attribute Accumulation Semantics (p.207) | — | — |  |  |
| LSG Part Structure (p.208) | — | — |  |  |
| Range LOD Node Alternative Rep Selection (p.208) | — | — |  |  |
| B-Rep Face Group Associations (p.208) | — | — |  |  |
| Watermark Image (p.209) | — | — |  |  |

| Figure | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Fig. 156 — Assembly node with SUBNODE (p.203) | — | — |  |  |
| Fig. 157 — Assembly node without SUBNODE (p.204) | — | — |  |  |
| Fig. 158 — Displaying Nodes that have SUBNODE properties (p.204) | — | — |  |  |
| Fig. 159 — CAD Component with Reference sets (p.205) | — | — |  |  |
| Fig. 160 — JT Format Convention for Modeling each Part in LSG (p.208) | — | — |  |  |


## Annex A — Object Type Identifiers

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Object Type Identifier table (GUID → element type) (p.211) | — | — |  |  |


## Annex B — Coding Algorithms: An Implementation

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Sample bit-length / arithmetic decoder source (p.215) | — | — |  |  |


## Annex C — Hashing: An Implementation

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Hash function implementation (p.239) | — | — |  |  |


## Annex D — Polygon Mesh Topology Coder

| Section | Read | Write | Evidence (tests) | Notes |
|---|---|---|---|---|
| Polygon mesh topology coder (p.242) | — | — |  |  |


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

