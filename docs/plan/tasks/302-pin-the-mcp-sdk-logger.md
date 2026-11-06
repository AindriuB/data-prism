# 302 — Pin the MCP SDK's logger so client arguments never reach logs

**Repo:** `.`
**Release:** 0.8.0. **Release obligation:** must land before the 0.8.0 cut.
**Base branch:** `origin/main` once 254 has merged
**Depends on:** none
**Decisions:** filed 2026-11-06; the decisions below are made here.
**Wave:** 11. **Serialised with:** 254 (wave 10) and 255 (wave 12) on `docs/configuration.md`, by wave order rather than a dependency.
**Owns:**
- data-prism-integration-tests/src/main/resources/simplelogger.properties
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioSdkLogPinTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/McpSdkLogPin.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/spring.factories *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/McpSdkLogPinTest.java *(new)*
- docs/configuration.md *(one new `### Logging` section only)*

## Goal
The MCP SDK logs the raw JSON-RPC message at DEBUG, including client-supplied identifiers such as `subjectId`, which breaks the `docs/conventions.md` rule on sensitive values in logs. Nothing pins these loggers, so turning on root DEBUG leaks them. Pin them on every transport Data Prism ships.

## What the SDK does (mcp-core 2.0.1, read from the bytecode)
| Logger | Line | Level | Reached by |
|---|---|---|---|
| `...server.transport.StdioServerTransportProvider` | `Received JSON message: {}` | DEBUG | stdio |
| `...spec.McpServerSession` | `Received request: {}`, `Received notification: {}` | DEBUG | stdio |
| `...spec.McpSchema` | `Received JSON message: {}` (`deserializeJsonRpcMessage`) | DEBUG | **stdio and streamable HTTP** |
| `...spec.McpStreamableServerSession` | `Received response: {}` | DEBUG | streamable HTTP |

So streamable HTTP, the production transport, has the same leak.

## Decisions
- **Pin `io.modelcontextprotocol` to WARN.** The one prefix covers every row above and any line a later SDK adds. WARN rather than INFO also keeps `Client initialize request ...` out of the log, which carries client-supplied `clientInfo`; nothing depends on that line.
- **Plain-Java stdio needs its own pin.** Spring refuses stdio (`STDIO_TRANSPORT_UNSUPPORTED`), so the only shipped stdio path is `ExampleApplication` on slf4j-simple: `org.slf4j.simpleLogger.log.io.modelcontextprotocol=warn` in `simplelogger.properties`. A named logger beats `defaultLogLevel`.
- **Spring needs a pin for HTTP, and `application.yaml` cannot carry it.** `data-prism-server`'s file never reaches a starter application. `McpSdkLogPin`, an `EnvironmentPostProcessor` registered in `spring.factories` (the Boot 4.1 key), adds `logging.level.io.modelcontextprotocol=WARN` as the lowest-precedence property source. This covers the server, the images and every starter application, and a custom `logback-spring.xml` does not undo it.
- **An operator who names the logger explicitly still wins.** Nothing in a library can stop that, so `docs/configuration.md` says it is unsupported in production, because it logs client arguments.

## Context
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java — the stdio launcher. Stdout is the protocol; diagnostics go to stderr.
- data-prism-integration-tests/pom.xml:46-56 — this module runs slf4j-simple, not logback, so `logging.level.*` has no effect here. That is why the Spring test lives in autoconfigure.
- docs/conventions.md — "No sensitive value in a log statement"; leak tests are shown non-vacuous by a mutation.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `StdioSdkLogPinTest` starts `ExampleApplication` in a child JVM with `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`, sends `initialize` and a `tools/call` carrying a synthetic sentinel `subjectId` on stdin, waits for the response, and finds the sentinel nowhere in stderr.
- [ ] Same test, positive control: adding `-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug` puts the sentinel in stderr, so the scan is not vacuous.
- [ ] `McpSdkLogPinTest` boots a `SpringApplication` under logback with `logging.level.root=DEBUG`: `McpSchema`, `McpServerSession` and `McpStreamableServerSession` are not DEBUG- or INFO-enabled, and `McpSchema.deserializeJsonRpcMessage` on a sentinel message puts nothing in the captured output.
- [ ] Same test, positive control: with `logging.level.io.modelcontextprotocol=DEBUG` the sentinel is captured, which also proves the explicit override wins.
- [ ] The Outcome reports one measured row: a malformed line carrying the sentinel on stdio, and on HTTP through `Failed to deserialize message: {}`. Both log at ERROR, above any pin here, and a parser message can quote a token. If the sentinel appears, report it for a coordinator decision; do not silence those lines in this task.
- [ ] `docs/configuration.md` has a `### Logging` section naming the pin, the WARN level, both mechanisms, the explicit-override warning, and that a plain-Java embedder of `DataPrismMcpServer` on its own SLF4J binding must set the same level itself.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `load-test/src/main/resources/simplelogger.properties`: a client driver, not a server.
- Data Prism's own log statements, and `PiiLogScanTest` (255 extends it).
- Upgrading or patching the SDK, or wrapping its transports.
- `CHANGELOG.md` and `docs/migration-0.8.md` (261; the Outcome hands over the line).
