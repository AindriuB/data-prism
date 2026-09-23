# 72 — Bring `timestamp` and `sourceSystems` inside the audit hash

**Repo:** .
**Depends on:** 66

**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventHash.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCli.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditRecorderTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCliTest.java

## Goal
`AuditEventHash.compute` joins seventeen fields and omits `timestamp` and
`sourceSystems`, so a record can be backdated, or have its source list rewritten,
and the chain still verifies clean without the editor recomputing anything. Add
both fields to the joined body, and make the verifier's printed limitation say
what the hash now actually covers.

## Why now, and why this is cheap today
Nothing durable has ever been written. Task 64's `FileAuditSink` is merged but
task 67 — which gives `dataprism.audit.sink: hash-chained` a bean — is not
recorded, so only `Slf4jAuditSink` runs and no deployment has produced a chain
this change would invalidate. That window closes when 67 lands. After that,
widening the hash means every previously written record fails recomputation.

The exclusion is an oversight, not a constraint: the architect spike of
2026-09-23 established that both are ordinary immutable record components on
`AuditEvent` (`AuditEvent.java:26-46`), set once in `AuditRecorder.record`
(`AuditRecorder.java:55-58`) from `clock.instant()` and the caller-supplied set,
never mutated after hashing, deterministic at construction. The owner's decision
of 2026-09-23 puts the operator and any LLM inside the audit trail's threat
model, so that an exposure of sensitive data can be traced — which makes *when*
it happened forensically central, and a rewritable timestamp a direct hit on
that requirement.

## Context
- `AuditEventHash.java:28-48` — the seventeen-field join, and the
  `compute(AuditEvent)` overload that delegates to it. Lines 33-35 sort
  `rejectedArguments` precisely so the hash does not depend on a `Set`'s
  iteration order; `sourceSystems` needs the same treatment.
- `AuditRecorder.java:49-58` — the hash is computed **before** the event is
  constructed, and `clock.instant()` is called only in the constructor call.
  Two separate reads of the clock would produce an event whose stored hash does
  not match its stored timestamp, i.e. a chain that breaks on its own first
  record.
- `AuditRecordFormat.java:52,79` — the durable representation is already
  `Instant.toString()` / `Instant.parse`, and line 64 encodes `sourceSystems`
  with the same set encoding as `rejectedArguments`.
- `AuditChainVerifierCli.java` `LIMITATION` (around :41-60) — enumerates the
  seventeen covered fields and states that a backdated record verifies clean.
  After this task that sentence is false.
- `AuditChainVerifierCliTest.java:157-182`
  (`aTimestampEditIsUndetectedAndTheLimitationSaysSoUpFront`) and `:65` — the
  test task 66's reviewer left as the tripwire for this change. It asserts
  `EXIT_INTACT` and `"intact: every record"` after editing a mid-chain
  timestamp, and pins the limitation substrings `"timestamp and sourceSystems"`
  and `"backdated"`. All four assertions must change here.
- `AuditChainVerifier.java:22-24` and around `:234` — javadoc naming the
  "seventeen" hashed fields and the two excluded ones.
- `docs/conventions.md`, "Tests" — the golden-vector rule; see item 4 below.

## Settled decisions — implement, do not re-open

**The public signature.** Extend the long overload to nineteen parameters,
adding `Instant timestamp` and `Set<String> sourceSystems` in `AuditEvent`'s
own component order; keep it public and keep `compute(AuditEvent)` delegating
to it. Do not drop it in favour of the `AuditEvent` overload alone: the only
other way for `AuditRecorder` to reach `compute(AuditEvent)` is to materialise
a half-built event carrying a null `eventHash`, and `AuditRecorderTest`'s
pinned-literal test needs an entry point that fixes every component including
`eventId`, which `AuditRecorder` mints randomly. The break in source
compatibility is free: callers are `AuditRecorder` (long form) and
`AuditChainVerifier` + tests (`compute(AuditEvent)`), all in this repository,
and 0.3.0 is not cut (task 70). A builder to replace nineteen positional
arguments is a separate concern and out of scope.

**Rendered forms.** `timestamp` joins as `Instant.toString()` — the same ISO-8601
form `AuditRecordFormat` already persists and parses, locale- and
JVM-independent, and equal instants always render equal. `sourceSystems` joins
as its elements sorted then comma-joined, exactly as `rejectedArguments` does.
A null `timestamp` keeps the existing convention for null components in the
join rather than throwing.

**This does not close the threat-model gap.** The chain is still unkeyed
SHA-256, so an operator who can write the file can recompute every hash after
any edit and the result verifies perfectly. Widening the coverage stops a *lazy*
edit — one that does not recompute — and makes each record self-consistent with
its own timestamp. It does not resist the operator. The CLI's adversary
sentence about unkeyed SHA-256 stays verbatim; nothing here may weaken it, and
no new output string may claim tamper-proofness, immutability or completeness.

