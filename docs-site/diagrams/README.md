# Diagrams

Five diagrams (task 88; task 90 adds a sixth, the extension points diagram, to
the developer guide, using this same convention). Mermaid source lives here as
`*.mmd`; each is rendered to a committed SVG under `docs/assets/diagrams/`,
with the same stem, and embedded in the one existing doc that covers what it
shows — never in a separate "learn" or gallery page.

## Rendering

```sh
docs-site/diagrams/render.sh
```

`render.sh` runs every `docs-site/diagrams/*.mmd` through `mermaid-cli`
(`minlag/mermaid-cli`), in Docker, offline and as the invoking user:

```sh
docker run --rm --network none -u "$(id -u):$(id -g)" \
    -v "$PWD/docs-site/diagrams:/diagrams:ro" \
    -v "$PWD/docs/assets/diagrams:/out" \
    minlag/mermaid-cli:10.9.1@sha256:f0e8d29ef5385d797724d78c2a1bb00c8398476e8370f0219c0da86cce07d44c \
    -i "/diagrams/<name>.mmd" -o "/out/<name>.svg" -c "/diagrams/mermaid-config.json"
```

**Pin.** `minlag/mermaid-cli:10.9.1`, pinned by tag *and*
`@sha256:f0e8d29ef5385d797724d78c2a1bb00c8398476e8370f0219c0da86cce07d44c` — the
digest `docker pull minlag/mermaid-cli:10.9.1` resolved to when this was
written. `--network none` means the pin is also the only thing standing
between a render and a supply-chain surprise: bump the tag and the digest
together, deliberately, never just the tag.

**`htmlLabels: false`.** `docs-site/diagrams/mermaid-config.json` sets
`flowchart.htmlLabels` to `false`. Mermaid's default renders a flowchart
node's label inside a `<foreignObject>` (an embedded XHTML `<div>`), which
some `<img>` renderers — including browsers, in some contexts — do not paint,
since an `<img>` reference rasterises or displays the SVG without running its
embedded HTML. `false` renders every label as plain SVG `<text>`/`<tspan>`
instead, which every `<img>` renderer handles. Verified against this
worktree's own output: the default renderer emits one `<foreignObject>` per
node; with this setting, zero. Sequence diagrams (`entity-context-call.mmd`)
never use `<foreignObject>` for their labels either way, so the setting is a
no-op for that one but is kept in the shared config for whatever task 90's
diagram turns out to be.

**Layout choices.** Diagrams 1 and 5 (`system-overview.mmd`,
`audit-chain.mmd`) are `flowchart TB`, not `LR`: a single wide row of nodes
shrinks to an unreadable strip once a `<img>` scales it down to the docs
site's ~688px content column. Stacked top-to-bottom, and with a few labels
given an explicit `\n` line break (mermaid renders `\n` as a real line break
in plain SVG text even with `htmlLabels: false`), both now render at native
size at that width instead of scaled down. `entity-context-call.mmd`'s nine
participants and call-and-return messages need real horizontal room; its
participants use short ids (`Tool`, `Authz`, …, matched to their class in the
trace table below) and `docs-site/diagrams/mermaid-config.json` tightens
`sequence.actorMargin`/`messageMargin`/`boxMargin`/`noteMargin`, but it still
renders wider than the other four — see the task report for its measured
effective label size.

**Dark-mode framing.** mermaid-cli's SVGs carry a hard white background with
no margin, which reads as an abrupt white strip on a dark page. `render.sh`
pads every rendered SVG's `viewBox` by a small uniform margin
(`PADDING_PX`) and draws a thin rounded border inset from the new edge, so
each diagram reads as a deliberate framed panel instead — pure
post-processing on the existing viewBox numbers (mermaid-cli itself has no
padding option), applied after every render, to every diagram.

**Reproducibility.** Two consecutive runs of `render.sh` in this worktree
produced byte-identical SVGs (`diff -r` empty) — mermaid-cli's flowchart and
sequence renderers are deterministic here, with no per-run randomness (no
`init` seed, no wall-clock content), and the padding/framing post-processing
is pure arithmetic on the rendered viewBox, so `render.sh` does not need to
normalise anything further. `git status --porcelain docs/assets/diagrams` is
empty after a fresh run.

