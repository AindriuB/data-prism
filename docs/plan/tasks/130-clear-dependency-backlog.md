# 130 — Clear the dependency-update backlog

**Repo:** `.`
**Release:** 0.5.0
**Depends on:** none (any time after 0.4.0 has finished publishing)
**Owns:**
- pom.xml and module `*/pom.xml` *(dependency and plugin versions only)*
- .github/workflows/** *(action versions only, plus any input renames a major bump requires)*
- docker/**/Dockerfile *(base-image tags only)*
- docs-site/logo/requirements.txt, docs-site/social-card/requirements.txt

## Goal

Bring the dependencies the open Dependabot pull requests bump up to date in one
branch, verify them together, then close those pull requests. From 2026-10-06
`.github/dependabot.yml` groups version updates into one pull request per
ecosystem per week; this task clears the ungrouped ones opened before that.

## Backlog (open on 2026-10-06)

| PR | Bump | Note |
|---|---|---|
| #94 | `com.nimbusds:nimbus-jose-jwt` 10.9.1 → 10.10 | JWT validation on the MCP and operator surfaces: read the release notes |
| #111 | `com.tngtech.archunit:archunit-junit5` 1.5.0 → 1.5.1 | test only |
| #91 | `maven-source-plugin` 3.3.1 → 3.4.0 | used by `-Prelease`: verify the sources jars |
| #92 | `actions/checkout` 4 → 7 | major |
| #93 | `actions/setup-java` 4 → 6 | major; check the `cache: maven` input and the GPG import in publish-central |
| #89 | `actions/upload-artifact` 4 → 7 | major; must move with #95 |
| #95 | `actions/download-artifact` 4 → 8 | major; must move with #89 |
| #90 | `docker/login-action` 3 → 4 | major; used by publish-image |
| #99 | `pillow` 11.3.0 → 12.3.0 in `docs-site/social-card` | security update; build-time image generation only |
| #102 | `pillow` 11.3.0 → 12.3.0 in `docs-site/logo` | same |

Re-list open Dependabot pull requests at the start. A grouped pull request may
already have superseded some of these.

## Acceptance

1. Every bump above is applied, or recorded in the return as deliberately held,
   with the reason.
2. For each major action bump, the release notes were read and any breaking
   input or behaviour change was handled. Name each one in the return.
3. `mvn clean verify` and `mvn -Prelease -Dgpg.skip=true clean verify` pass for
   the full reactor. `mkdocs build --strict` and `docs-site/hooks/check_site.py`
   pass. The logo and social-card scripts still run under the new Pillow and
   produce byte-identical or visually unchanged output.
4. The publish workflows cannot be exercised locally. List the steps in
   publish-central, publish-image and publish-mcp whose action versions
   changed, so that the owner can watch them on the next release.
5. Never run `mvn install`. Do not push, merge or close pull requests; the
   owner closes the superseded Dependabot pull requests after merge.
