package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Review of task 222: the privacy HMAC key goes through the same two resolver methods as the cluster keys. */
class HmacProviderReferenceTest {

    private static final byte[] FROM_PROVIDER = new byte[32];
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    static {
        Arrays.fill(FROM_PROVIDER, (byte) 'p');
    }

    /** {@code resolve} reads the environment (which holds nothing here); the provider method reads a map. */
    @Configuration(proxyBeanMethods = false)
    static class SplitResolver extends DataPrismAutoConfigurationTest.ReviewedHttpIntegrations {
        @Override @Bean HmacKeyReferenceResolver keys() {
            return new HmacKeyReferenceResolver() {
                @Override public byte[] resolve(String keyId, String reference) {
                    CALLS.add("env:" + reference);
                    throw new IllegalStateException("no such environment variable");
                }

                @Override public byte[] resolveProviderReference(String keyId, String reference) {
                    CALLS.add("provider:" + reference);
                    return Map.of("vault:hmac", FROM_PROVIDER).get(reference).clone();
                }
            };
        }
    }

    @Test
    void anHmacKeyGivenAsAProviderReferenceIsReadThroughTheProviderMethodEverywhere() {
        CALLS.clear();
        List<String> properties = new java.util.ArrayList<>();
        for (String p : OversightConfigurationTest.base("single-node")) {
            if (!p.startsWith("dataprism.privacy.hmac-key.environment-variable")) {
                properties.add(p);
            }
        }
        properties.add("dataprism.privacy.hmac-key.provider-reference=vault:hmac");
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(SplitResolver.class)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(SecretKeyProvider.class).secret("v1")).isEqualTo(FROM_PROVIDER);
                    assertThat(CALLS).isNotEmpty().allMatch(c -> c.equals("provider:vault:hmac"));
                });
    }
}
