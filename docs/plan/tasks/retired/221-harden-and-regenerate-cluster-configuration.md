# 221 — Force the safe Hazelcast settings, move to the `.s1` map generation and refuse old-format state

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 218 and 219.
**Depends on:** 218 (map names, codes, cutover rule), 219 (answers 1 to 5 and 12)
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(`configure`, `using`, the map-name constants and the map-configuration calls only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterMembership.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java *(insertions only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/IdentityMaps.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ApprovalMaps.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/OversightMaps.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CounterMaps.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterMembershipTest.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastHardeningTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ClusterCutoverTest.java *(new)*
- pom.xml *(the `hazelcast.version` property only)*

## Goal
Close the configuration gaps the design and the 0.7 review found, and give 0.8 its own map generation. On both `embedded` paths, Jet, user-code deployment, phone-home and (if 5.7 has a switch) SQL are forced off, and a Java serialisation allowlist is set. `using(instance)` checks the same things and refuses an instance it cannot make safe (OD-16 (i)). `member.interface` becomes required and may not match every address (OD-10 (ii)). Every Data Prism map moves to its `.s1` name. A member that finds pre-0.8 state refuses to start, so a 0.7 and a 0.8 generation never serve side by side (OD-215-1). The map configuration moves into one small class per area, so tasks 224 to 228 can each own their area's configuration without editing `PrivacyCluster`.

## Context
- docs/plan/design/cluster-security.md 2.3.6, 4.5 (what each path forces), 4.2 (`UNSAFE_HAZELCAST_SERIALIZATION`, `MISSING_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE`, `UNSEALED_CLUSTER_STATE`), 4.8, OD-10, OD-16, all as amended by 218.
- Task 219 Outcome, answers 1 to 5 and 12.
- PrivacyCluster.java:349-376 (`using`), :401-475 (`configure`, `approvalGuard`, `privacyMap`); ClusterMembership.java:91-92 (Jet and phone-home, today set only on this path).
- docs/plan/PLAN.md, "0.7.0 follow-ups found in review": "Jet and phone-home are not forced off for a supplied `Config` or instance" and "Hazelcast is pinned to 5.7.0 while the docs list 5.7.2". Both are folded in here.
- docs/plan/tasks/retired/202-*.md and 216-*.md: approval-map merge policy, `APPROVAL_PROTECTION` quorum, `EvictionPolicy.NONE` on the guard maps and on `oversight` and `callerrate`. The per-area classes keep each of these exactly.
- From 218/219 (2026-10-21): The startup `UNSEALED_CLUSTER_STATE` check refuses only when no other verified 0.8 member is present; the fence entry (`all` = TRUE in `dataprism.oversight`) is ignored, and legacy entries are counted and WARN-logged afterwards. Set `ManagementCenterConfig` `trustedInterfaces` to a NON-EMPTY set that matches no permitted client address (for example a single address no client can have), so clients cannot send MC state-change or shutdown. An empty set is Hazelcast 5.7.0's default and means unrestricted, so it is unsafe and must be treated as such: the spike test `withTheDefaultConfigAClientFreezesTheCluster` shows a keyless client freezing the cluster with it (Q3, Q13). Tests must show (a) a client is refused every MC message type the setting blocks, using the non-empty non-matching set, and (b) the empty set is unsafe, i.e. the client can freeze the cluster, so a regression to empty fails. `network/interfaces` is not an inbound filter (Q1). The serialization allowlist must cover every Data Prism processor and merge-policy class (Q4).

## Acceptance
- [ ] `HazelcastHardeningTest`, for `embedded(ClusterMembership)` and `embedded(Config)`: the started member has Jet off, user-code deployment off, phone-home off, SQL off (or the Outcome cites answer 5 for why SQL has no switch), and a serialisation filter whose allowlist is exactly the set that answer 4 established. A supplied `Config` whose filter allows more is refused with `UNSAFE_HAZELCAST_SERIALIZATION`.
- [ ] `using(instance)`: an instance with no allowlisting filter, or with Jet or user-code deployment enabled, is refused with the code decided in 218 (OD-16 (i)), and the instance is left running. The test covers each failed check alone.
- [ ] T12: a keyless client sends a disallowed class to a member started by either `embedded` path and gets a `SecurityException`. The member keeps serving.
- [ ] `ClusterMembership` with `tcp-ip` or `kubernetes` and no `member.interface` is refused with `MISSING_MEMBER_INTERFACE`. An interface matching every address (`*.*.*.*`) is refused with `UNBOUNDED_MEMBER_INTERFACE`. A `none` join needs neither. `PUBLIC_MEMBER_INTERFACE` is not added (OD-10 (ii)).
- [ ] Every `PrivacyCluster` map-name constant has the 218 name, ending `.s1`. The constants for the consumed-approval tombstone map and the member-card map are added now, with their map configuration in `ApprovalMaps` and a new entry in the area classes (the member-card map in `OversightMaps` unless 218 places it elsewhere), so that tasks 224 and 227 add no constant and no configuration call to `PrivacyCluster`. A test pins the full list against register section 8.1.
- [ ] `IdentityMaps`, `ApprovalMaps`, `OversightMaps` and `CounterMaps` each configure their area's maps. `PrivacyClusterMembershipTest` asserts each map's eviction, TTL, merge policy and quorum settings, and they equal the 0.7 settings map for map.
- [ ] `ClusterCutoverTest`: a member that joins a cluster holding any entry in a pre-0.8 map name (`dataprism.identity`, `dataprism.budget`, `dataprism.callerrate`, `dataprism.oversight`, `dataprism.approval`, `dataprism.approval.id`, `dataprism.approval.pending`, `dataprism.reidentification`) is refused with `UNSEALED_CLUSTER_STATE` and serves nothing. A budget entry written in the 0.7 format is the case named in the test. The check never reads or logs the old entries' contents.
- [ ] `pom.xml` pins the newest 5.7.x patch on Maven Central at implementation time. The spike package (219) and this module's tests pass on it. The Outcome names the version and the release-checklist compatibility note (design 4.8).
- [ ] Every new refusal message names the setting or condition and never a value (`PrivacyClusterRefusal` convention).
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
- Claim map and owner-tagged pending slots: the claim and pending maps keep `EvictionPolicy.NONE`, `PutIfAbsentMergePolicy` and `PendingMergePolicy`, and the approval quorum when `approvalMinimumMembers > 1`. Only the names change.
- Atomic commit with TTL, bounded locks, `CLUSTER_LOCK_TIMEOUT` and `CLUSTER_OPERATION_REFUSED` reaching callers: no store logic changes.
- 202's split-brain protection on the approval maps is configured exactly as before, under the new names.

## Out of scope
- Keys, sealing, keyed names and the keyless-join refusal (tasks 222 and 223).
- Spring validation of `member.interface` (task 222 maps these codes into `DataPrismPropertiesValidator`).
- The OD-5 (ii) compatibility mode. Under OD-215-1 (a) it is not built. If the owner chose (b), stop: this brief must be re-cut.
- `docs/multiple-instances.md` and `docs/configuration.md`. Record the facts for tasks 213 and 222 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented cluster names and identifiers in tests.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Waits are on latches or polled state with a bound.

## Outcome (2026-10-21, 0.8 wave 2)
Merged onto `record/0.8-wave2` (task branch head a0318b20; tester PASS with the full `./mvnw -B verify` twice, 311 hazelcast tests; reviewer APPROVE). Both embedded paths are hardened: Jet, user code and phone-home are off (SQL fails with `JetDisabledException`; no switch, answer 5), the REST API and memcache are forced off, the serialization filter has `defaultsDisabled` and a pinned allowlist, `ManagementCenterConfig` `trustedInterfaces` is forced to `{255.255.255.255}` (14 Management Center message types are refused, and an empty set is shown as unsafe), and a global-serializer override of Java serialization is refused. `using()` refuses an instance with no filter, defaults on, missing Data Prism classes, Jet, user code, extra prefixes or packages (extra named classes are allowed), empty `trustedInterfaces`, phone-home on, memcache, REST groups other than `HEALTH_CHECK`, or a global-serializer override. `PrivacyCluster.requiredSerializationFilter()` is public. New interface checks `MISSING_MEMBER_INTERFACE` and `UNBOUNDED_MEMBER_INTERFACE` parse ranges as integers. Ten `.s1` map constants exist, including `APPROVAL_CONSUMED_MAP` and `MEMBER_CARD_MAP`, with per-area classes and pinned settings.

`UNSEALED_CLUSTER_STATE` is raised at start when another member is visible (the fence entry alone is ignored). A membership listener re-runs the check on each join on its own threads with a 10 s watchdog, never shuts a running member down, counts legacy entries (package-private `legacyEntries()`) and WARN-logs them. The fence is recognised but not written (task 228).

Coordinator decisions: (A) the allowlist is widened with Hazelcast exception and plumbing classes, the `com.hazelcast.spi.exception.` prefix and the exceptions Data Prism processors throw, because a lost error reply hangs until the operation timeout; `FilterRejectionGuard` fails hazelcast-module tests on any "Resolving class ... is not allowed". (B) Owns extended to the Spring `ClusterConfigurationTest` `hardened()` helper (now phone-home off), the spike Q05 tests and Q5 row, and the guard infrastructure. (C) The verified-member excuse waits for 227: until then a join to a cluster of two or more that holds a non-fence old entry is refused. Hazelcast stays at 5.7.0 (the newest 5.7.x on Central, 2026-10-21).

BREAKING for integrators: `using()` refuses instances it used to accept. A release note is required.
