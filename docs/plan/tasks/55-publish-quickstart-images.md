# 55 — Publish the three quickstart images and make Compose pull them

**Repo:** .
**Depends on:** none
**Owns:**
- .github/workflows/publish-image.yml
- compose.yaml
- compose.build.yaml (new)
- docker/issuer/Dockerfile
- docker/fixtures/Dockerfile
- docker/server/Dockerfile
- docker/server/application.yaml

## Goal
A cold `docker compose up --build` costs 5m44s, of which 329.8s is the server
image's own Maven build, because every quickstart image is built from source on the
reader's machine. Publish the three quickstart images (server-with-extension,
fixtures, issuer) on tag alongside the existing distribution image, and make
`compose.yaml` pull them by default with the from-source path kept available.

## Context
- .github/workflows/publish-image.yml:61-270 — the existing per-architecture matrix,
  its version guard, its no-configuration refusal verification, and the
  digest-push/manifest-assembly split. Extend this shape; do not replace it.
- compose.yaml:22-118 — the four services, each with a `build:` block today.
- docker/distribution/Dockerfile — the only image the workflow builds today.
- docs/plan/HISTORY.md, grep `Task 40` — why the pipeline is a native matrix and what
  `docker save`/`load` cannot carry.

## Acceptance
- [ ] On a `v*` tag, the workflow publishes multi-architecture manifest lists for
      `data-prism-quickstart-server`, `data-prism-quickstart-fixtures` and
      `data-prism-quickstart-issuer` under the same GHCR namespace as the existing
      distribution image, each at the reactor version.
- [ ] Every registry-touching step for the new images is gated by the same conditions
      as the existing ones: nothing logs in, pushes or assembles a manifest off a `v*`
      tag ref. Verifiable by reading each new step's `if:`.
- [ ] The existing distribution image's build, its no-configuration refusal
      verification, and its manifest assembly are unchanged in behaviour.
- [ ] `docker compose up` with no `--build` starts every service from published
      images: no service in the default `compose.yaml` path has a `build:` block that
      Compose would execute.
- [ ] `docker compose -f compose.yaml -f compose.build.yaml up --build` builds every
      service from source and reaches the same working state, and that exact command
      is the one named in the file's own header comment.
- [ ] The issuer image's Dockerfile and its Compose service both carry a comment
      stating it is a fixture-only evaluation issuer, and nothing in this task sets
      `dataprism.transport.fixture-development=true` anywhere on the server side —
      grep the diff for that property and find no new occurrence.
- [ ] The tag-push path of the new steps is either exercised by a real Actions run, or
      the task's close-out states explicitly which steps are argued from their
      conditions and unexercised, as task 40's did.

## Out of scope
- `docs/quickstart.md`, `docs/configuration.md`, `README.md` — task 59 owns all three,
  including the prose describing the evaluation issuer.
- The demo command script — task 56 owns it.
- Version cutting, `CHANGELOG.md`, `server.json`, the MCP registry.
- Any change to what the server refuses at startup.
