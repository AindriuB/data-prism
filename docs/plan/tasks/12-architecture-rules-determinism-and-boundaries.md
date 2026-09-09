# 12 — Extend the determinism rule to `Instant.now()`, and settle boundaries 2 and 3

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ArchitectureTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/boundary/** *(new; create only if the boundary-3 judgement calls for a behavioural test)*

## Goal
`ArchitectureTest.pseudonymisationIsDeterministic` bans `Random`, `SecureRandom`,
`UUID.randomUUID` and `System.currentTimeMillis` from the pseudonymisation path
but not `Instant.now()`, which `docs/conventions.md:54` bans in the same sentence.
Add it. Then decide honestly whether boundaries 2 and 3 in `docs/architecture.md`
can be enforced by a test at all, and enforce only the ones that genuinely can.

## Context
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ArchitectureTest.java —
  the existing rules and the shape to follow, including
  `onlyDesignatedClassesCreateMappers` (boundary 1) and
  `coreDoesNotDependOnOuterLayers` (boundary 8).
- docs/conventions.md:54-55 — "No `Random`, `UUID.randomUUID()`, `Instant.now()`
  or map iteration order in a pseudonymisation path."
- docs/architecture.md:106-155 — the numbered boundaries and the enforcement
  audit. Boundary 2 (the engine operates on a data tree, not the Java object
  graph; reflective field mutation and `Unsafe` are not options) and boundary 3
  (a response failing validation is not returned, no log-and-continue path) are
  both marked *prose only*.
- docs/conventions.md:116-147 — seven cannot-fail assertions counted so far. An
  ArchUnit rule whose subject set is empty is the same bug in a new costume.
- Baseline: `main` at d1460ec, green, 326 tests, `mvn -B verify` from the repo root.

## Acceptance
- [ ] `pseudonymisationIsDeterministic` also rejects a call to `Instant.now()`
      (the no-argument form; `Instant.now(Clock)` stays legal, since
      `docs/conventions.md:103-104` requires an injected `Clock`).
- [ ] The extended rule is proven able to fire: in a scratchpad clone with its
      own `target/`, add an `Instant.now()` call to a class on the
      pseudonymisation path, run the rule, record the failure naming that class,
      revert. The report quotes the failure message. Never mutate the worktree.
- [ ] Every ArchUnit rule this task adds or edits fails when its subject set is
      empty — `allowEmptyShould(false)`, or an explicit assertion that the set of
      classes the rule matched is non-empty. State which mechanism was used.
- [ ] **Boundary 2.** Either a rule banning `sun.misc.Unsafe` and reflective
      field mutation (`Field#set*`, `setAccessible`, `VarHandle`/`MethodHandles`
      field access) in the privacy modules is added and proven able to fire by
      the same clone-mutate-revert method — or the report states concretely why
      it cannot be written without false positives or without matching nothing,
      and no rule is added. A rule that passes because it matches nothing is a
      worse outcome than the prose.
- [ ] **Boundary 3.** The report states a judgement, with reasons: expressible as
      an ArchUnit rule, expressible only as a behavioural test, or not honestly
      enforceable. If behavioural, the test goes in a new file under
      `.../example/boundary/`, asserts that a response failing validation is
      refused rather than returned, and is proven able to fail by introducing a
      log-and-continue path in a scratchpad clone. If not enforceable, add
      nothing and say so plainly.
- [ ] **Boundary 5** is confirmed in the report as intentionally prose-only:
      re-identification as a separate application cannot be enforced until the
      module exists, and S10 is deferred past V1 by decision. No test is written
      for it.
- [ ] `mvn -B verify` from the repo root is green. Report the new test count
      against the 326 baseline at d1460ec and what accounts for the difference.
- [ ] The report gives the exact replacement prose for the enforcement marks on
      boundaries 2, 3 and 5 in `docs/architecture.md:106-155`, reflecting what
      this task actually landed. `scribe` applies it at `/record` — this task
      edits no file under `docs/`.
- [ ] The branch reaches `main` only through a pull request whose head commit has
      a green `build` check. The implementer pushes the branch, opens the PR, and
      stops there: implementers never merge, never push to `main`, and never merge
      their own PR. Merging is `/record`'s, after `/verify`. See
      `docs/workflow.md` Phase 4.

## Out of scope
- Boundary 6 (Hazelcast holding no raw sensitive value) — task 13 owns it.
- Boundary 7's unscanned half (metric labels, trace attributes, audit records)
  and boundary 8's missing domain-type scan. Both stay open; note them if you
  touch the audit, do not fix them here.
- `EndToEndTest.java`, `WorkedExampleTest.java`, `ShippedDefaultsTest.java`,
  `StdioProductionProfileTest.java` and everything under `example/src/main` —
  these must be unchanged. If a new rule fails against production code, report
  the violation rather than editing the code to satisfy the rule.
- Widening the determinism rule beyond `Instant.now()` (`LocalDate.now`,
  `System.nanoTime`, and similar) unless a violation is actually found; say so
  in the report instead.
