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
