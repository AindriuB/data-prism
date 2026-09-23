# The durable audit chain

Every privacy decision Data Prism makes — an allow, a redaction, a refusal —
is audited. `docs/configuration.md` (task 59) owns the `dataprism.audit.*`
configuration vocabulary; this page owns what the durable, tamper-evident
sink actually is, what its offline verifier proves, and — the part worth
reading before relying on either — what neither of them proves.

There are three audit sinks:

- `slf4j` — the existing `Slf4jAuditSink`, one structured log line per event,
  going wherever this deployment's log pipeline sends log lines. Nothing
  verifies it independently: log infrastructure reorders, compresses and
  ships lines outside this application's control, so there is no byte-for-byte
  shape here for an offline tool to check.
- a bring-your-own sink, named by `dataprism.audit.credential-reference`
  (`APPROVED_SINK`) — out of scope here.
- `hash-chained` — `FileAuditSink` plus `AuditRecorder`, described below.
  This is the sink this page is about.

## `FileAuditSink`

`FileAuditSink` appends one line per event to a single file named by
`dataprism.audit.file-path`, opened `CREATE`/`WRITE`/`APPEND` and fsynced
(`FileChannel.force(true)`) before `record(AuditEvent)` returns — a write is
durable, on the operator's storage, before the call that made it returns
control to the caller.

A few properties are deliberate, not accidental gaps:

- **Single file, no rotation.** This release does not rotate, truncate or
  compact this file. Rotation, retention and shipping this file anywhere are
  operational concerns this release does not build; an operator wanting them
  supplies them outside Data Prism, against a file whose own shape (below)
  those tools must not break.
- **Append-only by the OS's own guarantee, not by anything this class
  enforces.** `FileAuditSink` opens the file with `StandardOpenOption.APPEND`,
  which is only as durable as the surrounding deployment makes it. Nothing
  stops an operator, a misconfigured backup job, or a compromised host from
  opening the same path some other way and rewriting it. Durable
  append-only-ness — `O_APPEND` enforced at the filesystem, WORM storage,
  object-lock, a dedicated log-shipping user with no other access — is an
  operator responsibility this release does not build for them.
- **Poisons itself after any write failure.** A short write, or a `force`
  call that fails after every byte reached the channel, leaves the file in a
  state this class cannot safely continue writing after — a later record
  landing directly after an unterminated fragment would make that fragment's
  bytes durable as part of a different, complete-looking line. So once a
  write fails, every subsequent `record()` call on that sink throws
  immediately rather than risking that. This is a fail-closed property of the
  writer, not of the file it already wrote.
- **Per-writer hash chain, not a global one.** `AuditRecorder` chains each
  event's hash to the previous event's hash *for that recorder instance*
  (`instanceId`, `dataprism.audit.writer-id`). Two processes, or two restarts
  of the same process with different writer ids, produce two independent
  chains in the same file, each starting from `GENESIS` (64 zero characters).
  `AuditEventHash` joins nineteen fields per record — `eventId`, `timestamp`,
  `instanceId`, `sequence`, `principalId`, `clientId`, `tool`, `entityType`,
  `subjectPseudonym`, `parameterFingerprint`, `privacyProfile`, `scopeId`,
  `purpose`, `caseId`, `policyDecision`, `correlationId`, `sourceSystems`,
  `rejectedArguments` and `previousHash` — never a raw source value, only what
  `Slf4jAuditSink` already emitted. `AuditFilePiiScanTest`
  (`data-prism-integration-tests`) scans this file's own output for stub
  fixture identifying values the same way `PiiLogScanTest` scans captured log
  output, closing the audit half of architecture boundary 7.

## The offline verifier

`AuditChainVerifierCli` replays each writer's chain from a copy of this file
and reports either an intact chain or the first edit or deletion found, per
writer. It is a standalone command-line tool on purpose — never a Spring
Actuator endpoint or a startup self-check — because a running instance
reporting on its own output conflates "the process that might have tampered
with this file says the file is fine" with independent verification, which
proves less than an operator asking that question would assume. It refuses
outright to be pointed at `Slf4jAuditSink` output: that never has the
byte-for-byte shape this class relies on.

Run it against a copy of the file:

```sh
java -cp <classpath> io.github.aindriub.dataprism.audit.AuditChainVerifierCli /path/to/audit.log
```

