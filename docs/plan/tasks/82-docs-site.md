# 82 — Build and deploy the MkDocs Material docs site from the existing user docs

**Repo:** `.`
**Wave:** 2 (spec task T7)
**Depends on:** 76, 77, 78, 79
**Base branch:** the LOCAL `discoverability` branch, after tasks 76-79 have
merged into it (not pushed, not `main`). `wt-new.sh` bases new worktrees on
`main`, so right after it, before any edit, run
`git -C <worktree> reset --hard discoverability`, then confirm that
`README.md` contains `<!-- site-intro:start -->` and that
`docs/assets/social-card.png`, `docs/faq.md` and `docs/use-cases/` exist. The
branch merges back into `discoverability`. This task file is uncommitted:
read it from `/srv/dev/projects/data-prism/docs/plan/tasks/82-docs-site.md`.
**Precondition (owner action, not part of this task):** GitHub Pages must be
enabled with `build_type=workflow`
(`gh api -X POST repos/AindriuB/data-prism/pages -f build_type=workflow`)
before the branch carrying this task merges to `main`. Otherwise the first
deploy job fails. Enforce HTTPS after the first deploy.
**Owns:**
- mkdocs.yml *(new)*
- docs/index.md *(new)*
- docs/changelog.md *(new)*
- docs-site/requirements.txt *(new)*
- docs-site/hooks/**
- docs-site/overrides/**
- docs-site/page-meta.yml *(new)*
- .github/workflows/pages.yml *(new)*
- .gitignore
- CONTRIBUTING.md *(a new "Docs site" section only)*

## Goal

Publish `https://aindriub.github.io/data-prism/` from the existing
user-facing docs as the single source, without editing or moving any of
them. Every page carries a unique description, a canonical URL and a social
card. The site also serves `llms.txt` and `llms-full.txt`, and a sitemap
that contains only nav pages. The build fails on a broken link, a missing
description, or an internal doc that leaks onto the site.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — binding. Read
  "Decisions → Surface / Measurement", "Facts the planner must respect",
  "Canonical description", "T7", "Owner actions" and "Risks → Tooling".
- **Verify at execution time and record in the report:**
  - whether MkDocs 1.6.x and Material 9.x are in maintenance mode. Pin
    them anyway. Zensical reads `mkdocs.yml`, which is the later migration
    path, so avoid config that Zensical documents as unsupported where an
    equivalent exists.
  - the exact config keys of the pinned `mkdocs-llmstxt` release (e.g. how
    it takes the summary, sections and full output). If it cannot produce
    both files with summary D, generate them from the hook instead.
  - the pinned `mkdocs-include-markdown-plugin` syntax for `start`/`end`
    markers and its relative-URL rewriting.
  - the current major versions of `actions/setup-python`,
    `actions/configure-pages`, `actions/upload-pages-artifact` and
    `actions/deploy-pages`.
  - the lychee flags needed for an offline check of a built site.
- Doc paths are baked into code:
  `data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismConfigurationFailureAnalyzer.java:34`
  and its tests assert `docs/configuration.md` and `docs/quickstart.md`.
  Never move or rename a user doc.
- Existing docs get **no front matter**, because `PLAN.md` and other docs
  cite line numbers inside them. Their titles and descriptions live in
  `docs-site/page-meta.yml` and the hook applies them. New pages from tasks
  77 and 78, and this task's `index.md` and `changelog.md`, carry front
  matter.
- These existing user docs go on the site (each needs a `page-meta.yml`
  entry):
  - `quickstart.md`, `protect-your-own-api.md`, `configuration.md`,
    `tools.md`, `extending.md`, `audit.md`, `architecture.md`
  - `agents/README.md`, `agents/stdio.md`, `agents/remote-http.md`
- `exclude_docs` is exactly `plan/`, `adr/`, `pack.md`, `conventions.md`,
  `workflow.md`, `development-plan.md` and `design-review.md`. `pack.md` is
  the superseded original spec and describes tools that were never built.
- Links that leave `docs/` today, found by
  `grep -rhoE '\]\((\.\./)+[^)#]*' docs/*.md docs/agents/*.md`, include:
  - `../examples/…` from `docs/`
  - `../../examples/…` from `docs/agents/`
  The hook must resolve each relative link against the page's source path,
  and rewrite any target outside `docs_dir` or in an excluded path to
  `https://github.com/AindriuB/data-prism/blob/main/<path>` for files, or
  `…/tree/main/<path>` for directories. That covers the `../../examples/…`
  links from `docs/use-cases/` too.
- The README's intro between `<!-- site-intro:start -->` and
  `<!-- site-intro:end -->` (task 76) links `docs/quickstart.md`,
  `docs/agents/README.md` and `docs/protect-your-own-api.md`. These links
  must resolve once included into `docs/index.md`.
- The home page description must be at most 155 characters. D is 280, so use
  T (96) as the home page meta description. Use D as the JSON-LD
  `description` and the llms.txt summary.
- `robots.txt` does nothing on a project Pages site. Do not ship one. Sitemap
  submission is an owner action through Search Console and Bing.
- Style references for `pages.yml`:
  - `.github/workflows/build.yml`: top-level `permissions: contents: read`,
    commented steps, pinned major versions
  - `.github/workflows/publish-mcp.yml:33-41`: the name check this task must
    not break
- `actionlint`, `lychee` and `mkdocs` are not installed on this host. Use
  their Docker images (`rhysd/actionlint`, `lycheeverse/lychee`) and a venv
  from `docs-site/requirements.txt`.
- Waiting on processes: never wait with `pgrep -f <pattern>` in a shell whose
  own command line contains that pattern. Wait on a captured PID, or use
  `timeout`.

## Acceptance

- [ ] `docs-site/requirements.txt` pins every package with `==`:
      mkdocs 1.6.x, mkdocs-material 9.x, mkdocs-include-markdown-plugin and
      mkdocs-llmstxt.
- [ ] `mkdocs.yml` sets `docs_dir: docs`,
      `site_url: https://aindriub.github.io/data-prism/`, `theme.font: false`,
      `theme.custom_dir: docs-site/overrides`, `hooks: [docs-site/hooks/site.py]`,
      and the `exclude_docs` list above.
- [ ] `mkdocs.yml` sets every `validation.links.*` and `validation.nav.*`
      key to `warn`, and the build runs with `--strict`.
- [ ] `mkdocs.yml` has no `extra.analytics`.
- [ ] The nav has exactly these top-level sections, covering every published
      page:
      - Get started
      - Use cases
      - Connect an agent
      - Reference
      - FAQ
      - Comparison
      - Changelog
- [ ] `docs/index.md` includes `README.md` between the site-intro markers
      through include-markdown, and has front matter with `description` = T.
- [ ] `docs/changelog.md` includes `CHANGELOG.md`.
- [ ] In a fresh venv from the pinned requirements, `mkdocs build --strict`
      exits 0.
- [ ] No excluded path appears in `site/`. `find site -path '*/plan/*' -o -path '*/adr/*' -o -name 'pack*' -o -name 'conventions*' -o -name 'workflow*' -o -name 'development-plan*' -o -name 'design-review*'`
      is empty, and `site/sitemap.xml` contains none of those path segments.
- [ ] Every `<loc>` in `site/sitemap.xml` is a nav page. A script in
      `pages.yml` compares the sitemap URLs with the nav-derived URLs and
      fails on any difference.
- [ ] Every HTML page listed in the sitemap has exactly one non-empty
      `<meta name="description">` of at most 155 characters. No two pages
      share one.
- [ ] Every sitemap page has a `<link rel="canonical">` starting with
      `https://aindriub.github.io/data-prism/`.
- [ ] Every sitemap page has `og:image` and `twitter:image` pointing at
      `https://aindriub.github.io/data-prism/assets/social-card.png`,
      `twitter:card` = `summary_large_image`, and `og:title` and
      `og:description` matching the page's own title and description. The
      tester runs a check script over `site/`, and `pages.yml` runs the same
      check.
- [ ] Only `site/index.html` carries a `<script type="application/ld+json">`
      block. That block parses with `jq`, has `@type` `SoftwareSourceCode`,
      `description` = D, `codeRepository` = the GitHub URL,
      `programmingLanguage` Java and an Apache-2.0 `license` URL, and has no
      `version` or `softwareVersion` key.
- [ ] Non-vacuity, shown by the tester with real output:
      - Deleting any one entry from `docs-site/page-meta.yml` makes
        `mkdocs build --strict` fail, with a message naming the page.
      - Duplicating one description makes it fail.
      - A description over 155 characters makes it fail.
      - All three pass again after reverting.
- [ ] Every link in the built site that previously pointed outside `docs/`
      is an absolute `https://github.com/AindriuB/data-prism/(blob|tree)/main/…`
      URL, and the reviewer spot-checks three for a 200.
- [ ] `site/llms.txt` exists and its first line is `# Data Prism`. Its
      summary blockquote is D: `grep -F "$D" site/llms.txt` matches.
- [ ] `site/llms-full.txt` exists and is non-empty.
- [ ] Neither llms file contains text from an excluded page (e.g.
      `grep -c 'pack.md' site/llms*.txt` is 0).
- [ ] `grep -rEl 'fonts.googleapis|fonts.gstatic|googletagmanager|google-analytics' site/`
      is empty.
- [ ] There is no `robots.txt` in `docs/` or in `site/`.
- [ ] Lychee in offline mode over `site/` exits 0.
- [ ] Actionlint on `.github/workflows/pages.yml` exits 0.
- [ ] `.github/workflows/pages.yml`:
      - triggers on `pull_request` and on `push` to `main`, path-filtered to
        `docs/**`, `docs-site/**`, `mkdocs.yml`, `README.md`, `CHANGELOG.md`
        and the workflow itself
      - the build job runs `mkdocs build --strict`, then lychee offline, then
        the excluded-path, sitemap, meta and llms guards above
      - the deploy job runs only when `github.event_name == 'push' && github.ref == 'refs/heads/main'`,
        with `permissions: pages: write, id-token: write`,
        `environment: github-pages` and `concurrency: pages`
      - top-level permissions are `contents: read`
      - the task changes no branch-protection setting, and the job is not a
        required check
- [ ] `.gitignore` ignores `site/`.
- [ ] `CONTRIBUTING.md` gains a "Docs site" section giving the venv, install,
      `mkdocs serve` and `mkdocs build --strict` commands. It also gives the
      rules:
      - existing docs get no front matter; add a `page-meta.yml` entry instead
      - new pages carry front matter
      - the excluded list
- [ ] `git diff --name-only discoverability -- docs/` lists only
      `docs/index.md` and `docs/changelog.md`. No existing `docs/**/*.md` is
      modified, moved or renamed.
- [ ] The three name extractions from `publish-mcp.yml:35-37` still print
      `io.github.AindriuB/data-prism` three times.
- [ ] Honesty checks on `docs/index.md`, `docs/changelog.md`,
      `docs-site/page-meta.yml`, the overrides and the JSON-LD:
      - Every hit of `grep -rniE 'anonymi|compliant|tamper-proof|guarantee'`
        in these files is a negation or refers to another tool. The reviewer
        lists each hit with a verdict.
      - `grep -rniE 'GDPR-compliant|tamper-proof|API gateway|Spring AI|comprehensive|robust|seamless'`
        on `docs-site/page-meta.yml` and `docs-site/overrides/` is empty.
      - Every `page-meta.yml` description is traced by the reviewer to its
        page's content.
- [ ] Deferred, checked when the `discoverability` branch is pushed and its
      PR to `main` is open (not by the implementer): the PR's `pages` build
      job is green. Record it as a checklist item in the report.

## Out of scope

- Editing, moving, renaming or adding front matter to any existing doc. That
  includes fixing a broken anchor inside one: report it, and make the build
  pass without touching the doc, or stop and report.
- README and `server.json` changes, including `websiteUrl` (task 83).
- Enabling Pages, enforcing HTTPS, Search Console, Bing, `gh repo edit` and
  the social preview upload: owner actions.
- Analytics, trackers, cookie banners, a custom domain, `robots.txt`, and the
  Material `social` plugin (the card comes from task 79).
- Migrating to Zensical.
