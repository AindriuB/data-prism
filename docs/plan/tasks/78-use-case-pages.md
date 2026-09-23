# 78 — Write three use-case pages for the searches this audience runs

**Repo:** `.`
**Wave:** 1 (spec task T3)
**Depends on:** none
**Base branch:** the LOCAL `discoverability` branch (not pushed, not `main`).
`wt-new.sh` bases new worktrees on `main`, so right after it, before any edit,
run `git -C <worktree> reset --hard discoverability`. The branch merges back
into `discoverability`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/78-use-case-pages.md`.
**Owns:**
- docs/use-cases/**

## Goal

Three intent pages, each answering one problem a Java/Spring team searches
for. Each routes the reader into the existing quickstart and reference docs
and does not duplicate them. The pages describe only what the shipped code
does.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — binding. Read
  "Canonical description", "Never use", "T3" and "Risks → Overclaiming".
- These are the fixed file names, because task 77 links to them and task 82
  puts them in the nav:
  - `docs/use-cases/pseudonymise-customer-data-spring-boot.md`: pseudonymise
    customer data before an LLM agent sees it (Spring Boot).
  - `docs/use-cases/gdpr-data-minimisation-mcp.md`: GDPR data minimisation
    for MCP tools.
  - `docs/use-cases/consistent-pseudonyms-across-systems.md`: keep one
    customer recognisable across systems without exposing identity.
- Sources to route to and trace from:
  - `docs/quickstart.md`, `docs/protect-your-own-api.md`,
    `docs/configuration.md`, `docs/extending.md` and `docs/agents/`.
  - `docs/tools.md:14` "The pseudonym collapse", `:229` "Worked example: the
    pseudonym collapse, demonstrated", the "Consistency findings" section,
    and the scope-isolation captures (`docs/tools.md:58`).
  - `README.md` "What Data Prism does" and "What it is not" (including "It is
    not anonymisation", GDPR Art. 4(5)).
  - `docs/audit.md:226`, if the audit trail is mentioned at all.
- GDPR citations must link to EUR-Lex CELEX 32016R0679
  (`https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679`)
  for Art. 5(1)(c), 4(5), 25 and 32. Link the article text and do not
  paraphrase it beyond what it says.
- The operator's job, from the README "It is not anonymisation" paragraph
  and `docs/configuration.md`: lawful basis, DPIA, transfer mechanism, HMAC
  key custody, retention, and deciding what is classified as what.
- Pages under `docs/use-cases/` sit one directory down, so repo-root links
  (`../../examples/…`) are rewritten to GitHub URLs by task 82's hook.
  Links to docs are relative (`../quickstart.md`).
- `docs/conventions.md:213` bans "comprehensive", "robust" and "seamlessly".

## Acceptance

- [ ] Exactly the three files above exist under `docs/use-cases/`, and no
      others. There is no index page: task 82's nav lists them.
- [ ] Each file starts with front matter holding a non-empty `title` and a
      `description` of at most 155 characters. The three descriptions are
      distinct.
- [ ] Each page states the problem in the reader's terms in its first
      paragraph, then says what Data Prism does about it, what it does not
      do, and where to go next. The next steps are links to existing docs.
- [ ] The Spring Boot page names the starter and the standalone server as
      the two deployment options, and links to the quickstart and to
      `protect-your-own-api.md`.
- [ ] The GDPR page:
      - links Art. 5(1)(c), 4(5), 25 and 32 to EUR-Lex 32016R0679
      - carries a visible "This is not legal advice" statement
      - has a section listing what stays the operator's job
      - says pseudonymised data is still personal data
- [ ] The cross-systems page explains the pseudonym collapse, consistency
      findings and scope isolation, linking to the matching `tools.md`
      sections by anchor.
- [ ] No page adds a new configuration or code snippet unless the tester has
      run it and pasted real output into the report. Otherwise, pages link to
      existing snippets (`protect-your-own-api.md`, `examples/…`).
- [ ] Every claim about Data Prism links to a doc that supports it, and the
      reviewer traces each one. There are no `README.md:NNN`-style
      citations.
- [ ] Honesty checks on `docs/use-cases/*.md`:
      - Every hit of `grep -rniE 'anonymi|compliant|tamper-proof|guarantee' docs/use-cases/`
        is a negation or refers to another tool. The reviewer lists each hit
        with a verdict.
      - `grep -rniE 'GDPR-compliant|tamper-proof|API gateway|Spring AI|comprehensive|robust|seamless' docs/use-cases/`
        is empty.
      - Nothing says or implies that Data Prism makes a deployment
        GDPR-compliant, or that pseudonymisation is anonymisation.
- [ ] `git diff --name-only discoverability` lists only files under
      `docs/use-cases/`.

## Out of scope

- The FAQ and comparison (task 77), and the site nav and meta (task 82).
- Editing any existing doc or example, even to add an anchor. If an anchor
  you need does not exist, link to the nearest heading and report the gap.
- New example code or configuration under `examples/`.
