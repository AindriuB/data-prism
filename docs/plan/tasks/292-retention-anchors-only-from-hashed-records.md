# 292 — Build retention anchors only from records the verifier hashed

**Repo:** `.`
**Release:** 0.8.0
**Priority:** P1 (audit integrity). Present in v0.6.0, v0.7.0, v0.7.1 and on `origin/wave/0.8-wave4`.
**Base branch:** `origin/wave/0.8-wave4` once PR #166 (207) has merged into it. One PR for this task, targeting `wave/0.8-wave4`.
**Depends on:** 207 (PR #166 rewrites the same `AuditRetention` code: `partlyPurgedDayLength`, `firstUnanchoredStart`, `firstBadOffset`, `anchorsFor`, and owns `AuditRetentionSizeCapTest.java`), 290 (both insert into `CHANGELOG.md` `[Unreleased]`)
**Wave:** 7 (filed 2026-10-27). Not wave 6: the only intersection with wave 6 is `CHANGELOG.md`, which 290 owns (insertions). The audit sources and tests are disjoint from 208, 227, 246, 262, 284 and 289. In wave 7 the Owns are disjoint from 211 (Spring audit wiring and `docs/configuration.md`, not core audit), 228, 247 (`AuditEvent.java` and its own new test files only; not `AuditChainVerifier.java` or retention) and 256. 291 also inserts into `CHANGELOG.md` in wave 7. The planner has not settled how these two are ordered. See "Ordering with 291" below.
**Decisions:**
- **OD-207-3 (a), decided by the owner 2026-10-27.** Retention builds anchors and day-deletion boundaries only from records the verifier actually hashed, through a small verifier API. In an expiring day, any line that parses as a complete record but was not hashed refuses that day with `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and the day is kept. A genuine crash fragment, which is a line that does not parse as a record, is tolerated, so purge keeps working after a real crash.
- **OD-207-4 (c), decided by the owner 2026-10-27.** Disclosure is by CHANGELOG only. There is no security advisory and no patch release. The CHANGELOG Fixed entry names the affected versions, the effect in operator terms and the workaround for 0.7.x (Acceptance, "CHANGELOG").
- **207's `DUPLICATE_SEQUENCE` clause in `firstBadOffset` stays (planner, at filing).** The new rule does not subsume it. The verifier reports a duplicate at the *first* copy's offset (`primaryOffset`, `AuditChainVerifier.java:798-811` on 207). The second copy is the unhashed line. The new rule refuses from the second copy. 207's clause refuses from the first copy, which can be in an earlier day: for example, a forged record inserted in day D before the genuine one, with the genuine one in D+1. The two agree when both copies are in one day. Keep the clause and update its comment to say that it covers the first copy and the new rule covers the second.
- **Open, OD-292-1 (owner):** a real crash can leave a line that parses. See "The rule".

## Goal
`AuditRetention` re-parses raw segment lines with `Files.readAllLines`. That strips a trailing `\r`, so the anchor it records, and the per-writer positions it bases deletion on, can come from lines the verifier skipped without hashing. A writer with access to the audit directory, but holding neither the chain key nor the anchor store, can then make purge record an anchor of their choosing and delete recent records with no trace, in plain and keyed mode. After this task, every chain position retention uses comes from the verifier's own replay. An expiring day that holds an unhashed complete record is kept. A tamper in day D is blamed on day D, never on D+1.

## The bug, as filed (checked on `origin/task/207-size-capped-audit-segments` at a60126e9)
- `AuditRetention.anchorsFor` (:420-450), `partlyPurgedDayLength` (:293-312) and `firstUnanchoredStart` (:368-399) each read lines with `Files.readAllLines` and `AuditRecordFormat.parse`. `firstBadOffset` (:401-418) looks only at breaks and anomalies.
- `AuditChainVerifier` deliberately skips two line shapes without hashing them:
  - a line ending in `\r`, reported as `INTERRUPTED_WRITE_FRAGMENT` and never parsed (:581-593);
  - an unterminated final chunk, reported as `tail()` and never parsed (:439-447). `join` (:178-194) appends `FileAuditSink.TORN_TAIL_TERMINATOR` (`"\r\n"`) to a non-final segment that ends without `\n`, so inside the concatenation that segment's torn tail becomes a `\r` line.
- Repro (plain and keyed). Writer w has seq 1-3 on 2026-03-01 (D1), 4-6 on 2026-09-05 (not expired) and 7 on 2026-09-08. Now is 2026-09-10. Delete the 09-05 segments, then append `serialize(record 6) + "\r\n"` to D1's last segment. `purge()` returns OK, deletes D1 and records an anchor at {6, hash6}. `AuditChainVerifier.verify(dir, checkpoints, P6M, keys, false)` then reports no break, and records 4-6 are gone undetected.
- Related shape: change ALLOW to DENY in D1's last record and end it with `\r\n` (or with no newline at all). Purge deletes the tampered day: on its own (the single-day shape), or with the link break that follows blamed on D2's first record (the joined shape).
- The coordinator's probes (`ZzProbeTest.java`, `BaseProbe.java`, shapes A, A2, B, C, C2, D, E) are the source of these tests. They are not repo files. Rewrite them as named tests here.

## The rule
1. **A hashed record** is one the replay recomputed and matched, in the replay chosen for that writer: the key that verified it in `verifyWithKeys` (:337-406), or the unkeyed replay where `allowUnkeyedWriters` accepted it. In `processLine` that is the path that reaches `writer.lastHash = event.eventHash()` at :709. A record after a writer's break, a duplicate sequence's second copy, a `\r` line and the unterminated tail are not hashed.
2. **Verifier API.** `verifySegments(segments, starts, keys, allowUnkeyedWriters)` exposes, on its report or on a sibling result, for every (segment index, writer) pair:
   - the first hashed record: sequence, `previousHash` and byte offset;
   - the last hashed record: sequence, `eventHash` and byte offset.
   For every writer with a break, it also exposes its last hashed record before the break. It also lists every **unhashed complete record**, by byte offset and segment index. That is a line, or the unterminated tail, that parses with `AuditRecordFormat.parse` once exactly one trailing `\r` is removed, but was not hashed. Its size is bounded by segments × writers plus the number of unhashed lines, not by the number of records. The existing anomalies, `tail()`, `hasBreak()` and `hasStructuralAnomaly()` do not change.
3. **Retention uses only that API.** `anchorsFor`, `partlyPurgedDayLength`'s last-remaining positions and `firstUnanchoredStart` read their sequences, hashes and positions from rule 2. `AuditRetention.java` no longer calls `Files.readAllLines` or `AuditRecordFormat.parse`. Each anchor is the day's last hashed record for that writer.
4. **An unhashed complete record refuses its day.** `firstBadOffset` also returns the lowest offset of an unhashed complete record. The normal path refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` from that segment's day, and keeps the day and every later expired segment, as for a break. The partial-day path (`partlyPurgedDayLength`) does not finish a prefix that contains one.
5. **A crash fragment is tolerated.** `FileAuditSink.recordLine` (:149-163) writes `serialize(event) + "\n"` in one buffer and poisons the sink on any failure. `AuditRecorder` gives every boot a fresh `instanceId` (:105). So a real torn write is a prefix of one record's line. It is the last line its instance ever writes, and it ends either with nothing (the segment was never reopened) or with the `"\r\n"` that `terminateTornTail` (:98-120) or `join` adds. Serialised records escape CR, so the only raw `\r` in such a line is the final one. A line that does not parse after one trailing `\r` is removed is not refused by this task. If the verifier already calls it a break (`UNPARSEABLE_RECORD`, `FIELD_COUNT_MISMATCH`), it is still refused, as today.
6. **Attribution.** A writer's first break that is a link failure (its `previousHash`, or its sequence position, does not follow the writer's last hashed record) is attributed to the segment of that writer's last hashed record before the break, not to the segment of the record where the break was detected. A content failure (the recomputed `eventHash` does not match) is attributed to its own record. Because rule 5's fragment is always its instance's last line, a link break never follows a real crash. So this rule never refuses a day for a crash.

