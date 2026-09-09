# 09 — Make the shipped defaults the ones the tests assert, and clear three pieces of debt

**Repo:** `.`
**Depends on:** 07
**Owns:**
- data-prism-example/pom.xml
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/WorkedExampleTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ShippedDefaultsTest.java
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java

Nothing else under `data-prism-example/src/**/java/**/example/http/**` — that
one file is the exception, and it is listed above — and not
`ArchitectureTest.java`, `src/main/resources/**` or `src/test/resources/**`.
Those were task 07's; 07 merged on 2026-09-09 and is retired to
`docs/plan/tasks/retired/`, and no other open task claims them, but this task
still does not touch them.

**Why `PiiLogScanTest` is here.** It was task 07's, it carries a residual gap 07
did not close, and when 07 retired the gap was left recorded in `PLAN.md` and
owned by nobody. 09 is the only open task and no other task file names the
file — checked against `docs/plan/tasks/` on 2026-09-09 — so it is folded in
here as the fifth item rather than left to rot or spun out into a task of its
own.

**Ownership note.** `data-prism-example/pom.xml` is also in task 07's `Owns`.
That is why this task depends on 07 rather than running beside it: 09 starts
from a tree in which 07's pom edits are already merged, and adds one dependency
element to them. Do not start 09 before 07 has merged.
`ExampleApplication.java` is in 07's `Owns` too, for the one item where 07 derives
stdio's `productionDeployment` flag from the active Spring profile — same reason,
same rule: 09 starts from the merged file, keeps that wiring, and re-checks the
line numbers cited below against it.

## Goal
Two of these four are the same defect this project keeps producing: a control
that exists, is tested, and is not what actually ships. `DataPrismAssembly`
mints an investigation context holding `EXPOSE_SOURCE_NAMES` by construction, so
the documented worked example prints real source names while the application
path masks them; and the shipped `developer` role's capability set is asserted
on by nothing, because every capability test builds its own policy. Both are
fixed the same way — make the shipped default the safe one, and put a test on it
that fails if someone widens it again.

The other two are ordinary debt: a stale class javadoc, and a dependency used
directly but declared transitively. They are here because they are small, they
touch files this task already owns, and two implementers in a row have been told
to leave the javadoc alone because they did not own the module.

The fifth is the same shape as the first two one level further out: a control
that exists, is tested, and does not cover the case it was written for.
`PiiLogScanTest` scans a full integration run's log for values that must never
appear, and its matcher cannot see a banned value glued to word characters. The
one leak shape this system is most likely to produce — a pseudonymiser that
concatenates a raw subject id onto a prefix instead of replacing it — is exactly
the shape it misses.

## Context
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java:103-109 —
  the constructor comment says `EXPOSE_SOURCE_NAMES` is there "because this example's
  sources are fictional and its output is meant to be read"; task 06 superseded that
  reasoning in `ExampleApplication` and could not reach this file
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java:31-34,49-50 —
  the scoped-down caller task 06 built, and the class javadoc that already claims
  "no `EXPOSE_SOURCE_NAMES`". The claim is currently true, and nothing keeps it true
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/WorkedExampleTest.java:104,142 —
  the two assertions that name `customer-api`, `account-api` and `order-api` directly
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java:245 —
  the only other caller of `investigationContext()`; there is no production caller
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceAliasing.java:40-45
  and DefaultContextOrchestrator.java:230-233 — aliasing is applied both to the `sources()`
  keys and to the names correlation puts into findings, so both change together
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java:12-22
  and :40-47 — the stale class javadoc, and the `@throws` on `resolve` that already
  documents `UNKNOWN_PURPOSE` correctly
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/SecurityPolicy.java:44-52 —
  `capabilitiesFor(Set<String> roles)`, which is what a test on the shipped policy asserts through
- pom.xml:110-114 — `data-prism-security` is already version-managed in the parent
- docs/conventions.md:111-113 and :250-262 — the mutation rule, and the reviewer-isolation
  rule that goes with it
- docs/conventions.md:265-287 — "Concurrent Maven verification", added 2026-09-09 after two
  `mvn` processes sharing one `target/` produced a false PII-leak alarm in this very test
  that took four agents and about a day to disprove. Every mutation in item five runs in a
  scratchpad clone with its own `target/`
- docs/pack.md:2126-2160 — §64, the worked example this task keeps honest
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java:82-85 —
  `BANNED_VALUES`, including the bare ids `123` and `456`; :196-214 — `findLeaked`, the
  `\b`-bounded matcher whose gap item five closes; :189-194 — `withoutTimestamps`, the
  other half of the flakiness fix, which stays
