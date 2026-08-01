# PACKAGE E — Shape LOD Segment (JT 9.5 Rev-D §7.2.2, Figures 81–100)

Range: 9.5 §7.2.2 in full (pp. 109–134), Figures 81 through 100, including §7.2.2.2 Primitive
Set Shape Element and every `Data collection` / `Data Collection` sub-layout in that range.

`Compressed Vertex Coordinate / Normal / Colour / Texture / Flag Array`, the Int32CDP /
Int32CDP2 / Float64CDP packet layouts, the probability context, the bitlength/arithmetic/
chopper CODECs and the Uniform Quantizer belong to **PACKAGE H**. Where a figure of mine
depends on them the dependency is named in the row and not analyzed.

**New empirical evidence produced by this pass.** Beyond the two documents I re-parsed the two
9.5 fixtures directly (a standalone Python framing/decoder, `scratchpad/skip.py` + `dec.py`) to
settle three questions the documents alone could not: the identity of the 12-byte tail on every
JT 9 shape body, the predictor on the 9.5 polyline index lists, and the exact extent of the
"if Polyline Shape" guard. All three are now decided by bytes **and** hashes, not by inference.
Details in findings 1–3. Every claim below marked *(fixture)* was checked on
`fixtures-local/RB___E_01955.jt` (12 tri-strip bodies) and/or `fixtures-local/KR360-1.jt`
(11 tri-strip + 5 polyline + 1 point-set bodies).

---

## Part 1 — ledger

