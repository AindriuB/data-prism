---
title: Pseudonymise customer data before an LLM agent sees it (Spring Boot)
description: Give an LLM agent or MCP client a pseudonymised view of customer data from a Spring Boot API, without exposing raw identifiers.
---

# Pseudonymise customer data before an LLM agent sees it

A Java/Spring team wants to put an LLM agent or MCP client in front of an
internal REST API that holds customer data — support tickets, orders,
account records — so the agent can investigate and answer questions. Sending
that API's raw responses straight to the model means names, emails and
account numbers leave the deployment as plain text, with no record of what
was disclosed or why.

## What Data Prism does

Data Prism sits between the MCP caller and the source API and returns a
scope-local view of one entity instead of the raw record. Each subject gets
one synthetic pseudonym, derived deterministically from the privacy scope,
the subject, the namespace, the algorithm version and an HMAC key — never
random, never stored in plaintext — so the same customer reads as the same
pseudonym everywhere the caller sees them within one privacy scope (case).
A different case gives the same subject a different, unrelated-looking
pseudonym, on purpose — see
[`docs/use-cases/consistent-pseudonyms-across-systems.md`](consistent-pseudonyms-across-systems.md).
Fields that are not classified are redacted or the call is refused
outright, rather than passed through unexamined. See
[`docs/tools.md`](../tools.md) for what the two shipped tools,
`get_entity_context` and `compare_entity_sources`, take and return.

Two deployment options consume the same configuration contract and the same
privacy pipeline:

- **The standalone server**, the primary product: an operator runs it in
  front of an existing API as its own process.
- **The Spring Boot starter**, the embedded option: a Spring application
  supplies its own reviewed `DataSourceAdapter` and `IdentityResolver` beans
  and gets the same protected HTTP surface embedded in it.

Both are described in [`docs/configuration.md`](../configuration.md), which
is the authoritative contract for either one.

## What it does not do

Data Prism does not route or proxy general traffic between systems, and it
is not an ETL platform, a master-data system or an identity provider.
**It is not anonymisation**: pseudonymisation, as [GDPR Art.
4(5)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#art_4)
defines it, keeps data re-attributable to its subject given additional
information, so [Recital
26](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679#rct_26)
treats it as still personal data, and sending it to a third-party model is
still processing that needs its own lawful basis. See
[`docs/use-cases/gdpr-data-minimisation-mcp.md`](gdpr-data-minimisation-mcp.md)
for what that means in practice and what stays the operator's job.

## Where to go next

- [`docs/quickstart.md`](../quickstart.md) brings up the standalone server,
  a synthetic fixture API and a local token issuer with one command, and
  proves a real, pseudonymised `get_entity_context` call end to end before
  you touch your own API.
- [`docs/protect-your-own-api.md`](../protect-your-own-api.md) walks a flat
  or one-level-nested JSON REST API from nothing to a working
  `get_entity_context` call, configured entirely in YAML — no Java adapter
  required for that shape of response.
- If your API's response nests deeper, or needs custom fetch logic,
  [`docs/extending.md`](../extending.md) covers writing a reviewed Java
  `DataSourceAdapter` instead.
- [`docs/agents/`](../agents/README.md) covers connecting an MCP agent
  client to either the quickstart stack or a real deployment.
