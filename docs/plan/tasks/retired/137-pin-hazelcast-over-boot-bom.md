# 137 — Pin Hazelcast 5.7.0 over Spring Boot's managed version

**Repo:** `.`
**Release:** 0.4.1
**Depends on:** none (any time before 136)
**Owns:**
- pom.xml *(the `<dependencyManagement>` block only: one new entry for `com.hazelcast:hazelcast`)*
- data-prism-hazelcast/pom.xml *(the hazelcast `<version>` element only, if it becomes redundant)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java *(one new assertion)*
- data-prism-server/pom.xml *(the failsafe `systemPropertyVariables` only: add `hazelcast.version`; added 2026-10-06 after the implementer's BLOCKED return)*

## Goal

Found by task 134 on 2026-10-06 and confirmed by the main session. The
packaged `data-prism-server-0.4.0.jar` bundles `BOOT-INF/lib/hazelcast-5.5.0.jar`,
not 5.7.0. The root pom declares `<hazelcast.version>5.7.0</hazelcast.version>`,
but the imported `spring-boot-dependencies` 3.5.16 BOM manages
`com.hazelcast:hazelcast` at its own `hazelcast.version` (5.5.0). An imported BOM
resolves its own properties, so it wins for every module that receives Hazelcast
transitively: the server and any starter consumer. Only `data-prism-hazelcast`,
whose dependency states `${hazelcast.version}` explicitly, builds and tests
against 5.7.0.

So the 0.4.0 server image and jar ship Hazelcast 5.5.0. Task 131's bytecode checks
were made against 5.7.0: OSS TLS absence, bind.any semantics, advanced-network
behaviour and auto-detection rules.

## Acceptance

1. The root `<dependencyManagement>` declares `com.hazelcast:hazelcast` at
   `${hazelcast.version}` **before** the Spring Boot BOM import, so it takes
   precedence. `mvn -pl data-prism-server -am dependency:tree -Dincludes=com.hazelcast`
   shows 5.7.0, and so does the starter (`data-prism-spring-boot-starter`).
2. `ServerPackagingIT` asserts that the packaged jar contains
   `BOOT-INF/lib/hazelcast-5.7.0.jar`, read from the `hazelcast.version` build property
   passed by failsafe (the task 125 pattern), and no other `hazelcast-*.jar`. It must fail
   before the pin.
3. The full reactor `mvn clean verify` and `mvn -Prelease -Dgpg.skip=true clean verify` both pass.
   Always build the full reactor or use `-am`; never `-rf`; never run `mvn install`.
4. In the return, state whether any test behaves differently with 5.7.0 in the
   server module, and list any Hazelcast 5.5→5.7 behaviour change the multi-member tests (134) or the
   refusal checks (131/132) depend on.

## Out of scope

The CHANGELOG line, which belongs to 136: "the server and starter now ship Hazelcast 5.7.0
(0.4.0 shipped 5.5.0 through Spring Boot's dependency management)".
