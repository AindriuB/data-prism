# 224 — Seal approval entries, claims and pending slots, and add consumed tombstones

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 223.
**Depends on:** 219 (answers 8 and 9), 223 (keyed names, `SealedWrites`, per-area boundary checkers)
**Decisions:** OD-215-12 decided 2026-10-20 (a): 100,000 ids, evicting the earliest-expiring.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClaimEntries.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PendingSet.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PendingSweeper.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ApprovalMergePolicy.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PendingMergePolicy.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ApprovalMaps.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ConsumedApprovals.java *(new: tombstones and the in-memory consumed set)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/ApprovalMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/Approval*.java, Claim*.java, Pending*.java *(existing: changes only where a stored value is read or written raw)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalForgeryTest.java *(new, T1)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalReplayTest.java *(new, T5)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalConsumeRaceTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalSpliceTest.java *(new, T2 at entry level)*

## Goal
After this task the three approval maps hold only `dps1` envelopes, and a member or client without the cluster key can neither read nor forge an approval, a claim or a pending slot. Consumption also writes a sealed tombstone, and every instance remembers the ids it consumed, so restoring a captured `APPROVED` value is not enough to reuse an approval (design 2.3.4). The consumed-wins rule from task 202 moves from the merge policy, which can no longer read the values, to the tombstone. Every 0.7 approval property survives with sealed values.

## Context
- docs/plan/design/cluster-security.md 1.5, 2.3.1 (per-structure failure: `approval` reports not found), 2.3.4, 2.3.7, as amended by 218 (inventory of `dataprism.approval.id` and `.pending`, OD-215-2 key shape).
- Task 219 Outcome, answer 8 (backup re-execution, retry, TTL on the backup) and answer 9 (whether a merge policy can reach the member-local `Sealer`).
- Task 223 Outcome: `SealedWrites` (compare-and-set and computing shapes), the call-site table, `ApprovalMapsBoundary`.
- docs/plan/tasks/retired/232-approval-creation-tokens-and-bounded-cleanup.md, "Value formats", "Call sites" and "Re-review fixes": creation tokens, claim validity, owner-tagged pending slots, a retried `Commit` recognising its own value.
- docs/plan/tasks/retired/202-*.md, "For 0.8": the consumed-wins rule must move to the tombstone. retired/216-*.md, "TTL tried and reverted": a merge copies the merging side's TTL.
- docs/plan/tasks/retired/235-*.md: audit-first decisions. retired/236-*.md: `ApprovalView` effective status.
- docs/plan/PLAN.md, "Follow-ups from the final review of 216": the `PendingSet` CONFLICT sentinel `"\u0001conflict"` could collide with a scope id. Folded in here, because the value format changes anyway.

## Required approach
- Every value in `dataprism.approval.s1`, `dataprism.approval.id.s1` and `dataprism.approval.pending.s1` is sealed through `Sealer`, with the canonical key parts and the creation token inside the plaintext and `expiresAt` authenticated as 218 fixed under OD-215-9. Every write goes through `SealedWrites`: `Commit` and the claim writes are compare-and-set on exact sealed bytes; `PendingSet` processors are computing processors.
- A value that does not open (tampered, unsupported, wrong key) is "not found" on every read path, counted by the sealer, and never an exception. A value past its authenticated `expiresAt` is expired whatever its cluster TTL says.
- `consumeApproved` writes a sealed tombstone to `dataprism.approval.consumed.s1`, keyed `mapKey(consumed, approvalId)`, with a TTL to `expiresAt`, in the same bounded step that marks the entry consumed. It checks the tombstone and the local consumed set before it consumes.
  - The tombstone and the entry are two keys in two maps, so no single processor covers them. The whole check, tombstone write and consume step runs under one lock whose scope is the approval id, at least as wide as the tombstone's key: the claim lock, keyed `mapKey(claims, approvalId)` (223), taken through `ClusterLocks` and bounded by the lock timeout (`CLUSTER_LOCK_TIMEOUT` on expiry). An entry-key lock alone is not enough, because a spliced or restored entry under another entry key carries the same approval id. If the 232 call-site table already holds that lock for consume, reuse it and say so; otherwise the Outcome adds it to the table.
  - Order and partial failure: the tombstone is written first, then the entry is marked consumed. If the tombstone write fails or is refused, nothing is consumed and `consumeApproved` refuses with the cluster code (the approval stays approved, never granted twice). If the tombstone is written and marking the entry fails, the approval reads as consumed everywhere (the tombstone wins) and the call refuses; the failure never leaves an approval that is both usable and tombstoned as unused. Both are checked by `find` and `approve` as well, so a restored `APPROVED` value with a tombstone or a local record reads as consumed.
