# 35 — Make `transport.mode=stdio` refuse startup in the Spring surface

**Repo:** data-prism
**Depends on:** none
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java — insertions only
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/**
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerIntegrationsConfiguration.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java

## Goal
`dataprism.transport.mode=stdio` with `fixture-development=true` passes
`DataPrismProperties.validate()`, but every MCP transport bean in
`DataPrismAutoConfiguration` is gated on `havingValue = "HTTP"` and nothing in the
Spring surface ever calls `DataPrismMcpServer.stdio()`. A starter-based
application so configured therefore starts successfully and serves no MCP
transport at all — fail-open by CLAUDE.md rule 6. Move the refusal into the
shared auto-configuration so every consumer of the starter inherits it, rather
than relying on the standalone server's own `standaloneTransportValidated` bean,
which the starter has no equivalent of.

## Context
- DataPrismAutoConfiguration.java:296-336 — four transport beans, all
  `havingValue = "HTTP", matchIfMissing = true`; nothing constructs a stdio one
- DataPrismProperties.java:96-113 — `validate()` lets `stdio` + `fixture` through
  and skips `protectedDeployment()` for it
- DataPrismContractValidator.java:25-26 — the same pair returns early before the
  adapter/identity/key/audit/metrics checks
- ServerIntegrationsConfiguration.java:21-27 — `STANDALONE_HTTP_ONLY` already
  refuses this on the standalone server, but `docs/plan/PLAN.md` records that it
  "is now asserted by no test anywhere", so its behaviour is unproven; establish
  what it actually does before changing it
- ExampleApplication.java:85 — the hand-built, non-Spring stdio path that
  `examples/agent-config/stdio-fixture/run-fixture-server.sh` and
  `docs/agents/stdio.md` document. It never binds `DataPrismProperties` and must
  keep working; `StdioProductionProfileTest` must stay green untouched
- `docs/plan/HISTORY.md`, grep `Tasks 21, 22, 23 and 24` — the precedent this
  follows: a refusal moved into the shared validator so every consumer inherits it
- AutoConfiguredBeanClassificationTest.java:24-42 — the mechanical sweep over
  `DataPrismAutoConfiguration`'s `@Bean` methods; adding or removing one without
  a matching `PrivacyExtensionPoints` row fails the build, which is why both
  files are owned here

## Acceptance
- [ ] A starter-shaped `ApplicationContextRunner` test with
      `dataprism.transport.mode=stdio` and `fixture-development=true` fails to
      start with a `DataPrismConfigurationException` carrying a single stable
      code, and the message names the transport, not a value.
- [ ] The same refusal holds for `mode=stdio` with `fixture-development` unset or
      false — no configuration combination reaches a started context with
      `mode=stdio`.
- [ ] A non-vacuity proof, in the diff: a test (or a documented mutation recorded
      in the commit body) showing that with the new refusal removed the context
      starts and contains no `McpSyncServer` and no MCP `ServletRegistrationBean`.
      An assertion that only proves "it throws" is not enough — the point is that
      the alternative was a running server with no transport.
- [ ] `ServerStartupTest` gains a case proving the standalone server refuses
      `mode=stdio`, naming the code it actually emits. `STANDALONE_HTTP_ONLY` is
      either asserted by that test or deleted as unreachable — not left untested.
- [ ] Every existing HTTP path still starts: the full autoconfigure, server and
      example suites are green, including `StdioProductionProfileTest` and
      `FixtureDevelopmentRefusalTest`, and a serialized full-reactor
      `mvn -B clean verify` has 0 failures and 0 errors.
- [ ] If the change makes `dataprism.transport.fixture-development=true`
      unreachable in the Spring surface, the commit body says so explicitly and
      names the two consequences the implementer found —
      `ConfiguredJsonSourcesInitializer`'s plaintext-loopback relaxation and
      `DataPrismContractValidator`'s early return — as findings for `/verify`,
      rather than silently changing either.

## Out of scope
- Building a stdio transport in the Spring surface. The decision is refusal, not
  wiring; do not add a `DataPrismMcpServer.stdio()` bean.
- `data-prism-mcp`'s `DataPrismMcpServer.stdio()` itself, and `data-prism-example`
  — the hand-built fixture journey stays exactly as it is.
- `data-prism-connectors-rest`. Its `fixture-development` reads may become dead;
  report that, do not edit it.
- `ServerPackagingIT.java` and `ConfiguredJsonSourcesPackagingIT.java` — task 33
  owns both.
- `docs/configuration.md`'s "Fixture development" row, which this changes the
  meaning of. Scribe hand-off at `/record`.
