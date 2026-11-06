package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The neutral text for a source that declared none. Built only from declared entity types, declared
 * namespaces and fixed words, never from the source's name, class or location. Both lists are in
 * ascending order of their text, whatever order they were declared in.
 */
final class GeneratedDescriptions {

    private static final int MAX_LENGTH = 240;
    static final String UNDECLARED = "A data source.";

    private GeneratedDescriptions() {
    }

    static String generate(SourceDescription description) {
        List<String> types = description.entityTypes().stream().sorted().toList();
        List<String> keys = description.inputKeys().stream().map(KeyNamespace::value).sorted().toList();
        if (types.isEmpty()) {
            return UNDECLARED;
        }
        String tail = keys.isEmpty() ? "." : "; accepts " + String.join(", ", keys) + " keys.";
        // Never longer than the served cap, so a source with many types is served, not refused: list the
        // types that fit in ascending order, then say how many more there are.
        for (int shown = types.size(); shown >= 1; shown--) {
            String more = shown == types.size() ? "" : ", and " + (types.size() - shown) + " more";
            String text = "Holds " + String.join(", ", types.subList(0, shown)) + more + " records" + tail;
            if (text.codePointCount(0, text.length()) <= MAX_LENGTH) {
                return text;
            }
        }
        return "Holds " + types.size() + " entity types.";
    }
}
