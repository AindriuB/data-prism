# 34 — Add CHANGELOG, SECURITY, dependabot and repository topics

**Repo:** data-prism
**Depends on:** none
**Owns:**
- CHANGELOG.md (new)
- SECURITY.md (new)
- CONTRIBUTING.md — the security-reporting section only
- .github/dependabot.yml (new)

## Goal
A public repository about to be listed on the MCP registry has no `CHANGELOG.md`,
no `SECURITY.md`, no dependency update automation and no topics; security
reporting is folded into `CONTRIBUTING.md`, which is non-standard. Add the four
standard files and set the repository's topics, so a stranger arriving from the
registry can find what changed, how to report a vulnerability privately, and can
see the dependencies are watched.

## Context
- `docs/plan/PLAN.md` — "No `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`,
  or issue/PR templates" has been open debt since the 2026-09-09 audit
- CONTRIBUTING.md — the existing security-reporting paragraph that `SECURITY.md`
  supersedes; leave a pointer rather than duplicating the text
- README.md:5-21 — the honest status paragraph. `CHANGELOG.md`'s 0.1.0 entry must
  agree with it: one MCP tool (`get_entity_context`), re-identification surface
  deferred, file/SLF4J audit sink only, no Elasticsearch connector
- CLAUDE.md rule 7 — no real credential, token or personal datum in any of these

## Acceptance
- [ ] `CHANGELOG.md` follows Keep a Changelog, with a `## [0.1.0] - <date>`
      section whose "Added" list names what actually ships and whose notes name
      what does not: the deferred re-identification surface, the single MCP tool,
      and the file/SLF4J audit sink as the only sink.
- [ ] `SECURITY.md` names the supported version (`0.1.0`), a private reporting
      channel, and a response expectation. It contains no email address or
      credential the owner has not supplied.
- [ ] GitHub private vulnerability reporting is enabled on the repository
      (`gh api repos/AindriuB/data-prism --jq .security_and_analysis` or the
      repository settings page shows it on), and `SECURITY.md` points at it.
- [ ] `.github/dependabot.yml` has three `updates` entries — `maven` at `/`,
      `github-actions` at `/`, and `docker` for the directories that hold
      Dockerfiles — each with a weekly schedule.
- [ ] `gh repo view AindriuB/data-prism --json repositoryTopics` returns a
      non-empty list including at least `mcp`, `mcp-server`, `privacy`,
      `pseudonymisation`, `java` and `spring-boot`.
- [ ] `CONTRIBUTING.md`'s security paragraph points at `SECURITY.md` instead of
      restating the process, and no other part of that file changes.

## Out of scope
- Any pom, version string or workflow — tasks 33 and 36 own those.
- `CODE_OF_CONDUCT.md` and issue/PR templates. Still open debt; not this task.
- Writing changelog entries for versions before 0.1.0. There are none: this is
  the first release, and reconstructing a history from `HISTORY.md` would invent
  releases that never existed.
- `docs/**` — scribe hand-off at `/record`.
