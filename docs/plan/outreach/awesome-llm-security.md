# Draft: corca-ai/awesome-llm-security (optional)

Target list: <https://github.com/corca-ai/awesome-llm-security>
Access date: 2026-09-23

## Contribution rules (quoted)

Source: <https://github.com/corca-ai/awesome-llm-security/blob/main/CONTRIBUTING.md>, fetched 2026-09-23 via
`gh api repos/corca-ai/awesome-llm-security/contents/CONTRIBUTING.md --jq .content | base64 -d`.

> # Contribution Guidelines
>
> We follow the [Awesome Manifesto](https://github.com/sindresorhus/awesome/blob/main/awesome.md).
>
> If you want to add, remove, or change things on this repository, please **just submit a pull request**.
> That's all.
>
> I'll add extra guidelines if needed.

No popularity or age threshold, and no explicit scope test beyond the Awesome Manifesto it defers to (which is
about list-formatting conventions, not project eligibility). The repository's own one-line description is "A
curation of awesome tools, documents and projects about LLM Security."

## Section and format

Section: `## Tools`. Existing entries: `- [Name](url): description. [optional ![GitHub Repo stars](shields.io badge)]`
— the stars badge is present on most entries but not all (e.g. the first entry, UTCP, has none), so it is
decorative, not required.

## Exact entry line

```markdown
- [Data Prism](https://github.com/AindriuB/data-prism): fail-closed privacy layer that pseudonymises enterprise data before it reaches an LLM agent or MCP client; classifies data at the tool boundary rather than inspecting model output or tool-call arguments for attacks. ![GitHub Repo stars](https://img.shields.io/github/stars/AindriuB/data-prism?style=social)
```

## PR title

`Add Data Prism to Tools`

## PR body

```markdown
Adds one entry under Tools for Data Prism, a fail-closed privacy layer for the MCP/LLM-agent data-access path.
It pseudonymises personal data per privacy scope and redacts or refuses anything it cannot classify before it
reaches the model, so it sits in the "reduce what an LLM/agent can see" part of this list's scope. It is not a
prompt-injection or jailbreak defence, and the entry says so.

Repo: https://github.com/AindriuB/data-prism
License: Apache-2.0

Disclosure: I am the maintainer of Data Prism.
```

## Eligibility verdict: hold

CONTRIBUTING sets no eligibility rule beyond "submit a PR" — there is no formal blocker. The open question is
fit, not process: every other entry in the Tools section is an attack, defence or red-teaming tool for LLMs
themselves (prompt-injection detection, jailbreak evaluation, fuzzing, vulnerability scanning). Data Prism is a
data-privacy/pseudonymisation control at the boundary between an internal API and an LLM/MCP client — a
different, if adjacent, concern, and the entry line above says plainly that it is not the kind of defence most
neighbouring entries provide. Submitting before that fit is clearer risks looking like scope-stretching for
visibility, which is exactly the goodwill cost the project's own reputation risk calls out. Hold until either
this list's maintainer has accepted a comparable data-privacy-for-LLMs tool (setting a precedent this entry can
point to), or the project adds a capability that is unambiguously "LLM security" in the sense the rest of the
section uses.

## Pre-post checklist

- [ ] Before posting, check whether any comparable data-privacy/pseudonymisation tool has since been accepted
      into the Tools section; if so, cite it in the PR body as precedent.
- [ ] Site is not live yet (task 82). This draft has no site links to swap.
