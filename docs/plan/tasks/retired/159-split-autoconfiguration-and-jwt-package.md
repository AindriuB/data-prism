# 159 — Split `DataPrismAutoConfiguration` by concern and move JWT support into `spring.boot.jwt` (pure move)

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2) after 158 has merged into it.
**Depends on:** 158
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/*.java (except `*Properties.java`, which 158 settled; type references only there)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/jwt/** (new)
- data-prism-spring-boot-autoconfigure/src/test/**
- data-prism-server/src/** (imports and FQCN strings only, including `ServerArchitectureTest`)
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/http/SecurityConfig.java and its test (imports only)
- data-prism-quickstart-extension/src/** (imports and FQCN strings only)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java
- docs/extending.md, docs/developer-guide/custom-identity-resolver.md (FQCNs and nested-class names only)

## Goal
Owner decision D-0.6-3, second half. `DataPrismAutoConfiguration` is 1,060
lines with 55 `@Bean` methods. Split it into package-private
`@Configuration(proxyBeanMethods = false)` classes by concern, all reached
from `DataPrismAutoConfiguration` through `@Import`, and move
`JwtDecoderSupport` and `JwtCallerContextExtractor` into `spring.boot.jwt`.
The auto-configuration FQCN, every bean name, bean type, condition and the
effective registration order stay as they are.

## Context
- DataPrismAutoConfiguration.java:122-160 (`@AutoConfiguration`, `@Import` of the existing nested selections), :164-320 (`IdentityResolverSelection`, `AuditSinkSelection`, `JsonProjection`), :639-830 (`AuditIntegrityHealth`, `ClusterBackedState`, `ReidentificationWiring`).
- AutoConfiguredBeanClassificationTest.java:20-110 — the sweep already follows nested and `@Import`ed classes recursively; the classification in `PrivacyExtensionPoints` is keyed by bean method name.
- ArchitectureTest.java:290-335 — `onlyTheExampleDependsOnSpringSecurity` exempts `JwtDecoderSupport` and `JwtCallerContextExtractor` by class, not package; its javadoc explains why.
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerArchitectureTest.java:18-30 — the same two classes as FQCN strings.
- Frozen FQCN: `io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration`, named in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `docs/developer-guide/custom-identity-resolver.md:235` (`after = ...`) and `docs/extending.md:734`.
- `@ConditionalOnMissingBean` resolves against what is already registered, and Spring processes an `@Import`ed class's beans before the importing class's own `@Bean` methods. Moving a bean between classes can change which of two conditional beans wins.

## Acceptance
- [ ] First commit, before any move: a test (for example `AutoConfiguredBeanInventoryTest`) that starts an `ApplicationContextRunner` with `DataPrismAutoConfiguration` under each property set already used by the module's configuration tests that produce a running context (at least: the default, `hash-chained` with a directory and checkpoint file, cluster on, re-identification on, oversight on), and asserts the sorted set of `(bean name, bean type)` pairs equals a checked-in list per scenario generated at the post-158 base. It passes on that commit.
- [ ] After the split the same test passes with the checked-in lists unchanged.
- [ ] `IdentityResolverOrderingTest` and `IdentityResolverOverrideTest` (data-prism-quickstart-extension) pass without edits beyond imports.
- [ ] `DataPrismAutoConfiguration` keeps its FQCN, `@AutoConfiguration` and `@EnableConfigurationProperties`, and is under 250 lines. Each extracted configuration class is package-private, `proxyBeanMethods = false`, and holds one concern; the hand-back lists each class with its beans.
- [ ] The `@Bean` sweep finds 55 methods, the same names as at base, and `PrivacyExtensionPoints` is unchanged apart from javadoc that names a moved class.
- [ ] `JwtDecoderSupport` and `JwtCallerContextExtractor` are in `io.github.aindriub.dataprism.spring.boot.jwt`. `onlyTheExampleDependsOnSpringSecurity` still exempts exactly these two classes (by class or by the `..spring.boot.jwt..` package; if by package, a new rule asserts the package holds only those two classes). `ServerArchitectureTest` names the new FQCNs.
- [ ] ArchUnit: `..spring.boot.jwt..` does not depend on `..spring.boot.validation..`, and 158's reverse rule is no longer vacuous (drop its `allowEmptyShould(true)`). Each proven non-vacuous by a temporary mutation reported in the hand-back.
- [ ] Unchanged files: `git diff` from the post-158 base is empty for both `META-INF` resource files and for `PrivacyExtensionPoints`' classification rows.
- [ ] Logging sites named in `docs/conventions.md` ("Deliberate, reviewed exception") keep their behaviour; if `dataPrismHashChainedAuditSink` moves class, the hand-back names the new class so task 162 can update that paragraph.
- [ ] String sweep: no file outside `target/`, `docs/plan/`, `docs/pack.md`, `docs/design-review.md` and `CHANGELOG.md` names `spring.boot.JwtDecoderSupport`, `spring.boot.JwtCallerContextExtractor`, or a nested class of `DataPrismAutoConfiguration` that no longer exists.
- [ ] The last commit's body carries the old → new FQCN table; task 162 builds the migration page from it.
- [ ] `mvn -B verify` over the full reactor exits 0.

- [ ] (Added 2026-10-08, from task 167's review.) `JwtDecoderSupport.parseDiscoveryMetadata` loses its stale `throws IOException`, since Jackson 3 exceptions are unchecked; callers' catch blocks adjust accordingly and the JWT tests still pass.

## Out of scope
- (t), the JSON projection as its own bean (task 161). Move `JsonProjection` and the `registerDisposableBean` wiring as they are.
- Adding, removing, renaming or reclassifying any bean.
- Any `spring.boot` subpackage other than `jwt` (`validation` is 158's).
- `docs/conventions.md` (task 162).

## Outcome
- `DataPrismAutoConfiguration` (FQCN unchanged, about 45 lines, no beans) `@Import`s 14 package-private `@Configuration(proxyBeanMethods = false)` classes in an order that reproduces the old registration order: `IdentityResolverSelection`, `AuditSinkSelection`, `AuditIntegrityHealth`, `ClusterBackedState`, `ReidentificationWiring`, `Preflights`, `PropertiesValidation`, `PrivacyEngineWiring`, `SecurityWiring`, `AuditWiring`, `ScopeBudgetWiring`, `OversightWiring`, `OrchestrationWiring`, `McpTransportWiring`. A `JsonProjection` helper class is separate.
- 50 `@Bean` methods. The task's "55" counted Javadoc mentions, not methods.
- `AutoConfiguredBeanInventoryTest` has a static view (method- and class-level conditions and classifications) and runtime views for 8 scenarios. The lists were generated before the move and are unchanged after it. The polish commit added class-level `@Conditional*` to the static view, proven by mutation.
- `JwtDecoderSupport` and `JwtCallerContextExtractor` moved to `spring.boot.jwt`. `parseDiscoveryMetadata` lost the stale `throws IOException`.
- ArchUnit: `validation` and `jwt` must not depend on each other, both directions, non-vacuous (proven by mutations). 158's `allowEmptyShould(true)` is gone.
- Scope waiver: a comment line in `validation/DataPrismContractValidator.java`.
- Verification: tester PASS at 4a0764a3 (full reactor JDK 21, 1669 tests, 0 failed, HEAD unchanged during the run; release-profile package green). Review APPROVE.
- Where things went: `dataPrismHashChainedAuditSink` and `JsonProjection` are in `AuditSinkSelection`; `dataPrismAuditRecorder` is in `AuditWiring`.
