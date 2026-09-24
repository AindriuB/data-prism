# 88 — Draw five concept diagrams and embed each in the doc that covers it

**Repo:** `.`
**Wave:** 1 (spec section D, diagrams 1–5)
**Depends on:** 85
**Base branch:** the LOCAL `site-polish` branch, after 85 has merged into it.
`wt-new.sh` bases new worktrees on `main`, so right after it, before any
edit, run `git -C <worktree> reset --hard site-polish`. The branch merges
back into `site-polish`. This task file is uncommitted: read it from
`/srv/dev/projects/data-prism/docs/plan/tasks/88-diagrams-in-context.md`.
**Owns:**
- docs-site/diagrams/** *(new: `*.mmd`, `render.sh`, `README.md`)*
- docs/assets/diagrams/** *(new: rendered `*.svg`)*
- docs-site/hooks/check_diagrams.py *(new)*
- docs/architecture.md *(one diagram embed only: diagram 1)*
- docs/tools.md *(two diagram embeds only: diagrams 2 and 3)*
- docs/configuration.md *(one diagram embed only: diagram 4)*
- docs/audit.md *(one diagram embed only: diagram 5)*

No other wave-1 task owns any of these files:
- 86 owns `docs/index.md`, and its cards only *link* to `tools.md`, `configuration.md` and `audit.md`.
- 89 owns `docs/extending.md` and the developer-guide pages.
- 87 owns only changelog files.

## Goal

Readers get a diagram where it explains something, inside the existing page
that covers that concept. This task draws five diagrams: the system overview,
one tool call, how a pseudonym is made, fail-closed field decisions, and the
audit chain. Each embed is one introductory sentence, the image and its alt
text. Mermaid source is committed, and a pinned Docker render produces the
committed SVGs, so the site still loads no third-party script. There is no
separate diagrams or "learn" page. Diagram 6, the extension points, is drawn
and placed later by task 90.

## Context

- **Binding sources:**
  - `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md` at `site-polish` commit 5ded855 or later: the Diagrams, Diagram rendering and Honesty rows, and section D, items 1–5 plus the no-move/no-reword rule.
  - `docs/plan/specs/2026-09-23-discoverability.md` line 238: the honesty greps.
- **Owner decision, 2026-09-24:** no landing, "learn" or gallery page, and nothing named after or modelled on another project's site. Diagrams live only inside the existing docs listed under **Owns**.
- **Where each diagram goes.** Each insertion point is at a section end, chosen so that no line number cited in `docs/plan/PLAN.md` shifts:
  1. **System overview** goes in `docs/architecture.md`, at the end of "## How they talk" (lines 86–135, before "## Boundaries that must not be crossed" at 136). PLAN.md:649 cites `docs/architecture.md:104-113`, which this does not shift. Retired task files cite 106-155, 114-118 and 135-141; list those for the scribe if they shift.
  2. **One `get_entity_context` call** (a sequence diagram) goes in `docs/tools.md`, at the end of "## `get_entity_context`" (96–194, before "## `compare_entity_sources`" at 195). PLAN.md:1322 cites `docs/tools.md:120`, which this does not shift.
  3. **How a pseudonym is made** goes in `docs/tools.md`, at the end of "## Scope isolation" (410–438, before "## Not yet built" at 439). That section already explains that a different case gives a different pseudonym.
  4. **Fail-closed field decisions** goes in `docs/configuration.md`, not `tools.md`. Unclassified handling is described in the `dataprism.privacy` row at line 73 (corrected by task 84), and `tools.md` only mentions it in passing at line 120. Put it at the end of "## `dataprism.*` vocabulary" (60–193, before "## Java-first now…" at 194). PLAN.md cites `docs/configuration.md:63` and `:73` (lines 1171, 1337 and 1688), which this does not shift.
  5. **The audit chain** goes in `docs/audit.md`, at the end of "## What this does and does not prove" (226–285, end of file). No out-of-history line citations into `audit.md` exist.

  `grep -rnoE '(architecture|tools|audit|configuration)\.md:[0-9]+' docs README.md CONTRIBUTING.md` reproduces the citation list. Re-run it on your branch.
- **Other constraints on these docs:**
  - `DataPrismConfigurationFailureAnalyzer` and several classes cite `docs/configuration.md` and friends by **path**. No Java cites them by anchor or line. Paths and headings must not change.
  - These four docs have no front matter. Their titles and descriptions come from `docs-site/page-meta.yml`, which stays untouched.
- **Trace sources.** Every node and edge must trace to one of:
  - `docs/architecture.md` ("## Components", "## How they talk", "## Boundaries that must not be crossed")
  - `docs/tools.md`
  - `docs/audit.md`
  - code. Starting points:
    - scope: `data-prism-security/.../security/ScopeResolver.java` (`case:`)
    - pseudonyms: `data-prism-pseudonymisation/.../HmacSyntheticGenerator.java`, `HmacValueTokenSource.java`
    - profiles: `data-prism-core/src/main/resources/privacy-profiles-default.yaml` (both profiles `unclassified: FAIL_REQUEST`)
    - audit: `data-prism-core/.../audit/AuditRecorder.java`, `AuditChainVerifier.java`
  - `CLAUDE.md` rule 1: no path from an adapter to the MCP layer bypasses the privacy engine.
- **Diagram 4:** show only what configuration can reach today. Task 84 established this: no `dataprism.*` property loads a custom profile, and only bundled `DEFAULT`/`STRICT` load. Relaxed settings (for example `PASS_THROUGH_UNSAFE`, or an unclassified action other than `FAIL_REQUEST`) must not appear as reachable branches.
- **Diagram 5:** use `docs/audit.md`'s wording strictly:
  - one chain per writer boot, `<writer-id>/<uuid>`, starting at `GENESIS`;
  - the verifier detects an edit anywhere in a chain, including the last record, and a deletion that has later records after it ("including the last record" applies to edits only; see `docs/audit.md` "What this does and does not prove");
  - it cannot detect tail truncation, deletion of a whole boot's records, or recomputation by someone with write access.

  No "tamper-proof", no "immutable". The file's closing paragraph says it is neither.
- **Diagram 3:** input is (scope, subject, namespace, algorithm version) plus the HMAC key, then digest, then synthetic identity plus discriminator. Scope is `case:` + case id. It is pseudonymisation, never anonymisation.
- **Rendering:**
  - No Material runtime Mermaid (no superfences `custom_fences`). 85's guard fails on `class="mermaid"`, and it allows only the two known `unpkg.com` strings in Material's bundle.
  - `render.sh` runs `minlag/mermaid-cli` (or equivalent), pinned by version **and** `@sha256:` digest, under `docker run --network none -u "$(id -u):$(id -g)"`.
  - It writes `docs/assets/diagrams/<name>.svg` from `docs-site/diagrams/<name>.mmd`.
  - Mermaid's default HTML labels use `<foreignObject>`, which some `<img>` renderers mishandle. Consider `htmlLabels: false`, and state your choice.
  - Task 90 will add diagram 6 using this same script. Keep `render.sh` generic, rendering every `.mmd` file in the directory.
- **Guards and build:**
  - `check_diagrams.py` is picked up by 85's `check_*.py` loop, with no `pages.yml` edit.
  - Build recipe: see task 85's Context.

## Acceptance

- [ ] Five `.mmd` files exist in `docs-site/diagrams/`, one per diagram 1–5. Each has a matching committed `.svg` in `docs/assets/diagrams/`, with the same stem. There is no extension-points diagram (that is task 90).
- [ ] `render.sh`:
  - pins the image by digest;
  - runs with `--network none`;
  - regenerates every SVG from every `.mmd` file.
  - After a fresh run, `git status --porcelain docs/assets/diagrams` is empty. If mermaid-cli output is not byte-stable, `render.sh` normalises it, and the report shows two consecutive runs with no diff.
- [ ] `grep -hoE 'https?://[^"'"'"' )]+' docs/assets/diagrams/*.svg | sort -u` shows only `http://www.w3.org/…` namespace URIs, and no SVG contains `<script` or `@import`.
- [ ] `docs-site/diagrams/README.md` documents how to render, and the pin. It also holds a trace table: for every node and every edge of every diagram, the `file:line` (code, or `docs/architecture.md` / `docs/tools.md` / `docs/audit.md`) that supports it. No row may be unsupported, and the reviewer re-traces every row.
- [ ] **Diagram 1** shows the spec's request path and response path. It has no edge from any adapter or source to the MCP layer or response that bypasses classification/scrubbing and the leak check. The report names the `docs/architecture.md` boundary line that backs this.
- [ ] **Diagram 4** has exactly three outcomes:
  - classified → pseudonymise/redact/remove;
  - unclassified → whole response refused (`FAIL_REQUEST`);
  - detected identifier shape → refused.

  `grep -niE 'PASS_THROUGH|relax|allow unclassified' docs-site/diagrams/*.mmd` is empty.
- [ ] **Diagram 5** and its introduction use only `docs/audit.md` phrasing for what is and is not detected.
- [ ] **Each embed is exactly:** one introductory sentence, the image with alt text that states what the diagram shows (not just its title), and nothing else. For each of the four docs:
  - `git diff site-polish -U0 -- <doc>` shows only added lines, in one contiguous hunk per diagram, at the insertion point above (or a stated, justified alternative section end).
  - No existing line is removed or changed: `git diff site-polish --numstat -- docs/architecture.md docs/tools.md docs/configuration.md docs/audit.md` shows 0 deletions for every file.
  - No heading changes, so all existing anchors survive.
- [ ] **Line shifts.** The report lists every citation of the form `<doc>.md:<line>` (from the grep in Context) whose target line shifted, with old and new numbers, for the scribe. Or it states that none shifted in `PLAN.md` and live docs, and lists the retired or historical ones that did.
- [ ] `docs-site/hooks/check_diagrams.py` fails, naming the file, unless all of these hold:
  - every `.mmd` has a matching `.svg`, and vice versa;
  - every SVG in `docs/assets/diagrams/` is referenced by at least one built page;
  - every diagram `<img>` in built HTML has non-empty `alt`;
  - no SVG contains a non-w3.org URL.

  Non-vacuity, with each failure quoted in the report. In scratch copies:
  1. delete one `.svg`;
  2. add an orphan `.mmd`;
  3. empty one alt text;
  4. insert `https://cdn.jsdelivr.net/x` into an SVG;
  5. remove one embed so its SVG is unreferenced.
- [ ] Honesty: `grep -rniE 'anonymi|compliant|tamper-proof|tamper proof|guarantee|immutable' docs-site/diagrams/` is empty. Every *added* line in the four docs (`git diff site-polish -- … | grep '^+'`) either has no hit or is a negation.
- [ ] `test ! -e docs/learn.md`, and no new page or nav entry is added.
- [ ] Full recipe green:
  - `mkdocs build --strict`
  - every `docs-site/hooks/check_*.py`
  - lychee `--offline` as in `pages.yml`
  - actionlint
  - `grep -rE 'fonts.googleapis|googletagmanager|google-analytics|gtag|unpkg|jsdelivr|cdnjs' site/ --exclude='bundle.*.min.js*'` empty
  - `grep -rl 'class="mermaid"' site/` empty
- [ ] `git diff --stat site-polish` lists only files under **Owns**.

## Out of scope

- Any "learn", diagrams-gallery or landing page, and any nav change. `mkdocs.yml`, `check_site.py` and `pages.yml` are also out.
- Diagram 6 (extension points) and the developer-guide overview (task 90, after 89).
- Rewording, reordering or correcting any existing sentence in `architecture.md`, `tools.md`, `configuration.md` or `audit.md`. If a doc is wrong, report it; do not fix it here.
- `docs-site/page-meta.yml`, and the landing page's links (86 owns `docs/index.md`).
- Diagrams for classifying models in depth or custom audit sinks (later plan).

## Attempt 1 — failed

Tester: FAIL, on diagram legibility. The tester's Owns "violation" (`docs/plan/tasks/87-changelog-collapse.md`) is a false positive: `site-polish` advanced with plan-only commits, so always diff with three dots, `git diff site-polish...HEAD`. Reviewer: CHANGES. Rebase onto LOCAL `site-polish`, which carries the corrected audit wording in this task's Context, and fix these:

1. **Diagram 5 overclaims** (audit-chain.mmd about line 8, and the audit.md alt text). It says an edit *or deletion* is detected "including the last record" or "anywhere in the chain". `docs/audit.md` "What this does and does not prove" attaches "including the last record" to edits only. A deletion is caught only when later records follow it, and deleting a writer's most recent records goes undetected. As drawn, the diagram contradicts its own "Blind" node. Use audit.md's own words in the node and in the alt text.
2. **Diagrams 1 and 5 are unreadable at page width.** Both are wide `flowchart LR` layouts: system-overview's viewBox is about 2415×134, and Material's roughly 688px content column shrinks it to about 688×39px. See `/tmp/t88-shots/architecture-system-overview-*.png` and `audit-audit-chain-*.png`. Redraw both top-to-bottom (`flowchart TB`), or group them into subgraphs, so node labels render at no less than about 12px effective size in a 688px column without zooming. Keep every node and edge traced, and update the README trace table if labels change.
3. **Dark mode.** The SVGs carry a hard white background, so each shows as a white band inside the dark page. That is acceptable, but make it deliberate: give the diagrams a small uniform padding or margin in the SVG, e.g. via mermaid config or render.sh options, so they read as a framed panel rather than a strip. Don't touch extra.css; it belongs to task 86. If you can reach a clean result another way inside Owns, say how.
4. **The line-shift list is incomplete.** Retired task 12 (lines 23 and 64) cites `architecture.md:106-155`, which now ends at 159. Retired task 13 (lines 30 and 88) cites `135-141`, which straddles the insertion; old 136–141 is now 140–145. HISTORY.md:1775 cites `architecture.md:156`. List all of these in your report for the scribe. Don't edit those files.
5. **Suggestions, which you should do:**
   - entity-context-call.mmd: show where the caller comes from, with a Tool→Tool "caller from transport context" step (GetEntityContextTool.java about lines 155 and 206). Align the README audit-message row with the .mmd label.
   - README trace-table preamble: drop "none of these source lines moved", since architecture.md and tools.md did move. Keep the cited numbers correct for the branch.

**Out of scope, not yours:** `docs/tools.md:120` says "unclassified values dropped", which contradicts FAIL_REQUEST. The scribe will handle it separately. Do not change that line.

**Acceptance for this attempt:**
- Re-render with render.sh twice and confirm the output is byte-stable.
- Take all 10 screenshots again (5 diagrams, light and dark) at 1280px with Playwright (`mcr.microsoft.com/playwright:v1.49.0-noble`, run with `--user $(id -u):$(id -g)`) into `/tmp/t88b-shots/`. Look at each one and report the effective label size.
- Re-run the keep-green list: the strict build, every `check_*.py`, check_diagrams' planted faults, the third-party grep, lychee, actionlint, 0 deletions in the four docs, and the Owns scope checked with three dots.
