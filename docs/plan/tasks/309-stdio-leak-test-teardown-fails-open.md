# 309 — Stdio leak tests: teardown can truncate stderr, so a leak check can pass (fail open)

**Repo:** .
**Depends on:** none
**Priority:** wanted before the 0.8.0 cut. These tests are the guard for 304's guarantee that no client content reaches an SDK log line.
**Decisions:** none. Test-only. No product leak was found: the SDK logs raw content only when an operator enables `io.modelcontextprotocol` at DEBUG or INFO (304 residual, `docs/configuration.md:882-903`).
**Owns:**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioParseErrorLeakTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioSdkLogPinTest.java

## Goal
`StdioParseErrorLeakTest.errorWrongType` failed once in CI and passed on rerun, and the same teardown makes every `doesNotContain(SENTINEL)` check in both classes able to pass on truncated stderr. Fix the teardown so a leak check reads stderr to the end or fails.

## The failure
- `assertGuardRefused:100`, `build (25)`, CI run 43049590251 attempt 1, PR #243, 2026-11-11; the rerun passed. Not related to 305.
- Cause (found): the tests call `Process.destroy()`, which on JDK 25 also closes the parent's stderr stream (`ProcessImpl.destroy`). Unread stderr is lost and the drain swallows `Stream closed`. Reproduced 2 in 200 in a Linux JDK 25 container with one CPU, and 5 of 5 with a forced 100 ms stderr lag; the fix below passes 5 of 5.
- Security relevance: the same teardown is in `StdioSdkLogPinTest.runChild` (:97-102) and `StdioParseErrorLeakTest.assertNoLeak` (:110-115). A `doesNotContain(SENTINEL)` check there can pass because the bytes that would hold the sentinel were never read.

## Acceptance
- [ ] AC1 Teardown in all three places: `toHandle().destroy()`, `waitFor` up to 10 s with a `destroyForcibly` fallback, then join both reader threads to end of stream, and fail the test if either reader ended on an `IOException`.
- [ ] AC2 Forced reproduction: with a 100 ms lag injected into the stderr reader, `errorWrongType` fails on the base commit and passes after the fix; both runs are in the Outcome.
- [ ] AC3 A mutation shows the leak checks can fail: with the guard's output temporarily made to echo the sentinel on stderr, each of the three checks fails with the new teardown. Removed before merge.
- [ ] AC4 No assertion is weakened; no retry, no sleep standing in for a condition, no timeout above the existing ones except the stated 10 s `waitFor`.
- [ ] AC5 Both classes pass in full, and 20 consecutive runs pass. Only the owned files change.
- [ ] AC6 The proposed `known-flakes.md` row is in the PR body (the scribe sets the existing `open` row to `fixed`).

## Checks
- `stdio-classes` test — AC1, AC5
  run: `./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest='StdioParseErrorLeakTest,StdioSdkLogPinTest' -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*Stdio*.xml`
  min-passing: the two classes' counts on `origin/main`  max-skipped: 0
- `class-repeat` command — AC5
  run: `for i in $(seq 20); do ./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest='StdioParseErrorLeakTest,StdioSdkLogPinTest' -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done`
- `scope` command — AC5
  run: `git diff --name-only origin/main...HEAD | grep -vE 'example/(StdioParseErrorLeakTest|StdioSdkLogPinTest)\.java$' | wc -l | grep -qx 0`
- `fail-closed-teardown` read — AC1, AC3, AC4
  how: reviewer reads the Outcome's mutation run and the diff: every place that reads child stderr joins to end of stream and fails on a reader `IOException`; no assertion is loosened.

## Out of scope
Product code; the SDK logger pin; other tests; editing `known-flakes.md`; two `mvn` processes at once in one worktree.
