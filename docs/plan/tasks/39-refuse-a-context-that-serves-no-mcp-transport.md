# 39 — Refuse a starter context that would serve no MCP transport

**Repo:** data-prism
**Depends on:** 35
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java — insertions only
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/**
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerIntegrationsConfiguration.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java

## Goal
Every MCP transport bean in `DataPrismAutoConfiguration` is gated on
`@ConditionalOnWebApplication(SERVLET)`. A non-servlet Spring application using
the starter at the **default** `dataprism.transport.mode=HTTP` therefore starts
cleanly with no `McpSyncServer`, no MCP servlet and no refusal — a server that
runs and serves nothing. This is the same fail-open shape task 35 closed for
stdio, but it sits on the default configuration path, so no operator has to
misconfigure anything to reach it. Make that context refuse startup, and settle
the refusal-code sprawl that task 35's reviewer flagged while the file is open.

## Context
- DataPrismAutoConfigurationTest.java:30 (branch `task/35-stdio-refuses-instead-of-serving-nothing`)
  — `boots_a_minimal_reviewed_context` is a plain `ApplicationContextRunner` at
  default `mode=HTTP` that asserts `hasNotFailed()`. That test *is* the defect:
  it demonstrates a started, transport-less context today.
- DataPrismAutoConfiguration.java:320,333,346,356 — the four transport beans, all
  `@ConditionalOnWebApplication(SERVLET)` and all `havingValue = "HTTP",
  matchIfMissing = true`. Nothing refuses when those conditions all evaluate false.
- DataPrismAutoConfiguration.java:308-313 — task 35's `dataPrismStdioTransportRefused`,
  an unconditional `@Bean`. The precedent, but not necessarily the right shape:
  reviewer 35 argued for the static `BeanFactoryPostProcessor` at
  DataPrismAutoConfiguration.java:85-93 (`dataPrismIdentityResolverPreflight`),
  which refuses before any singleton is built and so does not depend on bean
  declaration order. A post-processor also has the option of asking the question
  directly — "is any MCP transport bean *definition* present after conditions are
  evaluated?" — rather than proxying it through servlet-ness, which would also
  cover a WebFlux application and a servlet API missing from the classpath.
- Three codes now name one conceptual refusal: `STDIO_DEVELOPMENT_ONLY`
  (DataPrismProperties.java:99, and separately in `data-prism-mcp`),
  `STDIO_TRANSPORT_UNSUPPORTED` (DataPrismAutoConfiguration.java:311),
  `STANDALONE_HTTP_ONLY` (ServerIntegrationsConfiguration.java:24). A consumer
  keying on the code to detect "this deployment refused because it has no usable
  transport" must today match all three, and this task would make it four.
- PrivacyExtensionPoints.java:43 and AutoConfiguredBeanClassificationTest — the
  mechanical sweep over `DataPrismAutoConfiguration`'s `@Bean` methods, including
  the existing `dataPrismIdentityResolverPreflight` row. Adding or renaming a bean
  method without a matching row fails the build, which is why both files are owned.
- ServerStartupTest.java:121-134 — every `start(...)` helper hardcodes
  `.web(WebApplicationType.SERVLET)`, so no test covers the packaged server being
  launched with `spring.main.web-application-type=none`.
- All five `ApplicationContextRunner` users in this module
  (`DataPrismAutoConfigurationTest`, `ModelDescriptorsConfigurationTest`,
  `PrivacyExtensionPointsTest`, `SharedReadBudgetTest`, and the runners inside
  `FixtureDevelopmentRefusalTest`) build non-web contexts and will start failing.
  They are all inside the owned test tree. `ConfiguredJsonSourcesAutoConfigurationTest`
  in `data-prism-connectors-rest` does **not** load `DataPrismAutoConfiguration`
  and must stay untouched — verify that before assuming it.

## Acceptance
- [ ] A non-web `ApplicationContextRunner` loading `DataPrismAutoConfiguration`
      with an otherwise valid configuration and **no** `dataprism.transport.mode`
      set fails to start with a `DataPrismConfigurationException` carrying a
      single stable code whose message names the missing transport, not the
      property value.
- [ ] Non-vacuity, proven in the diff: a test (or a mutation reproduced and
      recorded verbatim in the commit body) showing that with the new refusal
      removed, that same context starts, `hasNotFailed()`, and contains zero
      `McpSyncServer` beans and zero MCP `ServletRegistrationBean`s. "It throws"
      alone does not satisfy this; the point being proven is what the alternative
      was.
- [ ] The refusal happens before any DataPrism singleton is constructed, proven
      by a test: a collaborator bean in the test configuration that records its
      own construction is never constructed on the refusing path. If the
      implementer concludes a `BeanFactoryPostProcessor` cannot answer the
      question, the commit body says why and names the ordering guarantee the
      chosen shape relies on instead.
- [ ] The refusal-code decision is explicit: either exactly one code now covers
      "this deployment has no usable MCP transport" across `DataPrismProperties`,
      `DataPrismAutoConfiguration` and `ServerIntegrationsConfiguration`, or a
      fourth code is added and a Javadoc comment on the new refusal states which
      code a consumer should key on for which condition and why they are not one
      code. Whichever is chosen, the commit body names it and lists the tests
      whose asserted code strings changed.
- [ ] `ServerStartupTest` gains a case starting `data-prism-server` with
      `WebApplicationType.NONE` (or `spring.main.web-application-type=none`) and
      asserting the code it actually emits.
- [ ] Every legitimate path still starts: `DataPrismAutoConfigurationTest`'s HTTP
      cases, `FixtureDevelopmentRefusalTest`, `StdioProductionProfileTest`, the
      example and quickstart suites, and a serialized full-reactor
      `mvn -B clean verify` with 0 failures and 0 errors.
- [ ] Any test migrated from `ApplicationContextRunner` to
      `WebApplicationContextRunner` keeps every assertion it had; the commit body
      lists each migrated test and confirms none were deleted or weakened.

## Out of scope
- Adding a non-servlet or stdio MCP transport to the Spring surface. The decision
  is refusal, not wiring.
- `data-prism-mcp`'s own `STDIO_DEVELOPMENT_ONLY`
  (`SecurityRefusedException`, a different layer and exception type) and
  `data-prism-example`. If code consolidation would ideally reach them, report it
  for `/verify`; do not edit either module.
- `data-prism-connectors-rest`, including its `ApplicationContextRunner` test.
- `docs/configuration.md` and any doc naming a refusal code — scribe hand-off at
  `/record`.
- Poms, `docker/distribution/**`, `server.json`, `README.md` and the publish
  workflows — tasks 36, 37 and 38 own those.
