package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationDecision;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The token-trusted case authorizer and the unrestricted client allowlist are fallbacks for a deployment that
 * supplies neither bean. They must not ship in a release from 0.8.0 on (task 210 removes them). This fails
 * the build when the project version is a non-SNAPSHOT 0.8 or later and the fallback is still wired.
 *
 * <p>The version comes from {@code project-version.properties}, a test resource Maven filters with
 * {@code ${project.version}} (see this module's pom), so it cannot drift from the build.
 *
 * <p>Each fallback is probed separately and passes when startup refuses with its specific code
 * ({@code MISSING_CASE_AUTHORIZER} for the case authorizer; task 210 defines no refusal for an unset allowlist, so
 * only a non-fallback wiring passes there) or the wired bean no longer behaves as the fallback. Only {@code -SNAPSHOT}
 * skips the gate; release candidates do not.
 */
class UnrestrictedFallbackRemovedTest {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(-.+)?$");

    private static final String CASE_REFUSAL = "MISSING_CASE_AUTHORIZER";

    @Test
    void the_fallbacks_are_gone_before_a_0_8_release() throws IOException {
        String version = projectVersion();
        assertFallbackAllowed(version, "CaseAuthorizer.tokenTrusted()", caseFailure(), CASE_REFUSAL, caseFallbackWired());
        assertFallbackAllowed(version, "ClientAllowlist.unrestricted()", allowlistFailure(), null,
                allowlistFallbackWired());
    }

    @Test
    void the_guard_fails_for_a_0_8_0_release_while_the_fallback_exists() {
        assertThatThrownBy(() -> assertFallbackAllowed("0.8.0", "f", null, CASE_REFUSAL, true))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertFallbackAllowed("1.0.0", "f", null, CASE_REFUSAL, true))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void the_guard_fails_for_a_release_candidate_while_the_fallback_exists() {
        assertThatThrownBy(() -> assertFallbackAllowed("0.8.0-RC1", "f", null, CASE_REFUSAL, true))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void the_guard_allows_snapshots_earlier_versions_and_a_removed_fallback() {
        assertFallbackAllowed("0.8.0-SNAPSHOT", "f", null, CASE_REFUSAL, true);
        assertFallbackAllowed("0.7.0", "f", null, CASE_REFUSAL, true);
        assertFallbackAllowed("0.8.0", "f", null, CASE_REFUSAL, false);
        assertFallbackAllowed("0.8.0", "f", new DataPrismConfigurationException(CASE_REFUSAL, "x"), CASE_REFUSAL,
                false);
    }

    @Test
    void the_guard_fails_when_startup_fails_for_an_unrelated_reason() {
        assertThatThrownBy(() -> assertFallbackAllowed("0.8.0", "f", new IllegalStateException("boom"), CASE_REFUSAL,
                false)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertFallbackAllowed("0.8.0", "f",
                new DataPrismConfigurationException("SOME_OTHER_CODE", "x"), CASE_REFUSAL, false))
                .isInstanceOf(AssertionError.class);
        // an allowlist with no defined refusal accepts no startup failure at all
        assertThatThrownBy(() -> assertFallbackAllowed("0.8.0", "f", new IllegalStateException("boom"), null, false))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void the_guard_fails_when_only_one_fallback_is_removed() {
        // case authorizer removed (startup refused with its code), allowlist fallback still wired
        assertThatThrownBy(() -> {
            assertFallbackAllowed("0.8.0", "tokenTrusted", new DataPrismConfigurationException(CASE_REFUSAL, "x"),
                    CASE_REFUSAL, false);
            assertFallbackAllowed("0.8.0", "unrestricted", null, null, true);
        }).isInstanceOf(AssertionError.class);
    }

    static void assertFallbackAllowed(String version, String fallback, Throwable startupFailure,
            String refusalCode, boolean fallbackBehaviourSeen) {
        Matcher m = VERSION.matcher(version);
        assertThat(m.matches()).as("project version '%s' is not x.y.z[-qualifier]", version).isTrue();
        boolean snapshot = "-SNAPSHOT".equals(m.group(4));
        boolean atLeast08 = Integer.parseInt(m.group(1)) > 0 || Integer.parseInt(m.group(2)) >= 8;
        if (!snapshot && atLeast08) {
            boolean refusedAsExpected = startupFailure != null && refusalCode != null && hasCode(startupFailure, refusalCode);
            boolean removed = startupFailure == null ? !fallbackBehaviourSeen : refusedAsExpected;
            assertThat(removed)
                    .as("version %s: the %s fallback must be removed (task 210); startup failure: %s", version,
                            fallback, startupFailure)
                    .isTrue();
        }
    }

    private static boolean hasCode(Throwable failure, String code) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e && code.equals(e.code())) {
                return true;
            }
        }
        return false;
    }

    private static String projectVersion() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = UnrestrictedFallbackRemovedTest.class.getResourceAsStream("/project-version.properties")) {
            assertThat(in).as("project-version.properties on the test classpath").isNotNull();
            properties.load(in);
        }
        String version = properties.getProperty("version");
        assertThat(version).as("Maven filtering ran").isNotBlank().doesNotContain("${");
        return version;
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller("principal-1", "client-1",
            Set.of("investigator"), "investigation", "case-1", Instant.parse("2026-01-01T12:00:00Z"));

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withPropertyValues(SecurityWiringTest.valid());
    }

    /** Context with no CaseAuthorizer bean (an allowlist-independent probe). */
    private static Throwable caseFailure() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner().withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> failure.set(result.getStartupFailure()));
        return failure.get();
    }

    /** True when that context starts and a case the principal is not assigned to still resolves. */
    private static boolean caseFallbackWired() {
        AtomicBoolean wired = new AtomicBoolean();
        runner().withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
            if (result.getStartupFailure() == null) {
                AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                        .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
                wired.set(result.getBean(ScopeResolver.class).resolve(CALLER, decision, CLOCK) != null);
            }
        });
        return wired.get();
    }

    /** Context with a CaseAuthorizer bean but no ClientAllowlist bean. */
    private static Throwable allowlistFailure() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner().withUserConfiguration(SecurityWiringTest.Base.class, SecurityWiringTest.RefusingCaseAuthorizer.class)
                .run(result -> failure.set(result.getStartupFailure()));
        return failure.get();
    }

    /** True when that context starts and permits a client nobody listed. */
    private static boolean allowlistFallbackWired() {
        AtomicBoolean wired = new AtomicBoolean();
        runner().withUserConfiguration(SecurityWiringTest.Base.class, SecurityWiringTest.RefusingCaseAuthorizer.class)
                .run(result -> {
                    if (result.getStartupFailure() == null) {
                        wired.set(result.getBean(AuthorizationService.class)
                                .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"))
                                .allowed());
                    }
                });
        return wired.get();
    }
}
