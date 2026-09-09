# History

What was built, newest first. Append-only. Only `scribe` edits this file.

**Read this before redesigning anything.** The `Cost` line on each entry is the
point of the file — it is where the sessions that already tried the obvious
approach tell you what happened.

Do not load this file to find out whether something exists. It grows without
bound. `HISTORY-INDEX.md` carries one row per entry; scan that, then grep this
file for the exact heading it names. Every entry added here gets its index row
in the same commit.

<!--
## YYYY-MM-DD — <what landed>
<Two or three sentences: what it does now that it did not before.>
**Cost:** <what was hard, what was tried and abandoned, what not to retry.>
-->

## 2026-09-09 — Task 10: the mTLS refusal assertion made cross-platform

`main` went red on GitHub Actions at commit `3f6a59a`.
`MutualTlsRestClientsHttpsTest.clientWithoutCertificateIsRefused` required an
`SSLException` in the cause chain. That holds on Windows; on the Linux runner
the server closes the TCP connection before the client reads the TLS alert, so
the client surfaces `HTTP/1.1 header parser received no bytes` instead. The
handshake was refused on both — the test asserted one platform's spelling of
it.

The test now accepts either observable and documents both in its javadoc. The
negative cases still hold: pointing the base URL at a closed port fails it,
and so does a request that reaches the server and gets a 404 — that second one
is what separates "refused during the TLS handshake" from "reached the server
and got an error", which the assertion this test started life with could not
distinguish. Verified green on the Linux CI runner in GitHub Actions run
34386674901, on `main` at `b953014` — this is the run being recorded as
closing the task, since every prior local verification of this assertion ran
on Windows.

**Cost worth recording, and it is about how this was verified rather than
about TLS.** This is the test task 08 rewrote, correctly, to fix a genuinely
vacuous `isInstanceOf(RuntimeException.class)`. The tightening was right and
should not be read as a mistake. What was wrong is that every verification of
it ran on Windows — a 20-run stability check included — while CI runs Linux. A
platform-specific assertion is invisible to a platform-specific verifier. The
single earlier failure seen under a full run and attributed to `target/`
contention was probably this, and that explanation was accepted too readily.

Recorded honestly as a known trade-off, not a hidden one: the Linux branch of
the assertion matches a literal JDK message string, because the wrapped
exception type varies with kernel behaviour and is not a reliable
discriminator. A JDK upgrade that reworks that message will turn this test red
for a reason unrelated to mTLS. That was a deliberate choice with the
alternative rejected; whoever sees it fail should read the javadoc before
assuming a real regression.

Two findings from this task are not this task's work, and are recorded as open
in `docs/plan/PLAN.md` rather than fixed here: repository auto-merge is
enabled with no branch protection requiring the build to pass — PR #9 merged
into `main` while its own Actions run was failing (run 34385475478), and this
task itself was never reviewed for that reason, so this is a decision needed
from the repository owner, not scheduled work — and a seventh cannot-fail
assertion at
`data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapterHttpTest.java:97`,
the same vacuous `isInstanceOf(RuntimeException.class)` form task 08 removed
from its neighbour. A survey of every test module found no other test
asserting on a platform-specific exception type or message, so the
cross-platform problem appears confined to the one test fixed here.

## 2026-09-09 — Task 09: shipped defaults, and closing the last gap in the log scan

This closes S8 and the cheap half of S9, and brings the plan to its
recommended stopping point. Five items, two substantive.

`DataPrismAssembly` stopped minting a context holding
`Capability.EXPOSE_SOURCE_NAMES` — the capability that unmasks real source
names — and handing it to any caller that asked. Task 06 worked around this
rather than fixing it, because it did not own the file, and the visible
symptom was that the documented worked example showed unmasked source names
while the actual application path masked them. `WorkedExampleTest`'s
assertions on literal source names moved to aliases, so the specification's
§64 worked example now demonstrates what running the application actually
does. The shipped `developer` policy became a named factory that main uses and
a test asserts, so the default that ships is the one under test rather than
one every test rebuilds for itself.

`PiiLogScanTest` now parses `dataprism.audit` records field by field instead
of matching raw text on word boundaries. That closes a residual gap — a banned
value glued to word characters, `subject=SUBJ-123a7f9` or `id_456_x`,
previously passed the scan, and the first is the shape a pseudonymisation bug
concatenating a raw id onto a prefix would produce.

