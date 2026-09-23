# 77 — Write the FAQ and a fair, sourced comparison page

**Repo:** `.`
**Wave:** 1 (spec task T2)
**Depends on:** none
**Base branch:** the LOCAL `discoverability` branch (not pushed, not `main`).
`wt-new.sh` bases new worktrees on `main`, so right after it, before any edit,
run `git -C <worktree> reset --hard discoverability`. The branch merges back
into `discoverability`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/77-faq-and-comparison.md`.
**Owns:**
- docs/faq.md *(new)*
- docs/comparison.md *(new)*

## Goal

Answer the questions this audience actually types into search engines and
assistants, in a form an assistant can quote accurately. Place Data Prism
fairly next to the tools it gets confused with: every claim about another
tool is sourced, and the page says plainly where Data Prism does not fit.

## Context

- `docs/plan/specs/2026-09-23-discoverability.md` — binding. Read
  "Canonical description", "Never use", "T2" and "Risks → Overclaiming".
- Primary keywords: MCP privacy; pseudonymise/pseudonymize PII for LLM
  agents; LLM data privacy for internal APIs; Spring Boot MCP server; GDPR
  data minimisation for LLM/MCP tools. Use them where they read naturally.
  Do not stuff them.
- New pages carry YAML front matter (`title`, `description`). Existing docs
  must not get front matter, because line-number citations elsewhere depend
  on their line counts.
- Where to trace answers from:
  - Anonymous? `README.md` "What it is not", the "It is not anonymisation"
    paragraph (GDPR Art. 4(5)).
  - How pseudonyms are made: `README.md` "What Data Prism does" (the
    deterministic `(scope, subject, namespace, algorithm version, key)`
    derivation), and `CHANGELOG.md` `[0.3.0]` for the current discriminator.
  - PII in free text: `docs/tools.md:190` (free-text notes are flagged, not
    obeyed), `docs/architecture.md:227` (naive pattern detection rejected),
    and the S4 pattern-detection history (`docs/plan/HISTORY-INDEX.md` row
    S4). Trace the answer to code or docs, and do not guess.
  - Do I need Java? `docs/protect-your-own-api.md` (YAML-only JSON REST
    mode), `docs/extending.md` (reviewed Java adapter), and `README.md` "If
    you found this on the MCP registry".
  - Audit trail: `docs/audit.md:226` "What this does and does not prove".
    Quote its "does not prove" list. Do not paraphrase it into something
    stronger.
  - Prompt injection:
    `data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/InstructionContentHeuristic.java`.
    It flags instruction-like content. It is deliberately not a defence.
  - Production-ready? Version 0.3.0, `docs/tools.md` "Not yet built", and
    the corrected README Status paragraph once task 76 lands. At base, use
    `CHANGELOG.md` `[0.3.0]`, since the base README Status is stale.
- Comparison subjects: Microsoft Presidio, LLM Guard, NeMo Guardrails, and
  MCP gateways or proxies as a category. If you name a specific product,
  source it like the rest. Read each tool's own docs or repo at execution
  time, and record the date you accessed it.
- Use-case pages from task 78 are written in parallel at these fixed paths.
  You may link to them:
  `use-cases/pseudonymise-customer-data-spring-boot.md`,
  `use-cases/gdpr-data-minimisation-mcp.md`,
  `use-cases/consistent-pseudonyms-across-systems.md`.
  They are verified by task 82's strict build, not here.
- `docs/conventions.md:213` bans "comprehensive", "robust" and "seamlessly".

## Acceptance

- [ ] Both files begin with front matter holding a non-empty `title` and a
      `description` of at most 155 characters. The two descriptions differ.
- [ ] `docs/faq.md` has an H2 per question, each phrased as a question and
      ending in `?`, covering at least these seven:
      - is the output anonymous
      - how are pseudonyms made
      - does it detect PII in free text
      - do I need Java
      - what does the audit trail prove
      - does it stop prompt injection
      - is it production-ready
- [ ] Each FAQ answer's first sentence answers the question directly, so it
      can be quoted on its own.
- [ ] The "anonymous" answer says no and gives the Art. 4(5) reason.
- [ ] The audit answer quotes the "does not prove" items from
      `docs/audit.md` verbatim or near-verbatim, including tail truncation,
      whole-boot deletion and the unkeyed SHA-256 point, and links to
      `audit.md`.
- [ ] The prompt-injection answer says `InstructionContentHeuristic` flags
      content and is deliberately not a prompt-injection defence.
- [ ] `docs/comparison.md` opens with "Written by the Data Prism maintainer;
      as of <YYYY-MM-DD>" using the execution date.
- [ ] The comparison covers Presidio, LLM Guard, NeMo Guardrails and MCP
      gateways/proxies, with:
      - a "different layers" table
      - a "Use X instead when…" section for each
      - a "Where Data Prism does not fit" section naming free-text prompts,
        Python stacks, anonymisation or re-identification needs, and non-JSON
        sources without a Java adapter
- [ ] Every sentence about another tool carries a link to that tool's own
      docs or repo with an access date (e.g. "(accessed 2026-09-24)"). The
      reviewer opens every link and confirms that it supports the sentence.
- [ ] No sentence makes a claim about another tool's quality, accuracy or
      performance.
- [ ] Every claim about Data Prism links to the repo doc that supports it,
      and the reviewer traces each one.
- [ ] Links to repo docs are relative (`audit.md`, `tools.md#…`) and resolve
      from `docs/`. There are no `README.md:NNN`-style line citations.
- [ ] Honesty checks on both files:
      - Every hit of `grep -niE 'anonymi|compliant|tamper-proof|guarantee' docs/faq.md docs/comparison.md`
        is a negation or refers to another tool (e.g. Presidio's anonymizer).
        The reviewer lists each hit with a verdict.
      - `grep -niE 'GDPR-compliant|tamper-proof|API gateway|Spring AI|comprehensive|robust|seamless' docs/faq.md docs/comparison.md`
        is empty. "MCP gateway" is allowed. "API gateway" is not.
      - Nothing describes pseudonymisation as anonymisation.
      - No audit wording exceeds `docs/audit.md`: never "immutable",
        "tamper-proof" or "independently complete".
- [ ] `git diff --name-only discoverability` lists only the two owned files.

## Out of scope

- The use-case pages (task 78), the site nav and meta tags (task 82), and
  README links to these pages (task 83).
- Editing any existing doc, including to fix something found while tracing.
  Report it instead.
- Benchmarks, or any performance or accuracy comparison.
