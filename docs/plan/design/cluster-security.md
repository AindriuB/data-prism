# Cluster security on open-source Hazelcast

Design for task 192. Internal planning document under `docs/plan/`, which mkdocs
excludes. No code and no configuration change accompany it: 0.8 builds what is
described here, and 0.8 is also where `docs/multiple-instances.md` and
`docs/configuration.md` change.

Status: **approved.** The owner confirmed the sealed-state design on 2026-10-19
(`docs/plan/reconciliation-2026-10-19.md`): sealed state, encrypted
re-identification values, separate cluster and name keys, no isolation-only
mode, no Enterprise feature. Every choice that was the owner's to make is marked
**OD-n** and collected in section 6.2, each with options and a recommendation.
The owner delegated every decision in section 6.2 to its recommendation on 2026-10-09 ("i trust your recommendations"); each is marked "Decided (owner delegated)" there, with the options kept visible. Where the text
says "recommended" it means the author's recommendation, not a decision.

Post-approval amendments (task 218, 2026-10-20). They bring this document into line with the 0.7 rework of the Hazelcast approval store (tasks 202, 216, 232, 233, 235, 236), the contract register's decisions (`contract-register.md`, section 14) and the owner's decisions in `docs/plan/tasks/OD-215-open-decisions.md`. Nothing else in the design changed.

- The key `dataprism.hazelcast.security.mode` and its two codes are withdrawn (OD-200-9, register D8). A joining member is sealed by definition.
- The key `dataprism.hazelcast.security.lock-timeout` is withdrawn: the shipped `dataprism.hazelcast.lock-timeout` governs (register D7, C3).
- The key-failure codes follow the family of OD-200-10: `CLUSTER_KEY_*` and `NAME_KEY_*`; `INVALID_CLUSTER_KEY` is withdrawn (OD-215-3 (a), 4.2).
- The remaining `dataprism.hazelcast.security.*` keys are Spring-bound, typed, bounded and pinned, each refused at startup with its own code (OD-200-14, 4.1). Their applicability on `join.mode: none` is OD-215-8 (a) (4.1).
- Metric names use `_` inside a segment (OD-200-7) and every signal is one `Metric` constant (OD-200-15, 4.4).
- Gauges are emitted through a default no-op `gauge` method on `PrivacyMetrics` (OD-215-5 (a), 4.4).
- The envelope has a nonce field and `expiresAt` sits inside the authenticated plaintext (OD-215-9 (a), 2.3.1).
- The key-derivation labels use the prefix `dataprism/cluster/v1/` and are listed once, in 4.7 (OD-215-4 (a)). The key check value formula is OD-215-7 (a) (4.7).
- Approvals keep the 0.7 key shape: entries are scoped, id claims are keyed by id alone (OD-215-2 (a), 2.3.2). The 0.7 maps `dataprism.approval.id` and `dataprism.approval.pending` are inventoried (1.5a, 1.5b).
- The 0.7 to 0.8 cutover is non-rolling and the compatibility mode of OD-5 is withdrawn; `UNSEALED_CLUSTER_STATE` enforces it (OD-215-1 (a), 4.8).
- Owner decision 2026-10-21, extends OD-215-1: every serving 0.8 member keeps a permanent pause entry (the fence) in the pre-0.8 `dataprism.oversight` map, so any 0.7 member that joins reads the cluster as paused. `UNSEALED_CLUSTER_STATE` refuses start only when no verified 0.8 member is present, and ignores the fence; once a verified 0.8 cluster exists, pre-0.8 entries are counted and logged, never a refusal (4.8). This adds the metric `dataprism.cluster.legacy.entries` (4.4).
- Tamper is a metric plus a rate-bounded WARN log line, and `CLUSTER_STATE_TAMPER` as an audit record is withdrawn; `isolation.attestation` is reported on health and in one startup log line and is not audited (OD-215-6 (b) and (c), 4.4).
- Coordinator decisions, 2026-10-21 (code review of task 218), each recorded where it applies: every lock key is the keyed map key of the entry it guards and there is no separate lock label (2.3.2, 4.7); every compare-and-set or removal on a key that can be locked runs under a bounded `tryLock` or a timed variant (2.3.3, 4.6); an unverified member is a metric, a rate-bounded WARN line and a health detail, never an audit event (2.4, OD-8); the consumed-approval map has no size eviction, a TTL of expiry plus the skew tolerance, a union merge and the approval quorum (2.3.4); the serialisation allowlist covers every Data Prism entry processor and merge policy (2.3.6, T12).
- The client-visible codes for a pause document that is not fresh, a member that is not serving and `rotation.blocked` are existing codes (OD-215-10 (a), 4.4).

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
cluster share eight maps in 0.7 (inventory in section 1); 0.8 replaces them with sealed successors and adds two (4.8). The member protocol is plain
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

All eight 0.7 maps are declared in `PrivacyCluster` (`IDENTITY_MAP` and the others).
They are configured by `privacyMap`: backup count 1, no `MapStore`, LRU at 100,000
entries per member, except `oversight`, `callerrate` and the two approval guard
maps (1.5a, 1.5b; `approvalGuard`), which are never evicted. The three approval
maps are under the `dataprism.approval.quorum` split-brain protection when
`approval-minimum-members` is above 1. Keys are `String`; the
separator is NUL (`ScopeKeys`). Values are `String` except `budget`
(`Integer`) and `oversight` (`Boolean`). This section describes the maps under
their 0.7 names and formats. The 0.8 successors have new names (4.8).

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

`HazelcastScopeBudget.tryRead` locks the key with `ClusterLocks.acquire`
(`tryLock` with a wait of `dataprism.hazelcast.lock-timeout`, default `PT2S`, and a
lease of five times that, tasks 202 and 216). An attacker who locks a known key
makes every reader of it wait the timeout and then be refused with
`CLUSTER_LOCK_TIMEOUT`: a bounded denial of service, not a hang.

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
| Value | 17 NUL-separated, escaped fields (task 232, "Value formats"): approval id, kind, requester principal and client, scope, tool, binding fingerprint, namespace, synthetic value, purpose, case id, created and expiry, **status**, approver principal, decided-at, and the 17th, the **creation token** (`=` and a random 36-character UUID that carries no personal data) (`HazelcastApprovalStore.Codec`) |
| Lifetime | TTL to the request's `expiresAt`; a decision commits its new value and its expiry in one step |
| **Read** | Principals, case ids, purposes and tools: who is asking to see what, and why |
| **Write** | **Forge an `APPROVED` entry** naming any approver, or flip `PENDING` to `APPROVED` and so bypass four-eyes (`SELF_APPROVAL` is a check in `approve`, which an attacker skips by writing the entry directly). Flip a legitimate entry to `CONSUMED`, `REJECTED` or `EXPIRED` to deny it. Rewrite a `CONSUMED` entry back to `APPROVED`: use an approval a second time |
| **Delete** | Discard pending approvals: deny service |
| Recoverable | No. Requesters must ask again |

In 0.7 an entry is usable only while the id claim names its key and token
(1.5a). That does not stop a restored `APPROVED` value of the same creation: the
token matches, and 2.3.4 bounds that.

Lock keys (each taken with `tryLock`, in this order, by `ClusterLocks.acquire`; each is the map key of the entry it guards, in the same map):
the bare approval id on `dataprism.approval.id`, the pending key on
`dataprism.approval.pending` (1.5b, which embeds the requester principal), and
the approval's own key (`scope NUL id`) on `dataprism.approval`. All three are
visible to the same attacker.

### 1.5a `dataprism.approval.id` (0.7: tasks 216 and 232)

| | |
|---|---|
| Key | the bare **approval id** |
| Value | the claim: `scope NUL id NUL token`, naming the approval entry that holds the id. A claim being taken over or swept is marked: `scope NUL id NUL token NUL ~ NUL scope2 NUL id NUL token2` (or `... NUL ~ NUL` for a sweep) |
| Lifetime | None. A claim is valid while the entry it names exists; a stale claim is taken over or swept. Never evicted; `PutIfAbsentMergePolicy` after a heal; the approval write quorum when set |
| **Read** | Which approval ids exist, and the scope each belongs to |
| **Write** | Claim an id first, so the real request is refused as a duplicate. Remove or rewrite a claim, so a legitimate approval is unusable (`find`, `approve`, `reject` and `consumeApproved` use an entry only while the claim equals `entryKey NUL token`) |
| **Delete** | Free an id; deny service to the approval it named |
| Recoverable | No. Requesters must ask again |

Lock key: the approval id.

### 1.5b `dataprism.approval.pending` (0.7: tasks 216 and 232)

| | |
|---|---|
| Key | `KIND NUL =requester principal`, or `KIND NUL ~` when there is none (`HazelcastApprovalStore.pendingKey`) |
| Value | `PendingSet`: triples `id NUL expiry NUL owner`, where the owner is `scope US token`, where `US` is U+001F (empty scope for a null scope), or `\u0001conflict` after a heal |
| Lifetime | None; never evicted. `PendingSweeper` bounds its retention every `approval-sweep-interval`. `PendingMergePolicy` merges after a heal (union of ids, later expiry) |
| **Read** | Which principals hold pending approvals, how many, and for which ids |
| **Write** | Fill a requester's slots so `TOO_MANY_PENDING` denies them; empty a requester's slots to lift the cap |
| **Delete** | Lift the pending cap for a requester |
| Recoverable | No. The cap is a safety control that a delete lifts |

Lock key: the pending key.

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

- **Locks.** Map locks on the keys above. In 0.7 every lock the stores take goes through `ClusterLocks.acquire` (`tryLock`, wait at most `dataprism.hazelcast.lock-timeout`, lease five times that), so a lock an attacker holds no longer hangs a request thread: the call is refused with `CLUSTER_LOCK_TIMEOUT` (`HazelcastScopeBudget`, `HazelcastCallerRateLimiter` and `HazelcastApprovalStore`, tasks 202, 216 and 232). Two things remain. The lock keys are plain (the budget key, the principal, the approval id, the pending key), so an attacker knows what to lock; the lock timeout bounds the harm to a denial of service, and in 0.8 each lock key is the keyed map key of the entry it guards (2.3.2). And `ScopeIdentityIndex.endScope` still deletes the identity, re-identification, budget and oversight keys of a scope without a lock bound, so a key another member holds makes that delete wait (a documented 0.7 limitation); only the approvals of the scope are bounded.
- **Partitions.** A joined member is given partitions. If an attacker holds an owner and its backup of an entry, it controls that entry's availability.
- **Cluster administration.** A joined member or client can attempt cluster-state change, member shutdown and other administrative operations. Not confirmed per operation for 5.7.0 OSS. **To verify in the 0.8 spike**, and treat as an availability risk regardless.
- **Deserialisation.** The member protocol carries Java-serialised objects. Without a filter, a class on the member classpath that is exploitable on deserialisation is reachable by A2. This is a code-execution risk, not a data risk, and neither sealing nor isolation-by-attestation addresses it. See 2.3 and 4.5.
- **Jet, phone-home and user-code deployment.** Only `ClusterMembership.toConfig` disables Jet and phone-home. `PrivacyCluster.configure` (the `embedded(Config)` path) forces phone-home off but not Jet. `PrivacyCluster.using` forces neither and checks neither. Section 4.5 says what the design enforces on each path.

