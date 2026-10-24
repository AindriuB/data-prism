package io.github.aindriub.dataprism.core.spi;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValueTokenSourceTest {

    @Test
    void unavailableReportsFalse() {
        assertThat(ValueTokenSource.unavailable().available()).isFalse();
    }

    @Test
    void aDecoratorThatDelegatesReportsFalse() {
        ValueTokenSource inner = ValueTokenSource.unavailable();
        ValueTokenSource decorator = new ValueTokenSource() {
            @Override
            public String hash(String value, PrivacyNamespace namespace, PrivacyContext context) {
                return inner.hash(value, namespace, context);
            }

            @Override
            public String token(String value, PrivacyNamespace namespace, PrivacyContext context) {
                return inner.token(value, namespace, context);
            }

            @Override
            public boolean available() {
                return inner.available();
            }
        };
        assertThat(decorator.available()).isFalse();
    }
}
