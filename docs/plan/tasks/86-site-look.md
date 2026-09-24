# 86 — Give the docs site a restrained identity: mark, palette, landing page

**Repo:** `.`
**Wave:** 1 (spec section A)
**Depends on:** 85
**Base branch:** the LOCAL `site-polish` branch, after 85 has merged into it.
`wt-new.sh` bases new worktrees on `main`, so right after it, before any
edit, run `git -C <worktree> reset --hard site-polish`. The branch merges
back into `site-polish`. This task file is committed on `site-polish`: read
it from `/srv/dev/projects/data-prism/docs/plan/tasks/86-site-look.md`.
**Owns:**
- mkdocs.yml *(the `theme:` block and a new top-level `extra_css:` key only; wave 1's only `mkdocs.yml` editor)*
- docs/index.md
- docs/stylesheets/** *(new)*
- docs/assets/logo.svg *(new; a byte-identical copy of the supplied `mark-dark.svg`)*
- docs/assets/favicon.svg *(new; derived by script)*
- docs/assets/favicon.png *(new; 32×32, rendered by script from `favicon.svg`)*
- docs-site/logo/** *(new: `supplied/` with the owner's files unchanged, plus `make_favicon.*`, its pinned requirements and a `README.md`)*
- docs-site/overrides/main.html *(one added `<link rel="icon" type="image/svg+xml" …>` line inside the existing `extrahead` block only)*
- docs-site/hooks/check_contrast.py *(new)*

## Goal

The site should read as a serious infrastructure tool, with its own identity
and no marketing gloss. It gets the owner-supplied prism mark as its header
logo, a scripted favicon derived from that mark, a slate or deep-indigo palette with one accent in both schemes at
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
- **The logo is supplied, not drawn** (owner decision, 2026-09-24).
  - The source of record is `/srv/dev/scratch/data-prism-logo/`:
    - `mark-light.svg` and `mark-dark.svg`
    - `wordmark-light.svg` and `wordmark-dark.svg`
    - PNG exports of each
    - `favicon-{light,dark}-{16,32}.png`
    - `preview.svg` and `preview.png`
    - a `README.md` describing the geometry
  - The geometry: 64×64 `viewBox` and six paths inside `<g transform="translate(2.56 2.56) scale(0.92)">`.
    - A side-face fill, `M24 14 34 8 48 42 38 48Z`, at `fill-opacity="0.16"`.
    - A prism outline, `M10 48 24 14 34 8 48 42 38 48Z M24 14 38 48`, `stroke-width="3"`.
    - An incoming ray, `M4 32H16.6`.
    - Three outgoing bars in indigo `#6366F1` at 100%, 85% and 70% opacity.
  - Strokes are slate: `#1E293B` in `mark-light.svg`, `#E2E8F0` in `mark-dark.svg`.
  - On 2026-09-24, the planner scanned every supplied SVG for `<script`, `<foreignObject`, `<image`, `href=`, `@import`, `url(`, `on…=` handlers and `http(s)://`. The only hit was `http://www.w3.org/2000/svg`.
- **Header logo.** Material's header background is `--md-primary-fg-color`, and it is dark slate in **both** schemes here. So `theme.logo` is `mark-dark.svg` (light strokes), copied byte-for-byte to `docs/assets/logo.svg`. Do not alter the mark's geometry or colours. Contrast is checked against the chosen header colour. The supplied indigo `#6366F1` measures about 3.3:1 against `#1E293B`, so the header colour must be at least that dark.
- **Wordmarks are not used on the site.** The theme already renders "Data Prism" as live text beside the logo.
- **Favicon.** `docs-site/logo/make_favicon.*` derives exactly one SVG, `docs/assets/favicon.svg`, from the supplied geometry, simplified for tab size:
  - drop the incoming ray;
  - replace the three thin outgoing bars with **two** thicker `#6366F1` bars;
  - keep the prism outline and side face (path data and group transform verbatim);
  - switch strokes between `#1E293B` (default) and `#E2E8F0` with an embedded `<style>` containing `@media (prefers-color-scheme: dark)`.

  It then renders a 32×32 PNG fallback, `docs/assets/favicon.png`, from that SVG with the default (light-surface) styles. Any rasteriser is pinned: an exact-version `requirements.txt`, or a Docker image pinned by digest. `docs-site/social-card/make_card.py`, with its pinned `requirements.txt` and README, is the pattern to follow.
- **Favicon wiring.**
  - Material 9.7.7's `base.html:39` emits one `<link rel="icon" href="{{ config.theme.favicon | url }}">` with no `type`. The `extrahead` block (`base.html:83`), which `docs-site/overrides/main.html` already overrides, comes later.
  - So set `theme.favicon: assets/favicon.png`, which gives the PNG link first, and add one `<link rel="icon" type="image/svg+xml" href="{{ 'assets/favicon.svg' | url }}">` line inside `main.html`'s existing `extrahead` block, giving the SVG link last. This is the usual PNG-then-SVG order.
  - Leave the rest of `main.html` (OG/Twitter/JSON-LD) alone.
- Build recipe: see task 85's Context (Docker `python:3.12`, then every `docs-site/hooks/check_*.py`, then lychee exactly as `pages.yml` does).

## Acceptance

- [ ] **Supplied files are committed unchanged.** `diff -r /srv/dev/scratch/data-prism-logo docs-site/logo/supplied` is empty, including the supplied `README.md`. `docs-site/logo/README.md` records three things: the files' provenance (owner-supplied, 2026-09-24), that they must not be edited, and how to regenerate the favicon.
- [ ] **Header logo is the supplied mark, unaltered.**
  - `cmp docs/assets/logo.svg docs-site/logo/supplied/mark-dark.svg` succeeds.
  - `theme.logo` is `assets/logo.svg`.
  - `site/assets/logo.svg` exists and is byte-identical.
- [ ] **No wordmark on the site.** `grep -rn 'wordmark' mkdocs.yml docs/ docs-site/overrides/` is empty, and `find site -name 'wordmark*'` is empty.
- [ ] **Favicon derivation.** `docs-site/logo/make_favicon.*` exists, with pinned dependencies and README commands.
  - Running it twice from a clean environment leaves `git status --porcelain docs/assets/` empty.
  - `docs/assets/favicon.svg`:
    - contains the supplied prism-outline and side-face `d` strings verbatim (`grep -F`);
    - does not contain `M4 32H16.6`;
    - contains exactly two `#6366F1` bar paths, each thicker than the supplied 3-unit bars (the report states their geometry);
    - contains one `<style>` with `@media (prefers-color-scheme: dark)` that switches strokes from `#1E293B` to `#E2E8F0`.

    Any other departure from the supplied geometry is reported as a proposal, not shipped.
  - `docs/assets/favicon.png` is 32×32, as reported by `python3 -c "import struct;d=open('docs/assets/favicon.png','rb').read(24);print(struct.unpack('>II',d[16:24]))"`.
- [ ] **Favicon viewed at size.**
  - The tester views `favicon.svg` rendered at 16 px and 32 px, on a light and a dark background, and `favicon.png` at 32 px, and reports whether the prism and two bars are distinguishable.
  - The report attaches the rasters used (paths), next to the supplied `favicon-{light,dark}-{16,32}.png` for comparison.
- [ ] **Favicon wiring.**
  - `theme.favicon` is `assets/favicon.png`.
  - Built `site/index.html` and one inner page each contain exactly two `rel="icon"` links: the PNG first, then `type="image/svg+xml"` `favicon.svg`.
  - `git diff site-polish -- docs-site/overrides/main.html` adds exactly one line, and removes none.
- [ ] **SVGs carry no scripts or external references.** For `docs/assets/logo.svg`, `docs/assets/favicon.svg` and every `docs-site/logo/**/*.svg`:
  - `grep -nE '<script|<foreignObject|<image|href=|@import|url\(|\son[a-z]+=' <file>` is empty;
  - `grep -oE 'https?://[^" ]+' <file> | sort -u` prints only `http://www.w3.org/2000/svg`.

  Planting `<script>alert(1)</script>` or `<image href="https://example.com/x.png"/>` in a scratch copy of `favicon.svg` makes that command line report the hit. The report quotes it.
- [ ] `theme.font: false` is unchanged, and the scheme toggle still renders in both schemes.
- [ ] **Palette.** A dark-slate header, meaning `--md-primary-fg-color` is dark slate in both `default` and `slate`, so the light-stroke mark works in both. There is one indigo accent.
- [ ] `docs-site/hooks/check_contrast.py` checks WCAG contrast ratios, in both schemes. It reads the colour values from `docs/stylesheets/extra.css`, and from Material's own defaults where a value is not overridden, citing the Material CSS file and variable in a comment. Then:
  - It computes body text on background, links on background, and header text on primary, in both schemes. It fails unless each is at least 4.5:1.
  - Logo on the header, per scheme (WCAG 1.4.11, non-text):
    - the supplied mark's `#E2E8F0` stroke against the header colour must be at least 3:1;
    - the fully opaque `#6366F1` bar against the header colour must be at least 3:1;
    - it prints the 85% and 70% bars' blended ratios for information, without failing on them, since the owner fixed that geometry.
  - It prints each ratio.
  - Planting a failing colour in a scratch copy of `extra.css` makes it fail, naming the pair and ratio. The report quotes the failure.
- [ ] `docs/stylesheets/extra.css` styles only tables, code blocks, cards and the palette variables.
  - `grep -nE '@import|url\(|@font-face|@keyframes|animation|transition' docs/stylesheets/extra.css` is empty.
  - `mkdocs.yml` has no `extra_javascript`.
  - `git diff site-polish -- docs-site/overrides` is the single favicon line above.
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
- Any other change to `docs-site/overrides/main.html`, the social card, JSON-LD, `docs-site/page-meta.yml`.
- Redrawing, recolouring or re-exporting the supplied mark or wordmarks, or using the supplied favicon PNGs as the site favicon (they are comparison references only).
- Rewording README's site-intro block (it is included, not edited here).
- Diagrams on the landing page (none are planned: 88 embeds diagrams in existing docs), and any icon/emoji extension.
- Changing any claim on the pages the cards link to.
