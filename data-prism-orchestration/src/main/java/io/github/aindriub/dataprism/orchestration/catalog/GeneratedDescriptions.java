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

    static final String UNDECLARED = "A data source.";

    private GeneratedDescriptions() {
    }

    static String generate(SourceDescription description) {
        List<String> types = description.entityTypes().stream().sorted().toList();
        List<String> keys = description.inputKeys().stream().map(KeyNamespace::value).sorted().toList();
        if (types.isEmpty()) {
            return UNDECLARED;
        }
        String holds = "Holds " + types.stream().collect(Collectors.joining(", ")) + " records";
        if (keys.isEmpty()) {
            return holds + ".";
        }
        return holds + "; accepts " + String.join(", ", keys) + " keys.";
    }
}
