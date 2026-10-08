# 172 — Allow StrictYaml on the factory rule; prune stale mapper allowlist

**Repo:** .
**Base:** `release/0.6.0-jackson3`. Unplanned integration fix, found testing merged wave 6.

## Goal
After 168 and 170 merged, `ArchitectureTest.onlyDesignatedClassesConstructJsonFactories` failed (c70599ac) because 170's `core.model.StrictYaml` builds a `YAMLFactory`. Each task passed alone; the two rules only met on the merged branch.

## Outcome
- `StrictYaml` added to the JSON/YAML factory allowlist.
- `designatedYamlReadersDoNotWrite` narrowed to `{RestSources, StrictYaml}` and extended to forbid `createGenerator*` and `JsonGenerator` construction. New negative fixture `YamlReaderWritesFixture` proves the rule catches a parse-only class that starts writing.
- Four stale mapper-allowlist entries removed: `SecurityPolicy`, `PrivacyProfiles`, `ModelDescriptors` and `VocabularyRegistry` parse through `StrictYaml` and no longer build mappers.
- `docs/architecture.md` updated to match.
- Verified: implementer full reactor verify and release-profile package green; coordinator reviewed the diff.
