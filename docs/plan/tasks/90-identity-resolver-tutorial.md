# 90 — Add the custom identity-resolver tutorial and its tested example resolver

**Repo:** `.`
**Wave:** 2 (spec section C, tutorial 2)
**Depends on:** 85, 88, 89
**Base branch:** the LOCAL `site-polish` branch, after 85, 86, 87, 88 and 89
have all merged into it. `wt-new.sh` bases new worktrees on `main`, so right
after it, before any edit, run `git -C <worktree> reset --hard site-polish`.
The branch merges back into `site-polish`. This task file is uncommitted:
read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/90-identity-resolver-tutorial.md`.
**Owns:**
- data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/identity/** *(new)*
- data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/identity/** *(new)*
- data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java *(snippet-marker comments only)*
- data-prism-quickstart-extension/pom.xml *(test-scope dependencies and snippet-marker comments only)*
- docs/developer-guide/custom-identity-resolver.md *(replaces 85's stub)*
- docs/developer-guide/index.md *(embed diagram 6 and adjust the learning-path link text only)*
- docs-site/diagrams/extension-points.mmd *(new)*
- docs/assets/diagrams/extension-points.svg *(new, rendered by 88's `render.sh`)*
- docs-site/diagrams/README.md *(add diagram 6's trace-table rows only)*
- docs/developer-guide/write-an-adapter.md *(the one sentence at about lines 61–63, "the `IdentityResolver` and `DataSourceAdapter` beans the platform refuses to start without", only; added 2026-09-24 when closing task 89)*

## Goal

Tutorial 2 explains how one subject is recognised across sources whose keys
differ, and how that differs from the `pass-through` resolver the quickstart
ships. It is backed by a small example `IdentityResolver`, compiled and unit
tested inside `data-prism-quickstart-extension`, and every snippet comes from
that code. This task also draws the extension-points diagram (6), using task
88's render pipeline, and embeds it in the developer-guide overview, as the
spec requires. The spec's wave list says diagram 6 is placed by C1 or C2.

## Context

- **Binding sources:**
  - `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md`: section C (tutorial 2, "prefer inside `data-prism-quickstart-extension`"), section D item 6, and the Honesty row.
  - `docs/plan/specs/2026-09-23-discoverability.md` line 238.
- **The SPI:** `data-prism-core/.../core/IdentityResolver.java`.
  - `resolve(SourceRef)` returns a `CanonicalId`.
  - `expand(CanonicalId, List<String> sourceNames)` returns a `List<SourceRef>`. A source absent from the result is not queried.
  - `CanonicalId` is "never exposed; pseudonymised first".
  - Both records reject blank values.
  - The shipped default is `data-prism-core/.../core/PassThroughIdentityResolver.java`. Read its Javadoc: it assumes every source already keys on the same subject id.
- **Wiring:**
  - `QuickstartExtensionAutoConfiguration.quickstartIdentityResolver()` registers `PassThroughIdentityResolver` under `@ConditionalOnMissingBean`.
  - `DataPrismAutoConfiguration.dataPrismIdentityResolverPreflight` refuses startup with no `IdentityResolver` bean.
  - `docs/extending.md` "## Implement `IdentityResolver`" (line ~203) is the reference. Link it; do not duplicate it.
- **Where the example lives.** It goes in a new `…quickstart.extension.identity` package, as a class the auto-configuration does **not** register. The quickstart, Compose demo and `QuickstartSmokeIT` behaviour must stay exactly as they are.
  - A deterministic cross-reference, for example a fixed map from canonical subject to per-source keys, is the honest shape.
  - Do not present probabilistic matching as something the example does.
  - If the tutorial claims that a user-supplied `IdentityResolver` bean replaces the default, a test in this module must prove it. For example, an `ApplicationContextRunner` over `QuickstartExtensionAutoConfiguration`, which may need a test-scope `spring-boot-test` dependency in `pom.xml`. Otherwise the tutorial must not make that claim.
  - `data-prism-architecture` scans this module (`data-prism-architecture/pom.xml:54-58`), so its rules must stay green.
- **Snippets:**
  - 85 set `base_path: [data-prism-quickstart-extension, docker]`, with `check_paths: true`.
  - `PassThroughIdentityResolver` lives in `data-prism-core`, outside `base_path`. Link it on GitHub, or snippet the quickstart bean method (add markers to `QuickstartExtensionAutoConfiguration`). Do not edit `data-prism-core`.
  - Task 89 established the marker style.
- **Diagram 6** (spec section D item 6) shows where a `DataSourceAdapter` and an `IdentityResolver` plug in, relative to the privacy engine.
  - Every node and edge must trace to code (the two SPIs, `QuickstartExtensionAutoConfiguration`, `DataPrismAutoConfiguration`) or to `docs/architecture.md`.
  - It must not show an adapter path to the MCP layer that bypasses the privacy engine (`CLAUDE.md` rule 1).
  - 88 built `docs-site/diagrams/render.sh` (pinned by digest, `--network none`, renders every `.mmd`), the README trace table, and `check_diagrams.py`. `check_diagrams.py` requires each SVG to be referenced by a built page, have non-empty alt text, and contain no non-w3.org URL.
  - Use `render.sh` unchanged. If it cannot render diagram 6, report it; do not edit it.
- **Build recipe:** see task 85's Context. Java: `mvn -B verify -pl data-prism-quickstart-extension,data-prism-architecture -am`.

## Acceptance

- [ ] The example resolver exists under `…/quickstart/extension/identity/` with a unit test under the matching test package. The tests cover:
  - `resolve` for a key from each source;
  - `expand` omitting a source that does not know the subject;
  - an unknown key's behaviour (documented in Javadoc and asserted);
  - blank input rejected.

  The report quotes the surefire summary line for the new test class.
- [ ] Non-vacuity: breaking the resolver's mapping in a scratch copy (for example, returning the input key unchanged) makes the new test fail. The report quotes the failure.
- [ ] Quickstart behaviour is unchanged.
  - `git diff site-polish -- data-prism-quickstart-extension/src/main/resources` is empty.
  - `QuickstartExtensionAutoConfiguration.java`'s diff adds only `--8<--` comment lines.
  - `pom.xml`'s diff adds only test-scope dependencies and/or `--8<--` comments.
  - `mvn -B verify -pl data-prism-quickstart-extension,data-prism-architecture -am` is green, including `QuickstartSmokeIT`.
- [ ] `docs/developer-guide/custom-identity-resolver.md` explains three things: how one subject is recognised across sources with different keys (`resolve` / `expand`); how `pass-through` differs and when it is the correct choice; and how to register a resolver.
  - Every Java block comes from a `--8<--` region in the compiled example or the auto-configuration.
  - Every wiring claim is backed by a named test.
  - It links `docs/extending.md#implement-identityresolver`.
  - It has a unique description of at most 155 characters.
  - In a scratch copy, renaming one marker in the example class fails `mkdocs build --strict`. The report quotes the error.
