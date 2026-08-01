# PACKAGE H — Data Compression and Encoding (9.5 §8) and Best Practices (9.5 §9)

Sources: `JT_v95_File_Format_Reference_Rev-D.pdf` §8 (pp. 253–294), §9 (pp. 294–296),
Appendix C (pp. 310–332), Appendix D (pp. 333–335); v10 Rev-C §12 (pp. 155–195), §13
(pp. 196–199), Annex C (pp. 239–241). Library at `/home/haui/devel/kotlinJT`, `main` @ d9c1c45.

**The one-line answer to the package's three questions.**

1. **Mk. 1 vs Mk. 2 is not a wire decision at all.** 9.5 p.19–20 defines the figure notation
   `Int32CDP` = §8.1.1 Mk. 1 and `Int32CDP2` = §8.1.2 Mk. 2, *per field*. Nothing in the byte
   stream distinguishes them; the reader dispatches on **which field it is reading**. v10's
   single `Int32CDP` is 9.5's **Mk. 2**, extended (Move-to-Front CODEC, escape-flag context,
   7-bit value width, the external-compression OOB branch). The library implements Mk. 2 twice
   (`Int32Cdp.read` = 9.5 Mk. 2, `Int32Cdp.readV10` = v10) and **Mk. 1 not at all**. `Int64Cdp`
   has no 9.5 counterpart. See finding 2.
2. **Float64CDP is not Int64CDP renamed** — it is a different codec with a different value
   domain, different framing (`F64` value range, `VecF64` raw out-of-band, `I32`/`F64` context
   entries, no chopper, no bit-packed context) and no bit-reinterpretation step. The library has
   never seen one. See finding 3.
