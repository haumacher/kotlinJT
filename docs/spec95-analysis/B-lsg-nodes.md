# Package B — 9.5 §7.2.1 LSG Segment and §7.2.1.1.1 Node Elements (Figures 11–38)

Source: *JT File Format Reference Version 9.5 Rev-D*, §7.2.1 (p.33) through §7.2.1.1.1.10.8.1 (p.54).
v10 counterparts: *JT v10.0 Rev-C*, §6.1.1 (Figures 20–45).
Library: `src/commonMain/kotlin/de/haumacher/kotlinjt/lsg/`.

Every figure in the range was read from the rendered PDF page, not only from the `pdftotext`
dump — Figures 14, 27, 30, 33, 34 and 37 have branch geometry the layout dump destroys, and in
Figure 14's case the dump is actively misleading. All byte budgets below were re-derived by an
independent parse of both 9.5 fixtures (`fixtures-local/KR360-1.jt`, `fixtures-local/RB___E_01955.jt`),
not taken from the library or from issue #11.

---

## Part 1 — ledger rows

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| Fig. 11 — LSG Segment data collection (p.34) | Fig. 20 | `identical` | done | done | `LsgDocument.kt:68` | Segment Header, Graph Elements→EOE, **Property Atom Elements**→EOE, Property Table. 9.5 names the second list correctly where v10 Fig. 20 mislabels it — see finding 7 |
| Fig. 12 — Base Node Element data collection (p.35) | Fig. 21 | `identical` | done | done | `LsgCodecs.kt:372` | header collection renamed (9.5 "Logical Element Header ZLIB" / v10 "…Compressed") — package A's row |
| Fig. 13 — Base Node Data collection (p.35) | Fig. 22 | `widths` | done | done | `LsgCodecs.kt:96`, width at `:46` | only `I16: Version Number` (9.5) vs `U8` (v10). `U32 Node Flags`, `I32 Attribute Count`, `Attribute Count × I32 Attribute Object ID` identical. 9.5 pins the only valid version to `0x0001`; v10 defers to "local version numbers" |
| Fig. 14 — Partition Node Element data collection (p.36) | Fig. 23 | `structural` | partial | done | `LsgCodecs.kt:386` | 9.5 inserts `BBoxF32: Reserved Field` on the **main** path and puts `BBoxF32: Transformed BBox` on a branch guarded `(Partition Flags & 0x00000001) == 0`. Exactly one of the two is written, so the byte count matches v10 in both branches — but the *identity* of the box differs. Finding 2 |
| Fig. 15 — Vertex Count Range data collection (p.37) | Fig. 24 | `identical` | done | done | `Values.kt` `CountRange`, `LsgCodecs.kt:399` | `I32 Min Count`, `I32 Max Count` |
| Fig. 16 — Group Node Element data collection (p.38) | Fig. 25 | `identical` | done | done | `LsgCodecs.kt:434` | |
| Fig. 17 — Group Node Data collection (p.39) | Fig. 26 | `widths` | done | done | `LsgCodecs.kt:117` | `I16` vs `U8` version. Base Node Data, version, `I32 Child Count`, `Child Count × I32` identical |
| Fig. 18 — Instance Node Element data collection (p.40) | Fig. 27 | `widths` | done | done | `LsgCodecs.kt:448` | `I16` vs `U8` version; `I32 Child Node Object ID` identical |
| Fig. 19 — Part Node Element data collection (p.40) | Fig. 28 | `widths` | done | done | `LsgCodecs.kt:470` | `I16` vs `U8` version; trailing `I32` renamed *Reserved Field* (9.5) → *Empty Field* (v10). Same 4 bytes, same "ignore it" semantics |
| Fig. 20 — Meta Data Node Element data collection (p.41) | Fig. 29 | `identical` | done | done | `LsgCodecs.kt:492` | |
| Fig. 21 — Meta Data Node Data collection (p.41) | Fig. 30 | `widths` | done | done | `LsgCodecs.kt:138` | Group Node Data + `I16` (9.5) / `U8` (v10) version |
| Fig. 22 — LOD Node Element data collection (p.42) | Fig. 31 | `identical` | done | done | `LsgCodecs.kt:506` | |
| Fig. 23 — LOD Node Data collection (p.42) | Fig. 32 | `structural` | done | done | `LsgCodecs.kt:152` | 9.5 = Group Node Data, `I16 Version Number`, **`VecF32: Reserved Field`**, **`I32: Reserved Field`**; v10 = Group Node Data, `U8 Version Number`. Confirms DESIGN.md delta 7 outright — finding 5 |
| Fig. 24 — Range LOD Node Element data collection (p.43) | Fig. 33 | `widths` (+ inherited `structural` from Fig. 23) | done | done | `LsgCodecs.kt:520` | own fields identical: version, `VecF32 Range Limits`, `CoordF32 Center` (9.5 "Center"/NCS, v10 "Centre"/MCS — wording only) |
| Fig. 25 — Switch Node Element data collection (p.44) | Fig. 34 | `widths` | done | done | `LsgCodecs.kt:549` | `I16` vs `U8` version; Selected Child is **`I32`** in 9.5 and `U32` in v10 — same 4 bytes. 9.5's signed type is the coherent one: both prose passages say "−1 < Selected Child < Child Count" with −1 meaning *no child*. The library reads `I32` in both generations, i.e. it already follows 9.5 |
| Fig. 26 — Base Shape Node Element data collection (p.45) | Fig. 35 | `identical` | done | done | `LsgCodecs.kt:571` | |
| Fig. 27 — Base Shape Data collection (p.45) | Fig. 36 | `structural` | done | done | `LsgCodecs.kt:180`, reserved box at `:188` | 9.5 inserts `BBoxF32: Reserved Field` **before** `BBoxF32: Untransformed BBox`, unconditionally. Also `I32: Size` (9.5) vs `U32: Size` (v10). Confirms DESIGN.md delta 8 outright — finding 4 |
| Fig. 28 — Vertex Count Range data collection (p.47) | Fig. 37 | `identical` | done | done | `LsgCodecs.kt:195` | second, Shape-Node-scoped copy of the same layout; prose adds "−1 = maximum vertex count unknown" in both documents |
| Fig. 29 — Vertex Shape Node Element data collection (p.47) | Fig. 38 | `identical` | done | done | `LsgCodecs.kt:585` | |
| Fig. 30 — Vertex Shape Data collection (p.48) | Fig. 39 | `structural` | partial | partial | `LsgCodecs.kt:222`, guard at `:239`, writer at `:260` | 9.5 = Base Shape Data, `I16 Version`, `U64 Vertex Binding`, **Quantization Parameters**, **`(Version Number == 1) U64 Vertex Binding`**; v10 = Base Shape Data, `U8 Version`, `U64 Vertex Binding`. Code gates the second U64 on `version >= 2` — the exact inverse. Finding 1 |
| Fig. 31 — Quantization Parameters data collection (p.49) | none in v10 §6 — v10 Fig. 90 has the identical layout but only inside §7.1.4.1.2.2.1 Vertex Shape LOD Data | `9.5-only` (at this position) | done | done | `LsgCodecs.kt:232` | `U8 Bits Per Vertex` [0:24], `U8 Normal Bits Factor` [0:13], `U8 Bits Per Texture Coord` [0:24], `U8 Bits Per Color` [0:24] — field-for-field equal to v10 Fig. 90 |
| Fig. 32 — Tri-Strip Set Shape Node Element data collection (p.49) | v10 §6.1.1.10.3 (its figure is mis-numbered "Figure 22" in Rev-C, p.43) | `identical` | done | done | `LsgCodecs.kt:599` | header + Vertex Shape Data, nothing else — both documents |
| Fig. 33 — Polyline Set Shape Node Element data collection (p.50) | Fig. 40 | `structural` | opaque | opaque | `LsgCodecs.kt:614` | 9.5 adds **`(Version Number == 1) U64: Vertex Bindings`** after Area Factor; v10 Fig. 40 has no such field at all. Plus `I16` vs `U8` version. In V9 the typed decode currently *fails* (8 bytes unconsumed) and the element is carried opaquely. Findings 1 and 3 |
| Fig. 34 — Point Set Shape Node Element data collection (p.51) | Fig. 41 | `widths` | opaque | opaque | `LsgCodecs.kt:637`, guard at `:648` | figures agree field-for-field including the `Version Number == 1` guarded `U64: Vertex Bindings`; only the version width differs. The library restricts the guarded field to non-V9 (`:648`), so V9 decode fails. Findings 1 and 3 |
| Fig. 35 — Polygon Set Shape Node Element data collection (p.52) | Fig. 42 | `identical` | done | done | `LsgCodecs.kt:667` | header + Vertex Shape Data only, both documents. No 9.5 fixture carries one |
| Fig. 36 — NULL Shape Node Element data collection (p.52) | Fig. 43 | `widths` | done | done | `LsgCodecs.kt:682` | `I16` vs `U8` version. No 9.5 fixture carries one |
| Fig. 37 — Primitive Set Shape Node Element data collection (p.53) | Fig. 44 | `structural` | partial | done | `LsgCodecs.kt:703` | 9.5 = Base Shape Data, `I16 Version`, **`I32 Texture Coord Binding`**, **`I32 Color Binding`**, `I32 Texture Coord Gen Type`, `I16 Version`, Primitive Set Quantization Parameters. v10 replaces the two I32s with one `U64 Vertex Bindings` at the same offset. Same total width; the library's V9 path reads the U64 and therefore records a wrong decomposition. Finding 6 |
| Fig. 38 — Primitive Set Quantization Parameters data collection (p.54) | Fig. 45 | `identical` | done | done | `LsgCodecs.kt:718` | `U8 Bits Per Vertex`, `U8 Bits Per Color` |

