# 97 — Detect tail truncation and missing boots with external audit checkpoints

**Repo:** `.`
**Depends on:** 92
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/** *(except `FileAuditSink.java`)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/**
- docs/audit.md *(the "What this does and does not prove" section and a new checkpoint section)*

## Goal

`docs/audit.md:226-290` states that deleting the tail of a writer's chain, or
every record of one boot, cannot be detected from inside the file. This task
has the recorder write checkpoints to a second, operator-separated sink: a
`BOOT` checkpoint at start, `PERIODIC` checkpoints on demand, and a
`SHUTDOWN` checkpoint at close. Each checkpoint holds `(instanceId, sequence,
headHash)`. The offline verifier, given the checkpoint file, then reports
truncation before a checkpoint and boots with checkpointed records but no
surviving records. The 2026-09-23 decision in `docs/architecture.md` already
names external checkpointing as the accepted way to close this gap.

## Context

- `AuditRecorder.java` holds the chain head (`previousHash`, `sequence`) and
  the instance id.
- `AuditChainVerifier` / `AuditChainVerifierCli` use exit codes 0-4
  (`docs/audit.md:115-124`) and print a limitation statement on every run.
- `FileAuditSink.java` is the pattern for an append-only fsync-per-record
  writer with poisoning. Do not edit it; task 102 owns it.
- `docs/audit.md:244-262` contains the truncation and whole-boot paragraphs
  this task narrows. It must not claim more than it proves; see
  `docs/plan/PLAN.md` lines 662-680 for the binding owner wording.
- Task 102 needs one checkpoint kind from this task: `RETENTION_ANCHOR`.

## Acceptance

- [ ] `AuditCheckpoint` is a record `(Kind kind, String instanceId, long
      sequence, String headHash, Instant recordedAt)`, with `Kind` one of
      `BOOT`, `PERIODIC`, `SHUTDOWN` or `RETENTION_ANCHOR`.
- [ ] `AuditCheckpointSink` is an SPI. `FileAuditCheckpointSink` appends one
      JSON line per checkpoint with fsync, and refuses to open a path equal to
      a given audit file path (`AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE`).
- [ ] `AuditRecorder` gains a constructor taking an `AuditCheckpointSink`.
      It writes a `BOOT` checkpoint (sequence 0, GENESIS hash) at
      construction, and the constructor throws if that write fails. It also
      gains `checkpoint()`, which writes `PERIODIC` with the current head, and
      `close()`, which writes `SHUTDOWN`. The existing constructors write no
      checkpoints and behave as before.
- [ ] If a checkpoint write fails, every later `record(...)` throws
      `AuditCheckpointUnavailableException` until a later `checkpoint()`
      succeeds. The chain head is not advanced by a refused record. A test
      with a throwing checkpoint sink asserts both.
- [ ] Given `--checkpoints <file>`, the verifier exits `5` and names the
      writer in each of two cases:
      - a writer's last surviving sequence is lower than its highest
        checkpointed sequence (`TRUNCATED_BEFORE_CHECKPOINT`);
      - a writer has a checkpoint with sequence > 0 and no surviving records
        (`MISSING_WRITER`).
      A record whose hash differs from its checkpointed `headHash` at the
      same sequence is reported as a break (exit 2). A writer with only a
      `BOOT` checkpoint and no records verifies intact.
- [ ] One test per case reproduces the `docs/audit.md` walkthroughs. The tail
      truncation case and the whole-boot deletion case each previously exited
      0, and each now exits 5 when checkpoints are supplied.
- [ ] Without `--checkpoints`, every existing verifier test passes unchanged.
- [ ] The limitation statement printed on every run, and `docs/audit.md`,
      still say three things. Records written after the last checkpoint
      remain undetectable if deleted. A checkpoint only helps if whoever can
      edit the audit file cannot also edit the checkpoint file. Neither file
      is tamper-proof. No sentence says "tamper-proof", "immutable" or
      "compliant".
- [ ] `git diff` shows `FileAuditSink.java` unchanged.
- [ ] `mvn -pl data-prism-core -am verify` passes.

## Out of scope

- Scheduling periodic checkpoints, and any `dataprism.*` property. That is
  task 103.
- Keyed or signed checkpoints, and keyed chains. That is task 107.
- Retention and segment deletion. That is task 102.
