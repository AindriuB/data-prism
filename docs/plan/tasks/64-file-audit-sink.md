# 64 — Add a durable append-only FileAuditSink

**Repo:** .
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/FileAuditSink.java (new)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecordFormat.java (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/FileAuditSinkTest.java (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditRecordFormatTest.java (new)

## Goal
The hash chain exists and is exercised, but `Slf4jAuditSink` is the only sink,
so nothing is durable and nothing can be verified later. Add a file sink that
appends one record per event and forces it to disk before returning, plus the
single record format both the sink and task 66's verifier read.

## Context
- `data-prism-core/.../audit/Slf4jAuditSink.java:17-28` — the only sink today and
  the field set it emits.
- `data-prism-core/.../audit/AuditEvent.java` — the record to serialise, including
  `instanceId`, `sequence`, `previousHash`, `eventHash`.
- `docs/architecture.md:277-285` — "no vendor client in core". Plain Java IO is
  not a vendor client, so this belongs in `data-prism-core` beside `Slf4jAuditSink`.
- `docs/conventions.md`, "Privacy rules a diff must satisfy" — no value read from
  a source payload may reach this file; only decisions, actor identifiers and
  pseudonyms, exactly as the SLF4J sink already emits.

## Settled design — implement, do not re-open
- One file, opened append-only, never rotated. Rotation is operational, not a
  correctness property, and is explicitly out of scope for this release.
- fsync per record by default. An operator-configurable batching option is
  acceptable, but per-record durability is the default and the documented one.
- Write-then-return: `record` returns only after the bytes are durable, so a
  throwing sink (task 63) means nothing was written.

## Acceptance
- [ ] `AuditRecordFormat` serialises one `AuditEvent` to a single UTF-8 line
      terminated by `\n`, and parses that line back to the same component values.
      The format carries every `AuditEvent` component including `instanceId`,
      `sequence`, `previousHash` and `eventHash`; a round-trip test asserts
      component-by-component equality. Task 66's verifier and task 65's scan both
      read this and nothing else.
- [ ] The format escapes or encodes any newline in a component value so one record
      is always exactly one line; a test feeds a component containing `\n` and
      asserts the written file still has one line and parses back identically.
- [ ] `FileAuditSink` opens its target with append semantics and creates it if
      absent; a test writes through two sink instances over the same path in
      sequence and asserts both sets of records are present in order.
- [ ] A test asserts the file content is durable per record: after `record`
      returns, reading the file through a fresh channel shows the record, with no
      flush or close by the test.
- [ ] Constructing the sink on a path that cannot be opened for append throws with
      a stable code and a message naming the path — never the event.
- [ ] An IO failure during `record` propagates out of `record` rather than being
      logged and swallowed (`docs/conventions.md`, "Errors"), so task 63's
      fail-closed guarantee holds for this sink.
- [ ] No rotation, size cap, retention or compression code is added.
- [ ] `mvn -pl data-prism-core -am test` passes.

## Out of scope
- `AuditRecorder.java` (task 63 owns it) and `Slf4jAuditSink.java`.
- Spring wiring of `dataprism.audit.sink: hash-chained` (task 67).
- The verifier (task 66), its PII scan (task 65) and documentation (task 62).
