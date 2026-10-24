package io.github.aindriub.dataprism.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
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
 * <p>Task 210: removing the fallback makes {@link #fallbackIsStillWired()} false; delete this test then, or
 * change the probe to assert the refusal.
 */
class UnrestrictedFallbackRemovedTest {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(-.+)?$");

    @Test
    void the_fallback_is_gone_before_a_0_8_release() throws IOException {
        assertFallbackAllowed(projectVersion(), fallbackIsStillWired());
    }

    @Test
    void the_guard_fails_for_a_0_8_0_release_while_the_fallback_exists() {
        assertThatThrownBy(() -> assertFallbackAllowed("0.8.0", true)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertFallbackAllowed("1.0.0", true)).isInstanceOf(AssertionError.class);
    }

    @Test
    void the_guard_allows_snapshots_earlier_versions_and_a_removed_fallback() {
        assertFallbackAllowed("0.8.0-SNAPSHOT", true);
        assertFallbackAllowed("0.7.0", true);
        assertFallbackAllowed("0.8.0", false);
    }

    static void assertFallbackAllowed(String version, boolean fallbackExists) {
        Matcher m = VERSION.matcher(version);
        assertThat(m.matches()).as("project version '%s' is not x.y.z[-qualifier]", version).isTrue();
        boolean release = m.group(4) == null;
        boolean atLeast08 = Integer.parseInt(m.group(1)) > 0 || Integer.parseInt(m.group(2)) >= 8;
        if (release && atLeast08) {
            assertThat(fallbackExists)
                    .as("version %s: the token-trusted / unrestricted fallback must be removed (task 210)", version)
                    .isFalse();
        }
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

    /** True when a context with neither a CaseAuthorizer nor a ClientAllowlist bean still starts. */
    private static boolean fallbackIsStillWired() {
        AtomicBoolean started = new AtomicBoolean();
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withPropertyValues(SecurityWiringTest.valid())
                .withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> started.set(result.getStartupFailure() == null));
        return started.get();
    }
}