### 1.8 What an attacker gains, by category

| Harm | Maps |
|---|---|
| Disclosure of subject identifiers | identity, budget (keys); reidentification (values) |
| Disclosure of the re-identification index | reidentification |
| Disclosure of callers, cases, purposes | callerrate, approval, approval id, approval pending |
| Forged control decision | approval, approval id, oversight |
| Resetting a limit | budget, callerrate, approval pending (the per-requester cap) |
| Corrupted identity | identity, reidentification |
| Denial of service | all, plus locks and partitions |

### 1.9 `dataprism.membercard.s1` (proposed; not in 0.7)

| | |
|---|---|
| Key | Keyed hash of the member UUID (2.3.2). One reserved key, the literal `sentinel`, holds the sentinel (4.7): it cannot be the keyed hash of a UUID, because those keys are 43 characters of URL-safe base64 and `sentinel` is eight. Card readers skip the reserved key |
| Value | Sealed card: member UUID, accepted cluster key ids, format version, lease (2.4, d2) |
| Lifetime | A card: the lease (`pause-lease`), refreshed by its member. The sentinel: none; it is never evicted and has no TTL |
| **Read** | Without the key: member count and card sizes only |
| **Write** | Without the key: cannot forge a card (AEAD; associated data binds the map key, which is the keyed hash of the member UUID). Garbage is a tamper count |
| **Delete / replay** | Make a member unverified, or hide an accepted key, until the next refresh: fail-closed effects only (2.4, 4.6) |
| Recoverable | Yes. Each member re-writes its own card |

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
- The operator must state how the member port is isolated (an enumerated attestation, 4.1). The value is reported in health and in one startup log line; it is not an audit record (OD-215-6 (c)). It is an **attestation, not a verification**.

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
attestation (`mesh-mtls`, `wireguard`, `ipsec`) and report it (health and one startup log line). Sniffing for a
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

- **Envelope** (a `String`, so no serialiser has to agree across members, the property `ScopeKeys` already protects), decided under OD-215-9 (a): `dps1.<key id>.<salt>.<nonce>.<ciphertext+tag>`. `dps1` is the format version. The salt (128 bits), the nonce (96 bits) and the ciphertext with its tag are URL-safe base64, which has no `.`. A **key id may not contain `.`**, so every field splits unambiguously on `.` and an envelope has exactly five fields; a configured key id with a `.` is refused at startup with the area's `_KEY_UNRESOLVED` code (4.2).
- **Algorithm:** AES-256-GCM.
- **Nonce uniqueness is by construction, not by luck.** A random 96-bit nonce under one long-lived key is limited to about 2^32 encryptions, and `budget` rewrites an entry on every counted read. Recommended: each process draws a random 128-bit **salt** at start and derives, per map, a subkey `HKDF(clusterKey, salt, info = the `aead` label of 4.7 for the map)`; it uses a 96-bit counter as the nonce under each subkey. The salt and the nonce are both fields of the envelope, so a reader derives the same subkey for the map it is reading (cached, bounded, LRU, keyed by salt and map) and needs nothing else to open the value. The label is in 4.7. No nonce is ever reused under a subkey, and a ciphertext from one map is not decryptable as another map's even before the associated data is checked. The cost is one HKDF per distinct (salt, map) on the read side; an attacker spraying salts burns CPU, so the salt cache is bounded and failed authentications are rate-bounded and counted (section 4.4).
- **Associated data** binds, in this order: the format version (`dps1`), the key id, the salt, the map name and the (opaque) map key. The nonce is not in the associated data; it is an input to the cipher. A ciphertext copied to another key, another map or another salt, or opened under another key id, fails to authenticate.
- **`expiresAt` is authenticated inside the plaintext, not in the associated data.** The previous draft put `expiresAt` in the associated data, which a reader cannot know before it opens the value. The plaintext of an entry with a lifetime now begins with `expiresAt`, so the tag authenticates it: an attacker cannot extend a lifetime without the key. The reader opens the value first, then enforces `expiresAt` itself and does not rely on the cluster TTL.
- **Plaintext carries the canonical key parts** (scope, namespace, subject, principal and so on) so a holder of the key can decompose any entry. This keeps the boundary 6 test (`HazelcastStoredValueBoundaryTest`) able to recompute state from stored data, and lets scope clean-up scan values where keys are opaque.
- **Failure behaviour.** A value that fails authentication is a **miss with a tamper signal**, never an exception that escapes and never a pass-through. Per structure: `identity` recomputes (the property boundary 6 already guarantees); `reidentification` returns empty; `budget` refuses the read (fail closed); `approval` reports not found; `callerrate` treats the window as unknown and refuses (fail closed); `oversight` per 2.3.3.

#### 2.3.2 Keyed pseudonymous map keys

- Key = `HMAC-SHA256(nameSubkey(map), canonical key)` (written `mapKey(map, canonical)` below), URL-safe base64 **without padding** (43 characters; the alphabet has no `.`), so subject ids, principals, approval ids and pseudonyms no longer appear in any key. `nameSubkey` comes from a **name key** that is a separate secret from the cluster key and is **not rotated in the rolling procedure** (4.6). The reason is that an HMAC'd map key or lock key that changed with the encryption key would give two members different keys for the same entry during a rotation, so each would miss the other's entries and take different locks for the same logical lock.
- **Scope prefix is kept as a token.** Scope end currently depends on `startsWith(scopePrefix)` (`ScopeIdentityIndex.endScope`, `HazelcastScopeBudget.forget`, `HazelcastApprovalStore.keysOf`). The new key is `scoped(map, scope id, canonical) = scopeToken + "." + mapKey(map, canonical)`, where the separator is a single `.`, and `scopeToken = HMAC(scopeSubkey, scope id)` truncated to 128 bits and encoded as unpadded URL-safe base64 (22 characters), so a scope's prefix is `scopeToken + "."`. In every `mapKey`, `scoped` and `nameSubkey` below, `map` is the **full name of the sealed map including the generation suffix** (for example `dataprism.approval.id.s1`), never the short word (`claims`, `pending`, `consumed`, `approval`) that this text uses for readability. Prefix scans continue to work; the scope id itself is hidden.
- **Approval key shape (OD-215-2 (a)): the 0.7 shape is kept.** Entries in the approval map are keyed `scoped(approval, scope id, approval id)`, with the empty scope id for an approval that has none. The id claims in `dataprism.approval.id` are keyed `mapKey(claims, approval id)`, with no scope token, so one id has one claim across every scope and the lookup by id alone that `locate` needs (it ignores scope) stays a single `get`. Scope end stays a prefix scan of the approval map by scope token; it opens only the entries it finds, to read each one's id and creation token, and releases the claim and the pending slot as `forget` does in 0.7. The pending map is keyed `mapKey(pending, pending key)`, where the canonical pending key is the 0.7 `KIND NUL (=requester or ~)` (1.5b). The consumed-approval tombstones (2.3.4) are keyed `mapKey(consumed, approval id)`. The alternative of an id-only key with no scope token, which would have scope end decrypt the whole approval map and rewrite the 232 claim protocol, was not taken.
- **A lock key is the keyed map key of the entry it guards** (coordinator decision 2026-10-21, following brief 223): the lock is taken on the same key in the same map, as in 0.7. There is no separate lock key and no lock subkey. A foreign lock on the entry therefore makes our `tryLock` time out with `CLUSTER_LOCK_TIMEOUT` instead of hanging a later `replace` or `remove` on that key. The 0.7 locks are the approval id on the claim map, the pending key on the pending map and the approval's own key on the approval map (1.5), taken in that order; in 0.8 each is the `mapKey` or `scoped` key of the same canonical string, so the lock order and the lock count of task 232 do not change, and no lock key leaks a principal or an id.
- **Locks use `tryLock` with a bounded wait and a lease**, not `lock`, so an attacker's lock cannot hang a request thread and cannot be held forever. Timeout fails closed. 0.7 already does this (`ClusterLocks.acquire`, `dataprism.hazelcast.lock-timeout`); 0.8 keeps that key, and the lock keys are the keyed map keys (above).

#### 2.3.3 Oversight (pauses) need a different design

Sealing a `Boolean` does nothing for the dangerous attack, which is a **delete**.
Recommended design (OD-3):

