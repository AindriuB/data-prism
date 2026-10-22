package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review of task 222: the packaged server's environment resolver never reads a provider reference as a variable name. */
class EnvironmentResolverProviderReferenceTest {

    @Test
    void aProviderReferenceIsRefusedWithACodeAndNotLookedUpAsAnEnvironmentVariable() {
        HmacKeyReferenceResolver resolver = new ServerIntegrationsConfiguration().environmentHmacKeyReferenceResolver();
        // PATH is set in every environment: were the reference read as a name, this would resolve.
        assertThatThrownBy(() -> resolver.resolveProviderReference("k", "PATH"))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_KEY_PROVIDER:");
        assertThat(resolver.resolve("k", "PATH")).isNotEmpty();
    }
}
