# 146 — Make the test suite clean and proven on JDK 25, including on Linux

**Repo:** `.`
**Release:** 0.5.0 (part of 140)
**Depends on:** 141
**Owns:**
- data-prism-mcp/pom.xml *(the surefire plugin block, plus one `maven-dependency-plugin` `properties` execution)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/MutualTlsRestClientsHttpsTest.java *(only if the Linux JDK 25 run fails it; see Acceptance)*

## Goal

CI gains a JDK 25 leg (task 144), so the suite must pass there on Linux, the
platform CI uses. It must not depend on warnings a later JDK turns into
errors. This task runs the post-Boot-4 suite on JDK 25 in a Linux container,
fixes what that run breaks, and loads Mockito as a declared agent instead of
self-attaching. It records the Hazelcast `sun.misc.Unsafe` warning rather than
hiding it.

## Context

- Probe on 2026-10-07 (macOS, JDK 25.0.1, Boot 4.1.1 as in task 141): the
  full reactor passed. It logged:
  - `Mockito is currently self-attaching to enable the inline-mock-maker`
    and `A Java agent has been loaded dynamically (.../byte-buddy-agent-1.18.11.jar)`.
    JEP 451 warns now and plans to disallow by default in a later JDK.
  - `sun.misc.Unsafe::objectFieldOffset has been called by
    com.hazelcast.shaded.org.jctools.util.UnsafeAccess`, three times, in
    Hazelcast-backed tests.
  - `sun.misc.Unsafe::staticFieldBase has been called by
    com.google.inject.internal.aop.HiddenClassDefiner`. That is Maven 3.9's
    own Guice, not this project.
- `org.mockito` is used only by `data-prism-mcp` tests
  (`data-prism-mcp/pom.xml:29-36`).
- `MutualTlsRestClientsHttpsTest` matches a literal JDK message
  (`LINUX_HANDSHAKE_REFUSAL_MESSAGE`) on Linux only. See
  `docs/conventions.md:156-167` and HISTORY "Task 10". The macOS probe
  cannot exercise that branch. JDK 25 may have changed the text.
- Owner decision D-140-C: do not suppress the Unsafe warning
  (`--sun-misc-unsafe-memory-access=allow`) in tests, images or CI.
- Owner decision D-140-D (recommended yes): declare the Mockito agent.
- `docs/conventions.md` — the counting method for tests, and never
  `mvn install`.

## Acceptance

- [ ] `data-prism-mcp/pom.xml` resolves the Mockito jar path with
      `maven-dependency-plugin:properties` and passes
      `-javaagent:${org.mockito:mockito-core:jar}` in surefire's `argLine`,
      keeping `@{argLine}` first. A JDK 25 run of
      `mvn -B -pl data-prism-mcp -am verify` logs neither `self-attaching`
      nor `loaded dynamically`, and the commit body shows the grep.
- [ ] No other module's test output on JDK 25 contains
      `Mockito is currently self-attaching`. If one does, stop and report the
      module rather than editing its pom; it is outside `Owns`.
- [ ] The full reactor `mvn -B verify` passes on JDK 25 on Linux. Run it in
      a throwaway container on a scratch clone (not a worktree), for example
      `docker run --rm -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 mvn -B verify`,
      with the container's own local repository. The commit body records the
      image digest and the counted test total.
- [ ] Also on JDK 21 (`maven:3.9-eclipse-temurin-21`, same method), the full
      reactor passes.
- [ ] If the Linux JDK 25 run fails `MutualTlsRestClientsHttpsTest`, the fix
      accepts the new JDK 25 observable as well as the JDK 21 one. It never
      replaces a refusal check with a weaker one, and the test still fails
      against an unreachable server, as task 10 required. The commit body
      records both message texts. If the test passes, it is unchanged and
      the commit body says so.
- [ ] The commit body lists every `sun.misc.Unsafe` warning line from the
      JDK 25 run, with its calling class. No flag suppresses them.

## Out of scope

- `.github/workflows/**`. That is task 144.
- Dockerfiles. That is task 143.
- Upgrading Hazelcast past 5.7.0 to remove the Unsafe use.
- Root `pom.xml`. Task 141 owns it in this release.
- Any other file under `data-prism-connectors-rest/**`. Task 111 owns it and
  depends on this task.
