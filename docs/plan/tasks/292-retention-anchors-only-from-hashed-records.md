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
- **OD-292-4 (b), decided by the owner 2026-10-28** (re-decided; the first answer, (a), rested on a false premise, because every restart leaves a writer that never writes again). Rule 6's boundary scan stops for a writer when the anchor store holds a `SHUTDOWN` checkpoint for that instance at the day's last hashed record. Without one, after a crash for instance, the scan goes on to the end. A `SHUTDOWN` that contradicts what retention reads refuses the day (rule 6, "Reading cost and the SHUTDOWN stop"). The exact stop condition is OD-292-6 (a)'s.
- **OD-292-6 (a), decided by the owner 2026-10-28.** A `SHUTDOWN` is a lower bound, not an exact match. A `SHUTDOWN` can legitimately sit below a hashed record on disk:
  - record N+1 is written durably, then the projection or the fsync throws (`TeeAuditSink.java:39-55`, `FileAuditSink.recordLine` :156-163);
  - the recorder rolls `sequence` back to N (`AuditRecorder.java:221-231`);
  - the sink is poisoned;
  - `close()` writes `SHUTDOWN{N, hashN}`.

  Rule 6 therefore stops for a writer when D's last hashed sequence is at or above the `SHUTDOWN`'s, and every hashed record of that instance above the `SHUTDOWN`'s sequence chains, without a break, from its `headHash`. When an instance has more than one `SHUTDOWN` (close, record, close), the one with the highest sequence is used. On the partial-day path the same rule is applied against the writer's recorded anchor for D. The follow-up, that `AuditRecorder` should refuse any `record()` after `close()`, is for the scribe to file.
- **OD-292-5 (a), decided by the owner 2026-10-28.** A forged record placed after a `SHUTDOWN`, beyond where the scan stops, is accepted for 292, because purge does not read past the stop. Recorded in rule 6 and Out of scope. The follow-up, a verifier finding for any record after its instance's `SHUTDOWN`, is for the scribe to file. No code change in 292.
- **OD-292-7 (a), decided by the owner 2026-10-28** (external review P2). When `L.sequence < S.sequence`, a next record that links to L does not resolve the writer. The scan goes on for that writer, in segment order, until its record at `S.sequence` appears, and that record's `eventHash` must equal `S.headHash`. It must also be a complete line (OD-292-8 (a)). If the scan reaches the last segment without it, or the hash differs, or the line is not complete, D is refused with the `SHUTDOWN`-citing refusal. Records between the next record and that record are not link-checked one by one; the full verifier does that. Cost: such a writer is read up to its shutdown, at most what a writer with no `SHUTDOWN` already costs. The case it closes: expired D1 holds seq 1-3, retained D2 held 4-6, the store holds `SHUTDOWN{6, h6}`, and records 5-6 are removed. Record 4 still links to D1, so under the earlier wording D1 purged despite the deleted tail (rule 6, step 3; Acceptance, "Deleted tail after a linking next record").
- **OD-292-8 (a), decided by the owner 2026-10-28.** The record at `S.sequence` must end in `\n` with no `\r` before it. Otherwise D is refused with the `SHUTDOWN`-citing refusal, even when its `eventHash` equals `S.headHash`. Without this, an attacker could delete the tail and leave a `\r`-terminated copy of the S record in a retained segment: purge would accept it, and the full verifier reports a `\r` line as a tolerated fragment, not a break. A genuine committed record never ends in `\r`, so no real shutdown is refused (rule 6, step 3; Acceptance, "Deleted tail after a linking next record", the "`\r` copy" variant, and mutant (iv-j)).
- **OD-292-9 (a), decided by the owner 2026-10-28, under OD-207-4 (c).** The CHANGELOG Fixed entry also discloses the whole-writer residual (Out of scope, "Whole-writer deletion"), because under OD-207-4 (c) the CHANGELOG is the only place it is disclosed. The entry says four things:
  - purge still deletes an expiring day from which every line of a writer was removed;
  - the full verifier reports this afterwards, except when the writer's highest checkpoint is `BOOT` or is covered by a retention anchor;
  - the behaviour has been present since 0.4.0;
  - the defence is an audit directory writable only by the server.

  The follow-up in Out of scope is unchanged (Acceptance, "CHANGELOG").
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

## The bug, as filed (checked at PR #166's head, 8459a812, and unchanged at `origin/wave/0.8-wave4` 0f712c2a, which holds 207; `origin/main` is still 5887e3b3 and does not)
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
   - the unkeyed replay where `allowUnkeyedWriters` accepted the writer;
   - the forger branch (:384-389): a writer that verifies only unkeyed when a key was required, with `allowUnkeyedWriters` false. Its hashed set is empty, because no record verified under a required key, and its break kind is content;
   - the not-broken, nothing-recomputed branch (:348-357), for example a lone non-GENESIS start: its hashed set is empty.

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

   So a real torn write is a prefix of one record's line and the last line its instance ever writes. It ends with nothing (the segment was never reopened) or with the `"\r\n"` that `terminateTornTail` (:98-120) or `join` adds, and the only raw `\r` in it is the final one. A line that does not parse once one trailing `\r` is removed is not refused by this task. A torn line never reaches the parser's break classification. It is either `\r`-terminated, which is skipped at :581-593, or the unterminated tail, which is skipped at :439-447. So a torn cut anywhere is either an unhashed complete record (rule 4) or a tolerated fragment, never `FIELD_COUNT_MISMATCH` or `UNPARSEABLE_RECORD`. Those breaks come only from newline-terminated lines without a `\r`, and they are still refused, as today.
