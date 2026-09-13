# 14 — Decide the supported deployment contract and configuration schema

**Repo:** `.`
**Depends on:** 11, 12, 13
**Owns:**
- docs/architecture.md
- docs/design-review.md
- docs/configuration.md
- docs/adr/**

## Goal

Resolve the product contract before wiring a starter or container: Data Prism is
a standalone Streamable HTTP MCP server first, with a Spring Boot starter as an
embedded integration option. Define one configuration vocabulary both modes can
consume, including the precise boundary between declarative configuration and
application-supplied Java components.

## Acceptance

- [ ] `docs/configuration.md` names every supported `dataprism.*` group, its
      default or required state, whether it is secret-bearing, and the failure
      behaviour for an invalid value.
- [ ] The contract covers transport, JWT validation and caller claim mapping,
      security policy, privacy profile, locale, scope lifetime, HMAC key
      reference, audit, metrics, Hazelcast and REST source configuration.
- [ ] It explicitly distinguishes the Java-first adapter mode from a future
      configuration-driven JSON mode, and states that the latter is not yet
      supported.
- [ ] It records that stdio is limited to single-principal fixture development,
      while protected APIs use authenticated Streamable HTTP.
- [ ] An ADR records why the server and starter share one configuration core,
      and why MCP/orchestration retain no dependency on connector modules.
- [ ] Existing architecture and design-review documents contain no contradictory
      statement about the planned server, starter, or configuration ownership.

## Out of scope

- New Java modules or runtime configuration binding.
- Docker, an agent-specific connection file, or a generic JSON adapter.