**Verified clean.**

```sh
grep -hoE 'https?://[^"'"'"' )]+' docs/assets/diagrams/*.svg | sort -u
# http://www.w3.org/1999/xlink
# http://www.w3.org/2000/svg
grep -l '<script' docs/assets/diagrams/*.svg   # none
grep -l '@import' docs/assets/diagrams/*.svg   # none
```

Only the SVG namespace URIs mermaid-cli always emits; every diagram is a
static, self-contained image with no runtime, no third-party host and no
embedded script.

## Where each diagram is embedded

| Diagram | Source | Rendered | Embedded in |
|---|---|---|---|
| 1. System overview | `system-overview.mmd` | `system-overview.svg` | `docs/architecture.md`, end of "## How they talk" |
| 2. One `get_entity_context` call | `entity-context-call.mmd` | `entity-context-call.svg` | `docs/tools.md`, end of "## `get_entity_context`" |
| 3. How a pseudonym is made | `pseudonym-generation.mmd` | `pseudonym-generation.svg` | `docs/tools.md`, end of "## Scope isolation" |
| 4. Fail-closed field decisions | `fail-closed-decisions.mmd` | `fail-closed-decisions.svg` | `docs/configuration.md`, end of "## `dataprism.*` vocabulary" |
| 5. The audit chain | `audit-chain.mmd` | `audit-chain.svg` | `docs/audit.md`, end of "## What this does and does not prove" (end of file) |

## Trace table

Every node and every edge of every diagram, with the `file:line` (code, or
`docs/architecture.md` / `docs/tools.md` / `docs/audit.md`) that supports it.
Line numbers are this branch's current ones, after the four embeds below and
the later full-size links and captions added to them. Each embed was
inserted at its section's end, so nothing it cites within that same section
moved; content further down the same file did shift by the number of lines
its embed (plus, for diagrams 2, 3 and 4, its later caption) added:
`architecture.md` +4, `tools.md` +7 then +5, `configuration.md` +6, `audit.md`
+5 at end of file (so nothing after it to shift) — see the task report's
line-shift list for what that moves.

### 1. System overview — `system-overview.mmd`

| Element | Label | Supports |
|---|---|---|
| Node | `Client` — MCP client | `docs/architecture.md:15` |
| Node | `Auth` — Authenticate caller | `docs/architecture.md:15`; `data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java:155,206-213` (`callerFrom`) |
| Node | `Authz` — Tool authorisation | `docs/architecture.md:43` (`security` row: `AuthorizationService`); `GetEntityContextTool.java:160` |
| Node | `Scope` — Derive scope and purpose | `docs/architecture.md:164-169` (boundary 4: the caller never supplies its own scope/purpose); `GetEntityContextTool.java:167` (`scopeResolver.resolve`) |
| Node | `Orchestrator` — Orchestrator (fan-out) | `docs/architecture.md:16,44` (`orchestration` row); `GetEntityContextTool.java:178` |
| Node | `Adapters` — Reviewed source adapters | `docs/architecture.md:46,97`; `data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java:277` (`fanOut.fetchAll(adapters, ...)`) |
| Node | `APIs` — Enterprise APIs | `docs/architecture.md:16,97-99` |
| Node | `Engine` — Classification and scrubbing: pseudonymise / redact / remove | `docs/architecture.md:18`; `docs/tools.md:120`; `DefaultContextOrchestrator.java:289` (`scrubber.scrub`) |
| Node | `Leak` — Raw-value leak check | `docs/architecture.md:19,160-163` (boundary 3); `DefaultContextOrchestrator.java:198-210` (validator loop, `VALIDATION_FAILED`) |
| Node | `Audit` — Audit | `docs/architecture.md:19,39` (`core` row: audit contract); `DefaultContextOrchestrator.java:215-221` (`audit(...)`, both DENY and ALLOW paths) |
| Node | `Response` — MCP response | `docs/architecture.md:19,91`; `GetEntityContextTool.java:181-184` |
| Edge | Client → Auth | `docs/architecture.md:15` |
| Edge | Auth → Authz | `GetEntityContextTool.java:155,160` (authenticate, then authorise) |
| Edge | Authz → Scope | `GetEntityContextTool.java:160,167` (authorise, then resolve scope from the decision) |
| Edge | Scope → Orchestrator | `GetEntityContextTool.java:167,178` |
| Edge | Orchestrator → Adapters | `DefaultContextOrchestrator.java:277` |
| Edge | Adapters ↔ APIs | `docs/architecture.md:97-99` |
| Edge | Adapters → Engine | `DefaultContextOrchestrator.java:280-289` (each fetched record is scrubbed before merge) |
| Edge | Engine → Leak | `DefaultContextOrchestrator.java:198-200` (validators run against the merged, scrubbed tree) |
| Edge | Leak → Audit | `DefaultContextOrchestrator.java:203-221` (both the refusal and success paths call `audit(...)`) |
| Edge | Audit → Response | `DefaultContextOrchestrator.java:220-222`; `GetEntityContextTool.java:181-184` |