- Pause state is **one sealed document** under a fixed, keyed name: the full set of pauses, a **sequence** number, a **refresh** counter, an **issued-at** time and the issuing member. Pause and resume write a new document with `sequence + 1` and `refresh = 0`. A lease refresh re-seals the same pauses with `refresh + 1`. Documents are ordered by (sequence, refresh), compared lexicographically.
- **Writes are compare-and-set.** Every write is `IMap.replace(name, oldSealedValue, newSealedValue)` (replace-if-equal on the exact sealed string the writer read), or `putIfAbsent` when it believes the document is absent. A lost race is re-read and retried, never overwritten, so two members cannot silently clobber each other's pause or resume. Each instance keeps the highest (sequence, refresh) it has accepted in memory and **never accepts a lower one**. A deleted entry cannot un-pause an instance that has already seen the pause. A replayed older document is rejected by every instance that saw a newer one.
- **Bounded writes (coordinator decision 2026-10-21; spike 219 answer 10).** `IMap.replace(key, old, new)`, `remove(key, value)`, `set`, `put`, `putIfAbsent` and `delete` wait without bound on a key another owner has locked. So every compare-and-set or removal on a key that can be locked (the pause document, the sweeper's re-seals, the sentinel and the id claims) runs only while holding that key's lock, taken by the bounded `tryLock` of `ClusterLocks.acquire`, or through a timed variant (`tryPut`, `tryRemove`) with the lock timeout. A timeout fails closed in the store's existing way (`CLUSTER_LOCK_TIMEOUT`). Correctness still rests on the compare-and-set, never on the lock.
- **Equal (sequence, refresh) with different content** can only come from a split-brain merge or from a key holder behaving badly. The rule is fail closed: the instance adopts the **union** of the two pause sets, counts `pause.conflict`, and writes the union at `max(sequence) + 1` by compare-and-set. This is the safe direction for a pause; the cost is that a resume made on one side of a partition can be undone by the merge, and the operator has to resume again (OD-14).
- **Split-brain merge.** The map's Hazelcast merge policy cannot merge sealed payloads, so the application reconciles. Reconciliation runs **only** when the member's `LifecycleEvent` reports `MERGED` (the member's side rejoined a healed cluster), not on a timer or on every read. On that event each instance compares its in-memory highest document with the cluster document. If the instance holds a pause the cluster document lacks, or the two documents have the same order but different content, it writes the **union** by compare-and-set at `max(sequence of the documents involved) + 1` with `refresh = 0`; when the orders differ the higher sequence still gets the union written one above it, so the result is never lower than either side. "Highest sequence wins" is the alternative and is unsafe, because a pause issued on the smaller side is dropped (OD-14).
- The document has a **lease**. Any member refreshes it (`refresh + 1`) when half of it is spent. Freshness is measured from the instance's **local monotonic receipt time**: an instance records `System.nanoTime()` when it accepts a document with a higher (sequence, refresh) than it held, and treats the document as fresh for one lease from then. The wall clock is used only to gate a **cold start**: an instance with no memory rejects a document whose `issued-at` is older than the lease plus `clock-skew-tolerance`, so a replayed old document is not accepted as fresh on a restart; after that first acceptance the wall clock is not consulted, so skew between members cannot make a healthy document look stale. An instance treats a document that is **absent, unauthentic or not fresh as "everything paused"**, and refuses to serve; the caller is refused with `OVERSIGHT_UNAVAILABLE` (4.4). A delete therefore becomes a denial of service, which is the safe direction, and it is visible (`dataprism.cluster.pause.fresh`).
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

- `consumeApproved` also writes a **consumed tombstone** (in `dataprism.approval.consumed.s1`, keyed `mapKey(consumed, approval id)`, configured as the next bullet says) and checks it, so a replay needs both a delete of the tombstone and a re-insert.
- Each instance keeps a bounded in-memory set of approval ids it has consumed until expiry and refuses a second consumption.
- **Configuration of `dataprism.approval.consumed.s1`** (coordinator decision 2026-10-21; brief 221 configures it): no size-based eviction, so a flood of entries cannot evict real tombstones; an entry TTL of the approval's expiry plus `clock-skew-tolerance`, which bounds memory only (correctness comes from the sealed `expiresAt`); a merge policy that keeps the union, so an entry present on either side of a healed split survives; and the same split-brain protection (the approval quorum) and backup count as the approval map.
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
Prism stores only `String`, `Integer` and `Boolean` values. Configure the 5.7
`java-serialization-filter` with an allowlist and refuse a supplied `Config` or
instance that has none (4.2). The allowlist contains those three value classes
**and every Data Prism entry processor and merge-policy class that is sent between
members**: the prefix `io.github.aindriub.dataprism.hazelcast.`, plus
`java.time.Ser` for the processors that carry an `Instant` (coordinator decision
2026-10-21). Spike 219 found why this matters: a processor class the filter
refuses surfaces to the caller as `OperationTimeoutException`, and when the caller
owns the key the primary applies the processor while the backup refuses it, so
primary and backup silently diverge. An incomplete allowlist is therefore not
only a denial of service but a consistency fault, and T12 proves it cannot happen
for Data Prism's own classes. Hazelcast's own internal operations use
identified data serialisation under `com.hazelcast.`, which the default prefixes
allow; whether `defaults-disabled` can be set safely is **to verify in the
0.8 spike**, and the spike must also confirm that Jet, user-code deployment and
SQL are off for every config path.

#### 2.3.7 What (c) does not stop

| Attack | Why |
|---|---|
| **Deletion** of any entry | The attacker can always delete a key. Bounded for budgets and rate limits (2.3.5), converted to a denial of service for pauses (2.3.3), a retry for approvals, a recompute for identity. **Re-identification index entries are lost for good** |
| **Replay** of an old valid ciphertext | Not detectable from the ciphertext. Bounded as in 2.3.3, 2.3.4, 2.3.5 |
| **Availability** | Joining, owning partitions, flooding, cluster-state change, member shutdown, and holding locks are untouched. The lock hang is mitigated: every lock is a bounded `tryLock`, and every write that can wait on a locked key runs under it or through a timed variant (2.3.3, spike 219 answer 10) |
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
- **(d2) Verified-member detection at the application level.** After join, and after the pre-0.8 check of 4.8 has passed (a starting member checks first and writes its card only afterwards), every member that holds the cluster key registers a *member card* in a sealed map of its own, `dataprism.membercard.s1` (inventory 1.9; the sentinel of 4.7 sits in the same map under one reserved key). A card holds the member UUID, the cluster key ids the member accepts, the format version and a lease equal to `pause-lease`. **A card is authenticated**: AES-GCM under a subkey of the cluster key, as a standard envelope (2.3.1, OD-215-9 (a)): the associated data is the envelope's (version, key id, salt, map name, map key) and nothing a reader cannot know before opening; the map key is the keyed hash of the member UUID, which the reader computes for each UUID in the live member list, so the card is bound to its member. The member UUID, the accepted key ids, the format version and the lease expiry are in the authenticated plaintext, and the reader enforces the lease after opening. An attacker without the key cannot forge one. A member is **verified** if a card that authenticates exists for a UUID in the live member list; cards for UUIDs not in the list are ignored. A member chooses its own UUID, so an attacker could present a just-departed member's UUID and have it treated as that member until the departed member's last card lease ends; that is a detection delay only, and the impostor still cannot read or forge anything. **To verify in the 0.8 spike:** how 5.7.0 handles a duplicate live UUID. A replayed old card for a live member is accepted only until its lease ends, and a deleted card makes the member unverified after the grace period. Those are the only effects, and each fails closed: an unverified member triggers the `unverified-member.action` (OD-8); a card claiming fewer keys than the member really accepts makes writers fall back or raise `rotation.blocked` (4.6); a replayed card claiming a key the member has since dropped can, within the lease, cause that member to miss entries sealed under it, which is a miss handled per structure (2.3.1). The cards are also what the rotation procedure in 4.6 uses to decide which key is safe to write. A joined member with no valid card after a grace period is **unverified**. Data Prism can raise a health failure, a metric and a rate-bounded WARN line, and (configurable) refuse to serve. It writes no audit event: the audit trail records data-access decisions (OD-215-6, coordinator decision 2026-10-21). It **cannot evict** the member (no supported OSS API), so it is *detection and fail-closed*, not prevention. An attacker's member keeps receiving ciphertext and can still delete, hold locks and deny service; the operator learns of it within the grace period. This also makes a mismatched key (A3) visible at runtime.
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
and refusal codes below are the contract for the 0.8 build. They are subject to the
owner's decisions; those that change with a decision say which.

### 4.1 Configuration keys

Every key is Spring-bound, typed and pinned in the frozen property list
(OD-200-14). A `Duration` is bound as a `Duration`. A value outside its bounds is
refused at startup with the code in its row; the message names the property and
never its value. Secrets are references, never literals, matching
`dataprism.privacy.hmac-key`. The
lock timeout is not a key of this group: the shipped
`dataprism.hazelcast.lock-timeout` governs (register D7).

**Where the keys apply (OD-215-8 (a)).**

- **A joining member** (`topology: embedded` with `join.mode` `tcp-ip` or `kubernetes`): the keys apply as the table says. The cluster key, the name key and the attestation are required.
- **`single-node`, and an application-supplied `PrivacyCluster` bean** (on any join mode): there is no member, or Data Prism did not build the cluster, so every `dataprism.hazelcast.security.*` key is refused with the existing `CLUSTER_SETTINGS_IGNORED`, naming the property, so a setting never silently does nothing.
- **`join.mode: none`** is a solo member. Its state is in process memory and is lost on restart, so no key has to agree with another member's, but every map is sealed the same way and there is one value format. The cluster key and the name key are **optional**: when both are absent, `PrivacyCluster` generates a random cluster key and a random name key per process; when supplied, they are validated with the same codes as on a joining member, and they are **both or neither** (a cluster key without a name key, a name key without a cluster key, or `next` or `previous` without `current` is refused with `MISSING_CLUSTER_KEY`, naming the missing property). `pause-lease` and `clock-skew-tolerance` are allowed. `unverified-member.*`, `local-floor.enabled` and `isolation.attestation` are refused with `CLUSTER_SETTINGS_IGNORED`, and the attestation is not required: a solo member has no member traffic to isolate.

| Key | Type | Default | Bounds and validation | Refusal code | On `join.mode: none` |
|---|---|---|---|---|---|
| `dataprism.hazelcast.security.cluster-key.key-id` | String | unset | Required. Exactly one of `environment-variable` and `provider-reference` must accompany it. Must resolve through the key provider, meet the strength rule used for `dataprism.privacy.hmac-key`, be a different key from the privacy HMAC key, the audit key and every link key, and contain no `.` (2.3.1) | `MISSING_CLUSTER_KEY` (absent, or not exactly one source); `CLUSTER_KEY_UNRESOLVED`; `CLUSTER_KEY_WEAK`; `CLUSTER_KEY_SHARED` | Optional, with the name key (both or neither); same codes when supplied |
| `dataprism.hazelcast.security.cluster-key.environment-variable` | String | unset | Name of the variable holding the key | `MISSING_CLUSTER_KEY` (neither source, or both) | As above |
| `dataprism.hazelcast.security.cluster-key.provider-reference` | String | unset | A reference to a key provider | `MISSING_CLUSTER_KEY` (neither source, or both) | As above |
| `dataprism.hazelcast.security.cluster-key.value` | String | unset | Refused if set: a literal key is never accepted | `LITERAL_SECRET_FORBIDDEN` | Refused the same way |
| `dataprism.hazelcast.security.cluster-key.next.key-id` | String | unset | Optional, phase 1 of a rotation (4.6, OD-6). Read-only key. Must differ from `current` and `previous`; the same resolution, strength and sharing rules as `current` | `DUPLICATE_CLUSTER_KEY_ID`; `CLUSTER_KEY_UNRESOLVED`; `CLUSTER_KEY_WEAK`; `CLUSTER_KEY_SHARED`; `MISSING_CLUSTER_KEY` (a source without a `key-id`, or a `key-id` without exactly one source) | Allowed with a `current` key (`MISSING_CLUSTER_KEY` without one); same codes |
| `dataprism.hazelcast.security.cluster-key.next.environment-variable` | String | unset | As above, for the next key | As above | As above |
| `dataprism.hazelcast.security.cluster-key.next.provider-reference` | String | unset | As above, for the next key | As above | As above |
| `dataprism.hazelcast.security.cluster-key.next.value` | String | unset | Refused if set: a literal key is never accepted. Bound only so it can be refused by name; never echoed | `LITERAL_SECRET_FORBIDDEN` | Refused the same way |
| `dataprism.hazelcast.security.cluster-key.previous.key-id` | String | unset | Optional, phase 2 of a rotation (4.6, OD-6). Must differ from `current` and `next`; the same resolution, strength and sharing rules as `current` | `DUPLICATE_CLUSTER_KEY_ID`; `CLUSTER_KEY_UNRESOLVED`; `CLUSTER_KEY_WEAK`; `CLUSTER_KEY_SHARED`; `MISSING_CLUSTER_KEY` (as for `next`) | Allowed with a `current` key; same codes |
| `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | String | unset | As above, for the previous key | As above | As above |
| `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | String | unset | As above, for the previous key | As above | As above |
| `dataprism.hazelcast.security.cluster-key.previous.value` | String | unset | Refused if set: a literal key is never accepted. Bound only so it can be refused by name; never echoed | `LITERAL_SECRET_FORBIDDEN` | Refused the same way |
| `dataprism.hazelcast.security.name-key.key-id` | String | unset | Required. The separate, non-rolling key for map keys (which are also the lock keys) and the scope token (4.6). Exactly one of `environment-variable` and `provider-reference` must accompany it. Must resolve, meet the strength rule, differ from every cluster key (`current`, `next`, `previous`), from the privacy HMAC key, from the audit key and from every link key, and contain no `.` | `MISSING_CLUSTER_KEY` (absent, or not exactly one source); `NAME_KEY_UNRESOLVED`; `NAME_KEY_WEAK`; `NAME_KEY_SHARED` | Optional, with the cluster key (both or neither); same codes when supplied |
| `dataprism.hazelcast.security.name-key.environment-variable` | String | unset | Name of the variable holding the name key | `MISSING_CLUSTER_KEY` (neither source, or both) | As above |
| `dataprism.hazelcast.security.name-key.provider-reference` | String | unset | A reference to a key provider | `MISSING_CLUSTER_KEY` (neither source, or both) | As above |
| `dataprism.hazelcast.security.name-key.value` | String | unset | Refused if set: a literal key is never accepted | `LITERAL_SECRET_FORBIDDEN` | Refused the same way |
| `dataprism.hazelcast.security.isolation.attestation` | enum | unset | Required. One of `network-policy`, `private-network`, `mesh-mtls`, `wireguard`, `ipsec`, `none-acknowledged`. Reported on health and in one startup log line; it is not an audit record (OD-215-6 (c)). An attestation, not a verification | `MISSING_ISOLATION_ATTESTATION` (absent); `INVALID_ISOLATION_ATTESTATION` (unknown value) | Refused with `CLUSTER_SETTINGS_IGNORED`; not required |
| `dataprism.hazelcast.security.pause-lease` | `Duration` | `PT30S` | From `PT5S` to `PT5M`. Sets the pause-replay window and the time to fail closed after the document is lost | `INVALID_PAUSE_LEASE` | Allowed |
| `dataprism.hazelcast.security.clock-skew-tolerance` | `Duration` | `PT5S` | From `PT0S` to half of `pause-lease`. Used only to gate the first acceptance of a pause document after a cold start (2.3.3); steady-state freshness uses local monotonic time | `INVALID_PAUSE_LEASE` | Allowed |
| `dataprism.hazelcast.security.unverified-member.action` | enum | `refuse` | `refuse` or `alert` (OD-8) | `INVALID_UNVERIFIED_MEMBER_POLICY` | Refused with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.unverified-member.grace` | `Duration` | `PT30S` | From `PT0S` to `PT10M` | `INVALID_UNVERIFIED_MEMBER_POLICY` | Refused with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.local-floor.enabled` | `boolean` | `true` | Per-instance count enforced in addition to the shared count for budgets and caller rate limits (OD-7). A value that is not a boolean fails binding | none beyond binding | Refused with `CLUSTER_SETTINGS_IGNORED` |

`pause-lease` and `clock-skew-tolerance` share `INVALID_PAUSE_LEASE`, because the
second is bounded by the first; the two `unverified-member` keys share
`INVALID_UNVERIFIED_MEMBER_POLICY`. These are the codes that the register already
carries (3.5), so no code is added for a bound. The withdrawn lock-timeout key
has no row: a lock timeout outside `PT100MS` to `PT30S` is the shipped
`INVALID_LOCK_TIMEOUT`.

The existing `dataprism.hazelcast.member.interface` becomes required for
`tcp-ip` and `kubernetes` **only if OD-10 is decided that way** (4.2), and the existing
`dataprism.hazelcast.tls-key-reference` and `tls-trust-reference` stay refused.

### 4.2 Startup refusal codes

Same style as the existing table: stop before a member starts, except for
`PrivacyCluster.using(instance)`. Codes match `[A-Z][A-Z0-9_]{0,63}`. A key is
"shared" when it has the same key id, or resolves to the same key material, as
another purpose key: the privacy HMAC key, the audit key, a link key, the name
key or a cluster key. The key-failure codes follow the family of OD-200-10
(`<AREA>_KEY_UNRESOLVED`, `_WEAK`, `_SHARED`) with two areas, one for each
secret (OD-215-3 (a)).

| Code | Condition |
|---|---|
| `MISSING_CLUSTER_KEY` | A joining member without a `cluster-key.key-id` or a `name-key.key-id`, or either without exactly one of environment variable and provider reference; on `join.mode: none`, only one of the cluster key and the name key supplied, or `next` or `previous` without `current` |
| `CLUSTER_KEY_UNRESOLVED` | A cluster key (`current`, `next` or `previous`) whose reference does not resolve, or whose id contains `.` |
| `CLUSTER_KEY_WEAK` | A cluster key below the strength rule used for `dataprism.privacy.hmac-key` |
| `CLUSTER_KEY_SHARED` | A cluster key that is the same key as the privacy HMAC key, the audit key or a link key (OD-9). As built (task 222) it fires on the same key id or the same material, and compares only the privacy HMAC key until tasks 211 (audit key) and 254 and 255 (link keys) extend `ClusterKeyResolution` |
| `NAME_KEY_UNRESOLVED` | The name key's reference does not resolve, or its id contains `.` |
| `NAME_KEY_WEAK` | The name key is below the strength rule |
| `NAME_KEY_SHARED` | The name key is the same key as another purpose key (the privacy HMAC key, the audit key or a link key) or as any cluster key. As built (task 222) it fires on the same key id or the same material, and compares only the privacy HMAC key and the cluster keys until tasks 211, 254 and 255 extend `ClusterKeyResolution` |
| `DUPLICATE_CLUSTER_KEY_ID` | The ids of `current`, `next` and `previous` are not pairwise distinct, or the name key's id equals the id of any of them (task 222) |
| `MISSING_ISOLATION_ATTESTATION` | `security.isolation.attestation` absent on a joining member |
| `INVALID_ISOLATION_ATTESTATION` | An unknown attestation value |
| `MISSING_MEMBER_INTERFACE` | `tcp-ip` or `kubernetes` without `member.interface`. Subject to OD-10 |
| `UNBOUNDED_MEMBER_INTERFACE` | `member.interface` matches every address. Subject to OD-10 |
| `PUBLIC_MEMBER_INTERFACE` | The resolved bind address is globally routable and the attestation is not `mesh-mtls`, `wireguard` or `ipsec`. Subject to OD-10 |
| `INVALID_PAUSE_LEASE` | `pause-lease` or `clock-skew-tolerance` outside its range, or skew above half the lease |
| `INVALID_UNVERIFIED_MEMBER_POLICY` | `unverified-member.action` or `.grace` outside its range |
| `UNSAFE_HAZELCAST_SERIALIZATION` | A supplied `Config` or instance with no allowlisting `java-serialization-filter`, or with user-code deployment, Jet or SQL enabled |
| `CLUSTER_KEY_MISMATCH` | At join, the sentinel already in the cluster does not authenticate under any key this member holds (`current`, `next`, `previous`), or is sealed under a key id it does not hold. This includes a member that holds only the old key after the sentinel has moved to the new one: it cannot read the cluster, so it refuses to join, never serves and never gets a member card (4.6, 4.7). A sentinel that authenticates under `previous` is normal in phase 2 and not a mismatch. A sentinel that authenticates under `next` is not a mismatch either: the member is behind but can read (`rotation.behind`) |
| `UNSEALED_CLUSTER_STATE` | A 0.8 member starts, **no verified 0.8 member is present** (no authenticated member card of a live member, 2.4), and it finds an entry in a pre-0.8 map (the eight 0.7 map names, 4.8) other than the fence entry. This is the refusal that enforces the non-rolling cutover (OD-215-1 (a)); there is no compatibility mode that reads those maps. It is never raised once a verified 0.8 member is present (owner decision 2026-10-21, extends OD-215-1): then the entries are counted and logged instead (4.8). "No verified 0.8 member is present" excludes the starting member itself: the check runs before the starting member writes its own card (2.4, 4.8), so rolling restarts for key rotation and the soak keep working |

Reused, not new: `LITERAL_SECRET_FORBIDDEN` for `cluster-key.value`, `cluster-key.next.value`, `cluster-key.previous.value` and `name-key.value`,
and `CLUSTER_SETTINGS_IGNORED` for the cases in 4.1.

Withdrawn: `MISSING_CLUSTER_SECURITY_MODE` and `UNSUPPORTED_CLUSTER_SECURITY_MODE`, with the key they checked (OD-200-9: a joining member is sealed by definition, and `MISSING_CLUSTER_KEY` refuses it without a key). `INVALID_CLUSTER_KEY` is withdrawn too, replaced by the six codes above (OD-215-3 (a)): one family of OD-200-10, two areas.

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
constant (OD-200-15): one `Metric` constant per signal, never one metric with a
closed label. The name uses `_` inside a segment, never `-` (OD-200-7), and a
gauge may carry a unit suffix (`age_seconds`). The constant is the name without
`dataprism.`, upper-cased, with `.` replaced by `_`, as for the shipped
`APPROVAL_RELEASE_SKIPPED`. None carries a key, a value, a subject, a principal,
a scope or a map entry (boundary 7).

| Signal | Constant | Kind | Meaning |
|---|---|---|---|
| `dataprism.cluster.seal.tamper` | `CLUSTER_SEAL_TAMPER` | counter | An entry failed authentication, or was a different format or key than expected. A non-zero rate is an attack or a mixed-key deployment |
| `dataprism.cluster.seal.unsupported_format` | `CLUSTER_SEAL_UNSUPPORTED_FORMAT` | counter | An envelope with a format version or key id this member cannot read |
| `dataprism.cluster.seal.salt_rejected` | `CLUSTER_SEAL_SALT_REJECTED` | counter | Salt-cache admissions refused under a spray of distinct salts |
| `dataprism.cluster.members.unverified` | `CLUSTER_MEMBERS_UNVERIFIED` | gauge | Joined members without a valid member card |
| `dataprism.cluster.pause.fresh` | `CLUSTER_PAUSE_FRESH` | gauge | 1 when the pause document is present, authentic and inside its lease; else 0 |
| `dataprism.cluster.pause.age_seconds` | `CLUSTER_PAUSE_AGE_SECONDS` | gauge | Age of the accepted document, from local monotonic receipt |
| `dataprism.cluster.pause.conflict` | `CLUSTER_PAUSE_CONFLICT` | counter | Equal-order documents with different content were merged by union (2.3.3) |
| `dataprism.cluster.rotation.accepting_next` | `CLUSTER_ROTATION_ACCEPTING_NEXT` | gauge | Verified members whose card lists the `next` key (phase 1 gate) |
| `dataprism.cluster.rotation.blocked` | `CLUSTER_ROTATION_BLOCKED` | counter | A write was refused because no held key is accepted by all verified members |
| `dataprism.cluster.rotation.behind` | `CLUSTER_ROTATION_BEHIND` | gauge | 1 while this member runs with a `current` key older than the sentinel's, holding the sentinel's key only as `next`; 0 otherwise. It can read, and writes under the write-key rule |
| `dataprism.cluster.seal.entries_under_previous` | `CLUSTER_SEAL_ENTRIES_UNDER_PREVIOUS` | gauge | Locally owned entries still sealed under the previous key (phase 2 gate) |
| `dataprism.cluster.lock.timeout` | `CLUSTER_LOCK_TIMEOUT` | counter | A map lock wait ended in a fail-closed refusal |
| `dataprism.cluster.floor.refusal` | `CLUSTER_FLOOR_REFUSAL` | counter | A local floor refused a request that the shared count would have allowed (evidence of reset or replay) |
| `dataprism.cluster.legacy.entries` | `CLUSTER_LEGACY_ENTRIES` | gauge | The latest membership check's count of entries (other than the fence) in pre-0.8 maps while a verified 0.8 member was present (4.8): a 0.7 member that joined late, or a leftover. A level, not an event count (task 222; `PrivacyCluster.legacyEntries()`) |

**How gauges are emitted (OD-215-5 (a)).** `PrivacyMetrics` has no gauge method
today. 0.8 adds a `default` no-op `gauge(Metric, DoubleSupplier)` to it. The
component that owns a value registers its supplier once, and the registry reads
it at scrape time. `FailSafeMetrics` forwards the call, and the two Micrometer
implementations (`ServerPrivacyMetrics` and `MicrometerPrivacyMetrics`)
implement it (task 220). A gauge has no tag, like every other signal. The six
gauges above are `members.unverified`, `pause.fresh`, `pause.age_seconds`,
`rotation.accepting_next`, `rotation.behind` and `seal.entries_under_previous`;
the other eight are counters. An embedder with its own `PrivacyMetrics` compiles
unchanged and gets no gauges. The values are also in the `clusterSecurity`
health details.

**What a caller sees (OD-215-10 (a)).** No new client-visible code is added. The
operator learns the reason from health and from the metrics above; the caller
only learns that the request was refused.

| Condition | Caller sees | Where it surfaces |
|---|---|---|
| The pause document is absent, unauthentic or not fresh (2.3.3): admission cannot be evaluated | `OVERSIGHT_UNAVAILABLE` | Tool calls (`ToolCalls.admit`); operator reads of state and approvals and the resume write (HTTP 503 `OVERSIGHT_UNAVAILABLE`). The pause write is allowed (below) |
| This member is not serving: an unverified member is present and the action is `refuse` after the grace, or the sentinel or a key check value failed | `CLUSTER_OPERATION_REFUSED` | Every path in register 3.2: admission, read budget, re-identification, operator reads and the resume write. The pause write is allowed (below) |
| `rotation.blocked`: a write was refused because no held key is accepted by all verified members (4.6) | `CLUSTER_OPERATION_REFUSED` | The same paths |

**Operator writes while the document is not fresh or the member is not serving (OD-215-16 (a)).** A pause is allowed; a resume is refused until the member serves again, because pausing is the safe direction and resuming while the cluster is in doubt is not. The serving gate never blocks the pause-document map (or the member-card map), so an operator can still pause, and a pause by `putIfAbsent` is the operator's way to re-create an absent document (2.3.3).

`CLUSTER_NOT_SERVING` and `PAUSE_STATE_STALE` are not added: a new core refusal
class would need new catch sites in the `security`, `reidentification` and
`server` modules, files the core plan also edits.

**Tamper is a metric and a log line, not an audit record (OD-215-6 (b)).** Each
failed authentication increments `dataprism.cluster.seal.tamper`. A rate-bounded
`WARN` line, at most one per map per interval, names the map-name constant and
the count since the last line, and never entry content, a key or a keyed name.
`CLUSTER_STATE_TAMPER` as an audit record is withdrawn: the audit trail records
data-access decisions, and an attacker-driven burst would flood it.

Health: a `clusterSecurity` health contributor (named like the existing `auditIntegrity`), part of the readiness group and not the liveness group, since restarting an instance does not repair a hostile or mismatched cluster. It is `DOWN` when the pause document is not fresh, when
an unverified member is present and the action is `refuse`, or when the sentinel
check or a key check value failed. The component reports the isolation attestation value (on a joining
member; a `join.mode: none` member has none) and the number of members, not
addresses. The attestation is also written once in a startup log line. It is
reported on health and in that line, and it is not audited (OD-215-6 (c)).

### 4.5 Hazelcast configuration the build forces

Today (0.7), per path:

| Path | Phone-home off | Jet off | Other checks |
|---|---|---|---|
| `embedded(ClusterMembership)` (`toConfig`, then `configure`) | Yes (`ClusterMembership.toConfig`, `PrivacyCluster.configure`) | Yes (`ClusterMembership.toConfig`) | Cluster name, advanced network, TLS and security refused |
| `embedded(Config)` (`PrivacyCluster.configure`) | Yes (`configure`) | **No** | Same refusals; auto-detection and multicast forced off |
| `using(instance)` (`PrivacyCluster.using`) | **No** | **No** | Cluster name, advanced network, auto-detection and multicast checked. It cannot change a running instance, and it does not check anything else |

The design enforces, on the two `embedded` paths, by **forcing**: phone-home off,
Jet off, user-code deployment off, the Java serialisation allowlist set when none
is present (a supplied non-allowlisting filter is refused). `tryLock` has replaced
`lock` in the stores already (0.7, tasks 202 and 216). SQL is to be disabled too if 5.7.0 exposes a
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
- The **name key** (map keys, which are also the lock keys, and the scope token; 2.3.2) is configured separately and **does not rotate in the rolling procedure.** Changing it moves every entry to a different map key, so it is a planned full restart with state loss (which for the re-identification index is a loss of the store). Its compromise lets an attacker test guesses against known identifiers and target a delete or a lock; it does not reveal any value. That is why it can be the long-lived one.

Because the name key does not change, two members can never derive different map keys (and so lock keys) for the same entry during a cluster-key rotation. Only the envelope's encryption key varies, and the envelope carries its key id.

**Configuration roles** (4.1): `cluster-key` is the **current** key. `cluster-key.next` is accepted for reading and never written. `cluster-key.previous` is accepted for reading and used for writing only in the fallback below.

**Write-key rule.** A member writes with `current` only if the member cards (2.4, d2) of **all** verified members list it as accepted. Otherwise it writes with `previous` if configured and accepted by all. Otherwise it writes nothing sealed and raises `rotation.blocked`; every operation that needs a write fails closed, and the caller is refused with `CLUSTER_OPERATION_REFUSED` (4.4). Reads accept every key the member holds. This rule is what keeps a key from being written before every member can read it, including a member that joined before the sentinel moved and is still on the old configuration. The fallback is reachable in exactly that case: such a member's card lacks K2, so writers cannot use K2 and write K1 (their `previous`), and the sentinel is not re-sealed under K2 until every verified card lists K2. A member that arrives after the sentinel is under K2 holding only K1 is not in this case; it is refused at join (below).

**Phases**, with K1 the old key and K2 the new:

| Phase | Action on each member, one at a time (rolling restart) | Entry condition for the next phase |
|---|---|---|
| **0. Provision** | K2 is provisioned in the secret provider on every instance, referenced by nothing | None |
| **1. Accept** | `current = K1`, `next = K2`. Every member now reads K1 and K2 and writes K1. Cards list K1 and K2 | The `rotation.accepting_next` gauge equals the member count, and no member is unverified |
| **2. Switch** | `current = K2`, `previous = K1`. Members write K2 as soon as all cards list K2 (they do, by phase 1); until then, or while a verified member's card still lacks K2 (an operator skipped the phase 1 gate), they fall back to K1. Only the sweeper re-seals (OD-215-13 (a)): never on read, because each store already writes under the policy's key on every write, and the gate metric is the same (below). A **pause document** sealed under K2 is written only after every member has accepted K2, by the write-key rule | `dataprism.cluster.seal.entries_under_previous` reads 0 on every member for one full pause lease and the longest approval lifetime, and a re-identification re-seal pass has finished |
| **3. Retire** | `previous` removed, one member at a time. K1 is no longer held anywhere | Complete; remove K1 from the secret provider |

**Sweeper.** In phase 2 each member walks the entries it owns locally (`IMap.localKeySet` or a partition-local entry iterator; **to verify** which is available in OSS 5.7.0), opens those sealed under `previous`, and re-seals them under `current` by compare-and-set, each under the bounded lock of 2.3.3. It reports the `entries_under_previous` gauge. Re-identification entries are the case that makes this necessary: they cannot be recomputed. Identity entries are skipped (a miss recomputes), approvals are short-lived, and the pause document is rewritten by the lease refresh.

**Sentinel and mismatch (4.7) during rotation.** Let S be the key id the sentinel is sealed under. A joining member tries the sentinel against every key it holds and acts on which one succeeds:

| S matches the member's | Meaning | Outcome |
|---|---|---|
| `current` | In step | Joins normally |
| `previous` | The member is **ahead**: phase 2 has begun and the sentinel is still under the old key. This is the normal start of phase 2 | Joins. If every verified card lists the member's `current`, it re-seals the sentinel under `current` by compare-and-set and writes `current`. If not, it leaves the sentinel alone and writes `previous` (the fallback above) |
| `next` | The member is **behind**: the cluster already uses the key this member only accepts. It can read | Joins, raises `rotation.behind`, and writes under the write-key rule (its `current`, which every K2 member lists as accepted through `previous`). It stops being behind when restarted with the new configuration |
| none of them | The member **cannot read** the cluster: it holds only the old key after the sentinel moved, or a different secret | `CLUSTER_KEY_MISMATCH`: it refuses to join and does not serve, so it never has a card and writers never wait for it |

The sentinel is re-sealed whenever the write key advances, only by a member that is ahead and has met the card rule. Because a genuinely-behind member (cannot read) is refused rather than admitted, the late-member case in the write-key rule is the *already-joined* member that was left on the old configuration, not a newcomer.

**Failure behaviour.** Skipping phase 1, or retiring K1 early, produces misses and `seal.unsupported_format`, never a read of an unauthenticated value. The cost of getting it wrong is lost re-identification entries and a refused pause document (fail closed), so the phase gates above are metrics an operator or the soak can assert on.

### 4.7 Key agreement and how link keys reach every member

Data Prism never distributes a key through Hazelcast. Each instance resolves the
same key id and reference from the deployment's secret provider, as it does
today for the privacy HMAC key (`ConfiguredSecretKeyProvider`).

- **Key-derivation labels (OD-215-4 (a)).** One labelled HKDF helper serves the cluster and the sealed links (task 191), so every label has one prefix style: `dataprism/<area>/v1/...`. The cluster labels are listed here; the closing table 4.9 only indexes them. `<map>` is the full name of the sealed map, including its generation suffix (4.8).

  | Label | Derived from | Used for |
  |---|---|---|
  | `dataprism/cluster/v1/aead/<map>` | cluster key, with the process salt (2.3.1) | The AES-GCM subkey of one map. The `dataprism.reidentification.s1` subkey is distinct from every other map's (6.1) |
  | `dataprism/cluster/v1/kcv` | any purpose key | The key check value (below) |
  | `dataprism/cluster/v1/name/<map>` | name key | The HMAC subkey for the map keys of one map (`mapKey`, 2.3.2) |
  | `dataprism/cluster/v1/scope` | name key | The HMAC subkey for the scope token (2.3.2) |

- **Sentinel at join.** A member writes, if absent, or reads a sealed constant (`dp-sentinel`) carrying the key id. It is stored in `dataprism.membercard.s1` under the reserved key `sentinel` (1.9). It is written only after the `UNSEALED_CLUSTER_STATE` check has passed (4.8). If an existing sentinel does not authenticate, the member refuses with `CLUSTER_KEY_MISMATCH` and leaves. This makes a misconfigured key visible at startup instead of as a stream of tamper counters. An attacker who plants a bogus sentinel before the first legitimate member can only cause that refusal (a denial of service), which is detected and visible. Behaviour during a rotation, including a member whose `previous` or `next` matches the sentinel, is in 4.6.
- **Sealed link keys (task 191).** Links do not use Hazelcast, so there is nothing to distribute: every instance derives the link key from the same configured link key id and reference, by labelled HKDF (`dataprism/link/v1/...`), exactly as the cluster key. A link issued by one instance is verified by any other that holds the same key. A mismatch would appear only as links being refused uniformly, which an operator cannot tell from an expired link. So the member card carries a **key check value** for each configured purpose key (OD-215-7 (a)): the first 8 bytes of `HMAC-SHA256(HKDF(purposeKey, label = the kcv label above), keyId)`, where `keyId` is the key id in UTF-8 and the HKDF salt is empty (the RFC 5869 default); the card is sealed under the cluster key. The 16-byte form and the unkeyed `HMAC(purposeKey, label || keyId)` form were not taken. A member that sees a card with a different check value for the same key id raises `dataprism.cluster.seal.tamper` and health `DOWN`. The check value is one-way and short; it adds no usable information about a key that meets the strength rule. Task 191's design should cite this section.
- Whether the cluster key, the privacy HMAC key and the link key are separate secrets is OD-9.

### 4.8 Mixed versions, the 0.8 maps and the 0.7 to 0.8 cutover

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
- New sealed maps use **new names**, with the generation suffix `.s1`, because the pre-0.8 maps hold `Integer` and `Boolean` values that a new member would misread. A later format generation takes `.s2`, and so on. 0.8 creates exactly these ten maps:

  | 0.7 map (pre-0.8; 0.8 reads it only for the check in the next bullets and writes only the fence) | 0.8 map |
  |---|---|
  | `dataprism.identity` | `dataprism.identity.s1` |
  | `dataprism.reidentification` | `dataprism.reidentification.s1` |
  | `dataprism.budget` | `dataprism.budget.s1` |
  | `dataprism.oversight` | `dataprism.oversight.s1` |
  | `dataprism.approval` | `dataprism.approval.s1` |
  | `dataprism.approval.id` | `dataprism.approval.id.s1` |
  | `dataprism.approval.pending` | `dataprism.approval.pending.s1` |
  | `dataprism.callerrate` | `dataprism.callerrate.s1` |
  | none | `dataprism.approval.consumed.s1` (the consumed-approval tombstones, 2.3.4) |
  | none | `dataprism.membercard.s1` (the member cards and the sentinel, 1.9) |

- **The cutover is non-rolling (OD-215-1 (a)).** The compatibility mode of OD-5, which read the old `oversight` map as extra pauses and dual-wrote pauses to both generations, is **withdrawn**. To move from 0.7 to 0.8: stop every 0.7 member, then start the 0.8 members, which create the `.s1` maps empty. A 0.8 member that finds an entry in any of the eight pre-0.8 maps, when no verified 0.8 member is present, refuses to start with `UNSEALED_CLUSTER_STATE` (4.2); the fence entry (below) does not count. It never reads, migrates or merges those entries. The order is fixed: a starting member runs this check first, and writes its own member card and the sentinel only after the check passes, so it is never counted as the verified member that excuses its own check. Once a verified 0.8 member (another member) is present, entries found in pre-0.8 maps (a 0.7 member that joined late, or a leftover) are counted (`dataprism.cluster.legacy.entries`, once per check that finds any) and logged (a rate-bounded WARN line naming the map-name constant and a count), and are never a refusal. The check runs at start and at each membership change. The cost, accepted by the owner: a full outage, and the loss of all in-memory state, including the re-identification index. Owner note: the cluster state is cached data and can be fetched again; pending approvals are lost and must be requested again. Task 213 documents the procedure in `docs/migration-0.8.md`.
- **The fence (owner decision 2026-10-21, extends OD-215-1).** Every 0.8 member, once serving, ensures that the pre-0.8 map `dataprism.oversight` holds the entry key `all` with the value `Boolean.TRUE`. That is exactly what the 0.7.0 `HazelcastOversightState.allPaused()` reads as paused (`flags().containsKey("all")`, key from `ScopeKeys.pausedAll()`, value written by `pauseAll()` as `Boolean.TRUE`; v0.7.0). The 0.7.0 `ToolAdmission.admit` calls `state.allPaused()` first on every tool call, uncached, and refuses with `DATAPRISM_PAUSED`; a failure to read refuses with `OVERSIGHT_UNAVAILABLE`. So any 0.7 member that joins the cluster refuses every tool call. The write is bounded (the bounded `tryLock` or timed variant of 2.3.3 on the key `all`), idempotent (`putIfAbsent`) and re-asserted whenever it is found deleted: at every pause-document lease refresh (2.3.3) and at the check above. The 0.8 member configures that map as 0.7 did (never evicted) so the entry cannot be evicted.
- **A mixed 0.7/0.8 cluster does not serve budgeted reads, by the fence.** The old window had budgets, rate limits and approvals per generation, so a scope's allowance silently doubled, and a pause on one generation was invisible to the other. The non-rolling rule removes the window, and the fence closes the one way back in: a 0.7 member started later joins a cluster that its own pause check reads as paused. The 0.8 members also see that 0.7 member as unverified (4.4, task 227), and with `unverified-member.action: refuse` (the default) they stop serving as well. Residuals, to document. (1) The fence entry can be deleted by anyone who can reach the member port, including a keyless client, and by a 0.7 operator's `resumeAll`; until a 0.8 member re-asserts it (within half a pause lease) a late 0.7 member serves from the old maps. Anyone on the network can already stop the cluster (spike 219, Q3), so this adds no new capability. (2) A 0.7 member that never reads the oversight map is not fenced: in 0.7.0 the Spring wiring (`OversightWiring`) always builds `ToolAdmission` over `HazelcastOversightState` for a clustered member, but a plain-Java embedder that passes `ToolAdmission.none()` checks nothing. (3) With `alert` the 0.8 members keep serving while the late 0.7 member is fenced out by its own check; if the fence has been deleted, each generation counts separately. That is the accepted cost of `alert` (OD-8), and the reason `refuse` is the default.
- Within 0.8.x, a later format bump (`dps2`) follows the same rule and ships a reader for `dps1` for one minor release.

### 4.9 Closing table: every proposed name

The names withdrawn by this design are not listed: `dataprism.hazelcast.security.mode`, `dataprism.hazelcast.security.lock-timeout`, `MISSING_CLUSTER_SECURITY_MODE`, `UNSUPPORTED_CLUSTER_SECURITY_MODE`, `INVALID_CLUSTER_KEY` and the audit event `CLUSTER_STATE_TAMPER` (OD-200-9, D7, OD-215-3 and OD-215-6; 4.1, 4.2, 4.4). The shipped codes `LITERAL_SECRET_FORBIDDEN` and `CLUSTER_SETTINGS_IGNORED` are reused, not proposed.

| Kind | Name | Section |
|---|---|---|
| key | `dataprism.hazelcast.security.cluster-key.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.value` (refused) | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.next.value` (refused) | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.cluster-key.previous.value` (refused) | 4.1 |
| key | `dataprism.hazelcast.security.name-key.key-id` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.environment-variable` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.provider-reference` | 4.1 |
| key | `dataprism.hazelcast.security.name-key.value` (refused) | 4.1 |
| key | `dataprism.hazelcast.security.isolation.attestation` | 4.1 |
| key | `dataprism.hazelcast.security.pause-lease` | 4.1 |
| key | `dataprism.hazelcast.security.clock-skew-tolerance` | 4.1 |
| key | `dataprism.hazelcast.security.unverified-member.action` | 4.1 |
| key | `dataprism.hazelcast.security.unverified-member.grace` | 4.1 |
| key | `dataprism.hazelcast.security.local-floor.enabled` | 4.1 |
| code | `MISSING_CLUSTER_KEY` | 4.2 |
| code | `CLUSTER_KEY_UNRESOLVED` | 4.2 |
| code | `CLUSTER_KEY_WEAK` | 4.2 |
| code | `CLUSTER_KEY_SHARED` | 4.2 |
| code | `NAME_KEY_UNRESOLVED` | 4.2 |
| code | `NAME_KEY_WEAK` | 4.2 |
| code | `NAME_KEY_SHARED` | 4.2 |
| code | `DUPLICATE_CLUSTER_KEY_ID` | 4.2 |
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
| metric | `dataprism.cluster.rotation.behind` | 4.4 |
| metric | `dataprism.cluster.seal.entries_under_previous` | 4.4 |
| metric | `dataprism.cluster.lock.timeout` | 4.4 |
| metric | `dataprism.cluster.floor.refusal` | 4.4 |
| metric | `dataprism.cluster.legacy.entries` | 4.4 |
| map | `dataprism.identity.s1` | 4.8 |
| map | `dataprism.reidentification.s1` | 4.8 |
| map | `dataprism.budget.s1` | 4.8 |
| map | `dataprism.oversight.s1` | 4.8 |
| map | `dataprism.approval.s1` | 4.8 |
| map | `dataprism.approval.id.s1` | 4.8 |
| map | `dataprism.approval.pending.s1` | 4.8 |
| map | `dataprism.callerrate.s1` | 4.8 |
| map | `dataprism.approval.consumed.s1` | 4.8 |
| map | `dataprism.membercard.s1` | 1.9 |
| key-derivation label | `dataprism/cluster/v1/aead/<map>` | 4.7 |
| key-derivation label | `dataprism/cluster/v1/kcv` | 4.7 |
| key-derivation label | `dataprism/cluster/v1/name/<map>` | 4.7 |
| key-derivation label | `dataprism/cluster/v1/scope` | 4.7 |
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
| T2 | Splice tests: a valid ciphertext copied to another key, another map, another scope or another salt, and after expiry, is rejected. A flipped bit in any of the five envelope fields, including the one that carries `expiresAt` inside the ciphertext, is rejected; so are truncation, a missing nonce field, a key id with a `.`, an unknown format and an unknown key id |
| T3 | Identity poisoning: arbitrary data written into `dataprism.identity` never changes a returned value; output equals the generator (boundary 6) |
| T4 | Pause: deleting the document makes every instance refuse and sets `pause.fresh` to 0; an older document is rejected by an instance that saw a newer one; an instance that restarts accepts a replayed older document **only inside the lease and the cold-start gate**, and a test asserts the window equals the configured lease; two concurrent writers, one `replace` loses and retries, neither pause is lost; equal-order documents with different content merge by union (OD-14); a member with a skewed wall clock does not make a healthy document stale |
| T5 | Approval replay: the tombstone and the in-memory consumed set bound reuse as stated in 2.3.4; the residual is asserted, not hidden |
| T6 | Budget and rate limit: delete and replay of shared counts never allow more than the local floor permits per instance |
| T7 | **`HazelcastStoredValueBoundaryTest` extended:** no key in any map contains a scope id, subject id, principal id, approval id, case id or pseudonym; every stored string matches the envelope grammar; unknown maps are still rejected; the test holds the key, opens every envelope and still recomputes allowed state from the decrypted canonical key parts and still rejects the distinctive raw-value fixture |
| T8 | Startup: every code in 4.2 is reachable by a table-driven test. `CLUSTER_SETTINGS_IGNORED` for every security key with `single-node` and with an application-supplied `PrivacyCluster`, and, on `join.mode: none`, for `unverified-member.*`, `local-floor.enabled` and `isolation.attestation` only; on `none` the cluster and name keys are optional, both-or-neither, validated with the same codes, and `pause-lease` and `clock-skew-tolerance` are accepted (4.1) |
| T9 | `CLUSTER_KEY_MISMATCH`: a member with another key joins a cluster holding a sentinel and refuses; a planted bogus sentinel produces the refusal and no data access |
| T10 | Unverified member: a keyless member joins; `members.unverified` goes to 1, health `DOWN` after the grace under `refuse`, recovery after it leaves |
| T11 | Lock hold: a client locks a budget key, an approval-id key and a caller key; each operation fails closed within `lock-timeout` and none hangs a thread |
| T12 | Serialisation filter: (a) a disallowed class sent from a keyless client is refused; a supplied `Config` without the filter is refused; (b) a test enumerates every entry processor and merge-policy class Data Prism uses and proves each is allowed by the configured filter, so the primary-and-backup divergence of 2.3.6 cannot occur for our own classes |
| T13 | Mixed format: a fixture writing an entry into each of the eight pre-0.8 maps makes a starting 0.8 member refuse with `UNSEALED_CLUSTER_STATE` and read none of it when no verified 0.8 member is present, the starting member itself not counting because it checks before it writes its own card; the fence entry alone does not; with a verified 0.8 member present the same fixture is counted in `dataprism.cluster.legacy.entries`, logged, and never refused; a serving 0.8 member writes the fence, re-asserts it after a delete, and a fixture applying the 0.7.0 `allPaused` test (`containsKey("all")`) reads the cluster as paused; a fixture writing `dps2` into a `.s1` map is ignored per structure and counted (4.8) |
| T14 | `ArchitectureTest` (module `data-prism-architecture`): the sealing package is not depended on by `mcp`, `orchestration` or `connectors`; only the re-identification index reads `reidentification` envelopes (boundary 5 shape) |
| T15 | Log, metric and audit scan: tamper, mismatch and refusal paths emit no key, value, subject, principal, scope id or ciphertext (extend `PiiLogScanTest`; boundary 7) |
| T16 | HKDF against RFC 5869 vectors; AES-GCM nonce uniqueness property across threads and across a simulated restart; salt-cache bound under a spray |

### 5.2 Soak (part of "Soak, including the cluster")

- Three members, with sealing on, the load harness (task 196) driving budget, identity, approval and pause traffic. Measure **latency and CPU of sealing against the unsealed baseline** at 100 and 500 sources, and entry size against the 100,000-per-member cap. These are the numbers that decide whether the cost estimate in 2.3 holds.
- Kill and restart a member; kill an owner and its backup together; roll all members. Checks: no unsealed leak, no stale pause accepted outside the lease, re-identification index loss behaves as documented.
- **Rotate the cluster key under load (4.6), concretely.** Three members, the load harness (task 196) at a fixed rate that mixes budget reads, identity lookups, approval create/approve/consume, and re-identification lookups for a pre-created set of 10,000 pseudonyms, with a pause toggled every 5 seconds throughout. Steps: (1) Phase 1 rolling restart with `next = K2`; assert `rotation.accepting_next` reaches 3 and no more than the restarting member's in-flight requests are refused. (2) Phase 2 rolling restart with `current = K2`, `previous = K1`. For the first restarted member assert: the sentinel is under K1 (its `previous`), it joins with no `CLUSTER_KEY_MISMATCH` and no `rotation.behind`, every card lists K2, so it re-seals the sentinel under K2 and starts writing K2, and the other two members, still `current = K1`, `next = K2`, read its entries. Continue to the third member; assert no pause is missed by any member and `seal.tamper` stays 0. (3) Behind case: after the sentinel is under K2, restart one member with its old phase 1 configuration; assert it joins, `rotation.behind` is 1, it writes K1, every member reads it, and restarting it with the phase 2 configuration clears the gauge. (4) Wait for `entries_under_previous = 0` on every member. (5) Phase 3, retire K1 one member at a time. Negative cases: (a) a fourth member holding **only K1** started after the sentinel is under K2 is refused with `CLUSTER_KEY_MISMATCH`, never gets a card, serves nothing, and changes no writer's behaviour; (b) a K1-only member that joined **before** phase 2 and is left running (a skipped phase 1 gate): writers fall back to K1, the sentinel stays under K1, and `accepting_next` is below the member count; (c) retire K1 on one member while `entries_under_previous` is non-zero and assert the loss is detected (`seal.unsupported_format`) and not served. Pass criteria for the main path: pause propagation never exceeds the configured lease; every one of the 10,000 pseudonyms still resolves; an approval created before phase 2 and consumed after it succeeds exactly once. The name key change is exercised separately as a full restart.
- A hostile member joins under load: tamper flood. Confirm CPU stays bounded (salt cache, failure counting), health goes `DOWN` per policy, and recovery after it leaves.
- Network partition with a pause issued on one side: lease behaviour, split-brain approval consumption (see NOTED in the task file), and rejoin.
- Clock skew injected on one member: pause lease and `expiresAt` behave per the tolerance.
- Environment verification, run once per supported platform and recorded: a capture on the member port shows no plaintext **only in the E3 profile**; the isolation claims in `docs/multiple-instances.md` are tested rather than inherited from D-0.4.1-E.

---

## 6. Boundaries and open decisions for the owner

### 6.1 Boundaries (`docs/architecture.md`, 144-260)

**Boundary 5 (re-identification is never an MCP tool).**
- The cluster key is not an operator credential and not an MCP credential; a holder of one is not thereby authorised for the other.
- Re-identification values are sealed under a subkey of their own: the subkey for the `dataprism.reidentification.s1` map is derived with the `aead` label of 4.7 for that map (2.3.1), different from every other map's, and the keyed map key hides the pseudonym. A member without the key, and an MCP client, cannot read the index. Separation between maps here is cryptographic (distinct subkeys) and also bound in the associated data; it is not separation between members that hold the cluster key, who can derive every subkey (A4, 0.2).
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
- Metrics have no tags and no values (4.4). Tamper is a metric and a rate-bounded WARN line that carries a map-name constant and a count; there is no `CLUSTER_STATE_TAMPER` audit record (OD-215-6 (b), 4.4).
- JCA failures are caught at the sealing boundary and reported as a counter and a constant message, never as the exception text or the input.
- T15 turns this into a test. Note that boundary 7 is only *partially* enforced today (metric labels and trace attributes are not scanned); the new signals depend on code review for that half until a later task scans them (task 194 covers the key-shape scan in the leak validators only).

### 6.2 Decisions (owner delegated to the recommendations)

On 2026-10-09 the owner delegated every decision below to the recommendation ("i trust your recommendations"). Each carries a **Decided** line naming the option taken. The options and reasoning stay visible.

**OD-1. Which position does 1.0 take on multi-instance security?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) **E2 with (b) advised**: seal what is stored, detect unverified members, fail closed, bound what cannot be sealed. Largest build.
- (ii) E1 plus the serialisation filter: isolation attested and checked; no sealing. Smallest build; the product is exactly as secure as the network.
- (iii) E3 as a requirement: refuse to start without an attested mesh or encrypted network. Not verifiable; adds a hard dependency on deployer infrastructure.
- **Recommend (i), on its merits and independent of the roadmap's wording.** It is the only option under which Data Prism itself enforces and can test something about cluster protection: it closes approval forging, identity poisoning and disclosure of the re-identification index and of subject identifiers, which are the attacks that make the product's central guarantees false. Rule 2 (fail closed) argues against shipping a multi-instance mode whose safety is an unverifiable attestation, which is what (ii) and (iii) are. The price is the largest build in 0.8, a new secret pair, new cryptography to review, and a state-format break from 0.7. What (i) does not buy is in 2.3.7 and in OD-13.

**OD-2. Is an isolation-only mode (no cluster key) allowed for a multi-member cluster?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Not allowed: any cluster with more than one member is sealed by definition and requires the cluster key and the name key.
- (ii) Allowed through an explicit isolation-only value of the mode key (that key is withdrawn, OD-200-9), with an attestation, and a startup WARN plus health note. Not taken.
- (iii) Allowed in 0.8 and 0.9, removed at 1.0.
- **Recommend (i).** Rule 2 (fail closed): an unclassified cluster is refused. The cost to an operator with good isolation is one secret. (iii) is rejected because it would freeze a mode in the 0.9 surface only to remove it, which breaks the stability promise.

**OD-3. How are pauses protected against deletion?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) The signed, sequenced, leased document in 2.3.3, with fail-closed on absence.
- (ii) Seal each flag only. A delete still lifts a pause; documented as a known limit.
- (iii) Keep plaintext flags and rely on isolation.
- **Recommend (i)**, with the compare-and-set, equal-order and monotonic-freshness rules in 2.3.3 (the merge and rewrite choices are OD-14 and OD-15). A pause is a safety control, and a delete that silently lifts it is the worst result in the inventory. The cost is a design with a lease, a clock-skew tolerance and a bootstrap rule. Consequence to accept: while the cluster is unreachable beyond the lease, an instance refuses everything, which is consistent with how budget already fails closed.

