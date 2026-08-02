# kotlinJT — decision record

This file is the project's decision record (established with the Layer 0 package, issue #2).
Every load-bearing decision lands here with its rationale; reversals quote the old rationale.
Architecture and principles live in `README.md`; scope in issue #1. Nothing in here is a
permanent non-goal — deferred work states the condition under which its time comes.

Spec references are to the *JT File Format Reference v10.0 Rev-C* (the withdrawn but
installed-base-authoritative ISO 14306:2017 text). "The fixture" below is the local
real-producer file: JT 9.5, written by NetAllied JTWriter R14, 48 192 bytes, little-endian
(gitignored under `fixtures-local/`, see the amendment on issue #1).

## Toolchain

Gradle 8.7 / Kotlin Multiplatform 1.9.24 / ktlint plugin 12.1.1 — the combination proven in
the sibling project (ConstructIt) on the same development machine. Deliberately conservative:
the library's value is format fidelity, not compiler novelty. Upgrade when a feature needs it
(e.g. kotlinx-io versions past 0.3.x require Kotlin 2.x klibs).

Targets: `jvm()` and `js(IR) { nodejs() }`, both with tests. A browser JS target is a future
extension; its time comes when a browser consumer exists (the ConstructIt seam) — nothing in
`commonMain` blocks it.

## Byte I/O: kotlinx-io at the edge, own cursor at the core

**Decision**: dependency on `kotlinx-io-core` (0.3.5, the last Kotlin-1.9-compatible line),
used only at the entry point (`readJtFile(path)`: multiplatform file reading on JVM and
Node). The parsing core runs on our own `ByteReader`/`ByteWriter` over `ByteArray`
(`de.haumacher.kotlinjt.io`), plus an immutable `Bytes` value type for payloads.

**Why not okio**: both libraries are stream-oriented and big-endian-native. JT demands
*per-file* byte order (a header byte selects it, clause 5.1.1) and TOC-driven random access —
neither library models that, so a thin cursor of our own was needed either way. Given that
the dependency only covers the filesystem edge, the lighter, KMP-first, kotlinx-ecosystem
library wins. okio's maturity advantage is neutralized by the seam being one file.

**Why not zero dependencies**: file access in `commonMain` without platform types is exactly
what kotlinx-io provides; hand-rolling it would re-implement the library behind an
`expect`/`actual` of our own. Risk recorded: kotlinx-io is pre-1.0; its types do not appear
in the public API, so churn is contained to `JtFiles.kt`.

## zlib seam

`expect fun zlibInflate(ByteArray): ByteArray` / `zlibDeflate(ByteArray, level)` in
`de.haumacher.kotlinjt.codec`, throwing a common `ZlibException` on corrupt/truncated input.

- **JVM**: `java.util.zip.Inflater`/`Deflater`. Note the inflate loop: `inflate()` can return
  0 *because the stream just finished* — check `finished()` before diagnosing truncation
  (this was a real defect caught by the empty-payload round-trip test).
- **JS**: pako 2.1.0 via `npm()`. pako throws plain JS *strings* on corrupt input, which a
  Kotlin `catch (e: Throwable)` never sees on the IR backend — the try/catch lives on the JS
  side of the boundary (a `js()` wrapper in `Zlib.js.kt`) and converts to `ZlibException`.
  (A second real defect caught by the hostile-input tests on JS.)

Deflate level defaults to 6 — the default of both engines, and (observed) the level that
byte-reproduces the fixture's LSG stream when re-deflating its inflated payload. Layer 0
losslessness does **not** depend on that: re-serialization always emits the raw payload
bytes; deflation is only for the future writer and for tests.

All segment-wide codecs sit behind one interface, `SegmentCodec`, registered by the U8
Compression Algorithm value: 1 = none, 2 = ZLIB, 3 = LZMA. Decoding never throws through the
API: a codec returns `CodecResult.Decoded` or `CodecResult.Refused(note)`.

- ~~**LZMA (v10, clause 12.2.5) is a named future extension**, not implemented.~~ Its
  condition ("the first v10 fixture that uses it") was met by the NIST fixture; decoding
  landed with issue #5 — see *LZMA decoding* below. `UNSUPPORTED_COMPRESSION` remains the
  refusal for well-formed xz features the decoder does not implement.

## Load notes — refusals speak

`LoadNote` is a sealed hierarchy of immutable data classes; each subtype has a **stable name**
(`UNSUPPORTED_COMPRESSION`, `UNKNOWN_SEGMENT_TYPE`, `SEGMENT_OUT_OF_BOUNDS`, …) and a
located, human-readable message. The contract:

- **Silence means success.** Anything the parser cannot fully decode is a note; there are no
  unnamed notes by construction, and no exceptions escape `JtFile.parse` for segment-local
  problems. Raw bytes are always preserved next to a refusal.
- `JtFormatException` is reserved for the top-level "this cannot be a JT file" cases:
  unreadable header, unusable TOC. Hostile-input tests pin both halves of this line.
- The fixture suite asserts each fixture's exact note-name list against its sidecar; a
  well-formed file asserts the empty list.

The base class's `toString()` is `final` so the data-class field dump can never replace the
`NAME: message` diagnostic rendering (a data class regenerates `toString` unless the
inherited one is final — a third defect the gate caught).

## Layer 0 losslessness: the region model

A parsed `JtFile` holds the header plus an ordered list of `FileRegion`s covering
`[headerLength, fileSize)` completely: `SegmentRegion` (per TOC entry), `TocRegion`, and
`GapRegion` for any bytes no TOC entry explains (preserved verbatim, flagged by note
`UNMAPPED_REGION`). A region starting inside an already-covered range (duplicate or hostile
TOC entries) is parsed but marked *shadowed* — inspectable, excluded from layout, its bytes
emitted by the covering region; the uncovered remainder, if any, falls out as a gap. This is
what makes *parse → serialize byte-identical* hold for damaged files too, which the
hostile-input tests assert for every note kind.

What is structurally re-serialized from parsed fields: file header (version bytes preserved
verbatim, empty field preserved as read per clause 4.3), TOC (entry order preserved), segment
headers (GUID, type, declared length — preserved even when they contradict the TOC, with
mismatch notes). What is emitted raw: segment payloads (including the 9 compression-header
bytes, which are additionally *parsed* as a view). Re-layout after mutation is Layer 1's
concern; `serialize()` `check`s layout contiguity so a mutated model fails loudly rather than
writing a corrupt file.

Element framing (`scanElements`) is deliberately a **diagnostic view, not a decode claim**:
segment data is walked as consecutive element lists (I32 length ≥ 16, then Object Type ID
GUID; the all-FF GUID ends a list), and the first unrecognizable position starts the
preserved `trailing` bytes. The write path never goes through the scan, so a conservative
scan can never lose bytes. A known-type segment whose data yields *no* frame at all is
flagged `ELEMENT_STREAM_UNRECOGNIZED`.

## Lenient when reading, strict when writing (Bernhard, 2026-08-01)

Acquiring the JT 9.5 Rev-D reference turned a class of open questions into a class of *conflicts*:
places where the document and a real producer disagree, and where the corpus cannot arbitrate.
The governing rule:

> **Lenient when reading, strict when writing.** The reader accepts both the document's encoding
> and the producer's; the writer emits only the document's.

Two constraints keep this from eroding issue #1's guarantees:

1. **Leniency is recorded, never normalized.** Layer 1 is lossless. If the reader accepts a
   variant the figure does not describe, the model carries *which variant it saw*, so
   re-serialization stays byte-identical. A reader that canonicalizes two encodings into one
   model field has broken round-tripping — a defect, not a convenience. (There is such a hole
   today: `writeVertexShapeData` re-derives field presence from `version` instead of from the
   model's nullability.)
2. **Leniency is not silence.** An accepted off-document encoding still earns a named
   `LoadNote` where it is material. Silence keeps meaning "this matched the document".

### Local version guards mean `>= N`, not `== N`

The rule that came out of the 9.5 delta pass, and it is library-wide. 9.5 §9.4 and v10 §13 both
define local versions as **append-only**: data for each local version is written in order, and a
reader reads up to the version it supports and skips the rest. A version-1 reader must therefore
be able to read the version-1 prefix of a version-2 body — so version 2 cannot *remove* a
version-1 field. A figure box guarded `Version Number == 1` says "this field belongs to local
version 1", i.e. it is present whenever the version is at least 1.

Consequence: 9.5 Figures 30/33/34 gate `U64` fields on `Version Number == 1`, both NetAllied
fixtures write version 2 and emit them, and the producer is **conformant**. `LsgCodecs.kt`'s
`version >= 2` was an empirical discovery of the right operator with the threshold off by one,
which is why every fixture passed. Validation condition when widening: if any v10 element has a
`== 1` guard over a field a version-2 body genuinely omits, the NIST round-trip will catch it.
(Applied and validated by package P2, issue #11: the NIST 10.5 battery stayed green, so no v10
element in the corpus depends on the narrow reading.)

### The length oracle: how a guarded field's presence is actually decided (issue #11)

*(Package P2, 2026-08-01.)* The `>= N` rule says what a *conformant* producer writes. The
reader does not test it. Element bodies are decoded on a sub-reader bounded to the framed body,
so `remaining` is an exact oracle, and the guarded `U64` fields of 9.5 Figures 30, 33 and 34 are
resolved from it (`LsgCodecs.resolveGuardedBindings`):

| element | bytes after Quantization Parameters | reading |
|---|---|---|
| Tri-Strip / Polygon Set / Vertex Shape Node | 0 / 8 | Figure 30's field absent / present |
| Polyline Set, Point Set | 6 / 14 / 22 | neither / exactly one / both |

The evaluation point matters: it has to sit after Base Node Data's variable-length attribute
list, i.e. *inside* the Vertex Shape Data read, which is why that function returns the enclosing
node's presence decision alongside its own data. Both 9.5 fixtures land on 22 in all 29 shape
nodes; the NIST v10 polylines land on their 5-byte tail with no guarded field, as v10 Figure 40
draws it.

The 14-byte case fits two readings of equal width. The `I16`/`U8` Version Number at the
candidate offset breaks the tie against the value set the figures document (`1..2`); **if that
does not discriminate either, the read is refused** — `ELEMENT_DECODE_FAILED`, bytes carried
opaquely. Guessing which of two 8-byte fields is on the wire would put an invented decomposition
into a model whose whole contract is that it is a projection of the bytes. Leniency stops where
the evidence does.

**No `LoadNote` for an accepted variant here, deliberately.** The doctrine asks for a named note
where an off-document encoding is *material*, and none of the accepted variants is:

- *both fields present* (what both 9.5 producers write) **is** the document's encoding under the
  `>= N` rule — a note would fire on all 29 shape nodes of every NetAllied file and mean nothing;
- *both absent* is what a producer reading `== 1` literally emits: unambiguous by length, fully
  recorded by the model's nullability, byte-exact on re-serialization — nothing is guessed and
  nothing is lost;
- *the mixed cases* are accepted only when the version number positively selects one of them,
  and refused by name otherwise.

Silence therefore still means "nothing was guessed and nothing was lost", which is the property
consumers rely on. The variant itself is never invisible: it is a field of the model
(`VertexShapeData.vertexBindings2`, `PolylineSetShapeNodeElement.vertexBindings`,
`PointSetShapeNodeElement.vertexBindings`), and the writers emit exactly what those fields hold.

## The JT 9.5 reference (Rev-D) and what it changed in this record

Until 2026-08-01 every v9 delta below was reverse engineered from two 9.5 fixtures. The 9.5
document is now available (kept locally, never committed — Siemens copyright, as with the v10
PDF), and an eight-package delta pass compared it against v10 and against this code. The result
is **`SPEC_COVERAGE_95.md`** (the ledger: 355 rows, ~84 findings, 21 contradictions) with the
per-package evidence under **`docs/spec95-analysis/`**.

What that pass did to the deltas recorded below:

- **Upgraded from fixture-inference to citation:** deltas 1, 3, 4, 6, 7, 8, 10, 11, 12, 16, 18
  (in part), 20, 38. Delta 16's failure mode is now quantified — read the v10 way, the headers
  agree for 22 bits and diverge at bit 23. Delta 20's 30/30/4 chunking is confirmed by three
  independent 9.5 passages.
- **Corrected:** delta 2 (v9 *does* specify the conditional trailing header GUID; the fixtures
  simply have Reserved Field = 0 — and in *both* documents that GUID is an XOR alternative to
  the LSG Segment ID, not an addition) · delta 14 (the "reserved 12-byte tail" is the documented
  TopoMesh Compressed Rep Data **V2** tail, 9.5 Fig. 92) · delta 17 (Rev-D Appendix C §2.2 *does*
  describe the bitlength wire format, statement for statement — so the library's least-grounded
  decoder is now its best-cited one; and package P8 below implements the *other* scheme delta 17
  names, Appendix C §2.1's Mk. 1 prefix code, as `decodeBitlengthMk1`) · delta 36 (Figure 126's
  guard encloses the `VecF32` too) · delta 37 (confirmed v10-only — the code was already right,
  and P8 added the 9.5 half of the rule to the delta itself) ·
  delta 9, corrected and closed by package P2 below (9.5 Fig. 30's guard is `>= 1`, not
  `>= 2`, and the identity of the second `U64` is the figure's own repeat of the first) ·
  **delta 11's *rationale*, corrected by package P6** (the `0x000F` refusal is right, but there
  is no "Common RGB Value" compact colour encoding to fear: 9.5 p.62 documents the same four
  bit groups as v10 and declares the rest reserved).
- **Corrected by package P6 — the model-correctness sweep** (byte-neutral defects: the library
  decoded the right *number* of bytes into the wrong model, so no fixture ever complained):
  the Partition Node's middle bounding box was the *Reserved Field*, not the Transformed BBox,
  in both 9.5 fixtures (delta 43) · Geometric Transform element values are `F32` in 9.5 and the
  Shared Image Flag is `U8` (delta 44) · the light family's Shadow Parameters sit on the
  *element*, gated on local version 2, not inside Base Light Data (delta 45) · State Flags bit
  `0x01` and the field-inhibit bit assignments differ in meaning between the generations
  (delta 46).
- **Refuted, and then acted on (package P7):** the statement in `LwpaDocument.kt`, this file and
  `SPEC_COVERAGE.md` that the 9.5 reference does not document a JT LWPA Element. It does —
  §7.2.9.1, Fig. 215, segment type 24, with the GUID `ObjectTypeIds.kt` already carries. All
  three statements are struck, and the JT 9 element now decodes: see delta 47 and *§9 LWPA*
  below. The opaque carry had been *defensible behaviour resting on a false reason*, and the
  false reason was what made the deferral look permanent.

Deltas below are left as originally written; where the pass changed their status, the list above
is the authority.

## v9 vs v10 structural deltas (established, with byte evidence from the fixture)

The v10 reference documents only the v10 wire format. The deltas below were verified against
the fixture's bytes; width selection keys on the header's version string (`major >= 10` ⇒
wide), `JtVersion.wideOffsets`.

1. **File header TOC Offset: I32 in v9, U64 in v10** (clause 5.1.1 shows U64).
   Evidence: I32 little-endian at offset 85 = 47 824; the TOC parsed there has 13 entries and
   ends exactly at the 48 192-byte file end. A U64 read at 85 consumes the first GUID half
   and yields garbage. v9 header is therefore 105 bytes: `Version[80]`, `UChar` byte order,
   `I32` empty field, `I32` TOC offset, `GUID` LSG segment id — and the fixture's first
   segment starts at offset 105 exactly.
2. **No conditional trailing header GUID in v9.** v10's Figure 11 appends a `GUID` empty
   field when the I32 empty field ≠ 0; implemented for v10 (unit-tested both branches),
   absent in v9 (fixture: first segment at 105 leaves no room).
3. **TOC entry: 28 bytes in v9 (GUID, I32 offset, I32 length, U32 attributes) vs 32 bytes in
   v10 (GUID, U64 offset, U32 length, U32 attributes)** (clause 5.1.2 shows the latter).
   Evidence: 4 + 13 × 28 = 368 bytes from 47 824 lands exactly on the file end; each entry's
   offset/length match the segment header GUID and length found at that offset. With 32-byte
   entries the second entry's GUID would start mid-field.
4. **Segment-wide compression is ZLIB in v9, LZMA in v10.** v10's Tables 8/9 say flag 3 /
   algorithm 3 = LZMA, algorithm 1 = none; the v9 generation used flag 2 / algorithm 2 =
   ZLIB with the same field layout (U32 flag, I32 compressed data length *including* the
   algorithm byte, U8 algorithm). Evidence: the fixture's LSG segment data begins
   `02 00 00 00 | C7 07 00 00 | 02 | 78 9C…` — flag 2, length 1991, algorithm 2, then a zlib
   stream (`78 9C`) that inflates to 8 663 bytes of well-formed element lists; 9 + (1991 − 1)
   bytes exactly fill the segment payload. The codec registry accepts both generations by
   dispatching on the algorithm byte.
5. **Element framing is shared.** Both generations frame elements as I32 length + that many
   bytes starting with the Object Type ID GUID, end-of-elements GUID all-FF. Evidence: the
   fixture's uncompressed Shape LOD0 segments and its *inflated* LSG data both walk cleanly:
   each shape segment = 1 element + end marker + 6 trailing bytes; the LSG = 67 graph
   elements + end marker + 42 property atoms + end marker + 694 bytes of non-element-framed
   tail (the property table — Layer 1 will interpret it).

### Recorded observations

- **Six trailing bytes `01 00 00 00 00 00` after the end-of-elements marker in shape
  segments — identified as an empty Property Table** (Figure 78: I16 version = 1, I32
  Element Property Table Count = 0), the same structure the LSG segment carries after its
  element lists. Evidence: all 12 shape segments of the 9.5 fixture (NetAllied) *and* all 39
  shape segments of the committed NIST fixture (Siemens NX, JT 10.5) end in exactly these six
  bytes — two producers, two format generations. The §7 package (issue #4) confirmed the
  identification: `ShapeLodDocument` now decodes it as a typed Property Table.
- **Compression fields with flag ∉ {2,3}** (compression off): the fields are still read and
  the remaining `dataLength − 1` bytes treated as plain element data. Spec-derived
  (Figure 19 shows the fields unconditionally on the first element), fixture-unverified —
  the fixture always compresses. If a real file contradicts this, the framing falls back to
  a `COMPRESSION_HEADER_INCONSISTENT` note with raw bytes preserved, so nothing can be lost
  meanwhile.
- ~~The v9.5 element header may or may not carry the v10 `I32 Object ID`.~~ **Resolved by
  Layer 1**: it does — see LSG delta 6 below.

## Layer 1: the LSG document model (issue #3)

**Model ↔ bytes mapping.** Every §6 element type is an immutable data class in
`de.haumacher.kotlinjt.lsg`, composed exactly as the spec composes its data collections
(`GroupNodeData` contains `BaseNodeData`, `VertexShapeData` contains `BaseShapeData`, …).
Decoding is codec-per-type (`LsgElementCodecs`, keyed by the Annex A GUID in
`ObjectTypeIds` — one table serving both the codecs and the inventory) and **strict**: each
element decodes from exactly its framed body on a bounded sub-reader; underrun, overrun, an
unexpected Object Base Type byte (Table 7), or any structurally impossible count refuses the
typed decode. A refusal never throws through the API and never loses bytes — the element is
carried as `OpaqueLsgElement` (type GUID + verbatim body) with a named note:
`UNKNOWN_ELEMENT_TYPE` (GUID outside Annex A), `ELEMENT_LAYOUT_UNVERIFIED` (type known, wire
layout for this generation not established), or `ELEMENT_DECODE_FAILED` (body did not parse).
There is deliberately **no half-decoded element**: partially understood bytes would make
re-serialization a reconstruction instead of a projection.

**Document structure** (`LsgDocument`, Figure 20): graph element list, property atom list
(each closed by the end-of-elements marker), typed `PropertyTable`. Streams that deviate get
named notes (`LSG_STRUCTURE_UNRECOGNIZED`, `PROPERTY_TABLE_MISSING`,
`PROPERTY_TABLE_UNRECOGNIZED`) and their unconsumed remainder preserved verbatim in
`LsgDocument.trailing`. Note: Figure 20's second-list box is garbled in the reference PDF
(it reads "Texture Coordinate Generator Attribute Elements"); the fixture confirms the list
holds the Property Atom Elements.

**The losslessness seam.** Layer 1's guarantee is at the *element-stream* level:
`LsgDocument.decode` → `encode` is byte-identical to the inflated element data — asserted for
every fixture and every hostile-path test. At the *file* level, unmodified segments re-emit
their raw (compressed) payload through Layer 0; re-deflation is never used to prove identity.
A **modified** segment goes through `JtFile.withSegmentPayload` (re-layout: region order
kept, offsets/TOC/header recomputed, result re-parsed) and produces a legal file asserted by
**model equality**, not byte equality — the compressed bytes of a re-deflated stream are not
canonical, models are. `encodeLsgSegmentPayload` writes the segment-wide fields for a fresh
LSG payload: v9 → ZLIB (flag 2, algorithm 2, level 6); v10 → stored (algorithm 1), the
simplest legal encoding (the LZMA *decoder* landed with issue #5; an encoder stays a
deferral — see the table).

**v9 policy.** Types whose v9 layout is fixture-verified or follows from fixture-verified
sub-collections plus the version-width rule decode typed in v9: the whole node family
(including all shape nodes — their tails after verified collections are fixed-size, so the
strict length check turns any wrong derivation into a named opaque fallback, not a misread),
the property atom family, the property table, and the Material attribute. Package P6 added the
three types whose 9.5 layout the Rev-D figures settle completely and whose bodies are
fixed-size after the verified collections: **Infinite Light, Point Light and Geometric
Transform** (deltas 43–45 below). All *other* attribute types (draw style, light set,
line/point style, textures, mappings) remain **opaque-by-policy in v9** with
`ELEMENT_LAYOUT_UNVERIFIED` — the LOD/shape/material deltas below prove that v9 layouts do
*not* follow mechanically from v10, so guessing variable-length layouts would risk silent
misreads. Their time comes with the first v9 fixture that carries them.

**Known spec ambiguities recorded**: **where Base Attribute Data sits inside Base Light Data is
`spec unclear` in both generations, and no fixture can settle it.** v10 Figure 57 draws a stray
"Logical Element Header Compressed" box in that slot — *after* the `I8 Version Number` — while
9.5 Figure 54 (p.84, rendered) omits the collection altogether, though both light sections' prose
presupposes it ("does not have any Field Inhibit flag … bit assignments"). Both drawings are
corrupt in the same place; the two candidate placements have identical width, so the length
oracle cannot discriminate them, and neither 9.5 fixture nor the NIST file carries a light. The
library reads Base Attribute Data **first**, per the attribute-element convention every other
element follows: presence is not in doubt, only position, and the choice is recorded in
`BaseLightData`'s KDoc rather than hidden in the codec. Resolvable only by a real file (a 9.5
version-1 Infinite Light is 89 body bytes with the collection and 82 without) or a second
independent reader. The Vector4f Property Atom's GUID appears in §6.2 but is missing from
Table A.1; `ObjectTypeIds` carries it with a comment. Float payloads are re-encoded from
`Float`/`Double` fields; exotic NaN bit patterns are not guaranteed bit-stable on Kotlin/JS
(`Float.fromBits` normalization) — no real file has shown one; if one does, the affected
field moves to raw-bits storage.

## v9 vs v10 LSG element deltas (established, with byte evidence from the fixture)

Continuing the numbering of the structural deltas above; all verified against the 9.5
fixture's inflated LSG stream (8 663 bytes, 66 graph elements + 41 property atoms + property
table) and pinned by the v9 halves of the per-figure codec tests.

6. **Version Number fields are I16 in v9, one byte (U8/I8) in v10** — pervasive, verified
   across the node family (`01 00` at every version position of all 66 graph elements), the
   attribute family (material), the property atom family (all 41 atoms), and both generations
   share the I16 property-table version. The element header carries the I32 Object ID after
   the base-type byte in **both** generations (v10 Figure 18; v9 verified: every element's
   first body field parses as its object id, and the property table references exactly those
   ids).
7. **LOD Node Data: v9 carries a reserved VecF32 + I32 that v10 dropped.** Evidence: the
   fixture's 12 Range LOD nodes parse to exact length only with `2+4+4` reserved bytes
   between the group data and the range-LOD version; the two `01 00` version markers pin the
   field positions (offsets 0 and 10 of the post-group bytes).
8. **Base Shape Data: v9 stores a reserved BBoxF32 before the untransformed box.** Evidence:
   all 12 tri-strip shape nodes repeat the identical 24-byte box twice; v10 Figure 36 has one
   box. (The writer emits the untransformed box for the reserved field when a model has none.)
9. **Vertex Shape Data: v9 = I16 version, U64 vertex bindings, Quantization Parameters
   (4 × U8: bits per vertex / normal bits factor / bits per texture coord / bits per colour),
   and a second U64 binding field from local version 1 upwards.** ~~and for version ≥ 2 a
   second U64 binding field~~ — the original threshold was an empirical guess, corrected by
   the 9.5 document: **§7.2.1.1.1.10.2.1, Figure 30, p.48** draws the second box as
   `U64 : Vertex Binding`, guarded `Version Number == 1`, which under §9.4's append-only local
   versions means "belongs to version 1", i.e. present from 1 up (see *Local version guards
   mean `>= N`*). The original entry's honest evidence limit — "those 22 bytes are all zero,
   so alternative groupings would fit" — is also closed: the figure titles both boxes
   identically, and in all 29 shape nodes of the two 9.5 fixtures the second value equals the
   first, so it is the same field repeated, exactly as drawn. Presence is now resolved from the
   body's remaining length and recorded in the model, never re-derived on write.
10. **Base Attribute Data: v9 has no Field Final Flags** (I16 version, U8 state flags, U32
    field inhibit flags). Evidence: both material elements parse to exact length only without
    the v10 U32; with it, the RGBA block would misalign by four bytes.
11. **Material Attribute: v9 has no bumpiness; reflectivity exists from local version 2 on.**
    Evidence: both material elements are version 2 with exactly 18 F32 payload values =
    4 RGBA + shininess + reflectivity (v10 Figure 47 has 19 with bumpiness). Data-flag bits
    0x000F ~~(the v10 inhibit table hints at "Common RGB Value" compact colour storage in older
    generations)~~ refuse the v9 typed decode — layout not established, never guessed.
    **Rationale corrected (package P6, from 9.5 §7.2.1.1.2.2, p.62):** there is no compact
    colour encoding to hint at. 9.5 documents exactly the four bit groups v10 Table 18 does
    (`0x0010` Blending, `0x0020` Override Vertex Colours, `0x07C0` Source Blend Factor,
    `0xF800` Destination Blend Factor) and declares every other bit reserved, and 9.5's
    Figure 42 has no such field either. The "Common RGB Value" names are a shared editorial
    artifact of *both* generations' field-inhibit tables. The refusal stands — a set reserved
    bit means the layout of what follows is unestablished — only its stated reason was wrong.
12. **The Property Table layout is shared by both generations** (I16 version even in v10,
    Figure 78). Evidence: the fixture's 694-byte tail parses to exactly 40 element property
    tables with zero leftover; all key/value object ids resolve to decoded property atoms.
42. **Polyline Set Shape Node: v9 appends a guarded `U64: Vertex Bindings` that v10 does not
    have; Primitive Set Shape Node: v9 splits v10's fused `U64` into two `I32` bindings.**
    (Numbered in this record's append-only sequence, placed with its v9 LSG siblings.)
    Citations: **9.5 §7.2.1.1.1.10.4, Figure 33, p.50** — Vertex Shape Data, `I16 Version`,
    `F32 Area Factor`, `(Version Number == 1) U64: Vertex Bindings`, where v10 Figure 40 stops
    after the Area Factor; and **9.5 §7.2.1.1.1.10.8, Figure 37, p.53** — `I32 : Texture Coord
    Binding` + `I32 : Color Binding` where v10 Figure 44 has one `U64: Vertex Bindings` at the
    same offset. The Primitive Set delta is byte-neutral, so it never showed up as a length
    error; the JT 9 path simply recorded `(texCoord << 32) | colour` (or its reverse) as one
    opaque number. No fixture in the corpus carries a Primitive Set Shape Node, so this is
    spec-derived and unexercised by real bytes — the strict length check still protects it.
43. **Partition Node: the single `BBoxF32` between File Name and Area is the *Reserved Field*
    in v9 when Partition Flags bit 0 is set, and the Transformed BBox only when it is clear.**
    (Package P6.) **9.5 §7.2.1.1.1.2, Figure 14, p.36**, read from the rendered page — the
    layout dump destroys the branch geometry and is actively misleading here: the main path
    runs File Name → `BBoxF32 : Reserved Field`, and a side branch guarded
    `(Partition Flags & 0x00000001) == 0` reaches `BBoxF32 : Transformed BBox`, the two
    rejoining above `F32 : Area`. v10 Figure 23 has no reserved field and writes the
    Transformed BBox unconditionally. Exactly one box either way, so **the byte count matches
    v10 in both branches and nothing ever failed** — only the field's identity changes. Both
    9.5 fixtures corroborate the figure: Partition Flags = `0x1`, the middle box is the
    empty-box sentinel (`min = +FLT_MAX`, `max = −FLT_MAX`) that no producer would write as an
    extent, and the trailing Untransformed BBox holds the real geometry (KR360-1:
    `(−2826, −2825.8, −0.1) … (2826, 2825.8, 2869.2)`). The model discriminates the two
    (`PartitionNodeElement.reservedBBox` / `.transformedBBox`, exactly one non-null) and offers
    `extentBBox` for consumers that want the declared extent; the world-box probes now read
    that, where before they were computing a bounding-box diagonal of `Infinity` from the
    sentinel and passing every containment check trivially. The prose is silent on the
    conditionality (only the Untransformed BBox paragraph mentions bit 0), so `spec unclear` on
    the prose; the figure is unambiguous.
44. **Two 9.5 attribute field widths v10 changed: Geometric Transform's `Element Value` is
    `F32` (v10 `F64`), and Image Format Description's `Shared Image Flag` is `U8` (v10 `U32`).**
    (Package P6.) Citations: **9.5 §7.2.1.1.2.11, Figure 61, p.91** — figure box *and* prose
    heading both `F32 : Element Value`, against v10 Figure 63's `F64`; and **9.5
    §7.2.1.1.2.3.5, Figure 48, p.72** with its p.73 prose — `U8 : Shared Image Flag`, against
    v10 Figure 53's `U32`. Both are *self-disambiguating from the body* and are resolved that
    way rather than from the generation (lenient read): the transform's stored values are the
    element's last field group, so `popcount(mask) × 4` against `× 8` decides; the image list
    is the texture element's last field group, so parsing it under each candidate width and
    keeping the one that consumes the body exactly decides. What was read is a model fact
    (`GeometricTransformAttributeElement.valueWidth`,
    `ImageFormatDescription.sharedImageFlagWidth`), so re-serialization stays a projection.
    The Shared Image Flag matters out of proportion to its three bytes: it sits immediately
    before `I16 : Mipmaps Count`, so a misread does not shorten the block — it walks the mipmap
    loop out of step.
45. **The light family: `Shadow Parameters` moved between the generations, and 9.5 gates the
    pair on the element's local version 2.** (Package P6.) v10 Figure 57 keeps
    `F32 Non-shadow Alpha Factor` + `F32 Shadow Alpha Factor` inside Base Light Data,
    unconditionally. **9.5 Figure 54, p.84** ends Base Light Data at `F32 : Shadow Opacity`,
    and **Figures 53 (p.83) and 56 (p.86)** hang the pair off the *element*, after its own
    payload, on a branch guarded `Version Number == 2` — i.e. present from local version 2
    upwards (see *Local version guards mean `>= N`*). Both figures mislabel that branch box
    "Shadow Opacity"; their own captions point at §7.2.1.1.2.6.2 *Shadow Parameters*, and
    Shadow Opacity already exists unconditionally inside Base Light Data. `readBaseLightData`
    previously read all three trailing `F32`s in *every* generation — the v10 layout applied to
    v9, wrong by 8 bytes for a version-1 light and wrong by placement for a version-2 one (the
    direction / spot intensity would have absorbed the factors). Presence is resolved from the
    body's remaining length and recorded (`InfiniteLightAttributeElement.shadowParameters`,
    `PointLightAttributeElement.shadowParameters`, `BaseLightData.shadowParameters` for the v10
    placement), so a version-1 light cannot round-trip as a version-2 one.
46. **State Flags bit `0x01` and the per-element field-inhibit bit assignments mean different
    things in the two generations — same bytes, different tables.** (Package P6, semantics not
    layout.) 9.5 §7.2.1.1.2.1.1 (p.55) assigns bit `0x01` the attribute-wide **Accumulation
    Final** flag; v10 Table 15 declares it **Unused**, having replaced it with the per-field
    Field Final Flags word that JT 9 does not have. Bits `0x02` Force, `0x04` Ignore, `0x08`
    Persistable are identical. The model reads the bit through
    `BaseAttributeData.accumulationFinal`, which is false whenever the Field Final Flags word
    is present — the generation is legible from the model itself — and the scene façade now
    names the JT 9 flag it cannot honour instead of ignoring it. Separately, 9.5 p.60 gives the
    Material element a **"Diffuse Color and Alpha (Legacy)"** row at inhibit bit 1 that v10
    Table 16 lacks, so v10's assignments for bits 1–8 are 9.5's shifted down by one; the same
    shape of shift affects Texture Image (9.5 puts Internal Compression Level on bit 8 with
    bit 7 unused, v10 on bit 7). The inhibit word is therefore carried verbatim and never
    interpreted; any future interpretation must branch on the generation.

47. **The JT LWPA Element: one width delta that moves a byte, two that do not, and one codec
    swap.** (Package P7, 9.5 Fig. 215/216 against v10 Fig. 100/101, both rendered.) The two
    figures draw the same four fields in the same order under the same single
    `Analytic Surface Count > 0` guard, and Figure 216/101 hold the same six members with the
    same predictors (`Lag1` on the Analytic Surface Indices, `NULL` on the Analytic Surface
    Type). The deltas:
    - **`I16 : Version Number` in 9.5, `U8` in v10** — the pervasive generational delta
      (delta 6), and here the *only* one that shifts a byte boundary. A 9.5 element read the
      v10 way desynchronizes at the Surface Count and never recovers; the library refuses it
      by name, which `LwpaDocumentTest.aJt9BodyReadTheV10WayIsRefusedByName` pins by feeding
      one frame to both readers.
    - **`I32` vs `U32` on Surface Count and Analytic Surface Count** — four bytes in both, so
      this is a *signedness* delta, not a width one. The model keeps a `UInt` for both
      generations and the 9.5 read refuses a negative value by name rather than laundering it
      into four billion surfaces.
    - **`Int32CDP2` (9.5 §8.1.2, "Mk. 2") vs `Int32CDP` (v10 §12.1.1, third generation)** on
      the two index vectors. The two packets agree byte for byte in their CODEC-0 form, so the
      swap only bites on an entropy-coded packet — which is what
      `LwpaDocumentTest.jt9AnalyticSurfaceVectorsUseTheMk2Packet` exercises, with a Bitlength
      CodeText whose 25 bits mean `[1, 2, 4]` under 9.5's 6+6-bit min/max grammar and nothing
      coherent under v10's nibble-encoded one.

    **Nothing in the element reaches a codec the library lacks.** Figure 216's four F64 arrays
    are Table 2's *bare* `VecF64` — an `I32` count and plain doubles "written in binary form" —
    not `VecF64{Float64CDP}`. So the two 9.5-only codec families that gate the JT 9 B-Rep and
    the JT 9 wireframe curve payload (`Float64CDP` §8.1.3 and Int32 CDP Mk. 1 §8.1.1, neither
    implemented at the time) are simply not on this path. That is why LWPA landed ahead of them —
    and package P8 has since implemented both, which changes nothing here: the LWPA element still
    reaches neither.

    The *Supported Surface Type* value set (0 Nurbs … 5 Torus, 6/7 Reserved) is value-identical
    in both documents; 9.5 leaves its table unnumbered on p.210, v10 numbers it Table 100. And
    the Analytic Surface Creation flow chart is 9.5 Figure 217 == v10 **Figure 102**, box for
    box — both revisions print it, and the projection it describes stays a recorded deferral.

## Layer 1: Shape LOD bodies (issue #4)

**Scope and grounding.** The §7/§12 package decodes Shape LOD segment bodies typed for the
**JT 9 generation**, established against the fixture's 12 tri-strip bodies and the JT 9.5
File Format Reference (Rev-A) — the fixture generation's own spec, which documents the wire
formats the v10 reference superseded. Every decode was cross-validated three ways: exact
byte consumption on all 12 bodies, the stored Annex-C hashes (composite topology hash,
vertex coordinate hash, vertex normal hash — all 36 verified), and the LSG's declared
vertex/polygon count ranges. An independent open-source JT reader (OpenCASCADE's TKJT) was
consulted to disambiguate field order where both spec generations' figures are garbled; all
its claims were re-verified against the fixture bytes before adoption.

~~**The v10 wire formats are deliberately not implemented here.**~~ (Resolved by issue #6 —
see *Layer 1: v10 shape LOD bodies* below; the deferral's reasoning stands as written:) v10's
Int32CDP (Figure 132: no symbol field, 7-bit value widths, Move-to-Front codec), its
bitlength variant (Annex B's nibbler-based block scheme), its packed Deering code array and
its shape element bodies differ from the JT 9 generation enough that implementing them
unverifiable would be guessing. Until the v10 shape-body package establishes them, v10 shape
elements are carried opaquely with `ELEMENT_LAYOUT_UNVERIFIED`. **Correction (issue #5)**: this section
originally justified the deferral with *"every v10 shape body in reach (all 39 NIST
segments) sits behind segment-wide LZMA, so no v10 layout can be established or even
exercised; their time comes with the LZMA package, which makes the NIST bodies readable"* —
that was wrong. Shape LOD segments are never segment-wide compressed (Layer 0's own
recorded observation already showed the 39 NIST shape segments scanning as plain element
streams); the NIST v10 bodies were readable all along. What the LZMA package *did* unlock
is the NIST LSG, so the v10 bodies are now also cross-checkable against their declaring
shape nodes. The deferral itself stands — unverified layouts stay unguessed — only its
stated condition was corrected (see the deferral table).

**Model ↔ bytes mapping.** `de.haumacher.kotlinjt.shape` mirrors the spec collections
(`ShapeLodDocument` → elements + the Figure-78 property table that DESIGN.md's Layer-0
observation predicted — now decoded; `TriStripSetShapeLodElement` →
`TopologicallyCompressedRepData` → `TopologicallyCompressedVertexRecords` → compressed
vertex arrays → `Int32Cdp` packets). Every wire field is preserved in the model — CDP
packets keep their codec byte, bit counts, CodeText words, probability context (raw bytes)
and nested packets — so `encode` is a projection, never a re-run of an entropy coder:
`encode(decode(body))` is byte-identical by construction, asserted per fixture body and per
hostile-path test. Decoded values (`Int32Cdp.values`, coordinates, normals, triangles) are
derived at decode time and validated against the stored hashes; any mismatch refuses the
typed decode (opaque carry + `ELEMENT_DECODE_FAILED`), so a codec defect can never produce
silently wrong geometry.

**The geometry surface** (`TriStripGeometry`) is what Layer 2 stands on: unique vertex
coordinates (bit-exact floats on the lossless path; dequantized values with the quantizer
parameters exposed otherwise), unique normal records, and triangles as index triples with
per-corner normal indices and the face group. The topology decoder (v10 Annex D; reference
source in the 9.5 reference's Appendix E) reconstructs the dual VFMesh strictly: unconsumed
symbols, incomplete rings or out-of-range attribute references all refuse the decode.
Cover faces the coder added to close open meshes are removed. Dequantization follows the
§12.2.1 inverse (`min + code·(max−min)/maxCode`) — spec-derived; the fixture stores
coordinates losslessly, so no real file has yet pinned the reconstruction convention.

## v9 vs v10 shape body deltas (established, with byte evidence from the fixture)

Continuing the numbering; verified against all 12 tri-strip bodies (composite hashes and
exact byte consumption pin each layout — a one-byte deviation breaks both).

13. **Shape element bodies carry U8 Object Base Type (4 = Shape LOD, Table 4/Table 7) plus
    an I32 object id, like LSG elements.** Evidence: every body starts `04` + I32. The
    fixture writes object id 0 and the LSG's Late Loaded atoms' `payloadObjectId` values do
    **not** match it — the association that actually works is the atom's segment GUID.
14. **Tri-Strip Set Shape LOD Element (v9): two leading I16 versions (Base Shape LOD Data +
    Vertex Shape LOD Data), U64 bindings, TopoMesh LOD Data (I16 version, I32 vertex records
    object id), I16 version, Topologically Compressed Rep Data — then 12 trailing bytes read
    as I16 + U64 + I16.** The final I16 is the element's own version (9.5 reference Figure
    93 places it after the data); the preceding I16+U64 (values 1 and a repeat of the
    bindings in all 12 bodies) are reserved fields whose semantics no spec revision at hand
    documents — carried as named reserved fields, byte-faithful. v10 (Figure 81/85) drops
    them and shrinks the versions to U8/I8.
    **Corrected (issue #12, package P3)**: they are not reserved. They are 9.5 Figure 92's
    *TopoMesh Compressed Rep Data V2* auxiliary-vertex-field extension — `I16` version, `U64`
    vertex bindings, and, when bit 64 of those bindings is set, the auxiliary field list. See
    *Layer 1: the 9.5 polyline and point set bodies* below; the model field is now
    `auxiliaryVertexFields: AuxiliaryVertexFieldData?`, not `reservedVersion` /
    `reservedBindings`, and its presence is read from the framed body's length rather than
    assumed.
15. **The JT 9 Int32CDP is the 9.5 reference's "Mk. 2" packet** (§8.1.2), not v10's Figure
    132: I32 value count (count 0 ends the packet); U8 codec (0 null / 1 bitlength /
    3 arithmetic / 4 chopper); null/bitlength/arithmetic: I32 CodeText bit length + exactly
    `ceil(bits/32)` U32 words (no separate vector count); arithmetic then appends the
    probability context and an out-of-band nested packet (always present, possibly empty);
    chopper: U8 chop bits — 0 defers to one nested packet, else I32 bias + U8 span bits +
    MSB/LSB nested packets. Evidence: exact consumption across 506 packets (nested ones
    included) in the 12 bodies.
16. **The JT 9 probability context is a byte-aligned bit block** — U32{16} entry count,
    6+6+6-bit field widths, 32-bit min value, entries of (symbol+2, occurrence count,
    value−min) — with **symbol −2 as the escape** pulling the next out-of-band value. v10's
    Figure 133 restructured this (1-bit escape flag, 7-bit value width, no symbol field).
17. **The JT 9 bitlength CodeText is a two-mode stream** — 1 mode bit; fixed: 6+6-bit widths
    of a signed min/max pair then unsigned `bitlength(max−min)`-bit fields biased by min;
    variable: 32-bit signed mean, 3+3-bit field/run widths, runs of signed fields biased by
    the mean. **Neither spec's prose describes this wire format** (the 9.5 Appendix C shows
    the older Mk.-1 prefix-code scheme; v10's Annex B a nibbler-based block scheme); it was
    established from the fixture bits (e.g. face degrees `05 4 5 5 5 4 4` decode only under
    this grammar) and confirmed by TKJT. Recorded as the single most treacherous delta.
18. **Predictors: Lag1 primes with the first four residuals verbatim** (9.5 Appendix C ==
    v10 Annex B). Coordinate packets (exponents, mantissae, codes) use Lag1; normal packets
    (sextant/octant/theta/psi and exp/mant) use NULL; vertex flags and split face symbols
    use Lag1; all other topology streams NULL. The composite hash is computed over the
    **unpacked** (primal) values — verified on all 12 bodies, with the honest caveat that
    the fixture's Lag1-predicted streams are all zero, so residual-vs-primal hashing is
    distinguishable only by a future fixture.
19. **Lossless vertex arrays store exponent+mantissa packet pairs per component** (float
    bits = `(exp << 23) | mantissa`, exponent carrying the sign); v10 stores single "binary"
    arrays. **Quantized normals store four code packets** (sextant, octant, theta, psi); v10
    packs one Deering code array. Evidence: 12 coordinate hashes + 12 normal hashes verify.
20. **The 8th face-attribute-mask context is chunked 30 + 30 + 4 bits in JT 9** (three CDPs)
    vs v10's 32 + 32 (two); the attribute-mask context is `min(7, degree − 2)`, while the
    face-degree context derives from valence and known ring degrees (Annex D reference).
21. **Quantization Parameters' normal bits factor does not govern the normal array** — the
    fixture says factor 8 (nominally 22 bits) while the arrays' own U8 says 8 bits, and the
    normal hashes confirm 8. The array's own field is authoritative.
22. **A shape may store one more vertex attribute record than the masks reference** (one
    fixture body: 204 stored, 203 referenced); tolerated — the extra record stays in the
    model and re-encodes, it is just never referenced by a triangle corner.

## Layer 1: v10 shape LOD bodies (issue #6)

**Scope and grounding.** The v10 generation of the shape pipeline, established against the
NIST fixture's 39 shape LOD bodies (24 tri-strip + 15 polyline, 13 each per LOD tier) and
the v10 reference (§7, §12, Annex B reference source, Annex D). The condition of the issue-#4
deferral was met (the NIST bodies were plain bytes all along, and since issue #5 the decoded
LSG cross-checks them); every wire structure below was verified three ways before the Kotlin
code was written: exact byte consumption on all 39 bodies, every stored hash (24 composite +
24 coordinate + 24 normal hashes on the tri-strips; 15 FGPV + 15 unique-length + 15
coordinate hashes on the polylines — 117 in total), and the cross-model battery against the
LSG's declared count ranges.

**Mechanism — how the generations share code without contaminating each other.** The JT 9
wire readers are untouched; the v10 generation gets parallel *readers* (`Int32Cdp.readV10`,
`CompressedVertex*Array.readV10`, the v10 element body functions) dispatched by
`LsgGeneration` at the element-codec table, while the *model types* are shared wherever the
wire is generation-invariant (packet classes, vertex arrays, vertex records) and split where
it is not (`TopologicallyCompressedRepDataV10`, the v10 element classes). Because every wire
field is preserved, `encode` stays a single generation-free projection — the packet knows its
own shape; only decoding needs to know the generation. Genuinely identical logic is shared,
not duplicated: the 16-bit arithmetic decoder core (both context generations project to one
entry form), the Annex D topology decoder (unchanged), the Deering angle-to-vector math, the
Jenkins hashes, and the triangle-extraction core (`buildTrianglesFromTopology` — the JT 9 and
v10 paths differ only in how the 8th mask context's 64-bit masks are chunked). Files
declaring 10.0–10.4 use the same v10 path; where a 10.0 producer deviates, the strict
checks refuse to opaque-with-note rather than misread.

**What the fixture's bodies actually use** (and therefore what is fixture-verified): CODEC
types 1 (bitlength, 696 packets — both modes), 3 (arithmetic, 436), 4 (chopper, 67), 5
(move-to-front, 94) and the empty packet (147); quantized coordinates (LOD1/2, 9–17 bits)
and lossless binary-float coordinates (LOD0); packed Deering normals (LOD1/2, 3 and 6 bits
per angle) and lossless binary normals (LOD0); per-vertex flag arrays (all tri-strips bind
Table 48 bit 7). The null CODEC never occurs in v10 wire (implemented, spec-derived — its
layout is Figure-132-fixed and identical to the fixture-verified JT 9 form). Point set,
polygon set and primitive set bodies remain refuse-with-note **in v10**: no v10 fixture carries
one. (The *JT 9* point set landed with issue #12 — see below; the polygon set has no 9.5 LOD
element at all, and the primitive set none anywhere in the corpus.)

## v10 shape wire format as NX 10.5 writes it (established, with byte evidence from the NIST fixture)

Continuing the delta numbering. Unlike the LSG deltas 23–26, most of these are cases where
the 10.0 reference is silent or its prose contradicts its own figures — with no 10.0 fixture
at hand, "10.5 delta vs. 10.0 documentation gap" cannot be distinguished, and each entry says
which reading the evidence supports.

27. **Vertex Shape LOD Data wraps its TopoMesh collection in a nested Logical Element
    Header whose type GUIDs are absent from Annex A.** Figure 85 does show the header box
    (so the structure is 10.0-documented); the values are not: all 24 tri-strip bodies carry
    `{F830A5AD-BE4C-4FBC-9B5F-B9269278D2E1}` (TopoMesh Topologically Compressed LOD Data),
    all 15 polyline bodies `{11C12D32-38F9-45BA-93BA-66F9D538DDFB}` (TopoMesh Compressed LOD
    Data), both with Object Base Type 9 ("JtBase", Table 7). The I32 Element Length spans
    GUID + base type + object id + payload — everything after itself except the outer
    element's trailing U8 version (verified on all 39 bodies; validated at decode, preserved
    verbatim in the model).
28. **The arithmetic out-of-band packet is on the wire exactly when the probability context
    carries an escape entry.** Figure 132 shows the field unconditionally. Evidence: every
    escapeless context (e.g. all packed-Deering packets) is followed directly by the next
    wire field — shape14's Deering context ends at body offset 1669 where the stored normal
    hash begins; reading a nested packet there yields garbage — while all contexts with an
    escape entry are followed by a well-formed nested packet whose values the escape symbols
    consume exactly.
29. **Lossless vertex arrays hash whole component arrays, not per-value.** The §12.1.3 and
    §12.1.4 pseudo-code calls `hash32(&value, 1, uHash)` per element; the bytes disagree:
    all 8 lossless LOD0 bodies' coordinate and normal hashes verify only as
    `hash32(componentArray, n, uHash)` chained over the three components (the two formulas
    differ because the hash mixes the array length in). The quantized paths hash per
    component array in both text and bytes.
30. **Binary Vertex Normals use the NULL predictor.** The §12.1.4 prose says the float bits
    are "fed directly into the Lag1 predictor"; Figure 139 shows no predictor annotation.
    The bytes side with the figure: under NULL all 5 006 normals of the largest LOD0 body
    are unit vectors and the stored hash verifies; under Lag1, 4 939 of them are not even
    unit length. (Binary Vertex *Coords* do use Lag1, as both prose and figure say.)
31. **The Move-to-Front wire form** (§12.1.1 prose describes the idea, not the encoding):
    a nested Window Values packet then a Window Offsets packet; offset **−1 is the escape**
    pulling the next window value; the recency window holds 16 entries, both new values and
    cache hits move to the *front*, eviction is from the back. Evidence: 94 MTF packets (the
    vertex-group stream of every tri-strip body) whose decoded output feeds the verified
    composite hashes.

Recorded observations from the same evidence:

- **Figure 92 is byte-accurate for v10**: the 8th attribute-mask context stores 32 + 32 bits
  (one LSB packet plus the MSB packet) and the composite hash covers them as two 32-bit
  arrays — confirming that the 30/30/4 chunking really was a JT 9 generation delta (delta
  20), not a spec error.
- **NX's LSG-declared polygon counts are upper bounds, not exact**: every NIST tri-strip
  LOD decodes fewer triangles than its shape node declares (e.g. 4 010 decoded vs. 4 922
  declared) — consistent with NX counting the degenerate triangles of its original strip
  form; the NetAllied 9.5 fixture declares exact counts. The declared *vertex* counts are
  per-corner counts: exactly the polyline vertex-list lengths (all 15 polyline LODs match
  to the digit), an upper bound on unique tri-strip coordinates. The fixture battery
  asserts each generation's actual truth.
- **LOD tiers descend strictly** in the NIST fixture: all 26 tier boundaries of its 13
  parts have strictly fewer primitives (triangles / polyline corners) than the tier above.
- The v10 `Quantization Parameters` normal-bits factor equals the normal array's own
  per-angle bit count in all 39 bodies (`BitsPerNormal = 6 + 2·factor` = the packed code
  width) — no v10 counterpart of the JT 9 discrepancy recorded in delta 21; the array's own
  field remains authoritative in the decoder.

## Layer 1: the 9.5 polyline and point set bodies (issue #12, package P3)

**Scope and grounding.** The JT 9 generation of the *non-topological* shape path: 9.5 Figures 94
(Polyline Set Shape LOD Element) and 95 (Point Set Shape LOD Element) over the inherited
Figures 84, 85, 86, 87, 91 and 92. Evidence: the five polyline and one point-set bodies of the
9.5 bug fixture, every stored hash in them (6 FGPV + 6 unique-vertex-map + 6 coordinate), exact
byte consumption on all six, and the 23 JT 9 tri-strip bodies of both 9.5 fixtures for the
Figure-92 extension and the composite-hash correction. The delta analysis is
`docs/spec95-analysis/E-shape-lod.md`; the ledger rows are package E of `SPEC_COVERAGE_95.md`.

**The reader was built from the document, not copied from the v10 one — deliberately.** 9.5
Figure 91 and v10 Figure 89 describe the same collection and differ in six field-level places.
Five of them are visible in a byte count and would have been found by trial; the sixth is not,
and it is the one that decides correctness:

1. **`if Polyline Shape` guards the face-group section.** 9.5 puts the `I32 Number of Face Group
   List Indices` *and* the Face Group List Indices array behind that guard; v10 writes both
   unconditionally. The point-set body proves the guard is literal: parsed with the count and
   the array, its next packet reads a CODEC byte of 128; parsed without them, it lands exactly
   on the primitive-list packet and consumes to the byte. The reverse experiment fails
   symmetrically on the polylines (CODEC byte 2).
2. **The three index lists are NULL-predicted (`{Int32CDP2}`), not Lag1 (`{Int32CDP, Lag1}`).**
   Both readings frame identically — same packets, same byte count — so only the stored hash
   can arbitrate, and it does: body `453756`'s FGPV hash is `0xbc4a3adf`, which the NULL reading
   reproduces and the Lag1 reading turns into `0x6f4a2d74`. The decoded lists agree with the
   verdict (NULL: `Prim [0,2,4,6,8,…]`, `Vtx [0,1,1,2,2,3,…]`; Lag1: `Prim [0,2,4,6,14,24,…]`).
   A copy-and-patch of the v10 reader inherits Lag1 and refuses all five bodies — a *false*
   refusal, which is worse than a wrong answer because it looks like honesty.
3. **9.5 adds `I32 : Number of Unique Vertex Coordinates`** inside the `if number records > 0`
   branch; v10 has no such field. Confirmed by consumption and by the two invariants 9.5's own
   prose states: it equals the length list's entry count and the coordinate array's count, and
   the length list sums to the vertex-record count (347 / 347 / 347 on the largest body).
4. **The counts are `I32` in 9.5, `U32` in v10** — same width, no observable difference; the
   reader takes the signed reading 9.5 prints and refuses a negative.
5. **The FGPV hash pseudo-code guards its face-group term `if (bLineStrip)`**; v10 deleted the
   guard, as it deleted the figure's. The point-set body's stored `0xe34cace1` is
   `hash32(prim) -> hash32(vtx)` with no face-group term.
6. **Auxiliary fields live in the Figure-92 V2 tail**, not in an `if AuxField Bindings` box
   inside the representation as v10 Figure 89 puts them.

**Figure 92, and what delta 14 called reserved.** 9.5 §7.2.2.1.2.3 / Figure 87 reads TopoMesh LOD
Data, an `I16` version, and then *TopoMesh Compressed Rep Data V2* if that version is at least 2,
V1 otherwise. V2 is V1 followed by `I16` version, `U64` vertex bindings and — only when Table 48
bit 64 is set — the auxiliary field list. All six polyline/point bodies declare version 2 and
carry exactly ten such bytes with bit 64 clear, which accounts for the "12-byte tail" delta 14
recorded (ten, plus the element's own trailing `I16`). All 23 tri-strip bodies carry the same ten
bytes in the same place although their container is *TopoMesh **Topologically** Compressed LOD
Data*, whose Figure 88 draws **no** version branch — while its own §7.2.2.1.2.4 prose declares
version `0x0002` valid, and §9.4's append-only local versions make appended data the only
consistent reading. **Figure 88 is incomplete**, and the model now names those fields
`AuxiliaryVertexFieldData(version, vertexBindings)`.

**Presence is read from the length, not from the version** — package P2's mechanism (issue #11),
and here it is not merely preferable but necessary, since the topologically compressed container
offers no version branch to test. The element's own trailing `I16` is the only field after the
representation, so a framed body has either 2 bytes left (no extension) or 12 (extension without
an auxiliary field list). The two readings cannot both fit, so nothing is guessed; anything else
refuses by name. The declared container version is preserved as read and never used as a gate, so
a producer writing version 1 with the extension — or version 2 without it — still round-trips
byte-exactly. The auxiliary field list itself refuses with a named message rather than being
decoded from the document alone: its 46-row type table and type-branched arrays have never been
validated against a real byte, and `ELEMENT_DECODE_FAILED` over a verbatim body is the honest
outcome until a fixture exists.

**The composite hash was hashing the wrong thing, and it was a latent false refusal.** 9.5 p.116
computes the composite hash over the *derived* face-attribute-mask arrays: contexts 0-6 masked to
their low 30 bits, and context 8's three projections (`& 0x3fffffff`, `>> 30 & 0x3fffffff`,
`>> 60 & 0x0f`) each hashed with `anAttrMasks[7]` elements — the context's own mask count,
whatever the three stored packets happen to carry. The library hashed the stored packets. Masking
contexts 0-6 is provably immaterial (the mask context is `min(7, degree - 2)`, so those contexts
hold rings of degree 2-8 and their masks are at most 8 bits wide); it is applied anyway, so the
code reads as the document does. The *lengthening* is not immaterial: the Jenkins hash mixes the
element count in, so a producer that elides an all-zero upper chunk as an empty packet — and the
top chunk is bits 60-63 of the mask, all zero unless some vertex has ring degree above 60, i.e.
all zero in every body of this corpus — writes a hash over N zeros where the old reader computed
one over an empty array. Different value, `ELEMENT_DECODE_FAILED`, a conformant file refused.
Both corpus producers happen to write the chunks out in full, which is why no fixture could ever
have caught it. `context8Chunks` now derives the three arrays once, at the context's length, and
both the hash and the mask reassembly read from it, so the two can no longer drift apart. The
regression test rewrites every JT 9 tri-strip body of every discovered fixture with its all-zero
top chunk elided and requires the stored hash to still verify and the mesh to be unchanged; under
the old rule every one of those bodies false-refuses.

**Two spec defects read past, and recorded rather than "fixed".** Figures 93, 94 and 95 each show
only Logical Element + Vertex Shape LOD Data + version, omitting the Base Shape LOD Data box that
Figure 84 requires of every Vertex Shape LOD Element; the bytes side with Figure 84 (every JT 9
shape body opens `04 | I32 objectId | 01 00 | 01 00 | U64 bindings` — two `I16` versions). And
Figure 97's Primitive Set Shape Element orders its fields differently from the prose that
describes them, which is one of the reasons that element stays opaque.

**The geometry surface.** `PolylineGeometry` is unchanged and now serves both generations through
`PolylineGeometryCarrier`; the point set gets `PointGeometry(vertices, points)` — coordinates
smeared to vertex-record space through the unique-length list exactly as for polylines, and one
vertex-record index per point in file order. A point set has no face groups to assign points to,
because 9.5's `if Polyline Shape` puts none on the wire; the primitive list that slices them is
validated at decode (it must tile the vertex list) and preserved in the wire model, so the
projection loses nothing. 9.5 §7.2.2.1.5's "Each point constitutes one primitive of the set" is
recorded but *not* enforced — it is a statement about meaning, and refusing a body that groups
several points into one primitive would be a false refusal. Layer 2 sees neither yet:
`SceneNode` has no point concept, and the scene's shape resolution for this fixture is
issue #13's.

**One branch of Figure 91 the corpus does not exercise**, and where the figure's own bracket is
hard to read even rendered: whether the binding-guarded vertex arrays sit inside the
`If number records > 0` branch or after it. No body in either 9.5 fixture declares zero vertex
records; the reader mirrors the v10 sibling (`readTopoMeshCompressedRepData`, settled on the
NIST bodies) and stops at the record count when it is zero. Its time comes with a fixture that
has one.

## LZMA decoding (issue #5)

**What JT v10 "LZMA" actually is: the `.xz` container with an LZMA2 filter.** Clause 12.2.5
never states a container; it names XZ Utils and lists the liblzma entry points JT
implementations use — `lzma_easy_encoder` / `lzma_stream_decoder`, which are the `.xz`
(not the classic `.lzma`) entry points. The fixture confirms it: all 68 flag-3/algorithm-3
segment bodies of the NIST 10.5 file begin with the `.xz` stream magic `FD 37 7A 58 5A 00`;
stream flags `00 04` (CRC64 check — the XZ Utils default); block header `02 00 21 01 10` =
one filter, id 0x21 (LZMA2), one props byte, dictionary size code 0x10 (1 MiB). All 68
streams decode under exactly this shape (59 in known compressible segment kinds; the other
9 sit inside the unknown NX segment types 23/31, which are carried opaquely and never reach
the codec).

**Platform decision: a pure-Kotlin decoder in `commonMain`** (`codec/Xz.kt` container +
CRC32/CRC64, `codec/Lzma.kt` LZMA2 chunk layer + LZMA1 range decoder, ported from the
normative decoder in the public-domain LZMA SDK's specification). Rationale against the
`expect`/`actual` alternative (JVM: org.tukaani:xz; JS: an npm lzma package): the JS side
has no maintained, full-`.xz` decoder of trustable provenance (most npm lzma packages are
abandoned or decode only the classic `.lzma` framing — which JT does not use); decode-only
scope keeps the pure-Kotlin cost at ~700 lines; zero dependencies serve both targets
identically; and 59 real-producer streams plus liblzma-written vectors (committed in
LzmaTest, both platforms) give the port a real acceptance spine. The zlib seam precedent
(expect/actual) does not carry over: platform zlib exists everywhere, platform xz does not.

**Strictness policy.** Structure, header CRC32s, the block index and the stream footer are
fully verified; block checks none/CRC32/CRC64 are verified (CRC64 is what the fixture and
XZ Utils' defaults use). The defined-but-unseen SHA-256 check is consumed *without*
verification — the XZ spec sizes all sixteen check IDs precisely so decoders can pass over
checks they do not implement; verifying it gets its time with the first real stream that
carries one. Refusals are two-tone and never throw through the API: corrupt/truncated
streams → `COMPRESSED_DATA_CORRUPT`; well-formed streams using unimplemented xz features
(non-LZMA2 filter chains, reserved check/flag bits) → `UNSUPPORTED_COMPRESSION`, raw bytes
kept either way. **Decode-only**: the writer keeps emitting ZLIB (v9) / stored (v10) per
the issue #1 codec policy; whole-file byte identity always re-emits raw payloads and never
depends on re-compression.

**What the 59 segments contained** (all inflate, all element-scan cleanly, each ending in
the Figure-78 six-byte empty property table except the LSG, which carries a real one): the
LSG (13 KB → 90 736 bytes), 14 PMI Data, 30 Meta Data, 8 XT B-Rep, 5 Wireframe, 1 MultiXT
B-Rep. The 44 meta data / PMI bodies decode typed since issue #9 (see *Layer 1: Meta data and
PMI*) and the 5 wireframe bodies since issue #10 (see *§8–§10*); the B-rep bodies stay opaque by
doctrine, with that opacity now proven rather than asserted. The 9 streams inside the undefined NX
types 23 and 31 do reach a codec since issue #10 — through a verified probe, not a guess.

## v10.0 vs 10.5 LSG element deltas (established, with byte evidence from the NIST fixture)

Continuing the delta numbering. The v10 reference documents the **10.0** wire format; the
NIST file (`Version 10.5 JT  DM 9.8.0.0`, the Siemens writer) deviates in four places,
established by exact byte accounting over its 1 211 LSG elements and pinned by
`Lsg105GenerationTest` plus the fixture battery. The codecs now distinguish a third
generation, `V10_5` (selected for `major > 10 || (major == 10 && minor >= 5)`); files
declaring 10.0–10.4 keep the spec-derived 10.0 layouts until a fixture shows otherwise.

23. **Partition Node (Figure 23): 10.5 inserts a version number (U8, observed 1) between
    the Group Node Data and the Partition Flags — and sets flags bit 0 *without* storing
    the untransformed bounding box.** Evidence: the single NIST partition parses to exact
    length only with the version byte and without the box (flags = 1, body ends after the
    polygon count range). Since the box is the element's final field, its presence is
    decided by the remaining length; every other combination falls to the strict
    full-consumption check (opaque carry, named note). The model invariant is accordingly
    one-directional: a stored box requires the bit, the bit no longer implies the box.
24. **Attribute elements (everything carrying Base Attribute Data) gain a trailing I32 at
    the very end of the element body — after the type-specific fields — observed −1 in all
    88 NIST attribute elements** (37 Material + 36 Geometric Transform + 15 Linestyle;
    three independent body layouts pin the position). Not documented by the v10.0
    reference; semantics unknown, carried verbatim (`BaseAttributeData.reservedTail`).
    Applied to the whole attribute family as a family rule — for types absent from the
    fixture (styles, lights, textures, and the tail-after-nested-element placement of the
    Texture Coordinate Generator) the placement is derived, and the strict length check
    turns a wrong derivation into an opaque carry, never a misread.
25. **Late Loaded Property Atom (Figure 76): 10.5 drops the Reserved I32** that the v10.0
    reference documents as "guaranteed to always be greater than or equal to 1". Evidence:
    all 105 NIST atoms are 35-byte bodies = base + version + GUID + two I32 — four bytes
    short of the documented layout; the v9 fixture's atoms carry the field.
26. **Date Property Atom (Figure 75): 10.5 appends an F32.** Evidence: all 13 NIST atoms
    trail exactly four bytes decoding as −4.0f — consistent with the UTC offset of the
    stored timestamps (NIST, US Eastern Daylight Time), but the semantics are not
    documented anywhere at hand, so the field is carried verbatim
    (`DatePropertyAtomElement.trailingField`), never interpreted.

With these four deltas every NIST LSG element decodes typed: 267 graph elements + 944
property atoms + a 135-entry property table, zero notes, element-stream round-trip
byte-identical, and the LsgProbeTest coherence probes (reference resolution, single root
partition, property-table validity, late-loaded atoms hitting real TOC segments of the
declared type) pass cross-producer for the first time.

## Layer 2, read side: the scene façade (issue #7)

**The API as landed** (`de.haumacher.kotlinjt.scene`, platform-free): `readScene(bytes, lodPolicy)`
/ `JtFile.readScene(lodPolicy)` → `Scene(units, root, notes)`; `SceneNode(name, transform,
meshes, polylines, material, children)`; `Mesh` (positions + normals, OBJ-style dual-indexed
triangles); `PolylineSet`; `Material(baseColor, roughness, metallic)`; `LengthUnit`
(Table 77 value set + `UNSPECIFIED`, each with `metersPerUnit`); `LodPolicy` (`ALL_LODS`,
`FINEST_ONLY`); own `Vec3`/`Color`/`Mat4` value types (nothing JT-specific in the package's
surface). Deviations from the issue #1 sketch, each deliberate:

1. **`Scene.notes`** — the honesty mechanism. The extraction never throws for content
   problems and never drops geometry silently; everything the scene cannot represent is a
   named note on the Scene (`SCENE_STRUCTURE_UNAVAILABLE`, `SCENE_STRUCTURE_INCOMPLETE`,
   `SCENE_GEOMETRY_UNAVAILABLE` — locating the node by its nearest *named* ancestor and the
   segment GUID — `SCENE_UNITS_UNRECOGNIZED`, `SCENE_UNITS_MIXED`,
   `SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED`, `SCENE_MATERIAL_AMBIGUOUS`,
   `SCENE_LOD_TIERS_UNALIGNED`). A part whose
   geometry refuses to decode keeps its named node, empty, plus the note (pinned by a
   synthetic whole-file test). Note lists live on the Scene, not on nodes — nodes stay
   shareable across instance paths.
2. **`SceneNode.polylines`** — a parallel per-LOD list next to `meshes`. 5 of the NIST
   fixture's 13 parts are polyline-only (section curves, tracelines); skip-with-note would
   demote perfectly decodable real-producer geometry. Rationale over the sketch: wireframe
   parts are first-class scene content, not a refusal.
3. **`readScene` never throws for content**: a file without a decodable LSG yields an empty
   root plus `SCENE_STRUCTURE_UNAVAILABLE` (`JtFormatException` stays reserved for
   "not a JT file" at `JtFile.parse`).

**Transform convention** (validated by TransformProbeTest, now the model's contract): `Mat4`
is row-major, row-vector — `p' = p·M`, translation in elements 12–14, world(child) =
`child.transform * world(parent)`. `SceneNode.transform` is *local*; the flat array layout
interchanges with glTF's column-major matrices without transposition.

**The walk and the collapse.** The partition node is the root (§13.9). Conversion is
memoized per LSG object id, so **instanced subtrees are shared scene objects** — identity
semantics recorded: two paths to one part hold the *same* immutable `SceneNode`/`Mesh`
objects (pinned by `assertSame` on the NIST hex nut's 10 instances); equality stays
structural, sharing is an optimization the consumer may exploit but must not rely on for
distinctness. The §13.9 Figure 160 part convention (Part → Range LOD → per-tier Group →
Shapes) collapses to one named part node: a LOD node's shapes become the part's geometry
(see *One node per body* below), a *pass-through* child (no name, identity transform, no
material, no geometry) is spliced out, and a sole nameless transform-free child is absorbed.
The collapse loses nothing the Scene models — it is what turns the 9.5 fixture into root →
assembly → 12 named parts and the NIST file into root → 38 placed instances over 13 shared
parts. Transforms *inside* a LOD tier (below the point where a tier's shapes become nodes)
are baked into the vertex coordinates (points `p·M`, normals via inverse-transpose,
renormalized).

### One node per body, one list entry per LOD (issue #13, Bernhard 2026-08-02)

The first model made a **LOD tier** the unit of geometry: a tier became one merged mesh, and
`SceneNode.meshes[i]` was tier *i*. That survives the NIST fixture, where a tier holds one
shape, and dies on `KR360-1.jt` (NetAllied 9.5), whose single tier is a Group Node holding 17
shapes — 11 tri-strip bodies with **11 different materials**, 5 polyline sets and a point set.
One merged mesh can only carry one material, so ten colours of a colour-coded assembly were
named away by `SCENE_MATERIAL_AMBIGUOUS` and the file rendered black. A Group Node under a
tier is ordinary JT, not exotic, so this was a fidelity ceiling in the seam ConstructIt
consumes, not a producer quirk.

**The model now**: the unit of geometry is the **body** — one shape node of the LSG — and the
unit of the `meshes`/`polylines` lists is the **LOD**. A LOD node's tiers are its alternative
representations; the shapes *within* a tier are paired with the shapes of the other tiers by
**position in the tier's shape order**, and each such *shape slot* becomes one scene node whose
mesh list is that body across the tiers, finest first, and whose material is its own. So a part
with M tiers of N shapes is a node with N geometry-bearing children of M meshes each; with
N = 1 the sole nameless slot is absorbed by the existing collapse, which is exactly where the
familiar "one mesh per tier on the part node" comes from — the NIST scene is byte-for-byte the
same as before (8 mesh parts × 3 tiers, 5 polyline parts × 3, 24 shared meshes). Rendering
level *i* means taking entry *i* of **every** node, never several entries of one node.

Consequences recorded, each deliberate:

* **`SCENE_MATERIAL_AMBIGUOUS` is nearly spent.** Within a tier no ambiguity can arise any
  more — one shape has one effective material. It fires only where the tiers *of one body*
  disagree, and no fixture in the corpus does.
* **Slots are numbered over shape *nodes*, not over decoded bodies**, so a tier whose shape
  failed to decode (already a named `SCENE_GEOMETRY_UNAVAILABLE`) does not shift the pairing of
  the others — the documented FINEST_ONLY fallback keeps working unchanged, with one note, not
  two.
* **Positional pairing is the only correspondence the file offers**, and it is safe for
  geometry: whatever the pairing, entry *i* of every node comes from tier *i*, so a uniform
  choice renders exactly that tier. It is *not* safe when a **coarser tier holds more shapes
  than a finer one** — then some slot's ladder has a hole and its entry *k* is no longer
  tier *k*. That, and only that, is `SCENE_LOD_TIERS_UNALIGNED`. Fewer shapes in a coarser tier
  is ordinary (a body that stops at some level) and is not noted.
* **A body's own ladder**: a shape node referencing several Shape LOD segments (types 6–16) is
  read as that body's own tiers, ordered by segment type (LOD0 finest) instead of merged into
  one mesh — the old merge would have drawn LOD0 and LOD1 on top of each other in silence. No
  fixture writes one; the ordering is Table 6's.
* **Names**: a slot node takes its shape node's `JT_PROP_NAME`, which real producers leave
  unset — so split bodies are unnamed nodes under a named part. Inventing `"part #3"` names
  would put a string in the model that the file does not contain; the guarantee kept instead is
  *locatability* (every geometry-bearing node is named or has a named ancestor), which is what
  `SceneFixtureTest` now asserts.

Alternatives rejected: **(a) keeping the merge and letting `Mesh` carry per-triangle material
ranges** — that puts a second material concept in the model and a submesh loop in every
consumer, for a case the tree already expresses; **(b) making the tier a node level of its own**
(part → tier → shapes) — the scene has no way to say "these children are alternatives", so a
consumer would render all tiers superimposed, which is a lie a note cannot fix; **(c) splitting
per shape but refusing coarse tiers when the counts differ** — it never mixes tiers, but it
drops geometry that decoded perfectly, and carrying it with a named note is the better trade.

**Write side**: a `SceneNode` carrying both meshes and polyline sets is now refused by
`writeJt` (`JtWriteException`) — it is two bodies on one node, and the read side would return
it as two siblings. This is the same discipline as the writer's other refusals: never write a
file that reads back as a different scene.

**Names — what the fixtures actually use** (§13.8, Table 79): both producers put
`JT_PROP_NAME` (hidden form, no `::`) on instance nodes and the partition, with the encoded
value form `Name;version;instance:` (e.g. `90591A141 HEX NUT.asm;23;2035:`,
`RB___E_01955.asm;0;0:`); the name component is extracted, non-matching values pass
verbatim. Key lookup accepts hidden and visible (`key::`) forms as one key, case-sensitive
otherwise. NX 10.5 additionally writes `CAD_PARTNAME::` on metadata nodes — not used for
naming (JT_PROP_NAME is the convention the spec names for node names); it stays readable at
Layer 1. Unnamed nodes get `""`, and geometry notes locate themselves via the nearest named
ancestor (the name sits on the instance above the part in both fixtures).

**Units — where the fixtures declare them** (§13.8, Table 77): both fixtures write
`JT_PROP_MEASUREMENT_UNITS` = **"Millimeters"** — capitalized, though ISO 14306 Ed 1 says
lowercase; the spec's own note records exactly this producer behavior and tells readers to
accept both, so parsing is case-insensitive. The NIST file declares it 44× (every metadata
node and part node), the 9.5 file 14× — uniformly. Scene policy: one recognized distinct
value → that unit; none → `UNSPECIFIED` (explicit, note-free — absence is a fact, not an
error); unrecognized values → `SCENE_UNITS_UNRECOGNIZED` per value; conflicting recognized
values → `SCENE_UNITS_MIXED` + `UNSPECIFIED` (a single global units field cannot honestly
hold two; the per-path precedence rule for mixed-unit files is deferred, see the table).

**Material mapping** (recorded; JT Phong → scene PBR): `baseColor` = Diffuse Colour + Alpha;
`roughness` = `sqrt(2 / (2 + shininess))` clamped to [0,1] (the standard Blinn-Phong
exponent → microfacet roughness conversion); `metallic` = 0 — classic JT materials carry no
metalness concept and inferring one from specular chroma would be a guess. Ambient,
specular, emission, reflectivity and bumpiness are abstracted by design and stay visible at
Layer 1. Accumulation follows §13.9: materials **replace** down the LSG (the shape's
material wins over the part's — both fixtures carry both), transforms **multiply**
(`A(child) = M(child)·A(parent)`); same-type attributes on one node accumulate in list
order. `SceneNode.material` is the material *introduced/effective at that node*, `null` =
inherit — replacement stays representable on shared subtrees. The Accumulation Ignore flag
(Table 15, 0x04) is honored; force (0x02), field-final and field-inhibit flags are beyond
the modelled semantics and produce `SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED` instead of a
silent misrepresentation (both fixtures use stateFlags = 8, persistable only, all
inhibit/final words 0 — verified across all 213 attribute elements).

**Geometry resolution**: a shape node's geometry is found through its Late Loaded Property
Atoms whose segment type is a Shape LOD type (6–16) — resolution by segment GUID, the
association the fixtures actually honor (DESIGN.md delta 13: object ids do not match), and
not by the `JT_LLPROP_SHAPEIMPL` key string. The internal `buildScene(document, policy,
resolver)` seam lets synthetic tests drive the whole walk with hand-built Layer 1 documents.

**LodPolicy**: `ALL_LODS` carries one mesh/polyline set per decoded tier, finest first;
`FINEST_ONLY` keeps the finest *decodable* tier — a failed finer tier falls back to the
next coarser one *with* its `SCENE_GEOMETRY_UNAVAILABLE` note, so the substitution is
visible. Range-limit-based (eye distance) selection is viewer runtime behavior, not a file
property.

## Layer 2, write side: `writeJt` (issue #8)

**The API as landed** (`de.haumacher.kotlinjt.write`, platform-free): `writeJt(scene,
byteOrder = LITTLE_ENDIAN): ByteArray` and `writeJtFile(scene, byteOrder): JtFile` (the same
image, parsed back — what tests inspect). Refusals are a `JtWriteException` naming the
offending scene path; nothing is ever written that would read back as a different scene.
Output is **deterministic** — identical scenes produce identical bytes, which is what makes
golden pinning possible; segment GUIDs are therefore minted from a per-file index plus a
`kotlinJT` marker instead of randomly.

### Version and codec choices (the "one version, simplest encodings" policy of issue #1)

- **Version string `"Version 10.0 JT kotlinJT"`**, padded per Figure 11 with the five
  ASCII/binary translation-detection bytes. The 10.0 wire is what the reference blesses; the
  10.5 deltas 23–26 are read but never written.
- **No segment-wide compression.** The brief for this package suggested "ZLIB (flag 2) or
  uncompressed" — but Table 8/Table 9 of the v10 reference define *only* flag 3 / algorithm 3
  (LZMA) and algorithm 1 (none); ZLIB is a JT 9 generation value with no v10 encoding at all.
  With the LZMA *encoder* still deferred, the only legal v10 choice is storing plainly
  (flag 0, algorithm 1), which is what `encodeLsgSegmentPayload` already emitted. Shape LOD
  segments are not compressible by Table 6 and carry no compression fields.
- **Null CODEC (Table 64 value 0) for every Int32CDP**: value count, codec byte, CodeText
  length `32·n`, then the values as plain 32-bit words. Predicted fields (Figure 92's
  `Lag1` fields, the binary coordinate arrays) store residuals produced by `packResiduals`,
  the exact inverse of the reader's `unpackResiduals`.
- **Lossless vertex arrays**: zero quantization bits everywhere, raw float bits per component
  (Lag1 for coordinates, NULL for normals — deltas 29/30), per-component-array hashes. Nothing
  the writer emits is lossy, so coordinates and normals come back bit-exact.
- Both byte orders are supported and tested; little-endian is the default, as in the installed
  base.

### The shape representation: why triangles must use the topology coder, and which topology

§7.1.4.1.2.2 settles the question the brief asked to establish from the spec: TopoMesh
Compressed Rep Data (the cheap face-group/primitive/vertex index lists) "is used when the shape
type is Polyline Set Shape Node Element, or Point Set Shape Node Element. For Tri-Strip Set
Shape Node Element and Polygon Set Shape Node Element, please refer to Topologically Compressed
Rep Data" — and Figure 85 branches on exactly that. **There is no simpler legal representation
of triangles in JT 10**; the dual-mesh topology coder is mandatory. (Polylines do take the
cheap path, which is what the writer emits for them.)

Implementing the Annex D *encoder* means mirroring the decoder's traversal, its active-face
queue heuristic, its split faces and its boundary cover faces exactly — and renumbering every
vertex into visit order. Instead the writer emits the simplest topology the coder can express:
**one connected component per triangle, closed by a single cover face** (the mirror of the
triangle). §7.1.4.1.3.1 documents that mechanism itself — the Vertex Flags array "contains a
value of 0 when the dual face was present in the original triangle mesh, and a value of 1 if the
dual face is a cover face that was added to artificially close the original mesh" — and every
conforming reader drops those faces again. Each component is therefore a closed 2-manifold, the
invariant the coder is built around, and the decoded result is exactly the triangles the writer
was given, in the given order and winding.

Per triangle the streams are (verified against the decoder, `WriteJtTest`):
valences `[3, 3]`; groups `[0, 0]` (the Layer 2 scene carries no face groups); flags `[0, 1]`
(Lag1 residuals on the wire); face degrees `[2]` in context 1 and `[2, 2]` in context 0 (the
Annex D context rule keyed on valence and known ring degree); three attribute masks in context 0
(degree 2 → mask context 0), each `1` when the mesh binds normals and `0` when it does not; no
split faces, no high-degree masks, no 8th-context MSB words. The vertex records then carry three
coordinates and (with normals) three normal records per triangle, in the same order.

**The cost, recorded honestly**: vertices are not shared between triangles, so a mesh stores
three coordinates per triangle instead of roughly half a coordinate, and the null CODEC adds no
entropy coding at all. Measured: the unit cube (12 triangles, one material) is **2 995 bytes**;
the NIST 10.5 fixture (1.62 MB, LZMA + quantized coordinates + a real topology coder) rewrites
to **6.07 MB** at `FINEST_ONLY` and **11.1 MB** with all three LOD tiers; the 9.5 fixture
(48 KB) rewrites to 687 KB. Correct and boring, as the policy demands — a vertex-sharing
Annex D encoder is a named future extension (see the deferral table), and so is the LZMA
encoder.

**Consequence for scene equality**: a re-read mesh has per-corner `positions`/`normals` arrays,
i.e. the same geometry with a different indexing. The acceptance therefore compares *resolved*
geometry — per triangle, in order, the three corner positions and normals as values — which is
what `assertSceneEquivalent` does (it also uses a small float tolerance, because on Kotlin/JS a
`Float` literal is only narrowed once it passes through the wire).

### The material inverse (`readScene`'s mapping, run backwards)

The read side maps JT Phong → scene PBR as `baseColor = diffuse colour + alpha`,
`roughness = sqrt(2 / (2 + shininess))`, `metallic = 0`. The writer inverts exactly that:

- `Diffuse Colour and Alpha` = `baseColor` (RGB and alpha verbatim),
- `Shininess` = **`2 / roughness² − 2`**, with the roughness first clamped to
  `[sqrt(2/130), 1]` so the exponent lands in `[0, 128]` (roughness 0 would demand an infinite
  exponent; 128 is the range Phong exponents are written in),
- `Ambient Colour` = the base colour's RGB with alpha 1, `Specular Colour` = 0.35 grey (what the
  installed base pairs with a diffuse material), `Emission` = black, `Reflectivity` = 0,
  `Bumpiness` = 1,
- `Data Flags` = 0 (Table 18: blending off, no vertex-colour override, zero blend factors),
  Base Attribute Data `stateFlags` = 8 (persistable only — what all 213 attribute elements of
  both fixtures carry), no inhibited or final fields,
- `metallic` is **not** encoded: classic JT materials have no metalness concept, and inventing
  one would be a guess in the opposite direction of the read-side decision.

The round trip is therefore exact in the scene's terms (`roughness → shininess → roughness`
agrees to well under 1e-5 for every value the fixtures and tests carry) while everything JT
knows and the scene does not (ambient, specular, emission, reflectivity, bumpiness) is written
as a conventional value rather than pretended to be recovered.

### Units: refusal, not a default

Writing a scene whose `units` are `UNSPECIFIED` **fails** with a `JtWriteException` naming
`JT_PROP_MEASUREMENT_UNITS`. Units are explicit in this model by doctrine (issue #1 rule 4): a
writer that silently picked millimeters would make every consumer's scale a guess. The value is
written capitalized (`"Millimeters"`), matching both fixtures and the spec's own note that
producers do so and readers must accept either case; it sits on the partition node, where
`readScene` finds it.

### What the writer refuses, and why it is a read-side question

`readScene` *collapses* structure (pass-through children are spliced out, a sole unnamed
transform-free child is absorbed into its parent, geometry lands on named part nodes) and puts
**one body on one node**. Three scene shapes therefore have no faithful pre-image, and the
writer refuses them by naming the path instead of writing a file that reads back differently:

1. a node carrying **geometry and children** — the collapse only ever puts geometry on a node
   whose children it absorbed, so re-reading would hang the geometry on an extra unnamed child;
2. a child the collapse would remove: **unnamed + identity transform + no material + no
   geometry** (spliced out), or a **sole** unnamed identity-transform child (absorbed);
3. a node carrying **meshes and polyline sets** (issue #13) — that is two bodies on one node,
   and the only LSG shape for it is a tier holding a tri-strip shape next to a polyline shape,
   which reads back as two sibling nodes.

Both are checks against `readScene`'s exact rules, not conservative guesses. Lifting them is a
Layer 2 *read-side* extension (a rule that hoists a geometry-only child onto its parent), so
they are recorded as a deferral rather than worked around in the writer.

### The LSG the writer emits

Partition node (root: name atom, units property, the scene root's transform/material, world
bounding box, summed vertex/polygon counts and surface area, `partitionFlags = 0` with no
untransformed box — the simplest legal form of Figure 23 — and an empty file name) → per scene
child either its definition directly or, when the scene shares a node by identity, an
**Instance Node** per reference (Figure 27; carrying no attributes, it is transparent to
`readScene`'s walk, so the shared node comes back as one shared scene object — the NIST hex nut's
ten placements round-trip as ten instances of one part). A node with children becomes a Group
Node; a node with geometry becomes a Part Node → Range LOD Node (empty range limits and centre,
as NX writes them) → one child per LOD tier, finest first: the tier's Tri-Strip Set Shape Node
and/or Polyline Set Shape Node, wrapped in a Group Node only when a tier holds both. Each shape
node declares its own bounding box, exact vertex/polygon count ranges — where "vertex count" is
the **primitive-consumed** count (sum of line lengths for polylines, strip-consumed vertices for
tri-strips), not the unique record count: the NIST producer's own polyline nodes declare 58 where
the segment holds 36 unique records and the line-vertex sum is 58, so Figure 37's "count that can
be achieved" means vertices the primitives consume — and its surface area (its
Figure 36 `Size` stays 0 — the field is the element's *in-memory* size, which the spec says is
unrelated to the on-disk size, and 0 is its documented "unknown"), and points at its Shape LOD
segment through a Late Loaded Property Atom
keyed `JT_LLPROP_SHAPEIMPL` with segment type `7 + tier` (Table 6: LOD0…LOD9, so more than ten
tiers is refused). Names are written as plain `JT_PROP_NAME` strings — the encoded
`Name;version;instance:` form is *read* but not synthesized, and plain values pass the reader's
regex through verbatim. Key and value atoms are interned, as producers do.

### Acceptance (what is committed)

`WriteJtTest` (commonTest — runs on JVM *and* JS): the unit cube, a two-part assembly with a
shared instanced part, materials, transforms and millimeter units, multi-tier LODs, a polyline
part, a mesh without normals, a structure-only scene, both byte orders, determinism, the header
and TOC layouts, the authored topology inspected at Layer 1, and every refusal. Every case
asserts the Layer 0/1 standards on the written file: parses with **zero notes**, re-serializes
**byte-identically**, and its shape bodies decode typed (which is what proves the stored hashes).
`WriteFixtureRewriteTest` (jvmTest) does the same for every *discovered* fixture — both tiers,
both LOD policies: `readScene(writeJt(readScene(f)))` equals `readScene(f)`, including the
instancing structure (identical path sets), with a segment-count inventory check
(1 + one shape segment per mesh/polyline tier).

### Golden candidates, not goldens

`GoldenCandidateWriterTest` stages `golden-candidates/` (gitignored except its README): the
synthetic scenes plus a rewrite of every discovered fixture at both LOD policies, each with an
inventory dump. Per the fixture-policy amendment on issue #1 these freeze as committed goldens
only after an external consumer (JT2Go) opens them — the README lists exactly what to check,
including the cover-face question, since a reader that ignored the Vertex Flags convention would
show doubled inward-facing triangles.

## Layer 1: Meta data and PMI (issue #9)

**The fixture inventory that decided this package.** The 9.5 file carries **no §11 segments at
all** — no Meta Data, no PMI Data — so every layout below rests on the NIST 10.5 file, which
carries 44: **30 Meta Data segments** (Table 6 type 4, each holding exactly one *Property Proxy
Meta Data Element*, all referenced by a `JT_LLPROP_METADATA` late-loaded atom on a Meta Data
Node or Part Node) and **14 PMI Data segments** (type 3, each holding exactly one *PMI Manager
Meta Data Element*, all referenced by `JT_LLPROP_PMI`). All 44 are LZMA-compressed and all 44
end, after the end-of-elements marker, in the same six-byte empty Property Table (Figure 78)
the shape segments carry — a third producer/segment-kind confirmation of that identification.

**One model for two segment kinds.** §11 is titled *Meta Data Segment*, and Annex A lists both
element types under segment type 4 — but NX writes its PMI Managers into type-3 *PMI Data*
segments, which Annex H deprecates while stating that "a PMI Data Segment should be treated
exactly the same as a PMI Manager Meta Data Element". `MetaDataDocument`
(`de.haumacher.kotlinjt.meta`) therefore serves both kinds: element list + Figure-78 property
table + preserved remainder, the same seam as `LsgDocument` and `ShapeLodDocument`, with
`decode` → `encode` byte-identical for decoded and opaquely carried elements alike.

**The property bag** (`PropertyProxyMetaDataElement`, Figure 108) is an *ordered list* of
`MetaProperty(key, value)` — duplicate keys are legal on the wire and preserved; `propertyMap`
is a lookup projection (first wins), never the storage. `MetaPropertyValue` is a sealed
hierarchy over Table 53: `None` (type 0), `Text`, `Integer`, `Real`, `Date` and
`Unrecognized`. `JtDate` (Figure 109) stores the six raw I16 fields — `commonMain` is
platform-free and the spec fixes no calendar, zone or validity rules for them. A **value type
outside Table 53 has unknown length**, so the bag cannot continue past it: the property list
keeps everything decoded so far, the offending property carries `Unrecognized(typeCode,
remainder)` with *every* remaining byte of the body, and `META_PROPERTY_VALUE_TYPE_UNKNOWN`
names it. That is the one deliberate exception to "no half-decoded element" in this package —
re-serialization stays a projection (fields + verbatim bytes), and refusing the whole element
would throw away the keys a real file does describe.

**The PMI Manager** (`PmiManagerMetaDataElement`, Figure 110) decodes every sub-collection
Figure 110 documents up to and including the font block: Design Group Entities with all three
Table-54 attribute value types (Figures 111/112), Associations with Table 55's packed
source/destination words exposed as bit-field accessors (Figure 113), User Attributes
(Figure 114), the String Table every String ID indexes into (Figure 115), Model Views with
their PMI Properties (Figures 116–118), Generic PMI Entities with the whole 2D stack — base
data, 2D-reference frame, 2D text, text box, text polylines, non-text polylines (Figures
119–128) — PMI Polygon Data (Figure 130), the CAD Tags Flag with PMI CAD Tag Data (Figure 129)
and per-font name / character set / glyph outlines. What follows the fonts is **carried
verbatim** (delta 33). Compressed CAD Tag Data (Figure 154) has its framing decoded and its
entropy-coded tag vectors kept as bytes: reading them needs the Int64 CDP, which no structure
has required yet (see the deferral table) — the collection's own Data Length makes the extent
exact, so nothing is guessed and nothing is lost.

**Why the undocumented tail cannot silently hide a misread.** Absorbing trailing bytes removes
the strict full-consumption check that protects every other Layer 1 decode, so the PMI Manager
decode *validates values* instead: every String ID must be −1 or a real index into the String
Table; every Hidden Flag must be a Table 59 value; PMI Polygon Data's parallel vectors must
have exactly 3 and 1 entries per non-empty element; and the CAD Tag Index Count must equal the
§11.2.7 formula's sum (design groups + model views + generic entities in the v10 wire — exact
in all 14 bodies). Any of these failing refuses the typed decode. Deliberately *not* validated:
Table 55 entity types, Table 56 reason codes and Table 60 entity/parent types — the fixture
writes values those tables omit (entity types `0x0310` and `0x030C`, source type 23), so the
value sets are open and the numbers are carried as read.

**Generation policy.** The Property Proxy element decodes in all three generations: the v9.5
reference's own Figure 134 shows the identical layout with only the version field's width
differing (delta 6), so v9 is spec-derived from *its own generation's spec*, not derived from
v10. The PMI Manager's v9 layout is a genuinely different structure (the v9.5 Figure 136 adds a
PMI Version Number and a reserved field, gates sub-collections on it, and lists per-entity-type
collections v10 dropped) and no v9 fixture carries one, so it is opaque-by-policy in V9 with
`ELEMENT_LAYOUT_UNVERIFIED` — never guessed. Between V10 and V10_5 only the Hidden Flag width
differs (delta 32); 10.0–10.4 keep the documented layout, as with the LSG deltas 23–26.

**Layer 1 only.** No scene-façade change: `readScene` neither reads nor is affected by §11
(pinned by `MetaDataProbeTest.theSceneFacadeIsUnaffectedByTypedMetaData`), and `writeJt` still
emits no meta data segments. Both are recorded deferrals with their conditions.

## v10.5 meta data / PMI wire format (established, with byte evidence from the NIST fixture)

Continuing the delta numbering. As with the shape deltas, no 10.0 fixture exists, so
"10.5 delta" and "10.0 documentation gap" cannot be distinguished; each entry says which
reading the evidence supports.

32. **Key PMI Property Atom: the Hidden Flag is one byte, not Figure 118's U32.** Evidence:
    every PMI property of the NIST fixture (Model View and Generic PMI Entity properties across
    all 14 managers) parses to exact length only with a U8 flag; read as U32 the following
    MbString's character count comes out as 0x00000A00. Both flag values occur (a Model View's
    `GeneralAttribute[0]` value atom is hidden, its key is not), so the field is a flag and not
    padding. 10.0–10.4 keep the documented U32; a producer that disagrees falls to the
    validation above (opaque carry + named note), never to a misread.
33. **PMI Manager bodies carry a block after the font loop that Figure 110 does not describe.**
    Evidence: all 14 bodies leave bytes after the fonts — exactly 16 in the nine PMI-light ones
    (four zero U32s) and 1 873 / 4 633 / 7 567 / 9 011 / 9 494 in the five that carry fonts.
    Figure 110 places `U32 Property Count` + PMI Properties + PMI Model View Sort Orders there.
    The first two U32s are zero in all 14, which is consistent with both counts being zero —
    but the *rest* of the block is a structure neither the 10.0 nor the 9.5 reference documents
    (in the large bodies it holds 20-byte records shaped exactly like PMI Associations, and
    ascending index arrays sized neither like the entity nor the string counts), so the
    boundary is not independently pinned. Reading the two counts would be a claim the bytes
    cannot confirm; the whole block is therefore carried verbatim in
    `PmiManagerMetaDataElement.undocumentedTail` with `PMI_MANAGER_TAIL_UNDOCUMENTED`. Its
    time comes with a fixture whose Property Count or Sort Order Count is non-zero, or with
    documentation of the trailing structure (see the deferral table).
34. **Compressed CAD Tag Data's Data Length counts from the Data Length field itself.**
    Figure 154 says only that a reader "may use this information to compute the end position".
    Evidence: in all 14 bodies `offset(Data Length) + Data Length` lands exactly on the next
    field (the U32 Font Count) — e.g. a 58-byte length covering the field, the inner I32
    version and 50 bytes of coded vectors. Counting from the collection start or from after the
    field misses by 1 and 4 bytes respectively.
35. **Figure 120's unlabeled last box is Non-Text Polyline Data.** The reference draws a fourth,
    empty rounded box at the end of PMI 2D Data; §11.2.6.1.3 places Non-Text Polyline Data
    inside PMI 2D Data without saying where, and the bytes settle it: every Generic PMI Entity
    of the fixture carries four index/type/width/coordinate arrays exactly there.
36. **Text Polyline Data's `VecF32 Polyline Vertex Coords` is unconditional.** Figure 126 gates
    only the *index* loop on `Polyline Segment Index Count > 0`. Evidence: the fixture's 2D Text
    Data records are a fixed 48 bytes when a text has no polylines — 40 bytes of scalars plus
    *two* zero counts — so the coordinate vector is written even when empty (verified across
    every text entity of all 14 bodies; the largest body's 17 texts of one dimension are spaced
    exactly 48 bytes apart).

Recorded observations from the same evidence:

- **NX 10.5 writes PMI into deprecated type-3 segments.** Annex H says PMI Data Segments
  "should not be written to JT files"; the current Siemens writer writes 14 of them, all
  referenced by `JT_LLPROP_PMI` atoms declaring segment type 3. Reading broadly means serving
  both kinds (issue #1's version policy).
- **PMI Manager version 2 is what NX 10.5 writes** (Figure 110's U8 Version Number); the v9.5
  reference names 1 and 2 as the valid values for its own generation's I16 field.
- **2D-Frame Flag 2 does not mean a dummy frame in practice.** §11.2.6.1.1 says flag 2 means
  "dummy (i.e. all zeros) 2D-Reference Frame data is written"; every Generic PMI Entity of the
  fixture writes flag 2, and the dimension entities' frames are fully populated. The flag is
  preserved, not interpreted. Quantified by the probe review (PmiWorldProbeTest): 206 of the
  403 frames are the spec's all-zero dummies, 197 are populated — under the same flag value —
  and every populated frame sits inside the partition's world box with non-degenerate axes.
- **Model View cameras are geometrically coherent but carry no viewport diameter**: all 89
  views write `viewportDiameter = 0.0` (unset), while eye direction equals the normalized
  eye→target vector on every view and the named standard views ("Top", "Front", "Right"…)
  carry exactly the axis-aligned directions their names promise (PmiWorldProbeTest) — the
  strongest fixture evidence that Figure 116 decodes at the right offsets.
- **Font glyph counts match their character sets exactly** — all five fonts define one
  PolygonData element per character identifier (19, 23, 24, 36 and 44), which is what the
  fixture battery asserts.
- **What the 30 property bags actually contain**: 151 properties over 21 distinct keys, 148 of
  them `Text` and 3 `Integer` — `Name::`, `LAYER::`, `CAD Source::`, `CAD_DENSITY::`,
  `CAD_MATERIAL::`, `CAD_MASS_UNITS::`, `CAD_VOLUME::`, `CAD_YOUNGS_MODULUS::`,
  `Translator Version::`, `JT_PROP_MEASUREMENT_UNITS::`, `AdvCompressLODLevel::`,
  `LAYERFILTER000::`…`006::`, `PMI_TYPE_TABLE`, `TOOLKIT_CUSTOMER` and
  `__PLM_PS_OCC_RelRoot` (the one Integer key, value 1). No `Date` and no type-0 value occurs
  in either fixture, so those two Table 53 rows are exercised only by the hand-built per-figure
  test — recorded honestly rather than claimed fixture-verified.

## §8–§10: wireframe reads, B-rep stays sealed, nothing unnamed (issue #10)

**What this package settled.** After §11 three ledger blocks were still `—` and nine segments of
the NIST fixture were anonymous. All of it now has a final fate: **§10 Wireframe decodes typed**
against five real bodies, **§9 LWPA decodes spec-derived** because its figures leave nothing to
infer, **§8 B-rep is `opaque` with a committed proof** instead of a claim, and the two segment
types **Table 6 does not define at all** are named, looked into and preserved verbatim.

### §10 Wireframe: what the five NIST bodies actually contain

`de.haumacher.kotlinjt.wireframe` (`WireframeDocument` + `WireframeRepElement`) follows the seam
of `LsgDocument` / `ShapeLodDocument` / `MetaDataDocument`: element list, the Figure-78 property
table (empty in all five), preserved remainder, and `decode` → `encode` byte-identical. All five
segments decode **typed with zero notes**.

| body (inflated) | edges | MCS curves | degrees | rational control points | stored knot values | CAD tags |
|---|---|---|---|---|---|---|
| 810 B | 22 | 22 | 1, 2 | 12 | 0 | 22 |
| 1 066 B | 31 | 31 | 1 | 0 | 2 (1 curve, type 2) | 31 |
| 1 625 B | 56 | 56 | 1, 2, 4 | 40 | 12 (2 curves, type 2) | 56 |
| 1 701 B | 52 | 52 | 1, 2, 4 | 52 | 12 (2 curves, type 2) | 52 |
| 2 452 B | 36 | 36 | 1, 2, 3, 4 | 100 | 64 (4 type-0 + 8 type-2 curves) | 36 |

197 edges over 197 NURBS curves with 197 CAD tags — every tag a Table-72 **type 1** (32-bit)
value, every `Edge Tag Counter` 0, every edge tag and MCS curve index the identity sequence
`0 … n−1`. The curves are the section and reference curves of the NIST assembly's drilled and
filleted parts: degree-1 straight lines, degree-2 rational arcs (weights 0.4142 = tan 22.5°,
0.5046/0.4954 — circle-arc weights), a few degree-3/4 splines. Coordinates are lossless `F64`.

**Why the layout is *established* and not merely plausible.** Exact byte consumption on five
bodies would already be strong; four independent count identities make it exact, and the decoder
enforces all four (a violation refuses to an opaque carry, never a misread):

1. `Edge Count` == length of both `VecI32` vectors, and every MCS curve index is `< MCS Curve Count`;
2. the control point vector holds **exactly three coordinates per control point** — rational
   curves store *non-homogeneous* coordinates, so dimensionality 3 and 4 store equally many;
3. `Weights Count` == the summed control point count of the **rational** curves (dimensionality 4,
   Table 71) — 204 of them across the five bodies, all with a stored weight;
4. the knot vector holds exactly the number of values §12.1.13's own `switch` predicts for the
   curves listed per Table-68 category — 90 values across the five bodies, and the two categories
   the fixture uses (0 and 2) are both exercised.

**What is decoded and what is projected.** Every wire field is preserved (packets keep their codec
byte, CodeText, probability-context bytes and nested packets), so `encode` is a projection.
`NurbsCurve` derives per curve: degree, control point coordinates, weights (1.0 where the wire
stores none, per §12.1.14) and the **stored** knot values with their Table-68 category. Assembling
the *full* knot vector is deliberately not done: §12.1.13 prints the interior-filling step of its
reconstruction sketch only for the `[x1:x2]` categories, and for a **trivial** knot vector — the
majority in the fixture — the reference gives only the two defining cases and no reconstruction at
all. A full vector would be part inference; the deferral names its condition instead.

### Where the shared §12 collections live

`de.haumacher.kotlinjt.encoding` holds the §12 collections that no §7 structure needed:
`Int64Cdp` (Figures 135–137), `CompressedCurveData` (Figures 148–153) and `CompressedCadTagData`
(Figure 154, now decoded). `Int32Cdp` stays in `shape`, where it landed with issue #4 — moving it
would churn every shape file for no behavioral gain, and the two packages share the module's
`internal` bit readers and the arithmetic decoder core either way. That core was refactored, not
duplicated: `decodeArithmeticSymbolIndices` returns the *index* of the probability-context entry
each symbol selected, and the Int32 and Int64 layers map indices to values — which is exactly what
§12.1.2 promises ("Int64CDP shares the same encoding and compression logic as Int32CDP").

`Int64Cdp` is fixture-verified for the arithmetic (12 packets, 8 with an escape), bitlength (2) and
move-to-front (1) codecs; its null and chopper forms are spec-derived, their layout fixed by
Figure 135 exactly as the fixture-verified Int32 forms.

### The two 9.5-only packet families, and the dispatch rule that has no wire signal (package P8)

`encoding.Int32CdpMk1` (9.5 §8.1.1, Figure 218) and `encoding.Float64Cdp` (§8.1.3, Figure 224)
close the last two codec gaps of the JT 9 generation. Together they gate every JT 9 B-Rep topology
stream, the §8.1.13–§8.1.15 NURBS machinery under the JT 9 wireframe rep, and the ULP `params1`
codes — all of which stay opaque for reasons that are *not* the codecs; see below.

**The dispatch rule is the design constraint.** 9.5 pp. 19–20 define the figure notation
`Int32CDP` = §8.1.1 Mk. 1 and `Int32CDP2` = §8.1.2 Mk. 2, **per field**, and there is no tag, no
length, no magic byte that separates the two on the wire. So the reading *call site* chooses, and
the API is built to make choosing unavoidable: `Int32CdpMk1` and `shape.Int32Cdp` are unrelated
types with no common supertype, no auto-detecting entry point, and no default. Where a collection
is generation-independent in shape but not in content — the NURBS figures, box for box identical
in 9.5 and v10 — the collection is modelled once against the `IntVectorField` /
`DoubleVectorField` interfaces and read through `read` (v10) or `read95` (9.5). A caller that
guesses is a bug, not a leniency, which is why the acceptance for this package is not only "both
decode" but "each refuses the other's bytes by name".

**What the two packets are.** Mk. 1 is a different packet from Mk. 2 end to end, not a variant:
`U8 CODEC Type` first with **no Value Count** (so no empty-packet form), a probability-context
*list* of one or two tables with a `U8` count and a per-entry `Next Context`, out-of-band data
gated on an explicit `I32 Out-Of-Band Value Count > 0`, `CodeText Length` and `Value Element Count`
*after* the out-of-band packet, an `I32 Symbol Count` when two tables are in play, and a real
`VecU32` CodeText carrying its own length word. Its arithmetic driver is a state machine: the
context of the next symbol is the entry's `Next Context`, and an escape emits an out-of-band value
**only while table 0 is in use** — which is why symbol count and value count are separate fields.
Its bitlength codec is a prefix-code walk with `cStepBits = 2` (Appendix C §2.1), unrelated to the
Mk. 2 bitlength grammar of §2.2.

Float64CDP is not `Int64CDP` renamed. Its symbols are natively `F64` (the context stores an
`F64 Associated Value`, so there is no bit-reinterpretation step), it carries `F64 Value Range
Min`/`Max` on the wire, its out-of-band array is **always** a raw `VecF64` — "*the Float64
Compressed Data Packet simply writes out the 'out-of-band data' array with no additional encoding
attempted*" (p.263) — its contexts are plain byte-aligned `I32` counts with flat 20-byte entries
(symbol *unbiased*, so the escape is literally `−2`; plus an `I32 Reserved Field` carried verbatim
per §9.3), and it has neither chopper nor move-to-front.

**Where the document runs out, the reader refuses by name.** Four places, all recorded rather than
guessed:

- a Mk. 1 packet declaring CODEC 4 (Chopper) — Figure 218's table lists it, but the figure draws no
  chopper fields and §8.1.2 introduces the Chopper as what *Mk. 2* brings to the table (p.258);
- a Float64 packet declaring CODEC 1 (Bitlength) — Appendix C §2.1's codec is `Int32`-valued and
  §8.1.3 defines no 64-bit form and no reinterpretation step — or CODEC 4, for the same reason as
  Mk. 1;
- a Float64 packet with **two** probability context tables *and* a live CodeText: Figure 224 writes
  a Symbol Count for that case and quotes the same escape-in-table-0 subtlety, but Figure 226 has
  no `Next Context` field, so there is nothing to switch tables with. With `CodeText Length = 0`
  (all values out of band) the same packet stays readable, because no symbol is decoded at all.

**Two readings that are inference, not citation**, and are marked as such in the code:

1. *A Mk. 1 escape met in a non-zero context emits no value.* §8.1.1 says only what it does **not**
   do ("Only if the Codec is using Probability Context Table 0 … does it emit a Value from the
   'Out-Of-Band' data array"), never what it does instead; emitting nothing is the only reading
   under which its own next sentence — "the number of Symbols decoded can be larger than the number
   of Values produced" — is true. The safety net is the mandatory `values.size == Value Element
   Count` check: a wrong reading of a real two-table packet fails loudly instead of producing a
   plausible list.
2. *A Float64 Null-CODEC packet packs each value into two CodeText words, low-order word first.*
   §8.1.3 never says. The two-words-per-value part is forced by the framing (a Null packet has no
   Value Element Count at all, so the `VecU32` count is the only thing that can give the value
   count); the word order is taken from v10 §12.1.2, this packet's own successor, and is
   unobservable in a little-endian file.

**The evidence, stated honestly.** No fixture in the corpus carries either packet — none of the
three files has a JT B-Rep, a ULP or a JT 9 wireframe segment — so every ledger row this package
flips is `spec`, never `spec+fixture`. Ranked by strength:

- the *framing* of both packets, the Mk. 1 context list and the Mk. 1 bitlength grammar come from
  the figures and from Appendix C's decoder source, read off pages rendered with `pdftoppm`
  (`pdftotext` loses the branch structure of Figures 218/219/224);
- the *arithmetic driver* is the fixture-verified core, and `CompressedDataPacketFixtureTest`
  makes that concrete: it walks the corpus, re-frames **663 real arithmetic Mk. 2 packets**
  (152 + 96 + 415 across the three fixtures) into the Mk. 1 layout around their own CodeText and
  histogram, and requires identical values and a byte-identical re-encode. Real entropy-coded data,
  no producer of ours;
- the same test offers all **684** Mk. 2 packets to the Mk. 1 reader in their own framing: every
  single one is refused with a named `JtFormatException` — the cross-generation hazard measured
  rather than asserted;
- CodeText produced by the tests' own arithmetic encoder (`CdpTestSupport`) backs only the
  multi-context and Float64 decodes, and proves self-consistency, nothing more. It is pinned
  against the fixture-verified decoder first so a bug in it cannot pass as a bug elsewhere;
- `CompressedDataPacketFixtureTest`'s first battery is the corpus hook: it skips *visibly* today and
  fires the day a fixture with one of those segment kinds arrives.

**What was wired up, and what deliberately was not.** The §8.1.13/§8.1.14/§8.1.15 collections (with
§8.1.15.1/2/3) now read in both generations — `CompressedCurveData.read95` and friends — because
those are §8 collections whose layout the document fixes completely. The *elements* that contain
them stay opaque, and their notes stay accurate: the JT 9 Wireframe Rep Element (Figure 130) has an
unestablished surrounding layout, the JT B-Rep topology streams are §7.2.3.1 work, and the ULP
`params1` collection needs §7.2.2's framing. Decoding a packet is not decoding the element that
contains one; opening those is the next package's job, and it now has its codecs.

### §9 LWPA: decoded in both generations, spec-derived, and honest about it

No fixture carries a JT LWPA segment — **in either generation**, which is the defining constraint
of this whole area. `de.haumacher.kotlinjt.lwpa` decodes it anyway because the figures leave
nothing to infer: the two `VecI32` vectors have their length fixed by `Analytic Surface Count`, and
the four `VecF64` arrays are plain count-plus-values vectors written "in binary form" — no
quantizer, no predictor, no hash, no conditional. Surface types are validated against the
*Supported Surface Type* set (0 Nurbs, 1 Plane, 2 Cylinder, 3 Cone, 4 Sphere, 5 Torus, 6/7
reserved — v10's Table 100, and 9.5's value-identical unnumbered twin on p.210), and the element
body must consume to its declared length, so a producer that contradicts the derivation gets an
opaque carry with a named note.

**The JT 9 generation decodes too, as of package P7** (delta 47). It used to be
"opaque-by-policy", and the policy rested on a false statement — that the v9.5 reference lists
segment type 24 in its Table 3 but documents no LWPA *element*. It documents one: §7.2.9.1,
Figure 215, Annex A Table 11. The reader dispatches on `LsgGeneration` for the `I16`-vs-`U8`
version, the `I32`-vs-`U32` counts and the `Int32CDP2`-vs-`Int32CDP` packet; the writer emits back
the dialect the document was read in, so re-serialization stays byte-exact. Nothing on this path
needs `Float64CDP` or the Mk. 1 Int32 packet.

**How a spec-only decode is held to account.** With no fixture, round-trip on hand-built frames is
the strongest proof available, and it is applied to *every* frame the tests build, including both
states of the one `Analytic Surface Count > 0` guard. Beyond byte counts the tests assert meaning:
that the `I16` version reads as one number rather than a reinterpreted byte pair (a frame carrying
version `0x0101`, whose two equal bytes make the byte order irrelevant), that the same bytes the
JT 9 reader accepts are *refused by name* by the v10 reader and vice versa, that `Lag1` is applied
and not merely recorded (five surfaces, because the predictor only bites past four primers), and
that a body one byte short or one byte long earns `ELEMENT_DECODE_FAILED` and an exact opaque carry
rather than an exception out of the API.

The Analytic Surface Creation flow chart — v10 Figure 102, 9.5 Figure 217, the same chart box for
box — says how many numbers each surface type consumes from the four arrays; building that
projection waits for a consumer.

### §8: opacity proven, not declared

`BrepOpacityTest` (jvmTest) is the mechanism that turns issue #1's third rule into a tested
property. Per fixture it asserts, for **every** segment of Table 6 types 2, 17, 20, 30 and 32:

- the type is *labelled* (never "UNKNOWN"), its compression fields are read, its element data
  decompresses, and its element list is terminated and ends in the Figure-78 empty property table;
- the framed element's Object Type ID is a **named** type and the one its segment kind promises —
  which required adding `ObjectTypeIds.MULTI_XT_BREP_ELEMENT`, because Annex A lists no element
  type for segment type 30 at all while Annex F §F.1.3 gives the GUID (a second inconsistency of
  the same kind as the Vector4f atom);
- the element frames reconstruct the decompressed element data **byte for byte**, and the whole
  file re-serializes byte-identically;
- the payload really is Parasolid's own world: the XT transmit file's `TRANSMIT FILE` banner sits
  inside it;
- every segment is reachable from the LSG through the late-loaded key its type promises
  (`JT_LLPROP_XTBREP`, `JT_LLPROP_MULTIXTBREP`).

On the NIST fixture: **8 XT B-Rep + 1 MultiXT B-Rep, 583 617 payload bytes preserved, 1 723 645
bytes of decompressed element data, 8 of the 9 carrying a Parasolid transmit file** (the ninth is
a 74-byte XT element with no transmit file — an empty body). JT B-Rep (deprecated), ULP and STEP
B-rep have no fixture; the suite covers their kinds and skips *visibly*. Their ledger fate is
`opaque`, never `n/a`: the doctrine, not the absence of data, is what keeps them sealed — and the
condition for reading them is a doctrine reversal, which belongs to the user, not to a package.

### Segment types 23 and 31: named citizens, uninterpreted bodies

`SegmentKind` stays exactly what its doc comment says — Table 6. The undefined codes get their own
documented home, `UndefinedSegmentTypes`, and the inventory now prints **"undefined type 23"**
instead of "UNKNOWN" (both in `inventory()` and in the sidecar JSON's `typeName`). The
`UNKNOWN_SEGMENT_TYPE` note stays: the type really is undefined.

Layer 0 additionally *looks inside* them. `probeUndefinedType` accepts a payload only when every
check passes — a well-formed Table-8/9 flag and algorithm, a declared length filling the payload
exactly, a codec that decodes, and a result whose first element list is properly terminated — and
is silent when any fails (the segment then stays as opaque as before; a failed probe adds no
information the named note does not already carry). This is verification, not guessing, and
losslessness is untouched because re-serialization always emits the raw payload. Both NX types
pass, which is why nine segments that used to show `compression: null, elementLists: null` now show
their real framing in the committed sidecar.

What the bytes prove, and nothing more (`UndefinedSegmentTypeTest`):

- **Type 23** — eight segments, 4 489–58 933 payload bytes (8 258–130 383 inflated), all LZMA.
  Each frames exactly **one** element of type `{CA7E6F89-97C8-47F0-9FCA-16990CFBE217}` — a GUID no
  table of either reference contains — with Object Base Type 9 and object id 0, followed by the
  end marker and the six-byte empty property table. In the LSG each is referenced by a Late Loaded
  Property Atom keyed **`JT_LLPROP_FERIT`** declaring segment type 23, sitting on exactly the eight
  Part Nodes that also carry `JT_LLPROP_XTBREP` — so a type-23 segment accompanies XT B-Rep, and
  the test pins that pairing so a future fixture can break it loudly. Body structure, recorded as
  observation only: `U8 1`, then `I32 1`, `I32 2`, `I32 2`, then four `I32`s of which the last two
  stand in an exact 2:1 ratio in all eight bodies (e.g. 22, 26, 90, 45 / 348, 438, 1 612, 806),
  then Int32CDP packets. **No semantics are assigned and the body is not decoded** — the element
  GUID is in no documented figure, which is the condition the brief set for decoding it.
- **Type 31** — one segment, 341 payload bytes (984 inflated), LZMA. It frames **14 String
  Property Atom Elements** (Figure 71, Annex A; base type 5, version 2, state flags `0x40000000`)
  forming seven key/value pairs that name the producing tool chain:
  `JT_PROP_JTOPENTOOLKIT_BUILD` = 200825C, `JT_PROP_XT_TOOLKIT_VERSION` = 33.0.89,
  `JT_PROP_JTOPENTOOLKIT_VERSION` = 10.8.0.0, `JT_PROP_DIRECTMODEL_BUILD` = 922218e,
  `JT_PROP_PARASOLID_VERSION` = 33.0.171, `JT_PROP_DIRECTMODEL_VERSION` = 9.8.0.0,
  `JT_PROP_BODYSHOP_VERSION` = 33.0.93. **No** late-loaded atom references it; the segment stands
  alone in the TOC. Its element type is fully documented, but what the *segment type* is for is
  not, so the payload is preserved verbatim and no typed document is built for it.

### Layer 1 only

No scene-façade change: `readScene` neither reads nor is affected by §8–§10, and `writeJt` emits
no wireframe, LWPA or precise-geometry segments. Both are recorded deferrals with their conditions.

## §10 / §12 wire format as NX 10.5 writes it (established, with byte evidence from the NIST fixture)

Continuing the delta numbering.

37. **The Arithmetic CODEC's out-of-band data has two wire forms, and Figure 132/135 says which
    applies: a nested packet when the segment is *not* externally compressed, an `I32` count plus
    plain values when it is.** Both figures draw the branch ("Segment is externally compressed" /
    "Segment is not externally compressed"); nothing in the surrounding prose mentions it, and the
    §7 package never met the compressed side because Shape LOD segments are Table-6
    non-compressible. Evidence: the Wireframe Rep bodies sit inside LZMA segments and every
    escape-carrying arithmetic packet in them is followed by a count plus raw `I32`/`I64` values —
    e.g. the control points' move-to-front offset packet, whose 19-byte context is followed by
    `12 00 00 00` and 18 plain `I64`s. Reading a nested packet there yields a chopper with 0 chop
    bits and desynchronizes immediately. This *generalizes* delta 28 rather than replacing it: the
    presence rule (out-of-band data exists exactly when the context carries an escape entry) holds
    in both forms — the fixture's escapeless arithmetic packets are followed directly by the next
    wire field on both sides of the branch. `Int32Cdp.readV10` and `Int64Cdp.read` therefore take
    the segment's actual compression state, and the model records which form it read
    (`Int32OutOfBand` / `Int64OutOfBand`) so re-serialization stays a projection.
    *Added by package P8 (9.5 finding 11), so the rule is never generalized backwards:* **9.5 has
    neither branch.** Its Mk. 2 packet always nests the out-of-band packet (Figure 221 draws it
    unconditionally under the Arithmetic branch), its Mk. 1 packet always writes an
    `I32 Out-Of-Band Value Count` and nests only when that count is positive (Figure 218), and its
    Float64 packet always writes a count plus a raw `VecF64` (Figure 224) — three conventions, none
    of them conditioned on the enclosing segment's compression state, which §8 never mentions.
    `externallyCompressed` therefore exists on `readV10` and `Int64Cdp.read` only, and correctly
    does not exist on `Int32Cdp.read`, `Int32CdpMk1.read` or `Float64Cdp.read`.
38. **Figure 104's Version Number box says `I16`; the field is one byte.** §10.1's own field
    description says `U8`, and the bytes side with the prose: all five bodies parse to exact length
    only with one version byte (`01`), and with two the Edge Count misaligns. Recorded as a figure
    error rather than a version delta, because the v9.5 reference's Figure 130 really does show
    `I16` — the field narrowed with the generation, exactly as delta 6 describes for the LSG, and
    the v10 figure was simply not updated.
39. **Revision B's own errata is the authority on the two index vectors' predictor.** The "What's
    New" list records: *"Corrections made in Figure 104 — Wireframe Rep Element data collection,
    Lag1 is replaced by NULL in two places."* Those two places are `MCS Curve Indices` and
    `Edge Tags`. Verified: under NULL both decode to the identity sequence `0 … n−1` in all five
    bodies; under Lag1 they would accumulate into nonsense (and the curve indices would leave the
    `[0, MCS Curve Count)` range the decoder validates). The JT 9 generation does use Lag1.
40. **Compressed CAD Tag Data writes both tag vectors unconditionally, empty-packet where the type
    does not occur.** Figure 154's prose says `CAD Tags Type-1` / `Type-2` are "only present if
    there are Type-1/Type-2 CAD Tags in the CAD Tag Types vector". Evidence: all five wireframe
    bodies carry only type-1 tags and still write the four zero bytes of an empty Int64 packet —
    without which `offset(Data Length) + Data Length` overshoots the collection by exactly four
    bytes (and the element then fails its full-consumption check). Delta 34's Data Length
    convention is confirmed a second time by the same arithmetic, now on a different segment kind.
41. **A PMI Manager's CAD tag *count* is not its CAD Tag Index Count.** §11.2.7's formula governs
    the *index* list (validated since issue #9, exact in all 14 managers); the number of tags in the
    nested Compressed CAD Tag Data is a different number. Evidence: ten of the fourteen NIST
    managers carry more tags than indices — twice as many in seven of them (44/22, 72/36, 62/31,
    112/56, 104/52, 106/53) and other ratios in the rest (87/49, 54/35, 116/64, 135/58) — which
    matches §12.1.16's own statement that "exactly what CAD entity types have CAD Tags … is defined
    by users of this data collection". Constraining the tag count would refuse ten perfectly
    well-formed bodies; the wireframe side, where §10.1.2 *does* fix the count at one tag per edge,
    keeps its check.

Recorded observations from the same evidence:

- **Annex A omits segment type 30 entirely.** Table 6 defines MultiXT B-Rep and Annex F §F.1.3
  documents its element and GUID, but Table A.1 has no "Types Stored Within MultiXT B-Rep Segment"
  block. `ObjectTypeIds.MULTI_XT_BREP_ELEMENT` carries the GUID with that recorded — the same
  treatment the Vector4f Property Atom got.
- **The Int64 probability context stores its associated values unsigned relative to Min Value**, as
  the Int32 context does, even though Figure 137 labels the field `I64{Number Value Bits}`.
  Evidence: the wireframe control-point contexts use 53–64 value bits with min values that are
  themselves `F64` bit patterns; read signed, half the entries come out as absurd negative
  numbers and the decoded coordinates stop being coordinates. Read unsigned + min, all five
  bodies' control points land inside the model's own extent.
- **The Int64 bitlength CODEC does not nibble its min/max/mean.** Annex B is explicit for the
  64-bit specialization — *"Simply write out all the bits for 64 bit"*, `nibblerGet(Int64&)` being
  a plain `GetUnsignedBits(…, 64)` — and the two bitlength packets of the fixture confirm it.
- **Every wireframe segment is referenced by `JT_LLPROP_WFREP`** declaring segment type 18, on the
  five Part Nodes that own section/reference curves; the pairing is asserted per fixture.

## Fixture conventions (from the amendment on issue #1)

- JVM-only `FixtureDiscoveryTest` auto-discovers `*.jt` in **both tiers** — the committed
  public spine under `fixtures/` (sidecars committed with them) and the IP-encumbered
  `fixtures-local/` (repo root found by walking up to `settings.gradle.kts`/`.git`). Battery
  per file: parse with exactly the sidecar's note names (empty for a healthy file); inventory
  JSON — including per-payload SHA-256 and the LSG element histogram — equal to the sidecar
  `<name>.expected.json` (created on first run for human review, asserted thereafter);
  byte-identical re-serialization; LSG element-stream round-trip; the model-level mutation
  probe (skipping visibly where a stage is not applicable — since issue #6 both fixtures run
  the full battery including the shape-geometry stage; the LOD-descent stage skips visibly
  on the 9.5 file, whose segments are all one tier).
- No fixtures ⇒ one visibly **SKIPPED** test whose name carries the count. Verified.
- Committed code never names a fixture file (customer part numbers). Sidecars live next to
  the fixtures, equally gitignored.
- Committed conformance lives in the synthetic round-trip suite (both byte orders, both
  version generations, hostile variants) — regression pinning, not conformance proof, per the
  "your own writer proves nothing" doctrine.

## Deferred (with the condition for its time)

| What | Its time comes when |
|---|---|
| ~~v10 shape element bodies + v10 Int32CDP wire formats, v10 bitlength/packed-Deering variants~~ | **done** (issue #6, see *Layer 1: v10 shape LOD bodies*): tri-strip + polyline bodies decode typed in v10, all 117 stored hashes verified |
| ~~Int64CDP (Figures 135–137)~~ | **done** (issue #10): landed with §10, its first consumer — arithmetic, bitlength and move-to-front fixture-verified on the wireframe bodies; null and chopper spec-derived |
| XZ SHA-256 block-check verification, non-LZMA2 xz filter chains | first real stream carrying them (today: SHA-256 decodes unverified; foreign filter chains refuse with `UNSUPPORTED_COMPRESSION`) |
| LZMA *encoder* (writer-side segment compression) | a consumer needs v10-writer output smaller than plain storage permits — note Table 8/9 leave *no other* v10 choice: ZLIB is a JT 9 value, so "stored" is the only legal alternative (issue #1 policy: simplest legal encodings) |
| ~~Polyline Set and Point Set Shape LOD bodies in the JT 9 generation~~ | **done** (issue #12, package P3, see *Layer 1: the 9.5 polyline and point set bodies*): the five Polyline Set and one Point Set body of the 9.5 bug fixture decode typed, hash-exact and byte-exact |
| Polygon Set and Primitive Set Shape LOD bodies; the v10 Point Set body | first fixture carrying them. 9.5 defines **no** Polygon Set LOD element at all (its Annex-A table lists six types and that is not one of them), and Figure 97's Primitive Set figure contradicts its own prose about where Texture Coord Gen Type sits — so the primitive set additionally needs a fixture to arbitrate, plus the Mk.-1 `Int32CDP` packet its §7.2.2.2.2 path uses and the library does not implement |
| The auxiliary vertex field *list* of 9.5 Figure 92 (GUID + field type + the type-branched `VecU32{Int32CDP2}` triples + the Auxiliary Data Hash) | the first fixture whose vertex bindings set Table 48 bit 64. The document specifies it; no file in the corpus exercises it, and P3 refuses it by name rather than decode a 46-row type table nothing has ever validated. The *extension point* it hangs off is decoded and typed today |
| Vertex colours, texture coordinates and auxiliary fields in vertex records | first fixture whose bindings declare them (typed decode refuses with a named note today; per-vertex *flags* landed with issue #6 — Table 48 bit 7, all NIST tri-strips) |
| ~~Element body parsing for meta data / PMI segments~~ | **done** (issue #9, see *Layer 1: Meta data and PMI*): all 44 Meta Data / PMI Data segments of the NIST fixture decode typed, byte-identical round-trip, cross-checked against the LSG's late-loaded references |
| The undocumented block NX 10.5 writes after a PMI Manager's fonts (delta 33) — and with it Figure 110's segment-level `Property Count` / PMI Properties and Figure 131's Model View Sort Orders | a fixture whose Property Count or Model View Sort Order Count is non-zero, or documentation of the trailing structure (today: carried verbatim with `PMI_MANAGER_TAIL_UNDOCUMENTED`, never read as something it may not be) |
| ~~The entropy-coded vectors inside Compressed CAD Tag Data (Figure 154)~~ | **done** (issue #10): the condition ("a consumer needs CAD tags") was met by §10's Wireframe Rep CAD Tag Data. All 5 wireframe reps and all 14 PMI managers decode their tag vectors; Type-2 (64-bit) tags stay spec-derived — no fixture writes one. Undecodable vectors fall back to verbatim bytes with `CAD_TAG_VECTORS_UNRECOGNIZED` |
| The v9 PMI Manager layout (v9.5 Figure 136: PMI Version Number, reserved field, per-entity-type collections) | the first v9 fixture carrying a PMI Manager (today: opaque with `ELEMENT_LAYOUT_UNVERIFIED`; the v9 Property Proxy element *does* decode — its v9.5 figure is the v10 layout with an I16 version) |
| Surfacing meta data properties and PMI into the Layer 2 scene | a consumer needs them *and* a decision is made about which conventions the scene interprets (§13.8's CAD/tessellation/PMI property tables) rather than passing raw key/value bags through a format-agnostic model; today they are complete at Layer 1 |
| Authoring §11 segments in `writeJt` (property bags, PMI) | the Layer 2 scene grows the concepts — a file without meta data segments is legal, and the writer emits none today (the read side is `done`, so a written file's bags could be verified against it immediately) |
| v9 layouts of the non-material attribute elements (lights, styles, transform, textures, mappings) | first v9 fixture carrying them (opaque with `ELEMENT_LAYOUT_UNVERIFIED` until then) |
| ~~Property-table *semantics* (units, key naming conventions, §13.8)~~ | **done** (issue #7, see *Layer 2, read side*): JT_PROP_NAME, JT_PROP_MEASUREMENT_UNITS, key visibility convention, late-loaded shape resolution; other conventions (SUBNODE/reference sets, CAD/tessellation properties) stay raw at Layer 1 — their time comes with the first consumer that needs them interpreted |
| ~~`writeJt(scene)` — Layer 2 write side~~ | **done** (issue #8, see *Layer 2, write side*): scene → LSG + shape LOD segments, round-trip-verified on both fixtures; what remains is the external validation (JT2Go opening the staged candidates) before any of it freezes as a golden |
| Vertex-sharing tri-strip topology (a real Annex D encoder) | file size or a consumer demands it: today one component per triangle costs three coordinates per triangle (NIST rewrite 6.1 MB vs. the original 1.6 MB) and no entropy coding at all — correct and boring by policy |
| Entropy-coded Int32CDPs on the write side (bitlength/arithmetic/chopper/MTF) | a consumer needs smaller files than the null CODEC produces; the decoders exist, so an encoder can be validated against them |
| Writing a scene node that carries geometry *and* children, a child the Layer 2 collapse would splice out/absorb, or a node carrying meshes *and* polyline sets | a read-side rule that hoists a geometry-only child onto its parent, resp. a body concept that can be triangles and lines at once (today: a named `JtWriteException`, never a file that reads back differently) |
| A point concept in the Layer 2 scene (`SceneNode.points`) | **the writer can author a Point Set** — i.e. the v10 Point Set LOD body above becomes `done`. The scene is a two-way seam: `readScene` growing points alone would turn today's honest read-side `SCENE_GEOMETRY_UNAVAILABLE` into a hard `JtWriteException` on the one fixture that carries a point set, which round-trips today — a strictly worse trade than the note. The Layer 2 half is an afternoon (`PointSet(positions)` next to `PolylineSet`, one collector branch); it lands *with* the write half, and then the note disappears in both directions at once. Until then the point set of `KR360-1.jt` is named, never silently dropped |
| Writing face groups, per-vertex colours, texture coordinates, PMI, B-rep | the Layer 2 scene grows the concept (face groups are read but the Scene model has no place for them yet) |
| Per-part units precedence (lowest node wins) for mixed-unit files | first real fixture declaring conflicting units (today: `SCENE_UNITS_MIXED` + `UNSPECIFIED`, never a guess) |
| Force/final/field-inhibit attribute accumulation in the scene | first real fixture using them (today: named note `SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED`; both fixtures use plain accumulation) |
| The **full** NURBS knot vector of a wireframe curve (and of a trivial one at all) | a consumer needs to *evaluate* curves. §12.1.13 prints the interior-filling step of its reconstruction sketch only for the `[x1:x2]` Table-68 categories, and for a **trivial** knot vector it gives only the two defining cases and no reconstruction — so a full vector would be part inference. Today the model exposes the stored values with their category, and validates their count against the formula that *is* printed |
| The Analytic Surface Creation projection (v10 Figure 102 == 9.5 Figure 217: turning LWPA's four `VecF64` arrays into planes, cylinders, cones, spheres and tori) | a consumer needs analytic surfaces — **and** the first fixture carrying an LWPA segment, since the whole LWPA decode is spec-derived in both generations today |
| Reading the body of an undefined-type segment (NX 10.5's types 23 and 31) | for **23**: its element GUID appearing in a documented figure, or documentation of the type. For **31**: nothing is blocked — its elements are documented String Property Atoms — but what the *segment type* is for is not, so no typed document claims it. Both are preserved verbatim today, with everything the bytes prove recorded in `UndefinedSegmentTypeTest` |
| Decoding B-rep (JT B-rep, XT, MultiXT, ULP, STEP) | **a doctrine reversal**, not a package: issue #1 rule 3 makes opacity deliberate, and `BrepOpacityTest` proves it is carriage rather than loss. A reversal belongs to the user and would be recorded here with the old rationale quoted |
| The JT 9 generation of the Wireframe Rep Element (v9.5 Figure 130: I16 version, Lag1 index vectors, "Mk. 2" CDPs throughout the curve data) | the first v9 fixture carrying a Wireframe segment (today: opaque with `ELEMENT_LAYOUT_UNVERIFIED`) |
| The UV (parameter-space) dimensionality of Compressed Curve Data (Table 70) | the first consumer of JT B-Rep PCS curves — the decoder already takes it as a parameter, so only the fixture is missing |
| Surfacing wireframe curves into the Layer 2 scene, and authoring §9/§10 segments in `writeJt` | the Scene model grows a curve concept. Today wireframe polylines *do* reach the scene (they are §7 Polyline Set shapes); §10's precise NURBS curves are complete at Layer 1 |
| Streaming input (not whole-file `ByteArray`) | first file too large to buffer comfortably |
| Browser JS target | first browser consumer (ConstructIt seam) |
| General re-layout on arbitrary mutation | Layer 1 authoring writer (single-segment payload replacement exists: `withSegmentPayload`) |
| kotlinx-io ≥ 0.4 / Kotlin 2.x upgrade | any feature blocked on the old toolchain |
