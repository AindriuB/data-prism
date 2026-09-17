# 47 — Close the PII scan's reflection-depth and word-boundary holes

**Repo:** `.`
**Depends on:** 46

**Owns:**
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/OrderDto.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/StubOrderAdapter.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DeliveryDto.java *(new)*

**Task 46 must be merged before this task starts.** 46 owns `PiiLogScanTest.java`
and `StubOrderAdapter.java`; this is a sequential dependency on 46 reaching
`main`, not merely on its content. Task 45 owns version literals across the tree
and no file above, so 45 and 47 do not intersect and may run concurrently.

This is one task. Both holes live in the same file, both are the same shape, and
one of them needs a fixture the other one's proof also reads. Do not split it,
and do not grow it.

## Goal

A reviewer found two remaining ways `PiiLogScanTest` narrows its own coverage
without saying so — the same disease task 46 cured for the hand-written banned
list. The derivation reflects exactly one level deep, so a nested or collection
fixture component would enter the banned set as its `toString` and leave its
leaf values unbanned. And `findLeaked`'s trailing `\b` makes any banned value
ending in punctuation unmatchable on a plain log line, which today silently
exempts both order notes. Close both, and prove each one closed by watching it
fail first.

## Context

Line numbers are as of task 46's branch; re-locate by member name after 46
merges rather than trusting the number.

- `PiiLogScanTest.java:141-167` — `deriveBannedValues` / `addFixtureValues`.
  Every component is read with its accessor and added as `String.valueOf(value)`.
  Non-String scalars are right: `new BigDecimal("4200.55")` renders `"4200.55"`.
  A nested record would enter as `DeliveryDto[courierRef=…]` and a collection as
  `[a, b]` — the string a log line will never contain — so the leaf values
  inside become unbanned while the set stays non-empty and
  `derivedBannedSetIsNonEmptyAndCoversKnownFixtureValues`
  (`PiiLogScanTest.java:285`) stays green. Adding a flat field auto-extends the
  control; adding a nested one auto-narrows it, with no signal. That asymmetry
  is the bug.
- `PiiLogScanTest.java:537-545` — `findLeaked` wraps each value in
  `\b` … `\b`. A trailing `\b` after a non-word character asserts that a **word**
  character follows, so `"No issues raised."` cannot match at end of line or
  before a space. Only the plain path is affected:
  `findLeakedAcrossLines` (`:556`) sends audit-shaped lines to
  `findLeakedInAuditLine`, which compares per field with `contains` and is
  unaffected — which is why the two order notes went unflagged during 46's
  mutation proof despite being in the derived set. Consequence: a raw order note
  in an ordinary application log line passes GREEN. The reviewer's fix is
  `(?<!\w)` / `(?!\w)` lookaround bounds.
- The `\b` bounds are load-bearing, and the reason is written down at
  `PiiLogScanTest.java:114-129`: `"123"` and `"456"` are banned, audit lines
  carry UUIDs, a 24-hex fingerprint and a 64-hex hash chain, and a hex run can
  spell `123` by coincidence. **Characterise that defence before you change the
  bounds** — say in the close-out what concrete strings the bound rejects — then
  pin it. Note that for a value whose first and last characters are word
  characters, `(?<!\w)`/`(?!\w)` and `\b` accept exactly the same positions; the
  change is expected to be strictly a fix, and the pinning test is what turns
  that expectation into evidence.
- `docs/conventions.md#tests` — leak tests assert on absence and must come with
  a mutation proving non-vacuity; `#acceptance-criteria-discipline` and
  `#concurrent-maven-verification` (do not run a mutating reviewer and a tester
  against one `target/`; clone for the mutation runs).
- `docs/conventions.md` rule 7 and `StubCustomerAdapter.java:14-16` — new
  fixture text is invented, resembles no real person, address, credential or
  token, and carries no address-shaped or contact-shaped string.
- Nested shapes are a supported production shape, not a novelty:
  `JsonTreeScrubbingEngine.java:185-196` descends objects and arrays,
  `SensitiveObject.java` is the annotation that authorises descent, and
  `NestedScrubbingTest` pins the behaviour. `DefaultContextOrchestrator.java:292-295`
  merges source trees with a shallow `setAll`, so a nested component arrives in
  the merged tree as one new top-level key.
- Runtime is not a concern and must not be traded against coverage:
  `PiiLogScanTest` drives the tool in-process and the whole class ran in 0.024s
  under task 46. Confirm it is still well under a second; do not optimise.

### The fixture, and why it is `OrderDto`

Hole 1's fix is only falsifiable if a real fixture holds a real nested value:
without one, "leak a nested leaf and watch the scan redden" has nothing to leak,
and a recursion proven only against a record declared inside the test file
proves the helper, not the control.

Add **one** nested component to `OrderDto` — a `DeliveryDto` record holding an
opaque courier reference and a collection of short free-text notes — so a single
component exercises both the record branch and the collection branch of the
descent. `OrderDto` is the right DTO: its existing components are already
`@NonSensitive` opaque-reference and free-text shapes, and order-api's only
correlated namespace is `PERSON_NAME`, which it already contributes. A nested
block carrying no new namespace should therefore add no consistency finding and
perturb no existing assertion. That is a prediction, not a fact — verify it, and
if a new finding does appear, stop and report rather than editing a test outside
`Owns` to accommodate it.

Keep the note text innocuous: instruction-shaped text would trip the injection
heuristic and add a `SUSPECTED_INSTRUCTION_CONTENT` finding.

