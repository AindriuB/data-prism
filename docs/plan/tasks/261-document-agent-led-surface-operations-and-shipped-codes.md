# 261 — Document the 0.8 agent-led surface, draft the operations guide, and document every shipped code (register successor S3)

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** the 0.8 integration branch once every dependency has merged
**Depends on:** 238, 244, 245, 246, 247, 249, 250, 253, 255, 256, 230 (sealed-cluster plan; 230 adds rows to `docs/configuration.md` and otherwise could run alongside this task, since both follow 255; its startup codes also belong in the startup-refusal index)
**Wave:** 13 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- docs/tools.md
- docs/audit.md *(the disposition keys and values these tasks add, and the resolver-failure statement only)*
- docs/reidentification.md *(the refusal-code table only)*
- docs/multiple-instances.md *(the refusal-code table only)*
- docs/configuration.md *(the `json-sources` `description:` block and file list, and a startup-refusal index section only)*
- docs/extending.md *(the adapter description and `KeyResolver` sections only)*
- docs/developer-guide/write-an-adapter.md *(the description section only)*
- docs/operations.md *(new: draft operations guide)*
- docs/migration-0.8.md *(new: the core sections; task 213 adds its own sections afterwards)*
- CHANGELOG.md *(`[Unreleased]`, the core entries only)*
- mkdocs.yml *(nav and llmstxt entries for `operations.md` and `migration-0.8.md` only)*

## Goal
Make the 0.8 core usable by someone who has never seen the project: the three new tools and `targetSources` in the tool reference, the new audit keys, how an adapter or a configured source describes itself and how a resolver is written, a first operations guide for running against many sources, and the migration notes for every behaviour the core changed. The roadmap asks for a draft operations guide in 0.8; the register's successor S3 asks that every shipped code be documented on the page for its surface.

## Context
- The Outcome of each dependency: what merged, and its "for the migration notes" list (246, 247 in particular).
- docs/plan/design/catalog.md, sealed-links.md, key-resolvers.md, batch-and-bulk-follow.md Part A, as corrected by 238.
- docs/plan/design/contract-register.md section 3, "Docs" column: the 68 shipped codes no shipped page lists, to be re-derived by re-running E1, E1c, E2 and E1r on this base.
- docs/migration-0.7.md and docs/tools.md: the existing structure, the status table and the unknown-status rule (OD-200-1: "not answered").
- docs-site/hooks/check_*.py: the documentation checks, including captured examples.

## Acceptance
- [ ] `docs/tools.md` documents `list_sources` (filters, paging, `page`, `labels`), `describe_source` (the protection vocabulary, `undeclared`, `valueNamespace`, `chainable`), `follow` (input, envelope, `items`, `itemsTruncated`, the uniform `LINK_INVALID`, `LINK_ATTEMPTS_EXHAUSTED`), `targetSources`, `_links` and `_linksOmitted`, the `RESOLVER_FAILED` status, `AUDIT_UNAVAILABLE` on every denial path, and the capabilities `LIST_SOURCES`, `DESCRIBE_SOURCE` and `FOLLOW` with the upgrade note that adding a tool grants nothing. Each example was captured against the real stack and the capture command is in the Outcome.
- [ ] `docs/audit.md` documents the `names` key (`REAL`, `ALIAS`), the `catalog` key and its `REFUSED:CATALOG_<REASON>` values, the `link` key and its `REFUSED:LINK_<REASON>` values, `<target>[<i>]:<refused>`, and that link ids, attempt counts and resolver timings are metrics, not audit fields.
- [ ] S3: every shipped code with "Docs: no" in register section 3, re-derived on this base, is documented on the page for its surface; the Outcome lists each code with its page and heading.
- [ ] `docs/operations.md` (draft, marked so) covers: sizing the read budget and scope total for many sources (the batch design's formula), the request deadline and per-source timeout, reviewing source descriptions (the startup check is a guard rail and the operator is the reviewer), enabling links and the checklist before doing so, that a retired link key must be destroyed in the key store and not only removed from memory (with a pointer to the rotation procedure of 213), and which health and metric signals to watch.
- [ ] `docs/migration-0.8.md` covers, for the core: per-source timeout semantics, per-source budget charging and the scope total, that `dataprism.limits.scope-read-budget` (default 100) now counts source reads (OD-08-3), the new `request-timeout` (`PT10S`) and `scope-total-read-budget` (50,000), the refusal that replaces `SKIPPED_OVER_LIMIT` (as OD-08-4 decided), that `EXPOSE_SOURCE_NAMES` alone no longer shows real names, `D-RM-9`'s refusal for an unselected large estate, the new capabilities, the `json-sources` changes, and that `list_sources` allows every source in 0.8 and will deny unmatched sources from 0.9 (catalog OD-C5). `CHANGELOG.md` `[Unreleased]` lists the same, with the `SKIPPED_OVER_LIMIT` removal under Breaking (OD-08-4).
- [ ] Wording says "supports", never "compliant" or "compliance". Every example uses the invented logistics domain of the design documents; no real system, organisation or person.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`.

## Out of scope
- Key-rotation procedures, the 1.0 controls and their migration sections (213).
- `docs/quickstart.md` (262).
- `docs/data-protection.md` (214).
- Any code, test, or design document.
