package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The identity and re-identification maps: keyed names, and values the generator reproduces. */
public final class IdentityMapsBoundary implements AreaBoundary {

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.IDENTITY_MAP, PrivacyCluster.REIDENTIFICATION_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        PrivacyContext context = new PrivacyContext(in.scopeId(), PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
        if (map.equals(PrivacyCluster.IDENTITY_MAP)) {
            in.assertKeys(map, contents, in.identityKey());
            // The value is what the generator makes of the key's own inputs: it never saw a source value.
            assertThat(contents.get(in.identityKey())).isEqualTo(
                    in.generator().syntheticValue(in.subjectId(), in.namespace(), context));
        } else {
            in.assertKeys(map, contents, in.reidentificationKey());
            Object subject = contents.get(in.reidentificationKey());
            assertThat(subject).isEqualTo(in.subjectId());
            assertThat(in.synthetic()).isEqualTo(in.generator().syntheticValue((String) subject,
                    in.namespace(), context));
        }
    }
}
