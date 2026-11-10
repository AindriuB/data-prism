package io.github.aindriub.dataprism.architecture.freeze;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads every module's {@code src/main} text, for the freeze tests that scan source rather than classes. */
final class MainSources {

    /** A file of {@code src/main}: where it is and what it says. */
    record Source(String module, Path relative, String text) {
        boolean isJava() {
            return relative.toString().endsWith(".java");
        }
    }

    private static final Set<String> RESOURCE_TEXT = Set.of("yaml", "yml", "json", "properties", "txt", "md",
            "imports", "factories", "xml", "csv", "html");

    private MainSources() {
    }

    /**
     * The aggregator pom is the first {@code pom.xml} above the working directory that is named
     * {@code data-prism} and has no parent, as {@code ModuleGraph} finds it; it is read here as text so this
     * package needs nothing from that class.
     */
    static Path repositoryRoot() throws IOException {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path pom = directory.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                String text = Files.readString(pom);
                if (!text.contains("<parent>") && text.contains("<artifactId>data-prism</artifactId>")
                        && text.contains("<module>")) {
                    return directory;
                }
            }
            directory = directory.getParent();
        }
        throw new IOException("could not locate the repository's root pom.xml above " + System.getProperty("user.dir"));
    }

    /** The {@code <module>} entries of the root pom, in order. */
    static List<String> modules(Path root) throws IOException {
        Matcher matcher = Pattern.compile("<module>([^<]+)</module>").matcher(Files.readString(root.resolve("pom.xml")));
        List<String> modules = new ArrayList<>();
        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }
        return modules;
    }

    static List<Source> all() throws IOException {
        Path root = repositoryRoot();
        List<Source> out = new ArrayList<>();
        for (String module : modules(root)) {
            Path main = root.resolve(module).resolve("src").resolve("main");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                    String name = file.getFileName().toString();
                    String extension = name.substring(name.lastIndexOf('.') + 1);
                    boolean java = name.endsWith(".java");
                    boolean resource = main.relativize(file).startsWith("resources")
                            && RESOURCE_TEXT.contains(extension);
                    if (java || resource) {
                        out.add(new Source(module, main.relativize(file),
                                new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
                    }
                }
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        return out;
    }
}
