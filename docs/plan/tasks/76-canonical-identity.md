# 76 — Give the project one canonical identity across README, poms, registry and image metadata

**Repo:** `.`
**Wave:** 1 (spec task T1)
**Depends on:** none
**Base branch:** the LOCAL `discoverability` branch (not pushed, not `main`).
`wt-new.sh` bases new worktrees on `main`, so right after it, before any edit,
run `git -C <worktree> reset --hard discoverability`. The branch merges back
into `discoverability`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/76-canonical-identity.md`.
**Owns:**
- README.md
- pom.xml *(the `<description>` element only)*
- data-prism-spring-boot-starter/pom.xml *(optional; `<description>` only)*
- data-prism-connectors-rest/pom.xml *(optional; `<description>` only)*
- server.json *(`.description` only)*
- docker/distribution/Dockerfile *(new `LABEL` lines only)*
- docker/server/Dockerfile *(one new `LABEL` line only)*
- CITATION.cff *(new)*
- docs/extending.md *(the three `README.md:NNN` citations only)*

## Goal

Every surface that search engines and AI assistants read (README, Maven pom,
MCP `server.json`, OCI image labels, `CITATION.cff`) states the same tagline T
and description D, and the README's Status paragraph stops claiming the
hash-chained sink and verifier are "Not built". The README also gains the
`site-intro` markers that task 82's docs site includes as its home page.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — the binding spec. Read
  "Canonical description", "Facts the planner must respect" and "T1".
- **Tagline T** (96 chars), verbatim:
  `Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients.`
- **Description D** (280 chars), verbatim:
  `Data Prism is an open-source privacy layer for Java/Spring teams putting LLM agents or MCP clients in front of internal APIs holding customer data. It pseudonymises personal data per privacy scope, redacts or refuses anything unclassified, and can keep a hash-chained audit trail.`
- The README is hard-wrapped at about 80 columns. T and D are the exception:
  each must sit on one unwrapped line wherever it appears, otherwise `grep -F`
  cannot find it.
- `README.md:1-24`: the H1, the `<!-- mcp-name: io.github.AindriuB/data-prism -->`
  line (line 3) and the stale Status paragraph (lines 7-24). The Status
  paragraph says "Not built: … the append-only audit sink with hash-chain
  verifier", but `CHANGELOG.md` `[0.3.0]` "Added" ships both
  (`FileAuditSink`, `AuditChainVerifier`). Its `pom.xml:24-42` citation is
  already wrong: those lines are now `<developers>`. Do not cite pom line
  numbers.
- `docs/tools.md` "## Not yet built" (line 439) is the source for what is
  still not built (`search_entity_data`, `describe_entity_model`). The
  re-identification surface and the Elasticsearch connector are still not
  built (`docs/architecture.md#decisions-worth-knowing`).
- `CHANGELOG.md` `[0.3.0]` "### Not changed" (line 171) and
  `docs/audit.md` "## What this does and does not prove" (line 226) are the
  only allowed sources for any audit-trail wording.
- `.github/workflows/publish-mcp.yml:33-41` extracts the registry name three
  ways, each line-anchored:
  `jq -r '.name' server.json`;
  `sed -n 's/^<!-- mcp-name: \(.*\) -->$/\1/p' README.md`;
  `sed -n 's/^LABEL io\.modelcontextprotocol\.server\.name="\(.*\)"$/\1/p' docker/distribution/Dockerfile`.
  The README comment and the Dockerfile `LABEL` line must stay byte-identical
  and each on its own line. The new OCI labels go on **separate** `LABEL`
  lines and must not be merged into the existing one.
- `server.json` follows schema 2025-12-11: `.description` has `maxLength: 100`.
  CI validates only on a tag push, so run `mcp-publisher validate server.json`
  locally. The binary is not installed on this host: download it exactly as
  `publish-mcp.yml:43-48` does.
- `docker/fixtures/Dockerfile:28` is the style for the fixture-only
  `org.opencontainers.image.description` in `docker/server/Dockerfile`, which
  is the quickstart server image and not the registry image.
