# 310 — Write the key-rotation guide

Split from 213 on 2026-11-12 (owner agreement of that day: 213 becomes four parallel docs tasks, 310 to 313, and one integrating task, 314). 213 is retired at `tasks/retired/213-document-key-rotation-and-1-0-controls.md`, which maps each of its criteria to its new home.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main`
**Depends on:** none. Prerequisites, all merged: 206, 207, 211, 222, 225, 227, 228, 255.
**Wave:** 14a (parallel with 311, 312, 313; 314 follows)
**Owns:**
- docs/key-rotation.md (new)
- docs/reidentification.md
- docs/log-shipping.md
- docs/use-cases/gdpr-data-minimisation-mcp.md *(the "HMAC key custody" bullet, :74-79 at filing, only)*
- docs/operations.md *(the two forward references to the key-rotation guide only: the Draft note at :9-12 and the last sentence of "Retiring a link key" at :134-137, at filing)*
- mkdocs.yml *(insert `key-rotation.md` into the Reference list of `nav` and of `llmstxt` only; strict builds refuse a page missing from nav. Every other `mkdocs.yml` change is 314's)*

## Goal
Publish one operator page with the key-rotation procedures for every key 0.8 ships, and replace the statements that say no procedure exists. 1.0 is a stability release, so what is documented here is what 0.8 ships.

## Rules for every claim
- Check every claim and example against current code on `origin/main` and the merged Outcomes (HISTORY.md entries via `HISTORY-INDEX.md`, the retired briefs), never against the October 213 brief. 228, 255, 260, 261, 263 (Part A), 304, 307, 309 and 309-F1 merged after it was written. Nothing proposed or deferred (224, 226, 229, 230, 231, 264, 295) is presented as shipped.
- Write "supports", never "compliant" or "compliance". Make no claim that a deployment meets a legal duty.
- Never call a cluster "secured" or "protected" by the trusted-network guardrail.
- Link only to pages and anchors on `origin/main` or in this task's Owns. Do not link to anything 311, 312 or 313 adds.

## Context
- `docs/plan/tasks/retired/{206,207,211}-*.md` (Outcomes): audit key, retired keys, segments. `retired/222-*.md`: cluster and name keys. `retired/225-*.md`: identity and re-identification entries sealed under the current cluster key only. `retired/255-*.md`: link keys as wired (PLAN.md wave-3 follow-up: only the current link key verifies until `LinkKeys` gets its held-key-id set; check the code).
- Owner decision (b), 2026-10-23: rolling cluster-key rotation (229) is deferred past 1.0, so a cluster-key change is a planned full restart; `tasks/deferred/229-*.md` describes what is not shipped.
- The unresolved coupling question to preserve: `docs/plan/archive/PLAN-2026-11-10.md:2236-2243` (tracing an old exposure depends on the HMAC key and `PseudonymisationVersion` staying stable).
- `docs/configuration.md:208-211` documents `cluster-key.next` and `.previous`; read it, do not edit it. Check what the code does with them before describing a procedure that uses them.
- Statements that say no procedure exists: `docs/use-cases/gdpr-data-minimisation-mcp.md:79`, `docs/operations.md:11` and `:136`.
- `docs/reidentification.md:211` ends with the stale line "Using the operator surface end to end in the Compose quickstart is not yet done." (PLAN.md, wave 6 follow-ups, 262). Check whether it is now done; correct or remove it.
- A new page carries its own `title` and `description` front matter (`docs/log-shipping.md:1-4` is the shape); `docs-site/hooks/site.py:6-11`: description at most 155 characters and unique.

## Acceptance
- [ ] AC1 (213 AC2) `docs/key-rotation.md` has a numbered pseudonymisation-key rotation procedure covering existing pinned scopes, new scopes, re-identification, and retaining historical keys for exposure tracing. It states the recorded unresolved coupling question as open, without inventing its answer.
- [ ] AC2 (213 AC3) Separate procedures cover the audit key, the cluster key, the name key, and link keys as implemented. A cluster-key change and a name-key change are each a planned full restart with state loss: identity and re-identification entries are sealed under the current key only (225), so the re-identification index is emptied and earlier pseudonyms can no longer be re-identified. No rolling cluster-key procedure is presented (229 deferred). The name key is never derived from the pseudonymisation key, and plaintext re-identification is never described as an approved alternative.
- [ ] AC3 (213 AC5) The cluster procedure describes the incompatible old and new map formats and a controlled cutover. It does not present a mixed 0.7/0.8 rolling upgrade that resets read counts as safe. It explains name-key changes and re-identification store loss.
- [ ] AC4 (213 AC12) The old statement that no rotation procedure exists (`gdpr-data-minimisation-mcp.md:79`) is replaced by a link to `key-rotation.md`. Segment examples in `docs/log-shipping.md` still match `audit-*.log` and the segment names the code writes.
- [ ] AC5 `docs/operations.md`'s two "will link when it is published" statements link to `key-rotation.md` instead; nothing else on that page changes.
- [ ] AC6 `docs/reidentification.md` says a cluster-key or name-key change empties the re-identification index and that pseudonyms issued before it can no longer be re-identified, with a link to `key-rotation.md`; its stale last line is corrected or removed.
- [ ] AC7 `key-rotation.md` is in the Reference list of `nav` and of `llmstxt` in `mkdocs.yml`, and nowhere else is changed there.
- [ ] AC8 The PR description's Outcome has a `Facts for 314` list in the format of `tasks/314-*.md` ("Hand-off format"), naming every key, property, refusal or behaviour change found while writing this page, or "none".
- [ ] AC9 (213 AC22) `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Checks
- `docs-site` command — AC9, AC7
  run: `mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — AC5, AC7 (files only; line limits are read)
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/key-rotation\.md|docs/reidentification\.md|docs/log-shipping\.md|docs/use-cases/gdpr-data-minimisation-mcp\.md|docs/operations\.md|mkdocs\.yml')"`
- `no-compliance-wording` command — rules
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs mkdocs.yml | grep -E '^\+[^+]' | grep -i -E 'complian')"`
- `no-old-statement` command — AC4, AC5
  run: `test -z "$(grep -n -E 'does not document a rotation procedure|will link when it is published' docs/use-cases/gdpr-data-minimisation-mcp.md docs/operations.md)"`
