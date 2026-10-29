# 246 — Rewrite the fan-out: a bounded queue, per-source and overall deadlines, per-source charging, no silent cut-off

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/wave/0.8-wave4`, which holds 243, 244, 203 and 285 (corrected 2026-10-28: 285 landed on `wave/0.8-wave4`, not `main`, OD-W5-1 (a); PR #163 is held). One PR for this task, targeting `wave/0.8-wave4`.
**Depends on:** 243 (reservation SPI), 244 (both edit `DefaultContextOrchestrator`), 203 (203 owns existing `data-prism-mcp` test files), 285 (implements `reserve` and `release` on `HazelcastScopeBudget`, unsealed; OD-08-1 is decided as option (a), 2026-10-20. Re-wired 2026-10-23 from 226, which owner decision (b) deferred past 1.0)
**Decisions:** OD-08-1 (a), OD-08-3 (`PT10S` deadline, scope total 50,000, `scope-read-budget` stays 100 and counts source reads), OD-08-4 (a, remove `SKIPPED_OVER_LIMIT`) and OD-08-5 (a, release reads never started) are decided 2026-10-20.
**Wave:** 6 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOutOptions.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the budget check and the fan-out call in `buildContext` and `fetchAndScrub` only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java *(`SKIPPED_OVER_LIMIT` as OD-08-4 decides, only)*
- data-prism-orchestration/src/test/resources/source-status-codes.txt *(as OD-08-4 decides, only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/RequestLimits.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/LimitsProperties.java *(the `new RequestLimits(` call only, passing the new defaults)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java *(the `new RequestLimits(` call only)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesLimitsTest.java *(the `new RequestLimits(` calls only)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ResponseLimitRefusalTest.java *(the `new RequestLimits(` calls only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/DenyDecisionCodeTest.java, ResponseLimitsTest.java, SourceFanOutMdcTest.java *(the `new RequestLimits(` calls only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceFanOutTest.java
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceFanOutDeadlineTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/PerSourceBudgetChargeTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/limits/RequestLimitsTest.java *(new)*

## Goal
The fan-out measures every source's timeout from the start of the whole fan-out (`SourceFanOut.java:102`, `:139`), waits for the bulkhead without a bound of its own, silently marks sources past `max-sources` as `SKIPPED_OVER_LIMIT`, and the budget is charged once per request. Rewrite it so that waiting for a bulkhead permit is bounded by an overall request deadline, each source's timeout starts when its own call starts, no source past the limit is silently dropped, and the reads are reserved, one per source to be called, against the subject and the scope total before any call is made, on both budget implementations.

## Context
- docs/plan/roadmap.md, 0.8 readiness: "Fan-out rewrite: separate queue, per-source and overall deadlines, no silent cut-off at `maxSources`" and "Read budget charged per source called, plus a per-scope total cap, on both budget implementations".
- docs/plan/PLAN.md "Facts found during design" (the timeout, budget and `SKIPPED_OVER_LIMIT` facts, each with a line reference).
- docs/plan/design/catalog.md section 6 (OD-C7, OD-C8: `SOURCE_SELECTION_TOO_LARGE` instead of a cut-off) and docs/plan/design/batch-and-bulk-follow.md section 2 (what the 0.9 batch expects of this rewrite).
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java: the current bulkhead, timeout and cancel logic.
- docs/conventions.md "Tests": an injected `Clock`, no sleeps; latches instead.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `RequestLimits` gains the overall request deadline and the per-scope total read budget, with the names and defaults OD-08-3 decides, and validates them in its constructor: both positive, and the deadline at least `perSourceTimeout` (`IllegalArgumentException` naming the component, never a value). `RequestLimits.DEFAULT` uses the decided defaults. Every `new RequestLimits(` call site is updated in place; no forwarding constructor is added.
- [ ] A source's timeout is measured from the moment its own call starts, after it holds a bulkhead permit. Waiting for a permit is bounded by the time left before the overall deadline; a source not started by then is never called (a counting adapter proves it), is reported `TIMED_OUT`, and its reserved read is handled as OD-08-5 decides.
- [ ] When the overall deadline passes, every call still running is cancelled and interrupted and reported `TIMED_OUT`, and `fetchAll` returns. A test with adapters that block on a latch shows `fetchAll` returns after the deadline plus a bounded cancellation interval, with a controllable clock.
- [ ] No permit leaks: after 1,000 requests mixing answers, failures, timeouts and cancellations, the bulkhead is back at `maxConcurrency` (asserted through a test hook, not by timing).
- [ ] More sources to call than `maxSources` refuses the whole call with `SOURCE_SELECTION_TOO_LARGE` before any adapter call or budget reservation (counting adapter and counting budget). `SKIPPED_OVER_LIMIT` is removed from `SourceOutcome.Status` (OD-08-4 (a)), and `source-status-codes.txt` and `SourceStatusCodesTest` agree with it.
- [ ] After the circuit-breaker filter, the orchestrator reserves one read per source it will call, against the subject budget and the scope total, in one `ScopeBudget.reserve` call, before any adapter is called. A refused reservation refuses the call with `SCOPE_READ_BUDGET` and calls nothing. A source whose circuit is open is not charged. `compare_entity_sources` follows the same rule (a test through its path).
- [ ] With the in-memory budget, two concurrent requests for one subject never jointly exceed the subject budget or the scope total. The same test runs against `HazelcastScopeBudget` in 285 (`HazelcastReservationTest`); this task's Outcome names it.
- [ ] Before merge, a whole-component concurrency hunt covers `SourceFanOut`, the reservation path and cancellation; each finding is fixed or filed and named in the Outcome.
- [ ] The Outcome lists the behaviour changes for the 0.8 migration notes (261): per-source timeout semantics, per-source charging, the refusal that replaces the cut-off.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Binding the new limits from configuration (254).
- `targetSources`, D-RM-9 and labels (247).
- The 0.9 batch, per-source truncation and per-source size caps.
- `HazelcastScopeBudget` (285, OD-08-1).

## Outcome (2026-10-29, 0.8; PR #180, tip a11534d2, wave/0.8-wave4)
`SourceFanOut` is rewritten as `plan` / `execute` / `abandon` (`fetchAll` is plan plus execute) and reads are charged per source called. `RequestLimits` gains `requestTimeout` (PT10S) and `scopeTotalReadBudget` (50,000). The per-source timeout runs from the call's own start, after it holds a permit; the permit wait is bounded by the overall deadline; a source not started by then is never called and is `TIMED_OUT`; at the deadline every running call is interrupted and `fetchAll` returns after a bounded 500 ms real-time unwind. `DefaultContextOrchestrator` makes one `ScopeBudget.reserve` of one read per callable source (after the breaker filter) before any call; a refusal gives `SCOPE_READ_BUDGET` with nothing called, and reads for never-started sources are released (OD-08-5 a). More callable sources than `maxSources` gives `SOURCE_SELECTION_TOO_LARGE` before any call or reservation; `SKIPPED_OVER_LIMIT` is removed (OD-08-4 a, six status codes). A request with no callable source is no longer charged. Base: `origin/wave/0.8-wave4`.

Owner decisions. OD-W6-3 (a): Owns widened to `SourceCircuitBreaker.java` and its test. A half-open trial was taken by `allows` and ended only by `record`, so a trial taken and never reported stuck the breaker for ever. `admit(name)` returns an `Admission(allowed, trial)` and numbers each trial; `releaseTrial(name, admission)` clears the trial only if its number is still current, so it is a no-op for a closed breaker, an open breaker with no trial, another caller's trial and an already ended one, and it records no failure and leaves `openedAt` alone. The fan-out hands back exactly the trials its plan took. OD-W6-4 (a), covering 180-3 and 180-6, as corrected by review: the fan-out counts leftover calls per source, meaning calls still running after their own request returned or abandoned them. A source with `maxConcurrency` leftovers is busy for a new request: never called, never charged, never admitted to its breaker, reported `TIMED_OUT` with the detail `source still busy with earlier calls`. Ordinary concurrent traffic is not counted. No new half-open trial is admitted while an earlier trial call is still running. The owner's first spec counted every running call and was corrected to count only leftovers; the first version of the change had that error, and a mutant now pins the corrected rule. OD-W6-5: `LimitsProperties` derives `requestTimeout = max(PT10S, per-source-timeout)` until 254 binds it, logged once at INFO when raised.

Orchestrator call: the leftover counts live per `SourceFanOut` instance, which is one per deployment. A static count across fan-outs is a one-line change but couples tests that share source names. Task 272's shared pool may replace the mechanism.

External review rounds closed 21 findings (180-1 to 180-21) and three more rounds. The notable ones: reservations are now settled in a `finally` (release the unused reads, forfeit the used remainder through `ReadReservation.takeUnused`, then `release(reservation, 0)` so `InMemoryScopeBudget` drops its record); `HazelcastScopeBudget.release` returns before any lock when `unused` is 0 (Owns widening; OD-285-2 (a) holds). A failing `execute` cancels every call, hands back every trial and rethrows after returning the unstarted reads. A call cut by the deadline before its own timeout records nothing in the breaker; a caller interrupt records nothing. Late results are judged by `finishedAt`, not by when the waiter looked. A call that starts after the deadline cancels itself. A per-call gate decides once whether the adapter is invoked, so a call cancelled between STARTED and the adapter is settled exactly like one that never started. A throwing `PrivacyMetrics` no longer skips `breaker.record` for the remaining sources.

CI failure on small runners was test-only: blocking test requests ran on the common pool, which has one thread on a two-core runner. They now run on a platform thread of their own (`TestAsync`). Reproduce with `-DargLine="-XX:ActiveProcessorCount=2 -Djdk.virtualThreadScheduler.parallelism=1"`. A `countLeftovers` double count after an Error was fixed with a guard and a probe test. Not shown red: the new tests use the new constructor or API, so none compiles against the base.

Edits outside Owns (accepted): `LimitsConfigurationTest`, `SourceStatusCodesTest`, `ExternalCorrelationPropagationTest`, `ClusterOperationRefusalToolTest`, `ConfiguredJsonSourcesLimitsTest`, `SourceFanOutTest`, `PerSourceResultsTest`, `OrchestratorRefusalCorrelationTest`, `ResponseLimitRefusalTest`, a `TestLimits` fixture, and the package-private test hooks in `SourceFanOut`.

Behaviour changes for 261's migration notes: the per-source timeout is measured from the call's start; `scope-read-budget` counts source reads and `scope-total-read-budget` is new; a selection over `max-sources` is refused instead of marking sources skipped (breaking); a request arriving while a trial call is still running now gets `TIMED_OUT` / busy rather than `CIRCUIT_OPEN`; `per-source-timeout` above 10s raises the effective deadline until 254; the `SOURCE_SELECTION_TOO_LARGE` count is taken after the breaker filter; with no source selection (247) a deployment with more adapters than `max-sources` refuses every call; a `Clock.fixed` test clock never times out a blocking adapter. Filed, not fixed: an adapter that ignores interrupts keeps its virtual thread and its per-request permit until it returns (pre-existing).
