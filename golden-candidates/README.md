# Golden candidates — files this library wrote, awaiting external validation

Everything in this directory is **produced by kotlinJT's own writer** (`writeJt`, issue #8) and
is *not* a golden yet. Per the fixture-policy amendment on
[issue #1](https://github.com/haumacher/kotlinJT/issues/1), writer output becomes a committed
golden only **after an external consumer opens it** — concretely: JT2Go (or NX / Teamcenter
Visualization) loads the file and shows the expected geometry, structure tree, names and
colours. A library that only round-trips its own output proves nothing.

The `.jt` files and their `*.inventory.txt` dumps are gitignored; this README is not.

## How they are produced

    ./gradlew jvmTest --tests 'de.haumacher.kotlinjt.write.GoldenCandidateWriterTest'

The test writes each candidate, parses it back and asserts scene equivalence, so a candidate
that appears here has at least passed this library's own read path with zero load notes.

| Candidate | What it exercises |
|---|---|
| `unit-cube.jt` | one closed part, per-face normals, one material, millimeter units |
| `two-part-assembly.jt` | a shared part instanced twice with different placements, two LOD tiers, a polyline set, two materials |
| `<fixture>-rewrite-finest.jt` | a discovered real-producer fixture re-authored from its scene, finest LOD only |
| `<fixture>-rewrite-all-lods.jt` | the same fixture with every LOD tier |

The fixture rewrites only appear when the corresponding fixture is present (the local tier is
IP-encumbered and gitignored, see `fixtures-local/README.md`).

## What to check in the viewer

1. The file opens without a warning or repair dialog.
2. The structure tree shows the part names (`JT_PROP_NAME`) and the instance placements.
3. The tessellation is complete and correctly oriented — no missing or inverted faces. The
   writer emits one topological component per triangle, each closed by a *cover face* that
   §7.1.4.1.3.1 tells readers to drop; a viewer that ignored those flags would show doubled,
   inward-facing triangles, which is exactly what this check catches.
4. Colours match the scene's materials, and measurements come out in millimeters.
5. Wireframe/polyline parts show their curves.

## When validation succeeds

Freeze a small candidate (the synthetic ones — never a customer file) as a committed golden
under `fixtures/`, with its expectation sidecar, and record the validating viewer and its
version in `DESIGN.md`. Until then this directory is scratch: regenerate it at will.
