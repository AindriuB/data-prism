# 311 — Document the keyed audit chain and record the 1.0 decisions

Split from 213 on 2026-11-12 (owner agreement of that day: 213 becomes four parallel docs tasks, 310 to 313, and one integrating task, 314). 213 is retired at `tasks/retired/213-document-key-rotation-and-1-0-controls.md`, which maps each of its criteria to its new home.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main`
**Depends on:** none. Prerequisites, all merged: 206, 207, 211, 292, 293.
**Wave:** 14a (parallel with 310, 312, 313; 314 follows)
**Owns:**
- docs/audit.md
- docs/architecture.md *(added lines only, in "Decisions worth knowing": new dated decision records and explicit supersession pointers)*

## Goal
`docs/audit.md` describes the audit chain as 0.8 ships it: keyed and unkeyed, current and retired keys, size-capped segments, and the honest limits of each. `docs/architecture.md` records the 1.0 decisions as they now stand, so a reader can tell what was imported, what was reconciled and what the owner later amended.

## Rules for every claim
- Check every claim and example against current code on `origin/main` and the merged Outcomes (HISTORY.md entries via `HISTORY-INDEX.md`, the retired briefs), never against the October 213 brief. 228, 255, 260, 261, 263 (Part A), 304, 307, 309 and 309-F1 merged after it was written. Nothing proposed or deferred (224, 226, 229, 230, 231, 264, 295) is presented as shipped.
- Write "supports", never "compliant" or "compliance". Make no claim that a deployment meets a legal duty.
- Never call a cluster "secured" or "protected" by the trusted-network guardrail.
- Link only to pages and anchors on `origin/main` or in this task's Owns. Do not link to anything 310, 312 or 313 adds (`key-rotation.md` included).

## Context
- `docs/plan/tasks/retired/206-keyed-audit-chain.md`, `207-size-capped-audit-segments.md`, `211-wire-keyed-audit-chain-and-segment-cap.md` (Outcomes): the keyed chain, retired keys, verifier CLI flags, segments, retention. `AuditChainVerifierCli` is the CLI as built; the flags are what its code accepts, not what the register proposed.
- `docs/audit.md` today has no keyed-chain section at all (checked 2026-11-12): headings at :28 (`FileAuditSink`), :121 (Retention), :332 (the offline verifier), :761 ("What this does and does not prove"), :821 (External checkpoints), :854 (Directory mode).
- PLAN.md, wave-4 follow-ups (213): `KEYED_LIMITATION` is not printed when the audit path is unreadable, and a lone non-GENESIS writer carries no key label. Say both in `audit.md`.
- D-1.0-4 (optional keyed audit chain, separate key, no protection against a key holder): `docs/plan/reconciliation-2026-10-19.md:9`; the rejection it partly reverses is the keyed-chain record at `docs/architecture.md:553-581` (reaffirmed 2026-10-06, D2). Keep that record's operator-threat reasoning; add the partial reversal as a new dated record with a pointer from the old one.
- October 15 imported decisions (the D-1.0-* family, PR #129's imported briefs) and the October 19 reconciliation: `docs/plan/reconciliation-2026-10-19.md`, `docs/plan/DECISIONS.md:34` (D-1.0-2). October 23 amendment of D-RM-1 (a), owner decision (b): `docs/plan/PLAN.md` "After 1.0: untrusted-network clusters" (:360-369), `DECISIONS.md:149-150`. The 1.0 claim to use is PLAN.md:369 (what 1.0 does and does not protect on a cluster).

## Acceptance
- [ ] AC1 (213 AC6) `docs/audit.md` covers current and retired HMAC keys, the verifier CLI flags, keyed and unkeyed verification, size-capped segment names and retention. It explains the transition from existing unkeyed logs: keyed retention refuses those writers; the old trail is preserved and verified separately, never silently skipped or deleted.
- [ ] AC2 (213 AC7) The limits are repeated: unkeyed chains do not resist rewriting by the operator; keyed chains do not resist key holders; truncation needs checkpoints under separate custody. `docs/architecture.md` records D-1.0-4's partial reversal of the old keyed-chain rejection, preserving its operator-threat reasoning.
- [ ] AC3 (213 AC8) Architecture records distinguish the October 15 imported decisions from the October 19 reconciliation, and add the October 23 amendment of D-RM-1 (a) (owner decision (b)): 1.0 supports multiple instances on a trusted network only, and the sealed-state design is the post-1.0 path for untrusted networks. Isolation-only claims are replaced by the 1.0 claim (PLAN.md:369), not by the full sealed-state claim, which 1.0 does not ship.
- [ ] AC4 `docs/audit.md` says that `KEYED_LIMITATION` is not printed when the audit path is unreadable, and that a lone non-GENESIS writer carries no key label.
- [ ] AC5 `docs/architecture.md` changes are additions only: no existing decision text is removed or reworded, apart from added pointers.
- [ ] AC6 The PR description's Outcome has a `Facts for 314` list in the format of `tasks/314-*.md` ("Hand-off format"): every audit property, CLI flag, startup refusal and behaviour change documented here, or "none".
- [ ] AC7 (213 AC22) `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Checks
- `docs-site` command — AC7
  run: `mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/audit\.md|docs/architecture\.md')"`
- `architecture-additions-only` command — AC5
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs/architecture.md | grep -E '^-[^-]')"`
- `no-compliance-wording` command — rules
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs | grep -E '^\+[^+]' | grep -i -E 'complian')"`
- `audit-content` read — AC1, AC2, AC4
  how: reviewer checks the keys, flags, segment names and transition against `AuditChainVerifierCli` and 206/207/211's Outcomes, and that all three limits are stated.
- `decision-records` read — AC2, AC3
  how: reviewer finds the D-1.0-4 record, the Oct 15 / Oct 19 distinction and the Oct 23 amendment, each dated, with pointers from what they supersede.
- `hand-off` read — AC6
  how: the PR body has the list, in the format, with a source for each fact.

## Out of scope
- `docs/key-rotation.md` and `docs/log-shipping.md` (310), the cluster pages (312), `docs/extending.md`, `docs/tools.md`, `docs/faq.md` (313), `docs/migration-0.8.md` and `CHANGELOG.md` (314).
- 239-8 (`docs/audit.md:330`, retention fallback codes): a 261 review leftover in PLAN.md, untasked.
- The audit-forgery follow-ups under PLAN.md "Untasked security follow-ups" (archive:206, archive:229-231): code, not docs.
- `docs/data-protection.md` (214), code, `docs/configuration.md`, version cuts and publication.

## Attempt 1 — failed

Verified at cc005580a11c6a2064646487d0d05a24b482148e: tester PASS (docs-site, scope, additions-only, no-compliance-wording); reviewer REQUEST CHANGES. Code review (CLEAN) raised the same point as a P3.

- docs/architecture.md:712-714 and the pointer at :584 date the keyed chain as "shipped in 0.8 on 2026-10-23 (206, 207, 211)". Only 206 landed on 2026-10-23; 207 landed on 2026-10-28 and 211 (the configuration the record describes: key reference resolved at startup, retired keys, `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY`) on 2026-11-04 (HISTORY-INDEX.md:37, :39, :40). 0.8.0 is unreleased. Fix: say it was built by 206 (2026-10-23), 207 (2026-10-28) and 211 (2026-11-04) for 0.8.0, and do not say "shipped". Additions-only still holds: these are lines this task added.
