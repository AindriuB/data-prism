# 19 — Publish tested MCP agent connection guides

**Repo:** `.`
**Depends on:** 17, 18
**Owns:**
- docs/agents/**
- examples/agent-config/**
- README.md

## Goal

Make connection to Data Prism understandable without embedding credentials in
agent configuration or weakening its transport rules. Support one local stdio
fixture workflow and one authenticated remote HTTP workflow first.

## Acceptance

- [ ] The guide names the supported agent clients and versions at the time it is
      written, with a testable configuration snippet for each rather than an
      unverified generic example.
- [ ] Local stdio instructions target only the fixture example and say that its
      single principal must not access a protected API.
- [ ] Remote instructions configure the Streamable HTTP `/mcp` endpoint and a
      documented credential-acquisition mechanism; no bearer token, HMAC key,
      client certificate or production URL appears in a checked-in snippet.
- [ ] Each guide shows expected tool discovery and one `get_entity_context`
      call, including the warning that returned content is untrusted data and
      that scope, purpose, principal and case id are not tool arguments.
- [ ] An automated smoke test validates each checked-in remote configuration
      template against the Compose quickstart or the template is explicitly
      excluded with a reproducible manual verification procedure and reason.

## Out of scope

- Supporting every MCP client, or adding tools to fit a particular client.
