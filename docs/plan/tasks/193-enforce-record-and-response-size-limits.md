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

## Outcome — implementation ready for review, 2026-10-19

Implementation commit: `e957f7940`, on `codex/193-response-limits`, based on
`plan/0.7.0` at `98fd4248`. Not merged or retired.

Both limits run after validation and response assembly, inside the existing
DENY audit boundary and before ALLOW. The returned context and, for comparison
requests, its comparison projection are both bounded.
`ResponseSize` is package-private and constructs no mapper, JSON factory or
generator. It counts returned source roots and object array elements, and walks
JSON iteratively. The byte bound covers the compact context body, including
source keys/statuses and findings. Comparison requests also measure the projected
identity and each finding's `consistent` boolean, enforcing the larger body
bound. Ignored bookkeeping and correlation fields,
the transport envelope and duplicate MCP representations are outside that
measure. Withheld payloads contribute zero; their existing status entries still
occupy bytes in the emitted body and are therefore counted. Ordinary scalar
encoding is exact except that each surrogate code unit reserves six bytes;
supported JSON trees are bounded within three times their compact UTF-8 size.
Opaque nodes whose custom serialisation cannot be bounded refuse conservatively.

Verification in the task's isolated worktree:

- Tests first against the integration baseline: the byte, record and nested
  record refusal tests failed because no exception was thrown (3 failures,
  0 errors). The withheld-source compatibility case already passed.
- Required `./mvnw -B verify -pl data-prism-orchestration,data-prism-mcp,data-prism-architecture -am`:
  all 20 reactor modules passed, 1,832 surefire and 17 failsafe tests, no
  failures/errors/skips. Architecture tests unchanged.
- Added three further tests for combined-source totals and validation-withheld
  payloads after that run; the final focused suite passed all 14 tests. No
  runtime behaviour changed after the full run.
- Generated-tree test: 2,000 deterministic cases cover Unicode, escaping,
  numeric values, nested containers and empty arrays. Additional tests cover
  numeric extremes, binary encoding, findings/status metadata and deep traversal.
- `ResponseLimitRefusalTest` drives the real MCP tool and its designated mapper:
  the first accepted byte limit bounds the emitted body; both refusals return
  exactly the code/path with the audit correlation id, one DENY and no ALLOW.
- Negative proof: disabling both guards fails
  `recordLimitAllowsEqualityAndRefusesOneMore`,
  `byteLimitAllowsEqualityAndRefusesOneLessThanAsciiBody`, and the combined-source
  checks. Maven skipped downstream MCP after those expected failures, so a
  separate mutation run proved
  `bothLimitErrorsCarryOnlyTheCodePathAndAuditedCorrelation` fails too. Source
  restored in `finally`; all 14 focused tests passed again afterward.
- `./mvnw -B -Prelease -DskipTests -Dgpg.skip package`: passed, no publication.
- `mkdocs build --strict` and all six standalone `check_*.py` docs guards passed
  with `DP_REQUIRE_NO_STUBS=true`; `git diff --check` passed.

### Changed (for task 201)

- Enforce the configured record and response-size caps before returning a
  context. Exceeding either refuses the whole call with `TOO_MANY_RECORDS` or
  `RESPONSE_TOO_LARGE`, respectively, and one correlated `DENY:<code>` audit event.

### Migration note (for task 201)

A response previously returned can now be refused. Raise
`dataprism.limits.max-records` or `dataprism.limits.max-response-size` when the
larger response is intended. Equality is allowed. Record totals exclude withheld
payloads; the size cap covers the compact context body including status entries
and findings, plus the comparison projection when requested, not the MCP envelope.
Comparison requests enforce the larger of the context and comparison bounds,
so omitted context fields can make a comparison refusal conservative. Surrogate-heavy text may be refused before
its exact encoded size reaches the cap because the bound is conservative.

### Review correction — comparison response size

The comparison tool adds a `consistent` boolean to every finding and projects
source fields into `identity`. The original context-only bound could therefore
allow a comparison body larger than the cap. The orchestrator now measures both
bodies for comparison requests before auditing, using no mapper or copied tree.

The new real-tool regression reproduced the defect before the fix: a cap of
1,763 bytes allowed a 1,878-byte ASCII comparison body with ten findings. It now
checks ASCII equality, Unicode and escaped values, text/structured equivalence,
and one correlated `DENY:RESPONSE_TOO_LARGE` with no payload one byte below the
accepted bound. All 15 focused response-limit tests pass.

After the correction, the required 20-module `verify` command passed again:
1,836 surefire and 17 failsafe tests, zero failures/errors/skips, including the
unchanged architecture tests. Strict MkDocs and `git diff --check` also passed.
Logs: `logs/193-comparison-red.log`, `logs/193-comparison-focused.log`,
`logs/193-comparison-verify.log`, and `logs/193-comparison-docs.log`.