**Cost worth recording — this test has now had three separate flake sources,
and the third was found only because 30 sequential runs were required as
evidence.** First, bare substring matching collided with random hex in audit
fields, about one run in four. Second, a real `Clock.systemUTC()` timestamp:
not hex, but its nanosecond digits are equally random, and one run
coincidentally spelled `456`. Third — not a flake but the thing that masked
the diagnosis — concurrent Maven processes against one `target/`, recorded
under task 07.

The general lesson: any unpinned field carrying random digits can impersonate
a leaked identifier, and a leak detector that cries wolf is one nobody reads.
The fix is that a field is exempt from scanning only by matching a pinned
shape, never by its name — an exemption keyed on a name is one a future field
inherits by accident, which is exactly how the timestamp field became a flake
source. Verified at review by making an exemption name-only and watching the
guard test redden.

Verification evidence: 50 consecutive sequential runs of `PiiLogScanTest`, 10
each of `WorkedExampleTest`, `ShippedDefaultsTest` and `EndToEndTest`, all
clean, no leaked ports or JVMs.

Two small items left open, not fixed here: `PiiLogScanTest.java:191-193`, a
tautological sum assertion (the sixth cannot-fail assertion found in this
repository, this one from the task brief rather than the implementer); and
`PiiLogScanTest.java:104-112`, a javadoc claiming "twenty placeholders" where
`AUDIT_KEYS` holds 19 names because the sink's `seq={}/{}` is two placeholders
folded into one field. Both recorded in `docs/plan/PLAN.md`.

**Cost:** the flake diagnosis above is what took the time — three unrelated
causes had to be told apart before the third fix could be trusted, and the
only way to trust it was volume (30 sequential runs) rather than a smaller
number of clean ones. Nothing was tried and abandoned on the substantive
items; the factory-not-caller fix for `EXPOSE_SOURCE_NAMES` was the design
task 06 had already pointed at but could not make because it did not own
`DataPrismAssembly`.

## 2026-09-09 — Task 07: OAuth2 resource server, Micrometer, PII log scan

This closes S8 and the cheap half of S9. A Spring Boot resource server validates
caller JWTs against a configured JWKS, an extractor puts an `AuthenticatedCaller`
— and only that, never the raw token — into the MCP transport context, and the
tool derives scope, principal, purpose and case from it. stdio's production
refusal is wired at the one call site that existed. Micrometer binds through
`MicrometerPrivacyMetrics`, which guards inside the implementation so a
conflicting meter registration cannot fail a lookup.

The criterion that mattered: task 06 built the streamable HTTP transport but
nothing exercised it — its test asserted a builder returned non-null and never
opened a socket. `McpHttpEndToEndTest` now makes a real HTTP MCP request bearing
a locally-minted JWT against an in-test JWKS, on an ephemeral port, and asserts
the `PrivacyContext` carries values derived from the token. Review proved it
non-vacuous by hardcoding a constant caller in the extractor and watching four
tests redden, and confirmed each negative-direction mutation flips exactly one
direction, so no test passes on a blanket refusal. Its `@AfterAll` asserts a
fresh socket connect to the port throws, so the server is proven released
rather than merely assumed.

**Cost worth recording — a false PII-leak alarm caused by the verification
process, not by the code.**

`PiiLogScanTest` failed intermittently with `Expecting empty but was: ["123",
"456"]` — the raw stub subject ids, in the one test whose job is to prove no
personal data reaches a log line. Attempt 1 was rejected for it after a
reviewer measured 3 failures in 12 runs and diagnosed incidental hex collision:
`BANNED_VALUES` held bare `123`/`456` while audit lines carry random hex.
Attempt 2 switched to word-boundary matching and reported 25 clean runs; a
second review approved with a structural argument that no field
`Slf4jAuditSink` emits can produce a 3-character word-bounded token. A tester
then ran it 30 times and got 5 failures, 4 of them the same paired ids, and
reasoned that a pair failing together is not what independent hex collisions
look like — which is correct, and which pointed at a real leak.

