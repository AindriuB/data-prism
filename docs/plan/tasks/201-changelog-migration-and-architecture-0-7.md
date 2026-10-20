# 201 — Write the 0.7 changelog, migration page and architecture decisions

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 188, 189, 190, 193, 194, 195, 196, 202, 209, 216, 217, 232, 233
**Owns:**
- CHANGELOG.md (the `## [Unreleased]` section only)
- docs/migration-0.7.md (new)
- mkdocs.yml (the `nav` and `llmstxt` entries for `migration-0.7.md` only)
- docs/architecture.md ("Decisions worth knowing" only)
- docs/faq.md (pattern-scan behaviour only)
- docs/configuration.md (the two "refusing the whole response" passages at
  `:132` and the `:448` diagram alt text only; and the
  `dataprism.limits.max-response-size` row at `:534` only)
- docs/plan/tasks/201-changelog-migration-and-architecture-0-7.md (Outcome section only)

## Goal
Each 0.7 code task leaves its proposed changelog lines and migration notes in
its Outcome rather than editing `CHANGELOG.md`, so parallel tasks do not
conflict. This task merges them into one `[Unreleased]` section, writes the
0.7 migration page (0.7 is pre-1.0 but the `get_entity_context` output
changes), and records the release's architecture decisions.

## Context
- Outcome sections of tasks 188, 189, 190, 193, 194, 195, 196 (retired files
  under `docs/plan/tasks/retired/` once recorded).
- `CHANGELOG.md:8-16` — the existing `[Unreleased]` `### Build` entries from
  tasks 184/185; keep them.
- `docs/migration-0.6.md` — the page shape (front matter, "Who has to do what" table).
- `mkdocs.yml:121,188` — where `migration-0.6.md` sits in the nav.
- `docs/architecture.md:278` "Decisions worth knowing" — dated entries with
  what was rejected and what it cost. Task 194 already edited the map-key entry.
- `data-prism-integration-tests/src/test/resources/characterisation/get-entity-context.structured.json`
  at the commits before and after task 190, for the before/after excerpt.

## Acceptance
- [ ] Include task 209's pattern-scan wiring fix in the changelog, migration guide and architecture record: it first runs on the Spring path in 0.7, not 1.0. Update `docs/faq.md` to describe the existing scan without claiming the future recogniser feature.
- [ ] `[Unreleased]` has `### Breaking` (the `get_entity_context` response
      shape), `### Added` (`dataprism.limits.*`, verification page),
      `### Changed` (record and size caps now enforced, new refusal codes),
      `### Security` (key-shape scan, signatures, SBOM, provenance) and
      `### Build` (mutation testing, load-test harness) lines, each traceable to
      a task's Outcome; nothing is claimed that no merged task did.
- [ ] `docs/migration-0.7.md` covers, by audience (agent or client author,
      operator, extension author): the response shape change with a before/after
      excerpt taken from the characterisation files, the `REFUSED` status and
      per-source refusal, the rule that an unknown status means "not answered",
      the audit disposition value `REFUSED:<code>`, the new refusal codes and
      what to do on each, the new properties with defaults ("unchanged unless
      you set them"), and how to verify signatures and SBOMs (link to
      `verifying-releases.md`).
- [ ] `docs/architecture.md` gains dated entries for:
      - per-source results never merged (D-070-1);
      - a refused source withheld while the others are returned, the whole
        call refused when every answered source is refused or the final check
        fails (D-070-2), with the cost: the model learns that a source refused;
      - the seven-code status contract and the unknown-code rule (D-070-3);
      - size and record caps refusing the whole call (D-070-5/6);
      - the limits bean and its classification (D-070-4);
      - the key scan always on (D-070-7).
- [ ] `docs/configuration.md:132` and the `:448` alt text describe per-source
      refusal as task 190 implemented it.
- [ ] The `dataprism.limits.max-response-size` row in `docs/configuration.md`
      (`:534`) no longer says "the largest response one request may take from a
      source". Since task 193 it bounds the compact body returned to the
      client, not one source's response, so the row says that.
