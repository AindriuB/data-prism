# 152 — Strip the configured outbound correlation header on every request, not only when an id is present

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/OutboundCorrelationInterceptor.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/OutboundCorrelationDefaultHeaderTest.java *(new)*

## Goal

External review of release PR #117 (head e23de419), finding A, P2, release
blocker for 0.5.0. `OutboundCorrelationInterceptor` removes the configured
header only inside the `instanceof ExternalCorrelationId` branch. If the
`RestClient` carries a default or preset header with the same name, a request
whose id is absent or was rejected sends that preset value downstream. That
is a fail-open path: an unvalidated value is sent under the correlation
header. The header must be removed on every request, and set only from a
validated id.

## Context

- `OutboundCorrelationInterceptor.java:32-41`: `remove` is inside the `if`.
  The class Javadoc (":19 Any existing value of the header is removed first")
  claims the behaviour unconditionally. The code must match the claim.
- `OutboundCorrelationHttpTest.java`: the local `HttpServer` stub that
  records `X-Correlation-ID` values per path, and the `adapter(header)`
  helper that builds `RestDataSourceAdapter` over `RestClient.create()`.
  Copy that shape. Build the client with
  `RestClient.builder().defaultHeader(<configured name>, "preset-synthetic-value")`.
- Task 111 (retired) is the origin. Task 113 adds the global
  `dataprism.correlation.outbound.header`. Traceparent mode uses the header
  name `traceparent`, so the same strip applies to it.
- CLAUDE.md rule 2, fail closed. Rule 3: header values in tests are
  obviously synthetic.

## Acceptance

- [ ] `intercept` calls `request.getHeaders().remove(header)` before, and
      outside, the attribute check. It then calls `set(header, value)` only
      when the attribute is an `ExternalCorrelationId`.
- [ ] A new test class `OutboundCorrelationDefaultHeaderTest` uses a stub
      server and a `RestClient` whose builder sets a default header with the
      configured name and a synthetic value. It covers four cases:
      - **No id attached:** the stub receives no instance of the header. The
        recorded list is empty.
      - **Id rejected by `CorrelationIdPolicy`**, so no attribute is set: the
        stub receives no instance of the header.
      - **Validated opaque id:** the stub receives exactly one value, equal
        to the validated id. The list has size 1 and does not contain the
        preset value.
      - **Traceparent mode:** a client with a default `traceparent` header
        and a validated traceparent id sends exactly one `traceparent`, the
        child traceparent. A client with a default `traceparent` header and
        no id sends none.
- [ ] One case sets the default header name in different letter case (for
      example `x-correlation-id` against the configured `X-Correlation-ID`)
      and asserts that it is stripped too.
- [ ] `OutboundCorrelationHttpTest`, `OutboundCorrelationHeaderConfigTest`
      and `NoAmbientCorrelationSourceTest` are byte-identical in `git diff`
      and pass.
- [ ] Mutation proof, recorded in the PR or the hand-back with the names of
      the failing tests: move `remove` back inside the `if`, run the new
      class, and see at least the no-id and rejected-id cases fail. Then
      revert.
- [ ] `mvn -pl data-prism-connectors-rest -am verify` passes.

## Out of scope

- Other headers on the client, such as `Authorization` or custom ones. Only
  the configured correlation header is in scope.
- `RestSource`, the auto-configuration, header-name validation and the
  property binding (tasks 113 and 141).
- Any `docs/*.md` change. The Javadoc already says "removed first". If you
  think a doc line is needed, name it in the hand-back.
- `CHANGELOG.md`, which task 153 owns for this release. Name the line you
  would add in the hand-back.
- Audit-sink work. That is task 153.