It was not a leak. Investigation checked the compiled bytecode directly:
`DefaultContextOrchestrator.audit()` loads the HMAC pseudonym, not
`request.subjectId()`, and no logging call site in the pipeline touches a raw
subject id. 100+ sequential and parallel reproduction attempts never produced
the failure. What did reproduce was concurrent Maven processes contending over
one shared `target/`, failing on exactly the class named in the accompanying
`ClassFormatError`.

The mechanism: the only change in the codebase that produces this exact
symptom — both ids, paired, in an otherwise well-formed `event=` line — is
swapping `subjectToken` for `request.subjectId()` in
`DefaultContextOrchestrator`, which is precisely the mutation both the
implementer and the reviewer run as their "prove this test can fail" step.
Verification spawns tester and reviewer concurrently against one worktree.
Once the reviewer compiles a mutated tree while the tester runs builds,
mutated classes can land under a running test.

Final confirmation, single agent, clean `target/`, strictly sequential: 50/50
passes, 318 tests. Re-verified again at close-out with a full clean sequential
`mvn -B verify` from `main` post-merge: 318 tests, 0 failures, 0 errors, 0
skipped — including `MutualTlsRestClientsHttpsTest`, itself flagged as a
one-off failure under a contended full run (see below), which passed cleanly
both times.

**Two process rules this establishes, added to `docs/conventions.md`:**

1. **Never run a compiling reviewer and a tester concurrently against one
   worktree.** Read-only verification can be parallel; this project's
   reviewers routinely mutate and rebuild to prove a test can fail, which
   makes them writers of `target/` even though they touch no tracked file.
   Either the reviewer clones first, always, or the two run in sequence. A
   contended `target/` does not fail loudly — it produces a wrong test
   result, and in this case one indistinguishable from a privacy defect.
2. **A failing leak-detection test must preserve its captured output.**
   `PiiLogScanTest` writes the full capture to `logs/pii-log-scan-<millis>.log`
   before asserting, and every failure so far deleted it before anyone read
   it. The line itself settles a leak question in one look; without it, this
   cost four agents and several hundred test runs to resolve by inference.

Also record, as a known residual and not a defect: `PiiLogScanTest`'s
word-boundary matching means a banned value glued to word characters is not
caught — `subject=SUBJ-123a7f9` and `id_456_x` both pass, verified at review.
No current code path emits either, but the first is the shape a
pseudonymiser bug concatenating a raw id would produce. Scanning the audit
line's structured fields rather than raw text would close it. Queued for task
09.

Also note `MutualTlsRestClientsHttpsTest`, which failed once during an
earlier full run, passed 20/20 standalone, in two full reactor runs during
review, and again in both of the close-out's clean sequential runs. Not a
flake; the same `target/` contention as the log-scan alarm above.

## 2026-09-09 — Task 06: streamable HTTP transport, per-request caller context, authorisation at the tool

This is the task that makes S8 mean something. `PrivacyContext` was a constant
and `scopeId` is half the pseudonymisation key, so scope isolation was real in
the code and vacuous in the deployment — there was only ever one scope. It now
derives from an authenticated session.

`DataPrismMcpServer` exposes two named factories instead of one constructor: a
stdio mode that refuses to start without an explicit development flag or under
a production profile, and a streamable HTTP mode taking a caller-supplied
`contextExtractor`. Both build `JacksonMcpJsonMapper` over the single
`DataPrismObjectMapper.create()`, so boundary 1 holds on both paths and
`ArchitectureTest` still enforces the one construction site.

`GetEntityContextTool` reads the caller from `exchange.transportContext()` —
it previously discarded the exchange entirely — then authorises, resolves the
session, and audits a denial. No caller means refusal with zero orchestrator
calls. A denial returns a code only, never partial data.

**Cost:** the task text said to use the assembly's development caller, but
`DataPrismAssembly.investigationContext()` carries `EXPOSE_SOURCE_NAMES` by
construction — the capability that unmasks real source names — so following
the task text literally would have violated the task's own acceptance
criterion that no dev principal holds it by default. The implementer built a
scoped-down caller in `ExampleApplication` and reported the contradiction
rather than silently choosing, and review confirmed that was right. The
consequence is that `DataPrismAssembly` still hands that context to anything
else that asks, and `WorkedExampleTest` is one such caller — now open item 1
below.

