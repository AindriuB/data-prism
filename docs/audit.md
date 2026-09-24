# The durable audit chain

Every privacy decision Data Prism makes — an allow, a redaction, a refusal —
is audited. `docs/configuration.md` (task 59) owns the `dataprism.audit.*`
configuration vocabulary; this page owns what the durable, tamper-evident
sink actually is, what its offline verifier proves, and — the part worth
reading before relying on either — what neither of them proves.
`AuditRecorder` builds and chains the `instanceId`/`sequence`/`previousHash`
fields described below for every configured sink, not only `hash-chained` —
only `FileAuditSink`'s own file has the byte-for-byte shape the offline
verifier can actually check.

There are three audit sinks:

- `slf4j` — the existing `Slf4jAuditSink`, one structured log line per event,
  going wherever this deployment's log pipeline sends log lines. Nothing
  verifies it independently: log infrastructure reorders, compresses and
  ships lines outside this application's control, so there is no byte-for-byte
  shape here for an offline tool to check.
- a bring-your-own sink, selected with `dataprism.audit.sink: approved-sink`
  and requiring the deployment to also supply an `AuditSink` bean of its own —
  startup refuses with `AUDIT_SINK_BEAN_REQUIRED` if that bean is missing.
  `dataprism.audit.credential-reference` is unrelated: setting it does not
  select or configure this sink. Out of scope here.
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
  event's hash to the previous event's hash *for that recorder instance*. The
  chain is keyed on `instanceId`, which is `dataprism.audit.writer-id` plus a
  `/` and a random UUID minted once per `AuditRecorder` construction — one per
  process boot, not one per configured deployment. Because `/` is the
  separator, a configured `writer-id` containing `/` is refused at startup
  with `INVALID_AUDIT_WRITER`. A restart under the *same*
  `writer-id` therefore mints a fresh `instanceId` automatically, so it is
  reported as a second, independent writer starting at `GENESIS`, not as a
  break in the first writer's chain (shown below, under "What a run actually
  looks like"). That is also this scheme's blind spot: deleting every record
  belonging to one boot's `instanceId` — its whole chain, not just its tail —
  leaves no trace that writer ever existed. The verifier only reports the
  writers it finds records for, so a wholly deleted boot is invisible, not
  merely unprovable, to a report that only ever saw the boots that survived
  (also shown below).
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
proves less than an operator asking that question would assume. It has no
special check for `Slf4jAuditSink` output, and pointing it at a log file is
worse than failing cleanly: an ordinary log line has no 0x1F field separators,
so it fails `AuditRecordFormat`'s field-count check the same way a torn
fragment from an interrupted write does, and `classifyParseFailure`
deliberately reads that shape as benign — every line is reported as
`INTERRUPTED_WRITE_FRAGMENT`, "INTERRUPTED WRITE, not tampering", and the run
exits **4**, the code this page's own table calls a structural anomaly, not
a break. That is a false reassurance, not a false alarm: a file that is not
an audit file at all reads as a run of benign interrupted writes, never as the break
it should be reported as. Do not point this
verifier at `Slf4jAuditSink` output; it only has the byte-for-byte shape this class
relies on when read back from `FileAuditSink`'s own file.

Run it against a copy of the file:

```sh
java -cp <classpath> io.github.aindriub.dataprism.audit.AuditChainVerifierCli /path/to/audit.log
```

or `--help` for a shorter summary.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Intact — every writer's chain verified: no break, no structural anomaly, no in-flight tail. |
| 1 | Unreadable input — the file could not be opened or read, or the invocation was malformed. |
| 2 | Break detected — an edit or deletion was found in at least one writer's chain, or a line could not be ruled out as tampering. |
| 3 | Possibly-in-flight tail — the final record has no terminating newline; not a break. Only reported when nothing scored higher: a run with both a break and an in-flight tail exits 2, and a run with both a structural anomaly and an in-flight tail exits 4 (precedence is break, then anomaly, then in-flight tail). |
| 4 | Structural anomaly — an interrupted-write fragment, a sink-contract duplicate-sequence violation, or a writer's chain not starting at `GENESIS` immediately after another structural anomaly, which offers plausible (never certain) context for the missing head. A writer's chain not starting at `GENESIS` in any other position is a break, exit code 2, not this. Never returned together with exit code 2. |

### What a run actually looks like

The blocks below are excerpts: every real run also prints a leading blank
line and, after the writer report, a `LIMITATION` paragraph, summarised
under "What this does and does not prove" below, omitted here for brevity.

Three records written by one writer (`walkthrough-writer-1`), verified intact:

```
Writer walkthrough-writer-1/e6b1ef0f-728f-45ec-ba7b-16da13df827b:
  first sequence seen: 1
  sequence count: 3
  head hash: 9965fd3691c08ebb61bb31e7d041a9ec5610c96862d77f542e9f8f50a55e337b
  intact: every record in this writer's chain verified against the one before it.
```
(exit code 0)

Three records, with the middle one's `subjectPseudonym` edited in
place after being written:

