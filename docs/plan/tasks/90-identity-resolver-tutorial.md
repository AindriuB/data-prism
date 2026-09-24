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

## Attempt 1 — failed

Tester: PASS. mvn and QuickstartSmokeIT are green; the identity tests are non-vacuous; the snippets are byte-identical; the literal run matches the page; diagram 6 is legible and byte-stable; no stubs remain. Reviewer: CHANGES, on tutorial accuracy. Rebase onto LOCAL `site-polish`, which now also carries the CHANGELOG and description-D fixes. Always diff with three dots. Then fix:

1. **The tutorial presents `resolve` as the runtime mechanism, but it isn't** (custom-identity-resolver.md about lines 25–29 and 78–82). The only runtime call is `expand(new CanonicalId(request.subjectId()), names)` (DefaultContextOrchestrator.java about line 313). Nothing in production code calls `resolve`. Rewrite the "how one subject is recognised across sources" and pass-through sections to match the code:
   - The caller's `subjectId` is taken as the canonical id.
   - `expand` maps it to each source's own key.
   - Under pass-through, every source is queried with the caller's id unchanged. A source that keys differently usually comes back as no data. It is a wrong-subject merge only if keys collide across sources.
   - Say plainly that the caller must ask for the canonical id. With MappedIdentityResolver, asking for "C-1001" ends in NO_SOURCE_DATA.
   - Describe what `resolve` is for only as far as the code and the SPI javadoc support. If nothing calls it, say it isn't on the request path today.
2. **The canonical id does more than look up source keys** (about lines 49–51). `request.subjectId()`, the canonical id, also keys:
   - the per-scope subject pseudonym (`synthetics.syntheticValue(request.subjectId(), …, context)`, DCO about line 151);
   - the fingerprint (about line 152);
   - the scope read budget (about line 165).

   Cover how identity feeds the per-scope pseudonym; the spec requires it. Fix "the canonical id is never shown to a client": the client supplies it, and it is the response that pseudonymises it.
3. **Diagram 6 and its index.md intro and alt text** (extension-points.mmd line 5; index.md about lines 48–50):
   - The preflight refuses startup when *either* bean is missing, not only "with neither bean present" (DPAC about line 107). Fix the label.
   - Don't say both beans are registered by an application's own @AutoConfiguration. `dataprism.identity.resolver: pass-through` supplies a resolver with no code (DPAC about lines 128–135).
   - Update the trace-table rows. Re-render with render.sh twice and confirm the output is byte-stable.
4. **Suggestions to apply:**
   - (a) Registration from a `-Dloader.path` jar. A plain `@Configuration` there is never scanned (write-an-adapter.md about line 61). Say how a loader.path extension registers its resolver: via `AutoConfiguration.imports`, ordered so the application's bean wins over the quickstart's `@ConditionalOnMissingBean` default. Make sure the example code and the tutorial agree. If the example's `ExampleIdentityResolverConfiguration` is a plain `@Configuration`, either explain that it's for an application's own scanned package, or show the auto-configuration route. Pick whichever is true to the code and tested.
   - (b) Add a test that `expand` on an unknown canonical id returns an empty list. That is the fail-closed path that actually runs. The tutorial should say what happens at runtime in that case, traced to code.
   - (c) extension-points.mmd line 10: tie the `@ConditionalOnMissingBean` edge to the quickstart's default resolver, not to the MappedIdentityResolver example.

Keep green:
- mvn verify, with QuickstartSmokeIT and the identity tests, plus the non-vacuity check;
- snippets that match their sources byte for byte, and check_snippet_markers.py;
- re-run every tutorial command literally and quote the output again if anything changed;
- write-an-adapter.md changed only in the IdentityResolver sentence;
- render.sh byte-stable, and check_diagrams.py;
- `DP_REQUIRE_NO_STUBS=true` passes;
- the strict build, every check_*.py, lychee and actionlint;
- the Owns scope, checked with three dots.

## Attempt 2 — failed

Tester: PASS. Reviewer: CHANGES, on three remaining accuracy defects. Everything else from attempt 1 is fixed and verified:
- `resolve` has no production caller; only `expand` runs;
- the canonical id's roles are quoted exactly;
- the diagram's "either" label is correct;
- the unknown-id `expand` test is in place;
- the scope is clean.

Rebase onto LOCAL `site-polish` and always diff with three dots. Then fix:

1. **The unknown-id outcome is wrong** (custom-identity-resolver.md about lines 139–144). The page promises "a visible, empty answer, not an error". In the code:
   - an empty `expand` makes SourceFanOut (about lines 80–82) skip every source, so `merged` stays null;
   - DefaultContextOrchestrator (about lines 193–195) then throws `PrivacyRefusedException("NO_SOURCE_DATA")`;
   - GetEntityContextTool's `catch (PrivacyRefusedException refused)` returns `error("refused: NO_SOURCE_DATA at …")`, with no ContextResponse and no `sources`.

   State that exactly, and quote the error shape from the code. Also fix the "visible gap" wording (about lines 126–128). A single source returning NO_DATA is visible in `sources` only if some other source answers. If every source returns nothing, the call is again a NO_SOURCE_DATA refusal (DCO about line 193).
