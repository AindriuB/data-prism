# Cluster security on open-source Hazelcast

Design for task 192. Internal planning document under `docs/plan/`, which mkdocs
excludes. No code and no configuration change accompany it: 0.8 builds what is
described here, and 0.8 is also where `docs/multiple-instances.md` and
`docs/configuration.md` change.

Status: **proposal.** Every choice that is the owner's to make is marked
**OD-n** and collected in section 6.2, each with options and a recommendation.
Nothing in this document is decided until the owner decides it. Where the text
says "recommended" it means the author's recommendation, not a decision.

Fixed inputs (not reopened here):

- **D-0.4.1-A:** no Hazelcast Enterprise.
- **D-RM-1**, as `PLAN.md` records it: "1.0 supports multiple instances; cluster security designed in 0.7, built in 0.8." The decision does not say what "secured" means. The agreed roadmap (`roadmap.md`, "What 1.0 means") says "Multiple instances supported and secured on open-source Hazelcast (no Enterprise features)"; what that wording may claim is left to the owner in OD-13, and this document does not assume an answer.
- Sealed links are stateless and never stored in Hazelcast (task 191). This
  design does not depend on them (section 4.7 says how link keys reach every
  member).

How the evidence below was gathered, on 2026-10-09:

- The Hazelcast 5.7.0 jar that Data Prism pins (`hazelcast.version` in the root
  `pom.xml`), inspected with `javap` and a byte scan. Quoted strings are from
  that jar.
- The Hazelcast 5.7 documentation at `https://docs.hazelcast.com/hazelcast/5.7/`.
  Pages cited by path in section 2.0.
- The Data Prism sources under `data-prism-hazelcast/`.

Anything not confirmed by one of those is marked **to verify in the 0.8 spike**.

---

## 0. Threat model

### 0.1 What the cluster is

Each instance runs an embedded Hazelcast member. Members that have joined one
cluster share six maps (inventory in section 1). The member protocol is plain
TCP on the member port (5701 by default). In the open-source distribution the
only thing a joiner must present is the cluster name. `ConfigCheck`
(`com.hazelcast.internal.cluster.impl.ConfigCheck`, 5.7.0) compares cluster
name, joiner type, partition-group settings and a few properties on join, and
nothing resembling a secret.

### 0.2 Actors

| Actor | Can do | In scope |
|---|---|---|
| **A1. Passive observer** on the member network | Read all member traffic, including entries and the cluster name | Yes |
| **A2. Active network attacker / hostile workload** that can reach the member port | Everything A1 does, plus join as a member or connect as a client with the cluster name; read, write, delete and lock any map entry; submit any operation the protocol allows | Yes. The main case |
| **A3. Misconfigured peer** (wrong cluster name, wrong key, other environment) | Join or fail to join by accident | Yes, as a safety property: must fail loudly, never merge silently |
| **A4. Holder of the cluster key** (a legitimate instance, or someone who obtained its secrets) | Everything, undetectably | **Out of scope.** Whoever holds the key is a member of the trust domain. Reduced by secret handling, not by this design |
| **A5. Operator / MCP client / LLM** | Not cluster actors. Covered by boundaries 4 and 5 and by the operator port | Out of scope here |

A2 is the actor the owner's concern describes. A1 is a weaker version of A2.

### 0.3 What an attacker wants, ranked by harm

1. **Forge an approval** and defeat four-eyes (a write to `dataprism.approval`).
2. **Lift a pause** (a delete in `dataprism.oversight`).
3. **Read the re-identification index** (a read of `dataprism.reidentification`).
4. **Read subject identifiers** from keys (`identity`, `budget`) and callers from keys (`callerrate`).
5. **Reset or exhaust budgets and rate limits** (write or delete in `budget`, `callerrate`).
6. **Poison the identity cache**: make a subject receive an attacker-chosen "synthetic" value, or make two subjects share one.
7. **Deny service**: hold locks, flood, change cluster state, take partitions.
8. **Run code on a member** through the member protocol's Java deserialisation surface.

### 0.4 What OSS Hazelcast gives and does not give

Evidence for "does not give" is the jar and the editions page, and is
the basis for excluding options in section 2.

| Capability | Edition | Evidence |
|---|---|---|
| Member TLS | Enterprise | `DefaultNodeExtension` string `SSL/TLS requires Hazelcast Enterprise Edition`; editions page lists "SSL/TLS Encryption", "TLS mutual authentication" as Enterprise |
| Member authentication, JAAS, RBAC | Enterprise | `Security requires Hazelcast Enterprise Edition`, `Security features are only available on Hazelcast Enterprise!`; editions page |
| Socket interceptor and security interceptor | Enterprise | `SocketInterceptor feature is only available on Hazelcast Enterprise!` (`DefaultNodeExtension.getSocketInterceptor` logs this and returns null); the socket-interceptor page carries the Enterprise marker |
| Symmetric encryption | Enterprise, and deprecated | `Symmetric Encryption requires Hazelcast Enterprise Edition`; encryption page says it is deprecated |
| Audit log | Enterprise | `Auditlog requires Hazelcast Enterprise Edition` |
| Managed rolling upgrade (patch or next minor) | Enterprise | editions page table: "Rolling upgrades" ticked for Enterprise, not Community; `maintain-cluster/rolling-upgrades` marks both the patch and the next-minor upgrade "Enterprise Edition only" |
| Emergency CVE patches | Enterprise | editions page table: "Emergency patches (CVEs)" ticked for Enterprise, not Community |
| Hot restart, dynamic config persistence, WAN, blue/green client filtering | Enterprise | `Hot Restart requires...`, `Dynamic Configuration Persistence requires...`; editions page table ticks for the rest |
| CP subsystem | Licensed | `CP subsystem is a licensed feature`. Data Prism uses no CP structure. It uses `IMap` and `IMap.lock`, which are AP |
| Inbound member allowlist | **Not found** | `NetworkConfig.getInterfaces()` has consumers only in `DefaultAddressPicker` and `AdvancedNetworkAddressPicker` (choose the local address to bind) and `TcpIpJoiner` (what it uses the value for is not confirmed). `multicast/trusted-interfaces` applies to multicast, which Data Prism disables. **To verify in the 0.8 spike** that a connection from an address outside `interfaces` is accepted |
| Java serialisation filter | **OSS** | `serialization/serialization-configuration`, "Disallowing Deserialization of Untrusted Java Classes" (`JavaSerializationFilterConfig`); no Enterprise marker |
| Same major.minor members join regardless of patch | OSS | `deploy/versioning-compatibility`, "Members" |

The cluster name is **not a secret**. It travels in the clear at join, and
Hazelcast log lines carry it (`LoggingServiceImpl` builds its version message
from the cluster name and the build version; the 5.x line format is
`[address]:port [cluster-name] [version]` **to verify on 5.7.0 in the spike**).

---

## 1. Inventory

All six maps are declared in `PrivacyCluster` (`IDENTITY_MAP` and the others) and
configured by `privacyMap`: backup count 1, no `MapStore`, LRU at 100,000 entries
per member, except `oversight` and `callerrate`, which are never evicted. Keys
are `String`; the separator is NUL (`ScopeKeys`). Values are `String` except
`budget` (`Integer`) and `oversight` (`Boolean`).

Impact below assumes actor A2 and the **current** (0.7) code.

### 1.1 `dataprism.identity`

| | |
|---|---|
| Key | scope id, namespace, **raw subject id**, NUL-separated (`ScopeKeys.identity`) |
| Value | the synthetic value |
| Lifetime | TTL to scope expiry |
| **Read** | Every subject id in use, which scope it is in, and the pseudonym it maps to. Joined with anything the attacker sees of model output, this undoes the pseudonymisation for those subjects |
| **Write** | `CachingSyntheticValueSource.syntheticValue` returns a cached value without checking it (`if (cached != null) return cached`). The collision check runs only on a miss, in `store`. An attacker value is therefore returned as the subject's identity: it can merge two subjects onto one pseudonym or split one subject across pseudonyms, which corrupts the very inconsistency evidence the product exists to show, and can place any chosen string in model-visible output. Undetected |
| **Delete** | CPU only: recomputed on the next lookup |
| Recoverable | Yes. The value is a pure function of scope, subject, namespace and key |

### 1.2 `dataprism.reidentification` (only with re-identification enabled)

| | |
|---|---|
| Key | scope id, namespace, **synthetic value** |
| Value | the **raw subject id** |
| Lifetime | TTL to scope expiry |
| **Read** | The complete pseudonym-to-subject index. The single most sensitive object in the cluster, and a boundary 5 asset |
| **Write** | Forge or alter a mapping, so an operator's re-identification returns the wrong subject and an investigation is misdirected |
| **Delete** | Destroys the mapping permanently. It is a store, not a cache |
| Recoverable | No |

### 1.3 `dataprism.budget`

| | |
|---|---|
| Key | scope id, **raw subject id** |
| Value | `Integer` read count |
| **Read** | Subject ids and how heavily each is being read |
| **Write** | Set to 0 or negative: more reads than the budget allows (extraction). Set high: deny reads for that subject |
| **Delete** | Reset the count. Same effect as writing 0 |
| Recoverable | No. A lost count is a lost count |

`HazelcastScopeBudget.tryRead` locks the key with `IMap.lock`, which waits
without a timeout. An attacker who locks a known key blocks every reader of it.

### 1.4 `dataprism.oversight`

