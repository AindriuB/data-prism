# 271 — Pass the loaded model descriptors to the Spring-built scrubber

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 248 has merged
**Depends on:** 248 (adds the `JsonTreeScrubbingEngine(resolver, policies, synthetics, tokens, Map<String, ModelDescriptor> descriptors)` constructor)
**Wave:** 5 (filed 2026-10-23)
**Decisions:**
- **2026-10-24 (coordinator, on fail-closed grounds).** A replaced resolver while a descriptor file is set refuses startup with `MODEL_DESCRIPTOR_RESOLVER_REPLACED`. Today such a resolver silently drops the descriptor's tightening for scrubbing, not only for description.
- **2026-10-26 (coordinator, external review P1).** With the file set, the only accepted resolver is the instance this auto-configuration built from that file, checked by identity. Any other bean refuses, including a `DescriptorFieldMetadataResolver` the application built itself: one with empty descriptors would otherwise pass a class check and silently drop every tightening rule.

**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java *(the class declaration (`implements SmartInitializingSingleton`), a constructor taking the `ListableBeanFactory` and `DataPrismProperties`, one field for the built resolver, the `dataPrismFieldMetadataResolver` method (to record the instance it builds), `afterSingletonsInstantiated`, and the `dataPrismScrubber` method only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java *(insertions only: a public `descriptors()` accessor)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolverTest.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/descriptors/ScrubberDescriptorWiringTest.java *(new; in a sub-package so that it stays outside 210's `spring/boot/*.java` glob)*
- data-prism-spring-boot-autoconfigure/src/test/resources/scrubber-descriptors/** *(new fixture files only)*
- docs/plan/design/contract-register.md *(insertions only: the `MODEL_DESCRIPTOR_RESOLVER_REPLACED` row in section 3.5)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Breaking line)*

## Goal
248 lets the scrubber describe a chainable field declared in a descriptor, but only if the engine is given the descriptor map. The Spring-built engine (`PrivacyEngineWiring.dataPrismScrubber`, :156-158) uses the four-argument constructor, so in production a descriptor-declared chainable field is described without its key namespace.

This task does two things:
- Pass the engine the same map, loaded once, that the field-metadata resolver was built from.
- When a descriptor file is set, refuse startup unless the only `FieldMetadataResolver` bean is the exact instance this auto-configuration built from that file. Any other resolver means the scrubber, and the describer, may not apply the file.

## The identity check
It follows the approach of 203's `BypassCheck` (`SecurityWiring.java`): remember the instance the auto-configuration built, then compare instances, not names or classes, once every singleton exists.
- `dataPrismFieldMetadataResolver` (still `@ConditionalOnMissingBean`) records the `DescriptorFieldMetadataResolver` it builds in a field of `PrivacyEngineWiring`.
- `PrivacyEngineWiring` implements `SmartInitializingSingleton`. In `afterSingletonsInstantiated`, when `dataprism.privacy.descriptor-file` is set, it takes every `FieldMetadataResolver` bean (`getBeansOfType(FieldMetadataResolver.class, true, true)`). It looks through AOP proxies, nested ones included, to each target, as `BypassCheck` does. It refuses unless there is exactly one, and that one is the recorded instance.
- With no descriptor file set, it checks nothing.
- **Why not a separate bean, as `BypassCheck` is.** A new bean would change the `bean-inventory/*.txt` fixtures and `PrivacyExtensionPoints`, which 210 owns in this wave. The configuration class is already a singleton, and it is not `@ConditionalOnMissingBean`, so an application-supplied `JsonTreeScrubbingEngine` or `FieldMetadataResolver` cannot skip the check.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java:51-59 (the resolver bean, which loads the file through `dataPrismModelDescriptors(descriptorFile)` at :84) and :156-158 (the engine bean).
- `SecurityWiring.BypassCheck` (203, on the base branch): the identity pattern and its proxy unwrapping.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java:42-47: holds the validated, immutable map.
- **Decided at filing (low stakes):** the engine bean takes the map from the injected `FieldMetadataResolver`, as `metadata instanceof DescriptorFieldMetadataResolver d ? d.descriptors() : Map.of()`.
  - With a descriptor file set, the identity check guarantees the injected resolver is the one built from the file. Without one, the `Map.of()` branch is reached.
  - Why: the file is parsed once, so the resolver and the describer cannot disagree, and no new bean is needed. Reading the file a second time is not allowed.
- 249 makes the same change at the connectors' `javaFirst` engine (`ConfiguredJsonSourcesAutoConfiguration.java:177`) and depends on this task for the accessor.
- `ModelDescriptorsConfigurationTest.java:56-57`: the `WebApplicationContextRunner` shape to copy.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `DescriptorFieldMetadataResolver.descriptors()` returns the map it was built with, unmodifiable, and a test shows that mutating the result throws.
- [ ] `ScrubberDescriptorWiringTest`: with `dataprism.privacy.descriptor-file` naming a fixture that declares a chainable identifier (`chainable: true`, `keyNamespace`) on a model whose code has no chainable marking, the context's `JsonTreeScrubbingEngine` describes that field with the declared `keyNamespace` when links are enabled. Without a descriptor file it describes no `keyNamespace`. Mutant killed: passing `Map.of()` unconditionally.
- [ ] The descriptor file is read once per context: a test counts the reads or asserts that one file object backs both the resolver and the engine. The Outcome says which.
- [ ] With no descriptor file set, an application `FieldMetadataResolver` bean still replaces the default (`@ConditionalOnMissingBean` unchanged), the context starts, and the engine gets `Map.of()`, which is what the four-argument constructor gave it.
- [ ] **Only the auto-configured resolver is accepted.** With `dataprism.privacy.descriptor-file` set, startup fails with `DataPrismConfigurationException` code `MODEL_DESCRIPTOR_RESOLVER_REPLACED` in each of these cases, each a context test:
  - an application `DescriptorFieldMetadataResolver` built with empty descriptors;
  - an application `DescriptorFieldMetadataResolver` built from the same descriptor file;
  - an application resolver of another class;
  - a decorator bean wrapping a `DescriptorFieldMetadataResolver`;
  - an application resolver together with an application `JsonTreeScrubbingEngine` bean, so that `dataPrismScrubber` never runs.

  The auto-configured resolver alone starts, and so does the same resolver behind an AOP proxy. The message is exactly `dataprism.privacy.descriptor-file is set but the FieldMetadataResolver bean is not the one built from it`. It names the property only: no file path, no file content, no class name. There is no WARN path. Mutant killed: replacing the identity comparison with an `instanceof DescriptorFieldMetadataResolver` check lets the first two cases start.
- [ ] `RefusalCodes.sanitise("MODEL_DESCRIPTOR_RESOLVER_REPLACED")` returns the code unchanged (asserted in `ScrubberDescriptorWiringTest`), and the contract register's section 3.5 gains its row.
- [ ] **`CHANGELOG.md` `[Unreleased]`, Breaking, this line verbatim:** "Startup refuses with `MODEL_DESCRIPTOR_RESOLVER_REPLACED` when `dataprism.privacy.descriptor-file` is set and any `FieldMetadataResolver` bean other than the one Data Prism builds from that file is present, including a `DescriptorFieldMetadataResolver` the application built itself. A deployer who needs a custom resolver must leave `dataprism.privacy.descriptor-file` unset and build a `DescriptorFieldMetadataResolver` themselves."
- [ ] The bean inventories are unchanged (no bean added or removed).
- [ ] Every existing test in the two modules passes unmodified.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- The connectors' `javaFirst` engine (249).
- `DataPrismAssembly` in `data-prism-integration-tests`, which loads no descriptors.
- Any other method of `PrivacyEngineWiring`, including `dataPrismSensitivePatternValidator` (208) and `dataPrismModelDescriptors`.
- `DataPrismContractValidator` and `PropertiesValidation` (270 changes them).
- `describe_source` (250).
