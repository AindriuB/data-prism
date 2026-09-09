# Data Prism — Development Plan

Companion to `design-review.md`. Sizing is for one experienced Java engineer; a pair roughly halves the
wall-clock on slices 3 onward, since most of them are independent.

## Sequencing principle

The pack's Phase 1–9 order builds each layer to completion before the next. That defers the riskiest
question — *does the end-to-end privacy boundary actually hold?* — to the very end. Invert it: build a
thin, ugly, working end-to-end path first (Slice 0), then thicken it. Every slice after Slice 0 keeps the
build green and the end-to-end test passing.

Two invariants are enforced from Slice 0 and never relaxed:

1. No path from a source adapter to the MCP layer that bypasses the privacy engine (ArchUnit + the
   dedicated `ObjectMapper`).
2. Every response passes the output validator before serialisation, and a validation failure fails the
   request.

---

## Slice 0 — Walking skeleton (≈1 week)

One vertical thread through every layer, with everything stubbed that can be stubbed.

- Parent POM, Java 21, module skeleton per `design-review.md` §C1, real group id (§C2).
- CI: build, test, ArchUnit, dependency-check, SpotBugs/ErrorProne.
- `@InternalIdentifier`, `@SensitiveData`, `@NonSensitive`, `@LlmExposedModel` — annotations only.
- Hardcoded `PrivacyContext`, one hardcoded stub `DataSourceAdapter` returning one DTO.
- `HmacSyntheticGenerator` v1 with a fixed dev key, no cache.
- A trivial validator that only checks "no field on the response is missing from the metadata map".
- MCP server exposing `get_entity_context` only, over stdio.
- Log-file `AuditSink`.

**Exit:** an MCP client calls `get_entity_context("CUSTOMER", "123")` and receives a pseudonymised
response, with an audit line written. One integration test asserts the whole path.

**Why first:** it forces the MCP SDK choice, the `ObjectMapper` boundary, and the `PrivacyContext`
plumbing to be settled while they are cheap to change.

---

## Slice 1 — Metadata and policy (≈1.5 weeks)

- `FieldMetadataResolver` over records, classes and accessors (§C3), cached via `ClassValue`.
- `@SubjectIdentifier` / `subject =` resolution (§A1) — the subject graph, not just the record id.
- `PrivacyPolicyResolver` with YAML profiles and the §29 precedence chain.
- Fail-closed handling with `@NonSensitive(reason=...)`, plus the annotation processor that fails the
  build on an unmarked field of an `@LlmExposedModel` (§B2).

**Exit:** unit tests for every precedence combination; a deliberately unannotated field fails compilation;
policy overrides `suggestedAction` in a test that would pass if it did not.

---

## Slice 2 — Pseudonymisation, properly (≈1.5 weeks)

- HMAC-SHA-256 keyed on `(scopeId, subjectId, namespace, algVersion, keyId)`; key material from a
  `SecretKeyProvider` SPI (dev: env var; prod: the organisation's vault).
- Deterministic generators per namespace: name, address, email, phone, org name, account id.
- Discriminator suffix and `PseudonymRenderer` SPI (§A2).
- Key rotation: `keyId` pinned at scope creation, multi-key resolution (§B4).
- **Golden-vector test file** pinning v1 outputs.

**Exit:** determinism across JVM restarts; different scopes diverge; golden vectors match; a property-based
test over 10⁶ subjects finds no collision in one scope.

---

## Slice 3 — Scrubbing engine (≈2 weeks) — the core deliverable

- Tree-based engine (§A4): `JsonNode` traversal driven by type metadata.
- All seven `PrivacyAction`s; `GeneralizationStrategy` SPI with declarative buckets (§B3).
- Nested objects, collections, maps, `null`, cycles, unknown/extra JSON properties.
- Installed as a Jackson `Module` on a dedicated MCP `ObjectMapper`.

**Exit:** the pack's §67 cross-model test passes; a hostile fixture DTO (PPSN, IBAN, JWT, API key, password,
free-text address) emits nothing prohibited; a test that removes an annotation fails (§71).

---

## Slice 4 — Validation and scanning (≈1.5 weeks)

- `SensitiveDataScanner`: structural checks plus pattern detection (PPSN/SSN, IBAN, card PAN with Luhn,
  email, phone, JWT, common API-key shapes).
- Scope-aware allowlisting of emitted pseudonyms (§A5).
- `LlmResponseValidator`, fail-closed, violations recorded without the offending value.

**Exit:** a synthesised-email response validates clean; the same response with one real email injected is
rejected; violation records contain a path and classification but never a value.

---

## Slice 5 — Connectors and orchestration (≈2 weeks)

- `RestDataSource` on `RestClient`; server-side endpoint config only, no LLM-supplied URLs.
- Virtual-thread fan-out, per-source timeouts, circuit breaker, bulkhead, bounded concurrency.
- `IdentityResolver` SPI + `PassThroughIdentityResolver` (§A3).
- `ContextOrchestrator`; request limits including the cost budget (§D4).

**Exit:** a slow source trips its timeout without failing the whole request; the fan-out cap is enforced;
ArchUnit proves `mcp` and `orchestration` do not reference `connectors-*`.

---

## Slice 6 — Canonical model and correlation (≈1.5 weeks)

