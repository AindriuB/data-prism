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
  - Task 245's three new test files (`DenialAuditUnavailableServiceTest`, `DenialAuditUnavailableOperatorTest`, `ResumeAuditOrderingTest`) are narrowed to carry none of these. If 245 has merged first, this task's sweep updates `OperatorHarness` and the shared fixtures and leaves those three files alone. There is no edge, by decision of 2026-10-21 for the 0.7.1 backport.
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

## Outcome (2026-10-22, 0.8)
A cluster key (with optional `next` and `previous`) and a separate name key are configured by reference under `dataprism.hazelcast.security.cluster-key.*` and `.name-key.*`, resolved through the same `HmacKeyReferenceResolver` as the privacy HMAC key. `tcp-ip` and `kubernetes` require both; `join.mode: none` takes both or neither (a lone one, or `next` or `previous` without a current key, is `MISSING_CLUSTER_KEY` naming the missing property); `single-node` and an application-supplied `PrivacyCluster` refuse every `security.*` key with `CLUSTER_SETTINGS_IGNORED`. A literal `value` is `LITERAL_SECRET_FORBIDDEN` on all four groups, including `next.value` and `previous.value`, which are bound only so they can be refused by name and never echoed.

Where checks live. `DataPrismPropertiesValidator.validateCluster` does property-only checks: presence, exactly one source, literals, single-node and supplied-cluster refusals, and the `member.interface` codes (`MISSING_MEMBER_INTERFACE` there; `UNBOUNDED_MEMBER_INTERFACE` and `INVALID_CLUSTER_INTERFACE` through `MemberInterfaceCheck`, a separate class so the validator loads without `data-prism-hazelcast`; a test proves it with a loader that hides the Hazelcast types). `ClusterKeyResolution`, called from the `PrivacyCluster` bean method, does the rest in this order: key ids (blank or containing a dot: `CLUSTER_KEY_UNRESOLVED` or `NAME_KEY_UNRESOLVED` naming `<prefix>.key-id`, checked per property before anything resolves), unresolved, weak (under 32 bytes), shared with the privacy HMAC key by id or material (`*_SHARED`), name key equal to a cluster key (`NAME_KEY_SHARED`), then ids not pairwise distinct (`DUPLICATE_CLUSTER_KEY_ID`). Messages name properties only; resolved material is zeroed once `ClusterKeys` has copied it.

Plain Java. `PrivacyCluster.embedded(...)` and `using(...)` have overloads taking `ClusterKeys`. Any enabled join (tcp-ip, kubernetes, aws, gcp, azure, eureka, discovery SPI) without keys is `MISSING_CLUSTER_KEY`, before a member starts and before `member.interface` is checked; `using` leaves the instance running. `PrivacyCluster.keys()` is package-private; the public accessor is `keyIds()` (ids only). `legacyEntries()` is public and `ClusterBackedState` registers `Metric.CLUSTER_LEGACY_ENTRIES` as a gauge over it, closing the cluster if registration throws.

Tests and deployment. `TestMembers.KEYS` and the server `ClusterMembers` give every multi-member test one shared set of keys; the server `OperatorHarness` resolver now returns distinct material per reference (the privacy key keeps its fixed bytes). `compose.yaml` and `kubernetes.yaml` configure both keys by reference with placeholder variable and Secret names; `kubernetes.yaml` now also sets `DATAPRISM_HAZELCAST_MEMBER_INTERFACE` (`10.*.*.*`, bounded and covering the common 10/8 pod networks; to be replaced with your pod network).

