package io.github.aindriub.dataprism.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Primary standalone Data Prism process.
 *
 * <p>Boot's own Hazelcast auto-configuration is excluded: Data Prism builds its cluster member
 * itself, from {@code dataprism.hazelcast.*}, and a stray {@code hazelcast.xml} or
 * {@code hazelcast.yaml} on the classpath must not start a second, unreviewed member.
 */
@SpringBootApplication(excludeName = "org.springframework.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration")
public class DataPrismServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataPrismServerApplication.class, args);
    }
}
