# Key resolvers: SPI contract and the direct-versus-translated rule

Status: design for 0.8, reviewed externally in 0.9. Internal (`docs/plan/design/`, D-070-12 (a)); not published.
Task 191. Companion: `sealed-links.md` (the link format, refusal, `follow` and `_links`). No code, SPI stub or configuration property is created by this task; every name is a proposal.

Examples use an invented neutral domain: a parcel service keyed by a parcel reference, a depot service keyed by a depot code, a courier-roster service keyed by a route code.

Inherited decisions: D-RM-4 (only `chainable: true` fields produce links), D-RM-5 (exact namespace match; translation only through a declared resolver), D-RM-14 (links are one step, built from adapter and resolver declarations), D-RM-10 (real names), D-070-1 and D-070-2 (per-source result shape; refused sources omitted).

## What a key resolver is

Two sources rarely share an identifier. The parcel service knows a parcel by `PARCEL-7Q2K4M`; the courier roster knows the same parcel only by the route it travelled on. A key resolver translates one identifier type (a namespace) into another so that a link can lead from one source to the other. It generalises `IdentityResolver` (today: subject to per-source keys) from "the canonical subject" to "any declared namespace to any other".

A resolver runs inside the server and sees real keys. It is trusted code reviewed by the operator, like an adapter. Its output never goes to the model.

Terms: a **namespace** here is a string label for an identifier type, for example `parcel-ref` or `route-code`. It is distinct from the existing `PrivacyNamespace` enum, which classifies pseudonymised value kinds (person name and so on). The two must not be conflated: `PrivacyNamespace` decides how a value is synthesised; a key namespace decides which sources accept it as input.

## 6. SPI signature, declarations, cardinality, failure, budget, audit and relation to `IdentityResolver`

### SPI (proposed, in `data-prism-core`, package `...core.spi`)

```java
public interface KeyResolver {

    /** Stable name; appears in the audit, never to the client. [a-z][a-z0-9-]{0,63} */
    String name();

    /** The namespace of the keys this resolver accepts. */
    KeyNamespace from();

    /** The namespace of the keys it returns. Must differ from from(). */
    KeyNamespace to();

    /**
     * Translate one key. Returns zero or more keys (one-to-many). An empty list means
     * "no translation known"; it is not an error. Throws {@link KeyResolutionException}
     * on any failure; never returns null; never returns more than maxResults.
     */
    List<String> resolve(String key, ResolutionContext context, int maxResults);

    /** Never null; never a real name or key. */
    record KeyNamespace(String value) {
        // [a-z][a-z0-9-]{0,63}; validated like RefusalCodes validates codes
    }
}
```

`ResolutionContext` carries only the `scopeId`, the principal id, the purpose and a deadline. It carries no raw record and no other keys. It is the same information `IdentityResolver` implementations already receive through the `PrivacyContext`.

`KeyResolutionException` carries a closed reason enum (`UNAVAILABLE`, `TIMEOUT`, `REJECTED`, `TOO_MANY`), never the key or a message built from it (boundary 7).

Registration: a resolver is a Spring bean (or registered through the builder in plain Java). There is no configuration that names one. A deployment with no resolver bean declares no translated links.

### Declaration of from/to

The pair `(from(), to())` is the declaration. The link builder reads declarations only; it does not call `resolve` at issue. At issue it computes, for a chainable field in namespace N:

- direct targets: every permitted source with an input key in namespace N;
- translated targets: for every resolver R with `from() == N`, every permitted source with an input key in `R.to()`.

Not calling `resolve` at issue means that issuing links does no I/O, cannot fail because a backing service is down, cannot leak a mapping through timing, and does not charge the budget. The cost is that a translated link is a promise that may turn out empty at follow time (status `NO_DATA`, below).

Declaration uniqueness: at most one resolver per `(from, to)` pair; startup refuses a duplicate (`DUPLICATE_KEY_RESOLVER`). A resolver whose `to()` equals `from()` is refused (`INVALID_KEY_RESOLVER`). A declared pair whose namespaces no source or field uses is a startup warning, not an error.

### Cardinality (one-to-many)

`resolve` may return several keys (one parcel, many depots on its route). The contract:

