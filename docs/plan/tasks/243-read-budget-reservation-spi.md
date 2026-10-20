# 243 — Add per-source read reservations and a per-scope total to the budget SPI

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main`
**Depends on:** none
**Wave:** 1 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/ScopeBudget.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/InMemoryScopeBudget.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/ReadReservation.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/limits/InMemoryScopeBudgetTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/limits/ReadReservationTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/limits/ScopeBudgetDefaultsTest.java *(new)*

## Goal
The read budget is charged once per request (`DefaultContextOrchestrator.java:178`), not once per source called, and nothing caps a whole scope. The roadmap asks for both, on both budget implementations. Add the SPI operations that the 0.8 fan-out (246) and `follow` (252) need, and the 0.9 batch needs as groundwork: one atomic reservation of N reads against a subject's counter and the scope's total, and a bounded release of reads that were reserved but never used. Implement them in memory. The Hazelcast implementation is not in this task (OD-08-1).

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/ScopeBudget.java and InMemoryScopeBudget.java: the shipped contract ("a refused read is not counted").
- docs/plan/design/batch-and-bulk-follow.md section 2, "Read budget across a batch": reservation in one atomic step per item; release of a reservation for an item never called. That is the 0.9 consumer this SPI must already fit, because the SPI freezes at 0.9.0.
- docs/plan/design/key-resolvers.md section 6, "Read budget": charge before the call; a call that cannot be afforded is not made.
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudget.java implements this interface and must still compile unchanged.

## Required shape (later tasks read these)
- `ReadReservation reserve(String scopeId, String subjectId, int reads, int subjectBudget, int scopeTotalBudget)`: either both counters grow by `reads`, or neither does and the result is refused, with `refusedBy()` one of `SUBJECT`, `SCOPE_TOTAL`.
- `void release(ReadReservation reservation, int unused)`: returns at most what that reservation still holds, once per read; a second release of the same reads does nothing.
- `int scopeReads(String scopeId)`; `forget(scopeId)` drops the subject counters and the scope total.
- Default methods on `ScopeBudget`: `reserve` returns a refused reservation and `release` does nothing, so an implementation that has not been updated refuses every read rather than reading uncounted.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `ScopeBudgetDefaultsTest` shows that an implementation of only the 0.7 methods refuses every reservation (fail closed). `HazelcastScopeBudget.java` is unchanged and the reactor compiles.
- [ ] In memory, a reservation that would take either counter past its budget is refused and changes neither counter. Equality with the budget is allowed. Boundary tests at budget minus one, budget and budget plus one, for both counters.
- [ ] Thirty-two threads reserving concurrently against one subject and one scope never take either counter past its budget, and the final counts equal the sum of granted reservations (a property test with random sizes).
- [ ] `release` never takes a counter below what other reservations hold: releasing more than a reservation holds releases only what it holds; a second release is a no-op; release after `forget` is a no-op.
- [ ] `reads <= 0`, a non-positive budget, and an arithmetic overflow are refused with `IllegalArgumentException` before any counter changes.
- [ ] Every 0.7 `InMemoryScopeBudgetTest` case passes unmodified. `tryRead` keeps its 0.7 meaning.
- [ ] Nothing in the correctness of a counter depends on a clock, a time-to-live or eviction.
- [ ] `./mvnw -B -pl data-prism-core -am verify` passes, and so does `./mvnw -B -pl data-prism-hazelcast -am verify` with no change under `data-prism-hazelcast`.

## Out of scope
- `HazelcastScopeBudget` (OD-08-1; a sealed-cluster brief).
- Calling `reserve` from the orchestrator (246) or from `follow` (252).
- Configuration keys and defaults for the scope total (OD-08-3; 246 and 254).
- Removing `tryRead`.
