# 89 — Start the developer guide: overview plus a tested "write a data-source adapter" tutorial

**Repo:** `.`
**Wave:** 1 (spec section C, tutorial 1)
**Depends on:** 85
**Base branch:** the LOCAL `site-polish` branch, after 85 has merged into it.
`wt-new.sh` bases new worktrees on `main`, so right after it, before any
edit, run `git -C <worktree> reset --hard site-polish`. The branch merges
back into `site-polish`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/89-developer-guide-and-adapter-tutorial.md`.
**Owns:**
- docs/developer-guide/index.md *(replaces 85's stub)*
- docs/developer-guide/write-an-adapter.md *(replaces 85's stub)*
- docs/extending.md *(a short pointer to the guide only)*
- data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/CustomerModel.java *(snippet-marker comments only)*
- data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartCustomerAdapter.java *(snippet-marker comments only)*
- data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java *(snippet-marker comments only)*
- data-prism-quickstart-extension/src/main/resources/META-INF/spring/** *(snippet-marker comments only, if used)*
- data-prism-quickstart-extension/pom.xml *(snippet-marker XML comments only)*
- docker/server/application.yaml *(snippet-marker comments only)*
- docker/server/Dockerfile *(snippet-marker comments only)*
- docker/distribution/Dockerfile *(snippet-marker comments only)*

## Goal

Open a "Developer guide" section. First, an overview page covering the
extension points (what each is for, and the learning path). Then tutorial 1,
which goes from an empty module to a pseudonymised MCP tool call using a
custom `DataSourceAdapter`. Every code snippet is pulled at build time from
marked regions in real, compiled and tested files, never hand-copied, so the
tutorial cannot drift from the code. `docs/extending.md` stays the full
reference.

## Context

- **Binding sources:**
  - `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md`: section C (tutorial 1's step list, snippet rule, extending.md rule), the Decisions table, and the Honesty row.
  - `docs/plan/specs/2026-09-23-discoverability.md` line 238: the honesty greps.
- **Snippet plumbing from 85:**
  - `pymdownx.snippets` is enabled with `check_paths: true`, `restrict_base_path: true` and `dedent_subsections: true`.
  - `base_path: [data-prism-quickstart-extension, docker]`.
  - Reference a region as `--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartCustomerAdapter.java:fetch"`, or `--8<-- "server/application.yaml:customer-source"`.
  - Mark regions with `// --8<-- [start:fetch]` … `// --8<-- [end:fetch]` in Java, `<!-- --8<-- [start:x] -->` in XML, and `# --8<-- [start:x]` in YAML/Dockerfile.
  - A missing file or region fails `mkdocs build --strict`.
- **The example code:** `data-prism-quickstart-extension` is a reactor module (plain jar, `provided` deps, `data-prism-processor` on the annotation-processor path). It contains:
  - `CustomerModel`: an `@LlmExposedModel` record with `@InternalIdentifier`, `@SensitiveData` and `@NonSensitive` fields.
  - `QuickstartCustomerAdapter`: `DataSourceAdapter<CustomerModel>`, `SOURCE_NAME = "customer"`, 404 → `null` → `NO_DATA`.
  - `QuickstartExtensionAutoConfiguration`: an `IdentityResolver` bean (pass-through) and the adapter bean, reading `dataprism.sources.customer.base-url` / `.timeout`.
  - `META-INF/spring/…AutoConfiguration.imports`.
  - `QuickstartSmokeIT` (failsafe) proves it end to end against the packaged server.
  - `data-prism-architecture` scans this module (`data-prism-architecture/pom.xml:54-58`).
- **Loading:**
  - `docker/server/Dockerfile:48` launches with `-Dloader.path=…/data-prism-quickstart-extension.jar`.
  - `docker/distribution/Dockerfile:38-49` documents `LOADER_PATH=/app/adapters`.
  - The quickstart's `dataprism.sources.customer` config is in `docker/server/application.yaml`.
- **Running it:**
  - `docker compose up` **pulls published images**, which do not contain a reader's local changes.
  - `docker compose -f compose.yaml -f compose.build.yaml up --build` builds from source (`docs/quickstart.md:30-34`).
  - `docs/quickstart.md` "## Get a token" / "## Run the demo" has the real token and MCP-call commands. The tutorial's run step must use whichever path actually exercises the code shown.
