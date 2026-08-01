# Package D — LSG Property Atom Elements and the Property Table (9.5 §7.2.1.2 / §7.2.1.3)

Range: 9.5 Figures 70–80 (pp. 101–109), the enclosing sections §7.2.1.2 (p.100) and §7.2.1.3
(p.108), plus §9.1 Late-Loading Data (p.295) and §9.6 Metadata Conventions (pp.296–298).
v10 counterparts: §6.2 Figures 69–77 (pp.83–89), §6.3 Figures 78–79 (pp.90–91), §13.1 (p.196),
§13.5.1 (pp.196–197), §13.8 (pp.198–203).

**Independent byte evidence gathered for this pass.** I re-derived the layouts directly from
both 9.5 fixtures with a standalone Python probe (not the library), so the confirmations below
are not circular:

| | `fixtures-local/RB___E_01955.jt` | `fixtures-local/KR360-1.jt` |
|---|---|---|
| inflated LSG element data | 8 663 B | 6 163 B |
| property atoms | 41 = 28 String + 12 Late Loaded + 1 Date | 26 = 8 String + 18 Late Loaded |
| every atom's Base Property Atom Data | `01 00` (I16 version 1) + `00 00 00 00` (state flags 0) | same |
| every atom's element-local version | `01 00` (I16 = 1) | same |
| Late Loaded body length | 41 B = 1+4+2+4+2+16+4+4+4 | 41 B |
| Late Loaded `Reserved` | 1 in all 12 | 1 in all 18 |
| Late Loaded `Segment Type` | 7 (Shape LOD0) | 4 (Meta Data) and 7 |
| Property Table | I16 version 1, count 40, 46 entries, 0 leftover of 694 B | I16 version 1, count 19, 20 entries, 0 leftover of 318 B |

---

## Part 1 — ledger rows

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

## Part 2 — findings

### F1 — Producer-vs-document conflict: `Payload Object ID` does not identify the payload. *(cost: none to fix — the library already ignores it; the cost is DESIGN.md wording)*

9.5 §7.2.1.2.7 (p.107) says of `I32: Payload Object ID`: *"Object ID is the identifier for the
payload. Other objects referencing this particular payload will do so using the Object ID."*
v10 (p.88) says the same sentence.

The fixtures contradict it. In `RB___E_01955.jt` the twelve Late Loaded atoms carry Payload
Object IDs 79, 82, 85, … — each exactly *its own atom's object id + 1* — while the Shape LOD
element inside the referenced segment has **object id 0** (verified: segment
`48230029-18be-6784-…`, element GUID `10dd10ab…` Tri-Strip Set Shape LOD, base type 4,
object id 0). Nothing in the file references 79. The GUID is the only working association.

This is the document-side confirmation of **DESIGN.md delta 13** ("object ids do not
associate"), which was recorded as a fixture observation. It should be restated as a
producer-vs-document conflict: *the 9.5 and v10 references both claim Payload Object ID is the
referencing key; both 9.5 producers and the NIST 10.5 producer ignore that and the payload
element is numbered 0.* Resolution by GUID (`ReadScene.kt:458`–`463`) is right and should stay;
the writer already mints its own association (`LsgAuthoring.kt:437`) with payload id 0 and
`SHAPE_ELEMENT_OBJECT_ID = 0`, which matches the installed base rather than the document — that
choice is deliberate and worth a one-line note in DESIGN.md so it does not look like an
oversight.

### F2 — Contradiction (v10 side, found while diffing §13): the writer emits property-atom version `1` where v10 §13.5.1 mandates `0x02`. *(cost: trivial — one constant, but see the caveat)*

v10 §13.5.1 "Version numbers" (pp.196–197) explicitly lists the exceptions to the default `0x01`:

> "0x02" — Base Property Atom Element, String Property Atom Element, Integer Property Atom
> Element, Floating Point Property Atom Element, JT Object Reference Property Atom Element,
> Date Property Atom Element, Late Loaded Property Atom Element, Vector4f Property Atom Element

`LsgAuthoring.kt:618` sets `ATOM_VERSION = 1` and the comment at `:615` claims "Local version
numbers of the v10 element bodies this writer emits (all '1')". Both version positions of every
authored atom (the Base Property Atom Data version and the element-local version) therefore go
out as `01`. That contradicts v10 §13.5.1, and it also contradicts the observed v10.5 producer —
DESIGN.md's own §"undefined segment type 31" note records real String Property Atoms with
"base type 5, **version 2**, state flags 0x40000000".

**9.5 is the opposite and the library is right there:** 9.5 gives no §13.5.1-style table; each
figure's prose says *"Version number '0x0001' is currently the only valid value"* (pp.101, 102,
103, 104, 105, 106, 107), and both fixtures carry `01 00` in all 134 version positions of their
67 atoms (each atom has two: the Base Property Atom Data version and the element-local one).
So the v9 reading and any future v9 writer are correct at 1; only the **v10
authoring path** is off-document.

