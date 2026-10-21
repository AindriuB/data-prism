# 219 — Pin the Hazelcast 5.7 behaviours the sealed cluster relies on

**Repo:** `.`
**Release:** 0.8.0
**Kind:** investigation recorded as committed characterisation tests. No production code changes.
**Base branch:** `origin/main` (or the 0.8 integration branch).
**Depends on:** none
**Owns:**
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/** *(new package)*

## Goal
`cluster-security.md` marks a number of Hazelcast behaviours "to verify in the 0.8 spike". The 0.7 rework (tasks 202, 216, 232) added more assumptions that sealing will rest on: how entry processors run on backups and on retry, and whether merge policies can reach member-local state. Settle each one against the Hazelcast jar the build pins. Each answer is a committed test that fails if a Hazelcast upgrade changes it, so tasks 221 to 229 build on observed facts, not documentation.

## Context
- docs/plan/design/cluster-security.md: 0.4 (inbound allowlist row), 0.4 closing paragraph (log line format), 1.7 (cluster administration), 2.1 (address classification), 2.3.6 and 4.5 (serialisation filter, Jet, user-code deployment, SQL), 2.4 d2 (duplicate live UUID), 4.6 "Sweeper" (`localKeySet`), 4.8 (mixed patch versions).
- docs/plan/tasks/retired/202-hazelcast-approval-consume-and-lock-timeouts.md, Outcome: `replace` resets an entry's expiry on 5.7.0, and split-brain protection works without a licence.
- docs/plan/tasks/retired/216-atomic-approval-creation-in-hazelcast.md, "TTL tried and reverted": a merge copies the merging side's TTL.
- docs/plan/tasks/retired/232-approval-creation-tokens-and-bounded-cleanup.md, "Re-review fixes": a `Commit` invocation can be retried after the first attempt applied.
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/TestMembers.java, FreePorts.java: how existing tests start members (read only; copy what you need into the spike package).

## Questions, one test class or test each
1. Does an inbound member connection from an address outside `network/interfaces` get accepted? (design 0.4)
2. Does a 5.7 log line carry the cluster name, and in what format? (design 0.4)
3. Which administrative operations does a joined, keyless member or client get to run on OSS 5.7: cluster-state change, member shutdown, map destroy, lock force-unlock? (design 1.7)
4. Can `java-serialization-filter` with `defaults-disabled` and an allowlist of `java.lang.String`, `java.lang.Integer`, `java.lang.Boolean` run every operation Data Prism uses (map, lock, entry processor, split-brain merge, member join), or which extra prefixes does it need? Does a disallowed class sent from a keyless client fail with `SecurityException`? (design 2.3.6, T12)
5. On each path (`Config` built by `ClusterMembership.toConfig`, a supplied `Config`, a supplied running instance): can Jet, user-code deployment, phone-home and SQL each be turned off, or read back as off? What is the switch for each? (design 4.5)
6. What does 5.7 do when a member joins with the UUID of a live member? (design 2.4 d2)
7. Is `IMap.localKeySet()` limited to entries this member owns as primary, and does it stay correct during migration? Is there a partition-local entry iterator in OSS? (design 4.6)
8. Entry processors: is `process` re-run on the backup replica by default? Does a processor that returns a custom `getBackupProcessor()` replace that re-run? Is the backup's entry TTL equal to the TTL that `ExtendedMapEntry.setValue(value, ttl, unit)` set on the primary? After an owner is killed mid-invocation, is the processor re-invoked on the new owner?
9. Can an entry processor and a `SplitBrainMergePolicy` reach member-local, non-replicated state? Try `HazelcastInstanceAware` and `Config.getUserContext()`, and say which works in each.
10. `IMap.tryPut(key, value, timeout, unit)`, `tryRemove(key, timeout, unit)`, `replace(key, old, new)` and `remove(key, value)` against a key that another member or a client holds locked. Which wait without bound, and which return within the timeout?
11. Is `LifecycleEvent.LifecycleState.MERGED` delivered to a lifecycle listener on the members of the smaller side after a split heals, and in what order relative to the merge policies running?
12. Members on two different 5.7.x patch versions: join or refuse? Run this only if a second 5.7 patch jar resolves from Maven Central; otherwise record "not run" and why. (design 4.8)

## Acceptance
- [ ] Each question 1 to 12 has a test in the `spike` package whose name states the observed behaviour (for example `aMemberOutsideTheInterfacesIsAccepted`). Its assertions fail if the behaviour changes. Question 12 may instead be a recorded "not run" with the reason.
- [ ] The Outcome holds a table: question, observed answer, test name, and which of tasks 221 to 229 relies on it. Where an answer contradicts the design, the row says what the design assumed.
- [ ] Question 3's answer lists each operation as allowed or refused for a keyless member and for a client, separately.
- [ ] Question 8's answer says plainly whether an entry processor that seals inside `process` would leave the primary and the backup holding different bytes. Tasks 223 and 224 depend on this answer.
- [ ] Question 9's answer says plainly whether `ApprovalMergePolicy` and `PendingMergePolicy` could unseal values. Task 224 chooses between its two designs on this answer.
- [ ] No test uses a fixed sleep. Waits are on latches, membership events or polled state with a bound.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` exits 0 twice in a row. Report the test counts.
- [ ] No file outside `Owns` changes. `git diff --stat` shows only the `spike` package.

## Out of scope
- Changing `PrivacyCluster`, `ClusterMembership` or any store. Task 221 acts on answers 1 to 5. Tasks 223 and 224 act on 8 to 10. Task 228 acts on 11, and task 227 on 6.
- Upgrading the pinned Hazelcast version. Task 221 owns `pom.xml`.
- Anything that needs Hazelcast Enterprise.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Cluster names, scopes and ids in tests are invented and neutral.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature or licence (D-0.4.1-A).

## Outcome (2026-10-21, 0.8 wave 1)
Merged onto `record/0.8-wave1a` (task branch head 6e751e49; tester PASS: two consecutive `./mvnw -B -pl data-prism-hazelcast -am verify` runs, 51 spike tests in 12 classes, 265 hazelcast tests, 0 failures; reviewer APPROVE). The answer table is in `data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/spike/package-info.java`; read it there. In summary:

- Q1: `network/interfaces` does not restrict inbound connections.
- Q3: a keyless client that knows only the cluster name can freeze or shut down the cluster with Management Center protocol messages. The Java client API refuses locally; the member accepts.
- Q13: a `ManagementCenterConfig` `trustedInterfaces` that does not match the client makes the member refuse MC change-state with `AccessControlException`. Only change-state was tested, and Data Prism does not set it today. The console, scripting and data-access switches do not stop it.
- Q4: with only String, Integer and Boolean allowed, a remote entry processor fails as `OperationTimeoutException`. On a caller-owned key the primary applies it and the backup is refused, which is silent divergence.
- Q5: phone-home is on when unset, forced off for a supplied `Config`, and untouched on a supplied running instance.
- Q6: a duplicate live UUID does not join.
- Q7: `localKeySet` is the primary-owned keys.
- Q8: sealing inside the process makes primary and backup bytes differ unless a custom backup processor copies them, and a copy-only backup processor loses the TTL.
- Q9: merge policies and entry processors that implement `HazelcastInstanceAware` can read member-local state, so they could unseal.
- Q10: `tryPut` and `tryRemove` return false within the timeout; `replace(k,old,new)` and `remove(k,v)` wait without bound on a locked key.
- Q11: MERGING, then the policy, then MERGED, on the smaller side only.
- Q12: not run, because Central has no second 5.7.x patch.

Incident: in review round 1 a spike test started a member with phone-home enabled, so builds of that commit posted Hazelcast's anonymous usage ping to `phonehome.hazelcast.com`. Fixed in round 2: every spike member-creation path calls a guard that refuses phone-home on, and a guard test covers it. Do not create a member in a test without that guard.
