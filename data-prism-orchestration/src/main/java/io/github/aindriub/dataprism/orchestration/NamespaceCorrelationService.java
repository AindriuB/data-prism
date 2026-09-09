package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.ConsistencyFinding;
import io.github.aindriub.dataprism.core.EntityCorrelationService;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.SourceTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Correlates sources by semantic namespace.
 *
 * <p>Reads raw records, groups their values by namespace, and reports how the
 * sources disagree without reporting what any of them said.
 */
public final class NamespaceCorrelationService implements EntityCorrelationService {

    /**
     * Namespaces where an abbreviation finding is allowed. These are the ones
     * whose values are names — a shortened form is a legitimate variant and
     * saying so is useful. For an identifier, "one value is a prefix of the
     * other" is a clue about the value itself, so it is never reported.
     */
    private static final Set<PrivacyNamespace> NAME_LIKE = Set.of(
            PrivacyNamespace.PERSON_NAME, PrivacyNamespace.PERSON_FIRST_NAME,
            PrivacyNamespace.PERSON_LAST_NAME, PrivacyNamespace.ORGANISATION_NAME);

    private final FieldMetadataResolver resolver;
    private final InstructionContentHeuristic instructions;

    public NamespaceCorrelationService(FieldMetadataResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.instructions = new InstructionContentHeuristic();
    }

    @Override
    public List<ConsistencyFinding> correlate(List<SourceRecord> records, PrivacyContext context) {
        // namespace -> (source alias -> raw value)
        Map<PrivacyNamespace, Map<String, String>> byNamespace = new LinkedHashMap<>();
        List<String> allSources = new ArrayList<>();
        List<ConsistencyFinding> findings = new ArrayList<>();

        for (SourceRecord source : records) {
            // Already resolved to a real name or a scope-local alias by the
            // caller: correlation compares and labels, it does not decide who
            // may see which. See SourceAliasing.
            String alias = source.sourceName();
            allSources.add(alias);
            JsonNode tree = SourceTree.of(source.record());

            for (FieldMetadata field : resolver.resolve(source.record().getClass())) {
                JsonNode value = tree.get(field.fieldName());
                if (value == null || value.isNull() || !value.isTextual()
                        || value.asText().isBlank()) {
                    continue;
                }

                // Correlation is by namespace, so an unnamespaced field has
                // nothing to be compared against.
                if (field.namespace() != PrivacyNamespace.NONE && !field.identifier()) {
                    byNamespace.computeIfAbsent(field.namespace(), n -> new LinkedHashMap<>())
                            .put(alias, value.asText());
                }

                // The injection check is not correlation and does not share its
                // filter. Instruction text lives in free-text notes and comments,
                // which are precisely the fields that carry no namespace — a
                // heuristic that only looked at namespaced fields would miss the
                // place the problem actually occurs.
                instructions.suspect(value.asText())
                        .map(reason -> new ConsistencyFinding(field.fieldName(), field.namespace(),
                                ConsistencyFinding.Kind.SUSPECTED_INSTRUCTION_CONTENT,
                                List.of(List.of(alias)), 1, reason))
                        .ifPresent(findings::add);
            }
        }

        byNamespace.forEach((namespace, values) ->
                compare(namespace, values, allSources).ifPresent(findings::add));
        return List.copyOf(findings);
    }

    private java.util.Optional<ConsistencyFinding> compare(PrivacyNamespace namespace,
                                                           Map<String, String> values,
                                                           List<String> allSources) {
        if (values.size() < 2) {
            // Only one source holds it, and others were asked: that is itself
            // worth saying, because a reader will otherwise assume all of them
            // agreed rather than that only one was able to answer.
            if (allSources.size() > 1) {
                return java.util.Optional.of(new ConsistencyFinding(namespace.name(), namespace,
                        ConsistencyFinding.Kind.MISSING_IN_SOME_SOURCES,
                        List.of(List.copyOf(values.keySet())), values.size(),
                        "only " + values.size() + " of " + allSources.size()
                                + " sources held a value"));
            }
            return java.util.Optional.empty();
        }

        Map<String, List<String>> exact = new LinkedHashMap<>();
        values.forEach((source, value) ->
                exact.computeIfAbsent(value, v -> new ArrayList<>()).add(source));
        if (exact.size() == 1) {
            return java.util.Optional.empty();
        }

        // Groups are emitted, values are not: a reader learns which systems agree
        // with each other and nothing about what they hold.
        List<List<String>> groups = exact.values().stream()
                .sorted(Comparator.comparingInt((List<String> g) -> g.size()).reversed())
                .map(List::copyOf)
                .toList();

        Set<String> loose = new java.util.LinkedHashSet<>();
        values.values().forEach(value -> loose.add(ComparisonForm.of(value)));

        if (loose.size() == 1) {
            return java.util.Optional.of(new ConsistencyFinding(namespace.name(), namespace,
                    ConsistencyFinding.Kind.FORMATTING_ONLY, groups, exact.size(),
                    "same value written differently: case, spacing or accents"));
        }

        if (NAME_LIKE.contains(namespace) && allAbbreviations(loose)) {
            return java.util.Optional.of(new ConsistencyFinding(namespace.name(), namespace,
                    ConsistencyFinding.Kind.ABBREVIATION, groups, exact.size(),
                    "one or more sources hold a shortened form of the same name"));
        }

        return java.util.Optional.of(new ConsistencyFinding(namespace.name(), namespace,
                ConsistencyFinding.Kind.INCONSISTENT, groups, exact.size(),
                "sources hold values that differ beyond formatting"));
    }

    /**
     * Every distinct value abbreviates, or is abbreviated by, the longest one.
     * Anything that does not is a real disagreement, and one odd value out of
     * three must not be softened into "an abbreviation".
     */
    private static boolean allAbbreviations(Set<String> forms) {
        String longest = forms.stream().max(Comparator.comparingInt(String::length)).orElseThrow();
        return forms.stream().allMatch(form ->
                form.equals(longest) || ComparisonForm.abbreviates(form, longest));
    }
}
