# 179 — Make `get_entity_context` `sources` order deterministic; make `ToolAdmission.none()` a singleton; fix stale MCP test comments

**Repo:** `.`
**Depends on:** none (wave 12). Starts only after the owner has answered D-179-1 and D-179-2. The default is the recommendation for each.
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextResponse.java
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/ContextResponseTest.java (new)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/characterisation/ToolResultCharacterisationTest.java
- data-prism-integration-tests/src/test/resources/characterisation/get-entity-context.text.json (only if D-179-1 is not (a))
- data-prism-integration-tests/src/test/resources/characterisation/get-entity-context.structured.json (only if D-179-1 is not (a))
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java (comments only)
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ToolAdmission.java
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/ToolAdmissionTest.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ToolOptions.java
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ToolOptionsTest.java
- CHANGELOG.md (the `[Unreleased]` section only)
- docs/tools.md
- docs/migration-0.6.md

## Goal
`get_entity_context`'s `sources` object comes out in a different order on each JVM run. The cause is `Map.copyOf` in `ContextResponse`'s compact constructor (ContextResponse.java:61). It throws away the `LinkedHashMap` order that `ContextResponse.of` builds (:94-97), and the JDK salts immutable-map iteration order for each JVM. Fix the order and pin it in the 166 golden without normalisation. In the same change, make `ToolAdmission.none()` a singleton without widening the admission API unsafely (the 155 follow-up). Fix two stale test comments.

## Context
- ContextResponse.java:51-100: the record, the compact constructor and `of(...)`. Every constructor goes through the compact constructor, so the order is fixed there and holds on every path. `answered()` (:118) inherits the order.
- SourceAliasing.java (orchestration): keys are scope-local HMAC aliases unless the caller holds `EXPOSE_SOURCE_NAMES`. An alias must mean nothing outside its scope (docs/pack.md §32, docs/design-review.md §E). This is why declared order is the wrong choice (see D-179-1).
- SourceFanOut.java:70: outcomes come back "in the order the adapters were given".
- ToolResultCharacterisationTest.java:50-53 and :100-113: the Javadoc paragraph on normalisation, the stale "whatever Jackson 2 does" sentence, and `sortSourcesObject`, used at :153 and :159. The stored goldens are already in key-sorted order.
- DataPrismMcpServerTest.java:121-155: `serverUsesTheSharedMapper` and `serverMapper`, which read the private fields `McpSyncServer.asyncServer` and `<async server>.jsonMapper`. The SDK version is `mcp.version` in pom.xml:72, currently 2.0.1.
- ToolAdmission.java:43-50: `none()` builds a fresh instance on every call.
- ToolOptions.java:9-44 and :65-76: `NO_ADMISSION` is recognised by identity. Its Javadoc explains why a fresh `none()` forced a separate `noAdmission()` entry point.
- ToolOptionsTest.java:40-51: uses `ToolAdmission.none()` as a stand-in "real" admission. Under D-179-2 (b) this test must switch to a genuinely real admission.
- docs/plan/tasks/retired/155-mcp-options-records-replace-overloads.md: D-0.6-6 says no default admission, and every caller names one.
- CHANGELOG.md:132 is `[Unreleased]` → `### Changed`. docs/migration-0.6.md:326 is "Building a tool or a server: options records replace overloads".

## Decisions (owner)
**D-179-1: what order `sources` uses.** The change is visible to every MCP client and model, so it is the owner's call.
- (a) **Sorted by the emitted key** (`String` natural order, which is the alias, or the real name when exposed). It is stable across runs and scopes. Position carries no information that the key does not already carry. The existing goldens stay byte-identical. **Recommended.**
- (b) Declared adapter order (fan-out order). This order means something to a reader who knows the configuration. Under aliasing, though, position *k* is the same system in every scope. That links aliases across scopes and defeats `SourceAliasing`'s guarantee. Rejected on privacy grounds.
- (c) Declared order only when real names are exposed, sorted when aliased. This gives two behaviours to document and test for little gain.

**D-179-2: the shape of `ToolAdmission.none()`.**
- (a) No API change. Close the follow-up by documenting on `none()` that each call returns a new instance and that only `Builder.noAdmission()` is recognised.
- (b) **`none()` returns one `private static final` instance. `ToolOptions` recognises that instance by identity (its `NO_ADMISSION` becomes `ToolAdmission.none()`), and no `isNone()` is added. Recommended.** As a result, `.admission(ToolAdmission.none(), null)` is accepted. This does not breach D-0.6-6, because the caller still names no-admission explicitly. A subclass or any other instance can never match by identity, so a real policy still needs a fingerprinter.
- (c) (b) plus `public final boolean isNone()`. This adds a public predicate that nothing needs, and it invites callers to branch on admission state. If chosen, it must be `final` and identity-based.

