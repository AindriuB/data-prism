# 94 — Add GDPR Art. 9 special-category classifications that fail closed

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/DataClassification.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/**
- data-prism-core/src/main/resources/privacy-profiles-default.yaml
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/policy/**
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSpecialCategoryTest.java *(new)*
- docs/extending.md *(the `@SensitiveData` classification paragraph only)*

## Goal

Data Prism has no way to say that a field is a GDPR Art. 9 special category
other than health (`PHI`). This task adds seven classifications. For each one
the default action becomes the strictest, `REMOVE`, and the resolver never
lets one reach the model in any recognisable form. This covers EU AI Act
Art. 5(1)(g) (biometric categorisation by sensitive attribute) and data
minimisation under Art. 10(5) and GDPR Art. 9.

## Context

- `DataClassification.java` has 11 constants. `PHI` stays as health data.
- `ProfilePrivacyPolicyResolver.java:41-94` is the precedence chain. When a
  classification has no profile rule, `fromProfile == null`, and the
  annotation's `suggestedAction` is used verbatim (`:80-86`). For a special
  category that path could yield `PASS_THROUGH`, and this task closes it.
  `override: true` takes a profile rule verbatim (`:89-91`), and this task
  closes that path for special categories too.
- `ActionStrictness.java` gives the order `PASS_THROUGH < GENERALIZE <
  SYNTHESIZE < TOKENIZE < HASH < REDACT < REMOVE`.
- `PrivacyProfiles.fromYaml` (`:35-91`) is the loader that fails at startup.
  Both the bundled file and any Java caller go through it.
- `privacy-profiles-default.yaml` contains `DEFAULT` and `STRICT`.
- The YAML catalogue path (`ConfiguredJsonSources`) parses classification
  names with `StrictYaml.enumValue`. New constants are accepted there with no
  edit.

## Acceptance

- [ ] `DataClassification` gains `BIOMETRIC`, `GENETIC`, `ETHNIC_ORIGIN`,
      `POLITICAL_OPINION`, `RELIGIOUS_BELIEF`, `TRADE_UNION` and
      `SEX_LIFE_ORIENTATION`. A public static `Set<DataClassification>
      SPECIAL_CATEGORIES` lists exactly those seven plus `PHI`. A test
      asserts the set's exact membership.
- [ ] `PrivacyProfiles.fromYaml` refuses any profile with a rule that maps a
      special category to an action other than `REDACT` or `REMOVE`. It
      throws `IllegalArgumentException` whose message contains
      `SPECIAL_CATEGORY_EXPOSED`, the profile name and the classification.
      One test per weaker action covers this: `PASS_THROUGH`, `GENERALIZE`,
      `SYNTHESIZE`, `TOKENIZE` and `HASH`. The refusal applies with and
      without `override: true`.
- [ ] `ProfilePrivacyPolicyResolver` never resolves a field that carries any
      special category to an action weaker than `REDACT`. When no profile rule
      covers any of the field's special categories, the result is `REMOVE`.
      Tests cover three cases: an annotation suggesting `PASS_THROUGH`, a
      field classified `PII` plus `BIOMETRIC` under `DEFAULT` (`PII` is
      `SYNTHESIZE` there), and a profile with no rule for the category.
- [ ] `PHI` behaviour under the shipped `DEFAULT` and `STRICT` profiles is
      unchanged (`REDACT`). An existing or new test asserts it.
- [ ] `privacy-profiles-default.yaml` lists all seven new classifications
      under both `DEFAULT` and `STRICT` with `action: REMOVE`, with a comment
      citing GDPR Art. 9.
- [ ] `ConfiguredJsonSpecialCategoryTest` proves two things. A YAML catalogue
      field with `classifications: [BIOMETRIC]` and `action: PASS_THROUGH`
      is removed from the scrubbed tree. A distinctive synthetic fixture value
      for that field is absent from the serialised output.
- [ ] `mvn -pl data-prism-core,data-prism-connectors-rest,data-prism-integration-tests -am verify`
      passes.
- [ ] `docs/extending.md` names the special categories and states that no
      profile can expose them. The wording says "supports" and never
      "compliant".

## Out of scope

- An Art. 10(5) bias-detection profile. It needs an owner decision; see the
  plan return.
- Detecting special-category values by shape in `SensitiveDataScanner`. They
  have no recognisable format.
- `docs/configuration.md` and `docs/eu-ai-act.md`. Those are tasks 103 and 106.
- Annotation-processor changes.

## Attempt 1 — failed

Branch `task/94-special-category-classifications` (2fd3e20). Reviewer: CHANGES (7/8 criteria met).

- Criterion not met: docs wording must say "supports" and never "compliant".
  docs/extending.md:325-326 reads "does not by itself make a deployment
  compliant". The intent (a denial) is right, but reword it without the word.
- Fix in the same attempt: ProfilePrivacyPolicyResolver.java:94-95 labels the
  REMOVE fallback for an uncovered special category `Decided.PROFILE_RULE`
  although no rule matched, so the audit trail misstates the reason. Use (or
  add) a reason that says it is the special-category default.
- Build command: the acceptance line's `-pl` list must include
  `data-prism-processor` or integration-tests cannot resolve it.
- Release note (for the scribe when this lands): profiles with a PHI rule
  weaker than REDACT now refuse to start, and Java-built profiles with no PHI
  rule now resolve PHI to REMOVE. No shipped config is affected.
- Optional: a field tagged BIOMETRIC+PHI under a profile with only a PHI rule
  resolves to REDACT, not REMOVE. At the floor, so not a defect; take the
  strictest across all special categories if it is cheap.
