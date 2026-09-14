# 25 — Wire the shared read budget, and make the topology an explicit choice

**Repo:** `.`
**Depends on:** 21, 22, 24
**Owns:**
- data-prism-spring-boot-autoconfigure/pom.xml
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterScopeBudgetConfiguration.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/SharedReadBudgetTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java *(insertions only: the classification rows for the beans this task adds)*
- data-prism-example/src/main/resources/application.yaml
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java *(the configuration arguments only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java *(the configuration arguments only)*
- docs/configuration.md *(the `dataprism.hazelcast` row and its surrounding prose only)*

## Goal

`dataprism.hazelcast.*` is bound and validated but read by nothing: the
auto-configuration always supplies `InMemoryScopeBudget`, so a two-member
deployment enforces the read budget once per process and a budget of 100 means
200. `HazelcastScopeBudget` exists, fails closed, and is tested — it is simply
not wired. Wire it for the embedded topology, and make a per-process budget a
named choice an operator has to make rather than the silent default.

## Context

- `DataPrismAutoConfiguration.java:153-156` — the `ScopeBudget` bean, always
  `InMemoryScopeBudget`.
- `DataPrismProperties.java:54-58` — `Hazelcast`, defaulting `topology` to
  `embedded`, and `:57` refusing anything else.
- `HazelcastScopeBudget.java:10-23` — the javadoc that already states exactly
  this defect: "Held per instance, a budget of a hundred lets a caller spread
  across eight instances read a subject eight hundred times."
- `PrivacyCluster.java:56,62` — `embedded(Config, boolean)` and
  `using(HazelcastInstance, boolean)`, the two construction routes.
- `docs/configuration.md:63` — "Embedded topology is the supported V1 topology",
  which today describes a property nothing reads.
- `docs/architecture.md:41,230-233` — the `hazelcast` module owns the "shared
  read budget", and the decision that the budget fails closed while the identity
  cache fails open. Both remain true; this task makes the first one reachable.
- `data-prism-example/src/main/resources/application.yaml:48-50` — declares
  `topology: embedded` while the module has no Hazelcast on its classpath: the
  defect, visible in shipped configuration.
- Baseline: `main` at `99b419b`, 377 tests, 0 failures, plus whatever tasks 21,
  22 and 24 add — re-measure on the merged base before quoting a total.

## Acceptance

- [ ] `dataprism.hazelcast.topology` accepts `embedded` and `single-node`, and
      is required for a protected HTTP deployment: an absent value refuses
      startup with stable code `MISSING_CLUSTER_TOPOLOGY` rather than defaulting.
- [ ] `topology: embedded` produces a `ScopeBudget` bean that is
      `HazelcastScopeBudget` over a `PrivacyCluster` built from the bound
      `dataprism.hazelcast` settings, and honours `identity-cache-ttl` and
      `reidentification-enabled`. A test asserts the bean type.
- [ ] `topology: embedded` with `data-prism-hazelcast` absent from the classpath
      refuses startup with stable code `MISSING_SHARED_BUDGET`; a test proves
      this with a filtered classloader, so the absent-dependency case cannot
      fall back to a per-process budget.
- [ ] `topology: single-node` produces `InMemoryScopeBudget`, and the property
      documentation states in one sentence that the budget is then enforced per
      process and is multiplied by the number of processes.
- [ ] The `dataprism.hazelcast` row in `docs/configuration.md` describes both
      values, which one is required for a multi-instance deployment, and the
      refusal codes above. No claim about shared enforcement is left standing
      for a configuration that does not share.
- [ ] `data-prism-example` declares its topology explicitly, and the close-out
      says which value it chose and why.
- [ ] Every new `@Bean` this task declares has a row in the classification list
      from task 22, inserted rather than reordered.
- [ ] The `data-prism-hazelcast` dependency of
      `data-prism-spring-boot-autoconfigure` is optional, so a `single-node`
      consumer does not take Hazelcast onto its classpath; a test or the
      dependency report evidences that it is not transitive through the starter.
- [ ] `mvn -B verify` is green; the test total is reported against the
      re-measured base.

## Out of scope

- Client-server Hazelcast topology, persistence or MapStore — still refused.
- The identity cache and the re-identification index: this task wires the
  budget, and touches `CachingSyntheticValueSource` only if the same
  `PrivacyCluster` bean makes it free. If it does not, say so and leave it.
- `docs/configuration.md`'s source-configuration sections, which task 20 owns.
- Changing `HazelcastScopeBudget` or anything else in `data-prism-hazelcast`.