**Counts:** 28 units — `identical` 12, `widths` 9, `structural` 6, `9.5-only` 1, `unchecked` 0.
Contradictions between the library's JT 9 path and the 9.5 document: **4** (findings 1, 2, 3, 6).

---

## Part 2 — findings

### 1. Both `Version Number == 1` guards fire at version 2 in real 9.5 files — producer vs. document (`trivial` guard, `small` with the model change)

**The document.** 9.5 Fig. 30 (p.48) gates the *second* `U64 : Vertex Binding` of Vertex Shape Data
on `Version Number == 1`. 9.5 Fig. 33 (p.50) and Fig. 34 (p.51) gate a `U64: Vertex Bindings` after
`F32 : Area Factor` on `Version Number == 1`. The prose of all three says the highest valid version
is `0x0002`. The guard idiom is unambiguous in the rendered figures: a box drawn around the field
with the condition attached to its left — the same idiom v10 Fig. 41 uses and the library already
implements for v10 point sets.

**The files.** Both NetAllied 9.5 fixtures write local version **2** everywhere in that family and
write **both** guarded U64s anyway:

| element | Base Shape Data ver | Vertex Shape Data ver | 2nd VSD U64 | node ver | node U64 |
|---|---|---|---|---|---|
| 11 × Tri-Strip (KR360-1) | 1 | 2 | present (= 0) | — | — |
| 12 × Tri-Strip (RB___E_01955) | 1 | 2 | present (= 0) | — | — |
| 5 × Polyline (KR360-1) | 1 | 2 | present (= 2) | 2 | present (= 0) |
| 1 × Point Set (KR360-1) | 1 | 2 | present (= 2) | 2 | present (= 0) |

