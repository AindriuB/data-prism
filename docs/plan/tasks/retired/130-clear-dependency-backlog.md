# 130 — Clear the dependency-update backlog

**Repo:** `.`
**Release:** 0.5.0
**Depends on:** 136 (changed 2026-10-06 by the 0.4.1 plan: was "none, any time after 0.4.0 has finished publishing". Task 136 sets the project version in every pom and `ARG VERSION`, and task 133 adds `EXPOSE 5701` to `docker/distribution/Dockerfile`, all files this task owns. Run after the 0.4.1 cut. Owns is unchanged.)
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

## Re-scoped 2026-10-07 (after the 0.4.1 release)

0.4.1 is released, and the grouping config is live. The backlog is now these PRs: #114 (maven group),
#115 (github-actions group), #116 (docker group), and #99 and #102 (Pillow security updates).
**Base:** `main` at de39e5c6, on branch `claude/dependency-updates`.

**Owner decisions (2026-10-07):**
- **D-130-A:** Spring Boot 3.5.16 → 4.1.1 (in #114) is **not** part of this task. It becomes a separate
  migration task (139). Stay on the 3.5.x line here.
- **D-130-B:** the Docker images **stay on Java 21**. Take no eclipse-temurin 25 or maven
  temurin-26 tags from #116.

**Owns, added:** `.github/dependabot.yml` *(ignore rules only)*.

**Do:**
1. Maven: nimbus-jose-jwt 10.10, archunit-junit5 1.5.1, maven-source-plugin 3.4.0. Read
   nimbus-jose-jwt's 10.10 release notes, and confirm the JWT tests still pass. Also take the newest
   Spring Boot **3.5.x** patch if one is newer than 3.5.16, but no 4.x.
2. Actions: checkout 7, setup-java 6, upload-artifact 7, download-artifact 8,
   docker/login-action 4, across every workflow. Read each one's release notes for breaking input or
   behaviour changes, e.g. setup-java's `cache` and GPG inputs, and upload/download artifact name and
   merge semantics. Handle each, and name each in the return. upload and download must stay compatible
   with each other.
3. Pillow 12.3.0 in both `docs-site/logo/requirements.txt` and `docs-site/social-card/requirements.txt`. Run both
   generator scripts in a scratchpad venv, and confirm the output is unchanged or visually equivalent.
   Do not commit regenerated images unless they changed meaningfully. Report it either way.
4. `.github/dependabot.yml` ignore rules:
   - maven: ignore `org.springframework.boot:*` `version-update:semver-major`, until task 139.
   - docker: ignore `eclipse-temurin` and `maven` `version-update:semver-major`, per D-130-B.
     Check that the ignore syntax matches Dependabot's documented schema.
5. Docker: no base-image tag changes, per D-130-B.
6. Builds, one at a time, never `-rf`, never `mvn install`: `mvn clean verify` and `mvn -Prelease -Dgpg.skip=true clean verify`.
   Then `mkdocs build --strict -f mkdocs.yml` (with /Users/Andrew/workspace/data-prism/.venv-docs) and check_site.py. The version is 0.4.1,
   which is now on Central, so always use the full reactor or `-am`.
7. The return lists every workflow step whose action version changed, so the owner can watch them on the next
   release, since publish workflows cannot run from a branch.

Closing the Dependabot PRs is for the owner after merge. Dependabot also closes grouped PRs that this task supersedes.
