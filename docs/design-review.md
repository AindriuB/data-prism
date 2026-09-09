# Data Prism — Design Review and Amendments

Status: amendments to `pack.md`. Where this document and the pack disagree, this document wins.
Scope: architecture only. No code has been written yet.

The pack's core thesis is sound: separate *identity consistency* from *data truth*, enforce privacy
server-side, treat the privacy engine and response validator as the security boundary. The amendments
below fix gaps that would otherwise surface as rework in Phase 3–6.

---

## A. Correctness gaps (must fix before coding)

### A1. The pseudonym subject is not always the record's own identifier

The pack keys every synthetic value off the record's single `@InternalIdentifier`. That breaks silently as
soon as one record mentions two people:

```java
record LoanApplication(
    @InternalIdentifier String applicationId,
    @SensitiveData(namespace = PERSON_NAME) String applicantName,
    @SensitiveData(namespace = PERSON_NAME) String guarantorName) {}
```

Both names resolve to the same pseudonym, merging two humans into one in the LLM's view. That is worse
than a leak — it is a false factual assertion the model will then reason from.

**Amendment.** Make the pseudonym subject explicit. Pseudonymisation is keyed on
`(scopeId, subjectId, namespace, algVersion, keyId)` where `subjectId` resolves as:

1. `@SensitiveData(subject = "<fieldName>")` if present — names the field holding this value's subject id;
2. otherwise the enclosing object's `@InternalIdentifier`;
3. otherwise fail closed (`REDACT` + security audit). Never fall back to hashing the value itself — that
   would make the pseudonym a function of the plaintext and reintroduce dictionary attacks.

```java
@SensitiveData(namespace = PERSON_NAME, subject = "guarantorId") String guarantorName;
```

Add `@SubjectIdentifier(role = "guarantor")` as the multi-subject form, and define `@InternalIdentifier`
as shorthand for `@SubjectIdentifier(role = "self")`.

### A2. Pseudonym collisions inside a scope

`HMAC → seed → pick(firstNames) × pick(lastNames)` over a realistic dictionary (5k × 5k = 25M pairs)
collides by the birthday bound at roughly 6,000 distinct subjects in one scope. In an investigation-scoped
platform that is reachable, and a collision makes two real entities indistinguishable to the model.

**Amendment.** Every identity-bearing pseudonym carries a deterministic discriminator drawn from the same
HMAC output: `Alex Murphy · 7QF2` (20 bits, base32 with an ambiguity-free alphabet). This keeps determinism
without the cache (G8), removes practical collisions, and signals to the model that the value is a
pseudonym rather than a real name. Make the rendering a `PseudonymRenderer` SPI so an organisation can
choose a different format or accept bare names with the collision risk. Emit
`dataprism.identity.collision` when the registry does observe one.

### A3. Cross-system correlation assumes a shared key that does not exist

The worked example has all three APIs returning `customerId = 123`. Real enterprises do not share a key —
that is precisely why correlation is hard. The pack has no entity-resolution story, and adding matching
later would change the shape of `EntityCorrelationService` and of the orchestrator's fan-out.

**Amendment.** State the boundary and design for it now. V1 requires a *resolvable* correlation key and
ships an SPI, not a matcher:

```java
public interface IdentityResolver {
    CanonicalId resolve(SourceRef ref);        // (sourceSystem, sourceRecordId) -> canonical id
    List<SourceRef> expand(CanonicalId id);    // canonical id -> per-source keys to fetch
}
```

Default: `PassThroughIdentityResolver` (source key == canonical key), which is what the example app uses.
Probabilistic matching is explicitly out of scope and lives behind this interface. Note that without
`expand`, the orchestrator cannot build its fan-out at all — this interface is on the critical path, not
an optional extra.

### A4. Records cannot be scrubbed by field reflection

Java records are immutable and their canonical constructors may validate. Reflective field mutation on a
record is unsupported, and `Unsafe` is not acceptable inside a security component. Reconstruction through
the canonical constructor fails on validating records and on any type the platform did not author.

**Amendment.** The scrubbing engine operates on a **data tree, not on Java objects**:

```
Source DTO --Jackson--> JsonNode + type-derived FieldMetadata --> scrub tree --> CanonicalEntity --> serialise
```

Consequences, all improvements:

- nesting, collections, maps, polymorphism and *unknown* fields fall out for free;
- unannotated fields are visible to the engine, so fail-closed actually works;
- the engine can be installed as a Jackson `Module` on the dedicated `ObjectMapper` used by the MCP layer,
  making "raw DTO reaches the LLM" structurally impossible rather than a code-review rule.

Keep `<T> T scrub(T, Class<T>, PrivacyContext)` as a test convenience, documented as *not* the enforcement
path.

### A5. The output validator will reject its own synthetic data

