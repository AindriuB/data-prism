# 85 — Lay the shared site plumbing every later site task builds on

**Repo:** `.`
**Wave:** 0 (enables spec waves 1 and 2)
**Depends on:** none
**Base branch:** the LOCAL `site-polish` branch (cut from `main` at fc5cc58,
not pushed). `wt-new.sh` bases new worktrees on `main`, so right after it,
before any edit, run `git -C <worktree> reset --hard site-polish`. The branch
merges back into `site-polish`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/85-site-plumbing.md`.
**Owns:**
- mkdocs.yml *(everything except the `theme:` block and a top-level `extra_css:` key — those are left for task 86)*
- docs-site/requirements.txt
- docs-site/hooks/check_site.py
- docs-site/hooks/changelog.py *(new; a no-op stub that task 87 replaces)*
- .github/workflows/pages.yml
- CONTRIBUTING.md *(the "## Docs site" section only)*
- docs/developer-guide/index.md *(new; stub that task 89 replaces)*
- docs/developer-guide/write-an-adapter.md *(new; stub that task 89 replaces)*
- docs/developer-guide/custom-identity-resolver.md *(new; stub that task 90 replaces)*

## Goal

The spec's four wave-1 tasks (look, changelog, diagrams embedded in
context, developer guide) all need `mkdocs.yml`, the guard scripts and the CI
workflow, so they would collide in those files. This task makes every shared
change up front: markdown extensions, nav and llmstxt slots with stub pages,
the changelog hook registration, a way to run more guard modules without
editing `check_site.py` or `pages.yml`, and a third-party-script guard that
can actually fail. After it lands, no wave-1 task except 86 (which alone edits
the `theme:` block) touches `mkdocs.yml`, and none touches `check_site.py` or
`pages.yml`.

## Context

- `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md` is binding.
  Read "Facts the planner must respect", sections B, C and D, and "Acceptance
  themes".
- `mkdocs.yml`:
  - Extensions today are `admonition`, `pymdownx.details`, `pymdownx.superfences`, `tables`.
  - Nav: seven top-level sections, with a comment saying so.
  - `hooks:` lists only `docs-site/hooks/site.py`.
  - `plugins.llmstxt.sections` must list exactly the nav pages. `check_site.py:check_llmstxt_sections_match_nav` fails otherwise.
  - `validation.nav.omitted_files` and `not_found` are `warn`, and `--strict` makes them failures. So a page on disk but not in the nav fails the build, and so does a nav entry with no file. That is why the nav slots need real stub files.
- **`check_site.py:check_llmstxt_sections_match_nav` parses `mkdocs.yml` with `yaml.safe_load`.** Any `!!python/name:` tag breaks that guard. That rules out `pymdownx.emoji` with Material's icon index, and any other extension configured through Python tags. Do not add one.
- `docs-site/hooks/site.py` `on_page_markdown` rewrites only inline `](…)` links. Hooks are appended after `plugins:`, so a hook's `on_page_markdown` sees the markdown after `include-markdown` has expanded it. Task 87 depends on that ordering.
- **Pages (all new, with front matter):** `docs/faq.md` shows the shape. Stubs need front matter with a `title` and a unique `description` of at most 155 characters. `site.py:_validate_page_meta` enforces that. The body is one sentence saying the page is being written, and it claims nothing about the product.
- **Snippets (tasks 89 and 90):**
  - Tutorial snippets come from marked regions such as `// --8<-- [start:name]` in:
    - `data-prism-quickstart-extension/` (Java sources and `pom.xml`)
    - `docker/server/application.yaml`, `docker/server/Dockerfile` and `docker/distribution/Dockerfile` (the `dataprism.sources.*` config and the `-Dloader.path` / `LOADER_PATH` launch)
  - `pymdownx.snippets` resolves `base_path` against the working directory (the repo root in CI and in the recipe below).
  - Restricting `base_path` to those two roots keeps excluded internal docs (`docs/plan/**`, `pack.md` and the rest) from being snippeted onto a published page.
  - `docs/plan/specs/2026-09-24-…md` contains a literal `--8<--`, but it is excluded from the build.
- **Third-party scripts: an owner-visible decision** (planner resolution, 2026-09-24, surfaced to the owner by the coordinator). The spec's acceptance theme says `grep -rE 'fonts.googleapis|googletagmanager|google-analytics|gtag|unpkg|jsdelivr|cdnjs' site/` must be empty. It cannot be empty literally with the pinned Material 9.7.7, for the reason below. It is satisfied as follows:
  - allow exactly two strings, `https://unpkg.com/mermaid@11/dist/mermaid.min.js` and `https://unpkg.com/resize-observer-polyfill`, and only inside Material's own `assets/javascripts/bundle.*.min.js` and its `.map`;
  - fail on any other hit of that pattern, in any file in `site/`;
  - fail on any built HTML element with `class="mermaid"`, which is the only thing that makes Material fetch the mermaid URL.

  The report must restate this decision in one line, so the owner sees it at review. If the owner rejects it, the fallback is a separate decision, not part of this task: for example, patching or replacing Material's bundle.
