# 81 — Draft the awesome-list entries and the launch write-up for the owner to post

**Repo:** `.`
**Wave:** 1 (spec task T6)
**Depends on:** none
**Base branch:** the LOCAL `discoverability` branch (not pushed, not `main`).
`wt-new.sh` bases new worktrees on `main`, so right after it, before any edit,
run `git -C <worktree> reset --hard discoverability`. The branch merges back
into `discoverability`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/81-outreach-drafts.md`.
**Owns:**
- docs/plan/outreach/**

## Goal

Ready-to-post drafts: one per awesome list, each checked against that list's
own contribution rules, plus one launch write-up with Show HN and r/java
variants. The owner reviews and posts each one by hand. No agent submits,
forks, opens a PR, comments or posts anywhere.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — binding. Read
  "Decisions → Off-repo", "Canonical description", "Never use", "T6" and
  "Risks → Reputation".
- The target lists:
  - `punkpeye/awesome-mcp-servers`
  - the canonical Spring or Spring AI awesome list, identified at execution
    time (record which one and why)
  - `akullpp/awesome-java`
  - optionally, an awesome-llm-security list
- **Read each list's CONTRIBUTING rules at execution time.** Quote them from
  the list's current default branch (e.g.
  `gh api repos/<owner>/<repo>/contents/CONTRIBUTING.md --jq .content | base64 -d`,
  or the README's contributing section if there is no CONTRIBUTING file),
  with URL and access date. Read-only `gh api` GETs only.
- No Spring AI integration exists. If the canonical Spring list is a Spring
  AI list, the verdict is very likely "hold". The entry text must never
  claim Spring AI support.
- "Data Prism" is a common name. Always pair it with "MCP privacy layer" in
  entry lines and titles.
- Submitting before the project is eligible costs goodwill (spec
  "Risks → Reputation"). Stars at baseline: 1.
- Launch post angle, verbatim: "Redaction breaks LLM investigations;
  consistent pseudonyms don't — a fail-closed privacy layer for MCP in Spring
  Boot". The README sections "The problem" and "What Data Prism does" state
  this argument. Limits come from "What it is not", `docs/tools.md` "Not yet
  built" and `docs/audit.md:226`.
- Link targets: the site is not live until task 82 deploys. Link to
  `https://github.com/AindriuB/data-prism/blob/main/…` URLs that return 200
  today. Each file carries a pre-post checklist item: "swap to site URLs
  after go-live".
- `docs/plan/` is excluded from the site.

## Acceptance

- [ ] There is one file per list, e.g. `awesome-mcp-servers.md`,
      `awesome-spring.md`, `awesome-java.md` and optionally
      `awesome-llm-security.md`. Each contains:
      - the list's contribution rules, quoted, with source URL and access
        date
      - the exact entry line in that list's required format and section
      - the PR title and PR body. The body discloses that the author
        maintains Data Prism.
      - an eligibility verdict: "ready" or "hold", with the reason (e.g. a
        popularity or age threshold, citing the quoted rule)
- [ ] `launch-post.md` has the agreed angle and a body in which every claim
      about Data Prism links to a doc. It has a plainly worded "Limits"
      section: not anonymisation, not a prompt-injection defence, what is not
      yet built, and the audit trail's "does not prove" points. It gives at
      least one Show HN title (at most 80 characters, starting "Show HN:")
      and at least one r/java title.
- [ ] `README.md` holds a status table with one row per draft: list or
      venue, draft file, verdict, and blank "posted on" and "outcome"
      columns for the owner.
- [ ] Every URL in the directory returns 200 at execution time (lychee or
      `curl -sfIL` per URL), except the maintainer's future site URLs, which
      appear only in the pre-post checklist.
- [ ] There is no submission script, no workflow, and nothing in the diff
      that calls a write API. The tester confirms that no `gh pr create`,
      `gh repo fork` or `gh api -X POST` was run.
- [ ] Honesty checks on `docs/plan/outreach/`:
      - Every hit of `grep -rniE 'anonymi|compliant|tamper-proof|guarantee' docs/plan/outreach/`
        is a negation, refers to another tool, or is inside a quoted rule.
        The reviewer lists each hit with a verdict.
      - `grep -rniE 'GDPR-compliant|tamper-proof|API gateway|comprehensive|robust|seamless' docs/plan/outreach/`
        is empty outside quoted list rules.
      - `grep -rni 'Spring AI' docs/plan/outreach/` hits only a list's name,
        URL or quoted rules, never a claim about Data Prism.
- [ ] `git diff --name-only discoverability` lists only files under
      `docs/plan/outreach/`.

## Out of scope

- Posting, submitting, forking or opening any PR or issue on another
  repository: owner actions, one at a time, and only where the rules are met.
- Blog platforms, social accounts or scheduling.
- Editing README or docs to support the post (tasks 76-78).
