# 103 — Wire audit checkpoints, segmented audit files and retention into configuration

**Repo:** `.`
**Depends on:** 102
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditRetentionConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/HashChainedAuditSinkTest.java
- docs/configuration.md *(the `dataprism.audit` row and section only)*

## Goal

Make tasks 97 and 102 usable from the `dataprism.audit.*` vocabulary. Each
needs one property, `checkpoint.file-path` and `directory` respectively.
Retention defaults to six months, and the daily purge and periodic
checkpoints are scheduled and shut down with the context. Every
misconfiguration refuses startup with a stable code.

## Context

- `DataPrismProperties.java` `validate*()` methods use `refuse(CODE,
  message)`. `:153-198` of `docs/configuration.md` documents
  `dataprism.audit`.
- `DataPrismAutoConfiguration` — `dataPrismHashChainedAuditSink` (task 67)
  logs `FileAuditSink.OpenFailedException` server-side only. Follow the same
  split; see `docs/conventions.md` lines 62-82.
- Task 97's `FileAuditCheckpointSink` and the `AuditRecorder(…,
  AuditCheckpointSink)` constructor. Task 102's `SegmentedFileAuditSink` and
  `AuditRetention`.

## Acceptance

- [ ] The following properties exist:
      - `dataprism.audit.directory`
      - `dataprism.audit.checkpoint.file-path`
      - `dataprism.audit.checkpoint.interval` (Duration, default `PT5M`)
      - `dataprism.audit.retention` (Period, default `P6M`)
- [ ] One `ApplicationContextRunner` test per row asserts each refusal code:

      | Condition | Code |
      |---|---|
      | `directory` and `file-path` both set | `AMBIGUOUS_AUDIT_LOCATION` |
      | `directory` set without `checkpoint.file-path` | `RETENTION_REQUIRES_CHECKPOINT` |
      | `retention` below six months | `AUDIT_RETENTION_BELOW_MINIMUM` |
      | non-positive `checkpoint.interval` | `INVALID_AUDIT_CHECKPOINT_INTERVAL` |
      | checkpoint path equals the audit file path | `AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE` |
      | unopenable checkpoint path (no path in the client-visible message) | `AUDIT_CHECKPOINT_FILE_UNUSABLE` |
- [ ] `sink: hash-chained` with `directory` set wires
      `SegmentedFileAuditSink`. With `file-path` set it wires
      `FileAuditSink`, as today.
- [ ] When `checkpoint.file-path` is set, the `AuditRecorder` bean is built
      with the checkpoint sink. `checkpoint()` runs every `interval` on a
      scheduler bean, and a `SHUTDOWN` checkpoint is written on context
      close. A test asserts that the checkpoint file has `BOOT` and
      `SHUTDOWN` lines after a context start and stop.
- [ ] When `directory` is set, `AuditRetention.purge()` runs once at startup
      and then every 24h. A test with a fixed `Clock` and a pre-seeded
      expired segment asserts that it is gone after startup and that an
      anchor was written.
- [ ] Existing `HashChainedAuditSinkTest` cases pass. Edits to that file
      only add cases.
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure,data-prism-server -am verify`
      passes.
- [ ] `docs/configuration.md` documents all four properties, the six refusal
      codes and the six-month default. It notes that with `file-path` (single
      file) retention is not enforced in-process and is an operator task.

## Note from owner decision D5 (2026-10-06)

Retention below six months refuses startup **unless an explicit override
property is set**; it is not an unconditional refusal. The table row for
`AUDIT_RETENTION_BELOW_MINIMUM` is amended accordingly. Required:

- A boolean property `dataprism.audit.retention-override` (default `false`).
  Only when it is `true` is a `retention` under six months accepted, and
  `AuditRetention` is constructed with the override.
- Tests: below six months without the override refuses with
  `AUDIT_RETENTION_BELOW_MINIMUM`; the same value with the override starts;
  six months or more starts either way; the override with a compliant value
  is accepted and inert.
- `docs/configuration.md` states that Art. 19 allows other periods under
  Union or national law, and that setting the override is the operator's own
  legal responsibility. Data Prism does not judge whether the law applies.
- D3 is answered: the segmented sink is approved; `FileAuditSink` stays as is.

## Out of scope

- Oversight, re-identification and operator properties. That is task 104,
  which edits these same files after this task.
- Keyed-chain properties. Task 107 was dropped (D2, 2026-10-06).
- `server.json` and Compose environment variables.

## Owns extension (main session, 2026-10-06)

`data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java`
is added to Owns, for the three new BEANS rows only
(AutoConfiguredBeanClassificationTest sweeps every @Bean). No other open task
owns it. The new package-private `AuditMaintenance.java` in the same package
is also accepted.

