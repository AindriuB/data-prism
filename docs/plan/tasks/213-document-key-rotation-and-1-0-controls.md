# 213 — Document key rotation, the new controls and the 1.0.0 breaking changes

Renumbered from 198 on 2026-10-19 (id collision with the 0.7.0 plan). Scope not yet reconciled with docs/plan/roadmap.md.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main` once 209, 210, 211 and 212 have merged
**Depends on:** 209, 210, 211, 212 (and through them 203 to 208)
**Owns:**
- docs/key-rotation.md *(new)*
- mkdocs.yml *(one `nav` insertion and one `llmstxt` insertion for `key-rotation.md` only)*
- docs/audit.md
- docs/architecture.md *(new dated entries under "Decisions worth knowing" only; no existing entry is edited)*
- docs/multiple-instances.md
- docs/reidentification.md
- docs/faq.md *(the free-text answer, :35-58, only)*
- docs/extending.md *(insertions only: two SPI sections)*
- docs/tools.md *(the constructor snippet at :69-70 only)*
- docs/log-shipping.md *(the segment-name sentence at :26 only)*
- docs/use-cases/gdpr-data-minimisation-mcp.md *(:70-80 only)*
- CHANGELOG.md *(the `[Unreleased]` section only)*

## Goal
Bring the user-facing docs in line with tasks 203 to 212, and close a gap the docs name themselves:
`docs/use-cases/gdpr-data-minimisation-mcp.md:75-79` says no key-rotation procedure is documented.
Write numbered rotation procedures for both the pseudonymisation key and the new audit chain key.
Record the decisions behind 1.0.0's hardening in `docs/architecture.md`, including the partial reversal
of the decision that rejected a keyed audit chain.

## Context
- docs/architecture.md:315-329 — the 2026-10-06 cluster entry whose "Accepted consequence" (raw subject ids in map keys) tasks 204 and 212 remove; :469-486 — the segmented audit entry; :496-523 — the 2026-09-23 decision rejecting a keyed chain, reaffirmed 2026-10-06 (D2)
- docs/plan/PLAN.md:1535-1542 — the open question coupling exposure tracing to a stable pseudonymisation key; state the effect of rotation on it, do not decide it
- docs/plan/HISTORY-INDEX.md, the S2a row (`## 2026-09-08 — S2a key rotation`) — how `MultiKeySecretKeyProvider` lets running scopes keep their key
- docs/plan/PLAN.md:2408-2417 — "Found on task 77", item 2: the FAQ omits the scan's depth cap of 16
- docs/audit.md:222-245 ("policyDecision values"), :246-290 (verifier, exit codes), :675-768 ("What this does and does not prove", "External checkpoints", "Directory mode")
- The retired task files 203 to 212, Outcomes included, for the merged names of types, properties and refusal codes
- docs-site/hooks/check_site.py and check_changelog.py — run by the docs build

## Acceptance
- [ ] `docs/key-rotation.md` has a numbered procedure for the pseudonymisation key. It says what happens to running scopes (pinned to their `PseudonymisationVersion`), to new scopes, to the re-identification index, and to the cluster subject key under the D-1.0-7 answer. It says that tracing a historical exposure from the audit trail needs the old key kept for the audit retention period, and links the open coupling question rather than answering it.
- [ ] `docs/key-rotation.md` has a numbered procedure for the audit chain key: generate a new key that is not the pseudonymisation key; restart, because each boot starts a new writer chain; move the old key to `dataprism.audit.retired-hmac-keys`; keep it until every segment written under it is past retention; verify across the change with the CLI and two `--hmac-key-file` arguments.
- [ ] `docs/audit.md` documents keyed mode, retired keys, `--hmac-key-file`, `--allow-unkeyed-writers`, size-capped segment names and multi-segment verification. It says plainly, at least once in "What this does and does not prove", that a keyed chain does not resist anyone holding the key, the operator and the writing process included. The `policyDecision values` section lists `DENY:CASE_NOT_AUTHORISED`, `DENY:CASE_AUTHORISATION_UNAVAILABLE` and `DENY:CLIENT_NOT_ALLOWED`.
- [ ] `docs/architecture.md` gains dated 2026-10-15 entries, one per decision:
  - D-1.0-4: an opt-in keyed chain under a separate key now exists. The entry says it **partly reverses** the 2026-09-23 decision and its 2026-10-06 reaffirmation, that it claims only to resist a writer without the key, and that the 2026-09-23 conclusion still holds for the operator threat, which still needs asymmetric signing or external checkpoints under separate custody.
  - D-1.0-1: case authorisation is a refused-unless-decided SPI.
  - D-1.0-2: multi-member joins need a declared isolated network, and map keys carry an HMAC of the subject. The entry says this removes the 2026-10-06 entry's "Accepted consequence" for identity and budget keys, and states what D-1.0-3 decided for the re-identification map.
  - The free-text classification and recogniser hook. The entry records that, before task 209, the Spring-built orchestrators did not run the pattern scan.
  No existing entry's text changes (`git diff` on the file shows only added lines).
- [ ] `docs/multiple-instances.md` documents `join.isolated-network`, `UNSECURED_CLUSTER_JOIN`, the shared subject key and `CLUSTER_SUBJECT_KEY_MISMATCH`, and no longer says that map keys hold raw subject ids. `docs/reidentification.md` states what the index stores, per D-1.0-3.
- [ ] `docs/faq.md`'s free-text answer names `FREE_TEXT` and its two bundled actions, the `FreeTextRecogniser` hook, the depth cap of 16 and its fail-closed result, and that the Spring-built server runs the scan from 1.0.0.
- [ ] `docs/extending.md` has a section for `CaseAuthorizer` (how a standalone server loads one from `loader.path`) and one for `FreeTextRecogniser` (it can only add refusals, a failure refuses, it must be bounded in time by its author).
- [ ] `docs/tools.md:69-70` compiles against the 1.0.0 constructors. `docs/log-shipping.md` says that size-capped segment names still match `audit-*.log`.
- [ ] `docs/use-cases/gdpr-data-minimisation-mcp.md` no longer says no rotation procedure is documented, and links `key-rotation.md`.
- [ ] `CHANGELOG.md` `[Unreleased]` has a **Breaking** list covering: the `MISSING_CASE_AUTHORIZER` startup refusal, the `ScopeResolver` and `AuthorizationService` constructor changes, `UNSECURED_CLUSTER_JOIN`, the removed `ClusterMembership.tcpIp`/`kubernetes` factories and non-public `PrivacyCluster.embedded(Config, ...)`, the changed cluster map key format (a rolling upgrade loses cached identities and resets read counts), the `AuditRecorder` and `AuditRetention` constructor changes, the `SensitivePatternValidator` constructor change, and the pattern scan now running on the Spring path. It has an **Added** list naming `CaseAuthorizer`, `CaseAccessRequest`, `ClientAllowlist`, `SubjectKeyHmac`, `AuditChainKey`, `AuditChainMode`, `FreeTextRecogniser`, `DataClassification.FREE_TEXT` and every new property. Names are as merged, which may differ from this list.
- [ ] Wording says "supports" and never "compliant" or "compliance".
- [ ] `mkdocs build --strict` passes, and so do `check_site.py` and `check_changelog.py` through it.

## Out of scope
- `docs/data-protection.md` (task 214).
- `docs/configuration.md`, which tasks 209 to 212 own and have already updated.
- `docs/plan/**`. The scribe's `/record` step updates `PLAN.md` and `HISTORY.md` separately.
- A migration page for 1.0.0 and the version cut, which belong to the release task.
