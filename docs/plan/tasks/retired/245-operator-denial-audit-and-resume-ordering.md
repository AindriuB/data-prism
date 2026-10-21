# 245 — Answer AUDIT_UNAVAILABLE on the re-identification and operator denial paths, and audit a failed resume

**Repo:** `.`
**Release:** 0.8.0, and backported to 0.7.1 by cherry-pick (task 266; owner decision 2026-10-21)
**Base branch:** `origin/main` once 244 has merged
**Depends on:** 244 (the metric constant). The edges to 210 and 223 were removed on 2026-10-21 for the 0.7.1 backport, because both are 0.8-only. The new test files are narrowed instead (see Owns), so nothing in them falls inside the 210, 222 or 223 sweeps. Whichever of those tasks lands after this one updates `OperatorHarness` and the shared fixtures, not these files.
**Decisions:** OD-08-14 decided 2026-10-20 (a): audit-first, with a linked `DENY:<code>` under the same correlation id when the apply fails.
**Wave:** 4 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java *(`refuse`, `refuseApproval`, `auditDeny` and the return values of their callers only)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OperatorAudit.java
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java *(the `recordDenial` call sites and `resume` only)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/ReidentificationOperatorController.java *(the denial mapping only)*
- data-prism-reidentification/src/test/java/io/github/aindriub/dataprism/reidentification/DenialAuditUnavailableServiceTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/DenialAuditUnavailableOperatorTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ResumeAuditOrderingTest.java *(new)*

The three new test files set no configuration property of their own: no `dataprism.security-policy.*`, no cluster-key, name-key or member-interface argument. They build no raw Hazelcast map key or lock key and start no Hazelcast member. They build the server only through the existing `OperatorHarness`, unchanged, or construct the classes under test directly with in-memory stores. This keeps them out of the 210, 222 and 223 sweeps and lets them run on `v0.7.0`. If a test needs a harness change, stop and report rather than editing `OperatorHarness`.

## Goal
Finish the owner decision that 244 starts. `ReidentificationService.refuse` and `refuseApproval` and the operator controller's `recordDenial` swallow a failed audit and still return the original refusal, so a denial can go unrecorded while the caller sees an ordinary code. Make each answer `AUDIT_UNAVAILABLE` and count the 244 metric. Separately, `OversightOperatorController.resume` writes `ALLOW:RESUMED` before applying the resume, so a failure in a custom `OversightState` leaves an ALLOW in the audit for a resume that did not happen; fix it as OD-08-14 decides.

## Context
- docs/plan/PLAN.md, "Follow-ups from 233" and "Follow-ups from the cluster-refusal retry docs fix".
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java:300-340: `refuseApproval`, `auditDeny` (returns `false` and carries on) and `refuse` (`// still a refusal`).
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OperatorAudit.java:25-70: `record` already throws a 503 `AUDIT_UNAVAILABLE`; `recordDenial` returns a boolean that most callers ignore.
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java:36, :106-120: the documented audit-first rule for permissive actions, and `resume`.
- **0.7.1 backport.** Task 266 cherry-picks this task's commits onto `release/0.7.1`, cut from `v0.7.0`, after 244. The diff may use only 244's `Metric` constant and code that exists at `v0.7.0`. It must not use the `core.crypto` or `hazelcast.seal` packages (`Sealer`, `KeyedNames`), `ClusterKeys`, `ChainableField`, the `.s1` map names, the keyed test setup from 222 and 223, or 210's case-authorisation settings. The owned main files have not changed since `v0.7.0` (`git diff v0.7.0 origin/main` over them was empty at filing), so a clean pick is expected. Re-check that at the base.
- HISTORY-INDEX row for task 235 (audit-first approval decisions with a linked DENY when the decision does not apply): the pattern OD-08-14 option (a) follows.

