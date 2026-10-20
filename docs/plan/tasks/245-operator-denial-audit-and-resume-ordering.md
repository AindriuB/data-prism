# 245 — Answer AUDIT_UNAVAILABLE on the re-identification and operator denial paths, and audit a failed resume

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 244, 210 and 223 have merged
**Depends on:** 244 (the metric constant), 210 (210 owns configuration arguments across `data-prism-server/src/test/**`), 223 (sealed-cluster plan; 222 owns the cluster-key, name-key and member-interface arguments across `data-prism-server/src/test/java/**`, and 223 owns the raw map-key and lock-key expressions across `data-prism-server/src/test/java/**` and `data-prism-reidentification/src/test/java/**`; this task's new test files sit under both globs, so they are written against the keyed, key-configured test setup. 223 follows 222)
**Blocked on:** OD-08-14 (how `resume` stays audit-first without leaving a false ALLOW)
**Wave:** 5 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java *(`refuse`, `refuseApproval`, `auditDeny` and the return values of their callers only)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OperatorAudit.java
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java *(the `recordDenial` call sites and `resume` only)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/ReidentificationOperatorController.java *(the denial mapping only)*
- data-prism-reidentification/src/test/java/io/github/aindriub/dataprism/reidentification/DenialAuditUnavailableServiceTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/DenialAuditUnavailableOperatorTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ResumeAuditOrderingTest.java *(new)*

## Goal
Finish the owner decision that 244 starts. `ReidentificationService.refuse` and `refuseApproval` and the operator controller's `recordDenial` swallow a failed audit and still return the original refusal, so a denial can go unrecorded while the caller sees an ordinary code. Make each answer `AUDIT_UNAVAILABLE` and count the 244 metric. Separately, `OversightOperatorController.resume` writes `ALLOW:RESUMED` before applying the resume, so a failure in a custom `OversightState` leaves an ALLOW in the audit for a resume that did not happen; fix it as OD-08-14 decides.

## Context
- docs/plan/PLAN.md, "Follow-ups from 233" and "Follow-ups from the cluster-refusal retry docs fix".
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java:300-340: `refuseApproval`, `auditDeny` (returns `false` and carries on) and `refuse` (`// still a refusal`).
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OperatorAudit.java:25-70: `record` already throws a 503 `AUDIT_UNAVAILABLE`; `recordDenial` returns a boolean that most callers ignore.
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java:36, :106-120: the documented audit-first rule for permissive actions, and `resume`.
- HISTORY-INDEX row for task 235 (audit-first approval decisions with a linked DENY when the decision does not apply): the pattern OD-08-14 option (a) follows.

## Acceptance
- [ ] Before any change, the Outcome lists every denial-audit site in `data-prism-reidentification/src/main` and `data-prism-server/src/main/.../operator`, found by a whole-component search, and what each returns today when the audit write throws.
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Every `ReidentificationService` refusal whose audit write throws returns `Refused("AUDIT_UNAVAILABLE")` and increments the 244 metric once. A refusal whose audit succeeds returns its own code, as today.
- [ ] Every operator denial whose audit write throws answers HTTP 503 with code `AUDIT_UNAVAILABLE` and increments the metric once; no operator endpoint answers its original denial code after a failed denial audit. Tests cover each endpoint that can deny.
- [ ] `resume` behaves as OD-08-14 decides. Under its option (a): the ALLOW is written first; if applying the resume then throws, a linked `DENY:<code>` under the same correlation id is written, the state is unchanged, and the response is the failure; if that DENY write also fails, the response is 503 `AUDIT_UNAVAILABLE`. A test with a throwing custom `OversightState` covers both.
- [ ] No sink exception text reaches any response body (sentinel test).
- [ ] Existing tests under `data-prism-reidentification` and `data-prism-server` pass unmodified.
- [ ] `./mvnw -B -pl data-prism-reidentification,data-prism-server -am verify` passes.

## Out of scope
- The other 232, 235 and 216 follow-ups (`ALLOW:FORWARDED` correlation id, `forgetOne`, the EXPIRED pre-check, lease expiry during decide).
- Any Hazelcast oversight or pause-document change (sealed-cluster work).
- Documentation (261).
