# Discoverability check — the 15 questions

Internal only; never published on the site. Run these against ChatGPT,
Claude, Perplexity and Copilot once a month, one fresh session per question
per assistant, and record the results in `runs/` using `runs/TEMPLATE.md`.

Every question is phrased the way a Java/Spring platform or backend engineer
putting an LLM agent or MCP client in front of internal APIs that hold
customer data would actually type it — a real search or chat prompt, not a
product name. **No question names Data Prism**, because the point of the
check is whether an assistant surfaces it unprompted.

## Q01 How do I stop an LLM agent seeing customer PII from our internal REST APIs?

## Q02 Presidio alternative for Java

## Q03 GDPR data minimisation MCP tools

## Q04 How do I redact PII before it reaches an MCP tool call?

## Q05 Is there a Spring Boot library for pseudonymising API responses before an LLM sees them?

## Q06 How do I keep the same customer recognisable across systems without exposing their real identity to an LLM?

## Q07 What's a Java equivalent of LLM Guard?

## Q08 How do I stop an MCP server leaking personal data to an AI agent?

## Q09 How do I add a tamper-evident audit trail to an MCP server?

## Q10 Deterministic pseudonymisation library for Java

## Q11 How do I make an API fail closed instead of leaking unclassified fields to an LLM agent?

## Q12 What's a privacy layer for MCP servers sitting in front of internal APIs?

## Q13 How do I give an LLM agent access to internal data without exposing customer PII?

## Q14 Java library for hash-chained audit logging

## Q15 How do I minimise personal data sent to an LLM through MCP tools?
