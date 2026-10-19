# 193 — Enforce maxRecords and maxResponseBytes, refusing when either is exceeded

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 190
**Decided (owner, 2026-10-09):**
- D-070-5 (a): a record is each source's top-level record plus each object
  inside an array; exceeding `max-records` refuses (truncation is 0.9).
- D-070-6 (a): a conservative upper bound computed in the orchestrator before
  the audit event, with no mapper; one `DENY:RESPONSE_TOO_LARGE`.
- Reconciled with D-070-2 (per-source refusal, task 190): exceeding either cap
  refuses the whole call. The caps are per response and are not privacy
  refusals, so per-source isolation does not apply. Sources marked `REFUSED`
  contribute nothing to the response and count zero toward either cap.
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ResponseSize.java (new, name free)
- data-prism-orchestration/src/test/**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/limits/RequestLimits.java (Javadoc only)
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ResponseLimitRefusalTest.java (new)
- docs/tools.md (refusal-code entries only)
- docs/plan/tasks/193-enforce-record-and-response-size-limits.md (Outcome section only)

## Goal
`RequestLimits` validates `maxRecords` and `maxResponseBytes` in its
constructor, and nothing in main code reads either. A response can therefore
be any size. This task enforces both on the per-source result shape task 190
produced. Exceeding either refuses the whole response with a stable code and an
audited `DENY:<code>`; truncation with markers is 0.9 work (D-RM-12), so in 0.7
the cap fails closed.

## Context
- `RequestLimits.java:15-26,46-48` — the declared but unread fields.
- `DefaultContextOrchestrator.java` after task 190: the point after validation
  and before the `ALLOW` audit is where a refusal is still audited once as
  `DENY:<code>` (see the catch block around `:219-238` on `main` today).
- Architecture boundary 1 (`docs/architecture.md:146-185`): orchestration may
  not construct a Jackson mapper, call `JsonMapper.shared()` or construct a JSON
  factory or generator. Per D-070-6 the size is a mapper-free walk that returns
  an upper bound on the compact UTF-8 serialisation of what the tool will emit.
- `RefusalCodes` — code format. Codes: `RESPONSE_TOO_LARGE` (named by D-070-6)
  and `TOO_MANY_RECORDS` (task 200 registers both).
- Limits are bound from configuration by task 189; this task tests with
  `RequestLimits` constructed directly and does not depend on 189.

## Acceptance
Write the tests first; each must fail on `main` before the change.
- [ ] A response one record over `maxRecords` is refused with the record code;
      one at exactly `maxRecords` is returned. Same pair for `maxResponseBytes`.
- [ ] A response whose `REFUSED` source would, if counted, exceed either cap
      is returned: refused sources count zero (D-070-2 reconciliation).
- [ ] Each refusal writes exactly one audit event, `DENY:<code>`, and no `ALLOW`;
      the client error carries the code and `correlationId` and no value or size
      derived from payload content beyond the configured limit.
- [ ] The size measure is an upper bound: a property test over generated trees
      (unicode, escapes, nesting, empty arrays) shows measured bytes are greater
      than or equal to the length of the tool's actual serialisation, and within
      a stated tolerance; `ResponseLimitRefusalTest` checks the bound against
      the real `DataPrismObjectMapper` output through the tool.
- [ ] No new mapper, factory or generator: `ArchitectureTest` passes unchanged.
- [ ] `RequestLimits` Javadoc defines a record and the size measure.
- [ ] `docs/tools.md` lists both codes with one line each; `mkdocs build --strict` exits 0.
- [ ] `./mvnw -B verify -pl data-prism-orchestration,data-prism-mcp,data-prism-architecture -am`
      exits 0; Outcome records the count and commit.
- [ ] The Outcome carries CHANGELOG `### Changed` lines and a migration note
      (a response that used to be returned may now be refused; how to raise the
      limits through task 189's properties) for task 201.

## Out of scope
- Truncation, paging or markers (0.9).
- Binding the limits from configuration (task 189).
- The fan-out, `maxSources` behaviour or the read budget.
- Audit-entry size handling (0.9).
