# 134 — Prove oversight, budget and re-identification hold across cluster members

**Repo:** `.`
**Release:** 0.4.1
**Base branch:** `claude/release-0.4.1` (not `main`)
**Depends on:** 132
**Owns:**
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/** *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java *(additive only: a constructor or builder option for cluster arguments, and anything needed to run two harnesses in one JVM; existing callers unchanged)*

## Goal

Show, against the real server with the operator surface, that state which
0.4.0 described as cluster-wide really is shared once two or three instances
have joined one embedded cluster. That state is the pause, approvals, the read
budget, the per-caller rate limit and re-identification. The task also proves
that misconfigured clustering refuses startup through the packaged server's
failure analyzer.

## Context

- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/OperatorHarness.java:170-215:
  starts a full server with an operator port, a JWKS identity server and
  `extraArguments`. Check its static state, such as the catalogue system
  property, before starting two harnesses in one JVM.
- OperatorEndToEndTest.java, ReidentificationEndToEndTest.java and
  OperatorSurfaceTest.java: how pause, approvals and re-identification are
  driven, and what each returns.
- ConfigurationRefusalMessageIT.java: the pattern for a packaged-server refusal
  test that asserts the code and the absence of a stack trace.
- task 132's "Shape" gives the property names; task 131 gives the member-count
  API (`PrivacyCluster.instance().getCluster().getMembers()`).
- docs/configuration.md `dataprism.oversight`: the budget, rate-limit and
  approval property names.

## Acceptance

- [ ] A shared helper starts N harnesses (N = 2, and 3 for the member-loss
      case). Each uses `topology=embedded`, the same unique `cluster-name`,
      `join.mode=tcp-ip` with every member on `127.0.0.1`, and a distinct free
      `member.port` from a `ServerSocket(0)` probe with retry. Each uses the
      same HMAC key reference. The helper waits up to 30s for
      `getMembers().size() == N` before any assertion, and fails with the
      observed size if that never happens.
- [ ] **Pause**:
      - `POST /operator/pause {"target":"ALL"}` on A, then an MCP call on B is
        refused with `DATAPRISM_PAUSED`;
      - resume on B, and the same call on A succeeds;
      - a `TOOL` pause on B refuses that tool on A with `TOOL_PAUSED`.
- [ ] **Approvals**: with the tool in `approval-required-tools`:
      - a call on A returns `APPROVAL_REQUIRED` with an `approvalId`;
      - a different principal approves it on B's operator port;
      - the identical call on B then succeeds once;
      - a repeat on A is refused as not approved, because consumption is
        cluster-wide.
- [ ] **Read budget**: with a per-subject budget of 3, two reads on A and
      one on B succeed, and a fourth read on either member is refused with the
      budget-exhaustion code.
- [ ] **Rate limit**: with `caller-rate-limit.requests=2` and a long window,
      one call on A and one on B succeed, and a third on either is refused with
      the rate-limit code.
- [ ] **Re-identification**: a pseudonym is returned by an MCP call on A. A
      re-identification request on B's operator port is approved by a
      different principal on A, and resolves to the fixture subject id. The
      same pseudonym is identical when the same subject is read through B.
- [ ] **Member loss, fail closed**: with 3 members:
      - pause `ALL` on A, then stop A;
      - B and C still refuse with `DATAPRISM_PAUSED`, because the pause flag
        has a backup.

      This pins backup-count 1. The test's comment states that losing the
      owner and the backup together loses the flag; task 135 documents this.
- [ ] **Startup refusals through the packaged server** (one new IT, in the
      pattern of `ConfigurationRefusalMessageIT`): `embedded` with no cluster
      name, with `cluster-name=dev`, and with no join mode. Each exits non-zero,
      prints its code, and shows no stack frame.
- [ ] Fixtures use only synthetic subjects that already exist in the harness.
      There are no real names, addresses or tokens.
- [ ] `mvn -pl data-prism-server -am verify` exits 0 on two consecutive runs.
      The return gives the wall time of the cluster test class.

## Out of scope

- Any `src/main` change. If a case fails because of a defect, stop and
  report it with the failing assertion. Do not fix it here.
- Partition (split-brain) behaviour and Hazelcast split-brain protection.
- Docker or Kubernetes runs. That is task 133.

## Attempt 1 — failed

Tester: PASS (two full-reactor runs green; MultiMemberOperatorTest took 53s and 20s; no port leaks).
Reviewer: CHANGES (head c3bb155). Every scenario except member loss is shown to fail
without clustering, and the budget-100 deviation is accepted. Required for attempt 2:
1. **The member-loss test does not pin backup-count 1.** `a.close()` is a graceful shutdown,
   which migrates partitions, and nothing ensures A owns the `pausedAll` partition. The test passes
   even with backup-count 0. Fix: find the member that owns the pause key (partition owner,
   or the oversight map's `localKeySet()`), stop it **ungracefully**
   (`getLifecycleService().terminate()`), then close its harness. Assert that the
   survivors still refuse. Show it red: with backup-count 0 it must fail. Prove it locally,
   e.g. by temporarily patching, and describe how; do not commit the patch.
2. ClusterConfigurationRefusalIT: also assert `doesNotContain("\tat ")`.
3. ClusterMembers.close(): close each member in its own try, so that one failure does not
   leak the rest.
4. OperatorHarness: release the SSL ref-count, and restore the JVM default, if startup
   throws between the increment (:140) and the try (:242).
Follow-up, not 134: in the packaged server, `topology=embedded` with no source adapter refuses with a
misleading MISSING_SHARED_BUDGET before the cluster validation runs. It is fail-closed; record it for 0.4.x.

## Attempt 2 — approved, with CI hardening required before merge

Tester: PASS (3 sequential full-reactor runs green; MultiMemberOperatorTest took 19–52s). Reviewer: APPROVE.
These go in **before merge**, because the 0.4.1 PR's CI is the first CI run of this test and GitHub
runners have 2–4 vCPUs. Attempt 3 is test code only:
1. In `ClusterMembers.start`, after the member count is reached, wait for
   `getPartitionService().isClusterSafe()` on every member (bounded, e.g. 60s, failing with the state seen).
2. The member-loss test calls `ownerOf` again just before `terminate`, and asserts the owner is unchanged.
3. Raise the test MCP client's initialization and request timeouts to 180s wherever the
   cluster tests build clients. If the helper is shared with OperatorHarness, change it only for the
   cluster tests, unless raising it globally is harmless.
