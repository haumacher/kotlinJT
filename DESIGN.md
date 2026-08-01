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

**The v10 wire formats are deliberately not implemented here.** v10's Int32CDP (Figure 132:
no symbol field, 7-bit value widths, Move-to-Front codec), its bitlength variant (Annex B's
nibbler-based block scheme), its packed Deering code array and its shape element bodies
differ from the JT 9 generation enough that implementing them unverifiable would be
guessing. Until the v10 shape-body package establishes them, v10 shape elements are carried
opaquely with `ELEMENT_LAYOUT_UNVERIFIED`. **Correction (issue #5)**: this section
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
B-Rep. Meta data / PMI / B-rep element *bodies* remain opaque per plan (§11 package;
B-rep opaque by doctrine).

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

## Fixture conventions (from the amendment on issue #1)

- JVM-only `FixtureDiscoveryTest` auto-discovers `*.jt` in **both tiers** — the committed
  public spine under `fixtures/` (sidecars committed with them) and the IP-encumbered
  `fixtures-local/` (repo root found by walking up to `settings.gradle.kts`/`.git`). Battery
  per file: parse with exactly the sidecar's note names (empty for a healthy file); inventory
  JSON — including per-payload SHA-256 and the LSG element histogram — equal to the sidecar
  `<name>.expected.json` (created on first run for human review, asserted thereafter);
  byte-identical re-serialization; LSG element-stream round-trip; the model-level mutation
  probe (skipping visibly where a stage is not applicable — since issue #5 both committed
  fixtures run the full LSG battery; NIST still skips the tri-strip geometry stage, whose
  v10 bodies await the v10 shape-body package).
- No fixtures ⇒ one visibly **SKIPPED** test whose name carries the count. Verified.
- Committed code never names a fixture file (customer part numbers). Sidecars live next to
  the fixtures, equally gitignored.
- Committed conformance lives in the synthetic round-trip suite (both byte orders, both
  version generations, hostile variants) — regression pinning, not conformance proof, per the
  "your own writer proves nothing" doctrine.

## Deferred (with the condition for its time)

| What | Its time comes when |
|---|---|
| v10 shape element bodies + v10 Int32CDP/Int64CDP wire formats (Figures 132–137), v10 bitlength/packed-Deering variants | **condition met** (corrected — see the issue #4 correction above): the NIST bodies were plain bytes all along, and since issue #5 the decoded LSG cross-checks them (declared count ranges, late-loaded references) — next decoding package (until then: opaque with `ELEMENT_LAYOUT_UNVERIFIED`) |
| XZ SHA-256 block-check verification, non-LZMA2 xz filter chains | first real stream carrying them (today: SHA-256 decodes unverified; foreign filter chains refuse with `UNSUPPORTED_COMPRESSION`) |
| LZMA *encoder* (writer-side segment compression) | a consumer needs v10-writer output smaller than stored/ZLIB permits (issue #1 policy: simplest legal encodings) |
| Polyline/Point/Polygon/Primitive Set Shape LOD bodies (TopoMesh Compressed Rep Data V1/V2, lossless/lossy primitive set data) | first fixture carrying them (all fixture shape segments are tri-strip) |
| Vertex colours, texture coordinates, per-vertex flags and auxiliary fields in vertex records | first fixture whose bindings declare them (typed decode refuses with a named note today) |
| Element body parsing for meta data / PMI segments | the §11 package (LSG done issue #3, shape LOD done issue #4) |
| v9 layouts of the non-material attribute elements (lights, styles, transform, textures, mappings) | first v9 fixture carrying them (opaque with `ELEMENT_LAYOUT_UNVERIFIED` until then) |
| Property-table *semantics* (units, key naming conventions, §13.8) | Layer 2 scene façade (raw carrying is done) |
| Streaming input (not whole-file `ByteArray`) | first file too large to buffer comfortably |
| Browser JS target | first browser consumer (ConstructIt seam) |
| General re-layout on arbitrary mutation | Layer 1 authoring writer (single-segment payload replacement exists: `withSegmentPayload`) |
| kotlinx-io ≥ 0.4 / Kotlin 2.x upgrade | any feature blocked on the old toolchain |