**OD-292-1 (open, for the owner).** By rule 5, a crash can also produce a line that *parses*:
- a crash after every byte but the `\n`, terminated later with `\r\n` or left unterminated;
- a cut inside the last field. `AuditRecordFormat.parse` checks the field count, not the length of the last field (`raw[19]` for v1, `raw[24]` for v3).

Rule 4 refuses such a day. It is kept, `dataprism.audit.retention.unverified` fires, and every later expired day is kept too, until an operator acts. This fails closed and should be rare, but nothing in the product clears it.
- (a) Recommended for this task: accept it and document it in `docs/audit.md`, which a follow-up owns, not this task.
- (b) As well, tolerate such a line without anchoring it, but only when all of these hold: it is its instance's last line in the verified scope, its sequence is that writer's last hashed + 1, and its `previousHash` equals that hash. Anchors would still come only from hashed records, so this cannot plant an anchor. But it would let purge delete the bytes of an edited last record in a single-expiring-day shape.

The implementer builds (a) unless the owner decides otherwise before work starts.

## Ordering with 291
291 (wave 7) inserts one Breaking line into `CHANGELOG.md` `[Unreleased]` and bases on `main` after PR #163. This task bases on `wave/0.8-wave4`. Pairwise, the two share a file in one wave. The coordinator chooses one of these:
- (a) Recommended: add 292 to PR #163's hold list. 291 then starts from a `main` that already has this entry, and 291 gains a `Depends on: 292` edge.
- (b) This task depends on 291 and moves to wave 8.