Note the corroboration for the *identity* of the extra 8 bytes in Vertex Shape Data: Fig. 30 titles
both boxes `U64 : Vertex Binding`, and in every one of the 29 shape nodes the second value **equals
the first** (0 for tri-strips, 2 for polyline/point). That is a repeat of the same field, exactly as
the figure draws it — it is not some other U64 the library happens to be absorbing.

**The code.** `LsgCodecs.kt:239` reads the second Vertex Shape Data U64 when `version >= 2` — the
exact inverse of the figure's guard. `LsgCodecs.kt:648` reads the Point Set U64 only when
`generation != V9 && version == 1`, and `PolylineSetShapeNodeElement` has no field for it at all.
Neither fixture can distinguish `version >= 2` from `version == 1` *for byte consumption* on the
tri-strips — both readings consume the same bytes there because every element is version 2 and
carries the field. They differ on every version-1 file.

**The crux nobody can settle from the bytes.** The figures never say *which* `Version Number` the
guard names. Two readings survive:

* **(a) the collection's own version** — the natural reading, and the one the library uses for v10.
  Under (a) the NetAllied producer is non-conformant in three places at once, always in the same
  direction (field present where the document says absent).
* **(b) an enclosing version** — most plausibly Base Shape Data's, which is **1** in every element of
  both fixtures. Under (b) the files are perfectly conformant and the library is simply reading the
  wrong version variable.

