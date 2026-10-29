# 228 — Replace pause flags with one sealed, sequenced, leased pause document

Kept for 1.0 under owner decision (b), 2026-10-23: 1.0 supports multiple instances on a trusted network only, and the sealed-cluster briefs 224, 226, 229, 230, 231 and 264 are deferred past 1.0 (`docs/plan/tasks/deferred/`; 225 was restored to 0.8 by a second owner decision the same day). This brief depends on none of them; `SealedWrites`, keyed names and `ClusterSecuritySettings` are on `main` (223). In 1.0 the sealed stores are the pause document, the member cards and the sentinel (227), and the identity cache and re-identification index (225); the docs (213) name them as what is protected.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 227.
**Depends on:** 219 (answers 10 and 11), 223 (keyed names, `SealedWrites`, `ClusterSecuritySettings`), 227 (`MemberCards`, `ClusterSecurityStatus`, the gate's pause-map exemption)
**Wave:** 7 (moved from 6 on 2026-10-23, after 227)
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightState.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PauseDocument.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/OversightMaps.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecurityStatus.java *(insertions only: pause freshness and age)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/OversightMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java *(changes only where a stored value is read or written raw, plus new cases)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseDocumentTest.java *(new, T4)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseSplitBrainTest.java *(new, T4 merge cases)*
- Added 2026-10-29 (Amendments 2026-10-29, after PR #191 review; line numbers are at PR #191 head 93877409 for test files and at `main` ca7e9b0c for `ClusterBackedState.java`):
  - data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java *(the `endScope` pause step, its ordering and its deadline only; amendment 5)*
  - data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/KeyedScopeEndTest.java *(the assertions at :98-99, :109 and :189-196, plus new cases; amendment 5)*
  - data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/SoloMemberKeysTest.java *(the oversight-map layout assertion at :105-106 only)*
  - data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/UnverifiedMemberTest.java *(:447-463 and the OD-215-16 (a) gate-closed cases only; amendments 1 and 6)*
  - data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the `dataPrismClusterOversightState` bean method and its constructor call, :172-175, only; amendment 7)*
  - data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterPauseGaugeWiringTest.java *(new; name may differ; amendment 7)*
  - data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/RefusedResumeAuditCodeTest.java *(new; amendment 6. `ResumeAuditOrderingTest.java` is not edited, and `OperatorHarness.java` is read only: 294 owns its `freePort`)*
  - Not owned and not needed: `MemberCards.java`, `PrivacyCluster.java`, `HazelcastApprovalStore.java`. If a clean fix seems to need one of them, stop and report.

## Goal
A deleted, forged or replayed entry can no longer lift a pause. Pause state becomes one sealed document with a sequence, a refresh counter and a lease. Writes are compare-and-set, every instance remembers the highest document it accepted and never goes back, a split-brain merge takes the union of pauses, and a document that is absent, unauthentic or not fresh means "everything paused" (design 2.3.3, OD-3, OD-4, OD-14, OD-15). Deletion becomes a visible denial of service, which is the safe direction.

## Context
- docs/plan/design/cluster-security.md 1.4, 2.3.3 (the whole section, including the residual paragraph), 4.4 (`pause.fresh`, `pause.age_seconds`, `pause.conflict`), OD-3, OD-4, OD-14, OD-15, as amended by 218 (OD-215-5 gauges, OD-215-10 code for a document that is not fresh).
- Task 219 answer 10 (`replace(k, old, new)` against a held lock) and answer 11 (`MERGED` delivery and its order relative to merge policies).
- Task 227: `MemberCards.reportPauseSequence`, `highestReportedPauseSequence`, `verifiedMembers`; `ClusterSecurityStatus`; the gate never blocks the pause map; OD-215-16 for oversight writes while not serving.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/OversightState.java: the SPI, unchanged.
- From 218/219 (2026-10-21): This task owns the 0.7 fence write (`all` = TRUE in `dataprism.oversight`) and its re-assertion at each lease refresh.
- From 227 (folded in 2026-10-23, shape changed after the review of PR #164): the stores use the gated `PrivacyCluster.servingInstance()`; `instance()` stays ungated, and `HazelcastOversightState` keeps using it, so the pause document stays readable and writable while not serving. OD-215-16 (a) is enforced here: while `ClusterSecurityStatus` says not serving, a pause is accepted and a resume is refused.
- Settings: 230 is deferred, so `pause-lease` and `clock-skew-tolerance` are not Spring properties in 1.0. The `ClusterSecuritySettings` defaults apply (`PT30S` and `PT5S`), on a joining member and on a solo member alike, and the tests construct settings directly.

## Required approach
- One document under a fixed keyed name in `dataprism.oversight.s1`: the full set of pauses, `sequence`, `refresh`, `issuedAt`, and the issuing member. Order is (sequence, refresh), lexicographic.
- Pause and resume write `sequence + 1, refresh = 0` *(how the sequence is chosen and what a write covers: amended 2026-10-29 by amendments 3 and 4)*; a lease refresh writes `refresh + 1` with the same pauses, when half the lease is spent, by any member. Writes are `replace(name, exactOldBytes, newBytes)`, or `putIfAbsent` when absent; a lost race re-reads and retries a bounded number of times, then fails closed.
- Freshness is from local monotonic receipt time (an injected nano clock). The wall clock is used only to gate the first acceptance after a cold start: reject a document whose `issuedAt` is older than lease plus `clockSkewTolerance`.
- Equal order with different content, or a `MERGED` event that finds a pause the cluster document lacks: write the union at `max(sequence) + 1`, `refresh = 0`, count `pause.conflict`.
- Missing document: rewrite from memory per OD-15 (i) only with `putIfAbsent`, only from a document accepted within the last lease, and only if no verified member's card reports a higher sequence. *(Still holds for an ABSENT document. For a BEHIND document, superseded 2026-10-29 by amendment 8: `replace` over the exact stored bytes, with a union.)* ~~First start: a member that finds no document and no other verified member writes the first one.~~ *(Superseded 2026-10-29 by amendment 1.)*
- Not fresh, absent or unauthentic: every `OversightState` read answers paused, or refuses with the OD-215-10 code; ~~the instance reports not serving through `ClusterSecurityStatus`~~ *(superseded 2026-10-29 by amendment 6: freshness is reported through `ClusterSecurityStatus.withPause` and `HazelcastOversightState.securityStatus()`, and the `PrivacyCluster` serving gate is not closed)*. Publish the accepted sequence to `MemberCards`.

## Acceptance
- [ ] T4, in `PauseDocumentTest`, with injected clocks: deleting the document makes every instance refuse and `pause.fresh` read 0; a document older than one an instance has seen is rejected by it; a restarted instance accepts a replayed older document only inside the lease and the cold-start gate, and the test asserts the window equals the configured lease (the residual, named as such); two concurrent writers both succeed in sequence with neither pause lost; a member with its wall clock skewed by more than the tolerance does not make a healthy document stale after first acceptance.
- [ ] T4 merge cases, in `PauseSplitBrainTest`: a pause issued on the smaller side of a split is present after the heal; equal-order documents with different content merge by union and count `pause.conflict`; a resume on one side may be undone by the heal, and the test is named for that cost (OD-14).
- [ ] OD-15 rule: a member that missed a pause (partitioned) does not rewrite an older document while a verified member reports a higher sequence; a member that accepted the latest document within the lease does rewrite a deleted one. *(Kept; amendments 1, 2 and 8 add cases.)*
- [ ] An attacker document (plaintext, 0.7 format, random envelope, envelope under another key) written by a keyless member or client never lifts a pause, and is counted.
- [ ] Every existing `HazelcastOversightTest` case passes; changes are only where a raw value was read or written. The `OversightState` SPI and `OversightSnapshot` are unchanged.
- [ ] `pause.fresh` and `pause.age_seconds` are emitted as decided under OD-215-5, and `ClusterSecurityStatus` reports freshness without any pause content.
- [ ] *(Amended 2026-10-29 by amendment 6: the audit order and both codes are also pinned in the new server-module test.)* OD-215-16 (a), with the serving gate closed by an unverified member under `refuse` (227): a pause is written and seen by every instance; a resume is refused with `CLUSTER_OPERATION_REFUSED` and audited as 245 audits a resume whose apply fails (`ALLOW:RESUMED`, then a linked `DENY`); after the gate reopens, the resume succeeds. `HazelcastOversightState` reaches the document through `instance()`, never `servingInstance()` (227's `ServingInstanceArchitectureTest` allows it), and the resume refusal comes from the status check, not from the accessor.
- [ ] `OversightMapsBoundary`: the oversight map holds exactly the document, which opens under the test key; scope ids appear only inside the plaintext.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Amendments 2026-10-29 (after PR #191 review)

The review of PR #191 (branch `task/228-signed-leased-pause-document`, head 93877409) found two P1 and several P2 design gaps. An architect settled them, and the owner decided the two open calls on 2026-10-29 (amendments 2 and 5, option (a) each). These amendments win where they disagree with the text above; the superseded sentences above are marked, not deleted. Names below (`Status` values, the `Peers` seam, `beforeCommit`, `pushHeld`, `tick`) are those of `PauseDocument.java` at 93877409. "Held" is the highest document this member accepted; "stored" is the authentic document read from the map.

### 1. First start (P1)
- A member with `held == null` that finds the document ABSENT may `putIfAbsent` Doc(1, no pauses) only if all of these hold:
  - it is alone, or it is the oldest member (first in the Hazelcast member list);
  - `MemberCards.unverifiedCount() == 0`;
  - `highestReportedPauseSequence() == 0`, counting every verified card, its own included.
- A member with `held != null` never takes this path; it uses the OD-15 restore.
- Why the bare "no card above 0" rule is unsafe: cards can be deleted, and stale seq-0 cards can be replayed; either makes a lost pause look like a first start.
- Residual, recorded: if the oldest member is hung, no member writes Doc(1); an operator pause is the fallback.
- Tests (the `Peers` seam gains `unverifiedCount()` and `isOldest()`):
  - [ ] three members started together produce exactly one Doc(1), and every read on every member works;
  - [ ] a full cluster restart produces exactly one Doc(1);
  - [ ] two eligible members race: one document, and `pause.conflict` is not counted;
  - [ ] attacker shapes, each of which must not write Doc(1), must refuse reads until a peer restores, and must keep the pause: a pause at seq 2 held by A and B, the document deleted, C restarts; the same with the cards also deleted; the same with stale seq-0 cards replayed;
  - [ ] the oldest member, with any card reporting above 0, does not write;
  - [ ] a 0.7 member or a card-less member present means no first start.

### 2. Lapsed-lease recovery (P1; owner decision (a): automatic, on the tick only, never on the read path)
- A member with `held != null` whose lease has lapsed, and whose stored document is authentic and CURRENT (order equal to held), may write `refresh + 1` with `replace` over the exact stored bytes, if all of these hold:
  - its cards status serves (a solo member always does);
  - its cards look is within a lease;
  - `peers.highestReportedSequence() <= held.sequence`.
- Never over ABSENT, UNREADABLE, BEHIND, GATED or CONFLICT values. Never for `held == null`; the cold-start gate and the replay window (equal to the lease) are unchanged.
- One rate-limited WARN, with no pause content, scope, tool or member address. No new metric.
- Residual, recorded: a lone stale holder whose peers' cards are also stale cannot tell a replay from the truth.
- Tests:
  - [ ] a solo member's monotonic clock passes the lease, reads refuse, one tick recovers, and `pause.fresh` returns to 1 with the same sequence and `refresh + 1`;
  - [ ] the identical bytes re-presented (re-put) do not reset freshness;
  - [ ] no recovery when a peer card reports a higher sequence, when a member is unverified, when the stored value is BEHIND (a replay), when it is ABSENT, or for a restarted member (`held == null`);
  - [ ] lapsed plus ABSENT is recovered only by an operator pause, and that pause keeps the held pauses.

### 3. A pause from a member that is behind (P2)
- Every write's pauses are `held.pauses` ∪ the stored authentic document's pauses (whatever their order) + the change.
- `sequence = max(held.sequence, stored.sequence) + 1`. The `peers.highestReportedSequence() + 1` bump (`PauseDocument.java:485` at 93877409) is dropped.
- Resume only: refuse with `OVERSIGHT_UNAVAILABLE` when `peers.highestReportedSequence() > top.sequence`. This covers a cold member too.
- A pause over an ABSENT document: `putIfAbsent(held ∪ change)` at `held.sequence + 1`, or Doc(1){change} when `held == null`. This reconciles OD-215-16. Do not write `all = true`.
- Tests (using the `beforeCommit` seam): A and B hold P and Q at seq 5; C is cold or stalled at 3; the document is deleted, replaced by a seq-3 replay, or replaced by garbage. C pauses X. For each shape:
  - [ ] after the heal, every instance shows P, Q and X;
  - [ ] C never gets a higher order than A and B;
  - [ ] a resume at C is refused while any card reports a higher sequence.

### 4. Every write is built inside lock → read → judge → compute (P2)
`pushHeld` today replaces without judging. Every write path, `pushHeld` included, now judges the stored value under the lock and computes from it:
- CURRENT or ACCEPTED: write nothing.
- ABSENT or BEHIND: the union at `max + 1`.
- UNREADABLE or GATED: overwritten only by an operator pause (amendment 8).
- A lost race re-reads, up to the existing bound, then fails closed.
- A structural guard in `commit`: a non-resume write must have an order above the stored document's, or be an identical-pause refresh successor of it, and must cover the stored authentic document's pauses. A write that fails the guard is not made.
- Tests:
  - [ ] a rival writes a higher document with an extra pause between judge and commit (`beforeCommit`); the final document covers both, at a higher order;
  - [ ] `pushHeld` never writes over a higher stored document;
  - [ ] a mutant that removes the guard fails a test (the Outcome shows it).

### 5. Ending a scope (owner decision (a): `endScope` removes the scope from the document)
- The removal is resume-class: serving, fresh and corroborated, per amendment 3. It is the LAST step of `ScopeIdentityIndex.endScope`, run only after every data sweep and the approval forget have succeeded.
- Any failure, skipped lock or stale document keeps the pause. When not serving or stale, the call throws `ClusterOperationRefusedException` after the sweeps, and the data is still erased.
- An ABSENT document is a no-op success. No write if the scope is not in the document.
- UNREADABLE or GATED: throw.
- Shared deadline: `min(remaining, lockTimeout)`; under a 100 ms floor the step is skipped and counted as a lock timeout.
- `ScopeIdentityIndex` holds its own lazily created, never-started `PauseDocument`. No new constructor parameters.
- An encode-side entry cap equal to the decode cap (`1 << 20`, `MAX_ENTRIES`). A pause past it is refused with `CLUSTER_OPERATION_REFUSED`.
- Tests, in `KeyedScopeEndTest`:
  - [ ] the ended scope is removed, while other scopes, tool pauses and the global pause survive (the assertions at :98-99, :109 and :189-196 move from raw map entries to the document);
  - [ ] not serving: the data is erased, the pause is kept, and the call throws `CLUSTER_OPERATION_REFUSED`;
  - [ ] stale document: the same;
  - [ ] a sweep failure keeps the pause;
  - [ ] a replayed older document at a cold index does not lift other scopes;
  - [ ] deadline exhaustion keeps the pause and throws the lock timeout;
  - [ ] an oversize encode is refused.

### 6. Status and gating
- Do NOT close the `PrivacyCluster` serving gate on a stale or absent document; it would deadlock recovery. Freshness stays in `ClusterSecurityStatus.withPause` and `HazelcastOversightState.securityStatus()`.
- Codes: not serving → `CLUSTER_OPERATION_REFUSED`; absent, unauthentic or stale → `OVERSIGHT_UNAVAILABLE`.
- The design's 4.4 sentence on approval reads is met by `state()` and admission, not by approval reads. `HazelcastApprovalStore` is not edited. For the scribe: amend `docs/plan/design/cluster-security.md` 4.4 to say so (this task does not edit design docs).
- Tests:
  - [ ] gate closed: a pause is written and seen by every instance, and a resume gives `CLUSTER_OPERATION_REFUSED` (`UnverifiedMemberTest`, :447-463 and the OD-215-16 (a) cases);
  - [ ] gate open and document stale: reads and resume give `OVERSIGHT_UNAVAILABLE`, `securityStatus()` reports `PAUSE_NOT_FRESH`, and `cluster.securityStatus().serving()` stays true;
  - [ ] `RefusedResumeAuditCodeTest` (new, `data-prism-server`), with a stub `OversightState`, asserts the audit order `ALLOW:RESUMED` then a linked `DENY`, once for each code.

### 7. Metrics
- 228 takes the `dataPrismClusterOversightState` bean method and its constructor call in `ClusterBackedState.java` (:172-175 at ca7e9b0c), copying the approval-store pattern at :177-181: `DataPrismProperties`, plus `ObjectProvider<PrivacyMetrics>` with `getIfAvailable(PrivacyMetrics::none)`. No bean is added, so the bean inventories do not change.
- Test:
  - [ ] `ClusterPauseGaugeWiringTest` (new): a recording `PrivacyMetrics` sees `pause.fresh` and `pause.age_seconds` registered exactly once each.

### 8. Recorded decisions
- Restore on BEHIND uses `replace` over the exact stored bytes, with a union, not `putIfAbsent`. This amends OD-15 (i). Test:
  - [ ] a replayed older document is repaired to a superset of it and of the held pauses.
- The 0.7 fence (`all = TRUE`) is asserted after this member's commit; the residual is up to half a lease, matching design 4.8. `OversightMaps` adds an explicit never-evict `MapConfig` (eviction `NONE`, no TTL) for `dataprism.oversight`. Test:
  - [ ] `OversightMapsBoundary` asserts both oversight maps' effective config (no eviction, no TTL).
- An unreadable document is repaired only by an operator pause, through `replace` over the exact stored bytes. Test:
  - [ ] plaintext, 0.7-format, random and wrong-key envelopes are never overwritten by a tick, are counted as tamper, and are replaced by an operator pause that keeps the held pauses.
- `OversightMapsBoundary.check()` must fail, not skip, when the keyed names came from the test name key and the envelope does not open.
- The MERGING→MERGED `mergeBase` capture gets a test. Test:
  - [ ] a mutant whose `beginMerge` is a no-op fails it (today such a mutant still passes 5 of 5 `PauseSplitBrainTest` cases).

### Ownership and ordering (checked 2026-10-29)
- The added Owns (in the Owns block, marked "Added 2026-10-29") are disjoint from the other wave-7 briefs: 211 (Spring audit and cluster-key files), 247 (orchestration, MCP, the `new DefaultContextOrchestrator(` call sites), 256 (validation), 292 (core audit) and 294 (the test harness, filed on PR #190: `FreePorts`, `TestMembers.pair`, `MemberCardTest`, `ApprovalRaceTest`, `SealedWritesTest`, `OperatorHarness.freePort`). 294 does not own `UnverifiedMemberTest`, `KeyedScopeEndTest` or `SoloMemberKeysTest`.
- `ClusterBackedState.java` is shared, and serialised: 228 (wave 7, the oversight bean only), then 287 (wave 8, the trusted-network pass-through; depends on 228), then 286 (wave 10, the health bean and the lock-timeout pass-through; depends on 228 and 287). 286 no longer passes the lock timeout to the oversight store; 228 does.
- 287 also owns the existing hazelcast tests for its declaration, which includes the three test files added here; it depends on 228, so it edits them afterwards.

## Preserves
Pause, resume and snapshot semantics for operators; the audit-first operator flow; bounded writes; both cluster codes; `OVERSIGHT_UNAVAILABLE` for an oversight state that cannot be evaluated.

## Out of scope
- The `resume` audit ordering (core task 245, OD-08-14).
- Rotation of the key the document is sealed under (229, deferred past 1.0).
- Spring binding of `pause-lease` and `clock-skew-tolerance` (230, deferred past 1.0; the defaults apply). The readiness contributor that reports freshness (286).
- `TestMembers.java` is read only. `docs/` pages: facts for 213 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented scope and tool names. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Both clocks are injected; waits are on events or polled state with a bound.
