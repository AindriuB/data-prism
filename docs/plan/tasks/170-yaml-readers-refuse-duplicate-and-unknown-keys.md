# 170 — Make the five YAML readers refuse duplicate keys, unknown keys, trailing documents and YAML 1.2 look-alikes at startup

**Repo:** .
**Base:** branch from `origin/main` after 166 and 167 have merged into it. Owner decisions D-170-1 (b), D-170-2 (a) and D-167-1 (b) are recorded below.
**Depends on:** 166, 167
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/StrictYaml.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/model/StrictYamlTest.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/ModelDescriptors.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/descriptor/ModelDescriptorsStrictKeysTest.java (new)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/PrivacyProfiles.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/policy/PrivacyProfilesTest.java (insertions, plus assertions on changed messages)
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/SecurityPolicy.java
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/SecurityPolicyTest.java (insertions, plus assertions on changed messages)
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestSources.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestSourcesStrictKeysTest.java (new)
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java (the `rejectUnknownKeys` message and the read/duplicate-key path only)
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesTest.java (insertions, plus assertions on changed messages)
- data-prism-pseudonymisation/src/main/java/io/github/aindriub/dataprism/pseudonymisation/vocabulary/VocabularyRegistry.java
- data-prism-pseudonymisation/src/test/java/io/github/aindriub/dataprism/pseudonymisation/vocabulary/VocabularyRegistryTest.java (insertions, plus assertions on changed messages)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/characterisation/{SecurityPolicy,PrivacyProfiles,ModelDescriptors,RestSources,Vocabulary}YamlCharacterisationTest.java (only these five; no other file in that directory)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/ShippedYamlLoadsStrictlyTest.java (new)
- .gitattributes (new)
- docs/configuration.md (one new subsection inserted at the end of "Configuration rules", ~:45-59. Edit nothing else in the file.)
- Shipped YAML, edited only to remove an unknown or duplicate key, and each edit listed in the hand-back: data-prism-core/src/main/resources/privacy-profiles-default.yaml, data-prism-pseudonymisation/src/main/resources/vocabulary/*.yaml, examples/json-sources/*.yaml, data-prism-connectors-rest/src/test/resources/*.yaml, data-prism-server/src/test/resources/configured-json/*.yaml
- Inline YAML in existing tests under data-prism-{core,security,connectors-rest,pseudonymisation,spring-boot-autoconfigure,server}/src/test/**, edited only where it contains an unknown or duplicate key, and each edit listed in the hand-back. This does not include `CorrelationConfigurationTest.java` (168) or any file under data-prism-mcp or data-prism-architecture.
- YAML code blocks in docs/**/*.md, edited only where they contain an unknown or duplicate key, and each edit listed in the hand-back. This excludes docs/plan/**, docs/pack.md, docs/design-review.md, docs/architecture.md, docs/conventions.md and docs/migration-0.6.md.

## Goal
Owner decision D-166-1 (a), 2026-10-08: configuration must fail closed. The 166 characterisation
tests show that all five YAML readers (`SecurityPolicy`, `PrivacyProfiles`, `ModelDescriptors`,
`RestSources`, `VocabularyRegistry`) silently keep the last of two duplicate keys. Four of them
also silently ignore unknown keys, both top-level and nested. A mistyped `undeclaredFields` or
`override`, or a duplicated `action`, therefore changes the privacy result without any error.
After this task, each reader refuses both cases at startup with a stable code. The task also adds
a `.gitattributes` entry so that line-ending conversion cannot rewrite the 166 golden files.

Added 2026-10-08 (D-167-1 (b)): 167 moved the readers to YAML 1.2, so `yes`/`no`/`on`/`off` are now
plain text and a leading-zero number such as `010` is decimal, which is a silent change of meaning
from 0.5.x. This task therefore also refuses, at startup, any boolean-typed field whose value is
anything other than exactly `true` or `false`, and any numeric-typed field written with a leading
zero (for example `010`), each with a stable code that follows this task's conventions
(`UPPER_SNAKE_CODE: ` prefix, key and path, never the value). Likewise, each reader refuses a
multi-document file or trailing content after the first document (enable `FAIL_ON_TRAILING_TOKENS`
or an equivalent check) with a stable code, so a second document cannot be silently dropped.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/StrictYaml.java. This is the shared helper for hand-written YAML parsing, and every reader's module already depends on data-prism-core. Put the shared key check here (for example `requireOnlyKeys(Map<?,?> body, Set<String> allowed, String where)`) rather than copying it into five places.
- data-prism-connectors-rest/.../ConfiguredJsonSources.java:464-470. `rejectUnknownKeys` is the existing shape for this check. Follow it, and give it the new code prefix.
- data-prism-connectors-rest/.../ConfiguredJsonSources.java:73-75. `ConfiguredJsonSources` reuses `RestSources.YAML`. If duplicate detection is turned on in that mapper, `ConfiguredJsonSources` (a sixth reader) also starts refusing duplicate keys. That is intended: test it and document it. If 167 gave it its own mapper, turn duplicate detection on there too.
- data-prism-security/.../SecurityPolicy.java:62-68. The one reader that already refuses an unknown top-level key, with "unknown security policy key 'extra'".
- data-prism-core/.../PrivacyProfiles.java:90, AuditedEntityTypes.java:71, AuditRecorder.java:142. The refusal-code convention for an `IllegalArgumentException` or `IllegalStateException` thrown at startup: the message starts with `UPPER_SNAKE_CODE: `, then a plain description. The code must also satisfy `core.refusal.RefusalCodes` (`[A-Z][A-Z0-9_]{0,63}`).
- data-prism-spring-boot-autoconfigure/.../DataPrismAutoConfiguration.java:473-489. The Spring path wraps any `IllegalArgumentException` from `ModelDescriptors` as `INVALID_MODEL_DESCRIPTOR_FILE` and on purpose does not repeat the file's content. Leave the wrapper alone. The new refusals must reach it as `IllegalArgumentException`.
- docs/conventions.md, "Errors". Every failure a caller can act on has a stable code, and no failure is swallowed.
- docs/configuration.md:194. "The message never repeats the entry." Configuration refusals do not echo values.
- The 166 tests (see 166's branch `task/166-jackson2-characterisation-tests`; take their names and paths as 167 left them). The `duplicateKey`, `unknownTopLevelKey` and `unknownNestedKey` tests pin the lax behaviour, and the `booleanSpellings*` and `octalLookingScalars` tests pin YAML scalar coercion.
- 167's hand-back, "Behaviour differences for owner acceptance". It shows how Jackson 3 parses YAML (YAML 1.2, snakeyaml-engine), how a duplicate key appears there, and whether parser exceptions are now unchecked (`JacksonException`). Under Jackson 3 a parser failure is not an `IOException`, so a `catch (IOException)` that becomes "could not be read" will not see it.

Allowed keys. For each fixed-schema mapping, the allowed set is exactly the set of keys the reader reads today. Do not remove any key that the reader reads. Mappings whose keys are names the user chooses are checked for duplicates only: model names, field names, profile names, source names, role names and the classification keys of a profile's rule map. `pools` keys are `PoolKind` names. An unknown pool name is an unknown key.

## Acceptance
- [ ] Two new codes. `DUPLICATE_CONFIG_KEY` covers a mapping, at any depth, that has the same key twice. `UNKNOWN_CONFIG_KEY` covers a fixed-schema mapping, at any depth, that has a key outside its allowed set. Each is thrown at load time as an `IllegalArgumentException` whose message starts with `<CODE>: `. The message names the document kind, the path to the mapping and the key (D-170-2 (a): the key and its path, the key truncated to 64 characters), and never a value.
- [ ] Neither code arrives wrapped as `UncheckedIOException` "could not be read". A test per reader asserts the exception type and the message prefix.
- [ ] `SecurityPolicy`'s existing unknown-top-level refusal uses the new form: `UNKNOWN_CONFIG_KEY: security policy has an unknown key 'extra'`, with the key truncated to 64 characters. `ConfiguredJsonSources.rejectUnknownKeys` also carries the `UNKNOWN_CONFIG_KEY: ` prefix. The text after each prefix may otherwise stay as it is.
- [ ] Tests per reader and per case. Each test is one behaviour, with a sentence-style name, and lives in the reader's own module test file listed in Owns. A cell marked (none) has no fixed-schema nested mapping, so no test is needed; the hand-back must confirm that.

  | Reader | duplicate top-level | duplicate nested | unknown top-level | unknown nested |
  |---|---|---|---|---|
  | `SecurityPolicy` | yes | yes (in `roles`) | yes (code changes) | (none) |
  | `PrivacyProfiles` | yes | yes (in a profile, and in a rule) | yes | yes (in a profile, and in a rule) |
  | `ModelDescriptors` | yes | yes (in a model, and in a field) | yes | yes (in a model, and in a field) |
  | `RestSources` | yes | yes (in a source, and in `tls`) | yes | yes (in a source, and in `tls`) |
  | `VocabularyRegistry` | yes | yes (in `pools`) | yes | yes (in `pools`) |
  | `ConfiguredJsonSources` | yes | yes (in a source) | already refused (prefix changes) | already refused (prefix changes) |
- [ ] A user-chosen name used once in each of two different mappings, such as the same field name in two models, still loads. One test covers this in `ModelDescriptors`.
- [ ] The 166 characterisation tests that pinned the lax behaviour now pin refusal: each asserts `refused IllegalArgumentException "<CODE>: ..."` in that file's existing `outcome(...)` style, and its `@DisplayName` says so. There are 14 tests:
  - `SecurityPolicyYamlCharacterisationTest`: `duplicateKey`, and `unknownTopLevelKey` (message text only)
  - `PrivacyProfilesYamlCharacterisationTest`: `duplicateKey`, `unknownTopLevelKey`, `unknownNestedKey`
  - `ModelDescriptorsYamlCharacterisationTest`: `duplicateKey`, `unknownTopLevelKey`, `unknownNestedKey`
  - `RestSourcesYamlCharacterisationTest`: `duplicateKey`, `unknownTopLevelKey`, `unknownNestedKey`
  - `VocabularyYamlCharacterisationTest`: `duplicateKey`, `unknownTopLevelKey`, `unknownNestedKey`

  `SecurityPolicyYamlCharacterisationTest.unknownNestedKey` (a role given a mapping) and every enum-spelling test stay byte-for-byte unchanged. So do the boolean and octal tests, except the string-field ones that D-170-1 (b) changes (see below).
- [ ] `git diff origin/main -- data-prism-integration-tests/src/test/resources/characterisation/` is empty. No golden file changes.
- [ ] `ShippedYamlLoadsStrictlyTest` loads every shipped YAML that no other test loads and asserts that it parses. That covers `privacy-profiles-default.yaml` through `PrivacyProfiles.fromYaml`, all seven bundled vocabularies through `VocabularyRegistry.withBuiltIns()`, and `examples/json-sources/customer-api.yaml` and `customer-api-nested.yaml` through `ConfiguredJsonSources.fromYaml`, read from the reactor root by a path relative to the module. Every other shipped YAML (`data-prism-connectors-rest/src/test/resources/task20-*.yaml`, `data-prism-server/src/test/resources/configured-json/reidentification-source.yaml`) is already loaded by an existing test. The hand-back names that test for each file.
- [ ] Every YAML code block in docs/**/*.md that is input for one of the six readers is checked for unknown and duplicate keys. The hand-back lists each block it checked (file:line) and each one it fixed. The same goes for every shipped YAML file and every inline test YAML it fixed. If nothing needed fixing, the hand-back says so.
- [ ] `.gitattributes` exists at the repository root and contains the line `data-prism-integration-tests/src/test/resources/characterisation/** -text`. `git check-attr text -- data-prism-integration-tests/src/test/resources/characterisation/audit-ascii.json` prints `text: unset`.
- [ ] docs/configuration.md gets a new subsection under "Configuration rules", "Strict keys in YAML configuration files". It names the six readers and the property or entry point that feeds each one, and states both codes, what triggers each, and that the message names the key but never a value. It states that the Spring path reports a descriptor-file refusal as `INVALID_MODEL_DESCRIPTOR_FILE` with the inner code in the cause, and that a configuration which loaded on 0.5.x may now refuse to start. It also states the quoting rule and `NON_STRING_CONFIG_SCALAR`.
- [ ] `mvn -q verify` passes on the full reactor.
- [ ] `git diff --stat origin/main` shows changes only under the Owns paths.

