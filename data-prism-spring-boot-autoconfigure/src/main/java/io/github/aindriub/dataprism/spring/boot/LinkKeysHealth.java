package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.link.LinkKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The {@code linkKeys} health contributor: the ids of the link keys this member holds, so that rotation phase 1 (a
 * new key held everywhere before it signs) can be confirmed member by member. Ids only: no key material, no
 * reference. {@code DOWN} when a configured key does not resolve now (the key set the keys were built with is not
 * the set the member can verify with, so a link sealed under it would be {@code UNKNOWN_KEY}). Present only with
 * Spring Boot Actuator and a configured link key, and not part of the readiness group: a missing link key does not
 * stop the member serving anything but links.
 */
@ConditionalOnClass(HealthIndicator.class)
@Configuration(proxyBeanMethods = false)
class LinkKeysHealth {

    /** The bean name minus {@code HealthIndicator} is the contributor name: {@code linkKeys}. */
    @Bean
    @Conditional(CatalogWiring.LinkKeyConfigured.class)
    HealthIndicator linkKeysHealthIndicator(ObjectProvider<LinkKeys> keys, DataPrismProperties properties) {
        // Resolved on each look, so this bean never forces the keys to be built early.
        LinksProperties links = properties.getLinks();
        Set<String> configured = new TreeSet<>(links.getKeys().keySet());
        return indicator(() -> keys.getIfAvailable(), links.getKeyId(), configured);
    }

    private static Set<String> heldNow(Supplier<LinkKeys> keys) {
        try {
            LinkKeys current = keys.get();
            return current == null ? Set.of() : current.heldKeyIds();
        } catch (RuntimeException unreadable) {
            return Set.of();
        }
    }

    static HealthIndicator indicator(Supplier<LinkKeys> keys, String currentKeyId, Set<String> configured) {
        return () -> {
            Set<String> held = heldNow(keys);
            List<String> sorted = new TreeSet<>(held).stream().toList();
            boolean complete = held.containsAll(configured) && held.contains(currentKeyId);
            return (complete ? Health.up() : Health.down())
                    .withDetail("current", currentKeyId)
                    .withDetail("held", sorted)
                    .withDetail("configured", configured.size())
                    .withDetail("unresolved", configured.stream().filter(id -> !held.contains(id)).count())
                    .build();
        };
    }
}
