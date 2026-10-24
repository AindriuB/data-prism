# 287 — Apply the trusted-network rule to the plain-Java cluster entry points

Filed 2026-10-23 for owner decision (b), as the second half of 284. 284 refuses a Spring-configured joining member that is not declared and bound to a trusted network. A plain-Java embedder reaches the cluster through `PrivacyCluster.embedded(ClusterMembership, …)`, `PrivacyCluster.embedded(Config, …)` and `PrivacyCluster.using(HazelcastInstance, …)`, and none of these checks anything of the kind. Rule 2 of the repository (fail closed) means one rule for every entry point. The 1.0 cluster state that stays unsealed is the same whichever way the member was started.

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 227, 228 and 284.
**Depends on:** 225 (owns `CachingSyntheticValueSource`, `ScopeIdentityIndex` and their tests in wave 5), 227 (owns `PrivacyCluster.java`, `ClusterMembership.java`, `PrivacyClusterRefusal.java` and `KeylessMemberIsolationTest.java`, and switches five store files, in wave 6), 228 (owns `HazelcastOversightTest.java` in wave 7), 284 (the rule, the codes and `MemberInterfaceCheck`, which this task makes delegate here)
**Wave:** 8 (moved from 7 on 2026-10-23, after 228)
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterSecuritySettings.java *(the trusted-network declaration only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/TrustedNetwork.java *(new: the declaration and the private-range rule)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(the checks in `embedded` and `using`, before a member starts or is wrapped, only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterMembership.java *(the private-interface check only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterRefusal.java *(insertions only: `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK`, `INVALID_TRUSTED_RANGE`)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/TrustedNetworkTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/TrustedNetworkEntryPointsTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/** *(existing tests and `TestMembers.java`: adding the declaration where a joining or multi-member instance is built, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(passing the declaration from `dataprism.hazelcast.trusted-network` and `member.trusted-ranges` to the `PrivacyCluster` bean only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/MemberInterfaceCheck.java *(delegating the private-range rule to `TrustedNetwork` only)*

## Goal
Every entry point that starts or wraps a member that joins, or already has, other members refuses unless it carries an explicit trusted-network declaration and the member listens only on a private interface (or one in the declared ranges). The codes are those 284 registered, and there is one copy of the rule, in the hazelcast module, which the Spring check calls.

## Context
- Task 284's brief and Outcome: the private rule, the order of the checks, the three codes, and the message rules (no interface or range value in any message).
- data-prism-hazelcast/.../PrivacyCluster.java: `embedded(ClusterMembership, …)` (:398-465 at 223), `embedded(Config, …)` (:652-715), `launch` (:717), `using` (:746-814), and the checks that `using` already applies (`checkClusterName`, `checkNoAdvancedNetwork`, discovery, `checkInstanceSettings`, `boundToLoopback`). The overloads without a `ClusterSecuritySettings` argument use `ClusterSecuritySettings.defaults()`.
- data-prism-hazelcast/.../ClusterMembership.java `toConfig` (`MISSING_MEMBER_INTERFACE` for a joining membership without an interface) and `validInterface`.
- data-prism-spring-boot-autoconfigure/.../validation/MemberInterfaceCheck.java: today it builds a `ClusterMembership` to borrow its rules. After this task it borrows the private rule the same way.
- `ClusterSecuritySettings` is new in 0.8 (223) and unreleased, so adding a component needs no migration note of its own. The plain-Java refusal is a behaviour change, and 213 documents it.

## Required shape
- `TrustedNetwork`: `undeclared()`, the default in `ClusterSecuritySettings.defaults()`, and `declared(List<String> extraRanges)`, which validates each range as 284 does (`INVALID_TRUSTED_RANGE`, including the refusal of a non-private range broader than `/24`). Its Javadoc states 284's isolation assertion word for word: declaring it asserts that only Data Prism members can reach the member port. A member started with a declaration logs that assertion at INFO once, with the member port and no address; on the Spring path the line is not logged twice. `ClusterSecuritySettings` carries one. The rule "every address this interface pattern can match is private or in a declared range" lives here, once.
- `embedded(ClusterMembership, …)` with a `tcp-ip` or `kubernetes` join: undeclared refuses with `MISSING_TRUSTED_NETWORK`, and an interface that is not private refuses with `PUBLIC_MEMBER_INTERFACE`, in 284's order and before any member starts.
- `embedded(Config, …)` on a config with `tcp-ip` or Kubernetes discovery enabled: no enabled interface refuses with `MISSING_MEMBER_INTERFACE`, and otherwise the same two checks apply, to every configured interface, before any member starts. Interfaces alone are not enough (second external review of PR #164): the same path also refuses `hazelcast.socket.bind.any` or `hazelcast.socket.server.bind.any` set to `true` (Config property or system property, read back the way Hazelcast resolves it, as 227 does for the keyed `ClusterMembership` path) with `PUBLIC_MEMBER_INTERFACE`, and a `localAddress` or `publicAddress` (the `NetworkConfig` field, or the `hazelcast.local.*` Config or system property) that is not private or in a declared range, with `PUBLIC_MEMBER_INTERFACE`. `using(…)` applies the same checks to the supplied instance's config.
- `using(…)` on an instance whose config joins or which already has more than one member: the same two checks apply to the configured interfaces and to the address the member is bound to. On refusal the instance is left running, as `using` does today.
- A solo member (`join: none`, or a loopback-only supplied instance) needs no declaration, and is never refused for one.

## Acceptance
- [ ] Tests are written first, and the Outcome shows each new test failing on the base commit. Do not use `git stash` for this: check out the base commit in a separate worktree.
- [ ] `TrustedNetworkEntryPointsTest`, one row per entry point (`embedded` with a membership, `embedded` with a `Config`, `using`) and case: undeclared and joining gives `MISSING_TRUSTED_NETWORK`; declared with interface `198.51.100.*` gives `PUBLIC_MEMBER_INTERFACE`; declared with `198.51.100.0/24` in the ranges and the same interface starts; declared with `10.0.*.*` or `127.0.0.1` starts; a `Config` that joins with no enabled interface gives `MISSING_MEMBER_INTERFACE`; a solo member starts undeclared. For `embedded`, no Hazelcast instance is left running after a refusal (asserted through `Hazelcast.getAllHazelcastInstances()`). For `using`, the supplied instance is left running.
- [ ] `using` on a supplied instance whose configured interface is private but whose bound address is not refuses with `PUBLIC_MEMBER_INTERFACE`.
- [ ] `embedded(Config, …)` and `using(…)`, declared, joining, interface `10.0.*.*`, one row each: `hazelcast.socket.bind.any=true` and `hazelcast.socket.server.bind.any=true`, each as a Config property and as a system property; `NetworkConfig.setPublicAddress("198.51.100.7")`; `hazelcast.local.publicAddress` and `hazelcast.local.localAddress` set to `198.51.100.7`. Each refuses with `PUBLIC_MEMBER_INTERFACE` (no member left running for `embedded`; the supplied instance left running for `using`). The same rows with `10.0.0.5`, or with `198.51.100.7` in a declared range, start (the `bind.any` rows have no passing variant).
- [ ] `TrustedNetworkTest` runs 284's boundary-address table against `TrustedNetwork` directly, and `MemberInterfaceCheckTest` (284) passes unchanged through the delegation. `git grep` finds the private block constants in `TrustedNetwork.java` only.
- [ ] A Spring member configured with `trusted-network: true` starts, so the declaration is passed through: 284's `TrustedNetworkConfigurationTest` passes unchanged.
- [ ] No refusal message, exception or log line contains an interface, an address or a range value (a sentinel scan over the test's `RecordingLogger` output and the exception messages).
- [ ] Existing tests change only by adding the declaration where a joining or multi-member instance is built. `git diff --stat` on `data-prism-hazelcast/src/test` lists those files, and `git diff` shows no assertion removed or weakened.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Out of scope
- New properties, `docs/configuration.md` and the contract register (284 owns the properties and `docs/configuration.md`, 286 the register rows, which name the condition independently of the entry point).
- Sealing any store, and member cards (224 and 226 deferred; 225; 227).
- IPv6 interfaces.
- `docs/` pages and `CHANGELOG.md`: put the before and after of a plain-Java joining member, with the declaration, in the Outcome for 213.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Addresses come from the private or documentation blocks only.
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps.
