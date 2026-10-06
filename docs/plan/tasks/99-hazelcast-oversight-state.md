# 99 — Back the oversight SPIs with Hazelcast, failing closed

**Repo:** `.`
**Depends on:** 95
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java *(the `endScope` method only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeKeys.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightState.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastCallerRateLimiter.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastStoredValueBoundaryTest.java *(insertions only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java *(new)*

## Goal

A pause, an approval or a rate-limit count held in one instance's memory does
not hold across a horizontally scaled deployment. This task implements task
95's three SPIs over the embedded Hazelcast member. Pausing on one instance
then pauses every instance, and an approval granted on one instance is
consumable on any instance exactly once. Like the read budget, and unlike the
identity cache, these fail closed: if the cluster is unreachable, they throw.

## Context

- `HazelcastScopeBudget.java` is the fail-closed shared-state precedent to
  follow. `CachingSyntheticValueSource` is the fail-open one, and is not the
  model here.
- `PrivacyCluster.java:40-75` declares the map-name constants and per-map
  config.
- `HazelcastStoredValueBoundaryTest.java:100-130` inventories every live map
  and rejects unknown ones. It is the exhaustiveness guard that any new map
  must be added to.
- `ScopeIdentityIndex.endScope` (`:66-75`) purges scope-prefixed keys from
  each map.
- `docs/architecture.md` boundary 6 says Hazelcast never holds raw sensitive
  values. Approval records may hold principal ids, purpose, case id, binding
  fingerprint, namespace and pseudonym. They must not hold a subject id.

## Acceptance

- [ ] `PrivacyCluster` declares three map constants, `OVERSIGHT_MAP`,
      `APPROVAL_MAP` and `CALLER_RATE_MAP`, each configured like the
      existing maps. Approval entries carry a TTL equal to the request's
      `expiresAt`.
- [ ] `HazelcastOversightState`, `HazelcastApprovalStore` and
      `HazelcastCallerRateLimiter` implement task 95's SPIs.
      `consumeApproved` and `tryAcquire` are atomic across members (entry
      processor or lock). A two-member test proves that two concurrent
      `consumeApproved` calls for one approval yield exactly one success.
- [ ] A test proves that a pause set through member A is observed through
      member B.
- [ ] With the Hazelcast instance shut down, every method of all three
      implementations throws a `RuntimeException` and returns no default. A
      test asserts this for each.
- [ ] `HazelcastStoredValueBoundaryTest` drives all three new maps,
      recognises them in its inventory, and rejects the distinctive raw-value
      fixture in their keys and values. The existing assertions are
      unchanged; the diff to that file is insertions only.
- [ ] `ScopeIdentityIndex.endScope(scopeId)` also removes that scope's
      approval entries and its scope-pause flag. A test asserts both are
      gone. `subjectFor` is unchanged.
- [ ] `mvn -pl data-prism-hazelcast -am verify` passes.

## Out of scope

- Choosing between in-memory and Hazelcast at startup. That is task 104.
- Any change to `subjectFor` or the identity cache.
- Persistence or MapStore. Both are refused by configuration today and stay
  refused.

## Notes carried forward from task 95's review

Task 95 merged with these two suggestions, which belong in the Hazelcast store
rather than in a second pass on the in-memory one. Make both hold in
`HazelcastApprovalStore` and, where the SPI allows, in a shared contract test
that runs against both implementations.

- `ApprovalStore.create` should require status `PENDING` and refuse a duplicate
  id. The in-memory store accepts either.
- `ApprovalStore.approve` should refuse a null approver.

## Attempt 1 — failed

Branch `task/99-hazelcast-oversight-state` (074d7ca). Reviewer: CHANGES (6/7 met, build not run by reviewer).

- Defect — HazelcastApprovalStore.java:54-59 with `locate` (~:209-213): the
  duplicate-id check is not atomic across scopes. Concurrent
  `create(id X, scope S1, alice, F1)` and `create(id X, scope S2, carol, F2)`
  both pass the `locate()` scan and both `putIfAbsent` (keys `S1 NUL X`,
  `S2 NUL X` differ). `find`/`approve`/`reject` then resolve "X" to whichever
  key `keySet()` yields first, so approving S2's reviewed request can approve
  S1's, and `consumeApproved` admits an unreviewed call. An approval for one
  request must never admit another.
- Fix: claim the bare id atomically (e.g. an id → scope `putIfAbsent` index, or
  a lock on the bare id around check-and-put), and make `locate` refuse with
  `UNKNOWN_APPROVAL` when an id matches more than one key. Add a two-member
  concurrent test with the same id in two scopes.
- Also fix (cheap, same files):
  - CALLER_RATE_MAP keeps LRU eviction at 100,000 entries/node; an evicted
    counter resets a caller's window and lets them exceed the limit. Use
    `EvictionPolicy.NONE` like OVERSIGHT_MAP; the 2-window TTL bounds size.
  - HazelcastStoredValueBoundaryTest fixture value
    `generated:CASE-42:PERSON_NAME:subject-42` contains the subject id, so the
    test cannot assert no subject id reaches the new maps. Use a value without
    it and add `doesNotContain(SUBJECT_ID)` for the three new maps (insertions
    only, unless changing the fixture line is unavoidable — say so).
  - HazelcastOversightState.snapshot() classifies by a `tool:` prefix; a scope
    id starting with `tool:` is misreported. Check the NUL separator first.
- Run the full reactor `mvn verify` and report the real exit code.
