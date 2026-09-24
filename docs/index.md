---
title: Data Prism
description: Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients.
---

# Data Prism

Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients.

[Quickstart](quickstart.md){ .md-button .md-button--primary }
[Developer guide](developer-guide/index.md){ .md-button }

<div class="grid cards" markdown>

-   **Pseudonymise per scope**

    ---

    The pseudonym is a function of both the subject and the case id the
    caller's session carries. Two investigations, with two different case
    ids, cannot correlate their findings by comparing pseudonyms.

    [Scope isolation](tools.md#scope-isolation)

-   **Fail closed**

    ---

    The server and starter load only the bundled privacy profiles; both set
    `unclassified: FAIL_REQUEST`, refusing the whole response for a field
    nobody classified.

    [Configuration contract](configuration.md#dataprism-vocabulary)

-   **Verifiable audit trail**

    ---

    Replaying a writer's chain catches an edit to any hashed field anywhere
    in it, including the last record, and catches a deletion when later
    records follow it. It cannot detect truncation of the most recent
    records, deletion of a whole boot's records, or recomputation by
    someone who already has write access.

    [What this does and does not prove](audit.md#what-this-does-and-does-not-prove)

</div>

{% include-markdown "../README.md" start="<!-- site-intro:start -->" end="<!-- site-intro:end -->" %}

## Where to go next

- **[Local Compose quickstart](quickstart.md)** — one command, no local JDK
  or Maven install, a real pseudonymised MCP response.
- **[Protect your own API](protect-your-own-api.md)** — a YAML-only
  walkthrough from a real JSON REST API to a working `get_entity_context`
  call.
- **[Connect an agent client](agents/README.md)** — local stdio for
  development, or authenticated Streamable HTTP against a real deployment.
- **[FAQ](faq.md)** and **[Comparison](comparison.md)** — direct answers,
  and how Data Prism relates to Presidio, LLM Guard, NeMo Guardrails and MCP
  gateways.
