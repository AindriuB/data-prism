# Log shipping: audit events into a dedicated index

Copyable material for the log end. Data Prism writes audit events durably on the
local disk; a shipper copies them to a log platform for search. Local durable
write plus shipping is the supported path. Data Prism has no direct
Elasticsearch output.

| File | What it shows |
|---|---|
| `data-prism-audit.properties.example` | The Data Prism properties: native segments, the JSON projection, ECS names and routing |
| `filebeat.yml` | Filebeat `filestream` input on `<json-directory>/audit-*.ndjson`, the `ndjson` parser, routing on `event.dataset` |
| `elastic-agent-policy.yml` | Elastic Agent custom-logs (filestream) policy snippet |
| `logstash.conf` | Logstash pipeline routing on `event.dataset` |
| `application-ecs.yaml` | Spring Boot structured logging for the slf4j sink |
| `mdc/` | MDC-based correlation for the slf4j sink (a separate task owns this directory) |

## What is the record, and what is a copy

- The native hash-chained segments (`audit-YYYY-MM-DD.log`) are the verifiable
  record. Verify them with the offline verifier. Do not ship or tail them.
- The `.ndjson` projection is a copy for search. It is not chained and nothing
  verifies it. Point shippers at the projection directory only.
- Retention applies to the shipped index too. Data Prism deletes expired native
  and projection segments on its schedule; deleting or keeping the copy in the
  log platform (index lifecycle, data stream retention) is the operator's
  responsibility.
- With the slf4j sink, durability is the appender's: whatever the logging
  configuration, rotation and shipper guarantee. There is no chain.

## Checking the files

Every YAML file parses:

```
python -c 'import yaml,sys;yaml.safe_load(open(sys.argv[1]))' filebeat.yml
python -c 'import yaml,sys;yaml.safe_load(open(sys.argv[1]))' elastic-agent-policy.yml
python -c 'import yaml,sys;yaml.safe_load(open(sys.argv[1]))' application-ecs.yaml
```

That checks syntax, not that the shipper accepts the configuration. Hosts are
`elasticsearch.example.invalid`; the API key is read from `ES_API_KEY` at run
time.
