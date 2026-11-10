# 260 — Add the freeze tests that need no decision (register successor S4)

**Repo:** `.`
**Release:** 0.8.0 (must be in place before the 0.9.0 freeze)
**Base branch:** the 0.8 integration branch once every 0.8 surface task has merged
**Depends on:** 245, 250, 253, 255 (the 0.8 core surface), 221 (the `.s1` map names and interface codes), 227 (the member-card map and `CLUSTER_KEY_MISMATCH`), 284 and 287 (the trusted-network properties and the codes `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK` and `INVALID_TRUSTED_RANGE`), 286 (the `clusterSecurity` health contributor), 256 (may add a public accessor to `SensitiveDataScanner` in the `validation` package, which `freeze/spi-signatures.txt` pins)
**Wave:** 13 (moved from 12 on 2026-11-06; re-planned 2026-10-23 for owner decision (b): 230 and 264 deferred past 1.0, 284, 286 and 287 added)
**Owns:**
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/freeze/** *(new)*
- data-prism-architecture/src/test/resources/freeze/** *(new; includes `single-word-codes.txt`, amendment 2026-11-10)*
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
- [ ] A signature snapshot of every extension interface and annotation listed in contract-register section 7.1, with the list read from 7.1, matches `freeze/spi-signatures.txt`; annotations are pinned with their attributes, defaults included (amendment 2026-11-10, decision 2).
- [ ] Single-word codes: `freeze/single-word-codes.txt` lists `UNAUTHENTICATED`, `FORBIDDEN`, `PAUSED`, `RESUMED`, `APPROVED`, `REJECTED` and `RESOLVED`. A test checks both directions: every listed word appears as a string literal in some module's `src/main`, and every single-word upper-case literal returned as a code or status by `data-prism-server`'s operator controllers and operator error advice is on the list. It is shown to fail, in a recorded run, by renaming one listed word (amendment 2026-11-10, decision 1).
- [ ] Every list can be regenerated with `-Dfreeze.write=<path>`, as `PropertyNamesFrozenTest` does; regenerating on an unchanged tree gives no diff.
- [ ] No file outside the owned paths changes.
- [ ] `./mvnw -B -pl data-prism-architecture,data-prism-mcp -am verify` passes, then `./mvnw -B verify`.

## Out of scope
- Documenting the codes (261) or deciding anything the register left open.
- Pinning configuration keys (`PropertyNamesFrozenTest` already does).
- The 0.9 surface (batch, bulk, `describe_entity_model`).

## Note from the wave-7 record (2026-11-04)
When this task lists the sources of `PrivacyClusterRefusal` codes, include task 228's insertion, `CLUSTER_SETTINGS_MISMATCH` (merged in PR #191), alongside 227's and 287's.

## Amendment 2026-11-10 (owner, after review round 1)
Owns and Acceptance above are changed in place.

**Decision 1 — single-word codes get a hand-kept list.** `CodeSetFreezeTest` follows the register's E1 rule (contract-register.md:230), which matches only literals containing an underscore, so the operator error codes `UNAUTHENTICATED` and `FORBIDDEN` (contract-register.md:380-381) and the operator status values `PAUSED`, `RESUMED`, `APPROVED`, `REJECTED` and `RESOLVED` (:382-386) are not frozen; renaming `FORBIDDEN` at ServerSecurityConfiguration.java:90 fails no test. Add `freeze/single-word-codes.txt` (already under the `freeze/**` Owns glob) listing those seven words, checked in both directions as the Acceptance row states. Reason: extending E1 to every upper-case literal would sweep in non-codes and make the frozen set noisy.

**Decision 2 — widen the SPI signature snapshot.** `SpiSignaturesFreezeTest` covers only `core.spi`, `validation`, `security` and the `audit` contract package. That narrowing came from this task's own Acceptance line and overrides nothing recorded. Register 7.1 (contract-register.md:1072, :1554) says the freeze pins the method sets of every registered extension interface and annotation; about 18 of the 30 are unpinned, including `ScopeBudget`, `PrivacyMetrics`, `PrivacyPolicyResolver`, `ApprovalStore`, `OversightState`, `CallerRateLimiter`, `ContextOrchestrator`, `Vocabulary`, `SubjectLookup`, `OperatorCallers`, `HmacKeyReferenceResolver` and the six annotations. The snapshot covers every extension type and annotation 7.1 lists, read from 7.1; annotations are pinned with their attributes, defaults included. Reason: the public surface freezes at 0.9.

## Review round 1 — findings
Reviewed HEAD 8e8dd24376d4f5df1eeffec4692093ea24f974ba (full branch).

- [P2] CodeSetFreezeTest / freeze lists — implement Decision 1 (single-word codes list, both-way check). route: implementer.
- [P2] SpiSignaturesFreezeTest.java:28 — implement Decision 2 (widen to every register 7.1 extension type and annotation). route: implementer.

### Follow-ups (not this round)
- P3 OperatorEndpointsFreezeTest.java:21-25,45: the source-regex scan keeps only the first path of a multi-path mapping, records a constant path as empty, and mistakes Javadoc text like "class <word>" before a class-level `@RequestMapping` for a method mapping. None of these occurs today.
- P3 CodeSetFreezeTest.java:47: the E2 pattern misses `enum Code implements X {`.
- NOTE: unused imports in MapNamesFreezeTest.java:5,8; EnumVocabularyFreezeTest.java:76 duplicates `MainSources.mainClasses()`; ToolsListGoldenTest.java:80 checks against `ListSourcesTool.DEFAULT_PAGE_MAX_LENGTH`, not the `LinksProperties` default.

## Review round 2 — findings

Reviewed the fix since 1428a930, at HEAD 245026c5ac7e9030519e9223282012a4c33f7dec.

- [P2] data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/freeze/SingleWordCodesFreezeTest.java:31-33,53-58 — the "returned as a code" direction only recognises three source shapes (`"status", "X"`, `codeOnly(..., "X")`, and `case ... -> "X"` in files whose path contains `Error`). The operator controllers' main way of returning a code is `new OperatorException(HttpStatus.X, "CODE")` (OversightOperatorController.java:401, ReidentificationOperatorController.java:85/101), and a `Map.of("code", "CODE")` body is another. Neither is seen, so the test does not cover what the Acceptance row asks: every single-word upper-case literal the operator controllers and operator error advice return as a code.
  route: implementer — add the `OperatorException(<status>, "X")` and `"code", "X"` shapes to the scan (or scan every single-word upper-case string literal in the operator controllers and error advice, with a small reviewed exclusion list for non-codes); keep Decision 1 and the Acceptance row as written.
  input: add `throw new OperatorException(HttpStatus.CONFLICT, "CONFLICT");` to OversightOperatorController, or change ReidentificationOperatorController.java:113 to `Map.of("code", "GONE")` → the new single-word code ships and SingleWordCodesFreezeTest still passes. Show this failing once, then revert.
- [STYLE] data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/freeze/SpiSignaturesFreezeTest.java:7-10,140-143 — new imports are out of order (`Retention`, `Target`, `Annotation`, then `java.nio.file.Files` before `java.lang.reflect.Constructor`), and `defaultOf` writes `java.lang.reflect.Array` in full while the file imports the other `java.lang.reflect` classes.
  route: implementer
  rule: the file's own sorted import block at SpiSignaturesFreezeTest.java:11-23.

## Review round 3 — findings

Reviewed the fix since ec04373c, at HEAD 1770c660df6de4f7c5d4ab98522024a83cd06cb6. Last round allowed for this branch.

- [P2] data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/freeze/SingleWordCodesFreezeTest.java:31-36,56-64 — the "returned as a code" direction still misses two shapes already on the operator surface, so it does not cover the operator error advice the Acceptance row names: (a) OperatorErrorAdvice returns codes through `body(HttpStatus.X, "CODE")` (OperatorErrorAdvice.java:36-56, helper :61-62); (b) OversightOperatorController assigns the code to a variable in a switch, `case X -> { code = "..."; status = ...; }` (:357-361), then throws `new OperatorException(status, code)` (:363). The Javadoc at :21-25 claims the five shapes are the ones "the server uses", which (a) and (b) disprove.
  route: implementer — stop matching shapes. Scan every single-word upper-case string literal in the operator controllers, the operator error advice and ServerSecurityConfiguration, against the list plus a small reviewed exclusion list for literals that are not codes (each exclusion with a one-line reason in the test). Correct the Javadoc. Decision 1 and the Acceptance row stay as written.
  input: add `return body(HttpStatus.CONFLICT, "CONFLICT");` in a new OperatorErrorAdvice handler, or change OversightOperatorController.java:360 to `case SELF_APPROVAL -> { code = "CONFLICT"; status = HttpStatus.CONFLICT; }` → the unlisted code ships and the test passes. Show both failing once, then revert.

## Attempt 1 — failed

/verify at 455be74ef8a6 (2026-11-10): reviewer 10/11 with AC11 pending on the full verify; tester FAIL. `./mvnw -B verify` (no filter) stopped at data-prism-architecture: 124 tests, 1 error, reproducible alone (not a flake).

- SpiSignaturesFreezeTest.theSpiSignaturesMatchTheFrozenSnapshot: `ArchUnitException$ReflectionException: java.lang.ClassNotFoundException: io.github.aindriub.dataprism.server.operator.OperatorCallers` at SpiSignaturesFreezeTest.java:59 (`describe(type.reflect(), actual)`). OperatorCallers is a register 7.1 type in data-prism-server. Under `verify` the server module is repackaged as a Spring Boot jar (classes under BOOT-INF/classes), so the class is not loadable from the architecture module's test classpath (server is test-scoped at data-prism-architecture/pom.xml:44). This is the same cause round 0 met in OperatorEndpointsFreezeTest, which now reads source. The `-am test` runs used in review rounds did not package, so they did not see it.
Next attempt: make the SPI snapshot describe register 7.1 types without `Class.forName`/`reflect()` for types that live in a repackaged module (for example describe them from ArchUnit's `JavaClass` model, or from source as OperatorEndpointsFreezeTest does), keep the snapshot content identical, and prove it with a full `./mvnw -B verify`, not `-am test`.