No edge from `Adapters` or `APIs` to `Response` or to any node upstream of
`Engine`/`Leak` exists in this diagram — the property the boundary line below
requires. **Boundary line backing this:** `docs/architecture.md:147-152`
("1. No source data reaches `mcp` without passing the privacy engine.").

### 2. One `get_entity_context` call — `entity-context-call.mmd`

Participant labels are kept short in the diagram itself (`Tool`, `Authz`,
`Scope`, `Orchestrator`, `Adapter`, `Scrub`, `Validator`, `Audit`) so the
sequence fits a usable width; the class each one names is given here instead.

| Element | Label | Supports |
|---|---|---|
| Participant | `Caller` — MCP client | `docs/tools.md:96-98` |
| Participant | `Tool` — `GetEntityContextTool` | `GetEntityContextTool.java:62-64` |
| Participant | `Authz` — `AuthorizationService` | `docs/architecture.md:43`; `GetEntityContextTool.java:84,108` |
| Participant | `Scope` — `ScopeResolver` | `docs/architecture.md:43`; `data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java:27` |
| Participant | `Orchestrator` — `ContextOrchestrator` | `docs/architecture.md:44`; `data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextOrchestrator.java:6,9` |
| Participant | `Adapter` — `DataSourceAdapter` | `docs/architecture.md:56-57`; `DefaultContextOrchestrator.java:69,277` |
| Participant | `Scrub` — `ScrubbingEngine` | `DefaultContextOrchestrator.java:70,289` |
| Participant | `Validator` — `LlmResponseValidator` | `docs/architecture.md:42`; `DefaultContextOrchestrator.java:72,199-200` |
| Participant | `Audit` — `AuditRecorder` | `docs/architecture.md:39`; `DefaultContextOrchestrator.java:75,215-221` |
| Message | Caller→Tool: `get_entity_context(entityType, subjectId)` | `docs/tools.md:100-105` (Arguments table); `GetEntityContextTool.java:144-149` |
| Message | Tool→Tool: `caller from transport context` | `GetEntityContextTool.java:155,206-213` (`callerFrom`: the transport-context caller, or the configured development caller when the transport carries none) |
| Message | Tool→Authz: `authorize(caller, invocation)` | `GetEntityContextTool.java:160` |
| Message | Authz-->Tool: `AuthorizationDecision` | `GetEntityContextTool.java:160-163` |
| Message | Tool→Scope: `resolve(caller, decision, clock)` | `GetEntityContextTool.java:167` |
| Message | Scope-->Tool: `PrivacySession` | `GetEntityContextTool.java:165-170` |
| Message | Tool→Orchestrator: `buildContext(request, context, …)` | `GetEntityContextTool.java:178-180` (`…` is `investigationContext`, the third argument, omitted from the label to save width) |
| Message | Orchestrator→Adapter: `fetch (fan-out)` | `DefaultContextOrchestrator.java:277` |
| Message | Adapter-->Orchestrator: `raw source record` | `DefaultContextOrchestrator.java:280-286` |
| Message | Orchestrator→Scrub: `scrub(record, context)` | `DefaultContextOrchestrator.java:289` |
| Message | Scrub-->Orchestrator: `scrubbed tree` | `DefaultContextOrchestrator.java:289-296` |
| Message | Orchestrator→Validator: `validate(merged, …, context)` | `DefaultContextOrchestrator.java:198-200` (real order is `validate(merged, prohibited, emitted, context)`; `…` stands for the omitted `prohibited, emitted` pair, kept between `merged` and `context` to preserve the real argument order) |
| Message | Validator-->Orchestrator: `ValidationResult` | `DefaultContextOrchestrator.java:200-203` |
| Message | Orchestrator→Audit: `record(decision=ALLOW)` | `DefaultContextOrchestrator.java:220-221` |
| Message | Orchestrator-->Tool: `ContextResponse` | `DefaultContextOrchestrator.java:222-223` |
| Message | Tool-->Caller: `CallToolResult` | `GetEntityContextTool.java:181-184`; `docs/tools.md:112-120` (Response table) |