## Decisions made (owner)
- **D-179-1 = (a):** `sources` is sorted by the emitted key. Goldens stay byte-identical; the sort normalisation in `ToolResultCharacterisationTest` is removed.
- **D-179-2 = (b):** `ToolAdmission.none()` returns one shared instance, recognised by identity in `ToolOptions`; no `isNone()`. `.admission(ToolAdmission.none(), null)` is accepted; real policies and subclasses still require a fingerprinter.

## Acceptance
Sources order (per D-179-1 (a); adjust the wording if the owner chooses otherwise):
- [ ] `ContextResponseTest` builds a `ContextResponse` from a `LinkedHashMap` of at least 8 source keys inserted in reverse-sorted order. It asserts that `sources().keySet()` iterates in ascending `String` order and that `answered()` follows the same order. This test fails on the pre-change `ContextResponse`. Show this by committing the test first, or with a temporary WIP commit, never with a bare `git stash`, and quote the failure in the hand-back.
- [ ] `ContextResponseTest` asserts that `sources()` is unmodifiable (`put` throws `UnsupportedOperationException`) and that a null key or a null value still throws `NullPointerException`, as `Map.copyOf` did.
- [ ] `grep -n "sortSourcesObject\|normalisation\|Jackson 2" data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/characterisation/ToolResultCharacterisationTest.java` prints nothing. The Javadoc instead says that the `sources` entries are pinned in the order chosen by D-179-1.
- [ ] Under (a), `git diff --stat -- data-prism-integration-tests/src/test/resources/characterisation/` is empty.
- [ ] `mvn -q -pl data-prism-integration-tests -am test -Dtest=ToolResultCharacterisationTest -Dsurefire.failIfNoSpecifiedTests=false` passes in 3 separate invocations (3 JVMs).
- [ ] `ContextResponse`'s Javadoc states the `sources` order and the reason it is not declared order (one or two sentences).
- [ ] docs/tools.md states the `sources` order guarantee in one sentence where the `get_entity_context` response is described.
- [ ] CHANGELOG.md `[Unreleased]` → `### Changed` has one entry: `get_entity_context`'s `sources` entries are now in a fixed order (state it), where before the order varied between runs. docs/migration-0.6.md has one sentence on the same point, in the most fitting existing section; do not create a new top-level section.

`ToolAdmission` (per D-179-2 (b); adjust if the owner chooses otherwise):
- [ ] `ToolAdmissionTest` asserts `ToolAdmission.none()` `isSameAs` `ToolAdmission.none()`, and that it still admits every call (the existing :202 assertion stays).
- [ ] `ToolOptionsTest` asserts that `ToolOptions.defaults().admission(ToolAdmission.none(), null).build()` succeeds with a null `fingerprinter()`. It asserts that a real admission without a fingerprinter is still refused with a message containing `fingerprinter`, on both the builder path and the canonical constructor. A real admission here means `new ToolAdmission(...)` with an `OversightPolicy` that requires approval for a tool. It also asserts the same refusal for an anonymous subclass of `ToolAdmission`.
- [ ] `grep -n "isNone" data-prism-security data-prism-mcp -r --include='*.java'` prints nothing under (b).
- [ ] `ToolOptions`' class Javadoc no longer says that `none()` builds a fresh instance on each call, and states the identity rule.
- [ ] CHANGELOG.md `[Unreleased]` → `### Changed` has one entry: `ToolAdmission.none()` returns a shared instance and is accepted without a fingerprinter. docs/migration-0.6.md:326's section mentions it in one sentence.

Stale comments:
- [ ] `serverMapper` in DataPrismMcpServerTest.java carries a comment naming the MCP Java SDK version whose private fields it reads (`mcp.version` 2.0.1), and the two field names. The comment says the test must be revisited when `mcp.version` changes. No assertion changes.

Migration-page counts (part of follow-up 6; 181 owns the rest of `docs/`):
- [ ] Each count claim in docs/migration-0.6.md (:57 "Six readers", :405 "Five configuration classes", :479 "the six YAML readers") has been checked against the code. Each is either corrected or confirmed in the hand-back, with the source-of-truth `file:line`.

Build:
- [ ] `mvn -q -pl data-prism-orchestration,data-prism-security,data-prism-mcp,data-prism-integration-tests -am verify` passes.

## Out of scope
- The order of `fieldsByNamespace` (`@JsonIgnore`, never emitted) and the order of sources inside findings (`agreementGroups`). Report either if you see it vary, but do not change it.
- The audit event's `sources` set.
- Making `ToolAdmission` `final`, or removing or deprecating `none()` or `Builder.noAdmission()`.
- Any other file under `docs/` and README.md. Task 181 owns them.
- Version bump, tag, release workflow and publishing (paused by the owner).
