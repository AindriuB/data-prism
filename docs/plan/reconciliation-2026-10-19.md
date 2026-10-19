# Roadmap reconciliation — 2026-10-19

PR #130 is the in-progress 0.7 integration branch, not a finished release or a task-only PR. Its base is 0.6.0 plus the Maven release guards and PR #129's imported briefs. The imported ids collided with 0.7 tasks and were renumbered 203–214. No feature is claimed implemented by this reconciliation.

## Authority and decisions

The owner requested reconciliation in this chat on 2026-10-19 and explicitly chose: “Use the sealed-state design (Recommended)”. The question made clear that this means encrypted re-identification values and separate cluster/name keys, replacing the plaintext and isolation-only options. This resolves D-1.0-3 and D-1.0-7 in favour of the existing design's encrypted storage and independent purpose-specific keys, and supersedes D-1.0-2's isolation-only option. Preserve the qualified security claim and residual risks in `design/cluster-security.md`.

D-1.0-1 (case-authoriser SPI and explicit token-trusted fallback) and D-1.0-4 (optional keyed audit chain, separate key, no protection against a key holder) remain the decisions recorded in the imported task briefs. They are not newly decided here. D-1.0-5's “ships in 1.0” is scheduled as available **by** 1.0: feature/API changes land in 0.8, before the 0.9 freeze. The 1.0 release adds the stability commitment, not these new features.

## Allocation

| Task | Release/status | Reconciled scope |
|---|---|---|
| 203, 210 | 0.8 | Case authorisation and client-id allowlist, then configuration. These are distinct from 0.9 per-source role authorisation and do not control egress. |
| 204, 205, 212 | Superseded, not implemented | Preserve original briefs as history. No isolation-only join mode, plaintext re-identification alternative or second subject-key configuration is implemented from them. |
| 215 | 0.8 planning | Split the approved sealed-cluster design into implementation briefs, including 202's follow-ups and concrete dependencies for 213. |
| 206, 207, 211 | 0.8 | Optional keyed audit chain, bounded segments and configuration. Preserve checkpoint custody and operator-threat limitations. |
| 209 | 0.7 | Wire the existing pattern validator into both Spring orchestration paths. No new recogniser API is needed. |
| 208 | 0.8 | FREE_TEXT and recognisers, including extension of the bean introduced by 209. |
| 213 | 0.8 | Rotation procedures, migration, controls documentation and changelog when the controls ship. Blocked until 215 assigns its cluster dependencies. |
| 214 | 0.9 | Data-protection annex based only on merged controls, as release-candidate evidence. It does not close the rest of S12. |

## Current 0.7 sequence

- Completed on the integration branch: 188, 189, 190, 191, 192, 195, 197, 202.
- Ready: 193 (record/size caps), 194 (key-shape scan), 196 (load harness), 198 (paging/batch design). Task 198 now depends on completed 197 and designs `list_sources` paging for 0.8 first (OD-C16).
- After 194: 209 (existing pattern-validator wiring). This is a source-confirmed gap on both main and the PR head: `PrivacyEngineWiring` registers only `RawValueLeakValidator`; both orchestration configurations collect validator beans. Runtime proof belongs in 209, not in this planning review.
- 199 follows 193 and 196 (plus completed 189/190); its measurements require a quiet named machine and owner agreement before running.
- 200 follows 193 and 198 (other prerequisites completed); register imported contracts as proposals, with release/status distinctions.
- 201 follows 193, 194, 196 and 209 (other prerequisites completed); document the pattern scan as a 0.7 fix.
- Re-capture the two HTTP examples, complete combined verification and prepare the release cut only after those tasks. No version bump, tag or publication is authorised by this reconciliation.

All remaining 0.7 briefs branch from `plan/0.7.0` while that is the integration branch. Starting them from today's `main` would omit their prerequisites.

## Remaining planning work

This is a roadmap to 1.0, not a claim that every implementation brief already exists. Task 215 must split cluster security. The 0.8 many-source implementation, 0.9 external review/pilot and freeze gates, and each release cut still need concrete briefs. New API/configuration proposals must pass task 200's contract reconciliation and be updated before implementation if the approved designs change.

The existing 0.8 many-source, fan-out, budget, catalog and sealed-link scope remains. The extra controls make 0.8 heavier; split implementation into waves rather than moving breaking changes past the 0.9 freeze. A deferred control must be explicitly rescheduled before that freeze.

## PR repair and validation

The docs CI failure was a nav/llmstxt mismatch: `verifying-releases.md` was in nav only. Add it to both. Future page tasks must update both and explicitly run all `docs-site/hooks/check_*.py` after `mkdocs build --strict`; the build alone did not exercise every guard.

PR #130 remains draft until its open tasks and verification are complete. Successful CI on the current partial implementation is not evidence that the release scope is complete.