- [ ] Every command the tutorial asks the reader to run (at least the `mvn` test run of the example) is run literally by the tester. Each output on the page is quoted from a real run.
- [ ] Overview page:
  - `docs-site/diagrams/extension-points.mmd` and its SVG exist. Re-running `render.sh` leaves `git status --porcelain docs/assets/diagrams` empty. Diagrams 1–5's SVGs stay byte-identical.
  - `docs-site/diagrams/README.md` gains trace rows for every node and edge of diagram 6. Its diff touches nothing else. The reviewer re-traces each row.
  - `grep -hoE 'https?://[^"'"'"' )]+' docs/assets/diagrams/extension-points.svg | sort -u` shows only w3.org namespace URIs.
  - `docs/developer-guide/index.md` embeds diagram 6 with one introductory sentence, plus alt text that states what it shows.
  - `git diff site-polish -- docs/developer-guide/index.md` shows only the image, its caption or intro sentence, and link-text changes.
  - `check_diagrams.py` still passes.
- [ ] Honesty: `grep -rniE 'anonymi|compliant|tamper-proof|guarantee|probabilistic|fuzzy' docs/developer-guide/ docs-site/diagrams/extension-points.mmd` finds only negations. The page does not claim canonical ids are ever shown to a client.
- [ ] Full recipe green:
  - `mkdocs build --strict`
  - every `docs-site/hooks/check_*.py`
  - lychee `--offline` as in `pages.yml`
  - actionlint
  - `grep -rE 'fonts.googleapis|googletagmanager|google-analytics|gtag|unpkg|jsdelivr|cdnjs' site/ --exclude='bundle.*.min.js*'` empty
- [ ] `git diff --stat site-polish` lists only files under **Owns**.

## Out of scope

- Registering the example resolver in the quickstart auto-configuration, or changing the quickstart's pass-through choice.
- Editing `data-prism-core` (including `PassThroughIdentityResolver` or the SPI), or adding a new reactor module (that would also require `pom.xml` and `data-prism-architecture/pom.xml` changes).
- `mkdocs.yml`, `docs/extending.md`, `write-an-adapter.md`, `render.sh`, `check_diagrams.py`, and diagrams 1–5's sources, SVGs and embeds.
- Any diagrams landing or "learn" page (the owner ruled it out, 2026-09-24).
- Tutorials on classification in depth or custom audit sinks (later plan).

## Amendment — 2026-09-24, carried from task 89's review

- **Correct the adapter tutorial's IdentityResolver sentence.** `docs/developer-guide/write-an-adapter.md` (about lines 61–63) says the auto-configuration class supplies "the `IdentityResolver` and `DataSourceAdapter` beans the platform refuses to start without". That is inaccurate for the resolver: `dataprism.identity.resolver: pass-through` supplies one with no code (DataPrismAutoConfiguration.java about lines 128–133). An application-supplied `IdentityResolver` bean still wins. Reword only that sentence, tracing it to the code, so it matches what this tutorial explains. Don't change anything else in that file. Acceptance: the sentence traces to DataPrismAutoConfiguration, and `git diff site-polish...HEAD -- docs/developer-guide/write-an-adapter.md` touches only those lines.
- Always diff with three dots (`git diff site-polish...HEAD`), because `site-polish` moves as tasks merge.
- Branch from LOCAL `site-polish` only after task 88 is recorded, because this task renders diagram 6 with 88's `render.sh`.
