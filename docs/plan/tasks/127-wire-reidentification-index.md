# 127 — Wire the re-identification index so a deployed app can resolve pseudonyms

**Repo:** `.`
**Depends on:** 105
*(105: both edit `DataPrismAutoConfiguration` and `PrivacyExtensionPoints`, and this task rewrites the
index-feeding helper in 105's `ReidentificationOperatorTest`.)*
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(the `ReidentificationWiring` nested class, and the `ClusterBackedState` nested class only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only: one row per new auto-configured bean)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ReidentificationConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ReidentificationIndexWiringTest.java *(new)*
- data-prism-server/pom.xml *(test-scope additions only, if any are needed)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ReidentificationOperatorTest.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java *(additive only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ReidentificationEndToEndTest.java *(new)*
- docs/reidentification.md *(the configuration / "How the index is fed" text only, not 105's HTTP section)*

## Goal

Nothing in `DataPrismAutoConfiguration` wraps the application's `SyntheticValueSource` in
`CachingSyntheticValueSource`, so the Hazelcast reverse map read by `ScopeIdentityIndex` is never
written and every operator re-identification in a deployed app returns
`REIDENTIFICATION_NOT_FOUND`. Task 105's tests pass only because they wrap the generator by hand.
This task makes the pseudonymisation path feed the reverse index when, and only when,
`dataprism.reidentification.enabled=true` with `embedded` topology.

## Context

- `DataPrismAutoConfiguration.java:367-368` (task 105's branch) — `dataPrismSyntheticValueSource`,
  `@ConditionalOnMissingBean`, `REPLACEABLE` in `PrivacyExtensionPoints.java:52`. A `@Bean` of the
  same type in a nested class is registered first and would make this one back off, so a plain
  competing bean is the wrong shape; decorate the resolved bean instead (for example a
  `BeanPostProcessor` declared in `ReidentificationWiring`), see decision D-127 below.
- `DataPrismAutoConfiguration.java:553-583` — `ClusterBackedState`, the one `PrivacyCluster` member.
- `DataPrismAutoConfiguration.java:604-628` — `ReidentificationWiring`, which builds the reader
  (`ScopeIdentityIndex`) but nothing that writes.
- `data-prism-hazelcast/.../CachingSyntheticValueSource.java:60-140` — writes the identity map and,
  when `cluster.reidentificationEnabled()`, the reverse map; falls back to computation on any
  cluster failure (determinism without the cache is a hard rule, `docs/conventions.md`).
- `DataPrismProperties.java:213-220` — `dataprism.reidentification.enabled` already refuses
  without `dataprism.hazelcast.reidentification-enabled=true` and `embedded` topology.
- `data-prism-hazelcast/.../HazelcastStoredValueBoundaryTest.java` — the reverse map holds subject
  ids only; every key must contain the generator's output for its stored subject.
- `data-prism-server/.../operator/ReidentificationOperatorTest.java:50-60` — the hand-wrapping to
  remove.
- `data-prism-spring-boot-autoconfigure/.../AutoConfiguredBeanClassificationTest.java` — the
  exhaustiveness guard over `PrivacyExtensionPoints`.

## Acceptance

- [ ] With `dataprism.reidentification.enabled=true`, `dataprism.hazelcast.reidentification-enabled=true`
      and `dataprism.hazelcast.topology=embedded`, the `SyntheticValueSource` injected into every
      `ScrubbingEngine` bean (the core `JsonTreeScrubbingEngine` and the configured-JSON engine) is a
      `CachingSyntheticValueSource` over the context's `PrivacyCluster`. `ReidentificationIndexWiringTest`
      asserts this, and that a value produced through it is then returned by
      `ReidentificationService` as `RESOLVED` with the fixture subject id.
- [ ] With `dataprism.reidentification.enabled=false` (including when
      `dataprism.hazelcast.reidentification-enabled=true` and topology is `embedded`), the
      `SyntheticValueSource` bean is not a `CachingSyntheticValueSource`, and after a scrub that
      produces a pseudonym the `PrivacyCluster.REIDENTIFICATION_MAP` map has size 0. A test asserts both.
- [ ] With `single-node` topology the wiring does not load, and no Hazelcast class is required on
      the classpath. Existing `ReidentificationConfigurationTest` cases still pass.
- [ ] The source is never wrapped twice: an application-supplied `SyntheticValueSource` that is
      already a `CachingSyntheticValueSource` is left as is. A test asserts it.
- [ ] Every new auto-configured bean has a row in `PrivacyExtensionPoints`, and
      `AutoConfiguredBeanClassificationTest` passes.
- [ ] `ReidentificationEndToEndTest` (new, `data-prism-server`) runs the real
      `DataPrismServerApplication` on embedded ports with fixture sources and re-identification
      enabled, and in one test: (1) an MCP `get_entity_context` call returns a pseudonym for a
      synthetic fixture subject; (2) an operator `POST /operator/reidentifications` for that
      pseudonym, its scope `case:<caseId>` and namespace returns pending; (3) a second operator
      principal approves it; (4) `GET /operator/reidentifications/{id}` by the requester returns
      `RESOLVED` with the fixture subject id. The test takes the pseudonym from the tool result, not
      from a generator call.
- [ ] `ReidentificationOperatorTest` no longer constructs `CachingSyntheticValueSource`;
      `grep -n CachingSyntheticValueSource data-prism-server/src/test` returns nothing.
- [ ] `HazelcastStoredValueBoundaryTest` and `ArchitectureTest` pass unchanged (no edit to either file).
- [ ] `docs/reidentification.md` states that the index is fed automatically when re-identification
      is enabled, that values produced while it was disabled are not re-identifiable, and that a
      cluster write failure leaves the value unresolvable (`REIDENTIFICATION_NOT_FOUND`), never
      resolved to a different subject. It uses "supports", never "compliant" or "tamper-proof".
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure,data-prism-server -am verify` passes.

## Out of scope

- Wiring the forward identity cache for `embedded` topology when re-identification is disabled.
  File a follow-up if wanted.
- `CachingSyntheticValueSource`, `ScopeIdentityIndex`, `PrivacyCluster` and
  `HazelcastStoredValueBoundaryTest` themselves (`data-prism-hazelcast/**`).
- `ReidentificationService` and the operator controllers (`data-prism-reidentification/**`,
  `data-prism-server/src/main/**`).
- `dataprism.correlation` and audit-output wiring in `DataPrismAutoConfiguration` (task 113).
- `docs/architecture.md`, `docs/eu-ai-act.md` (task 106).
- Refusal-code validation in MCP text (task 128).

## Decision D-127 (needs the owner)

When re-identification is enabled and the application supplies its own `SyntheticValueSource`
(it is `REPLACEABLE`), either (a) wrap it in `CachingSyntheticValueSource` like the default, or
(b) refuse startup with `REIDENTIFICATION_INDEX_UNWIRED`. Recommended: (a), since the wrapper only
adds a cache plus reverse-index writes and falls back to the wrapped source on any failure. If the
owner picks (b), add one acceptance test for the refusal code and its `DataPrismConfigurationFailureAnalyzer`
classification (then also own that file, insertions only).

## Owner decision (2026-10-06): D-127

Option (a): when re-identification is on, an application-supplied
SyntheticValueSource is wrapped like the default one, and the wrapper falls
back to the wrapped source on any cache failure. There is no
REIDENTIFICATION_INDEX_UNWIRED refusal.
