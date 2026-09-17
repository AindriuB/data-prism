# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1] - 2026-09-17

A dependency and infrastructure patch, no features. 0.1.1 supersedes 0.1.0 for
the published image: an image built from this tree carries a different JWT
library than the 0.1.0 artifacts already on Maven Central, so re-tagging the
image as 0.1.0 would have made the version label lie about its contents.

### Changed

- nimbus-jose-jwt bumped to 10.9.1, a security patch on the JWT verification
  path.
- The server image is now published as a multi-architecture manifest list
  (`linux/amd64` + `linux/arm64`) instead of `linux/amd64` only.
- Three GitHub Actions dependency bumps in CI workflows.

## [0.1.0] - 2026-09-16

First release: the walking skeleton and every slice through S9a.

### Added

- Privacy engine that assigns one deterministic synthetic identity per
  `(scope, subject, namespace, algorithm version, key)`, never random and
  never stored in plaintext.
- Correlation and consistency findings that surface source-data
  inconsistencies instead of hiding them.
- Parallel mTLS connectors to enterprise source APIs.
- Embedded Hazelcast identity cache and per-scope read budget.
- OAuth2 resource server with session-derived `PrivacyContext`.
- Audit logging and metrics.
- One MCP tool, `get_entity_context`.
- Standalone server as the primary deployment surface, and a Spring Boot
  starter for embedding the privacy layer directly.
- A one-command local Compose quickstart.

### Not included in this release

- The re-identification operator surface, deferred past V1 by decision (see
  `docs/architecture.md#decisions-worth-knowing`).
- The Elasticsearch connector and its search tools.
- The three additional MCP tools named in the design review — only
  `get_entity_context` exists today.
- An append-only audit sink with hash-chain verifier. The only audit sink in
  this release writes to a file and to SLF4J.

[0.1.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.1
[0.1.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.0