Review found no defects. Mutation proofs covered blanket ALLOW and blanket
DENY, `MCP_DENIED` removal, `MCP_REQUESTS` removal, `ReservedArguments`
bypass, a silent fallback caller, both halves of the stdio guard, a constant
`scopeId`, and `EXPOSE_SOURCE_NAMES` leaked into every decision. One
surviving mutant was correctly identified as equivalent rather than a gap:
neutralising the tool's own `!decision.allowed()` branch survives because
`ScopeResolver.resolve` re-refuses with the same code — defence in depth
working, not dead code.

One correction to the record, because it matters for how these numbers are
trusted: the reviewer reported "286 tests" from its scratch clone while the
tester reported 300. The tester was right — the branch has 292 `@Test` + 8
`@ArchTest` = 300, main has 279 + 8 = 287, no parameterized tests, and the
reviewer's clone was not this branch; 286 matches neither tree. Its
per-criterion findings stood regardless, each anchored to a file:line in the
actual worktree with mutation proofs run there. The criterion that replaced
the stale literal is only as good as the tree it is measured on.

Open items, not fixed here, going into a follow-up task:
1. `WorkedExampleTest` still runs on `assembly.investigationContext()` and
   prints real source names, so the documented worked example no longer
   matches what running the application shows.
2. Granting `Capability.EXPOSE_SOURCE_NAMES` to the shipped `developer` role
   in `ExampleApplication.java:49` kills no test — every capability test
   builds its own policy, so the shipped default is unguarded.
3. Nothing exercises the HTTP transport end to end: the new test asserts the
   builder returned non-null and never starts a server or opens a socket, and
   `EndToEndTest` drives the call handler through a synthetic exchange. The
   `contextExtractor` delivering a real caller into
   `exchange.transportContext()` on a real request is unverified.
4. `ScopeResolver`'s class javadoc still omits the purpose refusal added in
   task 08.
5. `data-prism-example/pom.xml` gets `data-prism-security` transitively
   through `mcp` rather than declaring it directly.

## 2026-09-09 — S8: three inert controls made to run

The theme is worth recording as a theme, because it is the shape of defect this
project keeps producing and the one a reviewer is least likely to catch: a
control that is present in the code and does nothing. Someone reading for "is
there a check?" finds one.

`PurposeValidator` existed and failed closed correctly when called, and nothing
called it. It is now composed into `ScopeResolver`'s sole constructor — no
overload, no default, `requireNonNull` on the field — and runs on the path that
builds a `PrivacyContext`, before the context is created. An unknown purpose
refuses with `UNKNOWN_PURPOSE` and yields no session.

`MutualTlsRestClientsHttpsTest` asserted `isInstanceOf(RuntimeException.class)`,
which a URL typo or a missing file satisfies. It now asserts
`ResourceAccessException` with an `SSLException` in the cause chain, and differs
from the positive test only by the key manager.

`EndToEndTest`'s reserved-argument assertion passed on a blanket DENY, so it
could not distinguish "the caller's own scopeId was ignored and the call
proceeded on session-derived context" from "the call was refused for an
unrelated reason". It now asserts content equality against a plain call plus a
single `ALLOW` audit event naming exactly the four rejected argument names. Both
directions of failure are now live on that assertion, proven at review by
mutating `GetEntityContextTool` to honour the caller's `scopeId` — the assertion
caught it, with the pseudonym differing between runs.

This brings the count of tests-that-could-not-fail found in this project to
five, all recorded in `docs/conventions.md`. Two of them were fixed here. 287
tests post-merge (up from 285), all 13 modules, `mvn -B verify` clean.

**Cost:** nothing unusual on the implementation side — this was a small,
targeted diff against three known files. The finding worth carrying is a
review note not actioned: `ScopeResolver`'s class javadoc
(`data-prism-security/.../ScopeResolver.java:12-22`) still describes only
scopeId and pseudonymisation pinning, omitting the purpose refusal that is now
the class's third responsibility. Left for whoever next touches the file
(task 06, which writes the first call site of the new constructor) rather than
edited here, since only `implementer` writes code and this task's owned-files
list did not cover a doc-only javadoc pass as its own item.

## 2026-09-09 — S8 wave 2: security module, real principal, and identity metrics

