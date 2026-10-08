# Correlated logging with MDC

Many organisations put a transaction id into the SLF4J MDC, pass it between
services in a header, and filter on it in Kibana. Data Prism supports that: with
one setting, its own log lines on a tool call's threads carry the call's
validated id, including the parallel source-fetch threads.

## 1. The client sends the id

```
POST /mcp
X-Transaction-Id: 3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b
```

The value above is a synthetic UUID.

## 2. Data Prism reads it and puts it in the MDC

`application.yaml` in this directory:

```yaml
dataprism:
  correlation:
    inbound:
      header: X-Transaction-Id
    mdc-key: transaction_id
```

`mdc-key` needs `inbound.header`. The key must match
`[A-Za-z][A-Za-z0-9_.-]{0,63}` and must not be a name a tracing integration or
ECS logging already writes (`traceId`, `trace.id`, `message`, `ecs.*`, and the
others listed in [docs/configuration.md](../../../docs/configuration.md)).
Startup is refused with `INVALID_CORRELATION_MDC_KEY`,
`CORRELATION_MDC_KEY_RESERVED` or `CORRELATION_MDC_KEY_WITHOUT_HEADER`
otherwise. Unset, nothing is added.

## 3. The JSON line carries the key

`logback-spring.xml` uses Spring Boot's `StructuredLogEncoder` with the `ecs`
format. Spring Boot writes every MDC entry as a top-level field:

```json
{"@timestamp":"2026-10-07T09:00:00.000Z","log.level":"INFO","message":"...","transaction_id":"3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b"}
```

`logback-spring-elastic-encoder.xml` does the same with
`co.elastic.logging.logback.EcsEncoder` for an application that does not use
Spring Boot's structured logging.

## 4. Filter in Kibana

```
transaction_id : "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b"
```

## Choose a pattern that admits generated ids only

Whatever `dataprism.correlation.inbound.pattern` admits appears in every log
line on those threads, including the lines of the `slf4j` audit sink. A pattern
broad enough to admit a name-like token lets personal data be supplied as an
id and then written to every log line. Keep the strict default, or admit only
ids your own systems generate.

## Check the files

```
python3 -c "import sys, yaml, xml.dom.minidom as m; yaml.safe_load(open('application.yaml')); [m.parse(f) for f in ('logback-spring.xml','logback-spring-elastic-encoder.xml')]"
```
