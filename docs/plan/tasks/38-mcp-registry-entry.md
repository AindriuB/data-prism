# 38 — Publish the MCP registry entry with an honest env contract

**Repo:** data-prism
**Depends on:** 33, 37
**Owns:**
- server.json (new)
- README.md
- .github/workflows/publish-mcp.yml (new)

## Goal
List Data Prism on the official MCP registry as `io.github.aindriub/data-prism`,
pointing at the real published image from task 37. The entry must declare every
environment variable a working deployment needs and must state plainly that the
image serves nothing until the operator supplies their own reviewed
`DataSourceAdapter` jar — a registry entry implying `docker run` alone yields a
working MCP server would be false.

## Context
- DataPrismContractValidator.java:25-33 — `MISSING_SOURCE_ADAPTER` and
  `UNRESOLVED_SOURCE_ADAPTER`: no adapter bean per configured source means no
  startup. This is the sentence the README and the entry's description must carry
- DataPrismProperties.java:114-140 (`protectedDeployment()`) — the exact set an
  HTTP deployment must supply: JWT issuer, audience, exactly one JWKS or
  issuer-discovery location, and the three caller-claim mappings
- docs/configuration.md — the `dataprism.*` vocabulary table: transport, security,
  security-policy, privacy (+ `hmac-key`), audit, metrics, hazelcast `topology`,
  and one `sources.<name>` entry per adapter. Relaxed binding gives the
  `DATAPRISM_*` environment-variable spellings
- docker/distribution/Dockerfile (task 37) — the loader-path directory and the
  configuration mount point the entry has to name
- README.md:5-21 — the existing honest status paragraph, the tone to match
- The registry requires the `mcp-name` marker in the README of the source
  repository it validates ownership against

## Acceptance
- [ ] `server.json` at the repository root declares `name`
      `io.github.aindriub/data-prism`, `version` `0.1.0`, a `repository` pointing
      at `https://github.com/AindriuB/data-prism`, and one `packages` entry with
      `registryType: oci`, identifier `ghcr.io/aindriub/data-prism-server`,
      version `0.1.0`, and a `streamable-http` transport.
- [ ] `server.json` validates against the published MCP server schema it names in
      `$schema`, checked by a command in CI, not by eye.
- [ ] Every variable in `DataPrismProperties.protectedDeployment()` appears in the
      entry's environment variables with `isRequired: true` and a one-line
      description; so do the audit sink, metrics sink, Hazelcast topology, privacy
      profile, HMAC key id and key reference, and at least one source base URL.
      A reviewer can diff the list against docs/configuration.md's table and find
      nothing required missing.
- [ ] The entry declares the loader-path / extension-jar variable and the
      configuration-file location variable the task 37 image reads, both marked
      required, with descriptions saying what the operator has to supply.
- [ ] The entry's description states, in one sentence, that the image refuses to
      start until a reviewed `DataSourceAdapter` jar for each configured source is
      supplied on the loader path — no wording that implies `docker run` alone
      produces a working server.
- [ ] `README.md` contains the line `mcp-name: io.github.aindriub/data-prism` in
      the form the registry's ownership validation expects.
- [ ] `README.md` gains a short section for registry arrivals that names the two
      things `docker run` does not give them — a reviewed adapter jar and a
      deployment configuration — and links to docs/configuration.md. It does not
      duplicate the quickstart.
- [ ] `.github/workflows/publish-mcp.yml` triggers on `push: tags: ['v*']` plus
      `workflow_dispatch`, has `permissions: id-token: write, contents: read`,
      and publishes with `mcp-publisher` authenticating through GitHub OIDC for
      the `io.github.aindriub` namespace. It runs after, not before, the image
      workflow's tag has produced a pullable image — state how that ordering is
      guaranteed.
- [ ] The published entry is verifiable: a `curl` against the registry's API for
      `io.github.aindriub/data-prism` returns the entry, and the report quotes the
      command and its status, not a screenshot.

## Out of scope
- Building or pushing the image. Task 37 owns `docker/distribution/**` and the
  image workflow; this task only references the published coordinate.
- Any pom, version string or Maven Central configuration — tasks 33 and 36.
- Rewriting README's status, problem or quickstart sections. Only the `mcp-name`
  marker and the new registry-arrival section change.
- Listing on any third-party directory, or adding client-specific config
  snippets beyond what `docs/agents/` already covers.
- `docs/**` — scribe hand-off at `/record`.