**OD-4. What should an instance do when the pause document is stale or absent?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Refuse every call (equivalent to a global pause) until a fresh document is seen.
- (ii) Refuse only calls that read subject data; allow `list_sources` and similar.
- (iii) Continue on the last known state and alert.
- **Recommend (i).** Simple, safe, and testable. (iii) reintroduces the attack for the length of the outage.

**OD-5. How do existing 0.7 clusters cut over to sealed state?**
**Decided (owner delegated, 2026-10-09): option (ii), the recommended one. Superseded by OD-215-1 (a) (owner, 2026-10-20): option (i), a non-rolling cutover. Option (ii), its compatibility mode and the dual write are withdrawn; `UNSEALED_CLUSTER_STATE` enforces the cutover (4.8).**
- (i) Flag day: stop all instances, start the new ones. Everything in memory is lost, including the re-identification index.
- (ii) Rolling with a compatibility behaviour: new members dual-write pauses and read the old `oversight` map as an additional pause only; nothing else is read from the old format. Withdrawn (OD-215-1 (a)): a mixed window doubles a scope's allowance, because budgets, rate limits and approvals are per generation in it.
- (iii) Rolling with a one-time migration that re-seals re-identification entries read from the old map. Not taken: no pre-0.8 entry is read.
- **Original recommendation, superseded: (ii).** Pauses cross the generations; forgeable structures do not. For deployments with re-identification on, (iii) is available as an explicit operator-run step, because migrating means trusting entries that could have been forged under 0.7; it should not be automatic. How many deployments run 0.7 clusters with re-identification on is a question for the owner, not an assumption made here.

