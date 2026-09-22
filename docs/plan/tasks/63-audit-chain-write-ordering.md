# 63 — Advance the audit hash chain only after the sink write succeeds

**Repo:** .
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventHash.java (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditRecorderTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditSinkFailureAbortsResponseTest.java (new)

## Goal
`AuditRecorder` advances `previousHash` before `sink.record(event)` runs. If the
sink throws, the in-memory chain head has moved past an event that was never
written, and the next successful write chains against a hash for a record that
does not exist — a chain that fails verification for a reason that is not
tampering. Fix the ordering, and make the existing accidental fail-closed on a
throwing sink an explicit, tested guarantee.

## Context
- `data-prism-core/.../audit/AuditRecorder.java:37-60` — `previousHash = hash;`
  precedes `sink.record(event);`; the whole method is `synchronized`.
- `data-prism-core/.../audit/AuditEvent.java:28-48` — the chain fields and why
  the chain is per writer, not global.
- `data-prism-mcp/.../GetEntityContextTool.java:214,223` — no call site wraps
  `audit.record(...)`, so a sink exception aborts the response. That is the
  behaviour to pin, not to soften (`CLAUDE.md` rule 2).

## Acceptance
- [ ] `sink.record(event)` completes before `previousHash` is advanced, or the
      advance is rolled back when the sink throws. Either way the recorder's state
      after a throwing sink is byte-identical to its state before the call.
- [ ] A test with a sink that throws on the second call asserts: the exception
      propagates out of `record`, and the third call's event carries
      `previousHash` equal to the first event's `eventHash`.
- [ ] A test asserts the sequence number is not consumed by a failed write, or —
      if the chosen design does consume it — states that in the recorder javadoc
      and asserts the resulting gap is what the verifier in task 66 accepts.
      Whichever is chosen must match task 66's stated tolerance.
- [ ] The canonical hash body construction moves into `AuditEventHash`, a public
      class in the same package, exposing a method that takes an `AuditEvent` (or
      its components) and returns the hex `eventHash`. Task 66's offline verifier
      calls this and nothing else to recompute a hash, so no second copy of the
      join format can drift.
- [ ] The hash string for a given event is unchanged by this task: an existing or
      new test pins one fully-specified event's `eventHash` to a checked-in literal
      and passes both before and after the refactor.
- [ ] `AuditSinkFailureAbortsResponseTest` boots the application with an
      `AuditSink` bean that throws, calls the tool, and asserts the call fails
      rather than returning data — the fail-closed guarantee, stated rather than
      accidental. No `catch` swallowing an audit failure is added anywhere.
- [ ] `mvn -pl data-prism-core,data-prism-integration-tests -am test` passes.

## Out of scope
- `Slf4jAuditSink.java`, `AuditSink.java`, `AuditEvent.java` and the new
  `FileAuditSink` (task 64 owns it).
- Retry, buffering or any recovery behaviour on sink failure.
- Changing the hash algorithm or the joined field order.