- `CanonicalEntity`, `SourceProvenance`, per-scope pseudonymisation of source system names (§E).
- `EntityCorrelationService`, `ConsistencyFinding`, normalisation-aware comparison (case, whitespace,
  diacritics) so "Pat" vs "Patrick" is a genuine finding rather than a formatting artefact.
- Injection-heuristic finding type (§D3).

**Exit:** the pack's §64 worked example produces one consistent pseudonym across three sources plus a
`name = INCONSISTENT` finding, with no raw variant values in the output.

---

## Slice 7 — Hazelcast (≈1 week)

**Landed 2026-09-09 diverging from the exit criteria below; see
`docs/plan/HISTORY.md`, grep `S7 embedded Hazelcast`.** The topology is
**embedded**, reversing §C4 (`docs/design-review.md#c4-hazelcast-topology`
carries the reversal note; the reasoning is in
`docs/architecture.md#decisions-worth-knowing`, 2026-09-09). The
Testcontainers kill test below was not built — tests instead use an embedded
member because Docker was unavailable during the session that built the
slice; a multi-member kill test is still open, unscheduled.

- ~~Client–server topology (§C4)~~ — embedded instead (see above). One map, scoped keys, TTL, `scopeId` index, scope purge.
- Forward and reverse maps; mTLS, auth, isolation.
- Cache-failure behaviour: degrade to pure HMAC, emit a metric, never change output.

**Exit (as originally written, not what shipped):** the §69 test — identical
output with the cluster up and with it killed mid-test (Testcontainers). What
actually shipped: identity-cache failure degrades to a recomputed HMAC without
changing output, proven against the embedded member; no Testcontainers kill
test exists.

---

## Slice 8 — Security (≈2 weeks)

- OAuth2 resource server per the MCP authorization spec; mTLS for source calls.
- `AuthorizationService`, RBAC + ABAC, purpose validation, `InvestigationContext` derived entirely from
  the authenticated session — never from tool arguments.
- Scope lifecycle: creation, TTL, extension, revocation — all outside the MCP surface (§B5).

**Exit:** a tool call carrying its own `principalId` or `caseId` in arguments is ignored, and the attempt is
audited.

---

## Slice 9 — Audit and observability (≈1.5 weeks)

The per-writer hash chain (§A6) has existed since S0, not this slice. S9a
(`docs/plan/HISTORY.md`, grep `S8 wave 2` and `Task 09`) closed the rest of
this slice's original scope: pseudonymised subject id in audit, Micrometer
metrics, and a `PiiLogScanTest` that can actually fail. The only piece of this
slice's original list that remains unbuilt is the **append-only audit sink**
(Kafka or a database table with a per-instance sequence) — the file/SLF4J sink
is what ships. Deliberately deferred, not forgotten.

- Per-writer hash chain with `instanceId` (§A6) — **done since S0**.
- File sink — **done**. Append-only sink — **open, deliberately deferred.**
- Pseudonymised subject id in audit records (§E); HMAC parameter fingerprints (§45) — **done (S9a).**
- Micrometer metrics per §89 plus the three additions; correlation id threaded through everything — **done (S9a).**
- Log filters that fail the build on any format string interpolating a sensitive-typed value — **not built**; `PiiLogScanTest` catches this at test time, not build time.

**Exit:** a chain-verification tool detects a tampered record; a log-scanning test over a full integration
run finds no PII.

---

## Slice 10 — Re-identification surface (≈1 week)

Separate application module, separate port, separate authorisation scope, no MCP dependency at all (§B1).
Mandatory purpose, mandatory audit, optional four-eyes approval. ArchUnit forbids `mcp` from depending on
this module.

---

## Slice 11 — Example application and search (≈2 weeks)

The three stub source APIs (Customer, Account, Order) with deliberately
divergent representations landed early, in S6
(`docs/plan/HISTORY.md`, grep `S6 correlation`), not here. What remains:

- Elasticsearch connector with index/field allowlists, complexity and size caps, no DSL pass-through (§44).
- `search_entity_data` with a controlled query grammar; `describe_entity_model` (§B5).
- Docker Compose bringing up the whole thing.

---

## Slice 12 — Hardening (≈1.5 weeks)

- Full threat-model test suite T1–T10.
- Mutation testing (PIT) targeting the privacy and validation modules; treat a surviving mutant there as a
  build failure.
- `docs/data-protection.md` (§D1), `docs/threat-model.md`, ADRs for each ambiguous decision (§94 tenth).
- Load test: fan-out latency, scanner cost on large payloads, cache hit ratios.

---

## Total

Roughly **19–20 engineer-weeks** for one engineer, or a ~12-week calendar with two. Slices 4, 5 and 7 are
largely independent of each other once Slice 3 lands, and are the natural parallelisation points.

## Decisions to make before Slice 0

1. **Group id / package root.** Blocks everything (§C2).
2. **MCP SDK.** The official Java SDK (`io.modelcontextprotocol.sdk`) directly, or via the Spring AI MCP
   server starter. Verify current coordinates and version at build time. Transport: streamable HTTP for
   production, stdio for local development.
3. **Secret management platform** for the HMAC key — determines the `SecretKeyProvider` implementation.
4. **Audit sink of record** — Kafka, database, or an external immutable log. Drives §A6.
5. **Whether re-identification is in scope for V1.** If it is not, the reverse map must still be built in
   Slice 7, or retrofitting it later invalidates every existing scope.
