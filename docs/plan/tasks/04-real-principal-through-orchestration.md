# 04 — Carry the real principal, its capabilities and its rejected arguments into audit

**Repo:** `.`
**Depends on:** 01
**Owns:**
- data-prism-audit/**
- data-prism-orchestration/**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/WorkedExampleTest.java

## Goal
`DefaultContextOrchestrator` attributes every event to a principal named `"system"`
that does not exist, and `SourceAliasing` decides whether to name real source
systems from a boolean nobody can hold. Thread an `InvestigationContext` down the
pipeline so audit records who actually asked, make the source-name exposure a real
capability check, record a caller's attempt to supply its own scope or principal
instead of ignoring it silently, and emit the §89 metrics the pipeline is the only
place that can see.

This task changes `ContextOrchestrator.buildContext`, so it necessarily touches
every call site of it. The two `mcp` files and `DataPrismAssembly` get the minimum
edit that keeps them compiling and honest; task 06 rewrites the `mcp` pair
afterwards, which is why it depends on this one.

## Context
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java:236-247 —
  `audit.record("system", ...)`, the line this task exists to delete
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceAliasing.java:32-57 —
  the `exposeRealNames` boolean and `SourceAliasing.exposed()`
- data-prism-audit/src/main/java/io/github/aindriub/dataprism/audit/AuditEvent.java:18-45 — the event
  shape, already pseudonymising the subject per §E
- data-prism-audit/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java:38-58 — the
  chained body string; anything added to the event must be added to the hash body too
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java:114-122 —
  `ignoresCallerSuppliedContext`, which today asserts only that the argument was ignored
- docs/pack.md:1719-1786 — §52 event fields and §53's do-not-audit list
- docs/pack.md:2739-2766 — §89 metric names
- docs/development-plan.md:135-137 — the slice exit criterion this task half-satisfies

## Acceptance
- [ ] `ContextOrchestrator.buildContext(ContextRequest, PrivacyContext, InvestigationContext)` is the
      only signature; no overload defaulting the caller survives, so a call site cannot forget one.
- [ ] `AuditEvent` gains `clientId`, `purpose`, `caseId` and `Set<String> rejectedArguments`, and
      `AuditRecorder` includes every one of them in the hashed body. A test mutates one field of an
      otherwise identical event and asserts the hash changes.
- [ ] No string literal `"system"` remains in `data-prism-orchestration/src/main`; a grep for it
      returns nothing. The audited principal, client, purpose and case come from the
      `InvestigationContext` argument.
- [ ] `SourceAliasing` no longer takes a boolean. `nameFor` returns the real source name only when the
      supplied `InvestigationContext` holds `Capability.EXPOSE_SOURCE_NAMES`, and the scope-local HMAC
      alias otherwise. `SourceAliasing.exposed()` is deleted; a test asserts that a caller without the
      capability sees an alias and one with it sees `customer-api`.
- [ ] `ContextRequest` gains `Set<String> rejectedArguments`, empty by default via a two-argument
      factory, holding argument *names* only. Passing a value there is impossible by type — the
      component is `Set<String>` of names and the javadoc says a value must never be put in it.
- [ ] A call whose rejected set is non-empty still succeeds, still uses the session's scope, and
      produces exactly one audit event whose `rejectedArguments` contains those names. `EndToEndTest`'s
      `ignoresCallerSuppliedContext` is extended to assert both halves: `scopeId` is still the session's
      and `rejectedArguments` equals `["scopeId","purpose"]`.
- [ ] The rejected names are also logged once at WARN with the names only. A test asserts that no
      *value* of a rejected argument appears in the audit event or in the log line, using a rejected
      value distinctive enough to grep for.
- [ ] `DefaultContextOrchestrator` takes a `PrivacyMetrics`, defaulting to `PrivacyMetrics.none()`, and
      increments `PRIVACY_TRANSFORMATIONS` per scrubbed source record, `PRIVACY_VALIDATION_FAILURES`
      per violation and `PRIVACY_FAILCLOSED` per `PrivacyRefusedException`. `SourceFanOut` records
      `SOURCE_LATENCY` and `SOURCE_ERRORS` per configured source name. A test with a recording
      `PrivacyMetrics` asserts the counts for one allowed call and one refused call.
- [ ] `DataPrismAssembly` exposes `investigationContext()` alongside `privacyContext()`, returning a
      caller whose principal is the literal `stdio-development`, whose case is `CASE-DEMO-1` and whose
      capabilities include `EXPOSE_SOURCE_NAMES` — the single-principal development mode the plan
      settled on, named in the javadoc as such — and also exposes `pseudonymisationVersion()` and
      `clock()`, which task 07 needs to build a `ScopeResolver`.
- [ ] `GetEntityContextTool` and `DataPrismMcpServer` take a supplier of the caller alongside the
      existing privacy-context supplier, and `GetEntityContextTool` passes the reserved argument names
      it saw into `ContextRequest`. It still reads no reserved argument's value.
- [ ] `mvn -B verify` from the repo root passes; all 212 tests at `7db0491` still pass, plus the new
      ones.

## Out of scope
- Any transport change, servlet, `contextExtractor` or authorisation decision in `mcp`. The tool keeps
  taking suppliers here; task 06 replaces them with the transport context.
- The `data-prism-security` module, including `ReservedArguments`. The tool hard-codes the reserved
  names it checks in this task, and task 06 replaces that with security's constant.
- The append-only audit sink and the chain verifier. Explicitly deferred past this slice.
- Micrometer. `PrivacyMetrics.none()` is the only implementation available here.
- `data-prism-hazelcast` metrics — task 05 owns that module.
