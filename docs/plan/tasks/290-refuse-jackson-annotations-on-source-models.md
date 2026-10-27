# 290 — Refuse Jackson annotations on source models at startup, and document the 0.8 source-model rule

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/wave/0.8-wave4` once 288, 210 and 271 have merged into it. One PR for this task, targeting `wave/0.8-wave4`. PR #163 does not merge until 210, 288, 289 and 290 have merged into it (owner decision 3, 2026-10-27).
**Depends on:** 288 (both edit `SourceModels.java`; this task replaces the `refuseForeignProperties` that 288 leaves in place, and documents 288's codes), 271 (both insert into `CHANGELOG.md` `[Unreleased]` and `docs/plan/design/contract-register.md`; 270 had this edge for the same reason), 210 (this task writes 210's contract-register changes from 210's Outcome, because 210 shares wave 5 with 271, which owns the register there)
**Wave:** 6 (filed 2026-10-27)
**Decisions:**
- **Owner decision 1, 2026-10-27.** Startup refuses Jackson annotations on source records. This is a message for the model's author, not a security guard: 288's walker ignores every such annotation, so the list does not have to be complete for safety. Refusing rather than silently ignoring is confirmed at filing: an ignored `@JsonIgnore` would start emitting a component its author meant to hide, and an ignored `@JsonFormat` or `@JsonProperty` would change field text or names without a word.
- **OD-270-1 and OD-270-3 (retired 270) are superseded.** Their startup code `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT` was never shipped and is not added; this task's `SOURCE_MODEL_JACKSON_ANNOTATION` takes its place in startup, and the scrub-time codes `CLASSIFIED_RECORD_NOT_AN_OBJECT` and `CLASSIFIED_RECORD_UNTYPED_POSITION` are replaced by 288's codes. OD-270-2 (fixed in 0.8.0 only, no 0.7.2) stands.
- **D-1.0-5.** Breaking change accepted for 0.8: this withdraws the documented allowance of `@JsonSerialize`, `@JsonAnyGetter`, `@JsonValue`, `@JsonUnwrapped`, `@JsonNaming` and `@JsonFormat` on source records (`docs/extending.md:364-366`, `docs/migration-0.6.md:259-261`).
- **Owner decision 4, 2026-10-27.** The `@JsonProperty` name-swap and swapping-serializer leak in 0.7.x and earlier is documented only: the CHANGELOG sentence below, verbatim. No advisory entry, no 0.7.x patch.
- **OD-290-1 and OD-290-2 are open** (below).

**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java *(the annotation refusal, its code constant, and the removal of `refuseForeignProperties`)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelAnnotationRefusalTest.java *(new)*
- docs/extending.md *(the "A response type must be a record" box, about :340-368 on wave4, only)*
- docs/migration-0.6.md *(one superseded-in-0.8 note after the "Still allowed" paragraph, about :259-264, only)*
- docs/architecture.md *(added lines only: one dated decision, and a supersession pointer at the 2026-10-08 map-key decision, about :380-395)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Security entry and one Breaking entry)*
- docs/plan/design/contract-register.md *(sections 3.1, 3.5 and 6.4 only: the rows named under Acceptance)*

## Goal
After 288, a Jackson annotation on a source record does nothing: the engine reads each component itself. An author who wrote one expected it to change the output, so refuse it at startup with a message that names it and says what to do instead. Document the new source-model rule where the old allowance was documented, record the decision, and record in the CHANGELOG both the breaking change and the 0.7.x gap it closes.

## The rule
`SourceModels.require` (startup, through `DataPrismContractValidator`) refuses with `SOURCE_MODEL_JACKSON_ANNOTATION` any source model that carries a Jackson annotation, as OD-290-1 decides, on:
- the record class, or an interface it implements;
- a component, as reflected on the component, its field, its accessor and the canonical constructor's parameter;
- any other method the record or its interfaces declare;
- an enum type reached from the model, or one of its constants.

A Jackson annotation is one whose type is in `com.fasterxml.jackson.annotation` or `tools.jackson.databind.annotation`, or is itself annotated `@JacksonAnnotationsInside`. The walk covers every record and enum the existing `walk` reaches. The message is `<Model.component>: @<Annotation> has no effect on a source model; remove it` (or `<Model>: ...` for the class), naming the annotation's simple name and never a value. The refusal replaces `refuseForeignProperties`, whose cases (`@JsonProperty` or `@JsonGetter` on a non-component method, `@JsonAppend`) it covers. Scrub time does not apply this rule: the walker already ignores the annotations.

## OD-290-1 (open): which Jackson annotations are refused
The owner listed `@JsonValue`, `@JsonSerialize` (including `contentUsing`), `@JsonFormat` (including `shape=ARRAY`), `@JsonIdentityInfo`, `@JsonTypeInfo`, `@JsonAppend`, `@JsonAnyGetter`, `@JsonUnwrapped`, `@JsonProperty` renames, `@JsonIgnore`, `@JsonInclude` and `@JsonNaming`, and said the list need not be complete. One consequence was not on that list: a source record is also what an adapter deserialises the upstream response into. `RestDataSourceAdapter.java:86` reads the body straight into the record, and 278's generated adapters will too. Deserialisation-only annotations (`@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`, `@JsonSetter`) change nothing the engine reads, and they are how a record maps upstream JSON names.
- **(a) Refuse every Jackson annotation.** Simplest rule. Cost: an adapter whose upstream names differ from the component names must deserialise into its own DTO and copy it into the record.
- **(b) Refuse every Jackson annotation except a closed list of deserialisation-only ones:** `@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`, `@JsonSetter`, and `@JsonIgnoreProperties` only with `ignoreUnknown = true` and no names. Each allowed annotation is pinned by a test that shows the tree is unchanged by it. Cost: a list to keep closed.
- **(c) Refuse only the owner's list.** Cost: an unlisted output annotation, for example `@JsonPropertyOrder` or `@JsonRawValue`, is silently ignored, which the decision above rejects.
- **Recommendation: (b).** It refuses exactly what claims to change output, and keeps hand-written and generated adapters able to read upstream names.

## OD-290-2 (open): the 0.7.x advisory GHSA-f94h-6mxv-c957 now that 270 is retired
270's Outcome was to confirm the advisory's affected range tag by tag (a leak probe per release tag) and to verify the pre-fix workaround (classify the holding field) against a matrix of holder annotations and profiles. PLAN.md's release-cut obligations rely on that Outcome. None of the new tasks carries it.
- **(a) This task carries it,** in its Outcome, exactly as 270's "Security advisory" acceptance item specified.
- **(b) Drop the per-tag proof and the workaround.** The advisory states every release up to and including 0.7.1 as affected (the fail-closed range that 270 would have fallen back to), offers no workaround, and names 0.8.0 as the patched version. The coordinator updates the draft; no task does.
- **(c) Move it to the 0.8.0 release-cut brief** when that is filed.
- **Recommendation: (b).** The per-tag work existed to make a narrower claim; the broad claim is the safe one, and a workaround that was never verified should not be published.

## Context
Line numbers are as on `origin/wave/0.8-wave4` (de5cf9dc).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java:129-153 (`refuseForeignProperties`), :206-210 (`objectShaped`, an enum with `@JsonFormat(shape=OBJECT)`: now covered by the rule), :225-283 (`walk`).
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java:175-221, :311-455: fixtures that pin today's allowances (`Annotated`, `@JsonAnyGetter`, `@JsonValue`, `@JsonSerialize`, `@JsonAnyGetter(enabled=false)`) and foreign-property refusals. Each allowance case flips to a refusal; each foreign-property case keeps refusing, now with the new code.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismContractValidator.java:51: where startup calls `require` for every adapter's response type. Not edited.
- docs/extending.md:340-368 (the box), docs/migration-0.6.md:250-264, docs/architecture.md:380-395 (the 2026-10-08 decision that rejected refusing `@JsonSerialize(keyUsing=...)` on source records, now reversed).
- 288's Outcome: the triggers and messages of `SOURCE_MODEL_RECORD_NOT_DECLARED`, `SOURCE_MODEL_NOT_READABLE` and `SOURCE_TREE_NOT_COMPONENT_EXACT`, and OD-288-1's outcome (the fix list's `@JsonAnyGetter` sentence follows it).
- 210's Outcome: its codes and properties for the register.
- docs/plan/design/contract-register.md section 3.5, row `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD`: the row shape.
- 261 owns `docs/migration-0.8.md` and takes this task's "for the migration notes" text from its Outcome.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `SourceModelAnnotationRefusalTest` refuses, with `SOURCE_MODEL_JACKSON_ANNOTATION` and the message above, a source model carrying each annotation in the owner's list, on the record and on a component, and each of 270's repro models 1 to 5, 7 and 8 (`docs/plan/tasks/retired/270-classified-records-must-serialise-as-objects.md`, "The gap"). It also refuses a Jackson annotation on an implemented interface's method, on an enum constant reached from the model, and a custom annotation meta-annotated `@JacksonAnnotationsInside`. No message contains a value.
- [ ] As OD-290-1 decides: under (b), each allowed deserialisation-only annotation starts, and a test shows the walker's tree is the same with and without it.
- [ ] `refuseForeignProperties` is gone; `SourceModelsTest`'s foreign-property cases refuse with `SOURCE_MODEL_JACKSON_ANNOTATION`, and its allowance cases are inverted; the Outcome lists each changed case.
- [ ] A model without Jackson annotations passes `require` as before: every other `SourceModelsTest` case is unchanged.
- [ ] `RefusalCodes.sanitise("SOURCE_MODEL_JACKSON_ANNOTATION")` returns it unchanged (asserted).
- [ ] **The fix list.** This exact text, called "the fix list", appears verbatim in `docs/extending.md`, in the CHANGELOG Breaking entry and in the Outcome's "for the migration notes" text. If OD-288-1 or OD-290-1 is decided other than as recommended, the coordinator amends the two marked sentences before the task starts.

  > To fix a model refused with `SOURCE_MODEL_JACKSON_ANNOTATION`, remove the annotation the message names. From 0.8, Data Prism reads a source record itself: each component is written under its own name with its own value, and Jackson annotations on the record, its components, its methods and the enums it holds change nothing. If the annotation renamed a component, rename the component. If it changed how a value is written (`@JsonValue`, `@JsonSerialize`, `@JsonFormat`), give the component the type you want written: for a single sensitive value, a `String` with `@SensitiveData` instead of a record that serialises as one; for a formatted date, a `String` holding the text. If it hid a component (`@JsonIgnore`), remove the component or classify it. [OD-288-1 (a):] If it added keys to the object (`@JsonAnyGetter`), hold them in a `Map<String, Object>` component instead. [OD-290-1 (b):] `@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`, `@JsonSetter` and `@JsonIgnoreProperties(ignoreUnknown = true)` stay allowed, because they affect only how an adapter reads the upstream response. To fix `SOURCE_MODEL_RECORD_NOT_DECLARED`, declare the position as the record type itself, not `Object`, an interface, `Record`, a type variable, an unbounded wildcard or a raw collection. To fix `SOURCE_MODEL_NOT_READABLE`, open the model's package to the `data-prism-core` module.

  The bracketed markers are not part of the text.
- [ ] **`docs/extending.md`.** The box states the 0.8 rule in two or three sentences (the engine reads components itself; Jackson annotations are refused at startup; a record must sit at a position declared as that record), then the fix list. The sentence at :364-366 that allows `@JsonSerialize`, `@JsonAnyGetter` and `@JsonValue` is gone, and so is the claim that the engine classifies what they produce.
- [ ] **`docs/migration-0.6.md`.** After the "Still allowed" paragraph, one line: "Superseded in 0.8.0: Jackson annotations on source models are refused at startup with `SOURCE_MODEL_JACKSON_ANNOTATION`; see `docs/migration-0.8.md`."
- [ ] **`docs/architecture.md`.** One dated decision (2026-10-27): the engine builds the scrub tree of a source record itself, Jackson writes leaves only, and Jackson annotations on source models are refused at startup; it supersedes the D-173-2 allowance and the 2026-10-08 rejection of refusing `@JsonSerialize(keyUsing=...)`, which gains a one-line pointer to it.
- [ ] **`CHANGELOG.md` `[Unreleased]`, Security, this entry verbatim (owner decision 4):**

  > Known gap in 0.7.x and earlier, closed in 0.8.0: on a source record, a `@JsonProperty` rename that swaps two components' names, or a class-level serializer that writes one component's value under another component's name, could release a sensitive value under the policy of a `@NonSensitive` field. Every release up to and including 0.7.1 is affected. 0.8.0 fixes this by construction: the engine reads each record component itself and writes it under its own name, and startup refuses Jackson annotations on source models. There is no 0.7.x patch; upgrade to 0.8.0.
- [ ] **`CHANGELOG.md` `[Unreleased]`, Breaking:** names `SOURCE_MODEL_JACKSON_ANNOTATION`, `SOURCE_MODEL_RECORD_NOT_DECLARED` and `SOURCE_MODEL_NOT_READABLE`; says that the allowance of `@JsonSerialize`, `@JsonAnyGetter`, `@JsonValue`, `@JsonUnwrapped`, `@JsonNaming` and `@JsonFormat` on source records is withdrawn; says that a record at an `Object`, interface or other undeclared position is now refused whether or not it is classified; says that component values are read from fields, not accessors; then gives the fix list verbatim and nothing else as a fix.
- [ ] **Contract register.**
  - Section 3.5 gains rows for `SOURCE_MODEL_JACKSON_ANNOTATION` and `SOURCE_MODEL_NOT_READABLE`; section 3.1 for `SOURCE_MODEL_RECORD_NOT_DECLARED`, `SOURCE_MODEL_NOT_READABLE` (scrub time) and `SOURCE_TREE_NOT_COMPONENT_EXACT`, from 288's Outcome.
  - From 210's Outcome: rows for `MISSING_CLIENT_ALLOWLIST`, `UNSUPPORTED_CLIENT_ALLOWLIST` and `CLIENT_ALLOWLIST_CONFLICT` (3.5) and `dataprism.security-policy.client-allowlist` (6.4); the `MISSING_CASE_AUTHORIZER` row's condition updated to D-1.0-1 as 210 built it; the `AMBIGUOUS_CASE_AUTHORIZER`, `AMBIGUOUS_CLIENT_ALLOWLIST`, `NON_SINGLETON_CASE_AUTHORIZER` and `NON_SINGLETON_CLIENT_ALLOWLIST` rows marked removed (never shipped; replaced by Spring's single injection, 210, 2026-10-27).
  - No row for `SOURCE_MODEL_CLASSIFIED_RECORD_NOT_AN_OBJECT`, `CLASSIFIED_RECORD_NOT_AN_OBJECT` or `CLASSIFIED_RECORD_UNTYPED_POSITION`; if any was added on the base, it is marked removed (never shipped, superseded).
- [ ] The Outcome carries "for the migration notes": the refused annotations, the three codes, the field-read change, the `Object`-position change, OD-288-1's outcome and the fix list, for 261.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass (install PyYAML first if `docs-site/requirements.txt` still lacks it).
- [ ] `./mvnw -B -pl data-prism-core,data-prism-spring-boot-autoconfigure,data-prism-integration-tests -am verify` passes.

## Out of scope
- The walker (288) and the describer (289).
- `docs/migration-0.8.md` (261) and every other user page.
- Applying the annotation rule at scrub time; it is a startup message only.
- The advisory itself (OD-290-2 says who updates it).
- 210's CHANGELOG and migration text (213 covers changed startup refusals and properties).
- Refusing `Object`, interface or raw positions at startup: a non-record value there is still allowed.
