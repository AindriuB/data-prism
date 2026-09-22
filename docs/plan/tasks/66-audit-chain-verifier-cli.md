# 66 — Ship the offline audit-chain verifier CLI

**Repo:** .
**Depends on:** 64
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java (new)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCli.java (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierTest.java (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCliTest.java (new)

## Goal
A chain nobody replays proves nothing. Add an offline command-line verifier that
reads a file written by `FileAuditSink`, replays each writer's chain, and reports
either an intact chain with its head hash or the first break with the writer id
and the file offset. It must state, in its own output, the one thing it cannot
detect.

## Context
- `docs/plan/tasks/64-file-audit-sink.md` — the record format this reads.
- `data-prism-core/.../audit/AuditEvent.java:28-48` — the chain is per writer
  (`instanceId`), because instances are horizontally scaled and a single global
  chain would fork under concurrency into something indistinguishable from
  tampering.
- Task 63's `AuditEventHash` — the single canonical hash construction. The
  verifier calls it; it does not re-implement the joined body.
- `data-prism-core/.../audit/AuditRecorder.java:22` — `GENESIS`, the 64-zero
  starting hash each writer's chain begins from.

## Settled design — implement, do not re-open
- Offline CLI only. Not an Actuator endpoint, not a startup check: an in-process
  endpoint conflates "the running instance says its own log is fine" with
  independent verification, which is a weaker compliance story.
- No verifier over `Slf4jAuditSink` output. Log infrastructure reorders,
  compresses and ships lines outside this application's control, so verifying
  that would prove less than an operator would assume.
- **Binding limitation, and it must be printed, not just documented.** The
  verifier cannot detect truncation of the most recent records: deleting the tail
  of an append-only file leaves a chain that verifies perfectly end to end.
  Detecting that needs an external checkpoint held outside the operator's
  control, which this release does not build.
- A truncated or unparseable final record is reported as "possibly in flight" —
  neither silently valid nor proof of tampering. A crash mid-write and a
  malicious truncation are indistinguishable from inside the file, and the output
  says so.

## Acceptance
- [ ] `AuditChainVerifier` takes a file (or stream) and returns a result per
      writer id: sequence count, head hash, and either intact or the first break
      with its sequence number, writer id and byte offset in the file.
- [ ] Interleaved writers verify independently: a file containing two writers'
      records interleaved reports both chains intact.
- [ ] A mutated record body in the middle of a chain is reported as a break at
      that record's sequence and offset, and every later record in that writer's
      chain is reported as after the break rather than as separate breaks.
- [ ] A deleted record in the middle of a chain is reported as a break naming the
      record whose `previousHash` no longer matches.
- [ ] A record whose `eventHash` does not match `AuditEventHash`'s recomputation
      is a break even when its `previousHash` links correctly.
- [ ] A truncated final line is reported as "possibly in flight", distinct in both
      exit code and message from a break, and does not mark the chain broken.
- [ ] The CLI prints, on every run including a fully intact one, an explicit
      statement that truncation of the most recent records cannot be detected
      without an external checkpoint this release does not build. No output string
      claims tamper-proofness, immutability or completeness.
- [ ] Exit codes are distinct and documented in `--help`: intact, break detected,
      possibly-in-flight tail, unreadable input.
- [ ] **Real artifact.** A test (or the task's own recorded verification) runs the
      CLI as a process against a file produced by an actual `AuditRecorder` +
      `FileAuditSink` run — `java -cp <built classes/jar> ...` — not against a
      hand-written fixture only, and asserts on its stdout and exit code.
- [ ] `mvn -pl data-prism-core -am test` passes.

## Out of scope
- Any Actuator endpoint, HTTP surface, or startup-time verification.
- Reading or verifying `Slf4jAuditSink` output.
- An external checkpoint, anchoring, or signing scheme.
- Documentation (task 62 owns `docs/audit.md` and `docs/architecture.md`).
