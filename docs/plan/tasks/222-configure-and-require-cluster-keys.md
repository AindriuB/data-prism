# 222 — Configure the cluster and name keys, and refuse a multi-member join without them

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 220, 221 and 211.
**Depends on:** 220 (`ClusterKeys`), 221 (`PrivacyCluster` configuration and the interface codes), 211 (serialised with 210, 211 and 208 on the shared Spring files: `DataPrismPropertiesValidator.java`, `frozen-property-names.txt`, `additional-spring-configuration-metadata.json`, `docs/configuration.md`, the multi-instance deployment files and the server tests)
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(factory overloads taking `ClusterKeys`, holding the keys, and the keyless multi-member refusal only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java *(insertions only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/TestMembers.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/**/*.java *(the `PrivacyCluster.embedded(` and `PrivacyCluster.using(` call sites only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ClusterKeyRefusalTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java *(new `security.cluster-key`, `.next`, `.previous` and `security.name-key` groups only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the `PrivacyCluster` bean method only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterKeyResolution.java *(new; name may differ)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validateCluster` and methods it calls only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterKeyConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java *(existing tests: the cluster-key and name-key properties of contexts that start a `tcp-ip` or `kubernetes` join, and `member.interface` where 221 now requires it, only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(cluster-key, name-key and member-interface arguments only)*
- docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(cluster-key and name-key settings only)*
- docs/configuration.md *(new rows for `dataprism.hazelcast.security.cluster-key.*` and `.name-key.*`, the startup codes this task adds, and the `member.interface` row only)*

## Goal
Every multi-member Data Prism cluster gets two configured secrets, resolved through `SecretKeyProvider` the same way as the privacy HMAC key: a cluster key (with optional `next` and `previous` for rotation) and a separate name key. A `tcp-ip` or `kubernetes` join without them is refused, both by the Spring starter and by the plain-Java `PrivacyCluster` factories, so no isolation-only cluster is possible (cluster-security OD-2 (i), OD-200-9). A solo embedded member (`join.mode: none`) is a real Hazelcast member: the keys are optional there (OD-215-8 (a)), and when supplied they are checked exactly as on a joining member. `single-node` runs no Hazelcast member, so the keys mean nothing there and are refused. This lands before any store uses the keys. That way the keys every member must share already reach every member, including the multi-member server tests, when task 223 starts deriving map names from them.

## Context
- docs/plan/design/cluster-security.md 4.1 and 4.2 as amended by 218. There is no `security.mode` (OD-200-9). The key-failure codes are those decided under OD-215-3. Literal `value` properties are refused like `dataprism.privacy.hmac-key.value`.
- DataPrismPropertiesValidator.java:229-305 (`validateCluster`) and :463-470 (the HMAC key reference and `LITERAL_SECRET_FORBIDDEN` pattern); PropertiesValidation.java:129-132 (the 32-byte strength rule and `HMAC_KEY_UNRESOLVED`).
- ClusterBackedState.java:47-60: where the `PrivacyCluster` bean is built.
- docker/multi-instance/kubernetes.yaml:104-111: how the privacy HMAC key reaches a member. Use the same pattern, with placeholder secret names.
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/ClusterMembers.java, MultiMemberOperatorTest.java: real multi-member servers, which must get one shared set of test keys.
- OD-200-14: typed, Spring-bound properties, pinned in the frozen list and documented, with refusals at startup.
- Task 221 Outcome: the exact `member.interface` codes.
- From 221 (2026-10-21): map `MISSING_MEMBER_INTERFACE` and `UNBOUNDED_MEMBER_INTERFACE` in `DataPrismPropertiesValidator`. Wire `Metric.CLUSTER_LEGACY_ENTRIES` to 221's package-private `legacyEntries()` count when you edit the `PrivacyCluster` wiring. `UNSAFE_HAZELCAST_SERIALIZATION` now also covers the REST, memcache, Management Center and phone-home refusals; rename it or document that in the register.

## Acceptance
- [ ] T8 (this task's share), in `ClusterKeyConfigurationTest`, one table-driven test, one row per code. With `embedded` and `tcp-ip` or `kubernetes`, the configurations are refused in this order: no cluster key or no name key (`MISSING_CLUSTER_KEY`); a literal `value` (`LITERAL_SECRET_FORBIDDEN`); a reference that does not resolve, a key under 32 bytes, or a cluster or name key equal to the privacy HMAC key (or to the audit or link key when one is configured); a name key equal to a cluster key; `current`, `next` and `previous` ids not pairwise distinct. Each of those is refused with the code 218 registered for it (OD-215-3). The missing and unbounded `member.interface` cases give 221's codes. Every refusal message names the property and never its value.
- [ ] `embedded` with `join.mode: none` (a solo member, OD-215-8 (a)): the cluster and name keys are optional. When any of them is supplied, the same table runs a second time on `none` with the same codes in the same order, except that absence is not a refusal: a literal `value` (`LITERAL_SECRET_FORBIDDEN`), an unresolved reference, a key under 32 bytes, a key shared with the privacy HMAC, audit or link key, a name key equal to a cluster key, and non-distinct `current`, `next` and `previous` ids are each refused with the code 218 registered. Keys are both-or-neither: a cluster key without a name key, a name key without a cluster key, or `next` or `previous` without `current` is refused with `MISSING_CLUSTER_KEY`, naming the missing property. These rows, at least:
  - `none` with a valid cluster key and name key supplied: the context starts, and the `PrivacyCluster` bean holds exactly the resolved `ClusterKeys` (its key ids equal the configured ids).
  - `none` with no `dataprism.hazelcast.security.*` key: the context starts, and no `ClusterKeys` is passed to the factory (task 223 generates the random per-process keys).
  - `none` with a cluster key under 32 bytes: refused with the weak-key code 218 registered for the cluster key (OD-215-3 (a)).
- [ ] `single-node` (no Hazelcast member): any `dataprism.hazelcast.security.*` key, including a valid cluster key and name key, is refused with `CLUSTER_SETTINGS_IGNORED`, naming the property; a row with a valid cluster key pins it. Beside an application-supplied `PrivacyCluster` bean, on any join mode, any of these keys is refused the same way, as the sweep-interval key is (task 233).
- [ ] Plain Java: `PrivacyCluster.embedded(...)` with a `tcp-ip` or `kubernetes` membership and no `ClusterKeys` is refused with `MISSING_CLUSTER_KEY` before a member starts. `using(instance)` with an enabled join and no `ClusterKeys` is refused, and the instance is left running. `embedded(...)` with a `none` membership starts with or without `ClusterKeys`, and holds the supplied `ClusterKeys` when given. `ClusterKeyRefusalTest` covers all three.
- [ ] Every new property is in `frozen-property-names.txt`, in the metadata JSON, and in a `docs/configuration.md` row. `PropertyNamesFrozenTest` and `ConfigurationMetadataDocumentedTest` pass.
- [ ] `TestMembers` and every multi-member test in this module, in the autoconfigure module and in the server module start their members with one shared set of generated test keys. `MultiMemberOperatorTest` passes.
- [ ] `docker/multi-instance/compose.yaml` and `kubernetes.yaml` configure both keys by reference, with placeholder secret names and no key material. `docker compose -f docker/multi-instance/compose.yaml config` exits 0.
- [ ] Key material is never logged and never retained beyond building `ClusterKeys`. A test drives each refusal with a distinctive key value and asserts that it appears in no log line and no exception message.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`.
- [ ] `./mvnw -B verify` exits 0 over the full reactor. Report the counts.

## Preserves
No store logic changes, and the keys are not yet used by any map. Creation tokens, claims, pending slots, atomic commits with TTL, sweeps, bounded locks, both cluster codes, audit-first decisions and `ApprovalView` status are untouched. `dataprism.hazelcast.lock-timeout` stays the one lock-wait setting (D7). No `security.lock-timeout` is added.

## Out of scope
- Using the keys to derive names or seal values (tasks 223 to 229), and generating the random per-process keys of a keyless solo member (task 223, OD-215-8 (a)). That a solo member seals with the supplied keys, or with random ones, is tested by 223 (names) and 231 (values, end to end).
- `pause-lease`, `clock-skew-tolerance`, `unverified-member.*`, `local-floor.enabled` and `isolation.attestation`. Task 230 binds each one together with the code that reads it, so that no setting is bound before something reads it.
- `docs/multiple-instances.md` and `docs/migration-0.8.md` (task 213). Put the facts in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg`, any `.env*` file or any other credential file.
- Test keys are generated in the test or are fixed test bytes. Secret names in the deployment files are placeholders.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A).
