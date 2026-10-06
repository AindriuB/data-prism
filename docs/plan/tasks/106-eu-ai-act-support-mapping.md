# 106 — Document EU AI Act support and record the architecture changes

**Repo:** `.`
**Depends on:** 94, 105
**Owns:**
- docs/eu-ai-act.md *(new)*
- mkdocs.yml *(the nav entry and its llms-txt mirror only)*
- docs/architecture.md *(boundary 5, new dated decision entries, and the `reidentification` module-table row only)*
- docs/audit.md *(a new "Joining to your AI-system logs" section; the "Single file, no rotation" bullet; the External checkpoints configuration sentence only)*
- docs/configuration.md *(the operator settings row near line 80, the operator endpoints sentence near line 258, and the operator property/code tables near line 320 only; added after task 105)*
- docs/reidentification.md *(the Errors list at lines 89-94 only; added after task 105)*

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

## Added after task 105 merged (2026-10-06)

Task 105's own Owns did not cover these two doc gaps, so they come here. Owns
above was extended with `docs/configuration.md` and `docs/reidentification.md`
(neither was covered before).

- `docs/configuration.md:80` must list `dataprism.operator.address`, and the
  refusal codes `OPERATOR_AUDIENCE_SHARED`, `INVALID_OPERATOR_ADDRESS` and
  `REIDENTIFICATION_MODULE_MISSING`.
- The Errors list at `docs/reidentification.md:89-94` must name
  `UNAUTHENTICATED` (401) and `FORBIDDEN` (403).

## Attempt 1 — failed

Tester: PASS (`mkdocs build --strict`, `check_site.py`, docs-only diff).
Reviewer: CHANGES. All other material claims were checked against the code and
hold, and the article references are correct. The S10 entry extension,
configuration.md:320-328 and the two mkdocs.yml lines are accepted.

Owns widened for attempt 2 (accepted after the fact, or added now):
- docs/architecture.md:48, the `reidentification` module-table row ("the same process"). Keep this edit.
- docs/configuration.md:320-328, the operator property and code tables.
- docs/audit.md, the "Single file, no rotation" bullet (around lines 38-42) and the External checkpoints "this release adds no configuration" sentence (around line 443).
- docs/configuration.md:258-260, the "operator endpoints … are not described here" sentence.

Required for attempt 2:
1. **Overstated claim (blocker).** docs/eu-ai-act.md:133-135 says unclassified fields always refuse (FAIL_REQUEST) and special-category values never reach the model. That holds only for the default and the bundled DEFAULT and STRICT profiles. A deployer profile can set `unclassified` to REDACT_AND_WARN, DROP_AND_WARN or the UNSAFE pass-through (PrivacyProfile.java:46-90). Qualify both sentences. In the responsibility paragraph, name choosing a weaker `unclassified` handling, especially UNSAFE, as the deployer's decision.
2. **Stale text that is false for 0.4.0.** Fix it so it agrees with eu-ai-act.md and the new architecture entry:
   - audit.md "Single file, no rotation" / "Rotation, retention … this release does not build": describe daily segments, retention and checkpoints, and link to configuration#segmented-files-checkpoints-and-retention. Keep anything that is still true of FileAuditSink.
   - audit.md:443: `dataprism.audit.checkpoint.interval` (PT5M) exists. Say so.
   - configuration.md:258-260: the operator endpoints exist. Link to reidentification.md's HTTP section.
3. Name AUDIT_CHECKPOINT_UNAVAILABLE (D7: audited calls are refused while a checkpoint cannot be written) in the "Failing closed" paragraph (eu-ai-act.md:106-108) or in the Art. 12 section, as an availability trade the deployer must plan for.
4. docs/audit.md:385: "the model never sees it" → "Data Prism never puts it in model-visible content" (an MCP client may forward `_meta`).

Leave reidentification.md:152-153 (Docker Compose "not yet done") alone. It is still true and is a known 0.4.x item.

## Attempt 2 — failed

Tester: PASS. Reviewer: CHANGES (head 22f4f63). Items 2b, 2c, 3 and 4 are approved.
Required for attempt 3, all inside the existing Owns:

1. **Wrong value name.** docs/eu-ai-act.md:139-140 and 163: the enum constant is
   `PASS_THROUGH_UNSAFE` (PrivacyProfile.java:95), and `unclassified: UNSAFE` fails to
   load. Write `PASS_THROUGH_UNSAFE` everywhere.
2. **Where weaker `unclassified` handling is possible.** The starter and server
   always load the bundled `privacy-profiles-default.yaml`
   (DataPrismAutoConfiguration.java:384-388) and refuse an application resolver
   bean. A weaker `unclassified` is therefore possible only when assembling the
   core library directly via `PrivacyProfiles.fromYaml`. State this precisely in
   eu-ai-act.md:136-144 and in the responsibility paragraph; verify it in code first.
3. **False checkpoint claim.** docs/audit.md:39-42 says checkpoints come from directory
   mode. `file-path` mode plus `dataprism.audit.checkpoint.file-path` also
   writes BOOT, PERIODIC and SHUTDOWN checkpoints
   (DataPrismAutoConfiguration.java:440-449; DataPrismProperties.java:323-330). Only
   daily segments and retention are directory-only.
4. docs/audit.md:465: say outright that the schedule runs only when a
   checkpoint location is configured (DataPrismAutoConfiguration.java:478-480).
5. Re-wrap docs/audit.md:391-392 to the file's line width.

## Attempt 3 — failed

Tester: PASS. Reviewer: CHANGES (head 6bbd496). Attempt-2 items 1-5 are met and there are no regressions.
Required for attempt 4. This is wording only, so change nothing else:
1. docs/audit.md:472: "without one nothing checkpoints" is false. An
   application-supplied `AuditCheckpointSink` bean with no `checkpoint.file-path` still
   gets BOOT and SHUTDOWN checkpoints; only PERIODIC is skipped. Change it to
   "without one no PERIODIC checkpoint is written".
2. docs/eu-ai-act.md:142-143: `PrivacyProfile` is a public record, so a profile
   built in Java can also carry `PASS_THROUGH_UNSAFE`. Change it to "assemble the core library
   yourself (for example with `PrivacyProfiles.fromYaml`)".
3. Re-wrap docs/eu-ai-act.md:147 and :170-171 to the paragraph width.
