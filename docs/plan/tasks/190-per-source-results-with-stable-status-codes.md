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
