# 212 — Wire the isolated-network join and the cluster subject key into configuration

Renumbered from 197 on 2026-10-19 (id collision with the 0.7.0 plan). Scope not yet reconciled with docs/plan/roadmap.md.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` once 204, 205 and 211 have merged
**Depends on:** 204, 205, 211 (211 only because both edit the shared Spring files below)
**Serialised with:** 209, 210, 211
**Blocked on:**
- **D-1.0-7 (open).** Where the subject-key HMAC key comes from. Option (a): derive it from the
  pseudonymisation key with a separate, versioned label, so no new property is added. Option (b): a new
  key reference, `dataprism.hazelcast.subject-key.*`, distinct from the pseudonymisation key. Record the
  owner's answer and date here before starting.
- **D-1.0-3 (open), through 205.** If 205 implemented "encrypt", this task also wires that key.

**Decided (owner, 2026-10-15), D-1.0-2:** no network join by default; multi-member only through an
explicitly named isolated-network option. On `main` the first half already holds: without `topology` no
member starts, `embedded` refuses an unset `join.mode` (`MISSING_CLUSTER_JOIN`, D-0.4.1-B), and
`join.mode: none` is a single member bound to loopback. This task keeps that refusal rather than
defaulting `join.mode` to `none`, because D-0.4.1-B rejected a silent single-member default: N replicas
would each enforce their own read budget. If the owner reads D-1.0-2 as "default to `none`", change
only that acceptance item.

**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(deleting the overloads without a subject key only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/** *(call sites of the deleted overloads only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ReidentificationWiring.java *(only if 205 implemented "encrypt")*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validateCluster` and the hazelcast checks, :229-300 and :490-492, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only, if a bean is added)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/SharedReadBudgetTest.java *(cluster properties only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterSubjectKeyConfigurationTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(cluster configuration arguments only)*
- docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(cluster settings only)*
- server.json *(the cluster environment-variable entries only)*
- docs/configuration.md *(the `dataprism.hazelcast` rows and the prose around them only)*

## Goal
Expose task 204's isolated-network declaration and subject-key HMAC through `dataprism.hazelcast.*`, and
remove the temporary overloads without a key that 204 left. After this task, no code path writes a raw
subject id into an identity or budget map key, and a multi-member join starts only when the deployer has
stated that the member port is on an isolated network.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java:36-80 — the member bean and the join translation 204 touched
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java:9-160 — `join.*`, `member.*` and the inert-but-refused `tls-*-reference`
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java:229-300 — `validateCluster`, `MISSING_CLUSTER_JOIN` at :245
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java — the overloads with and without `SubjectKeyHmac` after 204
- docs/plan/tasks/retired/132-*.md — the precedent for this file set and the property shape
- docker/multi-instance/compose.yaml:70-107 and kubernetes.yaml:80-110 — the examples that set `JOIN_MODE` today

## Acceptance
- [ ] `dataprism.hazelcast.join.isolated-network` binds as a boolean with default `false`. `join.mode: tcp-ip` or `kubernetes` without it set to `true` refuses startup with `UNSECURED_CLUSTER_JOIN`. Setting it with `join.mode: none` refuses with `ISOLATED_NETWORK_WITHOUT_JOIN`. `ClusterConfigurationTest` covers all three and the accepted case for each mode.
- [ ] `embedded` with `join.mode` unset still refuses with `MISSING_CLUSTER_JOIN` (see the D-1.0-2 note above).
- [ ] `dataprism.hazelcast.tls-key-reference` and `tls-trust-reference` still refuse with `HAZELCAST_TLS_UNSUPPORTED`, consistent with the jar check 204 recorded.
- [ ] The member is built with a `SubjectKeyHmac` whose key comes from the D-1.0-7 answer. Under (a), the derivation uses a versioned label that appears in the code once, and a test proves the derived key differs from the pseudonymisation key. Under (b), the new key reference follows the `hmac-key` shape with no inline-value property, refuses when unresolved (`CLUSTER_SUBJECT_KEY_UNRESOLVED`), and refuses when equal to the pseudonymisation key (`CLUSTER_KEY_REUSES_PSEUDONYMISATION_KEY`).
- [ ] An application `PrivacyCluster` bean built without a subject key cannot exist, because the overloads without a key are deleted. `rg -n 'ScopeKeys\.(identity|budget)\(' data-prism-hazelcast/src/main` shows no call that passes a raw subject id.
- [ ] `ClusterSubjectKeyConfigurationTest` starts an embedded member through the properties, performs a read, and asserts that no key or value in `IDENTITY_MAP` or `BUDGET_MAP` contains the fixture subject id.
- [ ] If 205 implemented "encrypt", the re-identification key is wired the same way, enabling the index without it refuses with `MISSING_REIDENTIFICATION_KEY`, and a test covers both.
- [ ] `docker/multi-instance/compose.yaml` and `kubernetes.yaml` set `DATAPRISM_HAZELCAST_JOIN_ISOLATEDNETWORK: "true"`, with a comment saying that this is the deployer's statement that port 5701 is reachable only by members. Under (b) they also set the subject-key reference. `server.json` lists any new environment variable.
- [ ] `docs/configuration.md` documents each new property, default and refusal code, and says that every member must share the subject key or the second member refuses with `CLUSTER_SUBJECT_KEY_MISMATCH`.
- [ ] `ConfigurationMetadataDocumentedTest`, `PropertyNamesFrozenTest`, `AutoConfiguredBeanClassificationTest` and `AutoConfiguredBeanInventoryTest` pass. The diffs to their checked-in lists contain only this task's lines.
- [ ] `./mvnw -B verify` (full reactor) passes, including the multi-member server tests under `data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/`.

## Out of scope
- Hazelcast Enterprise, member TLS and member authentication.
- Defaulting `join.mode` to `none` (unless the owner overrides the note above).
- Encrypting identity or budget values, signed approvals, and the pause document.
- `docs/multiple-instances.md`, `docs/architecture.md` and every `docs/` file other than `docs/configuration.md` (task 213).
