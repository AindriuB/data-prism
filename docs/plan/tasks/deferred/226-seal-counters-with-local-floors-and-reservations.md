# 226 — Seal the budget and caller-rate counters, add local floors, and implement budget reservations on Hazelcast

Deferred 2026-10-23, owner decision (b): 1.0 supports multiple instances on a trusted network only, and cluster security for untrusted networks is deferred past 1.0 (D-RM-1 (a) as amended; `docs/plan/PLAN.md`, "After 1.0: untrusted-network clusters"). This brief is kept as written for that milestone. Its dependencies, wave and base branch below are those of the 0.8 plan and must be re-planned when the milestone opens; read the 1.0 briefs 227, 228 and 284 to 287 first, because they changed some of the files named here.

What moved out for 1.0: the unsealed `reserve`, `release` and `scopeReads` on `HazelcastScopeBudget` that core 246 needs are task 285 (OD-08-1 (a) is met there, without sealing or local floors). When this brief is revived, it seals what 285 built and adds the floors and the merge policy; `HazelcastReservationTest` already exists (285) and gains the sealed and floor cases.

**Repo:** `.`
**Release:** after 1.0 (untrusted-network clusters); was 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 223 and 243.
**Depends on:** 219 (answers 8 and 9), 223 (keyed names, `SealedWrites`, `ClusterSecuritySettings`, `CounterMapsBoundary`), 243 (core plan: `ScopeBudget.reserve` and `release`)
**Wave:** none (deferred past 1.0; was a 0.8 wave)
**Decisions:** OD-215-11 (a if 219 shows it possible, otherwise b), OD-215-15 (a, local floor covers the scope total) and core OD-08-1 (a, this brief owns `reserve` and `release` on `HazelcastScopeBudget`) are decided 2026-10-20.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudget.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastCallerRateLimiter.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CounterMaps.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/LocalFloor.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CounterMergePolicy.java *(new, if OD-215-11 needs one)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/CounterMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudgetTest.java, CounterLeaseExpiryTest.java *(changes only where a stored value is read or written raw, plus new cases)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/CounterResetReplayTest.java *(new, T6)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastReservationTest.java *(new)*

## Goal
Budget and caller-rate counts become sealed, and a member that deletes or replays a lower count can no longer give a scope more than the documented `single-node` allowance: each instance enforces its own local count as well as the shared one (design 2.3.5, OD-7). `HazelcastScopeBudget` also implements the per-source reservation and the per-scope total that the core plan's fan-out rewrite needs (core 243, OD-08-1 (a)), atomically and under the same floors. Counters get a merge policy instead of Hazelcast's default (202 follow-up).

## Context
- docs/plan/design/cluster-security.md 1.3, 1.6, 2.3.1 (failure: `budget` refuses, `callerrate` refuses), 2.3.5, 4.4 (`floor.refusal`), as amended by 218.
- Core plan task 243 (`git show plan/0.8-core:docs/plan/tasks/243-read-budget-reservation-spi.md`), "Required shape": `reserve`, `release`, `scopeReads`, `forget`, and the refusal reasons `SUBJECT` and `SCOPE_TOTAL`. Core OD-08-1 in `OD-08-core-open-decisions.md`.
- Core plan task 246, acceptance: "The same test runs against `HazelcastScopeBudget` in the cluster brief named under OD-08-1". This is that brief; name the test in the Outcome.
- docs/plan/tasks/retired/202-*.md, "For 0.8": budget and rate maps still have the default merge policy.
- Task 219 answers 8 (processor re-run on backup and retry) and 9 (member-local state in a merge policy). Task 223 `SealedWrites`, computing shape: a retried counter may over-count, never under-count.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/InMemoryScopeBudget.java: the reference semantics.
- From 218/219 (2026-10-21): OD-215-11 answer: a merge policy can open values (Q9), so "keep the higher count" is available. The earlier wave-1 follow-ups in PLAN.md also apply.

## Required approach
- Counter values are sealed with their canonical parts and window or lease end. Every change is one computing processor from `SealedWrites`: open, check, add, seal once on the primary, copy bytes to the backup.
- A value that does not open refuses the read or the request (fail closed), never resets to zero. A missing value is zero, which is why the local floor exists.
- `LocalFloor` keeps per-instance counts per (scope, subject), per caller and per scope total (OD-215-15 (a)). It is enabled by `ClusterSecuritySettings.localFloorEnabled`. A request is allowed only if both the shared count and the local count allow it. A refusal by the floor alone counts `floor.refusal`. Local entries expire with their window or scope lifetime, so memory is bounded by live scopes and callers.
- `reserve(scope, subject, reads, subjectBudget, scopeTotalBudget)` changes the subject counter and the scope total in one atomic step or not at all, and that step is atomic against every other subject of the same scope, because the scope total is shared by all of them. The synchronisation unit is the scope, never the subject.
  - **Primary path.** One computing processor from `SealedWrites` over a partition-aware key of the scope, whose single invocation reads, checks and writes both the subject counter and the scope total. Either one sealed per-scope value holds the scope total and that scope's subject counters, or the two entries share the scope's partition key and 219 shows one processor invocation can update both atomically. The Outcome states which, citing 219. Two independent processors, one per counter, are not this path.
  - **Fallback**, only if 219 shows the primary path is impossible. Take a per-scope lock, the keyed name of the scope total's key (223), through `ClusterLocks` and bounded by the lock timeout, around both writes; expiry of the bound refuses with `CLUSTER_LOCK_TIMEOUT`. Every write to either counter, including `tryRead`, `release` and `forget`, is still a computing processor on its own key, so a write that is not under the scope lock can never lose an increment. `release` and `forget` take the same per-scope lock. A subject counter's lock is never the only lock taken around a scope-total write.
  - **Partial failure (fallback).** If the first of the two updates succeeds and the second fails or is refused, the first is undone (a computing processor subtracting exactly what was added) before `reserve` returns refused with the second update's code (`CLUSTER_OPERATION_REFUSED`, `CLUSTER_LOCK_TIMEOUT` or the budget refusal). No partial reservation survives as granted. If the undo itself fails, the counter is left over-counted (never under-counted), the request is refused, and one WARN line without any scope, subject or key records it. The over-count lasts at most until the scope's lifetime ends.
