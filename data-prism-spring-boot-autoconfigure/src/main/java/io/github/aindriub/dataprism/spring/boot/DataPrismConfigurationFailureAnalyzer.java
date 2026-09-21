package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a {@link DataPrismConfigurationException} into an operator-facing block instead of
 * the Java stack trace Spring would otherwise log at {@code ERROR}. This is the first-contact
 * failure the distribution image's own contract produces on purpose (see
 * {@code .github/workflows/publish-image.yml}'s "Verify the image refuses to start with no
 * configuration" step): the refusal itself is correct, but a stack trace is not how an operator
 * discovers what to do about it.
 *
 * <p>Only the stable {@link DataPrismConfigurationException#code()} is rendered, never the
 * exception's full message: {@code detail} passed to the exception's constructor at some call
 * sites (for example {@code UNKNOWN_AUDIT_SINK}, {@code UNKNOWN_PRIVACY_PROFILE}) carries a
 * configured value read from the environment, and this analyzer's output must never repeat one
 * back.
 */
class DataPrismConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<DataPrismConfigurationException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DataPrismConfigurationException cause) {
        // The simple class name + code alone, never cause.getMessage() or cause.toString():
        // both include the constructor's detail text, which some call sites populate with a
        // configured value (see the class javadoc above). This still yields the literal
        // "DataPrismConfigurationException: <CODE>" the publish workflow's smoke check greps
        // for; the detail text after it is simply never reproduced here.
        String description = String.format(
                "Data Prism refused to start: a required piece of deployment configuration is "
                        + "missing, invalid, or unsafe.%n%nRefusal: %s: %s",
                cause.getClass().getSimpleName(), cause.code());
        String action = String.format(
                "Supply the configuration this refusal names. See the deployment contract in "
                        + "docs/configuration.md for what `%s` requires, and docs/quickstart.md for a "
                        + "runnable, fully-configured demo to compare against.",
                cause.code());
        return new FailureAnalysis(description, action, cause);
    }
}
