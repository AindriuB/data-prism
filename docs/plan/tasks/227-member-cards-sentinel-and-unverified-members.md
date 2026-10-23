# 227 — Publish member cards, check the sentinel at join, and fail closed on unverified members

Kept for 1.0 under owner decision (b), 2026-10-23: 1.0 supports multiple instances on a trusted network only, and the sealed-store briefs 224, 225, 226, 229, 230, 231 and 264 are deferred past 1.0 (`docs/plan/tasks/deferred/`). This brief depends on none of them. One thing is folded in because of the deferral: the stores stay unsealed, so they never call `Sealer`, and the gate must also be applied where they reach the cluster (see Context, "Folded in 2026-10-23", and Acceptance).

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 223.
**Depends on:** 219 (answers 6 and 11), 220 (`ServingGate`, key check value), 223 (`ClusterSecuritySettings`, `MemberCardMapsBoundary`, sealing context in `PrivacyCluster`)
**Wave:** 5 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Decisions:** OD-215-16 decided 2026-10-20 (a): pause allowed, resume refused until the member serves again.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSentinel.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecurityStatus.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberServingGate.java *(new: the `ServingGate` implementation)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(the start-up sentinel check, the membership listener, card publication, the gate installation and the `memberCards()` and `securityStatus()` accessors, the serving gate on `instance()` and an ungated accessor for the member-card and pause maps (folded in 2026-10-23), plus the review follow-ups in Context dated 2026-10-23: import grouping, inline fully qualified names and the `launch()` bound-address check)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterMembership.java *(the review follow-ups in Context dated 2026-10-23 only: misplaced constants, the keyless `localhost` refusal and the keyed-path `bind.any` override; added 2026-10-23)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/KeylessMemberIsolationTest.java *(the poll at :298-300, inline fully qualified names, and new cases for the follow-ups above only; added 2026-10-23)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java *(insertions only: `CLUSTER_KEY_MISMATCH`)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/MemberCardMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ClusterKeyMismatchTest.java *(new, T9)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/UnverifiedMemberTest.java *(new, T10)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java *(new)*

## Goal
Every member that holds the cluster key proves it by publishing a sealed member card, and a member that joins with the wrong key refuses to start instead of producing a stream of tamper counts. When a joined member has no valid card after the grace period, every instance reports it and, under `refuse`, stops serving (design 2.4 d2, OD-8). Data Prism still cannot evict such a member; this is detection and fail closed, not prevention. The cards also carry what task 228 needs, and what the deferred rotation task 229 will need: the accepted key ids, key check values and the highest pause sequence each member has seen.

## Context
- docs/plan/design/cluster-security.md 1.9, 2.4 (d2), 4.2 (`CLUSTER_KEY_MISMATCH`), 4.4 (`members.unverified`, health conditions), 4.6 (the sentinel table: current, previous, next, none), 4.7 (sentinel, key check value), as amended by 218 (map name, sentinel key, OD-215-7 formula, OD-215-10 codes).
- Task 219 answer 6 (a duplicate live UUID) and answer 11 (`MERGED` delivery).
- Task 220 `ServingGate`, `Sealer`, key check value.
- Task 223 `ClusterSecuritySettings` (`unverifiedMemberAction`, `unverifiedMemberGrace`, `pauseLease` as the card lease).
- From 218/219 (2026-10-21): A member card must open knowing only the live member UUID; test it. The `UNSEALED_CLUSTER_STATE` check runs before the card write. Duplicate-UUID members cannot join (Q6).
- From 220/221 (2026-10-21): member cards should carry each member's sealing salt, so known members' salts are pre-admitted and the unknown-salt limiter (default 256 per minute) only affects strangers. Enforce that `next` is never written. Approval-map locks use `scoped(...)`, not `lockKey`. 221's verified-member excuse for `UNSEALED_CLUSTER_STATE` waits for this task: until then a join to a cluster of two or more holding a non-fence old entry is refused.
- From 222 (2026-10-22), deferred to this task as the next editor of `PrivacyCluster`'s cluster checks: (1) rename `UNSAFE_HAZELCAST_SERIALIZATION` or add a register note that it now also covers the REST, memcache, Management Center and phone-home refusals; (2) the serialization-filter blacklist check over-refuses ancestor packages Hazelcast would not block (safe, but wrong), and there is no test for a blacklisted subpackage under a required prefix; (3) the embedded-path message names the wrong fix for a blacklist collision.
- From the external review of PR #154, rounds 1-4 (2026-10-23): `PrivacyCluster`'s import grouping is off; fix it as the next editor of the file (the inline-name finding is restated, corrected, in the round-5 bullet below). `INVALID_CLUSTER_INTERFACE` now also covers a `hazelcast.*` address property that makes a single member reachable; keep any new wording consistent with `docs/configuration.md` and the contract register. Corrected 2026-10-23 (round 5): that holds for a keyed solo member only; a keyless one refuses `MISSING_CLUSTER_KEY` (`PrivacyCluster.embedded` passes it when keys are null). `docs/configuration.md` and the register say otherwise today; 284 fixes them (moved from 230, deferred) and 213 fixes `docs/multiple-instances.md`.
- From the external review of PR #154, round 5 (2026-10-23), for this task as the next `PrivacyCluster` and `ClusterMembership` editor:
  - The earlier "one remaining inline name" understated it. The tests 222 added carry dozens of inline fully qualified names (`java.util.concurrent.atomic.AtomicInteger` and others); fix them in every file this task edits, starting with `PrivacyCluster` and `KeylessMemberIsolationTest`. The `ClusterMembership` constants are also misplaced; move them to where they are used.
  - [security] A keyless member accepts `localhost` as `localAddress`, and `PrivacyCluster.launch()` never checks the address the member actually bound (`using()` does). Coordinator decision 2026-10-23: a keyless member's `localAddress` and `publicAddress` must be a literal loopback address (`127.x.x.x` or `::1`); `localhost` is no longer accepted, because it is resolved through the hosts file. See Acceptance.
  - [security] On a keyed member, `-Dhazelcast.socket.server.bind.any=true` overrides the required interface. The keyless path closes this: `ClusterMembership.isolate` sets both `bind.any` properties to `false` and then reads the effective configuration back the way Hazelcast does (`reachableOnlyFromThisHost`), so a system property that still wins is caught. The keyed path does not: `bindToInterface` sets only `hazelcast.socket.bind.any` and reads nothing back. Close it the same way; see Acceptance.
  - `KeylessMemberIsolationTest:298-300` sleep-polls against a 20 s deadline, against the "no sleeps" rule in `docs/conventions.md`. Now an Acceptance item.