Regex detectors for email/phone/address match `SYNTHESIZE`d emails and addresses. Fail-closed then rejects
every response containing a synthesised contact field. That is a deadlock, not a tuning problem.

**Amendment.** Make the validator scope-aware. The pseudonymisation layer maintains a per-scope registry of
emitted values; a detector hit whose exact value is in the registry passes. Two corollaries: pseudonyms
must be generated in formats the detectors recognise (so a bug leaking a *real* email is still caught by
shape), and registry lookups must be constant-time and never logged.

### A6. Hash-chained audit vs. horizontal scaling

G6 requires stateless instances; a single global `previousHash` chain requires a single writer. Instances
racing on `previousHash` produce forks indistinguishable from tampering.

**Amendment.** Chain per writer: `(instanceId, sequence, previousHash)`, with the sink providing total
ordering. Ship two `AuditSink` implementations — a dev/file sink and an append-only sink (Kafka partitioned
by `instanceId`, or a table with a per-instance sequence). If a single tamper-evident root is required,
publish a periodic signed checkpoint over all instance heads. Do not claim stronger tamper-evidence than
the configured sink actually provides.

---

## B. Missing capabilities

### B1. Re-identification is not optional, and is absent from the pack

An investigation ends with a human needing to know who `Alex Murphy · 7QF2` is. HMAC is one-way, so this
requires a stored reverse mapping — the single most sensitive object in the system.

**Amendment.** Design the re-identification path now, with these properties:

- **Never an MCP tool.** A separate authenticated surface, under a different authorisation scope,
  unreachable by the LLM or by any credential the LLM holds.
- Reverse index `(scopeId, namespace, syntheticValue) -> subjectId`, stored only alongside the forward map,
  same TTL, same cluster isolation. Still no raw PII: `subjectId` only. Resolving `subjectId` to a person
  remains the source systems' job.
- Every re-identification is a first-class audit event with mandatory purpose, and four-eyes approval
  available as configuration.

Leave this undesigned and someone will eventually add it as an MCP tool, which collapses the entire model.

### B2. `@NonSensitive` — fail-closed needs a positive assertion

§30 fails closed on unclassified fields, but offers no way to say "this field is genuinely safe". Developers
will therefore annotate everything `@SensitiveData(action = PASS_THROUGH)`, which is indistinguishable from
a real classification and defeats auditing.

**Amendment.** Add `@NonSensitive(reason = "...")`, required on every field of an `@LlmExposedModel` that
carries no `@SensitiveData`. `reason` is mandatory free text and becomes the review artefact. Enforce with
an annotation processor (or ArchUnit) so an unmarked field on an exposed model fails the *build*, not a
production request.

Also add `@LlmExposedModel(strict = true|false)`, where `strict = false` (redact-and-warn) is rejected at
startup outside non-production profiles.

### B3. `GENERALIZE` is undefined

`FINANCIAL: GENERALIZE` has no semantics in the pack. Bucket boundaries are policy, are domain-specific,
and leak by triangulation under repeated querying.

**Amendment.** `GeneralizationStrategy` SPI keyed by namespace, with declarative buckets in the profile
YAML and a default `RefuseGeneralization` that redacts when no strategy is configured. Document the
triangulation risk and rate-limit repeated generalised reads of the same subject within a scope.

### B4. Key rotation

§83 versions the algorithm but not the key. Rotating the HMAC key changes every pseudonym and would corrupt
an in-flight investigation.

**Amendment.** `PrivacyContext` pins `(algorithm, algVersion, keyId)` at scope creation, immutable for the
scope's life. New scopes pick up the current key; retired keys stay resolvable until every scope using them
has expired. `PseudonymisationVersion` becomes
`record PseudonymisationVersion(String algorithm, String version, String keyId)`.

### B5. MCP surface additions

Add `describe_entity_model(entityType)` — returns the *shape* of what the model can expect (field names,
namespaces, which fields are pseudonymised, which are redacted) and no data. Without it the LLM guesses at
redacted fields and hallucinates around them.

Explicitly do **not** add any tool that opens, selects, or extends a privacy scope. Scope comes from the
authenticated session (§51); exposing it as a tool hands the model the one lever that breaks scope
isolation (T4).

---

## C. Structural amendments

### C1. Module layout

§9 puts `mcp` above `connectors` in the dependency chain, while §72's ArchUnit rule forbids exactly that
dependency. Resolve by inserting an orchestration module and inverting the connector direction:

```
annotations
  └── core                       (privacy model, metadata, policy, canonical, SPI interfaces)
        ├── pseudonymisation
        │     └── hazelcast      (SyntheticIdentityResolver impl)
        ├── validation           (scanner + LlmResponseValidator)
        ├── security             (authz contracts)
        ├── audit
        └── orchestration        (ContextOrchestrator; depends on adapter *interfaces* only)
              └── mcp            (tool definitions, transport)

connectors-rest                  (implements DataSourceAdapter; nothing depends on it)
connectors-search                (Elasticsearch)
spring-boot-starter              (wiring; depends on everything, nothing depends on it)
example/example-sources          (three stub APIs)
example/example-app
```

