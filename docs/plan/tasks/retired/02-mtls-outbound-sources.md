# 02 — mTLS for outbound source calls

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-connectors-rest/**

## Goal
Source calls currently go out over whatever `RestClient` the assembler happened to
build. Give the connector module a configured `SSLContext` — client certificate and
trust anchors from a keystore named in configuration — and a way to refuse a source
whose base URL is not HTTPS when TLS is required. Mostly configuration, but it is
the half of §49 that faces the enterprise APIs rather than the caller.

## Context
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestSources.java:20-60 —
  the hand-written YAML loader whose style the TLS settings follow: a malformed entry is a startup
  failure naming the source, never a source that silently does not exist
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestSource.java:30-45 —
  the existing scheme validation, which currently permits `http`
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapter.java:38-43 —
  the adapter takes a `RestClient` it does not build; the factory added here builds it
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapterHttpTest.java:31-40 —
  the existing test harness pattern, `com.sun.net.httpserver.HttpServer` on an ephemeral port
- docs/pack.md:1630-1662 — §49, mTLS on the outbound leg
- docs/conventions.md — "What never goes in a file": no credentials, keys or keystores are committed

## Acceptance
- [ ] A `TlsSettings` record carries key store location, trust store location, store type and the
      *name of the environment variable* holding each password — never a password value. Loading
      settings that name an unset variable fails at construction with a message naming the variable
      and not its value.
- [ ] A `MutualTlsRestClients` (or equivalently named) factory builds a `RestClient` over an
      `SSLContext` initialised from those stores, with no fallback to the default context: a store
      that cannot be opened throws, and no client is returned.
- [ ] `RestSource` gains a `requireHttps` mode such that constructing a source with an `http` base URL
      under it throws `IllegalArgumentException` naming the source; the existing permissive behaviour
      remains available and remains the default for the in-process stub tests only.
- [ ] `RestSources.fromYaml` parses an optional top-level `tls:` block into `TlsSettings` and rejects
      an unparseable one with a message naming the offending key.
- [ ] A test starts `com.sun.net.httpserver.HttpsServer` on an ephemeral loopback port configured with
      `setNeedClientAuth(true)`, using a key pair and self-signed certificate generated inside the test
      into a temporary directory, and asserts both directions: the adapter built by the factory fetches
      a record successfully, and an adapter built from a client without the client certificate fails.
      No keystore, key or certificate file is added to the repository.
- [ ] The password of the generated test keystore is a literal in the test, is obviously synthetic, and
      protects nothing that exists outside the test run — stated in a comment.
- [ ] `mvn -B verify` from the repo root passes; the existing 212 tests at `7db0491` all still pass.

## Out of scope
- Any change outside `data-prism-connectors-rest/`. In particular, the example application's wiring of
  these sources is task 07.
- Inbound TLS or the OAuth2 resource server — that is the caller-facing leg, tasks 06 and 07.
- Certificate pinning, rotation or an OCSP path. Configuration only, per the slice.
- Adding a Spring Boot or Spring Security dependency to this module.
