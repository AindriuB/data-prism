# 191 — Design: sealed-link threat model, link contract and key-resolver contract

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/sealed-links.md (new)
- docs/plan/design/key-resolvers.md (new)
- docs/plan/tasks/191-design-sealed-links-and-key-resolvers.md (Outcome section only)

(Location decided by D-070-12 (a): `docs/plan/design/`, internal and not
published. Contracts move into `docs/tools.md` and `docs/configuration.md` as
each is built.)

## Goal
0.8 builds sealed links, `_links`, `follow` and key resolvers. They are new
cryptography on a privacy path, reviewed externally in 0.9. This task writes the
threat model and the two contracts 0.8 will implement, precisely enough that
the 0.9 freeze holds no surprises: tool schemas, refusal codes, audit fields and
configuration keys are named here.

## Context
- `docs/plan/roadmap.md` "Design: agent-led, HATEOAS-style" and "Risks".
- `docs/plan/PLAN.md` "Road to 1.0": D-RM-4 (only `chainable: true` fields),
  D-RM-5 (exact namespace match, translation only through a declared resolver),
  D-RM-6 (lifetime configurable, default 30 min, never beyond the scope),
  D-RM-14 (sealed, one-step, built from adapter and resolver declarations),
  D-RM-10 (real names), D-RM-12 (truncation, next-page links).
- `docs/architecture.md:144-260` — boundaries 1-8; links must respect 1
  (nothing reaches `mcp` unscrubbed), 5 (never the re-identification index),
  6 (never stored in Hazelcast), 7 (no sensitive value in logs or audit).
- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/IdentityResolver.java`
  — what a key resolver generalises; `docs/developer-guide/custom-identity-resolver.md`.
- `SecretKeyProvider` and the multi-key rotation of slice S2a
  (`docs/plan/HISTORY.md`, grep `## 2026-09-08 — S2a key rotation`).
- `RefusalCodes` (`data-prism-core/.../core/refusal/RefusalCodes.java`) — the
  code format `[A-Z][A-Z0-9_]{0,63}`.

## Required content
`sealed-links.md`:
1. Assets, actors and trust boundaries (honest agent, prompt-injected agent,
   compromised MCP client, a second caller in the same scope, a caller in another
   scope, an operator, a cluster member, anyone holding LLM provider logs).
2. Threats with mitigation and residual risk: forgery, tampering, replay across
   scope, caller or target, replay after expiry or scope end, enumeration and
   refusal oracles (including timing), link volume abuse, key rotation and
   revocation, and specifically the risk that a stateless link carries a real
   identifier as ciphertext into model context and provider logs, so key
   compromise later decrypts every link ever issued. Compare at least: (a)
   ciphertext of the real key, (b) ciphertext of a scope-local reference
   resolved server-side without Hazelcast, (c) the roadmap fallback, chaining
   only from keys a human supplies. Recommend one; leave the choice to the owner.
3. Link format: AEAD algorithm and why, key derivation from `SecretKeyProvider`
   (labelled, per purpose, rotation-aware), associated data binding scope,
   caller (or not, with reasoning), target source, relation, expiry, key id and
   format version; maximum encoded length.
4. The one uniform refusal code for every invalid link, the audit fields that
   record the real reason, and what the client never learns.
5. `follow` tool: input and output JSON Schema; how `_links` appears on a
   per-source result (shape from task 190 / D-070-1); per-response link cap and
   truncation marker (D-RM-12); that a `REFUSED` source (D-070-2) carries no
   links; configuration keys with defaults (lifetime per D-RM-6, cap per response).
`key-resolvers.md`:
6. SPI signature, declaration of from/to namespaces, cardinality (one-to-many),
   failure semantics (fail closed), budget charging, audit, and how
   `IdentityResolver` maps onto it without breaking existing implementations.
7. How a direct link versus a translated link is decided (D-RM-5), and what
   `chainable: true` means on a model field (annotation and YAML descriptor).
Both: a "Boundaries" section addressing 1, 5, 6 and 7 one by one; a "Test
obligations for 0.8 and 0.9" list (mutation-testable link checks); an "Open
decisions for the owner" section with options and a recommendation each.

## Acceptance
- [ ] Both files exist and contain every numbered item above; each item is a
      heading the reviewer can tick.
