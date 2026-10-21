# 240 — Declare chainable fields in annotations and model descriptors

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 239 has merged
**Depends on:** 239 (`KeyNamespace`)
**Wave:** 2 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/Chainable.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/ModelDescriptors.java *(the `chainable` and `keyNamespace` field keys and their validation only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/ModelDescriptor.java *(the accessor for a field's chainable declaration only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ChainableField.java *(new; moved from core.model during the task, see Outcome)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ChainableFields.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/descriptor/ChainableDescriptorTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ChainableFieldsTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ChainableDispositionParityTest.java *(new)*

## Goal
A link can only start from a field the model says is a key another source accepts (D-RM-4). Add `@Chainable(keyNamespace = "…")` (OD-200-6) and the descriptor keys `chainable` and `keyNamespace`, and a resolver that lists a model's chainable paths. The flag is metadata only: it never changes what the scrubber does with the field, and `FieldMetadata` is not touched, so no scrubbing call site moves.

## Context
- docs/plan/design/key-resolvers.md section 7 ("What `chainable: true` means"), test obligations 4 and 5.
- docs/plan/design/sealed-links.md section 5, rule 2 (indexed paths for collection elements; the `<undeclared-N>` rule).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/ModelDescriptors.java:28-31: the strict key sets; descriptors may only tighten (HISTORY, "S3 scrubbing engine").
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/DefaultFieldMetadataResolver.java: how annotations, nested models and arrays are walked today. `ChainableFields` walks the same graph.
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/InternalIdentifier.java: the target and retention to copy.

## Required shape (later tasks read these)
- `ChainableField(String path, KeyNamespace keyNamespace, boolean element)`: `path` uses the `describe_source` grammar (`a`, `a[]`, `a[].b`); `element` is true when the key sits in, or under, an array. Read by 248, 249, 251.
- `ChainableFields.of(Class<?> model, ModelDescriptors descriptors)` returns the list in ascending `path` order, or throws.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `@Chainable` has one attribute, `keyNamespace`, the same `@Target` and `@Retention` as `@InternalIdentifier`, and depends on nothing outside `java.lang` (the existing `annotationsDependOnNothing` rule still passes).
- [ ] A chainable flag is valid only on a field already classified as an identifier (`@InternalIdentifier`, `@SubjectIdentifier`, or the descriptor's `identifier`). On an unclassified, `@NonSensitive` or `@SensitiveData` field, `ChainableFields.of` throws with a message starting `INVALID_CHAINABLE_FIELD:` that names the model and field and no value. So does a namespace outside the `KeyNamespace` grammar, and descriptor `chainable: true` without `keyNamespace`.
- [ ] A descriptor may add a chainable flag. It may not remove one the annotation declared, or change its namespace: both throw `INVALID_CHAINABLE_FIELD:`. Tests cover add, remove and change.
- [ ] Nested and collection paths: a list of keys gives `keys[]`; a list of records with a chainable field gives `routes[].depot`; a nested object gives `a.b`. Each case has a test.
- [ ] `ChainableDispositionParityTest` runs the existing scrubbing fixtures with and without the flag set on their identifier fields and asserts identical scrubbed trees, dispositions and emitted sets. A mutant that changes a disposition when the flag is present fails it.
- [ ] `git diff origin/main -- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/FieldMetadata.java` is empty.
- [ ] `./mvnw -B -pl data-prism-annotations,data-prism-core -am verify` passes.

## Out of scope
- `chainable` in `json-sources` `fields:` (249) and in `describe_source` output (248).
- Building links from raw values (251).
- A compile-time check in `data-prism-processor`.
- Turning the `INVALID_CHAINABLE_FIELD:` exception into a Spring startup refusal (254).

## Outcome (2026-10-21, 0.8 wave 2)
Merged onto `record/0.8-wave2` (task branch head 7fa7912a; tester PASS, about 2,196 tests; reviewer APPROVE). Added `@Chainable` (one attribute, same target and retention as `@InternalIdentifier`), the descriptor field keys `chainable` and `keyNamespace`, and `ChainableField` with `ChainableFields`. Chainable is metadata only; a parity test, including annotated twins, proves it does not change dispositions.

Coordinator decisions: `ChainableField` lives in `core.engine`, not `core.model`, because the architecture test caught a model-to-spi package cycle (the Owns line above is updated). The entry points are `ChainableFields.of(Class, Map<String, ModelDescriptor>)` and `of(Class)`, not `ModelDescriptors`. Only arrays and `Iterable` are collections (`Optional` takes the plain path); `Map` fields are refused. The walk descends exactly where the scrubber does. A declared path over 64 UTF-8 bytes (including the `[]` suffix) is refused. Every `@Chainable` occurrence in the model's data graph (supertypes, fields, components, constructor parameters, generic arguments; not arbitrary method return or parameter types; not static field types) is consumed or refused with `INVALID_CHAINABLE_FIELD`, and a descriptor entry naming an unconsumed field, including a redundant `chainable:false`, is refused. Known residual: an enum constant body's `@Chainable` is not visited.
