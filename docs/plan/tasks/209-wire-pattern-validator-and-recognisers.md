# 209 — Run the existing pattern validator in every Spring-built orchestrator

Renumbered from 194 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19: repair existing validation in 0.7; task 208 adds recognisers in 0.8.

**Repo:** `.`
**Release:** 0.7.0 (existing validation wiring defect)
**Base branch:** `plan/0.7.0`, after task 194; use `main` once the integration branch has merged.
**Depends on:** 194 (complete the existing key-shape scan first)
**Serialised with:** 201 (release documentation waits for this fix). Later 0.8 wiring tasks build on this bean.

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
as an always-on framework bean, using the existing no-argument constructor, so both paths
run it.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java:159-166 — the unconditional, list-collected leak validator; copy its shape and its Javadoc reasoning
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/OrchestrationWiring.java:41-51 and data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java:128-145 — the two consumers; neither needs editing
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java:1-80 — the `PRIVACY_CRITICAL` / `COMPETING_BEAN_REFUSAL` contract for a list-collected bean
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java and AutoConfiguredBeanInventoryTest.java — the two exhaustiveness guards a new `@Bean` must satisfy
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitivePatternValidator.java — the existing no-argument constructor; no recogniser SPI is needed

## Acceptance
- [ ] A new `@Bean` method `dataPrismSensitivePatternValidator` returns `LlmResponseValidator`. It is unconditional, not `@ConditionalOnMissingBean`, and builds `SensitivePatternValidator` with its existing no-argument constructor.
- [ ] `PrivacyExtensionPoints` classifies it `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`. `AutoConfiguredBeanClassificationTest` passes unmodified. `PrivacyExtensionPointsTest` gains a behavioural case showing that an application `LlmResponseValidator` bean adds to the list and does not remove the pattern validator.
- [ ] The bean-inventory lists are regenerated. `git diff` on them shows only lines for `dataPrismSensitivePatternValidator`.
- [ ] `PatternValidatorWiringTest` starts the auto-configuration with a model whose `@NonSensitive` field returns a value shaped like an email address that is not an emitted pseudonym. It asserts that the call is refused with `VALIDATION_FAILED` and a first violation code of `SENSITIVE_PATTERN`. Mutation proof: removing the new bean makes that test fail. Name it in the close-out.
- [ ] `ConfiguredJsonSourcesPatternValidatorTest` proves the configured-JSON-sources orchestrator also refuses the email-shaped value.
- [ ] If any existing test in the reactor fails because the scan now runs on the Spring path, stop and report the test and the matched detector. Do not change fixtures, detectors or the validator to make it pass; a fixture tripping the scan is a finding for the owner.
- [ ] No property is added.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `DefaultContextOrchestrator`, `OrchestrationWiring` and `ConfiguredJsonSourcesAutoConfiguration`, which need no change.
- `FREE_TEXT`, the recogniser SPI and its Spring wiring (task 208, 0.8).
- `docs/faq.md`, `docs/extending.md` and `docs/architecture.md` (task 201 records the 0.7 fix; task 213 adds the later recogniser documentation).
- Security, audit and cluster wiring (210, 211 and the successors planned by 215).
