# 07 — Run it for real: OAuth2 resource server, Micrometer, and a log scan that fails on PII

**Repo:** `.`
**Depends on:** 02, 05, 06
**Owns:**
- data-prism-example/pom.xml
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/http/**
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java
- data-prism-example/src/main/resources/**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ArchitectureTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/StdioProductionProfileTest.java
- data-prism-example/src/test/resources/**

**Ownership note.** `ExampleApplication.java` is the repo's only
`DataPrismMcpServer.stdio(...)` call site, so the production-profile item below
lands there and nowhere else; it is listed here for that one item. It is also in
task 09's `Owns`, as is `data-prism-example/pom.xml`. That is why 09 depends on
07 rather than running beside it — do not parallelise them. Everything else in
`..example..` outside the globs above, including `DataPrismAssembly`,
`WorkedExampleTest` and `EndToEndTest`, stays task 09's.

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
      under the production profile and starts under the development one. The refusal itself already
      exists — `DataPrismMcpServer.stdio` throws `SecurityRefusedException("STDIO_DEVELOPMENT_ONLY")`
      when its `productionDeployment` argument is `true`, and DataPrismMcpServerTest.java:81-99 already
      asserts both directions of *that* argument. What is missing is the wiring: the repo's only call
      site, ExampleApplication.java:59-60, passes a hardcoded `false`. So this item is satisfied at that
      call site — the boolean becomes derived from the active Spring profile — and the test goes in
      `StdioProductionProfileTest`. Do not build a second stdio launcher inside the Boot application to
      avoid touching that file: a Spring Boot stdio path would duplicate the launcher and put a banner
      on the stream stdout is the protocol for.
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
- [ ] The HTTP transport itself is exercised, not just constructed. Task 06's tests assert only
      that `DataPrismMcpServer.streamableHttp(...)` returns a non-null `server()` and
      `transportProvider()` — no server is started and no socket is opened — and `EndToEndTest`
      drives the call handler through a synthetic `McpSyncServerExchange`. So the
      `contextExtractor` delivering an authenticated caller into `exchange.transportContext()`
      on a real request is unverified, and this is the first task with a real server to make
      requests against. A test starts the application, makes a real HTTP MCP request bearing a
      valid JWT, and asserts the resulting `PrivacyContext` carries the scope, principal,
      purpose and case derived from that token rather than any default or constant — assert
      against values that appear only in the token, so a hardcoded default cannot satisfy it.
      It asserts the negative in the same test class: a request with no token, and one whose
      purpose is not in the configured list, are both refused and never reach the orchestrator
      — assert on a test double that records calls and shows none, not merely on the response
      status. The server binds an ephemeral port, never a hardcoded one, and leaves no server
      or thread pool running after the test finishes; both were checked on task 06 and were
      clean, and both are checked again here. The JWT is minted locally in the test against the
      in-test JWKS — no real token, credential or personal data enters the repository, and any
      leak fixture uses documented invalid check digits.
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

## Attempt 1 — failed

12 of 13 acceptance criteria met, and the important ones are solidly met. The HTTP
transport is genuinely exercised: hardcoding a constant caller in the extractor
reddens four tests, and each negative-direction mutation flips exactly one
direction, so no test passes on a blanket refusal. Boundary 1 holds —
`streamableHttp` builds its own `DataPrismObjectMapper.create()`, no `ObjectMapper`
bean is injected, and the MCP servlet is registered raw, so Spring Boot's
auto-configured mapper never reaches it. Scope clean; no secrets; ArchUnit rules
gained two and the existing ones are byte-identical.

Rejected for one defect, in the one test least able to afford it:

**`PiiLogScanTest.java:71` fails at random, roughly one run in four.**
`BANNED_VALUES` contains the bare ids `"123"` and `"456"`. Audit lines carry
random hex — event id, correlation id, `params`, `hash`, `prev` — and whenever a
hex run happens to contain `123` or `456` the scan reports a leak that does not
exist. Measured: 3 failures in 12 clean runs, e.g. `Expecting empty but was:
["456"]`.

Two consequences, the second worse than the first. `mvn -B verify` fails at
random, so the build stops meaning anything. And this is the test whose entire
job is to detect personal data in log output — a leak detector that cries wolf
at random is one nobody reads, and the next real leak arrives looking like the
usual noise.

Fix the matching so a banned value cannot match incidental hex. Do not fix it by
deleting the short ids from the list: `123` is the stub subject id and is exactly
the kind of value that must not appear in a log line. Match on value boundaries,
or scan structured fields rather than the raw line, or plant ids that cannot
occur in hex — but keep the ids in scope.

Also address, from the same review:

- `PiiLogScanTest.java:76-85` — nothing asserts the captured output is non-empty.
  If the integration run ever stopped logging, the scan would pass in silence,
  which is the failure mode this project has now hit five times. Assert the
  capture contains a known-safe marker.

Not part of this task, recorded so it is not lost: `MutualTlsRestClientsHttpsTest`
failed once under a full `verify` and passes in isolation. It is in a module this
branch does not touch. It was rewritten in task 08, so the flake may date from
there.
