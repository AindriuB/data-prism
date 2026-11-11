# 307 — Soak provenance: never record a plausible-but-false identity

**Repo:** .
**Depends on:** 263 Part A (landed, PR #243). Should land before the 263 Part B soaks start, because Part B's evidence is only as good as the identity it records.
**Decisions:** none open. Filed 2026-11-11 from the PR #243 review (items 243-45 to 243-49, 243-52) and the code reviews of the same PR.
**Owns:**
- load-test/soak/build-images.sh
- load-test/soak/common.sh
- load-test/soak/member-image-id.sh
- load-test/soak/README.md (the provenance section only)
- load-test/src/main/java/io/github/aindriub/dataprism/loadtest/LoadTestMain.java and `LoadTestMainTest.java` (the `memberBuild` read only)

## Goal
The soak harness records which build each container ran. Several paths can still print a plausible identity that is false (a count of 0, a label from an earlier build, a container skipped because its lookup failed). A soak whose record says "clean, one commit, all members on it" when that is not so is worse than one with no record. Each path below either records the truth or says `unknown` and fails.

## Items
- 243-45 (Low, `common.sh:192`): `resolving_class_count` prints 0 when `dc` fails. Propagate the failure and print `unknown`, never 0.
- 243-46 (Low, `build-images.sh:51`): a reused image keeps the first build's commit and dirty labels. Record them from the stamp, or relabel on reuse.
- 243-47 (Low, `LoadTestMain.java:127`): `memberBuild` is read from the local overlay whatever target was driven. Read it only when the target is the overlay; otherwise record `unrecorded`.
- 243-48 (Low, `common.sh:111`): `provenance_stamp_check` skips a container whose lookup failed, as if absent. A failed lookup is `unknown` and refuses; only a container that is really absent is skipped.
- 243-52 (Low, `common.sh:56`): `running_match_check` skips stopped containers. A full restart stops all members, so `up` after a server-only rebuild brings stopped members up on the new build. Compare stopped containers too (`docker ps -a`), or compare the stamp's hash with the bring-up hash.
- 243-49 (Nit, `build-images.sh:39`): the content hash ignores file modes. Include the modes.
- Dirty flag (review note, `build-images.sh:24`): dirty is read from `git status` before the snapshot is copied, so an edit in between, or `--skip-worktree`, gives an image labelled `dirty=no` with content that is not HEAD's. Derive dirty from the snapshot compared with HEAD.

## Acceptance
- [ ] AC1 Each item above is fixed and each fix has a stub or fixture demonstration in the Outcome: with `docker` stubbed to fail, `resolving_class_count`, `provenance_stamp_check` and `member-image-id.sh` print `unknown` and exit non-zero, never 0 or absent.
- [ ] AC2 A reused image's recorded commit and dirty flag are those of the snapshot being used (shown by building, committing an unrelated change, rebuilding and reading the labels and the stamp).
- [ ] AC3 With a stopped member and a rebuilt server image, `dc up` refuses, naming the stopped container.
- [ ] AC4 Two snapshots that differ only in a file mode have different hashes.
- [ ] AC5 A file edited after `git status` but before the snapshot copy, and a `--skip-worktree` file, give `dirty=yes`.
- [ ] AC6 `LoadTestMainTest` shows `memberBuild` is `unrecorded` when the target is not the overlay.
- [ ] AC7 No file outside Owns changes. Nothing is recorded that holds an address, payload or key.

## Checks
- `scripts-parse` command — AC1 to AC5
  run: `for f in load-test/soak/*.sh; do bash -n "$f" || exit 1; done`
- `harness-build` command — AC6
  run: `./mvnw -B -q -pl data-prism-server,data-prism-connectors-rest -am -DskipTests package && ./mvnw -B -q -f load-test/pom.xml -Ddataprism.version="$(./mvnw -B -q -DforceStdout help:evaluate -Dexpression=project.version)" package`
- `provenance-stubs` read — AC1 to AC5
  how: reviewer reads the Outcome's stub runs and the diff; each failure path prints `unknown` or refuses.

## Out of scope
Part B runs; the other 243 Lows (they stay in PLAN.md); the `load-test/pom.xml` enforcer regex; `docker/multi-instance/**`.
