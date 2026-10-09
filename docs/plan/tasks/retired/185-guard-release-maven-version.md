# 185 — Guard the Central release path against Maven 3.10 and stop Dependabot reintroducing it

**Repo:** .
**Branch:** from `origin/main` at `87a2a720` (the moved `v0.6.0` tag)
**Depends on:** none
**Owns:**
- .github/workflows/publish-central.yml
- .github/dependabot.yml
- CHANGELOG.md (the `## [Unreleased]` → `### Build` list only)
- docs/plan/tasks/185-guard-release-maven-version.md (Outcome section only)

## Goal
Task 184 pinned Maven 3.9.16 through the Maven Wrapper because Maven 3.10.0
made central-publishing-maven-plugin 0.11.0 skip its metadata cleanup, so
Central rejected the bundle. This task adds two fail-closed guards the owner
decided (D-184-A = b, D-184-B): the Central workflow refuses to run on any
Maven other than 3.9.x, and Dependabot never proposes moving the wrapper to
Maven 3.10 or later.

## Context
- `.github/workflows/publish-central.yml:33-77` — the `stage` job: checkout,
  `actions/setup-java@v6`, then the first `./mvnw` call at :63 (tag guard).
- `.github/workflows/publish-central.yml:109-162` — the `publish` job: same
  shape, first `./mvnw` call at :143. Follow the existing guard-step style
  (a `name:`, a comment above saying why, `::error::` then `exit 1`).
- `.mvn/wrapper/maven-wrapper.properties` — `distributionUrl` points at
  `org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip`.
- `.github/dependabot.yml:4-15` — the single `maven` ecosystem entry at
  `directory: "/"`, with an existing `ignore` list. The docker entry's ignore
  comments (:33-46) are the comment style to follow.
- `docs/plan/tasks/184-pin-maven-wrapper.md` — "Decisions for the owner",
  D-184-A and D-184-B, for the reasoning.
- Dependabot's Maven ecosystem updates the wrapper's `distributionUrl` and
  names that dependency `org.apache.maven:apache-maven` (confirm against
  GitHub's Dependabot docs or the dependabot-core maven wrapper file parser
  source, and cite the URL in the Outcome; if the name differs, use the real
  one and say so).
- Never read `~/.m2/settings.xml`, `~/.gnupg/**` or any credential or token
  file, even to debug a failed build.

## Acceptance
- [ ] Both `stage` and `publish` in `publish-central.yml` have a new step,
      placed after `actions/setup-java` and before every other `./mvnw`
      call in that job, that runs `./mvnw -v` and exits non-zero with an
      `::error::` line unless the output's first line matches
      `^Apache Maven 3\.9\.[0-9]+( |$)`. The step does not swallow a
      failing `./mvnw -v` (for example, it uses `set -o pipefail` or
      captures the output then tests it, so a wrapper checksum failure also
      fails the step).
- [ ] Shown in the Outcome: the guard's shell logic run locally against
      three sample first lines, `Apache Maven 3.9.16 (abc)` (passes),
      `Apache Maven 3.10.0 (abc)` (fails), and `Apache Maven 3.9` with no
      patch (fails), plus `./mvnw -v` in the repo (passes).
- [ ] Each new step has a comment naming the reason (Maven 3.10.0 with
      central-publishing-maven-plugin 0.11.0 leaves artifact-level
      `maven-metadata*.xml` in the bundle; see task 184 / D-184-A).
- [ ] `pom.xml` is unchanged; the enforcer `requireMavenVersion` range stays
      `[3.6.3,)` (`git diff --stat origin/main` does not list `pom.xml`).
- [ ] `.github/dependabot.yml`'s `maven` entry has an `ignore` item with
      `dependency-name: "org.apache.maven:apache-maven"` and
      `versions: [">= 3.10"]`, with a comment giving the reason and saying it
      is lifted only after D-184-C (task 186) shows a fix. The existing
      Spring Boot ignore is unchanged.
