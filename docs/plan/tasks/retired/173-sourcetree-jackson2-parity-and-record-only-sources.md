# 173 — SourceTree Jackson 2 parity and record-only source models

**Repo:** .
**Base:** `release/0.6.0-jackson3`. Unplanned; no task file existed while it ran.

## Origin
External review P2: Jackson 3's default `WRITE_ENUMS_USING_TO_STRING` made `SourceTree.of()` emit an enum's `toString()` instead of `name()`, changing inputs to hashing, tokenisation and synthesis. 167's pin covered only `DataPrismObjectMapper`.

## Outcome
- `SourceTree` starts from `configureForJackson2()` with explicit pins. Enums serialise by name (values and keys); `Month` by name (`ONE_BASED_MONTHS`); legacy `Date`, `Timestamp`, `sql.Date` and `Calendar` as epoch millis; `sql.Time` as text; `Locale` in `toString` form; `@JsonFormat` is honoured, including `shape=STRING` with "+00:00".
- D-173-1 (b, a change): `java.time` types are ISO-8601 text and `Optional` is unwrapped; Jackson 2 refused these.
- Records are read by components only (`INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY`).
- D-173-2 (a): source models must be records. New `SourceModels` with code `SOURCE_MODEL_NOT_A_RECORD`.
  - Startup, in `DataPrismContractValidator`: the top level must be a record; components are walked recursively including generics, arrays, wildcards and type-variable bounds; JDK classes that would be read by getters are refused.
  - Runtime, in `SourceTree`, via serializer, key, collection and map modifiers plus an `AnnotationIntrospector`: beans at any depth, object-shaped enums, `Throwable`/`Color`/`Point`, user collection and map subclasses, class-level `@JsonSerialize` on non-records, `@JsonProperty`/`@JsonGetter` on non-component record methods, and non-JDK keys checked against the runtime key class.
  - Explicitly allowed and documented: `@JsonAnyGetter`, `@JsonValue`, component or class-level `@JsonSerialize` on records (the engine still classifies their output; bean lookups through them are refused). The runtime top level may also be a `JsonNode` (`ConfiguredJsonScrubbingEngine`).
- A Jackson 2 versus Jackson 3 probe over the full matrix shows only D-173-1 and D-173-2 as differences.
- Tests: `SourceTreeJacksonParityTest`, `SourceModelsTest`, `DataPrismObjectMapperDefaultsTest`, `DataPrismContractValidatorTest`. About 15 test adapters moved from `DataSourceAdapter<String>` to a `TestPayload` record; two test bean models became records.
- Docs: `extending.md` and `developer-guide/write-an-adapter.md` say "return a record".
- Verified: tester PASS (full reactor JDK 21, 1648 tests, 0 failed; release-profile package green; also green in Los_Angeles and Kolkata time zones). Reviews: CHANGES x4, then APPROVE at 22a57eed.

## Lessons
The parity check must cover every mapper that serialises, not just the output one. Reproducing Jackson 2 bean introspection on Jackson 3 was open-ended, so the contract was narrowed instead.
