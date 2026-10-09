# 198 — Design: `get_entity_contexts` batch, paged collections and status-only bulk follow

**Repo:** .
**Branch:** from `main` after tasks 190 and 191 have merged
**Depends on:** 190 (per-source result shape and status codes), 191 (sealed next and drill-in links)
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/batch-and-bulk-follow.md (new)
- docs/plan/tasks/198-design-batch-paging-and-bulk-follow.md (Outcome section only)

## Goal
The owner expects thousands of items per investigation. 0.9 builds the batch
tool, paged collections and a status-only bulk follow that returns counts plus
drill-in links for the exceptions. This task fixes those contracts now, so the
0.8 per-source result and link work leaves room for them and the 0.9 freeze
holds no surprises.

## Context
- `docs/plan/PLAN.md` "Road to 1.0": D-RM-7 (batch size per scope, default 16,
  configured ceiling), D-RM-12 (per-source truncation at record boundaries with
  markers and next-page links; hard overall cap), D-RM-15 (paging plus
  status-only bulk follow with drill-in links), and the verified facts (read
  budget charged once per request, `SKIPPED_OVER_LIMIT` silent partial answers).
- `docs/plan/roadmap.md` 0.8 (read budget per source called plus per-scope
  total; fan-out deadlines) and 0.9 lists; "Risks" (link volume, scale per investigation).
- Task 190's per-source result shape and status list in `docs/tools.md`;
  task 193's `TOO_MANY_RECORDS` / `RESPONSE_TOO_LARGE`.
- `docs/plan/design/sealed-links.md` (task 191) for next and drill-in links.
- `docs/audit.md` — one audit event per call today; audit volume at thousands
  of items is a stated risk.

## Required content
1. `get_entity_contexts`: input and output JSON Schema, per-item result reusing
   task 190's per-source shape, per-item status and refusal, ordering, the
   batch-size setting and ceiling (D-RM-7) with keys and defaults, the refusal
   code when exceeded.
2. Budget and limits per item: how read budget, `maxSources`, record and size
   caps apply across a batch, and how a partial batch is reported.
3. Paging: which collections page, page-size keys and defaults, sealed next
   links, end-of-collection marker, what happens when a link expires mid-walk.
4. Truncation markers (D-RM-12): shape, where they appear, the overall hard cap
   and its code.
5. Status-only bulk follow: input and output JSON Schema, the counts it returns
   (by status code), drill-in links for exceptions, their cap, and why it never
   returns entity data.
6. Audit: one event per call or per item, the fields added, and an estimate of
   audit volume for a 5,000-item investigation.
7. "Boundaries" (1, 6, 7), "Test obligations for 0.9", "Open decisions for the
   owner" with options and a recommendation each.

## Acceptance
- [ ] The file exists with every numbered item as a heading; every schema is
      valid JSON Schema (draft 2020-12), validator command in the Outcome.
- [ ] Every proposed tool, refusal code, status code, audit field and
      configuration key is in a closing table and collides with nothing existing
      or in tasks 191 and 197's tables (197 may run in parallel; reconcile in task 200).
- [ ] Examples use an invented neutral domain.
- [ ] **Review gate:** `reviewer` returns APPROVE after checking against D-RM-7,
      12 and 15, the status codes as merged by task 190, and boundaries 1, 6 and
      7; confirms no product decision is taken in the text. Verdict in the Outcome.

## Out of scope
- Code, properties, schemas in `docs/tools.md`.
- Catalog tools (task 197), link cryptography (task 191).
