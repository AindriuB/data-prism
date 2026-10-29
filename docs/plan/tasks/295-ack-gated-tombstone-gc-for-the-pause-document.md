# 295 — Ack-gated tombstone GC for the pause document

Filed 2026-10-29 as the follow-up the owner named when choosing option (e) for 228 (228, "Amendments 2026-10-29", revision note, decision 2). In 0.8, after 228, the pause document is an observed-remove set that only grows. Every pause adds an element and every resume adds tombstones. Nothing is ever removed, and a cluster that reaches 228's operating cap (8,192 elements, the last 16 reserved for `pause all`; tombstones are not counted, so total entries stay within 16,384; revised 2026-10-29 by coordinator decision after external review of PR #194 (round 3), owner may override; the cap is judged on the merged base and is separate from the format bound of 65,536 elements and 65,536 tombstones, so only a merge of split sides can produce a document above 16,384 entries, per coordinator decision 2026-10-29, owner may override; revised again per coordinator decision 2026-10-29, owner may override: non-`all` pauses stop at 8,176 elements and `pause all` is accepted up to the format bound, so `pause all` / resume-all churn without GC also grows the document past 16,384 entries, up to the format bound, and this task is the fix for that residual) can then only `pause all`. This task removes element-and-tombstone pairs once every verified member provably holds the tombstone.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 228 and 287.
**Depends on:** 228 (creates the observed-remove document, the commit guard, the memo and the `Peers` seam this task extends), 287 (wave 8; owns the existing hazelcast tests, `PauseDocumentTest.java` and `PauseSplitBrainTest.java` among them, for its trusted-network declaration)
**Wave:** 9 (after 228 in wave 7 and 287 in wave 8). In wave 9 its Owns are disjoint from 252 (orchestration `follow`), 254 (Spring limits and catalog wiring) and 293 (core audit retention). 286 (wave 10) owns no file here.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PauseDocument.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseDocumentTest.java *(insertions, plus changes where a test builds or reads tombstones raw)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseSplitBrainTest.java *(insertions only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PauseTombstoneGcTest.java *(new; name may differ)*
- Not owned: `HazelcastOversightState.java`, `MemberCards.java`, `OversightMaps.java`, `OversightMapsBoundary.java`, every server and Spring file. If a clean fix needs one of them, for example to list the verified member uuids, stop and report.

## Goal
A tombstone and the element it covers are pruned together, once every verified member has acknowledged the tombstone and this member has held it long enough that no in-lease replay can still bring the element back without it. The document then stays well under the cap in normal operation. Every failure of the rule makes a target paused again, never lifted.

## Context
- docs/plan/tasks/228-signed-leased-pause-document.md, "Amendments 2026-10-29": amendment 3 (elements, tombstones, `held ⊔ doc`, base, the preconditions), amendment 4 (the commit guard and the cap), amendment 7 (`pause.conflict` as redefined), and the superseded section for what the model replaced.
- `PauseDocument.java` after 228: the `Peers` seam (`highestReportedSequence`, `unverifiedCount`, `isOldest`), `commit`, `pushHeld`, the memo keyed by the sealed string, and the injected nano clock.
- `MemberCards.verifiedMembers()` (227): the source of the verified member uuids, read through `Peers`.
- `ClusterSecuritySettings` defaults: `pause-lease` `PT30S`, `clock-skew-tolerance` `PT5S`.
- docs/plan/design/cluster-security.md 2.3.3 and the new OD for the cap, the `all` reserve and GC deferral (the scribe adds it from PR #194); OD-215-16.

## Required approach
**Acks.**
- Each tombstone carries a grow-only `acks` set of member uuids. Merge unions the acks of the same tombstone id. The commit guard requires each stored tombstone's acks to be a subset of `next`'s, except for a pruned pair (below).
- A member that holds tombstone T, and is not in acks(T), adds its own uuid on its next write. A lease refresh counts as a write.
- A tick on a member whose ack is missing from some held tombstone makes one ack write, at most once per lease. It is `held ⊔ stored` plus its acks, at `max + 1`, over the exact stored bytes, and only over a CURRENT or BEHIND authentic document. Without this, a member that never refreshes would stall GC forever. Stalling fails safe, but the cap would then be reached.
- A member adds only its own uuid, never another's.

**Prune.** A member prunes element e and its tombstone T together, in its own held view and in its next write, only when all of these hold at that moment:
- acks(T) covers every verified card member, as `Peers` reports them;
- `unverifiedCount() == 0`;
- this member has held T for at least `pause-lease + clock-skew-tolerance` by its own monotonic clock. A local first-seen time per tombstone id is kept in memory and not written.
- The commit guard allows `next` to lack a stored element and tombstone only as such a pair, only when the prune condition holds, and never one without the other.
- A member that has pruned the pair and later receives a document still holding both re-prunes on acceptance, if the condition still holds. Pruning never makes a target live.

**Residuals, recorded, both fail closed.**
- A member that was unverified, or absent, at prune time and still holds e without T resurrects e once. The target is paused again until an operator resumes it.
- An attacker replay, within the lease, of a document holding e without T, made into a warm member that has pruned T, resurrects e until an operator resumes it. A cold member rejects the same replay through the cold-start gate.
- Divergence is signalled by `pause.conflict` (the document brings an element held lacks) and one rate-limited WARN with no pause content, scope, tool or member address. No new metric.

## Acceptance
- [ ] Property tests, as seeded JUnit loops over random member sets, write orders, acks and prunes (no new dependency):
  - merge, acks included, is commutative, associative and idempotent;
  - no merge or prune lifts a target that some member's view holds live;
  - a prune never removes an element without its tombstone, or a tombstone without its element.
- [ ] Three verified members, one resume: every member acks, and after `pause-lease + clock-skew-tolerance` on each member's nano clock the pair is gone from the stored document and from every held view. The target stays lifted.
- [ ] No prune while any verified member's ack is missing, while `unverifiedCount() > 0`, or before the held-for time on this member's clock, each shown with the stored document still holding the pair.
- [ ] A member that never refreshes still acks through the ack write, and that write happens at most once per lease.
- [ ] A member adds only its own uuid. A mutant that adds another member's uuid fails a test.
- [ ] The residual: a pruned pair, then a replay into a warm member that lacks T, shows the target paused again on that member. `pause.conflict` increments, one WARN is logged, and an operator resume clears it. A cold start rejects the same replay.
- [ ] The unverified-member residual: a member unverified at prune time that holds e without T, once verified, makes the target paused again everywhere, and one operator resume clears it.
- [ ] The cap: a cluster driven through more than 16,384 pause and resume pairs, with acks flowing, never refuses a non-`all` pause.
- [ ] Mutants that must fail at least one test (list them in the Outcome):
  - prune ignores acks;
  - prune ignores `unverifiedCount()`;
  - prune ignores the held-for time, or uses the wall clock;
  - prune drops e without T, or T without e;
  - acks are replaced instead of unioned.
- [ ] Every 228 test still passes.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Out of scope
- Any change to the cap values, the `all` reserve or the decode guard (228, owner decision).
- Any change to `MemberCards`, `HazelcastOversightState`, the resume response or the SPI.
- A GC that prunes on the wall clock, by sequence distance, or without acks.
- Design-doc edits: the scribe records the GC rule from this task's Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented scope and tool names. Keys are generated test bytes.
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Both clocks are injected; waits are on events or polled state with a bound.
