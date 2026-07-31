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

- **LZMA (v10, clause 12.2.5) is a named future extension**, not implemented. Its time comes
  with the first v10 fixture that uses it. Until then a segment flagged LZMA loads with note
  `UNSUPPORTED_COMPRESSION` and its raw bytes fully accessible — and re-serializes
  byte-identically.

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
  bytes — two producers, two format generations. Still carried verbatim by Layer 0 (shape
  segment *interpretation* is the §7 package's job; the identification is recorded so that
  package starts from fact, not mystery).
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
simplest legal encoding until an LZMA *encoder* has a real v10 fixture to prove itself
against (the committed NIST 10.5 fixture gives the *decoder* its condition — see deferrals).

**v9 policy.** Types whose v9 layout is fixture-verified or follows from fixture-verified
sub-collections plus the version-width rule decode typed in v9: the whole node family
(including all shape nodes — their tails after verified collections are fixed-size, so the
strict length check turns any wrong derivation into a named opaque fallback, not a misread),
the property atom family, the property table, and the Material attribute. All *other*
attribute types (draw style, lights, line/point style, geometric transform, textures,
mappings) are **opaque-by-policy in v9** with `ELEMENT_LAYOUT_UNVERIFIED` — the LOD/shape/
material deltas below prove that v9 layouts do *not* follow mechanically from v10, so
guessing variable-length layouts would risk silent misreads. Their time comes with the first
v9 fixture that carries them.

**Known spec ambiguities recorded**: Figure 57 (Base Light Data) shows a stray element-header
box; read as Base Attribute Data first, per the attribute-element convention — spec-derived,
not fixture-verified. The Vector4f Property Atom's GUID appears in §6.2 but is missing from
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
   and for version ≥ 2 a second U64 binding field.** Byte-consumption verified: the fixture's
   shape nodes carry version 2 and exactly 22 post-shape bytes. Evidence limit recorded
   honestly: those 22 bytes are all zero, so alternative field groupings of the same width
   would also fit; the chosen layout follows the v9-generation lineage of the v10 U64 field.
   A contradicting file falls back to opaque-with-note via the strict length check.
10. **Base Attribute Data: v9 has no Field Final Flags** (I16 version, U8 state flags, U32
    field inhibit flags). Evidence: both material elements parse to exact length only without
    the v10 U32; with it, the RGBA block would misalign by four bytes.
11. **Material Attribute: v9 has no bumpiness; reflectivity exists from local version 2 on.**
    Evidence: both material elements are version 2 with exactly 18 F32 payload values =
    4 RGBA + shininess + reflectivity (v10 Figure 47 has 19 with bumpiness). Data-flag bits
    0x000F (the v10 inhibit table hints at "Common RGB Value" compact colour storage in older
    generations) refuse the v9 typed decode — layout not established, never guessed.
12. **The Property Table layout is shared by both generations** (I16 version even in v10,
    Figure 78). Evidence: the fixture's 694-byte tail parses to exactly 40 element property
    tables with zero leftover; all key/value object ids resolve to decoded property atoms.

## Fixture conventions (from the amendment on issue #1)

- JVM-only `FixtureDiscoveryTest` auto-discovers `*.jt` in **both tiers** — the committed
  public spine under `fixtures/` (sidecars committed with them) and the IP-encumbered
  `fixtures-local/` (repo root found by walking up to `settings.gradle.kts`/`.git`). Battery
  per file: parse with exactly the sidecar's note names (empty for a healthy file); inventory
  JSON — including per-payload SHA-256 and the LSG element histogram — equal to the sidecar
  `<name>.expected.json` (created on first run for human review, asserted thereafter);
  byte-identical re-serialization; LSG element-stream round-trip; the model-level mutation
  probe (skipping visibly where the LSG is not decodable, e.g. the NIST fixture's LZMA).
- No fixtures ⇒ one visibly **SKIPPED** test whose name carries the count. Verified.
- Committed code never names a fixture file (customer part numbers). Sidecars live next to
  the fixtures, equally gitignored.
- Committed conformance lives in the synthetic round-trip suite (both byte orders, both
  version generations, hostile variants) — regression pinning, not conformance proof, per the
  "your own writer proves nothing" doctrine.

## Deferred (with the condition for its time)

| What | Its time comes when |
|---|---|
| LZMA segment codec (v10, §12.2.5) | **condition met**: the committed NIST 10.5 fixture uses it (59 refused segments incl. the LSG) — next decoding package |
| Int32CDP / geometry codecs (§12) | Layer 1 shape reading |
| Element body parsing for non-LSG segments (shape LOD, meta data, PMI) | the §7/§11 packages (LSG done, issue #3) |
| v9 layouts of the non-material attribute elements (lights, styles, transform, textures, mappings) | first v9 fixture carrying them (opaque with `ELEMENT_LAYOUT_UNVERIFIED` until then) |
| Property-table *semantics* (units, key naming conventions, §13.8) | Layer 2 scene façade (raw carrying is done) |
| Shape-segment trailing empty property table interpretation | §7 shape LOD package (identified, see observations) |
| Streaming input (not whole-file `ByteArray`) | first file too large to buffer comfortably |
| Browser JS target | first browser consumer (ConstructIt seam) |
| General re-layout on arbitrary mutation | Layer 1 authoring writer (single-segment payload replacement exists: `withSegmentPayload`) |
| kotlinx-io ≥ 0.4 / Kotlin 2.x upgrade | any feature blocked on the old toolchain |