- [ ] The Outcome confirms, with a cited source, that the `maven` entry at
      `directory: "/"` covers `.mvn/wrapper/maven-wrapper.properties` and that
      the dependency name is the one used.
- [ ] `python3 -c 'import yaml,sys;[yaml.safe_load(open(f)) for f in sys.argv[1:]]' .github/dependabot.yml .github/workflows/publish-central.yml`
      exits 0, and `actionlint` passes on `publish-central.yml` if it is
      installed (say in the Outcome if it is not).
- [ ] `CHANGELOG.md` gains two lines under `## [Unreleased]` → `### Build`,
      after the existing Maven Wrapper line: one for the publish-central
      Maven 3.9.x guard, one for the Dependabot ignore. `git diff` touches no
      line at or after the `## [0.6.0]` heading.
- [ ] No other workflow file is changed.

## Out of scope
- Capping the enforcer in `pom.xml` (D-184-A rejected option a).
- Guards in `build.yml`, `publish-image.yml`, `release.yml` or any other
  workflow.
- Bumping or unpinning central-publishing-maven-plugin or the wrapper's Maven
  version (task 186 investigates; nothing is applied).
- Docker `maven:` base-image ignores in the `docker` Dependabot entry.
- Dispatching any workflow, pushing, tagging or publishing.
- `docs/plan/PLAN.md`, `HISTORY.md`, `HISTORY-INDEX.md` (task 187 / scribe).

## Outcome
- `publish-central.yml`: new step "Guard Maven is 3.9.x" in `stage` and `publish`, directly after `actions/setup-java` (in `stage`, before the tag guard; in `publish`, before the tag guard). It captures `./mvnw -v` (`|| { ::error::; exit 1; }`, so a wrapper failure fails the step), takes the first line and requires `^Apache Maven 3\.9\.[0-9]+( |$)`. Each has a comment citing 3.10.0 + central-publishing-maven-plugin 0.11.0 and task 184 / D-184-A.
- Local run of the extracted step body (`bash -e -o pipefail`, fake `mvnw`): `Apache Maven 3.9.16 (abc)` exit 0; `Apache Maven 3.10.0 (abc)` exit 1; `Apache Maven 3.9` exit 1; `garbage` exit 1; `mvnw` exiting 1 gives exit 1. Real `./mvnw -v` in the repo: exit 0.
- `pom.xml` untouched; no other workflow changed.
- `dependabot.yml`: `maven` entry gains `dependency-name: "org.apache.maven:apache-maven"`, `versions: [">= 3.10"]`, with a reason comment; the Spring Boot ignore is unchanged.
- Dependency name confirmed in dependabot-core source: https://github.com/dependabot/dependabot-core/blob/main/maven/lib/dependabot/maven/file_parser/wrapper_mojo.rb (header comment: tracked coordinates `org.apache.maven:apache-maven` and `org.apache.maven.wrapper:maven-wrapper`; `name: Distributions::MAVEN_DISTRIBUTION_PACKAGE`, property `distributionUrl`). `maven/lib/dependabot/maven/file_parser.rb` (`wrapper_properties_files` selects files ending `maven-wrapper.properties`) and `file_fetcher.rb` (`WRAPPER_PROPERTIES_RELATIVE = ".mvn/wrapper/maven-wrapper.properties"`) show the `directory: "/"` entry covers it. Caveat: `add_wrapper_dependencies` returns early unless the `maven_wrapper_updater` experiment is enabled, so if that is off for this repo Dependabot does not touch the wrapper at all and the ignore is merely a harmless safeguard. I did not verify the flag's state on GitHub's hosted service.
- YAML: PyYAML is not installed here, so the `python3 -c 'import yaml...'` check could not run; both files parse with Ruby `YAML.safe_load`. `actionlint` (installed) passes on `publish-central.yml`.
- `CHANGELOG.md`: two lines added under Unreleased / Build; nothing at or after `## [0.6.0]` touched.
