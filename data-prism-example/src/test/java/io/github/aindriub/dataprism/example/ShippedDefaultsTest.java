package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the shipped defaults to what the tests assert, rather than to a
 * scoped-down stand-in a test builds for itself.
 *
 * <p>Three controls in particular exist, are tested, and were not what actually
 * shipped: {@code DataPrismAssembly} minted an {@code InvestigationContext}
 * holding {@code EXPOSE_SOURCE_NAMES} by construction, {@code
 * ExampleApplication}'s {@code developer} role was asserted on by nothing
 * because every capability test built its own policy, and -- the third source
 * of a shipped role, task 43 adds -- {@code application.yaml}'s own
 * {@code investigator}/{@code privileged-investigator} roles were never bound
 * and read back by any test at all, so a capability quietly added there would
 * fail nothing. This class asserts on the actual factories, and the actual
 * shipped file, every caller uses.
 */
class ShippedDefaultsTest {

    @Test
    @DisplayName("the shipped investigation context holds exactly GET_ENTITY_CONTEXT and COMPARE_ENTITY_SOURCES, "
            + "never EXPOSE_SOURCE_NAMES")
    void standardAssemblyContextIsMasked() {
        var context = DataPrismAssembly.standard().investigationContext();

        assertThat(context.capabilities())
                .isEqualTo(Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES));
        assertThat(context.has(Capability.EXPOSE_SOURCE_NAMES)).isFalse();
    }

    @Test
    @DisplayName("the shipped developer role holds exactly GET_ENTITY_CONTEXT and COMPARE_ENTITY_SOURCES")
    void shippedDeveloperRoleIsNarrow() {
        SecurityPolicy policy = ExampleApplication.shippedSecurityPolicy();

        Set<String> capabilities = policy.capabilitiesFor(Set.of("developer"));

        assertThat(capabilities).isEqualTo(Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES));
        assertThat(capabilities).doesNotContain(Capability.EXPOSE_SOURCE_NAMES);
    }

    /**
     * The third source of a shipped role, and the one nothing pinned before
     * task 43: {@code application.yaml} itself, bound the same way
     * {@code DataPrismAutoConfiguration} binds it at startup -- Spring's own
     * {@link Binder} over a {@link YamlPropertySourceLoader}-loaded property
     * source, never a hand-rolled YAML parse that could drift from what
     * production actually does with the file. {@code investigator} is the
     * baseline: exactly the same two capabilities the two Java factories above
     * are pinned to. {@code privileged-investigator} additionally holds
     * {@code EXPOSE_SOURCE_NAMES}, and nothing else -- an unnoticed third
     * capability added to either role, in the file alone, now fails this test.
     */
    @Test
    @DisplayName("application.yaml grants investigator exactly GET_ENTITY_CONTEXT and COMPARE_ENTITY_SOURCES, and "
            + "privileged-investigator exactly those two plus EXPOSE_SOURCE_NAMES")
    void applicationYamlRoleGrantsMatchTheShippedDefaults() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        loaded.forEach(propertySources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        DataPrismProperties properties = binder.bind("dataprism", DataPrismProperties.class).get();

        Map<String, List<String>> roles = properties.getSecurityPolicy().getRoles();

        assertThat(Set.copyOf(roles.get("investigator")))
                .isEqualTo(Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES));
        assertThat(Set.copyOf(roles.get("privileged-investigator")))
                .isEqualTo(Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES,
                        Capability.EXPOSE_SOURCE_NAMES));
    }
}
