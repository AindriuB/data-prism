# 228 — Replace pause flags with one sealed, leased, observed-remove pause document

**Contract version:** 2.3 (2026-11-02)

This version states the whole current contract. It replaces versions 1, 2, 2.1 and 2.2 and every amendment to them. Where version 1, a PR thread or the code on the implementation branch disagrees with this text, this text wins. Earlier wording is kept in "Superseded (history, do not implement)" at the end. Read it for history only and never build from it.

Kept for 1.0 under owner decision (b), 2026-10-23: 1.0 supports multiple instances on a trusted network only, and the sealed-cluster briefs 224, 226, 229, 230, 231 and 264 are deferred past 1.0 (`docs/plan/tasks/deferred/`; 225 was restored to 0.8 by a second owner decision the same day). This brief depends on none of them; `SealedWrites`, keyed names and `ClusterSecuritySettings` are on `main` (223). In 1.0 the sealed stores are the pause document, the member cards and the sentinel (227), and the identity cache and re-identification index (225); the docs (213) name them as what is protected.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main`. The implementation continues on `task/228-signed-leased-pause-document` (PR #191, head 3f472483 when this version was written).
**Depends on:** 219 (answers 10 and 11), 223 (keyed names, `SealedWrites`, `ClusterSecuritySettings`), 227 (`MemberCards`, `ClusterSecurityStatus`, the gate's pause-map exemption). All three are merged.
**Wave:** 7. The other wave-7 briefs (211, 247, 256, 292, 294) have merged.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightState.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PauseDocument.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/OversightMaps.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecurityStatus.java *(insertions only: pause freshness and age)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java *(the card digest field, the card sequence, the format version, publish-on-change, the per-member card sequences and their newer-card-only acceptance, the per-card first-seen nano time, the package-private `nanoClock` seam and look timing on it, member-list change detection, the per-look card markers and the `Peers` hooks only; R7)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java *(the `endScope` pause step, its ordering and its deadline only; R12)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/OversightMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java *(changes only where a stored value is read or written raw, plus new cases)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseDocumentTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseSplitBrainTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java *(the new card cases of A6 only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/KeyedScopeEndTest.java *(the assertions at :98-99, :109 and :189-196, plus new cases)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/SoloMemberKeysTest.java *(the oversight-map layout assertion at :105-106 only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/UnverifiedMemberTest.java *(:447-463 and the OD-215-16 (a) gate-closed cases only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterMembershipTest.java *(the `SETTINGS` map only: the `OVERSIGHT_FENCE_MAP` row)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/LegacyOversightStateTest.java *(one line, `state.pauseTool("seed")`, and its comment, in `aLegacyPauseWrittenAfterConstructionPausesThatScopeOnly`; already made on the implementation branch with owner approval)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java *(the `Sealer.open` allowlist only: the `PAUSE_DOCUMENT` constant and its entry in `onlyScopeIdentityIndexOpensTheReidentificationMap`)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the `dataPrismClusterOversightState` bean method and its constructor call, :172-175 at ca7e9b0c, only; R15)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterPauseGaugeWiringTest.java *(new; name may differ)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java *(the `resume` method's response shape, :109-133 at ca7e9b0c, only: the `stillPaused` field; R13)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ResumeAuditOrderingTest.java *(the exact-map assertion at :155 only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ResumeEffectTest.java *(new; name may differ)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/RefusedResumeAuditCodeTest.java *(new)*
- Read only: `OversightState.java` (core), `OperatorHarness.java` (its `freePort` was 294's), `TestMembers.java`.
- Not owned: `PrivacyCluster.java`, `HazelcastApprovalStore.java`, every other `MemberCards` member. If a clean fix seems to need one of them, stop and report.

## Goal
A deleted, forged or replayed entry can no longer lift a pause. Pause state becomes one sealed observed-remove document of pause elements and tombstones whose content only ever grows by union, written by compare-and-set and merged on every read and every split-brain heal. A member counts as fresh only while one gate, judged on what it holds, its peers' card digests and its own clock, has passed within the last lease. A document that is absent, unauthentic or not fresh means "everything paused" (design 2.3.3, OD-3, OD-4, OD-14, OD-15). Deleting the document becomes a visible denial of service, which is the safe direction.

## Context
- docs/plan/design/cluster-security.md 1.4, 1.9 (member cards), 2.3.3 (the whole section, including the residual paragraph), 4.4 (`pause.fresh`, `pause.age_seconds`, `pause.conflict`), OD-3, OD-4, OD-8, OD-14, OD-15, as amended by 218 (OD-215-5 gauges, OD-215-10 code for a document that is not fresh). The design docs are not yet updated for this version; the scribe changes are listed in the PR that filed it. Where they disagree, this brief wins.
- Task 219 answer 10 (`replace(k, old, new)` against a held lock) and answer 11 (`MERGED` delivery and its order relative to merge policies).
- Task 227: `MemberCards` (`reportPauseSequence`, `highestReportedPauseSequence`, `verifiedMembers`, `unverifiedCount`), `ClusterSecurityStatus`, the gate that never blocks the pause map, and OD-215-16 for oversight writes while not serving. The stores use the gated `PrivacyCluster.servingInstance()`; `instance()` stays ungated and `HazelcastOversightState` keeps using it, so the pause document stays readable and writable while not serving.
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java at `main` 2bbc191f: `FORMAT_VERSION` (:69), the `Card` record and its codec (:76-145), the single-writer `inFlight` flag (:258, :536-548), `highestReportedPauseSequence` and `reportPauseSequence` (:329-340), the card lease check (:671-694).
- `PauseDocument.java` on the implementation branch at 3f472483: `Peers` (:383-395), `judge` (:612), `commit` and `guard` (:872-927), `acceptWritten` (:929), `tick` and `decide` (:989-1110), `beginMerge` and `reconcileAfterMerge` (:1117-1170). The names below are those; they may change.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/OversightState.java: the SPI, unchanged.
- From 218/219 (2026-10-21): this task owns the 0.7 fence write (`all` = TRUE in `dataprism.oversight`) and its re-assertion.
- Settings: 230 is deferred, so `pause-lease` (L) and `clock-skew-tolerance` are not Spring properties in 1.0. The `ClusterSecuritySettings` defaults apply (`PT30S` and `PT5S`) on joining and solo members alike, and the tests construct settings directly.
- Terms. **Held**: the content this member has accepted, the union of every authentic document it has merged. **Stored**: the authentic document read from the map. **Base**: `held ⊔ stored`, or held alone when stored is ABSENT. **Digest(x)**: the card digest of content x (R7). **Seen(M)**: the digests of content member M has held, and of every authentic stored document M has merged (R6). **Verified peer**: another member in Hazelcast's member list whose card opened and was inside its lease at some look (R7, F5). **No-document card**: a card whose member has `held == null`; it carries a distinct marker in place of a digest (R7). **Lapsed**: not fresh. **Order**: the pair (`sequence`, `refresh`), compared lexicographically. **Post-write renewal point**: a gate evaluation that follows a write by this member (R6).

## Invariants
- **F0.** Content only ever unions. No accept, merge, restore or repair removes an element or a tombstone. The only way to lift a pause is a tombstone written by a resume or an `endScope`.
- **F1.** A member M is fresh at time t only if, at some renewal time within the last lease L before t, M passed the single gate:
  - the stored document is authentic and M lacks nothing from it (M has merged all of it), **and**
  - **Covered**: every verified peer still in the member list shows a valid card at the latest completed cards look (F5), and that card's digest is in Seen(M), meaning M holds that content now, has held it, or has merged it as a stored document. A valid no-document card carries no digest and passes with nothing to check (R7). A verified peer with no valid card at that look fails Covered *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  
  The bound on how long a member that stops being covered can stay fresh is W = L + Tp + A. Tp is the maximum time a peer's valid card can show content other than what that peer holds. While card writes succeed, Tp ≤ L/2: the card is published on change (R7), and the half-lease refresh bounds the delay when a change-triggered write is lost or coalesced. When a peer's card writes are deleted or blocked, its last card counts only while it is inside its lease (F5), so Tp ≤ L + `clockSkewTolerance`. A is the maximum accepted age of the cards look the gate judged on, at most one lease (R6). So W = L + Tp + A ≤ 3 L + `clockSkewTolerance` *(197-8: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override; 197-19: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; 197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- **F2.** The gate's inputs are held content, peer card digests, Seen(M) and the monotonic clock, nothing else. The sequence, the refresh counter, the issuer, and who wrote the bytes are never evidence. There is no `renew` flag. Every lease reset goes through the gate, at one of the renewal points listed in R6.
- **F3.** Unverified members never gate freshness for an established member. `unverified-member.action: alert` and the unverified grace period affect only `MemberCards`' serving status. `pause.fresh` is independent of them. Cold-start exception (owner decision 3 of 2026-11-02): a member that has not yet been fresh in this process life needs `unverifiedCount() == 0` for its first renewal. That renewal sets an `established` flag, which is reset whenever held becomes null.
- **F4.** A lapsed member's own writes (a union repair, a reconcile, an operator pause, an ABSENT restore) apply content but never reset its lease. Its lease is reset only by a later gate evaluation that passes. The gate enforces this itself (R6 clause 4); no other rule repeats the condition.
- **F5.** Silence never counts as coverage. A verified peer still in the member list counts in Covered only through a valid card at the latest completed cards look: one that opened, is inside its lease, and is not older, by `cardSequence`, than the latest card this member has accepted from it (R7). A verified peer whose card is gone, expired, blocked, unopenable or older fails Covered, and the digest it showed earlier never stands in for a current card. This holds for an established member too: F3 exempts only members never verified during their current listing. A peer that leaves the member list is forgotten. The only per-peer state kept across looks is the `cardSequence` of the latest accepted card, used for newness alone *(197-12: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; 197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- **Lone member.** With no verified peers, Covered is vacuously true, unless peer state is unknown (R6) *(197-24: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

## Rules

### R1. The document
- One document under a fixed keyed name in `dataprism.oversight.s1`, sealed under the cluster key. Its plaintext holds `sequence`, `refresh`, `issuedAt`, the issuer, a format tag, and two sets:
  - `elements`: each has an `id` of 128 random bits from a `SecureRandom`, a target kind (`ALL`, `TOOL` or `SCOPE`) and a name (empty for `ALL`);
  - `tombstones`: ids.
- A target is paused iff some element for it has no tombstone. These are its "live ids". The `OversightSnapshot` is derived from them, and its shape does not change.
- Decode rejects the document as UNREADABLE, counted as tamper, for:
  - an unknown format tag;
  - a duplicate id with a different target;
  - a list count above `1 << 20` (`MAX_ENTRIES`, the outer guard);
  - more than 65,536 elements, or more than 65,536 tombstones (the format bound, R5).
- Any authentic document at or below the format bound opens, whatever the operational cap. A tombstone whose id names no element in the same document is kept, not dropped: it blocks a later replay of that element. So decode does not reject a document with more tombstones than elements, or with tombstones for ids it does not hold; it bounds each set separately.
- `sequence` and `refresh` order writes (the commit guard, R4) and `sequence` feeds the card *(197-27: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*. They are never freshness evidence (F2).

### R2. Merge on acceptance
- Accepting any authentic document sets `held = held ⊔ doc`: the union of the element sets and the union of the tombstone sets. Acceptance never replaces held content, whatever the order.
- A document whose id names a different target from the same id in held is not accepted. Held is unchanged, the document is counted as tamper, and one rate-limited WARN is logged with no content.
- Held's metadata after a merge *(197-4: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*:
  - its order (`sequence`, `refresh`) is the lexicographic maximum of the orders of the merged documents, held included;
  - its `issuedAt` and issuer are those of the max-order document. On an order tie between documents with different `issuedAt` or issuer, the later `issuedAt` wins, then the issuer greater as encoded bytes, so merge stays commutative *(the tie-break is a coordinator detail)*;
  - so held's order never decreases, and the writes (R3, R4, R9, R11) and R13's `top` read these values.
- Merge is commutative, associative and idempotent, over the sets and the metadata alike. No merge removes a live element, except by a tombstone for its id.
- Every change of held appends `Digest(held)` to Seen (R6) and marks the card for publication (R7). Merging an authentic stored document also appends that document's digest to Seen, whether or not held changed *(197-21: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- Acceptance alone never resets the lease. Only a passing gate does (R6).

### R3. Every write is lock, read, compute from base
Under the oversight lock: re-read and judge the stored value, form base, and compute:
- **pause:** add one fresh element for the target. Always, even if the target is already live; pauses are never deduped.
- **resume:** the resume fixes its observed set `O` when it decides: the ids live for the target in the first base it reads under the lock. It tombstones `O ∩ live(base)`. If that set is empty, it writes nothing and returns success.
- **endScope:** the same observed-set rule, where `O` is the live ids of elements of kind `SCOPE` with that name. Elements of other kinds with the same name are untouched.
- `sequence = max(held.sequence, stored.sequence) + 1`, `refresh = 0`.
- No peer-derived term appears in any sequence: not in pause, resume, endScope, `pushHeld`, restore or `reconcileAfterMerge`. The `top = Math.max(top, peers.highestReportedSequence())` line (`PauseDocument.java` ~:692 at 93877409) and the `peers.highestReportedSequence() + 1` term (:485 there) do not exist.
- A pause over an ABSENT document is `putIfAbsent(held ⊔ change)` at `held.sequence + 1`, or Doc(1){change} when `held == null`. This reconciles OD-215-16. Do not write `all = true` into the document.
- A lost race (the `replace` or `putIfAbsent` fails) re-reads and recomputes from the new base, up to `MAX_ATTEMPTS`, then fails closed. A resume or endScope keeps the `O` it fixed on its first read: on every retry it tombstones exactly `O ∩ live(new base)` and never adds an id first seen on a retry, so a pause that lands concurrently stays active. If the intersection is empty, it writes nothing and returns success.
- After its own commit, `acceptWritten` merges the written document into held and calls the gate as a post-write renewal point (R6). Neither `commit` nor `acceptWritten` takes a renew argument.
- Writes (repair, restore, refresh, first start) happen only on the tick or inside an operator or `endScope` call, never on the read path. The gate itself writes nothing and may run on reads.

### R4. The commit guard
- `commit` refuses, and does not write, any `next` that fails one of these:
  - `next.elements ⊇ stored.elements` and `next.tombstones ⊇ stored.tombstones`, against the stored authentic document;
  - `next.elements ⊆ base.elements ∪ {the one fresh element of a pause}`;
  - `next.tombstones ⊆ base.tombstones ∪ T`. For a resume or endScope, `T` is `O ∩ live(base)`. For every other write `T` is empty: only a resume or an endScope adds a tombstone;
  - the order (sequence, then refresh) is above the stored document's, or `next` is a `refresh + 1` successor of it with identical sets.
- Each refusal is an `Outcome` the tests can see.
- `pushHeld`: under the lock, read; if stored lacks any element or tombstone of held, write `held ⊔ stored` at `max + 1`; otherwise write nothing. It never writes over a stored document that already covers held.
- There is no CONFLICT status: no `Status.CONFLICT`, no `case CONFLICT -> pushHeld()` arm, no equal-order union in `judge`, no `Pauses.union` or `Pauses.covers`.

### R5. Operational cap and format bound
- The operational cap is judged on the merged base, `b = |base.elements|`, before the change is added, and applies only to a pause:
  - a non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED` when `b + 1` would exceed 8,176;
  - `pause all` is accepted whenever `b + 1` stays within the format bound (65,536 elements), whether or not a merge happened. The reserve for `pause all` is the whole gap from 8,176 to 65,536, and only `pause all` may use it. The emergency pause is never blocked below the format bound.
