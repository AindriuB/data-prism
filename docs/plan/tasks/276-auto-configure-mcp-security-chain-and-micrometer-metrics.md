# 276 — Auto-configure the MCP security filter chain, the caller-context extractor and Micrometer metrics

**Repo:** `.`
**Release:** 0.9.0 (changes the starter's public contract; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once 273 and 275 have merged
**Depends on:** 275 (both edit `freeze/codes.txt` and the contract register, since this task adds `MISSING_MCP_SECURITY`; added 2026-10-24), 273 (both edit `DataPrismPropertiesValidator.java`, `PrivacyExtensionPoints.java`, the bean inventories, the metadata file, `docs/configuration.md` and 273's startup report; this task builds on 273's derived `JwtDecoder`), 210 (0.8; the last editor of `SecurityWiring`)
**Wave:** 17 (moved from 16 on 2026-10-24, after the third external review of PR #162 gave it a refusal code; 0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
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
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPointsTest.java *(insertions only: the MCP chain's guard case)*
- data-prism-spring-boot-autoconfigure/pom.xml *(optional `micrometer-core`; the Spring Security dependencies the chain needs, as `optional` or `provided`)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(the `dataprism.metrics.sink` entry only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports *(insertions only, if a separate auto-configuration class is needed for ordering)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt, bean-methods.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/McpSecurityAutoConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/MicrometerMetricsAutoConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java *(existing tests: the property sets or beans that start a context only)*
- docs/configuration.md *(the `dataprism.metrics` rows, and a new "Auto-configured beans" section listing the chain, the extractor and the metrics bean with their back-off conditions)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/McpChainPrecedenceTest.java *(new)*
- data-prism-architecture/src/test/resources/freeze/codes.txt *(the `MISSING_MCP_SECURITY` insertion only)*
- docs/plan/design/contract-register.md *(insertions only: the `MISSING_MCP_SECURITY` row)*
- docs/reidentification.md *(the sentence at about :104 that calls `PrivacyMetrics` "the application's" bean)*

## Goal
Today every embedding application writes the same three beans by hand: a `SecurityFilterChain` that requires a bearer JWT, the `JwtCallerContextExtractor`, and a `PrivacyMetrics` over Micrometer (`data-prism-integration-tests/.../http/SecurityConfig.java` and `MicrometerPrivacyMetrics.java`). Auto-configure all three, each backing off when the application supplies its own, and default `dataprism.metrics.sink` to `micrometer` when Micrometer is on the classpath.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 1, "Auto-configured, back-off on user bean".
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/http/SecurityConfig.java: the chain to auto-configure (stateless, CSRF off for a bearer-token API, a health path open, everything else authenticated with the JWT decoder) and the extractor bean. MicrometerPrivacyMetrics.java in the same package: the fail-safe metrics contract (the only tag is `source`; every Micrometer call guarded). Leave both files in place. They now prove the back-off.
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerSecurityConfiguration.java and ServerPrivacyMetrics.java: the server keeps its own beans. The auto-configuration must back off there, and the server's behaviour must not change.
- McpTransportWiring.java:60-70: `MISSING_CALLER_CONTEXT_EXTRACTOR` is refused today when no extractor bean exists. After this task an auto-configured extractor exists whenever the chain's preconditions hold.
- DataPrismContractValidator.java:104: `MISSING_METRICS_BINDING` when no `PrivacyMetrics` bean exists. Reuse it: with `micrometer` as the sink and no `MeterRegistry` bean there is no auto-configured bean, and that code refuses as today. The metrics part adds no refusal code. This task's only new code is the security chain's `MISSING_MCP_SECURITY`, below.
- The default sink depends on the classpath, so it cannot be a field initialiser in `MetricsProperties`. Apply it where the classpath is known, and show it in 273's startup report with the origin "default: Micrometer on the classpath".
- Ordering: the chain must be registered before Spring Boot's default web-security configuration evaluates its "no `SecurityFilterChain` bean" condition, or Spring Boot's default chain would win. Test the outcome, not the annotation.
- **Two chains (second external review of PR #162).** A single chain that backs off on any application `SecurityFilterChain` would also drop MCP protection when the application only adds, say, an `/admin/**` chain. So there are two chains.
  - **The MCP chain** (corrected after the third external review of PR #162).
    - **Matcher.** Its `securityMatcher` is exactly the path the transport serves, `dataprism.transport.http.path`, matched as the server's `exactPath(mcpPath, null)` matches it (`ServerSecurityConfiguration.java:41-48`), and nothing else. It does not match sub-paths. `/mcp/x` therefore falls to whatever chain the application has; on the server, that is a chain whose `anyRequest().denyAll()` refuses it.
    - **Rules.** Inside the chain: the exact path, authenticated with the JWT decoder; then `anyRequest().denyAll()` for anything else the matcher lets through (other dispatcher types, for example). The `ERROR` dispatch is permitted, as the server does.
    - **Order.** A named constant of `10`, documented in Javadoc and docs. Why 10: the server's operator chain is `@Order(1)` and matches by the operator port (`:62-69`), so it must still run first. A request to the operator port must never reach the MCP chain. Application chains with no `@Order` sit at the lowest precedence, so they still come after the MCP chain. The matcher also excludes a request whose local port is `dataprism.operator.port` when the operator surface is enabled, so the order is not the only guard.
    - It has no `@ConditionalOnMissingBean` and never backs off.
  - **Preconditions** (each listed in the Javadoc and in `docs/configuration.md`'s "Auto-configured beans" section). The MCP chain and the extractor register when all of these hold:
    1. `dataprism.transport.mode` is `http` (the default);
    2. the application is a servlet web application;
    3. Spring Security's web configuration (`org.springframework.security.config.annotation.web.builders.HttpSecurity`) and the OAuth2 resource server's JWT support (`org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider`) are on the classpath. The starter brings both through `spring-boot-starter-oauth2-resource-server`.
  - **Refusal when they do not.** If conditions 1 and 2 hold but the MCP chain bean is absent, startup refuses with a new code, `MISSING_MCP_SECURITY`. Causes include condition 3 failing, or the auto-configuration being excluded. The message names the missing class or says the chain was not registered, and never a configured value. The check is a preflight that tests the classes with `ClassUtils.isPresent` and the bean by name with `containsBeanDefinition`, so it works when Spring Security is absent. Today such an application fails later and less clearly, or depends on the tool-level refusal only.
  - **The catch-all chain.** It is OD-276-1's scope for every other path, and it backs off when the application defines any `SecurityFilterChain`.
  - **Classification.** The MCP chain is `PRIVACY_CRITICAL` with `Guard.COMPETING_BEAN_REFUSAL`, in its list-collected sense: `FilterChainProxy` collects chains into a list, so an application chain can be added but cannot remove ours. `PrivacyExtensionPointsTest` proves it.
  - **Residual risk.** An application chain registered at an even higher precedence and matching the MCP path would still win. Javadoc and the docs say so, and the tool-level refusal below is the backstop.
  - **The server.** `ServerSecurityConfiguration` keeps its own chains. The MCP chain then also exists on the server at order 10, after the operator chain and ahead of the server's MCP chain. Because it matches only the exact MCP path, with the same authentication the server's chain applies there, the server's behaviour is unchanged. The server's tests must pass unmodified, and `McpChainPrecedenceTest` proves the cases below. If they cannot pass, stop and report rather than adding an opt-out.
- `PrivacyExtensionPoints`: the catch-all chain, the extractor and the metrics bean are `REPLACEABLE`, as the spec decides. Rule 2 still holds when an application replaces the chain with a weaker one: the MCP tools refuse a call with no authenticated caller (`GetEntityContextTool.java:89`). Pin that with one test.
- docs/conventions.md: `ApplicationContextRunner` and `WebApplicationContextRunner`, latches, no sleeps.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## OD-276-1 (open, owner): what the auto-configured chain protects
When the application defines no `SecurityFilterChain`, Spring Boot's default chain backs off as soon as ours exists. So the scope of our chain decides how the rest of the host application is protected.
- **(a), recommended:** every request needs a valid bearer JWT, except the health probe. That is the server's `/health` and, when Actuator is present, only the Actuator health status (`/actuator/health`) and its `liveness` and `readiness` groups (`/actuator/health/liveness`, `/actuator/health/readiness`). Any other Actuator endpoint and any other health group or component path, such as `/actuator/health/db`, needs the JWT. Fail closed for the whole host application. An application that wants anything else defines its own chain, and ours backs off.
- **(b):** only the MCP path (`dataprism.transport.http.path`) is matched. Every other path of the host application is left with no chain, so it is open. This fails open for the host application.
- **(c):** the MCP path needs the JWT, and every other path is denied. This is safe, but it breaks any host endpoint the developer already has.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `McpSecurityAutoConfigurationTest`, with no application security beans, as OD-276-1 decides. Under (a): a request to the MCP path with no token gets 401, with a valid token reaches the transport, and with a token for the wrong audience gets 401; an arbitrary application path with no token gets 401; the health status and the `liveness` and `readiness` groups get 200 with no token; `/actuator/health/<component>`, another health group and every other Actuator endpoint get 401 with no token. Each path is a test row.
- [ ] Back-off: with an application `SecurityFilterChain` bean, our catch-all chain is absent and our MCP chain is present.
- [ ] With an application chain matched only to `/admin/**`, three things hold. `/admin/x` is decided by the application's chain alone. The MCP path with no token still gets 401 from our MCP chain. A token for the wrong audience still gets 401. Mutant killed: putting `@ConditionalOnMissingBean` on the MCP chain.
- [ ] With an application catch-all chain that permits everything, at the default order, the MCP path with no token still gets 401. With an application `McpTransportContextExtractor<HttpServletRequest>`, ours is absent. With an application `PrivacyMetrics`, ours is absent. One test each.
- [ ] Tool-level backstop (rule 2), fixed after the external review of PR #162. The test must actually reach the tool; otherwise our MCP chain's 401 makes it pass either way.
  - The test adds an application `SecurityFilterChain` matched to the MCP path, at a precedence above our MCP chain's (for example `@Order(5)`, below 10), that permits every request. This bypasses our chain for that path, exactly as a misconfigured application would.
  - The test first asserts that the bypass works: the request reaches the MCP transport, with no 401 or 403 from any chain.
  - An MCP `tools/call` of `get_entity_context` with no token is then refused by the tool. The result is a refusal for an unauthenticated caller (`GetEntityContextTool.java:89`, `UNAUTHENTICATED_PRINCIPAL`), no source is called (a counting adapter stays at zero), and no source value is in the response.
  - Mutant, recorded in the Outcome as a manual or PIT run: removing the tool's unauthenticated-caller refusal makes this test fail. Excluding `McpSecurityWiring` instead of adding the bypass chain is an acceptable alternative setup, if the Outcome says why.
- [ ] `MicrometerMetricsAutoConfigurationTest`:
  - With Micrometer and a `MeterRegistry` bean, and no `dataprism.metrics.sink` set, the sink is `micrometer`, and a `PrivacyMetrics` increment shows in the registry with only the `source` tag.
  - With Micrometer absent (a `FilteredClassLoader`) and no sink set, startup refuses `MISSING_METRICS_SINK` as today.
  - With Micrometer present but no `MeterRegistry` bean, startup refuses `MISSING_METRICS_BINDING`.
  - A registry that throws on registration never fails the reported call.
- [ ] `McpChainPrecedenceTest` (server):
  - `/mcp/x` and `/mcp/` are refused, both with no token (401 or 403) and with a valid MCP token (403). Both are refused on the base commit too, which pins that this task does not open them.
  - The exact `/mcp` with a valid token reaches the transport.
  - With the operator surface enabled, a request to the operator port at path `/mcp` is decided by the operator chain, not ours: an MCP-audience token gets 401.
  - Mutants killed: matching `/mcp/**`; dropping the final `denyAll`; an order ahead of the operator chain.
- [ ] Preconditions: with Spring Security's web classes hidden by a `FilteredClassLoader`, and again with the auto-configuration excluded, a servlet application with the HTTP transport refuses `MISSING_MCP_SECURITY`. A non-web application, or `transport.mode` set to anything else that is accepted, does not raise it. The code is in the register and `freeze/codes.txt`.
- [ ] The standalone server's bean set and behaviour are unchanged, apart from the added MCP chain bean, which the bean inventory lists. `ServerSecurityBoundaryTest`, `ServerPrivacyMetricsGaugeTest` and the operator tests pass unmodified.
- [ ] `data-prism-integration-tests` passes unmodified. Its own `SecurityConfig` and `MicrometerPrivacyMetrics` win over the auto-configuration.
- [ ] `freeze/codes.txt` gains only `MISSING_MCP_SECURITY`, and `frozen-property-names.txt` is unchanged.
- [ ] `docs/configuration.md` documents the `micrometer` default and its condition, and the new "Auto-configured beans" section names each bean, what it does and how to replace it. `docs/reidentification.md` no longer implies the application must supply `PrivacyMetrics`. `ConfigurationMetadataDocumentedTest` passes.
- [ ] `./mvnw -B -pl data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome lists, for 281's migration notes and CHANGELOG: the three auto-configured beans, the metrics default and OD-276-1's outcome.

## Out of scope
- The JWT settings and their derivation (273).
- Deleting the server's or the integration tests' own security and metrics beans.
- The operator surface's separate chain and port (`data-prism-server` operator package).
- The failure analyser's rendering (279).
- Any metrics sink other than Micrometer.
