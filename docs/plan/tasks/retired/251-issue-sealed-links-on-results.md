# 251 — Issue sealed _links on per-source results

**Repo:** `.`
**Release:** 0.8.0 (off by default through 0.9, sealed-links OD-7)
**Base branch:** `origin/main` once 240, 241, 242, 247 and 269 have merged
**Depends on:** 240 (chainable paths), 241 (resolver declarations, metric constants), 242 (codec), 247 (labels, visibility, `OrchestrationOptions`, both edit `DefaultContextOrchestrator`), 269 (the settled limiter contract: `AttemptSubject`, `LinkAttemptGate`, no `LinkKeys.attemptCounterKey`)
**Decisions:** OD-08-7 decided 2026-10-20 (a): no `_links` on `compare_entity_sources` in 0.8.
**Wave:** 8 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Wave 8 review (2026-11-05):** lines marked *(2026-11-05)* are coordinator calls, owner-delegated. This brief is consolidated: the text below is the current contract.
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/LinkBuilder.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/LinkTargets.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/IssuedLink.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/OrchestrationOptions.java *(insertions: link issuing settings)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(link issuing after the final validation, and the emitted-set exemption only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceResult.java *(`_links` and `_linksOmitted` only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextResponse.java *(carrying the links only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ResponseSize.java *(counting links, only if they are not already counted)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/LinkBuilderTest.java, LinkTargetsTest.java, LinkCapTest.java, LinkLeakTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/LinksDisabledParityTest.java *(new)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkBuilderArchitectureTest.java *(new)*

## Goal
When links are enabled, every `ANSWERED` source result carries one sealed link per distinct chainable key toward each permitted source, other than itself, that accepts its namespace directly, or through one declared resolver (D-RM-4, D-RM-5, D-RM-14). Two resolvers reaching one source with different `toNamespace` issue both links, as key-resolvers.md:142 says. Links are built from declarations only, with no I/O and no resolver call, from the raw record inside the engine, and the chained key leaves only as ciphertext. With links disabled, output is byte-for-byte what it is today.

## Context
- docs/plan/design/sealed-links.md section 5, "`_links` on a per-source result", rules 1 to 7, and section 7 items 12 to 17 and 23; section 6 boundary 1.
- docs/plan/design/key-resolvers.md section 7 (direct or translated; direct wins, OD-R7).
- docs/plan/design/contract-register.md OD-200-4: a source whose real name is over 64 characters gets no links in 0.8 (counted as omitted).
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java: where raw records, scrubbed trees and the `emitted` set meet; the final validation over the assembled tree.
- From 240 (2026-10-21): `ChainableField` is `io.github.aindriub.dataprism.core.engine.ChainableField`; use `ChainableFields.of(Class, Map<String, ModelDescriptor>)`.
- The codec seals whatever it is given. Supply the issue time from the clock and the link id from a random source. An over-bound binding (a scope or principal id over 65535 UTF-8 bytes) is `BINDING_TOO_LONG` on seal; handle the omit. `LinkKeys.forScope` can refuse `LINK_KEY_UNRESOLVED` when the current key is down; handle it. `ScopeKeys.fingerprint` refuses values over 65535 bytes, so fingerprint only values within the chained-key cap, and say so in its Javadoc.
- This task issues links and opens none, so it uses neither `LinkAttemptGate` nor the limiter (269). `LinkKeys.attemptCounterKey` no longer exists.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] With `enabled=false` (the default) no result carries `_links` or `_linksOmitted` and `LinksDisabledParityTest` shows the serialised response identical to the base commit's for the existing fixtures.
- [ ] Links exist only for chainable fields, only toward permitted sources, only on `ANSWERED` results (never on `REFUSED`, `NO_DATA`, `TIMED_OUT` or other statuses), with `rel` `direct` on an exact namespace match and `translated` through one declared resolver; direct wins over translated for the same target; two resolvers reaching one target with different `toNamespace` issue both links; no link through a chain of two resolvers; a near-miss namespace (case, trailing space, prefix) gives no link. A source is never linked to itself. *(2026-11-05)* Mutants killed: ignoring the flag, case-insensitive comparison.
- [ ] One link per distinct element key with an indexed `field` path (`depots[2]`, `routes[1].depot`); the same key at several indices toward one target collapses to the first index in path order before the cap (OD-14). A field whose name fails a validator gets no link and is not counted in `_linksOmitted`. *(2026-11-05)*
- [ ] Order: by `rel`, then target ascending, then `field`. Cap `max-per-response` across the response, taken round-robin across targets; each affected source carries `_linksOmitted` with its count. A key over 256 bytes or an encoding over `max-length` is not issued and counts as omitted. `LINKS_ISSUED` and `LINKS_OMITTED` are incremented by the counts.
- [ ] `target` is the target's label in the effective setting; `expiresAt` is the link's expiry in ISO-8601. The sealed binding uses the session's scope and caller and the context's scope expiry, never a value from the request. `tokenExpiry` is dropped from the settings: `ScopeResolver.java:93` already folds token expiry into `PrivacyContext.expiresAt`, so `LinkBinding`, which is unchanged, gets that one instant in both expiry components. An unknown expiry (`expiresAt` null) issues no link, so `NO_EXPIRY` goes. *(2026-11-05)*
  - Settings shape, for 252 and 255: `LinkIssuing(codec, maxPerResponse, resolvers, descriptors, clock)`, with no expiry function.
- [ ] Issuing calls no resolver and no adapter (counting stubs report zero calls).
- [ ] The response validators run over the response with links present. Issued tokens are added to the `emitted` exemption set. The exemption is exact-value and holds only this call's random tokens, so a record cannot pre-supply one (test). A lookalike from a record passes as ordinary data. No validator source file changes. *(2026-11-05)*
  - This narrows design rule 7 (sealed-links.md:442), which says a token-shaped string that is not a sealed link fails validation. The scribe corrects the rule at /record.
- [ ] Leak tests: the chained value, in plain, base64, base64url, hex and URL-encoded forms, is in no response, log line, metric, exception or audit record, including under failure; a second test opens each payload with the test key and finds only the padded chained key.
- [ ] Links count toward `max-response-size` (a response just under the cap without links is refused with them).
- [ ] `compare_entity_sources` carries no `_links` and no `_linksOmitted`, with links enabled (OD-08-7 (a)), and a test pins it.
- [ ] `LinkBuilderArchitectureTest` (new file) fails when a class in `mcp` or `connectors` depends on `orchestration.links` raw-value accessors. A nested fixture proves it can fail.
- [ ] Before merge, a whole-component hunt for privacy-boundary defects covers the builder and the assembled response; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `follow` and one-to-many items (252); the MCP surface (253); Spring properties (255).
- Collection page links and truncation markers (0.9).

## Appendix: superseded

Agents: do not read this section.

Text replaced by the 2026-11-05 consolidation, kept for the record.

When links are enabled, every `ANSWERED` source result carries one sealed link per distinct chainable key toward each permitted source that accepts its namespace directly, or through exactly one declared resolver (D-RM-4, D-RM-5, D-RM-14). Links are built from declarations only, with no I/O and no resolver call, from the raw record inside the engine, and the chained key leaves only as ciphertext. With links disabled, output is byte-for-byte what it is today.
- From the second external review of PR #154 (2026-10-23): an over-bound binding (a scope or principal id over 65535 UTF-8 bytes) is `BINDING_TOO_LONG` on seal; handle the omit. The limiter side of this bullet is settled by 269 (see the 269 bullet).
- From the external review of PR #154 (2026-10-22, 242's Outcome): gate the limiter on `refused.counted()`, never `reason.counted()` (a post-authentication `MALFORMED` and `KEY_UNAVAILABLE` are uncounted). Supply the issue time from the clock and the link id from a random source; the codec seals whatever it is given. `LinkKeys.forScope` can refuse `LINK_KEY_UNRESOLVED` when the current key is down; handle it (`attemptCounterKey` is removed by 269).
- From 269 (OD-269-1 to 4 decided by the owner and coordinator 2026-10-22, OD-269-5 decided (b) 2026-10-23; brief revised 2026-10-23, after review 5): `LinkKeys.attemptCounterKey` is removed. Links are opened only through the gate: `LinkAttemptGate.open(token, binding, accepted)` is the one call. It checks the inputs (a null or empty token, a null binding or `accepted`, an empty or over-bound id: `Invalid(MALFORMED)`, uncounted), then `lockState` (a locked principal gets `Exhausted(LOCKED)` before the token is opened, valid links included). It then opens the token and records a counted refusal atomically. It returns `Proceed(opened)`, `Invalid(reason)` or `Exhausted(cause)`; the cause is `LOCKED`, `OVER_LIMIT`, `CEILING`, `LIMITER_UNAVAILABLE` or `OPEN_FAILED`. It never throws, and it treats a null answer from the limiter or the codec as a refusal. Answer the client with `decision.clientCode()`: `LINK_INVALID`, `LINK_ATTEMPTS_EXHAUSTED`, `LINK_CHECK_UNAVAILABLE` (new, retryable) for a limiter outage, or `LINK_INVALID` for a failure to open (OD-269-5 (b), decided by the owner 2026-10-23). Never compute the code yourself. Write `decision.auditValue()` (`REFUSED:LINK_<REASON>` or `REFUSED:LINK_ATTEMPTS_<CAUSE>`) under the call's `link` disposition key. Architecture rules forbid calling or referencing `LinkCodec.open` or the limiter outside `core.link`, and forbid any `core.link` class outside `data-prism-core`; `mcp` may not depend on `core.link` at all. This task issues links and opens none, so it uses neither the gate nor the limiter. An over-bound binding is `BINDING_TOO_LONG` on seal.
- From the external review of PR #154, rounds 1-4 (2026-10-23): `ScopeKeys.fingerprint` refuses values over 65535 bytes, so fingerprint only values within the chained-key cap, and say so in its Javadoc. The `&& knownKey` guard in `LinkCodec` is defence in depth behind the `UNKNOWN_KEY` return, and no test can kill its mutant, so it needs a comment saying so and a PIT exclusion, not a test. Moved to 269 (2026-10-23), which owns `LinkCodec.java`.
- [ ] Links exist only for chainable fields, only toward permitted sources, only on `ANSWERED` results (never on `REFUSED`, `NO_DATA`, `TIMED_OUT` or other statuses), with `rel` `direct` on an exact namespace match and `translated` through one declared resolver; direct wins over translated for the same target; no link through two resolvers; a near-miss namespace (case, trailing space, prefix) gives no link. Mutants killed: ignoring the flag, case-insensitive comparison.
- [ ] One link per distinct element key with an indexed `field` path (`depots[2]`, `routes[1].depot`); the same key at several indices toward one target collapses to the first index in path order before the cap (OD-14). A field whose declared name would itself be sensitive gets no link.
- [ ] `target` is the target's label in the effective setting; `expiresAt` is the link's expiry in ISO-8601; the sealed binding uses the session's scope, caller, scope expiry and token expiry, never a value from the request.
- [ ] The response validators run over the response with links present. Issued tokens are added to the `emitted` exemption set; a token-shaped string that comes from a source record and was not issued still fails validation (test). No validator source file changes.

## Outcome (2026-11-05, 0.8; PR #208)
When link issuing is configured (off by default; with it off the serialised response is unchanged), `ANSWERED` per-source results carry sealed `_links` and `_linksOmitted`. `LinkBuilder`, `LinkTargets` and `IssuedLink` (in `orchestration.links`) build links from declarations and the raw record only, with no resolver or adapter call: direct on an exact namespace, translated through one declared resolver, direct wins, one link per distinct key at its first index, the cap taken round-robin across targets. `OrchestrationOptions.LinkIssuing` is optional (empty is off). Issued tokens join the `emitted` set and the links are scanned in the final validation pass; no validator changed. `compare_entity_sources` issues no links (OD-08-7 a). Metrics `LINKS_ISSUED` and `LINKS_OMITTED`.

Decided in review:
- `LinkIssuing.tokenExpiry`, `NO_EXPIRY` and the default are dropped. The binding uses the context's scope expiry, which already includes the caller's token expiry, and an unknown scope expiry issues no link.
- Two resolvers reaching one source with different `toNamespace` both give a link (the dedupe key in `LinkBuilder` includes the target namespace).
- A field whose declared name fails a validator gets no link and is not counted in `_linksOmitted`.
- The validator exemption is exact-value per call. A lookalike of an issued token is ordinary data.
- `OmitReason.TOO_LONG` is unreachable at legal settings and is kept as a fail-closed branch.

Tests failing first (at 5c086cbd): `twoResolversToOneSourceWithDifferentTargetNamespacesIssueBothLinks` and `noLinkIsIssuedWhenTheScopeEndIsUnknown`. Others pass on base by design because they pin behaviour that already held (the exact-value exemption, a token never launders a raw value, a secret-shaped value still withholds its source). `LinkLeakTest` writes its capture to `logs/` on failure. The whole-component hunt found no leak: the chained value is held only as sealed ciphertext, and the response, audit, metrics, logs and exceptions were checked for it in nine encodings on the success and refused paths. Only targeted tests were run locally; CI ran the full build.

Left open (PLAN follow-ups): `LINKS_ISSUED` is counted before the ALLOW audit write; `resolver.to()` is read live at issue; the catch at `DefaultContextOrchestrator.java:388` is untested; a caller without `FOLLOW` still sees source relationships.
