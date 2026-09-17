# 46 — Derive the PII scan's banned values from the stub fixtures

**Repo:** `.`
**Depends on:** 43

**Owns:**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/StubCustomerAdapter.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/StubAccountAdapter.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/StubOrderAdapter.java

**Task 43 must be merged before this task starts.** Task 43 owns
`data-prism-example/**`, which includes every file above. The two must not run
concurrently; this is a sequential dependency on 43 reaching `main`, not merely
on 43's content. Task 45 owns version literals in poms, `server.json`,
`CHANGELOG.md`, the Dockerfiles and three packaging ITs — no intersection with
the four files above, so 45 and 46 may run concurrently once 43 has landed.

This is one small task. It is a single test file, plus whatever minimal
accessor the three stub adapters need to expose their own fixture records. Do
not inflate it into a wave.

## Goal

`PiiLogScanTest`'s `BANNED_VALUES` is a hand-written literal list. It must
instead be derived from the stub adapters' own fixture data, so that changing a
fixture value or adding a field to a fixture DTO automatically extends what the
log scan looks for. The control currently degrades silently every time a
fixture changes, and reads as passing while it does.

## Context

- `data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java:98-101`
  — the hand-written list: two names, two emails, two subject ids.
- The gap, established by a reviewer during task 43 — **do not re-derive it**.
  The list omits `Pat Murphy` and `P. Murphy` (`StubAccountAdapter.java:14`,
  `StubOrderAdapter.java:16` — the account-api and order-api spellings of the
  same person that subject `123` carries, and that `get_entity_context`'s
  merged tree already handles), and also `ACC-1`, `ORD-9`, the balance
  `4200.55` and the free-text order note. A regression that logged a raw
  `holderName` or a raw `customerName` from those two adapters would leave this
  scan GREEN with the raw value sitting in the log line.
- **Patching in the missing literals is explicitly not the fix.** It closes
  today's six omissions and leaves the drift mechanism exactly where it is.
  A reviewer should reject a diff that only extends the list.
- The fixtures: `StubCustomerAdapter.java:13-15`, `StubAccountAdapter.java:12-15`,
  `StubOrderAdapter.java:12-18`. Their DTOs are records —
  `CustomerDto.java`, `AccountDto.java`, `OrderDto.java` — so their components
  are reflectively enumerable. `DataSourceAdapter.fetch(DataRequest)` is public
  and `DataRequest.of(entityType, subjectId)` exists
  (`data-prism-core/.../DataRequest.java:20`); the per-adapter `RECORDS` maps
  and therefore the set of fixture subject ids are private today.
- `DataPrismAssembly.standard()` (`DataPrismAssembly.java:125-129`) is the list
  of adapters the scanned run actually uses. Derive from that list, or from the
  same three adapter types — not from a second hand-kept list of adapters.
- Runtime is not a concern here, and the brief's assumption that this is an
  HTTP run is wrong: `PiiLogScanTest` drives the tool handler in-process, and
  the last recorded surefire run gives the whole class 0.022s, of which
  `fullIntegrationRunLeaksNoPii` is 0.017s. Broadening the banned set multiplies
  a per-field `contains` loop over a handful of captured lines. Confirm the
  class still runs in well under a second and say so; do not spend effort
  optimising it.
- `docs/conventions.md` — the rule against an assertion that cannot fail, and
  rule 7: no real personal data. The fixtures are invented test data and stay
  that way.
- `AUDIT_KEYS`'s javadoc at `PiiLogScanTest.java:103-108` says "twenty
  placeholders" for a 19-name list; `PLAN.md` records that it should be
  corrected the next time this file is touched. This task touches it, so
  correct it. Do not touch the tautological sum assertion at lines 191-193 —
  that is a separate recorded item and a separate-shaped change.

## Acceptance

- [ ] `BANNED_VALUES` is no longer a list of string literals naming fixture
      values. It is computed at test time from the stub adapters' fixture
      records, and a reader can see that adding a record component or changing
      a fixture value extends it without editing the test.
- [ ] The derived set covers every sensitive-bearing field the three stub
      adapters expose for every fixture subject — at minimum the three name
      spellings (`Patrick Murphy`, `Pat Murphy`, `P. Murphy`), both emails,
      both subject ids, both account ids, both order ids, both balances and
      both order notes. Any value the derivation deliberately excludes is
      excluded by a named, commented rule (not by omission), and the comment
      states the reason.
- [ ] A test asserts the derived set is non-empty **and** contains specific
      named fixture values — at least `Pat Murphy`, `P. Murphy`, `ACC-1`,
      `ORD-9` and the account balance. A derivation that silently returns an
      empty or near-empty set must redden this test. This criterion exists
      because a vacuously-empty derived set would pass every other test in the
      class while protecting nothing, which is the exact failure being fixed.
- [ ] Non-vacuity, proven by mutation rather than argued: in a scratchpad clone,
      make a production code path log a raw fixture value that is *not* in
      today's hand-written list (a raw `holderName` or the order `note` is the
      natural choice), run `PiiLogScanTest`, and record that
      `fullIntegrationRunLeaksNoPii` goes RED naming that value. Revert, re-run,
      record green, and confirm `git status` is clean and the tree byte-identical
      (`git diff --stat` empty). Both the failing assertion message and the
      confirmation of the revert go in the task's close-out report.
- [ ] The scan is run once against unmodified `main` with the broadened set and
      the result reported honestly. If a broadened value reddens the scan
      against today's code, that is a finding to report, not something to
      silence with an exclusion: state what field leaked, in which log line, and
      stop for a decision rather than adding an exemption.
- [ ] Derivation reads only the stub adapters' own fixture data. Nothing is
      pulled from configuration, the environment, a resource file, an HTTP
      response or any other source, and no new literal resembling a real
      person, address, credential or token is introduced anywhere
      (`docs/conventions.md`, rule 7).
- [ ] If the stub adapters gain an accessor so the test can enumerate fixture
      records, it is the smallest one that works, it exposes fixture data only,
      and it changes no adapter's behaviour under `fetch`. `EndToEndTest`,
      `WorkedExampleTest` and `McpHttpEndToEndTest` stay green unchanged.
- [ ] `AUDIT_KEYS`'s javadoc no longer claims twenty placeholders for a list of
      nineteen: either the count is corrected or the `seq={}/{}` fold is
      explained.
- [ ] `mvn -B clean verify` green across the full reactor, with the test count
      reported against the post-43 baseline.

## Out of scope

- Adding the missing literals to `BANNED_VALUES` as a shortcut. If the
  derivation proves hard, stop and report — do not fall back to a longer
  hand-written list.
- The tautological `auditCount + nonAuditCount == total` assertion at
  `PiiLogScanTest.java:191-193`. Recorded separately in `PLAN.md`; leave it.
- Changing what the stub fixtures contain, or adding a fourth stub adapter.
  The derivation must work with whatever the fixtures happen to hold.
- The scanning machinery itself: `findLeaked`, `findLeakedAcrossLines`,
  `EXEMPT_SHAPES`, the shape patterns and the audit-field splitter all stay as
  they are. Only where the banned set comes from changes.
- Any version literal, `CHANGELOG.md` entry or pom edit — task 45 owns those.
- Any other test module. If a broadened set implies a similar drift problem in
  `QuickstartSmokeIT` or `ServerPackagingIT`, report it; do not fix it here.
