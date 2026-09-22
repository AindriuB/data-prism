# 59 — Give the demo an exit ramp and bring the reference docs up to the new path

**Repo:** .
**Depends on:** 53, 54, 55, 56
**Owns:**
- docs/quickstart.md
- docs/configuration.md
- README.md

## Goal
`docs/quickstart.md` ends at "stop and reset", so the minutes someone just spent lead
nowhere. End it by pointing at the walkthrough, replace its four hand-scraped curl
blocks with task 56's single demo command, and record in the reference docs what tasks
53, 54 and 55 changed: the identity-resolver property, the single statement of a
configured source's transport, and the published evaluation issuer image.

## Context
- docs/quickstart.md:57-167 — the token block, the `Mcp-Session-Id` scrape and the
  `python3 -c` parse that task 56's command replaces; :180-195 — the dead end.
- docs/configuration.md:197-208 — the owed-work paragraph task 54 discharges. It must
  go, replaced by a statement of where a configured source's transport is now stated.
- docs/configuration.md's "Ownership boundary" table — its `Sources` row names
  `IdentityResolver` as application code only; task 53 changes that for the flat case.
- README.md — the landing page; it currently routes a newcomer at the Compose demo.

## Acceptance
- [ ] `docs/quickstart.md` ends with a "what next" section linking
      `docs/protect-your-own-api.md`, and no section of it still ends the reader's
      journey at `docker compose down`.
- [ ] The four curl blocks are replaced by task 56's one command, quoted by its real
      path, with its real output pasted from an actual run. No `grep | tr | cut` header
      scrape and no `python3 -c` one-liner remain in the file.
- [ ] `docs/quickstart.md` states the `docker compose up` (pull) default and the
      from-source override command exactly as task 55 landed them, and the stated
      command was run.
- [ ] `docs/configuration.md` documents `dataprism.identity.resolver`, including the
      accepted values, the `UNSUPPORTED_IDENTITY_RESOLVER` refusal, and that absence
      still yields `MISSING_IDENTITY_RESOLVER`.
- [ ] `docs/configuration.md` no longer tells the reader to state a configured source's
      `base-url` twice, and the owed-work paragraph at :197-208 is gone rather than
      reworded.
- [ ] The evaluation issuer image is documented — how to point a deployment's
      `jwk-set-uri` at it, and, in the same paragraph, that it is fixture-only, that
      `dataprism.transport.fixture-development=true` is still refused on the standalone
      server, and that nothing about it permits an unauthenticated call.
- [ ] `README.md` offers the newcomer both paths — "see the demo" and "protect your own
      API" — with the second linked.
- [ ] No claim in any of the three files is carried forward unverified: every command
      quoted was run, and any pre-existing claim the run contradicts is corrected or
      reported.

## Out of scope
- `docs/extending.md` and `docs/protect-your-own-api.md` — task 58 owns both.
- `CHANGELOG.md`, `server.json`, `docs/plan/*` — release and scribe territory.
- Any code, workflow or Compose change. If a doc cannot be made true without one,
  report it rather than editing outside `Owns`.
