# 125 — Integration tests derive the artifact version instead of hardcoding 0.3.1

**Release:** 0.4.0
**Depends on:** none (based on be8d769, the 0.4.0-SNAPSHOT bump)

## Why

The bump to 0.4.0-SNAPSHOT (be8d769) left several integration tests looking for
`…-0.3.1.jar` in `target/`. In a clean worktree those files do not exist, so the
tests fail. The planning worktree only passed because 0.3.1 jars were still in
`target/` from earlier builds. Any version hardcoded in a test breaks again at
the next release.

## Owns

- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfigurationRefusalMessageIT.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfiguredJsonSourcesPackagingIT.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java
- data-prism-server/pom.xml *(failsafe/surefire `systemPropertyVariables` only)*
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java
- data-prism-quickstart-extension/pom.xml *(failsafe/surefire `systemPropertyVariables` only)*

## Acceptance

- [ ] No test source contains a literal project version. `git grep -n "0\.3\.1\|0\.4\.0" -- '*/src/test/**'` returns only prose or Javadoc examples. Any remaining one is justified in the close-out.
- [ ] The version is passed in by the build: e.g. a `project.version` system property set from `${project.version}` in the failsafe/surefire configuration and read once per test class. If the property is missing, the test fails with a clear message. It never silently passes.
- [ ] Synthetic entry names such as `BOOT-INF/lib/data-prism-integration-tests-<version>.jar` in ServerPackagingIT use the same derived version, or a deliberately fixed fake version that is not the project version and is named as such.
- [ ] From a **clean** worktree, with no stale `target/` directories (`git clean -xdf` on the worktree, or a fresh worktree), the full reactor `mvn verify` exits 0. Report the real exit code. Do not run `mvn install`.

## Out of scope

- `DataPrismMcpServer.serverInfo("data-prism", "0.3.1")` (data-prism-mcp, owned by task 118 while it is in flight). Follow-up: derive the version from the jar manifest or a filtered resource.
- `docker/distribution/Dockerfile` `ARG VERSION=0.3.1` and the `publish-image.yml` default. These are release-time inputs; handle them when the release is cut.