- **Reference.** `docs/extending.md` (671 lines) is the full reference and must not be duplicated. Link its sections instead:
  - "The `-Dloader.path` trap"
  - "Implement `DataSourceAdapter`"
  - "Classify the model with `@LlmExposedModel`"
  - "The pom shape"
  - "Register the extension"
  - "Load the extension"
  - "Bind `dataprism.sources.<name>` to your adapter"

  The only line-number citation into it is historical (`docs/plan/HISTORY.md:329`), but keep the pointer small anyway.
- **Honesty:**
  - The module's pom uses the reactor parent and `provided` scopes. A reader outside the reactor needs explicit versions, so say so and link "The pom shape"; do not imply the snippet is a standalone pom.
  - Pseudonymised output is still personal data. Never say "anonymised".
  - Do not state that the unclassified-field behaviour is configurable (task 84).
- The overview page's extension-points diagram (6) is drawn and embedded by task 90 in wave 2. **Leave it out here.**
- **Build recipe:** see task 85's Context. Java: `mvn -B verify -pl data-prism-quickstart-extension,data-prism-architecture -am` runs `QuickstartSmokeIT` and the architecture rules.

## Acceptance

- [ ] **Markers change nothing but comments.**
  - `git diff site-polish -- data-prism-quickstart-extension docker` adds only comment lines that contain `--8<--`, and removes nothing. `git diff site-polish -U0 -- data-prism-quickstart-extension docker | grep '^[-+][^-+]' | grep -v -- '--8<--'` is empty.
  - `mvn -B verify -pl data-prism-quickstart-extension,data-prism-architecture -am` is green, `QuickstartSmokeIT` passes, and the report quotes the failsafe summary line.
  - `docker buildx build --check` passes on both Dockerfiles.
- [ ] **`docs/developer-guide/index.md`** covers:
  - what `DataSourceAdapter` and `IdentityResolver` are for, each linked to its source file and to its `docs/extending.md` section;
  - `AuditSink` and the classification annotations, marked as covered in a later part of the guide;
  - the learning path: tutorial 1, then tutorial 2 (`custom-identity-resolver.md`).

  It keeps a unique description of at most 155 characters.
- [ ] **`docs/developer-guide/write-an-adapter.md`** walks these steps, in order:
  1. model and annotations
  2. the `DataSourceAdapter` implementation
  3. auto-configuration and its `AutoConfiguration.imports` registration
  4. pom shape
  5. loading with `LOADER_PATH` / `-Dloader.path`
  6. `dataprism.sources.<name>`
  7. running it and seeing the pseudonymised response

  Every code, YAML, pom or Dockerfile block on the page comes from a `--8<--` include:
  - `grep -cE '^\s*(```|~~~)' docs/developer-guide/write-an-adapter.md` counts only fences that wrap a `--8<--` line, or fences for shell commands and captured output.
  - The report lists each fence and its type.
- [ ] **Snippets are non-vacuous.** In a scratch copy, each of these makes `mkdocs build --strict` fail, and the report quotes the error:
  - renaming one marker in `QuickstartCustomerAdapter.java`;
  - deleting one `[end:…]` marker.
- [ ] **Commands are real.**
  - Every shell command the tutorial tells a reader to run is run literally by the tester, from a clean checkout of this branch, in page order. That includes the build, the Compose `--build` bring-up, the token fetch and the MCP call.
  - Each output block on the page is quoted from a real run, and marked as such with the run date. The response shows a pseudonymised `customerName` and a redacted `email`.
  - The tester's report pastes their own outputs next to the page's outputs.
- [ ] `docs/extending.md` gains only a short pointer (at most 5 lines) to the developer guide. `git diff site-polish -- docs/extending.md` shows no other change.
- [ ] Honesty: `grep -rniE 'anonymi|compliant|tamper-proof|guarantee' docs/developer-guide/ docs/extending.md` finds only pre-existing `extending.md` lines or negations. No step shows a relaxed privacy profile as selectable.
- [ ] Full recipe green:
  - `mkdocs build --strict`
  - every `docs-site/hooks/check_*.py`
  - lychee `--offline` as in `pages.yml`
  - actionlint
  - `grep -rE 'fonts.googleapis|googletagmanager|google-analytics|gtag|unpkg|jsdelivr|cdnjs' site/ --exclude='bundle.*.min.js*'` empty
