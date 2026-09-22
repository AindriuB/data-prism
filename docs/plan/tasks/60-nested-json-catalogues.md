# 60 — Support one level of named nested catalogues in the configuration-driven connector

**Repo:** .
**Depends on:** none
**Owns:**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSource.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonFieldMetadataResolver.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNested*.java (new files)
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesTest.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceEndToEndTest.java
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNested*Test.java (new files)

## Goal
A flat-only `fields:` catalogue excludes most enterprise APIs from the no-code
path. Add exactly one level of nesting to the YAML vocabulary, as named
sub-catalogues declared once and referenced by name, and make the engine descend
into them using the descent `JsonTreeScrubbingEngine` already has. The whole
mechanism stays inside `data-prism-connectors-rest`.

## Context
- `ConfiguredJsonSources.java:42-52` — `FIELD_NAME`, the entire path grammar this mode accepts; `:194-250` — the three-shape field parser this gains a fourth shape beside.
- `ConfiguredJsonSource.java:36-66` — the startup validation standard a nested catalogue must match, and the record the parser returns.
- `ConfiguredJsonFieldMetadataResolver.java:45-48` (`type` is ignored today), `:56-65` (`descendable` returns hard false).
- `ConfiguredJsonScrubbingEngine.java:88-98` — `engine.scrub` then `SourceValues.prohibited(payload.body(), resolver)`; the leak check is the sharp edge below.
- `data-prism-core/.../core/JsonTreeScrubbingEngine.java:120-126` (subject inheritance), `:206-240` (`scrubNestedObject`: descent keyed on `descendable(Class)`, fail-closed otherwise).
- `data-prism-core/.../core/SourceValues.java:41-77` — the prohibited-value walk, also keyed on `descendable(Class)`.
- `docs/architecture.md:104-113` — the arbitrary-JSON-mapping design this must not become.

## Settled design — implement, do not re-open
- Named sub-catalogues only. A field entry gains a fourth shape, `nested: <name>`,
  mutually exclusive with `identifier: true`, `nonSensitive: <reason>` and
  `classifications`, where `<name>` names an entry in a new top-level map in the
  same YAML file. Each such entry is itself a flat `fields:`-shaped catalogue
  using the existing three shapes.
- Exactly one level. A nested catalogue's own entries may be scalars or arrays of
  scalars; `nested:` inside a nested catalogue is a startup refusal, not recursion.
- No dotted paths, no JSONPath, no wildcard descent, no inference from the wire.
  Property names stay exact-match under the existing `FIELD_NAME` grammar.
- `subject-json-path` is untouched: still a bare top-level property naming a
  top-level `identifier: true` field. A nested catalogue carries no identifier and
  no subject of its own; the engine inherits the subject downward already.
- core's SPI does not change. `FieldMetadata` and `FieldMetadataResolver` must not
  grow a string-keyed resolution path; the key stays `Class<?>`, so this connector
  mints one distinct `Class` token per nested catalogue inside its own module and
  keys `resolve`/`descendable` on it. (`MethodHandles.Lookup.defineHiddenClass`
  over fixed template bytes is one workable route; any route that needs no core
  change is acceptable.)

## Acceptance
- [ ] `git diff --stat` for this task touches no file under `data-prism-core/`.
- [ ] A YAML source whose `fields:` entry states `nested: address` and whose
      top-level nested-catalogue map declares `address` with scalar entries parses,
      and the resolved catalogue for `address` contains exactly the entries declared.
- [ ] A response carrying that nested object is scrubbed field by field under the
      nested catalogue: a `classifications` entry inside it is redacted or
      pseudonymised exactly as the same entry at top level would be, asserted on the
      emitted tree.
- [ ] An array of objects at a `nested:` field is scrubbed element by element under
      the same catalogue.
- [ ] **Leak-check parity.** `SourceValues.prohibited(payload.body(), resolver)` as
      called from `ConfiguredJsonScrubbingEngine.java:93` walks every nested
      catalogue the engine descends into. Proven non-vacuously: a test that places a
      sensitive nested value into the scrubbed output (for example by stubbing the
      engine's result, or by a deliberately mis-scrubbed tree) fails the leak check
      with a `PrivacyRefusedException`, and that same test passes today's code only
      because the value is not in the prohibited set.
- [ ] **New refusal.** A response containing an object where the catalogue declares
      a scalar/classified leaf is refused with a new stable code distinct from
      `UNCLASSIFIED_STRUCTURE` and from `UNKNOWN_FIELD`, naming the path and never
      the value. The code string is asserted in a test.
- [ ] A property present inside a nested object but absent from that nested
      catalogue is refused, the same way a top-level undeclared property is.
- [ ] Startup refusals, each asserted on message and named source: a `nested:` name
      with no matching catalogue entry; a nested catalogue declared but referenced by
      no field; a nested catalogue containing a `nested:` entry; a nested catalogue
      entry stating more than one or none of the three shapes; a nested catalogue
      entry marked `identifier: true`; a nested catalogue name outside `FIELD_NAME`.
- [ ] `ConfiguredJsonSource` exposes the nested catalogues (name → `Map<String,
      FieldMetadata>`) on its public shape, so task 69 can read the source's declared
      names off the same record without re-parsing the YAML.
- [ ] `mvn -pl data-prism-connectors-rest -am test` passes.

## Out of scope
- Any edit under `data-prism-core/` — including `FieldMetadata`,
  `FieldMetadataResolver`, `JsonTreeScrubbingEngine` and `SourceValues`.
- More than one level of nesting, dotted paths, or a nested subject.
- `ConfiguredJsonSourcesAutoConfiguration.java` and `ConfiguredJsonSourcesInitializer.java` (task 69 owns the first).
- Integration coverage through the real server (task 61) and documentation (task 62).
