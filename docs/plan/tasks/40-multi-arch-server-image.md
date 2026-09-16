# 40 — Publish the server image for linux/amd64 and linux/arm64

**Repo:** `.`
**Depends on:** none
**Owns:**
- .github/workflows/publish-image.yml
- docker/distribution/Dockerfile
- README.md
- server.json

## Goal

`ghcr.io/aindriub/data-prism-server` is pushed today as a single `linux/amd64`
image. The MCP registry entry (task 38, merged but deliberately unpublished)
points at exactly that coordinate, and a public listing sends strangers to it —
a large share of them on Apple Silicon. Publish the same 0.1.0 image as a
multi-architecture manifest list covering `linux/amd64` and `linux/arm64`, each
architecture built and verified on native hardware before anything is pushed.

This is a performance and polish problem, not a correctness one: the current
amd64-only image was verified to run under emulation on an Apple Silicon Mac.
It starts, and it refuses correctly. It is slow and it prints a platform
warning. Do not write the change up, in code comments or in the PR, as though
arm64 users are broken today.

## Context

Read `.github/workflows/publish-image.yml` before planning the edit. Its shape,
not a missing flag, is the obstacle — this is already diagnosed, do not
re-derive it:

- `.github/workflows/publish-image.yml:61` — `docker build`, one platform
- `.github/workflows/publish-image.yml:76` — the no-config refusal verification
  that gates everything downstream
- `.github/workflows/publish-image.yml:107,109` — `docker save` to a tarball,
  then `upload-artifact`
- `.github/workflows/publish-image.yml:162,167,183` — `download-artifact`,
  `docker load`, `docker push`

A `docker save` / `docker load` tarball cannot carry a manifest list. The
build-verify-hand-off-push pipeline therefore has to change shape.

The design to implement — sanity-checked against the file above and it holds:

- A job matrix builds each architecture on a **native** runner:
  `ubuntu-latest` for amd64, `ubuntu-24.04-arm` for arm64. The repository is
  public, so GitHub's arm64 runners cost nothing. Native beats QEMU on build
  time and, more importantly, means the arm64 image is verified on real arm64
  hardware rather than under emulation.
- Each matrix job builds its own single-platform image, loads it locally, and
  runs the existing no-config refusal verification against **that** image
  before anything is pushed. The current workflow's guarantee — verified
  before pushed — must survive intact. Per-architecture native verification is
  strictly stronger than today, where arm64 is neither built nor verified.
- Each matrix job pushes by digest. A final job assembles and pushes the
  manifest list for the version tag and `:latest`.
- Because the matrix job now writes to the registry itself, the guards that
  today live only in the `publish` job have to reach it. A plain tag push, or
  a dispatch left on a branch, must not produce a registry write of any kind,
  digest-only pushes included.

Other context:

- `.github/workflows/publish-mcp.yml` — its "Verify the referenced image is
  already published" step runs `docker manifest inspect` on the tag. Check
  whether that still holds against a manifest list and state the finding.
- `docs/conventions.md` — the privacy and commit rules the diff must satisfy.

## Decided, do not re-open

The image is **re-pushed as `0.1.0`**, not cut as a new version. Nothing
external references it: the registry entry is unpublished (confirmed by a live
registry search returning zero results, task 38) and the only puller so far is
this project's own demo. Re-pushing a tag nobody has consumed costs nothing and
keeps `server.json`, the poms and the tag in agreement. Record that reasoning
in the workflow or PR. **This option expires the moment the registry entry goes
live** — after that, a changed image needs a new version.

## Acceptance

- [ ] After a dispatch against a `v0.1.0` tag,
      `docker manifest inspect ghcr.io/aindriub/data-prism-server:0.1.0`
      returns a manifest list whose entries include both
      `{"os":"linux","architecture":"amd64"}` and
      `{"os":"linux","architecture":"arm64"}`. Same for `:latest`.
- [ ] The amd64 image is built and the no-config refusal verified on
      `ubuntu-latest`; the arm64 image is built and the refusal verified on
      `ubuntu-24.04-arm`. Neither verification runs under emulation: the
      workflow contains no `docker/setup-qemu-action`, no `binfmt` setup and
      no `--platform` cross-build of an image it then verifies.
- [ ] In each matrix job, the refusal-verification step appears before any
      registry login or push step, and the job fails if the image starts
      successfully or prints no named refusal code.
- [ ] The refusal grep is still
      `grep -oE 'DataPrismConfigurationException: MISSING_[A-Z_]+'`, unchanged,
      so the gate stays independent of which `MISSING_*` check fires first.
- [ ] If the `ubuntu-24.04-arm` runner is unavailable, the run fails. No path
      exists by which an amd64-only manifest is published because the arm64
      half did not run — verifiable by reading the job graph: the manifest job
      `needs:` every matrix leg and does not use `if: always()`,
      `continue-on-error` or `fail-fast: false` to survive a missing leg.
- [ ] Every job that logs in to GHCR or pushes anything — matrix legs included
      — is reachable only when `github.event_name == 'workflow_dispatch' &&
      startsWith(github.ref, 'refs/tags/v')`, and runs a guard step comparing
      the dispatch version against the reactor version
      (`mvn -B -q -DforceStdout help:evaluate -Dexpression=project.version`)
      that precedes its login step.
- [ ] `rg '\$\{\{' .github/workflows/publish-image.yml` shows matches only on
      `env:`, `if:`, `with:` and `strategy:` lines — no `${{ }}` inside any
      `run:` block. (This cost task 37 three review rounds.)
- [ ] A `workflow_dispatch` run left on a branch, and a plain `v*` tag push,
      each complete without any registry write. Demonstrate with an actual run
      of each, citing the run id.
- [ ] No version is bumped: the reactor version, `server.json`'s `.version` and
      `.packages[0].version` all still read `0.1.0`.
- [ ] `server.json`, `README.md` and `docker/distribution/Dockerfile` have each
      been checked for a statement or implication that the image is amd64-only.
      Anything found is corrected; where no change is needed — including the
      MCP registry entry — the PR says so explicitly, naming the file and why,
      rather than leaving it unexamined.
- [ ] `README.md`'s "If you found this on the MCP registry" section names the
      platforms the published image supports.

## Out of scope

- Cutting or bumping to any version other than `0.1.0`. Settled above.
- Changing what the image contains, its entrypoint, its user, its loader path
  or its config convention. `docker/distribution/Dockerfile` is owned here only
  so a platform-related comment can be corrected and, if the build genuinely
  needs it, a build argument added — not to redesign the image.
- `docker/server/Dockerfile`, `compose.yaml` and the quickstart modules. The
  local demo stack builds on the developer's own machine and is unaffected.
- `.github/workflows/publish-central.yml`, `publish-mcp.yml`, `release.yml`,
  `build.yml`. If `publish-mcp.yml`'s pullability guard turns out to need a
  change, report it as a finding and stop — it belongs to a successor task.
- `CHANGELOG.md`, `docs/plan/PLAN.md`, `docs/plan/HISTORY*.md`. Only the scribe
  writes those, at `/record`.
- Actually dispatching the publish for real, making the GHCR package public, or
  any other step of the owner-driven publish sequence in
  `docs/plan/PLAN.md`. Prove the workflow; do not run the release.
