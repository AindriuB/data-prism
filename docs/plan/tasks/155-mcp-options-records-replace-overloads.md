# 155 — Replace MCP tool, server-factory and orchestration overloads with validated options records

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2).
**Depends on:** none
**Owns:**
- data-prism-mcp/src/**
- data-prism-orchestration/src/**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java (call sites of the changed constructors and factories only)
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java (call sites only)
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceEndToEndTest.java (call sites only)
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/** (call sites only)
- docs/tools.md (the `DataPrismMcpServer.stdio(...)` sample at about :74 only)

## Goal
Owner decision D-0.6-5, a clean break with no deprecation cycle (the owner
states there are no external users). `GetEntityContextTool` and
`CompareEntitySourcesTool` each have eight public constructors,
`DataPrismMcpServer` has six `stdio` and six `streamableHttp` factories,
`ContextRequest` four constructors and `SourceFanOut` three. Each gets one
public constructor (or one factory per transport) taking a validated options
record with `defaults()`, and every old overload is removed. The fail-closed
checks tasks 110 and 150 added must hold on every remaining public path.

## Context
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java:106-210 — the eight constructors and the private canonical one; note `Objects.requireNonNull(fingerprinter, ...)` on every admission overload and the `ToolAdmission.none()`/`null` pairing on the rest.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java:105-195 — the same shape.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java:90-300 — the factory overloads; task 150's stdio drop of the entity-type registry is the regression to guard against.
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextRequest.java:80-121, SourceFanOut.java:58-75.
- docs/plan/tasks/retired/110-mcp-propagates-external-correlation.md, "Attempt 1 — failed" — why a public canonical constructor accepting a null fingerprinter defeats four-eyes approval binding.
- docs/plan/tasks/retired/150-audit-only-registered-entity-types.md, Acceptance — the `UNREGISTERED` default for a `ContextRequest` with no audited value, and `UnregisteredEntityTypeAuditTest`.
- PLAN.md follow-ups folded in here: (k) (cover `CompareEntitySourcesTool` and both factories in the null-fingerprinter test, assert the "fingerprinter" message, add a `streamableHttp` case to `toolsListUnchanged`) and item 1 of "Follow-ups from tasks 104 and 123" (the old `stdio`/`streamableHttp` overloads, and `ExampleApplication` moved to the admission path). `ToolAdmission.none()` itself is not removed here; see Out of scope.

## Acceptance
- [ ] Each of `GetEntityContextTool`, `CompareEntitySourcesTool`, `ContextRequest` and `SourceFanOut` has exactly one public constructor; `DataPrismMcpServer` has exactly one public `stdio` and one public `streamableHttp` factory. Checkable with `javap -public` on the built classes; the hand-back lists the before and after signatures.
- [ ] Each takes an options record (one per type, or one shared record for the two tools; the implementer chooses and says which) that is immutable, validates in its compact constructor, and has a static `defaults()` whose values equal what the most-used removed overload applied at base (for the tools: `CorrelationRequirement.OPTIONAL`, `CorrelationMdc.off()`, the base default for `AuditedEntityTypes`, no development caller).
- [ ] The options record refuses, with `NullPointerException` or `IllegalArgumentException` whose message contains `fingerprinter`, any admission other than `ToolAdmission.none()` without a fingerprinter. A test per tool and per factory (four tests, or four cases of one parameterised test) asserts the refusal and the message.
- [ ] `ContextRequest` built without an explicit audited entity type audits `AuditedEntityTypes.UNREGISTERED`; `AuditedEntityTypeOrchestratorTest` asserts this through the new constructor, replacing its legacy-constructor case.
- [ ] Both `DataPrismMcpServer` factories deliver the caller-supplied `AuditedEntityTypes` to both tools. A test per factory registers `CUSTOMER`, calls each tool on a DENY path, and asserts the audit record's `entityType` is `CUSTOMER` (not the default); a mutation that drops the registry from either factory fails it, reported in the hand-back.
- [ ] `toolsListUnchanged` (or its successor) has a `streamableHttp` case.
- [ ] `ExampleApplication` builds its stdio server through the admission path with a fingerprinter.
- [ ] No tool name, tool schema, refusal code, audit field or audit record version changes: `git diff` touches no string literal that is a refusal code or tool name, and the audit record tests in `data-prism-core` are unchanged.
- [ ] `grep -rn 'new GetEntityContextTool(\|new CompareEntitySourcesTool(\|new SourceFanOut(\|new ContextRequest(' docs README.md` shows no call using a removed signature; `docs/tools.md` shows the new form.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- Removing or deprecating `ToolAdmission.none()` (data-prism-security). Owner decision pending (see the plan's return note, D-0.6-6).
- A stdio MDC option (PLAN follow-up (ac)). The options record may carry `CorrelationMdc`, but wiring MDC for stdio in `ExampleApplication` or the auto-configuration is not this task.
- Moving any type to another package (tasks 156, 157).
- Splitting `DataPrismAutoConfiguration` (task 159). Edit only the call sites.
- Any change to `ToolCalls`, `ToolAdmission`, approval binding, or the order of checks inside a tool call.

## Owner decision D-0.6-6: decided 2026-10-08 (option C)

`ToolAdmission.none()` is kept, but the options record has no default for admission. Every caller must name either `ToolAdmission.none()` or a real admission policy, so approvals are never turned off silently.
- A real admission still requires a fingerprinter, validated in the record.
- `none()` is the only admission allowed without one.
- Spring wiring passes `none()` explicitly when no oversight is configured.

Acceptance:
- A test shows that building the options without naming an admission fails at construction.
- Runtime approval behaviour is unchanged. This includes per-tool `approval-required-tools`, single-use approvals for the identical call, and the TTL.
