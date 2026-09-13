# 17 — Package the standalone Data Prism server

**Repo:** `.`
**Depends on:** 16
**Owns:**
- data-prism-server/**
- pom.xml
- README.md

## Goal

Provide the primary product surface: a Spring Boot application built from the
shared configuration core, with no fixture adapters, development key, or
single-principal caller compiled into its production runtime.

## Acceptance

- [ ] `data-prism-server` starts a Streamable HTTP MCP endpoint at `/mcp` only
      when JWT, policy, key-reference, source-adapter and privacy configuration
      are complete and valid.
- [ ] Its production artifact has no dependency on `data-prism-example` and
      ships no stub API adapter or development HMAC key.
- [ ] It exposes a safe unauthenticated health endpoint and authenticated MCP
      endpoint consistent with the existing resource-server rules.
- [ ] A packaging test proves a minimal valid configuration starts, while a
      missing required production input refuses startup.
- [ ] README documents the server as the primary deployment surface and links
      to the configuration reference rather than instructing users to inspect
      Java source.
- [ ] `mvn -B verify` is green.

## Out of scope

- Building an image or orchestrating local dependencies; Task 18 owns that.
