# 252 — Follow one link through the full pipeline

**Repo:** `.`
**Release:** 0.8.0 (off by default through 0.9)
**Base branch:** `origin/main` once 251 has merged
**Depends on:** 251 (links, `OrchestrationOptions` fields, both edit `DefaultContextOrchestrator`), 246 (reservations), 241 (invoker), 242 (codec, limiter), 243 (release)
**Blocked on:** OD-08-15 (which subject a followed read is charged to)
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextOrchestrator.java *(the `follow` method only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(`follow` and the extraction of the shared fetch, scrub and validate steps it reuses)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/LinkFollower.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/FollowRequest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/OrchestrationOptions.java *(insertions: resolvers, limiter, follow settings)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceResult.java *(`items` and `itemsTruncated` only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java *(the `RESOLVER_FAILED` status only)*
- data-prism-orchestration/src/test/resources/source-status-codes.txt *(one insertion)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/FollowTest.java, FollowRefusalUniformityTest.java, FollowLimiterTest.java, FollowTranslatedTest.java, FollowBudgetTest.java, FollowLeakTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/LinkTimingSoakTest.java *(new; tagged `timing`, excluded from the default build, run by 263)*

## Goal
`follow` takes one sealed link and reads its target on the holder's behalf. It must re-check everything a fresh call checks (P5), refuse every invalid link with one uniform answer while the real reason goes to the audit only (P4, T7), lock out a principal that grinds forged links (OD-8), resolve a translated link at follow time through the bounded invoker (OD-R2), charge every read and resolver call before making it, and pass the target's answer through the same scrub and validation as any read. No path from the adapter reaches the caller without the engine.

## Context
- docs/plan/design/sealed-links.md sections 2 (oracle analysis), 4 (uniform refusal, audit table, limiter) and 5 (`follow` input and output, one-to-many rules, OD-9), and section 7 items 1, 7, 9, 10, 13, 17, 22 and 24.
- docs/plan/design/key-resolvers.md section 6 (cardinality, failure table, read budget, audit) and test obligations 6 to 9, with register OD-200-3 (`RESOLVER_FAILED`).
- docs/plan/design/batch-and-bulk-follow.md 3.2 "Relation bytes": `follow` accepts `0x00` and `0x01` in 0.8.
- D-070-1, D-070-2 (refused sources omitted, all refused refuses the call), D-070-13.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Order of checks: limiter (a locked principal gets `LINK_ATTEMPTS_EXHAUSTED` for any link, valid ones included, and nothing is read); codec verification; then, after authentication, target still permitted (`SourceVisibility`), relation still declared (target still accepts the namespace; for a translated link, the named resolver bean still exists), and payload consistency. Every failure returns the same result with code `LINK_INVALID` and text `The link is not valid.`; results for each reason in the closed set are byte-identical apart from `correlationId`; the audit decision is `DENY:LINK_INVALID` with `link` = `REFUSED:LINK_<REASON>`. Counted reasons are recorded in the limiter; others never are.
- [ ] A direct link reads the target once with the sealed key, through the shared fetch, scrub and validate steps (a stub validator that refuses yields a refusal, proving no bypass). The request to the adapter carries the key namespace (`DataRequest.withKeyNamespace`).
- [ ] A translated link calls the resolver through `ResolverInvoker` with `dataprism.key-resolvers.max-targets` and the timeout, charges `budget-cost` before the call, then reads each returned key once, each charged before its read; keys are read and `items` are ordered by ascending keyed fingerprint, never by the real key. More than the cap is `RESOLVER_FAILED` with no read; `more` sets `itemsTruncated`; an empty result is `NO_DATA`; each failure reason gives `RESOLVER_FAILED` and its metric, and never falls back to a direct read, another resolver or the untranslated key.
- [ ] Outer status and items follow OD-9: `ANSWERED` if any item answered, otherwise `NO_DATA`, a shared status, or `FAILED`; a refused item is omitted and audited under `<target>[<i>]:<refused>` as `REFUSED:<code>`; every answered item refused refuses the call with the first refused code. Tests cover each mix of sealed-links item 24.
- [ ] Budget: an unaffordable resolver call or read is not made; exhausted before the first read refuses with `SCOPE_READ_BUDGET`; exhausted part-way returns the items read and `itemsTruncated`. Reads are charged to the subject OD-08-15 decides and always to the scope total.
- [ ] The followed result is the `get_entity_context` envelope with exactly one entry keyed by the target label, `entity` only for `ANSWERED`, its own one-step `_links` (251's rules), and `names` recorded as 247 does. `RESOLVER_FAILED` is added to `SourceOutcome.Status` and `source-status-codes.txt` together.
- [ ] Leak tests: the link token, the chained key and every resolver input and output key are in no response, log line, metric, exception or audit record, in plain or encoded form, on success and on every failure path. `follow` never reaches `ScopeIdentityIndex.subjectFor` (existing architecture rule still passes).
- [ ] `LinkTimingSoakTest` measures expired, authenticated-but-not-permitted and forged tokens and asserts they are not separable beyond a tolerance stated in the test; it is excluded from `./mvnw verify` by tag.
- [ ] Before merge, a whole-component hunt for concurrency and privacy-boundary defects covers `follow`, the limiter path and the budget path; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- The MCP `follow` tool and its schema (253); Spring properties and beans (255).
- `follow_bulk`, collection pages and group links (0.9).
