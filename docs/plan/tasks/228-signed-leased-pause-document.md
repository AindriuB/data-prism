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
  - data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/RefusedResumeAuditCodeTest.java *(new; amendment 6. ~~`ResumeAuditOrderingTest.java` is not edited~~ (revised: see below), and `OperatorHarness.java` is read only: 294 owns its `freePort`)*
- Added 2026-10-29 in the revision after the external review of PR #194 (amendment 9; line numbers at `main` ca7e9b0c):
  - data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java *(the `resume` method's response shape, :109-133, only: the `stillPaused` read from `snapshot()` after the resume)*
  - data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ResumeAuditOrderingTest.java *(the exact-map assertion at :155 only)*
  - data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ResumeEffectTest.java *(new; name may differ)*
  - ~~`OversightState.java` (three `default` reporting methods), `ResumeOutcome.java` (new), `ResumeOutcomeDefaultsTest.java` (new)~~ *(removed 2026-10-29 by owner decision 5, revised: no core change; `OversightState.java` is read only)*
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
- One document under a fixed keyed name in `dataprism.oversight.s1`: the full set of pauses, `sequence`, `refresh`, `issuedAt`, and the issuing member. Order is (sequence, refresh), lexicographic. *(Revised 2026-10-29: the pauses are an observed-remove set of elements and tombstones, merged on every acceptance; order decides freshness only. See amendment 3.)*
- Pause and resume write `sequence + 1, refresh = 0` *(how the sequence is chosen and what a write covers: amended 2026-10-29 by amendments 3 and 4)*; a lease refresh writes `refresh + 1` with the same pauses, when half the lease is spent, by any member. Writes are `replace(name, exactOldBytes, newBytes)`, or `putIfAbsent` when absent; a lost race re-reads and retries a bounded number of times, then fails closed.
- Freshness is from local monotonic receipt time (an injected nano clock). The wall clock is used only to gate the first acceptance after a cold start: reject a document whose `issuedAt` is older than lease plus `clockSkewTolerance`.
- ~~Equal order with different content, or a `MERGED` event that finds a pause the cluster document lacks: write the union at `max(sequence) + 1`, `refresh = 0`, count `pause.conflict`.~~ *(Superseded 2026-10-29 by amendments 3, 4 and 7: every accepted document is merged, a repair writes `held ⊔ stored` when stored lacks something, and `pause.conflict` is redefined.)*
- Missing document: rewrite from memory per OD-15 (i) only with `putIfAbsent`, only from a document accepted within the last lease, and only if no verified member's card reports a higher sequence. *(Still holds for an ABSENT document. For a BEHIND document, superseded 2026-10-29 by amendment 8: `replace` over the exact stored bytes, with a union.)* ~~First start: a member that finds no document and no other verified member writes the first one.~~ *(Superseded 2026-10-29 by amendment 1.)*
- Not fresh, absent or unauthentic: every `OversightState` read answers paused, or refuses with the OD-215-10 code; ~~the instance reports not serving through `ClusterSecurityStatus`~~ *(superseded 2026-10-29 by amendment 6: freshness is reported through `ClusterSecurityStatus.withPause` and `HazelcastOversightState.securityStatus()`, and the `PrivacyCluster` serving gate is not closed)*. Publish the accepted sequence to `MemberCards`.

## Acceptance
- [ ] T4, in `PauseDocumentTest`, with injected clocks: deleting the document makes every instance refuse and `pause.fresh` read 0; a document older than one an instance has seen is rejected by it; a restarted instance accepts a replayed older document only inside the lease and the cold-start gate, and the test asserts the window equals the configured lease (the residual, named as such); two concurrent writers both succeed in sequence with neither pause lost; a member with its wall clock skewed by more than the tolerance does not make a healthy document stale after first acceptance.
- [ ] T4 merge cases, in `PauseSplitBrainTest`: a pause issued on the smaller side of a split is present after the heal; equal-order documents with different content merge by union and count `pause.conflict`; ~~a resume on one side may be undone by the heal, and the test is named for that cost (OD-14)~~ *(superseded 2026-10-29 by amendment 3: a resume on one side and a pause on the other both hold after the heal)*.
- [ ] OD-15 rule: a member that missed a pause (partitioned) does not rewrite an older document while a verified member reports a higher sequence; a member that accepted the latest document within the lease does rewrite a deleted one. *(Kept; amendments 1, 2 and 8 add cases.)*
- [ ] An attacker document (plaintext, 0.7 format, random envelope, envelope under another key) written by a keyless member or client never lifts a pause, and is counted.
- [ ] Every existing `HazelcastOversightTest` case passes; changes are only where a raw value was read or written. The `OversightState` SPI and `OversightSnapshot` are unchanged *(an interim amendment 9 of 2026-10-29 added default methods; withdrawn the same day by owner decision 5, revised)*.
- [ ] `pause.fresh` and `pause.age_seconds` are emitted as decided under OD-215-5, and `ClusterSecurityStatus` reports freshness without any pause content.
- [ ] *(Amended 2026-10-29 by amendment 6: the audit order and both codes are also pinned in the new server-module test.)* OD-215-16 (a), with the serving gate closed by an unverified member under `refuse` (227): a pause is written and seen by every instance; a resume is refused with `CLUSTER_OPERATION_REFUSED` and audited as 245 audits a resume whose apply fails (`ALLOW:RESUMED`, then a linked `DENY`); after the gate reopens, the resume succeeds. `HazelcastOversightState` reaches the document through `instance()`, never `servingInstance()` (227's `ServingInstanceArchitectureTest` allows it), and the resume refusal comes from the status check, not from the accessor.
- [ ] `OversightMapsBoundary`: the oversight map holds exactly the document, which opens under the test key; scope ids appear only inside the plaintext.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Amendments 2026-10-29 (after PR #191 review)

The review of PR #191 (branch `task/228-signed-leased-pause-document`, head 93877409) found two P1 and several P2 design gaps. An architect settled them, and the owner decided the two open calls on 2026-10-29 (amendments 2 and 5, option (a) each). These amendments win where they disagree with the text above; the superseded sentences above are marked, not deleted. Names below (`Status` values, the `Peers` seam, `beforeCommit`, `pushHeld`, `tick`) are those of `PauseDocument.java` at 93877409. "Held" is the highest document this member accepted; "stored" is the authentic document read from the map; "base" is `held ⊔ stored`.

**Revised 2026-10-29 after external review of PR #194 (P1, repeated pauses overtaking missing state): owner chose (e).** The review found that the first version of amendment 3 still loses pauses. A and B hold pauses at seq 5, and storage is rolled back to seq 3. A behind member C then issues three pauses (seq 4, 5, 6) that lack A's and B's pauses. Seq 6 outranks them, corroboration passes, and A's and B's pauses are lifted. The root cause is that acceptance replaces the held document by order, and a resume is a bare removal. The owner's decisions of 2026-10-29:
1. Option (e): pause state becomes an observed-remove set with explicit tombstones. It replaces the sequence-ordered model of pause content. Sequence and refresh stay, for order and freshness only. Lineage option (d) was rejected.
2. No automatic tombstone GC in 0.8. The ack-gated GC is follow-up brief 295.
3. Operating cap: 16,384 elements plus tombstones, with 16 slots reserved for `pause all`. `1 << 20` stays as the decode guard. *(Meaning changed 2026-10-29: 16,384 total entries is preserved, enforced as 8,192 elements, with the last 16 element slots reserved for `pause all`; tombstones ≤ elements, so the total cannot pass 16,384. See amendment 4. Coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override.)* *(Refined 2026-10-29, coordinator decision 2026-10-29, owner may override: the 16,384 bound holds for every document built by local writes. Only a document formed by merging split sides can be larger, and it is bounded by the separate format bound of 65,536 elements and 65,536 tombstones. See amendment 4, "Operational cap and format bound".)* *(Revised again, coordinator decision 2026-10-29, owner may override: the 16-slot reserve is gone. Non-`all` pauses stop at 8,176 elements; `pause all` is accepted whenever the base plus one stays within the format bound, so the reserve is the whole gap from 8,176 to 65,536, for `pause all` only. The 16,384 bound holds for local writes except through `pause all` churn, which grows up to the format bound without GC; 295 is the fix. See amendment 4.)*
4. A pause always mints a fresh element. Pauses are never deduped.
5. ~~The resume response gains one additive field reporting whether the target is still paused and how many ids were cleared (amendment 9).~~ **(5, revised 2026-10-29, owner)** The resume response gains one additive field, `stillPaused`, and nothing else. There is no cleared count, and no change to the core `OversightState` interface (amendment 9).
6. `pause.conflict` is redefined (amendment 7). No new metric.

Amendments 3 and 4 below replace the first versions, which are kept, marked superseded, at the end of this section. Amendments 1, 2 and 5 to 8 are touched up in place. Where a touched-up amendment and the superseded text disagree, the revision wins.

### 1. First start (P1; touched up in the revision)
- A member with `held == null` that finds the document ABSENT may `putIfAbsent` Doc(1) only if all of these hold:
  - it is alone, or it is the oldest member (first in the Hazelcast member list);
  - `MemberCards.unverifiedCount() == 0`;
  - `highestReportedPauseSequence() == 0`, counting every verified card, its own included;
  - *(added in the revision)* it has been up for at least one lease, by its own monotonic clock, and every verified card has read 0 at every look during that lease. A card above 0 at any look restarts the wait.
- Doc(1) is empty: no elements, no tombstones. A wrong Doc(1), written where a document should have existed, is merged by every member that holds more (amendment 3), never accepted as a replacement, so it cannot lift a pause. The repair tick then writes `held ⊔ Doc(1)` at seq 2.
- A member with `held != null` never takes this path; it uses the OD-15 restore.
- Why the bare "no card above 0" rule is unsafe: cards can be deleted, and stale seq-0 cards can be replayed; either makes a lost pause look like a first start. The uptime wait closes the window in which a seq-0 card replay, made just as the oldest member starts, is the only card it has seen.
- Residual, recorded: if the oldest member is hung, no member writes Doc(1); an operator pause is the fallback.
- Tests (the `Peers` seam gains `unverifiedCount()` and `isOldest()`; uptime comes from the injected nano clock):
  - [ ] three members started together produce exactly one Doc(1), and every read on every member works;
  - [ ] a full cluster restart produces exactly one Doc(1);
  - [ ] two eligible members race: one document, and `pause.conflict` is not counted;
  - [ ] attacker shapes, each of which must not write Doc(1), must refuse reads until a peer restores, and must keep the pause: a pause at seq 2 held by A and B, the document deleted, C restarts; the same with the cards also deleted; the same with stale seq-0 cards replayed;
  - [ ] the oldest member, with any card reporting above 0, does not write;
  - [ ] a 0.7 member or a card-less member present means no first start;
  - [ ] *(revision)* the oldest member does not write Doc(1) before one lease of uptime, and does write it after;
  - [ ] *(revision)* seq-0 card replay: A and B hold a pause at seq 3, the document and the cards are deleted, the oldest member restarts, and seq-0 cards for A and B are replayed while it waits. A's or B's real card arriving inside the lease stops Doc(1), and the pause survives on every member. A Doc(1) forced in through `beforeCommit` is merged on A and B, and the pause is still in force afterwards.

### 2. Lapsed-lease recovery (P1; owner decision (a): automatic, on the tick only, never on the read path; unchanged in the revision)
- A member with `held != null` whose lease has lapsed, and whose stored document is authentic and CURRENT (order equal to held, and the same bytes), may write `refresh + 1` with `replace` over the exact stored bytes, if all of these hold:
  - its cards status serves (a solo member always does);
  - its cards look is within a lease;
  - `peers.highestReportedSequence() <= held.sequence`.
- Never over ABSENT, UNREADABLE, BEHIND or GATED values. Never for `held == null`; the cold-start gate and the replay window (equal to the lease) are unchanged. *(Revision: recovery happens only over a CURRENT document. The CONFLICT status is gone; see amendment 4.)*
- One rate-limited WARN, with no pause content, scope, tool or member address. No new metric.
- Residual, recorded: a lone stale holder whose peers' cards are also stale cannot tell a replay from the truth.
- Tests:
  - [ ] a solo member's monotonic clock passes the lease, reads refuse, one tick recovers, and `pause.fresh` returns to 1 with the same sequence and `refresh + 1`;
  - [ ] the identical bytes re-presented (re-put) do not reset freshness;
  - [ ] no recovery when a peer card reports a higher sequence, when a member is unverified, when the stored value is BEHIND (a replay), when it is ABSENT, or for a restarted member (`held == null`);
  - [ ] lapsed plus ABSENT is recovered only by an operator pause, and that pause keeps the held pauses.

### 3. Pause state is an observed-remove set (revision; replaces the superseded amendment 3)
**The document.**
- The plaintext holds `sequence`, `refresh`, `issuedAt`, the issuer, a format tag, and two sets:
  - `elements`: each has an `id` of 128 random bits from a `SecureRandom`, a target kind (`ALL`, `TOOL` or `SCOPE`) and a name (empty for `ALL`);
  - `tombstones`: ids.
- A target is paused iff some element for it has no tombstone. These are its "live ids". The `OversightSnapshot` is derived from that, and its shape does not change.
- Decode rejects (as UNREADABLE, counted as tamper):
  - an unknown format tag;
  - a duplicate id with a different target;
  - a count above `1 << 20` (`MAX_ENTRIES`, kept as the decode guard);
  - ~~*(added 2026-10-29, round 3)* more than 8,192 elements, or more than 8,192 tombstones (the operating bound of amendment 4). Anything above either is tamper.~~ *(Superseded: coordinator decision 2026-10-29, owner may override. A bound equal to the operating cap made an authentic split-brain merge undecodable.)*
  - *(coordinator decision 2026-10-29, owner may override)* more than 65,536 elements, or more than 65,536 tombstones (the format bound of amendment 4, separate from the operating cap). Anything above either is tamper. Any authentic document at or below the format bound opens, whatever the operating cap.
  A tombstone whose id names no element in the same document is kept, not dropped: it blocks a later replay of that element. For that reason decode does not reject a document with more tombstones than elements, or with tombstones for ids it does not hold; it bounds each set separately instead (coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override).

**Merge on acceptance.**
- Accepting any authentic document sets `held = held ⊔ doc`: the union of the element sets and the union of the tombstone sets. Acceptance never replaces held content, whatever the order.
- A doc whose id names a different target from the same id in held is not accepted. Held is unchanged, the doc is counted as tamper, and one rate-limited WARN is logged with no content.
- Order still decides freshness:
  - a higher (sequence, refresh) resets the lease and raises held's order;
  - a lower or equal order never resets the lease, but its content is still merged;
  - the identical sealed string re-presented resets nothing (amendment 2).
- Merge is commutative, associative and idempotent. No merge removes a live element, except by a tombstone for its id.

**Every write is lock → read → compute from base.** Under the oversight lock, re-read and judge the stored value, form `base = held ⊔ stored` (or `held` alone when stored is ABSENT), and compute:
- pause: add one fresh element for the target. Always, even if the target is already live; never deduped.
- resume: tombstone exactly the ids live for the target in base. If there are none, write nothing (no cleared count is reported; amendment 9). *(Revised 2026-10-29, round 3: standard OR-set remove semantics. The resume fixes its observed set `O` at decision time: the ids live for the target in the first base it reads under the lock. It tombstones only ids in `O`; on a CAS retry see amendment 4. Coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override.)*
- endScope: tombstone the ended scope's live ids in base (kind `SCOPE`, that name). Elements of other kinds with the same name are untouched. *(Revised 2026-10-29, round 3: the same observed-set rule as resume. `O` is the ended scope's live ids in the first base read under the lock.)*
- `sequence = max(held.sequence, stored.sequence) + 1`, `refresh = 0`.
- No peer-derived term appears in any sequence. That covers pause, resume, endScope, `pushHeld`, restore (amendment 8) and `reconcileAfterMerge`. `PauseDocument.java` ~:692 at 93877409 (`top = Math.max(top, peers.highestReportedSequence())`) is removed, as is the `peers.highestReportedSequence() + 1` term at :485.
- A pause over an ABSENT document is `putIfAbsent(held ⊔ change)` at `held.sequence + 1`, or Doc(1){change} when `held == null`. This reconciles OD-215-16. Do not write `all = true` into the document.

**Resume and endScope preconditions** (OD-215-16 doubt handling). Each is checked under the lock, after the read:
- `held == null`: refused with `OVERSIGHT_UNAVAILABLE`.
- `unverifiedCount() > 0`: refused with `CLUSTER_OPERATION_REFUSED`. This adds to 227's not-serving refusal and does not replace it.
- `peers.highestReportedSequence() > top.sequence`, where `top = max(held, stored)`: refused with `OVERSIGHT_UNAVAILABLE`. This covers a cold member and a behind one.
- Fresh and serving, as before (amendment 6).

**Performance.**
- Memoize the decoded document by the exact authenticated sealed string. The same bytes are not opened or decoded twice. Different bytes always are.
- The target view (live ids per target) is recomputed once per accepted document, not per read.

### 4. The commit guard (revision; replaces the superseded amendment 4)
- `commit` refuses, and does not write, any `next` that fails one of these:
  - `next.elements ⊇ stored.elements` and `next.tombstones ⊇ stored.tombstones`, against the stored authentic document;
  - `next.elements ⊆ base.elements ∪ {the one fresh element of a pause}`;
  - `next.tombstones ⊆ base.tombstones ∪ T`. For a resume or endScope, `T` is `O ∩ live(base)`: its observed set (amendment 3) intersected with the ids still live for its target in the current base. *(Revised 2026-10-29, round 3; previously all ids live for the target in base.)* For every other write, `T` is empty: only a resume or an endScope adds a tombstone;
  - the order is above the stored document's, or `next` is a `refresh + 1` successor with identical sets.
  Each refusal is an Outcome the tests can see.
- Cap (owner decision 3, as re-enforced 2026-10-29 in round 3). *(Revised: the first version counted `n = |elements| + |tombstones|` against 16,368 and 16,384. Resuming 16,368 live elements then added 16,368 tombstones, passed the cap and blocked `pause all` despite the reserve. Coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override.)* The cap counts elements only. Let `e = |elements|` of `next`.
  - ~~At most 8,192 elements in total.~~ ~~`pause all` may use the last 16 element slots: accepted up to `e = 8,192`, refused above it.~~ *(Superseded by "Operational cap and format bound" below: coordinator decision 2026-10-29, owner may override.)*
  - Tombstones are not counted. A tombstone only ever names a live element id (amendment 3), so `|tombstones| <= |elements|` holds by construction, and the `pause all` reserve holds whatever resumes have accumulated.
  - A resume or endScope is not refused by the operating cap. It adds tombstones only for existing elements. A cap on resume would leave the cluster unable to lift a pause.
  - Residual, recorded: without GC (295), every pause uses an element slot for good. A cluster that reaches the cap can only `pause all` until 295 ships, or until a full restart wipes the state.
- **Operational cap and format bound** (coordinator decision 2026-10-29, owner may override). The operational cap and the format bound are separate numbers. *(Replaces the round-3 rule that bounded encode and decode at the cap. With both at 8,192, a split where each side paused near the cap merged to more than 8,192 elements; that union could not be encoded, so no merge or repair write was made, and a stored oversize document decoded as tamper. It failed closed, but the cluster might never recover. The round-3 residual paragraph about the unencodable merge is removed and replaced by this rule.)*
  - The operational cap is judged on the merged base, `b = |base.elements|`, before the change is added:
    - a non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED` when `b + 1` would exceed 8,176;
    - `pause all` is accepted whenever `b + 1` stays within the format bound (65,536 elements), whatever the 8,192 figure and whether or not a merge happened. The emergency pause must never be blocked.
    - *(coordinator decision 2026-10-29, owner may override.)* The reserve for `pause all` is the whole gap between 8,176 and the format bound, and only `pause all` may use it. *(Replaces the earlier `b < 8,192` / `b > 8,192, merge only` split and the 16-slot reserve. Without GC, each `pause all` mints a fresh element and each resume tombstones it, so 16 pause-all/resume-all cycles used up the 16 slots and `pause all` was then refused with no merge at all; it was also refused at exactly `b = 8,192`. Both contradicted the rule above.)*
  - The format bound, applied to encode and decode alike, is 65,536 elements and 65,536 tombstones. `1 << 20` (`MAX_ENTRIES`) stays as the outer per-list guard.
  - A merge, restore (amendment 8), `pushHeld` or repair write of any authentic union within the format bound is always encodable and is written. The operational cap never refuses one: it applies only to a pause.
  - An encode above the format bound is refused, never truncated. A decode above it is rejected as UNREADABLE and counted as tamper (amendment 3).
  - Size *(coordinator decision 2026-10-29, owner may override)*: a document built by local writes stays at most 16,384 entries (8,192 elements plus at most as many tombstones; decision 3 holds), except through `pause all` churn. Only `pause all` churn or a merge of split sides can make it larger, and both are bounded by the format bound.
  - Residual, recorded *(coordinator decision 2026-10-29, owner may override)*: heavy `pause all` / resume-all cycling without GC grows the document, one element and one tombstone per cycle, up to the format bound. At `b = 65,536` `pause all` is refused with `CLUSTER_OPERATION_REFUSED`, which fails closed. 295 (ack-gated tombstone GC) is the fix.
  - Residual, recorded: a union above the format bound is refused as unencodable, and a stored document above it decodes as tamper. Reaching it needs more than eight partitions each filled to the cap and then merged. In that state reads refuse, which fails closed.
- A lost race (the `replace` fails) re-reads and recomputes from the new base, up to the existing bound (`MAX_ATTEMPTS`), then fails closed.
  - *(Added 2026-10-29, round 3; coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override.)* A resume or endScope keeps the observed set `O` it fixed on its first read (amendment 3). On every retry it recomputes base and writes tombstones for exactly `O ∩ live(new base)`, the ids of `O` still live in the new base. It never adds an id first seen on a retry, so a pause that lands concurrently stays active. If the intersection is empty (everything in `O` was already tombstoned elsewhere), it writes nothing and returns success. `stillPaused` (amendment 9) is still read afterwards.
- `pushHeld` becomes: under the lock, read; if stored lacks any element or tombstone of held, write `held ⊔ stored` at `max + 1`; otherwise write nothing. It never writes over a stored document that already covers held.
- The CONFLICT status and its branches go (`Status.CONFLICT`, the `case CONFLICT -> pushHeld()` arm, and the equal-order union in `judge`), along with `Pauses.union` and `Pauses.covers`.
- Tests:
  - [ ] a rival writes a higher document with an extra pause between read and commit (`beforeCommit`); the final document holds both, at a higher order;
  - [ ] `pushHeld` never writes over a stored document that is a superset of held;
  - [ ] *(round 3)* a pause of X lands via `beforeCommit` between the resume of X's first read and its commit, forcing a retry: after the retry, X stays paused (the new element has no tombstone), every id in the resume's original `O` is tombstoned, and the operator response's `stillPaused` is true;
  - [ ] *(round 3)* the same shape for endScope: a pause of the ended scope landing mid-retry stays active;
  - [ ] *(round 3)* a resume whose `O` was wholly tombstoned elsewhere before its retry writes nothing and returns success;
  - [ ] *(round 3)* a mutant that recomputes `O` on retry fails the first of these;
  - [ ] each guard clause has a test that a mutant without it fails (the Outcome shows it).

### 5. Ending a scope (owner decision (a): `endScope` removes the scope from the document; touched up in the revision)
- *(Revision)* The removal is a tombstone write (amendment 3): it tombstones the scope's live ids in base (its observed set `O`, kept across CAS retries as in amendment 4; round 3), under the resume and endScope preconditions (`held != null`, `unverifiedCount() == 0`, no peer card above top, fresh and serving). It is the LAST step of `ScopeIdentityIndex.endScope`, run only after every data sweep and the approval forget have succeeded.
- Any failure, skipped lock, failed precondition or stale document keeps the pause. When a precondition fails, the call throws its code from amendment 3 after the sweeps, and the data is still erased.
- An ABSENT document is a no-op success. No write if the scope has no live id in base.
- UNREADABLE or GATED: throw.
- Shared deadline: `min(remaining, lockTimeout)`; under a 100 ms floor the step is skipped and counted as a lock timeout.
- `ScopeIdentityIndex` holds its own lazily created, never-started `PauseDocument`. No new constructor parameters.
- ~~An encode-side entry cap equal to the decode cap (`1 << 20`, `MAX_ENTRIES`). A pause past it is refused with `CLUSTER_OPERATION_REFUSED`.~~ *(Superseded in the revision by the operating cap in amendment 4.)*
- Tests, in `KeyedScopeEndTest`:
  - [ ] the ended scope is removed, while other scopes, tool pauses and the global pause survive (the assertions at :98-99, :109 and :189-196 move from raw map entries to the document). *(Revision: a tool paused under the same name as the ended scope also survives, and the scope's element is tombstoned, not deleted);*
  - [ ] not serving: the data is erased, the pause is kept, and the call throws `CLUSTER_OPERATION_REFUSED`;
  - [ ] *(revision)* `held == null`: the data is erased, the pause is kept, and the call throws `OVERSIGHT_UNAVAILABLE`; an unverified member: the same, with `CLUSTER_OPERATION_REFUSED`;
  - [ ] stale document: the same;
  - [ ] a sweep failure keeps the pause;
  - [ ] a replayed older document at a cold index does not lift other scopes, and endScope over it tombstones only the ended scope's ids;
  - [ ] deadline exhaustion keeps the pause and throws the lock timeout;
  - [ ] an oversize encode is refused.

### 6. Status and gating (unchanged in the revision)
- Do NOT close the `PrivacyCluster` serving gate on a stale or absent document; it would deadlock recovery. Freshness stays in `ClusterSecurityStatus.withPause` and `HazelcastOversightState.securityStatus()`.
- Codes: not serving → `CLUSTER_OPERATION_REFUSED`; absent, unauthentic or stale → `OVERSIGHT_UNAVAILABLE`.
- The design's 4.4 sentence on approval reads is met by `state()` and admission, not by approval reads. `HazelcastApprovalStore` is not edited. For the scribe: amend `docs/plan/design/cluster-security.md` 4.4 to say so (this task does not edit design docs).
- Tests:
  - [ ] gate closed: a pause is written and seen by every instance, and a resume gives `CLUSTER_OPERATION_REFUSED` (`UnverifiedMemberTest`, :447-463 and the OD-215-16 (a) cases);
  - [ ] gate open and document stale: reads and resume give `OVERSIGHT_UNAVAILABLE`, `securityStatus()` reports `PAUSE_NOT_FRESH`, and `cluster.securityStatus().serving()` stays true;
  - [ ] `RefusedResumeAuditCodeTest` (new, `data-prism-server`), with a stub `OversightState`, asserts the audit order `ALLOW:RESUMED` then a linked `DENY`, once for each code.

### 7. Metrics (touched up in the revision)
- 228 takes the `dataPrismClusterOversightState` bean method and its constructor call in `ClusterBackedState.java` (:172-175 at ca7e9b0c), copying the approval-store pattern at :177-181: `DataPrismProperties`, plus `ObjectProvider<PrivacyMetrics>` with `getIfAvailable(PrivacyMetrics::none)`. No bean is added, so the bean inventories do not change.
- *(Revision)* `pause.conflict` is redefined, with no new metric. It counts:
  - a stored authentic document that is not a superset of held (it lacks an element or a tombstone held has);
  - a document that is not newer than held but brings an element or tombstone held lacks.
  Each distinct sealed string is counted at most once (by the memo), so a tick that sees the same stored bytes again does not count again. Two members racing to write Doc(1), where one loses the `putIfAbsent`, is not a conflict.
- Test:
  - [ ] `ClusterPauseGaugeWiringTest` (new): a recording `PrivacyMetrics` sees `pause.fresh` and `pause.age_seconds` registered exactly once each;
  - [ ] *(revision)* `pause.conflict` increments once for a rolled-back stored document and once for a lower-order document carrying an extra element, and does not increment for the same bytes seen on a second tick.

### 8. Recorded decisions (touched up in the revision)
- *(Revision)* Restore on BEHIND is a union write: `replace` over the exact stored bytes with `held ⊔ stored` at `max + 1`, not `putIfAbsent`. This amends OD-15 (i). Test:
  - [ ] a replayed older document is repaired to a superset of it and of held (elements and tombstones).
- The 0.7 fence (`all = TRUE`) is asserted after this member's commit; the residual is up to half a lease, matching design 4.8. `OversightMaps` adds an explicit never-evict `MapConfig` (eviction `NONE`, no TTL) for `dataprism.oversight`. Test:
  - [ ] `OversightMapsBoundary` asserts both oversight maps' effective config (no eviction, no TTL).
- An unreadable document is repaired only by an operator pause, through `replace` over the exact stored bytes with `held ⊔ change`. Test:
  - [ ] plaintext, 0.7-format, random and wrong-key envelopes are never overwritten by a tick, are counted as tamper, and are replaced by an operator pause that keeps the held pauses.
- `OversightMapsBoundary.check()` must fail, not skip, when the keyed names came from the test name key and the envelope does not open.
- The MERGING→MERGED `mergeBase` capture gets a test. `reconcileAfterMerge` writes `mergeBase ⊔ held ⊔ stored` at `max(held, stored) + 1` only if stored lacks something. Test:
  - [ ] a mutant whose `beginMerge` is a no-op fails it (today such a mutant still passes 5 of 5 `PauseSplitBrainTest` cases).

### 9. Resume response (revision; owner decision 5, revised 2026-10-29)
- `POST /operator/resume` keeps `status` and `target` and gains one additive top-level field, `stillPaused` (boolean), matching the controller's flat response maps: `{"status": "RESUMED", "target": "TOOL", "stillPaused": false}`.
  - The controller computes it from `OversightState.snapshot()`, read after the resume returns, for the resumed target only: `ALL` reads `allPaused()`; `TOOL` reads `pausedTools().contains(name) || allPaused()`; `SCOPE` reads `pausedScopes().contains(name) || allPaused()`. A global pause still refuses calls to a tool or scope target, so it keeps that target `stillPaused` true: the response never suggests a target is unblocked while calls to it are still refused (fail closed; coordinator decision 2026-10-29, owner may override).
  - It is true when a concurrent pause that the resume did not observe keeps the target paused.
  - If that `snapshot()` throws, the response reports `stillPaused` true (fail closed: never claim a lift that cannot be confirmed). The resume has already applied and been audited, so no further audit is written and the status stays 200 (coordinator decision 2026-10-29, owner may override).
  - No other field changes, and a refused resume's body and audit are unchanged.
- No cleared count. The `OversightState` SPI, `OversightSnapshot`, `InMemoryOversightState` and `HazelcastOversightState`'s public surface are unchanged; no core file is edited.
- Tests:
  - [ ] `ResumeEffectTest` (new, `data-prism-server`): `stillPaused` is true after a resume when a concurrent unobserved pause keeps the target paused (on Hazelcast, a pause of the same target committed via `beforeCommit` while the resume is built), true when a tool target X is resumed while `pause all` is active, and the same for a scope target; and false otherwise (a clean resume with no global pause);
  - [ ] `stillPaused` true when the post-resume `snapshot()` throws;
  - [ ] `ResumeAuditOrderingTest`'s exact-map assertion at :155 gains `"stillPaused", false` and nothing else.

#### Superseded amendment 9 (interim version of 2026-10-29; withdrawn by owner decision 5, revised)
- ~~`effect`: `{"stillPaused": <boolean>, "cleared": <int>}`, computed by three additive `default` methods on `OversightState` (`resumeAllReporting()`, `resumeToolReporting(String)`, `resumeScopeReporting(String)`) returning a new `ResumeOutcome(boolean stillPaused, int cleared)` record, overridden by `HazelcastOversightState`, with a `ResumeOutcomeDefaultsTest` in core.~~

### Acceptance for the revision (all in `PauseDocumentTest` or `PauseSplitBrainTest` unless named)
- [ ] The reviewer's shape: A and B hold {p, q} at seq 5. Storage is rolled back to seq 3; in two more runs the document is deleted instead, or replaced by garbage. C, behind at 3, pauses x three times and so climbs to seq 6. In every run: every instance ends with targets {p, q, x} paused; a repair write happens at seq 7; no tombstone exists for any id of p or q.
- [ ] Variants of that shape: C cold (`held == null`); C stalled (its cards look is old); and the rollback taken after a resume of p (p stays lifted on A and B, and on C once it merges).
- [ ] Split-brain: a resume of p on one side and a pause of y on the other both hold after the heal.
- [ ] A concurrent pause of X committed (via `beforeCommit`) while a resume of X is built: X stays paused, the operator resume response reports `stillPaused` true (amendment 9), and a second resume clears it.
- [ ] Two writers race on the CAS, a pause each: both elements survive.
- [ ] A replay of an old document holding an element that is now tombstoned: the element stays lifted on every warm member.
- [ ] A cold member given a replay within the cold-start gate shows the replay's state until the next repair tick (by any warm member), then shows the union.
- [ ] ~~Cap: at 16,368 entries a non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED`; `pause all` is then accepted from the reserve; a resume at the cap is accepted; an encode over the decode guard is refused.~~ *(Superseded 2026-10-29, round 3, by the elements-only cap of amendment 4.)*
- [ ] Cap (round 3): 8,176 non-`all` pauses succeed; all 8,176 are resumed (8,176 tombstones); `pause all` then succeeds; the 8,177th non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED`; a resume at the cap is accepted; ~~`pause all` is refused past 8,192 elements; an encode over the decode bounds is refused~~ *(revised: coordinator decision 2026-10-29, owner may override)* ~~with no merge, `pause all` at `b = 8,192` is refused;~~ *(removed: coordinator decision 2026-10-29, owner may override)* an encode over the format bound (65,537 elements, or 65,537 tombstones) is refused, never truncated.
- [ ] ~~Decode (round 3): a document with 8,193 elements, or with 8,193 tombstones, is rejected as UNREADABLE and counted as tamper; one with 8,192 of each opens.~~ *(Superseded: coordinator decision 2026-10-29, owner may override.)*
- [ ] `pause all` reserve (coordinator decision 2026-10-29, owner may override): with 8,176 non-`all` elements, 16 or more (test with at least 20) `pause all` / resume-all cycles each succeed, and `pause all` is still accepted afterwards; with no merge, `pause all` at `b = 8,192` is accepted; `pause all` at `b = 65,536` is refused with `CLUSTER_OPERATION_REFUSED` and writes nothing (fails closed; the residual of amendment 4).
- [ ] Decode (coordinator decision 2026-10-29, owner may override): a document with 65,537 elements, or with 65,537 tombstones, is rejected as UNREADABLE and counted as tamper; one with 65,536 of each opens; one with 16,000 elements opens.
- [ ] Split-brain over the cap (in `PauseSplitBrainTest`; coordinator decision 2026-10-29, owner may override): each side of a split adds 8,000 distinct non-`all` pause elements. After the heal, the merged document encodes and is written to the map with 16,000 elements, and every member holds all 16,000. Afterwards a non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED`, and `pause all` is accepted and written (16,001 elements).
- [ ] endScope tombstones only the ended scope, including over a replay at a cold index, and refuses when `held == null` or a member is unverified (amendment 5).
- [ ] First start: the uptime and seq-0 card-replay tests of amendment 1.
- [ ] Property tests over random sequences of documents (seeded JUnit loops; no new dependency): merge is commutative, associative and idempotent; no merge removes or lifts an element unless that element's tombstone is in the merged state (equivalently, after a merge a target is paused iff the merged state holds some element for it without a tombstone) *(wording aligned with 295 by coordinator decision 2026-10-30 after external review of PR #194 (round 4), owner may override.)*.
- [ ] Decode rejects a duplicate id with a different target, and rejects an unknown format tag; a tombstone for an unknown id is kept through a decode and encode round trip.
- [ ] Memo: identical sealed bytes are not decoded twice (a counting decoder seam), and different bytes are.
- [ ] `stillPaused` is true after a resume when a concurrent unobserved pause keeps the target paused, true when TOOL X is resumed while `pause all` is active, and false otherwise (amendment 9).
- [ ] Mutants, each of which must fail at least one test (list them in the Outcome with the failing test):
  - accept replaces held instead of merging;
  - `next` drops a stored element or tombstone;
  - a pause adds a tombstone;
  - a resume tombstones ids outside base or outside its target;
  - a pause dedupes against a live element;
  - the cap truncates instead of refusing;
  - the cap counts tombstones (round 3);
  - the format bound equals the operational cap (8,192), which must fail the split-brain over the cap test (coordinator decision 2026-10-29, owner may override);
  - `pause all` is subject to the 8,192 limit, which must fail the `pause all` reserve test (coordinator decision 2026-10-29, owner may override);
  - a resume or endScope recomputes `O` on a CAS retry instead of keeping its first observed set (round 3);
  - the memo ignores the bytes (keys on sequence alone);
  - endScope removes elements by name, or across kinds, instead of tombstoning the scope's ids;
  - the peer-derived sequence bump is restored;
  - `stillPaused` is always false;
  - `stillPaused` ignores `allPaused()` for TOOL or SCOPE targets.

### Superseded (kept for the record; replaced by the revision above)

#### Superseded amendment 3 (first version): A pause from a member that is behind (P2)
- Every write's pauses are `held.pauses` ∪ the stored authentic document's pauses (whatever their order) + the change.
- `sequence = max(held.sequence, stored.sequence) + 1`. The `peers.highestReportedSequence() + 1` bump (`PauseDocument.java:485` at 93877409) is dropped.
- Resume only: refuse with `OVERSIGHT_UNAVAILABLE` when `peers.highestReportedSequence() > top.sequence`. This covers a cold member too.
- A pause over an ABSENT document: `putIfAbsent(held ∪ change)` at `held.sequence + 1`, or Doc(1){change} when `held == null`. This reconciles OD-215-16. Do not write `all = true`.
- Tests (using the `beforeCommit` seam): A and B hold P and Q at seq 5; C is cold or stalled at 3; the document is deleted, replaced by a seq-3 replay, or replaced by garbage. C pauses X. For each shape:
  - after the heal, every instance shows P, Q and X;
  - C never gets a higher order than A and B;
  - a resume at C is refused while any card reports a higher sequence.

#### Superseded amendment 4 (first version): Every write is built inside lock → read → judge → compute (P2)
`pushHeld` today replaces without judging. Every write path, `pushHeld` included, now judges the stored value under the lock and computes from it:
- CURRENT or ACCEPTED: write nothing.
- ABSENT or BEHIND: the union at `max + 1`.
- UNREADABLE or GATED: overwritten only by an operator pause (amendment 8).
- A lost race re-reads, up to the existing bound, then fails closed.
- A structural guard in `commit`: a non-resume write must have an order above the stored document's, or be an identical-pause refresh successor of it, and must cover the stored authentic document's pauses. A write that fails the guard is not made.
- Tests:
  - a rival writes a higher document with an extra pause between judge and commit (`beforeCommit`); the final document covers both, at a higher order;
  - `pushHeld` never writes over a higher stored document;
  - a mutant that removes the guard fails a test (the Outcome shows it).

### Ownership and ordering (checked 2026-10-29; rechecked in the revision)
- The added Owns (in the Owns block, marked "Added 2026-10-29") are disjoint from the other wave-7 briefs: 211 (Spring audit and cluster-key files), 247 (orchestration, MCP, the `new DefaultContextOrchestrator(` call sites), 256 (validation), 292 (core audit) and 294 (the test harness, filed on PR #190: `FreePorts`, `TestMembers.pair`, `MemberCardTest`, `ApprovalRaceTest`, `SealedWritesTest`, `ApprovalStoreContractTest`, `OperatorHarness.freePort`). 294 does not own `UnverifiedMemberTest`, `KeyedScopeEndTest` or `SoloMemberKeysTest`.
- *(Revision)* `OversightOperatorController.java` and `ResumeAuditOrderingTest.java` are in no other open brief's Owns: not 211, 247, 256, 292, 294 (PR #190), 286 or 287. 294 owns only `OperatorHarness.freePort` in the server module. 273 (wave 15) owns `data-prism-server/src/test/java/**` for configuration arguments only, and runs long after 228. 260 (wave 12) snapshots the SPI signatures after 228; 228 no longer changes them (owner decision 5, revised).
- `ClusterBackedState.java` is shared, and serialised: 228 (wave 7, the oversight bean only), then 287 (wave 8, the trusted-network pass-through; depends on 228), then 286 (wave 10, the health bean and the lock-timeout pass-through; depends on 228 and 287). 286 no longer passes the lock timeout to the oversight store; 228 does.
- 287 also owns the existing hazelcast tests for its declaration, which includes the three test files added here; it depends on 228, so it edits them afterwards.
- *(Revision)* 295 (ack-gated tombstone GC) owns `PauseDocument.java` and its tests after 228 and 287 have merged.

## Preserves
Pause, resume and snapshot semantics for operators; the audit-first operator flow; bounded writes; both cluster codes; `OVERSIGHT_UNAVAILABLE` for an oversight state that cannot be evaluated.

## Out of scope
- The `resume` audit ordering (core task 245, OD-08-14).
- Rotation of the key the document is sealed under (229, deferred past 1.0).
- Spring binding of `pause-lease` and `clock-skew-tolerance` (230, deferred past 1.0; the defaults apply). The readiness contributor that reports freshness (286).
- `TestMembers.java` is read only. `docs/` pages: facts for 213 in the Outcome.
- Tombstone garbage collection of any kind, including acks on tombstones (295). The document only grows in 0.8, up to the cap in amendment 4.
- The operator API reference pages for the new `stillPaused` field: facts for 261 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented scope and tool names. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Both clocks are injected; waits are on events or polled state with a bound.
