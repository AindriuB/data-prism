# 248 — Describe each model's field protection from the scrub plan

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 240, 265 and 267 have merged
**Depends on:** 240 (`ChainableFields`), 265 (both edit `JsonTreeScrubbingEngine.java`. 265 corrects the nested-type rule, so the type an object value or array element descends into, and the `describe` override must copy that corrected rule; added 2026-10-21), 267 (both edit `JsonTreeScrubbingEngine.java`. 267 makes an array inside an array keep the field's policy only for its scalars, and sends an object inside an inner array to the unclassified-structure path, so the describer must never describe such an object's fields; added 2026-10-21)
**Wave:** 4 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/ScrubbingEngine.java *(the `describe` default method only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/ModelDescription.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/FieldProtection.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(the `describe` override only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriber.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriberTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DescriptionConformsToScrubTest.java *(new)*

## Goal
`describe_source` must tell the agent how each field is protected without showing data, and it must say what the scrubber actually does, not what the annotations alone suggest: a descriptor or profile that tightens a field must change the description. Compute the description from the same plan the scrubber applies (annotations, descriptors, profile) and prove, by a conformance test, that every path a scrubbed response can contain is described with the protection it was scrubbed under.

## Context
- docs/plan/design/catalog.md section 4: "The single source of truth", the protection vocabulary, chainable markers, value namespace, "What is never returned", the output schema and rules 1 to 4; OD-C9 (a) and OD-C10 (a); section 8 items 5 to 7.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java and SourceModels.java: how the plan is built and applied.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/EffectivePrivacyPolicy.java and PrivacyProfiles.java: profile tightening; the profile comes from the `PrivacyContext`.
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/PrivacyAction.java: seven values, three implemented.
- From 240 (2026-10-21): `ChainableField` is `io.github.aindriub.dataprism.core.engine.ChainableField`; use `ChainableFields.of(Class, Map<String, ModelDescriptor>)`.
- From 265 and 267 (2026-10-21): the nested-type rule is `nestedType` in `JsonTreeScrubbingEngine`. An array inside an array is scrubbed level by level with the field's policy for its scalars, and an object inside an inner array is always an unclassified structure. A model with a record in such a place is refused at startup with `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD`. The path grammar's single `[]` level therefore describes everything the scrubber emits.

## Required shape (later tasks read these)
- `Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean linksEnabled)` on `ScrubbingEngine`; the default returns empty, which callers treat as "cannot describe" and refuse (fail closed). `ConfiguredJsonScrubbingEngine` overrides it in 249.
- `ModelDescription(List<FieldProtection> fields, Undeclared undeclared)` with `Undeclared` one of `REFUSE`, `RENAME`, `DROP`.
- `FieldProtection(String path, Protection protection, String valueNamespace, KeyNamespace keyNamespace)`; `Protection` one of `VERBATIM`, `SYNTHETIC`, `TOKEN`, `MARKER`, `BUCKET`, `ABSENT`; `valueNamespace` only for `SYNTHETIC` and `TOKEN`; `keyNamespace` only when the field is chainable and links are enabled.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Mapping from the effective action after profile and descriptor tightening: `PASS_THROUGH` to `VERBATIM`, `SYNTHESIZE` to `SYNTHETIC`, `TOKENIZE` and `HASH` to `TOKEN`, `REDACT` to `MARKER`, `GENERALIZE` to `BUCKET`, `REMOVE` to `ABSENT`. A field the scrubber would refuse, or whose action it cannot apply, is omitted from the description and never described as `VERBATIM`.
- [ ] `ABSENT` fields are omitted, except a chainable field while `linksEnabled` is true, which is listed as `ABSENT` with its `keyNamespace` (OD-C9 (a)). With links disabled, no `keyNamespace` appears anywhere.
- [ ] `fields` is in ascending `path` order; array elements use `[]`.
- [ ] `DescriptionConformsToScrubTest`: for annotated, descriptor-tightened, profile-tightened, nested and array models, under both bundled profiles, every path present in a scrubbed tree is described with the protection it was scrubbed under, and no field described `VERBATIM` is ever changed by the scrubber. A descriptor that tightens a field changes its description. Mutant killed: describing from the annotation only.
- [ ] A description contains no value, no classification name, no model class name and no package (asserted over its `toString()` and its JSON form).
- [ ] The scrubber's output and dispositions are unchanged: every existing `data-prism-core` test passes unmodified.
- [ ] `./mvnw -B -pl data-prism-core -am verify` passes.

## Out of scope
- Configured JSON sources (249) and the `describe_source` tool (250).
- `describe_entity_model` (0.9).
- The mutation-survivor work in `JsonTreeScrubbingEngine` (a separate candidate task).

## Outcome (2026-10-23, 0.8)
`ScrubbingEngine.describe`, `ModelDescription`, `FieldProtection` (its `keyNamespace` is a validated String, to avoid a model-to-spi cycle) and `ScrubPlanDescriber`.

Tests that fail on the base: `ScrubPlanDescriberTest` and `DescriptionConformsToScrubTest` (compile failure).

Decisions. Owns widened to a public 5-argument `JsonTreeScrubbingEngine` constructor taking a `Map<String, ModelDescriptor>`, with private `tokensAvailable` and `descriptors` fields; and to `SourceTree.java` for `readsAsPlainBean` and its private helpers (`contextual`, `plainHolder`, `plainBean`) only. The describer omits any record or structure that SourceTree's READER does not write as a plain bean. That is an allowlist built from Jackson's own serializers, contextualised, through the holder property's merged view and container content serializers.

Accepted residuals. Recursive paths are omitted: the brief's line "single `[]` level describes everything the scrubber emits" must be read as excluding recursive paths. A decorator around `ValueTokenSource.unavailable()` is reported as available. `Optional` content is judged by the type-level lookup. `READER._serializationContext()` is a Jackson internal and fails closed.

Routed. `PrivacyEngineWiring` passing the descriptor map goes to task 271. The scrubber-side leak goes to task 270 and advisory GHSA-f94h-6mxv-c957 (draft; the owner accepted the exposure).

Follow-ups. `profileChangesDescription` only asserts `isNotEqualTo`. `Optional<List<Record>>`'s element path is not described.

**Review round (PR #163).** `profileChangesDescription` asserts the exact STRICT tightening of `recipientName`, and the mutant was shown failing.

**Review round 3 (PR #163).** `ValueTokenSource.available()` (default true, false for `unavailable()`) replaces the enclosing-class check and the engine uses it. Decorators of `ValueTokenSource` must delegate it.

**Review round 5 (PR #163; reviewer ids 163-1 to 163-15).** `tokens.available()` is checked on every describe, and an `available()` that throws counts as unavailable.

**Review round 6 (PR #163; reviewer ids 163-1 to 163-19).** `ValueTokenSource.available()` defaults to false (fail closed), `HmacValueTokenSource` overrides it, and CHANGELOG has a Breaking line. The describer Javadoc is rewritten, `tokensAvailable` logs the exception class on failure, and imports and field order are fixed.
