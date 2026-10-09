# Sealed links: threat model, link contract and `follow`

Status: design for 0.8, reviewed externally in 0.9. Internal (`docs/plan/design/`, D-070-12 (a)); not published.
Task 191. Companion: `key-resolvers.md`. Nothing here is code, an SPI stub or a configuration property; every name is a proposal until the 0.8 task that builds it.

All examples use an invented neutral domain: a logistics estate with a parcel service, a depot service and a courier-roster service. No real system, organisation or data model is meant.

## Scope of this document

An agent calls `get_entity_context`, receives per-source results (D-070-1) and wants to ask a second source about the same entity. The identifier that second source needs (a parcel reference, a depot code) was never emitted: identifiers are removed by the privacy engine and the model sees only pseudonyms. A link is the server's way of letting the agent say "go there with the key you hold" without the agent ever holding the key. The link is therefore the one new object that carries authority across a call boundary, and the one place new cryptography enters a privacy path.

Decisions this design inherits and does not reopen: D-RM-4 (links only for fields marked `chainable: true`), D-RM-5 (exact namespace match; translation only through a declared resolver), D-RM-6 (lifetime configurable, default 30 minutes, may be set to the scope lifetime, never beyond the scope), D-RM-10 (real names only when scope setting and caller permission both allow), D-RM-12 (truncation at record boundaries with markers and next-page links), D-RM-14 (sealed, one step, built from adapter and resolver declarations), D-070-1 (per-source result shape) and D-070-2 (a `REFUSED` source is omitted).

Facts about the current code that this design rests on (checked 2026-10-09 on branch `plan/0.7.0`):

- `SecretKeyProvider.secret(keyId)` returns raw key bytes by id and throws on an unknown id; there is no derivation API. `MultiKeySecretKeyProvider` holds several keys at once; adding never displaces and `remove` is the only retirement (HISTORY, "S2a key rotation").
- The one existing keyed construct outside pseudonyms, `ParameterFingerprinter`, runs `HmacSHA256` under `secret(version.keyId())` over `scopeId|params|value` and truncates to 12 bytes. The synthetic-value key id is pinned per scope and is long-lived, because changing it changes every pseudonym.
- `ScopeResolver` computes a scope's expiry per request as `min(now + maxScopeLifetime, token expiry)`. A scope is `case:<caseId>`. There is no stored scope record: the same case id used again later is the same `scopeId` with the same pseudonyms. A scope has no fixed end instant that a stateless link could read; see OD-4.
- `RefusalCodes` accepts `[A-Z][A-Z0-9_]{0,63}`. Today a refusal reaches the model as the bare code and the audit decision is `DENY:<code>`.
- Per-source audit detail is carried as dispositions (`<source>:<refused>`, `merged:<refused>`). Their value grammar is closed: `AuditEvent.isValidAction` accepts a `PrivacyAction` name or `REFUSED`, and task 190 adds `REFUSED:<code>` (D-070-13). New values beyond those shapes change the published audit contract (see OD-13).

## 1. Assets, actors and trust boundaries

### Assets

| Asset | Why it matters |
|---|---|
| A1. The real chainable key (for example a parcel reference) | The thing the whole product keeps away from the model |
| A2. The link key material (derived from a `SecretKeyProvider` key) | Forging or opening links |
| A3. The authority a link carries | A link makes the server read a named source on the holder's behalf |
| A4. The existence and shape of relationships between sources | "Parcel P is also known to the depot service" is itself information |
| A5. The scope boundary | A link must never move data or authority between investigations |
| A6. Source read capacity and the read budget | Link volume is a cost lever |

### Actors

| Actor | Holds | Trusted to |
|---|---|---|
| Honest agent | Its session, links from its own results | Use links as intended |
| Prompt-injected agent | Same as honest agent, but its instructions are attacker-controlled | Nothing. It is the primary adversary. It can emit any string as a link and may be induced to follow, copy or exfiltrate links |
| Compromised MCP client | The caller's bearer token and everything the agent sees | Nothing beyond the caller's own authorisation |
| Second caller in the same scope | Own session; can see links if the agent pastes them to it | Same case, different principal |
| Caller in another scope | Valid session elsewhere | Nothing about this scope |
| Operator | Server configuration, audit files, logs | Operates; not trusted with real keys in the model path; has the link key's custody (see below) |
| Cluster member | Same configuration and key provider, shared Hazelcast | Same trust as any instance; cluster transport is task 192's |
| Anyone with LLM provider logs | Every byte the model saw: results, links, tool arguments | Nothing, and for an unbounded time |

### Trust boundaries

1. Client to server: everything in a `follow` argument is attacker-controlled bytes.
2. Server to model: anything inside a link is permanently outside the server's control once emitted (boundary 1 of `docs/architecture.md`; the provider-log actor is a permanent reader).
3. Server instance to server instance: links are valid on any instance holding the same link key, by design (stateless, cluster-safe).
4. Server to key provider: the link key is as sensitive as the pseudonymisation key.

### Security properties the design must give

- P1. Unforgeable: only the server can make a link that is accepted.
- P2. Unmodifiable: any bit change is refused.
- P3. Contained: a link is accepted only in the scope, for the caller (if bound), toward the target and relation it was issued for, before its expiry.
- P4. Non-revealing: the link tells a holder nothing it did not already hold, and its refusal tells a prober nothing about why.
- P5. Not an authorisation: following re-checks everything a fresh call would check.
- P6. Bounded: lifetime, count per response and attempt volume are capped.

