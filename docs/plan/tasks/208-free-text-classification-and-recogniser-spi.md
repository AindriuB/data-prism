# 208 — Add the FREE_TEXT classification and a free-text recogniser hook in validation

Renumbered from 193 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.8.0 (before the 0.9 API freeze)
**Base branch:** `origin/main` after 0.7.0 integration; refresh the cited paths and call sites against that base before implementation.
**Depends on:** 209, 210 (extend the existing pattern-validator bean after security wiring)
**Wave:** 6 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/DataClassification.java *(append `FREE_TEXT` only)*
- data-prism-core/src/main/resources/privacy-profiles-default.yaml
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/policy/PrivacyProfilesTest.java
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/FreeTextRecogniser.java *(new)*
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitivePatternValidator.java
- data-prism-validation/src/test/java/io/github/aindriub/dataprism/validation/** *(new `SensitivePatternValidatorRecogniserTest.java`; in existing files only the one-argument constructor call at `ScopeAllowlistTest.java:159`)*

**Additional ownership for Spring recogniser wiring:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java *(existing `dataPrismSensitivePatternValidator` method only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PatternValidatorWiringTest.java *(recogniser cases only)*

## Goal
Validation recognises only a fixed list of identifier shapes (`SensitiveDataScanner`, `docs/faq.md:35-58`).
A notes or comments field that carries a name in prose has no classification that says "unstructured,
assume it holds personal data". Add `FREE_TEXT`, map it in both bundled profiles, and give the response
validator a hook for operator-supplied recognisers, such as a call to an entity-recognition service. A
recogniser can only add refusals and can never relax one. Core gains no recognition dependency.

## Context
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/DataClassification.java:8-35 — the constants and `SPECIAL_CATEGORIES`
- data-prism-core/src/main/resources/privacy-profiles-default.yaml:15-101 — `DEFAULT` and `STRICT`, each with a one-line reason per mapping
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/PrivacyProfiles.java — how a classification a profile does not map is handled; `FREE_TEXT` in a custom profile follows that rule unchanged
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitivePatternValidator.java:30-65 — constructors, the emitted-value exemption and `RESPONSE_TOO_LARGE_TO_SCAN`
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitiveDataScanner.java:44,94 — the depth cap of 16 and the size budget that recogniser calls must share
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/Violation.java — what a refusal carries; nothing in it may be a value
- `DataClassification.values()` is read by `ModelDescriptors`, `PrivacyProfiles` and the connectors' `ConfiguredJsonSources`, so a new constant is accepted in descriptors and YAML sources with no further change

## Acceptance
- [ ] Extend the unconditional bean from 209 to collect all application `FreeTextRecogniser` beans in `@Order` order. An empty list preserves the existing scan. Spring tests prove a sentinel recognition refuses, an unrecognised value passes, and a recogniser failure refuses without exposing its exception. Do not add a second validator bean or weaken 209's tests.
- [ ] `DataClassification.FREE_TEXT` is the last constant. It is not in `SPECIAL_CATEGORIES`. Its Javadoc says it marks unstructured text that may contain personal data in prose.
- [ ] `privacy-profiles-default.yaml` maps `FREE_TEXT: { action: REDACT }` in `DEFAULT` and `FREE_TEXT: { action: REMOVE }` in `STRICT`, each with a one-line comment giving the reason. `PrivacyProfilesTest` asserts both mappings, and asserts that every `DataClassification` constant is mapped in both bundled profiles. Mutation proof: deleting the `STRICT` line makes that test fail.
- [ ] `FreeTextRecogniser` is a public `@FunctionalInterface` in `io.github.aindriub.dataprism.validation`. Its method takes one string leaf value and returns the `Set<DataClassification>` it recognised, empty for none. It is not given the `PrivacyContext`, the prohibited set, the emitted set or the JSON path.
- [ ] `SensitivePatternValidator` has a constructor `(SensitiveDataScanner, List<FreeTextRecogniser>)`, which replaces the one-argument `(SensitiveDataScanner)` constructor. The no-argument constructor stays and means no recognisers.
- [ ] Every string leaf the shape scan visits is passed to each recogniser in list order, except a leaf equal to a value in `emitted`. A non-empty result adds a `Violation` with code `FREE_TEXT_RECOGNISED` and a detection method naming only the recognised classifications. The response is refused, never edited in place.
- [ ] A recogniser that throws adds a `Violation` with code `FREE_TEXT_RECOGNISER_FAILED`. Nothing from the exception (message, class or stack) appears in the violation. A test uses a sentinel message and asserts its absence.
- [ ] A recogniser that returns `null` is treated as a failure, with the same code.
- [ ] Recogniser calls are bounded by the same depth and size budget as the shape scan. Past the budget the result is the existing `RESPONSE_TOO_LARGE_TO_SCAN` refusal, and no leaf is silently left unchecked. A test covers this.
- [ ] With an empty recogniser list, every existing test in `data-prism-validation` passes, and the only edit to an existing test is the constructor call at `ScopeAllowlistTest.java:159`.
- [ ] No `pom.xml` gains a dependency.
- [ ] `./mvnw -B -pl data-prism-annotations,data-prism-core,data-prism-validation,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- Creating the pattern-validator bean (task 209, already in 0.7); this task extends its recogniser list.
- `RawValueLeakValidator` and `DefaultContextOrchestrator`. Recognisers live inside `SensitivePatternValidator`, which is already in the validator list, so neither needs a change.
- Any bundled recogniser, language model or entity-recognition library.
- `docs/faq.md`, `docs/extending.md` and every other doc (task 213).
