# 199 — Take and record the first load-test baseline

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 189, 190, 193, 196
**Decided (owner, 2026-10-09):** D-070-11 (a): 8, 32 and 100 sources (300
optional), three runs each, on one named machine; recorded internally in
`docs/plan/perf/`; published from 0.9.
**Owns:**
- docs/plan/perf/baseline-0.7.md (new)
- load-test/baselines/0.7/** (new; result files only)
- docs/plan/tasks/199-first-load-test-baseline.md (Outcome section only)

## Goal
0.8 rewrites the fan-out, the read budget and the timeout model. Without a
before-measurement nobody can say whether that rewrite helped. This task runs
the harness against the 0.7 code (limits bound, per-source results, record and
size caps enforced) and records what it measured, where it is slow, and why,
in a form 0.8 and 0.9 can repeat.

## Context
- `load-test/README.md` and scenario files (task 196).
- The known structural limits to look for in the numbers: per-source timeout
  measured from the start of the fan-out (`SourceFanOut.java:102,139`),
  bulkhead `max-concurrency` 4 across the request, budget charged once per
  request (`DefaultContextOrchestrator.java:174` on `main` today),
  `SKIPPED_OVER_LIMIT` beyond `max-sources`.
- `docs/conventions.md` "Acceptance-criteria discipline": no other build or
  harness run on the same machine during a measurement; record contention.

## Acceptance
- [ ] Scenarios at 8, 32 and 100 sources (300 optional) run three
      times each on one named machine, with the limits set as
      the scenario files state; every raw result file is committed under
      `load-test/baselines/0.7/` and holds only synthetic data.
- [ ] `baseline-0.7.md` states the commit, environment (CPU, memory, OS, JDK,
      container or not), scenario parameters, and per scenario: median and
      spread across the three runs of p50/p95/p99 latency and throughput,
      refusal codes by count, per-source status counts.
- [ ] It names the observed bottlenecks with evidence (for example a profile
      excerpt or a status-count pattern), each tied to a 0.8 roadmap item, and
      says plainly what was not measured.
- [ ] The run that produced each number can be repeated from the document alone.

## Out of scope
- Any code change, including to the harness (file a successor task instead).
- Soak, cluster and 500-source runs (0.8/0.9).

## Outcome
Ran sources-8, -32, -100 and -300 (optional) three times each with `load-test/run.sh` at
`558371184710ee673cb329edaee63e2a42092cf0` on an Apple M1 (8 cores, 16 GiB, macOS 27.0.1, JDK 25.0.4,
no container); raw files in `load-test/baselines/0.7/`, write-up in `docs/plan/perf/baseline-0.7.md`.
p50 ms (median of 3): 30 / 51 / 103 / 190; throughput per second: 127 / 77 / 38 / 20. All calls
answered, no refusals, timeouts or transport failures (one FAILED source read in 90000 at 300 sources).
Latency fits a concurrency-capped fan-out, not profiled. Failure paths and the named structural limits
were not exercised (all-success workload). Host was not quiescent (sibling agents building); recorded
in the document, and a first pass overlapped by sibling builds was discarded. `ps` after the runs
showed no server or harness process left.