**D-170-1 (b), decided:**
- [ ] Every string-typed field in the six readers refuses a YAML scalar that the parser resolved to a non-string type (boolean, integer, float or null) with `NON_STRING_CONFIG_SCALAR: <where> must be a quoted string`. The message never contains the value. That covers a security purpose, the `RestSources` `tls` paths and `base-url`/`path`, a field's `subject`, `identifier` and `nonSensitive`, a band `unit`, a vocabulary `id`/`locale`/`script` and its pool entries, and any other field the reader reads with `String.valueOf`. The hand-back lists the full set.
- [ ] Numeric and boolean fields (`exposed`, `override`, band bounds, `version`, ...) are unchanged.
- [ ] Every 166 `booleanSpellings*` and `octalLookingScalars` test is updated where its subject is a string-typed field. Those are `SecurityPolicy` (purpose), `RestSources` (key-store), `ModelDescriptors` (`octalLookingScalars`, `booleanSpellingsAsText`), `PrivacyProfiles` (`octalLookingScalars`, for `unit` only) and `Vocabulary` (pool entry). After the update each one pins refusal for an unquoted coerced scalar and acceptance for the quoted form. Tests whose subject is a boolean or numeric field stay as 167 left them.

**D-167-1 (b), decided 2026-10-08, and the trailing-content scope addition:**
- [ ] A boolean-typed field (`exposed`, `override`, and every other field the readers read as a boolean) refuses any value other than exactly `true` or `false` (so `yes`, `no`, `on`, `off`, `True`, `1`) with a new stable code, proposed `INVALID_CONFIG_BOOLEAN`. The message names the key and its path, never the value.
- [ ] A numeric-typed field (band bounds, `version`, and every other field the readers read as a number) refuses a value written with a leading zero (`010`, `0777`, `00`) with a new stable code, proposed `LEADING_ZERO_CONFIG_NUMBER`. A plain `0` and a decimal such as `0.5` are still accepted. The message never contains the value. The implementer may choose different code names if the existing conventions require it, and lists the final names in the hand-back.
- [ ] Each of the five readers (and `ConfiguredJsonSources`) refuses a multi-document file (`---` followed by a second document) and any trailing content after the first document, with a stable code, proposed `TRAILING_CONFIG_CONTENT`. 167 pinned `FAIL_ON_TRAILING_TOKENS` off; this task turns it on or adds an equivalent check. One test per reader asserts the exception type and the message prefix, and that an empty trailing document marker is handled as the implementer documents.
- [ ] The 166 `booleanSpellings*` and `octalLookingScalars` tests whose subject is a boolean or numeric field flip from "accepted as 167 left it" to refusal with the new codes: `booleanSpellings*` where the field is boolean-typed (the `override` and `exposed` cases), and `octalLookingScalars` where the field is numeric (band bounds, vocabulary `version`). Tests on string-typed fields stay as D-170-1 (b) set them. Each flipped test keeps the quoted-form or `true`/`false` case as an accepted control.
- [ ] docs/configuration.md's "Strict keys" subsection also names the three added codes and states that `yes`/`no`/`on`/`off` and leading-zero numbers are refused.

