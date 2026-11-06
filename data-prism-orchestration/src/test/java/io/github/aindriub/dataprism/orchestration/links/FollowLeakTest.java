package io.github.aindriub.dataprism.orchestration.links;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.KeyResolutionException;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The token, the chained key and every resolver input and output are in nothing that leaves (T6, key-resolvers 9). */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowLeakTest {

    private static final String CHAINED = "P-SECRET/1+2=3 4";
    private static final String OUT_1 = "D-SECRET/9+8=7 6";
    private static final String OUT_2 = "D-SECRET/5+4=3 2";

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
                URLEncoder.encode(value, StandardCharsets.UTF_8),
                URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
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

    private void assertClean(FollowRig rig, String token, String response, Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t).append(t.getMessage());
            Arrays.stream(t.getSuppressed()).forEach(s -> chain.append(s.getMessage()));
        }
        List<String> secrets = new ArrayList<>();
        for (String value : List.of(CHAINED, OUT_1, OUT_2)) {
            secrets.addAll(forms(value));
        }
        secrets.add(token);
        Object[][] places = {{"response", response}, {"audit", rig.audit.toString()},
                {"metrics", rig.counters.toString()}, {"logs", logs()}, {"exception", chain.toString()}};
        for (Object[] place : places) {
            for (String secret : secrets) {
                assertThat((String) place[1]).as((String) place[0]).doesNotContain(secret);
            }
        }
    }

    private static FollowRig translated() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                        key -> new KeyResolver.Resolution(List.of(OUT_1, OUT_2), false));
        rig.records.put(OUT_1, new LinkBuilderTest.Plain("one"));
        rig.records.put(OUT_2, new LinkBuilderTest.Plain("two"));
        return rig;
    }

    @Test
    void nothingLeaksOnASuccessfulDirectAndTranslatedFollow() throws Exception {
        FollowRig direct = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        String directToken = direct.direct("parcel-api", "parcel-ref", CHAINED);
        ContextResponse one = direct.follow(directToken);
        assertClean(direct, directToken, LinkBuilderTest.MAPPER.writeValueAsString(one) + one, null);

        FollowRig rig = translated();
        String token = rig.translated("depot-api", "parcel-ref", "parcel-to-depot", CHAINED);
        ContextResponse two = rig.follow(token);
        assertClean(rig, token, LinkBuilderTest.MAPPER.writeValueAsString(two) + two, null);
    }

    @Test
    void nothingLeaksWhenTheResolverThrowsAMessageHoldingTheKey() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref", key -> {
                    throw new IllegalStateException("lookup failed for " + key + " and " + OUT_1);
                });
        String token = rig.translated("depot-api", "parcel-ref", "parcel-to-depot", CHAINED);

        ContextResponse response = rig.follow(token);

        assertThat(response.sources().get(rig.label("depot-api")).status()).isEqualTo("RESOLVER_FAILED");
        assertClean(rig, token, response.toString(), null);
    }

    @Test
    void nothingLeaksWhenAKeyedResolutionFails() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref", key -> {
                    throw new KeyResolutionException(KeyResolutionException.Reason.REJECTED);
                });
        String token = rig.translated("depot-api", "parcel-ref", "parcel-to-depot", CHAINED);

        assertClean(rig, token, rig.follow(token).toString(), null);
    }

    @Test
    void nothingLeaksWhenTheAdapterFailsWithTheKeyInItsMessage() {
        FollowRig rig = translated();
        rig.fetchFailure = key -> new IllegalStateException("could not read " + key);
        String token = rig.translated("depot-api", "parcel-ref", "parcel-to-depot", CHAINED);

        ContextResponse response = rig.follow(token);

        assertThat(response.sources().get(rig.label("depot-api")).status()).isEqualTo("FAILED");
        assertClean(rig, token, response.toString(), null);
    }

    @Test
    void nothingLeaksWhenTheCallIsRefusedByScrubbingOrValidation() {
        FollowRig scrub = translated();
        scrub.scrubFailure = record -> new PrivacyRefusedException("UNCLASSIFIED_FIELD", "$.x", "no");
        String scrubToken = scrub.translated("depot-api", "parcel-ref", "parcel-to-depot", CHAINED);
        Throwable scrubFailure = catchThrowable(() -> scrub.follow(scrubToken));
        assertThat(scrubFailure).isNotNull();
        assertClean(scrub, scrubToken, "", scrubFailure);

        FollowRig invalid = translated();
        String forged = invalid.forged();
        Throwable invalidFailure = catchThrowable(() -> invalid.follow(forged));
        assertClean(invalid, forged, "", invalidFailure);

        FollowRig denied = translated();
        String deniedToken = denied.translated("no-such-api", "parcel-ref", "parcel-to-depot", CHAINED);
        Throwable deniedFailure = catchThrowable(() -> denied.follow(deniedToken));
        assertClean(denied, deniedToken, "", deniedFailure);
    }

    @Test
    void nothingLeaksWhenTheBudgetRefuses() {
        FollowRig rig = translated();
        rig.budget = new io.github.aindriub.dataprism.core.limits.ScopeBudget() {
            @Override
            public boolean tryRead(String scopeId, String subjectId, int budget) {
                return false;
            }

            @Override
            public int reads(String scopeId, String subjectId) {
                return 0;
            }

            @Override
            public void forget(String scopeId) {
            }
        };
        String token = rig.translated("depot-api", "parcel-ref", "parcel-to-depot", CHAINED);

        Throwable failure = catchThrowable(() -> rig.follow(token));

        assertThat(failure).isNotNull();
        assertClean(rig, token, "", failure);
    }
}
