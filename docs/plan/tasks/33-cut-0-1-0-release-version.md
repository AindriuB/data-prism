# 33 — Cut the 0.1.0 release version and the GitHub Release workflow

**Repo:** data-prism
**Depends on:** none
**Owns:**
- pom.xml
- data-prism-*/pom.xml
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfiguredJsonSourcesPackagingIT.java
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java
- docker/server/Dockerfile
- docker/fixtures/Dockerfile
- docker/issuer/Dockerfile
- README.md — the artifact/jar version references only (`README.md:143`)
- .github/workflows/release.yml (new)

## Goal
The reactor is `0.1.0-SNAPSHOT` in 19 poms, in three Dockerfiles and hardcoded in
three integration tests that locate jars by filename; there are zero git tags and
zero releases. Drop `-SNAPSHOT` so the tree on `main` is the released coordinate
`0.1.0`, and add the workflow that turns a pushed `v0.1.0` tag into a GitHub
Release. This is the version every downstream strand carries: the Maven Central
artifacts (36) and the OCI image tag (37) both read it.

## Context
- pom.xml:10 — the root `<version>`; each module inherits it through `<parent>`
- ServerPackagingIT.java:77,180,208,337,366 and ConfiguredJsonSourcesPackagingIT.java:84,174
  and QuickstartSmokeIT.java:253,257,275 — jars located by literal filename, so the
  version string is load-bearing in tests, not only in poms
- docker/server/Dockerfile:20-21, docker/fixtures/Dockerfile:11, docker/issuer/Dockerfile:11
- docs/conventions.md — commit format `33: <imperative summary>`
- `docs/plan/HISTORY.md`, grep `Simplification wave 2` — the serialized-build rule:
  one `mvn clean verify` from one checkout, never concurrent builds against the
  shared local repository

## Acceptance
- [ ] `rg -n '0\.1\.0-SNAPSHOT' --glob '!**/target/**' .` prints nothing.
- [ ] Root `pom.xml` declares `<version>0.1.0</version>`; all 18 modules inherit it
      and no module declares a `<version>` of its own.
- [ ] A serialized `mvn -B clean verify` from a single checkout is green, with a
      test count at or above the 460 recorded for tasks 31-32, 0 failures, 0 errors.
- [ ] `.github/workflows/release.yml` triggers on `push: tags: ['v*']`, has
      `permissions: contents: write`, and creates a GitHub Release for the tag.
      It builds and tests before releasing; it does not publish to Maven Central
      and does not build an image.
- [ ] The release workflow does not run on `pull_request` or on pushes to `main`,
      so it cannot interfere with the `build` status check that branch protection
      requires.
- [ ] `git tag` still lists nothing on the task branch — tagging is a `/record`
      step, not the implementer's (see "Out of scope").

## Out of scope
- Creating the `v0.1.0` tag or the release itself. The implementer's job ends at
  the workflow on its branch; the owner pushes the tag during `/record`.
- `CHANGELOG.md`, `SECURITY.md`, `.github/dependabot.yml`, repo topics — task 34.
- Any publishing plugin, signing, `distributionManagement` or deploy-skip flag —
  task 36 owns every pom edit of that shape, after this one lands.
- Restructuring `docker/server/Dockerfile` into a distributable image — task 37
  builds a new file; this task only corrects its version string.
- The `mcp-name` marker or any registry prose in `README.md` — task 38.
- Bumping to a next development version (`0.2.0-SNAPSHOT`). `main` stays at
  `0.1.0` until a later planning decision says otherwise.
- `docs/**` — every doc update from this wave is a scribe hand-off at `/record`.
