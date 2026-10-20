# 235 — Never let an approval decision stand without its audit

**Repo:** .
**Release:** 0.7 (blocks the 0.7.0 release)
**Decided (owner, explicit, 2026-10-20):** D-0.7-AUD-1, fix before the 0.7.0 release. Design (a) below is
the planner's recommendation; the decision record in `PLAN.md` is the scribe's.
**Branch:** from `origin/plan/0.7.0` **after `docs/cluster-refusal-retry` has merged into it** (that branch,
`e4f6b11a` and `d79452b4`, edits `docs/multiple-instances.md`, `docs/tools.md`, `docs/reidentification.md`,
`docs/migration-0.7.md` and `CHANGELOG.md`). Line numbers below are at `db3a15e3`. Doc passages are named
by their text because that merge moves them.
**Depends on:** none (232 and 233 have landed; the branch above must merge first, see Branch)
**Owns:**
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java *(`approve` at `:85-119`, `revoke` at `:285-293` (deleted), `record` at `:262-268`, the constructors at `:49-61` (one overload added), the fields)*
- data-prism-reidentification/src/test/java/io/github/aindriub/dataprism/reidentification/ReidentificationServiceTest.java *(insertions, plus `approveAuditFailureLeavesNothingUsable` at `:252-262` if its assertions need tightening)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java *(the class Javadoc at `:27-34`, the constructor, `approve` at `:158-179`, `revoke` at `:250-258` (deleted); not `reject`, `denied`'s code mapping or `unavailableCode`)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OperatorAudit.java *(insertions only: overloads of `record` and `recordDenial` that take a correlation id)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ApprovalDecisionAuditTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ClusterRefusalOperatorTest.java *(the `new OversightOperatorController(...)` calls only, if the constructor changes)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ReidentificationWiring.java *(the `dataPrismReidentificationService` bean method at `:69-76` only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(insertion only: two constants)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(insertion only: two names in the expected set)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java *(insertion only: one rule and its test)*
- docs/reidentification.md *(the paragraph ending "the outcome is `REFUSED("AUDIT_UNAVAILABLE")`" after the step list; the `AUDIT_UNAVAILABLE` row of "Refusal codes"; the "**Audit.**" bullet of the operator API section. Not the two cluster-code rows and not the "What to do after either cluster code" block)*
- docs/tools.md *(the paragraph after the admission-code table that begins "After a different person approves, the identical call succeeds once". Not the table rows)*
- docs/multiple-instances.md *(under "Approvals under a partition", the paragraph that begins "Consuming, approving or rejecting changes the value" and holds "One residual remains". Not the two cluster-code passages in "A lock someone holds" and the refusal list, and not "What a cluster refusal leaves behind")*
- docs/audit.md *(the `ALLOW:<STAGE>` row of "policyDecision values" and one inserted sentence in the paragraph after that table)*
- docs/migration-0.7.md *(insertion only: one new bullet at the end of the "Other changes you will see:" list)*
- CHANGELOG.md *(insertion only: one bullet at the end of `[Unreleased]` → `### Security`)*
- docs/plan/design/contract-register.md *(insertion only: two rows in the section 5 metric table beside `dataprism.approval.release.skipped`, and the two names appended to the `Metric` cell of the enum inventory row)*
- docs/plan/tasks/235-no-unaudited-approval-decisions.md *(Outcome section only)*

## Goal

Approving an approval commits `APPROVED` to the store first and then writes `ALLOW:APPROVED`. If that
write fails, the code tries to undo the approval with `consumeApproved` and ignores a failed undo
(`ReidentificationService.java:112-117`, `:286-293`; `OversightOperatorController.java:171-177`,
`:251-258`). The approval can then stay `APPROVED`, so the requester's `collect` or the tool call's
admission can still use it, with no audit record of the decision, while the approver is told
`AUDIT_UNAVAILABLE`. This applies to re-identification approvals and to tool-call (four-eyes) approvals.
After this task an approval decision can take effect only after its `ALLOW:APPROVED` event has been
written. The undo goes away, so a failed undo can no longer leave anything behind.

## Chosen design: (a) audit first, with a linked refusal when the decision does not apply

**Why (a).** It is the only option whose safety depends on nothing but the order of two calls.
- (a) If the audit write fails, nothing has been committed, so nothing can be used. If the commit fails
  after the audit, the store is unchanged, or (if the reply was lost) it is `APPROVED` with its audit
  already written. No new store operation, status, SPI method, stored format or merge rank is added.
  `consumeApproved` and `collect` keep matching on `APPROVED` alone, which is an atomic compare-and-set
  (232). There are no clocks, TTLs or waits. The cost: the audit can record a decision that did not
  apply. The rules below make that pair readable, and the operator API has already taken the same
  trade for `resume` (`OversightOperatorController.java:31-33`, `:98`).
- (b) `APPROVED_PENDING_AUDIT` is also safe, but it adds what (a) does not need: a status that every
  `ApprovalStore`, the Hazelcast `Codec`, `ApprovalMergePolicy.rank` and the operator views must
  handle, and a new SPI `promote` that custom stores must implement. It also adds a second commit
  whose failure is a new way to fail: an approval that is stuck, unusable and cannot be re-approved
  (`NOT_PENDING`), so the requester has to ask again. Its audit can overstate a decision too, when the
  promote fails.
- (c) A bounded retry of the undo, then marking the approval unusable through the creation-token rule
  (releasing its id claim so `valid()` rejects it), is itself a cluster write. It can fail for the same
  reason the first undo failed. Its safety depends on a write succeeding, which the brief rules out.

**The order in both `approve` methods.**
1. Find and check the approval as today (`find`, kind, permission). Then also check, against the found
   value, everything the store would refuse: status `PENDING`, `now` before `expiresAt`, and approver
   not equal to requester. A failed check is refused as today with a single `DENY:<code>` event and no
   `ALLOW`, using the codes in today's switch (`ReidentificationService.java:102-108`,
   `OversightOperatorController.denied`).