- Folded in 2026-10-23 (owner decision (b)): `ServingGate` is consulted only inside `Sealer.seal` and `Sealer.open`. With 224 to 226 deferred, the approval store, the identity cache, the re-identification index, the budget and the caller-rate limiter write plain values and never call `Sealer`, so a closed gate alone would refuse none of them. Every one of them reaches its map through `PrivacyCluster.instance()` on each call (`HazelcastApprovalStore`:698-706, `CachingSyntheticValueSource`:91 and :145, `ScopeIdentityIndex`:68 and :95, `HazelcastScopeBudget`:123, `HazelcastCallerRateLimiter`:47, `HazelcastOversightState`:109 at 223). So the gate is applied there: while it is closed, `instance()` refuses with `CLUSTER_OPERATION_REFUSED` (OD-215-10 (a)). `MemberCards` and `ClusterSentinel` use a package-private ungated accessor, and 228 moves the pause document onto it. Until 228 lands, oversight reads while the gate is closed fail closed (`OVERSIGHT_UNAVAILABLE`), and a pause write is refused too. 228 restores OD-215-16 (a): pause allowed, resume refused. No store file is edited here.
- Settings: 230 is deferred, so `unverified-member.action` and `unverified-member.grace` are not Spring properties in 1.0. The `ClusterSecuritySettings` defaults apply (`REFUSE`, `PT30S`), and the tests construct settings directly.

## Required shape (228 and 286 read these; 229 and 230, which also did, are deferred past 1.0)
- A card holds: member UUID, format version, the cluster key ids this member accepts, a key check value for each configured purpose key (cluster keys and name key now; further purpose keys, such as link keys, through `MemberCards.addPurposeKey(id, material)` before start), the highest pause sequence this member accepted (from a supplier set by 228; zero until then), and a lease end. It is sealed with the member UUID and lease end authenticated.
- `MemberCards`: `verifiedMembers()`, `unverifiedCount()`, `acceptedKeyIdsOfAllVerified()`, `highestReportedPauseSequence()`, `reportPauseSequence(LongSupplier)`. A card is refreshed when half its lease is spent.
- `ClusterSentinel`: at start, read the sentinel; if absent, write it with `putIfAbsent`; act on which held key opens it, per design 4.6. Re-sealing the sentinel on a key advance is task 229's (deferred past 1.0), not this task's.
- `ClusterSecurityStatus`: serving or not, and why (sentinel mismatch, unverified member under `refuse`, key check value mismatch); member count; unverified count. No address, no key id material, no UUID in any text a log or health endpoint shows. Task 228 adds pause freshness to it; task 286 reports it as health.
- `MemberServingGate` closes when the status says not serving, and `PrivacyCluster.instance()` consults it (Context, "Folded in 2026-10-23"). It never blocks the member-card map or the pause-document map, so a member can still prove itself and read or write pause state. While closed, every other sealed operation is refused with the code decided under OD-215-10, so the 233 path carries it to callers. Oversight writes while closed follow OD-215-16.

