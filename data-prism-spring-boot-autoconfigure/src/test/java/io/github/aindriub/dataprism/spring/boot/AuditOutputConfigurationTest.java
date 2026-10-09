package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.sink.TeeAuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 113: {@code dataprism.audit.output.*} bound, refused and wired. */
class AuditOutputConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(AuditRetentionConfigurationTest.Integrations.class)
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=slf4j");
    }

    private static WebApplicationContextRunner segmented(Path dir) {
        return runner().withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withPropertyValues("dataprism.audit.sink=hash-chained",
                        "dataprism.audit.directory=" + dir.resolve("native"),
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

    private static AuditEvent recordOne(AuditRecorder recorder) {
        return recorder.record("p", "c", "get_entity_context", "CUSTOMER", "ps", "fp", "DEFAULT", "scope",
                "investigation", "case-1", "ALLOW", Set.of("customer"), Set.of(), "corr");
    }

    @Test
    void the_defaults_are_bound() {
        runner().run(context -> {
            AuditProperties.Output output = context.getBean(DataPrismProperties.class).getAudit().getOutput();
            assertThat(output.getFieldPreset()).isEqualTo("canonical");
            assertThat(output.getFieldNames()).isEmpty();
            assertThat(output.getJsonDirectory()).isNull();
            assertThat(output.getRouting().getEventDataset()).isNull();
            assertThat(output.getRouting().getDataStreamType()).isNull();
        });
    }

    @Test
    void every_property_is_bound(@TempDir Path dir) {
        segmented(dir).withPropertyValues("dataprism.audit.output.field-preset=ecs",
                "dataprism.audit.output.field-names.tool=custom.action",
                "dataprism.audit.output.routing.event-dataset=dataprism.audit",
                "dataprism.audit.output.routing.data-stream-type=logs",
                "dataprism.audit.output.routing.data-stream-dataset=dataprism.audit",
                "dataprism.audit.output.routing.data-stream-namespace=prod",
                "dataprism.audit.output.json-directory=" + dir.resolve("json")).run(context -> {
            assertThat(context).hasNotFailed();
            AuditProperties.Output o = context.getBean(DataPrismProperties.class).getAudit().getOutput();
            assertThat(o.getFieldPreset()).isEqualTo("ecs");
            assertThat(o.getFieldNames()).containsEntry("tool", "custom.action");
            assertThat(o.getRouting().getEventDataset()).isEqualTo("dataprism.audit");
            assertThat(o.getRouting().getDataStreamType()).isEqualTo("logs");
            assertThat(o.getRouting().getDataStreamDataset()).isEqualTo("dataprism.audit");
            assertThat(o.getRouting().getDataStreamNamespace()).isEqualTo("prod");
            assertThat(o.getJsonDirectory()).isEqualTo(dir.resolve("json").toString());
        });
    }

    @Test
    void an_unknown_field_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.field-names.bogus=x"),
                "UNKNOWN_AUDIT_FIELD");
    }

    @Test
    void an_invalid_output_path_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.field-names.tool=bad path!"),
                "INVALID_AUDIT_FIELD_PATH");
    }

    @Test
    void colliding_paths_are_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.field-names.tool=same",
                "dataprism.audit.output.field-names.purpose=same"), "AUDIT_FIELD_MAPPING_CONFLICT");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.field-names.tool=a",
                "dataprism.audit.output.field-names.purpose=a.b"), "AUDIT_FIELD_MAPPING_CONFLICT");
    }

    @Test
    void a_routing_path_that_collides_with_a_mapped_path_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.field-names.tool=event.dataset",
                "dataprism.audit.output.routing.event-dataset=dataprism.audit"), "AUDIT_FIELD_MAPPING_CONFLICT");
    }

    @Test
    void an_invalid_routing_value_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.routing.data-stream-namespace=Prod!"),
                "INVALID_AUDIT_ROUTING_VALUE");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.routing.data-stream-type=metrics"),
                "INVALID_AUDIT_ROUTING_VALUE");
    }

    @Test
    void an_unknown_preset_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.field-preset=gelf"),
                "INVALID_AUDIT_FIELD_PRESET");
    }

    @Test
    void json_directory_needs_the_segmented_sink(@TempDir Path dir) {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.output.json-directory=" + dir.resolve("json")),
                "AUDIT_JSON_REQUIRES_SEGMENTED_SINK");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.sink=hash-chained",
                "dataprism.audit.file-path=" + dir.resolve("audit.log"),
                "dataprism.audit.output.json-directory=" + dir.resolve("json")),
                "AUDIT_JSON_REQUIRES_SEGMENTED_SINK");
    }

    @Test
    void json_directory_equal_to_the_audit_directory_is_refused(@TempDir Path dir) {
        assertRefusedWith(segmented(dir).withPropertyValues(
                "dataprism.audit.output.json-directory=" + dir.resolve("native")),
                "AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT");
    }

    @Test
    void an_unwritable_json_directory_is_refused_without_naming_its_path(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("a-regular-file"));
        Path unusable = file.resolve("json");
        assertRefusedWith(segmented(dir).withPropertyValues(
                "dataprism.audit.output.json-directory=" + unusable),
                "AUDIT_JSON_DIRECTORY_UNUSABLE", unusable.toString(), "a-regular-file");
    }

    @Test
    void one_call_writes_one_native_line_and_one_ndjson_line_with_the_same_hash(@TempDir Path dir) {
        Path json = dir.resolve("json");
        segmented(dir).withPropertyValues("dataprism.audit.output.json-directory=" + json).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuditSink.class)).isInstanceOf(TeeAuditSink.class);

            AuditEvent event = recordOne(context.getBean(AuditRecorder.class));

            Path nativeSegment = dir.resolve("native").resolve("audit-2026-10-06.log");
            Path jsonSegment = json.resolve("audit-2026-10-06.ndjson");
            assertThat(Files.readAllLines(nativeSegment)).hasSize(1);
            List<String> projected = Files.readAllLines(jsonSegment);
            assertThat(projected).hasSize(1);
            assertThat(projected.get(0)).contains(event.eventHash());
            assertThat(Files.readString(nativeSegment)).contains(event.eventHash());

            AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(dir.resolve("native"));
            assertThat(report.anomalies()).isEmpty();
            assertThat(report.tail()).isEmpty();
        });
    }

    @Test
    void the_json_projection_is_its_own_bean_and_closing_the_context_closes_both_sinks(@TempDir Path dir) {
        Path json = dir.resolve("json");
        List<String> destroyed = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor recorder =
                (bean, name) -> {
                    if (name.equals("dataPrismHashChainedAuditSink") || name.equals("dataPrismJsonAuditProjection")) {
                        destroyed.add(name);
                    }
                };
        AuditSink[] sinks = new AuditSink[2];
        segmented(dir).withPropertyValues("dataprism.audit.output.json-directory=" + json)
                .withBean("destructionRecorder",
                        org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor.class, () -> recorder)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("dataPrismJsonAuditProjection");
                    sinks[0] = context.getBean(AuditSink.class);
                    sinks[1] = context.getBean(JsonProjection.class).asSink();
                });
        // the tee goes first and closes the projection and the native sink; the context's own close of the
        // projection afterwards is a no-op
        assertThat(destroyed).containsExactly("dataPrismHashChainedAuditSink", "dataPrismJsonAuditProjection");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sinks[1].record(null)).isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sinks[0].record(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void no_projection_bean_without_a_json_directory(@TempDir Path dir) {
        segmented(dir).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("dataPrismJsonAuditProjection");
        });
    }

    static final class SinkFactory implements org.springframework.beans.factory.FactoryBean<AuditSink> {
        @Override public AuditSink getObject() { return event -> { }; }
        @Override public Class<?> getObjectType() { return AuditSink.class; }
    }

    private void assertSinkRefusedAndDirectoryUntouched(Path dir,
            java.util.function.Function<WebApplicationContextRunner, WebApplicationContextRunner> withSink)
            throws Exception {
        Path json = Files.createDirectories(dir.resolve("json"));
        Path seeded = Files.writeString(json.resolve("audit-2020-01-01.ndjson"), "{}\n");
        assertRefusedWith(withSink.apply(segmented(dir)).withPropertyValues(
                "dataprism.audit.output.json-directory=" + json),
                "AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK", json.toString());
        assertThat(seeded).exists();
        try (var files = Files.list(json)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void an_application_bean_audit_sink_with_a_json_directory_is_refused(@TempDir Path dir) throws Exception {
        assertSinkRefusedAndDirectoryUntouched(dir,
                r -> r.withBean("applicationSink", AuditSink.class, () -> event -> { }));
    }

    @Test
    void an_application_factory_bean_audit_sink_with_a_json_directory_is_refused(@TempDir Path dir) throws Exception {
        assertSinkRefusedAndDirectoryUntouched(dir,
                r -> r.withBean("applicationSinkFactory", SinkFactory.class));
    }

    @Test
    void a_lazy_application_audit_sink_with_a_json_directory_is_refused(@TempDir Path dir) throws Exception {
        assertSinkRefusedAndDirectoryUntouched(dir,
                r -> r.withBean("lazySink", AuditSink.class, () -> event -> { },
                        bd -> bd.setLazyInit(true)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class UntypedSinkFactory implements org.springframework.beans.factory.FactoryBean {
        @Override public Object getObject() { return (AuditSink) event -> { }; }
        @Override public Class<?> getObjectType() { return null; }
    }

    @Test
    void an_untyped_factory_bean_does_not_replace_the_built_in_sink_so_the_projection_stays_wired(@TempDir Path dir) {
        // The framework cannot see this factory as an AuditSink, so the built-in sink (and tee) stays in
        // force and writes the directory; the guard in the projection bean fires only on a real replacement.
        segmented(dir).withBean("untypedSinkFactory", UntypedSinkFactory.class)
                .withPropertyValues("dataprism.audit.output.json-directory=" + dir.resolve("json"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("dataPrismHashChainedAuditSink")).isInstanceOf(TeeAuditSink.class);
                });
    }

    @Test
    void an_application_sink_overriding_the_built_in_sinks_name_is_refused(@TempDir Path dir) throws Exception {
        assertSinkRefusedAndDirectoryUntouched(dir,
                r -> r.withAllowBeanDefinitionOverriding(true)
                        .withBean("dataPrismHashChainedAuditSink", AuditSink.class, () -> event -> { }));
    }

    @Test
    void an_unusable_json_directory_leaves_the_native_file_unopened_and_unterminated(@TempDir Path dir)
            throws Exception {
        Path nativeDir = Files.createDirectories(dir.resolve("native"));
        Path segment = nativeDir.resolve("audit-2026-10-06.log");
        Files.writeString(segment, "torn-tail-without-newline");
        Path file = Files.createFile(dir.resolve("a-regular-file"));
        assertRefusedWith(segmented(dir).withPropertyValues(
                "dataprism.audit.output.json-directory=" + file.resolve("json")), "AUDIT_JSON_DIRECTORY_UNUSABLE");
        assertThat(Files.readString(segment)).isEqualTo("torn-tail-without-newline");
    }

    @Test
    void an_overridden_projection_definition_refuses_startup_rather_than_dropping_json_output(@TempDir Path dir) {
        assertRefusedWith(segmented(dir).withAllowBeanDefinitionOverriding(true).withPropertyValues(
                        "dataprism.audit.output.json-directory=" + dir.resolve("json"))
                        .withBean("dataPrismJsonAuditProjection", Object.class, Object::new),
                "AUDIT_JSON_PROJECTION_MISSING");
    }

    @Test
    void an_application_bean_cannot_replace_the_projection(@TempDir Path dir) {
        segmented(dir).withPropertyValues("dataprism.audit.output.json-directory=" + dir.resolve("json"))
                .withBean("dataPrismJsonAuditProjection", Object.class, Object::new).run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void startup_purges_an_expired_ndjson_segment_and_keeps_a_current_one(@TempDir Path dir) throws Exception {
        Path json = Files.createDirectories(dir.resolve("json"));
        Path expired = Files.writeString(json.resolve("audit-2025-01-10.ndjson"), "{}\n");
        Path current = Files.writeString(json.resolve("audit-2026-10-05.ndjson"), "{}\n");
        Path foreign = Files.writeString(json.resolve("notes.txt"), "keep\n");

        segmented(dir).withPropertyValues("dataprism.audit.output.json-directory=" + json).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(expired).doesNotExist();
            assertThat(current).exists();
            assertThat(foreign).exists();
        });
    }

    @Test
    void the_json_retention_honours_the_retention_floor_and_override(@TempDir Path dir) throws Exception {
        Path json = Files.createDirectories(dir.resolve("json"));
        Path aged = Files.writeString(json.resolve("audit-2026-07-01.ndjson"), "{}\n");
        segmented(dir).withPropertyValues("dataprism.audit.output.json-directory=" + json,
                "dataprism.audit.retention=P2M", "dataprism.audit.retention-override=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(aged).doesNotExist();
        });
    }

    @Test
    void the_slf4j_sink_with_an_ecs_preset_emits_event_id_as_a_key_value_pair() {
        Logger logger = (Logger) LoggerFactory.getLogger("dataprism.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner().withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                    .withPropertyValues("dataprism.audit.output.field-preset=ecs",
                            "dataprism.audit.output.routing.data-stream-type=logs").run(context -> {
                assertThat(context).hasNotFailed();
                AuditEvent event = recordOne(context.getBean(AuditRecorder.class));
                assertThat(appender.list).anySatisfy(e -> {
                    assertThat(e.getKeyValuePairs()).anySatisfy(kv -> {
                        assertThat(kv.key).isEqualTo("event.id");
                        assertThat(kv.value).isEqualTo(event.eventId());
                    });
                    assertThat(e.getKeyValuePairs()).anySatisfy(kv -> assertThat(kv.key).isEqualTo("data_stream.type"));
                });
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void the_slf4j_sink_with_nothing_configured_adds_no_pairs() {
        Logger logger = (Logger) LoggerFactory.getLogger("dataprism.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner().run(context -> {
                recordOne(context.getBean(AuditRecorder.class));
                assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getKeyValuePairs() == null || e.getKeyValuePairs().isEmpty()).isTrue());
            });
        } finally {
            logger.detachAppender(appender);
        }
    }
}
