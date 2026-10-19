# 198 — Design: `get_entity_contexts` batch, paged collections and status-only bulk follow

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 190 (per-source result shape and status codes), 191 (sealed next and drill-in links), 197 (catalog contract and OD-C16)
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/batch-and-bulk-follow.md (new)
- docs/plan/tasks/198-design-batch-paging-and-bulk-follow.md (Outcome section only)

(Location decided by D-070-12 (a): `docs/plan/design/`, internal.)

## Goal
First specify paging for `list_sources` in 0.8, as decided in catalog OD-C16.
The owner expects thousands of items per investigation. 0.9 builds the batch
tool, paged collections and a status-only bulk follow that returns counts plus
drill-in links for the exceptions. This task fixes those contracts now, so the
0.8 per-source result and link work leaves room for them and the 0.9 freeze
holds no surprises.

## Context
- `docs/plan/design/catalog.md`, OD-C16: `list_sources` paging ships in 0.8;
  specify that page first, preserving the catalog filters and name-visibility rules.
- `docs/plan/PLAN.md` "Road to 1.0": D-RM-7 (batch size per scope, default 16,
  configured ceiling), D-RM-12 (per-source truncation at record boundaries with
  markers and next-page links; hard overall cap), D-RM-15 (paging plus
  status-only bulk follow with drill-in links), and the verified facts (read
  budget charged once per request, `SKIPPED_OVER_LIMIT` silent partial answers).
- `docs/plan/roadmap.md` 0.8 (read budget per source called plus per-scope
  total; fan-out deadlines) and 0.9 lists; "Risks" (link volume, scale per investigation).
- Task 190's per-source result shape (D-070-1 b), its seven status codes
  including `REFUSED` (D-070-3), per-source refusal isolation (D-070-2), and the
  rule that clients treat an unknown code as "not answered";
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
3. Paging: specify the `list_sources` page for 0.8 first, including input and
   output JSON Schema, ordering, filter and caller/scope binding, page-size keys
   and defaults, sealed next links, end-of-collection marker, and what happens
   when a link expires mid-walk or the catalog changes. Separately specify the
   collection paging that ships in 0.9.
4. Truncation markers (D-RM-12): shape, where they appear, the overall hard cap
   and its code.
5. Status-only bulk follow: input and output JSON Schema, the counts it returns
   (by status code, `REFUSED` included), drill-in links for exceptions, their cap, and why it never
   returns entity data.
6. Audit: one event per call or per item, the fields added, and an estimate of
   audit volume for a 5,000-item investigation.
7. "Boundaries" (1, 6, 7), "Test obligations for 0.8 and 0.9", "Open decisions for the
   owner" with options and a recommendation each.

## Acceptance
- [ ] The file exists with every numbered item as a heading; every schema is
      valid JSON Schema (draft 2020-12), validator command in the Outcome.
- [ ] Every proposed tool, refusal code, status code, audit field and
      configuration key is in a closing table and collides with nothing existing
      or in tasks 191 and 197's tables; reconcile in task 200.
- [ ] `list_sources` paging is specified first and marked for 0.8; batch,
      collection paging and bulk follow remain 0.9. The review checks OD-C16.
- [ ] Examples use an invented neutral domain.
- [ ] **Review gate:** `reviewer` returns APPROVE after checking against D-RM-7,
      12 and 15, the status codes as merged by task 190, and boundaries 1, 6 and
      7; confirms no product decision is taken in the text. Verdict in the Outcome.

## Out of scope
- Code, properties, schemas in `docs/tools.md`.
- Catalog semantics other than paging (task 197), link cryptography (task 191).

## Outcome

Written: `docs/plan/design/batch-and-bulk-follow.md`. Part A (before section 1) specifies the paged `list_sources` for 0.8 first (OD-C16): input and output schemas, keyset ordering, `page.last` end marker, a sealed next link bound to scope, caller, filters, label kind and a catalog tag, expiry and catalog-change behaviour, name-visibility rules, configuration, audit. Sections 1 to 7 are the brief's numbered items (batch tool, budget and limits per item, paging with 3.1 pointing to Part A and 3.2 collection paging, truncation markers and the overall cap, `follow_bulk`, audit with a 5,000-item estimate, boundaries 1, 6, 7); 8 test obligations, 9 open decisions OD-B1 to OD-B17 (options and a recommendation each, none decided), 10 closing names table. Branch cut from `origin/main` at `f960f2a9`.

