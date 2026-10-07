# 148 — Put the validated external correlation id into the SLF4J MDC under an operator-configured key

**Repo:** `.`
**Release:** 0.5.0
**Depends on:** 110, 111, 113, 114
*(110: the tool handlers and `ContextRequest` carry the id and are owned there. 111: `ConfiguredJsonSourcesAutoConfiguration` builds a second `SourceFanOut` and is owned there. 113: `DataPrismProperties`, `DataPrismAutoConfiguration` and the `dataprism.correlation` section of `docs/configuration.md` are owned there. 114: `PiiLogScanTest` and `DataPrismAssembly` are owned there.)*
**Owner decision:** D-148-A (2026-10-07)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/correlation/CorrelationMdc.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/correlation/CorrelationMdcTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/SourceFanOutMdcTest.java *(new)*
- data-prism-orchestration/pom.xml *(one test-scoped `logback-classic` dependency only, if not already present)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/** *(after 110)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/CorrelationMdcToolTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java *(the `mdc-key` field of the `correlation` section and its validation only, after 113)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(the `CorrelationMdc` bean and passing it to `SourceFanOut` and to `DataPrismMcpServer` only, after 113)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/CorrelationMdcConfigurationTest.java *(new)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java *(passing an optional `CorrelationMdc` bean to its `SourceFanOut` only, after 111)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java *(insertions only, after 114)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java *(additive overloads only, after 114)*
- docs/configuration.md *(a `mdc-key` row and paragraph inside the `dataprism.correlation` section 113 adds, and its codes, only)*
- examples/log-shipping/mdc/** *(new; carved out of task 115's `examples/log-shipping/**`)*

## Goal

Organisations commonly put a transaction id into the SLF4J MDC, propagate it
between services in a header, and filter on it in Kibana. Data Prism
supports that: when `dataprism.correlation.mdc-key` is set, Data Prism's own
log lines on a tool call's threads, including the parallel source-fetch
threads and library logs on those threads, carry the call's validated
external correlation id under that key. The setting is unset by default, and
unset means nothing changes.

## Context

- **No MDC key is used anywhere in the repository today.** `grep -rn 'MDC'`
  over main and test sources finds nothing. In the 0.4.1 server jar, only the
  logging bridges (`logback-classic`, `log4j-to-slf4j`, `jboss-logging`,
  `slf4j-api`) reference `org/slf4j/MDC`, and none of them sets a key.
  Collisions to guard against are the keys that tracing integrations an
  operator may add will set: Micrometer Tracing (`traceId`, `spanId`), the
  OpenTelemetry Logback MDC instrumentation (`trace_id`, `span_id`,
  `trace_flags`), and Elastic APM log correlation (`trace.id`,
  `transaction.id`, `span.id`). Guard also against the top-level names Spring
  Boot's ECS structured logging writes, because Boot copies MDC entries into
  the same JSON object (`@timestamp`, `message`, `ecs.*`, `log.*`,
  `process.*`, `service.*`, `error.*`, `event.*`).
- **The real execution point.** Both tools are `McpServer.sync` tools
  (`DataPrismMcpServer.java:115` and `:185`). The SDK runs a sync tool's
  `callHandler` on a Reactor scheduler, not the servlet thread, unless
  `immediateExecution` is set, and Data Prism does not set it. So a servlet
  filter's MDC would be on the wrong thread. The correct boundary is the
  first statement of `GetEntityContextTool.handle` and
  `CompareEntitySourcesTool.handle` (`:175` in each), where task 110 reads
  the `InboundCorrelation` from `exchange.transportContext()` under
  `DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY`. Task 110 owns these
  files until it merges; read `docs/plan/tasks/110-mcp-propagates-external-correlation.md`.
- **The fan-out.** `SourceFanOut.fetchAll` (`SourceFanOut.java`, the
  `newVirtualThreadPerTaskExecutor` block) submits one task per source. Each
  task already has its `DataRequest`, whose
  `context().externalCorrelationId()` task 110 fills. Set MDC from that, at
  the start of the submitted task. Do not read the submitting thread's MDC
  and do not rely on `InheritableThreadLocal`. Virtual threads do not inherit
  MDC, and Logback's adapter is not inheritable anyway. No other task owns
  `SourceFanOut.java`; task 110 says it needs no edit.
- `SourceFanOut` is built in three places: `DataPrismAutoConfiguration`
  (`~:804`), `ConfiguredJsonSourcesAutoConfiguration` (`:141`), and the
  convenience constructor of `DefaultContextOrchestrator` (`:101`). Existing
  `SourceFanOut` constructors must keep compiling and mean MDC off. Leave the
  `DefaultContextOrchestrator` constructor unchanged.
- `DataPrismMcpServer.streamableHttp` is wired at
  `DataPrismAutoConfiguration` `~:896`. Add overloads in the way task 110
  added the `CorrelationRequirement` ones. Every existing overload delegates
  with MDC off.
- **`data-prism-integration-tests` uses `slf4j-simple`**
  (`data-prism-integration-tests/pom.xml:45-56`), and its provider installs
  `NOPMDCAdapter`, which was checked with `javap` against 2.0.18. `MDC.put`
  there is discarded, so the PII scan cannot observe MDC through SLF4J.
  `CorrelationMdc` therefore takes an `org.slf4j.spi.MDCAdapter`, which
  defaults to `MDC.getMDCAdapter()`, so the scan can install a recording
  adapter. Do not change that module's logging provider.
- `data-prism-mcp` already has `logback-classic` at test scope. A Logback
  `ListAppender` exposes each event's `getMDCPropertyMap()`.
- `DataPrismProperties` uses `ignoreUnknownFields = false`, so the property
  must be declared there. Use `refuse(CODE, message)`, and follow the split
  between the server log and the client-visible message
  (`docs/conventions.md` lines 62-82).
- Task 108's types: `ExternalCorrelationId.value()`, `InboundCorrelation`
  (`isPresent()`, `isRejected()`, `id()`), and `SourceCallContext`.
- Task 113's default inbound pattern (owner decision C4) admits only a UUID,
  hex, or a W3C traceparent. Whatever the configured pattern admits is what
  reaches every log line.

## Acceptance

- [ ] `dataprism.correlation.mdc-key` is bound and unset by default. When it
      is unset, no MDC key is ever set: a test runs a full call with an id
      present and asserts that every captured event's MDC map is empty.
- [ ] Startup refuses with a stable code, and one `ApplicationContextRunner`
      test per row:

      | Condition | Code |
      |---|---|
      | key does not match `[A-Za-z][A-Za-z0-9_.-]{0,63}` (one case each: blank, leading digit, space, `@timestamp`, 65 characters) | `INVALID_CORRELATION_MDC_KEY` |
      | key equals, case-insensitively, one of `traceId`, `spanId`, `trace_id`, `span_id`, `trace_flags`, `trace.id`, `span.id`, `transaction.id`, `message`, or starts with `ecs.`, `log.`, `process.`, `service.`, `error.` or `event.` | `CORRELATION_MDC_KEY_RESERVED` |
      | `mdc-key` set while `dataprism.correlation.inbound.header` is unset | `CORRELATION_MDC_KEY_WITHOUT_HEADER` |

      The reserved names are one public constant on `CorrelationMdc`, and a
      test iterates the constant. `transaction_id` and `x_correlation_id` are
      accepted, and a test asserts it.
- [ ] `CorrelationMdc` exposes a scope that takes an
      `Optional<ExternalCorrelationId>` or an `InboundCorrelation`, puts
      only `value()` under the key, and in `close()` restores the previous
      value of that key: put back if there was one, removed if there was
      none. An absent id, a `rejected()` correlation and an `off()` instance
      put nothing and remove nothing. Unit tests cover each case, including
      a pre-existing value that is restored.
- [ ] In both tools, the scope opens at the first statement of `handle`,
      once the `InboundCorrelation` is read, and closes in a `finally` that
      covers every return and throw path, DENY paths included. A test uses
      an `AuditSink` that logs one line per event. It asserts that the line
      logged on a DENY path (an admission refusal) carries the key.
- [ ] In `SourceFanOut`, each submitted task opens the scope from its own
      `DataRequest.context().externalCorrelationId()` before
      `bulkhead.acquire()` and closes it in `finally`. A test greps
      `SourceFanOut.java` and `CorrelationMdc.java` and asserts there is no
      `InheritableThreadLocal` and no `MDC.getCopyOfContextMap`.
- [ ] `SourceFanOutMdcTest`: a recording adapter logs one line inside
      `fetch`, and a Logback `ListAppender` asserts that the line's MDC map
      has `<key>=<id>`. A request with no id gives a line with no key.
- [ ] `CorrelationMdcToolTest`: two tool calls run concurrently, one with
      `synthetic-clid-0001` and one with `synthetic-clid-0002`, against a
      real `DefaultContextOrchestrator` and three logging recording adapters.
      Every captured event is attributed to its call by a per-call marker in
      the message, and carries only that call's id, including events from
      the fan-out threads. The test asserts at least one fan-out event per
      call, so it cannot pass vacuously.
- [ ] Reuse: a single-thread executor runs a call with an id, then a plain
      task that logs. That second event's MDC map has no key. The same check
      runs on the thread that ran the tool handler.
- [ ] Rejected id: with `required: false` and a header value the policy
      rejects, the call proceeds, and no captured event on any thread has
      the key. A test asserts that the rejected text appears in no MDC
      value.
- [ ] PII scan: `PiiLogScanTest` gains a full integration run with
      `mdc-key: transaction_id`, the id `synthetic-clid-0001`, and a
      recording `MDCAdapter` installed through a new `DataPrismAssembly`
      overload. Every value put under any key is scanned against the derived
      banned set. The test asserts at least one put of `synthetic-clid-0001`
      from a fan-out thread. A not-vacuous case uses the permissive pattern
      `[A-Za-z0-9._:-]{1,128}` and a fixture surname as the id, and asserts
      that the scan catches it. The commit body records the mutation used to
      show the clean case would fail. Existing cases pass unchanged.
- [ ] `docs/configuration.md` documents `mdc-key`, its default (off), all
      three codes and the reserved list. It states that only the validated
      id is ever placed in MDC, and that a rejected or absent id places
      nothing. It states that whatever the inbound pattern admits appears in
      every log line on those threads, the `slf4j` audit sink's lines
      included, so the pattern should admit generated ids only. It says
      "supports" and never "compliant".
- [ ] `examples/log-shipping/mdc/README.md` is a short "Correlated logging
      with MDC" recipe. It shows the client sending `X-Transaction-Id`, the
      settings `dataprism.correlation.inbound.header: X-Transaction-Id` and
      `dataprism.correlation.mdc-key: transaction_id`, and a
      `logback-spring.xml` using an ECS JSON encoder (Spring Boot
      `StructuredLogEncoder` with `ecs` format, or
      `co.elastic.logging.logback.EcsEncoder`) that maps the key into the
      JSON line. It also shows a Kibana KQL filter `transaction_id : "…"`
      with a synthetic UUID. It repeats the pattern warning above. Every
      XML/YAML file parses, and the README lists the command.
      `grep -riE 'compliant|tamper-proof' examples/log-shipping/mdc` returns
      nothing.
- [ ] `mvn -pl data-prism-core,data-prism-orchestration,data-prism-mcp,data-prism-connectors-rest,data-prism-spring-boot-autoconfigure,data-prism-integration-tests -am verify`
      passes.

## Out of scope

- Micrometer Observation, OpenTelemetry context propagation, or a `traceId`
  bridge. C6 stays deferred.
- MDC on Hazelcast threads, the audit purge scheduler, or any other
  background thread.
- A servlet filter that sets MDC on the request thread. It is the wrong
  thread for the tool, and it would run before validation in the tool.
- The global outbound header `dataprism.correlation.outbound.header`.
  Amendments to tasks 111 and 113 cover it.
- Changing the integration-tests logging provider, `docs/log-shipping.md`
  (task 116 links this recipe) and the rest of `examples/log-shipping/**`
  (task 115).
- `CHANGELOG.md`. Its entry is a release-cut item.
