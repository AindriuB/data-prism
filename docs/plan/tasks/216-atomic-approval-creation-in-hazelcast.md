# 216 — Make Hazelcast approval creation atomic and restore bounded lock leases

**Repo:** .
**Release:** 0.7
**Branch:** from `main`.
**Depends on:** none
**Owns:**
- data-prism-hazelcast/** (main and test; no other open task touches this module)
- docs/multiple-instances.md ("Shared state" map table, "A lock someone holds" and "Approvals under a partition" only)
- docs/configuration.md (the `dataprism.hazelcast.lock-timeout` row at `:195` only)
- docs/plan/tasks/216-atomic-approval-creation-in-hazelcast.md (Outcome section only)

## Goal
Owner decision D-0.7-HZ-1 (2026-10-19): "Rework atomically". Commits 34adde0b
and 08939a79 closed two real races in `HazelcastApprovalStore.create`: the
pending cap per requester could be exceeded, and one approval ID could exist
in two scopes. They did it by holding locks with an infinite lease
(`ClusterLocks.acquireRetained`, `Long.MAX_VALUE`), which contradicts the
approved design: the lease is derived from the timeout, and correctness rests
on the map operation. Make both guarantees hold through atomic map
operations, restore the bounded lease everywhere, and give infrastructure
failures their own refusal code. A failure must never be reported as a
duplicate ID.

## Context
- `data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterLocks.java:10-19, 53-60` covers the class Javadoc and `acquireRetained`, the method to remove.
- `HazelcastApprovalStore.java:63-137` covers both `create` overloads, the `values()` count, the bare-ID lock and `requesterLockKey`. Lines `:81` and `:86` both throw `IllegalArgumentException("duplicate approval id")`. With `hazelcast.lock.max.lease.time.seconds` set to a finite value, `tryLock(..., Long.MAX_VALUE, ...)` throws `IllegalArgumentException` too, and a caller cannot tell the two cases apart.
- `HazelcastApprovalStore.java:218-244` (`decide`) and `:166-196` (`consumeApproved`) are where an approval leaves `PENDING` or is consumed.
- `HazelcastScopeBudget.java:74-110` and `HazelcastCallerRateLimiter.java:50` contain the existing entry-processor pattern to follow (records implementing `EntryProcessor`).
- `PrivacyCluster.java:337-360` configures the maps: `privacyMap`, the approval map's `ApprovalMergePolicy` and `APPROVAL_PROTECTION` quorum, and `EvictionPolicy.NONE` where eviction would reopen a path.
- `data-prism-core/.../core/refusal/ClusterLockTimeoutException.java` shows the shape of a coded refusal (`PrivacyRefusedException`, a public `CODE` constant, a message that carries no data).
- `data-prism-core/.../oversight/ApprovalStore.java` and `InMemoryApprovalStore.java` define the contract. A duplicate ID is `IllegalArgumentException`, and the cap is `ApprovalRefusedException(TOO_MANY_PENDING)`. Both stay as they are.
- `docs/plan/design/cluster-security.md:328, :498`: the lock is bounded and leased, and the lease is derived from `lock-timeout`.
- `docs/plan/tasks/retired/202-hazelcast-approval-consume-and-lock-timeouts.md`: "the lease is derived from the timeout; correctness rests on the map operation".
- Tests to keep and adapt: `PendingCapLockTest.java`, `ApprovalIdLockTest.java`, `CounterLeaseExpiryTest.java:75`, `ClusterLockTimeoutTest.java:196`, `ApprovalStoreContractTest.java:315-326`.

## Required approach
1. **ID claim.** Claim each approval ID with `putIfAbsent` in a bare-ID
   index. Use a new map, or a reserved key space whose keys cannot equal a
   scoped entry key. Each index entry lives at least as long as its approval
   entry. Release it on consume, on expiry (TTL) and on a failed insert. A
   release is a conditional `remove(key, value)`, so a caller never releases
   another caller's claim. A present claim means `IllegalArgumentException`
   "duplicate approval id", and that is the only path that produces it.
2. **Pending cap.** Enforce the cap with one entry processor on a
   per-(kind, requester) entry that holds pending IDs with their expiries.
   Inside the processor, prune the expired IDs (`expiresAt <= createdAt` of the
   new request, as the current count does), refuse when the count is at or
   above the cap, and otherwise add the ID. An approval leaves the entry when
   it is approved, rejected, consumed, expired, forgotten with its scope or its
   insert fails. Both `create` overloads record the ID, because today's count
   includes approvals that the uncapped overload created.
3. **Order and undo.** Claim the ID, then reserve the cap, then
   `putIfAbsent` the approval. On failure, undo in reverse order. An undo that
   fails can only leave an ID unusable or a requester over-counted until
   expiry. It never under-counts.
4. **Locks.** Remove `acquireRetained`. Every lock goes through
   `ClusterLocks.acquire` with the lease of `LEASE_FACTOR` × timeout. `create`
   may still take bounded locks on the keys it writes, but only to bound the
   wait. No check may depend on still holding the lock.
5. **Distinct failure code.** If Hazelcast rejects a lock or lease (an
   `IllegalArgumentException` from `tryLock`), or an index or processor
   operation fails other than as a duplicate or a cap refusal, the caller gets
   a `PrivacyRefusedException` subtype with one stable code. Use
   `CLUSTER_OPERATION_REFUSED` unless the existing codes already have a
   better-fitting name; if they do, record the name chosen in the Outcome. Its
   message names no key, scope, principal or ID.

## Acceptance
- [ ] `grep -rn "acquireRetained\|Long.MAX_VALUE" data-prism-hazelcast/src/main` returns nothing. Every `tryLock` lease equals `ClusterLocks.leaseMillis(timeout)`.
- [ ] `HazelcastApprovalStore.create` (both overloads) never calls `approvals.values()` or `keySet()` to decide uniqueness or the cap.
- [ ] Any new map or reserved key space has `EvictionPolicy.NONE`, a TTL no shorter than the approval entry it guards, and, when `approvalMinimumMembers > 1`, the same `APPROVAL_PROTECTION` write quorum as `dataprism.approval`. Its merge policy never lowers a requester's pending count after a heal (union of IDs) and never drops a held ID claim. `PrivacyClusterMembershipTest` or a new test asserts this configuration.
- [ ] `PendingCapLockTest`: one caller is paused inside `create` (after the ID claim, before the approval insert) for longer than the lock lease. A second member then creates for the same requester with cap 1. The test asserts that exactly one of the two creations succeeds and the other gets `TOO_MANY_PENDING`.
- [ ] `ApprovalIdLockTest`, for both overloads: one caller is paused inside `create` for longer than the lock lease. A second member creates the same ID in another scope. Exactly one succeeds, the other gets `IllegalArgumentException("duplicate approval id")`, and the approval map then holds exactly one key for that ID.
- [ ] In both tests, the `resume.await(ClusterLocks.leaseMillis(TIMEOUT) + 100, ...)` waits (`PendingCapLockTest.java:55`, `ApprovalIdLockTest.java:62`) are gone. No fixed sleep replaces them; tests wait on latches or on observable state.
- [ ] Discrimination check, recorded in the Outcome with the failure output. With `create` temporarily reverted to the pre-34adde0b shape (leased lock, count over `values()`, bare-ID lock with a leased lease), both adapted tests fail. With the fix, both pass. The revert is not committed.
- [ ] `CounterLeaseExpiryTest.java:75` also asserts the stored counter value after the stalled call returns: the count equals the number of admitted acquisitions, so the stalled call added nothing.
- [ ] A test sets `hazelcast.lock.max.lease.time.seconds` to a finite value below the lease and calls `create` (capped and uncapped). The call fails with the new code from step 5, not with `IllegalArgumentException`. The same test asserts that a real duplicate ID still gets `IllegalArgumentException("duplicate approval id")`.
- [ ] A test approves, rejects and consumes approvals for one requester and checks the cap after each. With cap 1, a new create succeeds after the earlier approval leaves `PENDING`, and `forgetScope` frees the requester's slot.
- [ ] `ClusterLockTimeoutTest` and `ApprovalStoreContractTest` pass. Assertions that relied on `requesterLockKey` as a lock key are rewritten against whatever replaces it.
- [ ] The `ClusterLocks` class Javadoc states that locks bound waits only, that every lease is `LEASE_FACTOR` × timeout, and that correctness rests on compare-and-set, `putIfAbsent` and entry processors. It no longer mentions retained locks. The `HazelcastApprovalStore` class and method Javadoc agree with it.
- [ ] `docs/multiple-instances.md` lists the new map or maps in "Shared state". "A lock someone holds" no longer describes retained or non-expiring locks, and states the new refusal code. "Approvals under a partition" covers the index and the pending entry.
- [ ] The `docs/configuration.md:195` row no longer mentions retained locks or "bounded refusals". It says that every lock is leased for five times the timeout.
- [ ] `./mvnw -pl data-prism-hazelcast -am verify` exits 0.
- [ ] The Outcome lists proposed `CHANGELOG.md` lines (`### Fixed`, and `### Changed` for the new code) and a migration note for task 201. This task does not edit `CHANGELOG.md`.

## Out of scope
- Callers of the store (`ToolAdmission`, `ReidentificationService`, `OversightOperatorController`). They already turn any `RuntimeException` into a fail-closed refusal. Mapping the new code to a client-visible code is a successor task, so record it in the Outcome if you think it is needed.
- `ApprovalRefusedException.Code`. Adding a constant breaks exhaustive switches in other modules.
- `CHANGELOG.md`, `docs/tools.md`, `docs/architecture.md` and `docs/plan/design/cluster-security.md`. Task 201 or the 0.8 sealing successors of task 215 own them. If the 0.8 sealing must cover a new map, note it in the Outcome.
- Bounding the waits of identity, re-identification and pause-flag writes. That limitation is already documented.
- `HazelcastScopeBudget` and `HazelcastCallerRateLimiter` logic, apart from the shared lock-error translation in `ClusterLocks`.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Test identities, scopes and IDs are invented and neutral (`requester-a`, `scope-a`). Use no real person, organisation, workplace or business domain.
- In anything you write, say that the product "supports" a requirement, never that it is "compliant".

## Outcome

### Result (2026-10-19)

**Design.**
- `dataprism.approval.id` (`PrivacyCluster.APPROVAL_ID_MAP`): key is the bare approval id, value is the approval's
  entry key. `create` claims with `putIfAbsent`; a present claim is the only source of
  `IllegalArgumentException("duplicate approval id")`. The claim's TTL is the approval's TTL plus the one minute an
  expired entry stays visible, so it always outlives the entry. Release is `remove(id, entryKey)`.
- `dataprism.approval.pending` (`APPROVAL_PENDING_MAP`): key `KIND NUL (=requester | ~)`, value the alternating
  `id NUL expiry` list (`PendingSet`). `PendingSet.Reserve` prunes ids with `expiresAt <= createdAt`, refuses at the
  cap, else adds; `PendingSet.Release` removes one id and deletes the entry when empty. Both overloads reserve (the
  uncapped one with `Integer.MAX_VALUE`). The entry has no TTL: it is pruned on the next reserve and removed when
  empty, so it is never shorter-lived than the approvals it counts.
- Order is claim, reserve, insert; undo is in reverse. After an ambiguous insert failure the undo runs only if
  `containsKey` confirms the entry is absent.
- `create` takes bounded locks (id claim, pending entry, approval entry) through `ClusterLocks.acquire` only, to bound
  waits. `acquireRetained` and every `Long.MAX_VALUE` lease are gone from `src/main`.
- Both guard maps: `EvictionPolicy.NONE`, the `APPROVAL_PROTECTION` write quorum when `approvalMinimumMembers > 1`,
  merge policies `PutIfAbsentMergePolicy` (claims) and `PendingMergePolicy` (union of ids, later expiry).
- Approve, reject and expiry-on-decide remove the id from the pending entry (best effort, never under-counts).
  `forgetScope` and `ScopeIdentityIndex.endScope` (which used to delete approval keys by prefix and now delegates to
  `HazelcastApprovalStore.forget`) free the claim and the slot.
- New code `CLUSTER_OPERATION_REFUSED` (`ClusterOperationRefusedException`, in the hazelcast module because core is not
  owned here; no existing code fitted). `ClusterLocks.acquire` maps the `IllegalArgumentException` of `tryLock` to it;
  `create` maps any other failure of a claim, processor or insert call. It names no key, scope, principal or id.

**Deviations and decisions to confirm.**
1. Consume does not release the id claim (Required approach, step 1 says to release on consume). The consumed entry
   stays in the approval map until its expiry, and releasing the claim would allow a second entry with the same id,
   which `locate` refuses as ambiguous. The claim expires with the entry.
2. `SplitBrainProtectionException` is not translated to the new code: `ApprovalQuorumTest` and the docs already name it
   for a lost write quorum. Everything else a cluster call throws inside `create` becomes
   `CLUSTER_OPERATION_REFUSED`.
3. Outside `Owns`, not edited: `data-prism-spring-boot-autoconfigure/.../ClusterLockTimeoutConfigurationTest.java:156`
   holds the lock on the bare id in `PrivacyCluster.APPROVAL_MAP` and expects `create` to refuse. `create` now locks
   the id in `APPROVAL_ID_MAP`. The fix is one token (`APPROVAL_MAP` to `APPROVAL_ID_MAP`); with it the full reactor
   passes. Until that change lands (task 201 owns that module, or the orchestrator), the full `./mvnw verify` fails on
   that one test. The patch is kept by the implementer.

**Discrimination check.** `admit` was temporarily replaced with the pre-34adde0b shape (leased requester lock, count
over `approvals.values()`, leased bare-id lock, `locateAll`), uncommitted:
- `ApprovalIdLockTest.cappedCreationKeepsItsIdClaimAfterItsLeaseExpires` and `...uncapped...`:
  `AssertionError: Expecting code to raise a throwable.` at `ApprovalIdLockTest.java:50` (the second member's create of
  the same id in another scope succeeded).
- `PendingCapLockTest.aCallerPausedBeforeInsertingStillCountsAgainstTheCapAfterItsLeaseExpires`:
  `AssertionError: Expecting code to raise a throwable.` at `PendingCapLockTest.java:43` (the second create under cap 1
  succeeded).
With the fix all three pass. The tests wait on latches and on `tryLock` of every lock key the stalled caller may hold
(`StalledInsert.awaitFree`), which returns when the lease expires; no sleep.

**Verification.** `./mvnw verify` (full reactor, with the one-token fix above applied locally and then reverted):
BUILD SUCCESS, surefire 1857 tests, 0 failures, 0 errors, 0 skipped (hazelcast 141); failsafe 17 tests, 0 failures
(server 14, quickstart extension 3). Without that fix: 1 failure, `ClusterLockTimeoutConfigurationTest`.
`./mvnw -Prelease -DskipTests package` (Javadoc): BUILD SUCCESS. A first hazelcast run failed
`HazelcastStoredValueBoundaryTest` (it enumerates every map); the test now covers the two new maps, and the module
passed on both later full runs.

**Proposed `CHANGELOG.md` lines.**
- `### Fixed`: Hazelcast approval creation no longer relies on locks that never expire. The pending cap per requester
  and the uniqueness of an approval id across scopes now hold through atomic map operations, so a creating member that
  stalls past its lock lease can no longer let a requester exceed the cap or one id exist in two scopes. Every cluster
  lock is leased for five times `dataprism.hazelcast.lock-timeout` again.
- `### Changed`: a Hazelcast approval create that the cluster rejects (for example a configured
  `hazelcast.lock.max.lease.time.seconds` below the lease) is refused with the new code `CLUSTER_OPERATION_REFUSED`
  instead of an `IllegalArgumentException` that looked like a duplicate id. Two maps are added,
  `dataprism.approval.id` and `dataprism.approval.pending`.

**Migration note for task 201.** Map the new code in `ToolAdmission`, `ReidentificationService` and
`OversightOperatorController` (they already fail closed on any `RuntimeException`, but the client-visible code is
generic); update `ClusterLockTimeoutConfigurationTest.java:156` as in item 3; list the two new maps in
`docs/architecture.md` and `docs/tools.md` if they enumerate maps. A rolling upgrade is safe for creation only once
all members run the new version: an old member does not know the claims, so during the mix an id can still be created
twice. Pending entries and claims start empty after the upgrade, so approvals created before it are not counted
against the cap until they expire.

**For the 0.8 sealing (task 215).** The two guard maps hold approval ids (the claim key), scope ids (the claim value)
and requester principals (the pending key) in clear. If sealing covers the approval map it must cover these too.
