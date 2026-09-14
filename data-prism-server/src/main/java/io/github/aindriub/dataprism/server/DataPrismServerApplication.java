package io.github.aindriub.dataprism.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Primary standalone Data Prism process. */
@SpringBootApplication
public class DataPrismServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataPrismServerApplication.class, args);
    }
}
