# 141 — Migrate the reactor to Spring Boot 4.1.1 on a single Jackson 2 classpath

**Repo:** `.`
**Release:** 0.5.0 (part of 139)
**Depends on:** none *(130 is done)*
**Owns:**
- pom.xml *(`spring-boot.version`, the Hazelcast BOM comment, one new `nimbus-jose-jwt` `dependencyManagement` entry; the enforcer rules stay as they are)*
- data-prism-spring-boot-autoconfigure/pom.xml
- data-prism-spring-boot-starter/pom.xml
- data-prism-server/pom.xml
- data-prism-integration-tests/pom.xml
- data-prism-connectors-rest/pom.xml
- data-prism-quickstart-fixtures/pom.xml
- data-prism-quickstart-issuer/pom.xml
- data-prism-quickstart-extension/pom.xml
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/**
- data-prism-server/src/main/resources/application.yaml *(the `management` block only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(existing files: import and assertion changes forced by the migration only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java *(the `AuditIntegrityHealth` nested class and its imports only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditIntegrityHealthTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/McpHttpEndToEndTest.java *(imports only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredJsonNestedHttpTest.java *(imports only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditSinkFailureAbortsResponseTest.java *(imports only)*

## Goal

Move every module from Spring Boot 3.5.16 to 4.1.1 (Spring Framework 7.0.9,
Spring Security 7.1.1, Tomcat 11.0.24) in one change, because the BOM bump
breaks compilation in several modules at once and cannot land in halves. The
classpath stays on Jackson 2 only (owner decision D-139-A): Boot 4's default
Jackson 3 starter is excluded and Boot's deprecated `spring-boot-jackson2`
support is used, so the scrubbing module still serialises every MCP response
through the one Jackson 2 `ObjectMapper`.

## Context

- A probe on 2026-10-07 (scratchpad clone, not a worktree) showed the whole
  migration is 19 files and about 50 changed lines. With those changes,
  `mvn verify` passed on JDK 21 with no failures in any module. Expect the
  same scale. If you find you need much more, stop and report.
- Jackson. On Boot 4.1.1, `spring-boot-starter-web` (now a deprecated alias of
  `spring-boot-starter-webmvc`) pulls `spring-boot-starter-jackson` →
  `spring-boot-jackson` → `tools.jackson.core:jackson-databind:3.1.5`. The
  enforcer (`pom.xml:246-285`) then fails the build in the starter, server,
  quickstart fixtures and issuer modules. Boot 4.1.1 still manages
  `jackson-2-bom` 2.21.5 and ships `spring-boot-jackson2`
  (`Jackson2AutoConfiguration`). `spring-boot-http-converter` has
  `Jackson2HttpMessageConvertersConfiguration`, and actuator has
  `Jackson2EndpointAutoConfiguration`. All of these are `@Deprecated(since =
  "4.0.0")`, marked for removal. MCP SDK 2.0.1 `mcp-core` is
  framework-neutral (servlet 6.1 `provided`), and `mcp-json-jackson2` stays.
- Moved types the probe hit. Each of these is the whole fix at its site:

  | Boot 3.5 | Boot 4.1 |
  |---|---|
  | `o.s.boot.actuate.health.{HealthIndicator,Health,Status}` | `o.s.boot.health.contributor.*` (new optional dep `spring-boot-health`) |
  | `o.s.boot.web.servlet.context.ServletWebServerApplicationContext` | `o.s.boot.web.server.servlet.context.ServletWebServerApplicationContext` |
  | `o.s.boot.web.embedded.tomcat.TomcatServletWebServerFactory` | `o.s.boot.tomcat.servlet.TomcatServletWebServerFactory` |
  | `o.s.boot.web.embedded.tomcat.TomcatWebServer` | `o.s.boot.tomcat.TomcatWebServer` |
  | `factory.addAdditionalTomcatConnectors(...)` | `factory.addAdditionalConnectors(...)` |
  | `o.s.boot.web.servlet.error.{ErrorController,ErrorAttributes}` | `o.s.boot.webmvc.error.*` |
  | `o.s.boot.autoconfigure.web.servlet.error.{BasicErrorController,ErrorViewResolver}` | `o.s.boot.webmvc.autoconfigure.error.*` |
  | `o.s.boot.autoconfigure.web.ServerProperties` | `o.s.boot.web.server.autoconfigure.ServerProperties` |
  | `ServerProperties.getError()` and `server.error.*` | `WebProperties.getError()` and `spring.web.error.*` |
  | `o.s.boot.autoconfigure.hazelcast.HazelcastAutoConfiguration` | `o.s.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration`, in `spring-boot-hazelcast`, which nothing here depends on |

- `OperatorErrorController.java:28` maps `${server.error.path:...}`. On Boot 4
  that property is `spring.web.error.path`. Task 105's operator-port error
  handling depends on this mapping matching Boot's own `/error`.
- `DataPrismServerApplication` excludes `HazelcastAutoConfiguration` by class.
  On Boot 4 the class is absent unless `spring-boot-hazelcast` is on the
  classpath. `OperatorAddressTest.bootsOwnHazelcastAutoConfigurationIsExcluded`
  pins the exclusion by simple class name.
- `data-prism-server/src/main/resources/application.yaml` turns every actuator
  endpoint off with `management.endpoints.enabled-by-default: false`. That
  property has been deprecated since Boot 3.4.0 in favour of
  `management.endpoints.access.default`. Boot 4.1.1 metadata still lists it
  at warning level, so it still binds, but if it is ever dropped the
  endpoints come back on silently. That is a fail-open, so move to the
  replacement now.
- nimbus-jose-jwt. Spring Security 7.1.1 brings 10.9.1. Under Boot 3.5.16 the
  server bundled 9.37.4, and the quickstart issuer pins 10.10 with a stale
  comment (PLAN follow-up (a) from task 130). Owner decision D-139-C.
- Boot 4.1.1 also moves JUnit Jupiter to 6.0.3, Mockito to 5.23.0, Micrometer
  to 1.17.1 and Reactor to 2025.0.7. MCP SDK 2.0.1 was built against Reactor
  3.7. The probe passed with all of them. It still manages Hazelcast 5.5.0,
  which the root `dependencyManagement` entry keeps overriding to 5.7.0.
- `docs/conventions.md` — commit format `141: <summary>`.

## Acceptance

- [ ] `pom.xml` sets `spring-boot.version` to `4.1.1`. The enforcer
      `bannedDependencies` excludes are byte-identical to before. In
      particular `tools.jackson.core:*` is still banned.
- [ ] Every module that declared `spring-boot-starter-web` declares
      `spring-boot-starter-webmvc` instead, excludes
      `org.springframework.boot:spring-boot-starter-jackson`, and declares
      `org.springframework.boot:spring-boot-jackson2`.
      `mvn -B dependency:tree -Dincludes=tools.jackson.core` prints no
      `tools.jackson` artifact for any module.
- [ ] `mvn -B validate` passes, so the enforcer passes in every module.
- [ ] `DataPrismAutoConfiguration.AuditIntegrityHealth` is conditional on
      `org.springframework.boot.health.contributor.HealthIndicator`.
      `spring-boot-health` is an optional dependency of the autoconfigure
      module. `AuditIntegrityHealthTest` passes, and so does a context without
      `spring-boot-health` on the classpath (`FilteredClassLoader`), which has
      no `auditIntegrityHealthIndicator` bean.
- [ ] `DataPrismServerApplication` excludes Boot's Hazelcast
      auto-configuration by `excludeName =
      "org.springframework.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration"`,
      so the exclusion still holds if a deployer adds `spring-boot-hazelcast`.
      `OperatorAddressTest` asserts that string. A new assertion in an
      existing server test asserts that the running server context has exactly
      the `HazelcastInstance` beans Data Prism defines, or none for
      `topology: single-node`.
- [ ] `OperatorErrorController` maps `${spring.web.error.path:${error.path:/error}}`
      and builds `BasicErrorController` from `WebProperties.getError()`. No
      file in the repository still reads `server.error.`
      (`grep -rn 'server\.error\.' --include='*.java' --include='*.y*ml' .`
      prints nothing outside `docs/plan`).
- [ ] The second operator connector is added with `addAdditionalConnectors`.
      `OperatorAddressTest`, `OperatorSurfaceTest` and
      `ServerSecurityBoundaryTest` pass with only import changes plus the
      exclusion assertion above.
- [ ] `data-prism-server/src/main/resources/application.yaml` uses
      `management.endpoints.access.default: none` and no longer contains
      `enabled-by-default`. A server test asserts that `GET /actuator/health`
      and `GET /actuator/info` on the shipped configuration do not return 200.
- [ ] *(D-139-C)* `nimbus-jose-jwt` is managed once in the root
      `dependencyManagement`, at a version no lower than Spring Security
      7.1.1's 10.9.1. The quickstart issuer's own `<version>` and stale
      comment are removed.
      `mvn -B dependency:tree -Dincludes=com.nimbusds:nimbus-jose-jwt -pl data-prism-server,data-prism-quickstart-issuer`
      shows one version in both.
- [ ] `spring-boot-maven-plugin` repackaging still works. `ServerPackagingIT`
      and `ConfiguredJsonSourcesPackagingIT` pass, and the packaged server's
      `BOOT-INF/lib` holds no Jackson 3 jar:
      `unzip -l data-prism-server/target/data-prism-server-0.4.1.jar | grep -cE 'BOOT-INF/lib/jackson-(core|databind)-3\.'`
      prints `0`, and the same listing shows one `jackson-databind-2.` jar.
- [ ] `data-prism-architecture`'s `ArchitectureTest` and
      `ServerArchitectureTest` pass unchanged.
- [ ] `mvn -B verify` over the full reactor passes on JDK 21. The commit body
      states the test count, counted by the method `docs/conventions.md`
      prescribes.
- [ ] Spring Security 7 compiles with the existing lambda DSL, and neither
      `SecurityFilterChain` bean changes. If a Security 7 deprecation warning
      appears for either chain, record it in the commit body; do not rewrite
      the chain in this task.

## Out of scope

- Jackson 3 (`tools.jackson`, `mcp-json-jackson3`, porting the scrubbing
  module). That is a separate, unplanned migration (D-139-A).
- New regression guards for converters, actuator JSON and the operator
  `/error` path. That is task 142.
- `README.md`, `CLAUDE.md`, `docs/**`, `server.json`, `CHANGELOG.md`. That is
  task 147.
- Surefire `argLine`, JDK 25 and Docker images. Those are tasks 143-146.
- `.github/dependabot.yml`. Its Spring Boot major-version ignore now blocks
  Boot 5, which is still correct. Task 145 owns the file.
- `DataPrismProperties`, `JwtCallerContextExtractor`, `DataPrismContractValidator`,
  and every part of `DataPrismAutoConfiguration` other than `AuditIntegrityHealth`.
  Task 113 owns them.
