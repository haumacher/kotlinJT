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
polygon set and primitive set bodies remain refuse-with-note: no fixture carries one.

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
PMI*); the B-rep and wireframe bodies remain opaque — B-rep by doctrine, wireframe pending its
own package.

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
   `SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED`, `SCENE_MATERIAL_AMBIGUOUS`). A part whose
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
Shapes) collapses to one named part node: a LOD node's children become the per-tier
mesh/polyline lists (child order = tier order, finest first — byte-verified by the strictly
descending NIST triangle counts); a *pass-through* child (no name, identity transform, no
material, no geometry) is spliced out; a sole nameless transform-free child is absorbed.
The collapse loses nothing the Scene models — it is what turns the 9.5 fixture into root →
assembly → 12 named parts and the NIST file into root → 38 placed instances over 13 shared
parts. Transforms *inside* a LOD tier (below the point where tiers become meshes) are baked
into the vertex coordinates (points `p·M`, normals via inverse-transpose, renormalized);
multiple shapes in one tier merge with index offsets (differing effective materials →
`SCENE_MATERIAL_AMBIGUOUS`, first wins).

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
transform-free child is absorbed into its parent, geometry lands on named part nodes). Two scene
shapes therefore have no faithful pre-image, and the writer refuses them by naming the path
instead of writing a file that reads back differently:

1. a node carrying **geometry and children** — the collapse only ever puts geometry on a node
   whose children it absorbed, so re-reading would hang the geometry on an extra unnamed child;
2. a child the collapse would remove: **unnamed + identity transform + no material + no
   geometry** (spliced out), or a **sole** unnamed identity-transform child (absorbed).

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
| ~~v10 shape element bodies + v10 Int32CDP wire formats, v10 bitlength/packed-Deering variants~~ | **done** (issue #6, see *Layer 1: v10 shape LOD bodies*): tri-strip + polyline bodies decode typed in v10, all 117 stored hashes verified; Int64CDP (Figures 135–137) stays deferred — no §7 structure needs it, first consumer is B-rep/curve data |
| XZ SHA-256 block-check verification, non-LZMA2 xz filter chains | first real stream carrying them (today: SHA-256 decodes unverified; foreign filter chains refuse with `UNSUPPORTED_COMPRESSION`) |
| LZMA *encoder* (writer-side segment compression) | a consumer needs v10-writer output smaller than plain storage permits — note Table 8/9 leave *no other* v10 choice: ZLIB is a JT 9 value, so "stored" is the only legal alternative (issue #1 policy: simplest legal encodings) |
| Point/Polygon/Primitive Set Shape LOD bodies; Polyline Set in the JT 9 generation | first fixture carrying them (the NIST polylines settled the v10 Polyline layout — issue #6; no fixture shows the others) |
| Vertex colours, texture coordinates and auxiliary fields in vertex records | first fixture whose bindings declare them (typed decode refuses with a named note today; per-vertex *flags* landed with issue #6 — Table 48 bit 7, all NIST tri-strips) |
| ~~Element body parsing for meta data / PMI segments~~ | **done** (issue #9, see *Layer 1: Meta data and PMI*): all 44 Meta Data / PMI Data segments of the NIST fixture decode typed, byte-identical round-trip, cross-checked against the LSG's late-loaded references |
| The undocumented block NX 10.5 writes after a PMI Manager's fonts (delta 33) — and with it Figure 110's segment-level `Property Count` / PMI Properties and Figure 131's Model View Sort Orders | a fixture whose Property Count or Model View Sort Order Count is non-zero, or documentation of the trailing structure (today: carried verbatim with `PMI_MANAGER_TAIL_UNDOCUMENTED`, never read as something it may not be) |
| The entropy-coded vectors inside Compressed CAD Tag Data (Figure 154) | a consumer needs CAD tags — it needs the Int64 CDP (Figures 135–137) for Type-2 tags, the same deferral the B-rep/curve data waits on; today the framing is decoded and the coded bytes are kept verbatim, their extent pinned by the collection's own Data Length |
| The v9 PMI Manager layout (v9.5 Figure 136: PMI Version Number, reserved field, per-entity-type collections) | the first v9 fixture carrying a PMI Manager (today: opaque with `ELEMENT_LAYOUT_UNVERIFIED`; the v9 Property Proxy element *does* decode — its v9.5 figure is the v10 layout with an I16 version) |
| Surfacing meta data properties and PMI into the Layer 2 scene | a consumer needs them *and* a decision is made about which conventions the scene interprets (§13.8's CAD/tessellation/PMI property tables) rather than passing raw key/value bags through a format-agnostic model; today they are complete at Layer 1 |
| Authoring §11 segments in `writeJt` (property bags, PMI) | the Layer 2 scene grows the concepts — a file without meta data segments is legal, and the writer emits none today (the read side is `done`, so a written file's bags could be verified against it immediately) |
| v9 layouts of the non-material attribute elements (lights, styles, transform, textures, mappings) | first v9 fixture carrying them (opaque with `ELEMENT_LAYOUT_UNVERIFIED` until then) |
| ~~Property-table *semantics* (units, key naming conventions, §13.8)~~ | **done** (issue #7, see *Layer 2, read side*): JT_PROP_NAME, JT_PROP_MEASUREMENT_UNITS, key visibility convention, late-loaded shape resolution; other conventions (SUBNODE/reference sets, CAD/tessellation properties) stay raw at Layer 1 — their time comes with the first consumer that needs them interpreted |
| ~~`writeJt(scene)` — Layer 2 write side~~ | **done** (issue #8, see *Layer 2, write side*): scene → LSG + shape LOD segments, round-trip-verified on both fixtures; what remains is the external validation (JT2Go opening the staged candidates) before any of it freezes as a golden |
| Vertex-sharing tri-strip topology (a real Annex D encoder) | file size or a consumer demands it: today one component per triangle costs three coordinates per triangle (NIST rewrite 6.1 MB vs. the original 1.6 MB) and no entropy coding at all — correct and boring by policy |
| Entropy-coded Int32CDPs on the write side (bitlength/arithmetic/chopper/MTF) | a consumer needs smaller files than the null CODEC produces; the decoders exist, so an encoder can be validated against them |
| Writing a scene node that carries geometry *and* children, or a child the Layer 2 collapse would splice out/absorb | a read-side rule that hoists a geometry-only child onto its parent (today: a named `JtWriteException`, never a file that reads back differently) |
| Writing face groups, per-vertex colours, texture coordinates, PMI, B-rep | the Layer 2 scene grows the concept (face groups are read but the Scene model has no place for them yet) |
| Per-part units precedence (lowest node wins) for mixed-unit files | first real fixture declaring conflicting units (today: `SCENE_UNITS_MIXED` + `UNSPECIFIED`, never a guess) |
| Force/final/field-inhibit attribute accumulation in the scene | first real fixture using them (today: named note `SCENE_ATTRIBUTE_SEMANTICS_UNSUPPORTED`; both fixtures use plain accumulation) |
| Streaming input (not whole-file `ByteArray`) | first file too large to buffer comfortably |
| Browser JS target | first browser consumer (ConstructIt seam) |
| General re-layout on arbitrary mutation | Layer 1 authoring writer (single-segment payload replacement exists: `withSegmentPayload`) |
| kotlinx-io ≥ 0.4 / Kotlin 2.x upgrade | any feature blocked on the old toolchain |
