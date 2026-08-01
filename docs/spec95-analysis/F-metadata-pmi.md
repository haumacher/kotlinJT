# Package F — Meta Data Segment and PMI (9.5 §7.2.6, Figures 133–170)

Range: JT 9.5 Rev-D §7.2.6 in full (Figures 133–170, pp.162–201) plus §7.2.7 (p.202), the
`Data collection`-titled sub-layouts inside it, and its normative value tables. v10 counterpart:
JT v10 Rev-C chapter 11 (Figures 107–131, pp.123–154).

---

## The headline verdict: the restructuring is genuine, not a titling artifact

The inventory diff is real. **v10 deleted the entire typed-PMI-entity family from the wire.**

9.5 Figure 136 (PMI Manager) reads, unconditionally and before anything else, a
`7.2.6.2.1 PMI Entities` collection (Figure 137, p.168) that is a fixed sequence of **thirteen
typed entity collections**:

> Dimension → Note → Datum Feature Symbol → Datum Target → Feature Control Frame → Line Weld →
> Spot Weld → Surface Finish → Measurement Point → Locator → Reference Geometry → Design Group →
> Coordinate System

(order read off the rendered two-column figure on p.168, left column top-to-bottom then right
column top-to-bottom — confirmed against the PDF page image, not the `pdftotext` reflow).

v10 Figure 110 (p.127, PDF p.139 — also read from the page image) has **no `PMI Entities` box at
all**. Its element goes `U8 Version → I16 Empty Field → PMI Design Group Entities → PMI
Associations → …`. Design Group Entities is the *only* survivor of the thirteen, promoted to a
top-level sibling; the other twelve are gone from v10's normative text entirely — no figure, no
section, no prose. Their semantic content was folded into **Generic PMI Entities**, which grew
from two 9.5 pages (§7.2.6.2.6, pp.193–196) to eleven v10 pages (§11.2.6, pp.139–149) and
absorbed `PMI 2D Data` / `PMI Base Data` / `2D Text Data` / `Non-Text Polyline Data` as its own
subsections (in 9.5 those hang under *Dimension* Entities at §7.2.6.2.1.1.1.x). v10's Table 60
correspondingly grew by 16 entity-type codes (0x0230–0x0308) over 9.5's Table on p.195 — the new
codes name exactly the kinds of thing the deleted typed collections used to carry.

