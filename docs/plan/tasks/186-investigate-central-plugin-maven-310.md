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

## Findings

Investigated 2026-10-09. No code, POM, workflow or wrapper file was changed.
A local run of the plugin was not done: the plugin needs a `central` server
entry in settings and a reachable upload endpoint, so a run was stopped under
the task's "if it would need a token, stop" rule. The root cause below rests on
published source of both Maven versions and the plugin, plus upstream reports.

### 1. Versions released after 0.11.0

None. Central metadata
`https://repo.maven.apache.org/maven2/org/sonatype/central/central-publishing-maven-plugin/maven-metadata.xml`
lists 0.1.1 ... 0.11.0 with `<latest>` and `<release>` both `0.11.0`,
`lastUpdated` 20260616114315. The `org.sonatype.central` plugin's source
repository (`github.com/sonatype/central-publishing-maven-plugin`) is not
public (GitHub API returns 404), so it has no public issue tracker.

A community fork exists under a different groupId, `io.github.mavenplugins`
(`https://repo.maven.apache.org/maven2/io/github/mavenplugins/central-publishing-maven-plugin/maven-metadata.xml`:
1.0.0, 1.1.0, 1.1.1, 1.2.0, 1.3.0, 1.3.1; latest 1.3.1, 2026-08-26). Source
repo `github.com/mavenplugins/central-publishing-maven-plugin`.

### 2. Does any candidate fix cleanup under Maven 3.10?

- `org.sonatype.central` 0.11.0 (current): no, it is the failing version. Its
  sources jar (`.../0.11.0/central-publishing-maven-plugin-0.11.0-sources.jar`)
  hard-codes one name in `utils/ProjectUtilsImpl.java:35`:
  `MAVEN_METADATA_CENTRAL_STAGING_XML = "maven-metadata-central-staging.xml"`.
  Lines 197-208 delete only that file at group, group/artifact and GAV level,
  logging `Pre Bundling - deleted %s` on success. A file with any other name is
  neither deleted nor logged.
- `io.github.mavenplugins` 1.3.1: no. Its sources jar has the same constant at
  `ProjectUtilsImpl.java:36`. Upstream issue
  `https://github.com/mavenplugins/central-publishing-maven-plugin/issues/79`
  (open, 2026-10-09) reports the same failure for 1.3.1 and 0.11.0. Earlier
  fork versions share the lineage and are not candidates. Switching groupId
  would also be an owner decision about trusting a third-party fork.
- No other released version exists, so no version fixes it.

### 3. Root cause

A Maven 3.10.0 change, not a plugin regression. The plugin stages with the
maven-compat `ArtifactInstaller.install(file, artifact, repository)`
(`stager/ArtifactStagerImpl.java`), using a repository created with id
`central-staging` and the default layout.

- Maven 3.9.16 `maven-core` `LegacyLocalRepositoryManager.overlay()` always
  wraps the target repository in a `LegacyLocalRepositoryManager`, whose local
  metadata file name is `maven-metadata-<repositoryId>.xml`, so
  `maven-metadata-central-staging.xml`.
- Maven 3.10.0 (`maven-core-3.10.0-sources.jar`, same class) adds a branch:
  `if (repository.getLayout() instanceof DefaultRepositoryLayout)` it installs
  through a regular Resolver 2 manager
  (`DefaultRepositorySystemSessionFactory.setUpLocalRepositoryManager`). That
  writes `maven-metadata-local.xml` (`LocalRepository.ID = "local"` in
  maven-resolver-api 2.0.24, used by `SimpleLocalRepositoryManager.getPathForLocalMetadata`),
  plus `<version>/_remote.repositories` and `.locks/*.lock`. The plugin's
  cleanup no longer matches, nothing is deleted, the "Pre Bundling - deleted"
  line is absent, and Central rejects the bundle: "Bundle has content that does
  NOT have a .pom file". The maven-compat `DefaultArtifactInstaller` is
  byte-identical between 3.9.16 and 3.10.0; the difference is entirely in
  `LegacyLocalRepositoryManager`.
- The 3.10.0 release notes
  (`https://maven.apache.org/docs/3.10.0/release-notes.html`) mention Resolver
  2.x but list no change to this behaviour as breaking.

Upstream, independently confirming this table of file names and the cause:
- Maven: `https://github.com/apache/maven/issues/13388` (open, 2026-10-08),
  "LegacyLocalRepositoryManager.overlay() now writes Resolver local-repository
  files into the target repository (breaks central-publishing-maven-plugin)".
  It traces the change to #11778 and notes the plugin sources are not public
  and the reporter is contacting Sonatype support.
- Maven fix proposal: `https://github.com/apache/maven/pull/13389` (open, base
  `maven-3.10.x`, not merged, no milestone): restrict the Resolver manager to
  `"local".equals(repository.getId())`. One review approves; another requests
  changes (drop the `DefaultRepositoryLayout` branch entirely, as in 4.x). No
  Maven 3.10.1 exists on Central yet (metadata lists 3.10.0 as the newest 3.10).
- Plugin: `https://github.com/mavenplugins/central-publishing-maven-plugin/issues/78`
  and `/issues/79` (open), suggesting deleting `maven-metadata*.xml` by prefix
  and excluding `_remote.repositories`.

