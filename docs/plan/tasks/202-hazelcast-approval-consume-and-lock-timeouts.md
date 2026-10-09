# 202 — Make approval consumption conditional and bound every Hazelcast lock wait

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none (scheduled in wave 2 by D-070-14)
**Decided (owner, 2026-10-09):**
- D-070-14 (a): fix both defects found by the task 192 review in 0.7, as one task.
- D-070-15 = (b), D-070-16 = (a), D-070-17 = `dataprism.hazelcast.lock-timeout`
  (default `PT2S`, `PT100MS` to `PT30S`, `INVALID_LOCK_TIMEOUT`), decided by the
  owner. All `[15b]` and `[16a]` items apply.
**Decided (was open):** the three items below.
- D-070-15: what to do about split brain on the approval map. See "Decision
  D-070-15" below. Recommendation (b). Acceptance items marked **[15b]** apply
  only if (b) is chosen.
- D-070-16: which refusal code a bounded wait that runs out gives the client.
  See "Decision D-070-16" below. Recommendation (a). Items marked **[16a]**
  apply only if (a) is chosen.
- D-070-17: the name of the timeout key. Recommendation:
  `dataprism.hazelcast.lock-timeout`, default `PT2S`, range `PT100MS` to `PT30S`,
  startup refusal `INVALID_LOCK_TIMEOUT`. The 192 design names the same knob
  `dataprism.hazelcast.security.lock-timeout` and folds its range check into the
  0.8 code `INVALID_PAUSE_LEASE`. That would put a key that has nothing to do
  with sealing under `security.`, and use a refusal code that will not exist until
  0.8. Task 200 records the name chosen here and marks the design's name as
  superseded.

