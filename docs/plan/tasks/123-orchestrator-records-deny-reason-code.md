# 123 — Record the refusal code in the orchestrator's policyDecision as `DENY:<code>`

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** 118, 121
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the DENY `audit(...)` call in `buildContext`'s catch block, and one private helper that derives the decision string, only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/AuditedRefusalException.java *(Javadoc only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/DenyDecisionCodeTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestratorTest.java *(expected `policyDecision` values only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/UndeclaredKeyRefusalPathTest.java *(expected `policyDecision` values only)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/OrchestratorRefusalCorrelationTest.java *(expected `policyDecision` values only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/boundary/ValidationBoundaryTest.java *(expected `policyDecision` values only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java *(expected `policyDecision` values only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFieldDispositionTest.java *(the DENY filter at `~:168` only)*
- docs/audit.md *(the `### policyDecision values` subsection that task 121 adds, only)*
- docs/tools.md *(the paragraph beginning "Every tool result that the audit trail records carries that call's `correlationId`", currently `~:456-469`, only)*

## Goal

The orchestrator writes a plain `DENY` for every refusal and for every
audited internal failure, so the audit trail cannot say why a call was
refused; only the MCP client sees the code. This task makes the orchestrator
write `DENY:<code>`, the form `ReidentificationService` already uses, so an
operator can tell a budget refusal from a validation failure from an internal
error without the client's copy of the result.

## Context

- `DefaultContextOrchestrator.java:~208-229` (pre-118 numbering): the catch
  block writes `"DENY"` for both a `PrivacyRefusedException` and any other
  `RuntimeException`. Refusal codes thrown today: `SCOPE_READ_BUDGET`
  (`:~171`, before any fetch), `NO_SOURCE_DATA`, `VALIDATION_FAILED`, and
  whatever a scrubbing engine throws (`UNKNOWN_FIELD`, `UNDECLARED_FIELD`,
  `UNCLASSIFIED_STRUCTURE`, `TOO_DEEP`, and the configured-source codes).
- `AuditedRefusalException.REQUEST_FAILED` is the code already given to the
  caller for an audited internal failure. Use the same constant.
- `ReidentificationService.java:~212,~224` writes `"DENY:" + code`. Match it
  exactly: upper-case prefix, one colon, no spaces.
- A `PrivacyRefusedException` can come from an application-supplied
  `ScrubbingEngine` or validator, so its code is not guaranteed to be a
  constant. The audit file is operator-facing and must not take an arbitrary
  string from a third-party exception. Fail closed: a code that does not
  match `[A-Z][A-Z0-9_]{0,63}` is recorded as `DENY:INVALID_REFUSAL_CODE`.
- `policyDecision` is a hashed field. Changing the value written changes only
  the input to new records' hashes; the encoding is untouched, and the
  verifier recomputes from the stored value. Records written by 0.3.x carry a
  plain `DENY` and still verify. Record version 2 is unreleased, so no
  compatibility shim, flag or dual-write is needed, and none is to be added.
- Task 112's planned `event.outcome` rule is `ALLOW` or `ALLOW:*` = success,
  empty = unknown, anything else = failure. `DENY:<code>` is already a
  failure under it and 112 already lists `DENY:<code>`, so task 112 needs no
  change. Confirm in the close-out by quoting 112's acceptance line.
- Consumers of an exact `"DENY"` found while planning (re-run
  `grep -rIn 'DENY' . --exclude-dir=target --exclude-dir=.git` after 118 and
  121 merge and list every hit in the close-out with its disposition):
  - `DefaultContextOrchestratorTest.java:332,367`;
  - `OrchestratorRefusalCorrelationTest.java:139`;
  - `ValidationBoundaryTest.java:60`;
  - `EndToEndTest.java:155`;
  - `AuditFieldDispositionTest.java:168`, a filter that would silently match
    nothing after this change;
  - task 118's `UndeclaredKeyRefusalPathTest.java:~118`;
  - task 118's `AuditFilePiiScanTest` addition uses `contains("DENY")`, which
    still holds. Leave it.
  - No file under `examples/` and no script tests the value.
  - `docs/architecture.md:163`, `docs/conventions.md:168`,
    `docs-site/diagrams/README.md:126` use "DENY" as a word, not a value test.
    Leave them.
