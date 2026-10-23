# 273 — Default the low-stakes settings and derive the JWT and writer id from Spring

**Repo:** `.`
**Release:** 0.9.0 (changes the starter's public contract; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once every 0.8 brief has merged
**Depends on:** 260 (creates `freeze/codes.txt`, which this task may edit), 210 (case authorisation stays required; 210 also owns `validatePolicy`), 211, 230, 254 and 255 (the last 0.8 editors of `DataPrismPropertiesValidator.java`, `frozen-property-names.txt`, `additional-spring-configuration-metadata.json`, the bean inventories and `docs/configuration.md`)
**Wave:** 15 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** OD-273-1 and OD-273-2 are open (below). The settings to default and derive, and the ones that stay required, are owner decisions already made in the spec, section 1.
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyProperties.java *(the `profile` and `scope-lifetime` defaults only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/IdentityProperties.java *(the `resolver` default only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java *(the `topology` default only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityProperties.java *(the `Jwt` group only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditProperties.java *(the `writer-id` derivation only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/IdentityResolverSelection.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/Preflights.java *(`dataPrismIdentityResolverPreflight` only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/jwt/JwtDecoderSupport.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/EffectiveSettingsReport.java *(new; name may differ: the startup log block that lists every defaulted or derived value)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(insertions only, if the report is a bean)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(the privacy, identity, security-JWT, operator-audience (:487), audit `writer-id` and hazelcast `topology` checks only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(only as OD-273-2 decides)*
- data-prism-spring-boot-autoconfigure/src/test/resources/configuration-metadata-gaps.txt
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt, bean-methods.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DefaultedSettingsConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/SpringDerivedSettingsTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java *(existing tests: the property sets that start a context only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/JwtDecoderSupportTest.java *(whole file, not only configuration)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerSecurityConfiguration.java *(the JWT view copy at :140-160 only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(configuration arguments only)*
- data-prism-integration-tests/src/main/resources/application.yaml
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/http/SecurityConfig.java *(the Javadoc's property names only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/*.java *(existing files: configuration only)*
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java *(configuration only)*
- data-prism-quickstart-issuer/src/main/java/io/github/aindriub/dataprism/quickstart/issuer/JwksController.java, IssuerProperties.java *(comments and printed property names only)*
- load-test/src/main/java/io/github/aindriub/dataprism/loadtest/ServerProcess.java *(configuration arguments only)*
- docker/server/application.yaml, compose.yaml, docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(the JWT settings only, and only under OD-273-2 (a))*
- server.json *(the JWT environment-variable names only, and only under OD-273-2 (a))*
- examples/agent-config/remote-http/claude-code-add-remote.sh, docs/agents/remote-http.md, docs/reidentification.md, docs/protect-your-own-api.md *(the JWT property names only, and only under OD-273-2 (a))*
- docs/configuration.md *(the rows and group-table cells for the keys this task defaults or derives, every mention of `dataprism.security.jwt.*` (for example :58, :705 and :802) as OD-273-2 decides, and a new "Defaults and derived settings" section)*
- data-prism-architecture/src/test/resources/freeze/codes.txt *(only the codes this task adds or removes)*
- docs/plan/design/contract-register.md *(insertions only: rows for codes this task adds; the `dataprism.security.jwt` property rows as OD-273-2 decides)*

## Goal
A Spring Boot developer should set only what is a security decision. Default the six low-stakes settings, take the JWT settings and the audit writer id from the Spring properties the developer already has, and print every defaulted or derived value at startup so that nothing is silent. The security decisions stay required with no default: the HMAC key reference, the caller-claim mappings, the security policy, the audit sink and case authorisation (210).

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 1: the binding list of what is defaulted, derived and still required.
- Defaults to set (each visible in `spring-configuration-metadata.json` so that `ConfigurationMetadataDocumentedTest` compares it with `docs/configuration.md`):
  - `dataprism.privacy.profile`: `DEFAULT`.
  - `dataprism.privacy.scope-lifetime`: OD-273-1.
  - `dataprism.identity.resolver`: `pass-through` (`IdentityResolverSelection`, `Preflights.java:30-45`).
  - `dataprism.hazelcast.topology`: `single-node`.
  - `dataprism.transport.mode`: already `http` (`TransportProperties.java:7`); list it in the report only.
  - `dataprism.metrics.sink` is **not** this task's; 276 defaults it with the Micrometer bean.
- Derivations:
  - JWT: `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri` and `audiences`. Today the issuer, audience and exactly one of `jwk-set-uri` or `issuer-discovery-uri` come from `dataprism.security.jwt.*` (`DataPrismPropertiesValidator.java:514-530`, `JwtDecoderSupport.java:57`). Mapping: Spring's `issuer-uri` is the trusted issuer; `jwk-set-uri` set means keys are fetched from it with no discovery; `jwk-set-uri` unset means issuer discovery through `issuer-uri`. Every existing check on these values (HTTPS except local fixtures, `INVALID_JWT_LOCATION`, the audience validator) keeps applying to the derived values.
  - Audience (decided at filing, low stakes): Spring's `audiences` is a list; a token must carry at least one listed audience, as Spring does. An empty list refuses with the existing `MISSING_JWT_AUDIENCE`. `OPERATOR_AUDIENCE_SHARED` (`DataPrismPropertiesValidator.java:487`) refuses when the operator audience is any entry of the list.
  - `dataprism.audit.writer-id` defaults to `spring.application.name` when that is set. Both unset still refuses `MISSING_AUDIT_WRITER`. The existing writer-id rules (no `/`, `INVALID_AUDIT_WRITER`) apply to the derived value.
- The JWT decoder must be ours, not Spring Boot's resource-server auto-configuration's, because ours adds the audience and location checks. Show in a test that exactly one `JwtDecoder` bean exists and it rejects a token with the wrong audience.
- `ServerSecurityConfiguration.java:140-160` copies the JWT settings into the operator surface's view; it must read the same derived values.
- "Every default is visible": one INFO block at startup naming each defaulted or derived property, its effective value and its origin (`default`, `derived from spring.application.name`, and so on). It never prints a secret or a key reference's resolved value. The HMAC key reference, audit sink and the other required settings are not listed, because they are never defaulted.
- `docs/configuration.md:150-225` (the property reference table, with its `Default` column) and `:119-148` (the group table). 281 later splits the Required column; this task only corrects the cells for the keys above.
- docs/conventions.md: tests use the `ApplicationContextRunner` shapes already in `DataPrismAutoConfigurationTest`.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## OD-273-1 (open, owner): the default `scope-lifetime`
- **Recommended: `PT8H`.** It is one working session, and it is the value every shipped configuration already uses (`docker/server/application.yaml:32`, `docs/configuration.md:254`, `data-prism-integration-tests/src/main/resources/application.yaml:42`, `docs/protect-your-own-api.md:348`).
- Note: `ScopeResolver` extends a scope on each request (PLAN.md, 263's follow-ups), so this is an idle limit in practice. The docs must say so.
- Alternatives: a shorter `PT1H`, which rotates pseudonyms more often within a working day; or keep it required.

## OD-273-2 (open, owner): what happens to `dataprism.security.jwt.*`
- **(a), recommended: Spring's keys become the only source.** If any `dataprism.security.jwt.*` key is set, startup refuses with a new code, `JWT_SETTINGS_MOVED`, naming the Spring key to use instead. Why: one name for a security setting before the freeze, and 0.9 is the last window for breaking changes. Cost: breaking for every standalone-server operator. The sweep covers the docker and compose files, `server.json` (the MCP registry's environment-variable names), the quickstart, the load test and four doc pages, all listed under Owns with "only under OD-273-2 (a)".
- **(b):** both are accepted. Startup refuses with a new code, `JWT_SETTINGS_CONFLICT`, when both are set and differ. There is no sweep and no break, but two names for one setting are frozen into 1.x.
- **(c):** keep `dataprism.security.jwt.*` as deprecated aliases until 1.0. Not recommended: a deprecation that outlives the freeze is still frozen surface.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `DefaultedSettingsConfigurationTest`: a context with none of the six defaulted keys set, and every still-required key set, starts. The effective values are `DEFAULT`, the OD-273-1 value, `pass-through`, `single-node` and `http`. Setting each key explicitly overrides its default.
- [ ] For each still-required setting (HMAC key reference, caller-claim mappings, security policy, audit sink, case authorisation from 210), removing it alone still refuses with its existing code. One parameterised test, one row each.
- [ ] `SpringDerivedSettingsTest`: with only `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri` and `audiences` set, the context's single `JwtDecoder` accepts a token signed by the fixture key with a listed audience, and rejects one with an unlisted audience and one from another issuer. With `jwk-set-uri` unset it uses issuer discovery. A plain-HTTP non-local `issuer-uri` refuses with the existing location code.
- [ ] `spring.application.name: library-loans` with no `dataprism.audit.writer-id` gives writer id `library-loans`. An explicit `writer-id` wins. Neither set refuses `MISSING_AUDIT_WRITER`. `spring.application.name: a/b` refuses `INVALID_AUDIT_WRITER`.
- [ ] OD-273-2 is implemented as decided. Under (a): any `dataprism.security.jwt.*` key refuses `JWT_SETTINGS_MOVED`, and the message names the Spring key and no configured value; `frozen-property-names.txt` loses the removed names; the register and `freeze/codes.txt` change in the same commit; `git grep -n 'dataprism.security.jwt\|DATAPRISM_SECURITY_JWT'` outside `docs/plan/` and `CHANGELOG.md` returns nothing. Under (b): the conflict refusal is tested both ways and both names are documented.
- [ ] The startup report is logged once, at INFO, and lists every defaulted and derived property with its origin. A test captures it. A test with a sentinel value in the HMAC key environment variable shows that the sentinel never appears in the log.
- [ ] `docs/configuration.md` gives each defaulted key its default in the `Default` column and states each derivation, in a new section "Defaults and derived settings" that lists exactly what the startup report lists. `ConfigurationMetadataDocumentedTest` passes.
- [ ] The standalone server's no-configuration refusal still names a `MISSING_*` code, so `.github/workflows/publish-image.yml:167-190` needs no change. Shown by `ServerStartupTest` or `ConfigurationRefusalMessageIT`.
- [ ] Every existing test passes, with only configuration arguments changed in the owned test files.
- [ ] `./mvnw -B -pl data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome lists, for 281's migration notes and CHANGELOG, each new default, each derivation and OD-273-2's outcome.

## Out of scope
- `dataprism.metrics.sink` and the `PrivacyMetrics` bean, and the `SecurityFilterChain` (276).
- The failure analyser's rendering, the example value and the doc link (279).
- Splitting the Required column of `docs/configuration.md` (281).
- Any default for the HMAC key, caller claims, security policy, audit sink or case authorisation. The spec forbids one.
- A local or development mode. The owner rejected one.
