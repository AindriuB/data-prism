# 293 — Let an operator accept a confirmed crash line so retention can purge past it

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` after 292 has merged. One PR, targeting `main`.
**Depends on:** 292 (this task extends 292's verifier API and retention rule 4; both edit `AuditRetention.java` and `AuditChainVerifier.java`), 291 (both insert into `CHANGELOG.md` `[Unreleased]`; 291 is in wave 8)
**Wave:** 9 (filed 2026-10-28).
- In wave 9 its Owns are disjoint from 252 (orchestration `follow`) and 254 (Spring limits and catalog wiring, `docs/configuration.md`, and the `CLUSTER_KEY_SHARED` and `NAME_KEY_SHARED` rows of `docs/plan/design/contract-register.md`).
- It does not own the contract register, so it shares no file with 254. See Out of scope.
- No earlier open brief owns `AuditCheckpoint.java`, `AuditCheckpointSink.java`, `FileAuditCheckpointSink.java` or `AuditChainVerifierCli.java` after 207 (which owned the CLI help text only, and is done before 292).
**Decisions:**
- **OD-292-3 (a), decided by the owner 2026-10-27.** Recovery from a crash line that parses (OD-292-1 (a)) works like this:
  - a verifier CLI subcommand records an operator-confirmed acceptance in the anchor store, as a new checkpoint kind holding the segment name, the byte offset and the SHA-256 of the exact line bytes;
  - the command refuses unless the line is currently a complete record that was not hashed, its instance has no later record anywhere in the directory, and the supplied hash matches the line's bytes;
  - retention tolerates exactly that accepted line and nothing else;
  - files on disk are not changed.
- **Trust (planner, at filing, from 292).** An acceptance is only as trustworthy as the anchor store. The attacker 292 defends against cannot write the anchor store, so cannot forge an acceptance. Whoever can write the anchor store can already vouch for chain starts, as `AuditRetention`'s Javadoc says.
- **Open, OD-293-1 (owner).** Should the command also require that the line chains as its instance's next record, meaning its `previousHash` equals that instance's last hashed `eventHash`? A genuine crash line always does: it is the recorder's next record, cut at or after `raw[19]`, so `previousHash` in `raw[18]` is intact.
  - (a) Add the check. Recommended: it costs one comparison and rejects a relocated or invented record.
  - (b) The three owner-decided conditions only.

  Build (a) unless the owner decides otherwise before work starts.
- **Open, OD-293-2 (owner).** A real crash cut inside `raw[23]` of a v3 record gives 24 fields under a v3 `recordVersion`. The verifier reports that as `FIELD_COUNT_MISMATCH`, which is a break. The line does not parse, so this command cannot accept it, and purge stays blocked at that day with no product recovery. This was so before 292 too: 292 pins it as refused.
  - (a) Accept and document as a known limit. Recommended for 0.8.0.
  - (b) Extend acceptance to that one shape in a later task.

## Goal
After 292, a real crash that leaves an unhashed line that parses keeps its day and every later expired day until an operator acts (OD-292-1 (a)). Give the operator a way to act that changes no audit file and widens nothing else: after investigation, the operator records an acceptance of that exact line in the anchor store, and purge then tolerates that line alone.

## The command
- `AuditChainVerifierCli accept-crash-line <audit-directory> --checkpoints <anchor-store-file> --segment <segment-file-name> --offset <byte-offset-in-segment> --sha256 <hex>`, taking `--hmac-key-file` (repeatable) and `--allow-unkeyed-writers` exactly as the verify mode does. The exact flag names may differ; the help text and the CHANGELOG name whatever is built.
- The line is the bytes of `<segment>` from `<offset>` up to, but not including, the next `\n`, or to end of file for an unterminated tail. A trailing `\r` is included. The SHA-256 is over those bytes as they are on disk, never over the verifier's joined copy.
- The command refuses, writes nothing, and exits with a distinct non-zero code (a new `EXIT_*` constant, documented in `--help`) unless every one of these holds:
  1. `<offset>` is the start of a line in `<segment>`, and `<segment>` is an `audit-YYYY-MM-DD[.NNNN].log` file in `<audit-directory>`;
  2. the line is an unhashed complete record by 292's definition, judged by the verifier's full-directory replay under the supplied keys;
  3. its instance has no later record anywhere in the directory: no later line, in segment order, that parses as that instance's record once one trailing `\r` is removed;
  4. `--sha256` matches the line's bytes;
  5. if OD-293-1 (a) stands, its `previousHash` equals its instance's last hashed `eventHash`.
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
  - *the line lookup the command needs.)*
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
- [ ] **Exactly that line.** After acceptance, append a second unhashed complete line elsewhere in D1, or edit one byte of the accepted line. `purge()` refuses again and keeps D1.
- [ ] **Negatives.** Each of these exits with the new refusal code, writes nothing to the store, and leaves every file's bytes unchanged:
  - wrong SHA-256;
  - the instance has a later record (shape D of 292: a copy of record 6 with record 7 later);
  - the line is hashed;
  - the line does not parse (a crash fragment, already tolerated);
  - the offset is not a line start;
  - a segment name outside the directory or not matching the segment pattern;
  - if OD-293-1 (a) stands, a complete record that does not chain from its instance's last hashed record.
- [ ] **Store compatibility.** `AuditCheckpoint.fromJsonLine(cp.toJsonLine())` round-trips the new kind with its fields. A `BOOT`, `PERIODIC`, `SHUTDOWN` or `RETENTION_ANCHOR` line is byte-identical to the base's. `retentionAnchors()` never returns an acceptance.
- [ ] **Verifier unchanged otherwise.** With an acceptance in the store, the full verifier's report on every existing `AuditChainVerifierTest` input equals the report without it.
- [ ] **Mutation proofs**, recorded in the PR:
  - matching by segment and offset only, without the SHA-256: "Exactly that line" fails;
  - dropping the later-record check: the shape D negative fails;
  - `applyCheckpoints` not skipping the new kind: the recovery's final verify fails.
- [ ] **Help and CHANGELOG.** `--help` documents the subcommand and its exit code. One `### Added` entry under `[Unreleased]` names the command and when to use it: after a purge refuses with `AUDIT_RETENTION_CHAIN_UNVERIFIED` and an investigation finds a crash line, not tampering. It also says that an anchor store containing an acceptance cannot be read by a 0.7.x or earlier verifier, which then stops with an unreadable checkpoint file.
- [ ] Every existing test under `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**` passes with unchanged expectations.

## Out of scope
- `docs/audit.md`: 213 owns it (wave 13). The PR description gives the scribe the paragraph for 213 to carry.
- `docs/plan/design/contract-register.md` rows for the new kind, subcommand and exit code: 254 owns that file in wave 9. The PR lists the rows, and the coordinator routes them, as 290 does for 291's.
- Accepting a line that does not parse, including OD-293-2's `raw[23]` shape.
- Accepting a whole segment or a day, or any wildcard.
- Editing, moving or truncating audit files, in the command or in retention.
- Spring wiring and metrics. The Spring retention bean already uses `FileAuditCheckpointSink`, so it gets the read-back method with no wiring change.