So: `PMI Dimension Entities`, `PMI Note Entities`, `PMI Datum Feature Symbol Entities`,
`PMI Datum Target Entities`, `PMI Feature Control Frame Entities`, `PMI Line Weld Entities`,
`PMI Spot Weld Entities`, `PMI Surface Finish Entities`, `PMI Measurement Point Entities`,
`PMI Locator Entities`, `PMI Reference Geometry Entities`, `PMI Coordinate System Entities`,
`PMI Entities`, `PMI 3D Data` are **genuinely 9.5-only byte layouts**, fourteen of them, all
mandatory on the wire in every 9.5 PMI Manager. `PMI Property Atom` → `Key PMI Property Atom`
and `Generic PMI Entities` → `Generic PMI Entity` *are* mere retitlings (same layout modulo the
deltas below), and `Property Proxy Meta Data Element` is present in both under the same title
(the inventory's "9.5 only" for it is a diff artifact). `PMI Model View Sort Orders` is
genuinely v10-only.

**Consequence for the estimate.** The library's `readPmiManagerMetaDataElement`
(`MetaDataCodecs.kt:738`) is a v10-shaped reader. Pointed at a 9.5 PMI Manager it desynchronises
at byte 3 (v10 reads `U8+I16` where 9.5 has `I16+I16+I16`) and would then read thirteen typed
collections as a Design Group list. The library already refuses this correctly
(`MetaDataDocument.kt:199-201` returns a `null` decoder for `LsgGeneration.V9`, yielding
`ELEMENT_LAYOUT_UNVERIFIED` + opaque carry). Adding 9.5 PMI is a **large** package: fourteen new
collections, six version-gated branches, and four scalar/​string-type deltas inside the shared
collections. It is *not* a matter of re-using the v10 codecs with a generation flag.

---

## Part 1 — ledger rows

Read/Write describe the **JT 9 path** as it stands today. `n/a: writer emits v10` is the
authoring entry throughout — `writeJt` authors no §11 segment in any generation.

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| Fig. 133 — Meta Data Segment data collection (p.162) | Fig. 107 | identical | done | done (re-serialization) | `meta/MetaDataDocument.kt:87` | Segment Header + repeated Meta Data Element; neither reference draws the end-of-elements marker or the trailing Property Table. §7.1.3.2.3 (p.32) puts the ZLIB header on the **first element only**, so after Layer-0 decompression the element stream is plain `I32 length + GUID + body` exactly as in v10 → the Figure-107-shaped expectation is correct for 9.5. See F-9. |
| §7.1.3.2.2 Element Header (p.31) *(framing dependency)* | §5 Element Header | identical | done | done | `MetaDataCodecs.kt:40` | `GUID Object Type ID + UChar Object Base Type + I32 Object ID`; Table 4 base type 9 = JtBase, as the code asserts. Object Type IDs for both meta elements are byte-identical to v10 (`0xce357247…` / `0xce357249…`). |
| Fig. 134 — Property Proxy Meta Data Element data collection (p.163) | Fig. 108 | widths | done | done | `MetaDataDocument.kt:194`, `MetaDataCodecs.kt:139`, `:23` | Only delta: `I16 Version Number` (9.5, "0x0001 is currently the only valid value") vs v10 `U8`. Same key loop, same guard wording, same four value branches. Confirms DESIGN.md delta 6 for this element — see F-6. |
| Table (unnumbered) — Property Value Type values (p.163) | Table 53 | identical | done | done | `MetaDataCodecs.kt:156` | 9.5 set is exactly `{0 Unknown, 1 MbString, 2 I32, 3 F32, 4 Date}` — **no 9.5-only value type**. See F-7. |
| Fig. 135 — Date Property Value data collection (p.164) | Fig. 109 | identical | done | done | `MetaDataCodecs.kt:110` | Six `I16` fields, same order, same names, no guards. |
| Fig. 136 — PMI Manager Meta Data Element data collection (p.166) | Fig. 110 | **structural** | opaque (`ELEMENT_LAYOUT_UNVERIFIED`) | opaque (verbatim) | `MetaDataDocument.kt:199`, `MetaDataCodecs.kt:738` | See F-1 for the field-by-field diff. Rev-D's *only* change to the whole document is "Fixed to Figure 136 … repair inconsistencies" (p.14), so this figure is the deliberately corrected one. |
| Value set — PMI Version Number 3…8 (p.166) | none | 9.5-only | — | n/a: writer emits v10 | — | Six PMI generations gate six branches (see F-2). v10 replaced the field with `I16 Empty Field`. |
| Fig. 137 — PMI Entities data collection (p.168) | **none** | 9.5-only | — | n/a: writer emits v10 | — | Thirteen typed collections, fixed order, all unconditional. Order verified from the page image. |
| Fig. 138 — PMI Dimension Entities data collection (p.168) | none | 9.5-only | — | n/a | — | `I32 Dimension Count` + `PMI 2D Data ×count`. |
| Fig. 139 — PMI 2D Data collection (p.169) | Fig. 120 | identical | — (v9 unreachable) | n/a | `MetaDataCodecs.kt:539` | `PMI Base Data`, `I32 Text Entity Count`, `2D Text Data ×count`, `Non-Text Polyline Data`. 9.5 **labels** the fourth box that v10 draws empty → confirms DESIGN.md delta 35 outright. See F-11. |
| Fig. 140 — PMI Base Data collection (p.170) | Fig. 121 | structural (guards) | — | n/a | `MetaDataCodecs.kt:520` | 9.5 draws both guards v10 lost: `2D-Frame Flag != 0` → 2D-Reference Frame (v10 keeps it in prose only — the code follows prose, so this **confirms** the code) and `PMI Version Number > 4` → `U8 Symbol Valid Flag` (**no v10 counterpart at all**). See F-3. |
| Fig. 141 — 2D-Reference Frame data collection (p.171) | Fig. 122 | identical | — | n/a | `MetaDataCodecs.kt:444` | Three `CoordF32`. |
| Fig. 142 — 2D Text Data collection (p.171) | Fig. 123 | identical | — | n/a | `MetaDataCodecs.kt:498` | `I32 String ID`, `I32 Font`, `I32 Reserved Field`, `F32 Reserved Field`, Text Box, Text Polyline Data. v10 renames the two reserved fields "Empty Field"; the model already stores them (`emptyFieldI32`/`emptyFieldF32`). Font value set identical to v10 Table 62 (15 values). |
| Fig. 143 — Text Box data collection (p.173) | Fig. 124 | identical | — | n/a | `MetaDataCodecs.kt:457` | Six `F32`, same order. |
| Fig. 144 — Constructing Text Polylines from data arrays (p.174) | Fig. 125 | identical (illustrative, non-normative) | n/a | n/a | — | Same worked example. |
| Fig. 145 — Text Polyline Data collection (p.174) | Fig. 126 | identical — **and both contradict the code** | — | n/a | `MetaDataCodecs.kt:473` | The `Polyline Segment Index Count > 0` guard box encloses **both** the index loop **and** `VecF32 Polyline Vertex Coords` in 9.5 Fig. 145 *and* in v10 Fig. 126 (verified from both page images). See F-4 — this corrects DESIGN.md delta 36. |
| Fig. 146 — Constructing Non-Text Polylines from packed 2D data arrays (p.175) | Fig. 127 | structural (illustrative) | n/a | n/a | — | 9.5's picture has an "Array of Polyline Type Values"; v10's adds an "Array of Polyline Width Values". Matches the wire delta below. |
| Fig. 147 — Non-Text Polyline Data collection (p.176) | Fig. 128 | **structural + widths** | — | n/a | `MetaDataCodecs.kt:484` | Three deltas: (a) `I16 Polyline Segment Index` in 9.5, `I32` in v10; (b) the `I32 Polyline Type Count` + `I16 Polyline Type ×count` pair is **gated on `PMI Version Number > 4`** in 9.5, unconditional in v10; (c) 9.5 has **no Polyline Width Count / Polyline Width arrays** — v10-only. `VecF32 Polyline Vertex Coords` unguarded in both. Type value set identical to Table 63. |
| Fig. 148 — PMI Note Entities data collection (p.177) | none | 9.5-only | — | n/a | — | `I32 Note Count` + per note `{PMI 2D Data, [PMI Version Number > 5] U32 URL Flag}`. |
| Fig. 149 — PMI Datum Feature Symbol Entities data collection (p.178) | none | 9.5-only | — | n/a | — | `I32 DFS Count` + `PMI 2D Data ×count`. |
| Fig. 150 — PMI Datum Target Entities data collection (p.178) | none | 9.5-only | — | n/a | — | `I32 Datum Target Count` + `PMI 2D Data ×count`. |
| Fig. 151 — PMI Feature Control Frame Entities data collection (p.179) | none | 9.5-only | — | n/a | — | `I32 FCF Count` + `PMI 2D Data ×count`. |
| Fig. 152 — PMI Line Weld Entities data collection (p.179) | none | 9.5-only | — | n/a | — | `I32 Line Weld Count` + `PMI 2D Data ×count`. |
| Fig. 153 — PMI Spot Weld Entities data collection (p.180) | none | 9.5-only | — | n/a | — | `I32 Spot Weld Count` + per weld `{PMI 3D Data, [PMI Version Number >= 4] CoordF32 Weld Point, DirF32 Approach/Clamping/Normal Direction}` (48 extra bytes when gated in). |
| Fig. 154 — PMI 3D Data collection (p.181) | **none** | 9.5-only | — | n/a | — | `PMI Base Data`, `I32 String ID`, `I16 Polyline Dimensionality` (2 or 3), `I32 Polyline Segment Index Count`, `I16 Polyline Segment Index ×count`, `VecF32 Polyline Vertex Coords`. **No `> 0` guard here** — unlike Fig. 145. The dimensionality field is 9.5's answer to what v10 infers from "is it a Generic PMI Entity". |
| Fig. 155 — PMI Surface Finish Entities data collection (p.182) | none | 9.5-only | — | n/a | — | `I32 SF Count` + `PMI 2D Data ×count`. |
| Fig. 156 — PMI Measurement Point Entities data collection (p.183) | none | 9.5-only | — | n/a | — | `I32 MP Count` + per point `{PMI 3D Data, [PMI Version Number >= 4] CoordF32 Location, DirF32 Measurement/Coordinate/Normal Direction}`. |
| Fig. 157 — PMI Locator Entities data collection (p.184) | none | 9.5-only | — | n/a | — | `I32 Locator Count` + `PMI 2D Data ×count`. |
| Fig. 158 — PMI Reference Geometry Entities data collection (p.184) | none | 9.5-only | — | n/a | — | `I32 Reference Geometry Count` + `PMI 3D Data ×count`. Table on p.184 derives the geometry kind from `Polyline Segment Index[1]` (`==1` point, `==2` polyline, `>2` polygon) — an interpretation rule, not bytes. |
| Fig. 159 — PMI Design Group Entities data collection (p.185) | Fig. 111 | **structural + widths** | — | n/a | `MetaDataCodecs.kt:278` | 9.5: `I32 Design Group Count`, per group `I32 Group Name String ID` then **`[PMI Version Number >= 3]` gating `I32 Attribute Count` + attribute loop** (guard extent verified from the page image). v10: `U32` counts, attributes unconditional. A PMI-version-2-or-lower 9.5 group is 4 bytes; the v10 codec would read 8. |
| Fig. 160 — Design Group Attribute data collection (p.186) | Fig. 112 | identical | — | n/a | `MetaDataCodecs.kt:282` | `I32 Attribute Type` (1 Integer / 2 Double / 3 String), branch value, `I32 Label String ID`, `I32 Description String ID`. Value set = v10 Table 54. |
| Fig. 161 — PMI Coordinate System Entities data collection (p.187) | none | 9.5-only | — | n/a | — | `I32 Coord Sys Count` + per system `{I32 Name String ID, CoordF32 Origin, CoordF32 X-Axis Point, CoordF32 Y-Axis Point}` = 40 bytes each. |
| Fig. 162 — PMI Associations data collection (p.188) | Fig. 113 | **structural (reorder + guard)** | — | n/a | `MetaDataCodecs.kt:329` | 9.5 per association: `Source Data, Destination Data, Reason Code, [PMI Version Number > 5] Source Owning Entity String ID, Destination Owning Entity String ID`. v10: `Source Data, Source Owning Entity String ID, Reason Code, Destination Data, Destination Owning Entity String ID`. Same five words, **different order**, and the last two are gated in 9.5. Count is `I32` (9.5) / `U32` (v10). See F-5. |
| Table (unnumbered) — Source Data bit allocation (p.189) | Table 55 | identical | — | n/a | `meta/MetaDataElements.kt:169` | Bits 0-23 id, 24-30 type (22 values, 0…21), bit 31 indirect flag. Identical value list. 9.5 says "all undocumented bits are reserved"; v10 says they "should be set to 0". |
| Table (unnumbered) — Reason Code values (p.189) | Table 56 | identical | — | n/a | — | Same 17 codes (0,1,2,5,10–17,72,73,98,99,100). |
| Fig. 163 — PMI User Attributes data collection (p.191) | Fig. 114 | widths | — | n/a | `MetaDataCodecs.kt:351` | `I32 User Attribute Count` (v10 `U32`), then `I32 Key String ID` / `I32 Value String ID` per attribute. 9.5's figure draws both fields; v10's figure garbles the second box (prose supplies it). |
| Fig. 164 — PMI String Table data collection (p.191) | Fig. 115 | **structural (string type)** | — | n/a | `MetaDataCodecs.kt:368` | 9.5 writes `String : PMI String` — §7.1.1 (p.24) defines `String` as `I32 Count + Count × U8`. v10 writes `MbString` (`I32 Count + Count × U16`). Every PMI string in a 9.5 file is **single-byte**. See F-8. |
| Fig. 165 — PMI Model Views data collection (p.192) | Fig. 116 | **structural** | — | n/a | `MetaDataCodecs.kt:382` | Same eleven fields (76 bytes/view). v10 **appends** `I32 Property Count` + `PMI Property ×count` inside the per-view loop; 9.5 has neither — its per-model-view properties live in the Figure-136 tail instead (see F-2). Count `I32` vs `U32`. |
| Fig. 166 — Generic PMI Entities data collection (p.194) | Fig. 119 | **structural (guard) + widths** | — | n/a | `MetaDataCodecs.kt:560` | 9.5 gates `U16 User Flags` on `PMI Version Number > 6` (guard extent verified from the page image); v10 reads it unconditionally. Count `I32` vs `U32`. All other fields and their order identical. |
| Table (unnumbered) — Generic PMI Entity Type values (pp.194–195) | Table 60 | v10 superset | — | n/a | — | 9.5 lists 36 codes 0x0001…0x0128; v10 adds 16 more (0x0230 Fastener PMI … 0x0308 Composite FCF). No 9.5-only code. Neither set is validated by the code, correctly. |
| Table (unnumbered) — Generic PMI User Flag values (p.195) | Table 61 | identical | — | n/a | — | Single documented bit `0x0001` "flat to screen only". |
| Fig. 167 — PMI Property data collection (p.196) | Fig. 117 | identical | — | n/a | `MetaDataCodecs.kt:263` | Key atom + value atom. |
| Table 7 — Common Property Keys and Their Value Encoding formats (pp.196–197) | Table 58 | identical (spelling) | n/a | n/a | — | Same 18 keys; v10 spells the four colour keys `…Colour`, 9.5 `…Color`. Interpretive, not wire. |
| Fig. 168 — PMI Property Atom data collection (p.198) | Fig. 118 (retitled *Key PMI Property Atom*) | **structural (guard)** | — | n/a | `MetaDataCodecs.kt:241`, `:219` | `MbString Value` then **`[PMI Version Number > 6] U32 Hidden Flag`**. v10 reads the flag unconditionally, and NX 10.5 writes it as one byte (DESIGN.md delta 32). Three encodings a 9.5-aware reader must distinguish: absent / U8 / U32. See F-10. |
| Fig. 169 — PMI CAD Tag Data collection (p.199) | Fig. 129 | identical | — | n/a | `MetaDataCodecs.kt:681` | `I32 CAD Tag Index Count`, indices, `Compressed CAD Tag Data`. The index-count formula lists the same fifteen entity counts in both documents — **including the twelve counts v10 no longer defines anywhere**, which is independent evidence that v10's chapter 11 is a deletion from this text. In 9.5 the formula is satisfiable; the library's v10 sum (`MetaDataCodecs.kt:778`, design groups + model views + generic entities) is a v10-only simplification. |
| §8.1.16 Fig. 242 — Compressed CAD Tag Data collection (p.285) *(embedded dependency)* | Fig. 154 | widths + structural | — | n/a | `encoding/CadTagData.kt:41` | Out of range but reached from Fig. 169: 9.5 opens with **`I16 Version Number`**, the library writes `U8` (v10). 9.5 also gates the whole body on `CAD Tag Count > 0`. Belongs to the compression package; flagged here because PMI is its only §7.2.6 consumer. |
| Fig. 170 — PMI Polygon Data (p.200) | Fig. 130 | **structural** | — | n/a | `MetaDataCodecs.kt:605` | Wholly different shape — see F-12. Also internally inconsistent: the figure labels the `NormalBinding == 1` box `VecF32: Vertices` and the `TextureBinding == 1` box `I16 : Reserved Field`, while the prose (p.201) says `VecF32: Normals` and `VecF32: Texture Coords`. `spec unclear`. |
| §7.2.7 PMI Data Segment (p.202) | Annex H | identical | done (framing) | done | `MetaDataDocument.kt:146` | "a PMI Data Segment should be treated exactly the same as a 7.2.6 Meta Data Segment" — the library's `isMetaDataSegment` already unions Table-6 types 3 and 4 for all generations. |
| §9.6 Metadata Conventions (pp.296–299) | §13 Metadata Conventions | identical in kind | n/a: interpretive | n/a | — | Tables 9/10 and the tessellation/miscellaneous tables constrain **property keys and their string encodings**, not the Property Proxy byte layout. See F-7. |

**Counts:** 42 rows. `identical` 15 · `widths` 3 · `structural` 11 · `9.5-only` 16 (14 layouts +
1 value set + 1 counted under Fig. 137's children) · `v10 superset` 1 · illustrative/interpretive 3.
(Rows can carry two labels; the totals above assign each row its dominant one.)

---

## Part 2 — findings

### F-1 · CONTRADICTION (structural) — the PMI Manager element is a different structure in 9.5, field for field. Cost: **large**

Read from the Rev-D page image of p.166 (the `pdftotext -layout` reflow of this figure is
unusable — it interleaves the two columns).

| # | 9.5 Figure 136 | v10 Figure 110 |
|---|---|---|
| 1 | `Logical Element Header ZLIB` | `Logical Element Header Compressed` |
| 2 | `I16 Version Number` (1 or 2) | `U8 Version Number` |
| 3 | `I16 PMI Version Number` (3…8) | `I16 Empty Field` |
| 4 | `I16 Reserved Field` | — |
| 5 | **`PMI Entities`** (Fig. 137 — 13 typed collections) | — |
| 6 | `PMI Associations` | `PMI Design Group Entities` |
| 7 | `PMI User Attributes` | `PMI Associations` |
| 8 | `PMI String Table` | `PMI User Attributes` |
| 9 | `[PMI Version Number > 5]` → `PMI Model Views`, `Generic PMI Entities` | `PMI String Table` |
| 10 | `[PMI Version Number > 7]` → `U32 CAD Tags Flag`, `[CAD Tags Flag == 1]` → `PMI CAD Tag Data` | `PMI Model Views` (unguarded) |
| 11 | `[Version Number > 1]` → `PMI Property × Model View Count` | `Generic PMI Entities` (unguarded) |
| 12 | …then `PMI Polygon Data` | `PMI Polygon Data` |
| 13 | …then `I32 Font Count` | `U32 CAD Tags Flag`, `[== 1]` → `PMI CAD Tag Data` |
| 14 | …then `{String Font Name, VecI32 Character Set, PMI Polygon Data} × Font Count` | `U32 Font Count`, `{MbString Font Name, VecU16 Character Set, PMI Polygon Data} × Font Count` |
| 15 | *(element ends)* | `PMI Properties × Property Count` |
| 16 | — | `PMI Model View Sort Orders` |

Nine independent structural differences: the three-field vs two-field prologue, the thirteen
typed collections, Design Groups' position, three version gates v10 has none of, the font
block's position (inside `Version Number > 1` in 9.5, unconditional in v10), the font name's
string type (`String` vs `MbString`, F-8), the character set's element width (`VecI32` vs
`VecU16`), the model-view property list's location, and v10's two trailing collections.

The library refuses this correctly today — `MetaDataDocument.kt:199-201` hands back a `null`
decoder for `LsgGeneration.V9`, producing `ELEMENT_LAYOUT_UNVERIFIED` plus verbatim carry — so
there is no *bug*; there is a **gap**, and it is the biggest one in the package. Doctrine notes
for whoever implements it: the reader must key everything off the `I16 PMI Version Number` it
just read (six live values, five gates), and the model must remember both version numbers
verbatim, because the same byte stream is unreadable without them. Neither fixture exercises
any of this — the 9.5 file carries **no §11 segment at all** (DESIGN.md, "the fixture inventory
that decided this package"), so the whole of F-1 is document-only and will stay that way until
Bernhard supplies a 9.5 file with PMI.

### F-2 · GAP → answered — 9.5 does **not** explain NX 10.5's undocumented PMI Manager tail. Cost: **n/a (the answer is negative)**

The brief asks whether 9.5's Figure 136 documents a structure at the position where DESIGN.md
delta 33 records NX 10.5 writing an undocumented block. It does not, and the answer is cleanly
negative rather than ambiguous: **in 9.5 the font loop is the last thing in the element.**
Nothing follows it. The older document describes *less* there, not more.

What 9.5 does add is a different tail, in a different place: the `Version Number > 1` branch
runs `PMI Property × Model View Count` → `PMI Polygon Data` → `I32 Font Count` → font blocks.
That reveals the evolutionary story — 9.5 carries **one PMI Property per model view** in the
manager tail, and v10 moved that list *inside* Figure 116's per-view loop as
`I32 Property Count` + `PMI Property ×count` (F-row for Fig. 165), then reused the freed tail
slot for the new segment-level `PMI Properties` and `PMI Model View Sort Orders`. Useful
context, but it does not decode a single byte of the 10.5 tail: the NX block sits after fonts,
which in 9.5 is past the end of the element.

Two secondary observations that keep delta 33 honest: (a) 9.5's font-block position is inside a
version gate, so "the fonts are last" is itself version-dependent even in 9.5; (b) delta 33
notes the large tails hold "20-byte records shaped exactly like PMI Associations" — 9.5's
association record is also 20 bytes when the `PMI Version Number > 5` fields are present
(5 × I32), which is suggestive but proves nothing, since v10's is 20 bytes too. **Recommendation:
leave `PMI_MANAGER_TAIL_UNDOCUMENTED` exactly as it is.** The 9.5 reference does not lift the
deferral, and DESIGN.md's deferral condition (a fixture with a non-zero Property Count or Sort
Order Count) remains the right trigger.

### F-3 · CONFIRMATION + GAP — PMI Base Data: 9.5 restores a guard v10's figure lost, and adds one v10 has not got. Cost: **trivial** (v10 side) / part of F-1 (9.5 side)

v10 Figure 121 (p.142, verified from the page image) draws `2D-Reference Frame` on the main
spine with **no guard** — the conditionality survives only in the prose. 9.5 Figure 140 (p.170)
draws the guard explicitly: `2D-Frame Flag != 0`. The library reads it conditionally
(`MetaDataCodecs.kt:523`). **The 9.5 document upgrades that from a prose-derived guess to a
drawn citation** — cheap and worth recording in `SPEC_COVERAGE.md`.

The second guard is a real 9.5-only field gate: `[PMI Version Number > 4] → U8 Symbol Valid
Flag`. v10 has no such gate and the library reads the flag unconditionally
(`MetaDataCodecs.kt:524`) — correct for v10, wrong for a 9.5 file with PMI Version ≤ 4.

*Spec-internal wobble worth recording:* the p.170 prose says the flag is stored "if the **Version
Number** as defined in 7.2.6.2 PMI Manager Meta Data Element is greater than 4", while the figure
says **PMI Version Number** > 4. Since the element `Version Number` only ever takes 1 or 2, "> 4"
is unsatisfiable for it — the figure is right and the prose is loose shorthand. The same
shorthand recurs verbatim at Fig. 147 (p.176), Fig. 148 (p.177), Fig. 153 (p.179), Fig. 156
(p.183) and Fig. 162 (p.189). Read them all as *PMI* Version Number. The single place where
plain `Version Number` genuinely means the element version is Figure 136's own tail guard
`Version Number > 1`.

### F-4 · CORRECTION TO DESIGN.md + producer-vs-document conflict — Text Polyline Data's `VecF32` **is** inside the guard, in both references. Cost: **trivial** to record, **small** to make lenient

DESIGN.md delta 36 states: *"Figure 126 gates **only** the index loop on `Polyline Segment Index
Count > 0`"*, and concludes the coordinate vector is unconditional. **That is a misreading of the
figure.** In both v10 Figure 126 (p.146) and 9.5 Figure 145 (p.174), read from the rendered page
images, the guard rectangle encloses the index loop *and* `VecF32 : Polyline Vertex Coords`; the
main spine bypasses both and rejoins below the `VecF32` box. Both boxes are drawn to the right of
the spine; the unguarded fields are on it. The two references agree with each other and
**disagree with the library** (`MetaDataCodecs.kt:473` reads both vectors unconditionally).

Delta 36's *byte evidence* stands and is not in dispute: NX 10.5 writes two zero counts (48-byte
fixed text records), i.e. it emits the empty `VecF32` even when the index count is zero. So this
is a **producer-vs-document conflict**, not a code bug — but DESIGN.md currently books it as
"the figure says X", when the figure says the opposite and the *producer* says X.

Per the doctrine: the lenient reader should accept both (probe — when the index count is zero,
decide from the remaining extent whether four more bytes are a `VecF32` count or the next
field); the model must remember **which variant it saw** (a boolean per text entity, or storing
the coordinate vector as nullable rather than empty-list) so re-serialization is byte-identical;
the strict writer follows the figure. Today's model cannot distinguish "absent" from "present
and empty", so a document-conformant producer's file would not round-trip. Fix: reword delta 36
(trivial) and make the field nullable with a probe (small).

### F-5 · CONTRADICTION (9.5-vs-v10 reorder) — PMI Associations field order differs. Cost: part of F-1

9.5 Figure 162 (p.188, page image): `Source Data → Destination Data → Reason Code → [PMI Version
Number > 5] Source Owning Entity String ID → Destination Owning Entity String ID`.
v10 Figure 113 (p.130): `Source Data → Source Owning Entity String ID → Reason Code →
Destination Data → Destination Owning Entity String ID`.

Same five `I32` words, permuted, and 9.5 gates the last two. `readPmiAssociations`
(`MetaDataCodecs.kt:331`) hard-codes the v10 permutation. Because all five are `I32`, a v10-order
read of a 9.5 association **consumes the right number of bytes and produces silently wrong
semantics** — the worst failure mode in this package, and one no length check can catch. It is
currently unreachable (V9 refuses the element), but it is the trap waiting for anyone who
"reuses the v10 codec with a flag". The 9.5 model needs its own association record, or a
generation-aware field permutation stored explicitly.

### F-6 · CONFIRMATION — Property Proxy Meta Data Element, v9 layout. Cost: **trivial** (documentation only)

DESIGN.md already claims this from the 9.5 reference, and the reference confirms it exactly:
9.5 Figure 134 (p.163) is v10 Figure 108 with `I16 Version Number` in place of `U8`, the same
`If Property Key string is not empty` loop guard, and the same four value branches. The Rev-C
revision list (p.13) even records "Fixed Figure 134 … to be more clear", so this is a
deliberately reviewed figure. `SPEC_COVERAGE.md`'s Property Proxy row can cite
`9.5 §7.2.6.1 / Fig. 134 (p.163)` instead of "per the v9.5 Figure 134 + delta 6".

Note the framing dependency also confirms: 9.5 §7.1.3.2.3 (p.32) puts the ZLIB triple
(`I32 Compression Flag`, `I32 Compressed Data Length`, `U8 Compression Algorithm`) on the
**first element of the segment only** — so the library's segment-level decompression plus a
plain `I32 length + GUID` element loop is right for 9.5, not just for v10.

### F-7 · CONFIRMATION (negative result) — no 9.5-only Property Value Type; §9.6 does not touch the layout. Cost: **none**

The brief flags `META_PROPERTY_VALUE_TYPE_UNKNOWN` as a guaranteed refusal risk if 9.5 defines a
value type v10's Table 53 omits. **It does not.** 9.5's list on p.163 is exactly
`{0 Unknown, 1 MbString, 2 I32, 3 F32, 4 Date}` — the same five, same codes, same meanings, same
"if the type equals 0 then no Property Value is written" rule. The risk is nil and no change is
needed.

§9.6 Metadata Conventions (pp.296–299) turns out not to constrain the Property Proxy *layout* at
all. It is a best-practice catalogue of property **keys** (`JT_PROP_MEASUREMENT_UNITS`,
`CAD_MASS_UNITS`, `CAD_VOLUME`, `Chordal::`, `Angular::`, `PMI_TYPE_TABLE`,
`JT_PROP_ORIGINATING_BREPTYPE`, `JT_PROP_NAME`, …) and it points at §7.2.1.2 Property *Atom*
Elements — the LSG-segment mechanism — not at §7.2.6.1. Its one wire-relevant observation: every
convention row lists "JT File Data Type = MbString", i.e. real producers encode even numeric
conventional properties (`CAD_VOLUME` as F64, `CAD_CENTER_OF_GRAVITY` as three space-separated
F64) **as strings** under value type 1. Value types 2/3/4 are therefore rare in practice — which
matches the fixture (30 bags, 151 properties, no Date value).

### F-8 · GAP — 9.5 PMI strings are single-byte `String`, not `MbString`. Cost: **small** (one codec, two call sites)

§7.1.1 (p.24) defines two string types: `MbString` = `I32 Count + Count × U16`, and
`String` = `I32 Count + Count × U8`. 9.5's PMI uses **both**, deliberately:

- Fig. 164 PMI String Table (p.191): `String : PMI String` — single-byte.
- Fig. 136 (p.166): `String: Font Name` — single-byte.
- Fig. 168 PMI Property Atom (p.198): `MbString : Value` — two-byte.
- Fig. 134 Property Proxy (p.163): `MbString` for both key and value — two-byte.

v10 raised the first two to `MbString` (Fig. 115, Fig. 110). A 9.5 reader that calls
`readMbString` on the PMI String Table (as `MetaDataCodecs.kt:370` does) reads twice the bytes
for every string in the table and desynchronises immediately. Easy to miss because the type name
differs by two characters; easy to fix once, because it is exactly two call sites in the 9.5
path. The paired delta: `VecI32 : Character Set` (9.5, 4 bytes per glyph id) vs `VecU16 :
Character Set` (v10, 2 bytes) at `MetaDataCodecs.kt:715`.

### F-9 · CONFIRMATION — the `META_DATA_STRUCTURE_UNRECOGNIZED` framing expectation is correct for 9.5. Cost: **none**

The brief asks whether 9.5's Figure 133 matches the library's Figure-107-shaped expectation. It
does, exactly: `Segment Header` then a repeated `Meta Data Element`, with no marker, no property
table and no count drawn — identical to v10 Figure 107, and equally under-specified. The two
things the library adds beyond both figures (the end-of-elements GUID terminator and the
trailing Figure-78 Property Table) are producer conventions in *both* generations, and 9.5's
Figure 11 (p.34) shows the LSG segment annotating its element loop "Until End-Of-Elements marker
reached … See Table 11", which makes the same terminator convention normative one section over.
`MetaDataDocument.decode` will frame a 9.5 meta data segment correctly; only the *element
bodies* differ. Nothing to change.

### F-10 · GAP — three encodings of the Hidden Flag must coexist. Cost: **small**

`readHiddenFlag` (`MetaDataCodecs.kt:219`) already carries two: `U8` for `V10_5` (delta 32) and
`U32` for `V10`. 9.5 Figure 168 (p.198, page image) adds a third state — **absent**, whenever
`PMI Version Number <= 6`. Since a PMI Property is two atoms and atoms appear inside every model
view and every generic entity, getting this wrong desynchronises the largest collections in the
element. Whatever the 9.5 model looks like, it must record which of {absent, U8, U32} it saw:
`PmiPropertyAtom.hiddenFlag` is currently a non-null `Int`, which cannot express "absent"
distinguishably from zero.

### F-11 · CONFIRMATION — Figure 120's unlabeled box is Non-Text Polyline Data. Cost: **trivial** (documentation only)

DESIGN.md delta 35 established this from the bytes: v10 Figure 120 draws a fourth, empty rounded
box at the end of PMI 2D Data, and §11.2.6.1.3 never says where Non-Text Polyline Data goes. **9.5
Figure 139 (p.169) labels that box `Non-Text Polyline Data`** — the same four boxes in the same
order, fourth one named. Delta 35 goes from fixture-inferred to cited. Upgrade the DESIGN.md
entry and the `SPEC_COVERAGE.md` Generic PMI Entities row with `9.5 §7.2.6.2.1.1.1 / Fig. 139
(p.169)`.

### F-12 · GAP — PMI Polygon Data is a different collection in 9.5. Cost: **small** (one codec, self-contained)

| | 9.5 Fig. 170 (p.200) | v10 Fig. 130 (p.150) |
|---|---|---|
| version | `I16 Version Number` (only 1 valid) | `U8 Version Number` |
| after version | `I32 Reserved Field` | `I32 PolygonData Element Count` |
| element list | `VecI32 vNumVerts` — its length *is* the element count | `VecI32 vNumVerts` (length must equal the declared count) |
| bindings | **inline per element**: `I32 NormalBinding`, `I32 ColorBinding`, `I32 TextureBinding`, `I32 PolygonDimension` | **parallel vectors up front**: `VecI32 vBindings` (3/element, order colour→normal→texture), `VecI32 vPolygonDimensions` |
| per-element arrays | PrimTypes, PrimIndices, VertIndices, Vertices + the three conditional arrays | same |

Note the binding **order flip**: 9.5 writes Normal→Color→Texture inline, v10's `vBindings`
triples are Color→Normal→Texture (v10 prose, p.152). `readPmiPolygonData`
(`MetaDataCodecs.kt:605`) implements the v10 shape only. This collection is reachable twice per
9.5 PMI Manager (the manager's own block and each font's glyphs), so it is on the critical path
for any 9.5 PMI work — but it is self-contained and does not depend on the rest of F-1.

Also record the 9.5 figure/prose contradiction from the ledger row: the `NormalBinding == 1` box
is labelled `VecF32: Vertices` (prose: Normals) and the `TextureBinding == 1` box is labelled
`I16 : Reserved Field` (prose: `VecF32: Texture Coords`). `spec unclear` — trust the prose, note
the figure, and let a fixture settle it.

### F-13 · NOTE — `encoding/CurveData.kt` is not a PMI dependency

The brief names it as a place "where PMI geometry reaches into" the encoders. It does not:
`CompressedCurveData` has exactly two consumers, `wireframe/WireframeDocument.kt:271` and
`wireframe/WireframeElements.kt:65`. 9.5 PMI geometry is entirely uncompressed `VecF32` /
`VecI16` polyline arrays (Figs. 145, 147, 154) plus `PMI Polygon Data` (Fig. 170) — no CDP, no
curve encoding anywhere in §7.2.6. The one real encoder dependency is
`encoding/CadTagData.kt`, via Fig. 169 → §8.1.16, and it carries its own width delta (`I16`
version in 9.5 vs the `U8` the library writes) that belongs to the compression package.
