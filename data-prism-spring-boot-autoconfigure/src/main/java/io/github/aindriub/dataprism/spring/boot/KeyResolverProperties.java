package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * {@code dataprism.key-resolvers.*}: the bounds on a {@code follow} that goes through an application
 * {@code KeyResolver}. Bound as text so that a value that does not parse refuses with its stable code.
 */
public class KeyResolverProperties {
    /** The ceiling of {@code max-targets} (decision OD-08-16). */
    public static final int MAX_TARGETS_CEILING = 16;

    /** The most keys one resolver may return for one key. 1 to 16. */
    private String maxTargets = "5";
    /** The longest a resolver call may take. A positive duration. */
    private String timeout = "PT2S";
    /** The reads one resolver call is charged against the scope budget. Positive. */
    private String budgetCost = "1";

    public String getMaxTargets() {
        return maxTargets;
    }

    public void setMaxTargets(String v) {
        maxTargets = v;
    }

    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String v) {
        timeout = v;
    }

    public String getBudgetCost() {
        return budgetCost;
    }

    public void setBudgetCost(String v) {
        budgetCost = v;
    }

    /** @throws DataPrismConfigurationException {@code INVALID_KEY_RESOLVER_SETTING} unless 1 to 16 */
    public int maxTargetsValue() {
        return whole(maxTargets, MAX_TARGETS_CEILING, "dataprism.key-resolvers.max-targets");
    }

    /** @throws DataPrismConfigurationException {@code INVALID_KEY_RESOLVER_SETTING} unless a positive duration */
    public Duration timeoutValue() {
        try {
            if (timeout != null && !timeout.isBlank()) {
                Duration parsed = DurationStyle.detectAndParse(timeout.strip());
                if (!parsed.isZero() && !parsed.isNegative()) {
                    return parsed;
                }
            }
        } catch (RuntimeException notADuration) {
            // Refused below, naming the property only.
        }
        throw new DataPrismConfigurationException("INVALID_KEY_RESOLVER_SETTING",
                "dataprism.key-resolvers.timeout must be a positive duration");
    }

    /** @throws DataPrismConfigurationException {@code INVALID_KEY_RESOLVER_SETTING} unless positive */
    public int budgetCostValue() {
        return whole(budgetCost, Integer.MAX_VALUE, "dataprism.key-resolvers.budget-cost");
    }

    private static int whole(String text, int max, String property) {
        try {
            int parsed = Integer.parseInt(text.strip());
            if (parsed >= 1 && parsed <= max) {
                return parsed;
            }
        } catch (RuntimeException notAWholeNumber) {
            // Refused below, naming the property only.
        }
        throw new DataPrismConfigurationException("INVALID_KEY_RESOLVER_SETTING", property + (max == Integer.MAX_VALUE
                ? " must be a positive whole number" : " must be a whole number from 1 to " + max));
    }
}
