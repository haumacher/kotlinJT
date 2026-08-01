# Package A — Framing: data types, file structure, segment framing, ZLIB

JT 9.5 Rev-D §6.2, §7.1 (all sub-sections), Table 3 (Segment Types), §8.3, §9.3, §9.4
against JT v10.0 Rev-C §4.2, §5.1, Table 6, §12.2.5, §13.

All 9.5 figure geometry in this package was verified against the PDF page images (pp. 26, 30,
31, 32), not only against `pdftotext -layout` output, because the branch/loop notation of §6.1
does not survive the text dump. The v10 counterpart figures 11 and 19 were re-rendered from the
v10 PDF (pp. 19, 25) for the same reason.

---

## Part 1 — ledger rows

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

## Part 2 — findings

### 1. The File Header's second GUID is drawn as an **alternative**, not an addition — in *both* documents. The code reads it as an addition. `trivial`

9.5 Fig. 2 (p.26) and v10 Fig. 11 (p.19) are drawn identically: the shaft leaving `TOC Offset`
**branches**, with the branch guarded `Reserved Field != 0` (v10: `Empty Field != 0`) leading to
`GUID: Reserved Field`, the unguarded path leading to `GUID : LSG Segment ID`, and both rejoining
below. 9.5 §6.1 (p.21) defines exactly that glyph: *"If an arrow appears with a branch in its
shaft, then there are two or more options for data to be stored in the file"* — its worked example
is `I32:A` branching to `U8:B` / `U16:C` / `U32:D`, which are mutually exclusive.

Under the notation the figure therefore reads: **Reserved Field == 0 ⇒ `GUID LSG Segment ID`;
Reserved Field != 0 ⇒ `GUID Reserved Field` (and *no* LSG segment ID)** — a 105-byte header
either way in 9.5, 121 bytes in v10 either way.

The library reads it as sequential: `FileHeader.kt:110`–`116` reads `lsgSegmentId` unconditionally
and *then* `trailingGuid` when the guard holds — a 121-byte v10 header. `FileHeaderTest.kt:76`
pins that reading, but the test builds its own bytes, so it proves only self-consistency. No
fixture can arbitrate: `RB___E_01955.jt`, `KR360-1.jt` and `nist-mtc-crada-assembly.jt` all carry
Reserved/Empty Field = 0 (bytes 81–84 = `00 00 00 00`, verified), so the branch is never taken.

Doctrine: a **lenient reader can actually settle this per file** — the header length is pinned
from below by the offset of the lowest-offset segment or the TOC, so try the 16-bytes-shorter
reading first and accept the longer one only if the layout demands it. The model must then
remember *which* reading it used (a `headerVariant`, or simply: `lsgSegmentId: Guid?` +
`trailingGuid: Guid?` where exactly one is set under the XOR reading), or re-serialization of a
non-zero-reserved-field file cannot be byte-identical. The **strict writer** never faces the
question because it writes Reserved/Empty Field = 0. This is Bernhard's call: the notation says
XOR, the existing implementation says sequential, and nothing observable distinguishes them.

### 2. Correction to DESIGN.md delta 2: 9.5 **does** specify the conditional trailing GUID; the code makes it unreachable for v9. `trivial`

DESIGN.md:125–127 records *"No conditional trailing header GUID in v9. v10's Figure 11 appends a
GUID empty field when the I32 empty field ≠ 0; implemented for v10 (unit-tested both branches),
**absent in v9** (fixture: first segment at 105 leaves no room)."*

9.5 Fig. 2 (p.26) shows the conditional `GUID: Reserved Field` under the guard `Reserved Field != 0`
— the same construct as v10. The fixture's first segment sits at 105 because its Reserved Field is
**0**, not because 9.5 lacks the field. The evidence never supported the general claim.

`FileHeader.kt:112` gates the read on `version.wideOffsets && emptyField != 0`; the
`version.wideOffsets &&` conjunct has no basis in either document and makes the 9.5 field
unreachable. `FileHeader.headerLength` (`:53`) and `writeTo` (`:65`) are already version-agnostic
about `trailingGuid`, so only the parse guard is wrong. (Whether the field is *additional* or
*alternative* is finding 1 — that must be settled first, since fixing the guard the wrong way just
propagates the wrong reading to v9.) DESIGN.md delta 2 should be rewritten to say: the field is
specified in both generations, is absent from every fixture because Reserved/Empty Field is always
0, and its wire meaning is disputed by the figure notation.

### 3. Confirmations — DESIGN.md deltas 1 and 3 upgrade from fixture-verified to cited. `no cost`

- **Delta 1** (DESIGN.md:119–124, "File header TOC Offset: I32 in v9, U64 in v10"): 9.5 Fig. 2
  (p.26) prints `I32 : TOC Offset`, prose *"Defines the byte offset from the top of the file to
  the start of the TOC Segment"*. v10 Fig. 11 prints `U64: TOC Offset`. Confirmed outright; the
  105-byte v9 header follows arithmetically (80 + 1 + 4 + 4 + 16).
