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

## Attempt 1 — failed on review and test (2026-09-23)

The allow-list LOGIC is correct and fail-closed — keep it. An empty provider
leaves `reviewed = configured`, so an adapter named by neither mechanism is
still refused, and a `dataprism.sources`-only deployment is not falsely
refused. Both directions are tested, and mutation proof confirmed the negative
test is load-bearing (`if (false && !reviewed.contains(name))` turns it red).
The dead `MISSING_AUDIT_SINK` arm decision — kept as a defensive guard with an
accurate "unreachable today" comment — was also judged correct. None of that
needs redoing.

WHAT FAILED: the mechanism for getting the catalogue's source names into the
validator.

`DataPrismContractValidator` takes `ObjectProvider<ConfiguredJsonSourceNames>`
in unconditional `@Bean` method signatures on the main auto-configuration
class, backed by a new `<optional>true</optional>` Maven dependency from
`data-prism-spring-boot-autoconfigure` to `data-prism-connectors-rest`.

`data-prism-server/pom.xml:56-62` declares `connectors-rest` at TEST SCOPE
ONLY — the base distribution deliberately never compiles against a connector;
it is opted into at runtime via `-Dloader.path`. So in the packaged server the
class is genuinely absent, and startup crashes:

    java.lang.TypeNotPresentException: Type
      io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonSourceNames
      not present
    -> ClassNotFoundException

`<optional>true</optional>` does not help: the parameter type is compiled into
the class file, and Spring's autowire-candidate resolution calls
`Method.getGenericParameterTypes()`, which resolves every type argument via
`Class.forName` BEFORE `getIfAvailable()` is ever reached. There is no empty
provider — there is a hard failure at context refresh.

Blast radius is every deployment that does not bundle `connectors-rest`, not
just ones using the JSON catalogue. Reproduced by `mvn -pl data-prism-server
-am verify`:
- `ServerPackagingIT.executableLoadsAReviewedAdapterExtensionFromLoaderPath`
  (:223) — a REVIEWED adapter via `-Dloader.path`, no catalogue, no connector
  jar — server never binds Tomcat.
- `ConfigurationRefusalMessageIT.packagedServerRefusesWithNoSourceAdapterConfigured`
  (:108) — crashes instead of printing its clean refusal.

Unit tests pass 22/22 because `connectors-rest` is on the autoconfigure test
classpath. `mvn test` cannot see this defect; only `mvn verify` can.

WHY THE CITED PRECEDENT ARGUES THE OPPOSITE: `DataPrismAutoConfiguration:382-392`
states that `@ConditionalOnClass(HazelcastInstance.class)` plus confining the
optional type to `ClusterScopeBudgetConfiguration` is what lets the method go
unresolved when the jar is absent, with `dataPrismSharedBudgetPreflight`
turning that silence into a coded refusal. Task 69 has no `@ConditionalOnClass`,
no confinement, and no preflight.

THE FIX: the validator only needs a `Set<String>`. Publish a type owned by
`core` or `autoconfigure` that `connectors-rest` registers, so autoconfigure
has no compile dependency on the connector at all — the correct direction for a
wiring leaf that should see adapters only through `core`. That also removes the
out-of-Owns pom edit.

Note `data-prism-architecture`'s ArchUnit rules cover core/pseudonymisation/
validation/orchestration but NOT connectors, so no rule caught the dependency
direction. The green build was silence, not approval.
