# 270 — Fail closed when a classified record does not serialise as a plain object

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 248 has merged
**Depends on:** 248 (both edit `JsonTreeScrubbingEngine.java`; this task also edits 248's `ScrubPlanDescriber`, adds cases to 248's two new tests, and builds on 248's final `SourceTree.readsAsPlainBean`)
**Wave:** 5 (filed 2026-10-23)
**Decisions:** OD-270-1 decided (a) and OD-270-2 decided (a), both by the owner on 2026-10-23 (below).
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(the `apply`, `scrubArray` and `scalar` paths, for a value whose declared type is a classified record; not the `describe` override)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ClassifiedShapes.java *(new, package-private: the one rule, shared by every site below, for "carries a classification" and "serialises as a plain object of its components")*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java *(insertions only: the new startup refusal and its code constant)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java *(only where this task touches 248's `readsAsPlainBean`: a call into it, or a fix confined to that method that the Outcome names; nothing else in the file)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java *(the non-object value at a classified-record position only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriber.java *(the branch that decides such a type is ambiguous only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordShapeTest.java *(new: the three repros and the scrub-time cases)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordProhibitedValuesTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriberTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DescriptionConformsToScrubTest.java *(insertions only: the three shapes as models)*
- docs/extending.md *(the source-model shape paragraph, about :355-369, only)*
- docs/plan/design/contract-register.md *(insertions only: the rows for the codes this task adds, in sections 3.1 and 3.5)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Security entry and one Breaking entry)*

## Goal
The scrubber applies the holding field's policy to whatever Jackson writes for a value, and descends only into JSON objects. When a record that carries its own classification is written as something other than a plain object of its components (a scalar through `@JsonValue` or a custom or delegating serializer, an array through `@JsonFormat(shape=ARRAY)`, an object-id reference through `@JsonIdentityInfo`), the record's classification is never consulted and the holder's policy, often `@NonSensitive`, releases the value. The gap predates 0.8. Close it for the whole family, not per annotation: a classified record is either serialised as a plain object whose properties are exactly its components, or the model is refused at startup and, as a backstop, the response is refused at scrub time. The value is never passed through.

## The gap (found 2026-10-23; repros under the bundled DEFAULT profile, on `main`)
1. `@SensitiveObject record Email(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String value) { @JsonValue public String value() { return value; } }`, held by `@LlmExposedModel record Holder(@InternalIdentifier String ref, @NonSensitive(reason="wrapper") Email email)`. The scrub emits the email verbatim at `email`.
2. `@SensitiveObject @JsonFormat(shape=ARRAY) record ArrContact(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String email)`, held by `@NonSensitive(reason="x") ArrContact contact`. The scrub emits `{"contact":["ada@example.invalid"]}`. The same happens with `@JsonFormat(shape=ARRAY)` on the holding component instead of the class.
3. `@SensitiveObject @JsonIdentityInfo(generator=ObjectIdGenerators.PropertyGenerator.class, property="email") record IdContact(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String email)`, held by `@NonSensitive(reason="x") List<IdContact> contacts` with the same instance twice. The scrub emits `[{"email":"[REDACTED]"},"ada@example.invalid"]`: the second occurrence is an object id, a scalar, and takes the holder's policy.

`SourceValues.prohibited` is blind to all three for the same reason: it descends only into objects and the holder is not sensitive, so `RawValueLeakValidator` does not catch the emitted value either.

## Definitions (one implementation, in `ClassifiedShapes`)
- **Classified record:** a record for which the `FieldMetadataResolver` reports any component that is `sensitive()` or an `identifier()`. A record whose components are all `@NonSensitive` is not classified. `@SensitiveObject` alone does not make a record classified, and neither does an unannotated component, which follows the existing unclassified rules. At startup, where no resolver is passed in, the annotation-only `DefaultFieldMetadataResolver` reading is used, and the descriptor path is covered by the scrub-time rule.
- **Plain object:** exactly what 248's package-private `SourceTree.readsAsPlainBean(holder, component, type)` answers `true` for. It asks `READER`'s own serializers, through `READER._serializationContext()`, and requires all of these: an exact `BeanSerializer` or `UnrolledBeanSerializer`; no object id, filter id or unwrapping; and properties that are exactly the record's components. It judges the holder property through Jackson, so it covers format shape, meta-annotations, inherited annotations and container content serializers. `ClassifiedShapes` calls that method and does not implement a second check. If a repro shows the method answering `true` for a shape that is not a plain object, fix the method itself and name the change in the Outcome.
- **Classified-record position:** a field value or array element whose type under the existing `nestedType` rule is a classified record.

## OD-270-1: decided (a) by the owner, 2026-10-23
- **Decided: refuse at startup, and refuse at scrub time.** `SourceModels.require` refuses a model that reaches a classified record that is not a plain object, with the new code `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, naming `Model.component` and the record's simple name, never a value. This includes a holding component whose `@JsonFormat` or `@JsonSerialize` makes it non-plain. At scrub time a non-object, non-null value at a classified-record position refuses the response with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at that path. This reaches a record classified only by a descriptor, and an object id, which the declared types cannot always show. Why: it follows the narrowing of D-173-2 and the other `SOURCE_MODEL_*` refusals. The author learns at startup and fixes the model, by classifying the holder or by removing the custom shape. A redacted array or object id is not a value anyone wants delivered, and every refused path stays omitted from `describe`, which 248 already does. Cost: a breaking change for any adopter model with this shape. 0.8 accepts breaking changes (D-1.0-5).
- **Rejected when the task was filed:** a stricter-of merge of the holder's policy with the record's components' policies. It answers only the scalar case, needs a namespace rule for `SYNTHESIZE`, `TOKENIZE` and `HASH`, and has no meaning for an array or an object id.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java:241-382: `apply`, `scrubArray`, `scrubNestedObject`, `nestedType` and `scalar`. The holder's policy reaches `scalar` with no check of the declared type.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java:101-153 (`refuseSerializer`, `refuseForeignProperties`) and :225-283 (`walk`): where a startup refusal joins the existing ones. `DataPrismContractValidator.java:51` calls `require` for every adapter response type, so no call site changes.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java:56-140: `READER`, `ClassLevelSerializers`, the `RecordsOnly` modifier and, from 248, `readsAsPlainBean`. This is the mapper whose output is judged, and the one check of it.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java:58-90: `descend` only follows objects.
- From 248: `ScrubPlanDescriber` treats a record that serialises as a scalar as ambiguous and omits the field. After this task it uses `ClassifiedShapes` to decide that, so the scrubber and the describer cannot drift. The omission stays correct and gains nothing new to describe. A refused field is never described, and a model refused at startup is never described. An unclassified record with `@JsonValue` keeps the holder's policy, which is deterministic, so the describer may describe it with the holder's protection. Do that if 248 left it omitted, and cover it in the conformance test.
- docs/plan/design/contract-register.md section 3.1 row `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` (:252) and section 3.5: the row shape for the new codes.
- The fixture values use `example.invalid` and invented names only (repository rule 3).

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them and shows the failing output. `ClassifiedRecordShapeTest` holds the three repros exactly as written under "The gap", plus the property-level `@JsonFormat(shape=ARRAY)` variant of repro 2, each scrubbed under the bundled DEFAULT profile. On the base commit each emits `ada@example.invalid` (or the Email sentinel) somewhere in the scrubbed tree.
- [ ] `SourceModels.require` refuses `Holder`, the `ArrContact` holder (both the class-level and the property-level variant) and the `IdContact` holder with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`. The message names the component and the record's simple name, and no value. A record with `@JsonValue` whose components are all `@NonSensitive` still passes, as do an unclassified wrapper and every model in the existing `SourceModelsTest`.
- [ ] With the startup check bypassed (the engine is called directly, as `ClassifiedRecordShapeTest` does), each repro is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at the holding path. `PrivacyRefusedException`'s message and path contain no value.
- [ ] A record classified only by a descriptor (`DescriptorFieldMetadataResolver` classifying the `@JsonValue` component of an otherwise `@NonSensitive` record) is refused at scrub time, which the startup check cannot see.
- [ ] Unchanged behaviour, each pinned by a test: a classified record written as a plain object is descended into as today; a `null` at a classified-record position stays `null`; an unclassified record with `@JsonValue` takes the holder's policy as today.
- [ ] `SourceValues.prohibited` contains every scalar under a non-object value at a classified-record position (all three repros); `ClassifiedRecordProhibitedValuesTest` shows this, and that on the base commit the set misses them.
- [ ] `ScrubPlanDescriber` decides "ambiguous" through `ClassifiedShapes` and nothing else. `DescriptionConformsToScrubTest` gains the three shapes. No field the scrubber refuses or changes is described `VERBATIM`, and the existing cases pass unchanged.
- [ ] `ClassifiedShapes` decides plain-object-ness only by calling `SourceTree.readsAsPlainBean`. A grep in the Outcome shows that no other class in `core.engine` inspects serializer types for this purpose.
- [ ] Mutation proofs, recorded in the Outcome: making `ClassifiedShapes` treat every record as plain fails all three repros, and dropping the object-id condition from `readsAsPlainBean` fails repro 3.
- [ ] The Outcome includes a draft GitHub security advisory, under its own heading, for the release cut to publish (OD-270-2). It gives the affected versions (0.7.0 and 0.7.1, and every earlier release the implementer finds affected, checked against the tags), names 0.8.0 as the fixed version, and gives the workaround: do not mark a field `@NonSensitive` when it holds a classified record. It describes the three shapes without real data.
- [ ] `docs/extending.md`, in the source-model shape paragraph, states the rule and the startup code. The paragraph's existing sentence, that `@JsonValue` and `@JsonSerialize` "stay allowed (the engine still classifies what they produce)", is corrected, because it is the claim this task found false.
- [ ] The contract register gains a row for each new code. `CHANGELOG.md` `[Unreleased]` gains a Security entry: what leaked, under which shapes, and since when. It also gains a Breaking entry naming the startup code and the author's two fixes. Text for `docs/migration-0.8.md` goes in the Outcome under "for the migration notes" (261 owns that page).
- [ ] Every existing `data-prism-core` test passes unmodified.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-validation -am verify` passes.

## OD-270-2: decided (a) by the owner, 2026-10-23
The fix ships in 0.8.0 only. There is no 0.7.2. A GitHub security advisory for 0.7.x names 0.8.0 as the fixed version, with the workaround: do not mark a field `@NonSensitive` when it holds a classified record. This task drafts the advisory in its Outcome, and the 0.8.0 release cut publishes it. The recommendation cited Central's File Count limit, whose grace period ends 2026-11-08, as the reason not to cut a 0.7.2.

## Out of scope
- Maps of records, records inside nested containers and `Optional` shapes (refused already; 0.9).
- A stricter-of policy merge for scalar records (rejected above).
- `@JsonAnyGetter`, `@JsonUnwrapped` and other shapes that still write an object. Report one in the Outcome if a test finds it leaking; do not fix it here.
- `ConfiguredJsonScrubbingEngine` and configured JSON sources, which have no records (249).
- `PrivacyEngineWiring` and the descriptor map (271).
- `docs/migration-0.8.md` and every other user page (261).
