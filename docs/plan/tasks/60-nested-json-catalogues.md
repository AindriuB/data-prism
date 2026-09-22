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

## Attempt 1 — failed

Branch `task/60-nested-json-catalogues`, commit on top of main 2269fbd.
Tester: PASS. Reviewer: REQUEST CHANGES. The split is not a disagreement —
they probed opposite directions, and the reviewer probed the one that fails.
The main session then confirmed the defect directly from the source rather
than relying on either report.

### 1. FAIL-OPEN — a scalar where the catalogue declares `nested:` is emitted raw

Confirmed by reading the code, not inferred:

- `ConfiguredJsonSources.java:325-326` constructs a `nested:` field as
  `new FieldMetadata(fieldName, false, null, List.of(), PrivacyNamespace.NONE,
  null, "", "nested catalogue " + catalogueName, token, token)` — the eighth
  argument is `nonSensitiveReason`, so the field is marked non-sensitive and
  `ProfilePrivacyPolicyResolver` returns PASS_THROUGH unconditionally.
- `ConfiguredJsonNestedLeafShapeGuard:50-59` is
  `if (value.isObject()) { ... } else if (value.isArray()) { ... }` with no
  else, so a scalar at that field is skipped by the guard entirely.
- `SourceValues.descend` likewise skips a non-object/non-array.
- The engine's `apply` therefore falls through to `scalar()`.

Worked case from the reviewer: catalogue declares `address` as
`nested: address`, response carries `{"id":"C1","address":"123-45-6789"}`.
The SSN-shaped string is emitted verbatim under every profile, including the
strictest. Breach of CLAUDE.md rule 2.

This is the exact mirror of the refusal the task DID specify. The task file
required refusing a response that nests DEEPER than declared
(`NESTED_LEAF_NOT_SCALAR`, which works — the tester confirmed it). Nothing
required refusing a response that nests SHALLOWER. **The gap is in this task
file's contract, not only in the implementation** — a re-run against the
unamended file could reproduce it. The acceptance criteria must require a
non-object/non-array at a `nested:` field to refuse, in the same code family.

Note for whoever verifies attempt 2: the attempt-1 tester passed this branch
because it probed "declared scalar, got object" and never probed "declared
nested, got scalar". Probe both directions.

### 2. The generated-class mechanism must go

Attempt 1 satisfied "do not change data-prism-core" by minting one Class token
per nested catalogue at runtime via `MethodHandles.Lookup.defineHiddenClass`.
The architect spike that shaped this task rejected that approach in advance —
"not a dynamically generated Java class (that would be worse than the problem
it solves and is exactly the 'reflective'/generated-type smell
architecture.md's boundary 2 is alert to)" — and the reviewer, asked to judge
it independently, reached the same conclusion and supplied the concrete
evidence:

- A privacy decision (descend / do not descend) is keyed on an object with no
  stable name, no deterministic identity, and nothing an auditor can point at.
- It leaks into operator-facing output: core's UNKNOWN_FIELD message
  interpolates `type.getName()`, which for a descended nested object renders as
  `...ConfiguredJsonNestedCatalogueTemplate/0x00007f…` — a different string
  every run. The existing test passes only because it asserts on
  `address.country` rather than on the message.
- `ConfiguredJsonSource.equals`/`hashCode` now differ between two parses of
  identical YAML, and repeated configuration mints fresh classes with no cap.

No automated rule catches any of this: `PRIVACY_MODULES_DO_NOT_MUTATE_OBJECT_
GRAPHS_REFLECTIVELY` scopes to `..core..`, `..pseudonymisation..`,
`..validation..`, `..orchestration..` — `connectors` is not in scope — and its
`methodHandleFieldAccess()` predicate names `findGetter`/`findSetter`/
`findVarHandle`/`unreflect*`, not `defineHiddenClass`. The full reactor build
including `data-prism-architecture` passes.

Replace with a bounded pool of pre-declared marker types: a fixed set of
compiled, package-private, final classes in `data-prism-connectors-rest`,
assigned to catalogues at startup, with a fail-closed startup refusal when a
source declares more nested catalogues than the pool holds. Deterministic,
greppable, reviewable, AOT-safe, and no metaspace or hidden-class lifetime
argument to make. The reviewer's assessment is that this is a change to
`ConfiguredJsonNestedCatalogueTokens.mint` alone and nothing else in the branch
needs to move.

### 3. `nestedCatalogueResolutions` must come off the public record

`ConfiguredJsonSource` exposes a public component
`nestedCatalogueResolutions` of type `Map<Class<?>, Map<String, FieldMetadata>>`.
It is the generation mechanism on the public shape, it is what makes the
record's equality parse-dependent, and it constrains task 69: anything that
serialises the configured-source list hits a map whose keys stringify to
hidden-class addresses. `nestedCatalogues()` alone satisfies the acceptance
criterion. Build the token index inside the resolver, or hold it in a
package-private carrier the record does not expose.

### What attempt 1 got right — keep all of it

- Leak-check parity at depth is real and proven non-vacuous: a raw SSN planted
  in a sibling nested field is caught by `SourceValues.prohibited`
  (RAW_SOURCE_VALUE), and a paired test swapping in a resolver whose
  `descendable()` returns false shows the same tree passing clean.
- `NESTED_LEAF_NOT_SCALAR` fires correctly for the deeper-than-declared case
  and is distinct from UNCLASSIFIED_STRUCTURE and UNKNOWN_FIELD.
