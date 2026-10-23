# 230 — Wire the remaining cluster-security settings, the `clusterSecurity` health contributor and tamper reporting into Spring

Deferred 2026-10-23, owner decision (b): 1.0 supports multiple instances on a trusted network only, and cluster security for untrusted networks is deferred past 1.0 (D-RM-1 (a) as amended; `docs/plan/PLAN.md`, "After 1.0: untrusted-network clusters"). This brief is kept as written for that milestone. Its dependencies, wave and base branch below are those of the 0.8 plan and must be re-planned when the milestone opens; read the 1.0 briefs 225, 227, 228 and 284 to 287 first, because they changed some of the files named here.

What moved out for 1.0: the `clusterSecurity` readiness contributor, the `builtHere` / `isFrameworkCluster` unification, the `ClusterLegacyGaugeTest` recording fix and the `lock-timeout` pass-through are task 286; the `INVALID_CLUSTER_INTERFACE` wording in `docs/configuration.md` and the contract register is task 284. Still deferred here: the `security.pause-lease`, `clock-skew-tolerance`, `unverified-member.*`, `local-floor.enabled` and `isolation.attestation` properties (1.0 runs on the `ClusterSecuritySettings` defaults; `dataprism.hazelcast.trusted-network` from 284 is the 1.0 declaration in place of the attestation), the tamper reporter, and the link-key check values on member cards.

