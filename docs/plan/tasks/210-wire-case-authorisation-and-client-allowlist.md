# 210 — Wire case authorisation and the client allowlist into configuration

Renumbered from 195 on 2026-10-19 (id collision with the 0.7.0 plan). Scope not yet reconciled with docs/plan/roadmap.md.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` once 203 and 209 have merged
**Depends on:** 203, 209 (209 only because both edit the shared Spring files listed below)
**Serialised with:** 209, 211, 212 (shared: `PrivacyExtensionPoints.java`, the bean-inventory lists,
`frozen-property-names.txt`, `DataPrismPropertiesValidator.java`, `docs/configuration.md`)
**Decided (owner, 2026-10-15):**
- **D-1.0-1.** Startup refuses without a `CaseAuthorizer` bean unless
  `dataprism.security-policy.case-authorisation=token-trusted` is set explicitly. The default bean is
  classified `REPLACEABLE`.
- **D-1.0-5.** Breaking change, shipped in 1.0.0.

**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityPolicyProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityWiring.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/Preflights.java *(insertions only, if the refusal is a preflight)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validatePolicy`, :516-537, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/CaseAuthorisationConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClientAllowlistConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java *(existing tests: the property sets or beans that start a context only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(configuration arguments only)*
- data-prism-integration-tests/src/main/resources/application.yaml
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/StarterStartupFailureTest.java *(configuration only)*
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java *(configuration only)*
- docker/server/application.yaml
- docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(case-authorisation setting only)*
- docs/configuration.md *(the `dataprism.security-policy` rows and the prose around them only)*

## Goal
Expose task 203's case authoriser and client allowlist through `dataprism.*`. A deployment that has not
decided how cases are authorised refuses to start. One that wants the old behaviour must say
`token-trusted` in so many words, and is warned. A configured allowlist admits only the listed clients.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityWiring.java:15-26 — the three security beans
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityPolicyProperties.java — `purposes`, `roles`
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java:516-537 — `validatePolicy`, the refusal style
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/Preflights.java — startup checks that need the bean factory
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ConfigurationMetadataDocumentedTest.java:27-56 and PropertyNamesFrozenTest.java:23-48 — every new property needs a description, a `docs/configuration.md` row and a frozen-list line
- `rg -l 'security-policy' --glob '!**/target/**'` lists every configuration that starts a context, and each needs the new setting or a bean
- docker/distribution/Dockerfile:54-58 — the published image ships no `application.yaml`, so it is not affected; `docker/server/application.yaml` is the quickstart's

## Acceptance
- [ ] `dataprism.security-policy.case-authorisation` binds as a string. Its only accepted value is `token-trusted`; any other value refuses startup with `UNSUPPORTED_CASE_AUTHORISATION`.
- [ ] A new `dataPrismCaseAuthorizer` bean is `@ConditionalOnMissingBean`, is created only when the property is `token-trusted`, returns `CaseAuthorizer.tokenTrusted()`, and has a `PrivacyExtensionPoints` row `REPLACEABLE` / `NONE` (D-1.0-1).
- [ ] With no application `CaseAuthorizer` bean and the property unset, startup refuses with `MISSING_CASE_AUTHORIZER`. The message names the property and the bean type. With an application bean and the property set, startup refuses with `CASE_AUTHORISATION_CONFLICT`. With the property alone, startup logs exactly one WARN, which names the setting and contains no claim value.
- [ ] `dataPrismScopeResolver` passes the `CaseAuthorizer` bean to `ScopeResolver`. `CaseAuthorisationConfigurationTest` covers all four combinations, and proves through the wired `ScopeResolver` that an application authorizer returning `false` produces `CASE_NOT_AUTHORISED`.
- [ ] `dataprism.security-policy.allowed-clients` binds as a list and has no default. Unset means `ClientAllowlist.unrestricted()`. Set to an empty value, it refuses with `EMPTY_CLIENT_ALLOWLIST`. A blank entry refuses with `BLANK_ALLOWED_CLIENT`. Otherwise it reaches `AuthorizationService` as `ClientAllowlist.of(...)`. `ClientAllowlistConfigurationTest` covers all four and proves that a disallowed client gets `CLIENT_NOT_ALLOWED` through the wired bean.
- [ ] Every configuration under Owns that starts a context still starts. The quickstart and test configurations use `case-authorisation: token-trusted`, each with a comment saying that a real deployment supplies a `CaseAuthorizer`. `docker/multi-instance/kubernetes.yaml` gets the setting with the same comment.
- [ ] `docs/configuration.md` documents both properties, their defaults and all five refusal codes. It says how a standalone-server deployment supplies a `CaseAuthorizer` (an extension jar on `loader.path`, as in `data-prism-quickstart-extension`). It contains one sentence saying that the client allowlist does not control which model endpoint a client sends output to, or where the data is transferred, and that enforcing those belongs to an API gateway or egress control outside Data Prism.
- [ ] `AutoConfiguredBeanClassificationTest`, `PrivacyExtensionPointsTest`, `ConfigurationMetadataDocumentedTest` and `PropertyNamesFrozenTest` pass. The frozen list and bean-inventory diffs contain only lines for what this task adds.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- A built-in case authorizer backed by a store or a token claim.
- An example `CaseAuthorizer` in `data-prism-quickstart-extension`. File it as a follow-up if wanted.
- Audit, free-text and cluster wiring (tasks 211, 209 and 212).
- `server.json`. Registry metadata changes go with the release cut.
- Every `docs/` file other than `docs/configuration.md` (tasks 213 and 214).
