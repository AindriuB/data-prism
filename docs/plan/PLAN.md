# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse than
no plan.

Slice numbering (`S0`–`S12`) matches `docs/development-plan.md`, which carries
the sizing and the exit criteria. This file carries the order and the state.

---

## Decided

The five choices that blocked S0 were settled on 2026-09-08. Recorded here in
brief; the reasoning and the rejected alternatives are in
`docs/architecture.md#decisions-worth-knowing`.

| | Decision |
|---|---|
| Coordinates | Group `io.github.aindriub`, artifacts `data-prism-*`, package root `io.github.aindriub.dataprism` |
| MCP | Official MCP Java SDK directly, with Spring wiring written here. stdio in development, streamable HTTP in production |
| HMAC key | Local key supplied at startup through a `SecretKeyProvider` SPI. No vendor client in core |
| Audit sink | `AuditSink` SPI with a file/SLF4J implementation. No vendor client in core |
| Re-identification | Reverse map built in S7. The operator surface (S10) was deferred past V1; that deferral was lifted 2026-10-06 (D1) |

## Now

### EU AI Act plan — every planned task landed, 0.5.0 awaits the release cut

Tasks that make the audit, oversight and re-identification surfaces support an
EU AI Act deployment (Arts. 9, 10, 12, 14, 26) and GDPR Art. 9. The work is on
the planning branch `claude/data-prism-eu-compliance-04cf83`, not `main`. Task
files are in `docs/plan/tasks/`; each carries its own contract. A task starts
only when everything in its "Depends on" has merged.

On 2026-10-06 the owner split the work into two releases. 0.4.0 is EU AI Act
support. 0.5.0 is correlation ids and log-stack output, built on top of 0.4.0.

#### Release 0.4.0 — EU AI Act support (tasks 92-106, follow-ups 117-125)

| Wave | Task | What | Depends on | State |
|---|---|---|---|---|
| 1 | 92 | Audit record carries per-field dispositions and approval identity | none | done 2026-10-06 |
| 1 | 93 | Scrubbing engines report per-field dispositions | none | done 2026-10-06 |
| 1 | 94 | GDPR Art. 9 special-category classifications that fail closed | none | done 2026-10-06 |
| 1 | 95 | Oversight SPIs in core | none | done 2026-10-06 |
| 2 | 96 | Orchestrator audits dispositions, exposes `correlationId` | 92, 93 | done 2026-10-06 |
| 2 | 97 | External audit checkpoints (tail truncation, missing boots) | 92 | done 2026-10-06 |
| 2 | 98 | Tool admission in security: pause, approval gate, per-caller rate limit | 95 | done 2026-10-06 |
| 2 | 99 | Hazelcast-backed oversight state, failing closed | 95 | done 2026-10-06 |
| 2 | 100 | `data-prism-reidentification` module: audited, purpose-bound, optional four-eyes | 92, 95 | done 2026-10-06 |
| 3 | 101 | MCP tools enforce admission, return `correlationId` | 96, 98 | done 2026-10-06 |
| 3 | 102 | Audit segmented by day, expired segments purged with retention anchors | 97 | done 2026-10-06 |
| 4 | 103 | Wire checkpoints, segments and retention into configuration | 102 | done 2026-10-06 |
| 4 | 117 | Hash audit record v2 over an unambiguous, length-prefixed encoding | 102 | done 2026-10-06 |
| 4 | 118 | Undeclared payload keys render as `<undeclared>` on every refusal and warning sink | 101 | done 2026-10-06 |
| 4 | 119 | `InMemoryApprovalStore` matches `HazelcastApprovalStore` under one contract test | none | done 2026-10-06 |
| 4 | 121 | Correct REFUSED wording; document every `policyDecision` form | 101 | done 2026-10-06 |
| 4 | 122 | Find and remove the cause of `ServerStartupTest`'s intermittent `/health` 404 | none | done 2026-10-06 (cause unproven) |
| 4 | 125 | Integration tests derive the artifact version from the build | none | done 2026-10-06 |
| 5 | 120 | Cap live pending approvals per requester (`TOO_MANY_PENDING`) | 101, 119 | done 2026-10-06 |
| 5 | 123 | Orchestrator records the refusal code as `DENY:<code>` | 118, 121 | done 2026-10-06 |
| 5 | 124 | Undeclared property names never reach the model (`<undeclared-N>`) | 118 | done 2026-10-06 |
| 6 | 104 | Wire oversight, re-identification and operator-surface configuration | 99, 100, 101, 103, 120 | done 2026-10-06 |
| 6 | 126 | Reconcile 104's test with 123's `DENY:<code>` form | 104, 123 | done 2026-10-06 |
| 6 | 128 | Refusal codes validated before reaching client text (shared `RefusalCodes`) | 123 | done 2026-10-06 |
| 7 | 105 | Operator surface on a second port in the same process | 104 | done 2026-10-06 (attempt 3) |
| 8 | 127 | Wire the re-identification index (wrap application `SyntheticValueSource`) | 105 | done 2026-10-06 (attempt 3; `mvn clean verify`, `mkdocs build --strict`, `check_site.py` exit 0 on merged head) |
| 9 | 106 | EU AI Act support mapping and architecture records | 94, 105 | done 2026-10-06 (attempt 4; `mkdocs build --strict` and `check_site.py` exit 0) |
| 10 | 129 | Cut 0.4.0: poms, `server.json`, CHANGELOG, version literals, `serverInfo` from the build | all | done 2026-10-06 (attempt 3; release-profile and plain `mvn clean verify`, `mkdocs build --strict`, `check_site.py`, `check_changelog.py` exit 0) |

Task 107 (keyed audit chain) was dropped on 2026-10-06 under D2 and its task
file deleted. Tamper evidence rests on the unkeyed chain plus external
checkpoints (task 97) under separate custody.

**0.4.0 release candidate is ready** (task 129 merged 2026-10-06; nothing is pushed, tagged or published). Next is the owner-gated checklist below. 127 was the last feature task. 106 merged 2026-10-06; 105 merged earlier the same day (`mvn clean verify` of the merged head exited 0). Tasks 104, 123 and
126 merged on 2026-10-06; `mvn clean verify` of the merged head exited 0 only
after 126.
Tasks 102 and 103 carried notes that a retention below six months refuses
startup unless `dataprism.audit.retention-override` is set.

~~0.4.0 release note (94) for `CHANGELOG.md`~~ — closed 2026-10-06: task 129 put it in `CHANGELOG.md [0.4.0]` with the other behaviour changes.

Follow-ups from tasks 104 and 123, not yet tasks:

1. Deprecate `ToolAdmission.none()` and the old `DataPrismMcpServer`
   stdio/streamableHttp overloads, and move the integration-tests
   `ExampleApplication` (stdio) to the admission overload. Owned by
   data-prism-mcp and data-prism-security.
2. Consider upgrading `ToolAdmission` and `OversightPolicy` to
   PRIVACY_CRITICAL with a COMPETING_BEAN_REFUSAL guard: an application
   `@Primary` `ToolAdmission` could silently disable admission.
   `dataPrismHttpTransport` and `dataPrismAuthorizationService` are also
   REPLACEABLE.
3. Delete the now-unused `ClusterScopeBudgetConfiguration`.
4. The cluster budget reaches `PrivacyCluster` through an `ObjectProvider`, so
   the destroy-order dependency is not registered. Harmless today; register it
   explicitly.
5. Resolved by task 128: the duplicated refusal-code regex now lives in one
   shared `RefusalCodes` helper.
6. Amend task 112's acceptance wording: bare refusal codes and plain `DENY`
   appear only in pre-0.4.0 files.
7. Resolved by task 128: codes from application scrubbers, validators and
   resolvers are validated before client text.
8. Process: when two parallel tasks change the same record format or
   vocabulary, test the merged result before recording. 104 and 123 each passed
   against base 2c32884 and failed together.

Follow-ups from task 106, for 0.4.x, not yet tasks:

9. `wt-new.sh` and `wt-merge.sh` default to `main`; they need a base-branch
   argument. Task 106 had to be merged by hand into the planning branch.
10. `docs/reidentification.md` (around lines 152-153) says Docker Compose
    publishing of the operator port is "not yet done". That is true until the
    known 0.4.x Docker item lands; update the sentence then.

Follow-ups from task 127, for 0.4.x, not yet tasks:

11. Emit a dedicated failure metric in the `CachingSyntheticValueSource` catch
    block (data-prism-hazelcast) and wire `failures()` into health. Today a
    reverse-index write failure shows only as `IDENTITY_CACHE_MISS` plus a WARN.
12. The forward identity cache is not wired for embedded topology when
    re-identification is disabled. Performance only.
13. The `CachingSyntheticValueSource` wrapper is built over a cluster with no
    size cap, so memory grows with scopes x subjects x namespaces. Pre-existing;
    document it or cap it.

Accepted current behaviour: an approval-required call still consumes a
rate-limit token. This is documented.

#### Release 0.4.1 — real, safe, honestly documented clustering (tasks 131-136)

Planned 2026-10-06 on `claude/release-0.4.1`. These facts were found against
v0.4.0:
- `topology=embedded` starts a bare Hazelcast 5.7.0 `Config`, with cluster name
  `dev`, auto-detection on, no join list and no TLS. Separate instances
  therefore never cluster.
- On Kubernetes, auto-detection may join an unrelated `dev` cluster without
  authentication.
- `tls-*-reference` is read by nothing.
- The docs claim cluster-wide sharing.

Each effect fails closed: budget, pause, approvals and rate limits are per
instance. The danger is exposure and an overstated guarantee.

