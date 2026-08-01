# Package G — JT B-Rep, XT B-Rep, Wireframe, ULP and LWPA (JT 9.5 Rev-D §7.2.3–§7.2.5, §7.2.8–§7.2.9, §9.10)

**Calibration statement (as the brief requires).** This file is deliberately *uneven in depth*, and the
unevenness follows the library's doctrine rather than the spec's page count:

- **JT B-Rep (§7.2.3) and XT B-Rep (§7.2.4) are preserved opaquely by doctrine** (issue #1 rule 3;
  `BrepOpacityTest` is the committed proof). I went **deep on framing** — segment type codes, the
  compression column, the element GUIDs, the element header shape, the trailing bytes the proof test
  asserts — because that is what has to stay right for the opaque carry to be byte-faithful *and*
  correctly named on a 9.5 file. I went **broad, not deep, on contents**: every figure of Figs. 101–128
  gets an `opaque` row with its v10 counterpart and the condition under which its time comes, and I
  diffed the field lists and predictors far enough to say `identical` / `widths` / `structural`
  honestly, but I did not verify the NURBS collections at the level of "every guard traced". Where I
  did not, the Delta cell says so.
- **Wireframe (§7.2.5) and LWPA (§7.2.9) get the full field-by-field treatment**, because the library
  decodes both in the v10 generation and the 9.5 layouts are candidate work.
- **JT ULP (§7.2.8) gets an inventory and a cost estimate, not a diff.** I established what v10 did
  with it, what segment type carries it, and whether the library would name it. I then read only as
  much of the table collections (Figs. 186–207) as the estimate needed — see §"What I skipped" below.

Citations: 9.5 figures as `Fig. NNN` with the 9.5 page from the TOC; v10 figures as `Fig. NNN` from
the v10 Rev-C reference. **The two numbering schemes do not correspond.**

---

## Part 1 — ledger rows

### Vocabulary used in Read / Write

- `opaque` — the unit's bytes are enumerated, decompressed, framed, named and preserved verbatim, but
  never interpreted.
- `—` — nothing at all; the unit is inside an opaque body, so its bytes survive but the library has no
  concept of it.
- `n/a: byte-faithful carry` — the honest 9.5 Write target for an opaque unit: re-serialization
  reproduces the bytes exactly (proved for the whole file by `BrepOpacityTest`), and `writeJt` authors
  no segment of this kind.

