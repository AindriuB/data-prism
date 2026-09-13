# ADR 0001: share one configuration core between server and starter

**Status:** Accepted  
**Date:** 2026-09-13

## Context

Data Prism has two supported delivery surfaces: a standalone Streamable HTTP MCP
server for teams protecting existing APIs, and a Spring Boot starter for teams
embedding the privacy boundary. Both must authenticate a caller, derive a
privacy scope, apply the same policy/profile/key rules, call reviewed adapters,
validate the scrubbed result, audit it, and publish the same closed MCP tool
surface.

Duplicating property binding and wiring in the server and starter would allow
the two paths to drift: one could accept unsafe stdio, silently tolerate a
missing adapter, or resolve a key differently. It would also invite a
configuration-only connector mechanism that makes endpoint and schema choices
without reviewed Java code.

## Decision

Create one Spring Boot auto-configuration core that owns typed `dataprism.*`
binding, validation, and construction of the framework-neutral privacy pipeline.
Both the dependency-only starter and standalone server consume this core. The
server is the primary product; the starter is an embedded integration option,
not a second implementation.

The core accepts only reviewed integration points. In Java-first mode the host
application supplies `DataSourceAdapter` and `IdentityResolver` beans and
annotated LLM-exposed models. Configuration supplies only the deployment
parameters specified in `docs/configuration.md`. A missing required component
is a startup failure.

`mcp` and `orchestration` continue to depend only on core SPIs. They must not
depend on `connectors-*`, the auto-configuration module, the starter, or the
server. The auto-configuration module is the dependency/wiring leaf; concrete
connectors are selected there or by the host application at runtime.

Stdio remains a fixture-only, single-principal development transport. Protected
APIs use authenticated Streamable HTTP. Generic configuration-driven JSON REST
sources are deferred and require a separate ADR and security review.

## Consequences

- One validated contract governs both adoption paths and makes tests reusable.
- The starter cannot silently expose an empty or weakly configured server.
- The standalone server initially supports only adapters packaged and reviewed
  with it; broader JSON configuration is intentionally delayed.
- Adding a connector does not widen the MCP/orchestration dependency graph or
  permit a caller-controlled backend request.
- The auto-configuration module becomes a release-critical compatibility
  surface, so future property changes require migration notes and validation
  tests.
