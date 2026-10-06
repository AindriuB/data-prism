# 118 — Render undeclared payload keys as `<undeclared>` on every refusal and warning sink

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** 101
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/JsonTreeScrubbingEngine.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PrivacyRefusedException.java *(Javadoc only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/FieldMetadata.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/RefusalPaths.java *(new, if a shared helper is needed)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/ProfilePrivacyPolicyResolver.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/JsonTreeScrubbingEngineTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/UndeclaredKeyRefusalTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/policy/ProfilePrivacyPolicyResolverTest.java
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/**
- data-prism-validation/src/test/java/io/github/aindriub/dataprism/validation/**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedLeafShapeGuard.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonUndeclaredKeyRefusalTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/AuditedRefusalException.java
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/UndeclaredKeyRefusalPathTest.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/**
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFilePiiScanTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/UndeclaredKeyFixture.java *(new)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java *(additive overloads only)*

## Goal

A `PrivacyRefusedException` carries a path, and some paths are built from
property names in the source payload rather than in the reviewed model. A
payload key can itself be personal data, for example a map keyed by email
address. Today such a key reaches the MCP error text and the server log.
This task finds every place a refusal path or an undeclared field name is
written, and makes each one render an undeclared key as `<undeclared>`. The
two PII scans then also fail on a leaked key, not only on a leaked value.

## Context

Origins found while planning. The task's first deliverable is a complete
list, so confirm these and add any others:
- `JsonTreeScrubbingEngine.java:~158-177`. `fieldPath = path + "." + field`
  uses the raw key even when `unknownProperty` is true. The disposition
  pointer already uses `UNDECLARED`. `UNKNOWN_FIELD` at `:173` throws with
  that path. `UNCLASSIFIED_STRUCTURE` at `:248` throws with the path of an
  undeclared nested value. The other throw sites at `:81`, `:87`, `:144`,
  `:278` and `:314` receive the same `path` argument.
- `FieldMetadata.undeclared(field)` stores the payload key as `fieldName()`.
  `ProfilePrivacyPolicyResolver.java:147-163` logs `field.fieldName()` at
  WARN for `REDACT_AND_WARN`, `DROP_AND_WARN` and `PASS_THROUGH_UNSAFE`. That
  log line is a sink in its own right, with no refusal involved.
- The validators (`SensitivePatternValidator.java:60-63`,
  `RawValueLeakValidator.java:70`, `SensitiveDataScanner`) walk the merged
  tree. Under a pass-through profile that tree contains undeclared keys, and
  `DefaultContextOrchestrator.java:209` throws `VALIDATION_FAILED` with the
  first violation's path.
- `ConfiguredJsonScrubbingEngine.java:102` prefixes the leak check's
  violation path. `ConfiguredJsonNestedLeafShapeGuard.java:76-104` throws
  with paths over the raw body.

Sinks found while planning:
- MCP error text. `"refused: " + code + " at " + path` is built in
  `GetEntityContextTool`, `CompareEntitySourcesTool` and task 101's
  `ToolCalls.java:122`. This text goes to the model.
- `PrivacyRefusedException.getMessage()` contains the path. Task 101's
  `AuditedRefusalException` copies it.
- The audit record. `fieldDispositions` already uses fixed keys (task 96).
  Confirm that no other audit field receives a path.
- Metric tags carry only the source name (`ServerPrivacyMetrics`). No trace
  attributes and no `@ExceptionHandler` exist today. Confirm this, and record
  the result in the close-out.

To find which segments of a validator path are declared, the dispositions
the orchestrator has already collected give every declared pointer per
source. Any segment that does not resolve to one of them is undeclared.
Where resolution is impossible, the whole path is `<undeclared>`, which is
the fail-closed rule.

Task 101 (in flight) owns `data-prism-mcp/**` and `DefaultContextOrchestrator`,
so this task waits for it. Task 110 waits for this task. Rules:
`CLAUDE.md` rule 2 and `docs/conventions.md#errors`.

## Acceptance

- [ ] The close-out lists every construction site of `PrivacyRefusedException`
      and `Violation`, and every sink that reads `path()`, `getMessage()` or
      `FieldMetadata.fieldName()`, with the file and line of each. A test
      exists for every site that can carry a payload-supplied name.
- [ ] Where a path segment comes from a property that the reviewed model or
      configured catalogue does not declare, `PrivacyRefusedException.path()`
      renders that segment as `<undeclared>`. This holds for `UNKNOWN_FIELD`,
      `UNCLASSIFIED_STRUCTURE`, `VALIDATION_FAILED`, the configured-source
      leak check and the nested-leaf shape guard. Declared segments and array
      indices are unchanged. `UndeclaredKeyRefusalTest` asserts this for each
      code, using the payload key `zzUndeclaredKeyQx7`.
- [ ] `getMessage()` of every such exception, and of `AuditedRefusalException`,
      does not contain `zzUndeclaredKeyQx7`. A test asserts this.
- [ ] Under `REDACT_AND_WARN`, `DROP_AND_WARN` and `PASS_THROUGH_UNSAFE`,
      the WARN line for an undeclared property contains `<undeclared>` and
      not the key. A declared but unannotated field is still named. A test
      with a list appender asserts both.
- [ ] The MCP error text for each refusal code above, on both tools, does not
      contain `zzUndeclaredKeyQx7` and does contain the code. A test asserts
      this per tool.
- [ ] No audit event field for these refusals contains the key. A recording
      `AuditSink` test checks every field of the DENY event.
- [ ] `PiiLogScanTest` and `AuditFilePiiScanTest` each gain:
      - a full run in which a source returns `zzUndeclaredKeyQx7` as a
        property name, once under a refusing profile and once under a
        pass-through profile, asserting the key appears nowhere in the
        captured log, the audit file or the tool results;
      - a not-vacuous case proving that each scanner reports the key when it
        is planted.

      The existing cases pass unchanged. The fixture key is synthetic.
- [ ] `mvn verify` over the full reactor passes, and the close-out reports the
      real exit code.

## Out of scope

- Changing the fixed `<source>:<refused>` / `merged:<refused>` disposition
  keys, or giving non-scrub refusals a different key. That is a separate
  follow-up.
- Wording in `docs/tools.md`. That is task 121.
- The correlation-id scans. That is task 114.
- Values in paths. Values never enter a path today, and that must stay true.
