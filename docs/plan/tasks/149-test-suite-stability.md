# 149 — Remove the port races behind the known test flakes, without weakening any test

**Repo:** `.`
**Release:** 0.5.0 *(owner approved adding it, 2026-10-07)*
**Depends on:** none
*(Owns is test sources only and is disjoint from every open task: 113, 114, 115, 116, 147 and 148. Tasks 113 and 148 add new autoconfigure tests that may call `OversightConfigurationTest.runner(..)` and `ClusterConfigurationTest.singleMember()`, so those helpers keep their names, parameters and return types.)*
**Owner decisions:** D-149-A, D-149-B, D-149-C (decided 2026-10-07, see the end of this file)
**Owns:**
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/FreePorts.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java *(cluster and port setup helpers only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalStoreContractTest.java *(cluster and port setup helpers only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/IdentityMetricsTest.java *(`Config` network setup only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSourceTest.java *(`Config` network setup only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudgetTest.java *(`Config` network setup only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastStoredValueBoundaryTest.java *(`Config` network setup only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java *(`freePort`, `singleMember` and the supplied-instance helpers only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/OversightConfigurationTest.java *(`runner` only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ReidentificationConfigurationTest.java *(setup and teardown only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ReidentificationEndToEndTest.java *(setup only, if the harness change needs it)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ConfiguredJsonReidentificationEndToEndTest.java *(setup only, if the harness change needs it)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/ClusterMembers.java *(`freePorts` and launch only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/boot/Boot4Servers.java *(`freePort` only)*

## Goal
Four tests fail sometimes and pass on an unchanged rerun, mostly when two builds run at once. PLAN follow-up (f) lists three of them, and the Hazelcast port clashes are the fourth. Find and remove each root cause in the test harnesses, so that a full reactor passes repeatedly, including two full builds running at once. No assertion, refusal or fail-closed path may get weaker. Production code stays unchanged.

## Context
Read these before choosing a fix. Each one is a starting hypothesis, not a proven cause.

- **Probe-then-release port races (all three modules).** Each helper below opens a `ServerSocket`, reads its port and closes it. The port is bound for real only later, which leaves a window in which a concurrent build can take it:
  - `ClusterConfigurationTest.java:44` `freePort()`, used by `singleMember()` at `:52`, by `:200` and `:211`, and through `OversightConfigurationTest.runner(..)` at `OversightConfigurationTest.java:77-85` by every embedded autoconfigure test.
  - `OperatorHarness.java:423` `freePort()`, used for `operatorPort` at `:196` and the Hazelcast member port at `:242`.
  - `ClusterMembers.java:139` `freePorts(count)`.
  - `Boot4Servers.java:42` `freePort()`.
  - `FreePorts.java` in `data-prism-hazelcast`. Its own javadoc admits the gap.

  The production `ClusterMembership.toConfig()` (`data-prism-hazelcast/src/main/java/.../ClusterMembership.java:93`) sets `setPortAutoIncrement(false)`. A member that loses the race therefore fails to start, and the whole Spring context fails with it.
- **Wildcard bind against a 127.0.0.1 client (task 122's mechanism).** `OperatorHarness` starts the server with `--server.port=0` and no `--server.address`. The operator connector and the Hazelcast member also get no address, so all three bind the wildcard address. Clients then call `http://127.0.0.1:<port>` (`OperatorHarness.java:331`, `:345`, `:363`). On macOS, a socket another process binds to 127.0.0.1 shadows a wildcard bind on the same port. HISTORY says this in `## 2026-10-06 — Task 122: \`ServerStartupTest\` binds 127.0.0.1 to stop the \`/health\` 404 flake`. A foreign listener on the MCP or operator port would explain all of these symptoms:
  - the `ConnectException` and `ClosedChannelException` in `ReidentificationEndToEndTest`;
  - "Runtime Client failed to initialize by explicit API call" after about 31s in `ConfiguredJsonReidentificationEndToEndTest.aPseudonymFromAConfiguredJsonToolResultIsReidentified`. `McpSyncClient.initialize()` in `OperatorHarness.mcpClient` at `:361-375` waits on a peer that never answers.

  `OperatorAddressTest.java:30-31` passes its own `--server.address` and `--dataprism.operator.address`. `OperatorHarness.java:95` warns that a repeated command-line option is comma-joined, not overridden. Any default address the harness adds must therefore be dropped when the caller supplies one.
- **`ReidentificationConfigurationTest.the_service_exists_when_enabled_and_the_tool_list_is_unchanged`** (`:117-125`). It runs `runner("embedded", ..)`, so it starts a real member on a probed `freePort()` with `join.mode=none`, no interface and no auto-increment. The race in the first bullet fits the observed failure (`hasNotFailed` failed once in a full reactor). This is not proven.
- **JVM-wide state in `OperatorHarness`.** It sets `SSLContext.setDefault`, the `javax.net.ssl.*` and `dataprism.json-sources.config-location` system properties, and calls `Hazelcast.shutdownAll()` (`:139-147`, `:190-194`, `:394-405`). These are safe only while harnesses in one JVM do not overlap. Check that no surefire or JUnit parallel setting makes them overlap. If one does, isolate the state rather than serialising the tests silently.
- Single-node Hazelcast tests (`IdentityMetricsTest:52-57`, `CachingSyntheticValueSourceTest:49-53`, `HazelcastScopeBudgetTest:29-33`, `HazelcastStoredValueBoundaryTest:129-133`) leave the port at the Hazelcast default, 5701 with auto-increment. Under contention they can take a port that a sibling build has just probed. Change them only if the measurements implicate them.
- `docs/conventions.md` § "Do not run two tasks that drive the same exclusive resource at once". Re-measure every historical flake rate before trusting it.
- Precedent for recording an unproven cause honestly: HISTORY task 122.

Preferred direction, in order. Use the first one that removes the race:
1. Let the component pick its own port. Pass port 0 and read back the port it actually bound, for example from Tomcat's `getWebServer().getPort()`, the operator connector's `getLocalPort()`, or the Hazelcast member's `getLocalMember().getAddress()`. First check that the component and the production validation accept 0. Hazelcast 5.7 and `DataPrismProperties`'s operator-port rule must be checked, not assumed.
2. Bind every test listener to 127.0.0.1 explicitly, and probe with the same address the component will bind, never the wildcard.
3. Only if neither is possible, use a bounded re-allocation that applies to startup alone. See D-149-C.

## Acceptance
`$BASE` is the commit this task branched from, i.e. `git merge-base HEAD <integration branch>`.
- [ ] `git diff "$BASE" --stat -- '*/src/main/**' pom.xml '*/pom.xml' .github/` is empty. No production source, POM or CI file changes.
- [ ] The diff adds no `@Disabled`, `@DisabledIf*`, `@EnabledIf*`, `assumeTrue`/`assumeThat`, `@RepeatedTest`-as-retry, `@Timeout` increase, `rerunFailingTestsCount`, `skipAfterFailureCount` or `-Dsurefire.rerun*`. `git diff "$BASE" -U0 -- '*.java' '*.xml' | grep -E '^\+.*(Disabled|assume(True|That)|rerunFailingTestsCount|skipAfterFailureCount|EnabledIf|DisabledIf)'` prints nothing.
- [ ] No assertion line in the owned files is removed or loosened. Every `-` line in `git diff "$BASE" -- '*Test.java' '*IT.java'` that contains `assertThat`, `assertThrows` or `refuses(` has a `+` line that asserts the same thing. The reviewer checks this line by line.
- [ ] No timeout, `initializationTimeout`, `requestTimeout`, await bound or sleep is raised unless the commit body names the proven root cause that it serves. Raising a timeout is never the only change for a flake.
- [ ] Every remaining `new ServerSocket(0)` in the owned files, outside a helper whose socket stays open until the component has bound, either (a) is gone, replaced by a port the component reported after binding, or (b) carries a one-line comment saying why the race cannot occur there. `grep -n 'new ServerSocket' <owned files>` is in the commit body with each hit classified.
- [ ] `OperatorHarness` binds the MCP server, the operator connector and the embedded Hazelcast member to 127.0.0.1 by default. `OperatorAddressTest` still passes unchanged, and so do the other `OperatorHarness` callers. When a caller supplies `--server.address` or `--dataprism.operator.address`, that value replaces the default and is not appended to it.
- [ ] `OversightConfigurationTest.runner(String, String...)`, `ClusterConfigurationTest.singleMember()`, `ClusterConfigurationTest.uniqueName()`, and the `OperatorHarness` `start`, `startMember` and `startWithJsonSource` keep their names, parameter lists and return types.
- [ ] **Reproduction before fix.** For each of the four flakes, the commit body gives a command or a small throwaway program, not committed, that forces the suspected cause. Examples: hold the probed port from a second process between probe and bind, or bind `127.0.0.1:<port>` in a foreign process before the client calls. The body also gives that program's result against `$BASE`, where the test fails, and against this branch, where it passes. A flake the implementer cannot reproduce is reported as "cause unproven", with the mechanism that was reproduced, in the same form as task 122. It is not reported as fixed.
- [ ] `PLAN.md` follow-up (f) is not edited. The scribe records the outcome.
- [ ] **Proof run, sequential.** Five consecutive `mvn -B clean verify` runs of the full reactor on this branch's head, on a machine with no other build running, end with zero test failures and zero errors. The commit body records each run's start time, duration, `Tests run/Failures/Errors/Skipped` totals and the JDK.
- [ ] **Proof run, concurrent.** Two full `mvn -B clean verify` runs, started within 10 seconds of each other in two separate worktrees at this head with separate `target/` directories and one shared `~/.m2`, both end with zero failures and zero errors. Repeat this three times. The commit body records the six results and the host OS. The host is macOS unless D-149-B says otherwise.
- [ ] The skipped-test count in each proof run equals the count from a `$BASE` baseline run, which the commit body records.

## Out of scope
- Any change under `*/src/main/**`, including making `ClusterMembership` default to auto-increment or accept port 0. If a root cause needs a production change, stop and file a successor task (D-149-A).
- The wrong comment at `ServerStartupTest.java:224-225`, an existing PLAN follow-up, and any other `ServerStartupTest` change.
- `QuickstartSmokeIT.freePort()` in `data-prism-quickstart-extension`, unless a proof run fails there. In that case, record the failure and file a successor task.
- A shared cross-module test-support artefact or test-jar for port allocation. Fix each module in place.
- New autoconfigure test files owned by 113 or 148 (`CorrelationConfigurationTest`, `AuditOutputConfigurationTest`, `CorrelationMdcConfigurationTest`), and `PiiLogScanTest` and `AuditFilePiiScanTest` owned by 114 and 148.
- Surefire or failsafe configuration: parallelism, fork counts, reruns.
- Speeding up the suite.

## Owner decisions (decided 2026-10-07)
- **D-149-A (decided 2026-10-07).** If the only clean fix is in production code (`src/main`, POMs), the implementer stops and files a follow-up task. Owns is not widened.
- **D-149-B (decided 2026-10-07).** Proof on macOS alone is enough for this task's acceptance. CI's Linux matrix runs on the next push give the Linux evidence. That is a release-cut check in PLAN.md, not a criterion of this task.
- **D-149-C (decided 2026-10-07).** Bounded port re-allocation is allowed. At most 3 attempts, only on a bind failure of the chosen port, and never wrapped around an assertion. It is a port-selection retry, not a test rerun.
