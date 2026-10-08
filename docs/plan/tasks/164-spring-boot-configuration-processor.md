# 164 — Generate Spring configuration metadata for `dataprism.*` and check it against docs/configuration.md

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2)
once the tasks in Depends on have landed on that line. Do not start until owner decisions D-164-A and D-164-B below
are recorded in this file.
**Depends on:** 158, 159
**Owns:**
- data-prism-spring-boot-autoconfigure/pom.xml
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json (new)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/*Properties.java (Javadoc on fields and classes only; no code change)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ConfigurationMetadataDocumentedTest.java (new)
- data-prism-spring-boot-autoconfigure/src/test/resources/configuration-metadata-gaps.txt (new, the checked-in allow-list)
- docs/configuration.md (default cells and missing rows in the property tables, plus one sentence on IDE completion; no property renamed)
- data-prism-spring-boot-starter/pom.xml (only if the hand-back shows the starter needs a change; expected: none)

## Goal
Owner decision D-0.6-9. Add `spring-boot-configuration-processor` as a
build-time-only processor in the autoconfigure module, so the shipped jar
carries `META-INF/spring-configuration-metadata.json`. IDEs then complete and
document `dataprism.*` keys in YAML. A test keeps the metadata and
docs/configuration.md in step. Property names, types, defaults and refusals
do not change.

## Context
- data-prism-spring-boot-autoconfigure/pom.xml at 438ef802 has no `<build>` section. No module declares `spring-boot-configuration-processor` (158's Context, confirmed by 158's hand-back).
- pom.xml:71 Spring Boot 4.1.1, imported as a BOM at :113, so the processor's version is managed. pom.xml:81 `maven-compiler-plugin` 3.16.0, with no `annotationProcessorPaths` in the root pom.
- `data-prism-processor`, the project's own processor, is configured through `annotationProcessorPaths` only in data-prism-quickstart-extension/pom.xml:110-126 and data-prism-integration-tests/pom.xml:~94. It is not on the autoconfigure module's processor path. Setting `annotationProcessorPaths` in a module switches off classpath discovery for that module, which is why D-164-A matters.
- After 158, `DataPrismProperties` is the root, and each concern is a top-level `*Properties.java` in `spring.boot`. The processor reads field Javadoc for descriptions, and reads defaults from field initialisers only when they are simple literals or constants it recognises. A `Duration` or `Period` built in code may come out with no default.
- Keys bound outside `@ConfigurationProperties` do not appear in the generated metadata. Example: `dataprism.sources.<name>.*` for configured JSON sources, bound through `Binder` in data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesInitializer.java:~124. They need `additional-spring-configuration-metadata.json` entries or allow-list rows.
- docs/configuration.md documents keys two ways. Property tables have a Default column (for example :205-211, :266-274). Prose names keys in backticks with no table (for example `sink`, `file-path`, `entity-types` under `### dataprism.audit`, :157-200). Only table rows have a checkable default.
- `quickstart.issuer.*` (data-prism-quickstart-issuer IssuerProperties.java:13) is not `dataprism.*` and is not in this task.

## Owner decisions required before starting

**D-164-A: how the processor is put on the compiler's path.**
- **(1) An `<optional>true</optional>` dependency, discovered on the classpath. This is the form D-0.6-9's wording names.**
  - For: one dependency block, with the version from the Boot BOM. It does not interact with `data-prism-processor`, which this module does not use. Optional means it never reaches the starter's or the server's runtime classpath.
  - Against: it relies on implicit processor discovery. On JDK 21 javac prints a note about that. From JDK 23 javac does not run discovered processors unless `-proc:full` is set, so a toolchain upgrade silently stops generating the metadata. The test below would catch that, but only as a failure.
- **(2) In the module's `maven-compiler-plugin` `annotationProcessorPaths`, with the version taken from the BOM (`<annotationProcessorPathsUseDepMgmt>true</annotationProcessorPathsUseDepMgmt>`, compiler plugin 3.12 or later).**
  - For: explicit and independent of the JDK's discovery default. It matches how the project already wires `data-prism-processor`. Nothing is added to the dependency tree at all.
  - Against: it switches off classpath discovery for this module, so any processor that runs implicitly today stops. The hand-back must show the processor list before and after: expected empty before. It adds a `<build>` section to this pom.
- **(3) Both: the optional dependency plus `-proc:full`.**
  - For: works on newer JDKs without listing paths.
  - Against: two mechanisms to keep in step. `-proc:full` turns on any other processor that happens to be on the classpath.

**D-164-B: how strict the docs ↔ metadata check is.**
- **(i) Exact, both directions. Every `dataprism.*` key in the metadata is in docs/configuration.md, and every documented key is in the metadata. Each exception is a line in a checked-in allow-list with a reason. Documented defaults must equal metadata defaults.**
  - For: drift in either direction fails the build.
  - Against: the first run will likely surface many undocumented internal or nested keys. The task then either documents them (a larger docs diff) or allow-lists them (a list that can rot).
- **(ii) Docs → metadata only. Every documented key and table default must be in the metadata. Undocumented metadata keys are reported in the hand-back but do not fail.**
  - For: matches D-0.6-9's wording exactly, and the first diff is small.
  - Against: a new property can ship undocumented without failing anything.
- **(iii) As (i), but missing defaults are tolerated where the processor cannot infer them (Duration, Period, computed values), and only key presence fails both ways.**
  - For: avoids filling `additional-spring-configuration-metadata.json` with hand-written defaults that can themselves drift.
  - Against: documented defaults for those types go unchecked.

## Acceptance
- [ ] The processor is wired per D-164-A, with no version literal in any pom. `mvn -B dependency:tree -pl data-prism-spring-boot-starter,data-prism-server` shows no `spring-boot-configuration-processor`; the command and the grep are in the hand-back.
- [ ] `jar tf data-prism-spring-boot-autoconfigure/target/data-prism-spring-boot-autoconfigure-*.jar | grep spring-configuration-metadata.json` prints `META-INF/spring-configuration-metadata.json`, in the hand-back.
- [ ] Processor ordering: the hand-back shows the `-processorpath` or discovered-processor list for the autoconfigure, quickstart-extension and integration-tests compiles (from `mvn -X` or `-Dmaven.compiler.verbose`). `data-prism-processor` still runs in the two modules that use it: their existing `@LlmExposedModel` rejection tests pass unedited. The configuration processor runs in autoconfigure only.
- [ ] Every `dataprism.*` property in the generated metadata has a non-empty `description`, taken from field Javadoc. The test asserts it, and lists offenders by key on failure.
- [ ] `ConfigurationMetadataDocumentedTest` loads `META-INF/spring-configuration-metadata.json`, merged with the additional file, from the test classpath. It extracts every backticked `dataprism.<…>` key from `../docs/configuration.md`, normalising `<name>` and `<field>` segments to the metadata's map or wildcard form. It enforces D-164-B's chosen strictness. Each failure names the key and the side it is missing from, or the two differing defaults.
- [ ] Documented "unset" or "none" counts as "no default in the metadata". Default formats are normalised before comparing (for example `PT5M` and `5m`), and the rules are in the test's Javadoc.
- [ ] `configuration-metadata-gaps.txt`: one key per line with a reason. The hand-back lists every entry, grouped as "bound outside `@ConfigurationProperties`", "processor cannot infer default", "internal, deliberately undocumented". Removing any line makes the test fail (proved for one line in the hand-back).
- [ ] Mutation proofs in the hand-back: renaming one documented key in docs/configuration.md fails the test, and changing one documented default fails the test (unless D-164-B (iii) exempts that type).
- [ ] `additional-spring-configuration-metadata.json` has value hints for every `String` property whose docs list a closed set: at least `dataprism.audit.sink` (`approved-sink`, `slf4j`, `hash-chained`) and `dataprism.audit.output.field-preset` (`canonical`, `ecs`). The hand-back lists the full set. Free-form, regex-validated keys such as `dataprism.audit.entity-types` get no value hint; they get a description saying what the pattern is. Java `enum` properties get no manual hint.
- [ ] No property changes: 158's `PropertyNamesFrozenTest` checked-in list is unchanged (`git diff` empty), and `git diff` on `*Properties.java` touches only comment lines (`git diff -U0 | grep '^[+-][^+-]' | grep -vE '^\s*[+-]\s*(\*|/\*\*|\*/)'` is empty).
- [ ] docs/configuration.md changes only default cells that were wrong (each listed in the hand-back with the code value it now matches), rows added for keys D-164-B requires documenting, and one sentence saying the jar ships configuration metadata for IDE completion.
- [ ] The hand-back contains the one-line CHANGELOG `### Added` entry for task 162 to copy.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- Renaming, adding or removing any property, or changing a default or refusal in code.
- Metadata for `quickstart.issuer.*` or any non-`dataprism` prefix.
- Moving `dataprism.sources.<name>.*` binding into `@ConfigurationProperties`.
- `data-prism-processor` itself.
- `CHANGELOG.md` (task 162 writes the entry from this task's hand-back).
- Properties added by 163 (163 runs after this task and must satisfy this check).

## Owner decision D-164-A: decided 2026-10-08, option 2

`spring-boot-configuration-processor` is listed explicitly in the maven-compiler-plugin `annotationProcessorPaths` of data-prism-spring-boot-autoconfigure, with its version from the Spring Boot BOM. Classpath processor discovery is not relied on and `-proc:full` is not used.

The owner's concern: we cannot know what a full classpath scan would wire in consumers' environments. To address it:
- the processor must not appear in any published module's dependency tree, whether compile, runtime or transitive. Prove this with `mvn dependency:tree` on the starter and the autoconfigure module;
- the compiler-plugin config must not be inherited by consumers;
- the docs/configuration.md sentence on IDE completion must also say that consumers' own `@ConfigurationProperties` are unaffected. Their binding happens at runtime and needs no processor, and they add the processor to their own build only if they want IDE metadata for their own properties.

## Owner decision D-164-B: decided 2026-10-08, option (i)

The check is exact in both directions:
- every key documented in docs/configuration.md exists in the metadata with the documented default;
- every `dataprism.*` key in the metadata is documented.

Defaults the processor cannot infer, such as Duration, Period and computed values, are still checked, by parsing the documented value and comparing it with the field initialiser or an `additional-spring-configuration-metadata.json` entry.

Exceptions go in the checked-in allow-list (`configuration-metadata-gaps.txt`) with one reason per line. Binder-bound `dataprism.sources.<name>.*` is the expected main entry.

Any undocumented keys or wrong defaults found on the first run are fixed in this task, or allow-listed with a reason. Each one is listed in the commit body.
