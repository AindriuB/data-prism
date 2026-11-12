# 303 — Diagnose and fix two MemberCardTest flakes

**Repo:** .
**Depends on:** none
**Owns:**
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/** and their tests,
  **only if outcome (b) below is proven**; list every such file in the PR body

## Goal
Two MemberCardTest tests (listed under "The failure") each failed once in CI and passed on rerun. For each, find
out whether the test or the product is wrong, prove it, and fix that one. Do not decide an outcome from a hypothesis; decide it from evidence.

## The failure
1. `aCardCarriesTheNoDocumentMarkerUntilAMainDocumentExistsThenTheDigestOfWhatItHolds`
- 2026-11-09, PR #227 (task 302: an MCP SDK logger pin, no Hazelcast code), CI run 42684474153, job
  129387467821, `build (21)`. `build (25)` passed on the same commit; `main` at 35e78b2c passed.
- `MemberCardTest.java:1830`: `expected: Digest[...] but was: Digest[...]`, two different digests.
- The test (`MemberCardTest.java:1818-1837`) pauses tool "p" through `HazelcastOversightState`, waits up to
  30 s for `cardOf(wrappers[0]).pauseDigest() != null`, then asserts that digest equals
  `state.document().heldDigest()`.
- Hypothesis, unproven: the wait accepts any non-null digest, so a card written from an earlier version of the
  held document (for example the empty-document digest, before the pause element lands) satisfies it, and the
  equality then races the next card write.

2. `aStartThatEndsWithAnErrorLeavesTheLiveSetStopsItsExecutorAndAllowsALaterWrapperToStart`
- 2026-11-09, PR #230 (task 303's own docs-only PR), CI run 42689565210, job 129405395196, `build (25)`.
  `build (21)` passed on the same commit.
- `MemberCardTest.java:1767`: `Expecting value to be true but was false`, at
  `assertThat(later.securityStatus().serving()).isTrue();`. After a start forced to fail through
  `ClusterSentinel.afterWriteStarted`, a new wrapper started on the same instance is not serving at once.
- Hypothesis, unproven: the serving check is read before the new wrapper's first card or instance verdict is in
  place.

## Context
- `docs/plan/design/cluster-security.md:271` (section 1.9, card v2: `pauseDigest` or the no-document marker,
  how the digest is computed) and `:397-410` (2.3.3: a failed pause still held locally moves the card digest;
  freshness and the three-leases-plus-skew staleness bound). These are the rules a card must keep.
- `MemberCards.java:478-491` (`Declarations.report`: a digest change sets the mark and submits a pass to the
  first live wrapper that accepts it; if none accepts, the mark waits for the next pass) and `:1060`
  (`tick`). `PauseDocument.java:893-904` is the only caller of `report`.
- `docs/plan/tasks/retired/228-signed-leased-pause-document.md` — the card/document design as built.
- `docs/plan/tasks/retired/296-fix-the-known-test-flakes-before-wave-8.md` and
  `docs/plan/tasks/retired/298-member-cards-looksoon-waits-for-start.md` (Outcome sections) — how earlier
  MemberCardTest flakes were reproduced (a temporary latch or delay forcing the interleaving) and how a
  non-reproduction was reported.
- `docs/plan/known-flakes.md:24` — a fixed MemberCardTest row; follow its form (symptom, cause, how
  reproduced, change).

## Decide between (separately for each test)
| Outcome | Proven when | Change |
|---|---|---|
| (a) test defect | Every digest the card shows in the failing window is one the held document really had at some point, and the card reaches `heldDigest()` within the design's bound. | Test only: wait until the card's digest **equals** `state.document().heldDigest()` (read inside the wait), then assert. |
| (b) product defect | A card stays behind the held document longer than the design allows (a lost or never-submitted pass after `report`, a digest that skips the mark, or a card digest the document never held). | Fix in `data-prism-hazelcast` main code, plus a test that fails without the fix. Keep the test's exact-equality assertion. |

## Acceptance
- [ ] AC1 For each test, the PR body states which outcome, (a) or (b), and the evidence for it: the sequence of digests the
  card showed against the held digest over time, and what produced each.
- [ ] AC2 Each failure is reproduced (a temporary latch, delay or loop that forces the interleaving, removed
  before merge or kept as a deterministic test), and the PR body says how. If it never reproduces, the PR body
  states the attempt count and method, and AC4's row is `open`, not `fixed`.
- [ ] AC3 In each test the assertion is not weakened to hide a real lag: no removed or loosened equality, no wait longer
  than 30 s, no `isNotNull`-only check, no retry of the whole test, no `@Disabled`. For test 1 under (a) the wait's
  predicate compares to `heldDigest()`; under (b) the original assertion stands. For test 2 under (a) the
  test waits for a stated condition, not a sleep; under (b) the original assertion stands.
- [ ] AC4 The PR body carries a proposed `docs/plan/known-flakes.md` row for each test, in the form of line 24,
  with its run (42684474153 for test 1, 42689565210 for test 2) and status `fixed` (cause and change named) or `open` (per AC2). The scribe adds it;
  the implementer does not edit `known-flakes.md`.
- [ ] AC5 Per test, under (b) only: a new or changed test fails on `origin/main` without the product fix and passes
  with it; the PR body names it and shows the failing run.
- [ ] AC6 `MemberCardTest` passes in full, and passes 20 consecutive runs of each target test with no failure.
- [ ] AC7 The module's suite passes.

## Checks
- `membercard-class` test — AC6, AC5
  run: `mvn -q -pl data-prism-hazelcast -am test -Dtest=MemberCardTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-hazelcast/target/surefire-reports/TEST-*MemberCardTest.xml`
  min-passing: 98  max-skipped: 0
- `target-repeat` command — AC6
  run: `for t in aCardCarriesTheNoDocumentMarkerUntilAMainDocumentExistsThenTheDigestOfWhatItHolds aStartThatEndsWithAnErrorLeavesTheLiveSetStopsItsExecutorAndAllowsALaterWrapperToStart; do for i in $(seq 20); do mvn -q -pl data-prism-hazelcast -am test -Dtest="MemberCardTest#$t" -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done; done`
- `hazelcast-module` test — AC7
  run: `mvn -q -pl data-prism-hazelcast -am test`
  report: `data-prism-hazelcast/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus any new tests  max-skipped: 0
- `scope` command — AC5 (under (a) only)
  run: `git diff --exit-code origin/main...HEAD -- data-prism-hazelcast/src/main`
- `evidence-and-assertion` read — AC1, AC2, AC3, AC4
  how: reviewer reads the PR body for the outcome, the digest timeline and the reproduction or attempt count;
  reads the test diff for any loosened assertion or longer wait; reads the proposed known-flakes row.

## Out of scope
- Other MemberCardTest flakes and other `known-flakes.md` rows, including the serialisation-filter row at :17.
- Card format, digest computation, the freshness gate and lease defaults, unless (b) is proven and the fix needs
  exactly that.
- Task 302's logger pin and any non-Hazelcast module.
- Running two `mvn` processes at once in the same worktree (298 found that contention, not a defect).

## Review round 1 — findings

Reviewed HEAD: af18a377d150f036436fcbcbb1c26e7180b4b5d9

- [STYLE] data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java:1826 — The new test `aCardBuiltBeforeAPauseLandsIsFollowedByACardWithTheDigestOfTheDocumentAfterIt` writes out the full package name for `CountDownLatch` and `TimeUnit` (lines 1826, 1827, 1833, 1841). The file already imports both types.
  route: implementer — use the simple names `CountDownLatch` and `TimeUnit`.
  rule: imports at MemberCardTest.java:46 and :49. The neighbouring tests use the simple names, e.g. `CountDownLatch entered = new CountDownLatch(1);` at :591 and :1271. The file writes a full package name only for types it does not import (:2226, :2358, :2779).
