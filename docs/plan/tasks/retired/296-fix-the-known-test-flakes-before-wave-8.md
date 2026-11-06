# 296 — Fix the known test flakes before wave 8

**Repo:** `.`
**Base branch:** `origin/main`. One PR, targeting `main`. Merges before wave 8 starts.
**Depends on:** none
**Owns:**
- under `data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/`: `ApprovalIdLockTest`, `CounterLeaseExpiryTest`, `ApprovalCreationTokenTest`, `MemberCardTest` (holds `Fixture`), `ApprovalSplitBrainHealTest`, `HazelcastReservationTest`, `HazelcastOversightTest` (holds `Mesh`, `tickCardsAndWait`), `UnverifiedMemberTest`, `ClusterKeyMismatchTest`, `ClusterCutoverTest`, `KeyedScopeEndTest` (helper only), `TestMembers`, `StalledInsert`, `FreePorts`, `spike/**`
- `data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java`
- under `data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/`: `SourceCircuitBreakerTrialTest`, `SourceFanOutDeadlineTest` (`MutableClock` only), `TestAsync`
- `docs/plan/known-flakes.md` (new) and `docs/conventions.md`, only the "Background tasks in subagent turns" section. Both are an owner-agreed exception to scribe-only writes.

## Goal
Remove the flakes that `docs/plan/PLAN.md` lists under wave 7 ("Flakes not covered by 294" and "Fixtures that do not settle"), so a red run in wave 8 means a real defect. Keep a shared list of what remains, and correct the convention on subagent wake-up. Test code only: no test gets weaker.

## Context
Likely cause, to verify per test and not assume: injected clocks mixed with real asynchronous card writes. Reuse `HazelcastOversightTest.tickCardsAndWait` (:576), `Mesh.tickCards` (:711), `KeyedScopeEndTest.awaitEveryPeerCardCoversTheStoredDocument` (:105), `TestMembers.settled`, and `MemberCardTest.Fixture.await`. `Mesh` and the `KeyedScopeEndTest` helper are private; widen or move them rather than copy them. `tasks/retired/294-*.md`, Outcome, has what is already done.

| Test | Read at filing (2026-11-04); a lead, not a finding |
|---|---|
| `ApprovalIdLockTest` | No clock or card call. Real 300 ms lock lease, 10 s `StalledInsert.awaitFree`. The card cause does not obviously fit. |
| `CounterLeaseExpiryTest` | No clock or card call. Real 100 ms timeout, 5 s `tryLock`. Same. |
| `ApprovalCreationTokenTest.start:45` | The `TestMembers.pair` call: a port or settle failure, not a test body. |
| `MemberCardTest`, serialisation-filter reject | Test not named in PLAN. Best candidate is `aPartitionStateMismatchRaisedByAnotherMemberReachesTheCallerThroughTheFilter` (:1283). Confirm from a CI log. |
| `ApprovalSplitBrainHealTest:72` | Line 72 is a `Thread.sleep` in the merge wait; the 60 s assertion is :74. Confirm the symptom. |
| `HazelcastReservationTest:510` | `reserving.get(3, SECONDS)`: a real 3 s bound on a reserve during a paused read. |
| `ClusterConfigurationTest:355` | Stale number: the line before 294 is now :376, in `two_contexts_from_properties_alone_form_a_cluster_and_a_none_context_stays_alone`. 294 may already have fixed it. |
| `SourceCircuitBreakerTrialTest` | Orchestration, no Hazelcast. `MutableClock` advanced beside a real fan-out in `aNeverStartedSourceLeavesTheBreakersTrialAvailable`. |
| `HazelcastOversightTest` `pair()` (:64) | Checks member count on one side only. No `TestMembers.settled`. |
| `UnverifiedMemberTest` set-ups (:63, :147) | Multi-member start. `Fixture.awaitMembers` waits on membership, not on `isClusterSafe()`. |
| `ClusterKeyMismatchTest` :117-170, `ClusterCutoverTest`, spike Q03, Q04, Q06 to Q11 | Conditional: change only if one flakes. Q03 and Q11 call `Spike.config`, not `Spike.configs`. |

Run one class at a time, one run after another. The repeat command, with a fresh JVM each run, is:

```
./mvnw -B -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false
```

Read each exit code directly. Hazelcast suites never run concurrently: not two classes, not two worktrees, and not beside the full gate. If another agent's build is on the machine, say so beside the result.