## Out of scope
- `DataPrismAutoConfiguration`'s wrapping of reader exceptions, and any other Spring Boot `dataprism.*` property binding (158 and 159 own those files). Unknown Spring properties are not this task.
- Changing which YAML version or library the readers use, or any other Jackson setting beyond duplicate detection (167 settled the port).
- `ArchitectureTest`, `DataPrismObjectMapper` and the tool classes (168). docs/architecture.md and docs/conventions.md (169 and 162).
- CHANGELOG.md and docs/migration-0.6.md. 162 records these refusals from this hand-back.
- Any other characterisation test or golden file, and the audit, checkpoint and tool-result tests in particular.
- Renaming, adding or removing an accepted configuration key.

## Decisions (owner, all decided 2026-10-08)
- **D-170-1: DECIDED (b). YAML-coerced scalars in string-typed fields.** Under Jackson 2 (YAML 1.1), `010` becomes `"8"`, `0777` becomes `"511"`, and `yes`/`on` become `"true"` in string fields across all readers, including a TLS key-store path and a security purpose. YAML 1.2 under Jackson 3 probably stops the `yes`/`on` and leading-zero-octal cases, but `010` still resolves to an integer (shown as `"10"`), and `1e3`, `0x1F` and `~` still coerce. 167's difference table will show exactly what remains.
  (a) Leave it out of 170. Raise a follow-up after 167 reports.
  (b) In 170: a string-typed field refuses any non-string scalar, so a config author must quote `"010"`. This needs the acceptance items below.
  (c) In 170: document "quote every string" only, and refuse nothing.
  **Recommendation: (b).** It is the same fail-closed reasoning as D-166-1. It touches the same files, so it costs no extra coordination. It is also the only option that stops a silently changed key-store path or purpose whatever 167 observes. If 167's table shows that no coercion survives into string fields, (b) reduces to a guard and still costs little.
