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
- `AuditEventHash` is public in `data-prism-core` precisely so this verifier can
  reuse the canonical join; it was verified byte-identical to the previous inline
  implementation. Do not re-derive the joined body here.
- `AuditRecordFormat` — its class javadoc pins the completeness contract: a
  record is complete only when its line is `\n`-terminated. It encodes null as a
  `\0` sentinel and an empty set as `\e`, neither of which is forgeable from real
  field content, so a field-count or sentinel anomaly is a structural signal.
- The chain is keyed per writer on `instanceId`, never globally.
  `docs/plan/HISTORY.md` (grep `v0.3.0 wave 1`) carries the full account of the
  four ordinary, non-malicious failure modes whose file shape resembles
  tampering; all four are in the acceptance criteria below.

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
      possibly-in-flight tail, unreadable input — plus a code, or codes, for the
      structural non-tampering outcomes below (mid-file interrupted-write
      fragment, sink-contract violation) that is never the break-detected code.
- [ ] **Torn trailing record.** A final chunk with no `\n` terminator is reported
      under the same "possibly in flight" outcome as the truncated-final-line
      criterion above, in wording distinct from a hash mismatch, and is not
      counted as a chain break — `AuditRecordFormat`'s class javadoc pins this:
      an unterminated trailing chunk is an in-progress write, not evidence of
      tampering. Exercised by a test that writes a real record file and truncates
      it mid-line, not by a hand-asserted string.
- [ ] **Mid-file field-count error from a historic fragment.** A line in the
      MIDDLE of the file that fails to parse on field count — the shape produced
      when an operator restarts after a torn write and the new `FileAuditSink`
      opens APPEND on the same path, landing its first record directly after the
      surviving fragment — is reported as a probable interrupted write followed
      by a restart, naming the byte offset, and explicitly not as tampering. The
      records before and after that line are still verified. This failure mode is
      a documented operator responsibility this release does not close (durable
      append-only-ness needs `O_APPEND`, WORM or object-lock storage); the
      verifier handles it gracefully rather than fixing it. Exercised by a test
      that constructs the real concatenated-fragment file shape.
- [ ] **Duplicate sequence number within one writer.** Two durable records
      sharing a sequence number for the same `instanceId` (the shape a
      non-conforming sink produces if it writes durably and then throws, against
      `AuditSink`'s all-or-nothing contract; `AuditRecorder` itself now rolls the
      sequence back on throw) is reported as a sink-contract violation, distinct
      in wording from tampering, naming the writer, sequence and both offsets —
      including the case where the second record's `previousHash` points at the
      wrong record. Exercised by a test constructing that file.
- [ ] **A new writer starting mid-file.** A fresh `instanceId` whose chain begins
      at `GENESIS` partway through the file — the ordinary result of a process
      restart — verifies as normal. Each writer's chain is replayed
      independently and the transition is never reported as a break. Exercised by
      a test whose file contains one writer's records followed by a second
      writer's chain starting at `GENESIS`.
- [ ] **Unambiguous wording.** The four outcomes above are each distinguishable,
      by a compliance reader with no source access, from a genuine hash mismatch:
      the output names what happened and what the reader should do, and a reader
      never has to guess whether they are looking at tampering. Asserted on the
      CLI's actual stdout strings.
- [ ] Every one of the four is driven by a test that constructs the actual file
      shape on disk and runs the verifier over it. None may be covered by prose,
      a comment, or a unit-level assertion that bypasses parsing.
- [ ] The binding limitation is restated wherever these criteria touch it: none
      of the four outcomes above, and no combination of them, is claimed to
      detect truncation of the most recent records. That still requires an
      external checkpoint this release does not build, and the limitation is
      printed on every run — including runs that end in one of the four outcomes.
      Nothing in this task may claim more than intra-writer edit/delete detection.
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

## Attempt 1 — failed

