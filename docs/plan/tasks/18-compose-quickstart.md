# 18 — Deliver a reproducible local quickstart

**Repo:** `.`
**Depends on:** 16, 17
**Owns:**
- compose.yaml
- docker/**
- examples/quickstart/**
- docs/quickstart.md
- .env.example

## Goal

Replace source-reading as onboarding with one safe, reproducible local journey
that brings up the standalone server, synthetic fixture APIs and a local token
issuer, then proves an agent-compatible MCP request succeeds.

## Acceptance

- [ ] `docker compose up` uses only synthetic fixtures and generated local
      credentials; no real endpoint, token, key or certificate is committed.
- [ ] The quickstart documents exact commands to start, obtain a development
      token, discover the MCP tool, invoke `get_entity_context`, and stop/reset
      the environment.
- [ ] A repeatable smoke test proves the valid token path returns a
      pseudonymised response and an absent/invalid token cannot call `/mcp`.
- [ ] The Compose configuration passes secrets by environment/file reference,
      not inline production-like values, and labels every fixture-only default.
- [ ] The guide distinguishes this local demonstration from a production
      deployment and links to the configuration reference.

## Out of scope

- Kubernetes/Helm, cloud secret-manager integration, or treating Compose as a
  production deployment.
