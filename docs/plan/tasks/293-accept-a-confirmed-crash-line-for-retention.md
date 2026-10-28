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
- **OD-293-1 (a), decided by the owner 2026-10-28.** The command also requires the line's `previousHash` to equal its instance's last hashed `eventHash`. A genuine crash line always passes: it is the recorder's next record, and a cut that still parses keeps `raw[18]` intact. Its `sequence` must also be that record's `sequence + 1` (planner, 2026-10-28, from review: condition 5 (i) check 4).
- **OD-293-3 (a), decided by the owner 2026-10-28.** The command also accepts an instance's first line with `previousHash` GENESIS, provided that instance has no other record anywhere in the directory and the anchor store holds no `RETENTION_ANCHOR` for it (OD-293-4 (a)). It is one condition with three branches: condition 5 under "The command". OD-293-1 (a)'s predecessor rule applies to branch (i) only.
- **The key check on the sole line (planner, 2026-10-28, from external review of #172; reversed by OD-293-6 (a)).** It made a sole line's own `eventHash` recomputing under the supplied keys a condition on branch (ii). OD-293-6 (a) removes that condition on branches (ii) and (iii). The hash is now reported, not required.
- **OD-293-4 (a), decided by the owner 2026-10-28.** A crash can leave an unhashed line that parses as the first line of a day, after the instance's earlier records were purged and anchored. The instance then has no other record in the directory, and its line continues from a `RETENTION_ANCHOR`, not from GENESIS. Condition 5 gets a third branch, (iii), for that line. It is accepted only when it continues the anchor exactly, under the same anchor checks the replay applies to a chain start. Branch selection:
  - other records exist: branch (i);
  - no other record, and an anchor exists: branch (iii);
  - no other record and no anchor: branch (ii), which requires GENESIS at sequence 1.

  A GENESIS line for an instance that has an anchor is refused.
- **Several anchors (planner, 2026-10-28, applying OD-292-6 (a)).** As in 292's rule 6, branch (iii) uses only the instance's `RETENTION_ANCHOR` with the highest sequence. If two anchors share that highest sequence with different `headHash` values, the command refuses.
- **OD-293-5 (a), decided by the owner 2026-10-28.** The command refuses, on every branch, when the checkpoint store holds a `BOOT`, `PERIODIC` or `SHUTDOWN` checkpoint for the line's instance whose sequence is at or above the line's sequence (condition 7).
  - **Why.** A keyless attacker can delete a writer's later records and add `\r\n` to a genuine record, so that it looks like a crash line. The store cannot be forged, and such a checkpoint shows that the writer got past that line.
  - **Why "at or above".** On `origin/wave/0.8-wave4`, `AuditRecorder` writes `BOOT` at sequence 0 (:112). `PERIODIC` (:121-124) and `SHUTDOWN` (:128-132) carry `sequence.get()`, the last committed sequence. Those methods and `record` are all `synchronized`. A failed write rolls the counter back (:209-210, :225-229), so the sequence a checkpoint carries is always that of a committed record. A checkpoint at N therefore says record N was committed. A genuine crash line is the record after the last committed one, N+1, so every checkpoint of its instance is at most N, below the line. A checkpoint at the line's own sequence says that line was committed, so a crash line at that sequence is not genuine. `BOOT` at 0 never refuses, because every record's sequence is at least 1.
  - **Cost.** A power loss in which a checkpoint reached disk but the audit line did not is refused. That needs a disk that does not honour fsync. It fails closed, and the operator can still extend retention by hand.
- **OD-293-6 (a), decided by the owner 2026-10-28.** No branch requires the line's own `eventHash` to recompute. This reverses the sole-line key check above. The reason: a crash cut inside `raw[19]` (the `eventHash`) or inside a non-empty `raw[24]` can never match. OD-292-1 (a), and 292's "OD-292-1 (a) in practice", promise that every refused parsing shape can be recovered through 293.
  - **Acceptance is on structure:** branch (i) chains from the last hashed record by `previousHash` and `sequence`; branch (ii) is a GENESIS line at sequence 1 with no other record and no anchor; branch (iii) continues the highest anchor. Every branch also needs conditions 1-4, 6 and 7, the numbered list under "The command".
  - **Reported, not required.** Once condition 2 has parsed the line, the command reports whether the line's own hash recomputes, with the statuses under "Hash report". The report never decides acceptance.
  - **Keys.** Branch (i) keeps the key check through the chain replay, so a missing or wrong key is still refused there. On branches (ii) and (iii), `--hmac-key-file` is optional and used only for the report.
- **Review fixes that make the decisions hold (decided by the coordinator on the review's evidence, 2026-10-28).** Each fails closed.
  - **Branch (ii) requires sequence 1** (condition 5 (ii) check 2). This makes OD-293-5 (a)'s claim hold. Without it, a keyless attacker could delete records 2-50 of w, rewrite record 1's sequence to 51 with GENESIS kept, and end the line `\r\n`. That line passes condition 7 against `PERIODIC{w, 50}`. A genuine GENESIS record is always sequence 1: the counter starts at 0 and `record` takes `incrementAndGet` (`AuditRecorder.java:42`, `:189` on `origin/wave/0.8-wave4`).
  - **Condition 2 accepts only a line the replay skipped without parsing**: a `\r`-terminated line (`AuditChainVerifier.java:581-593`) or the unterminated tail (`:439-447`). A newline-terminated line that the replay parsed and did not hash is refused. That covers a record after a break, a duplicate second copy, and a content break. This makes OD-293-6 (a) hold: once the own hash is not a condition, an edited newline-terminated record must not pass as a crash line.
  - **A `NON_GENESIS_START` or `RETENTION_ANCHOR_REJECTED` finding for the line's instance refuses** (condition 5 (i) check 1). This makes OD-292-3 (a) hold, because the line must continue a chain the verifier accepts from its start. Other findings that are not breaks, such as `TRUNCATED_BEFORE_CHECKPOINT` and `MISSING_WRITER`, still fail no condition by themselves.
- **OD-293-2: withdrawn (2026-10-28).** Its premise was false. A torn write is always either `\r\n`-terminated or an unterminated tail, and the verifier skips both before parsing (`AuditChainVerifier.java:581-593`, `:439-447`). A `raw[23]` cut is therefore a tolerated fragment under 292's rules 2 and 5 and purges without this command. 292 pins it as purging.
- **What the checks prove (planner, at filing; updated for OD-293-5 (a) and OD-293-6 (a)).** They do not prove a crash. The operator's investigation is the gate. The help text and the CHANGELOG say so, and neither may call the checks proof of a crash.
  - **What condition 7 closes.** A record at or below a sequence the store has checkpointed for its instance cannot be passed off as a crash line.
  - **The residual.** A keyless attacker with write access to the audit directory can still craft a line that passes every condition from records written after the instance's last checkpoint. For example, take the instance's true next record after its last `PERIODIC`, delete everything after it, and end it `\r\n`. A shorter `dataprism.audit.checkpoint.interval` narrows that window. With no checkpoint sink, the store holds no `BOOT`, `PERIODIC` or `SHUTDOWN` for the instance, so condition 7 never refuses.
  - **No key on branches (ii) and (iii).** Nothing there authenticates the line's fields, so a crafted line can pass on structure alone. The hash report is the operator's signal: `UNKEYED_ONLY_FORGER_SHAPE` in particular is the shape a forger without the key produces. An acceptance tolerates that one line, and makes nothing else purgeable.
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
- **Replay mode.** Condition 2 and branch (i) of condition 5 are judged by the full-directory replay `AuditChainVerifier.verify(<audit-directory>, <anchor-store-file>, minimumRetention, keys, allowUnkeyedWriters)`, with the supplied keys and flag. `minimumRetention` is `--min-retention` or the verifier's default. This is the same replay the verify mode runs: not lenient, and with retention anchors honoured. Branches (ii) and (iii) of condition 5 are judged on the line's fields and the anchor store, because the replay never hashes that line. Condition 7 reads the same parsed store.
- **Which bytes are hashed, by both the CLI and retention.** The SHA-256 covers the on-disk bytes of the segment file, from the line's in-segment offset up to, but not including, the next `\n`, or to end of file for an unterminated tail, so a trailing `\r` is included. It never covers the verifier's joined copy, which appends `"\r\n"` to a non-final segment's unterminated tail (`join`, :178-194). Retention maps a joined offset back to (segment, in-segment offset) through `starts`, and re-reads those bytes from the segment file to compare.
- The command refuses, writes nothing, and exits with a distinct non-zero code (a new `EXIT_*` constant, documented in `--help`) unless every one of these holds:
  1. `<offset>` is the start of a line in `<segment>`, and `<segment>` is an `audit-YYYY-MM-DD[.NNNN].log` file in `<audit-directory>`;
  2. the line is one the verifier's full-directory replay, under the supplied keys, skipped without parsing: a `\r`-terminated line (`AuditChainVerifier.java:581-593`) or the unterminated tail (`:439-447`). It must also parse with `AuditRecordFormat.parse` once exactly one trailing `\r` is removed. This is narrower than 292's "unhashed complete record". A newline-terminated line that the replay parsed but did not hash is refused. That covers a record after a break, a duplicate second copy, and a content break;
  3. its instance has no later record anywhere in the directory: no later line, in segment order, that parses as that instance's record once one trailing `\r` is removed;
  4. `--sha256` matches the line's bytes;
  5. the line chains as its instance's next record (OD-293-1 (a), OD-293-3 (a), OD-293-4 (a), OD-293-6 (a)). One condition, with three branches, judged on structure only.
     - **Branch selection** uses two facts. The first is whether the instance has any other record anywhere in the directory, that is, any other line that parses as that instance's record once one trailing `\r` is removed. The second is whether the anchor store holds a `RETENTION_ANCHOR` for the instance.
       - Other records exist: branch (i).
       - No other record, and an anchor exists: branch (iii).
       - No other record, and no anchor: branch (ii).

       The line's `previousHash` never selects the branch.
     - **(i) Not the instance's first record.** The instance has another record. Checked in this order, in that replay:
       1. the instance is not broken before the line, and the replay reports no `NON_GENESIS_START` and no `RETENTION_ANCHOR_REJECTED` for it;
       2. it has at least one hashed record before the line;
       3. the line's `previousHash` equals the instance's last hashed `eventHash`;
       4. the line's `sequence` is that record's `sequence + 1`.

       The key is checked through the chain replay. A keyed chain replayed without its key, or under a wrong one, is broken at its first record or falls into the forger branch, so check 1 refuses it.
     - **(ii) The instance's sole line, with no anchor.** The instance has no other record and no `RETENTION_ANCHOR`, so this line is its only line. Checked in this order:
       1. its `previousHash` is GENESIS;
       2. its `sequence` is 1, which every genuine GENESIS record has.
     - **(iii) A line that continues from a retention anchor (OD-293-4 (a)).** The instance has no other record, and the anchor store holds at least one `RETENTION_ANCHOR` for it. Only the anchor with the highest sequence is used (OD-292-6 (a)). Checked in this order:
       1. no two anchors share that highest sequence with different `headHash` values;
       2. the line's `previousHash` is not GENESIS;
       3. the line's `sequence` is the anchor's `sequence + 1`;
       4. its `previousHash` equals the anchor's `headHash`;
       5. `anchorIsOldEnough(anchor, minimumRetention)` holds, with `minimumRetention` from "Replay mode";
       6. `anchorContradiction(anchor, checkpoints)` is null;
       7. the line's UTC date is not before the anchor's `segmentDate`.

       Checks 5-7 are the ones the replay's chain-start search applies (`AuditChainVerifier.java:633-653` on `origin/wave/0.8-wave4`).
  6. the anchor store's last byte is `\n`, or the store is empty;
  7. the store holds no `BOOT`, `PERIODIC` or `SHUTDOWN` checkpoint for the line's `instanceId` whose `sequence` is greater than or equal to the line's `sequence` (OD-293-5 (a)). This applies on every branch. `RETENTION_ANCHOR` and `CRASH_LINE_ACCEPTED` checkpoints are not counted. The checkpoints come from the verifier's own parse of `<anchor-store-file>`, the one `verify` already does (`AuditChainVerifier.java:249-271` on `origin/wave/0.8-wave4`), so no new read-back method is needed.
- **Refusal reasons.** Conditions are checked in their numbered order, and the first that fails is reported. A replay finding that is not a break fails no condition by itself, except the two that branch (i) check 1 names. So `TRUNCATED_BEFORE_CHECKPOINT` and `MISSING_WRITER` do not refuse. Each refusal prints which condition failed. For condition 5 it also prints the branch and the numbered check, for example `condition 5 (iii) check 3: the line's sequence is not the anchor's sequence + 1`. For condition 7 it prints the kind and sequence of the qualifying checkpoint with the highest sequence.
- **Hash report (OD-293-6 (a)).** Printed once condition 2 has parsed the line, whatever the later conditions decide, and never when condition 1 or 2 refuses. The `event` is the record that condition 2 parsed, with exactly one trailing `\r` removed and nothing else changed. The command tries `AuditEventHash.compute(event, k)` for every supplied key, in order, then the unkeyed `AuditEventHash.compute(event)`. It prints exactly one status token with a sentence of explanation:
  - `KEYED_MATCH <i>`: it recomputes under supplied key number `<i>`;
  - `UNKEYED_MATCH`: it recomputes unkeyed, and either no key was supplied or `--allow-unkeyed-writers` was passed;
  - `UNKEYED_ONLY_FORGER_SHAPE`: keys were supplied, `--allow-unkeyed-writers` was not, and only the unkeyed hash matches. The sentence says this is the shape a forger without the key produces, the case the verifier treats as a forger (`AuditChainVerifier.java:384-389` on `origin/wave/0.8-wave4`);
  - `NO_MATCH_LOOKS_CUT`: keys were supplied and nothing matches, unkeyed included. The sentence says the line looks like a cut inside the hashed fields, for example inside `raw[19]` or `raw[24]`, or an edit;
  - `NOT_CHECKED_NO_KEY`: no key was supplied and the unkeyed hash does not match. The sentence says "not checked: no key", and that in an unkeyed deployment a mismatch means the line looks like a cut. It does not say the line looks like a cut.

  The status never changes acceptance. Both `compute` overloads are already public (`AuditEventHash.java:185`, `:198` on `origin/wave/0.8-wave4`).
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
  - *the line lookup the command needs. That includes:*
    - *branch selection;*
    - *the checks of branches (i)-(iii), with branch (iii) using the existing `anchorIsOldEnough` and `anchorContradiction`;*
    - *condition 7, over the checkpoints `verify` already parses;*
    - *the hash report through `AuditEventHash.compute`.*

    *`AuditEventHash.java` is not changed.)*
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
- [ ] **Crash on the first record, plain and keyed (OD-293-3 (a), branch (ii)).** An instance's only line is its first record, complete with `previousHash` GENESIS, ended `\r\n`, in an expired day. A later expired day holds another instance's records. `purge()` refuses. `accept-crash-line` exits 0. `purge()` then deletes both days, and records no anchor for the crashed instance.
  - The plain variant reports `UNKEYED_MATCH`. In the keyed variant the line was written under the key passed as `--hmac-key-file`, and the report is `KEYED_MATCH 1`.
  - A two-key variant passes `[k0, k1]`, with the line written under `k1`. It is accepted, and the report is `KEYED_MATCH 2`, which pins that the report tries every key.
  - The keyed line run with no `--hmac-key-file` is also accepted, and the report is `NOT_CHECKED_NO_KEY` (OD-293-6 (a): the key is optional on branch (ii)).
  - Forger shape: keys `[k1]`, no `--allow-unkeyed-writers`, and a sole GENESIS line at seq 1 whose `eventHash` is the unkeyed hash. It is accepted, because the report does not gate, and the report is `UNKEYED_ONLY_FORGER_SHAPE`. The same line run with `--allow-unkeyed-writers` reports `UNKEYED_MATCH`.
- [ ] **Crash after a purge, plain and keyed (OD-293-4 (a), branch (iii)).** w writes seq 1-99 on D1. Its seq 100 is a crash line that parses, ended `\r\n`, and is the first line of D2. D1 is purged first, which records the anchor `{w, 99, h99}`. Then, with D2 expired:
  - `purge()` refuses D2;
  - `accept-crash-line` on the seq 100 line exits 0;
  - `purge()` deletes D2 and records no new anchor for w.

  In the keyed variant the line is written under the key passed as `--hmac-key-file`.
- [ ] **Cut crash lines (OD-293-6 (a)), plain and keyed.** Two cuts:
  - a v3 record cut inside `raw[19]`, which parses as v1 with a truncated `eventHash`;
  - one cut inside a non-empty `raw[24]`, which parses with its last field truncated.

  Each cut is ended `\r\n`. Each is tested on branch (ii), as an instance's sole GENESIS line at seq 1, and on branch (iii), as seq 100 after the anchor `{w, 99, h99}`. Each of the four is refused by `purge()` before acceptance. Each is accepted, and `purge()` then deletes its day. The report is `NO_MATCH_LOOKS_CUT` in the keyed variants, run with the key, and `NOT_CHECKED_NO_KEY` in the plain variants.
- [ ] **Crash after a checkpoint (OD-293-5 (a) guard), plain and keyed.** w writes seq 1-N, the store holds `PERIODIC{w, N}`, and record N+1 is a crash line that parses, ended `\r\n`. After the crash, `close()` writes `SHUTDOWN{w, N}`. The command accepts the line on branch (i), and `purge()` then deletes its day.
- [ ] **Recovery variants, plain and keyed.** Each is refused by `purge()` before acceptance and purges after it. Each also asserts that the acceptance's SHA-256 equals the SHA-256 of the on-disk line bytes, and differs from the hash of the joined copy wherever the two differ:
  - an unterminated complete record at the end of a non-final expired segment: a crash at 23:59 on D1, with the restart writing after midnight into D2, so D1's file is never reopened and `join` adds `"\r\n"` only in the joined copy;
  - an unterminated complete record as the final expiring segment's tail.
- [ ] **Partly deleted anchored day (optional; the reviewer's test).** The store holds the anchor `{w, 99, h99, D1}`, and only `audit-D1.0001.log` remains of D1, holding seq 51-99. w's seq 100 is a crash line that parses, at the start of D2. Before purge finishes D1, the command refuses at condition 5 (i) check 1. Seq 51 does not follow the anchor, so the replay reports w broken at 51, or `NON_GENESIS_START` if an anomaly precedes it. The test asserts that reason. Then `purge()` finishes D1 and refuses at D2 only. After that the command accepts the line on branch (iii).
- [ ] **Exactly that line.** After acceptance, append a second unhashed complete line elsewhere in D1, or edit one byte of the accepted line. `purge()` refuses again and keeps D1.
- [ ] **Negatives.** Each of these:
  - exits with the new refusal code, writes nothing to the store, and leaves every file's bytes unchanged;
  - asserts its refusal reason ("Refusal reasons"), so that each mutant below is killed by the check it names and not by an earlier one. Each fixture passes every check before the one it targets. A fixture's own `eventHash` need not recompute, because no branch gates on it.

  The negatives:
  - wrong SHA-256;
  - the instance has a later record (shape D of 292: a copy of record 6 with record 7 later);
  - the line is hashed;
  - the line does not parse (a crash fragment, already tolerated);
  - condition 2, a parsed newline-terminated line, keyed: an instance's sole GENESIS record at seq 1, with one field edited and its stored hash kept, ended `\n`. The replay parses it and reports a content break. Condition 2 refuses it, before any branch;
  - the offset is not a line start;
  - a segment name outside the directory or not matching the segment pattern;
  - branch (i), not the instance's first record:
    - an instance broken before the line (check 1);
    - a `NON_GENESIS_START` (check 1): a structural anomaly line, then w's seq 10 with a non-GENESIS `previousHash` and no anchor, then seq 11-12, hashed, and the crash line at seq 13 ended `\r\n`. w is not broken and has hashed records before the line, so only the finding refuses it;
    - keyed: the probe's chain run with no `--hmac-key-file`, and with a wrong one. Each is refused by check 1, because without its key the chain is broken at its first record;
    - no hashed record before the line (check 2): the instance's only other record is an earlier `\r`-terminated line;
    - a line whose `previousHash` does not equal the last hashed `eventHash`, at the right sequence (check 3, OD-293-1 (a));
    - a line whose `previousHash` equals the last hashed `eventHash` but whose sequence skips by 2 (check 4);
  - branch selection: a GENESIS line whose instance has other records (OD-293-3 (a)), pinned so that only branch selection can refuse it:
    - in keyed mode, w has hashed seq 1-5, and a copy of w's genuine record 1 is appended as the directory's last line, ended `\r\n`;
    - the store holds no checkpoint for w other than `BOOT`;
    - conditions 2, 3, 4 and 7 hold;
    - the instance has other records, so branch (i) applies and refuses it at check 3, because GENESIS is not `hash5`.

    The test asserts that this is the refusal reported;
  - branch (ii), the instance's sole line:
    - a sole line whose `previousHash` is not GENESIS (check 1);
    - a sole GENESIS line at seq 51, ended `\r\n`, with the store holding `PERIODIC{w, 50}`. This is the attack where records 2-50 are deleted and record 1's sequence rewritten. Check 2 refuses it, before condition 7 would pass it;
    - the same at seq 0 and at seq -1 (check 2). If the parser rejects a sequence, that variant asserts the condition 2 refusal instead, and the PR says so;
  - branch (iii), a line after a retention anchor, on the "Crash after a purge" directory with anchor `{w, 99, h99}` unless stated:
    - two anchors at sequence 99 with different `headHash` values (check 1);
    - a GENESIS line for w, at seq 1, as w's only line (check 2). Branch (iii) is selected and refuses it;
    - wrong sequence: the line at seq 101 (anchor + 2), with `previousHash` `h99` (check 3);
    - several anchors, keyed: anchors `{w, 50, h50}` and `{w, 99, h99}`, and w's genuine record 51, with a valid HMAC, appended `\r\n` to a later expired segment. Only the anchor at 99 is used, so check 3 refuses it;
    - `previousHash` not equal to the anchor's `headHash`, at seq 100 (check 4);
    - the anchor fails `anchorIsOldEnough`, through a `--min-retention` longer than the anchor's age (check 5);
    - the anchor fails `anchorContradiction`, through a checkpoint for w below sequence 99 that was recorded after the anchor's `segmentDate` (check 6);
    - starts before the segment: an anchor with `segmentDate` 03-02, and the line on 03-01 (check 7);
  - condition 7, the reviewer's attack (OD-293-5 (a)), plain and keyed. w writes seq 1-50, and the store holds `{w, 50}`, in two variants: `PERIODIC` and `SHUTDOWN`. Each case is refused by condition 7, and the reason names the checkpoint's kind and sequence 50:
    - branch (ii): records 2-50 deleted, and record 1 ended `\r\n`;
    - branch (i): records 11-50 deleted, and record 10 ended `\r\n`;
    - branch (iii): with D1 (seq 1-99) purged and anchored at `{w, 99, h99}`, D2 holds seq 100-150, and the store holds `PERIODIC{w, 120}` and `PERIODIC{w, 150}`. Records 101-150 are deleted and record 100 is ended `\r\n`. The reason names `PERIODIC` and sequence 150, the qualifying checkpoint with the highest sequence;
    - at the checkpoint: nothing deleted, and record 50 itself ended `\r\n`, on branch (i). This pins "at or above";
  - a store whose last byte is not `\n`, which is left byte-identical.
- [ ] **Store compatibility.** `AuditCheckpoint.fromJsonLine(cp.toJsonLine())` round-trips the new kind with its fields. A `BOOT`, `PERIODIC`, `SHUTDOWN` or `RETENTION_ANCHOR` line is byte-identical to the base's. `retentionAnchors()` never returns an acceptance.
- [ ] **Verifier unchanged otherwise.** On copies, in the new test, of the inputs used by `AuditChainVerifierTest`, the full verifier's report with an acceptance in the store equals the report without it.
- [ ] **Mutation proofs**, recorded in the PR. Each mutant is killed by the named test with that test's asserted refusal reason:
  - matching by segment and offset only, without the SHA-256: "Exactly that line" fails;
  - dropping the later-record check: the shape D negative fails;
  - `applyCheckpoints` not skipping the new kind: the recovery's final verify fails;
  - condition 7 skipped: every condition 7 attack case fails;
  - condition 7 comparing "above" instead of "at or above": the "at the checkpoint" case fails;
  - condition 2 accepting any unhashed complete record by 292's definition: the "parsed newline-terminated line" negative fails;
  - branch (i) check 1 ignoring `NON_GENESIS_START` and `RETENTION_ANCHOR_REJECTED`: the `NON_GENESIS_START` negative fails;
  - branch (ii) dropping its sequence check: the seq 51 negative fails;
  - condition 7 naming the first qualifying checkpoint found, not the highest: the branch (iii) condition 7 case fails on its reason;
  - branch (i) skipping its sequence check: the branch (i) "sequence skips by 2" negative fails;
  - the branch chosen by `previousHash == GENESIS` instead of by whether the instance has other records, with branch (ii) not re-checking that there is no other record: the "branch selection" negative fails;
  - branch (iii) skipping the `previousHash` check: the branch (iii) "`previousHash` not equal" negative fails;
  - branch (iii) skipping the sequence check: the branch (iii) "wrong sequence" negative fails;
  - branch (iii) accepting any anchor that matches, not only the highest: the "several anchors" negative fails;
  - branch (iii) dropping `anchorIsOldEnough`, `anchorContradiction` or the starts-before-segment check, one mutant each: the matching branch (iii) negative fails;
  - branch selection ignoring the anchor, so an instance with no other record always takes branch (ii): the branch (iii) GENESIS negative fails, and so does "Crash after a purge";
  - branch (i)'s predecessor rule applied to the sole line as well: "Crash on the first record" fails, in both modes;
  - acceptance gated on the line's own hash: the `raw[19]` case of "Cut crash lines" fails, on both branches.
- [ ] **Help and CHANGELOG.** `--help` documents the subcommand and its exit code. One `### Added` entry under `[Unreleased]` names the command and when to use it: after a purge refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` and an investigation finds a crash line, not tampering. The entry and the help text also:
  - say that the checks do not prove a crash, and that the operator's investigation is what decides;
  - say to stop the server and to run the command as the account that owns the anchor store;
  - say to re-run purge until it refuses only at the crash line's day, then run the command. A day the earlier purge left partly deleted must be finished first (Acceptance, "Partly deleted anchored day");
  - say that the command refuses when a `BOOT`, `PERIODIC` or `SHUTDOWN` checkpoint shows the writer got past the line (OD-293-5 (a)), and that a power loss on a disk that does not honour fsync can trigger this. The operator can then extend retention by hand. With no checkpoint sink configured, this check never refuses;
  - say that the command reports whether the line's own hash recomputes, and list the statuses. `NO_MATCH_LOOKS_CUT` looks like a cut or an edit, `UNKEYED_ONLY_FORGER_SHAPE` is the shape a forger without the key produces, and `NOT_CHECKED_NO_KEY` means no key was given. The report does not decide acceptance. On a first line, or a line after a retention anchor, the key is optional and used only for the report (OD-293-6 (a));
  - say that an anchor store containing an acceptance cannot be read by 0.7.x or earlier. A 0.7.x or earlier verifier stops with an unreadable checkpoint file. A server rolled back to 0.7.x fails closed, whenever it has an expired day to purge, with `AUDIT_RETENTION_CHAIN_UNVERIFIED: could not read the earlier retention anchors`, because its `FileAuditCheckpointSink.retentionAnchors()` (:87-105) parses every line and rejects the unknown kind. 0.7.1 reads anchors only when `verified > 0`.
- [ ] Every existing test under `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**` passes with unchanged expectations.

## Out of scope
- `docs/audit.md`: 213 owns it (wave 13). The PR description gives the scribe the paragraph for 213 to carry.
- `docs/plan/design/contract-register.md` rows for the new kind, subcommand and exit code: 254 owns that file in wave 9. The PR lists the rows, and the coordinator routes them, as 290 does for 291's.
- Accepting a line that does not parse. A torn line that does not parse is already tolerated by 292.
- Accepting a whole segment or a day, or any wildcard.
- Editing, moving or truncating audit files, in the command or in retention.
- Spring wiring and metrics. The Spring retention bean already uses `FileAuditCheckpointSink`, so it gets the read-back method with no wiring change.