- the resolver is asked for at most `dataprism.key-resolvers.max-targets` results (default 5); asking implies the cap, and returning more is a violation treated as `TOO_MANY` (fail closed, whole translation refused, not truncated silently);
- the server reads the target source once per returned key, in ascending key order, each read charged and subject to the per-source deadline;
- a `follow` over a translated link therefore returns `items` (sealed-links.md section 5): one entry per key with its own status and `entity`, `itemsOmitted` when the resolver reported more than it returned. Keys themselves are never shown; items are in the resolver's order after sorting by synthetic key so the output is deterministic and discloses no ordering of real keys.

Truncation is by design visible (a marker), consistent with D-RM-12.

### Failure semantics (fail closed)

| Event | Result |
|---|---|
| Resolver throws `UNAVAILABLE`, `TIMEOUT`, `REJECTED` | The translated follow returns the target source with a per-source status `KEY_UNRESOLVED` and no `entity`; audit `resolver:<name>` = `FAILED:<REASON>` |
| Resolver returns more than `maxResults` | Treated as `TOO_MANY`; no entities returned; status `KEY_UNRESOLVED` |
| Resolver returns an empty list | Status `NO_DATA` (a truthful "nothing known"), audit `resolver:<name>` = `EMPTY` |
| Resolver returns a blank or over-long key (over 256 characters) | `KEY_UNRESOLVED`; audit `INVALID_OUTPUT` |
| Resolver runtime exception of any other type | Same as `UNAVAILABLE`, never propagated with its message |
| Resolver exceeds `dataprism.key-resolvers.timeout` (default `PT2S`) | `TIMEOUT` |
| Resolver bean missing at follow time (configuration changed since issue) | The link is `LINK_INVALID`, audit reason `RELATION_UNDECLARED` |

A resolver failure never falls back to a direct read, to a different resolver, or to the untranslated key. `KEY_UNRESOLVED` is a new per-source status code and must be entered in task 200's register before 0.8; clients treat an unknown status as "not answered" (D-070-3).

The status is returned only to a holder of a valid, authenticated link, so it opens no oracle for a forger.

### Read budget

A resolver call is charged `dataprism.key-resolvers.budget-cost` (default 1) against the scope's read budget, because a resolver commonly reads a backing service. Each translated target read is charged as a source read. A refusal because the budget is exhausted is the existing `SCOPE_READ_BUDGET`. Charging happens before the call; a call that cannot be afforded is not made. The charge is applied on both budget implementations (in-process and Hazelcast), as the 0.8 per-source charging task requires.

### Audit

Dispositions on the `follow` call (no change to record fields or the hash encoding):

| Key | Value |
|---|---|
| `resolver:<name>` | `OK:<n>` (n keys returned), `EMPTY`, `FAILED:<REASON>` |
| `resolver:<name>:ms` | elapsed milliseconds, integer |

Never recorded: the input key, any returned key, any fingerprint of them. `<name>` is the resolver's own name from `name()`, validated against the pattern so that it cannot carry data. This is the audit of real behaviour and carries real resolver names; the client never sees them (see Boundaries).

### How `IdentityResolver` maps onto it without breaking implementations

`IdentityResolver` is `resolve(SourceRef) -> CanonicalId` and `expand(CanonicalId, List<String>) -> List<SourceRef>`. It stays exactly as is, with no signature change and no deprecation in 0.8: existing implementations, `docs/developer-guide/custom-identity-resolver.md` and the code that uses it for the orchestrator's fan-out keep working.

A provided adapter `IdentityResolverKeyResolvers` can expose an existing `IdentityResolver` as key resolvers for a declared pair of sources, so that an application with an existing mapping does not write a second one:

- the adapter is constructed with the `IdentityResolver`, the source names `S1` and `S2`, and the two namespaces;
- `resolve(key)` is `expand(resolve(new SourceRef(S1, key)), List.of(S2))`, returning the keys of `S2`;
- it is opt-in per pair; nothing is generated automatically, because an `IdentityResolver` that is correct for fan-out says nothing about which namespaces a link should join.

Differences to keep visible: `IdentityResolver` is subject-centred (it goes through a canonical id), a `KeyResolver` is namespace-centred (it does not require a canonical id); `IdentityResolver` is consulted at fan-out, a `KeyResolver` only at `follow`.

## 7. Direct versus translated links, and what `chainable: true` means

### Deciding direct or translated (D-RM-5)

For a chainable field in namespace N, and a source S that is permitted for the caller:

