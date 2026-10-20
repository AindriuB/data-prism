# 244 — Answer AUDIT_UNAVAILABLE on every tool path when a denial cannot be audited

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 220 has merged
**Depends on:** 220 (sealed-cluster plan; 220 inserts every `dataprism.cluster.*` constant into `Metric.java` and `PrivacyMetricsTest.java`, which this task also inserts into)
**Blocked on:** OD-08-13 (the metric's name and shape)
**Wave:** 3 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DenialAuditFailedException.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the DENY-path `audit(...)` call in the `buildContext` catch block only)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java *(the orchestrator-call catch blocks and the two `deny` methods only)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java *(the same parts only)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ToolCalls.java *(refusal-result helpers only)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/AuditUnavailableException.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(one insertion)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(one insertion)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/DenialAuditFailureTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DenialAuditUnavailableToolTest.java *(new)*

## Goal
Owner decision (follow-up of 233): a denial whose audit write fails answers `AUDIT_UNAVAILABLE` and counts a metric, the same on every path. On the tool paths today, a failed DENY write in the orchestrator escapes as an ordinary exception and the client gets "the request could not be completed", while the tools' own `deny` methods throw `AuditUnavailableException`. Make every tool-side denial whose audit fails return one documented result with code `AUDIT_UNAVAILABLE`, count it, and carry nothing from the sink's exception. Task 245 does the same for the re-identification and operator paths and reuses this metric.

## Context
- docs/plan/PLAN.md, "Follow-ups from 233": the owner decision and the paths it names.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java:200-335: the denial paths (unauthenticated, correlation id, authorisation, scope resolution, admission, orchestrator refusal) and the two `deny` methods that already throw `AuditUnavailableException`.
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java, the catch block of `buildContext`: the DENY audit runs inside it.
- docs/conventions.md, "Privacy rules": the reviewed `LOG.error(msg, auditFailure)` exception at these sites stays.

## Acceptance
- [ ] Before any change, the Outcome lists every place under `data-prism-mcp/src/main` and `data-prism-orchestration/src/main` where a denial is audited, found by a whole-component search, and the result each gives today when the audit write throws.
- [ ] Tests are written first and fail on the base commit for each path whose result changes; the Outcome names them.
- [ ] For every denial path of both tools (unauthenticated, correlation id required or invalid, authorisation denied, scope resolution refused, admission denied, orchestrator refusal), when the audit sink throws, the tool result is an error whose text is exactly `refused: AUDIT_UNAVAILABLE`, the metric chosen under OD-08-13 is incremented exactly once, and no orchestrator or adapter call follows (a counting adapter proves it).
- [ ] The sink's exception message, class name and stack trace appear in no tool result (a sentinel message is asserted absent). The server log keeps the reviewed `LOG.error(msg, auditFailure)` line.
- [ ] When the DENY audit write in the orchestrator fails, the orchestrator throws `DenialAuditFailedException` (no cause text in its message) and the tool maps it to the result above.
- [ ] A refusal whose audit write succeeds is unchanged: every existing test in `data-prism-mcp` and `data-prism-orchestration` passes unmodified.
- [ ] `./mvnw -B -pl data-prism-orchestration,data-prism-mcp -am verify` passes.

## Out of scope
- Re-identification and operator paths, and the resume ordering (245).
- An ALLOW whose audit write fails. Record what it does today in the Outcome; changing it needs an owner decision.
- `docs/tools.md` (261).