### 3. How a pseudonym is made — `pseudonym-generation.mmd`

| Element | Label | Supports |
|---|---|---|
| Node | `Scope` — Scope: case: plus case id | `data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java:86-88` (`scopeId(caseId)` returns `"case:" + caseId`); `docs/tools.md:419-423` |
| Node | `Subject` — Subject id, canonicalised | `data-prism-pseudonymisation/src/main/java/io/github/aindriub/dataprism/pseudonymisation/HmacSyntheticGenerator.java:38-40,72` (`Text.canonical(subjectId)`) |
| Node | `Namespace` | `HmacSyntheticGenerator.java:56,72` |
| Node | `Version` — Algorithm version | `HmacSyntheticGenerator.java:61,72` (`version.version()`) |
| Node | `Material` — scope, subject, namespace, version | `HmacSyntheticGenerator.java:72` |
| Node | `Key` — HMAC key | `HmacSyntheticGenerator.java:74` (`keys.secret(version.keyId())`) |
| Node | `Mac` — HMAC digest | `HmacSyntheticGenerator.java:74,78-87` (`mac(...)`) |
| Node | `Identity` — Synthetic identity | `HmacSyntheticGenerator.java:89-103` (`render`, `fullName`, `pick`) |
| Node | `Discriminator` — Discriminator, 8 characters | `HmacSyntheticGenerator.java:142-154` (`discriminator(d)`) |
| Edge | Scope → Material | `HmacSyntheticGenerator.java:72` |
| Edge | Subject → Material | `HmacSyntheticGenerator.java:72` |
| Edge | Namespace → Material | `HmacSyntheticGenerator.java:72` |
| Edge | Version → Material | `HmacSyntheticGenerator.java:72` |
| Edge | Material → Mac | `HmacSyntheticGenerator.java:74` |
| Edge | Key → Mac | `HmacSyntheticGenerator.java:74` |
| Edge | Mac → Identity | `HmacSyntheticGenerator.java:75,89-103` |
| Edge | Mac → Discriminator | `HmacSyntheticGenerator.java:90,148-154` |

`docs/tools.md:419-423` (Scope isolation) is the doc-side confirmation that a
different case id — the only input in `Scope` above that a different
investigation changes — produces a different, unrelated-looking pseudonym for
the same subject. This is pseudonymisation throughout: the diagram's own
label is "Synthetic identity", never a stronger word for what leaving this
scope would take back out.

### 4. Fail-closed field decisions — `fail-closed-decisions.mmd`

