package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies the durable-cluster boundary by recomputing every stored value from
 * its decomposed key. Identity entries must equal the generator output for the
 * key's scope, namespace and subject; re-identification keys must contain the
 * generator output for their stored subject; and budget values must be counts.
 * This is the right property because the generator never receives a source
 * value, so a raw value cannot satisfy it, unlike a brittle scan for a fixed
 * pseudonym. The raw fixture enters the real tree-scrubbing path before it
 * invokes the cache-backed generator; the separate scan catches a writer that
 * stores that source value alongside otherwise valid state.
 */
class HazelcastStoredValueBoundaryTest {

    private static final String RAW_SENSITIVE_VALUE = "TEST-PPSN-0000000ZZ";
    private static final String SUBJECT_ID = "subject-42";
    private static final String SCOPE_ID = "CASE-42";

    private PrivacyCluster cluster;

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void distributedMapsContainOnlyRecomputablePrivacyState() {
        var generator = new DeterministicGenerator();
        var cached = new CachingSyntheticValueSource(generator, start());
        PrivacyContext context = scope(SCOPE_ID);

        String synthetic = scrubber(cached).scrub(
                new SourceRecord(SUBJECT_ID, RAW_SENSITIVE_VALUE), context).tree()
                .get("fullName").asText();
        assertThat(new ScopeIdentityIndex(cluster)
                .subjectFor(SCOPE_ID, PrivacyNamespace.PERSON_NAME, synthetic))
                .contains(SUBJECT_ID);
        assertThat(new HazelcastScopeBudget(cluster).tryRead(SCOPE_ID, SUBJECT_ID, 3)).isTrue();

        Map<String, IMap<Object, Object>> maps = privacyMaps();
        assertThat(maps).isNotEmpty();
        assertThat(List.copyOf(maps.values())).allSatisfy(map -> assertThat(map.size()).isPositive());
        assertRawValueIsAbsent(maps);
        maps.forEach((name, map) -> assertMapContainsOnlyExpectedState(name, map, generator));
    }

    private PrivacyCluster start() {
        // Loopback only, no discovery: this test must never find a real cluster.
        Config config = new Config();
        config.setClusterName("dataprism-boundary-test-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, true);
        return cluster;
    }

    private static JsonTreeScrubbingEngine scrubber(SyntheticValueSource syntheticValues) {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII,
                        PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE)));
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), syntheticValues);
    }

    @SuppressWarnings("unchecked")
    private Map<String, IMap<Object, Object>> privacyMaps() {
        Map<String, IMap<Object, Object>> maps = new java.util.LinkedHashMap<>();
        for (DistributedObject object : cluster.instance().getDistributedObjects()) {
            if (object instanceof IMap<?, ?> map) {
                maps.put(object.getName(), (IMap<Object, Object>) map);
            }
        }
        assertThat(maps.keySet()).containsExactlyInAnyOrder(
                PrivacyCluster.IDENTITY_MAP,
                PrivacyCluster.REIDENTIFICATION_MAP,
                PrivacyCluster.BUDGET_MAP);
        return maps;
    }

    private static void assertMapContainsOnlyExpectedState(String name, IMap<Object, Object> map,
                                                            SyntheticValueSource generator) {
        switch (name) {
            case PrivacyCluster.IDENTITY_MAP -> map.forEach((key, value) -> {
                String[] parts = parts(key, 3, name);
                PrivacyNamespace namespace = PrivacyNamespace.valueOf(parts[1]);
                assertThat(value).isEqualTo(generator.syntheticValue(parts[2], namespace, scope(parts[0])));
            });
            case PrivacyCluster.REIDENTIFICATION_MAP -> map.forEach((key, value) -> {
                String[] parts = parts(key, 3, name);
                PrivacyNamespace namespace = PrivacyNamespace.valueOf(parts[1]);
                assertThat(parts[2]).isEqualTo(generator.syntheticValue((String) value, namespace, scope(parts[0])));
            });
            case PrivacyCluster.BUDGET_MAP -> map.forEach((key, value) -> {
                parts(key, 2, name);
                assertThat(value).isInstanceOf(Integer.class);
                Long.parseLong(value.toString());
            });
            default -> fail("unrecognised distributed map %s must be given a decomposition rule", name);
        }
    }

    private static String[] parts(Object key, int expectedParts, String mapName) {
        assertThat(key).as("%s key", mapName).isInstanceOf(String.class);
        String[] parts = ((String) key).split("\\u0000", -1);
        assertThat(parts).as("%s key components", mapName).hasSize(expectedParts);
        return parts;
    }

    private static void assertRawValueIsAbsent(Map<String, IMap<Object, Object>> maps) {
        maps.forEach((name, map) -> map.forEach((key, value) -> {
            assertThat(key.toString()).as("%s key", name).doesNotContainIgnoringCase(RAW_SENSITIVE_VALUE);
            assertThat(value.toString()).as("%s value", name).doesNotContainIgnoringCase(RAW_SENSITIVE_VALUE);
        }));
    }

    private static PrivacyContext scope(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    @LlmExposedModel
    private record SourceRecord(
            @InternalIdentifier String subjectId,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String fullName) {
    }

    private static final class DeterministicGenerator implements SyntheticValueSource {
        @Override
        public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
            return "generated:" + context.scopeId() + ":" + namespace.name() + ":" + subjectId;
        }
    }
}
