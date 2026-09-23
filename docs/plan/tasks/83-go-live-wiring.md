# 83 — Point the README and server.json at the live docs site

**Repo:** `.`
**Wave:** 3 (spec task T8)
**Depends on:** 82, 76
**Base branch:** the LOCAL `discoverability` branch. `wt-new.sh` bases new
worktrees on `main`, so right after it, before any edit, run
`git -C <worktree> reset --hard discoverability`. The branch merges back into
`discoverability`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/83-go-live-wiring.md`.
**Precondition (not part of this task):** task 82 is merged and **deployed**.
That means `discoverability`, carrying tasks 76-82, has reached `main`
through its PR; the owner enabled Pages beforehand; and the `pages` deploy
job has succeeded. Before starting, confirm that
`curl -s -o /dev/null -w '%{http_code}' https://aindriub.github.io/data-prism/`
prints 200, and bring `discoverability` up to date with `main`. If the site
is not live, stop and report. Do not start the task.
**Owns:**
- README.md *(the "Documentation" section only)*
- server.json *(add `websiteUrl` only)*

## Goal

Now that the site is live, the repo sends readers to it. The README's docs
table separates user docs, which are linked on the site, from internal
working docs, and gains the rows it has been missing. `server.json` gains
`websiteUrl`, so the next registry publish carries it.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — binding. Read
  "Facts the planner must respect", "T8" and "Owner actions".
- `README.md` "## Documentation" (at base, line 121): a single table mixing
  user docs with internal ones (`design-review.md`, `development-plan.md`,
  `pack.md`, `conventions.md`, `workflow.md`, `plan/…`). It lacks
  `audit.md`, `configuration.md` and `protect-your-own-api.md`. Task 82's
  nav lists the published pages; the site URL for a doc is its nav URL (e.g.
  `https://aindriub.github.io/data-prism/quickstart/`).
- `publish-mcp.yml:35-37`: the README `<!-- mcp-name: … -->` line must stay
  byte-identical and on its own line. The site-intro markers from task 76
  must stay intact, because the site includes the block between them.
- `server.json` schema 2025-12-11 allows `websiteUrl`. `.description` stays
  T, and the version stays 0.3.0. The registry will not show the change until
  the next release publishes. That is expected: do not bump anything.
- The README is hard-wrapped at about 80 columns, and table rows are exempt.

## Acceptance

- [ ] README "Documentation" has two tables or subsections: "User docs" and
      "Internal / project working docs".
- [ ] "User docs" has a row for each of these, linking both the site page
      and the repo file:
      - quickstart, protect-your-own-api, configuration, tools, extending
      - audit, architecture, agents
      - faq, comparison, and the three use cases
- [ ] "Internal" lists `design-review.md`, `development-plan.md`, `pack.md`
      (marked superseded and historical), `conventions.md`, `workflow.md`,
      `docs/plan/PLAN.md` and `docs/plan/HISTORY-INDEX.md`, and says these
      are not on the site.
- [ ] The section links to `https://aindriub.github.io/data-prism/` once, in
      prose.
- [ ] `git diff discoverability -- README.md` touches only lines inside the
      "## Documentation" section.
- [ ] `jq -r .websiteUrl server.json` prints
      `https://aindriub.github.io/data-prism/`.
- [ ] `git diff discoverability -- server.json` is that one added key and
      nothing else.
- [ ] `mcp-publisher validate server.json` exits 0 (binary downloaded as in
      `publish-mcp.yml:43-48`).
- [ ] The three name extractions from `publish-mcp.yml:35-37` print
      `io.github.AindriuB/data-prism` three times.
- [ ] The live site returns 200 at each of these (`curl -s -o /dev/null -w '%{http_code}'`,
      output quoted in the report):
      - `https://aindriub.github.io/data-prism/`
      - `…/sitemap.xml`
      - `…/llms.txt`
- [ ] Every site URL added to the README returns 200.
- [ ] Honesty checks on the changed README lines:
      - `git diff discoverability -U0 -- README.md | grep '^+' | grep -niE 'anonymi|compliant|tamper-proof|guarantee|API gateway|Spring AI|comprehensive|robust|seamless'`
        is empty, or each hit is a negation that the reviewer accepts.

## Out of scope

- Any other README section, including the intro, Status and badges (task
  76's).
- Any `docs/` file or site config (task 82's).
- `gh repo edit` (description D, homepage, the 11 new topics), the social
  preview upload, and Search Console / Bing sitemap submission: owner
  actions after go-live.
- Version bumps, and republishing to the registry, GHCR or Central.
