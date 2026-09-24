# Discoverability (SEO + GEO) — agreed design, 2026-09-23

Owner-approved design for making Data Prism findable by search engines and
cited by AI assistants (ChatGPT, Claude, Perplexity, Copilot), so that real
users adopt it. This is the input to `/plan`. The planner turns it into task
files. Nothing here is a task file.

## Decisions (binding)

| | Decision |
|---|---|
| Audience | Java/Spring platform and backend teams putting LLM agents or MCP clients in front of internal APIs that hold customer data |
| Surface | An MkDocs Material docs site on GitHub Pages, built from the existing user-facing docs as the single source, plus the repo itself |
| Off-repo | Drafts only. The owner reviews and posts every awesome-list entry and the launch write-up. No agent submits anything |
| Measurement | No analytics or trackers on the site. Public signals, plus a fixed 15-question assistant check re-run monthly |
| Honesty | Nothing published claims more than the code does. Audit wording follows `docs/audit.md`. Pseudonymisation is never described as anonymisation. The comparison page is fair and sourced |

## Baseline (captured 2026-09-23, before any change merged)

Raw JSON is in `/srv/dev/scratch/data-prism-baseline-2026-09-23/`. T5 commits it.

| Signal | Value |
|---|---|
| Stars / forks / watchers / open issues | 1 / 0 / 0 / 7 |
| Traffic views, last 14 days | 318 total, 9 unique (almost all the owner) |
| Clones, last 14 days | 1,358 total, 346 unique (mostly CI and automation, not people) |
| Referrers | github.com only (184 views, 7 unique) |

The window includes the v0.3.0 release activity. The owner still has to run
the 15-question assistant check once.

## Facts the planner must respect (verified by the design pass)

- **Stale README Status.** `README.md:7-24` says the hash-chained sink and
  verifier are "Not built". CHANGELOG 0.3.0 says both shipped. T1 fixes this,
  otherwise every surface built from the README repeats the wrong claim.
- **publish-mcp.yml name checks are line-anchored.** It checks
  `docker/distribution/Dockerfile` (the `LABEL io.modelcontextprotocol.server.name`
  line), the README's `<!-- mcp-name: … -->` line and `server.json` `.name`.
  Those lines must stay byte-identical, each on its own line, and its three
  name extractions must still agree.
- **`server.json` limits.** `.description` has `maxLength: 100` (schema
  2025-12-11). `websiteUrl` is allowed. `mcp-publisher validate server.json`
  must pass locally. CI only validates on a tag push.
- **Published metadata is frozen until the next release.** Central, the MCP
  registry and GHCR keep their 0.3.0 descriptions. Do not bump any version.
- **Doc paths are baked into code.** `DataPrismConfigurationFailureAnalyzer.java:34`
  and its tests assert `docs/configuration.md` and `docs/quickstart.md`. Never
  move or rename user docs.
- **Line-number citations.** `docs/extending.md` cites README line numbers
  (e.g. `README.md:160-163`), and PLAN.md cites line numbers inside the docs.
  So existing docs get **no front matter**, and README line citations become
  section-name references.
- **`robots.txt` does nothing on a project Pages site**, because crawlers only
  read it at the host root. Don't ship one. Submit the sitemap through Search
  Console and Bing Webmaster Tools instead.
- **Keep `docs/pack.md` off the site.** It is the superseded original spec and
  describes tools that were never built. Assistants would cite it as current
  behaviour.

## Canonical description (use exactly this everywhere)

- **Tagline T** (at most 100 chars): `Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients.`
- **Description D** (about 285 chars):
  `Data Prism is an open-source privacy layer for Java/Spring teams putting LLM agents or MCP clients in front of internal APIs holding customer data. It pseudonymises personal data per privacy scope, refuses anything unclassified, and can keep a hash-chained audit trail.`
  It says "can keep" because the hash-chained sink is opt-in.
  - 2026-09-24: "redacts or refuses" → "refuses", owner decision; shipped
    profiles use `FAIL_REQUEST`.