- An unknown property inside a nested object still refuses as UNKNOWN_FIELD.
- Arrays of objects scrub element-wise; a null nested object passes through
  without refusal.
- All six startup refusals assert on message and source name.
- No file under `data-prism-core/` is touched.
- Full reactor `mvn -B --no-transfer-progress verify` passes, 19 modules.

### Smaller items
- `ConfiguredJsonNestedLeafShapeGuard:70` — an array leaf mixing scalars and
  objects refuses on the first object without naming the index, unlike the
  object branch which does.
- `ConfiguredJsonSources:~250` — tokens are minted before the referencing
  fields are validated, so a rejected config still mints.

## Attempt 2 — failed

Tester: PASS. Reviewer: REQUEST CHANGES. Two defects. All three attempt-1
objections are otherwise addressed and that work must be kept.

What attempt 2 fixed, verified: the scalar-at-`nested:` fail-open now refuses as
NESTED_FIELD_NOT_STRUCTURED (exact worked case `{"address":"123-45-6789"}`
refuses, raw value absent from the message; a scalar array element refuses
naming `address[1]`); the runtime hidden-class mechanism is gone, replaced by 16
pre-declared final marker classes, with `defineHiddenClass`/`defineClass`/
`MethodHandles` surviving only in a javadoc explaining why they were rejected;
`nestedCatalogueResolutions` is off the public record and equals/hashCode are
now parse-independent. Full reactor green, zero files under data-prism-core/
touched, and every attempt-1 "keep" item still passing.

### Defect 1 — the slot pool is still not deterministic across runs

`ConfiguredJsonSources.java:237` (with :303) — `assignNestedTokens` assigns
ordinals by iterating `nestedCatalogues.keySet()`, but that map is a
`Map.copyOf` immutable map whose iteration order is **randomised per JVM**. The
reviewer verified it: a 3-key `Map.copyOf` printed three different orders across
four JVM runs.

Consequence: one run refuses with `...Slot0`, the next with `...Slot1`, for the
same configuration and the same input. That is the identical unstable
operator-facing type name attempt 1 was rejected for, and it contradicts the new
javadoc's "identical across runs" claim. Cross-run stability was the entire
reason the slot pool replaced the hidden classes, so this defeats the change's
purpose.

Fix: assign ordinals over the `LinkedHashMap` before `Map.copyOf`, or over a
sorted name list.

TEST SHAPE MATTERS HERE. Attempt 2's determinism test
(`refusalMessageNamesAStableTypeAcrossRuns`) parses the same catalogue twice
**inside one JVM**, which cannot observe per-JVM randomisation — the test is
shaped like the bug. Attempt 3's test must compare across two separate JVM
processes, or assert the ordinal assignment is a pure function of a sorted key
order rather than of map iteration.

### Defect 2 — an operator reason can be mistaken for a nested pointer

`ConfiguredJsonSources.java:378` (with :250) — the resolver rebuilds its
Class-token index by parsing each field's `nonSensitiveReason` for
`NESTED_FIELD_REASON_PREFIX`. An operator writing
`nonSensitive: "nested catalogue address"`, with `address` declared, has that
plain scalar field silently rewritten to carry the address token and become
descendable; a scalar value there is then refused as
NESTED_FIELD_NOT_STRUCTURED. It fails closed, but the refusal bears no relation
to what the operator wrote, so it is undiagnosable.

Fix: reject a `nonSensitive:` reason beginning with the prefix, in the
nonSensitive branch. One `startsWith` check removes the
structural-index-by-string-parsing hazard entirely.

### Also add — an untested safety property, and the sharpest one in the design

Tokens are minted per source starting at ordinal 0, so two sources' Nth
catalogue share the same `Class` object **by design**. That is safe only
because each `ConfiguredJsonFieldMetadataResolver` is built per source with its
`nestedByToken` scoped to that source's own catalogues. Attempt 2's tester
confirmed no test builds two sources side by side to demonstrate non-collision
at the resolver level.

Two catalogues sharing a slot while the engine descends with the wrong field set
would be worse than the fail-open this task already fixed once. Add a test that
configures two sources whose same-ordinal catalogues declare *different* field
sets, and proves each scrubs against its own.

### Known shape, recorded so it is not rediscovered as a defect

The fail-open is closed in practice, not structurally removed. `nested:` fields
still carry a `nonSensitiveReason`, so core would still resolve PASS_THROUGH for
a scalar there (`JsonTreeScrubbingEngine.java:150-160`); it is fenced by the
guard at `ConfiguredJsonScrubbingEngine.java:94`, which runs before
`engine.scrub`. The reviewer traced every construction site of the nested-aware
resolver/engine pair and confirmed no path reaches the engine bypassing that
guard. Accepted — but it is a fence in front of a live fail-open, and it holds
only while that remains the single construction site. Anyone adding a second one
must re-establish it.

Also surviving, and out of scope here: core's UNKNOWN_FIELD message interpolates
`type.getName()`, so operators see `...ConfiguredJsonNestedCatalogueSlot2`
rather than `address`. Stable once defect 1 is fixed, but still
mechanism-revealing; closing it fully would need a core change this task may not
make.

### Smaller
- `ConfiguredJsonSourcesTest.java:401` — "exactly one shape" is asserted only
  for the more-than-one case; acceptance also names the none-of-three case
  inside a nested catalogue.
