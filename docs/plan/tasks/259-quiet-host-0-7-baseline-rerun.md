# 259 — Re-measure the 0.7 load baseline on a quiet host

**Repo:** `.`
**Release:** 0.8.0 (evidence; must land before any 0.8 performance comparison)
**Executor:** the owner, or an agent on a dedicated host with no other workload. Not a shared CI runner.
**Base branch:** `origin/main`; the measured code and harness are the `v0.7.0` tag
**Depends on:** none
**Wave:** 1 (combined 0.8 graph of 213, 218 to 231 and 238 to 263; 203 and 206 to 211 counted as landed)
**Owns:**
- load-test/baselines/0.7-quiet/** *(new)*
- docs/plan/perf/baseline-0.7-quiet.md *(new)*

## Goal
The 0.7 baseline in `docs/plan/perf/baseline-0.7.md` was measured on a contended host. `docs/conventions.md` says to re-measure rather than inherit numbers taken under contention, and 0.8 needs a baseline it can compare its fan-out rewrite and per-source charging against. Re-run the 0.7 scenarios on a quiet, named machine and record what 0.8 compares against.

## Context
- docs/plan/perf/baseline-0.7.md: scenarios, run counts and the contention caveat.
- docs/plan/PLAN.md, "Follow-ups from 199 and 209": rerun at least the 300-source scenario.
- D-070-11 (a): 8, 32 and 100 sources (300 optional), three runs each, one named machine; internal in `docs/plan/perf/`, published from 0.9.
- load-test/README.md at `v0.7.0`: how to run a scenario.

## Acceptance
- [ ] Scenarios `sources-8`, `sources-32`, `sources-100` and `sources-300` are each run three times with the `v0.7.0` harness against the `v0.7.0` server, on one machine, one at a time.
- [ ] Each run's result JSON is committed under `load-test/baselines/0.7-quiet/`, unedited.
- [ ] `baseline-0.7-quiet.md` records the machine (CPU model, cores, memory, OS, JDK), the one-minute load average before and after each run, and that nothing else ran; it gives p50, p95, p99, throughput and error counts per scenario with the spread of the three runs; it compares each with `baseline-0.7.md` and states which figures 0.8 compares against.
- [ ] No hostname, user name, network address or workplace detail appears in either file; the machine is described by its specification only.
- [ ] `git diff --stat` lists only the owned paths.

## Out of scope
- Harness changes (258) and the 0.8 soak (263).
- Publishing performance figures (0.9).