- **Primary keywords:** MCP privacy; pseudonymise/pseudonymize PII for LLM
  agents; LLM data privacy for internal APIs; Spring Boot MCP server; GDPR data
  minimisation for LLM/MCP tools.
- **Secondary keywords:** deterministic HMAC pseudonymisation, privacy scope,
  fail-closed classification, PII redaction, hash-chained audit trail, Model
  Context Protocol Java SDK, consistent pseudonyms across systems.
- **Never use:**
  - "anonymisation", except to say it is not that
  - "GDPR-compliant"
  - "tamper-proof"
  - "API gateway" (the README says it is not one)
  - "Spring AI" (no integration exists)
  - "comprehensive", "robust", "seamlessly" (conventions rule)
- **GitHub topics:** keep the current 6 and add `model-context-protocol pseudonymization pii gdpr data-privacy data-minimization llm llm-security ai-agents audit-log hmac` (17 of the 20 allowed).

## Waves and tasks

The Owns lists must be disjoint within a wave. Every task follows the usual
implement, test, review loop.

### Wave 0 — scribe, standalone

- Add a rule to `docs/workflow.md`'s tester guidance: never wait with
  `pgrep -f <pattern>` inside a shell whose own command line contains that
  pattern. Wait on a captured PID, or use `timeout`. On 2026-09-23 nine tester
  wait-loops from tasks 59–75 ran for hours, because the `pgrep` matched the
  loop itself.

### Wave 1 (no dependencies)

**T1 canonical-identity**
- Owns: `README.md`, root `pom.xml` (optionally the starter and connectors-rest poms), `server.json`, `docker/distribution/Dockerfile`, `docker/server/Dockerfile`, new `CITATION.cff`, `docs/extending.md`.
- README:
  - The opening is T, then D, then a two-sentence "Who it's for".
  - Fix the Status paragraph so it matches CHANGELOG 0.3.0 and `docs/tools.md` "Not yet built".
  - Add `<!-- site-intro:start -->` and `<!-- site-intro:end -->` around the intro (tagline through "Try it").
  - Add badges (build, Maven Central, license) outside those markers.
- pom `<description>` = D.
- `server.json` `.description` = T.
- `docker/distribution/Dockerfile`: add separate OCI `LABEL` lines for `title`, `description`, `source`, `documentation` and `licenses=Apache-2.0`.
- `docker/server/Dockerfile`: a fixture-only description, in the style of `docker/fixtures/Dockerfile`.
- `CITATION.cff`: abstract = D, with no version and no date.
- `docs/extending.md`: change `README.md:NNN` citations to section names.
- Acceptance:
  - `grep -F` finds T in README, `server.json` and the Dockerfile description, and D in README, `pom.xml` and `CITATION.cff`.
  - The three name extractions from `publish-mcp.yml` agree, and `mcp-publisher validate server.json` passes.
  - `.description` is at most 100 chars, and every version is unchanged.
  - The reviewer traces every Status and audit claim to CHANGELOG or `docs/audit.md`.
  - `grep -rn 'README.md:[0-9]' docs/` is empty.
  - `docker buildx build --check` passes for both Dockerfiles, and `mvn -B -q -N validate` passes.

**T2 faq-and-comparison**
- Owns: `docs/faq.md`, `docs/comparison.md` (new files, with front-matter title and description).
- FAQ:
  - Questions are H2s phrased as real questions: is the output anonymous, how are pseudonyms made, does it detect PII in free text, do I need Java, what does the audit trail prove, does it stop prompt injection, is it production-ready.
  - The audit answer quotes the "does not prove" list from `docs/audit.md`.
  - The prompt-injection answer says what `InstructionContentHeuristic` does: it flags, and is deliberately not a defence.
- Comparison:
  - Covers Microsoft Presidio, LLM Guard, NeMo Guardrails and MCP gateways or proxies.
  - A "different layers" table, "use X instead when…", and "where Data Prism does not fit" (free-text prompts, Python stacks, anonymisation or re-identification needs, non-JSON sources without a Java adapter).
  - Opens with "Written by the Data Prism maintainer; as of <date>".
- Checkable rule for the comparison page: every sentence about another tool
  links to that tool's own docs or repo, with an access date. The reviewer
  opens every link. There are no claims about another tool's quality or
  performance.

