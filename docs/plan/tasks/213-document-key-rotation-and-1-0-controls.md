# 213 — Document the 0.8 controls, key rotation and migration

Renumbered from 198 on 2026-10-19. Reconciled with the pre-0.9 freeze; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** the 0.8 integration branch once all dependencies have merged; otherwise `origin/main` after those merges.
**Depends on:** 208, 210, 211, 261, and the sealed-cluster tasks 218 to 231 (231 depends on all of 218 to 230; their Outcomes and 231's fact list are this task's sources), and 264 (the shared link limiter for `docs/multiple-instances.md`; if 264 is cut, remove this edge and document the `MISSING_SHARED_LINK_LIMITER` refusal instead, which on a joining member covers both links and page links). Superseded tasks 204, 205 and 212 are not implementation prerequisites.
**Wave:** 14 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- docs/key-rotation.md (new)
- docs/migration-0.8.md (extend; created by 222's review round)
- mkdocs.yml (nav and llmstxt entries for both new pages)
- docs/audit.md
- docs/architecture.md *(added lines only: new dated decisions and explicit supersession pointers)*
- docs/multiple-instances.md
- docs/reidentification.md
- docs/faq.md (free-text answer)
- docs/extending.md (case-authoriser and recogniser SPI sections)
- docs/tools.md (constructor examples affected by these tasks)
- docs/log-shipping.md (segment names)
- docs/use-cases/gdpr-data-minimisation-mcp.md (rotation-procedure statements)
- CHANGELOG.md (`[Unreleased]` only)

## Goal
Document controls when they ship in 0.8, before the 0.9 freeze. Keep 1.0 a stability release. This replaces the original assumption that new APIs and breaking changes would first ship in 1.0.

## Acceptance
- [ ] Every claim and example matches merged code and the recorded Outcomes of the prerequisite tasks. No proposed property or control is presented as shipped.
- [ ] Numbered pseudonymisation-key rotation procedure covers existing pinned scopes, new scopes, re-identification, and retaining historical keys for exposure tracing. Preserve the recorded unresolved coupling question without inventing its answer.
- [ ] Separate procedures cover the audit key, rolling cluster encryption keys, non-rolling name key, and link keys if implemented. Follow the approved cluster/link designs and actual code. Never derive the name key from the pseudonymisation key or describe plaintext re-identification as the approved alternative.
- [ ] `docs/migration-0.8.md` states the OD-215-1 (a) cutover: non-rolling, every 0.7 member stopped before any 0.8 member starts, a 0.8 member that finds an entry in a pre-0.8 map refuses with `UNSEALED_CLUSTER_STATE`, and the compatibility mode is withdrawn. It says the cluster state is cached data that is fetched again; pending approvals are lost and must be requested again; the re-identification index starts empty. It also records the behaviour change that `dataprism.limits.scope-read-budget` (still 100) now counts source reads, so a subject gets about 12 eight-source calls rather than 100.
- [ ] The cluster procedure describes the incompatible old/new map formats and a controlled cutover; do not present a mixed 0.7/0.8 rolling upgrade that resets read counts as safe. Explain name-key changes and re-identification store loss.
- [ ] Audit docs cover current and retired HMAC keys, CLI flags, keyed/unkeyed verification, size-capped segment names and retention. Explain transition from existing unkeyed logs: keyed retention refuses those writers; preserve and verify the old trail separately rather than silently skipping or deleting it.
- [ ] Repeat the limits: unkeyed chains do not resist rewriting by the operator; keyed chains do not resist key holders; truncation needs checkpoints under separate custody. Record D-1.0-4's partial reversal of the old keyed-chain rejection, preserving its operator-threat reasoning.
- [ ] Document case-authorisation refusals, client allowlisting and its lack of egress control, FREE_TEXT actions, recogniser ordering and fail-closed failures, and the scanner's depth cap. Recognisers must bound their own runtime. The existing Spring pattern scan first runs in 0.7 (209); recognisers arrive in 0.8 (208).
- [ ] Architecture records distinguish the October 15 imported decisions from the October 19 reconciliation. Explicitly supersede isolation-only cluster claims with the approved sealed-state design and its qualified protection claim.
- [ ] `docs/extending.md` shows how a standalone server loads a `CaseAuthorizer` extension and how to supply a recogniser. Constructor examples compile against the merged APIs.
- [ ] Migration and changelog cover all changed public constructors, map formats, startup refusals, properties, audit behaviour, and recogniser functionality. Use `[Unreleased]`; no version bump, tag or publication.
- [ ] Replace the old statement that no rotation procedure exists with a link to the procedure. Segment examples still match `audit-*.log`.
- [ ] Wording says “supports”, never “compliant” or “compliance”, and makes no claim that a deployment meets a legal duty.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass, with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Context
- From wave 2 (2026-10-21), for the operator docs and migration notes: never restore one process snapshot more than once (GCM nonce reuse). The `using()` breaking change and what integrators must supply: `requiredSerializationFilter()`, non-empty `trustedInterfaces`, phone-home off, no memcache, REST `HEALTH_CHECK` only. State the `trustedInterfaces` value and that REST and memcache are off. Correct "docs list 5.7.2" to 5.7.0.
- From 222 (2026-10-22, corrected 2026-10-23 after round 5 of the PR #154 review): `docs/multiple-instances.md` still says `member.interface` is optional (:91) and that without it a `tcp-ip` or `kubernetes` member binds every interface (:103); `docs/migration-0.8.md` has no `member.interface` entry at all. For 0.8 it is required on `tcp-ip` and `kubernetes` (`MISSING_MEMBER_INTERFACE`), must be bounded (`UNBOUNDED_MEMBER_INTERFACE`) and valid (`INVALID_CLUSTER_INTERFACE`), and `kubernetes.yaml` now sets `DATAPRISM_HAZELCAST_MEMBER_INTERFACE` to a placeholder pod network. Correct the first page and add the entry to the second. Also document the packaged server's environment resolver as it is since 222's round-2 fix: it resolves `environment-variable` references only and refuses a `provider-reference` with `MISSING_KEY_PROVIDER`, and it is registered only when `dataprism.privacy.hmac-key.environment-variable` is set. A cluster key or name key given as `provider-reference` therefore needs a reviewed resolver extension, as the HMAC key does.
- From 222 external review (2026-10-23): `docs/migration-0.8.md` and its `mkdocs.yml` nav entries (both lists) already exist, created by 222's review round with the Breaking note on `HmacKeyReferenceResolver.resolveProviderReference`. Extend the page; do not recreate it, and keep that section.
- From the external review of PR #154, round 5 (2026-10-23):
  - `docs/multiple-instances.md:126` says a `hazelcast.*` address property that makes a single member reachable refuses `INVALID_CLUSTER_INTERFACE`. That holds for a keyed solo member only; a keyless one refuses `MISSING_CLUSTER_KEY`. Say so. (230 corrects `docs/configuration.md` and the contract register.)
  - The `ClusterMembership` record's interface component changed from `Optional<String>` to `String` (null when unset). That breaks plain-Java callers of the canonical constructor and has no migration note. Add it to `docs/migration-0.8.md` and to `CHANGELOG.md` under Breaking.

## Out of scope
- `docs/data-protection.md` (214), code and `docs/configuration.md` (implementation tasks).
- 0.7 release documentation (201), version cuts, release publication and new 1.0 features.
