# 132 — Bind cluster properties and refuse an embedded topology without explicit membership

**Repo:** `.`
**Release:** 0.4.1
**Base branch:** `claude/release-0.4.1` (not `main`)
**Depends on:** 131
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java *(the `Hazelcast` nested class and its validation block only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(`ClusterBackedState`, `dataPrismClusterScopeBudget` and a new cluster preflight only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterScopeBudgetConfiguration.java *(delete)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/** *(existing tests: only the properties that start an embedded member, the `INVALID_HAZELCAST_TLS` assertions, and the `ClusterScopeBudgetConfiguration` comment in `SharedReadBudgetTest`)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java *(the arguments added when `embedded` is true only)*

## Goal

Give `dataprism.hazelcast` explicit cluster settings, and translate them through
task 131's `ClusterMembership`. An embedded topology that does not say how its
members find each other now refuses to start, with a stable code. Before, it
silently ran a lone, auto-detecting `dev` member. The unused TLS reference
properties now refuse instead of implying encryption. This follows owner
decision **D-0.4.1-A**, option B; see PLAN.md "Release 0.4.1".

## Shape (property names are fixed; tasks 133 and 135 rely on them)

```yaml
dataprism:
  hazelcast:
    topology: embedded
    cluster-name: prism-prod          # required with embedded; never "dev"
    join:
      mode: tcp-ip                    # tcp-ip | kubernetes | none; required with embedded
      members: [10.0.0.11, 10.0.0.12:5701]   # tcp-ip only
      kubernetes:                     # kubernetes only
        namespace: data-prism
        service-name: data-prism-cluster   # exactly one of service-name / service-dns
        service-dns: data-prism-cluster.data-prism.svc.cluster.local
    member:
      port: 5701                      # default 5701, never auto-incremented
      interface: 10.0.*.*             # optional
```

`join.mode: none` is the explicit single-member choice. The member binds
`127.0.0.1` and never accepts another. It is how a 0.4.0 `embedded` deployment
keeps working with a one-line change; see owner decision **D-0.4.1-B**.

## Context

- DataPrismProperties.java:379-396 is the current Hazelcast validation, and
  :863-935 is the nested `Hazelcast` class.
- DataPrismProperties.java:180-190: the `OPERATOR_PORT_SHARED` check is the
  pattern to follow for a port that must not be shared.
- DataPrismAutoConfiguration.java:526-582 holds the `single-node` budget,
  `ClusterBackedState`, and the `new Config()` at :560. The preflight pattern
  that reads raw `Environment` is at :700-715.
- PLAN.md "Follow-ups from tasks 104 and 123", items 3 and 4:
  - delete the unused `ClusterScopeBudgetConfiguration`;
  - register the budget's destroy-order dependency on `PrivacyCluster`.

  Both concern this bean graph, so they are folded in here.
- Task 131's `ClusterMembership` and its `PrivacyClusterRefusal.code()`.

## Acceptance

- [ ] With `topology: embedded` and no application `PrivacyCluster` bean,
      startup is refused with `DataPrismConfigurationException` as follows.
      Each case has a test in `ClusterConfigurationTest`:
      - no `cluster-name`: `MISSING_CLUSTER_NAME`;
      - `cluster-name: dev` or `DEV`: `RESERVED_CLUSTER_NAME`;
      - no `join.mode`: `MISSING_CLUSTER_JOIN`;
      - an unknown mode: `UNSUPPORTED_CLUSTER_JOIN`;
      - `tcp-ip` with no members: `INVALID_CLUSTER_MEMBERS`;
      - `kubernetes` with no namespace, or with both or neither of
        service-name and service-dns: `INVALID_KUBERNETES_JOIN`;
      - `member.port` equal to `server.port`, `management.server.port` or
        `dataprism.operator.port`: `CLUSTER_PORT_SHARED`.

      `INVALID_CLUSTER_PORT` and `INVALID_CLUSTER_INTERFACE` pass through from
      task 131 with the same code.
- [ ] With `topology: single-node`, any of `cluster-name`, `join.*` or
      `member.*` being set refuses with `CLUSTER_SETTINGS_IGNORED`. Cluster
      settings that would do nothing must not look as if they work.
- [ ] Either `tls-key-reference` or `tls-trust-reference` being set, with any
      topology, refuses with `HAZELCAST_TLS_UNSUPPORTED`. The message names
      network isolation as the alternative. The old `INVALID_HAZELCAST_TLS`
      code is gone from `src/main`. The getters and setters stay, so that
      binding still sees the properties and refuses, rather than Boot
      ignoring them silently.
- [ ] The `dataPrismPrivacyCluster` bean is built from
      `ClusterMembership`, never from `new Config()`.
      `git grep -n 'new Config()' -- data-prism-spring-boot-autoconfigure/src/main`
      finds nothing.
- [ ] An application-supplied `PrivacyCluster` bean:
      - starts without `cluster-name` or `join`;
      - setting them alongside it refuses with `CLUSTER_SETTINGS_IGNORED`;
      - one wrapping an instance with auto-detection on refuses with
        `UNSAFE_HAZELCAST_DISCOVERY`, through task 131's `using` check.
        Applications build that bean with `PrivacyCluster.using`.

      Each case has a test.
- [ ] Two contexts built in one JVM from properties alone each form a
      2-member cluster:
      - `join.mode=tcp-ip`, members on `127.0.0.1`, distinct free ports, a
        unique cluster name.

      A third context with `join.mode=none` and the same cluster name stays
      at 1 member.
- [ ] Every existing autoconfigure test that starts `embedded` now sets a
      unique `cluster-name` and `join.mode=none`, or `tcp-ip` where it needs
      two members.
      `mvn -pl data-prism-spring-boot-autoconfigure -am verify` exits 0.
- [ ] `OperatorHarness`, when `embedded`, passes a unique
      `cluster-name`, `join.mode=none` and a free `member.port`.
      `mvn -pl data-prism-server -am verify` exits 0.
- [ ] `ClusterScopeBudgetConfiguration.java` is deleted. The cluster-backed
      `ScopeBudget` bean declares its dependency on `dataPrismPrivacyCluster`,
      either with `@DependsOn` or as a direct parameter. A test asserts
      `beanFactory.getDependenciesForBean(<budget bean>)` contains it.
- [ ] Refusal messages name the property, never its value. A test plants
      the member `198.51.100.7` and the namespace `secret-ns` in a refused
      configuration and asserts neither string appears in the exception
      message.

## Out of scope

- `docs/configuration.md` and every other doc. That is task 135.
- `server.json` and the Docker files. That is task 133.
- The multi-member behavioural test across pause, approvals, budget, rate
  limit and re-identification. That is task 134.
- The other `DataPrismProperties` sections, such as oversight, operator and
  audit. Task 113 (0.5.0) owns this file next.
- Making `ToolAdmission` or `OversightPolicy` PRIVACY_CRITICAL (PLAN follow-up
  item 2).

## Added from task 131 review (2026-10-06)

- **Stacked on 131.** Branch from `task/131-hazelcast-clustering`, not from
  `claude/release-0.4.1`; 131 and 132 merge together. 131's `toConfig()` is final.
- `join.mode: none` together with `member.interface` must refuse at property
  validation with `INVALID_CLUSTER_INTERFACE`, matching 131's `None.withInterface`
  refusal. Never call `withInterface` on `None`.
- An enabled advanced network config is refused by 131 (`UNSAFE_HAZELCAST_DISCOVERY`).
  Nothing in 132 may enable it.
- With `tcp-ip` or `kubernetes` and no `member.interface`, the member binds every
  interface. 135's docs must say so; 132's property javadoc must too.
- Version note: the project is at 0.4.0, which is published on Central. Always build
  with `-am` and never `-rf`.