Caveat before anyone changes the constant: version 2's *additional* content is not documented
anywhere in the v10 reference — the figures show no version-2-guarded fields — so bumping the
number without knowing what a version-2 body must contain could produce a file that claims more
than it carries. This is a decision for Bernhard, not a mechanical fix. The honest interim step
is a DESIGN.md entry recording the divergence.

### F3 — Gap with a real consequence: 9.5 documents neither the `::` visible/hidden key convention nor the mixed-case units tolerance, yet 9.5 producers rely on both. *(cost: none to the code; the cost is a citation in DESIGN.md/SPEC_COVERAGE.md)*

v10 §13.8.1.1.1 (p.198) defines the convention (`"property"` = hidden, `"property::"` = visible)
and v10 Table 77's note (p.201) tells implementers to accept `JT_PROP_MEASUREMENT_UNITS` values
"with the first letter in both upper and lower case". **9.5 §9.6 has neither passage.** 9.5's
only trace of the convention is that it spells the tessellation keys `Chordal::` / `Angular::`
literally, as if the colons were part of the name.

But the 9.5 fixtures use both: `RB___E_01955.jt` carries the keys `Converter::`, `Licensee::`,
`Version::`, `Date::` alongside the unsuffixed `JT_PROP_NAME`, `PartitionType`,
`JT_PROP_MEASUREMENT_UNITS`, `JT_LLPROP_SHAPEIMPL` — and its units value is **`"Millimeters"`**,
capital M, which is *not* in 9.5 Table 9's value set (all-lowercase) and has no 9.5 sanction.

The library already handles both — `removeSuffix("::")` at `ReadScene.kt:178` and `:211`, and
`LengthUnit.parse`'s `ignoreCase = true` at `Scene.kt:197`. Under the doctrine this is correct
lenient reading. Two consequences worth recording:

1. The comment at `Scene.kt:191`–`193` says "the spec's own note records that producers write
   mixed case … and tells implementers to accept it". True of v10, **false of 9.5** — for a 9.5
   file the acceptance is pure leniency against the governing document, not a documented
   allowance. The comment should say which edition it is citing.
2. The `::` stripping is a *normalizing* read: `stringProperty(id, "JT_PROP_NAME")` matches both
   `JT_PROP_NAME` and `JT_PROP_NAME::` and the Layer-2 `Scene` cannot say which it saw. That is
   fine — Layer 2 is explicitly lossy and Layer 1 keeps the exact atom string — but it is worth
   stating so nobody later tries to round-trip through `Scene`.

No `LoadNote` fires for either leniency today. `SceneUnitsUnrecognized` fires only when the
value is not a unit name at all, which is the right threshold: "Millimeters" *is* the documented
unit, differently cased.

### F4 — Lenient-reading gap: 9.5 §9.4 licenses trailing per-version data that the codecs refuse wholesale. *(cost: small — one shared tail policy in the element frame decoder, not per codec)*

9.5 §9.4 Local Version (p.295):

> "The standard convention followed by each data collection … is to write the data from each
> local version in order. This allows readers to read up to the maximum local version they
> support and then use the segment length that was read in the Segment Header to skip over any
> data they may not understand."

`decodeElementFrame` (`LsgCodecs.kt:1616`) does the opposite: any unconsumed byte throws
`"N bytes of the element body were not consumed"`, the whole element becomes an
`OpaqueLsgElement` with `ELEMENT_DECODE_FAILED`, and every typed field the codec *did* read is
discarded. For a 9.6/9.7 file whose String Property Atom appends a version-2 field, the library
would surface no value at all rather than the version-1 value plus a note.