### A. §7.2.3 JT B-Rep Segment (p.134–157)

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §7.2.3 JT B-Rep Segment (p.134) — Table 3 type 2, ZLIB=Yes | §8.1 + Annex E; Table 6 type 2, "deprecated" | `identical` (code, compression column); v10 adds the deprecation | `opaque` | `n/a: byte-faithful carry` | `SegmentKind.kt:12` | 9.5 does **not** call it deprecated. Finding 6 |
| Fig. 101 — JT B-Rep Segment data collection (p.135) | Fig. 161 (title lost in v10's TOC: "Figure 161 —data collection") | `identical` — Segment Header, JT B-Rep Element | `opaque` | `n/a: byte-faithful carry` | `BrepOpacityTest` | neither revision shows a trailing Property Table. Finding 4 |
| §7.2.3.1 Object Type ID `0x873a70c0,0x2ac8,0x11d1,9b6b0080c7bb5997` (p.135) | Annex E.1, same GUID | `identical` | `done` (named) | `n/a` | `ObjectTypeIds.kt:70`, `:143` | the opaque carry is correctly *named* on a 9.5 file |
| Fig. 102 — JT B-Rep Element data collection (p.136) | Fig. 162 | `structural` + `widths`: (a) version `I16` (9.5) vs `U8` (v10); (b) 9.5 has **three fields v10 dropped** — `U32 : Reserved Field` after the version, and `CoordF64 : Reserved Field` + `F64 : Reserved Field` between Geometric Entity Counts and the `Region Count > 0` block. Guards `Region Count > 0`, `Version Number > 4`, `CAD Tags Flag == 1` are the same | `opaque` | `n/a: byte-faithful carry` | — | 9.5: "Only version number 0x0001 is currently defined" — so the `Version Number > 4` CAD-tag branch is unreachable in a conforming 9.5 file. Finding 7 |
| Fig. 103 — Topological Entity Counts (p.137) | Fig. 163 | `identical` — 7 × `I32` in the order Region, Shell, Face, Loop, CoEdge, Edge, Vertex; checked field by field | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 104 — Geometric Entity Counts (p.138) | Fig. 164 | `identical` — 4 × `I32`: Surface, PCS Curve, MCS Curve, Point; checked | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 105 — Topology Data collection (p.139) | Fig. 165 | `identical` — same six `> 0` guards in the same order (Regions unguarded, then Shell/Face/Loop/CoEdge/Edge/Vertex counts) | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 106 — Regions Topology Data (p.140) | Fig. 166 | `identical` field-for-field: First Shell Indices, Last Shell Indices, Region Tags, all `VecI32{Int32CDP, Lag1}` | `opaque` | `n/a: byte-faithful carry` | — | the `Int32CDP` **token** denotes different packet formats per generation. Finding 3 |
| Fig. 107 — Shells Topology Data (p.141) | Fig. 167 | `identical`: First Face Indices, Last Face Indices, Shell Tags (`Lag1`), Shell Anti-Hole Flags (`Xor1`) | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 108 — Trim Loop example in parameter Space (p.142) | Fig. 168 | `identical` (illustration) | `n/a: illustration, no bytes` | `n/a` | — | |
| Fig. 109 — Faces Topology Data (p.142) | Fig. 169 | `identical`: First/Last Trim Loop Indices, Surface Indices, Face Tags (`Lag1`), Face Reverse Normal Flags (`Xor1`) | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 110 — Loops Topology Data (p.144) | Fig. 170 | `identical`: First/Last CoEdge Indices, Loop Tags (`Lag1`), Anti-Hole Flags (`Xor1`). 9.5 writes the codec token as `I32CDP` in two of the four boxes — a typo for `Int32CDP`, the prose says "Int32 version of the CODEC" | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 111 — CoEdges Topology Data (p.145) | Fig. 171 | `identical`: Edge Indices, PCS Curve Indices, CoEdge Tags (`Lag1`), MCS Curve Reversed Flags (`Xor1`) | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 112 — Edges Topology Data (p.146) | Fig. 172 | `identical`: Start/End Vertex Indices, MCS Curve Indices, Edge Tags — all `Lag1` | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 113 — Vertices Topology Data (p.146) | Fig. 173 | `identical`: Point Indices, Vertex Tags (`Lag1`) | `opaque` | `n/a: byte-faithful carry` | — | both revisions call the collection optional |
| Fig. 114 — Geometric Data collection (p.147) | Fig. 174 | `identical` — four `> 0` guards: Surface, PCS Curve, MCS Curve, Point | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 115 — Surfaces Geometric Data (p.148) | Fig. 175 | `structural` (predictor + rename): same eight members in the same order, but every `VecI32` is `Lag1` in 9.5 and `NULL` in v10; and 9.5's "NURBS Surface **Reserved** Fields" is v10's "NURBS Surface **Empty** Fields" | `opaque` | `n/a: byte-faithful carry` | — | Finding 5 |
| Fig. 116 — Non-Trivial Knot Vector NURBS Surface Indices (p.149) | Fig. 176 | `identical` — U then V, each a Compressed Entity List for Non-Trivial Knot Vector (9.5 §8.1.13 / v10 §12.1.13) | `opaque` | `n/a: byte-faithful carry` | — | the §8.1.13 collection itself is package H's unit |
| Fig. 117 — NURBS Surface Degree (p.150) | Fig. 177 | `structural` (predictor): U-Degrees, V-Degrees — `Lag1` in 9.5, `NULL` in v10 | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 118 — NURBS Surface Control Point Counts (p.150) | Fig. 178 | `structural` (predictor): U/V Control Point Counts — `Lag1` vs `NULL` | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 119 — NURBS Surface Control Point Weights (p.151) | Fig. 179 | `identical` — a pass-through to Compressed Control Point Weights Data (9.5 §8.1.14) | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 120 — NURBS Surface Control Points (p.151) | Fig. 180 | `structural` (codec): `VecF64{Float64CDP, NULL}` in 9.5, `VecF64{Int64CDP, NULL}` in v10 | `opaque` | `n/a: byte-faithful carry` | — | Finding 2 |
| Fig. 121 — NURBS Surface Knot Vectors (p.151) | Fig. 181 | `structural` (codec): U/V Knot Vectors `Float64CDP` vs `Int64CDP` | `opaque` | `n/a: byte-faithful carry` | — | Finding 2 |
| Fig. 122 — PCS Curves Geometric Data (p.152) | Fig. 182 | `identical` — Trivial PCS Curves, then Compressed Curve Data | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 123 — Trivial PCS Curves (p.153) | Fig. 183 | `structural` (codec, one field): three `I32` exist-flags then three guarded blocks — identical in field order, names and guards. The one delta: `Trivial Box Loop Corner Coords` is `VecF64{Float64CDP, NULL}` in 9.5 and `VecF64{Int64CDP, NULL}` in v10. All four `VecI32` stay `Lag1` in both | `opaque` | `n/a: byte-faithful carry` | — | note the contrast with Fig. 115: the *trivial PCS* vectors kept `Lag1` in v10 while the *surface* vectors went `NULL` |
| (the box-equality diagram inside §7.2.3.1.4.2.1, p.153) | Fig. 184 (v10 numbers it) | `identical` (illustration) | `n/a: illustration, no bytes` | `n/a` | — | 9.5 leaves it unnumbered; v10 gives it a figure number — a unit-count difference, not a byte difference |
| Fig. 124 — MCS Curves Geometric Data (p.155) | Fig. 185 | `identical` — one Compressed Curve Data collection | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 125 — Point Geometric Data (p.155) | Fig. 186 | `identical` — `CoordF32 : Point Coordinates` repeated `Point Count` times | `opaque` | `n/a: byte-faithful carry` | — | F32, not F64, in both |
| Fig. 126 — Topological Entity Tag Counters (p.156) | Fig. 187 | `identical` — 7 × `I32` in the same entity order as Fig. 103 | `opaque` | `n/a: byte-faithful carry` | — | |
| Fig. 127 — B-Rep CAD Tag Data (p.157) | Fig. 188 | `identical` at this level (a pass-through to Compressed CAD Tag Data); the *pointee* differs structurally — see the dependency table | `opaque` | `n/a: byte-faithful carry` | — | both fix the count at `Face Count + Edge Count` |

### B. §7.2.4 XT B-Rep Segment (p.157–159)

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §7.2.4 XT B-Rep Segment (p.157) — Table 3 type 17, ZLIB=Yes | §8.2 + Annex F; Table 6 type 17 | `identical` (code, compression column) | `opaque` | `n/a: byte-faithful carry` | `SegmentKind.kt:27` | |
| §7.2.4.1 Object Type ID `0x873a70e0,0x2ac9,0x11d1,9b6b0080c7bb5997` (p.157) | Annex F.1, same GUID | `identical` | `done` (named) | `n/a` | `ObjectTypeIds.kt:86`, `:153` | |
| Fig. 128 — XT B-Rep Element data collection (p.158) | Fig. 189 | `structural` + `widths`, heavily: 9.5 = LEH ZLIB, `I32` Version, `I32` Parasolid Major/Minor/Build, `I32` XT B-Rep Data Length, XT B-Rep Data. v10 = LEH Compressed, **`U8` Version**, **new `U8 : Subordinate Flag`** with a whole `!= 0` branch (`U32 : Body Count`, `VecI32{Int32CDP,NULL} : Body Identifiers`, MultiXT B-Rep Element), then the three XT version `I32`s, Data Length, XT B-Rep Data, and a **new trailing `Integer Attribute Data`** | `opaque` | `n/a: byte-faithful carry` | — | 9.5: "Version number '2' is currently the only valid value for v9 JT files". No subordinate/MultiXT concept in 9.5 — consistent with Table 3 having no type 30 |
| §7.2.4.1.1 XT B-Rep Data (p.159) | Annex F.1.1 | `identical` — a raw Parasolid `PK_PART_transmit` neutral-binary stream | `opaque` | `n/a: byte-faithful carry` | `BrepOpacityTest` ("TRANSMIT FILE" banner assertion) | outside JT's scope in both revisions |

### C. §7.2.5 Wireframe Segment (p.159–161) — **full field-by-field**

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §7.2.5 Wireframe Segment (p.159) — Table 3 type 18, ZLIB=Yes | §10; Table 6 type 18 | `identical` (code, compression column) | `done` (framing) | `n/a: writer emits v10` | `SegmentKind.kt:28`, `WireframeDocument.kt:160` | |
| Fig. 129 — Wireframe Segment data collection (p.159) | Fig. 103 | `identical` — Segment Header, Wireframe Rep Element | `done` | `n/a: byte-faithful carry` | `WireframeDocument.kt:88` | the document seam (element list + trailing Property Table) is generation-agnostic and works on a 9.5 stream |
| §7.2.5.1 Object Type ID `0x873a70d0,0x2ac8,0x11d1,9b6b0080c7bb5997` (p.159) | §10.1, same GUID | `identical` | `done` (named) | `n/a` | `ObjectTypeIds.kt:87`, `:154` | |
| Fig. 130 — Wireframe Rep Element data collection (p.160) | Fig. 104 | `widths` + `structural`, three deltas, all checked field by field: **(1)** `I16 : Version Number` in 9.5 — *and 9.5's prose agrees with its own box* — vs `U8` in v10's prose (v10's box still says `I16`). **(2)** the two conditional vectors are `VecI32{Int32CDP2, Lag1}` in 9.5 and `VecI32{Int32CDP, NULL}` in v10 — both the predictor **and** the packet generation change. **(3)** everything else is the same: `I32 Edge Count`, `I32 MCS Curve Count`, the `Edge Count > 0` block (MCS Curve Indices, Edge Tags), the `MCS Curve Count > 0` block, `I32 Edge Tag Counter`, `U32 CAD Tags Flag`, `CAD Tags Flag == 1` block — same order, same guards | `opaque` (V9 refused, `ELEMENT_LAYOUT_UNVERIFIED`) | `n/a: byte-faithful carry` | `WireframeDocument.kt:205-208` (the V9 refusal), `:228` (the v10 reader) | Findings 1 and 9 |
| Fig. 131 — Wireframe MCS Curves Geometric Data (p.161) | Fig. 105 | `identical` at this level — one Compressed Curve Data collection; the *pointee* differs structurally (see dependencies) | `opaque` (V9) / `done` (v10) | `n/a: byte-faithful carry` | `CurveData.kt` via `WireframeDocument.kt:271` | both say "currently only NURBS Curve types are supported" |
| Fig. 132 — Wireframe Rep CAD Tag Data (p.161) | Fig. 106 | `identical` at this level — one Compressed CAD Tag Data collection; the pointee differs structurally (see dependencies) | `opaque` (V9) / `done` (v10) | `n/a: byte-faithful carry` | `CadTagData.kt` via `WireframeDocument.kt:283` | both fix the count at one tag per Edge — the check at `WireframeDocument.kt:283` is valid for 9.5 too |

### D. §7.2.8 JT ULP Segment (p.202–249) — inventory

**Segment-level answers the brief asked for:**

- **v10 kept ULP, under the same name.** It is *not* in a numbered chapter: v10 §8.3 "JT ULP Segment"
  is a two-paragraph pointer, and the full description lives in **Annex G**, Figs. 194–236.
- **Segment type code 20 in both** (9.5 Table 3, v10 Table 6), compression = Yes in both.
- **Element GUID identical in both**: `0xf338a4af,0xd7d2,0x41c5,0xbc,0xf2,0xc5,0x5a,0x88,0xb2,0x1e,0x73`
  (9.5 §7.2.8.1 and Annex A Table 11; v10 §8.3 and Annex A).
- **The library would name a 9.5 ULP segment correctly.** `SegmentKind.ULP(20, "ULP", true)`
  (`SegmentKind.kt:29`) resolves the code, so `UndefinedSegmentTypes.labelFor(20)` returns `"ULP"` and
  **no `UNKNOWN_SEGMENT_TYPE` note is emitted**; the payload decompresses (ZLIB via the algorithm-byte
  dispatch), the element frames, and `ObjectTypeIds.nameOf` returns `"JT ULP Element"`
  (`ObjectTypeIds.kt:88`, `:155`). So Fig. 171 and Fig. 172's *framing* are `opaque`, not `—`.
  Everything inside the element body is `—`.

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §7.2.8 JT ULP Segment (p.202) — Table 3 type 20, ZLIB=Yes | §8.3 + Annex G; Table 6 type 20 | `identical` | `opaque` | `n/a: byte-faithful carry` | `SegmentKind.kt:29` | |
| Fig. 171 — JT ULP Segment data collection (p.202) | Fig. 194 | `identical` — Segment Header, JT ULP Element | `opaque` | `n/a: byte-faithful carry` | `BrepOpacityTest` (kind covered, skips visibly) | |
| §7.2.8.1 Object Type ID (p.202) | Annex G.1, same GUID | `identical` | `done` (named) | `n/a` | `ObjectTypeIds.kt:88` | |
| Fig. 172 — JT ULP Element data collection (p.203) | Fig. 195 | `structural` + `widths`: **(a)** version `I16` (9.5) vs `U8` (v10); **(b)** 9.5 orders the body *Material Attribute Element × Count → Topology Data → Geometric Data → (`Version Number > 1`) Material Attribute Element Properties × Count → Information Recovery*, while v10 pairs *Material Attribute Element + Material Attribute Element Properties* inside **one** leading loop and drops the `Version Number > 1` guard entirely | `opaque` | `n/a: byte-faithful carry` | — | 9.5 supports versions 1 and 2 |
| Fig. 173 — Topology Data collection (p.204) | Fig. 196 | `structural` (guards): 9.5 guards Regions with `Region Count > 1` and Shells with `Shell Count > 1` (**> 1**, not > 0); the remaining five use `> 0`. v10 Fig. 196 shows the same `> 1` / `> 0` split | `—` | `n/a: byte-faithful carry` | — | the `> 1` guards are unusual enough to be worth a second look if ULP is ever implemented |
| Fig. 174 — Topological Entity Counts (p.205) | Fig. 197 | `identical` — 7 × `I32`, same order as the JT B-Rep's Fig. 103 | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 175 — Combined Predictor Type (p.206) | Fig. 198 | `identical` (checked): `VecI32{Int32CDP2, ePredictorType} : BasicArray`, `U8 : ProcessingType`, `& 0x02` → MapArray + Element Mapping, `& 0x01` → MultiplicityArray + Multiplicity Expansion | `—` | `n/a: byte-faithful carry` | — | this is the ULP's own encoding idiom; nothing in the library resembles it |
| Fig. 176 — Regions Topology Data (p.207) | Fig. 199 | `unchecked` (inventory only) | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 177 — Shells Topology Data (p.208) | Fig. 200 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 178 — Faces Topology Data (p.209) | Fig. 201 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 179 — Loops Topology Data (p.212) | Fig. 202 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 180 — CoEdges Topology Data (p.214) | Fig. 203 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | v10 inserts two extra illustrations here (Figs. 204, 205 "Sample Model with Randomly / Sequentially Assigned Edge Indices") that 9.5 has no counterpart for |
| Fig. 181 — Surface Domain Classification (p.216) | Fig. 206 | `unchecked` (flow chart / classification, not a byte layout) | `—` | `n/a` | — | 9.5's rendering is badly broken in `pdftotext` (only the "Domain Type" caption survives) |
| Fig. 182 — Edges Topology Data (p.218) | Fig. 207 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 183 — Vertices Topology Data (p.220) | **none** | `9.5-only`: 9.5 stores `U8 : Vertex Array Flag` plus, when `& 0x01 != 0`, `VecI32{Int32CDP2, Combined:NULL} : Point Index Difference` and a Recover Point Indices step. **v10 Annex G says outright "Vertices Topology Data is not stored on disk. Instead it is constructed"** — yet v10's own Fig. 196 still draws a `Vertex Count > 0 → Vertices Topology Data` branch | `—` | `n/a: byte-faithful carry` | — | a v10 *internal* contradiction; 9.5 is unambiguous. Finding 8 |
| Fig. 184 — Geometric Data collection (p.221) | Fig. 208 | `unchecked` — `CoordF64 : Translation Vector`, `U32 : Geometric Tabe Flag` [sic], then eleven bit-guarded table blocks (`0x0001` Degree … `0x0400` Weight) | `—` | `n/a: byte-faithful carry` | — | the 9.5 rendering duplicates the `U32 : Geometric Tabe Flag` box; the prose confirms one field |
| Fig. 185 — "U32: Geometric Tabe Flag" / Geometric Entity Counts (p.222) | Fig. 209 | `unchecked`; 9.5's §7.2.8.1.2.1 is textually mangled — the figure caption and the collection name are crossed. The counts themselves are 4 × `I32` (Surface, MCS Curve, PCS Curve, Point) — note the **MCS-before-PCS order**, the reverse of the JT B-Rep's Fig. 104 | `—` | `n/a: byte-faithful carry` | — | `spec unclear` on the caption; the field list is legible |
| Fig. 186 — Degree Table (p.223) | Fig. 210 | `unchecked` — one `VecI32{Int32CDP2, Combined:NULL} : Degree Array` + a recovery step | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 187 — Recover Nurbs Degree (p.224) | Fig. 211 | `unchecked` (recovery flow chart, no bytes) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 188 — Number of Control Points Table (p.225) | Fig. 212 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 189 — Recover Number of Control Points (p.226) | Fig. 213 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 190 — Dimension Table (p.227) | Fig. 214 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 191 — Recover Dimension (p.228) | Fig. 215 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 192 — 3D Unit Vector Table (p.229) | Fig. 216 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 193 — "Recover Dimension" (p.230) | Fig. 217 (same wrong title) | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | both revisions mis-title this one; it recovers 3D unit vectors |
| Fig. 194 — 2D Unit Vector Table (p.231) | Fig. 218 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 195 — Recover 2D Unit Vector (p.231) | Fig. 219 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 196 — 3D MCS Point Table (p.232) | Fig. 220 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 197 — Recover 3D MCS Points (p.233) | Fig. 221 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 198 — Knot Vector Table (p.234) | Fig. 222 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 199 — Recover Knot Vectors (p.235) | Fig. 223 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 200 — 1D MCS Table (p.236) | Fig. 224 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 201 — Recover 1D MCS Table (p.238) | Fig. 225 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 202 — PCS Value Table (p.239) | Fig. 226 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 203 — Recover PCS Value Table (p.240) | Fig. 227 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 204 — Radian Table (p.240) | Fig. 228 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | |
| Fig. 205 — Recover Radian Table (p.241) | Fig. 229 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 206 — Weight Table (p.242) | Fig. 230 | `unchecked` | `—` | `n/a: byte-faithful carry` | — | outside the brief's named list but part of §7.2.8.1.2 — recorded so nothing is unnamed |
| Fig. 207 — Recover Weight Table (p.243) | Fig. 231 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 208 — Material Attribute Element Properties (p.244) | Fig. 232 | `unchecked` — `I32 : Property Count` then `Property Entry × Property Count`. 9.5's box is corrupted ("I32 : Property CountI32 : Entry Count"); the prose says one count | `—` | `n/a: byte-faithful carry` | — | `spec unclear` on the box; prose is legible |
| Fig. 209 — Information Recovery (p.245) | Fig. 233 | `unchecked` (top-level recovery flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 210 — PCS Curve Recovery from Surface Domain (p.246) | Fig. 234 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 211 — MCS Curve Recovery (p.247) | Fig. 235 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 212 — MCS Curve Recovery from Surface Geometry (p.248) | Fig. 236 | `unchecked` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | |
| Fig. 213 — PCS Curve Recovery from MCS Curve and Surface Geometry (p.249) | **none** — v10's Annex G ends at Fig. 236 | `9.5-only` (flow chart) | `n/a: derivation, no bytes` | `n/a` | — | v10 has no figure for this recovery step; whether it dropped the *step* or only the *picture* is `spec unclear` |

### E. §7.2.9 JT LWPA Segment (p.249–252) — **full field-by-field**

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §7.2.9 JT LWPA Segment (p.249) — Table 3 type 24, ZLIB=Yes | §9; Table 6 type 24 | `identical` | `done` (framing) | `n/a: writer emits v10` | `SegmentKind.kt:30`, `LwpaDocument.kt:253` | |
| Fig. 214 — JT LWPA Segment data collection (p.249) | Fig. 99 | `identical` — Segment Header, JT LWPA Element | `done` | `n/a: byte-faithful carry` | `LwpaDocument.kt:186` | |
| §7.2.9.1 Object Type ID `0xd67f8ea8,0xf524,0x4879,0x92,0x8c,0x4c,0x3a,0x56,0x1f,0xb9,0x3a` (p.249) | §9.1, same GUID | `identical`; also listed in 9.5 Annex A Table 11 under "Types Stored Within JT LWPA Segment (Segment Type = 24)" | `done` (named) | `n/a` | `ObjectTypeIds.kt:89`, `:156` | **contradicts a code comment and DESIGN.md** — Finding 1 |
| Fig. 215 — JT LWPA Element data collection (p.250) | Fig. 100 | `widths`, three of them, checked field by field: `I16 : Version Number` (9.5) vs `U8` (v10); `I32 : Surface Count` vs `U32`; `I32 : Analytic Surface Count` vs `U32`. Field order, names and the single `Analytic Surface Count > 0` guard are **identical** | `opaque` (V9 refused by policy) | `n/a: byte-faithful carry` | `LwpaDocument.kt:290` (the V9 refusal), `:308` (the v10 reader) | 9.5: only version 1. Finding 1 |
| Fig. 216 — Analytic Surface Geometry (p.251) | Fig. 101 | `structural` (codec generation only): the six members, their order, their predictors (`Lag1` on Indices, `NULL` on Type) and the four plain `VecF64` arrays are **identical**. The one delta: 9.5 writes `Int32CDP2` (the "Mk. 2" packet, §8.1.2) where v10 writes `Int32CDP` (the third-generation packet, §12.1.1) | `opaque` (V9) / `done` (v10) | `n/a: byte-faithful carry` | `LwpaDocument.kt:76-104` | **a 9.5 LWPA element is 12 lines of work away** — Finding 1 |
| Fig. 217 — Analytic Surface Creation (p.252) | (v10's unnumbered "Analytic Surface Creation" flow chart in §9.1.1) | `identical` (flow chart; both give the same plane/cylinder/cone/sphere/torus consumption rules). 9.5 numbers it, v10 does not | `n/a: derivation, no bytes` | `n/a` | — | the projection it describes is a recorded deferral in DESIGN.md |

### F. §9 Best Practices — §9.10 Brep Face Group Associations (p.300)

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §9.10 Brep Face Group Associations (p.300) | §13's "B-Rep Face Group Associations" (p.208) | `identical` in substance, checked paragraph by paragraph: the implicit scheme, "Tristrips may not cross face groups", the JT B-Rep/ULP sequential region→shell→face rule with the same FG0…FG7 worked example, and the XT B-Rep per-body increasing-identifier rule with the same FG0…FG3 example. Deltas are editorial only: 9.5 "must" → v10 "shall"; 9.5 names "Parasolid identifier" where v10 says "an identifier"; 9.5 adds one sentence v10 dropped — *"If XTBrep contains multiple XT bodies, then the sequence of those XT bodies are fixed across different Parasolid releases and therefore the index of each XT body is implied."* | `opaque` (face groups preserved per triangle, association not interpreted) | `opaque` | `ShapeLodDocument` (`faceGroup` per triangle) | the v10 ledger's row (`SPEC_COVERAGE.md:461`) transfers to 9.5 unchanged; the extra 9.5 sentence is a *stronger* guarantee, not a different one |

### G. Cross-package dependencies (units these figures point at — **package H's rows**, recorded here because my rows are meaningless without them)

| 9.5 unit | v10 counterpart | Delta | Read | Write | Notes |
|---|---|---|---|---|---|
| §8.1.1 Int32 Compressed Data Packet (Figs. 218–220, p.254) | superseded — v10 §12.1.1 `Int32CDP` is third-generation and "supersedes the two similarly-named ones from the Version 9 JT Specification" | `structural`: Mk. 1 starts at `U8 : CODEC Type` with **no leading Value Count**; Mk. 2 and v10 both start with `I32 : Value Count` | `—` | `—` | **the library implements Mk. 2 and gen-3, not Mk. 1** (`Int32Cdp.read` / `.readV10`). 9.5's JT B-Rep *and* Compressed Curve Data both use Mk. 1. Finding 3 |
| §8.1.2 Int32 Compressed Data Packet Mk. 2 (Figs. 221–223, p.259) | v10 §12.1.1 | `structural` (documented in DESIGN.md delta 15) | `done` | `done` | `Int32Cdp.kt:191` — the codec a 9.5 Wireframe/LWPA/ULP element needs |
| §8.1.3 Float64 Compressed Data Packet (Figs. 224–226, p.264) | **no v10 counterpart** — v10 replaced it with `Int64CDP` (§12.1.2) | `9.5-only` | `—` | `—` | **not implemented anywhere in the library** (`grep Float64Cdp` → nothing). Every 9.5 F64 vector in this package routes through it. Finding 2 |
| §8.1.13 Compressed Entity List for Non-Trivial Knot Vector (p.276) | v10 §12.1.13 | `unchecked` (package H) | `done` (v10) | `done` (v10) | reached from Figs. 116 and 8.1.15.1 |
| §8.1.14 Compressed Control Point Weights Data (p.280) | v10 §12.1.14 | `unchecked` (package H) | `done` (v10) | `done` (v10) | reached from Figs. 119 and 8.1.15.2 |
| §8.1.15 Compressed Curve Data (Figs. 238–241, p.282) | v10 §12.1.15, Figs. 150–153 | `structural`, four deltas: (a) the five `VecI32` are `Lag1` in 9.5, `NULL` in v10; (b) they are `Int32CDP` **Mk. 1** in 9.5, gen-3 in v10; (c) "NURBS Curve **Reserved** Fields" → "**Empty** Fields"; (d) Control Points and Knot Vectors are `VecF64{Float64CDP, NULL}` in 9.5, `VecF64{Int64CDP, NULL}` in v10 | `opaque` (V9) / `done` (v10) | `n/a: byte-faithful carry` | the whole 9.5 wireframe curve payload lives here. Findings 2, 3, 9 |
| §8.1.16 Compressed CAD Tag Data (Figs. 242–243, p.285) | v10 §12.1.16, Fig. 154 | `structural` + `widths`: (a) version `I16` vs `U8`; (b) **9.5 has an `I32 : CAD Tag Count` field and a `CAD Tag Count > 0` guard that v10 removed entirely**; (c) all vectors are `Int32CDP2, Lag1` in 9.5 vs `Int32CDP` (no predictor named) in v10; (d) **Type-2 (64-bit) tags are two `VecI32` halves in 9.5 (§8.1.16.1 "First I32" / "Second I32") but one `VecI64{Int64CDP}` in v10** | `opaque` (V9) / `done` (v10) | `n/a: byte-faithful carry` | 9.5's Fig. 242 is *also* internally broken: the box under "If Type-1 CAD Tags exist" reads `I16:Version Number`, while the prose immediately below defines `VecI32{Int32CDP2, Lag1} : CAD Tags Type-1`. **The prose is authoritative.** Finding 3 |

**Row count:** 84 ledger rows (77 in A–F, 7 dependency rows).
Deltas across A–F: `identical` 31 · `widths` 3 · `structural` 12 · `9.5-only` 2 · `unchecked` 36 (35 of them ULP by design, per the brief) · `n/a: illustration/derivation` counted inside the above.

---

## Part 2 — findings

### 1. **The 9.5 reference *does* document a JT LWPA Element — the code and DESIGN.md both say it does not.** (correction + gap; cost: `trivial` for the correction, `small` for the decode)

`LwpaDocument.kt:288-290` refuses the JT 9 generation with this reason:

> *"The v9.5 reference documents no LWPA element at all (the segment type exists in its Table 3, the
> element does not), so only the v10 generation decodes; V9 carries opaquely."*

`DESIGN.md:1036-1038` and `SPEC_COVERAGE.md:258` repeat it.

**Both statements are false.** JT 9.5 Rev-D **§7.2.9.1 (p.249–250)** gives the JT LWPA Element its Object
Type ID, its prose and **Fig. 215**, and **Annex A Table 11 (p.305)** lists it under *"Types Stored Within
JT LWPA Segment (Segment Type = 24)"* with exactly the GUID `ObjectTypeIds.kt:89` already carries.

The *behaviour* stays defensible — a 9.5 LWPA element read with the v10 codec **would** misread, because
the widths and the CDP generation differ (Fig. 215 vs Fig. 100: `I16`/`I32`/`I32` vs `U8`/`U32`/`U32`;
Fig. 216 vs Fig. 101: `Int32CDP2` vs gen-3 `Int32CDP`). But the *recorded reason* is wrong, and it is the
reason that makes the deferral look permanent when it is in fact **the cheapest decode in this whole
package**: three width swaps and one codec swap over an otherwise byte-identical figure.

- Lenient reader: dispatch on `LsgGeneration`, read `I16`/`I32`/`I32` + `Int32Cdp.read` for V9.
- Strict writer: emits v10 only (`n/a: writer emits v10`); a V9 LWPA element re-serializes from what it read.
- Model must remember: nothing extra — generation is already on the document (`LwpaDocument.generation`),
  and `Int32Cdp` preserves every wire field of whichever packet it read.
- Fixtures cannot tell the two readings apart: **no fixture carries an LWPA segment at all**, in either
  generation. The 9.5 decode would be spec-derived exactly as the v10 one already is.

### 2. **The Float64 Compressed Data Packet (§8.1.3) does not exist in the library, and every 9.5 F64 vector in this package needs it.** (gap; cost: `large`)

v10 replaced 9.5's `Float64CDP` with `Int64CDP` ("each deserialized 64 bit integer number should be
converted to bit wise equivalent 64 bit floating number"). The library implements `Int64Cdp`
(`encoding/Int64Cdp.kt`) and **nothing** named Float64 — `grep -r 'Float64Cdp\|Float64CDP' src/` returns
zero hits.

9.5 routes the following through `Float64CDP`, all inside this package's range:

- Fig. 120 NURBS Surface Control Points, Fig. 121 U/V Knot Vectors (JT B-Rep);
- Fig. 123 Trivial Box Loop Corner Coords (JT B-Rep);
- §8.1.15 NURBS Curve Control Points and Knot Vectors — **which is the Wireframe Rep's entire curve
  payload**, via Fig. 131.

So the deferred "JT 9 Wireframe Rep Element" package is not the small width-and-predicate job the
DESIGN.md entry implies. It needs a whole new CODEC family (Figs. 224–226) *plus* the Mk. 1 Int32 packet
(finding 3). That is the honest cost, and it should be recorded against the deferral.

### 3. **The 9.5 generation uses *three* different CDP framings across this package, and the library implements only two of them.** (gap; cost: `small` for Mk. 1, none for the rest)

| 9.5 collection | packet | library |
|---|---|---|
| JT B-Rep topology + geometry (Figs. 106–123) | `Int32CDP` **Mk. 1** (§8.1.1) | **absent** |
| Compressed Curve Data (§8.1.15) — the wireframe curve payload | `Int32CDP` **Mk. 1** | **absent** |
| Wireframe Rep Element's two index vectors (Fig. 130) | `Int32CDP2` **Mk. 2** (§8.1.2) | `Int32Cdp.read` ✓ |
| Compressed CAD Tag Data (§8.1.16) | `Int32CDP2` **Mk. 2** | `Int32Cdp.read` ✓ |
| JT ULP (Figs. 175–207) | `Int32CDP2` **Mk. 2** | `Int32Cdp.read` ✓ |
| JT LWPA (Fig. 216) | `Int32CDP2` **Mk. 2** | `Int32Cdp.read` ✓ |

Mk. 1 and Mk. 2 are not variants of one framing: Mk. 1 (Fig. 218) begins at `U8 : CODEC Type` with **no
leading `I32 : Value Count`**, while Mk. 2 (Fig. 221) and v10's gen-3 both begin with the count. A reader
that guessed wrong desynchronizes on the first field.

This also **corrects `WireframeDocument.kt:201-204` and DESIGN.md's deferral entry**, which both say the
JT 9 Wireframe Rep uses *"the JT 9 ('Mk. 2') CDP packets **throughout the curve data**"*. It does not: the
element's own two vectors are Mk. 2, and the curve data underneath is **Mk. 1**. A single-codec
implementation would fail.

### 4. **`BrepOpacityTest` asserts a trailing byte pattern that no revision makes normative.** (contradiction with the document, latent; cost: `trivial`)

The proof test asserts, for every precise-geometry segment:

```kotlin
assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0), scan.trailing.toByteArray(),
    "$id: unexpected bytes after the element list")
```

Neither 9.5 Fig. 101 nor v10 Fig. 161 shows *any* trailing Property Table after the JT B-Rep Element —
the segment is Segment Header + one Element. The six bytes are a producer convention (NX 10.5), promoted
to a hard assertion. A 9.5 file whose B-Rep segment carries a **non-empty** property table, or none, fails
the doctrine's own proof test — on a file that is perfectly conformant.

The same test also hard-codes `assertEquals(9, element.objectBaseType, …)` for ULP and JT B-Rep. 9.5
Table 4 does define base type 9 as `JtBase (none)`, and §7.2.3.1 / §7.2.8.1 name no other base type, so
this one is *probably* safe — but it is unverified for those two kinds in either generation (no fixture
carries them). Loosening the trailing-bytes assertion to "a Property Table, or nothing" and keeping the
byte-for-byte reconstruction check would preserve the proof's value without the brittleness.

### 5. **Predictor deltas in the JT B-Rep NURBS surface collections that no note records.** (gap in the record; cost: `trivial` to record, `large` to act on)

9.5 Figs. 115, 117, 118 use `Lag1` on every `VecI32`; v10 Figs. 175, 177, 178 use `NULL` on the same
fields. The topology figures (106–113 / 166–173) kept `Lag1`/`Xor1` in both. So v10 changed the predictor
for the *surface geometry* vectors only — the same shape of change Revision B later made to the two
wireframe index vectors (DESIGN.md delta 39), which suggests it is systematic rather than a typo. Worth
recording alongside delta 39 so the pattern is visible if JT B-Rep is ever opened.

Also editorial across the same figures: 9.5's "NURBS Surface/Curve **Reserved** Fields" is v10's "**Empty**
Fields", pointing at v10's `Empty Field` convention (§13). Same bytes, different name.

### 6. **9.5 does not deprecate JT B-Rep; v10 does.** (confirmation, with a caveat; cost: none)

`SPEC_COVERAGE.md:242` records the JT B-Rep Element Write column as
`n/a: deprecated, "read only for application creation" (§8.1)`. That is v10's language. The 9.5 reference
carries **no** deprecation of any kind (§7.2.3 describes it as a first-class precise-geometry format,
alongside XT B-Rep). The library's fate is unchanged — opaque either way — but the *justification* is
generation-specific and should read "deprecated in v10; a live format in 9.5, opaque by doctrine in both".

### 7. **9.5's JT B-Rep Element has a `Version Number > 4` guard it can never satisfy.** (`spec unclear`, informational; cost: none)

Fig. 102 guards `U32 : CAD Tags Flag` (and hence all of §7.2.3.1.6) on `Version Number > 4`, while the
field description says:

> *"Version Number is the version identifier for this JT B-Rep Element. Only version number 0x0001 is
> currently defined."*

So in a conforming 9.5 file the CAD Tag branch is **unreachable**, and §7.2.3.1.6 B-Rep CAD Tag Data
describes bytes that cannot occur. v10 Fig. 162 carries the identical guard with a `U8` version and the
same silence. Either real 9.5 producers write versions above 4 (making the "only 0x0001" sentence stale),
or the guard is vestigial. Nothing in the library depends on the answer today, but a future JT B-Rep
reader must resolve it from bytes rather than the figure — exactly the "lenient when reading" case.

### 8. **v10 contradicts itself on ULP Vertices Topology Data; 9.5 does not.** (correction to the v10 record; cost: none today)

9.5 Fig. 183 (p.220) stores it on disk: `U8 : Vertex Array Flag`, and when `& 0x01 != 0`,
`VecI32{Int32CDP2, Combined:NULL} : Point Index Difference`. v10 Annex G's Topology Data (Fig. 196) still
draws a `Vertex Count > 0 → Vertices Topology Data` branch, but its Vertices Topology Data section says
flatly *"Vertices Topology Data is not stored on disk. Instead it is constructed (G.1.4 Information
Recovery)"* and gives no figure. A ULP implementation would have to read the 9.5 form and skip the v10
one — and v10's own figure would mislead it.

### 9. **Confirmations the 9.5 document now upgrades from fixture-evidence to citation.** (cost: none — these are free)

- **DESIGN.md delta 38** ("Figure 104's Version Number box says `I16`; the field is one byte … recorded as
  a figure error rather than a version delta, because the v9.5 reference's Figure 130 really does show
  `I16`"). **Confirmed outright, and strengthened.** 9.5 §7.2.5.1 Fig. 130 (p.160) shows `I16 : Version
  Number` in the box *and* its field description is headed `I16 : Version Number` — 9.5 is
  **self-consistent** at I16, where v10 is self-*inconsistent* (box `I16`, prose `U8`). So the field
  genuinely narrowed with the generation and the v10 figure was simply not updated; this is now a
  citation, not an inference. This settles the `I16`-vs-`I32` question the brief asked about: **there is
  no `I32` reading anywhere** — the question is `I16` (9.5, both box and prose) versus `U8` (v10 prose,
  fixture-confirmed on five bodies).
- **DESIGN.md delta 39** ("The JT 9 generation does use Lag1"). **Confirmed**: 9.5 Fig. 130's two vectors
  are `VecI32{Int32CDP2, Lag1}` in the box, and the field descriptions confirm the Int32 CODEC.
- **`SegmentKind.kt`'s header comment** ("The v9.5 table is a subset with the same codes and the same
  compression column; the delta is only which algorithm the compressed types use"). **Confirmed exactly**
  against 9.5 Table 3 (p.29): types 1,2,3,4,6–16,17,18,20,24 with the identical ZLIB column; v10 adds only
  30 (MultiXT) and 32 (STEP). One tiny staleness: `UndefinedSegmentTypes.kt` cites *"v9.5 Rev A Table 3"*
  — we now have **Rev-D**, and Rev-D likewise defines neither type 23 nor type 31, so the claim holds and
  the citation can be upgraded.
- **Element framing and header for 9.5** (§7.1.3.2.1/.2, Figs. 8–9, p.31): `GUID : Object Type ID`,
  `UChar : Object Base Type`, `I32 : Object ID`, base type 9 = `JtBase`. Identical in shape to v10, which
  is why the opaque carry, the `baseType != 9` refusals in `WireframeDocument.kt:234` and
  `LwpaDocument.kt:313`, and `BrepOpacityTest`'s frame-reconstruction all transfer to 9.5 unchanged.
- **All five Object Type ID GUIDs in this package are byte-identical between 9.5 Annex A Table 11 and
  v10 Annex A** (JT B-Rep, XT B-Rep, Wireframe Rep, JT ULP, JT LWPA), and all five match `ObjectTypeIds.kt`
  lines 70, 86, 87, 88, 89. **A 9.5 B-Rep, ULP or LWPA segment is correctly framed and correctly named
  today.** That was the framing question the brief posed, and the answer is clean.

---

## What I skipped, and why

- **The ULP table collections (Figs. 186–207) were not diffed field by field.** The brief said not to, and
  the cost estimate did not need it. What I did read: the segment framing, the element (Fig. 172), the
  topology-data envelope (Fig. 173), the counts (Fig. 174), the Combined Predictor Type idiom (Fig. 175),
  the Vertices anomaly (Fig. 183), the geometric-data envelope with its eleven bit guards (Fig. 184), one
  representative table (Fig. 186 Degree Table) and the properties collection (Fig. 208). **Cost estimate
  for JT ULP: `large`, and larger than the figure count suggests** — 43 figures, of which ~24 are byte
  layouts and ~17 are normative *recovery algorithms* that must be implemented for the data to mean
  anything (unlike every other segment in this library, ULP's on-disk form is deliberately incomplete;
  §7.2.8.1.4 is explicit that "derivative information" is reconstructed, not read). It needs the Combined
  Predictor Type machinery, the eleven quantized geometric tables with their per-table recovery, the
  surface-domain classification, and the PCS/MCS curve recovery chain — on top of the Mk. 2 CDP the
  library already has. Estimate: comparable to §7 Shape LOD in total effort, with **no fixture** to verify
  any of it, and no consumer asking. It is the right thing to leave as `—`.
- **The JT B-Rep NURBS collections' guards were checked at figure level, not traced.** Rows saying
  `identical` in §A were compared member-by-member against their v10 counterparts (names, order,
  codec token, predictor); rows saying `structural` enumerate the delta. No JT B-Rep row is marked
  `identical` on inspection of the picture alone.
- **§8.1.13 / §8.1.14 internals** are package H's units; I recorded the pointers and the two deltas that
  are visible from my side (`Float64CDP` and Mk. 1) rather than diffing them.
