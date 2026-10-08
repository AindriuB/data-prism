# 158 — Split `DataPrismProperties` by concern and move its validation into `spring.boot.validation` (pure move)

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2) after 157 has merged into it.
**Depends on:** 157, 167, 168, 169, 170
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/*Properties.java (new)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java (moved)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/** (new)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java (type references and validator wiring only)
- data-prism-spring-boot-autoconfigure/src/test/**
- data-prism-server/src/** (type references only)
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java and data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesTest.java (type references only; 170 also edits `rejectUnknownKeys` and the read path in `ConfiguredJsonSources.java`, so 158 branches after 170 has merged)
- data-prism-integration-tests/src/**/{SecurityConfig,SecurityConfigTest,ShippedDefaultsTest,ConfiguredJsonNestedHttpTest}.java (type references only)
- data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java (type references only)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java
- docs/**/*.md except docs/plan/**, docs/pack.md and docs/design-review.md (Java type names only)

## Goal
Owner decision D-0.6-3, first half. `DataPrismProperties` is 1,708 lines: a
root with about 700 lines of cross-property validation and 27 nested
property classes. Give each top-level concern (`transport`, `security`,
`security-policy`, `privacy`, `audit`, `correlation`, `metrics`, `hazelcast`,
`identity`, `sources`, `oversight`, `reidentification`, `operator`) its own
file in `spring.boot`, and move the validation logic into the new
`spring.boot.validation` package beside `DataPrismContractValidator`. Every
property name, prefix, default and refusal stays exactly as it is.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java:17-715 (root, getters and `validate*` methods), :716-1708 (nested classes).
- DataPrismContractValidator.java — package-private `final class`, calls `properties.validate()`; moving it to another package needs its bean wiring to change.
- PrivacyExtensionPoints.java and AutoConfiguredBeanClassificationTest.java — the `@Bean` sweep; this task adds no `@Bean`, so the sweep must pass unedited.
- No `spring-boot-configuration-processor` is declared in any pom at 438ef802, so no `META-INF/spring-configuration-metadata.json` is generated today (confirm against the built jar before relying on it).
- docs/configuration.md — the documented property list, which must not change.

## Acceptance
- [ ] First commit, before any move: a test (for example `PropertyNamesFrozenTest`) that walks the bindable JavaBean property tree from `DataPrismProperties` and asserts the full set of relaxed property paths, each with its Java type and default value, equals a checked-in list generated at the post-157 base. It passes on that commit.
- [ ] After the split the same test passes with the checked-in list unchanged (`git diff` on the list file is empty after the first commit).
- [ ] `DataPrismProperties.java` is under 300 lines and holds only the root `@ConfigurationProperties("dataprism")` binding; every former nested class is a top-level class in `io.github.aindriub.dataprism.spring.boot` (nested classes of a concern may stay nested inside that concern's file).
- [ ] Cross-property validation lives in `io.github.aindriub.dataprism.spring.boot.validation`, together with `DataPrismContractValidator`. The order in which validation checks run is unchanged: the hand-back lists the order at base and at HEAD.
- [ ] Refusals unchanged: the sorted list of refusal-code string literals in `data-prism-spring-boot-autoconfigure/src/main` is identical at the post-157 base and at HEAD (command and both counts in the hand-back), and every existing configuration-refusal test passes with edits confined to imports and type names.
- [ ] Spring configuration metadata: the hand-back states whether the built `data-prism-spring-boot-autoconfigure` jar contains `META-INF/spring-configuration-metadata.json` at base and at HEAD; the answer is the same at both.
- [ ] `grep -c '@Bean' DataPrismAutoConfiguration.java` is unchanged (55 at 438ef802), and `AutoConfiguredBeanClassificationTest` passes without edits.
- [ ] `@ConfigurationProperties` prefix `dataprism` and the `DataPrismAutoConfiguration` FQCN are unchanged; `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and `META-INF/spring.factories` are unchanged.
- [ ] ArchUnit, in `ArchitectureTest`: `..spring.boot.validation..` does not depend on `..spring.boot.jwt..` (vacuous until task 159 creates `jwt`; mark it `allowEmptyShould(true)` with a comment naming 159) and nothing outside `..spring.boot..` depends on `..spring.boot.validation..`.
- [ ] String sweep: no file outside `target/`, `docs/plan/`, `docs/pack.md`, `docs/design-review.md` and `CHANGELOG.md` names a removed nested type (`DataPrismProperties.Audit` and the like).
- [ ] The last commit's body carries the old → new type-name table; task 162 builds the migration page from it.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- Splitting `DataPrismAutoConfiguration` and the `spring.boot.jwt` package (task 159).
- Adding `spring-boot-configuration-processor`. Owner decision pending (D-0.6-9).
- Renaming, adding or removing any property, or changing a default or a refusal message.
- Any package under `spring.boot` other than `validation`.

## Owner decision D-0.6-9: decided 2026-10-08

Add `spring-boot-configuration-processor` in 0.6.0, for IDE autocomplete and inline docs of `dataprism.*` keys in YAML. This task stays a pure move. A separate small task right after 158 adds the processor:
- it is an optional, build-time-only dependency in the autoconfigure module;
- the generated `META-INF/spring-configuration-metadata.json` must ship in the jar;
- a check confirms that every key documented in docs/configuration.md appears in the metadata with the documented default;
- property names do not change.
