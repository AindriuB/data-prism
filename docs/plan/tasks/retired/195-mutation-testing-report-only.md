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

## Outcome

Instrument added: `mutation` profile in `pom.xml` (pitest-maven 1.30.0,
pitest-junit5-plugin 1.2.3, pinned; the plugin version is also in
`pluginManagement`), `.github/workflows/mutation.yml` (weekly Monday 03:17 UTC
plus `workflow_dispatch`; no `pull_request`, no secrets, `contents: read`,
`actionlint` exits 0) and a "Mutation testing" subsection in `CONTRIBUTING.md`.
PIT 1.30.0 ran cleanly on JDK 25 against `--release 21` bytecode. No threshold
is set.

Default build proof: `./mvnw -B help:effective-pom -pl data-prism-core`
without `-Pmutation` contains pitest-maven only in `pluginManagement` (one
match, `<pitest-plugin.version>` property aside) and zero occurrences of
`mutationCoverage`. With `-Pmutation` the plugin is also in `build/plugins`
and `mutationCoverage` is bound to `verify` (one occurrence).

Run: `./mvnw -B --no-transfer-progress -Pmutation verify -pl
data-prism-core,data-prism-pseudonymisation,data-prism-validation,data-prism-orchestration -am`
(the exact workflow command), measured on 523fd730 plus the then-uncommitted profile (the profile
commit on this branch is the same code),
JDK 25, 4 threads, bounded by `perl alarm 3600`. Exit 0, BUILD SUCCESS,
4 min 8 s wall-clock. Score = (killed + timed out) / generated.

| Module | Generated | Killed | Survived | No coverage | Score | PIT time |
|---|---|---|---|---|---|---|
| data-prism-core | 1558 | 1263 (1252 + 11 timed out) | 183 | 112 | 81% | 3 min 15 s |
| data-prism-pseudonymisation | 128 | 110 | 11 | 7 | 86% | 8 s |
| data-prism-validation | 113 | 77 | 25 | 11 | 68% | 5 s |
| data-prism-orchestration | 181 | 143 (142 + 1 timed out) | 20 | 18 | 79% | 16 s |

(An earlier core-only run gave 1264 killed, 182 survived: one mutant is
timing-sensitive. Treat core as 81% plus or minus a mutant.) Reports are in
`<module>/target/pit-reports/` (HTML and XML).

Ten survivors most worth a test, ranked (privacy-path classes):

1. `JsonTreeScrubbingEngine.scrubObject` 189, negated conditional: the main
   scrub branch flips and no test notices, so a field could pass unscrubbed.
2. `JsonTreeScrubbingEngine.scrubObject` 155, boundary: an off-by-one on an
   engine limit or index that no test pins.
3. `JsonTreeScrubbingEngine.scrubNestedObject` 286, addition to subtraction:
   nesting-depth arithmetic is unchecked, so depth limits may not hold.
4. `JsonTreeScrubbingEngine.scrubNestedObject` 302, removed void call: a
   side effect of nested scrubbing is not asserted.
5. `ProfilePrivacyPolicyResolver.resolve` 92, negated conditional: policy
   selection can invert without a failing test.
6. `PrivacyProfiles.rule` 135, negated conditional: the rule lookup for a
   field can flip.
7. `ActionStrictness.stricter` 51, boundary: which of two actions wins is the
   fail-closed decision.
8. `RefusalPaths.redact` 50, boundary: the refusal path's redaction edge.
9. `SensitiveDataScanner` check-digit validators (IBAN `ibanChecksumValid`
   246 to 249, `luhnValid` 261 to 268): a validator that wrongly accepts or
   rejects changes what the leak scanner reports.
10. `SourceCircuitBreaker.record` 68 to 75 and `DefaultContextOrchestrator.fieldsByNamespace`
    262 to 271: orchestrator failure and grouping paths that are not asserted.

The full per-module list follows.

Survivor candidates by module (class, method, line; nothing fixed here).

core, engine and policy:
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java`
  `scrubObject` 189 (negated conditional), `scrubObject` 155 (boundary),
  `scrubNestedObject` 302 (removed void call), `scrubNestedObject` 286
  (addition replaced with subtraction, depth arithmetic).
- `.../core/policy/ProfilePrivacyPolicyResolver.java` `resolve` 92.
- `.../core/policy/PrivacyProfiles.java` `rule` 135, `generalizations` 112.
- `.../core/refusal/RefusalPaths.java` `redact` 50.
- `.../core/policy/ActionStrictness.java` `stricter` 51.

Follow-up note for test strengthening: the four `JsonTreeScrubbingEngine`
survivors above are the first thing to cover (nested-object depth accounting
and the scrub-object branch conditions); the engine is the privacy path.

pseudonymisation:
- `data-prism-pseudonymisation/.../VocabularyRegistry.java` `resolve` 83,
  `withBuiltIns` 60, `digest` 235 to 237.
- `.../MultiKeySecretKeyProvider.java` `remove` 83, constructor 47.
- `.../EnvironmentSecretKeyProvider.java` `secret` 53.

validation:
- `data-prism-validation/.../SensitiveDataScanner.java` the IBAN checksum
  (`ibanChecksumValid` 246 to 249), `luhnValid` 261 to 268, the national-ID
  check-character method (lines 280 to 281), and the tree walk `node` 91 to
  106 and `scalar` 118 to 129.
- `.../RawValueLeakValidator.java` `walk` 63.

orchestration:
- `data-prism-orchestration/.../DefaultContextOrchestrator.java`
  `fieldsByNamespace` 262 to 271, `buildContext` 147, `audit` lambda 377.
- `.../SourceCircuitBreaker.java` `record` 68 to 75, constructor 34.
- `.../AuditedRefusalException.java` `getMessage` 47.
- `.../InstructionContentHeuristic.java` `suspect` 46.
- `.../ParameterFingerprinter.java` `fingerprint` 38.

Follow-up: after this merges, re-run the `mutation` workflow with
`workflow_dispatch` on the merge commit to pin the baseline from which the
0.9 thresholds are set; the figures above were measured locally before merge.

CHANGELOG line for task 201, under `### Build`:

- Added a report-only `mutation` Maven profile (PIT) and a weekly and
  manual `mutation` workflow over the core, pseudonymisation, validation and
  orchestration modules; it sets no threshold and never fails a build.
