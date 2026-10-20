# 251 — Issue sealed _links on per-source results

**Repo:** `.`
**Release:** 0.8.0 (off by default through 0.9, sealed-links OD-7)
**Base branch:** `origin/main` once 240, 241, 242 and 247 have merged
**Depends on:** 240 (chainable paths), 241 (resolver declarations, metric constants), 242 (codec), 247 (labels, visibility, `OrchestrationOptions`, both edit `DefaultContextOrchestrator`)
**Blocked on:** OD-08-7 (whether `compare_entity_sources` results carry `_links` in 0.8)
**Wave:** 8 (combined 0.8 graph of 213, 218 to 231 and 238 to 263; 203 and 206 to 211 counted as landed)
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
When links are enabled, every `ANSWERED` source result carries one sealed link per distinct chainable key toward each permitted source that accepts its namespace directly, or through exactly one declared resolver (D-RM-4, D-RM-5, D-RM-14). Links are built from declarations only, with no I/O and no resolver call, from the raw record inside the engine, and the chained key leaves only as ciphertext. With links disabled, output is byte-for-byte what it is today.

## Context
- docs/plan/design/sealed-links.md section 5, "`_links` on a per-source result", rules 1 to 7, and section 7 items 12 to 17 and 23; section 6 boundary 1.
- docs/plan/design/key-resolvers.md section 7 (direct or translated; direct wins, OD-R7).
- docs/plan/design/contract-register.md OD-200-4: a source whose real name is over 64 characters gets no links in 0.8 (counted as omitted).
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java: where raw records, scrubbed trees and the `emitted` set meet; the final validation over the assembled tree.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] With `enabled=false` (the default) no result carries `_links` or `_linksOmitted` and `LinksDisabledParityTest` shows the serialised response identical to the base commit's for the existing fixtures.
- [ ] Links exist only for chainable fields, only toward permitted sources, only on `ANSWERED` results (never on `REFUSED`, `NO_DATA`, `TIMED_OUT` or other statuses), with `rel` `direct` on an exact namespace match and `translated` through one declared resolver; direct wins over translated for the same target; no link through two resolvers; a near-miss namespace (case, trailing space, prefix) gives no link. Mutants killed: ignoring the flag, case-insensitive comparison.
- [ ] One link per distinct element key with an indexed `field` path (`depots[2]`, `routes[1].depot`); the same key at several indices toward one target collapses to the first index in path order before the cap (OD-14). A field whose declared name would itself be sensitive gets no link.
- [ ] Order: by `rel`, then target ascending, then `field`. Cap `max-per-response` across the response, taken round-robin across targets; each affected source carries `_linksOmitted` with its count. A key over 256 bytes or an encoding over `max-length` is not issued and counts as omitted. `LINKS_ISSUED` and `LINKS_OMITTED` are incremented by the counts.
- [ ] `target` is the target's label in the effective setting; `expiresAt` is the link's expiry in ISO-8601; the sealed binding uses the session's scope, caller, scope expiry and token expiry, never a value from the request.
- [ ] Issuing calls no resolver and no adapter (counting stubs report zero calls).
- [ ] The response validators run over the response with links present. Issued tokens are added to the `emitted` exemption set; a token-shaped string that comes from a source record and was not issued still fails validation (test). No validator source file changes.
- [ ] Leak tests: the chained value, in plain, base64, base64url, hex and URL-encoded forms, is in no response, log line, metric, exception or audit record, including under failure; a second test opens each payload with the test key and finds only the padded chained key.
- [ ] Links count toward `max-response-size` (a response just under the cap without links is refused with them).
- [ ] `compare_entity_sources` carries links or not, as OD-08-7 decides, and a test pins it.
- [ ] `LinkBuilderArchitectureTest` (new file) fails when a class in `mcp` or `connectors` depends on `orchestration.links` raw-value accessors. A nested fixture proves it can fail.
- [ ] Before merge, a whole-component hunt for privacy-boundary defects covers the builder and the assembled response; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `follow` and one-to-many items (252); the MCP surface (253); Spring properties (255).
- Collection page links and truncation markers (0.9).
