# 190 — Return per-source results with stable status codes, isolating a refused source

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Decided (owner, 2026-10-09):**
- D-070-1 (b): each `sources` entry becomes `{ "status": "<CODE>", "entity": { ... } }`,
  with `entity` present only for `ANSWERED`; the top-level `entity` field is
  removed. Breaking, the one shape break before 1.0.
- D-070-2 (b): a source whose scrub or validation refuses is marked `REFUSED` and
  omitted; the other sources are still returned. If every source that answered
  is refused, the whole call is refused exactly as today.
- D-070-3 (a): the contract is today's six codes plus `REFUSED`; clients treat any
  unknown code as "not answered"; future codes go into task 200's register.
- D-070-13 (a): the audit value for a refused source is `REFUSED:<code>`, the code
  passed through `RefusalCodes.sanitise`. The audit record's fields, the hash-chain
  encoding and the `merged:<refused>` key are unchanged, so existing logs still
  verify (a test pins a log written before the change). Needs `AuditEvent`'s
  disposition check widened to accept `REFUSED:<code>`; see the Owns note below.
- Coordinator decisions (within D-070-13): `AuditEvent.java` (data-prism-core) is in
  Owns and `isValidAction` also accepts `REFUSED:` + `[A-Z][A-Z0-9_]{0,63}` only;
  `REFUSED:<code>` is recorded uniformly for per-source refusals, including the
  single-source all-refused case; `merged:<refused>` stays for the final
  whole-response validation; no legacy `ContextResponse` constructors (tests that
  built the old shape are updated); the scrub code wins for mixed refusals.
  Compatibility: logs written by 0.7 contain `REFUSED:<code>`, which a pre-0.7
  reader or verifier rejects; old logs still verify with the new reader.

