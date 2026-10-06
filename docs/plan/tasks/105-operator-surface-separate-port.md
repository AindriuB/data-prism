# 105 — Serve the operator surface on a separate port: pause, approvals, re-identification

**Repo:** `.`
**Depends on:** 104
**Owns:**
- data-prism-server/pom.xml
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/** *(new)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerSecurityConfiguration.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/** *(new)*
- docs/reidentification.md *(the HTTP section only)*

## Goal

Humans need somewhere to exercise oversight that an LLM, or any credential
an LLM holds, cannot reach. This task adds an operator HTTP surface on
`dataprism.operator.port`. It has its own security filter chain requiring
the operator audience and scope. It hosts these actions: the kill switch and
pause/resume per tool and per scope, listing and approving or rejecting
pending tool-call approvals, and re-identification request, approval and
collection. Every action is audited. The MCP endpoint is not served on this
port, and the operator endpoints are not served on the MCP port.

## Context

- `ServerSecurityConfiguration.java:23-50` — the existing JWT resource-server
  chain scoped to the MCP path.
- `JwtDecoderSupport.buildJwtDecoder(properties)` is reused with the
  operator audience.
- Task 104's `dataprism.operator.*` properties, its `OversightState`,
  `ApprovalStore` and `ReidentificationService` beans, and the
  `AuditRecorder` bean.
- `docs/design-review.md` §B1 requires a separate authenticated surface under
  a different authorisation scope, unreachable by the LLM.
- How "separate application" is realised here (a second connector in the
  same process) is a decision the owner must confirm. See the plan return.

## Acceptance

- [ ] When `dataprism.operator.enabled=true`, a second connector listens on
      `dataprism.operator.port`. Requests on that port reach only
      `/operator/**`, and requests for `/operator/**` on `server.port` return
      404. Tests assert both directions, including that the MCP path on the
      operator port returns 404.
- [ ] `/operator/**` requires a JWT whose audience contains
      `required-audience` and whose scope contains `required-scope`. A token
      valid for the MCP endpoint but lacking either gets 401 or 403. A test
      asserts this.
- [ ] The following endpoints exist, and each writes one audit event with
      tool `operator:<action>`, the operator's principal and client ids, and
      the target in `scopeId` or `entityType`:
      - `POST /operator/pause` and `POST /operator/resume`, with body
        `{"target":"ALL|TOOL|SCOPE","name":...}`
      - `GET /operator/state`
      - `GET /operator/approvals`
      - `POST /operator/approvals/{id}/approve` and
        `POST /operator/approvals/{id}/reject`
      - `POST /operator/reidentifications`
      - `POST /operator/reidentifications/{id}/approve`
      - `GET /operator/reidentifications/{id}`

      A test asserts the audit event for `pause`.
- [ ] End to end in one test, against embedded server ports with fixture
      sources:
      1. a `TOOL` pause makes the next `get_entity_context` call return
         `TOOL_PAUSED`;
      2. `resume` restores it;
      3. with `approval-required-tools` set, the first call returns
         `APPROVAL_REQUIRED approvalId=<id>`;
      4. approval by a second operator principal follows;
      5. the identical retry succeeds;
      6. a third identical call returns `APPROVAL_REQUIRED` again.
- [ ] Approving a tool-call request with the same principal that made the
      MCP call returns 409 with `SELF_APPROVAL`.
- [ ] Re-identification responses return `subjectId` only for `RESOLVED`.
      Every refusal returns its task-100 code and never echoes the synthetic
      value in an error message. A test asserts this.
- [ ] Error bodies carry a stable code and no exception message text.
- [ ] `mvn -pl data-prism-server,data-prism-integration-tests -am verify`
      passes, including `PiiLogScanTest`.
- [ ] `docs/reidentification.md` documents each endpoint, its body and its
      codes.

## Out of scope

- A UI.
- Compose, `server.json` and Docker changes for the second port. File as a
  follow-up if needed.
- Changes to the MCP JWT chain beyond restricting it to `server.port`.
- `docs/architecture.md`. That is task 106.

## Note from task 100's review (flooding)

Task 100 dropped dedup of identical re-identification requests, so one requester
can flood approvers. If task 104 does not cap live pending approvals per
requester, this task must rate-limit the operator surface instead.
