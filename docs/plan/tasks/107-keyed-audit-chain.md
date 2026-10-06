# 107 — Key the audit chain with HMAC under a dedicated key (blocked on owner decision)

**Repo:** `.`
**Depends on:** 104, 106
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditChainKeyConfigurationTest.java *(new)*
- docs/audit.md *(a new "Keyed chain" section and the limitation paragraph on unkeyed SHA-256)*
- docs/configuration.md *(the `dataprism.audit` section only)*
- docs/architecture.md *(one new dated decision entry only)*

## Goal

**Do not start until the owner has decided to supersede the 2026-09-23
decision** that explicitly rejected keying the chain via
`SecretKeyProvider`. Its stated reasons are that the key would live in the
operator's own process, and that third parties could no longer verify the
chain without it.

If the owner approves, this task adds an HMAC-SHA256 chain mode under a key
that is distinct from the pseudonymisation key. Someone with file write
access but without that key can then no longer recompute a forged chain.
Records state which algorithm hashed them. The verifier refuses to call a
keyed chain intact without the key. The documentation states plainly what
the key does not defend against: the operator who holds it. The external
checkpoints from task 97 remain the control against that.

## Context

- `docs/architecture.md:317-338` — the 2026-09-23 decision and its rejected
  alternative, which is exactly this task.
- `AuditEventHash.compute` is unkeyed SHA-256. `parameterFingerprint`'s HMAC
  via `SecretKeyProvider` is the existing keyed precedent.
- `docs/audit.md:263-270` contains the "unkeyed SHA-256" limitation paragraph.
- Task 92's `recordVersion` mechanism is the way to mark a new hashing mode.

## Acceptance

- [ ] `AuditEvent` gains `chainAlgorithm`: `SHA-256` or `HMAC-SHA256:<keyId>`.
      Records written without a chain key are byte-identical in hash to task
      92's version-2 records. Version-1 and version-2 fixtures still verify.
- [ ] `dataprism.audit.chain-key-reference` selects the key through
      `SecretKeyProvider`. If it equals the pseudonymisation key reference,
      startup refuses with `AUDIT_CHAIN_KEY_REUSED`. If it is set but does not
      resolve, startup refuses with `AUDIT_CHAIN_KEY_UNRESOLVED`. One test
      covers each.
- [ ] The verifier takes `--chain-key-file <path>`. A keyed chain verified
      without it exits `1` with "keyed chain: cannot verify without the
      chain key", never 0. A forged keyed chain recomputed with plain SHA-256
      is reported as a break (exit 2). Tests cover both.
- [ ] `docs/audit.md` states that the key resists an actor with write access
      who lacks the key, does not resist whoever holds the key, and that
      checkpoints held outside that actor's reach remain the control. It
      never says "tamper-proof".
- [ ] `docs/architecture.md` gains a dated decision entry that supersedes
      2026-09-23, cites it, and records the cost: third-party verification
      now needs the key.
- [ ] `mvn -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify`
      passes.

## Out of scope

- Asymmetric signing or a KMS client. The 2026-09-08 decision rules out a
  vendor client in core.
- Keying the checkpoint file.
