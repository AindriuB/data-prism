# 23 — Give the architecture rules the whole module graph back

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-architecture/** *(new module)*
- pom.xml *(root: the `<modules>` entry for the new module only)*
- data-prism-example/pom.xml
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ArchitectureTest.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestSources.java
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/SecurityPolicy.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerArchitectureTest.java
  *(added by amendment, decided by the repository owner 2026-09-14: attempt 2
  widened `onlyTheExampleDependsOnSpringSecurity`'s exemption to cover
  `..dataprism.server..`, and the rule needs narrowing to the two genuine edge
  classes in the same branch, not a follow-up task)*

## Goal

The boundary rules live in `data-prism-example` because it was the one module
that saw the whole graph. It no longer does: the `data-prism-connectors-rest`
dependency was dropped, and `data-prism-hazelcast`,
`data-prism-spring-boot-autoconfigure` and `data-prism-server` were never on its
classpath. `onlyDesignatedClassesCreateMappers`, `mcpDoesNotReachSources` and
`connectorsAreLeaves` therefore enforce nothing outside the example. Put the
rules where every module is visible and make the coverage itself checkable.

## Context

- `data-prism-example/pom.xml:24-33` — the starter dependency, and the comment
  at `:45-47` still claiming the example "depends on every module".
- `data-prism-example/src/test/java/.../ArchitectureTest.java:19-21` —
  `@AnalyzeClasses(packages = "io.github.aindriub.dataprism")` imports whatever
  the classpath happens to hold, so a missing dependency silently narrows it to
  a rule that cannot fail.
- `ArchitectureTest.java:35-40,65-70,85-89` — the three rules named above.
- `ArchitectureTest.java:38` — `callConstructor(ObjectMapper.class)` matches the
  no-argument constructor only. `RestSources.java:30` and `SecurityPolicy.java:28`
  both call `new ObjectMapper(new YAMLFactory())` and evade it today.
- `data-prism-server/src/test/java/.../ServerArchitectureTest.java` — the
  spring-security-at-the-edge rule. It stays where it is; narrowed by
  amendment, see the `Owns` note above — not unchanged.
- `data-prism-server/src/main/resources/application.yaml` and
  `data-prism-example/src/main/resources/application.yaml` would collide on one
  classpath, which is why the scanning module must run no Spring context.
- Baseline: `main` at `99b419b`, 377 tests, 0 failures.

## Acceptance

- [ ] The whole-graph rules run in a module whose test classpath carries the
      main classes of every module named in the root `pom.xml` `<modules>` list,
      including `data-prism-connectors-rest`, `data-prism-hazelcast`,
      `data-prism-spring-boot-autoconfigure` and `data-prism-server`.
- [ ] A coverage guard test derives the expected module list from the root
      `pom.xml` `<modules>` element and asserts that at least one main class
      from each module is present in the imported class set. Adding a module
      without putting it on the scanning module's classpath fails this test.
- [ ] No rule gains an allowlist entry, a package exclusion or
      `allowEmptyShould(true)` in order to make the widened scan pass. If a
      newly visible module violates a rule, stop and report rather than widen.
- [ ] `onlyDesignatedClassesCreateMappers` matches every `ObjectMapper`
      constructor, not only the no-argument one. `RestSources` and
      `SecurityPolicy` are either changed to obtain their mapper from a
      designated class, or named in the rule with a javadoc sentence saying why
      a YAML configuration reader is not a route from source data to transport.
- [ ] Non-vacuity, by mutation: temporarily adding `new ObjectMapper()` to a
      class in `data-prism-server` makes `onlyDesignatedClassesCreateMappers`
      fail, and temporarily making a `data-prism-mcp` class reference a
      `connectors.rest` class makes `mcpDoesNotReachSources` fail. Report both
      observed failures; neither mutation is committed.
- [ ] `data-prism-example` keeps its own tests and its annotation-processor
      configuration; any comment in its pom that no longer describes its
      dependencies is corrected.
- [ ] `data-prism-server/src/test/java/.../ServerArchitectureTest.java`'s
      `that()` side is narrowed to the two genuine edge classes
      (`ServerSecurityConfiguration`, `JwtCallerContextExtractor`), per the
      ownership amendment above — it is not left unchanged.
- [ ] `mvn -B verify` is green from the repository root; the new test total is
      reported against 377.

## Out of scope

- Adding new architecture rules for boundaries that have none today.
- Making `data-prism-example` runnable or adding `spring-boot-maven-plugin` to
  it — task 18 owns the quickstart's runnable services.
- Any change to `data-prism-server` main code.

## Attempt 1 — failed

The attempt was reviewed APPROVE and was correct; it ended red deliberately,
per its own brief, on one genuine pre-existing violation rather than weakening
a rule. Verify confirmed the work independently: the module graph is not a
vacuous scan (14 modules with main code contribute 182 class files, including
`connectors-rest`, `hazelcast`, `spring-boot-autoconfigure` and `server`), the
rule move was faithful (11 rules out of `data-prism-example`, 11 in, bodies
byte-identical apart from the mapper rule), and the arithmetic reconciles at
377 − 11 + 12 = 378 with exactly one `Failures: 1` line in the whole reactor
log. The reviewer separately cleared all five `ObjectMapper` exemptions on
their individual merits — each is a startup YAML config reader with one
`readValue` call site, no `writeValue*`, returning a typed config record, so
none can reach a source response or an LLM-facing payload.

The failure: `ArchitectureTest.onlyTheExampleDependsOnSpringSecurity`, 43
violations, all in `data-prism-server` (`ServerSecurityConfiguration`,
`JwtCallerContextExtractor`). The rule exempted only `..dataprism.example..`.
It was written when the example was the system's only HTTP edge; task 17 gave
`data-prism-server` its own OAuth2 resource server, and nothing updated the
rule because no test could see that module until this task made it visible.

Resolution decided by the repository owner on 2026-09-14: widen the exemption
to cover `..dataprism.server..` as well, since both packages are the
deliberate HTTP edge, and rely on `ServerArchitectureTest` to keep the inner
boundary honest (it already enforces that nothing else in `data-prism-server`
touches Spring Security). A second attempt is in progress on the same branch.

## Attempt 2 — failed

Attempt 2 widened `onlyTheExampleDependsOnSpringSecurity` to exempt
`..dataprism.server..` alongside `..dataprism.example..`, per the owner's
decision, and applied two further review fixes. Two of the three changes
verified clean under empirical probing rather than reading:
`ArchitectureCoverageTest.originatesFromModule` now compares resolved
`target/classes` paths and discriminates a wrong module directory correctly,
and the new `designatedYamlReadersDoNotWrite` rule was probed with a compiled
class calling `writeValueAsString`, `writeValueAsBytes`, `writeValue(File,…)`
and `writer().writeValueAsString(…)` — all four flagged. The other ten rules
stayed byte-identical.

The failure: the widening's javadoc at `ArchitectureTest.java:303-307`
asserted that `ServerArchitectureTest` keeps the inner boundary of
`data-prism-server` honest. It does not. `ServerArchitectureTest.java:14`
reads `noClasses().that().resideOutsideOfPackage("..server..")`, which
exempts all of `..server..` and constrains only what lies outside it. Since
all six `data-prism-server` main classes sit in one flat package, the
widening left that module exempt in both tests at once — a Spring Security
dependency added to a non-edge class such as `ServerPrivacyMetrics` turns
nothing red. The comment therefore asserted a state nobody had established,
which `docs/conventions.md` forbids, and the exemption shipped unguarded.

Worth recording for the future, because it is the reason this got through:
the false claim originated in the first implementer's own close-out report,
was carried into the decision put to the repository owner, and into the
brief for attempt 2 — three restatements before any agent checked the file it
described. It was caught only when a reviewer read `ServerArchitectureTest`
directly instead of trusting the citation.

Resolution: task 23's ownership amended to include
`ServerArchitectureTest.java` so the rule can be narrowed to the two genuine
edge classes (`ServerSecurityConfiguration`, `JwtCallerContextExtractor`) in
the same branch that makes the claim, rather than deferring the guard to a
follow-up task. A third attempt is in progress.
