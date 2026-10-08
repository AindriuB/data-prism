# 142 — Pin what Spring Boot 4 moved: Jackson 2 converters, actuator JSON, the operator error path

**Repo:** `.`
**Release:** 0.5.0 (part of 139)
**Depends on:** 141
**Owns:**
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/boot/** *(new package)*

## Goal

Task 141 keeps the classpath on Jackson 2 by excluding Boot 4's default
Jackson 3 starter and relying on Boot's deprecated Jackson 2 support. The
enforcer stops Jackson 3 from arriving, but nothing proves that the pieces
Boot 4 moved still behave the same at runtime. This task adds tests on the
real server that fail if the MVC converters, the actuator endpoint mapper or
the operator port's `/error` handling silently change.

## Context

- Task 141's Context table: what moved, and why the classpath is Jackson 2.
- Without Jackson 3, Boot 4 configures MVC JSON through
  `Jackson2HttpMessageConvertersConfiguration` and actuator JSON through
  `Jackson2EndpointAutoConfiguration`. If `spring-boot-jackson2` is dropped,
  the server could start with no JSON converter at all, or with
  `JacksonJsonHttpMessageConverter` if Jackson 3 arrives through another
  route.
- Task 105 review follow-up, still open in PLAN: "Add a behavioural test that
  an MCP-port MVC error still reaches Boot's `/error`. Today it is checked
  only structurally." Boot 4 renamed the error-path property
  (`server.error.path` → `spring.web.error.path`), so the gap now matters.
- The `auditIntegrity` health contributor (task 103), registered by
  `DataPrismAutoConfiguration.AuditIntegrityHealth`.
- `data-prism-server/src/test/java/.../server/operator/OperatorHarness.java` —
  the existing way to start a real server with the operator port. Reuse it
  from the new package; do not edit it.
- `docs/conventions.md#tests` — every new guard needs a mutation proof in the
  commit body.

## Acceptance

- [ ] A test on a started server context asserts that the
      `RequestMappingHandlerAdapter`'s message converters include a
      `MappingJackson2HttpMessageConverter` and that no converter class lives
      in a `tools.jackson` package or is named `JacksonJsonHttpMessageConverter`.
- [ ] A test asserts that `Class.forName("tools.jackson.databind.ObjectMapper")`
      throws `ClassNotFoundException` on the server's test classpath.
- [ ] An HTTP test against a running server whose test properties turn the
      health endpoint on (`management.endpoint.health.access=read-only`,
      `management.endpoints.web.exposure.include=health`,
      `management.endpoint.health.show-components=always`) GETs
      `/actuator/health` and parses the body as JSON. The body has an
      `auditIntegrity` component whose `status` is `UP` when an audit
      directory is configured, and the `Content-Type` is a JSON type. This is
      the actuator endpoint mapper running on Jackson 2. The shipped
      configuration keeps every endpoint off, and task 141 pins that.
- [ ] A behavioural test drives an MVC error on the MCP port (for example a
      request that makes a controller throw, or a 404 for a path no handler
      maps). It asserts that Boot's JSON error body is returned there (it has
      `status` and `path` keys), and that the operator port gives its
      code-only body for the same failure. This closes the task 105 follow-up.
- [ ] A test asserts that setting `spring.web.error.path=/custom-error` moves
      both the MCP-port and the operator-port error handling to
      `/custom-error`.
- [ ] Each test above is shown to fail under a mutation, and the commit body
      records each one: removing `spring-boot-jackson2` from the server pom,
      reverting the mapping to `${server.error.path:...}` with
      `spring.web.error.path` set, and dropping the `AuditIntegrityHealth`
      condition class.
- [ ] `mvn -B -pl data-prism-server -am verify` passes.

## Out of scope

- Any change under `data-prism-server/src/main/**`. If a guard finds a real
  defect, stop and report it; task 141 or a follow-up fixes it.
- Jackson 3 support.
- The `ServerSecurityConfiguration` filter chains. They are covered by
  `ServerSecurityBoundaryTest`, which task 141 keeps passing.
