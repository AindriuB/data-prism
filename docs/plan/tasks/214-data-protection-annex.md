# 214 — Write docs/data-protection.md, a DPIA-ready control annex

Renumbered from 199 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.9.0 (release-candidate evidence; no new feature)
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main` once 213 has merged
**Depends on:** 213 (cites its anchors in `audit.md`, `key-rotation.md` and `architecture.md`; both edit `mkdocs.yml`)
**Owns:**
- docs/data-protection.md *(new)*
- mkdocs.yml *(one `nav` insertion and one `llmstxt` insertion for `data-protection.md` only)*

## Goal
`docs/design-review.md:272-277` (D1), `docs/development-plan.md:199` and PLAN's S12 item
(`docs/plan/PLAN.md:2839-2841`) all call for `docs/data-protection.md`, and it has never been written.
Write it as an annex a controller can attach to a GDPR Art. 35 DPIA. Map each control Data Prism ships
on `main` to the provisions it supports, and say plainly what stays the operator's duty. This task
absorbs the S12 `data-protection.md` item. The rest of S12 (threat model, mutation and load testing)
stays open.

## Context
- docs/design-review.md:272-277 — pseudonymised output is still personal data; the page must say so first
- docs/use-cases/gdpr-data-minimisation-mcp.md — the citation style to follow: EUR-Lex links, verbatim quotes, "not legal advice"
- docs/eu-ai-act.md — the existing EU AI Act support mapping; link to it rather than repeat it
- docs/audit.md — what the trail records and what it does and does not prove; docs/key-rotation.md — both rotation procedures
- docs/configuration.md — the merged property names and refusal codes from tasks 209 to 212
- docs/architecture.md, "Decisions worth knowing" — the imported decisions and dated reconciliation entries task 213 adds
- docs/plan/roadmap.md and reconciliation-2026-10-19.md — the 0.9 evidence gate and 1.0 stability commitment

## Acceptance
- [ ] The page's sections, in this order:
  1. Scope, and a "not legal advice" statement.
  2. Pseudonymised output is still personal data (GDPR Art. 4(5)).
  3. Art. 25, data protection by design and by default.
  4. Art. 32, security of processing.
  5. Art. 35: what a controller can cite from this page.
  6. Chapter V, international transfers.
  7. Law-enforcement processing: Directive (EU) 2016/680 Art. 25 logging, and its transposition in Part 5 of Ireland's Data Protection Act 2018.
  8. Statutory and sector confidentiality duties.
  9. EU AI Act classification guidance.
  10. Operator duties.
- [ ] Every control row cites either a `path:line` on `main` or a `docs/configuration.md` property. No control is described that is not merged. The rows include, at least: scope-keyed pseudonymisation, profiles including `FREE_TEXT`, the pattern scan and recogniser hook, case authorisation, the client allowlist, purpose validation, the audit trail (unkeyed and keyed), retention and anchors, the sealed-state cluster controls and their residual risks, and the HMAC map/lock keys.
- [ ] The Chapter V section says that Data Prism does not choose, restrict or know the model endpoint a client sends output to. It says that enforcing approved endpoints and transfer destinations belongs to an API gateway or egress control, and that the client allowlist identifies callers and does not control egress.
- [ ] The logging section maps each element of the logging duty (operation type, date and time, who consulted or disclosed, justification, recipients) to the audit fields that carry it, or states that no field does. It quotes the Part 5 section that transposes Art. 25 verbatim, with the section number checked on irishstatutebook.ie.
- [ ] The confidentiality section maps such duties to case authorisation, the client allowlist, purpose validation and the audit trail. It names no particular statute, sector body or organisation.
- [ ] The EU AI Act section gives general classification guidance only: how a deployer works out whether its use falls under Art. 6 and Annex III, and what Data Prism does and does not supply towards Arts. 10, 12 and 26, linking `eu-ai-act.md`. It is headed or boxed **"For counsel: classification is not determined here."**
- [ ] The audit rows repeat, without softening, that an unkeyed chain does not resist the operator, that a keyed chain does not resist a key holder, and that truncation is detected only against checkpoints held under separate custody.
- [ ] The operator-duties section lists, at least: lawful basis; the DPIA itself; transfer mechanisms; supplying a `CaseAuthorizer` rather than `token-trusted`; transport protection and isolating the cluster port; custody and rotation of the pseudonymisation, audit and cluster keys; checkpoint custody; the retention period; and recogniser quality.
- [ ] Every legal citation links to EUR-Lex or irishstatutebook.ie, and every quotation is verbatim from that source.
- [ ] The page says "supports" and never "compliant" or "compliance".
- [ ] `mkdocs build --strict` passes, then every `docs-site/hooks/check_*.py` passes with `DP_REQUIRE_NO_STUBS=true`.

## Out of scope
- Any conclusion on AI Act risk classification, or any statement that a deployment meets a legal duty.
- `docs/threat-model.md` and the rest of S12.
- Every other file under `docs/` (task 213), and `docs/plan/**`.
