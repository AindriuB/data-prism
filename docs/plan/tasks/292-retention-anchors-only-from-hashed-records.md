# 292 — Build retention anchors only from records the verifier hashed

**Repo:** `.`
**Release:** 0.8.0
**Priority:** P1 (audit integrity). Present in every release since retention shipped: v0.4.0, v0.4.1, v0.5.0, v0.6.0, v0.7.0 and v0.7.1, and on `origin/wave/0.8-wave4`. Checked at the tags (planner, 2026-10-28):
- `AuditRetention.anchorsFor` and `firstUnanchoredStart` re-read raw lines with `Files.readAllLines` and `AuditRecordFormat.parse` from v0.4.0 on. Between v0.4.0 and v0.4.1 the file is byte-identical. v0.5.0 changes only `canBeShorterThanSixMonths`'s visibility and `firstBadOffset`'s anomaly test.
- The verifier skips an unterminated final chunk without hashing it (`TailAnomaly`) from v0.4.0 on.
- The `\r`-line skip and `TORN_TAIL_TERMINATOR` arrive in v0.5.0.

So in v0.4.x the unterminated-tail shapes apply (B, and D with the forged record left unterminated as the last expiring segment's tail). From v0.5.0 the `\r` shapes apply as well. The window-edge and rename shapes (rules 6 and 7) do not depend on either skip.
**Base branch:** `origin/main` once PR #163 (`wave/0.8-wave4`) has merged to `main`, the same as 291 and 247 (coordinator decision, 2026-10-27). One PR for this task, targeting `main`. 292 is not on #163's hold list. 207 (PR #166, targeting `wave/0.8-wave4`) must already be on `main` at that point. If it is not, stop and report; do not rebase onto `wave/0.8-wave4`.
**Depends on:** 207 (PR #166 rewrites the same `AuditRetention` code: `partlyPurgedDayLength`, `firstUnanchoredStart`, `firstBadOffset` and `anchorsFor`, and owns `AuditRetentionSizeCapTest.java`), 290 (both insert into `CHANGELOG.md` `[Unreleased]`)
**Wave:** 7 (filed 2026-10-27).
- Not wave 6: the only intersection with wave 6 is `CHANGELOG.md`, which 290 owns (insertions only). The audit sources and tests are disjoint from 208, 227, 246, 262, 284 and 289.
- In wave 7 the Owns are disjoint from 211 (Spring audit wiring and `docs/configuration.md`, not core audit), 228, 247 (`AuditEvent.java` and its own new test files only, not `AuditChainVerifier.java` or retention) and 256.
- 291 also inserts into `CHANGELOG.md`. By coordinator decision (2026-10-27) 292 goes first: 291 depends on 292 and is in wave 8.
- 293 (the crash-line recovery) depends on this task.
**Decisions:**
- **OD-207-3 (a), decided by the owner 2026-10-27.** Retention builds anchors and day-deletion boundaries only from records the verifier actually hashed, through a small verifier API. In an expiring day, any line that parses as a complete record but was not hashed refuses that day with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and the day is kept. A genuine crash fragment, a line that does not parse as a record, is tolerated, so purge keeps working after a real crash.
- **OD-207-4 (c), decided by the owner 2026-10-27.** Disclosure is by CHANGELOG only, with no security advisory and no patch release. The CHANGELOG Fixed entry names the affected versions, the effect in operator terms and the workaround (Acceptance, "CHANGELOG").
- **OD-292-1 (a), decided by the owner 2026-10-27.** A real crash can leave a line that parses but was never hashed. Such a line blocks purge of its day, and of every later expired day, until an operator acts. Option (b) was not chosen: tolerating such a line when it chains exactly from its writer's last hashed record. A test pins the refusal (Acceptance, "Crash line that parses"). How the operator recovers is task 293, not this task.
- **OD-292-2 (a), decided by the owner 2026-10-27.** Retention deletes only what it can link to what stays. There are two boundary rules, rules 6 and 7 below. The residual is recorded in Out of scope and in the CHANGELOG wording: if a writer never writes again, deleting its last records cannot be detected at the boundary, and periodic checkpoints are the defence.
- **OD-292-3 (a), decided by the owner 2026-10-27.** Crash-line recovery is an operator-confirmed acceptance recorded in the anchor store by a verifier CLI subcommand. That is task 293. Moving the segment aside is not the recovery.
- **Workaround wording, accepted by the owner 2026-10-27.** Set `dataprism.audit.retention` to a period longer than the oldest segment, for example `P100Y`. Keep the audit directory and `dataprism.audit.checkpoint.file-path` writable only by the server. Plain-Java embedders who never call `purge()` are unaffected.
- **207's `DUPLICATE_SEQUENCE` clause in `firstBadOffset` stays (planner, at filing).** The new rule does not subsume it. The verifier reports a duplicate at the *first* copy's offset (`primaryOffset`, `AuditChainVerifier.java:798-811`). The second copy is the unhashed line. Rule 4 refuses from the second copy. 207's clause refuses from the first, which can be in an earlier day: for example, a record forged in day D ahead of the genuine one in D+1. Keep the clause, and say in its comment that it covers the first copy and rule 4 covers the second.

## Goal
`AuditRetention` re-parses raw segment lines with `Files.readAllLines`. That strips a trailing `\r` and reads an unterminated tail. So the anchor it records, and the per-writer positions it bases deletion on, can come from lines the verifier skipped without hashing. It also never checks that what it deletes links to what it keeps, or that a segment's records belong to the day in its file name.

An attacker who can write to the audit directory, but holds neither the chain key nor the anchor store, can therefore make purge record an anchor of their choosing, or delete records the retention period still covers, with no trace, in plain and in keyed mode.

After this task:
- every chain position retention uses comes from the verifier's own replay;
- an expiring day that holds an unhashed complete record is kept;
- a day whose writers' next records do not link to it is kept;
- a day holding a record dated after the file's own day is kept.

## The bug, as filed (checked at PR #166's head, 8459a812; the verifier is unchanged since a60126e9)
- `AuditRetention.anchorsFor` (:422-452), `partlyPurgedDayLength` (:286-353) and `firstUnanchoredStart` (:370-401) each read lines with `Files.readAllLines` and `AuditRecordFormat.parse`. `firstBadOffset` (:403-420) looks only at breaks and anomalies.
- `AuditChainVerifier` deliberately skips two line shapes without hashing them:
  - a line ending in `\r`, reported as `INTERRUPTED_WRITE_FRAGMENT` and never parsed (:581-593);
  - an unterminated final chunk, reported as `tail()` and never parsed (:439-447).

  `join` (:178-194) appends `FileAuditSink.TORN_TAIL_TERMINATOR` (`"\r\n"`) to a non-final segment that ends without `\n`, so inside the concatenation that segment's torn tail becomes a `\r` line.
- **Forged anchor (shape D).** Writer w has seq 1-3 on 2026-03-01 (D1), 4-6 on 2026-09-05 (not expired) and 7 on 2026-09-08. Now is 2026-09-10. Delete the 09-05 segments, then append `serialize(record 6) + "\r\n"` to D1's last segment. `purge()` returns OK, deletes D1 and records an anchor at {6, hash6}. `AuditChainVerifier.verify(dir, checkpoints, P6M, keys, false)` then reports no break, and records 4-6 are gone undetected.
- **Edited last record.** Change ALLOW to DENY in D1's last record and end it with `\r\n`, or with no newline at all. Purge deletes the tampered day, either on its own (single-day shape) or with the link break that follows blamed on D2's first record (joined shape).
- **Window edge (reviewer probe `hideLastExpiringRecordAsFragment`).** w has seq 1-3 on 03-01 and 4 on 09-09. Record 3 is cut to its first 60 characters, ended `\r\n` or left unterminated. Purge returns OK and deletes D1, because nothing checks that seq 4 chains from what D1 keeps as its last hashed record.
- **Rename (reviewer probe `renameRecentSegmentIntoExpiredDay`).** In keyed mode, w has seq 1-3 on 03-01, 4-6 on 09-05 and 7 on 09-08. Rename `audit-2026-09-05.log` to `audit-2026-03-01.0001.log`. Purge deletes both D1 files, and the full verify afterwards is clean. Records 4-6 were inside the retention period.
- Sources for the tests: the coordinator's probes (`ZzProbeTest.java`, `BaseProbe.java`, shapes A, A2, B, C, C2, D, E) and the reviewer's (`Zz292Test.java`, `Zz292bTest.java`). They are not repo files. Rewrite them as named tests here.

## The rule
1. **A hashed record** is one the replay recomputed and matched. In `processLine` that is the path that reaches `writer.lastHash = event.eventHash()` at :709. Hashed positions, and the kind of a writer's first break, come from the same replay whose result is reported for that writer in `verifyWithKeys` (:337-406):
   - the key that verified the writer;
   - for a writer broken under every key, the replay whose break is reported (the "furthest" replay at :390-403);
   - the unkeyed replay where `allowUnkeyedWriters` accepted the writer.

   These are not hashed: a record after a writer's break, a duplicate sequence's second copy, a `\r` line, and the unterminated tail.
2. **Verifier API, on a sibling result type.** `VerificationReport` is a public record. Its existing constructors and accessors, and the existing `verifySegments` overloads, stay unchanged. `AuditRetentionBreakParityTest.java:105` builds one with the 4-argument constructor and is not in this task's Owns. Add a new entry point beside `verifySegments` that returns a new public type holding the `VerificationReport` and, per expiring segment index:
   - per writer, the first hashed record (sequence, `previousHash`, byte offset) and the last hashed record (sequence, `eventHash`, byte offset);
   - the latest UTC date among the segment's hashed record timestamps.

   It also gives, for every broken writer, its last hashed record before the break and whether the break is a link failure (`previousHash` does not match, :688-694) or a content failure (`eventHash` does not recompute, :700-706). It also gives the lowest byte offset of an **unhashed complete record**, or none. An unhashed complete record is a line, or the unterminated tail, that parses with `AuditRecordFormat.parse` once exactly one trailing `\r` is removed, but was not hashed.

   The result is bounded by segments × writers. The anomalies, `tail()`, `hasBreak()` and `hasStructuralAnomaly()` of the report it holds are identical to `verifySegments`'s.
3. **Retention uses only that API.** `anchorsFor`, the last-remaining positions in `partlyPurgedDayLength` and `firstUnanchoredStart` read their sequences, hashes and positions from rule 2. `AuditRetention.java` no longer calls `Files.readAllLines` or `AuditRecordFormat.parse`. Each anchor is the day's last hashed record for that writer.
4. **An unhashed complete record refuses its day.** `firstBadOffset` also returns rule 2's lowest unhashed-complete offset. The normal path refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` from that segment's day, and keeps that day and every later expired segment, as it does for a break. The partial-day path does not finish a prefix that contains one.
5. **A crash fragment is tolerated.** Three facts make a real torn write recognisable:
   - `FileAuditSink.recordLine` (:150-164) writes `serialize(event) + "\n"` in one buffer and poisons the sink on any failure.
   - `AuditRecorder` gives every boot a fresh `instanceId` (:110).
   - Serialised records escape CR.

   So a real torn write is a prefix of one record's line and the last line its instance ever writes. It ends with nothing (the segment was never reopened) or with the `"\r\n"` that `terminateTornTail` (:98-120) or `join` adds, and the only raw `\r` in it is the final one. A line that does not parse once one trailing `\r` is removed is not refused by this task. A line the verifier already counts as a break (`UNPARSEABLE_RECORD`, `FIELD_COUNT_MISMATCH`) is still refused, as today.
6. **Link across each expiring day's end (OD-292-2 (a), rule 1).** For every writer with hashed records in an expiring day D, that writer's next record after D, if any, must have `previousHash` equal to D's last hashed `eventHash` for that writer. The check is on `previousHash` only, which is the only link the verifier checks.
   - On the partial-day path, the comparison is against that writer's recorded anchor for D (`headHash`), because the day's tail is already gone.
   - The next record may sit in a later expiring day or in a retained segment.
   - If it does not link, refuse D with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, naming D's segment that holds the writer's last hashed record. Keep D and every later expired segment.

   This rule also gives the attribution: a link break detected at D+1's first record refuses D, not only D+1. A content break is attributed to its own record. Because a rule-5 fragment is always its instance's last line, no next record follows it, so this rule never refuses a day for a real crash.

   **Reading cost.** Read only boundary records, not the whole retained window. In segment order after D, scan only until every expiring writer has its next record, then stop. A writer's next record is its first newline-terminated line that parses as that writer's record once one trailing `\r` is removed, so a `\r` copy cannot be stepped over. An unterminated tail is not a next record, because it may be in flight in today's segment. The scan goes through a verifier helper, which keeps rule 3's grep clean. A writer with no later record makes the scan reach the last segment. That is the residual case below, and the PR states the cost it measured.
7. **Dates must match (OD-292-2 (a), rule 2).** Refuse a day with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and keep it and every later expired segment, if any segment's latest hashed timestamp date (rule 2) is later than the date in that segment's file name. `SegmentedFileAuditSink.record` (:174-183) only ever moves the segment date forward. A record's UTC date is therefore never later than its segment's date. It can be earlier, after the clock steps back.

**Residual (OD-292-2 (a)).** If a writer never writes again, its last records can be deleted before a purge, by a clean line-boundary cut that leaves no fragment, and rule 6 has no next record to compare. Rules 1-7 cannot detect that. Periodic checkpoints (`dataprism.audit.checkpoint.interval`), compared by the full verifier, are the defence.

**OD-292-1 (a) in practice.** A real crash can produce an unhashed line that *parses*. The cases, by where the cut falls in a v3 record (25 fields):
- every byte but the `\n`, later ended with `\r\n` or left unterminated: parses, so rule 4 refuses;
- a cut inside `raw[24]`, the last field: 25 fields, parses, so rule 4 refuses;
- a cut inside `raw[19]`: 20 fields, parses as a v1 record, so rule 4 refuses;
- a cut inside `raw[23]`: 24 fields with a v3 `recordVersion`, so `FIELD_COUNT_MISMATCH`, a break that is refused today and still is;
- any other cut: does not parse, so it is a tolerated fragment.

Each refused case keeps its day, `dataprism.audit.retention.unverified` fires, and every later expired day is kept too, until an operator acts through 293.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java:150-255 — `purge`: the verified prefix, the same-day rewind at :215-219, anchoring before deletion
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java:286-353 — the partial-day path. The "known gap" wording at :42-43, :283 and :335-337 becomes untrue with this task and must be reworded.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:139-194 — `verifySegments`, `join` and `starts`
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:310-409 — the per-writer reported replay under keys
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:575-711 — `processLine`: which paths hash and which skip
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/sink/SegmentedFileAuditSink.java:174-183 — the date only moves forward
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/sink/FileAuditSink.java:72,98-164 — the terminator, `terminateTornTail` and the single-buffer write
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java:110 — the per-boot `instanceId`
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionSizeCapTest.java:175-215,444-462 — `writeMulti`, `purgeFailingOn` and the test this task tightens
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionDefectsTest.java:194 — a non-parsing torn fragment, checked through `verify` only (it does not run a purge)
- docs/plan/HISTORY-INDEX.md, the row for task 102 — anchors and dates needed four attempts; read that HISTORY.md entry first

## Owns
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java *(`purge`'s refusal boundary, `partlyPurgedDayLength`, `firstUnanchoredStart`, `firstBadOffset`, `anchorsFor`, the two boundary rules, and the class and method Javadoc that describe them)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java *(the new API only:*
  - *the new entry point, its public result type and nested records;*
  - *the boundary-record helper;*
  - *the bookkeeping in `WriterState`, `processLine`, `replay` and `verifyWithKeys` that fills them.*

  *No change to `VerificationReport`, anomalies, breaks, `tail()`, messages or exit behaviour.)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionHashedAnchorsTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionBoundaryTest.java *(new: rules 6 and 7)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierHashedRecordsTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionSizeCapTest.java *(`aTamperedUnterminatedLastRecordInAPartlyDeletedPrefixIsRefusedAndKept` only: its final assertions and its "known gap" comment)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Fixed entry)*

## Acceptance
**Fails first.** Each test below fails on the base (`main` after PR #163, carrying 207) and passes after the change. The exception is the regression guard "A real crash fragment still purges", which may pass on the base too, and the PR says so. Every test runs in plain mode and in keyed mode (`AuditChainMode.keyed(key)` on the writer, the same key passed to `AuditRetention`'s seven-argument constructor, `allowUnkeyedWriters=false`). If a shape does not fail first in one mode, stop and report. The PR lists each test with its failing assertion on the base.

Every test class here also checks after each test that every `RETENTION_ANCHOR` recorded during the test equals rule 2's last hashed record for its day and writer, in sequence and hash.

- [ ] **Forged anchor (shape D).** The repro above, in two variants: the forged line ended `\r\n`, and left unterminated as the last expiring segment's tail (the v0.4.x form). `purge()` throws `AUDIT_RETENTION_CHAIN_UNVERIFIED`. Every D1 segment remains. No anchor with sequence 6 or hash6 is recorded. `AuditChainVerifier.verify(dir, checkpoints, P6M, keys, false).hasBreak()` is true.
- [ ] **Edited last record, `\r\n`, single expiring day (shape A2).** ALLOW to DENY in D1's last record, ended `\r\n`, with only D1 expired. The retained record on 09-09 is written by a *different* writer, so w never writes again and rule 6 has nothing to compare. Only rule 4 can refuse here, which mutants (i) and (ii) depend on. The purge refuses and keeps every D1 segment.
- [ ] **Edited last record, `\r\n`, next day also expiring (shape A).** The same edit, with D2 (2026-03-02) also expired. The refusal message names a D1 segment. Every D1 and D2 segment remains after a first run and after a second run with the same anchor store. There is a two-key variant: keys `[k0, k1]` passed to retention, the writer under `k1`. It asserts the same refusal and the after-each anchor check, so it pins rule 1's choice of replay.
- [ ] **Edited last record, no newline, as the final expiring segment (shape B).** The retained record is by a different writer, as in A2. The purge refuses and keeps every D1 segment.
- [ ] **Duplicate-sequence line.** A copy of a D1 record, edited, appended to D1's last segment as a newline-terminated line (shape E `appendDupForgedEnd`). The purge refuses and keeps every D1 segment.
- [ ] **Attribution.** D1's last record is replaced by a prefix of itself that does not parse, ended `\r\n`, and D2 is also expired. The purge refuses, the message names D1, and every D1 segment remains. This shape is not a real crash, because D2's record continues the same instance. There is a two-key variant as for shape A.
- [ ] **Window edge (`hideLastExpiringRecordAsFragment`).** w has seq 1-3 on 03-01 and seq 4 on 09-09, and record 3 is cut to its first 60 characters. Two variants: ended `\r\n`, and unterminated. The purge refuses, the message names D1, and D1 remains.
- [ ] **Rename (`renameRecentSegmentIntoExpiredDay`).** The repro above. The purge refuses. `audit-2026-03-01.log` and `audit-2026-03-01.0001.log` both remain. No anchor is recorded for D1.
- [ ] **Partial-day path (shapes C, C2).** A prefix of D1 left by a failed delete, then tampered (an unterminated edited last record, or one ended `\r\n`), is not finished. Every remaining D1 segment is kept. A clean prefix whose writer's next record chains from the recorded anchor is still finished; 207's existing partial-day tests cover this unchanged.
- [ ] **Crash line that parses (OD-292-1 (a)).** In an expired day, a writer instance's last line is not hashed but parses. Three variants:
  - its next record complete except for the final `\n`, ended `\r\n` (as `terminateTornTail` leaves it);
  - the same, unterminated as the day's last segment;
  - a v3 record cut inside `raw[19]`, ended `\r\n`.

  No later record of that instance exists, and a later expired day has records from another instance. `purge()` throws `AUDIT_RETENTION_CHAIN_UNVERIFIED` naming that day's segment. That day and the later expired day are both kept.
- [ ] **A real crash fragment still purges.** In an expired day, an instance's last line is a strict prefix of its next record, cut in an early field so it does not parse. Two variants: ended `\r\n`, and unterminated as the day's last segment. A different instance writes on a later expired day. The purge returns normally, deletes the day and records one anchor per writer at that writer's last hashed record. A v3 record cut inside `raw[23]` (`FIELD_COUNT_MISMATCH`) is pinned as refused: it is refused today and still is.
- [ ] **Clock stepped back.** A record whose timestamp date is earlier than its segment's date (the sink's forward-only rule) does not refuse its day under rule 7.
- [ ] **Verifier API.** `AuditChainVerifierHashedRecordsTest` pins rule 2 for each of these:
  - two writers across three segments;
  - a writer broken mid-segment, by a link failure and by a content failure (no hashed entry after the break, and the last hashed record before it and the break kind are exposed);
  - a keyed writer verified under the second of two keys;
  - a writer broken under every key (the positions come from the reported replay);
  - the lowest unhashed-complete offset for each unhashed shape: `\r` line, unterminated tail, duplicate second copy;
  - none for a fragment that does not parse.

  On every existing `AuditChainVerifierTest` and `AuditChainVerifierSizeCapTest` input, the held report's `anomalies()`, `tail()`, `hasBreak()` and `hasStructuralAnomaly()` equal those of `verifySegments`.
- [ ] `grep -nE 'readAllLines|AuditRecordFormat\.parse' data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java` prints nothing.
- [ ] The "known gap" sentences in `AuditRetention`'s Javadoc and comments are removed or replaced with what is now true, including the residual above and OD-292-1 (a).

**Mutation proofs.** Make each mutation by hand, run the new tests, record the result in the PR description, then revert:
- [ ] (i) Rule 4 off **and** anchors built from raw lines (the base's `readAllLines` loop), together. Shape A2's after-each anchor check fails, in both modes: the raw anchor is {3, stored hash3}, the last hashed record is {2, hash2}. With rule 4 off alone, the anchor check holds and only the refusal assertion fails. That is why this mutant is combined. Shape D cannot serve here, because rule 6 refuses it too and no anchor is recorded.
- [ ] (ii) Rule 4 removed: shapes A2 and B and the crash-line-that-parses test fail.
- [ ] (iii) Rule 6 removed: the attribution test and both window-edge variants fail.
- [ ] (iv) Rule 7 removed: the rename test fails, in both modes.
- [ ] (v) The rule changed to "refuse any unhashed line", whether or not it parses: the real-crash test fails.

**Existing tests.**
- [ ] Every existing test in `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**` passes with unchanged expectations, except those under "Changed expectations". The PR lists each one it changes, with the old and new assertion. An expectation change not on that list is a stop-and-report, not a fix.

**Changed expectations.**
- [ ] `AuditRetentionSizeCapTest.aTamperedUnterminatedLastRecordInAPartlyDeletedPrefixIsRefusedAndKept` (added by 207 in a60126e9). Today it asserts only that the purge throws and that `audit-2026-03-02.log` and `audit-2026-09-09.log` remain. D1 is purged today, because once the normal path joins D1 and D2 the tampered unterminated line becomes a `\r` line and is skipped unhashed. The tightened test also asserts that every remaining D1 segment, `audit-2026-03-01.log` and `audit-2026-03-01.0001.log`, still exists. The "known gap" comment at :459-460 is replaced.

**CHANGELOG.**
- [ ] One entry under `[Unreleased]` → `### Fixed` (the 0.8.0 section), written for operators. It:
  - names the affected versions, 0.4.0 through 0.7.1;
  - states the effect: anyone with write access to the audit directory could make a retention purge erase audit records while verification still passed, including records that were still inside the retention period;
  - says what 0.8.0 does instead, claiming no more than rules 1-7. A day is deleted only when every record in it that parses was verified, each writer's next record after the day links to the day's last verified record, and no record is dated after its file's day. Otherwise purge refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` and keeps the day.
  - states the residual: if a writer never writes again, deleting its last records before a purge is not detected by purge, and periodic checkpoints (`dataprism.audit.checkpoint.interval`) are the defence;
  - gives the workaround for anyone staying on an affected version, in the owner-accepted wording: set `dataprism.audit.retention` to a period longer than the oldest segment, for example `P100Y`, and keep the audit directory and `dataprism.audit.checkpoint.file-path` writable only by the server. Plain-Java embedders who never call `purge()` are unaffected. (No affected version has an on/off switch for the purge. It runs whenever `dataprism.audit.directory` is set, `AuditWiring.java:89-97` at v0.7.1.)
  - says that a crash can leave a complete-looking record that was never verified. Purge of that day, and of every later expired day, then stops with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and the `dataprism.audit.retention.unverified` metric fires until the operator acts. It names no recovery command; 293 adds its own entry for that. It does not tell the operator to move or edit segments.

## Out of scope
- Crash-line recovery: the CLI acceptance, its checkpoint kind and retention's tolerance of an accepted line (293).
- The residual of OD-292-2 (a): deleting a never-again writer's last records is not detected by purge. The defence is periodic checkpoints, which the full verifier compares; nothing here changes them.
- Advisory text of any kind (OD-207-4 (c)).
- How the verifier reports fragments, tails or duplicates: anomaly types, messages, `isBreak()`, CLI output and exit codes. This task adds an API only.
- OD-292-1 (b), which was not chosen: any tolerance rule for a crash line that parses.
- `JsonAuditRetention`. It writes no anchors and verifies nothing (`JsonAuditRetention.java:28`).
- The `AuditSink` and `FileAuditSink` write path, and making a torn write impossible.
- `docs/audit.md` (213), `docs/configuration.md` (211 owns the `dataprism.audit` rows in wave 7), and Spring wiring (`AuditWiring`, `AuditMaintenance`, metrics).
- 207's segment discovery, ordering and per-day deletion, other than the refusal boundary described here.
