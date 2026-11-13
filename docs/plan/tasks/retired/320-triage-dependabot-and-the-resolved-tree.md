# 320 — Triage the Dependabot alerts and the resolved dependency tree before 0.8.0

**Repo:** `.`
**Release:** 0.8.0 (before the cut, 321)
**Executor:** `implementer`
**Depends on:** none
**Wave:** 1 (0.8.0 pre-cut, filed 2026-11-13)
**Owns:**
- docs-site/logo/requirements.txt
- docs-site/social-card/requirements.txt
- pom.xml *(dependency-version `<properties>` only, and only under AC4)*
- docs/plan/tasks/320-triage-dependabot-and-the-resolved-tree.md *(the Outcome section only)*

## Goal
PLAN's release-cut list says to triage the Dependabot report on `main` before the cut, and, because
Dependabot cannot see BOM-managed versions, to read the resolved tree too. Clear the alerts that a pin bump
fixes, and prove whether any runtime dependency of a published artifact carries a known advisory.

## Context
- At filing (2026-11-13, `gh api repos/AindriuB/data-prism/dependabot/alerts?state=open`): 116 open alerts,
  every one Pillow, in `docs-site/logo/requirements.txt` and `docs-site/social-card/requirements.txt`
  (both `Pillow==12.3.0`; first patched 13.0.0). These are offline asset generators
  (`make_favicon.py`, `make_card.py`), not shipped. Re-count before starting; a new manifest changes the plan.
- Open Dependabot PRs: #246 and #148 (Pillow, the same two files), #245 (docs-site pip group), #244
  (github-actions), #247 (load-test), #229 (root maven group: Spring Boot 4.1.1 to 4.1.2, jackson-bom 3.1.8
  to 3.3.0, plugins; CI red at filing).
- PLAN.md, "0.8 backlog": Dependabot cannot see BOM-managed versions; the `jackson-bom` override at
  `pom.xml:79-120`; `spring-boot.version` at `pom.xml:71`.
- No scanner (`osv-scanner`, `trivy`, `grype`) is installed. Query the GitHub advisory database instead:
  `gh api graphql` `securityVulnerabilities(ecosystem: MAVEN, package: "<g:a>")`, per coordinate. Scripts
  go in the session scratchpad, never in the repo.
- Credentials: never read `~/.m2/settings*.xml`, `~/.gnupg` or any token file.

## Acceptance
- [ ] AC1 Both requirements files pin exactly `Pillow==13.0.0`,
  and `make_favicon.py` and `make_card.py` each run to exit 0 in a scratch venv with that pin, writing to a
  scratch directory. No committed image changes.
- [ ] AC2 The Outcome lists the runtime coordinates (`compile` and `runtime` scope) of every module published
  to Central and of the `data-prism-server` image jar, from `./mvnw -B dependency:list` on the branch, and
  for each the advisories that the GitHub advisory database reports for that resolved version; "none" is a
  valid row.
