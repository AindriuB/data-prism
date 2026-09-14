package io.github.aindriub.dataprism.architecture;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the module graph from the root {@code pom.xml} rather than hand
 * copying it, and resolves each module's compiled main classes on disk.
 *
 * <p>{@code target/classes} rather than the module's Maven artifact, because
 * one module on this graph — {@code data-prism-server} — is repackaged into
 * a Spring Boot executable jar during {@code package}. After that runs, its
 * application classes live under {@code BOOT-INF/classes/} inside that jar,
 * a location an ordinary classpath or dependency-jar scan does not look
 * inside. {@code target/classes} is unaffected by what a module's packaging
 * step does afterwards, is produced identically whether or not that module
 * repackages, and is exactly what {@code data-prism-architecture}'s own
 * dependency on each module already causes the reactor to build before this
 * module's tests run.
 */
final class ModuleGraph {

    private static final String INTERNAL_GROUP_ID = "io.github.aindriub";

    private ModuleGraph() {
    }

    /**
     * Walks upward from the working directory Surefire starts a module's
     * tests in until it finds the aggregator {@code pom.xml} — the one with
     * no {@code <parent>} — rather than assuming a fixed number of parent
     * directories.
     */
    static Path repositoryRoot() throws IOException {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("pom.xml");
            if (Files.isRegularFile(candidate) && isAggregatorRoot(candidate)) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IOException("could not locate the repository's root pom.xml above "
                + System.getProperty("user.dir"));
    }

    static List<String> declaredModules(Path pomXml) throws IOException {
        Element project = rootElementOf(pomXml);
        NodeList moduleNodes = project.getElementsByTagName("module");
        List<String> modules = new ArrayList<>();
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            modules.add(moduleNodes.item(i).getTextContent().trim());
        }
        return modules;
    }

    /** The {@code io.github.aindriub} artifact ids this module's own pom depends on. */
    static List<String> declaredDependencyArtifactIds(Path pomXml) throws IOException {
        Element project = rootElementOf(pomXml);
        Element dependencies = directChild(project, "dependencies");
        List<String> artifactIds = new ArrayList<>();
        if (dependencies == null) {
            return artifactIds;
        }
        NodeList children = dependencies.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element dependency && "dependency".equals(dependency.getTagName())
                    && INTERNAL_GROUP_ID.equals(textOfDirectChild(dependency, "groupId"))) {
                artifactIds.add(textOfDirectChild(dependency, "artifactId"));
            }
        }
        return artifactIds;
    }

    /**
     * A module with no {@code src/main/java} of its own — this scanning
     * module included — has no main class that could ever appear in an
     * imported set, so it cannot be required to contribute one.
     */
    static boolean hasMainSource(Path moduleDirectory) {
        Path mainJava = moduleDirectory.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(mainJava)) {
            return false;
        }
        try (var paths = Files.walk(mainJava)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code target/classes} for every given module that has main source of its own. */
    static List<Path> mainClassesDirectories(Path repositoryRoot, List<String> modules) {
        List<Path> directories = new ArrayList<>();
        for (String module : modules) {
            Path moduleDirectory = repositoryRoot.resolve(module);
            if (hasMainSource(moduleDirectory)) {
                directories.add(moduleDirectory.resolve("target").resolve("classes"));
            }
        }
        return directories;
    }

    private static boolean isAggregatorRoot(Path pomXml) {
        try {
            Element project = rootElementOf(pomXml);
            boolean hasParent = directChild(project, "parent") != null;
            String packaging = textOfDirectChild(project, "packaging");
            String artifactId = textOfDirectChild(project, "artifactId");
            return !hasParent && "pom".equals(packaging) && "data-prism".equals(artifactId);
        } catch (IOException e) {
            return false;
        }
    }

    private static Element rootElementOf(Path pomXml) throws IOException {
        try {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(pomXml.toFile());
            return document.getDocumentElement();
        } catch (Exception e) {
            throw new IOException("could not parse " + pomXml, e);
        }
    }

    private static Element directChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String textOfDirectChild(Element parent, String tagName) {
        Element child = directChild(parent, tagName);
        return child == null ? null : child.getTextContent().trim();
    }
}
