# 209 — Run the pattern validator and free-text recognisers in every Spring-built orchestrator

Renumbered from 194 on 2026-10-19 (id collision with the 0.7.0 plan). Scope not yet reconciled with docs/plan/roadmap.md.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` once 208 has merged
**Depends on:** 208
**Serialised with:** 210, 211, 212. All four edit `PrivacyExtensionPoints.java` and the bean-inventory
lists, so they run one at a time in the order 209, 210, 211, 212.

**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java *(one new `@Bean` method beside `dataPrismRawValueLeakValidator`, :159-166)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only: one row)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPointsTest.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated with `-Ddataprism.inventory.write=true`; only the new bean's lines may change)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PatternValidatorWiringTest.java *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesPatternValidatorTest.java *(new)*

## Goal
Both Spring-built orchestrators take their validator list from the `LlmResponseValidator` beans
(`OrchestrationWiring.java:41-51`, `ConfiguredJsonSourcesAutoConfiguration.java:128-145`). The only
framework bean of that type is `RawValueLeakValidator` (`PrivacyEngineWiring.java:159-166`), so on
current `main` **the shape scan never runs in the starter or the packaged server**. Only hand-wired
assemblies (`DataPrismAssembly`, tests) add `SensitivePatternValidator`. That contradicts
`DefaultContextOrchestrator.java:89-95` ("Pattern detection is not a caller's choice") and
`docs/faq.md:37-39` ("a fail-closed validator scans every response"). Register the pattern validator
as an always-on framework bean, built with every application `FreeTextRecogniser` bean, so both paths
run it.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java:159-166 — the unconditional, list-collected leak validator; copy its shape and its Javadoc reasoning
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/OrchestrationWiring.java:41-51 and data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java:128-145 — the two consumers; neither needs editing
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java:1-80 — the `PRIVACY_CRITICAL` / `COMPETING_BEAN_REFUSAL` contract for a list-collected bean
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java and AutoConfiguredBeanInventoryTest.java — the two exhaustiveness guards a new `@Bean` must satisfy
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitivePatternValidator.java — the `(SensitiveDataScanner, List<FreeTextRecogniser>)` constructor from task 208

## Acceptance
- [ ] A new `@Bean` method `dataPrismSensitivePatternValidator` returns `LlmResponseValidator`. It is unconditional, not `@ConditionalOnMissingBean`, and builds `SensitivePatternValidator` with every `FreeTextRecogniser` bean in `@Order` order, or none.
- [ ] `PrivacyExtensionPoints` classifies it `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`. `AutoConfiguredBeanClassificationTest` passes unmodified. `PrivacyExtensionPointsTest` gains a behavioural case showing that an application `LlmResponseValidator` bean adds to the list and does not remove the pattern validator.
- [ ] The bean-inventory lists are regenerated. `git diff` on them shows only lines for `dataPrismSensitivePatternValidator`.
- [ ] `PatternValidatorWiringTest` starts the auto-configuration with a model whose `@NonSensitive` field returns a value shaped like an email address that is not an emitted pseudonym. It asserts that the call is refused with `VALIDATION_FAILED` and a first violation code of `SENSITIVE_PATTERN`. Mutation proof: removing the new bean makes that test fail. Name it in the close-out.
- [ ] `PatternValidatorWiringTest` also registers a `FreeTextRecogniser` bean that flags a fixed sentinel string, and asserts a `FREE_TEXT_RECOGNISED` refusal for a response containing the sentinel and success for one without it.
- [ ] `ConfiguredJsonSourcesPatternValidatorTest` proves the configured-JSON-sources orchestrator also refuses the email-shaped value.
- [ ] If any existing test in the reactor fails because the scan now runs on the Spring path, stop and report the test and the matched detector. Do not change fixtures, detectors or the validator to make it pass; a fixture tripping the scan is a finding for the owner.
- [ ] No property is added.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `DefaultContextOrchestrator`, `OrchestrationWiring` and `ConfiguredJsonSourcesAutoConfiguration`, which need no change.
- Any shipped `FreeTextRecogniser` implementation.
- `docs/faq.md`, `docs/extending.md` and `docs/architecture.md` (task 213, which records that the scan was not wired on the Spring path before this task).
- Security, audit and cluster wiring (tasks 210, 211 and 212).
