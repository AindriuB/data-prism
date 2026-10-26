# 271 — Pass the loaded model descriptors to the Spring-built scrubber

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 248 has merged
**Depends on:** 248 (adds the `JsonTreeScrubbingEngine(resolver, policies, synthetics, tokens, Map<String, ModelDescriptor> descriptors)` constructor)
**Wave:** 5 (filed 2026-10-23)
**Decisions:**
- **2026-10-24 (coordinator, on fail-closed grounds).** A replaced resolver while a descriptor file is set refuses startup with `MODEL_DESCRIPTOR_RESOLVER_REPLACED`. Today such a resolver silently drops the descriptor's tightening for scrubbing, not only for description.
- **2026-10-26 (coordinator, external review P1).** With the file set, the only accepted resolver is the instance this auto-configuration built from that file, checked by identity. Any other bean refuses, including a `DescriptorFieldMetadataResolver` the application built itself.
- **2026-10-26 (coordinator, internal review F1, decision a).** The check also covers what the running engine uses. Every `JsonTreeScrubbingEngine` bean must use the recorded resolver instance. An application engine built with its own resolver refuses, even when our resolver bean is present.
- **`@Lazy(false)` (internal review F2), an accepted cost.** Under global lazy mode, `@Lazy(false)` forces the configuration class to initialise eagerly, and with the file set it also forces the resolver and engine beans the check reads. This is a small cost, and it is accepted.

