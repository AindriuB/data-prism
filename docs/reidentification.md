---
title: Re-identification
description: The audited, purpose-bound path from a pseudonym back to a subject id, with optional four-eyes approval. Never an MCP tool.
---

# Re-identification

An investigation ends with a human needing to know who a pseudonym stands for.
The `data-prism-reidentification` module is the one controlled path for that. It
supports a deployment's own accountability arrangements; it does not make a
deployment compliant with anything by itself.

**Re-identification is never an MCP tool.** The module is a library with no
transport. Architecture rules fail the build if anything in `mcp`,
`orchestration` or `connectors` depends on it, or if any class outside it calls
`ScopeIdentityIndex.subjectFor`. The HTTP surface is on a separate port, under a
separate authorisation scope; see [The operator HTTP surface](#the-operator-http-surface).

## The flow

Every call is made by an authenticated caller and writes an audit event with
tool `reidentify`, the synthetic value, the namespace, the purpose, the case id,
the scope id, and the requester's principal and client ids. Events carry
`approvalId` and `approverId` where they apply. They never carry the subject id.

1. `request(caller, ReidentificationRequest)` needs the `REQUEST` permission and
   a purpose from the configured re-identification purposes.
   - With four-eyes off it resolves at once.
   - With four-eyes on it returns `PENDING_APPROVAL(approvalId)` and does not
     touch the index.
2. `approve(caller, approvalId)` needs `APPROVE` and a principal other than the
   requester. It returns `APPROVED`.
3. `collect(caller, approvalId)` can be made only by the requester, once, before
   the approval expires. It returns `RESOLVED(subjectId)`.

Four-eyes is configured by `ReidentificationPolicy.fourEyes`; the owner decision
for this project is that deployments default it to on. If the audit sink fails,
no subject id is returned and the outcome is `REFUSED("AUDIT_UNAVAILABLE")`.

## Refusal codes

Each refusal writes an audit event whose decision is `DENY:<code>`.

| Code | Meaning |
|---|---|
| `REIDENTIFICATION_NOT_PERMITTED` | The caller's roles lack the needed permission |
| `PURPOSE_REQUIRED` | The purpose is blank |
| `PURPOSE_NOT_ALLOWED` | The purpose is not in the configured list |
| `REIDENTIFICATION_NOT_FOUND` | No entry: index disabled, scope ended or entry expired |
| `SELF_APPROVAL` | The approver is the requester |
| `NOT_REQUESTER` | `collect` by someone other than the requester |
| `APPROVAL_NOT_FOUND` | Unknown approval id |
| `APPROVAL_EXPIRED` | The approval's time to live has passed |
| `APPROVAL_NOT_PENDING` | `approve` on a request that was already decided |
| `APPROVAL_NOT_APPROVED` | `collect` before approval, or after it was used |
| `TOO_MANY_PENDING` | The requester already holds the maximum number of live pending re-identification approvals (`ReidentificationPolicy.maxPendingPerRequester`, default 5, counted apart from tool-call approvals). No approval is created, and the event carries no approval id |
| `REIDENTIFICATION_UNAVAILABLE` | A store or lookup failed; the request fails closed |
| `AUDIT_UNAVAILABLE` | The audit write failed; nothing is returned |

Resolving a subject id to a person remains the source systems' job.

## How the index is fed

The starter feeds the re-identification index automatically. With
`dataprism.reidentification.enabled=true`, `dataprism.hazelcast.reidentification-enabled=true` and
`dataprism.hazelcast.topology=embedded`, every `SyntheticValueSource` in the context, the default
generator or one the application supplies, is wrapped in `CachingSyntheticValueSource` over the
shared cluster member. Each pseudonym handed out is then entered in the reverse map that
`ScopeIdentityIndex` reads. A source that is already a `CachingSyntheticValueSource` is not wrapped
twice. With re-identification disabled, or with `single-node` topology, nothing is wrapped and nothing
is written to the reverse map.

- **Values produced while the index was disabled are not re-identifiable.** Switching it on does not
  back-fill; those pseudonyms resolve to `REIDENTIFICATION_NOT_FOUND`.
- **A cluster write failure leaves the value unresolvable.** The wrapper falls back to computing the
  value from the wrapped source, so the pseudonym is unchanged, but no entry exists and a later request
  returns `REIDENTIFICATION_NOT_FOUND`. It never resolves to a different subject.
- **A source that already caches over a different cluster is not rewrapped.** An application-supplied
  source that is already a `CachingSyntheticValueSource` over a different `PrivacyCluster` is left
  unwrapped, so its entries are never written where `ScopeIdentityIndex` reads and every request
  returns `REIDENTIFICATION_NOT_FOUND`.
- A reverse-map write failure is not reported as a distinct metric. It shows only as one identity
  cache miss on the application's `PrivacyMetrics` bean, which is indistinguishable from a healthy
  miss, plus a WARN log line ("identity cache unavailable").
- The reverse map holds subject ids only, keyed by scope, namespace and pseudonym, and the entry ends
  with its scope.

## The operator HTTP surface

Humans exercise oversight on a second port that an LLM, or any credential an LLM holds, cannot
reach. It is a second connector in the same process with its own security filter chain, not a
separate application. Set `dataprism.operator.enabled=true` with `port`, `required-audience` and
`required-scope`; see [configuration](configuration.md).

- **Two ports, two route sets.** `/operator/**` is served on the operator port only, and the operator
  port serves nothing else, not `/mcp` and not `/health`; anything else on the operator port is a 404
  `{"code":"NOT_FOUND"}`. On the MCP port an unknown path gets the MCP chain's answer (401 or 403
  without a valid MCP token). A request for `/operator/**` on `server.port` is a 404 before
  authentication, whatever spelling it uses. Forwarding headers (`X-Forwarded-Port`,
  `X-Forwarded-Host`, `Forwarded`) never change which surface a port serves.
