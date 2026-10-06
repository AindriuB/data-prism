# 116 — Document audit record v3, the JSON projection and log shipping

**Repo:** `.`
**Depends on:** 106, 113, 114, 115
**Owns:**
- docs/audit.md *(new sections "Record version 3", "External correlation id" and "Structured JSON output" only)*
- docs/log-shipping.md *(new)*
- mkdocs.yml *(one nav entry only)*

## Goal

Bring the user documentation up to date with what tasks 108-115 built: the
v3 record and its hash encoding, how the organisation's correlation id
arrives, where it is recorded and where it is sent, the field mapping and
routing, and how to ship to a log stack. This waits on task 106 because 106
owns sections of `docs/audit.md` and the `mkdocs.yml` nav until it lands.

## Context

- Tasks 108-115 as merged. The Javadoc on `AuditRecordFormat` and
  `AuditEventHash` is the source for the v3 layout.
- `docs/conventions.md#documentation`. "Supports", never "compliant" or
  "tamper-proof". Tamper evidence is the unkeyed chain plus external
  checkpoints (D2).
- `mkdocs.yml` `validation.nav.omitted_files: warn`, so a new page without a
  nav entry fails `--strict`.

## Acceptance

- [ ] `docs/audit.md` documents the 25-field v3 layout, that v3 appends `externalCorrelationId` to the
      length-prefixed encoding task 117 documented for v2, mixed v2/v3 chains,
      and `VERSION_REGRESSION`.
- [ ] `docs/audit.md` states that the external correlation id is read only
      from the configured header, validated, dropped if invalid, and
      refused if required. It also states that it is hashed into the record
      and sent only to sources that opt in.
- [ ] `docs/audit.md` has the full canonical-to-ECS table taken from
      `AuditFieldMapping.ecs()`. It states that the mapping only renames and
      reshapes, that `event.outcome` is derived from `policyDecision` and
      routing values are operator constants, and that the native segments
      remain authoritative.
- [ ] `docs/log-shipping.md` links the task 115 examples and explains the
      Filebeat/Elastic Agent and slf4j paths. It says why there is no direct
      Elasticsearch sink: the audit write stays local and fail-closed.
- [ ] `mkdocs build --strict` exits 0.
- [ ] `grep -nE 'compliant|tamper-proof' docs/audit.md docs/log-shipping.md`
      finds no new occurrence.

## Out of scope

- `docs/configuration.md` (task 113), `docs/tools.md` (task 110) and
  `docs/eu-ai-act.md` (task 106).
- `README.md` and the site landing page.