**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java *(only these parts:*
  - *the class declaration and its annotations (`implements SmartInitializingSingleton`, `@Lazy(false)`);*
  - *a constructor taking the `ListableBeanFactory` and `DataPrismProperties`, and one field for the built resolver;*
  - *the `dataPrismFieldMetadataResolver` method, to record the instance it builds;*
  - *`afterSingletonsInstantiated`;*
  - *the `dataPrismScrubber` method.)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/JsonTreeScrubbingEngine.java *(insertions only: a public `FieldMetadataResolver resolver()` accessor)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java *(insertions only: a public `descriptors()` accessor)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolverTest.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/descriptors/ScrubberDescriptorWiringTest.java *(new; in a sub-package so that it stays outside 210's `spring/boot/*.java` glob)*
- data-prism-spring-boot-autoconfigure/src/test/resources/scrubber-descriptors/** *(new fixture files only)*
- docs/plan/design/contract-register.md *(insertions only: the `MODEL_DESCRIPTOR_RESOLVER_REPLACED` row in section 3.5)*
- CHANGELOG.md *(`[Unreleased]`, insertions only: one Breaking line)*

## Goal
248 lets the scrubber describe a chainable field declared in a descriptor, but only if the engine is given the descriptor map. The Spring-built engine (`PrivacyEngineWiring.dataPrismScrubber`, :156-158) uses the four-argument constructor, so in production a descriptor-declared chainable field is described without its key namespace.

This task does two things:
- Pass the engine the descriptor map that the auto-configured resolver was built from. The file is loaded once.
- When a descriptor file is set, refuse startup unless both the resolver and the engine use exactly that resolver:
  - the only `FieldMetadataResolver` bean is the instance this auto-configuration built from the file;
  - every `JsonTreeScrubbingEngine` bean uses that same instance.

## "Is set"
`dataprism.privacy.descriptor-file` is set exactly when the value is non-null and not blank. This is the test `dataPrismFieldMetadataResolver` already uses (`PrivacyEngineWiring.java:54-56`). The check and the engine map use that same test, through one shared private method.

## The identity check
It follows the approach of 203's `BypassCheck` (`SecurityWiring.java`): remember the instance the auto-configuration built, then compare instances, not names or classes, once every singleton exists.

- **Recording.** `dataPrismFieldMetadataResolver` (still `@ConditionalOnMissingBean`) records the `DescriptorFieldMetadataResolver` it builds in a field of `PrivacyEngineWiring`.
- **When it runs.** `PrivacyEngineWiring` implements `SmartInitializingSingleton` and carries `@Lazy(false)`, so the check runs under `spring.main.lazy-initialization=true` too.
- **What it checks.** When the file is set, `afterSingletonsInstantiated` refuses with `MODEL_DESCRIPTOR_RESOLVER_REPLACED` unless all of these hold:
  - **Resolvers.** `getBeansOfType(FieldMetadataResolver.class, true, true)` holds exactly one bean. Unwrapped through AOP proxies, nested ones too, as `BypassCheck` does, that bean is the recorded instance. A JDK or CGLIB proxy whose target is the recorded instance passes.
  - **No other kinds of resolver bean.** A resolver produced by a `FactoryBean`, one registered under our bean name (bean-definition overriding), and a prototype- or custom-scoped resolver bean all refuse. Getting a non-singleton bean gives a new instance, which is never the recorded one. The check reads the definitions so that it says so rather than relying on that.
  - **Engines.** Every `JsonTreeScrubbingEngine` bean, unwrapped the same way, has `resolver()` equal by identity to the recorded instance, also after unwrapping. The orchestrator wiring injects a `JsonTreeScrubbingEngine` (`OrchestrationWiring.java:64`), so that is the type to check.
- **When it does nothing.** With no file set, it checks nothing.
- **Why not a separate bean, as `BypassCheck` is.** A new bean would change the `bean-inventory/*.txt` fixtures and `PrivacyExtensionPoints`, which 210 owns in this wave. The configuration class is already a singleton and is not `@ConditionalOnMissingBean`, so neither an application engine nor an application resolver can skip the check.

## The engine's descriptor map
`dataPrismScrubber` passes the recorded instance's `descriptors()` when the file is set, and `Map.of()` when it is not. It does not test the injected resolver with `instanceof`: a JDK proxy over our resolver would fail that test, and the descriptors would be lost silently.

## Context
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyEngineWiring.java:51-59 (the resolver bean, which loads the file through `dataPrismModelDescriptors(descriptorFile)` at :84) and :156-158 (the engine bean).
- `SecurityWiring.BypassCheck` (203, on the base branch): the identity pattern and its proxy unwrapping.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/descriptor/DescriptorFieldMetadataResolver.java:42-47: holds the validated, immutable map.
- 249 makes the same change at the connectors' `javaFirst` engine (`ConfiguredJsonSourcesAutoConfiguration.java:177`). It depends on this task for the accessor and unwraps proxies before reading the map.
- `ModelDescriptorsConfigurationTest.java:56-57`: the `WebApplicationContextRunner` shape to copy.

## Acceptance
- [ ] Tests are written first. The Outcome names each test and says which fail on the base commit and which are regression guards that pass on `main` too. These are expected to be guards:
  - the no-file cases;
  - the auto-configured resolver starting;
  - the proxy-starts case.
- [ ] `DescriptorFieldMetadataResolver.descriptors()` returns the map it was built with, unmodifiable, and a test shows that mutating the result throws. `JsonTreeScrubbingEngine.resolver()` returns the resolver the engine was constructed with.
- [ ] **Descriptor-declared chainable field is described.** In `ScrubberDescriptorWiringTest`, `dataprism.privacy.descriptor-file` names a fixture that declares a chainable identifier (`chainable: true`, `keyNamespace`) on a model whose code has no chainable marking.
  - With links enabled, the context's `JsonTreeScrubbingEngine` describes that field with the declared `keyNamespace`.
  - The same holds when the auto-configured resolver is wrapped in a JDK proxy, and the test asserts the described `keyNamespace` is present.
  - Without a descriptor file, no `keyNamespace` is described.
  - Mutants killed: passing `Map.of()` unconditionally; taking the map by `instanceof` on the injected resolver (the JDK-proxy case loses it).
- [ ] **Read once.** The descriptor file is read once per context: a test counts the reads, or asserts that one file object backs both the resolver and the engine. The Outcome says which. Mutant killed: calling `dataPrismModelDescriptors` a second time from `dataPrismScrubber`, which the test must catch.
- [ ] **No file set.** These cases start, and the engine gets `Map.of()`:
  - an application `FieldMetadataResolver` bean (`@ConditionalOnMissingBean` unchanged);
  - `dataprism.privacy.descriptor-file` set to a blank value (`" "`) together with an application resolver.
- [ ] **Refusals.** With `dataprism.privacy.descriptor-file` set, each of the following fails startup with `DataPrismConfigurationException` code `MODEL_DESCRIPTOR_RESOLVER_REPLACED`, each in its own context test:
  - an application `DescriptorFieldMetadataResolver` built with empty descriptors;
  - an application `DescriptorFieldMetadataResolver` built from the same descriptor file;
  - an application resolver of another class;
  - a decorator bean wrapping a `DescriptorFieldMetadataResolver`;
  - a resolver produced by a `FactoryBean`;
  - a resolver bean registered under the name `dataPrismFieldMetadataResolver` with bean-definition overriding enabled;
  - a prototype-scoped resolver bean;
  - an application `JsonTreeScrubbingEngine` bean built with `new DefaultFieldMetadataResolver()`, while our resolver bean is present and no application resolver bean exists;
  - an application resolver together with an application `JsonTreeScrubbingEngine` bean;
  - with `spring.main.lazy-initialization=true`, an application resolver.
- [ ] **Accepted with the file set.** The auto-configured resolver and engine start, and so does the same resolver behind an AOP proxy.
- [ ] **The message.** It is exactly `dataprism.privacy.descriptor-file is set but the FieldMetadataResolver bean is not the one built from it, or an engine does not use it`. It names the property only: no file path, no file content, no class name. There is no WARN path.
- [ ] **Mutants killed by the refusal tests:**
  - an `instanceof DescriptorFieldMetadataResolver` check in place of identity: the first two refusal cases start;
  - dropping the engine check: the `DefaultFieldMetadataResolver` engine case starts;
  - dropping `@Lazy(false)`: the lazy case starts.
- [ ] `RefusalCodes.sanitise("MODEL_DESCRIPTOR_RESOLVER_REPLACED")` returns the code unchanged (asserted in `ScrubberDescriptorWiringTest`), and the contract register's section 3.5 gains its row.
- [ ] **`CHANGELOG.md` `[Unreleased]`, Breaking, this line verbatim:** "Startup refuses with `MODEL_DESCRIPTOR_RESOLVER_REPLACED` when `dataprism.privacy.descriptor-file` is set and either any `FieldMetadataResolver` bean other than the one Data Prism builds from that file is present (including a `DescriptorFieldMetadataResolver` the application built itself), or a `JsonTreeScrubbingEngine` bean does not use that resolver. A deployer who needs a custom resolver or engine must leave `dataprism.privacy.descriptor-file` unset and build a `DescriptorFieldMetadataResolver` themselves."
- [ ] The bean inventories are unchanged (no bean added or removed).
- [ ] Every existing test in the two modules passes unmodified.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- The connectors' `javaFirst` engine (249).
- `DataPrismAssembly` in `data-prism-integration-tests`, which loads no descriptors.
- Any other method of `PrivacyEngineWiring`, including `dataPrismSensitivePatternValidator` (208) and `dataPrismModelDescriptors`.
- Any other part of `JsonTreeScrubbingEngine` (270 changes its scrub paths in wave 6).
- `DataPrismContractValidator` and `PropertiesValidation` (270 changes them).
- `describe_source` (250).
