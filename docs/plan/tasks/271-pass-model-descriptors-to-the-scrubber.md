# 271 — Pass the loaded model descriptors to the Spring-built scrubber

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 248 has merged
**Depends on:** 248 (adds the `JsonTreeScrubbingEngine(resolver, policies, synthetics, tokens, Map<String, ModelDescriptor> descriptors)` constructor)
**Wave:** 5 (filed 2026-10-23)
**Decisions:** a replaced resolver while a descriptor file is set refuses startup with `MODEL_DESCRIPTOR_RESOLVER_REPLACED` (coordinator, 2026-10-24, on fail-closed grounds). Today such a resolver silently drops the descriptor's tightening for scrubbing, not only for description.
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java *(the `dataPrismScrubber` method only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java *(insertions only: a public `descriptors()` accessor)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolverTest.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/descriptors/ScrubberDescriptorWiringTest.java *(new; in a sub-package so that it stays outside 210's `spring/boot/*.java` glob)*
- data-prism-spring-boot-autoconfigure/src/test/resources/scrubber-descriptors/** *(new fixture files only)*
- docs/plan/design/contract-register.md *(insertions only: the `MODEL_DESCRIPTOR_RESOLVER_REPLACED` row in section 3.5)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Breaking line)*

## Goal
248 lets the scrubber describe a chainable field declared in a descriptor, but only if the engine is given the descriptor map. The Spring-built engine (`PrivacyEngineWiring.dataPrismScrubber`, :156-158) uses the four-argument constructor, so in production a descriptor-declared chainable field is described without its key namespace. Pass the same map, loaded once, that the field-metadata resolver was built from. And refuse startup when a descriptor file is set but the resolver bean is not the one built from it, because then neither the scrubber nor the describer applies the file.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java:51-59 (the resolver bean, which loads the file through `dataPrismModelDescriptors(descriptorFile)` at :84) and :156-158 (the engine bean).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java:42-47: holds the validated, immutable map.
- Decided at filing (low stakes): the engine bean takes the map from the injected `FieldMetadataResolver`, as `metadata instanceof DescriptorFieldMetadataResolver d ? d.descriptors() : Map.of()`. The `Map.of()` branch is reached only when no descriptor file is set; with one set, a resolver that is not a `DescriptorFieldMetadataResolver` refuses startup (below). Why: the file is parsed once, so the resolver and the describer cannot disagree, and no new bean is needed. A new bean would change the `bean-inventory/*.txt` fixtures, which 210 owns in this wave. Reading the file a second time is not allowed.
- 249 makes the same change at the connectors' `javaFirst` engine (`ConfiguredJsonSourcesAutoConfiguration.java:177`) and depends on this task for the accessor.
- `ModelDescriptorsConfigurationTest.java:56-57`: the `WebApplicationContextRunner` shape to copy.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `DescriptorFieldMetadataResolver.descriptors()` returns the map it was built with, unmodifiable, and a test shows that mutating the result throws.
- [ ] `ScrubberDescriptorWiringTest`: with `dataprism.privacy.descriptor-file` naming a fixture that declares a chainable identifier (`chainable: true`, `keyNamespace`) on a model whose code has no chainable marking, the context's `JsonTreeScrubbingEngine` describes that field with the declared `keyNamespace` when links are enabled. Without a descriptor file it describes no `keyNamespace`. Mutant killed: passing `Map.of()` unconditionally.
- [ ] The descriptor file is read once per context: a test counts the reads or asserts that one file object backs both the resolver and the engine. The Outcome says which.
- [ ] With no descriptor file set, an application `FieldMetadataResolver` bean still replaces the default (`@ConditionalOnMissingBean` unchanged), and the engine gets `Map.of()`, which is what the four-argument constructor gave it.
- [ ] **Replaced resolver refuses startup.** When `dataprism.privacy.descriptor-file` is set but the `FieldMetadataResolver` bean is not a `DescriptorFieldMetadataResolver`, `dataPrismScrubber` throws `DataPrismConfigurationException` with code `MODEL_DESCRIPTOR_RESOLVER_REPLACED`. That covers an application bean, and a decorator or wrapper around one. The message is exactly `dataprism.privacy.descriptor-file is set but the FieldMetadataResolver bean is not the one built from it`. It names the property only: no file path, no file content, no class name.
  - A context test shows the refusal with an application resolver bean, and with a decorator bean that wraps a `DescriptorFieldMetadataResolver`.
  - The same beans start without the property.
  - There is no WARN path; nothing is logged instead of refusing.
- [ ] `RefusalCodes.sanitise("MODEL_DESCRIPTOR_RESOLVER_REPLACED")` returns the code unchanged (asserted in `ScrubberDescriptorWiringTest`), and the contract register's section 3.5 gains its row.
- [ ] **`CHANGELOG.md` `[Unreleased]`, Breaking, this line verbatim:** "Startup refuses with `MODEL_DESCRIPTOR_RESOLVER_REPLACED` when `dataprism.privacy.descriptor-file` is set and the application supplies its own `FieldMetadataResolver` bean, because that bean replaced the resolver the descriptor file is applied by. A deployer who decorates the resolver must build and wrap a `DescriptorFieldMetadataResolver` from the descriptor file itself and leave `dataprism.privacy.descriptor-file` unset, or drop the descriptor file."
- [ ] The bean inventories are unchanged (no bean added or removed).
- [ ] Every existing test in the two modules passes unmodified.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- The connectors' `javaFirst` engine (249).
- `DataPrismAssembly` in `data-prism-integration-tests`, which loads no descriptors.
- Any other method of `PrivacyEngineWiring`, including `dataPrismSensitivePatternValidator` (208) and `dataPrismModelDescriptors`.
- `describe_source` (250).
- Detecting a decorator that wraps a `DescriptorFieldMetadataResolver` internally. The class is final and a decorator is not one, so it refuses like any other replacement. The CHANGELOG line gives the way out.
