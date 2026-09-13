# Adoption roadmap

## Outcome

Make Data Prism usable in two supported ways: a standalone server for teams
protecting existing APIs, and a Spring Boot starter for applications that embed
the boundary. Both must use the same validated configuration model and preserve
the existing fail-closed privacy boundary.

The server is the primary product. The starter is an integration option, not a
second privacy implementation. Stdio remains fixture-only, single-principal
development mode; it is never a route to a production API.

## Release gate

Tasks 11, 12 and 13 remain prerequisites for an adoption release. In particular,
Task 13 must establish a non-vacuous test that Hazelcast never persists raw
values. They retain their existing ownership and are not superseded here.

## Waves

| Wave | Tasks | Outcome |
|---|---|---|
| 0 | 11, 12, 13 | Existing safety and test-debt work closed. |
| 1 | 14 | One agreed deployment contract, configuration vocabulary and supported-mode matrix. |
| 2 | 15 | A shared Spring Boot auto-configuration module binds and validates production configuration. |
| 3 | 16 | The starter proves the embedded Java-first integration path. |
| 4 | 17 | The standalone server packages the same configuration core as the primary product. |
| 5 | 18, 19 | A reproducible Compose quickstart and tested agent connection guides make the product approachable. |
| 6 | 20 | A separately reviewed configuration-driven JSON adapter serves non-Java source APIs. |

## Non-negotiable design constraints

- Configuration selects reviewed source adapters; it must not let an MCP caller
  select arbitrary hosts, paths, fields or schemas.
- The Java-first starter continues to require application-owned, annotated model
  types and explicit adapter/identity-resolution beans. It does not infer that
  data is safe from a DTO or endpoint name.
- A future generic JSON mode is a separate capability. It requires explicit
  subject and field classifications, allowlisted JSON paths, schema validation,
  and startup refusal on unknown or unclassified fields.
- Secrets are references or environment variable names, never literal values in
  application YAML, Compose files or agent configuration.
- The MCP surface remains closed. This roadmap does not add a generic proxy tool,
  re-identification tool, or caller-controlled privacy scope.

## Deferred deliberately

Elasticsearch/search tools, the append-only audit sink, re-identification,
Kubernetes/Helm packaging, and multi-provider secret-manager integrations remain
separate follow-on work. Docker Compose is a local integration environment, not
a production topology.