2. Pick one correlation id for the call. Write `ALLOW:APPROVED` with it. If the write fails, return
   `AUDIT_UNAVAILABLE` (re-identification `Refused("AUDIT_UNAVAILABLE")`, operator 503
   `{"code":"AUDIT_UNAVAILABLE"}`), increment `APPROVAL_DECISION_AUDIT_UNAVAILABLE`, and **do not call
   `approvals.approve`**.
3. Call `approvals.approve`.
   - It succeeds: return success as today.
   - `ApprovalRefusedException` (a race since step 1): the decision did not apply. Write the refusal
     as `DENY:<code>` (same code mapping as today) **with the same correlation id**, and refuse as today.
   - `ClusterLockTimeoutException` or `ClusterOperationRefusedException`: these come from the entry's
     lock, before the commit (`HazelcastApprovalStore.decide`, `:542-551`). Treat them like the case
     above: a linked `DENY:<code>`, answered with the 233 code. Confirm in the Hazelcast store that
     neither can be thrown after the commit's `executeOnKey`. If one can, treat that path like the
     next case.
   - Any other `RuntimeException`, such as a lost reply: the outcome is unknown. Re-read with `find`.
     If it shows `APPROVED` or `CONSUMED` with `approverPrincipalId` equal to the caller, the decision
     applied: return success and write no `DENY`. Otherwise write the linked
     `DENY:REIDENTIFICATION_UNAVAILABLE` or `DENY:OVERSIGHT_UNAVAILABLE`, increment
     `APPROVAL_DECISION_UNCONFIRMED`, and refuse with that code. This closes the 232 follow-up "re-read
     with `find` on an uncertain outcome before auditing DENY" for approve.
   - If the linked `DENY` write fails in any branch, still refuse with the decision's code (do not
     replace it with `AUDIT_UNAVAILABLE`), and increment `APPROVAL_DECISION_UNCONFIRMED`.

