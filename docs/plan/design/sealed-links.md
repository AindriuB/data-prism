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
- Per-source audit detail is carried as dispositions (`<source>:<refused>`, `merged:<refused>`), so new audit information can be added without changing the record's fields or hash encoding (docs/audit.md, "policyDecision values"; task 190 behaviour 5).

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
| T7 | Enumeration and refusal oracles, including timing | One refusal code and one text for every invalid link; all checks run before any outcome is chosen; audited reason is never returned; an attempt limiter per scope (`LINK_ATTEMPTS_EXHAUSTED`) | See "Oracle analysis" below |
| T8 | Link volume abuse: a record with thousands of chainable fields floods context, cost and logs | Per-response cap with a truncation marker (D-RM-12); links only for `chainable: true` fields (D-RM-4); one step only; link construction does no I/O | Marker reveals how many were omitted. Accepted |
| T9 | Key rotation and revocation | Dedicated link key id with rotation-aware verification (section 3); retiring a key invalidates its links at once | There is no per-link revocation. The way to revoke everything is to remove the key; the way to revoke one scope's links is to wait for expiry (OD-4) |
| T10 | A stateless link carries a real identifier into model context and provider logs; key compromise later decrypts every link ever issued | Depends on the option chosen. See "The real-identifier-in-link question" | Option dependent. This is OD-1 |
| T11 | Prompt-injected agent exfiltrates links to a third party | A link is useless outside its scope and, if bound, its caller; follow requires a live authenticated session | An attacker with the session token and the link has what the agent had. Out of this design's reach |
| T12 | Operator reads audit or logs and recovers keys | Neither the audit nor logs carry the link, the plaintext payload or key bytes; the audit carries a short `linkId` | An operator with key custody can forge links. Same trust as the pseudonymisation key |
| T13 | Cluster member with the key forges for any scope | Per-scope derived keys narrow nothing against a member holding the master. Same as the accepted consequence on `SecretKeyProvider` | Accepted; task 192 for the transport |
| T14 | Link reveals relationships (A4) to a reader of the model context | Link text is opaque; the target label is what the client already sees (alias or real name per D-RM-10); `_links` only exists for sources the caller may see (D-RM-11) | The count and relation of links on a result is visible to the holder |
| T15 | Decompression/oversize or parsing attack on `follow` input | Maximum length checked before any decoding; fixed-layout binary; no recursion; no deserialisation of untrusted classes | None expected |
| T16 | Link built from a field value the client controls (injection through a source record) | Payload built from a typed binary layout, with lengths; the chainable value is never concatenated into a delimited string | None expected |

### Oracle analysis

What a prober may learn from a refusal: nothing except that the call was refused. To make that hold:

- One code (`LINK_INVALID`) and one client text for malformed, oversized, wrong version, unknown key id, failed authentication, expired, wrong scope, wrong caller, wrong target, source not permitted, relation no longer declared, and stale origin (drift).
- The checks do not short-circuit on a secret-dependent condition: after the structural parse (which depends only on public layout), the server always performs the AEAD verification (against the key id named, or a fixed dummy key if the id is unknown), then evaluates expiry, authorisation and declarations as booleans, and combines them. A different early exit between "expired" and "forged" would be a timing oracle. Tag comparison is the JDK's constant-time AEAD verification; no hand-written comparison is allowed.
- A structural failure (too long, not base64url, wrong version byte) is cheaper than a cryptographic one. That difference reveals only public layout rules, not key-dependent facts, and is accepted. The length check runs first for exactly that reason, to cap work.
- The response body, size, status and the audit write happen for every outcome. The audit write is asynchronous or constant-cost relative to the outcome class.
- After authentication succeeds, the checks that can distinguish "valid but not allowed" from "invalid" (source no longer permitted for this caller, relation no longer declared, expired) also return `LINK_INVALID`. A prober cannot reach this point without a valid link, so what these outcomes would reveal is only revealed to a holder of a real link, who already knows the target's label. They are still uniform, to give nothing to a replaying holder whose permissions changed.
- Downstream status after a valid link (target `TIMED_OUT`, `NO_DATA`, `ANSWERED`) is not hidden: it is the answer. It is reachable only with a valid link.
- The attempt limiter is keyed by scope (and caller): `invalid-attempt-limit` failures within the scope lifetime pause `follow` for that scope with `LINK_ATTEMPTS_EXHAUSTED`. That code says only "too many failed attempts", reveals no link property, and its counter holds no sensitive value (boundary 6 permits it in Hazelcast).

