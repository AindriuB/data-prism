# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-09-17

A second MCP tool, `compare_entity_sources`, and no other new feature.

### Added

- `compare_entity_sources`, the second MCP tool: per-field `identity` plus
  findings over the same correlated, scrubbed `ContextResponse`
  `get_entity_context` already builds. Findings report agreement,
  disagreement (`INCONSISTENT`/`FORMATTING_ONLY`/`ABBREVIATION`) and
  `MISSING_IN_SOME_SOURCES`, each distinguishable by an explicit
  discriminator rather than by absence, so a caller can tell "compared and
  consistent" from "never compared".

### Changed

- The MCP registry namespace is corrected to `io.github.AindriuB/data-prism`,
  matching the casing the registry actually grants for the GitHub login.
  Maven Central's `io.github.aindriub` and the GHCR path
  `ghcr.io/aindriub/data-prism-server` are different identifiers, each
  correct in its own system and untouched by this correction — do not
  "fix" the casing inconsistency between them; doing so would break two
  already-published artifacts. No MCP registry listing for this server exists
  yet; this corrected namespace, and the label it is checked against, is what
  the first successful publish will use. `server.json`'s OCI package
  reference is also corrected to the canonical form the registry requires: no
  `registryBaseUrl`, and `identifier` now carries the image tag directly
  (`ghcr.io/aindriub/data-prism-server:0.2.0`) rather than a bare path paired
  with a separate `version` field.

### Behavioural change for API consumers

- `ContextResponse` gained a new record component (`fieldsByNamespace`), so
  its `equals`, `hashCode` and `toString` now include it. This is not a
  linkage break — binary compatibility was verified with `javap` against the
  published 0.1.1 jar, and the old constructor signature still works — but
  two `ContextResponse` values that compared equal under 0.1.1 may no longer
  compare equal under 0.2.0.

### Not changed

- The privacy engine, pseudonymisation and security modules: no behaviour
  change in this release.

## [0.1.1] - 2026-09-17

A release-plumbing patch, no features. 0.1.1 supersedes 0.1.0 for the
published image: the `v0.1.0` tag predates the multi-architecture publish
pipeline, so a dispatch against that tag would silently re-run the old
single-architecture workflow rather than publish a multi-arch image. `main`
has also diverged from the tag since (CI action bumps, documentation, a
quickstart dependency pin), so an image built from `main` and labelled
0.1.0 would not correspond to the tagged tree. Cutting a patch version is
cheaper and more honest than force-moving a tag a published GitHub Release
already points at. No production source differs from 0.1.0, so the library
artifacts on Maven Central are functionally identical and consumers of
those jars have no functional reason to upgrade; 0.1.1 exists for the image
and the publish pipeline, where multi-architecture support is a real
improvement for anyone running the image.

### Changed

- The server image is now published as a multi-architecture manifest list
  (`linux/amd64` + `linux/arm64`) instead of `linux/amd64` only.
- Three GitHub Actions dependency bumps in CI workflows.
- nimbus-jose-jwt bumped to 10.9.1 in `data-prism-quickstart-issuer`. This
  affects the quickstart issuer only, not the published server or library
  artifacts, which never depend on it directly.

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

[0.2.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.2.0
[0.1.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.1
[0.1.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.0
