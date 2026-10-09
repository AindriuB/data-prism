# 194 — Scan property names, not only values, in the leak validators

**Repo:** .
**Branch:** from `main` after task 190 has merged
**Depends on:** 190
**Blocked on owner decision:** D-070-7 (always, or only under `PASS_THROUGH_UNSAFE`)
**Owns:**
- data-prism-validation/**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/KeyShapeLeakScanTest.java (new)
- docs/extending.md (the "Profiles that admit unclassified data" section only)
- docs/architecture.md (the 2026-10-08 map-key decision entry only)
- docs/plan/tasks/194-key-shape-scan-in-leak-validators.md (Outcome section only)

## Goal
Under a Java-built `PASS_THROUGH_UNSAFE` profile, map keys and undeclared
property names reach the response verbatim, and the leak validators scan values
only. A map keyed by an email address therefore passes every check. This task
makes `SensitivePatternValidator` and `RawValueLeakValidator` scan property
names as well as values, so such a response is refused.

## Context
- `docs/architecture.md:380-393` — the map-key decision and its stated cost
  ("under `PASS_THROUGH_UNSAFE` no validator checks key shapes").
- `docs/extending.md:426-445` — "Profiles that admit unclassified data".
- `data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/RawValueLeakValidator.java:41-60`
  (walks values, builds paths from keys), `SensitivePatternValidator.java:30-70`,
  `SensitiveDataScanner`, `LlmResponseValidator.java` (the public SPI; the
  `emitted` allowlist).
- `UndeclaredPropertyNameTest`, `UndeclaredKeyRefusalTest`,
  `UndeclaredNameToolResultScanTest` — existing pins to keep green.
- After task 190, validators see only payload-derived trees, never source
  aliases as keys.
- Recommended for D-070-7: scan keys always. Under every other profile a key
  is a declared field name or `<undeclared-N>`, so a hit there is a real
  finding, and no SPI change is needed.

## Acceptance
Write the tests first; each must fail on `main` before the change.
- [ ] A tree whose property name is a synthetic, checksum-invalid identifier of
      each shape the scanner knows is refused by `SensitivePatternValidator`;
      a property name equal to a prohibited raw value is refused by
      `RawValueLeakValidator`. Values-only behaviour is unchanged.
- [ ] A property name that is in `emitted` is not refused (scope allowlist).
- [ ] The violation path never contains the offending name: it is redacted to
      `<undeclared>` exactly as undeclared keys are today; a test asserts the
      refusal text, the audit record and captured logs hold no part of it.
- [ ] `KeyShapeLeakScanTest`: end to end through `get_entity_context` with a
      `PASS_THROUGH_UNSAFE` profile and a map-keyed fixture, the call is refused
      `VALIDATION_FAILED`; proven non-vacuous by disabling the key scan and
      watching it pass the key through (Outcome shows both runs).
- [ ] If D-070-7 chooses "only under `PASS_THROUGH_UNSAFE`", the gating and its
      SPI or context change are covered by a test for each profile behaviour.
- [ ] `docs/extending.md` and the architecture decision entry state the new
      behaviour and drop the "no validator checks key shapes" cost.
- [ ] `./mvnw -B verify -pl data-prism-validation,data-prism-integration-tests -am`
      exits 0; Outcome records the count and commit.
- [ ] The Outcome carries a CHANGELOG `### Security` line for task 201.

## Out of scope
- New identifier shapes in `SensitiveDataScanner`.
- Changing how the engine renames undeclared keys.
- Any orchestration or MCP change.