The PDF's blue cross-reference text carries no link annotation (checked: pages 48/50/51 have no
`/Annots`), so the destination cannot be recovered from the document. Neither fixture has a Base
Shape Data version other than 1, so neither can discriminate (a) from (b). **This is Bernhard's
call, not mine.** It matters because it decides what the *strict writer* emits.

**What the doctrine requires either way.**
* *Lenient read:* resolve presence from the remaining length of the enclosing body, not from a
  hard-coded version test. The length oracle is exact for the observed case — after Quantization
  Parameters a polyline/point body has 6 bytes left if neither U64 is present, 22 if both are
  (the fixtures: 22), and 14 in the two single-present cases, which then need the secondary check
  that the `I16` at the candidate version offset is a plausible version. For tri-strip and polygon
  set, Vertex Shape Data ends the body, so 0 vs 8 remaining decides it outright.
* *Record, never normalize:* presence must be a model fact. `VertexShapeData.vertexBindings2` is
  already nullable, but `writeVertexShapeData` (`LsgCodecs.kt:260`) re-derives presence from
  `version >= 2` instead of from the nullability, so a document-conformant version-2 file that
  *omits* the field would gain 8 invented bytes on re-serialization — a latent losslessness hole
  today, not just after the fix. `PolylineSetShapeNodeElement` needs a new nullable `vertexBindings`;
  `PointSetShapeNodeElement.vertexBindings` exists but its writer (`:661`) has the same
  derive-from-version defect.
* *Not silent:* a named `LoadNote` when observed presence disagrees with the figure's guard.

### 2. 9.5 Partition Node: the box the library calls `transformedBBox` is the *Reserved Field* in both fixtures (`small`)

**The document.** 9.5 Fig. 14 (p.36) is **not** what the `pdftotext` dump suggests. Rendered, it
shows `MbString: File Name` flowing down into `BBoxF32 : Reserved Field` on the main path, with a
side branch guarded `(Partition Flags & 0x00000001) == 0` leading to `BBoxF32 : Transformed BBox`;
the two rejoin above `F32 : Area`. Exactly one BBoxF32 sits between the file name and the area:
*Reserved Field* when bit 0 is set, *Transformed BBox* when it is clear. v10 Fig. 23 has no reserved
field and writes the Transformed BBox unconditionally.

Consequence: the **byte count is identical to v10 in both branches** (one middle box, plus the
trailing Untransformed BBox iff bit 0). Only the field's identity changes. The prose is silent on
the conditionality — the Transformed BBox paragraph says nothing about it, where the Untransformed
BBox paragraph explicitly says "only present if Bit 0x00000001 … is ON". `spec unclear` on the
prose; the figure is unambiguous.

