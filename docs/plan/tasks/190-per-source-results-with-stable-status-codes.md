# 190 — Return per-source results with stable status codes instead of one merged entity tree

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Blocked on owner decisions:** D-070-1 (response keying), D-070-2 (per-source
refusal isolation), D-070-3 (status code set and forward compatibility)
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextResponse.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceResult.java (new, if D-070-1 (b))
- data-prism-orchestration/src/test/**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java
- data-prism-mcp/src/test/**
- data-prism-integration-tests/src/test/**
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceEndToEndTest.java
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java
- data-prism-server/src/test/**
- docs/tools.md
- docs/agents/stdio.md
- docs/agents/remote-http.md
- docs/protect-your-own-api.md
- docs/plan/tasks/190-per-source-results-with-stable-status-codes.md (Outcome section only)

## Goal
`get_entity_context` builds `entity` by `merged.setAll(...)` over every
source's scrubbed tree, so when two sources use the same field name the later
one silently overwrites the earlier. That hides exactly the disagreement the
platform exists to surface, and contradicts the agreed design: per-source
results are never merged, and each carries a stable status code. This task
changes the response so each source's scrubbed record is kept under that
source's key (shape per D-070-1) next to its status, and pins the status
vocabulary as a documented contract.

## Context
- `DefaultContextOrchestrator.java:316-367` `fetchScrubAndMerge`, overwrite at
  `:343`; validators run over `merged` at `:205-218`; the `NO_SOURCE_DATA`
  refusal at `:200-203`; `merged:<refused>` disposition at `:223-226`.
- `ContextResponse.java:56-157` — `sources` (alias to status name), `entity`,
  `@JsonIgnore` bookkeeping, `answered()`, `incomplete()`, `of(...)` building
  aliases through `SourceAliasing`; the ascending-key-order rule and why
  (`:24-29`, task 179).
- `SourceOutcome.java:17-37` — the six statuses.
- `CompareEntitySourcesTool.java:284-325` — `identity(...)` reads the merged
  `entity` by field name; it must read the per-source results instead.
  Recommended default: take each field from the first source, in ascending
  emitted-key order, that holds it, and pin that in a test.
- `data-prism-integration-tests/src/test/resources/characterisation/get-entity-context.{structured,text}.json`
  and `ToolResultCharacterisationTest` — the golden tool output; regenerate,
  never hand-edit, and show the diff in the Outcome.
- `docs/tools.md:101-226` — the `get_entity_context` response table and worked
  example; `docs/agents/*.md` and `docs/protect-your-own-api.md` carry captured
  outputs that must be re-captured, not retyped (task 47's lesson).
- `docs/audit.md:231` documents `merged:<refused>`; the audit format is a 1.0
  freeze surface, so this task keeps the audit record and its disposition keys
  unchanged and does not edit `docs/audit.md`.
- Validators must see each source's payload-derived tree, never the aliases or
  real source names as property keys, so that task 194's key-shape scan cannot
  refuse a structural key. Paths in refusals stay redacted through
  `RefusalPaths.redact`.
- Recommended for D-070-1 (b): `sources` becomes alias to
  `{ "status": "<CODE>", "entity": { ... } }`, `entity` present only for
  `ANSWERED`; the top-level `entity` field is removed.

## Acceptance
Write the tests first; each must fail on `main` before the change.
- [ ] Two stub sources that both return a field with the same name and
      different values: the response carries both values, each under its own
      source's key. Proven non-vacuous by restoring `merged.setAll` and watching
      the test fail (show it in the Outcome).
- [ ] Every source asked appears exactly once with a status from the documented
      set; a test enumerates `SourceOutcome.Status.values()` against a
      checked-in list and fails if a code is added, removed or renamed without
      updating the list and `docs/tools.md`.
- [ ] Ascending emitted-key order of sources is preserved (the task 179 test
      still passes, adapted to the new shape).
- [ ] The privacy behaviour is unchanged: the existing leak, validation-boundary
      (`ValidationBoundaryTest`), undeclared-key and PII log/audit scan tests
      pass; a validator refusal still withholds the whole response and audits
      `DENY:VALIDATION_FAILED` with `merged:<refused>` unless D-070-2 chose
      otherwise, in which case the chosen behaviour is pinned by its own test.
- [ ] Validators receive no source alias and no real source name as a property
      key (a test with a recording validator).
- [ ] `compare_entity_sources` output is unchanged on its existing worked
      example tests; its field selection across sources is pinned by a test.
- [ ] Characterisation JSON regenerated; `docs/tools.md` response table, worked
      example and the status-code list (each code with one line of meaning and
      the forward-compatibility rule from D-070-3) updated; the three captured
      docs re-captured. `mkdocs build --strict` exits 0.
- [ ] `./mvnw -B verify` (full reactor) exits 0; the Outcome records the test
      count and commit.
- [ ] The Outcome carries the CHANGELOG `### Breaking` entry and a migration
      note with a before/after JSON excerpt taken from the regenerated
      characterisation file, for task 201; `CHANGELOG.md` is unchanged.

## Out of scope
- Enforcing `maxRecords` / `maxResponseBytes` (task 193, which edits the same
  orchestrator after this lands).
- `_links`, `follow`, catalog tools, batch, paging, truncation markers (0.8/0.9).
- Changing the fan-out, `SKIPPED_OVER_LIMIT` behaviour or the timeout model (0.8).
- Changing the audit record, `docs/audit.md`, or the hash-chain format.
- Adding new status codes beyond what D-070-3 approves.