- data-prism-audit/src/main/java/io/github/aindriub/dataprism/audit/Slf4jAuditSink.java:19-27 —
  the one `AUDIT.info` call and its fixed twenty-placeholder format. Read this before
  designing item five: the "structured fields" the review suggested exist only as a rendered
  `key=value` string, and two of the values (`sources=`, `rejected=`) are collection
  `toString` output containing spaces and commas
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java:201,313 —
  `subjectToken` is the HMAC pseudonym and is what reaches `audit(...)`. Swapping it for
  `request.subjectId()` is item five's proven mutation

## Decision to implement, not to revisit
`investigationContext()` has no production caller: `ExampleApplication` stopped
using it in task 06, and the only remaining callers are `WorkedExampleTest` and
`EndToEndTest:245`. So the fix goes at the factory, not at the test — the
assembly stops minting a context that carries `EXPOSE_SOURCE_NAMES`, and the
worked example is updated to show what the application shows. Do not instead
build a scoped-down caller inside `WorkedExampleTest` and leave the factory as
it is: a factory that hands an unmasking context to anyone who asks is the same
hazard one level up.

## Item five: what must be true of the log scan, not how to build it

The gap. `findLeaked` matches `\b` + the value + `\b`, so a banned value bounded
by word characters is invisible: `subject=SUBJ-123a7f9` and `id_456_x` both pass
the scan today, verified at review. No current code path emits either shape. The
first is precisely what a pseudonymisation bug that prefixes rather than replaces
would emit, and catching that is the reason this test exists.

The gap is not a mistake to revert. Word boundaries were themselves a fix: bare
`String.contains` collided with the random hex in `event=`, `correlation=`,
`hash=` and `prev=` and failed about one run in four. Anything that reintroduces
that rate is worse than the gap, because a leak detector that cries wolf is one
nobody reads. Both properties have to hold at once.

The review suggested scanning the audit line's structured fields rather than raw
text. Judge that against `Slf4jAuditSink` before adopting it. What the sink
actually emits is a single SLF4J call with a fixed sequence of twenty
`key=value` placeholders, rendered into one line behind the simplelogger prefix;
the "fields" are recoverable, but only by splitting on that known key sequence,
because `sources=` and `rejected=` render collections as `[a, b]` and would break
any split on whitespace. If a different approach holds the two properties below,
take it — but say in the close-out why, and the approach must still distinguish
per-field, since the whole difficulty is that some fields are legitimately full
of random hex and one of them (`subject=`) is the field a leak would land in.

The captured output is not only audit records. `captureLogOutput` takes all of
`System.out` and `System.err`, so ordinary application log lines — Hazelcast
startup, `DefaultContextOrchestrator`, `ProfilePrivacyPolicyResolver` — are in
the same string. A fix that parses well-formed audit records and stops there
silently leaves those lines on the old raw-text path. That is acceptable as an
outcome but not as an accident: the partition has to be explicit, every captured
line has to fall into exactly one path, and the residual on the non-audit path
has to be written down rather than implied.

## Acceptance
- [ ] No method on `DataPrismAssembly` returns an `InvestigationContext` holding
      `Capability.EXPOSE_SOURCE_NAMES`. `ShippedDefaultsTest` asserts that
      `DataPrismAssembly.standard().investigationContext()` has a capability set of
      exactly `{GET_ENTITY_CONTEXT}`, and that `has(Capability.EXPOSE_SOURCE_NAMES)` is
      `false`. The superseded comment at DataPrismAssembly.java:103-106 is replaced by one
      stating the current reason rather than the old one.
- [ ] `WorkedExampleTest` passes against the masked default: the assertions at :104 and
      :142 assert the scope-local aliases rather than `customer-api`, `account-api` and
      `order-api`, and a further assertion states that none of those three real names
      appears in `response.sources()`, in `response.findings()`, or in the serialised
      entity. The alias assertions cannot pass on an empty or absent collection — assert
      exactly three distinct aliases, and assert that the alias for a given source is the
      same string in `sources()` as in the finding that names it.
- [ ] `ExampleApplication`'s shipped `SecurityPolicy` is reachable from a test — a named
      static factory rather than a local built inside `main` — and `main` uses that same
      factory, so the shipped default has one definition and not two.
- [ ] `ShippedDefaultsTest` asserts that `capabilitiesFor(Set.of("developer"))` on that
      shipped policy equals exactly `Set.of(Capability.GET_ENTITY_CONTEXT)`, and separately
      that it does not contain `Capability.EXPOSE_SOURCE_NAMES`. The test is proved able to
      fail: add `EXPOSE_SOURCE_NAMES` to the shipped role, record the failing assertion
      message in the close-out, revert. Per the reviewer-isolation convention, note in the
      close-out that this is a mutation-shaped proof, so a reviewer must read from
      `git show` rather than the working tree while it runs.
- [ ] `ScopeResolver`'s class javadoc gains one or two sentences saying that `resolve`
      refuses a caller whose purpose is not in the configured list, failing closed with
      `UNKNOWN_PURPOSE`. Nothing else in that file changes — no signature, no behaviour, no
      reformatting of untouched lines. The diff for this item is comment-only.