- `procedures` read — AC1, AC2, AC3, AC6
  how: reviewer checks each procedure step against the cited code and Outcomes; confirms no rolling cluster-key step, no derived name key, the coupling question left open.
- `segments` read — AC4
  how: reviewer compares the segment names in `log-shipping.md` with the code that names segments (207's Outcome).
- `line-limits` read — AC5, AC7
  how: the diff of `operations.md`, the use-case page and `mkdocs.yml` touches only the lines named in Owns.
- `hand-off` read — AC8
  how: the PR body has the list, in the format, with a source for each fact.

## Out of scope
- `docs/audit.md` and `docs/architecture.md` (311), `docs/multiple-instances.md` and `docs/eu-ai-act.md` (312), `docs/extending.md`, `docs/tools.md`, `docs/faq.md` (313), `docs/migration-0.8.md` and `CHANGELOG.md` (314).
- `docs/data-protection.md` (214), code, `docs/configuration.md`.
- The rest of `docs/operations.md`, including 239-15, 239-16 and 239-23 (PLAN.md, 261's review leftovers, untasked).
- 0.7 release documentation, version cuts, release publication and new 1.0 features.

## Review round 1 — findings

Reviewed HEAD a84ff0584633d031c67fdeb0b31ad9a77715868b (code review CLEAN). Owner decision 2026-11-12: findings that would mislead an operator on security or data loss are fixed in this round despite the docs Mediums rule; the other P3s and NOTEs are follow-ups.

- [P3, fix now] docs/key-rotation.md:255-257 — the name-key section says "use the cluster procedure above", but that procedure's step 4 says "Keep the name key unchanged" and step 7 says "Destroy the old cluster key"; pseudonymisation step 6 (:82-83) also sends readers to "the cluster steps… for the order". An operator changing only the name key could generate an unneeded cluster key or destroy the cluster key in use, and the next member restart fails to resolve its key or refuses `CLUSTER_KEY_MISMATCH` — an unplanned full restart with state loss. Fix: say exactly which steps a name-key-only change follows (generate, set and destroy the name key; leave the cluster key alone), and for a pseudonymisation-key change say that only the stop-all/start-all order applies.
