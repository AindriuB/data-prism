# 294 — Fix the test port picker, the Q01 spike address choice and three cluster-test timing flakes

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main`. One PR for this task, targeting `main`.
**Depends on:** none
**Wave:** 7 (filed 2026-10-29; the owner-approved harness fix from the wave-6 decisions, PLAN "Harness fix approved").
- The Owns are disjoint from every wave-7 brief in flight. 211 owns Spring audit and cluster-key files, not `ClusterConfigurationTest.java`. 247 owns orchestration, MCP and the `new DefaultContextOrchestrator(` call sites. 256 owns validation. 292 owns core audit. 228 owns `HazelcastOversightTest.java`, `PauseDocumentTest.java`, `PauseSplitBrainTest.java` and `OversightMapsBoundary.java`, and reads `TestMembers.java` without editing it. This task edits none of 228's files.
- 287 (wave 8) owns the existing hazelcast tests and `TestMembers.java` for its trusted-network declaration. 287 therefore depends on 294 for those files, and starts after 294 has merged.
- Test code only. Under D-149-A, if a clean fix needs `src/main` or a POM, stop and report. Do not widen the Owns.

**Owns:**
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/FreePorts.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/FreePortsTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/TestMembers.java *(`pair` and a private settle helper only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/SpikePorts.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/Q01InboundAllowlistSpikeTest.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/package-info.java *(row 1 of the answers table only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/seal/SealedWritesTest.java *(`freePort` and its two call sites only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalRaceTest.java *(the `RACE_LOCK_TIMEOUT` Javadoc; the await in `start` may be removed if `pair` now does the same)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java *(the `Fixture` card-write bound, and the tests that rely on it running out)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java *(`freePort` and its comment, :443-487 at ca7e9b0c, only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java *(the port-claim method and its comment, around :56-100 at ca7e9b0c, only)*

## Goal
The cluster tests fail at random in CI for reasons that have nothing to do with the code under test, and every red run costs a rerun and a triage. This task removes five such causes.
1. The port picker gives up after 3 draws that hit ports this same JVM already holds.
2. The Q01 spike picks whichever non-loopback address it enumerates first, even when that address cannot carry a join.
3. Tests write under the 2 s cluster-lock default while `TestMembers.pair` is still migrating partitions.
4. `MemberCardTest`'s 1 s card-write bound is shorter than a slow write on one CPU.
5. `ApprovalRaceTest` has one wrong Javadoc line.

## Root causes found while filing (verify, do not re-derive)
**Port picker.** The CI log of record/0.8-wave6 run 40890295207, attempt 1, shows the cause. `FreePorts.java:82` threw `no free loopback ports after 3 attempts`, caused by `IOException: port 22064 claimed in this JVM` from `FreePorts.java:48`. It was not a foreign bind. By that point the hazelcast module's single surefire JVM had started members on 583 distinct ports, and every claim is held until the JVM exits (comment at `FreePorts.java:29-32`). Each draw takes a random base in the 12,000-port range `20000 + nextInt(12000)` and fails if any port of the run is already held. That is about 2k/12000 per draw for a pair, and more for the 3- to 5-port runs that `UnverifiedMemberTest` takes. Three draws in a row fail often enough, once a few hundred ports are held, to break roughly one module run in ten. More attempts would only make it rarer. They would also break D-149-C, which allows a retry "only on a bind failure of the chosen port"; a port this JVM already holds is not a bind failure.

Bind-and-hold until the member binds does not work here. Hazelcast opens its own server socket, with `port-auto-increment` off, and has no public way to adopt a socket that is already bound. A held socket would make the member's own bind fail. The cross-JVM `FileLock` claim already does the holding that matters.

The same pattern has four copies:
- `FreePorts` (hazelcast);
- `SpikePorts` (the spike package, range 14000-19000);
- `ClusterConfigurationTest` (Spring);
- `OperatorHarness` (server). Its `continue` on a claimed port uses up an attempt; that is PLAN note (q).

`SealedWritesTest.freePort()` uses `ServerSocket(0)`, which hands back an ephemeral port and then releases it. Run 40393627542 failed at `SealedWritesTest.start:59`: "Port [37845] is already in use and auto-increment is disabled".

**Q01.** On the owner's Mac (2026-10-28, task 208's verify), `aMemberOutsideTheInterfacesIsAccepted:67` failed after 21.18 s. The await timed out at 20 s with one member, and the joiner's source was `192.168.148.0`. That address belongs to a VM or bridge interface (it is a `.0` address), not to the host's primary interface. `nonLoopbackAddress()` returns the first up, non-loopback, non-virtual IPv4 address in `NetworkInterface` enumeration order. That order changes as VM and VPN interfaces come and go; on the same machine today it returns `bridge100` 192.168.139.3, ahead of `en0`. The test passed 6 of 6 runs in isolation on 2026-10-29 and passes in every CI attempt checked; its failure depends on the host's network, not on timing. The failure message also reads backwards: it says "...yet the second ... joined" when the second did not join.

**Settling after `pair`.** `TestMembers.pair` returns as soon as the second member has started. Partitions are still migrating at that point, and a lock taken then can wait past `ClusterLocks.DEFAULT_TIMEOUT` (2 s, `ClusterLocks.java:24`). Run 40925073599 attempt 1 failed this way: `ApprovalRaceTest.exactlyOneCallConsumesAnApproval:78` threw `CLUSTER_LOCK_TIMEOUT`. PR #187 fixed only `ApprovalRaceTest` and `ClusterLockTimeoutTest`, each with its own `isClusterSafe()` await. 15 other test files call `TestMembers.pair`.

**MemberCardTest.** `Fixture.environment` passes a 1 s `cardWriteTimeout` (`MemberCardTest.java:146-150`), against the production default `PrivacyCluster.DEFAULT_CARD_WRITE_TIMEOUT` of 5 s. On one CPU, `addPurposeKey`'s write can outlive the 1 s wait. The write is still queued, so the next `publishNow` throws `a member card write is still outstanding` (`MemberCards.java:537`). That is the failure seen at `closingOneWrapperKeepsTheDeclarationsOfTheOtherAndClosingTheLastStopsTheCard:889` with ActiveProcessorCount=1.

## Context
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/FreePorts.java:29-83 — the picker to fix; `spike/SpikePorts.java` is the same code.
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java:443-487 and data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java:56-100 — the other two copies.
- docs/plan/PLAN.md, D-149-A, D-149-B and D-149-C (around :977-979), and notes (o) to (r) (around :1014-1017). Proof on macOS alone is enough for acceptance (D-149-B).
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalRaceTest.java:38-53 and ClusterLockTimeoutTest.java:49 — the settle await that PR #187 added, which this task moves into `TestMembers.pair`.
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java:76-210 (`Fixture`), :641-654 (`twoWrappers`) and :982 (`SHORT_WRITE_BOUND`, the tests that pin a short bound on purpose).
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java:519-545 and PrivacyCluster.java:188-210 — read only.
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/Spike.java:166-176 — `await` returns a boolean; read only.
- docs/conventions.md — tests state observed behaviour in their names; no real addresses or personal data in fixtures.

## Acceptance
**Port picker (all four copies, the same rule and the same comment):**
- [ ] The run is chosen only among ports this JVM has not claimed. A draw that overlaps this JVM's own claims is re-drawn, or the scan moves on, without using an attempt. This search is bounded. When no unclaimed run of `count` ports is left in the range, the picker throws `IllegalStateException` with a message that names the cause, for example "no unclaimed run of 3 ports left in this JVM". It does not throw "after 3 attempts".
- [ ] Only these use up one of the 3 attempts (D-149-C): a failed loopback or wildcard probe bind, or a `FileLock` held by another JVM. The final message and its cause still say which happened.
- [ ] The `OperatorHarness` re-pick of a claimed port no longer uses an attempt (PLAN note (q)).
- [ ] No copy holds a socket open past the probe. The cross-JVM `FileLock` claim, held until the JVM exits, is unchanged.
- [ ] `FreePortsTest` (new) uses a package-private seam that takes the set of claimed ports, the range and a `Random`, so no socket is opened. It covers three cases:
  - with every port claimed except one run of `count`, the seam returns that run;
  - with no run left, it throws the "no unclaimed run" error;
  - with 1,000 ports claimed out of a 12,000-port range, 10,000 picks of 5 ports all succeed with a fixed seed.

  The PR states that the third case fails on the base logic: re-implemented in the test as random-base-plus-3-draws with the same seed, it gives at least one failure.
- [ ] `SealedWritesTest` takes its two ports from `FreePorts.consecutive(2)`, not `ServerSocket(0)`. `FreePorts` and `consecutive` may become `public` for the `seal` package, and nothing else in `FreePorts` changes visibility.

**Q01:**
- [ ] The joiner's source address is chosen like this:
  1. First, the local address of a `DatagramSocket` connected to `192.0.2.1` (TEST-NET-1; a UDP connect sends no packet). That is the primary route's source.
  2. If there is none, or it fails the probe in step 3, fall back to the IPv4 addresses that are up, not loopback, not virtual, not point-to-point and not link-local, taken in a fixed order (by interface index).
  3. Keep the first address that passes a self-connect probe: a server socket on the wildcard address, and a client socket bound to the candidate connecting to `candidate:port` within 2 s.
  4. If no address passes, the test is skipped through `assumeTrue`, with a message that says no self-reachable non-loopback IPv4 address was found.
- [ ] The assertion message reads as the expectation, for example "the first member lists only 127.0.0.1; the second, arriving from %s, should have joined". It names the chosen interface as well as the address.
- [ ] Nothing retries the join or the assertion (D-149-C). The 20 s await stays.
- [ ] Row 1 of `spike/package-info.java` says the test is skipped on a host with no self-reachable non-loopback IPv4 address.

**Settling after `pair`:**
- [ ] `TestMembers.pair` returns only when both members list 2 members and `isClusterSafe()` is true on both, within 30 s. Otherwise it shuts both members down and throws `IllegalStateException("the pair did not settle within 30 s")`. This is a precondition of the fixture, not a retried assertion.
- [ ] The PR lists every caller of `TestMembers.pair` on the base (`rg -l 'TestMembers\.pair\(' data-prism-hazelcast/src/test`). For each, it says whether the caller writes under a cluster lock right after `pair` returns. If any caller needs an unsettled pair, stop and report; do not add an opt-out on your own.
- [ ] The PR lists the multi-member set-ups that do not go through `TestMembers.pair` and that take a `ClusterLocks` lock right after start, each with a file and line. Examples are `HazelcastOversightTest`'s private `pair()` (228's file), `ApprovalStoreContractTest:328` and `MemberCardTest`'s `pair(keysA, keysB)`. This task edits only those in its Owns: `MemberCardTest` and `SealedWritesTest`. The rest are reported as follow-ups.
- [ ] `ApprovalRaceTest`'s `RACE_LOCK_TIMEOUT` Javadoc says the bound is required: `exactlyOneCallConsumesAnApprovalWhileTheHolderStallsPastTheDefaultLockBound` holds the lock past `ClusterLocks.DEFAULT_TIMEOUT` + 1 s, and the waiter must still get it. The Javadoc no longer says it "should not be needed".

**MemberCardTest:**
- [ ] `Fixture.environment` uses `PrivacyCluster.DEFAULT_CARD_WRITE_TIMEOUT` as its card-write bound. Every test that needs a bound to run out passes its own short bound explicitly, as the `SHORT_WRITE_BOUND` tests do. The PR names any test that had relied on the 1 s default.
- [ ] `MemberCardTest` passes 5 runs in a row with `-XX:ActiveProcessorCount=1` added to the surefire `argLine` (keep the project's own `argLine`). The PR gives the command and the 5 results.

**Whole task:**
- [ ] `./mvnw -B verify` passes on macOS. In addition, `./mvnw -B -pl data-prism-hazelcast -am verify` passes 3 times in a row, with no `no free loopback ports`, `CLUSTER_LOCK_TIMEOUT` or `member card write is still outstanding` failure in any run. The PR quotes each run's `Tests run:` line for the module (D-149-B).
- [ ] `git diff --stat origin/main` lists only files in the Owns, and nothing under `src/main` or in a POM.

## Out of scope
- Making the four pickers one shared class. That needs a shared test-support artifact across three modules: a follow-up, not this task.
- `QuickstartSmokeIT.freePort()` and `load-test`'s `ServerProcess`, which use `ServerSocket(0)` (PLAN note (p)).
- `HazelcastOversightTest`'s private `pair()`: 228 owns the file in wave 7. Report it as a follow-up for after 228.
- Raising `ClusterLocks.DEFAULT_TIMEOUT` or `PrivacyCluster.DEFAULT_CARD_WRITE_TIMEOUT`, or any change to `MemberCards`, which are production code (D-149-A).
- These other flakes seen in the CI history while filing. Report them, do not fix them:
  - `ApprovalCreationTokenTest.start:45`: "a member card write did not finish in time" inside `TestMembers.pair` at the 5 s default (run 40890295207 attempt 2);
  - `MemberCardTest.theStoresOfEveryWrapperRefuseWheneverTheInstanceGateRefuses`: the serialisation filter rejected `PartitionStateVersionMismatchException` (run 40840988681);
  - `ApprovalSplitBrainHealTest:72`: "sleep interrupted" (runs 40732311456, 40685958907 and 40077663459);
  - `HazelcastReservationTest:510`: timeout (run 40774428370);
  - `ClusterConfigurationTest:355` (run 40076025709);
  - `SourceCircuitBreakerTrialTest` in orchestration (runs 40760329935 and 40741459588).
- Deleting the lock files in `java.io.tmpdir/dataprism-test-ports` (PLAN note (o)).
