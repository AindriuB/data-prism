# 139 — Migrate to Spring Boot 4

**Repo:** `.`
**Release:** 0.5.0
**Depends on:** 130
**Status:** placeholder. It needs a `/plan` pass to split into tasks before any work starts.

## Goal

Dependabot #114 proposes Spring Boot 3.5.16 → 4.1.1. Owner decision D-130-A (2026-10-07):
treat this as its own migration, not a version bump. It brings Spring Framework 7 and Jackson 3, and
changes the autoconfiguration, configuration-property, Spring Security and test-slice APIs used by
autoconfigure, the server, security and the MCP transport. Plan it with a migration pass and a full
regression. Until then, `.github/dependabot.yml` ignores Spring Boot major versions (task 130).
