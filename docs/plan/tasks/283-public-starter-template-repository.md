# 283 — Publish a public template repository generated from the YAML example

**Repo:** a new public GitHub repository (name: OD-283-1), plus two link lines in this repository
**Release:** after the release named in OD-283-1 is published to Maven Central
**Base branch:** `origin/main` once 280 and 282 have merged (for the two link lines)
**Depends on:** 280 (`examples/starter-yaml` is the source), 282 (the guide and the template file the repository links to), and the publication to Central of the release OD-283-1 names
**Wave:** after the 0.9.0 publication (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** OD-283-1 is open (below). Creating a public repository on the owner's GitHub account needs the owner's go in their own message; a coordinator's or another agent's message is not consent.
**Owns:**
- The new repository, all of it.
- README.md *(one link line to the template repository only)*
- docs/getting-started-starter.md *(one link line to the template repository only)*

## Goal
Give developers a GitHub "Use this template" starting point. It is the `starter-yaml` example as a standalone project that depends only on artifacts published to Maven Central, with no parent from this reactor. It replaces the owner's unpushed external demo project.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 4, "After 0.8.0 is published".
- examples/starter-yaml (280) and examples/starter-application-template.yaml (282).
- The repository must build in a clean environment from an empty local Maven repository, with no snapshot, staging or local repository. That is the meaning of "depending only on Maven Central".
- Swap the reactor parent for `spring-boot-starter-parent` (or the Spring Boot BOM) and a fixed `data-prism-spring-boot-starter` version. Follow `docs/extending.md`, "The pom shape", including the embedded Tomcat floor (`org.apache.tomcat.embed` 11.0.25 or later), for as long as that rule stands.
- Use a neutral package such as `com.example.loans` rather than `io.github.aindriub.dataprism.example`. Use invented neutral domains only; no owner workplace or system names (owner rule). No real personal data, credentials or tokens (repository rule 3).
- Include CI (`./mvnw -B verify` on push and pull request), Dependabot for Maven and Actions, a licence matching this repository's, and a README that links back to the guide.
- The owner's unpushed demo is local to the owner's machine. Do not read, push or delete it. The owner retires it.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## OD-283-1 (open, owner): when, what name, and with what visibility
- **Timing.** The spec says "after 0.8.0 is published". But the YAML example relies on 0.9 features: 275's path grammar, 273's defaults and derived JWT settings, and 276's auto-configured beans. A template on 0.8.0 would need the pre-0.9 configuration, which the template would then have to unlearn.
  - **(a), recommended:** create it once 0.9.0 is on Central.
  - **(b):** create it on 0.8.0 with the 0.8 configuration shape, and rewrite it at 0.9.0.
- **Name.** Proposed: `data-prism-starter-template`.
- **Visibility.** Public, marked as a GitHub template repository.
- Central's file-count limit does not apply: the template publishes nothing.

## Acceptance
- [ ] The repository exists under the name and timing OD-283-1 decides, is public, and is marked as a template.
- [ ] In a clean container with an empty `~/.m2/repository`, `./mvnw -B verify` passes, and the build log shows every Data Prism artifact resolved from `repo.maven.apache.org`.
- [ ] Its test is the `starter-yaml` test, adapted only for the parent and the package. It passes in the repository's own CI on the first push.
- [ ] A grep in the Outcome shows no owner-domain names, no credentials and no `SNAPSHOT` versions.
- [ ] README.md and the getting-started guide in this repository link to it.

## Out of scope
- Public-API catalogues. The owner wants them later as separate associated repositories; this is recorded in PLAN.md "Someday".
- Templates for the record or HTTP-interface routes.
- Retiring the owner's local demo project.
- Publishing anything to Maven Central.
