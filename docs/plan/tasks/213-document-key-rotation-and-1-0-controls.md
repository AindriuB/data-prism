# 213 — Document the 0.8 controls, key rotation and migration

Renumbered from 198 on 2026-10-19. Reconciled with the pre-0.9 freeze; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** the 0.8 integration branch once all dependencies have merged; otherwise `origin/main` after those merges.
**Depends on:** 208, 210, 211, and every sealed-cluster implementation successor planned by 215.
**Blocked on:** 215 must replace the descriptive cluster dependency with concrete task ids before this task starts. Superseded tasks 204, 205 and 212 are not implementation prerequisites.
**Owns:**
- docs/key-rotation.md (new)
- docs/migration-0.8.md (new)
- mkdocs.yml (nav and llmstxt entries for both new pages)
- docs/audit.md
- docs/architecture.md (new dated decisions and explicit supersession pointers)
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
- [ ] The cluster procedure describes the incompatible old/new map formats and a controlled cutover; do not present a mixed 0.7/0.8 rolling upgrade that resets read counts as safe. Explain name-key changes and re-identification store loss.
- [ ] Audit docs cover current and retired HMAC keys, CLI flags, keyed/unkeyed verification, size-capped segment names and retention. Explain transition from existing unkeyed logs: keyed retention refuses those writers; preserve and verify the old trail separately rather than silently skipping or deleting it.
- [ ] Repeat the limits: unkeyed chains do not resist rewriting by the operator; keyed chains do not resist key holders; truncation needs checkpoints under separate custody. Record D-1.0-4's partial reversal of the old keyed-chain rejection, preserving its operator-threat reasoning.
- [ ] Document case-authorisation refusals, client allowlisting and its lack of egress control, FREE_TEXT actions, recogniser ordering and fail-closed failures, and the scanner's depth cap. Recognisers must bound their own runtime. The existing Spring pattern scan first runs in 0.7 (209); recognisers arrive in 0.8 (208).
- [ ] Architecture records distinguish the October 15 imported decisions from the October 19 reconciliation. Explicitly supersede isolation-only cluster claims with the approved sealed-state design and its qualified protection claim.
- [ ] `docs/extending.md` shows how a standalone server loads a `CaseAuthorizer` extension and how to supply a recogniser. Constructor examples compile against the merged APIs.
- [ ] Migration and changelog cover all changed public constructors, map formats, startup refusals, properties, audit behaviour, and recogniser functionality. Use `[Unreleased]`; no version bump, tag or publication.
- [ ] Replace the old statement that no rotation procedure exists with a link to the procedure. Segment examples still match `audit-*.log`.
- [ ] Wording says “supports” and makes no claim that a deployment meets a legal duty.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass, with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Out of scope
- `docs/data-protection.md` (214), code and `docs/configuration.md` (implementation tasks).
- 0.7 release documentation (201), version cuts, release publication and new 1.0 features.
