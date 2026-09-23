# 84 — Correct the `dataprism.privacy` row's two false startup-refusal claims

**Repo:** `.`
**Depends on:** none
**Base branch:** the LOCAL `discoverability` branch (not `main`). `wt-new.sh`
bases new worktrees on `main`, so right after it, before any edit, run
`git -C <worktree> reset --hard discoverability` and confirm that
`docs/configuration.md:73` begins ``| `dataprism.privacy` |``. The branch
merges back into `discoverability`. This task file is uncommitted: read it
from `/srv/dev/projects/data-prism/docs/plan/tasks/84-correct-unclassified-profile-docs.md`.
**Owns:**
- docs/configuration.md *(the `dataprism.privacy` table row, line 73, only)*

## Goal
`docs/configuration.md:73` says startup is refused in two cases that have no
check behind them:
- "a production profile that relaxes fail-closed behaviour";
- "a profile that lacks a rule required by exposed models".

Owner decision 2026-09-23: correct the shipped doc now, so that the row states
only what the code does. Do not build either check. The relaxed-profile guard
is an unscheduled follow-up.

## Context
What the code does. Every clause you write must trace to one of these:
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/PrivacyProfiles.java:61-64`:
  a profile's `unclassified:` key defaults to `FAIL_REQUEST` when absent.
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/ProfilePrivacyPolicyResolver.java:124-146`:
  - `FAIL_REQUEST` refuses the whole response (`:126`).
  - `REDACT_AND_WARN` (`:127-131`) and `DROP_AND_WARN` (`:132-136`) redact or
    drop the field and log a warning with the field name.
  - `PASS_THROUGH_UNSAFE` (`:137-145`) releases the value unchanged and logs a
    warning with the field name.
  - Only `PASS_THROUGH_UNSAFE` releases the value. No warning includes the
    value.
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/PrivacyProfile.java:87`:
  nothing calls `releasesUnclassifiedData()`. Confirm with
  `grep -rn releasesUnclassifiedData --include=*.java . | grep -v /test/ | grep -v .worktrees`.
  It should print only the declaration.
- `data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java`:
  - `:354-358`: the resolver loads only the bundled
    `/privacy-profiles-default.yaml`.
  - `:571-577`: `validateProfile` checks only that the profile name exists
    (`UNKNOWN_PRIVACY_PROFILE`).
  - `:362-368`: startup is refused with `FORBIDDEN_PRIVACY_OVERRIDE` if an
    application `PrivacyPolicyResolver` bean competes with the framework's.
  - No `dataprism.*` property names a custom profile file.
- `data-prism-core/src/main/resources/privacy-profiles-default.yaml:18,69`:
  both shipped profiles are `unclassified: FAIL_REQUEST`.
- Result: the Spring Boot starter and the standalone server can reach only
  `FAIL_REQUEST` through configuration today. The other three values exist
  only for code that builds a `ProfilePrivacyPolicyResolver` directly.
- If the row mentions the audit, follow
  `data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java:211-217`:
  - A refusal is audited only as `policyDecision=DENY`, with no path, code or
    classification.
  - The code and path appear only in the error returned to the caller.
What the code does when a profile has no rule for a model's field. The
implementer must re-verify all of this; it comes from planning, not from a
test.
- Startup: no refusal code compares a profile's rules with the exposed models.
  To check, list every code with
  `grep -rhoE 'DataPrismConfigurationException\("[A-Z_]+"|refuse\("[A-Z_]+"' --include=*.java data-prism-*/src/main | sort -u`.
  Of these, only `UNKNOWN_PRIVACY_PROFILE` and `FORBIDDEN_PRIVACY_OVERRIDE`
  concern profiles. Then look for any other profile-versus-model validation,
  for example with `grep -rn "classifications()\|generalizations()" --include=*.java data-prism-*/src/main`.
- Request time, in `ProfilePrivacyPolicyResolver.java`:
  - `:66-70`: a classification with no rule in the profile is skipped.
  - `:81-87`: if none of the field's classifications has a rule, the field
    gets the annotation's suggested action. With no suggestion it gets
    `SAFE_DEFAULT`, which is `REDACT` (`:32`). The request is not refused.
  - `:103-114`: if the action resolves to `GENERALIZE` and the profile has no
    generalisation rule for the field's namespace, the resolver throws
    `IllegalStateException`. That exception reaches
    `DefaultContextOrchestrator.java:211-217`, which audits `DENY` and
    rethrows. So that one request fails, and startup does not.
  - `:43-47`: an unknown profile name also throws at request time. Startup
    already catches this case (`UNKNOWN_PRIVACY_PROFILE`).
- Constraints from the docs site (task 82):
  - `DataPrismConfigurationFailureAnalyzer.java:34` and its tests hard-code the
    path `docs/configuration.md`.
  - `PLAN.md` and other docs cite line numbers in this file.
  - So the file keeps its path, gets no front matter, and the row stays on one
    line.

## Acceptance
- [ ] `git diff discoverability --stat` lists only `docs/configuration.md`.
      `git diff discoverability --numstat -- docs/configuration.md` prints
      `1	1`.
- [ ] The changed line is still line 73. It still begins
      ``| `dataprism.privacy` |``. It still has exactly five `|` column
      separators outside backticks, so it keeps four cells.
- [ ] The "Secret-bearing" cell is still exactly `No`.
- [ ] Every other clause of the row is byte-identical to the base:
      `profile`, `locale`, scope lifetime, the whole `descriptor-file` text,
      unknown profile, unsupported locale, and non-positive scope lifetime.
      Only two parts may change: the unclassified-behaviour text, and the
      "lacks a rule required by exposed models" clause.
- [ ] The PR description records the re-verification of the second clause:
      - the output of the refusal-code grep from Context;
      - the result of the profile-versus-model search;
      - a statement that no startup check compares profile rules with the
        exposed models, or, if one is found, its file:line.
      If one is found, the clause stays and is corrected to match that check.
- [ ] If re-verification finds no startup check (the expected result):
      - `grep -c 'lacks a rule required by exposed models' docs/configuration.md`
        prints `0`;
      - the row no longer lists a missing rule among its startup refusals.
- [ ] In that case, the clause is either removed or rewritten to say what
      happens at request time, citing the file:line evidence in the PR
      description:
      - a classification with no profile rule falls back to the annotation's
        suggested action, or to `REDACT` if there is none, and the request is
        not refused (`ProfilePrivacyPolicyResolver.java:32,66-70,81-87`);
      - `GENERALIZE` with no generalisation rule fails that one request
        (`:103-114`, audited `DENY` at `DefaultContextOrchestrator.java:211-217`).
      Whichever it is, it sits under request-time behaviour, not in the list
      of startup refusals.
- [ ] The row says the shipped profiles fail closed: a field nobody
      classified refuses the whole response (`PrivacyProfiles.java:61-64`,
      `privacy-profiles-default.yaml:18,69`, `ProfilePrivacyPolicyResolver.java:126`).
- [ ] The row says that no `dataprism.*` property loads a custom profile file
      today, so only the bundled profiles can be selected
      (`DataPrismAutoConfiguration.java:354-358,571-577`).
- [ ] The row says that an application `PrivacyPolicyResolver` bean is refused
      at startup (`FORBIDDEN_PRIVACY_OVERRIDE`, `DataPrismAutoConfiguration.java:362-368`).
- [ ] If the row names `REDACT_AND_WARN`, `DROP_AND_WARN` or
      `PASS_THROUGH_UNSAFE`:
      - it describes them as `ProfilePrivacyPolicyResolver` settings that
        configuration cannot reach today;
      - it says each one logs a warning with the field name;
      - it says only `PASS_THROUGH_UNSAFE` releases the value
        (`ProfilePrivacyPolicyResolver.java:127-145`).
- [ ] Honesty check. This must print nothing:
      `git diff discoverability -U0 -- docs/configuration.md | grep '^+' | grep -niE 'relaxes|relaxed profile|refuse[sd]? startup for .*(unsafe|pass.through|relax|lacks|missing rule|required by exposed)|guarantee|compliant|anonymi|tamper-proof|robust|seamless|comprehensive'`
- [ ] Honesty check. The following must print only lines that do not claim a
      startup refusal for an unclassified-behaviour setting:
      `grep -niE 'relax|unsafe|pass.through|unclassified' docs/configuration.md`
- [ ] Line 1 of `docs/configuration.md` is still `# Configuration contract`.
      `head -1 docs/configuration.md | grep -c '^---'` prints `0`.
- [ ] `git diff discoverability --name-status` shows no rename, deletion or
      new file.

## Out of scope
- Building the startup guard, or adding any caller of
  `releasesUnclassifiedData()`. This is an unscheduled follow-up.
- Adding a property to load a custom profile file.
- Every other row, every clause of this row except the two named in Goal,
  and all prose outside the table.
- Adding a startup check that compares profile rules with the exposed models.
- The `privacy-profiles-default.yaml:8` comment ("only two settings") and any
  Java source or Javadoc.
- `README.md`, `docs/tools.md`, `docs/faq.md`, `docs/use-cases/**`,
  `CHANGELOG.md`, `docs/audit.md`. Specifically, these stay as follow-ups:
  - `README.md:12` and `docs/use-cases/gdpr-data-minimisation-mcp.md:25`
    ("redacted or refused");
  - `docs/tools.md:120` ("unclassified values dropped").
- Any docs-site file (task 82) or README section (task 83).
- Committing on `main`, or pushing.