| | |
|---|---|
| Key | `all`, `tool:<name>`, or `<scope id>` NUL `paused` |
| Value | `Boolean.TRUE`; presence means paused |
| Lifetime | Never evicted, no TTL |
| **Read** | Which tools and scopes are paused |
| **Write** | Pause anything: deny service |
| **Delete** | **Lift a pause.** `HazelcastOversightState.resume*` is exactly a delete, so an attacker's delete is indistinguishable from a resume |

### 1.5 `dataprism.approval`

| | |
|---|---|
| Key | scope id (empty if none), NUL, **approval id** |
| Value | NUL-joined record: kind, requester principal and client, scope, tool, binding fingerprint, namespace, synthetic value, purpose, case id, created and expiry, **status**, approver principal, decided-at (`HazelcastApprovalStore.Codec`) |
| Lifetime | TTL to the request's `expiresAt` |
| **Read** | Principals, case ids, purposes and tools: who is asking to see what, and why |
| **Write** | **Forge an `APPROVED` entry** naming any approver, or flip `PENDING` to `APPROVED` and so bypass four-eyes (`SELF_APPROVAL` is a check in `approve`, which an attacker skips by writing the entry directly). Flip a legitimate entry to `CONSUMED`, `REJECTED` or `EXPIRED` to deny it. Rewrite a `CONSUMED` entry back to `APPROVED`: use an approval a second time |
| **Delete** | Discard pending approvals: deny service |
| Recoverable | No. Requesters must ask again |

The store also locks bare approval ids and a `\0requester\0...` key that embeds
the requester principal. These lock keys are visible to the same attacker.

### 1.6 `dataprism.callerrate`

| | |
|---|---|
| Key | **caller principal id** |
| Value | `<window index>:<count>` |
| Lifetime | 2 windows; never evicted |
| **Read** | Caller identities and activity |
| **Write** | Set high: lock a caller out. Set low: lift a caller's limit |
| **Delete** | Reset the window |
| Recoverable | No |

### 1.7 Not in the maps, but reachable by the same actor

- **Locks.** `IMap.lock` entries on the keys above. Held forever by an attacker, they hang the calling request thread, because every call is `lock` with no timeout: `HazelcastScopeBudget.java:44`, `HazelcastCallerRateLimiter.java:32`, `HazelcastApprovalStore.java:59`, `:90`, `:151`, `:193`. `HazelcastScopeBudget`'s fail-closed `catch` (`:46-48`, `:56-58`) only runs when Hazelcast throws; a blocked `lock` never throws, so it hangs rather than refuses. That is a thread-exhaustion route to denial of service, not a fail-closed path.
- **Partitions.** A joined member is given partitions. If an attacker holds an owner and its backup of an entry, it controls that entry's availability.
- **Cluster administration.** A joined member or client can attempt cluster-state change, member shutdown and other administrative operations. Not confirmed per operation for 5.7.0 OSS. **To verify in the 0.8 spike**, and treat as an availability risk regardless.
- **Deserialisation.** The member protocol carries Java-serialised objects. Without a filter, a class on the member classpath that is exploitable on deserialisation is reachable by A2. This is a code-execution risk, not a data risk, and neither sealing nor isolation-by-attestation addresses it. See 2.3 and 4.5.
- **Jet, phone-home and user-code deployment.** Only `ClusterMembership.toConfig` (`ClusterMembership.java:91-92`) disables Jet and phone-home. `PrivacyCluster.configure` (the `embedded(Config)` path, `:134-141`) forces phone-home off but not Jet. `PrivacyCluster.using` (`:92-102`) forces neither and checks neither. Section 4.5 says what the design enforces on each path.

### 1.8 What an attacker gains, by category

| Harm | Maps |
|---|---|
| Disclosure of subject identifiers | identity, budget (keys); reidentification (values) |
| Disclosure of the re-identification index | reidentification |
| Disclosure of callers, cases, purposes | callerrate, approval |
| Forged control decision | approval, oversight |
| Resetting a limit | budget, callerrate |
| Corrupted identity | identity, reidentification |
| Denial of service | all, plus locks and partitions |

---

## 2. Options

### 2.0 Sources relied on

All Hazelcast references are to 5.7 unless noted; the pinned artifact is 5.7.0.
The documentation platform lists 5.7.2 as its current patch; see NOTED in the
Outcome of the task file.

| Source | Used for |
|---|---|
| `https://docs.hazelcast.com/hazelcast/5.7/getting-started/editions` | Enterprise versus Community feature table |
| `https://docs.hazelcast.com/hazelcast/5.7/security/enable-security`, `.../security/socket-interceptor`, `.../security/encryption` | Pages carrying the Enterprise marker; deprecation of symmetric encryption |
| `https://docs.hazelcast.com/hazelcast/5.7/clusters/network-configuration` | `interfaces`, `tcp-ip`, `trusted-interfaces` (multicast only), Member Address Provider SPI |
| `https://docs.hazelcast.com/hazelcast/5.7/serialization/serialization-configuration` | `java-serialization-filter`; the rules and the default-allowed prefixes `java`, `com.hazelcast.`, `[` |
| `https://docs.hazelcast.com/hazelcast/5.7/deploy/versioning-compatibility` | "Members operating on the same major and minor Platform version are always compatible, regardless of the patch version." Its second sentence, "Each minor version is always compatible with the previous minor version", is the compatibility that the managed rolling upgrade relies on, and that procedure is Enterprise only |
| `https://docs.hazelcast.com/hazelcast/5.7/maintain-cluster/rolling-upgrades` | Rolling upgrade is Enterprise only for both patch and next-minor upgrades |
| Jar `hazelcast-5.7.0.jar` | The class and string evidence in 0.4 |
| JDK 21 (`javax.crypto`: AES/GCM, HmacSHA256) | Option (c). No new dependency |

### 2.1 Option (a): network isolation only, documented and checked at startup

**What it is.** The 0.4.1 position: the deployer isolates the member port with a
firewall, `NetworkPolicy` or private network. This option adds what Data Prism
*can* check, and an explicit acknowledgement.

**Hazelcast reliance.** `NetworkConfig.getInterfaces()` and
`hazelcast.socket.bind.any=false` (5.7 network configuration page; already used
by `ClusterMembership.bindToInterface`). Nothing Enterprise.

**What Data Prism can check at startup (proposed):**

- A member interface is **required** for `tcp-ip` and `kubernetes`, and a pattern that matches everything (`*.*.*.*`) is refused. Today a missing interface means every host interface is bound.
- A resolved bind address that is globally routable is refused unless the attestation names transport protection (2.2). Address classification (private, link-local, unique-local, carrier-grade NAT range) is a heuristic. **To verify in the 0.8 spike** how it behaves on the supported platforms.
- The operator must state how the member port is isolated (an enumerated attestation, 4.1). The value is recorded in the audit startup event and exposed in health. It is an **attestation, not a verification**.

**What Data Prism cannot check.** Whether a firewall or `NetworkPolicy` exists or is
enforced by the CNI; whether another workload is on the same network; whether
5701 is published. The OrbStack observation in D-0.4.1-E shows the platform can
silently contradict the operator's belief.

| | |
|---|---|
| Cost | Low. A few validations and an attestation |
| Residual risk | **High.** Every attack in 0.3 is open to anyone who gets past the network layer, and a mistake is silent. Nothing in the cluster itself resists A2 |
| Operational burden | Network policy and its testing, owned by the deployer |
| Verifiable by Data Prism | Bind address only |

### 2.2 Option (b): transport protection outside Hazelcast

**What it is.** Member traffic is encrypted and the peers are authenticated by
something other than Hazelcast: a service mesh with mutual TLS (strict mode, and
an authorisation policy that admits only Data Prism's workload identity to the
member port), a CNI with transparent encryption (WireGuard or IPsec), or a
host-level WireGuard or IPsec overlay.

**Hazelcast reliance.** None beyond a plain TCP member protocol and a stable
advertised address per member (5.7 network configuration page). Hazelcast
members announce their own addresses to each other, so a tunnel that rewrites
addresses needs `public-address` configuration. Per-member tunnelling by hand
(for example a TLS wrapper per pair) is fragile for that reason and is not
recommended.

