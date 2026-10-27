package io.github.aindriub.dataprism.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-instance deployment files pin a published image, and that image must not be older than this build:
 * an older server ignores the settings the files carry (the cluster key, the name key, the security policy), so
 * the deployment would run without them. A moving tag ({@code latest}) is refused for the same reason.
 */
class DeploymentImageTagTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern IMAGE = Pattern.compile("data-prism-[a-z-]+:([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    @Test
    @DisplayName("every image in docker/multi-instance is a release tag not older than the project version")
    void noImageIsOlderThanTheProjectVersion() throws Exception {
        int[] project = release(projectVersion());
        List<String> problems = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.list(ROOT.resolve("docker/multi-instance"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = IMAGE.matcher(line);
                    while (m.find()) {
                        found++;
                        String tag = m.group(1);
                        Matcher r = RELEASE.matcher(tag);
                        if (!r.matches() || compare(release(tag), project) < 0) {
                            problems.add(file.getFileName() + ": " + m.group() + " is not a release of "
                                    + projectVersion() + " or later");
                        }
                    }
                }
            }
        }
        assertThat(found).as("image references found in docker/multi-instance").isPositive();
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("the version comparison orders releases and rejects a moving tag")
    void theComparisonOrdersReleases() {
        assertThat(compare(release("0.7.0"), release("0.8.0-SNAPSHOT"))).isNegative();
        assertThat(compare(release("0.8.0"), release("0.8.0-SNAPSHOT"))).isZero();
        assertThat(compare(release("0.10.0"), release("0.9.2"))).isPositive();
        assertThat(RELEASE.matcher("latest").matches()).isFalse();
    }

    /** The reactor's own version, from the root pom, without its qualifier. */
    private static String projectVersion() throws Exception {
        var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Element project = builder.parse(ROOT.resolve("pom.xml").toFile()).getDocumentElement();
        for (Node n = project.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("version")) {
                return e.getTextContent().strip();
            }
        }
        throw new IllegalStateException("the root pom has no version");
    }

    private static int[] release(String version) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) {
            return new int[] {Integer.MIN_VALUE, 0, 0};
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }
}
