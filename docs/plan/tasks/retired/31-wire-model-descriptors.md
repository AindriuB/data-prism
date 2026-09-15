# 31 — Wire the model-descriptor resolver behind a configuration property

**Repo:** `.`
**Depends on:** 30
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only: the classification row for any bean this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ModelDescriptorsConfigurationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfigurationTest.java
- docs/configuration.md *(the new `dataprism.privacy` descriptor row and its surrounding prose only)*

## Goal

`core/descriptor` is roughly 320 lines — `DescriptorFieldMetadataResolver`,
`ModelDescriptors`, `ModelDescriptor` — with tests but no production caller
anywhere in the reactor. It is the only way to classify a third-party model an
operator cannot annotate, and it is currently dead. Give it a real caller: an
optional `dataprism.privacy` descriptor-file property that, when set, makes the
`FieldMetadataResolver` bean a `DescriptorFieldMetadataResolver` wrapping the
default one.

## Context

- `DescriptorFieldMetadataResolver.java:44-49` — the constructor to call:
  a delegate `FieldMetadataResolver` plus `Map<String, ModelDescriptor>`.
- `ModelDescriptors.java:35-57` — `fromYaml(InputStream)`, which already
  refuses a file with no `models` section and a model that is not a mapping.
- `DescriptorFieldMetadataResolver.java:118-127,180-190` — a descriptor may
  only tighten: it cannot un-classify a field, and
  `undeclaredFields = NON_SENSITIVE` throws. That is the fail-closed property
  this wiring must not weaken and must prove.
- `DataPrismAutoConfiguration.java:106-107` — the existing
  `dataPrismFieldMetadataResolver` bean, `@ConditionalOnMissingBean`, returning
  `DefaultFieldMetadataResolver`. The descriptor path replaces what that bean
  returns, or adds a bean beside it.
- `PrivacyExtensionPoints.java:43-60` — the classification map, swept by
  `AutoConfiguredBeanClassificationTest.java:25` over
  `DataPrismAutoConfiguration.class.getDeclaredMethods()`. Any new `@Bean`
  method in that class needs a row here or the sweep fails.
- `docs/configuration.md:59` — the `dataprism.privacy` row, where the new
  property's refusal behaviour belongs.
- `DataPrismProperties.java` is reformatted by task 30; branch from it.

## Acceptance

- [ ] With the new property unset, the resolver bean is still
      `DefaultFieldMetadataResolver` and no descriptor file is read — asserted
      in `ModelDescriptorsConfigurationTest`.
- [ ] With the property set to a readable descriptor file, the
      `FieldMetadataResolver` bean is a `DescriptorFieldMetadataResolver`
      delegating to `DefaultFieldMetadataResolver`, and a field classified only
      by the descriptor is scrubbed accordingly in the resulting pipeline.
- [ ] Fail closed on every bad input: the property set to a path that does not
      exist, to an unreadable file, to a file with no `models` section, and to
      a file whose descriptor sets `undeclaredFields: NON_SENSITIVE` each
      refuse startup with a `DataPrismConfigurationException` carrying a named
      code. Four tests, one per case. None falls back to the default resolver.
- [ ] The refusal codes are new and distinct, and each names the property, not
      the file's contents — no line of the descriptor file reaches the message.
- [ ] Every `@Bean` method this task adds to `DataPrismAutoConfiguration` has a
      matching row in `PrivacyExtensionPoints`, and
      `AutoConfiguredBeanClassificationTest` passes unchanged.
- [ ] `docs/configuration.md`'s `dataprism.privacy` row names the property, says
      it is optional, and states the refusal behaviour above. It claims nothing
      the tests do not prove.
- [ ] `mvn -q verify` is green for the full reactor.

## Out of scope

- Changing anything under `data-prism-core/src/main/java/.../core/descriptor/`.
  This task calls that package; task 28 owns two files in `data-prism-core` and
  the descriptor classes' behaviour is already tested.
- The three `DataPrismAutoConfiguration` defects in `PLAN.md` from task 25's
  verify — the `@ConditionalOnBean` wrong-cause message at `:225`, the untested
  `matchIfMissing=true` at `:188`, the `"embedded".equals` case sensitivity.
  They are in a file this task owns, but each is a behaviour change with its
  own acceptance criteria.
- Wiring descriptors into `ConfiguredJsonSourcesAutoConfiguration` in
  `data-prism-connectors-rest`, which hand-copies the orchestrator assembly.
- Recording the descriptor package as deferred in `PLAN.md`. This task resolves
  it by wiring instead, and only `scribe` writes `PLAN.md`.