**What this gives that nothing else does.** It is the only open-source way to
*stop* an unauthorised party from **joining**, and so the only way to stop
deletion, replay and lock-holding at the source (see 2.3's limits). Option (c)
cannot do that.

**What Data Prism can verify at startup.** Very little. It can read an
attestation (`mesh-mtls`, `wireguard`, `ipsec`) and record it. Sniffing for a
sidecar (an environment variable, a mounted volume) is unreliable and should
not be used as proof. A deployer-run verification procedure (capture on the
member port and confirm it is not plaintext; attempt a connection from an
unauthorised workload and confirm refusal) belongs in the operations guide,
and in the 0.8 soak as an environment check.

| | |
|---|---|
| Cost | Zero Data Prism code beyond the attestation. High deployer cost if no mesh or encrypted CNI exists |
| Residual risk | Medium. Depends entirely on correct configuration, which Data Prism cannot see. Compromise of any pod admitted by the policy is full compromise (with no application-level integrity, a single admitted pod reads and writes everything) |
| Operational burden | A mesh or encrypted network and its identity management |
| Verifiable by Data Prism | No |

### 2.3 Option (c): application-level protection under a cluster key

**What it is.** Data Prism protects what it puts in the cluster, so that holding
the member port is no longer enough to read or forge anything. The key is
resolved through `SecretKeyProvider` from a **dedicated key id**, never the
privacy HMAC key (OD-9).

**Hazelcast reliance.** `IMap` with `String` keys and values, `set` with TTL,
`IMap.tryLock`; the 5.7 serialisation filter for 2.3.6. JDK 21 `javax.crypto`
only: `AES/GCM/NoPadding` and `HmacSHA256`. No dependency is added. Key
derivation is HKDF (RFC 5869) over `javax.crypto.Mac`, tested against the RFC
vectors; the JDK has a `KDF` API as of JDK 25 (JEP 510) but Data Prism emits Java
21 bytecode. Task 191 needs the same derivation, so one `labelled HKDF` helper
serves both.

#### 2.3.1 Authenticated encryption of values

- **Envelope** (a `String`, so no serialiser has to agree across members, the property `ScopeKeys` already protects): `dps1.<key id>.<salt>.<ciphertext+tag>`, URL-safe base64. `dps1` is the format version.
- **Algorithm:** AES-256-GCM.
- **Nonce uniqueness is by construction, not by luck.** A random 96-bit nonce under one long-lived key is limited to about 2^32 encryptions, and `budget` rewrites an entry on every counted read. Recommended: each process draws a random 128-bit **salt** at start and derives, per map, a subkey `HKDF(clusterKey, salt, info = "dp/cluster/v1/aead/" + mapName)`; it uses a 96-bit counter as the nonce under each subkey. The salt is in the envelope, so a reader derives the same subkey for the map it is reading (cached, bounded, LRU, keyed by salt and map). No nonce is ever reused under a subkey, and a ciphertext from one map is not decryptable as another map's even before the associated data is checked. The cost is one HKDF per distinct (salt, map) on the read side; an attacker spraying salts burns CPU, so the salt cache is bounded and failed authentications are rate-bounded and counted (section 4.4).
- **Associated data** binds: format version, key id, map name, the (opaque) map key, and for entries with a lifetime, the `expiresAt`. A ciphertext copied to another key, another map, or with an extended lifetime fails to authenticate. The reader also enforces `expiresAt` itself and does not rely on the cluster TTL.
- **Plaintext carries the canonical key parts** (scope, namespace, subject, principal and so on) so a holder of the key can decompose any entry. This keeps the boundary 6 test (`HazelcastStoredValueBoundaryTest`) able to recompute state from stored data, and lets scope clean-up scan values where keys are opaque.
- **Failure behaviour.** A value that fails authentication is a **miss with a tamper signal**, never an exception that escapes and never a pass-through. Per structure: `identity` recomputes (the property boundary 6 already guarantees); `reidentification` returns empty; `budget` refuses the read (fail closed); `approval` reports not found; `callerrate` treats the window as unknown and refuses (fail closed); `oversight` per 2.3.3.

#### 2.3.2 Keyed pseudonymous map keys

- Key = `HMAC-SHA256(nameSubkey(map), canonical key)`, URL-safe base64, so subject ids, principals, approval ids and pseudonyms no longer appear in any key. `nameSubkey` comes from a **name key** that is a separate secret from the cluster key and is **not rotated in the rolling procedure** (4.6). The reason is that an HMAC'd map key or lock key that changed with the encryption key would give two members different keys for the same entry during a rotation, so each would miss the other's entries and take different locks for the same logical lock.
- **Scope prefix is kept as a token.** Scope end currently depends on `startsWith(scopePrefix)` (`ScopeIdentityIndex.endScope`, `HazelcastScopeBudget.forget`, `HazelcastApprovalStore.keysOf`). The new key is `scopeToken.keyedPart`, where `scopeToken = HMAC(subkey, scope id)` truncated to 128 bits. Prefix scans continue to work; the scope id itself is hidden.
- **Approvals are found by approval id alone** (`locate` ignores scope today). The approval key is therefore `HMAC(approvalId)` with no scope token; scope end decrypts the (small, short-lived) approval map to find its entries.
- **Lock keys are keyed too** (the bare approval id, the `\0requester\0...` key). Otherwise they leak principals and are guessable.
- **Locks use `tryLock` with a bounded wait and a lease**, not `lock`, so an attacker's lock cannot hang a request thread and cannot be held forever. Timeout fails closed (4.1, `lock-timeout`).

#### 2.3.3 Oversight (pauses) need a different design

Sealing a `Boolean` does nothing for the dangerous attack, which is a **delete**.
Recommended design (OD-3):

- Pause state is **one sealed document** under a fixed, keyed name: the full set of pauses, a **sequence** number, a **refresh** counter, an **issued-at** time and the issuing member. Pause and resume write a new document with `sequence + 1` and `refresh = 0`. A lease refresh re-seals the same pauses with `refresh + 1`. Documents are ordered by (sequence, refresh), compared lexicographically.
- **Writes are compare-and-set.** Every write is `IMap.replace(name, oldSealedValue, newSealedValue)` (replace-if-equal on the exact sealed string the writer read), or `putIfAbsent` when it believes the document is absent. A lost race is re-read and retried, never overwritten, so two members cannot silently clobber each other's pause or resume. Each instance keeps the highest (sequence, refresh) it has accepted in memory and **never accepts a lower one**. A deleted entry cannot un-pause an instance that has already seen the pause. A replayed older document is rejected by every instance that saw a newer one.
- **Equal (sequence, refresh) with different content** can only come from a split-brain merge or from a key holder behaving badly. The rule is fail closed: the instance adopts the **union** of the two pause sets, counts `pause.conflict`, and writes the union with `sequence + 1` by compare-and-set. This is the safe direction for a pause; the cost is that a resume made on one side of a partition can be undone by the merge, and the operator has to resume again (OD-14).
- **Split-brain merge.** The map's Hazelcast merge policy cannot merge sealed payloads. The application reconciles after a merge: each instance's in-memory highest document is compared with the cluster document, and if the instance holds a pause the cluster document lacks, it writes the union (as above). "Highest sequence wins" is the alternative and is unsafe, because a pause issued on the smaller side is dropped (OD-14).
- The document has a **lease**. Any member refreshes it (`refresh + 1`) when half of it is spent. Freshness is measured from the instance's **local monotonic receipt time**: an instance records `System.nanoTime()` when it accepts a document with a higher (sequence, refresh) than it held, and treats the document as fresh for one lease from then. The wall clock is used only to gate a **cold start**: an instance with no memory rejects a document whose `issued-at` is older than the lease plus `clock-skew-tolerance`, so a replayed old document is not accepted as fresh on a restart; after that first acceptance the wall clock is not consulted, so skew between members cannot make a healthy document look stale. An instance treats a document that is **absent, unauthentic or not fresh as "everything paused"**, and refuses to serve. A delete therefore becomes a denial of service, which is the safe direction, and it is visible (`dataprism.cluster.pause.fresh`).
- **Missing document.** Whether a member re-writes a document it finds absent from its own memory is OD-15. Proposed: a member may do so only with `putIfAbsent`, only from a document it accepted within the last lease, and only if no verified member reports a higher sequence. Without that rule a member that missed a pause (partitioned) could re-write an older state and lift a pause that others hold.
- First start of a cluster: a member that finds no document and no other member writes the first one by `putIfAbsent`. A member that finds no document while other verified members exist stays not-ready until one appears, or the OD-15 rule restores it, or it is the only member.

Residual, stated plainly: a captured pre-pause document, replayed to an instance
that has not yet seen the pause (a fresh start) and not older than the cold-start
gate, lifts the pause for at most the lease. The default lease is the knob that
sets that window.

#### 2.3.4 Approvals and replay

AEAD stops forging. It does not stop restoring an old, valid ciphertext, such as
an `APPROVED` entry captured before `consumeApproved` set `CONSUMED`. Two
bounds, both proposed:

- `consumeApproved` also writes a **consumed tombstone** (keyed, with a TTL to `expiresAt`) and checks it, so a replay needs both a delete of the tombstone and a re-insert.
- Each instance keeps a bounded in-memory set of approval ids it has consumed until expiry and refuses a second consumption.
- What remains: an approval can be reused **by the same requester, for the same scope, tool and binding fingerprint, before it expires**, on an instance that did not consume it, if the attacker both deleted the tombstone and restored the entry. This is a narrow, time-limited residual, and 2.3.7 states it as such.

#### 2.3.5 Budgets and rate limits: bounded, not prevented

An attacker can delete or replay a lower count. Sealing cannot detect it.
Proposed bound (OD-7): each instance also keeps its own local count per
(scope, subject) and per caller, and enforces `local <= limit` as well as the
shared limit. Attack worst case then equals the documented `single-node`
behaviour: N times the limit for N instances, and no worse. The shared count
remains the control that makes the limit mean what it says.

#### 2.3.6 Deserialisation filter

Separate from sealing, and in the same option because it is OSS and cheap. Data
Prism stores only `String`, `Integer` and `Boolean`. Configure the 5.7
`java-serialization-filter` with an allowlist and refuse a supplied `Config` or
instance that has none (4.2). Hazelcast's own internal operations use
identified data serialisation under `com.hazelcast.`, which the default prefixes
allow; whether `defaults-disabled` can be set safely is **to verify in the
0.8 spike**, and the spike must also confirm that Jet, user-code deployment and
SQL are off for every config path.

#### 2.3.7 What (c) does not stop

| Attack | Why |
|---|---|
| **Deletion** of any entry | The attacker can always delete a key. Bounded for budgets and rate limits (2.3.5), converted to a denial of service for pauses (2.3.3), a retry for approvals, a recompute for identity. **Re-identification index entries are lost for good** |
| **Replay** of an old valid ciphertext | Not detectable from the ciphertext. Bounded as in 2.3.3, 2.3.4, 2.3.5 |
| **Availability** | Joining, owning partitions, flooding, cluster-state change, member shutdown, and holding locks are untouched. Only the lock hang is mitigated (`tryLock`) |
| **Traffic analysis** | Entry counts, entry sizes, access timing and the cluster name remain visible |
| **A holder of the key** | Section 0.2, A4 |
| **Protocol bugs** | Pre-authentication flaws in Hazelcast's own protocol. Mitigated only by staying on the latest 5.7.x patch and by isolation. The editions page lists "Emergency patches (CVEs)" as an Enterprise benefit, so the open-source line gets fixes only through ordinary releases |

| | |
|---|---|
| Cost | Largest build of the four: a sealing component, a rewrite of `ScopeKeys` and all six stores, the pause document, tests. Per-operation CPU for HMAC, AES-GCM is expected to be small beside a network round trip to the owning member; **the 0.8 soak measures it** (section 5). Memory overhead is the envelope per entry (salt, tag, key id) and the larger plaintext (canonical key parts); also measured |
| Residual risk | Medium-low for confidentiality and integrity; deletion, replay and availability remain as above |
| Operational burden | One more secret to provision, identical on every instance, and to rotate (OD-6) |
| Verifiable by Data Prism | **Yes.** The key resolves and is strong enough, every member holds the same key (4.7), and tampering is counted at runtime |

### 2.4 Option (d): a join secret or member allowlist, OSS only

Honest statement first: **OSS Hazelcast 5.7.0 has no member authentication and
no inbound allowlist.** What remains is weak and is not a control against A2.

- **(d1) A high-entropy cluster name used as a join secret.** It stops *blind* joiners (a scanner, an accidental peer) and nothing else: it is sent in the clear at join (A1 reads it), it appears in Hazelcast log lines (which operators ship and paste), and a client needs only it to connect. Treating it as a secret would require suppressing Hazelcast's own logging of it. Value: stops A3 and blind A2. Recommended at most as hygiene: reject guessable names. It must **not** be described as authentication.
- **(d2) Verified-member detection at the application level.** After join, every member that holds the cluster key registers a sealed *member card* (its member UUID, the cluster key ids it accepts, format version, a lease). The cards are also what the rotation procedure in 4.6 uses to decide which key is safe to write. A joined member with no valid card after a grace period is **unverified**. Data Prism can raise a health failure, a metric and an audit event, and (configurable) refuse to serve. It **cannot evict** the member (no supported OSS API), so it is *detection and fail-closed*, not prevention. An attacker's member keeps receiving ciphertext and can still delete, hold locks and deny service; the operator learns of it within the grace period. This also makes a mismatched key (A3) visible at runtime.
- **(d3) Static `tcp-ip` member lists, `required-member`, Kubernetes API-mode discovery.** These steer *outbound discovery* and say nothing about who may connect inward. Not controls.
- **(d4) `network/interfaces`.** Local bind selection (see 0.4). Part of option (a), not an allowlist.

| | |
|---|---|
| Cost | (d1) trivial. (d2) moderate: card write and refresh, a membership listener, health and metrics |
| Residual risk | **Does not prevent** an attacker joining, reading ciphertext, deleting, holding locks or denying service. (d1) does not stop anyone who has seen a log line or the wire |
| Operational burden | Low |
| Verifiable by Data Prism | (d2) yes, at runtime, not at startup |

### 2.5 Options that need Enterprise: excluded

Excluded from every combination below, on the evidence in 0.4:

- Member TLS and mutual TLS (`SSLConfig` with a context factory, TLS authentication).
- Member authentication, JAAS realms, RBAC, and Hazelcast `SecurityConfig` generally.
- Socket interceptors and security interceptors (a join-secret handshake would be the natural use, which is why they are not available).
- Symmetric encryption (also deprecated).
- Audit log.
- The managed rolling upgrade, hot restart persistence, dynamic configuration persistence, WAN replication, blue/green client filtering.

Data Prism already refuses the TLS and security settings (`HAZELCAST_TLS_UNSUPPORTED`). A home-grown `SSLEngineFactory` or interceptor was rejected in D-0.4.1-A (A2) and stays rejected.

### 2.6 Option (e): combinations

| Combination | Gives | Does not give |
|---|---|---|
| **E1 = (a) + (d1)** | The 0.4.1 position plus a stricter bind check and an attestation | Anything against A2 once the network layer fails |
| **E2 = (c) + (a) + (d2) + serialisation filter** | Confidentiality and integrity enforced by Data Prism and verifiable; unauthorised joiners detected and failed closed; a code-execution hardening; bounded budgets, signed fail-closed pauses | Prevention of join, deletion of re-identification entries, availability against a hostile member |
| **E3 = E2 + (b)** | Everything in E2 plus transport authentication and encryption, which is the only thing that stops an unauthorised join, deletion, replay and lock-holding at the source | Anything if the mesh or network is misconfigured (but then E2 still holds the integrity and confidentiality line) |
| **E4 = (c) alone** | Integrity and confidentiality | Detection and every availability protection; not recommended |

---

## 3. Recommendation

**Recommended (OD-1): E2 as the floor Data Prism enforces, with (b) documented as
the strongly advised deployment for anyone whose threat model includes A2 on the
member network.** In short: seal what we store, bound what cannot be sealed,
fail closed on what we cannot trust, and be exact about what remains.

Reasoning:

1. **It is the only choice where Data Prism enforces and can verify something.** (a) and (b) are attestations. D-0.4.1-E showed that a platform can contradict an operator's belief about isolation. A claim of security that rests only on an unverifiable claim is the weakest form of rule 2 (fail closed).
2. **It closes the most harmful attacks on the list in 0.3** (1, 3, 4, 6, and the confidentiality half of 2 and 5) and converts the pause delete (2) into a safe-direction denial of service. Those are the attacks that make the product's guarantees false, not merely degraded.
3. **It is honest about the rest.** Deletion, replay and availability are listed (2.3.7), bounded where a bound exists (2.3.3, 2.3.4, 2.3.5), and left to the deployer's transport layer where none does. (b) is the only thing that addresses them at source, which is why it is recommended alongside rather than replaced.
4. **It stays inside D-0.4.1-A.** Nothing Enterprise. No dependency added. Algorithms are JDK standard (AES-GCM, HMAC-SHA256); the one hand-written piece is HKDF over `Mac`, validated against the RFC vectors.
5. **It reuses what is there.** `SecretKeyProvider`, multi-key rotation (S2a), string-valued maps, and the boundary 6 test shape.

Costs to weigh against it, and what the owner is buying:

- 0.8 is already named heavy in the roadmap's Risks (cluster security, fan-out rewrite and sealed links land together). (c) is the largest single item in "Cluster security build". OD-11 offers a staged order.
- A new secret to provision and rotate, and a format change that cannot mix with 0.7 members (4.8).
- Application-level cryptography in a privacy path is new attack surface. The envelope and the pause document are in scope for the 0.9 external review, as sealed links are (OD-12).

If the owner prefers less build, E1 plus the serialisation filter is the smallest
honest position, and the documentation must then say plainly that multi-instance
is as secure as the network around it. Whether that is compatible with the
roadmap's wording for 1.0 is the owner's call (OD-1, OD-13).

---

## 4. Proposed configuration, refusal codes, signals, mixed versions

All keys are new and sit under `dataprism.hazelcast.security`. Names, defaults
and refusal codes below are proposals for the 0.8 build. They are subject to the
owner's decisions; those that change with a decision say which.

### 4.1 Configuration keys

Apply only with `topology: embedded` and a join mode other than `none`.
With `single-node` or `join.mode: none` they are refused with the existing
`CLUSTER_SETTINGS_IGNORED`, so a setting never silently does nothing. Secrets
are references, never literals, matching `dataprism.privacy.hmac-key`.

| Key | Default | Validation |
|---|---|---|
| `dataprism.hazelcast.security.mode` | unset | **Required** for `tcp-ip` and `kubernetes`. `sealed`; `isolation-only` only if OD-2 allows it |
| `dataprism.hazelcast.security.cluster-key.key-id` | unset | Required with `sealed`. Must resolve through the key provider |
| `dataprism.hazelcast.security.cluster-key.environment-variable` | unset | Name of the variable holding the key |
| `dataprism.hazelcast.security.cluster-key.provider-reference` | unset | A reference to a key provider |
| `dataprism.hazelcast.security.cluster-key.value` | unset | Refused if set: a literal key is never accepted |
| `dataprism.hazelcast.security.cluster-key.next.key-id` | unset | Optional, phase 1 of a rotation (4.6, OD-6). Read-only key. Must differ from `current` and `previous` |
| `dataprism.hazelcast.security.cluster-key.next.environment-variable` | unset | As above, for the next key |
| `dataprism.hazelcast.security.cluster-key.next.provider-reference` | unset | As above |
| `dataprism.hazelcast.security.cluster-key.previous.key-id` | unset | Optional, phase 2 of a rotation (4.6, OD-6). Must differ from `current` and `next` |
| `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | unset | As above, for the previous key |
| `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | unset | As above |
| `dataprism.hazelcast.security.name-key.key-id` | unset | Required with `sealed`. The separate, non-rolling key for map keys, lock keys and the scope token (4.6). Must differ from every cluster key and from the privacy HMAC key |
| `dataprism.hazelcast.security.name-key.environment-variable` | unset | Name of the variable holding the name key |
| `dataprism.hazelcast.security.name-key.provider-reference` | unset | A reference to a key provider |
| `dataprism.hazelcast.security.name-key.value` | unset | Refused if set: a literal key is never accepted |
| `dataprism.hazelcast.security.isolation.attestation` | unset | Required. One of `network-policy`, `private-network`, `mesh-mtls`, `wireguard`, `ipsec`, `none-acknowledged`. Recorded in the startup audit event and in health. An attestation, not a verification |
| `dataprism.hazelcast.security.pause-lease` | `PT30S` | A duration from `PT5S` to `PT5M`. Sets the pause-replay window and the time to fail closed after the document is lost |
| `dataprism.hazelcast.security.clock-skew-tolerance` | `PT5S` | A duration, at most half of `pause-lease`. Used only to gate the first acceptance of a pause document after a cold start (2.3.3); steady-state freshness uses local monotonic time |
| `dataprism.hazelcast.security.lock-timeout` | `PT2S` | A duration from `PT100MS` to `PT30S`. Wait for a map lock before failing closed. The lease on a held lock is derived from it |
| `dataprism.hazelcast.security.unverified-member.action` | `refuse` | `refuse` or `alert` (OD-8) |
| `dataprism.hazelcast.security.unverified-member.grace` | `PT30S` | A duration from `PT0S` to `PT10M` |
| `dataprism.hazelcast.security.local-floor.enabled` | `true` | Per-instance count enforced in addition to the shared count for budgets and caller rate limits (OD-7) |

The existing `dataprism.hazelcast.member.interface` becomes required for
`tcp-ip` and `kubernetes` **only if OD-10 is decided that way** (4.2), and the existing
`dataprism.hazelcast.tls-key-reference` and `tls-trust-reference` stay refused.

### 4.2 Startup refusal codes

Same style as the existing table: stop before a member starts, except for
`PrivacyCluster.using(instance)`. Codes match `[A-Z][A-Z0-9_]{0,63}`.

| Code | Condition |
|---|---|
| `MISSING_CLUSTER_SECURITY_MODE` | `security.mode` absent with `embedded` and `tcp-ip` or `kubernetes` |
| `UNSUPPORTED_CLUSTER_SECURITY_MODE` | `security.mode` is not an allowed value |
| `MISSING_CLUSTER_KEY` | `sealed` without a `cluster-key.key-id` or a `name-key.key-id`, or either without exactly one of environment variable and provider reference |
| `INVALID_CLUSTER_KEY` | A literal `cluster-key.value` or `name-key.value`; a key that does not resolve; a key below the strength rule used for `dataprism.privacy.hmac-key`; `current`, `next` and `previous` ids not pairwise distinct; the name key equal to a cluster key; or a cluster or name key that is the same key as the privacy HMAC key or a link key (OD-9) |
| `MISSING_ISOLATION_ATTESTATION` | `security.isolation.attestation` absent |
| `INVALID_ISOLATION_ATTESTATION` | An unknown attestation value |
| `MISSING_MEMBER_INTERFACE` | `tcp-ip` or `kubernetes` without `member.interface`. Subject to OD-10 |
| `UNBOUNDED_MEMBER_INTERFACE` | `member.interface` matches every address. Subject to OD-10 |
| `PUBLIC_MEMBER_INTERFACE` | The resolved bind address is globally routable and the attestation is not `mesh-mtls`, `wireguard` or `ipsec`. Subject to OD-10 |
| `INVALID_PAUSE_LEASE` | `pause-lease`, `clock-skew-tolerance` or `lock-timeout` outside its range, or skew above half the lease |
| `INVALID_UNVERIFIED_MEMBER_POLICY` | `unverified-member.action` or `.grace` outside its range |
| `UNSAFE_HAZELCAST_SERIALIZATION` | A supplied `Config` or instance with no allowlisting `java-serialization-filter`, or with user-code deployment, Jet or SQL enabled |
| `CLUSTER_KEY_MISMATCH` | At join, the sentinel already in the cluster does not authenticate under any key this member holds (`current`, `next`, `previous`), or is sealed under a key id it does not hold (4.6, 4.7). A sentinel that authenticates under `previous` is not a mismatch: the member is behind and writes nothing sealed (`rotation.stale_key`). The member leaves without serving on a true mismatch |
| `UNSEALED_CLUSTER_STATE` | The cluster holds entries in the pre-0.8 format in a map this release will not read, and the cutover rule chosen under OD-5 does not allow it |

`HAZELCAST_TLS_UNSUPPORTED` and `UNSAFE_HAZELCAST_DISCOVERY` are unchanged.

### 4.3 Collision with existing names

The closing table in 4.9 lists every proposed key, code, metric and health
name. The Outcome section of the task file records the `grep` commands and
their results over `docs/configuration.md`, `docs/multiple-instances.md`,
`docs/tools.md` and the module sources. The build must repeat the check before
it adds anything, because other 0.8 work adds codes (per-source status codes,
budget codes, fan-out deadlines).

### 4.4 Health and metric signals

`Metric` takes no free-form tag (see its Javadoc), so each signal is its own
constant. None carries a key, a value, a subject, a principal, a scope or a
map entry (boundary 7).

| Signal | Kind | Meaning |
|---|---|---|
| `dataprism.cluster.seal.tamper` | counter | An entry failed authentication, or was a different format or key than expected. A non-zero rate is an attack or a mixed-key deployment |
| `dataprism.cluster.seal.unsupported_format` | counter | An envelope with a format version or key id this member cannot read |
| `dataprism.cluster.seal.salt_rejected` | counter | Salt-cache admissions refused under a spray of distinct salts |
| `dataprism.cluster.members.unverified` | gauge | Joined members without a valid member card |
| `dataprism.cluster.pause.fresh` | gauge | 1 when the pause document is present, authentic and inside its lease; else 0 |
| `dataprism.cluster.pause.age_seconds` | gauge | Age of the accepted document, from local monotonic receipt |
| `dataprism.cluster.pause.conflict` | counter | Equal-order documents with different content were merged by union (2.3.3) |
| `dataprism.cluster.rotation.accepting_next` | gauge | Verified members whose card lists the `next` key (phase 1 gate) |
| `dataprism.cluster.rotation.blocked` | counter | A write was refused because no held key is accepted by all verified members |
| `dataprism.cluster.rotation.stale_key` | counter | This member joined holding only a key older than the cluster's |
| `dataprism.cluster.seal.entries_under_previous` | gauge | Locally owned entries still sealed under the previous key (phase 2 gate) |
| `dataprism.cluster.lock.timeout` | counter | A map lock wait ended in a fail-closed refusal |
| `dataprism.cluster.floor.refusal` | counter | A local floor refused a request that the shared count would have allowed (evidence of reset or replay) |

Health: a `clusterSecurity` health contributor (named like the existing `auditIntegrity`), part of the readiness group and not the liveness group, since restarting an instance does not repair a hostile or mismatched cluster. It is `DOWN` when the pause document is not fresh, when
an unverified member is present and the action is `refuse`, or when the sentinel
check failed. The component reports the isolation attestation value and the
number of members, not addresses. Tamper events also produce an audit record of
the form `CLUSTER_STATE_TAMPER` with a map name constant and a count, no entry
content.

### 4.5 Hazelcast configuration the build forces

Today (0.7), per path:

| Path | Phone-home off | Jet off | Other checks |
|---|---|---|---|
| `embedded(ClusterMembership)` (`toConfig`, then `configure`) | Yes (`ClusterMembership.java:92`, `PrivacyCluster.java:141`) | Yes (`ClusterMembership.java:91`) | Cluster name, advanced network, TLS and security refused |
| `embedded(Config)` (`configure`, `PrivacyCluster.java:134-141`) | Yes (`:141`) | **No** | Same refusals; auto-detection and multicast forced off |
| `using(instance)` (`PrivacyCluster.java:92-102`) | **No** | **No** | Cluster name, advanced network, auto-detection and multicast checked. It cannot change a running instance, and it does not check anything else |

The design enforces, on the two `embedded` paths, by **forcing**: phone-home off,
Jet off, user-code deployment off, the Java serialisation allowlist set when none
is present (a supplied non-allowlisting filter is refused), and `tryLock`
replacing `lock` in the stores. SQL is to be disabled too if 5.7.0 exposes a
supported switch (**to verify in the 0.8 spike**). On `using(instance)`, which
Data Prism did not start and cannot reconfigure, the design can only **check**
and then refuse, warn or accept: what it does for each failed check, above all
a missing serialisation filter, which breaks existing integrators, is OD-16.
Refusals use `UNSAFE_HAZELCAST_SERIALIZATION` (filter) or the existing
`UNSAFE_HAZELCAST_DISCOVERY` family; a new code for Jet or phone-home is not
proposed until OD-16 is decided.

### 4.6 Rotation

**Two secrets, two lifecycles.** (OD-6)

- The **cluster key** (AEAD, 2.3.1) rotates in the phased procedure below.
- The **name key** (map keys, lock keys, scope token; 2.3.2) is configured separately and **does not rotate in the rolling procedure.** Changing it moves every entry to a different map key, so it is a planned full restart with state loss (which for the re-identification index is a loss of the store). Its compromise lets an attacker test guesses against known identifiers and target a delete or a lock; it does not reveal any value. That is why it can be the long-lived one.

Because the name key does not change, two members can never derive different map keys or lock keys for the same entry during a cluster-key rotation. Only the envelope's encryption key varies, and the envelope carries its key id.

**Configuration roles** (4.1): `cluster-key` is the **current** key. `cluster-key.next` is accepted for reading and never written. `cluster-key.previous` is accepted for reading and used for writing only in the fallback below.

**Write-key rule.** A member writes with `current` only if the member cards (2.4, d2) of **all** verified members list it as accepted. Otherwise it writes with `previous` if configured and accepted by all. Otherwise it writes nothing sealed and raises `rotation.blocked`; every operation that needs a write fails closed. Reads accept every key the member holds. This rule is what keeps a key from being written before every member can read it, including a late-joining member that still has the old configuration.

**Phases**, with K1 the old key and K2 the new:

| Phase | Action on each member, one at a time (rolling restart) | Entry condition for the next phase |
|---|---|---|
| **0. Provision** | K2 is provisioned in the secret provider on every instance, referenced by nothing | None |
| **1. Accept** | `current = K1`, `next = K2`. Every member now reads K1 and K2 and writes K1. Cards list K1 and K2 | The `rotation.accepting_next` gauge equals the member count, and no member is unverified |
| **2. Switch** | `current = K2`, `previous = K1`. Members write K2 as soon as all cards list K2 (they do, by phase 1); until then, or while a stale member joins, they fall back to K1. Entries are re-sealed under K2 on read, and a sweeper re-seals the rest (below). A **pause document** sealed under K2 is written only after every member has accepted K2, by the write-key rule | `dataprism.cluster.seal.entries_under_previous` reads 0 on every member for one full pause lease and the longest approval lifetime, and a re-identification re-seal pass has finished |
| **3. Retire** | `previous` removed, one member at a time. K1 is no longer held anywhere | Complete; remove K1 from the secret provider |

**Sweeper.** In phase 2 each member walks the entries it owns locally (`IMap.localKeySet` or a partition-local entry iterator; **to verify** which is available in OSS 5.7.0), opens those sealed under `previous`, and re-seals them under `current` by compare-and-set. It reports the `entries_under_previous` gauge. Re-identification entries are the case that makes this necessary: they cannot be recomputed. Identity entries are skipped (a miss recomputes), approvals are short-lived, and the pause document is rewritten by the lease refresh.

**Sentinel and mismatch (4.7) during rotation.** The sentinel is re-sealed whenever the write key advances. A joining member tries the sentinel against **every key it holds** (`current`, `next`, `previous`). Success under any of them is a pass, and if the key that succeeded is its `previous` the member also raises `rotation.stale_key` and writes nothing sealed until its configuration catches up (it is behind the cluster). A sentinel sealed under a key id the member does not hold, or that fails under all its keys, is `CLUSTER_KEY_MISMATCH`. A joining member whose `next` equals the sentinel's key id passes, because it is ahead, not wrong.

**Failure behaviour.** Skipping phase 1, or retiring K1 early, produces misses and `seal.unsupported_format`, never a read of an unauthenticated value. The cost of getting it wrong is lost re-identification entries and a refused pause document (fail closed), so the phase gates above are metrics an operator or the soak can assert on.

### 4.7 Key agreement and how link keys reach every member

Data Prism never distributes a key through Hazelcast. Each instance resolves the
same key id and reference from the deployment's secret provider, as it does
today for the privacy HMAC key (`ConfiguredSecretKeyProvider`).

- **Cluster key.** Derived subkeys (`dp/cluster/v1/aead/<map>`, `dp/cluster/v1/kcv`) come from labelled HKDF over the cluster key. Map-key and lock-key HMACs come from the separate **name key** (2.3.2, 4.6): `dp/cluster/v1/name/<map>`, `dp/cluster/v1/lock/<map>`, `dp/cluster/v1/scope`.
- **Sentinel at join.** A member writes, if absent, or reads a sealed constant (`dp-sentinel`) carrying the key id. If an existing sentinel does not authenticate, the member refuses with `CLUSTER_KEY_MISMATCH` and leaves. This makes a misconfigured key visible at startup instead of as a stream of tamper counters. An attacker who plants a bogus sentinel before the first legitimate member can only cause that refusal (a denial of service), which is detected and visible. Behaviour during a rotation, including a member whose `previous` or `next` matches the sentinel, is in 4.6.
- **Sealed link keys (task 191).** Links do not use Hazelcast, so there is nothing to distribute: every instance derives the link key from the same configured link key id and reference, by labelled HKDF (`dp/link/v1/...`), exactly as the cluster key. A link issued by one instance is verified by any other that holds the same key. A mismatch would appear only as links being refused uniformly, which an operator cannot tell from an expired link. So the member card carries a **key check value** for each configured purpose key: `first 8 bytes of HMAC(subkey("kcv"), key id)`, sealed under the cluster key. A member that sees a card with a different check value for the same key id raises `dataprism.cluster.seal.tamper` and health `DOWN`. The check value is one-way and short; it adds no usable information about a key that meets the strength rule. Task 191's design should cite this section.
- Whether the cluster key, the privacy HMAC key and the link key are separate secrets is OD-9.

### 4.8 Mixed-version behaviour during a rolling upgrade

Two independent version axes.

**Hazelcast version.** Members on the same major.minor are documented as
compatible regardless of patch (versioning page, "Members"), so a patch bump
(5.7.0 to 5.7.x) can be done by restarting members one at a time. That is a
rolling *restart* and relies on that statement; the managed rolling-upgrade
procedure, and mixed minor versions, are Enterprise only (editions page,
`maintain-cluster/rolling-upgrades`). A Hazelcast minor bump (5.7 to 5.8)
therefore needs a full restart, or a second cluster under a different cluster
name with its own empty state. Whether a mixed-patch rolling restart behaves as
documented is **to verify in the 0.8 spike** and in the soak. Data Prism documents
this and the build adds a compatibility note to the release checklist. It
cannot enforce it beyond Hazelcast's own join refusal.

**Data Prism sealed-format version.**

- The envelope carries `dps<n>`. A member never reads a format it does not know, never reads an unsealed entry in a map it protects, and never writes a format below its minimum. An unreadable entry is a miss plus `seal.unsupported_format`, handled per structure as in 2.3.1.
- New sealed maps use **new names** (for example a `.s1` suffix), because the pre-0.8 maps hold `Integer` and `Boolean` values that a new member would misread. Old and new generations therefore **do not share state** while both are running.
- Consequences during a mixed 0.7/0.8 window, to be documented as a procedure, not left to chance: budgets and rate limits are per generation (effective limits are higher, as with `single-node`); an approval granted on one generation is not usable on the other; **a pause on one generation is invisible to the other.** The last is a safety gap.
- Closing it (OD-5): a 0.8 member in compatibility mode additionally reads the old-format `oversight` map and treats any entry there as an extra pause (additive, so a forged old entry can only cause a denial of service, never lift one), and dual-writes pauses to both. It never reads old-format approvals, budgets, rate limits or identity entries, because those are forgeable by design. The alternative is a flag-day cutover: stop every instance, then start the new ones.
- Within 0.8.x, a later format bump (`dps2`) follows the same rule and ships a reader for `dps1` for one minor release.

### 4.9 Closing table: every proposed name

| Kind | Name | Section |
|---|---|---|
| key | `dataprism.hazelcast.security.mode` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.value` (refused) | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.value` (refused) | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.isolation.attestation` | 4.1 |
| key | `dataprism.hazelcast.security.pause-lease` | 4.1 |
| key | `dataprism.hazelcast.security.clock-skew-tolerance` | 4.1 |
| key | `dataprism.hazelcast.security.lock-timeout` | 4.1 |
| key | `dataprism.hazelcast.security.unverified-member.action` | 4.1 |
| key | `dataprism.hazelcast.security.unverified-member.grace` | 4.1 |
| key | `dataprism.hazelcast.security.local-floor.enabled` | 4.1 |
| code | `MISSING_CLUSTER_SECURITY_MODE` | 4.2 |
| code | `UNSUPPORTED_CLUSTER_SECURITY_MODE` | 4.2 |
| code | `MISSING_CLUSTER_KEY` | 4.2 |
| code | `INVALID_CLUSTER_KEY` | 4.2 |
| code | `MISSING_ISOLATION_ATTESTATION` | 4.2 |
| code | `INVALID_ISOLATION_ATTESTATION` | 4.2 |
| code | `MISSING_MEMBER_INTERFACE` | 4.2 |
| code | `UNBOUNDED_MEMBER_INTERFACE` | 4.2 |
| code | `PUBLIC_MEMBER_INTERFACE` | 4.2 |
| code | `INVALID_PAUSE_LEASE` | 4.2 |
| code | `INVALID_UNVERIFIED_MEMBER_POLICY` | 4.2 |
| code | `UNSAFE_HAZELCAST_SERIALIZATION` | 4.2 |
| code | `CLUSTER_KEY_MISMATCH` | 4.2 |
| code | `UNSEALED_CLUSTER_STATE` | 4.2 |
| metric | `dataprism.cluster.seal.tamper` | 4.4 |
| metric | `dataprism.cluster.seal.unsupported_format` | 4.4 |
| metric | `dataprism.cluster.seal.salt_rejected` | 4.4 |
| metric | `dataprism.cluster.members.unverified` | 4.4 |
| metric | `dataprism.cluster.pause.fresh` | 4.4 |
| metric | `dataprism.cluster.pause.age_seconds` | 4.4 |
| metric | `dataprism.cluster.pause.conflict` | 4.4 |
| metric | `dataprism.cluster.rotation.accepting_next` | 4.4 |
| metric | `dataprism.cluster.rotation.blocked` | 4.4 |
| metric | `dataprism.cluster.rotation.stale_key` | 4.4 |
| metric | `dataprism.cluster.seal.entries_under_previous` | 4.4 |
| metric | `dataprism.cluster.lock.timeout` | 4.4 |
| metric | `dataprism.cluster.floor.refusal` | 4.4 |
| audit event | `CLUSTER_STATE_TAMPER` | 4.4 |
| health contributor | `clusterSecurity` | 4.4 |

---

## 5. Soak and test obligations for 0.8

Roadmap 0.8: "Cluster security build" and "Soak, including the cluster".
These are the obligations the design creates. They assume (c) is chosen; if the
owner chooses otherwise, the unsealed rows fall away and the isolation rows stay.

### 5.1 Tests (all in `data-prism-hazelcast` unless noted)

| # | Obligation |
|---|---|
| T1 | **A member without the cluster key cannot forge an approval.** A second embedded member (and separately a Hazelcast client) with the cluster name but no key writes a plausible plaintext entry, a legacy-format entry, and random ciphertext into `dataprism.approval`; `consumeApproved`, `approve`, `find` and `pending` never return it as usable, and `seal.tamper` increments. Also with a member holding a *different* key |
| T2 | Splice tests: a valid ciphertext copied to another key, another map, another scope, with an extended `expiresAt`, and after expiry is rejected. Bit-flip, truncation, unknown format and unknown key id are rejected |
| T3 | Identity poisoning: arbitrary data written into `dataprism.identity` never changes a returned value; output equals the generator (boundary 6) |
| T4 | Pause: deleting the document makes every instance refuse and sets `pause.fresh` to 0; an older document is rejected by an instance that saw a newer one; an instance that restarts accepts a replayed older document **only inside the lease and the cold-start gate**, and a test asserts the window equals the configured lease; two concurrent writers, one `replace` loses and retries, neither pause is lost; equal-order documents with different content merge by union (OD-14); a member with a skewed wall clock does not make a healthy document stale |
| T5 | Approval replay: the tombstone and the in-memory consumed set bound reuse as stated in 2.3.4; the residual is asserted, not hidden |
| T6 | Budget and rate limit: delete and replay of shared counts never allow more than the local floor permits per instance |
| T7 | **`HazelcastStoredValueBoundaryTest` extended:** no key in any map contains a scope id, subject id, principal id, approval id, case id or pseudonym; every stored string matches the envelope grammar; unknown maps are still rejected; the test holds the key, opens every envelope and still recomputes allowed state from the decrypted canonical key parts and still rejects the distinctive raw-value fixture |
| T8 | Startup: every code in 4.2 is reachable by a table-driven test; `CLUSTER_SETTINGS_IGNORED` for security keys with `single-node` and `none` |
| T9 | `CLUSTER_KEY_MISMATCH`: a member with another key joins a cluster holding a sentinel and refuses; a planted bogus sentinel produces the refusal and no data access |
| T10 | Unverified member: a keyless member joins; `members.unverified` goes to 1, health `DOWN` after the grace under `refuse`, recovery after it leaves |
| T11 | Lock hold: a client locks a budget key, an approval-id key and a caller key; each operation fails closed within `lock-timeout` and none hangs a thread |
| T12 | Serialisation filter: a disallowed class sent from a keyless client is rejected with a `SecurityException`; a supplied `Config` without the filter is refused |
| T13 | Mixed format: a fixture writing the pre-0.8 format and one writing `dps2` into a cluster of 0.8 members; both are ignored per structure, counted, and the old-format `oversight` entry behaves per OD-5 |
| T14 | `ArchitectureTest` (module `data-prism-architecture`): the sealing package is not depended on by `mcp`, `orchestration` or `connectors`; only the re-identification index reads `reidentification` envelopes (boundary 5 shape) |
| T15 | Log, metric and audit scan: tamper, mismatch and refusal paths emit no key, value, subject, principal, scope id or ciphertext (extend `PiiLogScanTest`; boundary 7) |
| T16 | HKDF against RFC 5869 vectors; AES-GCM nonce uniqueness property across threads and across a simulated restart; salt-cache bound under a spray |

### 5.2 Soak (part of "Soak, including the cluster")

- Three members, sealed mode, the load harness (task 196) driving budget, identity, approval and pause traffic. Measure **latency and CPU of sealing against the unsealed baseline** at 100 and 500 sources, and entry size against the 100,000-per-member cap. These are the numbers that decide whether the cost estimate in 2.3 holds.
- Kill and restart a member; kill an owner and its backup together; roll all members. Checks: no unsealed leak, no stale pause accepted outside the lease, re-identification index loss behaves as documented.
- **Rotate the cluster key under load (4.6), concretely.** Three members, the load harness (task 196) at a fixed rate that mixes budget reads, identity lookups, approval create/approve/consume, and re-identification lookups for a pre-created set of 10,000 pseudonyms, with a pause toggled every 5 seconds throughout. Steps: (1) phase 1 rolling restart with `next = K2`; assert `rotation.accepting_next` reaches 3 and no more than the restarting member's in-flight requests are refused. (2) Phase 2 rolling restart with `current = K2`, `previous = K1`; during it start a fourth member holding K1 only and assert the K2 writers fall back to K1, `rotation.stale_key` increments on the fourth, and no pause is missed by any member. (3) Wait for `entries_under_previous = 0` on every member. (4) Phase 3, retire K1 one member at a time. Pass criteria: `seal.tamper` stays 0 outside the stale-member step; pause propagation never exceeds the configured lease; every one of the 10,000 pseudonyms still resolves; an approval created before phase 2 and consumed after it succeeds exactly once. Negative run: retire K1 on one member while the gauge is non-zero and assert the loss is detected (`seal.unsupported_format`) and not served. Name-key change is exercised separately as a full restart.
- A hostile member joins under load: tamper flood. Confirm CPU stays bounded (salt cache, failure counting), health goes `DOWN` per policy, and recovery after it leaves.
- Network partition with a pause issued on one side: lease behaviour, split-brain approval consumption (see NOTED in the task file), and rejoin.
- Clock skew injected on one member: pause lease and `expiresAt` behave per the tolerance.
- Environment verification, run once per supported platform and recorded: a capture on the member port shows no plaintext **only in the E3 profile**; the isolation claims in `docs/multiple-instances.md` are tested rather than inherited from D-0.4.1-E.

---

## 6. Boundaries and open decisions for the owner

### 6.1 Boundaries (`docs/architecture.md`, 144-260)

**Boundary 5 (re-identification is never an MCP tool).**
- The cluster key is not an operator credential and not an MCP credential; a holder of one is not thereby authorised for the other.
- Re-identification values are sealed under a subkey of their own: the subkey for the `dataprism.reidentification` map is derived with `info = "dp/cluster/v1/aead/dataprism.reidentification"` (2.3.1), different from every other map's, and the keyed map key hides the pseudonym. A member without the key, and an MCP client, cannot read the index. Separation between maps here is cryptographic (distinct subkeys) and also bound in the associated data; it is not separation between members that hold the cluster key, who can derive every subkey (A4, 0.2).
- The one decrypt-and-return site stays `ScopeIdentityIndex.subjectFor`, so `ArchitectureTest.onlyReidentificationCallsSubjectFor` keeps its meaning. T14 adds the rule that the sealing package is not reachable from `mcp`, `orchestration` or `connectors`.
- Sealed links never use the index (task 191) and never touch Hazelcast. The only thing a link key shares with the cluster is a check value in the member card (4.7), which is one-way.

**Boundary 6 (Hazelcast never holds raw sensitive values).**
- Today subject ids sit in plain in three keys, and the boundary names that as the reason the member port must be isolated. After this design **no key contains a subject id, scope id, principal id, approval id or pseudonym in the clear**, and no value is readable without the key. Subject ids exist only inside authenticated ciphertext. The boundary strengthens.
- The boundary text assumes the member network is hostile-by-default only through isolation. With (c) it is also protected without isolation, for confidentiality and integrity. It is not protected for deletion or availability, and the text must say so.
- `HazelcastStoredValueBoundaryTest` must keep its strongest property, recomputation from decomposed keys. Because keys become one-way, the canonical key parts move into the sealed payload (2.3.1), which T7 relies on. The "reject unknown maps" and "reject the raw-value fixture" parts are retained.
- Rule 1 (no path bypassing the privacy engine) is unaffected: nothing here lets a source adapter reach `mcp`.

**Boundary 7 (no sensitive value in logs, metrics, exceptions or audit).**
- Hazelcast's own log lines include the cluster name and member addresses; neither is a sensitive value, and no secret is placed in either. A high-entropy cluster name must not be treated as a secret for that reason (2.4).
- Refusal messages name the field and never its value (the convention in `PrivacyClusterRefusal`); the new codes follow it. Keyed map keys are pseudonymous but are still not logged.
- Metrics have no tags and no values (4.4). `CLUSTER_STATE_TAMPER` audit records carry a map-name constant and a count.
- JCA failures are caught at the sealing boundary and reported as a counter and a constant message, never as the exception text or the input.
- T15 turns this into a test. Note that boundary 7 is only *partially* enforced today (metric labels and trace attributes are not scanned); the new signals depend on code review for that half until a later task scans them (task 194 covers the key-shape scan in the leak validators only).

### 6.2 Open decisions for the owner

Each is the owner's to decide. Recommendations are the author's.

**OD-1. Which position does 1.0 take on multi-instance security?**
- (i) **E2 with (b) advised**: seal what is stored, detect unverified members, fail closed, bound what cannot be sealed. Largest build.
- (ii) E1 plus the serialisation filter: isolation attested and checked; no sealing. Smallest build; the product is exactly as secure as the network.
- (iii) E3 as a requirement: refuse to start without an attested mesh or encrypted network. Not verifiable; adds a hard dependency on deployer infrastructure.
- **Recommend (i), on its merits and independent of the roadmap's wording.** It is the only option under which Data Prism itself enforces and can test something about cluster protection: it closes approval forging, identity poisoning and disclosure of the re-identification index and of subject identifiers, which are the attacks that make the product's central guarantees false. Rule 2 (fail closed) argues against shipping a multi-instance mode whose safety is an unverifiable attestation, which is what (ii) and (iii) are. The price is the largest build in 0.8, a new secret pair, new cryptography to review, and a state-format break from 0.7. What (i) does not buy is in 2.3.7 and in OD-13.

**OD-2. Is an isolation-only mode (no cluster key) allowed for a multi-member cluster?**
- (i) Not allowed: any cluster with more than one member requires `sealed`.
- (ii) Allowed with `security.mode: isolation-only`, an attestation, and a startup WARN plus health note.
- (iii) Allowed in 0.8 and 0.9, removed at 1.0.
- **Recommend (i).** Rule 2 (fail closed): an unclassified cluster is refused. The cost to an operator with good isolation is one secret. (iii) is rejected because it would freeze a mode in the 0.9 surface only to remove it, which breaks the stability promise.

**OD-3. How are pauses protected against deletion?**
- (i) The signed, sequenced, leased document in 2.3.3, with fail-closed on absence.
- (ii) Seal each flag only. A delete still lifts a pause; documented as a known limit.
- (iii) Keep plaintext flags and rely on isolation.
- **Recommend (i)**, with the compare-and-set, equal-order and monotonic-freshness rules in 2.3.3 (the merge and rewrite choices are OD-14 and OD-15). A pause is a safety control, and a delete that silently lifts it is the worst result in the inventory. The cost is a design with a lease, a clock-skew tolerance and a bootstrap rule. Consequence to accept: while the cluster is unreachable beyond the lease, an instance refuses everything, which is consistent with how budget already fails closed.

**OD-4. What should an instance do when the pause document is stale or absent?**
- (i) Refuse every call (equivalent to a global pause) until a fresh document is seen.
- (ii) Refuse only calls that read subject data; allow `list_sources` and similar.
- (iii) Continue on the last known state and alert.
- **Recommend (i).** Simple, safe, and testable. (iii) reintroduces the attack for the length of the outage.

**OD-5. How do existing 0.7 clusters cut over to sealed state?**
- (i) Flag day: stop all instances, start the new ones. Everything in memory is lost, including the re-identification index.
- (ii) Rolling with the compatibility behaviour in 4.8: new members dual-write pauses and read the old `oversight` map as an additional pause only; nothing else is read from the old format.
- (iii) Rolling with a one-time migration that re-seals re-identification entries read from the old map.
- **Recommend (ii).** Pauses cross the generations; forgeable structures do not. For deployments with re-identification on, (iii) is available as an explicit operator-run step, because migrating means trusting entries that could have been forged under 0.7; it should not be automatic. How many deployments run 0.7 clusters with re-identification on is a question for the owner, not an assumption made here.

**OD-6. How must the cluster key rotate for 1.0?**
- (i) Phased rolling rotation of the cluster key (4.6), with a separate, non-rolling name key. Changing the name key is a planned restart with state loss.
- (ii) Both keys rotate in the rolling procedure, by dual-probing map and lock keys under the current and previous name keys. More code and more states in the lock path.
- (iii) Rotation of either key requires a full restart and loses all state, documented.
- **Recommend (i).** The only structure that cannot be recomputed is the re-identification index, and it needs the cluster key to rotate without loss. Splitting out the name key is what removes the one hazard that makes rolling rotation unsafe (members deriving different keys for one entry). (iii) is defensible if the owner decides clusters will not run re-identification at 1.0.

**OD-7. Local floor for budgets and rate limits?**
- (i) Enable by default (2.3.5): worst case equals `single-node` behaviour.
- (ii) Shared counts only; document that a hostile member can reset them.
- **Recommend (i).** It turns an unbounded risk into the already documented bound at small cost. Residual: counts reset on a restart, as today.

**OD-8. What happens when an unverified member joins?**
- (i) `refuse`: health `DOWN` and the instance stops serving after the grace.
- (ii) `alert`: health stays up, metric and audit event only.
- **Recommend (i)**, with `alert` as an explicit choice, for the same fail-closed reason. Consequence: an attacker who can join can take every instance out of service. They could do worse anyway (deletion, locks), and the alternative is silence.

**OD-9. One key or several?**
- (i) Separate secrets for the cluster key, the privacy HMAC key and the link key, each with labelled subkeys.
- (ii) One master secret with labelled derivation for every purpose.
- **Recommend (i)**, which here also means the name key (4.6) is a fourth secret. A leaked cluster key (which sits on every instance's configuration and is read by every member) should not expose the pseudonym key or the link key. The cost is three references to provision. This also needs task 191 to agree.

**OD-10. Do the interface checks (required, unbounded, public) ship? Also decides whether `member.interface` becomes required.**
- (i) Yes: `MISSING_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE`, `PUBLIC_MEMBER_INTERFACE`.
- (ii) Only the first two; address classification is a heuristic.
- (iii) None; documentation only.
- **Recommend (ii)** until the spike shows address classification is reliable on the supported platforms. A false refusal on a legitimate cloud address is a support cost; a wrong accept is a false assurance.

**OD-11. Scope and order of the 0.8 build.**
- (i) All six maps, the pause document, member cards and the serialisation filter in one change set.
- (ii) Staged inside 0.8: the filter and startup checks first, then approval, oversight and re-identification, then identity, budget and caller rate, then member cards.
- (iii) Defer some structures to 0.9.
- **Recommend (ii)**, all landing before the 0.8 tag. Partial sealing leaves gaps in the test matrix; staging only orders the work so the first wave removes the harms ranked highest in 0.3. (iii) conflicts with the 0.9 API and configuration freeze.

**OD-12. External review scope.**
- (i) Include the sealing envelope, key derivation and the pause document in the 0.9 external security review alongside sealed links.
- (ii) Review sealed links only.
- **Recommend (i).** New cryptography in a privacy path; the same reasoning the roadmap applies to links.

**OD-13. What may the documentation and the 1.0 release notes claim about clustering?**
D-RM-1 as recorded in `PLAN.md` says only that 1.0 supports multiple instances, with the security designed in 0.7 and built in 0.8. It does not define "secured". The options:
- (i) "Multiple instances are supported. Cluster state is sealed against reading and forging by anyone without the cluster key. Transport protection and network isolation are the deployer's responsibility and are not verified by Data Prism. A party that can reach the member port can still delete or replay entries, hold locks, join as an unverified member and deny service (budgets and rate limits are bounded by a per-instance floor; pauses fail closed; re-identification entries can be lost to deletion)".
- (ii) "Multiple instances are secured" without qualification. Not supportable on open-source Hazelcast: it would overstate what 2.3.7 lists as not stopped.
- (iii) "Multiple instances are supported; secure them with network controls" with no application-level claim (the OD-1 (ii) position).
- **Recommend (i).** It states exactly what is enforced and what is not, in the same terms as 2.3.7, so a reader can check the claim against the tests in section 5. It requires the roadmap line "supported and secured" to be reworded by the owner.

**OD-14. Pause document: split-brain merge and equal-order rule.**
- (i) Union of the pause sets on any conflict or merge (2.3.3). Fails closed; a resume on one side of a partition may be undone and must be repeated.
- (ii) Highest (sequence, refresh) wins. Simpler; a pause issued on the smaller side of a partition is silently dropped, which lifts a pause.
- (iii) Refuse everything until an operator re-issues the state.
- **Recommend (i).** Rule 2: when the state is in doubt, the safe direction for a pause is paused.

**OD-15. May a member re-write a missing pause document from its own memory?**
- (i) Yes, with `putIfAbsent`, only from a document accepted within the last lease, and only if no verified member reports a higher sequence (2.3.3).
- (ii) No. An absent document stays a global pause until an operator issues a pause or resume, which writes a new one.
- (iii) Yes, unconditionally.
- **Recommend (i).** It heals a deletion without operator action, and the conditions prevent a partitioned member from re-writing an older state. (ii) is the more conservative choice; choose it if self-healing after a hostile delete is judged less valuable than having no automatic writer. (iii) is rejected because it lifts pauses.

**OD-16. What does `PrivacyCluster.using(instance)` do about settings it cannot change?**
`using` wraps an instance an integrator started. It cannot add a serialisation filter, turn off Jet, user-code deployment or phone-home (4.5).
- (i) Refuse (`UNSAFE_HAZELCAST_SERIALIZATION` for a missing or non-allowlisting filter, an `UNSAFE_*` code for the rest). Breaks integrators who pass an unfiltered instance, and needs a release note.
- (ii) Accept, log a warning and report `clusterSecurity` health as degraded.
- (iii) Accept only with an explicit acknowledgement property naming the unmet settings.
- **Recommend (i) in `sealed` mode.** An unfiltered member protocol is a code-execution surface that sealing does not address, and the integrator can set the filter on their own `Config` with one block. (iii) is the fallback if the owner wants the break softened.