3. **DESIGN.md's arithmetic-codec entries**: delta 16 confirmed verbatim; delta 17 **corrected**
   — Rev-D's Appendix C §2.2 documents the "undocumented" bitlength grammar in full; delta 38
   confirmed and strengthened (9.5's *prose* also says `I16`); deltas 28/37 are v10-only rules
   that 9.5 explicitly does **not** share. See findings 1, 4, 6, 7.

---

## Part 1 — ledger rows

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

## Part 2 — findings

### 1. CONFIRMED — DESIGN.md delta 16 is exactly right, and the "read it the v10 way" failure is now quantified. `trivial` (nothing to fix)

DESIGN.md delta 16 records the JT 9 probability context as "a byte-aligned bit block — `U32{16}`
entry count, 6+6+6-bit field widths, 32-bit min value, entries of (symbol+2, occurrence count,
value−min) — with symbol −2 as the escape". 9.5 §8.1.2.1 Figure 222 (p.261) and §8.1.2.1.1
Figure 223 (p.262) print precisely that, and §8.1.2.1.1's prose supplies the bias rule verbatim:

> "The symbol is stored with a '+2' added to the value and thus a reader must subtract '2' from
> the read value to get the true symbol value. … the escape symbol used for out-of-band data
> which will have the value '0' in the file, however it will become '−2', its true symbol
> value" (p.262)

and Appendix C §1.2 fixes the constant: `CEBEscape = -2` (p.310). `Int32ProbabilityContext.read`
(`Int32Cdp.kt:500`) reads `16, 6, 6, 6, 32` and subtracts 2 — a match on every field.

The desynchronisation the brief asks about is now exact. 9.5's header is
`16 + 6 + 6 + 6 + 32 = 66` bits; v10's Figure 133 header is `16 + 6 + 7 + 32 = 61`. They agree
for 22 bits and diverge from bit 23: v10 takes 9.5's *Number Occurrence Count Bits* as its
*Number Value Bits* and then reads a 32-bit Min Value from the middle of 9.5's value-width
field. Every subsequent entry is misaligned, and each entry differs again (9.5 spends
`symbolBits` where v10 spends 1). So the two context readers cannot be merged, and the library
is right not to have merged them.

Second confirmation on the same page: the arithmetic core. Appendix C §3.2's
`ArithmeticCodec2::decode` and `_removeSymbolFromStream` (pp. 327–328) are the 16-bit decoder
`decodeArithmeticSymbolIndices` (`Int32Cdp.kt:911`) implements, down to
`rescaledCode = ((code − low + 1) * total − 1) / (high − low + 1)`, the `_code ^= 0x4000`
underflow squeeze and the two-bit flush. The one textual difference — Kotlin uses
`low & 0x4000 && !(high & 0x4000)` where the reference's live line is
`((_low>>14)==1) & ((_high>>14)==2)` — is not a divergence: the branch is only reached after
`~(high^low) & 0x8000` failed, which forces `low`'s top bit to 0 and `high`'s to 1, making the
two tests equivalent. The reference itself carries the Kotlin form as the commented-out
alternative on the preceding line.

### 2. GAP — the Int32 CDP **Mk. 1** packet is not implemented, and 9.5 selects it statically, per field. `large`

9.5 p.19–20 defines the notation, and it is the whole dispatch rule:

> "Int32CDP – The Int32CDP (i.e. Int32 Compressed Data Packet) … A complete description … can
> be found in 8.1.1. **Int32CDP2** – The Int32CDP2 (i.e. Int32 Compressed Data Packet Mk. 2)
> represents a second-generation version of the above compressed data packet, and sports a
> simplified and more compact file layout … A complete description … can be found in 8.1.2."

There is **no wire tag**. A 9.5 reader must dispatch on the *field it is reading*, from the
figure's `{Int32CDP,…}` / `{Int32CDP2,…}` annotation. The split across the 9.5 document:

- **Mk. 2 (`Int32CDP2`)** — everything the library already reads: TopoMesh Compressed Rep
  Data V1/V2 streams (§7.2.2, spec95 ll. 5665–6103), the vertex arrays §8.1.4–8.1.8, the
  Wireframe Rep Element's index vectors (Fig. 130), Compressed CAD Tag Data §8.1.16.
- **Mk. 1 (`Int32CDP`)** — everything the library does not: **all** JT B-Rep topology streams
  (§7.2.3.1: First/Last Shell Indices, Region Tags, Face Tags, Shell Anti-Hole Flags,
  Trim Loop / CoEdge / Edge / PCS Curve indices, …), the JT LWPA element (Compressed Types,
  params1 Codes), and §8.1.13 / §8.1.14 / §8.1.15 — the entire NURBS curve and knot-vector
  machinery.

Mk. 1's layout is a different packet, not a variant (Figure 218, p.254): no leading Value Count,
so no empty-packet form; `U8 CODEC Type` first; the probability context is a **list** of 1–2
tables with a leading `U8` count and a per-entry `Next Context` field; out-of-band data is gated
on an explicit `I32 Out-Of-Band Value Count > 0`; `CodeText Length` and `Value Element Count`
come *after* the OOB packet; a `Symbol Count` appears when two tables are in play; and
`VecU32 : CodeText` is a real `VecU32` — "*a vector … starts with an I32 that defines the count
of following U32*" (p.21) — so it carries its **own length word** that Mk. 2 does not have.

The decoder is also different, not just the framing (Appendix C §3.1, p.325): it loops
`numSymbolsToRead()` (Symbol Count, not Value Count, when two tables), switches probability
context per symbol via `pCntxEntry->iNextCntx`, and — per §8.1.1's own note (p.254) — only
emits an out-of-band value when the escape is met **while using table 0**, which is why symbol
count and value count are separate fields at all.

Cost: `large`. A new packet class, a two-table context reader, a context-switching arithmetic
loop, plus the Mk. 1 bitlength variant (Appendix C §2.1). It gates every JT 9 B-Rep, LWPA and
NURBS decode. Note one open point: Figure 218's CODEC-type table lists `=4 Chopper CODEC`, but
§8.1.2 says the Chopper is what "the Int32 Compressed Data Packet Mk. 2 brings … to the table"
(p.258) and Figure 218 draws no chopper fields — `spec unclear`; a Mk. 1 packet declaring
codec 4 has no documented layout and the reader should refuse with a note.

**Doctrine.** The model must record which packet generation a field was read as, because
nothing in the bytes says. `Int32Cdp` is already a sealed hierarchy with per-variant `encode`;
adding `Mk1*` variants keeps re-serialization a projection.

### 3. GAP — the Float64 Compressed Data Packet is a codec the library has never seen, and it is *not* Int64CDP. `small`–`large`

§8.1.3 (pp. 263–266). The mechanism differs from v10's Int64CDP at every level:

| | 9.5 Float64CDP | v10 Int64CDP |
|---|---|---|
| symbol domain | `F64` values, natively | `I64`, bit-reinterpreted to `F64` by the consumer |
| framing | `U8 CODEC Type` first, then range/OOB/length | `I32 Value Count` first |
| value range | `F64 Value Range Min` / `Max` on the wire | none |
| out-of-band | `I32` count + `VecF64` raw, **always** — "*simply writes out the out-of-band data array with no additional encoding attempted*" (p.263) | nested packet, or count+raw when externally compressed |
| context | plain `I32` counts + 20-byte flat entries (`I32` symbol, `I32` count, `F64` value, `I32` reserved), list of 1–2 tables | bit-packed, single table, `U64{64}` Min Value |
| chopper / MTF | absent | present |

So this is a genuinely different codec, not a rename, and it cannot be reached by widening
`Int64Cdp`. Every 9.5 NURBS weight, control point and knot vector goes through it
(§8.1.14, §8.1.15, §8.1.15.3) — so **a 9.5 file with a JT B-Rep or a Wireframe Rep carries
Float64 packets the library cannot read**, on top of finding 2's Mk. 1 dependency.

Cost: `small` in isolation (the arithmetic core and the escape convention are shared; the
framing is flat and byte-aligned), `large` in effect because it only becomes useful together
with Mk. 1. Two `spec unclear` points to record: §8.1.3.1 never restricts the context
collection to the Arithmetic CODEC the way its Int32 siblings do, while Figure 224's guard is
the broader "CODEC Type not equal to Null"; and the `I32 Reserved Field` in every context entry
must be carried verbatim (§9.3) since the document assigns it no value.

### 4. CORRECTION TO DESIGN.md — delta 17's "neither spec's prose describes this wire format" is wrong; Rev-D Appendix C §2.2 describes it exactly. `trivial` (documentation)

DESIGN.md delta 17 calls the JT 9 bitlength stream "the single most treacherous delta" and says:

> "**Neither spec's prose describes this wire format** (the 9.5 Appendix C shows the older Mk.-1
> prefix-code scheme; v10's Annex B a nibbler-based block scheme); it was established from the
> fixture bits … and confirmed by TKJT."

That was true of an earlier revision. **Rev-D added `BitLengthCodec 2` as Appendix C §2.2**
(pp. 322–324) — the change list on p.13 records it: "*Added to Appendix C: Decoding Algorithms
– An Implementation section 2.2 BitLengthCodec 2*". Its `decode()` is the grammar
`decodeBitlength` (`Int32Cdp.kt:713`) implements, statement for statement:

```
GetUnsignedBits(iTmp, 1);                       // 0 = Fixed-width, 1 = Variable width
if (iTmp == 0) {
    GetUnsignedBits(cBitsInMinSymbol, 6);
    GetUnsignedBits(cBitsInMaxSymbol, 6);
    GetSignedBits  (iMinSymbol, cBitsInMinSymbol);
    GetSignedBits  (iMaxSymbol, cBitsInMaxSymbol);
    cNumCurBits = _nBitsInSymbol(iMaxSymbol - iMinSymbol);
    …  GetUnsignedBits(iSymbol, cNumCurBits); iSymbol += iMinSymbol;
} else {
    GetSignedBits(iMean, 32);
    GetUnsignedBits(cBlkValBits, 3);
    GetUnsignedBits(cBlkLenBits, 3);
    cMaxFieldDecr = -(1 << (cBlkValBits - 1));  cMaxFieldIncr = (1 << (cBlkValBits - 1)) - 1;
    do { GetSignedBits(cDeltaFieldWidth, cBlkValBits); cCurFieldWidth += cDeltaFieldWidth; }
      while (cDeltaFieldWidth == cMaxFieldDecr || cDeltaFieldWidth == cMaxFieldIncr);
    GetUnsignedBits(cRunLen, cBlkLenBits);
    … GetSignedBits(iTmp, cCurFieldWidth); *paiValues++ = iTmp + iMean;
}
```

Even the corner case matches: `_nBitsInSymbol` "*returns 0 bits for a symbol value of zero*",
which is why the Kotlin path emits `min` for every value when `range <= 0`. §8.2.2's *prose*
(p.287) still describes only the Mk. 1 prefix-code scheme, so delta 17's complaint about the
prose stands — but the reference **does** specify the format, in the appendix, and the entry
should be rewritten from "reverse-engineered, treacherous" to "9.5 Rev-D Appendix C §2.2,
pp. 322–324, code matches line for line". This is the single cheapest upgrade in the package:
the library's least-grounded decoder becomes its best-cited one.

### 5. CONTRADICTION — the JT 9 arithmetic path refuses the all-out-of-band packet the 9.5 document explicitly defines. `trivial`

§8.1.2 (p.258) states:

> "In some cases, all values may be written as 'out of band' when the Codec cannot perform any
> useful compression. In this case, the encoded **I32 : CodeText Length field will be 0**, and
> the I32 : Out-Of-Band Value Count will be equal to I32 : Value Element Count. The implied
> action in this case is to merely **copy the Out-Of-Band value data into the output Value
> Element array instead of invoking the Codec**."

The v10 path honours this (`Int32Cdp.kt:346`: `if (codeTextLength == 0) outOfBand?.values`).
The **JT 9 path does not**: `readCodeTextCodec` (`Int32Cdp.kt:256–266`) always calls
`decodeArithmetic`, which calls `decodeArithmeticSymbolIndices`, which throws at
`Int32Cdp.kt:920` — `"arithmetic CodeText is shorter than the initial 16-bit code"` — because
`codeText` is empty. Any JT 9 shape body containing one incompressible array is refused and
falls back to opaque.

The two fixtures cannot distinguish this: none of their 506 packets is an all-OOB arithmetic
packet (had one been, the shape would already be failing). This is a spec-vs-code contradiction
where the spec is unambiguous and the code is simply missing a branch — copy the four lines
from the v10 path. `trivial`. Same paragraph appears verbatim for Mk. 1 (§8.1.1, p.253) and for
Float64CDP (§8.1.3, p.263), so whatever is built for those must carry it too.

While there: the JT 9 arithmetic branch also lacks the v10 branch's
`if (values.size != count) throw` post-check (`Int32Cdp.kt:357`). Cheap to add, and it is what
§8.1.2's "*Upon completion of decoding … the number of decoded Values should be equal to Value
Count*" asks for.

### 6. CONFIRMATION — the JT 9 lossless vertex hash really is per-value, and 9.5 says so twice. `trivial` (nothing to fix)

DESIGN.md delta 29 records that v10's §12.1.3/§12.1.4 pseudo-code (`hash32(&value, 1, uHash)`
per element) does **not** match v10's bytes, which hash whole component arrays. The natural
worry is that the JT 9 path, which *does* hash per value (`VertexArrays.kt:118`, `:259`), is
copying a formula that is wrong in both generations. It is not:

