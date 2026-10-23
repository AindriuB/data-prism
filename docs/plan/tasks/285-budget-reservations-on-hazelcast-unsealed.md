# 285 — Implement read-budget reservations on `HazelcastScopeBudget`, unsealed

Filed 2026-10-23 for owner decision (b). Task 226, which owned `reserve` and `release` on `HazelcastScopeBudget` under core OD-08-1 (a), is deferred past 1.0 with the other sealed-store briefs. Core 246 still needs the reservation on both budget implementations. This brief carries the minimum out of 226: atomic, unsealed reservations with the same counting semantics. It has no sealing, no local floor and no merge policy.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 223 and 243 (both on `main`).
**Depends on:** 243 (core: `ScopeBudget.reserve`, `release`, `scopeReads`, `ReadReservation` and the in-memory reference), 223 (keyed map keys and bounded locks through `ScopeKeys` and `ClusterLocks`)
**Wave:** 5
**Decisions:** core OD-08-1 (a), decided 2026-10-20, now met by this brief instead of 226. OD-215-11 (the counter merge policy) and OD-215-15 (the local floor over the scope total) stay with 226 and are deferred.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudget.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeKeys.java *(insertions only: the scope-total key, if the chosen shape needs one)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/CounterMapsBoundary.java *(the scope-total key form only, if one is added)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudgetTest.java *(new cases only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastReservationTest.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/ScopeBudget.java *(the `release` Javadoc only)*

## Goal
`HazelcastScopeBudget` implements `reserve`, `release` and `scopeReads` with the semantics of `InMemoryScopeBudget`. A reservation changes the subject counter and the scope total in one atomic step, or neither, and the step is atomic across every member and every subject of the scope. Without this, 246 moves the fan-out to `reserve`, the interface default refuses every read on a cluster, and multi-member deployments stop serving.

## Context
- docs/plan/tasks/retired/243-read-budget-reservation-spi.md, "Required shape": `reserve`, `release`, `scopeReads`, `forget`, the refusal reasons `SUBJECT` and `SCOPE_TOTAL`, and the property tests this task reruns on two members.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/ScopeBudget.java: the contract. Atomicity is per scope, not per subject; correctness depends on no clock, TTL or eviction; `tryRead` counts toward the total without being capped by it.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/InMemoryScopeBudget.java: the reference semantics.
- data-prism-hazelcast/.../HazelcastScopeBudget.java: `tryRead` today takes the subject key's bounded lock and runs one entry processor; `forget` deletes by scope prefix under bounded locks. Every cluster failure fails closed (`CLUSTER_LOCK_TIMEOUT`, `CLUSTER_OPERATION_REFUSED`, or a refused read).
- docs/plan/tasks/deferred/226-seal-counters-with-local-floors-and-reservations.md, "Required approach", the `reserve` bullets: the primary path, the fallback and the partial-failure rule. Copy them without the sealing and floor clauses.
- Task 219's answers 8 and 9 (docs/plan/tasks/retired/219-hazelcast-capability-spike.md, Outcome, and the spike tests under `data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/`): whether one processor invocation can update two entries that share a partition key, and backup re-execution.
- From PLAN.md, wave-1 follow-ups for 226 (2026-10-21), moved here: repeat the "no-op a release you cannot match" rule on the `ScopeBudget#release` Javadoc.

## Required approach
- **Primary path.** One entry processor over a partition-aware key of the scope. Its single invocation reads, checks and writes both the subject counter and the scope total. Either one per-scope value holds the scope total and the scope's subject counters, or the two entries share the scope's partition key and 219 shows that one invocation can update both atomically. The Outcome states which, citing 219. Two independent processors, one per counter, do not satisfy this path.
- **Fallback**, only if 219 shows the primary path is impossible. Take a per-scope lock (the keyed name of the scope-total key) through `ClusterLocks`, bounded by the lock timeout, around both writes. When the bound expires, refuse with `CLUSTER_LOCK_TIMEOUT`. Every write to either counter, including `tryRead`, `release` and `forget`, is still an entry processor on its own key. `release` and `forget` take the same per-scope lock. If the first update succeeds and the second fails, undo the first before returning refused. If the undo fails too, the counter is left over-counted, never under-counted, and one WARN line records it with no scope, subject or key.
- `tryRead` keeps its 0.7 meaning, and a refused read is not counted. Its reads count toward the scope total, and under the chosen shape they are atomic against `reserve` for the same scope.
- `release` gives back at most what that reservation still holds, once. A second release, a release of a refused reservation, and a release after `forget` are no-ops.
- Values stay as they are today (plain integers or a plain record under keyed names). Sealing is 226's, after 1.0.

## Acceptance
- [ ] Tests are written first, and the Outcome shows each one failing on the base commit (the interface default refuses every `reserve`).
- [ ] `HazelcastReservationTest`, on two embedded members, reruns 243's property tests (single subject and multiple subjects) against `HazelcastScopeBudget`. 32 threads across both members reserve random sizes, and neither counter ever goes past its budget. The final counts equal the sum of the granted reservations. Boundary cases at budget minus one, budget and budget plus one, for both counters.
- [ ] Multi-subject concurrency, in `HazelcastReservationTest`, on two members: at least 64 subjects in one scope, with threads on both members issuing concurrent `reserve` and `release` calls of random sizes, where the sum of the subject budgets exceeds `scopeTotalBudget`. Throughout the run (a concurrent sampler) and at the end, the scope total never exceeds `scopeTotalBudget` and no subject counter exceeds its budget. At the end, the scope total equals the granted reservations minus releases, and equals the sum of the subject counters. The Outcome shows the run with a mutation that synchronises on the subject instead of the scope, and the test failing.
- [ ] `release`: it never goes below what other reservations hold; a second release is a no-op; a release after `forget` is a no-op.
- [ ] `forget` running concurrently with `reserve` on the same scope never leaves a subject counter whose reads are missing from the scope total.
- [ ] A refused lock or processor in `reserve` surfaces as `CLUSTER_OPERATION_REFUSED`, and a timed-out lock as `CLUSTER_LOCK_TIMEOUT`, as `tryRead` does today (233). No exception text reaches the caller. Fallback path only: with the second update forced to fail, `reserve` refuses and both counters equal their values before the call.
- [ ] Every existing `HazelcastScopeBudgetTest`, `CounterLeaseExpiryTest` and `HazelcastStoredValueBoundaryTest` case passes unchanged.
- [ ] `ScopeBudget#release`'s Javadoc states the no-op rule for a release the budget cannot match, with no change to its code.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Out of scope
- Calling `reserve` from the orchestrator, and its tests on the in-memory budget (core 246). 246's Outcome names `HazelcastReservationTest` as the cluster run.
- Sealing counter values, the local floor, `floor.refusal`, the counter merge policy, and `HazelcastCallerRateLimiter` (226, deferred).
- `CounterMaps.java` map configuration, unless a new map is unavoidable. If it is, stop and report: no new map is planned.
- `TestMembers.java` is read only.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented identifiers in tests.
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Waits are on latches or polled state with a bound.
