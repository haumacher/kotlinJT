# kotlinJT

A **Kotlin Multiplatform library for reading and writing JT files** (Jupiter Tessellation,
[ISO 14306](https://www.iso.org/standard/62770.html)) — Siemens' lightweight 3D representation
format, the de-facto visualization and DMU exchange format of automotive and aerospace PLM
(NX, Teamcenter, JT2Go, supplier data exchange).

There is no mature open implementation of JT on the JVM, let alone one that also runs in the
browser. This library aims to be that implementation: pure Kotlin in `commonMain`, with
platform specifics (compression codecs, byte I/O) behind small `expect`/`actual` seams.

## Architecture — three layers, three losslessness guarantees

| Layer | What it is | Guarantee |
|---|---|---|
| **0 — Segments & codecs** | The physical format: TOC, segment headers, element framing, compression codecs behind one interface | Byte-faithful access |
| **1 — Document model** | Typed, immutable JT concepts: LSG nodes (assembly / part / instance / shape), attributes (transforms, materials), property tables, shape LODs with tri-strip sets, PMI entities — plus **unknown segments preserved as opaque blobs** (id + bytes) | Lossless read → model → write, across spec versions |
| **2 — Scene façade** | A **format-agnostic scene**: named nodes, transforms, indexed-triangle meshes (one entry per LOD), simple materials, **units explicit in the model** | Convenient, honest by construction |

Nothing in the Layer-2 scene is JT-specific — deliberately. The same shape can feed a glTF
writer or a three.js viewer, and it is the seam through which
[ConstructIt](https://github.com/haumacher/constructit) (this library's sibling project) will
produce JT: one neutral scene handoff, many format consumers.

## Design principles

- **Round-trip tests are the acceptance spine.** Read a real file → write → read → models equal.
  The permanent fixtures are files that NX / JT2Go actually produced — a library that only
  round-trips its own output proves nothing.
- **Refusals speak.** A file with segments the library cannot decode loads with *named* notes —
  geometry is never silently dropped.
- **Write one version, read broadly.** The writer targets one spec version (JT v10 per
  ISO 14306:2017) and chooses simple encodings; the reader must handle what real producers emit,
  including the advanced-compressed meshes NX writes.
- **B-rep (JT B-rep / XT) is preserved opaquely, never interpreted.** Parasolid's representation
  is its own world; this library's honesty is the tessellation, the structure tree, the
  properties and the PMI.
- **`commonMain` stays platform-free.** zlib and friends live behind `expect`/`actual`
  (JVM: `java.util.zip`; JS: pako or equivalent).

## Status

Design phase. The scope and layering are tracked in
[issue #1](https://github.com/haumacher/kotlinJT/issues/1).

## Roadmap sketch (order of realism)

1. Layer 0 + Layer 1 reading of simple files; round-trip of what is read.
2. Writing: scene façade → tessellation + structure tree + names + materials.
3. Reading NX-produced files (advanced mesh codecs).
4. PMI (reading, then writing — ConstructIt's dimensions are real model objects and can map to
   JT PMI).
5. LOD *acceptance* is in from the start (the scene carries one mesh per LOD); LOD *generation*
   (decimation) is deliberately a different project.
