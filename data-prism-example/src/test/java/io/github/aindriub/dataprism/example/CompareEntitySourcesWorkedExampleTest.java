package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.mcp.CompareEntitySourcesTool;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code compare_entity_sources} put on the real assembly named in task 43:
 * real stub adapters, the real {@link io.github.aindriub.dataprism.core.ScrubbingEngine}
 * ({@code DataPrismAssembly.standard()} wires {@code JsonTreeScrubbingEngine}, never a
 * stub), real validators, real audit.
 *
 * <p>This class exists to settle one question {@code CompareEntitySourcesToolTest}
 * could not: that class's own
 * {@code identityResolvesFieldNamesThatDifferFromTheNamespace} test proves the
 * tool's {@code identity()} method correctly looks up a namespace's field names
 * via {@link io.github.aindriub.dataprism.orchestration.ContextResponse#fieldsFor},
 * but it does so against a hand-written {@code ScrubbingEngine} lambda that the
 * test itself keys by serialised field name — an assumption about the real
 * engine, not a proof of it. {@link CustomerDto#customerName()} and
 * {@link AccountDto#holderName()} are both classified
 * {@code PrivacyNamespace.PERSON_NAME}, under two field names that differ from
 * each other and from the namespace's own name. Driving {@code compare_entity_sources}
 * over that real fixture, through the real engine, and finding the pseudonym
 * under {@code customerName} and {@code holderName} in {@code identity} is the
 * only place in this codebase that proves
 * {@code JsonTreeScrubbingEngine} really does key its output tree by
 * {@code FieldMetadata.fieldName()} — see {@code JsonTreeScrubbingEngine.scrubObject},
 * which writes every scrubbed value back with {@code out.set(field, scrubbed)}
 * under the source's own serialised field name.
 */
class CompareEntitySourcesWorkedExampleTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
    private static final String PURPOSE = "demonstration";
    private static final String ROLE = "investigator";
    private static final String CASE_ID = "case-compare-1";

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;
    private final DataPrismAssembly assembly = DataPrismAssembly.standard();
    private final AuditRecorder toolAudit = new AuditRecorder(sink, FIXED, "example-1-mcp");

    private static AuthenticatedCaller caller() {
        return new AuthenticatedCaller("investigator-1", "client-1", Set.of(ROLE), PURPOSE, CASE_ID, null);
    }

    private static SecurityPolicy policyGranting(Set<String> capabilities) {
        return new SecurityPolicy(Set.of(PURPOSE), Map.of(ROLE, capabilities));
    }

    private static AuthorizationService authorizationServiceGranting(Set<String> capabilities) {
        return new AuthorizationService(policyGranting(capabilities), "DEFAULT", PrivacyScopeType.INVESTIGATION);
    }

    private static ScopeResolver scopeResolverFor(DataPrismAssembly assembly) {
        return new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(PURPOSE)));
    }

    private static McpSyncServerExchange exchangeFor(AuthenticatedCaller caller) {
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null,
                McpTransportContext.create(Map.of(
                        CompareEntitySourcesTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));
    }

    private static String soleText(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    @DisplayName("compare_entity_sources reports the disputed name, keyed by the real field names, "
            + "without leaking any raw fixture value")
    void comparesCustomer123WithoutLeakingRawValues() {
        Set<String> capabilities = Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES);
        AuthorizationService authorizationService = authorizationServiceGranting(capabilities);
        ScopeResolver scopeResolver = scopeResolverFor(assembly);

        GetEntityContextTool contextTool = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, FIXED);
        CompareEntitySourcesTool compareTool = new CompareEntitySourcesTool(assembly.orchestrator(),
                authorizationService, scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(),
                toolAudit, FIXED);

        // get_entity_context, in the same scope, only to read off the
        // scope-local alias set compare_entity_sources' own agreement groups
        // must be drawn from -- not to change what is asserted below.
        McpSchema.CallToolResult contextResult = contextTool.specification().callHandler().apply(
                exchangeFor(caller()), new McpSchema.CallToolRequest(
                        GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));
        McpSchema.CallToolResult compareResult = compareTool.specification().callHandler().apply(
                exchangeFor(caller()), new McpSchema.CallToolRequest(
                        CompareEntitySourcesTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(contextResult.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(compareResult.isError()).isNotEqualTo(Boolean.TRUE);

        // Neither serialised form -- the text content nor the structured one --
        // carries a raw fixture value.
        String text = soleText(compareResult);
        assertThat(text)
                .doesNotContain("Patrick Murphy").doesNotContain("Pat Murphy").doesNotContain("P. Murphy")
                .doesNotContain("patrick.murphy@example.invalid").doesNotContain("\"123\"");

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) compareResult.structuredContent();
        assertThat(String.valueOf(structured.get("subject")))
                .as("the subject in structured content is the pseudonym, never the raw subject id")
                .doesNotContain("123").startsWith("SUBJ-");

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) structured.get("identity");
        assertThat(identity)
                .as("identity must not be empty of signal -- the disputed name must be present, pseudonymised")
                .isNotEmpty();
        // Keyed by the real, serialised field names JsonTreeScrubbingEngine
        // actually wrote the scrubbed tree under -- customerName (customer-api
        // and order-api) and holderName (account-api) -- never by the
        // namespace's own name, which is not itself a key in a real scrubbed
        // tree.
        assertThat(identity.keySet()).contains("customerName", "holderName");
        assertThat(identity.keySet()).doesNotContain("PERSON_NAME");
        for (Map.Entry<String, Object> entry : identity.entrySet()) {
            String value = String.valueOf(entry.getValue());
            assertThat(value)
                    .as("identity value for '%s' must never be a raw fixture value", entry.getKey())
                    .doesNotContain("Patrick Murphy").doesNotContain("Pat Murphy").doesNotContain("P. Murphy");
        }
        // The same subject reads as the same pseudonym everywhere in the tree,
        // exactly as WorkedExampleTest already proves for get_entity_context.
        assertThat(identity.get("customerName")).isEqualTo(identity.get("holderName"));

        // Non-empty of signal the other way too: a finding about the disputed
        // field, with more than one agreement group -- three distinct
        // spellings across three sources cannot collapse into one group -- and
        // every alias it names is drawn from the same scope-local alias set
        // get_entity_context's own sources() uses, never a real source name.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) structured.get("findings");
        Map<String, Object> nameFinding = findings.stream()
                .filter(f -> "PERSON_NAME".equals(f.get("namespace")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no PERSON_NAME finding in " + findings));
        assertThat(nameFinding.get("field"))
                .as("a namespace-compared finding's field is the namespace's own name")
                .isEqualTo("PERSON_NAME");

        @SuppressWarnings("unchecked")
        List<List<String>> agreementGroups = (List<List<String>>) nameFinding.get("agreementGroups");
        assertThat(agreementGroups.size())
                .as("three spellings of one name must not read as one agreement group")
                .isGreaterThan(1);

        Set<String> aliasesInFinding = agreementGroups.stream()
                .flatMap(List::stream).collect(Collectors.toSet());
        assertThat(aliasesInFinding)
                .doesNotContain("customer-api", "account-api", "order-api");

        @SuppressWarnings("unchecked")
        Map<String, Object> contextBody = (Map<String, Object>) contextResult.structuredContent();
        @SuppressWarnings("unchecked")
        Set<String> sourceAliases = ((Map<String, Object>) contextBody.get("sources")).keySet();
        assertThat(sourceAliases).doesNotContain("customer-api", "account-api", "order-api");
        assertThat(sourceAliases)
                .as("every alias a finding names must be one of the aliases get_entity_context's own "
                        + "sources() uses, in the same scope")
                .containsAll(aliasesInFinding);
    }

    @Test
    @DisplayName("the audit trail records the comparison call under its own name, with the pseudonym as the subject")
    void auditTrailRecordsComparisonCallUnderItsOwnName() {
        // An allowed call is audited by the orchestrator itself (the tool's own
        // audit.record is reserved for a denial it must report without ever
        // reaching the orchestrator), so this assembly is built with the same
        // in-memory sink the test reads back from -- DataPrismAssembly.standard()
        // wires stderr instead, exactly like EndToEndTest's own pattern.
        DataPrismAssembly localAssembly = new DataPrismAssembly(
                List.of(new StubCustomerAdapter(), new StubAccountAdapter(), new StubOrderAdapter()), FIXED, sink);
        AuthorizationService authorizationService =
                authorizationServiceGranting(Set.of(Capability.COMPARE_ENTITY_SOURCES));
        CompareEntitySourcesTool compareTool = new CompareEntitySourcesTool(localAssembly.orchestrator(),
                authorizationService, scopeResolverFor(localAssembly), DataPrismObjectMapper.create(),
                PrivacyMetrics.none(), toolAudit, FIXED);

        McpSchema.CallToolResult result = compareTool.specification().callHandler().apply(
                exchangeFor(caller()), new McpSchema.CallToolRequest(
                        CompareEntitySourcesTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("ALLOW");
            assertThat(event.tool()).isEqualTo("compare_entity_sources");
            assertThat(event.subjectPseudonym()).startsWith("SUBJ-").isNotEqualTo("123");
        });
    }

    @Test
    @DisplayName("a caller granted only GET_ENTITY_CONTEXT is denied compare_entity_sources, the denial is "
            + "audited, and no source adapter is ever invoked")
    void callerWithoutCompareCapabilityIsDeniedAndNoAdapterIsInvoked() {
        CountingAdapter<CustomerDto> customer = new CountingAdapter<>(new StubCustomerAdapter());
        CountingAdapter<AccountDto> account = new CountingAdapter<>(new StubAccountAdapter());
        CountingAdapter<OrderDto> order = new CountingAdapter<>(new StubOrderAdapter());
        List<DataSourceAdapter<?>> adapters = List.of(customer, account, order);
        DataPrismAssembly localAssembly = new DataPrismAssembly(adapters, FIXED, sink);

        AuthorizationService authorizationService =
                authorizationServiceGranting(Set.of(Capability.GET_ENTITY_CONTEXT));
        CompareEntitySourcesTool compareTool = new CompareEntitySourcesTool(localAssembly.orchestrator(),
                authorizationService, scopeResolverFor(localAssembly), DataPrismObjectMapper.create(),
                PrivacyMetrics.none(), toolAudit, FIXED);

        McpSchema.CallToolResult result = compareTool.specification().callHandler().apply(
                exchangeFor(caller()), new McpSchema.CallToolRequest(
                        CompareEntitySourcesTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo("TOOL_NOT_PERMITTED");
        assertThat(customer.fetchCount()).as("customer-api must never be reached on a denial").isZero();
        assertThat(account.fetchCount()).as("account-api must never be reached on a denial").isZero();
        assertThat(order.fetchCount()).as("order-api must never be reached on a denial").isZero();
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("TOOL_NOT_PERMITTED");
            assertThat(event.tool()).isEqualTo("compare_entity_sources");
        });
    }

    /** Counts every {@link #fetch}, so a test can prove a source was never reached rather than assume it. */
    private static final class CountingAdapter<T> implements DataSourceAdapter<T> {
        private final DataSourceAdapter<T> delegate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingAdapter(DataSourceAdapter<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String sourceName() {
            return delegate.sourceName();
        }

        @Override
        public Class<T> responseType() {
            return delegate.responseType();
        }

        @Override
        public T fetch(DataRequest request) {
            calls.incrementAndGet();
            return delegate.fetch(request);
        }

        int fetchCount() {
            return calls.get();
        }
    }
}
