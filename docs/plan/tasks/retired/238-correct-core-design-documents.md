# 238 — Correct the stale rows in the core design documents and apply the OD-200 renames (register successor S1)

**Repo:** `.`
**Release:** 0.8.0 (planning documents; lands before the 0.8 core code tasks cite the documents)
**Kind:** planning documents only; no code, no shipped page.
**Base branch:** `origin/main`
**Depends on:** none
**Wave:** 1 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- docs/plan/design/sealed-links.md
- docs/plan/design/key-resolvers.md
- docs/plan/design/catalog.md
- docs/plan/design/batch-and-bulk-follow.md

## Goal
The contract register (`docs/plan/design/contract-register.md`, sections 12 and 14) recorded stale rows and fifteen decisions that the four core design documents do not yet reflect. Bring those four documents into line with the decided names and caps, so that every 0.8 core brief (239 to 263) and its implementer read one consistent contract. Nothing is decided here: every edit applies a decision already recorded.

## Context
- docs/plan/design/contract-register.md section 12 (D2 to D6, D11, D12) and section 14 (OD-200-3, -4, -5, -6, -7, -8, -12): what is stale and what was decided.
- docs/plan/design/contract-register.md section 8.2: the combined relation-byte table (`0x00` to `0x04`, `0x05` to `0xFF` reserved and invalid).
- docs/plan/design/contract-register.md section 10: the withdrawn names (`link:id`, `link:attempts`, `links:issued`, `links:omitted`, `resolver:<name>`, `resolver:<name>:ms`, `INVALID:<reason>`, `dataprism.paging.key-id`, `PAGE_INVALID`).
- The cluster rows (D7, D8, OD-200-9 and `cluster-security.md`) belong to the sealed-cluster plan (task 218 on `plan/0.8-sealed-cluster`), not to this task.

## Acceptance
- [ ] `sealed-links.md`: the closing table no longer lists `link:id`, `link:attempts`, `links:issued` or `links:omitted` as disposition keys; each is moved to a "withdrawn (OD-13 shape C)" note. The `link` row's values read `REFUSED:LINK_<REASON>`, not `INVALID:<reason>`.
- [ ] `sealed-links.md`: `dataprism.links.max-chain-depth` and `DEPTH_EXCEEDED` are removed from the configuration table, the reason table, the limiter text and the closing table, each replaced by a one-line note citing OD-200-8. Test obligation 21 is marked "withdrawn with option (b)". `LINK_ORIGIN_DRIFT` stays.
- [ ] `sealed-links.md`: the header table shows relation bytes `0x00` to `0x04` with the tool that accepts each, and states that `0x05` to `0xFF` are invalid, matching register section 8.2.
- [ ] `sealed-links.md`: a `FOLLOW` capability is defined for `follow` (OD-200-5), off until granted, with the upgrade-note sentence.
- [ ] All four documents apply OD-200-4: source labels at most 64 (every schema `maxLength` for `source`, `targetSources` items and the `label` definition is 64), key namespace and resolver name at most 32 (grammar `[a-z][a-z0-9-]{0,31}`), entity type 64 (the collection-page prose reads 1 + 64), field path 64. The worst-case length arithmetic in `sealed-links.md` section 3 and `batch-and-bulk-follow.md` Part A and 3.2 is recomputed with these caps, and each recomputed figure is at most the 1024 default.
- [ ] `key-resolvers.md`: every `KEY_UNRESOLVED` becomes `RESOLVER_FAILED` (OD-200-3), except one note that records the rename. The failure table's audit column states shape C: a failure is the target's `name:RESOLVER_FAILED` entry plus a metric, and `FAILED:<REASON>`, `EMPTY` and `INVALID_OUTPUT` are not disposition values. The closing table gains `KeyNamespace` and drops `resolver:<name>` and `resolver:<name>:ms`.
- [ ] `key-resolvers.md` and `catalog.md`: the annotation is `@Chainable(keyNamespace = "…")` (OD-200-6).
- [ ] `catalog.md`: the metric reads `dataprism.catalog.source_names.nonconforming` (OD-200-7), and one sentence says the audit key `names` and the result field `labels` are one setting on two surfaces (OD-200-12).
- [ ] Every edit cites its decision id in the line it changes or in a dated change note at the top of the document. No decision is changed and no new name is introduced. `git diff --stat` lists only the four owned files.
- [ ] Checks that pass, run from the repository root: `rg -n 'KEY_UNRESOLVED' docs/plan/design/{sealed-links,key-resolvers,catalog,batch-and-bulk-follow}.md` prints only rename notes; `rg -n 'max-chain-depth|DEPTH_EXCEEDED' docs/plan/design/sealed-links.md` prints only withdrawal notes; `rg -n 'source-names\.nonconforming' docs/plan/design/catalog.md` prints only the rename note.
- [ ] Every JSON schema touched is still valid draft 2020-12 and each `example-of` block still validates against it, by the command recorded in the Outcome of the retired task 198. The Outcome gives the command and its output.

## Out of scope
- `docs/plan/design/cluster-security.md` and `docs/plan/design/contract-register.md`: task 218 owns the cluster rows; the register is re-run at 0.9.0-rc.
- Any shipped page under `docs/` (task 261 and 213).
- Code, tests, or any name not already decided.

## Outcome (2026-10-21, 0.8 wave 1)
Merged onto `record/0.8-wave1a` (task branch head 49a57829; tester PASS, reviewer APPROVE after four rounds). `sealed-links.md`, `key-resolvers.md`, `catalog.md` and `batch-and-bulk-follow.md` now apply the OD-200 decisions: the renames and caps, the shape C closing tables (withdrawn disposition keys moved to notes), the `FOLLOW` capability, the combined relation-byte table (`0x00` to `0x04`, the rest invalid) and the 11 link reasons.

Coordinator decisions made during the task:

- Source-label schemas carry `maxLength` 128 in 0.8 and 64 from 0.9, with the refusal `INVALID_SOURCE_NAME` (OD-200-4, OD-C18 (c)). The register's OD-200-4 (a) wording says 64. The owner was told this and did not object.
- With real names in effect, a page with entries after it ends at the last entry within the page size whose label fits 64 bytes; if none fits, it extends to the next one that does, or to `last:true`.
- Metric constant names are left to task 241 ("one metric per reason, OD-200-15").
- `_links.field` and indexed paths carry no `maxLength`.

Schema check: `Draft202012Validator` over the touched schemas, catalog 7 schemas and 3 examples, batch 7 and 5, sealed-links 3 blocks, all valid; across the four documents 14 schemas and 8 examples validated, and `mkdocs --strict` passed.
