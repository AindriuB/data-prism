# 256 — Make each leak validator redact the keys the other flags, and assert the first SENSITIVE_PATTERN

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 208 has merged
**Depends on:** 208 (208 owns `SensitivePatternValidator.java` and the recogniser cases of `PatternValidatorWiringTest`)
**Wave:** 1 (combined 0.8 graph of 213, 218 to 231 and 238 to 263; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/RawValueLeakValidator.java *(the path-redaction step only)*
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitivePatternValidator.java *(the path-redaction step only)*
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitiveDataScanner.java *(a read-only accessor for flagged property names, if one is needed)*
- data-prism-validation/src/test/java/io/github/aindriub/dataprism/validation/CrossValidatorKeyRedactionTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PatternValidatorWiringTest.java *(the first-violation assertion only)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesPatternValidatorTest.java *(the first-violation assertion only)*

## Goal
Two small validation follow-ups. Task 194 made both leak validators scan property names, and each reports a flagged name as `<undeclared>`; but a violation path produced by one validator can still contain a name only the other flagged, and only `RefusalPaths.redact` in the orchestrator stops it reaching a refusal. Make each validator redact, in its own paths, every name either validator flags, so the guarantee holds without the orchestrator. Separately, task 209's two wiring tests assert a refusal but not that `SENSITIVE_PATTERN` is the first violation.

## Context
- No dependency on sealed-cluster tasks 222 or 223 is needed, although their Owns globs cover `data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/*.java`. 222 edits only contexts that start a `tcp-ip` or `kubernetes` join, and 223 only expressions that build a raw map or lock key. `PatternValidatorWiringTest` starts `single-node` and builds no map key, and this task must add neither.
- docs/plan/HISTORY.md, "## 2026-10-19 — Key-shape scan in the leak validators (194)": the follow-up as recorded.
- docs/plan/HISTORY.md, "## 2026-10-20 — Sensitive-pattern validator wired into every Spring-built orchestrator (209)": the missing first-violation assertion.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/RefusalPaths.java: the orchestrator-side redaction, which stays.
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/Violation.java: nothing in a violation may be a value.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `CrossValidatorKeyRedactionTest`, calling the validators directly (not through the orchestrator): a tree in which a key-shaped name sits above a value that `RawValueLeakValidator` refuses yields a violation path with that name as `<undeclared>`; the symmetric case for `SensitivePatternValidator` holds too. A mutant that removes the cross-redaction fails the test; a test also shows the orchestrator result is unchanged with `RefusalPaths.redact` still in place.
- [ ] `PatternValidatorWiringTest` and `ConfiguredJsonSourcesPatternValidatorTest` assert that the refusal's first violation is `SENSITIVE_PATTERN` (the "first SENSITIVE_PATTERN" text of the refusal detail). Each assertion is shown to fail when the first violation is changed in a recorded mutation.
- [ ] No other existing test changes; no validator gains a constructor parameter.
- [ ] `./mvnw -B -pl data-prism-validation,data-prism-connectors-rest,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- `RefusalPaths` and the orchestrator.
- Recognisers and `FREE_TEXT` (208).
- The mutation-survivor work in `SensitiveDataScanner` (`ibanChecksumValid`, `luhnValid`), a separate candidate task.