- 9.5 §8.1.4 (p.267) and §8.1.5 (p.268) print the same per-value pseudo-code, and the JT 9
  fixture's 12 coordinate hashes and 12 normal hashes verify under it (DESIGN.md delta 19).
- Appendix C's `CodecDriver2::assemble` (p.318) — which *does* hash whole arrays,
  `hash32(vMant, nIdx); hash32(vExp, nIdx)` — carries an explicit exclusion:

  > "NOTE: These methods **'CANNOT' be used with the 9.x serialization of Coordinates, Normals,
  > Colors, and Tex Coordinate** do[sic] the way the hash value is calculated."

So the reference itself says the whole-array form is the *non*-9.x one. The generations
genuinely differ in hashing, `VertexArrays.kt:118` is correct for 9.5, `:181` is correct for
v10, and delta 29 should gain the Appendix C citation as corroboration rather than being
treated as a doubt hanging over the JT 9 path. `CodecDriver2::assemble` also confirms the JT 9
float reconstruction `fi.ui = pExp[i] << 23 | pMant[i]` exactly as `VertexArrays.kt:116` does
it, and gives the `F64` form (`(exp << 52) | (mant1 << 31) | mant0`) for the three-packet
auxiliary-field case the shape package will need.

### 7. CONFIRMATION — the hash function is the same in both generations, on both documents' own word. `trivial` (nothing to fix)

