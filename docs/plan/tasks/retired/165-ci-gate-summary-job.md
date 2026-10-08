# 165 — Add a single `ci-gate` summary check to build.yml

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2). Do not start until owner decision
D-165-A below is recorded in this file.
**Depends on:** none
**Owns:**
- .github/workflows/build.yml
- .github/workflows/pages.yml (only if D-165-A picks (B) or (C): the `build` job id and the `needs: build` of `deploy`, nothing else)

## Goal
main's branch protection used to require a check named `build`. Task 144
made build.yml's job a JDK 21/25 matrix, which reports as `build (21)` and
`build (25)`. From then on the only check named `build` was pages.yml's docs
job, which runs only when docs paths change. The gate was silently checking
the docs build, and PR #119, which changed no docs, waited for it forever.
The owner's stopgap requires `build (21)`, `build (25)` and
`container-smoke`. Replace that list with one check that stands for the
whole of build.yml and cannot collide with another workflow's job name.

## Context
- .github/workflows/build.yml:12-44: job `build`, a matrix over `java: ["21", "25"]` with `fail-fast: false`. :47-60: job `container-smoke`. The triggers are `push` to main and every `pull_request`, with no path filter.
- .github/workflows/pages.yml:12-22: path-filtered triggers. :29: job id `build`. :155: `deploy` has `needs: build`. The header comment at :3-6 says it is deliberately not a required check (task 82).
- .github/workflows/publish-image.yml:472-480: the existing comment on how `needs:` waits for every leg of a matrix job. Reuse the explanation; do not copy the job.
- GitHub semantics this task relies on:
  - A job with `needs:` on a matrix job waits for every leg, and `needs.<job>.result` is a single `success`, `failure`, `cancelled` or `skipped` for the whole matrix.
  - Without `if: always()`, a job whose need failed is **skipped**, and branch protection treats a skipped required check as passing. That is why the gate must run on `always()` and decide for itself.
  - A required check that never reports (a path-filtered workflow) blocks forever. That is why the gate must live in a workflow with no path filter.
- No doc outside docs/plan/ names the required checks (`grep -rn 'build (21)' docs README.md CONTRIBUTING.md` is empty at 438ef802), so there is no docs edit.

## Owner decision required before starting

**D-165-A: how to make the required check unambiguous.**
- **(A) Add a `ci-gate` job to build.yml. Leave pages.yml alone.**
  - For: one required check, and it covers every build.yml job, matrix legs included. Adding a JDK to the matrix, or a new job listed in `needs:`, never needs a branch-protection change again. The name `ci-gate` is used by no workflow.
  - Against: pages.yml's job keeps the id `build`. A future protection edit that types `build` by habit reintroduces the trap, unless someone remembers this history. It adds one short job (a few seconds of runner time per run).
- **(B) Rename pages.yml's job (for example to `docs-site`) and add no gate.**
  - For: removes the collision at its source, with no new job.
  - Against: the owner must still require each build.yml check by its exact name: `build (21)`, `build (25)`, `container-smoke`. Every matrix or job change silently loosens the gate until protection is edited, which is the exact failure task 144 caused. It does nothing for "skipped counts as failure".
- **(C) Both: add `ci-gate` and rename pages.yml's job.**
  - For: the gate's benefits, and no job left named a bare `build` in any workflow.
  - Against: two files change. The pages rename is cosmetic once the gate is the only required check, and it changes the docs check's name, so anyone filtering checks by `build` sees it move.

## Acceptance
- [ ] (A) or (C): build.yml has a job with id and `name:` `ci-gate`, with `needs: [build, container-smoke]` and `if: always()`. Its single step exits non-zero unless every entry of `needs.*.result` is exactly `success`, so `failure`, `cancelled` and `skipped` all fail it. For example, pass `toJSON(needs)` in an `env:` variable and test it with `jq`; do not interpolate `${{ }}` into the script body. It has no checkout, `permissions: {}` or the workflow's `contents: read`, and a comment saying it must list every job in the file.
- [ ] (B) or (C): pages.yml's job `build` is renamed, `deploy`'s `needs:` follows it, and nothing else in pages.yml changes (`git diff` shows two changed lines, three with `name:`).
- [ ] `grep -rnE '^\s{2}(ci-gate):' .github/workflows` matches only build.yml, and no workflow has a job whose id or `name:` equals the gate's name.
- [ ] `actionlint` over `.github/workflows/` exits 0. Give the version and output in the hand-back. If actionlint is not installed locally, run it via its container image or `go run`, and say which.
- [ ] This task's own PR shows a `ci-gate` check that passed, linked in the hand-back.
- [ ] Failure proof, recorded in the hand-back as one of the following, saying which:
  - **(empirical)** A throwaway branch off this task's branch adds a commit that makes one leg fail (for example `exit 1` in `container-smoke`), opened as a draft PR. `ci-gate` concludes `failure`. Link the run, then close the PR and delete the branch.
  - **(reasoned)** A walk through the `needs`/`if: always()` semantics for each of `failure`, `cancelled` and `skipped` on a leg. It must also cover the whole run being cancelled: the gate either runs and fails, or is itself cancelled, and both leave the required check not passing.
  The empirical proof is preferred. The reasoned one is acceptable only if the hand-back says why the empirical one was not run.
- [ ] No repository setting is changed by the implementer: no branch protection, ruleset or required-check edit, via `gh api` or the UI.
- [ ] The hand-back contains the one-line CHANGELOG `### Changed` entry for task 162 to copy. It also contains the OWNER step below, word for word, so the recorder carries it into PLAN.md.

## OWNER step after merge
Once this task's PR has merged and `ci-gate` has reported on main at least
once, change main's branch protection so that the only required status
check from build.yml is `ci-gate`. Remove `build (21)`, `build (25)` and
`container-smoke` from the required list. Leave pages.yml's check
unrequired, as task 82 decided. The implementer does not make this change.

## Out of scope
- Branch protection, rulesets or any repository setting (owner step above).
- Making the docs build (pages.yml) a required check.
- Any change to the matrix, the JDK versions, the build steps or `container-smoke` itself.
- The publish-*, release workflows.
- `CHANGELOG.md` (task 162 writes the entry from this task's hand-back).

## Owner decision D-165-A: decided 2026-10-08, option (C)

Do both:
- add the `ci-gate` summary job to build.yml. It needs every matrix leg and container-smoke, runs with `if: always()`, and fails unless every needed job succeeded;
- rename pages.yml's `build` job to a distinct id and name, for example `docs-site`, so that no workflow job is named a bare `build`.

The owner's post-merge step is unchanged. After `ci-gate` reports on main once, switch main's required checks to `ci-gate` alone. The implementer changes no repository settings.

## Outcome (2026-10-08, wave 1)
Merged (4c9d7c79). ci-gate summary job in build.yml; pages.yml job renamed docs-site (D-165-A = C). actionlint clean; reviewed directly by the owner's delegate (two-file workflow change). Owner post-merge step: once ci-gate has reported on main, switch required checks from build (21), build (25) and container-smoke to ci-gate alone.
