# 122 — Find and remove the cause of ServerStartupTest's intermittent /health 404

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/HealthController.java

## Goal

`ServerStartupTest.minimalReviewedExtensionStartsAndExposesOnlySafeUnauthenticatedHealth`
once got a 404 from `/health` instead of a 200 during a full reactor run, and
passed when rerun. A test of a security boundary that sometimes fails will
end up being ignored. This task finds the cause and either fixes it or makes
the test deterministic. It must not loosen any assertion.

## Context

- `ServerStartupTest.java:28-47`. The test starts with `--server.port=0`,
  reads the port from `getWebServer().getPort()`, then sends to
  `http://127.0.0.1:<port>`. Tomcat binds the wildcard address by default.
- Leading hypothesis, not yet confirmed. On macOS, a socket bound to the
  wildcard address and another bound to `127.0.0.1` on the same port can
  coexist. If another JVM's server held `127.0.0.1:<port>`, the request
  would reach that server, and it would answer 404. Candidates are another
  module's test, or a sibling worktree's reactor running in parallel.
  `docs/conventions.md#acceptance-criteria-discipline` records a flake that
  turned out to be contention between worktrees.
- Other candidates to rule out: `/health` mapped late relative to the
  connector starting, and the `ServerSecurityConfiguration` matcher
  (`:33`).
- Task 105 (open) owns `ServerSecurityConfiguration.java`, `pom.xml` and
  `server/operator/**`. If the fix needs any of them, stop and report.

## Acceptance

- [ ] The close-out names the cause, with evidence: a reproduction, or a
      measurement that rules each candidate in or out. If the cause cannot be
      proven, the close-out says so plainly and gives the evidence for the
      most likely cause.
- [ ] Whatever the cause, a failure of the `/health` assertion reports which
      server answered. The diff shows that the assertion message (`as(...)` or
      `withFailMessage(...)`) includes the status, the body and the response
      headers.
- [ ] If the cause is a shared port, the test binds the address it then
      calls. For example, `--server.address=127.0.0.1` makes the bind fail
      instead of sharing the port with another process. If the cause lies in
      `HealthController`, it is fixed there.
- [ ] All existing assertions keep their current expected values: 200 and
      `{"status":"UP"}` for `/health`, 401 for `/mcp`, and 401/403/404 for
      `/actuator/health`.
- [ ] Measured with this module's tests run on their own:
      `for i in $(seq 30); do mvn -q -pl data-prism-server -Dtest=ServerStartupTest test || exit 1; done`
      exits 0. A second 30-run loop exits 0 while another worktree's
      `mvn verify` runs at the same time. The close-out reports both exit
      codes.
- [ ] `mvn verify` over the full reactor passes.

## Out of scope

- The second operator port and any test of it. That is task 105.
- The `data-prism-integration-tests` "kill self fork JVM" warning (separate
  follow-up).
- Changing the production bind address.
