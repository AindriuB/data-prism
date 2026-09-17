# 41 — Cut version 0.1.1 across the repository

**Repo:** data-prism
**Depends on:** none
**Owns:**
- pom.xml
- data-prism-*/pom.xml
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfiguredJsonSourcesPackagingIT.java
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java
- docker/server/Dockerfile
- docker/fixtures/Dockerfile
- docker/issuer/Dockerfile
- docker/distribution/Dockerfile
- .github/workflows/publish-image.yml
- README.md
- CHANGELOG.md
- server.json

`README.md` and `CHANGELOG.md` are normally scribe's. They are in this task's
`Owns` because they carry the version label itself, not a narrative about it —
splitting a version bump across two agents would leave the tree internally
inconsistent between them. `docs/plan/PLAN.md` and `docs/plan/HISTORY*.md` stay
with scribe and are out of scope below.

## Goal

Move the whole tree from `0.1.0` to `0.1.1` so that a multi-architecture image
and a Maven Central release can be published from a tree whose contents match
their version label. Nothing but the version label changes.

**Why 0.1.1 and not a re-push of 0.1.0.** 0.1.0 was released and published to
Maven Central. Since then `main` has moved. Most importantly it now carries
nimbus-jose-jwt 10.9.1 (PR #55) — a security patch on the JWT verification
path, pinned at `data-prism-quickstart-issuer/pom.xml:47` — plus the
multi-architecture publish pipeline from task 40 and three GitHub Actions
version bumps. An image built from current `main` but tagged `:0.1.0` would
carry a different JWT library than the 0.1.0 artifacts already on Maven
Central: same version string, different dependencies.

`.github/workflows/publish-image.yml:30-36` records the earlier decision to
re-push `:0.1.0` as multi-arch rather than cut a new version. That decision was
sound when it was taken, because `main` and the `v0.1.0` tag were then the same
tree, and its own stated condition was that nothing external referenced the
image. It has since expired for a different reason than the one it anticipated:
the trees diverged. The decision was revisited on 2026-09-17 and reversed. Say
so in the comment you replace it with, so the next reader does not re-derive it.

## Context

- `.github/workflows/publish-image.yml:30-47` — the expired "re-push as 0.1.0"
  rationale comment, and the `workflow_dispatch` input whose `default:` is the
  version string. Both move; the comment is rewritten, not just renumbered.
- `.github/workflows/publish-mcp.yml:57-65` — asserts `.version` and
  `.packages[0].version` in `server.json` are equal. `server.json:11` and
  `server.json:17` are those two fields. They must move together or this guard
  fails the publish.
- `docker/distribution/Dockerfile:55-65` — task 40's `ARG VERSION=0.1.0` and the
  `COPY` of `data-prism-server-${VERSION}.jar`. The comment block above it
  states the design: the default must agree with the reactor version or the
  build fails loudly rather than packaging nothing. That failure mode is
  desired; do not weaken it.
- `data-prism-mcp/.../DataPrismMcpServer.java:86` and `:127` — the version is a
  hard-coded literal in the MCP `serverInfo` handshake, in main source, in two
  places. It is the version the server advertises to clients, so it moves too.
- `CHANGELOG.md:8` and `:38` — the existing 0.1.0 entry and its link reference.
  Both stay exactly as they are; 0.1.1 is added above, with its own link
  reference alongside.
- 18 module poms plus the root carry `<version>0.1.0</version>`. Do not rely on
  this list being complete — derive it from the grep in the first acceptance
  item.

## Acceptance

- [ ] `rg -n '0\.1\.0' -g '!target/**' -g '!**/target/**' -g '!.git/**'` returns
      hits only in `docs/plan/HISTORY.md`, `docs/plan/HISTORY-INDEX.md`,
      `docs/plan/PLAN.md`, and `CHANGELOG.md`'s own historical 0.1.0 entry and
      its `[0.1.0]:` link reference. Every other hit is gone. Paste the
      surviving hit list in the PR description.
- [ ] `mvn -B clean verify` is green and reports 466 tests, the same count as
      before this change. A different count means something other than a
      version label moved.
- [ ] `jq -r '.version' server.json` and `jq -r '.packages[0].version'
      server.json` both print `0.1.1`, and `[ "$(jq -r .version server.json)" =
      "$(jq -r .packages[0].version server.json)" ]` succeeds — the same
      comparison `publish-mcp.yml` makes.
- [ ] `mcp-publisher validate` passes against `server.json`. If the CLI cannot
      be obtained on this machine, say so explicitly in the PR description and
      state that the two-field check above was run instead; do not silently skip.
- [ ] All four Dockerfiles build: `docker build -f docker/distribution/Dockerfile .`
      and the three others. If a build cannot be run locally, then at minimum
      every version-bearing line in each of the four was updated, quoted line by
      line in the PR description — including that
      `docker/distribution/Dockerfile`'s `ARG VERSION` default equals the new
      reactor version, so its `COPY` resolves.
- [ ] `CHANGELOG.md` has a `## [0.1.1]` entry that is short and honest: a
      nimbus-jose-jwt 10.9.1 security patch on the JWT verification path,
      multi-architecture (`linux/amd64` + `linux/arm64`) image support, and CI
      action bumps. No features. It states plainly that 0.1.1 supersedes 0.1.0
      for the image, and that this is because an image built from this tree
      carries a different JWT library than the 0.1.0 Maven Central artifacts.
- [ ] The rewritten comment in `.github/workflows/publish-image.yml` no longer
      claims the image will be re-pushed as 0.1.0, and records that the decision
      was revisited because `main` and `v0.1.0` diverged.
- [ ] `git diff` contains no change to any dependency version, no change to any
      production code path, and no new or deleted file. The only main-source
      change is the two `serverInfo` literals.

## Out of scope

- **Tagging, pushing and publishing.** No `git tag`, no `v0.1.1`, no workflow
  dispatch, no Maven Central release, no image push. Those are owner-driven and
  follow the merge. This task ends at a merged, green `main` at 0.1.1.
- **`docs/plan/PLAN.md` and `docs/plan/HISTORY*.md`.** `PLAN.md:184-233` still
  describes the 0.1.0 cut and the re-push option; it is scribe's to update at
  `/record`. History is a record of what happened and must never be rewritten,
  even where its `0.1.0` strings now look stale.
- **Dependency upgrades.** nimbus-jose-jwt 10.9.1 and the three Actions bumps
  are already merged. Do not bump anything else, including anything Dependabot
  has open.
- **Removing the hard-coded version from `DataPrismMcpServer`.** Sourcing it
  from the build at runtime is a real improvement and a real behaviour change.
  Move the two literals; leave the design alone.
- **The 0.1.0 CHANGELOG entry.** Do not edit, reword or retro-correct it.
