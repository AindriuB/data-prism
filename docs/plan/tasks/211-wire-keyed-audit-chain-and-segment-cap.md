# 211 — Wire the keyed audit chain, retired audit keys and the segment size cap into configuration

Renumbered from 196 on 2026-10-19 (id collision with the 0.7.0 plan). Scope not yet reconciled with docs/plan/roadmap.md.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` once 206, 207 and 210 have merged
**Depends on:** 206, 207, 210 (210 only because both edit the shared Spring files below)
**Serialised with:** 209, 210, 212
**Decided (owner, 2026-10-15), D-1.0-4:** the keyed chain is opt-in and off by default. It uses a key
separate from the pseudonymisation key, and configuring the same key is a startup refusal. It claims
only to resist a writer without the key, not the operator.

**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditWiring.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditSinkSelection.java *(`openPrimary`, :206-220, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditChainKeyResolution.java *(new; name may differ)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(the audit validation methods only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only, if a bean is added)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/configuration-metadata-gaps.txt *(insertions only, for map sub-keys)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/KeyedAuditChainConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditSegmentSizeConfigurationTest.java *(new)*
- docs/configuration.md *(the `dataprism.audit` rows and the prose around them only)*

## Goal
Expose task 206's keyed chain and task 207's segment size cap through `dataprism.audit.*`. A key that is
configured but cannot be resolved, or that is the pseudonymisation key, refuses startup. Retention must
be able to verify segments written under a retired key, or it would stop purging after a key rotation.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditWiring.java:25-40,88-100 — the recorder and retention beans
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditSinkSelection.java:205-225 — `openPrimary` and its sanctioned catch-and-log (docs/conventions.md, "Deliberate, reviewed exception")
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyProperties.java:40-80 and HmacKeyReferenceResolver.java — the `hmac-key` shape (`key-id`, `environment-variable`, `provider-reference`) and the resolver SPI to reuse, not copy
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ConfiguredSecretKeyProvider.java — how the pseudonymisation key is resolved, for the same-key check
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditMaintenance.java and AuditIntegrityHealth.java — the retention run and the `auditIntegrity` health contributor that reports its failure code
- data-prism-spring-boot-autoconfigure/src/test/resources/configuration-metadata-gaps.txt:3-9 — the `outside-binding` precedent for map sub-keys

## Acceptance
- [ ] `dataprism.audit.hmac-key.key-id`, `.environment-variable` and `.provider-reference` bind, with exactly one of the last two required when the group is set. There is no inline key-value property. Unset means unkeyed, and a test asserts that default.
- [ ] The group is valid only with `sink=hash-chained`; otherwise startup refuses with `AUDIT_HMAC_REQUIRES_HASH_CHAINED`. A reference that resolves to nothing refuses with `AUDIT_HMAC_KEY_UNRESOLVED`. Key material shorter than 32 bytes refuses with `AUDIT_HMAC_KEY_TOO_SHORT`. Neither message contains the reference value or any key byte.
- [ ] A key equal in bytes to the pseudonymisation key, compared in constant time, refuses with `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` (D-1.0-4). So does the same reference string. A test covers both.
- [ ] `dataprism.audit.retired-hmac-keys.<key-id>.environment-variable` or `.provider-reference` binds a map of verification-only keys. They are passed to `AuditRetention` together with the current key, and never to the recorder. A retired key under the same id as the current key refuses with `DUPLICATE_AUDIT_KEY_ID`. A retired key equal to the pseudonymisation key refuses with `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY`.
- [ ] With a key configured, `KeyedAuditChainConfigurationTest` records events through the wired recorder and verifies the written file with `AuditChainVerifier` and the key. It also shows that verification without the key reports a break.
- [ ] Retention in keyed mode passes `allowUnkeyedWriters=false`. A test shows that an expiring segment whose key is neither current nor retired is not deleted, and that `auditIntegrity` health reports DOWN with the retention failure code.
- [ ] `dataprism.audit.segment-max-size` binds as a Spring `DataSize`, is valid only with `dataprism.audit.directory` (otherwise `AUDIT_SEGMENT_SIZE_REQUIRES_DIRECTORY`), and must be at least 1MB (otherwise `INVALID_AUDIT_SEGMENT_SIZE`). `AuditSegmentSizeConfigurationTest` writes past a small cap through the wired sink and finds two segments for one day.
- [ ] `docs/configuration.md` documents each property, default and refusal code. It says the keyed chain resists a writer without the key and does not resist anyone holding it, the operator included. It does not say or imply more than that.
- [ ] `ConfigurationMetadataDocumentedTest`, `PropertyNamesFrozenTest`, `AutoConfiguredBeanClassificationTest` and `AutoConfiguredBeanInventoryTest` pass. The diffs to their checked-in lists contain only this task's lines.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Any change under `data-prism-core` (tasks 206 and 207).
- Key rotation tooling. Rotation is a documented procedure (task 213), not code.
- The JSON projection's segments.
- Security, free-text and cluster wiring (tasks 210, 209 and 212).
- `docs/audit.md` and every `docs/` file other than `docs/configuration.md` (task 213).
