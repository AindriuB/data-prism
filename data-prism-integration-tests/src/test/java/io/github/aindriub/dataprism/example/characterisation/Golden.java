package io.github.aindriub.dataprism.example.characterisation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-for-byte comparison against a checked-in golden file under
 * {@code src/test/resources/characterisation/}.
 *
 * <p>The golden files were produced by running the Jackson 2 code, never typed by hand. To see
 * what the current code produces without touching a golden, run with
 * {@code -Dcharacterisation.record=<directory>}: each actual output is then also written there
 * under the golden's name. The compared bytes are always UTF-8.
 */
final class Golden {

    private static final String RECORD_DIR_PROPERTY = "characterisation.record";

    private Golden() {
    }

    static void assertMatches(String goldenName, String actual) {
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        record(goldenName, actualBytes);
        byte[] expected = read(goldenName);
        assertThat(actualBytes)
                .as("bytes of %s, compared as UTF-8 text: expected [%s] but was [%s]",
                        goldenName, new String(expected, StandardCharsets.UTF_8), actual)
                .isEqualTo(expected);
    }

    private static byte[] read(String goldenName) {
        try (InputStream in = Golden.class.getResourceAsStream("/characterisation/" + goldenName)) {
            assertThat(in).as("golden file characterisation/" + goldenName).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void record(String goldenName, byte[] actual) {
        String dir = System.getProperty(RECORD_DIR_PROPERTY);
        if (dir == null) {
            return;
        }
        try {
            Path target = Path.of(dir).resolve(goldenName);
            Files.createDirectories(target.getParent());
            Files.write(target, actual);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