## 2. Threats, mitigations and residual risk

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | Forgery: the agent (or an injected instruction) makes up a link | AEAD under a key the client never holds; 128-bit tag; any failure is the one uniform refusal (section 4) | A tag guess succeeds with probability 2^-128 per attempt; attempts are also capped (T7) |
| T2 | Tampering: edit expiry, target, relation, scope or key id | Every such field is in the AAD (section 3); changing one fails authentication | None beyond T1 |
| T3 | Replay across scope: link from investigation A used in B | `scopeId` in the AAD and in key derivation | Reuse of the same case id later is the same scope (OD-4). Bounded by link lifetime |
| T4 | Replay across caller: a second principal in the same scope uses a link | Caller binding in the AAD (OD-2 recommends binding). Authorisation is rechecked on follow regardless (P5) | If unbound, any principal in the scope that holds the string can use it; no more access than they already have |
| T5 | Replay across target or relation: a valid link used to read a different source | Target and relation in the AAD; target also stored in the sealed payload and compared | None beyond T1 |
| T6 | Replay after expiry or scope end | Expiry in the AAD and checked; effective expiry is the minimum of the configured lifetime, the scope's expiry at issue and the caller token's expiry (D-RM-6) | Within the lifetime a link is reusable any number of times: the design is stateless and keeps no used-link set (Hazelcast is forbidden for it, boundary 6). A used link can therefore be replayed by anyone who can submit it in-scope, at most up to lifetime and the read budget. Bounded by lifetime, budget and rate limit |
| T7 | Enumeration and refusal oracles, including timing | One refusal code and one text for every invalid link; all checks run before any outcome is chosen; audited reason is never returned; a per-principal attempt limiter that ignores honest staleness (`LINK_ATTEMPTS_EXHAUSTED`) | See "Oracle analysis" below |
| T8 | Link volume abuse: a record with thousands of chainable fields floods context, cost and logs | Per-response cap with a truncation marker (D-RM-12); links only for `chainable: true` fields (D-RM-4); one step only; link construction does no I/O | Marker reveals how many were omitted. Accepted |
| T9 | Key rotation and revocation | Dedicated link key id with rotation-aware verification (section 3); retiring a key invalidates its links at once, provided every member has dropped it and, for (a'), the key store has destroyed it | There is no per-link revocation. The way to revoke everything is to remove the key; the way to revoke one scope's links is to wait for expiry (OD-4) |
| T10 | A stateless link carries a real identifier into model context and provider logs; key compromise later decrypts every link ever issued | Depends on the option chosen. See "The real-identifier-in-link question" | Option dependent. This is OD-1 |
| T11 | Prompt-injected agent exfiltrates links to a third party | A link is useless outside its scope and, if bound, its caller; follow requires a live authenticated session | An attacker with the session token and the link has what the agent had. Out of this design's reach |
| T12 | Operator reads audit or logs and recovers keys | Neither the audit nor logs carry the link, the plaintext payload or key bytes; the audit carries a short `linkId` | An operator with key custody can forge links. Same trust as the pseudonymisation key |
| T13 | Cluster member with the key forges for any scope | Per-scope derived keys narrow nothing against a member holding the master. Same as the accepted consequence on `SecretKeyProvider` | Accepted; task 192 for the transport |
| T14 | Link reveals relationships (A4) to a reader of the model context | Link text is opaque; the target label is what the client already sees (alias or real name per D-RM-10); `_links` only exists for sources the caller may see (D-RM-11) | The count and relation of links on a result is visible to the holder |
| T15 | Decompression/oversize or parsing attack on `follow` input | Maximum length checked before any decoding; fixed-layout binary; no recursion; no deserialisation of untrusted classes | None expected |
| T16 | Link built from a field value the client controls (injection through a source record) | Payload built from a typed binary layout, with lengths; the chainable value is never concatenated into a delimited string | None expected |
| T17 | Length leak: under (a) and (a') the sealed key's length reveals something about the real key | Pad the sealed key to a multiple of 64 bytes, at most 256 (section 3); a key longer than 256 bytes gets no link | The bucket and the number of links per result are visible to the model and its provider. Accepted if (a) or (a') is chosen |

### Oracle analysis

What a prober may learn from a refusal: nothing except that the call was refused. To make that hold:

- One code (`LINK_INVALID`) and one client text for malformed, oversized, wrong version, unknown key id, failed authentication, expired, wrong scope, wrong caller, wrong target, source not permitted, relation no longer declared, and stale origin (drift).
- The checks do not short-circuit on a secret-dependent condition: after the structural parse (which depends only on public layout), the server always performs the AEAD verification (against the key id named, or a fixed dummy key if the id is unknown), then evaluates expiry, authorisation and declarations as booleans, and combines them. A different early exit between "expired" and "forged" would be a timing oracle. Tag comparison is the JDK's constant-time AEAD verification; no hand-written comparison is allowed.
- A structural failure (too long, not base64url, wrong version byte) is cheaper than a cryptographic one. That difference reveals only public layout rules, not key-dependent facts, and is accepted. The length check runs first for exactly that reason, to cap work.
- The response body, size, status and the audit write happen for every outcome. The audit write is asynchronous or constant-cost relative to the outcome class.
- After authentication succeeds, the checks that can distinguish "valid but not allowed" from "invalid" (source no longer permitted for this caller, relation no longer declared, expired) also return `LINK_INVALID`. A prober cannot reach this point without a valid link, so what these outcomes would reveal is only revealed to a holder of a real link, who already knows the target's label. They are still uniform, to give nothing to a replaying holder whose permissions changed.
- Downstream status after a valid link (target `TIMED_OUT`, `NO_DATA`, `ANSWERED`) is not hidden: it is the answer. It is reachable only with a valid link.
- The attempt limiter (section 4) counts only failures that are decided before the token authenticates, per principal within a scope, over a fixed window. After `invalid-attempt-limit` such failures, that principal's `follow` calls in that scope are refused with `LINK_ATTEMPTS_EXHAUSTED` until the window ends. The code says only "too many failed attempts", reveals no link property, affects no other principal, and its counter holds no sensitive value (boundary 6).

### The real-identifier-in-link question

The mechanism that makes a link work must give the server, at `follow` time, the real key of the chained field (or a way to re-obtain it). Four designs are compared. The owner decides (OD-1). The comparison states the costs of every option, including those that count against the option this document leans toward.

**Option (a): ciphertext of the real key inside the link.** The payload carries the chained key (for example the parcel reference) encrypted under the link key, padded to a fixed size bucket (section 3). Stateless; one read of the target at follow time; the cost of a hop does not depend on how many hops came before; the format does not limit trace length.

**Option (a'): (a) with a dedicated, short-lived link key.** Same payload. The link key id is separate from the pseudonymisation key id and is rotated at least every `lifetime`; retired keys are removed from the provider. The benefit exists only if the provider's backing store destroys a retired key. `MultiKeySecretKeyProvider.remove` only drops the key from memory; a key that remains in the vault, a backup, an environment file or a secrets-manager version history can still be fetched after a compromise and still opens old links. (a') therefore depends on an operator procedure (destroy, not just remove) that this project cannot enforce or test, only document and ask the reviewer to check.

**Option (b): ciphertext of a scope-local reference ("origin reference"), resolved server-side without Hazelcast.** The payload carries no chained key. It carries how to obtain it again: the origin source label, the request that produced the result the link sits on, the field path, and a keyed fingerprint of the chained value. At follow time the server re-reads the origin (through the normal fetch path, scrubbed and budgeted), locates the record whose chainable field has that fingerprint, takes the real key from the raw record in memory, and reads the target. The only real identifier in any payload is the root input the caller itself supplied in its own tool argument, which the model context and provider logs already hold in clear.

What (b) costs, stated plainly:

- **Trace length is capped.** A link on the result of a followed link must carry the whole chain of origins, because the agent never holds a key to start again from. The chain is capped by `max-chain-depth` (default 3, ceiling 4, bounded by the size arithmetic in section 3). A trace needing more hops than the cap cannot be followed by links; a human has to supply a key (option (c) from that point).
- **Reads grow quadratically.** A follow at depth d re-reads d origins and then reads the target: d + 1 source reads. An n-hop trace costs 2 + 3 + ... + (n + 1) = n(n + 3) / 2 reads against n for (a): 9 against 3 at three hops, 14 against 4 at four hops (the ceiling). Each is charged (OD-5), and a translated hop adds a resolver call per re-read of that hop. With hundreds of sources and an agent following many links per hop, this multiplies the read budget an investigation uses.
- **Refusal by drift compounds.** Every re-read must still find a record whose chainable field matches the recorded fingerprint. If one origin re-read fails with probability p (a key was updated or deleted, a resolver answers differently; mere reordering is not drift), a link at depth d fails with probability about 1 - (1 - p)^d. The agent sees only `LINK_INVALID`, indistinguishable from forgery, and must repeat the origin call. For a "where did this record stop propagating" investigation, a record that is changing in flight is the usual case.
- **A one-to-many parent must record which child it came from.** When a translated follow returns several items, a link on item k must say which of the parent's translated keys it stems from. Under (b) the child's origin records the parent link's payload plus the keyed fingerprint of that item's translated key; re-deriving it means re-reading the grand-origin, calling the resolver again and selecting the key whose fingerprint matches. A resolver that returns a different set, or a different capped prefix, causes drift. Under (a) the item's real translated key is sealed into the child link.
- Moving parts: origin re-fetch, fingerprints, depth cap, drift handling.

What (a) and (a') cost, stated plainly:

- **Every chained key of every result is emitted once more, as ciphertext, into model context and provider logs.** If the link key is later disclosed, every link issued under it opens, revealing the real keys of the sources and, from target and relation, the relationships between them.
- **Length leaks (T17).** AEAD does not hide plaintext length. Padding to a bucket hides the exact length but not the bucket, and the number and shape of links on a result is visible regardless.
- **Key discipline is outside the product.** (a') relies on destruction of retired keys, as above.
- A defect in the sealer or the leak validator is higher-impact: under (a) the validator cannot look inside the ciphertext (section 6, boundary 1).

**Option (c): chain only from keys a human supplies (the roadmap fallback).** No `_links`. `list_sources` and `describe_source` tell the agent each source's input namespaces; a human gives the agent the identifier for the next question. No new cryptography and no new authority object. It cannot deliver the 1.0 purpose of an agent tracing a record unaided.

| Criterion | (a) | (a') | (b) | (c) |
|---|---|---|---|---|
| New real-identifier ciphertext in model context and provider logs | Yes, every chained key of every result | Yes, same | No. Only the root input already sent in clear | None |
| Effect of later key compromise | Every link ever issued under that key opens | Only links under keys the provider has not destroyed; none of the retired ones if truly destroyed, which the product cannot verify | Opens the root request and structural facts (labels, field paths); no new real identifier | Not applicable |
| Length of the sealed key leaks | Yes, to a bucket (T17) | Yes, to a bucket | No chained key in the payload | None |
| Maximum trace length by links | Not limited by the format (links are self-contained; lifetime limits it) | Same | `max-chain-depth` (default 3, ceiling 4) | Not applicable; a human at every hop |
| Source reads for an n-hop trace | n | n | n(n + 3) / 2 | n |
| Link fails because data moved | Only if the target no longer holds the sealed key | Same | Compounds per hop, about 1 - (1 - p)^d, uniform with forgery | Not applicable |
| One-to-many parent | The item's key is sealed in the child link | Same | Child records parent link plus item fingerprint; resolver called again on each re-read | Not applicable |
| Statelessness and cluster safety (D-RM-14) | Yes | Yes, plus a key-rotation procedure on every member | Yes | Yes |
| Hazelcast use (boundary 6) | None | None | None | None |
| Raw value in process memory | During the follow | Same | During each origin re-read and the follow; never stored | During the follow |
| Boundary 1: can the validator check links for raw values | No, opaque ciphertext | No | Yes, the payload holds no chained key | Fully inside the existing validator |
| "Never sees a real identifier" promise in `roadmap.md` | Weakened by ciphertext of one | Weakened, time-limited | Holds for chained values | Holds |
| Delivers the 1.0 purpose (agent tracing a record across many services) | Yes | Yes | Yes within the depth cap, at quadratic cost | No |
| Cost of a sealer mistake | High: leaks real keys | High, narrower in time | Low: leaks only the root request | None |

Evidence for the compromise claim: the accepted consequence recorded on `SecretKeyProvider` is that a memory disclosure exposes the key (`docs/architecture.md`, decisions; the Javadoc), and model providers keep logs for periods the operator does not control, so ciphertext emitted today can be read after a later compromise. The pseudonymisation key cannot be rotated away to fix this, because pseudonyms must stay stable for an investigation's life (HISTORY, "S2 configurable multi-locale name pools"), which is why (a') needs its own key id. Evidence for the (b) claim: every real identifier inside any (b) payload was previously sent to the server by the same caller in clear in a tool argument (`subjectId`); the chained value is never in a payload, only its fingerprint, and a keyed fingerprint is not reversible without the key and a candidate list. The read arithmetic above is exact; the drift probability is a stated model, not a measurement, and the load harness (task 196) and the pilot should measure p before the 0.9 freeze.

Weak points of (b) beyond cost: the fingerprint is a guessing oracle for whoever holds the key and a candidate list; a stored origin request that includes free-text search arguments (after 1.0) must carry the same fingerprint-only form the audit uses.

**Recommendation (for the owner to accept or overrule). This replaces the first draft's recommendation of (b).** The first draft led with (b) because it adds no new real-identifier ciphertext. Re-derived against the 1.0 purpose, an agent following a record across many services unaided, (b) caps the length of a trace at `max-chain-depth` hops, makes a trace's read cost quadratic, and turns ordinary data movement into compounding uniform refusals; those are failures of the purpose, not tuning matters. The recommendation is now **(a') with fixed-size padding**: a dedicated link key id, rotated at least every `lifetime`, retired keys destroyed in the key store (not only removed from memory), and the residual risks T10 and T17 accepted in writing. If the owner weighs "no new real-identifier ciphertext in provider logs" above long unaided traces, (b) is the right choice and the depth cap is then a published product limit. If the owner accepts neither residual, (c) is the fallback and is what `dataprism.links.enabled=false` gives. Two facts the owner can still get cheaply: the typical and longest trace length in the pilot, and p from the load harness.

## 3. Link format

### Algorithm

AES-256-GCM, from the JDK (`javax.crypto.Cipher`, `AES/GCM/NoPadding`), 96-bit random nonce, 128-bit tag. Reasons: authenticated encryption with associated data is exactly the required primitive; it is in the JDK 21 baseline, so no dependency is added; hardware support makes it cheap at thousands of links per investigation; ChaCha20-Poly1305 is also in the JDK and is the documented alternative if a platform lacks AES acceleration. AES-GCM-SIV is not in the JDK and would add a dependency, so it is not proposed. Random 96-bit nonces are safe up to about 2^32 messages per key; the per-scope derived key and the rotation of the link key keep the count per key far below that (a response carries at most `max-per-response` links).

Under option (b) the encryption protects structure and fingerprints rather than a real key; the AEAD is still required for integrity, so the choice of algorithm does not depend on OD-1.

### Key derivation

The link key id is `dataprism.links.key-id` (a `SecretKeyProvider` id, distinct from `dataprism.privacy.hmac-key.key-id`; startup refuses a shared id). The key material must be at least 32 bytes; a shorter key refuses startup.

Derivation is HKDF-SHA-256 (RFC 5869), written out so there is nothing to interpret:

1. Extract: `PRK = HMAC-SHA256(salt, secret(linkKeyId))`, with `salt` = 32 zero bytes (the RFC's default of HashLen zeros). A fixed salt is acceptable because the input is already a high-entropy key of at least 32 bytes, not a password; the salt adds nothing here, and the labels below give domain separation.
2. Expand: `OKM = T(1) = HMAC-SHA256(PRK, info || 0x01)`, 32 bytes, one block, so no counter loop. `info` is the length-prefixed concatenation of the purpose label (which carries the format version) and the scope id.

| Purpose | Label | Used for |
|---|---|---|
| Sealing | `dataprism/link/v1/seal` | AES-256 key for the AEAD |
| Fingerprinting | `dataprism/link/v1/fingerprint` | HMAC key for the chained-value fingerprint (option b) and for the attempt-counter key (section 4) |
| Caller binding | `dataprism/link/v1/caller` | HMAC key for the caller tag in the AAD |

Different labels mean the sealing key and the fingerprint key never coincide. The scope id in `info` means a key derived for one scope is not the key for another, which narrows what a leak of one derived key gives (not what a leak of the master gives). The JDK 21 baseline has no public HKDF API (`javax.crypto.KDF` arrives in Java 24), so 0.8 implements the two steps over `HmacSHA256` and tests them against the RFC 5869 vectors (OD-6).

Rotation: a token names its key id (see layout). Verification looks up exactly that id with `SecretKeyProvider.secret`, which throws on an unknown id and must not fall back to another key (the trap recorded in HISTORY S2a). Issuance always uses the current `key-id`. Rotation is: add the new key, change `key-id`, keep the old key until the maximum link lifetime (plus `clock-skew`) has passed, then retire it. With `lifetime=scope` the maximum lifetime is the maximum scope lifetime, so the key rotates at most that often and the exposure window under (a') widens accordingly (OD-15). After retirement its links are refused as `LINK_INVALID` with audit reason `UNKNOWN_KEY`. Retiring from the provider's memory (`MultiKeySecretKeyProvider.remove`) is not destruction; whether a retired key is also gone from the key store is the operator's procedure and decides whether option (a') delivers its benefit.

### Layout

Base64url without padding of a binary layout, fixed order, all lengths explicit. Cleartext header:

| Field | Size (bytes) | Notes |
|---|---|---|
| version | 1 | `0x01`. Any other value is refused before cryptography |
| key id length, key id | 1 + up to 16 | ASCII `[A-Za-z0-9_-]`, not sensitive |
| expiry | 8 | epoch seconds; also in the AAD |
| target length, target | 1 + up to 64 | the label the client sees in this response (alias or real name, D-RM-10); also in the AAD |
| relation | 1 | a code, `0x00` direct, `0x01` translated; also in the AAD |
| nonce | 12 | random |
| tag | 16 | at the end of the ciphertext |

The header is at most 1 + 17 + 8 + 65 + 1 + 12 + 16 = 120 bytes. The target must be cleartext because the AAD has to be known before decryption and the client already sees that label. The relation is cleartext for the same reason, but it is only a one-byte code: the namespaces and resolver name, which are operator-chosen labels that could resemble real system names, are inside the encrypted payload and never in cleartext. All label lengths are enforced at startup (namespaces and resolver names at most 32 bytes, source labels at most 64).

Associated data is the length-prefixed concatenation of: version, key id, expiry, target, relation, `scopeId` (from the session, never from the token), and the caller tag. Because scope and caller come from the authenticated session at verification time, a token replayed in another scope or by another caller fails authentication rather than relying on a comparison the code could forget.

Sealed payload:

| Field | Size (bytes) | (a), (a') | (b) |
|---|---|---|---|
| link id | 8 | random, for audit correlation | same |
| issue time | 8 | yes | yes |
| depth | 1 | 0 | number of origin levels, 1 or more |
| key namespace | 1 + up to 32 | yes | yes |
| resolver name (translated only) | 1 + up to 32 | yes | yes |
| chained key | 2 + 64k, at most 258 | the real key, zero-padded to a multiple of 64 bytes (k = 1 to 4); a key over 256 bytes gets no link and is counted as omitted | absent |
| origin, one record per level | at most 155 each | absent | origin source label (1 + 64), field path (1 + 32), fingerprint of the chained value (12), and for a translated level the resolver name (1 + 32) and fingerprint of the translated key (12) |
| root request | at most 291 | absent | entity type (1 + 32), `subjectId` (2 + at most 256), as the caller sent it |

The length prefix of the chained key is inside the ciphertext, so padding hides the exact length within a 64-byte bucket. It does not hide the bucket (T17).

Binding the caller: the AAD carries a caller tag, `HMAC(callerKey, principalId)` truncated to 16 bytes, not the principal in the clear. Whether to bind is OD-2. When unbound the tag is 16 zero bytes.

The target source is not repeated in the payload. The label in the header is authenticated by the AAD and mapped back to the source by the server's per-scope alias table.

### Size arithmetic and defaults

Worst case for option (a) and (a'): header 120 + payload (8 + 8 + 1 + 33 + 33 + 258 = 341) = 461 bytes, which is 616 characters.

Worst case for option (b) with d origin levels: header 120 + fixed payload (8 + 8 + 1 + 33 + 33 = 83) + root request 291 + 155 d = 494 + 155 d bytes. Base64url length is 4 x ceil(bytes / 3).

| d (hops allowed) | Bytes | Characters |
|---|---|---|
| 1 | 649 | 868 |
| 2 | 804 | 1072 |
| 3 | 959 | 1280 |
| 4 | 1114 | 1488 |
| 5 | 1269 | 1692 |

(b) at one level already exceeds 768 characters, so a 768 default could not hold even one hop. The defaults are therefore: `max-length` 1536 characters (1152 bytes), `max-chain-depth` 3 (1280 characters), ceiling 4 (1488). Depth 5 does not fit 1536 and would need `max-length` raised. Under (b) startup applies the formula: if `4 x ceil((494 + 155 x max-chain-depth) / 3)` exceeds `max-length`, startup refuses with `INVALID_LINK_LIMIT`. Typical links are far smaller than the worst case (short labels, short keys: about 200 to 300 bytes under (a), 300 to 450 under (b) at depth 1), but the cap and the schema use the worst case. The worst case is also why links count toward the response-size cap (task 193): 50 links at 1536 characters is 77 KB in the extreme.

Maximum encoded length is checked before any decoding. If the sealer would produce a link longer than `max-length`, the link is not issued and counts as omitted.

### Expiry and clock skew

Expiry at issue: `min(now + lifetime, scope.expiresAt, caller token expiry)`. `lifetime` is a duration or the word `scope`, meaning up to the scope's current expiry. It is never beyond the scope (D-RM-6).

Members of a cluster have clocks that differ slightly. `dataprism.links.clock-skew` (default `PT5S`, at most `PT30S`) is the tolerance: a link is treated as expired when `now >= expiry + clock-skew`, and a link whose issue time is later than `now + clock-skew` is invalid (audit reason `ISSUED_IN_FUTURE`). The tolerance applies to link expiry only. It never extends the scope or the caller's token, which the live session at `follow` time checks independently, so skew cannot make a link usable beyond the scope.

## 4. Uniform refusal, audit, attempt limiter and what the client never learns

Client sees, for every invalid link, the existing refusal result shape with:

- code `LINK_INVALID`;
- the same fixed text for every reason ("The link is not valid.");
- the usual `correlationId`.

The client never learns: which check failed; whether the link was once valid; whether it was expired, forged, for another scope, caller or target; whether the target exists or is permitted; whether the origin drifted; which key id it names.

The decision is `DENY:LINK_INVALID`. The real reason must be recorded somewhere in the audit, and the plan so far put it in the per-source disposition map. That map's grammar does not allow it today: `AuditEvent` accepts a disposition value only if it is a `PrivacyAction` name or `REFUSED` (task 190 / D-070-13 adds `REFUSED:<code>`). The values proposed in earlier text (`INVALID:<reason>`, counts, hex ids, milliseconds) therefore **require widening that grammar**, which changes the published `docs/audit.md` contract and the verifier's validation. Which way to go is OD-13. The table states the information to record and the shape it takes under the recommendation of OD-13 (shape C, `REFUSED:<code>`); the alternatives are in OD-13.

| Disposition key | Information | Shape under OD-13 shape C |
|---|---|---|
| `link` | The real reason, from a closed set: `MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED`, `EXPIRED`, `ISSUED_IN_FUTURE`, `TARGET_NOT_PERMITTED`, `RELATION_UNDECLARED`, `ORIGIN_DRIFT`, `DEPTH_EXCEEDED`, `PAYLOAD_MISMATCH` | `REFUSED:LINK_<REASON>`, for example `REFUSED:LINK_AUTH_FAILED` |
| `link:id` | The link's 16 hex-character id, only when the link authenticated | Not recorded under C (a metric-free loss: issue and use are joined by `correlationId` of each call only) |
| `link:attempts` | The principal's counted failures in the window | Not recorded under C; exposed as a metric without a principal label |

`AUTH_FAILED` covers forgery, tampering, wrong scope, wrong caller and wrong target, because the AEAD cannot say which input differed. That is a deliberate loss of operator diagnostics in exchange for no oracle (OD-3).

On the issuing call the audit would gain `links:issued` and `links:omitted` (counts); under shape C they are metrics, not dispositions. Resolvers are not called at issue, so there is no resolver entry there (see `key-resolvers.md`). A successful follow audits `ALLOW` and the target as `name:status` like any source. A refused one-to-many item is audited `REFUSED:<code>` per D-070-13, under the key `<target>[<i>]:<refused>` where `<i>` is the item's position in the sorted order.

Never in audit, logs, metrics or exceptions: the token, the sealed payload, the chained value, the fingerprint, any key. A rejected token is not echoed even truncated. `PiiLogScanTest` and `AuditFilePiiScanTest` extend to cover it (section 7).

### Attempt limiter

Purpose: stop a prompt-injected agent from grinding forged links. Properties required:

- It counts only failures decided before the token authenticates: `MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY` and `AUTH_FAILED`. These are the failures a forger produces. An honest link naming a retired key has expired anyway under the rotation rule in section 3, so counting `UNKNOWN_KEY` does not penalise honest use.
- Classification follows the authentication step. A token that fails authentication is always recorded and counted as `AUTH_FAILED`, whatever its cleartext header says: a forged token that names a target the caller cannot see, an expiry in the past or a relation that is not declared is `AUTH_FAILED`, not `TARGET_NOT_PERMITTED`, `EXPIRED` or `RELATION_UNDECLARED`. Those, together with `ORIGIN_DRIFT`, `ISSUED_IN_FUTURE`, `PAYLOAD_MISMATCH` and `DEPTH_EXCEEDED`, are classified only after authentication succeeds, so they are reachable only with a genuine link, are never counted, and give a prober no information (a prober cannot reach them). Counting them would only lock out honest users, for example on clock skew between members.
- It counts per principal within a scope, so one principal cannot lock the others out. The counter key is `HMAC(fingerprintKey, scopeId | principalId)` truncated and hex-encoded, so the map holds neither a case id nor a principal id (boundary 6).
- The window is fixed and does not depend on a stored scope end, which does not exist: the counter entry is created at the first counted failure with a time-to-live of `dataprism.links.invalid-attempt-window` (default `PT10M`) and increments do not extend it. In a single-node deployment the same semantics hold in process.
- When the count reaches `invalid-attempt-limit` (default 20), every `follow` by that principal in that scope is refused with `LINK_ATTEMPTS_EXHAUSTED` until the entry expires, including calls carrying a valid link. A lock that still accepted valid links would tell the prober which links are valid.
- The counter is cluster-wide on a shared topology (an integer under a keyed-hash key). Whether to also set an aggregate per-scope ceiling, the limit and window values, and whether to lock at all or only count and audit, are OD-8.

## 5. The `follow` tool and `_links`

### `follow`

Input schema (draft 2020-12). The caller supplies nothing but the link: scope, principal, purpose and case come from the session (rule 4; `ReservedArguments` strips them).

```json
{
  "type": "object",
  "properties": {
    "link": { "type": "string", "minLength": 1, "maxLength": 1536, "pattern": "^[A-Za-z0-9_-]+$" }
  },
  "required": ["link"],
  "additionalProperties": false
}
```

`maxLength` follows `dataprism.links.max-length`; the served schema is generated from the configured value. A client that ignores the schema gets the same `LINK_INVALID`.

Output: the same envelope as `get_entity_context` (D-070-1), with exactly one entry in `sources`, keyed by the target label. A one-to-many translation (see `key-resolvers.md`) returns `items` instead of `entity`.

```json
{
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "sources": {
      "type": "object",
      "minProperties": 1, "maxProperties": 1,
      "additionalProperties": { "$ref": "#/$defs/sourceResult" }
    }
  },
  "required": ["correlationId", "sources"],
  "$defs": {
    "status": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
    "sourceResult": {
      "type": "object",
      "properties": {
        "status": { "$ref": "#/$defs/status" },
        "entity": { "type": "object" },
        "items": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": { "status": { "$ref": "#/$defs/status" }, "entity": { "type": "object" }, "_links": { "type": "array", "items": { "$ref": "#/$defs/link" } }, "_linksOmitted": { "type": "integer", "minimum": 1 } },
            "required": ["status"]
          }
        },
        "itemsTruncated": { "const": true },
        "_links": { "type": "array", "items": { "$ref": "#/$defs/link" } },
        "_linksOmitted": { "type": "integer", "minimum": 1 }
      },
      "required": ["status"]
    },
    "link": {
      "type": "object",
      "properties": {
        "rel": { "enum": ["direct", "translated"] },
        "target": { "type": "string" },
        "field": { "type": "string" },
        "namespace": { "type": "string" },
        "toNamespace": { "type": "string" },
        "link": { "type": "string", "maxLength": 1536 },
        "expiresAt": { "type": "string", "format": "date-time" }
      },
      "required": ["rel", "target", "field", "link", "expiresAt"]
    }
  }
}
```

An invalid link returns the refusal result, not this shape. `entity` is present only for `ANSWERED` items or sources (D-070-1). `items` appears only for a one-to-many translation; a source result has either `entity` or `items`, never both. Since a resolver cannot always say how many keys it left out, truncation is a flag, `itemsTruncated`, not a count.

Outer status and items of a one-to-many follow (decisions on the rule itself are OD-9):

- An item whose read is `REFUSED` is omitted from `items` (D-070-2). If every item that answered is refused (items that returned `NO_DATA` or failed did not answer), the whole call is refused with the first refused item's code, exactly as D-070-2 says for sources; otherwise the call is returned with the refused items omitted. This aligns items with D-070-2; OD-9 records it.
- The source's `status` is `ANSWERED` if at least one item is `ANSWERED`. Otherwise it is `NO_DATA` if the resolver returned no keys or every item is `NO_DATA`; otherwise the common status if all items share one; otherwise `FAILED`.
- `items` follow the deterministic order defined in `key-resolvers.md` (ascending keyed fingerprint of the translated key), so output order discloses nothing about real keys.
- If the read budget runs out part-way, the items already read are returned, the remaining keys are not attempted, and `itemsTruncated` is `true`. If it is exhausted before the first read the call is refused as today (`SCOPE_READ_BUDGET`). A deadline cut-off behaves the same way, with the unread keys covered by `itemsTruncated`.

A bulk or status-only form is task 198's.

### `_links` on a per-source result

`_links` sits next to `entity` inside a source's result (the shape from task 190):

```json
{
  "sources": {
    "parcel-service": {
      "status": "ANSWERED",
      "entity": { "parcel": "PARCEL-7Q2K4M", "state": "in transit" },
      "_links": [
        { "rel": "direct", "target": "depot-service", "field": "parcel",
          "namespace": "parcel-ref", "link": "AQ...", "expiresAt": "2026-10-09T14:30:00Z" },
        { "rel": "translated", "target": "courier-roster", "field": "parcel",
          "namespace": "parcel-ref", "toNamespace": "route-code",
          "link": "AQ...", "expiresAt": "2026-10-09T14:30:00Z" }
      ],
      "_linksOmitted": 12
    }
  }
}
```

Rules:

1. Links exist only for fields marked `chainable: true` (D-RM-4), only toward sources the caller is permitted to see (D-RM-11) and whose declared input namespace matches the field's exactly (`direct`) or is reachable through one declared resolver (`translated`); never two resolvers in a row (D-RM-5, D-RM-14).
2. **A link identifies exactly one key.** `field` is the path of the chainable value in the record as the client sees it. For a scalar field it is the field name. For an element of a collection, whether the field is a list of keys or a list of records with a chainable field inside, there is one link per distinct element key, with an indexed path such as `depots[2]` or `routes[1].depot`. The same key at several indices of one source result, toward the same target, is collapsed by fingerprint into one link at its first index in path order, before the cap (OD-14). What a link carries: under (a) and (a') the element's real key; under (b) the origin source, the path including the index, and the fingerprint of that element's key. A single link standing for a whole collection is not issued (OD-10). Because only the key is chainable, the `field` path names where the value was, not the value; a chainable field that is dropped entirely from output still has its declared model name as `field`, unless that name would itself be sensitive, in which case the `<undeclared-N>` rule applies and no link is issued (fail closed).
3. A source whose status is not `ANSWERED` has no `_links` and no `_linksOmitted`. A `REFUSED` source (D-070-2) is omitted entirely and so is any link it would have produced.
4. Links are grouped by `rel`, then by target in ascending order, then by `field`, so output is deterministic (task 179's ordering rule).
5. Cap: `dataprism.links.max-per-response` (default 50) across the whole response. Selection under the cap takes links round-robin across targets in order, so one relation cannot starve the others. Each affected source carries `_linksOmitted` with the number dropped from it (D-RM-12). The marker states a count only, never what was dropped. Both the round-robin rule and the default are OD-11.
6. A link whose sealed encoding would exceed `max-length`, or whose key exceeds 256 bytes under (a) and (a'), is not issued and counts as omitted.
7. The leak validators run on the response with links present. A token is exempt from the key-shape scan by construction only if produced by the sealer (a typed `SealedLink` value); a token-shaped string that is not one fails validation (boundary 1).

### Configuration keys

All proposals. Unknown keys under `dataprism.links` are startup errors like every group.

| Key | Default | Validation, and what a bad value does at startup |
|---|---|---|
| `dataprism.links.enabled` | `false` | With `false`, no `_links`, and `follow` is not registered; the product behaves as option (c) |
| `dataprism.links.key-id` | none | Required when enabled. Unresolvable: `LINK_KEY_UNRESOLVED`. Shorter than 32 bytes: `LINK_KEY_WEAK`. Same id as `dataprism.privacy.hmac-key.key-id`: `LINK_KEY_SHARED` |
| `dataprism.links.lifetime` | `PT30M` | Positive duration, or `scope`; otherwise `INVALID_LINK_LIFETIME`. Never effective beyond the scope (D-RM-6) |
| `dataprism.links.max-per-response` | `50` | 1 to 500; otherwise `INVALID_LINK_LIMIT` |
| `dataprism.links.max-length` | `1536` | At most 4096. Floor depends on the option: 616 under (a) and (a'), the worst case of (a) in section 3; under (b) the formula `4 x ceil((494 + 155 x max-chain-depth) / 3)` (868 at depth 1). A value below the floor of the chosen option is `INVALID_LINK_LIMIT` |
| `dataprism.links.bind-caller` | `true` | Boolean (OD-2) |
| `dataprism.links.invalid-attempt-limit` | `20` | Positive; otherwise `INVALID_LINK_LIMIT` |
| `dataprism.links.invalid-attempt-window` | `PT10M` | Positive duration; otherwise `INVALID_LINK_LIMIT` |
| `dataprism.links.clock-skew` | `PT5S` | 0 to `PT30S`; otherwise `INVALID_LINK_LIMIT` |
| `dataprism.links.max-chain-depth` | `3` | 1 to 4; used only under option (b); otherwise `INVALID_LINK_LIMIT` |

## 6. Boundaries

1. **Nothing reaches `mcp` without passing the privacy engine.** The link builder runs in `orchestration`, on the raw record, and returns only a sealed value typed so that the mapper can emit it and nothing else carries the chained value toward `mcp`. `mcp` receives tokens, never keys. No new class may build a mapper; the sealer needs none (binary layout, base64url). A new architecture rule must forbid any class in `mcp` or `connectors` from depending on the raw-key accessor of the link builder. Under (a) and (a') the real key leaves the engine as ciphertext and the validator cannot check it; this is the principal technical argument against them and is weighed in OD-1.
2. **Boundary 5 (re-identification).** A link is never a path to `ScopeIdentityIndex.subjectFor`, and the link module must not depend on `data-prism-reidentification`. The fingerprint of a chained value is not a pseudonym and is never written to the re-identification index. `follow` is an ordinary MCP tool with ordinary authorisation; it is never an operator tool.
3. **Boundary 6 (Hazelcast).** Links are not stored anywhere: no issued-link table, no used-link set, no cache of origin records. The only shared state is the failed-attempt counter, an integer under a keyed-hash key (no case id, no principal id) with a time-to-live; `HazelcastStoredValueBoundaryTest` gains the new map in its inventory and must still reject raw values.
4. **Boundary 7 (no sensitive value in log, metric, exception, audit).** No token, payload, chained value, fingerprint or key in any of them. Exceptions from the sealer carry a reason enum, never input. Metrics use the closed reason set as a label at most, never a link id.

Also: rule 4 (the caller never supplies scope, principal, purpose or case) is honoured because scope and caller come from the session and enter the AAD; rule 2 (fail closed) is honoured because every unresolvable, unknown or erroring path is `LINK_INVALID`.

## 7. Test obligations for 0.8 and 0.9

Mutation-testable: each line names a mutant that must be killed. PIT gates the link checks from 0.9 (roadmap).

1. Round trip: issue then follow in the same scope and caller succeeds.
2. Each AAD field has its own test that changes only that field and expects `LINK_INVALID`: version, key id, expiry, target, relation, scope, caller. Mutants: drop any field from the AAD.
3. Bit flip at every byte position of a token (parameterised over the whole token) is refused. Mutant: skip tag verification.
4. Expiry at `expiry + skew - 1 s`, `expiry + skew`, `expiry + skew + 1 s`: boundary exact; skew 0 and the 30 s ceiling both tested. Mutants: `<` for `<=`, drop the check, apply skew to scope or token expiry.
5. Effective expiry is the minimum of lifetime, scope expiry and token expiry; each of the three can be the binding one. Mutant: use `lifetime` alone. A link whose issue time is in the future beyond the skew is `ISSUED_IN_FUTURE`.
6. `lifetime=scope` never exceeds scope expiry.
7. Replay across scope, across caller (when bound), across target.
8. Unknown key id, retired key id, and a provider holding exactly one key asked for a different id: all refused, never a fallback (the S2a trap).
9. Uniformity: for every reason in the closed set, the client-visible result is byte-identical apart from `correlationId`; the audit reason differs. Mutant: return a different text or code for any reason.
10. Timing: a statistical soak test (not a flaky unit assertion) that valid-but-expired, authenticated-but-not-permitted and forged tokens are not separable beyond a stated tolerance.
11. Oversize input is refused before any decoding or cryptography. Mutant: reorder the length check after decoding.
12. Cap: with `max-per-response=N` and more chainable fields, exactly N links and the right `_linksOmitted`; deterministic order; round-robin fairness.
13. `REFUSED`, `NO_DATA`, `TIMED_OUT` and other non-`ANSWERED` sources carry no `_links`.
14. Fields not marked `chainable` never produce a link. Mutant: ignore the flag.
15. Exact namespace match: a near-miss namespace (case, whitespace, prefix) gives no direct link. Mutant: case-insensitive compare.
16. Translation only through a declared resolver; two resolvers in sequence never produce a link.
17. Leak tests: the chained value, in plaintext, base64, base64url, hex and URL-encoded forms, does not appear in any response, log line, metric, exception message or audit record, including under failure. Under (a) and (a') this test cannot see inside the ciphertext; a second test opens the payload with the test key and asserts only the sealed key is present and padded. Under (b) the payload holds no chained value and a mutant that puts it there is caught by opening the payload.
18. The token contains no cleartext of the chained value or the root `subjectId` (search the raw bytes before base64). Mutant: put either in the header.
19. Padding (a, a'): keys of different lengths within one 64-byte bucket give tokens of identical length; a key over 256 bytes gets no link. Mutant: no padding, or padding to a variable length.
20. Option (b): origin drift (record removed, record changed) is `LINK_INVALID` with audit `ORIGIN_DRIFT`; reordering alone is not drift. A link on a one-to-many item re-selects the right translated key after the resolver returns a different order.
21. Option (b): depth `max-chain-depth + 1` is not issued; `DEPTH_EXCEEDED` on a forged deeper link. For every depth up to the ceiling the worst-case token fits `max-length`: a test builds a worst-case token (maximum labels, maximum `subjectId`) and checks the length formula in section 3. Startup refuses a `max-length` below the formula.
22. Failed-attempt limiter: the counted reasons (`MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED`) count; post-authentication reasons (`EXPIRED`, `ISSUED_IN_FUTURE`, `ORIGIN_DRIFT`, `TARGET_NOT_PERMITTED`, `RELATION_UNDECLARED`, `PAYLOAD_MISMATCH`, `DEPTH_EXCEEDED`) never do, tested with genuine links in each state; a forged token whose cleartext header names a target the caller cannot see, an expired expiry, or an undeclared relation is recorded and counted as `AUTH_FAILED` and not as the reason the header suggests (mutant: classify from the header before authentication); the N+1th counted failure by principal P returns `LINK_ATTEMPTS_EXHAUSTED` for P, including for a valid link; principal Q in the same scope and P in another scope are unaffected; the window ends and the lock lifts; increments do not extend the window; the counter map holds no case id, principal id or other sensitive value. Mutants: count every reason, key by scope only, extend the window on increment, accept valid links while locked.
23. Collections: one link per distinct element key; indexed paths; the same key at several indices within one source result and toward one target gives one link, at the first index in path order (OD-14); a record with no chainable value gets none; the cap applies after collapse.
24. One-to-many follow: the outer status rule for each mix (all answered, some answered, all `NO_DATA`, all `TIMED_OUT`, mixed failures, some refused, all refused); an item status that does not match the code pattern fails the schema; budget exhaustion part-way gives the items read so far and `itemsTruncated`; exhaustion before the first read is `SCOPE_READ_BUDGET`.
25. Architecture rules: no `mcp` or `connectors` class depends on the raw-key accessor; the link module does not depend on `data-prism-reidentification`; no class outside the sealer calls `Cipher.getInstance("AES/GCM...")` (extend the existing designated-class pattern).
26. HKDF against RFC 5869 vectors (with the zero salt and one-block expand); nonce uniqueness over a large sample; derived keys differ per label and per scope; a key shorter than 32 bytes refuses startup.
27. `PiiLogScanTest`, `AuditFilePiiScanTest` and `HazelcastStoredValueBoundaryTest` still pass with links enabled.
28. 0.9: external review reproduces items 2, 3, 8, 9 and 17 independently.

## 8. Open decisions for the owner

Not decided here. Each has options and a recommendation. Choices made in the text of this document are listed too (OD-9 to OD-17).

| # | Question | Options | Recommendation |
|---|---|---|---|
| OD-1 | How the real chained key reaches `follow` | (a) ciphertext of the real key; (a') as (a) with a dedicated short-lived key and destroyed retired keys; (b) origin reference resolved server-side without Hazelcast, with a trace-length cap, quadratic reads and compounding drift; (c) chain only from keys a human supplies. Comparison in section 2 | (a') with fixed-size padding, accepting T10 and T17 in writing, because (b) fails the 1.0 purpose beyond `max-chain-depth` hops. Choose (b) instead if no new real-identifier ciphertext in provider logs matters more than long unaided traces. Keep (c) reachable by `enabled=false`. Measure the pilot's trace lengths and p first if cheap |
| OD-2 | Bind a link to the caller? | Bind (a second principal in the case cannot reuse a pasted link) or scope-only (analysts can hand links over) | Bind. Authorisation is rechecked on follow either way, so binding costs only hand-over convenience, and the audit attributes issue and use to one principal |
| OD-3 | Operator diagnostics versus oracle | Keep `AUTH_FAILED` as one reason; or add a keyed scope tag to the cleartext header so audit can say "wrong scope" | Keep one reason. The loss is small and nothing reveals a scope relationship |
| OD-4 | Scope end semantics. A scope has no stored end; the same case id re-used later is the same scope | (i) accept: links die by lifetime alone; (ii) add a scope epoch (a value stored with the case that changes at scope end) into key derivation | (i) now, documented; revisit with the pilot. (ii) needs state the roadmap forbids unless the epoch derives from an existing source |
| OD-5 | Charging the origin re-reads under (b) | Charge each read (origin and target); or charge only the target | Charge each read. An uncharged read is an unbounded one. Moot if (a') is chosen |
| OD-6 | HKDF: implement over HmacSHA256 now, or wait for `javax.crypto.KDF` (Java 24) | Implement and test against RFC vectors; or defer links to a Java 25 baseline | Implement. Bytecode stays Java 21 |
| OD-7 | Default for `dataprism.links.enabled` | `false` (opt-in); `true` once the quickstart has a chainable field | `false` through 0.9 so a deployment never gains an authority object unreviewed; revisit at 1.0 |
| OD-8 | Failed-attempt policy (the rule for what counts, per-principal keying and the fixed window are specified in section 4) | The limit and window values; whether to lock or only count and audit; whether to add an aggregate per-scope ceiling on top of per-principal | Lock per principal at 20 counted failures per `PT10M`; no aggregate scope ceiling, because it would let one principal lock out the rest |
| OD-9 | Outer status of a one-to-many follow and partial results under budget exhaustion | The rule in section 5 (any answered item makes it `ANSWERED`; partial results with `itemsTruncated`); or the outer status is `FAILED` unless every item answered; or refuse the whole call if the budget runs out part-way | The rule in section 5. Returning what was read is consistent with D-RM-12 and the budget is already charged |
| OD-10 | One link per collection element versus a single link for a whole collection | Per element (a link identifies exactly one key); or one link per collection field that follows to `items` (under (b) only, since (a) would have to seal the whole key list) | Per element. It keeps every link small and fixed in meaning and keeps the cap and paging (task 198) in one place |
| OD-11 | Cap policy | Round-robin across targets with default 50; or fill by relation; or a smaller default | Round-robin, 50. Round-robin cannot let one relation starve the rest; 50 is a starting value for the load harness to confirm |
| OD-13 | Audit disposition grammar. `AuditEvent` accepts only `PrivacyAction` names, `REFUSED` and (D-070-13) `REFUSED:<code>`; the proposed reasons, counts, ids and timings do not fit | A: widen the grammar with an explicit allow-list of value shapes (changes the published `docs/audit.md` contract and the verifier). B: move counts, ids and timings to existing record fields or metrics, and widen only for reasons. C: encode only `REFUSED:<code>`-shaped values (one code per reason, e.g. `REFUSED:LINK_AUTH_FAILED`), and put counts and timings in metrics | C. It needs no contract or hash change; the cost is that link ids, attempt counts and resolver timings are in metrics, not the audit. Choose A if the audit must join an issued link to its use |
| OD-14 | Duplicate collection elements | Collapse the same key across all indices of a source result toward one target (first index wins, as in the rules); or only within one field path; or never collapse | Collapse across the result. Links are per key, and a second link for the same key adds only context cost |
| OD-15 | Key rotation when `lifetime=scope` | Rotate the link key at most every maximum scope lifetime and retire it after that plus skew; or cap link lifetime at the rotation interval; or forbid `scope` under (a') | Allow `scope` and state that the (a') exposure window then equals the scope lifetime plus the rotation interval |
| OD-16 | Clock skew defaults | `PT5S` default with `PT30S` ceiling; or 0 (strict); or a larger default | `PT5S` and `PT30S`. Skew applies to link expiry only |
| OD-17 | The 256-byte ceiling on a sealed key under (a), (a') | 256 bytes, longer keys get no link and show in `_linksOmitted`; or a larger bucket cap with a larger `max-length` | 256. A user with a longer identifier sees no link for that field and a count in `_linksOmitted`; raise the cap only on evidence of such keys |
| OD-12 | Link text against context cost | Keep `max-length` 1536 with depth ceiling 4; or shrink label limits so more hops fit | Keep. Typical links are one fifth of the worst case |

## 9. Proposed names (closing table)

None collides with an existing name; the commands used are recorded in the Outcome of task 191. `KEY_UNRESOLVED` (in `key-resolvers.md`) is close in spelling to the existing startup code `HMAC_KEY_UNRESOLVED`; they are different kinds (a per-source status and a startup refusal), but task 200 should record both together or rename the new one.

| Name | Kind | Proposed release |
|---|---|---|
| `follow` | MCP tool | 0.8 |
| `LINK_INVALID` | refusal code | 0.8 |
| `LINK_ATTEMPTS_EXHAUSTED` | refusal code | 0.8 |
| `LINK_KEY_SHARED` | startup refusal code | 0.8 |
| `LINK_KEY_UNRESOLVED` | startup refusal code | 0.8 |
| `LINK_KEY_WEAK` | startup refusal code | 0.8 |
| `INVALID_LINK_LIFETIME` | startup refusal code | 0.8 |
| `INVALID_LINK_LIMIT` | startup refusal code | 0.8 |
| `link` | audit disposition key (values `INVALID:<reason>`) | 0.8 |
| `link:id` | audit disposition key | 0.8 |
| `link:attempts` | audit disposition key | 0.8 |
| `links:issued` | audit disposition key | 0.8 |
| `links:omitted` | audit disposition key | 0.8 |
| `_links` | result field | 0.8 |
| `_linksOmitted` | result field | 0.8 |
| `items`, `itemsTruncated` | result fields (one-to-many follow) | 0.8 |
| `dataprism.links.enabled` | configuration key | 0.8 |
| `dataprism.links.key-id` | configuration key | 0.8 |
| `dataprism.links.lifetime` | configuration key | 0.8 |
| `dataprism.links.max-per-response` | configuration key | 0.8 |
| `dataprism.links.max-length` | configuration key | 0.8 |
| `dataprism.links.bind-caller` | configuration key | 0.8 |
| `dataprism.links.invalid-attempt-limit` | configuration key | 0.8 |
| `dataprism.links.invalid-attempt-window` | configuration key | 0.8 |
| `dataprism.links.clock-skew` | configuration key | 0.8 |
| `dataprism.links.max-chain-depth` | configuration key (option b only) | 0.8 |
| `LINK_<REASON>` (twelve audit-only reason codes, shape C of OD-13) | audit value | 0.8 |
| `dataprism/link/v1/seal`, `/fingerprint`, `/caller` | key-derivation labels | 0.8 |
