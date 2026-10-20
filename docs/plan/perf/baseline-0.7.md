# Load-test baseline, 0.7 code

Internal. First before-measurement for the 0.8 rewrite of the fan-out, the read
budget and the timeout model. Decision D-070-11 (a). Not for publication before
0.9. These results support a comparison against later runs on the same host; they
make no compliance or certification claim.

## Environment

| | |
|---|---|
| Commit | `558371184710ee673cb329edaee63e2a42092cf0` (`main` after PR #134), working tree clean for the measured runs |
| Host | Apple M1, 8 cores, 16 GiB, macOS 27.0.1 (aarch64), bare metal, no container |
| JDK | OpenJDK 25.0.4 (Homebrew); driver heap 4096 MiB max; server heap left at JVM default |
| Date | 2026-10-20 (UTC 07:37 to 07:42) |
| Topology | Synthetic sources, token issuer, server and driver all share this one host over loopback. The results are comparable only with runs on the same host. |

Contention: this was a developer laptop with other agents building and testing
in sibling worktrees. No run was made on a quiescent machine. The one-minute load
average before the runs was 9.0 and fell to about 5 by the 100-source runs (it
includes this task's own Maven builds, which `run.sh` does before each run).
During the 300-source runs other JVMs started (load average 13.6 to 14.5 after
runs 2 and 3), so the 300-source numbers are the least trustworthy. Per-run
load figures are in the log table below. A first pass of the same twelve runs was
discarded and not committed because sibling builds overlapped its end; the figures
below are from the second pass.

## Reproduce

From a checkout of the commit above, with JDK 21 or later and nothing else
running:

    load-test/run.sh load-test/scenarios/sources-8.json     # three times
    load-test/run.sh load-test/scenarios/sources-32.json    # three times
    load-test/run.sh load-test/scenarios/sources-100.json   # three times
    load-test/run.sh load-test/scenarios/sources-300.json   # three times

Each writes `load-test/results/<scenario>-<timestamp>.json` (git-ignored); the
twelve raw files of this baseline are in `load-test/baselines/0.7/`. They hold
only synthetic data. Scenario files are unchanged from the commit. No code,
harness or configuration was changed for the measurement.

## Parameters (from the scenario files)

| Scenario | Sources | Source latency | Workers | Calls (warm-up discarded) | maxSources | maxRecords | maxResponseSize | perSourceTimeout | maxConcurrency | scopeReadBudget |
|---|---|---|---|---|---|---|---|---|---|---|
| sources-8 | 8 | 5-25 ms | 4 | 300 (30) | 8 | 500 | 512KB | 3s | 8 | 100 |
| sources-32 | 32 | 5-25 ms | 4 | 300 (30) | 32 | 2000 | 2MB | 5s | 16 | 100 |
| sources-100 | 100 | 5-40 ms | 4 | 200 (20) | 100 | 5000 | 4MB | 5s | 32 | 100 |
| sources-300 | 300 | 5-40 ms | 4 | 100 (10) | 300 | 20000 | 16MB | 10s | 64 | 100 |

Unpaced (`ratePerSecond` 0), call timeout 60 s, seed 196. Every call asks for a
different subject. Sources-300 is the optional scenario. 500 sources, soak and
cluster runs were not made (out of scope).

## Results

Median across three runs, with the range (min to max) in brackets. Latency is in
ms over completed calls; throughput is completed calls per second of measured
wall time.

| Scenario | p50 | p95 | p99 | max | Throughput /s |
|---|---|---|---|---|---|
| 8 | 30.1 (29.9-30.2) | 39.7 (37.2-41.2) | 45.8 (41.3-52.9) | 49.1 (45.2-60.1) | 126.6 (126.4-130.9) |
| 32 | 50.5 (50.2-50.9) | 63.1 (62.8-63.2) | 67.5 (66.7-69.2) | 73.1 (68.3-76.3) | 77.1 (76.9-77.2) |
| 100 | 103.2 (102.5-103.4) | 125.1 (123.3-131.3) | 136.5 (136.3-155.9) | 147.2 (141.2-163.8) | 37.5 (37.5-37.6) |
| 300 | 190.3 (167.6-208.4) | 287.7 (261.9-320.7) | 341.8 (316.2-356.9) | 342.5 (321.4-375.9) | 19.7 (17.8-20.9) |

Per run, in every run: `transportFailures` 0, `notDispatched` 0, `clamped` 0, no
refusal codes (empty `refusalCodes`). Throughput includes the in-flight drain
after the last dispatch. With 3 runs, the "spread" is only the min to max range;
it is not a confidence interval.

Source status counts (per-source keys are per-scope aliases, so the file's
`perSourceStatus` has one entry per generated source, each with the same count):

| Scenario | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| 8 | ANSWERED 2400 | ANSWERED 2400 | ANSWERED 2400 |
| 32 | ANSWERED 9600 | ANSWERED 9600 | ANSWERED 9600 |
| 100 | ANSWERED 20000 | ANSWERED 20000 | ANSWERED 20000 |
| 300 | ANSWERED 30000 | ANSWERED 29999, FAILED 1 | ANSWERED 30000 |

No `TIMED_OUT`, `NO_DATA`, `CIRCUIT_OPEN`, `SKIPPED_OVER_LIMIT` or `REFUSED` in
any run. The one `FAILED` (300-source run 2) is a single source of 30000; the
result file records the status only, not a cause, so it is unexplained. It
happened during the run where other JVMs were starting on the host.

Run log (one-minute load average before and after each run; all runs were
back-to-back):

| Result file | Load before | Load after |
|---|---|---|
| sources-8-20261020T073756Z | 9.00 | 10.60 |
| sources-8-20261020T073812Z | 10.60 | 8.83 |
| sources-8-20261020T073827Z | 8.83 | 7.78 |
| sources-32-20261020T073842Z | 7.78 | 6.06 |
| sources-32-20261020T073859Z | 6.06 | 5.76 |
| sources-32-20261020T073917Z | 5.76 | 4.93 |
| sources-100-20261020T073934Z | 4.93 | 4.86 |
| sources-100-20261020T073953Z | 4.86 | 5.05 |
| sources-100-20261020T074012Z | 5.05 | 4.88 |
| sources-300-20261020T074032Z | 4.88 | 6.08 |
| sources-300-20261020T074054Z | 6.08 | 13.58 |
| sources-300-20261020T074115Z | 13.58 | 14.52 |

The 8-source runs had the highest load average and still showed the tightest p50,
so host load does not appear to move the median much at that size; the 300-source
spread (p50 168 to 208 ms) is the widest and coincides with the busiest host.
That is an observation, not a measured cause.

## What the numbers show

The workload here is all-success: every source answers inside the band, so none
of the failure paths (timeout, circuit breaker, over-limit, refusal) was
exercised. Under that workload:

1. **Latency grows with source count and is bounded by `maxConcurrency`, not by
   the slowest source.** The sources answer in at most 25 ms (8, 32) or 40 ms
   (100, 300), yet the call takes 30, 51, 103 and 190 ms (p50). With
   `maxConcurrency` 8, 16, 32, 64, the fan-out runs `ceil(sources / maxConcurrency)`
   waves of 1, 2, 4 and 5 (rounded up from 4.7). A rough check: the mean
   per-source delay times sources, divided by `maxConcurrency`, is 15, 30, 70
   and 105 ms; the measured p50 minus that is 15, 21, 33 and 85 ms. The pattern
   fits a concurrency-limited fan-out plus a per-request cost that grows with
   source count, but this run did not profile it, so the split between the two is
   not established. 0.8 item: the fan-out and bulkhead rewrite. A re-run should
   show p50 near the slowest source's latency plus the fixed cost if the cap is
   lifted.
2. **Throughput falls roughly in step with source count** (127, 77, 38, 20 calls
   per second), with 4 workers and unpaced. Per source, the server did about
   1000 (8), 2500 (32), 3750 (100) and 5900 (300) source reads per second, which
   rises with size, so the cost is not simply per-source. With four workers and
   one host it is not possible to tell from this data whether the server, the
   driver, the synthetic source listener or the shared CPU is the limit. Not
   profiled: no CPU or heap profile and no per-stage timing was taken.
3. **No saturation point was found in the range measured.** No timeouts,
   transport failures, undispatched calls or clamped latencies occurred, even at
   300 sources. The first sign of strain is the widening 300-source spread,
   which coincides with host contention.
4. **The structural limits named in the plan could not be seen in this
   workload.** Because every source answers well inside the per-source timeout
   (3 to 10 s) the "timeout measured from the start of the fan-out" limit
   (`SourceFanOut.java:102,139`) is not visible: that needs a slow or hung
   source, where waves queued behind the bulkhead would eat the timeout. Likewise
   the read budget charged once per request needs a scenario whose budget is
   exhausted (every call uses a different subject, so the budget is not what
   these runs measure), and `SKIPPED_OVER_LIMIT` needs `maxSources` below the
   source count. These are 0.8 items and need new scenarios (a successor task;
   no harness change was made here).

## Not measured

- Failure paths: timeouts, circuit-open, over-limit, refusals, size and record
  caps. Every call succeeded.
- Quiescent-host performance; contention above was uncontrolled.
- Source counts above 300; soak (token lifetime 12 h, scope lifetime 8 h);
  cluster; network-separated sources.
- Server-side stage timing, CPU, heap or GC; which component is the limit.
- Slow non-timeout failures (not timed by the harness); fast transport failures
  are counted but not timed.
- Any host other than the one above.
- Paced load (`ratePerSecond` above 0); only the unpaced, 4-worker mode was run.
- Throughput includes the in-flight drain after the last dispatch, so it is
  slightly understated or overstated on short runs of 2 to 6 seconds.
