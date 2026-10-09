# 187 — Record task 184, the v0.6.0 tag move and the 0.6.0 release completion

**Repo:** .
**Executor:** scribe (records only; no code)
**Depends on:** none
**Owns:**
- docs/plan/PLAN.md
- docs/plan/HISTORY.md (append-only: new entries)
- docs/plan/HISTORY-INDEX.md (insertions only: new rows)
- docs/plan/tasks/184-pin-maven-wrapper.md (moved to `docs/plan/tasks/retired/`, Outcome added)

## Goal
Bring the plan and history up to date with what happened after the 0.6.0 cut:
the Central rejection, the Maven Wrapper pin (task 184, PR #127), the tag move
(D-REL-1), the decisions D-184-A/B/C, the completed publication on every
channel, and repository housekeeping. Mark 0.6.0 complete.

## Context
- `docs/plan/PLAN.md:498-610` — the 0.6.0 section; :568 lists the owner-gated
  release steps that are now all done.
- `docs/plan/HISTORY-INDEX.md` — row format; one row per HISTORY.md entry,
  newest first, written in the same commit as the entry.
- `docs/plan/tasks/184-pin-maven-wrapper.md` — the task and its D-184-A to E.
- Facts to record (from the owner's session, 2026-10-09):
  - Central rejected the first 0.6.0 bundle ("Bundle has content that does
    NOT have a .pom file", all 14 modules). Cause: `ubuntu-24.04` image moved
    Maven 3.9.16 → 3.10.0; central-publishing-maven-plugin 0.11.0 then
    skipped its "Pre Bundling - deleted …/maven-metadata-central-staging.xml"
    step.
  - Task 184 / PR #127: Maven Wrapper 3.3.4, `only-script`, Maven 3.9.16 with
    `distributionSha256Sum`; every workflow Maven call goes through `./mvnw`.
  - D-REL-1 = (a): owner moved `v0.6.0` from `21f3a008` to `87a2a720`;
    workflow-only change, artifacts equivalent; precedent 0.2.0.
  - Re-run: Central validated with 14 "Pre Bundling" lines; owner published
    deployment `aca7186a`; Central live about 12:20Z 2026-10-09. GHCR image
    08:39Z, MCP registry 11:55Z, GitHub Release published. The release
    workflow's 422 on the re-tag was expected (the release already existed).
  - D-184-A = (b), D-184-B (ignore `org.apache.maven:apache-maven` >= 3.10),
    D-184-C (investigate only, no unpinning): delegated by the owner and
    decided; now tasks 185 and 186. D-184-D (leave docs' `mvn` mentions) and
    D-184-E (CHANGELOG under `[Unreleased]`) as recommended in the 184 file.
  - 36 Dependabot alerts on Pillow are stale: `requirements.txt` pins 12.3.0
    and every alert's vulnerable range is `< 12.3.0`. Owner to dismiss them.
  - Remote branches `task/55` and `task/67` deleted; backed up locally as tags
    `archive/task-55` and `archive/task-67`.
  - AAR for 0.6.0 presented to the owner in chat (never committed).

## Acceptance
- [ ] `git mv docs/plan/tasks/184-pin-maven-wrapper.md docs/plan/tasks/retired/`
      done, with an `## Outcome` section stating PR #127, merge commit
      `87a2a720`, and that the owner's publish-central re-run showed 14
      "Pre Bundling - deleted" lines.
- [ ] `HISTORY.md` has new dated `## 2026-10-09 — …` entries (one or more)
      covering every fact listed in Context; nothing above them is edited
      (`git diff` on HISTORY.md shows only added lines).
- [ ] `HISTORY-INDEX.md` has one new row per new HISTORY.md entry, at the top
      of the table, whose Heading column is the exact heading string;
      `grep -c` of each heading in HISTORY.md returns 1.
- [ ] `PLAN.md`'s 0.6.0 section says 0.6.0 is complete and published on
      Central, GHCR, the GitHub Release and the MCP registry, with the AAR
      presented; the "remaining release steps are owner-gated" text is
      replaced, not left contradicting it. The amd64 Docker smoke stays listed
      as open if it is still open.
- [ ] `PLAN.md` lists 185 and 186 as open with their titles, and lists two
      owner actions: dismiss the 36 stale Pillow alerts; file the upstream
      issue if task 186 finds no fix.
- [ ] No text mentions a credential value, token or GPG secret; deployment id
      `aca7186a` is the only Central identifier recorded.
- [ ] `git diff --stat` lists only files under `docs/plan/`, and does not list
      `docs/plan/tasks/185-*` or `docs/plan/tasks/186-*`.

## Out of scope
- Any AAR text in the repository (chat only, never committed).
- The 185 and 186 task files, which their implementers own.
- Dismissing Dependabot alerts, deleting branches or tags, or any GitHub
  action: the owner does these.
- `CHANGELOG.md` and every file outside `docs/plan/`.

## Outcome
Done by the scribe in commit 270aa22a: the 0.6.0 completion, task 184 and the tag move are recorded in PLAN.md, HISTORY.md and HISTORY-INDEX.md. The task file was removed in that commit and restored here to follow the retired/ convention.
