# 262 — Take the quickstart through re-identification end to end

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch once 210, 245, 222 and 223 have merged
**Depends on:** 210 (210 owns `docker/server/application.yaml`), 245 (the operator denial behaviour the walkthrough shows), 222 (cluster and name keys, and the keyless multi-member refusal), 223 (the solo-member keys of OD-215-8, which decide what an embedded single member needs configured)
**Wave:** 6 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- docs/quickstart.md
- docker/server/application.yaml *(the `dataprism.reidentification`, `dataprism.hazelcast` and operator settings only)*
- compose.yaml *(the quickstart server's environment, the operator port and, added 2026-10-23, the image-tag default only)*
- docker/smoke/reidentification-smoke.sh *(new)*
- docker/smoke/README.md *(the new script's section only)*

## Goal
The roadmap asks for a quickstart that shows re-identification end to end in 0.8. Today the quickstart stops at pseudonymised answers. Extend it so a reader can see the whole loop on their own machine: a pseudonym in an answer, a request to re-identify it, a second person approving, the real identifier released to the operator only, and the audit events each step left. Add a smoke script that runs the same steps against the Compose stack.

## Context
- docs/quickstart.md (sections "Run the demo", "Stop and reset", "How the pieces trust each other").
- docs/reidentification.md ("The flow", "The operator HTTP surface", "Startup refusals").
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java:378-395: re-identification needs `dataprism.hazelcast.topology=embedded`, the index, purposes and, for four-eyes, an approver role.
- docker/smoke/java-runtime-smoke.sh: the existing smoke-script style.
- From the external review of PR #159 (2026-10-23, security): the root `compose.yaml` defaults every quickstart image to `${QUICKSTART_IMAGE_TAG:-latest}`. `docker/multi-instance/compose.yaml` takes the server image from it, and until 0.8.0 is published `latest` is a 0.7 image that ignores the 0.8 cluster-key and name-key settings. Decided 2026-10-23 (third review of PR #160): the default names the version, not `latest`; see Acceptance. 210 handles the multi-instance side.

## Acceptance
- [ ] The quickstart's Compose stack starts with re-identification enabled, four-eyes on, an embedded solo member (`topology: embedded`, `join.mode: none`, not `single-node`, which re-identification refuses) with its cluster and name keys supplied by reference from generated local secrets (optional on a solo member and validated as on a joining member when supplied, OD-215-8 (a) as built by 222 and 223); the stack sets no other `dataprism.hazelcast.security.*` property except, optionally, `pause-lease` or `clock-skew-tolerance` (the only others 230 allows on `none`), and the operator surface on its own port; `docker compose up` succeeds from a clean checkout. Until 0.8.0 is published this check uses the local build overlay (decision 2026-10-23, coordinator); the 0.8.0 release cut re-runs it against the published images.
- [ ] `docs/quickstart.md` gains a re-identification section: get two tokens (requester and approver), call `get_entity_context`, request re-identification of a pseudonym, approve as the second principal, resolve, and read the audit lines for each step. Every command and output block was captured against the real stack (the capture method is in the Outcome), with generated identifiers only.
- [ ] The section shows one refusal (for example the requester trying to approve their own request) with its code.
- [ ] `docker/smoke/reidentification-smoke.sh` runs the same steps and exits 0 only when the resolved identifier matches the fixture and the audit holds the expected decisions in order; every wait in it is bounded. Its README section says how to run it.
- [ ] `compose.yaml` defaults no image to `latest`: `grep -c ':-latest}' compose.yaml` prints 0, and every `${QUICKSTART_IMAGE_TAG:-…}` default names `0.8.0`, the first version that reads the 0.8 cluster-key and name-key settings. `compose.yaml` is on the release cut's version-literal list (named in the Outcome for the 0.8.0 release brief). Because 0.8.0 is not published when this task lands, the clean-checkout check above and the capture use the local build overlay, and the Outcome says so.
- [ ] No real personal data, credential or real domain appears; secrets are generated at start and never committed.
- [ ] `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`.

## Out of scope
- Multi-member deployment (`docker/multi-instance/**`).
- Links and `follow` in the quickstart (off by default through 0.9).
- `docs/reidentification.md` (213 and 261).
