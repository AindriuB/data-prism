package io.github.aindriub.dataprism.orchestration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.SourceCallContext;
import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFanOutMdcTest {

    private static final String KEY = "transaction_id";
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("source-under-test");

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    private static final class Logging implements DataSourceAdapter<String> {
        @Override public String sourceName() { return "src"; }
        @Override public Class<String> responseType() { return String.class; }
        @Override public String fetch(DataRequest request) {
            LOG.info("inside fetch");
            return "x";
        }
    }

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger("source-under-test");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static Optional<ExternalCorrelationId> id(String value) {
        return CorrelationIdPolicy.opaque("[A-Za-z0-9-]{1,64}").validate(value);
    }

    private List<ILoggingEvent> run(CorrelationMdc mdc, Optional<ExternalCorrelationId> id) {
        DataRequest request = DataRequest.of("THING", "s-1").withContext(new SourceCallContext(id));
        new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none(), mdc)
                .fetchAll(List.of(new Logging()), Map.of("src", request),
                        new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(5), 4, 100));
        return appender.list;
    }

    @Test
    @DisplayName("the fetch's own log line carries the call's id under the key")
    void lineCarriesKey() {
        List<ILoggingEvent> events = run(CorrelationMdc.of(KEY), id("synthetic-clid-0001"));
        assertThat(events).singleElement().satisfies(e ->
                assertThat(e.getMDCPropertyMap()).containsEntry(KEY, "synthetic-clid-0001"));
    }

    @Test
    @DisplayName("a request with no id gives a line with no key")
    void noIdNoKey() {
        List<ILoggingEvent> events = run(CorrelationMdc.of(KEY), Optional.empty());
        assertThat(events).singleElement().satisfies(e -> assertThat(e.getMDCPropertyMap()).isEmpty());
    }

    @Test
    @DisplayName("with MDC off nothing is set even when an id is present")
    void offSetsNothing() {
        List<ILoggingEvent> events = run(CorrelationMdc.off(), id("synthetic-clid-0001"));
        assertThat(events).singleElement().satisfies(e -> assertThat(e.getMDCPropertyMap()).isEmpty());
    }

    @Test
    @DisplayName("nothing is inherited or copied between threads")
    void noInheritance() throws IOException {
        for (String file : List.of(
                "src/main/java/io/github/aindriub/dataprism/orchestration/SourceFanOut.java",
                "../data-prism-core/src/main/java/io/github/aindriub/dataprism/core/correlation/CorrelationMdc.java")) {
            String source = Files.readString(Path.of(file));
            assertThat(source).doesNotContain("InheritableThreadLocal").doesNotContain("MDC.getCopyOfContextMap");
        }
    }
}
