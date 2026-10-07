---
title: Shipping audit events to a log stack
description: How audit events reach a log platform from local files, why there is no direct Elasticsearch sink, and where the copyable examples are.
---

# Shipping audit events to a log stack

Data Prism writes each audit event durably to the local disk. To search events
alongside the rest of your logs, a shipper copies them to a log platform. This
page describes the two supported paths and where the copyable examples are. It
supports an operator who wants audit events in a dedicated index; it does not
change what is audited.

## There is no direct Elasticsearch sink

Data Prism has no output that writes to Elasticsearch, or any other remote
store, from the audit call. This is deliberate. The audit write sits on the
path of every tool call and is fail-closed: if the event cannot be recorded, the
call is refused. A network sink would make the privacy decision depend on a
remote cluster's availability, or would have to drop events when it was down.
Writing locally first keeps the write short and durable, and a shipper that
falls behind or restarts loses nothing, because the file is still there.

## What is the record, and what is a copy

- The native hash-chained segments (`audit-YYYY-MM-DD.log`) are the verifiable
  record. Verify them with the [offline verifier](audit.md#the-offline-verifier).
  Do not ship or tail them.
- The `.ndjson` projection is a copy for search. It is not chained and nothing
  verifies it. Point shippers at the projection directory only. See
  [Structured JSON output](audit.md#structured-json-output) for the field names
  and routing.
- Retention applies to the shipped index too. Data Prism deletes expired native
  and projection segments on its schedule; keeping or deleting the copy in the
  log platform is the operator's responsibility.

## Path 1: the JSON projection, shipped by Filebeat or Elastic Agent

Set `sink: hash-chained` with a `directory`, then
`dataprism.audit.output.json-directory`, the `ecs` field preset and the routing
constants. Data Prism writes one JSON line per event to
`audit-YYYY-MM-DD.ndjson` segments there. Filebeat (a `filestream` input with
the `ndjson` parser) or an Elastic Agent custom-logs policy reads them and
sends them to a data stream chosen by `event.dataset`.

Copyable files are in
[`examples/log-shipping/`](https://github.com/AindriuB/data-prism/tree/main/examples/log-shipping):
the Data Prism properties, `filebeat.yml`, `elastic-agent-policy.yml` and
`logstash.conf`. The fieldDispositions keys contain dots, so map
`dataprism.field_dispositions` as `flattened` or disabled; see
[Structured JSON output](audit.md#structured-json-output).

Two failure behaviours to plan for. A failed projection write (a full or
unwritable `json-directory`) makes every later tool call refuse with
`AUDIT_PROJECTION_FAILED` until the process restarts; this is fail-closed by
design, so a disk problem on the projection directory stops service. A failed
purge of expired `.ndjson` segments is only logged, unlike the native purge.

## Path 2: the slf4j sink

With `sink: slf4j`, each event is a log line on the `dataprism.audit` logger and
goes wherever the application's logging goes. A configured preset, field names
or routing attaches the mapped values as key-value pairs, which the logging
layer may write as JSON fields; the example uses Spring Boot's structured
logging (`logging.structured.format.file: ecs`). How that layer renders dotted
key-value names has not been verified here, so check it in your own logging
configuration before relying on a mapping. The example is `application-ecs.yaml` in the same directory. Durability
is the appender's, and there is no chain.

## Correlated logging with MDC

To filter every log line of a call by your own transaction id, set
`dataprism.correlation.mdc-key`. The recipe, with Spring Boot and Elastic
encoder configurations, is in
[`examples/log-shipping/mdc/`](https://github.com/AindriuB/data-prism/tree/main/examples/log-shipping/mdc).
Only the validated id is placed in the MDC. The stdio transport has no MDC
overload, so a library user who builds a stdio server always runs with it off.

## Sending the id from a client

Client snippets that set the correlation header on each request are in
[`examples/correlation-header/`](https://github.com/AindriuB/data-prism/tree/main/examples/correlation-header).
The Python snippets need Python 3.10 or later.

## Checking the examples

The YAML files parse with `yaml.safe_load`. That checks syntax, not that the
shipper accepts the configuration. Hosts use `elasticsearch.example.invalid`
and the API key is read from the environment at run time.