For the scribe and later tasks.
- `contract-register.md:809`: `dataprism.cluster.legacy.entries` must change from counter to gauge. It reports a level, the latest membership check's count.
- The shared-key check covers only the privacy HMAC key. Task 211 (audit key) and task 242 (link keys) must each extend `ClusterKeyResolution` to compare against theirs.
- `docs/multiple-instances.md` and `docs/migration-0.8.md` (task 213) still say an interface is optional; they need the 0.8 text. The `docs/configuration.md` property rows, the `dataprism.hazelcast` bullet and the startup-code table are updated.
- Deferred to 227 (next task editing `PrivacyCluster`'s cluster checks), from review: rename `UNSAFE_HAZELCAST_SERIALIZATION` or add a register note that it now also covers the REST, memcache, Management Center and phone-home refusals; the serialization-filter blacklist over-refusal, the missing test for a blacklisted subpackage, and the wrong-fix message.
- The packaged server's environment resolver is registered only when `hmac-key.environment-variable` is set and reads any reference with `System.getenv`; a cluster key given as `provider-reference` needs the same reviewed resolver extension as the HMAC key.

Owns widenings approved by the coordinator: `validation/MemberInterfaceCheck.java` (new); `ValidatorWithoutHazelcastTest.java` (new); `PrivacyCluster.legacyEntries()` made public for the `CLUSTER_LEGACY_ENTRIES` wiring; the `next.value` and `previous.value` refusals; the `keyIds()` accessor; the `member.interface` entry in `kubernetes.yaml`; and the corrections to the metadata description and `docs/configuration.md`.

More for the scribe.
- (a) `cluster-security.md` 4.1: add rows for `cluster-key.next.value` and `cluster-key.previous.value` to the property table (~563-576), the key index (~834-847) and the "Reused" sentence (~626). `contract-register.md`: two new key rows near ~991 and ~1001, the totals at ~1235 (20 to 22 `security.*` keys) and N4 at ~1348.
- (b) `CLUSTER_LEGACY_ENTRIES` counter to gauge at `docs/plan/design/contract-register.md:809` and `docs/plan/design/cluster-security.md:667`.
- (c) Wider rules as built: `DUPLICATE_CLUSTER_KEY_ID` also covers a name-key id equal to a cluster-key id, and `*_SHARED` also fires on the same key id as the HMAC key. Update `cluster-security.md` ~610 and ~613-614 and `contract-register.md` ~542 and ~546.
- (d) Add `ClusterKeyResolution.java` and the `CLUSTER_KEY_SHARED` / `NAME_KEY_SHARED` rows to the Owns of briefs 211 and 242's successors (254 and 255 for link keys), so they can extend the shared-key check.
- (e) `docs/multiple-instances.md` (owned by 213) still says `member.interface` is optional.

### External review of PR #154 — fixes

All of the following are review-fix scope, and the Owns widenings are approved:

- `ClusterMembership.isolate` (new, shared by `toConfig` and `PrivacyCluster.embedded(Config)`)
- `docker/smoke/java-runtime-smoke.sh` (header: openssl is required)
- the four new test files: `KeylessMemberIsolationTest`, `ClusterKeyResolutionTest`, `ClusterLegacyGaugeTest`, `MemberInterfaceCheckTest`
- `ClusterBackedState.closeSuppressing`
- the keyless-isolation paragraph and the ordered refusal table in `docs/configuration.md`
- `PropertiesValidation.validateKey` (clears its own copy of the HMAC key, not the resolver's array)

Notes:

- `-Dhazelcast.local.localAddress` may override the loopback interface that isolation sets. That is exposure only: the random cluster-name suffix still blocks any join.
- The resolver's original array is no longer cleared, either for the cluster keys or for the HMAC key. A resolver does not promise a fresh array, and a caching one would lose its live key. Only the copy this code holds is cleared; the resolver's own array stays in memory as long as the resolver keeps it. This is a deliberate trade-off.
- Refusals left without a stable code, by choice: the `IllegalArgumentException`s for `approvalMinimumMembers < 1`, for the sweep interval range, and from the `ClusterKeys` constructor. They are programmatic-API violations, and the Spring path rejects the same inputs earlier with codes.
- Isolation always adds a fresh `-solo-<uuid>` suffix and keeps no marker or other state in the `Config`, so nothing a caller sets can skip it. A `Config` passed twice carries two suffixes, which is harmless. This is what makes the note above true: the random name blocks any join.

### External review of PR #154 — round 2

1. A keyless supplied instance is refused at wrap unless its local member is bound to a loopback address. A keyless wrapper registers a membership listener: when a member is added it logs `MISSING_CLUSTER_KEY` (no key material), stops its background work, marks itself refused so `instance()` throws from then on, and shuts the instance down only if `PrivacyCluster` started it (`embedded`). A real late join cannot be produced in a test, because Hazelcast's joiner-type check turns away any member that is not itself joinerless; the tests announce the join to the wrapper's captured listeners through an instance that forwards everything else to a real one. Two fixtures in `ClusterConfigurationTest` now bind to loopback, as the rule requires.
2. The "name key is the HMAC key" refusal-order row now clashes by key id (`name-key.key-id=v1`) and asserts the full message, so it can no longer be overwritten by the next row. Proven: swapping the two steps in `ClusterKeyResolution` fails it.
3. The legacy-entries gauge test now writes a legacy-format entry, lets a third member join, and asserts the gauge reads 1. Proven: a gauge that returns 0 fails it.
4. A cluster key given as `provider-reference` is read through the new default method `HmacKeyReferenceResolver.resolveProviderReference` (default: `resolve`). The packaged server's environment resolver overrides it to refuse with `MISSING_KEY_PROVIDER`, naming the property. Owns widening approved as review scope: `HmacKeyReferenceResolver`, `ServerIntegrationsConfiguration`.
5. `PropertiesValidation.validateKey` no longer clones or wipes: it reads the resolver's array and leaves it alone.
6. `aKeyedConfigMemberKeepsItsClusterNameAndInterface` also asserts the interface.
7. The property prefixes live in `HazelcastProperties.Security.CLUSTER_KEY_PROPERTY` and `NAME_KEY_PROPERTY`.
8. Inline fully-qualified names removed from the new tests; the stale "stands in for the metric" Javadoc fixed; the test-only `resolve(properties, resolver)` overload removed (tests pass `() -> resolver`).

Follow-up to round 2:

- The loopback test for a keyless supplied instance now reads the listening socket's configuration, not the advertised address: `bind.any` and `server.bind.any` false, interfaces enabled and all loopback, no public address, loopback member address. A real instance with `bind.any` left at its default is refused (the reviewer's probe, reproduced).
- A join before the listener is registered is reported: the listener is an `InitialMembershipListener` and refuses in `init()` when more than one member is present; `using()` and `embedded()` then throw `MISSING_CLUSTER_KEY`.
- The privacy HMAC key is routed like the cluster keys: `HmacKeyReferences` sends an `environment-variable` to `resolve` and a `provider-reference` to `resolveProviderReference`, in `PropertiesValidation.validateKey`, `PrivacyEngineWiring`/`ConfiguredSecretKeyProvider` and `ClusterKeyResolution`. The default method still calls `resolve`, so existing resolvers are unchanged. The packaged server's environment resolver is only registered for an environment-variable HMAC key, so its default setup is unaffected. Owns widening approved as review scope: `ConfiguredSecretKeyProvider`, `PrivacyEngineWiring`, `PropertiesValidation`, new `HmacKeyReferences`.
- Late-join closing is detection, not prevention (partition replicas reach the joiner first; a supplied instance keeps running). `docs/configuration.md` and the Javadoc say so.
- Unused `Map` and `Set` imports removed from `HazelcastProperties`.

### External review of PR #154 — round 3

1. Isolation also sets `hazelcast.socket.server.bind.any=false`, clears the public address and disables the member address provider, then reads the result the way Hazelcast reads its properties (`ClusterMembership.reachableOnlyFromThisHost`, the same helper `PrivacyCluster.using` applies) and refuses `MISSING_CLUSTER_KEY` if the member would still be reachable elsewhere. Hazelcast lets a Config property win over a `-D` system property, so once isolation sets the Config value a system property cannot undo it; the refusal is the backstop. Tests cover `server.bind.any=true` on the Config and as a system property (restored afterwards), a public address and a member address provider, asserting the effective configuration and a failed LAN connect where the host has a non-loopback IPv4 address.
2. The default `HmacKeyReferenceResolver.resolveProviderReference` now refuses with `MISSING_KEY_PROVIDER`, naming no reference. The built-in resolvers in main code are the packaged server's two (environment-only, which already refused; and the missing-provider one, which refuses everything). The test resolvers that serve provider references override it. `docs/migration-0.8.md` did not exist; it is created here with the Breaking note, and tasks 213 and 261 (which list it as new) will meet it. Owns widening approved as review scope.
3. A refused `using()` and `close()` remove both membership listeners (ids kept in `AtomicReference`s; removal is idempotent).
4. The refusal is claimed with `AtomicBoolean.compareAndSet`, so it is logged once under concurrent joins.
5. The legacy-entries gauge is registered for an application-supplied `PrivacyCluster` too, by a `SmartInitializingSingleton`; a registration failure is logged by class and startup goes on, because that member is not ours to stop.
6. A test binds the keys through the `DATAPRISM_HAZELCAST_SECURITY_*` environment names in `compose.yaml` and `kubernetes.yaml` (read from the files) with a `SystemEnvironmentPropertySource`.
7. `validateMemberInterface` catches `NoClassDefFoundError` only. No test: the only way to reach that catch is a missing optional class, which this module's tests cannot remove.
8. `cluster-security.md` now says a keyless solo member holds no keys and is isolated; briefs 223 and 224 carry a dated Context bullet saying sealing and keyed names must refuse when keys are absent, never skip (Context only).
9. Nits: `${VAR:?}` in `compose.yaml`; the two Javadoc blocks on `ClusterKeyResolution.resolve` merged; `TestMembers.keys` Javadoc corrected (the material does not depend on the ids); lines over 120 characters wrapped in the lines this task added; `DataPrismConfigurationException` gained a cause-keeping constructor, used where the cause is a coded refusal with no key material (`ClusterBackedState`, `MemberInterfaceCheck`). The resolver-failure wrappers keep no cause, by decision: a resolver's exception can carry the reference or key material.
