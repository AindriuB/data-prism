# 194 — Scan property names, not only values, in the leak validators

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 190
**Decided (owner, 2026-10-09):** D-070-7 (a): the key scan is always on, under
every profile, with no SPI change.
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
- After task 190 (D-070-2), validators run on each source's payload-derived
  tree (a hit marks only that source `REFUSED`), then once more on the final
  response (a hit refuses the whole call). They never see source aliases as keys.
- Under profiles other than `PASS_THROUGH_UNSAFE` a key is a declared field
  name or `<undeclared-N>`, so a hit there is a real finding.

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
      `PASS_THROUGH_UNSAFE` profile: with one source and a map-keyed fixture,
      the call is refused `VALIDATION_FAILED`; with two sources, the map-keyed
      one is `REFUSED` and absent, and the other is returned. Proven
      non-vacuous by disabling the key scan and watching the key pass through
      (Outcome shows both runs).
- [ ] `LlmResponseValidator` is unchanged (`git diff` shows no edit to it).
- [ ] `docs/extending.md` and the architecture decision entry state the new
      behaviour and drop the "no validator checks key shapes" cost.
- [ ] `./mvnw -B verify -pl data-prism-validation,data-prism-integration-tests -am`
      exits 0; Outcome records the count and commit.
- [ ] The Outcome carries a CHANGELOG `### Security` line for task 201.

## Out of scope
- New identifier shapes in `SensitiveDataScanner`.
- Changing how the engine renames undeclared keys.
- Any orchestration or MCP change.
