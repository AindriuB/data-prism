# 96 — Make the orchestrator audit field dispositions and expose correlationId

**Repo:** `.`
**Depends on:** 92, 93
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextResponse.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextRequest.java
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFieldDispositionTest.java *(new)*
- docs/tools.md *(the `get_entity_context` audit paragraph only)*

## Goal

Join task 93's per-source dispositions to task 92's audit record, so that
every ALLOW and DENY event the orchestrator writes says which field paths got
which action. Also expose the orchestrator's `correlationId` on
`ContextResponse`, without serialising it, so that task 101 can return it to
the MCP client as the join key to the deployer's AI-system logs.

## Context

- `DefaultContextOrchestrator.java:150` generates `correlationId`. `:289`
  calls `scrubber.scrub(record, context)`. `:321-334` is `audit(...)`, which
  calls the 14-argument `AuditRecorder.record`.
- `ContextResponse.java:46-52` shows the precedent for a non-serialised
  component: `@JsonIgnore fieldsByNamespace`. The 5-argument secondary
  constructor (`:69`) must keep compiling, because tests in other modules use
  it.
- Task 92's `AuditEntry` and `record(AuditEntry)`. Path keys are
  `<sourceName>:<pointer>`, using the real source name. The audit trail always
  uses real names (`:323-325`).
- `PrivacyRefusedException.path()` and `.code()`. A refusal records that
  path as `REFUSED`.
- `ConfiguredJsonSourcesAutoConfiguration.java:131-141` constructs this
  orchestrator. Its constructor signature must not change.
- `AuditFilePiiScanTest` shows how to drive a full run against
  `FileAuditSink` and read the file back.

## Acceptance

- [ ] Every ALLOW event's `fieldDispositions` contains one entry per field
      scrubbed from every answering source, keyed `<sourceName>:<pointer>`.
      A unit test with two stub sources asserts the exact map.
- [ ] A DENY caused by `PrivacyRefusedException` records the refusing path
      as `REFUSED` in `fieldDispositions`. A unit test asserts it.
- [ ] `ContextResponse` gains `@JsonIgnore String correlationId`, equal to
      the `correlationId` written to that call's audit event. A test asserts
      the equality. `mapper.writeValueAsString(response)` for a response with
      a non-empty correlationId does not contain it. The 5-argument
      constructor still compiles.
- [ ] `ContextRequest` gains `approvalId` and `approverId` (`String`, `""`
      when absent). Every existing `ContextRequest` constructor still
      compiles and yields `""` for both. The orchestrator copies both into
      the ALLOW and DENY audit entries, and a unit test asserts this. Task 101
      populates these fields. This task freezes their shape.
- [ ] `DefaultContextOrchestrator`'s public constructors are unchanged.
      `git diff` shows no edit to `ConfiguredJsonSourcesAutoConfiguration.java`.
- [ ] `AuditFieldDispositionTest` runs a real `get_entity_context` call
      through `DataPrismAssembly` into a `FileAuditSink`. It asserts that the
      written record has `recordVersion` 2 and a non-empty
      `fieldDispositions`, and that none of the stub fixtures' identifying
      values appears anywhere in the file.
- [ ] `mvn -pl data-prism-orchestration,data-prism-integration-tests -am verify`
      passes, including `PiiLogScanTest` and `AuditFilePiiScanTest`
      unchanged.
- [ ] `docs/tools.md` states that each call's audit record lists field
      dispositions (path and action, never values).

## Out of scope

- Returning `correlationId` to the client in `_meta`. That is task 101.
- MCP-layer deny paths (unauthenticated, unauthorised). That is task 101.
- Accepting a caller-supplied correlation id.
