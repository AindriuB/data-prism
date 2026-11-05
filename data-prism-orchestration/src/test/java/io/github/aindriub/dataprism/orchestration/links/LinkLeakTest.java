package io.github.aindriub.dataprism.orchestration.links;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.LinkOpenResult;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Parcel;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Plain;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Rig;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The chained value never leaves except as ciphertext, in any form, on any path. */
class LinkLeakTest {

    private static final String KEY = "P-SECRET/1+2=3 4";

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(appender);
    }

    private static List<String> forms(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return List.of(value,
                java.util.Base64.getEncoder().encodeToString(bytes),
                java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes),
                java.util.Base64.getUrlEncoder().encodeToString(bytes),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                HexFormat.of().formatHex(bytes), HexFormat.of().withUpperCase().formatHex(bytes),
                URLEncoder.encode(value, StandardCharsets.UTF_8), URLEncoder.encode(value, StandardCharsets.UTF_8)
                        .replace("+", "%20"));
    }

    private static Rig rig() {
        return new Rig().source("origin-api", new Parcel(KEY, List.of(), List.of(), "ok"))
                .source("parcel-api", new Plain("a"), "parcel-ref");
    }

    private void assertNoForm(String where, String text) {
        for (String form : forms(KEY)) {
            if (text.contains(form)) {
                preserveCapture(where, text);
            }
            assertThat(text).as(where + " must not hold the chained value as " + form).doesNotContain(form);
        }
    }

    /** Writes what was captured to {@code logs/}, so a failure can be read afterwards (docs/conventions.md). */
    static Path preserveCapture(String where, String text) {
        try {
            Path dir = repoRoot().resolve("logs");
            Files.createDirectories(dir);
            String name = "link-leak-" + where.replaceAll("[^A-Za-z0-9]+", "-") + "-"
                    + Instant.now().toEpochMilli() + ".log";
            Path file = dir.resolve(name);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException unwritable) {
            throw new UncheckedIOException("could not preserve the captured output", unwritable);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("could not find a repository root above " + Path.of("").toAbsolutePath());
        }
        return dir;
    }

    @Test
    void aLeakFoundByTheScanLeavesItsCaptureInLogs() throws Exception {
        org.slf4j.Logger log = LoggerFactory.getLogger("leaky");
        log.warn("carrying {}", KEY);
        long before = System.currentTimeMillis();

        assertThatThrownBy(() -> assertNoForm("the logs", logs())).isInstanceOf(AssertionError.class);

        Path dir = repoRoot().resolve("logs");
        try (var files = Files.list(dir)) {
            Path written = files.filter(f -> f.getFileName().toString().startsWith("link-leak-the-logs-"))
                    .filter(f -> f.toFile().lastModified() >= before - 1000).findFirst().orElseThrow();
            assertThat(Files.readString(written)).contains(KEY);
            Files.delete(written);
        }
    }

    private String logs() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            out.append(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                out.append(event.getThrowableProxy().getMessage());
            }
        }
        return out.toString();
    }

    @Test
    void theChainedValueIsInNoResponseAuditMetricOrLogOnTheSuccessPath() throws Exception {
        Rig rig = rig();

        ContextResponse response = rig.run();

        assertThat(response.sources().values().stream().mapToInt(s -> s.links().size()).sum()).isEqualTo(1);
        assertNoForm("the response", LinkBuilderTest.MAPPER.writeValueAsString(response));
        assertNoForm("the audit trail", rig.audit.toString());
        assertNoForm("the metrics", rig.counters.toString());
        assertNoForm("the logs", logs());
        assertNoForm("toString of a response", response.toString());
    }

    @Test
    void theChainedValueIsInNoExceptionAuditOrLogWhenTheCallIsRefusedAfterIssuing() {
        // Refuses only the assembled links, so the failure happens with links present.
        LlmResponseValidator refusesLinks = (tree, prohibited, emitted, ctx) ->
                tree.toString().contains("\"_links\"")
                        ? ValidationResult.failed(List.of(new Violation("$", "LINKS", "TEST")))
                        : ValidationResult.ok();
        Rig rig = rig().validator(refusesLinks);

        Throwable failure = null;
        try {
            rig.run();
        } catch (RuntimeException refused) {
            failure = refused;
        }

        assertThat(failure).isNotNull();
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t).append(t.getMessage());
            Arrays.stream(t.getSuppressed()).forEach(s -> chain.append(s.getMessage()));
        }
        assertNoForm("an exception", chain.toString());
        assertNoForm("the audit trail", rig.audit.toString());
        assertNoForm("the logs", logs());
        assertNoForm("the metrics", rig.counters.toString());
    }

    @Test
    void openingEachPayloadFindsOnlyThePaddedChainedKey() {
        Rig rig = new Rig().source("origin-api", new Parcel(KEY, List.of("D-1", "D-22"), List.of(), "ok"))
                .source("parcel-api", new Plain("a"), "parcel-ref").source("depot-api", new Plain("a"), "depot-ref");
        List<IssuedLink> links = new ArrayList<>();
        rig.run().sources().values().forEach(s -> links.addAll(s.links()));
        assertThat(links).hasSize(3);

        for (IssuedLink link : links) {
            LinkOpenResult opened = rig.codec().open(link.link(), Rig.binding(),
                    Set.of(LinkRelation.DIRECT, LinkRelation.TRANSLATED));
            assertThat(opened).isInstanceOf(LinkOpenResult.Opened.class);
            FollowPayload payload = (FollowPayload) ((LinkOpenResult.Opened) opened).payload();
            byte[] key = payload.chainedKey();
            byte[] expected = (link.namespace().equals("parcel-ref") ? KEY : link.field().endsWith("[0]") ? "D-1" : "D-22")
                    .getBytes(StandardCharsets.UTF_8);
            assertThat(Arrays.copyOf(key, expected.length)).isEqualTo(expected);
            for (int i = expected.length; i < key.length; i++) {
                assertThat(key[i]).as("padding byte").isZero();
            }
            assertThat(Pattern.matches("[A-Za-z0-9_-]+", link.link())).isTrue();
        }
    }
}
