# 200 — Design: one register of every tool, code and key planned up to the 0.9 freeze

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 189, 190, 191, 192, 193, 197, 198, 202, 216, 232
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/contract-register.md (new)
- docs/plan/tasks/200-design-contract-register.md (Outcome section only)

## Goal
The surface freezes at the 0.9.0 release: public API, configuration properties,
refusal codes, status codes, MCP tool schemas and the audit format. The 0.7
design set proposes names across four documents and the 0.7 code adds more.
This task gathers every one into a single register, reconciles collisions and
naming inconsistencies, and marks which release introduces each, so the freeze
checklist exists before 0.8 starts building against it.

## Context
- Closing tables of `docs/plan/design/sealed-links.md`, `key-resolvers.md`
  (191), `cluster-security.md` (192), `catalog.md` (197),
  `batch-and-bulk-follow.md` (198).
- Shipped surface: `docs/tools.md` (tools, refusal and admission codes,
  status codes from 190, size codes from 193), `docs/audit.md` (decision forms,
  fields), `docs/configuration.md` (keys including `dataprism.limits.*` from
  189), `frozen-property-names.txt`, `RefusalCodes` format.
- D-RM-13: `compare_entity_sources` is kept and reviewed before the freeze.
- D-070-3 (a): the status contract is seven codes (today's six plus `REFUSED`),
  clients treat an unknown code as "not answered", and future codes are
  registered here.
- D-070-12 (a): this register is internal (`docs/plan/design/`).

## Required content
1. One table per kind (tools and their arguments, status codes, refusal codes,
   audit fields and decision forms, configuration keys, SPI types): name,
   state (shipped / proposed), introducing release, source document, owner decision ref.
2. Collisions and near-collisions found, and the proposed resolution of each
   (as an owner decision where it is a product choice).
3. Naming rules the register applies (code format, key casing, tool naming),
   and every entry that breaks one.
4. The freeze checklist for 0.9.0: what must exist, be documented and be pinned
   by a test (for example a frozen list) for each kind.

## Acceptance
- [ ] Include tasks 203, 206–208, 210–214 and the cluster design as proposed pre-freeze contracts, with release and decision status from `docs/plan/reconciliation-2026-10-19.md`. Proposals are not shipped APIs. Include task 209's merged bean behaviour when available; the register does not wait for 0.8 implementations.
- [ ] Register the refusal code that task 216 introduces for Hazelcast lock, index or entry-processor failures during approval creation (`CLUSTER_OPERATION_REFUSED`, or the name recorded in 216's Outcome), with its meaning and where it surfaces.
- [ ] Every name in the five design documents' closing tables, and every
      shipped tool, status code, refusal code and `dataprism.*` key, appears in
      the register; the Outcome shows the extraction commands and counts.
- [ ] No two entries share a name with different meanings, or each such pair
      is listed under item 2 with a resolution.
- [ ] **Review gate:** `reviewer` returns APPROVE after re-running the
      extraction independently and checking the register against D-RM-1 to
      D-RM-15 and `docs/architecture.md` boundaries. Verdict in the Outcome.

## Out of scope
- Editing the design documents or shipped docs; inconsistencies become owner
  decisions or successor tasks named in the Outcome.
