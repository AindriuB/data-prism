# 265 — Follow a type argument only for JDK collections, optionals and maps

**Repo:** `.`
**Release:** 0.8.0, and backported to 0.7.1 by cherry-pick as its Security fix (task 266; owner decision 2026-10-21, which replaces the earlier "no 0.7.1" note)
**Base branch:** `origin/main` once PR #144 (record/0.8-wave2, which lands 240's `ChainableFields`) has merged
**Depends on:** 240 (`ChainableFields` reads `FieldMetadata.elementType()`, and this task changes what that returns). Only the main-only commit needs 240; see "0.7.1 backport" in Context.
**Wave:** 3 (combined 0.8 graph of 213, 218 to 231 and 238 to 266; 203 and 206 to 211 counted as landed). 248 depends on 265 since 2026-10-21 and is wave 4.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/DefaultFieldMetadataResolver.java *(`elementType` and its Javadoc only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(`apply` and `scrubNestedObject` only, the nested-type choice; not `describe`, which is 248's)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/FieldMetadata.java *(the `elementType` Javadoc only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ChainableFields.java *(the comment at the `inner` choice, about "Optional<X> and other wrappers", only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ChainableFieldsTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java *(insertions; and any existing case whose fixture is now refused, each one named in the Outcome)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DefaultFieldMetadataResolverTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/NestedTypeArgumentTest.java *(new)*
- data-prism-validation/src/test/java/io/github/aindriub/dataprism/validation/GenericElementTypeCollisionTest.java *(new; adopted from the reproduction)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedLeafShapeGuard.java *(`descendType` only)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonFieldMetadataResolver.java *(Javadoc only, if it describes the old rule)*
- docs/extending.md *(the source-model shape bullet at about lines 344-356 only)*
- docs/developer-guide/write-an-adapter.md *(the record-only sentence at line 24 only)*
- docs/plan/design/contract-register.md *(two new rows next to `SOURCE_MODEL_NOT_A_RECORD`, line 249, only)*

## Goal
`DefaultFieldMetadataResolver.elementType()` returns the last type argument of any `ParameterizedType`. `JsonTreeScrubbingEngine.scrubNestedObject` and `SourceValues.descend` both prefer that type over the field's own type when the JSON value is an object. So a user generic field such as `Envelope<Leaf> env` is scrubbed with `Leaf`'s rules, and its prohibited values are collected with them too. When `Leaf` declares every field name that `Envelope` has, `Envelope`'s PII goes out raw under the DEFAULT profile, and both `RawValueLeakValidator` and `SensitivePatternValidator` pass it. A `Map<String, Leaf>` is scrubbed as if the map object were a `Leaf`, with map keys looked up as field names.

The fix fails closed and stays small:
- A type argument is followed only for JDK collections, JDK optionals, arrays and JDK map values.
- A user generic type is refused at startup.
- A map whose value type contains a record is refused at startup.
- The engine, the prohibited-value walk, the configured-JSON guard and `ChainableFields` all follow the same rule.

## Context
- The reproduction is on branch `repro/generic-collision-main`, commit `a4ad5e6a`, at `.worktrees/repro-generic-main/data-prism-validation/src/test/java/io/github/aindriub/dataprism/validation/GenericElementTypeCollisionTest.java`. It covers six shapes: sketch, collision, name-only collision, `Map<String, Leaf>`, a map keyed by a Leaf field name, and `Page<Leaf>(List<T> items)`. It runs the shipped DEFAULT profile, the real engine and both validators. All its values are invented. The defect is present at v0.7.0 and on `origin/main`.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/DefaultFieldMetadataResolver.java:171-189: `elementType`. Note that `rawType` of a `TypeVariable` is `Object.class`.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java:241-305: `apply` (object, array and scalar branches) and `scrubNestedObject`.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java:61-80: `descend`.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java:69-75 and 203-243:
  - `require`, `walk`, `refuseSerializer` (runtime) and `refusal`.
  - `walk` already recurses into type arguments and type-variable bounds, so a refusal at any depth falls out of it.
  - Messages name the class's simple name only.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedLeafShapeGuard.java:125-131: `descendType`, a deliberate copy of the engine's choice.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java:287: nested catalogue fields set `valueType` and `elementType` to the same minted token. Any rule that picks `valueType` for objects and `elementType` for array elements therefore leaves configured JSON unchanged. This file is 249's; do not edit it.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java:106,135: copies `elementType` from its delegate and adds no rule of its own. Check this, say so in the Outcome, and do not edit it.
- `ChainableFields.java` on PR #144, about line 117: `inner = field.elementType() != null ? field.elementType() : valueType`, with the comment "Optional<X> and other wrappers take the plain path; the type they hold is the last argument". After this task, a user wrapper yields `inner = valueType`, so chainable discovery walks the type the scrubber descends into. That is the consistency this task must keep.
- `ChainableFieldsTest` already has `Holder<T extends Leaf>`, `MapOfRoutes` and `MapOfList`. Those tests call `ChainableFields.of` without `SourceModels.require`, so they stay valid.
- docs/plan/design/contract-register.md section 3 (line 249, `SOURCE_MODEL_NOT_A_RECORD`, marked "yes") and section 11 (naming notes).
- docs/conventions.md: privacy rules for diffs, and the rule that messages never carry values.
- **0.7.1 backport.** Task 266 cherry-picks this task onto `release/0.7.1`, cut from `v0.7.0`, as the 0.7.1 Security fix. `ChainableField`, `ChainableFields`, `ChainableFieldsTest` and `ChainableDispositionParityTest` come from 240 and do not exist at `v0.7.0`. Split the work into two commit groups, in this order:
  - **Core fix (backported).** `DefaultFieldMetadataResolver.elementType`, `FieldMetadata` Javadoc, `SourceModels` with `SOURCE_MODEL_GENERIC` and `SOURCE_MODEL_MAP_OF_RECORD`, `JsonTreeScrubbingEngine` `apply` and `scrubNestedObject`, `SourceValues`, `ConfiguredJsonNestedLeafShapeGuard.descendType`, the `ConfiguredJsonFieldMetadataResolver` Javadoc, `SourceModelsTest`, `DefaultFieldMetadataResolverTest`, `NestedTypeArgumentTest`, the adopted `GenericElementTypeCollisionTest`, `docs/extending.md` and `docs/developer-guide/write-an-adapter.md`. These commits must not name any `Chainable*` type or any type or constant added after `v0.7.0` (check each new import with `git grep <type> v0.7.0`). The owned main and doc files were unchanged between `v0.7.0` and `origin/main` at filing. PR #144 changes none of them except by adding `ChainableFields`.
  - **Main-only (not backported).** The `ChainableFields` comment, the new `ChainableFieldsTest` case and the `docs/plan/design/contract-register.md` rows. These go in one or more commits whose subject starts `265 (main only):`. The register rows say `shipped | 0.7.1`.
  - On `main`, the full suite must pass after the core-fix commits alone, as well as at the head. That way 240's existing tests are proved to pass under the corrected rule before the main-only commit adds to them.

## Decisions taken in this brief
- **Map of records: refuse at startup, do not walk entries.** Walking entries needs a rule for map keys, and keys are data. Today a key becomes an output property name with no classification. Walking would mean deciding how a key is disposed of (placeholder, redaction or classification), then copying that decision into `SourceValues`, into 248's describer and into configured JSON. 248's path grammar also cannot name map entries; 240 found the same thing for chainables. That is a design question, not a security fix. The shape has never been scrubbed correctly, so refusing it removes nothing that worked. Walking map entries is a 0.9 follow-up for the scribe to log.
- **Two new startup codes.** The contract register has no existing code for either condition. `SOURCE_MODEL_NOT_A_RECORD` would be false for both, because a generic record is still a record. **New names for the register:**
  - `SOURCE_MODEL_GENERIC`: a record declaring type parameters, or a component whose declared type is a parameterized user type (a record, an interface or a class).
  - `SOURCE_MODEL_MAP_OF_RECORD`: a JDK map whose value type contains a record at any depth.
  - Both are condition-named, in the `SOURCE_MODEL_` family. Both are raised as `PrivacyRefusedException` with path `$`, in the same way as `SOURCE_MODEL_NOT_A_RECORD`.
- **Runtime parity.** A generic record that reaches the serializer at request time (behind an `Object`- or interface-typed component) is refused with `SOURCE_MODEL_GENERIC` by the existing `refuseSerializer` hook. A map of records found only at runtime is not refused by code. It already reaches the unclassified-structure path, which is fail-closed, and this task leaves that unchanged.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `elementType()` from `DefaultFieldMetadataResolver` is non-null only in these cases:
  - an array: the component type, as today;
  - a parameterized type whose raw type is a JDK class (bootstrap or platform loader) assignable to `java.lang.Iterable` or equal to `java.util.Optional`, with exactly one type argument that is a `Class`: that argument;
  - a parameterized JDK type assignable to `java.util.Map` with two type arguments, of which the second is a `Class`: that second argument.
  
  For a user generic (`Envelope<Leaf>`, a user interface `Box<Leaf>`, or a user `MyList<Leaf> extends List<Leaf>`) it is `null`. For a JDK wrapper outside that list (`AtomicReference<Leaf>`) it is also `null`. `DefaultFieldMetadataResolverTest` asserts each case.
- [ ] `SourceModels.require` refuses with `SOURCE_MODEL_GENERIC` in each of these cases:
  - a record class that declares type parameters, wherever the walk reaches it (top level, a component, a type argument or an array component);
  - a component whose declared type is a `ParameterizedType` with a non-JDK raw type.
  
  The message names the model's and the field's simple names (for example `SketchResponse.env: Envelope is generic; source models must not be`) and no value. `SourceModelsTest` asserts the code and asserts that the message contains neither the invented values nor a package name.
- [ ] `SourceModels.require` refuses with `SOURCE_MODEL_MAP_OF_RECORD` any JDK map component whose value type contains a record at any depth. That includes `Map<String, Leaf>`, `Map<String, List<Leaf>>`, `Map<String, Optional<Leaf>>`, and `List<Map<String, Leaf>>` inside a component. The message names model and field, and no value or key.
  
  These keep passing `require` unchanged: `Map<String, String>`, `Map<String, List<String>>`, `Map<String, Object>`, `Map<String, JsonNode>`, and an enum-keyed map of scalars.
- [ ] At request time, `SourceTree.of` refuses a generic record found behind an `Object`- or interface-typed component with `SOURCE_MODEL_GENERIC`.
- [ ] `JsonTreeScrubbingEngine` and `SourceValues` choose the nested type by one rule:
  - for an object value, `elementType()` is used only when `valueType()` is `java.util.Optional`; otherwise `valueType()` is used;
  - for an element of an array value, `elementType()` is used when it is non-null and not `Object`; otherwise `valueType()` is used.
  
  A map object value therefore never descends with the value type. It reaches the unclassified-structure path exactly as `Map<String, String>` does today.
- [ ] `ConfiguredJsonNestedLeafShapeGuard.descendType` copies that rule. Every existing `data-prism-connectors-rest` test passes unmodified, including `ConfiguredJsonNestedCatalogueScrubbingTest` and `ConfiguredJsonUndeclaredKeyRefusalTest`.
- [ ] `GenericElementTypeCollisionTest` is adopted from `a4ad5e6a` into `data-prism-validation`. Its `run` helper treats a `PrivacyRefusedException` from `SourceModels.require` as a refusal, with no scrub and no output. It asserts:
  - sketch, collision and name-only collision: `SOURCE_MODEL_GENERIC` at startup; no value emitted.
  - `Map<String, Leaf>`, and the same map keyed by `email`: `SOURCE_MODEL_MAP_OF_RECORD` at startup; no value emitted.
  - `Page<Leaf>`: `SOURCE_MODEL_GENERIC` at startup.
  - Its `System.out` diagnostics are removed.
- [ ] Defence in depth, with `require` skipped (`NestedTypeArgumentTest` in core, and one case in `GenericElementTypeCollisionTest` that runs both validators):
  - The fixture is a non-generic record `BoxImpl implements Box<Leaf>`, held in `@NonSensitive Box<Leaf> box`, where `Leaf` declares `BoxImpl`'s field names as `@NonSensitive`.
  - Under DEFAULT, this is refused with `UNCLASSIFIED_STRUCTURE` and `BoxImpl`'s invented PII is not in any output.
  - On the base commit the same test shows the value emitted raw, with both validators passing it.
- [ ] The prohibited set follows the same rule, independently of the resolver fix. `NestedTypeArgumentTest` sets up the following:
  - a test `FieldMetadataResolver` that wraps `DefaultFieldMetadataResolver` and reports the old metadata for one field: `valueType = NameEnvelope` (a non-generic `@SensitiveObject` with a PII `name`) and `elementType = NameLeaf` (which declares `name` `@NonSensitive`);
  - `SourceValues.prohibited` then contains the invented PII name;
  - `JsonTreeScrubbingEngine` over the same resolver does not emit it.
  
  Both assertions fail on the base commit.
- [ ] Unchanged behaviour, shown by the existing tests passing unmodified and by explicit new cases in `NestedTypeArgumentTest`:
  - `List<Leaf>`, `Set<Leaf>`, `Leaf[]` and `Optional<Leaf>` are scrubbed with `Leaf`'s rules;
  - `Map<String, String>` and `Map<String, Object>` take the unclassified-structure path;
  - `Optional<List<Leaf>>` and `List<List<Leaf>>` keep their current outcome.
- [ ] `ChainableFields`:
  - its comment no longer says "other wrappers" take the type argument;
  - a new `ChainableFieldsTest` case shows that a `@Chainable` identifier declared on `Envelope` and reached through a `Box<Leaf>`-style user generic field is found at `Envelope`'s path, never at `Leaf`'s.
  
  Every existing `ChainableFieldsTest` and `ChainableDispositionParityTest` case passes unmodified.
- [ ] The docs say that a source model must not be generic and that a map's values must not be records:
  - `docs/extending.md` (the shape bullet; drop "maps ... of those" for records) and `docs/developer-guide/write-an-adapter.md:24` both name the two codes.
  - `docs/plan/design/contract-register.md` gains two rows: `SOURCE_MODEL_GENERIC` and `SOURCE_MODEL_MAP_OF_RECORD`, both `shipped | 0.7.1 | core:SourceModels.java:<line>`.
- [ ] The Outcome lists every existing fixture or test across the reactor that is now refused, and how it was changed. It also confirms that `DescriptorFieldMetadataResolver` and `ConfiguredJsonFieldMetadataResolver` needed no logic change.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-validation,data-prism-connectors-rest -am verify` passes, and the full reactor `./mvnw -B verify` passes.
- [ ] The commits are split as "0.7.1 backport" in Context describes. `git log --format=%s` shows every commit that touches a `Chainable*` file or `docs/plan/**` with a subject starting `265 (main only):`, and no other commit touches those files. The Outcome names the last core-fix commit and gives the full-reactor result at that commit. After 266 cherry-picks the core-fix commits onto `release/0.7.1`, `./mvnw -B -pl data-prism-core,data-prism-validation,data-prism-connectors-rest -am verify` passes there, including `GenericElementTypeCollisionTest`, `NestedTypeArgumentTest` and the new `SourceModelsTest` cases.

## Out of scope
- Walking `Map<String, Record>` entries with the value type's rules (logged as a 0.9 follow-up; see Decisions).
- `CHANGELOG.md`. Task 266 writes the entry in `[0.7.1]` on the release branch and forward-ports it to `main`. 261 and 213 do not repeat it under `[Unreleased]`. What 266 needs, recorded here:
  - a **Security** entry: a field of a user generic type was scrubbed, and its prohibited values collected, using the type argument's rules instead of the field's own type. When the argument's type declared the same field names as `@NonSensitive`, values could be released under the DEFAULT profile past both validators. A `Map` of records was scrubbed as if the map were a record. Present in every release up to and including 0.7.0, and fixed in 0.7.1;
  - two new startup refusal codes, `SOURCE_MODEL_GENERIC` and `SOURCE_MODEL_MAP_OF_RECORD`;
  - the behaviour changes: a source model that is generic, or that has a map whose values are records, is now refused at startup. A JDK wrapper outside the collection, optional and map list (for example `AtomicReference<X>`) is no longer scrubbed with `X`'s rules and takes the unclassified-structure path.
- 248's `ScrubPlanDescriber` and `describe` override. 248 copies the corrected rule after this task.
- 249's configured-JSON files (`ConfiguredJsonSources.java` and the rest).
- `RawValueLeakValidator`, `SensitivePatternValidator` and `SensitiveDataScanner` (208 and 256 own them). The fix is in what they are given, not in them.
- Map-key classification (the 0.6 follow-up about `keyUsing` keys written as field names).
- Any other `ChainableFields` route or refusal rule (240 is closed).