1. S declares an input key in namespace N exactly (string equality of the validated namespace values; no case folding, trimming, prefix or alias): a **direct** link `rel=direct`.
2. Otherwise, a declared resolver R with `R.from() == N` and `R.to()` equal to an input namespace of S: a **translated** link `rel=translated`, carrying the namespace and `toNamespace`.
3. Otherwise no link. Never a guess, a near-match, a chain of two resolvers, or a resolver whose `to()` is itself resolvable onward.

When both 1 and 2 apply to the same source, only the direct link is issued. When two resolvers reach the same source, both links are issued only if they differ in `toNamespace`; otherwise startup refuses the duplicate pair.

The decision is made from declarations alone, at issue; `resolve` is invoked only at follow.

### What `chainable: true` means on a model field

A field marked chainable says: "the value of this field is a key in namespace N that another source accepts; the server may use the raw value to build a link". It is metadata about a field that is, in every other respect, an identifier like any other. It does not change the field's classification or its disposition: it is still removed or pseudonymised in output exactly as before. Chainable never causes a value to be emitted.

Requirements:

- It is valid only on a field that is already classified as an identifier (`@InternalIdentifier` or `@SubjectIdentifier`); a chainable flag on an unclassified, `@NonSensitive` or free-text field refuses startup (`INVALID_CHAINABLE_FIELD`). A non-sensitive field has no reason to be chainable, and an unclassified field must stay fail-closed.
- It names its namespace; a chainable field with no namespace refuses startup.
- Descriptors may add the flag but may not remove it or change a namespace that the annotation declared, and may never relax classification (the established rule: descriptors only tighten; disagreement on a namespace is a startup failure, HISTORY "S3 scrubbing engine").
- A chainable field produces a link only if the field is present in a record of a source whose result is `ANSWERED` and not `REFUSED`.

Annotation (proposed, in the `annotations` module):

```java
@InternalIdentifier
@Chainable(namespace = "parcel-ref")
String parcelRef;
```

YAML descriptor (proposed; a key inside the existing model descriptor field entry, strict-keys parsing as everywhere):

```yaml
models:
  ParcelRecord:
    fields:
      parcelRef:
        classification: INTERNAL_IDENTIFIER
        chainable: true
        keyNamespace: parcel-ref
```

`chainable: true` without `keyNamespace` is an error in the descriptor. The Java annotation takes `namespace` as its single attribute; its presence is the `true`. The names `@Chainable`, `chainable` and `keyNamespace` are proposals (table below). The existing descriptor key `namespace` already means `PrivacyNamespace`, so a different key is used to avoid ambiguity.

A source's **input keys** are declared on the adapter (the adapter-description SPI, task 197's catalog): each with a namespace. That declaration is also what makes `list_sources` and the human-supplied-key fallback (option (c)) work.

## 8. Boundaries

1. **Nothing reaches `mcp` unscrubbed.** The resolver sits in `orchestration`/`core`, outside `mcp`. Its output is consumed by the fetch path and goes through the same scrub and validate steps as a direct read. The `ResolutionContext` is deliberately narrow. A new architecture rule forbids `mcp` and `connectors` classes from depending on `KeyResolver`.
2. **Boundary 5 (re-identification).** A resolver is not given, and must not call, `ScopeIdentityIndex.subjectFor`. The module providing resolvers must not depend on `data-prism-reidentification`. A resolver translating a pseudonym back to a real key would be exactly the re-identification path this boundary closes; the SPI takes real keys only because the server, not the model, calls it with raw keys obtained from sources.
3. **Boundary 6 (Hazelcast).** No resolver result is cached in Hazelcast. A resolver implementation that caches internally is the operator's code; the project's reference implementations (if any) cache nothing. The read-budget counter is an integer and already allowed.
4. **Boundary 7 (no sensitive value in logs, audit, exceptions).** Resolver input and output keys are never logged or audited, nor are they in `KeyResolutionException` messages. `resolver:<name>` is a name validated against a pattern. Metrics may label by resolver name and outcome only.

Also: fail closed (rule 2) in every row of the failure table; real source names and resolver names reach the client only under D-RM-10 and then only source names, never resolver names.

## Test obligations for 0.8 and 0.9

Mutation-testable; PIT gates link checks in 0.9.