This is exactly the shape of the 10.5 Date-atom problem (DESIGN.md delta 26), which was solved
by a hard-coded generation branch (`LsgCodecs.kt:1424`) rather than by a general policy. The
doctrine-conformant design is: decode the fields the declared local version covers, retain the
remainder verbatim on the element (so re-serialization is byte-identical), and name it — e.g.
`ELEMENT_TRAILING_VERSION_DATA`. The model must remember the exact trailing bytes, per rule 1
of the doctrine. Neither 9.5 fixture exercises this (all 67 atoms are version 1 and consume
exactly), so it is a latent gap, not a current bug — but it applies to the whole element family,
not just property atoms, so it should be decided once.

### F5 — Naming nit with a real cost: `PROPERTY_TABLE_MISSING` fires on segments the document says have no property table. *(cost: trivial)*

9.5 Figure 81 (p.109) shows the Shape LOD Segment as exactly `Segment Header` + `Shape LOD
Element` — no end-of-elements marker, no Property Table. v10 Figure 80 (p.91) is the same. Yet
both 9.5 fixtures append the marker *and* a six-byte empty Property Table: verified directly on
`RB___E_01955.jt`'s first shape segment — TOC length 668, elements + marker consume 662, tail =
`01 00 00 00 00 00` = Figure 79 with version 1 and count 0.

So the real convention (undocumented, identical in 9.5 and v10, and already recorded in
DESIGN.md) is that *every* segment carrying an element list ends this way. Two notes follow:

- This is the 9.5-side confirmation that the "6-byte shape tail" identification in DESIGN.md is
  the Figure-79 Property Table and not a coincidence — the same three fields, the same widths,
  the same version constant as the LSG segment's real 694-byte table.
- `ShapeLodDocument.kt:109`, `WireframeDocument.kt:131`, `MetaDataDocument.kt:117` raise
  `PROPERTY_TABLE_MISSING` when the stream ends after the element list. Per 9.5 Figure 81 that
  is the *document-conformant* shape, so a spec-perfect file collects a note whose name reads
  like a defect. The note is still worth having (it records a deviation from the universal
  producer convention), but the detail string should say so — "no trailing property table; the
  figure documents none, every observed producer writes an empty one" — rather than implying the
  file is broken.

### F6 — Confirmation (upgrades a guess to a citation): the Late Loaded Property Atom's 9.5 layout is exactly the v10 layout with an I16 version — no segment-length, no payload-length, no extra id. *(cost: none — no change)*

9.5 §7.2.1.2.7 / Figure 77 (p.106) is field-for-field the v10 Figure 76 (p.88): LEH, Base
Property Atom Data, version, `GUID: Segment ID`, `I32: Segment Type`, `I32: Payload Object ID`,
`I32: Reserved`. The **only** difference is the version width (`I16` in 9.5, `U8` in v10), and
the GUID type definition is identical in both (9.5 §7.1.1 p.23).

This matters because it is the hinge the brief names. The library reads it at
`LsgCodecs.kt:1448` with `readVersionNumber(V9) = I16` and the 41-byte fixture bodies close the
arithmetic with no slack: `1 (base type) + 4 (object id) + 2 (base atom version) + 4 (state
flags) + 2 (version) + 16 (GUID) + 4 + 4 + 4 = 41`, and the frame length says 41. A U8 version
reading would leave 2 unconsumed bytes and the strict tail check at `LsgCodecs.kt:1616` would
refuse it. So this is one of the rare places where the fixture *can* distinguish the readings,
and it agrees with the document. DESIGN.md delta 6 ("Version Number fields are I16 in v9") is
now spec-cited for this family, not just fixture-inferred.