**The files corroborate the figure.** Both 9.5 fixtures set Partition Flags = `0x1`, and their
middle box is the canonical empty-box sentinel
`min = (FLT_MAX, FLT_MAX, FLT_MAX)`, `max = (−FLT_MAX, −FLT_MAX, −FLT_MAX)` — nothing a producer
would write as a transformed extent — while the trailing box holds the real geometry
(KR360-1: `(−2826, −2825.8, −0.1) … (2826, 2825.8, 2869.2)`). Under 9.5 Fig. 14 that is precisely
correct: bit 0 set ⇒ the middle box is Reserved.

**The code.** `LsgCodecs.kt:397` reads it as `transformedBBox` unconditionally, and
`PartitionNodeElement.transformedBBox` (`LsgElements.kt:187`) is a non-null field. So every 9.5
partition node in the model carries an inverted sentinel presented as a world bounding box. Bytes
round-trip fine; the *meaning* is wrong. Production code does not consume it (grep: only
`LsgCodecs.kt` and six test files read it), so the damage today is model semantics plus any probe
test that treats it as a world box — but a Layer 2 consumer that starts using it would silently get
an inverted box for JT 9 input.

*Model requirement:* the field must become discriminated (reserved vs. transformed) keyed on
Partition Flags bit 0, so re-serialization stays a projection and Layer 2 can tell whether a
transformed box exists at all.

### 3. Issue #11's byte budget — only "both U64s present" balances; the ordering question is already settled by the document (`trivial` to fix, blocked on finding 1's call)

Independent parse of `fixtures-local/KR360-1.jt` (LSG segment at file offset 458414, zlib payload
inflating to 6163 bytes, 60 frames). Element frame at inflated offset 1821, Polyline Set Shape Node,
object id 25: **141 body bytes** after the Object Type ID. Confirmed identical for object ids 26–29
and for the Point Set node id 30.

Fixed prefix (all readings): `U8 Object Base Type` + `I32 Object ID` = **5**; Base Node Data
(`I16` version, `U32` flags, `I32` count = 1, one `I32` id) = **14**; Base Shape Data after that
(`I16` version, 2 × `BBoxF32`, `F32` area, 3 × count range, `I32` size, `F32` compression level)
= **86**. Running total **105** — verified field by field against the bytes, and the two boxes are
byte-identical to each other, which independently re-proves finding 4.

| # | Reading | Vertex Shape Data | Polyline tail | Total | Balance vs 141 |
|---|---|---|---|---|---|
| A | Figures 30 + 33 taken literally, guard = own version (2 ⇒ absent) | 14 | 6 | 125 | **−16** |
| B | Library today (`version >= 2` ⇒ 2nd U64 present; no polyline U64) | 22 | 6 | 133 | **−8** ← the reported failure |
| C | Fig. 30 literal (absent) + polyline U64 present | 14 | 14 | 133 | **−8** |
| D | **Both guarded U64s present** | 22 | 14 | **141** | **0** ✔ |
| E | As D but polyline tail ordered `Version │ U64 │ Area Factor` | 22 | 14 | 141 | 0 ✔ (byte-indistinguishable, see below) |
| F | As D but no reserved BBox in Base Shape Data | 22 | 14 | 117 | −24 |
| G | As D but version numbers `U8` rather than `I16` (4 of them, one also inside the 105-byte prefix) | 21 | 13 | 137 | −4 |

**Only D/E balance, and D and E are the same field multiset.** Nothing else in Figures 27/30/33
offers a spare 8- or 16-byte field, so the residue is exactly the two guarded `U64`s and nothing
else. Reading F is refuted twice over (24 bytes left, and the two boxes are literally equal);
reading G is refuted by 4 bytes, which is an independent confirmation of DESIGN.md delta 6.

The actual 36 bytes from body offset 105:

```
02 00                                            I16  Vertex Shape Data version = 2
02 00 00 00 00 00 00 00                          U64  Vertex Binding            = 2
00 00 00 00                                      4×U8 Quantization Parameters   = 0,0,0,0
02 00 00 00 00 00 00 00                          U64  Vertex Binding (guarded)  = 2   ← +8
02 00                                            I16  Polyline version          = 2
00 00 00 00                                      F32  Area Factor               = 0.0
00 00 00 00 00 00 00 00                          U64  Vertex Bindings (guarded) = 0   ← +8
```

