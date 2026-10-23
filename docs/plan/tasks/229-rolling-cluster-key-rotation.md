# 229 — Rotate the cluster key in phases: card-based write key, sentinel advance and re-seal sweeper

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 224, 225, 226 and 228.
**Depends on:** 219 (answer 7), 224, 225, 226 (every store sealed), 227 (cards, sentinel), 228 (pause document)
**Wave:** 7 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Decisions:** OD-215-13 decided 2026-10-20 (a): re-sealing in phase 2 by the sweeper only.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/seal/CardWriteKeyPolicy.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/RotationSweeper.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSentinel.java *(the re-seal on key advance only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecurityStatus.java *(insertions only: rotation state)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(installing `CardWriteKeyPolicy` and starting and stopping the sweeper only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/RotationPhasesTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/RotationNegativeCasesTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/RotationSweeperTest.java *(new)*

## Goal
The cluster key can be replaced without losing the re-identification index and without a member ever writing a key another member cannot read (design 4.6, OD-6 (i)). A member writes under `current` only when every verified member's card accepts it, falls back to `previous`, and otherwise writes nothing and counts `rotation.blocked`. The sentinel moves to the new key only under the same rule, and a sweeper re-seals the entries each member owns, so an operator can see from metrics when it is safe to retire the old key. The name key does not rotate; changing it remains a planned full restart with state loss.

## Context
- docs/plan/design/cluster-security.md 4.6 (two secrets, write-key rule, phases 0 to 3, sweeper, sentinel table, failure behaviour), 4.4 (`rotation.accepting_next`, `rotation.blocked`, `rotation.behind`, `seal.entries_under_previous`), 5.2 (the rotation soak steps, used here as the in-process test plan), as amended by 218.
- Task 219 answer 7 (`localKeySet` ownership and migration; a partition-local iterator if one exists).
- Task 220 `WriteKeyPolicy`, `ClusterKeys` (`next`, `previous`). Task 223 `SealedWrites` compare-and-set. Task 227 `MemberCards.acceptedKeyIdsOfAllVerified`, `ClusterSentinel`.
- docs/plan/tasks/retired/202-*.md, Outcome: `replace` resets an entry's expiry on 5.7.0. A re-seal must keep the remaining lifetime.

## Required approach
- `CardWriteKeyPolicy`: `current` if every verified card lists it; else `previous` if configured and listed by all; else refuse with the 220 refusal (which counts `rotation.blocked`). Unverified members do not vote; under `alert` that is a recorded residual.
- Sentinel advance: a member that is ahead (the sentinel opens under its `previous`) and finds every verified card listing its `current` re-seals the sentinel by compare-and-set. No other member re-seals it.
- `RotationSweeper` walks the locally owned keys of every sealed map except identity (a miss recomputes) and the pause document (lease refresh rewrites it). It opens entries sealed under `previous`, re-seals under the policy's key by `SealedWrites` compare-and-set, and keeps each entry's remaining TTL and its authenticated `expiresAt`. It is bounded per pass, skips locked keys, and reports `seal.entries_under_previous`.
- No re-seal on read (OD-215-13 (a), sweeper only): no store's read path writes. A test reads an entry sealed under `previous` and asserts its bytes are unchanged until the sweeper runs.

## Acceptance
- [ ] `RotationPhasesTest`, three embedded members, restarting members one at a time with new `ClusterKeys`: phase 1 (`next = K2`) brings `rotation.accepting_next` to 3; phase 2 (`current = K2`, `previous = K1`) on the first member finds the sentinel under K1, joins with no `CLUSTER_KEY_MISMATCH` and no `rotation.behind`, re-seals the sentinel under K2 and writes K2, and the other two members read its entries; after the sweeper, `seal.entries_under_previous` reads 0 on every member; phase 3 retires K1. Throughout, a pause toggled between phases is seen by every member, an approval created before phase 2 and consumed after it succeeds exactly once, and every pre-created re-identification entry still resolves.
- [ ] The behind case: after the sentinel is under K2, a member restarted with the phase 1 configuration joins, reads `rotation.behind = 1`, writes under the write-key rule, every member reads its entries, and restarting it with the phase 2 configuration clears the gauge.
- [ ] `RotationNegativeCasesTest`: (a) a member holding only K1, started after the sentinel is under K2, is refused with `CLUSTER_KEY_MISMATCH`, publishes no card and changes no writer's key; (b) a K1-only member left running from before phase 2 makes the others fall back to K1, the sentinel stays under K1, and `accepting_next` is below the member count; (c) retiring K1 on one member while `entries_under_previous` is non-zero produces `seal.unsupported_format` and misses handled per structure, never an unauthenticated read; (d) with no acceptable key, every write is refused and `rotation.blocked` counts it.
- [ ] `RotationSweeperTest`, with OD-215-13 (a) (sweeper only): a re-sealed approval keeps its remaining lifetime and its sealed `expiresAt` (the 202 `replace` finding); a locked key is skipped and retried on a later pass; an entry that migrates during a pass is re-sealed by exactly one member or by a later pass, never left under `previous` once the gauge reads 0 everywhere.
- [ ] The name key is the same before and after every test above, and a test asserts that every keyed map key and lock key is unchanged across the rotation.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
Every store's 0.7 approval property and every protection from 224 to 228. Locks stay on keyed names, which do not change. Both cluster codes.

## Out of scope
- Rotating the name key (a full restart, documented by 213).
- Link-key rotation (core plan).
- The soak under load (core task 263 owns "Soak, including the cluster"; this task's tests are in-process and deterministic).
- Spring configuration of `next` and `previous` already exists (222). `docs/` pages: put the operator procedure's facts for 213 in the Outcome.
- `TestMembers.java` is read only.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented identifiers. Keys are generated test bytes, with invented key ids.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps.
