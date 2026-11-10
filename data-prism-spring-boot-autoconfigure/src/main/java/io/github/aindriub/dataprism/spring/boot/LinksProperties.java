package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code dataprism.links.*}: the link key, which page links need even while links are off, and the settings of
 * the agent-led surface ({@code _links} and {@code follow}), which {@link #isEnabled()} turns on. The numeric and
 * duration settings are bound as text so that a value that does not parse refuses with its stable code, not with a
 * binding error that would repeat the value.
 */
public class LinksProperties {
    /** Whether results carry {@code _links} and {@code follow} is served. Off by default. */
    private boolean enabled;
    /** How long a link stays valid: a positive duration of at least one second, or scope (up to the scope's expiry). */
    private String lifetime = "PT30M";
    /** The most links across one response. 1 to 500. */
    private String maxPerResponse = "50";
    /** The longest accepted or issued link, in characters. 616 to 4096. */
    private String maxLength = "1024";
    /** Counted failed attempts a caller may make within the window before it is locked. Positive. */
    private String invalidAttemptLimit = "20";
    /** The window of the failed-attempt count. A positive duration. */
    private String invalidAttemptWindow = "PT10M";
    /** The tolerance for link expiry and issue time. 0 to PT30S. */
    private String clockSkew = "PT5S";
    /** Whether the caller is bound into the link, so another caller cannot use it. */
    private boolean bindCaller = true;
    /** Id of the key that seals new links. It must be a key of the keys map below. At most 16 characters of A-Z, a-z, 0-9, underscore and hyphen. Unset means no page links. */
    private String keyId;
    /** Link keys by key id, the current one and any being retired: each held for verification, only key-id signs. Set exactly one of environment-variable and provider-reference per key. */
    private Map<String, Key> keys = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public String getLifetime() {
        return lifetime;
    }

    public void setLifetime(String v) {
        lifetime = v;
    }

    public String getMaxPerResponse() {
        return maxPerResponse;
    }

    public void setMaxPerResponse(String v) {
        maxPerResponse = v;
    }

    public String getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(String v) {
        maxLength = v;
    }

    public String getInvalidAttemptLimit() {
        return invalidAttemptLimit;
    }

    public void setInvalidAttemptLimit(String v) {
        invalidAttemptLimit = v;
    }

    public String getInvalidAttemptWindow() {
        return invalidAttemptWindow;
    }

    public void setInvalidAttemptWindow(String v) {
        invalidAttemptWindow = v;
    }

    public String getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(String v) {
        clockSkew = v;
    }

    public boolean isBindCaller() {
        return bindCaller;
    }

    public void setBindCaller(boolean v) {
        bindCaller = v;
    }

    /**
     * The lifetime, or {@code null} for {@code scope}.
     *
     * @throws DataPrismConfigurationException {@code INVALID_LINK_LIFETIME} unless a duration of at least a second, or scope
     */
    public Duration lifetimeValue() {
        if (lifetime != null && "scope".equalsIgnoreCase(lifetime.strip())) {
            return null;
        }
        Duration parsed = duration(lifetime, "INVALID_LINK_LIFETIME", "dataprism.links.lifetime");
        if (parsed.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new DataPrismConfigurationException("INVALID_LINK_LIFETIME",
                    "dataprism.links.lifetime must be at least one second, or scope");
        }
        return parsed;
    }

    /** @throws DataPrismConfigurationException {@code INVALID_LINK_LIMIT} unless 1 to 500 */
    public int maxPerResponseValue() {
        return whole(maxPerResponse, 1, 500, "dataprism.links.max-per-response");
    }

    /** @throws DataPrismConfigurationException {@code INVALID_LINK_LIMIT} unless 616 to 4096 */
    public int maxLengthValue() {
        return whole(maxLength, 616, 4096, "dataprism.links.max-length");
    }

    /** @throws DataPrismConfigurationException {@code INVALID_LINK_LIMIT} unless positive */
    public int invalidAttemptLimitValue() {
        return whole(invalidAttemptLimit, 1, Integer.MAX_VALUE, "dataprism.links.invalid-attempt-limit");
    }

    /** @throws DataPrismConfigurationException {@code INVALID_LINK_LIMIT} unless a positive duration */
    public Duration invalidAttemptWindowValue() {
        Duration parsed = duration(invalidAttemptWindow, "INVALID_LINK_LIMIT", "dataprism.links.invalid-attempt-window");
        if (parsed.isZero() || parsed.isNegative()) {
            throw new DataPrismConfigurationException("INVALID_LINK_LIMIT",
                    "dataprism.links.invalid-attempt-window must be a positive duration");
        }
        return parsed;
    }

    /** @throws DataPrismConfigurationException {@code INVALID_LINK_LIMIT} unless 0 to PT30S */
    public Duration clockSkewValue() {
        Duration parsed = duration(clockSkew, "INVALID_LINK_LIMIT", "dataprism.links.clock-skew");
        if (parsed.isNegative() || parsed.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new DataPrismConfigurationException("INVALID_LINK_LIMIT",
                    "dataprism.links.clock-skew must be 0 to PT30S");
        }
        return parsed;
    }

    private static int whole(String text, int min, int max, String property) {
        try {
            int parsed = Integer.parseInt(text.strip());
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (RuntimeException notAWholeNumber) {
            // Refused below, naming the property only.
        }
        throw new DataPrismConfigurationException("INVALID_LINK_LIMIT", property + (max == Integer.MAX_VALUE
                ? " must be a positive whole number" : " must be a whole number from " + min + " to " + max));
    }

    private static Duration duration(String text, String code, String property) {
        try {
            if (text != null && !text.isBlank()) {
                return DurationStyle.detectAndParse(text.strip());
            }
        } catch (RuntimeException notADuration) {
            // Refused below, naming the property only.
        }
        throw new DataPrismConfigurationException(code, property + " must be a duration");
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String v) {
        keyId = v;
    }

    public Map<String, Key> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, Key> v) {
        keys = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
    }

    /** Whether a link key is configured at all. */
    public boolean keyConfigured() {
        return keyId != null && !keyId.isBlank();
    }

    /** One link key's reference; its id is the map key. Set exactly one of the two. */
    public static class Key {
        /** Name of the environment variable holding the key. Exactly one of this and provider-reference. */
        private String environmentVariable;
        /** Reference to a key provider for the key. Exactly one of this and environment-variable. */
        private String providerReference;

        public String getEnvironmentVariable() {
            return environmentVariable;
        }

        public void setEnvironmentVariable(String v) {
            environmentVariable = v;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public void setProviderReference(String v) {
            providerReference = v;
        }
    }
}
