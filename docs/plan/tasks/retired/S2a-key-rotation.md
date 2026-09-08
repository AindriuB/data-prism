# S2a — Key rotation

Finish the half of S2 that was split out. `keyId` is already pinned per scope in
`PseudonymisationVersion` and already threaded through both
`HmacSyntheticGenerator` and `HmacValueTokenSource`, which call
`SecretKeyProvider.secret(keyId)`. What is missing is a provider that can resolve
more than one key at a time.

Without it, rotating the signing key means every scope still running under the
previous key breaks: its pseudonyms change mid-investigation, and every reference
already made to one silently starts meaning someone else.

## Owns

- `data-prism-pseudonymisation/src/main/java/io/github/aindriub/dataprism/pseudonymisation/MultiKeySecretKeyProvider.java` (new)
- `data-prism-pseudonymisation/src/main/java/io/github/aindriub/dataprism/pseudonymisation/EnvironmentSecretKeyProvider.java` (modify)
- `data-prism-pseudonymisation/src/test/java/io/github/aindriub/dataprism/pseudonymisation/MultiKeySecretKeyProviderTest.java` (new)

Do not edit anything outside that list. In particular `data-prism-core`,
`data-prism-validation` and `data-prism-orchestration` belong to S4 and are being
changed concurrently.

## Depends on

None. S3 is merged.

## Acceptance

- `MultiKeySecretKeyProvider` holds several keys by id and resolves each. Two
  scopes pinned to different key ids resolve simultaneously through one
  generator, and each is stable.
- A scope pinned to a retired key keeps resolving until the key is removed
  explicitly. Removal is a deliberate act, not a side effect of adding a new key.
- An unknown key id throws, naming the id. It never falls back to a current or
  default key: substituting one silently changes every synthetic value in that
  scope while nothing appears to fail. `SecretKeyProvider`'s javadoc already
  states this and the test must prove it.
- Key material is never logged, never in an exception message, and never in
  `toString()`. Assert this — an accidental `record` would put every key in
  `toString()` by default.
- Keys shorter than the existing 32-character minimum are rejected at
  construction, matching `EnvironmentSecretKeyProvider`.
- `EnvironmentSecretKeyProvider` gains multi-key support consistently: it already
  reads `DATA_PRISM_HMAC_KEY_<KEYID>` per id, so verify it resolves several ids
  and add the test if missing. Keep its no-fallback behaviour.
- Existing pseudonymisation tests still pass unchanged, including the two golden
  vector files.
- `mvn -B verify` passes from the repository root.

## Context

- `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/SecretKeyProvider.java`
  — the SPI, and its javadoc explains why there is no fallback.
- `data-prism-pseudonymisation/src/main/java/io/github/aindriub/dataprism/pseudonymisation/StaticSecretKeyProvider.java`
  — the single-key implementation to mirror, including the defensive `clone()`.
- `docs/conventions.md` — the privacy rules a diff must satisfy, especially the
  one about values never reaching a log or exception message.
