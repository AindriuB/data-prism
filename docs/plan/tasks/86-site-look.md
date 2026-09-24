# 86 — Give the docs site a restrained identity: mark, palette, landing page

**Repo:** `.`
**Wave:** 1 (spec section A)
**Depends on:** 85
**Base branch:** the LOCAL `site-polish` branch, after 85 has merged into it.
`wt-new.sh` bases new worktrees on `main`, so right after it, before any
edit, run `git -C <worktree> reset --hard site-polish`. The branch merges
back into `site-polish`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/86-site-look.md`.
**Owns:**
- mkdocs.yml *(the `theme:` block and a new top-level `extra_css:` key only; wave 1's only `mkdocs.yml` editor)*
- docs/index.md
- docs/stylesheets/** *(new)*
- docs/assets/logo.svg *(new)*
- docs/assets/favicon.* *(new)*
- docs-site/logo/** *(new)*
- docs-site/hooks/check_contrast.py *(new)*

## Goal

The site should read as a serious infrastructure tool, with its own identity
and no marketing gloss. It gets a reproducible SVG mark used as logo and
favicon, a slate or deep-indigo palette with one accent in both schemes at
WCAG AA contrast, a landing page with a one-line hero, two buttons and three
honest cards, and one small stylesheet. It gets no web fonts, no JavaScript
and no animation.

## Context

- `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md` section A and the Honesty row are binding. Also binding: `docs/plan/specs/2026-09-23-discoverability.md` for tagline T, description D and the honesty rules (line 238: each hit of `anonymi|compliant|tamper-proof|guarantee` is a negation or refers to another tool).
- **`mkdocs.yml` after 85:**
  - `theme.font: false` must stay.
  - The palette has two schemes (`default`, `slate`) with toggles and no `primary`/`accent`. Keep the toggle.
  - Material's supported way to set custom colours is `primary: custom` / `accent: custom`, plus CSS custom properties (`--md-primary-fg-color`, `--md-primary-bg-color`, `--md-accent-fg-color`, …) under `[data-md-color-scheme="default"]` / `[data-md-color-scheme="slate"]`. A named Material colour is also acceptable.
  - 85 has already enabled `attr_list` and `md_in_html`, so grid cards (`<div class="grid cards" markdown>`) and `{ .md-button }` work.
  - **No `pymdownx.emoji` or icon shortcodes.** 85 records why: `check_site.py` safe-loads `mkdocs.yml`. The cards are text-only.
- **`docs/index.md` today:**
  - front matter (`description` = T)
  - `{% include-markdown "../README.md" start="<!-- site-intro:start -->" end="<!-- site-intro:end -->" %}`
  - a "Where to go next" list

  The spec's order is the hero, then the cards, then the existing include, then "Where to go next". `check_site.py` requires exactly one JSON-LD block, on `index.html` only, from `docs-site/overrides/main.html`. Do not touch `main.html`.
- **The hero's two buttons:** Quickstart (`quickstart.md`) and Developer guide (`developer-guide/index.md`, a stub from 85 that task 89 fills in parallel; the link is valid either way).
- **Cards: text must come only from wording already verified on the target page.** Candidate sources:
  - *Pseudonymise per scope* → `docs/tools.md` "## Scope isolation" / "## The pseudonym collapse"
  - *Fail closed* → `docs/configuration.md`'s `dataprism.privacy` row as corrected by task 84: only the bundled profiles load, and both set `unclassified: FAIL_REQUEST`. Do not imply an operator can relax it, or that relaxation is refused at startup.
  - *Verifiable audit trail* → `docs/audit.md`, whose "## What this does and does not prove" section sets the limits. The chain is per writer boot. The verifier detects an edit or deletion inside a chain. It cannot detect tail truncation, deletion of a whole boot's records, or recomputation by someone with write access. The card must not say more than that.
  - Pseudonymisation is never called anonymisation.
- **Logo tooling:** `docs-site/social-card/make_card.py` (pinned Pillow in its own `requirements.txt`, and a README with regeneration steps) is the pattern to follow beside it. The mark is a prism or refracted-light motif, geometric, and legible at 16 px.
- Build recipe: see task 85's Context (Docker `python:3.12`, then every `docs-site/hooks/check_*.py`, then lychee exactly as `pages.yml` does).

## Acceptance

- [ ] `docs-site/logo/` holds a generator script, a pinned `requirements.txt` (if it needs any package) and a README. The script writes `docs/assets/logo.svg` and the favicon file or files.
  - Running it twice, from a clean venv built from its README's commands, leaves `git status --porcelain docs/assets/` empty.
  - The SVG has a `viewBox` and no `<text>`, `<image>`, `<script>`, `<foreignObject>` or external URL. `grep -oE 'https?://[^"]+' docs/assets/logo.svg` shows only `http://www.w3.org/…` namespace URIs.
  - The report includes (or attaches as a path) a 16 px and a 32 px raster of the favicon, for the reviewer to judge legibility.