| Wave | Task | What | Depends on |
|---|---|---|---|
| 1 | 131 | **Done.** `PrivacyCluster` membership explicit; auto-detection, multicast and phone-home off; refuse `dev`, TLS config and unsafe `using()` | none |
| 1 | 133 | **Done.** Multi-instance Compose and Kubernetes examples; `EXPOSE 5701`; `server.json` cluster and operator variables | none |
| 2 | 132 | **Done.** `dataprism.hazelcast.cluster-name`, `join.*`, `member.*`; startup refusals; TLS references refuse | 131 |
| 3 | 134 | **Done.** Multi-member server test: pause, approvals, budget, rate limit, re-identification, member loss, refusals | 132 |
| 3 | 135 | **Done.** Correct configuration, eu-ai-act, architecture and reidentification docs; new `multiple-instances.md` | 132, 133 |
| 3 | 137 | **Done.** Pin Hazelcast 5.7.0 over the Spring Boot BOM (0.4.0 shipped 5.5.0) | none |
| 3 | 138 | **Done.** `join.mode: none` refuses incoming joins (PR #113 P2 finding); merged 2026-10-07 | 132 |
| 4 | 136 | **Done.** Cut 0.4.1 (task 129 pattern); merged 2026-10-07 | 131-135, 137 |

All waves are merged (136 on 2026-10-07). **0.4.1 is release-candidate ready** on `claude/release-0.4.1`; publication waits on the owner (see "0.4.1 release checklist" below). Notes from 131-133, which 135 has now documented:
- 135 must document that without `member.interface` a tcp-ip or kubernetes member binds every interface.
- 135 must document the two cross-mode refusals 132 added: `members` with a non-tcp-ip mode, and `kubernetes.*` with a non-kubernetes mode.
- 135 should warn against `docker run -P` with `EXPOSE 5701`, which publishes the cluster port.
- The live Compose 2-member run was done in 136: the cluster formed, isolation did not hold (D-0.4.1-E).

0.4.x follow-ups found in 134, 135 and 137, not yet tasks:
- The packaged server with `topology=embedded` and no source adapter refuses with a misleading `MISSING_SHARED_BUDGET` before cluster validation runs. It fails closed, but the message points at the wrong fix.
- Add `k8s-rbac` snippet markers in `docker/multi-instance/kubernetes.yaml`, so the docs can embed the RBAC block instead of describing it in prose.
- The read budget is hard-coded at `RequestLimits.DEFAULT` (100). Consider making it configurable.
- The `hazelcast-spring` artifact is not pinned. Harmless while nothing uses it.

Sequencing with 0.5.0, recorded in the task files with Owns unchanged:
- 113 now depends on 132 and 135, because they touch the same files
  (`DataPrismProperties`, `DataPrismAutoConfiguration` and `configuration.md`).
- 116 depends on 135 (`mkdocs.yml`).
- 130 depends on 136 (poms and `docker/distribution/Dockerfile`).

Planner's choice: the 0.4.x item "expose the operator port in Docker Compose,
`server.json` and the image" is folded into 133 for `server.json` and the image
only. Operator use end to end in the Compose quickstart needs the issuer to
mint operator-audience tokens, so it stays a follow-up.

Owner decisions, open:

- **D-0.4.1-A — member security.** Verified against the jar: OSS 5.7.0 has no
  member TLS engine (`BasicSSLContextFactory` is absent) and no member
  authentication (`SecurityConfig` is Enterprise). Kubernetes discovery is OSS
  core. The options:
  - **(A1)** Hazelcast Enterprise for TLS and member authentication. This
    costs a commercial licence, a non-OSS dependency, licence-key handling and
    a dual build or classpath. It contradicts "Hazelcast stays OSS".
  - **(A2)** A home-grown encryption layer, such as a custom
    `SSLEngineFactory` or socket interceptor. The interceptor is Enterprise,
    and a custom engine is unsupported, security-critical code to own.
    Rejected.
  - **(B)** Keep the `tls-*-reference` property names, but make setting either
    one refuse startup with `HAZELCAST_TLS_UNSUPPORTED`. Do not delete them,
    because Boot would then ignore them silently. Document network isolation
    (a private network, `NetworkPolicy`, or an mTLS mesh) as the deployer's
    responsibility, and never claim encryption.

  **Recommended: B.** Tasks 132 and 135 are written for B.
- **D-0.4.1-B — is the new refusal a breaking change for a patch?** `embedded`
  without `cluster-name` and `join.mode` now refuses startup. The options:
  - **(a)** Ship it in 0.4.1 as a Breaking changelog entry, with a one-line
    migration (`join.mode: none` is an explicit single member bound to
    loopback). 0.4.0 `embedded` never clustered, so no working multi-instance
    deployment breaks; only a config edit is forced. Fail closed, as the repo
    rule requires.
  - **(b)** Make it 0.5.0. That renumbers the queued 0.5.0 work and leaves
    0.4.0 users on an auto-detecting `dev` member for longer.
  - **(c)** Ship in 0.4.1 but default to `none` with a WARN when `join` is
    unset. This keeps 0.4.0 configs starting. However, a deployer who runs N
    replicas expecting a shared budget is silently N times over budget, which
    breaks the fail-closed rule.

  **Recommended: (a).** 0.x semver permits it, the old behaviour was a
  defect, and if 0.4.0 has not been published yet the cost is near zero.
  Tasks 132 and 136 are written for (a). Under (c), 132 drops
  `MISSING_CLUSTER_JOIN` in favour of a WARN, and 136 moves the entry from
  Breaking to Changed.
- **D-0.4.1-C — application `PrivacyCluster` beans.** Keep them allowed, but
  validate them: `using()` refuses auto-detection or multicast
  (`UNSAFE_HAZELCAST_DISCOVERY`) and a `dev` name, and `embedded(Config)`
  forces both off. Setting the properties alongside such a bean refuses with
  `CLUSTER_SETTINGS_IGNORED`. **Recommended: as described.** The alternative,
  forbidding custom beans, breaks hosts that manage their own instance.
- **D-0.4.1-D — Kubernetes join mode.** Support both DNS mode (`service-dns`,
  a headless service, no RBAC) and API mode (`service-name`, which needs get
  and list on endpoints and pods). **Recommended: both, with DNS shown first in
  the examples**, because it grants the pod no Kubernetes API rights.

Known residual risk, documented by 135 and pinned by 134: the maps have backup
count 1, so losing an entry's owner and its backup together loses it. For a
pause flag, that reopens a paused path.

Owner decisions, closed 2026-10-07:

- **D-0.4.1-E (2026-10-07):** the Compose isolation claim is weakened, because
  Compose networks are not a security boundary. The live run on OrbStack formed
  a 2-member cluster, but a container on the default network only reached the
  cluster-network addresses by routing across bridges. The docs now say the
  members bind and advertise only their cluster-network address, that OrbStack
  was observed to route across networks, and that isolating 5701 is the
  deployer's job (firewall, NetworkPolicy or private network).
- **D-0.4.1-F (2026-10-07):** publication goes to Central (library modules;
  `data-prism-server` stays off Central), GHCR and the MCP Registry, as 0.4.0 did.

#### Deferred option, no tasks (2026-10-07): secure Hazelcast clustering and cross-DC

The owner dropped this for now. It is recorded so it is not re-researched. The architect found:

- OSS Hazelcast cannot authenticate members, and has no member TLS engine.
  Anyone who can reach the member port can read and write cluster state.
- Recommended if revived: application-layer sealing, meaning AES-GCM on values,
  HMAC'd keys, signed approvals, and a fail-closed signed pause document with a
  lease. Optionally add an Enterprise mutual-TLS profile.
- Budget and rate-limit deletion can only be bounded, not prevented.
- Cross-DC needs `CUSTOM` or `ZONE_AWARE` partition groups, sync versus async
  backups chosen per map, custom merge policies (pause wins; consumed approvals
  stay consumed), and a supported RTT ceiling.
- Open questions: whether to require Enterprise, the cutover from unsealed
  data, split-brain handling, and a Java serialization filter.
- The current 0.4.x write risks are documented as known limitations in 0.4.1.

Follow-ups from 136:

- The lychee link check was not run locally; CI is the first run. Check its result before tagging.
- 130 is now unblocked (it waited on 136). It has since landed.

### 0.4.1 release checklist (owner go-ahead required)

Per D-0.4.1-F: publish to Maven Central (library modules; `data-prism-server`
stays off Central), GHCR and the MCP Registry. Each step is an outward action
and needs the owner's go-ahead. Nothing has been pushed.

1. Push `claude/release-0.4.1` and open a PR to `main`.
2. Wait for CI green (including the lychee link check, not run locally), then merge.
3. Done: Dependabot superseded the old PRs with grouped PRs. Dependabot PRs are not closed by hand.
4. Push an annotated tag `v0.4.1` on the merge commit, matching `v0.4.0`. It is not signed.
5. Watch the `release.yml` run for `v0.4.1`; create the GitHub Release if the workflow has not.
6. Dispatch `publish-central`, then verify the library modules at 0.4.1 on Central (`data-prism-server` must not appear).
7. Dispatch `publish-image` with `-f version=0.4.1`, then verify the GHCR manifests for `data-prism-server` and the four `data-prism-quickstart-*` images.
8. Dispatch `publish-mcp`, then verify the registry lists 0.4.1 as latest.
9. Re-verify the `docs/extending.md` consumer snippet against Central 0.4.1 (throwaway project, no local repository), and drop any remaining "(recorded against ...)" markers if it passes. Still open.

**0.4.1 release complete (2026-10-07).** Steps 1 to 8 are done and verified:

- The GitHub Release exists.
- Maven Central has the 13 library modules at 0.4.1. `data-prism-server` is absent, as intended, and the parent pom pins Hazelcast 5.7.0.
- GHCR has 5 images at 0.4.1, each for amd64 and arm64.
- The MCP Registry lists 0.4.1 as latest.

Only step 9 remains.

#### Release 0.5.0 — correlation ids and log-stack output (tasks 108-116, 130, 141-147)

Scope per the owner, 2026-10-07: 108-116, 130 and 141-147 (139 and 140 split into them).

Lets an organisation's own correlation id flow from its MCP client through the
audit record and on to its REST sources, and writes audit events as JSON that
Elastic-style log stacks can ingest. It depends on 0.4.0's audit segments
(102), hash encoding (117) and configuration wiring (103, 104).

| Wave | Task | What | Depends on |
|---|---|---|---|
| Done | 130 | **Done** (2026-10-07). Cleared the dependency backlog; see HISTORY | 136 |
| Done | 108 | **Done** (2026-10-07). Add a validated external correlation id and carry it on `DataRequest` | none |
| Done | 141 | **Done** (2026-10-07). Migrate the reactor to Spring Boot 4.1.1 on a single Jackson 2 classpath | none (130 is done) |
| Done | 143 | **Done** (2026-10-07). Build and run every Docker image on Java 25 LTS, with a container smoke test | none |
| Done | 145 | **Done** (2026-10-07). Make Dependabot allow Java 25 images and really block 26 and later | none |
| Done | 109 | **Done** (2026-10-07, attempt 5). Record the external correlation id in audit record version 3 | 102, 108, 117, 123 |
| Done | 142 | **Done** (2026-10-07). Pin what Spring Boot 4 moved: Jackson 2 converters, actuator JSON, the operator error path | 141 |
| Done | 146 | **Done** (2026-10-07). Make the test suite clean and proven on JDK 25, including on Linux | 141 |
| Done | 110 | **Done** (2026-10-07, attempt 2). MCP tools and orchestrator carry the external correlation id to audit and sources | 101, 108, 109, 118, 123, 128 |
| Done | 111 | **Done** (2026-10-07, attempt 2). Send the external correlation id to configured REST sources through an interceptor | 108, 141, 146 |
| Done | 112 | **Done** (2026-10-07). Add a structured JSON audit projection with ECS field mapping and routing hints | 109, 118 |
| Done | 144 | **Done** (2026-10-07, attempt 2). Build on a JDK 21 and 25 matrix, release on 25, and gate released jars on class version 65 | 143, 146 |
| Done | 149 | **Done** (2026-10-07, attempt 2). Remove the port races behind the known test flakes, without weakening any test (test sources only) | none |
| Done | 113 | **Done** (2026-10-07). Wire inbound correlation headers and audit JSON output into configuration | 103, 104, 110, 112, 127, 141 |
| Done | 114 | **Done** (2026-10-07). Extend the PII scans to the correlation id and the JSON projection | 110, 112 |
| Done | 147 | **Done** (2026-10-07). Document the 0.5.0 platform: Spring Boot 4.1, Java 25 images, Java 21+ for consumers | 108, 111, 141, 143, 144 |
| Done | 148 | **Done** (2026-10-07). Put the validated external correlation id into the SLF4J MDC under an operator-configured key | 110, 111, 113, 114 |
| Done | 115 | **Done** (2026-10-07, attempt 2). Ship client correlation-header snippets and log-shipping recipes as examples | 113 |
| Done | 116 | **Done** (2026-10-07, attempt 2). Document audit record v3, the JSON projection and log shipping | 106, 113, 114, 115, 135, 148 |
| Done | 150 | **Done** (2026-10-07, attempt 2, D-150-A decided). Audit `entityType` verbatim only when registered or upper-case-identifier-shaped, otherwise `<unregistered>` | 148 |

**Every planned 0.5.0 task is done (waves 1 to 6), and 0.5.0 is released in git (v0.5.0, 2026-10-08; publication steps remain, see its checklist).** Historical note from before the cut: the next step was the release cut, which needed the owner's go-ahead. The pause stays in force: do not push, tag, publish or open a PR on the strength of this file alone. The consolidated checklist is below, under "0.5.0 release-cut checklist".

**Baseline after waves 1 to 3** (merged head a2c6bdf7, 2026-10-07). `mvn -Prelease -Dgpg.skip=true clean verify` on the full reactor: BUILD SUCCESS, 20 of 20 modules, about 1,220 tests, 0 failures, no flakes on this run. `check-class-version.sh 65` over the 19 built jars: exit 0. actionlint: clean. The reactor version is still 0.4.1; the bump to 0.5.0 is a release-cut item. Every task worktree is removed and every task branch deleted; only the main checkout and the session worktree remain.

#### Open owner questions

- 1.0 roadmap: draft ideas in docs/plan/roadmap-ideas.md (not agreed).
- Delete the local branches already merged into main: done. The owner deleted `docs/dedupe-claude-md-rules`, `record/19-20-close-wave` and `simplify/waves-1-2` locally on 2026-10-07; the remote branches are untouched.

Release-cut items are consolidated in "0.5.0 release-cut checklist" below.

Follow-ups from wave 1, not yet tasks:

- Remove the unused `ServerProperties` import in `OperatorErrorController`.
- `ServerStartupTest` should assert 401/404 rather than "not 200".
- The smoke teardown should remove images explicitly rather than rely on `--rmi local` semantics.
- Hazelcast fixed-port tests flake when worktrees build in parallel.

139 was an umbrella and is split into 141, 142 and 147 (all three done). Its file was retired to `docs/plan/tasks/retired/` on 2026-10-07 with the owner's approval. 140 is split into 143,
144, 145 and 146 and has no file of its own.

108 has no dependency and could start at any time, but nothing in 0.5.0 ships
before 0.4.0. Task 130 owned no files shared with tasks 108-116.

**Decisions from task 130 (2026-10-07):**

- **D-130-A:** Spring Boot 4 is not part of 130. It is its own migration, task 139.
- **D-130-B (historical, superseded 2026-10-07 by the Java 25 decision below):** the Docker images stay on Java 21.
- **Java 25 (2026-10-07):** the owner approved runtime and build images on Java 25 LTS, not 26, with a CI matrix on JDK 21 and 25. Bytecode stays `--release 21`, the README states the Java 21 consumer minimum, and Dependabot allows 25 but not 26. Language level 25 is not adopted. Risks: Hazelcast's Unsafe warnings, Mockito agent warnings, a JDK TLS message-text test, and Maven image tags. Work is task 140.

Follow-ups from 130, not yet tasks:

- (a) Closed by D-139-C, in task 141. (`data-prism-server` bundled nimbus-jose-jwt 9.37.4 through the Spring Boot BOM while the quickstart issuer pinned 10.10.)
- (b) Answered. The task 130 docker `maven` semver-major ignore does not block `-temurin-26`, because Dependabot reads `3.9-eclipse-temurin-21` as 3.9.21, so `-26` is a patch update. Task 145 fixes it.

**Owner decisions on the platform, 2026-10-07** (all decided):

- **D-139-A:** Stay on Jackson 2 for 0.5.0 and keep the Jackson 3 enforcer ban.
- **D-139-B:** 0.5.0's starter and autoconfigure require Spring Boot 4.1. Boot 3 users stay on 0.4.x. CHANGELOG Breaking.
- **D-139-C:** Manage nimbus-jose-jwt once in the root pom at 10.10, and remove the quickstart-issuer pin and its stale comment.
- **D-139-D:** Target Spring Boot 4.1.1, not 4.2 milestones. 4.1 OSS support runs to 2027-07-31.
- **D-140-A:** Floating `eclipse-temurin:25-jre` and `maven:3.9-eclipse-temurin-25` tags.
- **D-140-B:** Dependabot ignores eclipse-temurin 26 and later and maven 3.9.26 and later, plus Maven 4 (semver-major). Moving to the next LTS, 29, is an owner decision.
- **D-140-C:** Record the Hazelcast `sun.misc.Unsafe` warning on JDK 25; never silence it with `--sun-misc-unsafe-memory-access=allow`.
- **D-140-D:** Load Mockito as a `-javaagent` in data-prism-mcp only.

**Decisions on task 149, test-suite stability (2026-10-07):**

- **D-149-A:** If the only clean fix is in production code (`src/main`, POMs), the implementer stops and files a follow-up task. Owns is not widened.
- **D-149-B:** Proof on macOS alone is enough for acceptance. CI's Linux matrix runs on the next push give the Linux evidence, tracked as a release-cut item.
- **D-149-C:** Bounded port re-allocation is allowed: at most 3 attempts, only on a bind failure of the chosen port, never wrapped around an assertion. It is a port-selection retry, not a test rerun.

**Decision D-148-A (2026-10-07):** correlated logging through the SLF4J MDC.

- Data Prism supports MDC-based correlated logging. `dataprism.correlation.mdc-key` is unset by default, which means off.
- It puts only the validated `ExternalCorrelationId.value()` into the MDC.
- It is opened at the MCP tool handler, because the SDK runs sync tools off the servlet thread, and inside each `SourceFanOut` task from that task's own `DataRequest`. It never relies on ThreadLocal inheritance.
- It is always cleared in `finally`, restoring any previous value. A rejected or absent id sets nothing.
- Codes: `INVALID_CORRELATION_MDC_KEY`, `CORRELATION_MDC_KEY_RESERVED`, `CORRELATION_MDC_KEY_WITHOUT_HEADER`.
- Whatever the inbound pattern admits appears in every log line on those threads, so the pattern should admit generated ids only.
- No Micrometer or OTel propagation (C6 stays deferred), and no MDC on Hazelcast or other background threads.
- The optional global `dataprism.correlation.outbound.header` is an amendment to 111 (consumption) and 113 (binding, validation, docs). A per-source correlation-header overrides it.
- 115's Owns excludes `examples/log-shipping/mdc/**`, which is task 148's. 116 now depends on 148.

Follow-ups from wave 2, not yet tasks:

- (a) Audit verifier wording. An unterminated last line ("POSSIBLY IN FLIGHT, not a break", exit 3), and the truncation-equivalent residuals (21-23 fields with field 20 = "2"/"3", and more than 25 fields with an unparseable field 20, both reported as "INTERRUPTED WRITE, not tampering"), should read "unverified, tampering not ruled out". Consider covering a writer's tail with `--checkpoints`.
- (b) `AuditRecordFormat:113` relies on an NPE for a null field-20 token. It fails closed, but make it a deliberate check.
- (c) The CLI help and runbook should say that an interrupted write joined to a restarted writer can surface as exit 2, which is a safe false positive.
- (d) **Done 2026-10-08 by task 155 (with PR #118 folded in; plugin now 3.11.0 in root `pluginManagement`).** Move the maven-dependency-plugin version (3.8.1, pinned in data-prism-mcp's pom) into root `pluginManagement`, and check for a newer release.
- (e) Record surefire and failsafe counts separately in future JDK runs.
- (f) Task 149 merged: the port races are addressed (ports are now claimed with an OS file lock, probes run one after the other). The three end-to-end symptoms below are **cause unproven; mechanisms reproduced**, not fixed, so watch CI and the next full runs and treat a recurrence as new information. Original observations: `ReidentificationEndToEndTest` failed once with a `ConnectException` in a full `mvn clean verify` on the merged head and passed on an unchanged rerun. Watch for a recurrence; do not treat it as a regression without one. A second flake appeared in wave 3: `ConfiguredJsonReidentificationEndToEndTest` failed with "Runtime Client failed to initialize" (31s timeout) and passed on rerun. A third, in task 144's run: `ReidentificationConfigurationTest.the_service_exists_when_enabled_and_the_tool_list_is_unchanged` failed once in the full reactor, then passed on a module rerun and on a full rerun.

Follow-ups from wave 3 (tasks 110, 111, 112), not yet tasks:

- (g) **Done 2026-10-08 by task 160.** `TeeAuditSink` catches only `RuntimeException`. An `Error` from the projection after the primary write leaves the tee unpoisoned, so a sequence number could be reused. Catch `Throwable`, poison, and rethrow.
- (h) For task 116: `fieldDispositions` keys can contain dots, which Elasticsearch expands into nested objects. Document this and recommend a flattened or disabled mapping for `dataprism.field_dispositions`. Also check how Boot's ECS formatter renders dotted key-value names.
- (i) `PiiLogScanTest`'s full-run projection scan covers only the assembly recorder. The tool-level `toolAudit` recorder's events are not scanned as JSON or key-value pairs.
- (j) `OutboundCorrelationHeader` should also forbid hop-by-hop and framing names: Connection, Upgrade, TE, Keep-Alive, Content-Type, Expect.
- (k) In `ExternalCorrelationToolTest`, `nullFingerprinterWithAdmissionIsRefused` should also cover `CompareEntitySourcesTool`'s 10-arg constructor and the `DataPrismMcpServer` factories, and assert the "fingerprinter" message. `toolsListUnchanged` should add a streamableHttp case.
- (l) Task 110 defines `DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY = "externalCorrelation"`. Task 113 needs it.
- (m) `.github/scripts/check-class-version.sh`: the exit status of the `find` in `< <(...)` is not propagated. A class file of 4 to 6 bytes aborts the run under `set -e` without naming the jar. The nomagic case also prints a cosmetic "no classes to check" after its error. All of these still fail closed.
- (n) `publish-central.yml`: the `publish` job deploys a fresh `clean deploy` build that is not itself gated on class version. It is covered only through `needs: stage`.
Follow-ups from task 149, not yet tasks:

- (o) The lock files in `java.io.tmpdir/dataprism-test-ports` are never deleted. They are zero-byte and harmless, and the lock is the claim, not the file.
- (p) `QuickstartSmokeIT.freePort()` still uses `ServerSocket(0)`.
- (q) In `OperatorHarness`, a re-pick of a claimed port uses up one of the 3 attempts allowed by D-149-C.
- (r) The dfc9160c commit body cites stale line numbers for the `ServerSocket` hits. The current ones are `FreePorts` 53/56, `ClusterConfigurationTest` 76/79 and `OperatorHarness` 458/463. Recorded in HISTORY; the commits are not rewritten.

Follow-ups from wave 4 (tasks 113, 114, 147), not yet tasks:

- (s) The JSON projection purge failure only logs. It should record a failure code surfaced by `auditIntegrity` health, as the native purge does. It should not fail closed, since the projection is not authoritative.
- (t) Move the projection into its own classified bean, with `TeeAuditSink` made `Closeable` in core, instead of `registerDisposableBean` inside `dataPrismHashChainedAuditSink`. This needs `PrivacyExtensionPoints` and core in Owns.
- (u) Add an integration-tests case: a Spring context with the real `DefaultContextOrchestrator`, carrying the correlation id onto the ALLOW event. Task 113's test uses a stub orchestrator.
- (v) In `CorrelationConfigurationTest.a_rejected_value_is_logged_as_a_code_and_the_text_never_appears`, split the chained `noneSatisfy` into two separate `noneMatch` checks (message and args).
- (w) In `AuditOutputConfigurationTest`, add nested json-directory cases (under and over `directory`) for `AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT`.
- (x) Task 114's commit body records no mutation for `PiiLogScanTest`'s key-value scan. The `AuditFilePiiScanTest` javadoc for `extraArguments` is inaccurate, and there is a stray `{ }` block at about line 673.
- (y) For task 148 (merged; 148 captured pairs from a real sink): the log-line parser's `\b` key markers misparse when slf4j-simple prints key-value pairs before the message (a false hit from a UUID). Capture pairs from a real mapped sink rather than parsing lines.
- Task 113 added three refusal codes beyond its contract: `INVALID_CORRELATION_FORMAT`, `INVALID_AUDIT_FIELD_PRESET` and `AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT` (which also covers a json directory inside or containing `directory`). Task 116 should document them.

- Owner-visible point for 116: the `Slf4jAuditSink` mapped constructor attaches the routing constants as key-value pairs. That is the implementer's reading of the spec.

Follow-ups from wave 5 (tasks 115, 148), not yet tasks:

- (z) `CorrelationMdcToolTest.rejectedPutsNothing` asserts on text that can never be present. Feed a real rejected header through `InboundCorrelation.resolve(...)`.
- (aa) `concurrentCallsDoNotCross` checks only the marker events. Also check the audit-event lines.
- (ab) The 5f5f5df7 commit body calls a positive control "the mutation". Wording only; recorded in HISTORY, the commit is not rewritten.
- (ac) There is no stdio MDC overload, so library users of stdio always run with MDC off. Documented in `docs/log-shipping.md` by task 116 (done).
- (ad) The Python client snippets need Python 3.10 or later. Said in `docs/log-shipping.md` by task 116 (done).

Follow-ups from wave 6 (tasks 116, 150), not yet tasks:

- (ae) `docs/audit.md:87` now holds two sentences where task 150's Owns said one. Rewrap the 150 doc lines and the 116 fix lines, and remove the double blank line in `audit.md` around :506.
- (af) `docs/audit.md` field-count wording: a line of more than 25 fields whose field 20 is non-numeric is an interrupted write, not `FIELD_COUNT_MISMATCH`. A rare edge.
- (ag) The mutation proof for the stdio drop in 150 did not record the name of the failing test.
- (ah) The `OperatorAudit`, `OversightOperatorController` and `ReidentificationService` `entityType` fields were out of scope for 150. Raise a recon if they are a concern.

Follow-ups from the 0.5.0 cut (task 151), not yet tasks:

- (ai) `docs/audit.md:77` (81 characters, old text) and the short orphan line at :584 are cosmetic.
- (aj) Process: the AI tooling's auto-mode classifier denied the `-Prelease` build, mkdocs and a `bash -c` script locally during the cut. Release checks now rely on CI.

External review of PR #117 (2026-10-08) found two P2 defects. Both are fixed and merged on the PR branch (tasks 152 and 153). The container-smoke SIGPIPE fix (e23de419, merged as fix/smoke-sigpipe) also landed on PR #117 after CI caught exit 141.

Decisions recorded for task 153 (2026-10-08):

- D-153-A: A2. A resumed writer terminates a torn tail with `"\r\n"` and fsyncs before appending. The verifier reports any line ending in a raw `\r` as INTERRUPTED_WRITE_FRAGMENT without parsing it. Directory mode uses the same terminator.
- D-153-B: B1. A fused line already present in a pre-0.5.0 log stays a break (exit 2), and the FIELD_COUNT_MISMATCH text, `docs/audit.md` and the CHANGELOG name the legacy cause.

Follow-ups from tasks 152 and 153, not yet tasks:

- (ak) 153: assert "carriage return" in the anomaly message, so that mutation 2 kills all five tear points.
- (al) 153, post-0.5.0: when a CR-ended line hash-verifies as a complete record with the CR stripped, word it as "a complete record hidden behind the restart marker".
- (am) **Done 2026-10-08 by task 160.** `FileAuditCheckpointSink` has the same append-after-torn-tail hazard as the audit sink had.
- (an) 152: the rejected-id test should pass the `validate()` result through `SourceCallContext`.
- (ao) 152, known limit to document: the interceptor is installed only when a correlation header is configured, so a client default header on a source with no correlation header is untouched.

- (ap) **Closed 2026-10-08 as "Javadoc gated in `build.yml` on every PR" (task 171).** Finding: `release.yml` runs plain `mvn verify`, and the release profile runs only in `publish-central.yml`; `release.yml` itself is unchanged. Original note: `release.yml` runs plain `mvn verify`, not the release profile, so it never builds Javadoc. A Javadoc error was caught only by publish-central's stage after `release.yml` had already created a GitHub Release (v0.5.0, first tag). Make `release.yml` build with the same profile, or make it depend on the stage.
- (aq) Branch protection on `main` required a check named `build`, which `pages.yml` also produced. After task 144's matrix it silently gated on the docs build. The owner switched it on 2026-10-08 to require `build (21)`, `build (25)` and `container-smoke`. Task 165 fixes this properly with a `ci-gate` job (D-165-A).

Follow-ups from task 156 (2026-10-08), not yet tasks:

- (ar) `docs-site/diagrams/README.md:275-276` still links the old `core/` file paths. Folded into 162.
- (as) DONE in task 157's review polish (2026-10-08): `RefusalPaths` now uses an FQCN `{@link}`; the Javadoc-only imports are gone.
- (au) From task 157: `FileAuditCheckpointSink` could move to `audit.sink` so `terminateTornTail()` and `closeQuietly()` stay package-private. Considered and declined: `terminateTornTail` only appends CRLF, so the widening grants no new power. Not a task.
- (at) `coreRootPackageIsEmpty` (ArchitectureTest, about :418) carries a redundant `allowEmptyShould(true)`. Drop it; 167 already touches ArchitectureTest, so fold it in there.

### 0.5.0 release checklist (0.5.0 released 2026-10-08; owner steps remain)

Task 151 did the local cut on 2026-10-07 (merge 98b42144). Nothing has been pushed. Every outward action needs the owner's go-ahead.

Done by task 151:

- Version bump 0.4.1 to 0.5.0 and the CHANGELOG `[0.5.0] - 2026-10-07` section.
- `docs/extending.md` snippet and the "verified" paragraph. The post-publish rebuild of the extension pom is still open, below.
- `README.md:203`, the `docs/audit.md` stale record-version text and hashed-field list, and the (ae) rewraps.
- The 0.4.1 step 9 re-verify: the consumer snippet passed against Central on 2026-10-07.
- Class-version check: the owner ran `check-class-version.sh 65` over the 19 `data-prism-*-0.5.0.jar` files on 2026-10-08. All passed at major 65.

**0.5.0 is released in git.** PR #117 merged to `main` on 2026-10-08, the Javadoc fix merged through PR #119, and `v0.5.0` is tagged at c850c3e2. CI was green on JDK 21 and 25 plus `container-smoke`, the `-Prelease` build and class-version gate ran in `release.yml` and publish-central's stage, and Dependabot's docker run proposed no Java 26 tags (task 145's proof). Nothing is published yet.

Still open, owner-gated:

- Dispatch `publish-central`, then `publish-image` (`version=0.5.0`), then `publish-mcp`, following the 0.4.1 steps 1 to 8 pattern.
- Close Dependabot PRs #102 and #99 (Pillow, already at 12.3.0). #114, #115 and #116 were closed by Dependabot itself after the merge.
- **Done 2026-10-08:** rebuild of the `docs/extending.md` extension snippet against Central 0.5.0 with `mvn package` (D-151-A fallback). It resolved and built; the doc's "verified" paragraph records the result.
- PR #118 (maven-dependency-plugin 3.11.0) was folded into task 155 on 2026-10-08, which also did follow-up (d). Original note: follow-up (d) should move that plugin version into root `pluginManagement`; do it with or after merging #118.
- Follow-up (ap): `release.yml` should build with the release profile.

### 0.6.0 — package structure, API and style cleanup

Planned 2026-10-08 on `plan/0.6.0`, branched from `origin/main` at v0.5.0 (c850c3e2). Tasks 154 to 165, task files in `docs/plan/tasks/`. All tasks are done (see the table and notes below). The owner states there are no external users, so every change is a clean break with no deprecation cycle and no forwarding types, and the 0.6.0 CHANGELOG and migration page (162) say so. Every task branches from `origin/main` once the tasks in its Depends on line have merged.

| Wave | Task | What | Depends on |
|---|---|---|---|
| 1 | 154 | Bring `audit` and `oversight` under the core outer-layer ArchUnit rule | none |
| 1 | 155 | Replace MCP tool, server-factory and orchestration overloads with validated options records | none |
| 1 | 160 | Poison `TeeAuditSink` on any `Throwable`; terminate a torn checkpoint tail; `TORN_CHECKPOINT_LINE` | none |
| 1 | 165 | Single `ci-gate` summary check; rename pages.yml's `build` job | none |
| 2 | 156 | Split the `core` root package into `spi`, `model`, `engine`, `refusal`, `limits`, `metrics` (pure move) | 154, 155, 160. **Done 2026-10-08** |
| 3 | 157 | Split `audit` into contract, `format`, `sink`, `checkpoint`, `retention`, `verify`; move the verifier CLI | 156, 160. **Done 2026-10-08** |
| 4 | 166 | Pin Jackson 2 output and YAML behaviour with characterisation tests before the port | 157. **Done 2026-10-08** |
| 5 | 167 | Port the reactor to Jackson 3 in one step (code, mappers, MCP binding, Spring converters, enforcer) | 155, 156, 157, 166. **Done 2026-10-08** |
| 6 | 168 | Stop exposing data-prism's `ObjectMapper` in public API; ArchUnit guard; signature inventory | 167 (D-J3-1 decided). **Done 2026-10-08** |
| 6 | 169 | Record the Jackson 3 decision and the mapper invariant in architecture, conventions, README | 167. **Done 2026-10-08** |
| 6 | 170 | All five YAML readers (and `ConfiguredJsonSources`) refuse duplicate keys, unknown keys, trailing documents, unquoted non-string scalars in string fields, non-`true`/`false` booleans and leading-zero numbers at startup | 166, 167. **Done 2026-10-08** |
| 6 | 171 | Fix two unresolvable Javadoc links; gate Javadoc (release profile) in `build.yml` | unplanned, found in 168. **Done 2026-10-08** |
| 6 | 172 | Allow `StrictYaml` on the factory ArchUnit rule; narrow and extend the YAML read-only rule; prune four stale mapper-allowlist entries | unplanned, found testing merged wave 6. **Done 2026-10-08** |
| 6 | 173 | `SourceTree` Jackson 2 parity (enums by name, legacy dates, records by components) and record-only source models (`SOURCE_MODEL_NOT_A_RECORD`) | unplanned, external review P2 on 167. **Done 2026-10-08** |
| 6 | 174 | Record source models: every emitted property must come from a record component (closes interface-default `@JsonProperty` rename bypass) | unplanned, external review P2 on 173. **Done 2026-10-08** |
| 6 | 175 | Interface and abstract map key types (`CharSequence`, `Comparable`, `Serializable`) deferred to the per-key runtime check; `CheckedKey` forwards `resolve`/`createContextual`/`handledType` | unplanned, external review P2 on 173. **Done 2026-10-08** |
| 7 | 158 | Split `DataPrismProperties` by concern; validation into `spring.boot.validation` | 157, 167, 168, 169, 170. **Done 2026-10-08** |
| 8 | 159 | Split `DataPrismAutoConfiguration` by concern; JWT into `spring.boot.jwt`; drops `allowEmptyShould(true)` on 158's "validation must not depend on spring.boot.jwt" rule | 158. **Done 2026-10-08** |
| 9 | 161 | JSON audit projection as its own classified bean; `TeeAuditSink` `Closeable` | 159, 160. **Done 2026-10-08** |
| 9 | 164 | Generate Spring configuration metadata and check it against docs/configuration.md | 158, 159. **Done 2026-10-09** |
| 10 | 163 | Read-only `AuditEventListener` SPI called after the authoritative write | 157, 159, 161, 164. **Done 2026-10-09** |
| 11 | 162 | 0.6.0 CHANGELOG Breaking section, FQCN migration page, `docs-site/diagrams/README.md` path fix | 154-161, 163, 164, 165, 166-170. **Done 2026-10-09** |
| 11 | 177 | Bean inventory test uses a canonical annotation renderer | unplanned, PR #124 CI. **Done 2026-10-09** |
| 11 | 178 | Bounded `AuditEventListeners.close()`; json-directory via a Condition | unplanned, review findings. **Done 2026-10-09** |
| 12 | 180 | Drop dead FactoryBean loop in `AuditSinkSelection`; final drop line survives an interrupted `close()`; thread-count tests compare identities | unplanned, follow-ups from 161 and 163. **Done 2026-10-09** |
| 12 | 181 | Move 13 architecture fixtures into package-matching directories; sweep documented counts | follow-up from 154. **Done 2026-10-09** |
| 12 | 179 | Deterministic `sources` order in `get_entity_context`; `ToolAdmission` none | awaiting owner decisions D-179-1 and D-179-2 |

Re-sequenced 2026-10-08 when the Jackson 3 task files landed (branch `plan/jackson3`, merged onto `release/0.6.0-moves`). 156 was already running, so 166 runs after the moves rather than before them. Waves 6 (168, 169, 170) and 9 (161, 164) are parallel; 170 was added 2026-10-08 (D-166-1) and 158 waits for it because both edit `ConfiguredJsonSources`; 161 and 164 touch different files.

Follow-up from 166 (not yet a task): `get_entity_context`'s `sources` map iteration order varies between JVM runs. This is pre-existing nondeterminism, found while writing the golden for that tool; the golden normalises it. Candidate for a deterministic (for example sorted or insertion) order.

**Wave 4 done 2026-10-08:** 166 merged onto `release/0.6.0-jackson3` (branched from main c6b6b8bd).

**Wave 5 done 2026-10-08:** 167 merged onto `release/0.6.0-jackson3` (Jackson 3.1.5; 1455 tests, 0 failed on JDK 21; JDK 25 and container-smoke run in CI on the PR). Wave 6 is next: 168, 169 and 170 in parallel. Accepted or noted behaviour changes, signature changes and the pinned Jackson defaults are in the retired 167 task file's Outcome.

**Wave 6 done 2026-10-08:** 168, 169, 170 and the unplanned 171, 172, 173, 174 and 175 merged onto `release/0.6.0-jackson3` (tester PASS 1467 for 168; clean full reactor 1592 for 170). Wave 7 is next: 158. Details are in the retired task files' Outcomes. Notes carried forward:

- 158 now chains the cause on `INVALID_MODEL_DESCRIPTOR_FILE` so 170's inner codes reach the operator, and updates `docs/configuration.md`. 159 folds in `JwtDecoderSupport.parseDiscoveryMetadata`'s stale `throws IOException`. 162's acceptance now lists 170's nine codes, the YAML 1.2 changes, the credential-echo removals, 168's inventory and 171's CI step.
- 162 follow-up: a grep of `docs/` (outside `docs/plan`, `pack.md`, `design-review.md`) and `README.md` for "five YAML readers" and "seven designated classes" found no stale wording; `docs/architecture.md` was updated by 172. 162 should re-grep before release.
- Follow-ups from wave 6, not yet tasks: the `ToolResultCharacterisationTest` Javadoc says "whatever Jackson 2 does" and should be reworded now the port is done; the `serverUsesTheSharedMapper` comment should name the MCP SDK version whose private fields it reads; a Pillow bump (docs-site) is offered as a separate session.

**Wave 7 done 2026-10-08:** 158 merged onto `release/0.6.0-spring` (branched from main 387252a9). Wave 8 is next: 159. Details are in the retired 158 task file's Outcome. Notes carried forward:

- The 175 follow-up about `@JsonSerialize(keyUsing=...)` is RESOLVED. An architect review (2026-10-08) confirmed that map keys and dynamic property names are treated as undeclared data: the profile's `unclassified` setting governs, kept names become `<undeclared-N>`, refusals, audit and logs carry only `<undeclared>`, and only a Java-built `PASS_THROUGH_UNSAFE` profile emits keys verbatim. Documented at `docs/extending.md:391-416` and pinned by `UndeclaredPropertyNameTest`, `UndeclaredKeyRefusalTest`, `UndeclaredNameToolResultScanTest` and the audit and log PII scans.
- 162 now has an acceptance item to record that map-key decision in `docs/architecture.md` "Decisions worth knowing".
- Roadmap candidate, not 0.6.0: a key-shape scan in the leak validators under `PASS_THROUGH_UNSAFE` only.
- 159 must drop the `allowEmptyShould(true)` on 158's ArchUnit rule "validation must not depend on spring.boot.jwt" once the `spring.boot.jwt` package exists (already in its acceptance; confirmed).
- Correction to D-J3-2 below: the checkpoint writer on the allowlist is `audit.AuditCheckpoint`, not `FileAuditCheckpointSink`.

- **D-167-1: DECIDED 2026-10-08, option (b)** (170; owner had no opinion, chosen on correctness). 167 accepts YAML 1.2 parsing (`yes`/`no`/`on`/`off` are text; leading-zero numbers are decimal). 170 additionally refuses at startup any boolean-typed field value other than exactly `true`/`false`, and any numeric-typed field written with a leading zero (for example `010`), with stable codes. 170's file now carries the acceptance items and the list of characterisation tests that flip to refusal.
- Scope added to 170 (fail-closed, owner told and did not object): the readers also refuse multi-document files and trailing content with a stable code.
- Scope added to 168 (same basis): the ArchUnit mapper rule also catches obtained mappers (`JsonMapper.shared()` and similar static accessors, and data-prism classes using Spring Boot's auto-configured `JsonMapper`/`ObjectMapper` bean), with negative fixtures; the `SourceTree` Javadoc ("two names on the allowlist") is updated; `SourceTreeJacksonParityTest` asserts the specific Jackson empty-bean exception type.
- Scope added to 162: the migration page records the YAML 1.2 meaning changes now refused by 170, and 167's public signature changes.
**Wave 9, task 161 done 2026-10-08:** merged onto `release/0.6.0-spring`; 164 is still open. Details are in the retired 161 task file's Outcome. 162 must list the two new refusal codes (`AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK`, `AUDIT_JSON_PROJECTION_MISSING`) in the CHANGELOG/migration, and that an application `AuditSink` combined with `sink=hash-chained` and a json-directory now refuses to start.

Follow-ups from 161 (not yet tasks): (i) `AuditSinkSelection` FactoryBean loop (about lines 131-140) is effectively dead because `getType` returns the product type; remove it or comment it. (ii) `isBuiltInSink` relies on `AnnotatedBeanDefinition`, so hash-chained plus json-directory would be refused under Spring AOT/native images; AOT is unsupported today, note only.

**Wave 10 done 2026-10-09:** 163 merged onto `release/0.6.0-spring` (tester PASS at 8c5242b9). 162 is next and last before the release, which waits for the owner. Details are in the retired 163 task file's Outcome. 162 gained the CHANGELOG `Added` line for the listener SPI. Follow-ups (not tasks): a theoretical flake at `AuditEventListenerTest` about line 77; `close()` on an already-interrupted thread skips the final drop line.

**Wave 9 done 2026-10-09:** 164 merged onto `release/0.6.0-spring` (tester PASS 1670 at 383ba6ed). Wave 10 is next: 163, then 162. Details are in the retired 164 task file's Outcome. 162 gained the CHANGELOG `Added` line for the configuration metadata. 163 must satisfy `ConfigurationMetadataDocumentedTest` for any property it adds (document it in `docs/configuration.md` and keep the gaps file honest).

**0.6.0 tasks are all done (2026-10-09), including unplanned 177 and 178.** The release cut is next and waits on the owner's go (owner instruction 2026-10-09): version bump to 0.6.0, release PR, tag, publish to Central, GHCR and the MCP registry, then the after-action report. PR #124 (`release/0.6.0-spring`) carries waves 7 to 11. Consolidated 0.6.0 follow-ups, none yet tasks:
- DONE in 180: the dead `AuditSinkSelection` FactoryBean loop is gone (the AOT limitation of `isBuiltInSink` is now in its Javadoc, unsupported); the listener thread-count flake is fixed.
- OPEN (found in 180 review): `AuditEventListeners.close()` on an already-interrupted thread still writes the final drop line, but skips the drain because `thread.join(drainTimeout)` throws at once. Queued events are dropped and counted, and the reporter thread is not joined (`AuditEventListeners.java` about lines 232-266). Not fixed.
- `get_entity_context` `sources` map order varies between JVM runs (166).
- `ToolResultCharacterisationTest` Javadoc still says "whatever Jackson 2 does"; `serverUsesTheSharedMapper` comment should name the MCP SDK version; Pillow bump (docs-site) offered separately (wave 6).
- Roadmap candidate, not 0.6.0: key-shape scan in the leak validators under `PASS_THROUGH_UNSAFE` only.
- Optional `ToolAdmission.isNone()`/singleton in `security` (155). Fixture move DONE in 181.
- DONE in 181: every counted doc claim was checked against code and none needed changing.

**Owner instruction (2026-10-09): pause before any release step after 162.** No tag, release PR or publish without the owner's explicit go.

**Wave 8 done 2026-10-08:** 159 merged onto `release/0.6.0-spring`. Wave 9 is next: 161 and 164 in parallel. Details are in the retired 159 task file's Outcome. Notes carried forward: the audit wiring is now `AuditSinkSelection` (owns `dataPrismHashChainedAuditSink` and `JsonProjection`) and `AuditWiring` (owns `dataPrismAuditRecorder`); 161 and 163 Owns updated. 162 gained the logger-category rename, bean-name change and stale-reference fixes.

- Follow-up from 167 (not yet a task): `JwtDecoderSupport.parseDiscoveryMetadata` has a stale `throws IOException`. The file is not in 168's Owns, so fold it into 159 (which moves the file) or file a small task.

**Wave 1 done 2026-10-08** (tasks 154, 155, 160, 165, integrated on `release/0.6.0-wave1`; task files retired to `docs/plan/tasks/retired/`). Owner decisions and notes:

- 154: `audit` and `oversight` are under the core outer-layer ArchUnit rule with a baseline of 0 violations. Owner decided NOT to add a Jackson databind ban for `audit`; the existing output-shape tests cover it. Follow-up: move the fixtures into package-matching directories.
- 155: `ToolOptions` and `SourceFanOutOptions` replace the overloads, one entry point each. D-0.6-6 applied as `.noAdmission()` / `.admission(policy, fingerprinter)`. `ContextRequest.of`/`comparison` audit `<unregistered>`. Owner amendment: Dependabot PR #118 folded in (follow-up (d) done). Follow-up: an optional `ToolAdmission.isNone()`/singleton in `security`.
- 160: `TeeAuditSink` poisons on any `Throwable`; the checkpoint writer terminates a torn tail with `\r\n`; the verifier reports `TORN_CHECKPOINT_LINE`. Owner decisions: (a) the Owns list widened by one line in `AuditChainVerifierCli.header(AnomalyType)`; (b) the NARROW torn rule, where only `\r`-ended lines and a final unterminated unparseable chunk are torn and other garbage stays exit 1. This narrows D-0.6-7's "or otherwise unparseable" wording. Residual risk, to document: an attacker who can write the checkpoint file can label a deletion as "not tampering", which is still noisier than an outright delete; a CRLF-converted checkpoint file reports every line torn (exit 4).
- 165: `ci-gate` job in build.yml; pages.yml's job renamed `docs-site` (D-165-A). Owner post-merge step: once `ci-gate` has reported on main, switch main's required checks from `build (21)`, `build (25)` and `container-smoke` to `ci-gate` alone.

**Jackson 3 port is now IN 0.6.0.** Owner decisions J3-0 to J3-5, dated 2026-10-08. Task files are 166 to 169; the wave table above carries the order: package moves (156, 157), then Jackson 3 (166 to 169), then the Spring splits (158, 159).

- J3-0: Jackson 3 port is IN 0.6.0 (owner: "let that shape everything").
- J3-1: B, after the package moves (156, 157) and after 155; before the Spring splits (158, 159). Strictly separate tasks from the moves.
- J3-2: A. Data-prism keeps its own fixed, private Jackson 3 mappers: the single JSON writer (`DataPrismObjectMapper`), the `SourceTree` reader and the five YAML readers, built with Jackson 3 builders. They are not Spring beans and cannot be customised by application configuration. Owner's reason: adapter authors may bring their own `ObjectMapper` for their APIs, and must never be able to reconfigure data-prism's mapper for core behaviour. The ArchUnit "only `DataPrismObjectMapper` writes" rule must be rewritten for builders, with a negative test proving it still catches a violation.
- J3-3: C. Expose Jackson 3 tree types (`tools.jackson.databind.JsonNode`/`ObjectNode`) where they are the real data: `ScrubResult`, `SourceTree`, `Generalizer`, the validators, `ContextResponse` and `ComparisonResponse`. Narrow the accidental surface: `DataPrismObjectMapper.create()` and any public method that hands out or accepts data-prism's `ObjectMapper` stop being public, which reinforces J3-2. List every public signature change for the 162 migration page.
- J3-4: A. A small characterisation task runs first, on Jackson 2, before the port. Golden-byte tests for the audit JSON projection (non-ASCII, U+2028/2029, control characters, surrogate pairs, escape casing), checkpoint lines, and a full tool-result response. YAML characterisation tests for duplicate keys, YAML 1.1 booleans no/yes/on/off, unknown keys, enum case and whitespace, octal-looking scalars. The port must keep all of them green, or list each difference for owner acceptance.
- J3-5: A, a full flip. Enforcer: ban the Jackson 2 artifacts (`com.fasterxml.jackson.core:jackson-databind`, `jackson-core`, the `jackson-dataformat-*` and `jackson-datatype-*` artifacts), `io.modelcontextprotocol.sdk:mcp-json-jackson2` and `org.springframework.boot:spring-boot-jackson2`. CARVE-OUT: allow `com.fasterxml.jackson.core:jackson-annotations`, which Jackson 3 still uses and mcp-core needs. Lift the `tools.jackson`, mcp aggregate and `mcp-json-jackson3` bans. Spring: re-adopt `spring-boot-starter-jackson`, removing the exclusions and `spring-boot-jackson2` in the server, starter, quickstart-fixtures and quickstart-issuer poms. Task 142's `Boot4RegressionGuardsTest`: invert it to assert Jackson 3 converters and no Jackson 2. Docs: update the D-139-A decision in architecture.md (:284-290), and fix the stale "scrubbing engine is a Jackson module" wording at architecture.md:152 and conventions.md:36. The real invariant is "only `DataPrismObjectMapper` writes".

- **D-166-1: DECIDED 2026-10-08, option (a).** New 0.6.0 task 170, after 167: all five YAML readers (and `ConfiguredJsonSources`, which shares the `RestSources` mapper) refuse duplicate and unknown keys at startup (`DUPLICATE_CONFIG_KEY`, `UNKNOWN_CONFIG_KEY`). Background: the 166 characterisation tests showed all five readers keep the last of two duplicate keys and four ignore unknown keys.
- **D-170-1: DECIDED 2026-10-08, option (b)** (170). A string-typed field refuses any non-string scalar with `NON_STRING_CONFIG_SCALAR`, so values must be quoted. Rejected: (a) deferring it, (c) documenting only. 170's conditional acceptance items are now unconditional.
- **D-170-2: DECIDED 2026-10-08, option (a)** (170). Refusal messages name the offending key and its path, never the value, with the key truncated to 64 characters. 162's migration page and `[Unreleased]` Breaking entry record the three codes, that 0.5.x configs may now refuse to start, and the quoting rule.
- **D-173-1: DECIDED 2026-10-08, option (b)** (owner; 173). `SourceTree` serialises `java.time` types as ISO-8601 text and unwraps `Optional`, which Jackson 2 refused. The engine still classifies or refuses the result. Legacy `Date`, `Timestamp`, `sql.Date` and `Calendar` stay epoch milliseconds. 162 records this as a change.
- **D-173-2: DECIDED 2026-10-08, option (a)** (owner; 173). Source models must be records. Reproducing Jackson 2 bean introspection on Jackson 3 was open-ended, so the contract was narrowed: startup refuses a non-record model with `SOURCE_MODEL_NOT_A_RECORD` and `SourceTree` refuses beans at runtime. Allowed and documented: `@JsonAnyGetter`, `@JsonValue`, component or class-level `@JsonSerialize` on records. Refused: `@JsonProperty`/`@JsonGetter` on non-component record methods.
- Scope added to 162: the migration page and `[Unreleased]` Breaking entry cover D-173-2 (source models must be records, `SOURCE_MODEL_NOT_A_RECORD`, `@JsonProperty`/`@JsonGetter` on non-component record methods refused) and D-173-1 (`java.time` and `Optional` now accepted as ISO text; `Date` stays millis).
- Follow-ups from 173: (i) and (ii) DONE in 175. Original text: (i) startup should refuse a `Map` whose declared key type is an interface such as `Comparable`, `CharSequence` or `Serializable`; today it is refused on every request at runtime even with `String` keys, which fails closed but is wrong. (ii) `CheckedKey` does not forward `createContextual`/`resolve` to the wrapped key serializer; no observed effect.
- Follow-up from 175 (RESOLVED 2026-10-08, see wave 7 note): a component-level `@JsonSerialize(keyUsing=<user serializer>)` can write a user key by `toString()`; a probe showed a personal-data-looking string as a field name. D-173-2 allows author-chosen serializers, but field names are not classified like values. Consider refusing `keyUsing` on source records, or document that the engine must treat map keys of such components as data. Also for 162: a `Date` key declared as `Comparable` is written by `Date.toString()` (time-zone dependent), same as Jackson 2; JDK `StringBuilder`/`CharBuffer` keys are now refused.
- **D-J3-1: DECIDED 2026-10-08, option (a)** (168). The two tool constructors lose their `ObjectMapper` parameter; the tools take the mapper from package-private `DataPrismObjectMapper.create()`, and `DataPrismMcpServer` passes one shared instance through a package-private constructor so tools and transport still share it. Rejected: (b) package-private constructors, which rewrites 10 integration-test files for no extra protection; (c) an opaque data-prism-owned type, which adds a public type to carry one already hidden. 168 is no longer blocked.
- **D-J3-2: DECIDED 2026-10-08, option (b), in 0.6.0, folded into task 168** (owner delegated the call). 168 adds an ArchUnit allowlist rule for streaming JSON factory and generator construction (allowed: `DataPrismObjectMapper`, `AuditJsonRenderer`, the checkpoint writer `audit.AuditCheckpoint`, `JwtDecoderSupport`) with its own negative-test fixture. Not in 167, whose port stays behaviour-neutral.

Owner decisions, all decided 2026-10-08:

- **D-0.6-1:** `core` root package split with a clean break; no forwarding types at the old FQCNs (156).
- **D-0.6-2:** `audit` and `oversight` come under the `core` outer-layer ArchUnit rule (154).
- **D-0.6-3:** `DataPrismProperties` and `DataPrismAutoConfiguration` are split by concern. Property names and the `DataPrismAutoConfiguration` FQCN (it is named in `AutoConfiguration.imports`) are frozen (158, 159).
- **D-0.6-4:** the verifier CLI moves to `audit.verify` with no forwarding class at the old FQCN (157).
- **D-0.6-5:** an options record replaces the MCP overloads; the old constructors and factories are removed (155).
- **D-0.6-6:** `ToolAdmission.none()` is kept, but the options record has no default for admission, so every caller names `none()` or a real policy and approvals are never turned off silently (155).
- **D-0.6-7:** a resumed checkpoint sink terminates a torn tail with `"\r\n"` and fsyncs, failing closed; the verifier reports a damaged checkpoint line as `TORN_CHECKPOINT_LINE`, never a break, and still uses the intact checkpoints (160).
- **D-0.6-8:** the JSON projection bean is `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL` (161). D1, a read-only audit event listener SPI, becomes task 163. D2, application-written events in the hash-chained trail, is roadmap only.
- **D-0.6-9:** add `spring-boot-configuration-processor` for IDE completion of `dataprism.*` keys; 158 stays a pure move and 164 adds the processor.
- **D-163-A:** listeners run on one dispatcher thread behind a bounded queue with a capacity property; overflow drops for listeners only and is logged as `AUDIT_LISTENER_DROPPED`; a shutdown drain policy is required; the feed is best-effort.
- **D-163-B:** listeners are called after whichever sink accepted the event; durability holds only for `hash-chained`, and the docs say so.
- **D-163-C:** the dispatcher is its own `PRIVACY_CRITICAL` bean with `COMPETING_BEAN_REFUSAL`; applications add listeners but cannot replace it.
- **D-163-D:** a listener failure logs one WARN `AUDIT_LISTENER_FAILED` to the application log with the listener class, event id, sequence and exception class name only, never the message or stack trace.
- **D-164-A:** the processor is listed in the compiler plugin's `annotationProcessorPaths` with its version from the Spring Boot BOM, and must not appear in any published module's dependency tree.
- **D-164-B:** the docs-to-metadata check is exact in both directions, with exceptions only in the checked-in `configuration-metadata-gaps.txt`, one reason per line.
- **D-165-A:** add the `ci-gate` job (needs every matrix leg and `container-smoke`, `if: always()`) and rename pages.yml's `build` job to `docs-site`. After `ci-gate` reports on main once, the owner switches the required checks to `ci-gate` alone; the implementer changes no settings.

**Owner decision, 2026-10-06:** Dependabot stays on, with version updates
grouped into one PR per ecosystem per week (`.github/dependabot.yml`).
GitHub-hosted runner minutes are free on this public repo, so the concern was
noise and queueing, not cost. C1 to C7 are all decided (2026-10-07). The
full text follows, recorded here because no other file holds it.

- **C1** — Record the external correlation id inside the hash, as
  `recordVersion` 3. **Confirmed 2026-10-07.** The alternative, a field outside the
  hash, could be edited without breaking the chain.
- **C2** — Resolved: task 117 fixes the v2 encoding before release, and v3
  appends to it.
- **C3** — Keep the hash-chained native `.log` segments authoritative and write
  a separate JSON projection through a tee. Recommended. The alternative is JSON
  as the chained format, which means the verifier must know the mapping. Cost:
  a second copy on disk, purged on the same retention. **Confirmed 2026-10-07:** the hash-chained `.log` stays authoritative and JSON is a separate projection.
- **C4** — The broad pattern `[A-Za-z0-9._:-]{1,128}` admits name-like tokens
  such as `jane.doe`. **Decided 2026-10-07: strict default.** The default
  inbound pattern accepts only a UUID, hex of 16-128 characters, or a W3C
  traceparent. The broader pattern is available only by explicit
  configuration. This protects against personal data being smuggled in as an id.
- **C5** — ECS `event.outcome` is derived from `policyDecision` (`ALLOW` or
  `ALLOW:*` is success, empty is unknown, anything else is failure), and
  operator-set routing constants (dataset, namespace) are added to each output
  line. Is this acceptable as reshaping? The raw decision is kept as well. **Decided 2026-10-07:** acceptable. `event.outcome` is derived from `policyDecision`, operator routing constants are allowed, and the raw decision is kept.
- **C6** — Defer OpenTelemetry and Micrometer Tracing. Traceparent mode covers
  W3C propagation without a tracing dependency, and Micrometer's ThreadLocal
  context propagation conflicts with the parallel fan-out. **Confirmed 2026-10-07:** defer.
- **C7** — No direct Elasticsearch sink; ship from local files with Filebeat or
  Elastic Agent. **Confirmed 2026-10-07.**

Task 112 cites C3 and C5.

#### Owner decisions

D1, D2, D3, D4, D5, D7 and D8 are answered; D6 is open and informational.

- D1: answered 2026-10-06. The 2026-09-08 deferral of the re-identification
  operator surface is lifted. Recorded in
  `docs/architecture.md#decisions-worth-knowing`.
- D2: answered 2026-10-06. Task 107 dropped; the 2026-09-23 decision rejecting
  a keyed chain is reaffirmed. The key would live in the operator's process,
  and third-party verification would need a key that also lets its holder
  forge.
- D3: answered 2026-10-06. A segmented audit sink writes daily files, writes a
  `RETENTION_ANCHOR` checkpoint, then deletes segments past retention. The
  single-file `FileAuditSink` is unchanged. Native segment files are named
  `audit-YYYY-MM-DD.log`.
- D4: answered 2026-10-06. The operator surface is a second port in the same
  process, not a separate JVM. Shapes 105.
- D5: answered 2026-10-06. A retention below six months fails startup unless
  `dataprism.audit.retention-override` is set (Art. 19 allows other periods
  under Union or national law); using it is the operator's legal
  responsibility.
- D6: open, informational. No Art. 10(5) bias-detection profile has been filed.
  Affects what 106 may claim.
- D7: confirmed 2026-10-06. A checkpoint-write failure refuses all audited
  calls until a checkpoint can be written again. Implemented in 97.
- D8: answered 2026-10-06. Four-eyes for re-identification defaults ON. A
  configured high-impact tool call is refused with `APPROVAL_REQUIRED` and an
  `approvalId`; a different person approves it on the operator port; the
  identical call with the same argument fingerprint then succeeds once.

Pending-approval cap (planner's choice, accepted by the owner, task 120):
default 5 live pending approvals per requester, counted separately for
tool-call and re-identification approvals. The operator surface has no rate
limit. `TOO_MANY_PENDING` maps to HTTP 429.

Owner decisions of 2026-10-06 taken during tasks 103, 118, 123 and 124:

- A purge integrity failure (task 103) gives an ERROR log, metrics, and the
  `auditIntegrity` health status DOWN, while serving continues. Four separate
  metric names (`dataprism.audit.retention.unverified`, `.anchor_failed`,
  `.delete_failed`, `.failed`) are accepted in place of one tagged metric.
- Task 123 unifies every denial as `DENY:<code>`, including the MCP tools' bare
  codes. A malformed code is recorded as `DENY:INVALID_REFUSAL_CODE`.
- Task 124 numbers the placeholders `<undeclared-N>` alphabetically by name.
- Scanning property names in the leak validators is a 0.4.x follow-up.
- D-127(a), answered for task 127: when re-identification is on, an
  application `SyntheticValueSource` is wrapped like the default, and the
  wrapper falls back to the wrapped source on any cache failure. There is no
  `REIDENTIFICATION_INDEX_UNWIRED` refusal.
- Approvers on the operator port see the pseudonym and namespace, never the
  subject id.
- Exposing the operator port in Docker Compose, `server.json` and the image is
  a 0.4.x follow-up.

Follow-ups from the task 124 review, not yet tasks:

- `UndeclaredNameToolResultScanTest` never plants an `AuditEvent`; add a
  not-vacuous audit case.
- The orchestrator's shallow merge collapses `<undeclared-1>` from two sources
  into one key. No readable data is lost, but the count is understated.

Follow-ups from the task 120 review, not yet tasks:

- `HazelcastApprovalStore`'s cap count scans all approvals under the requester
  lock (O(n)); consider an index or a predicate.
- The two-member contract test picks a port from `nanoTime` with auto-increment
  off and does not retry on collision.

Follow-ups from the wave 4 reviews (tasks 103, 117, 118, 121, 125), not yet tasks:

- `AuditEventHashTest`: add a non-ASCII pair, so that the length prefix is
  pinned to byte length and not char length.
- Docs: the shape guard still renders real loop-counter indices (safe), which
  is inconsistent with `[*]` elsewhere.
- `RefusalPaths`: `email[07700900123]` next to a declared `email` is reported as
  `$.email[*]`, which can mislead triage.
- Task 103: the containment refusal messages for the same code differ between
  validation and the bean re-check. Also note that two `AuditSink` beans raise
  `NoUniqueBeanDefinitionException`.
- Task 125: `requireVersion()` is duplicated in four integration tests; consider
  a shared helper.
- ~~`DataPrismMcpServer.serverInfo` hardcodes `"0.3.1"`~~ — closed 2026-10-06 by
  task 129: it reads a Maven-filtered version resource and falls back to `unknown`.
- ~~Release time: the Dockerfile `ARG VERSION` and the `publish-image.yml`
  default~~ — closed 2026-10-06 by task 129 (both say 0.4.0).

Follow-ups from the task 102 review, not yet tasks:

- Document in `docs/audit.md` that a custom `AuditCheckpointSink` without a
  `retentionAnchors()` implementation refuses every purge after the first.
- Add a comment at `AuditRetention.java` near line 224 explaining why skipping
  unparseable lines is safe: `verifySegments` runs first and refuses a segment
  it cannot parse.

Follow-ups from the task 100 review, not yet tasks:

- The core `ApprovalStore` needs an explicit revoke or expire path for APPROVED
  requests. `ReidentificationService` rolls back an unaudited approve by
  consuming it, which looks the same as a real collect.
- Move `SubjectForMethodReferenceFixture` into a nested class of
  `ArchitectureTest`; it sits outside task 100's Owns list.
- `findPending` on `InMemoryApprovalStore` and `HazelcastApprovalStore` is no
  longer used by re-identification. Check whether `ToolAdmission` still needs
  it; remove it if not. Tasks 119 and 120 touch both stores, so decide there.

Follow-ups from the task 105 review (0.4.x), not yet tasks:

- ~~Add a behavioural test that an MCP-port MVC error still reaches Boot's
  `/error`~~ — closed 2026-10-07: task 142's `Boot4ErrorPathTest` guards the
  `spring.web.error.path` operator error mapping.
- `data-prism-architecture` tests log the SLF4J multiple-providers warning
  (logback-classic plus slf4j-simple). Remove one from that module's test
  classpath.
- Operator 401s keep the Bearer `error_description` header. Informational; it
  matches the MCP port.

Follow-ups from the task 128 review, not yet tasks:

- `MalformedRefusalCodeTest`'s log assertion uses the throwable proxy's
  `toString`; also check `getThrowableProxy().getMessage()`.
- `ToolCalls` copies `approvalId` into client text unchecked. It comes from the
  store, so the risk is low; consider validating its format.

Follow-ups from the task 96 review, not yet tasks:

- Non-scrub refusals (budget exhaustion, `NO_SOURCE_DATA`) also get
  `merged:<refused>`, which wrongly suggests a validation failure.

Follow-up from the task 122 review, not yet a task:

- `ServerStartupTest.java:224-225`: the comment says a port collision makes
  startup fail. With `--server.port=0` a held 127.0.0.1 port is simply never
  assigned, so reword it. The 404 cause is also unproven (mechanism
  reproduced only); reopen 122 if the flake returns.

Follow-ups from the task 97 review, not yet tasks:

- `AuditRecorder.writeCheckpoint` clears `checkpointFailure` directly, outside
  the two D7 methods. Move it into a D7 method or fix the field javadoc.
- `docs/audit.md:133`: the exit-3 row's precedence text omits exit 5.
- `AuditChainVerifierCli`: a malformed checkpoint file prints "could not read
  <audit path>". It should name the checkpoint file.
- A whole deleted boot is detectable only if that boot wrote a checkpoint past
  sequence 0. Consider a periodic checkpoint soon after boot (task 103's
  scheduling).

Follow-ups from the wave 1 reviews, not yet tasks:

- Rename `HazelcastOversightTest`'s `sameIdCreatedConcurrently...NeverCrossApproves`
  or assert approve and consume after the race; the name claims more than the
  test checks.
- Add a direct test for `reject()` on an ambiguous id.
- Add a test pinning that undeclared object or array values are never descended
  into when recording dispositions.
- `data-prism-integration-tests` surefire reports "kill self fork JVM" after
  about 30 seconds following `ShippedDefaultsTest`. A non-daemon thread is the
  likely cause. The build still passes.

Retired from these lists on 2026-10-06 because tasks 117-122 now cover them:
the `AuditEventHash` escaping gap and the verifier's limitation text (117), the
payload-key leak on refusal paths (118), `InMemoryApprovalStore` parity and the
`HazelcastApprovalStore.java:55` comment (119), the per-requester pending cap
(120), the `REFUSED` wording and `DECISION:<code>` documentation (121), and the
`ServerStartupTest` 404 flake (122).

### S8 and S9a — done

S8 (security: OAuth2 resource server, scope/principal/purpose/case derived from
an authenticated caller, streamable HTTP transport) and S9a (the cheap half of
observability: real principal in audit, Micrometer metrics, a PII log scan that
can actually fail) are both complete, closed by task 07 merging 2026-09-09. See
`docs/plan/HISTORY.md` — grep `Task 07` — for what landed and what it cost.

### Task 09 — done

Closed the last two real defects and cleared four pieces of debt. Task 09
merged 2026-09-09. See `docs/plan/HISTORY.md` — grep `Task 09` — for what
landed and what it cost.

### Task 10 — done

Made `main`'s red-on-Linux mTLS refusal assertion hold on both platforms it
runs on, without weakening it. Task 10 merged 2026-09-09, verified green on
Actions run 34386674901. See `docs/plan/HISTORY.md` — grep `Task 10` — for
what landed and what it cost.

### Tasks 11, 12, 13 — done

All three safety tasks merged through protected, green pull requests on
2026-09-13. Task 11 makes the REST 500 assertion specific; Task 12 enforces the
missing determinism and validation boundaries; Task 13 proves every live
Hazelcast map contains only recomputable state and no raw sensitive fixture.
See `docs/plan/HISTORY.md` — grep `Tasks 11, 12 and 13` — for verification and
the cost of closing them.

### Tasks 14, 15, 16, 17 — done

The adoption slice: a shared standalone-server/Spring-starter configuration
contract (14), the validated Spring Boot configuration core (15), the Spring
Boot starter and embedded example (16), and the packaged, fail-closed
standalone server (17). All merged through protected, green pull requests
2026-09-13 to 2026-09-14. See `docs/plan/HISTORY.md` — grep `Task 14: define`,
`Task 15: build`, `Task 16: ship`, `Task 17: package` — for what landed and
what each cost.

### Tasks 21, 22, 23, 24 — done

Closed the five defects found reviewing tasks 13-17's delivery (which was done
by OpenAI Codex, outside this kit), plus one fail-open found by the architect
that no reviewer had flagged: fixture-development refusal moved into the
shared validator so every consumer inherits it (21); `PrivacyPolicyResolver`
and `LlmResponseValidator` made non-replaceable, with a classified sweep of
every extension-point bean (22); the architecture rules given back the whole
module graph through a new scanning module (23); the packaged-server secret
scan given a search space that can actually fail (24). Merged through
protected, green pull requests 2026-09-14 (#28-#31). Post-merge full-reactor
re-run: 394 tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Tasks 21, 22, 23 and 24` — for what landed and what it cost, including the
counting-method settlement and the false-claim-propagation lesson.

### Task 25 — done

Wired the shared read budget through the auto-configuration and made the
Hazelcast topology an explicit configuration choice instead of an implicit
default: `embedded` now produces a real `HazelcastScopeBudget`, and
`dataprism.hazelcast.topology` is required rather than defaulted. Merged
through a protected, green pull request 2026-09-14 (#33). Post-merge
full-reactor re-run: 400 tests, 0 failures, 0 errors. See
`docs/plan/HISTORY.md` — grep `Task 25` — for what landed and what it cost,
including the ownership-breach precedent.

### Task 18 — done

Replaced source-reading as onboarding with one reproducible local Compose
journey: standalone server, synthetic fixture APIs and a local JWT issuer,
proving an agent-compatible MCP request succeeds end to end. Delivered three
new runnable modules (`data-prism-quickstart-fixtures`,
`data-prism-quickstart-issuer`, `data-prism-quickstart-extension`), since none
of the three runnable pieces it needed existed yet and the server refuses the
fixture-development shortcut the original brief assumed. Merged through a
protected, green pull request 2026-09-14 (#35). Post-merge reactor: 403
tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep `Task 18` —
for what landed and what it cost, including the `.env.example` hand-off this
environment forces on any future dotfile task.

### Tasks 19, 20 — done. The plan is done.

Task 19 published tested MCP agent connection guides — verified against a
real client (Claude Code CLI 2.1.271), not reasoned about, with GUI clients
explicitly excluded rather than listed unverified. Task 20 wired
`data-prism-connectors-rest`, dormant and unconsumed since it was built, into
a configuration-driven JSON REST mode with the same fail-closed guarantees as
the Java-first path, proven by an end-to-end parity test. Both merged through
protected, green pull requests 2026-09-15 (#37, #38). Post-merge full-reactor
re-run: 441 tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Tasks 19 and 20` — for what landed and what it cost.

With 19 and 20 closed, every task this plan named is done: the five defects
found reviewing the tasks 13-17 Codex work are closed (tasks 21-24), the
deployment slices shipped (14-18), the quickstart demonstrates the whole
system end to end (18), and the configuration-driven JSON mode was the last
piece (20). There is no task open and none in flight. The small open items
below are debt found along the way, not scheduled work — pick one up only if
a future task already owns the file it names.

**Nothing is scheduled past this point.** S5, S6, S7, S10, S11 and S12 remain
in "Someday" below, exactly as the 2026-09-09 stopping-point decision left
them (see "Decided" above and `docs/architecture.md#decisions-worth-knowing`).
Picking any of them up is a new planning decision, not a continuation of this
plan — do not treat this record as opening a next wave.

### Simplification plan, wave 1 (tasks 26-30) — done

A separate planning cycle from the slice plan above: opened 2026-09-15 to
close small duplication and readability debt accumulated across the earlier
waves. Task 26 collapsed the duplicated JWT/OIDC discovery, SSRF guards and
caller-context extractor in the server and the example into one shared
`JwtDecoderSupport`/`JwtCallerContextExtractor` pair in
`data-prism-spring-boot-autoconfigure`. Task 27 reduced
`DefaultContextOrchestrator` to its two used constructors and lifted the
fetch/scrub/merge loop into its own method. Task 28 extracted the duplicated
YAML `enumValue` logic into a new core `StrictYaml` helper. Task 29 bundled
`JsonTreeScrubbingEngine`'s parent/siblings/owner parameters into a private
`OwnerScope` record. Task 30 reformatted `DataPrismProperties` to one
statement per line. All five merged locally 2026-09-15, no conflicts. A
single serialized full-reactor `mvn clean verify` from the main checkout
afterwards — not the five testers' parallel runs, three of which hit
transient classpath failures caused by five concurrent Maven builds sharing
one local repository — gives 440 tests, 0 failures, 0 errors. See
`docs/plan/HISTORY.md` — grep `Simplification wave 1` — for what landed and
what it cost, including why a task's Owns list needs deriving from callers
rather than same-package usage.

### Simplification plan, wave 2 (tasks 31-32) — done. The simplification plan is done.

Task 31 gave `core/descriptor` — the ~320 lines of `DescriptorFieldMetadataResolver`,
`ModelDescriptors` and `ModelDescriptor` that had tests but no production
caller — a real one: an optional `dataprism.privacy.descriptor-file`
property that, when set, loads and validates a model-descriptor YAML file
and wraps the default `FieldMetadataResolver` in
`DescriptorFieldMetadataResolver`. Four distinct refusal codes, all fail
closed with no fallback to the undecorated resolver and no file content in
any message. Task 32 deleted `data-prism-audit` and moved its four classes
and test into `data-prism-core` with the package unchanged, so no consumer's
imports moved; five poms and the architecture module table were updated.
Both merged locally 2026-09-15, no conflicts. A single serialized
full-reactor `mvn clean verify` from the main checkout afterwards gives 460
tests, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Simplification wave 2` — for what landed and what it cost, including the
now twice-confirmed unreliability of concurrent Maven builds against this
repo's shared local repository.

With 31 and 32 closed, the simplification plan opened 2026-09-15 has no task
left: waves 1 and 2 closed every item it named. Superseded by the release plan
below, opened the next day.

### Release plan (tasks 33-39) — done. The release plan is done.

Opened 2026-09-16: cutting a publishable 0.1.0. Tasks 33, 34 and 35 merged
through protected, green pull requests (#41, #42, #43) the same day — 0.1.0
version cut plus a tag-triggered release workflow (33), release hygiene files
(34), and `dataprism.transport.mode=stdio` refusing unconditionally instead of
serving nothing (35). Tasks 36, 37 and 39 merged through protected, green pull
requests (#57, #58, #59) the same day — Maven Central publishing for the 12
deployable modules (36), the fixture-free distributable server image behind
gated, unpublished workflows (37), and the default-`mode=HTTP` non-web
transport fail-open closed with a new `MCP_TRANSPORT_UNAVAILABLE` code (39).
Task 38 merged through a protected, green pull request (#61) the next day —
the MCP registry entry (`server.json`, `mcp-name` marker, `publish-mcp.yml`),
which closed a defect that would have shipped a broken public onboarding
contract: a required environment variable that does not bind under Spring
Boot's relaxed map-key rules for a hyphenated prefix. See `docs/plan/HISTORY.md`
— grep `Tasks 33, 34, 35`, `Tasks 36, 37, 39`, and `Task 38` — for what
landed and what each cost, including the corrected test counts, the
fail-open's dependence on three modules' tests, the amended task 37
refusal-code criterion, and the env-var spelling defect.

With 38 closed, every task the release plan named is done.

### Task 40 — done. No task file remains under `docs/plan/tasks/`.

Opened 2026-09-16 after Maven Central 0.1.0 published: the GHCR server image
was still amd64-only, and the (unpublished) MCP registry entry points strangers
at it, a large share on Apple Silicon. Task 40 rebuilt `publish-image.yml` as a
native per-architecture matrix — `ubuntu-latest` and `ubuntu-24.04-arm`, each
verifying its own image on its own hardware before pushing by digest — with a
final job assembling the two digests into a `linux/amd64` + `linux/arm64`
manifest list. Merged through a protected, green pull request 2026-09-16 (#64).
See `docs/plan/HISTORY.md` — grep `Task 40` — for what landed, the buildx
driver bug only a real run caught, and the honest limits on what has and has
not actually been exercised yet.

**The publish sequence below is superseded by task 41's, immediately after.**
Task 40 planned to re-push the image as `:0.1.0`; task 41 reversed that
decision the next day. Left unedited above as the record of what task 40
actually closed with — do not follow it.

### Task 41 — done. No task file remains under `docs/plan/tasks/`.

Opened and closed 2026-09-17: `publish-image.yml`'s own comment (written by
task 40) still planned to re-push the GHCR image as `:0.1.0` once multi-arch
landed, on the reasoning that `main` and the `v0.1.0` tag were the same tree.
They no longer are, and for a sharper reason than drift: `git show
v0.1.0:.github/workflows/publish-image.yml` shows the tag predates the
multi-architecture pipeline entirely, so dispatching `workflow_dispatch`
against `v0.1.0` would silently re-run the old single-architecture workflow,
not task 40's matrix — `workflow_dispatch` reads the workflow file from the
ref it targets. Separately, `git diff --stat v0.1.0..main` confirms `main`
has moved: CI workflows, documentation, and `data-prism-quickstart-issuer/pom.xml`
only, no production source and no other module pom. Force-moving a tag a
published GitHub Release already points at is possible but dishonest; cutting
a patch version is cheaper. Task 41 moved the whole tree from 0.1.0 to 0.1.1
— 18 module poms plus root, both `server.json` version fields, the MCP
`serverInfo` handshake literals in `DataPrismMcpServer.java`, all four
Dockerfiles, and the stale `publish-image.yml` comment — with a `CHANGELOG.md`
entry and nothing else. Merged through a protected, green pull request
2026-09-17 (#66), which also carried the previously-unpushed `plan: task 41`
commit `main` was already sitting on. `mvn -B clean verify` green at 466
tests, the same count as before.

**Planned on a false premise, caught only at review.** The brief handed to
this task claimed `main` carried a nimbus-jose-jwt security patch reaching
the shipped server image, so an image tagged `:0.1.0` from current `main`
would ship a different JWT library than the Central 0.1.0 artifacts. That is
false: PR #55's only change is a version pin in
`data-prism-quickstart-issuer/pom.xml`; `data-prism-server` gets nimbus
transitively through `spring-security-oauth2-jose`, untouched by that PR, and
neither published image builds the issuer module. The task file (now
deleted) asserted this at its old lines 33-40 and 97-102, and required the
CHANGELOG to repeat it. The implementer wrote the false claim in faithfully,
exactly as briefed; the reviewer caught it and required a correction commit
before approving. The branch was right to contradict its own task file. This
is the second time this release cycle a reviewer has caught a false premise
supplied by planning rather than a defect introduced by an implementer — both
times the agents downstream had no way to challenge a factual claim handed to
them as context, and the verification chain is what caught it, only at the
last step. See `docs/plan/HISTORY.md` — grep `Task 41` — for the full
account.

**Superseded by the 0.2.0 publish sequence in the "Tasks 42-47" section
below.** Left unedited here as the record of what task 41 actually closed
with — do not follow it. `v0.1.1` was tagged and released, and
`publish-mcp.yml` was dispatched against it but returned 403 (see task 44
below); do not re-dispatch any `v0.1.1` step now that 0.2.0 is the version to
publish.

**Nothing left is development work** — the owner-driven publish sequence:

1. Tag `v0.1.1`. `release.yml` creates the GitHub Release from it.
2. Manually dispatch `publish-image.yml` on `v0.1.1` for the multi-arch
   manifest — the first real execution of task 40's digest-push and
   manifest-assembly steps against a ref that actually carries them.
3. Manually dispatch `publish-central.yml` on `v0.1.1` and approve the Portal
   bundle. Maven Central 0.1.0 is already published and immutable; 0.1.1 is a
   second, additional release, not a replacement.
4. Verify the manifest resolves per platform on real hardware: an x86_64 host
   should pull `amd64`, an Apple Silicon Mac should pull `arm64`.
5. Manually dispatch `publish-mcp.yml` on `v0.1.1` for the registry entry,
   which requires the image to be pullable. The reviewer confirmed
   `publish-mcp.yml`'s `docker manifest inspect` pullability guard is
   satisfied by a manifest list, so no successor task is needed there.

Owner actions outstanding, unchanged by task 41, none of which any agent can
perform:

- The `central` GitHub Environment exists but has no required reviewers
  ticked, so it currently gates nothing.
- `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` should move from
  repository secrets to the `central` environment's scope, now that
  `publish-central.yml`'s stage job no longer references them.
- The MCP registry namespace claim (`io.github.aindriub`) happens via GitHub
  OIDC at `publish-mcp.yml` dispatch time — no separate owner action, but the
  dispatching identity must be the repository owner's.

### Dependabot PRs — closed

All twelve PRs opened since task 34's `dependabot.yml` landed (#44-#55) are
resolved; zero remain open. Eight closed without merging, each with recorded
reasoning:

- #54 (`spring-boot.version` 3.5.16 to 4.1.1) — a major version bringing
  Jackson 3 transitively; two Jackson majors on one classpath would open a
  path around the scrubbing engine, breaching rule 5.
- #44, #46, #50, #52 (Docker base image JRE 21 to 25) and #48, #49, #53
  (build image 21 to 26) — every pom deliberately targets `--release 21`,
  and separately `build.yml` never builds a Docker image, so these PRs' green
  checks were vacuous regardless.

Four merged: #55 (`nimbus-jose-jwt` patch — the dependency task 41's own task
file mistakenly credited with reaching the server image, see above) and #45,
#47, #51 (`actions/checkout`, `actions/upload-artifact`, `actions/setup-java`
version bumps).

### Tasks 42-47 — done. Second MCP tool, registry casing, a scan hardening found along the way, and 0.2.0 cut

Opened 2026-09-17. All six tasks are done, merged through protected, green
pull requests the same day: 42 (#69), 44 (#68), 43 (#71), 46 (#73), 45 (#75),
47 (#76, landed on top of 45 after main advanced under it — the expected
sequential-merge race, resolved by merging main into 47's branch before
opening its PR). No task file remains under `docs/plan/tasks/`.

**Task 42 — done. No task file remains under `docs/plan/tasks/`.**

Shipped `compare_entity_sources`, the second MCP tool: per-field agreement,
disagreement (`INCONSISTENT`/`FORMATTING_ONLY`/`ABBREVIATION`) and
`MISSING_IN_SOME_SOURCES` findings over the same correlated, scrubbed
`ContextResponse` that `get_entity_context` already builds. Argument and
response field are `subjectId`, not the spec's `idInternal` — consistency with
the shipped tool an MCP client sees alongside it. `identity` carries
pseudonymised values copied verbatim from the scrubbed tree, never raw.
Agreement is computed pre-scrub, in `NamespaceCorrelationService`, because two
genuinely different values collapse to the same pseudonym after scrubbing and
become indistinguishable from two identical ones — recorded as an amendment to
§42 in `docs/design-review.md`. Both `ContextRequest` and `ContextResponse`
gained record components on a record type already published to Maven Central
(0.1.0 and 0.1.1, immutable); both got explicit constructors preserving the
old canonical-constructor descriptor, checked twice with `javap` against the
published jar rather than assumed safe. Full reactor `mvn -B clean verify`
green, 483 tests (466 baseline, +17). Merged 2026-09-17 (#69). See
`docs/plan/HISTORY.md` — grep `Task 42` — for what landed and what it cost,
including a defect three of the task's own tests missed and why.

**Task 44 — done. No task file remains under `docs/plan/tasks/`.**

Corrected the MCP registry namespace to `io.github.AindriuB/data-prism`,
matching the casing the registry actually grants for the GitHub login — the
server declared the lowercase `io.github.aindriub/data-prism` and
`mcp-publisher publish` returned 403 against it. Fixed in the three strings
that carry the name (`server.json`, `README.md`'s marker,
`docker/distribution/Dockerfile`'s label) and nowhere else; Maven Central's
`io.github.aindriub` coordinates are a different, correctly-lowercase
identifier and are untouched. Added a cross-file guard to `publish-mcp.yml`
that fails the workflow if the three strings drift apart again, proven
non-vacuous by the reviewer independently. Merged 2026-09-17 (#68). See
`docs/plan/HISTORY.md` — grep `Task 44` — for what landed and what it cost.

**Does not clear the 403 on its own.** The MCP label is baked into the GHCR
image at build time, so the corrected namespace takes effect only once
`publish-image.yml` rebuilds and re-pushes the image under 0.2.0 (task 45,
then an owner-dispatched `publish-mcp.yml` run). `mcp-publisher publish`
failed 403 against `v0.1.1` during this wave and nothing was published.

**Task 43 — done. No task file remains under `docs/plan/tasks/`.**

Put `compare_entity_sources` on the real assembly — real stub adapters, the
real `JsonTreeScrubbingEngine`, real audit — and proved no raw fixture value
reaches its output. Settled the assumption task 42's tests could only assert,
never test: the real scrubbed tree is keyed by
`FieldMetadata.fieldName()` (e.g. `customerName`), not by the namespace
constant (`PERSON_NAME`), proven against the real engine via
`DataPrismAssembly.standard()`, not a stub — and confirmed by mutation, not
argument: flipping `JsonTreeScrubbingEngine`'s SYNTHESIZE case to pass raw
values through reddened two of three end-to-end tests, reverted
byte-identical. Pinned the shipped example role in all three places it is
granted (`ExampleApplication`, `DataPrismAssembly`, `application.yaml`), where
previously only the two Java factories were pinned. Merged 2026-09-17 (#71).
Full reactor `mvn -B clean verify` green, 487 tests. See `docs/plan/HISTORY.md`
— grep `Task 43` — for what landed and what it cost, including the PII-scan
weakness it surfaced (now task 46) and a third instance this cycle of a
downstream agent correcting a coordinator's factual premise.

**Task 45 — done.** Cut version 0.2.0 across the reactor — 19 module poms plus
root, `server.json`, the MCP `serverInfo` literals, four Dockerfiles,
`README.md`, three PackagingIT/SmokeIT tests — plus a `CHANGELOG.md` entry
checked claim-by-claim against tasks 42-44's diffs, and a coordinator-
authorized two-line fix to two stale `0.1.1` literals in
`publish-image.yml` that a `--hidden` sweep found and a default `rg` sweep
never could have. Merged #75, 488 tests unchanged. See `docs/plan/HISTORY.md`
— grep `Task 45` — for what landed and what it cost, including the sweep
blind spot every prior version-literal sweep in this repository shared.

**Task 46 — done.** `PiiLogScanTest`'s banned values derived from the stub
adapters' own fixtures instead of a hand-maintained list. Merged #73, 488
tests. See `docs/plan/HISTORY.md` — grep `Task 46`.

**Task 47 — done.** Closed the two holes a reviewer found in the control task
46 fixed: the banned-value derivation now recurses through `Record`
components and `Collection` elements to arbitrary depth instead of stopping
one level deep, and `findLeaked`'s bounds moved from `\b` to `(?<!\w)`/`(?!\w)`
lookarounds so a banned value ending in punctuation (both order notes) can no
longer pass unmatched on an ordinary log line. `OrderDto` gained one nested
`DeliveryDto` component so the recursion is falsifiable against a real
fixture. Both holes proven by mutation, and the hex-collision defence the old
bound existed for was pinned before it was replaced. Merged #76, 492 tests.
See `docs/plan/HISTORY.md` — grep `Task 47`.

With 42-47 all closed, no task file remains under `docs/plan/tasks/`.

**Task 48 — done. No task file remains under `docs/plan/tasks/`.** The
0.2.0 publish sequence's registry-dispatch step (step 5 below, as it read
before this update) returned 400: OCI packages must not carry
`registryBaseUrl`, and `identifier` must be a canonical reference. Task 48
corrected `server.json`'s package block and extended `publish-mcp.yml`'s
version-agreement guard to a moved `v0.2.0` tag. Merged 2026-09-17 (#78).
See `docs/plan/HISTORY.md` — grep `Task 48` — for what landed, including why
the fix was found by reading the registry's server-side validator rather than
the error text, and why the tag had to move.

### 0.2.0 release — done. Every artifact is live.

Closed 2026-09-17. GitHub Release v0.2.0, Maven Central 0.2.0 (all modules),
the GHCR multi-arch image (verified `arm64` and `amd64` from the same tag on
real hardware), and the MCP registry entry
(`io.github.AindriuB/data-prism@0.2.0`, status `active`, published
2026-09-17T18:59:49Z) are all published. The registry entry is the first
successful publish after two failed attempts (403 namespace casing, fixed by
task 44; 400 forbidden OCI fields, fixed by task 48). See
`docs/plan/HISTORY.md` — grep `0.2.0 release complete` — for the full account
of both failures, why a green local `mcp-publisher validate` did not predict
either, and why the `v0.2.0` tag was force-moved to the PR #78 merge commit
without misrepresenting any already-published artifact.

Owner actions outstanding, unchanged by this wave: the `central` GitHub
Environment still has no required reviewers ticked, and
`CENTRAL_TOKEN_USERNAME`/`CENTRAL_TOKEN_PASSWORD` still live as repository
secrets rather than the `central` environment's scope — see task 41's section
above for the original recording of both.

### Tasks 49, 50, 51 — done

Opened 2026-09-17 after an architect established that `data-prism-example`
was never an example — it is the reactor's integration test suite, hosting
`PiiLogScanTest`, the sole enforcement of privacy rule 7, and a name that
invited deletion. Task 49 renamed it to `data-prism-integration-tests`
(`git mv`, nothing removed), proving by mutation and instrumented printing —
not by a green run alone — that `ArchitectureCoverageTest`'s dynamically
computed module list still saw the renamed module, since that class's own
javadoc records a prior silent narrowing from exactly this kind of miss.
Six `data-prism-example` strings (the JWT issuer and audit writer-id config
values, and the tests asserting on them) were left alone deliberately:
changing them changes observable audit output. Tasks 50 and 51 shipped the
first consumer guides this repository has had, `docs/extending.md` and
`docs/tools.md`, built entirely from real captures rather than transcribed
examples. Merged through protected, green pull requests 2026-09-17 (#80,
#82, #81 — the sequential-merge race hit twice, resolved each time by a
clean `git merge origin/main`). Post-merge full-reactor re-run:
`BUILD SUCCESS`, 0 failures, 0 errors. See `docs/plan/HISTORY.md` — grep
`Tasks 49, 50, 51` — for what landed and what it cost, including the
copyable-snippet lesson from task 50's pom block.

### Task 52 — done. No task file remains under `docs/plan/tasks/`.

Reconciled the documentation this wave left stale or newly-relevant.
The most consequential fix: `README.md` had said "Until Task 20
delivers…" the configuration-driven JSON REST mode since before that task
shipped — a stranger reading it would be told to write a Java adapter when
a configuration-only path may serve them. Corrected from the code, not the
old claim: `data-prism-connectors-rest` carries no `maven.deploy.skip`, so
it is a normally published, self-registering artifact needing no Java from
an operator, and `ConfiguredJsonFieldMetadataResolver.descendable()`
returns `false` unconditionally, so it covers only flat JSON — scalar
fields and arrays of them, never nested objects. `README.md` and
`docs/extending.md` now agree, both written from the code independently.

The task was planned against four stale "only one tool ships" claims; the
count grew twice under examination. A reviewer found a fifth during task
51 (`docs/agents/stdio.md` claiming the fixture caller holds only
`GET_ENTITY_CONTEXT`), and the executing scribe found a sixth
independently — `docs/architecture.md`'s Deployment paragraph calling
configuration-driven JSON sources "deferred", the same defect as the
README line. All six are corrected, using tool names taken from a real
`tools/list` response captured by driving the compiled fixture over a
FIFO stdio session, not inferred from `Capability.java`. The task file's
own cited line numbers were stale in three places, including
`docs/quickstart.md:81`, which named a section header rather than the
claim — the real one was at `:113`; re-derived with `rg -n` rather than
trusted.

A first pass wrote that both tools appear in `tools/list` "because this
fixture's caller holds both capabilities", implying the tool list is
capability-filtered. It is not: both tool specs register
unconditionally, which is exactly why the Compose quickstart's caller can
list `compare_entity_sources` and not call it. Caught at review before
merge, since the false causal link contradicted both `docs/quickstart.md`
and the grant-before-call rule `docs/tools.md` teaches. That grant-before-
call behaviour is now stated rather than left to surprise a reader:
`docker/server/application.yaml` grants the quickstart's `investigator`
role only `GET_ENTITY_CONTEXT`, so a new user following the Compose
quickstart sees both tools listed and gets `TOOL_NOT_PERMITTED` calling
the second.

Also carried: every remaining `data-prism-example` reference across the
owned files renamed and re-characterised as the reactor's integration
test suite; `ArchitectureTest`'s ownership re-pointed to
`data-prism-architecture`; `docs/extending.md` and `docs/tools.md` linked
from `README.md`, `docs/quickstart.md` and `docs/agents/README.md`. Merged
2026-09-17 (#84). See `docs/plan/HISTORY.md` — grep `Task 52` — for what
landed and what it cost, including the line-number-staleness lesson.

### Known open items at release, none scheduled

Recorded 2026-09-17 alongside the 0.2.0 release close-out, so they are found
in one place rather than scattered across dated `/verify` findings below.
None blocks anything already shipped; pick any up only as its own planned
work.

- The two latent `PiiLogScanTest` traps from task 47's `/verify` — see
  "Small open items, unscheduled" below (dated 2026-09-17, found on task 47)
  for both.
- Nothing asserts on the MCP `serverInfo` handshake version string — see the
  same "Small open items, unscheduled" section below (dated 2026-09-17, found
  on task 41) for the detail.
- Three MCP tools named across this plan and never built.
  `compare_entity_sources` and `get_entity_context` are the only two shipped.
  `search_entity_data` has a full spec, `pack.md:1475-1499` (§43): signature
  `search_entity_data(entityType, idInternal, query)`, with the explicit
  constraint that server-side policy must translate the query rather than
  passing any backend query language (Elasticsearch DSL, SQL, JPQL, Mongo)
  through to the LLM. `describe_entity_model` is named in this file's Someday
  list (S11) and in `docs/design-review.md:175-179` (§B5), but neither gives
  more than intent — a one-paragraph amendment saying it should return field
  names, namespaces and which fields are pseudonymised or redacted, with no
  parameter list, response shape or error codes. `describe_entity_model`
  needs a spec written before a task file for it would be buildable;
  `search_entity_data` does not.
- The append-only audit sink and its hash-chain verifier are still missing.
  The hash chain itself exists and is exercised (`AuditRecorder`,
  `data-prism-core/src/main/java/.../audit/`), but the only shipped
  `AuditSink` is `Slf4jAuditSink` — there is no durable sink and nothing
  verifies a stored chain for tampering. S9 as scoped in "Someday" below
  named both the chain and "append-only sink"; only the cheap half (real
  principal in audit, metrics, a PII log scan able to fail) was ever picked
  up, by task 07 on 2026-09-09. The durable sink and its verifier were never
  scheduled as their own task and remain outstanding. Listed here because the
  release makes it visible to anyone integrating this as a dependency, not
  because it is newly found.

Added 2026-09-17, out of task 52's wave. Neither blocks anything; pick either
up only as its own planned work.

- The external consumer demo at
  `/Users/Andrew/workspace/data-prism-github-demo` is not a public
  repository — `github.com/AindriuB/data-prism-github-demo` returns 404 —
  so `docs/extending.md` deliberately references no worked external
  example. If the owner publishes it, the adapter guide would be improved
  by linking a complete example against a real public API.
- `docs/agents/**` documents only one verified MCP client, the Claude Code
  CLI. Now that two consumer guides exist (`docs/extending.md`,
  `docs/tools.md`) and the server is listed on the MCP registry, other
  clients (any other MCP-capable agent) are unverified territory — nothing
  claims they work, but nothing has checked either.

### Wave 1 (tasks 53, 54, 56, 57) — done. Task 55 done.

Opened from a measured UX review 2026-09-21: the Compose quickstart demos
well but onboards nobody, since `QuickstartCustomerAdapter` hardcodes
`SOURCE_NAME="customer"` and `CustomerModel.class` into the image, and the
configuration-driven REST connector (task 20) had no `IdentityResolver`
supplied anywhere, so it was not actually no-code. Task 53 adds opt-in
`dataprism.identity.resolver: pass-through`; task 54 collapses the
duplicate `dataprism.sources` base-url check; task 56 extracts a
one-command demo out of `smoke-test.sh` (its first attempt failed
verification — see `docs/plan/HISTORY.md`, grep `Wave 1 (tasks 53, 54, 56,
57)`, for why); task 57 renders every configuration refusal as an
operator-facing block with no stack trace. All four merged locally
2026-09-21, no conflicts. See `docs/plan/HISTORY.md` — grep `Wave 1 (tasks
53, 54, 56, 57)` — for what landed and what it cost.

**Task 55 (publish the quickstart images) merged 2026-09-23, PASS/APPROVE
re-verified against current base, onto
`v0.3.0/audit-trail-and-nested-json`.** Originally held because its
`compose.yaml` pulls
`ghcr.io/aindriub/data-prism-quickstart-{server,fixtures,issuer,certs-init}`,
none of which are published yet — merging to `main` today would break
`docker compose up` (the command both `README.md` and `docs/quickstart.md`
tell a new user to run) for everyone. That risk does not apply to merging
onto the `v0.3.0` integration branch, which is not what a reader pulls
until it reaches `main`; the unblock condition (a `v*` tag pushed and
`publish-image.yml` dispatched for all four images) still gates the
integration branch's own merge to `main`, recorded in the release sequence
below. Its worktree and branch were removed after merge; task file
retired. See `docs/plan/HISTORY.md`, grep `Task 55`, for what landed, the
release-day version-pinning defect its fix pass closed, and the two
inert-plumbing defects (a dangling build-arg, an inaccurate comment) found
and fixed at this close-out.

### Task 58 — done. No task file remains under `docs/plan/tasks/`.

Shipped `docs/protect-your-own-api.md`, a YAML-only walkthrough taking a
reader with a flat JSON REST API to a pseudonymised MCP response without
writing Java — `data-prism-connectors-rest` plus
`dataprism.identity.resolver: pass-through` (task 53) and the
single-statement transport (task 54). Publishes the former test-only
catalogue as `examples/json-sources/customer-api.yaml`, fully commented,
and repositions `docs/extending.md` from the front door to the escape
hatch for nested responses, custom fetch logic and models a flat
catalogue cannot express. Merged 2026-09-22 (`bfabcb1`). See
`docs/plan/HISTORY.md` — grep `Task 58` — for what landed and the six
verification attempts it cost, including two lessons worth carrying
forward: a documentation task needs both a literal-reader tester and a
rules-reading reviewer, since neither method alone would have caught what
the other did; and this is the second task this wave (after 56) to assume
a bare JSON body from an endpoint that actually negotiates SSE, caught
only by exercising the real server both times.

**Task 59 — done.** Merged `--no-ff`, PASS + APPROVE on attempt 4, onto
`v0.3.0/quickstart-exit-ramp` — a local branch cut from `main` at the
`v0.3.0` integration branch's own merge (PR #96, `543defb`), not yet opened
as a PR. The owner ran this task before tagging, not after publish as
originally planned (see the release sequence below); every command it
quotes was verified via the from-source Compose path, since the published
GHCR images do not exist yet. Task file retired; see `docs/plan/HISTORY.md`,
grep `Task 59`, for what landed and the four-attempt cost.

### v0.3.0 plan (tasks 60-71) — opened 2026-09-22, none started

Thesis: "protect a real API, without Java, and prove what happened."
Follows from the 2026-09-21/22 onboarding work (wave 1 above and task 58,
both closed) plus two architect spikes run 2026-09-22, both owner-confirmed,
whose conclusions this section records because they shape every task below.
Task files for all twelve exist under `docs/plan/tasks/` (60-71); none has
been edited to add anything beyond what is recorded here.

**Nested JSON — bounded, and why.** One level only: named sub-catalogues
declared in the same YAML file, no dotted paths, no JSONPath, no wildcard
descent, no inferring structure from the wire. `subject-json-path` is
untouched; nested objects never carry their own subject. The reasoning that
unlocked this after task 20 shipped only a flat catalogue: the
reviewed-adapter boundary is "nobody may assert a classification without a
reviewed artefact behind it", not "the artefact must be a compiled Java
class" — the YAML catalogue already is that artefact for the flat case, and
a named sub-catalogue applies the same review surface once more. Arbitrary
depth or JSONPath addressing is the arbitrary-JSON-mapping outcome
`docs/architecture.md:104-113` rejects and must not be built at any
increment size. Owner decision: the descend-key mechanism stays entirely
inside `data-prism-connectors-rest` — core's `FieldMetadataResolver` and
`FieldMetadata` do not grow a second string-keyed resolution path, because
that SPI is shared with the Java-first path that works correctly today and
must not carry a concept only one implementation uses. This is why 60 and
61 are both needed rather than one task: `ConfiguredJsonScrubbingEngine`'s
raw-value leak check (`SourceValues.prohibited`, `:93`) must walk the same
nested structure the engine now descends, or it silently stops covering
nested fields — a fail-open, with no annotation-processor backstop for YAML
the way there is for Java models — and 61 is what exercises that through
the real MCP transport rather than trusting the parser alone.

**Audit — a file sink and an offline verifier, not an endpoint.** Ship a
single-file, append-mode, fsync-per-record `FileAuditSink` with no rotation
(rotation is operational, not a correctness question) wired via the
already-reserved `hash-chained` property value, plus an offline verifier
CLI — deliberately not an Actuator endpoint and not a startup check,
because an in-process endpoint conflates "the running instance says its own
log is fine" with independent verification. Owner decision on what it
claims, binding on every task and document in this wave: the verifier
cannot detect truncation of the most recent records. Deleting the tail of
an append-only file leaves a chain that verifies perfectly end to end, and a
crash mid-write is indistinguishable from a malicious truncation from
inside the file. Detecting that needs an external checkpoint held outside
the operator's control, which v0.3.0 does not build. The verifier must
print that limitation on every run, and the documentation must state that
tamper-evidence here means intra-writer edit/delete detection, with durable
append-only-ness left an operator responsibility (`O_APPEND`, WORM, object
lock). No task in this wave may produce a doc or output string claiming
more. Do not ship a verifier over `Slf4jAuditSink` output — log
infrastructure reorders, compresses and ships lines outside this
application's control.

Two live bugs the audit spike found in existing code, now owned by 63 and
67 respectively:
- `AuditRecorder.java:59-60` advances `previousHash` before `sink.record(event)`
  succeeds, so a throwing sink leaves the in-memory chain head past an event
  never durably written; the next successful write chains against a hash for
  a record that does not exist. Harmless while the only sink is SLF4J; a
  real corruption path the moment a durable sink exists.
- `DataPrismProperties.java:167-170` validates and accepts `approved-sink`
  and `hash-chained` with no bean behind either, so an operator configuring
  `sink: hash-chained` passes config validation and only hits
  `MISSING_AUDIT_SINK` at a later startup phase — accidental fail-closed via
  a missing bean, not designed fail-closed.
- Also worth recording: no call site wraps `audit.record(...)`, so a sink
  exception aborts the response — accidentally fail-closed today, matching
  rule 2; task 63 turns that into an explicit tested guarantee rather than
  leaving it accidental. Catch-and-continue would be the actual rule 2
  violation, and must not be introduced while fixing the ordering bug.

**Owner decision, recorded 2026-09-23: the audit trail's threat model is a
user, an operator, AND any LLMs.** Recorded in `docs/architecture.md`'s
decisions list (grep 2026-09-23) with the rejected alternative (an HMAC-keyed
chain); this entry carries the consequences, which change what this wave
does and does not deliver:
- The operator is in scope, and the design as built does not resist one.
  `AuditEventHash.compute` is unkeyed SHA-256, so anyone who can write the
  audit file can delete or alter a record and recompute every hash after it
  into a chain that verifies perfectly — demonstrated by injecting a
  fabricated writer with a self-computed `eventHash`. Closing it needs
  external checkpointing or asymmetric signing with the key held outside the
  writing process; neither is a v0.3.0 increment. Not scheduled; the owner
  has not yet said when.
- Task 66's verifier (merged 2026-09-23) prints its truncation-cannot-be-
  detected disclaimer on every run; that disclaimer is load-bearing against
  a stated requirement, not a nice-to-have caveat. Task 72 (merged
  2026-09-23) touched this file and did not weaken it — the disclaimer's
  wording is unchanged; only the field-count and the backdating claim it
  makes were corrected.
- Tasks 62 and 59 write the audit-facing documentation. Neither may state or
  imply that `hash-chained` resists an operator with write access to the
  audit file, in addition to the existing path-disclosure precondition
  (follow-up item 2 above). This still holds after task 72: the operator gap
  above is unchanged by it.
- Timestamp integrity was forensically central, not cosmetic: tracing when an
  exposure happened depends on a timestamp that cannot be rewritten without
  recomputing a hash. `AuditEventHash` used to exclude `timestamp` and
  `sourceSystems` from the joined body it hashes; task 72 (merged 2026-09-23)
  closed that, widening the join to nineteen fields. This item is done.
- Open question, not yet decided by the owner: the trail records
  `subjectPseudonym`, `parameterFingerprint`, `tool`, `sourceSystems` and the
  policy decision — enough to reconstruct what would have been returned,
  deterministically, without storing the payload. That ties tracing a
  historical exposure to the pseudonymisation being reproducible, which
  depends on the HMAC key and `PseudonymisationVersion` staying stable — a
  key rotation or version bump could break the ability to trace an old
  exposure. Confirm with the owner whether that coupling is intended.

**Owner decision, recorded 2026-09-22: pseudonym discriminator widens from 20
to 40 bits; `PseudonymisationVersion.version` stays at v1.** Task 71
(`docs/plan/tasks/71-widen-pseudonym-discriminator.md`) widens the
pseudonymisation discriminator from 20 bits to 40, because at 20 bits two
subjects in one scope collide with 50% probability at roughly 1,205 subjects
for the namespaces where the discriminator tag is the whole rendered
pseudonym (`EMAIL`, `NONE`, and the default branch), and at roughly 400
subjects for `ADDRESS`, which today carries no tag at all — `ADDRESS` gains
one as part of this change. `NONE` is the scope-local subject token audit
records in place of the real identifier, so a collision there means two
different subjects share one audit identity. Widening the discriminator
changes every namespace's rendered pseudonym, so both golden-vector files —
`data-prism-pseudonymisation/src/test/resources/golden-vectors-v1.tsv` and
`golden-vectors-western-v2.tsv` — are regenerated as part of this task, which
is why this entry exists: `docs/conventions.md`'s determinism-test rule
requires either bumping `PseudonymisationVersion.version` or a recorded
decision to hold it, cited by the diff that edits the vectors.
`PseudonymisationVersion.version` deliberately stays at `v1` rather than
bumping to `v2` — the owner's reasoning is that nothing durable depends on v1
output yet, so a version bump would buy nothing a wider discriminator does
not already deliver. The explicit cost, stated per the rule: any pseudonym
issued before this change will not reproduce afterwards. Task 71's own commit
body must cite this entry rather than restate the reasoning.

Two smaller items the planner surfaced reviewing task 71, neither blocking it:
- Task 71's new `PseudonymisationVersion` compact-constructor check rejects
  an algorithm value a consumer of the published `data-prism-core` record
  could previously construct successfully. That is a breaking change to a
  public API arriving in 0.3.0 and wants a `CHANGELOG.md` line; task 70 owns
  `CHANGELOG.md` and should carry it when it cuts the version.
- Task 75 changes `AuditRecorder`'s `instanceId` shape from the configured
  writer-id verbatim to `<writer-id>/<per-boot random UUID>`, and adds the
  `INVALID_AUDIT_WRITER` refusal code for a blank or `/`-containing
  writer-id. Both are operator-visible (`AuditChainVerifierCli` output
  now groups by the suffixed id; the config validation error surface
  gains a code) and belong in task 70's 0.3.0 `CHANGELOG.md` line.
- Task 71's collision test runs the generator roughly 80,000 times, with the
  subject count as its tuning knob. Below roughly 5,000 subjects the mutation
  proof stops being decisive for the 2^20 case — worth knowing before anyone
  is tempted to shrink the loop count for speed.

**Waves, in order:**
- **Wave 1 — no cross-dependencies:** 60 (nested JSON catalogues), 63 (audit
  chain write ordering), 64 (file audit sink), 68 (bean classification
  escape hatch — closes the task-53-review item above: the
  `dataPrismPassThroughIdentityResolver` row plus the sweep widened to
  nested/imported configs, and the `DataPrismAutoConfiguration:117-126`
  javadoc correction), 71 (widen the pseudonym discriminator from 20 to 40
  bits, per the owner decision recorded above).

  **Wave 1 is complete — 60, 63, 64, 68 all merged.** 63, 64, 68 merged
  locally 2026-09-22; task 64's task file was retired with its attempt-1/
  attempt-2 failure records mined into `docs/plan/HISTORY.md` — grep `v0.3.0
  wave 1` — before deletion. **60 merged 2026-09-22 at attempt 3** (fixed a
  fail-open on a scalar arriving where the catalogue declared `nested:`,
  then a non-deterministic slot ordinal assigned from `Map.copyOf` iteration
  order, now a pure function of the sorted catalogue-name set) — its task
  file was retired with both failed attempts mined into
  `docs/plan/HISTORY.md`, grep `Task 60`. Post-merge full-reactor
  `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS, 19 modules,
  1018 tests, 0 failures, 0 errors.

  **Task 71 (widen the pseudonym discriminator) merged 2026-09-23, PASS +
  APPROVE, onto `v0.3.0/audit-trail-and-nested-json`.** Its task file was
  retired; see `docs/plan/HISTORY.md`, grep `Task 71`.

- **Wave 2 — depends on wave 1, now unblocked:** 61 (nested JSON through the
  real MCP HTTP/SSE transport, deps 60 — unblocked now that 60 has merged),
  65 (file sink PII scan, deps 64 — unblocked), 66 (audit chain verifier CLI,
  deps 64 — unblocked, and see the "four tampering-shaped failure modes"
  note above and in `docs/plan/HISTORY.md`'s wave-1 entry before starting),
  67 (wire `hash-chained` to `FileAuditSink`, deps 64 and 68 — unblocked, and
  see follow-up item 6 below on the path-disclosure fix that belongs where
  the sink exception maps to an MCP response, not in the sink).

  **61 and 65 merged 2026-09-22, both PASS + APPROVE.** 61's task file was
  retired; 65 took two attempts (see `docs/plan/HISTORY.md`, grep `v0.3.0
  wave 2`, for both). **66 merged 2026-09-23 at attempt 4, PASS + APPROVE** —
  three earlier attempts each relocated the same defect (the tool asserting
  benignity it could not support); task file retired, mined into
  `docs/plan/HISTORY.md`, grep `Task 66`. **67 was PASS but REQUEST CHANGES
  for bookkeeping, not code** — its branch and worktree were held pending
  tasks 73 and 74 (the approved-sink refusal, and the sink-exception-to-MCP-
  response path disclosure) being filed and closed.

  **Tasks 73 and 74 both merged 2026-09-23, PASS + APPROVE, onto
  `v0.3.0/audit-trail-and-nested-json`.** Task 73 raises a new
  `AUDIT_SINK_BEAN_REQUIRED` code, naming
  `dataprism.audit.sink=approved-sink` and the required bean, when that
  specific configured value has no `AuditSink` bean at
  `dataPrismContractValidator` construction; `MISSING_AUDIT_SINK` stays for
  every other case reaching the same branch, deliberately not narrowed to
  assume `approved-sink` is the only value that can, since `hash-chained`
  reaches it too until 67 merges. Task 74 stops a throwing `AuditSink`'s raw
  exception message reaching the MCP client: `GetEntityContextTool` and
  `CompareEntitySourcesTool` now catch only the `audit.record(...)` failure
  and rethrow `AuditUnavailableException` (code `AUDIT_UNAVAILABLE`), with
  the caught exception attached via `addSuppressed` rather than as a cause —
  proven by mutation that restoring a normal cause lets the MCP SDK's
  `aggregateExceptionMessages` walk it back onto the wire, disclosing a real
  `FileAuditSink` path. See `docs/plan/HISTORY.md`, grep `Task 73` and
  `Task 74`.

  **Task 67 (wire `dataprism.audit.sink=hash-chained` to `FileAuditSink`)
  merged 2026-09-23, PASS + APPROVE on attempt 2, onto
  `v0.3.0/audit-trail-and-nested-json`.** Attempt 1 was rejected on review
  not for broken wiring — the wiring was correct throughout — but because
  two claims it carried went false while it sat verified against base
  `36ee57d`, which five tasks (66, 71, 72, 73, 74) then merged beneath:
  a javadoc claim of a client-visible leak task 74 had already closed, and
  a hand-rolled hash-chain replay task 66's now-merged `AuditChainVerifier`
  made unnecessary. Attempt 2 corrected both and is the version that
  merged, squashed into one commit so history does not carry the corrected
  commit's false claims. Task file retired; see `docs/plan/HISTORY.md`,
  grep `Task 67`.

  **The general lesson: a PASS expires when its base does.** Task 67 sat
  verified against a base that moved five merges before re-verification,
  and two of its claims did not survive the move. **Task 55 was in the same
  position and was handled the same way**: re-verified (PASS + APPROVE
  again) against the base it actually merged onto, not merged on the trust
  of an older verification, then merged 2026-09-23 onto
  `v0.3.0/audit-trail-and-nested-json`. See above and `docs/plan/HISTORY.md`,
  grep `Task 55`.

  **Task 72 (widen the audit hash to cover `timestamp` and `sourceSystems`)
  merged 2026-09-23, PASS + APPROVE, fast-forward onto
  `v0.3.0/audit-trail-and-nested-json` at `43badb8`.** It landed ahead of 67 as
  required — no durable chain existed yet to invalidate, since 67 (which wires
  `hash-chained` to `FileAuditSink`) had not merged. That ordering constraint
  is now satisfied; 67 may merge without invalidating anything 72 wrote. Task
  file retired; see `docs/plan/HISTORY.md`, grep `Task 72`.

  Task 60's durable caveats, load-bearing for 62 and 69: the fail-open is
  fenced by `ConfiguredJsonNestedLeafShapeGuard` running before
  `engine.scrub`, not structurally removed — any future path that hands a
  `ConfiguredJsonFieldMetadataResolver` to `JsonTreeScrubbingEngine` without
  calling the guard first reopens it; ordinals are per-source and assigned
  over sorted catalogue names, so task 69 must read names off
  `nestedCatalogues()` and never re-derive slots; core's `UNKNOWN_FIELD`
  message interpolates the slot class name
  (`ConfiguredJsonNestedCatalogueSlot0`), not the operator's catalogue name —
  task 62 owns documenting the slot-to-catalogue mapping.
- **Wave 3 — depends on waves 1-2. 69, 62 and 59 all merged; nothing remains
  in this wave.** Follow-up item 8 below (the `docs/configuration.md` gap for
  `AUDIT_SINK_BEAN_REQUIRED`, `AUDIT_SINK_FILE_UNUSABLE`,
  `dataprism.audit.file-path`, and task 75's writer-id shape and
  `INVALID_AUDIT_WRITER`) is resolved by task 59; see below.

  **Task 62 merged 2026-09-23, PASS + APPROVE on attempt 9, onto
  `v0.3.0/audit-trail-and-nested-json`.** Its attempt-5 reviewer found a real
  code defect while reading `docs/audit.md`, filed and closed as task 75
  (merged first); 62's attempts 6-9 documented the post-75 behaviour, never
  the bug 75 fixed. Task file retired; see `docs/plan/HISTORY.md`, grep
  `Task 62`, for what landed and the nine-attempt cost, including the
  lesson that convergence needed testers comparing quoted output BY TEXT
  against real runs, not by shape.

  **Task 69 (restore the reviewed-adapter allow-list) merged 2026-09-23,
  PASS + APPROVE on attempt 2, onto `v0.3.0/audit-trail-and-nested-json`,
  squashed into one commit.** `DataPrismContractValidator` now refuses, with
  the stable code `UNREVIEWED_SOURCE_ADAPTER`, any `DataSourceAdapter` bean
  named by neither `dataprism.sources` nor the JSON catalogue's own source
  names — restoring the allow-list property task 54 lost, not
  guarantee-preserving. Attempt 1 (rejected) wired the catalogue's names in
  via `ObjectProvider<ConfiguredJsonSourceNames>`, a connector-owned record
  type, in unconditional `@Bean` signatures backed by a new optional Maven
  dependency from autoconfigure to connectors-rest; that crashed the base
  standalone server (connectors-rest is test-scope only there) because
  Spring resolves every `@Bean` parameter type via `Class.forName` before
  `ObjectProvider.getIfAvailable()` runs — `<optional>true</optional>` cannot
  help, the type is compiled into the class file. Attempt 2 publishes the
  names as a plain `Set<String>` bean (`dataPrismConfiguredJsonSourceNames`),
  consumed via `ObjectProvider` with `@Qualifier`; `java.util.Set` is
  JDK-resolvable regardless of what connector is present, so autoconfigure
  keeps no compile edge to the connector at all. See `docs/plan/HISTORY.md`,
  grep `Task 69`, for the collision-safety proof and the mvn-verify-only
  blind spot this is the second instance of this task cycle. The unreachable
  `MISSING_AUDIT_SINK` arm (dead since task 67) was kept as a defensive guard
  with an accurate comment — that decision stands, nothing further owed.
- **Wave 4: task 70 merged 2026-09-23, PASS + APPROVE on attempt 4, onto
  `v0.3.0/audit-trail-and-nested-json`.** Cut 0.3.0 across the reactor —
  pom.xml and every module pom, `server.json` (plus the previously-missing
  `DATAPRISM_AUDIT_FILE_PATH` environment variable), both `serverInfo`
  literals, the distribution Dockerfile, `publish-image.yml`'s dispatch
  default, three PackagingIT/SmokeIT jar-name literals, and every remaining
  `0.2.0` literal in README/doc text — plus a `CHANGELOG.md` `[0.3.0]` entry
  written from the merged diffs of tasks 60-69, not from task-file intentions,
  naming the breaking changes with what a consumer must do: task 71's widened
  pseudonym discriminator (40-bit/24-byte-minimum MAC — anything using
  HmacMD5/HmacSHA1 must move algorithms, and pseudonyms stored or compared
  under 0.2.0 will no longer match), task 73's `AUDIT_SINK_BEAN_REQUIRED` code
  replacing `MISSING_AUDIT_SINK` for `approved-sink` with no bean, task 75's
  `instanceId` shape change and new `INVALID_AUDIT_WRITER` code, and task 69's
  `UNRESOLVED_SOURCE_ADAPTER` → `UNREVIEWED_SOURCE_ADAPTER` rename. Post-merge
  full-reactor `mvn -B --no-transfer-progress clean verify`: BUILD SUCCESS, 19
  modules, 646 tests (counted from the per-module surefire/failsafe `Results:
  Tests run:` lines, not by summing `target/*-reports/*.txt`, which
  undercounts `MultiKeySecretKeyProviderTest`'s `@Nested` classes), 0
  failures, 0 errors, 0 skipped. Task file retired; see
  `docs/plan/HISTORY.md`, grep `Task 70`, for the four-attempt cost.
  **This merges 70 onto the integration branch only — it does not put 0.3.0
  on `main`.** See the release sequence below: step 2 is now half done.

**Risks flagged by the planner, both open:**
- The per-nested-catalogue `Class` token task 60 introduces is the only
  unproven mechanism in the plan. If no route keeps `core` unchanged, 60
  stops and reports rather than widening core's SPI.
- ~~Task 69 sits behind two dependency edges on one file (60, then 67); a
  slip in 67 delays the allow-list fix, not the release, since 69 is wave 3
  and 70 waits on both.~~ Both 60 and 67 merged 2026-09-23; task 69 is
  unblocked. No slip occurred.

**Follow-ups filed from wave 2 (61, 65, 66, 67), 2026-09-22. Items 1 and 2 are
resolved below (tasks 73 and 74, merged 2026-09-23).**

~~1. **Blocks recording task 67 done.** `approved-sink` is still accepted by
   `DataPrismProperties.validate()` with no bean behind it, refusing later
   via `MISSING_AUDIT_SINK` at contract-validator construction instead of at
   config validation. ... File it as its own task owning those files.~~
   **Resolved 2026-09-23 by task 73.** `DataPrismContractValidator` now
   raises `AUDIT_SINK_BEAN_REQUIRED`, whose message names
   `dataprism.audit.sink=approved-sink` and the required bean type, for
   exactly that value with no bean; `MISSING_AUDIT_SINK` is retained for the
   absent-or-blank-property case and for any other accepted value reaching
   the bean-absent branch (still `hash-chained`, until 67 merges). All eight
   named fixtures still configure `approved-sink` and still exercise the
   path they were written for. See `docs/plan/HISTORY.md`, grep `Task 73`.
2. ~~**Hard precondition on tasks 62 and 59.** The sink-exception-to-MCP-
   response path disclosure: `FileAuditSink`'s `PoisonedException` names the
   file path by design, and `AuditSinkFailureAbortsResponseTest` pins a
   sink's raw exception message reaching the MCP client, so a server
   filesystem path can now reach a client. ... on condition that this
   is filed before either 62 or 59 tells an operator to use `hash-chained`.~~
   **Resolved 2026-09-23 by task 74.** `GetEntityContextTool` and
   `CompareEntitySourcesTool` catch only the `audit.record(...)` call sites
   and rethrow `AuditUnavailableException` (code `AUDIT_UNAVAILABLE`, no
   text derived from the caught exception), with the cause kept only via
   `addSuppressed` for the server-side log — proven by mutation that a plain
   `initCause`/constructor-cause wiring lets the MCP SDK's
   `aggregateExceptionMessages` disclose the real `FileAuditSink` path
   through the client-visible `McpError` `data` field. The abort itself is
   unchanged: every new `catch` logs and rethrows, none returns a result.
   `docs/conventions.md` records the resulting `LOG.error(msg, cause)` calls
   as a deliberate, reviewed exception to its own "no catch block logs the
   object it caught" rule. See `docs/plan/HISTORY.md`, grep `Task 74`.
3. The two PII scans have already drifted. `PiiLogScanTest` matches banned
   values at token boundaries (lookaround-bounded since task 47, so bare ids
   `123`/`456` cannot collide with hex); `AuditFilePiiScanTest`'s copy uses
   plain `String.contains`. Stricter today so it cannot miss a leak, and the
   derivations are byte-identical — but two definitions of "what counts as a
   leak" will drift further. Share one derivation and one matcher; needs
   `PiiLogScanTest`, which task 65 did not own.
4. `AuditFilePiiScanTest`'s `leaksIn` dispatches `instanceof String` /
   `instanceof Collection<?>` / else-throw, so a `null`-valued `String`
   component falls to the else branch and NPEs on `value.getClass()` rather
   than being skipped. Found independently by both 65's reviewer and
   tester. Latent — production uses `""` sentinels — but `AuditRecordFormat`
   round-trips nulls by design, so the path exists.
5. `ConfiguredJsonNestedHttpTest` (task 61) leaves a non-daemon thread
   (likely its per-test `java.net.http.HttpClient`), so
   `data-prism-integration-tests` now ends with surefire's "going to kill
   self fork JVM ... 30 seconds after System.exit(0)" — about 30 seconds and
   an ERROR line added to every build. A baseline run on `main` does not
   produce it. Reusing or closing one client fixes it.
6. `data-prism-integration-tests/pom.xml` (task 61) introduces a second TLS-
   password env var with the same literal as the existing
   `DATA_PRISM_TEST_TLS_PASSWORD`; reuse the existing name.
7. Two platform constraints worth recording for future tasks, both
   confirmed from source by 61's tester: `dataprism.transport.fixture-
   development` is refused unless `transport.mode=stdio`
   (`DataPrismProperties:123`), so an HTTP-mode configured JSON source must
   use a real TLS upstream; and `dataprism.json-sources.config-location`
   must be passed as a JVM system property, not a `--` command-line arg,
   because `DataPrismProperties` binds `ignoreUnknownFields=false` and
   Spring Boot's unbound-elements check exempts system properties but not
   command-line args — which is also how the packaged distribution wires it.
8. ~~**Owed to tasks 59/62, filed 2026-09-23 alongside task 73, extended
   2026-09-23 alongside task 67.** `DataPrismConfigurationFailureAnalyzer`
   prints the refusal code and points an operator at
   `docs/configuration.md`.~~ **Resolved 2026-09-23 by task 59.**
   `docs/configuration.md` now documents `AUDIT_SINK_BEAN_REQUIRED`,
   `AUDIT_SINK_FILE_UNUSABLE`, `dataprism.audit.file-path`, and (from task 75)
   that a writer-id need not be unique per boot, must not contain `/`, and
   the `INVALID_AUDIT_WRITER` refusal code alongside `MISSING_AUDIT_WRITER`.
   See `docs/plan/HISTORY.md`, grep `Task 59`.
9. **Known, documented, currently non-firing race — not scheduled.**
   `AuditSinkFailureAbortsResponseTest`'s own javadoc records a JVM-wide
   default-`SSLContext` singleton race against `McpHttpEndToEndTest` (and
   `ConfiguredJsonNestedHttpTest`) when they share a surefire fork. Task 71's
   implementer reported it as a flake; task 71's tester ran
   `data-prism-integration-tests` three times plus both pairwise test
   orderings — five runs total — and could not reproduce it, consistent with
   the documented mitigation (each of these tests uses an explicit
   `SSLContext` rather than the implicit default) actually holding. Recording
   it here so that if it ever does fire, whoever sees it finds this note
   instead of rediscovering the race from scratch.
10. **Owed hardening from task 55's close-out, not scheduled.** The three
    quickstart Dockerfiles each `find` "the one repackaged jar" a module's
    `target` directory holds and copy it to a fixed name, with no
    match-count assertion. Zero matches still fails loudly at the
    subsequent `COPY` (the old ARG-pinned behaviour's failure mode is
    preserved), but two matches would silently copy whichever one `find`
    lists first — a wrong image built quietly, worse than the version
    literal it replaced. Unreachable today; reaching it requires someone
    deliberately adding a `-Prelease` profile or an attached-classifier
    execution to a quickstart module. A `set -eu` plus an explicit
    match-count check in each `RUN find ...` would convert that from
    silent-wrong to loud-fail. File as its own task if any quickstart
    module ever gains a second packaging execution; not worth one before
    then.
11. **Found by task 59, not scheduled.** In stdio fixture-development mode,
    `DataPrismProperties.validate()` is skipped, so a missing
    `dataprism.audit.writer-id` surfaces as a raw `NullPointerException` and a
    `/`-containing one as a raw `IllegalArgumentException` from
    `AuditRecorder`, and a missing sink bean as a raw
    `NoSuchBeanDefinitionException` — startup still refuses (fail-closed) but
    with no stable operator-facing code. Development-only mode; not a leak.
    Worth a task if fixture-development mode is ever treated as more than a
    developer convenience.
12. **Found by task 59, pre-existing text, not scheduled.** `docs/configuration.md`'s
    `dataprism.transport` vocabulary row names only `STDIO_TRANSPORT_UNSUPPORTED`
    for `mode: stdio`. Without `fixture-development=true` that mode instead
    refuses with `STDIO_DEVELOPMENT_ONLY` first; the row does not distinguish
    the two forms.
13. **Found by task 59, not scheduled.** `examples/quickstart-demo/mcp-handshake.sh`
    still scrapes response headers with `grep | tr | cut` and parses the
    session token with a `python3 -c` one-liner — the same pattern task 56
    replaced in `run.sh`. Not in task 59's `Owns`.

**Release sequence — order is load-bearing, do not compress it:**
1. **Done.** Waves 1-4 (tasks 60-71) merged onto
   `v0.3.0/audit-trail-and-nested-json`.
2. **Done, 2026-09-23 (PR #96, `543defb`).** The integration branch —
   carrying 70's 0.3.0 cut and 55's quickstart-image workflow/compose
   changes together — merged to `main`. `main` is now at 0.3.0 but the
   `v0.3.0` tag has not been pushed, so no image or artifact has actually
   published yet: `docker compose up` on `main` still pulls images that do
   not exist on `ghcr.io`.
3. **Done, 2026-09-23, out of the originally-planned order.** The owner
   chose to run task 59 (quickstart exit ramp and reference docs) *before*
   tagging rather than after publish, verifying the published-image doc text
   against the from-source Compose build instead of a real pull, since no
   image is published yet. Task 59 merged `--no-ff` onto a new local branch,
   `v0.3.0/quickstart-exit-ramp`, cut from `main` at the step-2 merge — not
   yet pushed or opened as a PR. See `docs/plan/HISTORY.md`, grep `Task 59`.
   **Remaining owner steps, in order:**
   a. Open a PR from `v0.3.0/quickstart-exit-ramp` into `main` and merge it.
   b. Tag `v0.3.0` on `main` and push it. This triggers `release.yml`, which
      creates the public GitHub Release. `publish-image.yml`,
      `publish-central.yml` and `publish-mcp.yml` only build/validate on the
      tag push; none of them publishes anything until dispatched.
   c. Dispatch `publish-image.yml` on `v0.3.0`, publishing the distribution
      image and all four quickstart images to `ghcr.io`.
   d. Dispatch `publish-central.yml` (requires approval in the `central`
      GitHub environment) for the Maven Central publish.
   e. Dispatch `publish-mcp.yml` for the MCP registry publish; it refuses
      until the GHCR image from step (c) is actually pullable.

**No open tasks remain after this.** No task file remains under
`docs/plan/tasks/`; everything left is the owner steps listed above.

### Discoverability (tasks 76-84) — done

Design: `docs/plan/specs/2026-09-23-discoverability.md` (binding — read
"Decisions" and the per-task "T*" sections before touching any of these).
Makes Data Prism findable by search engines and citable by AI assistants,
without publishing any claim the code does not back. Local branch
`discoverability` (not pushed, not `main`) is the integration branch every
task below merges onto; task worktrees are reset onto it, not `main`.

- **Task 77 — done.** `docs/faq.md` (seven questions: anonymity per Art.
  4(5), how pseudonyms are made and scoped, fixed identifier-shape scanning
  rather than general PII/name detection, YAML path vs Java, what the audit
  trail proves and does not prove, prompt injection flagged but not
  defended against, not production-ready pre-1.0) and `docs/comparison.md`
  (Presidio, LLM Guard, NeMo Guardrails, Docker MCP Gateway; "different
  layers" table, "use X instead when", "where Data Prism does not fit";
  every claim about another tool sourced to that tool's own docs with an
  access date). Merged `--no-ff` onto `discoverability`. See
  `docs/plan/HISTORY.md` — grep `Task 77`.
- **Task 79 — done.** Reproducible social card (`docs/assets/social-card.png`,
  1280x640, tagline T, regenerated from `docs-site/social-card/`). Merged
  `--no-ff` onto `discoverability`. See `docs/plan/HISTORY.md` — grep
  `Task 79`.
- **Task 80 — done.** Discoverability measurement: 15-question assistant
  check, run template, `snapshot.sh` (bash+gh+jq, GET-only, fixed repo
  target, refuses to clobber a same-day snapshot), and the 2026-09-23
  baseline (`docs/plan/discoverability/`). Merged `--no-ff` onto
  `discoverability`. See `docs/plan/HISTORY.md` — grep `Task 80`.
- **Task 78 — done.** Three use-case pages under `docs/use-cases/`
  (pseudonymise customer data for an LLM agent in Spring Boot, GDPR data
  minimisation for MCP tools, consistent pseudonyms across systems), each
  routing into the existing quickstart/reference docs, GDPR citations linked
  to EUR-Lex 32016R0679 anchors. Merged `--no-ff` onto `discoverability`.
  See `docs/plan/HISTORY.md` — grep `Task 78`.
- **Task 76 — done.** Canonical tagline T and description D applied to the
  README, root `pom.xml`, `server.json`, `docker/distribution/Dockerfile`'s
  OCI labels, `docker/server/Dockerfile` and a new `CITATION.cff`; README
  Status paragraph corrected (the hash-chained sink and verifier are built);
  `docs/extending.md`'s README line-number citations replaced by section
  names. Merged `--no-ff` onto `discoverability`. See `docs/plan/HISTORY.md`
  — grep `Task 76`.
- **Task 81 — done.** Internal outreach drafts for the owner to post by hand
  (nothing submitted): `docs/plan/outreach/awesome-mcp-servers.md` (ready),
  `docs/plan/outreach/awesome-java.md` (ready), `docs/plan/outreach/awesome-spring.md`
  (hold — that list's MCP-server subsection is Spring-AI-built servers only,
  and Data Prism has no Spring AI dependency), `docs/plan/outreach/awesome-llm-security.md`
  (hold — scope), `docs/plan/outreach/launch-post.md` (angle, every claim
  linked, Limits section, Show HN/r/java titles, pre-post checklist), and a
  `README.md` status table. Merged `--no-ff` onto `discoverability`. See
  `docs/plan/HISTORY.md` — grep `Task 81`.
- **Task 82 — done.** MkDocs Material docs site (mkdocs 1.6.1, material
  9.7.7, include-markdown 7.3.0, llmstxt 0.5.0, all pinned) built from the
  existing docs as the single source — site_url
  `https://aindriub.github.io/data-prism/`; 17 nav pages; home page includes
  the README intro between the site-intro markers, changelog page includes
  `CHANGELOG.md`; internal docs excluded (`plan/`, `adr/`, `pack.md`,
  conventions, workflow, development-plan, design-review); per-page
  title/description from `docs-site/page-meta.yml` for existing docs (no
  front matter added to them); OG/Twitter tags and the social card; JSON-LD
  `SoftwareSourceCode` on the home page only, no version; `llms.txt` and
  `llms-full.txt`; no analytics, `theme.font: false`.
  `docs-site/hooks/site.py` rewrites links leaving `docs/` to GitHub
  blob/tree URLs (raw for images) and fails the build if the target does not
  exist; `check_site.py` verifies excluded paths, sitemap vs the real nav,
  meta/canonical/social tags, JSON-LD, no fonts/analytics, no `robots.txt`,
  and the llms files. `.github/workflows/pages.yml` builds strict on PRs and
  `main`, runs `check_site`, `lychee --offline` and the guards; deploys only
  on push to `main` with `pages:write`/`id-token:write` in the
  `github-pages` environment. Merged `--no-ff` onto `discoverability`. See
  `docs/plan/HISTORY.md` — grep `Task 82`.
- **Task 84 — done.** Corrected `docs/configuration.md:73`'s two false
  startup-refusal claims ("a production profile that relaxes fail-closed
  behaviour", "a profile that lacks a rule required by exposed models"): no
  `dataprism.*` property loads a custom profile file, so only the bundled
  `privacy-profiles-default.yaml` loads today, whose `DEFAULT` and `STRICT`
  profiles both set `unclassified: FAIL_REQUEST`, refusing the whole response
  for a field nobody classified; the real refusal it names instead is an
  application `PrivacyPolicyResolver` bean (`FORBIDDEN_PRIVACY_OVERRIDE`).
  Found while tracing task 81's launch-post claims back to code. Merged
  `--no-ff` onto `discoverability`, then a separate close-out commit. See
  `docs/plan/HISTORY.md` — grep `Task 84`.
- **Task 83 — done. All of tasks 76-84 are done; the discoverability plan is
  done.** Wires README and `server.json` to the now-live
  `https://aindriub.github.io/data-prism/` — site live since PR #98 merged
  `discoverability` → `main` on 2026-09-24, Pages `build_type=workflow`, HTTPS
  enforced. README's "## Documentation" splits into "User docs" (every page
  the site publishes — 16 doc pages plus the site root — each linking the
  live site URL and the repo file it is built from) and "Internal / project
  working docs" (not published). `server.json` gains `.websiteUrl`, effective
  at the next registry publish. Two attempts: attempt 1 reviewer APPROVE but
  tester FAIL (missed the changelog and both agent-transport pages); attempt
  2 added them, and the README's site-URL set was confirmed to equal the live
  sitemap's 17-page set exactly. PASS + APPROVE on attempt 2. Closed onto a
  new local `go-live-wiring` branch cut from `main` (not pushed, `main`
  itself untouched), carrying the `--no-ff` merge of `task/83-go-live-wiring`
  plus a close-out commit — since `discoverability` reached `main` before
  this task closed, its own base branch instruction (merge onto
  `discoverability`) no longer applied. Reaching `main` is a separate,
  still-pending step. See `docs/plan/HISTORY.md` — grep `Task 83`.

With 83 closed, no discoverability task remains open. What is left is owner
action, not a task:

- **Owner, after go-live:**
  - ~~`gh repo edit AindriuB/data-prism --description "<D>" --homepage https://aindriub.github.io/data-prism/`
    and add the 11 topics
    (`model-context-protocol pseudonymization pii gdpr data-privacy
    data-minimization llm llm-security ai-agents audit-log hmac`) to the
    existing 6.~~ — done 2026-09-24: About description is the corrected D,
    homepage set, 17 topics present, verified with `gh repo view`. See
    `HISTORY.md`.
  - ~~Upload the social preview (`docs/assets/social-card.png`) in Settings →
    General.~~ — done 2026-09-24: a custom social preview image is set.
  - ~~Verify the repo in Google Search Console and Bing Webmaster Tools, then
    submit `sitemap.xml` to both. Bing feeds Copilot and ChatGPT search.~~ —
    done 2026-09-24: Search Console verified as a URL-prefix property for
    `https://aindriub.github.io/data-prism/` using the meta tag PR #103 added
    (`docs-site/overrides/main.html`, home page only), `sitemap.xml`
    submitted; Bing Webmaster Tools imported from Search Console. Verified
    with `gh repo view` and curl. See `HISTORY.md`.
  - Post each `docs/plan/outreach/*.md` draft by hand, one at a time, only
    where that list's own rules are met (`awesome-spring.md` and
    `awesome-llm-security.md` are marked hold, not ready).
  - ~~Run a `snapshot.sh` run within 14 days of the 2026-09-23 baseline — a
    missed 14-day window loses that period's traffic data permanently.~~ —
    done 2026-09-24: first scheduled snapshot taken
    (`docs/plan/discoverability/snapshots/2026-09-24.json`). Next snapshot
    due by 2026-10-08. See `HISTORY.md`.
  - Run the first monthly 15-question assistant check
    (`docs/plan/discoverability/questions.md` /
    `docs/plan/discoverability/runs/TEMPLATE.md`) — still open.
- ~~Open question for the owner: description D said unclassified data is
  "redacted or refused", but the shipped profiles' `unclassified:
  FAIL_REQUEST` only refuses~~ — resolved 2026-09-24 by owner decision:
  corrected D to "refuses anything unclassified" everywhere it appeared
  (`README.md`, `pom.xml`, `CITATION.cff`, `CITATION.cff`'s abstract, the
  mkdocs `site_description`/`llms.txt`, `.github/workflows/pages.yml`'s D
  check, the discoverability spec's own D definition, and the outreach
  drafts under `docs/plan/outreach/`), plus the two use-case pages'
  "redacted or the call is refused" phrasing
  (`docs/use-cases/gdpr-data-minimisation-mcp.md`,
  `docs/use-cases/pseudonymise-customer-data-spring-boot.md`) — see
  `HISTORY.md`. The GitHub About text (`gh repo edit`) still needs the owner
  to paste the corrected description by hand.

### Site polish and developer guide (tasks 85-90) — done, merged to `main` via PR #101 on 2026-09-24; site redeployed.

Design: `docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md`
(binding — read "Decisions", "Facts the planner must respect" and the
per-section A-D detail before touching any of these). Restrained visual
polish, a collapsible changelog view, embedded diagrams and a real developer
guide with tutorials built from compiled, tested source — none of it
rewriting `CHANGELOG.md` or claiming more than the code does. Local branch
`site-polish` (cut from `main` at `fc5cc58`, not pushed) is the integration
branch every task below merges onto; task worktrees are reset onto it, not
`main`. **`site-polish` must not reach `main` before task 90 closes**, since
it now carries three stub developer-guide pages; the merge-only
`check_no_stub_pages.py` guard (`DP_REQUIRE_NO_STUBS`) enforces that in CI.

- **Task 85 — done.** Laid the shared plumbing every wave-1 task needs, so
  they land in `mkdocs.yml`, the guard scripts and the CI workflow without
  colliding: `attr_list`, `md_in_html` and `pymdownx.snippets` (with
  `check_paths: true`, `base_path: [data-prism-quickstart-extension, docker]`,
  `restrict_base_path: true`, `dedent_subsections: true`) added to the four
  existing markdown extensions, and `pymdown-extensions` pinned to the exact
  version the existing pins already resolve; a new "Developer guide" nav
  section (three stub pages, task 89/90 to replace two of them) placed before
  "Reference", mirrored in `llmstxt.sections`; `docs-site/hooks/changelog.py`,
  a no-op `on_page_markdown` stub task 87 owns; a third-party-script guard in
  `check_site.py` that allows exactly two `unpkg.com` strings and only inside
  Material's own `bundle.*.min.js`/`.map`, and fails on everything else
  (off-origin `<script src>`/`<link href>`/CSS `@import`/`url(`, any
  `class="mermaid"`); `pages.yml` running every `docs-site/hooks/check_*.py`
  in sorted order instead of one hardcoded step; a merge-only stub-page guard
  (`check_no_stub_pages.py`, gated on `DP_REQUIRE_NO_STUBS`, true only for a
  PR into `main` or a push to `main`, so wave branches keep building with the
  stubs in place); and a new CONTRIBUTING "## Docs site" section documenting
  the `check_*.py` convention, snippet sourcing, and the Docker build recipe.
  Merged onto `site-polish`, PASS + APPROVE on attempt 4 — see
  `docs/plan/HISTORY.md`, grep `Task 85`, for what the first three attempts'
  reviews closed and the two lessons worth carrying into any future guard
  task. Task file retired.
- **Task 86 — done.** Gave the docs site an identity: the owner's prism mark
  as the header logo (byte-identical to the supplied `mark-dark.svg`,
  original files kept unchanged under `docs-site/logo/supplied/`), a
  script-derived favicon (SVG with a `prefers-color-scheme` switch, plus a
  32px PNG), a dark-slate `#1e293b` header in both schemes with one indigo
  accent in two AA-tuned shades (`#4f46e5` light, `#818cf8` dark),
  `check_contrast.py` covering text, links, header, logo and both hero
  buttons in both schemes, a landing hero (tagline T, Quickstart and
  Developer guide buttons, three cards) and an `extra.css` covering only
  palette, tables, code, cards and buttons. Merged onto `site-polish`, PASS +
  APPROVE on attempt 2 — see `docs/plan/HISTORY.md`, grep `Task 86`, for what
  attempt 1's review closed and the two lessons worth carrying forward. Task
  file retired.
- **Task 88 — done.** Drew five concept diagrams (system overview, one
  `get_entity_context` call, pseudonym generation, fail-closed field
  decisions, the audit chain) and embedded each in the existing doc that
  covers it — `architecture.md`, `tools.md` (two), `configuration.md`,
  `audit.md` — insert-only, one intro sentence plus a linked image that
  opens full size. Mermaid source lives in `docs-site/diagrams/*.mmd`;
  `render.sh` renders it to committed SVGs in `docs/assets/diagrams/` with a
  digest-pinned mermaid-cli run `--network none` as the caller's uid, output
  byte-stable, each SVG padded with a thin border so it reads as a framed
  panel on dark pages. `check_diagrams.py` enforces `.mmd`/`.svg` pairing,
  references, alt text and no off-w3.org URLs/script/`@import`. No "learn"
  page, no runtime Mermaid. Merged onto local `site-polish`, PASS + APPROVE
  on attempt 2 — see `docs/plan/HISTORY.md`, grep `Task 88`, for what
  attempt 1's review closed and the two lessons worth carrying forward. Task
  file retired.
- **Task 89 — done.** Opened the developer guide: an overview page covering
  the extension points with GitHub-linked sources, and tutorial 1
  ("write a data-source adapter"), seven steps from model and annotations to
  a pseudonymised MCP response, every code/pom/YAML/Dockerfile block pulled
  via `--8<--` from marked regions in `data-prism-quickstart-extension` and
  `docker/`, never hand-copied. New `docs-site/hooks/check_snippet_markers.py`
  closes a gap `pymdownx.snippets` leaves open (a missing end marker is read
  silently to EOF, not failed). Merged onto local `site-polish`, PASS +
  APPROVE on attempt 2 — see `docs/plan/HISTORY.md`, grep `Task 89`, for what
  attempt 1's review closed and the lessons worth carrying forward. Task file
  retired. **Carry into task 90:** `write-an-adapter.md` currently implies (and
  task 90's own file said) the server "refuses to start without" an
  `IdentityResolver` bean; that's inaccurate — `dataprism.identity.resolver:
  pass-through` supplies one without code
  (`DataPrismAutoConfiguration.java:128-133`). Task 90's tutorial should
  correct this rather than repeat it.
- **Task 87 — done.** Replaced the `changelog.py` stub task 85 registered:
  each `CHANGELOG.md` release now renders as a `pymdownx.details` block,
  newest open and the rest closed, with an empty `[Unreleased]` dropped and
  a "version — date · N added · …" summary line (singular for a count of 1).
  `check_changelog.py` guards every release marker, `<summary>`, and that
  link-reference definitions stay at top level and resolve; `CHANGELOG.md`
  itself is untouched, and the only site side effect is `llms-full.txt`'s
  release headings. Merged onto `site-polish`, PASS + APPROVE on attempt 2 —
  see `docs/plan/HISTORY.md`, grep `Task 87`, for what attempt 1's review
  closed. Task file retired.
- **Task 90 — done.** Replaced the third and last stub page with tutorial 2,
  "write a custom identity resolver", backed by a tested example
  `MappedIdentityResolver` in `data-prism-quickstart-extension`
  (`ExampleIdentityMapping`, `ExampleIdentityResolverConfiguration`, and an
  `ExampleOrderedIdentityResolverAutoConfiguration` not registered in
  `AutoConfiguration.imports` and inert in the shipped jar), plus diagram 6
  (extension points) embedded in the developer-guide overview and the
  `write-an-adapter.md` `IdentityResolver` sentence task 89 flagged, now
  corrected. Every snippet is pulled from compiled, tested source. Merged onto
  local `site-polish`, PASS + APPROVE on attempt 4 — see `docs/plan/HISTORY.md`,
  grep `Task 90`, for what the first three attempts' reviews closed and the
  three lessons worth carrying forward. Task file retired.

With 90 closed, every task the site-polish plan named is done, and no stub
page remains under `docs/developer-guide/` — `DP_REQUIRE_NO_STUBS=true`
passes. ~~`site-polish` is ready for the owner-approved push and a pull
request into `main`; nothing has been pushed yet.~~ — merged to `main` via
PR #101 on 2026-09-24; the site redeployed. See `HISTORY.md`.

**Small follow-ups, found closing task 86 (not blocking):**
- The hero buttons' hover state in the slate scheme is white text on
  `#818cf8`, about 2.98:1 — below AA. `check_contrast.py` doesn't measure
  hover states, only the resting palette.
- `check_contrast.py` should fail on any unparsed `extra.css` selector that
  mentions `.md-button` or `data-md-color-scheme`, instead of silently
  falling back to defaults — it would have caught the attempt-1 invisible
  dark-mode button sooner.
- Cosmetic: Material's card `:hover` clears the accent top border set in
  `extra.css`.

**Open items for the owner, found closing task 87:**
- ~~`CHANGELOG.md`'s 0.3.0 `AuditChainVerifier` bullet says "an edit or
  deletion inside one writer's chain, caught even on that chain's own last
  record"~~ — corrected on `site-polish` (2026-09-24, see `HISTORY.md`): the
  0.3.0 "Added" and "Not changed" bullets now attach "caught even on the last
  record" to edits only, with deletion caught only when a later record
  follows it; `[Unreleased]` records the correction.
- ~~`docs/tools.md:120` says "unclassified values dropped"~~ — corrected on
  `site-polish` (2026-09-24, see `HISTORY.md`); the row now states an
  unclassified field refuses the whole response under the shipped profiles.

### 0.4.0 release checklist (owner go-ahead required)

Owner decision D-129(a): publish to Maven Central (library modules), GHCR and
the MCP Registry. `data-prism-server` stays off Central (`skipPublishing`) and
comes from source, a GitHub Release or GHCR. Each remaining
step is an outward action and needs the owner's go-ahead.

1. Done: pushed `claude/data-prism-eu-compliance-04cf83` and opened PR #112 to `main`.
2. Done: CI green, merged as `1e79904`.
3. Done: annotated tag `v0.4.0` pushed on the merge commit, matching `v0.3.1`. It is not signed.
4. In progress: the `release.yml` run for `v0.4.0`. When it finishes, create the GitHub Release for `v0.4.0` if the workflow has not.
5. Dispatch `publish-central`, then verify the library modules at 0.4.0 on Central (`data-prism-server` must not appear).
6. Dispatch `publish-image` with `-f version=0.4.0`, then verify the GHCR manifests for `data-prism-server` and the four `data-prism-quickstart-*` images.
7. Dispatch `publish-mcp`, then verify the registry lists 0.4.0 as latest.
8. After Central publishes, re-verify the `docs/extending.md` consumer snippet against Central 0.4.0 (throwaway project, no local repository). If it passes, drop the "(recorded against 0.3.0)" markers (four, around lines 513, 528, 539, 701) in a follow-up commit.

### Task 91 — done, and the 0.3.1 release is complete. No task file remains under `docs/plan/tasks/`.

Cut 0.3.1 for GHCR and the MCP Registry only — the owner decided Maven
Central stays at 0.3.0. The version bump covers 19 poms, `server.json`
(`.version` and `.packages[0].identifier`, a two-line diff), the Dockerfile
`ARG`, the publish-image dispatch default, the MCP `serverInfo` literals, the
IT jar literals, local build paths and `QUICKSTART_IMAGE_TAG`. Every
remaining `0.3.0` hit is classified as either a Central coordinate a reader
would copy, or a historical, past-tense record — those are marked "(recorded
against 0.3.0)" rather than bumped. The docs now say `data-prism-server` is
never on Central (`skipPublishing`) and comes from a source build, a GitHub
Release or GHCR; `data-prism-connectors-rest`'s source is unchanged since
v0.3.0. `CHANGELOG.md [0.3.1]` is dated 2026-09-24 and states "Not published
to Maven Central; the Maven artifacts remain at 0.3.0." verbatim. Attempt 1
was tester-PASS, reviewer-CHANGES (docs/CHANGELOG accuracy against the
no-Central decision); attempt 2 fixed those, and a final review approved all
but one CHANGELOG bullet, fixed directly in `0afe325`. Merged into local
`release-0.3.1`, not `main`; not pushed. See `docs/plan/HISTORY.md`, grep
`Task 91`, for what landed and the two classification lessons.

**Post-merge release checklist — done, verified 2026-09-24:**

1. ~~Confirm the CHANGELOG date matches tag day.~~ — done.
2. ~~`git tag -a v0.3.1 -m "v0.3.1" && git push origin v0.3.1`~~ — done; tag
   `v0.3.1` is on the #105 merge commit. The GitHub Release is live.
3. ~~Watch `release.yml`.~~ — done.
4. ~~Dispatch `publish-image` with `-f version=0.3.1`, then verify the GHCR
   manifests for `data-prism-server` and the four quickstart images.~~ —
   done: `ghcr.io/aindriub/data-prism-server:0.3.1` and the four
   `data-prism-quickstart-*:0.3.1` images all published and confirmed
   pullable.
5. ~~Dispatch `publish-mcp`, then verify the registry shows 0.3.1 with the new
   description and `websiteUrl`.~~ — done: the registry lists 0.3.1 as
   latest, with the new description and `websiteUrl`
   `https://aindriub.github.io/data-prism/`.
6. `publish-central` was **not** dispatched, by decision. Central stays at
   0.3.0.

Follow-up, not scheduled: `publish-mcp.yml` installs `mcp-publisher` from
`releases/latest`, unpinned. — **closed 2026-09-24** on branch
`pin-mcp-publisher` (commit `0e2fe46`): both `publish-mcp.yml` jobs now
download the pinned `mcp-publisher` 1.8.1 `linux_amd64` tarball and check it
against the SHA-256 from that release's `registry_1.8.1_checksums.txt`
before running it. To bump the version, change `MCP_PUBLISHER_VERSION` and
`MCP_PUBLISHER_SHA256` together, taking the new hash from that release's own
checksums file.

**Glama and awesome-mcp-servers, also 2026-09-24:**

- The Glama listing (<https://glama.ai/mcp/servers/AindriuB/data-prism>) is
  now claimed by the owner through `glama.json` at the repository root (PR
  #106), which grants Admin access to the listed GitHub user.
- The `docs/plan/outreach/awesome-mcp-servers.md` draft was posted:
  [PR #15059](https://github.com/punkpeye/awesome-mcp-servers/pull/15059) is
  open, from the `AindriuB` fork, in the Security section. Its entry carries
  ☕ 🏠 ☁️ (the draft's own scope-emoji reasoning is corrected in the draft
  file, which only counted ☕ 🏠 before posting). The list's
  check-submission CI passed, and its Glama badge bot commented. Awaiting
  the maintainers.

**Still open, unscheduled:**

- ~~The mcpservers.org free-form submission — owner action.~~ Submitted by the
  owner on 2026-09-25 through the free form (tagline as the short description).
  Accepted 2026-09-25: listed at
  <https://mcpservers.org/servers/aindriub/data-prism>, and the README now
  carries the "Listed on mcpservers.org" badge.
- The `docs/plan/outreach/awesome-java.md` submission — draft ready, not yet
  posted.
- The `docs/plan/outreach/launch-post.md` draft — owner's call on timing.
- The first monthly 15-question assistant check (see the discoverability
  section above) — still open.
- The next `snapshot.sh` run, due by 2026-10-08 (14 days from the
  2026-09-24 baseline).

## Remaining slices past the adopted core

S10-S12 were deferred past V1 on 2026-09-09, and adoption work (tasks 14-25)
has continued since without revisiting that deferral — it closes packaging and
configuration gaps the deferred slices do not touch.

S10 was deferred past V1 by decision and the index it needs already exists. S11
is the Elasticsearch connector and Docker Compose — most of that surface is now
covered by task 18's quickstart instead, and the three divergent stub sources
it was going to build landed in S6. S12's mutation and load testing is for a
system with users.

### Small open items, unscheduled

**Risk, unscheduled (2026-10-07):** Spring Boot 4.1 supports Jackson 2 but has
deprecated it for removal since 4.0.0.

- Jackson 3 port (scrubbing module, mcp-json-jackson3, flip the enforcer ban) before a Boot release removes Jackson 2 support. D-139-A keeps 0.5.0 on Jackson 2.

Found across v0.3.0 wave 1 (tasks 63, 64, 68), 2026-09-22. None blocks 63,
64 or 68, all merged; several are load-bearing for the wave-2 tasks named.

1. `FileAuditSink`'s poison is per instance, so after a torn write an
   operator restarts, the new sink opens `APPEND` on the same path, and its
   first record lands directly after the fragment — recreating the
   concatenated mid-file line the poisoning exists to prevent. Closing it
   needs inspecting the file's last byte at open, which this release
   deliberately makes an operator responsibility instead. Not a defect;
   task 66's verifier and its documentation must say so rather than
   rediscover it.
2. Tasks 63 and 64's contracts now interlock: 63's rollback-on-throw and
   64's poisoning are jointly correct only because chains are per-writer
   with a fresh `instanceId` per process (`docs/architecture.md` §A6). If
   either changes, re-establish the interlock explicitly.
3. `AuditSink`'s own javadoc still says nothing about the all-or-nothing
   requirement `AuditRecorder`'s rollback implicitly imposes on any
   implementation — it lives only in `AuditRecorder`'s javadoc and task 64's
   now-deleted task file, neither of which a future sink implementor reads.
4. `AuditRecorder` catches `RuntimeException` but not `Error`; a sink
   throwing `AssertionError` or an `OutOfMemoryError` leaves the sequence
   consumed. A gap, not corruption.
5. `FileAuditSink` catches `IOException` and `RuntimeException` but an
   `Error` thrown from inside the write loop escapes unpoisoned.
6. `AuditSinkFailureAbortsResponseTest` pins the sink's raw exception
   message reaching the MCP client. With the file sink, that message would
   name a server filesystem path — a disclosure to a client in a product
   whose premise is controlling what reaches the model. Two reviewers agreed
   the disclosure originates in the propagation path that maps a sink
   exception into an MCP response, not in the sink itself. Fix it where that
   mapping lives, before task 67 wires the sink.
7. `PRIVACY_MODULES_DO_NOT_MUTATE_OBJECT_GRAPHS_REFLECTIVELY` scopes to
   `..core..`, `..pseudonymisation..`, `..validation..`, `..orchestration..`
   — `connectors` is not in scope, and its `methodHandleFieldAccess()`
   predicate names `findGetter`/`findSetter`/`findVarHandle`/`unreflect*`,
   not `defineHiddenClass`/`defineClass`. Nothing stops a future connector
   reintroducing runtime class generation the way task 60's attempt 1 did.
   `data-prism-architecture` was not in any wave-1 task's `Owns`, so this
   needs its own task.
8. Test coverage still missing for properties currently safe by construction
   but unasserted in `FileAuditSink`: an already-closed channel followed by
   a second `record()` throwing `PoisonedException`; `ClosedByInterruptException`;
   a second sink opened on a path after the first was poisoned.
9. `data-prism-integration-tests` shares a surefire fork, and
   `java.net.http.HttpClient`'s default builder eagerly calls
   `SSLContext.getDefault()`, a JVM-wide singleton cached on first use. Any
   test building a default-SSLContext client before `McpHttpEndToEndTest`
   sets its own `javax.net.ssl.trustStore` poisons the cache and breaks that
   test's self-signed JWKS handshake for the rest of the fork. Task 63
   worked around it by giving its own client an explicit non-default
   `SSLContext`, but `McpHttpEndToEndTest` still owns the shared default, so
   the module is one careless new test away from the same failure.
   Recommended fix: give `McpHttpEndToEndTest` its own explicit `SSLContext`
   and retire the trustStore property.

Found on task 81 (outreach drafts), merged 2026-09-23. None blocks the
merge; tracing one launch-post sentence against the code turned up (4) and,
separately, the doc defect now filed as task 84.

1. No startup guard exists for an unclassified-unsafe profile:
   `PrivacyProfile.releasesUnclassifiedData()` (`PrivacyProfile.java:87`) has
   no callers anywhere in the reactor. **First verify reachability** — check
   whether a same-named `/privacy-profiles-default.yaml` earlier on the
   classpath (e.g. an extension jar on `LOADER_PATH`) can shadow the bundled
   one, since `DataPrismAutoConfiguration` loads it by classpath name alone
   (`DataPrismAutoConfiguration.java:356`, `:572`, both
   `getResourceAsStream("/privacy-profiles-default.yaml")`). If shadowing is
   possible, `PASS_THROUGH_UNSAFE` is reachable today with no refusal, and
   the guard is urgent rather than a nice-to-have. While there, consider
   putting the classification and path into the DENY audit event itself
   (see item 2) rather than leaving it only in the caller's error.
2. `docs/tools.md:120` says "unclassified values dropped" as the only
   behaviour; the code's default is `FAIL_REQUEST` (refuse the whole
   response), and drop is only one of the four `UnclassifiedBehaviour`
   settings.
3. `README.md:12` and `docs/use-cases/gdpr-data-minimisation-mcp.md:25` both
   say unclassified fields are "redacted or refused" — that names two of
   four settings (`REDACT_AND_WARN`, `FAIL_REQUEST`) and omits `DROP_AND_WARN`
   and `PASS_THROUGH_UNSAFE`.
4. `privacy-profiles-default.yaml:8`'s comment says unclassified "has only
   two settings by design" — `PrivacyProfile.UnclassifiedBehaviour`
   (`PrivacyProfile.java:46`) has four values, not two.

Found on task 84 (correct the `dataprism.privacy` row's false startup-refusal
claims), merged 2026-09-23. Neither blocks the merge.

1. `docs/configuration.md:73`'s "a production scope lifetime is required"
   understates the rule: `protectedDeployment()`
   (`DataPrismProperties.java` ~:127-129, :165) requires it in every
   deployment mode except stdio fixture-development, not only "production".
2. In stdio fixture-development mode, an unset `profile` skips
   `MISSING_PRIVACY_PROFILE` entirely; `validateProfile` then likely NPEs on
   `Map.copyOf(...).containsKey(null)` instead of returning a clean refusal
   code. Startup still fails either way, but with a stack trace instead of a
   named refusal — a code follow-up, not a docs one.

Found on task 82 (build and deploy the docs site), merged 2026-09-23. None
blocks the merge.

1. The llms-leak guard checks only the first line of at least 40 characters
   per excluded doc — a partial include of an excluded page's content could
   still slip past it undetected.
2. The link-rewrite hook gives a nested badge image
   (`[![alt](../img)](../x)`) the same `blob/main/…` treatment as a page
   link, when the image itself should get a `raw` URL like a bare image
   reference does.
3. A link leaving `docs/` with a `?query` suffix or a percent-encoded path
   component fails the build loudly (target-not-found) rather than being
   handled — acceptable today since no such link exists, but worth a
   deliberate rule if one is ever added.
4. MkDocs 1.6.x and Material 9.x are pinned for good reason: Material's own
   docs flag a coming backward-incompatible MkDocs 2.0, and Zensical reads
   `mkdocs.yml` as the documented migration path. Re-check both before ever
   bumping the pins.

Found on task 77 (FAQ and comparison page), merged 2026-09-23. None blocks
the merge; all three are wording gaps in `docs/faq.md`.

1. `docs/faq.md:125-126` sources "deferred by design" for the
   re-identification surface only — the Elasticsearch connector is also not
   built, but for a different reason (task 78/S11 scope, not a deferral
   decision), and the FAQ does not distinguish the two.
2. `docs/faq.md:41` (does it detect PII in free text) omits the scanner's
   depth cap of 16, beyond which it fails closed. Worth adding since a
   reader could otherwise assume unbounded scanning.
3. `docs/faq.md:68-70` (do I need Java) cites the README's MCP-registry
   section, which names only the adapter path — it does not by itself rule
   out every non-adapter, non-Java route a reader might ask about.

Found on task 69 (restore the reviewed-adapter allow-list), merged
2026-09-23. Neither blocks the merge; the first item matters more than it
looks.

1. **The autoconfigure/connector contract is a bean-name string duplicated
   as three literals** (`dataPrismConfiguredJsonSourceNames`, in
   `ConfiguredJsonSourcesAutoConfiguration`, `DataPrismAutoConfiguration`,
   and `DataPrismContractValidator`), with no shared constant — unavoidable,
   since keeping autoconfigure free of a compile edge to the connector is
   the whole point. A typo on either side fails closed (the provider comes
   back empty, the allow-list narrows, a legitimate configured-JSON adapter
   is refused) — the safe direction, but a confusing failure to debug. It is
   caught only by `ConfiguredJsonSourcesPackagingIT`, which runs under `mvn
   verify`, not `mvn test` — the same blind spot that hid attempt 1's crash
   (unit tests passed 22/22 there too, because connectors-rest sits on the
   autoconfigure test classpath; only `mvn verify` saw the
   `TypeNotPresentException`). Twice in one task, a real defect was invisible
   to `mvn test` alone. File a follow-up task for either a unit-level
   assertion tying the three literals together, or a documented requirement
   that this area be verified with `mvn verify` rather than `mvn test`. Until
   then: "the tests pass" means less here than usual unless it was `verify`.
2. The generic `Set<String>` injection point looks like it could collide
   with an unrelated `Set<String>` bean elsewhere in an application context,
   which for an allow-list would be a rule-1 widening. Verified empirically
   in real Spring contexts that it cannot: a named bean present alongside an
   unrelated `Set<String>` resolves only the intended one; the named bean
   absent alongside an unrelated `Set<String>` resolves to null (empty
   provider), it does not fall back to the other bean. Two candidates for the
   same name throw `NoUniqueBeanDefinitionException` (fail loud); none leaves
   the provider empty (fail closed). Do not re-litigate this: the only way to
   subvert it is an application deliberately defining a bean named
   `dataPrismConfiguredJsonSourceNames` in the vendor's own namespace. A
   core-owned marker type would be marginally stricter; the reviewer
   explicitly recommended against a third attempt to get it, and the
   recommendation stands.

Found on task 75 (per-boot audit chain identity), merged 2026-09-23. Neither
blocks the merge; the owner has not yet decided whether to schedule the
first item.

- `AuditChainVerifier` classifies any field-count-mismatch line anywhere in a
  file as `INTERRUPTED_WRITE_FRAGMENT` ("not tampering", exit 4). That means a
  file that is not an audit file at all — e.g. raw Slf4j log output pointed
  at the verifier by mistake — reads as a benign structural anomaly rather
  than as an alarm. Not filed as a task; unscheduled pending an owner
  decision on whether it is worth a task post-0.3.0.
- Two reviewer suggestions not taken, recorded rather than acted on:
  `AuditRecorderTest`'s `INSTANCE_ID_SHAPE`/`AuditChainVerifierTest`'s
  `SEQ_SHAPE` preconditions use `find` semantics (a substring search), where
  `matches` (a whole-string match) would pin the shape more tightly; and the
  `seq` field's `/\d+` suffix relies on the whole-line word-boundary fallback
  scan rather than being pinned by the field-shape regex itself. Neither is a
  known false negative today — recorded so a future tightening pass has
  somewhere to start rather than rediscovering both from scratch.

Found on task 66 (audit chain verifier CLI), merged 2026-09-23. Neither
blocks the merge.

10. `AuditChainVerifierCli.java:38` — the `EXIT_STRUCTURAL_ANOMALY` javadoc
    still reads "a known non-tampering structural anomaly", the exact
    phrasing the printed `--help` text dropped at attempt 3 for asserting
    benignity it could not support. Source-only, invisible to a compliance
    reader, but the same claim living on in a comment. Task 72 (merged
    2026-09-23) touched this file but not this line — still open. File it
    separately.
11. Process note, not code: attempt records appended to a task file in the
    main checkout are not visible in a worktree created earlier, because the
    worktree holds its own copy from its branch point. Task 66's worktree was
    created before its attempt-1/2/3 sections were written, and an
    implementer working from the worktree read a stale task file — a note in
    the file itself had to say "read this from the main checkout" to route
    around it. Future briefs should restate defects inline rather than
    relying on the task file being current inside a worktree, or write
    records into the worktree too.

Found on task 60 (nested JSON catalogues), merged 2026-09-22. None blocks the
merge; pick any up only if a future task already owns the file.

10. `ConfiguredJsonNestedCatalogueOrdinalDeterminismTest` asserts with
    `contains("Slot1")`, which also matches `Slot10`-`Slot15`; harmless at
    three catalogues today, but an exact suffix match would be tighter.
11. The reserved-prefix refusal (a `nonSensitive:` reason beginning with the
    nested-pointer token) is asserted only for a top-level field; a case
    inside a nested catalogue would pin both call sites.
12. The null-nested-object passthrough has no named test — a tester verified
    it with a throwaway and deleted it.

Found during `/verify` on task 58, 2026-09-22. Does not block anything; polish,
not a defect.

- `docs/protect-your-own-api.md:132, :157, :337, :351` — four verification/curl
  fences follow foreground processes without naming a terminal, unlike the
  server section, which says "a third terminal". A reader must infer a spare
  shell; no wrong output can result.

Found during `/verify` on tasks 53, 54, 55, 56, 57, 2026-09-21. None blocks
anything already merged; the first is the most important of the six.

- ~~**(53's review, most important.)** The new `IdentityResolver` bean is
  placed on a nested `@Import`ed static configuration class, which keeps it
  outside `AutoConfiguredBeanClassificationTest`'s reflection sweep.~~
  **Resolved 2026-09-22 by task 68** — see v0.3.0 wave 1 above and
  `docs/plan/HISTORY.md` (grep `v0.3.0 wave 1`).
- (54's review.) The subset relaxation lost a property nobody has restored:
  `dataprism.sources` was also the operator's allow-list, and any
  `DataSourceAdapter` bean on the classpath is now implicitly approved
  without appearing anywhere an operator reviewed. Not a fail-closed
  breach — the reviewer could not construct a misconfiguration the subset
  test lets through that exact match caught — but a real weakening of the
  reviewed-adapter posture. Owner-scheduled narrow fix: exclude exactly the
  names the JSON-catalogue mechanism supplies, via a marker or
  catalogue-names bean visible to both `data-prism-connectors-rest` and
  `data-prism-spring-boot-autoconfigure`. Task 54's own summary described
  the change as guarantee-preserving, which was stronger than warranted —
  do not repeat that framing.
- (55's review, for whoever merges 55.) `compose.yaml:12-13`'s comment
  contradicts itself — it says images are "tagged at the reactor version
  (or `QUICKSTART_IMAGE_TAG`, default `latest`)" but the default resolves
  to `latest`, never the reactor version. Also: four unguarded `--load`
  builds in `publish-image.yml` have no consumer, costing three extra Maven
  builds per arch on every plain tag push as a build-breakage smoke test
  only; and a tag pushed ahead of a pom bump fails at `COPY
  ...-${VERSION}.jar` with a raw buildx error rather than the guard's named
  message (fails closed, cosmetic).
- (56's re-review.) `run.sh:68-71` takes the first `data:` frame; if the
  server ever emits a progress or log notification before the result,
  fields come back empty and the demo fails with "missing an expected
  field" rather than parsing the frame whose id is 2. Not reachable against
  today's server and it fails closed, so a robustness nit rather than a
  defect. Also `mcp-handshake.sh:44` hardcodes `clientInfo.name` to
  `quickstart-demo`, so the smoke test now identifies itself as the demo in
  server-side logs.
- (57's review.) `ConfigurationRefusalMessageIT.java:133-138` reads process
  output only after `waitFor`, so a startup log exceeding the ~64KB OS pipe
  buffer would deadlock until the 20s timeout. Latent, not live.
- (minor, from 54's review.) `DataPrismContractValidatorTest.java:47` and
  `:78` have byte-identical bodies — one behaviour asserted twice under two
  names. That file also sits outside task 54's declared `Owns` list, but it
  was compelled by the acceptance criterion and collides with nothing, so
  this is recorded rather than treated as a violation.

Found by the reviewer during `/verify` on task 47, 2026-09-17. Neither is
reachable today; pick either up only if a future task already owns the file.

- `PiiLogScanTest`'s `EXCLUDED_FIXTURE_FIELDS` is consulted only for the
  top-level fixture record's own components, not for a nested type's. An
  exclusion keyed on a nested type (e.g. `DeliveryDto`) would be silently
  ignored — accepted by whatever registers it, applied to nothing — with no
  signal to whoever wrote it. Not reachable today because no nested type
  currently needs an exclusion.
- A `null` leaf in a fixture record would enter the derived banned-value set
  as the literal string `"null"`, which would almost certainly false-positive
  against ordinary log prose (`if (result == null) log.warn(...)` and
  similar are common). No fixture is null today, so this has not fired.

Found during `/verify` on task 41, 2026-09-17. Does not block anything; not
picked up by task 41 because moving the version bump's own criterion is a
different-shaped change than making one.

- Nothing asserts on the MCP `serverInfo` handshake version string
  (`DataPrismMcpServer.java:86,127`). It is the one value every MCP client
  reads on `initialize`, and today it is corroborated only indirectly by
  packaging tests that check jar paths, not the string a client actually
  sees. A future version bump could leave it stale and every existing check
  would still pass.

Found during `/verify` on task 35, 2026-09-16. Neither blocks anything; the
reviewer judged both safe to leave rather than fold into 35's scope.

- `dataprism.transport.mode=stdio` now refusing unconditionally
  (`DataPrismAutoConfiguration`'s `dataPrismStdioTransportRefused`) makes
  `dataprism.transport.fixture-development=true` unreachable everywhere in
  the Spring surface. `ConfiguredJsonSourcesInitializer`'s plaintext-loopback
  relaxation and `DataPrismContractValidator`'s zero-source early return are
  consequently dead-in-effect paths — still executed, still pinned by tests,
  but reachable by no live configuration.
- `data-prism-connectors-rest`'s own `fixture-development` read is dead code
  for the same reason.

Found during `/verify` on task 31, 2026-09-15. Neither blocks anything; pick
either up only if a future task already owns the file.

- An application that registers its own `FieldMetadataResolver` bean
  silently suppresses the descriptor wiring: the shipped bean is
  `@ConditionalOnMissingBean`, so a set `dataprism.privacy.descriptor-file`
  is then never read or validated, and startup succeeds without warning.
  The reviewer scoped the fix to a successor task rather than folding it
  into task 31, because closing it means guarding a `REPLACEABLE` extension
  point, a different-shaped change than wiring the property was.
- A descriptor file containing only a YAML document marker (`---` with no
  `models:` key) makes `ModelDescriptors` throw a `NullPointerException`
  rather than return a named refusal code. Startup still fails closed, so
  the fail-closed invariant holds, but `docs/conventions.md` expects a
  stable code for every refusal, not an incidental `NullPointerException`.

Found during `/verify` on tasks 28 and 29, 2026-09-15. Neither blocks
anything; pick either up only if a future task already owns the file.

- `StrictYamlTest`'s comment claims the old inlined `enumValue` logic was
  never invoked with `null`. The reviewer showed that is false: four call
  sites in `PrivacyProfiles` and `ModelDescriptors` do call it with `null`.
  `StrictYaml.enumValue`'s behaviour is correct either way — only the
  comment's stated justification is wrong, and `docs/conventions.md`
  forbids a comment asserting a state nobody established.
- `JsonTreeScrubbingEngineTest`'s new cross-field test, added to exercise the
  `OwnerScope` record, duplicates an existing assertion on the same fixture
  and does not exercise a nested parent scope as intended. The record's
  behaviour under real nesting remains unproven by a dedicated test.

Found during `/verify` on task 20, 2026-09-15. None blocks anything; pick any
of them up only if a future task already owns the file.

- `ConfiguredJsonSourcesAutoConfiguration` hand-copies
  `DefaultContextOrchestrator`'s construction from
  `DataPrismAutoConfiguration.java:233-241` rather than sharing it, so it is
  invisible to task 22's classification sweep, which scopes to
  `DataPrismAutoConfiguration.class.getDeclaredMethods()`. A future hardening
  of the base orchestrator's assembly will not reach a deployment with this
  jar on its loader path. The architect's recommendation: have the connector
  contribute a composed `ScrubbingEngine` bean instead, which would also let
  it drop its `data-prism-orchestration` dependency.
- `SourceValues.java:33-47` and `DefaultContextOrchestrator.java:234` resolve
  `FieldMetadata` by `record.getClass()`, which the shared
  `ConfiguredJsonPayload` wrapper defeats, so the orchestrator's own
  prohibited-value computation returns an empty or wrong set for configured
  sources. `ConfiguredJsonScrubbingEngine` independently closes that gap and
  fails closed, but the root assumption is wrong and lives in
  `core`/`orchestration`, not in the connector.
- `connectorsAreLeaves` only forbids INBOUND edges into
  `..dataprism.connectors..`; it does not constrain a connector's own
  outbound dependencies. `docs/architecture.md` declares the row
  `connectors-rest | core |`, which `data-prism-connectors-rest/pom.xml` now
  contradicts by depending on `validation` and `orchestration`, with nothing
  able to fail the build over it. The architecture document states a
  boundary no rule enforces.
- `ConfiguredJsonSourcesInitializer` never checks `dataprism.transport.mode`
  itself, relying on other modules to refuse. Fail-closed today, but it
  holds no independent opinion on transport mode.
- A catalogue source name containing `_` or uppercase now makes `Binder.bind`
  throw `InvalidConfigurationPropertyNameException` where `getProperty`
  previously returned null. It still fails closed, but the message names
  neither the source nor the cause; only blankness is validated at
  `ConfiguredJsonSources.java:104`.

Found during `/verify` on tasks 21-24, 2026-09-14. None blocks anything; pick
any of them up only if a future task already owns the file.

- The JWKS discovery success path now has no positive coverage in either
  module: task 21 removed `SecurityConfigTest.discovers_jwks_when_the_issuer_discovery_location_is_configured`
  because it depended on the removed plaintext relaxation. A positive test is
  still constructible — serve metadata over plaintext, return an `https://`
  `jwks_uri`, assert the decoder builds and `metadataRequests == 1`, since
  `NimbusJwtDecoder` fetches lazily. Token decoding itself remains proven by
  `ServerSecurityBoundaryTest` and `McpHttpEndToEndTest`, so this is a gap, not
  a hole.
- `STANDALONE_HTTP_ONLY` is now asserted by no test anywhere.
- `ServerIntegrationsConfiguration.java:22`'s `isFixtureDevelopment()` disjunct
  is now dead code, since fixture implies STDIO.
- `PrivacyExtensionPoints`' `COMPETING_BEAN_REFUSAL` is read from the same file
  the sweep checks, so a future bean can self-certify a guard that does not
  exist; only the `@ConditionalOnMissingBean`/`@Primary` halves are
  independently verified.
- `ServerArchitectureTest`'s `that()` side constrains every dataprism class on
  the server's test classpath rather than just `..dataprism.server..`; a
  future edge module using Spring Security would go red in a test owned by a
  different module.
- `data-prism-server`'s `spring-boot-maven-plugin` repackage replaces its
  plain jar, so its classes are invisible to any ordinary Maven-classpath scan
  after the `package` phase. Task 23 worked around it inside its own module;
  the underlying fix is a `<classifier>` on that plugin execution in
  `data-prism-server/pom.xml`.
- ~~`data-prism-connectors-rest` is an unconsumed leaf — nothing in the
  reactor depends on it.~~ **Resolved 2026-09-15.** Task 20 wired it: the
  configuration-driven JSON REST mode is its first real caller, and its test
  count went from 13 to 49. (`data-prism-hazelcast` was resolved the same
  way one wave earlier: task 25 gives it a real caller through the
  autoconfigure module's `embedded` topology.)

Found during `/verify` on task 18, 2026-09-14. Neither blocks anything; pick
either up only if a future task already owns the file.

- ~~`ServerPackagingIT.DEVELOPMENT_KEY_MARKERS`
  (`data-prism-server/src/test/java/.../ServerPackagingIT.java:42-51`) was
  not extended with the quickstart's HMAC literal,
  `quickstart-demo-hmac-key-not-a-real-secret-32-bytes-long`, and that file's
  own javadoc obliges whoever adds a development key to extend the list.~~
  **Retracted 2026-09-15.** This recommendation was wrong: `data-prism-server`
  never compiles or reads the quickstart env file, so no artefact this scan
  inspects can ever emit the literal, and the class's own javadoc forbids a
  marker no build artefact emits — extending the list would itself have
  violated the file's rule. Task 20 added the marker, then correctly removed
  it once this was established; a tester confirmed by unzip-scanning the
  freshly repackaged jar that the literal appears zero times. Do not
  re-propose this.
- `QuickstartSmokeIT.java:164` asserts the synthetic name's shape, not its
  stability. A second call for the same subject in the same scope returning
  the same value would make the pseudonymisation claim materially stronger,
  and consistency-within-a-scope is the core guarantee this product sells.

Found during `/verify` on task 25, 2026-09-14. None blocks anything; pick any
of them up only if a future task already owns the file.

- `dataprism.privacy.hmac-key`'s neighbour `identity-cache-ttl` is bound,
  validated, and read by nothing — `CachingSyntheticValueSource.java:142-147`
  derives its TTL from `context.expiresAt()`, and `PrivacyCluster.configure()`
  (`PrivacyCluster.java:71-75`) replaces any pre-set `MapConfig`, so no caller
  can wire it. Pre-existing, confirmed by review, not introduced by task 25.
  Same defect shape as `topology` was, one layer down: a property an operator
  can set that changes nothing. `docs/configuration.md:63` makes no false
  claim about it, but the refusal column still implies the property means
  something. Fixing it needs `data-prism-hazelcast`, which task 25 was
  explicitly scoped out of.
- `DataPrismAutoConfiguration.java:225` — `@ConditionalOnBean` also suppresses
  the bean definition, so `topology: embedded` with Hazelcast present but no
  `DataSourceAdapter` refuses with `MISSING_SHARED_BUDGET` and a message
  claiming Hazelcast is missing from the classpath, which is false. Fails
  closed, but reports the wrong cause.
- `DataPrismAutoConfiguration.java:188` — `matchIfMissing=true` is untested;
  flipping it to `false` leaves all 36 autoconfigure tests green. It makes no
  refusal unreachable, so it is not a fail-open, but the default is unproven.
- `DataPrismAutoConfiguration.java:225` — `"embedded".equals(...)` is
  case-sensitive while the `@ConditionalOnProperty` above it matches
  case-insensitively, so `topology=Embedded` under stdio fixture-development
  with Hazelcast absent fails closed on an `UnsatisfiedDependencyException`
  rather than the stable code.

Left by task 09's close-out. Neither blocks anything; pick either up only if a
future task already owns the file.

- `PiiLogScanTest.java:191-193` — the sum assertion (`auditCount + nonAuditCount
  == total`) is tautological: the second count is defined as the complement of
  the first, so the assertion cannot fail. The two non-empty assertions either
  side of it are the load-bearing checks and do work. Harmless, but the sixth
  instance in this repository of an assertion that cannot fail — this one came
  from the task brief itself rather than from the implementer.
- `PiiLogScanTest.java:104-112` — `AUDIT_KEYS` holds 19 names while the javadoc
  says "twenty placeholders": the sink's `seq={}/{}` is two placeholders folded
  into one field. `docs/conventions.md` forbids a comment asserting a state
  nobody established; this one should be corrected to 19, or the javadoc
  reworded to explain the fold, next time this file is touched.

Found by the 2026-09-09 documentation audit, not fixed there because none of
it is a doc fix:

- `ArchitectureTest.pseudonymisationIsDeterministic`'s missing `Instant.now()`
  check, and boundaries 2, 3, 5, 6 in `docs/architecture.md` having no
  enforcing test — see that file's boundaries section for the full
  enforcement audit — are now tasks 12 and 13 above, not free-floating items.
- No `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, or issue/PR
  templates. Security reporting is folded into `CONTRIBUTING.md`, which works
  but is non-standard for a public repository.

Found by task 10, not fixed there because neither is that task's work:

- Task 10's PRs (#9 and #10) were merged into `main` by that task's own
  implementer, using the owner credentials every agent authenticates as —
  not by repository auto-merge, which was and is off (`allow_auto_merge` is
  `false`). PR #9 was merged while its own Actions run was failing (run
  34385475478). This has two halves, and only one is closed:
  - ~~`main` had no branch protection requiring the `build` check to pass
    before a branch could reach it, by any route including a direct
    push.~~ **Resolved 2026-09-09.** Branch protection on `main` now
    requires the `build` status check (GitHub Actions), up to date with the
    branch being merged, enforced for admins, with force-push and deletion
    blocked. A commit whose `build` check has not passed can no longer reach
    `main`. Consequence for the loop: `/record` can no longer merge a task
    branch locally and push straight to `main` — that push is itself
    rejected, since the merged commit has no check run against it yet.
    Every task branch now reaches `main` through a PR whose head commit goes
    green. See `docs/workflow.md`'s Phase 4 for the updated flow.
  - **Still open.** Branch protection does not stop an agent with owner
    credentials from merging green-but-unreviewed work, including its own
    pull request — agents can do anything the repository owner can, and
    requiring approvals would not change that, since the same credentials
    could approve too. The only real control is role discipline: an
    implementer's job ends at reporting on its branch, and merging happens
    only in `/record`, after `/verify`. That is now stated explicitly in
    `docs/workflow.md` rather than left implicit. Owned by the role
    definitions, not by repository configuration.
- The kit's own `/record` skill definition (outside this repository, in
  `~/.claude/commands/`) still describes the old flow — merge locally, push
  straight to `main`. This repository cannot fix it; it belongs to the
  repository owner to update in the kit itself, or the next `/record`
  invocation will attempt a push that branch protection now rejects.
- The seventh cannot-fail assertion this survey found —
  `RestDataSourceAdapterHttpTest.java:97`'s bare
  `isInstanceOf(RuntimeException.class)`, the same vacuous form task 08
  removed from its neighbour — is now task 11 above, not a free-floating
  item. A survey of every test module found no other test asserting on a
  platform-specific exception type or message, so the cross-platform problem
  task 10 fixed appears confined to the one test it fixed.

## Someday

Ordered, not scheduled. S5, S6 and S7 are independent of one another once S3
lands and are the natural parallelisation point.

- **S5 — Connectors and orchestration.** `RestClient` sources, virtual-thread
  fan-out with timeouts and circuit breakers, `IdentityResolver` SPI, request and
  cost limits.
- **S6 — Canonical model and correlation.** Canonical entity, provenance,
  per-scope pseudonymisation of source names, normalisation-aware consistency
  findings, the injection-heuristic finding type.
- **S7 — Hazelcast.** Client–server topology, forward and reverse maps, TTL,
  scope purge, and the test proving output is identical with the cluster killed.
- **S8 — Security.** OAuth2 resource server, mTLS, RBAC and ABAC, purpose
  validation, scope lifecycle held entirely outside the MCP surface.
- **S9 — Audit and observability.** Per-writer hash chain, append-only sink,
  pseudonymised subject ids in audit, metrics, and a log-scanning test that fails
  on any PII in a full integration run.
- **S10 — Re-identification surface.** Separate application, separate port,
  separate authorisation scope, mandatory purpose and audit. Deferred past V1 by
  decision; the reverse map it will read is still built in S7, because one added
  after the fact cannot resolve any pseudonym issued before it existed.
- **S11 — Example application and search.** Three divergent stub APIs, the
  Elasticsearch connector with allowlists and caps, `search_entity_data` and
  `describe_entity_model`, Docker Compose.
- **S12 — Hardening.** Threat model T1–T10 as tests, mutation testing over the
  privacy and validation modules, `docs/data-protection.md`,
  `docs/threat-model.md`, ADRs, load testing.

## Not doing

Recorded so they are not re-proposed. Each is a deliberate scope boundary from
`docs/design-review.md`, not an oversight.

- Probabilistic entity matching. It sits behind `IdentityResolver` and is a
  different product.
- Any MCP tool that opens, selects or extends a privacy scope. That is the one
  lever that breaks scope isolation.
- Any MCP tool that re-identifies a pseudonym.
- Caching raw source responses.
