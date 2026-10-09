# 184 — Pin Maven 3.9.16 with the Maven Wrapper and run CI through it

**Repo:** .
**Branch:** `fix/pin-maven`, from `origin/main` at `21f3a008` (the `v0.6.0` tag commit)
**Depends on:** none
**Owns:**
- mvnw (new)
- mvnw.cmd (new)
- .mvn/wrapper/maven-wrapper.properties (new)
- .gitattributes (insertions only: line-ending rules for `mvnw` / `mvnw.cmd`)
- .github/workflows/build.yml
- .github/workflows/publish-central.yml
- .github/workflows/publish-image.yml
- .github/workflows/release.yml
- CHANGELOG.md (the `## [Unreleased]` section only)
- README.md (the "Building and running" section only)

## Goal
The 0.6.0 Central publish (Actions run 37909637054) failed Central validation
with "Bundle has content that does NOT have a .pom file" for all 14 deployable
modules. The cause is that the `ubuntu-24.04` runner image moved from Maven
3.9.16 (image 20260927, which published 0.5.0) to 3.10.0 (image 20261004).
Under 3.10.0, central-publishing-maven-plugin 0.11.0 no longer runs its
"Pre Bundling - deleted .../maven-metadata-central-staging.xml" step, so
artifact-level metadata stays in the bundle. Nothing in the repo pins Maven.
This task pins Maven 3.9.16 through the official Maven Wrapper and makes
every workflow use it, so CI and releases no longer depend on the runner
image's `mvn`. It changes workflows and build tooling only, so the artifacts
are byte-equivalent and the owner can move tag `v0.6.0` to the fix commit
(as was done for 0.2.0).

## Context
- `.github/workflows/*.yml`: `git grep -n '\bmvn\b' -- .github` finds the
  runnable invocations at build.yml:35 and :45, publish-central.yml:63, :77,
  :143 and :162, publish-image.yml:279 and :513, and release.yml:27.
  `pages.yml` and `publish-mcp.yml` have none, but confirm that with the same
  grep. publish-central.yml:9, :11, :22 and :71 mention `mvn deploy` /
  `mvn verify` in comments.
- `pom.xml:290-301`: the enforcer rule `requireMavenVersion` is `[3.6.3,)`.
  Do not change it (see decision D-184-A).
- `docker/{distribution,fixtures,issuer,server}/Dockerfile` build on
  `maven:3.9-eclipse-temurin-25`, which stays on the 3.9 line and never
  deploys to Central. They are out of scope, and `.github/dependabot.yml`
  already ignores maven image tags from 3.9.26 up.
- `README.md:199-208`: the build instructions show
  `mvn -B --no-transfer-progress verify` and call it "the same command CI
  runs".
- `CHANGELOG.md:8`: `## [Unreleased]` is empty and `## [0.6.0]` starts at
  line 10.
- Wrapper: maven-wrapper-plugin 3.3.4 (latest on Central), distribution type
  `only-script`, so no `maven-wrapper.jar` is committed. One way to generate
  it, using an explicit plugin version so it does not resolve to whatever is
  newest:
  `mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dtype=only-script -Dmaven=3.9.16`
- Checksum, verified by the planner on 2026-10-09:
  `apache-maven-3.9.16-bin.zip` from `repo.maven.apache.org` matches the
  official `.sha512` (`ed41650d…33454af3`), and its SHA-256 is
  `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`.
  Check this again yourself. Do not copy it on trust.
- Never read `~/.m2/settings.xml`, `~/.gnupg/**` or any credential or token
  file, even to debug a failed build.

## Acceptance
- [ ] `mvnw` and `mvnw.cmd` are the files maven-wrapper-plugin 3.3.4
      generates for `only-script`. `git ls-files -s mvnw` shows mode `100755`.
      No `.mvn/wrapper/maven-wrapper.jar` and no `MavenWrapperDownloader.java`
      are committed.
- [ ] `.mvn/wrapper/maven-wrapper.properties` contains
      `distributionType=only-script`, a `distributionUrl` pointing at
      `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip`,
      and `distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`.
      The hand-off shows the command that re-derived this SHA-256 from a
      download whose SHA-512 matched Central's published `.sha512`.
- [ ] Tampering is caught. With `distributionSha256Sum` temporarily changed by
      one character and `~/.m2/wrapper/dists/apache-maven-3.9.16*` removed,
      `./mvnw -v` fails with a checksum error. Revert the change afterwards.
- [ ] `.gitattributes` gives `mvnw` `eol=lf` and `mvnw.cmd` `eol=crlf`, and
      the existing `characterisation/** -text` line is unchanged.
- [ ] `./mvnw -v` prints `Apache Maven 3.9.16`.
- [ ] `git grep -nE '(^|[^./])\bmvn ' -- .github/workflows` finds no runnable
      `mvn` invocation. Every `run:` line and every `$(mvn …)` substitution
      uses `./mvnw`. Comments that name a Maven command are reworded to
      `./mvnw …` or left generic, and none still claims a step runs plain
      `mvn`.
