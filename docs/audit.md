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

- **`file-path` mode is a single file, with no rotation or retention.**
  `FileAuditSink` itself never rotates, truncates or compacts the file it is
  given. Daily segments and retention are available only in directory mode
  (`dataprism.audit.directory`), described under
  [Directory mode](#directory-mode) and in
  [configuration](configuration.md#segmented-files-checkpoints-and-retention).
  Checkpoints are not directory-only: `file-path` mode with
  `dataprism.audit.checkpoint.file-path` also writes `BOOT`, `PERIODIC` and
  `SHUTDOWN` checkpoints. With `file-path` there is no retention: shipping the
  file anywhere, and pruning it, are operational concerns an operator supplies
  outside Data Prism, against a file whose own shape (below) those tools must
  not break.
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
  `Slf4jAuditSink` already emitted. `entityType` holds the requested type
  only when it is a configured entity type, or an upper-case identifier when
  none is configured; otherwise it holds `<unregistered>` (see
  [`dataprism.audit`](configuration.md#dataprismaudit)). With no list, an
  upper-case token such as `MURPHY` or `ACC123` still passes the shape, so set
  `dataprism.audit.entity-types`.

  Records are written as `recordVersion` 3. Version 1 hashes the nineteen
  fields above, version 2 adds four, and version 3 adds
  `externalCorrelationId` (25 fields; see [Record version
  3](#record-version-3)). Version 1, 2 and 3 records all still verify. A line
  with no `recordVersion` is version 1 and still verifies, hashed over exactly
  the nineteen fields above, joined with `|` and `,` as before. Because that
  joining does not escape its separators, some distinct version 1 records can
  share a hash; version 1 keeps it so that committed chains still verify.
  Version 2 hashes a length-prefixed encoding (each item is written as its
  UTF-8 byte length, a colon and the text, so no two different records produce
  the same input) that includes `recordVersion`, `fieldDispositions`,
  `approvalId` and `approverId` as well as the nineteen fields. Editing a
  version 2 record's `recordVersion` to `1` is therefore reported as a break.
  `fieldDispositions` maps a field path to the action taken on it. Paths look
  like `<sourceName>:<json-pointer>` with array indices collapsed to `*` (for
  example `crm:/contacts/*/email`); the action is a `PrivacyAction` name or
  `REFUSED`. Dispositions name field paths and actions and never values.
  `approvalId` and `approverId` identify a four-eyes approval request and the
  second principal, or are empty. `AuditFilePiiScanTest`
  (`data-prism-integration-tests`) scans this file's own output for stub
  fixture identifying values the same way `PiiLogScanTest` scans captured log
  output, closing the audit half of architecture boundary 7.

## Retention

`FileAuditSink` is unchanged and still never rotates. For deployments that must
keep logs for a bounded period and then let them go, `SegmentedFileAuditSink`
writes one file per UTC day, `directory/audit-YYYY-MM-DD.log`, the date being
the UTC date of the event's timestamp. The `.log` suffix is deliberate: a
record is a field-separated line, not JSON. Each segment is written with
`FileAuditSink`'s own discipline (fsync before return; the sink poisons after
any failed write, and does so for every day, not just the failing one). The
record format and the per-writer hash chain are the same; a chain simply runs
across segment files.

### The six-month minimum

`AuditRetention` refuses a retention period that can be shorter than six
calendar months with `IllegalArgumentException` containing
`AUDIT_RETENTION_BELOW_MINIMUM`. A day count is not safe merely for being
"about six months": six calendar months span 181 to 184 days, so `P181D` to
`P183D` are refused and `P184D` and `P6M` are accepted. `purge()` also checks
at run time that its cutoff is no later than today minus six calendar months,
and refuses with the same code if not. The
reason is EU AI Act Arts. 19 and 26(6), which ask deployers to keep
automatically generated logs for at least six months. Art. 19 also says
"unless provided otherwise in applicable Union or national law", so other
periods can be lawful. An explicit override
(`allowBelowMinimum`, configured as `dataprism.audit.retention-override`)
lets a shorter period through. Using the override is the operator's legal
responsibility; Data Prism does not judge whether it applies. GDPR storage
limitation points the other way: logs should not be kept longer than needed.

### Purge is deletion

`AuditRetention.purge()` deletes every whole segment dated strictly before
today (UTC) minus the retention period, and never today's segment. Before it
anchors or deletes anything it verifies the chains of the expiring segments;
if a segment's chain does not verify, that segment and every later expired one
are left in place and purge throws a `RetentionException` containing
`AUDIT_RETENTION_CHAIN_UNVERIFIED`, so purge never erases evidence of
tampering. Purge also reads the checkpoint sink's earlier retention anchors:
each writer's first expiring record must start at `GENESIS` or follow an earlier
anchor exactly (anchor sequence plus one, anchor hash equal to its
`previousHash`). If not, a segment was deleted by hand or its front was cut, and
purge refuses with the same code rather than anchor and delete the evidence. A
checkpoint sink that cannot read its anchors back leaves only the `GENESIS`
start acceptable, so purge fails closed. A segment's date never goes backwards within one sink: if the clock
steps back across UTC midnight, later events stay in the later-dated segment,
so one writer's chain is never split backwards across files. That rule is per
sink instance: after a restart with the clock behind, a reused writer id's
record can land in an earlier-dated file than its predecessor, and the verifier
reports a break. That fails loud, which is the intended direction. The records
in a deleted segment are gone; nothing here archives them. Archiving to
external storage before purge is an operator responsibility.

### Retention anchors

Deleting the front of a chain would otherwise look like tampering. So, before
deleting anything, purge writes a `RETENTION_ANCHOR` checkpoint to the
checkpoint sink for each writer's last record in each segment about to go,
carrying that record's sequence and hash, and the UTC date of the segment it
covers (`segmentDate`). Checkpoint files written before this field existed
still parse, but an anchor without a date covers nothing. If any anchor cannot be written,
nothing is deleted and purge throws. The anchors belong in the checkpoint file,
under different custody from the audit directory, like any other checkpoint.

With `--checkpoints`, the verifier accepts a writer whose first surviving
record has `previousHash` equal to an anchor's hash and sequence equal to the
anchor's sequence plus one. That writer is reported intact, with a line naming
the anchor, and the surviving records still verify end to end; the purged
records are not verified, since they no longer exist. The same chain with no
matching anchor is reported as it always was for a chain that does not start
at `GENESIS`. A segment removed by hand leaves no anchor: later segments then
report a break, and a checkpointed writer with no surviving records is
`MISSING_WRITER` (exit 5). A writer whose every record was purged is not
reported missing when an anchor covers its checkpointed sequence.

The verifier accepts an anchored start only if all of these hold:

- the anchor's `segmentDate` is at least the minimum retention before its
  `recordedAt`. A purge only deletes segments older than the retention period,
  so an anchor over a younger segment did not come from one. The minimum
  defaults to `P6M`; a deployment that runs purge with the below-minimum
  override passes its own period with `--min-retention <ISO-8601 period>`;
- the same writer has no `BOOT`, `PERIODIC` or `SHUTDOWN` checkpoint at a lower
  sequence recorded on a UTC date after the anchor's `segmentDate`. Such a
  checkpoint says the writer had not yet reached the anchored sequence by that
  date, so the date is forged;
- the first surviving record after the anchor is dated no earlier than the
  anchor's `segmentDate`.

An anchor that matches a writer's start but fails any of these is reported as
`RETENTION_ANCHOR_REJECTED`, naming the writer, at exit 2, and explains nothing.
An undated, too-recent or contradicted anchor also does not suppress
`MISSING_WRITER`.

This shows that purge was recorded; it does not prove the purge was
authorised. Whoever can append to the checkpoint file can still make a recent
deletion look like a purge when the writer has no head checkpoint recorded after
the forged date: they delete the segments, then append an anchor carrying an
old date. Regular `PERIODIC` checkpoints (task 103 schedules them) narrow that
window, because each one pins the date by which the writer had reached a
sequence. They do not close it. Anyone with that access can also make the
deletion of segments older than the retention window look legitimate. Keep
checkpoint custody separate from the audit directory's, and treat an anchor as
only as trustworthy as that custody.

### policyDecision values

The `policyDecision` field is not only `ALLOW` or `DENY`. Five forms are
recognised, written by three modules; two of them are written only by releases
before 0.4.0.

| Form | Written by | Meaning | Example |
|---|---|---|---|
| `ALLOW` | `data-prism-orchestration` (`DefaultContextOrchestrator`) | The call was answered | `ALLOW` |
| `DENY:<code>` | `data-prism-orchestration` (`DefaultContextOrchestrator`) | A call that reached the orchestrator was refused, before or after fetching, with the refusal code (for example an exhausted read budget, `DENY:SCOPE_READ_BUDGET`). A failure that is not a privacy refusal, such as a scrubber or validator fault, is `DENY:REQUEST_FAILED`; the client sees only "the request could not be completed". Adapter failures are recorded per source, and if every source fails the result is `DENY:NO_SOURCE_DATA`. A code that is not a plain upper-case token (`[A-Z][A-Z0-9_]{0,63}`), which an application-supplied scrubber or validator could throw, is recorded as `DENY:INVALID_REFUSAL_CODE`. A refusal is also marked `REFUSED` under one of two fixed keys, `<source>:<refused>` or `merged:<refused>`, with no code; dispositions for fields already scrubbed from earlier sources may also be present, and an internal error adds no `REFUSED` mark. The code is joined to the client's result by `correlationId` | `DENY:SCOPE_READ_BUDGET`, `DENY:REQUEST_FAILED` |
| `DENY:<code>` | `data-prism-mcp` (`GetEntityContextTool`, `CompareEntitySourcesTool`) | The tool refused the call before the orchestrator ran, with the refusal code: authorisation, scope, admission, or no authenticated caller. The client's result carries the bare code | `DENY:TOOL_NOT_PERMITTED`, `DENY:NO_AUTHENTICATED_CALLER`, `DENY:CALLER_RATE_LIMITED`, `DENY:APPROVAL_REQUIRED` |
| `ALLOW:<STAGE>` | `data-prism-reidentification` (`ReidentificationService`) | A re-identification step succeeded. `<STAGE>` is `REQUESTED`, `APPROVED` or `RESOLVED` | `ALLOW:REQUESTED` |
| `DENY:<code>` | `data-prism-reidentification` (`ReidentificationService`) | A re-identification step was refused, with the refusal code | `DENY:APPROVAL_EXPIRED` |
| `DENY` (plain) | Releases before 0.4.0 (the orchestrator) | A refusal or internal failure, without the code. Never written by 0.4.0; still read, and still verifies, in files written by 0.3.x | `DENY` |
| bare `<CODE>` | Releases before 0.4.0 (the MCP tools) | A tool refusal, recorded as the code alone. Never written by 0.4.0; still read, and still verifies, in files written by 0.3.x | `TOOL_NOT_PERMITTED` |

From 0.4.0 every denial is `DENY:<code>`. Classify by prefix and code, not by
an exact `DENY`. `ALLOW` or a value starting with `ALLOW:` is a success. An
empty value is unknown; it is reserved and never written today. Anything else
is a denial or failure. A consumer that tests only for an exact `DENY` misses
every `DENY:<code>`, and, when it reads files written before 0.4.0, every bare
code. Treat a value you do not recognise as a denial. The set of codes can grow
between releases.

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
java -cp <classpath> io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli /path/to/audit.log
```

or `--help` for a shorter summary.

The verifier class moved in 0.6.0: before that release it sat directly in the
`audit` package (the class name is unchanged), and the old name no longer
exists (no forwarding class).

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Intact — every writer's chain verified: no break, no structural anomaly, no in-flight tail. |
| 1 | Unreadable input — the file could not be opened or read, or the invocation was malformed. |
| 2 | Break detected — an edit or deletion was found in at least one writer's chain, a line could not be ruled out as tampering, or a retention anchor was refused (`RETENTION_ANCHOR_REJECTED`: too recent, undated, contradicted by a head checkpoint, or starting after the first surviving record's date). |
| 3 | Possibly-in-flight tail — the final record has no terminating newline; not a break. Only reported when nothing scored higher: a run with both a break and an in-flight tail exits 2, and a run with both a structural anomaly and an in-flight tail exits 4 (precedence is break, then anomaly, then in-flight tail). |
| 4 | Structural anomaly — an interrupted-write fragment, a sink-contract duplicate-sequence violation, or a writer's chain not starting at `GENESIS` immediately after another structural anomaly, which offers plausible (never certain) context for the missing head. A writer's chain not starting at `GENESIS` in any other position is a break, exit code 2, not this. Never returned together with exit code 2. |
| 5 | Checkpoint mismatch — only with `--checkpoints`: a writer's last surviving sequence is lower than its highest checkpointed sequence (`TRUNCATED_BEFORE_CHECKPOINT`), or a writer has a checkpoint past sequence 0 and no surviving records (`MISSING_WRITER`). Each is named per writer. Returned only when no break was found, and takes precedence over 3 and 4. A record whose hash differs from the checkpointed head at the same sequence is a break, exit 2. |

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

## Joining to your AI-system logs

Every tool result that the audit trail records carries that call's
`correlationId` in the result's `_meta`, under the key
`io.github.aindriub.dataprism/correlationId`. The audit record for the same call
carries the same value in its `correlationId` field, which is one of the fields
the record hash covers, for successful calls and for refusals the orchestrator
audits (`DENY:<code>`). To join your AI system's logs to this trail, store the
`correlationId` from `_meta` in your own log entry for the call, then look it up
in the audit file or directory. See
[Correlating with your AI-system logs](tools.md#correlating-with-your-ai-system-logs)
for what the id is derived from and which calls carry none.

The id is the only join key. It is random and carries no data, and Data Prism
never puts it in model-visible content (an MCP client may forward `_meta`).
This supports a deployer's own record-keeping; it does not make
the Data Prism trail a record of your AI system's inputs or outputs, which are
yours to log. A call rejected for a missing argument is not audited and has no
id to join. See [EU AI Act and GDPR Art. 9 support](eu-ai-act.md).

## Record version 3

`recordVersion` is `3` for every record written now. A version 3 record has 25
fields, the 24 of version 2 plus `externalCorrelationId` as the last one,
after `approverId`. The fields are `eventId`, `timestamp`, `principalId`,
`clientId`, `tool`, `entityType`, `subjectPseudonym`, `parameterFingerprint`,
`privacyProfile`, `scopeId`, `purpose`, `caseId`, `policyDecision`,
`sourceSystems`, `rejectedArguments`, `correlationId`, `instanceId`,
`sequence`, `previousHash`, `eventHash`, `recordVersion`, `fieldDispositions`,
`approvalId`, `approverId` and `externalCorrelationId`.

Version 3 hashes exactly what version 2 hashes, in the same order, using the
same length-prefixed encoding (each item written as its UTF-8 byte length, a
colon and the text), and appends one more item after `approverId`: the
encoding of `externalCorrelationId`. An empty value is written as `0:`. Version
3 defines no encoding of its own, and `externalCorrelationId` is not part of
the version 1 or version 2 hash, so records already written still verify
unchanged. Editing the id in a version 3 record breaks its hash, and so does
rewriting its `recordVersion` to `2` to make the id drop out of the hash.

A chain may mix versions. A writer that was upgraded part way through has
version 2 records followed by version 3 records, and the offline verifier
replays each record under the version it declares. The line's field count has
to agree with the declared version exactly (20 for version 1, 24 for version
2, 25 for version 3). `FIELD_COUNT_MISMATCH`, a break (exit code 2), covers a
line with too many fields and a full-length line that carries the wrong
version. A shorter line that could be a torn write, for example 22 fields
declaring version 3, is not a break: it is reported as an interrupted write
(exit code 4). A writer's version only increases. A record whose version is
lower than one the same writer already wrote is reported as
`VERSION_REGRESSION`, also a
break (exit code 2), because a downgrade is the way to forge a record that
avoids a field the newer version hashes. Retention does not purge past it.

### Interrupted writes and the restart terminator

A process that dies mid-write leaves a line with no newline. A writer that
resumes on that file (`FileAuditSink`, or a segment opened by
`SegmentedFileAuditSink`) checks the last byte before its first record. If the
file is non-empty and does not end in `\n`, it writes `\r\n`, fsyncs, and only
then appends. An empty, absent or newline-terminated file is not touched.
Existing bytes are never truncated or rewritten. If the tail cannot be read or
the terminator cannot be written and fsynced, opening fails with
`AUDIT_SINK_OPEN_FAILED` and nothing is appended.

A serialized record never contains a raw `\r` (it is escaped), so the verifier
reads any line that ends in one as a writer-terminated torn fragment: an
`INTERRUPTED_WRITE_FRAGMENT`, exit code 4, never parsed as a record. This
holds wherever the write was torn, including inside the event hash or the
external correlation id, and when the record was complete except for its
newline. The resumed writer's first record is on its own line and verifies as
the `GENESIS` head of its own chain. Directory mode adds the same `\r\n` to a
non-final segment that ends torn. The over-count rule above is unchanged: an
over-long line with a parseable version is still `FIELD_COUNT_MISMATCH`.

An attacker who appends `\r` to a valid line hides that record, which has the
same power as deleting it. In the middle of a writer's chain the next record
then fails its `previousHash` check, a break (exit code 2). If it was the
writer's last record, the effect is tail truncation, which
[`--checkpoints`](#external-checkpoints) reports as `TRUNCATED_BEFORE_CHECKPOINT`
(exit code 5). The marker cannot inject a field, because the line is never
parsed.

Logs written before 0.5.0 may already hold a fragment fused with a restarted
writer's first record, with no terminator between them. That is still
`FIELD_COUNT_MISMATCH`, a break, and retention stops there. One known cause is a
legacy interrupted write followed by a restart. To check, see whether the
trailing 20, 24 or 25 fields of the line parse as a `GENESIS` record of a new
`instanceId`, and whether the record after the line continues that writer.

One live writer process is assumed to append to a given file or segment
directory. A second live writer opening the same file could terminate the first
writer's in-flight line.

## External correlation id

An organisation's own transaction id can be recorded on the audit event as
`externalCorrelationId`, so an audit record can be matched to a request in the
organisation's other systems. This is separate from Data Prism's own
`correlationId`, which Data Prism generates and which is described under
[Joining to your AI-system logs](#joining-to-your-ai-system-logs). Setup is in
[configuration](configuration.md#dataprismcorrelation).

- **One source.** The id is read only from the single HTTP request header named
  by `dataprism.correlation.inbound.header`. It is never taken from a tool
  argument, a body or a second header. A repeated header is rejected.
- **Validated.** The value must pass a fixed ceiling (at most 256 characters,
  each in `[A-Za-z0-9._:/+=-]`) and then the configured pattern or, for
  `format: traceparent`, the W3C `traceparent` form, whose trace id is
  recorded. The strict default pattern admits a canonical UUID, 16 to 128 hex
  characters containing at least one letter a to f, or a W3C `traceparent`,
  all within the 256-character ceiling above. The pattern limits an id's shape,
  not its meaning, so choose one that admits generated ids only.
- **Dropped if invalid.** A rejected value is dropped and the call proceeds as
  if no id had been sent. A WARN line with the code
  `EXTERNAL_CORRELATION_ID_DROPPED` is logged and the rejected text is never
  logged.
- **Refused if required.** With `inbound.required: true`, a call with no valid
  id is refused and audited before any source is called:
  `EXTERNAL_CORRELATION_ID_REQUIRED` when the header is absent,
  `EXTERNAL_CORRELATION_ID_INVALID` when it is present but rejected.
- **Hashed.** The validated id is part of the version 3 record hash, so editing
  it is detected like an edit to any other hashed field.
- **Sent only where configured.** The id goes to a source only through
  `dataprism.correlation.outbound.header` or a source's own
  `correlation-header`. A source's `correlation-header` overrides the global
  header for that source. Setting the global outbound header sends it to every
  configured source; a source with neither receives no id. It reaches a
  source's request headers and nowhere in model-visible content.
- **Logs.** With `dataprism.correlation.mdc-key` set, the validated id is also
  placed in the SLF4J MDC for the duration of the tool call, so Data Prism's own
  log lines on that call carry it. The MDC is wired by the Spring Boot starter.
  There is no MDC overload for the stdio transport, so a library user who
  builds a stdio server themselves always runs with the MDC off.

Startup refuses a bad correlation configuration with a code before any
request is served. Besides the codes in the
[configuration reference](configuration.md#dataprismcorrelation),
`INVALID_CORRELATION_FORMAT` is raised when `inbound.format` is neither
`opaque` nor `traceparent`.

This supports a deployer's own record-keeping. It does not establish who the
caller is.

## Structured JSON output

The native hash-chained segments stay the authoritative record. A deployment
that ships audit events to a log platform can also have Data Prism render each
event as one line of JSON, with chosen field names and routing values. The
rendering only renames and reshapes. It adds no value that is not in the event,
apart from two things: the `event.outcome` derived from `policyDecision`, and
the routing constants an operator sets. The properties are under
`dataprism.audit.output.*` in
[configuration](configuration.md#output-field-names-routing-and-a-json-projection).

**Presets.** `canonical` writes each field under its own name. `ecs` writes
the names below. The mapping is total: every field is written exactly once. An
operator can override one field's path with
`dataprism.audit.output.field-names.<field>`; a dot in a path nests. For
example, to write the external correlation id as `transaction_id` instead of
`trace.id`:

```properties
dataprism.audit.output.field-preset=ecs
dataprism.audit.output.field-names.externalCorrelationId=transaction_id
```

| Canonical field | ECS path |
|---|---|
| `eventId` | `event.id` |
| `timestamp` | `@timestamp` |
| `principalId` | `user.id` |
| `clientId` | `dataprism.client_id` |
| `tool` | `event.action` |
| `entityType` | `dataprism.entity_type` |
| `subjectPseudonym` | `dataprism.subject_pseudonym` |
| `parameterFingerprint` | `dataprism.parameter_fingerprint` |
| `privacyProfile` | `dataprism.privacy_profile` |
| `scopeId` | `dataprism.scope_id` |
| `purpose` | `dataprism.purpose` |
| `caseId` | `dataprism.case_id` |
| `policyDecision` | `dataprism.policy_decision` |
| `sourceSystems` | `dataprism.source_systems` |
| `rejectedArguments` | `dataprism.rejected_arguments` |
| `correlationId` | `dataprism.correlation_id` |
| `instanceId` | `dataprism.instance_id` |
| `sequence` | `dataprism.sequence` |
| `previousHash` | `dataprism.previous_hash` |
| `eventHash` | `dataprism.event_hash` |
| `recordVersion` | `dataprism.record_version` |
| `fieldDispositions` | `dataprism.field_dispositions` |
| `approvalId` | `dataprism.approval_id` |
| `approverId` | `dataprism.approver_id` |
| `externalCorrelationId` | `trace.id` |
| (derived) | `event.outcome` |

**Derived and constant values.** `event.outcome` is derived from
`policyDecision` alone: `ALLOW` and `ALLOW:...` give `success`, an empty
decision gives `unknown`, and anything else (including `DENY:<code>`) gives
`failure`. The routing constants `event.dataset`, `data_stream.type`,
`data_stream.dataset` and `data_stream.namespace` are written exactly as the
operator configured them, and follow the Elastic data stream naming rules. A
routing path or the outcome path that collides with a mapped path is refused at
startup with `AUDIT_FIELD_MAPPING_CONFLICT`.

**The native segments remain authoritative.** The `.ndjson` projection
(`dataprism.audit.output.json-directory`) is written after the native line and
carries the same `eventHash`. It is not chained and nothing verifies it. Verify
the native segments. Do not tail them, and point a shipper at the projection
only. See [Log shipping](log-shipping.md).

**A failed projection write stops service.** If writing the `.ndjson` line
fails (a full or unwritable `json-directory`), the native event is already on
disk, and every later audited tool call is refused with
`AUDIT_PROJECTION_FAILED` until the process restarts. This is fail-closed by
design: the alternative is reusing a sequence number. Monitor the free space
and permissions of `json-directory`. A failure to purge expired `.ndjson`
segments is different: it is only logged, and does not stop the native purge or
any call.

**Sinks.** With `sink: slf4j`, a configured preset, `field-names` or routing
makes `Slf4jAuditSink` attach the mapped values to each log event as key-value
pairs. Its mapped constructor attaches the routing constants as key-value pairs
as well. With none of them set, the message carries no key-value pairs.

**Dotted keys in `fieldDispositions`.** The keys of `fieldDispositions` are
field paths such as `crm:/contacts/*/email`, and they can contain dots.
Elasticsearch expands a dotted name into nested objects, which splits such a key
into an object path and can conflict with another key that is a prefix of it.
For `dataprism.field_dispositions` in an Elastic index, use a mapping that does
not expand the keys: map the field as `flattened`, or as an object with
`enabled: false` so it is kept in the source and not indexed. Check how your
logging layer renders dotted key-value names before relying on a mapping.

**Refusal codes at startup** specific to this output:
`INVALID_AUDIT_FIELD_PRESET` (`field-preset` is neither `canonical` nor
`ecs`) and
`AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT` (`json-directory` is the audit `directory`,
inside it, or contains it). The full list is in
[configuration](configuration.md#output-field-names-routing-and-a-json-projection).

## What this does and does not prove

Read this before treating an intact report, or this file's mere existence,
as more than it is.

**What it proves.** For every record the verifier could see, in every
writer's chain, replaying the chain found no edit or deletion of any of the
hashed fields (the nineteen of version 1; for version 2 also `recordVersion`,
`fieldDispositions`, `approvalId` and `approverId`; for version 3 also
`externalCorrelationId`). Editing a record breaks its own stored hash the
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

- **Truncation of the most recent records is undetectable from the audit
  file alone.** An append-only file with its tail removed verifies perfectly
  end to end: there is nothing left in the file to disagree with. The
  demonstration above is not a corner case, it is the general shape of this
  gap. Detecting it needs an external checkpoint — a recorded head hash held
  somewhere the same actor who could truncate the file cannot also reach.
  [External checkpoints](#external-checkpoints) narrow this gap when you
  supply a checkpoint file; they do not close it, because records written
  after a writer's last checkpoint remain undetectable if deleted. A final record with no terminating
  newline is reported as "possibly in flight" (exit code 3) precisely because
  it is *not* proof of either tampering or health: it is exactly as
  consistent with an in-progress write as with a truncation caught mid-line.
  Deleting an entire boot's records — every record under one `instanceId`,
  not just its tail — has the same shape at the level of the whole writer:
  the surviving writers each still verify intact, and the report simply never
  mentions the boot whose every record is gone, because the verifier can only
  report on the writers it finds records for (demonstrated above alongside
  the restart case). A checkpoint file makes such a boot visible only if it
  recorded a checkpoint past sequence 0.
- **This is intra-writer edit and delete detection, not a guarantee against
  a capable adversary.** `AuditEventHash` is unkeyed SHA-256 over the joined
  record body. Anyone able to write to this file directly can edit or delete
  a record and then simply recompute every hash that follows it — the
  resulting chain verifies perfectly, because nothing about an unkeyed hash
  stops whoever holds write access from recomputing it. Resisting that needs
  a keyed MAC (a secret the adversary does not also have), which this release
  does not build, or a checkpoint file the adversary cannot also rewrite.
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

### External checkpoints

An `AuditRecorder` built with an `AuditCheckpointSink` writes checkpoints to a
second file, separate from the audit file: a `BOOT` checkpoint at construction
(sequence 0, `GENESIS` head; construction fails if it cannot be written),
`PERIODIC` checkpoints whenever `checkpoint()` is called, and a `SHUTDOWN`
checkpoint on `close()`. Each is one JSON line, fsynced, holding the writer's
`instanceId`, the sequence reached, the head hash and a timestamp.
`FileAuditCheckpointSink` refuses a path equal to the audit file
(`AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE`). A server built from the Spring Boot
starter calls `checkpoint()` on a schedule: `dataprism.audit.checkpoint.interval`
(default `PT5M`) sets it. The schedule runs only when a checkpoint location
(`dataprism.audit.checkpoint.file-path`) is configured; without one no PERIODIC
checkpoint is written. An application that builds `AuditRecorder` itself must
call `checkpoint()` on its own schedule.
A torn checkpoint tail is terminated before the next append: on open, if the
checkpoint file does not end in `\n`, `FileAuditCheckpointSink` writes `\r\n` and
fsyncs before its first append, and fails the open if it cannot. The torn line
is never edited and is never read back as a checkpoint. A writer whose last
checkpoint was torn is therefore verified against its previous checkpoint, so
tail-truncation coverage for that writer is reduced to that earlier point.
With `--checkpoints`, the verifier reports a checkpoint line that ends in a raw
`\r`, or a final unterminated line that is not a valid checkpoint, as the
anomaly `TORN_CHECKPOINT_LINE`: never parsed as a checkpoint, never skipped
silently, and never a break. The intact checkpoints are still used, and the
anomaly exits 4 under the existing precedence. Any other unparseable
checkpoint line is still unreadable input (exit 1).
A checkpoint file converted to CRLF line endings (git autocrlf, a copy made on
Windows) reports every line as `TORN_CHECKPOINT_LINE` and uses no checkpoints. It
exits 4, not 0, but restore LF line endings before relying on it.
`RETENTION_ANCHOR` checkpoints are written by `AuditRetention`; see
[Retention](#retention).

### Directory mode

Given a directory instead of a file, the verifier reads every
`audit-YYYY-MM-DD.log` segment in date order as one stream and replays it as
usual. A non-final segment ending in a torn write is given the same `\r\n`
terminator a resumed writer writes, and is reported as an
interrupted-write fragment rather than fused with the next segment's first
record. Byte offsets in a directory report are offsets into that concatenation.

If a checkpoint write fails, the recorder refuses every later `record(...)`
with `AuditCheckpointUnavailableException`, without advancing the chain, until
a later `checkpoint()` succeeds.

```sh
java -cp <classpath> io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli \
    /path/to/audit.log --checkpoints /path/to/checkpoints.jsonl
```

With `--checkpoints`, a writer whose last surviving sequence is below its
highest checkpointed sequence, and a writer with a checkpoint past sequence 0
and no surviving records, are each named and exit 5. The two walkthroughs
above that exit 0 (a truncated tail, a deleted boot) exit 5 once the
checkpoint file is supplied. A missing or malformed checkpoint file exits 1.

What this still does not prove:

- Records written after a writer's last checkpoint are undetectable if
  deleted: nothing external says they existed.
- A checkpoint only helps if whoever can edit the audit file cannot also edit
  the checkpoint file. Keep them under different custody. A person who can
  rewrite both can make them agree.
- Neither file resists tampering by anyone who can write to it. Checkpoints
  are unkeyed and unsigned; keyed or signed checkpoints are not built.

No sentence above, or anywhere else in this file, should be read as a claim
that the durable audit log is tamper-proof, immutable, or independently
complete. It is not any of those. It is evidence — evidence that stands up to
replay for the fields it hashes, within the boundary this section states —
and evidence is what an offline verifier can actually give.

The diagram below shows what one writer's hash chain looks like, and what
its offline verifier can and cannot tell you.

[![The audit chain: one chain per writer boot (writer-id/uuid) starts at GENESIS; the offline verifier replaying it detects an edit anywhere in the chain, including the last record, and a deletion that has later records after it, but cannot detect truncation of the tail, deletion of a whole boot's records, or recomputation by someone with write access.](assets/diagrams/audit-chain.svg)](assets/diagrams/audit-chain.svg)
