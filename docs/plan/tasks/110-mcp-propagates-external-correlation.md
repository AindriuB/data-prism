# 110 — MCP tools and orchestrator carry the external correlation id to audit and sources

**Repo:** `.`
**Depends on:** 101, 108, 109, 118, 123
*(118 added 2026-10-06: task 118 edits `data-prism-mcp/**` and `DefaultContextOrchestrator` after 101, so this task starts from its result.)*
*(123 added 2026-10-06: task 123 changes the DENY `policyDecision` in `DefaultContextOrchestrator` and its expected value in `OrchestratorRefusalCorrelationTest`, both owned here.)*
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/**
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextRequest.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/ExternalCorrelationPropagationTest.java *(new)*
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ReservedArguments.java
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/ReservedArgumentsTest.java
- docs/tools.md *(a new "Passing your correlation id" section and the reserved-argument list only)*

## Goal

Both MCP tools read an `InboundCorrelation` from the MCP transport context,
which an HTTP context extractor populates (task 113). Tool arguments are
never a source for it. A present id is put on `ContextRequest`, written into
every audit event the call produces, ALLOW and every DENY, and attached to
every `DataRequest` in the fan-out through `SourceCallContext`. When the
server is configured to require an id, a call without a valid one is refused
and audited, and no source is called.

## Context

- Task 101's tool shape: authenticate, authorise, resolve scope, admit, then
  orchestrate. Every deny path writes its own audit event. Task 101 added
  `_meta` correlationId on every audited path and an orchestration exception
  carrying the orchestrator's correlationId.
- `GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY` is the precedent for a
  transport-context key, and for treating a value of the wrong type as absent.
- `DefaultContextOrchestrator.requestsPerSource` (`~:330`) builds
  `DataRequest.of(...)` per source. `SourceFanOut` passes each request
  through unchanged, so it needs no edit.
- `ReservedArguments.NAMES` — reserved names are ignored and recorded in
  `rejectedArguments`. They are never read.
- Task 108: `InboundCorrelation`, `ExternalCorrelationId`,
  `SourceCallContext`. Task 109: the 18-argument `AuditEntry`.

## Acceptance

- [ ] `DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY` is a public
      constant. A value under it that is not an `InboundCorrelation` is
      treated as `absent()`.
- [ ] `DataPrismMcpServer` gains overloads that take a `CorrelationRequirement`
      (`OPTIONAL` or `REQUIRED`). Every existing overload delegates with
      `OPTIONAL` and keeps compiling. `tools/list` is unchanged, and a test
      asserts it.
- [ ] Under `REQUIRED`, an `absent()` correlation refuses with
      `EXTERNAL_CORRELATION_ID_REQUIRED`, and a `rejected()` one with
      `EXTERNAL_CORRELATION_ID_INVALID`. Each refusal writes one DENY audit
      event with that code and increments `Metric.MCP_DENIED`. The result
      carries `_meta` correlationId as task 101 defines it. A recording
      orchestrator asserts zero invocations. One test per code per tool.
- [ ] Under `OPTIONAL`, a `rejected()` correlation proceeds with no external
      id: the audit field is `""`. A test asserts this.
- [ ] A present id appears as `externalCorrelationId` on the ALLOW event and
      on every DENY event of that call, including admission refusals and
      orchestrator refusals. A test per path uses a recording `AuditSink`.
- [ ] `ContextRequest` gains `externalCorrelationId`
      (`Optional<ExternalCorrelationId>`, never null). Existing constructors
      and factories keep compiling and give `Optional.empty()`.
- [ ] Every `DataRequest` the orchestrator builds for a call carries that
      call's id in `context().externalCorrelationId()`. A test with three
      recording adapters, run in parallel, asserts all three received it.
      Two concurrent calls with different ids each see only their own id.
- [ ] `ReservedArguments.NAMES` adds `correlationId`,
      `externalCorrelationId` and `traceparent`. A test calls each tool with
      all three as arguments, with and without a transport-context id. It
      asserts that they appear in `rejectedArguments` and that the audit
      `externalCorrelationId` is only ever the transport-context value or
      `""`.
- [ ] The correlation id never appears in `structuredContent` or text
      content. A test asserts this.
- [ ] `mvn -pl data-prism-mcp,data-prism-orchestration,data-prism-security,data-prism-integration-tests -am verify`
      passes.
- [ ] `docs/tools.md` states that the id is read only from the configured
      HTTP header and never from arguments. It lists both refusal codes and
      the three new reserved names.

## Out of scope

- Reading the HTTP header and binding properties. That is task 113.
- Sending the id to REST sources. That is task 111. Custom adapters read it
  from `DataRequest`.
- Returning the external id in `_meta`. The client already has it.
- Any change to `ToolAdmission` or the approval binding.
