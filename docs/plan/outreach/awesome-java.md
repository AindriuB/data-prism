# Draft: akullpp/awesome-java

Target list: <https://github.com/akullpp/awesome-java>
Access date: 2026-09-23

## Contribution rules (quoted)

Source: <https://github.com/akullpp/awesome-java/blob/main/CONTRIBUTING.md>, fetched 2026-09-23 via
`gh api repos/akullpp/awesome-java/contents/CONTRIBUTING.md --jq .content | base64 -d`.

> # Contribution Guidelines
>
> ## Suggest a Project
>
> Edit `README_SOURCE.md`, add one line under the best category, and open one pull request:
>
> ```markdown
> - [Project Name](https://github.com/owner/repository) - A concise, neutral description ending with a period.
> ```
>
> Use the canonical GitHub repository when one exists. The generated `README.md` handles ordering, counts,
> stars and activity; contributors do not need to run the generator.
>
> A project should:
>
> - make Java a primary API, runtime, implementation target or substantial first-class integration;
> - be noteworthy because it is widely recommended, innovative, unique or fills a useful niche;
> - provide English documentation and clear licensing;
> - have clear pricing and a free tier when commercial.
>
> Known GitHub SPDX licenses appear automatically; do not repeat them in descriptions or add license or
> commercial badges manually. If no chip is available, disclose restrictive, noncommercial or source-available
> terms in the entry. Keep descriptions short, factual and distinctive from similar entries. Use
> `Miscellaneous` only when no focused category fits.
>
> Search existing entries and issues before submitting. Self-promotion is reviewed carefully but is welcome
> when the project meets the same criteria. Use one pull request per project.

(The document also covers "Suggest a Resource" and umbrella-project metadata; neither applies to this entry.)

No star-count or age threshold is stated. The bar is the four bullet points above, plus documentation/license
clarity.

## PR template (quoted)

There is also a live pull request template that every PR is opened against.

Source: <https://github.com/akullpp/awesome-java/blob/main/.github/pull_request_template.md>, fetched
2026-09-23 via `gh api repos/akullpp/awesome-java/contents/.github/pull_request_template.md --jq .content | base64 -d`.

> ## Suggestion type
>
> - [ ] Project
> - [ ] Resource
>
> ## Checklist
>
> - [ ] I searched the list and existing issues for duplicates.
> - [ ] I changed `README_SOURCE.md`, not the generated `README.md`.
> - [ ] This pull request contains one suggestion.
> - [ ] The suggestion is relevant to Java or the JVM and fits its chosen category.
> - [ ] I used the canonical project or resource link.
> - [ ] The suggestion is current and maintained.
> - [ ] The concise, neutral description explains its distinguishing value and ends with punctuation.
> - [ ] Licensing is clear and any restrictive terms are disclosed where applicable.

The PR body below is filled in against this template, not left blank.

## Section and format

File: `README_SOURCE.md` (not the generated `README.md`). Section: `### Security`.

Format, exactly as specified: `- [Project Name](https://github.com/owner/repository) - A concise, neutral description ending with a period.`
Known SPDX licenses (Apache-2.0 here) render automatically from the repository metadata and must not be
repeated in the description text.

## Exact entry line

The section is not alphabetically sorted today: its actual order runs ... Certificate Ripper,
Dependency-Track, OWASP Dependency-Check, Cryptomator, jjwt ... — so "Cryptomator" and "Dependency-Track" are
not adjacent to each other (Cryptomator sits two entries after Dependency-Track, not before it). The one
alphabetically correct insertion point that doesn't disturb anything already in order is immediately after
"Certificate Ripper" and before "Dependency-Track" (`Certificate Ripper` < `Data Prism` < `Dependency-Track`).
Re-check the exact neighbours at post time, since the section may have changed:

Description is the project's own tagline (T, 96 chars, ends with a period, per the required format):

```markdown
- [Data Prism](https://github.com/AindriuB/data-prism) - Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients.
```

## PR title

`Add Data Prism (MCP privacy layer) to Security`

## PR body

```markdown
Adds Data Prism to the Security section of README_SOURCE.md, alphabetically immediately after "Certificate
Ripper" and before "Dependency-Track" (the section isn't fully sorted today; those are the nearest two entries
this insertion keeps in correct alphabetical order).

Data Prism is a Java/Spring privacy layer for teams putting LLM agents or MCP clients in front of internal
APIs that hold customer data. It pseudonymises personal data per privacy scope, redacts or refuses anything it
cannot classify, and can keep a hash-chained audit trail (opt-in). Java is the implementation language and the
primary runtime; a Spring Boot starter is one of its two supported deployment surfaces.

Repo: https://github.com/AindriuB/data-prism
License: Apache-2.0 (free, no commercial tier)

Disclosure: I am the maintainer of Data Prism.

## Suggestion type

- [x] Project
- [ ] Resource

## Checklist

- [x] I searched the list and existing issues for duplicates. I did not find another entry covering this
      niche (a fail-closed, privacy-scoped pseudonymisation layer for the MCP/LLM-agent access path).
- [x] I changed `README_SOURCE.md`, not the generated `README.md`.
- [x] This pull request contains one suggestion.
- [x] The suggestion is relevant to Java or the JVM and fits its chosen category. Java is the primary
      implementation language and runtime; Security fits better than any other existing category.
- [x] I used the canonical project or resource link.
- [x] The suggestion is current and maintained.
- [x] The concise, neutral description explains its distinguishing value and ends with punctuation.
- [x] Licensing is clear (Apache-2.0) and there are no restrictive terms to disclose.
```

## Eligibility verdict: ready

The project meets all four listed bullets: Java is the primary implementation language and runtime (not a
secondary binding); it fills a niche not otherwise covered in the Security section (privacy/pseudonymisation
for the LLM-agent-to-internal-API path, as distinct from the section's existing authentication, cryptography
and dependency-scanning entries); documentation is in English; licensing is Apache-2.0, clearly stated, no
commercial tier to disclose. No popularity or age threshold applies.

## Pre-post checklist

- [ ] Re-fetch `README_SOURCE.md`'s current Security section immediately before posting to confirm the
      alphabetical insertion point and that no near-duplicate entry has since been added.
- [ ] Site is not live yet (task 82). This draft has no site links to swap.