**OD-6. How must the cluster key rotate for 1.0?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Phased rolling rotation of the cluster key (4.6), with a separate, non-rolling name key. Changing the name key is a planned restart with state loss.
- (ii) Both keys rotate in the rolling procedure, by dual-probing map and lock keys under the current and previous name keys. More code and more states in the lock path.
- (iii) Rotation of either key requires a full restart and loses all state, documented.
- **Recommend (i).** The only structure that cannot be recomputed is the re-identification index, and it needs the cluster key to rotate without loss. Splitting out the name key is what removes the one hazard that makes rolling rotation unsafe (members deriving different keys for one entry). (iii) is defensible if the owner decides clusters will not run re-identification at 1.0.

**OD-7. Local floor for budgets and rate limits?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Enable by default (2.3.5): worst case equals `single-node` behaviour.
- (ii) Shared counts only; document that a hostile member can reset them.
- **Recommend (i).** It turns an unbounded risk into the already documented bound at small cost. Residual: counts reset on a restart, as today.

**OD-8. What happens when an unverified member joins?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) `refuse`: health `DOWN` and the instance stops serving after the grace.
- (ii) `alert`: health stays up; metric, a rate-bounded WARN line and a health detail only (no audit event, coordinator decision 2026-10-21).
- **Recommend (i)**, with `alert` as an explicit choice, for the same fail-closed reason. Consequence: an attacker who can join can take every instance out of service. They could do worse anyway (deletion, locks), and the alternative is silence.

