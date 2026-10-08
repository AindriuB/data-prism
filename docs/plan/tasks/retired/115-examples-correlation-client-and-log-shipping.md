# 115 — Ship client correlation-header snippets and log-shipping recipes as examples

**Repo:** `.`
**Depends on:** 113
*(Owns amended 2026-10-07 by D-148-A: `examples/log-shipping/mdc/**` is carved out for task 148, which runs in the same wave. Do not create files under `mdc/`. The `examples/log-shipping/README.md` this task writes may link `mdc/` by name.)*
**Owns:**
- examples/correlation-header/** *(new)*
- examples/log-shipping/** *(new; except `mdc/**`, which task 148 owns)*

## Goal

Give an organisation copyable material for both ends. On the client side,
the material shows how its MCP client copies its own correlation id into the
configured header on every tool call. On the log side, it shows how
Filebeat or Elastic Agent tails the JSON projection segments, and how the
slf4j sink feeds a JSON encoder, so that audit events land in a dedicated
index. Local durable write plus shipping is the supported path.

## Context

- Task 113's property names: `dataprism.correlation.inbound.*` and
  `dataprism.audit.output.*`. Task 112's file layout:
  `<json-directory>/audit-YYYY-MM-DD.ndjson`.
- MCP Streamable HTTP sends one HTTP request per JSON-RPC message, so a
  per-call id needs a per-request hook rather than a header fixed at
  connection time. Python SDK: `streamablehttp_client(url, headers=...,
  httpx_client_factory=...)` with an httpx event hook. TypeScript SDK:
  `StreamableHTTPClientTransport(url, { fetch })` or `requestInit`. Claude
  Code: `claude mcp add --transport http --header`. Check each against the
  current SDK release before writing it.
- `examples/agent-config/remote-http/` is the existing style for client
  config examples.
- Spring Boot 4.1 `logging.structured.format.file=ecs` (the reactor is on Boot
  4.1.1 after task 141, which this task follows through 113) and task 112's
  note on key-value support. *(Amended 2026-10-07 by the 0.5.0 plan; this
  previously said Boot 3.5. Depends and Owns are unchanged.)*

## Acceptance

- [ ] `examples/correlation-header/README.md` and runnable snippets for:
      the Python MCP SDK with a per-request hook that reads the id from the
      application's own context; the TypeScript MCP SDK with a custom
      `fetch`; `curl` against the MCP endpoint; and a static header in
      `claude mcp add`. Each snippet uses the header `X-Correlation-ID`, a
      `traceparent` variant, and a host under `example.invalid`.
- [ ] Each Python snippet passes `python -m py_compile`. Each TypeScript
      snippet passes `npx tsc --noEmit` against a `package.json` in the
      example directory that pins the SDK version. The README states the
      commands.
- [ ] `examples/log-shipping/` contains: a `filebeat.yml` with a `filestream`
      input on `<json-directory>/audit-*.ndjson`, the `ndjson` parser,
      `index`/data-stream routing on `event.dataset`, and no `.ndjson`
      input over the native audit directory; an Elastic Agent custom-logs
      policy snippet; a Logstash pipeline routing on `event.dataset`; and an
      `application-ecs.yaml` for the slf4j sink with Boot structured
      logging.
- [ ] Every YAML file parses (`python -c 'import yaml,sys;yaml.safe_load(open(sys.argv[1]))'`).
      The README lists the command.
- [ ] The README states that the native segments are the verifiable record
      and the projection is a copy for search. It says retention applies to
      the shipped index too and is the operator's responsibility. It says
      the slf4j sink's durability is the appender's.
- [ ] `grep -riE 'compliant|tamper-proof' examples/correlation-header examples/log-shipping`
      returns nothing. No credential, real host, real name or real
      identifier appears.

## Out of scope

- `docs/` pages and `mkdocs.yml`. That is task 116.
- Editing `examples/agent-config/**`.
- A direct Elasticsearch output from Data Prism.

## Attempt 1 — failed

Reviewer: CHANGES. Names, ids, scope and wording were all checked against the merged code and are clean.
- `examples/log-shipping/logstash.conf:6`: the `file` input with `codec => "json_lines"` emits nothing, because the file input already splits and strips newlines. Use `codec => "json"`.
- `examples/correlation-header/client_opaque.py:32-38` and `client_traceparent.py:36-42`: the httpx event hook likely reads the contextvar copied when the transport was entered, because requests are sent from a background task. Changing the id between calls on one session would then still send the first id, which contradicts the README's "per-request hook". Verify against mcp==2.3.0 by setting two ids in turn on one session and capturing the headers. If confirmed, set the header from the calling task, for example per-call headers or one session per unit of work, or document the per-connection limit plainly. The TS AsyncLocalStorage variant is fine.
- Label as untested or illustrative: `application-ecs.yaml:5-8` (Boot's ECS formatter rendering key-value pairs, and dotted-key nesting) and `filebeat.yml:31-38` (the drop_event on log.logger).
- README: state that the id is per unit of work. `initialize` and `tools/call` share it.
- `curl-example.sh`: send the `MCP-Protocol-Version` header on requests after initialize.