1. Exact namespace match: `parcel-ref` does not match `Parcel-Ref`, `parcel-ref ` (trailing space), `parcel` or `parcel-ref-2`. Mutants: ignore case, trim, `startsWith`.
2. A link toward a source is direct when namespaces match and translated only through a declared resolver; with no resolver there is no translated link; with two resolvers chained (`A to B`, `B to C`) there is no link from A toward a source of C.
3. Direct preferred over translated for the same source.
4. `chainable` on a non-identifier, unclassified or `@NonSensitive` field refuses startup. A descriptor cannot remove a chainable flag or change its namespace.
5. A chainable field's value is never in output: with the flag set the disposition is unchanged (existing scrubbing tests are re-run with the flag set and produce identical output).
6. Fail closed: for each exception type, timeout, blank key, over-long key, over-count result and missing bean, the outcome is `KEY_UNRESOLVED` or `LINK_INVALID` as the table says and no target read happens. Mutants: catch and continue with the untranslated key; truncate over-count instead of refusing.
7. Budget: a resolver call and each target read are charged on both budget implementations; an unaffordable call is not made. Mutant: charge after the call, or not at all.
8. One-to-many: the number of target reads never exceeds `max-targets`; order is deterministic; `itemsOmitted` is correct.
9. Leak tests: resolver input and output values do not appear in any audit record, log line, metric, exception message or response (plaintext and encoded forms), including on failure. A resolver that throws an exception whose message contains the key must not leak it.
10. Architecture rules: `mcp` and `connectors` do not depend on `KeyResolver`; no resolver depends on `data-prism-reidentification`.
11. `IdentityResolver` implementations from the existing test suites run unchanged; the adapter returns exactly the keys `expand` returns for the pair.
12. Duplicate and self-resolving pairs refuse startup.

## 9. Open decisions for the owner

| # | Question | Options | Recommendation |
|---|---|---|---|
| OD-R1 | One-to-many cap default | 5, 16 (matching the batch default, D-RM-7), or unbounded with paging | 5 for `follow`; larger sets belong to bulk follow and paging (task 198). Configurable with a ceiling |
| OD-R2 | Timing of resolution: at follow (proposed) or at issue | At follow: no I/O at issue, the link may later turn out empty. At issue: the agent sees only links that resolve, but every result costs resolver calls and a mapping is observable through the number of links | At follow. Issuing links is then cheap and leaks no mapping |
| OD-R3 | New per-source status for resolver failure | New `KEY_UNRESOLVED`; or reuse `FAILED` | New code, entered in task 200's register, because "the target failed" and "we could not form the key" differ for the agent's next step |
| OD-R4 | Where the namespace vocabulary lives | Free-form validated labels (proposed); or a registry declared once per deployment | Free-form with a startup check that every namespace used appears on at least one field and one input key. A typo otherwise silently yields no link |
| OD-R5 | Opt-in adapter from `IdentityResolver` | Provide it; or require a hand-written `KeyResolver` | Provide it, opt-in per pair, so no existing mapping is written twice |
| OD-R6 | Should resolvers be configured (a YAML list) or only beans | Beans only; or YAML with class names | Beans only. Class names in configuration widen the reviewed surface, which is the opposite of the project's reviewed-extension rule |

## 10. Proposed names (closing table)

None collides with an existing name: the commands used are recorded in the Outcome of task 191. Names for links, `follow` and `dataprism.links.*` are in `sealed-links.md`.

| Name | Kind | Proposed release |
|---|---|---|
| `KeyResolver` | SPI interface (core) | 0.8 |
| `KeyResolutionException` | SPI exception | 0.8 |
| `ResolutionContext` | SPI record | 0.8 |
| `@Chainable` | annotation | 0.8 |
| `chainable`, `keyNamespace` | model descriptor keys | 0.8 |
| `IdentityResolverKeyResolvers` | adapter class | 0.8 |
| `KEY_UNRESOLVED` | per-source status code | 0.8 (register in task 200) |
| `INVALID_CHAINABLE_FIELD` | startup refusal code | 0.8 |
| `DUPLICATE_KEY_RESOLVER` | startup refusal code | 0.8 |
| `INVALID_KEY_RESOLVER` | startup refusal code | 0.8 |
| `resolver:<name>` | audit disposition key | 0.8 |
| `resolver:<name>:ms` | audit disposition key | 0.8 |
| `dataprism.key-resolvers.max-targets` | configuration key (default 5) | 0.8 |
| `dataprism.key-resolvers.timeout` | configuration key (default `PT2S`) | 0.8 |
| `dataprism.key-resolvers.budget-cost` | configuration key (default 1) | 0.8 |
