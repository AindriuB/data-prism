package io.github.aindriub.dataprism.architecture.freeze;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signature snapshot of the public types in the SPI packages (register section 15, "SPI interfaces and
 * annotations"): {@code core.spi}, {@code validation}, {@code security} and {@code audit}, the audit contract
 * package (its {@code verify} and {@code retention} subpackages are not part of it), plus every interface and
 * annotation in register section 7.1, read from the register (task 260 amendment 2026-11-10). Annotations also
 * record each attribute's default and their {@code @Retention} and {@code @Target}. For each public type, nested
 * public types included: its declaration with supertypes, then every public or protected constructor, method and
 * field, each as {@code Class#toGenericString} and friends print it with the common package prefix removed.
 * Bridge and synthetic members are skipped. Regenerate with {@code -Dfreeze.write=<directory>}.
 */
class SpiSignaturesFreezeTest {

    private static final String ROOT = "io.github.aindriub.dataprism.";
    private static final String REGISTER = "docs/plan/design/contract-register.md";
    private static final Pattern ROW = Pattern.compile("^\\| `@?(\\w+)` \\| (?:interface|annotation) \\|");
    private static final Set<String> PACKAGES = Set.of(ROOT + "core.spi", ROOT + "validation",
            ROOT + "security", ROOT + "audit");

    @Test
    void theSpiSignaturesMatchTheFrozenSnapshot() throws IOException, ClassNotFoundException {
        List<String> actual = new ArrayList<>();
        Set<String> registered = registeredTypes();
        Set<String> found = new TreeSet<>();
        ClassLoader loader = moduleClasses();
        for (JavaClass type : MainSources.mainClasses()) {
            if (type.getEnclosingClass().isPresent()) {
                continue;
            }
            boolean listed = registered.contains(type.getSimpleName());
            if (listed) {
                found.add(type.getSimpleName());
            }
            if (listed || PACKAGES.contains(type.getPackageName())) {
                describe(Class.forName(type.getName(), false, loader), actual);
            }
        }
        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(found);
        assertThat(missing).as("register 7.1 types with no class in src/main").isEmpty();
        actual.replaceAll(line -> line.replace(ROOT, ""));
        actual.sort(null);
        FreezeLists.assertMatches("spi-signatures.txt", actual);
    }

    /**
     * A loader over every module's {@code target/classes}, in front of the test classpath's own copy. Under
     * {@code verify} a module such as the server is repackaged, its classes sit under {@code BOOT-INF/classes}
     * in the jar and the test classpath cannot load them by name; the directories hold them plainly.
     */
    private static ClassLoader moduleClasses() throws IOException {
        Path root = MainSources.repositoryRoot();
        List<URL> urls = new ArrayList<>();
        for (String module : MainSources.modules(root)) {
            Path classes = root.resolve(module).resolve("target").resolve("classes");
            if (Files.isDirectory(classes)) {
                urls.add(classes.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), SpiSignaturesFreezeTest.class.getClassLoader());
    }

    /**
     * The simple names in the register's section 7.1 table, interfaces and annotations (an annotation is listed
     * as {@code `@Name`}). The register is read, so a type added to 7.1 is pinned without editing this test.
     */
    private static Set<String> registeredTypes() throws IOException {
        List<String> lines = Files.readAllLines(MainSources.repositoryRoot().resolve(REGISTER));
        Set<String> names = new TreeSet<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("### 7.1 ")) {
                inSection = true;
            } else if (inSection && line.startsWith("#")) {
                break;
            } else if (inSection) {
                Matcher row = ROW.matcher(line);
                if (row.find()) {
                    names.add(row.group(1));
                }
            }
        }
        assertThat(names).as("types read from " + REGISTER + " section 7.1").hasSizeGreaterThanOrEqualTo(30);
        return names;
    }

    private static void describe(Class<?> type, List<String> out) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return;
        }
        String name = type.getName();
        out.add(name + " | type | " + typeLine(type));
        if (type.getGenericSuperclass() != null && type.getSuperclass() != Object.class && !type.isEnum()
                && !type.isRecord()) {
            out.add(name + " | extends | " + type.getGenericSuperclass().getTypeName());
        }
        for (Type implemented : type.getGenericInterfaces()) {
            out.add(name + " | implements | " + implemented.getTypeName());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (exposed(constructor.getModifiers()) && !constructor.isSynthetic()) {
                out.add(name + " | constructor | " + constructor.toGenericString());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (exposed(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                out.add(name + " | method | " + method.toGenericString());
                if (type.isAnnotation()) {
                    out.add(name + " | attribute | " + method.getName() + " default "
                            + defaultOf(method.getDefaultValue()));
                }
            }
        }
        if (type.isAnnotation()) {
            for (Annotation meta : type.getDeclaredAnnotations()) {
                if (meta instanceof Retention || meta instanceof Target) {
                    out.add(name + " | meta | " + meta);
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (exposed(field.getModifiers()) && !field.isSynthetic()) {
                out.add(name + " | field | " + field.toGenericString());
            }
        }
        Arrays.stream(type.getDeclaredClasses()).forEach(nested -> describe(nested, out));
    }

    private static final Pattern TYPE_KEYWORD = Pattern.compile("@interface|\\binterface\\b|\\bclass\\b|\\benum\\b|\\brecord\\b");

    /**
     * {@code toGenericString} with {@code sealed} and {@code non-sealed} stated the same way on every JDK: JDK 25
     * prints {@code sealed} and JDK 21 does not, so both are removed and put back, just before the type keyword, from
     * {@link Class#isSealed()} and the type's sealed supertypes.
     */
    private static String typeLine(Class<?> type) {
        String line = type.toGenericString().replace("non-sealed ", "").replace("sealed ", "");
        String marker = type.isSealed() ? "sealed " : nonSealed(type) ? "non-sealed " : "";
        Matcher keyword = TYPE_KEYWORD.matcher(line);
        return marker.isEmpty() || !keyword.find() ? line
                : line.substring(0, keyword.start()) + marker + line.substring(keyword.start());
    }

    private static boolean nonSealed(Class<?> type) {
        if (type.isSealed() || Modifier.isFinal(type.getModifiers()) || type.isEnum() || type.isRecord()) {
            return false;
        }
        if (type.getSuperclass() != null && type.getSuperclass().isSealed()) {
            return true;
        }
        return Arrays.stream(type.getInterfaces()).anyMatch(Class::isSealed);
    }

    private static String defaultOf(Object value) {
        if (value == null) {
            return "(none)";
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> items = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                items.add(String.valueOf(Array.get(value, i)));
            }
            return items.toString();
        }
        return value instanceof String ? "\"" + value + "\"" : value.toString();
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
