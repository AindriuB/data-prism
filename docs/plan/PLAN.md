# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse than
no plan.

Slice numbering (`S0`–`S12`) matches `docs/development-plan.md`, which carries
the sizing and the exit criteria. This file carries the order and the state.

---

## Road to 1.0 (agreed 2026-10-09)

Full roadmap in `roadmap.md` (this directory). It supersedes `roadmap-ideas.md`, which is removed. Releases 0.7 (foundations and design), 0.8 (many-source core), 0.9 (release candidate, freeze at 0.9.0), then 1.0.

Owner decisions:

- D-0.4.1-A: no Hazelcast Enterprise.
- D-RM-1 (a): 1.0 supports multiple instances; cluster security designed in 0.7, built in 0.8. **Amended 2026-10-23 by owner decision (b): 1.0 supports multiple instances on a trusted network only. Cluster security for untrusted networks is deferred past 1.0** ("After 1.0: untrusted-network clusters", below). 1.0 keeps member cards and the sentinel (227) and the sealed, leased pause document (228), and adds trusted-network guardrails (284, 287): a joining member must declare `dataprism.hazelcast.trusted-network: true` and listen on a private interface. Identity and re-identification entries are sealed in 1.0 (225, kept by a second owner decision on 2026-10-23, so the 2026-10-19 "encrypted re-identification values" decision stays in force); approvals and counters are not. Key rotation (229) is deferred, so sealed values use the current cluster key only and a cluster-key change is a planned full restart. Owner-settled: generic tools only; the agent sequences its own calls; no application-specific traces or links on the server; per-source results are never merged; real names are opt-in per scope.
- D-RM-2 (a): search comes after 1.0.
- D-RM-3: folded into D-RM-14.
- D-RM-4 (a): links only for fields marked `chainable: true`.
- D-RM-5: exact namespace match; translation only via a declared key resolver.
- D-RM-6: link lifetime configurable, default 30 min, may be set to the scope lifetime, never beyond the scope.
- D-RM-7 (c): batch size configurable per scope, default 16, with a configured ceiling. The owner expects thousands of items per investigation.
- D-RM-8 (a): separate alias description checked at startup, generated fallback.
- D-RM-9 (a): refuse with a clear code when no source is chosen and the estate is too large.
- D-RM-10 (a): real names only when the scope setting and the caller permission both allow; audit always records real names plus the effective setting.
- D-RM-11 (a): role-to-source patterns in the security policy; unmatched sources denied.
- D-RM-12 (a): per-source truncation at record boundaries with markers and next-page links; hard overall cap as backstop.
- D-RM-13 (a): keep `compare_entity_sources`; review usage before the 0.9 freeze.
- D-RM-14 (a): HATEOAS links: sealed, one-step, built from adapter and resolver declarations.
- D-RM-15 (b): paging plus status-only bulk follow with drill-in links.

Design facts found for the 0.7 planner (2026-10-09) are in `archive/PLAN-2026-11-11-wave-13b.md` (block 1).



### Open work and execution state (updated 2026-10-21)

0.7.0 is released (2026-10-20); see `HISTORY.md`, "0.7.0 released". Every 0.7.0 task has landed on `main`. 0.8 is planned (task 215 and the core briefs); see "0.8 plan" below. 0.7.1 is released (2026-10-22, tag `v0.7.1` at `256d5e16`; Central, ghcr image and MCP registry); see "0.7.0 and 0.7.1: released" below and `HISTORY.md`.

##### Open: Sonatype Central File Count limit (decided at the 0.8.0 release cut)

Central warned on 2026-10-22 that the organisation reached its monthly File Count publishing limit; the grace period ends 2026-11-08. On 2026-11-04 the owner moved the choice (check the plan or limit in the portal, publish fewer modules, or release at most once a month) to the 0.8.0 release cut. The owner does not expect wave 8 to finish before 2026-11-08 and can defer the release, so the cut, not an earlier task, decides.

Decided 2026-11-13 (D1): before task 321 publishes, check the Central File Count; release at most once a month. The owner checks the count on 2026-11-13, and 321 is gated on the result.

##### Open (owner, deferred 2026-10-23): merge small modules to cut published artifacts

The owner has asked to record the options and return to this later; nothing is decided. This is the architect's assessment of 2026-10-23, and it bears on the File Count item above.

- **Recommended (a):** merge `data-prism-validation`, `data-prism-pseudonymisation` and `data-prism-security` into `data-prism-core`, keeping the package names. Central goes from 13 published jars to 10. Also make `data-prism-spring-boot-starter` pom-only, since it holds only `package-info.java`. Per-release files go from about 400 to about 300 (an estimate). Do it as the last 0.8 task, after 203, 208, 248, 250 and 256 have merged, because their Owns name those module paths.
  - Cost: three Maven coordinates are removed, which is a breaking change. The build-level barrier goes, leaving ArchUnit as the only guard. `docs/architecture.md:35-52` and `ArchitectureCoverageTest` need updating, and the connectors-rest "depends on core" drift needs fixing (it also depends on validation and orchestration).
- **(b):** merge only validation and pseudonymisation, keeping security separate if core's published dependency footprint should stay minimal.
- **(c):** no merge; handle the limit through the Central portal plan, or a release cadence of at most one a month.
- **Must not merge:**
  - annotations and processor: the processor must stay off the compile classpath.
  - connectors-rest: it is an opt-in `-Dloader.path` plug-in.
  - mcp and orchestration into core: boundary 1, and "core carries no business domain".
  - hazelcast and reidentification: both are optional for single-node deployments.
  - reidentification into hazelcast: it weakens boundary 5.
- **Note:** one release a month matters more than module count for the File Count limit.
- **Bears on 0.9 starter onboarding (2026-10-23):** if a merge brief is filed, 277 (the starter pom) and 280 (the root and architecture poms) depend on it. 277's OD-277-1 (a) adds `data-prism-connectors-rest` as a starter dependency, which every option here allows, a pom-only starter included.

#### 0.7.0 and 0.7.1: released

0.7.0 (2026-10-20) and 0.7.1 (2026-10-22, tag `v0.7.1` at `256d5e16`) are released; see `HISTORY.md`. The 0.7.1 plan, the backport commit lists and the 0.7.0 gate are in the archive (`archive/PLAN-2026-11-10.md:75-130`). What 0.7.1 left owed to 261 (user pages, on `main`) stays here, word for word:

Documentation owed by 245, 268 and 244 for 261 landed in PR #239 (moved verbatim to `archive/PLAN-2026-11-10-wave-13a.md`). Still owed after it: `docs/conventions.md:74-77` (stop naming `AuditUnavailableException` as client-visible; add the orchestrator log site) and `docs/plan/design/contract-register.md:253` (point `AUDIT_UNAVAILABLE` at `DenialAuditFailedException` and `ToolCalls`; add the metric row and the deprecation), both untouched by 261 (outside its Owns); and the owner decision whether a checkpoint failure on an allowed call should answer `AUDIT_UNAVAILABLE` instead of a generic error with no code (the docs now describe the code as it is).

Related, not fixed in 0.7.1: a Map of a record type has a similar element-type bug. 265 refuses it; proper support (walking `Map<String, Record>` entries) is planned for 0.9. 267 refuses records inside nested containers; descending into them needs `FieldMetadata` widened (0.9), and wildcard lower bounds in `recordBeyondFirstArray` also wait for 0.9 (they fail closed). Open: whether `Optional` shapes should be refused at startup.

#### 0.8 plan (agreed 2026-10-20)

Waves 1 to 12 have landed (the table below carries one row for waves 1 to 11 and one for wave 12; the records are in `HISTORY-INDEX.md`). The re-plan of 2026-10-23 (owner decision (b), D-RM-1 (a) as amended above) deferred 224, 226, 229, 230, 231 and 264 past 1.0 (`tasks/deferred/`) and kept 225, 227 and 228; the owner cut nothing else from 0.8.0. The briefs carry their own dependencies, **Wave** lines and contract text under `docs/plan/tasks/`; the decisions made during waves 5 to 11 are in `DECISIONS.md`. The full pre-cut text, including the wave-landing records, the owner decisions of 2026-10-27 and the per-wave review notes, is in `archive/PLAN-2026-11-10.md:131-501`.

