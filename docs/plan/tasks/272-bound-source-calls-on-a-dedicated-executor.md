# 272 — Bound the fan-out's source calls on a dedicated executor

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 241, 246 and 211 have merged
**Depends on:** 241 (`ResolverInvoker`, the executor shape to copy or reuse), 246 (rewrites `SourceFanOut.java` and owns it whole), 211 (both edit `docs/plan/design/contract-register.md`; the only shared file)
**Wave:** 8 (filed 2026-10-23)
**Decisions:** the executor shape follows the coordinator's decision for 241's `ResolverInvoker` (2026-10-23): a dedicated bounded executor of platform threads, a slot held until the thread actually exits, and an immediate refusal when it is saturated. OD-272-1 decided (a) by the owner 2026-10-23 (below).
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceCallExecutor.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceFanOutStarvationTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceCallExecutorTest.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(insertions only: one constant)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(insertions only)*
- docs/plan/design/contract-register.md *(insertions only: the new metric's row in section 5)*

## Goal
`SourceFanOut` runs each adapter call on a per-request virtual-thread executor and, on timeout, only cancels the future with an interrupt (`SourceFanOut.java:145-149` on `main`). An adapter that ignores interrupts keeps its thread. A CPU-bound or pinning one keeps a carrier, and about one per core pins every carrier of the shared virtual-thread scheduler, so every later call in the process times out. The per-request executor's `close()` also waits for such a call, so `fetchAll` itself does not return at the timeout. Run source calls on one process-wide, bounded executor of platform threads, so that a misbehaving adapter can only use up its own slots and the fan-out returns on time whatever an adapter does.

## Context
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java: `fetchAll` (the `newVirtualThreadPerTaskExecutor` in try-with-resources) and `collect` (the `future.cancel(true)` on timeout). Read 246's rewrite on the base branch; the line numbers above are `main`'s.
- 241's `ResolverInvoker` (data-prism-orchestration/.../links/ResolverInvoker.java, on the base branch): the same flaw and the decided shape. Copy its executor shape, slot accounting and saturation refusal. If 241 merged a reusable executor class, reuse it rather than writing a second.
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java:14-17: `detail` is "a code or short reason, never a payload fragment".
- data-prism-orchestration/src/test/resources/source-status-codes.txt and `SourceStatusCodesTest`: the frozen per-source status set (contract-register section 2). This task adds no status (OD-272-1).
- docs/conventions.md "Tests": latches, no sleeps, an injected `Clock`.

## Required shape
- `SourceCallExecutor` (package-private unless 241's class is reused) is a fixed pool of platform daemon threads with no queue, sized by a constant. The default is 128, stated in its Javadoc with a one-line reason. A call takes a slot before it is submitted and gives it back in the task's own `finally`, so a slot stays taken until the adapter call has really returned, however long after its timeout that is.
- `SourceFanOut` uses one shared default instance for the process. A package-private constructor takes an instance, for tests. The public constructors and every `new SourceFanOut(` and `new SourceFanOutOptions(` call site are unchanged.
- No slot free: the source is not called. Its outcome is `FAILED`, with `detail` the constant `UNAVAILABLE` (OD-272-1 (a)), at once, without waiting for a slot. The new metric `SOURCE_CALLS_SATURATED`, `dataprism.source.calls_saturated`, is incremented with the source name. The breaker does not record that outcome against the source, because the source did nothing wrong.
- A timed-out call is still cancelled with an interrupt and reported `TIMED_OUT`, as 246 specifies. The MDC is still opened from the task's own request.

## OD-272-1: decided (a) by the owner, 2026-10-23
How a saturated call appears to the client.
- **Decided:** the existing `FAILED` status with `detail` `UNAVAILABLE`. There is no change to the frozen seven-code status set, and clients already treat `FAILED` as "not answered".

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them and shows the failing output.
- [ ] Starvation repro (`SourceFanOutStarvationTest`), failing on the base commit:
  - Run `fetchAll` calls whose adapters ignore interrupts and stay busy until a test latch releases them. Use at least `Runtime.getRuntime().availableProcessors()` of them, concurrently, each with a short per-source timeout.
  - Then make one `fetchAll` call to a well-behaved adapter that answers at once.
  - On the base commit that call reports `TIMED_OUT`, or `fetchAll` does not return within the timeout plus a bounded margin. After the change it reports `ANSWERED`.
  - The test releases every busy adapter in a `finally`, so no thread outlives it.
- [ ] `fetchAll` returns within the overall deadline plus a bounded cancellation interval even when every adapter ignores interrupts. This is asserted with latches and a controllable clock, not sleeps.
- [ ] Saturation: with an executor of size 2 and two adapters held busy, a third source is reported `FAILED` with `detail` `UNAVAILABLE` without its adapter being called (counting adapter). `SOURCE_CALLS_SATURATED` is incremented once, and the breaker's record for that source is unchanged.
- [ ] Slot accounting: a slot taken by a timed-out call stays taken until the adapter call actually returns, and is then given back. After 1,000 mixed calls (answers, failures, timeouts, interrupt-ignoring calls that are later released), the executor reports every slot free. This goes through a test hook, not by timing.
- [ ] Executor threads are platform daemon threads with a fixed name prefix, and no virtual thread runs an adapter call. Assert this from inside a test adapter.
- [ ] `Metric` gains `SOURCE_CALLS_SATURATED` and `PrivacyMetricsTest` pins it; no existing constant changes. The contract register's section 5 gains its row.
- [ ] Every existing `data-prism-orchestration` test passes unmodified, including 246's `SourceFanOutTest` and `SourceFanOutDeadlineTest`.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-orchestration -am verify` passes.
- [ ] The Outcome lists, for the 0.8 migration notes (261): source calls now run on platform threads (thread-local and MDC behaviour), the pool size, and the saturation outcome.

## Out of scope
- Binding the pool size from configuration. Name it in the Outcome as a follow-up for 254, which owns the `dataprism.limits` wiring.
- `ResolverInvoker` (241) and any other executor in the codebase.
- The bulkhead, deadlines and budget reservation (246); this task changes only where a call runs and what happens when no slot is free.
- A new per-source status. OD-272-1 decided against one.
- `docs/tools.md` and `docs/migration-0.8.md` (261).
