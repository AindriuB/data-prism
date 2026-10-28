# 210 — Wire case authorisation and the client allowlist explicitly, through Spring's single injection

Renumbered from 195 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19; see `docs/plan/reconciliation-2026-10-19.md`. Amended 2026-10-27 for owner decision 2 (explicit wiring) and decision 3 (base on wave 4).

**Repo:** `.`
**Release:** 0.8.0 (before the 0.9 API freeze)
**Base branch:** `origin/wave/0.8-wave4` (tip de5cf9dc at amendment), which holds 203 and its twelve review rounds. One PR for this task, targeting `wave/0.8-wave4`. PR #163 does not merge until 210, 288, 289 and 290 have merged into it (owner decision 3, 2026-10-27).
**Depends on:** none open (203 is on `wave/0.8-wave4`; 209 has landed)
**Wave:** 5
**Serialised with:** 208, 211, and the cluster-wiring successor tasks (shared: `PrivacyExtensionPoints.java`, the bean-inventory lists,
`frozen-property-names.txt`, `DataPrismPropertiesValidator.java`, `docs/configuration.md`)
**Decided (owner):**
- **D-1.0-1 (2026-10-15).** Startup refuses without a `CaseAuthorizer` bean unless
  `dataprism.security-policy.case-authorisation=token-trusted` is set explicitly. The default bean is
  classified `REPLACEABLE`.
- **Client allowlist, 2026-10-27 (reverses the 2026-10-24 decision that an unset allowlist means unrestricted).** It works the same way: startup refuses without a `ClientAllowlist` bean unless `dataprism.security-policy.allowed-clients` lists the clients or `dataprism.security-policy.client-allowlist=unrestricted` is set explicitly.
- **Decision 2, 2026-10-27: make the unsafe wirings impossible rather than detect them.** The default `ScopeResolver` and `AuthorizationService` take the bean through the one injection Spring uses, so Spring itself refuses none, many or a non-candidate. The release gate and the hand-written candidate lookup in `SecurityWiring` go.
- **D-1.0-5.** Breaking change, required by 1.0.0 and scheduled for 0.8.0 before the freeze.

