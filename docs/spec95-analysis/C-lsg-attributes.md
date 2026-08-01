# Package C — LSG Attribute Elements (JT 9.5 Rev-D §7.2.1.1.2, Figures 39–69)

Range: 9.5 §7.2.1.1.2 *Attribute Elements* in full, pp. 54–100. Every figure in the range is a
row, including the four the inventory filter dropped (Figs. 44, 47, 49, 50) and the two
sub-collections shared with the light family (Figs. 54, 58). Figure 57 is an illustration, not
a byte layout, and is marked `n/a`. The unnumbered normative bit tables of the section
(State Flags, per-element Field Inhibit assignments, the four Data Flags tables) are rows too —
9.5 numbers no tables in this section, so they are cited by section and page.

v10 counterparts are cited as `Fig. N` from **JT v10 Rev-C §6.1.2**. Reminder: the numbers do
not correspond (9.5 Fig. 42 Material ↔ v10 Fig. 47 Material).

**Fixture evidence used below.** Both local 9.5 fixtures (`RB___E_01955.jt`, `KR360-1.jt`, both
"NetAllied JTWriter R14") were re-parsed from the raw bytes for this pass. Their inflated LSG
streams carry **13 Material Attribute Elements and no other attribute type at all** — no draw
style, no lights, no styles, no transform, no texture, no mapping, no shader element. So every
non-material row below is spec-only: the fixtures cannot confirm *or* refute it.

---

