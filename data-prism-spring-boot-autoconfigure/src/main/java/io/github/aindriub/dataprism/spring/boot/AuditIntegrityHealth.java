package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



/** The {@code auditIntegrity} health contributor; present only when Spring Boot Actuator is. */
@ConditionalOnClass(org.springframework.boot.health.contributor.HealthIndicator.class)
@Configuration(proxyBeanMethods = false)
class AuditIntegrityHealth {
    /** The bean name minus {@code HealthIndicator} is the contributor name: {@code auditIntegrity}. */
    @Bean
    org.springframework.boot.health.contributor.HealthIndicator auditIntegrityHealthIndicator(
            AuditMaintenance maintenance) {
        return () -> {
            String code = maintenance.failureCode();
            if (code == null) {
                return org.springframework.boot.health.contributor.Health.up().build();
            }
            org.springframework.boot.health.contributor.Health.Builder down =
                    org.springframework.boot.health.contributor.Health.down().withDetail("code", code);
            if (maintenance.failureSegmentDate() != null) {
                down.withDetail("segmentDate", maintenance.failureSegmentDate());
            }
            return down.build();
        };
    }
}
