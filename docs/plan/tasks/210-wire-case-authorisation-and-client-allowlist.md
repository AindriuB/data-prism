# 210 — Wire case authorisation and the client allowlist into configuration

Renumbered from 195 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.8.0 (before the 0.9 API freeze)
**Base branch:** `origin/main` once 203 and 209 have merged
**Depends on:** 203, 209 (209 only because both edit the shared Spring files listed below)
**Wave:** 5 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Serialised with:** 208, 211, and the cluster-wiring successor tasks (shared: `PrivacyExtensionPoints.java`, the bean-inventory lists,
`frozen-property-names.txt`, `DataPrismPropertiesValidator.java`, `docs/configuration.md`)
**Decided (owner, 2026-10-15):**
- **D-1.0-1.** Startup refuses without a `CaseAuthorizer` bean unless
  `dataprism.security-policy.case-authorisation=token-trusted` is set explicitly. The default bean is
  classified `REPLACEABLE`.
- **D-1.0-5.** Breaking change, required by 1.0.0 and scheduled for 0.8.0 before the freeze.

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
  - Task 245's three new test files (`DenialAuditUnavailableServiceTest`, `DenialAuditUnavailableOperatorTest`, `ResumeAuditOrderingTest`) are narrowed to carry none of these. If 245 has merged first, this task's sweep updates `OperatorHarness` and the shared fixtures and leaves those three files alone. There is no edge, by decision of 2026-10-21 for the 0.7.1 backport.
- data-prism-integration-tests/src/main/resources/application.yaml
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/StarterStartupFailureTest.java *(configuration only)*
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java *(configuration only)*
- docker/server/application.yaml
- docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(the case-authorisation setting; and, widened 2026-10-23, the server image tag and the check that the 0.8 cluster-key and name-key settings take effect)*
- docs/configuration.md *(the `dataprism.security-policy` rows and the prose around them only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/DeploymentImageTagTest.java *(new, if the image-tag check is a test rather than the version sweep; added 2026-10-23)*

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
- From the external review of PR #154, round 5 (2026-10-23): `StarterStartupFailureTest` carries a dead property line (one the context never reads). Remove it while adding this task's configuration to the file.
- From the external review of PR #159 (2026-10-23, Medium, security): `docker/multi-instance/kubernetes.yaml` sets the 0.8 cluster-key and name-key settings (222) but pins `ghcr.io/aindriub/data-prism-server:0.7.0`, which ignores them, so an operator who applies the file runs members without the keys they think they configured. Change the tag to the first version that reads them (0.8.0, published by the 0.8.0 release; say so in a comment), and check that the settings take effect: a test that reads the file and fails when the image tag is older than the project version, or an equivalent check in the existing version sweep. `docker/multi-instance/compose.yaml` has the same gap by another route: with published images it takes the server image from `../../compose.yaml`, which defaults to `${QUICKSTART_IMAGE_TAG:-latest}`, so until 0.8.0 is published `latest` is a 0.7 image that ignores the key settings. Make the multi-instance file refuse or warn on an older image (its header comment at least), and leave the root `compose.yaml` default to 262, which owns that file.
- From review round 7 of PR #163 (2026-10-24): the build-time gate (`TokenTrustedFallbackRemovedTest`, `project-version.properties`, the `<testResources>` entries) is gone, replaced by a runtime check in `SecurityWiring`. With no `CaseAuthorizer` bean and a manifest `Implementation-Version` that is non-SNAPSHOT and at least 0.8.0, startup refuses with `MISSING_CASE_AUTHORIZER`. 210 must keep that refusal, or supersede it with its explicit `token-trusted` opt-in, and adjust the tests in `SecurityWiringTest` (`the_fallback_*`, `with_a_case_authorizer_bean_every_version_starts`) to match.

## Acceptance
- [ ] `dataprism.security-policy.case-authorisation` binds as a string. Its only accepted value is `token-trusted`; any other value refuses startup with `UNSUPPORTED_CASE_AUTHORISATION`.
- [ ] A new `dataPrismCaseAuthorizer` bean is `@ConditionalOnMissingBean`, is created only when the property is `token-trusted`, returns `CaseAuthorizer.tokenTrusted()`, and has a `PrivacyExtensionPoints` row `REPLACEABLE` / `NONE` (D-1.0-1).
- [ ] With no application `CaseAuthorizer` bean and the property unset, startup refuses with `MISSING_CASE_AUTHORIZER`. The message names the property and the bean type. With an application bean and the property set, startup refuses with `CASE_AUTHORISATION_CONFLICT`. With the property alone, startup logs exactly one WARN, which names the setting and contains no claim value.
- [ ] `dataPrismScopeResolver` passes the `CaseAuthorizer` bean to `ScopeResolver`. `CaseAuthorisationConfigurationTest` covers all four combinations, and proves through the wired `ScopeResolver` that an application authorizer returning `false` produces `CASE_NOT_AUTHORISED`.
- [ ] `dataprism.security-policy.allowed-clients` binds as a list and has no default. Unset means `ClientAllowlist.unrestricted()`. Set to an empty value, it refuses with `EMPTY_CLIENT_ALLOWLIST`. A blank entry refuses with `BLANK_ALLOWED_CLIENT`. Otherwise it reaches `AuthorizationService` as `ClientAllowlist.of(...)`. `ClientAllowlistConfigurationTest` covers all four and proves that a disallowed client gets `CLIENT_NOT_ALLOWED` through the wired bean.
- [ ] Every configuration under Owns that starts a context still starts. The quickstart and test configurations use `case-authorisation: token-trusted`, each with a comment saying that a real deployment supplies a `CaseAuthorizer`. `docker/multi-instance/kubernetes.yaml` gets the setting with the same comment.
- [ ] `docs/configuration.md` documents both properties, their defaults and all five refusal codes. It says how a standalone-server deployment supplies a `CaseAuthorizer` (an extension jar on `loader.path`, as in `data-prism-quickstart-extension`). It contains one sentence saying that the client allowlist does not control which model endpoint a client sends output to, or where the data is transferred, and that enforcing those belongs to an API gateway or egress control outside Data Prism.
- [ ] `AutoConfiguredBeanClassificationTest`, `PrivacyExtensionPointsTest`, `ConfigurationMetadataDocumentedTest` and `PropertyNamesFrozenTest` pass. The frozen list and bean-inventory diffs contain only lines for what this task adds.
- [ ] No image older than the project version (review of PR #160, security): `grep -rnE 'data-prism-[a-z-]+:(0\.[0-7]\.[0-9]+|latest)' docker/multi-instance/` prints nothing, `docker/multi-instance/kubernetes.yaml` names `ghcr.io/aindriub/data-prism-server:0.8.0`, and the same check runs in the build (`DeploymentImageTagTest`, which reads the file and compares the tag with the project version, or the existing version sweep; the Outcome names which) so the pin cannot fall behind again. `docker/multi-instance/compose.yaml`'s header says the published-image route needs 0.8.0 or later for the key settings to take effect.
- [ ] `StarterStartupFailureTest` no longer carries the dead property line from the round-5 review: the diff removes it, the Outcome names it, and the test still passes.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- A built-in case authorizer backed by a store or a token claim.
- An example `CaseAuthorizer` in `data-prism-quickstart-extension`. File it as a follow-up if wanted.
- Audit, free-text and cluster wiring (211, 208 and the successors planned by 215).
- `server.json`. Registry metadata changes go with the release cut.
- Every `docs/` file other than `docs/configuration.md` (tasks 213 and 214).