**Two corrections to the issue text.**

1. The issue's "open question" — whether the tail is `Version │ Area Factor │ Vertex Bindings` or
   `Version │ Vertex Bindings │ Area Factor` — **is settled by both references**: 9.5 Fig. 33/34
   (p.50/51) and v10 Fig. 41 (p.45) all draw `Version Number`, then `F32 : Area Factor`, then the
   guarded `U64: Vertex Bindings`. No fixture is needed.
2. The issue's supporting argument for the second ordering ("Area Factor decodes to 0.0, which
   §6.1.1.10.4 declares out of range, weak evidence for the second") **does not hold**: the entire
   12-byte tail after the `I16` version is zero, so Area Factor is `0.0` under *either* ordering.
   The bytes cannot discriminate, and they do not need to.
   Separately: `Area Factor = 0.0` is outside the documented range `(0,1]` in both references, in
   all six elements. That is a producer deviation in its own right — the value must be preserved
   verbatim and is a candidate for a named note, never a clamp.

What the issue could not know: the same off-document field is present in the **eleven Tri-Strip Set
Shape Nodes too** (their bodies are 127 = 105 + 22, not 119 = 105 + 14). They "decode clean" only
because `LsgCodecs.kt:239` already inverted Fig. 30's guard. So the file's disagreement with
Figure 30 is universal in this producer, not a polyline/point quirk — which is why finding 1's
question (which Version Number does the guard name?) has to be answered before the fix is written.

### 4. Confirmation — DESIGN.md delta 8 (Base Shape Data reserved bounding box) is correct (`n/a`, upgrade a guess to a citation)

DESIGN.md delta 8: *"Base Shape Data: v9 stores a reserved BBoxF32 before the untransformed box"*,
recorded as fixture-verified from the repeated 24-byte box. **9.5 §7.2.1.1.1.10.1.1, Figure 27,
p.45** confirms it outright and unconditionally: the collection is `Base Node Data`,
`I16: Version Number`, `BBoxF32 : Reserved Field`, `BBoxF32 : Untransformed BBox`, `F32 : Area`,
Vertex/Node/Polygon Count Range, `I32 : Size`, `F32 : Compression Level`. The per-field prose on
p.45 states "Reserved Field is a data field reserved for future JT format expansion" — no guard,
no condition. v10 Fig. 36 (p.40) has only the untransformed box. The library's `LsgCodecs.kt:188`
is right, and the DESIGN.md entry can be upgraded from *fixture-verified* to *9.5 §7.2.1.1.1.10.1.1
Fig. 27 p.45*.

One correction of degree, not of kind: the same figure types the size field **`I32 : Size`**, where
v10 Fig. 36 types it `U32 : Size`. `LsgCodecs.kt:198` reads `U32` in both generations, and
`BaseShapeData.size` is a `UInt`. Same four bytes, so nothing round-trips wrong; the model just
cannot represent a negative value the 9.5 document permits (9.5's prose only assigns meaning to
zero, so this is cosmetic — `trivial` if it is ever worth aligning).

Also worth recording: the writer's fallback at `LsgCodecs.kt:211`
(`data.reservedBBox ?: data.untransformedBBox`) matches what both fixtures actually contain — in
all 29 shape nodes of the two 9.5 fixtures the reserved box **equals** the untransformed box. (It is only in the *Partition*
node that the reserved box is the FLT_MAX sentinel — finding 2.)

### 5. Confirmation — DESIGN.md delta 6 (I16 version numbers) and delta 7 (LOD Node Data reserved fields) are correct (`n/a`)

