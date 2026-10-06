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
`ScopeIdentityIndex.subjectFor`. The HTTP surface, on a separate port under a
separate authorisation scope, is a separate application.

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