The three tasks that depended only on wave 1 landed together. `AuthenticatedCaller`,
`AuthorizationService`, `AuthorizationDecision`, `PurposeValidator` and `ScopeResolver`
give Data Prism capability-based authorisation, with roles from token claims mapped to
capabilities in configuration; `ScopeResolver` pins `keyId` and `vocabularyId` at scope
creation, so a key rotation or vocabulary change mid-scope cannot silently change what a
pseudonym means. `DefaultContextOrchestrator` previously hardcoded the principal as
`"system"`, so every audit event was attributed to a principal that does not exist — the
real authenticated principal now flows through into audit, and `SourceAliasing`'s
expose-real-names boolean became a real capability check. Hazelcast identity cache hit,
miss, collision and re-identification counters now publish through the `PrivacyMetrics`
SPI. 285 tests post-merge (up from the 224-test baseline this wave started from), all 13
modules, `mvn -B verify` clean with all three branches merged into one tree.

**Cost:** the Hazelcast metrics task took three attempts, and the reason generalises
past this file. Attempts 1 and 2 both failed on the same theme: instrumentation that can
change the thing it measures. Attempt 1 emitted `dataprism.identity.cache.miss` twice on
one lookup when the *generator* threw rather than the cluster, so the published metric
disagreed with the counter it exists to publish. Attempt 2 fixed that but guarded only
one of three emit sites — a `PrivacyMetrics` whose `increment` throws could still either
escape `syntheticValue` (breaking the fail-open guarantee stated in the class's own
javadoc) or, on the collision path, exit `store()` before the corrective `identities.set`,
leaving the known-bad cached value in place so every later lookup in that scope returned
it. The collision counter exists precisely because a non-zero value means an answer may
have changed; its own failure would have caused that outcome. Attempt 3 fixed it
structurally with `FailSafeMetrics`, a wrapper applied once at construction so emit sites
added later are guarded by construction rather than by memory — the transferable point is
that a guard which has to be remembered at each new call site will be missed, and it was,
between attempts 2 and 3. This mattered immediately rather than hypothetically: task 07
binds Micrometer, which throws on conflicting meter registration.

One deliberate exception, ruled correct on review rather than an oversight to fix later:
`ScopeIdentityIndex.subjectFor` calls `metrics.increment` unguarded. Re-identification is
not the fail-open case — the identity cache is, because a synthetic value is a pure
function and nothing can recompute a subject id from a pseudonym. A metrics failure
refusing to reveal a subject is fail-closed working as intended.

## 2026-09-09 — S8 wave 1: session types, metrics SPI, and mTLS to sources

The two tasks with no dependency on the rest of S8 landed first. `InvestigationContext`
and `Capability` give the pipeline a caller shape to carry once wave 2 threads a real
principal through it; `Metric` and `PrivacyMetrics` are the SPI wave 2 and S9a report
through, with `PrivacyMetrics.none()` as the default so nothing downstream is forced onto
a backend yet. `data-prism-security` is registered in the reactor as an empty module,
ready for task 03. Outbound calls to source systems can now run over mTLS:
`TlsSettings`, `MutualTlsRestClients`, `RestSourcesConfig`, a `requireHttps` mode on
`RestSource`, and `tls:` block parsing. 220 tests after task 01, 224 after both, all 13
modules, `mvn -B verify` clean with both branches merged together (neither task depended
on the other, so this is the first time they were built as one tree).

**Cost:** two follow-ups fell out of review that were real but did not block merging,
carried forward as acceptance criteria on task 03 rather than fixed ad hoc:
`Capability.KNOWN` has no test pinning it, so a fifth capability added later without
updating `KNOWN` fails silently — `Metric` already has this test, in `PrivacyMetricsTest`,
and it needed mirroring rather than inventing something new. The other is a vacuous
assertion: `MutualTlsRestClientsHttpsTest`'s no-client-certificate case asserts
`isInstanceOf(RuntimeException.class)`, which would also pass if the test server were
simply unreachable, so it does not prove what it claims to. This is the fourth vacuous
assertion this project has shipped, which is why `docs/conventions.md` calls the pattern
out by name — worth treating as a class of bug to look for on every review, not a one-off.

Also settled this session and worth not re-litigating: Data Prism is an OAuth2 resource
server, never a token issuer and never a pass-through of the caller's token to source
systems. See `docs/architecture.md#decisions-worth-knowing` (2026-09-09) for the full
reasoning — the short version is that pass-through would recreate the network path
`pack.md` §87 asks to be impossible, and would collapse two distinct authorisation
questions into one.

