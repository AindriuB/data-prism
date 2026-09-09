# 10 — Make the mTLS refusal assertion hold on Linux without weakening it

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/MutualTlsRestClientsHttpsTest.java

## Goal
`main` is red on GitHub Actions: run 34383330987 on commit `3f6a59a` failed
`MutualTlsRestClientsHttpsTest.clientWithoutCertificateIsRefused`. The
assertion encodes one platform's spelling of a refused handshake — it demands
an `SSLException` in the cause chain, and on Linux the server closes the TCP
connection before the client reads the TLS alert, so the client surfaces
`ResourceAccessException: ... HTTP/1.1 header parser received no bytes`
instead. The handshake was still refused; only the observable differs. Find
the property that is true of a server-side handshake rejection on both
platforms and assert that.

## Context
- `data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/MutualTlsRestClientsHttpsTest.java:159-177`
  — the failing assertion.
- Same file, `:137-147` — the positive counterpart. It uses the same server,
  the same trust store and the same base URI, differing only in that a real key
  manager is installed. That differential is the load-bearing part of the proof
  and must survive unchanged in substance.
- Same file, `:149-158` — the javadoc that explains why only the absent
  certificate can be the cause. Keep it true of whatever you assert.
- CI: `.github/workflows/build.yml` runs on Linux. Every local verification of
  this test to date ran on Windows, which is exactly how this reached `main`.
- History, as context and **not** as work: task 08 (`docs/plan/HISTORY-INDEX.md`
  row `2026-09-09 | 08`) rewrote this test from a vacuous
  `isInstanceOf(RuntimeException.class)` — which passed on a URL typo or a
  missing keystore — to the current form, and a reviewer verified the
  tightening by pointing the base URI at a closed port and confirming the test
  then failed. That was correct in substance and caught a genuine vacuity. The
  defect here is narrower than "08 was wrong", and the `HISTORY.md` record of
  08 should not be read as invalidated.

Determine the cross-platform property yourself: read how the JDK's
`HttpClient` surfaces a server-side handshake rejection on Linux and on
Windows before choosing a shape. Do not simply widen the assertion until it
goes green.

## Acceptance
- [ ] `clientWithoutCertificateIsRefused` passes on Linux in GitHub Actions and
      on Windows locally, and the task records the green Actions run id in its
      completion report along with the branch it ran on. A green Windows run
      alone does not satisfy this item.
- [ ] `factoryBuiltClientCompletesTheHandshake` still passes and still differs
      from the refusal test only by the key manager, against the same server,
      trust store and base URI.
- [ ] Temporarily pointing `base` at a closed port makes
      `clientWithoutCertificateIsRefused` **fail**; this is demonstrated and the
      observed failure message quoted in the completion report. The temporary
      change is not committed.
- [ ] Temporarily pointing the request at a path the server has no context for
      (a 404, not a refusal) makes `clientWithoutCertificateIsRefused` **fail**;
      demonstrated the same way and not committed.
- [ ] The assertion is not `isInstanceOf(RuntimeException.class)`, `isNotNull()`
      or any equivalent "some exception was thrown" form.
- [ ] The test carries a comment or javadoc stating, in prose, what property is
      being asserted and why it is the cross-platform one — naming both the
      Linux and the Windows observable — so someone seeing it fail on a third
      platform knows what the assertion means.
- [ ] The completion report lists any other test in the repository that asserts
      on a platform-specific exception type or message (grep is enough; say so
      if there are none). These are reported, not fixed.

## Out of scope
- Fixing any other platform-specific assertion found by the survey above.
- `MutualTlsRestClients`, `TlsSettings`, `RestDataSourceAdapter` or any other
  production code. If the test cannot be made to pass without a production
  change, stop and report rather than editing outside `Owns`.
- The module POM's surefire environment configuration.
- The two `PiiLogScanTest.java` items listed under "Small open items" in
  `docs/plan/PLAN.md`.
- `.github/workflows/build.yml` — the CI matrix is not being changed here.
