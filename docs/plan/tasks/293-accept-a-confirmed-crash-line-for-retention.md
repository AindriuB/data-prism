# 293 — Let an operator accept a confirmed crash line so retention can purge past it

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` after 292 has merged. One PR, targeting `main`.
**Depends on:** 292 (this task extends 292's verifier API and retention rule 4; both edit `AuditRetention.java` and `AuditChainVerifier.java`), 291 (both insert into `CHANGELOG.md` `[Unreleased]`; 291 is in wave 8)
**Wave:** 9 (filed 2026-10-28).
- In wave 9 its Owns are disjoint from 252 (orchestration `follow`) and 254 (Spring limits and catalog wiring, `docs/configuration.md`, and the `CLUSTER_KEY_SHARED` and `NAME_KEY_SHARED` rows of `docs/plan/design/contract-register.md`).
- It does not own the contract register, so it shares no file with 254. See Out of scope.
- `AuditCheckpointSink.java` and `FileAuditCheckpointSink.java` are also 292's (its `SHUTDOWN` read-back, OD-292-4 (b)), and this task depends on 292. No other open brief owns `AuditCheckpoint.java`, `AuditCheckpointSink.java`, `FileAuditCheckpointSink.java` or `AuditChainVerifierCli.java`. 207 owned the CLI help text only, and is on `wave/0.8-wave4` at 0f712c2a.
**Decisions:**
- **OD-292-3 (a), decided by the owner 2026-10-27.** Recovery from a crash line that parses (OD-292-1 (a)) works like this:
  - a verifier CLI subcommand records an operator-confirmed acceptance in the anchor store, as a new checkpoint kind holding the segment name, the byte offset and the SHA-256 of the exact line bytes;
  - the command refuses unless the line is currently a complete record that was not hashed, its instance has no later record anywhere in the directory, and the supplied hash matches the line's bytes;
  - retention tolerates exactly that accepted line and nothing else;
  - files on disk are not changed.
- **Trust (planner, at filing, from 292).** An acceptance is only as trustworthy as the anchor store. The attacker 292 defends against cannot write the anchor store, so cannot forge an acceptance. Whoever can write the anchor store can already vouch for chain starts, as `AuditRetention`'s Javadoc says.
- **OD-293-1 (a), decided by the owner 2026-10-28.** The command also requires the line's `previousHash` to equal its instance's last hashed `eventHash`. A genuine crash line always passes: it is the recorder's next record, and a cut that still parses keeps `raw[18]` intact.
- **OD-293-3 (a), decided by the owner 2026-10-28.** The command also accepts an instance's first line with `previousHash` GENESIS, provided that instance has no other record anywhere in the directory. It is one condition with two branches: condition 5 under "The command". OD-293-1 (a)'s predecessor rule applies to branch (i) only.
- **The key check on the sole line (planner, 2026-10-28, from external review of #172).** On branch (ii) the replay skips the line and there is no hashed predecessor, so the chain replay checks no key. The command therefore recomputes the line's own `eventHash` under the supplied keys and refuses when it does not match (condition 5 (ii)). A wrong or missing `--hmac-key-file` is refused on both branches.
- **OD-293-2: withdrawn (2026-10-28).** Its premise was false. A torn write is always either `\r\n`-terminated or an unterminated tail, and the verifier skips both before parsing (`AuditChainVerifier.java:581-593`, `:439-447`). A `raw[23]` cut is therefore a tolerated fragment under 292's rules 2 and 5 and purges without this command. 292 pins it as purging.
- **What the checks prove (planner, at filing).** They do not prove a crash. A keyless attacker with write access to the audit directory can craft a line that passes every condition: for example, the instance's true next record, rewritten as a last line ended `\r\n`. The operator's investigation is the gate. The help text and the CHANGELOG say so, and neither may call the checks proof of a crash.
- **Running it (planner, at filing).**
  - Stop the server first. The command appends to the live anchor store, and opening a `FileAuditCheckpointSink` runs `terminateTornTail`. Run against a store the server is writing, the command could terminate an in-flight `PERIODIC` line, which then reads as `TORN_CHECKPOINT_LINE`.
  - The command refuses, writing nothing, if the store's last byte is not `\n`, so it never terminates a line itself.
  - Run it as the account that owns the anchor store, which is the server's account (292's workaround keeps the store writable only by the server).
  - The help text says all three.

## Goal
After 292, a real crash that leaves an unhashed line that parses keeps its day and every later expired day until an operator acts (OD-292-1 (a)). Give the operator a way to act that changes no audit file and widens nothing else: after investigation, the operator records an acceptance of that exact line in the anchor store, and purge then tolerates that line alone.

## The command
- `AuditChainVerifierCli accept-crash-line <audit-directory> --checkpoints <anchor-store-file> --segment <segment-file-name> --offset <byte-offset-in-segment> --sha256 <hex>`, taking `--hmac-key-file` (repeatable) and `--allow-unkeyed-writers` exactly as the verify mode does. The exact flag names may differ; the help text and the CHANGELOG name whatever is built.
- The line is the bytes of `<segment>` from `<offset>` up to, but not including, the next `\n`, or to end of file for an unterminated tail. A trailing `\r` is included. The SHA-256 is over those bytes as they are on disk, never over the verifier's joined copy.
- **Replay mode.** Condition 2 and branch (i) of condition 5 are judged by the full-directory replay `AuditChainVerifier.verify(<audit-directory>, <anchor-store-file>, minimumRetention, keys, allowUnkeyedWriters)`, with the supplied keys and flag. `minimumRetention` is `--min-retention` or the verifier's default. This is the same replay the verify mode runs: not lenient, and with retention anchors honoured. Branch (ii) of condition 5 adds a single-record hash check, because the replay never hashes that line.
- **Which bytes are hashed, by both the CLI and retention.** The SHA-256 covers the on-disk bytes of the segment file, from the line's in-segment offset up to, but not including, the next `\n`, or to end of file for an unterminated tail, so a trailing `\r` is included. It never covers the verifier's joined copy, which appends `"\r\n"` to a non-final segment's unterminated tail (`join`, :178-194). Retention maps a joined offset back to (segment, in-segment offset) through `starts`, and re-reads those bytes from the segment file to compare.
- The command refuses, writes nothing, and exits with a distinct non-zero code (a new `EXIT_*` constant, documented in `--help`) unless every one of these holds:
  1. `<offset>` is the start of a line in `<segment>`, and `<segment>` is an `audit-YYYY-MM-DD[.NNNN].log` file in `<audit-directory>`;
  2. the line is an unhashed complete record by 292's definition, judged by the verifier's full-directory replay under the supplied keys;
  3. its instance has no later record anywhere in the directory: no later line, in segment order, that parses as that instance's record once one trailing `\r` is removed;
  4. `--sha256` matches the line's bytes;
  5. the line chains as its instance's next record (OD-293-1 (a), OD-293-3 (a)). One condition, with two branches. The branch is chosen by whether the instance has any other record anywhere in the directory, that is, any other line that parses as that instance's record once one trailing `\r` is removed:
     - **(i) Not the instance's first record:** the instance has another record. It must have at least one hashed record before the line and must not be broken before it, in that replay. The line's `previousHash` must equal the instance's last hashed `eventHash`. The key is checked through the chain replay: a keyed chain replayed without its key, or under a wrong one, is broken at its first record or falls into the forger branch, so it has no hashed record before the line.
     - **(ii) The instance's sole line:** the instance has no other record, so this line is its only line. Its `previousHash` must be GENESIS. Its stored `eventHash` must also recompute and match on its own: `AuditEventHash.compute(event, k)` for one of the supplied `--hmac-key-file` keys, or the unkeyed `AuditEventHash.compute(event)` only when no key is supplied or `--allow-unkeyed-writers` is set. This is the same acceptance rule the replay applies to a writer's first record (292's rule 1). A keyed line checked with no key, or under a wrong one, fails here. Both `compute` overloads are already public (`AuditEventHash.java:185`, `:198` on `origin/wave/0.8-wave4`), so the check needs no change outside 293's Owns.
  6. the anchor store's last byte is `\n`, or the store is empty.
- On success it appends one checkpoint to `<anchor-store-file>` through `FileAuditCheckpointSink`, prints it, and exits 0. The new kind is `CRASH_LINE_ACCEPTED`. It carries:
  - the line's `instanceId` and `sequence`;
  - the SHA-256 of the line as `headHash`;
  - the segment's date as `segmentDate`;
  - two new fields, the segment file name and the byte offset.

  No audit file is opened for writing.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpoint.java:30-110 — the record, `Kind`, `toJsonLine` and `fromJsonLine`. `Kind.valueOf` throws on an unknown kind, so a 0.7.x verifier given a store that contains the new kind fails with "not a valid checkpoint" (fails closed).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpointSink.java — `retentionAnchors()` is the pattern for a defaulted read-back method
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/checkpoint/FileAuditCheckpointSink.java:87-105 — `retentionAnchors()` filters by kind
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:473-527 — `applyCheckpoints` treats every kind except `RETENTION_ANCHOR` as a head claim. Left unchanged, an acceptance would raise `TRUNCATED_BEFORE_CHECKPOINT`.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:555-573 — `anchorContradiction` treats every non-anchor kind as a dated sequence claim
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCli.java:107-160,291-336 — argument parsing, exit codes and `--help`
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCliTest.java:232 — `helpDocumentsEveryExitCodeAndTheLimitation`
- docs/plan/tasks/292-retention-anchors-only-from-hashed-records.md — the rules this extends, especially rules 2, 4 and 6 and "OD-292-1 (a) in practice"
- The reviewer's probe `recoveryAfterParsingCrashLine` (`Zz292Test.java`, in the coordinator's scratchpad, not a repo file):
  - writer a writes four records on 03-01, and its last record has its final `\n` removed;
  - a second writer b then reopens the segment, which adds `\r\n`, and writes on 03-01, 03-02 and 09-09.

## Owns
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpoint.java *(`Kind.CRASH_LINE_ACCEPTED`; two new optional components, segment name and byte offset, written to and read from JSON only when present. The 6- and 5-argument constructors are kept, so no caller changes.)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpointSink.java *(one new default read-back method returning the recorded acceptances, empty by default)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/checkpoint/FileAuditCheckpointSink.java *(implementing that method)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java *(only these:*
  - *the new kind is skipped in `applyCheckpoints` and `anchorContradiction`;*
  - *292's lowest-unhashed-complete computation takes a set of acceptances and skips a line only when segment name, in-segment offset and the SHA-256 of its current bytes all match one;*
     - *the line lookup the command needs, including branch (ii)'s single-record `eventHash` check through `AuditEventHash.compute`. `AuditEventHash.java` is not changed.)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCli.java *(the subcommand, its exit code and its `--help` lines)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java *(reading the acceptances and passing them to rule 4 only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/retention/AuditRetentionCrashLineAcceptanceTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCliAcceptCrashLineTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditCheckpointTest.java *(insertions only: round trip of the new kind and its fields)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCliTest.java *(insertions only, in `helpDocumentsEveryExitCodeAndTheLimitation`: the new exit code and the subcommand)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Added entry)*

## Acceptance
- [ ] **Recovery (`recoveryAfterParsingCrashLine`), plain and keyed.** On the probe's directory:
  - before acceptance, `purge()` throws `AUDIT_RETENTION_CHAIN_UNVERIFIED` naming D1, and every file remains (292's rule);
  - `accept-crash-line` with the line's segment, offset and SHA-256 exits 0 and appends one `CRASH_LINE_ACCEPTED` line;
  - `purge()` then returns normally and deletes the 03-01 and 03-02 segments, and the bytes of every remaining file are unchanged;
  - writer a's anchor is its last hashed record (its third), never the accepted fourth;
  - `AuditChainVerifier.verify(dir, store, P6M, keys, false)` reports no break, no checkpoint finding, and nothing from the new kind.
- [ ] **Crash on the first record, plain and keyed (OD-293-3 (a), branch (ii)).** An instance's only line is its first record, complete with `previousHash` GENESIS, ended `\r\n`, in an expired day. A later expired day holds another instance's records. `purge()` refuses. `accept-crash-line` exits 0. `purge()` then deletes both days, and records no anchor for the crashed instance. In the keyed variant the line was written under the key passed as `--hmac-key-file`, with `allowUnkeyedWriters` false, and the command recomputes its `eventHash` under that key.
- [ ] **Recovery variants, plain and keyed.** Each is refused by `purge()` before acceptance and purges after it. Each also asserts that the acceptance's SHA-256 equals the SHA-256 of the on-disk line bytes, and differs from the hash of the joined copy wherever the two differ:
  - an unterminated complete record at the end of a non-final expired segment: a crash at 23:59 on D1, with the restart writing after midnight into D2, so D1's file is never reopened and `join` adds `"\r\n"` only in the joined copy;
  - an unterminated complete record as the final expiring segment's tail.
- [ ] **Exactly that line.** After acceptance, append a second unhashed complete line elsewhere in D1, or edit one byte of the accepted line. `purge()` refuses again and keeps D1.
- [ ] **Negatives.** Each of these exits with the new refusal code, writes nothing to the store, and leaves every file's bytes unchanged:
  - wrong SHA-256;
  - the instance has a later record (shape D of 292: a copy of record 6 with record 7 later);
  - the line is hashed;
  - the line does not parse (a crash fragment, already tolerated);
  - the offset is not a line start;
  - a segment name outside the directory or not matching the segment pattern;
  - branch (i), not the instance's first record:
    - a complete record that does not chain from its instance's last hashed record (OD-293-1 (a));
    - a line whose instance has records but none hashed before the line;
    - an instance broken before the line;
    - keyed: the probe's chain run with no `--hmac-key-file`, and with a wrong one. Each is refused through the replay, because no record before the line is hashed;
  - branch (ii), the instance's sole line:
    - a GENESIS line whose instance has other records anywhere in the directory (OD-293-3 (a)). Branch (ii) does not apply, so branch (i) or condition 3 refuses it;
    - a sole line whose `previousHash` is not GENESIS;
    - keyed: the "Crash on the first record" line, written under key `k1`, run with no `--hmac-key-file`, and with `--hmac-key-file` holding a different key `k2`. Each is refused by the single-record `eventHash` check;
  - a store whose last byte is not `\n`, which is left byte-identical.
- [ ] **Store compatibility.** `AuditCheckpoint.fromJsonLine(cp.toJsonLine())` round-trips the new kind with its fields. A `BOOT`, `PERIODIC`, `SHUTDOWN` or `RETENTION_ANCHOR` line is byte-identical to the base's. `retentionAnchors()` never returns an acceptance.
- [ ] **Verifier unchanged otherwise.** On copies, in the new test, of the inputs used by `AuditChainVerifierTest`, the full verifier's report with an acceptance in the store equals the report without it.
- [ ] **Mutation proofs**, recorded in the PR:
  - matching by segment and offset only, without the SHA-256: "Exactly that line" fails;
  - dropping the later-record check: the shape D negative fails;
  - `applyCheckpoints` not skipping the new kind: the recovery's final verify fails;
  - branch (ii)'s single-record `eventHash` check removed: both keyed branch (ii) negatives fail;
  - branch (i)'s predecessor rule applied to the sole line as well: "Crash on the first record" fails, in both modes.
- [ ] **Help and CHANGELOG.** `--help` documents the subcommand and its exit code. One `### Added` entry under `[Unreleased]` names the command and when to use it: after a purge refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` and an investigation finds a crash line, not tampering. The entry and the help text also:
  - say that the checks do not prove a crash, and that the operator's investigation is what decides;
  - say to stop the server and to run the command as the account that owns the anchor store;
  - say that an anchor store containing an acceptance cannot be read by 0.7.x or earlier. A 0.7.x or earlier verifier stops with an unreadable checkpoint file. A server rolled back to 0.7.x fails closed, whenever it has an expired day to purge, with `AUDIT_RETENTION_CHAIN_UNVERIFIED: could not read the earlier retention anchors`, because its `FileAuditCheckpointSink.retentionAnchors()` (:87-105) parses every line and rejects the unknown kind. 0.7.1 reads anchors only when `verified > 0`.
- [ ] Every existing test under `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**` passes with unchanged expectations.

## Out of scope
- `docs/audit.md`: 213 owns it (wave 13). The PR description gives the scribe the paragraph for 213 to carry.
- `docs/plan/design/contract-register.md` rows for the new kind, subcommand and exit code: 254 owns that file in wave 9. The PR lists the rows, and the coordinator routes them, as 290 does for 291's.
- Accepting a line that does not parse. A torn line that does not parse is already tolerated by 292.
- Accepting a whole segment or a day, or any wildcard.
- Editing, moving or truncating audit files, in the command or in retention.
- Spring wiring and metrics. The Spring retention bean already uses `FileAuditCheckpointSink`, so it gets the read-back method with no wiring change.