- [ ] `git diff --stat site-polish` lists only files under **Owns**.

## Out of scope

- The identity-resolver tutorial and any example `IdentityResolver` (task 90). `custom-identity-resolver.md` stays 85's stub.
- Drawing or embedding diagram 6 (task 90), and diagrams 1–5 (88).
- Any behavioural change to the quickstart-extension classes, the Dockerfiles or `application.yaml`; any new class or test in the module.
- `mkdocs.yml` or snippet config changes. If `base_path` is insufficient, report it; do not edit (86 owns `mkdocs.yml` in this wave).
- Rewriting or restructuring `docs/extending.md`; verifying its 32 existing fences.

## Attempt 1 — failed

Tester: PASS. The tutorial ran literally from a clean clone, and its output matched the page character for character; all 9 snippets match the source byte for byte; mvn and QuickstartSmokeIT are green. Reviewer: CHANGES. Rebase onto LOCAL `site-polish` and always diff with three dots (`git diff site-polish...HEAD`). Then fix:

1. **The pom scope prose contradicts the snippet** (write-an-adapter.md about lines 104–105). It says the pom "marks everything but data-prism-core/data-prism-annotations `provided`", but the snippet above it (pom.xml about lines 43 and 48) and extending.md:117 mark all four `provided`. A reader following the prose would make core compile-scope, which is the loader.path trap. Make the prose match the snippet.
2. **The annotation authority is overstated** (write-an-adapter.md about lines 35–36 and 180). The page says `@SensitiveData` "states what should happen" and that email is redacted "per that field's own REDACT action". In the code, `suggestedAction` is a suggestion, and the profile rule, or the stricter of the two, wins (ProfilePrivacyPolicyResolver.java about lines 79–93; extending.md:316). Reword it so the privacy engine decides and the annotation only suggests. CLAUDE.md rule 1 applies.
3. **index.md overclaims tutorial 1's coverage** (about lines 47–48). It says AuditSink and the annotations are "Both … used … in tutorial 1", but AuditSink does not appear in tutorial 1. Correct it.
4. **A missing end marker is silent.** pymdownx.snippets 12.1 reads to EOF when an `[end:x]` is missing (confirmed in the library source and by the tester). Add `docs-site/hooks/check_snippet_markers.py`; it is accepted into this task's scope and collides with nothing. The check must:
   - read `base_path` from mkdocs.yml;
   - scan every file under it, using pymdownx's own section regex (`-{1,}8<-{1,}`), so both the `--8<--` and `-8<-` forms count;
   - fail on a missing, duplicate or out-of-order `[end:x]`, and on an unmatched `[start:x]`, naming the file and section.
   The existing check_*.py loop in pages.yml picks it up, so there is no pages.yml or mkdocs.yml change. Planted faults, each in a scratch copy:
   - (a) delete `[end:dependencies]` from pom.xml;
   - (b) delete an end marker from a Java file;
   - (c) duplicate a start marker.
   Each must fail with its message quoted. The unmodified tree passes. This replaces the unmeetable "deleting an end marker fails the build" criterion, which now reads "…fails check_snippet_markers.py".
5. **The acceptance grep** for marker-only changes becomes `grep -vE -- '-8<-'`, which matches both forms.
6. **Suggestions to apply:**
   - Line 126: the `@Value` bindings live in QuickstartExtensionAutoConfiguration, not in "the adapter's".
   - Lines 84–86: after the dependencies snippet, which stops before `</dependencies>`, note "(test-scope entries omitted)".
   - Line 145: say to run `run.sh` in a second terminal, since the foreground `up --build` blocks the first. Keep the command itself unchanged, because the quoted run used it.
   - index.md: "linked to its source file" — make the source paths real links to the files on GitHub, or drop the word "linked".

Keep green:
- mvn verify, with QuickstartSmokeIT;
- marker-only diffs in the Java, pom and Docker files;
- buildx --check;
- snippets byte-identical to their source regions;
- the page's quoted output unchanged (no re-run needed unless a command changes);
- the strict build and every check_*.py;
- the stub guard naming only custom-identity-resolver;
- lychee and actionlint;
- the Owns scope, plus check_snippet_markers.py.
