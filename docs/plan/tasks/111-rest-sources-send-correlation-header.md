# 111 — Send the external correlation id to configured REST sources through an interceptor

**Repo:** `.`
**Depends on:** 108
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
- Spring Framework 6.2 (Boot 3.5.16): `RestClient.RequestHeadersSpec.attribute`
  and `HttpRequest.getAttributes()`. Confirm both exist at this version. If
  either is missing, stop and report; do not fall back to a ThreadLocal.
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
- A global default header for all sources. Each source opts in, because
  every source that receives the id is one more party that can join it.
- Micrometer or OpenTelemetry instrumentation of `RestClient`.
- `dataprism.sources.<name>` properties in `DataPrismProperties`, which
  tasks 103 and 104 own.