## Acceptance
- [ ] T9, in `ClusterKeyMismatchTest`: a member with a different cluster key joining a cluster that holds a sentinel is refused with `CLUSTER_KEY_MISMATCH` before it serves, leaves the cluster, and publishes no card. A sentinel planted by a keyless client before the first legitimate member causes that refusal and no data access. A member whose `previous` or `next` opens the sentinel joins, per the 4.6 table (one test per row).
- [ ] T10, in `UnverifiedMemberTest`, with an injected clock: a keyless member joins; `members.unverified` becomes 1; after the grace, under `refuse`, every instance reports not serving and a tool-path store call is refused with the OD-215-10 code; under `alert` it keeps serving and the gauge still reads 1. After the member leaves, service resumes without a restart.
- [ ] A member that runs without cards (standing in for a 0.7 member, or any member without the key) is counted unverified exactly like a keyless member. The test is named for the 0.7 case.
- [ ] A replayed old card for a live member is accepted only until its lease ends; a deleted card makes the member unverified after the grace. Cards for UUIDs not in the member list are ignored.
- [ ] A card with a different key check value for a key id this member holds counts `seal.tamper` and makes the status not serving.
- [ ] Duplicate live UUID: the behaviour 219 answer 6 found is pinned by a test, and the status never counts an impostor card as a second verified member.
- [ ] While the gate is closed, card refresh and pause-document reads still work (a test reads the pause map through the closed gate).
- [ ] Gate on the unsealed stores (folded in 2026-10-23): with the gate closed, in `UnverifiedMemberTest`, one case per store: `HazelcastApprovalStore` (`create`, `find`, `consumeApproved`), `ScopeIdentityIndex.subjectFor`, `HazelcastScopeBudget.tryRead` and `HazelcastCallerRateLimiter` each refuse with `CLUSTER_OPERATION_REFUSED`, and the tool path carries it to the caller by the 233 path. `CachingSyntheticValueSource` returns the generator's value without reading or writing the identity map (design 2.3.1: identity recomputes). After the gate reopens, each store serves again without a restart. `git diff --stat` shows none of those store files changed.
- [ ] `MemberCardMapsBoundary`: every value in the member-card map is an envelope that opens under the test key; the sentinel sits only at the reserved key 218 named.
- [ ] No log line, exception message or status text contains a key, a key id's material, a member address or a UUID (a test scans `RecordingLogger` output and the status text).
- [ ] Keyless addresses (coordinator decision 2026-10-23): `ClusterMembership.reachableOnlyFromThisHost` accepts a `hazelcast.local.localAddress` or `hazelcast.local.publicAddress` (Config property or system property) only when it is a literal loopback address, `127.x.x.x` or `::1`. `localhost` and every other host name are refused with `MISSING_CLUSTER_KEY` on a keyless solo member (`INVALID_CLUSTER_INTERFACE` on a keyed one). A test covers `localhost`, `127.0.0.2`, `::1` and a non-loopback literal, as a Config property and as a system property. `docs/configuration.md` is 284's (moved from 230, deferred); record the rule in the Outcome for it.
- [ ] `PrivacyCluster.launch()` checks the address the started member actually bound, as `using()` does, and refuses before serving when it is not one the membership allows. A test proves the check fails when the bound address is widened.
- [ ] Keyed `bind.any`: on a joining member with keys, `bindToInterface` sets both `hazelcast.socket.bind.any` and `hazelcast.socket.server.bind.any` to `false`, and the effective configuration is read back the way Hazelcast reads it, so `-Dhazelcast.socket.server.bind.any=true` (or the general property) is refused at startup instead of overriding `member.interface`. A test sets each system property and asserts the refusal and that no member is left running.
- [ ] No sleeps (`docs/conventions.md`): `KeylessMemberIsolationTest` contains no `Thread.sleep` (`grep -c 'Thread.sleep' data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/KeylessMemberIsolationTest.java` prints 0). The wait at :298-300 is a `CountDownLatch` released by a `LifecycleListener` on `SHUTDOWN`, awaited with a bounded timeout, and the test fails, not hangs, when the event never comes.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
Every store's behaviour while the gate is open. The default `WriteKeyPolicy` (`current`) is unchanged; the card-based rule is task 229 (deferred past 1.0). Bounded locks and both cluster codes.

## Out of scope
- The pause document and its freshness (228). Rotation and the sentinel re-seal (229, deferred past 1.0; in 1.0 a cluster-key change is a planned full restart, which 213 documents).
- The health contributor (286). Spring properties for these settings, and tamper reporting (230, deferred past 1.0; the defaults apply). Audit records (none; OD-215-6).
- Sealing the approval, identity, re-identification, budget and caller-rate values (224 to 226, deferred). Editing those store files: the gate is applied in `PrivacyCluster` only.
- Evicting a member: there is no supported OSS API, and none is added.
- `TestMembers.java` is read only. `docs/` pages: facts for 213 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented cluster names and identifiers. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Grace and lease use an injected clock; waits are on membership events or polled state with a bound.
