# 15 — Build the validated Spring Boot configuration core

**Repo:** `.`
**Depends on:** 14
**Owns:**
- data-prism-spring-boot-autoconfigure/**
- pom.xml

## Goal

Create the shared auto-configuration module used by both supported deployment
surfaces. It binds and validates the deployment contract, constructs only the
framework-neutral privacy pipeline, and fails before accepting traffic when a
required production input is absent or unsafe.

## Acceptance

- [ ] `data-prism-spring-boot-autoconfigure` exposes typed, documented
      `@ConfigurationProperties` rooted at `dataprism` for every group agreed in
      Task 14.
- [ ] Invalid source URL, missing key reference, invalid role/capability,
      empty purpose list, unsafe production stdio selection, and unresolved
      required bean each fail application startup with a stable diagnostic.
- [ ] The module constructs the MCP HTTP surface only from the existing
      `DataPrismMcpServer` and its single scrubbed mapper; it does not create a
      second mapper or add a connector dependency to `mcp` or orchestration.
- [ ] The application must explicitly provide its source adapters and identity
      resolver in Java-first mode; absence is a startup refusal, never a server
      that silently protects no API.
- [ ] Slice tests boot a minimal valid context and independently prove each
      critical invalid configuration fails.
- [ ] `mvn -B verify` is green.

## Out of scope

- The starter dependency-only module, server launcher, Docker image, and
  config-driven generic JSON sources.
