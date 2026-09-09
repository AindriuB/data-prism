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

