# 260 — Add the freeze tests that need no decision (register successor S4)

**Repo:** `.`
**Release:** 0.8.0 (must be in place before the 0.9.0 freeze)
**Base branch:** the 0.8 integration branch once every 0.8 surface task has merged
**Depends on:** 245, 250, 253, 255 (the 0.8 core surface), 221 (the `.s1` map names and interface codes), 227 (the member-card map and `CLUSTER_KEY_MISMATCH`), 284 and 287 (the trusted-network properties and the codes `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK` and `INVALID_TRUSTED_RANGE`), 286 (the `clusterSecurity` health contributor), 256 (may add a public accessor to `SensitiveDataScanner` in the `validation` package, which `freeze/spi-signatures.txt` pins)
**Wave:** 12 (re-planned 2026-10-23 for owner decision (b): 230 and 264 deferred past 1.0, 284, 286 and 287 added)
**Owns:**
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/freeze/** *(new)*
- data-prism-architecture/src/test/resources/freeze/** *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ToolsListGoldenTest.java *(new)*
- data-prism-mcp/src/test/resources/io/github/aindriub/dataprism/mcp/golden/tools-list.json *(new)*

Re-wired 2026-10-23 for owner decision (b): 230 and 264 are deferred past 1.0, so the edges to them are removed. `freeze/map-names.txt` pins the map names without `dataprism.linkattempt.s1`, which 1.0 does not add (264's fallback (c)).

## Goal
The public surface freezes at 0.9.0, and most of it is not pinned today (register section 15). Add the pins that need no further decision: the set of codes extracted from the code, the closed vocabularies whose constants leave the process, a golden `tools/list`, the Hazelcast map names, the operator endpoints and a signature snapshot of the SPI packages. Each fails when a name is added, removed or renamed without the checked-in list changing in the same commit.

## Context
- docs/plan/design/contract-register.md section 15 (the freeze checklist, "Must be pinned by" and "Gap" columns) and the extraction commands E1, E1c, E2 and E1r in its Outcome; section 7.4 (the 25 closed vocabularies, five with untraced exits).
- docs/plan/PLAN.md, "Contract register successor tasks", S4: the frozen list must classify the five enums whose exits were not traced.
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PropertyNamesFrozenTest.java: the `-Dfrozen.write=<path>` regeneration pattern to follow.
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ModuleGraph.java: how the architecture module reads every module's classes.

## Acceptance
- [ ] Each test is shown to fail once, in a recorded run, by adding a made-up name to the code without updating its list.
- [ ] Code extraction: one test extracts string-literal codes, message-prefix codes (`"CODE: …"`), `Code`-style enum constants from every module's `src/main`, and code-shaped tokens from `src/main/resources` (E1, E1c, E2, E1r), checks each against `RefusalCodes`' format, and compares the set, by surface (call, admission, re-identification, operator, startup, violation, audit runtime), with `freeze/codes.txt`.
- [ ] Closed vocabularies: every enum of any visibility whose `name()` reaches a response, an audit record, a metric or a log line is listed in `freeze/enums.txt` with its constants and whether it leaves the process; the five enums the register could not trace are traced and classified, and the Outcome says where each exit is. A new enum or constant fails the test until classified.
- [ ] `ToolsListGoldenTest` builds the MCP server with links enabled and compares `tools/list` (names, `inputSchema`, any `outputSchema`) byte for byte with `golden/tools-list.json`; the `follow` `maxLength` is the configured default.
- [ ] Hazelcast map names (read from the cluster module's constants by reflection from the architecture module) match `freeze/map-names.txt`.
- [ ] Operator endpoints (every request mapping in `data-prism-server`'s operator controllers, with method and path) match `freeze/operator-endpoints.txt`.
- [ ] A signature snapshot of the public types in `core.spi`, `validation`, `security` and the `audit` contract package matches `freeze/spi-signatures.txt`.
- [ ] Every list can be regenerated with `-Dfreeze.write=<path>`, as `PropertyNamesFrozenTest` does; regenerating on an unchanged tree gives no diff.
- [ ] No file outside the owned paths changes.
- [ ] `./mvnw -B -pl data-prism-architecture,data-prism-mcp -am verify` passes, then `./mvnw -B verify`.

## Out of scope
- Documenting the codes (261) or deciding anything the register left open.
- Pinning configuration keys (`PropertyNamesFrozenTest` already does).
- The 0.9 surface (batch, bulk, `describe_entity_model`).