| Wave | Briefs |
|---|---|
| 1 to 11 | landed 2026-10-21 to 2026-11-09 (268, 218, 219, 238, 239, 243, 258, 259, 220, 221, 240, 222, 242, 265, 244, 257, 203, 206, 223, 241, 248, 269, 245, 267, 207, 210, 225, 271, 285, 288, 208, 227, 246, 262, 284, 289, 290, 211, 228, 247, 256, 292, 294, 249, 250, 251, 272, 287, 291, 296, 297, 298, 293, 299, 252, 254, 300, 253, 286, 301, 302). 295 was deferred past 0.8.0 (`tasks/deferred/295-ack-gated-tombstone-gc-for-the-pause-document.md`) and must be reissued against F1 to F5 of the 228 contract before pickup. Per-wave detail: `archive/PLAN-2026-11-10.md:141-151` and `HISTORY-INDEX.md` |
| 12 | 255 (landed 2026-11-10, PR #236), 304 (landed 2026-11-10, PR #237) (253, 286 and 301 landed earlier. 304: client content out of the MCP SDK's inbound parse-error logs; filed 2026-11-09) |
| 13 | 260 (landed 2026-11-10, PR #240), 261 (landed 2026-11-10, PR #239), 263 Part A (landed 2026-11-11, PR #243, `95cfee34`; Part B, the owner-run soak, stays open as a 0.8.0 release-cut obligation below and `tasks/263-*.md` is now its contract). Filed 2026-11-10: 306 (links-enabled capture fixture for `follow` and `_links`, unscheduled; `tasks/306-links-enabled-capture-fixture.md`). Filed 2026-11-11 from PR #243: 307 (soak provenance; landed 2026-11-12, PR #254, `64af8265`; follow-ups under "307 follow-ups"), 308 (HazelcastOversightTest grace-race test fix), 309 (stdio leak-test teardown; landed 2026-11-11, PR #249, `8c41a1f1`; its review follow-ups are under "309 follow-ups"), 309-F1 (stdio reader end-of-stream and child-exit checks; landed 2026-11-12, PR #252, `4059d4bd`; follow-ups under "309-F1 follow-ups") |
| 14a | 310 (key-rotation guide, PR #260, `1036a6c0`), 311 (audit and architecture, PR #259, `4001d053`), 312 (multiple-instances, eu-ai-act, cluster-security, PR #258, `3a4ea595`), 313 (extending, tools, faq, PR #257, `52781007`): done 2026-11-12. 213 was split into these and 314 (`tasks/retired/213-*.md`) |
| 14b | 314 (0.8 migration notes and changelog, PR #262, `703528ae`): done 2026-11-12 (`tasks/retired/314-*.md`) |
| 0.8.0 wave 1 | 315 (PR #270), 316 (PR #271), 320 (PR #272), 317 (PR #273), 319 (PR #274), 318 (PR #275): landed 2026-11-13 (`tasks/retired/`; record in `HISTORY-INDEX.md`). Dependabot #246 and #148 closed as superseded by #272 |
| 0.8.0 wave 2 | 321 (cut 0.8.0; no brief filed). **Gated on D1 and D2** (`DECISIONS.md`, 2026-11-13): the owner checks the Maven Central File Count first, and runs the soak on the release PR's merge commit before the tag. Release at most once a month |
| 15 (0.9) | 273, 274 (0.9 starter onboarding, filed 2026-10-23; see "0.9 starter onboarding plan" below. 273's OD-273-3 (a) mechanism, rewritten 2026-10-26, is in `tasks/273-default-low-stakes-settings-and-derive-from-spring.md`; the pre-cut row is `archive/PLAN-2026-11-10.md:155`) |
| 16 (0.9) | 275, 277 |
| 17 (0.9) | 276 (moved from 16 on 2026-10-24: it now adds `MISSING_MCP_SECURITY`, so it follows 275 on `freeze/codes.txt`) |
| 18 (0.9) | 278 (after 276, on `freeze/codes.txt`) |
| 19 (0.9) | 279 |
| 20 (0.9) | 280 |
| 21 (0.9) | 281 (after 280: it cites 280's Outcome) |
| 22 (0.9) | 282 |
| per OD-283-1 (recommended after 0.9.0) | 283 (external template repository; OD-283-1 is open) |
| deferred past 1.0 | 224, 226, 229, 230, 231, 264 (`tasks/deferred/`; see "After 1.0: untrusted-network clusters") |

Obligations for the 0.8.0 release cut (no brief filed yet; whoever files it copies these into its Acceptance):

- The obligations marked done on 2026-10-27, 11-06, 11-07 and 11-09 (tasks 210, 299, 300, 301, 302) were met and moved verbatim to `archive/PLAN-2026-11-10-wave-13a.md`. Still live from them: the 0.8.0 CHANGELOG and 213's docs must say that startup refuses with `MISSING_CASE_AUTHORIZER` or `MISSING_CLIENT_ALLOWLIST` unless configured.
- Re-run 262's clean-checkout `docker compose up` against the published 0.8.0 images, not the local build overlay (coordinator decision 2026-10-23). 262 has merged (PR #182); the cut also reruns the `docs/quickstart.md` walkthrough capture and `docker/smoke/reidentification-smoke.sh` with `SMOKE_IMAGES=published`.
- Decide the Sonatype Central File Count choice (owner decision 2026-11-04; see the open item near the top). The grace period ends 2026-11-08; the release can be deferred. **D1 (2026-11-13): check the count before 321 publishes; release at most once a month; 321 is gated on the owner's check.**
- 263 Part B, the owner-run soak: run the 0.8 core on one node (8 hours) and on a three-member trusted-network cluster (4 hours), with the faults, startup probes, captures and `LinkTimingSoakTest`, and record the evidence in `docs/plan/perf/soak-0.8.md`. Part A (the scaffolding) landed in PR #243; `tasks/263-*.md` is the Part B contract and 263 is not retired until Part B is recorded. It must run from a clean committed snapshot: `build-images.sh` refuses a dirty tree without `SOAK_ALLOW_DIRTY`, and evidence runs never set it. The hostile-member run needs `TAMPER_FLOOD_CMD`, `HEALTH_PATH` and `HEALTH_BEARER`, and the clock-skew run needs `FAKETIME_LIB`. `load-test/pom.xml` no longer needs a manual `dataprism.version` bump (`run.sh` passes it). **D2 (2026-11-13): run it on the release PR's merge commit, before the tag.**
- Triage the Dependabot report on the default branch before the cut: a push on 2026-11-11 reported 116 vulnerabilities (10 critical). This is on top of the two GHSA advisories listed below; Dependabot cannot see BOM-managed versions (see the 0.8 backlog), so also read the resolved tree. **Done 2026-11-13 by task 320 (PR #272): Pillow pinned to 13.0.0 in both manifests, which clears the 116 alerts on merge; the resolved runtime tree (76 coordinates) has no advisory; #246 and #148 closed as superseded. #245, #244, #247 and #229 deferred to 0.9 (D3). Dependabot #276 (pip group, 4 updates) is open and untriaged.**
- Publish the 0.7.x security advisory with the release (draft GHSA-hcfg-rxg3-mgw6, not published; owner decision 2026-11-06: no 0.7.2). Publish it alongside the GHSA-f94h-6mxv-c957 update below.
- Keep `compose.yaml` and `docker/multi-instance/kubernetes.yaml` on the version-literal list, and confirm that no image reference is older than the released version.
- Update the GitHub security advisory GHSA-f94h-6mxv-c957 with the patched version 0.8.0, and with the affected range and workaround as OD-290-2 (b) records (OD-290-2 (b), decided 2026-10-27: every release through 0.7.1 is affected, there is no workaround, and the fix is 0.8.0; 270 and its Outcome no longer exist, so there is no per-tag proof to cite). The advisory was drafted on 2026-10-23, ahead of the release (owner decision H1 (a)). The 0.8.0 release-cut brief owns this, when it is filed; 261 does not. Owner decision OD-270-2 (a), 2026-10-23, stands. Owner decision 4 below: the name-swap leak is a known-gap note in the 0.8.0 CHANGELOG only.

Owner decisions that change behaviour and belong in `docs/migration-0.8.md`: the cutover is non-rolling and refuses `UNSEALED_CLUSTER_STATE` when no other verified 0.8 member is present (cached state is fetched again, pending approvals must be requested again); every serving 0.8 member fences 0.7 members by keeping key `all` = TRUE in `dataprism.oversight` (residuals: anyone on the network can delete the fence until it is re-asserted, and plain-Java embedders using `ToolAdmission.none()` are not fenced); `dataprism.limits.scope-read-budget` stays 100 but now counts source reads; the new defaults are `request-timeout` `PT10S` and `scope-total-read-budget` 50,000; `SKIPPED_OVER_LIMIT` is removed before the 0.9 freeze (Breaking in `CHANGELOG.md` when built); a missing shared link limiter refuses with `MISSING_SHARED_LINK_LIMITER`, which in 1.0 refuses links and page links on every `tcp-ip` or `kubernetes` member (264 deferred). Per-instance weakening of the link limiter is excluded. Added 2026-10-23 (owner decision (b)): a `tcp-ip` or `kubernetes` member needs `dataprism.hazelcast.trusted-network: true` and a private `member.interface` (284), and a plain-Java joining member needs the same declaration (287).

##### Follow-ups carried from landed waves

Lows and Nits found in review that no task has absorbed. For waves 5 to 11 and the older review rounds of PR #154 and PR #163, only items that name a task still open (213, 214, 255, 260, 261, 263, 273 to 283, 304) are kept here; the rest are in the archive at the lines named in each group (`archive/PLAN-2026-11-10.md`). Bullets that name another wave's task keep that task's number.

Waves 5 and 6 (`archive/PLAN-2026-11-10.md:204-260`; waves 7 to 11 are at 270-352):

Follow-ups from wave 5, each belonging to the task named (Lows unless said otherwise):

- 225: one poisoned identity entry is opened twice in one lookup, so `seal.tamper` rises by 2 per bad value (open once and pass it through); the `Sealer.open` allow-list includes `CachingSyntheticValueSource`, so T14 does not guard a change there; `IdentityMapsBoundary` uses the member's registered sealer; `IdentityPoisoningTest` simulates the keyless and other-key writers by bytes on one member; `PrivacyCluster` has no accessor for a keyless solo member's keys; `ServingGate.open()` stays the production default until 227. 213 notes that a cluster-key change empties both maps.

Follow-ups from wave 6, each belonging to the task named (Lows unless said otherwise):

- 254: `SKIPPED_OVER_LIMIT` is still referenced in `load-test/` (README, `SourceStatuses.java`, `ResultAggregatorTest`, `FailureScenarioTest`, `ResponseClassifierTest`, baselines JSON), `docs/plan/perf/baseline-0.7.md` and design docs. 261 corrected the user pages (`docs/architecture.md` and `docs/migration-0.7.md` in the 13a record). `docs/migration-0.8.md` still says "eight status codes"; see 239-12.
- 262:
  - `examples/quickstart-demo/run.sh` is broken on the new stack (it reads `structuredContent.entity`, which sits under `sources`);
  - `README.md` line 105 still says `QUICKSTART_IMAGE_TAG=0.7.0`;
  - `docs/reidentification.md` ends with a stale line, "Using the operator surface end to end in the Compose quickstart is not yet done." (213/261);
  - the 0.8.0 cut must rerun the walkthrough capture and `reidentification-smoke.sh` against the published images, not the build overlay (this joins the release-cut obligations below);
  - `operatorScope` is tied to the server's required scope only by default.
- 227:
  - `PendingSweeper` writes to the approval maps without the gate while the member is not serving;
  - the `Sealer.open` allow-list is per class, not per map;
  - cards do not carry the sealing salt (`Sealer` cannot pre-admit one and is outside 227's Owns), so known members count against the unknown-salt limiter; this supersedes the wave-2 note under 227;
  - the keyless `none` path of `embedded(ClusterMembership...)` uses the wrong code for 127.0.0.2;
  - the keyed `bind.any` read-back is dead code (Hazelcast reads the Config property first);
  - the `close()` generation bump is untested;
  - `listeningAddresses` relies on Hazelcast internals (`HazelcastInstanceProxy.getOriginal().node.getServer()`, `ServerSocketRegistry`) and is only confirmed against 5.7.0; shutting a member down on an internals mismatch is untested;
  - for 213: a hand-built `java-serialization-filter` must include `PartitionStateVersionMismatchException`; use `PrivacyCluster.requiredSerializationFilter()`.

Follow-ups from wave 7, each belonging to the task named (Lows unless said otherwise):

- Audit-sink interrupt bug: done as task 297 (PR #214, 2026-11-06; kept as history). Related, recorded in wave 6: 208's recognisers do not restore the interrupt flag, to protect the audit sink; 297 removed that reason, so see the wave-8 follow-up that reconsiders it.
- 213: an operator page on `CLUSTER_LOCK_TIMEOUT` during scale-out. A member that starts or rejoins can refuse tool calls for about one tick interval after a peer's write, and a start-up card write waits up to Hazelcast's 5 s backup timeout.
- 213 (and a startup warning): source names over 128 characters. Document the limit and add a startup warning for a name that will not fit the `targetSources` schema.
- Keep client-echoed `method`, `id` and `protocolVersion` out of the MCP SDK's WARN lines (stdio and HTTP), and the `roots/list_changed` WARN and ERROR lines that log client-returned roots. Residual of 304, found in PR #232's review.
- 261 and 213: the "Design-doc facts" lines of `tasks/retired/228-signed-leased-pause-document.md` marked "For 213" and "261" are still owed to those tasks, including `ClusterSecurityStatus` gaining two components and `Reason.PAUSE_NOT_FRESH`.
- 287 and 260: both task files now carry a note that 228 inserted `CLUSTER_SETTINGS_MISMATCH` into `PrivacyClusterRefusal`.

Follow-ups from wave 8, each belonging to the task named (Lows unless said otherwise):

- Links for configured sources must be settled before 255; settled by 254's review as task 301 (wave 11).

Follow-ups from wave 9, each belonging to the task named (Lows unless said otherwise):

- 293: a second, different line for the same instance and sequence is accepted. That needs an operator's action, and refusing it needs a new condition.
- 293: the PR does not say which mutant rows were rerun after the design change; verify the remaining unrerun rows (the brief expected two) before relying on the mutation list.
- 299: no test proves the caller's context reaches the scrubber (`DefaultContextOrchestratorDescribeTest.java:77`).
- 299: `lastLinks` is asserted only for `true`; assert `false` as well.
- 299: `ConfiguredSourceDescribeTest.java:215` hard-codes the key id and vocabulary id; read them from `PseudonymisationVersion` instead.

Follow-ups from wave 10, each belonging to the task named (Lows unless said otherwise):

- 253: settle `entityType` in the follow response against the output schema, which lists none (252 builds it in `followFinish`). 253 must use `ToolInput`.
- 252: `SourceLabels.Collision` is not handled in follow (about `DefaultContextOrchestrator.java:780`). The `budget.reserve` wait is unbounded (a Hazelcast lock, for example). Budget `settle` and `continue` tests are missing (mutants M09 and M13). `LinkKeyResolution.java:56` drops a retiring key id's shape silently. Whether the in-process budget should key as the shared one does is open (it holds raw subject keys on the heap, OD-08-15 (a)).
- 300: `EveryToolValidatesInputTest` needed an edit when `follow` landed; it already covers `follow` (met, 242-3). A lone unknown property does not invalidate a call.
- 302: the SDK's ERROR lines may quote tokens; 302 measured the stdio leak, which is filed as 304.
- 254: lazy singletons that are never created are invisible to the raw `FactoryBean` guard.
- 255 (still open after it landed): `LINKS_REFUSED` and `LINKS_ATTEMPTS_EXHAUSTED` (`Metric.java:58-59`) are incremented nowhere in main code.
- 252 and 301: a multi-type configured source has no way to declare a mapping and nothing warns at startup; 301 adds the YAML key and the WARN.

255 follow-ups (PR #236 review, 2026-11-10). Landed as task 255 (`tasks/retired/255-wire-links-follow-and-key-resolvers.md`). Paths are under `data-prism-spring-boot-autoconfigure` unless the file is a test in `data-prism-integration-tests`.

Security, before links are enabled for users:

- 236-D2: `heldKeyIds()` fetches each key from the provider on every health poll and gauge scrape. Root health is often unauthenticated, so ticket this before links ship.
- 236-13: `LinkKeysHealth.java:54`: health details publish the current and held key ids, which are treated as unnameable elsewhere. Publish counts only, or document when-authorized details.
- 236-17: `LinksProperties.java:134`: no ceiling on `invalid-attempt-limit` and no floor on the window, so config can silently disable lockout. Bound both and refuse with `INVALID_LINK_LIMIT`.
- 236-21: `PiiLogScanTest.java:1673`: the scans do not ban the link-key material prefix or `LINK_SCAN_KEY_REF`.
- 236-28: `PiiLogScanTest.java:1599`: expired-link and unknown-key-id refusals never run in the scanned process.
- 236-11: `LinkAttemptLimiterConfigurationTest.java:326`: no row for an application `PrivacyCluster` named `dataPrismPrivacyCluster` or registered as a singleton, which would prove origin, not name, decides.
- 236-D1: a DOWN `linkKeys` result takes the root `/actuator/health` DOWN, contrary to the Javadoc.

Other Lows:

- 236-6: `CatalogWiring.java:229`: `pageLinks` returns null if the codec, keys or gate is missing, so paging silently stops. Refuse startup instead.
- 236-7: `DataPrismPropertiesValidator.java:949`: the paging-vs-link-lifetime check only runs with links on.
- 236-8: `KeyResolverProperties.java:72`: number and duration parsing duplicates `LinksProperties`.
- 236-9: `LinkAttemptLimiterConfigurationTest.java:232`: never checks that the gate holds the application limiter.
- 236-10: `LinkAttemptLimiterConfigurationTest.java:258`: the unresolvable-type row never reaches the preflight's null-type branch.
- 236-12: `LinkAttemptLimiterConfigurationTest.java:352`: the kubernetes "started" rows use a hand-registered singleton, not a declared marker-type bean.
- 236-14: `LinkWiring.java:192`: the refusal code is chosen by message prefix. Use a typed exception.
- 236-15: `LinksConfigurationTest.java:75`: the codec and limiter defaults are never asserted.
- 236-16: `LinksConfigurationTest.java:124`: the lockout test does not assert the refusal code for each attempt.
- 236-18: `PiiLogScanTest.java:1401`: `DESCRIPTION_TEXTS` copies the stub strings; read them from the stubs.
- 236-19: `PiiLogScanTest.java:1429`: the Javadoc says the scans share one run, but `reuseForks=false` boots three.
- 236-20: `PiiLogScanTest.java:1434`: `deleteOnExit` on a non-empty temp dir leaves keystores behind.
- 236-23: integration-tests `pom.xml:129`: `-Dtest` overrides both executions' filters, so a selected class runs twice and the TRACE pin fails there.

Nits: 236-24 (`CatalogWiring` imports and grouping), 236-25 (fully qualified names in `LinkAttemptLimiterConfigurationTest`), 236-26 (the in-memory-default row checks the exception type, not the code), 236-27 (`PiiLogScanTest` `surfaces` is never asserted), 236-29 (the lockout comment says a genuine link is refused, but none is presented).

From our own reviews:

- `LinkWiring.java:241`: the Javadoc still says "in @Order order"; resolvers are ordered by name. Code comment, for an implementer.
- `AuditFilePiiScanTest` `auditFileLinkScanIsNotVacuous`: proves it can fail by hand-injecting a token, not by a production mutation.
- `AuditFieldDispositionTest.java:205`: the comment calls the invalid follow "the one such call", but the run now has two.
- `LinkKeysHealth.java:52`: the `held.contains(currentKeyId)` clause is redundant.
- The preflight `break`s on the first non-marker limiter; with two limiters the outcome depends on order, though it always fails closed.
- A class implementing the marker can override `clusterWide()` to false and still pass the preflight.

304 follow-ups (PR #237 review, 2026-11-10). Landed as task 304 (`tasks/retired/304-no-client-token-in-sdk-stdio-parse-errors.md`). Paths are under `data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp` unless a test.

- 237-4: `ContentFreeMcpJsonMapper.java:113`: the `HashMap` routing-key check applies to every read of that TypeRef. Only `McpSchema$1`, the inbound read, uses it in mcp-core 2.1.0. Scope the check, or comment that it depends on that, for the next SDK upgrade.
- 237-5: `HttpParseErrorLeakTest.java:128`: the E cases assert neither status nor live capture.
- 237-6: `StdioInboundGuard.java:31`: the Javadoc says the guard never holds more than the limit, but the hand-off queue (64 lines x limit) can hold about 1 GiB. Fix the Javadoc and bound the queue; the bound was already a P3 follow-up.
- 237-7: `StdioInboundGuard.java:71`: the reader thread is never stopped and keeps reading stdin after `closeGracefully`.
- 237-8: `StdioInboundGuard.java:178`: pass-three refusals reply with `id:null` even when a valid id was parsed, so the caller waits for a timeout.
- 237-9: `StdioInboundGuardTest.java:176`: a `Thread.sleep(500)` breaks the no-sleeps rule; use a latch.
- Nits 237-10 and 237-11: fully qualified `java.util` names in `StdioInboundGuard`; a test that loops over three inputs should be parameterised.
- `DataPrismMcpServer.java:76`: the Javadoc says the limit is "in bytes: the SDK's own default", but the SDK counts UTF-16 chars. The guard is the stricter of the two; only the wording is wrong.
- For 261: plain-Java embedders get no `McpSdkLogPin` and must keep `io.modelcontextprotocol` at WARN, because the SDK logs the raw HTTP body and forwarded stdio lines at DEBUG (residual of 304); the migration notes must say so.
- Recorded in 304's Outcome, no action: AC6 mutation 1 broke `initialize` (the guard's reader probably kept consuming stdin) rather than demonstrating a leak; the AC5 test builds its own `StdioServerTransportProvider` to get a server from `syncServer`, which AC5's "never by building providers itself" did not intend, and is not a defect.


Waves 1 to 4, PR #154 and PR #163 review rounds, 245 and the 0.7.1 planning review (`archive/PLAN-2026-11-10.md:368-498`):

Follow-ups from wave 1 (landed 2026-10-21), each belonging to the brief named:

- 263: the load-test `consoleTail` reads the whole console file and should be bounded. The kept `.console.log` path is not named in the startup-failure message. The 8 h scope lifetime is not a hard limit because `ScopeResolver` extends it per request, so scope rotation changes what a soak measures; decide whether to keep it. A FAILED source status carries no cause (needs a server change). Server death ends `main` with an uncaught `IllegalStateException`.
- 249 (code landed in PR #207; the documentation is 261's): document "description failed", that "e.g." and "i.e." are refused by rule 3, the rule 4 eager-construction errors, and that the duplicate-source-name refusal has no stable code.

Follow-ups from wave 2 (landed 2026-10-21), each belonging to the brief named (also recorded as dated Context bullets in the briefs):

- 263 (231 is deferred past 1.0): fail if any member logs "Resolving class ... is not allowed" during the soak runs.
- 213: never restore one process snapshot more than once (GCM nonce reuse); the `using()` breaking change and integrator requirements (`requiredSerializationFilter()`, non-empty `trustedInterfaces`, phone-home off, no memcache, REST `HEALTH_CHECK` only); the `trustedInterfaces` value; REST and memcache off; correct "docs list 5.7.2" to 5.7.0.

Follow-ups from the external review of PR #154 (rounds 1–4), 2026-10-23. The owner agreed on 2026-10-23 that once a review round finds nothing Medium or worse the PR merges and the Lows become follow-ups; round 4 reached that bar. Each item belongs to the brief named (also a dated Context bullet in 227, 251, 252 and 261; owner decisions stay here only):

- 222/hazelcast, to 227 (the next `PrivacyCluster` editor): fix `PrivacyCluster` import grouping. Corrected 2026-10-23 (round 5): not "one remaining inline name" but dozens of inline fully qualified names in the tests 222 added, and the `ClusterMembership` constants are misplaced; 227 now owns `ClusterMembership.java` for this. The `INVALID_CLUSTER_INTERFACE` wording added to `docs/configuration.md`, `docs/multiple-instances.md` and the contract register is not fixed: it holds for a keyed solo member only, and a keyless one refuses `MISSING_CLUSTER_KEY`. 284 corrects `docs/configuration.md` and 286 the register (moved from 230, deferred, on 2026-10-23), 213 corrects `docs/multiple-instances.md`.
- 261: `docs/audit.md` must list `REFUSED:LINK_ATTEMPTS_{LOCKED,OVER_LIMIT,CEILING,LIMITER_UNAVAILABLE,OPEN_FAILED}` (PR #157, the 269 revision) and `REFUSED:LINK_KEY_UNAVAILABLE`; it and `docs/tools.md` must list the retryable client code `LINK_CHECK_UNAVAILABLE` (OD-269-5). Closed 2026-10-23: recorded in 261's Context bullets (PR #158).

Follow-ups from the external review of PR #154, round 5 (2026-10-23). Each belongs to the brief named; where a brief's body is not edited here (the bodies of 250, 251, 252, 255, 264 and 269 belong to the 269 revision, PR #158), the item is recorded here only:

- 213: `docs/multiple-instances.md` says `member.interface` is optional and binds every interface. Context bullet in 213.
- 213: the `ClusterMembership` interface component changed from `Optional<String>` to `String`, a breaking change for plain-Java callers with no migration note; add it to `docs/migration-0.8.md` and `CHANGELOG.md`. Context bullet in 213.

Follow-ups from wave 3 (222 and 242 landed 2026-10-22), each belonging to the brief named (also recorded as dated Context bullets in the briefs, except where noted):

- 211, 254, 255: extend `ClusterKeyResolution`'s shared-key check (today the privacy HMAC key only) to the audit key and the link keys. The file, its test and the `CLUSTER_KEY_SHARED` / `NAME_KEY_SHARED` rows are in all three Owns since 2026-10-23 (255's in its Owns lines only).
- 255: supplying `LinkKeys`' held-key-id set and the health signal for a supplier that returns null or throws are done (`LinkKeyResolution.java:56`, `LinkKeysHealth.java:39-55`); no longer open.
- 213 (corrected 2026-10-23): `docs/multiple-instances.md` calls `member.interface` optional and says a member without it binds every interface; correct it. `docs/migration-0.8.md` is not wrong but is missing the `member.interface` entry; add it. The packaged server's environment resolver no longer reads every reference with `System.getenv`: since 222's round-2 fix it refuses a `provider-reference` with `MISSING_KEY_PROVIDER`, and it is registered only when `dataprism.privacy.hmac-key.environment-variable` is set, so a cluster or name key given as `provider-reference` needs a reviewed resolver extension, as the HMAC key does.
- Design: sealed-links obligation 10's timing soak is still unwritten (252 writes it, 263 runs it).

Follow-ups from wave 4 (203, 206, 223, 241, 248 and 269 landed 2026-10-23), each belonging to the brief named (the full lists are in the Outcomes under `tasks/retired/`):

- 210 (landed 2026-10-27, PR #168): the security wiring is explicit. Two beans of either type fail with Spring's `NoUniqueBeanDefinitionException` unless one is `@Primary`; with none and no setting, startup refuses `MISSING_CASE_AUTHORIZER` or `MISSING_CLIENT_ALLOWLIST`. The `AMBIGUOUS_*` and `NON_SINGLETON_*` codes named in 213's follow-up below no longer exist; 213's CHANGELOG and docs list the new codes from 210's Outcome instead.
- 213: `docs/tools.md` lines 69-70 show the old `CaseAuthorizer` constructors; tell implementers to keep case data out of the messages of exceptions they throw; `docs/audit.md` notes that `KEYED_LIMITATION` is not printed when the audit path is unreadable and that a lone non-GENESIS writer carries no key label; `docs/migration-0.8.md` notes that `PrivacyCluster.close()` now stops only an instance it started; the cluster-security design table at line 175 still shows the scope-pause key form. The CHANGELOG entry for 0.8.0 (release cut or 213) lists the new startup refusals `AMBIGUOUS_CASE_AUTHORIZER` and `AMBIGUOUS_CLIENT_ALLOWLIST` (more than one `CaseAuthorizer` or `ClientAllowlist` bean).
- 255: add a pool-size property for `ResolverInvoker` (default 8 concurrent calls). Small, from 241: rename the test `aNullKeyInTheListIsInvalidOutput`; the adapter filters `expand`'s keys to the second source; `PrivacyMetricsTest` does not tie each metric name to its constant.
- Migration note, for 261 or `docs/migration-0.8.md`: upgrading from 0.7 or from any pre-223 build requires wiping the cluster state with a full restart of every member, because raw NUL-keyed entries in maps other than the oversight map are neither detected nor migrated. A cluster whose oversight map still holds such a pause refuses to start with LEGACY_OVERSIGHT_STATE.

Second external review round on PR #163 (Lows and Nits; none scheduled, each fold into the next edit of the named code):

- `AuditChainVerifierCli`:274: readKey failures should report a category.

##### 260 and 261 follow-ups (PR #240 and PR #239, 2026-11-10)

Open items from the external reviews, none blocking. The review text is not on GitHub; it comes from the owner's pasted reviews and the task files (`tasks/retired/260-*.md`, `tasks/retired/261-*.md`).

- 213 owns the `FREE_TEXT_RECOGNISED`, `FREE_TEXT_RECOGNISER_FAILED` and `PAUSE_DOCUMENT_*` codes: they are in task 260's `codes.txt` and on no user page until 213 documents the FREE_TEXT actions and the pause document. `NOT_PENDING` is internal (mapped to `APPROVAL_NOT_PENDING`); `INVALID_SOURCE_NAME` is 0.9.
- 306: captured `follow` and `_links` examples need a links-enabled fixture (successor to the 261 deferral). Moved to 0.9.0 (D4, 2026-11-13).
- 240-6: a reverse SPI check would freeze the 11 public types that are not in register 7.1 (for example `Chainable`, `LinkAttemptLimiter`, `ClusterWideLinkAttemptLimiter`). The register section 7.1 must list those types first; the register is outside 260's Owns.
- 240-8 (Low, `codes.txt:157`): wrong surfaces: `INVALID_TOKEN_REQUEST` is the demo issuer, `EXPIRED` never reaches a client, `PAUSE_DOCUMENT_*` are log prefixes, verifier verdicts are marked not-a-code. Fix: relabel each.
- 240-9 (Low, `MapNamesFreezeTest.java:5`): unused imports `IOException` and `Arrays`. Fix: remove them.
- 240-10 (Low, `MapNamesFreezeTest.java:32`): name fields off the suffix convention are skipped silently. Fix: fail on any unclassified static field valued `dataprism.*`.
- 240-11 (Low, `OperatorEndpointsFreezeTest.java:33`): endpoints are scoped by source directory, so an `/operator` mapping elsewhere is not frozen. Fix: scan all modules for paths starting `/operator`.
- 240-12 (Low, `SingleWordCodesFreezeTest.java:42`): the still-exists direction accepts the word anywhere, comments and resources included. Fix: restrict to the operator file set, comments stripped.
- 240-13 (Low, `SpiSignaturesFreezeTest.java:45`): exact package match drops `audit.sink`, `audit.checkpoint` and `audit.format`, contrary to the Javadoc. Fix: add the packages or correct the Javadoc.
- 240-14 (Low, `SpiSignaturesFreezeTest.java:88`): the `URLClassLoader` is parent-first and never closed; without `-am` stale SNAPSHOT jars can pass a change. Fix: child-first loader in try-with-resources, or fix the Javadoc.
- 240-15 (Nit, `EnumVocabularyFreezeTest.java:77`): `enumsInMainClasses` duplicates `MainSources.mainClasses`. Fix: reuse it.
- 240-16 (Nit, `SpiSignaturesFreezeTest.java:144`): only `Retention` and `Target` are pinned; `Inherited`, `Repeatable`, `Documented` changes pass. Fix: record every `java.lang.annotation` meta-annotation.
- 240-17 (Nit, `ToolsListGoldenTest.java:12`): imports out of order. Fix: reorder.
- 240-18 (Nit, `enums.txt:45`): the `TrustedNetwork.Verdict` note names `MemberInterfaceCheck`; the mapping is in `PrivacyCluster:668`. Fix: correct the note.
- 240-19 (Nit, `operator-endpoints.txt:3`): the error controller is frozen as two identical lines, losing the `produces` difference. Fix: include `produces` in the line.
- 240-20 (Low, `CodeSetFreezeTest.java:50`): concatenated codes need an area passed to `usableId` or `ref`; a new concatenation site with another helper yields no codes, silently. Fix: fail when a file has a SUFFIX match and no AREA match.
- 260 round-1 P3: `OperatorEndpointsFreezeTest` keeps only the first path of a multi-path mapping, records a constant path as empty, and can mistake Javadoc text like "class <word>" before a class-level `@RequestMapping` for a method mapping (none occurs today). `CodeSetFreezeTest`'s E2 pattern misses `enum Code implements X {`.
- 260 notes: unused imports in `MapNamesFreezeTest`; `EnumVocabularyFreezeTest` duplicates `MainSources.mainClasses()`. No automated test ties the operator surface in `codes.txt` to what the controllers return (240-3 added the lines by hand).
- 239-8 to 239-20 (Lows, docs, PR #239 round 3): `docs/audit.md:330` `AUDIT_RETENTION_FAILED` is a fallback, a JSON listing failure is `DELETE_FAILED`, and `ANCHOR` means nothing further is deleted (239-8); `docs/configuration.md:1062` alias rule 5 also forbids a source name, adapter class name or base-url host (239-9); `docs/extending.md:256` an `IllegalArgumentException` from `description()` is `INVALID_SOURCE_DESCRIPTION` rule 4 (239-10); `docs/migration-0.8.md:10` the CHANGELOG `[Unreleased]` omits page approval, the audit-sink thread, the trusted-network rule and the paging codes (239-11); `docs/migration-0.8.md:115` and `docs/tools.md:142` say eight status codes, `SourceOutcome.Status` has seven (239-12); `docs/multiple-instances.md:140` `PUBLIC_MEMBER_INTERFACE` fires only for an address beyond the private and trusted ranges (239-14); `docs/operations.md:101` a link key equal to the cluster or name key is refused with `CLUSTER_KEY_SHARED` or `NAME_KEY_SHARED`, not `LINK_KEY_SHARED` (239-15); `docs/operations.md:122` a retired key id reaches the client as `LINK_INVALID`, `LINK_UNKNOWN_KEY` is only the audit reason (239-16); `docs/tools.md:458` the catalog input schemas lack `additionalProperties: false`, so a typo returns everything (239-17); `docs/tools.md:515` expired, filter-mismatch and catalog-change page-link refusals are uncounted (239-18); `docs/tools.md:642` `items` and `itemsTruncated` leave out deadline truncation and that refused items are dropped (239-19); `docs/tools.md:649` `LINK_ATTEMPTS_EXHAUSTED` also fires when the limiter is full, and a locked caller's valid links are refused but not counted (239-20). 239-13 is under "Untasked security follow-ups".
- 239-21 to 239-24 (Nits): the duplicate CHANGELOG line; the incomplete `KeyResolutionException` list in `docs/extending.md:302`; "1.0" where `docs/operations.md:16` should say 0.8; the "two calls" example at `docs/tools.md:523`.
- Not documented because they do not exist in 0.8: the proposed-0.9 codes `BATCH_*`, `BULK_*`, `INVALID_SOURCE_NAME`, `ENTITY_MODEL_NOT_AVAILABLE`. `load-test/` and the design documents still reference `SKIPPED_OVER_LIMIT`.
- Items elsewhere in this file that name 261 as owner (for example the 228 "Design-doc facts", the 249 documentation list and the plain-Java `McpSdkLogPin` note) were not itemised in PR #239's Outcome; check them against the pages before relying on them being done.

##### Wave 14a follow-ups (PRs #257 to #260 reviews, 2026-11-12)

Lows and Notes from the reviews of 310 to 313 that the owner did not fix in the round. Docs items belong to whoever next edits the named page; 314 owns only `docs/migration-0.8.md` and `CHANGELOG.md`.

- Docs, from 310 (`docs/key-rotation.md`): the wording of the id clash; the "change both keys" route; which of the oldest and newest segment is meant; the audit id check; the collision-counter signal; "two phases" against "three phases".
- Docs, from 311 (`docs/audit.md`, `docs/architecture.md`): "since 0.8" should read "in 0.8.0", because 0.8.0 is unreleased.
- Docs, from 312: `docs/multiple-instances.md` near :510 still has the stale `endScope` sentence; the `docs/plan/design/cluster-security.md` fence and Write rows; Hazelcast clients against "members are verified"; "the map exists" should read "is configured".
- Docs, from 313: `docs/faq.md:52` names the per-source refusal and the blank strings imprecisely; `docs/faq.md:41` is one very long line; the recogniser page needs a note that a recogniser sees a scalar; the allowlist-by-value note.
- Docs: `docs/eu-ai-act.md:47` still calls the audit chain unkeyed.
- Code: `AuditMaintenance.java:28`: the `SEGMENT` pattern misses size-capped `audit-YYYY-MM-DD.NNNN.log` segments, which the verifier (`AuditChainVerifier.java:382`) already handles. What it affects (the pattern is applied to a message at :70) is not confirmed; check before filing.

##### Wave 14b follow-ups (PR #262 review, 2026-11-12)

Docs, P3. None blocks 0.8.0; whoever next edits the page takes them.

- (a) `docs/migration-0.8.md:403`: the `SensitivePatternValidator(SensitiveDataScanner)` removal note sits under "## Added: free-text recognisers". Give it its own `## Breaking:` heading, as the `DefaultContextOrchestrator` constructor has at :264.
- (b) The migration page omits that `DataClassification.FREE_TEXT`, a new constant in an enum released in 0.7.1, breaks integrators' exhaustive `switch` statements that have no default.
- (c) Note: CHANGELOG puts the `ClusterSecurityStatus` change under Breaking, while the migration page heads it "Changed". Make them agree.
- (d) The load-test `SourceStatuses.java` and its tests still list `SKIPPED_OVER_LIMIT`, which product code has removed.
- (e) `docs/multiple-instances.md` "Shared state" says raw subject ids (a 312 docs bug).

##### 263 Part A follow-ups (PR #243 review, 2026-11-11)

Open Lows and Nits from the owner's review (round 4) and our code reviews; none blocks. Security items are under "Untasked security follow-ups". 243-45 to 243-49, 243-52 and the dirty-flag note were fixed by 307 (landed); the rest are untasked. Paths are under `load-test/` (`soak/` for scripts).

- Scripts (Low): 243-16 `common.sh:44` the health probe read has no timeout, and `wait_joined` calls it (use `read -t 5`); 243-17 `fault-clock-skew.sh:25` leaves the member skewed, and the undo names `dc` (restore after `SKEW_SECONDS`, wait ready); 243-18 `probe-startup-refusals.sh:19` infers a refusal from a log line (also require not ready or not running); 243-35 `capture-member-port.sh:64` hides tcpdump read errors (treat 0 packets with a pcap as not run); 243-36 `fault-network-partition.sh:22` silences a failed reconnect and never checks the address (check `ip`, note a restore failure); 243-37 `soak-three-member.json:10` `subjectPool` is ignored in target mode but recorded (remove or refuse).
- Harness code (Low): 243-22 `TargetTokens.java:52` mint ignores `lifetime` (use `lifetime.toMinutes()`); 243-33 `LoadDriver.java:122` `shutdownNow` does not await the sampler, so final rows can duplicate or arrive out of order (`awaitTermination`); 243-34 `ResultAggregator.java:89` no-mix scenarios get empty hourly rows (record under `entityContext` or skip); 243-38 `pom.xml:37` `spring-boot.version` is copied by hand (pass in or check); also `Scenario.java` target mode still records `sourceLatency`, and the pom's empty `-Ddataprism.version=` passes the enforcer (add `<regex>.+</regex>`).
- Docs and workflow (Low): 243-24 `load-test.yml:18` the three-member choice needs the overlay and the workflow does not say (comment: self-hosted with overlay); 243-25 `soak-0.8.md:73` FAILED causes are per run with no per-member "Resolving class" table (add Hour and a per-member table); the README's manual `docker compose up` bypasses `dc`'s running-image guard (`pull_policy: never` still prevents pulls), and kill, start and restart do not go through `up` (a FINDING only); PR #243's Outcome lacks run durations for kill-restart, kill-owner-and-backup and roll-all (AC19 wording).
- Nits: 243-27 `LoadDriver.java:109` inline fully qualified names (import); 243-41 `pom.xml:35` comment orphan break and long line; 243-42 `fault-full-restart-key-change.sh:24` the guard accepts 32 characters where 64 hex are required (validate before stopping); 243-43 `LoadDriverMixedCallTest.java:26` static mutable `CLOCK` (per test); 243-50 `LoadDriver.java:324` mixed `||` and `&&` without parentheses; 243-51 `ResultWriter.java:33` the five-argument overload is used only by tests (remove).

##### 309 follow-ups (PR #249 review, 2026-11-11)

Owner review round 1, "Looks good", nothing Medium or above. Paths are under `data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/`.

- 249-2 (Low): `StdioParseErrorLeakTest.java:221`: the new fail-closed branch has no recorded run showing it fires. Record a run with the old `destroy()` plus the 100 ms lag failing with the new message.
- 249-5 (Nit): `drain` and `joinToEndOfStream` are duplicated across both classes; a shared test helper.
- 249-4 (Low) is settled in the archived task's Outcome (the orchestrator's commit `e55f9010`); no follow-up.

##### 309-F1 follow-ups (PR #251 and #252 reviews, 2026-11-12)

Owner reviews of PR #251 (the plan) and PR #252 (the change), both "Looks good", nothing Medium or above. 252 paths are under `data-prism-integration-tests/src/`.

- 252-1 (Low, **owner to place**; wanted before 0.8.0 if the fixture server is part of the release story): `main/java/io/github/aindriub/dataprism/example/ExampleApplication.java:88`. EOF is seen on raw stdin, before the guard and the SDK have drained queued lines, so `closeGracefully` drops replies to a piped client's last lines, including a final line with no newline. Fix: signal end of input after the guard finishes, or when the SDK session ends, not on the raw read. This probably needs a `data-prism-mcp` hook, so it needs its own task. It affects agent clients through `run-fixture-server.sh`. **Deferred to 0.9 (D5, 2026-11-13).**
- 252-2 (Low, **owner to place**; same release note as 252-1): `ExampleApplication.java:89`. If `closeGracefully` throws, `System.exit` is skipped and non-daemon SDK threads keep the JVM up. Fix: `try { closeOnce.run(); } finally { System.exit(...); }`. **Deferred to 0.9 (D5, 2026-11-13).**
- 252-3 (Low): the AC5 evidence in the PR #252 body quotes no failure message. Quote the `doesNotContain(SENTINEL)` failure for one case.
- 252-4 (Nit): move the nested `EndOfInputSignal` type after the methods in `ExampleApplication`.
- 252-5 (Nit): name the 10 s child-exit limit as a constant (`test/java/io/github/aindriub/dataprism/example/StdioParseErrorLeakTest.java:168`, `StdioSdkLogPinTest.java:101`).
- 251-1 (Low): `docs/agents/stdio.md` should say that the fixture server (`run-fixture-server.sh`) now exits on stdin EOF, which is the MCP stdio shutdown sequence.
- Tester note: 309-F1's scope check used `wc -l | grep -qx 0`, which fails on macOS because `wc` pads its output. Future task Checks should use `grep -c` or `test -z`.

##### 307 follow-ups (PR #254 review, 2026-11-12)

Owner review round 2 of PR #254, "Looks good", nothing Medium or above. Paths are under `load-test/` (`soak/` for scripts, `src/` for Java).

- 254-3 (Low, `soak/build-images.sh:81`): a relabel gives the reused image a new ID, so running containers then mismatch, and the refusal advises a rebuild that cannot help. Say in the refusal and in the README that a relabel is a new build (down, then up).
- 254-4 (Low, `soak/build-images.sh:83`): the relabel build sends `$scratch` (throwaway git objects) as context. Use an empty directory. Also, under a buildx docker-container builder `FROM` cannot see local images, so a reuse is refused rather than relabelled; this fails closed.
- 254-5 (Low, `soak/build-images.sh:47`): a failed inner `git rev-parse --git-common-dir` is hidden; `cd ""` succeeds and the alternates point to the wrong place. Capture it in a variable and check it.
- 254-6 (Low, `soak/common.sh:230`): `resolving_class_count` returns 1, but all five callers use it inside a `note` argument, so the fault scripts still exit 0. Capture first, fail or mark the run, then note. Related (code review, P3): it reads a member's whole `dc logs` into memory; stream it and check `PIPESTATUS` instead.
- 254-7 (Low, `soak/common.sh:109`): the third copy of the `docker ps -aq` service lookup (also `:50` and `:95`), each reporting failure differently. Extract one helper.
- 254-8 (Low, `src/main/java/io/github/aindriub/dataprism/loadtest/LoadTestMain.java:141`): any loopback host counts as the overlay, so a tunnel to a remote cluster records the local build. Also match the overlay port, or use an explicit overlay flag.
- 254-9 (Low, `src/test/java/io/github/aindriub/dataprism/loadtest/LoadTestMainTest.java:28`): no test that the overlay supplier is not called for a remote target, and no `[::1]` case. Use a throwing supplier and add `[::1]`.
- 254-10 (Nit): `soak/README.md:21-25` and `:44` exceed the 120-column wrap, and `soak/build-images.sh:14` is left as a fragment. Rewrap.

##### Untasked security follow-ups from landed work

Open follow-ups on security, privacy, audit or fail-closed behaviour that no open task file covers. They were in the cut text and are carried here so the 2026-11-10 cut did not drop them. Each cites its archive line (`archive/PLAN-2026-11-10.md`). The older items (archive:788 to archive:1524) are carried as filed and have not been re-checked against the current code, except archive:794, which was (`PrivacyExtensionPoints.java:130` still lists `dataPrismToolAdmission` as `REPLACEABLE`, `Guard.NONE`).

- 263 Part A review (PR #243, 2026-11-11), Lows: 243-15 `capture-member-port.sh:84` the full-payload pcap is kept indefinitely, mode 0644 after `docker cp` (delete after counting unless `KEEP_PCAP`, else `chmod 600`); 243-19 `Scenario.java:119` plain-http target URLs are accepted for any host (http only for loopback); 243-20 `ResultWriter.java:48` parameters echo target subjects and URLs and results are uploaded as artifacts (record a subject count); 243-21 `TargetTokens.java:41` a non-JSON issuer answer can carry token text in the exception chain (fixed message, no cause); 243-30 `capture-member-port.sh:63` ids are not trimmed or validated and the control plants only the first id (trim, validate, control per id); 243-31 `capture-member-port.sh:66` four-digit fixture ids match unrelated digits (delimited matches or a long distinctive id). Nits: 243-26 `fault-clock-skew.sh:19` `FAKETIME_LIB` and the offset are spliced into YAML (validate); 243-39 `HarnessKeyResolverAutoConfiguration.java:8` auto-configures if the jar is on the loader path (gate on a harness property); 243-40 `ServerJvmProbe.java:34` leaves a local JMX agent running in the harness server (note in the README or use `jcmd`).
- 239-13 (from 261's review, PR #239): `docs/migration-0.8.md:130`. A bare `*` in `real-names-scopes` is accepted and opts every scope in, with no warning. Warn about it in the migration notes, or file a code follow-up to refuse it.
- archive:309: `TrustedNetwork.java` should reject `0.0.0.0/8` ranges (no match for it in the current file).
- archive:314: a caller without `FOLLOW` still sees source relationships (from 251; an authorisation gap).
- archive:307: `FileAuditCheckpointSink` has the same write pattern as 297's sinks, so an interrupt can close its channel.
- archive:308: decide whether 208's recognisers should restore the interrupt flag, now that 297 removed the audit-sink reason.
- archive:313: `get_entity_context` and `compare_entity_sources` audit the client's `entityType` in shape mode; archive:1145: the `entityType` fields in `OperatorAudit`, `OversightOperatorController` and `ReidentificationService` were never reviewed for the same.
- archive:206: the verifier should report any record from an instance after that instance's `SHUTDOWN` checkpoint, and `AuditRecorder` should refuse `record()` after `close()` (OD-292-5, OD-292-6; `DECISIONS.md:92-93`). This is the audit-forgery gap `docs/architecture.md` points to.
- archive:229, archive:230, archive:231: from 293's review (172-9 to 172-11). `fromJsonLine` does not reject `bootWaived` on other kinds; no test asserts the recorder and retention share one checkpoint sink, and a split sink would make the waiver routine; `bootWaived` needs a caveat that it is only as trustworthy as the store.
- archive:1514: a whole deleted boot is detectable only if that boot wrote a checkpoint past sequence 0; consider a periodic checkpoint soon after boot.
- archive:485, archive:486, archive:487: from 245. Operator read endpoints write `ALLOW` before a read that can then fail; `pause` and `resume` can race and leave state and audit disagreeing; `collect` can spend the approval and then answer `AUDIT_UNAVAILABLE`.
- archive:442, archive:473, archive:478: done. `AuditChainVerifier` now pairs writers by instance id and reports a writer that verifies under no key as broken; under `--allow-unkeyed-writers` only an unkeyed-verifying writer is counted as `unkeyedAllowed` (`AuditChainVerifier.java:459-461`). Kept as history.
- archive:1095: audit verifier wording for unterminated and truncation-equivalent tails should read "unverified, tampering not ruled out"; consider covering a writer's tail with `--checkpoints`.
- archive:1121: a failed JSON-projection purge only logs; it should surface through `auditIntegrity` health.
- archive:1106, archive:1428: the PII log scan misses the tool-level `toolAudit` recorder's events, and `UndeclaredNameToolResultScanTest` never plants an `AuditEvent`, so neither scan proves it can fail on an audit leak.
- archive:1524: no test pins that undeclared object or array values are never descended into when recording dispositions.
- archive:794: `ToolAdmission` and `OversightPolicy` could be `PRIVACY_CRITICAL` with a `COMPETING_BEAN_REFUSAL` guard, since an application `@Primary` `ToolAdmission` can silently disable admission.
- archive:1468: the core `ApprovalStore` has no revoke or expire path for APPROVED requests, so a rolled-back unaudited approve is consumed and looks like a real collect.
- archive:1284: a key-shape scan in the leak validators under `PASS_THROUGH_UNSAFE` (roadmap candidate).
- archive:278: 0.9, an approver cannot see the entity, subject or selection they approve.

#### After 1.0: untrusted-network clusters (deferred 2026-10-23, owner decision (b))

The owner chose option (b) on 2026-10-23. 1.0 supports multiple instances on a trusted network only (D-RM-1 (a) as amended, above). Cluster security for networks the operator does not trust is a post-1.0 milestone. Its briefs are kept as written in `tasks/deferred/`, each headed "Deferred 2026-10-23, owner decision (b)". Their dependencies, waves and base branches are those of the 0.8 plan and must be re-planned when the milestone opens.

- 224: seal approval entries, claims and pending slots; consumed tombstones.
- 226: seal the budget and caller-rate counters; local floors; the counter merge policy. Its unsealed `reserve` and `release` moved to 285.
- 229: rolling cluster-key rotation (card-based write key, sentinel advance, re-seal sweeper). Until then a cluster-key change is a planned full restart, as a name-key change already is.
- 230: Spring binding of `pause-lease`, `clock-skew-tolerance`, `unverified-member.*`, `local-floor.enabled` and `isolation.attestation`; the tamper reporter; link-key check values on member cards. Its readiness contributor and wiring defects moved to 286, and its `INVALID_CLUSTER_INTERFACE` docs correction to 284 and 286.
- 231: the adversarial and integration verification across real servers (T1 to T16).
- 264: the shared Hazelcast link-attempt limiter. Until then core 255's `MISSING_SHARED_LINK_LIMITER` refuses links and page links on joining members (fallback (c)).

What 1.0 does protect on a cluster: member cards and the sentinel (227: a wrong key refuses to start, an unverified member stops service under `refuse`), the sealed, sequenced, leased pause document (228), sealed identity and re-identification entries (225, current key only), keyed map and lock names (223), and the startup guardrails (284, 287). What it does not: approvals and counters are plain values that anyone reaching the member port can read and write. 213 documents both lists in `docs/multiple-instances.md` and `docs/eu-ai-act.md`.

Follow-ups that go with the deferred briefs are in the archive (`archive/PLAN-2026-11-10.md`), marked "deferred", except those kept above. `docs/plan/roadmap.md` (0.8 readiness) and `design/cluster-security.md` (status) carry a dated note of 2026-10-23 pointing here; revisit both in full when the milestone opens.

#### 0.9 starter onboarding plan (filed 2026-10-23)

Goal: adopting Data Prism should feel like adopting any Spring Boot starter. Spec: `specs/2026-10-23-starter-onboarding.md` (owner-agreed design). Eleven briefs, 273 to 283, in waves 15 to 22 above. They change the starter's public contract, so they must land before the 0.9.0 freeze. Every 0.8 brief precedes wave 15, and none of these runs alongside wave 4.

- 273 defaults the low-stakes settings and derives the JWT settings and writer id from Spring. 274 adds the restricted JSONPath library. 275 keys `json-sources:` fields by restricted JSONPath. 276 auto-configures the security chain, the caller-context extractor and Micrometer metrics. 277 makes configured JSON sources work in an embedded starter application. 278 adds `@DataPrismSource`. 279 makes startup refusals name the property, an example and the doc anchor. 280 adds three runnable examples, built in the reactor and never published. 281 updates the reference docs. 282 writes the getting-started guide and the `application.yaml` template. 283 is the public template repository.
- The order is mostly serial because 273, 275, 276, 278 and 279 all add refusal codes, so each edits 260's `freeze/codes.txt` and the contract register. 273, 276, 279 and 281 all edit `docs/configuration.md`.
- 279's `RefusalGuide` coverage test is a new shared exhaustiveness guard. Any brief filed after it that adds a startup code must own `RefusalGuide.java` (insertions only).
- Interactions: 210 (case authorisation and the client allowlist are both required), 249 and 251 (275 keys chainable fields by path), 213 and 261 (281 follows their docs), 288-290 and 271 (PR #161; 275 and 278 build on them), and the pending module-merge decision above (277 and 280 gain an edge to the merge brief if one is filed; every option is compatible with this plan).

Open owner decisions (each brief carries options and a recommendation):

- OD-273-1: the default `dataprism.privacy.scope-lifetime`. Recommended: `PT8H`.
- OD-273-3: **decided (a) by the owner, 2026-10-24.** `dataprism.identity.resolver` defaults to pass-through only when exactly one source is configured. With two or more sources and no resolver configured, startup refuses `MISSING_IDENTITY_RESOLVER`, as today. Found in the external review of PR #162. Mechanism (rewritten 2026-10-26, for a robust fix): a marked default bean, plus a startup guard over the orchestrator's served sources. The earlier definition-time count is removed.
- Topology default (coordinator decision 2026-10-24, second external review of PR #162): `single-node` stays the default.
  - Any `dataprism.hazelcast` member, join, security or trusted-network setting without an explicit `topology: embedded` refuses `CLUSTER_SETTINGS_IGNORED`.
  - `MISSING_CLUSTER_TOPOLOGY` is retired, with a migration line and a CHANGELOG entry (281).
  - The effective topology is logged once at INFO. 273 carries the rows.
- OD-273-2: what happens to `dataprism.security.jwt.*` now that Spring's keys are read. Recommended (a): Spring's keys only, with a coded migration refusal. This is breaking for server operators and touches `server.json`, docker, compose and the quickstart. (b) accepts both and refuses on conflict.
- OD-275-1: existing catalogues. Recommended (a): bare names keep working as `$.<name>`, and `nested-catalogues:` is retired with a coded refusal and a migration note.
- OD-276-1: what the auto-configured chain protects when the host application has none. Recommended (a): every request needs a JWT except the health probe, so the host application fails closed.
- OD-277-1: whether the starter depends on `data-prism-connectors-rest`. Recommended (a): yes. It stays inert until configured, and no artifact is added.
- OD-283-1: when to create the template repository, its name and visibility. Recommended: after 0.9.0 is on Central (the spec said after 0.8.0, but the example needs 0.9 features), named `data-prism-starter-template`, public. Creating it needs the owner's own go.

#### 0.8 backlog (carried from 0.7)

Follow-ups from the 0.7.0 release (none scheduled):

- Sign and attest the per-arch platform manifests, or document the flattening. `imagetools create` flattens the signed per-arch indexes, so the per-arch digests discoverable from the tag carry no cosign signature or attestation. Today only the manifest list is verifiable from the tag; the per-arch index digests are recorded in the publish-image run.
- Pin GitHub Actions by full version or SHA, and add a CI check that every `uses:` resolves. `sigstore/cosign-installer@v4` did not exist and was caught only by the real tag push.
- Add a periodic dependency-tree advisory scan (the 234 follow-up below stands).
- Consumers need `jackson-bom` until Spring Boot manages Jackson 3.1.7 or later.
- The release SBOMs and the Central SBOMs differ because they come from two builds; consider building once.

Follow-ups from the re-capture (not release-blocking): the other fail-closed blocks on `docs/protect-your-own-api.md` (the `MISSING` and `UNSUPPORTED_IDENTITY_RESOLVER` refusals and the rest) were not re-captured against the real stack.


Follow-ups from 235 (found in its review, none scheduled):

- P3: a lease expiring mid-decide can make `committed()` false after a commit that applied, producing a `DENY:APPROVAL_NOT_PENDING` that understates what happened. This existed before 235.
- P3: the `ArchitectureTest` audit-first rule cannot see stores outside the reactor or decorator stores.
- P3: `ReidentificationOperatorController` writes `ALLOW:FORWARDED` under its own correlation id.
- P3: an expired approval is no longer marked `EXPIRED` at the pre-check.
- P3: the `Metric.java` line references in the contract register for the `audit.retention.*` rows are two lines stale (the constants are now at lines 28 to 31).

Follow-ups from the cluster-refusal retry docs fix (0.8, none scheduled):

- `forgetOne` aborts on `CLUSTER_OPERATION_REFUSED` while the other maps are already deleted. It fails closed, but unlike the `CLUSTER_LOCK_TIMEOUT` path.
- Done in 245: `OversightOperatorController.resume` now follows OD-08-14 (a), with a linked `DENY` when the apply fails.

Follow-ups from 234 (none scheduled):

- Dependabot cannot see BOM-managed versions. Add a CI check or a periodic `dependency:tree` audit for runtime advisories, for example OWASP dependency-check or osv-scanner on the resolved tree.
- Add a pip ecosystem entry and a load-test entry to `dependabot.yml`.
- Remove the `jackson-bom` override in the root `pom.xml` once Spring Boot manages Jackson 3.1.7 or later.
- Dependabot PR #133 is superseded by 234; the owner can close it.
- The stale Pillow alerts #1 to #36 should clear on rescan, or the owner can dismiss them as fixed (Pillow is 12.3.0 on `main`).

Follow-ups from 233 (none scheduled):

- Done in 244 (tools) and 245 (re-identification and operator): a denial whose audit write fails answers `AUDIT_UNAVAILABLE` and counts a metric. Original note: a denial whose audit write fails answers `AUDIT_UNAVAILABLE` and counts a metric, consistently across the re-identification, operator and tool paths. Today `ReidentificationService.refuse` and `refuseApproval` and the operator controller's `recordDenial` swallow the failed audit and still refuse. The 233 brief wrongly called the `AUDIT_UNAVAILABLE` answer existing behaviour; the owner waived that acceptance item.
- P3: `ClusterRefusals` in the hazelcast module holds a mutable static logger, which would break under parallel tests.
- P3: the sentinel log test runs at `ClusterRefusals.refused` rather than at a real throw site.

Follow-ups from 199 and 209 (none scheduled):

- Rerun the baseline on a quiet host before 0.8 compares against it, at least the 300-source scenario. Per `docs/conventions.md`, re-measure rather than inherit the contended 0.7 numbers in `docs/plan/perf/baseline-0.7.md`.
- Failure-path load scenarios (timeouts, the read budget, `SKIPPED_OVER_LIMIT`) as a successor task; the 0.7 baseline is all-success.
- The load-test result file should record the cause of a FAILED source read; today it counts one without saying why.
- Task 209: assert "first `SENSITIVE_PATTERN`" in `PatternValidatorWiringTest` and `ConfiguredJsonSourcesPatternValidatorTest`.

Follow-ups from 232 (found in its review and bug hunt, none scheduled):

- Done in 235: `ReidentificationService` re-reads with `find` on an uncertain outcome before auditing DENY.
- `endScope` and `forgetScope` have no production caller. Wire scope end to them, or note in the docs that they are unused.
- The endScope wiring test relies on brittle `arg$1` reflection; replace it.
- A forget during a split is undone by the heal.

Follow-ups from the final review of 216 (all P3):

- `useLockTimeout` is last-store-wins; add a Javadoc note saying so.
- The `PendingSet` CONFLICT sentinel `"\u0001conflict"` could collide with a scope ID. Prefer a null owner, or validate scope IDs.
- With the quorum off (`approval-minimum-members` of 1), the docs should say that the same ID created in two scopes on either side of a split shares one slot after the heal. It fails closed, because both entries are then ambiguous to locate. Put this in `docs/multiple-instances.md` (task 201 or the next doc pass).


Follow-ups found in review, none yet scheduled:

- Task 194: defence in depth. Each leak validator should also redact the keys flagged by the other. `RefusalPaths.redact` covers it today.
- For task 201 (from 193): the `docs/configuration.md` wording of `dataprism.limits.max-response-size` (already in 201's brief); and the per-source `REFUSED:RESPONSE_TOO_LARGE` direction of OD-B18 changes what 193 built, for the 0.9 migration notes.

### Flake fixes 303/305/308 follow-ups

Landed 2026-11-13 (PRs #266, #267, #265; records in `HISTORY-INDEX.md`). Open items from their reviews:

- 264-4: the 305 guard refuses before the SDK's own checks (same as 267-4).
- 264-5: other handlers that convert params synchronously keep the 500.
- 267-3 to 267-11: listed in `tasks/retired/305-http-parse-error-leak-flake.md`, "Follow-ups (from PR #267 review)".
- Content-Length test gap (round-3 P3): the `HttpInboundGuardTest` `post()` helper never stubs `getContentLengthLong`, so mutating `>` to `>=`, or refusing on -1, would pass.
- Move `GUARD_MAPPERS` inside the `HttpTransport` record.
- 303 reviewer suggestion: the new deterministic test never asserts the card was stale before release (`MemberCardTest` ~:1841).
- 303 reviewer note: `aSecondWrapperUsesTheSameMainDocument...` (:1901) has the same weak `pauseDigest() != null` wait.
- 263-1 (Low): follow-up (e) under wave 14b, about "Shared state" in `docs/multiple-instances.md` saying raw subject ids, names no line; cite it or drop it.
- The 404 variant of the 305 flake (`refused:152`) is unexplained; the `known-flakes.md` row stays `open`.
- Direct `transportProvider()` mounts bypass the guard (accepted residual; four tests and any integrator). Check the external demo project. **D6 (2026-11-13): switch the external demo project to `servlet()`, or retire it; a 0.9 follow-up, not a 0.8 blocker.**
- Docs: CHANGELOG `[Unreleased]` may need a line for the 305 behaviour change. A malformed `tools/call` or `initialize` now gets 400 with -32602, and no MCP HTTP error body carries a stack trace. **Done by 315 (PR #270).**

### 0.8.0 wave 1 follow-ups (315 to 320, PRs #270 to #275, 2026-11-13)

All Lows and Nits; none blocks 321. Task ids refer to `tasks/retired/`.

- 315: add a `FREE_TEXT` Breaking line to the CHANGELOG; the `FREE_TEXT` example and the runtime-failure note.
- 316: the `:142-149` citation on the page; T4 omits the cold-start gate.
- 317: the key-rotation line wrap (`key-rotation.md:29-30`, `:151`); "From 0.8" in `faq.md`; `extending.md:984` says only "non-blank string". AC6 is accepted in its by-value reading (D7).
- 318: inline fully qualified names; one long message line.
- 319: the test depends on the "could not list" wording; inline fully qualified names; duplicated set-up.
- 320: Pillow 13 needs Python 3.11 or later, but the Outcome says 3.10; the #244/#245 row wording; the generator READMEs.
- Integration tests: make `PiiLogScanTest` and `McpHttpEndToEndTest` robust under `-Dtest=`, which overrides the surefire default-test and link-scans split at `data-prism-integration-tests/pom.xml:113-149`. Local only; CI is unaffected.
- PR #269 Lows: stale #268 references; the 321 nothing-published check; the `test -z` greps; 315's "stack" check.
- Dependabot #276 (pip group, 4 updates) is open and untriaged. #245, #244, #247 and #229 are deferred to 0.9 (D3).

### Contract register successor tasks (0.8 and 0.9 follow-ups, from task 200)

The register (`docs/plan/design/contract-register.md`, section 16) names six successor tasks. Ids are not yet allocated. The planner writes the briefs; none is a task file today.

- S1 (0.8, before the 0.8 briefs): correct the stale rows in the design documents (`sealed-links.md` closing and relation tables, `key-resolvers.md` closing and failure tables, `cluster-security.md` lock-timeout rows) and apply the OD-200-4 length caps, OD-200-3, -6, -7, -8, -9 and -10 renames. Depends on the decisions above, now made.
- S2 (0.7, done): task 233 (OD-200-2 and OD-200-14), landed 2026-10-20.
- S3 (0.8): document the shipped codes the shipped pages lack ("Docs: no" in register section 3) (`CLUSTER_OPERATION_REFUSED` is already in `docs/tools.md`). Part of this lands in 201.
- S4 (0.8, before the 0.9 freeze): add the freeze tests of register section 15 that need no decision: code extraction and frozen lists (run E1c as well as E1), golden `tools/list`, map names, operator endpoints, SPI signature snapshot. Its frozen list must classify the five enums whose exits were not traced.
- S5 (done): the D-070-3 line in `PLAN.md` is corrected by the OD-200-1 decision, recorded 2026-10-20.
- S6 (0.9, before the 0.9 briefs): design the 0.9 contract with no name yet (register section 6.5): per-source role authorisation (D-RM-11), audit-entry size handling, and the `compare_entity_sources` review.


### 0.7.0 follow-ups found in review

- A raw `FactoryBean` (no generic type) with `allowEagerInit=false` escapes both the `RequestLimits` preflight and the `PrivacyPolicyResolver` guard (`Preflights.java:50-56`). Fix both together.
- Existing Hazelcast defects confirmed by the review of task 192. The first two are decided (D-070-14 to D-070-17) and landed as task 202; the last two remain unscheduled:
  - approval consumption relies on `IMap.lock`, which is unsafe under split brain (`HazelcastApprovalStore.java:151-165`, `:193`), fixed in task 202;
  - locks have no timeout (`HazelcastScopeBudget.java:44`, `HazelcastCallerRateLimiter.java:32`, `HazelcastApprovalStore.java:59`, `:90`, `:151`, `:193`), fixed in task 202;
  - Jet and phone-home are not forced off for a supplied `Config` or instance (`PrivacyCluster.java:83-102`, `:131-152` against `ClusterMembership.java:91`);
  - Hazelcast is pinned to 5.7.0 while the docs list 5.7.2 (`pom.xml:74`).
- 0.8 follow-ups from task 202: writes in `HazelcastOversightState`, `CachingSyntheticValueSource`, `ScopeIdentityIndex` and the `putIfAbsent` in `HazelcastApprovalStore.create` still block unbounded on a lock someone else holds; budget and rate maps still use the default merge policy; once values are sealed the consumed-wins rule must move to a tombstone, because a merge policy cannot read sealed payloads; a crash between `replace` and `setTtl` leaves an entry without expiry until eviction (the stored `expiresAt` refuses it on every use, so not fail-open).
- NOTEs for the 0.8 soak work (from the 196 harness review): the 12 h token lifetime and 8 h scope lifetime limit how long a run can last; throughput includes the in-flight drain after the deadline; a slow non-timeout failure, such as the 10 s connect timeout at high concurrency, is counted but not timed; and a multi-connector server would fail the health log match.
- Mutation survivors (task 195): strengthen tests before the 0.9 gate turns on. Validation is weakest at 68%, mostly the sensitive-data scanner's check-character functions (`ibanChecksumValid`, `luhnValid`) and its tree walk; core's `JsonTreeScrubbingEngine` survivors (`scrubObject` 155 and 189, `scrubNestedObject` 286 and 302) come next. Candidate 0.8 task. The ranked list is in the retired task 195 outcome.
- After 0.7.0 reaches `main`, re-run `mutation.yml` through `workflow_dispatch` to pin the 0.9 baseline; the first local scores (core 81%, pseudonymisation 86%, validation 68%, orchestration 79%) are not CI numbers.
- Test environment: `SourceTreeJacksonParityTest` depended on whether `/tmp/x` existed; fixed on `plan/0.7.0`. Check other tests for reliance on scratch paths.

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

## Release checklists

The 0.4.0, 0.4.1 and 0.5.0 release checklists are closed and moved verbatim to `archive/PLAN-2026-11-10-wave-13a.md`.

## Closed work, moved to the archive

Everything not kept above is in `docs/plan/archive/PLAN-2026-11-10.md`, a byte-for-byte copy of this file at `3dbc3324`. Landed work is in `HISTORY.md` (scan `HISTORY-INDEX.md`). Decisions and accepted residuals that lived only in the cut text are in `DECISIONS.md`. Cut sections, by line in the archive: the 0.7.0 state and design outcomes (618-684); the EU AI Act plan and releases 0.4.0 and 0.4.1 (732-971); release 0.5.0 and its open owner questions and lettered follow-ups (997-1176); the 0.6.0 plan and its owner decisions (1199-1536); tasks 09 to 58 and the 0.2.0 release (1537-2135); the v0.3.0 plan, discoverability, site polish and task 91 (2136-2974).

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

- 0.8.x byte-cap task (decided OD-291-2 (b), `DECISIONS.md:158`, archive:187): still to be filed. It depends on 291 (landed). On landing, add the byte cap to the docs' memory clause; the clause stays, since neither cap is a memory limit.
- Jackson 3 port (scrubbing module, mcp-json-jackson3, flip the enforcer ban) before a Boot release removes Jackson 2 support. D-139-A keeps 0.5.0 on Jackson 2.

The "Found" lists from v0.3.0 wave 1 and tasks 81, 84, 82, 77 and 69 (2026-09-22 and 2026-09-23) moved verbatim to `archive/PLAN-2026-11-12-wave-14a.md` (block 1). Read them before touching the files they name.


The "Found" lists from tasks 75, 66, 60, 58, 53 to 57, 47, 41, 35, 31, 28, 29 and 20 (2026-09-15 to 2026-09-23) moved verbatim to `archive/PLAN-2026-11-13-wave-1-0.8.0.md` (block 1). Read them before touching the files they name.

Found during `/verify` on tasks 21-24, 18 and 25 (2026-09-14): moved verbatim to `archive/PLAN-2026-11-11-wave-13b.md` (block 2). None blocked anything and no open task owns those files.

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
- **Public-API catalogues (future idea, owner 2026-10-23).** Ready-made `json-sources:` catalogues for public APIs, kept as separate associated repositories, not as modules of this one. Recorded from the starter-onboarding spec; nothing is planned.

## Not doing

Recorded so they are not re-proposed. Each is a deliberate scope boundary from
`docs/design-review.md`, not an oversight.

- Probabilistic entity matching. It sits behind `IdentityResolver` and is a
  different product.
- Any MCP tool that opens, selects or extends a privacy scope. That is the one
  lever that breaks scope isolation.
- Any MCP tool that re-identifies a pseudonym.
- Caching raw source responses.