- The MCP tools' own refusals write a bare code (`TOOL_NOT_PERMITTED`,
  admission codes). They do not change here.
- `docs/conventions.md#documentation`: "supports", never "compliant" or
  "tamper-proof".

## Acceptance

- [ ] For a `PrivacyRefusedException` caught in `buildContext`, the DENY
      event's `policyDecision` is exactly `"DENY:" + code()`. For any other
      `RuntimeException` it is exactly `DENY:REQUEST_FAILED`, built from
      `AuditedRefusalException.REQUEST_FAILED`. The orchestrator never writes
      a plain `DENY`: `grep -n '"DENY"' DefaultContextOrchestrator.java`
      returns nothing.
- [ ] A refusal whose code does not match `[A-Z][A-Z0-9_]{0,63}` (tested with
      `"bad code"` and with a 65-character code) is recorded as
      `DENY:INVALID_REFUSAL_CODE`, and the exception thrown to the caller is
      unchanged from today.
- [ ] `DenyDecisionCodeTest` has one test per case, each asserting the exact
      recorded value through a recording `AuditSink`:
      `DENY:SCOPE_READ_BUDGET`, `DENY:NO_SOURCE_DATA`,
      `DENY:VALIDATION_FAILED`, a scrub refusal (`DENY:UNKNOWN_FIELD`),
      `DENY:REQUEST_FAILED` for a scrubber that throws
      `IllegalStateException`, and `DENY:INVALID_REFUSAL_CODE`.
- [ ] Each existing test listed under Context asserts the new exact value
      (for example `DENY:VALIDATION_FAILED`), not a prefix. No assertion is
      loosened to `startsWith("DENY")`. Only expected values change in those
      files.
- [ ] The dispositions, `correlationId` and every other field of the DENY
      event are unchanged. Existing disposition assertions pass unchanged.
- [ ] `docs/audit.md`'s `policyDecision values` table:
      - the orchestrator row is `DENY:<code>`, with examples
        `DENY:SCOPE_READ_BUDGET` and `DENY:REQUEST_FAILED`, and says an
        internal failure is `DENY:REQUEST_FAILED`;
      - the statement that the orchestrator's record does not carry the
        refusal code is removed;
      - a plain `DENY` row remains, marked as written by releases before
        0.4.0 and never written by 0.4.0;
      - the classification rule is unchanged.
- [ ] The `docs/tools.md` paragraph names the DENY record as `DENY:<code>`.
- [ ] `grep -niE 'compliant|tamper-proof'` on the changed doc lines returns
      nothing, and `mkdocs build --strict` exits 0.
- [ ] The close-out states that no hash compatibility shim was added and
      why, and gives a one-line release note for the scribe (no edit to
      `CHANGELOG.md`).
- [ ] `mvn verify` over the full reactor passes; the close-out reports the
      real exit code.

## Out of scope

- The MCP tools' bare refusal codes. They stay bare (see the owner decision
  noted with this task).
- `ReidentificationService`, which task 120 owns while in flight.
- Disposition keys (`<source>:<refused>`, `merged:<refused>`). Task 118 and
  task 96 fixed them; they do not change.
- Any `AuditEventHash`, record-format or verifier change (task 117).
- `CHANGELOG.md`. The scribe adds the release note.
- Task 112's `event.outcome` rule and its task file.

## Owner decisions (2026-10-06)

- Unify in 0.4.0: the MCP tools' own refusals (deny() and denyUnauthenticated()
  in GetEntityContextTool and CompareEntitySourcesTool, and ToolCalls) also
  record `DENY:<code>` instead of a bare code, so v2 ships with one denial
  form. Owns is extended to those MCP files. 123 already depends on 118, which
  owns data-prism-mcp/**. Update the tests that assert bare codes and the
  docs/audit.md policyDecision table. Drop the "bare <CODE>" row, or mark it
  pre-0.4.0 only.
- A malformed code is recorded as `DENY:INVALID_REFUSAL_CODE`, as the planner proposed.
