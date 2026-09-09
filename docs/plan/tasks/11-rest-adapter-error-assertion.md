# 11 — Make the REST adapter's server-error assertion name the failure it demonstrates

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapterHttpTest.java

## Goal
`RestDataSourceAdapterHttpTest.serverErrorPropagates` asserts
`isInstanceOf(RuntimeException.class)`, which any failure satisfies — including a
server that never started. It is the seventh cannot-fail assertion counted in this
repository and the same vacuous form task 08 removed from the neighbouring mTLS
test. Replace it with an assertion that only the 500 response can satisfy.

## Context
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapterHttpTest.java:91-98 —
  the test, and the `/customers/broken` stub at :48-52 that returns a bare 500.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapter.java —
  `fetch` catches only `HttpClientErrorException.NotFound`; everything else
  propagates from `RestClient.retrieve().body(...)` unwrapped. Read this and the
  stub before deciding what to assert. Do **not** assume it mirrors the mTLS case:
  that one was a transport failure with a platform-dependent observable, this one
  is an HTTP status from a loopback server and is deterministic on both platforms.
- docs/conventions.md:116-147 — the enumerated list of cannot-fail assertions;
  `RestDataSourceAdapterHttpTest.java:97` is its fourth item.
- docs/plan/HISTORY.md — grep `Task 10` — why a fix here must not pin a
  platform-specific exception type or JDK message string.
- Baseline: `main` at d1460ec, green, 326 tests, `mvn -B verify` from the repo root.

## Acceptance
- [ ] `serverErrorPropagates` asserts the specific exception the 500 stub
      produces — established by running the test and reading the actual thrown
      type, not inferred — including its status code. A bare
      `isInstanceOf(RuntimeException.class)` no longer appears in the file.
- [ ] The new assertion distinguishes a rejected request from an unreachable
      server. Proven in a scratchpad clone with its own `target/`: point the
      adapter at a port with nothing listening, run the test, record that it
      fails, revert. Report the failure output.
- [ ] The assertion contains no platform-specific exception type and no literal
      JDK or OS message string; it is the same on Windows and on Linux CI.
- [ ] `notFoundIsNoData` and `traversalDoesNotEscapeOverTheWire` are unchanged
      and still pass.
- [ ] `mvn -B verify` from the repo root is green, still 326 tests (this task adds
      no test and removes none). Any change to that count is explained.
- [ ] The report gives the exact replacement prose for `docs/conventions.md`'s
      fourth enumerated item (currently at :139-142) marking this one fixed and
      naming what it now asserts, and for the `PLAN.md` bullet at :130-136.
      `scribe` applies both at `/record` — this task does not edit any file
      under `docs/`.
- [ ] The branch reaches `main` only through a pull request whose head commit has
      a green `build` check. The implementer pushes the branch, opens the PR, and
      stops there: implementers never merge, never push to `main`, and never merge
      their own PR. Merging is `/record`'s, after `/verify`. See
      `docs/workflow.md` Phase 4.

## Out of scope
- The two `PiiLogScanTest` items open in `PLAN.md` (the tautological sum at
  :191-193 and the 19-versus-twenty javadoc). Different file, different owner.
- Any change to `RestDataSourceAdapter` main code. If the propagated type looks
  wrong, report it; do not fix it here.
- Adding new cases to this test file beyond what the acceptance requires.
