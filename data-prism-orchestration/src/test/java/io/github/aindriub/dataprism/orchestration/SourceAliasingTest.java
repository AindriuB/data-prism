package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a source is named or aliased is a capability check on the caller, not a static switch. */
class SourceAliasingTest {

    private static final PrivacyContext CONTEXT = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);

    private final SourceAliasing aliasing = new SourceAliasing(
            new HmacValueTokenSource(StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data")));

    @Test
    @DisplayName("a caller without the capability sees an alias, never the real name")
    void aliasesWithoutCapability() {
        InvestigationContext noCapability = new InvestigationContext(
                "investigator-1", "client-1", "CASE-1", Set.of());

        String name = aliasing.nameFor("customer-api", noCapability, CONTEXT);

        assertThat(name).isNotEqualTo("customer-api");
    }

    @Test
    @DisplayName("a caller holding EXPOSE_SOURCE_NAMES sees the real name")
    void namesWithCapability() {
        InvestigationContext exposed = new InvestigationContext(
                "investigator-1", "client-1", "CASE-1", Set.of(Capability.EXPOSE_SOURCE_NAMES));

        String name = aliasing.nameFor("customer-api", exposed, CONTEXT);

        assertThat(name).isEqualTo("customer-api");
    }

    @Test
    @DisplayName("the alias is stable within a scope")
    void aliasIsStableWithinScope() {
        InvestigationContext noCapability = new InvestigationContext(
                "investigator-1", "client-1", "CASE-1", Set.of());

        String first = aliasing.nameFor("customer-api", noCapability, CONTEXT);
        String second = aliasing.nameFor("customer-api", noCapability, CONTEXT);

        assertThat(second).isEqualTo(first);
    }
}