- Tombstones are not counted. A resume or endScope is never refused by the cap: it only tombstones existing elements, and a cap on it would leave the cluster unable to lift a pause.
- The format bound, applied to encode and decode alike, is 65,536 elements and 65,536 tombstones. `1 << 20` stays as the outer per-list guard.
- A merge, restore, `pushHeld` or repair write of any authentic union within the format bound is always encodable and is written.
- An encode above the format bound is refused, never truncated. A decode above it is UNREADABLE and counted as tamper (R1).
- Size: a document built by local writes stays at or below 16,384 entries (8,176 non-`all` elements plus at most as many tombstones) except through `pause all` churn. Only that churn or a merge of split sides makes it larger, and both are bounded by the format bound.

### R6. The freshness gate
- One method (`gate`, name may differ) is the only code that resets the lease, that is, sets the local receipt time (`receivedAt`). No other assignment of `receivedAt = now` exists.
- The renewal points, each of which calls the gate and none of which resets the lease any other way:
  1. `judge`, on the read path and on the tick;
  2. `acceptWritten`, after any commit by this member (pause, resume, endScope, `pushHeld`, repair, restore, first start, refresh);
  3. recovery on the tick (a lapsed member re-judging without writing);
  4. `reconcileAfterMerge` (R11);
  5. the operator writes: pause, resume and endScope, through `acceptWritten`;
  6. refresh (R10), through `acceptWritten`.

  Points 2, 5 and 6 are always post-write renewal points; point 4 is one when it wrote; point 3 is one when the tick wrote before it.