Schema validation (draft 2020-12), run with the `jsonschema` package installed in a throwaway virtual environment outside the repository, using the same checker as task 197 (`check_schemas.py`, kept outside the repository):

```
python3 -m venv <scratch>/venv && <scratch>/venv/bin/pip install jsonschema
<scratch>/venv/bin/python -I check_schemas.py docs/plan/design/batch-and-bulk-follow.md
```

It extracts every fenced JSON block with a `$schema`, runs `Draft202012Validator.check_schema`, then validates each block preceded by `<!-- example-of: <title> -->` against the schema of that title. Result: 7 schemas valid (`list_sources` input and output, `get_entity_contexts` input and output, `follow page result`, `follow_bulk` input and output) and 5 examples valid. Negative checks confirmed the conditional rules reject `page.last: false` without `next`, `last: true` with `next`, and a `REFUSED` item without `code`.

Collision check: a loop ran `grep -rIlw <name>` over `docs/`, `data-prism-*/src`, `examples/`, `README.md` and `CHANGELOG.md` for each new tool, capability, refusal and startup code, result field, configuration group and `outcome`/`deferredBy` value. No hit outside this document, except `get_entity_contexts` (named in `roadmap.md` and this brief) and `maxItems`, which is a JSON Schema keyword in `catalog.md` and was therefore renamed `batchSize` in the result. Reuses, not new names: `LINK_INVALID`, `LINK_ATTEMPTS_EXHAUSTED`, `LINK_EXPIRED`, `LINK_ORIGIN_DRIFT`, `TOO_MANY_RECORDS`, `RESPONSE_TOO_LARGE`, `SCOPE_READ_BUDGET`, `INVALID_REQUEST_LIMITS`. Reconcile in task 200.

Cross-task points for 200: (1) relation bytes `0x02` to `0x04` extend task 191's header table; (2) a collection page link is 544 bytes / 728 characters at the worst, against `max-length` 768, so 191's sizing must include it; (3) `docs/tools.md` says an unknown status is "not answered" while PLAN.md D-070-3 says "an error"; (4) catalog schema `source` allows 128 characters while task 191 caps labels at 64 (the page link sizing in Part A uses the 128).

Forbidden-word scan: no real-world business or workplace terms; examples use the invented parcel, depot and courier-roster domain.

Review gate: first review CHANGES; revised (see below); re-review PENDING. The orchestrator runs the reviewer against D-RM-7, 12, 15, the status codes as merged by task 190 and boundaries 1, 6, 7. The document no longer claims that no product decision is taken: every number, name and shape is a proposal under an OD-B, and the text follows the recommendations where it must be specific.

### Revision after review

Changes: a relation-byte table says which tool accepts each of `0x00` to `0x04`; the batch cap now covers the envelope and an item that cannot fit an empty response is `REFUSED` (progress guaranteed); truncation is fail closed, with `next` required in every marker; `follow_bulk` has an explicit three-phase call with the attempt limiter defined (shared counter, whole-call refusal when locked or when the limit is reached), per-target counts and per-target omitted exceptions (D-RM-1), a group link bounded and counted against `max-links`, and one audit event per link asked; `pageSize` without a link key is handled inside OD-B2; the collection page link is sized at its true worst case (768 characters, zero margin, flagged with fixes in OD-B12); the page schema declares `_links` and `_linksOmitted`; the closing table gains `item`, `status`, `target`. Quiet decisions are now explicit: OD-B18 to OD-B23, plus extensions to OD-B2, OD-B10, OD-B11, OD-B12 and OD-B14. Open decisions are now OD-B1 to OD-B24 (OD-B24 added after the re-review). Schemas re-validated: 7 schemas and 5 examples valid, same checker.