**Repo:** `.`
**Release:** after 1.0 (untrusted-network clusters); was 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 229 and core task 255.
**Depends on:** 222 (the key groups and `validateCluster`), 229 (every reader of the settings exists), 255 (core plan; serialised with 254 and 255 on the shared Spring files below, and the link keys whose check values the member card carries)
**Wave:** none (deferred past 1.0; was a 0.8 wave)
**Decisions:** OD-215-6 decided 2026-10-20 (b)+(c): tamper is a metric plus a rate-bounded WARN log with no audit record; `isolation.attestation` is kept, reported on health and in one startup log line, with no audit record.
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java *(the `security.pause-lease`, `clock-skew-tolerance`, `unverified-member.*`, `local-floor.enabled` and `isolation.attestation` properties only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(passing `ClusterSecuritySettings` and link-key check values to the `PrivacyCluster` bean, the health contributor bean and the tamper listener, and, added 2026-10-23, the `builtHere` unification with `PropertiesValidation.isFrameworkCluster` (Context), only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterSecurityHealth.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validateCluster` and methods it calls only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterSecuritySettingsConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterSecurityHealthTest.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterTamperReporter.java *(new: the `TamperListener` that applies OD-215-6, rate-bounded)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ClusterTamperReporterTest.java *(new)*
- docs/configuration.md *(rows for the keys and startup codes this task adds, and the `INVALID_CLUSTER_INTERFACE` wording, only)*
- docs/plan/design/contract-register.md *(the `INVALID_CLUSTER_INTERFACE` row only; added 2026-10-23)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PropertiesValidation.java *(`isFrameworkCluster` and the shared helper the `builtHere` unification needs only; added 2026-10-23)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterLegacyGaugeTest.java *(the recording map only; added 2026-10-23)*

## Goal
Every cluster-security setting the 0.8 code reads is a typed, validated, documented Spring property, bound only now that something reads it (OD-200-14, 222's out-of-scope note). An operator sees cluster security on the readiness probe: `clusterSecurity` is `DOWN` while the pause document is not fresh, an unverified member is present under `refuse`, or the sentinel or a key check value failed. Tamper events are reported as OD-215-6 (b) decided: a metric and a rate-bounded WARN log line with a map-name constant and a count, never entry data and never an audit record.

## Context
- docs/plan/design/cluster-security.md 4.1, 4.2 (`INVALID_PAUSE_LEASE`, `INVALID_UNVERIFIED_MEMBER_POLICY`, attestation codes), 4.4 (health contributor: readiness group, not liveness; reports the attestation and the member count, never addresses), as amended by 218 (OD-200-14 bounds codes, OD-215-6).
- Task 223 `ClusterSecuritySettings`; task 227 and 228 `ClusterSecurityStatus`; task 220 `TamperListener`; task 227 `MemberCards.addPurposeKey`.
- data-prism-spring-boot-autoconfigure/.../AuditIntegrityHealth.java and its `PrivacyExtensionPoints` row: the health pattern to follow.
- Core task 255 (`git show plan/0.8-core:docs/plan/tasks/255-wire-links-follow-and-key-resolvers.md`): where the link keys are resolved, so their check values can go on the card.
- Task 222's `ClusterKeyConfigurationTest`: the table-driven refusal style.
- From the external review of PR #154, round 5 (2026-10-23), for this task as the next editor of `ClusterBackedState` and the cluster rows of `docs/configuration.md`:
  - Two places decide "is this the starter's cluster": `ClusterBackedState.builtHere` and `PropertiesValidation.isFrameworkCluster`. Make one call the other (or both call one helper), so they cannot drift.
  - `ClusterLegacyGaugeTest` cannot detect a double gauge registration, because its recording map overwrites. Record every registration and assert exactly one per metric.
  - The `INVALID_CLUSTER_INTERFACE` wording in `docs/configuration.md` and the contract register says a `hazelcast.*` address property that makes a single member reachable refuses with that code. That is true only for a keyed solo member; a keyless one refuses `MISSING_CLUSTER_KEY`. Say so in both. 213 makes the same correction in `docs/multiple-instances.md`.

## Acceptance
- [ ] Each property in 218's 4.1 table that this task owns is bound with its type, default and bounds; a value outside bounds is refused at startup with the code 218 registered, naming the property and never the value. One table-driven test, one row per code.
- [ ] With `single-node` (no Hazelcast member), or beside an application-supplied `PrivacyCluster` bean on any join mode, each of these properties is refused with `CLUSTER_SETTINGS_IGNORED` (as 222 does for the keys). With `embedded` and `join.mode: none` (a solo member, which seals with supplied or random keys under OD-215-8 (a)), the rule is per property, and `ClusterSecuritySettingsConfigurationTest` has one `none` row per property:
  - `pause-lease`: allowed and validated as on a joining member. A solo member still writes, refreshes and checks the freshness of its own pause document (228) and leases its own member card (227).
  - `clock-skew-tolerance`: allowed and validated as on a joining member, including the at-most-half-the-lease bound. The cold-start gate (228) runs on every member, and the bound couples it to `pause-lease`: refusing it on `none` would leave the `PT5S` default above half of an allowed `pause-lease` below `PT10S` and make that lease unusable.
  - `unverified-member.action` and `unverified-member.grace`: refused with `CLUSTER_SETTINGS_IGNORED`. They act only on another member that joins, and a `none` member has no join.
  - `local-floor.enabled`: refused with `CLUSTER_SETTINGS_IGNORED`. On one member the local count and the shared count are kept by the same process, so the floor changes nothing.
  - `isolation.attestation`: refused with `CLUSTER_SETTINGS_IGNORED`, and not required (no `MISSING_ISOLATION_ATTESTATION` on `none`). It states how the member port is isolated between members; a `none` member has no member traffic, as 212 refuses `join.isolated-network` there.
  The key groups of 222 (cluster key with `next` and `previous`, and the name key) stay allowed on `none` as 222 states.
- [ ] `isolation.attestation` is kept (OD-215-6 (c)): required on `tcp-ip` and `kubernetes` (absent: `MISSING_ISOLATION_ATTESTATION`; unknown value: `INVALID_ISOLATION_ATTESTATION`), reported on health and in exactly one startup log line, and written to no audit record (a test asserts the startup audit events carry no attestation).
- [ ] `ClusterSecurityHealthTest`: the contributor is in the readiness group and not the liveness group; it is `DOWN` for each of the three causes alone and `UP` otherwise; its details hold the attestation (on a joining member; absent on `join.mode: none`), member count and unverified count, and no address, UUID, key id material or key.
- [ ] A tamper burst of 10,000 events on one map (OD-215-6 (b)): the tamper metric counts all 10,000; the WARN log is rate-bounded to at most one line per map per interval, naming the map-name constant and the count since the last line, never one per event; no audit record is written (`CLUSTER_STATE_TAMPER` is not an audit action). `ClusterTamperReporterTest` asserts all three, and that no log line contains entry data, a key or a keyed name.
- [ ] Whenever a link key is configured (core 254's link-codec bean: links enabled, or page links on with links disabled), the member card carries a check value for each held link key, and two members with different link keys under one id make health `DOWN` (an autoconfigure test with two contexts on one cluster, run once with links enabled and once with links disabled and a link key configured).
- [ ] `PrivacyExtensionPointsTest`, `AutoConfiguredBeanClassificationTest`, `PropertyNamesFrozenTest` and `ConfigurationMetadataDocumentedTest` pass. The frozen list and bean-inventory diffs contain only this task's lines.
- [ ] One decision for "is this the starter's cluster": `ClusterBackedState.builtHere` and `PropertiesValidation.isFrameworkCluster` both call one shared method and neither keeps its own copy of the rule. A test runs both callers over a starter-built cluster, an application cluster under the starter's bean name and an application cluster under another name, and asserts that they agree on each.
- [ ] `ClusterLegacyGaugeTest` records every gauge registration (a list, not a map keyed by metric) and asserts exactly one registration per gauge metric; registering the legacy gauge twice fails the test (the Outcome shows the mutant).
- [ ] The `INVALID_CLUSTER_INTERFACE` wording: in `docs/configuration.md` and the contract register's row, the refusal for a `hazelcast.*` address property that makes a single member reachable is stated for a keyed solo member only, and the same sentence says a keyless one refuses `MISSING_CLUSTER_KEY`.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`.
- [ ] `./mvnw -B verify` exits 0 over the full reactor. Report the counts.

## Out of scope
- The key properties (222). Store and protocol behaviour (224 to 229).
- `docs/multiple-instances.md`, `docs/migration-0.8.md`, `docs/key-rotation.md` (213). Put the facts in the Outcome.
- The end-to-end adversarial suite (231).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg`, any `.env*` file or any other credential file.
- Test keys are generated. Secret names are placeholders.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A).
- No sleeps.
