# 248 — Describe each model's field protection from the scrub plan

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 240 and 265 have merged
**Depends on:** 240 (`ChainableFields`), 265 (both edit `JsonTreeScrubbingEngine.java`. 265 corrects the nested-type rule, so the type an object value or array element descends into, and the `describe` override must copy that corrected rule; added 2026-10-21)
**Wave:** 4 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
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
