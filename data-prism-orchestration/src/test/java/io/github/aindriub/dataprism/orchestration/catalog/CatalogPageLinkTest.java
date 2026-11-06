package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.SCOPE_END;
import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.caller;
import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.context;
import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.names;
import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.sources;
import static org.assertj.core.api.Assertions.assertThat;

/** The page link: what it binds, what refuses it, and how the attempt limiter shapes the answer. */
class CatalogPageLinkTest {

    private static PagingSettings settings() {
        return new PagingSettings(2, 5, Duration.ofMinutes(15));
    }

    private static String firstNext(Fixture fx) {
        CatalogQueries.Outcome first = fx.list(null, null);
        return first.body().get("page").get("next").asString();
    }

    private static CatalogQueries.Outcome call(Fixture fx, String principal, String scope, boolean real,
                                               CatalogQueries.ListRequest request) {
        return fx.queries.listSources(request, caller(principal, real), context(scope), null, "c-1");
    }

    private static CatalogQueries.ListRequest page(String token) {
        return new CatalogQueries.ListRequest(null, null, null, token);
    }

    private static void assertRefused(CatalogQueries.Outcome outcome, String code, String audit) {
        assertThat(outcome.body()).isNull();
        assertThat(outcome.refusalCode()).isEqualTo(code);
        assertThat(outcome.dispositions()).containsEntry("link", audit);
        assertThat(outcome.sourceSystems()).isEmpty();
    }

    private static String forge(String token) {
        int at = token.length() / 2;
        return token.substring(0, at) + (token.charAt(at) == 'A' ? 'B' : 'A') + token.substring(at + 1);
    }

    // ---- what the link is ----

    @Test
    @DisplayName("a page link carries relation 0x02 and an empty target in its cleartext header")
    void relationAndEmptyTarget() {
        Fixture fx = new Fixture(names(5), settings(), true);

        byte[] raw = Base64.getUrlDecoder().decode(firstNext(fx));

        int keyIdLength = raw[1];
        int targetLengthAt = 2 + keyIdLength + 8;
        assertThat(raw[targetLengthAt]).isZero();
        assertThat(raw[targetLengthAt + 1]).isEqualTo((byte) 0x02);
    }

    @Test
    @DisplayName("two instances over the same catalog accept each other's link: the tag is a function of the labels")
    void sameCatalogSameTag() {
        String next = firstNext(new Fixture(names(5), settings(), true));

        CatalogQueries.Outcome resumed = call(new Fixture(names(5), settings(), true), "investigator-1", "scope-a",
                true, page(next));

        assertThat(resumed.refusalCode()).isNull();
        assertThat(sources(resumed)).containsExactly("src-003", "src-004");
    }

    @Test
    @DisplayName("expiresAt is the link's expiry, within the paging lifetime")
    void expiryIsWithinTheLifetime() {
        Fixture fx = new Fixture(names(5), settings(), true);

        Instant expiresAt = Instant.parse(fx.list(null, null).body().get("page").get("expiresAt").asString());

        assertThat(expiresAt).isEqualTo(CatalogQueriesTest.T0.plus(Duration.ofMinutes(15)));
    }

    // ---- refusals ----

    @Test
    @DisplayName("a link is refused in another scope, for another caller, after expiry: LINK_INVALID with its reason")
    void bindingRefusals() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String next = firstNext(fx);

