package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver.CanonicalId;
import io.github.aindriub.dataprism.core.IdentityResolver.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MappedIdentityResolverTest {

    private final MappedIdentityResolver resolver =
            new MappedIdentityResolver(ExampleIdentityMapping.keysByCanonicalId());

    @Test
    void resolvesEachSourceItsOwnKeyToTheSameCanonicalId() {
        assertThat(resolver.resolve(new SourceRef("customer", "C-1001")))
                .isEqualTo(new CanonicalId("cust-001"));
        assertThat(resolver.resolve(new SourceRef("billing", "B-77")))
                .isEqualTo(new CanonicalId("cust-001"));
        assertThat(resolver.resolve(new SourceRef("crm", "CRM-42")))
                .isEqualTo(new CanonicalId("cust-001"));
    }

    @Test
    void expandOmitsASourceThatDoesNotKnowTheSubject() {
        List<SourceRef> refs = resolver.expand(new CanonicalId("cust-002"), List.of("customer", "billing", "crm"));

        assertThat(refs).containsExactlyInAnyOrder(
                new SourceRef("customer", "C-1002"),
                new SourceRef("crm", "CRM-43"));
        assertThat(refs).noneMatch(ref -> ref.sourceName().equals("billing"));
    }

    @Test
    void resolveRejectsAnUnknownSourceKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolve(new SourceRef("customer", "does-not-exist")))
                .withMessageContaining("customer")
                .withMessageContaining("does-not-exist");
    }

    @Test
    void blankInputIsRejectedByTheRecordsThemselves() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceRef("customer", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceRef(" ", "C-1001"));
        assertThatIllegalArgumentException().isThrownBy(() -> new CanonicalId(""));
    }
}