- **D-170-2: DECIDED (a). May a refusal message name the offending key?** `SecurityPolicy` and `ConfiguredJsonSources` already do. docs/configuration.md:194 forbids repeating an entry's value.
  (a) Name the key and its path, never the value, and truncate the key to 64 characters.
  (b) Name only the path to the parent mapping and the line and column.
  **Recommendation: (a).** A key name is schema rather than data, and an operator needs it to fix a typo. Truncation limits the damage if someone pastes a secret where a key should be.

Recorded 2026-10-08: D-166-1 = (a), this task exists (0.6.0, after 167). D-170-1 = (b): a string-typed field refuses any non-string scalar with `NON_STRING_CONFIG_SCALAR`, so values must be quoted. D-170-2 = (a): refusal messages name the offending key and its path, never the value, with the key truncated to 64 characters. `ConfiguredJsonSources` is covered because it shares the `RestSources` mapper. Task 158 and 162 now depend on this task.

Recorded 2026-10-08: D-167-1 = (b). The owner had no opinion; chosen on correctness. 167 accepts YAML 1.2 parsing (`yes`/`no`/`on`/`off` are text, leading-zero numbers are decimal), and this task refuses the look-alikes at startup rather than letting them change meaning. Scope addition, same date, fail-closed (the owner was told and did not object): the readers also refuse multi-document files and trailing content.
