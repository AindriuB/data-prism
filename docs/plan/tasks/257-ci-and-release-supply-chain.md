# 257 — Harden CI and the release supply chain: resolvable action refs, an advisory scan, Dependabot coverage, one SBOM build, per-arch signing

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 221 has merged
**Depends on:** 221 (sealed-cluster plan; 221 edits the `hazelcast.version` property of the same root `pom.xml`, so the two edits land in order)
**Blocked on:** OD-08-11 (per-arch signing), OD-08-12 (one SBOM build), OD-08-17 (whether the advisory scan fails the run)
**Wave:** 3 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- .github/workflows/*.yml
- .github/workflows/supply-chain.yml *(new)*
- .github/scripts/check-action-refs.sh *(new)*
- .github/scripts/** *(new files only)*
- .github/dependabot.yml
- .github/osv-scanner.toml *(new, if the scan uses an ignore file)*
- pom.xml *(the `jackson3.version` property, its comment and the `jackson-bom` import only)*
- docs/verifying-releases.md

## Goal
Close the 0.7.0 release follow-ups that are about the build and the release, not the product. `sigstore/cosign-installer@v4` did not exist and was caught only by the real tag push, so every `uses:` reference must be pinned to a full version or SHA and checked to resolve on every change. Dependabot cannot see BOM-managed versions, so the resolved dependency tree needs its own advisory scan. Dependabot misses the pip files and the out-of-reactor load test. The release and Central SBOMs differ because they come from two builds. The per-arch image digests carry no signature after `imagetools create` flattens them. And the `jackson-bom` override should go once Spring Boot manages Jackson 3.1.7 or later.

## Context
- docs/plan/PLAN.md, "0.8 backlog (carried from 0.7)" and "Follow-ups from 234".
- .github/workflows/publish-image.yml:489-541 (keyless signing of pushed digests) and :676-790 (`imagetools create` and the digests read back).
- .github/workflows/release.yml:54-90 and publish-central.yml:216: the two SBOM sets.
- pom.xml:76-80 and :112-118: the override and the condition for removing it.
- docs/verifying-releases.md: what a consumer can verify today.
- docker/*/Dockerfile base images and `docs-site/requirements.txt`, `docs-site/logo/requirements.txt`, `docs-site/social-card/requirements.txt`, `examples/correlation-header/requirements.txt`, `load-test/pom.xml`: what Dependabot should watch.

## Acceptance
- [ ] Every `uses:` in `.github/workflows/*.yml` names a full version tag (`vX.Y.Z`) or a 40-character SHA; no floating major or minor tag remains (`rg -n 'uses: [^@]+@v[0-9]+(\.[0-9]+)?$' .github/workflows` prints nothing).
- [ ] `.github/scripts/check-action-refs.sh` resolves every `uses:` reference (for example with `git ls-remote`) and exits non-zero naming each one that does not resolve. Its `--self-test` mode runs it against a fixture containing one bad reference and expects failure. It runs in CI on every pull request that touches `.github/`.
- [ ] `supply-chain.yml` runs weekly and on `workflow_dispatch`: it resolves the full Maven tree (root reactor and `load-test/`) and scans it for known advisories (for example osv-scanner over the CycloneDX output), and fails or reports as OD-08-17 decides. Any ignore entry requires a written reason.
- [ ] `dependabot.yml` gains a `pip` entry for each `requirements.txt` above and a `maven` entry for `/load-test`, each grouped weekly like the existing entries.
- [ ] Jackson: the Outcome states the Spring Boot version checked and the Jackson 3 version its BOM manages. If it is 3.1.7 or later, the `jackson3.version` property and the `jackson-bom` import are removed and `./mvnw dependency:tree` shows `tools.jackson.core:jackson-databind` at 3.1.7 or later; otherwise `pom.xml` is unchanged.
- [ ] SBOMs as OD-08-12 decides. Under its option (a): the release builds the artifacts once, and the CycloneDX files attached to the GitHub release are byte-identical (same SHA-256) to those in the Central bundle; a workflow step asserts it.
- [ ] Per-arch digests as OD-08-11 decides. Under its option (a): each per-arch digest reachable from a published tag is signed and attested, and `docs/verifying-releases.md` shows the command that verifies one; under (b), the page states plainly that only the manifest list is verifiable and why.
- [ ] `actionlint` passes on every workflow file.
- [ ] `./mvnw -B verify` passes.

## Out of scope
- Version bumps, tags and publication (the release cut).
- The Hazelcast version property in `pom.xml` (sealed-cluster task 221).
- The mutation-testing workflow's gate (0.9).
