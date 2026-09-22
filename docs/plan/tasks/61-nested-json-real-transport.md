# 61 — Prove the nested-JSON refusals against the real MCP transport

**Repo:** .
**Depends on:** 60
**Owns:**
- data-prism-integration-tests/pom.xml
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredJsonNestedHttpTest.java (new)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredJsonNestedFixtures.java (new, if needed)

## Goal
Task 60's unit coverage runs against the engine directly. The two failures that
matter — a response nesting deeper than the catalogue declares, and a type
mismatch at depth — must be shown to refuse through the real server over the
real transport, because twice in the last wave a task assumed a bare JSON body
from an endpoint that actually negotiates SSE and only a real run caught it.

## Context
- `data-prism-integration-tests/.../http/McpHttpEndToEndTest.java:274` — the
  `Accept: application/json, text/event-stream` handshake this must reuse; the
  response is an SSE frame, not a bare JSON body.
- `docs/plan/tasks/60-nested-json-catalogues.md` — the grammar, the new refusal
  code, and the leak-check parity requirement under test here.
- `data-prism-connectors-rest/.../ConfiguredJsonSourceEndToEndTest.java:103-133`
  — the `com.sun.net.httpserver.HttpServer` fixture pattern for a configured
  source's upstream.
- `docs/plan/PLAN.md`, "Task 58 — done" — both SSE incidents.

## Acceptance
- [ ] `data-prism-integration-tests/pom.xml` gains a test-scoped dependency on
      `data-prism-connectors-rest` and nothing else; the module still builds.
- [ ] A test boots the application with an MCP HTTP transport and a
      configuration-driven JSON source whose catalogue declares one nested
      catalogue, calls `get_entity_context` over HTTP with the SSE `Accept`
      header, parses the SSE frame whose id matches the request, and asserts the
      nested object comes back scrubbed under the nested catalogue.
- [ ] Deeper-than-declared: the upstream fixture returns an object where the
      catalogue declares a scalar leaf; the tool result is a refusal carrying the
      stable code task 60 introduced, and the response body contains no value from
      the fixture.
- [ ] Type mismatch at depth: the upstream fixture returns a scalar where the
      catalogue declares a `nested:` object, and the call refuses rather than
      passing the scalar through.
- [ ] A property inside the nested object that the nested catalogue does not
      declare refuses over the same transport.
- [ ] Each refusal assertion names the expected code string, not
      `isInstanceOf(RuntimeException.class)` — see `docs/conventions.md`,
      "Assertions that cannot fail".
- [ ] `mvn -pl data-prism-integration-tests -am test` passes.

## Out of scope
- Any edit under `data-prism-connectors-rest/` or `data-prism-core/`.
- Version strings in `pom.xml` (task 70 owns the 0.3.0 bump).
- Documentation (task 62).
