# 144 — Build on a JDK 21 and 25 matrix, release on 25, and gate released jars on class version 65

**Repo:** `.`
**Release:** 0.5.0 (part of 140)
**Depends on:** 143, 146
**Owns:**
- .github/workflows/build.yml
- .github/workflows/release.yml
- .github/workflows/publish-central.yml *(`setup-java` `java-version` values and one new class-version step only)*
- .github/workflows/publish-image.yml *(`setup-java` `java-version` values only)*
- .github/scripts/check-class-version.sh *(new)*

## Goal

The library still targets Java 21 consumers (`maven.compiler.release` 21), but
the images and the release toolchain move to Java 25 LTS (owner, 2026-10-07).
CI therefore builds and tests on both JDK 21 and JDK 25. The release,
Central and image workflows build on 25. A built jar whose classes are not
class-file major version 65 (Java 21) can never be released, because a JDK
25 toolchain that lost `--release 21` would otherwise ship bytecode that
Java 21 consumers cannot load.

## Context

- `build.yml` runs one job on `java-version: "21"`. Its comment explains why
  it builds on the target version. That reasoning now moves into the 21 leg
  of the matrix.
- `setup-java` `"21"` also appears at `release.yml:20`,
  `publish-central.yml:48,103` and `publish-image.yml:261,498`.
- `pom.xml:68`: `maven.compiler.release` is `21`. Class-file major 65 is Java
  21. Java 25 is 69.
- Task 146 makes the suite clean on JDK 25 and proves it in a Linux JDK 25
  container. This task depends on it, so the 25 leg is green when it lands.
- Task 143's `docker/smoke/java-runtime-smoke.sh` builds the images and
  forms a two-member cluster. It needs only Docker with Compose v2, which
  `ubuntu-latest` provides.
- The publish workflows' guards and approval gates (tasks 36, 37, 40) must not
  change. Only the JDK version and the new class-version check are edits.

## Acceptance

- [ ] `build.yml`'s build job has `strategy.matrix.java: ["21", "25"]` with
      `fail-fast: false`. `setup-java` uses `${{ matrix.java }}`, and the
      uploaded surefire artifact name includes the Java version, so the two
      legs do not collide.
- [ ] `build.yml` has a separate `container-smoke` job that runs
      `docker/smoke/java-runtime-smoke.sh` on `ubuntu-latest` and uploads
      `docker/smoke/target/` as an artifact.
- [ ] `release.yml`, both jobs of `publish-central.yml`, and both
      `setup-java` steps of `publish-image.yml` use `java-version: "25"`.
      `grep -n 'java-version' .github/workflows/*.yml` shows `"21"` only
      as a value in `build.yml`'s matrix.
- [ ] `.github/scripts/check-class-version.sh <expected-major> <jar>...`
      reads the major version of every `.class` entry in each jar (bytes 6-7
      of the class file). It skips `META-INF/versions/**` and the
      `module-info.class` of third-party nested jars. It exits non-zero and
      names the jar and class when any class's major differs. For a Spring
      Boot fat jar it checks the jar's own `BOOT-INF/classes/**` only.
- [ ] `release.yml` and `publish-central.yml`'s `stage` job run the script
      with `65` over every `data-prism-*/target/data-prism-*.jar` (excluding
      `-sources`/`-javadoc`) after the build and before any upload or
      release creation.
- [ ] The script is shown to fail: the commit body records a run against a
      jar built with `-Dmaven.compiler.release=25`, failing and naming major
      `69`, and a run against the normal build, passing.
- [ ] A pull request run shows both matrix legs and `container-smoke` green.
      The commit body or PR records the run URL.
- [ ] No step in `publish-central.yml` or `publish-image.yml` other than the
      `java-version` values and the new class-version step differs from
      `main`. `git diff main -- .github/workflows/publish-*.yml` shows only
      those lines.

## Out of scope

- `.github/dependabot.yml`. That is task 145.
- Dockerfiles and the smoke script itself. That is task 143.
- `pom.xml` and surefire configuration. That is task 146.
- Changing `maven.compiler.release` or adopting Java 25 language features.
  The owner has not approved that.
- `pages.yml` and `publish-mcp.yml`, which use no JDK.
