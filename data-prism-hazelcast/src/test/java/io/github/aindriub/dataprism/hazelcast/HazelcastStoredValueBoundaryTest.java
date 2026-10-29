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
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.hazelcast.boundary.ApprovalMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.boundary.AreaBoundary;
import io.github.aindriub.dataprism.hazelcast.boundary.CounterMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.boundary.IdentityMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.boundary.Inputs;
import io.github.aindriub.dataprism.hazelcast.boundary.MemberCardMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.boundary.OversightMapsBoundary;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies the durable-cluster boundary by recomputing every stored key from the canonical inputs the test wrote.
 * Every key is the keyed name of those inputs (docs/plan/design/cluster-security.md 2.3.2) and contains none of them;
 * identity entries equal the generator output for their inputs; re-identification entries name the subject the
 * pseudonym stands for; and budget values are counts. This is the right property because the generator never
 * receives a source value, so a raw value cannot satisfy it, unlike a brittle scan for a fixed pseudonym. The raw
 * fixture enters the real tree-scrubbing path before it invokes the cache-backed generator; the separate scan
 * catches a writer that stores that source value alongside otherwise valid state.
 *
 * <p>This class enumerates the maps and fails on one it does not know. The map list comes from the constants of
 * {@link PrivacyCluster}, so a task that adds a map adds a constant and a checker in
 * {@code io.github.aindriub.dataprism.hazelcast.boundary}, and does not edit this file. Each area's checks live in
 * its own checker.
 */
class HazelcastStoredValueBoundaryTest {

    private static final String RAW_SENSITIVE_VALUE = "TEST-PPSN-0000000ZZ";
    private static final String SUBJECT_ID = "subject-42";
    private static final String SCOPE_ID = "CASE-42";
    private static final String APPROVAL_ID = "approval-7f3a";
    private static final String REQUESTER = "alice";
    private static final String TOOL = "search";

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
                .get("fullName").asString();
        assertThat(new ScopeIdentityIndex(cluster)
                .subjectFor(SCOPE_ID, PrivacyNamespace.PERSON_NAME, synthetic))
                .contains(SUBJECT_ID);
        assertThat(new HazelcastScopeBudget(cluster).tryRead(SCOPE_ID, SUBJECT_ID, 3)).isTrue();

