# 74 — Stop a sink's raw exception message reaching the MCP client

**Repo:** .
**Depends on:** 67
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/AuditUnavailableException.java (new)
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/GetEntityContextToolTest.java
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesToolTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditSinkFailureAbortsResponseTest.java

## Goal
An `AuditSink` that throws at record time aborts the response — correct, and
required. But the thrown exception's own message travels out of the tool's call
handler, is stringified by the MCP SDK, and reaches the client verbatim as an
`McpError`. With task 67 wiring `hash-chained` to `FileAuditSink`, that message
can now be a `PoisonedException` naming the configured audit file path, so a
server filesystem path can be disclosed to a model — in a product whose premise
is controlling what reaches the model. Replace the verbatim message with a stable
refusal code, changing nothing about the abort itself.

## Where the defect is — two reviewers established this, do not relitigate it
The disclosure originates in the **propagation path**, not in the sink.
`FileAuditSink` names the path in `OpenFailedException` (`FileAuditSink.java:108`)
and `PoisonedException` (`FileAuditSink.java:135`) deliberately: that is a
startup/operator-facing message and its own acceptance list requires it. At
record time the first failure throws a plain `UncheckedIOException`
(`FileAuditSink.java:87-89`) wrapping a channel `IOException` whose JDK message
carries no path. So the sink is correct as written; what is missing is any
sanitising boundary between `audit.record(...)` and the client.

That boundary belongs in the tools. `GetEntityContextTool.deny`
(`GetEntityContextTool.java:220-231`) and `denyUnauthenticated`
(`:212-218`) call `audit.record(...)` with nothing wrapping it — deliberately, so
the call aborts — and the `try/catch` block at `:171-195` is downstream of them
and does not cover them. The exception therefore escapes `handle` and the SDK
renders it. `CompareEntitySourcesTool` has the identical shape at `:274-298`.
**Do not "fix" this in `FileAuditSink`.**

## The shape of the fix
Catch only the audit-record failure, at each `audit.record(...)` call site in the
two tools, and rethrow a new `AuditUnavailableException` carrying a stable code
(`AUDIT_UNAVAILABLE`) and **no text derived from the caught exception** — not its
message, not its class name, not its cause's message. Keep the cause attached for
the server-side log only. This is a wrapped rethrow, which `docs/conventions.md`
"Errors" permits; a silent catch or a catch-and-continue is not.

Note the precedent already in the file: a refusal the client may see is rendered
as a bare stable code — `deny(...)` returns `error(code)` and the
`PrivacyRefusedException` branch returns `"refused: " + code + " at " + path`,
with the comment at `:185-187` stating exactly why the value never went into the
exception in the first place. `RuntimeException` at `:190-194` already refuses to
echo a message for the same reason. This task extends that discipline to the one
path it does not yet cover.

## Constraints this task must not break
- **The abort does not change.** Task 63 made "a throwing audit sink aborts the
  response rather than returning data" an explicit, tested guarantee, and
  CLAUDE.md rule 2 requires it. The call still fails; no `catch` may let the
  request continue, return a result, or downgrade to an `isError` result on the
  audit-failure path. Only what the client is *told* changes.
- **The client still learns the call failed and why**, in terms that disclose no
  deployment detail: a stable code, no path, no host name, no exception text.
- **`AuditSinkFailureAbortsResponseTest`'s non-vacuity survives.** It currently
  pairs `auditSinkFailureAbortsTheResponse` (real boot, real transport, throwing
  sink → `McpError`) with `sameDenialWithoutAFailingSinkIsAnOrdinaryErrorResult`
  (same denial, recording sink → ordinary `isError` result). That pair is what
  distinguishes an abort from a normal error; keep both halves and keep the
  second one's assertion that the sink actually recorded.

## Context
- `GetEntityContextTool.java:212-231` — the two unwrapped `audit.record(...)`
  call sites; `:238-243` — `error(...)`, the existing code-only rendering.
- `CompareEntitySourcesTool.java:274-298` — the same two call sites.
- `FileAuditSink.java:78, 87-89, 102-147` — which exceptions carry the path and
  which do not.
- `AuditSinkFailureAbortsResponseTest.java:176-196` — the assertion that currently
  pins the raw message (`hasMessageContaining("simulated sink failure, for this
  test only")`); `:209-241` — the non-vacuity companion.
- `docs/conventions.md`, "Privacy rules a diff must satisfy" — no sensitive value
  in an exception message; "Errors" — stable machine-readable codes, privacy
  refusals distinct from transport failures.

## Acceptance
- [ ] A call whose audit record fails still fails: the MCP client sees an
      `McpError`, never a `CallToolResult` carrying data or an unaudited decision.
      Asserted through the real booted application and the real MCP client
      transport already used in `AuditSinkFailureAbortsResponseTest` — not a
      directly-invoked call handler. (Two tasks in an earlier wave assumed a bare
      JSON body from an endpoint that negotiates SSE; both were caught only
      against the real server. Exercise the transport.)
- [ ] That `McpError`'s message contains `AUDIT_UNAVAILABLE` and does **not**
      contain the throwing sink's own message. The existing throwing sink's text
      (`"simulated sink failure, for this test only"`) is asserted absent, where
      it is currently asserted present.
- [ ] A new case wires a **real `FileAuditSink`** over a real temporary file,
      poisons it so that `record(...)` throws the path-naming
      `PoisonedException`, drives the same denial through the real transport, and
      asserts that neither the temporary directory string nor the file name
      appears anywhere in the `McpError` message or its cause chain as the client
      observes it. A stubbed sink does not satisfy this criterion — the path has
      to come from the real artifact.
- [ ] `sameDenialWithoutAFailingSinkIsAnOrdinaryErrorResult` still passes
      unchanged in intent: the same denial against a non-throwing sink returns an
      ordinary `isError` result and the sink recorded an event.
- [ ] Both tools are covered: an equivalent unit-level assertion in
      `GetEntityContextToolTest` and `CompareEntitySourcesToolTest` that a
      throwing `AuditRecorder` sink produces `AuditUnavailableException` with no
      substring of the cause's message, for both the unauthenticated-deny and the
      authorisation-deny call sites.
- [ ] No `catch` added in this diff returns a result, logs and continues, or
      otherwise lets an unaudited decision reach the client. A reviewer can check
      this by reading every new `catch` block in the diff.
- [ ] `mvn -q clean verify` passes over `data-prism-mcp` and
      `data-prism-integration-tests`. Baseline is commit `aaea5ae` plus task 67;
      re-measure rather than quoting a count forward.

## This task is a hard precondition on tasks 62 and 59
Both write documentation that would tell an operator to configure
`dataprism.audit.sink: hash-chained` — task 62 owns `docs/audit.md`, task 59 owns
`docs/quickstart.md` and `docs/configuration.md`. Neither may do so while this is
open: the documented configuration is exactly the one that makes a server
filesystem path reachable by an MCP client. Land 74 first, or the docs are
recommending the disclosure.

## Out of scope
- `FileAuditSink` and everything else under `data-prism-core/audit` — the sink is
  correct and task 72 owns that file set. Changing `OpenFailedException` or
  `PoisonedException` to drop the path breaks the operator-facing message their
  own task required, and is the wrong fix for this defect.
- The `hash-chained` wiring and the audit properties (tasks 67, 73).
- Any documentation edit. Name the new code in the close-out; 62 and 59 write it.
- Widening the sanitising boundary to the orchestrator or the source adapters.
  Only the audit-record call sites in the two tools are in scope.