`mcp` and `orchestration` see adapter *interfaces* from `core`; concrete connectors are runtime-wired by
the starter. The ArchUnit rule then holds by construction rather than by vigilance.

### C2. Package and coordinates

`com.example.dataprism` must not survive the first commit. Choose the real group id now — renaming a
published annotation package is a breaking change for every consuming application.

### C3. Annotation targets

Declare `@Target({FIELD, RECORD_COMPONENT, METHOD, PARAMETER})` on all three annotations, and have
`FieldMetadataResolver` read record components first, then fields, then accessors, so metadata resolves
identically regardless of where javac propagated the annotation.

### C4. Hazelcast topology

> **Reversed 2026-09-09 (S7).** This section recommended client–server topology.
> The build instead runs Hazelcast **embedded** — see
> `docs/architecture.md#decisions-worth-knowing`, entry dated 2026-09-09, for
> the reasoning: a synthetic value is a pure function of scope, subject,
> namespace and key, so a lost partition costs a recomputed HMAC and never a
> different answer, which removes the objection below. The original reasoning
> is left in place rather than deleted, because it was the reasoning believed
> at the time and the reversal is only correct in light of a property (pure
> determinism without the cache) this codebase did not yet demonstrably have
> when this section was written.

Embedded members in autoscaled stateless pods rebalance partitions on every scale event — the wrong
property for the map holding identity mappings. Use **client–server topology** against a dedicated,
isolated cluster. One map with scoped keys; TTL from `PrivacyContext.expiresAt`; an index on `scopeId` so
scope purge is a single predicate delete; `MapStore` explicitly disabled; persistence off unless the
organisation has consciously accepted storing mappings at rest.

---

## D. Risk framing the pack understates

### D1. Pseudonymised data is still personal data

Under GDPR Art. 4(5) and Recital 26, pseudonymisation is a security measure, not anonymisation. Data Prism
output remains personal data, and sending it to a third-party LLM remains processing that needs a lawful
basis, a DPIA, and — for non-EU providers — a transfer mechanism. The pack's framing ("safe for AI") will
be read by stakeholders as "GDPR no longer applies". Add `docs/data-protection.md` saying so plainly. It is
only a documentation fix, but omitting it is the most likely route from this project to real-world harm.

### D2. Synthetic names encode demographics

Locale-matched dictionaries (Patrick Murphy → Alex Murphy) preserve apparent ethnicity and gender: that
helps the model reason and leaks a protected attribute. A neutral pool removes the signal and can make
output read oddly. Default to a single locale-neutral pool, make it configurable, and document the
trade-off rather than making it by accident.

### D3. Prompt injection — flag, never sanitise

§75 is right that source data is untrusted. Do not strip or rewrite suspicious source strings: that would
violate §97 (never make the data look cleaner than it is). Instead emit source text only inside structured
JSON fields — never concatenated into prose — and attach an advisory `ConsistencyFinding` of type
`SUSPECTED_INSTRUCTION_CONTENT` when heuristics fire. The model then sees the anomaly as data.

### D4. Denial of wallet

§74 caps records and sources. Add cost-shaped limits: a per-principal per-scope budget on emitted context
tokens, and a cap on total scope-lifetime fan-out. An agent in a retry loop is the realistic failure mode,
not a malicious one.

---

## E. Smaller corrections

- §22 `HmacSyntheticGenerator` must take `keyId` and algorithm version from `PrivacyContext`, not from a
  field. As written it cannot support B4.
- §24 resolver: `putIfAbsent` returning a *different* value indicates a collision or key-rotation error,
  not a benign race. Log and meter it; do not silently prefer `existing`.
- §26 metadata cache: use `ClassValue<List<FieldMetadata>>` — correct classloader semantics, no leak.
- §34 `ConsistencyFinding.sources` exposes real source-system names, contradicting §32. Pseudonymise source
  names per scope by default, or gate behind an explicit `EXPOSE_SOURCE_NAMES` capability.
- §45 fingerprints: HMAC under the scope key, not a plain hash. A plain hash of "John Smith" is trivially
  reversed by dictionary.
- §52 `AuditEvent.idInternal` is raw, which §53 itself calls sensitive. Store the pseudonymised subject id
  in audit; keep the real one only in the access-controlled re-identification store.
- §89 metrics: add `dataprism.identity.collision`, `dataprism.privacy.failclosed`,
  `dataprism.reidentification`.
- Add **golden-vector tests**: a checked-in file of `(scopeId, subjectId, namespace, key) -> expected
  output` for algorithm v1, so any accidental change to the generator breaks the build. This is what
  actually enforces §83.
