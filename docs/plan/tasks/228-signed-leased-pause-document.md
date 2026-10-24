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
- Pause and resume write `sequence + 1, refresh = 0`; a lease refresh writes `refresh + 1` with the same pauses, when half the lease is spent, by any member. Writes are `replace(name, exactOldBytes, newBytes)`, or `putIfAbsent` when absent; a lost race re-reads and retries a bounded number of times, then fails closed.
- Freshness is from local monotonic receipt time (an injected nano clock). The wall clock is used only to gate the first acceptance after a cold start: reject a document whose `issuedAt` is older than lease plus `clockSkewTolerance`.
- Equal order with different content, or a `MERGED` event that finds a pause the cluster document lacks: write the union at `max(sequence) + 1`, `refresh = 0`, count `pause.conflict`.
- Missing document: rewrite from memory per OD-15 (i) only with `putIfAbsent`, only from a document accepted within the last lease, and only if no verified member's card reports a higher sequence. First start: a member that finds no document and no other verified member writes the first one.
- Not fresh, absent or unauthentic: every `OversightState` read answers paused, or refuses with the OD-215-10 code; the instance reports not serving through `ClusterSecurityStatus`. Publish the accepted sequence to `MemberCards`.

## Acceptance
- [ ] T4, in `PauseDocumentTest`, with injected clocks: deleting the document makes every instance refuse and `pause.fresh` read 0; a document older than one an instance has seen is rejected by it; a restarted instance accepts a replayed older document only inside the lease and the cold-start gate, and the test asserts the window equals the configured lease (the residual, named as such); two concurrent writers both succeed in sequence with neither pause lost; a member with its wall clock skewed by more than the tolerance does not make a healthy document stale after first acceptance.
- [ ] T4 merge cases, in `PauseSplitBrainTest`: a pause issued on the smaller side of a split is present after the heal; equal-order documents with different content merge by union and count `pause.conflict`; a resume on one side may be undone by the heal, and the test is named for that cost (OD-14).
- [ ] OD-15 rule: a member that missed a pause (partitioned) does not rewrite an older document while a verified member reports a higher sequence; a member that accepted the latest document within the lease does rewrite a deleted one.
- [ ] An attacker document (plaintext, 0.7 format, random envelope, envelope under another key) written by a keyless member or client never lifts a pause, and is counted.
- [ ] Every existing `HazelcastOversightTest` case passes; changes are only where a raw value was read or written. The `OversightState` SPI and `OversightSnapshot` are unchanged.
- [ ] `pause.fresh` and `pause.age_seconds` are emitted as decided under OD-215-5, and `ClusterSecurityStatus` reports freshness without any pause content.
- [ ] OD-215-16 (a), with the serving gate closed by an unverified member under `refuse` (227): a pause is written and seen by every instance; a resume is refused with `CLUSTER_OPERATION_REFUSED` and audited as 245 audits a resume whose apply fails (`ALLOW:RESUMED`, then a linked `DENY`); after the gate reopens, the resume succeeds. `HazelcastOversightState` reaches the document through `instance()`, never `servingInstance()` (227's `ServingInstanceArchitectureTest` allows it), and the resume refusal comes from the status check, not from the accessor.
- [ ] `OversightMapsBoundary`: the oversight map holds exactly the document, which opens under the test key; scope ids appear only inside the plaintext.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

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
