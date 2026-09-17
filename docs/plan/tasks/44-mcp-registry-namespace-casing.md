# 44 — Correct the MCP registry namespace to the GitHub login's casing

**Repo:** `.`
**Depends on:** none
**Owns:**
- server.json
- docker/distribution/Dockerfile
- README.md
- .github/workflows/publish-mcp.yml

## Goal

`mcp-publisher publish` returns 403: the registry grants the namespace
`io.github.AindriuB/*`, matching the GitHub login's casing, while the server
declares `io.github.aindriub/data-prism`. Three strings carry that name and all
three must agree, because the registry cross-checks the published name against
the image's own label. Add a guard so they cannot drift apart again.

## Context

- `server.json:3` — `"name": "io.github.aindriub/data-prism"`.
- `README.md:3` — `<!-- mcp-name: io.github.aindriub/data-prism -->`.
- `docker/distribution/Dockerfile:24` —
  `LABEL io.modelcontextprotocol.server.name="io.github.aindriub/data-prism"`.
  This is the only Dockerfile of the five that carries the label.
- `.github/workflows/publish-mcp.yml:57-76` — the existing pattern for a
  pre-publish consistency guard: two `jq` reads compared in shell, failing with
  `::error::`. The new guard belongs beside these.
- `docs/plan/PLAN.md:289` — the namespace claim happens via GitHub OIDC at
  dispatch time, under the repository owner's identity.

## The one thing that must not change

Maven Central's coordinates are **correctly lowercase** and are a different
identifier entirely: group `io.github.aindriub`, package root
`io.github.aindriub.dataprism` (`docs/plan/PLAN.md:23`). Only the
slash-separated MCP registry name `io.github.aindriub/data-prism` changes. A
diff that touches a `pom.xml`, a Java package or an artifact id is wrong.

## Acceptance

- [ ] `server.json`'s `.name`, `README.md`'s `mcp-name` marker and
      `docker/distribution/Dockerfile`'s
      `LABEL io.modelcontextprotocol.server.name` all read
      `io.github.AindriuB/data-prism`.
- [ ] `git diff --stat` for this task lists exactly the four owned files. No
      `pom.xml`, no `.java`, no other Dockerfile.
- [ ] `rg 'io\.github\.aindriub/data-prism'` over the working tree returns hits
      only in `docs/plan/HISTORY.md` and `docs/plan/HISTORY-INDEX.md`, which are
      a record of what happened and are not edited.
- [ ] `rg 'io\.github\.aindriub\.dataprism|<groupId>io\.github\.aindriub'`
      returns the same count of hits as before the change.
- [ ] `publish-mcp.yml` gains a step, running on every tag push as well as on
      dispatch, that extracts the name from all three files and fails with an
      `::error::` unless they are byte-identical to each other.
- [ ] **Non-vacuity, recorded in the PR description:** run that step's shell
      locally against a copy of the tree with one of the three reverted to the
      lowercase form, and paste the failing output. Then run it against the real
      tree and paste the pass.
- [ ] `./mcp-publisher validate server.json` — or the workflow's existing
      validate job on the branch — passes with the new name.
- [ ] The PR description states plainly that the label is baked into the image,
      so this fix takes effect only once `publish-image.yml` has rebuilt and
      re-pushed the image for the version `server.json` references, and that the
      403 is confirmed cleared only by an owner-dispatched `publish-mcp.yml`
      run. Do not claim the publish is fixed; claim the strings are.

## Out of scope

- Dispatching any workflow, tagging, publishing, or claiming the namespace.
  Those are owner actions (`docs/plan/PLAN.md:283-292`).
- The version fields in `server.json`. Task 45 owns those, and it runs after
  this one.
- The other four Dockerfiles, `publish-image.yml` and `publish-central.yml`.
- Prose in the README beyond the marker comment on line 3.