### The real-identifier-in-link question

The mechanism that makes a link work must give the server, at `follow` time, the real key of the chained field (or a way to re-obtain it). Three options (plus one variant) are compared. The owner decides (OD-1); the recommendation is stated at the end of this subsection and the text of the contract in the rest of the document is written so that sections 3 to 5 hold for all of them except where a row says otherwise.

**Option (a): ciphertext of the real key inside the link.** The payload carries the key (for example the parcel reference) encrypted under the link key. Stateless, one read at follow time, no origin replay.

**Option (b): ciphertext of a scope-local reference, resolved server-side without Hazelcast ("origin reference").** The payload carries no chained key at all. It carries a description of how to obtain it again: the origin source label, the request that produced the result the link sits on, the field path, and a keyed fingerprint of the chained value. At follow time the server re-reads the origin source for that request (through the normal fetch path, fully scrubbed and budgeted), locates the record whose chainable field has the recorded fingerprint, takes the real key from the raw record in memory, and reads the target. The "reference" is the request the caller already made; the only real identifier that ever sits in any payload is the root input the caller itself supplied in its own tool argument, which the model and the provider logs already hold in clear. A link on a followed result points back to the follow link that produced it (a chain of origin links), capped by `max-chain-depth`.

**Option (c): the roadmap fallback, chain only from keys a human supplies.** No `_links`. `list_sources` and `describe_source` tell the agent which input namespaces each source takes; a human gives the agent the identifier for the next question. No new cryptography, no new authority object.

