# 13 — Test boundary 6: Hazelcast holds pseudonyms and subject ids, never a raw value

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-hazelcast/src/test/**
- data-prism-hazelcast/pom.xml

## Goal
Nothing checks that the cluster's maps hold only pseudonyms, subject ids and
counts. A raw sensitive value written into a distributed map is silent and
durable — not a build failure, not a request-time refusal, and not something a
later fix retrieves. Write the test that would catch it, and say in the test
itself what property it asserts and why that property is the right one.

## Context
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java:40-48 —
  the three map names: `dataprism.identity`, `dataprism.reidentification`,
  `dataprism.budget`.
- .../hazelcast/ScopeKeys.java — keys are NUL-separated strings, scope first.
  The separator is what makes a stored key decomposable in a test.
- .../hazelcast/CachingSyntheticValueSource.java:76-135 — the only writer of the
  identity and re-identification maps; `store` also writes the reverse entry.
- .../hazelcast/HazelcastScopeBudget.java and ScopeIdentityIndex.java — the other
  two touchers of cluster state.
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSourceTest.java:45-60 —
  how a test starts a loopback-only embedded member with no discovery. Reuse it.
- data-prism-pseudonymisation/src/main/java/.../HmacSyntheticGenerator.java — the
  real generator, if you take a test-scoped dependency on that module.
- docs/architecture.md:135-141 — boundary 6 and why it is the one worth a test
  soonest.
- docs/conventions.md:56-60 — no plaintext value feeds pseudonym generation; real
  personal data never enters a fixture; leak fixtures use invalid check digits.
- Baseline: `main` at d1460ec, green, 326 tests, `mvn -B verify` from the repo root.

## Establish the property before writing the test
The maps are `IMap<String, String>`, so there is no type-level guarantee to lean
on, and a test that greps for hardcoded fixture strings rots the moment the
fixtures change. Establish what is genuinely assertable and state it in the new
test's class javadoc, in a paragraph, with the reason it is the right property.

The property this task expects, which you may replace only with a stronger one
and a written justification:

> Every string in every distributed map is recomputable from the generator and the
> key's own components. For each identity-map entry, decomposing the key on the
> NUL separator gives `(scopeId, namespace, subjectId)`, and re-running the
> generator over exactly those inputs reproduces the stored value. For each
> re-identification entry, re-running the generator over the stored subject id
> reproduces the synthetic component of its key. Budget values parse as a long.
> A raw value cannot satisfy that, because the generator never sees one.

Recomputation, not string matching, is what stops this rotting: no expected
pseudonym is written into the test as a literal.

## Acceptance
- [ ] A new test in `data-prism-hazelcast/src/test/...` drives a realistic run —
      identity caching with the re-identification index enabled, and the budget
      exercised — then dumps every key and value of every map and asserts the
      stated property. The class javadoc states the property and why it is the
      right one.
- [ ] No expected pseudonym, hash or map value appears in the test as a string
      literal; expected values are recomputed at test time.
- [ ] **Map inventory guard.** The test enumerates the live member's distributed
      objects rather than the three name constants, and fails on any map it does
      not know how to decompose — so a fourth map added later cannot go
      unscanned. Proven: in a scratchpad clone with its own `target/`, write an
      entry to a fourth map, watch the test fail, revert.
- [ ] **Raw-value scan.** At least one run carries an obviously synthetic,
      distinctive sensitive value (invalid check digits, per
      `docs/conventions.md:58-60`) through the path that reaches the cluster, and
      the test asserts it appears in no key and no value of any map, case-
      insensitively. Proven able to fail: in the clone, make a writer store that
      raw value, watch the test fail, revert. Report both failure outputs.
- [ ] **Non-empty guard.** The test asserts the maps are non-empty before
      scanning them, so an empty or never-started cluster cannot pass it. This is
      the eighth cannot-fail assertion this repository would otherwise acquire.
- [ ] Every mutation proof is done in a scratchpad clone with its own `target/`,
      never in the worktree — `docs/conventions.md`, "Concurrent Maven
      verification", and the day task 07 lost to a contended `target/`.
- [ ] If the test takes a test-scoped dependency on `data-prism-pseudonymisation`
      to use the real generator, `data-prism-example`'s `ArchitectureTest` is
      unchanged and still passes; report that it was run. If it does not pass,
      use a deterministic stub generator instead and say why.
- [ ] `mvn -B verify` from the repo root is green. Report the new test count
      against the 326 baseline at d1460ec.
- [ ] The report gives the exact replacement prose for boundary 6's enforcement
      mark in `docs/architecture.md:135-141` and for the boundary-6 bullet in
      `PLAN.md:89-93`. `scribe` applies both at `/record` — this task edits no
      file under `docs/`.
- [ ] The branch reaches `main` only through a pull request whose head commit has
      a green `build` check. The implementer pushes the branch, opens the PR, and
      stops there: implementers never merge, never push to `main`, and never merge
      their own PR. Merging is `/record`'s, after `/verify`. See
      `docs/workflow.md` Phase 4.

## Out of scope
- Any change under `data-prism-hazelcast/src/main`. If the test finds a real
  boundary-6 violation, stop and report it — the fix is a successor task, not
  this one.
- The S10 re-identification operator surface. Boundary 5 stays prose-only by
  decision and is task 12's to note, not this task's to test.
- Boundaries 2, 3 and 7, and the `ArchitectureTest` file — task 12 owns those.
- Adding persistence, a `MapStore`, or TLS configuration to `PrivacyCluster` to
  make the test easier to write.
