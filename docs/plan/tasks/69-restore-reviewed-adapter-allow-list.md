# 69 — Restore the reviewed-adapter allow-list without reinstating the duplication

**Repo:** .
**Depends on:** 60, 67
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidatorTest.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceNames.java (new)
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfigurationTest.java

## Goal
Task 54 relaxed the validator from `supplied.equals(configured)` to
`supplied.containsAll(configured)` so a configured JSON source need not be
duplicated under `dataprism.sources`. That also lost the allow-list property:
any `DataSourceAdapter` bean on the classpath is now implicitly approved without
appearing anywhere an operator reviewed. Narrow the exemption to exactly the
names the JSON-catalogue mechanism supplies.

## Context
- `DataPrismContractValidator.java:27-38` — the subset check and the comment
  explaining why a configured JSON source legitimately outgrows `configured`.
- `DataPrismAutoConfiguration.java:135-140` — `dataPrismPropertiesValidated`, which
  collects `List<DataSourceAdapter<?>>` and calls `validateIntegrations`; the
  catalogue-names bean reaches the validator from here.
- `ConfiguredJsonSourcesInitializer.java:35-60` — where configured adapters are
  registered as singletons, ahead of context refresh.
- `ConfiguredJsonSource` as task 60 leaves it — the source record the declared
  names are read off.
- `docs/plan/PLAN.md`, "Small open items, unscheduled" (2026-09-21, 54's review) —
  the finding, the owner-chosen narrow fix, and the warning not to repeat task 54's
  "guarantee-preserving" framing.

## Acceptance
- [ ] The JSON-catalogue mechanism publishes the set of source names it supplies
      through a bean registered by `ConfiguredJsonSourcesAutoConfiguration` (or the
      initializer beside it), derived from the parsed catalogue rather than restated
      by hand, and visible to `data-prism-spring-boot-autoconfigure`.
- [ ] `DataPrismAutoConfiguration` reaches it only by adding a parameter — an
      `ObjectProvider` of that type — to the existing `dataPrismPropertiesValidated`
      method. No new `@Bean` method is declared there, so no new classification row
      is needed and task 68's sweep stays green unchanged.
- [ ] `validateIntegrations` refuses startup when a `DataSourceAdapter` bean's
      `sourceName()` appears neither in `dataprism.sources` nor in that published
      set, with a stable code and a message naming the unreviewed adapter name.
- [ ] A configured JSON source still starts with no matching `dataprism.sources`
      entry — task 54's removal of the duplication is not reinstated, asserted by a
      test that configures only `json-sources:` and reaches a running context.
- [ ] A `DataSourceAdapter` bean named by neither mechanism fails startup, asserted
      with the refusal code. This is the case today's subset check lets through and
      the reason the task exists.
- [ ] The existing `UNRESOLVED_SOURCE_ADAPTER` case — a `dataprism.sources` entry
      with no adapter bean — still refuses, unchanged.
- [ ] `DataPrismContractValidatorTest.java:47` and `:78`, byte-identical bodies
      asserting one behaviour under two names, are reduced to one test or made to
      assert genuinely different behaviours.
- [ ] Neither the commit message nor any comment describes this change as
      guarantee-preserving; it restores a property that was lost.
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure,data-prism-connectors-rest -am test` passes.

## Out of scope
- `DataPrismProperties.java` and `PrivacyExtensionPoints.java` (tasks 67 and 68).
  Declaring a new `@Bean` in `DataPrismAutoConfiguration` is therefore out of
  scope: the names bean belongs to the connector module.
- Re-introducing a required `dataprism.sources` entry per configured JSON source.
- The nested-catalogue grammar (task 60).
