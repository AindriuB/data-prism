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