2. **Registration from a `-Dloader.path` jar has no ordering** (about lines 180–185, and the ExampleIdentityResolverConfiguration Javadoc). The failure: a reader adds an unconditional `@AutoConfiguration` IdentityResolver to the imports, Spring Boot orders auto-configurations by class name, and the quickstart's `@ConditionalOnMissingBean` default may register first. That gives two IdentityResolver beans, and startup fails (DPAC about line 446). Give correct guidance, e.g. `@AutoConfiguration(before = <the class that supplies the default>)`. If you recommend an approach, prove it with a test: one where the reader's resolver wins under auto-configuration ordering, not only under plain `@Configuration`. Keep the test in the owned identity test package. If a meaningful test is not possible within Owns, say so plainly in the tutorial and in your report, and don't claim a guarantee. Fix the Javadoc's reference to an "ordering guarantee" in write-an-adapter.md; that guarantee isn't there.
3. **The overview intro overclaims about adapters** (index.md about lines 48–49 and the alt text). "a DataSourceAdapter is always a bean an application's own @AutoConfiguration registers" is false. A YAML-configured JSON source gets its adapter from ConfiguredJsonSourcesInitializer in data-prism-connectors-rest, with no application code, and an ordinary scanned app can use a plain @Configuration. Reword both the intro and the alt text to match the code.
4. **Suggestion to apply:** extension-points.mmd line 6. The adapter check is `validateIntegrations` (DPAC about line 247 → DataPrismContractValidator about lines 44–45), not the preflight, and it is skipped in fixture STDIO mode. Label it along the lines of "preflight and contract validation refuse startup when either bean is missing". Check the wording against the code, including the STDIO skip, and update the README trace rows. Re-render twice to confirm byte-stability.

Keep green: the full attempt-1 keep-green list. The tester verified it all in attempt 2.

## Attempt 3 — failed

Tester: PASS. The ordering test is non-vacuous. A scratch orchestrator test confirmed the NO_SOURCE_DATA refusal end to end. The example auto-configuration is not registered.

Reviewer: CHANGES, for one defect. Everything else from attempt 2 is met.

This is the **final bounded round**. Fix exactly the items below and nothing further. Rebase onto LOCAL `site-polish`, and diff with three dots.

1. **The ordering guidance is specific to the quickstart** (custom-identity-resolver.md: "The fix is an explicit `@AutoConfiguration(before = QuickstartExtensionAutoConfiguration.class)`" and the following "Either way … pass-through" paragraph).

   How it fails: a reader's `org.acme.AcmeIdentityAutoConfiguration` follows the recipe with `dataprism.identity.resolver: pass-through` set. `io.github…DataPrismAutoConfiguration` sorts first, with no ordering, so `IdentityResolverSelection` (DPAC about lines 128–135) registers the pass-through bean. The reader's unconditional bean then adds a second one, and startup fails at DPAC about line 446.

   State the general rule: order your auto-configuration before whichever auto-configuration supplies the `@ConditionalOnMissingBean` default in your deployment. That is `QuickstartExtensionAutoConfiguration` for the quickstart, and `DataPrismAutoConfiguration` when `dataprism.identity.resolver: pass-through` is set. Mention `beforeName` for when the class isn't a compile dependency. Drop "behaves exactly as described above" for the pass-through case, or make it accurate. A new test is not required. If you make a claim about `DataPrismAutoConfiguration` ordering, trace it to the code and don't overstate it.
2. **Precision notes** (small wording fixes, all required):
   - (a) extension-points.mmd (about line 7), plus the index.md intro and alt text: qualify "refuse startup when either bean is missing". The adapter check is skipped in fixture STDIO mode (DataPrismContractValidator about lines 42–43). A short "(outside fixture STDIO mode)" or equivalent is enough. Re-render twice to confirm byte-stability.
   - (b) custom-identity-resolver.md, "exactly `refused: NO_SOURCE_DATA at CUSTOMER`": the path is the client-supplied entityType (DCO about line 194). Say so, e.g. "…at CUSTOMER, for a request with entityType CUSTOMER".
   - (c) IdentityResolverOrderingTest.java (about lines 69–70): the comment credits argument order for the quickstart class coming first. The real cause is AutoConfigurations' name sort, so fix the comment. Optionally, assert that the failed context's cause is NoUniqueBeanDefinitionException.

Keep green: everything on the attempt-3 tester's list.
