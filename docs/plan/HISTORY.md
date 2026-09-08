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

