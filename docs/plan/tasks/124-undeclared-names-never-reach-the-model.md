# 124 — Keep undeclared property names out of successful tool results

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** 118
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/JsonTreeScrubbingEngine.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/PrivacyProfile.java *(Javadoc of `UnclassifiedBehaviour` only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/UndeclaredPropertyNameTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/NestedScrubbingTest.java *(expected output property names only, if any change)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/ScrubDispositionsTest.java *(expected output property names only, if any change)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonUndeclaredKeyRefusalTest.java *(added cases only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/UndeclaredKeyFixture.java *(additive only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/UndeclaredNameToolResultScanTest.java *(new)*
- docs/extending.md *(a new `### Profiles that admit unclassified data` subsection, inserted immediately before `### What the processor actually rejects — proven by compiling`, only)*
- docs/tools.md *(the `entity` row of the `get_entity_context` result table, currently `:120`, only)*

## Goal

A profile whose `unclassified` setting admits unknown properties redacts or
drops an undeclared property's value, but `scrubObject` still writes the
payload's own key into the output tree. A key can be personal data, for
example `jane.doe@example.com`, and it then reaches the model in a successful
result. Task 118 stopped such keys reaching refusals, logs and the audit
trail; this task stops them reaching the model's answer, fail-closed under
every setting except the one spelled `UNSAFE`.

## Context

- `JsonTreeScrubbingEngine.scrubObject` (`:~153-188`, pre-118 numbering):
  `out.set(field, scrubbed)` uses the raw key even when `unknownProperty` is
  true. Configured JSON sources are scrubbed by the same class
  (`ConfiguredJsonScrubbingEngine` builds one per source), so one fix covers
  both kinds of source.
- `ProfilePrivacyPolicyResolver.unclassified`: `REDACT_AND_WARN` gives
  `REDACT`, `DROP_AND_WARN` gives `REMOVE`, `PASS_THROUGH_UNSAFE` gives
  `PASS_THROUGH`. `apply(...)` returns `null` for `REMOVE`, so
  `DROP_AND_WARN` most likely already omits the property. Confirm with a
  test before changing anything for it.
- Only properties for which `unknownProperty` is true are renamed. A field
  declared on the model (or in the configured catalogue) keeps its name even
  when unannotated: its name is reviewed code, not payload. A declared field
  that holds an undescendable structure (`NestedScrubbingTest`'s `details`)
  is not affected.
- Placeholder order. A source's map serialisation order is not stable across
  JVMs (`Map.of`, `HashMap`), so "payload order" is not deterministic. Number
  the undeclared properties of one object by `String.compareTo` over their raw
  keys: `<undeclared-1>` is the smallest. The output keeps each property's
  position. Numbering restarts at 1 in each object, including nested ones.
- Disposition keys stay as tasks 93 and 118 fixed them: every undeclared
  property's pointer segment is `<undeclared>`, never `<undeclared-N>`. The
  numbered form appears only in the output tree.
- Task 118's `RefusalPaths` renders any path segment that is not a declared
  pointer as `<undeclared>`. A validator walking the renamed tree will report
  `<undeclared-N>` segments, which are not declared, so the refusal path still
  shows `<undeclared>`. 118's tests must pass unchanged.
- `DefaultContextOrchestrator.fetchScrubAndMerge` merges sources with
  `merged.setAll(...)`, a shallow overwrite. Two sources each with
  `<undeclared-1>` collapse to one, exactly as two sources with the same
  declared key do today. Both values are `[REDACTED]`, so nothing is lost
  that the model could have read. Not changed here.
- Validators scan values only, never property names
  (`SensitiveDataScanner.java:99-100`, `RawValueLeakValidator.java:57-58`).
  Under `PASS_THROUGH_UNSAFE` an email-shaped key therefore reaches the model
  with no check. This is documented, not changed (see owner decision).
- Task 118's `UndeclaredKeyFixture.Open` repeats a classified value under the
  undeclared key, so a pass-through run refuses with `VALIDATION_FAILED`.
  This task needs a success path: add a second adapter whose undeclared
  value is benign and synthetic, with a new distinct key
  `zzResultKeyWv4@example.com`.
- Interactions checked while planning:
  - tasks 102 and 103 (audit segments, checkpoints, retention) store audit
    events only. The output tree never enters the audit record, so there is
    no interaction.
  - task 112 (JSON projection) renders audit events only. No interaction.
    It owns `PiiLogScanTest`, which this task does not edit.
  - task 114 owns `PiiLogScanTest` and `AuditFilePiiScanTest`. This task
    puts its scan in a new file to stay out of both.
- `docs/conventions.md#documentation`: "supports", never "compliant".

## Acceptance

- [ ] Under `REDACT_AND_WARN`, an object with undeclared properties
      `zeta@example.com`, `alpha@example.com` and `mid` is emitted with keys
      `<undeclared-3>`, `<undeclared-1>` and `<undeclared-2>` in the
      input's positions, each valued `[REDACTED]`. Declared fields keep their
      names and positions. A nested descendable object numbers its own
      undeclared properties from 1. `UndeclaredPropertyNameTest` asserts all
      of this and that the same input scrubbed twice, with keys inserted in
      different orders, gives the same name-to-placeholder assignment.
- [ ] If a placeholder would equal a declared property name present in the
      same object, the engine uses the next free number. No output property
      is overwritten. A test with a resolver declaring `<undeclared-1>`
      asserts both values survive.
- [ ] Under `DROP_AND_WARN`, the output has no property for any undeclared
      key and no placeholder. A test pins this. The close-out says whether
      code had to change for it.
- [ ] Under `PASS_THROUGH_UNSAFE`, the output is byte-identical to before
      this task, raw undeclared key included. A test pins the key's presence
      so that the documented behaviour is checked.
- [ ] Under `FAIL_REQUEST`, behaviour is unchanged (`UNKNOWN_FIELD`).
- [ ] `ScrubResult.dispositions()` is unchanged for every case above: one
      `<undeclared>` segment, no `<undeclared-N>` key. A test asserts it.
- [ ] `ConfiguredJsonUndeclaredKeyRefusalTest` gains one case: a configured
      source under `REDACT_AND_WARN` with an undeclared body key emits
      `<undeclared-1>`, not the key.
- [ ] `UndeclaredNameToolResultScanTest` runs both tools end to end through
      `DataPrismAssembly`, with a recording audit sink and a captured log,
      against the new adapter, once per `UnclassifiedBehaviour`. For each run
      it asserts on the tool result's `structuredContent` (serialised) and on
      each `TextContent`:
      - `FAIL_REQUEST`, `REDACT_AND_WARN`, `DROP_AND_WARN`: the token
        `zzResultKeyWv4` appears in none of the result, the captured log or
        any audit event field. The two `_AND_WARN` runs are successful
        results, not errors.
      - `REDACT_AND_WARN`: the result contains `<undeclared-1>`.
      - `PASS_THROUGH_UNSAFE`: the result does contain the token (pinned
        unsafe behaviour); the captured log and audit events do not.
      - a not-vacuous case: the same scan reports the token when it is
        planted in a result.
- [ ] `PrivacyProfile.UnclassifiedBehaviour` Javadoc: `REDACT_AND_WARN` says
      an undeclared property is renamed to a numbered placeholder;
      `PASS_THROUGH_UNSAFE` says undeclared property names pass through
      unchanged as well as values.
- [ ] `docs/extending.md`'s new subsection lists the four `unclassified`
      settings and, for each, what happens to an undeclared property's name
      and value. It states plainly that under `PASS_THROUGH_UNSAFE` property
      names from the source payload reach the model unchanged and that no
      validator checks names. It notes that no `dataprism.*` property selects
      such a profile; it is built in Java. The `docs/tools.md` `entity` row
      says that under a profile that admits undeclared properties their names
      are replaced by `<undeclared-N>` or dropped.
- [ ] `mkdocs build --strict` exits 0; `grep -niE 'compliant|tamper-proof'`
      on changed doc lines returns nothing. Fixture keys are synthetic and at
      `example.com`.
- [ ] `mvn verify` over the full reactor passes; the close-out reports the
      real exit code and gives a one-line release note for the scribe.

## Out of scope

- Scanning property names in the validators. Logged as an owner decision,
  not built here.
- Renaming under `PASS_THROUGH_UNSAFE`.
- Disposition keys, refusal paths and the WARN log line (task 118).
- `DefaultContextOrchestrator`, including the shallow merge (tasks 118, 123).
- `PiiLogScanTest` and `AuditFilePiiScanTest` (tasks 112, 114).
- `CHANGELOG.md`, `docs/configuration.md`.

## Owner decisions (2026-10-06)

- Placeholders are numbered alphabetically by raw key (deterministic), as proposed.
- Scanning property names in the leak validators is a 0.4.x follow-up, not this task.
  Document the gap under PASS_THROUGH_UNSAFE.
