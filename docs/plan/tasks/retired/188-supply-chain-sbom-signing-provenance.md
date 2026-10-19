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

## Outcome

**SBOMs.** `cyclonedx-maven-plugin` 2.9.3 (`cyclonedx-plugin.version`) in the
`release` profile only, goal `makeBom` bound to `package` (so before the
`verify`-phase GPG signing), JSON output. `./mvnw -B -Prelease -Dgpg.skip=true
-DskipTests package` writes `target/bom.json` in 14 modules: the parent
`data-prism`, annotations, processor, core, pseudonymisation, validation,
security, orchestration, mcp, connectors-rest, hazelcast, reidentification,
spring-boot-autoconfigure, spring-boot-starter. Deliberately excluded: server,
integration-tests, architecture and the three quickstart modules, which are not
deployed to Central (the plugin's `skipNotDeployed` default skips them via
`maven.deploy.skip`); the server's SBOM is the image SBOM instead.

**Bundle check** (dummy `central` server in a throwaway settings file outside the
repo, `-Dgpg.skip=true -DcentralBaseUrl=http://127.0.0.1:9 deploy`):
`unzip -l target/central-publishing/central-bundle.zip` lists 14
`<artifactId>-0.6.0-cyclonedx.json` files, each with .md5/.sha1/.sha256/.sha512
(335 files in the bundle). With GPG enabled each also gets `.asc`.

**Images** (`publish-image.yml`): all five pushes add `--sbom=true
--provenance=mode=max`. Each matrix leg then, behind the same dispatch + `v*`
tag-ref `if:` and after the version-guard step, signs each per-arch digest
with keyless cosign and runs `actions/attest-build-provenance@v4` on it (5
images). `publish` signs and attests the five manifest-list digests (read back
via `imagetools inspect`), behind its job-level `if:` and version guard.
`id-token: write` and `attestations: write` are job-level on those two jobs
only. A plain tag push reaches none of it.

**Release assets** (`release.yml`): build now `-Prelease -Dgpg.skip=true
verify`; SBOMs are collected as `sbom/<module>-<version>-cyclonedx.json`,
attested (`attest-build-provenance@v4`) and attached by `gh release create`.
Job-level `id-token`/`attestations`. **Jars** (`publish-central.yml`, `publish`
job): after `deploy`, the jars and SBOMs are extracted from the uploaded
`central-bundle.zip` and attested, so the subjects are exactly the published files.

**actionlint** (`actionlint .github/workflows/publish-image.yml
.github/workflows/publish-central.yml .github/workflows/release.yml`, Homebrew
build): no output, exit 0. `mkdocs build --strict` (pinned requirements, Python
3.12 venv): exit 0. `docs/verifying-releases.md` added; nav entry under
Reference; pointer in `SECURITY.md`.

**Release checklist for 0.7.0 (first end-to-end proof; none of this was run):**
1. After the dispatch of `publish-image.yml`, confirm `cosign verify` and
   `gh attestation verify` from `docs/verifying-releases.md` pass on the
   manifest list and one per-arch digest of every image.
2. Confirm `docker buildx imagetools create` of the two per-arch indexes (now
   carrying attestation manifests) yields a manifest list that still lists
   amd64 and arm64 (the existing verify step checks this) and that
   `imagetools inspect --format '{{json .SBOM}}'` returns an SBOM.
3. Confirm the Central bundle lists `*-cyclonedx.json` with `.asc` files, and
   that `publish-central.yml`'s attest step finds `central-bundle.zip` after
   a real deploy.
4. Confirm `gh attestation verify oci://ghcr.io/aindriub/<image>@<digest>`
   passes against both the manifest list and a per-arch digest (per-arch
   digests are now indexes).
5. Confirm the GitHub Release shows the SBOM assets and
   `gh attestation verify` passes on them.

**Proposed CHANGELOG `[Unreleased]` lines (task 201):**

```
### Build
- Release builds attach a CycloneDX SBOM to every module published to Maven Central and to the GitHub Release.
- Images are pushed with BuildKit SBOM and provenance attestations.

### Security
- Every pushed image digest (including the manifest lists and the four quickstart images) is signed keylessly with cosign, and images, jars and release assets carry GitHub build-provenance attestations. See `docs/verifying-releases.md`.
```
