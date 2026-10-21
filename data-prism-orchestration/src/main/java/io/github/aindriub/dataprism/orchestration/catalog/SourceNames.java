package io.github.aindriub.dataprism.orchestration.catalog;

import java.util.Collection;
import java.util.regex.Pattern;

/** The source-name grammar {@code [a-z][a-z0-9-]{0,63}}. In 0.8 it is counted, never enforced. */
final class SourceNames {

    private static final Pattern GRAMMAR = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private SourceNames() {
    }

    static boolean conforms(String name) {
        return GRAMMAR.matcher(name).matches();
    }

    static int nonconformingCount(Collection<String> names) {
        return (int) names.stream().filter(name -> !conforms(name)).count();
    }
}
