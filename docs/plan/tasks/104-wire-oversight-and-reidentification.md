# 104 — Wire oversight, re-identification and operator-surface configuration

**Repo:** `.`
**Depends on:** 99, 100, 101, 103
**Owns:**
- data-prism-spring-boot-autoconfigure/pom.xml
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/OversightConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ReidentificationConfigurationTest.java *(new)*
- docs/configuration.md *(new `dataprism.oversight`, `dataprism.reidentification`, `dataprism.operator` rows and sections only)*

## Goal

Turn tasks 98-101 from library code into deployed behaviour. Three
vocabularies are bound and validated: `dataprism.oversight.*`,
`dataprism.reidentification.*` and `dataprism.operator.*`. The task chooses
Hazelcast-backed oversight state when a cluster is configured, and in-memory
state otherwise. It also passes a real `ToolAdmission` and
`ParameterFingerprinter` into `DataPrismMcpServer`. Task 105 depends on
these properties, so their shape is fixed here.

## Context

- `DataPrismProperties.java:208-223` — the `hazelcast` validation, including
  `reidentificationEnabled` and `MISSING_REIDENTIFICATION_CONTROLS`.
  `:600-680` is the `Hazelcast` nested class.
- `ClusterScopeBudgetConfiguration.java` and `docs/configuration.md` show
  the precedent for the choice: shared state when topology is `embedded`,
  and `MISSING_SHARED_BUDGET` otherwise.
- `DataPrismAutoConfiguration.java:386` builds `dataPrismAuthorizationService`.
  The `DataPrismMcpServer` bean is built here too.
- Task 101's new `DataPrismMcpServer` overloads. Task 98's `OversightPolicy`
  and `ToolAdmission`. Task 99's Hazelcast implementations. Task 100's
  `ReidentificationPolicy` and `ReidentificationService`.

## Acceptance

- [ ] The following properties are bound:
      - `dataprism.oversight.approval-required-tools` (list)
      - `dataprism.oversight.approval-ttl` (default `PT15M`)
      - `dataprism.oversight.caller-rate-limit.requests` (optional int)
      - `dataprism.oversight.caller-rate-limit.window` (default `PT1M`)
      - `dataprism.reidentification.enabled` (default `false`)
      - `dataprism.reidentification.purposes`
      - `dataprism.reidentification.roles` (map of role to `REQUEST`/`APPROVE`)
      - `dataprism.reidentification.four-eyes` (default `true`)
      - `dataprism.reidentification.approval-ttl` (default `PT15M`)
      - `dataprism.operator.enabled` (default `false`)
      - `dataprism.operator.port`
      - `dataprism.operator.required-audience`
      - `dataprism.operator.required-scope`
- [ ] One test per row asserts each refusal code:

      | Condition | Code |
      |---|---|
      | an approval-required tool name that is neither `get_entity_context` nor `compare_entity_sources` | `UNKNOWN_OVERSIGHT_TOOL` |
      | non-positive rate limit, window or TTL | `INVALID_OVERSIGHT_LIMIT` |
      | re-identification enabled with `dataprism.hazelcast.reidentification-enabled=false` | `REIDENTIFICATION_INDEX_DISABLED` |
      | re-identification enabled with empty `purposes` | `EMPTY_REIDENTIFICATION_PURPOSES` |
      | re-identification enabled with no role holding `APPROVE` while `four-eyes=true` | `NO_REIDENTIFICATION_APPROVER` |
      | re-identification enabled without `operator.enabled` | `REIDENTIFICATION_REQUIRES_OPERATOR_SURFACE` |
      | `operator.enabled` without `port`, `required-audience` or `required-scope` | `MISSING_OPERATOR_SECURITY` |
      | `operator.port` equal to `server.port` | `OPERATOR_PORT_SHARED` |
      | approval-required tools or a rate limit configured without `operator.enabled` | `OVERSIGHT_REQUIRES_OPERATOR_SURFACE` |
- [ ] With `dataprism.hazelcast.topology=embedded`, the `OversightState`,
      `ApprovalStore` and `CallerRateLimiter` beans are the Hazelcast
      implementations. Otherwise they are the in-memory ones. A test asserts
      each case.
- [ ] The `DataPrismMcpServer` bean is built with a `ToolAdmission`
      constructed from the bound `OversightPolicy`. A context test with
      `approval-required-tools: [get_entity_context]` proves that a call
      returns `APPROVAL_REQUIRED`.
- [ ] A `ReidentificationService` bean exists only when
      `dataprism.reidentification.enabled=true`. No bean in the context
      exposes it as an MCP tool, and a test asserts that `tools/list` is
      unchanged.
- [ ] Every test from task 103 and all pre-existing autoconfigure tests pass
      unchanged.
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests -am verify`
      passes.
- [ ] `docs/configuration.md` documents every property, default and code
      above.

## Out of scope

- The HTTP endpoints and the second port. That is task 105.
- `MISSING_REIDENTIFICATION_CONTROLS` and
  `reidentification-controls-reference`, which keep their current meaning.
- `connectors-rest`'s own auto-configuration.

## Note from task 98 (merged)

`ToolAdmission` takes a `Clock` as its fifth constructor argument, so the
wiring must supply one. Approval-required calls consume a rate-limit token
before the `APPROVAL_REQUIRED`/`APPROVAL_PENDING` refusal. Task 101 owns the
decision on whether that is intended; do not set a tight caller limit here
until it is settled.

## Note from task 100's review (owner decision D8)

`ReidentificationPolicy.fourEyes` is a primitive with no library default. This
task's property binding must default `dataprism.reidentification.four-eyes` to
`true`, with a test asserting the default. Add it to acceptance.