**Delta 6** — *"Version Number fields are I16 in v9, one byte (U8/I8) in v10"*. Confirmed by every
figure in this range that carries one: 9.5 Figs. 13, 17, 18, 19, 21, 23, 24, 25, 27, 30, 33, 34, 36
and 37 (twice) all type it `I16`, against `U8` in v10 Figs. 22, 26, 27, 28, 30, 32, 33, 34, 36, 39,
40, 41, 43, 44. (Package C's Fig. 39 Base Attribute Data, p.55, is the `I16` vs v10 `I8` case, which
is where DESIGN.md's "U8/I8" wording comes from — also correct.) No exception anywhere in §7.2.1.1.1.
The second half of delta 6 — *"the element header carries the I32 Object ID after the base-type byte
in both generations"* — is confirmed by 9.5 §7.1.3.2.2 Element Header, Figure 9, p.31:
`GUID : Object Type ID`, `UChar : Object Base Type`, `I32 : Object ID`.

**Delta 7** — *"LOD Node Data: v9 carries a reserved VecF32 + I32 that v10 dropped"*. Confirmed
outright by 9.5 §7.2.1.1.1.7.1, Figure 23, p.42: Group Node Data, `I16: Version Number`,
`VecF32 : Reserved Field`, `I32 : Reserved Field`, both described as "reserved for future JT format
expansion". v10 Fig. 32 stops after the version byte. `LsgCodecs.kt:152` is right; both fixtures'
Range LOD nodes carry an empty VecF32 (count 0) and a zero I32.

### 6. Contradiction — 9.5 Primitive Set Shape Node has two `I32` bindings where the library reads one `U64` (`trivial`)

9.5 §7.2.1.1.1.10.8, Figure 37, p.53: Base Shape Data, `I16 : Version Number`,
**`I32 : Texture Coord Binding`**, **`I32 : Color Binding`**, `I32 : Texture Coord Gen Type`,
`I16 : Version Number`, Primitive Set Quantization Parameters. v10 Fig. 44 (p.47) has
`U8 Version`, `U64: Vertex Bindings`, `I32: Tex Coord Gen Type`, `U8 Version`, same quantization
block — i.e. v10 fuses the two 9.5 I32 fields into one U64 at the same offset.

`LsgCodecs.kt:715` reads `r.readU64()` for **all** generations, so a JT 9 primitive set node gets a
`vertexBindings` value that is really `(texCoordBinding << 32) | colorBinding` (big-endian files) or
the reverse (little-endian). Byte count is unaffected, so the element would round-trip byte-exactly —
this is a pure model-fidelity defect, and it is currently unexercised (neither 9.5 fixture has a
Primitive Set Shape Node, and SPEC_COVERAGE.md already marks the v10 row "spec-derived, not yet
fixture-verified"). Both 9.5 fields are documented as `= 0` None / `= 1` Per Vertex.

The fix is a generation switch in one codec plus two model fields; the *risk* is that it is
untestable against a real file today, so it should land behind the same strict-length check that
already protects the rest.

### 7. Confirmation — 9.5 Figure 11 names the LSG Segment's second element list correctly (`n/a`)

DESIGN.md records: *"Figure 20's second-list box is garbled in the reference PDF (it reads 'Texture
Coordinate Generator Attribute Elements'); the fixture confirms the list holds the Property Atom
Elements."* 9.5 §7.2.1, **Figure 11, p.34** settles it from the document side: the three boxes are
`Segment Header`, `Graph Elements` ("Until End-Of-Elements marker reached"), `Property Atom
Elements` ("Until End-Of-Elements marker reached"), `Property Table`. The v10 label is an
editing error in Rev-C; the library's reading is the documented one in the older revision.

### 8. Gap — nothing in this range is undecoded (`n/a`)

For completeness, since the ledger's job is to give every unit a fate: there is **no** 9.5 node-element
layout in Figures 11–38 that the library fails to decode for a structural reason. Every type in the
range has `v9Layout = true` (`LsgCodecs.kt:1513`–`1529`). The two `opaque` rows (Figs. 33, 34) are
opaque only because of finding 1's guard, not because the layout is unknown; the two `partial` rows
(Figs. 14, 37) consume the right number of bytes and only mis-name fields. Nothing here needs a new
element family — the whole package is `trivial`/`small` work.
