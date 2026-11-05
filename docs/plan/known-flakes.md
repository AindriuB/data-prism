# Known test flakes

Shared list of tests that have failed without a code defect, with what is known. Status:

- `fixed`: the cause is found and the change is named.
- `open`: not fixed, or never reproduced and no cause found.
- `watch`: conditional; no failure seen.

A test that passed with no change is never `fixed`. Last updated by task 296 (2026-11-05). Symptoms come from CI
logs of failed runs (`gh run view <id> --log-failed`) unless a row says otherwise.

| Test | Symptom | Status |
|---|---|---|
| `ApprovalIdLockTest` (both tests) | `Expecting code to raise a throwable` at the first `assertThatThrownBy` (CI runs 40957997338, 40964404738, 40970678718). | `fixed`. Cause: the paused caller's id lock has a real lease of five times its timeout (1.5 s at 300 ms), and the test reached its first assertion after the lease had run out on a slow runner, so the second member's `create` took the lock instead of being refused. Reproduced by a 2 s sleep before that assertion. Change: the paused caller's store gets a 1 s timeout (5 s lease); the second member keeps 300 ms. |
| `CounterLeaseExpiryTest` | Named in PLAN; no failure in any CI log found, none in 20 runs, none in 5 runs with one CPU. | `open`: not reproduced, no cause found. Its waits are on state (`tryLock` 5 s, latches); the 100 ms timeout is the lease under test. |
| `ApprovalCreationTokenTest.start:45` | `no free loopback ports after 3 attempts` from `TestMembers.pair` (`FreePorts`). Logged as `port N claimed in this JVM`, a message the current `FreePorts` can no longer produce. | `open`: 294 changed `FreePorts` to draw only unclaimed ports; 20 passes since, no change made here. |
| `MemberCardTest`, serialisation-filter reject | `theStoresOfEveryWrapperRefuseWheneverTheInstanceGateRefuses`: `a member's serialisation filter rejected a class: PartitionStateVersionMismatchException` (run 40840988681, 2026-10-29). Not the :1283 test. | `open`: the class has been in `PrivacyCluster.ALLOWED_CLASSES` since 227 (13379f05); 10 passes since, no change made here. |
| `ApprovalSplitBrainHealTest:72` | `InterruptedException: sleep interrupted` in the merge wait after 371 s, with the same filter rejection of `PartitionStateVersionMismatchException` suppressed (run 40732311456, 2026-10-28). | `open`: the rejection predates the 227 allow-list change; 20 passes since, no change made here. |
| `HazelcastReservationTest:510` | `reserving.get(3, SECONDS)` timed out (run 40774428370, 2026-10-28, before 294). | `open`: 10 passes since; not reproduced. The 3 s bound is real time against a 20 s lock timeout, so a slow runner could still fail it. |
| `ClusterConfigurationTest:355` | `two_contexts_from_properties_alone_form_a_cluster_and_a_none_context_stays_alone` failed in a lambda (run 40076025709, 2026-10-24, before 294). | `open`: 10 passes since; not reproduced. |
| `SourceCircuitBreakerTrialTest` | `Timeout` and failed assertions across the class (runs 40741459588, 40760329935), the common pool starved by blocking requests. | `open`: `TestAsync` (added by 246, cb8c4b9e) runs each request on its own thread; 20 passes since, no change made here. |
| `HazelcastOversightTest` `pair()` | Checked the member count on one side only; no failure seen from it. | `open`: not reproduced, so not claimed as fixed. Hardened: `pair()` now waits on `TestMembers.settled` (both members, partitions safe). 10 passes, and 5 with one CPU. |
| `UnverifiedMemberTest` set-ups | `no free loopback ports` (FreePorts, see above) and a stale-pause-document failure (run 40952837585, fixed by 228). The set-ups waited on membership, not on partitions. | `open`: not reproduced, so not claimed as fixed. Hardened: the two multi-member set-ups now also wait on `isClusterSafe()` (`Fixture.awaitSafe`). 20 passes, and 5 with one CPU. |
| `MemberCardTest.aResumeOrEndScopeThroughAServingWrapperIsRefusedWhileAnotherWrapperOfTheInstanceDoesNotServe` | `Expecting code to raise a throwable` at :715 (CI run 42041178892). | `fixed`. Cause: the test's pauses wake the schedule, which submits a pass to a live wrapper's single-thread executor; if that is the faulty wrapper, the look reads the no-op `lookFault` and enters `read()` unlocked. The test then set the fault and hand-called `faulty.tick()` on the test thread, which wrote not-serving, and the older executor look wrote serving over it. Reproduced by a temporary latch holding the executor look while the test faulted on its own thread. Change: the fault and recovery legs run as a pass on the executor (`passOnExecutor`, FIFO, so every earlier look has finished) and wait on the status. The same overlap (a membership look queued on the executor) is closed in `aFailedLookNeverKeepsTheLastStatus` and `UnverifiedMemberTest.aFailedLookLeavesTheGraceClockWhereItWas`. |
| `SealedWritesTest` | `Port [41829] is already in use` (CI run 40967767280). | `open`: the port is outside the range `FreePorts` draws from, so a member there is not using `FreePorts`; no cause found, no change made. |
| `ClusterKeyMismatchTest` :117-170 | No failure seen. | `watch` |
| `ClusterCutoverTest` | No failure seen. | `watch` |
| Spike Q03, Q04, Q06, Q07, Q08, Q09, Q10, Q11 | No failure seen in 5 runs each. | `watch` |