**Variant (a'): option (a) with a dedicated, short-lived link key.** Same payload as (a), but the link key id is separate from the pseudonymisation key id and is rotated at least every `lifetime`, with keys older than the maximum lifetime removed with `MultiKeySecretKeyProvider.remove`.

| Criterion | (a) | (a') | (b) | (c) |
|---|---|---|---|---|
| New real-identifier ciphertext in model context and provider logs | Yes, every chained key of every result | Yes, same | No. Only the root input already sent in clear | None |
| Effect of later key compromise | Every link ever issued under that key is opened: reveals real keys of the sources | Only links issued under keys not yet removed; removed keys cannot be recovered by compromising the current one. Logs older than the retention of the key are safe | Opens only the root request the caller typed, plus structural facts (source labels, field paths). No new real identifier | Not applicable |
| Statelessness and cluster safety (D-RM-14) | Yes | Yes, plus a key-rotation procedure shared by all members | Yes: nothing stored | Yes |
| Hazelcast use (boundary 6) | None | None | None | None |
| Reads at follow time | 1 (target) | 1 | 2 or more (origin re-read, plus one per chain step, then target) | 1 |
| Read-budget cost | 1 per follow | 1 | 2 or more per follow (see OD-5) | 1 |
| Behaviour when origin data changes | Follows the key as it was | Same | Fingerprint mismatch gives `LINK_INVALID` (drift); the agent re-runs the origin call | Not applicable |
| Raw value in process memory | Only during the follow | Same | During the origin re-read and the follow; never stored | During the follow |
| Boundary 1 (nothing unscrubbed reaches `mcp`) | The chain value leaves the engine as ciphertext. The leak validator cannot see inside; relies on the sealer being the only emitter | Same | The ciphertext holds no chained value, so the validator's raw-value check is meaningful for links | Fully inside the existing validator |
| Compatibility with the "never sees a real identifier" promise in `roadmap.md` | Weakened: it sees ciphertext of one | Weakened, time-limited | Holds for chained values | Holds |
| Delivers the 1.0 purpose (trace a record across hundreds of services unaided) | Yes | Yes | Yes, at higher read cost | No. Needs a human at every hop, which is the headline use case |
| Cost of mistakes in the sealer | High (leaks real keys) | High, narrower in time | Low (leaks only the root request) | None |
| Moving parts | Fewest | Key rotation procedure | Origin re-fetch, fingerprint, depth cap | Fewest |

Evidence for the compromise claim in (a) and (a'): the existing accepted consequence on `SecretKeyProvider` is that a memory disclosure exposes the key (`docs/architecture.md`, decisions; Javadoc of `SecretKeyProvider`). Model providers keep logs for periods the operator does not control, so ciphertext emitted today can be read after the key is compromised later. The pseudonymisation key cannot be rotated away to fix this because pseudonyms must stay stable for an investigation's life (HISTORY, "S2 configurable multi-locale name pools"), which is why (a') needs its own key id. For (b), the property rests on one argument that the reviewer should test: every real identifier that appears inside any link payload was previously sent to the server by the same caller in clear in a tool argument (`subjectId`), and so already sits in the model context and provider logs. A chained value (the parcel reference read from a source record) is never in a payload; only its keyed fingerprint is, and a fingerprint keyed under the link key is not reversible without both the key and a candidate list.

Weak points of (b) to be examined, not hidden:

- It doubles the reads of a follow and adds one per chain step. This matters at the 100/300/500-source load targets. A short-lived in-process cache of the raw origin record would remove the cost but would hold raw values in process memory beyond a request; it is not proposed.
- The fingerprint is a guessing oracle for whoever holds the key and a candidate list of references. Same custody as every other keyed construct here.
- Drift: the origin may no longer return the record. The agent must repeat the origin call. That is correct behaviour (fail closed), at the price of user-visible refusals on volatile sources.
- Stored origin requests that include free-text search arguments carry them in the payload. In 0.8 `get_entity_context` takes only `entityType` and `subjectId`; when search arrives (after 1.0) the payload must carry the same fingerprint-only form the audit uses.

**Recommendation (for the owner to accept or overrule): option (b).** It is the only option that adds no new real-identifier ciphertext to the provider logs, so a key compromise years later reveals nothing the logs did not already contain; its extra cost is reads, a quantity this roadmap already measures (load harness, task 196). Option (a') is the second choice if the read cost proves unacceptable at 300 or 500 sources, because it bounds the harm in time. Option (a) with the pseudonymisation key is not recommended. Option (c) remains the guaranteed fallback and costs nothing to keep available: with `dataprism.links.enabled` false the system is option (c).

## 3. Link format

### Algorithm

AES-256-GCM, from the JDK (`javax.crypto.Cipher`, `AES/GCM/NoPadding`), 96-bit random nonce, 128-bit tag. Reasons: authenticated encryption with associated data is exactly the required primitive; it is in the JDK 21 baseline, so no dependency is added; hardware support makes it cheap at thousands of links per investigation; ChaCha20-Poly1305 is also in the JDK and is the documented alternative if a target platform lacks AES acceleration. AES-GCM-SIV is not in the JDK and would add a dependency, so it is not proposed. Random 96-bit nonces are safe up to about 2^32 messages per key; the per-scope derived key (below) plus the rotation of the link key keeps the count per key far below that (1 link is at most one encryption; a response carries at most `max-per-response`).

The "encryption" in option (b) protects structure and fingerprints rather than a real key; the AEAD is still required for integrity, so the choice does not depend on OD-1.

### Key derivation

The link key id is `dataprism.links.key-id` (a `SecretKeyProvider` id, distinct from `dataprism.privacy.hmac-key.key-id`; startup refuses a shared id). Derivation is HKDF-SHA-256 (RFC 5869) over `secret(linkKeyId)`, with an `info` field that is a length-prefixed concatenation of a purpose label, the format version and the scope id:

| Purpose | `info` label | Used for |
|---|---|---|
| Sealing | `dataprism/link/v1/seal` | AES-256 key for the AEAD |
| Fingerprinting | `dataprism/link/v1/fingerprint` | HMAC key for the chained-value fingerprint (option b) |
| Caller binding | `dataprism/link/v1/caller` | HMAC key for the caller tag in the AAD |

Different labels mean that the sealing key and the fingerprint key never coincide. The scope id in `info` means a key derived for one scope is not the key for another, which narrows what a leak of one derived key gives (it does not narrow what a leak of the master gives). The JDK 21 baseline has no public HKDF API (`javax.crypto.KDF` arrives in Java 24), so 0.8 implements HKDF over `HmacSHA256` directly and tests it against the RFC 5869 vectors (OD-6).

Rotation: a token names its key id (see layout). Verification looks up exactly that id with `SecretKeyProvider.secret`, which already throws on an unknown id and must not fall back to another key (the trap recorded in HISTORY S2a). Issuance always uses the current `key-id`. Rotation is: add the new key, change `key-id`, keep the old key until the maximum link lifetime has passed, then `remove` it. After removal its links are refused as `LINK_INVALID` with audit reason `UNKNOWN_KEY`.

### Layout

Base64url without padding of a binary layout, fixed order, all lengths explicit:

| Field | Size | Notes |
|---|---|---|
| version | 1 byte | `0x01`. A different value is refused before any cryptography |
| key id length, key id | 1 + up to 16 bytes | ASCII `[A-Za-z0-9_-]`, cleartext (not sensitive) |
| expiry | 8 bytes | epoch seconds, cleartext, also in the AAD |
| target length, target | 1 + up to 64 bytes | the label the client sees in this response (alias or real name, D-RM-10), cleartext, also in the AAD |
| relation length, relation | 1 + up to 64 bytes | cleartext, also in the AAD |
| nonce | 12 bytes | random |
| ciphertext and tag | variable + 16 | the sealed payload |

Associated data is the length-prefixed concatenation of: version, key id, expiry, target, relation, `scopeId` (taken from the session, never from the token), and the caller tag (below). Because scope and caller come from the authenticated session at verification time, a token replayed in another scope or by another caller fails authentication rather than relying on a comparison the code could forget.

Sealed payload (binary, versioned by the outer version byte):

| Field | Option (a) | Option (b) |
|---|---|---|
| link id | 8 random bytes (audit correlation) | same |
| target source (internal name) and direction (`direct` or `translated`) | yes | yes |
| namespace, and for translated the resolver name and target namespace | yes | yes |
| the chained value | the real key | absent |
| origin: source, request (`entityType`, `subjectId`), field path, fingerprint of the chained value, depth, and the parent link's payload | absent | yes |
| issue time | yes | yes |

Binding the caller: the AAD carries a caller tag, `HMAC(callerKey, principalId)` truncated to 16 bytes, not the principal in the clear (the tag is in neither the cleartext header nor the audit). Whether to bind is OD-2. When unbound the tag is 16 zero bytes. The recommendation is to bind.

Binding the target source and relation in both header and payload: the header copy lets the AAD name them before decryption; the payload copy is compared after decryption and a mismatch is `LINK_INVALID` (belt and braces against a future change that stops authenticating the header).

Maximum encoded length: `dataprism.links.max-length`, default 768 characters. Worst case under option (b) at depth 1: header about 160 bytes, nonce 12, payload about 200, tag 16 gives about 390 bytes, 520 characters. Depth multiplies the origin portion, which is why `max-chain-depth` defaults to 3 and the sealer refuses to issue a link whose encoding would exceed `max-length` (the link is omitted and counted in the truncation marker). Longer input to `follow` is refused before decoding.

Expiry: `min(now + lifetime, scope.expiresAt, caller token expiry)` at issuance. `lifetime` is a duration or the word `scope`, which means "up to the scope's current expiry". It is never beyond the scope (D-RM-6).

## 4. Uniform refusal, audit and what the client never learns

Client sees, for every invalid link, the existing refusal result shape with:

- code `LINK_INVALID`;
- the same fixed text for every reason ("The link is not valid.");
- the usual `correlationId`.

The client never learns: which check failed; whether the link was once valid; whether it was expired, forged, for another scope, caller or target; whether the target exists or is permitted; whether the origin drifted; which key id it names.

Audit (using dispositions so the record's fields and hash encoding do not change; the decision is `DENY:LINK_INVALID`):

| Disposition key | Value | Meaning |
|---|---|---|
| `link` | `INVALID:<reason>` | The real reason, from a closed set: `MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED`, `EXPIRED`, `TARGET_NOT_PERMITTED`, `RELATION_UNDECLARED`, `ORIGIN_DRIFT`, `DEPTH_EXCEEDED`, `PAYLOAD_MISMATCH` |
| `link:id` | 16 hex characters | The link's id, when the link authenticated; absent otherwise (an unauthenticated id is attacker data) |
| `link:attempts` | integer | The scope's failed-attempt count after this call |

`AUTH_FAILED` covers forgery, tampering, wrong scope, wrong caller and wrong target, because the AEAD cannot say which input differed. That is a deliberate loss of operator diagnostics in exchange for no oracle. If the owner wants more, the only safe addition is a keyed scope tag in the cleartext header (OD-3 option).

On the issuing call the audit gains `links:issued` (count), `links:omitted` (count truncated) and, per resolver used for translated links, no entry at issue (resolvers are not called at issue, see `key-resolvers.md`). A successful follow audits `ALLOW`, the target as `name:status` like any source, and `link:id`.

Never in audit, logs, metrics or exceptions: the token, the sealed payload, the chained value, the fingerprint, any key. A rejected token is not echoed even truncated. `PiiLogScanTest` and `AuditFilePiiScanTest` extend to cover it (section "Test obligations").

## 5. The `follow` tool and `_links`

### `follow`

Input schema (draft 2020-12). The caller supplies nothing but the link: scope, principal, purpose and case come from the session (rule 4; `ReservedArguments` strips them).

```json
{
  "type": "object",
  "properties": {
    "link": { "type": "string", "minLength": 1, "maxLength": 768, "pattern": "^[A-Za-z0-9_-]+$" }
  },
  "required": ["link"],
  "additionalProperties": false
}
```

`maxLength` follows `dataprism.links.max-length`; the schema served is generated from the configured value. A client that ignores the schema gets the same `LINK_INVALID`.

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
    "sourceResult": {
      "type": "object",
      "properties": {
        "status": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
        "entity": { "type": "object" },
        "items": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": { "status": { "type": "string" }, "entity": { "type": "object" } },
            "required": ["status"]
          }
        },
        "itemsOmitted": { "type": "integer", "minimum": 1 },
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
        "link": { "type": "string", "maxLength": 768 },
        "expiresAt": { "type": "string", "format": "date-time" }
      },
      "required": ["rel", "target", "field", "link", "expiresAt"]
    }
  }
}
```

An invalid link returns the refusal result, not this shape. `entity` is present only for `ANSWERED` (D-070-1). `items` appears only for a one-to-many translation and only when the status of the target is `ANSWERED`; the item cap and omission count are `dataprism.key-resolvers.max-targets` and `itemsOmitted`. A bulk or status-only form is task 198's.

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
          "namespace": "parcel-ref", "link": "AQ…", "expiresAt": "2026-10-09T14:30:00Z" },
        { "rel": "translated", "target": "courier-roster", "field": "parcel",
          "namespace": "parcel-ref", "toNamespace": "route-code",
          "link": "AQ…", "expiresAt": "2026-10-09T14:30:00Z" }
      ],
      "_linksOmitted": 12
    }
  }
}
```

Rules:

1. Links exist only for fields marked `chainable: true` (D-RM-4), only toward sources the caller is permitted to see (D-RM-11) and whose declared input namespace matches the field's exactly (`direct`) or is reachable through one declared resolver (`translated`); never two resolvers in a row (D-RM-5, D-RM-14).
2. `field` is the name of the field as the client sees it in `entity`. Because the identifier itself is removed from output, `field` names the pseudonymised or placeholder field the link stands for. A chainable field that is dropped entirely carries its `field` as the declared model name only if that name is not itself a sensitive key; the scrubber's `<undeclared-N>` rule applies and a link is not issued for an undeclared field (fail closed).
3. A source whose status is not `ANSWERED` has no `_links` and no `_linksOmitted`. A `REFUSED` source (D-070-2) is omitted entirely and so is any link it would have produced. A source that returned `NO_DATA` has none.
4. Links are grouped by `rel`, then by target, in ascending target order, so output is deterministic (task 179's ordering rule).
5. Cap: `dataprism.links.max-per-response` (default 50) across the whole response. Selection under the cap takes links round-robin across targets in order so one relation cannot starve the others. When anything is dropped each affected source carries `_linksOmitted` with the number dropped from it (D-RM-12). The marker states a count only, never what was dropped.
6. Collections: one link per distinct (field, target) pair, not one per element, unless the collection's elements are individually chainable, in which case the cap applies per response and paging (task 198) carries the rest.
7. A link whose sealed encoding would exceed `max-length` is not issued and counts as omitted.
8. The leak validators run on the response with links present. The token is exempt from the key-shape scan by construction only if produced by the sealer (a typed `SealedLink` value); a token-shaped string that is not one fails validation (boundary 1).

### Configuration keys

All proposals; unknown keys under `dataprism.links` are startup errors like every group.

| Key | Default | Validation |
|---|---|---|
| `dataprism.links.enabled` | `false` | With `false`, no `_links`, and `follow` is not registered; the product behaves as option (c) |
| `dataprism.links.key-id` | none | Required when enabled; must resolve; must differ from `dataprism.privacy.hmac-key.key-id` (`LINK_KEY_SHARED`) |
| `dataprism.links.lifetime` | `PT30M` | Positive duration, or `scope`. Never effective beyond the scope (D-RM-6) |
| `dataprism.links.max-per-response` | `50` | 1 to 500 |
| `dataprism.links.max-length` | `768` | 256 to 2048 |
| `dataprism.links.bind-caller` | `true` | Boolean (OD-2) |
| `dataprism.links.invalid-attempt-limit` | `20` | Positive; per scope per scope lifetime |
| `dataprism.links.max-chain-depth` | `3` | 1 to 5; only meaningful under option (b) |

## 6. Boundaries

1. **Nothing reaches `mcp` without passing the privacy engine.** The link builder runs in `orchestration`, on the raw record, and returns only a sealed value typed so that the mapper can emit it and nothing else carries the chained value toward `mcp`. `mcp` receives tokens, never keys. No new class may build a mapper; the sealer needs none (binary layout, base64url). A new architecture rule must forbid any class in `mcp` or `connectors` from depending on the raw-key accessor of the link builder. Under (a) the real key leaves the engine as ciphertext and the validator cannot check it; this is the principal technical argument for (b).
2. **Boundary 5 (re-identification).** A link is never a path to `ScopeIdentityIndex.subjectFor` and the link module must not depend on `data-prism-reidentification`. The fingerprint of a chained value is not a pseudonym and is never written to the re-identification index. `follow` is an ordinary MCP tool with ordinary authorisation; it is never an operator tool.
3. **Boundary 6 (Hazelcast).** Links are not stored anywhere: no issued-link table, no used-link set, no cache of origin records. The only shared state is the failed-attempt counter, an integer keyed by scope, which holds no sensitive value; `HazelcastStoredValueBoundaryTest` gains the new map in its inventory and must still reject raw values.
4. **Boundary 7 (no sensitive value in log, metric, exception, audit).** No token, payload, chained value, fingerprint or key in any of them. Exceptions from the sealer carry a reason enum, never input. Metrics use the closed reason set as a label at most, never a link id.

Also: rule 4 (caller never supplies scope, principal, purpose or case) is honoured because scope and caller come from the session and enter the AAD; rule 2 (fail closed) is honoured because every unresolvable, unknown or erroring path is `LINK_INVALID`.

## 7. Test obligations for 0.8 and 0.9

Mutation-testable: each line names a mutant that must be killed. PIT gates the link checks from 0.9 (roadmap).

1. Round trip: issue then follow in the same scope and caller succeeds.
2. Each AAD field has its own test that changes only that field and expects `LINK_INVALID`: version, key id, expiry, target, relation, scope, caller. Mutants: drop any field from the AAD.
3. Bit flip at every byte position of a token (parameterised over the whole token) is refused. Mutant: skip tag verification.
4. Expiry at `now - 1 s`, `now`, `now + 1 s`: boundary exact. Mutants: `<` for `<=`, drop the check.
5. Effective expiry is the minimum of lifetime, scope expiry and token expiry; each of the three can be the binding one in a test. Mutant: use `lifetime` alone.
6. `lifetime=scope` never exceeds scope expiry.
7. Replay across scope, across caller (when bound), across target.
8. Unknown key id, retired key id, and a provider holding exactly one key asked for a different id: all refused, never a fallback (the S2a trap).
9. Uniformity: for every reason in the closed set, the client-visible result is byte-identical apart from `correlationId`; the audit reason differs. Mutant: return a different text or code for any reason.
10. Timing: a statistical test (not a unit assertion) that valid-but-expired, authenticated-but-not-permitted and forged tokens are not separable beyond a stated tolerance. Marked as a soak test, not a flaky unit test.
11. Oversize input is refused before any decoding or cryptography. Mutant: reorder the length check after decoding.
12. Cap: with `max-per-response=N` and more chainable fields, exactly N links and the right `_linksOmitted`; deterministic order; round-robin fairness.
13. `REFUSED`, `NO_DATA`, `TIMED_OUT` and other non-`ANSWERED` sources carry no `_links`.
14. Fields not marked `chainable` never produce a link. Mutant: ignore the flag.
15. Exact namespace match: a near-miss namespace (case, whitespace, prefix) gives no direct link. Mutant: case-insensitive compare.
16. Translation only through a declared resolver; two resolvers in sequence never produce a link.
17. Leak tests: the chained value, in plaintext, base64, base64url, hex and URL-encoded forms, does not appear in any response, log line, metric, exception message or audit record, including under failure. Under (a) this test cannot see inside the ciphertext; under (b) the payload holds no chained value and a deliberate mutant that puts it there is caught by a test that opens the payload with the test key.
18. The token contains no cleartext of the chained value or the root `subjectId` (search the raw bytes before base64). Mutant: put either in the header.
19. Option (b): origin drift (record removed, record changed, record reordered) is `LINK_INVALID` with audit `ORIGIN_DRIFT`; reordering alone is not drift.
20. Option (b): chain depth `max-chain-depth + 1` is not issued; `DEPTH_EXCEEDED` on a forged-deeper link.
21. Failed-attempt limiter: the N+1th failure in a scope returns `LINK_ATTEMPTS_EXHAUSTED`; another scope is unaffected; the counter map holds no sensitive value.
22. Architecture rules: no `mcp` or `connectors` class depends on the raw-key accessor; the link module does not depend on `data-prism-reidentification`; no class outside the sealer calls `Cipher.getInstance("AES/GCM...")` (extend the existing designated-class pattern).
23. HKDF against RFC 5869 test vectors; nonce uniqueness over a large sample; derived keys differ per label and per scope.
24. `PiiLogScanTest`, `AuditFilePiiScanTest` and `HazelcastStoredValueBoundaryTest` still pass with links enabled.
25. 0.9: external review reproduces items 2, 3, 8, 9 and 17 independently.

## 8. Open decisions for the owner

Not decided here. Each has options and a recommendation.

| # | Question | Options | Recommendation |
|---|---|---|---|
| OD-1 | How the real chained key reaches `follow` | (a) ciphertext of the real key; (a') as (a) with a dedicated short-lived key; (b) origin reference resolved server-side without Hazelcast; (c) chain only from keys a human supplies. Comparison in section 2 | (b). Second choice (a') if read cost is unacceptable at 300 to 500 sources. Keep (c) reachable by `enabled=false` |
| OD-2 | Bind a link to the caller? | Bind (a second principal in the case cannot reuse a pasted link) or scope-only (analysts can hand links over) | Bind. Authorisation is rechecked on follow either way, so binding costs only hand-over convenience, and the audit then attributes issue and use to one principal |
| OD-3 | Operator diagnostics versus oracle | Keep `AUTH_FAILED` as one reason; or add a keyed scope tag to the cleartext header so audit can say "wrong scope" | Keep one reason. The loss is small and nothing reveals a scope relationship |
| OD-4 | Scope end semantics. A scope has no stored end; the same case id re-used later is the same scope | (i) accept: links die by lifetime alone; (ii) add a scope epoch (a value stored with the case that changes at scope end) into the key derivation | (i) now, documented; revisit with the pilot. (ii) needs state the roadmap forbids unless the epoch is derived from an existing source |
| OD-5 | Charging the origin re-read under (b) | Charge each read (origin and target); or charge only the target | Charge each read. The budget exists to bound reads of sources, and an uncharged read is an unbounded one |
| OD-6 | HKDF: implement over HmacSHA256 now, or wait for `javax.crypto.KDF` (Java 24) | Implement and test against RFC vectors; or defer links to a Java 25 baseline | Implement. Bytecode stays Java 21 |
| OD-7 | Default for `dataprism.links.enabled` | `false` (opt-in); `true` once the quickstart has a chainable field | `false` through 0.9 so a deployment never gains an authority object unreviewed; revisit at 1.0 |
| OD-8 | Failed-attempt policy | Pause the scope's `follow` after N failures; or only count and audit; or reuse the per-caller rate limit | Pause per scope (default 20) so a prompt-injected agent cannot grind. Counter only holds an integer |

## 9. Proposed names (closing table)

None collides with an existing name: the commands used are recorded in the Outcome of task 191.

| Name | Kind | Proposed release |
|---|---|---|
| `follow` | MCP tool | 0.8 |
| `LINK_INVALID` | refusal code | 0.8 |
| `LINK_ATTEMPTS_EXHAUSTED` | refusal code | 0.8 |
| `LINK_KEY_SHARED` | startup refusal code | 0.8 |
| `LINKS_KEY_MISSING` | startup refusal code | 0.8 |
| `INVALID_LINK_LIFETIME` | startup refusal code | 0.8 |
| `INVALID_LINK_LIMIT` | startup refusal code | 0.8 |
| `link` | audit disposition key (values `INVALID:<reason>`) | 0.8 |
| `link:id` | audit disposition key | 0.8 |
| `link:attempts` | audit disposition key | 0.8 |
| `links:issued` | audit disposition key | 0.8 |
| `links:omitted` | audit disposition key | 0.8 |
| `_links` | result field | 0.8 |
| `_linksOmitted` | result field | 0.8 |
| `items`, `itemsOmitted` | result fields (one-to-many follow) | 0.8 |
| `dataprism.links.enabled` | configuration key | 0.8 |
| `dataprism.links.key-id` | configuration key | 0.8 |
| `dataprism.links.lifetime` | configuration key | 0.8 |
| `dataprism.links.max-per-response` | configuration key | 0.8 |
| `dataprism.links.max-length` | configuration key | 0.8 |
| `dataprism.links.bind-caller` | configuration key | 0.8 |
| `dataprism.links.invalid-attempt-limit` | configuration key | 0.8 |
| `dataprism.links.max-chain-depth` | configuration key | 0.8 |
| `dataprism/link/v1/seal`, `/fingerprint`, `/caller` | key-derivation labels | 0.8 |