- The gate passes at time t iff all of these hold:
  1. the stored document read at this renewal point is authentic and held has every element and tombstone of it (it is merged first, R2). ABSENT, UNREADABLE and GATED fail this clause;
  2. Covered: every entry of `Peers.peerDigests()` is a digest in Seen(M); an entry marked "no valid card" fails it (F5) *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*;
  3. if `established` is false: `Peers.unverifiedCount() == 0`. A pass then sets `established = true`;
  4. at a post-write renewal point: this member was fresh when the write began, and clauses 1-3 are judged on state read after that write: the stored document is the one the successful CAS wrote (or a re-read after it), held is merged with it, and `peerDigests()` and `unverifiedCount()` are read after the commit. A member that was lapsed when the write began never renews from that write, whatever clauses 1-3 say *(197-3: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.
- Clause 4 is the only place the lapsed-own-write rule lives (F4). R3, R9, R11 and R13 refer to it and do not restate it.
- Clause 4 forbids renewal only as part of this member's own write, that is, at the post-write renewal point that follows it. Any later renewal point, a read-path `judge` included, may renew the member if clauses 1-3 pass on the state read then, even when the stored bytes are the very ones this member wrote. This is sound because Covered depends only on peers' card digests: a document this member wrote is never evidence (F2), and its own write cannot put a peer's digest into Seen(M) *(197-11: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
- Seen(M) holds digests of content M has held, and the digest of every authentic stored document M has merged, since M then holds all of that document's content *(197-21: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*. A peer's card digest is never added to it, by a read or otherwise. It has two parts *(197-2: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*:
  - the ring: the last 256 distinct unpinned values, the current `Digest(held)` included, appended on every change of held and on the first merge of each distinct stored document (by the memo, R17). Only unpinned entries age out of it;
  - the pinned entries: every value in Seen(M) that equals a digest entry of `peerDigests()` is pinned and never evicted. It is unpinned when no entry of `peerDigests()` equals it any longer: the peer's card changes or stops being valid, or the peer leaves the member list *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*. An entry that becomes unpinned re-enters the ring at its head, as if just appended, so it survives 256 further changes of held before it can be evicted *(197-15: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
  - Bound: the number of pinned entries is at most the number of listed members, so Seen(M) holds at most 256 plus that many digests.
- Coverage is membership in Seen(M), not equality with the current `Digest(held)`. A peer that holds content M held earlier is covered.
- `established` is reset to false whenever held becomes null.
- `fresh()` is true at t iff the gate passed at some renewal point within the last L before t, by the injected nano clock. Not fresh, ABSENT or unauthentic: every `OversightState` read answers paused or refuses with `OVERSIGHT_UNAVAILABLE` (OD-215-10).
- `alert`, the unverified grace period and `MemberCards.status().serving()` are not inputs to the gate (F3).
- The cold-start wall-clock gate is unchanged: the wall clock is used only to gate the first acceptance after a cold start. A document whose `issuedAt` is older than L plus `clockSkewTolerance` is not accepted by a member with `held == null` (GATED). After first acceptance a skewed wall clock has no effect on freshness.
- **Peer state unknown.** While any other member is in Hazelcast's member list, peer state is unknown if any of these holds:
  - this member has not completed a cards look since it started (this process life);
  - its latest completed cards look is older than one lease;
  - the member list has changed since that look (a member joined or left), detected by a membership listener or by comparing the member-list version or UUID set recorded at the look with the current one; it stays unknown until a new look completes *(197-23: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*;
  - in that look every other member's card that was found was rejected as expired *(197-24: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

  While peer state is unknown, clause 2 (Covered) and clause 3 (cold-start unverified) both fail: an empty `peerDigests()`, or an `unverifiedCount()` of 0, read with no look or from a stale look is not evidence. The two conditions are one rule and are treated alike. The rule does not apply to a lone member (no other member in the member list). One lease is therefore the maximum accepted look age A of F1 *(197-10: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; the stale look failing clause 3 as well as clause 2 is a coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
- Look age is measured on the injected monotonic nano clock, through `Peers.lastLookNanos()` (R7), never on the wall clock, so a wall-clock jump changes no look age. Card validity is still 227's wall-clock lease check. A backward jump cannot make an older card count, because newness is by `cardSequence`, and cannot keep any card valid for more than L after this member first saw it, because validity is also capped on the nano clock (R7) *(coordinator decision 2026-11-02, owner may override)*. A forward jump past the card lease makes every peer card read as expired, which is peer state unknown: the member is not covered and lapses once its lease passes, which fails closed *(197-13: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; 197-24: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

### R7. Card v2 (`MemberCards`)
- The card gains `pauseDigest`: the first 16 bytes of SHA-256 over a 4-byte big-endian count of element ids, the element ids sorted ascending as unsigned 16-byte values, a 4-byte count of tombstone ids, then the sorted tombstone ids. The two counts stop an id that moves between the lists from leaving the digest unchanged *(the counts are a planner detail; owner may override)*.
- A member with `held == null` publishes a no-document card: the digest field carries a distinct "no document" marker (for example a presence byte of 0 and no digest bytes), never `Digest(empty)` and never equal to any digest. `Digest(empty)` is reported only by a member that holds an empty document. A valid no-document card opens and verifies its member like any card. It is excluded from `peerDigests()`, so its member passes Covered with no digest to check, and any digest that member showed before stops counting (which unpins it, R6) *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*. So a restarted member never makes an established member stale, and once any member holds a document every other member can be covered by it *(197-1: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.
- The card keeps `highestPauseSequence` as 227 left it, but nothing in this brief reads it: resume has no sequence precondition (R13) and first start reads the digest (R8) *(197-27: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- `FORMAT_VERSION` becomes 2. A version-1 card does not open as a card (the member is unverified), and that is tested. The cutover is non-rolling, as in 227.
- The card is published when the reported digest or sequence changes, as well as at half lease, inside the single-writer `inFlight` rule: no two card writes run at once. A change seen while a write is in flight is published by the next write, which starts when the in-flight one completes or at the next schedule pass, whichever comes first. The latest value wins.
- `MemberCards` keeps, per member in the live member list, only the `cardSequence` of the latest card it accepted from that member, for newness. It keeps no digest across looks: what `peerDigests()` reports for a peer is what that peer's valid card showed at the latest completed look (F5) *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*. The entry is dropped when the member leaves the list.
- Card newness *(197-12: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*:
  - Card v2 carries a sealed `cardSequence`: a per-process counter that the writing member increments on every card write. The entry is keyed by member UUID, and a restarted member has a new UUID, so the counter never needs to survive a restart.
  - A card whose `cardSequence` is greater than the recorded one is accepted, and its sequence is recorded. A card whose `cardSequence` equals the recorded one is that same card and stays valid while its validity (below) lasts. A card with a lower `cardSequence`, a replayed no-document card included, is not a valid card: it records nothing, and its member has no valid card at that look (F5) *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  - Validity on the nano clock *(coordinator decision 2026-11-02, owner may override)*: when this member first sees a given peer card (that peer, that `cardSequence`), it records `firstSeenNanos` on `MemberCards`' `nanoClock`, alongside the recorded `cardSequence`. The card is valid only until the earlier of its wall-clock lease end (plus `clockSkewTolerance`, as 227 checks it) and `firstSeenNanos + L`. A wall-clock jump back on this member therefore never keeps a card valid for more than L after it was first seen. The timestamp is replaced when a newer card is accepted and dropped with the entry when the member leaves the list.
  - Newness is judged on the card sequence, not on a wall-clock `issuedAt`, so a peer whose wall clock jumps back cannot have its newer digest ignored *(the choice of sequence over `issuedAt` is a planner detail; owner may override)*.
- Hooks *(197-14: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*. Names may differ; these are all of them:
  - `Peers.peerDigests()`: for every verified peer in the member list, what it showed at the latest completed look: the digest of its valid card, or "no valid card" (F5). A peer whose valid card at that look is a no-document card, and a listed member never verified during its current listing, are absent from it (R6 clause 2) *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  - `Peers.unverifiedCount()`: as in 227 (R6 clause 3, R8, R13).
  - `Peers.lookCompleted()`: true once this member has completed a cards look since it started and since the latest change of the member list (R6, peer state unknown) *(197-23: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  - `Peers.lastLookNanos()`: the time at which the latest cards look completed, read from `MemberCards`' `nanoClock` and never from its wall clock (R6, peer state unknown and look age).
  - `Peers.lookMarkers()`: per verified member, this one included, the marker its card carried at the latest look (no document, or a digest), with that look's nano time, so first start can check every look during its wait (R8).
  - Card newness: the `cardSequence` acceptance above, inside `MemberCards`; it has no hook of its own but is what `peerDigests()` reflects *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  - Inputs to `MemberCards`: a digest reporter (or no-document marker) beside `reportPauseSequence`, and a change notice for publish-on-change. Only the main `HazelcastOversightState` document is the digest reporter; the `ScopeIdentityIndex` document never reports (R12) *(197-25: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  - Nano clock seam *(197-22: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*: `MemberCards` gains a package-private `LongSupplier nanoClock`, defaulting to `System::nanoTime`. Tests set it to the same nano clock they inject into `PauseDocument`, since look age compares the two. `lastLookNanos()` and the `lookMarkers()` times read it. `PrivacyCluster.Environment` is not changed; it carries a wall clock only. `HazelcastOversightState` reads `lastLookNanos()` only through `Peers`.
  - `Peers.highestReportedSequence()` is removed: nothing reads it (R13) *(197-27: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
  - `PauseDocument` exposes `established()` package-private for tests.

### R8. First start
- A member with `held == null` that finds the document ABSENT may `putIfAbsent` an empty Doc(1) (no elements, no tombstones) only if all of these hold, judged under the oversight lock in the same decision as the write:
  - it is alone in Hazelcast's member list (the lone-member exemption: no uptime wait), or it is the oldest member (first in the list) and has been up at least one lease by its own nano clock;
  - `unverifiedCount() == 0`: every member is verified;
  - every verified card, its own included, was a no-document card or reported `Digest(empty)` at every look during that lease. Any other digest at any look restarts the wait. First start reads these markers from the cards directly, not through `peerDigests()`.
- A wrong Doc(1), written where a document should have existed, is merged by every member that holds more (R2), so it cannot lift a pause. The repair tick then writes `held ⊔ Doc(1)` at `max(held.sequence, 1) + 1`, as R3's sequence rule gives *(197-17: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
- A member with `held != null` never takes this path.
- After first start, freshness comes from the gate like any other renewal.

### R9. Lapsed holders, restore and repair
- Owner decision (b): a member with `held != null` whose stored document is BEHIND (it lacks an element or tombstone of held) writes `held ⊔ stored` at `max + 1` with `replace` over the exact stored bytes. Over ABSENT it writes `putIfAbsent(held)` at `held.sequence + 1`. This holds whether the member is fresh or lapsed. Whether the write renews it is decided by the gate alone (R6 clause 4).
- This is OD-15 (i) as amended by owner decision 5 of 2026-11-02: the restore is a union and has no "no higher sequence" condition, because a union cannot lift a pause (F0). Whether the restorer is fresh afterwards is decided by the gate alone, by digest coverage.
- UNREADABLE or GATED values (plaintext, 0.7 format, random envelope, envelope under another key, or an authentic document too old for the cold-start gate) are never overwritten by a tick and are counted as tamper. Only an operator pause replaces one, with `replace` over the exact stored bytes, writing `held ⊔ gated ⊔ change` under the R4 guard *(197-18: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*:
  - `gated` is the stored value's content, unioned in only because it is authenticated: a GATED document opened and authenticated, and only its age failed the cold-start gate. An UNREADABLE value contributes nothing;
  - for this write, the GATED document is the stored document of R3 and R4: base is `held ⊔ gated`, the guard's superset clause applies to it, and the sequence is `max(held.sequence, gated.sequence) + 1`, an absent term counting as 0. With `held == null` over GATED the write is `gated ⊔ change`. Over UNREADABLE it is `held ⊔ change` at `held.sequence + 1`, or Doc(1){change} when `held == null`;
  - writing it does not pass the cold-start gate by the back door: the member merges what it wrote (R3), but it is fresh only through the gate, and clause 4 applies.
- `held == null` over ABSENT, not eligible for first start: write nothing; reads refuse.
- One rate-limited WARN for a lapse or a recovery, with no pause content, scope, tool or member address. No new metric.

### R10. Refresh writes
- A member that is fresh at the tick, whose stored document opens to exactly its held sets, writes a `refresh + 1` successor (identical sets, new `issuedAt`) once half a lease has passed, by its nano clock, since it first read the current stored bytes. This keeps `issuedAt` inside the cold-start gate (R6) and re-asserts the 0.7 fence (R16).
- A lapsed member never writes a refresh. Writing or reading a refresh renews nobody beyond what the gate gives (F2) *(planner reading of decision 5 of 2026-11-02; owner may override)*.

### R11. Split-brain
- `beginMerge` captures held as `mergeBase` on MERGING. On MERGED, `reconcileAfterMerge` writes `mergeBase ⊔ held ⊔ stored` at `max(held, stored) + 1`, only if stored lacks something of it, and then calls the gate (R6). When it wrote, that call is a post-write renewal point and clause 4 applies; when it wrote nothing, it is an ordinary one.
- A resume of p on one side and a pause of y on the other both hold after the heal (OD-14 under owner decision (e)).

### R12. Ending a scope (owner decision (a))
- The removal is a tombstone write (R3): it tombstones the scope's live ids in base (its `O`, kept across CAS retries). It is the LAST step of `ScopeIdentityIndex.endScope`, run only after every data sweep and the approval forget have succeeded.
- Preconditions, under the lock, after the read: R13's, in R13's order and with its codes. Then, if the stored document is ABSENT, UNREADABLE or GATED, nothing is written and the call throws `OVERSIGHT_UNAVAILABLE`. Over ABSENT the pause is kept: held still holds the scope's element, so a restore brings it back, and the operator retries once the document is restored *(197-26: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- Any failure, skipped lock, failed precondition or stale document keeps the pause. When a precondition fails, the call throws its code after the sweeps, and the data is still erased.
- No write if the scope has no live id in base.
- Shared deadline: `min(remaining, lockTimeout)`. Under a 100 ms floor the step is skipped and counted as a lock timeout.
- `ScopeIdentityIndex` holds its own lazily created, never-started `PauseDocument`. No new constructor parameters.
- That document never publishes card digests; only the main `HazelcastOversightState` document does (R7). It judges Covered with its own Seen, which holds only what it has held or merged since it was created, so it is often not covered. The endScope is then refused with `OVERSIGHT_UNAVAILABLE` (not fresh) after the sweeps: the pause is kept and the data is still erased (Residuals) *(197-25: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

### R13. Resume and `stillPaused`
- Resume and endScope preconditions, checked under the lock after the read, in this order:
  - `held == null`: refused with `OVERSIGHT_UNAVAILABLE`;
  - not serving (`ClusterSecurityStatus`, 227, OD-215-16 (a)): refused with `CLUSTER_OPERATION_REFUSED`;
  - `unverifiedCount() > 0`: refused with `CLUSTER_OPERATION_REFUSED`;
  - not fresh, which includes not covered: refused with `OVERSIGHT_UNAVAILABLE`.
- There is no sequence precondition. Digest coverage, through the fresh-and-covered gate, is the check *(197-27: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- Owner decision 4 of 2026-11-02: an operator pause on a member that is not covered is written and takes effect cluster-wide, but does not make that member fresh, and its resume is refused (not fresh) until it is covered. A covered, fresh member's operator pause does renew it, through the gate (R6, renewal point 5).
- `POST /operator/resume` keeps `status` and `target` and gains one additive top-level field, `stillPaused` (boolean): `{"status": "RESUMED", "target": "TOOL", "stillPaused": false}`.
  - The controller reads `OversightState.snapshot()` after the resume returns, for the resumed target only: `ALL` reads `allPaused()`; `TOOL` reads `pausedTools().contains(name) || allPaused()`; `SCOPE` reads `pausedScopes().contains(name) || allPaused()`.
  - It is true when a concurrent pause that the resume did not observe keeps the target paused.
  - If that `snapshot()` throws, `stillPaused` is true. The resume has already applied and been audited, so no further audit is written and the status stays 200.
  - No other field changes. A refused resume's body and audit are unchanged.
- There is no cleared count. The `OversightState` SPI, `OversightSnapshot`, `InMemoryOversightState` and `HazelcastOversightState`'s public surface are unchanged; no core file is edited.

### R14. Status and gating
- Do not close the `PrivacyCluster` serving gate on a stale or absent document; that would deadlock recovery. Freshness is reported through `ClusterSecurityStatus.withPause` and `HazelcastOversightState.securityStatus()`, with no pause content.
- Codes: not serving gives `CLUSTER_OPERATION_REFUSED`; absent, unauthentic or not fresh gives `OVERSIGHT_UNAVAILABLE`.
- OD-215-16 (a): while `ClusterSecurityStatus` says not serving, a pause is accepted and a resume is refused. The refusal comes from the status check, not from the accessor; `HazelcastOversightState` reaches the document through `instance()`, never `servingInstance()`.
- The design's 4.4 sentence on approval reads is met by `state()` and admission, not by approval reads. `HazelcastApprovalStore` is not edited.

### R15. Metrics and the Spring bean
- 228 takes the `dataPrismClusterOversightState` bean method and its constructor call in `ClusterBackedState.java` (:172-175 at ca7e9b0c), copying the approval-store pattern at :177-181: `DataPrismProperties`, plus `ObjectProvider<PrivacyMetrics>` with `getIfAvailable(PrivacyMetrics::none)`. No bean is added, so the bean inventories do not change.
- `pause.fresh` (1 or 0, from `fresh()`) and `pause.age_seconds` (time since the last passing gate) are emitted as decided under OD-215-5.
- `pause.conflict` (no new metric) counts a stored authentic document that is not a superset of held, and a document not newer than held that brings an element or tombstone held lacks. Each distinct sealed string counts at most once (by the memo). Two members racing to write Doc(1), where one loses the `putIfAbsent`, is not a conflict.

### R16. The 0.7 fence and the maps
- The 0.7 fence (`all = TRUE` in `dataprism.oversight`) is asserted after this member's commits, including refresh writes; the residual is up to half a lease, matching design 4.8.
- `OversightMaps` adds an explicit never-evict `MapConfig` (eviction `NONE`, no TTL) for `dataprism.oversight`.
- `OversightMapsBoundary.check()` fails, not skips, when the keyed names came from the test name key and the envelope does not open.

### R17. Performance
- Memoize the decoded document by the exact authenticated sealed string. The same bytes are not opened or decoded twice; different bytes always are.
- The target view (live ids per target) and `Digest(held)` are recomputed once per change of held, not per read.

## Decisions
Current decisions only, each where it was made. Coordinator and planner calls are marked "owner may override".
- Owner decision (b), 2026-10-23: 1.0 is trusted-network only; this brief stays in 1.0.
- Owner decision (e), 2026-10-29: pause state is an observed-remove set with explicit tombstones (R1-R3). Lineage option (d) was rejected.
- Owner, 2026-10-29: no automatic tombstone GC in 0.8; the ack-gated GC is 295.
- Owner decision 3, 2026-10-29, as re-enforced by coordinator decisions the same day (owner may override): non-`all` pauses stop at 8,176 elements; `pause all` is accepted up to the format bound of 65,536; tombstones are not counted (R5).
- Owner, 2026-10-29: a pause always mints a fresh element and is never deduped.
- Owner decision 5 (revised), 2026-10-29: the resume response gains `stillPaused` only; no cleared count and no core `OversightState` change (R13).
- Owner, 2026-10-29: `pause.conflict` is redefined, with no new metric (R15).
- Owner decision (a), 2026-10-29: `endScope` removes the scope from the document, as its last step (R12).
- Owner decision (b) on lapsed holders (PR #191 review): a lapsed holder writes `held ⊔ stored` at `max + 1` over BEHIND or ABSENT and never renews (R9).
- Owner decisions 2026-11-02 (freshness), which replace every sequence-based freshness rule:
  1. Card v2: a 16-byte truncated SHA-256 digest over the element ids and then the tombstone ids; the sequence stays for first start and resume; publish on change as well as at half lease, within the single-writer rule; `FORMAT_VERSION` bumped and a v1 card rejected; last-seen digests per live member in `MemberCards`; a Seen ring of 256 in `PauseDocument`; `Peers.peerDigests()` and an `established` hook (R6, R7).
  2. Freshness is the single gate of F1, with inputs as in F2 and no renew flag (R6).
  3. The cold-start exception of F3: `unverifiedCount() == 0` for a member's first renewal in this process life, with the `established` flag (R6).
  4. An operator pause on an uncovered member takes effect cluster-wide, does not make that member fresh, and its resume is refused until it is covered (R13).
  5. OD-15 (i) and the refresh text move from "higher sequence" to digest coverage (R9, R10).
  6. The residuals below are documented, not fixed.
- Coordinator, 2026-10-29 (owner may override): `stillPaused` is true for a TOOL or SCOPE target while `pause all` is active, and true when the post-resume snapshot fails, with status 200 and no further audit (R13).
- Planner readings in this version (owner may override): the count prefixes in the digest (R7); refresh writes only by a fresh member (R10). That a cards look older than a lease makes peer state unknown is now a coordinator decision (R6, 2.2 below).
- Coordinator decisions 2026-11-02 after external review of PR #197 round 1, owner may override (contract version 2.1):
  - 197-1: a member with `held == null` publishes a no-document card, excluded from `peerDigests()` and Covered (R7, F5).
  - 197-2: Seen pins every digest that an entry of `peerDigests()` shows; only unpinned entries age out (R6).
  - 197-3: the lapsed-own-write rule is clause 4 of the gate, with the renewal points listed (R6); R3, R9 and R11 refer to it.
  - 197-4: held's order after a merge is the maximum (`sequence`, `refresh`) of the merged documents, and `issuedAt` is the max-order document's (R2).
  - 197-5: 295 must be reissued against this version before it is picked up (Ownership and ordering, Out of scope).
  - 197-6: added A4 and A6 cases and mutants for a stale cards look, a departed peer, ring eviction, the digest counts and publish coalescing.
  - 197-8: W = L + Tp + A, with A ≤ L (F1); the bound is restated by 197-19 below.
  - 197-9: the "`receivedAt = now` outside the gate" mutant is split into one mutant per renewal point (A13).
- Coordinator decisions 2026-11-02 after external review of PR #197 round 2, owner may override (contract version 2.2):
  - 197-10: until a member has completed a cards look since it started, clauses 2 and 3 fail whenever another member is listed; a lone member is unaffected (R6).
  - 197-10, stale look: a cards look older than one lease is treated exactly like no look yet. Peer state is unknown, and clauses 2 and 3 both fail while another member is listed, so a cold member cannot pass clause 3 on a stale `unverifiedCount()` of 0. The two cases are one "peer state unknown" rule (R6).
  - 197-11: clause 4 forbids renewal only as part of the member's own write; a later read may renew it if the full gate passes then (R6).
  - 197-12: card newness is judged by a sealed per-process `cardSequence`; a replayed older card, no-document included, records nothing (R7, F5, Residuals). The choice of sequence over `issuedAt` is a planner detail.
  - 197-13: look age is measured on the monotonic nano clock, through `Peers.lastLookNanos()` (R6, R7).
  - 197-14: R7 lists every `Peers` hook; Owns for `MemberCards` matches (R7, Owns).
  - 197-15: an entry that becomes unpinned re-enters the Seen ring at its head (R6).
  - 197-16: the two-lapsed-holders test is restated as fresh or not-fresh states per step (A4).
  - 197-17: the repair over a wrong Doc(1) writes at `max(held.sequence, 1) + 1` (R8).
  - 197-18: an operator pause over GATED writes `held ⊔ gated ⊔ change` under the R4 guard (R9, A1).
  - 197-19: Tp is defined (F1); its bound is restated by 197-20 below.
- Coordinator decisions 2026-11-02 after external review of PR #197 round 3, owner may override (contract version 2.3):
  - 197-20: silence never counts as coverage. A listed verified peer with no valid in-lease card fails Covered, and its earlier digest never stands in; only the `cardSequence` is kept across looks, for newness. This narrows owner decision 1's last-seen digests to card sequences. Tp ≤ L + `clockSkewTolerance` and W ≤ 3 L + `clockSkewTolerance` (F1, F5, R6, R7, Residuals).
  - 197-21: Seen also holds the digest of every authentic stored document merged (R2, R6, Residuals).
  - 197-22: the nano clock reaches `MemberCards` through a package-private `LongSupplier nanoClock`, defaulting to `System::nanoTime` (R7, Owns).
  - 197-23: a member-list change since the last completed look makes peer state unknown until a new look completes (R6, R7).
  - 197-24: a look in which every peer card found was rejected as expired makes peer state unknown (R6).
  - 197-25: only the main `HazelcastOversightState` document publishes card digests; the `ScopeIdentityIndex` document judges Covered on its own Seen and refuses when not covered (R7, R12, Residuals).
  - 197-26: endScope over ABSENT, UNREADABLE or GATED throws `OVERSIGHT_UNAVAILABLE` after the sweeps and keeps the pause. This replaces 197-7's no-op success over ABSENT (R12).
  - 197-27: resume has no sequence precondition, and `Peers.highestReportedSequence()` is removed. This drops the resume use of the sequence in owner decision 1 (R1, R7, R13).
- Coordinator decision 2026-11-02, owner may override (contract version 2.3, follow-up): a peer card is valid only until the earlier of its wall-clock lease end and `firstSeenNanos + L` on this member's nano clock, closing the backward-wall-clock residual. The widened bound W ≤ 3 L + `clockSkewTolerance` of 197-20 is accepted (R6, R7).

## Residuals (recorded, not fixed)
- A replayed card can mask a peer only while the replay is inside its own lease and is not older, by `cardSequence`, than the latest card this member has accepted from that peer: a newer card this member has not yet seen, or the current card re-put after the peer's later writes were blocked, carrying a digest this member has held. A replay with a lower `cardSequence` is no valid card and fails Covered (R7) (owner decision 6; 197-12: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; 197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override).
- Deleting or blocking a verified peer's cards makes every member that judges that peer uncovered within one lease, and the members lapse. This is a denial of service that fails closed: the same capability as deleting the document, already accepted *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- A peer stalled at a subset of this member's content that this member never held exactly and never merged as a whole stored document blocks this member's freshness until that peer ticks or leaves the member list. This fails closed *(197-21: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- An endScope may be refused because the `ScopeIdentityIndex` document's sparse Seen leaves it not covered, and may need a retry after the main document has been read. The refusal keeps the pause and the data is still erased, so it fails closed *(197-25: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- A pause held only by members that have left the member list, or only by peers whose card this member never saw, can be lost (owner decision 6).
- A lone member that restarts accepts a replayed older document inside the cold-start gate (L plus skew) and, having no peers, is fresh on it. The replay window equals the cold-start gate.
- If the oldest member is hung, no member writes Doc(1); an operator pause is the fallback.
- Without GC (295) every pause uses an element slot for good. A cluster at the cap can only `pause all` until 295 ships or a full restart wipes the state. Heavy `pause all` / resume-all cycling grows the document by one element and one tombstone per cycle, up to the format bound, where `pause all` is refused with `CLUSTER_OPERATION_REFUSED` (fails closed).
- A union above the format bound is refused as unencodable, and a stored document above it decodes as tamper. Reaching it needs more than eight partitions each filled to the cap and then merged. Reads then refuse, which fails closed.
- The 0.7 fence can be deleted until the next re-assertion, up to half a lease (design 4.8).

## Acceptance
All in `PauseDocumentTest` unless named. Both clocks are injected throughout.

### A1. Document, merge and writes
- [ ] Deleting the document makes every instance refuse and `pause.fresh` read 0 once its lease passes without a passing gate.
- [ ] A document older than what an instance holds never resets its lease and never removes content from held.
- [ ] A restarted instance merges a replayed older document only inside the cold-start gate. With a verified peer holding newer content it is not fresh. Alone, it is fresh, and the test asserts the window equals the cold-start gate, named as the residual.
- [ ] Two concurrent writers race on the CAS, a pause each: both succeed in turn and both elements survive.
- [ ] A member whose wall clock is skewed by more than the tolerance does not make a healthy document stale after first acceptance.
- [ ] The reviewer's shape: A and B hold {p, q} at sequence 5. Storage is rolled back to sequence 3; in two more runs the document is deleted instead, or replaced by garbage. C, behind at 3, pauses x three times and so reaches sequence 6. In every run every instance ends with {p, q, x} paused, a repair write happens at sequence 7, and no tombstone exists for any id of p or q. Variants: C cold (`held == null`); C with an old cards look; the rollback taken after a resume of p (p stays lifted on A and B, and on C once it merges).
- [ ] A replay of an old document holding an element that is now tombstoned: the element stays lifted on every warm member.
- [ ] A cold member given a replay within the cold-start gate shows the replay's state until the next repair tick by any warm member, then shows the union.
- [ ] An attacker document (plaintext, 0.7 format, random envelope, envelope under another key) written by a keyless member or client never lifts a pause, is never overwritten by a tick, is counted as tamper, and is replaced by an operator pause that keeps the held pauses.
- [ ] GATED operator pause (197-18): a cold member (`held == null`) finds a GATED document holding {p} (authentic, `issuedAt` older than L plus skew). An operator pause of x replaces it with `replace` over the exact stored bytes; the written document holds {p, x}, at an order above the GATED document's, the commit guard accepts it, and p and x are paused on every member. The same with `held` = {q}: the written document holds {p, q, x}. Over an UNREADABLE value with `held` = {q}, the written document holds {q, x}.
- [ ] Every existing `HazelcastOversightTest` case passes, changed only where a raw value was read or written. The `OversightState` SPI and `OversightSnapshot` are unchanged.

### A2. Commit guard and retries
- [ ] A rival writes a higher document with an extra pause between read and commit (`beforeCommit`): the final document holds both, at a higher order.
- [ ] `pushHeld` never writes over a stored document that is a superset of held.
- [ ] A pause of X lands via `beforeCommit` between a resume of X's first read and its commit, forcing a retry. After the retry X stays paused, every id in the resume's original `O` is tombstoned, and the operator response's `stillPaused` is true. The same shape for endScope: a pause of the ended scope landing mid-retry stays active.
- [ ] A resume whose `O` was wholly tombstoned elsewhere before its retry writes nothing and returns success.
- [ ] Each guard clause has a test that a mutant without it fails; the Outcome shows it.

### A3. Cap and format bound
- [ ] 8,176 non-`all` pauses succeed; all 8,176 are resumed (8,176 tombstones); `pause all` then succeeds; the 8,177th non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED`; a resume at the cap is accepted; an encode above the format bound (65,537 elements, or 65,537 tombstones) is refused, never truncated.
- [ ] `pause all` reserve: with 8,176 non-`all` elements, at least 20 `pause all` / resume-all cycles each succeed and `pause all` is still accepted afterwards; with no merge, `pause all` at `b = 8,192` is accepted; `pause all` at `b = 65,536` is refused with `CLUSTER_OPERATION_REFUSED` and writes nothing.
- [ ] Decode: a document with 65,537 elements, or 65,537 tombstones, is UNREADABLE and counted as tamper; one with 65,536 of each opens; one with 16,000 elements opens.
- [ ] In `PauseSplitBrainTest`: each side of a split adds 8,000 distinct non-`all` pause elements. After the heal the merged document is written with 16,000 elements and every member holds all of them. A non-`all` pause is then refused with `CLUSTER_OPERATION_REFUSED`, and `pause all` is accepted and written (16,001 elements).

### A4. Freshness gate (new in version 2)
- [ ] 5a: A holds {seed, p} at sequence 2 and stalls. A replay of sequence 1 is put, then the document is deleted, and C ticks repeatedly. C's sequence reaches 2 but `fresh()` stays false on C throughout. Repeated after A's card has expired while A is still in the member list (F5): the same result.
- [ ] Replay stepping: k replay, delete and tick loops (k at least 5). C's sequence rises by k and C is never fresh.
- [ ] Alert and grace: three members plus a keyless intruder, once under `alert` and once under `refuse` inside a `PT10M` grace. The established members stay fresh and serve. Under `refuse` after the grace, `serving()` is false but `pause.fresh` is unchanged. A cold member is not fresh while the intruder is present, and is fresh once it leaves.
- [ ] Two lapsed holders (197-16): A holds {p}, B holds {q}, both are lapsed, the document is deleted, and every cards look is driven by the test. The test asserts only fresh or not-fresh states and held content after each step, never who wrote the bytes:
  1. A ticks (it restores {p} over ABSENT): A not fresh, B not fresh.
  2. B ticks (it repairs to {p, q}): B not fresh, A not fresh.
  3. A reads after a look in which B's card still shows Digest({q}): A holds {p, q} and is not fresh.
  4. A reads after a look in which B's card shows Digest({p, q}): A is fresh.
  5. B reads after a look in which A's card still shows Digest({p}): B is not fresh.
  6. B reads after a look in which A's card shows Digest({p, q}): B is fresh, and both hold {p, q}.
- [ ] Reconcile and operator: a member that is not covered writes a reconcile union but is not fresh. An operator pause on such a member takes effect on every member, the member stays not fresh, and its resume is refused with `OVERSIGHT_UNAVAILABLE`. A covered member's operator pause does renew it.
- [ ] Equal sequence, different content: split halves both at sequence 3. C accepts B's document and is not covered until it holds A's content too.
- [ ] Card replay and delete: a replayed card extends a member's freshness by at most that card's own lease. Deleting a card never makes a member covered.
- [ ] A lone member still refreshes: it passes the gate on every tick with no peers and stays fresh.
- [ ] A solo member's nano clock passes the lease and reads refuse; one tick renews it through the gate and `pause.fresh` returns to 1.
- [ ] On a member whose gate fails, re-putting identical bytes, a higher refresh, or a higher sequence written by anyone never renews it.
- [ ] No renewal over ABSENT or UNREADABLE stored values, or for a cold member while a member is unverified.
- [ ] A member that missed a pause (partitioned) is not fresh while any verified peer's digest is outside its Seen, whatever it writes. A member that holds the latest content restores a deleted document as a union and is fresh after the restore.
- [ ] Restart of one member (197-1): A, B and C are fresh; C restarts with `held == null` and publishes a no-document card. A and B stay fresh throughout, `peerDigests()` on A and B has no entry for C until C holds a document, and C becomes fresh once it accepts the document and is covered.
- [ ] All-lapse recovery (197-1): every member lapses (no gate passes for more than L, by the nano clocks), with some members restarted and cold. Once any member holds a document that every other member is covered by, every member renews on its next tick with no operator pause, and the test asserts no operator write took place.
- [ ] Seen pinning (197-2): peer P publishes a card at digest D, keeps its valid card at D (its half-lease refreshes carry D) and stays in the member list *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*; M makes at least 257 distinct content changes. D is still in Seen(M), M still passes Covered for P, and the number of pinned entries never exceeds the number of listed members. When P leaves the member list, or P's card changes, D is unpinned and ages out after 256 further changes: it is still in Seen(M) after 255 of them and gone after the 256th (197-15).
- [ ] Stale cards look (197-6): with a peer listed, every peer's card digest in Seen(M), and M's latest completed cards look older than one lease, the gate fails and `fresh()` goes false once the lease passes; a completed fresh look then lets the next gate pass.
- [ ] Departed peer (197-6): a peer whose card digest is outside Seen(M) blocks M's renewal while it is listed. Once it leaves the member list its entry is dropped from `peerDigests()` and M renews at its next gate.
- [ ] Post-write renewal (197-3): for each renewal point of R6 that follows a write (`acceptWritten` after a repair, a restore, a refresh and each operator write; `reconcileAfterMerge` after a write; recovery after a tick write), a member lapsed when the write began is not fresh after it even though clauses 1-3 pass on the post-write state, and is fresh after its next non-write tick.
- [ ] Lapsed own write, then a read (197-11): a member lapsed when it writes a repair is not fresh after the write. Run both ways. With no read and no tick after the write, it stays lapsed. With a read-path `judge` on the same stored bytes after the write, it is fresh after that read only if covered: once with a listed peer whose card digest is outside Seen(M) (it stays lapsed), and once with every peer's digest in Seen(M) (it is fresh).
- [ ] No look yet (197-10): a cold member with a listed peer, a replayed document inside the cold-start gate, and no completed cards look since it started stays not fresh, though `peerDigests()` is empty and `unverifiedCount()` is 0. After a completed look that shows the peer at a digest outside its Seen it is still not fresh; once it holds that content and a look shows it covered, it is fresh. A lone member with no look is fresh on its first passing gate.
- [ ] Late intruder (197-10, 197-23): a cold member (`established` false) with a listed peer completes a cards look that finds no unverified member, holds the document and is covered by that look. A keyless intruder then joins, and no further look completes. The look is still under one lease old by the nano clock and `unverifiedCount()` still reads 0 from it, but the member list has changed since the look, so at every gate from the join until the next completed look the member stays not fresh and `established()` stays false. The same holds once the look is older than one lease. After a look that sees the intruder it is still not fresh. Once the intruder leaves it stays not fresh until a look completes after the leave; with every peer covered at that look it is fresh. Separately, an established, fresh member whose member list changes fails every gate until a new look completes and passes the next gate after it *(197-23: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Wall-clock jump (197-13, 197-24): with every member fresh and covered and the nano clock unchanged, moving a member's wall clock back by more than a lease changes neither `peerDigests()`, nor coverage, nor `fresh()`. Moving it forward by more than a lease makes every peer card read as expired at the next look: peer state is unknown, no gate passes, and `fresh()` goes false once the lease passes by the nano clock. With the nano clock advanced past one lease since the last look, the gate fails whatever the wall clock reads *(197-24: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Replayed no-document card (197-12): peer P's card at digest D has been accepted, D is outside Seen(M), and M is not covered. An older no-document card of P (a lower `cardSequence`) is replayed and a look completes. P reads as "no valid card" in `peerDigests()`, and M stays not covered and not fresh. A replayed older card of P with a digest in Seen(M) reads the same way, and M stays not covered *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*. A newer card of P is accepted.
- [ ] Deleted card (197-20): peer P's valid card shows a digest in Seen(M), and M is covered and fresh. P's card is deleted while P stays listed; in separate runs, P's card writes are blocked until its card expires, and P's card is replaced by an older card of P. In each run M is not covered at the first look after the card stops being valid, which is within one lease, and `fresh()` goes false once M's lease passes with no gate passing. A newer valid card of P restores coverage *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Reader clock back: peer P's card is first seen by M at nano time t0, and M is covered by it. P's card writes are then blocked, and M's wall clock jumps back by more than `clockSkewTolerance` (in a second run, by more than a lease), so the card would read as inside its wall-clock lease for longer. At the first look after t0 + L by M's nano clock, P has no valid card in `peerDigests()` and M is not covered *(coordinator decision 2026-11-02, owner may override)*.
- [ ] Merged stored document (197-21): peer P is stalled at content X, a strict subset of M's held content that M never held exactly. Once M merges an authentic stored document whose content is X, M is covered by P. Without that merge, M stays not covered while P is listed and stalled, as the residual says *(197-21: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Resume after a rollback (197-27): the stored document is rolled back to an authentic older one at a lower sequence while a peer's card still reports the higher `highestPauseSequence`. Once a member is fresh and covered, its resume succeeds and writes its tombstones *(197-27: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Every probe test from the previous review is a real, named test that passes and shows no defect: the fourth-hole variants and `crProbe_aColdMemberRecoversOverItsOwnLapsedUnionWriteAndRefreshesWithPLifted` included. No test is left that expects or documents a defect. The Outcome lists each probe with its new name.

### A5. First start
- [ ] Three members started together produce exactly one Doc(1), and every read on every member works.
- [ ] A full cluster restart produces exactly one Doc(1).
- [ ] Two eligible members race: one document, and `pause.conflict` is not counted.
- [ ] Attacker shapes, each of which must not write Doc(1), must refuse reads until a peer restores, and must keep the pause: a pause at sequence 2 held by A and B, the document deleted, C restarts; the same with the cards also deleted; the same with stale empty-digest or no-document cards replayed.
- [ ] The oldest member, with any verified card reporting a non-empty digest, does not write.
- [ ] A 0.7 member, a version-1 card member or a card-less member present means no first start.
- [ ] The oldest member does not write Doc(1) before one lease of uptime, and does write it after. A lone member writes it without the wait, and the membership it judges is read under the lock.
- [ ] Empty-digest card replay: A and B hold a pause at sequence 3, the document and the cards are deleted, the oldest member restarts, and old empty-digest or no-document cards for A and B are replayed while it waits. A's or B's real card arriving inside the lease stops Doc(1), and the pause survives on every member. A Doc(1) forced in through `beforeCommit` is merged on A and B, and the pause is still in force afterwards.

### A6. Card v2, in `MemberCardTest`
- [ ] The digest is independent of the order in which elements and tombstones were added.
- [ ] A change that adds only a tombstone changes the digest.
- [ ] A version-1 card is rejected: the member reads as unverified.
- [ ] A change of the reported digest triggers a card publication before the half-lease schedule would, and never two card writes at once.
- [ ] Digest counts (197-6): content with elements {a} and no tombstones and content with no elements and tombstones {a} have different digests.
- [ ] Publish coalescing (197-6): several digest and sequence changes while a card write is in flight produce exactly one further write once it completes, and the card then published carries the latest digest and sequence; never two writes at once.
- [ ] No-document card (197-1): a member with `held == null` publishes a card whose marker differs from `Digest(empty)`; it opens and counts the member as verified; it is absent from `peerDigests()`; and the digest that member showed before is no longer in `peerDigests()` *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Card newness (197-12, 197-20): a card whose `cardSequence` is lower than the recorded one for its member, a no-document card included, records nothing and reads as "no valid card" in `peerDigests()`; an equal one is the same card and stays valid inside its lease; a greater one is accepted and its sequence recorded. `lastLookNanos()` reads the package-private `nanoClock` set by the test and does not move when only the wall clock does (197-13, 197-22: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override).
- [ ] Member-list change (197-23): after a member joins or leaves, `lookCompleted()` is false until the next look completes, and true after it *(197-23: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Departed peer (197-6): a member that leaves the member list is dropped from the per-member card sequences and from `peerDigests()`; a verified member whose card expires or is deleted while it stays listed reads as "no valid card" in `peerDigests()` at the next look *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

### A7. Lapsed holders, restore and repair
- [ ] A replayed older document is repaired to a superset of it and of held, elements and tombstones.
- [ ] A lapsed holder over ABSENT writes `putIfAbsent(held)` without renewing; its next tick renews it only if it is covered.

### A8. endScope, in `KeyedScopeEndTest`
- [ ] The ended scope is removed while other scopes, tool pauses, a tool paused under the same name and the global pause survive. The scope's element is tombstoned, not deleted. The assertions at :98-99, :109 and :189-196 read the document, not raw map entries.
- [ ] Not serving: the data is erased, the pause is kept, and the call throws `CLUSTER_OPERATION_REFUSED`. `held == null`: the same with `OVERSIGHT_UNAVAILABLE`. An unverified member: the same with `CLUSTER_OPERATION_REFUSED`. A stale document: the same with `OVERSIGHT_UNAVAILABLE`.
- [ ] A sweep failure keeps the pause. Deadline exhaustion keeps the pause and throws the lock timeout.
- [ ] A replayed older document at a cold index does not lift other scopes, and endScope over it tombstones only the ended scope's ids.
- [ ] ABSENT (197-26): with the document deleted, endScope on a serving, verified index with `held != null` erases the data, writes nothing, leaves the document ABSENT, keeps the scope's element in held and throws `OVERSIGHT_UNAVAILABLE`. Once the document is restored with the scope's element, a retried endScope that passes its preconditions tombstones it. With the document deleted and `held == null` it throws `OVERSIGHT_UNAVAILABLE`; not serving, `CLUSTER_OPERATION_REFUSED`; with an unverified member, `CLUSTER_OPERATION_REFUSED`. The data is erased in every case *(197-26: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Index document not covered (197-25): a peer's valid card shows a digest that is in the main document's Seen but not in the index document's Seen. endScope erases the data, writes nothing, keeps the pause and throws `OVERSIGHT_UNAVAILABLE`. The index document never publishes a card digest: the card carries the main document's digest throughout *(197-25: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

### A9. Status, gating and the resume response
- [ ] In `UnverifiedMemberTest` (:447-463 and the OD-215-16 (a) cases): with the serving gate closed by an unverified member under `refuse`, a pause is written and seen by every instance; a resume gives `CLUSTER_OPERATION_REFUSED`; after the gate reopens, the resume succeeds.
- [ ] Gate open and document stale: reads and resume give `OVERSIGHT_UNAVAILABLE`, `securityStatus()` reports `PAUSE_NOT_FRESH`, and `cluster.securityStatus().serving()` stays true.
- [ ] `RefusedResumeAuditCodeTest` (new, `data-prism-server`), with a stub `OversightState`: the audit order is `ALLOW:RESUMED` then a linked `DENY`, once for each code.
- [ ] `ResumeEffectTest` (new, `data-prism-server`): `stillPaused` is true after a resume when a concurrent unobserved pause keeps the target paused (on Hazelcast, a pause of the same target committed via `beforeCommit` while the resume is built), and a second resume clears it; true when TOOL X is resumed while `pause all` is active, and the same for a SCOPE target; true when the post-resume `snapshot()` throws; false after a clean resume with no global pause.
- [ ] `ResumeAuditOrderingTest`'s exact-map assertion at :155 gains `"stillPaused", false` and nothing else.
- [ ] `ClusterSecurityStatus` reports freshness and age without any pause content.

### A10. Metrics, fence and maps
- [ ] `ClusterPauseGaugeWiringTest` (new): a recording `PrivacyMetrics` sees `pause.fresh` and `pause.age_seconds` registered exactly once each.
- [ ] `pause.conflict` increments once for a rolled-back stored document and once for a lower-order document carrying an extra element, and not for the same bytes seen on a second tick.
- [ ] `OversightMapsBoundary`: the oversight map holds exactly the document, which opens under the test key; scope ids appear only inside the plaintext; both oversight maps' effective config has no eviction and no TTL.
- [ ] `PrivacyClusterMembershipTest`'s `SETTINGS` has the `OVERSIGHT_FENCE_MAP` row (eviction `NONE`), and the test passes.
- [ ] `ArchitectureTest.onlyScopeIdentityIndexOpensTheReidentificationMap` allows `PauseDocument` to call `Sealer.open`, and passes.

### A11. Split-brain, in `PauseSplitBrainTest`
- [ ] A pause issued on the smaller side of a split is present after the heal.
- [ ] Documents of equal order with different content merge by union and count `pause.conflict`.
- [ ] A resume of p on one side and a pause of y on the other both hold after the heal.
- [ ] A mutant whose `beginMerge` is a no-op fails a test.

### A12. Properties, decode and memo
- [ ] Property tests over random sequences of documents (seeded JUnit loops; no new dependency): merge is commutative, associative and idempotent; after a merge a target is paused iff the merged state holds some element for it without a tombstone.
- [ ] Order property (197-4), same seeded loops: held's order (`sequence`, `refresh`) never decreases across any sequence of merges; the order of a merge result equals the lexicographic maximum of its inputs' orders; its `issuedAt` is that of the max-order input (by the R2 tie-break on ties); and the result's metadata is the same whatever the merge order.
- [ ] Decode rejects a duplicate id with a different target and an unknown format tag. A tombstone for an unknown id survives a decode and encode round trip.
- [ ] Memo: identical sealed bytes are not decoded twice (a counting decoder seam), and different bytes are.

### A13. Mutants
Each must fail at least one named test. The Outcome lists each mutant with the test that fails it.
- [ ] Accept replaces held instead of merging.
- [ ] `next` drops a stored element or tombstone.
- [ ] A pause adds a tombstone.
- [ ] A resume tombstones ids outside base or outside its target.
- [ ] A pause dedupes against a live element.
- [ ] The cap truncates instead of refusing.
- [ ] The cap counts tombstones.
- [ ] The format bound equals 8,192, which fails the split-brain over the cap test.
- [ ] `pause all` is subject to the 8,192 limit, which fails the `pause all` reserve test.
- [ ] A resume or endScope recomputes `O` on a CAS retry.
- [ ] The memo keys on the sequence alone, ignoring the bytes.
- [ ] endScope removes elements by name, or across kinds, instead of tombstoning the scope's ids.
- [ ] The peer-derived sequence bump is restored.
- [ ] `stillPaused` is always false.
- [ ] `stillPaused` ignores `allPaused()` for TOOL or SCOPE targets.
- [ ] The gate compares the reported pause sequence with `held.sequence` instead of judging Covered *(197-27: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Seen absorbs peer digests on read.
- [ ] One mutant per renewal point of R6, each assigning `receivedAt = now` directly instead of through the gate *(197-9)*: in `judge`; in `acceptWritten`; in recovery on the tick; in `reconcileAfterMerge`; in the operator write path (pause, resume, endScope); after a refresh write.
- [ ] The digest is computed without the count prefixes *(197-6)*.
- [ ] A peer that leaves the member list is not forgotten *(197-6)*.
- [ ] A pinned Seen entry is evictable *(197-2, 197-6)*.
- [ ] A member with `held == null` reports `Digest(empty)` instead of the no-document marker *(197-1)*.
- [ ] Merge keeps held's order instead of the maximum of the inputs *(197-4)*.
- [ ] `unverifiedCount() > 0` blocks an established member's renewal.
- [ ] No `established` flag (the cold-start condition applies always, or never).
- [ ] An expired or deleted card is read as the last-seen digest *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] A member-list change since the last completed look is ignored *(197-23: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] The digest omits tombstones; separately, the digest omits elements.
- [ ] Coverage by equality with the current `Digest(held)` only.
- [ ] The card is published only at half lease.
- [ ] The lapsed union write renews the lease.
- [ ] An empty peer set before the first completed cards look counts as covered *(197-10)*.
- [ ] A stale cards look does not fail clause 3 (a cold member passes on the stale `unverifiedCount()`) *(197-10, stale look)*.
- [ ] A read renews a lapsed member that is not covered *(197-11)*.
- [ ] A card with a lower `cardSequence` than the recorded one counts as a valid card *(197-12, 197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.
- [ ] Card validity ignores `firstSeenNanos` *(coordinator decision 2026-11-02, owner may override)*.
- [ ] Look age is measured on the wall clock *(197-13)*.
- [ ] An unpinned Seen entry keeps its old ring position instead of re-entering at the head *(197-15)*.
- [ ] An operator pause over GATED writes `held ⊔ change` without the stored content *(197-18)*.

### A14. Build
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the test counts.

## Ownership and ordering
Checked 2026-11-02 against every open brief.
- Wave 7 is merged apart from 228, so nothing in wave 7 shares a file with it.
- `MemberCards.java`, `ArchitectureTest.java`, `PrivacyClusterMembershipTest.java` and `LegacyOversightStateTest.java` are in no other open brief's Owns. 294 owned `MemberCardTest`'s `Fixture` and has merged (PR #196). 295 lists `MemberCards.java` as not owned. 251, 253 and 278 own other files under `data-prism-architecture/src/test`, not `ArchitectureTest.java`; 280 only reads it.
- 287 (wave 8) depends on 228. It owns `data-prism-hazelcast/src/test/java/**` for its trusted-network declaration, which includes `MemberCardTest`, `PrivacyClusterMembershipTest`, `LegacyOversightStateTest` and every test this brief adds, and edits them after 228 merges. It also edits `ClusterBackedState.java` after 228.
- `ClusterBackedState.java` is serialised: 228 (the oversight bean only), then 287 (the trusted-network pass-through), then 286 (wave 10: the health bean and the lock-timeout pass-through; depends on 228 and 287).
- 295 (wave 9, ack-gated tombstone GC) owns `PauseDocument.java` and its tests after 228 and 287 merge. It builds on the `Peers` seam, which this version extends with `peerDigests()`.
- 295 must be reissued against F1-F5 of this version before anyone picks it up. Its current text relies on version 1 amendment 2 (lapsed-lease recovery, deleted in version 2) and on sequence-based freshness, neither of which exists now. Its tombstone GC also breaks Covered's soundness as written: pruning a tombstone changes `Digest(held)`, so the reissue must say how pruning interacts with the digest, with Seen (including pinned entries) and with peers' card digests, so that GC neither makes a covered member stale nor makes an uncovered member look covered. This brief does not edit 295 *(197-5: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.
- `OversightOperatorController.java` and `ResumeAuditOrderingTest.java` are in no other open brief's Owns. 273 (wave 15) owns `data-prism-server/src/test/java/**` for configuration arguments only and runs long after 228. 260 (wave 12) snapshots the SPI after 228, which leaves it unchanged.

## Preserves
Pause, resume and snapshot semantics for operators; the audit-first operator flow; bounded writes; both cluster codes; `OVERSIGHT_UNAVAILABLE` for an oversight state that cannot be evaluated.

## Out of scope
- The `resume` audit ordering (core task 245, OD-08-14).
- Rotation of the key the document is sealed under (229, deferred past 1.0).
- Spring binding of `pause-lease` and `clock-skew-tolerance` (230, deferred past 1.0; the defaults apply). The readiness contributor that reports freshness (286).
- Tombstone garbage collection of any kind, including acks on tombstones (295). The document only grows in 0.8, within R5. 295 is to be reissued against F1-F5 before it is picked up (Ownership and ordering); editing 295 is not part of this task.
- Any `MemberCards` change beyond R7: its serving status, grace, `alert` handling and gauges stay as 227 left them.
- `docs/` pages and design docs: facts for 213 and the scribe go in the Outcome. The operator API reference for `stillPaused`: facts for 261 in the Outcome.

## Working constraints
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented scope and tool names. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Both clocks are injected; waits are on events or on polled state with a bound.
## Superseded (history, do not implement)

Nothing in this section is part of the contract. It holds, verbatim from version 1 of this brief (the file at `main` 2bbc191f), every passage that version 2 replaces, grouped by where it stood, and, in its last five subsections, every passage of version 2 that version 2.1 replaces, every passage of version 2.1 that version 2.2 replaces, every passage of the first version 2.2 text that the stale-look follow-up replaces, every passage of version 2.2 that version 2.3 replaces, and every passage of the first version 2.3 text that the card first-seen follow-up replaces. The only edit is that strike-through markers were removed, since every line here is superseded, and each passage is quoted. Line numbers are version 1's.

Also superseded by the freshness decisions of 2026-11-02, though not in the version 1 text: refresh decision (a) on the implementation branch (a refresh needs the same corroboration as recovery: `unverified == 0` and `highestReportedSequence() <= held.sequence`), and the `renew` flag on `commit`, `acceptWritten` and the tick's `Action` (`PauseDocument.java` :409 and :880-943 at 3f472483).

### Version 1 Owns: struck and withdrawn entries (lines 26, 31, 32)

>   - data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/RefusedResumeAuditCodeTest.java *(new; amendment 6. `ResumeAuditOrderingTest.java` is not edited (revised: see below), and `OperatorHarness.java` is read only: 294 owns its `freePort`)*
>
>   - `OversightState.java` (three `default` reporting methods), `ResumeOutcome.java` (new), `ResumeOutcomeDefaultsTest.java` (new) *(removed 2026-10-29 by owner decision 5, revised: no core change; `OversightState.java` is read only)*
>   - Not owned and not needed: `MemberCards.java`, `PrivacyCluster.java`, `HazelcastApprovalStore.java`. If a clean fix seems to need one of them, stop and report.

### Version 1 Goal (line 35)

> A deleted, forged or replayed entry can no longer lift a pause. Pause state becomes one sealed document with a sequence, a refresh counter and a lease. Writes are compare-and-set, every instance remembers the highest document it accepted and never goes back, a split-brain merge takes the union of pauses, and a document that is absent, unauthentic or not fresh means "everything paused" (design 2.3.3, OD-3, OD-4, OD-14, OD-15). Deletion becomes a visible denial of service, which is the safe direction.

### Version 1 Required approach (lines 46-52)

> ## Required approach
> - One document under a fixed keyed name in `dataprism.oversight.s1`: the full set of pauses, `sequence`, `refresh`, `issuedAt`, and the issuing member. Order is (sequence, refresh), lexicographic. *(Revised 2026-10-29: the pauses are an observed-remove set of elements and tombstones, merged on every acceptance; order decides freshness only. See amendment 3.)*
> - Pause and resume write `sequence + 1, refresh = 0` *(how the sequence is chosen and what a write covers: amended 2026-10-29 by amendments 3 and 4)*; a lease refresh writes `refresh + 1` with the same pauses, when half the lease is spent, by any member. Writes are `replace(name, exactOldBytes, newBytes)`, or `putIfAbsent` when absent; a lost race re-reads and retries a bounded number of times, then fails closed.
> - Freshness is from local monotonic receipt time (an injected nano clock). The wall clock is used only to gate the first acceptance after a cold start: reject a document whose `issuedAt` is older than lease plus `clockSkewTolerance`.
> - Equal order with different content, or a `MERGED` event that finds a pause the cluster document lacks: write the union at `max(sequence) + 1`, `refresh = 0`, count `pause.conflict`. *(Superseded 2026-10-29 by amendments 3, 4 and 7: every accepted document is merged, a repair writes `held ⊔ stored` when stored lacks something, and `pause.conflict` is redefined.)*
> - Missing document: rewrite from memory per OD-15 (i) only with `putIfAbsent`, only from a document accepted within the last lease, and only if no verified member's card reports a higher sequence. *(Still holds for an ABSENT document. For a BEHIND document, superseded 2026-10-29 by amendment 8: `replace` over the exact stored bytes, with a union.)* First start: a member that finds no document and no other verified member writes the first one. *(Superseded 2026-10-29 by amendment 1.)*
> - Not fresh, absent or unauthentic: every `OversightState` read answers paused, or refuses with the OD-215-10 code; the instance reports not serving through `ClusterSecurityStatus` *(superseded 2026-10-29 by amendment 6: freshness is reported through `ClusterSecurityStatus.withPause` and `HazelcastOversightState.securityStatus()`, and the `PrivacyCluster` serving gate is not closed)*. Publish the accepted sequence to `MemberCards`.

### Version 1 Acceptance: replaced items (lines 55-57, 59)

> - [ ] T4, in `PauseDocumentTest`, with injected clocks: deleting the document makes every instance refuse and `pause.fresh` read 0; a document older than one an instance has seen is rejected by it; a restarted instance accepts a replayed older document only inside the lease and the cold-start gate, and the test asserts the window equals the configured lease (the residual, named as such); two concurrent writers both succeed in sequence with neither pause lost; a member with its wall clock skewed by more than the tolerance does not make a healthy document stale after first acceptance.
> - [ ] T4 merge cases, in `PauseSplitBrainTest`: a pause issued on the smaller side of a split is present after the heal; equal-order documents with different content merge by union and count `pause.conflict`; a resume on one side may be undone by the heal, and the test is named for that cost (OD-14) *(superseded 2026-10-29 by amendment 3: a resume on one side and a pause on the other both hold after the heal)*.
> - [ ] OD-15 rule: a member that missed a pause (partitioned) does not rewrite an older document while a verified member reports a higher sequence; a member that accepted the latest document within the lease does rewrite a deleted one. *(Kept; amendments 1, 2 and 8 add cases.)*
>
> - [ ] Every existing `HazelcastOversightTest` case passes; changes are only where a raw value was read or written. The `OversightState` SPI and `OversightSnapshot` are unchanged *(an interim amendment 9 of 2026-10-29 added default methods; withdrawn the same day by owner decision 5, revised)*.

### Version 1 amendment preamble and owner decisions of 2026-10-29, as worded then (lines 67-77)

> The review of PR #191 (branch `task/228-signed-leased-pause-document`, head 93877409) found two P1 and several P2 design gaps. An architect settled them, and the owner decided the two open calls on 2026-10-29 (amendments 2 and 5, option (a) each). These amendments win where they disagree with the text above; the superseded sentences above are marked, not deleted. Names below (`Status` values, the `Peers` seam, `beforeCommit`, `pushHeld`, `tick`) are those of `PauseDocument.java` at 93877409. "Held" is the highest document this member accepted; "stored" is the authentic document read from the map; "base" is `held ⊔ stored`.
>
> **Revised 2026-10-29 after external review of PR #194 (P1, repeated pauses overtaking missing state): owner chose (e).** The review found that the first version of amendment 3 still loses pauses. A and B hold pauses at seq 5, and storage is rolled back to seq 3. A behind member C then issues three pauses (seq 4, 5, 6) that lack A's and B's pauses. Seq 6 outranks them, corroboration passes, and A's and B's pauses are lifted. The root cause is that acceptance replaces the held document by order, and a resume is a bare removal. The owner's decisions of 2026-10-29:
> 1. Option (e): pause state becomes an observed-remove set with explicit tombstones. It replaces the sequence-ordered model of pause content. Sequence and refresh stay, for order and freshness only. Lineage option (d) was rejected.
> 2. No automatic tombstone GC in 0.8. The ack-gated GC is follow-up brief 295.
> 3. Operating cap: 16,384 elements plus tombstones, with 16 slots reserved for `pause all`. `1 << 20` stays as the decode guard. *(Meaning changed 2026-10-29: 16,384 total entries is preserved, enforced as 8,192 elements, with the last 16 element slots reserved for `pause all`; tombstones ≤ elements, so the total cannot pass 16,384. See amendment 4. Coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override.)* *(Refined 2026-10-29, coordinator decision 2026-10-29, owner may override: the 16,384 bound holds for every document built by local writes. Only a document formed by merging split sides can be larger, and it is bounded by the separate format bound of 65,536 elements and 65,536 tombstones. See amendment 4, "Operational cap and format bound".)* *(Revised again, coordinator decision 2026-10-29, owner may override: the 16-slot reserve is gone. Non-`all` pauses stop at 8,176 elements; `pause all` is accepted whenever the base plus one stays within the format bound, so the reserve is the whole gap from 8,176 to 65,536, for `pause all` only. The 16,384 bound holds for local writes except through `pause all` churn, which grows up to the format bound without GC; 295 is the fix. See amendment 4.)*
> 4. A pause always mints a fresh element. Pauses are never deduped.
> 5. The resume response gains one additive field reporting whether the target is still paused and how many ids were cleared (amendment 9). **(5, revised 2026-10-29, owner)** The resume response gains one additive field, `stillPaused`, and nothing else. There is no cleared count, and no change to the core `OversightState` interface (amendment 9).
> 6. `pause.conflict` is redefined (amendment 7). No new metric.
>
> Amendments 3 and 4 below replace the first versions, which are kept, marked superseded, at the end of this section. Amendments 1, 2 and 5 to 8 are touched up in place. Where a touched-up amendment and the superseded text disagree, the revision wins.

### Version 1 amendment 1: the sequence-based first-start conditions and their tests (lines 79, 83-84, 87, 93-94, 97)

> ### 1. First start (P1; touched up in the revision)
>
>   - `highestReportedPauseSequence() == 0`, counting every verified card, its own included;
>   - *(added in the revision)* it has been up for at least one lease, by its own monotonic clock, and every verified card has read 0 at every look during that lease. A card above 0 at any look restarts the wait.
>
> - Why the bare "no card above 0" rule is unsafe: cards can be deleted, and stale seq-0 cards can be replayed; either makes a lost pause look like a first start. The uptime wait closes the window in which a seq-0 card replay, made just as the oldest member starts, is the only card it has seen.
>
>   - [ ] attacker shapes, each of which must not write Doc(1), must refuse reads until a peer restores, and must keep the pause: a pause at seq 2 held by A and B, the document deleted, C restarts; the same with the cards also deleted; the same with stale seq-0 cards replayed;
>   - [ ] the oldest member, with any card reporting above 0, does not write;
>
>   - [ ] *(revision)* seq-0 card replay: A and B hold a pause at seq 3, the document and the cards are deleted, the oldest member restarts, and seq-0 cards for A and B are replayed while it waits. A's or B's real card arriving inside the lease stops Doc(1), and the pause survives on every member. A Doc(1) forced in through `beforeCommit` is merged on A and B, and the pause is still in force afterwards.

### Version 1 amendment 2: lapsed-lease recovery, whole (lines 99-111)

> ### 2. Lapsed-lease recovery (P1; owner decision (a): automatic, on the tick only, never on the read path; unchanged in the revision)
> - A member with `held != null` whose lease has lapsed, and whose stored document is authentic and CURRENT (order equal to held, and the same bytes), may write `refresh + 1` with `replace` over the exact stored bytes, if all of these hold:
>   - its cards status serves (a solo member always does);
>   - its cards look is within a lease;
>   - `peers.highestReportedSequence() <= held.sequence`.
> - Never over ABSENT, UNREADABLE, BEHIND or GATED values. Never for `held == null`; the cold-start gate and the replay window (equal to the lease) are unchanged. *(Revision: recovery happens only over a CURRENT document. The CONFLICT status is gone; see amendment 4.)*
> - One rate-limited WARN, with no pause content, scope, tool or member address. No new metric.
> - Residual, recorded: a lone stale holder whose peers' cards are also stale cannot tell a replay from the truth.
> - Tests:
>   - [ ] a solo member's monotonic clock passes the lease, reads refuse, one tick recovers, and `pause.fresh` returns to 1 with the same sequence and `refresh + 1`;
>   - [ ] the identical bytes re-presented (re-put) do not reset freshness;
>   - [ ] no recovery when a peer card reports a higher sequence, when a member is unverified, when the stored value is BEHIND (a replay), when it is ABSENT, or for a restarted member (`held == null`);
>   - [ ] lapsed plus ABSENT is recovered only by an operator pause, and that pause keeps the held pauses.

### Version 1 amendment 3: the round-3 decode bound and the order-decides-freshness rule (lines 123, 130-133)

>   - *(added 2026-10-29, round 3)* more than 8,192 elements, or more than 8,192 tombstones (the operating bound of amendment 4). Anything above either is tamper. *(Superseded: coordinator decision 2026-10-29, owner may override. A bound equal to the operating cap made an authentic split-brain merge undecodable.)*
>
> - Order still decides freshness:
>   - a higher (sequence, refresh) resets the lease and raises held's order;
>   - a lower or equal order never resets the lease, but its content is still merged;
>   - the identical sealed string re-presented resets nothing (amendment 2).

### Version 1 amendment 4: earlier cap wording (lines 161-162, 166, 170)

> - Cap (owner decision 3, as re-enforced 2026-10-29 in round 3). *(Revised: the first version counted `n = |elements| + |tombstones|` against 16,368 and 16,384. Resuming 16,368 live elements then added 16,368 tombstones, passed the cap and blocked `pause all` despite the reserve. Coordinator decision 2026-10-29 after external review of PR #194 (round 3), owner may override.)* The cap counts elements only. Let `e = |elements|` of `next`.
>   - At most 8,192 elements in total. `pause all` may use the last 16 element slots: accepted up to `e = 8,192`, refused above it. *(Superseded by "Operational cap and format bound" below: coordinator decision 2026-10-29, owner may override.)*
>
> - **Operational cap and format bound** (coordinator decision 2026-10-29, owner may override). The operational cap and the format bound are separate numbers. *(Replaces the round-3 rule that bounded encode and decode at the cap. With both at 8,192, a split where each side paused near the cap merged to more than 8,192 elements; that union could not be encoded, so no merge or repair write was made, and a stored oversize document decoded as tamper. It failed closed, but the cluster might never recover. The round-3 residual paragraph about the unencodable merge is removed and replaced by this rule.)*
>
>     - *(coordinator decision 2026-10-29, owner may override.)* The reserve for `pause all` is the whole gap between 8,176 and the format bound, and only `pause all` may use it. *(Replaces the earlier `b < 8,192` / `b > 8,192, merge only` split and the 16-slot reserve. Without GC, each `pause all` mints a fresh element and each resume tombstones it, so 16 pause-all/resume-all cycles used up the 16 slots and `pause all` was then refused with no merge at all; it was also refused at exactly `b = 8,192`. Both contradicted the rule above.)*

### Version 1 amendment 5: the encode-side cap (line 197)

> - An encode-side entry cap equal to the decode cap (`1 << 20`, `MAX_ENTRIES`). A pause past it is refused with `CLUSTER_OPERATION_REFUSED`. *(Superseded in the revision by the operating cap in amendment 4.)*

### Version 1 interim amendment 9 (lines 250-251)

> #### Superseded amendment 9 (interim version of 2026-10-29; withdrawn by owner decision 5, revised)
> - `effect`: `{"stillPaused": <boolean>, "cleared": <int>}`, computed by three additive `default` methods on `OversightState` (`resumeAllReporting()`, `resumeToolReporting(String)`, `resumeScopeReporting(String)`) returning a new `ResumeOutcome(boolean stillPaused, int cleared)` record, overridden by `HazelcastOversightState`, with a `ResumeOutcomeDefaultsTest` in core.

### Version 1 revision acceptance: replaced cap and decode items (lines 261-263)

> - [ ] Cap: at 16,368 entries a non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED`; `pause all` is then accepted from the reserve; a resume at the cap is accepted; an encode over the decode guard is refused. *(Superseded 2026-10-29, round 3, by the elements-only cap of amendment 4.)*
> - [ ] Cap (round 3): 8,176 non-`all` pauses succeed; all 8,176 are resumed (8,176 tombstones); `pause all` then succeeds; the 8,177th non-`all` pause is refused with `CLUSTER_OPERATION_REFUSED`; a resume at the cap is accepted; `pause all` is refused past 8,192 elements; an encode over the decode bounds is refused *(revised: coordinator decision 2026-10-29, owner may override)* with no merge, `pause all` at `b = 8,192` is refused; *(removed: coordinator decision 2026-10-29, owner may override)* an encode over the format bound (65,537 elements, or 65,537 tombstones) is refused, never truncated.
> - [ ] Decode (round 3): a document with 8,193 elements, or with 8,193 tombstones, is rejected as UNREADABLE and counted as tamper; one with 8,192 of each opens. *(Superseded: coordinator decision 2026-10-29, owner may override.)*

### Version 1 first versions of amendments 3 and 4 (lines 290-312)

> ### Superseded (kept for the record; replaced by the revision above)
>
> #### Superseded amendment 3 (first version): A pause from a member that is behind (P2)
> - Every write's pauses are `held.pauses` ∪ the stored authentic document's pauses (whatever their order) + the change.
> - `sequence = max(held.sequence, stored.sequence) + 1`. The `peers.highestReportedSequence() + 1` bump (`PauseDocument.java:485` at 93877409) is dropped.
> - Resume only: refuse with `OVERSIGHT_UNAVAILABLE` when `peers.highestReportedSequence() > top.sequence`. This covers a cold member too.
> - A pause over an ABSENT document: `putIfAbsent(held ∪ change)` at `held.sequence + 1`, or Doc(1){change} when `held == null`. This reconciles OD-215-16. Do not write `all = true`.
> - Tests (using the `beforeCommit` seam): A and B hold P and Q at seq 5; C is cold or stalled at 3; the document is deleted, replaced by a seq-3 replay, or replaced by garbage. C pauses X. For each shape:
>   - after the heal, every instance shows P, Q and X;
>   - C never gets a higher order than A and B;
>   - a resume at C is refused while any card reports a higher sequence.
>
> #### Superseded amendment 4 (first version): Every write is built inside lock → read → judge → compute (P2)
> `pushHeld` today replaces without judging. Every write path, `pushHeld` included, now judges the stored value under the lock and computes from it:
> - CURRENT or ACCEPTED: write nothing.
> - ABSENT or BEHIND: the union at `max + 1`.
> - UNREADABLE or GATED: overwritten only by an operator pause (amendment 8).
> - A lost race re-reads, up to the existing bound, then fails closed.
> - A structural guard in `commit`: a non-resume write must have an order above the stored document's, or be an identical-pause refresh successor of it, and must cover the stored authentic document's pauses. A write that fails the guard is not made.
> - Tests:
>   - a rival writes a higher document with an extra pause between judge and commit (`beforeCommit`); the final document covers both, at a higher order;
>   - `pushHeld` never writes over a higher stored document;
>   - a mutant that removes the guard fails a test (the Outcome shows it).

### Version 1 ownership and ordering, as checked 2026-10-29 (lines 314-319)

> ### Ownership and ordering (checked 2026-10-29; rechecked in the revision)
> - The added Owns (in the Owns block, marked "Added 2026-10-29") are disjoint from the other wave-7 briefs: 211 (Spring audit and cluster-key files), 247 (orchestration, MCP, the `new DefaultContextOrchestrator(` call sites), 256 (validation), 292 (core audit) and 294 (the test harness, filed on PR #190: `FreePorts`, `TestMembers.pair`, `MemberCardTest`, `ApprovalRaceTest`, `SealedWritesTest`, `ApprovalStoreContractTest`, `OperatorHarness.freePort`). 294 does not own `UnverifiedMemberTest`, `KeyedScopeEndTest` or `SoloMemberKeysTest`.
> - *(Revision)* `OversightOperatorController.java` and `ResumeAuditOrderingTest.java` are in no other open brief's Owns: not 211, 247, 256, 292, 294 (PR #190), 286 or 287. 294 owns only `OperatorHarness.freePort` in the server module. 273 (wave 15) owns `data-prism-server/src/test/java/**` for configuration arguments only, and runs long after 228. 260 (wave 12) snapshots the SPI signatures after 228; 228 no longer changes them (owner decision 5, revised).
> - `ClusterBackedState.java` is shared, and serialised: 228 (wave 7, the oversight bean only), then 287 (wave 8, the trusted-network pass-through; depends on 228), then 286 (wave 10, the health bean and the lock-timeout pass-through; depends on 228 and 287). 286 no longer passes the lock timeout to the oversight store; 228 does.
> - 287 also owns the existing hazelcast tests for its declaration, which includes the three test files added here; it depends on 228, so it edits them afterwards.
> - *(Revision)* 295 (ack-gated tombstone GC) owns `PauseDocument.java` and its tests after 228 and 287 have merged.

### Version 2 passages replaced in version 2.1

Verbatim from version 2 (PR #197 head a0be909f), replaced by the coordinator decisions of 2026-11-02 after external review of PR #197 round 1 (findings 197-1 to 197-9). Line numbers are version 2's. Passages version 2.1 only added to are not repeated here.

#### Header and preamble (lines 3, 5)

> **Contract version:** 2 (2026-11-02)
>
> This version states the whole current contract. It replaces version 1 and every amendment to it. Where version 1, a PR thread or the code on the implementation branch disagrees with this text, this text wins. Earlier wording is kept in "Superseded (history, do not implement)" at the end. Read it for history only and never build from it.

#### Terms (line 53)

> - Terms. **Held**: the content this member has accepted, the union of every authentic document it has merged. **Stored**: the authentic document read from the map. **Base**: `held ⊔ stored`, or held alone when stored is ABSENT. **Digest(x)**: the card digest of content x (R7). **Seen(M)**: the ring of digests of content member M has held (R6). **Verified peer**: another member in Hazelcast's member list whose card opened and was inside its lease at some look (R7, F5). **Lapsed**: not fresh.

#### F1 bound, F2, F4 and F5 (lines 59-65; F3 is unchanged and quoted only for continuity)

>   - **Covered**: every verified peer's card digest is in Seen(M), meaning M holds that content now or has held it.
>   
>   The bound on how long a member that stops being covered can stay fresh is W = L + Tp, where Tp is the card publication delay. With publish-on-change (R7), W is about 1.2 L.
> - **F2.** The gate's inputs are held content, peer card digests, Seen(M) and the monotonic clock, nothing else. The sequence, the refresh counter, the issuer, and who wrote the bytes are never evidence. There is no `renew` flag. Every lease reset goes through the gate: `judge`, `acceptWritten`, recovery on the tick, `reconcileAfterMerge` and operator writes.
> - **F3.** Unverified members never gate freshness for an established member. `unverified-member.action: alert` and the unverified grace period affect only `MemberCards`' serving status. `pause.fresh` is independent of them. Cold-start exception (owner decision 3 of 2026-11-02): a member that has not yet been fresh in this process life needs `unverifiedCount() == 0` for its first renewal. That renewal sets an `established` flag, which is reset whenever held becomes null.
> - **F4.** A lapsed member's own writes (a union repair, a reconcile, an operator pause, an ABSENT restore) apply content but never reset its lease. Its lease is reset only by a later gate evaluation that passes.
> - **F5.** A formerly verified peer that is still in the member list but whose card is gone or expired is judged at the last digest this member saw from it. A peer that leaves the member list is forgotten.

#### R2 merge (line 86)

> - Merge is commutative, associative and idempotent. No merge removes a live element, except by a tombstone for its id.

#### R3 `acceptWritten` (line 99)

> - After its own commit, `acceptWritten` merges the written document into held. The lease is reset only if this member was fresh when the write began and the gate passes on the written document (F2, F4). Neither `commit` nor `acceptWritten` takes a renew argument.

#### R6 renewal points, clauses and Seen (lines 123-128)

> - One method (`gate`, name may differ) is the only code that resets the lease, that is, sets the local receipt time (`receivedAt`). No other assignment of `receivedAt = now` exists. It is called at every renewal point: `judge` (read path and tick), `acceptWritten`, recovery on the tick, `reconcileAfterMerge`, and the operator writes (pause, resume, endScope).
> - The gate passes at time t iff all of these hold:
>   1. the stored document read at this renewal point (for `acceptWritten`, the document just written) is authentic and held has every element and tombstone of it (it is merged first, R2). ABSENT, UNREADABLE and GATED fail this clause;
>   2. Covered: for every entry of `Peers.peerDigests()`, the digest is in Seen(M);
>   3. if `established` is false: `Peers.unverifiedCount() == 0`. A pass then sets `established = true`.
> - Seen(M) is a ring of the last 256 distinct values of `Digest(held)`, the current one included, appended on every change of held. It records only M's own held content. A peer's digest is never added to it, by a read or otherwise.

#### R6 cards-look age (line 134)

> - If another member is in the member list and this member's latest completed cards look is older than one lease, `peerDigests()` is unknown and clause 2 fails *(planner reading, fail closed; owner may override)*.

#### R7 empty-held digest and hooks (lines 137, 142)

> - The card gains `pauseDigest`: the first 16 bytes of SHA-256 over a 4-byte big-endian count of element ids, the element ids sorted ascending as unsigned 16-byte values, a 4-byte count of tombstone ids, then the sorted tombstone ids. The two counts stop an id that moves between the lists from leaving the digest unchanged *(the counts are a planner detail; owner may override)*. A member with no held document reports `Digest(empty)`.
>
> - Hooks: `Peers.peerDigests()` returns that map for every listed member other than this one; a member whose card this member has never seen is absent from it. `MemberCards` also takes a digest reporter beside `reportPauseSequence`, and a change notice for publish-on-change. `PauseDocument` exposes `established()` package-private for tests. Names may differ.

#### R8 first-start card condition (line 148)

>   - every verified card, its own included, reported `Digest(empty)` at every look during that lease. A non-empty digest at any look restarts the wait.

#### R9 lapsed write (line 154)

> - Owner decision (b): a member with `held != null` whose stored document is BEHIND (it lacks an element or tombstone of held) writes `held ⊔ stored` at `max + 1` with `replace` over the exact stored bytes. Over ABSENT it writes `putIfAbsent(held)` at `held.sequence + 1`. This holds whether the member is fresh or lapsed. A lapsed member's write never renews it (F4).

#### R11 reconcile (line 165)

> - `beginMerge` captures held as `mergeBase` on MERGING. On MERGED, `reconcileAfterMerge` writes `mergeBase ⊔ held ⊔ stored` at `max(held, stored) + 1`, only if stored lacks something of it, and then calls the gate (F2).

#### R12 preconditions and ABSENT (lines 170-172)

> - Preconditions, under the lock, after the read: those of a resume (R13).
> - Any failure, skipped lock, failed precondition or stale document keeps the pause. When a precondition fails, the call throws its code after the sweeps, and the data is still erased.
> - An ABSENT document is a no-op success. No write if the scope has no live id in base. UNREADABLE or GATED: throw.

#### R13 decision 4 (line 183)

> - Owner decision 4 of 2026-11-02: an operator pause on a member that is not covered is written and takes effect cluster-wide, but does not make that member fresh, and its resume is refused (not fresh) until it is covered. A covered, fresh member's operator pause does renew it, through the gate.

#### A5 replayed-card wording (lines 288, 292)

> - [ ] Attacker shapes, each of which must not write Doc(1), must refuse reads until a peer restores, and must keep the pause: a pause at sequence 2 held by A and B, the document deleted, C restarts; the same with the cards also deleted; the same with stale empty-digest cards replayed.
>
> - [ ] Empty-digest card replay: A and B hold a pause at sequence 3, the document and the cards are deleted, the oldest member restarts, and old empty-digest cards for A and B are replayed while it waits. A's or B's real card arriving inside the lease stops Doc(1), and the pause survives on every member. A Doc(1) forced in through `beforeCommit` is merged on A and B, and the pause is still in force afterwards.

#### A13 renewal mutant (line 355)

> - [ ] Any `receivedAt = now` outside the gate.

#### Out of scope: tombstone GC (line 383)

> - Tombstone garbage collection of any kind, including acks on tombstones (295). The document only grows in 0.8, within R5.

### Version 2.1 passages replaced in version 2.2

Verbatim from version 2.1 (PR #197 head 2af006d9), replaced by the coordinator decisions of 2026-11-02 after external review of PR #197 round 2 (findings 197-10 to 197-19). Line numbers are version 2.1's. Passages version 2.2 only added to are not repeated here.

#### Header and preamble (lines 3, 5)

> **Contract version:** 2.1 (2026-11-02)
>
> This version states the whole current contract. It replaces versions 1 and 2 and every amendment to them. Where version 1, a PR thread or the code on the implementation branch disagrees with this text, this text wins. Earlier wording is kept in "Superseded (history, do not implement)" at the end. Read it for history only and never build from it.

#### Owns: `MemberCards.java` (line 19)

> - data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java *(the card digest field, the format version, publish-on-change, the last-seen digests and the `Peers` hooks only; R7)*

#### F1 bound (line 61)

>   The bound on how long a member that stops being covered can stay fresh is W = L + Tp + A, where Tp is the card publication delay and A is the maximum accepted age of the cards look the gate judged on. A is at most one lease (R6), so W ≤ about 2.2 L with publish-on-change (R7) *(197-8: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.

#### F5 (line 65)

> - **F5.** A formerly verified peer that is still in the member list but whose card is gone or expired is judged at the last digest this member saw from it. A peer that leaves the member list is forgotten. A peer whose latest card is a no-document card has no last-seen digest.

#### R6 clause 4 (line 141)

>   4. at a post-write renewal point: this member was fresh when the write began, and clauses 1-3 are judged on state read after that write: the stored document is the one the successful CAS wrote (or a re-read after it), held is merged with it, and `peerDigests()` and `unverifiedCount()` are read after the commit. A member that was lapsed when the write began never renews from that write, whatever clauses 1-3 say; it renews only at a later renewal point that passes and is not a reset from its own write *(197-3: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.

#### R6 pinned entries (line 145)

>   - the pinned entries: every value in Seen(M) that equals the last-seen digest of some peer still in the member list (an entry of `peerDigests()`) is pinned and never evicted. It is unpinned when that peer's last-seen digest changes or the peer leaves the member list, and no other listed peer still references it; it then ages out like any ring entry.

#### R6 cards-look age (line 152)

> - If another member is in the member list and this member's latest completed cards look is older than one lease, `peerDigests()` is unknown and clause 2 fails *(planner reading, fail closed; owner may override)*. One lease is therefore the maximum accepted look age A of F1.

#### R7 no-document card, last-seen digests and hooks (lines 156, 160, 161)

> - A member with `held == null` publishes a no-document card: the digest field carries a distinct "no document" marker (for example a presence byte of 0 and no digest bytes), never `Digest(empty)` and never equal to any digest. `Digest(empty)` is reported only by a member that holds an empty document. A no-document card opens and verifies its member like any card, but it is excluded from `peerDigests()` and so from Covered, and on arrival it removes that member's last-seen digest entry (which unpins it, R6). So a restarted member never makes an established member stale, and once any member holds a document every other member can be covered by it *(197-1: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.
>
> - `MemberCards` keeps, per member in the live member list, the digest of the latest card from it that opened and was inside its lease. That entry survives the card's expiry or deletion while the member stays listed (F5). It is dropped when the member leaves the list.
>
> - Hooks: `Peers.peerDigests()` returns that map for every listed member other than this one; a member whose card this member has never seen, or whose latest card is a no-document card, is absent from it. `MemberCards` also takes a digest reporter beside `reportPauseSequence`, and a change notice for publish-on-change. `PauseDocument` exposes `established()` package-private for tests. Names may differ.

#### R8 repair of a wrong Doc(1) (line 168)

> - A wrong Doc(1), written where a document should have existed, is merged by every member that holds more (R2), so it cannot lift a pause. The repair tick then writes `held ⊔ Doc(1)` at sequence 2.

#### R9 UNREADABLE or GATED values (line 175)

> - UNREADABLE or GATED values (plaintext, 0.7 format, random envelope, envelope under another key) are never overwritten by a tick and are counted as tamper. Only an operator pause replaces one, with `replace` over the exact stored bytes and `held ⊔ change`.

#### Decisions: 197-8 (line 261)

>   - 197-8: W = L + Tp + A, with A ≤ L, so W ≤ about 2.2 L (F1).

#### Residuals: replayed card (line 265)

> - A replayed card can mask a peer for up to L: a replay of a peer's old card, still inside its lease, carries a digest this member has held (owner decision 6).

#### A4 two lapsed holders (line 305)

> - [ ] Two lapsed holders: A holds {p}, B holds {q}, and the document is deleted. Neither renews on the other's lapsed document alone. They converge to {p, q}, and then both renew.

#### A6 no-document card (line 339)

> - [ ] No-document card (197-1): a member with `held == null` publishes a card whose marker differs from `Digest(empty)`; it opens and counts the member as verified; it is absent from `peerDigests()`; and on arrival it removes that member's earlier last-seen entry.

#### Superseded preamble (line 446)

> Nothing in this section is part of the contract. It holds, verbatim from version 1 of this brief (the file at `main` 2bbc191f), every passage that version 2 replaces, grouped by where it stood, and, in its last subsection, every passage of version 2 that version 2.1 replaces. The only edit is that strike-through markers were removed, since every line here is superseded, and each passage is quoted. Line numbers are version 1's.

### First version 2.2 passages replaced by the stale-look follow-up

Verbatim from the first version 2.2 text (PR #197 head a551e1b4), replaced by the coordinator decision of 2026-11-02 after external review of PR #197 round 2 that a stale cards look is treated exactly like no look yet. Line numbers are that text's. Passages the follow-up only added to are not repeated here.

#### R6 peer state unknown (lines 153-155)

> - Peer state is unknown, and the gate fails closed, in two cases. Neither applies to a lone member (no other member in Hazelcast's member list):
>   - **No look yet.** Until this member has completed at least one cards look since it started (this process life), clauses 2 and 3 both fail whenever any other member is in the member list. An empty `peerDigests()` or an `unverifiedCount()` of 0 before the first completed look is not evidence *(197-10: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
>   - **Stale look.** If another member is in the member list and this member's latest completed cards look is older than one lease, `peerDigests()` is unknown and clause 2 fails *(planner reading, fail closed; owner may override)*. One lease is therefore the maximum accepted look age A of F1.

#### R7 hook cross-references (lines 172, 173)

>   - `Peers.lookCompleted()`: true once this member has completed at least one cards look since it started (R6, no look yet).
>
>   - `Peers.lastLookNanos()`: the injected nano-clock time at which the latest cards look completed, recorded by `MemberCards` on the nano clock and not on its wall clock (R6, stale look and look age).

#### Decisions: planner readings (line 272)

> - Planner readings in this version (owner may override): the count prefixes in the digest (R7); refresh writes only by a fresh member (R10); a cards look older than a lease fails Covered (R6).

#### Superseded preamble (line 495)

> Nothing in this section is part of the contract. It holds, verbatim from version 1 of this brief (the file at `main` 2bbc191f), every passage that version 2 replaces, grouped by where it stood, and, in its last two subsections, every passage of version 2 that version 2.1 replaces and every passage of version 2.1 that version 2.2 replaces. The only edit is that strike-through markers were removed, since every line here is superseded, and each passage is quoted. Line numbers are version 1's.

### Version 2.2 passages replaced in version 2.3

Verbatim from version 2.2 as amended by its stale-look follow-up (PR #197 head 9594f212), replaced by the coordinator decisions of 2026-11-02 after external review of PR #197 round 3 (findings 197-20 to 197-27). Line numbers are that text's. Passages version 2.3 only added to are not repeated here.

#### Header and preamble (lines 3, 5)

> **Contract version:** 2.2 (2026-11-02)
>
> This version states the whole current contract. It replaces versions 1, 2 and 2.1 and every amendment to them. Where version 1, a PR thread or the code on the implementation branch disagrees with this text, this text wins. Earlier wording is kept in "Superseded (history, do not implement)" at the end. Read it for history only and never build from it.

#### Owns: `MemberCards.java` (line 19)

> - data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java *(the card digest field, the card sequence, the format version, publish-on-change, the last-seen digests and their newer-card-only acceptance, look timing on the nano clock, the per-look card markers and the `Peers` hooks only; R7)*

#### Terms (line 53)

> - Terms. **Held**: the content this member has accepted, the union of every authentic document it has merged. **Stored**: the authentic document read from the map. **Base**: `held ⊔ stored`, or held alone when stored is ABSENT. **Digest(x)**: the card digest of content x (R7). **Seen(M)**: the ring of digests of content member M has held (R6). **Verified peer**: another member in Hazelcast's member list whose card opened and was inside its lease at some look (R7, F5). **No-document card**: a card whose member has `held == null`; it carries a distinct marker in place of a digest (R7). **Lapsed**: not fresh. **Order**: the pair (`sequence`, `refresh`), compared lexicographically. **Post-write renewal point**: a gate evaluation that follows a write by this member (R6).

#### F1 Covered and bound (lines 59-61)

>   - **Covered**: every verified peer's card digest is in Seen(M), meaning M holds that content now or has held it. No-document cards carry no digest and take no part in Covered (R7).
>   
>   The bound on how long a member that stops being covered can stay fresh is W = L + Tp + A. Tp is the maximum delay between a change of a member's held content and a published card that shows it: the card is published on change (R7), and the half-lease refresh bounds the delay when a change-triggered write is lost or coalesced, so Tp ≤ L/2. A is the maximum accepted age of the cards look the gate judged on, at most one lease (R6). So W = L + Tp + A ≤ 2.5 L *(197-8: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override; 197-19: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.

#### F5 and the lone member (lines 65-66)

> - **F5.** A formerly verified peer that is still in the member list but whose card is gone or expired is judged at the last digest this member saw from it. A peer that leaves the member list is forgotten. A peer whose latest accepted card is a no-document card has no last-seen digest. A card is accepted into last-seen state only if it is strictly newer than the one already recorded for that peer (R7), so a replayed older card changes nothing *(197-12: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
> - **Lone member.** With no verified peers, Covered is vacuously true.

#### R1 sequence (line 81)

> - `sequence` and `refresh` order writes (the commit guard, R4) and `sequence` feeds the card and the resume precondition (R13). They are never freshness evidence (F2).

#### R2 Seen append (line 91)

> - Every change of held appends `Digest(held)` to Seen (R6) and marks the card for publication (R7).

#### R6 clause 2 (line 139)

>   2. Covered: for every entry of `Peers.peerDigests()`, the digest is in Seen(M);

#### R6 Seen, ring and pinned entries (lines 144-146)

> - Seen(M) holds digests of M's own held content only. A peer's digest is never added to it, by a read or otherwise. It has two parts *(197-2: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*:
>   - the ring: the last 256 distinct unpinned values of `Digest(held)`, the current one included, appended on every change of held. Only unpinned entries age out of it;
>   - the pinned entries: every value in Seen(M) that equals the last-seen digest of some peer still in the member list (an entry of `peerDigests()`) is pinned and never evicted. It is unpinned when that peer's last-seen digest changes or the peer leaves the member list, and no other listed peer still references it. An entry that becomes unpinned re-enters the ring at its head, as if just appended, so it survives 256 further changes of held before it can be evicted *(197-15: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.

#### R6 peer state unknown and look age (lines 153-154)

> - **Peer state unknown.** While any other member is in Hazelcast's member list, peer state is unknown if this member has not completed a cards look since it started (this process life), or if its latest completed cards look is older than one lease. While peer state is unknown, clause 2 (Covered) and clause 3 (cold-start unverified) both fail: an empty `peerDigests()`, or an `unverifiedCount()` of 0, read with no look or from a stale look is not evidence. The two conditions are one rule and are treated alike. The rule does not apply to a lone member (no other member in the member list). One lease is therefore the maximum accepted look age A of F1 *(197-10: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; the stale look failing clause 3 as well as clause 2 is a coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.
> - Look age is measured on the injected monotonic nano clock, through `Peers.lastLookNanos()` (R7), never on the wall clock. A wall-clock jump forward or back changes neither coverage nor freshness *(197-13: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override)*.

#### R7 no-document card, sequence, last-seen state and newness (lines 158, 159, 162, 165)

> - A member with `held == null` publishes a no-document card: the digest field carries a distinct "no document" marker (for example a presence byte of 0 and no digest bytes), never `Digest(empty)` and never equal to any digest. `Digest(empty)` is reported only by a member that holds an empty document. A no-document card opens and verifies its member like any card, but it is excluded from `peerDigests()` and so from Covered, and when it is accepted (it is strictly newer, below) it removes that member's last-seen digest entry (which unpins it, R6). So a restarted member never makes an established member stale, and once any member holds a document every other member can be covered by it *(197-1: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.
>
> - The card keeps `highestPauseSequence`. Resume's precondition reads it (R13). First start reads the digest, not the sequence (R8).
>
> - `MemberCards` keeps, per member in the live member list, the digest (or the no-document marker) and the card sequence of the latest accepted card from it: one that opened, was inside its lease, and was strictly newer than the card already recorded for that member. That entry survives the card's expiry or deletion while the member stays listed (F5). It is dropped when the member leaves the list.
>
>   - A card is accepted into last-seen state only if its `cardSequence` is strictly greater than the one recorded for that member. A card that is not newer, a replayed no-document card included, is ignored: it neither replaces nor removes the recorded digest.

#### R7 hooks (lines 168, 170-171, 173-175)

>   - `Peers.peerDigests()`: the last-seen map for every listed member other than this one. A member whose card this member has never accepted, or whose latest accepted card is a no-document card, is absent from it (R6 clause 2).
>
>   - `Peers.lookCompleted()`: true once this member has completed at least one cards look since it started (R6, peer state unknown).
>   - `Peers.lastLookNanos()`: the injected nano-clock time at which the latest cards look completed, recorded by `MemberCards` on the nano clock and not on its wall clock (R6, peer state unknown and look age).
>
>   - `Peers.highestReportedSequence()`: as now (R13).
>   - Card newness: the strictly-newer acceptance above, inside `MemberCards`; it has no hook of its own but is what `peerDigests()` reflects.
>   - Inputs to `MemberCards`: a digest reporter (or no-document marker) beside `reportPauseSequence`, and a change notice for publish-on-change.

#### R12 preconditions (lines 207-212)

> - Preconditions, under the lock, after the read, in this order *(197-7: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*:
>   1. R13's first three preconditions: `held != null`, serving, and `unverifiedCount() == 0`, with R13's codes;
>   2. then, if the stored document is ABSENT: a no-op success. Nothing is written and R13's sequence and freshness preconditions are not checked;
>   3. otherwise R13's remaining preconditions (the reported sequence, then fresh).
> - Any failure, skipped lock, failed precondition or stale document keeps the pause. When a precondition fails, the call throws its code after the sweeps, and the data is still erased.
> - No write if the scope has no live id in base. UNREADABLE or GATED: throw.

#### R13 sequence precondition (line 221)

>   - `peers.highestReportedSequence() > top.sequence`, where `top = max(held, stored)`: refused with `OVERSIGHT_UNAVAILABLE`;

#### Decisions: 197-2, 197-7, 197-12 and 197-19 (lines 273, 278, 285, 292)

>   - 197-2: Seen pins every digest referenced by a listed peer's last-seen card; only unpinned entries age out (R6).
>
>   - 197-7: endScope checks R13's first three preconditions before treating ABSENT as a no-op success (R12).
>
>   - 197-12: a card is accepted into last-seen state only if strictly newer, by a sealed per-process `cardSequence`; a replayed older card, no-document included, is ignored (R7, F5, Residuals). The choice of sequence over `issuedAt` is a planner detail.
>
>   - 197-19: Tp is defined and bounded by L/2, so W = L + Tp + A ≤ 2.5 L (F1).

#### Residuals: replayed card (line 295)

> - A replayed card can mask a peer only until that peer's next newer card is seen, and never by erasing the peer's last-seen digest: a card that is not strictly newer than the recorded one is ignored, a replayed no-document card included (R7). The mask needs a replay newer than anything this member has accepted from that peer, carrying a digest this member has held (owner decision 6; 197-12: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override).

#### A4 cases (lines 353-355, 357, 359-361)

> - [ ] Seen pinning (197-2): peer P publishes a card at digest D and then stays silent and in the member list; M makes at least 257 distinct content changes. D is still in Seen(M), M still passes Covered for P, and the number of pinned entries never exceeds the number of listed members. When P leaves the member list, or P's card changes, D is unpinned and ages out after 256 further changes: it is still in Seen(M) after 255 of them and gone after the 256th (197-15).
> - [ ] Stale cards look (197-6): with a peer listed, every last-seen digest in Seen(M), and M's latest completed cards look older than one lease, the gate fails and `fresh()` goes false once the lease passes; a completed fresh look then lets the next gate pass.
> - [ ] Departed peer (197-6): a peer whose last-seen digest is outside Seen(M) blocks M's renewal while it is listed. Once it leaves the member list its entry is dropped from `peerDigests()` and M renews at its next gate.
>
> - [ ] Lapsed own write, then a read (197-11): a member lapsed when it writes a repair is not fresh after the write. Run both ways. With no read and no tick after the write, it stays lapsed. With a read-path `judge` on the same stored bytes after the write, it is fresh after that read only if covered: once with a listed peer whose last-seen digest is outside Seen(M) (it stays lapsed), and once with every peer's digest in Seen(M) (it is fresh).
>
> - [ ] Stale look and a late intruder (197-10, stale look): a cold member (`established` false) with a listed peer completes a cards look that finds no unverified member, holds the document and is covered by that look. A keyless intruder then joins, and no further look completes until the look is older than one lease by the nano clock. The member's `unverifiedCount()` still reads 0 from the stale look, but at every gate in that time it stays not fresh and `established()` stays false. After a fresh look that sees the intruder it is still not fresh; once the intruder leaves and a later look completes with every peer covered, it is fresh.
> - [ ] Wall-clock jump (197-13): with every member fresh and covered, moving the wall clock forward by more than a lease, and separately back by more than a lease, with the nano clock unchanged, changes neither `peerDigests()`, nor coverage, nor `fresh()`; and with the nano clock advanced past one lease since the last look, the gate fails whatever the wall clock reads.
> - [ ] Replayed no-document card (197-12): peer P's card at digest D has been accepted, D is outside Seen(M), and M is not covered. An older no-document card of P (a lower `cardSequence`) is replayed and a look completes. P's last-seen digest is still D, `peerDigests()` still has P, and M stays not covered and not fresh. A replayed older card with a digest in Seen(M) is ignored the same way. A newer card of P is accepted.

#### A6 cases (lines 381-383)

> - [ ] No-document card (197-1): a member with `held == null` publishes a card whose marker differs from `Digest(empty)`; it opens and counts the member as verified; it is absent from `peerDigests()`; and when it is newer than the recorded card it removes that member's earlier last-seen entry.
> - [ ] Card newness (197-12): a card whose `cardSequence` is not greater than the recorded one for its member, a no-document card included, leaves the recorded digest and sequence unchanged; a greater one replaces them. `lastLookNanos()` reads the injected nano clock and does not move when only the wall clock does (197-13).
> - [ ] Departed peer (197-6): a member that leaves the member list is dropped from the last-seen map and from `peerDigests()`; a member whose card expires while it stays listed is kept at its last-seen digest.

#### A8 ABSENT case (line 394)

> - [ ] ABSENT (197-7): with the document deleted, endScope on a serving, verified index with `held != null` erases the data, writes nothing, leaves the document ABSENT and returns success even though the index is not fresh. With the document deleted and `held == null` it throws `OVERSIGHT_UNAVAILABLE`; not serving, `CLUSTER_OPERATION_REFUSED`; with an unverified member, `CLUSTER_OPERATION_REFUSED`. The data is erased in every case.

#### A13 mutants (lines 440, 450, 458)

> - [ ] The gate uses `peers.highestReportedSequence() <= held.sequence` instead of Covered.
>
> - [ ] An expired card is read as absent instead of at its last-seen digest.
>
> - [ ] Last-seen state accepts a card that is not strictly newer *(197-12)*.

#### Ownership and ordering: 295 (line 473)

> - 295 must be reissued against F1-F5 of this version before anyone picks it up. Its current text relies on version 1 amendment 2 (lapsed-lease recovery, deleted in version 2) and on sequence-based freshness, neither of which exists now. Its tombstone GC also breaks Covered's soundness as written: pruning a tombstone changes `Digest(held)`, so the reissue must say how pruning interacts with the digest, with Seen (including pinned entries) and with peers' last-seen digests, so that GC neither makes a covered member stale nor makes an uncovered member look covered. This brief does not edit 295 *(197-5: coordinator decision 2026-11-02 after external review of PR #197 round 1, owner may override)*.

#### Superseded preamble (line 496)

> Nothing in this section is part of the contract. It holds, verbatim from version 1 of this brief (the file at `main` 2bbc191f), every passage that version 2 replaces, grouped by where it stood, and, in its last three subsections, every passage of version 2 that version 2.1 replaces, every passage of version 2.1 that version 2.2 replaces, and every passage of the first version 2.2 text that the stale-look follow-up replaces. The only edit is that strike-through markers were removed, since every line here is superseded, and each passage is quoted. Line numbers are version 1's.

### First version 2.3 passages replaced by the card first-seen follow-up

Verbatim from the first version 2.3 text (PR #197 head cddca560), replaced by the coordinator decision of 2026-11-02 (owner may override) that a peer card's validity is also capped at `firstSeenNanos + L`. Line numbers are that text's. Passages the follow-up only added to are not repeated here.

#### Owns: `MemberCards.java` (line 19)

> - data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java *(the card digest field, the card sequence, the format version, publish-on-change, the per-member card sequences and their newer-card-only acceptance, the package-private `nanoClock` seam and look timing on it, member-list change detection, the per-look card markers and the `Peers` hooks only; R7)*

#### R6 look age and the wall clock (line 160)

> - Look age is measured on the injected monotonic nano clock, through `Peers.lastLookNanos()` (R7), never on the wall clock, so a wall-clock jump changes no look age. Card validity is still 227's wall-clock lease check. A backward jump cannot make an older card count, because newness is by `cardSequence` (R7). A forward jump past the card lease makes every peer card read as expired, which is peer state unknown: the member is not covered and lapses once its lease passes, which fails closed *(197-13: coordinator decision 2026-11-02 after external review of PR #197 round 2, owner may override; 197-24: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

#### R7 card newness (line 171)

>   - A card whose `cardSequence` is greater than the recorded one is accepted, and its sequence is recorded. A card whose `cardSequence` equals the recorded one is that same card and stays valid while inside its lease. A card with a lower `cardSequence`, a replayed no-document card included, is not a valid card: it records nothing, and its member has no valid card at that look (F5) *(197-20: coordinator decision 2026-11-02 after external review of PR #197 round 3, owner may override)*.

#### Residuals: the reader's backward wall clock (line 312)

> - Card validity is judged on the reader's wall clock (227). A reader whose wall clock runs back by more than `clockSkewTolerance` keeps a card it can still find, a re-put one included, valid for longer than its lease, which lengthens Tp beyond the F1 bound for that reader. Newness by `cardSequence` still rejects an older card *(planner note recorded with 197-20, not fixed; owner may override)*.

#### Superseded preamble (line 519)

> Nothing in this section is part of the contract. It holds, verbatim from version 1 of this brief (the file at `main` 2bbc191f), every passage that version 2 replaces, grouped by where it stood, and, in its last four subsections, every passage of version 2 that version 2.1 replaces, every passage of version 2.1 that version 2.2 replaces, every passage of the first version 2.2 text that the stale-look follow-up replaces, and every passage of version 2.2 that version 2.3 replaces. The only edit is that strike-through markers were removed, since every line here is superseded, and each passage is quoted. Line numbers are version 1's.

## Changelog
- Contract version 2 (2026-11-02): version 1 and its amendments of 2026-10-29 and 2026-10-30 consolidated into one current contract; sequence-based freshness replaced by the digest-coverage gate and Card v2 (owner decisions 1-6 of 2026-11-02); `MemberCards`, `MemberCardTest`, `PrivacyClusterMembershipTest`, `ArchitectureTest` and `LegacyOversightStateTest` added to Owns.
- Contract version 2.1 (2026-11-02): coordinator decisions after external review of PR #197 round 1, owner may override. No-document cards excluded from Covered (197-1); pinned Seen entries (197-2); the lapsed-own-write rule moved into the gate as clause 4 with every renewal point listed (197-3); merge metadata and an order property (197-4); 295 to be reissued before pickup (197-5); new A4, A6 and A13 cases for the uncovered paths (197-6); endScope precondition order over ABSENT (197-7); W = L + Tp + A (197-8); the renewal mutant split per renewal point (197-9).
- Contract version 2.2 (2026-11-02): coordinator decisions after external review of PR #197 round 2, owner may override. Peer state unknown before the first completed cards look (197-10); a later read may renew a member after its own lapsed write if the full gate passes (197-11); last-seen state accepts only strictly newer cards, by a sealed `cardSequence` (197-12); look age on the nano clock (197-13); every `Peers` hook listed in R7 and the `MemberCards` Owns entry matched (197-14); unpinned Seen entries re-enter the ring at its head (197-15); the two-lapsed-holders test restated as states (197-16); the wrong-Doc(1) repair at `max(held.sequence, 1) + 1` (197-17); an operator pause over GATED writes `held ⊔ gated ⊔ change` (197-18); Tp defined and W ≤ 2.5 L (197-19).
- Contract version 2.2, follow-up (2026-11-02): coordinator decision after external review of PR #197 round 2, owner may override. A cards look older than one lease is treated exactly like no look yet: one "peer state unknown" rule in R6 fails clauses 2 and 3 while another member is listed; new A4 case for a cold member with a stale look and a late intruder, and a mutant for a stale look that does not fail clause 3.
- Contract version 2.3 (2026-11-02): coordinator decisions after external review of PR #197 round 3, owner may override. Silence never counts as coverage: a listed verified peer with no valid in-lease card fails Covered, only card sequences are kept across looks, and W ≤ 3 L + `clockSkewTolerance` (197-20); Seen also holds merged stored documents' digests (197-21); the `MemberCards` `nanoClock` seam (197-22); a member-list change makes peer state unknown until a new look (197-23); a look with every peer card expired makes peer state unknown (197-24); only the main document publishes card digests, and the index document refuses when not covered (197-25); endScope over ABSENT throws `OVERSIGHT_UNAVAILABLE` and keeps the pause (197-26); the resume sequence precondition and `Peers.highestReportedSequence()` removed (197-27). New residuals for 197-20, 197-21, 197-25 and the reader's backward wall clock.
- Contract version 2.3, follow-up (2026-11-02): coordinator decision, owner may override. A peer card is valid only until the earlier of its wall-clock lease end and `firstSeenNanos + L` on the reader's nano clock (R6, R7); the backward-wall-clock residual is removed; new A4 case and mutant; `MemberCards` Owns names the first-seen time. The widened W bound of 197-20 is accepted.