## Acceptance
- [ ] `git diff --stat origin/main...HEAD` shows no path under `src/main`, and none outside `Owns`.
- [ ] No listed test gains `@Disabled`, a retry or a rerun setting, and no assertion is removed. A raised time bound is allowed only with a comment stating why the old bound was wrong for the behaviour under test. New waits are on state, bounded and asserted (`docs/conventions.md`, "No sleeps").
- [ ] 20 consecutive passes each: `ApprovalIdLockTest`, `CounterLeaseExpiryTest`, `ApprovalCreationTokenTest`, `ApprovalSplitBrainHealTest`, `UnverifiedMemberTest`, `SourceCircuitBreakerTrialTest`.
- [ ] 10 consecutive passes each: `MemberCardTest`, `HazelcastOversightTest`, `HazelcastReservationTest`, `ClusterConfigurationTest`.
- [ ] 5 consecutive passes each: `ClusterKeyMismatchTest`, `ClusterCutoverTest`, and spike Q03, Q04, Q06, Q07, Q08, Q09, Q10, Q11. A class that fails moves into scope, and its count becomes 10.
- [ ] Every class changed here also passes 5 runs with `-XX:ActiveProcessorCount=1` added to the surefire `argLine` (keeping the project's own `argLine`), as 294 did.
- [ ] A failed run restarts that class's count. The PR gives the command and pass count per class, and every failure seen with its symptom.
- [ ] `docs/plan/known-flakes.md` exists with one row per test in the table above: test, symptom, status. Status is `fixed` (the row names the cause and the change), `open` (not fixed, or never reproduced with no cause found) or `watch` (conditional, no failure seen). A test that passes with no change is `open` or `watch`, never `fixed`. Nothing is dropped from the list.
- [ ] In `docs/conventions.md`, "Background tasks in subagent turns" no longer says subagents get no notification or are never woken. It says a subagent is woken when its own background task ends (tested 2026-11-04), and that an agent still pushes its work and records PARTIAL before it ends a turn to wait. The rest of the file is unchanged.
- [ ] After all repeat runs, once, at the end: `./mvnw -B verify` and `./mvnw -B -DskipTests -Prelease -Dgpg.skip package` both exit 0.

## Stop and report BLOCKED
- A flake turns out to be a production race, or needs any `src/main` change. Give the test, the evidence and the suspected class. Change nothing in `src/main`, and list the test as `open`.
- A hook or permission denial. Do not route around it, including with git plumbing.

## Out of scope
- `src/main`, and any test not in the table, even if it flakes: add it to `known-flakes.md` as `open` and stop there.
- Other sleep-polling cleanups, `ApprovalRaceTest` (228 M3) and the other wave-7 Lows.
- `PLAN.md`, `HISTORY.md` and retiring this task: the scribe's.
- Other *(kit)* sections, and the kit's own copy of the conventions.

## Rules
Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or credential files. No `git stash`, no `mvn install`, no force-push. No owner-workplace details in the repo.

## Outcome (2026-11-05, 0.8; PR #203)
Only one listed flake could be reproduced, and it is fixed. `ApprovalIdLockTest`: the paused caller's id lock has a real lease of 5x its timeout (1.5 s at 300 ms); on a slow runner the lease ran out before the first assertion, so the second member's `create` took the lock and `assertThatThrownBy` reported "Expecting code to raise a throwable". A 2 s sleep before that assertion reproduces it. The paused caller's store now uses a 1 s timeout (5 s lease), and the same sleep passes.

Hardened and not reproduced (listed `open` in `known-flakes.md`): `HazelcastOversightTest.pair()` waits on `TestMembers.settled`; the two multi-member `UnverifiedMemberTest` set-ups also wait on `isClusterSafe()` (`Fixture.awaitSafe`, in `MemberCardTest`). An extra round fixed `MemberCardTest.aResumeOrEndScopeThroughAServingWrapper...` (CI run 42041178892): a queued executor look overwrote a hand-called tick; reproduced with a latch, fixed in the test, and closed in two sibling tests. `CounterLeaseExpiryTest`, the earlier `FreePorts` failures and the serialisation-filter failure were not reproduced and are `open`; `SealedWritesTest` (a port outside `FreePorts`) is `open`. No test gained `@Disabled` or a retry, no assertion was removed, and there is no `src/main` change. `docs/conventions.md` "Background tasks in subagent turns" now says a subagent is woken by its own background task and pushes a `PARTIAL` commit before ending a turn to wait.

Evidence: fresh JVM each, one class at a time. 20/20 for `ApprovalIdLockTest`, `CounterLeaseExpiryTest`, `ApprovalCreationTokenTest`, `ApprovalSplitBrainHealTest`, `UnverifiedMemberTest` and `SourceCircuitBreakerTrialTest`; 10/10 for `MemberCardTest`, `HazelcastOversightTest`, `HazelcastReservationTest` and `ClusterConfigurationTest`; 5/5 for the key-mismatch, cutover and Q03, Q04, Q06 to Q11 classes; 5/5 at `-XX:ActiveProcessorCount=1` for every changed class. `./mvnw -B verify` and the release-profile package both exit 0.
