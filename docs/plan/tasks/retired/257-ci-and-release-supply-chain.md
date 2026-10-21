# 257 — Harden CI and the release supply chain: resolvable action refs, an advisory scan, Dependabot coverage, one SBOM build, per-arch signing

**Repo:** `.`
**Release:** 0.8.0, and backported to 0.7.1 by cherry-pick (task 266; owner decision 2026-10-21)
**Base branch:** `origin/main` once 221 has merged
**Depends on:** 221 (sealed-cluster plan; 221 edits the `hazelcast.version` property of the same root `pom.xml`, so the two edits land in order)
**Decisions:** OD-08-11 (a, sign and attest each per-arch digest), OD-08-12 (a, build once, publish the same SBOM files to both) and OD-08-17 (a, scheduled scan fails on HIGH or CRITICAL) are decided 2026-10-20.
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
- **0.7.1 backport.** Task 266 cherry-picks this task's commits onto `release/0.7.1`, cut from `v0.7.0`. `.github/` and `pom.xml` were unchanged between `v0.7.0` and `origin/main` at filing. PR #144 leaves `pom.xml` unchanged too. For the pick to work:
  - No workflow step may build, scan or sign a module, image or path that exists only on 0.8. The scans and signing must work over the 0.7.0 reactor and `load-test/` as they are.
  - `docs/verifying-releases.md` on `main` carries a correction made after the tag (commit `e34a738c`, "Record 0.7.0 release; correct release-verification docs"), and the tag does not. 266 brings that correction onto the release branch before this task's change to the same file. Keep this task's edit to that page in its own commit so the pick stays clean.
  - The Jackson hunk in `pom.xml` sits within three lines of `hazelcast.version` (:74). If 221 or a dependency update has moved that line by the time this merges, the backport keeps `v0.7.0`'s `hazelcast.version` and applies only the Jackson change.
  - If the Jackson override is removed because of a Spring Boot bump that is on `main` but not on `v0.7.0`, the backport keeps the override, and 266 confirms that `dependency:tree` on the branch shows `tools.jackson.core:jackson-databind` at 3.1.7 or later.
  - `dependabot.yml` is read only from the default branch. On the release branch it is inert, but it is picked anyway so the two branches stay aligned.
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
- [ ] No step added to a workflow names a module, path or image that is absent at `v0.7.0` (`git ls-tree -r --name-only v0.7.0` holds each one). The change to `docs/verifying-releases.md` is its own commit. After 266 cherry-picks the commits onto `release/0.7.1`, `.github/scripts/check-action-refs.sh` (with and without `--self-test`), `actionlint` and `./mvnw -B verify` pass on that branch.

## Out of scope
- Version bumps, tags and publication (the release cut).
- The Hazelcast version property in `pom.xml` (sealed-cluster task 221).
- The mutation-testing workflow's gate (0.9).

## Outcome

Landed 2026-10-21 on branch `task/257-ci-supply-chain` at `f456a12b`, merged with a merge commit in PR record/0.7.1-wave1-244 so that 266 can cherry-pick the commits. Commits, in order: `34bcce2b`, `4c8a070b`, `e5864921`, `9118afc7`, `61ec2f94` (docs only), `00810a75`, `752d867a` (docs only), `f456a12b`. On `release/0.7.1`, commit `e34a738c` (the post-tag correction to `docs/verifying-releases.md`) must be picked before `61ec2f94`.

What it does. Every `uses:` is pinned to a full version or SHA, and `check-action-refs.sh` (with a `--self-test` mode) resolves each reference in the new `action-refs.yml` on every pull request touching `.github/`. `supply-chain.yml` runs weekly and on dispatch: it builds the CycloneDX SBOMs of the resolved Maven trees and scans them with osv-scanner; HIGH, CRITICAL and unrated advisories fail, moderate ones warn, and every ignore in `.github/osv-scanner.toml` must carry a reason and an `ignoreUntil`, which a check enforces. Dependabot gains the pip files and `load-test`. The release builds once and attaches to the GitHub release the same SBOM files that go in the Central bundle, with a step asserting equal SHA-256. `publish-image` signs and attests each per-architecture digest reachable from a published manifest list, and its last step fails if any list or platform digest is unsigned. `publish-central` is guarded by the GitHub release existing. `docs/verifying-releases.md` shows the per-architecture and release-SBOM checks.

Verification. actionlint and `check-action-refs.sh` (both modes) pass. Tester PASS at `f456a12b`: `./mvnw -B verify` green; the scan's SBOM build produces 21 `bom.json` files including `data-prism-server` (86 components, containing `spring-boot-actuator-autoconfigure` and `micrometer-core`) and `load-test`; the ignore-list check passes. The reviewer ran osv-scanner v2.7.0 (checksum-verified binary) against 0.7.0's real SBOMs and a vulnerable test SBOM: empty SBOMs are skipped, HIGH, CRITICAL, label-only HIGH and unrated advisories fail, moderate warns. The `publish-image` jq filters were checked against the live GHCR 0.7.0 lists and pick exactly the amd64 and arm64 entries.

Jackson. Spring Boot 4.1.1 GA manages Jackson 3.1.5, below 3.1.7, so the 3.1.8 `jackson-bom` override stays and `pom.xml` is unchanged.

**Code review found, and the implementer fixed:** attestation-manifest entries in the image list broke the unsigned-digest check; empty SBOMs made osv-scanner exit 128; a label-only HIGH advisory slipped through; no digest guard; `.yaml` and `docker://` references were not checked; `skipNotDeployed` hid `data-prism-server` from the scan; Central deploy was not guarded by the GitHub release existing; ignores needed an enforced reason and expiry.

**Coordinator decision:** unrated advisories fail the scan (fail closed).

**Not runnable here** (Docker down, no workflow triggers): the real `publish-image`, `attach-sboms`, the cosign identity check and the docker osv-scanner image. Their first real run is the 0.7.1 release. The `--sbom=` form of osv-scanner is used; the `-L` form is untested.
