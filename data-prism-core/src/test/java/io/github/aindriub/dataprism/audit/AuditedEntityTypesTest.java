package io.github.aindriub.dataprism.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuditedEntityTypesTest {

    private static final String SENTINEL = "<unregistered>";

    @Test
    void sentinelValueIsStable() {
        assertThat(AuditedEntityTypes.UNREGISTERED).isEqualTo(SENTINEL);
    }

    @Test
    void shapeModeAuditsAnUpperCaseIdentifierVerbatim() {
        AuditedEntityTypes types = AuditedEntityTypes.shape();
        assertThat(types.audited("CUSTOMER")).isEqualTo("CUSTOMER");
        assertThat(types.audited("ORDER_LINE_2")).isEqualTo("ORDER_LINE_2");
        // The documented residual: an upper-case token passes the shape.
        assertThat(types.audited("ACC123")).isEqualTo("ACC123");
    }

    @Test
    void shapeModeAuditsEverythingElseAsTheSentinel() {
        AuditedEntityTypes types = AuditedEntityTypes.shape();
        for (String raw : new String[] {"ACC-1", "customer", "John Smith", "a@b.c", "", "  ", " CUSTOMER",
                "CUSTOMER ", "1CUSTOMER", "_X", SENTINEL, "A".repeat(65), null}) {
            assertThat(types.audited(raw)).as("raw=%s", raw).isEqualTo(SENTINEL);
        }
    }

    @Test
    void shapeModeAcceptsExactlySixtyFourCharacters() {
        assertThat(AuditedEntityTypes.shape().audited("A".repeat(64))).isEqualTo("A".repeat(64));
    }

    @Test
    void emptyCollectionMeansShapeMode() {
        AuditedEntityTypes types = AuditedEntityTypes.of(List.of());
        assertThat(types.isShapeMode()).isTrue();
        assertThat(types.audited("CUSTOMER")).isEqualTo("CUSTOMER");
        assertThat(types.audited("ACC-1")).isEqualTo(SENTINEL);
    }

    @Test
    void listModeAuditsOnlyExactMembersVerbatim() {
        AuditedEntityTypes types = AuditedEntityTypes.of(List.of("CUSTOMER", "Case-File"));
        assertThat(types.isShapeMode()).isFalse();
        assertThat(types.audited("CUSTOMER")).isEqualTo("CUSTOMER");
        assertThat(types.audited("Case-File")).isEqualTo("Case-File");
        // Passes the shape, but the shape fallback is off once a list is set.
        assertThat(types.audited("ORDER")).isEqualTo(SENTINEL);
        assertThat(types.audited("customer")).isEqualTo(SENTINEL);
        assertThat(types.audited(" CUSTOMER")).isEqualTo(SENTINEL);
        assertThat(types.audited("CUSTOMER ")).isEqualTo(SENTINEL);
        assertThat(types.audited("")).isEqualTo(SENTINEL);
        assertThat(types.audited(null)).isEqualTo(SENTINEL);
        assertThat(types.audited(SENTINEL)).isEqualTo(SENTINEL);
    }

    @Test
    void ofRejectsNamesThatDoNotMatchThePattern() {
        for (String bad : new String[] {"", " ", "1ABC", "A B", "A".repeat(65), SENTINEL, "A@B"}) {
            assertThatThrownBy(() -> AuditedEntityTypes.of(List.of(bad)))
                    .as("bad=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_AUDIT_ENTITY_TYPE");
        }
    }

    @Test
    void ofRejectsNullEntries() {
        assertThatThrownBy(() -> AuditedEntityTypes.of(java.util.Arrays.asList("CUSTOMER", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_AUDIT_ENTITY_TYPE");
    }

    @Test
    void rejectionMessageDoesNotEchoTheValue() {
        assertThatThrownBy(() -> AuditedEntityTypes.of(List.of("ACC 1")))
                .hasMessageNotContaining("ACC 1");
    }
}
