# Spec coverage ledger — JT v9.5 File Format Reference Rev-D

The sibling of [`SPEC_COVERAGE.md`](SPEC_COVERAGE.md), which tracks the v10 reference this
library was built from. This ledger exists because the library's **JT 9 support grew by
example** — every v9 delta in `DESIGN.md` was reverse engineered from two real 9.5 fixtures —
and Bernhard has now supplied the 9.5 reference itself (Rev-D, 2010; kept locally, never
committed: Siemens copyright, same rule as the v10 PDF).

Reverse engineering from fixtures found real deltas. What it cannot do is find what the
fixtures do not exercise, or tell a *correct* guess from a *lucky* one. The worked example
that opened this pass:

> 9.5 Figure 30 (Vertex Shape Data) gates its second `U64: Vertex Binding` on
> `Version Number == 1`. `LsgCodecs.kt:239` reads it when `version >= 2`. Both readings
> consume the same bytes on the fixture that "verified" it — the fixture is version 2 and
> carries the field — so no fixture in the corpus can tell them apart, yet they disagree on
> every version-1 file in existence. And the fixture's bytes contradict the figure's guard.

The ledger's job is to give **every normative unit of the 9.5 document an explicit fate**, and
to say for each whether the library's behaviour rests on the document, on a fixture, or on a
guess.

## The governing doctrine: lenient when reading, strict when writing

*(Bernhard, 2026-08-01.)* Where the 9.5 document and a real producer disagree — and they
demonstrably do — the reader accepts **both** readings and the writer emits only the
**document's**. A guard like Figure 30's `Version Number == 1` is therefore not a question to
settle once and hard-code: the reader resolves it from what is actually present (remaining
length, the enclosing collection's extent, the field's own plausibility), and the writer
follows the figure.