**T3 use-case-pages**
- Owns: `docs/use-cases/**`.
- Pages:
  - Pseudonymise customer data before an LLM agent sees it (Spring Boot)
  - GDPR data minimisation for MCP tools (EUR-Lex 32016R0679 links for Art. 5(1)(c), 4(5), 25 and 32; "not legal advice"; what stays the operator's job)
  - Keep one customer recognisable across systems without exposing identity (pseudonym collapse, consistency findings, scope isolation)
- No new config snippets unless the tester runs them. Link to existing ones instead.

**T4 social-card**
- Owns: `docs/assets/**`, `docs-site/social-card/**`.
- A 1280×640 PNG under 1 MB whose text is tagline T, reproducible from a
  pinned Pillow script.

**T5 measurement**
- Owns: `docs/plan/discoverability/**` (internal, never on the site).
- `questions.md`:
  - 15 questions, Q01–Q15, phrased as this audience would ask them.
  - Examples: "How do I stop an LLM agent seeing customer PII from our internal REST APIs?", "Presidio alternative for Java", "GDPR data minimisation MCP tools".
- `runs/` template:
  - Record date, assistant, model and mode (fresh session, web search on).
  - A grid of each question × {ChatGPT, Claude, Perplexity, Copilot} → cited Y/N, URL cited, other tools named.
  - Citation rate = cited cells / 60.
- `snapshot.sh` (bash + gh + jq, run with the owner's `gh` auth):
  - Captures repo counts and `/traffic/{views,clones,popular/referrers,popular/paths}` into `snapshots/YYYY-MM-DD.json`.
  - Must run at least every 14 days, because traffic data expires.
- `baseline.md`: the baseline above, plus the raw JSON copied into `snapshots/`.
- A README with the procedure, and the numbers that must be read by hand: Central Portal download stats, GHCR totals, and Search Console / Bing once verified.
- Acceptance: `bash -n` and shellcheck pass, a live run produces valid JSON with `views.count`, and `questions.md` has exactly 15 ids.

**T6 outreach-drafts**
- Owns: `docs/plan/outreach/**` (internal).
- One file per list:
  - lists: punkpeye/awesome-mcp-servers, the canonical Spring or Spring-AI awesome list, akullpp/awesome-java, and optionally awesome-llm-security
  - quotes that list's CONTRIBUTING rules, **read at execution time**, with URL and date
  - gives the exact entry line, PR title and body, and an eligibility verdict ("hold" with the reason if ineligible, e.g. a popularity threshold)
- `launch-post.md`: angle "Redaction breaks LLM investigations; consistent pseudonyms don't — a fail-closed privacy layer for MCP in Spring Boot". Every claim links to a doc, and the limits are stated plainly. Include Show HN and r/java title variants.
- A status table in `README.md`.
- No submission script.

### Wave 2 (after T1–T4 merge, and after the owner has enabled Pages)

**T7 docs-site**
- Owns: `mkdocs.yml`, `docs/index.md`, `docs/changelog.md`, `docs-site/{requirements.txt,hooks/**,overrides/**,page-meta.yml}`, `.github/workflows/pages.yml`, `.gitignore`, `CONTRIBUTING.md`.
- Site configuration:
  - `mkdocs.yml` at the repo root, `docs_dir: docs`, `site_url: https://aindriub.github.io/data-prism/`.
  - Pinned mkdocs 1.6.x, mkdocs-material 9.x, mkdocs-include-markdown-plugin and mkdocs-llmstxt.
  - `theme.font: false` (no Google Fonts) and no analytics.
  - Strict link validation (`links.*: warn` combined with `--strict`).
- Content:
  - `docs/index.md` pulls in the README between the site-intro markers.
  - `docs/changelog.md` pulls in `CHANGELOG.md`.
  - `exclude_docs`: `plan/`, `adr/`, `pack.md`, `conventions.md`, `workflow.md`, `development-plan.md`, `design-review.md`.
  - The nav covers Get started, Use cases, Connect an agent, Reference, FAQ, Comparison and Changelog.
- Hook (`docs-site/hooks/site.py`):
  - Takes existing docs' titles and descriptions from `page-meta.yml`, and raises an error on any missing or duplicate description (at most 155 chars).
  - Rewrites `../examples/…` links to GitHub `blob`/`tree` URLs.
- Overrides: OG and Twitter tags using `docs/assets/social-card.png`, plus JSON-LD `SoftwareSourceCode` on the home page (no version field).
- llms: generate `llms.txt` (summary = D) and `llms-full.txt` in CI. If the plugin can't do it, fall back to the hook.
- `pages.yml`, in `build.yml` style:
  - Path-filtered, and runs on PRs and on `main`.
  - Build steps: `mkdocs build --strict`, then `lychee --offline site/`, then a guard that no excluded path appears in the site or sitemap and that both llms files exist.
  - Deploy on `main` only, with `pages: write` + `id-token: write`, the `github-pages` environment, and `concurrency: pages`.
  - Not a required check.
- A "Docs site" section in `CONTRIBUTING.md` with the local build commands.
- Acceptance:
  - `mkdocs build --strict` exits 0.
  - Every sitemap URL is a nav page, and none contains an excluded path.
  - Every page has a unique non-empty meta description, a canonical URL under `site_url`, and `og:image`.
  - Deleting one `page-meta.yml` entry fails the build (non-vacuity check).
  - `llms.txt` opens with `# Data Prism` and its summary is D.
  - `grep -rE 'fonts.googleapis|googletagmanager|google-analytics' site/` is empty.
  - Lychee and actionlint pass.
  - No existing `docs/*.md` file is modified.
  - The publish-mcp name check still passes, and the PR's `pages` build job is green.

### Wave 3 (after T7 is merged and deployed)

**T8 go-live-wiring**
- Owns: `README.md`, `server.json`.
- README docs table:
  - Split into user docs and internal docs.
  - Link to the site.
  - Add the missing rows for `audit.md`, `configuration.md` and `protect-your-own-api.md`.
- `server.json` gets `websiteUrl`.
- Acceptance:
  - The live site, `sitemap.xml` and `llms.txt` each return 200.
  - `mcp-publisher validate` passes.
  - The name check passes.

## Owner actions (outward-facing; run only on explicit confirmation)

- **Enable Pages**, before wave 2 merges: `gh api -X POST repos/AindriuB/data-prism/pages -f build_type=workflow`. After the first deploy, enforce HTTPS.
- **After go-live:**
  - `gh repo edit AindriuB/data-prism --description "<D>" --homepage https://aindriub.github.io/data-prism/` and add the 11 topics.
  - Upload the social preview in the web UI (Settings → General).
  - Verify Google Search Console and Bing Webmaster Tools, then submit `sitemap.xml`. Bing feeds Copilot and ChatGPT search.
- **Post each outreach draft yourself**, one at a time, and only where that list's rules are met.
- **Next release, not now:**
  - Descriptions on Central, the MCP registry and GHCR update when the next version publishes.
  - GHCR's multi-arch description needs index annotations in `publish-image.yml`. That is its own reviewed task.

## Risks

- **Overclaiming:**
  - Most exposed: the comparison page, audit wording, and the words "anonymisation" and "compliant".
  - Every new page's acceptance includes: each hit of `anonymi|compliant|tamper-proof|guarantee` is a negation or refers to another tool, and the reviewer checks each one.
- **Drift:**
  - The intent pages and `page-meta.yml` can go stale.
  - T and D are repeated in six or more places.
  - Add "re-check faq/comparison/use-cases; grep T and D across surfaces" to the release checklist.
- **Tooling:**
  - MkDocs and Material may be in maintenance mode. Pin versions.
  - Zensical reads `mkdocs.yml`, which gives a later migration path.
- **Measurement:**
  - Assistant answers vary between runs, so record the model and mode every time.
  - A missed 14-day snapshot loses that period's traffic permanently.
- **Reputation:**
  - "Data Prism" is a common name, so always pair it with "MCP privacy layer".
  - Submitting to lists before the project is eligible costs goodwill.
