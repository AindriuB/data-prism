# 267 — Never apply a field's own policy to an array or object nested inside its array

**Repo:** `.`
**Release:** 0.8.0, and backported to 0.7.1 by cherry-pick as its second Security fix (task 266; owner decision 2026-10-21 adds this task to 0.7.1 alongside 265, 244, 245 and 257)
**Base branch:** `origin/main` once 265 has merged. Until then, `task/265-follow-only-collection-type-arguments`.
**Depends on:** 265 (both edit `JsonTreeScrubbingEngine.apply`, `SourceValues`, `SourceModels`, `ConfiguredJsonNestedLeafShapeGuard`, `NestedTypeArgumentTest`, `SourceModelsTest`, the two docs and the contract register. This task builds on 265's nested-type rule and its `SOURCE_MODEL_` codes, and replaces one of 265's pins)
**Wave:** 4 (combined 0.8 graph of 213, 218 to 231 and 238 to 267; 203 and 206 to 211 counted as landed). 248 depends on this task and moves to wave 5.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(the array branch of `apply` and `scrubNestedObject` only; not `describe`, which is 248's)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java *(`collect`, `descend` and a new private helper only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java *(a new public code constant, `walk`, and a new refusal helper only)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedLeafShapeGuard.java *(`checkLeaves` and `firstObjectIndex` only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/NestedTypeArgumentTest.java *(the `nestedContainersKeepTheirOutcome` case only, which is replaced)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/NestedContainerTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/engine/SourceModelsTest.java *(insertions, plus any existing case whose fixture is now refused; name each one in the Outcome)*
- data-prism-validation/src/test/java/io/github/aindriub/dataprism/validation/NestedContainerLeakTest.java *(new; adopted from the probe)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedCatalogueScrubbingTest.java *(insertions only)*
- docs/extending.md *(the source-model shape bullet that 265 edited, at about lines 344-358, only)*
- docs/developer-guide/write-an-adapter.md *(the sentence at line 24 that 265 edited, only)*
- docs/plan/design/contract-register.md *(one new row directly after 265's `SOURCE_MODEL_MAP_OF_RECORD` row, only)*

## Goal
When a field's value is an array, `JsonTreeScrubbingEngine.apply` sends an element that is itself an array to `scalar(...)` with the field's own policy. A `@NonSensitive` field therefore releases every record inside an inner array raw under the DEFAULT profile. `SourceValues.descend` skips inner arrays, so `RawValueLeakValidator` gets no prohibited values for them and passes the output. `SensitivePatternValidator` catches only pattern-shaped values. The defect is present at v0.7.0 and on `main`.

The fix fails closed. A field's own policy applies only to the scalars in its value, never to an array or object nested inside an array. The engine recurses into an inner array. An object found inside an inner array has no type the engine can determine, so it takes the unclassified-structure path, which refuses under DEFAULT. The prohibited-value walk and the configured-JSON guard follow the same rule. `SourceModels.require` refuses, at startup, any declared shape that puts a record inside an inner array.

## Context
- The probe that confirmed the defect is `NestedContainerLeakProbeTest.java` in the coordinator's scratchpad: `/private/tmp/claude-501/-Users-Andrew-workspace-data-prism--claude-worktrees-data-prism-eu-compliance-04cf83/339b800c-b811-482a-b7bb-f0e39b7b319c/scratchpad/probe/NestedContainerLeakProbeTest.java`. It may be gone by the time you read this. The shapes in Acceptance are its complete fixture set. It uses the shipped DEFAULT profile, the real engine and both validators. Its values are invented (`Zelda Invented-Probe`, `zelda.probe@invalid.example`). Its outcomes at filing were:
  - raw release, `SourceModels.require` accepts: `List<List<Leaf>>`, `Leaf[][]`, `List<Leaf[]>`, `List<Leaf>[]`, `Set<List<Leaf>>`, `List<Set<Leaf>>`, `List<List<List<Leaf>>>`, `Optional<List<List<Leaf>>>`. At request time the same happens for `@NonSensitive Object v` and `@NonSensitive List<Object> v`, each holding `[[leaf]]`;
  - fail closed: `Optional<List<Leaf>>`, `Optional<Leaf[]>` and `List<Optional<Leaf>>` (`UNCLASSIFIED_STRUCTURE`), unannotated `List<List<Leaf>>` (`UNDECLARED_FIELD`), and `@SensitiveData(REDACT) List<List<Leaf>>` (each inner array replaced by `[REDACTED]`).
- On the 265 branch, data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java:
  - `apply`, about lines 241-271. The array branch at about :261-263 sends a non-object element to `scalar`;
  - `scrubNestedObject` and `nestedType`, about :279-316. The unclassified path resolves `FieldMetadata.unannotated(md.fieldName())`, refuses with `UNCLASSIFIED_STRUCTURE` when not allowed, and records `run.disposed(pointer, ...)`;
  - `scrubObject` raises `TOO_DEEP` beyond `MAX_DEPTH` (16).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceValues.java, about lines 45-105 on the 265 branch: `collect`, `descend`, `nestedType` and `addScalars`. `addScalars` already recurses, so a sensitive field already contributes the scalars of its inner arrays. What is missing is the values inside records inside inner arrays under a non-sensitive field.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/SourceModels.java, about lines 222-276 on the 265 branch: `walk`. It already resets at each record and recurses into components, type arguments, array components and bounds, so a refusal at any depth falls out of it. Follow `genericRefusal` and `mapOfRecordRefusal` for the message form (`Model.field: ...`, simple names only, no value, path `$`).
- `FieldMetadata` (core.model) carries one level of type information: `valueType` and `elementType`, both `Class`. After 265, `elementType` is null for `List<List<Leaf>>`, because the argument is not a `Class`. That is why the engine cannot determine the type of an object inside an inner array. Do not widen `FieldMetadata`; see Decisions.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedLeafShapeGuard.java:
  - a `nested:` field whose array element is itself an array is already refused with `NESTED_FIELD_NOT_STRUCTURED`. Keep that;
  - `checkLeaves` and `firstObjectIndex` (about :87-127) look for an object only among a leaf array's direct elements, so `[[{...}]]` at a leaf passes the guard and reaches the engine.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ChainableFields.java (240, main only) already refuses a chainable declaration reachable through a nested generic or a multi-dimensional array. This task does not touch it, and its tests stay valid.
- Both shipped profiles set `unclassified: FAIL_REQUEST` (`data-prism-core/src/main/resources/privacy-profiles-default.yaml:18,79`). That file's header says exposing unclassified data is never an option.
- 265's `NestedTypeArgumentTest.nestedContainersKeepTheirOutcome` (about :168-177 on the 265 branch) pins the leaking output `[[{"name":"<PII>"}]]` for `ListList`, with a comment calling it a separate gap. This task replaces that assertion.
- docs/conventions.md: privacy rules for diffs; messages never carry values.
- **0.7.1 backport.** Task 266 cherry-picks this task onto `release/0.7.1-cut` straight after 265's core-fix commits and before 244. Split the work into two commit groups, in this order:
  - **Core fix (backported).** Every owned file except `docs/plan/design/contract-register.md`. These commits must not name any `Chainable*` type or any type, constant or file added after `v0.7.0`, apart from what 265's core-fix commits add (`SourceModels.GENERIC_CODE`, `SourceModels.MAP_OF_RECORD_CODE`, `NestedTypeArgumentTest`). Check each import in the new tests with `git grep <type> v0.7.0`. That includes `ProfilePrivacyPolicyResolver`, `PrivacyProfiles`, `RawValueLeakValidator` and `SensitivePatternValidator`, which the probe uses.
  - **Main-only (not backported).** The contract-register row, in a commit whose subject starts `267 (main only):`. The row says `shipped | 0.7.1`.
  - On `main`, the full suite must pass after the core-fix commits alone, as well as at the head.

## Decisions taken in this brief
- **Refuse at startup and refuse in the engine. Do not descend.** The engine has to refuse in any case, because `Object` and `List<Object>` components can carry `[[record]]` at request time and no startup check can see that. The startup refusal is added on top, because a declared `List<List<Leaf>>` can never be scrubbed with `Leaf`'s rules: the engine cannot determine the inner type, so every non-empty response would be refused. Failing at startup tells the author on day one rather than on the first request.

  Descending was rejected for two reasons. It needs the full generic type of the field, and `FieldMetadata` holds only one level of it as `Class`. Widening that public `core.model` record changes its canonical constructor. That breaks every custom `FieldMetadataResolver`, including `DescriptorFieldMetadataResolver` and `ConfiguredJsonFieldMetadataResolver`, which is not a change for a patch release. And 248's path grammar names one array level (`[]`), as 240 found for chainables. Descending into nested containers of records is a 0.9 follow-up for the scribe to log.
- **Startup rule: count array levels, with `Optional` transparent.** For a component's declared type, a record is at array level *n*, where:
  - an array class, a `GenericArrayType`, and a parameterized JDK type assignable to `Iterable` each add one level;
  - `Optional` adds none;
  - type variables and wildcards are read through their upper bounds, as `walk` does.

  A record at level 2 or deeper is refused. A record at level 0 or 1 is not, so `Optional<List<Leaf>>`, `Optional<Leaf[]>` and `List<Optional<Leaf>>` keep their request-time `UNCLASSIFIED_STRUCTURE`. Counting restarts inside each record, because `walk` handles each record's components on their own.
- **One new startup code.** `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` is a condition name in the `SOURCE_MODEL_` family. It is raised as `PrivacyRefusedException` with path `$`, as 265's codes are. Where a component matches more than one rule, the existing order of `walk` decides: `SOURCE_MODEL_GENERIC` and `SOURCE_MODEL_MAP_OF_RECORD` come first. So `Map<String, List<List<Leaf>>>` stays `SOURCE_MODEL_MAP_OF_RECORD`.
- **Scalars inside inner arrays keep the field's policy.** `@NonSensitive List<List<String>>` stays accepted at startup and its output does not change. Under any other action, each scalar in an inner array is now treated on its own, as a scalar in a flat array already is. The whole inner array is no longer replaced, so REDACT gives `[["[REDACTED]", ...]]` where it used to give `["[REDACTED]"]`.
- **Prohibited values for an object inside an inner array: every scalar in it.** Its type cannot be determined, and no shipped profile may expose an unclassified structure. So `SourceValues` treats every scalar under such an object as prohibited. Objects at level 1 are unchanged: they keep 265's rule, and an undescendable one still contributes nothing.

## Acceptance
- [ ] Tests are written first and fail on the base commit (265's last core-fix commit); the Outcome names them.
- [ ] `JsonTreeScrubbingEngine.apply` never passes a JSON array or object to `scalar(...)`. An array element that is itself an array is processed as an array, with the same field metadata and policy. Its path is `path[i][j]`, its pointer gains one `/*` per level, and it counts toward `MAX_DEPTH`; past that, the result is `TOO_DEEP`. Inside an inner array:
  - a scalar takes the field's policy, exactly as a scalar in a flat array does;
  - an object takes the unclassified-structure path of `scrubNestedObject` (`UNCLASSIFIED_STRUCTURE` when the profile refuses unclassified data, with a disposition recorded) and is never scrubbed with `nestedType`.
- [ ] `NestedContainerLeakTest` in `data-prism-validation`, adopted from the probe, with its `System.out` removed. It runs the shipped DEFAULT profile, the real engine and both validators, and treats a `PrivacyRefusedException` from `SourceModels.require` as a refusal with no output. It asserts:
  - `List<List<Leaf>>`, `Leaf[][]`, `List<Leaf[]>`, `List<Leaf>[]`, `Set<List<Leaf>>`, `List<Set<Leaf>>`, `List<List<List<Leaf>>>` and `Optional<List<List<Leaf>>>` under `@NonSensitive`: `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` at startup;
  - the same eight with `require` skipped: `UNCLASSIFIED_STRUCTURE` from the scrub, and neither invented value in any output;
  - `@NonSensitive Object v` and `@NonSensitive List<Object> v`, each holding `[[leaf]]`: accepted at startup, then `UNCLASSIFIED_STRUCTURE` at scrub, with no invented value in any output;
  - `Optional<List<Leaf>>`, `Optional<Leaf[]>` and `List<Optional<Leaf>>`: accepted at startup, then `UNCLASSIFIED_STRUCTURE` (unchanged);
  - unannotated `List<List<Leaf>>` and `@SensitiveData(REDACT) List<List<Leaf>>`: now `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` at startup. With `require` skipped, the unannotated one stays `UNDECLARED_FIELD` and the REDACT one becomes `UNCLASSIFIED_STRUCTURE`. The Outcome states both changes; both were fail-closed before and are fail-closed now.
- [ ] Defence in depth: `SourceValues.prohibited` for each of the ten leaking shapes above (eight declared, two request-time) contains both invented values. A case in `NestedContainerLeakTest` feeds the base commit's leaking tree, `[[{"name":..., "email":...}]]`, to `RawValueLeakValidator` with that prohibited set and asserts the result is not valid. That case fails on the base commit, where the set is empty.
- [ ] `SourceModels.require` refuses with `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` each of the eight declared shapes, a record nested inside another record that has such a component, and `List<? extends List<Leaf>>`. The message has the form `Model.field: ...`, names simple names only, and contains no invented value and no package name. `SourceModelsTest` asserts all of this. These are accepted unchanged: `List<List<String>>`, `String[][]`, `byte[][]`, `List<int[]>`, `Optional<List<Leaf>>`, `Optional<Leaf[]>` and `List<Optional<Leaf>>`. `Map<String, List<List<Leaf>>>` stays `SOURCE_MODEL_MAP_OF_RECORD`.
- [ ] `NestedTypeArgumentTest.nestedContainersKeepTheirOutcome` is replaced. The `ListList` assertion now expects `UNCLASSIFIED_STRUCTURE` with `require` skipped, and the leaking-output pin and its "separate gap" comment are gone. The `OptionalList` assertion is kept.
- [ ] `NestedContainerTest` in core asserts, under DEFAULT with `require` skipped:
  - `@NonSensitive List<List<String>>` output is byte-identical to the base commit's;
  - `@SensitiveData(REDACT)` on `List<List<String>>` gives each inner scalar as `[REDACTED]`;
  - an array nested 17 levels deep under one field is refused with `TOO_DEEP`;
  - the dispositions record the inner-array pointer with one `/*` per level.
- [ ] `ConfiguredJsonNestedLeafShapeGuard`: a leaf field whose value holds an object at any array depth (`[[{...}]]`) is refused with `NESTED_LEAF_NOT_SCALAR`, with the path naming the outermost index. A `nested:` field whose element is an array is still refused with `NESTED_FIELD_NOT_STRUCTURED`. `ConfiguredJsonNestedCatalogueScrubbingTest` gains a case for each, and every existing `data-prism-connectors-rest` test passes unmodified.
- [ ] Docs name the new code. The `docs/extending.md` shape bullet and `docs/developer-guide/write-an-adapter.md:24` both say a record must not sit inside a collection inside a collection (`SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD`). `docs/plan/design/contract-register.md` gains the row `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD | shipped | 0.7.1 | core:SourceModels.java:<line>`.
- [ ] The Outcome lists every existing fixture or test across the reactor that is now refused, and how it was changed. At filing, the core `ChainableFieldsTest` fixtures `NestedLists`, `Grid`, `ListGrid` and `ListList` were known to have such shapes; they call `ChainableFields.of` without `require` and are expected to stay valid. The Outcome confirms that `ChainableFields`, `DescriptorFieldMetadataResolver` and `ConfiguredJsonFieldMetadataResolver` needed no change.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-validation,data-prism-connectors-rest -am verify` passes, and the full reactor `./mvnw -B verify` passes.
- [ ] The commits are split as "0.7.1 backport" in Context describes. `git log --format=%s` shows that the only commit touching `docs/plan/**` starts `267 (main only):`, and no commit touches a `Chainable*` file. The Outcome names the last core-fix commit and gives the full-reactor result at that commit. After 266 cherry-picks 265's core-fix commits and then this task's core-fix commits onto `release/0.7.1-cut`, `./mvnw -B -pl data-prism-core,data-prism-validation,data-prism-connectors-rest -am verify` passes there, including `NestedContainerLeakTest`, `NestedContainerTest`, the replaced `NestedTypeArgumentTest` case and the new `SourceModelsTest` cases.

## Out of scope
- Descending into records inside nested containers with their own rules, and widening `FieldMetadata` to carry a full generic type (a 0.9 follow-up; see Decisions).
- Refusing `Optional<List<Leaf>>`, `Optional<Leaf[]>` or `List<Optional<Leaf>>` at startup. They fail closed at request time today and keep doing so. Whether they should be scrubbable or refused earlier is a separate question for the scribe to log.
- `CHANGELOG.md`. Task 266 writes it in `[0.7.1]`. What 266 needs, recorded here:
  - a **Security** entry: when a field held an array of arrays (for example `List<List<Leaf>>` or `Leaf[][]`), the field's own policy was applied to each inner array whole. A field marked `@NonSensitive` therefore released every record inside it unscrubbed under the DEFAULT profile, and neither output validator caught it. The same happened at request time for an `Object` or `List<Object>` field holding nested lists of records. The defect is present in every release up to and including 0.7.0, and fixed in 0.7.1;
  - the new startup refusal code `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD`;
  - the behaviour changes: a model with a record inside a collection inside a collection is now refused at startup. A record found inside a nested array at request time is refused as an unclassified structure. A non-PASS_THROUGH action on a field holding nested scalar arrays now applies to each scalar, not to each inner array.
- 248's `ScrubPlanDescriber` and `describe` override. 248 copies this rule after this task.
- `ChainableFields` (240, closed), and 249's configured-JSON files.
- `RawValueLeakValidator`, `SensitivePatternValidator` and `SensitiveDataScanner`. The fix is in what they are given.
- Map-key classification and walking map entries (265's follow-up).

## Outcome

Landed 2026-10-21 on branch `task/267-refuse-records-inside-nested-containers`, stacked on 265, merged with a merge commit in PR record/0.7.1-wave2-265-267. Core-fix commits, in pick order after 265's: `82329385`, `34a6a0e3`, `f2090cae`, `096a823e`. Main-only commit (skipped by 266): `2d5e5c30`. Tester PASS: full `./mvnw -B verify` at `5dd88b39`, 2,169 tests. The head `2d5e5c30` differs from that only by one test file in `data-prism-validation`, which passed the implementer's targeted verify. The core-fix commits cherry-pick onto `v0.7.0` after 265's and pass there. Code review clean; reviewer APPROVE after the both-validators fix.

**Last core-fix commit.** `096a823e`, which adds `thePatternValidatorCatchesTheEmailButNotTheName` to document that the raw-value validator closes the gap the pattern validator leaves.

**Accepted behaviour changes.**
- An unannotated `List<List<Leaf>>` and a `REDACT` one are now refused at startup as `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD`. With startup validation skipped, the `REDACT` one becomes `UNCLASSIFIED_STRUCTURE` at request time.
- `REDACT` (and any non-pass-through action) on nested scalars now applies to each scalar, not to each inner array whole. Pass-through output is unchanged.

**Existing fixtures.** No existing fixture is refused. `ChainableFields`, `DescriptorFieldMetadataResolver` and `ConfiguredJsonFieldMetadataResolver` needed no functional change.

**Test-first depth inputs were off by one.** The failing-first tests in `82329385` used a nesting depth one too shallow in two cases; `34a6a0e3` corrected them with the fix.

**Accepted scope deviation (`f2090cae`).** Javadoc in `FieldMetadata` and `DefaultFieldMetadataResolver`, two `SourceModelsTest` renames, and checkable values in the request-time generic fixture. These were outside the Owns list and were made on the coordinator's instruction.

**Follow-ups.**
- 0.9: descend into records inside nested containers, which needs `FieldMetadata` widened.
- 0.9: wildcard lower bounds in `recordBeyondFirstArray`. It fails closed today.
- Open: whether `Optional` shapes should be refused at startup.
