# 117 — Hash audit record version 2 over an unambiguous, length-prefixed encoding

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** 102
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventHash.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCli.java *(the `LIMITATION` text and help text only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java *(class Javadoc only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecordFormat.java *(Javadoc only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/**
- docs/audit.md *(the hashed-fields paragraphs under "`FileAuditSink`" and the "What it proves" paragraph under "What this does and does not prove" only)*

## Goal

`AuditEventHash` builds a version-2 record's hash body by joining fields
with `|`, set elements with `,` and disposition entries with `,` and `=`,
none of them escaped. Two different records can therefore hash identically,
so an edit that moves a separator between fields cannot be detected. Record
version 2 has not been released, so its hash body can still change. This task
gives v2 a length-prefixed encoding that cannot be ambiguous, and leaves v1
hashing byte-identical so committed v1 chains still verify.

## Context

- `AuditEventHash.java:47-70`: the 23-argument `compute`. The v1 body is
  `String.join("|", …)` over nineteen fields. The v2 part appends `dispositions`,
  `approvalId` and `approverId` with the same joining.
- `recordVersion` is not in the hash body today. The body's shape is the only
  thing that depends on it.
- `AuditEvent` rejects a disposition value that is not a `PrivacyAction` name or
  `REFUSED`. Disposition keys are not restricted, so they may contain `,`
  and `=`.
- `AuditRecordVersionTest.versionOneHashCoversExactlyTheNineteenOriginalFields`
  and `committedVersionOneChainVerifiesIntact` (`v1-chain.jsonl`) pin v1.
  No v2 hash is pinned anywhere in the repository today.
- `AuditChainVerifierCli.java:47-67` `LIMITATION` lists only the nineteen v1
  fields. `AuditChainVerifier.java:21-23` refers to them in its Javadoc.
- `docs/audit.md:75-85` lists the hashed fields. `:243-246` says "nineteen
  hashed fields".
- Task 102 (in flight) owns `audit/**` until it merges, which is why this task
  depends on it. Task 109 adds version 3 on top of the encoding defined here.
  Write the encoding's Javadoc so that 109 can extend it by appending one field.

## Acceptance

- [ ] For `recordVersion >= 2`, the hash input is the concatenation of
      `enc(x)` over this fixed order:
      1. the version as a decimal string;
      2. the sixteen scalar fields, in the existing v1 order, from `eventId`
         to `correlationId`;
      3. `sourceSystems`, then `rejectedArguments`. Each is written as
         `enc(count)` followed by its elements, sorted by `String.compareTo`,
         each written with `enc`;
      4. `previousHash`;
      5. `fieldDispositions`, written as `enc(count)` followed by `enc(key)`
         and `enc(value)` for each entry, in key order;
      6. `approvalId`, then `approverId`.

      `enc(s)` is `<decimal UTF-8 byte length of s>:<s>`. A null scalar is
      written as `~`, which no `enc` output can start with. The Javadoc on
      `AuditEventHash` states this layout exactly.
- [ ] The v1 overload, and the 23-argument overload with `recordVersion` 1,
      produce the same hex output as before this task. A test pins the v1
      golden vector, and `committedVersionOneChainVerifiesIntact` passes
      unchanged.
- [ ] `src/test/resources/audit/hash-vectors.txt` (new) pins one v1 and one v2
      input with its expected hash, using synthetic values only. A test
      recomputes both and asserts that they match. The file format leaves
      room for task 109 to append a v3 line.
- [ ] A test builds the following v2 pairs and asserts that the two records in
      each pair have different hashes:
      - `sourceSystems` `{"a,b"}` against `{"a","b"}`;
      - `fieldDispositions` `{"p=MASK,q": "REDACT"}` against
        `{"p": "MASK", "q": "REDACT"}`;
      - `principalId` `"a|b"` with `clientId` `"c"` against `principalId`
        `"a"` with `clientId` `"b|c"`;
      - a null `caseId` against `caseId` `"~"`.

      The same test asserts that the first pair still hashes equal at
      `recordVersion` 1, as a pinned statement of the v1 limitation. The
      close-out shows that the v2 assertions fail when run against the
      pre-change `AuditEventHash`.
- [ ] A v2 record whose `recordVersion` is edited to `1` in the file is
      reported as a break by the verifier. A test asserts this.
- [ ] `versionTwoChainWithEditedDispositionsIsABreak` and every other
      existing verifier and recorder test pass. Edits to existing tests change
      only expected v2 hash values, and the close-out lists each one.
- [ ] `AuditChainVerifierCli`'s `LIMITATION` names the v1 fields and, for
      version 2, `recordVersion`, `fieldDispositions`, `approvalId` and
      `approverId`. `AuditChainVerifierCliTest` asserts that all four names
      appear. The `AuditChainVerifier` class Javadoc agrees with it.
- [ ] `docs/audit.md` states that v2 hashes a length-prefixed encoding that
      includes `recordVersion`. It also states that v1 records keep the
      original `|`/`,` joining, under which some distinct v1 records can share
      a hash. It does not say "tamper-proof".
- [ ] `mvn -pl data-prism-core,data-prism-integration-tests -am verify` passes.

## Out of scope

- Version 3 and `externalCorrelationId`. That is task 109.
- `AuditRecordFormat`'s on-disk field layout, which does not change. Only its
  Javadoc may mention the hash.
- A keyed chain (D2).
- The sample runs under "What a run actually looks like" in `docs/audit.md`.
  They are v1 runs with random instance ids, and stay as they are.
- The "Retention" and directory-mode sections of `docs/audit.md` (task 102),
  "Joining to your AI-system logs" (task 106) and the `policyDecision` section
  (task 121).
