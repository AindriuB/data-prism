# 227 — Publish member cards, check the sentinel at join, and fail closed on unverified members

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 223.
**Depends on:** 219 (answers 6 and 11), 220 (`ServingGate`, key check value), 223 (`ClusterSecuritySettings`, `MemberCardMapsBoundary`, sealing context in `PrivacyCluster`)
**Decisions:** OD-215-16 decided 2026-10-20 (a): pause allowed, resume refused until the member serves again.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSentinel.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecurityStatus.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberServingGate.java *(new: the `ServingGate` implementation)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(the start-up sentinel check, the membership listener, card publication, the gate installation and the `memberCards()` and `securityStatus()` accessors only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java *(insertions only: `CLUSTER_KEY_MISMATCH`)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/MemberCardMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ClusterKeyMismatchTest.java *(new, T9)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/UnverifiedMemberTest.java *(new, T10)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java *(new)*

## Goal
Every member that holds the cluster key proves it by publishing a sealed member card, and a member that joins with the wrong key refuses to start instead of producing a stream of tamper counts. When a joined member has no valid card after the grace period, every instance reports it and, under `refuse`, stops serving (design 2.4 d2, OD-8). Data Prism still cannot evict such a member; this is detection and fail closed, not prevention. The cards also carry what tasks 228 and 229 need: the accepted key ids, key check values and the highest pause sequence each member has seen.

## Context
- docs/plan/design/cluster-security.md 1.9, 2.4 (d2), 4.2 (`CLUSTER_KEY_MISMATCH`), 4.4 (`members.unverified`, health conditions), 4.6 (the sentinel table: current, previous, next, none), 4.7 (sentinel, key check value), as amended by 218 (map name, sentinel key, OD-215-7 formula, OD-215-10 codes).
- Task 219 answer 6 (a duplicate live UUID) and answer 11 (`MERGED` delivery).
- Task 220 `ServingGate`, `Sealer`, key check value.
- Task 223 `ClusterSecuritySettings` (`unverifiedMemberAction`, `unverifiedMemberGrace`, `pauseLease` as the card lease).

## Required shape (tasks 228, 229 and 230 read these)
- A card holds: member UUID, format version, the cluster key ids this member accepts, a key check value for each configured purpose key (cluster keys and name key now; further purpose keys, such as link keys, through `MemberCards.addPurposeKey(id, material)` before start), the highest pause sequence this member accepted (from a supplier set by 228; zero until then), and a lease end. It is sealed with the member UUID and lease end authenticated.
- `MemberCards`: `verifiedMembers()`, `unverifiedCount()`, `acceptedKeyIdsOfAllVerified()`, `highestReportedPauseSequence()`, `reportPauseSequence(LongSupplier)`. A card is refreshed when half its lease is spent.
- `ClusterSentinel`: at start, read the sentinel; if absent, write it with `putIfAbsent`; act on which held key opens it, per design 4.6. Re-sealing the sentinel on a key advance is task 229's, not this task's.
- `ClusterSecurityStatus`: serving or not, and why (sentinel mismatch, unverified member under `refuse`, key check value mismatch); member count; unverified count. No address, no key id material, no UUID in any text a log or health endpoint shows. Task 228 adds pause freshness to it; task 230 reports it as health.
- `MemberServingGate` closes when the status says not serving. It never blocks the member-card map or the pause-document map, so a member can still prove itself and read or write pause state. While closed, every other sealed operation is refused with the code decided under OD-215-10, so the 233 path carries it to callers. Oversight writes while closed follow OD-215-16.

## Acceptance
- [ ] T9, in `ClusterKeyMismatchTest`: a member with a different cluster key joining a cluster that holds a sentinel is refused with `CLUSTER_KEY_MISMATCH` before it serves, leaves the cluster, and publishes no card. A sentinel planted by a keyless client before the first legitimate member causes that refusal and no data access. A member whose `previous` or `next` opens the sentinel joins, per the 4.6 table (one test per row).
- [ ] T10, in `UnverifiedMemberTest`, with an injected clock: a keyless member joins; `members.unverified` becomes 1; after the grace, under `refuse`, every instance reports not serving and a tool-path store call is refused with the OD-215-10 code; under `alert` it keeps serving and the gauge still reads 1. After the member leaves, service resumes without a restart.
- [ ] A member that runs without cards (standing in for a 0.7 member, or any member without the key) is counted unverified exactly like a keyless member. The test is named for the 0.7 case.
- [ ] A replayed old card for a live member is accepted only until its lease ends; a deleted card makes the member unverified after the grace. Cards for UUIDs not in the member list are ignored.
- [ ] A card with a different key check value for a key id this member holds counts `seal.tamper` and makes the status not serving.
- [ ] Duplicate live UUID: the behaviour 219 answer 6 found is pinned by a test, and the status never counts an impostor card as a second verified member.
- [ ] While the gate is closed, card refresh and pause-document reads still work (a test reads the pause map through the closed gate).
- [ ] `MemberCardMapsBoundary`: every value in the member-card map is an envelope that opens under the test key; the sentinel sits only at the reserved key 218 named.
- [ ] No log line, exception message or status text contains a key, a key id's material, a member address or a UUID (a test scans `RecordingLogger` output and the status text).
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
Every store's behaviour while the gate is open. The default `WriteKeyPolicy` (`current`) is unchanged; the card-based rule is task 229. Bounded locks and both cluster codes.

## Out of scope
- The pause document and its freshness (228). Rotation and the sentinel re-seal (229).
- Health contributor, Spring properties, audit records (230).
- Evicting a member: there is no supported OSS API, and none is added.
- `TestMembers.java` is read only. `docs/` pages: facts for 213 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented cluster names and identifiers. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Grace and lease use an injected clock; waits are on membership events or polled state with a bound.