## 2026-09-09 — S7 embedded Hazelcast for distributed scope state

Three pieces of shared state that look alike and are not: the identity cache, the
read budget, and the re-identification index. 212 tests.

**Cost:** the useful distinction, and the one to preserve if this is ever changed.
The identity cache may fail open, because a synthetic value is a pure function of
scope, subject, namespace and key — losing the cluster costs a recomputed HMAC
and never a different answer. The read budget must fail closed, because an
unreachable budget means nobody is counting and continuing would silently remove
the only limit on how much a caller can extract about one subject. The first
version of that fail-closed path did not work: obtaining the map and taking the
lock are themselves cluster calls that throw when the member is gone, and they
sat outside the guard, so the exception escaped before the refusal could run.

Embedded rather than client-server, reversing the design review. The objection was
that autoscale rebalancing disturbs the identity map; it does not, because a lost
partition costs recomputation rather than a rename. What the argument does not
cover is the re-identification index, which is a store rather than a cache —
nothing recomputes a subject id from a pseudonym — so it is off unless a
deployment enables it and accepts that its durability is the cluster's.

Departed from the specification's §24 sketch, which returns the stored value on a
concurrent write. Two threads deriving one key derive one value, so a differing
stored value is not a race but a mid-scope change of key, algorithm or
vocabulary. Preferring it would make output depend on cache state.

Tests use an embedded member rather than Testcontainers because Docker was
unavailable; a multi-member test is still worth adding. Hazelcast startup makes
this module's tests take about a minute against under ten seconds for everything
else.

## 2026-09-09 — S6 correlation and consistency findings

Three systems holding "Patrick Murphy", "Pat Murphy" and "P. Murphy" now produce
one consistent identity and a finding saying the systems disagree. The
specification's §64 worked example runs end to end. 201 tests.

**Cost:** the ordering is the whole slice and is easy to get backwards.
Correlation runs on raw records before scrubbing, because the pseudonym is keyed
on the subject — after scrubbing all three spellings are the same string, and
correlating then would report perfect agreement about data that agrees on
nothing. Anyone moving correlation later in the pipeline will find it still
compiles, still passes most tests, and silently reports agreement.

Findings carry no values, only which sources agreed with each other. Fields match
across sources by namespace rather than name, and a field with no namespace is
deliberately not correlated: two fields both called `status` in different systems
are usually not the same fact.

Two things worth not undoing. `ComparisonForm` is aggressive where
`Text.canonical` is conservative, on purpose — being wrong in the latter splits a
subject or lets a leak past, being wrong in the former downgrades a finding.
`ABBREVIATION` is restricted to name-like namespaces because "one value is a
prefix of the other" is a genuine clue about an identifier.

The injection heuristic first examined only namespaced fields, which is precisely
where instruction text does not live — it lives in free-text notes. Correlation
and the heuristic were never the same filter and sharing one hid the bug.

An architecture rule caught this work: three classes each built their own reading
ObjectMapper, so the "one mapper" rule had a four-name allowlist that would have
grown again. Reading is consolidated in `SourceTree` and the allowlist is two
names — one that reads, one that writes.

## 2026-09-09 — S5 parallel connectors and request limits

Sources are called in parallel on virtual threads under a bulkhead, a per-source
timeout and a source cap, with a circuit breaker per source. A failing source is
recorded absent and the answer built from the rest. 180 tests, new
`connectors-rest` module.

**Cost:** three things worth knowing. The per-source timeout is wall-clock from
the start of the fan-out, so a source queued behind the bulkhead spends part of
its budget waiting — intended, and it means concurrency set far below the source
count shows up as timeouts rather than slow success. Holding nothing for a
subject is an answer and does not trip a breaker; treating it as failure would
open the breaker on a healthy source. And putting per-source `Duration` in the
response broke MCP serialisation, which is how it surfaced: latency is
operational telemetry, and infrastructure timings tell an untrusted reader about
the health of systems it cannot otherwise see.