- **Why (verified 2026-09-24):**
  - Material 9.7.7's own `assets/javascripts/bundle.<hash>.min.js` (and its `.map`) contains the strings `https://unpkg.com/mermaid@11/dist/mermaid.min.js` and `https://unpkg.com/resize-observer-polyfill`.
  - So the spec's literal `grep -rE '…|unpkg|jsdelivr|cdnjs' site/` is non-empty on today's site, before any change.
  - Material only fetches the mermaid URL when a page contains a `.mermaid` element, which only appears if superfences gets a mermaid `custom_fences` entry.
  - The guard must therefore:
    - allow exactly those known strings, in exactly those bundle files;
    - fail on any other hit anywhere in `site/`;
    - fail if any built HTML contains `class="mermaid"`;
    - fail on any `<script src>`, `<link href>`, CSS `@import` or `url(` that points to an absolute non-`site_url` origin in built HTML or CSS.
- Existing guard: `check_site.py:check_no_fonts_or_analytics` (pattern `fonts.googleapis|fonts.gstatic|googletagmanager|google-analytics`). `pages.yml` repeats it as a grep step, and runs `check_site.py` as one step.
- **Build recipe.** The host `python3` has no `pip`, so build in Docker:
  `docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD:/w" -w /w python:3.12 sh -c 'python -m venv /tmp/v && /tmp/v/bin/pip -q install -r docs-site/requirements.txt && /tmp/v/bin/mkdocs build --strict && for f in docs-site/hooks/check_*.py; do /tmp/v/bin/python "$f" || exit 1; done'`
  Then run lychee exactly as `pages.yml` does, and actionlint with `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest`.

## Acceptance

- [ ] `mkdocs.yml` `markdown_extensions` adds `attr_list`, `md_in_html` and `pymdownx.snippets`, and keeps the existing four. The snippets config sets:
  - `check_paths: true`
  - `base_path: [data-prism-quickstart-extension, docker]`
  - `restrict_base_path: true`
  - `dedent_subsections: true`

  No superfences `custom_fences` entry for mermaid exists.
- [ ] `python3 -c "import yaml; yaml.safe_load(open('mkdocs.yml'))"` succeeds, so there are no Python tags.
- [ ] `docs-site/requirements.txt` pins `pymdown-extensions` to an exact version, the one the existing pins resolve today. The other four pins are unchanged.
- [ ] Nav:
  - A new top-level "Developer guide" section, placed before "Reference", lists `developer-guide/index.md`, `developer-guide/write-an-adapter.md` and `developer-guide/custom-identity-resolver.md`, in that order.
  - `llmstxt.sections` gains the same three pages under a matching "Developer guide" section.
  - No other nav or llmstxt entry is added. There is no `learn.md` or diagrams landing page. The spec's Diagrams row (owner decision, 2026-09-24) rules one out.
  - The "exactly seven sections" comment is updated to the real count.
- [ ] `hooks:` lists `docs-site/hooks/site.py` and then `docs-site/hooks/changelog.py`. `changelog.py` defines `on_page_markdown` returning its input unchanged, and has a docstring naming task 87 as its owner.
- [ ] The three stub pages each have front matter with a unique description of at most 155 characters. `grep -rniE 'anonymi|compliant|tamper-proof|guarantee' docs/developer-guide/` is empty. `test ! -e docs/learn.md`.
- [ ] Snippets proven live and non-vacuous in a scratch copy:
  - Appending `--8<-- "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"` to a stub builds, and the built page contains `QuickstartExtensionAutoConfiguration`.
  - Appending `--8<-- "src/main/java/nope.java"` makes `mkdocs build --strict` fail.
  - Appending `--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/CustomerModel.java:nosuchsection"` also fails.
  - Appending `--8<-- "../docs/pack.md"` fails, or includes nothing (state which).

  The report quotes the failing output of each case. Nothing from the scratch copy is committed.
- [ ] `check_site.py`'s third-party check behaves as described in Context:
  - pattern `fonts\.googleapis|fonts\.gstatic|googletagmanager|google-analytics|gtag|unpkg|jsdelivr|cdnjs`
  - the exact two-string allowlist, confined to `assets/javascripts/bundle.*.min.js(.map)`
  - no `class="mermaid"`
  - no off-origin `<script src>` / `<link href>` / `@import` / `url(`

  Each planted fault fails the check, naming the file. Run against a scratch copy of `site/`:
  1. A third `unpkg.com` string appended to the bundle.
  2. `<script src="https://cdn.jsdelivr.net/x.js"></script>` added to a page.
  3. `<pre class="mermaid">` added to a page.
  4. `@import url(https://cdnjs.cloudflare.com/x.css);` added to a CSS file.

  The report quotes each failure. The check passes on the real build.
