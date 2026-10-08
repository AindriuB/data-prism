# 157 — Split `audit` into contract, `format`, `sink`, `checkpoint`, `retention` and `verify`; move the verifier CLI (pure move)

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2).
**Depends on:** 156, 160
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/** (only if a resource path is derived from a package name)
- data-prism-*/src/**/*.java in every other module (import lines and fully qualified name strings only)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java
- docs/**/*.md except docs/plan/**, docs/pack.md and docs/design-review.md (fully qualified names, import lines and the verifier command only)
- docs-site/diagrams/README.md (verifier FQCN only)
- README.md (verifier command only, if present)

## Goal
The `audit` package is 24 public types: the event contract, record format and
hashing, four sinks, checkpoints, retention and a 767-line verifier with its
CLI. Split it so `audit` holds the contract only and each concern has its own
subpackage, and make internals package-private where the split allows it.
Per owner decision D-0.6-4 the verifier CLI moves to `audit.verify` with no
forwarding class. **This changes the operator command** for verifying an
audit file: `java -cp <classpath> io.github.aindriub.dataprism.audit.AuditChainVerifierCli`
stops working. Task 162 puts it in the 0.6.0 CHANGELOG Breaking section.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/ — the 24 files.
- Planner's proposed grouping, a GUESS; the first acceptance step replaces it with a measured one:
  - `audit` (contract): AuditEvent, AuditSink, AuditEntry, AuditRecorder, AuditedEntityTypes, AuditCheckpointUnavailableException
  - `audit.format`: AuditRecordFormat, AuditEventHash, AuditJsonRenderer, AuditFieldMapping, AuditRouting, FieldCountMismatchException
  - `audit.sink`: FileAuditSink, SegmentedFileAuditSink, SegmentedJsonAuditSink, Slf4jAuditSink, TeeAuditSink
  - `audit.checkpoint`: AuditCheckpoint, AuditCheckpointSink, FileAuditCheckpointSink
  - `audit.retention`: AuditRetention, JsonAuditRetention
  - `audit.verify`: AuditChainVerifier, AuditChainVerifierCli
- At 438ef802 `audit` imports nothing from `core`; the only intra-audit import is of `AuditChainVerifier` (6 files), so `retention` and the sinks reach into the verifier. Same-package references without imports are not visible to that scan.
- Java has no friend packages: splitting a package can force members that were package-private to become public. That is the cost of this split; keep it visible (see Acceptance).
- Known string references: `docs/audit.md:271` and `:721` (verifier command), `docs-site/diagrams/README.md`, `AuditChainVerifierCli.java` usage text, `UnregisteredEntityTypeAuditTest` in data-prism-mcp. Grep for more; do not trust this list.
- Frozen by owner instruction: the `dataprism.audit` logger name (`Slf4jAuditSink.java:24`), every `dataprism.audit.*` property name, the audit record format and version.

## Acceptance
- [ ] First step, before any move: a class-level dependency scan of `audit` at the post-160 base (`jdeps -verbose:class` or ArchUnit), including package-private member access, and the final grouping derived from it, in the first commit's body with one line per class. The grouping may differ from the guess; it must not add a subpackage outside the five named.
- [ ] `audit` directly contains only contract types (the hand-back lists them); every other type is in exactly one of `audit.format`, `audit.sink`, `audit.checkpoint`, `audit.retention`, `audit.verify`, simple names unchanged.
- [ ] `AuditChainVerifierCli` is `io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli`; no class remains at the old FQCN.
- [ ] Visibility: the hand-back has a table of every type and every member whose visibility changed, old and new. A widening is allowed only where the split requires it and says which class needs it. At least the types not referenced from outside their new subpackage (by the scan) are package-private; any that stays public says why.
- [ ] Otherwise a pure move: `git diff -M` shows every moved file as a rename whose only changes are `package`, `import` and visibility modifiers.
- [ ] Frozen behaviour: the literal `"dataprism.audit"` in the `Slf4jAuditSink` logger is unchanged; the audit golden, format and version tests (`AuditRecordFormatTest`, `AuditRecordV3Test`, `AuditRecordVersionTest`, `AuditEventHashTest`) change only in `package` and `import` lines, and pass.
- [ ] ArchUnit locks the layout, in `ArchitectureTest`:
  - `audit` (the package itself, not `..audit..`) does not depend on any `audit.*` subpackage;
  - `audit.sink`, `audit.checkpoint`, `audit.retention` and `audit.format` do not depend on `audit.verify`, unless the scan shows a dependency that cannot be removed by placement, in which case it is an explicit class-level allow-list with a reason;
  - `slices().matching("..dataprism.audit.(*)..").should().beFreeOfCycles()` passes, or the task stops and reports the cycle.
  Each new rule is proven non-vacuous by a temporary mutation reported in the hand-back.
- [ ] String sweep: `grep -rnE 'dataprism\.audit\.[A-Z]'` over the repository, excluding `target/`, `.claude/`, `site/`, `logs/`, `docs/plan/`, `docs/pack.md`, `docs/design-review.md` and `CHANGELOG.md`, returns no line naming a type at its old location. Property names (`dataprism.audit.file-path` and the like) are lower-case and are not touched.
- [ ] `docs/audit.md` shows the new verifier command at both places and says once, next to the first, that the class moved in 0.6.0.
- [ ] The last commit's body carries the old FQCN → new FQCN table for every moved type; task 162 builds the migration page from it.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- Splitting `AuditChainVerifier` (767 lines) into smaller classes. That is a rewrite; file it as a follow-up if the scan suggests a seam.
- `oversight` (stays as it is).
- PLAN follow-ups (ak) and (al) on the verifier.
- A forwarding `AuditChainVerifierCli` at the old FQCN (D-0.6-4 rejected it).
- `CHANGELOG.md` (task 162).

## Outcome (2026-10-08, wave 3)
Merged onto `release/0.6.0-moves` (task branch head df47729a). `audit` now has 9 types in the root and 5 subpackages: `format`, `sink`, `checkpoint`, `retention`, `verify`. The verifier CLI is `audit.verify.AuditChainVerifierCli` with no forwarding class at the old FQCN (D-0.6-4); this breaks operators' command lines, and the CHANGELOG entry is task 162. ArchUnit locks the layout; the one cross-package edge that placement could not remove, `retention` to `verify`, is allowed at class level only (`AuditRetention` to `AuditChainVerifier`, because retention replays the chain before deleting a segment).

Six package-private members became public because each is used across the new package boundary: `FileAuditSink.TORN_TAIL_TERMINATOR`, `terminateTornTail()` and `closeQuietly()` (used by `AuditChainVerifier` and `FileAuditCheckpointSink`); `SegmentedFileAuditSink.segmentDate()` (used by `AuditChainVerifier` and `AuditRetention`); `AuditChainVerifier.verifySegments()` (used by `AuditRetention`); `AuditChainVerifierCli.run()` (used by tests in other audit packages).

Review polish: the `verifySegments` Javadoc now warns that it is not a full verification; Javadoc-only imports were replaced with FQCN links, including `RefusalPaths`, which closes follow-up (as) from 156; one sentence in `docs/audit.md` was made intact. Tester PASS twice on JDK 21 (full reactor, 1400 tests, 0 failures); reviewer APPROVE.
