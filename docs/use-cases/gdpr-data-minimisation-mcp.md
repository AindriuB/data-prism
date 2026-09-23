---
title: GDPR data minimisation for MCP tools
description: How Data Prism's pseudonymisation and fail-closed classification support GDPR data minimisation for MCP tools, and what stays the operator's job.
---

# GDPR data minimisation for MCP tools

A team exposing an internal customer API through MCP tools has to account
for [GDPR Art.
5(1)(c)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#005.001),
which requires personal data to be "adequate, relevant and limited to what
is necessary in relation to the purposes for which they are processed
('data minimisation')". Applied to an MCP tool call: handing an LLM agent
an API's full raw response, on the chance that some of it turns out to be
useful for the caller's purpose, does not sit well with that requirement.

**This is not legal advice.** It describes what the shipped code does and
does not do. Whether a given deployment satisfies the GDPR is a legal
judgement for the operator and their advisers, made against their own
processing, not something a piece of software can certify.

## What Data Prism does

Every field an MCP tool could return is either explicitly classified or it
is not. Unclassified fields are redacted or the call is refused outright —
fail-closed, not fail-open — so a source's raw response never reaches a
caller unexamined by default. A field that is classified is pseudonymised,
redacted or removed according to its classification before it reaches the
caller: where the configured action is pseudonymisation, one subject gets
one deterministic pseudonym within its privacy scope. Either way, no raw
identifier, source host, path or credential is ever included in a
response. See [`docs/tools.md`](../tools.md) for what the two shipped tools
return, and [`docs/configuration.md`](../configuration.md) for the
classification and scope vocabulary that decides this.

A deployment can also keep a hash-chained audit trail of every privacy
decision — an allow, a redaction, a refusal — described in
[`docs/audit.md`](../audit.md). It is opt-in, and that page states plainly
what its offline verifier does and does not prove.

[GDPR Art.
4(5)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#art_4)
defines pseudonymisation as processing personal data so that it can no
longer be attributed to a subject without additional information kept
separately. **Pseudonymised data is still personal data**: [Recital
26](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#rct_26)
states directly that personal data which have undergone pseudonymisation,
and which could be attributed to a natural person by the use of additional
information, remain information on an identifiable natural person. Data
Prism does not change that: sending its output to a third-party model is
still processing personal data.

## What it does not do

Installing Data Prism does not, by itself, make a deployment compliant with
the GDPR, and pseudonymisation is not anonymisation. Fail-closed
classification and per-scope pseudonymisation are consistent with the
design obligations in [GDPR Art.
25](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#025.001)
(data protection by design and by default) and can contribute to the
technical measures [GDPR Art.
32](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#032.001)
(security of processing) requires, but neither article is satisfied by
installing one component, and Data Prism does not assess the rest of a
deployment against either one.

## What stays the operator's job

- **Lawful basis** for processing the personal data the API holds, and for
  sending a pseudonymised view of it to an LLM agent or MCP client.
- **A DPIA**, where the processing requires one.
- **A transfer mechanism**, where the model or agent runtime receiving the
  output sits outside the EU.
- **HMAC key custody.** Data Prism pins pseudonymisation to a configured key
  reference (`dataprism.privacy.hmac-key`) and refuses startup if it cannot
  be resolved. [`docs/configuration.md`](../configuration.md) describes only
  that reference/pinning contract; supplying the key via a provider,
  protecting it, and rotating it are the operator's job, and this release
  does not document a rotation procedure.
- **Retention** of source data, pseudonym mappings and any audit trail kept.
- **Deciding what is classified as what** — which fields are personal data,
  which are sensitive, and which profile and rules apply to them — is a
  configuration and review decision the operator makes; Data Prism enforces
  whatever that decision was, and refuses to guess when it is missing.

## Where to go next

- [`docs/configuration.md`](../configuration.md) is the complete
  configuration contract, including classification and key-reference setup.
- [`docs/quickstart.md`](../quickstart.md) shows classification-driven
  pseudonymisation and redaction running end to end against fixture data.
- [`docs/protect-your-own-api.md`](../protect-your-own-api.md) walks
  classifying and protecting a real JSON REST API.
- [`docs/audit.md`](../audit.md) covers the opt-in audit trail, and what its
  verifier proves and does not prove.