- [ ] `pages.yml`:
  - The single `check_site.py` step becomes a step that runs every `docs-site/hooks/check_*.py` in sorted order. It fails on the first failure, and also fails if no file matches.
  - The fonts/analytics grep step is widened to the same pattern. It excludes only the Material bundle files and asserts the bundle's matches are exactly the two allowed strings.
  - actionlint on `pages.yml` is clean.
- [ ] `CONTRIBUTING.md` "## Docs site" documents three things: the `check_*.py` convention; that tutorial snippets must come from `--8<--` markers under `base_path`, never hand-copied; and the Docker build recipe.
- [ ] Full recipe green:
  - `mkdocs build --strict`
  - every `check_*.py`
  - lychee `--offline` exactly as in `pages.yml`
  - actionlint
  - the widened grep step's script, run locally against `site/`
- [ ] `git diff --stat site-polish` lists only files under **Owns**.

## Out of scope

- The `theme:` block (logo, favicon, palette), `extra_css` and `docs/index.md`. These belong to task 86.
- Any real content in the stub pages (tasks 89, 90), any diagram (88), and any changelog transform (task 87).
- `docs-site/hooks/site.py`, `docs-site/page-meta.yml` and `docs-site/overrides/**`. Nothing in this plan needs them changed.
- Adding snippet markers to any Java, pom, YAML or Dockerfile (tasks 89, 90).
- Bumping the mkdocs or Material pins.

## Attempt 1 — failed

Tester: PASS. Reviewer: CHANGES. The third-party guard has holes, and there is one criterion I have added. Rebase onto LOCAL `site-polish` (not `origin/`), which now carries this section, and fix all of the following. Prove each fix with its own planted fault in a scratch copy of `site/`, and quote the failure in your report.

1. **Protocol-relative URLs pass** (`check_site.py` `_is_offsite`, about line 273). Anything starting with `/` counts as local, so `<script src="//plausible.io/js/x.js">` and `url(//fonts.bunny.net/x.woff2)` get through. Treat `//host` as off-site. Planted faults: both of those examples.
2. **The attribute match is too narrow** (`_ATTR_SRC_HREF_RE`, about line 266, and the scheme test, about line 277). Only double-quoted `src`/`href` values are matched, and schemes match case-sensitively. Match single-quoted, double-quoted and unquoted values, and match schemes case-insensitively. Planted faults: `<script src='https://cdn.jsdelivr.net/x.js'>` and `<script src="HTTPS://cdn.jsdelivr.net/x.js">`.
3. **Scheme-less references in the bundle pass** (`check_site.py` about lines 215 and 233, and the `pages.yml` grep step about line 117). Inside the Material bundle, only `https?://` tokens are compared with the allowlist. Compare every URL-like hit of the guard pattern in the bundle, including `//host/...` and bare `unpkg.com/...` or `cdn.jsdelivr.net/...`, against exactly the two allowed strings. Do this in both `check_site.py` and the `pages.yml` step. Planted faults: `"//cdn.jsdelivr.net/x.js"` and `"unpkg.com/other@1/x.js"` appended to the bundle. Each must fail in both places.
4. **The `.map` exemption is too broad.** Narrow it to the one known prose occurrence, matched as exactly as practical, and only in `bundle.*.min.js.map`. Any other pattern hit in a `.map` fails. Planted fault: a third unpkg URL appended to the `.map`.
5. **Stub guard (new criterion).** Add a check that fails when any built page contains the stub body "This page is being written.", but only when `pages.yml` runs for a pull request into `main` or a push to `main`. Pass the condition through an env var set in the workflow, e.g. `DP_REQUIRE_NO_STUBS: ${{ github.base_ref == 'main' || github.ref == 'refs/heads/main' }}`. Don't make the script guess from other variables. Locally and on wave branches it must pass, so wave builds keep working. Proof:
   - the real build fails with the variable set to `true`, naming the three stub pages;
   - it passes with the variable unset or `false`;
   - actionlint stays clean.
   Document the variable in the CONTRIBUTING "## Docs site" section in one sentence.
6. **Suggestions, not blocking:**
   - make the `class="mermaid"` match not fire on `language-mermaid` (a fenced code block showing Mermaid source is fine; only a rendered `mermaid` class is the runtime hook);
   - leave `img`/`srcset`/`iframe`/`fetch` out of scope, but add one comment line saying so.

