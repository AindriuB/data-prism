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