## Owner decision (2026-10-06): purge integrity failures must be visible

When AuditMaintenance's purge fails with CHAIN_UNVERIFIED (or an anchor or
delete failure), keep serving and delete nothing, as now. Also:
- increment a metric, e.g. `dataprism.audit.retention.unverified`, tagged with
  the refusal code only (no paths, no writer ids);
- expose an `auditIntegrity` health status of DOWN, with the code and the
  first refused segment date as details (dates only), until a later purge
  succeeds.
Tests: a CHAIN_UNVERIFIED purge flips health to DOWN and increments the
metric; a subsequent clean purge restores UP.
If adding a Metric constant trips an exhaustiveness test (e.g.
PrivacyMetricsTest) in a file another open task owns, stop and report.

## Attempt 1 — failed

Branch at 289fa20. Reviewer: CHANGES.

Owns: the main session accepts data-prism-core Metric.java and PrivacyMetricsTest
(implied by the owner decision). The autoconfigure pom.xml (the optional
spring-boot-actuator line) is also accepted. 104 owns that file but has not
started; a note has been added to 104.

- Defect 1 — a checkpoint file inside the audit directory is not refused
  (DataPrismAutoConfiguration.java:416; FileAuditCheckpointSink.isSame only
  compares against the directory itself). `checkpoint.file-path=/d/cp.log`
  with `directory=/d` starts, and `/d/audit-<today>.log` even shares today's
  segment. Refuse at startup, with a stable code, any checkpoint path equal to
  or inside the audit directory, and equal to the file-path sink. Compare
  normalised, real paths where they exist. Test each case.
- Defect 2 — `dataprism.audit.directory` set with a non-hash-chained sink
  (slf4j or approved) and no checkpoint.file-path passes validation and fails
  with a raw NoSuchBeanDefinitionException (:435). Validate it in
  DataPrismProperties regardless of sink type, with a stable code (e.g.
  RETENTION_REQUIRES_CHECKPOINT), or refuse `directory` with a non-segmented
  sink, whichever matches the design. Test it.
- Defect 3 — docs/configuration.md: document the auditIntegrity health
  indicator's effect on liveness:
  - It is part of the aggregate /actuator/health.
  - Use Boot's liveness/readiness groups, or the server's /health, for
    probes, so a tamper finding does not cause restart loops.
  - The shipped server exposes no actuator endpoints.
- Defect 4 — docs/configuration.md:189 uses "compliant". Reword it, e.g.
  "a value of six months or more".
- Also fix:
  - the javadoc at DataPrismAutoConfiguration.java:439 claims a declared
    dependency that ObjectProvider does not create. Make it true (@DependsOn,
    or inject the recorder), so SHUTDOWN is always written after the last
    PERIODIC;
  - add a test that runs purge twice with a surviving later segment, proving
    retentionAnchors() read-back prevents a spurious CHAIN_UNVERIFIED.
- Run the full reactor `mvn verify` and mkdocs --strict; report the real exit codes.

## Owner decision (2026-10-06)

The four separate metric names (dataprism.audit.retention.unverified,
.anchor_failed, .delete_failed, .failed) are accepted in place of one tagged
metric.

## Attempt 2 — failed

HEAD 8029fd9. Reviewer: CHANGES (one gap). Everything else is confirmed, including shutdown ordering, the liveness docs and the purge-twice test.

- Defect — case-insensitive filesystem (macOS, default APFS) on a fresh deploy:
  `directory=/var/audit/FRESH` with `checkpoint.file-path=/var/audit/fresh/audit-<today>.log`
  passes validation because the directory does not exist yet. The sink then
  creates it, and the checkpoint ends up in the audit directory, sharing
  today's segment. Fix: repeat the containment check in
  dataPrismAuditCheckpointSink (DataPrismAutoConfiguration.java:~409), after
  the audit sink has created the directory, comparing real paths
  (toRealPath on the directory, and on the checkpoint's nearest existing
  ancestor). Refuse with the same code. Test it with a directory that does not
  exist yet and a case-variant checkpoint path. Skip that case with an
  assumption only where the filesystem is case-sensitive, and say so.
- Also fix:
  - the dependency registration names the hard-coded bean
    "dataPrismAuditRecorder" (:451). An application AuditRecorder under another
    name gets no ordering guarantee. Resolve the recorder bean name by type;
  - add a comment at DataPrismProperties.java:345-347 saying an unparseable path
    is caught later as AUDIT_CHECKPOINT_FILE_UNUSABLE, so it is not fail-open.
- Run the full reactor `mvn verify` and mkdocs --strict from the worktree root.