| Element | Label | Supports |
|---|---|---|
| Node | `Field` — Response field | `docs/tools.md:120` |
| Node | `Classified` — Classified? | `data-prism-core/src/main/resources/privacy-profiles-default.yaml:16-18,69` (each profile is `unclassified:` plus a `classifications:` map — the two branches this decision has) |
| Node | `Action` — Pseudonymise, redact or remove per classification | `privacy-profiles-default.yaml:24-25` (SYNTHESIZE), `:27-35,59` (REDACT), `:63-64` (REMOVE); `docs/tools.md:120` |
| Node | `Unclassified` — Unclassified: whole response refused, FAIL_REQUEST | `privacy-profiles-default.yaml:18,69` (`unclassified: FAIL_REQUEST` in both shipped profiles); `docs/configuration.md:73` |
| Node | `Scan` — Response contains a detected identifier shape? | `data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitivePatternValidator.java:13-20` |
| Node | `RefusedShape` — Refused | `SensitivePatternValidator.java:13-14`; `docs/architecture.md:160-163` (boundary 3) |
| Node | `Returned` — Field returned | `DefaultContextOrchestrator.java:203-210,222` (no violations → the response is returned) |
| Edge | Field → Classified | `privacy-profiles-default.yaml:16-18,69` |
| Edge | Classified —yes→ Action | `privacy-profiles-default.yaml:24-64` |
| Edge | Action → Scan | `DefaultContextOrchestrator.java:289` then `:198-200` (scrub, then validate the scrubbed tree) |
| Edge | Classified —no→ Unclassified | `privacy-profiles-default.yaml:18,69`; `docs/configuration.md:73` |
| Edge | Scan —yes→ RefusedShape | `DefaultContextOrchestrator.java:203-210` (`VALIDATION_FAILED`) |
| Edge | Scan —no→ Returned | `DefaultContextOrchestrator.java:222` |

Task 84 established that no `dataprism.*` property loads a custom profile and
only the bundled `DEFAULT`/`STRICT` profiles load — both set
`unclassified: FAIL_REQUEST` and neither exposes `PASS_THROUGH_UNSAFE` or any
other unclassified action, so this diagram shows exactly the three outcomes
above and nothing a relaxed setting could reach:
`grep -niE 'PASS_THROUGH|relax|allow unclassified' docs-site/diagrams/*.mmd`
is empty.

### 5. The audit chain — `audit-chain.mmd`

| Element | Label | Supports |
|---|---|---|
| Node | `Boot` — Writer boot: instanceId is writer-id / uuid | `docs/audit.md:61-67`; `data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java:63` |
| Node | `Genesis` — GENESIS | `docs/audit.md:67`; `AuditRecorder.java:28,42` |
| Node | `R1`/`R2`/`R3` — record 1 / record 2 / record N | `docs/audit.md:8`; `AuditRecorder.java:84-110` (`previousHash` chaining) |
| Node | `Verifier` — Offline verifier replays the chain | `docs/audit.md:87` (`AuditChainVerifierCli` replays each writer's chain); `data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java:16` |
| Node | `Detect` — Detects: an edit anywhere in the chain, including the last record, and a deletion that has later records after it | `docs/audit.md:231-239` (edit caught anywhere including the tail; only deleting a writer's *most recent* records goes undetected, so a deletion followed by later records is caught) |
| Node | `Blind` — Cannot detect: tail truncation, deletion of a whole boot's records, or recomputation by someone with write access | `docs/audit.md:246-254` (truncation, whole-boot deletion); `docs/audit.md:261-268` (recomputation by anyone with write access) |
| Edge | Boot → Genesis | `docs/audit.md:61-67` |
| Edge | Genesis → R1 → R2 → R3 | `AuditRecorder.java:84,90-92,110` |
| Edge | R3 → Verifier | `docs/audit.md:87` |
| Edge | Verifier → Detect | `docs/audit.md:231-239` |
| Edge | Verifier → Blind | `docs/audit.md:246-268` |

This diagram uses only `docs/audit.md`'s own wording for what is and is not
detected: "including the last record" is attached only to edits, never to
deletions, matching "What this does and does not prove" exactly (attempt 1's
review found the previous wording overclaimed this — a deletion is caught
only when later records still follow it in the chain, and deleting a
writer's most recent records is the tail-truncation gap the `Blind` node
already names).