- [ ] `mkdocs build --strict` exits 0, then run every `docs-site/hooks/check_*.py` with `DP_REQUIRE_NO_STUBS=true`, as CI does. A successful MkDocs build alone does not run every guard.
- [ ] No version number, date or tag is written for 0.7.0; the section stays `[Unreleased]`.

## Out of scope
- Version bumps, `server.json`, tagging, publishing, the release itself.
- Editing any task's code or other docs; a gap found becomes a successor task.

## Outcome

Written from the retired Outcomes of 188, 189, 190, 193, 194, 195, 196, 202, 209, 216, 217, 232 and 233, then checked against the code at `22c1ee6d` (defaults in `LimitsProperties`, the frozen property list diffed against `v0.6.0`, the two new `Metric` constants, `AuditEvent`, `LlmResponseValidator`, the registered `dataPrismSensitivePatternValidator`). Where an Outcome's proposed line was superseded the merged behaviour is written instead: the pending-entry TTL of 216 was reverted (a sweep bounds retention), 216's rolling-upgrade note is replaced by 232's stop-all upgrade, and `dataprism.approval.pending.sweep-seconds` was never released so it has no entry.

- `CHANGELOG.md`: `[Unreleased]` now has Breaking, Added, Changed, Fixed, Security and Build; the three existing Build lines are kept. No version, date or tag.
- `docs/migration-0.7.md`: by audience (agent or client authors, operators, extension authors), with the before/after excerpt from `get-entity-context.structured.json` at `37d5c2a9^` and `37d5c2a9`.
- `docs/architecture.md`: dated entries for D-070-1, -2, -3, -4/-5/-6, -7, task 209 and the Hazelcast approval decisions.
- `docs/configuration.md`: the `:132` FAIL_REQUEST passage, the diagram alt text and the `max-response-size` row only. `docs/faq.md`: the pattern-scan paragraph only.
- `mkdocs.yml`: nav and `llmstxt` entries for `migration-0.7.md`.
- `mkdocs build --strict` exit 0; every `docs-site/hooks/check_*.py` exit 0 with `DP_REQUIRE_NO_STUBS=true`.

Gaps found, not fixed (outside Owns):
- (Struck on review: `docs/multiple-instances.md:373-381` already has the stop-all upgrade caution.)
- 188's five-step release checklist (cosign and attestation verification, SBOM in the bundle) has never been run; the CHANGELOG and migration page describe the pipeline as built.
- The 0.9 migration notes must record OD-B18 (per-source `REFUSED:RESPONSE_TOO_LARGE` replacing the whole-call refusal).

### Review round (coordinator findings, same branch)

- Added the Breaking item for the 0.6 overload `PrivacyCluster.embedded(ClusterMembership, boolean)`, which now applies the default approval quorum whether or not approvals are in use, and corrected "call sites need updating": every 0.6 overload remains.
- Corrected the startup-refusal scope in the CHANGELOG and the migration page: an explicit `approval-minimum-members` below 1, with `none`, or above the counted members, and `INVALID_LOCK_TIMEOUT`, are unconditional; `CLUSTER_SETTINGS_IGNORED` cases stated. Only 0.6 approval entries are unreadable (0.6 had no claim or pending maps). The "After" excerpt keeps source `9SAP0WPN`. D-070-1 to -7 are all dated by the 2026-10-09 owner decision, with a note.
- `LimitsProperties` field Javadoc for `maxResponseSize` now matches `configuration.md` and `RequestLimits`.
- BLOCKED (b): the two converted HTTP example blocks were not re-captured. The Docker daemon is not running here (OrbStack's socket is absent), and I did not start it; the from-source Compose build also needs network pulls. The blocks are still marked "Not re-captured for 0.7". Do it on a machine with the stack before release.

**Release note.** 188's five-step supply-chain verification (its retired Outcome, "Release checklist for 0.7.0") must be run right after publishing and before announcing. If any step fails, correct the CHANGELOG Security and Build lines and the migration page's "Verifying a release" section.
