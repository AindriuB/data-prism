# 50 — Write the adapter developer guide

**Executor:** `scribe` (this is documentation; per `CLAUDE.md` rule 4 only
`scribe` writes docs). It will need to **run** builds and a server to check
its own claims — that is required, not optional, see Acceptance.
**Repo:** `.` (`/Users/Andrew/workspace/data-prism`)
**Depends on:** none
**Owns:**
- `docs/extending.md` (new file, and the only file this task writes)

## Goal

Nothing in this repository teaches someone how to extend Data Prism
end to end. `DataSourceAdapter` and `IdentityResolver` are *named* in
`README.md`, `docs/configuration.md` and `docs/architecture.md`, and *taught*
only in `docs/pack.md`'s spec prose and in javadoc — neither of which a
consumer should have to read. Write `docs/extending.md`: the single
end-to-end contract for building, packaging and loading a reviewed adapter
extension, structured as the path a consumer actually walks.

## Context

The in-repo worked example is `data-prism-quickstart-extension`, which is
CI-covered by `QuickstartSmokeIT`. **No new module may be created** — the
owner has ruled that out. Cite the existing one.

- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/DataSourceAdapter.java`
  and `.../IdentityResolver.java` — the two SPIs to implement.
  `PassThroughIdentityResolver` is the only-when-every-source-shares-an-id
  option (`README.md:160-163`).
- `data-prism-quickstart-extension/` — the whole module is the worked
  example: `QuickstartCustomerAdapter.java`, `CustomerModel.java`,
  `QuickstartExtensionAutoConfiguration.java`, and its single-line
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- `data-prism-quickstart-extension/pom.xml:38-57` — the `provided`-scope
  block (`data-prism-core`, `data-prism-annotations`, `spring-web`,
  `spring-boot-autoconfigure`); `:105-111` — `annotationProcessorPaths`
  carrying `data-prism-processor`. The processor must appear **only** there,
  never as a `<dependency>`.
- `data-prism-annotations/src/main/java/.../` — `LlmExposedModel`,
  `SensitiveData`, `NonSensitive`, `InternalIdentifier`, `SubjectIdentifier`,
  `SensitiveObject`, `UndeclaredFields`, `PrivacyAction`, `PrivacyNamespace`.
- `data-prism-processor/src/main/java/.../LlmExposedModelProcessor.java:70` —
  the single `Diagnostic.Kind.ERROR` emission. What it rejects is the
  authoritative answer; `docs/conventions.md:40-46` states the rule but is a
  process doc, and the guide must not send a consumer there.
- `data-prism-spring-boot-autoconfigure/src/main/java/.../DataPrismContractValidator.java:29`
  — `UNRESOLVED_SOURCE_ADAPTER` and its exact message. Proven by
  `data-prism-server/src/test/java/.../ServerStartupTest.java:55`.
- `README.md:160-176` — the one existing walkthrough-adjacent passage, one
  passing mention of the `.imports` file, never walked through.

**The `-Dloader.path` trap.** `-Dloader.path` puts the extension jar's own
classes and resources on the running server's classpath and **not its
dependencies**. It fails at runtime, not at build time, which makes it the
single most expensive mistake a consumer can make here. Correcting the brief
this task was planned from: it is *not* undocumented. It is stated at
`data-prism-quickstart-extension/pom.xml:18-26` and again at
`docker/distribution/Dockerfile:33-35`. What is true is that it appears in no
consumer-facing document — a reader has to be already reading two build files
to find it. Give it its own section, before the pom section, and cite both
existing statements rather than inventing a third wording.

Line numbers were derived on 2026-09-17 against `e5d1c22`. Re-derive with
`rg -n` before citing; a mismatch means the file moved.

## Acceptance

- [ ] `docs/extending.md` exists and covers, in order a consumer walks:
      implementing `DataSourceAdapter`; implementing `IdentityResolver` (and
      when `PassThroughIdentityResolver` is legitimate); classifying the model
      with `@LlmExposedModel` and the field annotations; the pom shape;
      registration via the `.imports` file; loading via `-Dloader.path`; and
      binding `dataprism.sources.<name>` to the adapter.
- [ ] Every code snippet in the file is either copied verbatim from a file in
      this repository, with a `path:line` citation, or is the captured output
      of a command the writer actually ran, with that command shown. No
      snippet is composed by hand. This standard is not negotiable: this
      repository has a recorded history of docs drifting from reality, and
      the precedent is the scribe who fixed `docs/agents/stdio.md:102` by
      re-driving the stdio fixture server and capturing real output rather
      than hand-editing.
- [ ] The list of what the annotation processor rejects at build time is
      derived from `LlmExposedModelProcessor` and **proven by compiling**: add
      an unclassified field to a throwaway `@LlmExposedModel` outside the
      repository (or in a scratchpad copy), run `javac`/`mvn` against it, and
      paste the real `error:` line the processor emitted. Do not paraphrase
      `docs/conventions.md`.
- [ ] The `-Dloader.path` section states, in its own words and before the pom
      section, that the extension jar's dependencies are **not** added to the
      classpath and that the failure surfaces at runtime. It cites
      `data-prism-quickstart-extension/pom.xml` and
      `docker/distribution/Dockerfile` as the existing statements of the same
      fact, and explains the `provided` scoping as the consequence.
- [ ] The `dataprism.sources.<name>` section states that binding is by the
      `sourceName()` **return value** — not bean name, not class name, not an
      annotation — and names `UNRESOLVED_SOURCE_ADAPTER` as what a mismatch
      produces, quoting the refusal message exactly as
      `DataPrismContractValidator` emits it. The refusal is **observed**: run
      the packaged server (or the starter) with a deliberately mismatched
      source name and paste what it actually printed.
- [ ] The guide contains no table or list of `dataprism.*` properties. Every
      property it mentions links to `docs/configuration.md`. Checkable: `rg -c
      'dataprism\.' docs/extending.md` is small, and every occurrence is
      either a link target or a single named property in prose.
- [ ] The processor appears in the guide's pom guidance only under
      `annotationProcessorPaths`. Checkable: `rg -n 'data-prism-processor'
      docs/extending.md` shows no occurrence inside a `<dependency>` element.
- [ ] If the external consumer project at
      `/Users/Andrew/workspace/data-prism-github-demo` is referenced at all,
      it is referenced only by its public repository URL — verified to
      resolve publicly — never by a filesystem path, and it is described as a
      third-party example this repository does not build or test. Checkable:
      `rg -n 'workspace/data-prism-github-demo' docs/extending.md` returns
      nothing, and no build file, workflow or test in this repository
      mentions it.

## Out of scope

- Creating any new module. The owner has ruled it out; use
  `data-prism-quickstart-extension`.
- `docs/configuration.md`, `README.md`, `docs/architecture.md`,
  `docs/quickstart.md`, `docs/agents/**` — task 52 owns all of them, and 52
  is what adds the link from `README.md` to this new file. Do not add the
  link yourself; that is the collision this split exists to prevent.
- The two MCP tools' arguments, responses and capability grants. Task 51
  owns `docs/tools.md`. Link to it by name if you need to; do not describe it.
- The module rename in flight as task 49. **Do not write the string
  `data-prism-example` or `data-prism-integration-tests` into this file.**
  Task 49 is renaming that module in a sibling worktree, so either name would
  be a claim you cannot check. Cite classes and packages
  (`io.github.aindriub.dataprism.example.StubCustomerAdapter`) instead — the
  Java package is explicitly unchanged by 49.
- Re-specifying anything in `docs/pack.md`. That is the specification; this
  is a guide.