Two test traps caught here. An assertion on a decoded URL path fails for the
right reason and the wrong one — `/customers/../../admin` contains `/admin` while
still being one safe segment, and the property to assert is that the slash
arrived encoded. And a test class named `...IT` is Failsafe's convention, so
Surefire skipped it in silence: a test that never runs is worse than no test.

## 2026-09-08 — S4 pattern detection with a scope-aware allowlist

The validator now catches sensitive values that were never in a classified field
— an identifier in a free-text note, an IBAN in a description. Detectors for
IBAN, card PAN, PPSN, SSN, email, phone, JWT and common API-key prefixes, run
over the whole tree at any depth and size-bounded. 146 tests.

**Cost:** detection and the allowlist had to be one change. SYNTHESIZE emits
`person.kz48@example.invalid`; an email detector matches it, fail-closed refuses,
and every synthesising profile deadlocks. Building detection first would have
produced something that passes its own tests and cannot be deployed. The engine
therefore reports what it emitted and the validator permits those values —
PASS_THROUGH values deliberately excluded, since a field passed through unchanged
should still be scanned.

The non-obvious call is that **shape refuses and the checksum only labels the
reason**. Gating refusal on a valid checksum is unimplementable here: fixtures are
required to carry invalid check digits, so every detection test would be vacuous.
Checksum correctness is proved by counting valid variants — one of 100 IBAN
check-digit pairs, one of 10 card final digits, one of 23 PPSN letters — without
committing a valid value anywhere.

This also caught a breach of the project's own rule: `IE29AIBK93115212345678`,
introduced in S3 and merged to main, computes to mod-97 = 1 and is therefore a
structurally valid IBAN. A documentation example rather than a real account, so
nothing was disclosed, but the rule exists so a fixture cannot quietly be real.

## 2026-09-08 — S2a key rotation

`MultiKeySecretKeyProvider` resolves several keys at once, so a rotation can begin
without invalidating scopes still running under the previous key. Adding a key
never displaces another; `remove` is the only way to retire one. 132 tests.

**Cost:** small slice, one trap. The tempting fallback is to return the only key
held when an unknown id is asked for — it looks harmless and would silently change
every synthetic value in that scope while nothing appeared to fail. There is a
specific test for a provider holding exactly one key still refusing a different
id. The provider is deliberately not a `record`, because the generated
`toString()` would print every key.

## 2026-09-08 — S3 scrubbing engine, and the nesting hole it closed

Nested objects and collections are descended into rather than copied; class-level
defaults and descriptor files provide two routes to classifying a model without
annotating every field; and the action set is complete with HASH, TOKENIZE and
GENERALIZE. 117 tests.

**Cost:** the reason this slice mattered was a fail-open hole nobody had noticed.
A nested object declared `@NonSensitive` — the natural annotation for a
sub-structure believed inert — was copied into the response wholesale, raw values
included, and the validator did not catch it because `SourceValues` only walked
top-level fields. Eighty-three green tests missed it because every fixture was
flat. If a future change makes the engine copy a subtree again, that is the
failure to look for.

Two things fell out of fixing it. A nested structure must inherit its parent's
subject, or every nested synthesised field fails for want of one. And an
undescendable subtree must consult the profile's unclassified setting rather than
the holding field's action — consulting the field meant a `@NonSensitive` wrapper
passed its contents through even under FAIL_REQUEST, which was the original hole
wearing a different hat.

Retrofit ergonomics drove the rest. Annotating every field of a large legacy model
produces no information and creates pressure to disable fail-closed globally, so
`@LlmExposedModel(undeclaredFields = ...)` states per type what silence means, and
descriptors classify types that cannot be annotated at all. Descriptors may only
tighten: if configuration could declassify a field, anyone who can edit a file can
disclose data. Namespace is the one thing that cannot merge by strictness and
fails loudly on disagreement, because picking one would split a subject in two.

## 2026-09-08 — S2 configurable multi-locale name pools

Name pools are now configuration. Seven sets ship — a widened Latin default,
pan-European, Irish, Arabic, Mandarin, Cyrillic, and the original pool frozen —
and any of them can be replaced, extended, or joined by a locale that is not
bundled. Rendering follows the script: Han names are family-first and unspaced,
Han addresses run largest unit first. 83 tests.