Note that deleting only `maven-metadata-local.xml` would not be enough on its
own: `_remote.repositories` and `.locks/` also land in the bundle under 3.10.0.
The issue 13388 reporter gave a build-side workaround (not tested here, not
recommended for adoption without a staging check):
`-Daether.priority.VersionsMetadataGeneratorFactory=NaN
-Daether.priority.EnhancedLocalRepositoryManagerFactory=NaN
-Daether.syncContext.named.factory=rwlock-local`.

### 4. Fixing version: none exists

So there is nothing to bump. The fix will come from one of: a Maven 3.10.x
release containing PR 13389 (or an equivalent), or a plugin release that
cleans `maven-metadata*.xml`, `_remote.repositories` and `.locks`. Both are
already tracked upstream; Sonatype's own tracker is not public.

Recommendation: keep the Maven 3.9.16 wrapper pin and the task 185 guards.
When either upstream fix ships, verify it as below before unpinning.

Correction to the verification plan in this task's Acceptance: the suggested
`-DskipPublishing=true` check will not produce a bundle. In 0.11.0
`PublishMojo.processRelease` filters out every artifact when `skipPublishing`
is true (lines 417-422), so nothing is staged and no bundle is built. The
check that does work offline of Central's validation is to point the plugin at
a closed loopback port, as in issue 13388's reproduction
(`-DcentralBaseUrl=http://127.0.0.1:9`; the parameter is `centralBaseUrl`, not
`central.baseUrl`): the bundle is built and the upload then fails locally. It
still needs a `central` server entry in settings, so run it only in CI where
the existing secrets are supplied, or with the owner's agreement.

### 5. Proposed check for when a fix ships (not applied)

1. Maven fix: a Maven 3.10.x release with PR 13389 or equivalent. CI check:
   dispatch publish-central on a branch with that Maven and
   `-DcentralBaseUrl=http://127.0.0.1:9` (no upload to Central), upload
   `target/central-publishing/central-bundle.zip` as an artifact, and run
   `unzip -l` on it. Pass means no `maven-metadata*.xml` at group or artifact
   level, no `_remote.repositories`, no `.locks/`, and 14 "Pre Bundling -
   deleted" lines in the log.
2. Plugin fix: same check, after setting `central-publishing-plugin.version`
   in `pom.xml:90` from `0.11.0` to the fixed version.
3. The wrapper pin and the task 185 guards stay until that check passes.

### 6. Ready-to-file upstream issue (for the owner)

Where: `https://github.com/mavenplugins/central-publishing-maven-plugin` has
public trackers but is a fork; the owning plugin (`org.sonatype.central`) has
no public tracker, so use Sonatype Central support. Existing reports to link
instead of duplicating: mavenplugins issues 78 and 79, apache/maven 13388.

Title: central-publishing-maven-plugin 0.11.0 on Maven 3.10.0: staging cleanup
misses `maven-metadata-local.xml`, Central rejects bundle ("does NOT have a
.pom file")

Versions: `org.sonatype.central:central-publishing-maven-plugin:0.11.0`;
Apache Maven 3.10.0 (works on 3.9.16); GitHub `ubuntu-24.04` runner image
20261004 (Maven 3.10.0) versus 20260927 (Maven 3.9.16); Java 21 bytecode.

Steps to reproduce:
1. Multi-module Maven project, release version, plugin 0.11.0 declared with
   `<extensions>true</extensions>`, `publishingServerId` `central`,
   `autoPublish` false.
2. Run `mvn deploy` with Maven 3.10.0 (the same command works with 3.9.16).
3. Inspect `target/central-staging` and the uploaded bundle.

Expected: the log shows "Pre Bundling - deleted
.../maven-metadata-central-staging.xml" for each module; the bundle holds only
artifacts, poms and checksums; Central validates it.

Actual: no "Pre Bundling - deleted" line appears. The bundle contains
`<group>/<artifact>/maven-metadata-local.xml`, `<version>/_remote.repositories`
and `.locks/*.lock`. Central rejects it for all 14 deployable modules with
"Bundle has content that does NOT have a .pom file: <group path>/<artifact>".

Cause: Maven 3.10.0 `LegacyLocalRepositoryManager.overlay()` now uses a
Resolver 2 local repository manager for default-layout repositories, so the
staging install writes `maven-metadata-local.xml` instead of
`maven-metadata-central-staging.xml`; `ProjectUtilsImpl` deletes only the
latter. See apache/maven 13388 and PR 13389.

Suggested fix: in `ProjectUtilsImpl.deleteMavenMetadataCentralStagingXml`
delete any `maven-metadata*.xml`; also exclude `_remote.repositories` and the
`.locks` directory from the bundle; keep working if Maven restores the 3.9
file name.

No tokens, deployment ids or repository secrets are included. The failing
run's public Actions run id is 37909637054.

### 7. Sources

- Central metadata, plugin and fork URLs above; `maven-core` sources
  `3.9.16` and `3.10.0`; `maven-compat` sources both; `maven-resolver-impl`
  and `-api` 2.0.24 sources (all from repo.maven.apache.org, extracted in the
  session scratchpad, not executed).
- Maven 3.10.0 release notes URL above; apache/maven 13388, 13389;
  mavenplugins 78, 79.
