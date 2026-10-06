# 128 — Validate refusal codes before they reach the MCP client

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/RefusalCodes.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/RefusalCodesTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the `REFUSAL_CODE` pattern and `denyDecision` only)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ToolCalls.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/MalformedRefusalCodeTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ToolCallsDenyDecisionTest.java
- data-prism-mcp/pom.xml *(test-scope dependencies only, if log capture needs one)*
- docs/tools.md *(one sentence under the refusal / `policyDecision` text only)*

## Goal

Refusal codes from application-supplied scrubbers, validators, authorisation services and
admission decisions are copied into the MCP tool result unchecked, so a code can carry arbitrary
text, including personal data, to the model. The audit already accepts only
`[A-Z][A-Z0-9_]{0,63}` and records `INVALID_REFUSAL_CODE` otherwise. This task applies the same
rule to every client-facing refusal text, through one shared helper that the audit paths also use.

## Context

- `ToolCalls.java:38,80-82` — the MCP copy of the pattern and `denyDecision`.
- `ToolCalls.java:85-89` — `refusalText(code, approvalId)`.
- `ToolCalls.java:131-138` — `refused(AuditedRefusalException)` writes `refused: <code> at <path>`.
- `GetEntityContextTool.java:191,198,208,238` and `CompareEntitySourcesTool.java:191,198,208,239` —
  `decision.denialCode()`, `SecurityRefusedException.code()`, `admitted.code()` and
  `PrivacyRefusedException.code()` reach `deny(...)` / `error(...)` and so the client text.
- `DefaultContextOrchestrator.java:272-288` — the orchestration copy of the pattern.
- `data-prism-core/.../core/RefusalPaths.java` — the sibling helper for paths; follow its shape
  (final class, private constructor, static method, Javadoc saying why).
- `data-prism-orchestration/.../DenyDecisionCodeTest.java:172-186` — the exception keeps its raw
  code; only what is written out is replaced. Keep that.
- `docs/conventions.md` — fail closed; no test data that is real personal data.

## Acceptance

- [ ] `io.github.aindriub.dataprism.core.RefusalCodes` exists with a public constant
      `INVALID = "INVALID_REFUSAL_CODE"` and a static method returning its argument when it matches
      `[A-Z][A-Z0-9_]{0,63}` in full, and `INVALID` otherwise, including for `null` and blank.
      `RefusalCodesTest` covers a valid code, a 64-character code, a 65-character code, lower case,
      a leading digit, a space, a newline, `null` and blank.
- [ ] `grep -rn 'A-Z0-9_' --include='*.java' data-prism-*/src/main` returns only `RefusalCodes.java`.
- [ ] `ToolCalls.denyDecision` and `DefaultContextOrchestrator`'s deny decision use the helper;
      `ToolCallsDenyDecisionTest` and `DenyDecisionCodeTest` pass unchanged in their assertions.
- [ ] Every refusal text either tool returns passes its code through the helper: `refused: <code> at <path>`
      from `ToolCalls.refused` and from both tools' `PrivacyRefusedException` branch, and the deny text
      built from `denialCode()`, `SecurityRefusedException.code()` and `AdmissionDecision.code()`.
      `APPROVAL_REQUIRED approvalId=<id>` is still produced for valid codes.
- [ ] `MalformedRefusalCodeTest` drives each of those four code sources, for both tools, with a code
      such as `"leak alice@example.invalid +353-0-000-0000"`. For each it asserts that the tool
      result text contains `INVALID_REFUSAL_CODE`, that no tool result content or `_meta` contains
      `example.invalid`, and that no log event captured on the root logger during the call contains
      `example.invalid`.
- [ ] A well-formed code (for example `TOOL_PAUSED`) is returned unchanged; an existing test or a new
      case asserts it.
- [ ] `AuditedRefusalException.code()`, `PrivacyRefusedException.code()` and
      `SecurityRefusedException.code()` still return the raw code; no exception class is changed.
- [ ] `docs/tools.md` states that a refusal code which is not an upper-case token is shown as
      `INVALID_REFUSAL_CODE`.
- [ ] `mvn -pl data-prism-core,data-prism-orchestration,data-prism-mcp -am verify` passes.

## Out of scope

- The `<path>` part of `refused: <code> at <path>`; task 124 already redacts it via `RefusalPaths`.
- `ReidentificationService`'s `DENY:<code>` (its codes are an internal enum) and the operator
  surface's error bodies (`data-prism-server/**`, task 105).
- `ToolAdmission.none()` and overload deprecation (follow-up 1).
- Correlation-id work in the MCP tools and orchestrator (task 110, which now depends on this task).
- Changing the allowed pattern.
