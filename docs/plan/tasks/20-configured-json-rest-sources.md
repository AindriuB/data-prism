# 20 — Add a separately reviewed configuration-driven JSON REST mode

**Repo:** `.`
**Depends on:** 14, 15, 17
**Owns:**
- data-prism-connectors-rest/**
- data-prism-server/**
- docs/configuration.md

## Goal

Let the standalone server protect selected non-Java REST APIs without turning
the MCP tool into a generic proxy or silently inferring which data is safe.

## Acceptance

- [ ] Source configuration expresses only server-owned base URLs, fixed path
      templates, bounded timeouts, a subject JSON path, explicit response model
      version, and an allowlisted field classification catalogue.
- [ ] Every emitted field is explicitly classified; unknown fields and schema
      mismatches fail closed before a response can reach MCP serialisation.
- [ ] JSON paths cannot introduce an outbound host, query, alternate endpoint,
      dynamic tool name, or arbitrary source field selected by the MCP caller.
- [ ] A schema/contract test validates source configuration at startup and
      records a stable failure for invalid or incomplete catalogues.
- [ ] End-to-end tests demonstrate one configured source produces the same
      pseudonymised, validated canonical response as a Java-first adapter, and
      mutation tests prove unclassified fields cannot pass.
- [ ] `mvn -B verify` is green.

## Out of scope

- Elasticsearch, raw query DSL, probabilistic matching, and re-identification.
