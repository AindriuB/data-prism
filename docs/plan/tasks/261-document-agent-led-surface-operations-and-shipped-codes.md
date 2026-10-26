# 261 — Document the 0.8 agent-led surface, draft the operations guide, and document every shipped code (register successor S3)

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** the 0.8 integration branch once every dependency has merged
**Depends on:** 266 (serialised on `CHANGELOG.md`: 266 forward-ports the `[0.7.1]` section to `main` directly below `[Unreleased]`), 238, 244, 245, 246, 247, 249, 250, 253, 255, 256, 284 and 287 (the trusted-network codes belong in the startup-refusal index; 284 adds rows to `docs/configuration.md`), 286 (the `clusterSecurity` readiness contributor, a signal `docs/operations.md` names), 270, 271 and 272 (each leaves migration notes in its Outcome for `docs/migration-0.8.md`; added 2026-10-23)
**Wave:** 12 (re-planned 2026-10-23 for owner decision (b): 230 deferred past 1.0; 284, 286 and 287 added; 271 added by PR #161)
**Owns:**
- docs/tools.md
- docs/audit.md *(the disposition keys and values these tasks add, and the resolver-failure statement only)*
- docs/reidentification.md *(the refusal-code table only)*
- docs/multiple-instances.md *(the refusal-code table only)*
- docs/configuration.md *(the `json-sources` `description:` block and file list, and a startup-refusal index section only)*
- docs/extending.md *(the adapter description and `KeyResolver` sections only)*
- docs/developer-guide/write-an-adapter.md *(the description section only)*
- docs/operations.md *(new: draft operations guide)*
- docs/migration-0.8.md *(extend; created by 222's review round: the core sections are added here, and task 213 adds its own afterwards)*
- CHANGELOG.md *(`[Unreleased]`, the core entries only)*
- mkdocs.yml *(nav and llmstxt entries for `operations.md` only; the `migration-0.8.md` nav entries already exist)*

## Goal
Make the 0.8 core usable by someone who has never seen the project: the three new tools and `targetSources` in the tool reference, the new audit keys, how an adapter or a configured source describes itself and how a resolver is written, a first operations guide for running against many sources, and the migration notes for every behaviour the core changed. The roadmap asks for a draft operations guide in 0.8; the register's successor S3 asks that every shipped code be documented on the page for its surface.

## Context
- The Outcome of each dependency: what merged, and its "for the migration notes" list (246, 247 in particular).
- docs/plan/design/catalog.md, sealed-links.md, key-resolvers.md, batch-and-bulk-follow.md Part A, as corrected by 238.
- docs/plan/design/contract-register.md section 3, "Docs" column: the 68 shipped codes no shipped page lists, to be re-derived by re-running E1, E1c, E2 and E1r on this base.
- docs/migration-0.7.md and docs/tools.md: the existing structure, the status table and the unknown-status rule (OD-200-1: "not answered").
- Recorded 2026-10-21 (244 merged): the pages 244 leaves to this task are listed in `PLAN.md`, "Documentation owed by 244": `docs/tools.md`, `docs/audit.md`, `docs/conventions.md:74-77`, `contract-register.md:253`, and `docs/eu-ai-act.md:114-117` and `docs/configuration.md:359-361` (checkpoint failures: `AUDIT_UNAVAILABLE`, not `AUDIT_CHECKPOINT_UNAVAILABLE`, or an owner decision to map it through).
- docs-site/hooks/check_*.py: the documentation checks, including captured examples.
- From 222 external review (2026-10-23): `docs/migration-0.8.md` and its `mkdocs.yml` nav entries (both lists) already exist, created by 222's review round with the Breaking note on `HmacKeyReferenceResolver.resolveProviderReference`. Extend the page; do not recreate it, and keep that section.
- From the external review of PR #154, rounds 1-4 (2026-10-23): `docs/audit.md` must list the new audit values `REFUSED:LINK_ATTEMPTS_LOCKED`, `REFUSED:LINK_ATTEMPTS_OVER_LIMIT`, `REFUSED:LINK_ATTEMPTS_CEILING`, `REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE` and `REFUSED:LINK_ATTEMPTS_OPEN_FAILED` (PR #157, the 269 revision), and `REFUSED:LINK_KEY_UNAVAILABLE`.
- Recorded 2026-10-23 (OD-269-5 (b), decided by the owner): a limiter outage is answered with the new retryable client code `LINK_CHECK_UNAVAILABLE`, and a failure to open the token with `LINK_INVALID`; the audit keeps `REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE` and `REFUSED:LINK_ATTEMPTS_OPEN_FAILED`. `docs/tools.md` (or wherever client codes are documented, beside `LINK_INVALID` and `LINK_ATTEMPTS_EXHAUSTED`) and `docs/audit.md` must list `LINK_CHECK_UNAVAILABLE` as retryable, saying that it reveals nothing about the token, and that `OPEN_FAILED` reaches the client as `LINK_INVALID`.

## Acceptance
- [ ] `docs/tools.md` documents `list_sources` (filters, paging, `page`, `labels`), `describe_source` (the protection vocabulary, `undeclared`, `valueNamespace`, `chainable`), `follow` (input, envelope, `items`, `itemsTruncated`, the uniform `LINK_INVALID`, `LINK_ATTEMPTS_EXHAUSTED`), `targetSources`, `_links` and `_linksOmitted`, the `RESOLVER_FAILED` status, `AUDIT_UNAVAILABLE` on every denial path, and the capabilities `LIST_SOURCES`, `DESCRIBE_SOURCE` and `FOLLOW` with the upgrade note that adding a tool grants nothing. Each example was captured against the real stack and the capture command is in the Outcome.
- [ ] `docs/audit.md` documents the `names` key (`REAL`, `ALIAS`), the `catalog` key and its `REFUSED:CATALOG_<REASON>` values, the `link` key and its `REFUSED:LINK_<REASON>` values, `<target>[<i>]:<refused>`, and that link ids, attempt counts and resolver timings are metrics, not audit fields.
- [ ] S3: every shipped code with "Docs: no" in register section 3, re-derived on this base, is documented on the page for its surface; the Outcome lists each code with its page and heading.
- [ ] `docs/operations.md` (draft, marked so) says, for running more than one instance, that 1.0 supports a trusted network only (`dataprism.hazelcast.trusted-network`, 284), links and page links are refused on joining members (`MISSING_SHARED_LINK_LIMITER`, 255), and readiness carries `clusterSecurity` (286); it links to `docs/multiple-instances.md` (213) for what is and is not sealed, rather than repeating it.
- [ ] `docs/operations.md` (draft, marked so) covers: sizing the read budget and scope total for many sources (the batch design's formula), the request deadline and per-source timeout, reviewing source descriptions (the startup check is a guard rail and the operator is the reviewer), enabling links and the checklist before doing so, that a retired link key must be destroyed in the key store and not only removed from memory (with a pointer to the rotation procedure of 213), and which health and metric signals to watch.
- [ ] `docs/migration-0.8.md` covers, for the core: per-source timeout semantics, per-source budget charging and the scope total, that `dataprism.limits.scope-read-budget` (default 100) now counts source reads (OD-08-3), the new `request-timeout` (`PT10S`) and `scope-total-read-budget` (50,000), the refusal that replaces `SKIPPED_OVER_LIMIT` (as OD-08-4 decided), that `EXPOSE_SOURCE_NAMES` alone no longer shows real names, `D-RM-9`'s refusal for an unselected large estate, the new capabilities, the `json-sources` changes, and that `list_sources` allows every source in 0.8 and will deny unmatched sources from 0.9 (catalog OD-C5). `CHANGELOG.md` `[Unreleased]` lists the same, with the `SKIPPED_OVER_LIMIT` removal under Breaking (OD-08-4).
- [ ] `docs/migration-0.8.md` carries the "for the migration notes" lists from 270's and 272's Outcomes. From 270: the newly refused model shapes, their three codes, the `DataPrismContractValidator` constructor change and 270's fix list, quoted verbatim from 270's "Two kinds of advice" (it is also the text in `docs/extending.md` and the CHANGELOG). Do not use the pre-fix workaround (classifying the holding field), which belongs to the 0.7.x advisory only and does not resolve the 0.8 refusal. From 271: the `MODEL_DESCRIPTOR_RESOLVER_REPLACED` startup refusal: with `dataprism.privacy.descriptor-file` set, only Data Prism's own resolver is accepted, and a custom resolver needs the property unset. From 272: what adapters see on platform threads, the call-slot sizes and the `FAILED`/`UNAVAILABLE` saturation outcome (added 2026-10-23).
- [ ] Wording says "supports", never "compliant" or "compliance". Every example uses the invented logistics domain of the design documents; no real system, organisation or person.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`.

## Out of scope
- CHANGELOG entries for 265, 267, 244, 245, 257 and 268. They shipped in `[0.7.1]` (task 266) and are not repeated under `[Unreleased]`. Their user docs (`docs/tools.md`, `docs/reidentification.md`) are still this task's.
- Key-rotation procedures, the 1.0 controls and their migration sections (213).
- `docs/quickstart.md` (262).
- `docs/data-protection.md` (214).
- Any code, test, or design document.
- Updating the 0.7.x security advisory GHSA-f94h-6mxv-c957 with the patched version: the 0.8.0 release-cut brief, not yet filed, owns it through PLAN.md's release-cut obligations.