- [ ] Every `actions/setup-java` step keeps `cache: maven`. The wrapper
      distribution lands in `~/.m2/wrapper`, which that cache covers, so no
      new cache step is added.
- [ ] `./mvnw -B --no-transfer-progress verify` is green locally on JDK 21
      or 25. Run one Maven build at a time (docs/workflow.md:84).
- [ ] `./mvnw -B --no-transfer-progress -Prelease -DskipTests -Dgpg.skip package`
      is green locally.
- [ ] `CHANGELOG.md` gets one entry under `## [Unreleased]` → `### Build`
      saying that the build is pinned to Maven 3.9.16 through the Maven
      Wrapper and that CI and release workflows call `./mvnw`. It gives the
      reason in one clause: Maven 3.10.0 on the runner image broke the
      Central bundle. Everything from `## [0.6.0]` down is byte-identical
      (`git diff` touches no line at or after the `## [0.6.0]` heading).
- [ ] `README.md` "Building and running" shows
      `./mvnw -B --no-transfer-progress verify` (Windows: `mvnw.cmd`), says
      the wrapper downloads the pinned Maven 3.9.16, and keeps the
      "Maven >= 3.6.3" floor sentence true for people using a system `mvn`.
      No other doc is edited.
- [ ] `pom.xml` and every `docker/**` file are unchanged
      (`git diff --stat origin/main` lists none of them).
- [ ] The PR's CI (`build.yml`, both JDK legs, including the release-profile
      Javadoc step) is green, and its logs show the wrapper downloading or
      using Maven 3.9.16. The central-publishing plugin's "Pre Bundling -
      deleted" lines cannot be checked locally without Central credentials.
      This check stands in for that, and the owner's re-dispatch of
      publish-central is the real proof.

## Out of scope
- Moving tag `v0.6.0`, pushing, opening the PR, dispatching any workflow, or
  publishing anything. All of these belong to the owner.
- Changing the `[0.6.0]` CHANGELOG section or any release date.
- The enforcer `requireMavenVersion` range in `pom.xml` (D-184-A).
- A publish-central step that asserts the Maven version (D-184-A).
- The Docker build stages under `docker/` and their `maven:3.9-…` base tags.
- Upgrading central-publishing-maven-plugin or reporting the 3.10
  incompatibility upstream (D-184-C).
- Rewriting the many `mvn …` mentions in `docs/**` guides such as
  extending.md, protect-your-own-api.md and quickstart.md (D-184-D).
- Dependabot configuration (D-184-B).
- Any `docs/plan/` file other than this one.

## Decisions for the owner
- **D-184-A: guard the release path against Maven 3.10.** (a) Cap the
  enforcer at `[3.6.3,3.10)`. This breaks every local build for contributors
  whose system Maven is 3.10, including any `mvn` run that skips the wrapper.
  (b) Add a step to publish-central's `stage` and `publish` jobs that fails
  unless `./mvnw -v` reports exactly `3.9.16`. This touches only the release
  path, and costs nothing for contributors. (c) Rely on the wrapper alone.
  **Recommendation: (b), as a follow-up task after the tag move.** With the
  wrapper in place, (b) only catches someone reverting to bare `mvn`, which
  the acceptance grep already covers. It is cheap and it fails closed. Option
  (a) punishes local builds for a problem that only affects the Central
  bundle.
- **D-184-B: Dependabot and the wrapper.** Dependabot's `maven` ecosystem can
  propose `distributionUrl` bumps to the Maven wrapper, and one of those would
  move to 3.10.0 and bring the bug back. Recommendation: in a follow-up task,
  add an `ignore` for `org.apache.maven:apache-maven` with versions `>= 3.10`
  until D-184-C is resolved.
- **D-184-C: upstream fix.** Pinning works around the bug. It does not fix
  it. Recommendation: open a follow-up to test a newer
  central-publishing-maven-plugin on Maven 3.10 and, if it still fails,
  report the problem upstream. Unpin only after a staged bundle (the `stage`
  job artifact) under 3.10 has no `maven-metadata-central-staging.xml`.
- **D-184-D: docs beyond README.** Many guides show `mvn …`. Any Maven of
  3.6.3 or later builds the project, and the bug only affects Central
  bundling, so those commands are still correct. Recommendation: leave them.
  The README change is enough.
- **D-184-E: CHANGELOG placement.** The entry goes under `[Unreleased]` even
  though the tag will move to a commit that includes it. The 0.6.0 artifacts
  do not change, so the `[0.6.0]` notes stay accurate. Recommendation: keep
  it in `[Unreleased]` and let it ship in the next release's notes. The
  alternative is to edit the released `[0.6.0]` section after it was cut.