```
Writer walkthrough-writer-1/4291cb3f-acd2-40b2-989b-e1525e6a2325:
  first sequence seen: 1
  sequence count: 3
  head hash: 99bb386c16d8cd6e22fe8ccc96ac0460856c870f616f9b0a0fee614ef0eaa009
  CHAIN BREAK at sequence 2, byte offset 389: its eventHash does not match AuditEventHash recomputed from its own stored fields -- its content was altered after it was written. This is evidence the record was edited or deleted after being written -- investigate immediately.
  1 later record(s) in this writer's chain follow the break above and are reported as after the break, not as separate breaks.
```
(exit code 2)

Three fresh records, this time with the *last* one's `subjectPseudonym`
edited in place after being written — proving that a chain does not need a
successor record to catch a tampered tail:

```
Writer walkthrough-writer-1/81e2238a-b55c-4a81-b592-0ab8d137db9f:
  first sequence seen: 1
  sequence count: 3
  head hash: bfbfbb87d2d3da2350dea021d17c1f85c52a46f8ab5c3c5f31fb786e241c01ef
  CHAIN BREAK at sequence 3, byte offset 778: its eventHash does not match AuditEventHash recomputed from its own stored fields -- its content was altered after it was written. This is evidence the record was edited or deleted after being written -- investigate immediately.
```
(exit code 2 — editing the final record is caught exactly as an edit
anywhere earlier in the chain is: only *deleting* the tail, not editing it,
escapes detection)

Three records, with the third deleted entirely — the truncation
this verifier cannot detect, proven rather than merely claimed:

```
Writer walkthrough-writer-1/1c000be7-2f60-4e1e-b41f-30ab8b5d00a5:
  first sequence seen: 1
  sequence count: 2
  head hash: 1ab35954f9bffef2c833532063e874eba1665a706dabbedfa4e2cab6b3eab307
  intact: every record in this writer's chain verified against the one before it.
```
(exit code 0 — reported intact, because it is: a truncated append-only file's
remaining records chain perfectly, and nothing inside the file distinguishes
"this writer stopped writing" from "someone deleted the tail")

The same writer id (`walkthrough-writer-1`), restarted: two records from one
boot, then the process exits and a second boot writes a third record under
the same configured `writer-id`. Each boot mints its own random suffix, so
the reader's own run will show different UUIDs after the `/`, but the shape
is always two independent writers, each starting at `GENESIS`, neither
reporting a break:

```
Writer walkthrough-writer-1/6d943435-978d-405a-9de7-8af642fcb80f:
  first sequence seen: 1
  sequence count: 2
  head hash: 21e6611131ac46c541f861d5f6af5d3977af77066dda96313ba23087c1894fdc
  intact: every record in this writer's chain verified against the one before it.

Writer walkthrough-writer-1/ca9588c5-0db3-4404-89a2-3d6277c5c7f5:
  first sequence seen: 1
  sequence count: 1
  head hash: 68e35e893821f4f137b7b69746cfb6831830b735a603e82df1d66bdbf8a785d1
  intact: every record in this writer's chain verified against the one before it.
```
(exit code 0 — a restart under the same `writer-id` is reported as a second
writer starting fresh, never as a break in the first one's chain)

The same setup, but this time the second boot's one record is deleted
outright — not truncated to a shorter chain, removed entirely, leaving only
the first boot's two records in the file:

```
Writer walkthrough-writer-1/6d943435-978d-405a-9de7-8af642fcb80f:
  first sequence seen: 1
  sequence count: 2
  head hash: 21e6611131ac46c541f861d5f6af5d3977af77066dda96313ba23087c1894fdc
  intact: every record in this writer's chain verified against the one before it.
```
(exit code 0 — the report never mentions the second boot at all: it is not
reported as broken, missing or suspicious, because nothing in the surviving
file records that a second boot ever wrote anything. Deleting a whole boot's
chain is indistinguishable from that boot never having run.)

Every run also prints a limitation statement, regardless of outcome; the
section below is the full account of what that statement summarises.

## What this does and does not prove

Read this before treating an intact report, or this file's mere existence,
as more than it is.

**What it proves.** For every record the verifier could see, in every
writer's chain, replaying the chain found no edit or deletion of any of the
nineteen hashed fields. Editing a record breaks its own stored hash the
moment its content no longer matches what `AuditEventHash` recomputes from
that content, so an edit is caught anywhere in the chain, including the very
last record written — a chain does not have to have a successor record to
catch an edit to its tail (verified above). Only deleting one or more of a
writer's most recent records goes undetected, because there is then nothing
left in the file for the check to notice is missing; that gap, and the
related whole-boot-deletion gap, are covered below. Where an edit is found,
this check follows the break forward to report every later record in that
writer's chain as after it, not as separate breaks, at exit code 2.

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
  Deleting an entire boot's records — every record under one `instanceId`,
  not just its tail — has the same shape at the level of the whole writer:
  the surviving writers each still verify intact, and the report simply never
  mentions the boot whose every record is gone, because the verifier can only
  report on the writers it finds records for (demonstrated above alongside
  the restart case).
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

The diagram below shows what one writer's hash chain looks like, and what
its offline verifier can and cannot tell you.

[![The audit chain: one chain per writer boot (writer-id/uuid) starts at GENESIS; the offline verifier replaying it detects an edit anywhere in the chain, including the last record, and a deletion that has later records after it, but cannot detect truncation of the tail, deletion of a whole boot's records, or recomputation by someone with write access.](assets/diagrams/audit-chain.svg)](assets/diagrams/audit-chain.svg)