**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityWiring.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityPolicyProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/Preflights.java *(insertions only, if a refusal is a preflight)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validatePolicy`, :669-691 on wave4, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/data-prism/version.properties *(deleted)*
- data-prism-spring-boot-autoconfigure/pom.xml *(the two `<resource>` entries that filter `version.properties`, and the surefire `dataprism.expectedVersion` system property, removed; nothing else)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for beans this task adds or removes)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/SecurityWiringTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/CaseAuthorisationConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClientAllowlistConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java *(existing tests: the property sets or beans that start a context only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(configuration arguments only; and in `ServerPackagingIT`, deleting `nestedAutoconfigureJarCarriesTheProjectVersionResource`)*
  - Task 245's three test files (`DenialAuditUnavailableServiceTest`, `DenialAuditUnavailableOperatorTest`, `ResumeAuditOrderingTest`) carry none of these; leave them alone (decision of 2026-10-21).
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfigurationTest.java, ConfiguredJsonSourcesLimitsTest.java, ConfiguredJsonSourcesPatternValidatorTest.java *(the property sets or beans that start a context only)*
- data-prism-integration-tests/src/main/resources/application.yaml
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/StarterStartupFailureTest.java, AuditSinkFailureAbortsResponseTest.java, ConfiguredJsonNestedHttpTest.java, McpHttpEndToEndTest.java *(configuration only)*
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java, identity/IdentityResolverOrderingTest.java, identity/IdentityResolverOverrideTest.java *(configuration only)*
- load-test/src/main/java/io/github/aindriub/dataprism/loadtest/ServerProcess.java *(the server's start arguments only)*
- docker/server/application.yaml *(the `dataprism.security-policy` settings only)*
- docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(the two security-policy settings; and, widened 2026-10-23, the server image tag and the check that the 0.8 cluster-key and name-key settings take effect)*
- docs/configuration.md *(the `dataprism.security-policy` rows and the prose around them only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/DeploymentImageTagTest.java *(new, if the image-tag check is a test rather than the version sweep; added 2026-10-23)*

## Goal
Expose task 203's case authoriser and client allowlist through `dataprism.*`, and make each an explicit decision. A deployment that has not decided how cases are authorised, or which clients are allowed, refuses to start. One that wants the permissive behaviour says so in so many words and is warned. The default resolver and service get the deployer's bean the way Spring injects any dependency, so the cardinality, candidate and scoped-proxy cases that 203's twelve review rounds kept finding are Spring's to refuse, not ours to detect. What remains of the guard checks the outcome: every resolver and service in the context holds the injected bean.

## The wiring
- **Case authorisation.**
  - `dataprism.security-policy.case-authorisation` binds as a string. Its only accepted value is `token-trusted`; any other value refuses with `UNSUPPORTED_CASE_AUTHORISATION`.
  - With `token-trusted`, a `dataPrismCaseAuthorizer` bean returning `CaseAuthorizer.tokenTrusted()` is defined, with a `PrivacyExtensionPoints` row `REPLACEABLE` / `NONE`, and startup logs exactly one WARN naming the setting and containing no claim value. With the setting and an application `CaseAuthorizer` bean, startup refuses with `CASE_AUTHORISATION_CONFLICT`.
  - `dataPrismScopeResolver` gets the bean through `ObjectProvider<CaseAuthorizer>.getObject()` (or a direct constructor parameter). If Spring finds no injectable bean (`NoSuchBeanDefinitionException` that is not a `NoUniqueBeanDefinitionException`), it refuses with `MISSING_CASE_AUTHORIZER`, whose message names the property and the bean type. Every other failure of that one resolution, such as two candidates with no `@Primary`, propagates as Spring raises it.
- **Client allowlist.**
  - `dataprism.security-policy.allowed-clients` binds as a list with no default. Set, it defines a `dataPrismClientAllowlist` bean of `ClientAllowlist.of(...)`; empty refuses with `EMPTY_CLIENT_ALLOWLIST`, a blank entry with `BLANK_ALLOWED_CLIENT`.
  - `dataprism.security-policy.client-allowlist` binds as a string. Its only accepted value is `unrestricted`; any other refuses with `UNSUPPORTED_CLIENT_ALLOWLIST`. With it, `dataPrismClientAllowlist` is `ClientAllowlist.unrestricted()`, and startup logs exactly one WARN naming the setting.
  - Both properties set, or either with an application `ClientAllowlist` bean, refuses with `CLIENT_ALLOWLIST_CONFLICT`.
  - `dataPrismAuthorizationService` gets the bean through the same single injection. No injectable bean refuses with `MISSING_CLIENT_ALLOWLIST`, naming both properties and the bean type.
  - `dataPrismClientAllowlist` has a `PrivacyExtensionPoints` row `REPLACEABLE` / `NONE`.
- **`BypassCheck`, kept and reduced** (`SecurityWiring.java:145-182` on wave4, `@Lazy(false)` as round 11 made it). Once every singleton exists it resolves `CaseAuthorizer` and `ClientAllowlist` by the same resolution as the injection, through `AutowireCapableBeanFactory.resolveNamedBean(type)` on the context's bean factory, which also gives the name. With no injectable bean it refuses `MISSING_CASE_AUTHORIZER` or `MISSING_CLIENT_ALLOWLIST`, which covers an application `ScopeResolver` or `AuthorizationService` that skips the default. If `isSingleton(name)` is false it refuses with `CASE_AUTHORIZER_BYPASSED` or `CLIENT_ALLOWLIST_BYPASSED`, with a message saying the bean must be a singleton (or a scoped proxy) so that every holder can be shown to hold it. Then every `ScopeResolver` and `AuthorizationService` bean, this context and its ancestors, must hold that instance (`usesCaseAuthorizer`, `usesClientAllowlist`), or it refuses with the same two codes as today.
- **Removed from `SecurityWiring`:** the release gate (`VERSION`, `VERSION_RESOURCE`, `LibraryVersion`, `embeddedVersion`, `fallbackForbidden`, and their use at :160-165); `lookup` and its helpers (`ambiguous`, `isOrphanScopedTarget`, `isDefaultCandidate`, `isGenuineScopedTarget`, `definitionOf`, :196-286); the `AMBIGUOUS_*` and `NON_SINGLETON_*` codes; the two `getIfUnique` fallbacks and their WARNs (:317-321, :330-335); and the Javadoc describing them.

## Verified at amendment (2026-10-27)
A probe against Spring Framework 7.0.9, the version Boot 4.1.1 brings, compared `ObjectProvider.getObject()`, direct constructor injection and `resolveNamedBean` across 24 contexts. All three resolve identically in every case:
- one bean: injected, singleton;
- none, only an `autowireCandidate=false` bean, or only a `defaultCandidate=false` bean: `NoSuchBeanDefinitionException`;
- two beans: `NoUniqueBeanDefinitionException`; two with one `@Primary`: the primary;
- one bean plus a `defaultCandidate=false` bean: the first;
- a bean merely named `scopedTarget.x`: injected as an ordinary bean;
- prototype scope, and a custom scope with no proxy: injected, but `isSingleton` is false, and each `getObject()` gives another instance, so the `isSingleton` check is needed;
- a scoped proxy, `TARGET_CLASS` or `INTERFACES`, of the interface `CaseAuthorizer`: the proxy is injected and is a singleton;
- an `INTERFACES` scoped proxy of the final class `ClientAllowlist`: `BeanNotOfRequiredTypeException` at startup; a `TARGET_CLASS` one: startup fails, because a final class cannot be subclassed;
- parent and child contexts: a bean only in the child is not seen by a resolver in the parent (`NoSuchBeanDefinitionException`); a bean only in the parent is injected into a resolver in the child; with a bean in both, the child's wins; a `defaultCandidate=false` bean in the child hides the parent's (`NoSuchBeanDefinitionException`).

So round 12's scoped-proxy cases all either resolve to a singleton the outcome check can compare, or fail startup inside Spring.

**Parent and child contexts, decided at amendment.** Covered, not a known limit, provided the outcome check keeps enumerating ancestors:
- Auto-configuration in the parent, the deployer's bean only in the child: the parent refuses `MISSING_*` unless it opts out explicitly, and then the child's bean is not consulted. That is the parent's explicit choice. It is recorded under Known limits.
- Auto-configuration in both, the parent with its own `CaseAuthorizer` bean and the child with another: the child's `@ConditionalOnMissingBean` sees the parent's resolver and builds none. The child's `BypassCheck` resolves the child's bean (a local bean wins, per the probe), finds the parent's resolver holding the parent's instance, and refuses `CASE_AUTHORIZER_BYPASSED`. This is a test case.
- Auto-configuration in both, `token-trusted` set in the parent and a bean only in the child: `setParent` merges the parent's environment into the child (checked with a probe on 2026-10-27: the child reads `token-trusted`), so the child sees the setting and its own bean and refuses `CASE_AUTHORISATION_CONFLICT`. This is a test case too. Corrected after the internal review of 058d0f89, which expected `*_BYPASSED` here.

## Context
- `SecurityWiring.java` on wave4: :83-137 the release gate, :145-182 `BypassCheck`, :196-286 `lookup` and helpers, :289-294 the guard bean, :313-338 the two default factories.
- `docs/plan/tasks/retired/203-case-authorisation-and-client-allowlist.md` on wave4, "Review round 12": the cases this design must still refuse or accept. Its "Accepted" line (two candidates refuse even with `@Primary`) is reversed: Spring's `@Primary` now decides, and the outcome check then holds every resolver to the primary.
- data-prism-spring-boot-autoconfigure/pom.xml:39-52 (the `version.properties` filter) and :55-62 (surefire `dataprism.expectedVersion`, read only by `SecurityWiringTest`:597-604). data-prism-server/pom.xml:97 passes `project.version` to failsafe; `ServerPackagingIT` still needs it for its other tests, so it stays. `ServerPackagingIT.java:47-81` is the version-resource test to delete.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismConfigurationFailureAnalyzer.java: how a `DataPrismConfigurationException` thrown inside a bean factory method reaches the startup message; check that the wrapped `MISSING_*` shows its code.
- `DataPrismProperties` binds with `ignoreUnknownFields = false`, so no configuration may set the new properties before this task lands. 271 (same wave) therefore supplies beans, not properties, in its new test.
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java:84-88 builds its `ScopeResolver` with `CaseAuthorizer.tokenTrusted()` in plain Java. With the release gate gone it is not refused; no change. The round-8 Context bullet on wave4 that said otherwise is superseded.
- From the external review of PR #154, round 5 (2026-10-23): `StarterStartupFailureTest` carries a dead property line (one the context never reads). Remove it while adding this task's configuration to the file.
- From the external review of PR #159 (2026-10-23, Medium, security): `docker/multi-instance/kubernetes.yaml` pins `ghcr.io/aindriub/data-prism-server:0.7.0`, which ignores the 0.8 cluster-key and name-key settings. Change the tag to 0.8.0 (published by the 0.8.0 release; say so in a comment), and check it in the build: a test that reads the file and fails when the image tag is older than the project version, or an equivalent check in the existing version sweep. `docker/multi-instance/compose.yaml` takes the server image from `../../compose.yaml` (`${QUICKSTART_IMAGE_TAG:-latest}`); make the multi-instance file's header say the published-image route needs 0.8.0 or later, and leave the root `compose.yaml` to 262.
- `rg -l 'security-policy|AutoConfigurations.of|@SpringBootTest|SpringApplication' --glob '!**/target/**'` lists the contexts to check. A context added by another task after this one lands (271's `ScrubberDescriptorWiringTest`, 273 to 283) carries its own settings.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `CaseAuthorisationConfigurationTest` covers: no bean and no property (`MISSING_CASE_AUTHORIZER`); the property alone (starts, one WARN); an application bean alone (starts, the default resolver holds it); both (`CASE_AUTHORISATION_CONFLICT`); an unsupported value (`UNSUPPORTED_CASE_AUTHORISATION`). Through the wired `ScopeResolver`, an application authoriser returning `false` produces `CASE_NOT_AUTHORISED`.
- [ ] `ClientAllowlistConfigurationTest` covers: nothing set and no bean (`MISSING_CLIENT_ALLOWLIST`); `allowed-clients` alone (starts; a disallowed client gets `CLIENT_NOT_ALLOWED` through the wired service); `client-allowlist: unrestricted` alone (starts, one WARN); an application bean alone; each conflict (`CLIENT_ALLOWLIST_CONFLICT`); an empty list (`EMPTY_CLIENT_ALLOWLIST`); a blank entry (`BLANK_ALLOWED_CLIENT`); an unsupported value (`UNSUPPORTED_CLIENT_ALLOWLIST`).
- [ ] **Single injection.** For each of `CaseAuthorizer` and `ClientAllowlist`, `SecurityWiringTest` covers: two beans (startup fails; the root cause is Spring's `NoUniqueBeanDefinitionException`, naming both beans); two with one `@Primary` (starts; every resolver or service holds the primary); only an `autowireCandidate=false` bean and only a `defaultCandidate=false` bean (`MISSING_*`); a prototype-scoped bean (`*_BYPASSED`, the singleton message); a request- or custom-scoped `CaseAuthorizer` behind a `TARGET_CLASS` and an `INTERFACES` scoped proxy (starts); an `INTERFACES` scoped `ClientAllowlist` (startup fails). These replace the `AMBIGUOUS_*` and `NON_SINGLETON_*` cases.
- [ ] **Outcome check.** An application `ScopeResolver` built with `CaseAuthorizer.tokenTrusted()` while an application `CaseAuthorizer` bean exists refuses `CASE_AUTHORIZER_BYPASSED`; the same for `AuthorizationService` and `CLIENT_ALLOWLIST_BYPASSED`. An application resolver or service built with the injected bean starts. With no bean and no property, an application `ScopeResolver` that skips the default still refuses `MISSING_CASE_AUTHORIZER`. Each refusal also fires with `spring.main.lazy-initialization=true`.
- [ ] **Parent and child.** With the auto-configuration in both contexts:
  - a `CaseAuthorizer` bean in the parent and another in the child: the child refuses `CASE_AUTHORIZER_BYPASSED`;
  - `token-trusted` set in the parent and a bean only in the child: the child refuses `CASE_AUTHORISATION_CONFLICT`, because it inherits the parent's environment.

  With the auto-configuration in the child only and the bean in the parent, the child starts and its resolver holds the parent's bean. If a case fires another code than stated, the implementer records which and why in the Outcome and asks the coordinator before changing the expectation.
- [ ] **Removed.** `SecurityWiring` has no `LibraryVersion`, `embeddedVersion`, `fallbackForbidden`, `lookup` or `getIfUnique`, and no `AMBIGUOUS_` or `NON_SINGLETON_` string; `version.properties`, its pom filter, the surefire property and `ServerPackagingIT.nestedAutoconfigureJarCarriesTheProjectVersionResource` are gone. `SecurityWiringTest`'s release-gate cases (`the_fallback_*`, `with_a_case_authorizer_bean_every_version_starts`, the `embeddedVersion` and `expectedVersion` cases) are deleted, and the Outcome lists them.
- [ ] Every configuration under Owns that starts a context still starts. The quickstart, test and example configurations use `case-authorisation: token-trusted` and either `allowed-clients` or `client-allowlist: unrestricted`, each with a comment saying that a real deployment supplies a `CaseAuthorizer` and a list of clients. `docker/multi-instance/kubernetes.yaml` gets both settings with the same comment.
- [ ] `docs/configuration.md` documents the three properties, that none has a default, and all ten refusal codes (`UNSUPPORTED_CASE_AUTHORISATION`, `CASE_AUTHORISATION_CONFLICT`, `MISSING_CASE_AUTHORIZER`, `UNSUPPORTED_CLIENT_ALLOWLIST`, `CLIENT_ALLOWLIST_CONFLICT`, `MISSING_CLIENT_ALLOWLIST`, `EMPTY_CLIENT_ALLOWLIST`, `BLANK_ALLOWED_CLIENT`, `CASE_AUTHORIZER_BYPASSED`, `CLIENT_ALLOWLIST_BYPASSED`). It says that two beans of either type fail with Spring's own error unless one is `@Primary`. It says how a standalone-server deployment supplies a `CaseAuthorizer` (an extension jar on `loader.path`, as in `data-prism-quickstart-extension`). It contains one sentence saying that the client allowlist does not control which model endpoint a client sends output to, or where the data is transferred, and that enforcing those belongs to an API gateway or egress control outside Data Prism.
- [ ] `AutoConfiguredBeanClassificationTest`, `PrivacyExtensionPointsTest`, `ConfigurationMetadataDocumentedTest` and `PropertyNamesFrozenTest` pass. The frozen list and bean-inventory diffs contain only lines for what this task adds or removes.
- [ ] No image older than the project version (review of PR #160, security): `grep -rnE 'data-prism-[a-z-]+:(0\.[0-7]\.[0-9]+|latest)' docker/multi-instance/` prints nothing, `docker/multi-instance/kubernetes.yaml` names `ghcr.io/aindriub/data-prism-server:0.8.0`, and the same check runs in the build (`DeploymentImageTagTest`, or the existing version sweep; the Outcome names which). `docker/multi-instance/compose.yaml`'s header says the published-image route needs 0.8.0 or later for the key settings to take effect.
- [ ] `StarterStartupFailureTest` no longer carries the dead property line from the round-5 review: the diff removes it, the Outcome names it, and the test still passes.
- [ ] The Outcome lists, for 290, every code this task adds, changes or removes and the new property, with trigger and message, so 290 can write the contract-register rows.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Known limits (not closed by design; `docs/configuration.md` states the first two)
- A deployer bean that is itself `CaseAuthorizer.tokenTrusted()`, or always answers `true`, or a `ClientAllowlist` that admits everyone: Data Prism checks that a decision was made, not what it decides.
- An `HttpTransport` built by hand around its own `ScopeResolver` or `AuthorizationService`, outside the bean factory: the outcome check sees beans only.
- A parent context that opts out explicitly while the deployer's bean lives only in a child where the auto-configuration does not run: the bean is not consulted, by the parent's own explicit setting.

## Out of scope
- A built-in case authoriser backed by a store or a token claim.
- An example `CaseAuthorizer` in `data-prism-quickstart-extension`. File it as a follow-up if wanted.
- Audit, free-text and cluster wiring (211, 208, 284 to 287).
- `server.json`. Registry metadata changes go with the release cut.
- `CHANGELOG.md`, `docs/migration-0.8.md` and every `docs/` file other than `docs/configuration.md` (213, which documents case authorisation and the allowlist from this task's Outcome).
- The contract register (290 writes this task's rows from its Outcome; 271 owns the register in this wave).
- The 0.9 onboarding briefs 273 to 283, which carry their own line on these settings.

## Outcome (2026-10-27, 0.8; PR #168, tip 66eaf863, wave/0.8-wave4)
Case authorisation and the client allowlist are explicit decisions: three properties and ten refusal codes. Defaults inject through Spring's single injection and `BypassCheck` checks the injected outcome on real instances. The release gate, the bean lookup, `AMBIGUOUS_*`, `NON_SINGLETON_*`, `getIfUnique` and `LibraryVersion` are removed. This is the structural fix for #163's security wiring (owner decision 2 (b), 2026-10-27).

Decisions. OD-210-1 (a): allowlists are compared by value (`ClientAllowlist` equals/hashCode, `AuthorizationService.usesClientAllowlist`), so a child context inheriting the parent's list starts and a different list is refused. Owns widened to `data-prism-security`: `ClientAllowlist`, `AuthorizationService`, `CaseAuthorizer`, and a new package-private `TokenTrustedCaseAuthorizer` (`tokenTrusted()` is one instance by construction).

For task 290 (contract register). New properties, unset by default and in the frozen list: `dataprism.security-policy.case-authorisation` (only `token-trusted`), `dataprism.security-policy.allowed-clients` (list), `dataprism.security-policy.client-allowlist` (only `unrestricted`). New beans (REPLACEABLE/NONE): `dataPrismCaseAuthorizer`, `dataPrismClientAllowlist`, `dataPrismSecurityPolicyPreflight`; bean count 58 to 61. Codes added: `UNSUPPORTED_CASE_AUTHORISATION`, `CASE_AUTHORISATION_CONFLICT`, `MISSING_CASE_AUTHORIZER` (no longer a release gate), `UNSUPPORTED_CLIENT_ALLOWLIST`, `CLIENT_ALLOWLIST_CONFLICT`, `MISSING_CLIENT_ALLOWLIST`, `EMPTY_CLIENT_ALLOWLIST`, `BLANK_ALLOWED_CLIENT`. Changed: `CASE_AUTHORIZER_BYPASSED` and `CLIENT_ALLOWLIST_BYPASSED` also fire when the injected bean is not a singleton. Removed: the four `AMBIGUOUS_*` and `NON_SINGLETON_*` codes. Exactly one WARN each for `token-trusted` and `unrestricted`, naming the property and no claim value.

Review: three internal rounds; none Medium+ in round 3. Accepted residual: a `FactoryBean` with a null object type has its product created whenever a setting is in use, and if `getObject()` throws, startup fails with that exception (fail closed); one that misreports its type is caught as BYPASSED by name wiring. `StarterStartupFailureTest`'s dead property line is removed. `DeploymentImageTagTest` fails when a deployment image tag is older than the project version.