Two constraints keep this from eroding the guarantees in [issue #1](https://github.com/haumacher/kotlinJT/issues/1):

1. **Leniency is recorded, never normalized.** Layer 1 is lossless. If the reader accepts a
   variant the figure does not describe, the model carries *which variant it saw*, so
   re-serialization stays byte-identical. A reader that silently canonicalizes two encodings
   into one model field has broken round-tripping — that is a defect, not a convenience.
2. **Leniency is not silence.** Accepting an off-document encoding is still a deviation and
   earns a named `LoadNote` where it is material. Silence continues to mean "this matched the
   document".

This doctrine also settles what `Write` means in this ledger. The writer targets v10 (issue #1:
*write one version, read broadly*), so for almost every 9.5 unit the honest final Write state is
**byte-faithful re-serialization of what was read** — enough for Layer 1 losslessness on 9.5
input — and `n/a: writer emits v10` for authoring. Where a 9.5 unit is *only* ever
re-serialized, the Notes column says so.

## Unit of account and status vocabulary

Unchanged from `SPEC_COVERAGE.md`: the unit is the **data-collection figure** (a normative byte
layout — one decoder, one serializer, at least one committed test each); sections without
figures are tracked at section granularity. Note that 9.5 titles shared sub-collections
`X Data collection` with a capital D (`Base Node Data`, `Base Shape Data`, `Vertex Shape Data`,
`Base Attribute Data`); these are normative layouts and are counted.

Statuses are `—`, `opaque`, `partial`, `done`, `n/a: <reason>` as in the v10 ledger. This
ledger adds a **Delta** column, which is the reason it exists:

- `identical` — same fields, types, order and guards as the v10 counterpart, checked field by
  field. The JT 9 code path may simply share the v10 one.
- `widths` — same structure, differing scalar widths. Overwhelmingly this is 9.5's `I16`
  version numbers against v10's `U8`/`I8` (`DESIGN.md` delta 6).
- `structural` — fields added, removed, reordered or re-guarded. Enumerated per row.
- `9.5-only` / `v10-only` — present in one document and not the other.
- `unchecked` — not yet analyzed. An honest gap; never left silently.

And an **Evidence grade**, because the point of this pass is to know what we actually know:

- `spec` — the 9.5 document states it.
- `fixture` — a real 9.5 file demonstrates it and the document is silent or absent.
- `spec+fixture` — both, agreeing. The strongest grade.
- `conflict` — the document and a real file disagree. Resolved per the doctrine above: lenient
  reader, strict writer, variant recorded in the model. Every `conflict` row must name the
  finding that describes it.
- `guess` — neither. These are the rows this pass exists to eliminate.

## How 9.5 and v10 are numbered

The two documents do not share section or figure numbering, and citations must never be bare.
9.5 writes `Figure 33: Title`; v10 writes `Figure 40 — Title`. The chapter map:

| JT 9.5 Rev-D | JT v10 Rev-C | Ledger package |
|---|---|---|
| §6.2 Data Types | §4.2 Data Types | A |
| §7.1 File Structure (header, TOC, data segment, element framing) | §5 File Format | A |
| §7.2.1 LSG Segment | §6 LSG Segment | B (nodes), C (attributes), D (property atoms, property table) |
| §7.2.2 Shape LOD Segment | §7 Shape LOD Segment | E |
| §7.2.3 JT B-Rep Segment / §7.2.4 XT B-Rep Segment | §8 Precise Geometry Segment | G |
| §7.2.5 Wireframe Segment | §10 Wireframe Segment | G |
| §7.2.6 Meta Data Segment / §7.2.7 PMI Data Segment | §11 Meta Data Segment | F |
| §7.2.8 JT ULP Segment | *no counterpart* | G |
| §7.2.9 JT LWPA Segment | §9 JT LWPA Segment | G |
| §8 Data Compression and Encoding | §12 Data Compression and Encoding | H |
| §9 Best Practices | §13 Common Data Conventions and Constructs | A, D, H (split by topic) |

Inventory at a glance: 9.5 has **245** figures, of which **150** are titled `data collection`
(plus the capital-D shared collections); v10 has **236** and **124**. Roughly 111 data-collection
titles are common to both, ~39 are 9.5-only by title and ~13 are v10-only — those asymmetries
are where the structural deltas concentrate, and each package is charged with deciding whether
a title-level difference is a rename or a real restructuring.

## The analysis packages

Phase 1 is a read-only, spec-driven delta analysis, one agent per package, all eight running
against both documents and the current code. No package edits code; each writes one findings
file. The ledger tables below are assembled from their output.

| Package | Range | Why it matters |
|---|---|---|
| **A** Framing | §6.2, §7.1, §8.3 ZLIB, §9.3–9.4 | Segment type codes, compression flags, TOC entry width, `Logical Element Header ZLIB` vs v10's `Logical Element Header Compressed` |
| **B** LSG nodes | Figs. 12–38 | Holds the Figure 30 contradiction *and* the live bug ([#11](https://github.com/haumacher/kotlinJT/issues/11)): Figs. 33/34 gate a `U64: Vertex Bindings` on version 1, the file writes it at version 2 |
| **C** LSG attributes | Figs. 39–69 | Five shader/shadow attribute figures are 9.5-only by title — prime sources of opaque elements in real 9.5 files |
| **D** Property atoms & table | Figs. 70–80 | Late Loaded Property Atom is the hinge of late loading; the Property Table framing was found by trial |
| **E** Shape LOD | Figs. 81–100 | Densest package. Polyline/Point Set LOD bodies ([#12](https://github.com/haumacher/kotlinJT/issues/12)), TopoMesh Rep Data **V1 vs V2** where v10 has one, the 30/30/4 mask chunking, the hash formulas |
| **F** Meta data & PMI | Figs. 133–168 | Nearly the whole PMI entity family is 9.5-only *by title* — rename or restructuring decides the cost of 9.5 PMI |
| **G** B-rep, wireframe, ULP, LWPA | Figs. 101–132, 171–217 | B-rep stays `opaque` by doctrine (framing only); wireframe and LWPA get full treatment; **JT ULP has no v10 counterpart and no code at all** |
| **H** Compression & encoding | §8, §9.2, §9.5 | `Int32 CDP Mk. 2` and `Float64 CDP` are 9.5-only; v10's `Int64 CDP` is absent from 9.5. Confirms or refutes `DESIGN.md`'s least-grounded entries — the arithmetic-codec bit divergence under every compressed array |

## Phase 2 — implementation

Cut from this ledger once Phase 1 lands, ordered by what real 9.5 files actually contain rather
than by document order. The already-known work is filed as
[#11](https://github.com/haumacher/kotlinJT/issues/11) (Polyline/Point Set Shape *Node*
elements), [#12](https://github.com/haumacher/kotlinJT/issues/12) (their *LOD* bodies) and
[#14](https://github.com/haumacher/kotlinJT/issues/14) (over-strict acceptance invariants);
Phase 1 will re-scope all three against the document and add the rest.

Every implementation package inherits the standing rules of the working method, plus two that
belong to this ledger specifically:

- A row moves to `done` only with a committed test tagged `// spec: 9.5 Figure N` naming the
  9.5 figure — the v10 tag is a different citation and must not be reused.
- A row whose Evidence grade is `conflict` needs **two** tests: the lenient read of the
  producer's bytes, and the strict write of the document's encoding.

**Landed so far.** **P2** (issue #11, package B's Figs. 30/33/34/37): the `>= N` reading of local
version guards, applied library-wide; presence of the guarded `U64` vertex-binding fields
resolved from the element body's remaining length and written from the model's nullability; the
Primitive Set Shape Node's JT 9 binding split. Package B's three `conflict` rows are
`spec+fixture`, and its Evidence column is now filled in.

**P6 — the LSG model-correctness sweep** (package B's Fig. 14, package C's Figs. 48/53/54/55/56/61
and the State Flags / field-inhibit bit tables). Every item was a place where the library decoded
the right *number* of bytes into the wrong model, so the whole corpus stayed green while the
meaning was wrong: the Partition Node's middle bounding box (the reserved field read as a
transformed extent — the one with visible consequences, since the world-space probes were taking
their tolerance from an `Infinity` diagonal), Geometric Transform's `F32` element values, the
`U8` Shared Image Flag, the light family's relocated and version-gated Shadow Parameters, and
the two bit tables whose meanings invert between the generations. The lesson the package
enforces: **a byte-neutral defect needs a test that asserts meaning** — every fix here was
verified red-before-green by reverting it and watching a named test fail, because a byte count
never would have. Package C's Evidence column is now filled in.

---

# Phase 1 result

All eight packages landed 2026-08-01. **355 ledger rows**, ~84 findings, **21 contradictions**
between the library and the 9.5 document. The delta distribution across the packages:

| | identical | widths | structural | 9.5-only | unchecked |
|---|---|---|---|---|---|
| totals | ~90 | ~45 | ~66 | ~34 | 38 (35 of them JT ULP, deliberately) |

The headline: **`widths` is not the dominant delta.** The `I16`-vs-`U8` version-width story that
`DESIGN.md` tells is real but accounts for well under half the differences. `structural` — fields
added, moved, re-guarded, or renamed onto a different meaning — is nearly as large, and it is
concentrated in exactly the places no fixture exercised.

## The rule that came out of the pass

**Every `Version Number == N` guard in both documents means `>= N`, not `== N`.**

9.5 §9.4 and v10 §13 both define local versions as append-only: *"write the data from each local
version in order … readers read up to the maximum local version they support and then use the
segment length to skip over any data they may not understand."* A version-1 reader must be able
to read the version-1 prefix of a version-2 body, so **version 2 cannot remove a version-1
field**. A box guarded `Version Number == 1` therefore says *"this field belongs to local version
1"* — present whenever the version is at least 1.

This resolves the pass's central puzzle without positing producer error. 9.5 Figures 30, 33 and
34 gate `U64` fields on `Version Number == 1`; both NetAllied fixtures write version 2 and emit
them, in all 29 shape nodes. Under the literal reading the producer is non-conformant three times
over *and* the fields are dead in every 9.5 file (the prose says 2 is the highest valid version).
Under `>= N` the files are conformant and the library merely has the wrong comparison —
`LsgCodecs.kt:239`'s `version >= 2` was an empirical discovery of the right operator with the
threshold off by one, which is why every fixture passed.

The rule is library-wide and applies to the v10 path too (`LsgCodecs.kt:648`). **Validation
condition:** if any v10 element has a `== 1` guard over a field that a version-2 body genuinely
omits, widening would break it — the NIST round-trip is the arbiter and must stay green.

## The 21 contradictions, by kind

**Code reads the document wrongly** (the library is wrong, the document is clear):
Partition Node's `transformedBBox` is the *Reserved Field* box (B-2) · light family reads two
alpha factors unconditionally that 9.5 places after the payload at version 2 (C-F9) · Geometric
Transform `F32` read as `F64`, `Shared Image Flag` `U8` read as `U32` (C-F2) · Primitive Set
Shape Node's two `I32` bindings read as one `U64` (B-6) · PMI Associations field order (F-5) ·
PMI strings are single-byte `String`, not `MbString` (F-8) · the JT 9 arithmetic path refuses the
all-out-of-band packet §8.1.2 defines (H-5) · `FileHeader.kt:112`'s `version.wideOffsets`
conjunct (A-2).

**Document contradicts a real producer** — the doctrine's territory, lenient read + strict write:
the three `Version Number == 1` guards (B-1, resolved above) · Area Factor `0.0` outside the
documented `(0,1]` in all six polyline/point nodes (B-3) · `Payload Object ID` does not identify
the payload (D-F1) · Text Polyline Data's `VecF32` guard (F-4) · Deering quantization bit range
(H-8) · `Number of Vertex Attributes` vs mask popcount (E-6).

**The document contradicts itself** — reader must prefer the prose:
9.5 Figs. 93/94/95 omit the Base Shape LOD Data box Fig. 84 requires (E-5) · Primitive Set Shape
Element figure vs its own prose on field order (E-10) · three §8 figures contradict their prose
(H-10) · v10 is self-inconsistent on the Wireframe Rep `I16`/`U8` where 9.5 is consistent (G) ·
9.5's JT B-Rep `Version Number > 4` guard it can never satisfy (G-7).

**Tests assert what no revision makes normative:**
`BrepOpacityTest` requires the literal bytes `01 00 00 00 00 00` after every precise-geometry
element list — an NX 10.5 convention promoted to the doctrine's own proof (G-4). Same shape as
the two invariants in [#14](https://github.com/haumacher/kotlinJT/issues/14).

## What the pass upgraded, corrected and refuted

**Upgraded from guess/fixture to citation** — `DESIGN.md` deltas 1, 3, 4, 6, 7, 8, 10, 11, 12,
16, 18 (half), 20, 38. Delta 16's failure mode is now quantified: read the v10 way, the headers
agree for 22 bits and diverge at bit 23. Delta 20's 30/30/4 chunking is confirmed by three
independent 9.5 passages.

**Corrected** — delta 2 (v9 *does* have the conditional header GUID) · delta 14 (the "reserved
12-byte tail" is the documented TopoMesh Compressed Rep Data **V2** tail) · delta 17 (Rev-D
Appendix C §2.2 *does* describe the bitlength wire format, statement for statement — the
library's least-grounded decoder is now its best-cited one) · delta 36 (Figure 126's guard) ·
delta 37 (confirmed v10-only, so the code was already right) · the claim in
`LwpaDocument.kt:288`, `DESIGN.md:1036` and `SPEC_COVERAGE.md:258` that 9.5 does not document a
JT LWPA Element — it does, §7.2.9.1 with a GUID and Fig. 215, under segment type 24, with the
exact GUID `ObjectTypeIds.kt:89` already carries.

**Refuted risks** — two suspected problems do not exist, which is worth as much as a finding:
9.5's Property Value Type set is exactly v10's Table 53, so `META_PROPERTY_VALUE_TYPE_UNKNOWN`
cannot fire on a conformant 9.5 file (F-7); and the Property Table layout is genuinely
generation-independent, `I16` version and all (D-F7).

**Negative answers, recorded so nobody re-asks** — 9.5 does *not* explain NX 10.5's undocumented
PMI Manager tail; in 9.5 the font loop ends the element (F-2), so
`PMI_MANAGER_TAIL_UNDOCUMENTED` and its deferral condition stand unchanged. And 9.5 says nothing
normative relating the LSG's Vertex Count Range to any LOD count — `triangles + 2·strips` is a
producer convention the wording permits but does not require (E-13), which settles
[#14](https://github.com/haumacher/kotlinJT/issues/14) in favour of dropping the assertion.

## Cost re-estimates

Two deferrals were mis-costed in the existing record, both upward:

- **JT 9 wireframe** is `large`, not a width fix. The curve payload under §8.1.15 uses **Mk. 1**
  CDP framing (no leading Value Count) and `Float64CDP` — two codec families that exist nowhere
  in the codebase.
- **9.5 PMI** is `large`. v10 deleted the typed-entity family outright and folded thirteen
  collections into `Generic PMI Entities`; the corroboration is that v10's own CAD-tag index
  formula still sums fifteen entity counts, twelve of which v10 no longer defines. 9.5 PMI is not
  a flagged reuse of the v10 codecs.

And one downward: **9.5 LWPA decode** is `small` — three width swaps and one codec swap.

# Phase 2 — implementation packages

Ordered by value against what real 9.5 files contain, not by document order.

| # | Package | Closes | Cost | Why here |
|---|---|---|---|---|
| **P1** | Acceptance invariants: the two in `FixtureDiscoveryTest`, plus `BrepOpacityTest`'s byte pattern | [#14](https://github.com/haumacher/kotlinJT/issues/14), G-4 | trivial | Cheapest, and it is what keeps `KR360-1.jt` from turning the suite red while the rest lands. E-13 supplies the citation |
| **P2** | The `>= N` version-guard rule + Polyline/Point Set Shape **Node** elements | [#11](https://github.com/haumacher/kotlinJT/issues/11) | small | Bernhard's bug, first half. Includes the *live* losslessness hole: the writers re-derive field presence from `version` instead of model nullability |
| **P3** | Polyline/Point Set Shape **LOD** bodies, 9.5 layout | [#12](https://github.com/haumacher/kotlinJT/issues/12) | small | Second half. Six deltas from the v10 reader; the NULL-vs-Lag1 predictor is settled by a stored hash, and the point set's FGPV hash omits the face-group term |
| **P4** | LSG model-correctness sweep | B-2, B-6, C-F2, C-F7/F8/F9, A-1/A-2 | small | Every one spec-cited, every one currently silent-wrong rather than noisy-wrong |
| **P5** | The all-out-of-band arithmetic packet | H-5 | trivial | Four lines; the v10 path already does it |
| **P6** | 9.5 LWPA decode | G-1 | small | Newly cheap, and it removes a false statement from three files |
| **P7** | Int32 CDP **Mk. 1** and `Float64CDP` | H-2, H-3, G-2/G-3 | large | Gates JT 9 B-Rep, LWPA curve data, NURBS and the Primitive Set path. Nothing below it decodes without it |
| **P8** | 9.5 PMI | F-1, F-5, F-8, F-10, F-12 | large | Genuine restructuring; needs its own ledger pass |
| **P9** | Writer strictness | [#15](https://github.com/haumacher/kotlinJT/issues/15) | small | The strict half of the doctrine, and a v10 conformance bug in shipping code |

P1–P3 are one session's work and close both open 9.5 bugs. P7 is the gate for everything
precise-geometry-shaped and should be scheduled as its own milestone.

---

# Ledger

Assembled from the eight package analyses, 2026-08-01. Rows are in 9.5 figure order within each
package. No row is deleted once written; `unchecked` rows are honest gaps, not omissions.

## Package A — Framing: data types, file structure, segment framing, ZLIB

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §6.2 Table 1 — Basic Data Types (p.22–23) | Table 3 (p.14) | `identical` | done | done | `io/ByteReader.kt:36`–`121`, `io/ByteWriter.kt` | Same ten rows, same order, same wording: UChar, U8, U16, U32, U64, I16, I32, I64, F32, F64. Both byte orders implemented and tested (`PrimitivesTest`). |
| §6.2 Table 2 — Composite Data Types (p.23–25) | Table 4 (p.14–17) | `structural` | partial | partial | `io/ByteReader.kt:101`–`121`, `lsg/Values.kt` | 9.5-only rows: **CoordF64**, **HCoordF32**, **HCoordF64**. v10-only rows: **Mx4F64**, **VecI16**, **VecU16**. All shared rows (BBoxF32, CoordF32, DirF32, GUID, MbString, Mx4F32, PlaneF32, Quaternion, RGB, RGBA, String, VecF32, VecF64, VecI32, VecU32) are word-for-word identical. **Both tables are incomplete w.r.t. their own document** — see finding 7. |
| §7.1 Fig. 1 — JT File Structure (p.26) | Fig. 10 (p.18) | `identical` | done | done | `JtFile.kt:100` / `JtFile.kt:71` | Header → TOC Segment → Data Segment (looped). v10's figure carries a stray "Number of TOC entries" loop label the 9.5 figure lacks; no wire consequence. Prose delta: 9.5 says the TOC is "typically located either immediately following the File header … **or at the very end of the file**"; v10 §13 hardens this to "should be located … immediately following the file header". The region model (`FileRegion`) accepts either. |
| §7.1.1 Fig. 2 — File Header data collection (p.26) | Fig. 11 (p.19) | `widths` + naming | done | n/a: writer emits v10 (re-serialization `done`) | `FileHeader.kt:84` / `FileHeader.kt:55` | Field-by-field: `UChar Version`×80 ≡; `UChar Byte Order` ≡ (0 LsbFirst / 1 MsbFirst, identical); `I32 Reserved Field` ≡ v10 `I32 Empty Field` (**rename only**); **`I32 TOC Offset` vs v10 `U64 TOC Offset` — the one width delta**; `GUID LSG Segment ID` ≡; conditional `GUID Reserved Field` under guard `Reserved Field != 0` ≡ v10's `GUID Empty Field` under `Empty Field != 0` — **present in 9.5, contrary to DESIGN.md delta 2** (findings 2, 3). 9.5 prose additionally states the I32 Reserved Field "Must have the value 0" (finding 11). v9 header = 105 bytes; confirmed. Both branch arrows drawn as XOR — finding 1. |
| §7.1.2 Fig. 3 — TOC Segment data collection (p.28) | Fig. 12 (p.20) | `identical` | done | n/a: writer emits v10 (re-serialization `done`) | `Toc.kt:83` / `Toc.kt:68` | `I32 Entry Count` then `TOC Entry` × Entry Count. Identical box for box, identical prose. |
| §7.1.2.1 Fig. 4 — TOC Entry data collection (p.28) | Fig. 13 (p.21) | `widths` | done | n/a: writer emits v10 (re-serialization `done`) | `Toc.kt:38` / `Toc.kt:22`; size at `Toc.kt:66`, `Toc.kt:97` | `GUID Segment ID` ≡; **`I32 Segment Offset` vs v10 `U64`**; **`I32 Segment Length` vs v10 `U32`** (signedness delta as well as width — the code reads `readI32()` on the v9 path, matching 9.5 exactly); `U32 Segment Attributes` ≡. Entry = **16+4+4+4 = 28 bytes**, v10 = 32. **DESIGN.md delta 3 confirmed outright** (finding 3). The Segment-Attributes bit allocation is an unnumbered inline table in 9.5 (bits 0–23 reserved, bits 24–31 segment type) and is v10's numbered Table 5 — identical content. |
| §7.1.3 Fig. 5 — Data Segment data collection (p.29) | Fig. 14 (p.22) | `identical` | done | n/a: writer emits v10 (re-serialization `done`) | `JtFile.kt:161` / `Segment.kt:74` | Segment Header + Data. |
| §7.1.3.1 Fig. 6 — Segment Header data collection (p.29) | Fig. 15 (p.22) | `identical` | done | n/a: writer emits v10 (re-serialization `done`) | `JtFile.kt:175`–`188` / `Segment.kt:74`–`78` | `GUID Segment ID`, `I32 Segment Type`, `I32 Segment Length` — same types, same order, same prose ("should be equal to the length value stored with this segment's TOC Entry"). 24 bytes in both generations; `JtSegment.headerSize = 24` is correct for 9.5. |
| §7.1.3.1 Table 3 — Segment Types (p.30) | Table 6 (p.22–23) | `structural` (v10-only rows) | done | n/a: writer emits v10 | `SegmentKind.kt:9`–`31`; unknown-code path `JtFile.kt:190`–`208`, `UndefinedSegmentTypes.kt` | 9.5 defines exactly **19 codes**: 1 LSG (Y), 2 JT B-Rep (Y), 3 PMI Data (Y), 4 Meta Data (Y), 6 Shape (N), 7–16 Shape LOD0–LOD9 (N), 17 XT B-Rep (Y), 18 Wireframe Representation (Y), 20 ULP (Y), 24 LWPA (Y). v10 adds **30 MultiXT B-Rep (Y)** and **32 STEP B-rep (Y)** and nothing else; every shared code carries the identical label and the identical compression flag. Both notes (7–16 LOD ordering; when type 6 is used) are word-identical. Column header differs: 9.5 "ZLIB Applied?", v10 "Compression". `SegmentKind` is the v10 superset — finding 6. |
| §7.1.3.2 Fig. 7 — Data collection (p.31) | Fig. 16 (p.24) | `identical` (title of the right-hand box differs) | done | n/a: writer emits v10 (re-serialization `done`) | `JtFile.kt:211`–`217` | Two alternatives selected by Table 3: non-compressible ⇒ `Logical Element Header` + `Object Data`; compressible ⇒ `Logical Element Header ZLIB` + `Object Data` (v10: `Logical Element Header Compressed`). Same structure, same selector. |
| §7.1.3.2.1 Fig. 8 — Logical Element Header data collection (p.31) | Fig. 17 (p.24) | `identical` on the wire; **9.5 figure erratum** | done | n/a: writer emits v10 (re-serialization `done`) | `Elements.kt:61` `scanElements` | `I32 Element Length` then a folder box. **9.5 labels that box "Object Data"; v10 labels it "Element Header".** 9.5's own prose immediately below points at §7.1.3.2.2 Element Header, and Fig. 7 already places Object Data *after* the Logical Element Header, so the 9.5 box label double-counts. Wire bytes are unaffected: length, then GUID + UChar + I32, then type data. Finding 9. Prose for Element Length ("total length in bytes of the element Object Data") is word-identical in both. |
| §7.1.3.2.2 Fig. 9 — Element Header data collection (p.31) | Fig. 18 (p.24) | `identical` | done | n/a: writer emits v10 (re-serialization `done`) | `Elements.kt:71`–`77`; base-type check `lsg/LsgCodecs.kt:1610` | `GUID Object Type ID`, `UChar Object Base Type`, `I32 Object ID` — same types, same order. Prose delta: v10 adds "If the GUID is not found in Annex A, the reader should skip Element Length **+ 1** number of bytes"; 9.5 has no such sentence — finding 10. |
| §7.1.3.2.2 Table 4 — Object Base Types (p.32) | Table 7 (p.25) | `identical` value set (one label differs) | done | n/a: writer emits v10 | `Elements.kt:75` (captured), `lsg/LsgCodecs.kt:353`, `:1610` (enforced) | Codes 255, 0, 1, 2, 3, 4, 5, 6, 8, 9 in both; 7 undefined in both. Only difference: 9.5 names 255 "**Unknown** Graph Node Object", v10 "None". Base-type data-format column points at the 9.5 section numbers instead of v10's hyperlinked titles — same targets. |
| §7.1.3.2.3 Fig. 10 — Logical Element Header **ZLIB** (p.32) | Fig. 19 — Logical Element Header **Compressed** (p.25) | `widths` + value sets (title differs; **same collection, renamed**) | done | done for the v9 branch (`encodeLsgSegmentPayload` V9 emits flag 2 / algorithm 2 / zlib) | `JtFile.kt:266` `decodeCompressible`; `Segment.kt:12`; `codec/SegmentCodec.kt:38`–`90`; v9 writer `lsg/LsgDocument.kt:236`–`241` | **Same four boxes, same order, same guard.** All three compression fields sit inside one `If first Element within file Segment` branch that bypasses straight to `Logical Element Header` otherwise — verified from the page image; identical geometry in v10 Fig. 19. Deltas: (a) **`I32 Compression Flag` in 9.5 vs `U32` in v10** — same 4 bytes, signedness only; (b) flag value set **9.5 `= 2` ZLIB ON / `!= 2` OFF** vs v10 `= 3` LZMA ON / `!= 3` none; (c) algorithm value set **9.5 `1` none, `2` ZLIB** vs v10 `1` none, `3` LZMA; (d) 9.5 states the flag/algorithm value sets inline, v10 numbers them Table 8 / Table 9. `I32 Compressed Data Length` and the "Compression Algorithm is included in this count" rule are word-identical. **So: a rename, not a different collection** — finding 4. |
| §7.1.3.2.4 Object Data (p.33) — normative section, no figure | §5.1.3.2.3 (p.26) | `identical` | n/a: dispatch prose | n/a | `JtFile.kt:219`, `lsg/LsgCodecs.kt` | One sentence in both: interpretation depends on the Object Type ID of the Logical Element Header. |
| §8.3 ZLIB Compression (p.294) | none (v10 §12.2.5 is *LZMA compression*) | `9.5-only` | done | done | `codec/Zlib.kt`, `codec/SegmentCodec.kt:49` | 9.5: "essentially the same as that in gzip and Zip … The JT format uses **Version 1.1.2** of the ZLIB compression library." That is RFC 1950 zlib framing, which is what the fixtures carry (`78 9C`) and what `zlibInflate`/`zlibDeflate` implement. v10 has **no ZLIB section at all** (only bibliography ref [23]); its §12.2.5 specifies LZMA via XZ Utils. Symmetrically LZMA is `v10-only` and absent from 9.5. |
| §9.3 Reserved Field (p.295) | §13 "Empty Field" (p.196) | `identical` semantics, global **rename** | done | done | `FileHeader.kt:36` and every `emptyField`/`reservedTail` in the model | 9.5: "If you are writing a JT file whose data did not originate from reading a previous JT file, then Reserved Fields should be set to a value a '0' … If … originated from reading a previous JT file (i.e. rewriting a JT File), then 'Reserved Fields' should be written with the same value that was read from the originating JT file." v10's Empty Field paragraph is the **same two sentences with "Reserved"→"Empty"**. The example citation changes with the field name (9.5 §7.2.1.1.1.7.1 LOD Node Data "Reserved Field" → v10 LOD Node Data "Empty Field"). Finding 12. |
| §9.4 Local Version (p.295) | §13 "Local version numbers" (p.196) | `structural` (scope statement differs; v10 adds two sentences, 9.5 adds one) | partial | done (re-serialization) | `lsg/LsgCodecs.kt:46`–`60` (`readVersionNumber`: I16 in v9, U8 in v10/10.5) | 9.5 verbatim: *"The local version values seen throughout the data collections provides a simple means by which those data collections can be extended **within current and future minor versions of the 9.x file format**. The standard convention followed by each data collection, unless explicitly specified otherwise, is to write the data from each local version in order. This allows readers to read up to the maximum local version they support and then use the segment length that was read in the Segment Header to skip over any data they may not understand."* v10 replaces the 9.x-extensibility clause with the **closed** statement *"All version information for 10.0 JT data is included within this document"*, and adds *"Local version numbers are used for conditional branching as depicted in the element figures."* 9.5 has **no counterpart to v10 §13 "Version numbers"** (the 0x01/0x02/0x05 default list). Finding 8. |
| §9.2 Bit Fields (p.295) — *adjacent, in scope for reading every other figure* | §13 "Bit Fields" (p.196) | `identical` semantics | n/a: convention | n/a: convention | — | 9.5: undocumented bits "are reserved … should be set to '0' when writing". v10: "All bits fields that are not defined as in use shall be set to '0'." Same rule. |

**Row count:** 19 units. `identical` 8 · `widths` 3 · `structural` 4 · `9.5-only` 1 · identical-semantics-with-rename 2 · dispatch-prose 1. `unchecked`: none — every field and every guard in this range was compared box by box.

---


## Package B — 9.5 §7.2.1 LSG Segment and §7.2.1.1.1 Node Elements (Figures 11–38)

| 9.5 unit | v10 counterpart | Delta | Evidence | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|---|
| Fig. 11 — LSG Segment data collection (p.34) | Fig. 20 | `identical` | `spec+fixture` | done | done | `LsgDocument.kt:68` | Segment Header, Graph Elements→EOE, **Property Atom Elements**→EOE, Property Table. 9.5 names the second list correctly where v10 Fig. 20 mislabels it — see finding 7 |
| Fig. 12 — Base Node Element data collection (p.35) | Fig. 21 | `identical` | `spec` | done | done | `LsgCodecs.kt:372` | header collection renamed (9.5 "Logical Element Header ZLIB" / v10 "…Compressed") — package A's row |
| Fig. 13 — Base Node Data collection (p.35) | Fig. 22 | `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:96`, width at `:46` | only `I16: Version Number` (9.5) vs `U8` (v10). `U32 Node Flags`, `I32 Attribute Count`, `Attribute Count × I32 Attribute Object ID` identical. 9.5 pins the only valid version to `0x0001`; v10 defers to "local version numbers" |
| Fig. 14 — Partition Node Element data collection (p.36) | Fig. 23 | `structural` | `spec+fixture` | done | done | `LsgCodecs.kt:524`, identity at `:539` | 9.5 inserts `BBoxF32: Reserved Field` on the **main** path and puts `BBoxF32: Transformed BBox` on a branch guarded `(Partition Flags & 0x00000001) == 0`. Exactly one of the two is written, so the byte count matches v10 in both branches — but the *identity* of the box differs. Finding 2, **closed by P6** (DESIGN.md delta 43): the model discriminates them (`PartitionNodeElement.reservedBBox` / `.transformedBBox`, exactly one non-null, plus `extentBBox` for consumers), keyed on generation and flag bit 0. Both 9.5 fixtures put the `±FLT_MAX` empty-box sentinel in the reserved slot, which the library had been presenting as a world bounding box — the world-space probes were computing an `Infinity` diagonal from it and passing every containment check trivially. Tests: `LsgNodeElementCodecTest.partitionNodeMiddleBoxIsReservedInJt9WhenBitZeroIsSet` (both branches, both generations, both byte orders), `Lsg95PartitionBoxFixtureTest` (both 9.5 fixtures: the declared extent is never inverted) |
| Fig. 15 — Vertex Count Range data collection (p.37) | Fig. 24 | `identical` | `spec+fixture` | done | done | `Values.kt` `CountRange`, `LsgCodecs.kt:399` | `I32 Min Count`, `I32 Max Count` |
| Fig. 16 — Group Node Element data collection (p.38) | Fig. 25 | `identical` | `spec+fixture` | done | done | `LsgCodecs.kt:434` | |
| Fig. 17 — Group Node Data collection (p.39) | Fig. 26 | `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:117` | `I16` vs `U8` version. Base Node Data, version, `I32 Child Count`, `Child Count × I32` identical |
| Fig. 18 — Instance Node Element data collection (p.40) | Fig. 27 | `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:448` | `I16` vs `U8` version; `I32 Child Node Object ID` identical |
| Fig. 19 — Part Node Element data collection (p.40) | Fig. 28 | `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:470` | `I16` vs `U8` version; trailing `I32` renamed *Reserved Field* (9.5) → *Empty Field* (v10). Same 4 bytes, same "ignore it" semantics |
| Fig. 20 — Meta Data Node Element data collection (p.41) | Fig. 29 | `identical` | `spec+fixture` | done | done | `LsgCodecs.kt:492` | |
| Fig. 21 — Meta Data Node Data collection (p.41) | Fig. 30 | `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:138` | Group Node Data + `I16` (9.5) / `U8` (v10) version |
| Fig. 22 — LOD Node Element data collection (p.42) | Fig. 31 | `identical` | `spec` | done | done | `LsgCodecs.kt:506` | |
| Fig. 23 — LOD Node Data collection (p.42) | Fig. 32 | `structural` | `spec+fixture` | done | done | `LsgCodecs.kt:152` | 9.5 = Group Node Data, `I16 Version Number`, **`VecF32: Reserved Field`**, **`I32: Reserved Field`**; v10 = Group Node Data, `U8 Version Number`. Confirms DESIGN.md delta 7 outright — finding 5 |
| Fig. 24 — Range LOD Node Element data collection (p.43) | Fig. 33 | `widths` (+ inherited `structural` from Fig. 23) | `spec+fixture` | done | done | `LsgCodecs.kt:520` | own fields identical: version, `VecF32 Range Limits`, `CoordF32 Center` (9.5 "Center"/NCS, v10 "Centre"/MCS — wording only) |
| Fig. 25 — Switch Node Element data collection (p.44) | Fig. 34 | `widths` | `spec` | done | done | `LsgCodecs.kt:549` | `I16` vs `U8` version; Selected Child is **`I32`** in 9.5 and `U32` in v10 — same 4 bytes. 9.5's signed type is the coherent one: both prose passages say "−1 < Selected Child < Child Count" with −1 meaning *no child*. The library reads `I32` in both generations, i.e. it already follows 9.5 |
| Fig. 26 — Base Shape Node Element data collection (p.45) | Fig. 35 | `identical` | `spec` | done | done | `LsgCodecs.kt:571` | |
| Fig. 27 — Base Shape Data collection (p.45) | Fig. 36 | `structural` | `spec+fixture` | done | done | `LsgCodecs.kt:180`, reserved box at `:188` | 9.5 inserts `BBoxF32: Reserved Field` **before** `BBoxF32: Untransformed BBox`, unconditionally. Also `I32: Size` (9.5) vs `U32: Size` (v10). Confirms DESIGN.md delta 8 outright — finding 4 |
| Fig. 28 — Vertex Count Range data collection (p.47) | Fig. 37 | `identical` | `spec+fixture` | done | done | `LsgCodecs.kt:195` | second, Shape-Node-scoped copy of the same layout; prose adds "−1 = maximum vertex count unknown" in both documents |
| Fig. 29 — Vertex Shape Node Element data collection (p.47) | Fig. 38 | `identical` | `spec` | done | done | `LsgCodecs.kt:585` | |
| Fig. 30 — Vertex Shape Data collection (p.48) | Fig. 39 | `structural` | `spec+fixture` | done | done | `LsgCodecs.kt:307`, presence oracle at `:257`, writer at `:336` | 9.5 = Base Shape Data, `I16 Version`, `U64 Vertex Binding`, **Quantization Parameters**, **`(Version Number == 1) U64 Vertex Binding`**; v10 = Base Shape Data, `U8 Version`, `U64 Vertex Binding`. Finding 1, **settled by P2** (issue #11): §9.4's append-only local versions make the guard `>= 1`, so the producers are conformant and the old `version >= 2` was the right operator with the wrong threshold. Presence is now read from the body's remaining length (0 vs 8 where Vertex Shape Data ends the body) and written from the model's nullability, closing a losslessness hole that invented 8 bytes for a body legally omitting the field. Tests: `Lsg95ShapeNodeGuardTest.triStripSetResolvesTheGuardedFieldFromTheBodyLength`, `…polygonSetResolvesTheGuardedFieldFromTheBodyLength`, `Lsg95ShapeNodeFixtureTest` |
| Fig. 31 — Quantization Parameters data collection (p.49) | none in v10 §6 — v10 Fig. 90 has the identical layout but only inside §7.1.4.1.2.2.1 Vertex Shape LOD Data | `9.5-only` (at this position) | `spec+fixture` | done | done | `LsgCodecs.kt:232` | `U8 Bits Per Vertex` [0:24], `U8 Normal Bits Factor` [0:13], `U8 Bits Per Texture Coord` [0:24], `U8 Bits Per Color` [0:24] — field-for-field equal to v10 Fig. 90 |
| Fig. 32 — Tri-Strip Set Shape Node Element data collection (p.49) | v10 §6.1.1.10.3 (its figure is mis-numbered "Figure 22" in Rev-C, p.43) | `identical` | `spec+fixture` | done | done | `LsgCodecs.kt:599` | header + Vertex Shape Data, nothing else — both documents |
| Fig. 33 — Polyline Set Shape Node Element data collection (p.50) | Fig. 40 | `structural` | `spec+fixture` | done | done | `LsgCodecs.kt:705` | 9.5 adds **`(Version Number == 1) U64: Vertex Bindings`** after Area Factor; v10 Fig. 40 has no such field at all. Plus `I16` vs `U8` version. Findings 1 and 3, **closed by P2** (issue #11): `PolylineSetShapeNodeElement.vertexBindings` is a nullable model field, read from the body's remaining length (6 / 14 / 22 after Quantization Parameters) and written only when the model holds it; the five previously-opaque wireframe nodes of the 9.5 bug fixture now decode typed with zero notes. Tests: `Lsg95ShapeNodeGuardTest.polylineSetReadsBothGuardedFieldsAtVersionTwo` (lenient read), `…polylineSetWriterEmitsExactlyWhatTheModelHolds` (strict write), `…polylineSetRefusesAnUndecidableMixedEncoding`, `Lsg95ShapeNodeFixtureTest` |
| Fig. 34 — Point Set Shape Node Element data collection (p.51) | Fig. 41 | `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:735` | figures agree field-for-field including the `Version Number == 1` guarded `U64: Vertex Bindings`; only the version width differs. Findings 1 and 3, **closed by P2** (issue #11): the guard applies in *every* generation now (it was restricted to non-V9 and to `version == 1`), presence comes from the remaining length, and the writer follows the model. The bug fixture's point set decodes typed. Tests: `Lsg95ShapeNodeGuardTest.pointSetReadsBothGuardedFieldsAtVersionTwo`, `…pointSetWriterEmitsExactlyWhatTheModelHolds`, `…v10PointSetCarriesTheGuardedFieldAboveVersionOne`, `Lsg95ShapeNodeFixtureTest` |
| Fig. 35 — Polygon Set Shape Node Element data collection (p.52) | Fig. 42 | `identical` | `spec` | done | done | `LsgCodecs.kt:667` | header + Vertex Shape Data only, both documents. No 9.5 fixture carries one |
| Fig. 36 — NULL Shape Node Element data collection (p.52) | Fig. 43 | `widths` | `spec` | done | done | `LsgCodecs.kt:682` | `I16` vs `U8` version. No 9.5 fixture carries one |
| Fig. 37 — Primitive Set Shape Node Element data collection (p.53) | Fig. 44 | `structural` | `spec` | done | done | `LsgCodecs.kt:801` | 9.5 = Base Shape Data, `I16 Version`, **`I32 Texture Coord Binding`**, **`I32 Color Binding`**, `I32 Texture Coord Gen Type`, `I16 Version`, Primitive Set Quantization Parameters. v10 replaces the two I32s with one `U64 Vertex Bindings` at the same offset. Finding 6, **fixed by P2**: the JT 9 path reads the two `I32`s into `textureCoordBinding` / `colourBinding` and leaves `vertexBindings` null; JT 10 keeps the fused `U64` and leaves the split fields null. Byte-neutral, so no fixture could ever have caught it — and none carries a Primitive Set Shape Node, which is why the grade stays `spec`. Test: `LsgNodeElementCodecTest.primitiveSetShapeNodeElement` (both decompositions, both byte orders) |
| Fig. 38 — Primitive Set Quantization Parameters data collection (p.54) | Fig. 45 | `identical` | `spec` | done | done | `LsgCodecs.kt:718` | `U8 Bits Per Vertex`, `U8 Bits Per Color` |

**Counts:** 28 units — `identical` 12, `widths` 9, `structural` 6, `9.5-only` 1, `unchecked` 0.
Evidence: `spec+fixture` 20, `spec` 8, `conflict` 0, `guess` 0.
Contradictions between the library's JT 9 path and the 9.5 document: **4** found, **all 4
closed** — findings 1, 3 and 6 by package P2 / issue #11, finding 2 (the Partition Node
bounding-box identity flip) by package P6, the model-correctness sweep.

*(This table is the first to carry the `Evidence` column the legend defines; the other packages'
tables grow it as their implementation packages land. A row graded `spec` is one the document
states and no fixture in the corpus exercises — an honest gap, not a doubt about the reading.)*

---


## Package C — LSG Attribute Elements (JT 9.5 Rev-D §7.2.1.1.2, Figures 39–69)

| 9.5 unit | v10 counterpart | Delta | Evidence | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|---|
| Fig. 39 — Base Attribute Data collection (p.55) | Fig. 46 | `structural` + `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:266` / `:278` | v10 appends `U32 Field Final Flags` (absent in 9.5); version `I16`→`I8`. Byte-verified on 13 fixture elements. See F6. |
| §7.2.1.1.2.1.1 State Flags bit table (p.55) | v10 Table 15 | `structural` (semantics) | `spec` | done | n/a: semantics, not bytes | `LsgElements.kt:166` (`BaseAttributeData.accumulationFinal`), consumer `ReadScene.kt:277` | 9.5 `0x01` = **Accumulation Final**; v10 `0x01` = **Unused** (v10 moved per-field finals into Field Final Flags). Same byte, opposite meaning. F7, **closed by P6** (DESIGN.md delta 46): the bit is read only where it means something — the model discriminates by the presence of the Field Final Flags word, which JT 9 does not have — and the scene façade now *names* the JT 9 flag it cannot honour instead of ignoring it. Bits `0x02`/`0x04`/`0x08` are identical in both. Tests: `Lsg95AttributeLayoutTest.accumulationFinalIsAJt9ReadingOfStateFlagsBitZero`, `…theSceneNamesTheJt9AccumulationFinalFlagItCannotHonour` |
| §7.2.1.1.2.1.1 Field Inhibit Flags (p.55) | v10 §6.1.2.1.1 | `identical` (U32, bits 0–31, per-element assignment) | `spec` | n/a: semantics | n/a | — | Byte layout identical; the *assignments* differ per element (rows below). |
| Fig. 40 — Base Shader Data collection (p.56) | **none** | `9.5-only` | `spec` | — | — | — | `I16 Version`, `I32 Shader Language`, `U32 Inline Source Flag`, then `MbString Source Code` if flag==1 **else** `MbString Source Code Loc`, `I32 Shader Param Count`, `Shader Parameter × count`. See F1. |
| Fig. 41 — Shader Parameter data collection (p.58) | **none** | `9.5-only` | `spec` | — | — | — | `MbString Param Name`, 6 × `U32` (Param Type, Value Class, Direction, Semantic Binding, Variability, Reserved), then `U32 Value × 16` (64 bytes, fixed). See F1. |
| Fig. 42 — Material Attribute Element (p.61) | Fig. 47 | `structural` + `widths` | `spec+fixture` | done | done | `LsgCodecs.kt:738` | Version `I16`(1\|2) vs `I8`(1 only); `F32 Reflectivity` gated `Version==2` in 9.5, unconditional in v10; v10 adds `F32 Bumpiness`, 9.5 has **none**. Byte-verified on 13 elements. See F6. |
| §7.2.1.1.2.2 Material field-inhibit bits (p.60) | v10 Table 16 | `structural` | `spec` | n/a: semantics | n/a | `LsgElements.kt:138` (KDoc on `fieldInhibitFlags`) | 9.5 bit 1 = "Diffuse Color and Alpha (Legacy)"; v10 has no such row, so **bits 1–8 are shifted by one** relative to 9.5 (same shape of shift as the Texture Image row below). F8, **recorded by P6** (DESIGN.md delta 46): nothing interprets the word — it is carried verbatim as a `UInt`, which is exactly right — and the model now says in one place that any future interpretation must branch on the generation |
| §7.2.1.1.2.2 Material Data Flags bits (p.62) | v10 Table 18 | `identical` | `spec+fixture` | done | done | `LsgCodecs.kt:746` | `0x0010` Blending, `0x0020` Override Vertex Colours, `0x07C0` Src Blend Factor (bits 6–10), `0xF800` Dst Blend Factor (bits 11–15). Low nibble `0x000F` is **reserved in both**. Fixture value 14752 fully explained. F5, **closed by P6**: the v9 refusal on a set low nibble stands, but its stated reason was wrong — there is no "Common RGB Value" compact colour encoding in either generation, those names are a shared editorial artifact of the *inhibit* tables. Comment and DESIGN.md delta 11 corrected to "9.5 p.62 declares these bits reserved and we will not invent a meaning" |
| Fig. 43 — Texture Image Attribute Element (p.64) | Fig. 48 | `structural` | `spec` | opaque | opaque | `LsgCodecs.kt:1233` (`v9Layout=false`), gate `:1604` | 9.5: `LEH ZLIB`, `Base Attribute Data`, `I16 Version` (1\|2\|3), *[Texture Vers-1 Data]*, `Version>=2 → Vers-2`, `Version>=3 → Vers-3`. v10: version 1 only, one Vers-1 block. The unconditional block's label in the PDF literally reads "Because the" — a document defect. See F2, F3. |
| Fig. 44 — Texture Vers-1 Data collection (p.65) | Fig. 49 (v10 renamed 9.5's *Vers-3* to "Vers-1") | `structural` | `spec` | opaque | opaque | `LsgCodecs.kt:1233` | 9.5: Type, Environment, CoordGen, `I32 Texture Channel`, `U32 Reserved Field`, `U8 Inline Flag`, `I32 Image Count`, images/names. v10 inserts `I32 Tex Coord Channel` after Texture Channel and renames Reserved→Empty. 9.5 Texture Type enum = 7 values (0–6, Bump/Cube/Depth Map); v10 = 26 values. Channel range 9.5 `[0,31]`, v10 `[-1,2³¹−1]`. See F3. |
| Fig. 45 — Texture Environment collection (p.67) | Fig. 50 | `identical` | `spec` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1146` / `:1161` | 8 × `I32` (Border Mode, Mag Filter, Min Filter, S/T/R Wrap, Blend Type, Internal Compression Level), `RGBA Blend`, `RGBA Border`, `Mx4F32 Texture Transform`. Field-for-field checked, incl. all enum tables. |
| Fig. 46 — Texture Coord Generation Parameters (p.70) | Fig. 51 | `identical` | `spec` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1244` | `I32 Tex Coord Gen Mode × 4`, `PlaneF32 Tex Coord Reference Plane × 4`, S/T/R/Q order; 6 mode values in both. |
| Fig. 47 — Inline Texture Image Data collection (p.71) | Fig. 52 | `identical` | `spec` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:1208` | `Image Format Description`, `I32 Total Image Data Size`, then per mipmap `I32 Mipmap Image Byte Count` + texel bytes, `Mipmaps Count` times. |
| Fig. 48 — Image Format Description collection (p.72) | Fig. 53 | `widths` | `spec` | done | done | `LsgCodecs.kt:1387`, flag at `:1399`, width oracle at `:1435` | **9.5 `U8 : Shared Image Flag`; v10 `U32: Shared Image Flag`** — a 3-byte delta immediately before `I16 : Mipmaps Count`, so a misread walks the mipmap loop out of step rather than shortening the block. All other fields identical (`U32` Pixel Format, `U32` Pixel Data Type, 6 × `I16`, `I16 Mipmaps Count`). F2b, **closed by P6** (DESIGN.md delta 44): the width is resolved from the body — the image list ends the element, so it is parsed under each candidate and the one that consumes the body exactly wins — and recorded in `ImageFormatDescription.sharedImageFlagWidth`, so the re-encode is a projection. (The *enclosing* Texture Image element stays `v9Layout = false`: its three chained version blocks are F3's work, not this package's.) Test: `Lsg95AttributeLayoutTest.sharedImageFlagWidthIsResolvedFromTheImageList` |
| Fig. 49 — Texture Vers-2 Data collection (p.75) | **none** | `9.5-only` | `spec` | opaque | opaque | — | Leading "Texture Vers-1 Data : Stub" box = the *already-read* Fig. 43 block, not extra bytes (see F2). New fields = Vers-1 field set with the 26-value Texture Type enum and channel range `[-1,31]`. |
| Fig. 50 — Texture Vers-3 Data collection (p.78) | Fig. 49 (title "Texture Vers-1 Data") | `structural` | `spec` | opaque | opaque | — | This *is* v10's Vers-1 layout, except: leading Vers-2 stub reference (9.5 only) and `I32 Tex Coord Channel` sits at the **tail** in 9.5 but between Texture Channel and Empty Field in v10. Texture Type table identical (0–26). See F3. |
| Fig. 51 — Draw Style Attribute Element (p.81) | Fig. 54 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:793` (`v9Layout=false`) | Only delta: `I16` vs `I8` Version Number. `U8 Data Flags` bit table identical (0x01…0x20). |
| §7.2.1.1.2.4 Draw Style inhibit + Data Flags (p.80–81) | v10 Tables 34/35 | `identical` | `spec` | n/a: semantics | n/a | — | Inhibit bits 0–5 same rows, same order; data-flag bits identical. |
| Fig. 52 — Light Set Attribute Element (p.82) | Fig. 55 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:819` | Only delta: version width. `I32 Light Count` + `I32 Light Object ID × count`. |
| Fig. 53 — Infinite Light Attribute Element (p.83) | Fig. 56 | `structural` + `widths` | `spec` | done | done | `LsgCodecs.kt:1024`, tail at `:460` | 9.5 versions **1 and 2**; `Version==2` appends a `Shadow Parameters` block (2 × F32) — under §9.4's append-only local versions, present from version 2 upwards. v10 knows version 1 only and has no such tail. Figure box is mislabelled "Shadow Opacity" (its own caption points at §7.2.1.1.2.6.2 Shadow Parameters); version box is mislabelled "16 :". F9, **closed by P6** (DESIGN.md delta 45): the type now decodes typed in v9, presence of the tail comes from the body's remaining length and is recorded in `InfiniteLightAttributeElement.shadowParameters`, so a version-1 light cannot re-encode as a version-2 one. No fixture carries a light — hence the `spec` grade. Test: `Lsg95AttributeLayoutTest.infiniteLightV9CarriesShadowParametersOnTheElementFromVersionTwo` |
| Fig. 54 — Base Light Data collection (p.84) | Fig. 57 | `structural` + `widths` | `spec` | done | done | `LsgCodecs.kt:409` / `:437` | 9.5: `I16 Version`, 3 × `RGBA`, `F32 Brightness`, `I32 Coord System`, `U8 Shadow Caster Flag`, `F32 Shadow Opacity` — **and nothing else**. v10 additionally carries `F32 Non-shadow Alpha Factor` + `F32 Shadow Alpha Factor` here. F9 **closed by P6**: the pair is now read here only in the JT 10 generations (`BaseLightData.shadowParameters`, `null` in v9), where the code previously read all three trailing `F32`s in *every* generation. F10 **recorded, not resolved** (`spec unclear`): 9.5's figure omits the Base Attribute Data box entirely and v10 Figure 57 draws a stray element-header box in the same slot — both generations' drawings are corrupt there, the two candidate placements have equal width, and no fixture carries a light, so nothing can settle it. The library reads Base Attribute Data first per the attribute-element convention and says so in `BaseLightData`'s KDoc and in DESIGN.md's *Known spec ambiguities*. Tests: `Lsg95AttributeLayoutTest.infiniteLightV9…` (9.5 placement), `…infiniteLightV10KeepsShadowParametersInBaseLightData` (v10 placement) |
| Fig. 55 — Shadow Parameters collection (p.85) | **none** (absorbed into v10 Fig. 57) | `9.5-only` (placement) | `spec` | done | done | `LsgElements.kt:521` (`ShadowParameters`), `LsgCodecs.kt:460` / `:474` | `F32 Non-shadow Alpha Factor`, `F32 Shadow Alpha Factor`. Same two fields exist in v10 but *inside Base Light Data, unconditionally*; in 9.5 they hang off the **element** and only from element version 2. F9, **closed by P6**: one model type used from both placements, so the model always says which one the file used. Tests: `Lsg95AttributeLayoutTest.infiniteLightV9CarriesShadowParametersOnTheElementFromVersionTwo`, `…pointLightV9CarriesShadowParametersAfterSpotIntensity` |
| Fig. 56 — Point Light Attribute Element (p.86) | Fig. 58 | `structural` + `widths` | `spec` | done | done | `LsgCodecs.kt:1059`, tail at `:460` | Same shape as v10 (`HCoordF32 Position`, `Attenuation Coefficients`, `F32 Spread Angle`, `DirF32 Spot Direction`, `I32 Spot Intensity`) plus the `Version==2 → Shadow Parameters` tail and the `I16` version. F9, **closed by P6** exactly as the infinite light: typed in v9, tail presence from the body's remaining length, recorded in `PointLightAttributeElement.shadowParameters`. Test: `Lsg95AttributeLayoutTest.pointLightV9CarriesShadowParametersAfterSpotIntensity` |
| Fig. 57 — Spread Angle illustration (p.87) | Fig. 59 | `n/a: illustration` | `spec` | n/a | n/a | — | Not a byte layout. Clamping rule (`==180` point light; `0–90` spot) identical in both. |
| Fig. 58 — Attenuation Coefficients collection (p.88) | Fig. 60 | `identical` | `spec` | opaque (unreached in v9) | opaque | `LsgCodecs.kt:889` | 3 × `F32` (Constant, Linear, Quadratic), all `>= 0`. |
| Fig. 59 — Linestyle Attribute Element (p.88) | Fig. 61 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:925` | Only delta: version width. `U8 Data Flags` (`0x0F` Line Type 0–7, `0x10` Antialiasing) + `F32 Line Width`, identical tables. |
| Fig. 60 — Pointstyle Attribute Element (p.90) | Fig. 62 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:959` | Only delta: version width. `U8 Data Flags` (`0x0F` reserved Point Type, `0x10` Antialiasing) + `F32 Point Size`. |
| Fig. 61 — Geometric Transform Attribute Element (p.91) | Fig. 63 | `widths` | `spec` | done | done | `LsgCodecs.kt:1207`, width oracle at `:1194` | **Two** width deltas: version `I16`→`I8`, and **`F32 : Element Value` in 9.5 vs `F64: Element Value` in v10** — checked in both the figure box and the prose heading of p.91 (rendered). `U16 Stored Values Mask`, bit15→element0, `<<1` walk: identical. F2a, **closed by P6** (DESIGN.md delta 44): the type decodes typed in v9, and the value width is resolved from the body (`popcount(mask) × 4` against `× 8`, the values being the element's last field group) rather than hard-coded per generation, then recorded in `GeometricTransformAttributeElement.valueWidth` — widening an `F32` into the model's `Double` is exact but not reversible without it. Tests: `Lsg95AttributeLayoutTest.geometricTransformV9StoresF32ElementValues`, `…geometricTransformValueWidthComesFromTheBodyNotTheGeneration` |
| Fig. 62 — Shader Effects Attribute Element (p.92) | **none** | `9.5-only` | `spec` | — (unknown GUID → opaque + `UnknownElementType`) | opaque | gate `LsgCodecs.kt:1601` | GUID `0xaa1b831d,0x6e47,0x4fee,a8,65,cd,7e,1f,2f,39,**db**`. Fixed 34-byte payload after Base Attribute Data: `I16 Version`, `U32 Enable Flag`, `I32 Reserved 1`, `F32 Env Map Reflectivity`, `I32 Reserved 2`, `F32 Bumpiness Factor`, `U32 Reserved 3`, `U32 Phong Shading Flag`, `U32 Reserved 4`. See F1. |
| Fig. 63 — Vertex Shader Attribute Element (p.94) | **none** | `9.5-only` | `spec` | — (unknown GUID → opaque) | opaque | gate `LsgCodecs.kt:1601` | GUID `0x2798bcad,0xe409,0x47ad,bd,46,0b,37,1f,d7,5d,61`. Order is `LEH ZLIB`, `Base Attribute Data`, **`Base Shader Data`**, **`I16 Version Number` last** — verified against the rendered PDF page, not just pdftotext. See F1. |
| Fig. 64 — Fragment Shader Attribute Element (p.95) | **none** | `9.5-only` | `spec` | — (unknown GUID → opaque) | opaque | gate `LsgCodecs.kt:1601` | GUID `0xad8dccc2,0x7a80,0x456d,b0,d5,dd,3a,0b,8d,21,e7`. Same layout as Fig. 63, version last. See F1. |
| Fig. 65 — Texture Coordinate Generator Attribute Element (p.96) | Fig. 64 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:1106` | Only delta: version `I16` vs `U8`. `I32 Texture Coord Channel` + nested `Mapping Surface` element frame, identical. |
| Fig. 66 — Mapping Plane Element (p.97) | Fig. 65 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:1044` / `:1078` | `LEH ZLIB`, version, `Mx4F64 Matrix`, `I32 Coordinate System`; **no Base Attribute Data** in either generation. Only version width differs. |
| Fig. 67 — Mapping Cylinder Element (p.98) | Fig. 66 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:1085` | as above |
| Fig. 68 — Mapping Sphere Element (p.99) | Fig. 67 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:1092` | as above |
| Fig. 69 — Mapping TriPlanar Element (p.100) | Fig. 68 | `widths` | `spec` | opaque | opaque | `LsgCodecs.kt:1099` | as above |
| §7.2.1.1.2.3 Texture Image inhibit bits (p.63) | v10 Table 19 | `structural` | `spec` | n/a: semantics | n/a | — | 9.5 assigns Internal Compression Level to **bit 8** and leaves bit 7 unused; v10 assigns it to **bit 7**. Bits 0–6 identical. |
| §7.2.1.1.2.8 Linestyle Data Flags (p.88) | v10 Table 41 | `identical` | `spec` | n/a: semantics | n/a | — | 8 line types + antialias bit, same values. |
| §7.2.1.1.2.9 Pointstyle Data Flags (p.89) | v10 Table 42 | `identical` | `spec` | n/a: semantics | n/a | — | reserved point-type nibble + antialias bit. |

**Counts** — 37 rows. `identical` 10 · `widths` 11 · `structural` 9 · `9.5-only` 6 · `n/a` 1.
Byte layouts only (excluding the 8 semantics/table rows): 29 rows, of which `identical` 5,
`widths` 11, `structural` 6, `9.5-only` 6, `n/a` 1.
Evidence: `spec+fixture` 3 (the material family — the only attribute type either 9.5 fixture
carries), `spec` 34, `conflict` 0, `guess` 0.

Findings closed by package P6 (the model-correctness sweep): **F2a** (Geometric Transform
`F32` element values), **F2b** (`U8` Shared Image Flag), **F5** (the `0x000F` refusal's
rationale), **F7** (State Flags bit `0x01`), **F8** (the shifted field-inhibit assignments,
recorded), **F9** (Shadow Parameters' placement and its version-2 guard). **F10** is
`spec unclear` by construction and is recorded, not resolved — both generations' Base Light
Data figures are corrupt in the Base Attribute Data slot, the candidate placements have equal
width, and no fixture carries a light. Still open: **F1** (the four 9.5-only shader figures)
and **F3/F4** (the Texture Image family's three chained version blocks and the Vers-1↔Vers-3
rename), which keep the Texture Image element `v9Layout = false`; **F6** and **F11** are
confirmations and a gap inventory, not defects.

*(Read/Write on a `spec`-graded row means the layout is implemented from the document and
exercised by hand-built frames in both byte orders — not that a real 9.5 producer's bytes have
been through it. The three light and transform types P6 opened decode typed in v9 now; draw
style, light set, line/point style, textures and the mapping elements stay opaque-by-policy
until a fixture carries them.)*

---


## Package D — LSG Property Atom Elements and the Property Table (9.5 §7.2.1.2 / §7.2.1.3)

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| §7.2.1.2 Property Atom Elements (p.100) — the 8-type family and its key/value pairing | §6.2 (p.83) | `identical` — same eight types, same Object Type IDs (all eight GUIDs byte-for-byte equal, 9.5 pp.100–107 vs v10 pp.83–89); same "key identifies type and meaning of value" pairing rule | done | n/a: writer emits v10 | `LsgCodecs.kt:1509` (registry) | v10's "Key PMI Property Atom" is **not** a new LSG atom type — see F8 |
| Fig. 70 — Base Property Atom Element data collection (p.101) | Fig. 69 (p.83) | `identical` (LEH + Base Property Atom Data; the LEH's own name differs — 9.5 "Logical Element Header ZLIB" §7.1.3.2.3 vs v10 "Logical Element Header Compressed", a Package-A unit) | done | n/a: writer emits v10 | `LsgCodecs.kt:1302` | base type 5 (9.5 Table 4, p.32) — matches `objectBaseType = 5` |
| Fig. 71 — Base Property Atom **Data** collection (p.101) | Fig. 70 (p.83) | `widths` — 9.5 `I16: Version Number`, v10 `U8: Version Number`; `U32: State Flags` identical incl. the "bits 0–7 free, all others reserved" prose | done | n/a: writer emits v10 | `LsgCodecs.kt:291`; width switch at `LsgCodecs.kt:46` | 9.5 pins the value: "0x0001 is currently the only valid value". v10 §13.5.1 pins **0x02** for this family — see F2 |
| Fig. 72 — String Property Atom Element data collection (p.102) | Fig. 71 (p.84) | `widths` — element-local `I16` vs `U8` version; `MbString: Value` identical (9.5 §7.1.1 p.23 defines MbString as I32 count + count × U16, same as v10) | done | n/a: writer emits v10 (`writeJt` authors v10 string atoms) | `LsgCodecs.kt:1316` | fixture-verified 28 + 8 atoms |
| Fig. 73 — Integer Property Atom Element data collection (p.102) | Fig. 72 (p.85) | `widths` — version `I16` vs `U8`; `I32: Value` identical | done | n/a: writer emits v10 | `LsgCodecs.kt:1338` | no v9 fixture instance; layout is spec-derived for 9.5 |
| Fig. 74 — Floating Point Property Atom Element data collection (p.103) | Fig. 73 (p.85) | `widths` — version `I16` vs `U8`; `F32: Value` identical | done | n/a: writer emits v10 | `LsgCodecs.kt:1360` | no v9 fixture instance |
| Fig. 75 — JT Object Reference Property Atom Element data collection (p.104) | Fig. 74 (p.86) | `widths` — version `I16` vs `U8`; `I32: Object ID` identical | done | n/a: writer emits v10 | `LsgCodecs.kt:1383` | base type **6** (9.5 Table 4 p.32: "JT Object Reference Object") — matches the codec |
| Fig. 76 — Date Property Atom Element data collection (p.105) | Fig. 75 (p.87) | `widths` — version `I16` vs `U8`; the six date fields are `I16` in **both**, with identical ranges (Year [1900,2999], Month [0,11], Day [1,31], Hour [0,23], Minute [0,59], Second [0,59]) | done | n/a: writer emits v10 | `LsgCodecs.kt:1406` | fixture bytes `e4 07 0a 00 08 00 0f 00 32 00 08 00` = 2020-10(0-based)-08 15:50:08, body ends there → **no trailing F32 in 9.5**, confirming the 10.5-only guard (DESIGN.md delta 26). 9.5 prose bug: the Version Number paragraph says "for Late Loaded Property Atom Element" (copy-paste) |
| Fig. 77 — Late Loaded Property Atom Element data collection (p.106) | Fig. 76 (p.88) | `widths` — version `I16` vs `U8`. Everything else identical: `GUID: Segment ID` (9.5 §7.1.1 p.23 GUID = U32+2×U16+8×U8, same as v10), `I32: Segment Type`, `I32: Payload Object ID`, `I32: Reserved` ("guaranteed to always be greater than or equal to 1" — verbatim the same sentence in both) | done | n/a: writer emits v10 | `LsgCodecs.kt:1448` | **no payload-length field in either generation.** 41-byte bodies in both fixtures prove the I16-version reading exactly. See F6, F1 |
| Fig. 78 — Vector4f Property Atom Element data collection (p.107) | Fig. 77 (p.89) | `widths` — version `I16` vs `U8`; value is 4 × `F32` with **no count prefix** in both (the "4" is the figure's repeat annotation, and both texts say "VecF32 … with the length to be equal to 4") | done | n/a: writer emits v10 | `LsgCodecs.kt:1486`, `Values.kt:79` | GUID `0x2e7db4be…` is in the section body but **missing from 9.5 Table 11 (p.303)** exactly as it is missing from v10 Annex A — the same spec inconsistency in both editions |
| Fig. 79 — Property Table data collection (p.108) | Fig. 78 (p.90) | `identical` — `I16: Version Number` (**I16 in both**), `I32: Element Property Table Count`, then Count × (`I32: Element Object ID` + Element Property Table). No generational branch is needed or present | done | `done` (byte-faithful re-serialization); authoring n/a: writer emits v10 | `LsgDocument.kt:165` / `:187` | See F7 — this row upgrades DESIGN.md delta 12 from fixture-guess to citation |
| §7.2.1.3.1 / Fig. 80 — Element Property Table data collection (p.109) | Fig. 79 (p.91) | `identical` — repeat `I32: Key Property Atom Object ID`; while key ≠ 0 read `I32: Value Property Atom Object ID`. v10's figure draws the value's `If Key != 0` guard explicitly, 9.5's draws only the `While Key != 0` bracket, but 9.5's prose states the same rule ("A value is not stored if Key Property Atom Object ID has a value of 0", p.109) — same bytes | done | `done` (re-serialization); authoring n/a | `LsgDocument.kt:173`–`182` | terminator is per-element-table, not per-file |
| §9.1 Late-Loading Data (p.295) | §13.1 (p.196) | `identical` in substance — same list of late-loadable containers (Meta Data Node, JT B-Rep, XT B-Rep, Wireframe Rep, PMI Manager Meta Data, JT ULP, JT LWPA, Shape LOD), same "GUID looked up in the TOC Segment" resolution rule. Wording delta only: 9.5 "recommended as a best practice"; v10 "Initial loading of a JT file **shall** require the TOC and the LSG segments" | done | n/a: writer emits v10 | `ReadScene.kt:450`–`463`, `:613` | resolution is by segment GUID, which both editions mandate; see F1 |
| §9.6.1 / Table 9 CAD Property Conventions (p.296) | §13.8.3 / Table 77 (pp.200–201) | `structural` (content, not bytes) — **JT_PROP_MEASUREMENT_UNITS value set is identical**: millimeters, centimeters, meters, inches, feet, yards, micrometers, decimeters, kilometers, mils, miles (11 values, same order-independent set). CAD_MASS_UNITS set identical (micrograms…pounds). v10 **adds** CAD_FORCE_UNITS, CAD_MOMENT_OF_INERTIA, CAD_PROP_YOUNGS_MODULUS and the "UD_" prefix note; 9.5 adds nothing v10 lacks | done | `done` (Layer 2 read); n/a for write | `Scene.kt:168`–`199`, `ReadScene.kt:174` | The library's `LengthUnit` set matches **both** editions exactly. See F3 for the case-sensitivity divergence |
| §9.6.1.2 / Table 10 CAD Optional Property Units (p.297) | §13.8.3.2 (no numbered table; same content in Table 77's prose) | `identical` content (area = units², volume = units³, density = mass/units³, …) | `n/a: no byte layout` | n/a | — | Layer 2 does not interpret these properties (recorded as deferred in DESIGN.md) |
| §9.6.2 Tessellation Properties (p.297) | §13.8.4 / Table 78 (p.201) | `structural` — 9.5 names the keys **`Chordal::`** and **`Angular::`** (double colon baked into the key); v10 Table 78 names them `Chordal` / `Angular` and moves the "::" to the separate visible/hidden convention of §13.8.1.1.1 | `n/a: not consumed` | n/a | — | see F3 |
| §9.6.3 Miscellaneous Properties (untitled table, pp.298) | §13.8.5 / Table 79 (p.202) | `structural` — 9.5 carries one extra row, **`JT_PROP_TRISTRIP_DATA_LAYOUT` ("deprecated, no longer used")**, which v10 dropped. `PMI_TYPE_TABLE`, `JT_PROP_SHAPE_DATA_TYPE`, `JT_PROP_ORIGINATING_BREPTYPE`, `JT_PROP_NAME` are word-for-word identical, including JT_PROP_NAME's encoded form `"AlignmentPin.part;0;1:"` (Name / Version # / Instance #) | done (JT_PROP_NAME only) | n/a: writer emits v10 | `ReadScene.kt:222`, `:616` | the `^(.*);\d+;\d+:$` regex is correct for 9.5 too; fixture names e.g. `RB___E_01955.asm;0;0:` |
| *(cross-range, load-bearing)* §7.1.3.2.2 Table 4 Object Base Types (p.32) | §5.1.3.2.2 Table 7 | `identical` for the atom rows: 5 = Base Property Object, 6 = JT Object Reference Object, 8 = JT Late Loaded Property Object (no base type 7 in either) | done | n/a | `LsgCodecs.kt:1611` | the only normative statement of the atoms' base-type bytes; the codecs' 5/6/8 are right for 9.5 |

**Delta tally.** For the 11 figures in range: `identical` 3 (Fig. 70, 79, 80), `widths` 8
(Fig. 71–78), `structural` 0, `9.5-only` 0, `v10-only` 0. For all 18 rows above (figures plus
the section-level and convention-level units): `identical` 7, `widths` 8, `structural` 3,
`9.5-only` 0, `v10-only` 0, `unchecked` 0. Nothing in this range was left unchecked — every
field of every figure was compared, including guard conditions, field widths and the value
ranges stated in the per-field prose.

---


## Package E — Shape LOD Segment (JT 9.5 Rev-D §7.2.2, Figures 81–100)

`Read`/`Write` describe the **library's JT 9 path today**. `Write` is split by the doctrine: the
authoring writer (`write/ShapeAuthoring.kt:416`) hard-codes `LsgGeneration.V10`, so for every
9.5 unit the honest authoring entry is `n/a: writer emits v10`; the achievable target is
byte-faithful re-serialization of what was read, which is what `done (re-ser)` means.

| 9.5 unit | v10 counterpart | Delta | Evidence | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|---|
| Fig. 81 — Shape LOD Segment data collection (p.109) | Fig. 80 (p.92) | `identical` — Segment Header + Shape LOD Element, both boxes, same order | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:69` | 9.5 adds no Property Table box; the trailing 6-byte empty Property Table the library reads after the element list is *not* in either figure (both fixtures carry it — `trailingBytes: 6`). Pre-existing finding, unchanged. |
| §7.2.2.1 Shape LOD Element (p.109), prose | v10 §7.1.3 (p.92) | `structural` — 9.5 defines 6 concrete LOD element types (Null, Point Set, Polyline Set, Primitive Set Shape, Tri-Strip Set, Vertex Shape); v10 adds **Polygon Set LOD Element** (Fig. 84, GUID `0x10dd109f`). 9.5's Annex-A table (p.303) lists exactly those 6. 9.5 defines a Polygon Set Shape **Node** Element (Fig. 35, p.52) with *no* LOD-side element — see finding 9 | `spec+fixture` | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:164`, table at `:186-191` | JT 9 dispatch now admits tri-strip, **polyline set**, **point set** and null (issue #12, package P3); polygon set and primitive set remain `ElementLayoutUnverified` + opaque — 9.5 defines no polygon-set LOD element at all (finding 9), and the primitive set has no fixture and a self-contradicting figure (finding 10). Tests: `ShapeLod95PolylinePointTest`, `ShapeLod95FixtureTest` |
| Fig. 82 — Base Shape LOD Element data collection (p.110) | none (v10 folds Base Shape LOD Data into Fig. 85) | `structural` — 9.5 has an explicit abstract element figure (Logical Element + Base Shape LOD Data); v10 has none, its Fig. 85 nests Base Shape LOD Data as the first member of Vertex Shape LOD Data | `spec+fixture` | done (as realized) | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:245`, `:285` | Object Type ID `0x10dd10a4…` is given in §7.2.2.1.1 but absent from 9.5's Annex-A table — abstract, never framed standalone. Library has no constant for it; correct. |
| Fig. 83 — Base Shape LOD Data collection (p.110) | Fig. 86 (p.97) | `widths` — `I16 : Version Number` (9.5, only 0x0001 valid) vs `I8 : Version Number` (v10) | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:285` (read), `:307` (write) | *(fixture)* value 1 in all 29 JT 9 bodies. |
| Fig. 84 — Vertex Shape LOD Element data collection (p.110) | none (v10 has no element figure; §7.1.4.1 + Fig. 85) | `structural` — 9.5: Logical Element + **Base Shape LOD Data** + Vertex Shape LOD Data; v10 moves Base Shape LOD Data inside Vertex Shape LOD Data | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:297-303`, `:857-863`, `:890-896` | Realized identically by the tri-strip, polyline and point readers: two `I16` versions before the bindings. The derived-element figures 93/94/95 **omit** the Base Shape LOD Data box this figure requires — finding 5, a spec defect confirmed against the bytes of all 29 JT 9 bodies; Figure 84 is the one to follow. Tests: `ShapeLod95PolylinePointTest.polylineSetShapeLodDecodesTheSquareOutline`, `.pointSetShapeLodDecodesWithoutAFaceGroupSection` |
| Fig. 85 — Vertex Shape LOD Data collection (p.111) + the U64 Vertex Bindings bit table (pp.111-112) | Fig. 85 (p.95) + Table 48 (pp.95-96) | `structural` + `widths` — (a) version `I16` (9.5) vs `I8` (v10); (b) 9.5 does **not** contain Base Shape LOD Data (it sits one level up, Fig. 82/84), v10 does; (c) 9.5 has **no nested Logical Element Header** between the bindings and the TopoMesh collection, v10 does (DESIGN.md delta 27) — *(fixture)* confirmed absent in all 29 JT 9 bodies; (d) the bit table is field-for-field identical (bits 1-3 coords, 4 normal, 5-6 colour, 7 flags, 9-40 texcoord 0-7, 64 aux); v10 adds cosmetic "Bits 41-62 Unused"/"Bit 63" rows | `spec+fixture` | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:299-300`, `:860-862`, `:893-895`; v10 nested header `:530` | Both figures branch the same way: `If Tri-Strip Set Shape LOD Element` → TopoMesh Topologically Compressed LOD Data, else TopoMesh Compressed LOD Data. **Both branches are now taken in JT 9** (P3): the tri-strip takes the topological one, the polyline and point sets the compressed one — so "Tri-Strip Set" is literal, as the point-set body already showed. `partial` remains because colour / texture-coordinate / flag / auxiliary bindings still refuse with a note (no fixture binds them). Tests: `ShapeLod95PolylinePointTest`, `ShapeLod95FixtureTest` |
| Fig. 86 — TopoMesh LOD Data collection (p.112) | Fig. 88 (p.98) | `widths` — `I16 : Version Number` (9.5; 0x0001 **and 0x0002** valid) vs `U8`; `I32 : Vertex Records Object ID` vs `U32` (same width, signedness label only) | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:288` | *(fixture)* version is **2** in all 29 JT 9 bodies. Neither 9.5 nor v10 says what version 2 changes here; the figure shows no branch and byte consumption says nothing is added at this level. |
| Fig. 87 — TopoMesh Compressed LOD Data collection (p.113) — the 9.5 figure caption misreads "TopoMesh LOD Data collection", duplicating Fig. 86's title | Fig. 87 (p.97) | `structural` — 9.5: TopoMesh LOD Data, `I16 : Version Number`, then **`if version >= 2` → TopoMesh Compressed Rep Data V2, else V1**; v10: TopoMesh LOD Data, `U8 : Version Number`, one unversioned TopoMesh Compressed Rep Data | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:862`, `:895`; the V1/V2 resolution at `:352-384` | *(fixture)* version 2 in all 6 polyline/point bodies. **P3 implements the gate by length, not by version**: the element's own trailing `I16` is the only field after the representation, so the framed body has either 2 bytes left (V1) or 12 (V1 + the Figure-92 extension) — the two readings can never both fit, and the declared version is preserved rather than trusted. Tests: `ShapeLod95PolylinePointTest.theAuxiliaryVertexFieldExtensionIsPresenceNotVersion`, `.anUnaccountableTrailingRunRefuses` |
| Fig. 88 — TopoMesh Topologically Compressed LOD Data collection (p.113) | Fig. 91 (p.102) | `widths` on its face (`I16` vs `U8` version) — but see finding 1: §7.2.2.1.2.4 declares version **0x0002** valid while the figure shows **no** version branch, and the fixture proves version 2 appends 10 bytes the figure does not show | `conflict` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:303`; the extension at `:352-384`, model `ShapeLodElements.kt:160` `AuxiliaryVertexFieldData` | *(fixture)* version 2 in all 23 tri-strip bodies, each followed by the same 10 bytes. **`conflict`**: §7.2.2.1.2.4's prose declares version `0x0002` valid but the figure draws no branch for it, so the figure and the file disagree — finding 1. Resolved as Figure 87 documents for the sibling collection: the 10 bytes are the *TopoMesh Compressed Rep Data V2* auxiliary-vertex-field extension. P3 renames `reservedVersion`/`reservedBindings` to `AuxiliaryVertexFieldData` and corrects DESIGN.md delta 14. Tests: `ShapeLodDocumentTest.triStripSetElementDecodesTheTetrahedron`, `.aTriStripBodyWithoutTheAuxiliaryExtensionDecodesAndRoundTrips`, `ShapeLod95FixtureTest` |
| Fig. 89 — Topologically Compressed Rep Data Collection (p.115) + composite-hash pseudo-code (pp.115-116) | Fig. 92 (p.103) + pseudo-code (pp.104) | `structural` — the 8th attribute-mask context: 9.5 stores **three** packets (30 LSBs / 30 next MSBs / 4 MSBs), v10 **two** (32 LSBs / 32 MSBs). Everything else is field-for-field identical (8 face-degree packets, valences, groups, Lag1 flags, 8 mask packets, `VecU32` high-degree masks, Lag1 split-face syms, split-face positions, `U32` composite hash, then the vertex records). Packet generation differs: 9.5 `Int32CDP2` (§8.1.2, Mk. 2) vs v10's third-generation `Int32CDP` — PACKAGE H | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:409` (`context8Chunks`), hash `:459-475`, masks reassembled `:1055-1071` | Confirms DESIGN.md delta 20 outright — finding 4. Predictor annotations confirm delta 18 — finding 8. **P3 fixed the composite hash** (finding 7): the p.116 pseudo-code hashes the *derived* mask arrays — every context masked to 30 bits, and context 8's three projections each hashed with `anAttrMasks[7]` entries — where the library hashed the stored packets. Equal on both corpus producers, divergent for a producer that elides an all-zero upper chunk as an empty packet, which would have been false-refused. Tests: `ShapeLod95FixtureTest.elidedUpperMaskChunkStillVerifies`, `ShapeLod95PolylinePointTest.aZeroChunkAndAnElidedChunkHashDifferentlyWhenNotLengthened` |
| Fig. 90 — Topologically Compressed Vertex Records data collection (p.118) | Fig. 93 (p.106) | `structural` (minor) — identical left column (`U64` bindings, Quantization Parameters, `I32` num topological vertices, `if > 0` `I32` num vertex attributes) and identical binding-guarded right column, **except** v10 adds an eighth guarded box `if AuxField Bindings → Compressed Auxiliary Fields Array`; 9.5 has none here (aux lives in Fig. 92's V2 tail instead) | `spec+fixture` | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:393`; binding refusal mask `:326` | Depends on PACKAGE H for all five array layouts and on `Quantization Parameters` (9.5 §7.2.1.1.1.10.2.1.1, p.47 — PACKAGE B/C; v10 Fig. 90). JT 9 decodes coordinates + normals only; colour/texcoord/flag/aux bindings refuse with a note (`UNSUPPORTED_BINDING_MASK = ~0xF`). |
| Fig. 91 — TopoMesh Compressed Rep Data V1 data collection (p.119) + FGPV and unique-vertex-map hash pseudo-code (pp.119-120) | Fig. 89 (p.99) | `structural` — **six** deltas, all field-level: (1) the `I32 Number of Face Group List Indices` count **and** the Face Group List Indices array are guarded `if Polyline Shape` in 9.5, unconditional in v10; (2) the three index lists are `VecI32{Int32CDP2}` — **NULL predictor** — in 9.5, `VecI32{Int32CDP, Lag1}` in v10; (3) 9.5 adds `I32 : Number of Unique Vertex Coordinates` inside the `if number records > 0` branch, absent in v10; (4) counts are `I32` in 9.5, `U32` in v10 (same width); (5) the FGPV hash pseudo-code guards the face-group term `if (bLineStrip)` in 9.5, unguarded in v10; (6) v10 adds the `if AuxField Bindings → Compressed Auxiliary Fields Array` box, 9.5 does not (V2 carries aux) | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:933` (`readTopoMeshCompressedRepDataV1`), writer `:1023`, model `ShapeLodElements.kt:196` `TopoMeshCompressedRepDataV1`; v10 twin at `:734` | Layout established and byte-verified by the analysis pass (finding 2) and **implemented by P3, built from the document rather than copied from the v10 twin** — all six deltas are live: the `if Polyline Shape` guard over the count and the array, the **NULL** predictor on all three index lists (v10: Lag1 — arbitrated by body `453756`'s stored hash `0xbc4a3adf`, which NULL reproduces and Lag1 does not), the 9.5-only `I32 Number of Unique Vertex Coordinates`, the `I32` counts, the `if (bLineStrip)` guard in the FGPV hash, and aux living in the V2 tail. Tests: `ShapeLod95PolylinePointTest.theIndexListsAreNullPredictedNotLag1`, `.thePointSetFgpvHashOmitsTheFaceGroupTerm`, `ShapeLod95FixtureTest` |
| Fig. 92 — TopoMesh Compressed Rep Data V2 data collection (p.122) + Field Type table (p.123) + Auxiliary Data Hash pseudo-code (pp.123-124) | none as a collection; v10 relocates the payload to §12 Fig. 143 *Compressed Auxiliary Fields Array* (p.171) | `structural` / effectively `9.5-only` — 9.5: V1 + `I16 : Version Number` + `U64 : Vertex Bindings` + `if aux binding` { `U32` field count, then per field: `GUID` id, `U8` field type (46-entry type/components table), and a type-branched triple of `VecU32{Int32CDP2}` arrays (Exponents / Upper Mantissae / Lower Mantissae for floats; U32_0 / U32_1 / U32_2 for integers), `I32` Auxiliary Data Hash }. v10's Fig. 143 restructures the per-field record entirely: adds `U8 Unused Field`, `U8 Number of Quantization Bits`, `U8 Number of Steps`, a quantized/lossless branch with `Uniform Quantizer Data`, and reduces the arrays to LSW/MSW pairs | `spec+fixture` | partial | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:352-384`, model `ShapeLodElements.kt:160` `AuxiliaryVertexFieldData` | **The collection that explains the JT 9 "reserved" tail** — finding 1. P3 types the version + bindings prefix and reads its presence from the framed length. `partial` because the auxiliary field list itself is still unexercised — *(fixture)* bit 64 is clear in all 29 bodies — and a body that carries one **refuses by name** rather than being decoded from the document alone; its condition is the first fixture with an auxiliary vertex field binding. Tests: `ShapeLod95PolylinePointTest.theAuxiliaryVertexFieldExtensionIsPresenceNotVersion`, `.anAuxiliaryFieldListRefusesByName` |
| Fig. 93 — Tri-Strip Set Shape LOD Element data collection (p.125) | Fig. 81 (p.92) | `structural` + `widths` — trailing version `I16` (9.5) vs `U8` (v10); 9.5's figure omits the Base Shape LOD Data box (finding 5); v10 additionally carries the nested Logical Element Header inside Fig. 85 (delta 27); and the real 9.5 wire carries the Fig.-92 V2 tail the figure does not show (finding 1) | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:297`, model `ShapeLodElements.kt:339` `TriStripSetShapeLodElement` | Object Type ID identical in both revisions (`0x10dd10ab…`). The V2 tail the figure does not show is now typed, not reserved (finding 1). Tests: `ShapeLodDocumentTest.triStripSetElementDecodesTheTetrahedron`, `ShapeLod95FixtureTest` |
| Fig. 94 — Polyline Set Shape LOD Element data collection (p.125) | Fig. 82 (p.93) | `widths` at element level (trailing version `I16` vs `U8`), plus everything inherited from Figs. 85/87/91/92 | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:857` (`readPolylineSetShapeLod`), writer `:873`, model `ShapeLodElements.kt:461` `PolylineSetShapeLodElement` | Issue #12, **closed by P3**. Object Type ID identical (`0x10dd10a1…`). All five `KR360-1.jt` bodies decode typed with zero notes, every stored hash verifying, and `encode(decode(body))` byte-identical. Tests: `ShapeLod95PolylinePointTest.polylineSetShapeLodDecodesTheSquareOutline`, `.aPointSetBodyIsNotReadableAsAPolylineSet`, `ShapeLod95FixtureTest.decodesTypedAndRoundTrips`, `.geometryIsSane` |
| Fig. 95 — Point Set Shape LOD Element data collection (p.126) | Fig. 83 (p.94) | `widths` at element level (`I16` vs `U8`), plus the Fig.-91 deltas — **and the point set is not a "Polyline Shape"**: no Face Group count, no Face Group array, and the FGPV hash covers only the primitive and vertex lists | `spec+fixture` | done | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:890` (`readPointSetShapeLod`), writer `:906`, model `ShapeLodElements.kt:490` `PointSetShapeLodElement` | Issue #12, **closed by P3**. Object Type ID identical (`0x98134716,0x0011,…`). The point set is not a "Polyline Shape": no face-group count, no face-group array, and the FGPV hash covers only the primitive and vertex lists — reusing the v10 reader here would **false-refuse** a conformant file (finding 3). The one `KR360-1.jt` body decodes typed, hash-exact and byte-exact. Tests: `ShapeLod95PolylinePointTest.pointSetShapeLodDecodesWithoutAFaceGroupSection`, `.thePointSetFgpvHashOmitsTheFaceGroupTerm`, `ShapeLod95FixtureTest`. **Layer 2** (issue #13): the decoded body still stops at Layer 1 — the scene has no point concept and says so by name (`SCENE_GEOMETRY_UNAVAILABLE` on shape node #30), because growing one on the read side alone would turn that note into a `writeJt` refusal on this very fixture; DESIGN.md's deferral table pairs it with the v10 Point Set body |
| Fig. 96 — Null Shape LOD Element data collection (p.127) | Fig. 94 (p.107) | `widths` — `I16 : Version Number` (9.5) vs `U8` (v10); `BBoxF32 : Untransformed BBox` identical | `spec` | partial (spec-derived, no fixture) | done (re-ser); n/a: writer emits v10 | `ShapeLodDocument.kt:255`, writer `:266` | The library's JT 9 reading (`I16`) matches 9.5 §7.2.2.1.6 exactly — a confirmation, not a change. Still fixture-less: neither 9.5 fixture nor the NIST file carries one. |
| Fig. 97 — Primitive Set Shape Element data collection (p.128) | Fig. 95 (p.108) | `structural` + `widths` — 9.5: LEH, `I16` Version, `I32` Texture Coord Binding, `I32` Color Binding, `I32` Texture Coord Gen Type, `I16` Version (=1 or **=2**), `I32` Bits Per Vertex, branch on `Bits Per Vertex == 0`. v10: LEH, `U8` Base Shape Version, `U8` Base PrimSet Version, **`U64` Vertex Bindings** (replacing the two `I32` binding enums), `I8` Texture Coord Gen Type, `U8` Version (=1 only), `I32` Bits Per Vertex, same branch. So: 2 version fields vs 3; two `I32` binding enums vs one `U64` bit field; Tex Coord Gen Type `I32`→`I8` and it moves after the bindings | `spec` | opaque | opaque (byte-faithful carry); n/a: writer emits v10 | refused at `ShapeLodDocument.kt:186-191` | Object Type ID identical (`0xe40373c2…`). **Spec unclear, deliberately not implemented:** 9.5's *prose* order (Version, TexCoordBinding, ColorBinding, Version, BitsPerVertex, TexCoordGenType) contradicts its own figure, which puts Texture Coord Gen Type before the second version — finding 10 — and 9.5 admits a "Version-2 Format" no revision documents — finding 11. No fixture carries a Primitive Set element in either generation, so it stays opaque-with-note until one appears. Test: `ShapeLodDocumentTest.unestablishedV10ShapeTypesStayOpaqueWithNote` (the opaque path) |
| §7.2.2.2.1 / Fig. 98 — Lossless Compressed Primitive Set Data collection (p.129) + Table 5 (p.130) + Table 6 (p.130) | Fig. 96 (p.109) + Tables 51/52 (pp.110) | `identical` in structure — `I32` Uncompressed Data Size, `I32` Compressed Data Size, then `Compressed Data Size > 0` → `U8[size]` Compressed Primitive Data, `< 0` → `U8[abs(size)]` Primitive Data. Element table (reserved I32, params1 CoordF32, params2 DirF32, params3 Quaternion, colour RGB, type I32; types 0-4) identical; params# interpretation table identical. **Only delta: the compression method — ZLIB in 9.5, LZMA in v10** | `spec` | opaque | opaque; n/a: writer emits v10 | — | Stride and primitive count are derived from the two size fields; per-primitive stride is 4+12+12+16+12+4 = 60 bytes. |
| §7.2.2.2.2 / Fig. 99 — Lossy Quantized Primitive Set Data collection (p.131) | Fig. 97 (p.111) | `widths` — `U8 : Bits Per Color` (9.5) vs `U32 : Bits Per Colour` (v10). Structure otherwise identical: `I32` Primitive Count; `Primitive Count > 4` → { Bits Per Color, Compressed params1, Compressed params3, Compressed params2, `if Color Binding != 0` Compressed Colors, `VecI32{Int32CDP,Lag1}` Compressed Types }; else Primitive-Count repeats of { Quaternion params3, CoordF32 params1, DirF32 params2, `if Color Binding != 0` RGB Color, `I32` Type } | `spec` | opaque | opaque; n/a: writer emits v10 | — | The `Color Binding` the guard tests is 9.5's `I32 Color Binding` field; in v10 it is the colour bits of the `U64 Vertex Bindings`. Uses **`Int32CDP` (Mk. 1, §8.1.1)**, not the `Int32CDP2` the rest of §7.2.2 uses — finding 12. |
| §7.2.2.2.2.1 / Fig. 100 — Compressed params1 data collection (p.133) | Fig. 98 (p.113) | `identical` — `VecF32 : Quantization Range Min/Max Pairs` (I32 count + F32 values; length 2·num_ordinates), `VecI32{Int32CDP, Lag1} : params1 Codes`. Packet generation differs (Mk. 1 vs v10's third generation) | `spec` | opaque | opaque; n/a: writer emits v10 | — | Depends on PACKAGE H for `Int32CDP` Mk. 1 and the Uniform Quantizer. |
| §7.2.2.2.2.2 Compressed params3 (p.133) | v10 §7.2.2.2 (p.112) | `identical` — "the storage format … is exactly the same as that documented in Figure 100"; 4 ordinates ⇒ VecF32 length 8 | `spec` | opaque | opaque; n/a: writer emits v10 | — | Both revisions carry the same copy-paste error ("Since params1 is of type 'Quaternion'"). |
| §7.2.2.2.2.3 Compressed params2 (p.133) | v10 §7.2.2.3 (p.113) | `identical` — same as Fig. 100; 3 ordinates ⇒ VecF32 length 6 | `spec` | opaque | opaque; n/a: writer emits v10 | — | — |
| §7.2.2.2.2.4 Compressed Colors (p.134) | v10 §7.2.2.4 | `identical` — same as Fig. 100; 3 ordinates; quantized with **Bits Per Color**, not Bits Per Vertex; present only when Color Binding != 0 | `spec` | opaque | opaque; n/a: writer emits v10 | — | — |

**Counts.** 24 normative units analyzed: `identical` 6, `widths` 7, `structural` 10,
`9.5-only` 1 (Fig. 92 in substance), `unchecked` 0. Rows marked "structural + widths"
(Figs. 85, 93, 97) are counted as `structural`. Two units (Figs. 82, 84) are abstract element
figures with no v10 counterpart and are counted under `structural`.
Evidence: `spec+fixture` 15, `spec` 8, `conflict` 1 (Fig. 88, finding 1), `guess` 0.

**What package P3 (issue #12) closed.** Read status moved on nine rows — Figs. 84, 85, 87, 88,
89, 91, 92, 94, 95 — plus the §7.2.2.1 dispatch row. The 9.5 Polyline Set and Point Set Shape
LOD elements decode typed (all six `KR360-1.jt` bodies, zero notes, every stored hash verifying,
`encode(decode(body))` byte-identical), Figure 92's extension replaces DESIGN.md delta 14's
"reserved" tail, and the composite hash no longer false-refuses a producer that elides an
all-zero context-8 chunk (finding 7). Three spec defects are recorded and read past rather than
"fixed" in code: Figs. 93/94/95 omit Figure 84's Base Shape LOD Data box (finding 5), Figure 88
omits the version branch its own prose implies (finding 1), and Figure 97 contradicts its own
prose (finding 10 — the reason the Primitive Set stays opaque).

**Still open in this range.** The auxiliary vertex field *list* of Fig. 92 (refuses by name;
condition: a fixture that binds Table 48 bit 64), the Primitive Set Shape Element and its four
sub-collections (Figs. 97-100 — `spec unclear` plus the Mk.-1 `Int32CDP` dependency of finding
12), and colour / texture-coordinate / flag vertex bindings in the JT 9 arrays (Fig. 90).

---


## Package F — Meta Data Segment and PMI (9.5 §7.2.6, Figures 133–170)

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


## Package G — JT B-Rep, XT B-Rep, Wireframe, ULP and LWPA (JT 9.5 Rev-D §7.2.3–§7.2.5, §7.2.8–§7.2.9, §9.10)

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


## Package H — Data Compression and Encoding (9.5 §8) and Best Practices (9.5 §9)

Read/Write judge the library's **JT 9** path only. `n/a: writer emits v10` is the honest Write
entry wherever authoring is v10-targeted; for 9.5 the Write target is byte-faithful
re-serialization of what was read, which is what `encode`/`write` do.

### §8.1 Common Compression Data Collection Formats

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| `§8.1.1 / Fig. 218 — Int32 Compressed Data Packet (p.254)` | `Fig. 132` (v10 has only one Int32 packet, and it is the Mk. 2 lineage) | `structural` — a different packet from Mk. 2 end to end. Mk. 1 order: `U8 CODEC Type` → *(Arithmetic)* `Int32 Probability Contexts` → `I32 Out-Of-Band Value Count` → *(count>0)* nested `Int32 Compressed Data Packet` → *(codec≠Null)* `I32 CodeText Length`, `I32 Value Element Count` → *(table count>1)* `I32 Symbol Count` → `VecU32 CodeText`. **No leading Value Count** (so no "empty packet" form), OOB gated on an explicit count, CodeText is a `VecU32` (own `I32` count, p.21) not a bare word run, and a separate Symbol Count exists for the 2-table case. | `—` | `n/a: never read` | `—` | Used by every `VecI32{Int32CDP,…}` field: all JT B-Rep topology streams, LWPA, §8.1.13/14/15. Finding 2. |
| `§8.1.1.1 / Fig. 219 — Int32 Probability Contexts (p.256)` | `Fig. 133` (single table, no count byte) | `structural` — leading `U8 Probability Context Table Count` (1 or 2); per table `U32{32} Entry Count`, `U32{6} Number Symbol Bits`, `U32{6} Number Occurrence Count Bits`, **first table only** `U32{6} Number Value Bits`, `U32{6} Number Next Context Bits`, `U32{32} Min Value`, entries, `U32{variable} Alignment Bits`. v10: no table count, `U32{16}` entry count, no symbol-bits, no next-context-bits, `U32{7}` value bits. | `—` | `n/a: never read` | `—` | Note the 32-bit entry count (Mk. 2 and v10 use 16). Finding 2. |
| `§8.1.1.1.1 / Fig. 220 — Int32 Probability Context Table Entry (p.257)` | `Fig. 134` | `structural` — `Symbol` (stored +2; `−2` = escape), `Occurrence Count`, `Associated Value` (−Min), **`Next Context`**. v10 replaces `Symbol` with a `U32{1}` escape flag and drops `Next Context`. | `—` | `n/a: never read` | `—` | The `Next Context` field is what makes Mk. 1's decoder a *state machine*; Mk. 2's is not. Finding 2. |
| `§8.1.2 / Fig. 221 — Int32 Compressed Data Packet Mk. 2 (p.259)` | `Fig. 132` | `structural` (small, but real) — order `I32 Value Count` → *(count>0)* `U8 CODEC Type` → chopper: `U8 Chop Bits`, *(Chop Bits ≠ 0)* `I32 Value Bias` + `U8 Value Span Bits` + MSB/LSB nested packets; else `I32 CodeText Length` + `CodeText Length/32` × `U32 CodeText Word`; arithmetic then `Int32 Probability Contexts Mk. 2` + **an unconditional nested `Int32 CDP Mk. 2 : OOB Data Values`**. v10 adds CODEC 5 (Move-to-Front) + its two window packets, forbids Chop Bits 0 ("always greater than 0, and less than 32"), conditions the OOB field on external compression, and raises the recursion cap from **3** ("For JT v9 files, the maximum recursion depth may not exceed three", p.258) to eight. | `done` | `done` (byte-faithful) | `Int32Cdp.kt:191`, `:206`, `:237` | Codec set `{0,1,3,4}` — matches `CodecDriver2::CodecType` (App. C §1.6, p.318). Reader caps depth at 8, not 3 — lenient, harmless. Findings 4, 5. |
| `§8.1.2.1 / Fig. 222 — Int32 Probability Contexts Mk. 2 (p.261)` | `Fig. 133` | `structural` — `U32{16} Entry Count`, `U32{6} Number Symbol Bits`, `U32{6} Number Occurrence Count Bits`, `U32{6} Number Value Bits`, `U32{32} Min Value`, entries, `U32{variable} Alignment Bits`. v10 drops Number Symbol Bits and widens Number Value Bits to `U32{7}`. Header is **66 bits in 9.5 vs 61 in v10**; they diverge at bit 22. Single table only (the Mk. 1 table-count byte is gone). | `done` | `done` (raw block preserved) | `Int32Cdp.kt:482`, `:500` | Exactly DESIGN.md delta 16 — now a citation. The prose's leftover "*If a second Probability Context Table is present…*" is a Mk. 1 carry-over; Fig. 222 has no table count. Finding 1. |
| `§8.1.2.1.1 / Fig. 223 — Int32 Probability Context Table Entry Mk. 2 (p.262)` | `Fig. 134` | `structural` — `U32{Number Symbol Bits} Symbol` (+2 bias, `−2` = escape), `U32{Number Occurrence Count Bits} Occurrence Count`, `U32{Number Value Bits} Associated Value` (−Min). **No Next Context.** v10: `U32{1} Is Escape Symbol`, occurrence count, `I32{…} Associated Value`. | `done` | `done` | `Int32Cdp.kt:489`, `:512` | Escape detection `symbol == -2` at `Int32Cdp.kt:263` matches `CntxEntryBase2::CEBEscape = -2` (App. C §1.2, p.310). |
| `§8.1.3 / Fig. 224 — Float64 Compressed Data Packet (p.264)` | `none` (v10 has `Int64 CDP`, Fig. 135 — a different mechanism) | `9.5-only` — `U8 CODEC Type` → *(codec≠Null)* `Float64 Probability Contexts`, `F64 Value Range Min`, `F64 Value Range Max`, `I32 Out-Of-Band Value Count`, `VecF64 Out-Of-Band Values`, `I32 CodeText Length`, `I32 Value Element Count` → *(table count>1)* `I32 Symbol Count` → `VecU32 CodeText`. Out-of-band is **always raw `VecF64`, never nested** ("the Float64 Compressed Data Packet simply writes out the 'out-of-band data' array with no additional encoding attempted", p.263). No chopper, no bit-packed context. | `—` | `n/a: never read` | `—` | Finding 3. |
| `§8.1.3.1 / Fig. 225 — Float64 Probability Contexts (p.266)` | `none` (`Fig. 136` is Int64, bit-packed) | `9.5-only` — plain `I32 Probability Context Table Count` (1 or 2), then per table `I32 Probability Context Table Entry Count` + entries. **Byte-aligned `I32`s, no bit packing, no Min Value, no field widths, no alignment bits.** | `—` | `n/a: never read` | `—` | `spec unclear`: §8.1.3.1 never says the collection is Arithmetic-only (its Int32 siblings do), and Fig. 224's guard is "CODEC Type not equal to Null". A Bitlength Float64 packet would then still carry a context. Finding 3. |
| `§8.1.3.1.1 / Fig. 226 — Float64 Probability Context Table Entry (p.266)` | `none` (`Fig. 137`) | `9.5-only` — `I32 Symbol` (`−2` = escape, stored **unbiased**, unlike the Int32 tables' +2), `I32 Occurrence Count`, `F64 Associated Value`, `I32 Reserved Field`. 20 bytes flat. | `—` | `n/a: never read` | `—` | The `I32 Reserved Field` must be carried verbatim for round-tripping (§9.3). Finding 3. |
| `§8.1.4 / Fig. 227 — Compressed Vertex Coordinate Array (p.267)` | `Fig. 138` | `structural` — lossless branch stores **two packets per component** (`VecU32{Int32CDP2,Lag1} Vertex Coord Exponents` then `… Mantissae`, float bits = `exp<<23 \| mantissa`); v10 stores one `Binary Vertex Coords` packet per component. Header (`I32 Unique Vertex Count`, `U8 Number Components`, `Point Quantizer Data`) and trailer (`I32 Vertex Coordinate Hash`) identical; quantized branch identical. v10 adds "the only legal value for [Number Components] is 3". | `done` | `done` | `VertexArrays.kt:89` | Confirms DESIGN.md delta 19. Hash pseudo-code is **per value** on the lossless path here and the code matches — see finding 6 (v10's bytes disagree with its identical pseudo-code; 9.5's do not). |
| `§8.1.5 / Fig. 228 — Compressed Vertex Normal Array (p.269)` | `Fig. 139` | `structural` — quantized branch stores **four packets** (`Sextant`, `Octant`, `Theta`, `Psi` Codes, all `VecU32{Int32CDP2}`, NULL predictor); v10 stores one packed `Deering Normal Codes` packet. Lossless branch: exponent+mantissa pair per component vs v10's single binary packet. v10 adds "The maximum value for this field is 13"; **9.5 states no maximum** (and §8.2.4 implies 6 — see finding 8). Hash is `U32` in both. | `done` | `done` | `VertexArrays.kt:234` | Confirms DESIGN.md delta 19 second half. The `quantizationBits > 13` refusal at `VertexArrays.kt:270` is a v10 rule imported into the JT 9 path — harmless (9.5 files use ≤ 8) but not 9.5-derived. |
| `§8.1.6 / Fig. 229 — Compressed Vertex Texture Coordinate Array (p.271)` | `Fig. 140` | `structural` — `I32 Texture Coord Count` (v10 `U32`); lossless branch two packets per component (exponents, mantissae) vs v10's one binary packet; quantized branch identical (`Texture Quantizer Data` then one `VecU32{Int32CDP2,Lag1}` per component); hash `U32` (v10 `I32`). | `—` (JT 9 refuses the binding: `ShapeLodDocument.kt:408`) | `n/a: never read` | `—` | 9.5 doc error: the figure prints the guard **"QuantBits = 0" on both branches** (verified in the PDF, p.271); the right branch must be `> 0`. Finding 10. |
| `§8.1.7 / Fig. 230 — Compressed Vertex Color Array (p.273)` | `Fig. 141` | `structural` — quantized branch stores **four separate code packets** (`Hue/Red`, `Sat/Green`, `Value/Blue`, `Alpha`, each `VecU32{Int32CDP2,Lag1}`); v10 stores **one packed `Colour Codes`** packet with bit fields. Lossless branch exp+mantissa pairs vs v10 binary. `I32 Color Count` (v10 `U32`), hash `U32` (v10 `I32`). Hash chains four arrays (v10 hashes one). | `—` (binding refused) | `n/a: never read` | `—` | Same "QuantBits = 0" twice figure error as Fig. 229 (PDF p.273). Finding 10. |
| `§8.1.8 / Fig. 231 — Compressed Vertex Flag Array (p.275)` | `Fig. 142` | `widths` — `I32 Vertex Flag Count` (v10 `U32`, same 4 bytes, signedness only); `VecU32{Int32CDP2} Vertex Flags` vs `VecU32{Int32CDP}`. Structure otherwise identical; no hash in either. | `—` (JT 9 refuses bit 6 of Vertex Bindings) | `n/a: never read` | `VertexArrays.kt:381` is v10-only (`readInt32CdpValuesV10`) | Adding the JT 9 form is a two-line change (`Int32Cdp.read` + `readI32`); the blocker is the binding-mask refusal at `ShapeLodDocument.kt:408`, not the array. Finding 9. |
| `§8.1.9 / Fig. 232 — Point Quantizer Data (p.275)` | `Fig. 144` | `identical` — X, Y, Z Uniform Quantizer Data, in that order, nothing else. | `done` | `done` | `VertexArrays.kt:43` | |
| `§8.1.10 / Fig. 233 — Texture Quantizer Data (p.276)` | `Fig. 145` | `identical` — *n* Uniform Quantizer Data, *n* from the enclosing array's `U8 Number Components`; count not stored. | `—` | `n/a: never read` | `—` | Unimplemented in both generations (no texture-coordinate consumer). |
| `§8.1.11 / Fig. 234 — Color Quantizer Data (p.277)` | `Fig. 146` | `identical` — `U8 HSV Flag`; `=0` → Red/Green/Blue/Alpha Uniform Quantizer Data; `=1` → `U8` Hue/Saturation/Value/Alpha bit counts. The HSV assumed ranges table (Hue 0–6, others 0–1) is identical (9.5 p.276 / v10 Table 66). | `—` | `n/a: never read` | `—` | Unimplemented in both generations. |
| `§8.1.12 / Fig. 235 — Uniform Quantizer Data (p.278)` | `Fig. 147` | `identical` — `F32 Min`, `F32 Max`, `U8 Number Of Bits`, range `[0,32]`. | `done` | `done` | `VertexArrays.kt:18` | |
| `§8.1.13 / Fig. 236 — Compressed Entity List for Non-Trivial Knot Vector (p.279)` | `Fig. 148` | `structural` (predictor) — four `VecI32{Int32CDP, `**`Stride1`**`} : Entity Index Codes`; v10 uses `{Int32CDP, `**`Lag1`**`}`. Also Mk. 1 vs the v10 packet. The `VecI32 Entities of Knot Type Exist Flags` header and the four Table-68 categories are identical. 9.5's trivial-knot Case-2 carries an extra clause v10 dropped: "All distinct interior knots are repeated exactly one time" (p.278). | `—` | `n/a: writer emits v10` | `CurveData.kt:82` is v10-only (`Int32Cdp.readV10`, `Predictor.LAG1`) | The reconstruction sketch (`case 0..3` numVals formulas) is byte-identical in the two documents. Finding 2. |
| `§8.1.14 / Fig. 237 — Compressed Control Point Weights Data (p.281)` | `Fig. 149` | `structural` — `I32 Weights Count`, then `VecI32{Int32CDP, `**`Stride1`**`} : Weight Indices` (v10: `{Int32CDP, `**`Lag1`**`}`), then `VecF64{`**`Float64CDP`**`, NULL} : Weight Values` (v10: `VecF64{Int64CDP, NULL}` with bitwise `I64`→`F64` reinterpretation). The "weight 1 is not stored, infer it" rule is identical in both. | `—` | `n/a: writer emits v10` | `CurveData.kt:139` is v10-only (`Predictor.LAG1`, `Int64Cdp`) | The Float64CDP dependency makes this unreachable without finding 3's work. |
| `§8.1.15 / Fig. 238 — Compressed Curve Data (p.282)` | `Fig. 150` | `structural` — all five per-curve index vectors are `VecI32{Int32CDP, `**`Lag1`**`}`; v10 uses `{Int32CDP, `**`NULL`**`}`. Fifth vector named `NURBS Curve Reserved Fields` (v10 `NURBS Curve Empty Fields`). `VecF64{`**`Float64CDP`**`, NULL} : NURBS Curve Knot Vectors` vs v10's `Int64CDP`. Sub-collection order and the Table-69/70/71 semantics identical. | `—` | `n/a: writer emits v10` | `CurveData.kt:255` is v10-only | Both the Mk. 1 packet and Float64CDP are prerequisites. Findings 2, 3. |
| `§8.1.15.1 / Fig. 239 — Non-Trivial Knot Vector NURBS Curve Indices (p.284)` | `Fig. 151` | `identical` — a bare delegation to §8.1.13 (which itself differs; see that row). | `—` | `n/a: writer emits v10` | `—` | |
| `§8.1.15.2 / Fig. 240 — NURBS Curve Control Point Weights (p.284)` | `Fig. 152` | `identical` — a bare delegation to §8.1.14. | `—` | `n/a: writer emits v10` | `—` | |
| `§8.1.15.3 / Fig. 241 — NURBS Curve Control Points (p.284)` | `Fig. 153` | `structural` — `VecF64{`**`Float64CDP`**`, NULL} : Control Points`; v10 `VecF64{Int64CDP, NULL}` plus "each deserialized 64 bit integer number should be converted to bit wise equivalent 64 bit floating number" — a step 9.5 does not have because its codec is natively `F64`. | `—` | `n/a: writer emits v10` | `Vectors.kt:32` (`Float64Vector` is Int64CDP + bit reinterpretation — v10 only) | Finding 3. |
| `§8.1.16 / Fig. 242 — Compressed CAD Tag Data (p.285)` | `Fig. 154` | `structural` + `widths` — `I16 Version Number` (v10 `U8`); `I32 Data Length`; `I32 Version Number` ("1" the only valid value; v10 defers to local-version conventions); **`I32 CAD Tag Count` — a 9.5-only field**; then *(CAD Tag Count > 0)* `VecI32{Int32CDP2, `**`Lag1`**`} : CAD Tag Types` (v10 `{Int32CDP}`, NULL); *(Type-1 tags exist)* `VecI32{Int32CDP2, Lag1} : CAD Tags Type-1`; *(Type-2 tags exist)* the **`Compressed CAD Tag Type-2 Data` sub-collection** (§8.1.16.1) instead of v10's single `VecI64{Int64CDP}`. | `—` | `n/a: writer emits v10` | `CadTagData.kt:63`, `:122` are v10-only | Two 9.5 figure errors here — the Type-1 branch box literally reads `I16:Version Number` (the prose says the tag vector), and both branch guards say "exist in `I32 : Surface Count` data", a copy-paste from the B-Rep. Prose is authoritative. Finding 10. |
| `§8.1.16.1 / Fig. 243 — Compressed CAD Tag Type-2 Data (p.286)` | `none` (v10 folds this into `VecI64{Int64CDP}`) | `9.5-only` — `VecI32{Int32CDP2, Lag1} : First I32 of Type-2 CAD Tags` then `VecI32{Int32CDP2, Lag1} : Second I32 of Type-2 CAD Tags`: 64-bit tags split into **two Int32 Mk. 2 packets**. | `—` | `n/a: writer emits v10` | `—` | `spec unclear`: the document never says which half is high-order. Reader must resolve it from tag plausibility or refuse. Finding 2. |

### §8.2 Encoding Algorithms (section granularity; algorithm, not framing)

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| `§8.2.1 Uniform Data Quantization (p.286)` | `§12.2.1` | `identical` — same three-line encoder, same `iMaxCode` formula, same clamping note; only "must"→"shall". | `done` (inverse) | `n/a: writer emits v10` | `VertexArrays.kt:24` | |
| `§8.2.2 Bitlength CODEC (p.287)` | `§12.2.2` | `structural` (the algorithm, not the framing) — **9.5's §8.2.2 prose describes only the Mk. 1 prefix-code scheme** (a `0` bit for "same width"; `1` + run of increment/decrement bits + a complement terminator, step size 2). v10's §12.2.2 prose describes a fixed-width/adaptive-width block scheme. The two are different codecs, and neither prose is the JT 9 wire format actually used by Mk. 2 — that one is in **Appendix C §2.2** (see the appendix rows). | `done` (Mk. 2 form) | `done` | `Int32Cdp.kt:713` | Finding 4 — this corrects DESIGN.md delta 17. |
| `§8.2.3 Arithmetic CODEC (p.288–293)` | `§12.2.3` | `identical` at prose level (both are the same tutorial: Shannon/Elias history, the `{2,9,12,…}` worked example, the 5-digit register table, the underflow discussion). The *implementations* differ by generation, not by document: Mk. 1 (App. C §3.1) is a **multi-context state machine** — it loops `numSymbolsToRead()` (= Symbol Count when two tables), switches context per symbol via `pCntxEntry->iNextCntx`, and only emits an out-of-band value when the escape arrives *in context 0*. Mk. 2 (App. C §3.2) has a single context, loops `nValues`, and emits an OOB value on every escape. The 16-bit renormalisation core (`_low=0`, `_high=0xffff`, `_code` = first 16 bits, `rescaledCode = ((code−low+1)*total−1)/(high−low+1)`, the `0x4000` underflow squeeze, two flush bits) is **identical in Mk. 1, Mk. 2 and v10**. | `done` (Mk. 2 / single-context) | `done` | `Int32Cdp.kt:876`, `:911` | The Kotlin underflow test `low & 0x4000 && !(high & 0x4000)` is the *commented-out* variant in App. C §3.2; given the preceding "top bits match" test failed, it is provably equivalent to the live `((_low>>14)==1)&((_high>>14)==2)`. No divergence. Finding 1. |
| `§8.2.4 Deering Normal CODEC + Fig. 244 Sextant Coding on the Sphere (p.293)` | `§12.2.4 + Fig. 155` | `identical` — word-for-word, same sextant code assignment figure, same θ/φ formulas, same "n is in the range from 0 to 6 bits" and "max grand total of 18 bits (3+3+6+6)". | `done` (JT 9 four-packet form) | `done` | `VertexArrays.kt:397` | Both documents' `n ≤ 6` is contradicted by real producers on both sides (JT 9 fixture: 8 bits; v10: up to 13 by Fig. 139's own text). Finding 8. |
| `§8.3 ZLIB Compression (p.294)` | `§12.3 LZMA compression` | `structural` — 9.5 mandates **ZLIB 1.1.2**; v10 replaces it with LZMA/XZ. | see PACKAGE A | see PACKAGE A | `codec/Zlib.kt`, `codec/Lzma.kt` | Segment-level compression belongs to PACKAGE A; referenced here only because §8.3 is inside my range. |

### §9 Best Practices

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| `§9.1 Late-Loading Data (p.294)` | `§13.1` | `identical` in substance (same element list, same GUID→TOC→Data Segment path). v10 hardens "shall require the Table of Contents and the LSG segments" and adds `§13.2 TOC Segment Location`. | `n/a: convention` | `n/a: convention` | `—` | |
| `§9.2 Bit Fields (p.294)` | `§13.4 Bit Fields` | `identical` in effect — 9.5: "All undocumented bits are reserved" means "set to 0 when writing"; v10: "All bits fields that are not defined as in use shall be set to '0'". | `n/a: convention` | `partial` | `—` | Strict-writing consequence: a lenient reader that preserves unknown bits must **not** zero them on rewrite, or §9.3's rewrite rule is violated. Note the two rules pull in opposite directions for a rewrite; §9.3 wins. |
| `§9.3 Reserved Field (p.294)` | `§13.5 Empty Field` (renamed) | `identical` in effect — zero for fresh data, **preserve verbatim when rewriting a file that was read**. | `n/a: convention` | `done` | reserved fields carried throughout (e.g. `TriStripSetShapeLodElement.reservedVersion/reservedBindings`) | This is the document's own statement of the losslessness doctrine. |
| `§9.4 Local Version (p.294)` | `§13.6 Local version numbers` | `identical` in substance — write each local version's data in order; readers read up to the version they support and use the Segment Header length to skip. v10 adds a normative list of which collections use version `0x02` / `0x05`. | `n/a: convention` | `n/a: convention` | `—` | 9.5 has no such list — a 9.5 reader cannot pre-know a collection's version. |
| `§9.5 Hash Value (p.295)` | `§13.8 Hash Value` | `identical` — same Bob Jenkins 1997 function, same seed-chaining convention, same "the order that individual fields are hashed is extremely important". v10 states it outright: "*It is the same implementation that was used in JT v9.x*" (p.196). | `done` | `done` | `JtHash.kt:13`, `:93` | Appendix D vs Annex C: byte-identical bodies (`mix`, `hash`, `hash3`); only the wrapper name differs (`hash16` → `jthash16`). Neither document prints `hash2`'s body — both delegate `hash32` to it. Finding 7. |
| `§9.6–§9.10 (pp. 296–302)` | `§13.9–§13.13` | `unchecked` | — | — | — | Metadata conventions, LSG attribute accumulation, part structure, Range-LOD selection, B-Rep face-group associations — claimed by the LSG / meta / B-Rep packages. |

### Appendix C / D — normative by reference from §8.2 and §9.5

These are the *only* place either document specifies the JT 9 codec bit grammars, so they are
listed as units.

| 9.5 unit | v10 counterpart | Delta | Read | Write | Code | Notes |
|---|---|---|---|---|---|---|
| `App. C §1.1/1.3/1.5 — CntxEntry 1, ProbabilityContext 1, CodecDriver 1 (pp. 309–318)` | `none` (Annex B has only the Mk. 2 lineage) | `9.5-only` — `CntxEntry` carries `iSym`, `cCount`, `cCumCount`, `iNextCntx`; `CodecDriver::addOutputSymbol(iSymbol, iNextContext)`; predictor enum of 9 values (`PredLag1=0 … PredNULL=8`). | `—` | `n/a` | `—` | The Mk. 1 decoder contract. Finding 2. |
| `App. C §1.2/1.4/1.6 — CntxEntry 2, ProbabilityContext 2, CodecDriver 2 (pp. 310–319)` **new in Rev-D** | `Annex B` (v10 reference source) | `structural` — `CEBEscape = −2`; `ProbContext2::lookupEntryByCumCount` accumulates counts in **file entry order** (`accumulateCounts`, entry 0 cumulative 0); `CodecDriver2::CodecType` = `{0 Null, 1 BitLength, 3 Arithmetic, 4 Chopper}` — **no Move-to-Front**; predictor enum of **12** values, adding `PredMean2=9`, `PredMean3=10`, `PredMean4=11` on top of 9.5 §6's table of 9. | `done` | `done` | `Int32Cdp.kt:482`, `:598`, `:611` | `Predictor` (`Int32Cdp.kt:598`) is missing `MEAN2/3/4`. No §8 field uses them, but the LWPA "Combined Predictor Type" collection may — flag to the LWPA package. Also `CodecDriver2::assemble` (p.318) is the exp+mantissa reconstruction, with an explicit note that its hash order "**CANNOT** be used with the 9.x serialization of Coordinates, Normals, Colors and Tex Coordinate" — see finding 6. |
| `App. C §2.1 — BitLengthCodec 1 (p.320)` | `none` | `9.5-only` — the prefix-code scheme of §8.2.2. | `—` | `n/a` | `—` | Needed only with Mk. 1. |
| `App. C §2.2 — BitLengthCodec 2 (pp. 322–324)` **new in Rev-D** | `Annex B BitLengthCodec` | `structural` — 1-bit mode tag; **fixed**: `U{6} cBitsInMinSymbol`, `U{6} cBitsInMaxSymbol`, `S{cBitsInMinSymbol} iMinSymbol`, `S{cBitsInMaxSymbol} iMaxSymbol`, field width `_nBitsInSymbol(max−min)`, then unsigned fields biased by min; **variable**: `S{32} iMean`, `U{3} cBlkValBits`, `U{3} cBlkLenBits`, then per run a `S{cBlkValBits}` width delta repeated while it equals ±the extreme, a `U{cBlkLenBits}` run length, and `S{cCurFieldWidth}` fields biased by the mean. v10's Annex B uses nibble-coded min/max/mean and fixed 4-bit block/length widths. | `done` | `done` | `Int32Cdp.kt:713` (JT 9) vs `:786` (v10) | **This is the correction of DESIGN.md delta 17.** The Kotlin `decodeBitlength` matches this code line for line, including `_nBitsInSymbol(0) == 0`. Finding 4. |
| `App. C §3.1 — ArithmeticCodec 1 (p.325)` | `none` | `9.5-only` — see the §8.2.3 row. | `—` | `n/a` | `—` | |
| `App. C §3.2 — ArithmeticCodec 2 (pp. 327–328)` **new in Rev-D** | `Annex B ArithmeticCodec` | `identical` core, different context type. Signature `decode(nValues, vOOBValues, vCodeText, nBitsCodeText, ovValues, pProbCntx)` — the OOB array is a **plain `Veci` handed in**, consumed one per escape, in order. | `done` | `done` | `Int32Cdp.kt:876`, `:911` | Finding 1. |
| `App. C §4 — Deering normal decoding (pp. 330–332)` | `Annex B` | `unchecked` (the lookup-table class was not diffed field-by-field; the JT 9 normal hashes verify on all 12 fixture bodies, so the decode is right in practice) | `done` | `done` | `VertexArrays.kt:397` | Honest gap: the sextant/octant remap table in the code is fixture-validated, not diffed against 9.5's `DeeringNormalLookupTable`. |
| `App. D — Hashing (pp. 333–335)` | `Annex C` | `identical` — bodies byte-for-byte; wrapper `hash16` renamed `jthash16` in v10. | `done` | `done` | `JtHash.kt` | Finding 7. |

**Counts.** 40 units analysed: `identical` 12, `widths` 1, `structural` 17, `9.5-only` 7,
`unchecked` 2, plus §8.3 (deferred to PACKAGE A). Contradictions found: 3 code-vs-spec
(findings 5, 9 and the Mk.-1 dispatch of finding 2), 3 spec-internal (finding 10), 1
producer-vs-document (finding 8).

---

