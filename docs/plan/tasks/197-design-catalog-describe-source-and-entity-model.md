# 197 — Design: `list_sources`, `describe_source` and `describe_entity_model`

**Repo:** .
**Branch:** from `main` after task 191 has merged
**Depends on:** 191 (input keys, namespaces, `chainable` and key resolvers are defined there)
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/catalog.md (new)
- docs/plan/tasks/197-design-catalog-describe-source-and-entity-model.md (Outcome section only)

(Location decided by D-070-12 (a): `docs/plan/design/`, internal.)

## Goal
The agent-led design starts at a catalog: `list_sources` is the root resource,
`describe_source` returns a source's output shape and per-field protection
without data, and `describe_entity_model` (0.9) describes entity types and the
relations an agent can follow. 0.8 builds the first two and the adapter
description they read. This task fixes their contracts and the adapter
description SPI so 0.8 implements, and 0.9 freezes, a reviewed design.

## Context
- `docs/plan/roadmap.md` "Design" and 0.8/0.9 feature lists.
- `docs/plan/PLAN.md` "Road to 1.0": D-RM-8 (separate alias description,
  checked at startup, generated fallback), D-RM-9 (refuse with a clear code
  when no source is chosen and the estate is too large), D-RM-10 (real names
  only when scope setting and caller permission both allow; audit always real
  names plus the effective setting), D-RM-11 (role-to-source patterns,
  unmatched denied), D-RM-13 (`compare_entity_sources` kept).
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/DataSourceAdapter.java:12-16`
  — today only `sourceName()`, `responseType()`, `fetch()`.
- `docs/plan/design/sealed-links.md` and `key-resolvers.md` (task 191).
- `SourceAliasing` (`data-prism-orchestration/.../SourceAliasing.java`) and
  the alias rules; `docs/tools.md` "Scope isolation".
- Model descriptors and `@LlmExposedModel` / `@SensitiveData` / `@NonSensitive`
  (`docs/extending.md`), which supply per-field protection.

## Required content
1. Adapter description SPI: entity types, alias description, real description,
   input keys with namespaces, output model reference, cost hint, freshness
   hint; source compatibility for existing adapters (default methods or a
   separate descriptor), and the YAML equivalent for configured JSON sources.
2. Startup checks on description text (D-RM-8): what is checked, what refuses,
   with codes; the generated fallback.
3. `list_sources`: input and output JSON Schema, paging (cite task 198), how
   aliases versus real names are chosen (D-RM-10), how per-source authorisation
   filters the list (D-RM-11: hidden or listed as not permitted, as an open
   decision), and the audit record.
4. `describe_source`: input and output JSON Schema, per-field protection
   vocabulary, `chainable` markers, what is never returned.
5. `describe_entity_model`: purpose, input and output JSON Schema, how relations
   are derived from adapter and resolver declarations, and how it avoids
   leaking estate shape beyond what `list_sources` already shows.
6. Targeting chosen sources on `get_entity_context` and the D-RM-9 refusal:
   argument name, schema change, refusal code, the configuration key for the
   estate-size limit.
7. "Boundaries" (1, 7, 8: no business domain in core), "Test obligations for
   0.8 and 0.9", "Open decisions for the owner" with options and a recommendation each.

## Acceptance
- [ ] The file exists with every numbered item as a heading; every schema is
      valid JSON Schema (draft 2020-12) checked with a validator, command in the Outcome.
- [ ] Every proposed tool, argument, refusal code, audit field and configuration
      key is in a closing table and collides with nothing existing or in task
      191's tables.
- [ ] Examples use an invented neutral domain; no real system, organisation or
      data model is named.
- [ ] **Review gate:** `reviewer` returns APPROVE after checking against D-RM-8,
      9, 10, 11, 13 and boundaries 1, 7 and 8, and confirms no product decision
      is taken in the text. Verdict recorded in the Outcome.

## Out of scope
- Code, SPI stubs or properties.
- Batch, paging and bulk-follow schemas (task 198); link format (task 191).

## Outcome

Written: `docs/plan/design/catalog.md` (sections 1 to 6 are the six numbered items; 7 Boundaries, 8 Test obligations, 9 Open decisions OD-C1 to OD-C18, 10 closing names table). Nothing in it is decided; every product choice is an open decision with options and a recommendation.

Schema validation (draft 2020-12), run with the `jsonschema` package installed in a throwaway virtual environment outside the repository:

```
python3 -m venv <scratch>/venv && <scratch>/venv/bin/pip install jsonschema
<scratch>/venv/bin/python -I check_schemas.py docs/plan/design/catalog.md
```

`check_schemas.py` extracts every fenced JSON block that has a `$schema`, runs `Draft202012Validator.check_schema` on it, then validates each block preceded by `<!-- example-of: <title> -->` against the schema of that title. Result: 7 schemas valid (`list_sources` input and output, `describe_source` input and output, `describe_entity_model` input and output, `get_entity_context` input) and 3 examples valid.

Collision check: a script took every backticked name in the first column of the closing table and ran `grep -rIl -w <name>` over `docs/` (which includes `tools.md`, `audit.md`, `configuration.md`, the three design documents, tasks 190, 196, 198, 200, 201) and the main sources of core, orchestration, mcp, annotations, connectors-rest and server. All hits were reviewed. Real collisions: none. Close names, recorded in the table: `DESCRIBE_ENTITY_MODEL` (exists as a capability, not a tool), `UndeclaredFields.DROP`, `entityTypes()` and `dataprism.audit.entity-types` (audited types), `dataprism.limits.max-sources`. Words that merely appear in prose (`description`, `fields`, `source`, `path`, `labels`) are generic. Tasks 198 and 200 propose no names yet that overlap; reconcile in task 200 (OD-C13, OD-C16 are cross-task points).

Forbidden-word scan (a script holding the words reversed so they are not spelled here) over the new file: no hits.

Review gate: not run by the implementing agent. `reviewer` must return APPROVE against D-RM-8, 9, 10, 11, 13 and boundaries 1, 7, 8, and confirm no product decision is taken. Verdict to be recorded here by the caller.

Points the reviewer should look at first: OD-C2 (D-RM-10 asks the audit to record the effective setting; shape C has no place for it, so this is a conflict with shape C and not a choice inside it), OD-C3 (where the per-scope real-names setting lives), and OD-C16 (list_sources size at hundreds of sources against paging that task 198 places in 0.9).