- **Delta 3** (DESIGN.md:128–132, "TOC entry: 28 bytes in v9 … vs 32 in v10"): 9.5 Fig. 4 (p.28)
  prints `GUID : Segment ID`, `I32 : Segment Offset`, `I32 : Segment Length`, `U32 : Segment
  Attributes` = 28 bytes. v10 Fig. 13 prints GUID, U64, U32, U32 = 32. Confirmed outright, and the
  document adds a detail the fixture could not show: 9.5's Segment **Length** is `I32` (signed),
  v10's is `U32`. `Toc.kt:50` already reads `readI32()` on the v9 path — correct.
- **Delta 4** (DESIGN.md:133–140, ZLIB in v9): confirmed by 9.5 Fig. 10 (p.32) plus its inline
  value tables — flag `= 2` ZLIB ON, algorithm `= 1` none / `= 2` ZLIB, Compressed Data Length
  *including* the algorithm byte, and Table 3's "ZLIB Applied?" column. The fixture's
  `02 00 00 00 | C7 07 00 00 | 02 | 78 9C…` is exactly what the document prescribes. §8.3 (p.294)
  pins the library version (zlib 1.1.2, i.e. RFC 1950 framing).
- **`SegmentKind`'s doc comment** (`SegmentKind.kt:3`–`8`) claims *"The v9.5 table is a subset with
  the same codes and the same compression column."* Table 3 (p.30) confirms this literally: 19
  codes, every shared code with the same label and the same Yes/No. Only the two v10-only codes
  differ. The comment's incidental claim in `UndefinedSegmentTypes.kt:9` cites "v9.5 Rev A
  Table 3"; Rev D is the document at hand and it likewise lists neither 23 nor 31 — the
  observation stands, the revision reference should be updated to Rev D.
- **The "compression fields present even when compression is off" observation** (DESIGN.md:157–162,
  recorded as *spec-derived, fixture-unverified*) is confirmed for 9.5 too: Fig. 10's branch guard
  is `If first Element within file Segment`, not "if compressed" — all three fields are inside it.

### 4. `Logical Element Header ZLIB` is a **rename**, not a different collection. `no cost`

This was one of the title-level differences the inventory diff turned up; it resolves cleanly.
9.5 §7.1.3.2.3 Fig. 10 and v10 §5.1.3.2.2 Fig. 19 have the same four boxes in the same order
under the same guard, with the same "Compression Algorithm is included in this count" rule. The
real deltas are three, all small: the flag is `I32` in 9.5 and `U32` in v10 (four bytes either
way); the ON value is 2 (ZLIB) rather than 3 (LZMA); the algorithm value is 2 (ZLIB) rather than
3 (LZMA), with 1 = none shared. `CompressionHeader` (`Segment.kt:12`) stores the flag as `UInt`
and the algorithm as `Int` verbatim, and `decodeCompressible` (`JtFile.kt:282`–`312`) dispatches
on the *algorithm* byte and never tests the flag — which is exactly the lenient reading the
doctrine asks for, and the flag is carried verbatim so re-serialization is faithful. The v9
writer (`lsg/LsgDocument.kt:236`–`241`) emits flag 2 / algorithm 2 — the document's encoding.
Nothing to change; the ledger should record the collection as the same unit under two titles.

### 5. `SegmentKind` recognises two codes 9.5 does not define. `trivial`

Codes 30 (MultiXT B-Rep) and 32 (STEP B-rep) are v10-only; 9.5 Table 3 (p.30) stops at 24.
`SegmentKind.fromCode` (`SegmentKind.kt:36`) is version-blind, so a 9.5 file carrying type 30
would be silently accepted as a known compressible segment instead of raising
`LoadNote.UnknownSegmentType` and going down the conservative `probeUndefinedType` path
(`JtFile.kt:190`). Losslessness is unaffected (the payload is preserved either way) and no such
file is known to exist, but the honest behaviour for a v9 file is "undefined type 30". Cost is one
version-aware predicate. Low severity — record it, do not necessarily fix it.

### 6. 9.5's composite-type table is not the set of composite types 9.5 uses — and neither is v10's. `small`, and it belongs to packages B/C

9.5 Table 2 defines **CoordF64, HCoordF32, HCoordF64** which v10 Table 4 drops, and omits
**Mx4F64, VecI16, VecU16** which v10 adds. But each document then uses types missing from its own
table:

- 9.5 uses `Mx4F64` in four figures (Mapping Plane / Cylinder / Sphere / TriPlanar matrices,
  pp. ~110–115 of the text dump) and `VecU16`/`VecU64` in B-Rep pseudo-code, though Table 2 lists
  none of them.
- v10 uses `HCoordF32` and `CoordF64` though Table 4 lists neither.
- `HCoordF64` is defined by 9.5 Table 2 and **never used** anywhere in the 9.5 document.

