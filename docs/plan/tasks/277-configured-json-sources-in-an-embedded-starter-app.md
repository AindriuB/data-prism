# 277 — Make configured JSON sources work in an application that embeds the starter

**Repo:** `.`
**Release:** 0.9.0 (changes the starter's published dependencies; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once 273 has merged
**Depends on:** 273 (whose configuration sweep owns the existing `data-prism-integration-tests` files, and whose JWT and default changes this task's new tests must use), 249, 246 and 247 (0.8: the last editors of `ConfiguredJsonSourcesAutoConfiguration` and `ConfiguredJsonSourcesInitializer`), 271 (PR #161: 249 depends on it). If the owner chooses to merge modules or make the starter pom-only (PLAN.md, "merge small modules"), and that brief lands in 0.8, this task depends on it too: both edit `data-prism-spring-boot-starter/pom.xml`.
**Wave:** 16 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** OD-277-1 is open (below). "The configured JSON source mode must work inside the embedded starter as well as in the standalone server" is an owner decision already made in the spec, section 2.
**Owns:**
- data-prism-spring-boot-starter/pom.xml *(as OD-277-1 decides)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesInitializer.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesConfig.java
- data-prism-connectors-rest/src/main/resources/META-INF/spring.factories, META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfigurationTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/embedded/** *(new)*
- data-prism-integration-tests/src/test/resources/embedded/** *(new)*
- data-prism-integration-tests/pom.xml *(the `data-prism-connectors-rest` dependency's scope only, if OD-277-1 makes it redundant)*

## Goal
A developer who embeds the starter should be able to describe an API in a `json-sources:` YAML file and have it served, with the same result as in the standalone server, both alone and alongside Java-first adapters. Prove both cases through the real MCP HTTP transport, fix the wiring where they fail, and decide whether the starter brings the connector with it.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 2, first bullet; section 5 (no new published artifacts).
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java:50-90 (class Javadoc): adapters are registered by `ConfiguredJsonSourcesInitializer`, an `ApplicationContextInitializer`, so that `@ConditionalOnBean(DataSourceAdapter.class)` sees them. This auto-configuration replaces the base `ContextOrchestrator` through `@AutoConfigureBefore` and `@ConditionalOnMissingBean`. Check that both hold in an embedded application with its own `@SpringBootApplication`, its own beans and a Java adapter.
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredJsonNestedHttpTest.java: the existing proof over the real transport (system-property `config-location`, in-process JWT key, an explicit `SSLContext` for the MCP client). Copy its harness shape. Do not edit it; 273 and 275 own it.
- docs/configuration.md:709-722 says the JSON mode is "never part of the base standalone server distribution" and is opted into with `-Dloader.path`. That stays true for the server under every option below. 281 rewrites the section for the starter case from this task's Outcome.
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfiguredJsonSourcesPackagingIT.java: the server's `-Dloader.path` opt-in, which must keep working unchanged.
- Fixture values are invented. Repository rule 3.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## OD-277-1 (open, owner): does the starter bring `data-prism-connectors-rest`?
- **(a), recommended:** `data-prism-spring-boot-starter` depends on `data-prism-connectors-rest`. The connector stays inert unless `dataprism.json-sources.config-location` is set, as it is today. One dependency gives both the YAML route and 278's `@DataPrismSource`, which also lives in the connector. No new artifact is published. The standalone server keeps its `-Dloader.path` opt-in.
  - Cost: every starter user gets the connector jar on the classpath, and its auto-configuration is evaluated.
  - This is compatible with every module-merge option at PLAN.md:62, including a pom-only starter.
- **(b):** the developer adds `data-prism-connectors-rest` explicitly. The starter stays as it is, the getting-started guide carries a second dependency, and 278's annotation needs the same second dependency.

## Acceptance
- [ ] Tests are written first and fail on the base commit where the wiring is broken; the Outcome names them, and says which cases already passed before any change.
- [ ] YAML only: an application under `example/embedded/` with the starter (and, under OD-277-1 (b), the connector), one `json-sources:` source, `dataprism.json-sources.config-location` set and no Java adapter starts. It answers `get_entity_context` over the real MCP HTTP transport with the configured source's scrubbed values. No raw fixture value appears in the response or the captured log.
- [ ] Mixed: the same application plus one Java-first adapter answers from both sources, with per-source statuses, and neither source's result is merged into the other's.
- [ ] Inert: with the connector on the classpath and `config-location` unset, no bean of a `connectors.rest` type exists apart from auto-configuration classes, and the context's other bean names equal those of the same application without the connector. Compared in a test.
- [ ] Under OD-277-1 (a): the starter's published pom gains exactly one dependency, `data-prism-connectors-rest`, and `./mvnw -B -Prelease -DskipTests -Dgpg.skip package` stages no new artifact. The Outcome shows the staged list before and after.
- [ ] The standalone server's `ConfiguredJsonSourcesPackagingIT` and `ConfiguredJsonReidentificationEndToEndTest` pass unmodified.
- [ ] Every existing test in `data-prism-connectors-rest` passes. `ConfiguredJsonSourcesAutoConfigurationTest` changes only by added cases.
- [ ] `./mvnw -B -pl data-prism-connectors-rest,data-prism-spring-boot-starter,data-prism-integration-tests,data-prism-server -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome lists, for 281: the embedded-mode text for `docs/configuration.md:709-722`, and the CHANGELOG entry for the starter's new dependency if OD-277-1 is (a).

## Out of scope
- The `json-sources:` grammar (275) and `@DataPrismSource` (278).
- `docs/configuration.md`, the getting-started guide and the examples (281, 282, 280).
- The server's packaging and `-Dloader.path` opt-in.
- Merging modules or making the starter pom-only. That is the separate pending owner decision at PLAN.md:62.