- **Its own token rules.** A token must carry the operator audience (`required-audience`) and scope
  (`required-scope`). An MCP token fails the audience check with 401, and a token with the right
  audience and no scope gets 403. The same issuer and keys as `dataprism.security.jwt` are used.
  Do not issue one token with both audiences.
- **The caller.** The operator is identified from the same claims as an MCP caller
  (`dataprism.security.caller-claims.*`, `azp` or `client_id`, `purpose`), so an operator token
  carries a principal, a client, a purpose and a case id. Roles in `roles` decide who may request or
  approve a re-identification (`dataprism.reidentification.roles`).
- **Audit.** Every endpoint writes one event with tool `operator:<action>`, the operator's principal
  and client ids and the target in `scopeId` or `entityType`. A refusal is `DENY:<code>`. If the event
  cannot be written the call fails with 503 `AUDIT_UNAVAILABLE`, and a resume, an approval or any
  re-identification step does not take effect. A pause or a rejection takes effect first and is then
  audited; if that audit write fails the answer is 503 `APPLIED_AUDIT_UNAVAILABLE`, which means the
  pause or rejection **is in force** but unaudited. Do not retry it as if it had failed.
- **Errors.** Every error body is `{"code":"<CODE>"}`. No exception message and no request value is
  ever copied into a response. Requests to a path that does not exist or with the wrong method get
  `NOT_FOUND` and `METHOD_NOT_ALLOWED`. A body that cannot be read or fails validation gets 400
  `INVALID_REQUEST`. Anything unexpected is 500 `OPERATOR_ERROR`. This holds for requests the HTTP
  firewall or Tomcat refuses before a controller sees them (a double slash, a path parameter, an
  encoded slash, a trailing-dot path): they get a `{"code":...}` body and never an echo of the path.
- **Subject ids.** A subject id appears in exactly one response: `RESOLVED`, returned to the
  requester when they collect (or when a request resolves at once with four-eyes off). It never
  appears in an error, a log line or an audit event.

### Endpoints