9.5 §9.5 (p.295) and v10 §13.8 (p.196) describe the same Bob Jenkins 1997 function with the
same seed-chaining convention, and v10 states the identity outright: "*It is the same
implementation that was used in JT v9.x.*" Appendix D (pp. 333–335) and Annex C (pp. 239–241)
carry byte-identical bodies for `mix`, `hash` and `hash3`; the only textual difference is the
wrapper name `hash16` → `jthash16`. `JtHash.kt` therefore needs no generation split, and every
collection that stores a hash — the vertex arrays, the FGPV list, the composite topology hash,
the unique-length list — shares one verifier across 9.5 and v10. Since the brief flagged this
as the "breaks decoding everywhere at once" risk: **there is no such risk.**

One caveat worth recording: neither document prints `hash2`'s body — both define
`hash32(pWords, nWords, seed) { return hash2(...); }` and then show only the byte-oriented
`hash()` and the 16-bit `hash3()`. `JtHash.hash32` is therefore reconstructed (from `hash()`'s
word form: `c += count` where count is the *word* count) and validated by the fixtures'
117 + 24-odd stored hashes, not by a printed reference. That is solid evidence, but it is
evidence, not citation.

### 8. PRODUCER-vs-DOCUMENT — the Deering quantization bit range is wrong in both documents. Bernhard's call. `trivial`