The re-run must keep everything the tester verified in attempt 1 green:
- the strict build;
- every `check_*.py`;
- the four original planted faults;
- the snippets `check_paths` and `restrict_base_path` cases;
- lychee `--offline`;
- actionlint;
- the verbatim CONTRIBUTING Docker recipe;
- `git diff --stat site-polish` limited to **Owns**.

## Attempt 2 — failed

Tester: PASS. Reviewer: CHANGES. Items 1–6 from attempt 1 are closed for their stated forms, and the new `check_no_stub_pages.py` is accepted as in scope: it is a new `check_*.py` required by item 5 and collides with no other task. Two bypasses of the same kind remain. Rebase onto LOCAL `site-polish`, fix both, and prove each with the planted faults listed, in a scratch copy of `site/`, quoting the failure lines.

1. **Backslash URLs pass** (`check_site.py` around lines 313–329, and the `pages.yml` grep step if it has the same gap). Browsers treat `\` as `/` in http(s) URLs and strip tabs and newlines, so all three of these load from off-origin hosts, yet `_is_offsite` returns False for each:
   - `<script src="/\evil.example.com/x.js">`
   - `<script src="https:\\evil.example.com/x.js">`
   - CSS `url('/\fonts.bunny.net/x.woff2')`

   Before the off-site test, normalise the value: turn `\` into `/` and remove ASCII tab, CR and LF. Planted faults: the three examples above, plus one with an embedded tab (`src="/<TAB>/evil.example.com/x.js"`).
2. **The mermaid class matches only in double quotes** (`MERMAID_CLASS_RE`, about line 278). `<pre class='mermaid'>` and `<pre class=mermaid>` pass, yet each is a `.mermaid` element that makes Material fetch mermaid from unpkg. Accept single-quoted and unquoted class values, as was done for src/href, and keep `language-mermaid` passing. Planted faults: both forms fail, and `class='highlight language-mermaid'` still passes.
3. **Suggestion, not blocking:** the greedy `[^>]*\ssrc` takes the last ` src` in a tag, so a decoy inside another attribute's value hides the real one. Fix it only if the fix is cheap: e.g. iterate over every `src`/`href` attribute in the tag rather than just the last.

Keep green everything the attempt-2 tester verified:
- the strict build;
- every `check_*.py`, including the stub guard's true, false and unset behaviour;
- all earlier planted faults: the original four and attempt-1 items 1–4;
- the snippets cases;
- lychee `--offline`;
- actionlint;
- the verbatim CONTRIBUTING recipe;
- `git diff --stat site-polish`, limited to Owns plus `check_no_stub_pages.py`.

## Attempt 3 — failed

Tester: PASS. Reviewer: CHANGES. All attempt-2 items are closed. The following also pass:
- `SRC=`, spaces around `=`, and `&#47;`, `&#x2F;` and `&sol;` slashes;
- leading whitespace and `URL(`;
- no false positives on `<a href>`;
- the `url()` / `@import` labels are correct.

This is the **final bounded round**. The guard protects our own generated site against an accidental CDN script, font or tracker, not against a deliberate attacker who can already edit the repo. Fix exactly these two items, each proven with the planted faults listed, and nothing further in this family. Rebase onto LOCAL `site-polish` first.

1. **Minified `@import` with no space is missed** (`_AT_IMPORT_RE`, check_site.py about line 337). `@import"https://fonts.bunny.net/x.css";` and `@import'//fonts.bunny.net/x.css';` are valid CSS, browsers load them, and minifiers emit them. Change `@import\s+` to `@import\s*`. Also check that the pages.yml grep step catches the third-party host in this form (it should, as it's a host grep). Planted faults: both forms, appended to a scratch copy of `main.*.min.css`.
2. **Attribute separators other than whitespace** (check_site.py about lines 279 and 314). Attributes are only found after `\s`. HTML parsers also treat `/` and a closing quote as separators, and `class="mermaid"` after a quoted attribute is a small regression from attempt 2. Allow `[\s/"']` (or equivalent) before the attribute name for `src`, `href` and `class`. Planted faults:
   - `<script/src="https://evil.example.com/x.js">`
   - `<script type="module"src="https://evil.example.com/x.js">`
   - `<pre id="x"class="mermaid">`
   - negative case: `class="highlight language-mermaid"` still passes.

**Out of scope, deliberately not fixed:** C0 control characters other than whitespace inside attribute values, CSS escapes such as `\2f` or `h\ttps` inside `url()`, and quoted `url()` strings that contain the other quote. These need deliberate obfuscation. Add one comment line in check_site.py naming them as out of scope, next to the existing img/srcset/iframe/fetch comment.

Keep green the full list from the attempt-2 section plus the attempt-2 planted faults.
