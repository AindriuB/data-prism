---
title: Migrating to 0.6.0
description: What breaks when you upgrade Data Prism from 0.5.x to 0.6.0, by audience, with old-to-new names, new refusal codes and the YAML quoting rule.
---

# Migrating from 0.5.x to 0.6.0

0.6.0 is a clean break. Nothing is deprecated and there are no forwarding types
at the old names: a class that moved is simply gone from its old fully
qualified name, so every consumer recompiles and changes imports. This page
lists every breaking change, what you have to do about it, and the old and new
names. The shorter version is the `[Unreleased]` section of the
[changelog](changelog.md).

Package names below are abbreviated by dropping the prefix
`io.github.aindriub.dataprism.`; the commands and properties are written in full.

## Who has to do what

| If you are... | Read | The short version |
|---|---|---|
| An operator running the standalone server | [Operators and configuration](#operators-and-configuration) | Change the audit verifier command. Check your YAML files against the stricter readers. Change log levels set on the renamed logger categories. |
| Writing a `DataSourceAdapter` or other extension | [Adapter and extension authors](#adapter-and-extension-authors) | Change imports. Return a record. Expect Jackson 3 types in the signatures that expose JSON trees. |
| Embedding the Spring Boot starter | [Spring integrators](#spring-integrators) | Change imports of the properties types and the JWT classes. Do not combine your own `AuditSink` with a JSON audit directory. |
| Reading or verifying the audit trail | [Audit consumers](#audit-consumers) | The record format is unchanged. The verifier class name changed, and there is a new read-only listener SPI. |

What did **not** change: the `dataprism.*` property names, the
`io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration` class name
and its entry in `AutoConfiguration.imports`, every refusal code that existed
in 0.5.x, the audit record format (`recordVersion` 3) and the `dataprism.audit`
logger name. The only new property is
`dataprism.audit.listeners.queue-capacity`, and the new refusal codes are in
[New refusal codes](#new-refusal-codes).

## Operators and configuration

### The audit verifier has a new class name

The verifier is now
`io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli`. The old name,
`io.github.aindriub.dataprism.audit.AuditChainVerifierCli`, no longer exists and
Java reports that it cannot find or load the main class.

```sh
# 0.5.x
java -cp <classpath> io.github.aindriub.dataprism.audit.AuditChainVerifierCli /path/to/audit.log
# 0.6.0
java -cp <classpath> io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli /path/to/audit.log
```

Find the old name in your runbooks, cron entries and CI jobs. The arguments and
the exit codes are unchanged. See [the audit chain](audit.md) for the exit
codes.

### YAML files are read more strictly

Six readers parse a YAML file by hand rather than through Spring's property
binding: the model descriptor file (`dataprism.privacy.descriptor-file`), the
json-sources file (`dataprism.json-sources.config-location`), the privacy
profiles, the vocabularies, the security policy and the REST sources. All six
now refuse, at startup, files that 0.5.x accepted and read differently from what
they said. **A configuration that loaded on 0.5.x may now refuse to start.** That
is intended: each mistake below changed what the file meant, without an error.

| Code | What triggers it | What to do |
|---|---|---|
| `DUPLICATE_CONFIG_KEY` | The same key twice in one mapping, at any depth. 0.5.x kept the last one. This includes two models, fields, profiles, sources or roles with the same name. | Remove the duplicate. |
| `UNKNOWN_CONFIG_KEY` | A key outside the allowed set of a fixed-schema mapping. 0.5.x ignored it in every reader except the security policy, which refused an unknown top-level key. | Fix the spelling, or remove the key. A misspelt key used to mean the setting was silently not applied. |
| `NON_STRING_CONFIG_SCALAR` | A string-typed field written as a number, a boolean or an empty value (`purpose: 42`, `model-version: 1.0`, `locale:`, `timeout: true`). | Quote it. |
| `INVALID_CONFIG_BOOLEAN` | A boolean-typed field (`exposed`, `descendable`, `override`, `identifier`) that is not exactly `true` or `false`: `yes`, `no`, `on`, `off`, `True` and `1` are refused. | Write `true` or `false`. |
| `LEADING_ZERO_CONFIG_NUMBER` | A numeric-typed field (a band bound, a vocabulary `version`) written with a leading zero, such as `010`. 0.5.x read it as octal. | Write the decimal number. Quoting does not help here: the parser cannot tell `"010"` from `010`. |
| `TRAILING_CONFIG_CONTENT` | A second YAML document, or anything after the first. A bare trailing `---` counts. | Remove it. A `...` end marker is accepted. |
| `UNSUPPORTED_CONFIG_YAML` | An alias (`*name`), an anchor (`&name`) or an explicit tag (`!custom`, `!!int`). | Write the value out in full. |
| `INVALID_CONFIG_SHAPE` | A section present but not a mapping (or a pool not a list), including an empty one such as `fields:` with nothing after it, or an empty `tls:`. | Write the mapping, or omit the key. An empty `tls:` used to disable `requireHttps`; it is now refused. |
| `NULL_LIKE_CONFIG_SCALAR` | A string field whose text is `~`, `Null` or `NULL`. | Write the text you mean. Quoting does not help. A value that is present but null (an empty value, or lower-case `null`) in a string field is refused as `NON_STRING_CONFIG_SCALAR`. |

Messages name the kind of file, the path and the key (cut to 64 characters, with
control characters replaced) and never a value. The full table, with the exact
set of keys per reader, is in
[Strict keys in YAML configuration files](configuration.md#strict-keys-in-yaml-configuration-files).

**The quoting rule.** A value in a string-typed field that YAML reads as a number
or a boolean has to be quoted, or the file is refused. `timeout: PT2S` and
`model-version: customer-v1` are text and need nothing. `model-version: 1.0`,
`1e3`, `42` and `true` are a number or a boolean, so write
`model-version: "1.0"`. Not everything that looks numeric is a number to a YAML 1.2
parser: see the next section for `010`, `0x1F`, `1_000` and `yes`, which are text
and load as written.

### YAML 1.2: values that changed meaning

The YAML reader moved from Jackson 2 (YAML 1.1) to Jackson 3 (YAML 1.2). Some
unquoted values mean something different. Where a wrong reading would be
dangerous the readers now refuse, as above. Where the new reading is simply the
text you wrote, they load it, so a string field that depended on the old
reading changes value **without an error**. Check any string field (a purpose, a
path, a pool entry) that held one of these.

| Written in the file | 0.5.x | 0.6.0 |
|---|---|---|
| `yes`, `no`, `on`, `off` in a boolean field | a boolean | refused, `INVALID_CONFIG_BOOLEAN` |
| `yes`, `no`, `on`, `off`, `True`, `FALSE` (any spelling other than lower-case `true` or `false`) in a string field | the text `true` or `false` | the text as written. **Not refused** |
| `010` or `0777` in a numeric field (a band bound, a vocabulary version) | octal (8, 511) | refused, `LEADING_ZERO_CONFIG_NUMBER` |
| `010` or `0777` in a string field | the text `8` or `511` | the text as written. **Not refused** |
| `1e3`, `1.0`, `42`, `true` in a string field | not characterised | refused, `NON_STRING_CONFIG_SCALAR`; quote it |
| `~` in a string field | read as absent | refused, `NULL_LIKE_CONFIG_SCALAR` |

The octal and boolean rows were pinned on 0.5.x by characterisation tests. The
0.5.x reading of `1e3`, `1.0`, `42` and `true` in a string field was not pinned;
0.6.0 refuses them, whatever 0.5.x did with them.

Enum values (classifications, actions, purposes) are still matched without regard
to case and with surrounding whitespace trimmed.

### Error messages no longer echo URLs or credentials

The json-sources reader, the REST sources reader, `RestSource` and the
json-sources initializer used to repeat the configured `base-url` or location in
some messages. They no longer do. A config location in a message is now shown as
`<location not shown>` (when it contains an `@`, or cannot be parsed), as a local
path cut at the first `?` or `#`, or as `scheme://host[:port]` for a remote
location. A token placed in a host name would still show. If you grep logs for
those messages, search on the code, not the URL.

### Log categories were renamed

If you set log levels on any of these, change them. The loggers that moved are
named after their class, and the class names changed.

| 0.5.x logger category | 0.6.0 logger category |
|---|---|
| `io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration$AuditSinkSelection` | `io.github.aindriub.dataprism.spring.boot.AuditSinkSelection` |
| `io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration$JsonProjection` | `io.github.aindriub.dataprism.spring.boot.JsonProjection` |
| `io.github.aindriub.dataprism.spring.boot.JwtCallerContextExtractor` | `io.github.aindriub.dataprism.spring.boot.jwt.JwtCallerContextExtractor` |

The `dataprism.audit` category is unchanged, and so is the
`DataPrismAutoConfiguration` category, which still carries the checkpoint and
HTTP-transport log lines.

### A JSON audit directory needs the built-in sink

`dataprism.audit.output.json-directory` writes through a tee behind the built-in
hash-chained sink. Two new startup refusals protect that:

- `AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK`: a `json-directory` is set with
  `dataprism.audit.sink=hash-chained`, and an application `AuditSink` bean
  (a `@Bean` method, a `FactoryBean<AuditSink>`, a lazy definition, or a bean
  overriding the built-in sink's name) replaces the built-in sink. Nothing would
  write to the directory, yet retention would still purge it. Remove either the
  application sink or the `json-directory`.
- `AUDIT_JSON_PROJECTION_MISSING`: a `json-directory` is set, but the framework's
  JSON projection bean is not registered (for example it was overridden, with
  bean overriding switched on).

Neither message contains a path.

### Descriptor-file errors carry the reader's rule

`INVALID_MODEL_DESCRIPTOR_FILE` is unchanged, but it now chains the reader's own
code as the cause (for example `UNKNOWN_CONFIG_KEY`) so you can see which rule the
file broke. The cause has the code, the path and the key name only, never a
value. A reader message without a code, an enum message and an I/O message are
not chained.

### New property and metadata

`dataprism.audit.listeners.queue-capacity` (default `1024`, must be at least 1;
see [`AuditEventListener`](#audit-consumers)) is the only new property. The
autoconfigure jar now ships `META-INF/spring-configuration-metadata.json`, so an
IDE completes and describes `dataprism.*` keys in YAML.

## Adapter and extension authors

### Imports: moved types

Every public type that moved is in this table, 51 in all: 34 from `core`, 15
from `audit` and 2 from `spring.boot`. `core.spi` is the extension-facing
package: the interfaces you implement and the request types you receive are
there. A type not listed did not move.

| Old | New |
|---|---|
| `core.Capability` | `core.model.Capability` |
| `core.ConsistencyFinding` | `core.model.ConsistencyFinding` |
| `core.DataRequest` | `core.spi.DataRequest` |
| `core.DataSourceAdapter` | `core.spi.DataSourceAdapter` |
| `core.DefaultFieldMetadataResolver` | `core.engine.DefaultFieldMetadataResolver` |
| `core.EntityCorrelationService` | `core.spi.EntityCorrelationService` |
| `core.FieldMetadata` | `core.model.FieldMetadata` |
| `core.FieldMetadataResolver` | `core.spi.FieldMetadataResolver` |
| `core.IdentityResolver` | `core.spi.IdentityResolver` |
| `core.InMemoryScopeBudget` | `core.limits.InMemoryScopeBudget` |
| `core.InvestigationContext` | `core.model.InvestigationContext` |
| `core.JsonTreeScrubbingEngine` | `core.engine.JsonTreeScrubbingEngine` |
| `core.Metric` | `core.metrics.Metric` |
| `core.PassThroughIdentityResolver` | `core.spi.PassThroughIdentityResolver` |
| `core.PrivacyContext` | `core.model.PrivacyContext` |
| `core.PrivacyMetrics` | `core.metrics.PrivacyMetrics` |
| `core.PrivacyRefusedException` | `core.refusal.PrivacyRefusedException` |
| `core.PrivacyScopeType` | `core.model.PrivacyScopeType` |
| `core.PseudonymisationVersion` | `core.model.PseudonymisationVersion` |
| `core.RefusalCodes` | `core.refusal.RefusalCodes` |
| `core.RefusalPaths` | `core.refusal.RefusalPaths` |
| `core.RequestLimits` | `core.limits.RequestLimits` |
| `core.ScopeBudget` | `core.limits.ScopeBudget` |
| `core.ScrubResult` | `core.model.ScrubResult` |
| `core.ScrubbingEngine` | `core.spi.ScrubbingEngine` |
| `core.SecretKeyProvider` | `core.spi.SecretKeyProvider` |
| `core.SourceCallContext` | `core.spi.SourceCallContext` |
| `core.SourceProvenance` | `core.model.SourceProvenance` |
| `core.SourceTree` | `core.engine.SourceTree` |
| `core.SourceValues` | `core.engine.SourceValues` |
| `core.StrictYaml` | `core.model.StrictYaml` |
| `core.SyntheticValueSource` | `core.spi.SyntheticValueSource` |
| `core.Text` | `core.engine.Text` |
| `core.ValueTokenSource` | `core.spi.ValueTokenSource` |
| `audit.AuditChainVerifier` | `audit.verify.AuditChainVerifier` |
| `audit.AuditChainVerifierCli` | `audit.verify.AuditChainVerifierCli` |
| `audit.AuditFieldMapping` | `audit.format.AuditFieldMapping` |
| `audit.AuditJsonRenderer` | `audit.format.AuditJsonRenderer` |
| `audit.AuditRecordFormat` | `audit.format.AuditRecordFormat` |
| `audit.AuditRetention` | `audit.retention.AuditRetention` |
| `audit.AuditRouting` | `audit.format.AuditRouting` |
| `audit.FieldCountMismatchException` | `audit.format.FieldCountMismatchException` |
| `audit.FileAuditCheckpointSink` | `audit.checkpoint.FileAuditCheckpointSink` |
| `audit.FileAuditSink` | `audit.sink.FileAuditSink` |
| `audit.JsonAuditRetention` | `audit.retention.JsonAuditRetention` |
| `audit.SegmentedFileAuditSink` | `audit.sink.SegmentedFileAuditSink` |
| `audit.SegmentedJsonAuditSink` | `audit.sink.SegmentedJsonAuditSink` |
| `audit.Slf4jAuditSink` | `audit.sink.Slf4jAuditSink` |
| `audit.TeeAuditSink` | `audit.sink.TeeAuditSink` |
| `spring.boot.JwtCallerContextExtractor` | `spring.boot.jwt.JwtCallerContextExtractor` |
| `spring.boot.JwtDecoderSupport` | `spring.boot.jwt.JwtDecoderSupport` |

`core.model.StrictYaml` already existed in 0.5.x (as `core.StrictYaml`) with one public helper
(`enumValue`). It now carries the readers' shared contract (the nine codes,
`readMapping`, `requireOnlyKeys` and typed accessors); only code that wrote its
own YAML configuration reader on top of it is affected.

### Source models must be records

A `DataSourceAdapter` response type, and every user type nested in it, must now
be a record, read by its components only. Anything else is refused with
`SOURCE_MODEL_NOT_A_RECORD`:

- at startup, from the declared types (including generic arguments, arrays,
  wildcards and type-variable bounds), so a response type that is a bean, or a
  JDK type such as `String`, fails before the first request;
- at request time, from the actual object graph, for a component declared as
  `Object` or an interface that holds a bean at runtime.

The message names the class's simple name only. If your adapter returned a bean
or a `String`, return a record. Concretely refused: a bean at any depth, a user
subclass of a collection or a map, an enum written as an object, a
`Throwable`, a class with its own class-level serializer that is not a record,
`@JsonProperty` or `@JsonGetter` on a method that is not a record component, and
a JDK class that Jackson would read through getters.

Still allowed, because they are the model author's explicit choice:
`@JsonAnyGetter`, `@JsonValue`, `@JsonUnwrapped` components, `@JsonNaming`,
`@JsonFormat`, and `@JsonSerialize` on a record or on one of its components. The
engine still classifies whatever they produce. The one deliberate difference from
Jackson 2 that you might hit: `@JsonAppend(attrs = @Attr("<component name>"))` is
refused, where Jackson 2 printed the component.

Map keys: a declared key type that is an interface or abstract (`CharSequence`,
`Comparable`, `Serializable`) is checked per key at request time. JDK key types
pass; a user class is refused at any depth. JDK `StringBuilder` and `CharBuffer`
keys are refused, where Jackson 2 wrote their text. A `Date` key declared as
`Comparable` is written by `Date.toString()`, which depends on the time zone, as
it did on Jackson 2. Map keys and dynamic property names are treated as
undeclared data: see
[Profiles that admit unclassified data](extending.md#profiles-that-admit-unclassified-data).

### `java.time` and `Optional`

Both are now accepted in a source model, where Jackson 2 refused them. `java.time`
values are written as ISO-8601 text and an `Optional` is unwrapped (empty is
null). The engine still classifies or refuses the result like any other field.
Legacy `Date`, `Timestamp`, `java.sql.Date` and `Calendar` stay epoch
milliseconds, and enums stay written by name.

### Jackson 3: signatures that changed

The classpath is Jackson 3 (`tools.jackson`). `com.fasterxml.jackson.annotation`
(`jackson-annotations`) is the one Jackson 2 package that stays, because Jackson 3
still reads those annotations. Public signatures that exposed a Jackson 2 type now
expose the Jackson 3 one. Update imports from `com.fasterxml.jackson.databind` to
`tools.jackson.databind`:

| Type and member | Change |
|---|---|
| `core.engine.SourceTree.of(Object)`, `newObject()`, `newArray()` | return `tools.jackson.databind` `JsonNode`, `ObjectNode`, `ArrayNode` |
| `core.engine.SourceTree.text(String)` | returns `StringNode` (was `TextNode`) |
| `core.model.ScrubResult` | the component, accessor and constructor `ObjectNode` is `tools.jackson.databind.node.ObjectNode` |
| `core.policy.Generalizer.generalise(JsonNode, ...)` | takes the Jackson 3 `JsonNode` |
| `orchestration.ContextResponse` | `entity()` and the constructors use the Jackson 3 `ObjectNode` |
| `mcp.CompareEntitySourcesTool.ComparisonResponse.identity` | Jackson 3 `ObjectNode` |
| `validation.LlmResponseValidator.validate`, `RawValueLeakValidator.validate`, `SensitivePatternValidator.validate` | `response` is the Jackson 3 `JsonNode` |
| `validation.SensitiveDataScanner.scan` (both overloads) | takes the Jackson 3 `JsonNode` |
| `mcp.DataPrismObjectMapper` and `create()` | no longer public. They returned `ObjectMapper`, and no public method hands out or accepts data-prism's mapper any more |
| `mcp.GetEntityContextTool`, `mcp.CompareEntitySourcesTool` constructors | lose their `ObjectMapper` parameter |

No `throws JsonProcessingException` was removed from a public signature. The MCP
tools return "the response could not be serialised" when converting a response
for the MCP result fails; on 0.5.x a failed conversion returned "the request
could not be completed".

Dependency swaps for anything that depended on the data-prism poms: the MCP SDK
binding is `io.modelcontextprotocol.sdk:mcp-json-jackson3` (was
`mcp-json-jackson2`), and the Spring side takes
`spring-boot-starter-jackson` from Spring Boot 4.1 (the starter, server and
quickstart modules no longer exclude it or add `spring-boot-jackson2`). The
Jackson 2 artifacts (`com.fasterxml.jackson.core:jackson-databind` and
`jackson-core`, `com.fasterxml.jackson.dataformat:*`,
`com.fasterxml.jackson.datatype:*`) are banned in data-prism's own build.
Data-prism keeps private, fixed mappers; adapter authors may keep their own
`ObjectMapper` for their own APIs, and it can no longer affect data-prism's.

Jackson 3 defaults that would have changed output are pinned back to the Jackson 2
values, each with a test. The golden outputs recorded on 0.5.x for the audit JSON
projection (non-ASCII, U+2028 and U+2029, control characters, escape casing) and
for the checkpoint lines are unchanged. The tool-result goldens pin the converted
map, not the MCP SDK's wire bytes.

### Building a tool or a server: options records replace overloads

`ToolOptions` (in `mcp`) and `SourceFanOutOptions` (in `orchestration`) carry what
the removed overloads took one at a time. Every removed constructor and factory is
in the table below. Admission has no default: name `noAdmission()` or a real
policy, so approvals are never switched off by omission.

```java
ToolOptions options = ToolOptions.defaults()
        .noAdmission()                      // or .admission(policy, fingerprinter)
        .correlationRequirement(CorrelationRequirement.OPTIONAL)
        .mdc(CorrelationMdc.off())
        .entityTypes(AuditedEntityTypes.shape())
        .build();
SourceFanOutOptions fanOut = SourceFanOutOptions.defaults()
        .withMetrics(metrics).withMdc(mdc);
```

| Removed | Replacement |
|---|---|
| `GetEntityContextTool`: all 8 public constructors of 0.5.x removed (including those taking an `ObjectMapper`); replaced by | `GetEntityContextTool(ContextOrchestrator, AuthorizationService, ScopeResolver, PrivacyMetrics, AuditRecorder, Clock, AuthenticatedCaller developmentCaller, ToolOptions)` |
| `CompareEntitySourcesTool`: all 8 public constructors of 0.5.x removed; replaced by | `CompareEntitySourcesTool(ContextOrchestrator, AuthorizationService, ScopeResolver, PrivacyMetrics, AuditRecorder, Clock, AuthenticatedCaller developmentCaller, ToolOptions)` |
| `DataPrismMcpServer.stdio(...)`: 6 overloads in 0.5.x | `stdio(ContextOrchestrator, AuthorizationService, ScopeResolver, AuthenticatedCaller developmentCaller, boolean singlePrincipalDevelopmentMode, boolean productionDeployment, PrivacyMetrics, AuditRecorder, Clock, ToolOptions)` |
| `DataPrismMcpServer.streamableHttp(...)`: 6 overloads in 0.5.x | `streamableHttp(ContextOrchestrator, AuthorizationService, ScopeResolver, McpTransportContextExtractor<HttpServletRequest>, String endpointPath, PrivacyMetrics, AuditRecorder, Clock, ToolOptions)` |
| `SourceFanOut(SourceCircuitBreaker, Clock)`, `(…, PrivacyMetrics)`, `(…, PrivacyMetrics, CorrelationMdc)` | `SourceFanOut(SourceCircuitBreaker, Clock, SourceFanOutOptions)`; `SourceFanOutOptions.defaults()` is no metrics and no MDC |
| `ContextRequest` constructors with 3, 5, 7 and 8 arguments | `ContextRequest.of(entityType, subjectId)`, `ContextRequest.comparison(entityType, subjectId, rejectedArguments, toolName)`, or the canonical nine-component constructor |

Behaviour to know about: the removed shorter `ContextRequest` constructors
derived the audited entity type from the shape fallback. `of` and `comparison`
audit `<unregistered>`; to audit a registered type, pass `auditedEntityType` to
the canonical constructor (the tools do this through `AuditedEntityTypes`).

`AuditRecorder` gained a constructor taking the listener dispatcher. The two
existing constructors are unchanged.

## Spring integrators

### The properties types are top-level

The 13 nested types of `DataPrismProperties` are now top-level classes in
`io.github.aindriub.dataprism.spring.boot`. `DataPrismProperties` keeps the same
property paths, types and defaults; only the Java types of its accessors changed.

| 0.5.x | 0.6.0 |
|---|---|
| `spring.boot.DataPrismProperties.Transport` | `spring.boot.TransportProperties` (`Mode` and `Http` stay nested) |
| `spring.boot.DataPrismProperties.Security` | `spring.boot.SecurityProperties` (`Jwt` and `CallerClaims` nested) |
| `spring.boot.DataPrismProperties.SecurityPolicy` | `spring.boot.SecurityPolicyProperties` |
| `spring.boot.DataPrismProperties.Privacy` | `spring.boot.PrivacyProperties` (`HmacKey` nested) |
| `spring.boot.DataPrismProperties.Audit` | `spring.boot.AuditProperties` (`Output`, `Output.Routing`, `Checkpoint` nested) |
| `spring.boot.DataPrismProperties.Correlation` | `spring.boot.CorrelationProperties` (`Inbound`, `Outbound` nested) |
| `spring.boot.DataPrismProperties.Metrics` | `spring.boot.MetricsProperties` |
| `spring.boot.DataPrismProperties.Hazelcast` | `spring.boot.HazelcastProperties` (`Join`, `Kubernetes`, `Member` nested) |
| `spring.boot.DataPrismProperties.Identity` | `spring.boot.IdentityProperties` |
| `spring.boot.DataPrismProperties.Source` | `spring.boot.SourceProperties` (`Mtls` nested) |
| `spring.boot.DataPrismProperties.Oversight` | `spring.boot.OversightProperties` (`CallerRateLimit` nested) |
| `spring.boot.DataPrismProperties.Reidentification` | `spring.boot.ReidentificationProperties` (`Permission` nested) |
| `spring.boot.DataPrismProperties.Operator` | `spring.boot.OperatorProperties` |
| `spring.boot.DataPrismContractValidator` (package-private) | `spring.boot.validation.DataPrismContractValidator` (public) |
| the `validate()` internals of `DataPrismProperties`, `sameOrInside` | `spring.boot.validation.DataPrismPropertiesValidator` (`DataPrismProperties.validate()` is still public and delegates) |

`DataPrismProperties.getTransport()`, `getSecurity()` and the other accessors
now return the top-level types, and `getSources()` returns
`Map<String, SourceProperties>`. `spring.boot.validation` is internal to
`spring.boot`, and `validation` and `jwt` do not depend on each other.

### `DataPrismAutoConfiguration` is a list of imports

The class name, the `@AutoConfiguration` and the `@EnableConfigurationProperties`
are unchanged. It no longer declares any bean: it `@Import`s 14 package-private
`@Configuration(proxyBeanMethods = false)` classes (`IdentityResolverSelection`,
`AuditSinkSelection`, `AuditIntegrityHealth`, `ClusterBackedState`,
`ReidentificationWiring`, `Preflights`, `PropertiesValidation`,
`PrivacyEngineWiring`, `SecurityWiring`, `AuditWiring`, `ScopeBudgetWiring`,
`OversightWiring`, `OrchestrationWiring`, `McpTransportWiring`), in an order that
reproduces the old registration order. Bean names, types, conditions and the
registration order of the `@Bean` methods are unchanged, and an inventory test pins
them. What changed for you:

- Five configuration classes that were nested in `DataPrismAutoConfiguration`
  (`$IdentityResolverSelection`, `$AuditSinkSelection`, `$AuditIntegrityHealth`,
  `$ClusterBackedState` and `$ReidentificationWiring`) are now top-level classes of
  the same simple names in `spring.boot`, and the former nested `$JsonProjection`
  helper is now the top-level `JsonProjection`. Spring registers an imported
  configuration class under its class name, so the bean names of those
  configuration classes changed with them. This only matters if you referred to
  one of those beans by name.
- `dataPrismHashChainedAuditSink` is declared by `AuditSinkSelection`;
  `dataPrismAuditRecorder` by `AuditWiring`. A static reference such as
  `DataPrismAutoConfiguration#dataPrismHashChainedAuditSink` in a comment or a
  `@see` no longer resolves.
- The JSON audit projection is its own bean, `dataPrismJsonAuditProjection`,
  classified `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`. It is deliberately not
  an `AuditSink`, so an `AuditRecorder` injection stays unambiguous.
- The listener dispatcher, `dataPrismAuditEventListeners`, is also
  `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`. A second dispatcher bean is
  refused with `AUDIT_EVENT_LISTENERS_NOT_REPLACEABLE`; register
  `AuditEventListener` beans instead.

### JWT classes moved

`JwtDecoderSupport` and `JwtCallerContextExtractor` are in
`io.github.aindriub.dataprism.spring.boot.jwt` (see the table above). They were not
deprecated first.

### Dependencies

The starter no longer excludes `spring-boot-starter-jackson` or adds
`spring-boot-jackson2`; Jackson 3 comes with Spring Boot 4.1 as for any Boot 4
application. Spring Boot 3 users stay on 0.4.x (unchanged from 0.5.0).

### `AuditSink` and `TeeAuditSink`

If you build a `TeeAuditSink` yourself, it is now in `audit.sink` and is
`Closeable`: closing it closes the projection and then the primary sink, even if
the projection close throws. It poisons on any `Throwable` from the projection.

## Audit consumers

- **Format and logger.** The audit record format (`recordVersion` 3) and the
  `dataprism.audit` logger are unchanged, and the recorded goldens for the JSON
  projection and the checkpoint lines are unchanged.
- **Moved classes.** `AuditRecorder`, `AuditEvent`, `AuditSink`, `AuditEventHash`,
  `AuditCheckpoint`, `AuditCheckpointSink`, `AuditCheckpointUnavailableException`,
  `AuditEntry` and `AuditedEntityTypes` stay in `audit`. The writers moved to
  `audit.sink`, the field mapping and JSON rendering to `audit.format`, the
  checkpoint writer to `audit.checkpoint`, retention to `audit.retention` and the
  verifier to `audit.verify` (see the table above).
- **Verifier.** New main class, as under [Operators](#the-audit-verifier-has-a-new-class-name).
  With `--checkpoints` it reports a checkpoint line that ends in a raw `\r`, or a
  final unterminated line that is not a valid checkpoint, as the anomaly
  `TORN_CHECKPOINT_LINE` (exit code 4), never as a break, and still uses the intact
  checkpoints. Any other unparseable checkpoint line is still unreadable input
  (exit code 1). A checkpoint file converted to CRLF line endings reports every
  line as torn, so restore LF endings. See
  [External checkpoints](audit.md#external-checkpoints).
- **Checkpoint writer.** A resumed writer terminates a torn checkpoint tail with
  `\r\n` and fsyncs before its first append, and fails the open if it cannot. The
  torn line is never edited. The writer that was torn is verified against its
  previous checkpoint, so tail-truncation coverage for it is reduced to that point.
- **Listeners.** `AuditEventListener` is a new read-only SPI, called after the
  configured sink accepted each event, on one bounded asynchronous dispatcher
  (`dataprism.audit.listeners.queue-capacity`, default 1024). It is best effort:
  events are dropped for listeners only when the queue is full, and the audit log is
  unaffected. A failing listener is logged by class name only, as
  `AUDIT_LISTENER_FAILED`; drops are logged, at most one line per 10 seconds, as
  `AUDIT_LISTENER_DROPPED`. A listener is not part of the evidence chain. See
  [Extending](extending.md).

## New refusal codes

| Code | Where | What triggers it |
|---|---|---|
| `DUPLICATE_CONFIG_KEY`, `UNKNOWN_CONFIG_KEY`, `NON_STRING_CONFIG_SCALAR`, `INVALID_CONFIG_BOOLEAN`, `LEADING_ZERO_CONFIG_NUMBER`, `TRAILING_CONFIG_CONTENT`, `UNSUPPORTED_CONFIG_YAML`, `INVALID_CONFIG_SHAPE`, `NULL_LIKE_CONFIG_SCALAR` | the six YAML readers, at startup; reported through `INVALID_MODEL_DESCRIPTOR_FILE` for the descriptor file | see [YAML files are read more strictly](#yaml-files-are-read-more-strictly) |
| `SOURCE_MODEL_NOT_A_RECORD` | startup (declared response types) and request time (the object graph) | a source model that is not a record, or holds a bean or another refused shape |
| `AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK` | startup | `dataprism.audit.output.json-directory` set with `sink=hash-chained` while an application `AuditSink` replaces the built-in sink |
| `AUDIT_JSON_PROJECTION_MISSING` | startup | `json-directory` set but the framework's JSON projection is not registered |
| `INVALID_AUDIT_LISTENER_QUEUE_CAPACITY` | startup | `dataprism.audit.listeners.queue-capacity` below 1 |
| `AUDIT_EVENT_LISTENERS_NOT_REPLACEABLE` | startup | more than one `AuditEventListeners` dispatcher bean |
| `AUDIT_LISTENER_FAILED`, `AUDIT_LISTENER_DROPPED` | log lines, not refusals | a listener threw (class name only), or events were dropped because the listener queue was full |
| `TORN_CHECKPOINT_LINE` | verifier anomaly, not a startup refusal | a damaged checkpoint line, as described above |

## What this page does not claim

These changes make the configuration stricter and the extension surface smaller.
They do not make an unreviewed adapter safe, and the verifier still cannot detect
an operator who rewrites the whole audit file, unless external checkpoints are kept
where that operator cannot write. See the [audit chain](audit.md) for
what the verifier does and does not show.
