---
name: kotlinjt-workflow
description: The working method for developing kotlinJT — orchestrating sub-agents, adversarial probe reviews, the verification gate, fixture discipline for a binary format library, GitHub-issue triage, and the losslessness doctrines. Load at the start of any session doing feature or bug work on this repository.
---

# The kotlinJT working method

Adapted from the ConstructIt working method (the sibling project at
`/home/haui/devel/constructit`, whose skill this descends from) for a **binary format library**:
same pipeline, same review discipline, different traps and doctrines. The two documents it
complements, never duplicates: `README.md` (the architecture and design principles) and
**issue #1** (the scope contract — three layers, version/codec policy, the six design rules).
Read both before touching anything. When a `DESIGN.md` exists, it is the decision record and
this skill's references move there.

## 1. The operating loop

1. **Intake**: GitHub issues (`gh issue list -R haumacher/kotlinJT`) and the milestones in
   issue #1. Bugs outrank milestones. Triage every new issue with a diagnosis comment *before*
   fixing.
2. **Reproduce before dispatching.** A bug report about a file gets a headless reproduction
   first: load the file, print the segment/element inventory and the load notes. Get the
   offending file into `src/*/resources/fixtures/` (or a linked corpus) before anyone burns
   tokens — for a format library, **the fixture IS the reproduction**.
3. **Dispatch** one sub-agent per coherent package. Sequential by default; parallel only with
   disjoint file ownership stated in both briefs.
4. **Review with a novel probe** — non-negotiable, see §4.
5. **Gate** (§5), **commit** (§6), close the issue with the fixing commit + a comment naming the
   regression test/fixture.
6. **Before finishing: `gh issue list -R haumacher/kotlinJT`.** Keep in-flight plans mirrored in
   issues (or DESIGN.md once it exists) so a crashed session loses nothing.

Track packages with TaskCreate/TaskUpdate; statuses honest.

## 2. Agent briefs — what makes them work

Opus for feature/bug packages; Sonnet only for genuinely mechanical chores. Run in background
with a stall monitor (§2a). A good brief contains, in order:

- **Grounding**: "Read README.md and issue #1, then <the exact spec sections that govern this
  work — cite ISO 14306 / the Siemens JT reference by section number>. Study <the specific
  source files and the fixtures that are the behavioral contract>."
- **The task as design intent** with the load-bearing decisions made: what is preserved vs
  interpreted, what refuses vs carries opaquely, which layer owns it.
- **The SPEC_COVERAGE.md entries this package flips**, named explicitly. `done` requires a
  committed test tagged `// spec: Figure N` (or `// spec: §x.y`) named in the Evidence column.
  At review, diff the ledger against the delivery — an entry flipped without its test, or a
  delivery whose figures stay unflipped, is a rework.
- **Fixture files named explicitly** as required regression tests — and where a fixture from a
  real producer (NX, JT2Go) is needed but missing, the brief says to STOP and report rather
  than substitute self-written output.
- **Explicit acceptance tests**, exact where computable (byte counts, segment inventories,
  vertex counts, checksums; "read → write → read → models equal" belongs in almost every brief).
- **The standing rules block**: ALL existing tests green; `--rerun-tasks` on stale
  NoClassDefFoundError; ktlintFormat; do NOT commit; commonMain platform-free; **nothing
  half-done** — undecodable input produces *named* load notes, never silence; update the
  decision record.
- **Ask for a report**: mechanism chosen and why, alternatives rejected, cuts, test count
  before/after. A vague report predicts a vague delivery.

**Reworks go back to the same agent** via SendMessage with the failing probe and the demand:
*fix it generally, not to the probe*. If several rounds fail, question the architecture.

### 2a. Stalled agents

Arm a Monitor on the agent transcript's mtime (10-minute quiet threshold). **Verify before
acting on any monitor event** — the age reading lags the polling window, and a quiet transcript
with busy gradle daemons is a long test run, not a stall (check CPU + tree changes + mtime
yourself). On real silence: nudge-resume via SendMessage. If still frozen after a resume, take
the remainder over yourself; check `git status` and run its tests first — committed work is
usually further along than the last message suggests.

## 3. Concurrency

- **Never let two agents edit the same file.** Parallel briefs get an explicit FILE BOUNDARY
  paragraph in *both*.
