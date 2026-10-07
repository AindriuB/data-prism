# Correlation header: client snippets

Copyable material for the client end. Your MCP client copies its own
correlation id into a request header on every tool call, and Data Prism records
it on the audit event as `externalCorrelationId`.

Server side (see `docs/configuration.md`, `dataprism.correlation`):

```yaml
dataprism:
  correlation:
    inbound:
      header: X-Correlation-ID   # or traceparent, with format: traceparent
      format: opaque             # or traceparent
      required: false
```

MCP Streamable HTTP sends one HTTP request per JSON-RPC message. A header fixed
when the connection is made would give every call the same id, so the Python and
TypeScript snippets set the header in a per-request hook that reads the id from
the application's own context.

| File | What it shows |
|---|---|
| `client_opaque.py` | Python SDK, `X-Correlation-ID` from a contextvar, in an httpx2 request hook |
| `client_traceparent.py` | The same with a W3C `traceparent` value |
| `client-opaque.ts` | TypeScript SDK, `X-Correlation-ID` from `AsyncLocalStorage`, in a custom `fetch` |
| `client-traceparent.ts` | The same with `traceparent` |
| `curl-example.sh` | One `tools/call` with `curl`; `CORRELATION_VARIANT=traceparent` switches header |
| `claude-code-static-header.sh` | `claude mcp add --transport http --header`; the value is static per registration |

All hosts are `data-prism.example.invalid`. Set `DATAPRISM_MCP_URL` and
`DATAPRISM_MCP_TOKEN` at run time; no credential is stored here.

## Checking the snippets

The snippets were written against `mcp` 2.3.0 for Python (`requirements.txt`)
and `@modelcontextprotocol/sdk` 1.32.1 for TypeScript (`package.json`). Both
SDKs change; re-check against the release you use.

```
python -m py_compile client_opaque.py client_traceparent.py
npm install
npx tsc --noEmit
```

`py_compile` checks syntax only. `tsc` checks types against the pinned SDK.

## Notes

- With `format: opaque`, the default pattern accepts a UUID, 16 to 128 hex
  characters containing at least one letter a to f, or a `traceparent`. A value
  that does not match is dropped (or refused, with `required: true`).
- A static header (the Claude Code script) gives every call from one
  registration the same id. It identifies a session or workstation, not a call.
