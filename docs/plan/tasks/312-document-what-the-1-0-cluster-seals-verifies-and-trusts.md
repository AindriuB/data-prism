# 312 — Document what the 1.0 cluster seals, verifies and trusts

Split from 213 on 2026-11-12 (owner agreement of that day: 213 becomes four parallel docs tasks, 310 to 313, and one integrating task, 314). 213 is retired at `tasks/retired/213-document-key-rotation-and-1-0-controls.md`, which maps each of its criteria to its new home.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main`
**Depends on:** none. Prerequisites, all merged: 222, 223, 225, 227, 228, 255, 284, 285, 286, 287.
**Wave:** 14a (parallel with 310, 311, 313; 314 follows)
**Owns:**
- docs/multiple-instances.md
- docs/eu-ai-act.md *(the shared-cluster paragraph only, :122-125 at filing)*
- docs/plan/design/cluster-security.md *(the 1.4 `dataprism.oversight` table only, :173-182 at filing)*

## Goal
`docs/multiple-instances.md` says exactly what a 0.8 cluster seals, verifies and leaves open, what the operator must assert and isolate, and what an operator sees when members start, rejoin or fill the pause document. `docs/eu-ai-act.md` stops promising a pause behaviour 228 removed.

## Rules for every claim
- Check every claim and example against current code on `origin/main` and the merged Outcomes (HISTORY.md entries via `HISTORY-INDEX.md`, the retired briefs), never against the October 213 brief. 228, 255, 260, 261, 263 (Part A), 304, 307, 309 and 309-F1 merged after it was written. Nothing proposed or deferred (224, 226, 229, 230, 231, 264, 295) is presented as shipped.
- Write "supports", never "compliant" or "compliance". Make no claim that a deployment meets a legal duty.
- Never call a cluster "secured" or "protected" by the trusted-network guardrail.
- Link only to pages and anchors on `origin/main` or in this task's Owns. Do not link to anything 310, 311 or 313 adds (`key-rotation.md` included).

## Context
- Stale on `origin/main` (checked 2026-11-12): `multiple-instances.md:93` calls `member.interface` optional; `:105` says a `tcp-ip` or `kubernetes` member without it binds every interface; `:128` (`INVALID_CLUSTER_INTERFACE`) says a `hazelcast.*` address property refuses with it, which holds for a keyed solo member only (a keyless one refuses `MISSING_CLUSTER_KEY`); `:206-217` still describes raw subject ids in keys and lifting a pause by deleting a flag. `:23` holds 284's insertion. `eu-ai-act.md:122-125` says losing a pause flag's owner and backup reopens the paused path.
- From 222: `member.interface` is required on `tcp-ip` and `kubernetes` (`MISSING_MEMBER_INTERFACE`), bounded (`UNBOUNDED_MEMBER_INTERFACE`) and valid (`INVALID_CLUSTER_INTERFACE`); `docker/multi-instance/kubernetes.yaml` sets `DATAPRISM_HAZELCAST_MEMBER_INTERFACE` to a placeholder. The packaged server's environment resolver resolves `environment-variable` references only, refuses a `provider-reference` with `MISSING_KEY_PROVIDER`, and is registered only when `dataprism.privacy.hmac-key.environment-variable` is set; a cluster or name key given as `provider-reference` needs a reviewed resolver extension.
- PLAN.md follow-ups for 213 (wave 2, :234; wave 6, :136): never restore one process snapshot more than once (GCM nonce reuse); a plain-Java embedder using `PrivacyCluster.using()` must supply `requiredSerializationFilter()` (a hand-built `java-serialization-filter` must include `PartitionStateVersionMismatchException`), non-empty `trustedInterfaces`, phone-home off, no memcache, REST `HEALTH_CHECK` only; state the `trustedInterfaces` value. The page must say Hazelcast 5.7.0 (no "5.7.2" remains on `origin/main`; keep it so).
- PLAN.md:141 (wave 7): a member that starts or rejoins can refuse tool calls with `CLUSTER_LOCK_TIMEOUT` for about one tick interval after a peer's write, and a start-up card write waits up to Hazelcast's 5 s backup timeout.
- "For 213 (operations)" in `tasks/retired/228-signed-leased-pause-document.md:729-731`: the pause-document cap of 8,176 non-`all` elements with no tombstone GC in 0.8 (295 deferred), refusal with `CLUSTER_OPERATION_REFUSED`, recovery by planned full restart, avoid scripted pause/resume churn; `endScope` refused with `OVERSIGHT_UNAVAILABLE` until the first pause document exists (about one lease after a full start); the rejoin refusal above. Its :732 migration fact is 314's.
- PLAN.md:268: `PAUSE_DOCUMENT_FRESH`, `PAUSE_DOCUMENT_LAPSED`, `PAUSE_DOCUMENT_MAINTENANCE_FAILED`, `PAUSE_DOCUMENT_WRITE_DID_NOT_LAND` are WARN log prefixes (`PauseDocument.java:779-782, :1213, :1622`), on no user page. Document them as log lines, not client codes.
- PLAN.md:256: `cluster-security.md` 1.4 still shows the key form `all`, `tool:<name>`, `<scope id>` NUL `paused` (now :177). Correct it to the keyed names (223) and the fence that 228 leaves in that map.
- The 1.0 protect / not-protect lists: PLAN.md:369. Readiness: 286's `clusterSecurity` contributor.

## Acceptance
- [ ] AC1 (213 AC15) `docs/multiple-instances.md` opens its cluster section with exactly this text, verbatim, which is also what 284 inserted: "Multi-member: trusted network only. Approvals and counters are unsealed, and `trusted-network: true` is the operator's assertion that only Data Prism members can reach the member port." It replaces 284's insertion; the two must not differ. It then says exactly what is and is not protected in 1.0:
  - Sealed: the pause document (228). A deleted, forged or replayed document cannot lift a pause; an absent or stale one means everything is paused.
  - Verified: members (227). A member with the wrong key refuses to start (`CLUSTER_KEY_MISMATCH`). A joined member without a valid card is counted, and under the default `refuse` every instance stops serving and readiness goes `DOWN` (286) until it leaves. Data Prism cannot evict it.
  - Sealed: identity and re-identification entries (225). Values are envelopes under the cluster key; subject ids appear in the cluster only inside them, and forged entries never change a returned value. Deleted re-identification entries are lost for good. They are sealed under the current key only: rotation (229) is deferred, so a cluster-key change is a planned full restart that empties both maps, and pseudonyms issued before it can no longer be re-identified.
  - Not sealed: approvals and their claims and pending slots, and the budget and caller-rate counters. Anyone who can reach the member port can read them and can write them. Map keys are keyed names (223), so a reader learns no scope or subject from a key, but values are plain.
  - Required on `tcp-ip` and `kubernetes`: `dataprism.hazelcast.trusted-network: true` and a private `member.interface` (or a `member.trusted-ranges` entry), with the refusals `MISSING_TRUSTED_NETWORK`, `PUBLIC_MEMBER_INTERFACE` and `INVALID_TRUSTED_RANGE` (284), and the plain-Java declaration (287). The member port must still be isolated by the deployer (firewall, NetworkPolicy or a private network), because member traffic is not encrypted.
  - Links and page links are refused on joining members (`MISSING_SHARED_LINK_LIMITER`, 255), so `list_sources` answers on one page there.
  - Cluster security for untrusted networks is planned after 1.0, and the page says so, without dates.
- [ ] AC2 (213 AC13, first half) `docs/multiple-instances.md` no longer calls `member.interface` optional or says a member binds every interface without it, and its `INVALID_CLUSTER_INTERFACE` row states the address-property refusal for a keyed solo member only, with `MISSING_CLUSTER_KEY` for a keyless one.
- [ ] AC3 (213 AC18, first half) Wherever `docs/multiple-instances.md` documents `trusted-network`, it is the operator's isolation assertion, in 284's words: only Data Prism members can reach the member port; it is not inferred from a private address. The Kubernetes member-port NetworkPolicy is shown as what makes the assertion true there, with its limit: it admits any pod labelled `app: data-prism`, so "trusted" means anyone who can create pods in that namespace, and the page recommends a dedicated namespace. The assertion holds only with a network plugin that enforces NetworkPolicy (flannel and kind's default do not) and with no `hostNetwork` pods in the namespace, which bypass it. The multi-instance Compose file is a local demo only: the assertion holds only on a Linux Docker engine where cross-network routing to the member port has been checked, and not on OrbStack or Docker Desktop, which may route to it across networks.
- [ ] AC4 (213 AC19) The trusted-network guardrail is described as what it is: an operator assertion plus a bind and advertised-address check (284, 287). The page says it does not stop an untrusted host from joining and does not make a cluster safe on a network the operator does not control. No page calls the cluster "secured" or "protected" by it.
- [ ] AC5 (213 AC16) `docs/eu-ai-act.md`'s shared-cluster paragraph no longer says that losing a pause flag's owner and backup reopens the paused path. In one or two sentences it says that the pause is sealed and members are verified, that identity and re-identification entries are sealed, that approvals and counters are not, that a cluster is supported on a trusted network only, and it links to `docs/multiple-instances.md` for the detail.
- [ ] AC6 `docs/multiple-instances.md` has an operations section covering: the start/rejoin `CLUSTER_LOCK_TIMEOUT` window and the 5 s card write; the pause-document cap, its refusal, its recovery and the churn warning; `endScope` refused until the first pause document exists; and the four `PAUSE_DOCUMENT_*` log lines and what each means.
- [ ] AC7 `docs/multiple-instances.md` states the plain-Java `using()` requirements and the snapshot-restore rule listed in Context, the `trustedInterfaces` value, that REST and memcache are off, and that a cluster or name key given as `provider-reference` needs a reviewed resolver extension.
- [ ] AC8 `docs/plan/design/cluster-security.md` 1.4 shows the map's current key form and contents; nothing else in that file changes.
- [ ] AC9 The PR description's Outcome has a `Facts for 314` list in the format of `tasks/314-*.md` ("Hand-off format"): every cluster property, refusal, record or constructor change, and behaviour change documented here, or "none".
- [ ] AC10 (213 AC22) `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Checks
- `docs-site` command — AC10
  run: `mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns (files only; line limits are read)
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/multiple-instances\.md|docs/eu-ai-act\.md|docs/plan/design/cluster-security\.md')"`
- `verbatim-opening` command — AC1
  run: `test "$(grep -c -F 'Multi-member: trusted network only. Approvals and counters are unsealed, and `trusted-network: true` is the operator'"'"'s assertion that only Data Prism members can reach the member port.' docs/multiple-instances.md)" = 1`
