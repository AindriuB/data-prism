package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.ToolAdmission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolOptionsTest {

    private static final ParameterFingerprinter FINGERPRINTER = new ParameterFingerprinter(
            StaticSecretKeyProvider.of("task-155-test-key-not-for-any-real-data-32b"));

    @Test
    @DisplayName("building the options without naming an admission fails at construction")
    void admissionIsNotDefaulted() {
        assertThatThrownBy(() -> ToolOptions.defaults().build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("admission");
        assertThatThrownBy(() -> ToolOptions.defaults().mdc(CorrelationMdc.off()).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("admission");
    }

    @Test
    @DisplayName("the defaults are OPTIONAL, no MDC and the shape mode")
    void defaultsMatchTheRemovedOverloads() {
        ToolOptions options = ToolOptions.defaults().noAdmission().build();

        assertThat(options.correlationRequirement()).isEqualTo(CorrelationRequirement.OPTIONAL);
        assertThat(options.mdc()).isSameAs(CorrelationMdc.off());
        assertThat(options.entityTypes().audited("CUSTOMER")).isEqualTo("CUSTOMER");
        assertThat(options.entityTypes().audited("acc-1")).isEqualTo(AuditedEntityTypes.UNREGISTERED);
        assertThat(options.fingerprinter()).isNull();
    }

    @Test
    @DisplayName("a real admission without a fingerprinter is refused, whichever way the record is reached")
    void realAdmissionNeedsAFingerprinter() {
        ToolAdmission real = ToolAdmission.none();
        assertThatThrownBy(() -> ToolOptions.defaults().admission(real, null).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("fingerprinter");
        assertThatThrownBy(() -> new ToolOptions(real, null, CorrelationRequirement.OPTIONAL,
                CorrelationMdc.off(), AuditedEntityTypes.shape()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("fingerprinter");
        assertThat(ToolOptions.defaults().admission(real, FINGERPRINTER).build().fingerprinter())
                .isSameAs(FINGERPRINTER);
    }

    @Test
    @DisplayName("every other component refuses null")
    void refusesNulls() {
        assertThatThrownBy(() -> ToolOptions.defaults().noAdmission().correlationRequirement(null).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("correlationRequirement");
        assertThatThrownBy(() -> ToolOptions.defaults().noAdmission().mdc(null).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("mdc");
        assertThatThrownBy(() -> ToolOptions.defaults().noAdmission().entityTypes(null).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("entityTypes");
    }
}