**Cost:** the thing that is easy to miss is that a name is chosen by
`digest mod pool size`. Adding one entry shifts the choice for a large share of
subjects, silently, for every scope at once — under a scheme where a pseudonym
must stay stable for the life of an investigation, that is indistinguishable
from corruption. Vocabularies are therefore content-addressed and pinned by the
scope exactly as the signing key is, and a generator serving a scope pinned to a
different pool refuses. Two golden vector files exist for the same reason: the
frozen `generic-v1` reproduces every S0 vector byte for byte, and `western-v2`
disagrees with it on the same subject. The disagreement is the evidence.

Unicode closed two failures that both looked like success. The same person
stored NFC in one system and NFD in another would have become two people; and a
value leaked in the other normalisation form would have passed the output
validator while it reported the check as passing. Canonicalisation is NFC plus
removal of invisible bidi and zero-width marks, and deliberately does not fold
case, accents or Cyrillic Ё — merging genuinely different names is the
symmetric failure and just as damaging.

Two choices that look like bugs and are not. Russian surnames are listed in both
gendered forms and paired by digest, so a pseudonym can read as grammatically
odd; enforcing agreement would make the pseudonym encode the subject's gender.
And locale is configuration rather than per-record detection, because a
locale-matched pseudonym preserves an attribute the pseudonym was otherwise
removing — that should be a decision someone makes, not a default.

A test asserting that no bundled set mixes scripts caught two stray Latin and
Cyrillic entries in the Mandarin pool during authoring.

## 2026-09-08 — S1 policy layer and build-time fail-closed

Privacy decisions moved out of model classes and into YAML profiles. The engine
now applies a decision from `PrivacyPolicyResolver` rather than reading
`suggestedAction`, so changing what is disclosed no longer means editing and
redeploying application code. Metadata is read from plain classes and accessors
as well as record components, `@SubjectIdentifier` lands, and an annotation
processor fails the build on any unclassified field of an exposed model. 55 tests.

**Cost:** the interesting problem was combining a profile rule with a model
author's suggestion. Letting the profile win outright would let an annotation
widen disclosure by being edited; letting the annotation win would make the
profile advisory. Both are wrong, so the two are combined by taking the stricter,
which needs a total order over actions — and that order is a judgement, not a
fact. It is stated and argued in `ActionStrictness`. The placement worth knowing
is SYNTHESIZE below REDACT: a stable pseudonym is linkable within its scope,
which is strictly more disclosure than redaction, even though it looks more
thorough. `override: true` exists so an over-classified field can still be
relaxed, deliberately and visibly.

Profiles are parsed by hand from a generic map rather than data-bound. Binding
turns a misspelled classification into a rule that silently does not apply, and
a privacy profile that quietly loses a rule is the worst kind of configuration
bug: nothing fails, and less is protected than the file says.

## 2026-09-08 — S0 walking skeleton

One vertical thread now runs from an MCP tool call to a pseudonymised response:
eight Maven modules, the annotation set, a tree-based scrubbing engine, keyed
HMAC pseudonymisation, an independent output validator, a per-writer audit chain
and a stdio MCP server. Thirty-one tests, architecture rules and a banned-
dependency rule run on every build. `Patrick Murphy` reaches a client as
`Jordan Walsh (3W1Q)` with the email redacted and the correlation identifier
absent entirely.

**Cost:** three things cost real time and would cost it again. The MCP SDK 2.x
line has no Spring transport artifact — `mcp-spring-webmvc` stopped at 0.18.4 —
and the `mcp` aggregate pulls `mcp-json-jackson3`, which puts Jackson 3 beside
Spring Boot's Jackson 2 and silently registers the scrubbing module on a mapper
that never serialises MCP output; depend on `mcp-core` plus `mcp-json-jackson2`
and let Enforcer ban the rest. Maven 3.8.6 defaults to compiler plugin 3.1
(no `--release`) and surefire 2.12.4 (cannot run JUnit 5 at all, so the suite
reports success having run nothing); both are pinned in the parent. And a stdio
smoke test fed from a file produces no response to `tools/call` — EOF closes the
transport before the async reply flushes, which looks exactly like a server bug.
Hold stdin open.

Two decisions worth not relitigating: scrubbing operates on a Jackson tree
because records cannot be written back to, and pseudonyms carry a 20-bit ASCII
discriminator because a 32x32 name pool collides well before 50,000 subjects and
a collision tells the model that two people are one.

