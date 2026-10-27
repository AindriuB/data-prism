# 270 — Fail closed when a classified record does not serialise as a plain object

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 248 and 271 have merged
**Depends on:** 271 (both insert into `CHANGELOG.md` `[Unreleased]` and `docs/plan/design/contract-register.md`; added 2026-10-24), 248 (both edit `JsonTreeScrubbingEngine.java`; this task also edits 248's `ScrubPlanDescriber`, adds cases to 248's two new tests, and builds on 248's final `SourceTree.readsAsPlainBean`)
**Wave:** 6 (filed 2026-10-23 at wave 5; moved to 6 on 2026-10-24 by the edge on 271)
**Decisions:** OD-270-1 decided (a) and OD-270-2 decided (a), both by the owner on 2026-10-23, and OD-270-3 decided (a) by the owner on 2026-10-27 (all below). OD-270-3 and the serializer verification at scrub time answer the external review of PR #161 at 994c623b (P1: a scrub-time check of property names alone lets a custom serializer write a sensitive value under a non-sensitive component's name). The repros under "The gap" stay in this public brief: the owner accepted that exposure on 2026-10-23 (review item H1), and the advisory GHSA-f94h-6mxv-c957 is drafted now rather than at the release cut. `readsAsPlainBean` stays strict, because strict fails closed (coordinator, 2026-10-23, review of PR #161).
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(the `apply`, `scrubArray` and `scalar` paths, for a value at a classified-record position; in `scrub`, the `SourceTree.of(Object, FieldMetadataResolver)` call and the pre-built-tree rule (Definitions); not the `describe` override)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ClassifiedShapes.java *(new, package-private: the one rule, shared by every site below, for "classified record", "plain object" and "plain root"; the root rule (OD-270-3); the component-exact tree check; the memo cache and its package-private miss counter)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java *(insertions only: the new startup refusals, for a classified-record position and for a classified root, their code constant and the `require(Class, FieldMetadataResolver)` overload)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java *(the `of` path and its `of(Object, FieldMetadataResolver)` overload; a new package-private non-refusing builder `lenientOf(Object, FieldMetadataResolver)`; the runtime verification walk over the Java object graph that both run before `READER.valueToTree` (Definitions); 248's `readsAsPlainBean`, with the two new conditions confined to that method and its `plainBean` helper; and the new package-private `readsAsPlainRoot(Class)`, split from its type-level half)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java *(the non-object value at a classified-record position, and the `SourceTree.of` call, only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/ClassifiedRecordSourceRefusalTest.java *(new: the two-source test; no main code in `data-prism-orchestration` changes)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriber.java *(the branch that decides such a type is ambiguous only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordShapeTest.java *(new: the repros and the scrub-time cases)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordPositionTest.java *(new: the runtime position check and the heap-pollution case)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedShapesTest.java *(new: the direct unit test of the component-exact tree check, and the cache test)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ClassifiedRecordProhibitedValuesTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/ScrubPlanDescriberTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/DescriptionConformsToScrubTest.java *(insertions only: the repro shapes as models)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismContractValidator.java *(the constructor, one new `ObjectProvider<FieldMetadataResolver>` field, and the resolver-aware model check in `afterPropertiesSet` only; the static `validateIntegrations` signature is unchanged)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PropertiesValidation.java *(the `dataPrismContractValidator` bean method only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/validation/ClassifiedRecordStartupTest.java *(new; in the `validation` sub-package, outside 210's `spring/boot/*.java` glob)*
- data-prism-spring-boot-autoconfigure/src/test/resources/classified-records/** *(new fixture files only)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ClassifiedShapesArchitectureTest.java *(new; 225 owns `ArchitectureTest.java`, so the rule lives in its own file)*
- docs/extending.md *(the source-model shape paragraph, about :343-368, only)*
- docs/plan/design/contract-register.md *(insertions only: the rows for the codes this task adds, in sections 3.1 and 3.5)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Security entry and one Breaking entry)*

## Goal
The scrubber applies the holding field's policy to whatever Jackson writes for a value, and descends only into JSON objects. When a record that carries its own classification is written as something other than a plain object of its components, the record's classification is never consulted, and the holder's policy, often `@NonSensitive`, releases the value. Shapes that do this include:
- a scalar, through `@JsonValue` or a custom or delegating serializer;
- an array, through `@JsonFormat(shape=ARRAY)`;
- an object-id reference, through `@JsonIdentityInfo`;
- a type-info wrapper.

The same happens when a classified record sits in a position typed `Object` or an interface. It also happens when a classified record, or the classified source model itself, is written as an object whose keys are its component names but whose values are swapped between them, so that a sensitive value sits under a `@NonSensitive` component's name (repro 8). The gap predates 0.8.

Close it for the whole family, not per annotation. A classified record is either serialised as a plain object, whose properties are exactly its components, each written under its own component's name, at a position declared as that record, or:
- the model is refused at startup;
- as a backstop, the response is refused at scrub time.

The value is never passed through. A classified source model (the root) is held to the root rule of OD-270-3 in the same two places.

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
8. Values swapped under the right names (found 2026-10-27, external review of PR #161 at 994c623b). `@SensitiveObject record Contact(@SensitiveData(classifications=CONTACT, namespace=EMAIL, suggestedAction=REDACT) String email, @NonSensitive(reason="x") String ref)`, and a `Swap` serializer that writes `{"email": <ref>, "ref": <email>}`. Every variant writes an object whose keys are exactly `Contact`'s component names, so a check of property names alone accepts it, and the email leaves under `ref`, which is `@NonSensitive`.
   - (a) type level: `@JsonSerialize(using=Swap.class)` on `Contact`, held by `@NonSensitive(reason="x") Contact contact`;
   - (b) holder level: `@NonSensitive(reason="x") @JsonSerialize(using=Swap.class) Contact contact`;
   - (c) container content: `@NonSensitive(reason="x") @JsonSerialize(contentUsing=Swap.class) List<Contact> contacts`;
   - (d) the `@JsonProperty` name swap, no custom serializer: `record Contact(@JsonProperty("ref") @SensitiveData(...) String email, @JsonProperty("email") @NonSensitive(reason="x") String ref)`. 248's `readsAsPlainBean` passes it, because `plainBean` compares the set of written names with the set of component names, and `SourceModels.refuseForeignProperties` passes it, because each renamed property is backed by some component's accessor;
   - (e) the root: an `@LlmExposedModel` record with `Contact`'s two components and a swapping class-level `@JsonSerialize(using=...)`. `SourceModels.refuseSerializer` allows a class-level serializer on a record today, and the root is at no classified-record position;
   - (f) the root with the `@JsonProperty` name swap of (d) on its own components.

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
    - properties that are exactly the record's components;
    - each property written under the name of the component whose accessor backs it (new in this task: 248's set comparison misses the name swap of repro 8 (d));
    - no property writer that suppresses nulls or empty values, so no `@JsonInclude` on the record or its components (new in this task: startup and scrub time then agree, because a plain object never omits a component).
  - It stays strict. A component renamed with `@JsonProperty`, and an `@JsonInclude` on the record, its components or the holding component, are therefore not plain. They are refused like the rest, and named in the Breaking entry.
  - The part of the type-level check that the root rule shares (exact serializer class, no filter id, no property under another component's name) is split into a new package-private `SourceTree.readsAsPlainRoot(Class)`. `readsAsPlainBean` calls it and adds the stricter conditions above, so the rule lives in one place.
  - `ClassifiedShapes` calls those two methods and implements no second serializer check. If a repro shows a method answering `true` for a shape that is not a plain object (a type-info wrapper, for example), fix the method itself and name the change in the Outcome.
- **Plain root:** exactly what `readsAsPlainRoot` answers `true` for, the root rule of OD-270-3: an exact `BeanSerializer` or `UnrolledBeanSerializer`, no filter id, and no property written under another component's name. An any-getter, `@JsonInclude` and a rename to a name that is no other component's stay allowed at the root.
- **Classified-record position:** a field value or array element whose type, under the existing `nestedType` rule, is a classified record.
- **Runtime verification (the scrub-time guard).** `SourceTree.of(Object, FieldMetadataResolver)` and `lenientOf` walk the Java object graph before `READER.valueToTree`, using the resolver's metadata and the scrubber's `nestedType` rule.
  - At each non-null value at a classified-record position (holder `H`, component `c`, declared record `R`), both must hold:
    - the value's runtime class is exactly `R`;
    - `readsAsPlainBean(H, c, R)`, where `H` is the holder's runtime class.
  - A classified root must pass `readsAsPlainRoot(source.getClass())` under the root rule.
  - On a failure, `of` refuses with `CLASSIFIED_RECORD_NOT_AN_OBJECT` (at `$` for the root), and `lenientOf` records the position.
  - The same walk performs the untyped-position check on each value. This replaces the earlier plan of `RecordsOnly` serializer-modifier callbacks, which run once per type and know nothing of the resolver.
  - The walk is bounded by the engine's `MAX_DEPTH` (16): beyond it, `of` refuses with `TOO_DEEP` and `lenientOf` records the position.
  - **Why this covers every position.** A record that holds a classified record is itself classified (the transitive rule). So every classified-record position sits inside a holder that was itself verified at its own position, or is the root, which the root rule verifies.
- **Pre-built tree.** A `JsonNode` passed to `scrub` has no Java object to verify.
  - With the default resolver, `exposed(ObjectNode)` is false, so `MODEL_NOT_EXPOSED` refuses it first, as today.
  - With a resolver that exposes it, any position whose `nestedType` is a classified record is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT`, whatever its shape; a `null` stays `null`. `lenientOf` records such a position.
  - The root rule does not apply to a `JsonNode` root.
- **Component-exact object:** a JSON object whose property names are exactly the classified record's component names: no extra key, none missing, none renamed. It is a second, tree-level check at each classified-record position (defence in depth), applied after the runtime verification and independently of `readsAsPlainBean`. It is no longer the only runtime guard: it cannot see which value sits under which name, so it does not stop repro 8. No end-to-end test can isolate it once the runtime verification is in place, so a direct unit test of the `ClassifiedShapes` tree check kills its mutants.
- **Cache.**
  - `ClassifiedShapes` memoises `readsAsPlainBean` and `readsAsPlainRoot` per (holder, component, type).
  - The cache keeps no class loader reachable beyond the holder's own lifetime (for example, a `ClassValue` keyed on the holder).
  - Classification is memoised at least per call.
  - A package-private miss counter exists for the cache test.
- **Untyped position:** a property or element whose declared type is `Object`, an interface, an abstract type, a type variable, an unbounded wildcard, or a raw collection element. A bounded wildcard is read by its upper bound. `List<? extends Email>` is a classified-record position for `Email`, not an untyped one; records are final, so the bound is exact.
- **`SourceValues.prohibited` never throws for this task's refusals.**
  - `DefaultContextOrchestrator.fetchAndScrub` (`:387`) calls it outside the per-source `try`, so a throw there would fail the whole request, not just the source.
  - Instead, where the scrubber refuses with `CLASSIFIED_RECORD_NOT_AN_OBJECT` or `CLASSIFIED_RECORD_UNTYPED_POSITION`, `prohibited` adds every scalar under that value, which fails closed, and returns normally. It reads the tree through `SourceTree.lenientOf(Object, FieldMetadataResolver)`, a new package-private builder. It applies the same runtime verification, position and shape checks as `of`, but where `of` would refuse with either code, or with `TOO_DEEP` from the walk, it records the position so that `prohibited` can prohibit every scalar under it. `SourceValues` never calls the refusing `of` overloads. `lenientOf` does not relax any pre-existing runtime refusal, such as `SOURCE_MODEL_NOT_A_RECORD` or `SOURCE_MODEL_GENERIC`: those still throw from `prohibited` as they do on `main`, outside the per-source `try`. That is an existing behaviour, named under Out of scope as a follow-up.
  - The refusal then comes from `scrubber.scrub`, inside the existing per-source `try`: that source is `REFUSED` and dropped whole, its values stay prohibited for every other source, and the other sources still answer.
  - No change to `DefaultContextOrchestrator` is needed. `ConfiguredJsonScrubbingEngine` calls `prohibited` after `scrub`, so it is unaffected.

## OD-270-1: decided (a) by the owner, 2026-10-23
- **Decided: refuse at startup, and refuse at scrub time.**
  - **At startup.** `SourceModels.require` refuses a model that reaches a classified record that is not a plain object, with the new code `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`. This includes a holding component whose `@JsonFormat`, `@JsonSerialize` or `@JsonInclude` makes it non-plain, and a record whose serializer or `@JsonProperty` names could write one component's value under another component's name (repro 8 (a) to (d)). The message reads `<Model.component>: <Record> serialised shape not verifiable as a plain object`, naming the component and the record's simple name, never a value.
  - **At scrub time.**
    - **First, the serializer is verified (the runtime verification, Definitions).** While the tree is built, before `READER.valueToTree`, `SourceTree.of` checks each non-null value at a classified-record position: its runtime class must be exactly the declared record, and `readsAsPlainBean` must hold for its holder's runtime class. A failure refuses the response with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at that path. The refusal message reads `classified record value is not an object of exactly its components`, with no value and no property name. This covers:
      - a scalar or an array;
      - a wrapper key (`@JsonTypeInfo` `WRAPPER_OBJECT`);
      - an appended or any-getter property;
      - a missing component (an `@JsonInclude` omission, for example);
      - a renamed one;
      - values swapped between component names, by a custom serializer or a `@JsonProperty` name swap (repro 8), which no check of the tree can see.
    - **Second, the tree is checked (defence in depth).** A value at a classified-record position that is not a component-exact object is refused with the same code and message.
    - While the tree is built, `SourceTree.of` refuses a classified record written at an untyped position, plain or not, with `CLASSIFIED_RECORD_UNTYPED_POSITION`. The message reads `classified record at a position whose declared type is not that record`, with no value and no class name.
  - **Why.**
    - It follows the narrowing of D-173-2 and the other `SOURCE_MODEL_*` refusals.
    - The author learns at startup and fixes the model by following the 0.8 upgrade instructions ("Two kinds of advice" below).
    - A redacted array or object id is not a value anyone wants delivered.
    - Every refused path stays omitted from `describe`, which 248 already does.
  - **Cost.** A breaking change for any adopter model with these shapes. 0.8 accepts breaking changes (D-1.0-5).
- **Rejected when the task was filed:** a stricter-of merge of the holder's policy with the record's components' policies. It answers only the scalar case, needs a namespace rule for `SYNTHESIZE`, `TOKENIZE` and `HASH`, and has no meaning for an array or an object id.

## OD-270-3: decided (a) by the owner, 2026-10-27
- **Decided: the root rule.** This task also refuses a classified source model (the root) whose serializer could write a component's value under another component's name. That covers a class-level custom serializer (`@JsonSerialize(using=...)`), `@JsonValue`, and a `@JsonProperty` name that is another component's name (repro 8 (e) and (f)).
  - **At startup.** `SourceModels.require` refuses such a root with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`. The message reads `<Model>: <Model> serialised shape not verifiable as a plain object`, naming the model's simple name, never a value.
  - **At scrub time.** `SourceTree.of` refuses it with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at `$`, with the message of OD-270-1, before the tree is built.
  - **What the root must be.** A plain root (Definitions): written by an exact `BeanSerializer` or `UnrolledBeanSerializer`, with no filter id, and with no property written under another component's name.
  - **Still allowed at the root.** An any-getter (`UndeclaredKeyRefusalTest.Open`, `UndeclaredPropertyNameTest`), `@JsonInclude`, and a rename to a name that is no other component's. They are not value swaps, and the existing undeclared-key handling applies to what they write.
  - **Why.** The root is at no classified-record position, so without this rule 270 never checks it, and repro 8 (e) and (f) leak there exactly as (a) to (d) do at a nested position.
  - **Cost.** A breaking change for a classified source model with a class-level serializer or `@JsonValue`, which `SourceModels.refuseSerializer` and `docs/extending.md` allow today. 0.8 accepts breaking changes (D-1.0-5).
  - **Advisory.** The root shapes are added to GHSA-f94h-6mxv-c957. The coordinator does that, not this task; this task's Outcome covers repro 8 in the matrix and the per-tag probes like every other repro.

## Two kinds of advice (external review, 2026-10-26)
Keep these apart. Each place in this task names the one it uses.
- **Pre-fix workaround** (0.7.x only; for the GHSA-f94h-6mxv-c957 advisory text): mark the holding field with a classification, for example `@SensitiveData(classifications=CONTACT, suggestedAction=REDACT)`, instead of `@NonSensitive`. The scrubber then applies the holder's policy to the scalar. This holds only before 0.8. From 0.8.0 the same model is refused at startup, because the record's shape is still not a plain object.
- **0.8 upgrade instructions: the fix list.** This exact text is used verbatim in `docs/extending.md`, the CHANGELOG Breaking entry and 261's migration notes, so the three cannot drift:

  > To fix a model refused with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, make each classified record serialise as a plain JSON object of exactly its components: remove `@JsonValue`; remove any custom, delegating or converting serializer (`@JsonSerialize(using=...)`, `contentUsing=...` or `converter=...`, on the record or on the holding component); remove `@JsonFormat(shape=ARRAY)` from the record and from the holding component; remove `@JsonIdentityInfo`; remove `@JsonTypeInfo`; remove `@JsonAppend` and `@JsonAnyGetter`; undo any `@JsonProperty` rename of a component; and remove `@JsonInclude` from the record, its components and the holding component. A classified source model (the response type itself) must be written by Jackson's own bean serializer, with no property under another component's name: remove `@JsonValue` and any class-level `@JsonSerialize(using=...)` or `converter=...` from it, and do not give a component another component's name with `@JsonProperty`. If the value really is a single scalar, make the field itself a classified scalar (for example a `String` with `@SensitiveData`) instead of a classified record. To fix `CLASSIFIED_RECORD_UNTYPED_POSITION`, declare the position as the record type instead of `Object`, an interface, `Record`, a type variable, an unbounded wildcard or a raw collection.

  Classifying the holding field does not resolve the startup refusal, and none of the three places suggests it.

## Startup with the wired resolver (M3)
`DataPrismContractValidator` gains an `ObjectProvider<FieldMetadataResolver>`, passed from `PropertiesValidation.dataPrismContractValidator`. 271's identity check lives in `PrivacyEngineWiring`, not here. After the existing static checks, `afterPropertiesSet` calls `SourceModels.require(adapter.responseType(), resolver)` for every adapter, so a record classified only by a descriptor is refused at startup. The refusal is wrapped exactly as `validateIntegrations` wraps `require` today: a `DataPrismConfigurationException` with the same code and the source name. The public static `validateIntegrations` keeps its signature and its annotation-only check, so its existing callers and tests are untouched.

## Context
Line numbers are as on `origin/wave/0.8-wave4`, where 248's code lives; re-check them on the base commit.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java:109-127 (`scrub`: `exposed` at :114, `SourceTree.of` at :119) and :307-448 (`apply`, `scrubArray`, `scrubNestedObject`, `nestedType` and `scalar`). The holder's policy reaches `scalar` with no check of the declared type. `MAX_DEPTH` is :72.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java:101-153 (`refuseSerializer`, `refuseForeignProperties`) and :225-283 (`walk`): where a startup refusal joins the existing ones. `refuseForeignProperties` returns early for a serializer that is not a bean serializer (:130), so a class-level serializer on a record passes, and it accepts any property backed by some component's accessor (:147), so a name swap passes.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceTree.java:99-247 (`READER`, the `RecordsOnly` modifier, `ClassLevelSerializers`), :271-294 (248's `readsAsPlainBean`), :435-459 (`plainBean`, whose set comparison at :446-458 misses a name swap) and :465-477 (`of`). This is the mapper whose output is judged, and the one check of it. `of(Object)` is also called by `NamespaceCorrelationService` (orchestration, :59), which keeps the one-argument form and gets the same verification with the annotation-only resolver.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java:37 and :44-81: `collect` and `descend` only follow objects.
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java:387 and :390: `fetchAndScrub` calls `SourceValues.prohibited`, then `scrubber.scrub`, its only way into the engine.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java:98-99 and :112: a configured JSON source is scrubbed as a pre-built `JsonNode` under `ConfiguredJsonFieldMetadataResolver`, whose types are leaf types or the final non-record `ConfiguredJsonNestedCatalogueSlotN` tokens (`ConfiguredJsonNestedCatalogueSlots.java:29`), so it has no classified-record position and 249 is unaffected; every other source goes to `javaFirst.scrub`.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismContractValidator.java:51 and PropertiesValidation.java:107-116: the startup check and its bean.
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
  - every repro under "The gap" (1 to 8, each variant separately);
  - the property-level `@JsonFormat(shape=ARRAY)` variant of repro 2.

  The Outcome reports the per-repro result. Each of 1, 2, 3, 4, 6, 7 and 8 (each of (a) to (f)) emits `ada@example.invalid` (or the Email sentinel) somewhere in the scrubbed tree. The exception is repro 6's type-variable variant, which `main` already refuses with `SOURCE_MODEL_GENERIC`. The Outcome records whether that comes from `SourceModels.require` or from `refuseSerializer` at runtime, and the variant is not required to leak.
- [ ] **Startup refusal.** `SourceModels.require` refuses each of the following with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`:
  - the holders of repros 1, 2 (class-level and property-level), 3, 4 (all four containers), 5 (both `@JsonTypeInfo` variants and `@JsonAppend`) and 7 (all three variants). For repro 7's third variant the message starts `Holder.w:`, the wrapper's holder, not `Wrapper.inner:`, which pins that the transitive rule refused it;
  - a classified record with a `@JsonProperty`-renamed component;
  - a classified record that carries `@JsonInclude` on the record or on a component, and a holding component of a classified record that carries it;
  - the holders of repro 8 (a) to (d).

  The message is `<Model.component>: <Record> serialised shape not verifiable as a plain object` and contains no value.
- [ ] **Root rule at startup (OD-270-3).** `SourceModels.require` refuses repro 8 (e) and (f), and a classified root with `@JsonValue`, with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT` and the message `<Model>: <Model> serialised shape not verifiable as a plain object`, with no value.
- [ ] **Still allowed at startup.** A record with `@JsonValue` passes when it is not classified under the transitive rule: its components are all `@NonSensitive`, and none of their types, at any depth, is a classified record. An unclassified wrapper also passes. Repro 7's `Wrapper` does not pass: it is refused. At the root, a classified model with an any-getter, with `@JsonInclude`, or with a component renamed to a name that is no other component's passes. Every model in the existing `SourceModelsTest` passes unchanged, and so do `UndeclaredKeyRefusalTest` and `UndeclaredPropertyNameTest`.
- [ ] **Scrub-time backstop.** With the startup check bypassed (the engine is called directly), each of the following is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at the holding path:
  - repros 1 to 5 and 7, including the `WRAPPER_OBJECT` and `@JsonAppend` variants, which write objects;
  - repro 8 (a) to (d), and (e) and (f) at `$`;
  - a classified record with an `@JsonAnyGetter` that adds a key;
  - one with `@JsonInclude(NON_NULL)`, both with a `null` component (the serialised object misses it) and with every component non-null;
  - one with a `@JsonProperty`-renamed component.

  The message is `classified record value is not an object of exactly its components`. Neither the message nor the path contains a value or a property name.
- [ ] **Repro 8 end to end.** For each of (a) to (f): the base commit leaks `ada@example.invalid` (characterisation); the direct engine call refuses as above; `SourceModels.require` refuses with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`; and `SourceValues.prohibited` contains `ada@example.invalid` without throwing.
- [ ] **Heap pollution.** A `List<Contact>` filled through a raw cast with a `Decoy` record, whose serializer writes `Contact`'s component names, is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT` at the element's path. This kills the mutant that checks the declared class only.
- [ ] **Pre-built tree.** A `JsonTreeScrubbingEngine` with a test resolver that exposes `ObjectNode` and reports `valueType=Contact` for a field, given a pre-built tree with a component-exact `ObjectNode` in that field, refuses with `CLASSIFIED_RECORD_NOT_AN_OBJECT`, and `SourceValues.prohibited` on the same tree contains every scalar under that field. A `null` in that field stays `null`.
- [ ] **Cache.** `ClassifiedShapesTest` scrubs a 1,000-element `List<Contact>`: the miss counter rises at most once per distinct (holder, component, type) key, and not at all on a second scrub of the same source.
- [ ] **Tree check, directly.** `ClassifiedShapesTest` calls the component-exact tree check of `ClassifiedShapes` with an object that has an extra key, one that misses a key, and one with a renamed key, and each is rejected; an exact one is accepted.
- [ ] **Untyped positions.** Repro 6, in each of its first six declarations (`Object`, interface, `java.lang.Record`, `List<?>`, raw `List`, `Optional<Object>`), is refused while the tree is built, with `CLASSIFIED_RECORD_UNTYPED_POSITION`, through the engine. `SourceValues.prohibited` on the same source returns normally and contains every scalar under the value. A `List<? extends Email>` holding plain `Email` objects is descended into as a classified-record position, not refused; holding repro 1's `Email` it is refused with `CLASSIFIED_RECORD_NOT_AN_OBJECT`. The same holds for a plain, classified record in an `Object` position. An unclassified record at an untyped position keeps today's behaviour, pinned by a test.
- [ ] **Descriptor-only classification, scrub time.** A record classified only by a descriptor (`DescriptorFieldMetadataResolver` classifying the `@JsonValue` component of an otherwise `@NonSensitive` record) is refused at scrub time with `CLASSIFIED_RECORD_NOT_AN_OBJECT`. At an untyped position it is refused with `CLASSIFIED_RECORD_UNTYPED_POSITION`.
- [ ] **Descriptor-only classification, startup (M3).** `ClassifiedRecordStartupTest` starts a context whose descriptor file (under `src/test/resources/classified-records/`) classifies the `@JsonValue` component of an otherwise `@NonSensitive` record. One adapter returns a model that holds it. Startup fails with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT` and the source name. Without the descriptor, the same context starts. The bean inventories are unchanged.
- [ ] **Unchanged behaviour (characterisation, each pinned by a test):**
  - a classified record written as a plain object at a declared position is descended into as today;
  - a `null` at a classified-record position stays `null`;
  - a record with `@JsonValue` that is unclassified under the transitive rule (no classified component at any depth) takes the holder's policy as today. Repro 7 is not such a record, and no test pins its current output except the characterisation item.
- [ ] **`lenientOf` parity.** For every existing fixture in the `data-prism-core` engine tests that `SourceTree.of` accepts, `lenientOf` returns an equal tree and records no position. The test iterates the fixtures and compares the trees with `JsonNode.equals`. Mutant killed: a `lenientOf` that drops a property.
- [ ] **Prohibited values.** `SourceValues.prohibited` contains every scalar under a value at a classified-record position that fails the runtime verification (repros 1 to 5, 7 and 8, including the root of 8 (e) and (f)), and under every classified record at an untyped position (repro 6). It throws for none of them. `ClassifiedRecordProhibitedValuesTest` shows this, and that on the base commit the set misses them.
- [ ] **One bad source does not fail the request.** `ClassifiedRecordSourceRefusalTest` builds a `DefaultContextOrchestrator` with two sources. Source `a` returns repro 1's `Holder`; source `b` returns a well-formed model.
  - The response is answered: `a` is `REFUSED` with disposition `a:<refused>` = `REFUSED:CLASSIFIED_RECORD_NOT_AN_OBJECT`, and `b` is `ANSWERED`.
  - `a`'s email is in the prohibited set.
  - A second case uses repro 6's `Object` variant for `a`, with `REFUSED:CLASSIFIED_RECORD_UNTYPED_POSITION`.
  - Mutant killed: letting `prohibited` throw fails the whole request, which fails this test.
- [ ] **Describer.** `ScrubPlanDescriber` decides "ambiguous" through `ClassifiedShapes` and nothing else. `DescriptionConformsToScrubTest` gains repros 1 to 4. No field the scrubber refuses or changes is described `VERBATIM`, and the existing cases pass unchanged.
- [ ] **One check.** `ClassifiedShapesArchitectureTest` (ArchUnit) asserts four things:
  - `ClassifiedShapes` calls `SourceTree.readsAsPlainBean` and `SourceTree.readsAsPlainRoot`;
  - `JsonTreeScrubbingEngine.scrub` calls `SourceTree.of(Object, FieldMetadataResolver)` and never `SourceTree.of(Object)`;
  - `SourceValues` calls `SourceTree.lenientOf` and calls neither `SourceTree.of` overload. Mutant: switching `SourceValues` back to `of` fails this rule, and also fails the two-source test, because the whole request then fails. ArchUnit sees direct calls only; an indirect call through another class is caught by the two-source test, which is the accepted backstop;
  - no class in `io.github.aindriub.dataprism.core.engine` other than `SourceTree` depends on `tools.jackson.databind.ser.BeanSerializer`, `tools.jackson.databind.ser.UnrolledBeanSerializer` or `tools.jackson.databind.ser.impl.ObjectIdWriter`. Use the class names as they are on the base commit.
- [ ] **Mutation proofs, each recorded in the Outcome with the test that fails:**
  - remove the whole scrub-time backstop (the runtime verification and the tree check): the direct-engine and descriptor-only tests fail;
  - remove the runtime verification but keep the tree check: repro 8 (a) to (d) fail through the direct engine call;
  - check the declared class only, not the runtime class: the heap-pollution test fails;
  - drop the name-to-accessor condition from `readsAsPlainBean` and `readsAsPlainRoot`: repro 8 (d) and (f) fail;
  - drop the root rule: repro 8 (e) and (f) fail, at startup and through the direct engine call;
  - drop the pre-built-tree rule: the pre-built-tree test fails;
  - drop the suppression condition: `@JsonInclude(NON_NULL)` is then refused only when a component is `null` (by the tree check), so its every-component-non-null case fails;
  - weaken the tree check to "is a JSON object" (dropping the exact-property-names comparison), or to "no extra keys" (allowing missing ones): the direct `ClassifiedShapesTest` tree-check cases fail. No end-to-end test kills these, because the runtime verification refuses the same shapes first;
  - remove the startup check from `SourceModels.require`: the startup tests fail;
  - make `ClassifiedShapes` treat every record as plain: repros 1 to 5, 7 and 8 fail;
  - make the classification rule non-transitive (components' own annotations only): repro 7 fails;
  - drop the object-id condition from `readsAsPlainBean`: repro 3 fails;
  - remove the untyped-position check: each of repro 6's first six declarations fails, and so does the direct call for the type-variable case.
- [ ] **Codes.** `RefusalCodes.sanitise` returns each new code unchanged: `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, `CLASSIFIED_RECORD_NOT_AN_OBJECT` and `CLASSIFIED_RECORD_UNTYPED_POSITION`. A test asserts it. The contract register gains a row for each.
- [ ] **`docs/extending.md`, source-model shape paragraph.**
  - **The "stay allowed" clause, `docs/extending.md:364-366`.** The sentence begins "A `@JsonSerialize(using=...)` on a record or its components, `@JsonAnyGetter` and `@JsonValue` are the model author's explicit choice and stay allowed (the engine still classifies what they produce)". Reword it so that it applies to unclassified records only: "On an unclassified record, a `@JsonSerialize(using=...)` on the record or its components, `@JsonAnyGetter` and `@JsonValue` are the model author's explicit choice and stay allowed; the holding field's classification applies to what they produce." The old parenthesis, the claim this task found false, is gone.
  - Add these sentences verbatim, after the reworded one: "A record is classified if any of its components carries a classification (`@SensitiveData` or `@InternalIdentifier`, in code or through a model descriptor), or if any component's type, through arrays, collections and `Optional`, is itself a classified record. A classified record must serialise as a plain JSON object whose properties are exactly its components, each under its own name, and must be held at a position declared as that record. A classified source model must be written by Jackson's own bean serializer, with no property under another component's name. Startup refuses any other shape with `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, and a response that holds a classified record at any other position is refused with `CLASSIFIED_RECORD_UNTYPED_POSITION`." Then the fix list from "Two kinds of advice", verbatim.
  - The paragraph does not give the pre-fix workaround (classifying the holding field), which does not resolve the refusal.
- [ ] **`CHANGELOG.md` `[Unreleased]`.**
  - Security entry: what leaked, under which shapes, and since which version (as established below).
  - Breaking entry:
    - names `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT` and `CLASSIFIED_RECORD_UNTYPED_POSITION`;
    - names the shapes now refused, including the `@JsonProperty` rename, `@JsonInclude` on the record, its components or the holder, and the value-swap shapes of repro 8 (a custom serializer on the record, the holding component or a container's content, and a `@JsonProperty` name swap);
    - names the root rule (OD-270-3): a classified source model with a class-level serializer, `@JsonValue`, or a component given another component's name is now refused, although `docs/extending.md` allowed a class-level serializer on a record before;
    - names `DataPrismContractValidator`'s public constructor change (a new `ObjectProvider<FieldMetadataResolver>` parameter);
    - gives the fix list from "Two kinds of advice", verbatim, and nothing else as a fix. It does not suggest classifying the holding field.
  - Text for `docs/migration-0.8.md` goes in the Outcome under "for the migration notes" (261 owns that page and depends on this task).
- [ ] **Security advisory GHSA-f94h-6mxv-c957 (OD-270-2).** The advisory is already drafted (owner decision, 2026-10-23). The Outcome has a heading of its own with the text to update it with: the confirmed affected range, the pre-fix workaround ("Two kinds of advice"), the 0.8 upgrade instructions, and the patched version 0.8.0. Before it states the workaround or the range, the Outcome does both of the following:
  - **Workaround matrix.** On `v0.7.1`, the Outcome runs every repro shape (1 to 8, each variant) against three things:
    - **each holder annotation:**
      - `@NonSensitive`;
      - `@SensitiveData` with each implemented action (`PASS_THROUGH`, `REDACT`, `REMOVE`, `SYNTHESIZE`, `TOKENIZE`, `HASH`, `GENERALIZE`);
      - `@InternalIdentifier`;
      - unannotated;
      - classified by a descriptor;
    - **each bundled profile:** DEFAULT and STRICT;
    - **one custom profile** that maps the holder's classification to `PASS_THROUGH`.

    It records shape × annotation × profile as leaks or not.
  - **The advisory's 0.7.x workaround is scoped to what that table proves.**
    - It lists the shapes for which classifying the holding field stops the leak under each profile, and the annotations that do so.
    - It states plainly that every other shape has no workaround and needs 0.8.0.
    - It names the annotations and profiles that still leak, as not workarounds: `@SensitiveData` with `PASS_THROUGH`, and any profile that maps the holder's classification to `PASS_THROUGH`.
    - It says the workaround applies only before 0.8.0, and that upgrading requires the fix list.
    - It states the workaround as verified on `v0.7.1` only, the tag the matrix ran on. Earlier affected tags are listed as unverified for the workaround; they are affected, but whether classifying the holder stops the leak there was not tested.
  - **Affected versions.**
    - Cover every release tag from `v0.1.0` (or the earliest release tag, if that is later) through `v0.7.1`. The scalar repros (1 and 6) need no record descent, so no tag is skipped on that ground. The Outcome also names the commit that introduced nested-record descent, for the advisory's description.
    - For each tag, write a leak probe against that tag's own API: its annotations, its engine constructor and its resolver. Do not copy this task's repro classes.
      - The probe expresses each repro shape (1 to 8, each variant) in that API.
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
- `NamespaceCorrelationService`, which keeps `SourceTree.of(Object)` and gets the same verification through it; it produces no output.
- Pre-existing runtime refusals thrown from `SourceValues.prohibited` (`SOURCE_MODEL_NOT_A_RECORD`, `SOURCE_MODEL_GENERIC` and the others `SourceTree.of` raises today). They are raised outside `fetchAndScrub`'s per-source `try`, so one such source fails the whole request, on `main` as well. That is a candidate follow-up, recorded in the Outcome.
- An explicit record accessor that returns another component's value (`public String ref() { return email; }`). Jackson writes it under the right name through the bean serializer, so no serializer check can see it.
- An unclassified record that swaps an unannotated value into a `@NonSensitive` slot: it carries no classification for the swap to defeat, and keeps today's handling.
- Any-getter keys at the root that collide with component names: existing behaviour, unchanged by the root rule.
- Third-party `ScrubbingEngine` implementations: the verification is in `JsonTreeScrubbingEngine` and `SourceTree`.
- Reusing one serialization context across 248's `readsAsPlainScalar` and `overridesSerialization`: the Outcome may describe it as a follow-up, and does not change them.
- `PrivacyEngineWiring` and the descriptor map (271).
- `docs/migration-0.8.md` and every other user page (261).
- Updating GHSA-f94h-6mxv-c957 with the patched version once 0.8.0 is released (the 0.8.0 release-cut brief).
