# 174 — Record properties must match components

**Repo:** .
**Base:** `release/0.6.0-jackson3`. Unplanned, follow-up to 173.

## Origin
External review P2. `SourceModels`' reflective `getDeclaredMethods()` scan missed `@JsonProperty`/`@JsonGetter` on interface default methods, so a record implementing `@JsonProperty default String getURL()` passed validation and emitted "getURL" (Jackson 2 emitted "url"), breaking descriptor matching: strict requests then fail `UNKNOWN_FIELD`.

## Outcome
- The reflective scan is replaced by a structural check. Every property the bean serializer emits for a record must come from a real record component field or a 0-arg component accessor (`AnnotatedField`/`AnnotatedMethod`, not a virtual member). Only real `AnyGetterWriter` properties are exempt.
- It runs at startup (walk, then `refuseIfReadByGetters`) and at runtime (`RecordsOnly` modifier). Refusal is `SOURCE_MODEL_NOT_A_RECORD`, naming the simple class name only.
- Still allowed, and matching Jackson 2: an accessor renamed via `@JsonProperty`/`@JsonGetter` (including through an implemented interface), real any-getters (including interface defaults; their entries are classified by the engine like any field), `@JsonValue`, `@JsonUnwrapped` components, `@JsonNaming`, type and identity info, class-level `@JsonSerialize`.
- Deliberate fail-closed difference from Jackson 2: `@JsonAppend(attrs=@Attr("<component name>"))` is refused where Jackson 2 printed the component.
- Verified: tester PASS at 1dc7c2e6 (full reactor JDK 21, 1653 tests, 0 failed, HEAD unchanged during the run; release-profile package green). Reviews: CHANGES (any-getter `enabled=false` bypass; `@JsonAppend` virtual property named like a component), then APPROVE.

## Process lesson
Two invalid test runs this release (170, 174) came from the coordinator sending fixes to an implementer while a tester was building the same worktree. Never send changes to a worktree with a test run in progress.