**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEvent.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/CorrelationConfigurationTest.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextResponse.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceResult.java (new)
- data-prism-orchestration/src/test/**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java
- data-prism-mcp/src/test/**
- data-prism-integration-tests/src/test/**
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceEndToEndTest.java
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java
- data-prism-server/src/test/**
- docs/tools.md
- docs/audit.md (the `DENY:<code>` row and the per-source disposition description only)
- docs/agents/stdio.md
- docs/agents/remote-http.md
- docs/protect-your-own-api.md
- docs/index.md (the "Fail closed" card only)
- docs/eu-ai-act.md (the `unclassified` / `FAIL_REQUEST` paragraph only)
- docs/developer-guide/write-an-adapter.md (the "refused the whole response" sentence only)
- docs-site/diagrams/fail-closed-decisions.mmd, docs-site/diagrams/entity-context-call.mmd
- docs/assets/diagrams/fail-closed-decisions.svg, docs/assets/diagrams/entity-context-call.svg (re-rendered with `docs-site/diagrams/render.sh`, never hand-edited)
- docs/plan/tasks/190-per-source-results-with-stable-status-codes.md (Outcome section only)

`docs/configuration.md:132` and `:448` (alt text) also say "refusing the whole
response"; task 189 owns that file in this wave, so task 201 corrects them.

## Goal
`get_entity_context` builds `entity` by `merged.setAll(...)` over every
source's scrubbed tree, so when two sources use the same field name the later
one silently overwrites the earlier. That hides exactly the disagreement the
platform exists to surface. This task keeps each source's scrubbed record
under that source's key next to its status, makes one source's privacy refusal
cost only that source, and pins the status vocabulary as a documented contract.

## Context
- `DefaultContextOrchestrator.java:316-367` `fetchScrubAndMerge`, overwrite at
  `:343`; a scrub refusal rethrown at `:330-335` (today it refuses the whole
  call); `prohibited`/`emitted` collected at `:328,:338`; correlation over all
  raw records at `:188-199`; validators over `merged` at `:205-218`;
  `NO_SOURCE_DATA` at `:200-203`; `merged:<refused>` disposition at `:223-226`.
- `ContextResponse.java:56-157` — `sources`, `entity`, `@JsonIgnore`
  bookkeeping (`fieldsByNamespace`), `answered()`, `incomplete()`, `of(...)`;
  the ascending-key-order rule and why (`:24-29`, task 179).
- `SourceOutcome.java:17-37` — the six statuses; `REFUSED` is added.
- `CompareEntitySourcesTool.java:284-325` — `identity(...)` reads the merged
  `entity` by field name; it must read the per-source results instead. Take each
  field from the first `ANSWERED` source, in ascending emitted-key order, that
  holds it, and pin that in a test.
- `docs/audit.md:231` — today's per-source disposition key
  `<source>:<refused>`, recorded with no code. The audit record already lists
  `name:status` per source, so a refused source appears as `name:REFUSED`.
  Recording the refusal reason needs a disposition value carrying the code
  (`REFUSED:<code>`, the code passed through `RefusalCodes.sanitise`). That is
  a change to audit content on a 1.0 freeze surface. Keep the record's fields,
  the hash-chain encoding and the `merged:<refused>` key unchanged.
- `characterisation/get-entity-context.{structured,text}.json` and
  `ToolResultCharacterisationTest`: regenerate, never hand-edit.
- `docs/tools.md:101-226` and the captured outputs in `docs/agents/*.md` and
  `docs/protect-your-own-api.md` must be re-captured, not retyped (task 47).

## Behaviour to build
1. Each answered source is scrubbed, then validated on its own scrubbed tree
   (validators see the payload-derived tree, never an alias or real source name
   as a property key, so task 194's key scan cannot hit a structural key). A scrub
   refusal or a validation violation marks that source `REFUSED` and drops its
   tree.
2. A refused source contributes nothing to the response: no fields, no partial
   tree, no field count, no size hint, no `<undeclared-N>` placeholders, no
   consistency finding and no `fieldsByNamespace` entry. Correlation runs only
   over sources that were not refused. Its raw values stay in `prohibited`. Its
   synthetic values are not added to `emitted`.
3. After assembly, validators run once more over the final response. A
   violation there refuses the whole call as today (`DENY:VALIDATION_FAILED`,
   `merged:<refused>`).
4. If every source that answered is refused, the whole call is refused exactly
   as a single-source refusal is today: the first refused source's code is
   thrown and audited as `DENY:<code>`. If no source answered at all,
   `NO_SOURCE_DATA` as today.
5. When some sources are refused and the call is returned, the audit decision is
   `ALLOW`; each refused source is listed as `name:REFUSED` and has the
   disposition `<source>:<refused>` = `REFUSED:<code>`.

## Acceptance
Write the tests first; each must fail on `main` before the change.
- [ ] Two stub sources return the same field name with different values: the
      response carries both, each under its own source's key. Restoring
      `merged.setAll` makes the test fail (show it in the Outcome).
- [ ] **Refusal isolation.** Three sources, one refusing in scrub (an
      unclassified field under `FAIL_REQUEST`) and, separately, one refusing in
      per-source validation (a synthetic sensitive shape): the response holds
      the other two under `ANSWERED`, and the refused one has status `REFUSED`
      and no `entity` key. A test serialises the response through the real
      `DataPrismObjectMapper` and asserts:
      - none of the refused source's field names or values (raw or
        pseudonymised) appear anywhere in it;
      - no finding names the refused source;
      - the response is byte-identical to a run where that source returned
        `NO_DATA`, apart from its status string.
      Proven non-vacuous: a mutation that keeps the refused source's partial
      tree, or that feeds its raw record to correlation, fails the test (both
      shown in the Outcome).
- [ ] The audit entry for that call is `ALLOW`, lists the refused source as
      `name:REFUSED`, and carries `<source>:<refused>` = `REFUSED:<code>` with
      the sanitised code. `AuditFilePiiScanTest` and `PiiLogScanTest` still pass.
- [ ] All answered sources refused, in scrub and in validation: the call is
      refused with the same code and audit decision as on `main` today. The
      same holds when one source answers and is refused and every other
      source returns `NO_DATA`. Write these tests against `main` first so
      they pin the observed outcome before the change.
- [ ] A violation found only by the final whole-response validation still
      refuses the whole call with `DENY:VALIDATION_FAILED` and `merged:<refused>`
      (`ValidationBoundaryTest` still passes).
- [ ] Validators receive no alias and no real source name as a property key (a
      recording validator).
- [ ] Every source asked appears exactly once with a status from the documented
      set. A test compares `SourceOutcome.Status.values()` with a checked-in list
      of the seven codes and fails on any addition, removal or rename.
- [ ] Ascending emitted-key order is preserved (task 179's test, adapted).
- [ ] `compare_entity_sources` output is unchanged on its existing worked
      examples. Its field selection across sources is pinned by a test, and a
      refused source contributes nothing to `identity`.
- [ ] Characterisation JSON regenerated. `docs/tools.md` updates:
      - the response table and worked example;
      - the seven status codes, one line of meaning each;
      - the rule that an unknown code means "not answered";
      - per-source refusal.
      `docs/audit.md` documents `name:REFUSED` and `REFUSED:<code>`. The
      "whole response" passages and the two diagrams listed in Owns now say:
      a refused source is withheld, and the call is refused when every
      answered source is refused or the final check fails. The captured docs
      are re-captured. `mkdocs build --strict` exits 0.
- [ ] `./mvnw -B verify` (full reactor) exits 0. The Outcome records the test
      count and commit.
- [ ] The Outcome carries proposed text for task 201:
      - a CHANGELOG `### Breaking` entry for the response shape;
      - a `### Changed` entry for per-source refusal and the new `REFUSED` code;
      - a `### Changed` entry for the audit disposition value;
      - a migration note with a before/after JSON excerpt taken from the
        regenerated characterisation file.
      `CHANGELOG.md` is unchanged.
- [ ] **Review gate (privacy boundary):** `reviewer` checks refusal isolation
      explicitly as a privacy boundary, against `docs/architecture.md`
      boundaries 1, 3 and 7 and the project rule "fail closed". The reviewer
      confirms by reading the diff:
      - a refused source's data cannot reach the response, the findings, the
        tool error text or the logs by any path;
      - its values stay prohibited;
      - the final whole-response validation still runs.
      The verdict is recorded in the Outcome.

## Out of scope
- Enforcing `maxRecords` / `maxResponseBytes` (task 193).
- `_links`, `follow`, catalog tools, batch, paging, truncation markers (0.8/0.9).
- The fan-out, `SKIPPED_OVER_LIMIT` or the timeout model (0.8).
- Audit record fields, hash-chain encoding, `docs/configuration.md` (task 201 fixes its wording).
- Any status code beyond the seven.

## Outcome

Implemented on `task/190-per-source-results`. `./mvnw -B -o verify` (full reactor, 20 modules) exits 0:
1741 tests, 0 failures, 0 skipped (sum of the per-module summaries). `mkdocs build --strict` and
the `docs-site/hooks/check_*.py` checks exit 0.

Order of commits: pin tests first (`AllSourcesRefusedPinTest`, run green against the unchanged pipeline,
and a log generated by the unchanged pipeline, `audit/pre-190-plain-refused-chain.jsonl`), then the
red tests (`PerSourceResultsTest`, `SourceStatusCodesTest`, the disposition-shape test), then the change.

### What changed
- `SourceResult` (new): `{status, entity}`; `entity` only for `ANSWERED`. `ContextResponse.sources` is
  `Map<String, SourceResult>`; the top-level `entity` is gone; the legacy constructors are gone (no shim).
- `SourceOutcome.Status.REFUSED` added (seven codes, pinned by `SourceStatusCodesTest` against
  `source-status-codes.txt`).
- `DefaultContextOrchestrator`: scrub each source, then validate each scrubbed tree on its own once every
  source's raw values are known. A refusal in either stage marks the source `REFUSED` and drops it whole:
  no tree, no raw record for correlation, no `fieldsByNamespace` entry, no dispositions or `declared`
  pointers, no synthetic values in `emitted`. Its raw values stay in `prohibited`. A final pass validates
  every returned tree with the union `emitted`; a hit refuses the call (`VALIDATION_FAILED`, `merged:<refused>`).
  No tree left: the first scrub refusal is thrown, else the first validation refusal, else `NO_SOURCE_DATA`
  (the scrub code wins for mixed refusals, as pinned).
- `AuditEvent` (core): disposition values also accept `REFUSED:` + `[A-Z][A-Z0-9_]{0,63}`. Hash encoding
  and record fields unchanged.
- `CompareEntitySourcesTool.identity`: each field from the first `ANSWERED` source, ascending key order, that
  holds it; a refused source has no entity.
- Final validation runs per returned tree (not over one merged tree), so a validator path is
  `$.<payload key>` and no alias or source name is ever a property key a validator sees (pinned by
  `PerSourceValidationTest`).

### Mutation proofs (each run against `PerSourceResultsTest`, then reverted)
- Keep a validation-refused source's tree in the response: fails `refusedSourceIsWithheldAndLeavesNoTrace[validation]`.
- Feed a refused source's raw record to correlation (both scrub and validation refusals): fails
  `refusedSourceIsWithheldAndLeavesNoTrace` for both kinds (findings differ from the `NO_DATA` run).
- Restore `merged.setAll` semantics (every answered source gets the overwritten merge): fails
  `sameFieldNameIsNotOverwritten`.
- Not asserted, by design: the refused source's pseudonym for a name equals the answering sources', because
  a pseudonym is keyed on the subject. The test asserts only pseudonyms exclusive to the refused source.

### Goldens and tests changed, and why
- `get-entity-context.{text,structured}.json`: regenerated with `-Dcharacterisation.record`; the response
  shape is the deliberate break. `compare-entity-sources.*.json` are byte-identical.
- `AuditFieldDispositionTest`: the single-source refusal now records `REFUSED:UNKNOWN_FIELD` (D-070-13).
- `DefaultContextOrchestratorTest.denyRecordsRefusedPath`: every source failed validation, so the keys are
  `<source>:<refused>` = `REFUSED:VALIDATION_FAILED`, not `merged:<refused>`; `merged:<refused>` is pinned by
  `PerSourceValidationTest`.
- Tests that read `response.entity()` or `"entity"` read the per-source entities (`ConfiguredJsonSourceEndToEndTest`,
  `WorkedExampleTest`, `McpHttpEndToEndTest`, `ConfiguredJsonNestedHttpTest`, `QuickstartSmokeIT`, the three
  server operator tests, and the `ContextResponse` constructions in the mcp, orchestration and autoconfigure tests).

### Captures
`docs/tools.md` and `docs/agents/stdio.md` were captured from the real stdio fixture server
(`ExampleApplication`), which reproduced `SUBJ-0VYFHPY9` exactly. The two single-source HTTP captures
(`docs/agents/remote-http.md`, `docs/protect-your-own-api.md`) are the previous real captures converted
mechanically to the new shape; they were NOT re-run against the Compose stack (pseudonyms and aliases do
not depend on the response shape). Re-capture them when the stack is next run.

### Compatibility note for task 201 and `docs/audit.md`
Logs written by 0.7 can contain `REFUSED:<code>` dispositions. A reader or verifier older than 0.7 rejects
such a record (`AuditEvent` validation), so this is forward-incompatible for old readers. Logs written
before 0.7 (plain `REFUSED`) still read and verify with the new reader
(`RefusedDispositionLogCompatibilityTest`, which also checks a mixed log).

### Proposed text for task 201 (`CHANGELOG.md` is unchanged)
```
### Breaking
- `get_entity_context`: each `sources` entry is now `{ "status": "<CODE>", "entity": { ... } }`, with
  `entity` present only for `ANSWERED`, and the top-level `entity` field is removed. Sources are no longer
  merged, so two sources that use the same field name for different values are both returned.
  `ContextResponse.sources()` is `Map<String, SourceResult>`; `ContextResponse.entity()` and the
  constructors that took it are removed.

### Changed
- A source whose scrub or validation refuses is withheld and marked with the new status `REFUSED`; the
  other sources are returned. The call is refused only when every source that answered is refused, or the
  final check over the assembled response fails. The status codes are a documented contract: unknown
  codes mean "not answered".
- Audit: a refused source is recorded as `REFUSED:<code>` under `<source>:<refused>` (was `REFUSED`), and
  is listed as `<source>:REFUSED` among the source systems. Logs written by 0.7 are not readable by
  releases before 0.7; earlier logs still verify.
- `compare_entity_sources`: `identity` takes each field from the first answered source, in ascending key
  order, that holds it.
```
Migration note, from the regenerated `get-entity-context.text.json` (shortened):
```
before: "sources":{"ORGANISATION_IDENTITY-9SAP0WPN":"ANSWERED", ...},"findings":[...],
        "entity":{"customerName":"Devon Doherty (8QDSD50W)","email":"[REDACTED]","status":"ACTIVE",
                  "accountId":"ACC-1","holderName":"Devon Doherty (8QDSD50W)", ...}
after:  "sources":{"ORGANISATION_IDENTITY-J8DXZTGX":{"status":"ANSWERED","entity":{
                  "customerName":"Devon Doherty (8QDSD50W)","email":"[REDACTED]","status":"ACTIVE"}},
                  "ORGANISATION_IDENTITY-PXT33N02":{"status":"ANSWERED","entity":{
                  "accountId":"ACC-1","holderName":"Devon Doherty (8QDSD50W)", ...}}, ...},"findings":[...]
```
Read a status as `sources[alias].status` and a field as `sources[alias].entity.<field>`; treat any status
other than `ANSWERED` as "not answered".

### Review gate
Not run by the implementer. The `reviewer` verdict on refusal isolation (architecture boundaries 1, 3 and 7,
"fail closed") is still to be recorded here.

### Review follow-up (after the tester and privacy review)
- The final pass now validates one JSON array of the returned sources' trees in response order, with the
  union prohibited and emitted sets, so a validator refusing a combination across sources refuses again
  (`PerSourceValidationTest.crossSourceCombinationIsStillRefused`). Source names and aliases are still never
  keys. `LlmResponseValidator`'s Javadoc states the two calls.
- `dataprism.privacy.failclosed` also counts each source withheld from a call that succeeds (no new metric;
  documented in `docs/tools.md`).
- Added tests: a refused source's raw value stays prohibited in either source order; the byte-identity check
  with `NO_DATA` also covers `structuredContent`.
- Docs: the index card, `docs/tools.md` and `docs/audit.md` now say a mixed refusal reports the first
  scrub code, then the first validation code.
- FOLLOW-UP before the 0.7.0 release: re-capture the two single-source HTTP blocks in
  `docs/agents/remote-http.md` and `docs/protect-your-own-api.md` against the Compose quickstart built from
  source (`docker compose -f compose.yaml -f compose.build.yaml up --build`). Both are marked in the docs
  as converted from an earlier 0.6 capture, not re-captured. Not attempted here: the from-source build needs
  network image and dependency pulls.
