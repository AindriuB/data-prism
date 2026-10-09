---
title: Verifying a release
description: How to verify the GPG signatures, SBOMs, image signatures and build provenance published with every Data Prism release.
---

# Verifying a release

Every `v*` release of Data Prism publishes four kinds of evidence, so you can
check that what you run is what the project built:

| Artifact | Where | Evidence |
|---|---|---|
| Maven jars | Maven Central, group `io.github.aindriub` | GPG signature (`.asc`), CycloneDX SBOM, build provenance |
| Container images | `ghcr.io/aindriub/data-prism-server` and the four `data-prism-quickstart-*` images | keyless cosign signature, build provenance, SBOM attestation |
| SBOM files | the GitHub Release page and Maven Central | build provenance |

All commands below use placeholders in angle brackets. Replace `<version>`
with the release version without the leading `v` (for example `0.7.0`).

## Maven Central artifacts

### GPG signature

Every file Central serves for a module has a detached `.asc` signature made
with the project release key (key ID `FC5E68A44A325696`). Check a jar:

```bash
base=https://repo1.maven.org/maven2/io/github/aindriub/data-prism-core/<version>
curl -fsSLO "$base/data-prism-core-<version>.jar"
curl -fsSLO "$base/data-prism-core-<version>.jar.asc"
gpg --keyserver keyserver.ubuntu.com --recv-keys FC5E68A44A325696
gpg --verify data-prism-core-<version>.jar.asc data-prism-core-<version>.jar
```

Expected: `Good signature` from a key whose fingerprint ends in
`FC5E68A44A325696`. A `BAD signature` or a different key means do not use the
file.

### SBOM

Each deployed module carries a CycloneDX JSON SBOM with the classifier
`cyclonedx`, itself GPG-signed like any other file in the bundle:

```bash
curl -fsSLO "$base/data-prism-core-<version>-cyclonedx.json"
curl -fsSLO "$base/data-prism-core-<version>-cyclonedx.json.asc"
gpg --verify data-prism-core-<version>-cyclonedx.json.asc data-prism-core-<version>-cyclonedx.json
jq '.components | length' data-prism-core-<version>-cyclonedx.json
```

### Build provenance

The jars and SBOMs Central received are attested by the `publish-central.yml`
workflow. Download the jar and verify it against the repository:

```bash
gh attestation verify data-prism-core-<version>.jar \
  --repo AindriuB/data-prism \
  --signer-workflow AindriuB/data-prism/.github/workflows/publish-central.yml
```

Expected identity: the attestation was produced by the
`publish-central.yml` workflow of the `AindriuB/data-prism` repository, run
from the `v<version>` tag.

## Container images

The images are `ghcr.io/aindriub/data-prism-server` and
`ghcr.io/aindriub/data-prism-quickstart-{server,fixtures,issuer,certs-init}`.
Resolve a tag to its digest first, and verify the digest, not the tag:

```bash
image=ghcr.io/aindriub/data-prism-server
digest=$(docker buildx imagetools inspect "$image:<version>" --format '{{json .Manifest}}' | jq -r .digest)
```

### Signature

Images are signed keylessly with cosign, using the GitHub Actions identity of
the `publish-image.yml` workflow. Each per-architecture digest and each
manifest list is signed.

```bash
cosign verify \
  --certificate-identity-regexp '^https://github\.com/AindriuB/data-prism/\.github/workflows/publish-image\.yml@refs/tags/v.+$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$image@$digest"
```

Expected identity: the workflow
`AindriuB/data-prism/.github/workflows/publish-image.yml` at a `v*` tag, issued
by `https://token.actions.githubusercontent.com`. The signature is also
recorded in the public Rekor transparency log, which names the repository and
workflow. Tighten the regexp to `@refs/tags/v<version>$` to pin one release.

### Build provenance

```bash
gh attestation verify "oci://$image@$digest" \
  --repo AindriuB/data-prism \
  --signer-workflow AindriuB/data-prism/.github/workflows/publish-image.yml
```

Expected: the attestation names the `publish-image.yml` workflow of
`AindriuB/data-prism` at the `v<version>` tag. The images additionally carry
the BuildKit provenance attestation (SLSA, `mode=max`) described below.

### SBOM

The build attaches an SBOM attestation to every image. For a multi-platform
image, `.SBOM` is keyed by platform, so index it by platform; run this against
the manifest list digest to show both:

```bash
for platform in linux/amd64 linux/arm64; do
  echo "$platform: $(docker buildx imagetools inspect "$image@$digest" \
    --format "{{json (index .SBOM \"$platform\")}}" | jq '.SPDX.packages | length') packages"
done
```

Expected: a non-zero package count for each platform. Running the same
inspect on a per-architecture digest works too, but you still index by that
digest's platform. For the provenance use `.Provenance` the same way. Both are
attestation manifests stored in the registry next to the image, and the
manifest list's signature covers them.

## GitHub Release assets

The SBOM files attached to the GitHub Release have build provenance from the
`release.yml` workflow:

```bash
gh release download v<version> --repo AindriuB/data-prism --pattern '*-cyclonedx.json'
gh attestation verify data-prism-core-<version>-cyclonedx.json \
  --repo AindriuB/data-prism \
  --signer-workflow AindriuB/data-prism/.github/workflows/release.yml
```

Expected identity: the `release.yml` workflow of `AindriuB/data-prism`, run
from the `v<version>` tag.

## What this does and does not prove

A valid signature and attestation show the artifact was built by the named
workflow in the named repository from the tagged commit. They do not show the
code is free of defects, and an SBOM lists components, not their
vulnerabilities. Report a security problem as described in `SECURITY.md`.
