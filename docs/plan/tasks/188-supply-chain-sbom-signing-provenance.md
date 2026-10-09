# 188 — Publish SBOMs, signed images and build provenance from the release workflows

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Decided (owner, 2026-10-09):** D-070-8 (a): CycloneDX via `cyclonedx-maven-plugin`
attached to the Central bundle; buildx SBOM and provenance attestations on
images; keyless cosign on every pushed digest, including the four quickstart
images; `actions/attest-build-provenance` on release assets.
**Owns:**
- .github/workflows/publish-image.yml
- .github/workflows/publish-central.yml
- .github/workflows/release.yml
- pom.xml (the `release` profile only: an SBOM plugin and its execution)
- docs/verifying-releases.md (new)
- mkdocs.yml (one `nav` entry for `verifying-releases.md` only)
- SECURITY.md (a short pointer to `verifying-releases.md` only)
- docs/plan/tasks/188-supply-chain-sbom-signing-provenance.md (Outcome section only)

## Goal
1.0 promises a verifiable supply chain. Today the published artifacts carry
GPG signatures on Central and nothing else: no SBOM, no image signature, no
build provenance. This task makes every `v*` release publish an SBOM for each
Maven artifact and each image, a signature on each pushed image digest, and
build provenance for the jars and the images, plus a published page that tells
a consumer how to verify each one.

## Context
- `.github/workflows/publish-image.yml:1-55` — the header comment explains the
  gating: a tag push builds and verifies; only a `workflow_dispatch` against a
  `v*` tag ref writes to GHCR, and every registry-touching step repeats the
  guard. `:72` `build-and-verify` matrix (per-arch push by digest), `:479`
  `publish` (manifest-list assembly). Any new signing or attestation step is a
  registry write and must carry the same `if:` guard as the existing push steps.
- `.github/workflows/publish-central.yml:33` `stage`, `:124` `publish` — the
  Central path; the `central` GitHub Environment gates the token.
- `.github/workflows/release.yml:11-60` — builds, checks class version, then
  `gh release create` (`:52`).
- `.github/workflows/publish-mcp.yml:16-17,155` — the existing `id-token: write`
  pattern to follow for OIDC-based signing.
- `pom.xml:308-360` — the `release` profile (sources, javadoc, `sign-artifacts`).
- `docs/plan/PLAN.md` "0.6.0 is complete and published" — the bundle check
  recipe (`-DcentralBaseUrl=http://127.0.0.1:9`, then `unzip -l` the bundle).
- D-070-8 (a), above, fixes the tools. Keyless cosign uses GitHub OIDC and
  writes public Rekor transparency-log entries naming the repository and workflow.
- Never read `~/.m2/settings.xml`, `~/.gnupg/**` or any credential or token
  file, even to debug a failed build. Build locally with `-Dgpg.skip=true`.

## Acceptance
- [ ] `./mvnw -B -Prelease -Dgpg.skip=true -DskipTests package` produces an SBOM
      file (CycloneDX JSON) in `target/` of every
      module that is deployed to Central; the Outcome lists the modules and the
      files, and names any module deliberately excluded and why.
- [ ] The SBOM is attached to the deployed artifacts (`cyclonedx-maven-plugin` attaches it
      with its own classifier, so the Central bundle carries it); the Outcome shows the `unzip -l` of a
      bundle built with `-DcentralBaseUrl=http://127.0.0.1:9` listing it, or, if
      that run cannot be done locally without credentials, says so and adds the
      check to the release checklist note in the Outcome.
- [ ] `publish-image.yml`: for the distribution image and the four quickstart
      images, every pushed digest (each per-arch digest and the manifest list)
      gets a signature and a provenance attestation, and the image carries an
      SBOM attestation. Every such step has the same tag-ref/dispatch `if:`
      guard and version-guard precondition as the existing push steps; a plain
      `v*` tag push still performs no registry write of any kind.
- [ ] `id-token: write` and `attestations: write` (if used) are granted only on
      the jobs that sign or attest, not at workflow level.
- [ ] `release.yml` attaches the SBOMs to the GitHub Release and produces a
      provenance attestation for every uploaded asset.
- [ ] `actionlint` (latest release) exits 0 on all three workflow files; the
      Outcome shows the command and output.
- [ ] `docs/verifying-releases.md` gives copy-pasteable commands to verify a
      Central artifact (GPG signature, SBOM), an image signature (for example
      `cosign verify --certificate-identity-regexp ... --certificate-oidc-issuer
      https://token.actions.githubusercontent.com ghcr.io/aindriub/data-prism-server@<digest>`)
      and a provenance attestation (for example `gh attestation verify`),
      each with the expected identity spelled out. `mkdocs build --strict` exits 0.
- [ ] The Outcome carries proposed CHANGELOG `[Unreleased]` lines (`### Build`
      and `### Security`) for task 201 to merge; `CHANGELOG.md` is unchanged.

## Out of scope
- Any change to how or when images or Central artifacts are pushed (gating,
  version guards, Maven 3.9 pin) other than adding the new steps.
- Pinning actions by SHA, Dependabot changes, `build.yml`, `pages.yml`,
  `publish-mcp.yml`.
- The mutation-testing profile in `pom.xml` (task 195, which depends on this one).
- Cutting, tagging or publishing a release. The first end-to-end proof is the
  0.7.0 release itself; note it as a release checklist item, do not run it.
