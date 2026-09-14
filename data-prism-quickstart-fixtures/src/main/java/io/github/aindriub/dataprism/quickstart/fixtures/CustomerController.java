package io.github.aindriub.dataprism.quickstart.fixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The synthetic customer API the quickstart's reviewed extension calls.
 *
 * <p>Every value below is invented for this quickstart. It never described a
 * real person, and a 404 for an unknown id is data, not a failure — see
 * {@code RestDataSourceAdapter} in {@code data-prism-connectors-rest} for why
 * that distinction matters to the orchestrator that eventually calls this.
 */
@RestController
class CustomerController {

    private static final Map<String, CustomerRecord> RECORDS = Map.of(
            "1001", new CustomerRecord(
                    "1001", "Fixture Person One", "fixture.person.one@example.invalid", "ACTIVE"),
            "1002", new CustomerRecord(
                    "1002", "Fixture Person Two", "fixture.person.two@example.invalid", "DORMANT"));

    @GetMapping("/customers/{id}")
    ResponseEntity<CustomerRecord> customer(@PathVariable("id") String id) {
        CustomerRecord record = RECORDS.get(id);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(record);
    }
}