- [ ] Every proposed tool name, refusal code, audit field and configuration key
      appears in a closing table (name, kind, proposed release), and none
      collides with an existing one (`grep -rn` over `docs/tools.md`,
      `docs/audit.md`, `docs/configuration.md`; show the commands in the Outcome).
- [ ] No real system, organisation or data model is named; examples use an
      invented neutral domain.
- [ ] **Review gate:** `reviewer` returns APPROVE after checking the documents
      against D-RM-4, 5, 6, 10, 12, 14 and against boundaries 1, 5, 6 and 7 in
      `docs/architecture.md`, and confirms that no product decision is taken in
      the text (each is in "Open decisions"). The verdict is recorded in the Outcome.

## Out of scope
- Any code, SPI stub or configuration property.
- Catalog, `describe_source`, batch and bulk-follow schemas (tasks 197, 198),
  which cite this document.
- Cluster security (task 192).

## Outcome

Written: `docs/plan/design/sealed-links.md` (items 1 to 5, boundaries, test obligations, open decisions OD-1 to OD-12, closing name table) and `docs/plan/design/key-resolvers.md` (items 6 and 7, boundaries, test obligations, open decisions OD-R1 to OD-R9, closing name table). Each numbered item is a heading.

Facts found while writing, for the 0.8 builder:

- `ScopeResolver` computes a scope's expiry per request (`min(now + maxScopeLifetime, token expiry)`); a scope is `case:<caseId>` with no stored end. "Never beyond the scope" therefore cannot be a fixed end instant; the design takes the minimum at issue and records the case-id reuse residual as OD-4.
- The JDK 21 baseline has no public HKDF; the design implements it over `HmacSHA256` (OD-6).
- The existing `PrivacyNamespace` enum is a pseudonymisation kind, not a key namespace; the design uses a separate `KeyNamespace` and descriptor key `keyNamespace`.

The owner's open question (real identifier as ciphertext, scope-local reference, or human-supplied keys) is compared in `sealed-links.md` section 2 with a recommendation and is recorded as OD-1; it is not decided.

Collision check (all returned zero matches, except `resolver:` which matched only the substring in `IdentityResolver:` at `docs/configuration.md:729`):

```
for n in LINK_INVALID LINK_ATTEMPTS_EXHAUSTED LINK_KEY_SHARED LINK_KEY_UNRESOLVED LINK_KEY_WEAK INVALID_LINK_LIFETIME \
  INVALID_LINK_LIMIT KEY_UNRESOLVED Resolution INVALID_CHAINABLE_FIELD DUPLICATE_KEY_RESOLVER INVALID_KEY_RESOLVER \
  dataprism.links dataprism.key-resolvers _links _linksOmitted itemsOmitted KeyResolver @Chainable \
  chainable keyNamespace "resolver:" "links:issued" "link:id"; do
  grep -rn -- "$n" docs/tools.md docs/audit.md docs/configuration.md; done
grep -rn -w "follow\|links\|items" docs/tools.md docs/audit.md docs/configuration.md   # prose uses only
grep -rn "KeyResolver\|chainable" --include='*.java' .                                  # none
```

Domain check: `grep -n -i -E "payroll|payslip|tax|revenue|RPN|PPSN|employ" docs/plan/design/*.md` returns nothing. Examples use parcels, depots and routes.

Review gate: NOT YET RUN. The implementing agent has no reviewer to call; the `reviewer` verdict (APPROVE required, against D-RM-4, 5, 6, 10, 12, 14 and boundaries 1, 5, 6, 7, and confirming no product decision is taken in the text) must be recorded here by the caller.

Revision after review (reviewer requested changes): OD-1 comparison now states the costs of (b) (depth cap, n(n + 3) / 2 reads, compounding drift, one-to-many parents) and of (a)/(a') (ciphertext of real keys in provider logs, length leak, key destruction outside the product); the recommendation changed from (b) to (a') with padding, still the owner's call. Sizes recomputed (defaults `max-length` 1536, `max-chain-depth` 3, ceiling 4); `KeyResolver.resolve` returns a `Resolution` with a `more` flag; limiter counts only non-honest failures, per principal, over a fixed window; collection links, one-to-many outer status, HKDF salt, clock skew and ODs 9 to 12 and R7 to R9 added. Review gate: still to be re-run by the caller.