**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudget.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastCallerRateLimiter.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterLocks.java (new, name free: the bounded-wait helper)
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ApprovalMergePolicy.java (new, name free)
- data-prism-hazelcast/src/test/**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/ClusterLockTimeoutException.java (new, name free) **[16a]**
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ToolAdmission.java (the `admit` catch blocks only) **[16a]**
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/ToolAdmissionTest.java **[16a]**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java (the `dataprism.hazelcast` checks only)
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json (insertions only)
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt (insertions only)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterLockTimeoutConfigurationTest.java (new, name free)
- docs/multiple-instances.md (the "Configuration", "Refusals" and "Failure behaviour" sections only)
- docs/configuration.md (the `dataprism.hazelcast` row at `:138` and the `dataprism.hazelcast.*` key table at `:185-195` only)
- docs/tools.md (the "Admission codes: oversight refusals" section, `:442-474`, only) **[16a]**
- docs/plan/tasks/202-hazelcast-approval-consume-and-lock-timeouts.md (Outcome section only)

## Goal
The task 192 review found two correctness bugs in released Hazelcast features.
First, an approval is consumed "exactly once" only because of `IMap.lock`.
Hazelcast does not protect that lock under split brain, so one approval can be
used twice, and after the cluster heals the default merge can make a consumed
approval usable again. Second, every lock is taken with no timeout. A held lock
(another member, a slow member or an attacker on the member port) therefore
hangs request threads instead of refusing. This task makes consumption a
conditional operation, keeps the consumed state when the cluster heals, bounds
every wait with a configurable timeout that fails closed with a stable code, and
documents exactly what can still happen under split brain.

## Decision D-070-15: split brain on the approval map

What is known. The CP Subsystem is a licensed feature in 5.7.0
(`docs/plan/design/cluster-security.md:84`), so no linearisable structure is
available. Split-brain protection (`SplitBrainProtectionConfig`,
`com.hazelcast.splitbrainprotection.impl`) is present in the open-source 5.7.0
jar, and the jar contains no licence check for it. The implementer confirms at
runtime that it works without a licence before relying on it. Once the members
are partitioned, each side has its own copy of the entry. A conditional replace
then makes consumption atomic *within* each side, but each side can still
consume the approval once.

- **(a) Conditional consume plus a consumed-wins merge policy only.** After the
  heal, an approval that either side consumed stays `CONSUMED`. What remains: up
  to one use per side while the partition lasts. Documented, no new key.
- **(b) (a) plus split-brain protection on the approval map** for its write
  operations, so the minority side refuses to consume, approve or reject. The
  refusal surfaces as `OVERSIGHT_UNAVAILABLE` through the existing catch in
  `ToolAdmission`. New key `dataprism.hazelcast.approval-minimum-members`
  (integer, at least 1):
  - With `tcp-ip`, unset means a majority of the distinct `join.members` entries.
    The list is documented as naming every member, this one included.
  - With `none`, unset means `1`.
  - With `kubernetes`, the key is required when
    `dataprism.oversight.approval-required-tools` is non-empty. Startup refuses
    with `MISSING_APPROVAL_QUORUM`.
  - A value above the member count, or one set with `none`, is refused with
    `INVALID_APPROVAL_QUORUM`.

  What remains: one use per side only inside the detection window, before the
  minority notices it is a minority (bounded by Hazelcast's heartbeat settings,
  which the doc states). The cost: in a two-member cluster, losing either member
  stops approval-gated tools until it returns. That is fail-closed, as rule 2
  requires.
- **(c) Neither. Only document the defect.** Rejected by D-070-14.

Recommendation: **(b)**. Approval exists to enforce four-eyes, and (b) is the
only option that refuses on the side that cannot know whether the approval was
already used. In both (a) and (b) the residual is stated in
`docs/multiple-instances.md`.

## Decision D-070-16: the code a timed-out wait gives the client

- **(a) One new code, `CLUSTER_LOCK_TIMEOUT`, on the tool-call path.**
  - `HazelcastScopeBudget.tryRead` throws `ClusterLockTimeoutException`, a
    `PrivacyRefusedException` with that code. The existing catch in
    `DefaultContextOrchestrator` (`:222-238`) audits it as
    `DENY:CLUSTER_LOCK_TIMEOUT` with no orchestrator change.
  - `ToolAdmission.admit` maps the same exception to
    `refuse("CLUSTER_LOCK_TIMEOUT")`.
  - The operator API (`OversightOperatorController`) keeps returning 503
    `OVERSIGHT_UNAVAILABLE` from its existing `RuntimeException` catch. Its tests
    live in `data-prism-server/src/test/**`, which task 190 owns.
- **(b) No new code.** Admission timeouts become `OVERSIGHT_UNAVAILABLE`. A budget
  timeout returns `false`, as a cluster failure already does, and the client is
  told `SCOPE_READ_BUDGET`. That tells the client the budget is exhausted when it
  is not.

Recommendation: **(a)**. (b) gives the client a wrong reason, and an operator
then cannot tell a held lock from real use.

## Context
- `HazelcastApprovalStore.java:146-168` (`consumeApproved`): lock, read, check
  `APPROVED`, `set` to `CONSUMED`. `:190-213` (`decide`): the same shape for
  approve and reject. `:58-59` and `:89-90`: the id lock and the requester lock in
  `create`. Both are still needed, because neither the duplicate check across
  scopes nor the per-requester cap is a single-key operation.
- `HazelcastScopeBudget.java:42-67`: its fail-closed `catch` only runs on a
  thrown exception, so a blocked `lock` hangs rather than refuses.
  `HazelcastCallerRateLimiter.java:31-54`: the same lock, with no catch at all.
- **A trap to test, not assume.** A mutating `IMap` operation (`set`, `replace`,
  `remove`, `executeOnKey`) on a key that another owner has locked *waits for that
  lock*. Swapping `lock` for a compare-and-set therefore does not fix defect 2 by
  itself. Every mutating call on a key that can be locked needs a bounded wait:
  `tryLock(timeout, unit, lease, unit)`, or an async variant
  (`submitToKey`, `setAsync`, ...) completed with
  `toCompletableFuture().get(timeout)`. The held-lock tests below decide which
  works. The Outcome records what a timed-out async operation leaves parked on
  the member.
- TTL: `write` (`:242-249`) re-sets the TTL because a plain `set` resets it.
  Check whether `replace(key, old, new)` keeps or resets the entry's expiry on
  5.7.0. An approval must still expire at its `expiresAt`.
- `PrivacyCluster.configure` (`:131-155`) is where map configs are built: the
  merge policy and, under [15b], the split-brain protection config go on the
  `dataprism.approval` map config. `PrivacyCluster.using(instance)` (`:92-102`)
  adds no map config. A supplied instance therefore gets neither unless its owner
  configures them. Document this. Do not refuse it (that is 0.8's
  `UNSAFE_HAZELCAST_SERIALIZATION` area).
- `docs/plan/design/cluster-security.md`:
  - 1.4 (`:141-142`) and `:184` describe the hang.
  - 2.3.2 (`:328`) proposes `tryLock` with a lease.
  - 2.3.4 (`:348-356`) is the 0.8 consumed tombstone. Do not build the tombstone
    here.
  - `:338`: a Hazelcast merge policy cannot read sealed payloads. 0.8 sealing will
    therefore have to move the consumed-wins rule to the tombstone or the
    application. Note this in the Outcome for 0.8.
  - T11 (`:742`) is the lock-hold test this task brings forward.
- `ToolAdmission.java:77-83`: the catch blocks that turn any failure into
  `OVERSIGHT_UNAVAILABLE`.
- `ClusterBackedState.java:45-98` builds the cluster and the three stores. Keep
  the public constructors of the stores and the `PrivacyCluster` factories source
  compatible. Add an overload that takes the timeout, and give the existing ones
  the default.
- `PropertyNamesFrozenTest` and `ConfigurationMetadataDocumentedTest` are the
  exhaustiveness guards for any new key: the frozen list, the metadata JSON and
  `docs/configuration.md` all gain the row.
- `docs/conventions.md#reviewer-isolation-kit`: mutation proofs are run on a
  worktree no reviewer is reading.

## Acceptance
Write the tests first. Each item says whether it must fail on `main`.
- [ ] **Merge on heal, fails on `main`.** Take an `APPROVED` entry on one side
      and the same entry `CONSUMED` on the other. Merging them through the
      `dataprism.approval` map's configured merge policy, in either direction and
      whichever side is larger, leaves `CONSUMED`. A `REJECTED` or `EXPIRED` entry
      also beats `APPROVED`, and `PENDING` never beats a decided state. On `main`
      the default policy keeps the larger side's `APPROVED`, so the test fails.
- [ ] **Real heal (best effort).** An integration test in
      `data-prism-hazelcast/src/test` forms two clusters with the same name, lets
      them merge with shortened merge delays, and asserts that the consumed
      approval is still `CONSUMED`. If 5.7.0 cannot make this happen reliably in
      under 60 s, the Outcome says why and the item above stands alone.
- [ ] **Concurrent consume.** At least 16 threads across two members race
      `consumeApproved` for one approval, repeated 50 times. Exactly one call
      returns the request each time. (This passes on `main`. Its job is the
      mutation proof below.)
- [ ] **Mutation proof for the conditional operation.** Change the conditional
      replace or remove in `consumeApproved` to an unconditional `set`, and remove
      any surrounding lock. The concurrent-consume test then fails. Do the same
      for approve and reject in `decide`: a test racing `approve` against `reject`
      ends with exactly one decision. Record both runs in the Outcome.
- [ ] **No lock-then-check on the consume path.** `consumeApproved` and `decide`
      no longer call `IMap.lock`. The only remaining waits on locks are bounded.
      `grep -n '\.lock(' data-prism-hazelcast/src/main` returns no lines.
- [ ] **Held lock, fails on `main` within a bound.** For each of
      `HazelcastScopeBudget.tryRead`, `HazelcastCallerRateLimiter.tryAcquire`,
      `HazelcastApprovalStore.consumeApproved`, `approve`, `reject` and both
      `create` overloads, a test locks the key the call needs from another thread
      or member. It then runs the call on an executor and waits at most
      `3 × lock-timeout`.
      - On `main` the wait runs out and the test fails. The test then releases
        the lock with `forceUnlock`, so the suite does not hang.
      - After the change, each call refuses within `lock-timeout` plus 500 ms,
        and no counter or approval state changed.
- [ ] **[16a] The code is stable.**
      - A budget timeout surfaces as `PrivacyRefusedException` with code
        `CLUSTER_LOCK_TIMEOUT`. An orchestration-level test in
        `data-prism-hazelcast/src/test`, or a `ToolAdmissionTest` case, shows the
        code unchanged by `RefusalCodes.sanitise`.
      - An admission timeout gives `AdmissionDecision.refuse("CLUSTER_LOCK_TIMEOUT")`.
      - A cluster exception that is not a timeout still gives
        `OVERSIGHT_UNAVAILABLE`.
      - A budget cluster failure that is not a timeout still returns `false`.
- [ ] **Configurable timeout.**
      - `dataprism.hazelcast.lock-timeout` (or the D-070-17 name) binds with
        default `PT2S`.
      - Values below `PT100MS` or above `PT30S` refuse startup with
        `INVALID_LOCK_TIMEOUT`.
      - Setting it with `single-node` refuses with the existing
        `CLUSTER_SETTINGS_IGNORED`.
      - The bound value reaches all three stores: a context test with
        `PT200MS` and a held lock refuses within 700 ms.
      - The lock lease is derived from the timeout, and the doc states the rule.
- [ ] **[15b] Quorum.**
      - With `approval-minimum-members` greater than the visible member count,
        `consumeApproved`, `approve` and `reject` throw. `ToolAdmission` turns
        that into `OVERSIGHT_UNAVAILABLE`.
      - With enough members, the same calls succeed.
      - The default rules for `tcp-ip`, `none` and `kubernetes`, and the refusal
        codes `MISSING_APPROVAL_QUORUM` and `INVALID_APPROVAL_QUORUM`, each have
        a test in `ClusterConfigurationTest` or the new configuration test.
- [ ] **Expiry kept.** After `consumeApproved`, `approve` and `reject`, the
      entry's `EntryView.getExpirationTime()` still equals the request's
      `expiresAt` to within 1 s. An expired entry kept for retention is the
      exception: it uses the existing one-minute window.
- [ ] **Guards and existing tests.** `PropertyNamesFrozenTest`,
      `ConfigurationMetadataDocumentedTest`, `ApprovalStoreContractTest` and
      every other existing test pass. The frozen list and the metadata change by
      insertion only.
- [ ] **Docs.**
      - `docs/multiple-instances.md` states, under "Failure behaviour":
        - what a partition can still allow (the D-070-15 residual, in plain
          words, with the detection window);
        - that a consumed approval stays consumed after the heal;
        - that a held lock now refuses within `lock-timeout`;
        - that budgets and rate windows can still be lost or lowered by a merge;
        - that a supplied instance passed to `using()` gets none of the map
          settings.
      - The "Refusals" table gains each new startup code.
      - `docs/configuration.md` gains each new key row.
      - **[16a]** `docs/tools.md` "Admission codes" gains `CLUSTER_LOCK_TIMEOUT`.
- [ ] **Outcome.** The Outcome section of this file holds:
      - proposed `CHANGELOG.md` lines under Fixed and Added (task 201 writes the
        file);
      - a migration note (the new default timeout, plus the quorum default and
        its availability cost under [15b]);
      - every new name for task 200's register (keys, codes, the merge policy
        class);
      - the 0.8 note about sealed payloads and the merge policy;
      - the two mutation-proof runs.

## Out of scope
- Forcing Jet and phone-home off on a supplied `Config` or instance, the
  Hazelcast 5.7.2 bump, sealing, the consumed tombstone, the per-instance
  budget floor, the `dataprism.cluster.lock.timeout` metric and every other
  0.8 item in `docs/plan/design/cluster-security.md`.
- Bounding writes in `HazelcastOversightState`, `CachingSyntheticValueSource`
  and `ScopeIdentityIndex`. They take no lock themselves, but a lock someone else
  holds on one of their keys still blocks the write. Name this in the Outcome as
  a 0.8 item.
- Merge policies for the budget, rate-limit, oversight and identity maps (record
  the budget and rate-window loss in the docs. Do not fix it here).
- `OversightOperatorController` and every file under `data-prism-server/src/test/**`
  (task 190 owns the tests). The operator API keeps `OVERSIGHT_UNAVAILABLE`.
- `DefaultContextOrchestrator` and `docs/tools.md` outside the admission-codes
  section (tasks 190 and 193).
- `CHANGELOG.md`, `docs/migration-0.7.md` and `docs/architecture.md` (task 201),
  and `docs/plan/design/**` (tasks 197, 198 and 200).


## Outcome

Branch `task/202-hazelcast-fixes`. Runtime confirmation first: split-brain protection
(`SplitBrainProtectionConfig`, `ProtectOn.WRITE`) works in the open-source 5.7.0 jar with no
licence. A map write, `tryLock` and `replace` on a protected map threw
`SplitBrainProtectionException` on a member below the minimum, and reads still answered.

### Design as built
- Consume, approve, reject: read, then `IMap.replace(key, expected, next)`, then `setTtl`.
  `replace` resets the entry's expiry to none on 5.7.0, so `setTtl` restores it.
  Between the two a crash leaves an entry with no expiry (still LRU-evictable). The
  bounded `tryLock` is kept around the swap because a mutating call on a key someone
  else locked waits for that lock; the swap, not the lock, is what makes consumption single.
- `ClusterLocks.acquire` is `tryLock(timeout, lease = 5 x timeout)`. `consumeApproved`
  reads first (reads never wait on a lock) and locks only a matching entry.
- Async variants were not needed, so nothing is left parked on the member by a timed-out call.
- `ApprovalMergePolicy` ranks CONSUMED, REJECTED, EXPIRED, APPROVED, PENDING; ties keep the
  surviving copy; an unreadable entry is dropped. It takes `MergingValue<String>`, because
  `MapMergeTypes` makes Hazelcast demand per-entry statistics on the map.
- Quorum: `approval-minimum-members` of 1 configures nothing (any running member meets it).
  A minimum above 1 applies the protection, and a protected member refuses approval writes
  for about five seconds after it starts, until Hazelcast first evaluates the member list
  (seen on a solo member; a joining member is evaluated at once). That is why 1 is not configured.
- The real-heal test merges two same-name clusters in about 7 s with 5 s merge delays.

### Mutation proofs (both run on this worktree, then reverted)
1. `consumeApproved` with the lock removed and the `replace` swap replaced by an unconditional
   `set`: `ApprovalRaceTest.exactlyOneCallConsumesAnApproval` failed (BUILD FAILURE, 1 failure of 2).
2. `decide` with the lock removed and the swap replaced by an unconditional `set`:
   `ApprovalRaceTest.exactlyOneOfApproveAndRejectDecides` failed (1 failure of 2).

### Proposed CHANGELOG lines (task 201 writes the file)
Fixed:
- An approval can no longer be consumed twice across members: consumption is a compare-and-set,
  not a lock followed by a check.
- After a split cluster heals, an approval either side consumed, rejected or expired stays so
  (a merge policy on the approval map). Previously the larger side's `APPROVED` could win.
- A cluster lock held by another member or by something on the member port no longer hangs
  request threads. Every wait is bounded and refuses with `CLUSTER_LOCK_TIMEOUT`.
Added:
- `dataprism.hazelcast.lock-timeout` (default `PT2S`, `PT100MS` to `PT30S`); startup code `INVALID_LOCK_TIMEOUT`.
- `dataprism.hazelcast.approval-minimum-members` and split-brain protection on the approval map;
  startup codes `INVALID_APPROVAL_QUORUM`, `MISSING_APPROVAL_QUORUM`.
- Refusal code `CLUSTER_LOCK_TIMEOUT` (tool calls and the shared read budget).

### Migration note
- A call that waits for a cluster lock now refuses after two seconds by default instead of waiting.
- With `join.mode: tcp-ip` the approval minimum defaults to a majority of the distinct `join.members`,
  so that list must name every member including this one, and a two-member cluster stops approval-gated
  tools while either member is down (fail closed). Set `approval-minimum-members: 1` to opt out.
- New startup refusals apply only when approvals are in use (`dataprism.oversight.approval-required-tools` not empty OR
  `dataprism.reidentification.enabled`, which uses the same approval store; coordinator decision, consistent with D-070-15).
  When approvals are not in use no quorum is computed or required and the starter applies no protection. When in use:
  - a `tcp-ip` hostname member without `approval-minimum-members`: `MISSING_APPROVAL_QUORUM`;
  - a wildcard, other-octet range or bad-port `tcp-ip` member: `INVALID_APPROVAL_QUORUM`;
  - `kubernetes` without `approval-minimum-members`: `MISSING_APPROVAL_QUORUM`.
  A minimum that is below 1, above the countable members, or set with `none` is refused regardless.
- A budget read blocked by a lock returns `CLUSTER_LOCK_TIMEOUT` where it would have hung.
- A supplied `PrivacyCluster.using(instance)` gets neither the merge policy nor the quorum.

### New names for task 200's register
Keys: `dataprism.hazelcast.lock-timeout`, `dataprism.hazelcast.approval-minimum-members`.
Startup codes: `INVALID_LOCK_TIMEOUT`, `INVALID_APPROVAL_QUORUM`, `MISSING_APPROVAL_QUORUM`.
Refusal code: `CLUSTER_LOCK_TIMEOUT`. Classes: `ClusterLockTimeoutException` (core, `refusal`),
`ApprovalMergePolicy` and package-private `ClusterLocks` (hazelcast). Split-brain protection name
`dataprism.approval.quorum`. API: `PrivacyCluster.embedded(..., OptionalInt)`,
`ClusterMembership.defaultApprovalMinimumMembers()`, store constructors taking a `Duration`.
The 192 design's `dataprism.hazelcast.security.lock-timeout` is superseded by `dataprism.hazelcast.lock-timeout`.

### For 0.8
- A Hazelcast merge policy cannot read sealed payloads, so once values are sealed the consumed-wins
  rule must move to the tombstone (design 2.3.4) or the application.
- Writes in `HazelcastOversightState`, the `putIfAbsent` in `HazelcastApprovalStore.create` (it runs under the id lock, but not under a lock on its own entry key), `CachingSyntheticValueSource` and `ScopeIdentityIndex` still
  block on a lock someone else holds on their keys.
- Budget and rate maps still have the default merge policy.
- `data-prism-server` operator tests (task 190) that run an embedded cluster are unaffected by the
  quorum while the member is solo (minimum 1 configures nothing), but any test using `tcp-ip` lists
  will now meet the majority default.

### Review changes
- Member counting is `PrivacyCluster.countTcpIpMembers(List<String>)` (public static; new name for task 200's register),
  used by the startup default and by the validator. It expands commas, semicolons, spaces and last-octet IPv4 ranges,
  treats `a` and `a:5701` as one, and throws for forms it cannot expand reliably (validator: `INVALID_APPROVAL_QUORUM`).
  Second review: `countTcpIpMembers(entries, memberPort)` now takes the member port (portless = that port) and splits on
  `[,; ]+`; `PrivacyCluster.hasHostname` makes a hostname entry require an explicit minimum (`MISSING_APPROVAL_QUORUM`),
  and the validator checks countability for every `tcp-ip` configuration. `PrivacyClusterRefusal.Code` gained
  `INVALID_APPROVAL_QUORUM` and `MISSING_APPROVAL_QUORUM` (file outside Owns, added at the coordinator's instruction).
  `countTcpIpMembers` is for the starter, not applications.
  `ClusterMembership` is back to its original state; the default lives in package-private
  `PrivacyCluster.defaultApprovalMinimumMembers`. No coordinator grant was needed.
- A crash between `replace` and `setTtl` leaves an entry with no expiry until LRU eviction. The stored `expiresAt`
  still refuses it on every use, so this is not fail-open.
