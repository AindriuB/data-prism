# 258 — Extend the load harness: failure-path scenarios, the cause of every FAILED, and a soak mode

**Repo:** `.` (the standalone `load-test/` build, outside the reactor)
**Release:** 0.8.0
**Base branch:** `origin/main`
**Depends on:** none
**Wave:** 1 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- load-test/src/main/java/** 
- load-test/src/test/java/**
- load-test/scenarios/** 
- load-test/README.md
- load-test/run.sh
- load-test/pom.xml *(test dependencies only)*

## Goal
The 0.7 baseline is all-success, so 0.8's fan-out rewrite, per-source budget and deadlines have nothing to be compared against on the paths that matter. Add scenarios that drive timeouts, read-budget exhaustion and over-limit selection; make the result file say why each FAILED call failed; and add a soak mode that can run for many hours without hitting the 12 h token and 8 h scope lifetimes, as the 196 review notes ask.

## Context
- docs/plan/PLAN.md, "Follow-ups from 199 and 209" and "NOTEs for the 0.8 soak work (from the 196 harness review)".
- load-test/README.md and load-test/src/main/java/io/github/aindriub/dataprism/loadtest/: `Scenario`, `SyntheticSources`, `ResponseClassifier`, `ResultAggregator`, `ResultWriter`, `SourceStatuses` (the status list), `TokenIssuer`, `ServerProcess`.
- docs/plan/HISTORY.md, "## 2026-10-19 — PR #132 review fixes": the harness defects already fixed, and the lesson to hunt the whole harness after a second review miss.
- docs/conventions.md, "Concurrent Maven verification" and "Do not run two tasks that drive the same exclusive resource at once".

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] New scenarios, each a file under `load-test/scenarios/`: slow synthetic sources past `per-source-timeout`; a subject read past `scope-read-budget`; more sources than `max-sources` in one request. Their expected classification is asserted by unit tests over recorded responses: `TIMED_OUT` per source, the `SCOPE_READ_BUDGET` refusal, and for over-limit either `SKIPPED_OVER_LIMIT` (0.7 servers) or the `SOURCE_SELECTION_TOO_LARGE` refusal (after 246).
- [ ] Every FAILED call in the result file carries a cause from a closed set stated in the README (for example connect timeout, read timeout, HTTP status class, MCP error code, unparseable body); the total of the causes equals the FAILED count, and a test proves it is not true by construction (each cause is counted from its own classifier branch).
- [ ] `SourceStatuses` recognises `RESOLVER_FAILED`; an unknown status is still counted as "not answered".
- [ ] Soak mode: a scenario can set a duration of up to 24 h; tokens are re-issued before expiry and scope ids rotate before the scope lifetime, both counted in the result; throughput excludes the in-flight drain after the deadline; a slow non-timeout failure is timed; the health check matches a server log with more than one connector.
- [ ] Every wait in the harness is bounded (connect, read, launch, cleanup), and a test covers each new one.
- [ ] Before merge, a whole-harness bug hunt runs; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -f load-test/pom.xml verify` passes.

## Out of scope
- Running the baselines (259) or the 0.8 soak (263).
- `load-test/baselines/**` and `load-test/soak/**`.
- `.github/workflows/load-test.yml` (257 owns the workflows; 263 adds the new scenarios to its choice list).
- Any change to the server under test.

## Outcome (2026-10-21, 0.8 wave 1)
Merged onto `record/0.8-wave1a` (task branch head 0ea1585e; tester PASS, 112 tests; reviewer APPROVE). The load harness has three failure scenarios, a closed `FailureCause` set (including `HTTP_STATUS_UNREPORTED` for the 5xx responses SDK 2.0.1 does not report), `RESOLVER_FAILED` and not-answered tallies, and a 24 h soak mode with credential rotation (3 or 4 rotations) and bounded waits. `ServerWatch` halts the run and keeps the logs if the server dies. The server log is rolling and capped, with a separate `server-console.log`, and the harness passes `--logging.threshold.console=OFF` because an empty console pattern produced a Logback error.

Red runs: 176 compile errors on the base; `SdkFailureCauseTest` red against the real SDK; mutating the REFUSED regex failed 3 tests.

Bug hunt: the fixed findings include the server watch, the capped server log, usage errors, connect and call timeout validation, and the separate console file (the later commits on the branch). The filed findings are the 263 follow-ups in PLAN.md. The implementer's full fixed and filed lists were not passed to the scribe, so this summary is from the branch history and the coordinator's follow-up list.
