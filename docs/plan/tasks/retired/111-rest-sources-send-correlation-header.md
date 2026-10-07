# 111 — Send the external correlation id to configured REST sources through an interceptor

**Repo:** `.`
**Depends on:** 108, 141, 146
*(141 and 146 added 2026-10-07 by the 0.5.0 plan. 141 moves the reactor to Spring Framework 7, so this task is written against it once. 146 may edit `MutualTlsRestClientsHttpsTest.java`, which this task's test glob also covers. Owns is unchanged.)*
*(Followed by 148, added 2026-10-07 by D-148-A: 148 passes an optional `CorrelationMdc` bean to the `SourceFanOut` that `ConfiguredJsonSourcesAutoConfiguration` builds. See also the D-148-A amendment at the end of this file. Owns is unchanged.)*
**Owns:**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/** *(except `ConfiguredJsonScrubbingEngine.java` and `ConfiguredJsonNestedLeafShapeGuard.java`, which task 118 owns and this task leaves unchanged)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/** *(except `ConfiguredJsonUndeclaredKeyRefusalTest.java`, task 118)*
- examples/json-sources/customer-api.yaml *(one commented, disabled `correlation-header` line only)*
- docs/protect-your-own-api.md *(one paragraph under "The catalogue" only)*
- docs/extending.md *(one paragraph appended to task 108's section only)*

## Goal

A source owner should be able to see the organisation's correlation id on
the calls Data Prism makes on that caller's behalf. Each REST source or JSON
source can name an outbound header. When it does, a `ClientHttpRequestInterceptor`
sets that header from the `DataRequest`'s `SourceCallContext`. When it does
not, nothing is sent. The value travels as a per-request attribute, not a
ThreadLocal, because the fan-out runs on parallel virtual threads.

## Context

- `RestDataSourceAdapter.java` and `ConfiguredJsonDataSourceAdapter.java` get
  a `RestClient` from their caller (`ServerIntegrationsConfiguration`,
  `ExampleIntegrationsConfiguration`, `ConfiguredJsonSourcesInitializer`).
  The first two are outside `Owns`. Install the interceptor on the adapter's
  own copy with `client.mutate().requestInterceptor(...)`, so that no caller
  changes.
- Spring Framework 7.0.9 (Boot 4.1.1, after task 141):
  `RestClient.RequestHeadersSpec.attribute` and `HttpRequest.getAttributes()`.
  The 0.5.0 plan checked both with `javap` against `spring-web-7.0.9.jar` on
  2026-10-07, and both exist. If either is missing on `main` when you start,
  stop and report; do not fall back to a ThreadLocal. *(Amended 2026-10-07;
  this line previously named Framework 6.2.)*
- `RestSources.java` and `ConfiguredJsonSources.java` hand-parse YAML and
  fail at startup, naming the line. A new key follows the same discipline.
- Task 108: `SourceCallContext`, `ExternalCorrelationId.value()`,
  `childTraceparent()`.

## Acceptance

- [ ] Both YAML schemas accept an optional per-source `correlation-header`.
      A name that is not an RFC 9110 token, or that equals
      (case-insensitively) `Authorization`, `Proxy-Authorization`, `Cookie`,
      `Host`, `Content-Length`, `Transfer-Encoding` or `Forwarded`, fails
      startup with `INVALID_CORRELATION_HEADER` and the line number. One
      test per rejected name.
- [ ] `OutboundCorrelationInterceptor` sets the configured header only when
      the request attribute is present. In opaque mode the value is
      `value()`. In traceparent mode it is `childTraceparent()`, a fresh
      parent-id per outbound request. It removes any existing value of that
      header first, so the header is set at most once.
- [ ] An HTTP test against a local stub server asserts that: a source with
      `correlation-header: X-Correlation-ID` receives the id; a source
      without the key receives no such header; a call with no id sends no
      such header; a traceparent-mode call sends a valid `traceparent` with
      the inbound trace-id.
- [ ] Two concurrent fetches with different ids through one adapter instance
      each send their own id. A test asserts this with a stub that records
      headers per path.
- [ ] Nothing in this module reads a ThreadLocal, MDC or
      `RequestContextHolder` for the id. A test greps the module's main
      sources and asserts it.
- [ ] The id is never added to the URL or query string. A test asserts the
      stub saw the unchanged path and query.
- [ ] `RestDataSourceAdapterHttpTest`, `ConfiguredJsonDataSourceAdapterHttpTest`
      and `MutualTlsRestClientsHttpsTest` pass. Edits to them only add
      cases.
- [ ] `mvn -pl data-prism-connectors-rest,data-prism-server,data-prism-integration-tests -am verify`
      passes, and `mkdocs build --strict` exits 0.

## Out of scope

- Inbound header reading. That is task 113.
- *(Superseded 2026-10-07 by D-148-A; see the amendment at the end.)* A global default header for all sources. Each source opts in, because
  every source that receives the id is one more party that can join it.
- Micrometer or OpenTelemetry instrumentation of `RestClient`.
- `dataprism.sources.<name>` properties in `DataPrismProperties`, which
  tasks 103 and 104 own.

## Amendment D-148-A (2026-10-07): global outbound header default

The owner decided (D-148-A) to support an optional global default. This
amendment supersedes the "A global default header for all sources" bullet
under Out of scope. Owns is unchanged: everything below is in
`connectors/rest/**`.

- [ ] `ConfiguredJsonSourcesInitializer` reads
      `dataprism.correlation.outbound.header` through `Binder`, as it already
      does for `fixture-development`. It applies the same rules as the
      per-source `correlation-header`: an RFC 9110 token, and none of
      `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`,
      `Content-Length`, `Transfer-Encoding` or `Forwarded`. Any other value
      fails startup with `INVALID_CORRELATION_HEADER`.
- [ ] A source with its own `correlation-header` uses that header. A source
      without one uses the global value. With neither, nothing is sent. One
      test per case.
- [ ] `RestSources.fromYaml` gains an overload that takes the default. The
      existing signature behaves as before, with no default.
- [ ] The paragraph this task adds to `docs/protect-your-own-api.md` states
      that the global default sends the id to every configured source. Each
      such source is one more party that can join it.

## Attempt 1 — failed

Reviewer: CHANGES (code sound; all D-148-A items met).
- `docs/extending.md:235` says "A source without the key sends nothing". False when `dataprism.correlation.outbound.header` is set: such a source sends the id under the global header. Rewrite to state the global default applies, and that each such source is one more party that can join the id.
- `ConfiguredJsonSourcesInitializer.java:74-76`: a blank global value is silently treated as unset. The amendment says any other value fails startup, and a blank per-source key is already rejected. Refuse a blank global value with INVALID_CORRELATION_HEADER (fail closed), and add a test.
- Follow-up, not this task: also forbid hop-by-hop and framing names (`Connection`, `Upgrade`, `TE`, `Keep-Alive`, `Content-Type`, `Expect`).
