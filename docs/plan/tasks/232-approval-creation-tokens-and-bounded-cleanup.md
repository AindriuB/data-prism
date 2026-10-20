# 232 — Give Hazelcast approvals creation tokens and bound every clean-up wait

**Repo:** .
**Release:** 0.7
**Branch:** from `origin/plan/0.7.0` (3ef687f8 or later). Lands on PR AindriuB/data-prism#134 before that PR merges.
**Depends on:** 216 (landed)
**Decided (owner, 2026-10-20):** D-0.7-HZ-2 "Fix all, with creation tokens".
**Owns:**
- data-prism-hazelcast/** (main and test)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ReidentificationWiring.java *(the `dataPrismReidentificationService` bean method at `:69-74` only: pass the lock timeout to `ScopeIdentityIndex`)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the `dataPrismClusterApprovalStore` bean method at `:107-110` only: pass `PrivacyMetrics`)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterLockTimeoutConfigurationTest.java *(insertions only: one test that the configured timeout reaches `ScopeIdentityIndex`)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(insertion only: one constant)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(insertion only: the new name in the expected set)*
- docs/multiple-instances.md ("Shared state" approval rows, "A lock someone holds" and "Approvals under a partition" only)
- docs/plan/tasks/232-approval-creation-tokens-and-bounded-cleanup.md (Outcome section only)

Disjoint from the open 0.7 tasks: 199 owns `docs/plan/perf/**` and `load-test/baselines/**`; 200 owns
`docs/plan/design/contract-register.md`; 201 owns `CHANGELOG.md`, `docs/migration-0.7.md`, `mkdocs.yml`,
`docs/architecture.md`, `docs/faq.md` and `docs/configuration.md` passages; 209 owns `PrivacyEngineWiring.java`,
`PrivacyExtensionPoints*.java`, `bean-inventory/*.txt` and two new tests. The bean inventory does not record bean
method parameters, so the two wiring edits here change no inventory file. The 1.0 tasks 204, 205, 212 and 213 also
touch `data-prism-hazelcast`, `ReidentificationWiring.java` and `docs/multiple-instances.md`; they branch from `main`
after 0.7 ships and so come after this task.

## Goal
A bug hunt over 3ef687f8 confirmed, with running tests, that the Hazelcast approval store still has waits with no
bound after a decision has committed, a decision that can report failure after it has changed state (which makes
re-identification audit DENY for an approval that is APPROVED and collectable, failing open), and clean-up paths that
identify a claim or a pending slot only by (scope, id), so a same-scope re-creation of a caller-supplied id can be
stripped of its guard. Give every creation a random token that every release, removal, withdrawal, undo, takeover,
sweep and merge compares. Bound every lock-aware call on the approval paths. Make each decision commit together with
its TTL, or not commit at all.

## Context
- **Hunt proof (read it first and adapt it):**
  `/private/tmp/claude-501/-Users-Andrew-workspace-data-prism--claude-worktrees-data-prism-eu-compliance-04cf83/339b800c-b811-482a-b7bb-f0e39b7b319c/scratchpad/src/data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HuntProofTest.java`.
  It asserts the defects (`assertThat(returned).isFalse()`, `assertThat(secondScope).isTrue()`), so the adapted tests
  invert those assertions. If the scratch file is gone, these are its three scenarios. Two members and `TIMEOUT` are
  used. A helper on member 1 calls `IMap.lock(key)` and holds it until a latch is released.
  1. `approveBlocksPastTheTimeoutBehindAHeldPendingLockAfterTheStatusChanged`: create `a-1` for `alice` and hold the
     pending key `pendingKey(TOOL_CALL, "alice")`. Then `approve` does not return within 20 × `TIMEOUT`, while
     `find` already shows `APPROVED`.
  2. `forgetPausedAfterDeleteReleasesTheSameScopeReplacementsClaim`: create `x-1` in `scope-a` for `alice` and hold
     alice's pending key. Start `forgetScope("scope-a")` and wait for the entry to vanish. Re-create `x-1` in `scope-a`
     for `carol` on member 1, then release the lock. The forget strips carol's claim, and `x-1` can then be created in
     `scope-b` while carol's approval is live.
  3. `forgetBlocksPastTheTimeoutBehindAHeldApprovalLock`: hold the approval entry's lock. Then `forgetScope` does not
     return within 20 × `TIMEOUT`.
- `data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java`:
  - `:114-176` `admit`. The undos are at `:139`, `:143` and `:157-159`. The post-insert `claims.get` is at `:170`, and
    a throw there leaves the entry, claim and slot in place behind `CLUSTER_OPERATION_REFUSED`.
  - `:194-220` `withdraw`, `unclaim` and `unreserve`. `:215` calls `executeOnKey` with no lock.
  - `:262-265` `leavePending`, reached from `decide` at `:378` and `:386` after the swap has committed.
  - `:338-361` `forgetScope` and the static `forget`. They take no lock, and `delete`, `executeOnKey` and `remove`
    are each unbounded.
  - `:363-391` `decide`. `:377` ignores the swap's result.
  - `:420-435` `swap`. `setTtl` at `:433` runs after `replace` has committed.
  - `:469-519` `Codec`, with 16 fields.
- Hazelcast 5.7 `EntryOperation` (behind `executeOnKey`), `delete`, `remove`, `replace`, `putIfAbsent` and `setTtl` are
  lock-aware and wait with no bound (`waitTimeout -1`) when another member holds the key's lock. `get`,
  `containsKey`, `keySet` and `values` do not wait. A `ReadOnly` entry processor does not wait either.
- `ClusterLocks.java:53-83` `acquire`, `tryAcquireNow` and `release`. The lease is `leaseMillis(timeout)`.
- `ClaimEntries.java:36-120`: claim, mark and finish, release, and sweep. The claim value is currently the plain entry
  key, and a marked claim is `K NUL ~ NUL new`.
- `PendingSet.java:24-150`: `Member(expiresAt, owner)`, `ownerOf(scopeId)`, `Reserve`, `Release`, `Sweep`, `union`
  and `CONFLICT`.
- `ApprovalMergePolicy.java:23-37` ranks by status only. `PendingMergePolicy.java:20-36`.
  `PrivacyCluster.java:394-395`: the claim map uses `PutIfAbsentMergePolicy`.
- `ScopeIdentityIndex.java:34-41, 65-77`. `endScope` calls the static `HazelcastApprovalStore.forget(cluster, scopeId)`.
  Its only production constructor call is `ReidentificationWiring.java:72`. The store is built at
  `ClusterBackedState.java:109` with `properties.getHazelcast().getLockTimeout()`.
- `ReidentificationService.approve` writes its ALLOW audit only after `ApprovalStore.approve` returns. A store call
  that hangs therefore writes no audit at all, and one that throws after committing is audited as DENY.
- `HazelcastScopeBudget.java:74-110` shows the entry-processor record pattern, and `FailSafeMetrics.wrap` shows how
  this module holds metrics.
- `StalledInsert.java` and `TestMembers.java` are the existing pause and injection helpers. Extend them rather than
  writing new ones.
- `docs/plan/tasks/retired/216-atomic-approval-creation-in-hazelcast.md`, Outcome: the claim protocol, the owner rule,
  the cap tolerance and the earlier sealing notes. Every guarantee stated there must still hold.
- `docs/multiple-instances.md:283-326` "A lock someone holds" ("The call waits at most
  `dataprism.hazelcast.lock-timeout`") and `:327-` "Approvals under a partition".

## Required approach
1. **Creation token.** Each `admit` generates a random token (`UUID.randomUUID()`). It is stored as a new last field
   of the approval entry value, in the claim value (`entryKey NUL token`, with the marked form extended to match) and
   in the pending slot owner (scope plus token, through `PendingSet.ownerOf(scopeId, token)`). `ApprovalRequest` does
   not change: the token is internal to the store. A value in the old format (16 fields, a bare entry-key claim, a
   scope-only owner) is unreadable and is refused, never guessed.
2. **Token comparison everywhere.** The following act only when the full token matches: `ClaimEntries.release`, the
   takeover's mark and finish (compare-and-set on the full claim value), the claim sweep's mark and remove,
   `PendingSet.Release`, the admit undos, `withdraw` (remove the entry at `entryKey` only if its token is this
   creation's, whatever its status), and `forget`'s releases (they use the token read from the entry being deleted).
   The takeover's staleness check stays "no entry at `entryKey`", which is stricter than a token match.
3. **Validity rule (fail closed).** An approval entry is usable by `find`, `findPending`, `approve`, `reject` and
   `consumeApproved` only while the claim for its id names its entry key and token. Otherwise it is treated as unknown:
   `decide` refuses with `UNKNOWN_APPROVAL`, and the reads skip it. This one rule covers a withdrawal that failed, a
   claim lost after the insert, a heal that kept the entry from one creation and the claim from another, and entries
   in the old format. The claim is read with `get`, which never waits.
4. **Decision commits with its TTL.** Replace `swap` with one entry processor on the approval map. It compares the
   stored value with the value read, and if they are equal it calls `ExtendedMapEntry.setValue(next, ttl, unit)` and
   returns true. A decision or a consume therefore either commits with its TTL or not at all. `decide` handles the
   expire-swap's result (`:377`): if the swap is lost, refuse with `NOT_PENDING` and release nothing. Otherwise
   release the slot and refuse with `EXPIRED`.
5. **Bounded slot release after a decision.** `decide` releases the approval entry's lock before it touches the
   pending key, so no thread ever holds the entry lock and the pending lock in the order opposite to `admit`. It then
   takes the pending key with `ClusterLocks.acquire` (bounded by the store's timeout) and runs `Release` while holding
   that lock. On a timeout or any failure it skips the release (an over-count, which the sweep removes), increments the
   new metric, and still returns the decision. It never reports failure for a decision that committed.
6. **Bounded forget and endScope.** `forget` becomes an instance method that uses the store's lock timeout and
   metrics. `ScopeIdentityIndex` gains a constructor that takes the lock timeout. The existing constructors default to
   `ClusterLocks.DEFAULT_TIMEOUT`. `ReidentificationWiring` passes `properties.getHazelcast().getLockTimeout()`.
   `endScope` then delegates to a store built with that timeout and the index's metrics. For each entry of the scope,
   `forget` does this:
   - take the entry's lock (bounded), read it, and remove it by compare-and-set on the value read;
   - only then take the pending key's lock (bounded) and release the slot by token;
   - then take the claim key's lock (bounded) and release the claim by token.

   If the entry's lock times out, that entry, its claim and its slot are left intact and the loop moves to the next
   entry. If a later lock times out, that release is skipped and the metric is incremented. A guard is never released
   while its entry exists. When the loop ends, if any entry was skipped, `forgetScope` and `endScope` throw
   `ClusterLockTimeoutException`. State is left over-counted, never half-released.
7. **Unknown claim state after insert (`:170`).** If the post-insert `claims.get` throws, try a withdrawal by token
   while the entry's lock is still held, then throw `CLUSTER_OPERATION_REFUSED`. If the withdrawal fails too, the
   entry stays. Under step 3 it is usable only if its claim names it. Even then it is a `PENDING` approval that still
   needs an operator decision, it counts against the cap, and it expires on its TTL. Document this rule in the
   `admit` Javadoc and in the docs.
8. **Merge rules.** For `ApprovalMergePolicy`, when both sides decode and their tokens differ, keep the existing side.
   This keeps the creation that is current in the surviving cluster and never resurrects the other. When the tokens
   are equal, the status rank applies as today. When a side is unreadable, the current behaviour stays (drop). For
   `PendingMergePolicy`, a member whose owner (scope and token) differs between the sides keeps the later expiry and
   gets `CONFLICT` (no owner), so neither creation can release it. The claim map keeps the existing claim when the
   values differ. Keep `PutIfAbsentMergePolicy` if it does exactly that, and state in the Outcome that you checked it.
   With step 3, a heal that pairs an entry with another creation's claim leaves the entry unusable, which fails closed.
9. **Bounded sweeps.** The pending sweep runs `Sweep` only on keys it can lock with `ClusterLocks.tryAcquireNow`, and
   skips the rest until the next interval, as the claim sweep already does.
10. **Metric.** Add `Metric.APPROVAL_RELEASE_SKIPPED("dataprism.approval.release.skipped")`. It counts every release
    that was skipped after a commit or a delete because a lock timed out or a call failed. The store takes a
    `PrivacyMetrics` through a new constructor and wraps it with `FailSafeMetrics`; the existing constructors use
    `PrivacyMetrics.none()`. `ClusterBackedState` passes the context's `PrivacyMetrics`. `dataprism.privacy.failclosed`
    is not reused, because `docs/tools.md` defines it as counting refused calls only.

## Acceptance
- [ ] Each finding has a test that fails on 3ef687f8 and passes after the fix. Record the failure output of each
      against 3ef687f8, without committing the revert, in the Outcome:
  - [ ] (approve or reject behind a held pending lock, adapted from scenario 1) With alice's pending key locked from
        another member, `approve` and `reject` each return within 3 × `TIMEOUT`, the status is `APPROVED` or
        `REJECTED`, `APPROVAL_RELEASE_SKIPPED` was incremented once, and a new create for alice at cap 1 is still
        refused until the lock is released and the sweep runs.
  - [ ] (forget behind a held lock, adapted from scenario 3) With the approval entry's lock held, `forgetScope` and
        `ScopeIdentityIndex.endScope` each throw `ClusterLockTimeoutException` within 3 × `TIMEOUT` × (entries in
        scope). The entry, its claim and its slot are all still present. A second test holds the pending key instead:
        the entry is gone, the slot is still counted, the metric was incremented, and the id can be re-created after
        the sweep.
  - [ ] (swap TTL) A test makes the TTL step fail on 3ef687f8. For example, inject a throw after `replace` through
        the test seam, or drop the write quorum between the two calls. After the fix, approve, reject and consume
        either return success with the entry carrying the new TTL, or throw with the entry unchanged. The test asserts
        that no outcome leaves the status changed while the call threw.
  - [ ] (ABA, adapted from scenario 2) After the paused forget completes, carol's claim and slot are intact, and
        creating `x-1` in `scope-b` throws `IllegalArgumentException("duplicate approval id")`. Further tests cover
        a stale admit undo (`:139`, `:157-159`) and a stale `withdraw` against a same-scope re-creation. In each, the
        re-creation keeps its claim and slot, and no id is live in two scopes.
  - [ ] (merge) `ApprovalMergePolicyTest`: tokens differ, so the existing side is kept for every pair of statuses,
        in both argument orders. Tokens are equal, so the status rank applies. `PendingMergePolicy`: an owner that
        differs only by token gives `CONFLICT`. A claim-map heal with differing values keeps the existing claim. One
        split-brain heal test pairs an entry with another creation's claim and asserts that `decide` refuses
        `UNKNOWN_APPROVAL` and `consumeApproved` returns empty.
  - [ ] (`:170`) `claims.get` is injected to throw after a successful insert. The create throws
        `CLUSTER_OPERATION_REFUSED`, and the approval map holds no entry for that id. With the withdrawal also
        injected to fail, the leftover entry is refused by `decide` whenever its claim does not name its token, and
        the id cannot be created in another scope.
  - [ ] (`:377`) If the expire-swap loses to a concurrent decision, `decide` throws `NOT_PENDING` and the winner's
        slot release is the only release.
- [ ] No unbounded wait remains in the approval paths. Every lock-aware Hazelcast call (`executeOnKey` with a
      non-ReadOnly processor, `put*`, `replace`, `remove`, `delete`, `set*`) in `HazelcastApprovalStore`,
      `ClaimEntries`, `PendingSet`, `PendingSweeper`, `ScopeIdentityIndex.endScope`'s approval part and
      `PrivacyCluster`'s sweep entry points, on a key another member may lock, runs while this thread holds that key's
      lock, taken by `ClusterLocks.acquire` or `tryAcquireNow`. The Outcome has a table of every such call site
      (`file:line`, the call, and how it is bounded), plus every `get`, `containsKey`, `keySet`, `values` and
      ReadOnly-processor call, listed as non-waiting.
- [ ] `grep -n "setTtl" data-prism-hazelcast/src/main` returns nothing.
- [ ] `HazelcastApprovalStore.forget` is not static. `ScopeIdentityIndex` has a constructor that takes a `Duration`.
      `ReidentificationWiring.java` passes the configured lock timeout to it, and the inserted
      `ClusterLockTimeoutConfigurationTest` case shows that a held approval lock makes `endScope` refuse within the
      configured timeout, not the default.
- [ ] `PrivacyMetricsTest` lists `dataprism.approval.release.skipped`, and `MicrometerPrivacyMetricsTest` passes
      unchanged.
- [ ] `docs/multiple-instances.md` is true of the code:
  - "A lock someone holds" states the bound per lock wait. A call that takes n locks waits at most n ×
    `lock-timeout` in total; name the calls with more than one lock (create, forget, endScope).
  - It states that approve and reject never wait unboundedly after they commit, that a release skipped on a timeout
    only over-counts and is counted in `dataprism.approval.release.skipped`, and that forget and endScope refuse with
    `CLUSTER_LOCK_TIMEOUT` and leave the skipped approvals in place.
  - "Approvals under a partition" describes the token, the validity rule (step 3) and the merge rules (step 8).
  - The Upgrade caution says that approvals, claims and pending entries written before the upgrade are unreadable
    after it and are refused (re-request them), and that no member of the old version may run with members of the
    new one.
  - No sentence says "compliant".
- [ ] The Outcome has a section "Value formats (for the 0.8 sealing, task 215, and the 0.7 migration note, task
      201)". It gives the old and new format of the approval entry, the claim and the pending member owner, states
      that the token is random and carries no personal data, and states which fields are still clear (principal,
      approval id, scope). It also gives the rolling-upgrade caution as a migration-note line for task 201, and
      proposed `CHANGELOG.md` lines (`### Fixed`, and `### Added` for the metric). It does not edit `CHANGELOG.md`.
- [ ] `./mvnw verify` (full reactor) exits 0, and `./mvnw -Prelease -DskipTests package` exits 0. Record the test
      counts in the Outcome.

## Out of scope
- The re-identification cache, the identity map, budget, oversight and pause-flag deletes in `endScope`. Their waits
  are an already documented limitation and stay so; say so in the docs without changing it.
- `ReidentificationService`, `ToolAdmission` and `OversightOperatorController`. A store call that now returns
  promptly is the fix. Mapping `CLUSTER_OPERATION_REFUSED` to a client code belongs to task 201's migration note.
- `ApprovalRequest`, `ApprovalStore`, `InMemoryApprovalStore` and `ApprovalRefusedException.Code`. The token stays
  internal, and adding a code breaks exhaustive switches.
- Sealing or encrypting any approval value, which belongs to 0.8 (task 215's successors). Record what it must cover
  instead.
- `HazelcastScopeBudget` and `HazelcastCallerRateLimiter`.
- `CHANGELOG.md`, `docs/architecture.md`, `docs/configuration.md` and `docs/tools.md` (task 201).
- Any other `@Bean` method in `ReidentificationWiring.java` or `ClusterBackedState.java`.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Test identities, scopes and IDs are invented and neutral (`alice`, `scope-a`, `x-1`). Use no real person,
  organisation, workplace or business domain.
- In anything you write, say that the product "supports" a requirement, never that it is "compliant".
- No fixed sleeps in new tests. Wait on latches or on observable state, as `StalledInsert.awaitFree` does.

## Outcome

Branch `task/232-approval-tokens`, cut from `f5906239` (which contains 3ef687f8). Nothing outside `Owns` was edited.

### What changed

- **Creation token.** `admit` generates `UUID.randomUUID()` and stores it as the 17th field of the approval entry, as
  the second part of the claim value (`entryKey NUL token`) and in the pending owner (`scope US token`).
  `ApprovalRequest` is unchanged. Values in the old forms (16 fields, a bare entry-key claim, a scope-only owner) are
  unreadable and refused.
- **Token comparison.** `ClaimEntries.release`, the takeover's mark and finish, the claim sweep's mark and remove,
  `PendingSet.Release`, every admit undo, `withdraw` (now by token, whatever the status) and `forget`'s releases (token
  read from the entry being deleted) compare the whole token. The takeover's staleness check is still "no entry at
  `entryKey`".
- **Validity rule.** `find`, `findPending`, `approve`, `reject`, `consumeApproved` (and `pending(Instant)`, which would
  otherwise list entries nobody can decide) use an entry only while `claims.get(id)` equals `entryKey NUL token`
  exactly (an unmarked claim). Otherwise it is unknown.
- **Decision commits with its TTL.** `swap` is gone. `commit` is one `Commit` entry processor on the approval map
  that compares the stored value and calls `ExtendedMapEntry.setValue(next, ttl, unit)`. `decide` refuses
  `NOT_PENDING` and releases nothing when the expire-swap is lost.
- **Bounded release after a decision.** `decide` unlocks the entry first, then takes the pending key's lock with
  `ClusterLocks.acquire`, runs `Release`, and on a timeout or failure skips it, increments
  `APPROVAL_RELEASE_SKIPPED` and still returns the decision.
- **Bounded forget and endScope.** `forgetScope` is an instance method (the static `forget` is gone). For each entry:
  entry lock (bounded), compare-and-set remove, then the pending lock and release by token, then the claim lock and
  release by token. An entry whose lock times out is left whole; at the end `ClusterLockTimeoutException` is thrown.
  `ScopeIdentityIndex(cluster, metrics, Duration)` is new, the old constructors default to
  `ClusterLocks.DEFAULT_TIMEOUT`, and `endScope` delegates to a store built by the package-private
  `HazelcastApprovalStore.forScopeEnd` (it does not call `PrivacyCluster.useLockTimeout`, so the index's default
  cannot override the timeout the application's store announced).
  `ReidentificationWiring` passes the configured timeout.
- **`:170`.** A throw from the post-insert `claims.get` withdraws the entry by token while the entry lock is held,
  then rethrows (`CLUSTER_OPERATION_REFUSED`). Documented in the `admit` Javadoc and `docs/multiple-instances.md`.
- **Merge.** `ApprovalMergePolicy`: differing tokens keep the existing side, equal tokens use the status rank,
  an unreadable side is dropped. `PendingMergePolicy`/`PendingSet.union` already gave `CONFLICT` for owners that
  differ; the owner now includes the token, so a token-only difference gives `CONFLICT`. Claim map: checked
  `com.hazelcast.spi.merge.PutIfAbsentMergePolicy.merge(merging, existing)`: it returns `existing.getValue()` whenever an
  existing value is present, so it keeps the existing claim when the values differ. `ApprovalGuardMapsTest` proves
  it on the real class and the map is still configured with `"PutIfAbsentMergePolicy"`.
- **Sweep.** `PendingSweeper.sweep` runs `Sweep` only on keys it gets with `ClusterLocks.tryAcquireNow`.
- **Metric.** `Metric.APPROVAL_RELEASE_SKIPPED("dataprism.approval.release.skipped")`;
  `HazelcastApprovalStore(cluster, timeout, PrivacyMetrics)` wraps it with `FailSafeMetrics`;
  `ClusterBackedState.dataPrismClusterApprovalStore` passes `ObjectProvider<PrivacyMetrics>.getIfAvailable(none)`.

### Failure output against 3ef687f8

The new tests were copied into a scratch worktree at 3ef687f8 (removed afterwards, never committed) with a few stubs
so they compile: the metric constant, the constructors that take a metrics object or a `Duration` (arguments ignored),
`Codec.tokenOf` (a constant), `Codec.encode(request, token)` (ignores the token), `ClaimEntries.value` (the bare
entry key), `ClaimEntries.read`, and `PendingSet.ownerOf(scope, token)` (the scope). The stubs add no behaviour, so
every failure below is the old behaviour. 22 of the 23 tests in the two new classes fail there; the one that passes is
the control (`aDecisionWhoseLockIsFreeReleasesItsSlotAndCountsNothingSkipped`).

| Finding | Test | Failure on 3ef687f8 |
|---|---|---|
| approve / reject behind a held pending lock | `aDecisionBehindAHeldPendingLockCommitsAndReturnsWithinTheBoundAndOnlyOverCounts` (both) | `AssertionError: the call did not return within PT0.9S` |
| expired decision behind a held pending lock | `anExpiredDecisionBehindAHeldPendingLockStillRefusesExpiredWithinTheBound` | `Expecting ... ExecutionException but was TimeoutException` |
| forget behind a held approval lock | `forgetBehindAHeldApprovalLockRefusesWithinTheBound...` | `the call did not return within PT1.8S` |
| endScope behind a held approval lock | `endScopeBehindAHeldApprovalLockRefusesWithinTheBound...` | `the call did not return within PT0.9S` |
| forget behind a held pending key | `forgetBehindAHeldPendingKeyRemovesTheEntryKeepsTheSlotCounted...` | `the call did not return within PT0.9S` |
| pending sweep behind a held lock | `thePendingSweepSkipsAKeyWhoseLockIsHeld...` | `expected: 0 but was: 1` (it swept a key someone held, waiting for the lock) |
| swap TTL, `setTtl` fails | `aFailingStepNeverLeavesAStatusChangedWhileTheCallThrew[1]` | `[t-approve after a call that threw] expected: PENDING but was: APPROVED` |
| swap TTL, `replace` / `executeOnKey` fail | `...[2]`, `...[3]` | `[t-approve threw] Expecting value to be false but was true` / `... true but was false` (the old path depends on `replace` and `setTtl`, not on the processor) |
| ABA, forget paused after delete | `forgetPausedAfterItsDeleteLeavesAReCreationInTheSameScopeItsClaimAndItsSlot` | `expected: "scope-a x-1" but was: null` (carol's claim was released) |
| stale undo after the cap refused | `aStaleUndoAfterTheCapRefusedItLeaves...` | `expected: "scope-a x-1" but was: null` |
| stale undo after the insert failed | `aStaleUndoAfterTheInsertFailedLeaves...` | `expected: "scope-a x-1" but was: null` |
| stale withdrawal | `aStaleWithdrawalAfterTheClaimWasLostLeaves...` | `Expecting code to raise a throwable`: with a bare `entryKey` claim the paused creator's claim check passed against the re-creation's claim, so both creates returned success for one id |
| `:170` | `anUnreadableClaimAfterTheInsertWithdrawsTheEntryAndRefuses` | `Expecting empty but was: ["scope-a x-1"]` |
| `:170`, withdrawal fails too | `anUnreadableClaimAfterTheInsertWithAFailedWithdrawal...` | `Expecting an empty Optional but was containing value: ApprovalRequest[...x-1...]` (a claim that does not name the token did not make it unknown) |
| validity | `anEntryWhoseClaimIsGoneOrIsAnotherCreationsIsUnknownToEveryRead`, `anApprovedEntryWhoseClaimIsAnotherCreationsCannotBeConsumed` | `Expecting an empty Optional but was containing value` |
| heal pairing | `aHealThatPairsAnEntryWithAnotherCreationsClaimLeavesItUnusable` | `Expecting an empty Optional but was containing value` |
| old formats, token | `valuesInTheOldFormsAreUnreadableAndRefused`, `theTokenIsRandomPerCreationAndHoldsNoPersonalData` | `IllegalStateException: unreadable approval entry` (stub), `Expecting ... not to be equal to ...` (stub) |
| `:377` | `anExpireSwapThatLosesToAConcurrentDecisionRefusesNotPendingAndReleasesNothing` | fails at its set-up assertion (`Expecting value to be true but was false`): the test stops the decision at its first entry processor on the approval map, which only exists after the fix. The old defect is visible in the source (`HazelcastApprovalStore.decide`, `swap(...)` result ignored, then `leavePending`), not in a running test on 3ef687f8 |

The merge tests (`ApprovalMergePolicyTest`, `ApprovalGuardMapsTest`) assert the new token forms and do not compile
against 3ef687f8, so no old-code output exists for them. The wiring test
(`ClusterLockTimeoutConfigurationTest.the_configured_lock_timeout_reaches_the_scope_end_of_the_reidentification_index`)
was shown to fail by temporarily passing no timeout to `ScopeIdentityIndex` (the held lock refused after the default two
seconds, not within 700 ms), and then restored.

The three hunt scenarios correspond to: scenario 1 to the approve/reject row, scenario 2 to the ABA row, scenario 3 to
the forget row.

### Call sites (`file:line` at the final commit)

Lock-aware calls. Every one runs while the calling thread holds that key's lock, taken by `ClusterLocks.acquire`
(bounded by `lockTimeout`) or `tryAcquireNow` (no wait).

| Call site | Call | Bounded by |
|---|---|---|
| `HazelcastApprovalStore.java:185` | `pendingSets.executeOnKey(Reserve)` | admit `HeldLocks.take(pendingSets, pendingKey)` `:178` |
| `HazelcastApprovalStore.java:202` | `approvals.putIfAbsent` | admit `HeldLocks.take(approvals, entryKey)` `:179` |
| `HazelcastApprovalStore.java:262` | `approvals.remove(entryKey, raw)` (`withdraw`) | the admit lock on the approval entry `:179` |
| `HazelcastApprovalStore.java:277` | `ClaimEntries.release` (`unclaim`) | admit claim lock `:177` |
| `HazelcastApprovalStore.java:289` | `pendingSets.executeOnKey(Release)` (`unreserve`) | admit pending lock `:178` |
| `ClaimEntries.java:97, 106, 110, 113` | `claims.putIfAbsent`, `replace` (take over) | admit claim lock `:177` (called from `:180`) |
| `ClaimEntries.java:121` | `claims.remove(id, value)` (`release`) | admit `:177`, or `releaseClaim` `:360` |
| `HazelcastApprovalStore.java:348` | `pendingSets.executeOnKey(Release)` (`leavePending`) | `lockOrSkip` `:344` (`acquire`, `:375`) |
| `HazelcastApprovalStore.java:364` | `ClaimEntries.release` (`releaseClaim`) | `lockOrSkip` `:360` |
| `HazelcastApprovalStore.java:625` | `approvals.executeOnKey(Commit)` | `acquire` `:426` (consume) and `:529` (decide) |
| `HazelcastApprovalStore.java:505, 511` | `approvals.remove(key, raw)` (`forgetOne`) | `acquire` `:491` |
| `ClaimEntries.java:154, 161, 166, 169` | `claims.replace` / `claims.remove` (sweep) | `tryAcquireNow` `:133` |
| `PendingSweeper.java:95` | `pending.executeOnKey(Sweep)` | `tryAcquireNow` `:91` |
| `PrivacyCluster.java:108, 113` | `sweepPendingApprovals`, `sweepStaleClaims` | delegate to the two rows above; no call of their own |
| `ScopeIdentityIndex.endScope`, approval part | `approvals.forgetScope` | the `forgetOne` rows above |

Non-waiting calls (`get`, `containsKey`, `keySet`, `values`, `entrySet`, `localKeySet`; no ReadOnly processors are used):
`HazelcastApprovalStore.java:197, 221` (`claims.get`), `:244` (`containsKey`), `:258` (`get`), `:421, 428, 497, 531, 568, 656`
(`approvals.get`), `:451` (`entrySet`), `:477, 643, 647` (`keySet`), `:610` (`claims.get`);
`ClaimEntries.java:102, 109, 152, 165` (`containsKey`), `:132` (`localKeySet`), `:146` (`claims.get`);
`PendingSweeper.java:83` (`localKeySet`); `ScopeIdentityIndex.java` `subjectFor` (`get`) and the `keySet` of the
other maps in `endScope`. `ClusterLocks` `tryLock` (bounded by its timeout, or zero) and `unlock` wait for nothing.
The `endScope` deletes of the identity, re-identification, budget and oversight maps are the unchanged, documented
limitation; `docs/multiple-instances.md` says so.

`grep -n "setTtl" data-prism-hazelcast/src/main` returns nothing.

### Value formats (for the 0.8 sealing, task 215, and the 0.7 migration note, task 201)

| Value | Old (0.6, 3ef687f8) | New |
|---|---|---|
| Approval entry (`dataprism.approval`, key `scope NUL id`) | 16 NUL-separated, escaped fields: approvalId, kind, requesterPrincipalId, requesterClientId, scopeId, tool, bindingFingerprint, namespace, syntheticValue, purpose, caseId, createdAt, expiresAt, status, approverPrincipalId, decidedAt | the same 16 fields, then a 17th: the creation token (`=` and the 36-character UUID) |
| Id claim (`dataprism.approval.id`, key `id`) | `scope NUL id`; marked: `scope NUL id NUL ~ NUL scope2 NUL id` (or `... NUL ~ NUL` for a sweep) | `scope NUL id NUL token`; marked: `scope NUL id NUL token NUL ~ NUL scope2 NUL id NUL token2` (or `... NUL ~ NUL` for a sweep) |
| Pending member owner (`dataprism.approval.pending`, value `id NUL expiry NUL owner ...`) | `scope` (empty for a null scope) | `scope US token`, where `US` is U+001F, or `\u0001conflict` after a heal |

The token is random (`UUID.randomUUID()`), derived from nothing, and carries no personal data. The fields still in
clear are the principal ids, client id, approval id, scope, tool, fingerprint, namespace and synthetic value in the
entry; the approval id and scope in the claim; and the principal (in the key), the approval id and the scope in the
pending map. Sealing in 0.8 must cover the whole entry value, the whole claim value and the whole pending value, and
`ApprovalMergePolicy`, `PendingMergePolicy` and `ClaimEntries` read those strings, so the token comparison has to
move to a field the merge can still read (or the merge has to unseal).

Migration note line for task 201: "Approvals, id claims and pending entries written by 0.6 are unreadable by 0.7 and
are refused (re-request approvals). No 0.6 member may run together with 0.7 members. The maps are in memory only, so
stop every member and start 0.7; do not roll."

Proposed `CHANGELOG.md` lines (not edited here):

```
### Fixed
- Hazelcast approvals: every creation now carries a random token that every release, removal, withdrawal, undo,
  takeover, sweep and merge compares, so a clean-up that belongs to one creation of an id can no longer strip the
  claim or pending slot of a later creation of the same id in the same scope. An approval is usable only while the
  claim for its id names its entry and token. Approvals, claims and pending entries written before this version are
  unreadable and refused; do not run old and new members together.
- Hazelcast approvals: approving or rejecting no longer waits for ever, or reports failure after it has committed,
  when the requester's pending entry is locked; the release is bounded and an over-count is left for the sweep. A
  decision now commits its new value and its expiry in one step. Ending a scope or forgetting its approvals is
  bounded and refuses with CLUSTER_LOCK_TIMEOUT, leaving the approvals it could not lock in place. The pending
  sweep skips keys that are locked.

### Added
- Metric `dataprism.approval.release.skipped`: pending-slot or id-claim releases skipped after a commit or delete
  because a lock timed out or a call failed.
```

### Test counts

Counted from the surefire and failsafe XML under every module's `target`, exit codes captured directly:

- `./mvnw -B verify` (full reactor): exit 0, `BUILD SUCCESS`. 1949 tests, 0 failures, 0 errors, 0 skipped
  (1932 surefire, 17 failsafe).
- `./mvnw -B -Prelease -DskipTests package`: exit 0, `BUILD SUCCESS`.
- `./mvnw -B -pl data-prism-hazelcast -am verify`, twice more for flakiness: exit 0 both times. 197 tests in the
  hazelcast module each time, 0 failures, 0 errors, 0 skipped.
- New and changed tests: `ApprovalBoundedCleanupTest` (13), `ApprovalCreationTokenTest` (10), additions to
  `ApprovalMergePolicyTest`, `ApprovalGuardMapsTest`, `PrivacyMetricsTest` (name added to the set) and
  `ClusterLockTimeoutConfigurationTest` (one case; 27 tests in that class). `MicrometerPrivacyMetricsTest` is unchanged
  and passes.

### Noted, not changed

- Old-format id claims (a bare entry key) are never swept, so such an id stays unusable until the claim map is emptied
  (stopping every member does that). Sweeping them is possible but the task says unreadable values are refused.
- A lock lease (five times the timeout) can still lapse during a paused call, and a lock-aware call made after that
  waits as Hazelcast does for a lock someone else now holds. The task's rule is "while this thread holds that key's
  lock"; a paused thread past its lease is outside it, as before. Correctness never relies on the lock.
- `pending(Instant)` now skips unreadable and unnamed entries instead of throwing on an unreadable one. The task did
  not list it among the validity rule's callers; it follows the same rule so the operator list shows only approvals
  that can be decided.
