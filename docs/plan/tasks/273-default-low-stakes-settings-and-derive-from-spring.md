# 273 — Default the low-stakes settings and derive the JWT and writer id from Spring

**Repo:** `.`
**Release:** 0.9.0 (changes the starter's public contract; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once every 0.8 brief has merged
**Depends on:** 260 (creates `freeze/codes.txt`, which this task may edit), 210 (case authorisation stays required; 210 also owns `validatePolicy`), 211, 254, 255, 284 and 286 (PR #164; 284 and 286 replace the deferred 230; these are the last 0.8 editors of `DataPrismPropertiesValidator.java`, `frozen-property-names.txt`, `additional-spring-configuration-metadata.json`, the bean inventories and `docs/configuration.md`)
**Wave:** 15 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** OD-273-1 and OD-273-2 are open (below). OD-273-3 was decided (a) by the owner on 2026-10-24 (below). The settings to default and derive, and the ones that stay required, are owner decisions already made in the spec, section 1.
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
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(the privacy, identity, security-JWT, operator-audience (:487), audit `writer-id` and hazelcast `topology` checks, including the `CLUSTER_SETTINGS_IGNORED` checks at about :229-233 and :655-662 and the re-identification topology check at about :463, only)*
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
  - `dataprism.identity.resolver`: `pass-through`, but only when exactly one source is configured (OD-273-3, decided (a) 2026-10-24). `IdentityResolverSelection.java` (its `@ConditionalOnProperty(prefix = "dataprism.identity", name = "resolver", havingValue = "pass-through")`) and `Preflights.java:25-45` (`dataPrismIdentityResolverPreflight`).
  - `dataprism.hazelcast.topology`: `single-node`. Coordinator decision of 2026-10-24, after the second external review of PR #162: the default removes `MISSING_CLUSTER_TOPOLOGY` (`DataPrismPropertiesValidator.java:627`), so a deployment that meant to cluster must not silently run alone.
    - Any `dataprism.hazelcast` member, join, cluster-name, security (cluster key, name key, rotation), trusted-network (284's `trusted-network` and `member.trusted-ranges`), approval-quorum, lock-timeout or approval-sweep-interval setting without an explicit `topology: embedded` refuses with the existing `CLUSTER_SETTINGS_IGNORED`. That covers both the defaulted and the explicit `single-node` topology. The message names the first such property set and says to set `dataprism.hazelcast.topology: embedded` or remove it. Today's checks (about :229-233 and :655-662) already refuse explicit `single-node` with these settings; extend them to every setting in that list, and to the defaulted case.
    - `dataprism.reidentification.enabled: true` without an explicit `embedded` topology still refuses as today (about :463).
    - `MISSING_CLUSTER_TOPOLOGY` is retired. It is removed from the validator, the contract register and `freeze/codes.txt`, and the Outcome gives 281 a migration line and a CHANGELOG entry (Removed: the code; Changed: the default).
    - The effective topology is always in the startup report, once at INFO, whether it was defaulted or set. The defaulted line says that each replica runs alone, so budgets and limits are per replica (third external review of PR #162).
    - When the topology is defaulted (not set) to `single-node` and the environment has `KUBERNETES_SERVICE_HOST` set, startup logs one WARN. It says that replicas will not share state and names `dataprism.hazelcast.topology: embedded`. Startup is not refused: the owner chose single-node as the default. An explicit `single-node` gives no WARN.
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

## OD-273-3: decided (a) by the owner, 2026-10-24: when the identity resolver defaults to pass-through
A pass-through resolver uses the requested subject id as every source's key. With one source that is harmless. With two or more, a default would silently join records from sources that key the same person differently, which today's `MISSING_IDENTITY_RESOLVER` refusal stops. Found in the external review of PR #162.
- **Decided (a):** default to pass-through only when exactly one source is configured. With two or more sources and no resolver configured, startup refuses as today. This was the coordinator's recommendation.
- **Required shape:**
  - `IdentityResolverSelection`'s bean condition gains `matchIfMissing = true`, so the pass-through bean is defined when the property is unset. `@ConditionalOnMissingBean(IdentityResolver.class)` stays, so an application resolver always wins.
  - The count cannot be a bean condition, because configured JSON adapters (`ConfiguredJsonSourcesInitializer`) and 278's generated adapters are registered by other means. `dataPrismIdentityResolverPreflight` counts the sources with `factory.getBeanNamesForType(DataSourceAdapter.class, true, false)`. That call returns bean definitions and manually registered singletons alike, which matters because `ConfiguredJsonSourcesInitializer` registers configured JSON adapters with `registerSingleton` (`ConfiguredJsonSourcesInitializer.java:59-62`), not as definitions. The check runs where it already runs: in the static `BeanFactoryPostProcessor`'s `postProcessBeanFactory`. By then the initializer's singletons are registered (an `ApplicationContextInitializer` runs before refresh), and so are the definitions from `ConfigurationClassPostProcessor`, a `BeanDefinitionRegistryPostProcessor` that runs before every plain `BeanFactoryPostProcessor`, including 278's generated adapters. It initialises no bean (`allowEagerInit` false). A hand-written adapter is counted by its `@Bean` method's declared return type. The Javadoc says so, and a test pins an adapter declared as its concrete type. When `dataprism.identity.resolver` is unset, the only `IdentityResolver` is the defaulted pass-through bean, and the count is two or more, it refuses `MISSING_IDENTITY_RESOLVER`. The message names `dataprism.identity.resolver` and says that two or more sources need a resolver or an explicit `pass-through`.
  - An explicit `dataprism.identity.resolver: pass-through` is still accepted with any number of sources, because the operator has stated it.
  - The startup report lists the defaulted resolver with the origin "default: one source configured".
- **Rejected:** (b) default to pass-through regardless of the source count, as the spec's list reads; this removes a refusal that prevents silent joins. (c) no default; the resolver stays required.

## OD-273-2 (open, owner): what happens to `dataprism.security.jwt.*`
- **(a), recommended: Spring's keys become the only source.** If any `dataprism.security.jwt.*` key is set, startup refuses with a new code, `JWT_SETTINGS_MOVED`, naming the Spring key to use instead. Why: one name for a security setting before the freeze, and 0.9 is the last window for breaking changes. Cost: breaking for every standalone-server operator. The sweep covers the docker and compose files, `server.json` (the MCP registry's environment-variable names), the quickstart, the load test and four doc pages, all listed under Owns with "only under OD-273-2 (a)".
- **(b):** both are accepted. Startup refuses with a new code, `JWT_SETTINGS_CONFLICT`, when both are set and differ. There is no sweep and no break, but two names for one setting are frozen into 1.x.
- **(c):** keep `dataprism.security.jwt.*` as deprecated aliases until 1.0. Not recommended: a deprecation that outlives the freeze is still frozen surface.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `DefaultedSettingsConfigurationTest`: a context with one source, none of the six defaulted keys set, and every still-required key set, starts. The effective values are `DEFAULT`, the OD-273-1 value, `pass-through`, `single-node` and `http`. Setting each key explicitly overrides its default.
- [ ] For each still-required setting, removing it alone still refuses with its existing code. One parameterised test, one row each:
  - the HMAC key reference;
  - the caller-claim mappings;
  - the security policy;
  - the audit sink;
  - case authorisation (210);
  - the JWT issuer (Spring's `issuer-uri`, `MISSING_JWT_ISSUER`);
  - the JWT audience (Spring's `audiences`, `MISSING_JWT_AUDIENCE`);
  - the writer id, with both `dataprism.audit.writer-id` and `spring.application.name` unset (`MISSING_AUDIT_WRITER`).
  281 maps its mandatory rows to these.
- [ ] OD-273-3 (a):
  - One configured source with no resolver set starts, using pass-through.
  - Two sources with no resolver set refuse `MISSING_IDENTITY_RESOLVER`. One row each: two Java adapters; two configured JSON sources (registered as singletons by `ConfiguredJsonSourcesInitializer`, with no Java adapter); one Java adapter and one configured JSON source. Each row must refuse.
  - One configured JSON source alone, with no Java adapter, starts with pass-through.
  - Two sources with explicit `pass-through` start.
  - An application `IdentityResolver` bean with two sources starts and is the only resolver.
  - Mutants killed: removing `matchIfMissing`; counting `>= 1` instead of `>= 2`; counting with `getBeanDefinitionNames` or `includeNonSingletons` false, which the two-JSON-sources row catches.
- [ ] Topology (coordinator decision 2026-10-24):
  - With no `dataprism.hazelcast` setting, the context starts single-node, and the startup report shows `dataprism.hazelcast.topology=single-node (default)` once.
  - One row per setting family, each with the topology unset and again with an explicit `single-node`, refuses `CLUSTER_SETTINGS_IGNORED` naming that property: `join.mode`, `join.members`, `member.interface`, `cluster-name`, the cluster key, the name key, `trusted-network` (284), `approval-minimum-members`, `lock-timeout` and `approval-sweep-interval`.
  - `dataprism.reidentification.enabled: true` with the topology unset refuses as today.
  - Each of those settings with an explicit `embedded` topology passes this check.
  - The WARN: logged once with the topology unset and `KUBERNETES_SERVICE_HOST=10.0.0.1` in the environment (set through the context runner's properties); absent with an explicit `single-node`; absent with no such variable. A captured-log test pins all three.
  - `git grep -n MISSING_CLUSTER_TOPOLOGY` outside `docs/plan/`, `CHANGELOG.md` and `docs/migration-*.md` returns nothing.
- [ ] `SpringDerivedSettingsTest`: with only `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri` and `audiences` set, the context's single `JwtDecoder` accepts a token signed by the fixture key with a listed audience, and rejects one with an unlisted audience and one from another issuer. With `jwk-set-uri` unset it uses issuer discovery. A plain-HTTP non-local `issuer-uri` refuses with the existing location code.
- [ ] `spring.application.name: library-loans` with no `dataprism.audit.writer-id` gives writer id `library-loans`. An explicit `writer-id` wins. Neither set refuses `MISSING_AUDIT_WRITER`. `spring.application.name: a/b` refuses `INVALID_AUDIT_WRITER`.
- [ ] OD-273-2 is implemented as decided. Under (a): any `dataprism.security.jwt.*` key refuses `JWT_SETTINGS_MOVED`, and the message names the Spring key and no configured value; `frozen-property-names.txt` loses the removed names; the register and `freeze/codes.txt` change in the same commit; `git grep -n 'dataprism.security.jwt\|DATAPRISM_SECURITY_JWT'` outside `docs/plan/` and `CHANGELOG.md` returns nothing. Under (b): the conflict refusal is tested both ways and both names are documented.
- [ ] The startup report is logged once, at INFO, and lists every defaulted and derived property with its origin. A test captures it. A test with a sentinel value in the HMAC key environment variable shows that the sentinel never appears in the log.
- [ ] `docs/configuration.md` gives each defaulted key its default in the `Default` column and states each derivation, in a new section "Defaults and derived settings" that lists exactly what the startup report lists. `ConfigurationMetadataDocumentedTest` passes.
- [ ] The standalone server's no-configuration refusal still names a `MISSING_*` code, so `.github/workflows/publish-image.yml:167-190` needs no change. Shown by `ServerStartupTest` or `ConfigurationRefusalMessageIT`.
- [ ] Every existing test passes, with only configuration arguments changed in the owned test files.
- [ ] `./mvnw -B -pl data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome lists, for 281's migration notes and CHANGELOG, each new default, each derivation, OD-273-2's outcome, the retired `MISSING_CLUSTER_TOPOLOGY` code, and the new topology rule.

## Out of scope
- `dataprism.metrics.sink` and the `PrivacyMetrics` bean, and the `SecurityFilterChain` (276).
- The failure analyser's rendering, the example value and the doc link (279).
- Splitting the Required column of `docs/configuration.md` (281).
- Any default for the HMAC key, caller claims, security policy, audit sink or case authorisation. The spec forbids one.
- A local or development mode. The owner rejected one.
