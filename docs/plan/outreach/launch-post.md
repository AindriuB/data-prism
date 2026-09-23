# Launch write-up

Owner posts this by hand, one venue at a time. No agent submits, comments or posts anywhere.

**Pre-post checklist:**
- [ ] Swap every `https://github.com/AindriuB/data-prism/blob/main/...` link below to the equivalent
      `https://aindriub.github.io/data-prism/...` site URL once task 82 deploys the docs site. Until then, the
      GitHub blob links are the only ones that resolve.
- [ ] Re-check the star count and open-issue count in the "Baseline" line below; they will have moved since
      2026-09-23.
- [ ] Re-run task 81's honesty and wording checks against this whole directory before posting, in case an
      edit since reintroduced an overclaim.
- [ ] Confirm the README on `main` no longer says the hash-chained audit sink/verifier is "Not built" (fixed
      on the discoverability branch, PR #98).

## Angle

> Redaction breaks LLM investigations; consistent pseudonyms don't — a fail-closed privacy layer for MCP in
> Spring Boot.

## Body

An LLM investigating live business data spread across several internal systems can't safely be given direct
API access: those systems carry personal and confidential data, and each one names the same person
differently. The obvious fix — redact everything sensitive — destroys the investigation, because once three
systems' names for one person are all `[REDACTED]`, the model can no longer tell whether it's looking at one
person or three. ([The problem](https://github.com/AindriuB/data-prism/blob/main/README.md#the-problem))

[Data Prism](https://github.com/AindriuB/data-prism) sits between an MCP client (or any LLM agent) and those
APIs and does the opposite: it makes identity *consistent* instead of erasing it. One subject gets one
synthetic identity across every source, derived deterministically and reproducibly, never randomly and never
stored in plaintext. The same person reading differently in three systems shows up as one person to the model
— and where those systems actually disagree about that person's data, the answer carries a finding that says
so, rather than hiding the disagreement behind uniform redaction. Pseudonyms are scoped, so the same person in
two different investigations gets two different synthetic identities and nothing correlates across cases by
accident. ([What Data Prism does](https://github.com/AindriuB/data-prism/blob/main/README.md#what-data-prism-does))

It fails closed by default: an unclassified field refuses the whole response unless a deployment has
explicitly chosen a looser setting, and every refusal is audited with the classification and the path, never
the value. Passing unclassified data through unchanged is possible, but only by naming the one setting spelled
for exactly that risk, `PASS_THROUGH_UNSAFE` — it is not what happens by omission.
([`dataprism.privacy`](https://github.com/AindriuB/data-prism/blob/main/docs/configuration.md#dataprism-vocabulary))

It can also keep a durable, hash-chained audit trail of what was recorded and released — a single file, no
rotation, with a separate hash chain per writer inside it, and an offline verifier that replays each chain.
That's opt-in (`dataprism.audit.sink: hash-chained`), and shipped in 0.3.0.
([`FileAuditSink` and the offline verifier](https://github.com/AindriuB/data-prism/blob/main/docs/audit.md);
[CHANGELOG](https://github.com/AindriuB/data-prism/blob/main/CHANGELOG.md)) Read the Limits section below
before treating that trail as more than it is.

It also scans every response for a fixed set of identifier shapes — IBAN, payment card, Irish PPSN, US SSN,
email, international phone, JWT, API key — wherever they appear, free-text fields included, and refuses if one
turns up somewhere nothing declared as sensitive. That is shape detection, not general PII or name detection:
everything else is classified from what a deployment's own field metadata (annotations or its YAML catalogue)
declares, not from reading the text. ([`SensitiveDataScanner`](https://github.com/AindriuB/data-prism/blob/main/data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitiveDataScanner.java),
[Components](https://github.com/AindriuB/data-prism/blob/main/docs/architecture.md#components))

Two deployment surfaces ship today: a standalone Streamable HTTP MCP server (the primary target), and a Spring
Boot starter for embedding the same pipeline in an existing application. A one-command local Compose
quickstart proves a real MCP call against the real privacy engine, no local JDK or Maven install needed.
([Try it](https://github.com/AindriuB/data-prism/blob/main/README.md#try-it),
[Local Compose quickstart](https://github.com/AindriuB/data-prism/blob/main/docs/quickstart.md)) It's
Apache-2.0. ([LICENSE](https://github.com/AindriuB/data-prism/blob/main/LICENSE))

Baseline, for anyone wondering how new this is: 1 GitHub star as of 2026-09-23. This is a young, small project,
not an established one — treat everything above as "here's what it does today," not "here's what everyone
already uses."

## Limits

Read this before treating anything above as more than it is.

- **This is not anonymisation.** Under GDPR Art. 4(5), pseudonymised data is still personal data. Sending Data
  Prism's output to a third-party model is still processing, and still needs a lawful basis, a DPIA, and a
  transfer mechanism where the provider is outside the EU. The platform reduces exposure; it does not remove
  that obligation.
  ([What it is not](https://github.com/AindriuB/data-prism/blob/main/README.md#what-it-is-not))
- **This is not a prompt-injection defence.** A heuristic flags source values that read like an instruction to
  a model (`InstructionContentHeuristic`) and attaches a finding — it never strips or rewrites the value, by
  design, because doing so would hide the attack from the one person who'd recognise it. It is deliberately a
  heuristic and deliberately not a defence: anyone determined will phrase around it, and it does not inspect
  model output or tool-call arguments at all.
  ([D3. Prompt injection — flag, never sanitise](https://github.com/AindriuB/data-prism/blob/main/docs/design-review.md#d3-prompt-injection--flag-never-sanitise))
- **Not yet built:** the re-identification operator surface (deferred past V1 by decision — see
  [Decisions worth knowing](https://github.com/AindriuB/data-prism/blob/main/docs/architecture.md#decisions-worth-knowing));
  the Elasticsearch connector and its search tools, listed as `(planned)` in the component map
  ([Components](https://github.com/AindriuB/data-prism/blob/main/docs/architecture.md#components)); and two of
  the four MCP tools named in the original design, `search_entity_data` and `describe_entity_model` — only
  `get_entity_context` and `compare_entity_sources` ship today.
  ([Not yet built](https://github.com/AindriuB/data-prism/blob/main/docs/tools.md#not-yet-built))
- **What the audit trail does not prove, deliberately, not as an oversight:** truncating a writer's most
  recent records is structurally undetectable — an append-only file with its tail removed replays perfectly,
  because there is nothing left in it to disagree with; deleting a whole boot's records has the same shape one
  level up. The hash chain is unkeyed SHA-256, so anyone able to write to the file directly can edit or delete
  a record and then recompute every hash that follows it — the resulting chain still replays clean. Whether
  the file is actually append-only in practice, and who else can open it, is a property of the deployment's
  storage and access control, not of this code. None of this should be read as a claim that the durable audit
  log can't have been altered by someone with write access to it, or that it is independently complete on its
  own — it is evidence for what an offline verifier can actually check, within the boundary just described. It
  also says nothing about metric labels or trace attributes: this release's PII scan tests cover logs and the
  audit file itself, not those two.
  ([What this does and does not prove](https://github.com/AindriuB/data-prism/blob/main/docs/audit.md#what-this-does-and-does-not-prove))

## Show HN

- `Show HN: Data Prism, a fail-closed MCP privacy layer for Spring Boot` (68 chars)
- `Show HN: Data Prism, an MCP privacy layer for Java/Spring Boot` (62 chars)

First-comment guidance for whoever posts: lead with the angle line, then the "Limits" section verbatim or
close to it — Show HN audiences downvote posts that read as overclaiming once someone checks, and this project
is one star old.

## r/java

- `Data Prism: a fail-closed MCP privacy layer for Spring Boot (pseudonymises PII before an LLM agent sees it)`

Suggested framing for r/java specifically: open with the two supported surfaces (standalone server vs. Spring
Boot starter) and the Maven module count, since that audience will ask "is this a real project or a toy"
before anything else; link the Local Compose quickstart early so people can run it without installing
anything.