- [ ] `theme.logo` and `theme.favicon` point at those files, and both are present in built `site/`. `theme.font: false` is unchanged, and the scheme toggle still renders in both schemes.
- [ ] The palette is a slate or deep-indigo primary plus one accent, set for both `default` and `slate`.
- [ ] `docs-site/hooks/check_contrast.py` checks WCAG contrast ratios, in both schemes. It reads the colour values from `docs/stylesheets/extra.css`, and from Material's own defaults where a value is not overridden, citing the Material CSS file and variable in a comment. Then:
  - It computes body text on background, links on background, and header text on primary, in both schemes.
  - It fails unless each ratio is at least 4.5:1.
  - It prints each ratio.
  - Planting a failing colour in a scratch copy of `extra.css` makes it fail, naming the pair and ratio. The report quotes the failure.
- [ ] `docs/stylesheets/extra.css` styles only tables, code blocks, cards and the palette variables.
  - `grep -nE '@import|url\(|@font-face|@keyframes|animation|transition' docs/stylesheets/extra.css` is empty.
  - `mkdocs.yml` has no `extra_javascript`.
  - `git diff site-polish -- docs-site/overrides` is empty.
- [ ] Landing page:
  - `docs/index.md` renders, in order: a one-line hero containing T verbatim, a Quickstart button and a Developer guide button (`.md-button`), then a three-card grid titled *Pseudonymise per scope*, *Fail closed* and *Verifiable audit trail*, then the unchanged README include, then "Where to go next".
  - Each card links to the doc that proves it.
  - The front-matter `description` is still exactly T, and the built `site/index.html` meta description equals T.
- [ ] For each card, the report lists every sentence next to the `file:line` it is taken from. `grep -niE 'anonymi|compliant|tamper-proof|tamper proof|guarantee|immutable|cannot be altered' docs/index.md` is empty, or every hit is a negation.
- [ ] Full recipe green:
  - `mkdocs build --strict`
  - every `docs-site/hooks/check_*.py`, including 85's third-party check
  - lychee `--offline` as in `pages.yml`
  - actionlint
  - `grep -rE 'fonts.googleapis|googletagmanager|google-analytics|gtag|unpkg|jsdelivr|cdnjs' site/ --exclude='bundle.*.min.js*'` empty
- [ ] `git diff site-polish -- mkdocs.yml` touches only the `theme:` block and `extra_css:`. `git diff --stat site-polish` lists only files under **Owns**.

## Out of scope

- Nav, `markdown_extensions`, `plugins`, `hooks` in `mkdocs.yml` (all set by 85).
- `docs-site/overrides/main.html`, the social card, JSON-LD, `docs-site/page-meta.yml`.
- Rewording README's site-intro block (it is included, not edited here).
- Diagrams on the landing page (none are planned: 88 embeds diagrams in existing docs), and any icon/emoji extension.
- Changing any claim on the pages the cards link to.
