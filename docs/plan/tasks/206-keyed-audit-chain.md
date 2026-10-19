# 206 — Add an opt-in keyed HMAC audit chain, with verifier and retention support

Renumbered from 191 on 2026-10-19 (id collision with the 0.7.0 plan). Scope not yet reconciled with docs/plan/roadmap.md.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` (f74757a at filing, post-0.6.0)
**Depends on:** none
**Decided (owner, 2026-10-15), D-1.0-4:** the keyed chain is opt-in and off by default. It uses a key
separate from the pseudonymisation key; using the same key is a startup refusal (task 211). It claims
only to resist a writer who lacks the key, not the operator. **This partly reverses the 2026-09-23
decision rejecting a keyed chain**, `docs/architecture.md:496-523`, which was reaffirmed on 2026-10-06 as
owner decision D2 when task 107 was dropped (`docs/plan/PLAN.md:75-77,678-681`). That decision's
reasoning still holds for the operator threat; task 213 records the partial reversal.

**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventHash.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainKey.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainMode.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCli.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java *(constructor and the verification call at :139 only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/** *(new test files; existing files: constructor call sites only, except `AuditChainVerifierCliTest.java` and `AuditEventHashTest.java`, insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditWiring.java *(the `new AuditRecorder(` call at :32 and the `new AuditRetention(` call at :95 only, passing the unkeyed mode)*

## Goal
`AuditEventHash` is unkeyed SHA-256, so anyone who can write the audit file can edit a record and
recompute every later hash into a chain that verifies (`docs/architecture.md:496-512`). Add an opt-in
mode in which each `eventHash` is HMAC-SHA256 over the same body under a dedicated audit key, and in
which the verifier and the retention purge check the chain with that key. Unkeyed behaviour must stay
byte-identical, and the existing disclaimers must not be softened.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventHash.java:11-189 — the version 1 to 3 bodies; the keyed form must hash exactly these bytes
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java:27-113,182-215 — constructors, the chain head, and the advance-after-durable-write ordering
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifier.java:108-206,543-548 — the public `verify` overloads and the recompute
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierCli.java:52-120,205-240 — `LIMITATION`, argument parsing, help text and exit codes; the disclaimer is load-bearing
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/retention/AuditRetention.java:55-60,139 — the purge verifies an expiring segment before deleting it, and deletes nothing if the chain breaks
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditCheckpoint.java — checkpoint lines carry head hashes; in keyed mode they carry keyed hashes, unchanged in shape
- docs/architecture.md:469-486 — segmentation and retention, "the chain is still unkeyed"
- Each writer chain starts at GENESIS per boot (`AuditRecorder.java:58-66`), so switching mode or key always starts a new chain under a new `instanceId`

## Acceptance
- [ ] `AuditEvent`, `AuditRecordFormat` and the record versions are unchanged. Nothing written to the audit or checkpoint file says whether the chain is keyed. The verifier is told the mode by its caller, so a forged file cannot downgrade itself.
- [ ] `AuditChainKey` is a public final class holding a key id and at least 32 bytes of key material, which it copies. Its `toString()` contains the key id and no key bytes, and a test asserts this.
- [ ] `AuditEventHash` gains one keyed entry point, `compute(AuditEvent, AuditChainKey)`, returning hex HMAC-SHA256 over the same body the unkeyed `compute(AuditEvent)` hashes. The existing pinned-literal tests in `AuditEventHashTest` pass unmodified. A new test recomputes one event's keyed hash independently with `javax.crypto.Mac` and compares.
- [ ] `AuditChainMode` is a public type with exactly two forms, `unkeyed()` and `keyed(AuditChainKey)`. `AuditRecorder`'s full constructor takes an `AuditChainMode` in place of its current signature (`null` is rejected). The two shorter constructors stay and mean unkeyed. In keyed mode every `eventHash`, and every checkpoint head, is the keyed form.
- [ ] `AuditChainVerifier` gains one public entry point that takes the audit path (file or segment directory), an optional checkpoint file, the minimum retention, a list of `AuditChainKey`, and an `allowUnkeyedWriters` flag. Every existing public `verify` overload keeps its signature and means unkeyed. No keyed variant of each overload is added.
- [ ] With keys given, each writer chain must verify entirely under exactly one supplied key. The report says which key id verified each writer. A writer that verifies under no key is a break. A writer that verifies only unkeyed is a break unless `allowUnkeyedWriters` is set, and then it is reported as unkeyed and counted.
- [ ] Tests prove each of these: a keyed file passes with its key; it fails with the wrong key; it fails with no key, rather than passing; an unkeyed file fails when a key is required; a keyed file in which one record was edited and every later hash recomputed unkeyed fails; and a directory whose older writers used retired key A and newer writers used key B passes when both keys are given.
- [ ] `AuditRetention` takes the same key list and flag and passes them to the verifier. A test proves that an expiring keyed segment whose key is not supplied is not deleted.
- [ ] `AuditChainVerifierCli` accepts `--hmac-key-file <path>`, repeatable, and `--allow-unkeyed-writers`. A key file holds the key id and the base64 key, in a format stated in `--help`. The CLI never prints key material. An unreadable or malformed key file exits `EXIT_UNREADABLE_INPUT` with a message that names the flag and not the file's contents.
- [ ] The existing `LIMITATION` text is unchanged. On every run with a key, the CLI also prints one line saying that a keyed chain resists a writer without the key, not anyone holding it, including the operator and the process that writes the file. When unkeyed writers were allowed, it prints a line with their count. A test asserts all three strings.
- [ ] Mutation proof: making the keyed entry point return the unkeyed hash makes at least one test fail. Name it in the close-out.
- [ ] `AuditWiring` passes `AuditChainMode.unkeyed()` and an empty key list, so Spring behaviour is unchanged until task 211.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- Properties, key resolution, the same-key refusal and `docs/configuration.md` (task 211).
- Size-capped segments (task 207, which edits `AuditChainVerifier` and `AuditRetention` after this task).
- Asymmetric signing and external publication of checkpoints. These remain the answer to the operator threat and are not part of this task.
- Collapsing the existing `verify` overloads. That is a roadmap API-cleanup item; doing it here would touch test files owned by tasks 203 and 211.
- The JSON projection, which is not hash-chained.
- `docs/audit.md`, the rotation procedure and `docs/architecture.md` (task 213).
