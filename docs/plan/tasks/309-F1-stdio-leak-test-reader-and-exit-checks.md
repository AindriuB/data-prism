# 309-F1 — Stdio leak tests: require end of stream, and wait for the child's own exit

**Repo:** .
**Depends on:** none
**Priority:** wanted before the 0.8.0 cut. These tests guard 304's guarantee that no client content reaches an SDK log line; both findings let a `doesNotContain(SENTINEL)` check pass on output that was never read.
**Decisions:** one, taken here: the stdio example launcher exits on stdin EOF (see Context). The module is not published (`data-prism-integration-tests/pom.xml:27-28`).
**Owns:**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioParseErrorLeakTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioSdkLogPinTest.java
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java

## Goal
Close the owner's PR #249 review Lows 249-1 and 249-3. A reader thread that dies on an unchecked exception or an `Error` must fail the test (today only an `IOException` does). Every leak check must also see everything the child writes after stdin closes: close stdin, wait for the child to exit on its own, and use `destroy` only as cleanup.

## Context
- 249-1: `StdioParseErrorLeakTest.java:190-224` and `StdioSdkLogPinTest.java:125-152`. `drain` catches only `IOException`, and `joinToEndOfStream` takes "thread not alive and no `IOException`" as "fully read". A `RuntimeException` or `Error` in a reader ends the thread silently, so a leak check passes on partial output.
- 249-3: `StdioParseErrorLeakTest.java:166-172` and `StdioSdkLogPinTest.java:97-103` call `in.close()` and then `toHandle().destroy()` straight away. Anything the child writes while handling EOF or shutting down can be cut off and is never checked.
- Why the launcher changes: `ExampleApplication.main` (`:65-73`) ends in `Thread.currentThread().join()`, so the child never exits on stdin EOF. A test-only `waitFor` would always time out. The MCP stdio shutdown sequence is: the client closes stdin, waits for the server to exit, and sends SIGTERM only if it does not. The launcher should follow it. Only these two leak test classes start `ExampleApplication` as a process. `StdioProductionProfileTest` and `StdioCatalogToolsTest` call `buildServer` and do not depend on `main`. `ServerPackagingIT:124` matches the class name only.
- `DataPrismMcpServer.java:164` builds the `StdioInboundGuard` on `System.in`. `StdioInboundGuard.java:129-138` treats a read fault as EOF and calls `finish()`.
- `docs/plan/tasks/retired/309-stdio-leak-test-teardown-fails-open.md` has the current teardown, why it uses `toHandle().destroy()` (JDK 25 `ProcessImpl.destroy` closes the parent's stderr), and how the evidence was recorded in PR #249.

## Acceptance
- [ ] AC1 Each class's `drain` records that `readLine` returned `null` (end of stream). It also records any `Throwable` the reader ends on, not only an `IOException`. `joinToEndOfStream` fails the test unless both readers reached end of stream with no recorded throwable. A recorded throwable is attached as the `AssertionError` cause.
- [ ] AC2 In every place that tears down a child (`StdioParseErrorLeakTest.run` and `StdioSdkLogPinTest.runChild`), the normal path closes stdin and then calls `process.waitFor(10, SECONDS)` before any `destroy`. The test fails if the child has not exited by then, or if it exits with a status other than 0. The `finally` block still does `toHandle().destroy()` and the `destroyForcibly` fallback, but only for cleanup. Reaching it on the normal path means the test has already failed.
- [ ] AC3 When stdin reaches EOF, `ExampleApplication` closes the server gracefully and the JVM exits with status 0. It does not wait for a signal. No file under `data-prism-mcp/` changes. If the exit cannot be done from the launcher alone, stop and report: changing the published module is a separate decision.
- [ ] AC4 Mutation evidence for 249-1, in the PR body: a temporary change makes the stderr reader throw an unchecked exception after its first line. With it, a `StdioParseErrorLeakTest` case fails with the new end-of-stream message, and the same case passes with the mutation on `origin/main`'s test code. Revert the change before commit.
- [ ] AC5 Mutation evidence for 249-3, in the PR body: a temporary launcher change writes `SENTINEL` to stderr after stdin EOF, about 200 ms before it exits. With it, every `StdioParseErrorLeakTest` case and `StdioSdkLogPinTest.pinKeepsClientArgumentsOutOfStderr` fails. Revert it before commit.
- [ ] AC6 No assertion is weakened. No retry is added. No sleep stands in for a condition (the existing measurement sleep in `StdioSdkLogPinTest.runChild` stays as it is). No timeout is added or raised: the 10 s `waitFor` and the 5000 ms reader joins are the existing values.
- [ ] AC7 Both classes pass in full, 20 consecutive runs pass, and only the owned files change.

## Checks
- `stdio-classes` test — AC1, AC2, AC3, AC7
  run: `./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest='StdioParseErrorLeakTest,StdioSdkLogPinTest' -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*Stdio*.xml`
  min-passing: 11  max-skipped: 0
- `class-repeat` command — AC7
  run: `for i in $(seq 20); do ./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest='StdioParseErrorLeakTest,StdioSdkLogPinTest' -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done`
- `no-core-change` command — AC3
  run: `git diff --exit-code origin/main...HEAD -- data-prism-mcp`
- `scope` command — AC7
  run: `git diff --name-only origin/main...HEAD | grep -vE 'example/(StdioParseErrorLeakTest|StdioSdkLogPinTest|ExampleApplication)\.java$' | wc -l | grep -qx 0`
- `fail-closed-reader-and-exit` read — AC1, AC2, AC4, AC5, AC6
  how: reviewer reads the diff and the PR body's two mutation runs. Both readers must reach end of stream or the test fails. The normal path waits for the child's own exit with status 0 before any signal. No assertion is loosened and no timeout is raised.

## Out of scope
- 249-2 (a recorded run of the old `destroy()` failing with the new message) and 249-5 (extracting `drain` and `joinToEndOfStream` into a shared helper). Both are separate follow-ups in PLAN.md.
- Anything under `data-prism-mcp/`, including how `StdioInboundGuard` or the SDK transport handles EOF.
- Other stdio tests, the HTTP leak tests (305), `known-flakes.md`, and running two `mvn` processes at once in one worktree.
