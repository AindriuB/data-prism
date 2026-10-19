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