**How a reader tells intent from effect** (this goes into `docs/audit.md`). `ALLOW:APPROVED` records
that an authorised approver submitted the decision. It took effect unless a `DENY:<code>` event with the
**same `correlationId`** and approval id follows. With `DENY:APPROVAL_NOT_FOUND`, `APPROVAL_NOT_PENDING`,
`APPROVAL_EXPIRED`, `SELF_APPROVAL`, `CLUSTER_LOCK_TIMEOUT` or `CLUSTER_OPERATION_REFUSED` it did not
apply. With `DENY:REIDENTIFICATION_UNAVAILABLE` or `DENY:OVERSIGHT_UNAVAILABLE` the outcome is unknown:
re-read the approval. Every use of an approval is audited separately with its approval id
(`ALLOW:RESOLVED`; the tool call's `ALLOW` carrying `approvalId`), so an approval that was used always
has both events. When the linked `DENY` itself could not be written, the `ALLOW` stands alone and the
audit overstates. `dataprism.approval.decision.unconfirmed` counts those cases. The audit can overstate
a decision. It can never understate one.

**Reject stays as it is.** It is restrictive: committing it first can only make an approval unusable,
and today's commit-then-audit with `APPLIED_AUDIT_UNAVAILABLE` (`:181-209`) is the documented fail-closed
order for restrictive actions. Re-identification has no reject path apart from the `open` undo.

**Consume and collect.** The only code that writes `APPROVED` is `ApprovalStore.approve`. Its only
production callers will audit first, so an `APPROVED` value with no `ALLOW:APPROVED` before it can no
longer be created. `consumeApproved` and `collect` need no new check. A new `ArchitectureTest` rule,
modelled on `ONLY_REIDENTIFICATION_CALLS_SUBJECT_FOR` (`ArchitectureTest.java:699-716`), keeps it so:
no class outside `ReidentificationService`, `OversightOperatorController` and `ApprovalStore`
implementations calls `ApprovalStore.approve`.

**Metrics.** Two `Metric` constants, untagged:
- `APPROVAL_DECISION_AUDIT_UNAVAILABLE("dataprism.approval.decision.audit_unavailable")`: an approve was
  refused because its `ALLOW:APPROVED` could not be written, and nothing was committed.
- `APPROVAL_DECISION_UNCONFIRMED("dataprism.approval.decision.unconfirmed")`: an `ALLOW:APPROVED` was
  written and no confirming outcome or linked `DENY` follows, because the outcome was unknown or the
  linked `DENY` could not be written.

`ReidentificationService` gains a constructor that takes `PrivacyMetrics`. The existing constructors
pass `PrivacyMetrics.none()`. `ReidentificationWiring` passes the metrics it already receives.
`OversightOperatorController` takes `ObjectProvider<PrivacyMetrics>`, falling back to
`PrivacyMetrics::none`, as `ReidentificationWiring.java:56-62` does.

## Context
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java:40-62,418-431,542-581,639-660: decide, the atomic commit, the store's own note on the lost-reply residual
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/ApprovalStore.java: the SPI, unchanged by this task
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ToolAdmission.java:94-113: tool-call consume, unchanged
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OperatorAudit.java:25-50: `record` throws `OperatorException(503, AUDIT_UNAVAILABLE)`; `entry` draws a random correlation id
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ClusterRefusalOperatorTest.java: building the controller directly over a store and audit
- data-prism-reidentification/src/test/java/io/github/aindriub/dataprism/reidentification/ReidentificationServiceTest.java:252-262: the existing audit-failure test, which passes only because the in-memory undo succeeds
- docs/plan/PLAN.md "Follow-ups from 232" (re-read on an uncertain outcome) and "Follow-ups from 233" (denial audit failures, 0.8)
- docs/conventions.md: privacy rules; neutral invented names in fixtures

## Acceptance
- [ ] `ReidentificationService` and `OversightOperatorController` contain no `revoke` method and do not call `consumeApproved`. Each `approve` writes `ALLOW:APPROVED` before it calls `ApprovalStore.approve`.
- [ ] Each of these tests fails on `db3a15e3` and passes on the branch. Each uses an audit sink that fails the `ALLOW:APPROVED` write, and a store wrapper whose first `consumeApproved` call throws (so the old undo fails):
  - [ ] re-identification: `request`, then `approve` returns `Refused("AUDIT_UNAVAILABLE")`; with the sink restored, `collect` by the requester returns `Refused("APPROVAL_NOT_APPROVED")`, the lookup is never called, and `find(id)` shows `PENDING`.
  - [ ] tool call: admission creates the approval, then `POST .../approve` answers 503 `{"code":"AUDIT_UNAVAILABLE"}`; with the sink restored, `ToolAdmission.admit` for the identical call is not admitted (`APPROVAL_PENDING`), and `find(id)` shows `PENDING`.
  - [ ] for both paths: a sink that records the store status at the moment it receives `ALLOW:APPROVED` sees `PENDING`. On `db3a15e3` it sees `APPROVED`.
- [ ] For both paths, a store wrapper whose `approve` throws `ApprovalRefusedException(NOT_PENDING)` after the pre-checks pass produces exactly `ALLOW:APPROVED` then `DENY:APPROVAL_NOT_PENDING`, with equal `correlationId` and approval id. The answer is `APPROVAL_NOT_PENDING` (409 on the operator API).
- [ ] For both paths, a store wrapper whose `approve` delegates and then throws `IllegalStateException` returns success with no `DENY` event. A wrapper that throws without delegating returns `REIDENTIFICATION_UNAVAILABLE` / 503 `{"code":"OVERSIGHT_UNAVAILABLE"}` with a linked `DENY` and increments `dataprism.approval.decision.unconfirmed` by 1.
- [ ] A self-approval, an expired approval or one already decided is refused with one `DENY:<code>` event and no `ALLOW:APPROVED`. The approver is not counted in either metric.
- [ ] The audit-down case increments `dataprism.approval.decision.audit_unavailable` by 1. A linked `DENY` whose write fails increments `dataprism.approval.decision.unconfirmed` by 1, and the answer keeps the decision's code.
- [ ] Happy path unchanged: four-eyes re-identification still writes `ALLOW:REQUESTED`, `ALLOW:APPROVED`, `ALLOW:RESOLVED` in that order and returns `Resolved`. The tool-call approve still answers 200 `{"status":"APPROVED","approvalId":...}` and the identical call is admitted once. Existing tests in `ReidentificationServiceTest`, `OperatorSurfaceTest` and `ClusterRefusalOperatorTest` pass unmodified, apart from constructor calls.
- [ ] `reject` behaves exactly as at `db3a15e3`: `OperatorSurfaceTest.aPauseOrRejectThatTookEffectButCouldNotBeAuditedSaysSo` passes unmodified.
- [ ] The new `ArchitectureTest` rule passes, and fails when a class in another module (for example `ToolAdmission`) is temporarily made to call `ApprovalStore.approve`. Revert that change before committing.
- [ ] `PrivacyMetricsTest` and `MicrometerPrivacyMetricsTest` pass with the two new names.
- [ ] `docs/audit.md`: the `ALLOW:<STAGE>` row no longer says every `ALLOW:APPROVED` is a step that succeeded, and the paragraph gives the correlation-id rule above. It also names the operator API's `operator:approve` events.
- [ ] `docs/reidentification.md`, `docs/tools.md` and `docs/multiple-instances.md` say, in the named passages only, that an approval is usable only after its `ALLOW:APPROVED` is written. The `multiple-instances.md` residual paragraph says that an approve whose reply is lost is re-read, and when it applied it is answered as success. Even when the re-read cannot confirm it, the decision is audited. `reject` keeps the residual. The cluster-code rows and "What a cluster refusal leaves behind" are byte-identical to `origin/plan/0.7.0` at the branch point (`git diff` shows no hunk in them).
- [ ] `CHANGELOG.md` `[Unreleased]` → `### Security` has one new bullet: a failed audit write on approve could leave a usable but unaudited approval; approve now audits first; the two new metrics. `docs/migration-0.7.md` has one new bullet telling audit readers how `ALLOW:APPROVED` pairs with a linked `DENY`.
- [ ] `./mvnw -B verify` over the full reactor exits 0. Use `-pl <module> -am` for any single-module run, because `~/.m2` holds a stale 0.6.0 core jar. `./mvnw -B -Prelease -DskipTests package` exits 0. `mkdocs build --strict` exits 0. Record the test counts in Outcome.
- [ ] No committed text names a real-world domain or organisation. Docs say "supports", never "compliant".

## Out of scope
- `reject`'s order and `APPLIED_AUDIT_UNAVAILABLE` (restrictive, already fail-closed).
- `ReidentificationService.open`'s swallowed undo of an unaudited `PENDING` request (`:206-215`). A
  request is not a decision, and any use of one still needs an audited approve and an audited
  `ALLOW:RESOLVED`.
- Denials whose audit write fails (`refuse`, `refuseApproval`, `recordDenial`): the 0.8 follow-up from 233.
- Any change to `ApprovalStore`, `InMemoryApprovalStore`, `HazelcastApprovalStore`, `ApprovalMergePolicy`,
  `ToolAdmission` or the `Status` enum.
- The lost-reply residual of `reject`, and of `consume` in admission.
- Any edit to the passages owned by `docs/cluster-refusal-retry`: the cluster-code rows in
  `docs/tools.md`, `docs/reidentification.md` and `docs/migration-0.7.md`, the two cluster-code passages
  and "What a cluster refusal leaves behind" in `docs/multiple-instances.md`, and that branch's CHANGELOG
  bullets. That subsection's approve row ("Nothing from this call") still holds for stored state, so
  leave it alone and note it in Outcome if you think it now needs an audit sentence.
- `HISTORY.md`, `HISTORY-INDEX.md`, `PLAN.md` (the scribe's).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any credential file.
- Fixtures use invented neutral principals, scopes and values (`alice`, `bob`, `scope-a`). No real
  personal data, credentials or tokens.

## Outcome

Decisions (owner): D-0.7-AUD-1, fix before 0.7.0. Design (a), audit-first, accepted 2026-10-20: an
`ALLOW:APPROVED` took effect unless a `DENY` with the same correlation id and approval id follows it. Reject
keeps commit-then-audit.

Built on `c4fdbd86` (`origin/plan/0.7.0`, which includes `docs/cluster-refusal-retry`).

**What changed.** Both `approve` methods now run: find and check, pre-check what the store would refuse (one
`DENY`, no `ALLOW`, no store call), write `ALLOW:APPROVED` under one correlation id, then `ApprovalStore.approve`.
A failed `ALLOW` write refuses `AUDIT_UNAVAILABLE` and never calls the store. `revoke` and its `consumeApproved`
undo are gone from both classes (`ReidentificationService.collect` still calls `consumeApproved`; that is the
requester's one use, not an undo). `ApprovalRefusedException`, `ClusterLockTimeoutException` and
`ClusterOperationRefusedException` write a linked `DENY`. Any other `RuntimeException` re-reads with `find`: an
`APPROVED` or `CONSUMED` value with the caller as approver is success with no `DENY`, anything else (including a
failed re-read) is a linked `DENY:REIDENTIFICATION_UNAVAILABLE` or `DENY:OVERSIGHT_UNAVAILABLE`.
`dataprism.approval.decision.unconfirmed` is incremented at most once per call (unknown outcome, or a linked
`DENY` that could not be written). `dataprism.approval.decision.audit_unavailable` once per refused `ALLOW`.

**Hazelcast check.** In `HazelcastApprovalStore.decide`, `ClusterLockTimeoutException` and
`ClusterOperationRefusedException` come from `ClusterLocks.acquire` and the lookups before the commit. The commit's
`executeOnKey` is not wrapped in `cluster(...)`, so a failure after it is a raw `RuntimeException` and takes the
unknown-outcome path. Neither cluster code can be thrown after the commit, so the linked-`DENY` treatment is right.

**Red evidence (before the fix).** The scaffolding commit `f5c051e7` adds only the metric constants, constructors
and `OperatorAudit` overloads and has no behaviour change, so the new tests fail on it exactly as on `c4fdbd86`
(they do not compile there). Failures, from `bb8293b9` (`ReidentificationServiceTest`, 7 of 32 failed, the 25 old
tests passed):
- `approveWithAuditDownLeavesTheApprovalPendingEvenWhenAnUndoWouldFail`: expected `Refused[APPROVAL_NOT_APPROVED]`
  but was `Resolved[subjectId=<withheld>]` (the bug: a failed undo left a usable, unaudited approval).
- `theApprovalIsStillPendingWhenItsAllowEventIsWritten`: expected `[PENDING]`, actual `[APPROVED]`.
- `anApproveThatLosesARaceIsAuditedAsAllowThenALinkedDeny`: expected `[ALLOW:APPROVED, DENY:APPROVAL_NOT_PENDING]`,
  actual `[DENY:APPROVAL_NOT_PENDING]`.
- `anApproveWhoseReplyIsLostAfterItAppliedIsSuccessWithoutADeny`: `Refused[REIDENTIFICATION_UNAVAILABLE]` was not
  `Approved`.
- `anApproveThatCannotBeConfirmedIsRefusedWithALinkedDenyAndCounted`: expected `[ALLOW:APPROVED,
  DENY:REIDENTIFICATION_UNAVAILABLE]`, actual `[DENY:REIDENTIFICATION_UNAVAILABLE]`.
- `aLinkedDenyThatCannotBeWrittenKeepsTheDecisionsCodeAndIsCounted`: metrics `{}`, expected `UNCONFIRMED=1`.
- `anApproveTheStoreWouldRefuseIsDeniedOnceWithoutAnAllowAndNeverReachesTheStore`: store `approve` calls expected 0
  but was 3.

From `400915ad` (`ApprovalDecisionAuditTest`, 7 of 8 failed; the happy-path test passed as a regression guard):
- `approveWithAuditDownLeavesTheApprovalPendingEvenWhenAnUndoWouldFail`: the identical call was admitted
  (`Expecting value to be false but was true`; the old undo failed as designed).
- `theApprovalIsStillPendingWhenItsAllowEventIsWritten`: expected `[PENDING]`, actual `[APPROVED]`.
- `anApproveThatLosesARaceIsAuditedAsAllowThenALinkedDeny`: actual `[DENY:APPROVAL_NOT_PENDING]` only.
- `anApproveThatCannotBeConfirmedIsRefusedWithALinkedDenyAndCounted`: actual `[DENY:OVERSIGHT_UNAVAILABLE]` only.
- `anApproveWhoseReplyIsLostAfterItAppliedIsSuccessWithoutADeny`: threw `OperatorException: OVERSIGHT_UNAVAILABLE`.
- `aLinkedDenyThatCannotBeWrittenKeepsTheDecisionsCodeAndIsCounted`: metrics `{}`.
- `anApproveTheStoreWouldRefuseIsDeniedOnceWithoutAnAllowAndNeverReachesTheStore`: store `approve` calls expected 0
  but was 3.

The failing-undo wrappers fail the first `consumeApproved` after an `approve` (not the first call overall, because
tool-call admission itself calls `consumeApproved`). No sleeps or waits are used anywhere in the tests.

**Owns widenings.**
1. `docs/multiple-instances.md`, the approve row of "What a cluster refusal leaves behind" (coordinator-approved):
   "Nothing from this call" became "No stored state from this call. For `approve`, the audit pair is already
   written ... A refused `reject` writes a `DENY` only." The rest of that row, and the rest of the subsection, is
   unchanged.
2. `ClusterRefusalOperatorTest.aClusterRefusalStaysARefusalWhenTheDenialCannotBeAudited`: not only a constructor
   call. It set `auditDown = true` and expected the cluster code. Under audit-first an unwritable `ALLOW` is
   correctly `AUDIT_UNAVAILABLE`, so the test failed. The intent (a cluster refusal whose denial cannot be written
   is still that refusal) is kept by a new `denialAuditDown` flag that fails only `DENY` events. The coordinator
   should confirm or revert this.
3. `ReidentificationServiceTest`: the existing fixture gained an `onEvent` hook, a recording `PrivacyMetrics`,
   and `service(...)` now builds with the metrics constructor and an optional store. No existing assertion changed.
4. `OversightOperatorController.denied`: its code mapping is untouched, but it was split into `denied` (audit) and
   `refusalOf` (the mapping) so the linked path reuses the mapping. `reject` still calls `denied` as before.
5. `ReidentificationService` gained a second public constructor `(ScopeIdentityIndex, ..., PrivacyMetrics)`, because
   `ReidentificationWiring` may not name `ScopeIdentityIndex::subjectFor` (`ArchitectureTest`).

**ArchitectureTest.** `onlyTheAuditingApproversCallApprove` passes on the real classes. The negative check is a
fixture class nested in `ArchitectureTest` (`ApproveCallerFixture`, a class that calls `store.approve`), and
`approveRuleCatchesACallFromAnotherClass` asserts the rule fails on it. This stands in for temporarily editing
`ToolAdmission` (a separate fixture file would be outside `Owns`).

**Verification.**
- `./mvnw -B verify`: exit 0. Surefire XML: 1995 tests, 0 failures, 0 errors, 0 skipped. Failsafe XML: 17 tests,
  0 failures, 0 errors, 0 skipped.
- `./mvnw -B -Prelease -DskipTests package`: exit 0.
- `mkdocs build --strict`: exit 0. The six `docs-site/hooks/check_*.py` with `DP_REQUIRE_NO_STUBS=true`: all exit 0
  (they read the built `site/`, so mkdocs is run into the default `site/` first; `site/` is untracked and not
  committed).
- `git diff c4fdbd86 -- docs/multiple-instances.md` has two hunks: the approve row and the residual paragraph.

**Noted, not fixed.**
- `docs/plan/design/contract-register.md`: the new constants sit at `Metric.java:25-26`, so the `Metric.java:26-29`
  line references of the four `audit.retention.*` rows are now two lines out. The task allowed insertions only.
- An expired approval is now refused at the pre-check without calling the store, so the store no longer marks it
  `EXPIRED` and frees its pending slot at that moment. It frees on its own expiry as before (live-pending counts are
  expiry-aware).
- The re-identification operator endpoint (`ReidentificationOperatorController.approve`) writes its own
  `ALLOW:FORWARDED` event under `operator:reidentify-approve` before calling the service, and that write failing
  gives `AUDIT_UNAVAILABLE` before the service runs. It is outside `Owns` and unchanged.
- The `Metric.java` register line and `reject`/`open` residuals are as listed in Out of scope.

**Coordinator acceptance and follow-up (after test and review; bug hunt clean).** The three deviations above
(the `denialAuditDown` flag in `ClusterRefusalOperatorTest`, the `denied`/`refusalOf` split, the extra
`ReidentificationService` constructor) are accepted. Follow-up commit adds:
- Guards for the `applied()` filters in both classes: `anotherApproversDecisionIsNotThisApproversSuccess` (another
  approver's decision committed, then a raw exception: refused with a linked `DENY`) and
  `aDecidedStatusThatCarriesThisApproverIsNotSuccess` (a `REJECTED` value carrying this approver is not success).
  The reidentification and operator versions each fail with the approver filter removed (re-identification: assertion
  at `ReidentificationServiceTest:409`; operator: approve returned success, `NullPointerException` in `statusOf`)
  and with the status filter removed (`:425`; operator likewise). The filters are restored.
  An `EXPIRED` variant was not added: an expiry commit does not record an approver.
- `aClusterRefusalBeforeTheCommitIsAllowThenALinkedDeny` in both classes, for `ClusterLockTimeoutException` and
  `ClusterOperationRefusedException`: `ALLOW:APPROVED` then `DENY:<code>`, one correlation id, the approval id.
  It fails when the branch uses the unlinked denial (`ReidentificationServiceTest:446`,
  `ApprovalDecisionAuditTest:309`). Restored.
- `docs/multiple-instances.md` approve row: the audit pair is qualified for a linked `DENY` that could not be written.
