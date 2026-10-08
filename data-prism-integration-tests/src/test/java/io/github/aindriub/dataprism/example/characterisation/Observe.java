package io.github.aindriub.dataprism.example.characterisation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns "what a YAML reader did with this input" into one comparable line: the rendered result,
 * or the refusal as {@code refused <exception type> "<message prefix>" caused by <cause type>}.
 * Only simple class names are recorded, so the line does not depend on a package.
 */
final class Observe {

    /** The scalar spellings every reader is probed with. */
    static final List<String> BOOLEAN_SPELLINGS = List.of("yes", "no", "on", "off", "y", "n", "True", "FALSE");

    static final List<String> OCTAL_SPELLINGS = List.of("010", "0o10", "0777");

    private static final int MESSAGE_PREFIX = 70;

    private Observe() {
    }

    static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static String outcome(Supplier<String> parse) {
        try {
            return "ok " + parse.get();
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage()).replace("\n", " ");
            String prefix = message.length() > MESSAGE_PREFIX ? message.substring(0, MESSAGE_PREFIX) : message;
            String cause = e.getCause() == null ? "" : " caused by " + e.getCause().getClass().getSimpleName();
            return "refused " + e.getClass().getSimpleName() + " \"" + prefix + "\"" + cause;
        }
    }

    /** One line per spelling: {@code spelling => outcome}. */
    static String table(List<String> spellings, Function<String, String> yamlFor, Function<InputStream, String> render) {
        StringBuilder out = new StringBuilder();
        for (String spelling : spellings) {
            out.append(spelling).append(" => ")
                    .append(outcome(() -> render.apply(yaml(yamlFor.apply(spelling))))).append('\n');
        }
        return out.toString();
    }
}
