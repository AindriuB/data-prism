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
`./mvnw -B dependency:list -DincludeScope=runtime` on the branch. It resolved every module through
`data-prism-quickstart-issuer` except `data-prism-quickstart-extension`, which fails resolving its
unpublished test-scope sibling jars from Central at 0.7.0. That module is not published and is not in the
server image. Each Maven block is unlabelled, so the list below is the union of `compile` and `runtime`
coordinates for the 13 published modules (annotations through `-spring-boot-starter`) plus
`data-prism-server`, with project-internal artifacts removed. 66 third-party coordinates. Each was queried
with `securityVulnerabilities(ecosystem: MAVEN, package: ...)` for its resolved version and matched against
`vulnerableVersionRange`. **Result: none for all 66.**

| Group | Resolved version | Advisories |
|---|---|---|
| spring-boot (autoconfigure, http-converter, jackson, security, servlet, tomcat, validation, web-server, webmvc, oauth2-resource-server, and the `-starter*` set) | 4.1.1 | none |
| spring-framework (aop, beans, context, core, expression, web, webmvc) | 7.0.9 | none |
| spring-security (config, core, crypto, oauth2-core, oauth2-jose, oauth2-resource-server, web) | 7.1.1 | none |
| tools.jackson (jackson-core, jackson-databind, jackson-dataformat-yaml) | 3.1.8 | none |
| com.fasterxml.jackson.core:jackson-annotations | 2.21 | none |
| tomcat-embed (core, el, websocket) | 11.0.27 | none |
| com.hazelcast:hazelcast | 5.7.0 | none |
| com.nimbusds:nimbus-jose-jwt | 10.10 | none |
| io.modelcontextprotocol.sdk (mcp-core, mcp-json-jackson3) | 2.1.0 | none |
| com.networknt:json-schema-validator | 3.0.6 | none |
| ch.qos.logback (classic, core) | 1.5.38 | none |
| slf4j (api, jul-to-slf4j) | 2.0.18 | none |
| log4j (api, to-slf4j) | 2.25.5 | none |
| io.projectreactor:reactor-core | 3.8.7 | none |
| io.micrometer (commons, observation) | 1.17.1 | none |
| org.hibernate.validator:hibernate-validator | 9.1.3.Final | none |
| org.yaml:snakeyaml / org.snakeyaml:snakeyaml-engine | 2.6 / 3.0.1 | none |
| commons-logging 1.3.6, classmate 1.7.3, itu 1.14.0, jboss-logging 3.6.3.Final, jspecify 1.0.1, reactive-streams 1.0.4, jakarta.annotation-api 3.0.0, jakarta.validation-api 3.1.1 | as stated | none |

Caveat: the range matcher is a small script (scratchpad, not committed); it was spot-checked on four
known in/out-of-range cases. The advisory database lists older ranges for several of these packages, and
none cover the resolved versions. The tomcat 11.0.27 and jackson 3.1.8 overrides stay as they are.

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
