# 143 — Build and run every Docker image on Java 25 LTS, with a container smoke test

**Repo:** `.`
**Release:** 0.5.0 (part of 140)
**Depends on:** none
**Owns:**
- docker/distribution/Dockerfile *(`FROM` lines and their comments only)*
- docker/server/Dockerfile *(`FROM` lines and their comments only)*
- docker/certs-init/Dockerfile *(`FROM` line only)*
- docker/fixtures/Dockerfile *(`FROM` lines only)*
- docker/issuer/Dockerfile *(`FROM` lines only)*
- docker/smoke/** *(new)*

## Goal

The owner approved Java 25 LTS for the build and runtime images (2026-10-07),
and never 26 or another non-LTS release. Every image moves from the Java 21
tags to the Java 25 tags. The bytecode stays `--release 21`, so only the
JVM inside the images changes. A repeatable smoke script proves that the
server image starts, that two members form one Hazelcast cluster, and that
the JDK 25 `sun.misc.Unsafe` warning Hazelcast triggers is recorded, not
hidden.

## Context

- The current `FROM` lines are `maven:3.9-eclipse-temurin-21` (build stage) and
  `eclipse-temurin:21-jre` (runtime), at `docker/distribution/Dockerfile:11,22`,
  `docker/server/Dockerfile:9,35`, `docker/certs-init/Dockerfile:5`,
  `docker/fixtures/Dockerfile:4,22` and `docker/issuer/Dockerfile:10,28`.
- Tag check on Docker Hub, 2026-10-07: `eclipse-temurin:25-jre` exists for
  amd64 and arm64. So do `maven:3.9-eclipse-temurin-25`, `maven:3.9.11-eclipse-temurin-25`
  and `eclipse-temurin:25-jdk`. `eclipse-temurin:26-jre` and
  `maven:3.9-eclipse-temurin-26` also exist and must not be used.
  `publish-image.yml` builds linux/amd64 and linux/arm64 natively, so both
  architectures are required.
- Open Dependabot PR #116 proposes `25-jre` for the runtime and
  `3.9-eclipse-temurin-26` for the build stage. It does not cover
  `docker/distribution`. This task supersedes it; do not merge #116.
- Hazelcast 5.7.0 on JDK 25. A probe build on 2026-10-07 logged
  `WARNING: sun.misc.Unsafe::objectFieldOffset has been called by
  com.hazelcast.shaded.org.jctools.util.UnsafeAccess` (JEP 471/498: warn by
  default on JDK 24+, behaviour unchanged). Owner decision D-140-C: record
  the warning; do not suppress it with `--sun-misc-unsafe-memory-access=allow`.
- `docker/multi-instance/compose.yaml` and `compose.build.yaml` (task 135)
  already run two server members, `server-a` and `server-b`, with `tcp-ip`
  join on a private network. Drive the smoke test through them; do not copy
  them.
- `docker/server/Dockerfile`'s `ENTRYPOINT` sits between `--8<--` snippet
  markers that the docs embed. Leave it unchanged.

## Acceptance

- [ ] Every `FROM` under `docker/**/Dockerfile` names `maven:3.9-eclipse-temurin-25`
      or `eclipse-temurin:25-jre`.
      `grep -rnE 'FROM .*(temurin-?2[^5]|:2[^5]-)' docker` prints nothing.
- [ ] `docker build` succeeds for all five Dockerfiles from the repository
      root on the local architecture. The commit body lists the image
      digests or IDs.
- [ ] `docker/smoke/java-runtime-smoke.sh` exists, uses `set -euo pipefail`,
      needs nothing but Docker with Compose v2, and:
      - runs `java -version` in the distribution and server images and fails
        unless it reports `25.`;
      - runs the distribution image with no configuration and fails unless
        it exits non-zero with the existing no-config refusal (the same
        observable `publish-image.yml` already checks);
      - brings up `docker/multi-instance` from source and waits, with a
        timeout of 180 seconds or less, until both `server-a` and `server-b`
        log a Hazelcast `Members {size:2` line;
      - writes every `sun.misc.Unsafe` warning line from both members to
        `docker/smoke/target/unsafe-warnings.txt` (already git-ignored by the `target/` rule; `.gitignore` is not edited) and prints the
        count. A count of 0 is allowed, and the script says so;
      - tears the stack down on exit, including on failure (`trap`).
- [ ] The script has been run once. The commit body pastes its summary: Java
      version, cluster size seen by each member, and the Unsafe warning
      lines.
- [ ] `docker/smoke/README.md` (short) states what the script proves, how to
      run it, and that the Unsafe warning is expected on JDK 24+ and is
      recorded on purpose.
- [ ] No real credential, host or personal value appears in the script or
      its output. The stack uses the quickstart's synthetic issuer and
      fixtures only.

## Out of scope

- `.github/workflows/**`, including running this smoke test in CI. That is
  task 144.
- `.github/dependabot.yml`. That is task 145.
- JVM flags in any `ENTRYPOINT`, including the Unsafe flag (D-140-C).
- `maven.compiler.release`, which stays `21`.
- README and documentation wording. That is task 147.
