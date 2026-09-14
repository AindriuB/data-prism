# 18 — Deliver a reproducible local quickstart

**Repo:** `.`
**Depends on:** 16, 17, 21, 23, 25

**Amended 2026-09-14.** As first written this task owned no Java module but
required three runnable artefacts that do not exist, and assumed a
fixture-development shortcut that the server refuses. The `Owns` list, the
`Context` section and the first two acceptance criteria below are the amendment;
the goal is unchanged.

**Owns:**
- compose.yaml
- docker/**
- docs/quickstart.md
- .env.example
- data-prism-quickstart-fixtures/** *(new module: the synthetic source APIs)*
- data-prism-quickstart-issuer/** *(new module: the local JWT issuer)*
- data-prism-quickstart-extension/** *(new module: the reviewed adapter jar)*
- pom.xml *(root: the `<modules>` and `dependencyManagement` entries for the
  three modules above only — task 23 adds its own module entry, hence the
  dependency edge)*

## Goal

Replace source-reading as onboarding with one safe, reproducible local journey
that brings up the standalone server, synthetic fixture APIs and a local token
issuer, then proves an agent-compatible MCP request succeeds.

## Context — what does not exist yet

Three runnable pieces are missing, and none of them can be borrowed:

- **Synthetic fixture APIs.** `data-prism-example` has stub adapters but no
  `spring-boot-maven-plugin`, so it builds a library jar that cannot be started.
  Nothing in the repository serves a fixture HTTP source.
- **A local JWT issuer.** JWT minting exists only at test scope, inside
  `data-prism-example/src/test/java/.../http/McpHttpEndToEndTest.java:96-139`
  and `data-prism-server/src/test/java/.../ServerSecurityBoundaryTest.java`.
  `McpHttpEndToEndTest:96-139` is the worked pattern: generate an RSA key,
  serve the JWK set over HTTPS from a generated PKCS12 keystore, sign tokens
  with it. That is the shape the issuer service needs, promoted to main code.
- **A reviewed adapter extension.** The standalone server refuses startup
  unless a `DataSourceAdapter` bean exists for every configured source, plus an
  `IdentityResolver` — see `ServerPackagingIT.java:150-170` for a minimal
  reviewed extension and `-Dloader.path` for how it is loaded. Configuration-
  driven REST sources are task 20 and are not available here.

Two constraints follow from task 21 and cannot be worked around in this task:

- **The server refuses `dataprism.transport.fixture-development=true`.** The
  quickstart issues real JWTs against a real issuer; there is no development
  bypass to fall back on.
- **Plaintext `http://` is refused for the JWKS location, and for every
  `dataprism.sources.*.base-url`.** The issuer and the fixture APIs both serve
  HTTPS with a certificate generated at first run into an ignored path, and the
  server trusts it by reference. Nothing generated is committed.

## Preconditions

`docker compose up` cannot be verified on this machine as things stand: the
Docker daemon is not running (OrbStack is not started). Per
`docs/conventions.md:253-258`, a criterion must be checkable by a measurement
somebody has taken at least once — so the compose criteria below are blocked
until the daemon is up. Start OrbStack and confirm `docker compose version`
before this task is dispatched; if that is not possible, split the three service
modules out as a predecessor task and leave the compose half unscheduled.

## Acceptance

- [ ] The fixture APIs, the token issuer and the adapter extension are built by
      `mvn -B verify` from the repository root: the first two produce executable
      Spring Boot jars, the third a plain jar carrying an
      `AutoConfiguration.imports` entry loadable through `-Dloader.path`.
- [ ] A smoke test that needs no Docker daemon starts the three artefacts and
      the packaged server directly, obtains a token from the issuer, and asserts
      a `get_entity_context` call over `/mcp` returns a pseudonymised response
      while an absent or invalid token is refused.
- [ ] `docker compose up` uses only synthetic fixtures and generated local
      credentials; no real endpoint, token, key or certificate is committed.
- [ ] The quickstart documents exact commands to start, obtain a development
      token, discover the MCP tool, invoke `get_entity_context`, and stop/reset
      the environment.
- [ ] The Compose configuration passes secrets by environment/file reference,
      not inline production-like values, and labels every fixture-only default.
      It sets `dataprism.hazelcast.topology` explicitly, as task 25 requires.
- [ ] The guide distinguishes this local demonstration from a production
      deployment and links to the configuration reference.
- [ ] The guide states that the quickstart issues real JWTs because no
      development bypass exists, and names the one-command path to a token.

## Out of scope

- Kubernetes/Helm, cloud secret-manager integration, or treating Compose as a
  production deployment.
- Making `data-prism-example` runnable, or adding `spring-boot-maven-plugin` to
  it — the quickstart's services are their own modules, and task 23 owns that
  pom.
- Configuration-driven REST sources — task 20.
