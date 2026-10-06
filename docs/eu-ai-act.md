---
title: EU AI Act and GDPR Art. 9 support
description: What Data Prism supports for a deployer under Regulation (EU) 2024/1689 and GDPR Art. 9, article by article, and what stays the deployer's job.
---

# EU AI Act and GDPR Art. 9 support

This page maps Data Prism's features to articles of Regulation (EU) 2024/1689
(the EU AI Act) and to GDPR Article 9. It says what each feature supports, what
it does not do, and what remains the deployer's job. **It is not legal advice.**
Whether an obligation applies to you, and whether a control satisfies it, is a
question for your own counsel.

Two limits apply to every section below.

- **Data Prism is a component.** It is not an AI system's complete
  record-keeping or oversight solution. It records and controls what passes
  through the privacy layer between an MCP client and your source APIs. It does
  not see the model's prompts, the model's outputs, or anything your AI system
  does outside a Data Prism tool call.
- **Data Prism does not classify your system's risk level.** It does not decide
  whether your AI system is high-risk, prohibited or neither, and it does not
  check that you have met the provider obligations that attach to that
  classification.

The output is also not anonymous. Pseudonymised data is still personal data
under GDPR Article 4(5); see the [FAQ](faq.md#is-the-output-anonymous).

## Art. 12, Art. 19 and Art. 26(6): records, retention and the join key

Art. 12 asks that a high-risk AI system technically allow automatic recording of
events, and Arts. 19 and 26(6) ask providers and deployers to keep the logs
they control for at least six months, unless Union or national law provides
otherwise. Data Prism supports this for the decisions the privacy layer makes.

**Field dispositions.** Every audited call writes a record with a
`fieldDispositions` map: a field path (`<sourceName>:<json-pointer>`, array
indices collapsed to `*`) to the action taken on it, one of the `PrivacyAction`
names or `REFUSED`. A disposition names a field and an action and never a value.
See [The durable audit chain](audit.md).

**A hash-chained, checkpointed file.** The `hash-chained` sink writes each record
with the hash of the one before it, per writer, and an offline verifier replays
the chain. External `BOOT`, `PERIODIC`, `SHUTDOWN` and `RETENTION_ANCHOR`
checkpoints, written to a separate file, let the verifier report a truncated
tail or a missing boot (exit 5). This is tamper *evidence*, not tamper
prevention. The chain is unkeyed. Someone who can write the audit file can
recompute the chain after an edit, so the evidence only holds if the checkpoint
file is held under separate custody from the audit files. It does not resist an
operator who controls both. The reasoning is in the 2026-09-23 entry in
[Architecture](architecture.md#decisions-worth-knowing). Records written after a
writer's last checkpoint are undetectable if deleted.

**Retention.** With `dataprism.audit.directory` the sink writes one
`audit-YYYY-MM-DD.log` segment per UTC day. Segments older than
`dataprism.audit.retention` are purged, after a `RETENTION_ANCHOR` checkpoint is
recorded for each writer's last record in them. The default is six months, and a
shorter period refuses startup unless `dataprism.audit.retention-override` is
`true`. Purging is deletion. See [Retention](audit.md#retention) and
[configuration](configuration.md#segmented-files-checkpoints-and-retention).

**`correlationId` as the join key.** Every tool result the audit trail records
carries a random `correlationId` in its `_meta`, which you store in your AI
system's own log entry and join to the matching audit record. See
[Joining to your AI-system logs](audit.md#joining-to-your-ai-system-logs).

**What remains the deployer's responsibility.** Logging your AI system's own
inputs, outputs and events, and storing the `correlationId` alongside them.
Deciding the retention period for the logs you hold, and the legal basis for
changing it. Custody of the audit directory and, separately, of the checkpoint
file, with access controls and storage that make deletion hard. Shipping,
backing up and protecting the files. Running the verifier on a schedule and
acting on its result. Data Prism does not enforce append-only storage, and the
single-file sink (`file-path`) has no retention at all.

## Art. 14, Art. 26(1) and Art. 26(2): human oversight

Art. 14 concerns human oversight of a high-risk AI system, and Art. 26(1) and (2)
ask the deployer to use the system according to its instructions and to assign
oversight to people with the competence and authority to do it. Data Prism
supplies controls those people can use on the tool calls that pass through it.

**Pause.** An operator can pause every call, one tool, or one privacy scope. A
paused call is refused with `DATAPRISM_PAUSED`, `TOOL_PAUSED` or `SCOPE_PAUSED`
before any source is touched, and is audited as `DENY:<code>`. See
[admission codes](tools.md#admission-codes-oversight-refusals).

**Approvals.** Tools named in `dataprism.oversight.approval-required-tools` are
refused with `APPROVAL_REQUIRED` and an `approvalId` until a different person
approves on the operator port. The identical call then succeeds once. An approval
is bound to the entity, subject, purpose, privacy profile and client of the call,
and the approval id and approver are written to the audit record. A requester
cannot approve their own call (`SELF_APPROVAL`), and the number of live pending
approvals per requester is capped (`TOO_MANY_PENDING`).

**Per-caller limits.** `dataprism.oversight.caller-rate-limit` bounds how many
calls one caller may make per window (`CALLER_RATE_LIMITED`). An
approval-required call still uses a token while it waits.

**Re-identification.** The path from a pseudonym back to a subject id is never
an MCP tool. It lives in `data-prism-reidentification`, is reached only on the
operator port under its own audience and scope, needs a configured purpose and a
case id, is audited, and defaults to four-eyes. The subject id never appears in an
audit record. See [Re-identification](reidentification.md).

**Failing closed.** If pause state, the approval store or the audit write cannot
be reached, the call is refused (`OVERSIGHT_UNAVAILABLE`, `AUDIT_UNAVAILABLE`)
and not let through. The same holds while an audit checkpoint cannot be written:
audited calls are refused with `AUDIT_CHECKPOINT_UNAVAILABLE` until the next
checkpoint succeeds. That is an availability trade the deployer must plan for,
for example by monitoring the checkpoint file's storage.

**What remains the deployer's responsibility.** Deciding who the oversight people
are, training them, and giving them the authority to pause and to decline. Deciding
which tools need approval and what the rate limits should be. Reading what they
approve: the approver's view shows the request, not whether it is appropriate.
Keeping the operator port on an internal address (`dataprism.operator.address`)
and issuing operator tokens separately from MCP tokens. Oversight of your AI
system's behaviour outside Data Prism's tools, and your obligations as deployer
beyond those controls, are not covered here.

## Art. 5(1)(g), Art. 10(5) and GDPR Art. 9: the special categories

GDPR Art. 9 restricts processing of special categories of personal data. Art.
5(1)(g) of the EU AI Act prohibits certain biometric categorisation systems that
infer sensitive attributes. Art. 10(5) lets providers of high-risk systems
process special categories exceptionally, for bias detection and correction, under
conditions that the Act sets out.

**What Data Prism does.** The classifications `PHI`, `BIOMETRIC`, `GENETIC`,
`ETHNIC_ORIGIN`, `POLITICAL_OPINION`, `RELIGIOUS_BELIEF`, `TRADE_UNION` and
`SEX_LIFE_ORIENTATION` are the special categories. No profile can expose them:
a profile that maps one weaker than `REDACT` refuses startup with
`SPECIAL_CATEGORY_EXPOSED`, and a field carrying one is removed when the profile
has no rule for it. The bundled `DEFAULT` and `STRICT` profiles remove all of
them except `PHI`, which they redact. A field nobody classified is handled by the profile's
`unclassified` setting. In the bundled `DEFAULT` and `STRICT` profiles, and
wherever the setting is omitted, that refuses the whole response
(`FAIL_REQUEST`). The Spring Boot starter and the server always load those
bundled profiles and refuse an application `PrivacyPolicyResolver` bean
(`FORBIDDEN_PRIVACY_OVERRIDE`), so a server deployment always has
`FAIL_REQUEST`. A weaker setting is possible only when you assemble the core
library yourself and load your own YAML with `PrivacyProfiles.fromYaml`: there
`unclassified` can be `REDACT_AND_WARN`, `DROP_AND_WARN` or
`PASS_THROUGH_UNSAFE`, and under `PASS_THROUGH_UNSAFE` an unclassified field,
including one that holds a special-category value nobody labelled, reaches the
model unchanged. With `FAIL_REQUEST` this supports data minimisation: a classified special-category
value does not reach the model, and an unclassified field is refused rather than
passed. See [Extending](extending.md) and
[configuration](configuration.md).

**No bias-detection profile ships.** Art. 10(5) is a narrow exception for
providers, and its conditions turn on facts Data Prism cannot know, such as
whether bias detection can be done with other data and what safeguards and
deletion are in place. A profile that let special-category values through for that
purpose would contradict the rule above that none can be exposed. No such profile has
been filed, and the question is still open with the project owner. Do not read the absence of
a profile as a statement about whether you may rely on Art. 10(5).

**What Data Prism does not do.** It acts on classifications that a model author or
profile has declared. It does not detect a special category in a field that was
classified as something else, or that sits in free text. It does not decide
whether a biometric categorisation practice is prohibited under Art. 5(1)(g); it
only removes fields classified as biometric or as another special category.

**What remains the deployer's responsibility.** Classifying the fields of every
model correctly, and reviewing the `@NonSensitive` reasons. If you assemble the
core library directly with your own profiles, choosing a weaker `unclassified`
handling than `FAIL_REQUEST`, especially `PASS_THROUGH_UNSAFE`, which is your
decision and removes the refusal described above. The starter and server do not
allow it. Establishing a
condition under GDPR Art. 9(2) before processing special categories anywhere in
your system, including outside Data Prism. Assessing whether your system falls
under Art. 5 or Art. 10(5), and what bias testing you need, with your own
counsel.

## Where each feature is documented

| Feature | Reference |
|---|---|
| Audit records, chain, verifier, retention | [The durable audit chain](audit.md) |
| Audit and oversight configuration | [Configuration](configuration.md) |
| Admission codes, `correlationId` in `_meta` | [Tool reference](tools.md) |
| Operator surface, re-identification | [Re-identification](reidentification.md) |
| Classifications, profiles, SPIs | [Extending](extending.md) |
| Decisions and boundaries | [Architecture](architecture.md) |
