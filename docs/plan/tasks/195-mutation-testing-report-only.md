# 195 — Run mutation testing on the privacy modules, report only

**Repo:** .
**Branch:** from `main` after task 188 has merged
**Depends on:** 188 (both edit `pom.xml`)
**Decided (owner, 2026-10-09):** D-070-9 (a): PIT on `data-prism-core`,
`data-prism-pseudonymisation`, `data-prism-validation` and
`data-prism-orchestration`; weekly schedule plus manual dispatch; report only.
**Owns:**
- pom.xml (a `mutation` profile and a `pluginManagement` entry only)
- .github/workflows/mutation.yml (new)
- CONTRIBUTING.md (one "Mutation testing" subsection only)
- docs/plan/tasks/195-mutation-testing-report-only.md (Outcome section only)

## Goal
1.0 promises mutation testing gating the privacy modules; 0.9 turns the gate
on. This task adds the instrument and takes its first reading: PIT runs on
demand and on a schedule over the privacy modules, publishes its report as a
CI artifact, and never fails a build. The first scores per module are recorded
so 0.9 can set thresholds from a measurement, not a guess.

## Context
- `pom.xml:44-62` modules, `:308-360` the `release` profile (task 188 adds an
  SBOM plugin there; do not touch it).
- Tests are JUnit 5 (PIT needs `pitest-junit5-plugin`). Bytecode is
  `--release 21`, the build JDK is 25: confirm the PIT version supports both.
- `docs/conventions.md` "Concurrent Maven verification": a PIT run writes
  `target/`; never run it alongside another build in the same worktree, and
  build with `-am`.
- `.github/workflows/build.yml` — the existing job shape (`setup-java@v6`,
  `./mvnw`, permissions) to follow; do not edit it.
- Never read `~/.m2/settings.xml`, `~/.gnupg/**` or any credential file.

## Acceptance
- [ ] `./mvnw -B verify` without the profile does not run PIT (show that the
      plugin is absent from `./mvnw help:effective-pom` output without
      `-Pmutation`, or equivalent).
- [ ] `./mvnw -B -Pmutation verify -pl data-prism-core,data-prism-pseudonymisation,data-prism-validation,data-prism-orchestration -am` (exact command
      in `CONTRIBUTING.md`) exits 0 and writes an HTML and XML PIT report per
      module; the build does not fail on low scores or surviving mutants
      (no `mutationThreshold`, `coverageThreshold` or equivalent set).
- [ ] `mutation.yml` runs that command weekly (`schedule`) and on `workflow_dispatch`, not on pull requests, with
      `permissions: contents: read` and uploads the reports as artifacts;
      `actionlint` exits 0.
- [ ] The Outcome records, per module, mutations generated, killed, survived,
      no-coverage and the score, with the commit and wall-clock time, plus the
      ten surviving mutants in privacy-path classes (engine, policy,
      pseudonymisation, validators, orchestrator refusal paths) most worth a
      test, as follow-up candidates. Nothing is fixed in this task.
- [ ] The Outcome carries a CHANGELOG `### Build` line for task 201.

## Out of scope
- Any threshold or gate (0.9).
- Writing tests to kill survivors.
- `build.yml` or any other workflow.
