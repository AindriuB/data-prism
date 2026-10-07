# 145 — Make Dependabot allow Java 25 images and really block 26 and later

**Repo:** `.`
**Release:** 0.5.0 (part of 140)
**Depends on:** none
**Owns:**
- .github/dependabot.yml

## Goal

Dependabot should never propose a Java image newer than the approved LTS.
The current `semver-major` ignore on the `maven` image does not do this.
Dependabot reads the Java major in `maven:3.9-eclipse-temurin-N` as the
*patch* segment, so a move to Java 26 arrives as a patch update. Replace the
docker ignore rules with rules that block Java 26 and later for both images,
and cover `docker/distribution`, which Dependabot does not watch today.

## Context

- Evidence, 2026-10-07. Dependabot PR #116 was generated from `main`'s
  `dependabot.yml`, which has no docker ignore rules yet; task 130's rules
  are unpushed. It proposes `eclipse-temurin` `21-jre` → `25-jre` and `maven`
  `3.9-eclipse-temurin-21` → `3.9-eclipse-temurin-26`. So Dependabot will
  jump the build image to a non-LTS JDK.
- Why, from `dependabot-core` `docker/lib/dependabot/docker/tag.rb`:
  - `3.9-eclipse-temurin-21` matches `WORDS_WITH_BUILD`, so the whole string
    is the version. `numeric_version` strips `-[a-z]+`, which gives `3.9-21`,
    then `3.9.21`. `-25` and `-26` are `3.9.25` and `3.9.26`, both
    `semver-patch` bumps of `3.9.21`. A `semver-major` ignore never matches
    them.
  - `21-jre` has version `21` and suffix `-jre`, so `25-jre` and `26-jre` are
    `semver-major` bumps. Task 130's rule blocks both, including the
    approved 25.
- Task 143 moves every image to `eclipse-temurin:25-jre` and
  `maven:3.9-eclipse-temurin-25` by hand. After that, the tags float: the
  registry delivers Temurin 25 and Maven 3.9 patch releases with no
  Dependabot PR. A Dependabot PR on these images can only ever be a Java
  major change or Maven 4.
- Owner decision D-140-B picks the rule shape. Recommended: version ranges,
  `eclipse-temurin` `>= 26` and `maven` `>= 3.9.26`, plus `maven`
  `semver-major` for Maven 4. Java 29, the next LTS (expected September
  2027), is then a deliberate owner decision, as Java 25 was. Dependabot's
  `ignore` cannot express "LTS only" without a hand-kept list of non-LTS
  majors.
- The `org.springframework.boot` `semver-major` ignore (task 130) stays. It
  blocks Boot 5 once task 141 is on Boot 4.

## Acceptance

- [ ] The docker entry's `directories` adds `/docker/distribution`.
- [ ] The docker `ignore` rules are, with comments that explain the patch
      encoding above:
      - `eclipse-temurin`, `versions: [">= 26"]`
      - `maven`, `versions: [">= 3.9.26"]`
      - `maven`, `update-types: ["version-update:semver-major"]`

      No rule ignores `eclipse-temurin` `semver-major` any more. The comment
      citing D-130-B is replaced with one citing the Java 25 decision.
- [ ] The Spring Boot ignore's comment says that a Boot major is a migration
      (139) and not a bump. The rule itself is unchanged.
- [ ] The rules are proven against Dependabot's own logic, not argued. Either:
      - run the Dependabot CLI (`dependabot update docker ... --local .`) with
        a job file whose `ignore-conditions` mirror these rules, on a branch
        where the Dockerfiles are still on 21, and show it proposes
        `25-jre` but neither `26-jre` nor `3.9-eclipse-temurin-26`; or
      - after the change reaches the default branch, comment
        `@dependabot show maven ignore conditions` and
        `@dependabot show eclipse-temurin ignore conditions` on a Dependabot
        PR, and record the replies.

      The commit body or PR records which proof was used, with its output.
- [ ] `python3 -c 'import yaml,sys;yaml.safe_load(open(".github/dependabot.yml"))'`
      exits 0.

## Out of scope

- The Dockerfiles. That is task 143.
- Closing PR #116. The coordinator closes it once task 143 merges.
- Maven-ecosystem rules other than the Spring Boot comment.
- An allow-list of future LTS majors (see D-140-B).
