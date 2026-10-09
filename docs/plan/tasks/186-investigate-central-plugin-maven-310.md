# 186 — Investigate whether a newer central-publishing-maven-plugin works under Maven 3.10

**Repo:** .
**Depends on:** none
**Owns:**
- docs/plan/tasks/186-investigate-central-plugin-maven-310.md (a `## Findings` section appended at the end)

## Goal
Decision D-184-C: the Maven 3.9.16 pin works around a bug, it does not fix it.
Find out, with evidence, whether any released central-publishing-maven-plugin
newer than 0.11.0 restores the "Pre Bundling - deleted
…/maven-metadata-central-staging.xml" step under Maven 3.10, so the owner can
decide whether to bump the plugin or report the problem upstream. This is an
investigation: no code, POM, workflow or wrapper file changes.

## Context
- `pom.xml:90` — `central-publishing-plugin.version` is `0.11.0`;
  `pom.xml:360-370` is the plugin block.
- `docs/plan/tasks/184-pin-maven-wrapper.md` — Goal and D-184-C. The failing
  run was Actions run 37909637054 (Maven 3.10.0 on runner image 20261004);
  the passing re-run after the tag move logged 14 "Pre Bundling - deleted"
  lines on Maven 3.9.16.
- Upstream: `https://github.com/sonatype/central-publishing-maven-plugin`
  (releases, issues, source of the bundling/metadata cleanup step) and the
  plugin's versions on Central
  (`https://repo.maven.apache.org/maven2/org/sonatype/central/central-publishing-maven-plugin/maven-metadata.xml`).
- Maven 3.10.0 release notes, for changes to local-repository or metadata
  file naming that would explain why the cleanup no longer matches.
- Local reproduction without credentials is allowed:
  `./mvnw` (3.9.16) versus a separately downloaded Maven 3.10.0 binary in the
  scratchpad, running
  `-Prelease -DskipTests -Dgpg.skip -DskipPublishing=true deploy` only if the
  plugin builds the bundle without contacting Central under that flag.
  If it would need a token, stop and propose the CI check instead.
- Never read `~/.m2/settings.xml`, `~/.gnupg/**` or any credential or token
  file. Do not pass any credential on the command line.

## Acceptance
- [ ] `## Findings` lists every plugin version released after 0.11.0 (or
      states none exist), with the Central metadata URL as the source.
- [ ] For each candidate, Findings states whether it fixes metadata cleanup
      under Maven 3.10, backed by at least one of: a linked upstream issue,
      PR, commit or release note; a quoted source line from the cleanup code;
      or a local run's log excerpt. "Probably" without evidence is not a
      finding.
- [ ] Findings names the root cause as far as the evidence shows (which
      Maven 3.10 change the plugin's cleanup no longer matches), or says it is
      unknown.
- [ ] If a fixing version exists: Findings proposes, without applying, the
      `pom.xml` property change and a credentialed-CI verification: dispatch
      publish-central on a branch with Maven 3.10 and
      `-DskipPublishing=true`, upload the bundle as an artifact, and
      `unzip -l` it to show no artifact-level `maven-metadata*.xml`; it also
      states that the wrapper pin and the task 185 guards stay until that
      check passes.
- [ ] If no fixing version exists: Findings contains a ready-to-file upstream
      issue text (title, Maven and plugin versions, reproduction steps,
      expected versus actual, the missing "Pre Bundling - deleted" log line,
      and Central's "Bundle has content that does NOT have a .pom file"
      error), with no repository secrets, tokens or deployment ids beyond
      what is already public.
- [ ] `git diff --stat origin/main` lists only this task file.

## Out of scope
- Changing `pom.xml`, `.mvn/**`, `mvnw*`, any workflow, or `dependabot.yml`.
- Filing the upstream issue (the owner files it).
- Unpinning Maven or relaxing any guard from task 185.
- Running anything that talks to Central or uses a credential.
