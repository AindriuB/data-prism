# 131 — Make PrivacyCluster membership explicit and refuse unsafe discovery

**Repo:** `.`
**Release:** 0.4.1
**Base branch:** `claude/release-0.4.1` (not `main`)
**Depends on:** none
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterMembership.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java *(new, or a nested exception type in `PrivacyCluster`; implementer's choice)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/** *(new `PrivacyClusterMembershipTest.java`, plus the existing tests only where a stricter `PrivacyCluster` forces a change, and the port choice in `ApprovalStoreContractTest`/`HazelcastOversightTest`)*

## Goal

With v0.4.0, `PrivacyCluster.embedded(new Config(), …)` starts a member with
Hazelcast 5.7.0 defaults. That means cluster name `dev`, auto-detection on, no
join list and phone-home on, so separate instances never cluster. On Kubernetes
such a member may instead join an unrelated `dev` cluster without
authentication. This task makes the hazelcast module translate an explicit,
validated membership description into a `Config`. It always disables
auto-detection, multicast and phone-home, and it refuses unsafe configurations
before any member starts.

## Settled facts (verified 2026-10-06 against `hazelcast-5.7.0.jar`)

- Kubernetes discovery ships in OSS core: `com.hazelcast.kubernetes.*` and
  `com.hazelcast.config.KubernetesConfig` are in the jar. No extra dependency
  is needed.
- OSS 5.7.0 has no TLS engine. Only the interfaces `nio/ssl/SSLContextFactory`
  and `SSLEngineFactory` are present; `BasicSSLContextFactory` is absent.
  Member authentication (`SecurityConfig`) is Enterprise. Pin both facts with
  the probe test below.

## Context

- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java:63-90:
  `embedded`, `using` and `configure`. The current javadoc says cluster name,
  discovery and TLS are left to the caller. That stops being true here.
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastOversightTest.java:50-75
  shows the existing two-member TCP-IP-on-loopback pattern, which picks its port
  from `nanoTime`.
- docs/plan/PLAN.md "Follow-ups from the task 120 review": the two-member
  contract test picks a port from `nanoTime` and never retries on a collision.
  Fix that here.
- Property names that task 132 binds onto this type are fixed in that task's
  "Shape". Keep the field names aligned with them.

## Shape

`ClusterMembership` is an immutable value with these parts:
- `clusterName`;
- a join, which is exactly one of:
  - `TcpIp(List<String> members)`, where each member is `host` or `host:port`;
  - `Kubernetes(String namespace, String serviceName, String serviceDns)`,
    with exactly one of `serviceName` and `serviceDns`;
  - `None`, a single member that never accepts others;
- `port`, an int defaulting to 5701;
- `Optional<String> interfaceAddress`.

`Config toConfig()` builds a fresh `Config`. `PrivacyCluster.embedded(ClusterMembership, boolean)`
is added. `PrivacyCluster.embedded(Config, boolean)` stays, and is hardened as
described under Acceptance.

## Acceptance

- [ ] `ClusterMembership.toConfig()` always produces these, each asserted in
      `PrivacyClusterMembershipTest`:
      - `getJoin().getAutoDetectionConfig().isEnabled() == false` and
        `getJoin().getMulticastConfig().isEnabled() == false`;
      - property `hazelcast.phone.home.enabled` is `false`;
      - `getNetworkConfig().isPortAutoIncrement() == false`;
      - the given cluster name and port.
- [ ] For each join:
      - `TcpIp` enables only the TCP-IP join, with exactly the given members.
      - `Kubernetes` enables only `getJoin().getKubernetesConfig()`. It carries
        property `namespace`, plus exactly one of `service-name` or
        `service-dns`, and nothing else is enabled.
      - `None` enables TCP-IP with an empty member list, and the interface and
        bind address are `127.0.0.1`, so a lone member listens on loopback only.
- [ ] Construction refuses each of these with a `PrivacyClusterRefusal`
      (an `IllegalStateException` subtype) whose `code()` is the given code,
      and whose message never echoes a member address or namespace beyond the
      field name:
      - blank cluster name: `MISSING_CLUSTER_NAME`;
      - cluster name equal to `dev`, ignoring case and surrounding whitespace:
        `RESERVED_CLUSTER_NAME`;
      - `TcpIp` with no members, or a member that is blank or has a port
        outside 1–65535: `INVALID_CLUSTER_MEMBERS`;
      - `Kubernetes` with a blank namespace, or with both or neither of
        service name and service DNS: `INVALID_KUBERNETES_JOIN`;
      - port outside 1–65535: `INVALID_CLUSTER_PORT`;
      - an interface that is neither an IPv4 literal nor a Hazelcast
        wildcard pattern such as `10.0.*.*`: `INVALID_CLUSTER_INTERFACE`.
- [ ] `PrivacyCluster.embedded(Config, boolean)` does all of the following
      before starting a member:
      - forces auto-detection, multicast and phone-home off on the supplied
        `Config`;
      - refuses a blank or `dev` cluster name with the codes above;
      - refuses a `Config` whose `getNetworkConfig().getSSLConfig()` is
        enabled, or whose `getSecurityConfig()` is enabled, with
        `HAZELCAST_TLS_UNSUPPORTED`.

      A test passes `new Config()` and asserts `RESERVED_CLUSTER_NAME`.
- [ ] `PrivacyCluster.using(HazelcastInstance, boolean)` inspects
      `instance.getConfig()`:
      - auto-detection or multicast enabled gives `UNSAFE_HAZELCAST_DISCOVERY`;
      - a `dev` or blank cluster name gives the codes above.

      It never shuts down an instance it did not start. A test covers each case.
- [ ] Probe test `ossHasNoMemberTls`: on the test classpath,
      `Class.forName("com.hazelcast.nio.ssl.BasicSSLContextFactory")` throws
      `ClassNotFoundException`. Its comment explains that this pins the
      Enterprise-only finding the 0.4.1 security decision rests on.
- [ ] Two `PrivacyCluster.embedded(ClusterMembership.tcpIp(...))` members on
      `127.0.0.1` with the same unique name form a 2-member cluster, and
      `instance().getCluster().getMembers().size() == 2` within 30s. A third
      member with a different cluster name stays at size 1.
- [ ] Ports in this module's multi-member tests are chosen with a
      `ServerSocket(0)` probe and retried up to 3 times on a bind failure. No
      test derives a port from `nanoTime`.
- [ ] The `PrivacyCluster` class javadoc states what is now enforced. It says
      transport encryption and member authentication are not provided in OSS,
      and that network isolation is the deployer's responsibility.
- [ ] `mvn -pl data-prism-hazelcast -am verify` exits 0.

## Out of scope

- Spring properties, startup refusals in configuration, and the bean wiring.
  Those are task 132, and `data-prism-spring-boot-autoconfigure` is not
  touched here.
- Docs pages. That is task 135.
- Map sizing and the other PLAN follow-ups listed under 127, such as the
  failure metric and the size cap.
- Changing the Hazelcast version or adding `hazelcast-enterprise`.

## Added from task 133 review (2026-10-06), required

- `ClusterMembership.toConfig()` must set `hazelcast.socket.bind.any=false`
  whenever an interface pattern is configured, for TcpIp and Kubernetes as well as
  None. Hazelcast's default `bind.any=true` makes the member listen on every
  interface; `interfaces` only chooses the advertised address. Test it: the
  built `Config` has `bind.any=false` whenever an interface is set. If no interface is
  set for tcp-ip or kubernetes, decide and test whether to refuse
  (fail closed, e.g. `MISSING_MEMBER_INTERFACE`) or bind to any. Recommended:
  allow bind-any, but document it. Task 133's Compose example relies on this.