Until one is recorded, do not run 291 and 292 at the same time.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java:150-255 — `purge`: the verified prefix, the same-day rewind at :214-219, anchoring before deletion
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java:284-351 — the partial-day path, with the "known gap" comments at :42-43 and :333-335, which this task makes untrue and must reword
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:139-194 — `verifySegments`, `join` and `starts`
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:310-409 — the per-writer chosen replay under keys; the hashed records must come from the same choice
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:575-711 — `processLine`: which paths hash and which skip
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/sink/FileAuditSink.java:72,98-163 — the terminator, `terminateTornTail` and the single-buffer write
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionSizeCapTest.java:175-215,444-462 (on 207) — `writeMulti`, `purgeFailingOn` and the test this task tightens
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionDefectsTest.java:194 — the existing non-parsing torn-fragment case, which must still purge
- docs/plan/HISTORY-INDEX.md, the row for task 102 — anchors and dates needed four attempts; read that HISTORY.md entry first

## Owns
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java *(`purge`'s refusal boundary, `partlyPurgedDayLength`, `firstUnanchoredStart`, `firstBadOffset`, `anchorsFor`, and the class and method Javadoc that describe them)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java *(the new hashed-records API only: new public records, the fields that carry them on the `verifySegments` result, and the per-writer bookkeeping in `WriterState`, `processLine` and `replay` that fills them. No change to anomalies, breaks, `tail()`, messages or exit behaviour.)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionHashedAnchorsTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierHashedRecordsTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionSizeCapTest.java *(`aTamperedUnterminatedLastRecordInAPartlyDeletedPrefixIsRefusedAndKept` only: its final assertions and its "known gap" comment)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Fixed entry)*

## Acceptance
**Fails first.** Each test below fails on the base (207 merged into `wave/0.8-wave4`) and passes after the change, in plain mode and in keyed mode (`AuditChainMode.keyed(key)` on the writer, the same key passed to `AuditRetention`'s seven-argument constructor, `allowUnkeyedWriters=false`). The PR lists each test with its failing assertion on the base.
- [ ] **Forged anchor (shape D).** The repro above. `purge()` throws `AUDIT_RETENTION_CHAIN_UNVERIFIED`. Every D1 segment remains. No `RETENTION_ANCHOR` with sequence 6 or hash6 is recorded. `AuditChainVerifier.verify(dir, checkpoints, P6M, keys, false).hasBreak()` is true.
- [ ] **Edited last record, `\r\n`, single expiring day (shape A2).** ALLOW to DENY in D1's last record, ended with `\r\n`, and only D1 expired. The purge refuses and keeps every D1 segment.
- [ ] **Edited last record, `\r\n`, next day also expiring (shape A).** The same edit, with D2 (2026-03-02) also expired. The refusal message names a D1 segment, not D2. Every D1 and D2 segment remains, on a first run and on a second run with the same anchor store.
- [ ] **Edited last record, no newline, as the final expiring segment (shape B).** The purge refuses and keeps every D1 segment.
- [ ] **Duplicate-sequence line.** A copy of a D1 record, edited, appended to D1's last segment as a newline-terminated line (shape E `appendDupForgedEnd`). The purge refuses and keeps every D1 segment.
- [ ] **Attribution.** D1's last record is replaced by a prefix of itself that does not parse, ended with `\r\n`, and D2 is also expired. The purge refuses, the message names D1, and every D1 segment remains. This shape is not a real crash, because D2's record continues the same instance.
- [ ] **Partial-day path (shapes C, C2).** A prefix of D1 left by a failed delete, then tampered (an unterminated edited last record, or a `\r\n`-ended one) is not finished. Every remaining D1 segment is kept.
- [ ] **A real crash fragment still purges.** In an expired day, an instance's last line is a strict prefix of its next record (a cut in an early field, so it does not parse), in two forms: ended with `\r\n`, and unterminated as the day's last segment. A different instance writes on a later expired day. The purge returns normally, deletes the day and records one anchor per writer at that writer's last hashed record. `AuditRetentionDefectsTest`'s `torn-fragment` case passes unchanged.
- [ ] **Verifier API.** `AuditChainVerifierHashedRecordsTest` pins rule 2 for each of these:
  - two writers across three segments;
  - a writer broken mid-segment (no hashed entry after the break, and the last hashed record before it is exposed);
  - a keyed writer verified under the second of two keys;
  - each unhashed shape: `\r` line, unterminated tail, duplicate second copy, and a fragment that does not parse (listed as not complete).

  `VerificationReport.anomalies()`, `tail()`, `hasBreak()` and `hasStructuralAnomaly()` are identical to the base's on every existing `AuditChainVerifierTest` and `AuditChainVerifierSizeCapTest` input.
- [ ] `grep -nE 'readAllLines|AuditRecordFormat\.parse' data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java` prints nothing.
- [ ] The "known gap" sentences in `AuditRetention`'s Javadoc and comments are removed or replaced with what is now true, including OD-292-1's residual if (a) stands.

**Mutation proofs.** Make each mutation by hand, run the new tests, record the result in the PR description, then revert:
- [ ] `anchorsFor` built from raw lines again (the base's `readAllLines` loop): shape D fails, in both modes.
- [ ] The unhashed-complete-record rule removed from `firstBadOffset`: shapes A2, B and the duplicate case fail.
- [ ] Rule 6 removed, so a link break is attributed to its own record's segment: the attribution test and shape A fail.
- [ ] The rule changed to "refuse any unhashed line", whether or not it parses: the real-crash test fails.

**Existing tests.**
- [ ] Every existing test in `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**` passes with unchanged expectations, except those under "Changed expectations". The PR lists each one it changes, with the old and new assertion. An expectation change not on that list is a stop-and-report, not a fix.

**Changed expectations.**
- [ ] `AuditRetentionSizeCapTest.aTamperedUnterminatedLastRecordInAPartlyDeletedPrefixIsRefusedAndKept` (added by 207 in a60126e9). Today it asserts only that the purge throws and that `audit-2026-03-02.log` and `audit-2026-09-09.log` remain. Today D1 is purged: once the normal path joins D1 and D2, the tampered unterminated line becomes a `\r` line and is skipped without hashing. Tightened, it asserts that every remaining D1 segment, `audit-2026-03-01.log` and `audit-2026-03-01.0001.log`, still exists, together with the existing assertions. The "known gap" comment at :459-460 is replaced.

**CHANGELOG.**
- [ ] One entry under `[Unreleased]` → `### Fixed` (the 0.8.0 section), written for operators. It:
  - names the affected versions, 0.6.0 through 0.7.1;
  - states the effect: anyone with write access to the audit directory could make a retention purge erase recent audit records while verification still passed;
  - says what 0.8.0 does instead: purge refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` and keeps the day;
  - gives the workaround for anyone staying on 0.7.x: disable the retention purge, and make the audit directory and the anchor store writable only by the server.

  0.7.x has no on/off switch for the purge. It runs whenever `dataprism.audit.directory` is set (`AuditWiring.java:89-97` at v0.7.1). The entry therefore states the workaround concretely: set `dataprism.audit.retention` to a period longer than the age of the oldest segment, for example `P100Y`, so the purge deletes nothing. Plain-Java embedders do not call `AuditRetention.purge()`. The anchor store is the file named by `dataprism.audit.checkpoint.file-path`. If OD-292-1 (a) stands, one more sentence says that a day holding a complete-looking record left by a crash is now kept and reported through `dataprism.audit.retention.unverified`.

## Out of scope
- Advisory text of any kind. OD-207-4 (c) says CHANGELOG only, with no advisory and no patch release.
- How the verifier reports fragments, tails or duplicates: anomaly types, messages, `isBreak()`, CLI output and exit codes. This task only adds the API.
- OD-292-1 (b), unless the owner chooses it before work starts.
- `JsonAuditRetention`. It writes no anchors and verifies nothing (`JsonAuditRetention.java:28`).
- The `AuditSink` and `FileAuditSink` write path, and making a torn write impossible.
- `docs/audit.md`, `docs/configuration.md` (211 owns the `dataprism.audit` rows in wave 7) and Spring wiring: `AuditWiring`, `AuditMaintenance` and metrics.
- 207's segment discovery, ordering and per-day deletion, other than the refusal boundary described here.
