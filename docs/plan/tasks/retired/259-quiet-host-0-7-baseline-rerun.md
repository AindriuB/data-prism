# 259 — Re-measure the 0.7 load baseline on a quiet host

**Repo:** `.`
**Release:** 0.8.0 (evidence; must land before any 0.8 performance comparison)
**Executor:** the owner, or an agent on a dedicated host with no other workload. Not a shared CI runner.
**Base branch:** `origin/main`; the measured code and harness are the `v0.7.0` tag
**Depends on:** none
**Wave:** 1 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
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
- [x] Scenarios `sources-8`, `sources-32`, `sources-100` and `sources-300` are each run three times with the `v0.7.0` harness against the `v0.7.0` server, on one machine, one at a time.
- [x] Each run's result JSON is committed under `load-test/baselines/0.7-quiet/`, unedited.
- [x] `baseline-0.7-quiet.md` records the machine (CPU model, cores, memory, OS, JDK), the one-minute load average before and after each run, and that nothing else ran; it gives p50, p95, p99, throughput and error counts per scenario with the spread of the three runs; it compares each with `baseline-0.7.md` and states which figures 0.8 compares against.
- [x] No hostname, user name, network address or workplace detail appears in either file; the machine is described by its specification only.
- [x] `git diff --stat` lists only the owned paths.

## Out of scope
- Harness changes (258) and the 0.8 soak (263).
- Publishing performance figures (0.9).

## Outcome

Landed 2026-10-21 (PR record/0.8-wave1c-and-0.7.1-plan). Figures: `docs/plan/perf/baseline-0.7-quiet.md`; results: `load-test/baselines/0.7-quiet/`.

Task 259 re-ran the four 0.7 load scenarios (8, 32, 100, 300 sources, three runs each) with the `v0.7.0` harness on the owner's laptop, an Apple M1 with 16 GiB, with the owner's approval and other agents stopped. Every result JSON is committed under `load-test/baselines/0.7-quiet/`, and `docs/plan/perf/baseline-0.7-quiet.md` records the machine, loads, medians and ranges; 0.8 compares against it. Medians and throughput agree with the contended baseline within about 3 percent; the 300-source tail is 5 percent lower. The same wave of planning added the 0.7.1 patch: task 265 (the 0.7.0 user-generic element-type fail-open), the 0.7.1 backport notes for 244, 245, 257 and 265, and release brief 266. The owner decided on 2026-10-21 that 0.7.1 ships from `v0.7.0` with those four tasks and that everything else stays 0.8.0; whether to publish a GitHub security advisory for 0.7.0 remains open.

**Cost:** The laptop was never quiet: the desktop alone held the one-minute load at 2 to 4 on 8 cores. The coordinator's rule (repeat a run if the after-load exceeds 4) could not be met by the larger scenarios: all four 300-source runs and two 100-source runs ended above 4 although every before-load was 3.44 or lower, so each was repeated once and then flagged in the file rather than repeated indefinitely. The cause is not established; the run's own load is one candidate. A first pass without gaps between runs gave before and after loads up to 8, so its six runs were discarded and committed under `discarded/`. The 32-source p95 and p99 are higher than in the original baseline (ranges do not overlap) and this is unexplained, so use the new file for those. Do not call this a dedicated host. The review found the first draft's workplace detail and unrecorded discarded runs; both are fixed. 245's edges to 210 and 223 were replaced by narrowed test files so it can be cherry-picked to a branch cut from `v0.7.0`; 248 now depends on 265 and 261 on 266.
