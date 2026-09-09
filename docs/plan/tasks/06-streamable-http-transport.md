# 06 — Streamable HTTP transport, per-request caller context, and authorisation at the tool

**Repo:** `.`
**Depends on:** 03, 04
**Owns:**
- data-prism-mcp/**
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java

## Goal
stdio cannot carry an identity, so the transport and the security model are one
piece of work. Add the streamable HTTP transport, extract a per-request
authenticated caller into the MCP transport context, and have the tool read it via
`exchange.transportContext()` instead of a constant supplier. The tool then
authorises the call, resolves the session, and audits a denial — so a caller with
the wrong purpose or no capability never reaches the orchestrator.

stdio survives as an explicitly single-principal development mode, and refuses to
start when the deployment says it is in production.

## Context
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java:69 —
  `.callHandler((exchange, request) -> handle(request))`, discarding the exchange this task needs
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java:47-49 — the
  stdio transport, and the `JacksonMcpJsonMapper` wrapping the one project `ObjectMapper` that makes
  the privacy boundary structural. The HTTP transport must be given the same mapper
- `io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.Builder` and
  `io.modelcontextprotocol.server.McpTransportContextExtractor` in mcp-core 2.0.1 — confirmed present
  in the jar; no filter-and-ThreadLocal workaround is needed
- `io.modelcontextprotocol.common.McpTransportContext` — what `exchange.transportContext()` returns
- docs/architecture.md — boundary 1 (one mapper), boundary 4 (no caller-supplied scope), and the `mcp`
  row: depends on `orchestration`; `security` sits below it and is a permitted dependency
- data-prism-security ­— `AuthenticatedCaller`, `AuthorizationService`, `ScopeResolver`,
  `PrivacySession`, `ReservedArguments` from task 03
- docs/plan/PLAN.md:42-45 — the SDK plumbing is understood and supported

## Acceptance
- [ ] `data-prism-mcp/pom.xml` adds `jakarta.servlet:jakarta.servlet-api` at `provided` scope and
      `data-prism-security` at compile scope. It adds no Spring, Spring Security or Spring Boot
      dependency, and no second JSON library — the enforcer's banned-dependency rule still passes.
- [ ] `DataPrismMcpServer` exposes two named factories rather than one constructor: a stdio mode and a
      streamable HTTP mode built on `HttpServletStreamableServerTransportProvider`. Both are given the
      same `ObjectMapper` wrapped in `JacksonMcpJsonMapper`; `DataPrismObjectMapper` remains the only
      place in the module that constructs a mapper, so `ArchitectureTest.onlyDesignatedClassesCreateMappers`
      still passes unchanged.
- [ ] The HTTP mode is built with a `contextExtractor(McpTransportContextExtractor<HttpServletRequest>)`
      supplied by the caller of this API, so the SDK type is confined to the builder call and the
      extractor implementation itself can live in the application. The extractor contract is documented:
      it returns an `McpTransportContext` carrying an `AuthenticatedCaller`, or an empty context.
- [ ] The stdio factory requires an explicit `singlePrincipalDevelopmentMode` argument. Called with it
      false, or with a production indicator set, it throws at construction with a stable code naming
      stdio as a development transport. A test asserts the refusal; another asserts stdio still starts
      when the flag is explicitly true.
- [ ] `GetEntityContextTool` reads the caller from `exchange.transportContext()`. A call with no caller
      in the context is refused with a stable code and never reaches the orchestrator — a test with a
      recording orchestrator asserts zero invocations.
- [ ] The tool calls `AuthorizationService` then `ScopeResolver`, and passes the resolved
      `PrivacyContext` and `InvestigationContext` to `buildContext`. A denied decision returns an error
      result carrying the decision's code and no other detail, increments `MCP_DENIED`, and writes an
      audit event with `policyDecision` equal to the denial code.
- [ ] Every accepted call increments `MCP_REQUESTS`.
- [ ] Reserved argument names come from `ReservedArguments`; the hard-coded list added in task 04 is
      deleted. A call supplying `scopeId`, `purpose`, `principalId` or `caseId` is served from the
      resolved session and the names appear in `rejectedArguments` on the audit event.
- [ ] A test drives the tool handler twice with two different `AuthenticatedCaller`s carrying different
      `case_id` claims and asserts the two responses give the same subject two *different* pseudonyms —
      the scope isolation property, proved at the tool rather than at the resolver.
- [ ] `ExampleApplication` still runs stdio, now passing the development flag explicitly and using the
      assembly's development caller.
- [ ] `mvn -B verify` from the repo root passes; all 224 tests at `da0bbdf` still pass.

## Out of scope
- The OAuth2 resource server, JWKS configuration, the servlet registration and the Spring Boot
  application — task 07 owns all of it. This task defines the extractor interface it plugs into.
- JWT signature verification. The extractor receives claims already verified by the filter chain.
- `compare_entity_sources`, `search_entity_data` and `describe_entity_model`. Only
  `get_entity_context` exists in this slice.
- Any tool that opens, selects or extends a scope, and any tool that re-identifies a pseudonym.
- Any dependency from `mcp` on a connector module.
