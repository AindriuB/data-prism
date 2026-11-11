package io.github.aindriub.dataprism.architecture.freeze;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads and compares the checked-in freeze lists under {@code src/test/resources/freeze}.
 *
 * <p>Every list can be regenerated with {@code -Dfreeze.write=<directory>}: the test writes the list it
 * computed to {@code <directory>/<name>} before comparing, so a deliberate change is one run and one
 * copy. Lines starting with {@code #} are comments and are not compared. Regenerating on an unchanged tree
 * gives the same file apart from the comments, which {@link #write} copies from the checked-in list.
 */
final class FreezeLists {

    private FreezeLists() {
    }

    /** The non-comment lines of the checked-in list {@code freeze/<name>}. */
    static List<String> expected(String name) throws IOException {
        return lines(name, false);
    }

    private static List<String> lines(String name, boolean withComments) throws IOException {
        try (InputStream in = FreezeLists.class.getResourceAsStream("/freeze/" + name)) {
            assertThat(in).as("freeze/" + name).isNotNull();
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                if (line.isEmpty() || (!withComments && line.startsWith("#"))) {
                    continue;
                }
                out.add(line);
            }
            return out;
        }
    }

    /** Writes {@code actual} to {@code -Dfreeze.write}'s directory when that property is set. */
    static void write(String name, List<String> actual) throws IOException {
        String directory = System.getProperty("freeze.write");
        if (directory == null) {
            return;
        }
        List<String> out = new ArrayList<>(lines(name, true).stream().filter(l -> l.startsWith("#")).toList());
        out.addAll(actual);
        Path target = Path.of(directory);
        Files.createDirectories(target);
        Files.write(target.resolve(name), (String.join("\n", out) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Writes when asked, then compares with the checked-in list, in order. */
    static void assertMatches(String name, List<String> actual) throws IOException {
        write(name, actual);
        assertThat(actual)
                .as("freeze/" + name + " (regenerate with -Dfreeze.write=<directory>)")
                .containsExactlyElementsOf(expected(name));
    }
}
