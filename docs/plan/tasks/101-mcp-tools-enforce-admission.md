# 101 — Enforce admission in the MCP tools and return correlationId as the join key

**Repo:** `.`
**Depends on:** 96, 98
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/**
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/**
- docs/tools.md *(refusal codes table and a new "Correlating with your AI-system logs" section)*

## Goal

Both MCP tools call task 98's `ToolAdmission` after scope resolution and
before the orchestrator. A refused call is audited with its code and never
reaches a source. Every tool result, success or refusal, carries that call's
`correlationId` in `_meta`, so a deployer can store it in the AI system's
own logs and join it to the Data Prism audit record (EU AI Act Arts. 12 and
26(6)). Admitted calls under an approval write `approvalId` and `approverId`
into the audit record.

## Context

- `GetEntityContextTool.java` `handle(...)`: authenticate, authorise
  (`:~150`), `scopeResolver.resolve` (`:~158`), then the orchestrator.
  `deny(...)` and `denyUnauthenticated(...)` each generate their own
  `UUID.randomUUID()` correlationId. `CompareEntitySourcesTool.java` has the
  same shape (`:286`, `:309`).
- `DataPrismMcpServer.java:60-155` has the `stdio(...)` and HTTP factory
  methods that construct both tools. `DataPrismAssembly`
  (integration-tests) and `DataPrismAutoConfiguration` call the existing
  overloads. Those callers are outside `Owns`, so the existing overloads must
  keep compiling.
- `ParameterFingerprinter(SecretKeyProvider).fingerprint(value,
  PrivacyContext)` in orchestration is the scope-keyed HMAC to use for the
  binding. The conventions forbid a bare digest.
- Task 96's `ContextResponse.correlationId()` is `@JsonIgnore`.
- MCP `_meta` keys use a reverse-DNS prefix:
  `io.github.aindriub.dataprism/correlationId`.
- `docs/conventions.md#errors` — refusal messages carry the code, never a
  value.

## Acceptance

- [ ] Both tools call `ToolAdmission.admit(caller, NAME,
      session.privacyContext().scopeId(), binding)`. `binding` is
      `ParameterFingerprinter.fingerprint` over `entityType + "\u0000" +
      subjectId`, plus `sources` for the compare tool.
- [ ] A refused admission returns `isError` with text `<code>`, plus
      `approvalId=<id>` for `APPROVAL_REQUIRED` and `APPROVAL_PENDING`. It
      increments `Metric.MCP_DENIED` and writes one DENY audit event with that
      code and `approvalId`. The orchestrator is not invoked. A test with a
      recording orchestrator asserts zero invocations for each of the seven
      codes from task 98.
- [ ] An admitted call under an approval passes `approvalId` and
      `approverId` to the orchestrator through task 96's `ContextRequest`
      fields. A test with a recording `AuditSink` asserts that the resulting
      ALLOW event carries both. No file under `data-prism-orchestration` is
      edited.
- [ ] Every `CallToolResult` from both tools carries
      `_meta["io.github.aindriub.dataprism/correlationId"]`. On success it
      equals `ContextResponse.correlationId()`. On every deny path it equals
      the correlationId written to that deny's audit event. A test per path
      asserts the equality against a recording `AuditSink`.
- [ ] The correlationId does not appear in `structuredContent` or in the
      text content of a successful result.
- [ ] `DataPrismMcpServer` gains overloads taking `ToolAdmission` and
      `ParameterFingerprinter`. The existing overloads delegate with
      `ToolAdmission.none()`. `tools/list` still returns exactly
      `get_entity_context` and `compare_entity_sources`, and a test asserts
      this.
- [ ] `mvn -pl data-prism-mcp,data-prism-integration-tests -am verify`
      passes.
- [ ] `docs/tools.md` lists the seven admission codes. A new section states
      that `correlationId` is returned in `_meta`, is the key to join a
      deployer's AI-system log entry to the matching Data Prism audit record,
      and is a random identifier that carries no data.

## Note from owner decision D8 (2026-10-06)

The approval flow is approved as planned and D8 is fully answered. A
configured high-impact tool call is refused with `APPROVAL_REQUIRED` and an
`approvalId`. A different person approves it on the operator port. The
identical call, with the same argument fingerprint, then succeeds exactly
once. Tests should cover the retry succeeding once and a second retry being
refused again, and an altered argument being refused.

## Out of scope

- Wiring a real `ToolAdmission` in Spring. That is task 104.
- Accepting a caller-supplied correlation id.
- The operator endpoints. That is task 105.
- `docs/audit.md`. Task 106 adds the join-key paragraph there.

## Note from task 98 (merged)

`ToolAdmission` takes a `Clock` as its fifth constructor argument. An
approval-required call consumes a rate-limit token before it is refused with
`APPROVAL_REQUIRED` or `APPROVAL_PENDING`, so a caller who retries while
waiting for approval burns its own limit. Decide whether that is intended; if
not, move the approval step ahead of the rate-limit step or refund the token.
Tasks 101 and 104 are where it becomes visible.

## Attempt 1 — failed

Branch `task/101-mcp-tools-enforce-admission` (72642c8). Reviewer: CHANGES.

- Defect 1 — approval binding too narrow (same class as task 100). The binding
  value is `entityType NUL subjectId`. Case is bound through scope, but
  purpose (JWT claim), privacyProfile (role-derived) and clientId are not. P is
  refused under purpose `fraud-review`, the approver approves, then P retries
  with a token whose purpose is `marketing` (also allow-listed), or with roles
  giving a looser profile. The call runs, and the ALLOW event shows an approval
  next to a purpose nobody approved.
  Fix: include purpose, privacyProfile and clientId in the HMAC'd binding value
  (GetEntityContextTool ~:211, CompareEntitySourcesTool ~:211). Add a test per
  field: approve under one value, retry under another → APPROVAL_REQUIRED and
  approval not consumed.
- Defect 2 — orchestrator-refusal results carry no `_meta` correlationId,
  although DefaultContextOrchestrator writes a DENY audit event (:213-226). An
  audited refusal, possibly after consuming an approval, cannot be joined.
  Owns is EXTENDED for this attempt (planning decision, main session):
  `data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java`
  and one new exception type under the same package. Make the orchestrator's
  correlationId available to the caller on every audited refusal, e.g. a new
  orchestration exception carrying it with the original as cause. Keep any
  existing `catch (PrivacyRefusedException …)` in other modules working:
  grep callers repo-wide. If that is impossible without editing core or other
  modules, stop and report. Return the id in `_meta` on those paths, with a
  test per path.
- Fix docs/tools.md: "Every tool result that the audit trail records
  carries…" must be true after the fix. Keep input-validation results (no audit
  event) explicitly excluded.
- Tests: correlation equality on the scope-resolution deny path, and the
  authorisation-deny test for CompareEntitySourcesTool too.
- Record the task-98 decision in docs/tools.md: an approval-required call
  consumes a rate-limit token before APPROVAL_REQUIRED/PENDING. State it as
  current behaviour, with a polling caller able to rate-limit itself, and
  revisit in 104.
- Not a defect here, for 104: production wiring (DataPrismAutoConfiguration
  ~:543) still uses the `none()` overload. Do not change autoconfigure in 101.
- Run the full reactor `mvn verify` and mkdocs `--strict`; report real exit codes.

## Attempt 2 — failed

Branch `task/101-mcp-tools-enforce-admission` (50f9d32). Reviewer: CHANGES. Everything else from attempt 1 is now met.

- Defect — capabilities are not bound (ToolCalls.java:82-90). They come from
  the token's roles (SecurityPolicy.capabilitiesFor) and change the output:
  SourceAliasing.java:42 returns real source names under EXPOSE_SOURCE_NAMES.
  P is refused APPROVAL_REQUIRED holding {investigator}, gets approved, then
  retries with an added role that grants EXPOSE_SOURCE_NAMES. The call is
  admitted, real source names are returned, and the ALLOW event shows an
  approval for output nobody approved. Fix: bind the sorted capability set in
  the length-prefixed encoding, and extend approvalIsBoundToPurposeProfileAndClient
  (or add a test) to cover a capability change on both tools.
  Also re-audit the binding javadoc's claim ("everything in the call that
  decides what the orchestrator will do") against InvestigationContext: list
  each field and either bind it or justify it in a comment.
- Also:
  - OrchestratorRefusalCorrelationTest: assert that the REQUEST_FAILED
    result text does not contain the cause's message (e.g. "scrubber down").
  - Add a CHANGELOG [Unreleased] line: ContextOrchestrator.buildContext now
    throws AuditedRefusalException (a PrivacyRefusedException) with
    code REQUEST_FAILED for audited internal failures. Starter users who map
    PrivacyRefusedException to 403 should check `code()`. CHANGELOG.md is
    added to Owns for this one line.
- Run the full reactor `mvn verify` and mkdocs `--strict`; report real exit codes.

## Attempt 3 — failed

Reviewer: CHANGES — one comment line; behaviour is correct and every other criterion is met.

- ToolCalls.java:90-91: the binding javadoc says "caseId: not bound. The
  orchestrator only copies it into the audit event; it does not alter what is
  returned." That is false. ScopeResolver.java:73/:86-87 sets
  scopeId = "case:" + caseId. That scope is part of the ToolAdmission approval
  key, keys the ParameterFingerprinter HMAC and pseudonymisation, and so does
  change the output. Replace the bullet with: "caseId: bound by ToolAdmission
  through scopeId `case:<caseId>`; it also keys the binding HMAC and
  pseudonymisation." No code change.
- Run the full reactor `mvn verify`; report the real exit code.
