# 308 — Fix the HazelcastOversightTest grace-clock race

**Repo:** .
**Depends on:** none
**Decisions:** none. The cause is found; the fix is test-only. A product change needs a new owner decision.
**Owns:**
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java

## Goal
`HazelcastOversightTest.afterTheFirstWrapperClosesBeforeItsRepairTickASecondOneRepairsTheStoredDocumentAndUsesItsOwnDependencies` failed once in CI and passed on rerun. Make it deterministic without weakening it.

## The failure
- `Expecting value to be false but was true` at `HazelcastOversightTest.java:1666`, `build (21)`, CI run 43027813160, PR #243, 2026-11-11; the rerun passed.
- Cause (found): the grace clock starts at the first look that sees the intruder (`MemberCards.java:1286-1290`). The only look before the test's `GRACE+1s` advance is the membership-event `lookSoon` on the executor, which the test never waits for, so the clock can start after the advance. Reproduced 1 in 50 locally (JDK 21), and 3 of 3 by holding the executor.
- Not a product defect: production looks are serialised on one executor at real time (OD-W6-7, OD-W6-8, OD-W6-9).

## Acceptance
- [ ] AC1 Before the clock advance, the test runs `tickCardsAndWait(second)` and asserts `unverifiedCount()==1`; the rest of the test is unchanged and no assertion is loosened, no retry, sleep or timeout is added.
- [ ] AC2 The forced interleaving (hold the executor so the membership look has not run) fails on the base commit and passes after the fix; both runs are in the Outcome. The temporary hold is removed before merge or kept as a deterministic test.
- [ ] AC3 The test class passes in full and 20 consecutive runs of the class pass.
- [ ] AC4 Only the owned file changes. The proposed `known-flakes.md` row is in the PR body (the scribe adds it; the row exists in the file as `open` and is set `fixed` at /record).

## Checks
- `oversight-class` test — AC1, AC3
  run: `./mvnw -B -q -pl data-prism-hazelcast -am test -Dtest=HazelcastOversightTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-hazelcast/target/surefire-reports/TEST-*HazelcastOversightTest.xml`
  min-passing: the class's count on `origin/main`  max-skipped: 0
- `class-repeat` command — AC3
  run: `for i in $(seq 20); do ./mvnw -B -q -pl data-prism-hazelcast -am test -Dtest=HazelcastOversightTest -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done`
- `scope` command — AC4
  run: `test "$(git diff --name-only origin/main...HEAD)" = "data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java"`
- `assertion-intact` read — AC1, AC2
  how: reviewer reads the diff for a loosened assertion, caught exception, retry or sleep.

## Out of scope
Any product code (`MemberCards.java`); other tests; editing `known-flakes.md`; two `mvn` processes at once in one worktree.
