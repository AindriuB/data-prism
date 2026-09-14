# 21 — Refuse fixture development in HTTP mode, in the shared validator

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/FixtureDevelopmentRefusalTest.java *(new)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerIntegrationsConfiguration.java
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerSecurityConfiguration.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/http/SecurityConfig.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/SecurityConfigTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/StarterStartupFailureTest.java

## Goal

`fixture-development=true` currently turns off the whole protected-deployment
contract — JWT issuer/audience/JWKS, the HMAC key reference, audit and metrics
sinks, Hazelcast checks and every integration cross-check — for any consumer,
in any transport mode. Only `data-prism-server` guards against it, so a starter
consumer inherits nothing. Move the refusal into the shared validator so fixture
development means only what its name says: the stdio development path.

## Context

- `DataPrismProperties.java:34-38` — `validate()`; `fixture` short-circuits
  `protectedDeployment()` regardless of `transport.mode`.
- `DataPrismContractValidator.java:25` — the matching early return that skips
  adapter, identity, key, audit and metrics cross-checks.
- `ServerIntegrationsConfiguration.java:19-27` — the only guard that exists
  today, and it protects one module.
- `ServerSecurityConfiguration.java:102,148-154` and
  `SecurityConfig.java:117,169-175` — a plaintext `http://` JWKS location is
  accepted when `fixtureDevelopment` is set; both are resource-server-only code,
  so after this change the branch is only reachable if HTTP+fixture still is.
- `DataPrismAutoConfigurationTest.java:30-33` — `boots_a_minimal_reviewed_context`
  relies on HTTP+fixture today and must move to a valid configuration.
- Baseline: `main` at `99b419b`, `mvn -B verify` green, 377 tests, 0 failures.

## Acceptance

- [ ] `DataPrismProperties.validate()` refuses `transport.fixture-development=true`
      whenever `transport.mode` is not `STDIO`, with stable code
      `FIXTURE_DEVELOPMENT_STDIO_ONLY`. No module-local guard is required for a
      consumer to inherit that refusal.
- [ ] A starter-path test in `data-prism-example`
      (`StarterStartupFailureTest`) starts a starter-based application with
      `dataprism.transport.mode=http` and `fixture-development=true` and asserts
      startup fails with `FIXTURE_DEVELOPMENT_STDIO_ONLY`. Deleting the new
      refusal from `DataPrismProperties` makes this test fail — report the
      observed failure from that mutation.
- [ ] A test asserts that an HTTP context with `fixture-development=true` and a
      blank `dataprism.security.jwt.issuer` refuses with `MISSING_JWT_ISSUER`
      rather than starting, proving `protectedDeployment()` now runs.
- [ ] `DataPrismContractValidator.validateIntegrations` reaches its early return
      only for STDIO fixture development; a test asserts an HTTP context with
      `fixture-development=true` and no `DataSourceAdapter` still refuses.
- [ ] No reachable configuration accepts a plaintext `http://` JWKS or
      issuer-discovery location: the `fixtureDevelopment` relaxation in
      `ServerSecurityConfiguration` and example `SecurityConfig` is removed as
      unreachable, or a test demonstrates a configuration that still reaches it.
      Either way a test asserts `http://localhost/jwks` is refused.
- [ ] `ServerStartupTest.fixtureDevelopmentModeRefusesStartup` still passes and
      asserts a specific stable code rather than any failure.
- [ ] `mvn -B verify` is green; the new test total is reported against the 377
      baseline, and every test changed only because it used HTTP+fixture is
      listed in the close-out with what it moved to.

## Out of scope

- Bean conditions in `DataPrismAutoConfiguration` — task 22 owns that file.
- `dataprism.hazelcast` semantics and the read budget — task 25.
- `ServerPackagingIT` — task 24.
- Introducing any new development or "unprotected" mode to replace what this
  refusal removes.
