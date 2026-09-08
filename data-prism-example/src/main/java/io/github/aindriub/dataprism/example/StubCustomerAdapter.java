package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;

import java.util.Map;

/**
 * Stands in for a customer API.
 *
 * <p>S0 needs a source, not a network. The three divergent stub APIs that make
 * the consistency findings meaningful are S11; this one returns a single fixed
 * record so the thread through the pipeline can be proven on its own.
 *
 * <p>The data is invented. Nothing resembling a real person belongs in a
 * fixture, in this repository or any other.
 */
public final class StubCustomerAdapter implements DataSourceAdapter<CustomerDto> {

    private static final Map<String, CustomerDto> RECORDS = Map.of(
            "123", new CustomerDto("123", "Patrick Murphy", "patrick.murphy@example.invalid", "ACTIVE"),
            "456", new CustomerDto("456", "Aoife Byrne", "aoife.byrne@example.invalid", "DORMANT"));

    @Override
    public String sourceName() {
        return "customer-api";
    }

    @Override
    public Class<CustomerDto> responseType() {
        return CustomerDto.class;
    }

    @Override
    public CustomerDto fetch(DataRequest request) {
        return RECORDS.get(request.subjectId());
    }
}
