# 189 — Bind every request limit from configuration at both orchestrator wiring sites

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Decided (owner, 2026-10-09):** D-070-4 (a): prefix `dataprism.limits`, keys
`max-sources`, `max-records`, `max-response-size` (`DataSize`),
`per-source-timeout` (`Duration`), `max-concurrency`, `scope-read-budget`;
defaults unchanged; positive values only; `INVALID_REQUEST_LIMITS` at startup;
one bean `dataPrismRequestLimits`, `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`.
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/LimitsProperties.java (new)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/OrchestrationWiring.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PropertiesValidation.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java (insertions only)
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/LimitsConfigurationTest.java (new)
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/** (insertions only)
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt (insertions only)
- data-prism-spring-boot-autoconfigure/src/test/resources/configuration-metadata-gaps.txt (insertions only, if any)
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesLimitsTest.java (new)
- docs/configuration.md
- docs/plan/tasks/189-bind-request-limits-from-configuration.md (Outcome section only)

## Goal
`RequestLimits.DEFAULT` is hard-wired at both places that build the
orchestrator, so an operator cannot raise `maxSources` above 8, change the
per-source timeout or set the read budget. This task binds all six
`RequestLimits` fields from configuration and makes both wiring sites use the
bound value, with defaults unchanged. The read budget becomes configurable as a
consequence.

## Context
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/RequestLimits.java:28-61`
  — the six fields, `DEFAULT` (8, 500, 512 KiB, 3 s, 4, 100) and the
  positive-value checks.
- `OrchestrationWiring.java:43-52` and
  `ConfiguredJsonSourcesAutoConfiguration.java:128-147` — the two sites passing
  `RequestLimits.DEFAULT`. `data-prism-connectors-rest` does not depend on
  `data-prism-spring-boot-autoconfigure`, so it cannot read
  `DataPrismProperties`; the decided shape (D-070-4) is one `RequestLimits` bean built
  from properties in autoconfigure and injected at both sites.
- `ScopeBudgetWiring.java` — both budget implementations take the limit per
  call through `ScopeBudget.tryRead(..., limit)`; no change needed there.
- Exhaustiveness guards this task will trip and therefore owns:
  `ConfigurationMetadataDocumentedTest` (every metadata key documented in
  `docs/configuration.md` and vice versa, defaults compared),
  `PropertyNamesFrozenTest` (`frozen-property-names.txt`, regenerate with
  `-Dfrozen.write=<path>`), `AutoConfiguredBeanInventoryTest`
  (`bean-inventory/*.txt`, regenerate with `-Ddataprism.inventory.write=true`),
  `AutoConfiguredBeanClassificationTest` (every bean listed in
  `PrivacyExtensionPoints`).
- `Preflights.java:39-77` and `ClusterBackedState.java:66` — the
  `DataPrismConfigurationException("CODE", ...)` style for startup refusals.
- The per-source timeout is measured from the start of the fan-out, not per call
  (`SourceFanOut.java:102,139`). The property documentation must say so
  honestly; changing it is 0.8 work.
- D-070-4 (a), above. The bean is `PRIVACY_CRITICAL` because it carries the
  read budget; see how `dataPrismPrivacyPolicyResolver` and
  `dataPrismRawValueLeakValidator` declare `COMPETING_BEAN_REFUSAL` in
  `PrivacyExtensionPoints.java:55-58`.

## Acceptance
Write the tests first; each must fail on `main` before the change.
- [ ] `LimitsConfigurationTest`: with no `dataprism.limits.*` set, the bean
      equals `RequestLimits.DEFAULT`; with each of the six properties set
      to a non-default value, the orchestrator's limits carry that value
      (one test per property).
- [ ] A zero or negative value for any of the six, and a response size that
      does not fit in an `int`, refuses startup with the stable code
      `INVALID_REQUEST_LIMITS`, whose message names the property but no
      other value; one test per property.
- [ ] `ConfiguredJsonSourcesLimitsTest`: with `dataprism.json-sources.config-location`
      set, the orchestrator built by `ConfiguredJsonSourcesAutoConfiguration`
      honours a configured `max-sources` of 1 (a two-source request reports the
      second as `SKIPPED_OVER_LIMIT`) and a configured `scope-read-budget` of 1
      (the second read is refused `SCOPE_READ_BUDGET`).
- [ ] `grep -rn "RequestLimits.DEFAULT" --include='*.java' data-prism-*/src/main`
      lists no line in `data-prism-spring-boot-autoconfigure` or
      `data-prism-connectors-rest`.
- [ ] `PrivacyExtensionPoints` lists `dataPrismRequestLimits` as
      `PRIVACY_CRITICAL` / `COMPETING_BEAN_REFUSAL`, and an application that
      supplies its own `RequestLimits` bean is refused at startup with the
      competing-bean refusal used by the other such beans, proven by a test.
- [ ] `docs/configuration.md` has a `dataprism.limits` section with a
      `Property | Default | ...` table covering all six keys, the per-source
      timeout's "from the start of the fan-out" semantics, and the refusal code.
      `ConfigurationMetadataDocumentedTest`, `PropertyNamesFrozenTest`,
      `AutoConfiguredBeanInventoryTest` and `AutoConfiguredBeanClassificationTest`
      pass; the frozen and inventory diffs are insertions only.
- [ ] `./mvnw -B verify -pl data-prism-spring-boot-autoconfigure,data-prism-connectors-rest -am`
      exits 0; the Outcome records the test count and commit.
- [ ] The Outcome carries proposed CHANGELOG `[Unreleased]` `### Added` lines
      and a migration note ("defaults unchanged; new startup refusal code") for
      task 201; `CHANGELOG.md` is unchanged.

