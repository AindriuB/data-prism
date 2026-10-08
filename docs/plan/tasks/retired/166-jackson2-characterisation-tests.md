# 166 — Pin Jackson 2 output and YAML parsing behaviour with characterisation tests before the Jackson 3 port

**Repo:** .
**Base:** branch from the 0.6.0 moves branch (`release/0.6.0-moves`) once 157 has merged onto it.
Owner-side sequencing: 156 was already running when this task was planned, so 166 runs after the
moves (156, 157) and before the port (167). The tests are written against the post-move package
names.
**Depends on:** 157
**Owns:**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/characterisation/** (new)
- data-prism-integration-tests/src/test/resources/characterisation/** (new: golden files and YAML inputs)

## Goal
Owner decision J3-4 (A). Before any Jackson 3 code lands, pin today's Jackson 2 behaviour as
golden bytes and parsed values, so the port (167) must either keep every test green or list each
difference for the owner to accept. The tests add coverage and change no production code.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditJsonRenderer.java:30-60. The JSON audit projection, which uses a Jackson streaming generator with `ESCAPE_NON_ASCII`.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpoint.java. Checkpoint line format.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismObjectMapper.java:26-33. The single output mapper. Its Jackson 2 defaults are what a full tool result serialises with, including property order.
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java:70-90. The existing way to build a `GetEntityContextTool` against fixed data and a fixed clock.
- The five YAML readers, all `fromYaml(InputStream)` or a builder (`VocabularyRegistry.builder()`): `RestSources` (connectors-rest), `SecurityPolicy` (security), `PrivacyProfiles` (core.policy), `ModelDescriptors` (core.descriptor), `VocabularyRegistry` (pseudonymisation.vocabulary). Jackson 3's YAML module moves from SnakeYAML (YAML 1.1) to SnakeYAML Engine (YAML 1.2), so the boolean and octal cases are the ones expected to change.
- CLAUDE.md rule 3. All inputs are synthetic. No real names, emails or tokens, even in "non-ASCII" samples.

## Acceptance
- [ ] `mvn -B -pl data-prism-integration-tests -am verify` passes on Jackson 2 with the new tests included.
- [ ] Audit JSON projection golden test: one `AuditJsonRenderer.render(...)` output per case, compared byte for byte with a checked-in file under `src/test/resources/characterisation/`. The cases are: non-ASCII BMP text (Latin-1 and CJK), U+2028 and U+2029, each control character U+0000 to U+001F plus U+007F, a surrogate pair (one astral code point), and a value whose escapes show hex-digit casing (for example U+00E9 → `é` or `é`, whichever Jackson 2 produces now).
- [ ] Checkpoint golden test: at least two consecutive checkpoint lines produced by the production code path, compared byte for byte with a checked-in file, line terminators included.
- [ ] Full tool-result golden test: one `GetEntityContextTool` result and one `CompareEntitySourcesTool` result, built with a fixed clock and fixed synthetic data and serialised through the production mapper path. Each is compared byte for byte with a checked-in file, so property order, null handling and date format are all pinned.
- [ ] YAML characterisation, run against each of the five readers where the reader accepts the construct: a duplicate key; `yes`, `no`, `on`, `off`, `y`, `n` and `True`/`FALSE` as scalar values; an unknown top-level key and an unknown nested key; an enum value in lower case, mixed case, and with leading or trailing whitespace; the octal-looking scalars `010`, `0o10` and `0777`. Each test asserts what happens today: the parsed value, or the exception type and message prefix on refusal.
- [ ] Each test's Javadoc or name records the observed Jackson 2 behaviour in plain words (for example "duplicate key: last wins" or "duplicate key: refused"), so 167's hand-back can quote it.
- [ ] `git grep -n 'com.fasterxml.jackson' -- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/characterisation` prints nothing. The tests observe behaviour through data-prism's public API and byte or string comparisons only, so the port does not need to edit them.
- [ ] `git diff --stat origin/main` shows changes only under the two Owns paths.

## Out of scope
- Changing any behaviour the tests reveal, even one that looks wrong. Record it in the hand-back as a candidate follow-up.
- Editing existing tests, or moving any production class (156 and 157 do the moves).
- Any Jackson 3 dependency or code (167).

## Outcome (2026-10-08, wave 4)
Merged onto `release/0.6.0-jackson3` (task branch head 3dbabc4b). 44 characterisation tests in `data-prism-integration-tests` pin Jackson 2 behaviour before the port. Tester PASS on JDK 21 (full reactor, 1444 tests, 0 failures; the characterisation tests were stable over 3 JVMs); reviewer APPROVE.

Observed Jackson 2 behaviours, now golden or asserted:
- Audit JSON projection: upper-case hex escapes (`\u00E9`), U+2028 and U+2029 escaped, U+007F written raw.
- Checkpoint lines: fixed field order, one trailing LF.
- A duplicate key is accepted and the last one wins, in all five YAML readers (`SecurityPolicy`, `PrivacyProfiles`, `ModelDescriptors`, `RestSources`, `VocabularyRegistry`).
- YAML 1.1 scalars: `yes`/`no`/`on`/`off` are booleans and leading-zero numbers are octal, and both coerce into String fields (a security purpose, a key-store path, a pool entry).
- Unknown keys are silently ignored, top-level and nested, in every reader except `SecurityPolicy`, which refuses an unknown top-level key.
- Enum values are matched case-insensitively and with surrounding whitespace trimmed.
- Tool results: the text content and structured-content goldens pin the Map conversion, not the SDK wire bytes. Null inclusion and date format are not pinned there.

Golden record mode: run with `-Dcharacterisation.record=<dir>` and each actual output is also written to that directory, to regenerate or inspect goldens. Follow-ups: task 170 (readers refuse duplicate and unknown keys), PLAN.md follow-up on `get_entity_context` source order.
