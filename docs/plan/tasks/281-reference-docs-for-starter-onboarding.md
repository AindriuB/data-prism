# 281 — Update the reference docs: mandatory versus optional, the path grammar, the 0.9 migration notes and the changelog

**Repo:** `.`
**Release:** 0.9.0
**Base branch:** `origin/main` once 273, 275, 276, 277, 278, 279 and 280 have merged
**Depends on:** 279 (both edit `docs/configuration.md`; 279's startup-refusal anchors are linked from here), 273, 275, 276, 277, 278 and 280 (their Outcomes carry the text this task publishes; 280's says what the examples are and that they are not published), 213 and 261 (0.8: the last editors of `CHANGELOG.md`, `mkdocs.yml` and `docs/migration-0.8.md`)
**Wave:** 20 (moved from 19 on 2026-10-24, after the external review of PR #162, because it cites 280's Outcome; 0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Owns:**
- docs/configuration.md *(the group table at about :119-148, the property reference at about :150-225, and the JSON section at about :709-795)*
- docs/migration-0.9.md *(new)*
- docs/architecture.md *(the configured-JSON grammar paragraph at about :105-125 only)*
- mkdocs.yml *(`nav` and `llmstxt` insertions for `migration-0.9.md` only)*
- CHANGELOG.md *(`[Unreleased]` only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ConfigurationMetadataDocumentedTest.java *(only if the split table needs a parser change; the test's rules must not weaken)*

## Goal
Bring the reference documentation into line with what 273 to 279 changed, from their Outcomes, in one pass. Split `docs/configuration.md`'s "Required" into truly mandatory settings (the security decisions with no default) and optional ones (defaulted, derived, or not needed). Replace the JSON grammar section with the restricted JSONPath grammar and the embedded-starter case. Write the 0.9 migration notes and the `[Unreleased]` changelog entries.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, sections 1, 2 and 4 ("split the Required column into true mandatory versus optional").
- The Outcomes of 273 to 279, under the headings that name this task ("For 281", "for 281's migration notes and CHANGELOG"). Each new default, derivation, auto-configured bean, the grammar, the starter's new dependency (OD-277-1), `@DataPrismSource`, the refusal format, and the decided outcomes of OD-273-1, OD-273-2, OD-275-1, OD-276-1 and OD-277-1.
- docs/configuration.md:121-123: "`Required` means required in every protected HTTP deployment unless a row says otherwise". This sentence is the ambiguity to remove. The mandatory set after 273 is exactly: the HMAC key reference, the caller-claim mappings, the security policy, the audit sink, case authorisation (210), the JWT issuer, keys and audience (now given through Spring's properties), and the writer id unless `spring.application.name` is set. `dataprism.identity.resolver` is mandatory only with two or more sources (OD-273-3 (a)). Check this list against the code. If a row disagrees, the code wins and the Outcome says so.
- `ConfigurationMetadataDocumentedTest` parses tables whose first header is `Property` and compares any `Default` column with the metadata. Keep both working. If a new `Mandatory` column needs a parser change, it must not weaken an existing check.
- docs/migration-0.8.md: the page shape to follow. The migration page covers each breaking change with before and after: OD-273-2 (a) if chosen, the JSON grammar under OD-275-1, and the exception-type change of 279.
- The configured-JSON section (`docs/configuration.md:709-722`) says the mode is opted into with `-Dloader.path` and is never part of the base server. Keep that for the server, and add the embedded-starter case as 277 decides.
- docs/conventions.md: British spelling, and plain prose without marketing words.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## Acceptance
- [ ] The group table and the property reference each say, per key, whether it is mandatory (no default; startup refuses without it) or optional, with its default or derivation. A short paragraph above the tables lists the mandatory set in one place.
- [ ] Every mandatory row is checked against the code by a refusal test that already exists: 273's still-required test, whose rows include the JWT issuer, the JWT audience and the writer id. The Outcome maps each row to its test.
- [ ] The JSON section states the restricted JSONPath grammar: what is allowed, what is refused and with which codes, the exactly-one-path coverage rule, `subject-json-path` and chainable fields, and how to use it from the starter and from the server. `docs/architecture.md`'s grammar paragraph matches it.
- [ ] `docs/migration-0.9.md` exists, is in `mkdocs.yml` `nav` and `llmstxt`, and has a before-and-after example for each breaking change.
- [ ] `CHANGELOG.md` `[Unreleased]` has Added, Changed and Breaking entries for 273 to 280, including that the examples are not published.
- [ ] `ConfigurationMetadataDocumentedTest` passes. `mkdocs build --strict` passes, if the docs build is part of CI (`pages.yml`); otherwise the Outcome shows a local strict build.
- [ ] No owned page states a default or a required setting that the code contradicts. The Outcome lists the claims checked and the source line for each.

## Out of scope
- The getting-started guide and the `application.yaml` template (282).
- `docs/extending.md` (278 owns it) and `docs/protect-your-own-api.md` (275 owns it).
- The startup-refusal index section of `docs/configuration.md` (279).
- Any code change, apart from the parser change allowed above.
