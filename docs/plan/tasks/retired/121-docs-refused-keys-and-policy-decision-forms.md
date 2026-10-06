# 121 — Correct the REFUSED disposition wording and document every policyDecision form

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** 101
**Owns:**
- docs/tools.md *(the field-dispositions paragraph under "`get_entity_context`", currently `:198-202`, only)*
- docs/audit.md *(a new `### policyDecision values` subsection, inserted immediately before `## The offline verifier`, only)*

## Goal

Two reference pages describe the audit record inaccurately. `docs/tools.md`
says `REFUSED` is recorded "for the path that caused a refusal". Since task
96, the key is a fixed placeholder, so no payload-derived path is ever
recorded. `docs/audit.md` never says what `policyDecision` may contain.
Consumers test for an exact `DENY` and miss the other forms. This task
corrects the first and documents the second from the code as merged.

## Context

- `DefaultContextOrchestrator.java:~214-221` and `:~301-305`: the key is
  `<source>:<refused>` when one source's scrub refused. It is
  `merged:<refused>` for every other refusal after fetching. That includes
  validation of the merged response, and also non-scrub refusals such as
  `NO_SOURCE_DATA` and budget exhaustion.
- `policyDecision` forms written today. Re-derive these with
  `grep -rn 'policyDecision\|"DENY\|"ALLOW\|DENY:\|ALLOW:'` over `*/src/main`
  once task 101 has merged:
  - `ALLOW` and `DENY` from the orchestrator;
  - a bare refusal code from the MCP tools' own denials, for example
    `TOOL_NOT_PERMITTED` and task 101's admission codes;
  - `ALLOW:<STAGE>` (`REQUESTED`, `APPROVED`, `RESOLVED`) and `DENY:<code>`
    from `ReidentificationService`.
- `docs/conventions.md#documentation`: use "supports", and never use
  "compliant" or "tamper-proof".
- Other owners of these pages: task 101 owns the admission table and the
  correlation section of `docs/tools.md`. Task 110 owns its own sections.
  Task 102 owns "Retention" and the directory-mode sections of
  `docs/audit.md`, task 106 owns "Joining to your AI-system logs", task 116
  owns the v3 sections, and task 117 owns the hashed-fields paragraphs. Keep
  the edits inside the spans named in `Owns`.

## Acceptance

- [ ] The `docs/tools.md` paragraph says:
      - a refusal is recorded under the fixed key `<source>:<refused>` when a
        source's scrub refused, and `merged:<refused>` otherwise;
      - no path from the payload is ever recorded;
      - `merged:<refused>` also marks refusals that are not validation
        failures, such as `NO_SOURCE_DATA`.
- [ ] `docs/audit.md`'s new subsection has a table of every `policyDecision`
      form, with the module that writes it and one example of each. The
      forms are `ALLOW`, `DENY`, bare `<CODE>`, `ALLOW:<STAGE>` and
      `DENY:<code>`. It tells consumers to classify by prefix and code, not
      by an exact `DENY`. It gives the rule that a value is a denial unless
      it is `ALLOW` or starts with `ALLOW:`.
- [ ] Every string literal used as `policyDecision` in `*/src/main` at merge
      time is covered by a row. The close-out pastes the grep output.
- [ ] `mkdocs build --strict` exits 0. `grep -niE 'compliant|tamper-proof'` on
      the changed lines returns nothing.

## Out of scope

- Changing any `policyDecision` value or disposition key in code.
- The operator surface's audit events. Task 105 documents those in
  `docs/reidentification.md`.
- The `AuditChainVerifierCli` limitation text and the hashed-field list. That
  is task 117.
- The admission-codes table in `docs/tools.md` (tasks 101 and 120).

## Attempt 1 — failed

Branch `task/121-docs-refused-keys-and-policy-decision-forms` (91e364a). Tester: PASS (mkdocs --strict). Reviewer: CHANGES.

- docs/audit.md:208, `DENY` row, two false statements:
  - "refused after fetching": SCOPE_READ_BUDGET is thrown before any fetch
    (DefaultContextOrchestrator.java:171) and is still recorded as DENY. The
    same catch also records DENY for any RuntimeException (adapter or
    scrubber failure), which is an error, not a privacy refusal. Say DENY
    covers refusals before or after fetching, and internal failures.
  - "the reason is in the field dispositions": dispositions only ever hold
    `<source>:<refused>` / `merged:<refused>` = REFUSED; no refusal code is
    recorded. Say plainly that the orchestrator's DENY record does not carry
    the refusal code. (The client-facing code is in the MCP result, joined by
    correlationId.)
- docs/tools.md:202: drop "after fetching" from "for every other refusal
  after fetching", which contradicts the budget example in the same paragraph.
- docs/audit.md:213-217: align the classification rule with task 112's planned
  event.outcome: ALLOW or ALLOW:* = success; empty = unknown (reserved, never
  written today); anything else = failure/denial.
- Run mkdocs --strict; report the exit code.

## Attempt 2 — failed

Branch at 1653d61. Reviewer: CHANGES (3/4 met).

- docs/audit.md:209: "dispositions hold only `REFUSED` under `<source>:<refused>`
  or `merged:<refused>`" is false. Scrub actions for sources already processed
  are put into the same map before the refusal
  (DefaultContextOrchestrator.java:315-316), and the whole map is audited on
  DENY (:222). Reword: the refusal itself is marked only as `REFUSED` under
  one of the two fixed keys, with no code; dispositions for fields already
  scrubbed may also be present.
- Same row: limit "The client-facing code is in the MCP result" to refusals.
  For an internal error the MCP text is just "the request could not be
  completed" (ToolCalls.java:121-123), and no code is shown to the client.
- Run mkdocs --strict; report the exit code.

## Attempt 3 — failed

Reviewer: CHANGES. One example is wrong; it came from the attempt-1 review.

- docs/audit.md:209: "an internal error such as an adapter or scrubber failure".
  Adapter failures never reach the orchestrator's catch. SourceFanOut.java:147-152
  turns them into a per-source FAILED outcome, so the call is ALLOW if
  another source answers, or the NO_SOURCE_DATA refusal if none does.
  Use "a scrubber or validator failure" as the example. Add one clause: adapter
  failures are recorded per source, and if every source fails the result is
  NO_SOURCE_DATA. Run mkdocs --strict.
