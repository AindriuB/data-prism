# 106 — Document EU AI Act support and record the architecture changes

**Repo:** `.`
**Depends on:** 94, 105
**Owns:**
- docs/eu-ai-act.md *(new)*
- mkdocs.yml *(one nav entry only)*
- docs/architecture.md *(boundary 5, and new dated decision entries only)*
- docs/audit.md *(a new "Joining to your AI-system logs" section only)*

## Goal

Write down, article by article, what Data Prism now supports for a deployer
under Regulation (EU) 2024/1689 and GDPR Art. 9, what it does not do, and
what remains the deployer's job. The page uses "supports" throughout and
never "compliant". The task also brings `docs/architecture.md` up to date:
boundary 5 is now mechanically enforced, and two dated decision entries are
added. One lifts the S10 deferral. The other reverses the v0.3.0 no-rotation
choice. Both decisions are the owner's own, and this task only transcribes
them.

## Context

- Tasks 92-105, as merged. Each task's own doc section (`audit.md`,
  `tools.md`, `configuration.md`, `reidentification.md`, `extending.md`) is
  linked from the new page, not repeated in it.
- `docs/architecture.md:170-172` — boundary 5, prose only. Tasks 100 and 105
  add the enforcing tests (`ArchitectureTest` rules and the operator-port
  tests).
- `docs/architecture.md:303-307` — the 2026-09-08 decision to defer the
  operator surface. The new entry supersedes it and cites that entry rather
  than editing it.
- `docs/faq.md` (task 77) — the existing position that output is not
  anonymous under GDPR Art. 4(5). Stay consistent with it.
- `docs-site/page-meta.yml:8-10` — new pages carry their own front matter.

## Acceptance

- [ ] `docs/eu-ai-act.md` has one section per item below. Each section names
      the feature, links its reference doc, and has a "What remains the
      deployer's responsibility" paragraph.
      - Art. 12, Art. 19 and Art. 26(6): field dispositions, checkpoints,
        retention, and correlationId as the join key.
      - Art. 14 and Art. 26(1)-(2): pause, approvals, per-caller limits and
        re-identification.
      - Art. 5(1)(g), Art. 10(5) and GDPR Art. 9: the special categories.
- [ ] The Art. 10(5) section states that no bias-detection profile ships, and
      why.
- [ ] `grep -niE '\bcompliant\b|\bcompliance with\b|guarantee' docs/eu-ai-act.md`
      returns nothing.
- [ ] The page states that Data Prism is a component, not an AI system's
      complete record-keeping or oversight solution, and that it does not
      classify the deployer's system's risk level.
- [ ] `docs/architecture.md` boundary 5 is marked **Enforced** and names the
      `ArchitectureTest` rules from task 100 and the operator-port tests from
      task 105. Two new dated decision entries exist. Each cites the entry it
      supersedes and states what was rejected and what it costs.
- [ ] `docs/audit.md` gains a section explaining that `correlationId` is
      returned in each tool result's `_meta` and can be joined to the
      matching audit record.
- [ ] `mkdocs build --strict` passes, and
      `python3 docs-site/hooks/check_site.py` passes.

## Out of scope

- `README.md`, `server.json` and the site landing page.
- `docs/plan/PLAN.md` and `HISTORY.md`, which belong to scribe.
- Legal advice. The page says it is not legal advice.
- A keyed-chain decision entry. Task 107 was dropped (D2, 2026-10-06); the
  2026-09-23 decision stands. The mapping must not claim the chain resists an
  operator; tamper evidence is the unkeyed chain plus external checkpoints
  held under separate custody.