Consequence for this pass: **a "type not in the composite table" argument is never evidence** for
any figure in packages B–H — the tables are conveniences, not closed sets. The library's model
already covers Mx4F64, VecI16, VecU16, VecF64 and 4-component F32 vectors; `CoordF64`
(3 × F64, used by 9.5's `CoordF64 : Translation Vector` and `CoordF64 : Reserved Field`) has no
named helper, which those packages should check.

### 7. §9.4's 9.x-extensibility clause is a standing licence for local versions the library will refuse. `small` (a policy decision, not a code change here)

9.5 §9.4 (p.295) says local versions exist so collections *"can be extended within current and
future minor versions of the 9.x file format"* — i.e. a conforming 9.5 reader is told to expect
version numbers it does not know, written by 9.6/9.7 producers, and to skip past what it cannot
parse. v10 §13 deliberately closes this (*"All version information for 10.0 JT data is included
within this document"*).

The library's per-element decode is strict: an unexpected width or an over-read refuses the typed
decode and the element becomes `OpaqueLsgElement` with `ELEMENT_LAYOUT_UNVERIFIED` /
`ELEMENT_DECODE_FAILED` (DESIGN.md:166–181). That is lossless and honest, so nothing is *broken* —
but under 9.5's own convention a higher local version is **expected**, not exceptional, and the
right response is "read the versions I know, carry the tail verbatim", not "carry the whole
element verbatim". Worth a decision: for 9.x elements, a `versionTail: Bytes` on the decoded model
would let a 9.6 element decode to its 9.5 prefix while still round-tripping byte-identically.

Note also the shared imprecision (not a delta): both documents say to skip unknown local-version
data *"using the segment length that was read in the Segment Header"*, which is the wrong
granularity — per-element skipping uses the `I32 Element Length` of Fig. 8, not the segment
length. The code (`Elements.kt:79`, `position += 4 + length`) does the sensible thing.

### 8. 9.5 Fig. 8's second box is mislabelled "Object Data" where the prose and v10 say "Element Header". `no cost` — documentation only

Verified on the page image (p.31): the box under `I32 : Element Length` reads **Object Data**.
But (a) the sentence immediately below the figure says *"Complete description for Logical Element
Header can be found in 7.1.3.2.2 Element Header"*, and (b) Fig. 7 on the same page already shows
`Logical Element Header` **followed by** `Object Data`, so the Fig. 8 label double-counts Object
Data. v10 Fig. 17 fixes the label to `Element Header`. Wire layout is unaffected and the library
reads it correctly. Record it so a later reader of the 9.5 figure is not misled into thinking the
9.5 Logical Element Header lacks the Element Header fields.

### 9. v10's "skip Element Length + 1" has no 9.5 counterpart — and the library follows 9.5. `no cost`

v10 §5.1.3.2.1 adds *"If the GUID is not found in Annex A, the reader should skip Element Length
+ 1 number of bytes"*; 9.5 says only (for an unknown **base type**) *"the loader should simply
skip (read pass) Element Length number of bytes"*, with no rule at all for an unknown Object Type
ID GUID. `scanElements` advances `4 + length` (`Elements.kt:79`) — the 9.5 rule, and the one the
fixtures confirm in both generations. v10's `+ 1` looks like an erratum; note only, and note that
following 9.5 here is also what makes the v10 path work.

### 10. 9.5 is stricter than v10 about the File Header's Reserved Field. `no cost`

9.5 §7.1.1 (p.26) states flatly *"I32 : Reserved Field — **Must have the value 0**."* v10 replaces
that with a pointer to the Empty Field convention, which only says *set* it to 0 for fresh files
and *preserve* it when rewriting. Under 9.5's rule the conditional GUID of finding 1 is
**unreachable in a reference-compliant 9.5 file**, which is why no producer has ever exercised it
and why the fixtures cannot arbitrate. The lenient reader still accepts a non-zero value (the
library preserves it in `FileHeader.emptyField`); the strict 9.5 writer must emit 0 for authored
files and preserve on rewrite — which is what §9.3 already says and what the code already does.

### 11. Terminology: 9.5's "Reserved Field" is v10's "Empty Field", globally. `no cost` — ledger hygiene

The rename is systematic (§9.3 vs §13, and every figure that carries one, including LOD Node Data
which both documents use as the example). The library uses the v10 name (`emptyField`) throughout.
Packages B–H should expect **every** v10 "Empty Field" box to appear as "Reserved Field" in the
corresponding 9.5 figure and must not score that as a structural delta — but must also not let the
rename hide a real one, since 9.5 sometimes has a Reserved Field where v10 has a *named* field and
vice versa (that is a per-figure question, not a global one).

---

### Two things this package could not settle

- **Whether any real 9.5 producer writes a non-zero File Header Reserved Field.** Both available
  9.5 fixtures come from the *same* producer (`NetAllied JTWriter R14`), so the header evidence is
  one producer wide. Finding 1 stays open until a second-producer 9.5 file exists.
- **Whether a 9.5 file with segment-wide compression *off* (flag != 2) exists.** Both fixtures
  always compress. The layout for that case is spec-derived in both generations (Fig. 10 / Fig. 19
  put the fields under the first-element guard, not under a compressed guard) and the code's
  fallback is a named `COMPRESSION_HEADER_INCONSISTENT` note with raw bytes preserved, so nothing
  can be lost meanwhile.
