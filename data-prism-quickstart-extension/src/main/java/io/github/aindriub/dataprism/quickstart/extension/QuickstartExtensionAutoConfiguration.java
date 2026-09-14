package io.github.aindriub.dataprism.quickstart.extension;

import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The reviewed extension {@code -Dloader.path} adds to the packaged standalone
 * server for the local Compose quickstart (task 18) — see {@code
 * ServerPackagingIT.ReviewedExtension} in {@code data-prism-server} for the
 * worked pattern this follows: an {@code IdentityResolver} bean plus a {@code
 * DataSourceAdapter} bean for every {@code dataprism.sources.*} entry the
 * deployment configures, both of which
 * {@code DataPrismAutoConfiguration.dataPrismIdentityResolverPreflight} and
 * {@code DataPrismContractValidator} refuse startup without.
 *
 * <p>Every source setting this reads — base URL, timeout — comes from {@code
 * dataprism.sources.customer.*}, the same server-controlled, HTTPS-only,
 * operator-owned configuration {@code DataPrismProperties} already validates
 * (task 21). Nothing here lets a caller choose a host, a path or a source: MCP
 * arguments carry only {@code entityType} and {@code subjectId}, per docs/
 * configuration.md.
 */
@AutoConfiguration
public class QuickstartExtensionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdentityResolver quickstartIdentityResolver() {
        // Every source the quickstart configures already keys on the same
        // subjectId, so the honest default resolver is the correct one — see
        // PassThroughIdentityResolver's own Javadoc.
        return new PassThroughIdentityResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    DataSourceAdapter<CustomerModel> quickstartCustomerAdapter(
            @Value("${dataprism.sources.customer.base-url}") String baseUrl,
            @Value("${dataprism.sources.customer.timeout}") String timeout) {
        Duration readTimeout = DurationStyle.detectAndParse(timeout);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) readTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        return new QuickstartCustomerAdapter(client);
    }
}