- `pause-log-lines` command — AC6
  run: `for c in PAUSE_DOCUMENT_FRESH PAUSE_DOCUMENT_LAPSED PAUSE_DOCUMENT_MAINTENANCE_FAILED PAUSE_DOCUMENT_WRITE_DID_NOT_LAND; do grep -q -w "$c" docs/multiple-instances.md || exit 1; done`
- `no-stale-claims` command — AC2, AC5
  run: `test -z "$(grep -n -i -E 'binds \*\*every\*\*|member\.interface` \| Optional|reopens the paused path' docs/multiple-instances.md docs/eu-ai-act.md)"`
- `no-compliance-wording` command — rules
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs | grep -E '^\+[^+]' | grep -i -E 'complian')"`
- `cluster-claims` read — AC1, AC3, AC4, AC6, AC7
  how: reviewer checks each list item against the named Outcomes and code (`PrivacyCluster`, `PauseDocument`, `MemberCards`, `TrustedNetwork`), and that no sentence calls the cluster secured or protected by the guardrail.
- `line-limits` read — AC5, AC8
  how: the diffs of `eu-ai-act.md` and `cluster-security.md` touch only the lines named in Owns.
- `hand-off` read — AC9
  how: the PR body has the list, in the format, with a source for each fact.

## Out of scope
- `docs/key-rotation.md` (310: the rotation procedures themselves), `docs/audit.md` and `docs/architecture.md` (311), `docs/extending.md`, `docs/tools.md`, `docs/faq.md` (313), `docs/migration-0.8.md` and `CHANGELOG.md` (314).
- `docs/operations.md` (its "Running more than one instance" section is not 213's).
- 239-14 (`multiple-instances.md:140`, `PUBLIC_MEMBER_INTERFACE` wording) and the quorum-off split note (PLAN.md:454): not earmarked for 213; untasked.
- The rest of `docs/plan/design/cluster-security.md`; `docs/data-protection.md` (214); code; `docs/configuration.md`; version cuts and publication.

## Review round 1 — findings

Reviewed HEAD 6f90a838b7f312a0bdfd2ecb98a4525dd43449dc. Owner decision 2026-11-12: these P2s are fixed in this round despite the docs Mediums rule, because each overstates what the cluster protects. The P3s (Hazelcast clients and "members are verified"; the cluster-security.md Write-row consequence) and the NOTE are follow-ups.

- [P2] docs/multiple-instances.md:21-22 — the opening warning says sealing makes tampering with the pause document, identity and re-identification entries "detectable and ineffective". Deletion works: a deleted re-identification entry is lost for good and a deleted or overwritten pause document pauses everything (the page's own :62-64, :278-281). Reword: forgery fails; deletion still destroys re-identification entries and denies service.
- [P2] docs/multiple-instances.md:111 — the fence row says `all` in `dataprism.oversight` "keeps a 0.7 member from joining. It holds no pause". Wrong: a 0.7 member joins and reads everything as paused; the write is best effort and anyone on the network can delete the fence until it is re-asserted (PauseDocument.java:1394-1408; docs/plan/design/cluster-security.md:30; DECISIONS.md:127). Also reconcile ":118 Every map key is a keyed name" with this literal `all` key.
- [P2] docs/multiple-instances.md:115-117 (and :124-125, :133-134) — the table gives the approval maps their 0.7 names; the 0.8 maps are `dataprism.approval.s1`, `dataprism.approval.id.s1`, `dataprism.approval.pending.s1` (PrivacyCluster.java:125, :132, :138); the unsuffixed names are pre-0.8 maps checked only for leftovers (:150-155). Add `dataprism.approval.consumed.s1` (PrivacyCluster.java:143; unsealed, never evicted, ApprovalMaps.java:43-49) to the table, the "Not sealed" list (:70-74) and the eviction paragraph (:128-134). The eviction paragraph must not imply the fence map `dataprism.oversight` is evicted (OversightMaps.java:26-27 never evicts it).
- [P2] docs/multiple-instances.md:125-126 — unsealed approval values carry more than "the principal, the approval id and the scope": the encoded record (HazelcastApprovalStore.java:43-44, :787-792) also holds the tool, client id, binding fingerprint, namespace, synthetic value (pseudonym), free-text purpose, case id and approver. State what a reader at the member port can see.
- [P2] docs/multiple-instances.md:375-379 — the environment-variable resolver is credited to "the starter"; it exists only in the packaged server (data-prism-server/.../ServerIntegrationsConfiguration.java:39-60). The starter and autoconfigure define no `HmacKeyReferenceResolver` bean, so a starter user's `environment-variable` reference is not resolved and startup refuses. Say so.