9.5 §8.2.4 (p.293) and v10 §12.2.4 (p.193) are word-identical:

> "all normals within the sextant are represented as two n-bit angular addresses … where 'n' is
> in the range from **0 to 6** bits" … "Resulting in a max grand total of 18 bits (3 + 3 + 6 + 6)".

Reality disagrees on both sides. v10's own Figure 139 prose says "*The maximum value for this
field is 13*" — flatly contradicting its §12.2.4 four pages later. And the JT 9 fixture writes
**8** quantization bits (DESIGN.md delta 21: "the arrays' own `U8` says 8 bits, and the normal
hashes confirm 8"), which §8.2.4 forbids and 9.5 states no other bound for.

The reading: §8.2.4/§12.2.4 is Deering's original 1995 parameterisation, carried forward
unchanged, and neither format actually honours it. The lenient reader should accept any bit
count the array's own `U8` declares (the JT 9 path already does, up to 13); the strict writer
should emit what it read. Nothing to change in code except the provenance of
`VertexArrays.kt:270`'s `> 13` refusal, which is v10-derived and has no 9.5 basis — for the
JT 9 reader the honest bound is "whatever the four code packets' values fit", since 9.5 stores
sextant/octant/theta/psi in four *separate* packets and therefore has none of v10's
pack-into-32-bits constraint.

### 9. GAP — three of the five 9.5 vertex arrays have no reader in either generation. `small` each

`ShapeLodDocument.kt:326` sets `UNSUPPORTED_BINDING_MASK = 0xF.inv()`, so the JT 9 path accepts
only coordinates (bits 0–2) and normals (bit 3) and refuses everything else at
`ShapeLodDocument.kt:408`. That leaves:

- **§8.1.6 / Fig. 229 Texture Coordinate Array** — no reader at all, either generation.
- **§8.1.7 / Fig. 230 Color Array** — no reader at all, either generation. The 9.5 form is
  further from v10 than any other array: four separate Lag1 code packets (Hue/Red, Sat/Green,
  Value/Blue, Alpha) against v10's single bit-packed `Colour Codes` array, and a hash chained
  over four arrays against v10's one.
- **§8.1.8 / Fig. 231 Vertex Flag Array** — `CompressedVertexFlagArray.read`
  (`VertexArrays.kt:381`) exists but is v10-only twice over: it reads a `U32` count where 9.5
  says `I32` (same bytes) and calls `readInt32CdpValuesV10`. Adding the JT 9 form is two lines;
  the actual blocker is the binding-mask refusal.

Cost: `small` each on the array itself (the shapes are simple, and each carries a stored hash
that validates the result immediately), plus widening the binding mask. The flag array is the
cheapest and the most likely to appear in real 9.5 files.

### 10. SPEC-INTERNAL ERRORS in 9.5 §8 — three figures contradict their own prose. `trivial` (reader must prefer the prose)

Recorded so the implementation packages do not read the boxes literally. All verified against
the PDF, not just the text extraction.

1. **Figures 229 and 230 print the guard "QuantBits = 0" on *both* branches** (pp. 271, 273).
   The right-hand branch is plainly `QuantBits > 0` — Figures 227 and 228 print it correctly,
   and the field prose describes a quantizer + code arrays there. `pdftotext -f 271 -l 273
   -layout` reproduces `QuantBits = 0    QuantBits = 0` twice.
2. **Figure 242's Type-1 branch box reads `I16:Version Number`** (p.285) where the prose
   documents `VecI32{Int32CDP2, Lag1} : CAD Tags Type-1`. A copy-paste of the collection's own
   leading version box. Prose is authoritative.
3. **Figure 242's two branch guards read "If 'Type-1'/'Type-2' CAD Tags exist in `I32 : Surface
   Count` data"** (p.285). There is no Surface Count in this collection; §8.1.16's prose says
   the reader "must first uncompress/decode and evaluate the previously read **CAD Tag Types**"
   — a leftover from the B-Rep section this figure was cloned from.

Also worth carrying forward, though not an error: §8.1.2.1's prose retains the Mk. 1 sentence
"*Number Value Bits is only specified in the JT file for the first Probability Context Table.
If a second Probability Context Table is present…*" (p.262) even though Figure 222 has no table
count and Mk. 2 has exactly one table. Harmless if the figure is followed.

### 11. NOTE — DESIGN.md deltas 28, 37 and 38 against the 9.5 document

Not findings in their own right, but the brief asks for each to be confirmed, corrected or
refuted.

- **Delta 38 — CONFIRMED, and strengthened.** "*the v9.5 reference's Figure 130 really does
  show `I16`*". It does (p.160), **and so does its prose**: "`I16 : Version Number` — Version
  Number is the version identifier for this JT Wireframe Rep Element. Version numbers '1' is
  currently supported." So this is not a 9.5 figure quirk that v10 inherited — the field
  genuinely narrowed from `I16` to `U8` with the generation, exactly as delta 6 describes for
  the LSG, and v10's Figure 104 box is stale while its §10.1 prose is right. Delta 38's
  classification ("figure error rather than a version delta", *for v10*) is correct and now
  rests on a citation rather than an inference. `WireframeDocument.kt:205`'s refusal to guess
  the JT 9 layout is vindicated — and finding 2 explains why that layout is more work than the
  version byte: Figure 130's curve data is Mk. 1 + Float64CDP throughout.
- **Deltas 28 and 37 — CONFIRMED as v10-only; 9.5 takes the opposite position, and the code is
  already right.** Both are rules about *when* and *in what form* the arithmetic out-of-band
  data appears. 9.5 does not share either:
  - Figure 221 (p.259) draws `Int32 Compressed Data Packet Mk. 2 : OOB Data Values`
    **unconditionally** under the Arithmetic branch — no escape-presence condition, and no
    "segment is externally compressed" branch anywhere in §8. `Int32Cdp.kt:258` reads it
    unconditionally, which is what the figure says. Delta 15's parenthetical "(always present,
    possibly empty)" is therefore confirmed by the document, not merely by the 506 fixture
    packets.
  - Figure 218 (p.254) gates Mk. 1's nested packet on an explicit `I32 Out-Of-Band Value
    Count > 0` — a *third* convention, different from both v10 forms and from Mk. 2's.
  - The external-compression branch of v10 Figures 132/135 is genuinely new in v10; nothing in
    9.5 §8 mentions the enclosing segment's compression state. So `externallyCompressed`
    correctly appears only on `readV10`/`Int64Cdp.read` and correctly does not exist on the
    JT 9 path. Delta 37 should gain the sentence "9.5 has neither branch; its Mk. 2 packet
    always nests and its Mk. 1 packet always counts", so a future reader does not try to
    generalise the rule backwards.

### 12. NOTE — predictor set

9.5's normative predictor table (p.20) lists nine: Lag1, Lag2, Stride1, Stride2, StripIndex,
Ramp, Xor1, Xor2, NULL — matching `Predictor` (`Int32Cdp.kt:598`) exactly, and
`CodecDriver::PredictorType` (App. C §1.5, p.315) assigns them the numeric codes 0…8.
Appendix C §1.6's `CodecDriver2::PredictorType` (p.318) adds three more: `PredMean2 = 9`,
`PredMean3 = 10`, `PredMean4 = 11` (`(a+b)>>1`, `(a+b+c)/3`, `(a+b+c+d)>>2`). No §8 figure uses
them, so this package needs nothing; but any collection that stores a predictor code
*numerically* (the LWPA "Combined Predictor Type data collection" is the candidate) can carry
9–11, and `unpackResiduals` would have no case for them. Flagged to the LWPA package.
By contrast v10's Table 2 lists only three predictors (Lag1, Xor1, NULL) while its figures use
more — a v10 documentation gap, not a 9.5 one.
