# 274 — Add a restricted JSONPath parser and coverage checker for configured JSON sources

**Repo:** `.`
**Release:** 0.9.0
**Base branch:** `origin/main` once every 0.8 brief has merged
**Depends on:** 249 (the last 0.8 editor of the configured-JSON grammar; read its final `ConfiguredJsonSources` before designing the API). No 0.9 dependency: this task adds a new package and touches no existing file.
**Wave:** 15 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Owns:**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/jsonpath/** *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/jsonpath/** *(new)*

## Goal
Configured JSON sources will key their fields by restricted JSONPath instead of flat or one-level names (275 wires it in). This task builds the part that does not depend on the existing catalogue code: parse a path, check that a set of paths is unambiguous, and walk a JSON tree to say which single path covers each value and which values no path covers. It is a pure library with no Spring and no refusal codes, so it can be built and mutation-tested alongside 273.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 2, "Headline: YAML": the grammar is binding.
  - Allowed: `$`, child steps `.name` and `['odd key']`, and `[*]` at any depth.
  - Refused: recursive descent (`..`), filters, unions, indexes and slices; overlapping or ambiguous paths; a path that crosses a field already classified as a leaf.
  - Every value must be covered by exactly one path; anything uncovered is reported (275 refuses it with `UNKNOWN_FIELD`, as today).
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java:40-60 (`FIELD_NAME` and the strict key grammar it replaces) and :195-235 (how `fields:` and nested catalogues are validated today). Read, do not edit.
- Decided at filing (low stakes), for the API shape 275 needs:
  - `JsonPath.parse(String)` returns a parsed path or a failure carrying a reason from a closed set (one reason per refused construct: recursive descent, filter, union, index, slice, bad quoting, missing `$`, empty step, and so on). It never throws an unchecked exception for user input.
  - `[*]` matches the elements of an array only, never the members of an object. An object's members are reached only by name. With that rule, two paths can match the same value only if they are equal after normalisation, so "ambiguous" and "equal after normalisation" are the same check.
  - A path set refuses two paths that are equal after normalisation (`$.a` and `$['a']`; `$.items[*].id` and `$.items[*]['id']`), and a path that is a strict prefix of another (the "crosses a leaf" rule: `$.a` declared as a leaf with `$.a.b` or `$.a[*]` also declared).
  - The walker takes a Jackson 3 (`tools.jackson`) `JsonNode` and a path set. For each scalar or null value it reports the one covering path, or "uncovered" with its location. A location is a path string built from keys and `[*]` only, never from values. It also reports a shape mismatch: an object where a declared leaf expects a scalar, or a scalar where a declared path needs to descend. 275 decides the policy for each.
  - The reasons are an enum. Its `name()` must not reach a log, response or exception message in this task. 275 maps reasons to refusal codes and owns `freeze/enums.txt` for that.
- Key text: a quoted step may contain any character except an unescaped `'`. Define escaping (`\'` and `\\` only) and test it. Keys are compared exactly (no case folding, no Unicode normalisation), as the current grammar does.
- Follow the package and naming rules `ArchitectureTest` enforces; add a `package-info.java` if sibling packages have one.
- docs/conventions.md "Tests": table-driven tests, no sleeps. This package is in the mutation profile's scope if `connectors.rest` is.
- Fixture values are invented (`example.invalid`, invented names). Repository rule 3.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## Acceptance
- [ ] Tests are written first. The Outcome names them.
- [ ] Parser, table-driven: each allowed form parses to the expected steps (`$`, `$.a`, `$['a b']`, `$.a[*].b`, `$[*]`, `$.a[*][*]`, `$['it\'s']`). Each refused form gives its own reason: `$..a`, `$.a[?(@.b)]`, `$['a','b']`, `$.a[0]`, `$.a[0:2]`, `$.a[-1]`, `a.b` (no `$`), `$.`, `$.a.`, `$['a`, `$.a b`, `$.length()`.
- [ ] Path set: equal-after-normalisation pairs and strict-prefix (leaf crossing) pairs are each refused, naming the two paths. `[*]` applied to an object member is reported as uncovered by the walker, not matched. `$.a.b` with `$.a.c`, and `$.items[*].id` with `$.items[*].name`, are accepted.
- [ ] Walker: over a fixture tree with nested objects, arrays of objects, arrays of arrays and nulls, every scalar and null is reported exactly once, with its covering path or as uncovered. Shape mismatches are reported, not thrown. A property-based or exhaustive test over generated small trees shows that no value is reported twice or skipped.
- [ ] No reported location or failure message contains a value from the tree. A test uses sentinel values and asserts that none of them appears in any output string.
- [ ] Mutation: PIT over `connectors.rest.jsonpath` kills every mutant, or the Outcome lists each survivor with the reason it is equivalent.
- [ ] No file outside `jsonpath/**` changes.
- [ ] `./mvnw -B -pl data-prism-connectors-rest -am verify` passes.

## Out of scope
- Wiring the library into `ConfiguredJsonSources`, the scrubbing engine, `subject-json-path` or chainable fields, the refusal codes, and the migration of existing catalogues (275).
- Any JSONPath feature beyond the spec's list, including functions, negative indexes and filters.
- A nested-YAML alternative syntax. The owner rejected one.