## Part 1 — ledger rows

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| Fig. 39 — Base Attribute Data collection (p.55) | Fig. 46 | `structural` + `widths` | done | done | `LsgCodecs.kt:266` / `:278` | v10 appends `U32 Field Final Flags` (absent in 9.5); version `I16`→`I8`. Byte-verified on 13 fixture elements. See F6. |
| §7.2.1.1.2.1.1 State Flags bit table (p.55) | v10 Table 15 | `structural` (semantics) | n/a: semantics, not bytes | n/a | — | 9.5 `0x01` = **Accumulation Final**; v10 `0x01` = **Unused** (v10 moved per-field finals into Field Final Flags). Same byte, opposite meaning. See F7. |
| §7.2.1.1.2.1.1 Field Inhibit Flags (p.55) | v10 §6.1.2.1.1 | `identical` (U32, bits 0–31, per-element assignment) | n/a: semantics | n/a | — | Byte layout identical; the *assignments* differ per element (rows below). |
| Fig. 40 — Base Shader Data collection (p.56) | **none** | `9.5-only` | — | — | — | `I16 Version`, `I32 Shader Language`, `U32 Inline Source Flag`, then `MbString Source Code` if flag==1 **else** `MbString Source Code Loc`, `I32 Shader Param Count`, `Shader Parameter × count`. See F1. |
| Fig. 41 — Shader Parameter data collection (p.58) | **none** | `9.5-only` | — | — | — | `MbString Param Name`, 6 × `U32` (Param Type, Value Class, Direction, Semantic Binding, Variability, Reserved), then `U32 Value × 16` (64 bytes, fixed). See F1. |
| Fig. 42 — Material Attribute Element (p.61) | Fig. 47 | `structural` + `widths` | done | done | `LsgCodecs.kt:738` | Version `I16`(1\|2) vs `I8`(1 only); `F32 Reflectivity` gated `Version==2` in 9.5, unconditional in v10; v10 adds `F32 Bumpiness`, 9.5 has **none**. Byte-verified on 13 elements. See F6. |
| §7.2.1.1.2.2 Material field-inhibit bits (p.60) | v10 Table 16 | `structural` | n/a: semantics | n/a | — | 9.5 bit 1 = "Diffuse Color and Alpha (Legacy)"; v10 has no such row, so **bits 1–8 are shifted by one** relative to 9.5. See F8. |
| §7.2.1.1.2.2 Material Data Flags bits (p.62) | v10 Table 18 | `identical` | done | done | `LsgCodecs.kt:746` | `0x0010` Blending, `0x0020` Override Vertex Colours, `0x07C0` Src Blend Factor (bits 6–10), `0xF800` Dst Blend Factor (bits 11–15). Low nibble `0x000F` is **reserved in both**. Fixture value 14752 fully explained. See F5. |
| Fig. 43 — Texture Image Attribute Element (p.64) | Fig. 48 | `structural` | opaque | opaque | `LsgCodecs.kt:1233` (`v9Layout=false`), gate `:1604` | 9.5: `LEH ZLIB`, `Base Attribute Data`, `I16 Version` (1\|2\|3), *[Texture Vers-1 Data]*, `Version>=2 → Vers-2`, `Version>=3 → Vers-3`. v10: version 1 only, one Vers-1 block. The unconditional block's label in the PDF literally reads "Because the" — a document defect. See F2, F3. |
| Fig. 44 — Texture Vers-1 Data collection (p.65) | Fig. 49 (v10 renamed 9.5's *Vers-3* to "Vers-1") | `structural` | opaque | opaque | `LsgCodecs.kt:1233` | 9.5: Type, Environment, CoordGen, `I32 Texture Channel`, `U32 Reserved Field`, `U8 Inline Flag`, `I32 Image Count`, images/names. v10 inserts `I32 Tex Coord Channel` after Texture Channel and renames Reserved→Empty. 9.5 Texture Type enum = 7 values (0–6, Bump/Cube/Depth Map); v10 = 26 values. Channel range 9.5 `[0,31]`, v10 `[-1,2³¹−1]`. See F3. |
| Fig. 45 — Texture Environment collection (p.67) | Fig. 50 | `identical` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1146` / `:1161` | 8 × `I32` (Border Mode, Mag Filter, Min Filter, S/T/R Wrap, Blend Type, Internal Compression Level), `RGBA Blend`, `RGBA Border`, `Mx4F32 Texture Transform`. Field-for-field checked, incl. all enum tables. |
| Fig. 46 — Texture Coord Generation Parameters (p.70) | Fig. 51 | `identical` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1244` | `I32 Tex Coord Gen Mode × 4`, `PlaneF32 Tex Coord Reference Plane × 4`, S/T/R/Q order; 6 mode values in both. |
| Fig. 47 — Inline Texture Image Data collection (p.71) | Fig. 52 | `identical` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1208` | `Image Format Description`, `I32 Total Image Data Size`, then per mipmap `I32 Mipmap Image Byte Count` + texel bytes, `Mipmaps Count` times. |
| Fig. 48 — Image Format Description collection (p.72) | Fig. 53 | `widths` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1178`, flag at `:1188` | **9.5 `U8 : Shared Image Flag`; v10 `U32: Shared Image Flag`** — a 3-byte delta inside a variable-length block. All other fields identical (`U32` Pixel Format, `U32` Pixel Data Type, 6 × `I16`, `I16 Mipmaps Count`). See F4. |
| Fig. 49 — Texture Vers-2 Data collection (p.75) | **none** | `9.5-only` | opaque | opaque | — | Leading "Texture Vers-1 Data : Stub" box = the *already-read* Fig. 43 block, not extra bytes (see F2). New fields = Vers-1 field set with the 26-value Texture Type enum and channel range `[-1,31]`. |
| Fig. 50 — Texture Vers-3 Data collection (p.78) | Fig. 49 (title "Texture Vers-1 Data") | `structural` | opaque | opaque | — | This *is* v10's Vers-1 layout, except: leading Vers-2 stub reference (9.5 only) and `I32 Tex Coord Channel` sits at the **tail** in 9.5 but between Texture Channel and Empty Field in v10. Texture Type table identical (0–26). See F3. |
| Fig. 51 — Draw Style Attribute Element (p.81) | Fig. 54 | `widths` | opaque | opaque | `LsgCodecs.kt:793` (`v9Layout=false`) | Only delta: `I16` vs `I8` Version Number. `U8 Data Flags` bit table identical (0x01…0x20). |
| §7.2.1.1.2.4 Draw Style inhibit + Data Flags (p.80–81) | v10 Tables 34/35 | `identical` | n/a: semantics | n/a | — | Inhibit bits 0–5 same rows, same order; data-flag bits identical. |
| Fig. 52 — Light Set Attribute Element (p.82) | Fig. 55 | `widths` | opaque | opaque | `LsgCodecs.kt:819` | Only delta: version width. `I32 Light Count` + `I32 Light Object ID × count`. |
| Fig. 53 — Infinite Light Attribute Element (p.83) | Fig. 56 | `structural` + `widths` | opaque | opaque | `LsgCodecs.kt:847` | 9.5 versions **1 and 2**; `Version==2` appends a `Shadow Parameters` block (2 × F32). v10 knows version 1 only and has no such tail. Figure box is mislabelled "Shadow Opacity"; version box is mislabelled "16 :". See F9. |
| Fig. 54 — Base Light Data collection (p.84) | Fig. 57 | `structural` + `widths` | opaque | opaque | `LsgCodecs.kt:305` / `:323` | 9.5: `I16 Version`, 3 × `RGBA`, `F32 Brightness`, `I32 Coord System`, `U8 Shadow Caster Flag`, `F32 Shadow Opacity` — **and nothing else**. v10 additionally carries `F32 Non-shadow Alpha Factor` + `F32 Shadow Alpha Factor` here. 9.5's figure also **omits the Base Attribute Data box entirely**. See F9, F10. |
| Fig. 55 — Shadow Parameters collection (p.85) | **none** (absorbed into v10 Fig. 57) | `9.5-only` (placement) | — | — | — | `F32 Non-shadow Alpha Factor`, `F32 Shadow Alpha Factor`. Same two fields exist in v10 but *inside Base Light Data, unconditionally*; in 9.5 they hang off the **element** and only when the element's version == 2. See F9. |
| Fig. 56 — Point Light Attribute Element (p.86) | Fig. 58 | `structural` + `widths` | opaque | opaque | `LsgCodecs.kt:879` | Same shape as v10 (`HCoordF32 Position`, `Attenuation Coefficients`, `F32 Spread Angle`, `DirF32 Spot Direction`, `I32 Spot Intensity`) plus the `Version==2 → Shadow Parameters` tail and the `I16` version. See F9. |
| Fig. 57 — Spread Angle illustration (p.87) | Fig. 59 | `n/a: illustration` | n/a | n/a | — | Not a byte layout. Clamping rule (`==180` point light; `0–90` spot) identical in both. |
| Fig. 58 — Attenuation Coefficients collection (p.88) | Fig. 60 | `identical` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:889` | 3 × `F32` (Constant, Linear, Quadratic), all `>= 0`. |
| Fig. 59 — Linestyle Attribute Element (p.88) | Fig. 61 | `widths` | opaque | opaque | `LsgCodecs.kt:925` | Only delta: version width. `U8 Data Flags` (`0x0F` Line Type 0–7, `0x10` Antialiasing) + `F32 Line Width`, identical tables. |
| Fig. 60 — Pointstyle Attribute Element (p.90) | Fig. 62 | `widths` | opaque | opaque | `LsgCodecs.kt:959` | Only delta: version width. `U8 Data Flags` (`0x0F` reserved Point Type, `0x10` Antialiasing) + `F32 Point Size`. |
| Fig. 61 — Geometric Transform Attribute Element (p.91) | Fig. 63 | `widths` | opaque | opaque | `LsgCodecs.kt:1002`, value read at `:1016` | **Two** width deltas: version `I16`→`I8`, and **`F32 : Element Value` in 9.5 vs `F64: Element Value` in v10** — checked in both the figure box and the prose heading of p.91. `U16 Stored Values Mask`, bit15→element0, `<<1` walk: identical. See F2. |
| Fig. 62 — Shader Effects Attribute Element (p.92) | **none** | `9.5-only` | — (unknown GUID → opaque + `UnknownElementType`) | opaque | gate `LsgCodecs.kt:1601` | GUID `0xaa1b831d,0x6e47,0x4fee,a8,65,cd,7e,1f,2f,39,**db**`. Fixed 34-byte payload after Base Attribute Data: `I16 Version`, `U32 Enable Flag`, `I32 Reserved 1`, `F32 Env Map Reflectivity`, `I32 Reserved 2`, `F32 Bumpiness Factor`, `U32 Reserved 3`, `U32 Phong Shading Flag`, `U32 Reserved 4`. See F1. |
| Fig. 63 — Vertex Shader Attribute Element (p.94) | **none** | `9.5-only` | — (unknown GUID → opaque) | opaque | gate `LsgCodecs.kt:1601` | GUID `0x2798bcad,0xe409,0x47ad,bd,46,0b,37,1f,d7,5d,61`. Order is `LEH ZLIB`, `Base Attribute Data`, **`Base Shader Data`**, **`I16 Version Number` last** — verified against the rendered PDF page, not just pdftotext. See F1. |
| Fig. 64 — Fragment Shader Attribute Element (p.95) | **none** | `9.5-only` | — (unknown GUID → opaque) | opaque | gate `LsgCodecs.kt:1601` | GUID `0xad8dccc2,0x7a80,0x456d,b0,d5,dd,3a,0b,8d,21,e7`. Same layout as Fig. 63, version last. See F1. |
| Fig. 65 — Texture Coordinate Generator Attribute Element (p.96) | Fig. 64 | `widths` | opaque | opaque | `LsgCodecs.kt:1106` | Only delta: version `I16` vs `U8`. `I32 Texture Coord Channel` + nested `Mapping Surface` element frame, identical. |
| Fig. 66 — Mapping Plane Element (p.97) | Fig. 65 | `widths` | opaque | opaque | `LsgCodecs.kt:1044` / `:1078` | `LEH ZLIB`, version, `Mx4F64 Matrix`, `I32 Coordinate System`; **no Base Attribute Data** in either generation. Only version width differs. |
| Fig. 67 — Mapping Cylinder Element (p.98) | Fig. 66 | `widths` | opaque | opaque | `LsgCodecs.kt:1085` | as above |
| Fig. 68 — Mapping Sphere Element (p.99) | Fig. 67 | `widths` | opaque | opaque | `LsgCodecs.kt:1092` | as above |
| Fig. 69 — Mapping TriPlanar Element (p.100) | Fig. 68 | `widths` | opaque | opaque | `LsgCodecs.kt:1099` | as above |
| §7.2.1.1.2.3 Texture Image inhibit bits (p.63) | v10 Table 19 | `structural` | n/a: semantics | n/a | — | 9.5 assigns Internal Compression Level to **bit 8** and leaves bit 7 unused; v10 assigns it to **bit 7**. Bits 0–6 identical. |
| §7.2.1.1.2.8 Linestyle Data Flags (p.88) | v10 Table 41 | `identical` | n/a: semantics | n/a | — | 8 line types + antialias bit, same values. |
| §7.2.1.1.2.9 Pointstyle Data Flags (p.89) | v10 Table 42 | `identical` | n/a: semantics | n/a | — | reserved point-type nibble + antialias bit. |

**Counts** — 37 rows. `identical` 10 · `widths` 11 · `structural` 9 · `9.5-only` 6 · `n/a` 1.
Byte layouts only (excluding the 8 semantics/table rows): 29 rows, of which `identical` 5,
`widths` 11, `structural` 6, `9.5-only` 6, `n/a` 1.

---

## Part 2 — findings

### F1 — The four shader figures: v10 **dropped** them; only one field was absorbed. `large`

Verdict per element, established from both documents:

| 9.5 unit | v10 fate | Evidence |
|---|---|---|
| Fig. 40 Base Shader Data | **dropped** | The string "shader" appears **exactly once** in the whole v10 Rev-C text, in the revision history: *"Revision C … 2. Remove remaining shader Cg shader references"* (v10 p.4). No figure, no section, no GUID. |
| Fig. 41 Shader Parameter | **dropped** | same; 9.5's `Appendix B: Semantic Value Class Shader Parameter Values` (9.5 p.306) has no v10 annex. |
| Fig. 62 Shader Effects Attribute Element | **dropped, one field absorbed** | Its `F32 Bumpiness Factor` reappears as **`F32 Bumpiness` on v10's Material Attribute Element** (v10 Fig. 47, p.51) with a near-verbatim description ("larger values … more highly embossed … negative values … engraved rather than embossed"). Nothing else survives: Enable Flag, Env Map Reflectivity, Phong Shading Flag and the four reserved fields have no v10 home. |
| Fig. 63 Vertex Shader Attribute Element | **dropped** | GUID `0x2798bcad…` is absent from v10 Annex A Table A.1. |
| Fig. 64 Fragment Shader Attribute Element | **dropped** | GUID `0xad8dccc2…` absent from v10 Table A.1 (present **twice** in 9.5's Annex A — a 9.5 documentation duplicate). |
| Fig. 55 Shadow Parameters | **absorbed, not dropped** — see F9 | |

**What a 9.5 reader must therefore handle that the v10 path has never seen.** Three element
types whose GUIDs are not in `ObjectTypeIds.kt` at all. Today `decodeElementFrame`
(`LsgCodecs.kt:1601`) carries them as `OpaqueLsgElement` with `LoadNote.UnknownElementType` —
**lossless, so nothing is at risk**, but the note says "unknown", which is wrong: they are
*known 9.5-only* types. The inventory also cannot name them (`ObjectTypeIds.nameOf` → `null`).

Two of the three are **variable-length** (`MbString` shader source, `MbString` param name,
N shader parameters), so an opaque carry is the only honest state until a fixture appears; the
Shader Effects element by contrast is a **fixed 34-byte payload** after Base Attribute Data and
could be decoded spec-only with the strict length check as the safety net.

Watch the GUIDs: Shader Effects is `…2f,39,**db**` and Texture Coordinate Generator is
`…2f,39,**dc**` — adjacent by one byte. Any GUID comparison that truncates would misdispatch a
Shader Effects element into the Texture Coordinate Generator codec, which then reads a nested
element frame from reserved fields. `Guid` compares in full today, so this is a hazard note,
not a bug.

Cost: `trivial` to add the three GUIDs + names to `ObjectTypeIds.kt` and give them a named
`9.5-only` load note instead of `UnknownElementType`. `small` to decode Shader Effects.
`large` for the shader-source family (Base Shader Data + Shader Parameter + two elements +
Appendix B's semantic value enum).

### F2 — Two width contradictions the code would get wrong the moment a 9.5 fixture carries them. `trivial` each

**(a) Geometric Transform element values are `F32` in 9.5, `F64` in v10.**
9.5 Figure 61 (p.91) prints `F32 : Element Value` in the figure box **and** as the prose field
heading; v10 Figure 63 (p.76) prints `F64: Element Value`. `LsgCodecs.kt:1016` reads `readF64()`
in every generation. Neither fixture carries a Geometric Transform element, so nothing misreads
today — the type is `v9Layout = false` and carried opaquely — but the delta must be recorded
before that codec is opened for v9, or a 16-element matrix would over-read by 64 bytes.

Under the doctrine this one is *self-disambiguating*: after `U16 Stored Values Mask` the
remaining body length is `popcount(mask) × 4` (F32) or `× 8` (F64). A lenient reader resolves it
from the length; the strict writer emits the figure's `F32` for v9 and `F64` for v10. **What the
model must remember:** which width it saw, since `Mx4F64` stores `Double` and widening an F32
is lossless but not reversible without the flag. `mask == 0` is the one case where the two
readings coincide and no flag is needed.

**(b) `Shared Image Flag` is `U8` in 9.5, `U32` in v10.**
9.5 Figure 48 / p.73 prose both say `U8 : Shared Image Flag`; v10 Figure 53 / p.65 prose both
say `U32: Shared Image Flag`. `readImageFormatDescription` (`LsgCodecs.kt:1178`, flag at `:1188`)
reads `U32` unconditionally. Same situation: unreachable in v9 today, fatal when the texture
codec is opened for v9, because the flag sits *before* `I16 Mipmaps Count` and a 3-byte
misalignment corrupts the mipmap loop.

### F3 — v10's "Texture Vers-1 Data" is 9.5's **Vers-3** renamed, with one field relocated. `large`

This is the largest structural rename in the attribute family and it is invisible from the
titles. v10 Figure 49 is titled *Texture Vers-1 Data* but carries 9.5's **Vers-3** content:
the 26-value Texture Type table including `= 26 Resets texture state…` (9.5 p.79, v10 p.56 —
identical rows), the `[-1, 2147483647]` channel range, and the `I32 Tex Coord Channel` field.
9.5's actual Vers-1 (Fig. 44) has a 7-value Texture Type enum (`4` = Bump Map, `5` = Cube Map,
`6` = Depth Map — meanings v10 reassigned) and **no Tex Coord Channel at all**.

The relocated field: 9.5 Figure 50 puts `I32 Tex Coord Channel` **last, after the image
list**; v10 Figure 49 puts it **between `I32 Texture Channel` and `U32 Empty Field`**. Same
field, different offset in a variable-length block.

Consequence for a 9.5 reader: a v9 Texture Image Attribute Element of version 3 is *three*
successive Vers blocks, and only the third has the trailing Tex Coord Channel. The v10 code
path has never read more than one.

**Figure defect that must not be misread (9.5 p.75, p.78).** Figure 49 opens with a box
"Texture Vers-1 Data : Stub" and Figure 50 with "Texture Vers-2 Data : Stub". These denote the
**blocks already read at the Figure-43 level**, not additional bytes. Reading them as nested
would make a version-3 texture contain six Vers-1-shaped blocks. The prose settles it:
*"Any Texture Image Attribute Element using the Texture Vers-2 Data format will contain a
'degenerate' Texture Vers-1 Data block, where Image Count data field has a value of '0'"*
(9.5 p.74) — i.e. one stub, at the top level.

### F4 — Figure 43's unconditional block has no label: the PDF prints "Because the". `trivial` (documentation)

Rendered page 64 of the 9.5 PDF shows the fourth box of Figure 43 containing the literal text
**"Because the"** — the opening words of the paragraph below have overwritten the box label.
The box is a hyperlink and the surrounding prose (*"Complete description for Texture Vers-1
Data can be found in 7.2.1.1.2.3.1"*, plus §7.2.1.1.2.3.1's *"advanced textures also write a
Texture Vers-1 Data block"*) establishes beyond doubt that it is **Texture Vers-1 Data,
unconditional**. Recording it so nobody re-derives it: the block is present for versions 1, 2
**and** 3.

### F5 — Material Data Flags: DESIGN.md's `0x000F` refusal has the wrong rationale. `trivial`

`LsgCodecs.kt:747` refuses a v9 material decode when `dataFlags & 0x000F != 0`, with the
comment *"The v10 inhibit table hints at 'Common RGB Value' compact colour storage in earlier
generations; its v9 wire layout is not established."* DESIGN.md delta 11 repeats it.

The 9.5 document now answers the question the comment was hedging against: **9.5 documents the
same four bit groups as v10 and no others** — `0x0010` Blending, `0x0020` Override Vertex
Colours, `0x07C0` Source Blend Factor, `0xF800` Destination Blend Factor — and states *"All
undocumented bits are reserved"* (9.5 p.62). There is no compact-colour field in 9.5's
Figure 42 either. 9.5's *inhibit* table does name "Ambient Common RGB Value" / "Specular Common
RGB Value" / "Emission Common RGB Value" (p.60) exactly as v10's does, so the phantom names are
a shared editorial artifact of both generations, not a 9.5 feature.

The guard itself is still right under the doctrine (an undocumented low nibble means the layout
is not established → refuse → opaque carry with a note, never a guess). Only the *stated
reason* is wrong: it is "9.5 declares those bits reserved and we will not invent a meaning",
not "9.5 might carry compact colours". Correct the comment and DESIGN.md delta 11.

Fixture support: all 13 material elements carry `dataFlags = 14752 = 0x39A0` = `0x0020`
(Override Vertex Colours ON) | src factor 6 (`GL_SRC_ALPHA`) << 6 | dst factor 7
(`GL_ONE_MINUS_SRC_ALPHA`) << 11, blending OFF, low nibble 0. Every bit is accounted for by
9.5 p.62.

### F6 — Confirmations: DESIGN.md deltas 10 and 11 are now citations, not guesses. `trivial`

DESIGN.md delta 10 (*"Base Attribute Data: v9 has no Field Final Flags"*, evidence "both
material elements parse to exact length") is **confirmed outright** by 9.5 Figure 39 (p.55):
three fields, `I16 Version Number` / `U8 State Flags` / `U32 Field Inhibit Flags`, full stop.
v10 Figure 46 (p.49) adds `U32 Field Final Flags` as a fourth. Upgrade the note from
fixture-inferred to spec-cited.

DESIGN.md delta 11 (*"Material Attribute: v9 has no bumpiness; reflectivity exists from local
version 2 on"*) is **confirmed outright** by 9.5 Figure 42 (p.61): `F32 Shininess`
unconditional, `F32 Reflectivity` guarded `Version Number == 2`, and no Bumpiness field
anywhere in the section. v10 Figure 47 has all three unconditional.

Byte re-verification done for this pass, independent of the test suite: 13 Material Attribute
Elements across the two 9.5 fixtures, **every one 104 bytes exactly** = 16 GUID + 1 base type
+ 4 object id + 7 Base Attribute Data + 2 version + 2 data flags + 64 RGBA×4 + 4 shininess
+ 4 reflectivity. Zero leftover on all 13. Figure 42 is field-for-field correct.

One deviation worth naming, and it is the **producer's**, not the layout's: `RB___E_01955.jt`
writes `shininess = 0.0` on both its materials, outside 9.5's stated valid range `[1,128]`
(p.61), and `emission = (0,0,0,0.00392)`. Values, not bytes — the reader must not range-check
these into a refusal.

The one place the code is *stricter than lenient*: `LsgCodecs.kt:760` gates reflectivity on
`version >= 2` where Figure 42 says `Version Number == 2`. Harmless (no version 3 is defined),
and the `>=` form is the more lenient of the two, so it is doctrine-correct as written; the
**writer** should keep emitting it only for version 2.

### F7 — `State Flags` bit `0x01` means opposite things in the two generations. `trivial`

9.5 p.55: `0x01` = **Accumulation Final flag** (`1` = accumulation is final).
v10 Table 15 (p.49): `0x01` = **Unused** — v10 replaced the single attribute-wide final bit with
the per-field `Field Final Flags` word, and says so explicitly in §6.1.2 (*"JT v10 replaces this
single bit with separate 'field final' bits for each field"*).

Bits `0x02` Force, `0x04` Ignore, `0x08` Persistable are identical in both. Both fixtures write
`stateFlags = 8` on every attribute (persistable, nothing else), so nothing observed exercises
it — but SPEC_COVERAGE.md's *LSG Attribute Accumulation Semantics* row currently emits
`SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED` for force/final, and when that is implemented it must
branch on generation: in v9 `0x01` is meaningful, in v10 reading it is a bug.

### F8 — Material field-inhibit bit assignments are shifted by one between the generations. `trivial`

9.5 p.60 has nine rows; v10 Table 16 has the same set **minus** 9.5's bit 1
("Diffuse Color and Alpha (Legacy)"):

| bit | 9.5 | v10 |
|---|---|---|
| 0 | Ambient Common RGB Value, Ambient Color | Ambient Common RGB Value, Ambient Colour |
| 1 | **Diffuse Color and Alpha (Legacy)** | Specular Common RGB Value, Specular Colour |
| 2 | Specular Common RGB Value, Specular Color | Emission Common RGB Value, Emission Colour |
| 3 | Emission Common RGB Value, Emission Color | Blending Flag, Source/Destination Blending Factor |
| 4 | Blending Flag, Source/Destination Blending Factor | Override Vertex Colour Flag |
| 5 | Override Vertex Color Flag | Material Reflectivity |
| 6 | Material Reflectivity | Diffuse Colour |
| 7 | Diffuse Color | Diffuse Alpha |
| 8 | Diffuse Alpha | *(blank)* |

The same shape of shift affects Texture Image (9.5 puts Internal Compression Level on **bit 8**
with bit 7 unused; v10 puts it on **bit 7**). Byte layout unaffected — the `U32` is stored
identically — but any code that *interprets* inhibit bits must branch on generation. Nothing
does today (they are carried as an opaque `UInt`), which is exactly right; this finding exists
so that the accumulation work does not silently reuse the v10 table on a v9 file.

### F9 — The light family: `Shadow Parameters` moved, and 9.5 gates it on element version 2. `small`

The absorption question, answered: v10 did **not** drop Shadow Parameters. Its two fields
(`F32 Non-shadow Alpha Factor`, `F32 Shadow Alpha Factor`) are in v10 Figure 57 **Base Light
Data**, unconditional, with verbatim-identical prose. In 9.5 they are a separate collection
(Fig. 55, p.85) attached to the **element** — Infinite Light (Fig. 53) and Point Light
(Fig. 56) — and **only when that element's Version Number == 2**.

So the 9.5 wire layout of a light differs from v10 in *two* directions at once:

```
9.5 Base Light Data   : I16 ver, RGBA×3, F32 brightness, I32 coord sys, U8 shadow caster, F32 shadow opacity          (63 B)
v10 Base Light Data   : I8  ver, RGBA×3, F32 brightness, I32 coord sys, U8 shadow caster, F32 shadow opacity,
                        F32 non-shadow alpha, F32 shadow alpha                                                        (70 B)
9.5 element tail (v2) : … + F32 non-shadow alpha, F32 shadow alpha
9.5 element tail (v1) : … + nothing
```

`readBaseLightData` (`LsgCodecs.kt:305`) reads all three trailing F32s unconditionally in
**every** generation, including V9. That is the v10 layout applied to v9 — wrong for a 9.5
version-1 light by 8 bytes, and wrong for a 9.5 version-2 light by *placement* (the code reads
them before the element version and direction; 9.5 puts them after the direction / spot
intensity). Both light types are `v9Layout = false`, so no 9.5 file reaches this code today and
nothing misreads — but the codec is *not* a version-width away from being v9-correct, which is
what the `v9Layout = false` flag was protecting against, and this finding is the proof that the
protection was warranted.

**What a lenient reader must accept / a strict writer must emit / the model must remember:**
accept both the "in Base Light Data" and the "after the element payload, gated on version 2"
placements, resolvable from the element's declared length; emit 9.5's placement for v9 and
v10's for v10; the model must carry *whether the shadow parameters were present at all*
(a version-1 9.5 light has none) rather than defaulting them to 0.0, or a version-1 light
round-trips as a version-2 light.

**Figure defects in this family, all confirmed against the rendered PDF:**
- 9.5 Fig. 53 labels the version box `16 : Version Number` (the `I` is lost). It is `I16`.
- 9.5 Fig. 53 and Fig. 56 label the guarded box **"Shadow Opacity"**, but the caption text
  under both figures says *"Complete description for **Shadow Parameters** can be found in
  7.2.1.1.2.6.2"*, and `Shadow Opacity` already exists unconditionally inside Base Light Data.
  The box is the **Shadow Parameters** collection (2 × F32), mislabelled.

### F10 — 9.5's Base Light Data figure omits Base Attribute Data entirely. `trivial` (record), `small` (resolve)

9.5 Figure 54 (rendered p.84) shows eight boxes and **no Base Attribute Data box**. Neither does
Figure 53 (Infinite Light) nor Figure 56 (Point Light). Yet both element sections state
*"…does not have any Field Inhibit flag (see 7.2.1.1.2.1.1 Base Attribute Data) bit
assignments"*, which presupposes the collection is there. Taken literally, 9.5 lights carry no
Base Attribute Data at all — which cannot be right, since every other attribute element does.

v10 has the mirror-image defect in the *same* figure: v10 Figure 57's second box is labelled
"Logical Element Header Compressed" where Base Attribute Data belongs — already recorded in
DESIGN.md under *Known spec ambiguities* and in SPEC_COVERAGE.md's Fig. 57 row. So **both**
generations' Base Light Data figures are corrupt in the same slot, and neither can be used to
settle where the collection sits. Note also that v10's stray box is *after* `I8 Version Number`
while the library reads Base Attribute Data *before* the version (`LsgCodecs.kt:305`,
spec-derived per the attribute-element convention, not fixture-verified).

This is resolvable by length when a fixture appears: a 9.5 version-1 Infinite Light is **89
bytes** of element body with Base Attribute Data present and **82** without; the difference
between "before version" and "after version" placement is not a length difference and needs a
real file (or a second reader) to settle. Record `spec unclear` on the placement; the presence
is not in doubt.

### F11 — Honest gaps: what is `opaque` in v9 today and what it would cost. `small` each, `large` for two

Every attribute element except Material is `v9Layout = false` and therefore opaque in the JT 9
path (`LsgCodecs.kt:1604`, note `ELEMENT_LAYOUT_UNVERIFIED`). With the 9.5 document in hand,
these are now **document-established**, not guesses, and the only thing missing is a fixture:

- `trivial→small`, pure version-width change from the existing v10 codec: Draw Style (Fig. 51),
  Light Set (Fig. 52), Linestyle (Fig. 59), Pointstyle (Fig. 60), Texture Coordinate Generator
  (Fig. 65), the four Mapping elements (Figs. 66–69).
- `small`, width change **plus** one enumerated structural delta: Geometric Transform (F32
  values — F2a), the two lights (Shadow Parameters placement — F9).
- `large`: the Texture Image family (Figs. 43/44/45/46/47/48/49/50) — three chained version
  blocks, the Vers-1↔Vers-3 rename, the `U8`/`U32` Shared Image Flag, and the relocated Tex
  Coord Channel; and the shader family (F1).

Neither local 9.5 fixture exercises **any** of them: the LSG streams re-parsed for this pass
contain 13 Material Attribute Elements and nothing else from this section. So the deferral
condition recorded in DESIGN.md's table ("first v9 fixture carrying them") is still unmet — but
its stated reason ("v9 layouts do not follow mechanically from v10") is now *quantified* rather
than assumed: 11 of the 29 byte layouts in this range differ only in scalar widths, 6 differ
structurally, and 6 have no v10 counterpart at all.
