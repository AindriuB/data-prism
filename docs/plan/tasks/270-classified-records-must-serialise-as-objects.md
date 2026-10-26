# 270 — Fail closed when a classified record does not serialise as a plain object

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 248 and 271 have merged
**Depends on:** 271 (both insert into `CHANGELOG.md` `[Unreleased]` and `docs/plan/design/contract-register.md`; added 2026-10-24), 248 (both edit `JsonTreeScrubbingEngine.java`; this task also edits 248's `ScrubPlanDescriber`, adds cases to 248's two new tests, and builds on 248's final `SourceTree.readsAsPlainBean`)
**Wave:** 6 (filed 2026-10-23 at wave 5; moved to 6 on 2026-10-24 by the edge on 271)
**Decisions:** OD-270-1 decided (a) and OD-270-2 decided (a), both by the owner on 2026-10-23 (below). The repros under "The gap" stay in this public brief: the owner accepted that exposure on 2026-10-23 (review item H1), and the advisory GHSA-f94h-6mxv-c957 is drafted now rather than at the release cut. `readsAsPlainBean` stays strict, because strict fails closed (coordinator, 2026-10-23, review of PR #161).
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(the `apply`, `scrubArray` and `scalar` paths, for a value at a classified-record position, and the `SourceTree.of` call in `scrub`; not the `describe` override)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ClassifiedShapes.java *(new, package-private: the one rule, shared by every site below, for "classified record" and "plain object")*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java *(insertions only: the new startup refusal, its code constant and the `require(Class, FieldMetadataResolver)` overload)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java *(the `of` path and its `of(Object, FieldMetadataResolver)` overload, the `RecordsOnly` serializer-modifier callbacks for the runtime position check, and 248's `readsAsPlainBean`: calls into it, or fixes confined to that method that the Outcome names)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java *(the non-object value at a classified-record position, and the `SourceTree.of` call, only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/ClassifiedRecordSourceRefusalTest.java *(new: the two-source test; no main code in `data-prism-orchestration` changes)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriber.java *(the branch that decides such a type is ambiguous only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordShapeTest.java *(new: the repros and the scrub-time cases)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordPositionTest.java *(new: the runtime position check)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordProhibitedValuesTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriberTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DescriptionConformsToScrubTest.java *(insertions only: the repro shapes as models)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismContractValidator.java *(the constructor, one new `ObjectProvider<FieldMetadataResolver>` field, and the resolver-aware model check in `afterPropertiesSet` only; the static `validateIntegrations` signature is unchanged)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PropertiesValidation.java *(the `dataPrismContractValidator` bean method only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/validation/ClassifiedRecordStartupTest.java *(new; in the `validation` sub-package, outside 210's `spring/boot/*.java` glob)*
- data-prism-spring-boot-autoconfigure/src/test/resources/classified-records/** *(new fixture files only)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ClassifiedShapesArchitectureTest.java *(new; 225 owns `ArchitectureTest.java`, so the rule lives in its own file)*
- docs/extending.md *(the source-model shape paragraph, about :355-369, only)*
- docs/plan/design/contract-register.md *(insertions only: the rows for the codes this task adds, in sections 3.1 and 3.5)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Security entry and one Breaking entry)*

## Goal
The scrubber applies the holding field's policy to whatever Jackson writes for a value, and descends only into JSON objects. When a record that carries its own classification is written as something other than a plain object of its components, the record's classification is never consulted, and the holder's policy, often `@NonSensitive`, releases the value. Shapes that do this include:
- a scalar, through `@JsonValue` or a custom or delegating serializer;
- an array, through `@JsonFormat(shape=ARRAY)`;
- an object-id reference, through `@JsonIdentityInfo`;
- a type-info wrapper.

The same happens when a classified record sits in a position typed `Object` or an interface. The gap predates 0.8.

Close it for the whole family, not per annotation. A classified record is either serialised as a plain object, whose properties are exactly its components, at a position declared as that record, or:
- the model is refused at startup;
- as a backstop, the response is refused at scrub time.

The value is never passed through.

## The gap (found 2026-10-23; repros under the bundled DEFAULT profile, on `main`)
1. `@SensitiveObject record Email(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String value) { @JsonValue public String value() { return value; } }`, held by `@LlmExposedModel record Holder(@InternalIdentifier String ref, @NonSensitive(reason="wrapper") Email email)`. The scrub emits the email verbatim at `email`.
2. `@SensitiveObject @JsonFormat(shape=ARRAY) record ArrContact(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String email)`, held by `@NonSensitive(reason="x") ArrContact contact`. The scrub emits `{"contact":["ada@example.invalid"]}`. The same happens with `@JsonFormat(shape=ARRAY)` on the holding component instead of the class.
3. `@SensitiveObject @JsonIdentityInfo(generator=ObjectIdGenerators.PropertyGenerator.class, property="email") record IdContact(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String email)`, held by `@NonSensitive(reason="x") List<IdContact> contacts` with the same instance twice. The scrub emits `[{"email":"[REDACTED]"},"ada@example.invalid"]`: the second occurrence is an object id, a scalar, and takes the holder's policy.
4. Container variants of 1 and 2, each held by a `@NonSensitive(reason="x")` component: `Optional<Email>`, `Optional<ArrContact>`, `List<Email>` and `Email[]`. `Optional` is transparent to `nestedType`, and an element takes the element type, so each reaches the same path.
5. Type-info wrappers: the `ArrContact` record without `@JsonFormat`, instead annotated `@JsonTypeInfo(use=Id.NAME, include=As.WRAPPER_ARRAY)`, and again with `As.WRAPPER_OBJECT`. Also a classified record with `@JsonAppend` adding a virtual property. Their outcomes on `main` are recorded first (characterisation). Whatever 248's `readsAsPlainBean` answers for them, each must end up refused.
6. Untyped position: an `Email` held by a `@NonSensitive(reason="x")` component declared as each of the following:
   - `Object`;
   - an interface the record implements;
   - an abstract base class: `java.lang.Record` itself, the only abstract class a record can extend, since a record cannot extend a user class;
   - `List<?>` (the record as an element);
   - a raw `List`;
   - `Optional<Object>`;
   - a type variable: `record Holder<T>(@NonSensitive(reason="x") T payload)` holding an `Email`. A generic record is refused today with `SOURCE_MODEL_GENERIC`, at startup and by `refuseSerializer` at runtime. The characterisation records which refusal fires. The untyped-position check must refuse it too if that refusal is ever relaxed, which a test proves by calling the position check directly.

   Nothing at startup sees the record (except the type-variable case). At scrub time the scalar takes the holder's policy.
7. Transitive wrapper: `@SensitiveObject record Wrapper(@NonSensitive(reason="x") Email inner) { @JsonValue public String v() { return inner.value(); } }`, held by `@NonSensitive(reason="x") Wrapper w`. A second variant has `@JsonValue public Email inner()`, so Jackson writes the inner `Email` through its own `@JsonValue`. A third variant replaces `Email` with a plain object, `@SensitiveObject record Inner(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String email)`, which has no `@JsonValue`: `record Wrapper(@NonSensitive(reason="x") Inner inner) { @JsonValue public String v() { return inner.email(); } }`. `Wrapper`'s only component is `@NonSensitive`, so under a non-transitive rule it would not be classified, and the email would leave as a scalar. In the third variant nothing under `Wrapper.inner` is refused on its own, so only the transitive rule refuses it.

`SourceValues.prohibited` is blind to all of these for the same reason: it descends only into objects, and the holder is not sensitive. So `RawValueLeakValidator` does not catch the emitted value either.

## Definitions (one implementation, in `ClassifiedShapes`)
- **Classified record (transitive):** a record is classified if either of these holds:
  - the `FieldMetadataResolver` reports any of its components as `sensitive()` or an `identifier()`;
  - any component's type is itself a classified record, looking through arrays, JDK collections and `Optional`. Map values are already refused when they are records.

  The check walks the type graph with a visited set, so a cycle terminates. A record is not classified only when no component carries a classification and no component's type, at any depth, is a classified record.
  - Repro 7's `Wrapper` is classified because its component's type, `Email`, is.
  - `@SensitiveObject` alone does not make a record classified, and neither does an unannotated component, which follows the existing unclassified rules.
  - Wherever a resolver is available, it is the one used: the engine's at scrub time, and the wired bean at Spring startup (see M3 below).
  - Only the one-argument `SourceModels.require(Class)` and `SourceTree.of(Object)` fall back to the annotation-only `DefaultFieldMetadataResolver`.
- **Plain object:** exactly what 248's package-private `SourceTree.readsAsPlainBean(holder, component, type)` answers `true` for.
  - How it decides: it asks `READER`'s own serializers, through `READER._serializationContext()`. It judges the holder property through Jackson, which covers format shape, meta-annotations, inherited annotations and container content serializers.
  - It requires all of:
    - an exact `BeanSerializer` or `UnrolledBeanSerializer`;
    - no object id, filter id or unwrapping;
    - properties that are exactly the record's components.
  - It stays strict. A component renamed with `@JsonProperty`, and an `@JsonInclude` on the holding component, are therefore not plain. They are refused like the rest, and named in the Breaking entry.
  - `ClassifiedShapes` calls that method and implements no second check. If a repro shows the method answering `true` for a shape that is not a plain object (a type-info wrapper, for example), fix the method itself and name the change in the Outcome.
- **Classified-record position:** a field value or array element whose type, under the existing `nestedType` rule, is a classified record.
- **Component-exact object:** a JSON object whose property names are exactly the classified record's component names: no extra key, none missing, none renamed. This is the only shape the scrubber accepts at a classified-record position. It is checked on the tree at scrub time, independently of `readsAsPlainBean`, because a scrubbed tree carries no serializer.
- **Untyped position:** a property or element whose declared type is `Object`, an interface, an abstract type, a type variable, an unbounded wildcard, or a raw collection element. A bounded wildcard is read by its upper bound. `List<? extends Email>` is a classified-record position for `Email`, not an untyped one; records are final, so the bound is exact.
- **`SourceValues.prohibited` never throws for this task's refusals.**
  - `DefaultContextOrchestrator.fetchAndScrub` (`:387`) calls it outside the per-source `try`, so a throw there would fail the whole request, not just the source.
  - Instead, where the scrubber refuses with `CLASSIFIED_RECORD_NOT_AN_OBJECT` or `CLASSIFIED_RECORD_UNTYPED_POSITION`, `prohibited` adds every scalar under that value, which fails closed, and returns normally. It reads the tree through a non-refusing variant of the same position check.
  - The refusal then comes from `scrubber.scrub`, inside the existing per-source `try`: that source is `REFUSED` and dropped whole, its values stay prohibited for every other source, and the other sources still answer.
  - No change to `DefaultContextOrchestrator` is needed. `ConfiguredJsonScrubbingEngine` calls `prohibited` after `scrub`, so it is unaffected.

## OD-270-1: decided (a) by the owner, 2026-10-23
- **Decided: refuse at startup, and refuse at scrub time.**
  - **At startup.** `SourceModels.require` refuses a model that reaches a classified record that is not a plain object, with the new code `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`. This includes a holding component whose `@JsonFormat`, `@JsonSerialize` or `@JsonInclude` makes it non-plain. The message reads `<Model.component>: <Record> serialised shape not verifiable as a plain object`, naming the component and the record's simple name, never a value.
  - **At scrub time.**
    - A non-null value at a classified-record position that is not a component-exact object refuses the response with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at that path. The refusal message reads `classified record value is not an object of exactly its components`, with no value and no property name. This covers:
      - a scalar or an array;
      - a wrapper key (`@JsonTypeInfo` `WRAPPER_OBJECT`);
      - an appended or any-getter property;
      - a missing component (an `@JsonInclude` omission, for example);
      - a renamed one.
    - While the tree is built, `SourceTree.of` refuses a classified record written at an untyped position, plain or not, with `CLASSIFIED_RECORD_UNTYPED_POSITION`. The message reads `classified record at a position whose declared type is not that record`, with no value and no class name.
  - **Why.**
    - It follows the narrowing of D-173-2 and the other `SOURCE_MODEL_*` refusals.
    - The author learns at startup and fixes the model by following the 0.8 upgrade instructions ("Two kinds of advice" below).
    - A redacted array or object id is not a value anyone wants delivered.
    - Every refused path stays omitted from `describe`, which 248 already does.
  - **Cost.** A breaking change for any adopter model with these shapes. 0.8 accepts breaking changes (D-1.0-5).
- **Rejected when the task was filed:** a stricter-of merge of the holder's policy with the record's components' policies. It answers only the scalar case, needs a namespace rule for `SYNTHESIZE`, `TOKENIZE` and `HASH`, and has no meaning for an array or an object id.

## Two kinds of advice (external review, 2026-10-26)
Keep these apart. Each place in this task names the one it uses.
- **Pre-fix workaround** (0.7.x only; for the GHSA-f94h-6mxv-c957 advisory text): mark the holding field with a classification, for example `@SensitiveData(classifications=CONTACT, suggestedAction=REDACT)`, instead of `@NonSensitive`. The scrubber then applies the holder's policy to the scalar. This holds only before 0.8. From 0.8.0 the same model is refused at startup, because the record's shape is still not a plain object.
- **0.8 upgrade instructions** (for `docs/extending.md`, the CHANGELOG Breaking entry and the migration notes): a classified record must serialise as a plain object: remove `@JsonValue`, custom or delegating serializers, `@JsonFormat(shape=ARRAY)`, object ids (`@JsonIdentityInfo`) and type-info wrappers from classified records; if the value really is a single scalar, make the field itself a classified scalar (for example a `String` with `@SensitiveData`) instead of a classified record. Classifying the holding field does not resolve the startup refusal.

## Startup with the wired resolver (M3)
`DataPrismContractValidator` gains an `ObjectProvider<FieldMetadataResolver>`, passed from `PropertiesValidation.dataPrismContractValidator`. 271's identity check lives in `PrivacyEngineWiring`, not here. After the existing static checks, `afterPropertiesSet` calls `SourceModels.require(adapter.responseType(), resolver)` for every adapter, so a record classified only by a descriptor is refused at startup. The refusal is wrapped exactly as `validateIntegrations` wraps `require` today: a `DataPrismConfigurationException` with the same code and the source name. The public static `validateIntegrations` keeps its signature and its annotation-only check, so its existing callers and tests are untouched.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java:241-382: `apply`, `scrubArray`, `scrubNestedObject`, `nestedType` and `scalar`. The holder's policy reaches `scalar` with no check of the declared type.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java:101-153 (`refuseSerializer`, `refuseForeignProperties`) and :225-283 (`walk`): where a startup refusal joins the existing ones.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java:56-140 and :250: `READER`, `ClassLevelSerializers`, the `RecordsOnly` modifier, `of`, and, from 248, `readsAsPlainBean`. This is the mapper whose output is judged, and the one check of it. `of(Object)` is also called by `NamespaceCorrelationService` (orchestration), which keeps the one-argument form.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java:37 and :58-90: `descend` only follows objects.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismContractValidator.java:51 and PropertiesValidation.java:105-112: the startup check and its bean.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/RefusalCodes.java: the shared code rule `[A-Z][A-Z0-9_]{0,63}`.
- From 248: `ScrubPlanDescriber` treats a record that serialises as a scalar as ambiguous, and omits the field.
  - After this task it decides that through `ClassifiedShapes`, so the scrubber and the describer cannot drift.
  - The omission stays correct and gains nothing new to describe: a refused field is never described, and neither is a model refused at startup.
  - A record with `@JsonValue` that is unclassified under the transitive rule keeps the holder's policy, which is deterministic, so the describer may describe it with the holder's protection. Do that if 248 left it omitted, and cover it in the conformance test.
- docs/plan/design/contract-register.md section 3.1 row `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` (:252) and section 3.5: the row shape for the new codes.
- The fixture values use `example.invalid` and invented names only (repository rule 3).

## Acceptance
- [ ] Tests are written first and fail on the base commit. The Outcome names them and shows the failing output.
- [ ] **Characterisation, before any change.** `ClassifiedRecordShapeTest` and `ClassifiedRecordPositionTest` record what `main` emits, under the bundled DEFAULT profile, for:
  - every repro under "The gap" (1 to 7, each variant separately);
  - the property-level `@JsonFormat(shape=ARRAY)` variant of repro 2.

  The Outcome reports the per-repro result. Each of 1, 2, 3, 4, 6 and 7 emits `ada@example.invalid` (or the Email sentinel) somewhere in the scrubbed tree. The exception is repro 6's type-variable variant, which `main` already refuses with `SOURCE_MODEL_GENERIC`. The Outcome records whether that comes from `SourceModels.require` or from `refuseSerializer` at runtime, and the variant is not required to leak.
- [ ] **Startup refusal.** `SourceModels.require` refuses each of the following with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`:
  - the holders of repros 1, 2 (class-level and property-level), 3, 4 (all four containers), 5 (both `@JsonTypeInfo` variants and `@JsonAppend`) and 7 (all three variants). For repro 7's third variant the message starts `Holder.w:`, the wrapper's holder, not `Wrapper.inner:`, which pins that the transitive rule refused it;
  - a classified record with a `@JsonProperty`-renamed component;
  - a holding component of a classified record that carries `@JsonInclude`.

  The message is `<Model.component>: <Record> serialised shape not verifiable as a plain object` and contains no value.
- [ ] **Still allowed at startup.** A record with `@JsonValue` passes when it is not classified under the transitive rule: its components are all `@NonSensitive`, and none of their types, at any depth, is a classified record. An unclassified wrapper also passes. Repro 7's `Wrapper` does not pass: it is refused. Every model in the existing `SourceModelsTest` passes unchanged.
- [ ] **Scrub-time backstop.** With the startup check bypassed (the engine is called directly), each of the following is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at the holding path:
  - repros 1 to 5 and 7, including the `WRAPPER_OBJECT` and `@JsonAppend` variants, which write objects;
  - a classified record with an `@JsonAnyGetter` that adds a key;
  - one whose serialised object misses a component (`@JsonInclude(NON_NULL)` with a `null` component);
  - one with a `@JsonProperty`-renamed component.

  The message is `classified record value is not an object of exactly its components`. Neither the message nor the path contains a value or a property name.
- [ ] **Untyped positions.** Repro 6, in each of its first six declarations (`Object`, interface, `java.lang.Record`, `List<?>`, raw `List`, `Optional<Object>`), is refused while the tree is built, with `CLASSIFIED_RECORD_UNTYPED_POSITION`, through the engine. `SourceValues.prohibited` on the same source returns normally and contains every scalar under the value. A `List<? extends Email>` holding plain `Email` objects is descended into as a classified-record position, not refused; holding repro 1's `Email` it is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT`. The same holds for a plain, classified record in an `Object` position. An unclassified record at an untyped position keeps today's behaviour, pinned by a test.
- [ ] **Descriptor-only classification, scrub time.** A record classified only by a descriptor (`DescriptorFieldMetadataResolver` classifying the `@JsonValue` component of an otherwise `@NonSensitive` record) is refused at scrub time with `CLASSIFIED_RECORD_NOT_AN_OBJECT`. At an untyped position it is refused with `CLASSIFIED_RECORD_UNTYPED_POSITION`.
- [ ] **Descriptor-only classification, startup (M3).** `ClassifiedRecordStartupTest` starts a context whose descriptor file (under `src/test/resources/classified-records/`) classifies the `@JsonValue` component of an otherwise `@NonSensitive` record. One adapter returns a model that holds it. Startup fails with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT` and the source name. Without the descriptor, the same context starts. The bean inventories are unchanged.
- [ ] **Unchanged behaviour (characterisation, each pinned by a test):**
  - a classified record written as a plain object at a declared position is descended into as today;
  - a `null` at a classified-record position stays `null`;
  - a record with `@JsonValue` that is unclassified under the transitive rule (no classified component at any depth) takes the holder's policy as today. Repro 7 is not such a record, and no test pins its current output except the characterisation item.
- [ ] **Prohibited values.** `SourceValues.prohibited` contains every scalar under a non-object value at a classified-record position (repros 1 to 5 and 7), and under every classified record at an untyped position (repro 6). It throws for none of them. `ClassifiedRecordProhibitedValuesTest` shows this, and that on the base commit the set misses them.
- [ ] **One bad source does not fail the request.** `ClassifiedRecordSourceRefusalTest` builds a `DefaultContextOrchestrator` with two sources. Source `a` returns repro 1's `Holder`; source `b` returns a well-formed model.
  - The response is answered: `a` is `REFUSED` with disposition `a:<refused>` = `REFUSED:CLASSIFIED_RECORD_NOT_AN_OBJECT`, and `b` is `ANSWERED`.
  - `a`'s email is in the prohibited set.
  - A second case uses repro 6's `Object` variant for `a`, with `REFUSED:CLASSIFIED_RECORD_UNTYPED_POSITION`.
  - Mutant killed: letting `prohibited` throw fails the whole request, which fails this test.
- [ ] **Describer.** `ScrubPlanDescriber` decides "ambiguous" through `ClassifiedShapes` and nothing else. `DescriptionConformsToScrubTest` gains repros 1 to 4. No field the scrubber refuses or changes is described `VERBATIM`, and the existing cases pass unchanged.
- [ ] **One check.** `ClassifiedShapesArchitectureTest` (ArchUnit) asserts two things:
  - `ClassifiedShapes` calls `SourceTree.readsAsPlainBean`;
  - no class in `io.github.aindriub.dataprism.core.engine` other than `SourceTree` depends on `tools.jackson.databind.ser.BeanSerializer`, `tools.jackson.databind.ser.bean.UnrolledBeanSerializer` or `tools.jackson.databind.ser.impl.ObjectIdWriter`. Use the class names as they are on the base commit.
- [ ] **Mutation proofs, each recorded in the Outcome with the test that fails:**
  - remove the scrub-time backstop: the direct-engine and descriptor-only tests fail;
  - weaken the backstop to "is a JSON object" (dropping the exact-property-names comparison): the `WRAPPER_OBJECT`, `@JsonAppend`, any-getter, missing-component and renamed-component tests fail;
  - weaken it to "no extra keys" (allowing missing ones): the missing-component test fails;
  - remove the startup check from `SourceModels.require`: the startup tests fail;
  - make `ClassifiedShapes` treat every record as plain: repros 1 to 5 and 7 fail;
  - make the classification rule non-transitive (components' own annotations only): repro 7 fails;
  - drop the object-id condition from `readsAsPlainBean`: repro 3 fails;
  - remove the untyped-position check: each of repro 6's first six declarations fails, and so does the direct call for the type-variable case.
- [ ] **Codes.** `RefusalCodes.sanitise` returns each new code unchanged: `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, `CLASSIFIED_RECORD_NOT_AN_OBJECT` and `CLASSIFIED_RECORD_UNTYPED_POSITION`. A test asserts it. The contract register gains a row for each.
- [ ] **`docs/extending.md`, source-model shape paragraph.**
  - Delete the words "(the engine still classifies what they produce)", because this task found that claim false.
  - Add these four sentences verbatim: "A record is classified if any of its components carries a classification (`@SensitiveData` or `@InternalIdentifier`, in code or through a model descriptor), or if any component's type, through arrays, collections and `Optional`, is itself a classified record. A classified record must serialise as a plain JSON object whose properties are exactly its components, and must be held at a position declared as that record. Startup refuses any other shape with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, including `@JsonValue`, a custom or delegating serializer, `@JsonFormat(shape=ARRAY)`, `@JsonIdentityInfo`, `@JsonTypeInfo`, `@JsonAppend`, a component renamed with `@JsonProperty`, and `@JsonInclude` on the holding component. To fix a refused model, remove that shape from the classified record, or, if the value really is a single scalar, make the field itself a classified scalar (for example a `String` with `@SensitiveData`) instead of a classified record."
  - The paragraph does not give the pre-fix workaround (classifying the holding field), which does not resolve the refusal.
- [ ] **`CHANGELOG.md` `[Unreleased]`.**
  - Security entry: what leaked, under which shapes, and since which version (as established below).
  - Breaking entry:
    - names `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT` and `CLASSIFIED_RECORD_UNTYPED_POSITION`;
    - names the shapes now refused, including the `@JsonProperty` rename and `@JsonInclude` on the holder;
    - names `DataPrismContractValidator`'s public constructor change (a new `ObjectProvider<FieldMetadataResolver>` parameter);
    - gives the 0.8 upgrade instructions only: a classified record must serialise as a plain object: remove `@JsonValue`, custom or delegating serializers, `@JsonFormat(shape=ARRAY)`, object ids (`@JsonIdentityInfo`) and type-info wrappers from classified records; if the value really is a single scalar, make the field itself a classified scalar (for example a `String` with `@SensitiveData`) instead of a classified record; and, for an untyped position, declare the position as the record. It does not suggest classifying the holding field.
  - Text for `docs/migration-0.8.md` goes in the Outcome under "for the migration notes" (261 owns that page and depends on this task).
- [ ] **Security advisory GHSA-f94h-6mxv-c957 (OD-270-2).** The advisory is already drafted (owner decision, 2026-10-23). The Outcome has a heading of its own with the text to update it with: the confirmed affected range, the pre-fix workaround ("Two kinds of advice"), the 0.8 upgrade instructions, and the patched version 0.8.0. Before it states the workaround or the range, the Outcome does both of the following:
  - **Workaround matrix.** Runs repro 1 under DEFAULT with each holder annotation, and records which leak:
    - `@NonSensitive`;
    - `@SensitiveData` with each implemented action (`PASS_THROUGH`, `REDACT`, `REMOVE`, `SYNTHESIZE`, `TOKENIZE`, `HASH`, `GENERALIZE`);
    - `@InternalIdentifier`;
    - unannotated;
    - classified by a descriptor.

    The advisory's workaround for 0.7.x is the pre-fix workaround, checked against that table: every annotation the table shows stops the leak is a valid alternative to `@NonSensitive`, and every one that leaks (for example `@SensitiveData` with `PASS_THROUGH`) is named as not a workaround. The advisory states that the workaround applies only before 0.8.0, and that upgrading requires the 0.8 upgrade instructions.
  - **Affected versions.**
    - Cover every release tag from `v0.1.0` (or the earliest release tag, if that is later) through `v0.7.1`. The scalar repros (1 and 6) need no record descent, so no tag is skipped on that ground. The Outcome also names the commit that introduced nested-record descent, for the advisory's description.
    - For each tag, write a leak probe against that tag's own API: its annotations, its engine constructor and its resolver. Do not copy this task's repro classes.
      - The probe expresses each repro shape (1 to 7, each variant) in that API.
      - Check the tag out in its own scratch worktree, add the probe as a test there, and run `./mvnw -B -pl data-prism-core -am test -Dtest=<probe class>`.
      - Each shape's result is one of four:
        - **leaks:** the raw value appears in the scrubbed output;
        - **refused**;
        - **inexpressible:** with the evidence, such as the annotation or type absent from that tag's sources, cited by path;
        - **unknown:** the probe could not be built or run, or the result is ambiguous.
    - The Outcome tabulates tag × shape with those four results, and keeps each probe's source in the Outcome or in a linked scratch branch.
    - The affected range for GHSA-f94h-6mxv-c957 fails closed. It is every tag on which any shape leaks, plus every tag with any shape whose result is unknown. Only a tag where every shape is refused or inexpressible, with evidence, is left out. That range replaces the advisory's current one when this task merges. It names 0.8.0 as the patched version, and it describes the shapes without real data.
- [ ] Every existing test in the touched modules passes unmodified.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-validation,data-prism-orchestration,data-prism-connectors-rest,data-prism-spring-boot-autoconfigure,data-prism-architecture -am verify` passes.

## OD-270-2: decided (a) by the owner, 2026-10-23
The fix ships in 0.8.0 only. There is no 0.7.2. On 2026-10-23 the owner also decided (H1 (a)) to accept the exposure of the public repros, and to draft the advisory now rather than at 0.8.0. The draft is GHSA-f94h-6mxv-c957. This task's Outcome confirms the affected range tag by tag and the pre-fix workaround from the matrix above, and the advisory is then updated with that range and workaround. The 0.8.0 release-cut brief, not yet filed, updates GHSA-f94h-6mxv-c957 with the patched version 0.8.0; this is listed in PLAN.md's release-cut obligations. The recommendation cited Central's File Count limit, whose grace period ends 2026-11-08, as the reason not to cut a 0.7.2.

## Out of scope
- Maps of records, and records inside nested containers (refused already; 0.9).
- A stricter-of policy merge for scalar records (rejected above).
- Unclassified records with `@JsonAnyGetter`, `@JsonUnwrapped` or other object shapes: they keep today's handling. A classified record with such a shape is refused by the backstop above.
- `ConfiguredJsonScrubbingEngine` and configured JSON sources, which have no records (249).
- `NamespaceCorrelationService`, which keeps `SourceTree.of(Object)`.
- `PrivacyEngineWiring` and the descriptor map (271).
- `docs/migration-0.8.md` and every other user page (261).
- Updating GHSA-f94h-6mxv-c957 with the patched version once 0.8.0 is released (the 0.8.0 release-cut brief).