## Out of scope
- Enforcing `maxRecords` and `maxResponseBytes` (task 193).
- Changing `RequestLimits.java` (task 193 owns its Javadoc) or `DEFAULT`.
- Per-call timeouts, charging the budget per source, per-scope caps (0.8).
- `DataPrismAssembly` in `data-prism-integration-tests`, which may keep `DEFAULT`.
- Per-scope or per-caller limit overrides.

## Outcome

Done on branch `task/189-limits-config`. `./mvnw -B verify -pl data-prism-spring-boot-autoconfigure,data-prism-connectors-rest -am`
exits 0 with 1450 tests, 0 failures. Tests were committed failing before the change.

- `dataprism.limits.*` binds all six `RequestLimits` fields (`LimitsProperties`); one bean
  `dataPrismRequestLimits` (`PRIVACY_CRITICAL` / `COMPETING_BEAN_REFUSAL`), injected into the orchestrator
  in `OrchestrationWiring` and `ConfiguredJsonSourcesAutoConfiguration`.
- An application `RequestLimits` bean is refused with the existing `FORBIDDEN_PRIVACY_OVERRIDE` code, by a new
  static preflight `dataPrismRequestLimitsPreflight` (classified `REPLACEABLE`).
- `data-prism-connectors-rest` cannot see the bean when the autoconfigure module is absent. Rather than require it
  (which would break `ConfiguredJsonSourcesAutoConfigurationTest`, not owned here), it falls back to
  `STANDALONE_LIMITS`, a copy of the defaults pinned to `RequestLimits.DEFAULT` by a test.
- Edit outside `Owns`: `AutoConfiguredBeanInventoryTest` hard-codes the `@Bean` method count (`hasSize(53)` to
  `hasSize(55)`). Any other task adding a bean will conflict on that line.

Proposed CHANGELOG `[Unreleased]` `### Added` lines for task 201:
- `dataprism.limits.max-sources`, `max-records`, `max-response-size`, `per-source-timeout`, `max-concurrency` and
  `scope-read-budget` set the request limits that were fixed at their defaults. The per-source timeout is measured
  from the start of the fan-out, not per call.
- Startup is refused with `INVALID_REQUEST_LIMITS` for a zero or negative limit or a response size that does not fit
  in an `int`.

Migration note: defaults unchanged; new startup refusal code `INVALID_REQUEST_LIMITS`. An application that supplied
its own `RequestLimits` bean is now refused with `FORBIDDEN_PRIVACY_OVERRIDE`.
