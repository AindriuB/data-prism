# 51 — Write the tool user guide

**Executor:** `scribe` (this is documentation; per `CLAUDE.md` rule 4 only
`scribe` writes docs). It will need to **drive a running server** to capture
its own examples — that is required, not optional, see Acceptance.
**Repo:** `.` (`/Users/Andrew/workspace/data-prism`)
**Depends on:** none
**Owns:**
- `docs/tools.md` (new file, and the only file this task writes)

## Goal

Write the reference for the two MCP tools this platform ships.
`compare_entity_sources` has zero consumer documentation today — it shipped
in 0.2.0 and is described nowhere outside javadoc, tests and
`docs/pack.md` §42. `get_entity_context` is mentioned across four documents
but its response shape is written down in none of them. Write `docs/tools.md`
so a caller can go from "the tool appeared in `tools/list`" to a successful,
understood call without reading source.

Two things in this guide are load-bearing beyond reference material, and the
guide is a failure without them:

**The pseudonym collapse.** Two sources holding *different* values render as
the *same* pseudonym, because a pseudonym is keyed on the subject, not the
value. The consistency finding is the only thing in the response that reveals
they disagreed. A reader who misses this concludes the tool is broken and
that the platform is hiding a data-quality problem — when surfacing that
problem is the point of the product. State it early and plainly.

**Grant before call.** Adding a tool to the server does not grant it. A role
must hold the capability in `dataprism.security-policy.roles` or the call is
refused with `TOOL_NOT_PERMITTED` — a code named in no document today. This
was observed for real: an external consumer upgrading 0.1.1 to 0.2.0 saw the
new tool appear in `tools/list` and be refused on call until the policy was
widened. Say so as the concrete consequence, not as a caveat.

## Context

- `data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/CompareEntitySourcesTool.java`
  and `.../GetEntityContextTool.java` — the two tools: argument schemas,
  response assembly, refusal paths.
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/ConsistencyFinding.java:8-26`
  — the javadoc already states the pseudonym-collapse point better than a
  paraphrase will. `:46-96` — the `Kind` enum. **Correcting the brief this
  task was planned from:** there are six constants, not three —
  `INCONSISTENT`, `FORMATTING_ONLY`, `ABBREVIATION`,
  `MISSING_IN_SOME_SOURCES`, `SUSPECTED_INSTRUCTION_CONTENT`, `CONSISTENT`.
  `:97-102` — `disagreement()` excludes the last two. Derive the list from
  the file, not from this paragraph.
- `ConsistencyFinding`'s components: `field`, `namespace`, `kind`,
  `agreementGroups` (sources grouped by the value they agreed on, largest
  group first, **never the value itself**), `distinctValues`, `detail`.
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/Capability.java:15-20`
  — `GET_ENTITY_CONTEXT`, `COMPARE_ENTITY_SOURCES`, `EXPOSE_SOURCE_NAMES`,
  `DESCRIBE_ENTITY_MODEL`, and the `SUPPORTED` set.
  `EXPOSE_SOURCE_NAMES` is what decides whether a finding's source names are
  real or per-scope aliases — without it, the source identifiers in findings
  are themselves pseudonymised, which the guide must say.
- `data-prism-security/src/main/java/.../AuthorizationService.java:38` —
  where `TOOL_NOT_PERMITTED` is produced. `docs/configuration.md:72,107` —
  the `dataprism.security-policy` binding and a worked `roles:` block.
- `data-prism-orchestration/src/main/java/.../NamespaceCorrelationService.java`
  — agreement is computed **pre-scrub**, on the trusted side, precisely
  because scrubbing collapses distinct values. This is the mechanism behind
  the collapse point above.
- `docs/agents/stdio.md` and `docs/quickstart.md` — two already-tested ways to
  get a live server to call. Use one of them to capture output.
- Worked-example tests that already exist and can be read for shape, but are
  not a substitute for a real capture:
  `io.github.aindriub.dataprism.example.CompareEntitySourcesWorkedExampleTest`
  and `.WorkedExampleTest`.

Line numbers were derived on 2026-09-17 against `e5d1c22`. Re-derive with
`rg -n` before citing.

## Acceptance

- [ ] `docs/tools.md` exists and documents both `get_entity_context` and
      `compare_entity_sources`: every argument with its type and whether it is
      required, the response shape field by field, and one worked
      request/response pair each.
- [ ] Every request and response in the file is a **real capture from a
      running server**, with the exact command that produced it shown
      alongside. None is hand-written or adapted from a test fixture. The
      precedent is the scribe who corrected `docs/agents/stdio.md:102` by
      re-driving the stdio fixture server and capturing real output rather
      than hand-editing; this repository has a recorded history of docs
      drifting from reality and this is the standard that stops it.
- [ ] The guide lists **every** constant of `ConsistencyFinding.Kind`, derived
      from the source file. Checkable: `rg -o '[A-Z_]{4,}' docs/tools.md` is a
      superset of the enum's constants.
- [ ] `agreementGroups` and `distinctValues` are each explained, including
      that `agreementGroups` says which sources agreed with each other and
      never what any of them held.
- [ ] The pseudonym-collapse point appears before the first worked response,
      and is **demonstrated**: the captured `compare_entity_sources` response
      shows a field where two sources rendered the same pseudonym and an
      `INCONSISTENT` (or `FORMATTING_ONLY`/`ABBREVIATION`) finding names it.
      If the fixture set cannot produce such a case, say so explicitly rather
      than illustrating with an invented one.
- [ ] The guide states that source identifiers inside findings are themselves
      pseudonymised per scope unless the caller holds `EXPOSE_SOURCE_NAMES`,
      and shows that in the capture.
- [ ] The grant-before-call section names `TOOL_NOT_PERMITTED`, shows a
      worked `dataprism.security-policy.roles` fragment granting
      `COMPARE_ENTITY_SOURCES`, and **reproduces the refusal**: call the tool
      with a role that lacks the capability against a live server and paste
      what came back. It states the 0.1.1-to-0.2.0 observation — a new tool
      appears in `tools/list` and is refused on call until the policy is
      widened — as the reason this section exists.
- [ ] Scope isolation is shown as observed behaviour, not asserted: the same
      subject called under two different case ids yields two different
      pseudonyms, captured from two real calls, with both outputs shown.
- [ ] The guide contains no table or list of `dataprism.*` properties. The
      `security-policy` fragment is a worked illustration and links to
      `docs/configuration.md` for the reference; it does not restate the
      binding rules, the refusal table or the defaults.

## Out of scope

- `README.md`, `docs/architecture.md`, `docs/quickstart.md`,
  `docs/agents/**`, `docs/configuration.md` — task 52 owns all of them, and
  52 is what links `README.md` to this new file. Do not add the link
  yourself; that is the collision this split exists to prevent.
- The four stale "only one MCP tool" claims scattered through those files.
  Task 52. Do not fix them here even though you will read them.
- `search_entity_data` and `describe_entity_model`. Neither is built; this is
  a guide to shipped behaviour. One sentence saying they do not exist is
  enough, if you want it at all.
- Building or changing an adapter, the annotation model, or `-Dloader.path`
  packaging. Task 50 owns `docs/extending.md`.
- The module rename in flight as task 49. **Do not write the string
  `data-prism-example` or `data-prism-integration-tests` into this file.**
  Task 49 is renaming that module in a sibling worktree, so either name would
  be a claim you cannot check. Cite classes by fully-qualified name instead —
  the Java package is explicitly unchanged by 49.
- Any change to tool behaviour. If the capture disagrees with what a tool
  should do, that is a defect report in your close-out, not an edit.
