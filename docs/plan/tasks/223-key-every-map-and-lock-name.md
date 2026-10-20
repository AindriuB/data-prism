# 223 — Derive every map key and lock key from the name key, and give processors a deterministic sealed write

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 222.
**Depends on:** 219 (answers 8, 9 and 10), 220 (`KeyedNames`, `Sealer`), 222 (every multi-member cluster now has keys)
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeKeys.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterLocks.java *(insertions only: bounded write helpers)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(the sealing context, member-local registration for processors and merge policies, solo-member keys per OD-215-8, factory overloads that add a `ClusterSecuritySettings` parameter and its accessor, and the `useLockTimeout` Javadoc only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PendingSweeper.java *(constructor and key derivation only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java, ClaimEntries.java, PendingSet.java, HazelcastOversightState.java, CachingSyntheticValueSource.java, ScopeIdentityIndex.java, HazelcastScopeBudget.java, HazelcastCallerRateLimiter.java *(the key-derivation and lock-key expressions only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/seal/SealedWrites.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecuritySettings.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ClusterSecuritySettingsTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/seal/SealedWritesTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/KeyedLockHoldTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/KeyedScopeEndTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/SoloMemberKeysTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastStoredValueBoundaryTest.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/** *(new: one checker per area, `IdentityMapsBoundary`, `ApprovalMapsBoundary`, `OversightMapsBoundary`, `CounterMapsBoundary`, `MemberCardMapsBoundary`)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/**/*.java *(expressions that build a raw map key or lock key only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java, data-prism-reidentification/src/test/java/**, data-prism-server/src/test/java/** *(expressions that build a raw map key or lock key only)*

## Goal
After this task no Hazelcast key, and no lock key, contains a scope id, subject id, principal, approval id, case id or pseudonym. Each is an HMAC under a subkey of the name key, scoped entries are prefixed by a 128-bit scope token, and prefix-based scope clean-up keeps working. Values stay as they are; tasks 224 to 228 seal them. This task also provides `SealedWrites`, the one way a store writes a sealed value through an entry processor. The value is sealed once, before the invocation, and the processor and its backup only compare and set exact bytes. A backup re-run or a retried invocation can therefore never produce different bytes. With that, the 232 rule "a retried commit recognises its own value" still holds once values are sealed. Every write this task touches is bounded by the lock timeout, which closes task 202's open "unbounded writes" item for keys.

## Context
- docs/plan/design/cluster-security.md 2.3.2 (keyed keys, scope token, lock keys, `tryLock`), 4.6 (the name key does not rotate), boundary 6 and T7, as amended by 218 (OD-215-2: the approval entry key keeps a scope token, and `dataprism.approval.id` keeps id-only lookup).
- ScopeKeys.java (all key shapes); ScopeIdentityIndex.endScope; HazelcastScopeBudget.forget; HazelcastApprovalStore.keysOf, locate, pendingKey; ClaimEntries; PendingSweeper (sweeps over `localKeySet`).
- docs/plan/tasks/retired/232-*.md, "Call sites": every lock-aware call and the lock it runs under. That table must stay true with keyed names.
- Task 219 answers 8 (backup re-execution, retry, TTL on the backup), 9 (member-local state in processors and merge policies) and 10 (which writes wait on a held lock).
- HazelcastStoredValueBoundaryTest.java:59-247: the single test that enumerates every map. It is the exhaustiveness guard this task splits into per-area checkers.
- docs/plan/PLAN.md, "Follow-ups from the final review of 216": `useLockTimeout` is last-store-wins. Folded in here as a Javadoc note.

## Required approach
- `ScopeKeys` takes `KeyedNames` and returns keyed strings only. The canonical inputs are the same parts, in the same order, as today. Scoped maps (identity, re-identification, budget, approval entries, oversight scope pauses) use `scoped(...)`. Claims use `mapKey(claims, approvalId)`. Pending entries use `mapKey(pending, kind NUL requester)`. The caller-rate map uses `mapKey(callerrate, principal)`.
- Every lock key is the keyed map key of the entry it guards. No lock is taken on a raw string.
- `SealedWrites` provides two processor shapes.
  - **Compare-and-set.** The processor carries the expected bytes, the new bytes (sealed once, by the caller) and the TTL.
  - **Computing.** The processor opens the stored value on the primary, computes the next plaintext, seals it once there, and returns the resulting bytes. Use this only where the next value depends on the stored one (`PendingSet` processors, counters).

  Both shapes define a backup processor that sets exactly the primary's resulting bytes, with the same TTL, and never seals. A re-run must be idempotent, or at worst over-count (a counter re-run on retry adds again; a pending reserve of the same id does not). Where answer 8 shows the default backup re-run is already byte-exact, document that and keep the explicit backup anyway.
- Writes that 219 answer 10 shows can wait without bound (`set`, `put`, `delete`, `putIfAbsent`, `replace`, `remove(k, v)`) run either under `ClusterLocks.acquire` on their key or through a timed variant (`tryPut`, `tryRemove`) with the store's lock timeout. A timeout fails closed in the store's existing way.
- `ClusterSecuritySettings` is an immutable record holding every runtime cluster-security setting that tasks 226 to 228 read: `pauseLease`, `clockSkewTolerance`, `unverifiedMemberAction`, `unverifiedMemberGrace` and `localFloorEnabled`, with the defaults and bounds of design 4.1 as amended by 218. The constructor refuses a value outside its bounds with `IllegalArgumentException` naming the component, never the value. `PrivacyCluster` holds one (the defaults unless supplied) and exposes it. No store reads it in this task; it exists now so that 226, 227 and 228 can run in parallel without each adding a field to a shared file. Task 230 binds the Spring properties to it.
- `PrivacyCluster` registers its `Sealer` and `KeyedNames` as member-local state where answer 9 shows processors and merge policies can reach them. It never puts them in replicated or serialised state.

## Acceptance
- [ ] `HazelcastStoredValueBoundaryTest` still enumerates every map and fails on an unknown map. It takes the map list from the `PrivacyCluster` constants (221), including the consumed-approval and member-card maps, so later tasks never edit it. Each area's checks live in its own `boundary/*Boundary.java` file; `MemberCardMapsBoundary` and the consumed-map part of `ApprovalMapsBoundary` assert, for now, that those maps are empty. Tasks 224 to 228 each own only their area's checker. For every map, every key equals the keyed name the test computes from the canonical inputs it wrote, and no key contains any of those inputs as a substring. The raw-value fixture check is kept.
- [ ] `KeyedScopeEndTest`, on a single member and on two members, shows `endScope`, `forgetScope` and budget `forget` remove exactly the entries of one scope and leave a second scope's entries.
- [ ] Every approval test class (`Approval*`, `Claim*`, `Pending*`), `ClusterLockTimeoutTest` and `ClusterOperationRefusalTest` passes. Only their raw-key expressions changed (`git diff` shows no assertion removed or weakened). `StalledInsert` pause points still fire at the same steps.
- [ ] T11 (keys share), in `KeyedLockHoldTest`: a Hazelcast client holding the lock on a budget key, an approval-id claim key and a caller-rate key, each computed as the keyed name, makes the matching operation refuse within `lock-timeout` with `CLUSTER_LOCK_TIMEOUT`, and no thread stays blocked.
- [ ] Every write listed by 219 answer 10 as unbounded is bounded. The Outcome holds a call-site table in 232's format (`file:line`, call, bounded by), covering the identity, re-identification, oversight and scope-end writes that 202 left unbounded. `grep -n "\.lock(" data-prism-hazelcast/src/main` returns nothing.
- [ ] `SealedWritesTest`, for both processor shapes: a run on a two-member cluster leaves byte-identical values on the primary and the backup. After the owner is killed mid-invocation, a retried compare-and-set finds its own bytes, and a retried computing processor is idempotent or over-counts, never under-counts. Mutating the backup processor to re-seal makes the byte-identity test fail (shown once in the Outcome).
- [ ] Solo members (`join.mode: none`, OD-215-8 (a)), in `SoloMemberKeysTest`: `PrivacyCluster.embedded(...)` with a `none` membership and supplied `ClusterKeys` derives every keyed map key and lock key from the supplied name key (the test recomputes them from that key). With no `ClusterKeys`, it starts and generates a random cluster key and name key per process: two such members started one after the other give different keyed names for the same canonical inputs, no key in any map contains a canonical input as a substring, and no generated key material appears in any log line, exception message or `toString`. A joining membership never gets generated keys (222's `MISSING_CLUSTER_KEY` still applies).
- [ ] `ClusterSecuritySettingsTest`: each default equals design 4.1 as amended by 218; each bound is checked at the bound, one unit inside and one unit outside; `clockSkewTolerance` above half of `pauseLease` is refused.
- [ ] `./mvnw -B verify` exits 0 over the full reactor, twice. Report the counts.

## Preserves
- Creation tokens: tokens stay in the values, and keys change only by derivation, so every release, removal, withdrawal, undo, takeover, sweep and merge still compares the full token.
- Claim map and validity rule: the claim key is `mapKey(claims, approvalId)`, so one id still has one claim across scopes. The claim value still names the (now keyed) entry key and token, and a claim is valid exactly while its named entry exists.
- Pending set: the owner-tagged slot logic is unchanged. Only the pending key is keyed.
- Atomic commit with TTL: `Commit` is unchanged in this task. Its expected and next values are still exact strings.
- Sweeps: they still run over `localKeySet` and skip locked keys with `tryAcquireNow`, on keyed lock keys.
- Bounded locks and both cluster codes: every lock still goes through `ClusterLocks`, and every new bound refuses with the existing codes.
- Audit-first approve and `ApprovalView` status: the store API and its exceptions are unchanged.

## Out of scope
- Sealing any value (tasks 224 to 228). Values may still hold raw identifiers until then; the boundary checkers still recompute them as today.
- Changing the approval protocol, merge policies or `PendingSet` logic (task 224).
- The pause document (task 228). Scope pauses stay keyed flags until then.
- `docs/` pages. Put the facts for 213 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented identifiers in tests. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A).
- No sleeps. Waits are on latches or polled state with a bound.