Also confirmed against the document: 9.5 uses `Table 3: Segment Types` (p.30) with Shape =
6, Shape LOD0..LOD9 = 7..16, identical to v10 — so `ReadScene.kt:613`'s `SHAPE_SEGMENT_TYPES =
6..16` is correct for 9.5.

### F7 — Confirmation: the Property Table layout is genuinely generation-independent, `I16` version and all. *(cost: none)*

DESIGN.md delta 12 records this as fixture-inferred ("the fixture's 694-byte tail parses to
exactly 40 element property tables with zero leftover"). 9.5 Figure 79 (p.108) now says it
outright: `I16: Version Number`, `I32: Element Property Table Count`, and the repeat block —
byte-for-byte the v10 Figure 78 (p.90), including the anomaly that the version is I16 here while
every *element-local* version in 9.5's siblings is I16 and in v10's is U8. The Property Table is
not an element, has no Logical Element Header, and its version simply never narrowed.

Both fixtures parse to the last byte with an independent parser: RB = version 1, 40 tables, 46
entries, 694 B consumed of 694; KR360 = version 1, 19 tables, 20 entries, 318 B of 318. The
`readPropertyTable` bound at `LsgDocument.kt:169` (`count > remaining / 8`) is correct — the
minimum table is 4 B element id + 4 B terminator = 8 B.

**The brief's suspicion of a silent v9/v10 divergence here is not borne out.** The library's
trial-and-error discovery landed on the documented layout. `PROPERTY_TABLE_MISSING` /
`PROPERTY_TABLE_UNRECOGNIZED` have never fired on a 9.5 LSG segment and, per the document,
never should.

### F8 — Correction to the inventory diff: v10 did **not** gain a "Key PMI Property Atom" LSG atom type; 9.5 has the same eight LSG atom types v10 has. *(cost: none — inventory artifact)*

The brief flagged `Key PMI Property Atom` as a v10 addition. It is not an LSG Property Atom
Element and is not in §6.2/§7.2.1.2 at all. It is v10 §11.2.5.1.1 / Figure 118 (p.138), the
PMI *property* key-value sub-collection of the Meta Data segment — and 9.5 has the identical
unit under a slightly different name: §7.2.6.2.6.1.1 "PMI Property Atom" / Figure 168 (p.198).
v10 renamed it "Key PMI Property Atom" (its enclosing figure labels the two halves "Key Key PMI
Property Atom" / "Value Key PMI Property"; 9.5 labels them "Key PMI Property Atom" / "Value PMI
Property Atom"). A pure title change, and it belongs to the §11 / §7.2.6 package, not here.

Symmetrically: **9.5 has no property atom type v10 dropped.** All eight GUIDs are identical, and
9.5's Table 11 (p.303) omits the Vector4f GUID exactly as v10's Annex A does — the same
documentation bug in both editions, already recorded in `ObjectTypeIds.kt`.

### F9 — Confirmations with no action, recorded so the ledger is complete. *(cost: none)*

- **Base types 5 / 6 / 8.** 9.5 Table 4 (p.32) assigns Base Property Object = 5, JT Object
  Reference Object = 6, JT Late Loaded Property Object = 8 — precisely the `objectBaseType`
  values at `LsgCodecs.kt:1302`, `:1384`, `:1449`. Both fixtures show base type 5 on String and
  Date atoms and 8 on Late Loaded atoms.
- **State Flags.** Identical prose in both editions ("bits 0–7 free … all other bits are
  reserved"); the model carries the raw `UInt` (`LsgElements.kt:140`) and never masks, which is
  the lossless choice. Both fixtures write 0.
- **Element framing is generation-independent.** 9.5 Figure 8 (`I32: Element Length` = length of
  the Object Data, exclusive of the length field itself) + Figure 9 (`GUID` + `UChar` base type +
  `I32` object id) match v10 and match `decodeElementFrame`. Verified by walking both fixtures'
  full element streams with an independent parser.
- **MbString and GUID.** 9.5 §7.1.1 (p.23) defines both exactly as v10 does (I32 count + count ×
  U16; U32 + 2 × U16 + 8 × U8). `readMbString` / `readGuid` need no generation branch.
- **9.5 prose bugs, harmless.** The Date atom (p.105) and the Vector4f atom (p.107) both say
  their version number is "the only valid value **for Late Loaded Property Atom Element**" — a
  copy-paste from §7.2.1.2.7. The Table 11 extract on p.303 also has interleaved Date-atom prose
  bleeding into the Late Loaded row. Neither affects a byte.