- `release` returns at most what that reservation still holds, once, under the same unit of synchronisation as `reserve`. `forget` racing a `reserve` on the same scope leaves either both counters removed or both holding the granted reservation, never one without the other.
- The local floor's subject counts and scope total for one scope are updated under one per-scope lock or one atomic per-scope structure in `LocalFloor`, with the same all-or-nothing rule. The floor is checked and reserved first; if the shared reservation then refuses, the floor's reservation is released before `reserve` returns.

## Acceptance
- [ ] T6, in `CounterResetReplayTest`, on two members with the floor on: deleting the shared budget entry, or replaying a captured lower sealed count, never lets one instance exceed `budget` reads for a subject, and never lets the cluster exceed `2 × budget`. The same for the caller rate. With the floor off, the test documents the reset (the case is named for it). `floor.refusal` counts each floor-only refusal.
- [ ] A tampered or foreign counter value refuses the read with `SCOPE_READ_BUDGET` (budget) or `CALLER_RATE_LIMITED` (caller rate), counts the tamper, and is not replaced by zero.
- [ ] `HazelcastReservationTest` runs the 243 property tests (single subject and multiple subjects) against `HazelcastScopeBudget` on two members: 32 threads across both members reserving random sizes never take either counter past its budget, and the final counts equal the sum of granted reservations. Boundary cases at budget minus one, budget and budget plus one, for both counters. `release` cannot go below what other reservations hold, a second release is a no-op, release after `forget` is a no-op.
- [ ] A reservation refused by the local floor changes no shared counter.
- [ ] Multi-subject concurrency, in `HazelcastReservationTest`, on two members: at least 64 distinct subjects in one scope, with threads on both members issuing concurrent `reserve` and `release` calls of random sizes, where the sum of subject budgets exceeds `scopeTotalBudget`. Across the whole run the scope total never exceeds `scopeTotalBudget` (sampled by a concurrent reader and asserted at the end), no subject counter exceeds its budget, and the final scope total equals the sum of granted reservations minus releases, and equals the sum of the final subject counters (no increment lost or overwritten). A mutation that synchronises on the subject instead of the scope makes the test fail (shown once in the Outcome). The same test runs with the local floor on.
- [ ] Partial failure: with the second of the two updates forced to fail (a refused processor or a timed-out lock, injected in the test), `reserve` returns refused with the cluster code and both counters equal their values before the call. With the undo also forced to fail, the counter is over-counted, never under-counted, and the request is refused.
- [ ] `forget` concurrent with `reserve` on the same scope never leaves a subject counter whose reads are missing from the scope total.
- [ ] A rejected lock or processor in `reserve` surfaces as `CLUSTER_OPERATION_REFUSED`, a timed-out lock as `CLUSTER_LOCK_TIMEOUT`, as `tryRead` does today (233). No exception text reaches the caller.
- [ ] Merge: counters have the merge policy decided under OD-215-11. `HazelcastScopeBudgetTest` gains a split-and-heal case showing the merged count is never below the larger side's count when OD-215-11 (a) is chosen, or naming the documented loss under (b).
- [ ] Every 0.7 `HazelcastScopeBudgetTest` and `CounterLeaseExpiryTest` case passes; changes are only where a raw value was read or written.
- [ ] `CounterMapsBoundary`: every counter value opens under the test key, recomputes its keyed name, and the raw-value fixture is still rejected.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
`tryRead` keeps its 0.7 meaning; "a refused read is not counted"; bounded locks and both cluster codes; the scope lifetime and lease expiry semantics of `CounterLeaseExpiryTest`.

## Out of scope
- Calling `reserve` from the orchestrator (core 246) or configuring the scope total (core 254).
- A Hazelcast failed-attempt limiter for sealed links (task 264, core OD-08-2 (a)). Not built here.
- Spring binding of `local-floor.enabled` (230).
- `TestMembers.java` is read only. `docs/` pages: facts for 213 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented identifiers in tests. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Windows use an injected clock.
