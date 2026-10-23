# 276 — Auto-configure the MCP security filter chain, the caller-context extractor and Micrometer metrics

**Repo:** `.`
**Release:** 0.9.0 (changes the starter's public contract; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once 273 has merged
**Depends on:** 273 (both edit `DataPrismPropertiesValidator.java`, `PrivacyExtensionPoints.java`, the bean inventories, the metadata file, `docs/configuration.md` and 273's startup report; this task builds on 273's derived `JwtDecoder`), 210 (0.8; the last editor of `SecurityWiring`)
**Wave:** 16 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** OD-276-1 is open (below). "Auto-configured, back-off on user bean" for the chain and the metrics bean, and "`dataprism.metrics.sink` defaults to Micrometer when it is on the classpath", are owner decisions already made in the spec, section 1.
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/McpSecurityWiring.java *(new; name may differ: the `SecurityFilterChain` and the `JwtCallerContextExtractor` beans)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/MetricsWiring.java *(new; name may differ: the Micrometer `PrivacyMetrics` bean)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/MicrometerPrivacyMetrics.java *(new; moved in from `data-prism-integration-tests`, or written to the same contract)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(the `@Import` list, insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/MetricsProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/EffectiveSettingsReport.java *(insertions only: the `dataprism.metrics.sink` line; the file is 273's)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(the `dataprism.metrics` checks, about :620-626, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/pom.xml *(optional `micrometer-core`; the Spring Security dependencies the chain needs, as `optional` or `provided`)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(the `dataprism.metrics.sink` entry only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports *(insertions only, if a separate auto-configuration class is needed for ordering)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt, bean-methods.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/McpSecurityAutoConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/MicrometerMetricsAutoConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java *(existing tests: the property sets or beans that start a context only)*
- docs/configuration.md *(the `dataprism.metrics` rows, and a new "Auto-configured beans" section listing the chain, the extractor and the metrics bean with their back-off conditions)*
- docs/reidentification.md *(the sentence at about :104 that calls `PrivacyMetrics` "the application's" bean)*

## Goal
Today every embedding application writes the same three beans by hand: a `SecurityFilterChain` that requires a bearer JWT, the `JwtCallerContextExtractor`, and a `PrivacyMetrics` over Micrometer (`data-prism-integration-tests/.../http/SecurityConfig.java` and `MicrometerPrivacyMetrics.java`). Auto-configure all three, each backing off when the application supplies its own, and default `dataprism.metrics.sink` to `micrometer` when Micrometer is on the classpath.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 1, "Auto-configured, back-off on user bean".
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/http/SecurityConfig.java: the chain to auto-configure (stateless, CSRF off for a bearer-token API, a health path open, everything else authenticated with the JWT decoder) and the extractor bean. MicrometerPrivacyMetrics.java in the same package: the fail-safe metrics contract (the only tag is `source`; every Micrometer call guarded). Leave both files in place. They now prove the back-off.
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerSecurityConfiguration.java and ServerPrivacyMetrics.java: the server keeps its own beans. The auto-configuration must back off there, and the server's behaviour must not change.
- McpTransportWiring.java:60-70: `MISSING_CALLER_CONTEXT_EXTRACTOR` is refused today when no extractor bean exists. After this task an auto-configured extractor exists whenever the chain's preconditions hold.
- DataPrismContractValidator.java:104: `MISSING_METRICS_BINDING` when no `PrivacyMetrics` bean exists. Reuse it: with `micrometer` as the sink and no `MeterRegistry` bean there is no auto-configured bean, and that code refuses as today. This task adds no refusal code and does not touch `freeze/codes.txt`.
- The default sink depends on the classpath, so it cannot be a field initialiser in `MetricsProperties`. Apply it where the classpath is known, and show it in 273's startup report with the origin "default: Micrometer on the classpath".
- Ordering: the chain must be registered before Spring Boot's default web-security configuration evaluates its "no `SecurityFilterChain` bean" condition, or Spring Boot's default chain would win. Test the outcome, not the annotation.
- `PrivacyExtensionPoints`: the three beans are `REPLACEABLE`, as the spec decides. Rule 2 still holds when an application replaces the chain with a weaker one: the MCP tools refuse a call with no authenticated caller (`GetEntityContextTool.java:89`). Pin that with one test.
- docs/conventions.md: `ApplicationContextRunner` and `WebApplicationContextRunner`, latches, no sleeps.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## OD-276-1 (open, owner): what the auto-configured chain protects
When the application defines no `SecurityFilterChain`, Spring Boot's default chain backs off as soon as ours exists. So the scope of our chain decides how the rest of the host application is protected.
- **(a), recommended:** every request needs a valid bearer JWT, except the health probe (the server's `/health`, and Actuator `health` when Actuator is present). Fail closed for the whole host application. An application that wants anything else defines its own chain, and ours backs off.
- **(b):** only the MCP path (`dataprism.transport.http.path`) is matched. Every other path of the host application is left with no chain, so it is open. This fails open for the host application.
- **(c):** the MCP path needs the JWT, and every other path is denied. This is safe, but it breaks any host endpoint the developer already has.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `McpSecurityAutoConfigurationTest`, with no application security beans, as OD-276-1 decides. Under (a): a request to the MCP path with no token gets 401, with a valid token reaches the transport, and with a token for the wrong audience gets 401; an arbitrary application path with no token gets 401; the health path gets 200 with no token.
- [ ] Back-off: with an application `SecurityFilterChain` bean, ours is absent, and the application's chain alone decides. With an application `McpTransportContextExtractor<HttpServletRequest>`, ours is absent. With an application `PrivacyMetrics`, ours is absent. One test each.
- [ ] With the application's chain replaced by one that permits everything, an MCP tool call with no token is refused by the tool, not answered.
- [ ] `MicrometerMetricsAutoConfigurationTest`:
  - With Micrometer and a `MeterRegistry` bean, and no `dataprism.metrics.sink` set, the sink is `micrometer`, and a `PrivacyMetrics` increment shows in the registry with only the `source` tag.
  - With Micrometer absent (a `FilteredClassLoader`) and no sink set, startup refuses `MISSING_METRICS_SINK` as today.
  - With Micrometer present but no `MeterRegistry` bean, startup refuses `MISSING_METRICS_BINDING`.
  - A registry that throws on registration never fails the reported call.
- [ ] The standalone server's bean set and behaviour are unchanged. `ServerSecurityBoundaryTest`, `ServerPrivacyMetricsGaugeTest` and the operator tests pass unmodified.
- [ ] `data-prism-integration-tests` passes unmodified. Its own `SecurityConfig` and `MicrometerPrivacyMetrics` win over the auto-configuration.
- [ ] `freeze/codes.txt` and `frozen-property-names.txt` are unchanged.
- [ ] `docs/configuration.md` documents the `micrometer` default and its condition, and the new "Auto-configured beans" section names each bean, what it does and how to replace it. `docs/reidentification.md` no longer implies the application must supply `PrivacyMetrics`. `ConfigurationMetadataDocumentedTest` passes.
- [ ] `./mvnw -B -pl data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome lists, for 281's migration notes and CHANGELOG: the three auto-configured beans, the metrics default and OD-276-1's outcome.

## Out of scope
- The JWT settings and their derivation (273).
- Deleting the server's or the integration tests' own security and metrics beans.
- The operator surface's separate chain and port (`data-prism-server` operator package).
- The failure analyser's rendering (279).
- Any metrics sink other than Micrometer.