`docs/agents/stdio.md:102` prints the merged entity tree verbatim and will drift
once `delivery` exists. Do not edit it — only `scribe` writes docs. Name the
drift in the close-out so `/record` can fix it.

## Acceptance

- [ ] The derivation descends recursively: a component that is a `Record` has
      each of its own components visited, and a component that is a
      `Collection` has each element visited, to arbitrary depth. Leaves are
      stringified as they are today. A reader can see that adding a nested
      component extends the banned set rather than replacing its leaves with a
      `toString`.
- [ ] The descent fails loud rather than narrow: a component whose runtime type
      is neither a recognised scalar nor a `Record` nor a `Collection` (a `Map`,
      an array, an arbitrary POJO) throws an exception naming the component and
      its type. The recognised-scalar set is an explicit, commented predicate,
      not "everything else". State in the close-out why a silent
      `String.valueOf` fallback for unknown kinds would re-open exactly the hole
      this task closes.
- [ ] Termination is designed, not hoped for: the descent carries a depth cap or
      an identity-based visited set. A test constructs a self-referential
      structure (a `List` added to itself, wrapped in a record declared in the
      test) and asserts the derivation **completes** — returning, or throwing a
      named exception. A `StackOverflowError` or a hang fails this criterion.
- [ ] `OrderDto` carries exactly one new nested component, of a new
      `DeliveryDto` record with a courier-reference component and a
      `Collection<String>` of notes; `StubOrderAdapter`'s two fixture records
      populate it with invented, non-instruction-shaped text. No other fixture
      value changes. The new type carries whatever annotation the engine needs
      to treat it as a reviewed structure, and the choice is justified in a
      comment against `SensitiveObject.java`'s contract.
- [ ] `derivedBannedSetIsNonEmptyAndCoversKnownFixtureValues` still asserts the
      set is non-empty and still names the values task 46 pinned, **and** now
      also names at least two leaves reachable only by recursion: the courier
      reference and one element of the notes collection. A derivation that
      reverts to one level deep must redden this test.
- [ ] **Hole 1 proven by mutation.** In a scratchpad clone: make a production
      code path log a raw nested leaf value, run `PiiLogScanTest`, record that
      `fullIntegrationRunLeaksNoPii` goes RED naming that value. Then, on the
      same mutation, revert only the recursive descent to the one-level
      derivation and record that the scan goes GREEN with the raw leaf still in
      the log — that second run is the evidence the hole was real. Revert both;
      `git status` clean and `git diff --stat` empty. All three observations go
      in the close-out.
- [ ] `findLeaked`'s bounds are `(?<!\w)` / `(?!\w)` lookarounds rather than
      `\b`, and its javadoc says why, naming the trailing-`\b`-after-punctuation
      case in plain terms.
- [ ] **The hex-collision defence is pinned before it is changed.** A test
      asserts that a banned digit run embedded in a hex id, a UUID and a 64-hex
      hash chain is *not* reported by `findLeaked`, while the same digits
      standing as their own token *are*. The test must be shown RED against a
      `contains`-based `findLeaked` and GREEN against both the old `\b` version
      and the new lookaround version. All three runs recorded; a test that is
      green under `contains` pins nothing.
- [ ] **Hole 2 proven by mutation.** In a scratchpad clone: make a production
      code path emit a raw order note (`"No issues raised."`) on an ordinary,
      non-audit-shaped application log line. Record that the scan is GREEN
      against the pre-fix `\b` bounds with the raw note in the log — the hole
      itself — and RED naming the note against the lookaround bounds. Revert;
      tree byte-identical. Both observations go in the close-out.
- [ ] The completed scan is run once against otherwise-unmodified `main`. If the
      recursive set or the corrected bounds redden it, that is a finding to
      report — name the field and the log line and stop for a decision. Do not
      add an exemption to silence it.
- [ ] `EndToEndTest`, `WorkedExampleTest`, `CompareEntitySourcesWorkedExampleTest`
      and `McpHttpEndToEndTest` are green **unchanged**. If the new component
      forces an edit to any of them, stop and report: they are outside this
      task's `Owns`.
- [ ] `mvn -B clean verify` green across the full reactor, test count stated
      against the post-46 baseline, and `PiiLogScanTest`'s own class time
      reported and still well under a second.
- [ ] No new literal resembling a real person, address, credential or token
      anywhere in the diff (`docs/conventions.md`, rule 7).

## Out of scope

- The tautological `auditCount + nonAuditCount == total` assertion (near
  `PiiLogScanTest.java:262-264` after 46). Recorded separately in `PLAN.md`;
  leave it.
- `findLeakedInAuditLine`, `auditFields`, `EXEMPT_SHAPES`, `AUDIT_KEYS` and the
  shape patterns. The per-field `contains` path does not have hole 2 and is not
  being reworked. Only `findLeaked`'s two bounds change.
- Widening the banned set by any route other than the recursion — no new
  exclusion rules, no new literals, no second derivation source.
- Changing any other fixture value, adding a fourth stub adapter, or adding a
  nested component to `CustomerDto` or `AccountDto`. One nested shape is enough
  to make the recursion falsifiable.
- `docs/agents/stdio.md`, `docs/agents/remote-http.md` or any other document,
  including the entity tree they print verbatim. Report the drift; `scribe`
  fixes it.
- Version literals, `CHANGELOG.md` and poms — task 45 owns those.
- Any other test module. If the recursion implies a similar drift problem in
  `QuickstartSmokeIT` or the connectors' fixtures, report it; do not fix it here.
