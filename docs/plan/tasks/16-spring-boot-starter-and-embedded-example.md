# 16 — Ship the Spring Boot starter and an embedded protected-API example

**Repo:** `.`
**Depends on:** 15
**Owns:**
- data-prism-spring-boot-autoconfigure/**
- data-prism-spring-boot-starter/**
- data-prism-example/**
- pom.xml

## Goal

Make the Java-first path a normal Spring Boot dependency: an application adds
the starter, supplies explicit reviewed source and identity beans, configures
`dataprism.*`, and receives the protected `/mcp` endpoint without copying the
current manual assembly.

## Acceptance

- [ ] The shared auto-configuration module owns the MCP SDK server lifecycle
      and servlet registration; the dependency-only starter supplies that
      module, and no application code beyond the standard dependency and
      explicit extension beans is required to create the MCP endpoint.
- [ ] The example is converted from hand-built `DataPrismAssembly` production
      wiring to consume the starter, while retaining fixture-only stdio as a
      distinct development entry point.
- [ ] An end-to-end test starts the example with a test JWKS and explicit source
      beans, calls `/mcp` with a valid JWT, and proves that a protected response
      is pseudonymised.
- [ ] A second test proves a missing adapter or identity resolver prevents the
      application from starting.
- [ ] Existing architecture rules still prove the MCP layer cannot directly
      access connector DTOs or bypass the privacy mapper.
- [ ] `mvn -B verify` is green.

## Out of scope

- OCI packaging, Docker Compose, and YAML-defined arbitrary JSON APIs.
