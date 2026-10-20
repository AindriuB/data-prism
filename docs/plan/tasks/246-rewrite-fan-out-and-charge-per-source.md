# 246 — Rewrite the fan-out: a bounded queue, per-source and overall deadlines, per-source charging, no silent cut-off

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch once 243, 244, 203 and 226 have merged
**Depends on:** 243 (reservation SPI), 244 (both edit `DefaultContextOrchestrator`), 203 (203 owns existing `data-prism-mcp` test files), 226 (sealed-cluster plan; 226 implements `reserve` and `release` on `HazelcastScopeBudget`, OD-08-1 is decided as option (a), 2026-10-20)
**Decisions:** OD-08-1 (a), OD-08-3 (`PT10S` deadline, scope total 50,000, `scope-read-budget` stays 100 and counts source reads), OD-08-4 (a, remove `SKIPPED_OVER_LIMIT`) and OD-08-5 (a, release reads never started) are decided 2026-10-20.
**Wave:** 6 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
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
- [ ] With the in-memory budget, two concurrent requests for one subject never jointly exceed the subject budget or the scope total. The same test runs against `HazelcastScopeBudget` in the cluster brief named under OD-08-1; this task's Outcome records that brief's test name.
- [ ] Before merge, a whole-component concurrency hunt covers `SourceFanOut`, the reservation path and cancellation; each finding is fixed or filed and named in the Outcome.
- [ ] The Outcome lists the behaviour changes for the 0.8 migration notes (261): per-source timeout semantics, per-source charging, the refusal that replaces the cut-off.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Binding the new limits from configuration (254).
- `targetSources`, D-RM-9 and labels (247).
- The 0.9 batch, per-source truncation and per-source size caps.
- `HazelcastScopeBudget` (OD-08-1).