        assertRefused(call(fx, "investigator-1", "scope-b", true, page(next)), "LINK_INVALID", "REFUSED:LINK_AUTH_FAILED");
        assertRefused(call(fx, "investigator-2", "scope-a", true, page(next)), "LINK_INVALID", "REFUSED:LINK_AUTH_FAILED");
        fx.clock.now = CatalogQueriesTest.T0.plus(Duration.ofMinutes(16));
        assertRefused(call(fx, "investigator-1", "scope-a", true, page(next)), "LINK_INVALID", "REFUSED:LINK_EXPIRED");
    }

    @Test
    @DisplayName("different filters or pageSize, a changed label kind and a changed catalog are refused")
    void walkRefusals() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String next = firstNext(fx);

        assertRefused(call(fx, "investigator-1", "scope-a", true,
                new CatalogQueries.ListRequest("PARCEL", null, null, next)), "LINK_INVALID", "REFUSED:LINK_FILTER_MISMATCH");
        assertRefused(call(fx, "investigator-1", "scope-a", true,
                new CatalogQueries.ListRequest(null, "parcel-ref", null, next)), "LINK_INVALID", "REFUSED:LINK_FILTER_MISMATCH");
        assertRefused(call(fx, "investigator-1", "scope-a", true,
                new CatalogQueries.ListRequest(null, null, 3, next)), "LINK_INVALID", "REFUSED:LINK_FILTER_MISMATCH");
        assertRefused(call(fx, "investigator-1", "scope-a", false, page(next)), "LINK_INVALID", "REFUSED:LINK_LABELS_CHANGED");
        // A source added, and then one removed: the visible set differs either way.
        assertRefused(call(new Fixture(names(6), settings(), true), "investigator-1", "scope-a", true, page(next)),
                "LINK_INVALID", "REFUSED:LINK_CATALOG_CHANGED");
        assertRefused(call(new Fixture(names(4), settings(), true), "investigator-1", "scope-a", true, page(next)),
                "LINK_INVALID", "REFUSED:LINK_CATALOG_CHANGED");
    }

    @Test
    @DisplayName("a walk with the same filters and the same pageSize resumes; omitted arguments take the link's")
    void walkResumesWithRepeatedArguments() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String next = firstNext(fx);

        assertThat(call(fx, "investigator-1", "scope-a", true, new CatalogQueries.ListRequest(null, null, 2, next))
                .refusalCode()).isNull();
        assertThat(call(fx, "investigator-1", "scope-a", true, page(next)).refusalCode()).isNull();
    }

    @Test
    @DisplayName("a narrowed visible set refuses the walk")
    void narrowedVisibilityRefuses() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String next = firstNext(fx);
        CatalogQueries narrowed = new CatalogQueries(SourceCatalog.build(fx.adapters, Map.of()),
                (caller, name) -> !name.equals("src-005"), new io.github.aindriub.dataprism.orchestration.SourceAliasing(
                        new io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource(
                                io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider.of(
                                        "development-only-key-not-for-any-real-data"))),
                new RealNamesPolicy(List.of("scope-*")), CatalogQueriesTest.orchestratorOver(fx.engine, fx.adapters),
                List.of(new io.github.aindriub.dataprism.validation.SensitivePatternValidator()), settings(),
                new CatalogQueries.PageLinks(fx.codec, fx.keys,
                        new io.github.aindriub.dataprism.core.link.LinkAttemptGate(fx.codec, fx.limiter)),
                true, fx.metrics, fx.clock);

        assertRefused(narrowed.listSources(page(next), caller("investigator-1", true), context("scope-a"), null, "c"),
                "LINK_INVALID", "REFUSED:LINK_CATALOG_CHANGED");
    }

    @Test
    @DisplayName("a follow link passed as page is refused as LINK_WRONG_TOOL and is not counted")
    void followLinkIsWrongTool() {
        Fixture fx = new Fixture(names(5), settings(), true);
        FollowPayload payload = new FollowPayload(new byte[8], CatalogQueriesTest.T0, "parcel-ref", Optional.empty(),
                new byte[] {1, 2, 3}, "src-001", LinkRelation.DIRECT);
        String follow = fx.codec.seal(payload, new LinkBinding("scope-a", "investigator-1", SCOPE_END, SCOPE_END))
                .orElseThrow().token();

        assertRefused(call(fx, "investigator-1", "scope-a", true, page(follow)), "LINK_INVALID", "REFUSED:LINK_WRONG_TOOL");
        assertThat(fx.limiter.recorded).isZero();
    }

    @Test
    @DisplayName("a forged page token within the limit is LINK_AUTH_FAILED, counted once")
    void forgedTokenIsCounted() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String forged = forge(firstNext(fx));

        assertRefused(call(fx, "investigator-1", "scope-a", true, page(forged)), "LINK_INVALID", "REFUSED:LINK_AUTH_FAILED");
        assertThat(fx.limiter.recorded).isEqualTo(1);
    }

    @Test
    @DisplayName("structural failures count; expiry, filter and catalog refusals do not")
    void onlyPreAuthenticationFailuresCount() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String next = firstNext(fx);

        call(fx, "investigator-1", "scope-a", true, page("AAAA"));
        int afterMalformed = fx.limiter.recorded;
        call(fx, "investigator-1", "scope-a", true, new CatalogQueries.ListRequest("PARCEL", null, null, next));
        call(fx, "investigator-1", "scope-a", false, page(next));
        fx.clock.now = CatalogQueriesTest.T0.plus(Duration.ofMinutes(20));
        call(fx, "investigator-1", "scope-a", true, page(next));

        assertThat(afterMalformed).isEqualTo(1);
        assertThat(fx.limiter.recorded).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty or oversized token is refused uncounted")
    void emptyTokenIsUncounted() {
        Fixture fx = new Fixture(names(5), settings(), true);

        assertRefused(call(fx, "investigator-1", "scope-a", true, page("")), "LINK_INVALID", "REFUSED:LINK_MALFORMED");
        assertThat(fx.limiter.recorded).isZero();
    }

    @Test
    @DisplayName("UNREADABLE_PAGE is a non-empty token that fails to decode: MALFORMED, counted once, LINK_INVALID")
    void unreadablePageIsACountedMalformedLink() {
        Fixture fx = new Fixture(names(5), settings(), true);

        assertThat(CatalogQueries.UNREADABLE_PAGE).isNotEmpty();
        assertRefused(call(fx, "investigator-1", "scope-a", true, page(CatalogQueries.UNREADABLE_PAGE)),
                "LINK_INVALID", "REFUSED:LINK_MALFORMED");
        assertThat(fx.limiter.recorded).isEqualTo(1);
    }

    // ---- the limiter ----

    @Test
    @DisplayName("a locked principal gets LINK_ATTEMPTS_EXHAUSTED for a call with page, never for a call without")
    void lockedPrincipal() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String next = firstNext(fx);
        fx.limiter.lock = LinkAttemptLimiter.LockState.LOCKED;

        CatalogQueries.Outcome valid = call(fx, "investigator-1", "scope-a", true, page(next));
        CatalogQueries.Outcome without = fx.list(null, null);

        assertRefused(valid, "LINK_ATTEMPTS_EXHAUSTED", "REFUSED:LINK_ATTEMPTS_LOCKED");
        assertThat(without.refusalCode()).isNull();
        assertThat(sources(without)).hasSize(2);
    }

    private record Row(String name, java.util.function.Consumer<Fixture> setUp, boolean forged, String code,
                       String audit) {
    }

    private static Stream<Row> rows() {
        return Stream.of(
                new Row("LOCKED", fx -> fx.limiter.lock = LinkAttemptLimiter.LockState.LOCKED, false,
                        "LINK_ATTEMPTS_EXHAUSTED", "REFUSED:LINK_ATTEMPTS_LOCKED"),
                new Row("OVER_LIMIT", fx -> fx.limiter.admission = LinkAttemptLimiter.Admission.OVER_LIMIT, true,
                        "LINK_ATTEMPTS_EXHAUSTED", "REFUSED:LINK_ATTEMPTS_OVER_LIMIT"),
                new Row("CEILING", fx -> fx.limiter.admission = LinkAttemptLimiter.Admission.CEILING, true,
                        "LINK_ATTEMPTS_EXHAUSTED", "REFUSED:LINK_ATTEMPTS_CEILING"),
                new Row("lock UNAVAILABLE", fx -> fx.limiter.lock = LinkAttemptLimiter.LockState.UNAVAILABLE, false,
                        "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE"),
                new Row("lock null", fx -> fx.limiter.nullLock = true, false,
                        "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE"),
                new Row("lock throws", fx -> fx.limiter.throwOnLock = true, false,
                        "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE"),
                new Row("record UNAVAILABLE", fx -> fx.limiter.admission = LinkAttemptLimiter.Admission.UNAVAILABLE, true,
                        "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE"),
                new Row("record null", fx -> fx.limiter.nullRecord = true, true,
                        "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE"),
                new Row("record throws", fx -> fx.limiter.throwOnRecord = true, true,
                        "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE"),
                new Row("OPEN_FAILED", fx -> fx.clock.failing = true, false,
                        "LINK_INVALID", "REFUSED:LINK_ATTEMPTS_OPEN_FAILED"));
    }

    @Test
    @DisplayName("each limiter or opener state gives its client code and its exact audit value")
    void limiterRows() {
        for (Row row : rows().toList()) {
            Fixture fx = new Fixture(names(5), settings(), true);
            String next = firstNext(fx);
            row.setUp().accept(fx);

            CatalogQueries.Outcome outcome = call(fx, "investigator-1", "scope-a", true,
                    page(row.forged() ? forge(next) : next));

            assertThat(outcome.refusalCode()).as(row.name()).isEqualTo(row.code());
            assertThat(outcome.dispositions()).as(row.name()).containsEntry("link", row.audit());
            assertThat(outcome.body()).as(row.name()).isNull();
        }
    }

    @Test
    @DisplayName("LOCKED, OVER_LIMIT and CEILING give identical client results; OPEN_FAILED looks like a forged link")
    void clientResultsDoNotSeparateTheCauses() {
        List<CatalogQueries.Outcome> exhausted = rows().filter(row -> row.code().equals("LINK_ATTEMPTS_EXHAUSTED"))
                .map(row -> {
                    Fixture fx = new Fixture(names(5), settings(), true);
                    String next = firstNext(fx);
                    row.setUp().accept(fx);
                    return call(fx, "investigator-1", "scope-a", true, page(row.forged() ? forge(next) : next));
                }).toList();
        assertThat(exhausted).hasSize(3);
        assertThat(exhausted).allSatisfy(o -> {
            assertThat(o.refusalCode()).isEqualTo("LINK_ATTEMPTS_EXHAUSTED");
            assertThat(o.body()).isNull();
            assertThat(o.sourceSystems()).isEmpty();
        });

        Fixture failing = new Fixture(names(5), settings(), true);
        String next = firstNext(failing);
        failing.clock.failing = true;
        CatalogQueries.Outcome openFailed = call(failing, "investigator-1", "scope-a", true, page(next));
        failing.clock.failing = false;
        CatalogQueries.Outcome forgedOutcome = call(failing, "investigator-1", "scope-a", true, page(forge(next)));

        assertThat(openFailed.refusalCode()).isEqualTo(forgedOutcome.refusalCode()).isEqualTo("LINK_INVALID");
        assertThat(openFailed.sourceSystems()).isEqualTo(forgedOutcome.sourceSystems());
        assertThat(openFailed.dispositions()).containsEntry("link", "REFUSED:LINK_ATTEMPTS_OPEN_FAILED");
        assertThat(forgedOutcome.dispositions()).containsEntry("link", "REFUSED:LINK_AUTH_FAILED");
    }

    @Test
    @DisplayName("no client text reaches an outcome's audit parts or string form")
    void noClientTextInTheAuditParts() {
        Fixture fx = new Fixture(names(5), settings(), true);
        String token = "SENTINEL-TOKEN-" + "A".repeat(30);

        CatalogQueries.Outcome outcome = call(fx, "investigator-1", "scope-a", true,
                new CatalogQueries.ListRequest("SENTINEL-TYPE", null, null, token));

        assertThat(outcome.toString()).doesNotContain("SENTINEL");
        assertThat(outcome.dispositions().toString()).doesNotContain("SENTINEL");
        assertThat(new CatalogQueries.ListRequest("SENTINEL-TYPE", null, null, token).toString()).doesNotContain("SENTINEL");
        assertThat(Set.copyOf(outcome.dispositions().keySet())).containsExactly("link");
    }

    @Test
    @DisplayName("a link that cannot be sealed refuses the page rather than serve a last=false page without next")
    void unsealableLinkRefuses() {
        Fixture fx = new Fixture(names(5), settings(), true);
        fx.clock.failing = true;

        CatalogQueries.Outcome outcome = fx.list(null, null);

        assertThat(outcome.body()).isNull();
        assertThat(outcome.refusalCode()).isEqualTo(CatalogQueries.PAGING_UNAVAILABLE);
    }
}