Tester: PASS (full reactor, 572 tests, all 19 modules). Reviewer: REQUEST
CHANGES — two defects, both probed against real files, both cases where the
artifact would tell a compliance reader "intact" about genuine tampering.

Most of this attempt is right and must be kept. The four wave-1 failure modes
are each distinguished from a break, on real bytes rather than hand-built
strings, including a genuine short-write-then-throw FileChannel fault injection
to produce real torn fragments. Exit codes are distinct and documented, with
structural anomalies never sharing the break code. One CLI test forks a real
`java -cp` process against a file from an actual AuditRecorder + FileAuditSink
run. AuditEventHash is reused, never re-derived. The limitation prints on every
path including --help. No output contains "tamper-proof", "immutable" or
"completeness".

The attack analysis is worth keeping in full. CANNOT be hidden: a mid-chain
deletion masked by an appended malformed fragment (skipped lines do not advance
lastHash, so the next record still breaks — probed, exit 2, break beats anomaly);
duplicate-sequence lines never advance the chain; a forged instanceId cannot
mask a mid-chain break. CAN be hidden: tail truncation (disclosed), head
truncation (defect 1, NOT disclosed), timestamp/sourceSystems edits (defect 2,
NOT disclosed).

### Defect 1 — head deletion reads as intact, and the limitation does not cover it

`AuditChainVerifier.java:127` — the first record seen for a writer skips the
link check without asserting `previousHash == GENESIS`. Deleting a writer's
first two records yields "sequence count: 2 / intact", exit 0. Probed on a real
FileAuditSink file.

This is worse than a missing feature. The printed limitation disclaims only
truncation of the MOST RECENT records, so a reader is entitled to conclude that
earlier deletion would have been caught. The artifact actively explains away
real tampering it does not disclose.

Fix, entirely within this task's Owns: assert GENESIS on a first-seen writer
and print that writer's first sequence number so a reader can see where each
chain starts.

### Defect 2 — the limitation claims more than the hash covers

`AuditChainVerifierCli.java:44` — the limitation says the tool "detects an edit
of a record already written". `AuditEventHash.compute` joins seventeen fields
and `timestamp` and `sourceSystems` are not among them (confirmed directly
against `data-prism-core/src/main/java/.../audit/AuditEventHash.java:28-40`).
Editing a mid-chain record's timestamp prints intact, exit 0. Probed.

THE ROOT CAUSE IS NOT IN THIS TASK. The hash is task 63's, already merged. An
audit hash that omits the timestamp means a record can be backdated and the
chain still verifies, which is most of what "tamper-evident" means to a
compliance reader. Two ways forward, and the owner's decision was pending when
this was recorded:
- Widen `AuditEventHash` to cover timestamp and sourceSystems, in a new task
  owning that file and AuditRecorder. Invalidates any chain already written;
  nothing durable exists yet.
- Leave the hash and narrow this limitation to name exactly which fields the
  check covers.
Either way defect 1 is fixed here. This task's wording must match whatever the
hash actually covers when it lands — do not restate "detects an edit" without
qualification.

### Suggestions
- `AuditChainVerifier.java:146-160` — `cause.getClass() == IllegalArgumentException.class`
  is sound today but couples the benign classification to an unowned class's
  exception type. If `AuditRecordFormat` later throws a different type for the
  same condition, interrupted writes would be silently reclassified as
  tampering — the cry-wolf defect arriving by the back door, with no test
  failing to announce it. Counting unescaped 0x1F separators (!= 19) would test
  the shape itself and cannot silently flip.
- `AuditChainVerifier.java:100` — on a duplicate sequence the FIRST occurrence
  becomes canonical, so a forged record inserted before the real one makes the
  real record the reported anomaly. The message names both offsets so a reader
  can still tell, but which is treated as canonical should be stated.
- No test covers the narrower parse-failure path the implementer flagged
  (DateTimeParseException / NumberFormatException escalating to break severity
  rather than the benign bucket). Add one.