## Acceptance
- [ ] Before any change, the Outcome lists every denial-audit site in `data-prism-reidentification/src/main` and `data-prism-server/src/main/.../operator`, found by a whole-component search, and what each returns today when the audit write throws.
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Every `ReidentificationService` refusal whose audit write throws returns `Refused("AUDIT_UNAVAILABLE")` and increments the 244 metric once. A refusal whose audit succeeds returns its own code, as today.
- [ ] Every operator denial whose audit write throws answers HTTP 503 with code `AUDIT_UNAVAILABLE` and increments the metric once; no operator endpoint answers its original denial code after a failed denial audit. Tests cover each endpoint that can deny.
- [ ] `resume` follows OD-08-14 (a): the ALLOW is written first; if applying the resume then throws, a linked `DENY:<code>` under the same correlation id is written, the state is unchanged, and the response is the failure; if that DENY write also fails, the response is 503 `AUDIT_UNAVAILABLE`. A test with a throwing custom `OversightState` covers both.
- [ ] No sink exception text reaches any response body (sentinel test).
- [ ] Existing tests under `data-prism-reidentification` and `data-prism-server` pass unmodified.
- [ ] `./mvnw -B -pl data-prism-reidentification,data-prism-server -am verify` passes.
- [ ] The three new test files contain no `dataprism.` property literal, no `PrivacyCluster`, `HazelcastInstance` or `ClusterKeys` reference, and no edit to `OperatorHarness` (`git diff --stat` shows it untouched). After 266 cherry-picks 244 and then this task, `./mvnw -B -pl data-prism-reidentification,data-prism-server -am verify` passes on `release/0.7.1`, with these tests included.

## Out of scope
- The other 232, 235 and 216 follow-ups (`ALLOW:FORWARDED` correlation id, `forgetOne`, the EXPIRED pre-check, lease expiry during decide).
- Any Hazelcast oversight or pause-document change (sealed-cluster work).
- Documentation (261).

## Outcome

Landed 2026-10-21 on branch `task/245-operator-denial-audit-and-resume-ordering`, merged with a merge commit in PR record/0.7.1-wave3-245-268 so that 266 can cherry-pick the original commits. Backport commits, in pick order after 244: `168ca025`, `f0cfdb10`, `811a73eb`, `5fd20ef7`, `212fceb5`, `c81fa46f`, `846d9b2f`. None touches `docs/plan/`. Tester PASS on a re-run (the first run's surefire fork hung at teardown after the tests passed; filed as a follow-up). Reviewer APPROVE.

**What changed.**
- `ReidentificationService.refuse` and `refuseApproval` answer `Refused("AUDIT_UNAVAILABLE")` and count the 244 metric once when the DENY audit write throws (`5fd20ef7`).
- `OperatorAudit` takes the metrics provider (`f0cfdb10`, no behaviour change). Every operator denial whose DENY cannot be written answers 503 `AUDIT_UNAVAILABLE` and counts the metric (`212fceb5`).
- `OversightOperatorController.resume` stays audit-first (`ALLOW:RESUMED`, then apply). If the apply throws, a linked `DENY:<code>` is written under the same correlation id and the failure is returned; if that DENY also fails, the answer is 503 `AUDIT_UNAVAILABLE` (`212fceb5`). `846d9b2f` documents the order on the controller.

**Failing-first tests.** `168ca025` (`DenialAuditUnavailableServiceTest`) and `811a73eb` (`DenialAuditUnavailableOperatorTest`, `ResumeAuditOrderingTest`) were written before the fixes. `c81fa46f` makes the resume test detect an attempted DENY after a failed ALLOW. `846d9b2f` renames the tests as sentences.

**Accepted deviation.** `ReidentificationServiceTest.aClusterRefusalWithTheAuditSinkDownStillRefusesAndResolvesNothing` pinned the old answer and now expects `AUDIT_UNAVAILABLE`. The brief said existing tests pass unmodified; this is the one change, six lines.

**Follow-ups.** Filed in PLAN.md under "Follow-ups from 245": read endpoints write `ALLOW` before the read, a pause/resume race, `collect` can consume and then answer `AUDIT_UNAVAILABLE`, and the surefire teardown hang. The user-page documentation is 261's.
