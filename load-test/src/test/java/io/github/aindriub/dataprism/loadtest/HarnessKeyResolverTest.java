package io.github.aindriub.dataprism.loadtest;

import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.loadtest.resolver.HarnessKeyResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** D2: the harness's resolver translates into the namespace the generated sources declare, and is registered. */
class HarnessKeyResolverTest {

    @Test
    void itTranslatesIntoTheNamespaceTheSyntheticSourcesDeclare() {
        HarnessKeyResolver resolver = new HarnessKeyResolver();

        assertThat(resolver.from().value()).isEqualTo(SourceGenerator.KEY_NAMESPACE);
        assertThat(resolver.to().value()).isEqualTo(SourceGenerator.REF_NAMESPACE);
        KeyResolver.Resolution resolution = resolver.resolve("m-00000001", null, 10);
        assertThat(resolution.keys()).containsExactly("ref-m-00000001");
        assertThat(resolution.more()).isFalse();
    }

    @Test
    void theAutoConfigurationIsListedForTheServerToLoad() throws Exception {
        Path imports = Path.of("src/main/resources/META-INF/spring/"
                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");

        assertThat(Files.readString(imports)).contains(
                "io.github.aindriub.dataprism.loadtest.resolver.HarnessKeyResolverAutoConfiguration");
    }
}