or `--help` for the same summary this section gives.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Intact — every writer's chain verified: no break, no structural anomaly, no in-flight tail. |
| 1 | Unreadable input — the file could not be opened or read, or the invocation was malformed. |
| 2 | Break detected — an edit or deletion was found in at least one writer's chain, or a line could not be ruled out as tampering. |
| 3 | Possibly-in-flight tail — the final record has no terminating newline; not a break. |
| 4 | Structural anomaly — an interrupted-write fragment, a sink-contract duplicate-sequence violation, or a writer's chain not starting at `GENESIS` (an ordinary restart, but deletion of that writer's earliest records cannot be ruled out). Never returned together with exit code 2. |

### What a run actually looks like

Three records written by one writer (`walkthrough-writer-1`), verified intact:

```
Writer walkthrough-writer-1:
  first sequence seen: 1
  sequence count: 3
  head hash: 68f085448baae1bd72e133774d4ab3dc5209c675d6839124aa03c21ef6e4b884
  intact: every record in this writer's chain verified against the one before it.
```
(exit code 0)

The same three records, with the middle one's `subjectPseudonym` edited in
place after being written:

```
Writer walkthrough-writer-1:
  first sequence seen: 1
  sequence count: 3
  head hash: 68f085448baae1bd72e133774d4ab3dc5209c675d6839124aa03c21ef6e4b884
  CHAIN BREAK at sequence 2, byte offset 351: its eventHash does not match AuditEventHash recomputed from its own stored fields -- its content was altered after it was written. This is evidence the record was edited or deleted after being written -- investigate immediately.
  1 later record(s) in this writer's chain follow the break above and are reported as after the break, not as separate breaks.
```
(exit code 2)

The same three records, with the third deleted entirely — the truncation
this verifier cannot detect, proven rather than merely claimed:

```
Writer walkthrough-writer-1:
  first sequence seen: 1
  sequence count: 2
  head hash: 205fef8a045db82fc2fd40536229fb72f0be1eebcda5fdc59cb52d12f1b566fc
  intact: every record in this writer's chain verified against the one before it.
```
(exit code 0 — reported intact, because it is: a truncated append-only file's
remaining records chain perfectly, and nothing inside the file distinguishes
"this writer stopped writing" from "someone deleted the tail")

Every run also prints the limitation below, in full, regardless of outcome.

## What this does and does not prove

Read this before treating an intact report, or this file's mere existence,
as more than it is.

**What it proves.** Replaying the chain found no edit or deletion of any of
the nineteen hashed fields in any record the verifier could see, in any
writer's chain, that this check did not also have to recompute past to reach
an "intact" verdict. Editing or deleting a record anywhere but the very end
of a writer's chain breaks every hash after it; this check follows that break
to its first occurrence and reports it, per writer, at exit code 2.

**What it does not prove — deliberately, not as an oversight:**

- **Truncation of the most recent records is undetectable, structurally.** An
  append-only file with its tail removed verifies perfectly end to end: there
  is nothing left in the file to disagree with. The demonstration above is
  not a corner case, it is the general shape of this gap. Detecting it needs
  an external checkpoint — a periodically recorded expected head hash, held
  somewhere the same actor who could truncate the file cannot also reach —
  and this release does not build one. A final record with no terminating
  newline is reported as "possibly in flight" (exit code 3) precisely because
  it is *not* proof of either tampering or health: it is exactly as
  consistent with an in-progress write as with a truncation caught mid-line.
- **This is intra-writer edit and delete detection, not a guarantee against
  a capable adversary.** `AuditEventHash` is unkeyed SHA-256 over the joined
  record body. Anyone able to write to this file directly can edit or delete
  a record and then simply recompute every hash that follows it — the
  resulting chain verifies perfectly, because nothing about an unkeyed hash
  stops whoever holds write access from recomputing it. Resisting that needs
  a keyed MAC (a secret the adversary does not also have) or an external
  checkpoint, and this release builds neither.
- **Durable append-only-ness is an operator responsibility, not something
  this class enforces.** `FileAuditSink` opens the file with `O_APPEND`
  semantics; it does not configure WORM storage, an object-lock policy, or
  restrict who else can open the same path some other way. Whether this file
  is actually append-only in practice is a property of the deployment's
  storage and access control, not of this code.
- **It says nothing about metric labels or trace attributes.** Architecture
  boundary 7 covers logs (`PiiLogScanTest`) and this file
  (`AuditFilePiiScanTest`); metric labels and trace attributes are unscanned
  by any test in this release.

No sentence above, or anywhere else in this file, should be read as a claim
that the durable audit log is tamper-proof, immutable, or independently
complete. It is not any of those. It is evidence — evidence that stands up to
replay for the fields it hashes, within the boundary this section states —
and evidence is what an offline verifier can actually give.
