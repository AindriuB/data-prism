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

## Out of scope

- Oversight, re-identification and operator properties. That is task 104,
  which edits these same files after this task.
- Keyed-chain properties. That is task 107.
- `server.json` and Compose environment variables.
