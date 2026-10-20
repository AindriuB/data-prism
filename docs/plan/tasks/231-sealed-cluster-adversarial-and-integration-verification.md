# 231 — Verify the sealed cluster end to end: mixed formats, hostile members, leak scans and the obligation matrix

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch, at a commit that contains 230.
**Depends on:** 230 (and through it 218 to 229 and core 255)
**Owns:**
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MixedFormatTest.java *(new, T13)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/StartupCodeCoverageTest.java *(new, T8 completeness)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/SealedClusterEndToEndTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/HostileMemberEndToEndTest.java *(new)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java *(new cluster scenarios only, T15)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ClusterSignalScanTest.java *(new, T15 metrics and health)*

## Goal
Prove, across real servers and not only inside the hazelcast module, that the sealed cluster holds what the design claims and fails closed where it says it does, and record the evidence task 213 documents from. Every test obligation T1 to T16 in design section 5.1 maps to a passing named test, and every gap is listed, not assumed covered.

## Context
- docs/plan/design/cluster-security.md 5.1 (T1 to T16), 2.3.7 (what is not stopped), 6.1 boundary 7, OD-13 (the qualified claim), as amended by 218.
- docs/plan/tasks/215-plan-sealed-cluster-implementation.md, Outcome: the obligation-to-task map and the gap list.
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/ClusterMembers.java, MultiMemberOperatorTest.java: how real multi-member servers start (read only).
- data-prism-integration-tests/.../PiiLogScanTest.java: the scan shape. Core task 255 adds scenarios to the same file; this task adds after it.
- The Outcomes of 219 to 230.

## Acceptance
- [ ] T13, in `MixedFormatTest`: a fixture writing 0.7-format entries into the `.s1` maps and one writing `dps2` envelopes into a cluster of 0.8 members; each is ignored per structure (identity recomputes, re-identification empty, budget and caller rate refuse, approval not found, pause treated as not fresh) and counted. A fixture writing 0.7 entries into the old map names after start does not change any 0.8 answer.
- [ ] `StartupCodeCoverageTest` reads every startup code in register section 3.5 marked as cluster and 0.8, and fails if any has no test row in 221, 222, 227 or 230's table-driven tests (found by a fixed list of test classes, not by text search of messages).
- [ ] `SealedClusterEndToEndTest`, three servers with shared generated keys: a tool call, a re-identification request with approval, a pause and a resume all work across members; after the run, a keyless Hazelcast client reading every map sees no subject id, scope id, principal, approval id, case id or pseudonym in any key or value.
- [ ] `HostileMemberEndToEndTest`: a keyless member joins three servers under load from a fixed request loop; health goes `DOWN` within the grace under `refuse`; tool calls are refused with the OD-215-10 code and audited `DENY:<code>`; CPU work per tamper is bounded (the salt-cache bound holds; counted, not timed); service recovers after the member leaves. The same member under `alert` leaves health `UP` with the gauge at 1.
- [ ] T15: the tamper, mismatch, unverified and refusal paths emit no key, key material, value, subject, principal, scope id, keyed name or ciphertext in any log line, audit record, metric name or health detail (`PiiLogScanTest` new scenarios and `ClusterSignalScanTest`).
- [ ] The Outcome holds a table T1 to T16: obligation, test class and method, task that added it, and pass. Any obligation not covered is listed as a gap with the reason. A second table lists each residual from design 2.3.7 and 2.3.3 to 2.3.5 with the test whose name states it.
- [ ] The Outcome lists the facts 213 needs: every startup code, property, map name, metric and health detail added in 0.8, the cutover procedure as built, and the rotation procedure as built.
- [ ] `./mvnw -B verify` exits 0 over the full reactor twice in a row. Report the counts.

## Out of scope
- The soak (core task 263) and the environment packet-capture check (design 5.2).
- Any production code change. A failing obligation is reported with a reproducing test and filed, not fixed here.
- `docs/` pages (213).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented identifiers and invented neutral domains only. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A).
- No sleeps. Waits are on latches, health polls or membership events with a bound.
