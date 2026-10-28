# 282 — Write the "Getting started with the Spring Boot starter" guide and the copy-and-edit `application.yaml` template

**Repo:** `.`
**Release:** 0.9.0
**Base branch:** `origin/main` once 280 and 281 have merged
**Depends on:** 280 (the guide walks through `examples/starter-yaml`), 281 (both edit `mkdocs.yml`; the guide links to 281's mandatory-settings paragraph and grammar section)
**Wave:** 22 (moved from 20 on 2026-10-24 with 281; 0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** the guide, its 15-minute target and the template are owner decisions already made in the spec, section 4. The page name and the template's location are decided at filing (below).
**Owns:**
- docs/getting-started-starter.md *(new)*
- examples/starter-application-template.yaml *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/StarterTemplateTest.java *(new)*
- mkdocs.yml *(`nav` and `llmstxt` insertions for the guide only)*
- README.md *(a link to the guide in the getting-started part only)*
- docs/index.md *(a link to the guide only)*
- docs/quickstart.md *(a link to the guide in "What next" only)*

## Goal
A medium-level Java developer who has never seen Data Prism should get one protected endpoint working from the docs in about 15 minutes. The guide does this: add the starter, set the handful of mandatory properties, describe the API in YAML, start, call it, and read a refusal when something is missing. It then points to the two Java routes and the advanced route. The template is an `application.yaml` to copy and edit, with every setting marked required or optional, and a test keeps it true.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md: the goal paragraph (the 15-minute target, "every startup refusal says which property to set") and section 4.
- examples/starter-yaml (280): the guide's worked example. Quote its files, do not paraphrase them. Mention examples/starter-record and examples/starter-http-interface as the Java routes, and docs/extending.md (278) for the hand-written adapter.
- docs/configuration.md (281): link the mandatory-settings paragraph, the "Defaults and derived settings" section (273) and the "Auto-configured beans" section (276) instead of repeating them.
- Decided at filing (low stakes): the page is `docs/getting-started-starter.md`, and the template is `examples/starter-application-template.yaml`, outside any module, so the site and the template repository (283) can link to it.
- The template: every `dataprism.*` and `spring.*` key a starter application can set for one protected endpoint, each with a `# required` or `# optional` comment and the default for optional ones. Secret-bearing settings are shown by reference only, never as a literal. Invented neutral values.
- `StarterTemplateTest` reads the template from the repository root. It checks that every `dataprism.*` key exists in the configuration metadata, that each key marked `required` is in 281's mandatory set, and that every mandatory key appears and is marked `required`.
- Honesty rules from the discoverability spec (`docs/plan/specs/2026-09-23-discoverability.md`): pseudonymisation is never called anonymisation, and the guide claims nothing the code does not do.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- From the 2026-10-27 re-plan of 210 (owner decision 2; the client-allowlist part reverses the 2026-10-24 decision): from 0.8 a context with the starter refuses to start unless case authorisation and the client allowlist are each decided. That means a `CaseAuthorizer` bean or `dataprism.security-policy.case-authorisation: token-trusted` (else `MISSING_CASE_AUTHORIZER`), and a `ClientAllowlist` bean, `dataprism.security-policy.allowed-clients`, or `dataprism.security-policy.client-allowlist: unrestricted` (else `MISSING_CLIENT_ALLOWLIST`). The guide and the `application.yaml` template show both decisions as required, with the opt-outs commented as development-only.

## Acceptance
- [ ] The guide's steps are, in order: prerequisites, the dependency, the mandatory properties, the API description, run, call with a token, read the result, see a refusal and fix it, next steps. Each command and file in it is copied from `examples/starter-yaml` or verified against it.
- [ ] A timed walkthrough by someone who did not write the guide (a fresh agent with no repository context, following only the page) reaches a scrubbed answer. The Outcome records the time and every point where the reader got stuck, and the page is fixed for each.
- [ ] `StarterTemplateTest` passes, and fails once in a recorded run when a mandatory key is removed from the template.
- [ ] The guide says, where it runs the application, that the default topology is single-node and that more than one replica needs `dataprism.hazelcast.topology: embedded`, linking 281's topology section.
- [ ] The guide is in `mkdocs.yml` `nav` and `llmstxt`, and is linked from `README.md`, `docs/index.md` and the end of `docs/quickstart.md`.
- [ ] No owned page states a default, a required setting or a refusal text that the code contradicts. The Outcome lists the claims checked.
- [ ] `./mvnw -B -pl data-prism-spring-boot-autoconfigure -am verify` passes. `mkdocs build --strict` passes, as 281 established.

## Out of scope
- The public template repository (283).
- `docs/configuration.md`, `docs/extending.md`, `docs/protect-your-own-api.md` and the migration page (281, 278, 275).
- New examples, or changes to the three examples beyond a fix the walkthrough shows is needed. Report such a fix in the Outcome as a follow-up for 280's files rather than editing them here.
