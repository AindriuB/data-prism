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
