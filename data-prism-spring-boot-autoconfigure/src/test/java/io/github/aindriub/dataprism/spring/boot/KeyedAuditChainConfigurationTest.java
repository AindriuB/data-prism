package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditChainMode;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 211: the keyed audit chain and its retired keys, through {@code dataprism.audit.hmac-key}. */
class KeyedAuditChainConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final String PRIVACY_REFERENCE = "DATAPRISM_HMAC_KEY_REF";

    /** Reference to material. A reference not listed resolves as the other configuration tests' resolver does. */
    static final Map<String, byte[]> KEYS = new ConcurrentHashMap<>();

    private static byte[] key(char fill) {
        byte[] material = new byte[40];
        java.util.Arrays.fill(material, (byte) fill);
        return material;
    }

    private static byte[] privacyMaterial() {
        return (PRIVACY_REFERENCE + ":v1:resolved-key-material").getBytes(StandardCharsets.UTF_8);
    }

    private static WebApplicationContextRunner runner() {
        KEYS.clear();
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(Integrations.class)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained");
    }

    private static WebApplicationContextRunner file(Path dir) {
        return runner().withPropertyValues("dataprism.audit.file-path=" + dir.resolve("audit.log"));
    }

    private static WebApplicationContextRunner keyed(Path dir) {
        WebApplicationContextRunner r = file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.environment-variable=AUDIT_KEY_REF");
        KEYS.put("AUDIT_KEY_REF", key('a'));
        return r;
    }

    private static WebApplicationContextRunner segmented(Path dir) {
        return runner().withPropertyValues("dataprism.audit.directory=" + dir.resolve("segments"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl"));
    }

    private static void assertRefusedWith(WebApplicationContextRunner r, String code, String... forbidden) {
        r.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
            assertThat(((DataPrismConfigurationException) failure).code()).isEqualTo(code);
            for (String f : forbidden) {
                assertThat(failure.getMessage()).doesNotContain(f);
            }
        });
    }

    private static void record(AuditRecorder recorder) {
        for (int i = 0; i < 3; i++) {
            recorder.record("p", "c", "get_entity_context", "CUSTOMER", "ps", "fp", "DEFAULT", "scope",
                    "investigation", "case-1", "ALLOW", Set.of("customer"), Set.of(), "corr");
        }
    }

    @Test
    void the_chain_is_unkeyed_by_default(@TempDir Path dir) {
        AuditProperties audit = new AuditProperties();
        assertThat(audit.getHmacKey().getKeyId()).isNull();
        assertThat(audit.getHmacKey().getEnvironmentVariable()).isNull();
        assertThat(audit.getHmacKey().getProviderReference()).isNull();
        assertThat(audit.getRetiredHmacKeys()).isEmpty();
        file(dir).run(context -> {
            assertThat(context).hasNotFailed();
            record(context.getBean(AuditRecorder.class));
            Path written = dir.resolve("audit.log");
            assertThat(AuditChainVerifier.verify(written).hasBreak()).isFalse();
            assertThat(AuditChainVerifier.verify(written, null, Period.ofMonths(6),
                    List.of(new AuditChainKey("any", key('z'))), false).hasBreak()).isTrue();
        });
    }

    @Test
    void a_configured_key_signs_the_chain_and_the_file_verifies_only_with_it(@TempDir Path dir) {
        keyed(dir).run(context -> {
            assertThat(context).hasNotFailed();
            record(context.getBean(AuditRecorder.class));
            Path written = dir.resolve("audit.log");
            AuditChainKey key = new AuditChainKey("audit-1", key('a'));
            assertThat(AuditChainVerifier.verify(written, null, Period.ofMonths(6), List.of(key), false)
                    .hasBreak()).isFalse();
            assertThat(AuditChainVerifier.verify(written).hasBreak()).isTrue();
        });
    }

    @Test
    void the_group_is_valid_only_with_the_hash_chained_sink(@TempDir Path dir) {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.sink=slf4j",
                "dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.environment-variable=AUDIT_KEY_REF"),
                "AUDIT_HMAC_REQUIRES_HASH_CHAINED", "AUDIT_KEY_REF");
    }

    @Test
    void exactly_one_reference_is_required_and_there_is_no_inline_key(@TempDir Path dir) {
        assertRefusedWith(file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1"),
                "MISSING_AUDIT_HMAC_KEY_REFERENCE");
        assertRefusedWith(file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.environment-variable=A", "dataprism.audit.hmac-key.provider-reference=B"),
                "MISSING_AUDIT_HMAC_KEY_REFERENCE");
        assertRefusedWith(file(dir).withPropertyValues("dataprism.audit.hmac-key.environment-variable=A"),
                "MISSING_AUDIT_HMAC_KEY_REFERENCE");
        assertThat(java.util.Arrays.stream(AuditProperties.HmacKey.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("value", "key", "material", "secret");
    }

    @Test
    void a_reference_that_resolves_to_nothing_is_refused_without_the_reference(@TempDir Path dir) {
        assertRefusedWith(file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.environment-variable=NOWHERE_REF_NAME"),
                "AUDIT_HMAC_KEY_UNRESOLVED", "NOWHERE_REF_NAME", "audit-1");
    }

    @Test
    void a_provider_reference_is_resolved_as_one(@TempDir Path dir) {
        WebApplicationContextRunner r = file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.provider-reference=provider:audit");
        KEYS.put("provider:audit", key('p'));
        r.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void key_material_shorter_than_32_bytes_is_refused_without_the_bytes(@TempDir Path dir) {
        WebApplicationContextRunner r = file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.environment-variable=SHORT_REF");
        KEYS.put("SHORT_REF", "short-key-bytes".getBytes(StandardCharsets.UTF_8));
        assertRefusedWith(r,
                "AUDIT_HMAC_KEY_TOO_SHORT", "short-key-bytes", "SHORT_REF");
    }

    @Test
    void the_pseudonymisation_key_is_refused_by_reference_string_and_by_bytes(@TempDir Path dir) {
        assertRefusedWith(file(dir).withPropertyValues("dataprism.audit.hmac-key.key-id=audit-1",
                "dataprism.audit.hmac-key.environment-variable=" + PRIVACY_REFERENCE),
                "AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY", PRIVACY_REFERENCE);
        // a different reference to the same bytes
        WebApplicationContextRunner sameBytes = file(dir).withPropertyValues(
                "dataprism.audit.hmac-key.key-id=audit-1", "dataprism.audit.hmac-key.environment-variable=OTHER_REF");
        KEYS.put("OTHER_REF", privacyMaterial());
        assertRefusedWith(sameBytes, "AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY", "OTHER_REF");
    }

    @Test
    void a_retired_key_equal_to_the_pseudonymisation_key_is_refused(@TempDir Path dir) {
        WebApplicationContextRunner r = keyed(dir).withPropertyValues(
                "dataprism.audit.retired-hmac-keys.audit-0.environment-variable=RETIRED_REF");
        KEYS.put("RETIRED_REF", privacyMaterial());
        assertRefusedWith(r, "AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY", "RETIRED_REF");
    }

    @Test
    void a_retired_key_with_the_current_key_id_is_refused(@TempDir Path dir) {
        assertRefusedWith(keyed(dir).withPropertyValues(
                "dataprism.audit.retired-hmac-keys.audit-1.environment-variable=RETIRED_REF"),
                "DUPLICATE_AUDIT_KEY_ID");
    }

    @Test
    void an_application_recorder_with_the_key_set_is_refused(@TempDir Path dir) {
        assertRefusedWith(keyed(dir).withBean("appRecorder", AuditRecorder.class, () -> new AuditRecorder(
                new SegmentedFileAuditSink(dir.resolve("other")), Clock.systemUTC(), "app")),
                "AUDIT_RECORDER_REPLACED_WITH_HMAC_KEY", "AUDIT_KEY_REF");
    }

    @Test
    void a_parent_context_recorder_with_the_key_set_is_refused(@TempDir Path dir) {
        org.springframework.context.support.GenericApplicationContext parent =
                new org.springframework.context.support.GenericApplicationContext();
        parent.registerBean("appRecorder", AuditRecorder.class, () -> new AuditRecorder(
                new SegmentedFileAuditSink(dir.resolve("other")), Clock.systemUTC(), "app"));
        parent.refresh();
        try {
            assertRefusedWith(keyed(dir).withParent(parent), "AUDIT_RECORDER_REPLACED_WITH_HMAC_KEY", "AUDIT_KEY_REF");
        } finally {
            parent.close();
        }
    }

    @Test
    void a_manually_registered_singleton_named_like_the_built_in_recorder_is_refused(@TempDir Path dir) {
        keyed(dir).withInitializer(context -> context.getBeanFactory().registerSingleton("dataPrismAuditRecorder",
                new AuditRecorder(new SegmentedFileAuditSink(dir.resolve("other")), Clock.systemUTC(), "app")))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                        failure = failure.getCause();
                    }
                    assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
                });
    }

    @Test
    void an_application_recorder_without_the_key_starts(@TempDir Path dir) {
        file(dir).withBean("appRecorder", AuditRecorder.class, () -> new AuditRecorder(
                new SegmentedFileAuditSink(dir.resolve("other")), Clock.systemUTC(), "app"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void the_key_with_no_replacement_starts_keyed(@TempDir Path dir) {
        keyed(dir).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AuditRecorder.class)).containsOnlyKeys("dataPrismAuditRecorder");
        });
    }

    @Test
    void retired_keys_need_a_current_key(@TempDir Path dir) {
        assertRefusedWith(file(dir).withPropertyValues(
                "dataprism.audit.retired-hmac-keys.audit-0.environment-variable=RETIRED_REF"),
                "AUDIT_RETIRED_KEYS_REQUIRE_HMAC_KEY");
    }

    @Test
    void retention_with_the_retired_key_verifies_and_purges_an_expired_segment(@TempDir Path dir) throws Exception {
        Path expired = seedExpired(dir, new AuditChainKey("audit-0", key('r')));
        WebApplicationContextRunner r = segmented(dir).withPropertyValues(
                "dataprism.audit.hmac-key.key-id=audit-1", "dataprism.audit.hmac-key.environment-variable=AUDIT_KEY_REF",
                "dataprism.audit.retired-hmac-keys.audit-0.environment-variable=RETIRED_REF");
        KEYS.put("AUDIT_KEY_REF", key('a'));
        KEYS.put("RETIRED_REF", key('r'));
        r.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(expired).doesNotExist();
            assertThat(context.getBean("auditIntegrityHealthIndicator", HealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.UP);
        });
    }

    @Test
    void retention_does_not_delete_a_segment_whose_key_is_neither_current_nor_retired(@TempDir Path dir)
            throws Exception {
        Path expired = seedExpired(dir, new AuditChainKey("audit-0", key('r')));
        WebApplicationContextRunner r = segmented(dir).withPropertyValues(
                "dataprism.audit.hmac-key.key-id=audit-1", "dataprism.audit.hmac-key.environment-variable=AUDIT_KEY_REF");
        KEYS.put("AUDIT_KEY_REF", key('a'));
        r.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(expired).exists();
            HealthIndicator health = context.getBean("auditIntegrityHealthIndicator", HealthIndicator.class);
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.health().getDetails()).containsEntry("code", "AUDIT_RETENTION_CHAIN_UNVERIFIED");
        });
    }

    @Test
    void retention_does_not_delete_an_unkeyed_segment_once_the_chain_is_keyed(@TempDir Path dir) throws Exception {
        Path expired = seedExpired(dir, null);
        WebApplicationContextRunner r = segmented(dir).withPropertyValues(
                "dataprism.audit.hmac-key.key-id=audit-1", "dataprism.audit.hmac-key.environment-variable=AUDIT_KEY_REF");
        KEYS.put("AUDIT_KEY_REF", key('a'));
        r.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(expired).exists();
            assertThat(context.getBean("auditIntegrityHealthIndicator", HealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.DOWN);
        });
    }

    /** One old segment, written under {@code key} (unkeyed when null). */
    private static Path seedExpired(Path dir, AuditChainKey key) throws Exception {
        Path segments = dir.resolve("segments");
        try (SegmentedFileAuditSink seed = new SegmentedFileAuditSink(segments)) {
            AuditRecorder seeded = new AuditRecorder(seed,
                    Clock.fixed(Instant.parse("2025-01-10T10:00:00Z"), ZoneOffset.UTC), "seed", null, null,
                    key == null ? AuditChainMode.unkeyed() : AuditChainMode.keyed(key));
            record(seeded);
        }
        Path expired = segments.resolve("audit-2025-01-10.log");
        assertThat(expired).exists();
        return expired;
    }

    @Configuration(proxyBeanMethods = false)
    static class Integrations {
        @Bean DataSourceAdapter<TestPayload> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<TestPayload> responseType() { return TestPayload.class; }
                @Override public TestPayload fetch(io.github.aindriub.dataprism.core.spi.DataRequest request) { return null; }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() {
            return new HmacKeyReferenceResolver() {
                @Override public byte[] resolve(String keyId, String reference) {
                    if (reference.startsWith("NOWHERE")) {
                        return null;
                    }
                    byte[] material = KEYS.get(reference);
                    return material != null ? material
                            : (reference + ":" + keyId + ":resolved-key-material").getBytes(StandardCharsets.UTF_8);
                }
                @Override public byte[] resolveProviderReference(String keyId, String reference) {
                    return KEYS.get(reference);
                }
            };
        }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }
}