- [ ] `data-prism-example/pom.xml` declares `io.github.aindriub:data-prism-security`
      directly, with no `<version>` element, alongside the dependencies task 07 added.
      `mvn -B dependency:tree -pl data-prism-example` shows it at depth one.
- [ ] `PiiLogScanTest` catches a banned value that is glued to word characters. A test
      pushes `subject=SUBJ-123a7f9` and `id_456_x` through `LoggerFactory.getLogger("dataprism.audit")`
      in the same shape `scannerIsNotVacuous` already uses, and the scanning method reports
      `123` and `456` respectively. Both of these fail on today's matcher; state in the
      close-out that they were run against the unchanged matcher first and observed to fail.
- [ ] Every captured line is scanned by exactly one path, and the partition is asserted, not
      assumed. The test states which lines it treats as audit records and which it does not,
      asserts both classes are non-empty in a real run, and asserts that the two counts sum to
      the number of captured lines — so a line that matches neither cannot be dropped
      unscanned. The non-audit path is at least as strict as today's `\b` matcher, and the
      close-out names in one sentence what that path still cannot catch.
- [ ] Where the scan relies on a field's value being random hex to skip it, the skip is
      earned by the whole value matching a shape pinned in the test — not by the value merely
      looking hex-ish, and not by the field's name alone. A value in an exempt field that does
      not match that field's pinned shape whole is scanned like any other. Assert this
      directly: a synthetic audit line carrying `SUBJ-123a7f9` in a field that is normally
      exempt is still reported.
- [ ] Field splitting survives the sink's own output. A synthetic line whose `sources=` value
      is a multi-element collection (`[alpha, beta]`, with the space and comma
      `Slf4jAuditSink` really produces) is parsed with that whole collection as one field
      value, and a banned value placed inside it is reported.
- [ ] The scan is no flakier than it was. Record in the close-out the exact command and a
      count of at least 30 consecutive passes of `PiiLogScanTest`, run strictly sequentially
      inside a single Maven process, zero failures. Not 30 parallel runs, not 30 across
      concurrent processes: `docs/conventions.md:265-287` exists because two `mvn` processes
      over one `target/` produced a false leak alarm in this test. If any run in the sequence
      fails, the count restarts; report the real number, never a rounded-up one.
- [ ] The scan still catches what it already caught. In a scratchpad clone of the repo with
      its own `target/` — never in the worktree — swap `subjectToken` for `request.subjectId()`
      at DefaultContextOrchestrator.java:313, run `PiiLogScanTest`, and record the failure
      message showing both `123` and `456` reported from a well-formed `event=` line. Revert
      by deleting the clone. `scannerIsNotVacuous`'s existing `Patrick Murphy` case still
      passes unchanged. Note in the close-out that this item is a mutation-shaped proof, so a
      reviewer reads from `git show` rather than the working tree, and that the mutation never
      touched the worktree's `target/`.
- [ ] `mvn -B verify` passes from the repo root with no test failures and no skipped
      tests, and the test count is not below the tip of `main` at the time 09 starts.
      That number is 318, measured on `main` at 7db0491 after 07 merged, 0 failures and
      0 skips. Re-measure before making any change here and report the number you saw;
      if it is not 318, say so rather than quoting this line.

## Out of scope
- Anything under `data-prism-example/.../example/http/**` other than `PiiLogScanTest.java`,
  and `ArchitectureTest`, `application.yaml`, or the HTTP application's own `SecurityPolicy`.
  That policy was task 07's and is loaded from yaml; this task governs only the stdio
  development default built in `ExampleApplication`.
- Changing `Slf4jAuditSink`, `AuditEvent`, or the audit line's format to make it easier to
  parse — including switching to structured/JSON logging. Item five adapts the test to the
  sink that ships; a sink change is a separate decision with its own downstream effects on
  the hash chain and on operators' log pipelines. If the format genuinely blocks the item,
  report that as a finding.
- Widening `BANNED_VALUES` with new fixture values, or extending the scan to modules other
  than the example integration run. Item five changes how the existing values are matched
  and nothing else.
- Removing `withoutTimestamps`, or relaxing the `contains("event=")` non-vacuity guard at
  PiiLogScanTest.java:99-102. Both are load-bearing parts of the earlier flakiness fix.
- Removing or renaming `investigationContext()`, and any change to `InvestigationContext`,
  `Capability` or `SourceAliasing` in the core and orchestration modules. Narrow the
  default the example mints; do not reshape the type system around it.
- Any other javadoc in `data-prism-security`, and any behaviour change in `ScopeResolver`.
  Item four is a comment fix and stops there.
- Granting `EXPOSE_SOURCE_NAMES` anywhere as a configurable example role. If the worked
  example turns out to need real source names to remain readable, that is a finding to
  report, not a grant to make.