- The local consumed set is bounded as decided under OD-215-12 and is memory only.
- Merge policies. If 219 answer 9 shows a merge policy can reach the member-local `Sealer`, `ApprovalMergePolicy` and `PendingMergePolicy` open both sides and keep their 0.7 rules. If it cannot, `ApprovalMergePolicy` keeps the existing entry (`PutIfAbsent` semantics) and consumed-wins is enforced only by the tombstone, which a merge cannot drop because tombstones are keyed per id and either side's tombstone is enough; `PendingMergePolicy` is replaced by the owner-tag rule evaluated on the next read, as the Outcome describes. A merge policy that cannot open a value never chooses it over one it can. Record which design was built and why.
- The CONFLICT marker no longer shares a value space with scope ids: a conflict is its own sealed record type.

## Acceptance
- [ ] T1, in `ApprovalForgeryTest`: a second embedded member with the cluster name and no key, a Hazelcast client with no key, and a member holding a different cluster key each write into all three approval maps a plausible plaintext value, a 0.7-format value, a random string in envelope grammar and a valid envelope copied from another key. `create`, `approve`, `reject`, `consumeApproved`, `find`, `pending` and the operator views never return any of them as usable. `seal.tamper` or `seal.unsupported_format` increments for each.
- [ ] T2 at entry level, in `ApprovalSpliceTest`: a valid sealed approval copied to another approval id, another scope's entry key, the claim map, or written back with a later `expiresAt` is not usable; an entry past its sealed `expiresAt` with the cluster TTL still running is expired.
- [ ] T5, in `ApprovalReplayTest`: after `consumeApproved`, restoring the captured `APPROVED` value does not allow a second consume, on the consuming instance and on another instance. Deleting the tombstone as well still fails on the consuming instance (local set). The residual of design 2.3.4 is asserted, not hidden: on an instance that did not consume, with the tombstone deleted and the entry restored, the same requester, scope, tool and binding can consume once before `expiresAt`. The test is named for that residual.
- [ ] `ApprovalConsumeRaceTest` (new): concurrent `consumeApproved` of one approval id from two members, including one call through a restored `APPROVED` value under the same id, succeeds exactly once. With the tombstone write forced to fail, nothing is consumed and the call refuses with the cluster code; with the entry write forced to fail after the tombstone, the approval reads as consumed on both members.
- [ ] A tombstone survives a split-brain heal from either side; `ApprovalSplitBrainHealTest` adds a case where only the smaller side consumed and the approval stays consumed after the heal.
- [ ] Byte identity on backups: for `Commit`, a claim write and a `PendingSet` reserve on a two-member cluster, the primary and the backup hold identical bytes (the 223 `SealedWritesTest` method applied to these processors).
- [ ] `ApprovalMapsBoundary`: every value in the three approval maps and the consumed map matches the envelope grammar, opens under the test's key, and its decrypted canonical parts recompute the entry key. The raw-value fixture is still rejected.
- [ ] Every existing `Approval*`, `Claim*`, `Pending*`, `ClusterLockTimeoutTest` and `ClusterOperationRefusalTest` case passes. `git diff` shows no assertion removed or weakened; tests change only where they read or write a raw value. `StalledInsert` pause points fire at the same steps.
- [ ] A scope id equal to the old CONFLICT sentinel text is accepted and does not collide (a new case in `PendingOwnershipTest`).
- [ ] The Outcome names the merge-policy design built, cites 219 answer 9, and lists each 0.7 property below with the test that still proves it.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
Creation tokens on entries, claims and pending slots; the claim validity rule; owner-tagged pending slots; atomic commit with TTL; bounded sweeps over `localKeySet` that skip locked keys; bounded locks with `CLUSTER_LOCK_TIMEOUT` and `CLUSTER_OPERATION_REFUSED` reaching callers by the 233 path; audit-first approve, reject and consume (235); `ApprovalView` effective status (236); the approval quorum when `approvalMinimumMembers > 1`. The store's public API and exceptions are unchanged.

## Out of scope
- Identity, re-identification, counters and pause state (225, 226, 228).
- Member cards and the write-key rule (227, 229). The default `WriteKeyPolicy` (`current`) applies.
- Spring wiring, `docs/` pages. Put facts for 213 in the Outcome.
- `TestMembers.java` is read only. Put any helper in the task's own new test classes.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented identifiers in tests. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Waits are on latches or polled state with a bound; time comes from an injected clock.
