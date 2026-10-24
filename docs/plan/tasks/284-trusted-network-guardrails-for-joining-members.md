# 284 — Refuse a joining member that is not declared and bound to a trusted network

Filed 2026-10-23 for owner decision (b): 1.0 supports multiple instances on a trusted network only (D-RM-1 (a) as amended, `PLAN.md`). The sealed-store briefs 224, 226, 229 to 231 and 264 are deferred past 1.0, so on a multi-member cluster approvals and counters stay unsealed (225 seals identity and re-identification entries in 0.8). This task makes that limit a startup refusal instead of a sentence in the docs.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 210.
**Depends on:** 210 (owns `docker/multi-instance/*.yaml`, `data-prism-server/src/test/**` configuration and the existing Spring tests, and is the previous editor of the shared Spring files below). The edge to 270 was removed on 2026-10-24: 270 moved to wave 6 (PR #161), so this task no longer edits `docs/plan/design/contract-register.md`; 286 writes its rows
**Wave:** 6
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java *(the `trusted-network` and `member.trusted-ranges` properties, and `clusterSettingsSet`, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validateCluster`, `validateMemberInterface` and new private methods they call, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/MemberInterfaceCheck.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/validation/MemberInterfaceCheckTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/TrustedNetworkConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterConfigurationTest.java, ClusterKeyConfigurationTest.java, ClusterLegacyGaugeTest.java, ClusterLockTimeoutConfigurationTest.java *(adding `trusted-network: true` to a `tcp-ip` or `kubernetes` context only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/** *(adding the opt-in to a joining member's properties only)*
- docker/multi-instance/compose.yaml, docker/multi-instance/kubernetes.yaml *(the `DATAPRISM_HAZELCAST_TRUSTED_NETWORK` setting and its comment only; the NetworkPolicy is not changed)*
- docker/smoke/java-runtime-smoke.sh *(the opt-in only, and only if the script sets join settings itself)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/MultiInstanceManifestTest.java *(new: the NetworkPolicy check)*
- docs/configuration.md *(rows for the two new properties and three new codes, the refusal-order table, the keyless loopback-literal rule from 227, and the `INVALID_CLUSTER_INTERFACE` wording, only)*

## Goal
A Spring-configured member that joins other members (`join.mode` `tcp-ip` or `kubernetes`) starts only when the operator has declared the network trusted and the member listens only on a private interface. Otherwise it refuses at startup with a code that names the property. 1.0 keeps approvals and counters unsealed on a cluster. These refusals stop that state being exposed on a network nobody chose. The plain-Java entry points get the same rule in 287.

## Context
- Owner decision (b), 2026-10-23, and D-RM-1 (a) as amended (`PLAN.md`, "Road to 1.0").
- docs/plan/design/cluster-security.md 4.2, the `PUBLIC_MEMBER_INTERFACE` row (:630) and OD-10. The name was reserved and "not built"; this task builds it, with the private-range rule below instead of the attestation rule. The attestation (`isolation.attestation`, `MISSING_ISOLATION_ATTESTATION`) is deferred with 230; `trusted-network` is the 1.0 declaration in its place.
- data-prism-spring-boot-autoconfigure/.../validation/DataPrismPropertiesValidator.java `validateCluster` (:229-311 at filing) and `validateMemberInterface` (:367-380): the refusal style and order. `MemberInterfaceCheck` keeps Hazelcast off the validator's class path (222's Outcome: do not fold it back).
- data-prism-hazelcast/.../ClusterMembership.java `validInterface` and `matchesEveryAddress`: the interface grammar (an IPv4 literal or an octet pattern with `*` and `a-b` ranges). It has no IPv6 form, so a unique-local (ULA, `fc00::/7`) interface cannot be expressed today. The private rule covers ULA for when the grammar gains IPv6; until then an IPv6 value stays `INVALID_CLUSTER_INTERFACE`.
- Already on `main` since 222: a `tcp-ip` or `kubernetes` join without `member.interface` refuses with `MISSING_MEMBER_INTERFACE`, both in Spring (`validateMemberInterface`) and in `ClusterMembership.toConfig()`. This task pins it and does not rebuild it.
- Sealed links on joining members: core 255 refuses `dataprism.links.enabled=true` and a configured link key on `tcp-ip` and `kubernetes` with `MISSING_SHARED_LINK_LIMITER` (264's fallback (c), now the 1.0 behaviour because 264 is deferred). That refusal and its table-driven test belong to 255, which comes later in the graph (wave 11), not to this task.
- Moved from 230 (deferred), from the external review of PR #154, round 5: the `INVALID_CLUSTER_INTERFACE` wording in `docs/configuration.md` (this task) and the register (286) says a `hazelcast.*` address property that makes a single member reachable refuses with that code. That is true only for a keyed solo member; a keyless one refuses `MISSING_CLUSTER_KEY`. Say so in both. 213 makes the same correction in `docs/multiple-instances.md`.
- From 227 (coordinator decision 2026-10-23, recorded in 227's Acceptance): a keyless member's `localAddress` and `publicAddress` must be a literal loopback address; `localhost` is refused. Write the rule into `docs/configuration.md` from 227's brief. 227 runs in the same wave, so describe the rule as decided and shipped by 227; no edge is needed, because the files do not overlap.
- From the external review of PR #164 (2026-10-23, Medium): a private address is not a trusted network. With approvals and counters unsealed, anyone who can reach the member port can forge them, whatever the address range. `trusted-network: true` is therefore an isolation assertion by the operator, and every place it appears says so in the same words (see Required shape).
- `ClusterKeyConfigurationTest` (222): the table-driven refusal style to follow.

## Required shape
- `dataprism.hazelcast.trusted-network`: boolean, default `false`. On `embedded` with `join.mode` `tcp-ip` or `kubernetes` it must be `true`; otherwise startup refuses with `MISSING_TRUSTED_NETWORK`. The message names the property and states the isolation assertion: setting `trusted-network: true` asserts that only Data Prism members can reach the member port (`dataprism.hazelcast.member.port`, 5701 by default); approvals and counters on that port are unsealed, so anyone else who can reach it can read and forge them. The property's metadata description and its `docs/configuration.md` row state the same assertion. Each joining member that starts with it logs one INFO line at startup stating the assertion and the member port number, with no address. With `single-node`, with `join.mode: none`, or beside an application-supplied `PrivacyCluster`, setting it (to either value) refuses with `CLUSTER_SETTINGS_IGNORED`, as 222 does for the keys.
- `dataprism.hazelcast.member.trusted-ranges`: a list of IPv4 CIDR blocks, default empty. It applies only where `trusted-network` does, and is refused with `CLUSTER_SETTINGS_IGNORED` elsewhere. An entry that does not parse, or that is not wholly inside the private blocks below and has a prefix shorter than `/24` (from the external review of PR #164: a public `/8` must not be accepted with only a warning), refuses with `INVALID_TRUSTED_RANGE` (the message names the property and the entry's index, never the value).
- Private means: every address `member.interface` can match lies in `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` or `127.0.0.0/8`, or in one `trusted-ranges` entry. ULA (`fc00::/7`) and `::1` count as private when the grammar accepts IPv6. Because each octet of a pattern is a range, the set is contained in a block when its lowest and highest addresses both are. A pattern that spans two blocks without lying inside one (`172.15-16.*.*`) is refused. Link-local (`169.254.0.0/16`), shared (`100.64.0.0/10`) and every other address refuse with `PUBLIC_MEMBER_INTERFACE` unless a `trusted-ranges` entry covers them.
- Order: after `MISSING_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE` and `INVALID_CLUSTER_INTERFACE`, then `PUBLIC_MEMBER_INTERFACE`, then `MISSING_TRUSTED_NETWORK`. A value that fails an earlier check never reaches a later one. `docs/configuration.md`'s refusal-order table shows the order.
- `member.interface` and `trusted-ranges` values never appear in a refusal message or a log line. A member started with a `trusted-ranges` entry outside the private blocks (at most `/24`, as above) logs one WARN line at startup with the count of such entries only.

## Acceptance
- [ ] Tests are written first, and the Outcome shows each new test failing on the base commit. `git stash` is not used to show it: check out the base commit in a separate worktree.
- [ ] `TrustedNetworkConfigurationTest`, table-driven, one row per case, each asserting the code and that the message names the property and contains no interface or range value:
  - `tcp-ip` and `kubernetes`, interface `10.0.*.*`, keys configured, `trusted-network` unset: `MISSING_TRUSTED_NETWORK`.
  - The same with `trusted-network: false`: `MISSING_TRUSTED_NETWORK`.
  - The same with `trusted-network: true`: starts.
  - `trusted-network: true` and interface `203.0.113.*`, `198.51.100.7`, `169.254.1.*`, `100.64.0.*` or `172.15-16.*.*`: each `PUBLIC_MEMBER_INTERFACE`.
  - `trusted-network: true` and interface `127.0.0.1`, `10.*.*.*`, `172.16-31.*.*` or `192.168.4.*`: each starts.
  - `trusted-network: true`, interface `203.0.113.*`, `trusted-ranges: [203.0.113.0/24]`: starts, and logs one WARN line with the count 1 and no address.
  - `trusted-ranges: [203.0.113.0/33]`, `[not-a-range]`, `[0.0.0.0/0]`, `[198.0.0.0/8]` or `[198.51.100.0/23]`: each `INVALID_TRUSTED_RANGE`. `[10.0.0.0/8]` (private) is accepted.
  - The `MISSING_TRUSTED_NETWORK` message contains the isolation assertion text; a started joining member logs it at INFO exactly once (asserted through a log capture), with the member port and no address.
  - `tcp-ip` with no `member.interface` and `trusted-network: true`: `MISSING_MEMBER_INTERFACE` (pinned: this already refuses on `main`).
  - `single-node`, `join.mode: none`, and an application-supplied `PrivacyCluster` bean, each with `trusted-network: true`, and each with `trusted-ranges` set: `CLUSTER_SETTINGS_IGNORED`.
  - `join.mode: none` with neither property: starts, unchanged.
- [ ] `MemberInterfaceCheckTest` covers the private rule on its own, with no Spring context: each boundary address (`9.255.255.255`, `10.0.0.0`, `10.255.255.255`, `11.0.0.0`, `172.15.255.255`, `172.16.0.0`, `172.31.255.255`, `172.32.0.0`, `192.167.255.255`, `192.168.0.0`, `192.168.255.255`, `192.169.0.0`) and the pattern forms above. Each mutant that widens or narrows a block by one address is killed (the Outcome shows one).
- [ ] Every existing context that joins other members passes after adding `trusted-network: true` and nothing else: the four Spring test classes in Owns, every `data-prism-server` cluster test, and the multi-instance Compose and Kubernetes manifests. `git diff` on those files shows only the opt-in lines.
- [ ] `docker/multi-instance/compose.yaml` and `kubernetes.yaml` set `DATAPRISM_HAZELCAST_TRUSTED_NETWORK: "true"`, with a comment that states the isolation assertion and what 1.0 leaves unsealed.
- [ ] The member-port NetworkPolicy stays: `kubernetes.yaml` keeps the `data-prism-cluster-members` NetworkPolicy, whose ingress to the member pods on port 5701 is only from pods labelled `app: data-prism`. `MultiInstanceManifestTest` (new) parses the manifest and fails if that policy is missing, selects other pods, admits any other source on the member port, or names a port other than the configured member port. The Outcome shows it failing with the policy removed. The comment above `DATAPRISM_HAZELCAST_TRUSTED_NETWORK` names the policy as what makes the assertion true on Kubernetes. The Compose stack still starts two members that form one cluster (the existing multi-instance smoke, or `docker compose -f docker/multi-instance/compose.yaml up` with the result in the Outcome).
- [ ] `docs/configuration.md` has rows for `trusted-network` (stating the isolation assertion) and `member.trusted-ranges` (stating the `/24` limit for non-private ranges), rows for `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK` and `INVALID_TRUSTED_RANGE`, and the refusal-order table in the order above. The `INVALID_CLUSTER_INTERFACE` sentence states the address-property refusal for a keyed solo member only, and says a keyless one refuses `MISSING_CLUSTER_KEY`. The keyless loopback-literal rule (227) is stated.
- [ ] The Outcome lists, for 286, the contract-register rows this task's codes and properties need: `PUBLIC_MEMBER_INTERFACE` (built, private-range condition), `MISSING_TRUSTED_NETWORK`, `INVALID_TRUSTED_RANGE`, both properties, and the `INVALID_CLUSTER_INTERFACE` keyed or keyless wording. `git diff` shows no change to `docs/plan/design/contract-register.md`.
- [ ] `PropertyNamesFrozenTest`, `ConfigurationMetadataDocumentedTest` and `AutoConfiguredBeanClassificationTest` pass, and the frozen list and metadata diffs contain only this task's lines.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`.
- [ ] `./mvnw -B verify` exits 0 over the full reactor. Report the counts.

## Out of scope
- The plain-Java entry points (`PrivacyCluster.embedded`, `using`, `ClusterMembership`, `ClusterSecuritySettings`). 287 gives them the same rule. Nothing under `data-prism-hazelcast/` changes here.
- The `MISSING_SHARED_LINK_LIMITER` refusal for links and page links on joining members (core 255).
- IPv6 member interfaces. The grammar stays IPv4.
- `isolation.attestation`, health, tamper reporting and the other 230 properties (deferred with 230; health is 286).
- `docs/multiple-instances.md`, `docs/migration-0.8.md`, `docs/eu-ai-act.md` and `CHANGELOG.md` (213). Put the facts in the Outcome: both properties, the three codes, the private rule, and the before and after of a `tcp-ip` configuration.
- Root `compose.yaml` and `docker/server/application.yaml`: the quickstart is a solo member (262).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg`, any `.env*` file or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Addresses in tests and docs are from the private blocks or the documentation blocks (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`), never a real host.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
