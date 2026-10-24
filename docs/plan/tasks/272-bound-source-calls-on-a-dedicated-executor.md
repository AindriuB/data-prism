# 272 — Bound the fan-out's source calls on dedicated platform threads

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 241, 246 and 211 have merged
**Depends on:** 241 (`ResolverInvoker`, the decided shape this copies), 246 (rewrites `SourceFanOut.java` and owns it whole), 211 (both edit `docs/plan/design/contract-register.md`; the only shared file)
**Wave:** 8 (filed 2026-10-23)
**Decisions:**
- The shape follows the coordinator's decision for 241's `ResolverInvoker` (2026-10-23): a dedicated, bounded set of platform threads; a slot held until the adapter call returns; an immediate refusal when no slot is free.
- OD-272-1 decided (a) by the owner, 2026-10-23 (below).

**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceCallSlots.java *(new, package-private)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java *(the `FAILED` constant's Javadoc only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceFanOutStarvationTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceCallSlotsTest.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(insertions only: one constant)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(insertions only)*
- docs/plan/design/contract-register.md *(insertions only: the new metric's row in section 5)*

## Goal
`SourceFanOut` runs each adapter call on a virtual thread and, on timeout, only cancels the future with an interrupt (`SourceFanOut.java:145-149` on `main`). An adapter that ignores interrupts keeps running. A CPU-bound or pinning adapter keeps its carrier. About one per core pins every carrier of the shared virtual-thread scheduler, and then every later source call in the process times out.

Run source calls on dedicated platform threads instead, bounded in total and per source. This delivers two things:
- A misbehaving adapter can hold at most its source's ceiling of slots and cannot starve the virtual-thread scheduler.
- Calls to other sources keep answering until the pool as a whole is full.

It does not stop a hung adapter from holding its own slots; those come back only when its calls return. Four sources hung at their ceiling (4 × 32) fill the whole 128-slot pool, and every source is then `UNAVAILABLE` until a hung call returns. The circuit breaker is the component that would stop a hung source reaching its ceiling: it opens after repeated `TIMED_OUT` outcomes, and a source with an open breaker is not called. But every shipped wiring builds `SourceFanOut` with `SourceCircuitBreaker.disabled()` (`OrchestrationWiring.java:70`, `ConfiguredJsonSourcesAutoConfiguration.java:183`, `DefaultContextOrchestrator.java:105`), so today nothing limits it. Enabling the breaker is out of scope here, and the Outcome names it as a follow-up.

## What adapters will see (also for the migration notes)
- Adapter calls run on platform daemon threads named `dataprism-source-<n>`, not on virtual threads.
- Each call runs on a fresh thread that exits when the call returns, so no `ThreadLocal` set by one call is visible to another. The thread is created with `inheritInheritableThreadLocals=false`, so it also inherits no `InheritableThreadLocal` from the requesting thread.
- The MDC is opened from the call's own request and closed in the call's own `finally`, as today.
- When the pool or the source's ceiling is full, the source is not called. It is reported `FAILED` with `detail` `UNAVAILABLE`.
- A source can have at most 32 calls in flight across all requests. Four sources hung at that ceiling fill the 128-slot pool, and then every source answers `UNAVAILABLE` until a hung call returns. Only the circuit breaker would limit that, and it is disabled in every shipped wiring today.

## What this keeps from 241, and what it changes
- **Kept:**
  - a `Semaphore` taken with `tryAcquire`, never a blocking `acquire`;
  - daemon platform threads;
  - slots released in the task's own `finally`, behind a double-release guard (an `AtomicBoolean` per call), so a slot is released once even if the call both completes and is cancelled.
- **Changed:**
  - no queue and no pool: a thread per call, bounded by the semaphores, so there are no idle threads to time out and no thread is reused;
  - the thread-name prefix `dataprism-source-`;
  - no inherited thread-locals (`new Thread(group, r, name, 0, false)`);
  - the sizes below;
  - a second, per-source semaphore beside the pool's.

## Required shape
- `SourceCallSlots` holds one pool semaphore of 128 permits, and one semaphore per source name of 32 permits, created on first use. Both sizes are constants with these one-line reasons in their Javadoc:
  - **Pool, 128:** 32 concurrent requests each running `RequestLimits.DEFAULT.maxConcurrency()` (4) calls. Each platform thread reserves a native stack, so the bound is fixed rather than elastic.
  - **Per source, 32:** the number of concurrent requests the pool is sized for (128 / 4). A request calls a given source at most once at a time, so all 32 of those requests can call the same healthy source at once without hitting its ceiling. A single hung source holds at most a quarter of the pool, which leaves 96 slots for every other source. Under more than 32 concurrent requests to one source, the excess is reported `UNAVAILABLE`, which is the intended back-pressure.
- `SourceFanOut` uses one shared default `SourceCallSlots` for the process. A package-private constructor takes an instance and its sizes, for tests. The public constructors, and every `new SourceFanOut(` and `new SourceFanOutOptions(` call site, are unchanged.
- **Order.** Inside a call, after 246's bulkhead permit is held:
  1. take the source's slot (`tryAcquire`), then the pool's;
  2. if either fails, release whatever was taken and report the source at once;
  3. otherwise start the thread.

  Both slots are released in the thread's own `finally`, when the adapter call returns. That may be long after its timeout.
- **Saturation.**
  - The source is not called. Its outcome is `FAILED` with `detail` the constant `UNAVAILABLE`.
  - `SourceFanOut` marks it as saturated with a structured marker, never by reading `detail`. `Fetched` is a public record, and a new component would change public API, so `fetchAll` keeps a local set of saturated source names.
  - That set decides the follow-up for each saturated source:
    - the breaker does not record it, because the source did nothing wrong;
    - `SOURCE_ERRORS` is not incremented;
    - `SOURCE_LATENCY` is not recorded;
    - the new metric `SOURCE_CALLS_SATURATED`, `dataprism.source.calls_saturated`, is incremented with the source name.
- A timed-out call is still interrupted and reported `TIMED_OUT`, as 246 specifies. Its slots stay held until it returns.
- `SourceOutcome.Status.FAILED`'s Javadoc says it also covers "not called: no call slot was free (detail `UNAVAILABLE`)".

## OD-272-1: decided (a) by the owner, 2026-10-23
How a saturated call appears to the client.
- **Decided:** the existing `FAILED` status with `detail` `UNAVAILABLE`. There is no change to the frozen seven-code status set, and clients already treat `FAILED` as "not answered".

## Context
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java:
  - on `main`: `fetchAll`, with its `newVirtualThreadPerTaskExecutor` in try-with-resources, and `collect`'s `future.cancel(true)` on timeout;
  - on the base branch: read 246's rewrite there.
- 241's `ResolverInvoker` (data-prism-orchestration/.../links/ResolverInvoker.java on the base branch): the same flaw and the decided shape. 241 adds no reusable executor class, so this task writes its own.
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java:14-17: `detail` is "a code or short reason, never a payload fragment".
- `source-status-codes.txt` and `SourceStatusCodesTest`: the frozen per-source status set. This task adds no status.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/RequestLimits.java: `DEFAULT`'s `maxSources` (8) and `maxConcurrency` (4), as 246 leaves them.
- docs/conventions.md "Tests": latches and injected clocks, no sleeps.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them and shows the failing output.
- [ ] **Starvation repro** (`SourceFanOutStarvationTest`), failing on the base commit.
  - **Spinners.** Let `n = Runtime.getRuntime().availableProcessors()`. Use `n` adapters, all for one source `spin`, each looping `while (!released) Thread.onSpinWait();` on a `volatile boolean released`. They ignore interrupts and never block.
  - **Spinner calls.** Each spinner's `fetchAll` runs on its own platform thread, started by the test with a per-source timeout of 200 ms.
  - **Probe.** Once every spinner has entered its loop (a `CountDownLatch` of `n`, counted down on entry), run the probe's `fetchAll` on its own platform thread, never on the test thread. It has one adapter, for source `probe`, which answers at once, also with a 200 ms timeout. The probe thread stores its result in an `AtomicReference`.
  - **Assertions.**
    - The test joins the probe thread with a bound of 1,200 ms. That margin is for slow CI; the expected time is far below it.
    - If the join times out, the test sets `released`, joins the probe thread again (5 s bound), and fails with "probe did not return within 1,200 ms". The base commit therefore fails rather than hangs.
    - If the join succeeds, the probe's result reports `ANSWERED`. On the base commit it reports `TIMED_OUT`, or the join times out.
  - **Sizes.** Inject them through the package-private constructor: pool `n + 4`, per-source ceiling `n`.
  - **Cleanup.** In `finally`, set `released`, then join every spinner thread and the probe thread with a 5 s bound each, and fail the test if any is still alive.
- [ ] **Per-source ceiling (H2).**
  - Inject a pool of 8 and a ceiling of 2.
  - Two interrupt-ignoring spinners for source `a` hold their slots.
  - A third call to `a` is reported `FAILED`, with `detail` `UNAVAILABLE`, without its adapter being called (a counting adapter shows this).
  - In the same and in a later `fetchAll`, a call to source `b` reports `ANSWERED`.
  - Cleanup as above.
- [ ] **Pool saturation.**
  - Inject a pool of 2 and a ceiling of 2, and hold both slots with spinners for two different sources.
  - A call to a third source is reported `FAILED`, with `detail` `UNAVAILABLE`, without being called.
  - `SOURCE_CALLS_SATURATED` is incremented once, with that source's name.
  - `SOURCE_ERRORS` is not incremented for it, and the breaker's record for it is unchanged.
- [ ] **On time with interrupt-ignoring adapters.** A `fetchAll` whose only adapter spins and ignores interrupts returns within its per-source timeout plus 1,000 ms, reporting `TIMED_OUT`. This is a regression guard: it proves 272 does not wait for a running call. Whether it also fails on the post-246 base is recorded in the Outcome, without claiming either way in advance.
- [ ] **Slot accounting.**
  - A timed-out call's slots stay taken until its adapter call returns, and are then released exactly once (the double-release guard is exercised by a call that completes as it is cancelled).
  - After 1,000 mixed calls, `SourceCallSlots` reports every pool and per-source permit free. The mix covers answers, failures, timeouts, and interrupt-ignoring calls that are later released.
  - Both are checked through a package-private test hook, not by timing.
- [ ] **Thread isolation (H3).** Inject a pool of 1 so that request B's call follows request A's. Before starting B, wait until A's slots are back: poll `SourceCallSlots`'s package-private permit hook (pool and per-source `availablePermits`) in a loop bounded at 2 s by `System.nanoTime`, with `Thread.onSpinWait()` between polls. Fail if the bound passes. B never races A's release.
  - An adapter in request A sets a `ThreadLocal` and an `InheritableThreadLocal` it owns. The adapter in request B reads both and sees neither value.
  - The requesting thread sets an `InheritableThreadLocal`, and the adapter does not see it.
  - The adapter thread's name starts with `dataprism-source-`, and it is a daemon, platform (`!isVirtual()`) thread.
  - The MDC is cleared inside the call thread's own `finally`. A package-private test hook on `SourceCallSlots`, run as the last statement of that `finally` (after the MDC scope closes), captures `MDC.getCopyOfContextMap()`. The test asserts the capture is `null` or empty, and also that the adapter saw the request's correlation id while it ran. Mutant killed: removing the MDC close from the `finally` makes the capture non-empty.
- [ ] `Metric` gains `SOURCE_CALLS_SATURATED`, pinned by `PrivacyMetricsTest`; no existing constant changes. The contract register's section 5 gains its row. `SourceOutcome.Status.FAILED`'s Javadoc carries the sentence above.
- [ ] Every existing `data-prism-orchestration` test passes unmodified, including 246's `SourceFanOutTest` and `SourceFanOutDeadlineTest`.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-orchestration -am verify` passes.
- [ ] The Outcome repeats "What adapters will see" under "for the migration notes" (261 depends on this task and carries it into `docs/migration-0.8.md`).

## Out of scope
- Binding the pool size and the per-source ceiling from configuration. Name it in the Outcome as a follow-up for 254, which owns the `dataprism.limits` wiring.
- `ResolverInvoker` (241) and any other executor in the codebase.
- The bulkhead, deadlines and budget reservation (246); this task changes only where a call runs and what happens when no slot is free.
- A new per-source status; OD-272-1 decided against one.
- Enabling the circuit breaker in `OrchestrationWiring`, `ConfiguredJsonSourcesAutoConfiguration` or `DefaultContextOrchestrator` (a follow-up named in the Outcome).
- `docs/tools.md` and `docs/migration-0.8.md` (261).