6. **Link across each expiring day's end (OD-292-2 (a), rule 1).** For every writer with hashed records in an expiring day D, that writer's next record after D, if any, must have `previousHash` equal to D's last hashed `eventHash` for that writer. The check is on `previousHash` only, which is the only link the verifier checks.
   - On the partial-day path, the comparison is against that writer's recorded anchor for D (`headHash`), because the day's tail is already gone.
   - The next record may sit in a later expiring day or in a retained segment.
   - If it does not link, refuse D with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, naming D's segment that holds the writer's last hashed record. Keep D and every later expired segment.

   This rule also gives the attribution: a link break detected at D+1's first record refuses D, not only D+1. A content break is attributed to its own record. Because a rule-5 fragment is always its instance's last line, no next record follows it, so this rule never refuses a day for a real crash.

   **Reading cost and the SHUTDOWN stop (OD-292-4 (b)).** Read only boundary records, not the whole retained window.
   - **Scan order.** Go in segment order after D. A writer's next record is its first newline-terminated line that parses as that writer's record once one trailing `\r` is removed, so a `\r` copy cannot be stepped over. An unterminated tail is not a next record, because it may be in flight in today's segment. The scan goes through a verifier helper, which keeps rule 3's grep clean.
   - **Terms.** For each expiring writer:
     - **L** is D's last hashed record for that writer (sequence, `eventHash`). On the partial-day path it is the writer's recorded anchor for D (`sequence`, `headHash`), because the day's tail is already gone.
     - **S** is the instance's `SHUTDOWN` checkpoint with the highest sequence, if any (OD-292-6 (a)). No existing retention test writes a `SHUTDOWN`: their recorders have no checkpoint sink, or are never closed into one. Every `SHUTDOWN` case below is therefore new. `AuditRecorder.close` (:126-132) writes `SHUTDOWN` with the instance's `instanceId`, its current `sequence` and the last committed hash as `headHash`.
   - **The order of the checks.** Each expiring writer is checked in this order. **What retention reads** means two things: the expiring segments, which are always read in full, and any retained segment the scan opens for some writer. (a) looks for the next record, step 3 for the S record, and (c) for records beyond the chain, across everything retention reads. That includes a segment opened for another writer after this one was resolved by (b). All three are evaluated once the scan has stopped. (a)'s link test applies to the next record only, and step 3's hash test to the S record only, not to every later record.
     1. **(a) The next record.** If the writer's next record after D appears in what retention reads, it must have `previousHash` equal to L's hash. If it does not, refuse D with rule 6's link refusal. If it does:
        - with no S, or with `L.sequence >= S.sequence`, the writer is resolved;
        - with `L.sequence < S.sequence`, the writer is not yet resolved, and step 3 goes on to the S record (OD-292-7 (a)). A writer that spans D and a later day and then closes cleanly is this case.
     2. **(b) The `SHUTDOWN` stop**, only when no next record has been seen and `L.sequence >= S.sequence`. If the sequences are equal, L's hash must equal `S.headHash`. Every hashed record of the instance above `S.sequence`, up to L, must chain without a break from `S.headHash`; the first has `previousHash == S.headHash`. On the partial-day path, L is the anchor and the chain check covers the hashed records that remain. If this passes, the writer is resolved without opening any further segment for it. A record above S on disk is the legitimate rolled-back N+1 case. If it fails, refuse D.
     3. **Keep scanning** for a writer that neither (a) nor (b) has resolved. Go on in segment order:
        - **Until the next record**, when none has been seen and either there is no S, or `L.sequence < S.sequence`. When it appears, apply (a).
        - **Then until the S record**, when the next record has linked and `L.sequence < S.sequence` (OD-292-7 (a)). The **S record** is the writer's first line, from the next record on, that is newline-terminated and parses as that writer's record at `S.sequence` once one trailing `\r` is removed. The next record is itself the S record when its sequence is `S.sequence`. The S record must end in `\n` with no `\r` before it (OD-292-8 (a)), and its `eventHash` must equal `S.headHash`. If both hold, the writer is resolved. If either fails, refuse D. A genuine S record is always a complete line: the recorder commits a record before `close()` writes its sequence into a `SHUTDOWN`, and only a torn line ends in `\r`. So a `\r` copy at `S.sequence` is refused, not stepped over. Records between the next record and the S record are not link-checked one by one; they stay on disk, and the full verifier checks them.

        If the scan reaches the last segment with the writer still unresolved:
        - with no S and no next record, the writer is resolved: this is the residual case, for example after a crash;
        - with `L.sequence < S.sequence`, refuse D, whether or not a next record linked. The instance shut down after records that are no longer there, so the tail of a cleanly closed writer was deleted.
     4. **(c) Beyond the chain.** This check applies only to a writer with an S.
        - **The chain** is followed from the higher of L and S: from L's hash when `L.sequence >= S.sequence`, and from `S.headHash` otherwise. It extends to the next record that (a) found, if that record is above the starting point. When `L.sequence < S.sequence`, for a genuine history the next record is at or below `S.sequence`, so the chain starts at the S record that step 3 confirmed and does not extend to the next record.
        - **Refusal.** Refuse D if what retention reads holds a record of the instance, with a sequence above `max(L.sequence, S.sequence)`, that is not on that chain.
        - **Why this is fail-closed.** It refuses a second record written after `close()`. `AuditRecorder` accepts such records today, but a real recorder writes none once its sink is closed. Refusing the case is deliberate, and it falls under the OD-292-6 (a) follow-up: `AuditRecorder` refusing `record()` after `close()`.
   - **More than one `SHUTDOWN` at the highest sequence.** If two `SHUTDOWN`s for one instance share the highest sequence but have different `headHash` values, refuse D (fail closed). A real recorder cannot produce this: with no record between two `close()` calls, both write the same head.
   - **The scan stops** as soon as every expiring writer is resolved or D is refused. A writer with no `SHUTDOWN` keeps the scan going until its next record is found or the last segment is reached, as before. A writer with `L.sequence < S.sequence` keeps it going until its S record is found or the last segment is reached (OD-292-7 (a)).
   - **Refusals that cite the `SHUTDOWN`:** (b) failing; step 3's S record failing (it ends in `\r`, or its `eventHash` differs from `S.headHash`); the `L.sequence < S.sequence` end-of-scan case in step 3, with or without a linking next record; (c); and two `SHUTDOWN`s that conflict at the highest sequence. The only refusal in rule 6 that does not cite the `SHUTDOWN` is (a)'s link refusal.
     The refusal message is `AUDIT_RETENTION_CHAIN_UNVERIFIED: writer <instanceId>'s SHUTDOWN checkpoint at sequence <n> in the checkpoint store does not match its records in <segment>; it and every later expired segment were not purged. Compare the checkpoint store (dataprism.audit.checkpoint.file-path) with the audit directory before purging.`

     `<segment>` is the file the operator should open first:
     - when a record fails a check, the segment holding that record: the S record that ends in `\r` or has the wrong `eventHash` (step 3), the first record above S that does not chain (b), or the record beyond the chain (c);
     - when no record failed, because the S record was never found (step 3's end of scan) or the `SHUTDOWN`s conflict, or L's own hash differs from `S.headHash` in (b), D's segment holding L. On the partial-day path, where L's record is gone, it is D's first remaining segment.

     A forged later record beyond what the scan reads is not seen by purge. OD-292-5 (a), decided by the owner 2026-10-28, accepts this for 292, because purge does not read past the stop. The follow-up is that the verifier reports any record from an instance that comes after that instance's `SHUTDOWN` checkpoint. The scribe files it. No code for it in this task.
   - **Reading the store.** Retention reads `SHUTDOWN` checkpoints through a new default read-back method on `AuditCheckpointSink`. By default it returns an empty list, so a sink with no read-back falls back to the full scan. If the read throws, purge fails closed with `AUDIT_RETENTION_CHAIN_UNVERIFIED: could not read the SHUTDOWN checkpoints, so the end of the expiring chain cannot be checked; <deletedNote>`, as `earlierAnchors` does (`AuditRetention.java:355-362`). It never falls back silently to the full scan. `FileAuditCheckpointSink` implements it, filtering by kind as `retentionAnchors()` does (:87-105). The anchor store is trusted (`AuditRetention`'s Javadoc), so a keyless attacker cannot forge a `SHUTDOWN`.
   - **Read-counting seam.** The seam sits on the retention side. `AuditRetention` holds a package-private segment opener, like `deleter`, and passes it to the verifier's boundary helper, so the retention test package can count the segments opened.
   - **Cost reporting.** The implementation PR states the measured cost, as segments read and wall time: a clean-restart directory, and a crash directory with no `SHUTDOWN`, each with 180 retained daily segments. A writer with `L.sequence < S.sequence` is read up to its S record, which is at most the crash directory's full scan, so it needs no third measurement.
7. **Dates must match (OD-292-2 (a), rule 2).** Refuse a day with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and keep it and every later expired segment, if any segment's latest hashed timestamp date (rule 2) is later than the date in that segment's file name. `SegmentedFileAuditSink.record` (:174-183) only ever moves the segment date forward. A record's UTC date is therefore never later than its segment's date. It can be earlier, after the clock steps back.

   **A segment with no hashed record (172-4).** Rule 7 has no date evidence for a segment that holds no hashed record, so renaming such a segment to an earlier date is not detected.
   - If the segment holds an unhashed complete record, rule 4 refuses its new day anyway.
   - Otherwise it holds only lines that do not parse once one trailing `\r` is removed (rule 5 fragments), or nothing. Purge then deletes it under its new, earlier date.

   This is accepted. Such a segment holds no hashed record, so deleting it removes no chain position, anchor or link that rules 1-7 or the full verifier rely on. What is lost is the verifier's fragment anomaly (`INTERRUPTED_WRITE_FRAGMENT` or `tail()`), which records that a write was torn, not a record. Renaming a fragment-only segment needs the same write access to the audit directory that deleting it outright needs, and this task does not detect a deleted fragment either.

**Residual (OD-292-2 (a), narrowed by OD-292-4 (b) and OD-292-7 (a)).** If a writer stopped without a `SHUTDOWN` checkpoint (a crash, or a recorder built without a checkpoint sink) and never writes again, its last records can be deleted before a purge, by a clean line-boundary cut that leaves no fragment, and rule 6 has no next record to compare. Rules 1-7 cannot detect that. A writer that shut down cleanly is covered: when its `SHUTDOWN` is beyond the day's last hashed sequence, purge requires the writer's record at the `SHUTDOWN`'s sequence, carrying the `SHUTDOWN`'s hash and ending in `\n` with no `\r` (OD-292-8 (a)), in what it reads, whether or not a next record links (OD-292-7 (a)). Purge does not link-check the records between the next record and that record. They stay on disk, so the full verifier still reports a break among them. Periodic checkpoints (`dataprism.audit.checkpoint.interval`), compared by the full verifier, are the defence. Deleting every line of a writer from an expiring day is also not detected by purge, because purge does not compare the store's checkpoints with writers missing from disk. After the purge, the full verifier reports it (`MISSING_WRITER`, or a non-GENESIS start or anchor rejection when the writer survives in a later segment), except when the writer's highest checkpoint is `BOOT` or is covered by a valid retention anchor, where nothing detects it (`AuditChainVerifier.java:503-512`, `:530-535` on `origin/wave/0.8-wave4`) (Out of scope, "Whole-writer deletion").

**OD-292-1 (a) in practice.** A real crash can produce an unhashed line that *parses*. The cases, by where the cut falls in a v3 record (25 fields):
- every byte but the `\n`, later ended with `\r\n` or left unterminated: parses, so rule 4 refuses;
- a cut inside `raw[24]`, the last field: 25 fields, parses, so rule 4 refuses;
- a cut inside `raw[19]`: 20 fields, parses as a v1 record, so rule 4 refuses;
- a cut inside `raw[23]`: 24 fields under a v3 `recordVersion`, which does not parse. Being torn, the line is skipped before the verifier's parse (rule 5), so it is a tolerated fragment and purges;
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
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpointSink.java *(one new default read-back method for `SHUTDOWN` checkpoints, empty by default)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/checkpoint/FileAuditCheckpointSink.java *(implementing that method only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionBoundaryTest.java *(new: rules 6 and 7, and the `SHUTDOWN` stop)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierHashedRecordsTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionSizeCapTest.java *(`aTamperedUnterminatedLastRecordInAPartlyDeletedPrefixIsRefusedAndKept` only: its final assertions and its "known gap" comment)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Fixed entry)*

## Acceptance
**Fails first.** Each test below fails on the base and passes after the change. The base is `origin/wave/0.8-wave4` at 0f712c2a or later, which holds 207; `main` after PR #163 is the same code. These tests may pass on the base too, and the PR says so for each:
- "A real crash fragment still purges", a regression guard;
- "Duplicate-sequence line" (shape E), already refused by 207's `DUPLICATE_SEQUENCE` clause at the first copy's offset;
- "Clock stepped back", because the base has no rule 7.
- "Rename of a segment with no hashed record", positive variant, because it pins accepted behaviour that the base shares.
- "Clean restart, bounded read", "Crash, no `SHUTDOWN`, full scan", "Rolled-back N+1" (both variants), "Partial day after a clean shutdown", "Two `SHUTDOWN`s, positive" and "A writer spanning D1 and a later day closes cleanly", because the base has no boundary scan. Each has a named mutant that kills it (mutants (iv-b) to (iv-g), and (iv-k) for the spanning writer's third variant);
- "Two-key purge that succeeds", because the base already deletes a clean D1. It pins the replay choice.

Otherwise: Every test runs in plain mode and in keyed mode (`AuditChainMode.keyed(key)` on the writer, the same key passed to `AuditRetention`'s seven-argument constructor, `allowUnkeyedWriters=false`). If a shape does not fail first in one mode, stop and report. The PR lists each test with its failing assertion on the base.

**Anchor oracle.** Immediately before each `purge()` call, every test captures rule 2's last hashed record per (day, writer) over the expiring segments. It has to be captured then, because purge deletes the files. After the call, every `RETENTION_ANCHOR` that call recorded must equal the captured record for its day and writer, in sequence and hash.

- [ ] **Forged anchor (shape D).** The repro above, in two variants: the forged line ended `\r\n`, and left unterminated as the last expiring segment's tail (the v0.4.x form). `purge()` throws `AUDIT_RETENTION_CHAIN_UNVERIFIED`. Every D1 segment remains. No anchor with sequence 6 or hash6 is recorded. `AuditChainVerifier.verify(dir, checkpoints, P6M, keys, false).hasBreak()` is true.
- [ ] **Edited last record, `\r\n`, single expiring day (shape A2).** ALLOW to DENY in D1's last record, ended `\r\n`, with only D1 expired. The retained record on 09-09 is written by a *different* writer, so w never writes again and rule 6 has nothing to compare. Only rule 4 can refuse here, which mutants (i) and (ii) depend on. The purge refuses and keeps every D1 segment.
- [ ] **Edited last record, `\r\n`, next day also expiring (shape A).** The same edit, with D2 (2026-03-02) also expired. The refusal message names a D1 segment. Every D1 and D2 segment remains after a first run and after a second run with the same anchor store. There is a two-key variant: keys `[k0, k1]` passed to retention, the writer under `k1`. It asserts the same refusal. A refusal records no anchor, so this variant does not by itself pin rule 1's choice of replay; the successful two-key purge below does.
- [ ] **Two-key purge that succeeds (keyed only).** This test runs in keyed mode only: a two-key replay has no plain form. Keys `[k0, k1]` are passed to retention, and the writer is under `k1`. D1 is clean. D2 (also expired) has a content break: one record's field edited, its stored hash kept. The purge deletes D1, refuses at D2, and D2 remains. D1's anchor is asserted against an independent expectation, not the oracle: the `sequence` and `eventHash` of the `AuditEvent` that `AuditRecorder.record` returned for D1's last record.
- [ ] **Edited last record, no newline, as the final expiring segment (shape B).** The retained record is by a different writer, as in A2. The purge refuses and keeps every D1 segment.
- [ ] **Duplicate-sequence line.** A copy of a D1 record, edited, appended to D1's last segment as a newline-terminated line (shape E `appendDupForgedEnd`). The purge refuses and keeps every D1 segment.
- [ ] **Attribution.** D1's last record is replaced by a prefix of itself that does not parse, ended `\r\n`, and D2 is also expired. The purge refuses, the message names D1, and every D1 segment remains. This shape is not a real crash, because D2's record continues the same instance. There is a two-key variant as for shape A.
- [ ] **Clean restart, bounded read (OD-292-4 (b)).** Writers w1 and w2 each write on D1 (expired) and close with a `SHUTDOWN` in the store. A new instance writes on each of 180 retained daily segments. The test counts the segments the boundary scan opens, through a package-private read seam like `deleter`. The purge deletes D1, and the scan opens no retained segment for w1 or w2: at most the segments needed for writers without a `SHUTDOWN`, here none.
- [ ] **Rolled-back N+1 (OD-292-6 (a)), plain and keyed.** w1 writes through a `TeeAuditSink` whose projection throws on record N+1. N+1 is on disk, the recorder rolls back to N, and a clean `close()` writes `SHUTDOWN{N, hashN}`. A second writer w2, with no `SHUTDOWN`, also writes on D1, and its next record is in the first of 30 retained daily segments. So the scan must open at least that segment for w2, and the read-count assertion for w1 cannot pass vacuously. Two variants:
  - **N+1 in D1:** the purge deletes D1, and the scan opens no retained segment for w1;
  - **N+1 across midnight:** N+1 lands in the next day's segment, which is retained in one sub-case and also expired in the other. By step (a), N+1 links to `hashN`, and D1 purges. Mutant (iv-d) kills both sub-cases.
- [ ] **A writer spanning D1 and a later day closes cleanly, plain and keyed (OD-292-7 (a) guard).** w writes seq 1-5 on D1 and seq 6-10 on 03-02, then closes (`SHUTDOWN{10, h10}`). Here `L = 5 < S = 10`, the next record (6) links, and the S record (10) is present with `eventHash == h10`. Three variants:
  - 03-02 retained: D1 purges;
  - 03-02 also expired: D1 and 03-02 purge;
  - seq 6-8 on 03-02 and seq 9-10 on 03-03, both retained, followed by 30 retained daily segments written only by another instance: D1 purges, and the counted reads show the scan opens no segment after 03-03. This variant pins that step 3 goes past the next record's segment to the S record, and no further.
- [ ] **Partial day after a clean shutdown.** w1 writes seq 1-10 across D1's segments and shuts down cleanly (`SHUTDOWN{10, h10}`). Purge 1 anchors {10, h10} and fails while deleting `audit-2026-03-01.log` (through `deleter`). Purge 2 finishes the day, deletes the remaining prefix, and does not refuse.
- [ ] **Deleted tail after a linking next record (OD-292-7 (a)), plain and keyed.** w writes seq 1-3 on D1 (expired) and seq 4-6 on D2 (retained), then closes (`SHUTDOWN{6, h6}`). Each variant refuses D1 with the `SHUTDOWN`-contradiction message, keeps every D1 segment and records no anchor for D1. Each asserts the message's `<segment>` (rule 6, "Refusals that cite the `SHUTDOWN`"):
  - **the reviewer's example:** records 5 and 6 removed from D2 by a clean line cut. Record 4 still links to D1's last hashed record, and the scan reaches the last segment without a record at seq 6. The message names D1's segment holding record 3;
  - **hash differs:** record 6 replaced by a line at seq 6 whose `eventHash` is not `h6`. The message names D2's segment holding that line;
  - **`\r` copy:** record 6 removed by a clean line cut, then `serialize(record 6) + "\r\n"` appended to D2's last segment. The copy's `eventHash` is `h6`, but it ends in `\r`, so step 3 refuses it (OD-292-8 (a)). The message names D2's last segment, which holds the copy.

  This test fails on the base, which purges D1 in every variant.
- [ ] **Two `SHUTDOWN`s.** Three cases:
  - **positive:** close at N, record N+1, close at N+1; the purge deletes D1;
  - **negative:** close at N, records N+1 and N+2, close at N+2, then N+2 removed by a clean line cut. The purge refuses D1, because the highest `SHUTDOWN` wins. Using the lower one would let it purge;
  - **conflicting:** two `SHUTDOWN`s at the same highest sequence with different `headHash` values (written by hand into the store). The purge refuses D1.
- [ ] **`SHUTDOWN` read fails.** The checkpoint sink's `SHUTDOWN` read-back throws. The purge fails with `AUDIT_RETENTION_CHAIN_UNVERIFIED` naming the `SHUTDOWN` read, deletes nothing, and opens no retained segment.
- [ ] **Crash, no `SHUTDOWN`, full scan.** The same directory, but w1 has no `SHUTDOWN` (crashed). The purge still deletes D1, and the counted reads reach the last segment.
- [ ] **`SHUTDOWN` contradicted.** Each refuses D1 and keeps it:
  - a forged record of w1 above its `SHUTDOWN`, whose `previousHash` does not link to L, placed in the first retained segment the scan reads for another, unclosed writer. Step (a) refuses it with the link refusal;
  - the same record placed in a later expired day;
  - a `SHUTDOWN` whose `headHash` differs from D1's last hashed hash at the same sequence. Step (b) refuses it;
  - a `SHUTDOWN` beyond D1's last hashed sequence with no later record of w1, because its last records were deleted by a clean cut. Step 3 refuses it;
  - a `SHUTDOWN{N, X}` written by hand into the store, with `X != hashN`, while D1 holds a genuine N+1 that links to `hashN`. The store holds only that hand-written `SHUTDOWN` for w1. w1's recorder is not closed into that store, so no real `SHUTDOWN{N+1}` exists to become the highest checkpoint; if one did, a correct implementation would purge. Then `L = N+1 > S`, and the first record above S does not link from `S.headHash`, so step (b)'s chain check refuses with the `SHUTDOWN` message. This must fail on the base, which purges. A forged N+1 in D1 would not serve: its link break (`AuditChainVerifier.java:688-694`) makes `firstBadOffset` refuse before rule 6 runs;
  - w1 writes seq 1-5 on D1 and seq 6 in the first retained segment, then closes (`S = 6`). A forged w1 record at seq 8, not on the chain, is placed in a later retained segment that the scan opens for another, unclosed writer. Since `8 > max(5, 6)`, step (c) refuses it.

  Each refusal's message is the one rule 6 assigns to its step: the link refusal for step (a), and the `SHUTDOWN`-contradiction message for the others.
- [ ] **Window edge (`hideLastExpiringRecordAsFragment`).** w has seq 1-3 on 03-01 and seq 4 on 09-09, and record 3 is cut to its first 60 characters. Two variants: ended `\r\n`, and unterminated. The purge refuses, the message names D1, and D1 remains.
- [ ] **Rename (`renameRecentSegmentIntoExpiredDay`).** The repro above. The purge refuses. `audit-2026-03-01.log` and `audit-2026-03-01.0001.log` both remain. No anchor is recorded for D1.
- [ ] **Partial-day path (shapes C, C2).** A prefix of D1 left by a failed delete, then tampered (an unterminated edited last record, or one ended `\r\n`), is not finished. Every remaining D1 segment is kept. A clean prefix whose writer's next record chains from the recorded anchor is still finished; 207's existing partial-day tests cover this unchanged.
- [ ] **Crash line that parses (OD-292-1 (a)).** In an expired day, a writer instance's last line is not hashed but parses. Three variants:
  - its next record complete except for the final `\n`, ended `\r\n` (as `terminateTornTail` leaves it);
  - the same, unterminated as the day's last segment;
  - a v3 record cut inside `raw[19]`, ended `\r\n`.

  No later record of that instance exists, and a later expired day has records from another instance. `purge()` throws `AUDIT_RETENTION_CHAIN_UNVERIFIED` naming that day's segment. That day and the later expired day are both kept.
- [ ] **A real crash fragment still purges.** In an expired day, an instance's last line is a strict prefix of its next record, cut in an early field so it does not parse. Two variants: ended `\r\n`, and unterminated as the day's last segment. A different instance writes on a later expired day. The purge returns normally, deletes the day and records one anchor per writer at that writer's last hashed record. A third variant, a v3 record cut inside `raw[23]` and ended `\r\n`, is pinned as purging. It is torn, so the verifier skips it before parsing (rule 5), and it does not parse once the `\r` is removed.
- [ ] **Rename of a segment with no hashed record (172-4), in `AuditRetentionBoundaryTest`.** w writes seq 1-3 on 03-01 (expired). Instance x's only line is a torn prefix of its first record that does not parse, ended `\r\n`, alone in `audit-2026-09-09.log`. Another instance writes on 09-10 (retained). Rename `audit-2026-09-09.log` to `audit-2026-03-01.0001.log`. Two variants:
  - **fragment only:** `purge()` returns normally and deletes both D1 files, as rule 7 states for a segment with no hashed record. The full verify afterwards reports no break;
  - **unhashed complete record:** x's line is instead a complete record ended `\r\n`. `purge()` refuses under rule 4, naming `audit-2026-03-01.0001.log`, and both D1 files remain.
- [ ] **Clock stepped back.** A record whose timestamp date is earlier than its segment's date (the sink's forward-only rule) does not refuse its day under rule 7.
- [ ] **Verifier API.** `AuditChainVerifierHashedRecordsTest` pins rule 2 for each of these:
  - two writers across three segments;
  - a writer broken mid-segment, by a link failure and by a content failure (no hashed entry after the break, and the last hashed record before it and the break kind are exposed);
  - a keyed writer verified under the second of two keys;
  - a writer broken under every key (the positions come from the reported replay);
  - the lowest unhashed-complete offset for each unhashed shape: `\r` line, unterminated tail, duplicate second copy;
  - none for a fragment that does not parse.

  On copies, in the new test, of the inputs used by `AuditChainVerifierTest` and `AuditChainVerifierSizeCapTest`, the held report's `anomalies()`, `tail()`, `hasBreak()` and `hasStructuralAnomaly()` equal those of `verifySegments`.
- [ ] `grep -nE 'readAllLines|AuditRecordFormat\.parse' data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java` prints nothing.
- [ ] The "known gap" sentences in `AuditRetention`'s Javadoc and comments are removed or replaced with what is now true, including the residual above and OD-292-1 (a).

**Mutation proofs.** Make each mutation by hand, run the new tests, record the result in the PR description, then revert:
- [ ] (i) Rule 4 off **and** anchors built from raw lines (the base's `readAllLines` loop), together. Shape A2's anchor oracle fails, in both modes: the raw anchor is {3, stored hash3}, the last hashed record is {2, hash2}. With rule 4 off alone, the anchor check holds and only the refusal assertion fails. That is why this mutant is combined. Shape D cannot serve here, because rule 6 refuses it too and no anchor is recorded.
- [ ] (ii) Rule 4 removed: shapes A2 and B and the crash-line-that-parses test fail.
- [ ] (iii) Rule 6 removed: the attribution test and both window-edge variants fail.
- [ ] (iv) Rule 7 removed: the rename test fails, in both modes.
- [ ] (iv-b) `SHUTDOWN` ignored by the scan: the clean-restart bounded-read test fails on its read count.
- [ ] (iv-c) The pre-delta rule, "a `SHUTDOWN` below L refuses": the rolled-back N+1 test (N+1 in D1) fails.
- [ ] (iv-d) The pre-fix (c) wording, "any record above `L.sequence` refuses": both variants of the spanning-writer test fail.
- [ ] (iv-e) On the partial path, L is taken from the remaining prefix instead of the anchor: "Partial day after a clean shutdown" fails, because L < S and the scan finds no next record.
- [ ] (iv-f) A writer with no `SHUTDOWN` is treated as resolved: "Crash, no `SHUTDOWN`, full scan" fails on its read count.
- [ ] (iv-g) More than one `SHUTDOWN` for an instance is treated as a contradiction: "Two `SHUTDOWN`s, positive" fails. No mutant of the highest-wins choice can kill the positive case, because the lower `SHUTDOWN` also lets it purge. The negative case is what pins highest-wins.
- [ ] (iv-h) Hashed positions taken from `k0`'s replay: "Two-key purge that succeeds" fails. `k0`'s replay hashes nothing of the writer, so D1 is refused. The test's independent anchor assertion (against the returned `AuditEvent`) kills the mutant however it is scoped.
- [ ] (iv-i) The pre-OD-292-7 rule, "a linking next record resolves the writer whatever S is": the reviewer's example in "Deleted tail after a linking next record" fails.
- [ ] (iv-j) Step 3 accepts any line at `S.sequence` without comparing its `eventHash` with `S.headHash`, or without rejecting a trailing `\r`: the "hash differs" or the "`\r` copy" variant fails, respectively. Record both.
- [ ] (iv-k) Step 3 looks for the S record only in the next record's segment, and refuses D when it is not there: the spanning writer's third variant fails. Its next record (6) is on 03-02 and its S record (10) on 03-03, so the mutant refuses D1 where the rule purges it.
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
  - says the clean-shutdown cover needs a checkpoint sink that implements the `SHUTDOWN` read-back. `FileAuditCheckpointSink` does; a custom `AuditCheckpointSink` without it falls back to the residual;
  - lists the `SHUTDOWN`-contradiction refusal. A day is also kept when a writer's `SHUTDOWN` checkpoint contradicts its records. The entry quotes the operator-facing part of that message: compare the checkpoint store (`dataprism.audit.checkpoint.file-path`) with the audit directory before purging;
  - states the residual: if a writer stopped without a clean shutdown (for example after a crash) and never writes again, deleting its last records before a purge is not detected by purge. Periodic checkpoints (`dataprism.audit.checkpoint.interval`) are the defence. A writer that shut down cleanly is covered by its `SHUTDOWN` checkpoint. It also states the whole-writer residual (OD-292-9 (a)):
    - purge still deletes an expiring day when every line of a writer has been removed from it;
    - the full verifier reports this afterwards, as `MISSING_WRITER`, or as a non-GENESIS start when the writer survives elsewhere;
    - it does not report it when the writer's highest checkpoint is `BOOT`, or when a retention anchor covers it. In those cases nothing detects the deletion;
    - this behaviour predates 0.8: it is present since 0.4.0, the same span as the fix above;
    - the defence is keeping the audit directory writable only by the server;
  - gives the workaround for anyone staying on an affected version, in the owner-accepted wording: set `dataprism.audit.retention` to a period longer than the oldest segment, for example `P100Y`, and keep the audit directory and `dataprism.audit.checkpoint.file-path` writable only by the server. Plain-Java embedders who never call `purge()` are unaffected. (No affected version has an on/off switch for the purge. It runs whenever `dataprism.audit.directory` is set, `AuditWiring.java:89-97` at v0.7.1.)
  - says that a crash can leave a complete-looking record that was never verified. Purge of that day, and of every later expired day, then stops with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and the `dataprism.audit.retention.unverified` metric fires until the operator acts. It names no recovery command; 293 adds its own entry for that. It does not tell the operator to move or edit segments.

## Out of scope
- Crash-line recovery: the CLI acceptance, its checkpoint kind and retention's tolerance of an accepted line (293).
- The verifier finding for a record after its instance's `SHUTDOWN` checkpoint (OD-292-5 (a)). This is a follow-up for the scribe to file. Purge accepts that a forged record beyond the scan's stop goes unseen.
- `AuditRecorder` refusing `record()` after `close()` (OD-292-6 (a) follow-up, for the scribe to file).
- **Whole-writer deletion (residual of 292; candidate follow-up for the owner, from the review of #172, 2026-10-28; not designed here).** Purge does not cross-check the store's `BOOT`, `PERIODIC` and `SHUTDOWN` checkpoints against writers that are missing from the expiring days. Purge's replay is given no checkpoints (`AuditChainVerifier.java:149-154`, called from `AuditRetention.java:185` on `origin/wave/0.8-wave4`), and rule 6 reads `SHUTDOWN` only for writers with hashed records in the day. So deleting every line of a writer from an expiring day lets purge delete the day, with or without an anchor. Afterwards, the full verifier reports it (`MISSING_WRITER`, or a non-GENESIS start or anchor rejection when the writer survives in a later segment), except when the writer's highest checkpoint is `BOOT` or is covered by a valid retention anchor, where nothing detects it (`AuditChainVerifier.java:503-512`, `:530-535` on `origin/wave/0.8-wave4`).
- The residual of OD-292-2 (a): deleting a never-again writer's last records is not detected by purge. The defence is periodic checkpoints, which the full verifier compares; nothing here changes them.
- Advisory text of any kind (OD-207-4 (c)).
- How the verifier reports fragments, tails or duplicates: anomaly types, messages, `isBreak()`, CLI output and exit codes. This task adds an API only.
- OD-292-1 (b), which was not chosen: any tolerance rule for a crash line that parses.
- `JsonAuditRetention`. It writes no anchors and verifies nothing (`JsonAuditRetention.java:28`).
- The `AuditSink` and `FileAuditSink` write path, and making a torn write impossible.
- `docs/audit.md` (213), `docs/configuration.md` (211 owns the `dataprism.audit` rows in wave 7), and Spring wiring (`AuditWiring`, `AuditMaintenance`, metrics).
- 207's segment discovery, ordering and per-day deletion, other than the refusal boundary described here.
