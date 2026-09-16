# 37 — Build and publish the distributable server image to GHCR

**Repo:** data-prism
**Depends on:** 33
**Owns:**
- docker/distribution/** (new)
- .github/workflows/publish-image.yml (new)
- .dockerignore (new, if the build needs one)

## Goal
The only server image in the repository, `docker/server/Dockerfile`, is a demo:
it bundles `data-prism-quickstart-extension` and mounts the quickstart's
fixture-only `application.yaml`. Publishing that as the product would ship
fixture adapters and a fixture issuer configuration to every consumer. Build a
separate, fixture-free image of `data-prism-server` alone, labelled for the MCP
registry, and publish it to `ghcr.io/aindriub/data-prism-server` on a release tag.

## Context
- docker/server/Dockerfile:1-26 — the demo, and the `-Dloader.path` mechanism to
  keep: an operator supplies their own reviewed adapter jar exactly this way
- data-prism-server/pom.xml:56-71 — `spring-boot-maven-plugin` with
  `<layout>ZIP</layout>`, i.e. `PropertiesLauncher`, which is what makes
  `loader.path` work
- DataPrismContractValidator.java:25-33 — `MISSING_SOURCE_ADAPTER` /
  `UNRESOLVED_SOURCE_ADAPTER`: with no adapter bean per configured source the
  server refuses to start. This is the behaviour the image must demonstrate, not
  paper over
- ServerPackagingIT.java — the existing proof that the packaged jar carries no
  development key material; the image must not reintroduce any
- docs/configuration.md — the `dataprism.*` vocabulary a mounted configuration
  file has to satisfy

## Acceptance
- [ ] `docker build -f docker/distribution/Dockerfile .` succeeds from a clean
      checkout and produces an image whose entrypoint runs
      `data-prism-server-0.1.0.jar`.
- [ ] The image carries
      `LABEL io.modelcontextprotocol.server.name="io.github.aindriub/data-prism"`,
      verifiable with
      `docker inspect --format '{{index .Config.Labels "io.modelcontextprotocol.server.name"}}' <image>`.
- [ ] The image contains no quickstart artifact: exporting its filesystem and
      listing it (`docker export <container> | tar -t | grep quickstart`) returns
      nothing, and no `data-prism-quickstart-*` jar, fixture yaml or issuer
      certificate is present.
- [ ] The image ships no `application.yaml` that would let it start with a
      fixture or placeholder security configuration. Running it with no mounted
      configuration exits non-zero with a named `dataprism` refusal code
      (`MISSING_JWT_ISSUER` or `MISSING_SOURCE_ADAPTER`), and the test records
      which code and the exit status.
- [ ] Running it with a complete configuration but no adapter jar on the loader
      path still exits non-zero with `MISSING_SOURCE_ADAPTER`. The commit body
      states this is the intended consumer experience, not a bug.
- [ ] An operator-supplied adapter jar mounted into the image's loader-path
      directory is loaded — the same mechanism
      `ServerPackagingIT.executableLoadsAReviewedAdapterExtensionFromLoaderPath`
      proves for the jar — and the directory's path is documented in the
      Dockerfile itself.
- [ ] The image runs as a non-root user and exposes the HTTP port the server
      listens on.
- [ ] `.github/workflows/publish-image.yml` triggers on `push: tags: ['v*']` plus
      `workflow_dispatch`, has `permissions: packages: write, contents: read`,
      authenticates to GHCR with `GITHUB_TOKEN`, and pushes
      `ghcr.io/aindriub/data-prism-server:0.1.0` and `:latest` for at least
      `linux/amd64`.
- [ ] The workflow builds the image from the repository at the tagged commit and
      runs the no-configuration refusal check above before pushing, so a fail-open
      image cannot reach the registry.

## Out of scope
- `docker/server/Dockerfile`, `compose.yaml` and the quickstart journey. The demo
  image stays exactly as it is; this is a second, separate image.
- Changing `data-prism-server`'s pom, packaging layout or tests — task 33 owns the
  two packaging ITs and task 36 owns every pom.
- Writing or shipping a reviewed `DataSourceAdapter`. The consumer supplies one;
  that is the contract, not a gap to fill.
- `server.json`, the `mcp-name` README marker and registry publication — task 38.
- Signing or attesting the image (cosign, SLSA provenance). A later decision.
- `docs/**` — scribe hand-off at `/record`.
