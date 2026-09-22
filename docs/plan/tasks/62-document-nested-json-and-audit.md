# 62 — Document nested catalogues and the durable audit chain

**Repo:** .
**Depends on:** 60, 64, 66
**Owns:**
- docs/architecture.md
- docs/protect-your-own-api.md
- docs/extending.md
- docs/audit.md (new)
- examples/json-sources/**

## Goal
Two claims in the shipped docs go stale with this release: that the configured
JSON catalogue is flat by design, and that the only audit sink is a logger with
nothing verifying it. Correct both, publish a worked nested example, and state
exactly what the audit chain's tamper-evidence does and does not prove.

## Context
- `docs/architecture.md:104-113` — "its allowlisted `fields:` catalogue is flat by
  design (`ConfiguredJsonFieldMetadataResolver.descendable()` always returns
  `false`)", now false; `:166-170` — boundary 7, "partially enforced", which task
  65 extends to the file sink but still not to metric labels or trace attributes;
  `:277-285` — the "no vendor client in core" decision the file sink sits inside.
- `docs/protect-your-own-api.md` — the YAML-only walkthrough; `:440` cites the
  `UNKNOWN_FIELD` refusal and the guide currently sends a reader with a nested
  response to `docs/extending.md`.
- `docs/extending.md` — positioned by task 58 as the escape hatch for nested
  responses; one level of nesting is no longer a reason to leave the no-code path.
- `examples/json-sources/customer-api.yaml` — the published, fully commented
  catalogue to extend with a nested sub-catalogue.
- Task files 60, 64 and 66 for the exact grammar, record format, CLI flags, exit
  codes and limitation wording.

## Acceptance
- [ ] `docs/architecture.md`'s flat-by-design paragraph is replaced with what is
      actually true after task 60: one level of named sub-catalogues, no recursion,
      no dotted paths or JSONPath, exact-match property names, and the unchanged
      `subject-json-path`. It still says what the mode refuses to become.
- [ ] `docs/architecture.md`'s boundary 7 entry names the audit file scan from task
      65 alongside `PiiLogScanTest`, and still says metric labels and trace
      attributes are unscanned.
- [ ] A new `docs/audit.md` documents the chain, `FileAuditSink` (single file,
      append, fsync per record, **no rotation**), and the offline verifier: how to
      run it, what each exit code means, and how a break is reported.
- [ ] `docs/audit.md` states the limitation in the owner's terms: tamper-evidence
      here is **intra-writer edit and delete detection**; truncation of the most
      recent records cannot be detected, because an append-only file with its tail
      removed verifies perfectly end to end, and detecting that needs an external
      checkpoint outside the operator's control which this release does not build.
      Durable append-only-ness is an operator responsibility — `O_APPEND`, WORM,
      object lock. A truncated final record is "possibly in flight", not proof of
      either tampering or health.
- [ ] No sentence in any file this task owns claims the audit log is tamper-proof,
      immutable, or independently complete, and none claims rotation, retention or
      shipping is provided.
- [ ] `examples/json-sources/customer-api.yaml` (or a sibling example beside it)
      carries a commented nested sub-catalogue that parses under task 60's grammar,
      and a test or documented command shows it loading rather than only asserting
      in prose.
- [ ] `docs/protect-your-own-api.md` shows the nested case end to end — the YAML,
      the response, the scrubbed result — and names the deeper-than-declared refusal
      code from task 60.
- [ ] `docs/extending.md` no longer lists "a nested response" as a reason to leave
      the configuration-driven path; it lists two levels or more, custom fetch
      logic, and models a catalogue cannot express.
- [ ] Every command and YAML fence in these files was actually run or loaded by
      whoever wrote it, against the real server where the claim depends on the
      transport — the SSE-not-bare-JSON lesson from tasks 56 and 58.

## Out of scope
- `docs/configuration.md`, `docs/quickstart.md` and `README.md` — task 59 owns all
  three, and must gain the nested-catalogue reference, `dataprism.identity.resolver`,
  and the `dataprism.audit.sink: hash-chained` properties there.
- `CHANGELOG.md` and `server.json` (task 70).
- Any source change.
