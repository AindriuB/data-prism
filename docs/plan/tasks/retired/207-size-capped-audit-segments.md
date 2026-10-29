# 207 — Cap the size of a day's audit segment, keeping one continuous chain

Renumbered from 192 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.8.0 (before the 0.9 API freeze)
**Base branch:** `origin/main` once 206 has merged
**Depends on:** 206 (both edit `AuditChainVerifier` and `AuditRetention`)
**Wave:** 5 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/sink/SegmentedFileAuditSink.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java *(segment discovery, ordering and per-day deletion only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java *(directory-mode segment discovery and ordering only, around :141-160)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCli.java *(help text only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/sink/SegmentedFileAuditSinkSizeCapTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionSizeCapTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierSizeCapTest.java *(new)*

## Goal
Time-based rotation with chain continuity already exists. Since task 102 (2026-10-06),
`SegmentedFileAuditSink` writes one `audit-YYYY-MM-DD.log` per UTC day, the writer chain runs across
segments unbroken, `AuditRetention` purges whole expired days behind `RETENTION_ANCHOR` checkpoints, and
the verifier has a directory mode (`docs/architecture.md:469-486`, `docs/audit.md` "Retention" and
"Directory mode"). What is missing is a size bound: one busy day is one unbounded file. Add an opt-in
maximum segment size within a day. Rotation must never split a record, and the chain must stay one
chain across every segment.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/sink/SegmentedFileAuditSink.java:17-158 — naming (`SEGMENT_NAME`, `segmentName`, `segmentDate`), the never-backwards date rule, per-segment `FileAuditSink`, whole-sink poisoning, and the package-private constructor `SegmentedJsonAuditSink` reuses with a `.ndjson` suffix
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java:110-160,255-290 — segment discovery by `segmentDate`, the verification before deletion, and `anchorsFor`
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:135-160 — `verifySegments` and directory-mode listing
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditMaintenance.java:97 — calls `segmentDate`; it must keep working unchanged
- docs/log-shipping.md:26 — shippers match `audit-*.log`; new names must still match that glob
- docs/plan/HISTORY-INDEX.md, row for task 102 — four attempts were needed to get anchors and dates right; read that HISTORY.md entry first

## Acceptance
- [ ] `SegmentedFileAuditSink` gains a public constructor that takes a maximum segment size in bytes. Without one, file names and behaviour are exactly as today, and every existing `SegmentedFileAuditSinkTest` case passes unmodified.
- [ ] With a maximum, the first segment of a day is `audit-YYYY-MM-DD.log`, and later ones are `audit-YYYY-MM-DD.NNNN.log` with a zero-padded index starting at `0001`. Before a write that would take the current segment past the maximum, the sink fsyncs and closes it and opens the next. A single record larger than the maximum is written whole into a fresh segment, never split. Tests cover all three cases.
- [ ] `segmentDate(Path)` returns the day for both name forms, and `null` for anything else. A new public helper orders the segments of one day by index. The JSON projection's use of the package-private constructor gets no size cap and is unchanged.
- [ ] Across a size rotation, the first record of segment `n+1` has `previousHash` equal to the `eventHash` of the last record of segment `n`, in both unkeyed and keyed mode (task 206). A test forces rotation by size and asserts this.
- [ ] The verifier's directory mode reads segments in (date, index) order. Deleting a middle segment of a day is reported as a break. A test covers this.
- [ ] `AuditRetention` treats all of a day's segments as one unit. It writes its `RETENTION_ANCHOR` for the last record of the day's last segment, and deletes either every segment of an expired day or none of them. A test with a three-segment expired day covers both outcomes, including the case where the middle segment fails to verify.
- [ ] The poisoning rule holds across a size rotation: once any write fails, every later `record` throws. A test covers a failure while opening the next segment.
- [ ] The CLI's `--help` mentions the indexed segment names. No other CLI text changes.
- [ ] `./mvnw -B -pl data-prism-core -am verify` passes.

## Out of scope
- The `dataprism.audit` property and its validation (task 211).
- Size caps for the JSON projection or the single-file `FileAuditSink` (`file-path` mode), which still never rotates.
- Keyed hashing (task 206).
- `docs/audit.md` and `docs/log-shipping.md` (task 213).

## Outcome (2026-10-27, 0.8; PR #166, tip 8459a812, wave/0.8-wave4)
A day's audit segment is capped in size and the chain stays continuous across the rotations: segments of one day are named by index, the verifier's directory mode reads them in index order, and a purge deletes a day whole and anchors it at its last segment's last record. Base: `origin/wave/0.8-wave4` (OD-W5-1 (a)).

Decisions. OD-207-1 (b): a purge finishes a partly deleted day by its recorded anchors before verifying later days. OD-207-2 (a): a partly purged day is finished only after its remaining segments verify on their own (same chain keys, never joined to the next day) with no break, structural anomaly or unterminated tail, and every writer's first remaining record starts at GENESIS or follows a recorded anchor; the anchor rule is judged on verified records. Purge also refuses at a duplicate sequence. OD-207-3 (a) and OD-207-4 (c): see the pre-existing gap below.

Trust assumption (in the `AuditRetention` Javadoc): the retention anchor store is trusted.

Review. External review found two P2s: segment indices used the default locale (fixed with `Locale.ROOT` in c3af4980, test `indexedNamesAreAsciiWhateverTheDefaultLocaleIs`), and the finish-partial-day path inferred partial deletion from sequence numbers in unverified records (fixed in 0c5ed3a8 and a60126e9 with four red-first tests). Internal review ended CLEAN.

Pre-existing gap found, not fixed here: retention builds anchors by re-parsing raw lines, including lines the verifier skips unhashed (a `\r`-terminated line, an unterminated tail), so someone with write access to the audit directory can make purge write an anchor of their choosing and erase recent records undetected. It is in v0.6.0 to v0.7.1 and on wave4; this task does not make it worse. Task 292 closes it, and task 293 gives the operator recovery from a genuine crash line. Disclosure is a 0.8.0 CHANGELOG entry only (OD-207-4 (c)).

Follow-up (Low): when a purge finishes a partly deleted day and a later day then fails verification, the `CHAIN_UNVERIFIED` message does not name the finished day.
