# 108 — Add a validated external correlation id and carry it on DataRequest

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/correlation/** *(new package)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/DataRequest.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/SourceCallContext.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/correlation/** *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/DataRequestTest.java *(new)*
- docs/extending.md *(one new section, "Using the caller's correlation id in an adapter", only)*

## Goal

Organisations want to join a Data Prism audit record, and the source calls it
caused, to their own correlation id (CLID). This task adds the value types:
an `ExternalCorrelationId` that can only exist if it passed a strict
validation, a policy that does the validating (opaque pattern or W3C
`traceparent`), and an explicit per-call `SourceCallContext` on `DataRequest`.
The context is a field rather than a ThreadLocal because the source fan-out
is parallel on virtual threads. Nothing reads a header or writes an audit
record yet; tasks 110, 111 and 113 do.

## Context

- `DataRequest.java` — a three-component record. Adapters get it in
  `DataSourceAdapter.fetch(DataRequest)`. It is built in
  `DefaultContextOrchestrator.requestsPerSource` (task 110 changes that call).
- The CLID must never travel inside `parameters`. Adapters may map
  `parameters` onto URLs, and a correlation id is not a query input.
- `docs/conventions.md#privacy-rules-a-diff-must-satisfy`: no caller-supplied
  value reaches an audit record unvalidated. The correlation id is the only
  caller-supplied string Data Prism will record, so the validation is the
  privacy control.
- W3C Trace Context, `traceparent` version `00`:
  `00-<32 lowercase hex trace-id>-<16 lowercase hex parent-id>-<2 hex flags>`.
  An all-zero trace-id or parent-id is invalid.
- Consumers already planned, so every field they need is here (conventions,
  "Task-file scoping"): task 109 records `ExternalCorrelationId.value()`; task
  110 reads an `InboundCorrelation` from the MCP transport context; task 111
  writes the outbound header and needs `traceparent()` for a child span; task
  113 builds a `CorrelationIdPolicy` from properties.

## Acceptance

- [ ] `CorrelationIdPolicy.opaque(String regex)` and
      `CorrelationIdPolicy.traceparent()` exist. `opaque` throws
      `IllegalArgumentException` containing `INVALID_CORRELATION_PATTERN` for a
      regex that does not compile. The message does not echo the regex.
- [ ] `CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN` is the strict default
      (owner decision C4, 2026-10-07). It accepts only a UUID (canonical form),
      hex of 16–128 characters containing at least one a–f letter (so an
      all-digit card or account number is refused; an all-digit UUID is still
      accepted), or a W3C traceparent. The broad pattern
      `[A-Za-z0-9._:-]{1,128}` is available only when the operator sets it
      explicitly. A test asserts that `jane.doe` is rejected under the default.
- [ ] A fixed ceiling applies before any operator pattern runs: a candidate
      longer than 256 characters, or with any character outside
      `[A-Za-z0-9._:/+=-]`, is rejected whatever the configured pattern
      allows. A test configures the pattern `.*` and asserts that a value
      containing a space, `|`, `"`, `@`, a newline and a non-ASCII letter is
      each rejected.
- [ ] Opaque matching is a full match (`Matcher.matches()`), not a find. A
      test asserts that `abc` with the pattern `b` is rejected.
- [ ] Traceparent mode accepts only version `00` with lowercase hex and
      rejects all-zero trace-id or parent-id, uppercase hex, a version other
      than `00`, and extra trailing fields. One test per case.
- [ ] `ExternalCorrelationId` has no public constructor. It exposes `value()`
      (opaque mode: the header value; traceparent mode: the 32-hex trace-id)
      and `Optional<String> traceparent()` (present only in traceparent
      mode). A test asserts that the only way to obtain one is through
      `CorrelationIdPolicy`.
- [ ] `ExternalCorrelationId.childTraceparent()` in traceparent mode returns a
      `traceparent` with the same trace-id and flags and a new, non-zero
      parent-id. In opaque mode it returns `Optional.empty()`. A test asserts
      the trace-id is preserved and the parent-id differs from the inbound one.
- [ ] `InboundCorrelation.resolve(List<String> headerValues,
      CorrelationIdPolicy policy)` returns `absent()` for no values,
      `rejected()` for a value that fails validation, and `rejected()` for
      more than one value. Otherwise it returns `present(id)`. `rejected()`
      carries no copy of the rejected text, and a test asserts its
      `toString()` does not contain the input.
- [ ] `DataRequest` gains a fourth component, `SourceCallContext context`,
      never null. `SourceCallContext.none()` is the default, and
      `SourceCallContext.externalCorrelationId()` returns
      `Optional<ExternalCorrelationId>`. The three-argument constructor and
      `DataRequest.of(...)` still compile and produce `SourceCallContext.none()`.
      `DataRequest.withContext(SourceCallContext)` returns a copy.
- [ ] `parameters` never contains the correlation id. A test builds a
      `DataRequest` with a context and asserts `parameters()` is unchanged.
- [ ] `mvn verify` over the full reactor passes with no edit outside `Owns`.
- [ ] `docs/extending.md` gains one section showing an adapter reading
      `request.context().externalCorrelationId()` and setting its own
      outbound header. It states that the value has already been validated,
      that an adapter must not log it next to source data, and that no
      adapter should send it unless the source owner expects it.

## Out of scope

- Reading any HTTP header. That is task 113.
- The audit record field. That is task 109.
- Outbound REST headers. That is task 111.
- Micrometer Tracing or OpenTelemetry. Owner decision; not planned.
- `ReservedArguments`. Task 110 adds the reserved names, because it owns
  `docs/tools.md` where they are listed.

## Owner decision C4 (2026-10-07)

The default inbound correlation-id pattern is the strict default, not
`[A-Za-z0-9._:-]{1,128}`. It accepts only a UUID, hex of 16 to 128 characters,
or a W3C traceparent. The broader pattern is available only by explicit
configuration, because it admits name-like tokens such as `jane.doe` and so
lets personal data be smuggled in as an id. The Owns list is unchanged.