- **Concurrent gradle runs corrupt incremental compilation.** Symptom: `NoClassDefFoundError`
  on inline-lambda classes in tests that were green. Never a real failure; `--rerun-tasks`
  resolves it. Put that in every brief.
- Don't run gradle while an agent is mid-edit; use a git worktree for side experiments.
- A commit made while an agent has uncommitted work must be **selective** (`git add <files>`),
  and check attribution on any shared file first.

## 4. The probe review (the quality mechanism)

After a delivery reports done and before committing: **write a test the agent never saw**,
composing the new capability with what exists. For a format library, good probes are:

- **Round-trip under mutation**: read a fixture, change one thing through the model (a name, a
  transform), write, re-read — everything *else* identical.
- **Cross-producer**: the feature tested on a fixture from a *different* producer than the one
  the agent developed against.
- **Hostile input**: truncated file, wrong segment length, unknown segment id — must produce a
  named note or a clean error, never a silent partial load, never an exception escaping the API.
- **The consumer's view**: drive the Layer-2 scene façade, not the layer the agent worked in —
  the seam is what the sibling project will actually call.

Keep passing probes as permanent tests (`<Feature>ProbeTest`).

**Expect your own probes to be wrong first.** Seed traps (grow this list from real defects, and
record each new one here the day it burns you):

- **Your own writer proves nothing**: a probe whose fixture was produced by this library's
  writer tests the writer's dialect, not the format. Real-producer fixtures or it isn't a probe.
- **Compression state**: comparing decompressed payloads byte-for-byte across producers is wrong
  (same model, different encodings are legal) — compare *models*, not bytes, except where the
  spec fixes bytes.
- **Endianness/alignment**: JT segments have their own byte-order rules; a probe that builds
  expected bytes by hand must follow the spec's, not the platform's.
- **Floating point**: mesh coordinates survive round-trip bit-exact only if the codec is
  lossless — know which codec the fixture used before asserting exact equality.
- **API layer**: Layer-1 preserves what Layer-2 abstracts; asserting a Layer-2 view shows
  everything Layer-1 holds is a category error (opaque segments are invisible up there, by
  design).

## 5. The verification gate (before every commit)

```bash
./gradlew ktlintFormat        # then check its output for errors
./gradlew check               # all targets: jvmTest, jsTest, ktlintCheck
```

All green or no commit. On weird failures: `--rerun-tasks` first, then a real look. Count
PASSED lines when in doubt; a clean rerun is the arbiter. (Update this block when the build
grows targets — browser tests, publishing checks.)

## 6. Committing

- House style: a poetic-but-precise one-line title stating the *principle*, then a paragraph of
  the why (never a bullet changelog), ending with the Co-Authored-By line. `Closes #N` for
  issues. Push to `origin main` every time.
- Agents don't commit; you do, after review. Decision-record updates ride the same commit as
  the code they describe.

## 7. Doctrines (enforce in every brief and review)

Carried from issue #1 and the sibling project's hard lessons; each is law:

- **Losslessness is layered**: Layer 0 byte-faithful; Layer 1 read→model→write preserves
  everything including **unknown segments as opaque blobs**; Layer 2 is honest about what it
  abstracts.
- **Refusals speak**: undecodable input yields *named* load notes; geometry is never silently
  dropped. Silence must always mean success.
- **Write one version, read broadly**: the writer targets JT v10 with the simplest legal
  encodings; the reader must handle what NX actually emits.
- **B-rep (JT/XT) is preserved opaquely, never interpreted.**
- **Fixtures from real producers are the acceptance spine** — committed, permanent, and named
  in the test that uses them. In-house round-trips alone prove nothing.
- **Units and up-axis explicit in the model**, never a folk convention.
- **`commonMain` stays platform-free**; codecs and byte I/O behind `expect`/`actual`.
- **No permanent non-goals**: deferred work is a *future extension* with the condition under
  which its time comes. Never point at an external tool as the alternative in a refusal.
- **Everything generic**: a fix shaped like the bug report isn't done.

## 8. Working with this user

They are an expert (mechanical/architectural CAD, software architecture, and they have read the
JT spec) testing continuously. Bug reports will come as JT files — commit them as fixtures and
embed the load expectation as the regression test. Their design sketches are usually complete
designs: restate crisply, adopt, credit in the decision record. When they overrule a recorded
decision, the reversal is documented with the old rationale quoted, not litigated. Answer
questions with the mechanism, then the plan, then queue position.