- `docs/extending.md:246`, `:603` and `:611` cite `README.md:160-163`,
  `README.md:160-176` and `README.md:167-171`. Each becomes a reference to
  the README section by its heading (e.g. "the README's 'Building and
  running' section"). Find the right heading by reading what those lines
  covered at base.
- Published metadata is frozen until the next release. Central, the MCP
  registry and GHCR keep their 0.3.0 text. Change no version anywhere.
- The Maven Central badge must point at an artifact that is actually on
  Central under `io.github.aindriub` (e.g. `data-prism-spring-boot-starter`).
  The build badge points at `.github/workflows/build.yml`.
- The pom `<developers>` comment (`pom.xml:25-29`) explains why no email is
  published. `CITATION.cff` follows the same rule: author name only
  (`NOTICE`: Andrew Bannister), no email.

## Acceptance

- [ ] README opens (after the H1, the unchanged `mcp-name` line and the
      badges) with T, then D, then a "Who it's for" of exactly two sentences
      naming the audience: Java/Spring platform and backend teams putting
      LLM agents or MCP clients in front of internal APIs that hold customer
      data.
- [ ] `<!-- site-intro:start -->` and `<!-- site-intro:end -->` each occur
      exactly once, each on its own line, in that order. The block starts at T
      and ends after the last line of the "Try it" section. The H1, the
      `mcp-name` line and every badge are outside the block.
- [ ] The badges are build, Maven Central and license, and the license badge
      says Apache-2.0.
- [ ] `grep -F "$T"` matches README.md, server.json and
      docker/distribution/Dockerfile. `grep -F "$D"` matches README.md,
      pom.xml and CITATION.cff.
- [ ] `jq -r .description server.json` equals T exactly, and its length is
      at most 100.
- [ ] `mcp-publisher validate server.json` exits 0.
- [ ] Running the three extractions from `publish-mcp.yml:35-37` against the
      branch prints `io.github.AindriuB/data-prism` three times.
      `git diff discoverability -- README.md docker/distribution/Dockerfile`
      shows neither the `mcp-name` line nor the
      `LABEL io.modelcontextprotocol.server.name=` line as changed.
- [ ] `docker/distribution/Dockerfile` has five new single-line `LABEL`s:
      `org.opencontainers.image.title="Data Prism"`,
      `org.opencontainers.image.description="<T>"`,
      `org.opencontainers.image.source="https://github.com/AindriuB/data-prism"`,
      `org.opencontainers.image.documentation="https://aindriub.github.io/data-prism/"`
      (this 404s until task 82 deploys, which is acceptable because the image
      is not republished before the next release), and
      `org.opencontainers.image.licenses="Apache-2.0"`. There is no
      `version` label.
- [ ] `docker/server/Dockerfile` gains one
      `org.opencontainers.image.description` label that says the image is a
      fixture-only quickstart server, not for production. The label must not
      be T.
- [ ] `docker buildx build --check -f docker/distribution/Dockerfile .` and
      the same command for `docker/server/Dockerfile` both exit 0.
      `docker build` of the distribution image followed by `docker inspect`
      shows all six labels (the five new ones plus the unchanged name label).
- [ ] `pom.xml` `<description>` is D on one line. `mvn -B -q -N validate`
      exits 0. If the starter or connectors-rest descriptions change, they
      stay accurate to that module, and `mvn -B -q validate` exits 0.
- [ ] `CITATION.cff` is CFF 1.2.0 with `abstract` equal to D, `title`,
      `message`, `authors` (name only), `repository-code`, and
      `license: Apache-2.0`. It has no `version` and no `date-released`.
      `cffconvert --validate` (via `pipx run` or a throwaway venv) exits 0.
- [ ] `git diff discoverability | grep -E '^[-+].*<version>|"version"'` shows
      no version change in any file.
- [ ] `grep -rn 'README.md:[0-9]' docs/ --exclude-dir=plan` is empty.
      (Stricter than the spec, and narrower: `docs/plan/` holds HISTORY and
      the spec itself, which cite README lines historically and are not this
      task's to edit.)
- [ ] The reviewer traces every sentence of the new Status paragraph, and
      every audit claim in the README, to `CHANGELOG.md` `[0.3.0]`,
      `docs/tools.md` "Not yet built" or `docs/audit.md`, and lists each
      claim with its source.
- [ ] Honesty checks on README.md, CITATION.cff and the new Dockerfile labels:
      - Every hit of `grep -niE 'anonymi|compliant|tamper-proof|guarantee'`
        is a negation or refers to another tool, and the reviewer lists each
        hit with a verdict.
      - `git diff discoverability -U0 | grep '^+' | grep -niE 'GDPR-compliant|tamper-proof|API gateway|Spring AI|comprehensive|robust|seamless'`
        is empty. The existing "Not an API gateway" negation in "What it is
        not" is untouched.
      - Nothing describes pseudonymisation as anonymisation, and the "It is
        not anonymisation" paragraph survives.

## Out of scope

- The README "Documentation" table: task 83 splits it and links the site.
- `server.json` `websiteUrl`: task 83 adds it after the site is live.
- `.github/workflows/*`, including OCI index annotations in
  `publish-image.yml` (a separate next-release task in the spec).
- Any version bump, CHANGELOG entry or release.
- Front matter on any existing doc, and any doc other than the three
  `docs/extending.md` citations.
- `gh repo edit` (description, homepage, topics) and the social preview:
  owner actions after go-live.
