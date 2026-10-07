# 138 — `join.mode: none` must refuse incoming joins

**Repo:** `.`
**Release:** 0.4.1 (blocker; found in review of PR #113)
**Depends on:** none
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ClusterMembership.java *(the `None` branch of `toConfig()` and its javadoc)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(javadoc only, if it describes `none`)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterMembershipTest.java
- docs/multiple-instances.md *(sentences describing `join.mode: none` only)*
- docs/configuration.md *(sentences describing `join.mode: none` only)*
- CHANGELOG.md *(the [0.4.1] bullets that describe `join.mode: none` only)*

## Goal

Review finding (P2) on `ClusterMembership.java:114`. `join.mode=none` enables
the ordinary TCP-IP joiner with an empty member list and binds to loopback.
That stops *outgoing* discovery. But Hazelcast 5.7.0 still accepts a compatible
*incoming* join request, so another member with the same cluster name on the same
host can join by targeting the `none` member's loopback port. Two separate local
deployments would then share pause flags, approvals, budgets and the
re-identification index. That contradicts the documented "explicit single member".

## Acceptance

1. **Red first.** Add a test that starts a `none` member. Then start a second member with
   the **same configured cluster name**, `tcp-ip` targeting `127.0.0.1:<none member's port>`,
   and interface 127.0.0.1. Assert that after a bounded wait (e.g. 15s) both members still see exactly
   one member. Commit it and show it fails on the current code. If it does not fail, report that,
   with evidence, and stop. The finding would then be wrong for 5.7.0.
2. **Fix.** The `none` member must not accept any incoming join. Required:
   - The effective Hazelcast cluster name for `none` is the configured name plus a
     per-process random suffix, at least 128 bits, e.g. `name + "-solo-" + UUID`. No other process can
     match it. The configured name is still validated as before.
   - Also disable every joiner for `none`: TCP-IP, multicast, auto-detection and discovery,
     so the member starts standalone, if Hazelcast 5.7.0 allows that without error. If it
     refuses, keep TCP-IP with an empty list and rely on the random name; say which in the return.
   - Keep the loopback binding and `bind.any=false`.
   Check in the 5.7.0 jar how join requests are validated, e.g. `ClusterJoinManager` and
   `JoinRequest` cluster-name checks, and cite what you found.
3. The test from item 1 passes. Existing `none` tests still pass. Add an assertion that
   the effective cluster name differs from the configured name and differs between two `none`
   members started from the same configuration.
4. **Docs and CHANGELOG.** Describe `none` as a member that neither discovers nor accepts other
   members. If you mention the cluster-name suffix, say it is internal and appears in logs.
5. **Build.** The full reactor `mvn clean verify` must pass. Never use `-rf` or run `mvn install`, and run one build at a time.
   Then run `mkdocs build --strict -f mkdocs.yml` with /Users/Andrew/workspace/data-prism/.venv-docs, check_site.py and
   check_changelog.py.

## Out of scope

`tcp-ip` and `kubernetes` modes. Open-source Hazelcast cannot authenticate incoming members there;
that is a documented limitation, and the secure-clustering work is a deferred option.
