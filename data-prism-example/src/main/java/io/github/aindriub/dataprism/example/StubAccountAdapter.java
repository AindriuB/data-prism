package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/** Stands in for an account API. Invented data; no real person is described. */
public final class StubAccountAdapter implements DataSourceAdapter<AccountDto> {

    private static final Map<String, AccountDto> RECORDS = Map.of(
            // The same customer as the customer API, recorded less formally.
            "123", new AccountDto("123", "ACC-1", "Pat Murphy", new BigDecimal("4200.55")),
            "456", new AccountDto("456", "ACC-2", "Aoife Byrne", new BigDecimal("18.00")));

    @Override
    public String sourceName() {
        return "account-api";
    }

    @Override
    public Class<AccountDto> responseType() {
        return AccountDto.class;
    }

    @Override
    public AccountDto fetch(DataRequest request) {
        return RECORDS.get(request.subjectId());
    }

    /**
     * The fixture records themselves, for tests that need to enumerate what
     * this stub actually holds — {@code PiiLogScanTest} derives its banned
     * value set from this rather than keeping a second, hand-written copy of
     * it. Exposes fixture data only; {@link #fetch} is unchanged.
     */
    public static Collection<AccountDto> fixtureRecords() {
        return RECORDS.values();
    }
}