**OD-9. One key or several?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Separate secrets for the cluster key, the privacy HMAC key and the link key, each with labelled subkeys.
- (ii) One master secret with labelled derivation for every purpose.
- **Recommend (i)**, which here also means the name key (4.6) is a fourth secret. A leaked cluster key (which sits on every instance's configuration and is read by every member) should not expose the pseudonym key or the link key. The cost is three references to provision. This also needs task 191 to agree.

**OD-10. Do the interface checks (required, unbounded, public) ship? Also decides whether `member.interface` becomes required.**
**Decided (owner delegated, 2026-10-09): option (ii), the recommended one.**
- (i) Yes: `MISSING_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE`, `PUBLIC_MEMBER_INTERFACE`.
- (ii) Only the first two; address classification is a heuristic.
- (iii) None; documentation only.
- **Recommend (ii)** until the spike shows address classification is reliable on the supported platforms. A false refusal on a legitimate cloud address is a support cost; a wrong accept is a false assurance.

**OD-11. Scope and order of the 0.8 build.**
**Decided (owner delegated, 2026-10-09): option (ii), the recommended one.**
- (i) All six maps, the pause document, member cards and the serialisation filter in one change set.
- (ii) Staged inside 0.8: the filter and startup checks first, then approval, oversight and re-identification, then identity, budget and caller rate, then member cards.
- (iii) Defer some structures to 0.9.
- **Recommend (ii)**, all landing before the 0.8 tag. Partial sealing leaves gaps in the test matrix; staging only orders the work so the first wave removes the harms ranked highest in 0.3. (iii) conflicts with the 0.9 API and configuration freeze.

**OD-12. External review scope.**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Include the sealing envelope, key derivation and the pause document in the 0.9 external security review alongside sealed links.
- (ii) Review sealed links only.
- **Recommend (i).** New cryptography in a privacy path; the same reasoning the roadmap applies to links.

**OD-13. What may the documentation and the 1.0 release notes claim about clustering?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
D-RM-1 as recorded in `PLAN.md` says only that 1.0 supports multiple instances, with the security designed in 0.7 and built in 0.8. It does not define "secured". The options:
- (i) "Multiple instances are supported. Cluster state is sealed against reading and forging by anyone without the cluster key. Transport protection and network isolation are the deployer's responsibility and are not verified by Data Prism. A party that can reach the member port can still delete or replay entries, hold locks, join as an unverified member and deny service (budgets and rate limits are bounded by a per-instance floor; pauses fail closed; re-identification entries can be lost to deletion)".
- (ii) "Multiple instances are secured" without qualification. Not supportable on open-source Hazelcast: it would overstate what 2.3.7 lists as not stopped.
- (iii) "Multiple instances are supported; secure them with network controls" with no application-level claim (the OD-1 (ii) position).
- **Recommend (i).** It states exactly what is enforced and what is not, in the same terms as 2.3.7, so a reader can check the claim against the tests in section 5. It requires the roadmap line "supported and secured" to be reworded by the owner.

**OD-14. Pause document: split-brain merge and equal-order rule.**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Union of the pause sets on any conflict or merge (2.3.3). Fails closed; a resume on one side of a partition may be undone and must be repeated.
- (ii) Highest (sequence, refresh) wins. Simpler; a pause issued on the smaller side of a partition is silently dropped, which lifts a pause.
- (iii) Refuse everything until an operator re-issues the state.
- **Recommend (i).** Rule 2: when the state is in doubt, the safe direction for a pause is paused.

**OD-15. May a member re-write a missing pause document from its own memory?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
- (i) Yes, with `putIfAbsent`, only from a document accepted within the last lease, and only if no verified member reports a higher sequence (2.3.3).
- (ii) No. An absent document stays a global pause until an operator issues a pause or resume, which writes a new one.
- (iii) Yes, unconditionally.
- **Recommend (i).** It heals a deletion without operator action, and the conditions prevent a partitioned member from re-writing an older state. (ii) is the more conservative choice; choose it if self-healing after a hostile delete is judged less valuable than having no automatic writer. (iii) is rejected because it lifts pauses.

**OD-16. What does `PrivacyCluster.using(instance)` do about settings it cannot change?**
**Decided (owner delegated, 2026-10-09): option (i), the recommended one.**
`using` wraps an instance an integrator started. It cannot add a serialisation filter, turn off Jet, user-code deployment or phone-home (4.5).
- (i) Refuse (`UNSAFE_HAZELCAST_SERIALIZATION` for a missing or non-allowlisting filter, an `UNSAFE_*` code for the rest). Breaks integrators who pass an unfiltered instance, and needs a release note.
- (ii) Accept, log a warning and report `clusterSecurity` health as degraded.
- (iii) Accept only with an explicit acknowledgement property naming the unmet settings.
- **Recommend (i) for a joining member.** An unfiltered member protocol is a code-execution surface that sealing does not address, and the integrator can set the filter on their own `Config` with one block. (iii) is the fallback if the owner wants the break softened.
