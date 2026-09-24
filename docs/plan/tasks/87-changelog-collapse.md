# 87 — Collapse the changelog page per release, without editing CHANGELOG.md

**Repo:** `.`
**Wave:** 1 (spec section B)
**Depends on:** 85
**Base branch:** the LOCAL `site-polish` branch, after 85 has merged into it.
`wt-new.sh` bases new worktrees on `main`, so right after it, before any
edit, run `git -C <worktree> reset --hard site-polish`. The branch merges
back into `site-polish`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/87-changelog-collapse.md`.
**Owns:**
- docs-site/hooks/changelog.py *(replaces 85's no-op stub)*
- docs-site/hooks/check_changelog.py *(new)*
- docs/changelog.md
- .gitignore *(one added line for a build artefact, if the design needs it)*

## Goal

The changelog page is one long wall today: `[0.3.0]` alone is about 175 lines.
A build hook turns each release into a collapsible block. The newest release
starts open and the rest start closed, and every summary line gives the
version, the date and a short count. `CHANGELOG.md` stays the only source and
is not edited, so GitHub shows it unchanged. A new guard proves that no
release or link can go missing in the transformation.

## Context

- `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md` section B is binding.
- **`CHANGELOG.md`** (287 lines):
  - `## [Unreleased]` (line 8, empty today), `## [0.3.0] - 2026-09-23` (line 10), `[0.2.0]` (185), `[0.1.1]` (229) and `[0.1.0]` (254).
  - Each release has an optional intro paragraph and `###` subsections: `Added`, `Changed`, `Fixed`, `Behavioural change for API consumers`, `Not changed`, and `Not included in this release`.
  - Link-reference definitions (`[0.3.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.3.0` and the rest) sit at lines 284–287 and must stay at top level. They must not end up indented into a details block.
- `docs/changelog.md` is front matter plus `{% include-markdown "../CHANGELOG.md" %}`.
- **Hook plumbing from 85:**
  - `mkdocs.yml` `hooks:` already registers `docs-site/hooks/changelog.py` after `site.py`, as a no-op `on_page_markdown` stub.
  - Hooks run after plugins, so the hook sees the markdown after `include-markdown` has expanded it. Verify this rather than assume it.
  - Transform only when `page.file.src_uri == "changelog.md"`.
  - `pymdownx.details` is enabled: `???` starts closed, `???+` starts open, and the content is indented four spaces.
- **Guard plumbing from 85:** `pages.yml` and the local recipe run every `docs-site/hooks/check_*.py`, so `check_changelog.py` is picked up without editing `pages.yml`.
- **The spec's non-vacuity proof is "delete a release from a scratch copy of the rendered markdown, and the check must fail".** So the guard must be able to read the transformed markdown: either the hook writes it to a gitignored artefact (as `site.py` does with `docs-site/.manifest.json`, listed in `.gitignore`), or the guard accepts an explicit path to it. Either way, the guard also checks the built `site/changelog/index.html`.
- **Build recipe:** see task 85's Context (Docker `python:3.12`, then every `check_*.py`, then lychee as in `pages.yml`).

## Acceptance

- [ ] `git diff site-polish -- CHANGELOG.md` is empty.
- [ ] Built `site/changelog/index.html` has one `<details>` per release, in file order.
  - Only the first (newest) release has the `open` attribute: `[0.3.0]` today.
  - `[Unreleased]` is absent today, because it is empty.
  - In a scratch copy of `CHANGELOG.md` with a bullet under `[Unreleased]`, the Unreleased block appears first, open, and `[0.3.0]` is then closed. The report shows the resulting `<details>` open/closed pattern for both runs.
- [ ] Each summary shows the version, the release date and a count taken from the entry.
  - The report states the counting rule. For example: top-level list items per `###` subsection, rendered as "N added · N changed · N behavioural changes", omitting zero or absent subsections.
  - For every release, the tester recomputes each count independently with `awk`/`grep` over `CHANGELOG.md`, and it matches.
- [ ] Text survives the transform:
  - Every release's intro paragraph and `###` subsection text survives. A diff of the visible text of `site/changelog/index.html` against `CHANGELOG.md`'s own lines shows no dropped bullet. The report states the method.
  - Fenced code, nested lists and tables inside a release, if any, render as they do today.
- [ ] `docs-site/hooks/check_changelog.py` fails, naming what is missing, unless both hold for the rendered markdown and the built page:
  - every `## [x.y.z]` version in `CHANGELOG.md` appears;
  - every link-reference definition in `CHANGELOG.md` resolves: each `[x.y.z]` label renders as an `<a href>` to its defined URL, and no literal unresolved `[x.y.z]` bracket text remains in the HTML.
- [ ] Non-vacuity, with each failure quoted in the report:
  1. Deleting one release block from a scratch copy of the rendered markdown makes `check_changelog.py` fail, naming the version.
  2. Deleting one link-reference definition from a scratch copy of the rendered markdown, or a rendered HTML copy, fails, naming the label.
  3. A scratch hook variant that drops `[0.1.0]` fails.
- [ ] Only `changelog.md` is transformed. The built HTML of every other page is byte-identical to a build of `site-polish` without this task, apart from asset hashes: `diff -r` over the two `site/` trees, excluding `changelog/`, `sitemap.xml.gz` and `search/`, is empty or explained line by line.
- [ ] Full recipe green:
  - `mkdocs build --strict`
  - every `check_*.py`
  - lychee `--offline` as in `pages.yml`
  - actionlint
  - the third-party grep from 85's pages step
- [ ] `git diff --stat site-polish` lists only files under **Owns**.

## Out of scope

- Editing `CHANGELOG.md`, rewording any entry, or changing its GitHub view.
- `mkdocs.yml`, `check_site.py`, `pages.yml`, `site.py` (85 already did all the wiring this task needs).
- Changing the changelog's nav position or front-matter description.
- Any change to how other pages render.