`Read`/`Write` describe the **library's JT 9 path today**. `Write` is split by the doctrine: the
authoring writer (`write/ShapeAuthoring.kt:416`) hard-codes `LsgGeneration.V10`, so for every
9.5 unit the honest authoring entry is `n/a: writer emits v10`; the achievable target is
byte-faithful re-serialization of what was read, which is what `done (re-ser)` means.

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| Fig. 81 — Shape LOD Segment data collection (p.109) | Fig. 80 (p.92) | `identical` — Segment Header + Shape LOD Element, both boxes, same order | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:69` | 9.5 adds no Property Table box; the trailing 6-byte empty Property Table the library reads after the element list is *not* in either figure (both fixtures carry it — `trailingBytes: 6`). Pre-existing finding, unchanged. |
| §7.2.2.1 Shape LOD Element (p.109), prose | v10 §7.1.3 (p.92) | `structural` — 9.5 defines 6 concrete LOD element types (Null, Point Set, Polyline Set, Primitive Set Shape, Tri-Strip Set, Vertex Shape); v10 adds **Polygon Set LOD Element** (Fig. 84, GUID `0x10dd109f`). 9.5's Annex-A table (p.303) lists exactly those 6. 9.5 defines a Polygon Set Shape **Node** Element (Fig. 35, p.52) with *no* LOD-side element — see finding 9 | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:164`, table at `:186-191` | JT 9 dispatch admits only tri-strip + null; polyline / point set / primitive set → `ElementLayoutUnverified` + opaque. |
| Fig. 82 — Base Shape LOD Element data collection (p.110) | none (v10 folds Base Shape LOD Data into Fig. 85) | `structural` — 9.5 has an explicit abstract element figure (Logical Element + Base Shape LOD Data); v10 has none, its Fig. 85 nests Base Shape LOD Data as the first member of Vertex Shape LOD Data | done (as realized) | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:245`, `:285` | Object Type ID `0x10dd10a4…` is given in §7.2.2.1.1 but absent from 9.5's Annex-A table — abstract, never framed standalone. Library has no constant for it; correct. |
| Fig. 83 — Base Shape LOD Data collection (p.110) | Fig. 86 (p.97) | `widths` — `I16 : Version Number` (9.5, only 0x0001 valid) vs `I8 : Version Number` (v10) | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:285` (read), `:307` (write) | *(fixture)* value 1 in all 29 JT 9 bodies. |
| Fig. 84 — Vertex Shape LOD Element data collection (p.110) | none (v10 has no element figure; §7.1.4.1 + Fig. 85) | `structural` — 9.5: Logical Element + **Base Shape LOD Data** + Vertex Shape LOD Data; v10 moves Base Shape LOD Data inside Vertex Shape LOD Data | done (as realized in the tri-strip reader) | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:283-289` | The derived-element figures 93/94/95 **omit** the Base Shape LOD Data box that this figure requires — see finding 5. |
| Fig. 85 — Vertex Shape LOD Data collection (p.111) + the U64 Vertex Bindings bit table (pp.111-112) | Fig. 85 (p.95) + Table 48 (pp.95-96) | `structural` + `widths` — (a) version `I16` (9.5) vs `I8` (v10); (b) 9.5 does **not** contain Base Shape LOD Data (it sits one level up, Fig. 82/84), v10 does; (c) 9.5 has **no nested Logical Element Header** between the bindings and the TopoMesh collection, v10 does (DESIGN.md delta 27) — *(fixture)* confirmed absent in all 29 JT 9 bodies; (d) the bit table is field-for-field identical (bits 1-3 coords, 4 normal, 5-6 colour, 7 flags, 9-40 texcoord 0-7, 64 aux); v10 adds cosmetic "Bits 41-62 Unused"/"Bit 63" rows | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:286-287`; v10 nested header `:451` | Both figures branch the same way: `If Tri-Strip Set Shape LOD Element` → TopoMesh Topologically Compressed LOD Data, else TopoMesh Compressed LOD Data. *(fixture)* the point-set body takes the *Compressed* branch, so "Tri-Strip Set" is literal. JT 9 reads only the topologically-compressed branch. |
| Fig. 86 — TopoMesh LOD Data collection (p.112) | Fig. 88 (p.98) | `widths` — `I16 : Version Number` (9.5; 0x0001 **and 0x0002** valid) vs `U8`; `I32 : Vertex Records Object ID` vs `U32` (same width, signedness label only) | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:288` | *(fixture)* version is **2** in all 29 JT 9 bodies. Neither 9.5 nor v10 says what version 2 changes here; the figure shows no branch and byte consumption says nothing is added at this level. |
| Fig. 87 — TopoMesh Compressed LOD Data collection (p.113) — the 9.5 figure caption misreads "TopoMesh LOD Data collection", duplicating Fig. 86's title | Fig. 87 (p.97) | `structural` — 9.5: TopoMesh LOD Data, `I16 : Version Number`, then **`if version >= 2` → TopoMesh Compressed Rep Data V2, else V1**; v10: TopoMesh LOD Data, `U8 : Version Number`, one unversioned TopoMesh Compressed Rep Data | — (JT 9 body is opaque) | opaque (byte-faithful carry); n/a: writer emits v10 | — | *(fixture)* version 2 in all 6 polyline/point bodies → V2 selected. This is the gate that finding 1 turns on. |
| Fig. 88 — TopoMesh Topologically Compressed LOD Data collection (p.113) | Fig. 91 (p.102) | `widths` on its face (`I16` vs `U8` version) — but see finding 1: §7.2.2.1.2.4 declares version **0x0002** valid while the figure shows **no** version branch, and the fixture proves version 2 appends 10 bytes the figure does not show | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:289`; the undocumented tail at `:291-292`, modelled as `reservedVersion`/`reservedBindings` in `ShapeLodElements.kt:250-253` | *(fixture)* version 2 in all 23 tri-strip bodies. |
| Fig. 89 — Topologically Compressed Rep Data Collection (p.115) + composite-hash pseudo-code (pp.115-116) | Fig. 92 (p.103) + pseudo-code (pp.104) | `structural` — the 8th attribute-mask context: 9.5 stores **three** packets (30 LSBs / 30 next MSBs / 4 MSBs), v10 **two** (32 LSBs / 32 MSBs). Everything else is field-for-field identical (8 face-degree packets, valences, groups, Lag1 flags, 8 mask packets, `VecU32` high-degree masks, Lag1 split-face syms, split-face positions, `U32` composite hash, then the vertex records). Packet generation differs: 9.5 `Int32CDP2` (§8.1.2, Mk. 2) vs v10's third-generation `Int32CDP` — PACKAGE H | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:328`; hash `:350-364`; masks reassembled `:760-776` | Confirms DESIGN.md delta 20 outright — finding 4. Predictor annotations in the figure (`{Int32CDP2}` = NULL except `{Int32CDP2, Lag1}` on Vertex Flags and Split Face Syms) confirm DESIGN.md delta 18 — finding 8. |
| Fig. 90 — Topologically Compressed Vertex Records data collection (p.118) | Fig. 93 (p.106) | `structural` (minor) — identical left column (`U64` bindings, Quantization Parameters, `I32` num topological vertices, `if > 0` `I32` num vertex attributes) and identical binding-guarded right column, **except** v10 adds an eighth guarded box `if AuxField Bindings → Compressed Auxiliary Fields Array`; 9.5 has none here (aux lives in Fig. 92's V2 tail instead) | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:393`; binding refusal mask `:326` | Depends on PACKAGE H for all five array layouts and on `Quantization Parameters` (9.5 §7.2.1.1.1.10.2.1.1, p.47 — PACKAGE B/C; v10 Fig. 90). JT 9 decodes coordinates + normals only; colour/texcoord/flag/aux bindings refuse with a note (`UNSUPPORTED_BINDING_MASK = ~0xF`). |
| Fig. 91 — TopoMesh Compressed Rep Data V1 data collection (p.119) + FGPV and unique-vertex-map hash pseudo-code (pp.119-120) | Fig. 89 (p.99) | `structural` — **six** deltas, all field-level: (1) the `I32 Number of Face Group List Indices` count **and** the Face Group List Indices array are guarded `if Polyline Shape` in 9.5, unconditional in v10; (2) the three index lists are `VecI32{Int32CDP2}` — **NULL predictor** — in 9.5, `VecI32{Int32CDP, Lag1}` in v10; (3) 9.5 adds `I32 : Number of Unique Vertex Coordinates` inside the `if number records > 0` branch, absent in v10; (4) counts are `I32` in 9.5, `U32` in v10 (same width); (5) the FGPV hash pseudo-code guards the face-group term `if (bLineStrip)` in 9.5, unguarded in v10; (6) v10 adds the `if AuxField Bindings → Compressed Auxiliary Fields Array` box, 9.5 does not (V2 carries aux) | — (JT 9 body is opaque) | opaque (byte-faithful carry); n/a: writer emits v10 | v10 twin at `ShapeLodDocument.kt:649`; the JT 9 reader does not exist | Layout **fully established and byte-verified** by this pass — finding 2. Unique Vertex Coordinate Length List is `VecI32{Int32CDP2}`, NULL-predicted, hash `hash32(list, nUniqVtx)` — *(fixture)* verified on 4 bodies. Depends on PACKAGE H for the coordinate array. |
| Fig. 92 — TopoMesh Compressed Rep Data V2 data collection (p.122) + Field Type table (p.123) + Auxiliary Data Hash pseudo-code (pp.123-124) | none as a collection; v10 relocates the payload to §12 Fig. 143 *Compressed Auxiliary Fields Array* (p.171) | `structural` / effectively `9.5-only` — 9.5: V1 + `I16 : Version Number` + `U64 : Vertex Bindings` + `if aux binding` { `U32` field count, then per field: `GUID` id, `U8` field type (46-entry type/components table), and a type-branched triple of `VecU32{Int32CDP2}` arrays (Exponents / Upper Mantissae / Lower Mantissae for floats; U32_0 / U32_1 / U32_2 for integers), `I32` Auxiliary Data Hash }. v10's Fig. 143 restructures the per-field record entirely: adds `U8 Unused Field`, `U8 Number of Quantization Bits`, `U8 Number of Steps`, a quantized/lossless branch with `Uniform Quantizer Data`, and reduces the arrays to LSW/MSW pairs | partial (only the 10-byte version+bindings prefix is consumed, as untyped "reserved" fields) | done (re-ser) for the prefix; n/a: writer emits v10 | `ShapeLodDocument.kt:291-292` | **This is the collection that explains the JT 9 "reserved" tail** — finding 1. The aux block itself is unexercised: *(fixture)* bit 64 is clear in all 29 bodies. The v10 aux array is PACKAGE H (§12); the 9.5 aux block is mine, and it is *not* the same layout. |
| Fig. 93 — Tri-Strip Set Shape LOD Element data collection (p.125) | Fig. 81 (p.92) | `structural` + `widths` — trailing version `I16` (9.5) vs `U8` (v10); 9.5's figure omits the Base Shape LOD Data box (finding 5); v10 additionally carries the nested Logical Element Header inside Fig. 85 (delta 27); and the real 9.5 wire carries the Fig.-92 V2 tail the figure does not show (finding 1) | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:283`, model `ShapeLodElements.kt:238` | Object Type ID identical in both revisions (`0x10dd10ab…`). |
| Fig. 94 — Polyline Set Shape LOD Element data collection (p.125) | Fig. 82 (p.93) | `widths` at element level (trailing version `I16` vs `U8`), plus everything inherited from Figs. 85/87/91/92 | — (opaque with `ElementLayoutUnverified`) | opaque (byte-faithful carry); n/a: writer emits v10 | refused at `ShapeLodDocument.kt:186-191` | Issue #12. Object Type ID identical (`0x10dd10a1…`). 5 bodies in `KR360-1.jt`. Layout now settled — finding 2. |
| Fig. 95 — Point Set Shape LOD Element data collection (p.126) | Fig. 83 (p.94) | `widths` at element level (`I16` vs `U8`), plus the Fig.-91 deltas — **and the point set is not a "Polyline Shape"**: no Face Group count, no Face Group array, and the FGPV hash covers only the primitive and vertex lists | — (opaque with `ElementLayoutUnverified`) | opaque (byte-faithful carry); n/a: writer emits v10 | refused at `ShapeLodDocument.kt:186-191` | Issue #12. Object Type ID identical (`0x98134716,0x0011,…`). 1 body in `KR360-1.jt`; layout verified byte-exact and hash-exact — finding 3. |
| Fig. 96 — Null Shape LOD Element data collection (p.127) | Fig. 94 (p.107) | `widths` — `I16 : Version Number` (9.5) vs `U8` (v10); `BBoxF32 : Untransformed BBox` identical | partial (spec-derived, no fixture) | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:255`, writer `:266` | The library's JT 9 reading (`I16`) matches 9.5 §7.2.2.1.6 exactly — a confirmation, not a change. Still fixture-less: neither 9.5 fixture nor the NIST file carries one. |
| Fig. 97 — Primitive Set Shape Element data collection (p.128) | Fig. 95 (p.108) | `structural` + `widths` — 9.5: LEH, `I16` Version, `I32` Texture Coord Binding, `I32` Color Binding, `I32` Texture Coord Gen Type, `I16` Version (=1 or **=2**), `I32` Bits Per Vertex, branch on `Bits Per Vertex == 0`. v10: LEH, `U8` Base Shape Version, `U8` Base PrimSet Version, **`U64` Vertex Bindings** (replacing the two `I32` binding enums), `I8` Texture Coord Gen Type, `U8` Version (=1 only), `I32` Bits Per Vertex, same branch. So: 2 version fields vs 3; two `I32` binding enums vs one `U64` bit field; Tex Coord Gen Type `I32`→`I8` and it moves after the bindings | opaque | opaque (byte-faithful carry); n/a: writer emits v10 | refused at `ShapeLodDocument.kt:186-191` | Object Type ID identical (`0xe40373c2…`). 9.5's *prose* order (Version, TexCoordBinding, ColorBinding, Version, BitsPerVertex, TexCoordGenType) contradicts its own figure, which puts Texture Coord Gen Type before the second version — finding 10. 9.5's second version admits a "Version-2 Format" whose content no revision documents — finding 11. |
| §7.2.2.2.1 / Fig. 98 — Lossless Compressed Primitive Set Data collection (p.129) + Table 5 (p.130) + Table 6 (p.130) | Fig. 96 (p.109) + Tables 51/52 (pp.110) | `identical` in structure — `I32` Uncompressed Data Size, `I32` Compressed Data Size, then `Compressed Data Size > 0` → `U8[size]` Compressed Primitive Data, `< 0` → `U8[abs(size)]` Primitive Data. Element table (reserved I32, params1 CoordF32, params2 DirF32, params3 Quaternion, colour RGB, type I32; types 0-4) identical; params# interpretation table identical. **Only delta: the compression method — ZLIB in 9.5, LZMA in v10** | opaque | opaque; n/a: writer emits v10 | — | Stride and primitive count are derived from the two size fields; per-primitive stride is 4+12+12+16+12+4 = 60 bytes. |
| §7.2.2.2.2 / Fig. 99 — Lossy Quantized Primitive Set Data collection (p.131) | Fig. 97 (p.111) | `widths` — `U8 : Bits Per Color` (9.5) vs `U32 : Bits Per Colour` (v10). Structure otherwise identical: `I32` Primitive Count; `Primitive Count > 4` → { Bits Per Color, Compressed params1, Compressed params3, Compressed params2, `if Color Binding != 0` Compressed Colors, `VecI32{Int32CDP,Lag1}` Compressed Types }; else Primitive-Count repeats of { Quaternion params3, CoordF32 params1, DirF32 params2, `if Color Binding != 0` RGB Color, `I32` Type } | opaque | opaque; n/a: writer emits v10 | — | The `Color Binding` the guard tests is 9.5's `I32 Color Binding` field; in v10 it is the colour bits of the `U64 Vertex Bindings`. Uses **`Int32CDP` (Mk. 1, §8.1.1)**, not the `Int32CDP2` the rest of §7.2.2 uses — finding 12. |
| §7.2.2.2.2.1 / Fig. 100 — Compressed params1 data collection (p.133) | Fig. 98 (p.113) | `identical` — `VecF32 : Quantization Range Min/Max Pairs` (I32 count + F32 values; length 2·num_ordinates), `VecI32{Int32CDP, Lag1} : params1 Codes`. Packet generation differs (Mk. 1 vs v10's third generation) | opaque | opaque; n/a: writer emits v10 | — | Depends on PACKAGE H for `Int32CDP` Mk. 1 and the Uniform Quantizer. |
| §7.2.2.2.2.2 Compressed params3 (p.133) | v10 §7.2.2.2 (p.112) | `identical` — "the storage format … is exactly the same as that documented in Figure 100"; 4 ordinates ⇒ VecF32 length 8 | opaque | opaque; n/a: writer emits v10 | — | Both revisions carry the same copy-paste error ("Since params1 is of type 'Quaternion'"). |
| §7.2.2.2.2.3 Compressed params2 (p.133) | v10 §7.2.2.3 (p.113) | `identical` — same as Fig. 100; 3 ordinates ⇒ VecF32 length 6 | opaque | opaque; n/a: writer emits v10 | — | — |
| §7.2.2.2.2.4 Compressed Colors (p.134) | v10 §7.2.2.4 | `identical` — same as Fig. 100; 3 ordinates; quantized with **Bits Per Color**, not Bits Per Vertex; present only when Color Binding != 0 | opaque | opaque; n/a: writer emits v10 | — | — |

**Counts.** 24 normative units analyzed: `identical` 6, `widths` 7, `structural` 10,
`9.5-only` 1 (Fig. 92 in substance), `unchecked` 0. Rows marked "structural + widths"
(Figs. 85, 93, 97) are counted as `structural`. Two units (Figs. 82, 84) are abstract element
figures with no v10 counterpart and are counted under `structural`.

---

## Part 2 — findings

### 1. The JT 9 "reserved 12-byte tail" is the TopoMesh Compressed Rep Data **V2** tail. DESIGN.md delta 14's open question is answered. *(severity: high — it converts an untyped mystery into a typed, extensible field; cost: small)*

DESIGN.md delta 14 records that every JT 9 tri-strip body ends with `I16 + U64 + I16`, that the
final `I16` is the element's own version (Fig. 93), and that "the preceding I16+U64 (values 1
and a repeat of the bindings in all 12 bodies) are reserved fields whose semantics no spec
revision at hand documents". `ShapeLodElements.kt:250-253` names them `reservedVersion` /
`reservedBindings`.

They are not reserved. 9.5 §7.2.2.1.2.8 / **Figure 92** defines *TopoMesh Compressed Rep Data
V2* as exactly:

> TopoMesh Compressed Rep Data V1 → `I16 : Version Number` → `U64 : Vertex Bindings` →
> *if auxiliary vertex field binding* { `U32 : Number of Auxiliary Fields`, per field: GUID,
> field type, data arrays, `I32 : Auxiliary Data Hash` }

and §7.2.2.1.2.3 / **Figure 87** gates it: *TopoMesh Compressed LOD Data* reads TopoMesh LOD
Data, then `I16 : Version Number`, then **V2 if that version ≥ 2, else V1**.

The polyline and point-set bodies of `KR360-1.jt` prove the gate directly. I parsed all six
(1 point set + 5 polylines) to exact byte consumption:

```
451576 poly  cnt=347 lossless  bytes left after the coordinate array: 12
  -> 01 00 | 02 00 00 00 00 00 00 00 | 01 00
     V2 version=1   V2 vertex bindings=0x02   element version=1
```
— identical on all six, and their TopoMesh Compressed LOD Data version is **2** in every case.
Bit 64 (Auxiliary Vertex Field Binding) is clear in the repeated `U64`, so the aux block is
absent and the V2 contribution is exactly those 10 bytes. The document accounts for every byte.

Now the tri-strips. Their container is *TopoMesh **Topologically** Compressed LOD Data*
(Fig. 88), whose §7.2.2.1.2.4 prose says "Version number `0x0001` **and `0x0002`** are
currently the only valid values" — while Figure 88 shows **no** version-dependent content.
*(fixture)* that version is **2** in all 23 tri-strip bodies of both files, and each is followed
by the same 10 bytes with the same values (`I16` 1, `U64` = the shape's own bindings). 9.5 §9.4
*Local Version* states the append convention that makes this the only consistent reading:

> "The standard convention followed by each data collection … is to write the data from each
> local version in order. This allows readers to read up to the maximum local version they
> support and then use the segment length … to skip over any data they may not understand."

**Conclusion.** The 10 bytes are the V2 auxiliary-vertex-field extension, applied to the
topologically compressed representation, gated on the Fig.-88 version ≥ 2. Figure 88 is
incomplete: it is missing the branch its own prose implies and its own sibling (Fig. 87)
documents.

**Doctrine consequences.** *Lenient reader*: gate the tail on the Fig.-88/87 version ≥ 2 rather
than on presence; keep a fall-back to "no tail" for a version-1 file (none exists in either
fixture — a version-1 body is the fixture we lack). *Strict writer*: emit version 2 with the
tail, or version 1 without it, never a mismatch. *Model must remember*: the container version
(1 vs 2) and, when 2, the V2 version, the V2 bindings **and** the aux field list; the V2
bindings must stay a separate field, because although it repeats the shape bindings in every
observed body, nothing in the document requires that. Rename `reservedVersion`/
`reservedBindings` to the spec's names; a `LoadNote` is *not* warranted any more (this is now
document-conformant, modulo Figure 88's omission, which is worth one note the first time a
version-2 topologically-compressed body is read — or arguably no note at all, since the prose
sanctions version 2).

### 2. The 9.5 Polyline Set Shape LOD Element is fully established, and it differs from the implemented v10 reader in **six** places. *(severity: high — this is issue #12; cost: small)*

`readPolylineSetShapeLodV10` (`ShapeLodDocument.kt:615`, `:649`) is the closest existing code.
Field-by-field against 9.5 Figs. 94/85/87/91/92, with every claim verified against the five
`KR360-1.jt` polyline bodies:

| wire field | v10 (implemented) | 9.5 (established) |
|---|---|---|
| Base Shape LOD Data version | `U8` | `I16` |
| Vertex Shape LOD Data version | `U8` | `I16` |
| Vertex Bindings | `U64` | `U64` — same |
| nested Logical Element Header | present (`I32` len, GUID, `U8` base type, `I32` id) | **absent** |
| TopoMesh LOD Data | `U8` version, `U32` object id | `I16` version, `I32` object id |
| TopoMesh Compressed LOD Data version | `U8` | `I16`; **selects V1/V2** (≥2 ⇒ V2) |
| Number of Face Group List Indices | `U32`, always | `I32`, **only if Polyline Shape** |
| Number of Primitive / Vertex List Indices | `U32` | `I32` |
| Face Group List Indices | `VecI32{Int32CDP, Lag1}`, always | `VecI32{Int32CDP2}` **NULL**, only if Polyline Shape |
| Primitive / Vertex List Indices | `VecI32{Int32CDP, Lag1}` | `VecI32{Int32CDP2}` — **NULL predictor** |
| FGPV List Indices Hash | `I32`; hashes FG(n+1), Prim(n+1), Vtx(n) | `I32`; **skips FG when not a polyline** |
| Vertex Bindings (records) | `U64` | `U64` — same |
| Quantization Parameters | 4×`U8` | 4×`U8` — same |
| Number of Vertex Records | `I32` | `I32` — same |
| Number of Unique Vertex Coordinates | — | **`I32`, 9.5-only**, inside `if records > 0` |
| Unique Vertex Coordinate Length List | `VecI32{Int32CDP}` NULL | `VecI32{Int32CDP2}` NULL — same predictor |
| Unique Vertex List Map Hash | `I32` = `hash32(list, nUniq)` | same formula |
| coordinate / normal / colour / texcoord / flag arrays | v10 array layouts | JT 9 array layouts (PACKAGE H; `CompressedVertexCoordinateArray.read` already exists) |
| aux fields | `if AuxField Bindings` inline (Fig. 89) | in the **V2 tail** (Fig. 92) |
| trailing element version | `U8` | `I16` |

**The predictor is the decisive delta and it is settled by a hash, not by reading.** For body
`453756` (nFG=1, nPrim=80, nVtx=160, three bitlength packets) the stored FGPV hash is
`0xbc4a3adf`. Hashing the decoded symbols **as-is** (NULL) gives `0xbc4a3adf`; hashing them
through Lag1 gives `0x6f4a2d74`. NULL wins, and the decoded lists are sane under NULL and
nonsense under Lag1:

```
NULL: FG [0, 80]  Prim [0,2,4,6,8,10,…]  Vtx [0,1,1,2,2,3,…]
LAG1: FG [0, 80]  Prim [0,2,4,6,14,24,…] Vtx [0,1,1,2,4,7,…]
```

This matches Figure 91's annotation exactly — `VecI32{Int32CDP2}` with no predictor — so the
document and the bytes agree, and it is v10's `Lag1` that is the change. A JT 9 reader built by
copying the v10 one would fail on the FGPV hash (a refusal, not bad geometry — the check earns
its keep here).

Byte-consumption proof of the whole layout: all six bodies end with exactly 12 bytes after the
coordinate array (the V2 tail + element version), and the four bodies whose index lists use
non-arithmetic CODECs verify both stored hashes (FGPV and unique-vertex-map). The remaining two
use arithmetic/chopper packets I did not decode in the probe; their framing consumes exactly.

**Cost:** small. One new reader + writer pair reusing `Int32Cdp.read`, `unpackResiduals(…, NONE)`,
`CompressedVertexCoordinateArray.read`, `JtHash`, and `buildPolylineGeometry` unchanged. The
model needs a JT 9 sibling of `TopoMeshCompressedRepData` with the extra
`numberOfUniqueVertexCoordinates` field and a nullable face-group section.

### 3. "if Polyline Shape" excludes the Point Set — and the 9.5 FGPV hash pseudo-code says so where v10's does not. *(severity: high; cost: trivial once finding 2 is built)*

9.5 p.119:

```
if (bLineStrip)
  uHash = hash32( (UInt32*)(&vFGIndices), nFGIdx+1, uHash );
uHash = hash32( (UInt32*)(& vPrimIdices), nPrimIdx+1, uHash );
uHash = hash32( (UInt32*)(& vVertexIndices), nVtxIdx , uHash );
```

v10 p.99 is the same three lines **with the `if (bLineStrip)` removed** — a real normative
change, not a typo, and it matches v10 Figure 89 dropping the guard from the figure too.

The `KR360-1.jt` point-set body settles the 9.5 side: parsing it *without* a face-group count
and *without* a face-group array lands exactly on the primitive-list packet, and
`hash32(prim) → hash32(vtx)` reproduces the stored `0xe34cace1`. So "Polyline Shape" means
literally the Polyline Set, and the Point Set omits both the count and the array from the wire
**and** from the hash.

The library's v10 reader is right for v10 (`ShapeLodDocument.kt:661-673` requires
`faceGroups.size == faceGroupCount + 1` unconditionally) and must not be reused for 9.5 point
sets. *Model must remember*: whether the face-group section was present, so a point set
re-serializes without it.

### 4. Confirmation — the 30/30/4 chunking is normative, not a producer quirk. DESIGN.md delta 20 upgraded from fixture-guess to citation. *(severity: n/a; cost: zero)*

DESIGN.md calls delta 20 fixture-derived. The 9.5 document states it three times.

Figure 89 (p.115) names the three fields: `Face Attribute Masks (30 LSBs)` ×8,
`Face Attribute Mask 8 (30 next MSBs)`, `Face Attribute Mask 8 (4 MSBs)`, and the prose says the
last "encodes the 4 most significant bits of the 8th context group … rounding out its full
64-bit width."

Appendix E (p.335) states the design:

> "we break this list of bit vectors into those of length 64 and smaller into one group, and all
> others into a list of so-called 'high-valence' bit vectors. **The low-valence bit vectors are
> encoded into three fields of 30, 30, and 4 bits respectively.** … Context group number 8 is
> the only one that encodes valence rings up to valence 64."

v10 Annex D, same sentence: "The low-valence bit vectors are encoded into **two fields of 32
bits each**." Both revisions say it in prose and in the figure; the delta is real and
intentional.

The composite-hash pseudo-code agrees (9.5 p.116 vs v10 p.104): 9.5 masks each of contexts 0-6
with `0x3fffffff` and hashes context 7 three times (`&0x3fffffff`, `>>30 &0x3fffffff`,
`>>60 &0x0f`), all three with count `anAttrMasks[7]`; v10 hashes contexts 0-6 unmasked and
context 7 twice (`&0xffffffff`, `>>32`).

Also confirmed from 9.5 Appendix E (p.348), the attribute-mask context formula DESIGN.md
delta 20 states: `_nextAttrMaskSymbol(::min(7,::max(0,cDeg-2)))` — exactly
`TopologyDecoder.kt:346-355`. The face-degree context (`_faceCntxt`, p.344-345, valence 3 → 0/1/2
by known total degree vs 6·n, valence 4 → 3/4/5 vs 4·n, valence 5 → 6, else 7) is
`TopologyDecoder.kt:126-155`, line for line.

### 5. Figures 93, 94 and 95 omit the Base Shape LOD Data box that Figure 84 requires. *(severity: medium — a spec defect that would mislead a from-scratch implementer; cost: zero for us)*

Figure 84 defines *Vertex Shape LOD Element* = Logical Element + **Base Shape LOD Data** +
Vertex Shape LOD Data. The three concrete subclasses (Figs. 93/94/95) each show only
Logical Element + Vertex Shape LOD Data + `I16 : Version Number`. Elsewhere 9.5 is careful to
list the inherited collection by name (e.g. Fig. 30 opens with a `Base Shape Data` box).

The bytes say Figure 84 is right: *(fixture)* every JT 9 shape body begins
`04 | I32 objectId | 01 00 | 01 00 | U64 bindings …` — two `I16` version fields, Base Shape LOD
Data's and Vertex Shape LOD Data's. `ShapeLodDocument.kt:285-286` already reads both. No code
change; record the figure defect so nobody "fixes" the reader to match Fig. 93.

### 6. Contradiction (documented, tolerated) — the 9.5 document says `Number of Vertex Attributes` equals the mask popcount; a real 9.5 producer writes one more. *(severity: medium; cost: zero, already lenient)*

9.5 Fig. 90 prose (p.118) is normative and exact:

> "One set of vertex attribute records is written to the file corresponding to each 1 bit across
> all encoded dual Face Attribute Masks."

DESIGN.md delta 22 records one `RB___E_01955.jt` body with 204 records stored and 203 referenced
by the masks. That is a **producer-vs-document conflict**: the file violates the sentence above.
`ShapeLodDocument.kt:861-866` already implements the lenient reading (it refuses only when the
masks reference *more* records than are stored) and the extra record survives round-trip
untouched. So no change is needed — but it should now be recorded as a *known deviation from a
normative sentence*, not as a tolerance of unspecified behaviour, and it deserves a `LoadNote`
under the "leniency is not silence" rule. Resolution of whether the writer should ever emit the
extra record is Bernhard's call; the doctrine's answer is no (the strict writer emits the
popcount).

### 7. The composite-hash implementation hashes the *stored packets* where the spec hashes *derived arrays*. Equal on both fixtures; provably equal for contexts 0-6; a latent false-refusal for context 8. *(severity: medium; cost: trivial)*

`ShapeLodDocument.kt:355-358`:

```kotlin
for (i in 0 until 7) hash = JtHash.hash32(maskPackets[i].values.toIntArray(), hash)
hash = JtHash.hash32(IntArray(...) { maskPackets[7].values[it] and 0x3FFFFFFF }, hash)
hash = JtHash.hash32(mask8Mid.values.toIntArray(), hash)
hash = JtHash.hash32(mask8Top.values.toIntArray(), hash)
```

Against 9.5 p.116, two differences:

* **Contexts 0-6 unmasked.** The spec masks with `0x3fffffff`. This is *provably* immaterial:
  the mask context is `min(7, max(0, degree-2))`, so contexts 0-6 hold degree 2-8 rings, i.e.
  masks of at most 8 bits. The mask is a no-op. Safe, but worth a comment so nobody "corrects"
  it into a divergence.
* **Context 8's three chunks use the packets' own lengths.** The spec hashes all three chunks
  with count `anAttrMasks[7]` — i.e. with the *context-8 element count*, regardless of what the
  packets carry. The library instead hashes `mask8Mid.values` / `mask8Top.values` as stored.
  These coincide only if the writer emits mid/top packets of exactly the context-8 length.
  *(fixture)* it always does: across all 23 tri-strip bodies of both files, `mid` and `top`
  value counts equal `masks[7]`'s count exactly (e.g. `masks[7]=102, mid=102, top=102`;
  `masks[7]=0, mid=0, top=0`). But a producer that elides an all-zero upper chunk as an empty
  packet would compute the spec's hash over N zeros while the library computes it over an empty
  array — different values (Jenkins mixes the length in) — and the body would **false-refuse**.
  The geometry builder already anticipates that case
  (`ShapeLodDocument.kt:754-759` tolerates `valueCount == 0`), so the code contains an internal
  contradiction: dead tolerance behind a check that would already have thrown.

  *Lenient reader*: on a hash mismatch, retry with the chunks zero-extended to
  `masks[7].valueCount` before giving up. *Model must remember*: which of the two produced the
  match, so the writer reproduces the same packet lengths.

### 8. Confirmation — the predictor assignments of Figure 89 are documented, closing DESIGN.md delta 18's caveat by half. *(severity: n/a; cost: zero)*

DESIGN.md delta 18 records the Lag1/NULL assignment as fixture-derived with the caveat that "the
fixture's Lag1-predicted streams are all zero, so residual-vs-primal hashing is distinguishable
only by a future fixture." Figure 89 annotates them explicitly: `{Int32CDP2, Lag1}` on **Vertex
Flags** and **Split Face Syms** only, plain `{Int32CDP2}` (NULL) on face degrees, valences,
groups, attribute masks and split-face positions. That half of the delta is now a citation.

On the hashing question the pseudo-code leans primal (what the library does): it names
`vVtxFlags` and hashes it with `hash16`, and 9.5 declares the array `VecU16 vFaceFlags` (v10:
`VecI16`) — the semantic flag values, not residuals. `viSplitVtxSyms` is genuinely ambiguous
("Syms" could be either); the fixture cannot separate them and the document does not either.
Keep the caveat for split-face syms, drop it for vertex flags.

Note the pseudo-code is sloppy in both revisions: it declares `nVtxFlags` and `vFaceFlags` but
calls `hash16(vVtxFlags.ptr(), nFlags, …)`. The intent is unambiguous.

### 9. Gap in the 9.5 document — a Polygon Set Shape **Node** with no Polygon Set Shape **LOD** element. *(severity: low; cost: none to us)*

9.5 §7.2.1.1.1.10.6 / Fig. 35 (p.52) defines a Polygon Set Shape Node Element
(`0x10dd1048…`), and Annex A lists it. But §7.2.2.1 defines no Polygon Set LOD element, and
9.5's Annex-A "Types Stored Within Shape LOD Segment" table (p.303) has exactly six entries:
Null, Point Set, Polyline Set, Primitive Set Shape, Tri-Strip Set, Vertex Shape. v10 fills the
hole with Figure 84 / `0x10dd109f…`. So a 9.5 file with a polygon set has no documented LOD
container. The library already carries `POLYGON_SET_LOD_ELEMENT` and refuses it with a note in
both generations, which is the right behaviour for a 9.5 file too. No action; record so the
9.5 ledger can mark the LOD side `n/a: 9.5 defines no LOD element for this node type`.

### 10. The Primitive Set Shape Element figure and its own prose disagree on field order. *(severity: low; cost: trivial, but it makes the element unimplementable without a fixture)*

Figure 97 (p.128) orders the fields: `I16` Version, `I32` Texture Coord Binding, `I32` Color
Binding, `I32` Texture Coord Gen Type, `I16` Version, `I32` Bits Per Vertex. The per-field prose
that follows (pp.128-129) presents them: Version, Texture Coord Binding, Color Binding, Version,
Bits Per Vertex, **Texture Coord Gen Type** — moving Texture Coord Gen Type to the end. Per the
brief's convention the figure is the wire order, and v10's Fig. 95 corroborates a Gen-Type field
sitting *before* the version/bits pair. But no fixture in hand carries a Primitive Set element,
so the element must stay opaque-with-note in both generations until one appears. Mark as
`spec unclear` in the 9.5 ledger with this quote pair; do not guess.

### 11. 9.5 admits a "Version-2 Format" for the Primitive Set Shape Element and never documents it. *(severity: low; cost: n/a while opaque)*

9.5 p.129: "*= 1 Version-1 Format / = 2 Version-2 Format*" — with no figure, no field list and
no prose anywhere for what version 2 adds. v10 Table 49 lists only `= 1`. Under §9.4's append
convention a version-2 element appends unknown fields after the version-1 ones, which a reader
cannot skip without an element length (the Shape LOD element frame does provide one, so an
opaque carry is safe). Record as a documented-but-unspecified variant.

### 12. Two Int32 CODEC generations inside 9.5 §7.2.2 — the Primitive Set path needs the Mk. **1** packet the library does not implement. *(severity: low today, blocking for the primitive set; cost: large if pursued)*

DESIGN.md delta 15 says "The JT 9 Int32CDP is the 9.5 reference's 'Mk. 2' packet (§8.1.2)". True
for the topology and rep-data fields — Figures 89, 91 and 92 all annotate `Int32CDP2`. But
Figures 99 and 100 annotate plain **`Int32CDP`**, which 9.5 §6.1 (p.19) defines as the *first*
generation (§8.1.1), of which Mk. 2 is "a second-generation version … [with] a simplified and
more compact file layout". So implementing the 9.5 Primitive Set Shape Element requires the
Mk.-1 packet (the scheme 9.5 Appendix C describes and DESIGN.md delta 17 notes is *not* what the
Mk.-2 bitlength CodeText actually uses). PACKAGE H owns the packet layouts; this finding just
scopes the dependency, and it is the reason the primitive set is a `large` item, not a `small`
one. `Int32Cdp.read` today implements Mk. 2 only, and correctly.

### 13. `Number of Topological Vertices` / `Number of Vertex Records` / `Number of Unique Vertex Coordinates`: what 9.5 actually promises. *(severity: medium for issue #14 — the answer is "the document promises nothing about the LSG count"; cost: n/a)*

The brief asks what the 9.5 document says these mean, because the acceptance tests assert a
relationship. Verbatim:

* `I32 : Number of Topological Vertices` (Fig. 90, p.118) — "the number of topological vertices
  encoded by the topology encoder. **This is the number of unique vertex coordinates that will
  be written in the later Compressed Vertex Coordinate Array field.**" Exact and normative;
  `ShapeLodDocument.kt:416-421` and `:855-860` already enforce it both ways.
* `I32 : Number of Vertex Attributes` (Fig. 90) — the mask popcount; see finding 6.
* `I32 : Number of Vertex Records` (Fig. 91, p.119) — "Number of vertex records." No further
  semantics.
* `I32 : Number of Unique Vertex Coordinates` (Fig. 91) — "Number of unique vertex coordinates
  values in the Compressed Vertex Coordinate Array."
* `Unique Vertex Coordinate Length List` (Fig. 91, pp.119-120) — "contains the number of vertex
  records containing each of the unique vertex coordinates and **should sum to the number of
  vertex records**"; the coordinate array "is therefore parallel to the Unique Vertex Length
  List". So 9.5 does state two hard invariants: `len(lengthList) == numberOfUniqueVertexCoordinates
  == coordinateArray.count`, and `sum(lengthList) == numberOfVertexRecords`. *(fixture)* both hold
  on all six bodies (e.g. 347 unique / 347 records / 347 all-ones lengths).

**What 9.5 does not say.** Nothing in §7.2.2 relates any of these to the LSG's declared counts.
The only relevant 9.5 text is §7.2.1.1.1.10.1.1.1 *Vertex Count Range* (p.47): "Min Count is the
least vertex count that **can be achieved by this Shape Node**. Max Count is the maximum vertex
count that can be achieved by this Shape Node." That is a statement about the node's *rendered*
representation, not about the LOD's compressed record counts.

So the issue-#14 observation — a 9.5 producer declaring `triangles + 2·strips` — is **not** a
spec violation and **not** a spec requirement: it is a producer convention that happens to be
the natural reading of "vertex count achieved by this Shape Node" for a strip representation
(a strip of *n* triangles has *n+2* corners). The v10 reference is equally silent, and the NIST
producer's convention differs (DESIGN.md: NX declares upper bounds). **Recommendation for the
acceptance tests:** keep the per-generation assertions the fixtures actually support
(`FixtureDiscoveryTest.kt:128-153`, `:170-180`) and do not promote either producer's convention
to an invariant — the document licenses neither.

### 14. Two small documentation errors worth recording, and one library-doc correction. *(severity: low; cost: trivial)*

* **9.5 Figure 87's caption is wrong.** Titled "TopoMesh LOD Data collection", duplicating
  Figure 86; §7.2.2.1.2.3 makes clear it is *TopoMesh Compressed LOD Data*. Cite by section, not
  by caption.
* **9.5 Appendix D never gives `hash2`'s body.** `hash32` is defined as `return hash2(...)`, and
  the appendix then prints `hash()` (byte variant) and `hash3()` (16-bit variant) but not
  `hash2`, relying on the comment "hash2() is identical to hash() on little-endian machines,
  except that the length has to be measured in ub4s instead of bytes." `JtHash.hash32` is the
  word variant and is fixture-verified on 117+ stored hashes; the 9.5 text alone would not have
  sufficed. Worth naming as a `spec incomplete` unit rather than an `identical` one.
* **`SPEC_COVERAGE.md` is now stale on two rows.** Line 196 says the JT 9 polyline layout is
  unestablished because "no v9 fixture carries one", and line 197 says the same for the point
  set — but `fixtures-local/KR360-1.jt` carries **five** polyline and **one** point-set JT 9 LOD
  bodies. The rows were written before that fixture existed. Correct them when the 9.5 ledger
  lands.
