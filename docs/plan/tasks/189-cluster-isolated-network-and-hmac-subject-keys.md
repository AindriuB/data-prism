# 189 — Gate multi-member joins behind an isolated-network declaration and key cluster maps by an HMAC of the subject

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` (f74757a at filing, post-0.6.0)
**Depends on:** none
**Decided (owner, 2026-10-15):**
- **D-1.0-2.** There is no network join by default; a single member is the default. A multi-member
  cluster is possible only through an explicitly named isolated-network option. Breaking change accepted.
  This task must first check, against the Hazelcast jar the build actually resolves, whether member TLS
  and member authentication are Enterprise-only, and record the result.
- **D-1.0-5.** Ships in 1.0.0; breaking changes accepted; no overload survives into 1.0 for compatibility.

**Not decided here:** where the subject-key HMAC key comes from (**D-1.0-7**, open) and what the
re-identification map stores (**D-1.0-3**, open). This task takes the key as an argument and does not
touch the re-identification value, so neither decision blocks it. Tasks 190 and 197 carry them.

**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/** *(not the re-identification `set` in `CachingSyntheticValueSource.java:131-135` or `ScopeIdentityIndex.java`, which task 190 owns)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(`dataPrismPrivacyCluster` and `join` only, behaviour-preserving; see the acceptance item)*

## Goal
On current `main`, 0.4.1 (tasks 131, 132, 138) already closed most of the original cluster gap:
the cluster is never built from a bare `Config`, auto-detection, multicast and phone-home are always
off, `dev` is refused, TLS settings are refused (`HAZELCAST_TLS_UNSUPPORTED`), and `embedded` refuses
without an explicit `join.mode`. Two things remain. A `tcp-ip` or `kubernetes` join is accepted
without the deployer ever stating that the member port is on an isolated network. And every
identity and budget map key embeds the raw subject id, which may be a national identifier
(`ScopeKeys.java:29-39`), readable by anyone who reaches the member port
(`docs/architecture.md:315-329`, "Accepted consequence"). This task closes both in the library.

## Context
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java:36-155 — the hardening Javadoc, `embedded`, `using` and `configure`
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterMembership.java:18-90 — the `Join` variants and factories
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeKeys.java:16-78 — `identity`, `budget` and `scopePrefix`
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java:60-135 and HazelcastScopeBudget.java:30-92 — the two writers of subject-bearing keys
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java:9-18 — the refusal codes
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastStoredValueBoundaryTest.java — the proof that the maps hold no raw fixture value; extend it, do not weaken it
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java:46-69 — the only production caller
- docs/plan/PLAN.md:183-200 (D-0.4.1-A) and :249-262 ("Deferred option ... secure Hazelcast clustering") — the earlier jar check against 5.7.0 and the "HMAC'd keys" recommendation this task partly revives. docs/plan/roadmap-ideas.md "Hazelcast cluster security" is the same item.
- docs/conventions.md, "Privacy rules a diff must satisfy" — fingerprints use a keyed HMAC, never `hashCode()` or a bare digest
- `pom.xml:74` — `hazelcast.version` 5.7.0

## Acceptance
- [ ] **First, before any code change:** run `./mvnw -B -q -pl data-prism-hazelcast dependency:tree -Dincludes=com.hazelcast` and record the resolved coordinates. Then, with a scratch test against that jar, enable `SSLConfig` on an embedded member's network config, and separately enable `SecurityConfig`, and record what each does, along with whether `com.hazelcast.nio.ssl.BasicSSLContextFactory` is on the classpath. Write the command, the coordinates and the observed outcome into `PrivacyCluster`'s class Javadoc and the close-out. If the result contradicts D-0.4.1-A (that is, either feature works in the open-source jar), stop and report before writing anything else. The scratch test is not committed.
- [ ] `ClusterMembership` has an explicit network declaration with exactly two states: a single member, and members on a declared isolated network. A `TcpIp` or `Kubernetes` join is accepted only with the isolated-network declaration. Without it, construction throws `PrivacyClusterRefusal` with new code `UNSECURED_CLUSTER_JOIN`. The public factories for multi-member joins are named so the call site states the isolation, for example `tcpIpOnIsolatedNetwork(...)` and `kubernetesOnIsolatedNetwork(...)`; the old `tcpIp(...)` and `kubernetes(...)` factories no longer exist. A test covers each join variant with and without the declaration.
- [ ] `PrivacyCluster.using(...)` takes the same declaration as an argument, and refuses with `UNSECURED_CLUSTER_JOIN` an instance whose join enables TCP/IP or Kubernetes discovery without it. `embedded(Config, ...)` is no longer public; the public way to start a member is through `ClusterMembership`. Tests cover both.
- [ ] A new public final class, for example `SubjectKeyHmac`, is built from at least 32 bytes of key material, which it copies. Its `toString()` contains no key bytes, and a test asserts this. It computes hex HMAC-SHA256 over a length-prefixed encoding of the scope id and the subject id, so the same subject keys differently in two scopes.
- [ ] `PrivacyCluster.embedded` and `using` have overloads that take a `SubjectKeyHmac`. A cluster built with one writes `ScopeKeys.identity` and `ScopeKeys.budget` keys that contain the scope prefix and the HMAC, and never the subject id. A test reads a fixture subject through `CachingSyntheticValueSource` and `HazelcastScopeBudget`, then asserts the fixture subject id is not a substring of any key or value in `IDENTITY_MAP` or `BUDGET_MAP`. Ending a scope by prefix still removes its entries, and a test proves this.
- [ ] Mutation proof: with `ScopeKeys.identity` reverted to embed the raw subject id, the new `HazelcastStoredValueBoundaryTest` case fails. Name it in the close-out.
- [ ] A keyed member records a key-check value (an HMAC of a fixed label, never the key) in a cluster map at startup. A second member with a different key refuses to start with new code `CLUSTER_SUBJECT_KEY_MISMATCH` and shuts down only the instance it started. A two-member test covers this. The reason is in the Javadoc: two keys silently split the read budget, which would fail open.
- [ ] The `embedded` and `using` overloads without a `SubjectKeyHmac` still exist so `ClusterBackedState` compiles, and keep today's raw keys. Their Javadoc says task 197 deletes them before 1.0.0. They are not `@Deprecated`, because they will never ship.
- [ ] `ClusterBackedState` changes only so it compiles. It passes the isolated-network declaration for `tcp-ip` and `kubernetes` and the single-member declaration for `none`, and calls the overload without a key. A comment names task 197 as the task that replaces this. `ClusterConfigurationTest` passes unmodified.
- [ ] No exception message, log line or `toString()` added by this task contains a subject id or key material.
- [ ] `./mvnw -B -pl data-prism-hazelcast,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- The re-identification map's value (task 190, D-1.0-3).
- Where the key comes from, properties, refusals at the Spring layer and `docs/configuration.md` (task 197, D-1.0-7).
- Encrypting map values, signing approvals, or a signed pause document. These are the rest of the deferred "secure clustering" option in PLAN.md and stay deferred.
- Hazelcast Enterprise, member TLS and member authentication.
- `docs/multiple-instances.md` and `docs/architecture.md` (task 198).