- [ ] AC3 The Outcome has one row per open Dependabot alert group and open Dependabot PR: fixed here,
  dismiss (with the reason Dependabot's UI accepts), or defer to 0.9, each with a one-line reason. It names
  the PRs this branch supersedes (#246, #148) for the owner to close.
- [ ] AC4 A version property in `pom.xml` changes only if AC2 shows a runtime advisory that a patch-level bump
  fixes; each such change names the advisory id in the commit message. A minor or major bump (for example
  jackson 3.3.0) is not made here; it is a row in AC3 for the owner.
- [ ] AC5 If AC4 changed `pom.xml`, the full build passes.

## Checks
- `alerts-fixed` command — AC1
  run: `grep -x 'Pillow==13.0.0' docs-site/logo/requirements.txt && grep -x 'Pillow==13.0.0' docs-site/social-card/requirements.txt`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs-site/(logo|social-card)/requirements\.txt|pom\.xml|docs/plan/tasks/320-triage-dependabot-and-the-resolved-tree\.md')"`
- `full-build` command — AC5
  run: `./mvnw -B verify`
- `triage` read — AC1 to AC4
  how: reviewer reads the Outcome tables against `dependency:list` output in the PR body and checks that any
  `pom.xml` hunk is a `<properties>` patch bump with a named advisory.

## Out of scope
- Merging or closing any Dependabot PR, or dismissing an alert (owner actions; the Outcome recommends).
- Adding a scanner to CI or a pip/load-test entry to `dependabot.yml` (0.8 backlog, 0.9).
- Removing the `jackson-bom` override; the project `<version>` (321).

## Outcome

Run 2026-11-13 on `origin/main` d79f6973. No `pom.xml` change (AC4): the resolved tree has no advisory.

### Alerts (AC1)
Re-counted: 116 open alerts (10 critical, 70 high, 36 medium), all Pillow, only in the two manifests
(`docs-site/logo/requirements.txt`, `docs-site/social-card/requirements.txt`). Both now pin `Pillow==13.0.0`,
which is the first patched version. `make_favicon.py` and `make_card.py` each exited 0 under Python 3.12 with
Pillow 13.0.0 installed in a scratch venv (Pillow 13 needs Python 3.10 or later; the macOS system 3.9 only
offers 11.3). They wrote to a scratch copy of `docs-site`, so no image in the repo changed.

### Resolved runtime tree (AC2)
Attempt 2. `./mvnw -B dependency:list -DincludeScope=runtime -pl <the 14 modules>` on the branch (the 13
published modules, annotations through `-spring-boot-starter`, plus `data-prism-server`). Each Maven block
is unlabelled, so this is the union of `compile` and `runtime` coordinates, project-internal artifacts
removed. The unpublished `data-prism-integration-tests`, `-quickstart-*` modules are excluded
(`data-prism-quickstart-extension` also cannot resolve its unpublished test siblings from Central at 0.7.0).

**76 third-party coordinates.** Attempt 1 listed 66; it missed 10 that arrive through
`spring-boot-starter-actuator` (optional in some modules, compile in the server): micrometer-core,
micrometer-jakarta9, HdrHistogram and seven Spring Boot actuator and micrometer artifacts. All are now checked.

Each coordinate was queried with `securityVulnerabilities(ecosystem: MAVEN, package: "<g:a>")`, paging with
`first:100` and `pageInfo.endCursor` until `hasNextPage` was false (no first-page cap; 66 + 10 coordinates,
774 advisory-range records returned in total). Every record's `vulnerableVersionRange` was matched against
the resolved version. **Result: none for all 76.** The advisory database lists older ranges for
several (tomcat-embed-core, spring-web, jackson, snakeyaml); none cover the resolved versions (for example
tomcat-embed-core 11.0.27 sits above every 11.0.x range returned).

- `ch.qos.logback:logback-classic:1.5.38`: none
- `ch.qos.logback:logback-core:1.5.38`: none
- `com.ethlo.time:itu:1.14.0`: none
- `com.fasterxml.jackson.core:jackson-annotations:2.21`: none
- `com.fasterxml:classmate:1.7.3`: none
- `com.hazelcast:hazelcast:5.7.0`: none
- `com.networknt:json-schema-validator:3.0.6`: none
- `com.nimbusds:nimbus-jose-jwt:10.10`: none
- `commons-logging:commons-logging:1.3.6`: none
- `io.micrometer:micrometer-commons:1.17.1`: none
- `io.micrometer:micrometer-core:1.17.1`: none
- `io.micrometer:micrometer-jakarta9:1.17.1`: none
- `io.micrometer:micrometer-observation:1.17.1`: none
- `io.modelcontextprotocol.sdk:mcp-core:2.1.0`: none
- `io.modelcontextprotocol.sdk:mcp-json-jackson3:2.1.0`: none
- `io.projectreactor:reactor-core:3.8.7`: none
- `jakarta.annotation:jakarta.annotation-api:3.0.0`: none
- `jakarta.validation:jakarta.validation-api:3.1.1`: none
- `org.apache.logging.log4j:log4j-api:2.25.5`: none
- `org.apache.logging.log4j:log4j-to-slf4j:2.25.5`: none
- `org.apache.tomcat.embed:tomcat-embed-core:11.0.27`: none
- `org.apache.tomcat.embed:tomcat-embed-el:11.0.27`: none
- `org.apache.tomcat.embed:tomcat-embed-websocket:11.0.27`: none
- `org.hdrhistogram:HdrHistogram:2.2.2`: none
- `org.hibernate.validator:hibernate-validator:9.1.3.Final`: none
- `org.jboss.logging:jboss-logging:3.6.3.Final`: none
- `org.jspecify:jspecify:1.0.1`: none
- `org.reactivestreams:reactive-streams:1.0.4`: none
- `org.slf4j:jul-to-slf4j:2.0.18`: none
- `org.slf4j:slf4j-api:2.0.18`: none
- `org.snakeyaml:snakeyaml-engine:3.0.1`: none
- `org.springframework.boot:spring-boot-actuator-autoconfigure:4.1.1`: none
- `org.springframework.boot:spring-boot-actuator:4.1.1`: none
- `org.springframework.boot:spring-boot-autoconfigure:4.1.1`: none
- `org.springframework.boot:spring-boot-health:4.1.1`: none
- `org.springframework.boot:spring-boot-http-converter:4.1.1`: none
- `org.springframework.boot:spring-boot-jackson:4.1.1`: none
- `org.springframework.boot:spring-boot-micrometer-metrics:4.1.1`: none
- `org.springframework.boot:spring-boot-micrometer-observation:4.1.1`: none
- `org.springframework.boot:spring-boot-security-oauth2-resource-server:4.1.1`: none
- `org.springframework.boot:spring-boot-security:4.1.1`: none
- `org.springframework.boot:spring-boot-servlet:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-actuator:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-jackson:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-logging:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-micrometer-metrics:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-security:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-tomcat-runtime:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-tomcat:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-validation:4.1.1`: none
- `org.springframework.boot:spring-boot-starter-webmvc:4.1.1`: none
- `org.springframework.boot:spring-boot-starter:4.1.1`: none
- `org.springframework.boot:spring-boot-tomcat:4.1.1`: none
- `org.springframework.boot:spring-boot-validation:4.1.1`: none
- `org.springframework.boot:spring-boot-web-server:4.1.1`: none
- `org.springframework.boot:spring-boot-webmvc:4.1.1`: none
- `org.springframework.boot:spring-boot:4.1.1`: none
- `org.springframework.security:spring-security-config:7.1.1`: none
- `org.springframework.security:spring-security-core:7.1.1`: none
- `org.springframework.security:spring-security-crypto:7.1.1`: none
- `org.springframework.security:spring-security-oauth2-core:7.1.1`: none
- `org.springframework.security:spring-security-oauth2-jose:7.1.1`: none
- `org.springframework.security:spring-security-oauth2-resource-server:7.1.1`: none
- `org.springframework.security:spring-security-web:7.1.1`: none
- `org.springframework:spring-aop:7.0.9`: none
- `org.springframework:spring-beans:7.0.9`: none
- `org.springframework:spring-context:7.0.9`: none
- `org.springframework:spring-core:7.0.9`: none
- `org.springframework:spring-expression:7.0.9`: none
- `org.springframework:spring-web:7.0.9`: none
- `org.springframework:spring-webmvc:7.0.9`: none
- `org.yaml:snakeyaml:2.6`: none
- `tools.jackson.core:jackson-core:3.1.8`: none
- `tools.jackson.core:jackson-databind:3.1.8`: none
- `tools.jackson.dataformat:jackson-dataformat-yaml:3.1.8`: none

Caveat: the range matcher is a small script (scratchpad, not committed), spot-checked on known in and
out-of-range cases. The tomcat 11.0.27 and jackson 3.1.8 overrides stay as they are.

### Dependabot PRs and alerts (AC3)

| Item | Recommendation | Reason |
|---|---|---|
| 116 Pillow alerts (both manifests) | Auto-close on merge of this PR; do not dismiss | The pin is 13.0.0, the first patched version, so the alerts resolve themselves. |
| #246 (Pillow, logo) | Close as superseded by this branch | Same one-line change. |
| #148 (Pillow, social-card) | Close as superseded by this branch | Same one-line change. |
| #245 (docs-site pip group, 5 updates) | Defer to 0.9, or merge after this PR with a rebase | Overlaps the same two Pillow lines, so it conflicts. mkdocs-material 9.8.1 and pymdown-extensions bumps are docs-site only and untested here; mkdocs-material is announced end-of-life 2027-05-05. |
| #244 (github-actions group, 4 updates) | Defer to 0.9 | Pinned actions in release workflows (setup-java, setup-python, docker/login, cosign-installer): do not change the release path in the cut. Checks skipped on the PR. |
| #247 (load-test maven group, 8 updates) | Defer to 0.9 | Includes jackson-databind 3.3.0 and junit 6.1.3 (minor bumps), and the load-test pom is not shipped. CI green. |
| #229 (root maven group, 8 updates) | Defer to 0.9 | Contains jackson-bom 3.1.8 to 3.3.0 (a minor bump, AC4 forbids it here), Boot 4.1.1 to 4.1.2, and plugin bumps. CI is red (`build (21)`, `build (25)`, `ci-gate`). Nothing in the resolved tree needs it for security. |

## Attempt 1 — failed

Verified HEAD: 36a6c32f963d266c1c05668ca3a4d9d3a2633c61. reviewer: REQUEST CHANGES on AC2.
- AC2 — the Outcome table groups coordinates and does not list every one: the "`-starter*` set" (about 9 artifacts, inferred from the stated total of 66) and the base `spring-boot` jar are never named, and the PR #272 body does not include the `dependency:list` output the read check compares against. The 66-coordinate list and the "no advisory" claim therefore cannot be checked. Fix: list every runtime coordinate (groupId:artifactId:version) by name in the Outcome (or the PR body), so each can be matched against the advisory database.

Merged 2026-11-13, PR #272, merge `3b60cf9a`, verified at `f95f04dd` (tester PASS, reviewer APPROVE on the second attempt; Attempt 1 below failed review on AC2 and was fixed by listing all 76 coordinates). Dependabot #246 and #148 were closed as superseded by this PR. Correction: Pillow 13 needs Python 3.11 or later, not 3.10 as stated above (see `PLAN.md` follow-ups).
