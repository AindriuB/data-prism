# 289 — Describe source models by the walker's rules, and delete the describe-side Jackson proofs

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/wave/0.8-wave4` once 288 has merged into it. One PR for this task, targeting `wave/0.8-wave4`. PR #163 does not merge until 210, 288, 289 and 290 have merged into it (owner decision 3, 2026-10-27).
**Depends on:** 288 (the walker makes the scrubber ignore Jackson annotations, which is what lets the describer ignore them; both edit `SourceTree.java`, `ScrubPlanDescriberTest.java` and `DescriptionConformsToScrubTest.java`)
**Wave:** 6 (filed 2026-10-27; the brief's proposal of wave 5 alongside 288 does not hold, because describing by the new rules before the scrubber follows them would describe fields the scrubber still writes differently)
**Decisions:** owner decision 1, 2026-10-27: describe and scrub follow one plan. 270 (retired) had the describer decide "ambiguous" through a shared serializer check; that is superseded.

**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriber.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java *(deleting `readsAsPlainBean`, `readsAsPlainScalar`, `overridesSerialization`, `jacksonScalar`, `fromJacksonDatabind`, `overrides`, `contextual`, `plainHolder` and `plainBean` (`:263-459` on wave4), and the imports only they use; nothing else)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriberTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DescriptionConformsToScrubTest.java

## Goal
248's describer has only declared types, and Jackson could write a declared record as a scalar, an array or anything else, so it carries an "ambiguous shape" branch that asks Jackson's serializers how a type will be written and omits the field when the answer is unclear. After 288 the scrubber's tree no longer depends on Jackson: a declared record is always an object of its components, and a declared scalar is always a scalar. Remove the branch and the proofs behind it, so that describe and scrub follow one plan and there is no second copy of the rules to drift.

## Context
Line numbers are as on `origin/wave/0.8-wave4` (de5cf9dc); re-check them on the base commit.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriber.java:43-59 (the class Javadoc on the ambiguous reading), :290-322 (`shapeOf`), :324-336 (`overridden` and `plain`, the call sites of `SourceTree.overridesSerialization` at :330 and `readsAsPlainBean` at :335), :311 (`readsAsPlainScalar`), :348 onwards (`mayBeScalar` and `supertypes`, which read `@JsonValue` and `@JsonSerialize`).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java:263-459: the describe-only methods. After this task, nothing in `data-prism-core` main code asks Jackson for a serializer except `SourceTree`'s leaf writing and `refuseIfReadByGetters`.
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriberTest.java: 18 references to the deleted methods (`:667-716` and around), each with a fixture record carrying a Jackson annotation.
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DescriptionConformsToScrubTest.java:136, :241-335 (`ambiguousShapes`, `ambiguousSources`, `ambiguousShapesRedacting`, `ambiguousShapesBundled`) and the fixtures at :351-401 (`@JsonSerialize`, `@JsonValue`, `@JsonTypeInfo`).
- The rules that stay: an `Object`, raw collection or `JsonNode` position is still dynamic (the scrubber reads it by the value it meets); an array inside an array is still dynamic (267); `byte[]` and `char[]` are scalars.
- 288's "The walker" section: the rules this task's describer must agree with.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `ScrubPlanDescriber` makes no call into `SourceTree` and imports nothing from `com.fasterxml.jackson.annotation` or `tools.jackson.databind`. `mayBeScalar`, `overridden`, `plain`, `supertypes` and the `OMIT` outcome for a declared record or user type are gone. A field whose declared type is a record is described through the record's components; a declared scalar or enum is described as a scalar.
- [ ] The nine `SourceTree` methods listed under Owns are deleted, and no test refers to them.
- [ ] **Conformance.** `DescriptionConformsToScrubTest`'s ambiguous-shape cases become conformance cases. For each fixture that carries a Jackson annotation (every one at :351-401, and a record with `@JsonValue` at a declared position), under both bundled profiles, every path in the scrubbed tree is described with the protection it was scrubbed under, and the description equals that of the same model without the annotation. The cases that stay dynamic (`Object`, raw collection, `JsonNode`, an array inside an array) keep their current assertions. Every other existing case passes unchanged.
- [ ] **Repros described.** `DescriptionConformsToScrubTest` gains 270's repros 1 to 4 and 8 (a) to (d) as models (`docs/plan/tasks/retired/270-classified-records-must-serialise-as-objects.md`, "The gap"). Each email field is described with the protection of the record's own classification (`MARKER` under DEFAULT), never `VERBATIM`, and conforms to the scrub.
- [ ] **Mutants, each recorded in the Outcome with the test that fails:** restoring the `@JsonValue` scan in `mayBeScalar` (a repro is then omitted where the scrub emits it); describing a declared record as `DYNAMIC`.
- [ ] `ScrubPlanDescriberTest`'s 18 references are replaced by cases that assert the description of the same fixtures under the new rule, or deleted where the fixture tested only the removed check; the Outcome lists which.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-orchestration,data-prism-mcp -am verify` passes.

## Out of scope
- The walker, `SourceModels` and `SourceValues` (288).
- The startup annotation refusal, docs, `CHANGELOG.md` and the contract register (290).
- `JsonTreeScrubbingEngine.describe` and `ChainableFields` (unchanged; 271 edits the engine in wave 5).
- Configured JSON sources' description (249).