## Acceptance
- [ ] `AuditEventHash.compute`'s joined body includes `timestamp` and
      `sourceSystems`; the long overload takes both, in `AuditEvent` component
      order, and `compute(AuditEvent)` passes `event.timestamp()` and
      `event.sourceSystems()` through. No second join format exists anywhere.
- [ ] Changing only `timestamp` on an otherwise identical event changes the
      computed hash; changing only `sourceSystems` changes it. Asserted
      directly in `AuditRecorderTest`, one behaviour per test.
- [ ] `sourceSystems` is sorted before joining: two events differing only in the
      iteration order of an equal `sourceSystems` set — construct one as a
      `LinkedHashSet` in each of two different insertion orders — hash
      identically.
- [ ] Two events whose `timestamp`s are the same `Instant` obtained by different
      routes (e.g. `Instant.parse("...")` and `Instant.ofEpochSecond(...)`,
      including one built with explicit zero nanos) hash identically, pinning
      the rendered form as stable rather than incidental.
- [ ] `AuditRecorder.record` reads the clock exactly once and uses that single
      `Instant` for both the hash and the constructed event. Proved by a test
      that records an event and asserts
      `AuditEventHash.compute(event).equals(event.eventHash())`, driven by a
      `Clock` that would return a different instant on a second read (a ticking
      or stepping clock, not the fixed clock the other tests use).
- [ ] `AuditRecorderTest.hashMatchesPinnedLiteral` still exists and still pins a
      literal — recomputed for the new join, not deleted, not relaxed to
      "matches what the code produces". Its javadoc says the literal changed
      because the join widened, and the commit body says the same.
- [ ] `AuditChainVerifierCli`'s `LIMITATION` no longer claims `timestamp` and
      `sourceSystems` are excluded, no longer says a backdated record verifies
      clean, and lists the fields it now covers accurately. Its unkeyed-SHA-256
      adversary sentence and its tail-truncation sentence are unchanged.
- [ ] `AuditChainVerifierCliTest`'s timestamp test is updated, not deleted: the
      same mid-chain timestamp edit now yields the break exit code and a break
      message, and the limitation assertions at `:65` and in that test assert
      the new wording. The test name changes to describe the new behaviour.
- [ ] A mid-chain `sourceSystems` edit on a real `FileAuditSink` file is
      likewise reported as a break at that record's sequence and offset.
- [ ] **Real artifact.** Both of the above are exercised by editing a file
      produced by an actual `AuditRecorder` + `FileAuditSink` run and running
      the verifier over it — not by hand-built event objects or hand-written
      fixture lines.
- [ ] A file produced by an unedited `AuditRecorder` + `FileAuditSink` run still
      verifies intact end to end after the change, via the CLI, exit
      `EXIT_INTACT`. This is the criterion that catches a single-clock-read
      regression at the artifact level.
- [ ] `AuditChainVerifier`'s javadoc no longer says "seventeen", and no comment
      or javadoc in the owned files still names `timestamp` or `sourceSystems`
      as unhashed.
- [ ] `mvn -pl data-prism-core -am test` passes, and the full reactor build
      passes.
- [ ] The commit body states that the change invalidates any chain written
      before it, and that no durable chain exists because `hash-chained` has no
      bean until task 67.

## The golden-vector rule does not bind here — do not reject the diff on it
`docs/conventions.md`'s rule ("a diff that edits it without bumping the version
is a finding", amended 2026-09-22 to allow a recorded owner decision cited by
reference) is written about the pseudonymisation golden vectors:
`data-prism-pseudonymisation/src/test/resources/golden-vectors-v1.tsv` and
`golden-vectors-western-v2.tsv`, pinning algorithm v1 outputs, where the cost of
holding the version is that previously issued pseudonyms stop reproducing. This
task touches neither file, and the audit hash has no algorithm version to bump
and no checked-in vector file. The pinned literal in `AuditRecorderTest` is an
inline test constant, not a golden-vector artifact, and nothing downstream
reproduces a value from it.

So: no `PLAN.md` decision entry is required, and no version bump is required.
The equivalent discipline that *does* apply is the commit-body requirement
above — the changed literal must be explained in the diff that changes it. A
reviewer must not hold this diff for a missing golden-vector decision record.

## Out of scope
- A keyed MAC, signing, anchoring or any external checkpoint. The unkeyed-SHA-256
  limitation stands and its wording is frozen.
- Tail-truncation and head-deletion detection — task 66's ground, already settled.
- Anything in `data-prism-spring-boot-autoconfigure`: wiring `hash-chained` to a
  bean is task 67's, and this task must not touch its files even though its
  merge is what closes this window.
- `AuditRecordFormat` and `FileAuditSink`: the durable representation of both
  fields already exists and is correct. No format change is needed.
- `docs/architecture.md`, `docs/audit.md`, `PLAN.md`, `HISTORY.md` — documentation
  belongs to task 62 and the scribe.
- Adding fields beyond these two, or introducing a builder for
  `AuditEventHash.compute`.
