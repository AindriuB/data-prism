# 201 — Write the 0.7 changelog, migration page and architecture decisions

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 188, 189, 190, 193, 194, 195, 196, 202, 209, 216, 217, 232
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
