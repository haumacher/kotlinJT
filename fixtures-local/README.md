# fixtures-local — real-producer JT files (never committed)

Drop real-producer `.jt` files here. Everything in this directory except this README is
gitignored, because real fixtures are typically IP-encumbered customer data — **including
their file names** (part numbers), which is why the committed test code must never name a
local fixture.

The test suite auto-discovers every `*.jt` file here and runs the standard battery:

1. **Loads cleanly** — no unexpected load notes; every note that does appear is *named*.
2. **Inventory matches** — segment/element counts and checksums equal the sidecar
   expectations file (`<name>.expected.json`, created on first run, reviewed by a human,
   then authoritative — the sidecar lives here and is equally gitignored).
3. **Round-trip** — read → model → write → read → models equal.

When this directory holds no `.jt` files the suite **skips visibly with a count** — it never
silently passes. On a machine that has fixtures, the pre-commit verification gate includes
this suite; CI cannot see it and does not pretend to.

Committed tests use synthetic goldens instead (see the amendment in
[issue #1](https://github.com/haumacher/kotlinJT/issues/1)).
