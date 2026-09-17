# 42 — Add the `compare_entity_sources` MCP tool

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-mcp/**
- data-prism-orchestration/**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/ConsistencyFinding.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/ConsistencyFindingTest.java
  *(new file if the change needs one. The two core paths are owned only for the
  agreement-finding requirement below, and only for additive change — see the
  constraints stated there.)*

## Goal

Ship the second MCP tool, `compare_entity_sources` (docs/pack.md §42): the same
correlated read as `get_entity_context`, projected down to the question "field
by field, do the sources agree about this entity, and where do they not?". It
adds no new way to reach source data — its only data dependency is
`ContextOrchestrator`, whose `ContextResponse` is already scrubbed, validated,
aliased and audited.

Two real gaps sit behind it. Audit attribution:
`DefaultContextOrchestrator` hard-codes the audited tool name, so without a
change a compare call would be recorded as a `get_entity_context` call. And
agreement: correlation reports only disagreement today, which leaves a caller
unable to tell "compared and consistent" from "never compared" — see the settled
contract below.

## Context

- `docs/pack.md:1436-1471` — §42, the authoritative spec. Arguments
  `entityType`, `idInternal`; response `entityType`, `idInternal`, `identity`,
  `findings[{field, consistent, sources}]`.
- `data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java`
  — the shape to follow exactly: caller from `exchange.transportContext()` under
  `TRANSPORT_CONTEXT_CALLER_KEY`, `null` caller refused as
  `NO_AUTHENTICATED_CALLER`, `AuthorizationService.authorize` then
  `ScopeResolver.resolve`, `Metric.MCP_REQUESTS` only after both succeed,
  `ReservedArguments.rejected` over the argument names, and three catch clauses
  that never echo a downstream message.
- `data-prism-mcp/.../DataPrismMcpServer.java:89-90` (stdio) and `:130-131`
  (streamable HTTP) — the two registration sites. Note stdio passes a
  development caller and HTTP deliberately does not; the new tool must keep that
  asymmetry.
- `data-prism-core/.../Capability.java:16` — `COMPARE_ENTITY_SOURCES` already
  exists and is already in `KNOWN`. No core change is needed for it.
- `data-prism-core/.../ConsistencyFinding.java` — `field`, `namespace`, `kind`,
  `agreementGroups`, `distinctValues`, `detail`, and the `disagreement()`
  predicate (true for every kind except `SUSPECTED_INSTRUCTION_CONTENT`). No
  finding ever carries a value; keep it that way.
- `data-prism-orchestration/.../NamespaceCorrelationService.java:82,102,129-141`
  — every place a finding kind is produced today. Agreement is not among them;
  this is where it has to come from.
- `data-prism-orchestration/.../DefaultContextOrchestrator.java:203-211` — the
  ALLOW/DENY audit, and `:282-296` where the tool name is the literal
  `"get_entity_context"`.
- `data-prism-orchestration/.../DefaultContextOrchestrator.java:246-248` —
  source names are aliased *before* correlation sees them, so
  `ConsistencyFinding.agreementGroups` in a `ContextResponse` already honours
  `EXPOSE_SOURCE_NAMES` (docs/design-review.md:308). Do not alias again, and do
  not bypass this by reading raw names.
- `data-prism-mcp/src/test/.../DataPrismMcpServerTest.java:100-101` —
  `server.listTools()` is asserted to contain exactly one name today. That
  assertion is yours to update.
- `data-prism-architecture/.../ArchitectureTest.java:164-173` —
  `mcpDoesNotReachSources`. It must stay green unchanged; you do not own it.

## How to read §42: shape authoritative, example values superseded

**Do not implement `docs/pack.md:1436-1471`'s example response literally.** It is
a pre-privacy-engine sketch, written before the engine it now sits behind, and
as written it breaches CLAUDE.md rule 5 three times over:

- `"idInternal": "idInternal_98745"` — a raw internal identifier.
- `"identity": {"name": "Alex Murphy"}` — a raw personal name.
- `"sources": ["customer-api", "account-api"]` — raw source-system names, which
  S6 made per-scope pseudonymised. The shipped tool already emits pseudonymised
  source identifiers inside `agreementGroups`
  (`DefaultContextOrchestrator.java:246-248`, docs/design-review.md:308).

The **shape** of §42 is authoritative: the tool takes an entity type and a
subject, and returns an entity type, the subject, an identity block and a list
of per-field findings. The **values** in its example are not.

## Settled by the owner. Contract, not a judgement call.

1. **The argument is `subjectId`, not the spec's `idInternal`.** An MCP client
   sees both tools side by side in `tools/list`; two names for one concept is a
   usability defect. The spec predates the shipped tool, which already uses
   `subjectId` across its whole surface. Same reasoning for the response: emit
   `ContextResponse.subject()`, the scope-local pseudonym, under a name that
   matches what `get_entity_context` already returns. Nothing derived from the
   raw argument value ever appears in the response.

2. **`identity` carries pseudonymised values, in the same shape
   `get_entity_context` already emits — never raw.** Concretely: for each field
   a finding is about, copy that field's node verbatim out of
   `ContextResponse.entity()`, which is the post-scrub tree. Preserve nesting
   and naming exactly as that tree has it, so a caller reading both tools sees
   one representation of the entity and not two. No value may be re-derived,
   re-formatted or read from anywhere else. If a finding's field is absent from
   the scrubbed tree — redacted, unclassified, dropped — it is omitted from
   `identity`; never substitute a placeholder and never fall back to a source
   value.

3. **Findings report agreement as well as disagreement.** If only disagreements
   appear, a caller cannot tell "compared across sources and consistent" from
   "never compared", and an ambiguity of exactly that kind is what this tool
   exists to remove. Three states must be distinguishable in the output without
   inference:
   - the sources that hold this field agreed;
   - the sources that hold this field disagreed, with the existing `kind`
     saying how (`INCONSISTENT`, `FORMATTING_ONLY`, `ABBREVIATION`);
   - some sources hold this field and others do not
     (`MISSING_IN_SOME_SOURCES`), which is neither of the above.

   Distinguishable by an explicit discriminator carried in the finding, not by
   absence from a list. An agreement finding discloses nothing new: it names a
   field and the sources that agreed, and like every other finding it carries no
   value.

4. **`consistent` does not replace the taxonomy.** Every finding keeps `kind`,
   `agreementGroups` and `distinctValues` alongside the spec's boolean.
   Collapsing to the boolean alone would lose the `MISSING_IN_SOME_SOURCES`
   distinction the shipped tool already makes and would report
   `SUSPECTED_INSTRUCTION_CONTENT` as a consistency verdict — a regression, not
   a simplification. Derive `consistent` from the existing
   `ConsistencyFinding.disagreement()` predicate rather than writing a second
   one; if adding an agreement state changes what that predicate should return,
   change it there, once, rather than branching at the call site.

### The constraint on getting item 3

Agreement is only knowable on the trusted side — which fields more than one
source held, and whether their raw values matched — so it cannot be reconstructed
in the MCP layer from a `ContextResponse`. It therefore comes from
`NamespaceCorrelationService` (`data-prism-orchestration`, owned) and, if a new
`ConsistencyFinding.Kind` is the cleanest expression of it, from
`ConsistencyFinding` (`data-prism-core`, owned for this and nothing else).

Whatever the placement:

- The change to core must be **additive**. No existing constant renamed, no
  existing component removed, no signature broken.
- **`get_entity_context`'s response must not change.** Agreement findings are
  produced for the comparison path only. If they were emitted into the existing
  tool's output, that is a behaviour change to a shipped tool and it is out of
  scope here. A test must pin this.
- No file outside this task's `Owns` list may need editing. If it does, stop and
  report rather than widening.

Confirmed against the "Not doing" list in `docs/plan/PLAN.md`: §42 requires no
scope selection, no scope extension and no re-identification. If an
implementation seems to need any of the three, stop and report instead.

### For `scribe` at `/record`, not for the implementer

`docs/design-review.md` is the file that amends `docs/pack.md` where the two
disagree, and it does not yet record that §42's example response is superseded
by the privacy engine. That amendment should be written there. Do not write it
in this task.

## Acceptance

- [ ] `CompareEntitySourcesTool.NAME` is `"compare_entity_sources"` and its
      `ToolInvocation` requires `Capability.COMPARE_ENTITY_SOURCES`.
- [ ] The input schema declares `required: ["entityType", "subjectId"]` and
      exactly two properties. A test asserts the built schema has no third
      property key — in particular none of `scopeId`, `purpose`, `caseId`,
      `sourceName`, `host`, `profile` or `classification`.
- [ ] `server.listTools()` returns exactly
      `["get_entity_context", "compare_entity_sources"]` for both
      `DataPrismMcpServer.stdio(...)` and `DataPrismMcpServer.streamableHttp(...)`,
      asserted in `DataPrismMcpServerTest`.
- [ ] A caller holding `GET_ENTITY_CONTEXT` but **not**
      `COMPARE_ENTITY_SOURCES` is denied, `Metric.MCP_DENIED` is incremented,
      an audit event is written with the denial code, and the recording
      orchestrator stub records zero calls.
- [ ] **Non-vacuity, recorded in the PR description:** with the required
      capability in `ToolInvocation` temporarily changed to one the caller does
      hold, the denial test above fails. Paste the failure line.
- [ ] A call whose transport context carries no `AuthenticatedCaller` is refused
      with `NO_AUTHENTICATED_CALLER` before the orchestrator is touched, and the
      HTTP registration site passes no development caller (visible in the diff).
- [ ] Reserved argument names supplied by the caller are never read for their
      value: a test calls twice, once with `{"scopeId": "<other scope>"}` added
      and once without, and asserts the two responses are byte-identical and
      that the name `scopeId` reaches the audit event's rejected-arguments set.
- [ ] **Non-vacuity, recorded in the PR description:** that test fails if the
      `ReservedArguments.rejected(...)` call is removed from the handler.
- [ ] Every value in the response is either a constant, the entity type, the
      pseudonym `ContextResponse.subject()`, or a value copied unmodified out of
      the `ContextResponse` the orchestrator returned. Checkable by reading the
      diff: the tool holds no reference to a `DataSourceAdapter`,
      `ScrubbingEngine`, `SyntheticValueSource`, `SourceAliasing` or any
      connector type, and constructs no `ObjectMapper` of its own.
- [ ] `identity` is assembled only from nodes copied verbatim out of
      `ContextResponse.entity()`, and a field a finding names but the scrubbed
      tree does not contain is omitted rather than defaulted. A test asserts a
      redacted field produces no `identity` entry and no placeholder.
- [ ] The response distinguishes all three states without inference — sources
      agreed, sources disagreed (with `kind`), and `MISSING_IN_SOME_SOURCES` —
      by an explicit discriminator on the finding. A test drives one subject
      exhibiting all three and asserts each is separately identifiable, and that
      a field no source pair compared appears in none of them.
- [ ] No finding of any state carries a value: a test asserts the serialised
      comparison response for a disagreeing fixture contains none of the raw
      values that produced the finding.
- [ ] Every finding in the response carries `kind`, `agreementGroups` and
      `distinctValues` in addition to `consistent`, and `consistent` is derived
      from `ConsistencyFinding.disagreement()` — one predicate, not two
      (checkable in the diff).
- [ ] `get_entity_context`'s own output is unchanged: a test pins the findings
      `NamespaceCorrelationService` produces for the existing path, and no
      agreement finding appears in a `ContextResponse` built by
      `buildContext(...)` for a caller of the existing tool.
- [ ] Any change to `ConsistencyFinding` is additive — no constant renamed, no
      component removed, no signature broken — verifiable by reading the diff of
      that one file.
- [ ] Fail-closed paths return `isError` with a code and no downstream message:
      `PrivacyRefusedException` returns `refused: <code> at <path>`, any other
      `RuntimeException` returns a fixed string. A test asserts the error text
      of a thrown orchestrator exception carrying a fixture value does not
      contain that value.
- [ ] An accepted `compare_entity_sources` call is audited with
      `AuditEvent.tool() == "compare_entity_sources"`, asserted against a real
      `AuditRecorder` and a capturing sink at the orchestration level.
- [ ] The orchestration change is **additive and source-compatible**: no file
      outside this task's `Owns` list needs editing. Proved by
      `mvn -B clean verify` green over the whole reactor with
      no edits elsewhere, and by `ContextRequest.of(entityType, subjectId)`
      still compiling at its existing call sites in `data-prism-example` and
      `data-prism-connectors-rest` tests.
- [ ] `data-prism-example`'s `EndToEndTest:136`
      (`assertThat(event.tool()).isEqualTo("get_entity_context")`) is still green
      without being edited.
- [ ] `mvn -B clean verify` green over the full reactor, with the new test count
      stated against the 466 baseline.

## Out of scope

- Granting `COMPARE_ENTITY_SOURCES` to any shipped or example role, and any edit
  under `data-prism-example/**` — that is task 43, including the end-to-end
  proof that a real, unscrubbed fixture value never appears in this tool's
  response.
- `search_entity_data` (docs/pack.md:1475-1499) and `describe_entity_model`.
- Any change to `data-prism-core` beyond `ConsistencyFinding` and its own test,
  and any change to `data-prism-security`, `data-prism-architecture` or
  `data-prism-server`. `COMPARE_ENTITY_SOURCES` already exists in `Capability`;
  a deployment grants it in its own configuration, so the distributable needs no
  change to expose the tool.
- Changing what `get_entity_context` returns. Agreement findings are for the
  comparison path; folding them into the shipped tool's output is a separate
  decision nobody has taken.
- README, `docs/`, `CHANGELOG.md` and anything else stating that one tool
  exists. Those are prose, and only `scribe` edits them.
- Version numbers. The 0.2.0 cut is task 45.
