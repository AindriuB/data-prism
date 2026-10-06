# 93 — Report per-field dispositions from the scrubbing engines

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/ScrubResult.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/JsonTreeScrubbingEngine.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/JsonTreeScrubbingEngineTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/ScrubDispositionsTest.java *(new)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDispositionsTest.java *(new)*

## Goal

The engine already decides an action for every field it visits but keeps no
record of the decisions. This task makes `ScrubResult` carry that record as
field path to `PrivacyAction`, built only from declared field names and never
from payload values, so that task 96 can put it in the audit record.

## Context

- `ScrubResult.java:20` is `record ScrubResult(ObjectNode tree, Set<String> emitted)`.
  It is constructed only at `JsonTreeScrubbingEngine.java:88`.
- `JsonTreeScrubbingEngine.java:71-88` — the per-run walk. Decisions come
  from `PrivacyPolicyResolver.resolve(FieldMetadata, PrivacyContext)`.
- `ConfiguredJsonScrubbingEngine.java:81-108` delegates to the tree engine
  for configured JSON sources (`:97`) and to the Java-first engine otherwise
  (`:108`). Nested catalogues are walked through the same engine (task 60).
- Path format, fixed by task 92: a JSON pointer from the record root, with
  array indices collapsed to `*`. The source-name prefix is added by task 96,
  not here.

## Acceptance

- [ ] `ScrubResult` gains `Map<String, PrivacyAction> dispositions`, an
      immutable copy with key order sorted. The two-argument constructor
      remains and yields an empty map.
- [ ] `JsonTreeScrubbingEngine.scrub` returns one disposition per field it
      resolved, including `PASS_THROUGH` for fields declared non-sensitive.
      Each key is a JSON pointer built from declared field names. A test
      scrubs a record with a nested object and a list, and asserts the exact
      map, for example `/contacts/*/email -> REDACT`.
- [ ] A test feeds a payload containing a distinctive synthetic value. It
      asserts that the value appears in no key and no value of
      `dispositions`, and does not appear in `ScrubResult.toString()`.
- [ ] `ConfiguredJsonScrubbingEngine.scrub` returns the dispositions of the
      engine it delegated to, for both the configured-JSON path and the
      Java-first path. `ConfiguredJsonDispositionsTest` covers a one-level
      nested catalogue.
- [ ] When the engine throws `PrivacyRefusedException`, behaviour is
      unchanged. The exception still carries `path()`, and no partial
      `ScrubResult` is returned.
- [ ] `mvn -pl data-prism-core,data-prism-connectors-rest -am verify` passes.

## Out of scope

- Writing dispositions into `AuditEvent`. That is task 96.
- Changing any action decided for any field. This task records decisions and
  does not alter them.
- The `ScrubbingEngine` interface signature, which stays as it is.

## Attempt 1 — failed

Branch `task/93-scrub-reports-field-dispositions` (abb4dc3). Tester: PASS. Reviewer: CHANGES.

- Criterion not met: "each key a JSON pointer built from declared field names".
  `JsonTreeScrubbingEngine.scrubObject` builds `fieldPointer = child(pointer, field)`
  from the payload's own keys. For an undeclared property under a permissive
  `unclassified` profile (REDACT / REMOVE / PASS_THROUGH), the payload-supplied
  name lands in `dispositions` — e.g. `{"alice@corp.example": "x"}` yields
  `/alice@corp.example -> REDACT`. Task 96 would then write personal data into
  the audit record (CLAUDE.md rule 3).
- Fix: undeclared properties must never contribute their name. Record them
  under a fixed placeholder derived from declared structure only (e.g. the
  parent pointer plus `/<undeclared>`), or omit them; keep the action.
- Add a test in ScrubDispositionsTest under a permissive `unclassified` profile
  where the distinctive value is a payload **key**, asserting it is absent from
  `dispositions` keys and from `toString()`. Use a reserved domain
  (example.com / example.org) for the value.
