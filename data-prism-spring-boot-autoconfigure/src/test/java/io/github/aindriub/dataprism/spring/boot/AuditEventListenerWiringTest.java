package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventListener;
import io.github.aindriub.dataprism.audit.AuditEventListeners;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 163: listener beans are collected in {@code @Order}, delivered after either sink, and the dispatcher is not replaceable. */
class AuditEventListenerWiringTest {

    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());
    private static final String SECRET_URL = "https://user:hunter2@sink.example/hook?token=abc";

    private Logger dispatcherLog;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void capture() {
        CALLS.clear();
        dispatcherLog = (Logger) LoggerFactory.getLogger(AuditEventListeners.class);
        appender = new ListAppender<>();
        appender.start();
        dispatcherLog.addAppender(appender);
        dispatcherLog.setLevel(Level.DEBUG);
    }

    @AfterEach
    void release() {
        dispatcherLog.detachAppender(appender);
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoOrderedListeners {
        // declared out of order on purpose: @Order, not declaration order, decides
        @Bean @Order(2) AuditEventListener second() { return e -> CALLS.add("second:" + e.sequence()); }
        @Bean @Order(1) AuditEventListener first() { return e -> CALLS.add("first:" + e.sequence()); }
    }

    @Configuration(proxyBeanMethods = false)
    static class ThrowingListener {
        @Bean AuditEventListener leaky() {
            return e -> {
                throw new IllegalStateException("could not post to " + SECRET_URL);
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameNameDispatcher {
        @Bean AuditEventListeners dataPrismAuditEventListeners() {
            return new AuditEventListeners(List.of(), 1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherNameDispatcher {
        @Bean AuditEventListeners myDispatcher() {
            return new AuditEventListeners(List.of(), 1);
        }
    }

    private static WebApplicationContextRunner runner(String... extra) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(AuditRetentionConfigurationTest.Integrations.class)
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues(extra);
    }

    private static WebApplicationContextRunner hashChained(Path dir, String... extra) {
        return runner("dataprism.audit.sink=hash-chained", "dataprism.audit.file-path=" + dir.resolve("audit.log"))
                .withPropertyValues(extra);
    }

    private static AuditEntry entry() {
        return new AuditEntry("p", "c", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp", "DEFAULT", "CASE-1",
                "investigation", "CASE-1", "ALLOW", Set.of("customer:ANSWERED"), Set.of(), "corr", Map.of(), "", "");
    }

    @Test
    void two_ordered_listeners_both_receive_events_in_order_after_the_hash_chained_write(@TempDir Path dir) {
        hashChained(dir).withUserConfiguration(TwoOrderedListeners.class).run(context -> {
            assertThat(context).hasNotFailed();
            AuditRecorder recorder = context.getBean(AuditRecorder.class);
            AuditEvent a = recorder.record(entry());
            AuditEvent b = recorder.record(entry());
            context.getBean(AuditEventListeners.class).close(); // drains
            assertThat(CALLS).containsExactly("first:1", "second:1", "first:2", "second:2");
            assertThat(Files.readAllLines(dir.resolve("audit.log"))).hasSize(2);
            assertThat(Files.readString(dir.resolve("audit.log"))).contains(a.eventHash()).contains(b.eventHash());
        });
    }

    @Test
    void listeners_are_also_called_after_the_slf4j_sink() {
        runner("dataprism.audit.sink=slf4j").withUserConfiguration(TwoOrderedListeners.class).run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(AuditRecorder.class).record(entry());
            context.getBean(AuditEventListeners.class).close();
            assertThat(CALLS).containsExactly("first:1", "second:1");
        });
    }

    @Test
    void with_no_listener_bean_the_context_starts_and_the_recorder_behaves_as_before(@TempDir Path dir) {
        hashChained(dir).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuditEventListeners.class).active()).isFalse();
            assertThat(context.getBean(AuditRecorder.class).record(entry()).sequence()).isEqualTo(1);
            assertThat(Files.readAllLines(dir.resolve("audit.log"))).hasSize(1);
        });
    }

    @Test
    void a_listener_exception_message_never_reaches_the_log_output(@TempDir Path dir) {
        hashChained(dir).withUserConfiguration(ThrowingListener.class).run(context -> {
            context.getBean(AuditRecorder.class).record(entry());
            context.getBean(AuditEventListeners.class).close();
        });
        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).startsWith("AUDIT_LISTENER_FAILED")
                .contains("exception=java.lang.IllegalStateException");
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("hunter2").doesNotContain("sink.example")
                    .doesNotContain("token=abc");
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
        }
    }

    @Test
    void a_competing_dispatcher_bean_refuses_startup() {
        runner("dataprism.audit.sink=slf4j").withUserConfiguration(SameNameDispatcher.class).run(context ->
                assertThat(context).hasFailed());
        runner("dataprism.audit.sink=slf4j").withUserConfiguration(OtherNameDispatcher.class).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
            assertThat(((DataPrismConfigurationException) failure).code())
                    .isEqualTo("AUDIT_EVENT_LISTENERS_NOT_REPLACEABLE");
        });
    }

    @Test
    void a_non_positive_queue_capacity_is_refused_and_a_positive_one_starts() {
        runner("dataprism.audit.sink=slf4j", "dataprism.audit.listeners.queue-capacity=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "INVALID_AUDIT_LISTENER_QUEUE_CAPACITY: dataprism.audit.listeners.queue-capacity must be positive");
        });
        runner("dataprism.audit.sink=slf4j", "dataprism.audit.listeners.queue-capacity=3")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void starting_and_stopping_the_context_repeatedly_leaves_no_dispatcher_thread() {
        long before = threads();
        for (int i = 0; i < 10; i++) {
            runner("dataprism.audit.sink=slf4j").withUserConfiguration(TwoOrderedListeners.class).run(context -> {
                assertThat(context).hasNotFailed();
                context.getBean(AuditRecorder.class).record(entry());
            });
        }
        assertThat(threads()).isEqualTo(before);
    }

    private static long threads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("data-prism-audit-listeners") && t.isAlive()).count();
    }
}
