# 286 — Report cluster security on readiness, and fix the cluster wiring follow-ups 230 carried

Filed 2026-10-23 for owner decision (b). Task 230 is deferred past 1.0, but 1.0 keeps 227 (an unverified member under `refuse` stops the instance serving) and 228 (a pause document that is not fresh makes every read answer paused or refuse). An instance that refuses every call while its readiness probe says `UP` keeps receiving traffic from a load balancer. This brief takes from 230 only the readiness contributor and the wiring defects already found in it. The properties, the tamper reporter and the link-key check values stay deferred.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 228, 254, 270, 284 and 287.
**Depends on:** 228 (pause freshness in `ClusterSecurityStatus`), 287 (both edit `ClusterBackedState.java`), 254 (the previous editor of `PrivacyExtensionPoints.java` and the bean inventories; 255 follows this task), 270 (both edit `PropertiesValidation.java` and `docs/plan/design/contract-register.md`; PR #161 moved `PropertiesValidation.java` to 271 on 2026-10-24 and back to 270 on 2026-10-26, so the edge to 271 is dropped), 284 (both edit `ClusterLegacyGaugeTest.java`)
**Wave:** 10
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterSecurityHealth.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the health contributor bean, the `lock-timeout` argument to the cluster-backed stores and the cluster bean, and the `builtHere` unification, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ReidentificationWiring.java *(the `lock-timeout` argument at :61 only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PropertiesValidation.java *(`isFrameworkCluster` and the shared helper only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for the bean this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterSecurityHealthTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterLockTimeoutPassThroughTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterLegacyGaugeTest.java *(the recording map only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPointsTest.java *(insertions only, if it lists rows)*
- docs/plan/design/contract-register.md *(the rows 284's Outcome lists: `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK`, `INVALID_TRUSTED_RANGE`, the `trusted-network` and `member.trusted-ranges` properties, and the `INVALID_CLUSTER_INTERFACE` row, only; moved from 284 on 2026-10-24)*

## Goal
An operator sees cluster security on the readiness probe. The `clusterSecurity` contributor is `DOWN` while this instance is not serving, for any of these reasons: an unverified member under `refuse`, a sentinel or key-check-value failure (227), or a pause document that is not fresh (228). It is `UP` otherwise. It is in the readiness group and not the liveness group, so a refusing instance is taken out of rotation, not restarted. Three defects found in reviews of the cluster wiring are fixed alongside.

## Context
- docs/plan/tasks/deferred/230-wire-cluster-security-settings-health-and-tamper-reporting.md: the `ClusterSecurityHealthTest` acceptance item and the round-5 follow-ups. They are reproduced below without the attestation, the properties and the link keys.
- docs/plan/design/cluster-security.md 4.4 (the health contributor: readiness group, not liveness; reports the member count, never addresses).
- `ClusterSecurityStatus` (227, with pause freshness from 228): serving or not, the reason, the member count and the unverified count.
- data-prism-spring-boot-autoconfigure/.../AuditIntegrityHealth.java and its `PrivacyExtensionPoints` row: the pattern to follow.
- From the external review of PR #154, round 5 (2026-10-23), moved from 230:
  - Two places decide "is this the starter's cluster": `ClusterBackedState.builtHere` and `PropertiesValidation.isFrameworkCluster`. Make one call the other, or both call one helper, so they cannot drift.
  - `ClusterLegacyGaugeTest` cannot detect a double gauge registration, because its recording map overwrites.
- From the wave-4 record (PR #163, 2026-10-23; "fold into 230" there): Spring does not pass `dataprism.hazelcast.lock-timeout` to the new overloads at `ClusterBackedState`:172 and `ReidentificationWiring`:61.

## Acceptance
- [ ] Tests are written first, and the Outcome shows each one failing on the base commit. Do not use `git stash` for this: check out the base commit in a separate worktree.
- [ ] `ClusterSecurityHealthTest`: the contributor is in the readiness group and not in the liveness group. It is `DOWN` for each cause alone (an unverified member under `refuse` past the grace, a sentinel mismatch, a key-check-value mismatch, a pause document that is not fresh) and `UP` otherwise. Its details hold the reason, the member count and the unverified count, and no address, UUID, key id, key material or pause content (a sentinel scan of the details map).
- [ ] The contributor exists whenever a `PrivacyCluster` bean exists: a starter-built member on `tcp-ip`, `kubernetes` or `join.mode: none`, and an application-supplied cluster. With `single-node` there is no contributor. The bean-inventory diff contains only this line.
- [ ] `lock-timeout` pass-through: with `dataprism.hazelcast.lock-timeout: PT3S`, every cluster-backed store and the reidentification index feed built by the starter use `PT3S`. `ClusterLockTimeoutPassThroughTest` asserts this for each bean through an accessor or a recording `PrivacyCluster`, not by timing.
- [ ] One rule decides "is this the starter's cluster": `ClusterBackedState.builtHere` and `PropertiesValidation.isFrameworkCluster` both call one shared method, and neither keeps its own copy. A test runs both callers over a starter-built cluster, an application cluster under the starter's bean name and an application cluster under another name, and asserts that they agree on each.
- [ ] `ClusterLegacyGaugeTest` records every gauge registration in a list, not in a map keyed by metric, and asserts exactly one registration per gauge metric. Registering the legacy gauge twice fails the test (the Outcome shows the mutant).
- [ ] The contract register (moved from 284 on 2026-10-24, because 270 now shares 284's wave and owns the register there): `PUBLIC_MEMBER_INTERFACE` moves from "not built; the name stays reserved" to built by 284 and 287, with the private-range condition. `MISSING_TRUSTED_NETWORK`, `INVALID_TRUSTED_RANGE` and the two properties have rows, taken from 284's Outcome. The `INVALID_CLUSTER_INTERFACE` row carries the same keyed or keyless wording as `docs/configuration.md`.
- [ ] `PrivacyExtensionPointsTest`, `AutoConfiguredBeanClassificationTest` and `AutoConfiguredBeanInventoryTest` pass, with only this task's lines in their lists.
- [ ] `./mvnw -B verify` exits 0 over the full reactor. Report the counts.

## Out of scope
- Every property 230 would have bound (`security.pause-lease`, `clock-skew-tolerance`, `unverified-member.*`, `local-floor.enabled`, `isolation.attestation`). 1.0 runs on the `ClusterSecuritySettings` defaults, and this task adds no property and no `docs/configuration.md` row.
- The tamper reporter (`ClusterTamperReporter`, OD-215-6) and the link-key check values on member cards (230, deferred).
- `docs/multiple-instances.md` and `docs/operations.md` (213, 261). Put the contributor's name, group and details in the Outcome.
- `HazelcastProperties.java`, `DataPrismPropertiesValidator.java` (284, 254, 255).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg`, any `.env*` file or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Test keys are generated. Neutral, invented identifiers.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No sleeps.
