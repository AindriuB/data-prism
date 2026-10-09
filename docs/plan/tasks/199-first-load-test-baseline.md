# 199 — Take and record the first load-test baseline

**Repo:** .
**Branch:** from `main` after tasks 189, 190, 193 and 196 have merged
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
