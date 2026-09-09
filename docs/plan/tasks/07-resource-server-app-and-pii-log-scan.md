# 07 — Run it for real: OAuth2 resource server, Micrometer, and a log scan that fails on PII

**Repo:** `.`
**Depends on:** 02, 05, 06
**Owns:**
- data-prism-example/pom.xml
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/http/**
- data-prism-example/src/main/resources/**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ArchitectureTest.java
- data-prism-example/src/test/resources/**

## Goal
Assemble the deployable shape: a Spring Boot application that validates any JWT
against a configured JWKS URL, converts the verified claims into an
`AuthenticatedCaller`, hands it to the MCP streamable HTTP transport as
per-request context, binds `PrivacyMetrics` to Micrometer, and refuses the stdio
transport when a production profile is active. Then prove the observability claim:
a full integration run's log output contains no PII.

This is what removes the asterisk from the README — every claim it makes becomes
true of a deployment rather than only of the library.

## Context
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java —
  after task 04 it exposes `orchestrator()`, `investigationContext()`, `pseudonymisationVersion()` and
  `clock()`, which is everything a `ScopeResolver` needs
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java:12-20 —
  why stdio pins all diagnostics to stderr: under stdio, stdout *is* the protocol
- data-prism-mcp — the streamable HTTP factory and the `McpTransportContextExtractor` contract from
  task 06; `data-prism-security` — `AuthenticatedCaller.fromClaims`, `SecurityPolicy.fromYaml`
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ArchitectureTest.java:41-90 —
  the existing boundary rules; the new `security` module needs rows of its own
- docs/pack.md:2768-2789 — §90, the good and bad log lines
- docs/pack.md:2739-2766 — §89, and the label names that must never appear
- docs/conventions.md — "Leak tests assert on absence, which is easy to write vacuously"; also
  `logs/` at the repo root for any log file a test writes, and it is gitignored
- Local `~/.m2` has `spring-boot-dependencies:3.5.16`; the OAuth2 resource server and Tomcat starters
  at that version are **not** cached and will need a network fetch on first build

## Acceptance
- [ ] `data-prism-example/pom.xml` adds `spring-boot-starter-web`,
      `spring-boot-starter-oauth2-resource-server` and `micrometer-core`, all version-managed by the
      existing Boot BOM import. The enforcer's banned-dependency rule still passes and no Jackson 3
      artifact enters the tree — verified with `mvn -B dependency:tree` showing a single Jackson major.
- [ ] A Spring Boot application starts an HTTP server whose only unauthenticated endpoint is the health
      probe; the MCP endpoint requires a bearer token, and an unauthenticated request to it gets 401.
- [ ] The resource server is configured by `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`
      alone — no IdP-specific client, discovery hack or issuer-specific claim name in code. Required
      issuer and audience are configuration values, and a token failing either is rejected.
- [ ] A `McpTransportContextExtractor<HttpServletRequest>` converts the verified `Jwt`'s claims through
      `AuthenticatedCaller.fromClaims` and puts the caller in the transport context. The raw token is
      never placed in the context, logged, or put on any request attribute.
- [ ] `application.yaml` carries the purpose list and the role-to-capability map, loaded through
      `SecurityPolicy`; it contains no secret, key, keystore password or real URL of any kind.
- [ ] A `MicrometerPrivacyMetrics` implements `PrivacyMetrics` over a `MeterRegistry`. A test asserts
      that the registry contains exactly the `Metric` enum's names after a run, and that no meter in the
      registry carries a tag key or value drawn from `idInternal`, `name`, `email`, `PPSN` or
      `accountId` — the §89 banned list, asserted rather than assumed.
- [ ] Starting the stdio transport with the production Spring profile active fails at startup with a
      message naming stdio as a development-only transport. A test asserts both directions: it fails
      under the production profile and starts under the development one.
- [ ] An end-to-end test drives the real HTTP endpoint with a locally-signed JWT — key pair generated in
      the test, JWKS served from an in-test endpoint — and asserts: a valid token with a permitted
      purpose returns pseudonymised context; an unknown purpose is refused with the `UNKNOWN_PURPOSE`
      code; two tokens with different `case_id` claims give the same subject different pseudonyms; and a
      caller without `EXPOSE_SOURCE_NAMES` sees aliased source names while one with it sees real ones.
- [ ] A log-scanning test captures *all* log output of a full integration run — application log and
      `dataprism.audit` — into a file under `logs/`, and fails if it contains any of the stub fixtures'
      identifying values: the customer names, the email addresses, and the raw subject ids. It fails on
      substring match, not on a regex that could be satisfied vacuously.
- [ ] The log-scanning test is proved non-vacuous by a companion test that pushes a known fixture value
      through the same logger and asserts the scanner reports it. Per the reviewer-isolation convention,
      note in the task close-out that this is a mutation-shaped proof, so a reviewer must read from
      `git show` rather than the working tree while it runs.
- [ ] `ArchitectureTest` gains rules that `..dataprism.security..` does not depend on
      `..dataprism.mcp..`, `..dataprism.orchestration..`, `..dataprism.connectors..` or
      `..dataprism.example..`, and that no class outside `..dataprism.example..` depends on
      `org.springframework.security..`. Existing rules are unchanged.
- [ ] `mvn -B verify` passes from the repo root with no test failures and no skipped tests, and the
      test count is not below the tip of `main` — establish that number by running the suite on `main`
      before starting.

## Out of scope
- The append-only audit sink and the chain verifier. S9's other half, deliberately deferred.
- Any log filter that fails the *build* on a sensitive-typed interpolation. That is a static check and
  belongs with S12's hardening; this task's guarantee is a runtime scan of a real run.
- Docker Compose, an Elasticsearch connector or additional stub sources — S11.
- A re-identification endpoint, port or module — S10, deferred past V1.
- The `spring-boot-starter` auto-configuration module. Wiring stays hand-written in the example, as it
  has since S0.
- Editing `EndToEndTest` or `WorkedExampleTest`; new HTTP-level tests go in the `http` sub-package.