| Endpoint | Body | Success | Codes |
|---|---|---|---|
| `POST /operator/pause` | `{"target":"ALL"\|"TOOL"\|"SCOPE","name":"..."}`; `name` is required for `TOOL` and `SCOPE` and absent for `ALL` | 200 `{"status":"PAUSED","target":...}` | 400 `INVALID_REQUEST`, 503 `OVERSIGHT_UNAVAILABLE`, `AUDIT_UNAVAILABLE` |
| `POST /operator/resume` | as `pause` | 200 `{"status":"RESUMED",...}` | as `pause` |
| `GET /operator/state` | none | 200 `{"allPaused":bool,"pausedTools":[...],"pausedScopes":[...]}` | 503 |
| `GET /operator/approvals` | none | 200 `{"approvals":[{approvalId, kind, requesterPrincipalId, requesterClientId, scopeId, tool, purpose, caseId, createdAt, expiresAt}]}`; a re-identification entry also carries `namespace` and `syntheticValue`. Never a subject id or a binding | 503 |
| `GET /operator/approvals/{id}` | none | 200 the same view for one approval, of either kind | 404 `APPROVAL_NOT_FOUND`, 503 |
| `POST /operator/approvals/{id}/approve` | none | 200 `{"status":"APPROVED","approvalId":...}` for a tool-call approval | 404 `APPROVAL_NOT_FOUND`, 409 `APPROVAL_NOT_PENDING`, `APPROVAL_EXPIRED`, `SELF_APPROVAL`, 503 |
| `POST /operator/approvals/{id}/reject` | none | 200 `{"status":"REJECTED",...}` | 404 `APPROVAL_NOT_FOUND`, 409 `APPROVAL_NOT_PENDING`, `APPROVAL_EXPIRED`, 503 |
| `POST /operator/reidentifications` | `{"scopeId","namespace","syntheticValue","purpose","caseId"}`; `namespace` is a `PrivacyNamespace` name | 202 `{"status":"PENDING_APPROVAL","approvalId":...}`; 200 `{"status":"RESOLVED","subjectId":...}` with four-eyes off | 400 `INVALID_REQUEST`, `PURPOSE_REQUIRED`, `PURPOSE_NOT_ALLOWED`; 403 `REIDENTIFICATION_NOT_PERMITTED`; 404 `REIDENTIFICATION_NOT_FOUND`; 429 `TOO_MANY_PENDING`; 503 |
| `POST /operator/reidentifications/{id}/approve` | none | 200 `{"status":"APPROVED"}` | 403 `REIDENTIFICATION_NOT_PERMITTED`; 404 `APPROVAL_NOT_FOUND`; 409 `SELF_APPROVAL`, `APPROVAL_NOT_PENDING`, `APPROVAL_EXPIRED`; 503 |
| `GET /operator/reidentifications/{id}` | none | 200 `{"status":"RESOLVED","subjectId":...}`, once | 403 `NOT_REQUESTER`, `REIDENTIFICATION_NOT_PERMITTED`; 404 `APPROVAL_NOT_FOUND`, `REIDENTIFICATION_NOT_FOUND`; 409 `APPROVAL_NOT_APPROVED`, `APPROVAL_EXPIRED`; 503 |

The refusal codes of the re-identification endpoints are the [refusal codes](#refusal-codes) above,
unchanged. `TOO_MANY_PENDING` is 429 with the body `{"code":"TOO_MANY_PENDING"}`. It is audited
once, by the service, as `DENY:TOO_MANY_PENDING` under tool `reidentify`. The endpoint's own event for
that call records `ALLOW:FORWARDED`, meaning only that the operator asked. There is no separate
operator rate limit; the cap on pending approvals per requester is what bounds a flood.

Other codes: `OPERATOR_IDENTITY_UNAVAILABLE` (401, the token lacks a usable principal, client, purpose
or case claim), `REIDENTIFICATION_DISABLED` (404, `dataprism.reidentification.enabled` is `false`),
`OVERSIGHT_UNAVAILABLE` and `AUDIT_UNAVAILABLE` (503), `APPLIED_AUDIT_UNAVAILABLE` (503, from `pause` and
`reject` only: the action took effect and could not be audited), `PAYLOAD_TOO_LARGE` (413).

**The approver's view.** The approver sees what is being asked for: the re-identification entry in
the approval list and in the detail carries the synthetic value and its namespace, with the requester,
purpose, case and expiry. It never carries the subject id, which an approval has no field for. A
tool-call approval carries neither a synthetic value nor a namespace.

The tool-call endpoints (`/operator/approvals/...`) act only on tool-call approvals; a
re-identification approval is decided through `/operator/reidentifications/{id}/approve`, which
checks the `APPROVE` permission and writes the `reidentify` event as well. A tool-call approval made
by the principal who made the MCP call is refused with 409 `SELF_APPROVAL`.

### Startup refusals

| Code | Condition |
|---|---|
| `OPERATOR_PORT_SHARED` | `dataprism.operator.port` equals `server.port` or `management.server.port` |
| `OPERATOR_AUDIENCE_SHARED` | `dataprism.operator.required-audience` equals `dataprism.security.jwt.audience`: one token would then serve both surfaces |
| `INVALID_OPERATOR_ADDRESS` | `dataprism.operator.address` is set and is not a usable address |
| `REIDENTIFICATION_MODULE_MISSING` | `dataprism.reidentification.enabled=true` with `data-prism-reidentification` absent from the classpath. The standalone server carries it; an embedded application using the starter must add it |

### What this does not do

The operator connector is plain HTTP. Bind it to an internal address with `dataprism.operator.address`
(for example `127.0.0.1`), independently of `server.address`, which it follows when unset. Or terminate
TLS in front of it, and keep the port off any network an MCP client can reach. Request bodies over
16 KiB are refused with 413 `PAYLOAD_TOO_LARGE`, whether or not the request declares a length: a
chunked body is read only up to the limit.

The surface is off by default. Publishing the operator port in Docker Compose, `server.json` and the
image is not yet done.
