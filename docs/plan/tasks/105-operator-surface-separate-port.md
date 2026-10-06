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

**Decided 2026-10-06 (planning):** task 120 caps live pending approvals per
requester, and task 104 binds the cap. This task adds no operator-surface
rate limit. `POST /operator/reidentifications` maps the `TOO_MANY_PENDING`
refusal to 429, with body `{"code":"TOO_MANY_PENDING"}`. A test asserts the
status and the body, and that the refusal was audited once (by
`ReidentificationService`, not a second time by the endpoint).
`docs/reidentification.md` lists the 429 in the HTTP section.

## Owner decisions (2026-10-06)

- D4: a second port in the same process — a separate connector with its own
  JWT audience/scope and filter chain inside the existing server. Not a
  separate JVM.
- D8: the tool-call approval flow is approved as planned, and four-eyes for
  re-identification defaults ON.

## Notes from task 104's review (2026-10-06)

- Fail closed: with `dataprism.reidentification.enabled=true`, embedded topology,
  and data-prism-reidentification absent from the classpath (the starter
  makes it optional), `@ConditionalOnClass` silently skips ReidentificationWiring
  and startup succeeds with no service. Since this task depends on that bean,
  add a preflight that refuses with a stable code (e.g.
  REIDENTIFICATION_MODULE_MISSING) when re-identification is enabled but the
  module is absent. Owns: the autoconfigure files this task already touches.
- OPERATOR_PORT_SHARED (DataPrismAutoConfiguration ~:275) compares only with
  `server.port`. When the operator connector is bound here, also refuse an
  operator port equal to `management.server.port`.

## Owner decisions (2026-10-06, after attempt 1)

- The approver view shows the pseudonym. The re-identification approval list
  and detail include the synthetic value and its namespace, alongside
  requester, purpose, case and expiry. They never include the subject id.
  Test it, and update docs/reidentification.md.
- Exposing the operator port in Docker Compose, server.json and the image is
  a 0.4.x follow-up, not this task. The surface stays off by default.

## Attempt 1 — failed

Branch at e1ca78a. Tester: PASS (clean verify exit 0; operator tests stable over 3 runs). Reviewer: CHANGES.
Port separation, auth split, four-eyes, subject-id handling and audit
ordering are all confirmed sound.

Owns (main session): these are accepted as in scope, as the 104-review note
intended: DataPrismAutoConfiguration.java, DataPrismProperties.java,
PrivacyExtensionPoints.java and OperatorSurfacePreflightTest.java in
data-prism-spring-boot-autoconfigure. data-prism-server/src/main/** (e.g.
DataPrismServerApplication) is also in scope.

Required:
1. Defect: requests rejected by Spring Security's StrictHttpFirewall on the
   operator port (e.g. `/operator//state`, `/operator;x=1/state`) fall through to
   Boot's BasicErrorController, which returns
   {timestamp,status,error,path} and echoes the path. Every error on the
   operator port must return `{"code":...}` only, with no path echo. Use a
   RequestRejectedHandler and/or an operator-port error handler. Add tests
   for `//`, `;`-params, a trailing dot and X-Forwarded-Port/Host on both ports.
2. Approver view (owner decision): the re-identification approval list and
   detail show the synthetic value AND the namespace, never the subject id.
   (The reviewer recommended namespace only; the owner chose to show the
   pseudonym too.) Test it, and document it.
3. Refuse at startup when `dataprism.operator.required-audience` equals
   `dataprism.security.jwt.audience` (stable code, e.g.
   OPERATOR_AUDIENCE_SHARED). Test it.
4. When a pause or reject has taken effect but the audit write fails, return
   a distinct code (e.g. 503 `APPLIED_AUDIT_UNAVAILABLE`) so the operator
   isn't told it failed when it worked. Document it.
5. Add a `dataprism.operator.address` property so the operator connector
   can bind loopback/internal-only, independently of `server.address`.
   Default: the same as server.address. Fix docs/reidentification.md
   accordingly, and fix "anything else on either port is a 404" (on the MCP
   port, unknown paths get 401/403 from the MCP chain).
6. Exclude Boot's HazelcastAutoConfiguration in the server application, so a
   stray hazelcast.xml/yaml cannot start an extra member.
7. Enforce the 16 KiB operator body limit for chunked requests too (bounded
   read), not only via Content-Length.
- Run `mvn clean verify` over the full reactor and mkdocs --strict; report real exit codes.
