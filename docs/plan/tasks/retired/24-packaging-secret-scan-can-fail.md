# 24 — Make the packaged-server secret scan able to fail

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java

## Goal

Task 17's "ships no development HMAC key" criterion is evidenced by an assertion
that cannot fail: it reads only `BOOT-INF/classes/`, which holds the server's own
five classes, and looks for two literals that exist only in test code and in
`data-prism-example` — both of which the same test already excludes by name two
lines earlier. Give the scan a search space that can contain what it looks for,
and a positive control proving it detects it.

## Context

- `ServerPackagingIT.java:39-45` — the exclusion at `:36-38` and the scan at
  `:40-44`; the scan's iteration is restricted to `BOOT-INF/classes/`, so
  `BOOT-INF/lib/*.jar`, `META-INF/` and the packaged configuration are never read.
- `DataPrismAssembly.java:55` — `DEV_KEY =
  "development-only-key-not-for-any-real-data"`, the actual development key
  material this criterion is about. It lives in `data-prism-example`.
- `StaticSecretKeyProvider` is in `data-prism-pseudonymisation` main, so the
  server jar legitimately ships the *mechanism*. The criterion is about key
  *material*, and the scan must distinguish the two rather than be narrowed
  again when the class name shows up.
- `docs/conventions.md:116-146` — this repository has counted seven
  cannot-fail assertions. This is the eighth. The pattern, stated so a reviewer
  can look for it: **an assertion of absence whose search space cannot contain
  the thing it searches for** — a filter applied before the search (here, a path
  prefix and a name exclusion) that removes every candidate, leaving a test
  that passes by construction. A scan for absence needs a positive control in
  the same test, or it is decoration.
- Baseline: `main` at `99b419b`, 377 tests, 0 failures. `ServerPackagingIT` runs
  under failsafe: `mvn -B verify -pl data-prism-server -am`.

## Acceptance

- [ ] The scan reads every entry in the packaged jar, including the contents of
      nested `BOOT-INF/lib/*.jar` entries, `BOOT-INF/classes/` resources and
      `META-INF/`, rather than one path prefix.
- [ ] A positive control in the same test builds a jar containing a planted
      development key marker inside a nested library entry and asserts the same
      scanner function reports it. Deleting the scan's body makes the positive
      control fail.
- [ ] The marker set includes the literal that `data-prism-example` actually
      ships (`development-only-key-not-for-any-real-data`) and every marker is
      documented in the test with where it comes from; a marker that no build
      artefact in this repository ever emits is not added.
- [ ] The test states, in javadoc, that shipping `StaticSecretKeyProvider` is
      expected and asserts on key material rather than on the class being
      present.
- [ ] A failure message names the offending jar entry and marker, so a real hit
      is diagnosable without re-running with a debugger.
- [ ] `mvn -B verify -pl data-prism-server -am` is green, and the close-out
      reports the mutation used to prove the scan non-vacuous.

## Out of scope

- `data-prism-server` main code and the other three tests in the module — task
  21 owns `ServerStartupTest` and the server's main classes.
- Changing what the server packages, or adding a build-time secret scanner
  plugin. This task makes one existing assertion real.
- Fixing the other open cannot-fail assertions listed in `docs/plan/PLAN.md`.

## Attempt 1 — failed

Tested PASS (378 tests, 0 failures, failsafe ITs confirmed running) but
reviewed CHANGES on a real measured defect. `ServerPackagingIT.java:216` used
`JarInputStream`, which consumes each nested jar's manifest internally and
never returns it from `getNextEntry()`. Against the real built artifact only 3
of 65 nested `META-INF/MANIFEST.MF` entries were visible, versus 65 with
`ZipInputStream`. A development key planted as a manifest attribute in
`BOOT-INF/lib/data-prism-example-*.jar!/META-INF/MANIFEST.MF` therefore
produced an empty scan and a passing test — the same bug class the task exists
to eliminate, an invisible filter shrinking the search space so that a green
suite reads as evidence when it is not.

Record also what the first attempt got right, so the second does not undo it:
the marker-set narrowing to a single literal was verified correct rather than
convenient (`test-only-key` occurs only in test sources and is never packaged;
`DEV_KEY` is a field name redundant with its own value), and the reviewer
independently established that the scanner matches literals inside class-file
constant pools within nested lib jars — stronger evidence than the
implementer's own gut-the-body mutation, because it shows the scan can fire at
the location a real leak would occupy. A second attempt is in progress on the
same branch.
