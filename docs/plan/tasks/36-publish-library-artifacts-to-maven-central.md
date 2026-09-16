# 36 — Publish the library artifacts to Maven Central

**Repo:** data-prism
**Depends on:** 33
**Owns:**
- pom.xml
- data-prism-*/pom.xml
- .github/workflows/publish-central.yml (new)

## Goal
Nothing in this repository can be consumed as a dependency today: there is no
signing, no sources or javadoc jar, no publishing plugin and no
`distributionManagement` (pom.xml:182-214 carries only compiler, surefire,
enforcer and the Spring Boot plugin). Configure Maven Central publishing under
`io.github.aindriub` for the modules that are library artifacts, and mark the
ones that are applications or test scaffolding as non-deployable, so a release
build cannot accidentally push a runnable server or a quickstart fixture to
Central.

## Context
- pom.xml:8-21 — group id, licences and the module list; `<url>`, `<scm>` and
  `<developers>` are absent and Central requires all three
- data-prism-spring-boot-autoconfigure/pom.xml:15-21 — the dependency fan-out that
  fixes the minimum publish set; `data-prism-hazelcast` is `<optional>` but still
  has to resolve for anyone who opts in
- data-prism-server/pom.xml:56-71 — `spring-boot-maven-plugin` `repackage`
  replaces the plain jar, which is one reason this module is not a library
  artifact
- `docs/plan/PLAN.md` — the open item on that repackage replacing the plain jar;
  do not try to fix it here
- `docs/plan/HISTORY.md`, grep `Task 18` — the precedent for a task whose
  contract includes a step only the repository owner can perform

## Decision this task implements, not re-opens
Deployed to Central (13 artifacts): the root aggregator `pom` — required, since
every module names it as `<parent>` — plus `data-prism-annotations`,
`data-prism-processor`, `data-prism-core`, `data-prism-pseudonymisation`,
`data-prism-validation`, `data-prism-security`, `data-prism-orchestration`,
`data-prism-mcp`, `data-prism-connectors-rest`, `data-prism-hazelcast`,
`data-prism-spring-boot-autoconfigure`, `data-prism-spring-boot-starter`.

Not deployed: `data-prism-server`, `data-prism-example`, `data-prism-architecture`,
`data-prism-quickstart-fixtures`, `data-prism-quickstart-issuer`,
`data-prism-quickstart-extension`.

## Acceptance
- [ ] Each of the six non-deployed modules sets `maven.deploy.skip` (or the
      equivalent plugin skip) to `true` in its own pom, with a one-line comment
      saying why it is not a library artifact.
- [ ] A release build produces, for each of the 12 deployed jar modules, a
      `-sources.jar`, a `-javadoc.jar` and a `.asc` for every artifact including
      the poms; and produces none of those for the six skipped modules. Show the
      check as a command whose output an outsider can read, e.g.
      `find . -path '*/target/*' -name '*.asc' | sort`.
- [ ] The root pom carries `<url>`, `<scm>` (connection, developerConnection,
      url) and at least one `<developers>` entry, and every deployed module has a
      non-blank `<name>` and `<description>` — the Central validation set.
- [ ] Signing and the sources/javadoc jars live behind a profile (or equivalent)
      that a plain `mvn verify` does not activate: the default developer build
      must not require a GPG key, and `mvn -B clean verify` stays green with no
      key present.
- [ ] `mvn -B -P<release-profile> clean verify` succeeds locally with javadoc
      generation enabled — javadoc errors fail this build, so any malformed
      javadoc found is fixed in the module that owns it, or the failure is
      reported rather than suppressed by disabling doclint wholesale.
- [ ] `.github/workflows/publish-central.yml` triggers on `push: tags: ['v*']`
      (plus `workflow_dispatch`), imports the GPG key and Central credentials from
      repository secrets by name, runs the full test suite before deploying, and
      deploys only the reactor's deployable modules. It does not auto-publish
      without the owner's step unless the owner has said otherwise.
- [ ] The owner-only prerequisites are written down in the task's hand-off report
      and blocked on explicitly rather than faked: Central Portal namespace
      verification for `io.github.aindriub` (the GitHub login is `AindriuB`;
      confirm the Portal accepts the lowercased namespace for that account), and
      the repository secrets holding the GPG private key, its passphrase and the
      Central Portal token. An implementer that cannot create these stops and
      reports, exactly as task 18 did for `.env.example`.

## Out of scope
- Actually publishing to Central. The workflow and the local dry run are the
  deliverable; the first real deploy happens at `/record` with the owner's
  credentials.
- Changing the version, which task 33 already set to `0.1.0`.
- Any `<classifier>` fix for `data-prism-server`'s repackage. Known debt, and
  that module is not published.
- Adding, removing or renaming a module, or moving a class between modules.
- The OCI image (37) and the registry entry (38).
- `docs/**` — scribe hand-off at `/record`.
