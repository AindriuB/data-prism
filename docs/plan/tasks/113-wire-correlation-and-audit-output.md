# 113 — Wire inbound correlation headers and audit JSON output into configuration

**Repo:** `.`
**Depends on:** 103, 104, 110, 112, 127, 141
*(141 added 2026-10-07 by the 0.5.0 plan: 141 edits the `AuditIntegrityHealth` nested class of `DataPrismAutoConfiguration` and moves the reactor to Spring Boot 4.1.1. Write the `ApplicationContextRunner` tests against Boot 4. Owns is unchanged.)*
*(127 added 2026-10-06: task 127 edits `DataPrismAutoConfiguration`, owned here.)*
*(132 and 135 added 2026-10-06 by the 0.4.1 plan: 132 edits the `Hazelcast` section of `DataPrismProperties` and `ClusterBackedState` in `DataPrismAutoConfiguration`; 135 edits the `dataprism.hazelcast` row and section of `docs/configuration.md`. Rebase on the 0.4.1 cut (136) before starting. Owns is unchanged.)*
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/JwtCallerContextExtractor.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/CorrelationConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditOutputConfigurationTest.java *(new)*
- docs/configuration.md *(new `dataprism.correlation` row and section, and an `output` subsection under `dataprism.audit` only)*

## Goal

Turn tasks 108, 110 and 112 into deployed behaviour. The HTTP context
extractor reads one configured header, validates it with the configured
policy, and puts the result in the MCP transport context. The MCP server is
built with the configured requirement. `dataprism.audit.output.*` selects the
field mapping, the routing hints and an optional JSON projection directory.
Every misconfiguration refuses startup with a stable code.

## Context

- `JwtCallerContextExtractor.extract(HttpServletRequest)` builds the
  transport context with the caller. It is constructed with
  `DataPrismProperties` in `ServerSecurityConfiguration` (task 105 owns that
  file) and in integration-tests `SecurityConfig`. The constructor signature
  must not change.
- Task 103's `dataprism.audit.directory`, `retention`, `retention-override`
  and purge scheduler. Task 104's wiring of the `DataPrismMcpServer` bean
  through the admission overload. Task 110's `CorrelationRequirement`
  overload and `TRANSPORT_CONTEXT_CORRELATION_KEY`. Task 112's
  `AuditFieldMapping`, `AuditRouting`, `TeeAuditSink`,
  `SegmentedJsonAuditSink`, `JsonAuditRetention` and the mapped
  `Slf4jAuditSink`.
- `DataPrismProperties` `refuse(CODE, message)`, and the split between the
  server log and the client-visible message (`docs/conventions.md` lines
  62-82).

## Acceptance

- [ ] The following properties are bound:
      - `dataprism.correlation.inbound.header` (unset by default, which
        disables the feature)
      - `dataprism.correlation.inbound.format` (`opaque` or `traceparent`,
        default `opaque`)
      - `dataprism.correlation.inbound.pattern` (default
        `CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN`)
      - `dataprism.correlation.inbound.required` (default `false`)
      - `dataprism.audit.output.field-preset` (`canonical` or `ecs`, default
        `canonical`)
      - `dataprism.audit.output.field-names` (map)
      - `dataprism.audit.output.routing.event-dataset`
      - `dataprism.audit.output.routing.data-stream-type`,
        `.data-stream-dataset` and `.data-stream-namespace`
      - `dataprism.audit.output.json-directory`
- [ ] One `ApplicationContextRunner` test per row asserts each refusal code:

      | Condition | Code |
      |---|---|
      | header not an RFC 9110 token, or `Authorization`/`Cookie`/`Proxy-Authorization` | `INVALID_CORRELATION_HEADER` |
      | `pattern` does not compile | `INVALID_CORRELATION_PATTERN` |
      | `pattern` set with `format: traceparent` | `CORRELATION_PATTERN_NOT_APPLICABLE` |
      | `required: true` with no `header` | `CORRELATION_REQUIRED_WITHOUT_HEADER` |
      | `required: true` when no HTTP transport is configured | `CORRELATION_REQUIRES_HTTP_TRANSPORT` |
      | unknown field in `field-names` | `UNKNOWN_AUDIT_FIELD` |
      | invalid output path | `INVALID_AUDIT_FIELD_PATH` |
      | colliding paths | `AUDIT_FIELD_MAPPING_CONFLICT` |
      | invalid routing value | `INVALID_AUDIT_ROUTING_VALUE` |
      | `json-directory` without `sink: hash-chained` and `directory` | `AUDIT_JSON_REQUIRES_SEGMENTED_SINK` |
      | `json-directory` equal to `directory` | `AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT` |
      | `json-directory` not writable (no path in the client-visible message) | `AUDIT_JSON_DIRECTORY_UNUSABLE` |

- [ ] With a header configured, the extractor adds
      `InboundCorrelation.resolve(request.getHeaders(name), policy)` under
      `TRANSPORT_CONTEXT_CORRELATION_KEY`. A rejected value is logged at WARN
      as the code `EXTERNAL_CORRELATION_ID_DROPPED` and never as the value. A
      test with a captured log asserts that the rejected text is absent.
- [ ] A Spring-built server test with `required: true` asserts that a call
      without the header returns `EXTERNAL_CORRELATION_ID_REQUIRED`. With
      the header `X-Correlation-ID: synthetic-clid-0001`, the ALLOW audit
      event's `externalCorrelationId` is `synthetic-clid-0001`.
- [ ] With `json-directory` set, the `AuditSink` bean is
      `TeeAuditSink(SegmentedFileAuditSink, SegmentedJsonAuditSink)`. A test
      asserts that one call produces one native line and one `.ndjson` line
      with the same `eventHash`, and that the native directory still
      verifies with the verifier.
- [ ] `JsonAuditRetention.purge()` runs on task 103's schedule with the same
      `retention` and `retention-override`. A fixed-`Clock` test asserts that
      an expired `.ndjson` segment is removed at startup.
- [ ] With `sink: slf4j`, the sink is built with the bound mapping and
      routing. A test asserts that an `ecs` preset yields an `event.id`
      key-value pair.
- [ ] All task 103 and task 104 tests and all pre-existing autoconfigure tests
      pass unchanged.
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests -am verify`
      passes.
- [ ] `docs/configuration.md` documents every property, default and code
      above. It states that the pattern limits the id's shape and cannot
      limit its meaning, so a deployer should choose a pattern that
      admits only generated identifiers. It says "supports" and never
      "compliant" or "tamper-proof".

## Out of scope

- The operator port and `ServerSecurityConfiguration`. That is task 105.
- REST outbound headers. That is task 111, configured in source YAML.
- Example client snippets and shipping recipes. That is task 115.
- `docs/audit.md`. That is task 116.

## Owner decision C4 (2026-10-07)

The default inbound correlation-id pattern is the strict default, not
`[A-Za-z0-9._:-]{1,128}`. It accepts only a UUID, hex of 16 to 128 characters,
or a W3C traceparent. The broader pattern is available only by explicit
configuration, because it admits name-like tokens such as `jane.doe` and so
lets personal data be smuggled in as an id. The Owns list is unchanged.
