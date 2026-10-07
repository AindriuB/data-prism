# 112 — Add a structured JSON audit projection with ECS field mapping and routing hints

**Repo:** `.`
**Depends on:** 109, 118
*(118 added 2026-10-06: task 118 adds undeclared-key cases to `PiiLogScanTest`, which this task also edits.)*
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java

## Goal

Enterprise log stacks such as ELK need audit events as JSON, with field names
they can map and a hint that routes them to their own index. The
hash-chained native segments stay the authoritative, verifiable record. This
task adds a rendering of each event as a JSON object under a configurable
field mapping, with a canonical preset and an Elastic Common Schema (ECS)
preset. It adds a durable, day-segmented JSON-lines projection sink that a
tee writes after the native sink, failing closed. It also gives the slf4j
sink the same mapped names as SLF4J key-value pairs, for a JSON log encoder.
The mapping renames and reshapes. It never adds a value from anywhere except
the record, apart from operator-configured routing constants.

## Context

- Owner decision C3 (recommended): keep the native chained file authoritative
  and add a separate JSON projection. The alternative, JSON as the chained
  format itself, would need the verifier to know the mapping.
- `docs/conventions.md#privacy-rules-a-diff-must-satisfy`: "No new
  `ObjectMapper` in or below `mcp`". `ArchitectureTest.onlyDesignatedClassesCreateMappers`
  enforces it, and `audit` is below `mcp`. Render with Jackson's streaming
  `JsonFactory`/`JsonGenerator` (no `ObjectMapper`) or a hand-written RFC 8259
  writer. `ArchitectureTest.java` must stay unchanged.
- `FileAuditSink` and task 102's `SegmentedFileAuditSink`: the fsync,
  poisoning and torn-write discipline to reuse. `AuditRecorder` leaves its
  state unchanged when the sink throws. A tee whose first write succeeded
  and second failed would otherwise let the next event reuse the sequence
  number already on disk.
- `Slf4jAuditSink` — the `dataprism.audit` logger message format.
  `PiiLogScanTest` parses that message field-aware, with shape exemptions
  for hex, timestamp and seq fields.
- Spring Boot structured logging (`logging.structured.format.*=ecs`) and
  logstash-logback-encoder both render SLF4J 2 key-value pairs. Confirm that
  Boot's ECS formatter includes key-value pairs at the Boot version on `main`
  (4.1.1 once task 141 lands) before relying on it in docs (task 116).
  *(Amended 2026-10-07 by the 0.5.0 plan; this previously said 3.5.16.
  Depends and Owns are unchanged.)*
- Elastic data-stream naming: dataset `[a-z0-9_.]`, namespace `[a-z0-9_]`,
  both 1–100 characters; type `logs`.

## Acceptance

- [ ] `AuditFieldMapping` maps each canonical field name to a dotted output
      path. The canonical names are the `AuditEvent` component names. The
      mapping is total: every canonical field is emitted exactly once. It
      has two presets, `canonical()` (identity) and `ecs()`, and
      `withOverrides(Map<String,String>)`. Overrides throw
      `IllegalArgumentException` with:
      - `UNKNOWN_AUDIT_FIELD` for an unknown canonical name;
      - `INVALID_AUDIT_FIELD_PATH` for a path not matching
        `[A-Za-z_@][A-Za-z0-9_@]*(\.[A-Za-z0-9_@]+)*`;
      - `AUDIT_FIELD_MAPPING_CONFLICT` for two fields on one path, or one
        path that is a prefix segment of another.

      One test per code.
- [ ] `ecs()` maps at least:

      | canonical | ECS |
      |---|---|
      | `timestamp` | `@timestamp` |
      | `eventId` | `event.id` |
      | `tool` | `event.action` |
      | `principalId` | `user.id` |
      | `externalCorrelationId` | `trace.id` |
      | `correlationId` | `dataprism.correlation_id` |
      | `policyDecision` | `dataprism.policy_decision` |

      Every other field goes under `dataprism.*` in snake_case. A test pins
      the whole preset.
- [ ] *(C5)* `ecs()` also emits `event.outcome`, derived only from
      `policyDecision`: `ALLOW` or a value starting `ALLOW:` gives `success`,
      an empty value gives `unknown`, and anything else gives `failure`. That
      includes `DENY`, `DENY:<code>` and the bare refusal codes the MCP tools
      write, such as `TOOL_NOT_PERMITTED`. Task 121 documents the forms. It is
      the only derived field. A test pins one case per form. *(Amended
      2026-10-06. The earlier rule mapped bare codes to `unknown`.)*
- [ ] `AuditRouting` holds optional `eventDataset`, `dataStreamType`,
      `dataStreamDataset` and `dataStreamNamespace`, validated against the
      Elastic rules above. A failure throws `INVALID_AUDIT_ROUTING_VALUE`. A
      routing path that collides with a mapped path throws
      `AUDIT_FIELD_MAPPING_CONFLICT`.
- [ ] `AuditJsonRenderer.render(AuditEvent, AuditFieldMapping, AuditRouting)`
      returns one line of JSON with no line break inside. A property-style
      test over generated events asserts the following. The multiset of leaf
      values equals the event's field values (sets and maps rendered as JSON
      arrays and objects), plus the routing constants, plus, with C5, the
      derived `event.outcome`, and nothing else. Inverting the mapping gives
      back an `AuditEvent` whose `AuditEventHash.compute` equals `eventHash`.
- [ ] `SegmentedJsonAuditSink(Path directory, mapping, routing)` appends to
      `directory/audit-YYYY-MM-DD.ndjson` (UTC date of the event), with
      `FileAuditSink`'s fsync and poisoning semantics. A test ports the
      torn-write case.
- [ ] `TeeAuditSink(AuditSink primary, AuditSink projection)` writes primary,
      then projection. If primary throws, projection is not called. If
      projection throws, the tee poisons itself: this and every later
      `record` throws a code-only `AUDIT_PROJECTION_FAILED` until restart.
      A test asserts that after a projection failure the recorder writes no
      second record with the same sequence.
- [ ] `JsonAuditRetention(Path directory, Period retention, Clock clock)`
      deletes `.ndjson` segments strictly older than retention, never today's,
      and applies task 102's six-month minimum and override rule. A
      fixed-`Clock` test asserts the files left.
- [ ] `Slf4jAuditSink` keeps its no-argument constructor and its exact
      message text, plus one new key `extCorrelation={}`. A new
      `Slf4jAuditSink(AuditFieldMapping, AuditRouting)` also attaches every
      mapped field as an SLF4J key-value pair, and with C5, `event.outcome`.
      A test with a list appender asserts the key-value set.
- [ ] `PiiLogScanTest` parses `extCorrelation` as a scanned field, not a
      shape-exempt one. It gains a not-vacuous case: a banned fixture value
      placed in `extCorrelation`, and separately in a key-value pair, is
      caught. It also gains a full-run case scanning the rendered key-value
      pairs. The existing cases pass, and edits to existing cases are limited
      to the parser.
- [ ] `ArchitectureTest.java` is unchanged.
- [ ] `mvn verify` over the full reactor passes with no edit outside `Owns`.

## Out of scope

- `dataprism.audit.output.*` properties and the purge schedule. That is task 113.
- A direct Elasticsearch, Logstash or HTTP sink. Not proposed. A network
  sink would put a remote dependency inside the fail-closed write path.
- Changing the native segment format, the verifier or checkpoints.
- Shipping recipes and `docs/audit.md`. Those are tasks 115 and 116.
