package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.link.SealResult;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.FollowRequest;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Reserved argument names reach the audit through the REAL orchestrator, on a refusal and on a success. */
class FollowToolAuditTest {

    record Thing(String note) {
    }

    /** Delegates to the real orchestrator and remembers the session the tool handed it. */
    static final class Capturing implements ContextOrchestrator {
        final ContextOrchestrator real;
        final AtomicReference<PrivacyContext> privacy = new AtomicReference<>();
        final AtomicReference<InvestigationContext> caller = new AtomicReference<>();

        Capturing(ContextOrchestrator real) {
            this.real = real;
        }

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext context,
                                            InvestigationContext investigation) {
            return real.buildContext(request, context, investigation);
        }

        @Override
        public ContextResponse follow(FollowRequest request, PrivacyContext context,
                                      InvestigationContext investigation) {
            privacy.set(context);
            caller.set(investigation);
            return real.follow(request, context, investigation);
        }
    }

    private static DataSourceAdapter<Thing> parcelSource() {
        SourceDescription description = new SourceDescription(Set.of(), null, null,
                List.of(new KeyNamespace("parcel-ref")), null, null, Map.of());
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "parcel-api";
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                return new Thing("alpha");
            }

            @Override
            public SourceDescription description() {
                return description;
            }
        };
    }

    private static Map<String, Object> withReserved(String link) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("link", link);
        arguments.put("principalId", "someone-else");
        return arguments;
    }

    private static ScrubbingEngine scrubber() {
        return (record, ctx) -> new ScrubResult(DataPrismObjectMapper.create().createObjectNode()
                .put("note", ((Thing) record).note()), Set.of(), Map.of("/note", PrivacyAction.PASS_THROUGH));
    }

    private static String label(PrivacyContext privacy) {
        return new SourceAliasing(new HmacValueTokenSource(ListSourcesToolTest.KEYS)).aliasFor("parcel-api", privacy);
    }

    private static String genuineLink(PrivacyContext privacy, InvestigationContext caller) {
        LinkKeys keys = new LinkKeys(new ListSourcesToolTest.MapKeys(), "link-key", "pseudo-key");
        LinkCodec codec = new LinkCodec(
                new LinkSettings("link-key", Optional.empty(), 1024, true, Duration.ofSeconds(30)), keys,
                ListSourcesToolTest.FIXED);
        LinkBinding binding = new LinkBinding(privacy.scopeId(), caller.principalId(), privacy.expiresAt(),
                privacy.expiresAt());
        SealResult sealed = codec.sealDetailed(new FollowPayload(new byte[8], ListSourcesToolTest.FIXED.instant(),
                "parcel-ref", Optional.empty(), "P-1".getBytes(StandardCharsets.UTF_8), label(privacy),
                LinkRelation.DIRECT), binding);
        return ((SealResult.Issued) sealed).link().token();
    }

    @Test
    @DisplayName("a forged link sent beside principalId is refused by the orchestrator and audited with the name")
    void aForgedLinkAuditsTheReservedArgumentName() {
        FollowToolTest.Rig rig = FollowToolTest.rig(FollowToolTest.WITH_FOLLOW,
                ToolOptions.defaults().noAdmission().build(), true, FollowToolTest::realOrchestrator,
                FollowToolTest.limiter(5), ListSourcesToolTest.CALLER);

        try (FollowToolTest.Peer peer = new FollowToolTest.Peer(rig.wiring)) {
            FollowToolTest.Answer answer = peer.call("follow", withReserved(FollowToolTest.VALID_SHAPE));
            assertThat(answer.isError()).isTrue();
            assertThat(answer.text()).isEqualTo("The link is not valid.");
        }
        assertThat(rig.audited).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("follow");
            assertThat(event.policyDecision()).startsWith("DENY");
            assertThat(event.rejectedArguments()).containsExactly("principalId");
        });
    }

    @Test
    @DisplayName("a genuine link followed with principalId beside it succeeds, and its audit event names the argument")
    void aGenuineLinkAuditsTheReservedArgumentName() {
        List<DataSourceAdapter<?>> adapters = List.of(parcelSource());
        AtomicReference<Capturing> orchestrator = new AtomicReference<>();
        FollowToolTest.Rig rig = FollowToolTest.rig(FollowToolTest.WITH_FOLLOW,
                ToolOptions.defaults().noAdmission().build(), true, (audit, limiter) -> {
                    orchestrator.set(new Capturing(
                            FollowToolTest.realOrchestrator(adapters, scrubber(), audit, limiter)));
                    return orchestrator.get();
                }, FollowToolTest.limiter(5), ListSourcesToolTest.CALLER);

        try (FollowToolTest.Peer peer = new FollowToolTest.Peer(rig.wiring)) {
            // A throwaway call, so the session the tool builds is known and the link can be bound to it.
            peer.follow(FollowToolTest.VALID_SHAPE);
            PrivacyContext privacy = orchestrator.get().privacy.get();
            String link = genuineLink(privacy, orchestrator.get().caller.get());

            FollowToolTest.Answer answer = peer.call("follow", withReserved(link));

            assertThat(answer.isError()).as(answer.text()).isFalse();
            JsonNode body = answer.result().get("structuredContent");
            assertThat(body.get("sources").propertyNames()).containsExactly(label(privacy));
            assertThat(body.get("sources").get(label(privacy)).get("status").asString()).isEqualTo("ANSWERED");
            assertThat(answer.text()).contains("alpha");
        }
        assertThat(rig.audited).hasSize(2);
        assertThat(rig.audited.get(1)).satisfies(event -> {
            assertThat(event.tool()).isEqualTo("follow");
            assertThat(event.policyDecision()).isEqualTo("ALLOW");
            assertThat(event.rejectedArguments()).containsExactly("principalId");
        });
    }
}