        Map<String, IMap<Object, Object>> maps = allPrivacyMaps();
        assertThat(maps.keySet()).containsExactlyInAnyOrder(
                PrivacyCluster.IDENTITY_MAP, PrivacyCluster.REIDENTIFICATION_MAP, PrivacyCluster.BUDGET_MAP);
        assertThat(List.copyOf(maps.values())).allSatisfy(map -> assertThat(map.size()).isPositive());
        assertRawValueIsAbsent(maps);
        checkEveryMap(maps, inputs(generator, synthetic, "ap-unused", "alice"));
    }

    @Test
    void oversightMapsContainOnlyPrincipalsFingerprintsAndCounts() {
        start();
        var generator = new DeterministicGenerator();
        String synthetic = "generated:" + SCOPE_ID + ":PERSON_NAME:pseudonym-7";
        Instant now = Instant.now();
        var approvals = new HazelcastApprovalStore(cluster);
        approvals.create(new ApprovalRequest(APPROVAL_ID, ApprovalRequest.Kind.REIDENTIFICATION, REQUESTER, "client",
                SCOPE_ID, "reidentify", "fp-1", "PERSON_NAME", synthetic, "audit", SCOPE_ID, now,
                now.plus(1, ChronoUnit.HOURS), ApprovalRequest.Status.PENDING, null, null));
        var state = new HazelcastOversightState(cluster);
        state.pauseAll();
        state.pauseTool(TOOL);
        state.pauseScope(SCOPE_ID);
        assertThat(new HazelcastCallerRateLimiter(cluster)
                .tryAcquire(REQUESTER, 5, java.time.Duration.ofMinutes(1), now)).isTrue();

        Map<String, IMap<Object, Object>> maps = allPrivacyMaps();
        assertThat(maps.keySet()).containsExactlyInAnyOrder(PrivacyCluster.OVERSIGHT_MAP,
                PrivacyCluster.APPROVAL_MAP, PrivacyCluster.APPROVAL_ID_MAP, PrivacyCluster.APPROVAL_PENDING_MAP,
                PrivacyCluster.CALLER_RATE_MAP);
        assertThat(List.copyOf(maps.values())).allSatisfy(map -> assertThat(map.size()).isPositive());
        assertRawValueIsAbsent(maps);
        maps.forEach((name, map) -> {
            assertThat(map.keySet().toString()).doesNotContain(SUBJECT_ID);
            assertThat(map.values().toString()).doesNotContain(SUBJECT_ID);
        });
        checkEveryMap(maps, inputs(generator, synthetic, APPROVAL_ID, REQUESTER));

        // The guard bites: a raw value smuggled into any of these maps is caught.
        cluster.instance().<String, String>getMap(PrivacyCluster.APPROVAL_MAP)
                .put(SCOPE_ID + "\0ap-2", RAW_SENSITIVE_VALUE);
        assertThatThrownBy(() -> assertRawValueIsAbsent(allPrivacyMaps()))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void anUnknownMapFailsTheEnumeration() {
        start();
        cluster.instance().getMap("dataprism.not-a-known-map").put("k", "v");
        assertThatThrownBy(() -> checkEveryMap(allPrivacyMaps(), inputs(new DeterministicGenerator(), "unused-pseudonym", "unused-approval", "unused-requester")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("dataprism.not-a-known-map");
    }

    @Test
    void aKeyThatIsNotTheKeyedNameOfItsInputsFails() {
        start();
        var generator = new DeterministicGenerator();
        new HazelcastScopeBudget(cluster).tryRead(SCOPE_ID, SUBJECT_ID, 3);
        // The pre-0.8 shape of the key: the scope, a NUL and the subject, in the clear.
        cluster.instance().<String, Integer>getMap(PrivacyCluster.BUDGET_MAP).put(SCOPE_ID + "\0" + SUBJECT_ID, 1);
        assertThatThrownBy(() -> checkEveryMap(allPrivacyMaps(), inputs(generator, "unused-pseudonym", "unused-approval", "unused-requester")))
                .isInstanceOf(AssertionError.class);
    }

    private Inputs inputs(SyntheticValueSource generator, String synthetic, String approvalId, String requester) {
        return new Inputs(cluster.keyedNames(), generator, SCOPE_ID, SUBJECT_ID, PrivacyNamespace.PERSON_NAME,
                synthetic, approvalId, requester, ApprovalRequest.Kind.REIDENTIFICATION, TOOL);
    }

    /** Every checker, one per area. A later task changes only the checker of its own area. */
    private static List<AreaBoundary> areas() {
        return List.of(new IdentityMapsBoundary(), new OversightMapsBoundary(), new CounterMapsBoundary(),
                new MemberCardMapsBoundary(), new ApprovalMapsBoundary(new ApprovalMapsBoundary.Decoders() {
                    @Override
                    public String[] approval(String value) {
                        var request = HazelcastApprovalStore.Codec.decode(value);
                        return new String[]{request.approvalId(), request.scopeId()};
                    }

                    @Override
                    public String[] claim(String value) {
                        return ClaimEntries.read(value).filter(named -> !named.marked())
                                .map(named -> new String[]{named.entryKey(), named.token()}).orElse(null);
                    }

                    @Override
                    public Set<String> pendingIds(String value) {
                        return PendingSet.decode(value).keySet();
                    }
                }));
    }

    /** The full name of every sealed map, taken from the constants of {@link PrivacyCluster}. */
    private static Set<String> knownMaps() {
        Set<String> names = new java.util.TreeSet<>();
        for (var field : PrivacyCluster.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && field.getName().endsWith("_MAP")) {
                try {
                    field.setAccessible(true);
                    String name = (String) field.get(null);
                    if (name.endsWith(".s1")) {
                        names.add(name);
                    }
                } catch (ReflectiveOperationException unreadable) {
                    throw new AssertionError(unreadable);
                }
            }
        }
        return names;
    }

    /**
     * Fails on a map that no area owns, runs the owning area's checker over each existing map, and runs the checkers
     * of the maps that must be empty for now (the consumed-approval and member-card maps).
     */
    private void checkEveryMap(Map<String, IMap<Object, Object>> existing, Inputs inputs) {
        Set<String> known = knownMaps();
        List<AreaBoundary> areas = areas();
        for (String name : existing.keySet()) {
            if (!known.contains(name)) {
                fail("unrecognised distributed map %s must be given a decomposition rule", name);
            }
        }
        for (String name : known) {
            assertThat(areas.stream().filter(area -> area.maps().contains(name)).count())
                    .as("map %s is owned by exactly one area", name).isEqualTo(1);
        }
        existing.forEach((name, map) -> owner(areas, name).check(name, map, inputs));
        for (String name : List.of(PrivacyCluster.APPROVAL_CONSUMED_MAP, PrivacyCluster.MEMBER_CARD_MAP)) {
            if (!existing.containsKey(name)) {
                owner(areas, name).check(name, cluster.instance().getMap(name), inputs);
            }
        }
    }

    private static AreaBoundary owner(List<AreaBoundary> areas, String map) {
        return areas.stream().filter(area -> area.maps().contains(map)).findFirst()
                .orElseThrow(() -> new AssertionError("unrecognised distributed map " + map));
    }

    @SuppressWarnings("unchecked")
    private Map<String, IMap<Object, Object>> allPrivacyMaps() {
        Map<String, IMap<Object, Object>> maps = new java.util.LinkedHashMap<>();
        for (DistributedObject object : cluster.instance().getDistributedObjects()) {
            if (object instanceof IMap<?, ?> map) {
                maps.put(object.getName(), (IMap<Object, Object>) map);
            }
        }
        return maps;
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
