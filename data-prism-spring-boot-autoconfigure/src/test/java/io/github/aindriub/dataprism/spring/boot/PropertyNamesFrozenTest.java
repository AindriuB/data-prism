package io.github.aindriub.dataprism.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Freezes the {@code dataprism.*} vocabulary: every relaxed property path with its Java type and
 * default, walked from {@link DataPrismProperties}. Types of this module's own beans are rendered
 * as {@code bean} so that moving or renaming a property class does not disturb the list; only
 * names, shapes and defaults do. Regenerate deliberately with
 * {@code -Dfrozen.write=<path>}; the checked-in list must not change in a refactor.
 */
class PropertyNamesFrozenTest {
    private static final String RESOURCE = "frozen-property-names.txt";
    private static final String OWN_PACKAGE = "io.github.aindriub.dataprism.spring.boot";

    @Test
    void propertyPathsTypesAndDefaultsMatchTheCheckedInList() throws IOException {
        List<String> actual = new ArrayList<>();
        walk(new DataPrismProperties(), "dataprism", actual);
        String write = System.getProperty("frozen.write");
        if (write != null) {
            Files.write(Path.of(write), actual, StandardCharsets.UTF_8);
        }
        List<String> expected;
        try (InputStream in = PropertyNamesFrozenTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            expected = Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private static void walk(Object bean, String prefix, List<String> out) {
        List<Method> getters = new ArrayList<>();
        for (Method m : bean.getClass().getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || m.getParameterCount() != 0
                    || m.getReturnType() == void.class || m.isSynthetic()) {
                continue;
            }
            String n = m.getName();
            if ((n.startsWith("get") && n.length() > 3) || (n.startsWith("is") && n.length() > 2)) {
                getters.add(m);
            }
        }
        getters.sort(Comparator.comparing(g -> kebab(g.getName().replaceFirst("^(get|is)", ""))));
        for (Method g : getters) {
            String raw = g.getName().startsWith("is") ? g.getName().substring(2) : g.getName().substring(3);
            String path = prefix + "." + kebab(raw);
            boolean settable = Arrays.stream(bean.getClass().getDeclaredMethods())
                    .anyMatch(s -> s.getName().equals("set" + raw) && s.getParameterCount() == 1);
            Object value = invoke(g, bean);
            Type type = g.getGenericReturnType();
            if (isOwnBean(g.getReturnType()) && value != null) {
                out.add(path + " | bean | " + (settable ? "rw" : "ro"));
                walk(value, path, out);
            } else if (value instanceof Map<?, ?> && type instanceof ParameterizedType pt
                    && pt.getActualTypeArguments()[1] instanceof Class<?> vc && isOwnBean(vc)) {
                out.add(path + " | " + typeName(type) + " | " + (settable ? "rw" : "ro") + " | " + value);
                try {
                    var ctor = vc.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    walk(ctor.newInstance(), path + ".*", out);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            } else {
                out.add(path + " | " + typeName(type) + " | " + (settable ? "rw" : "ro") + " | "
                        + (value instanceof Collection<?> || value instanceof Map<?, ?> ? value : String.valueOf(value)));
            }
        }
    }

    private static Object invoke(Method m, Object target) {
        try {
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isOwnBean(Class<?> c) {
        return c.getName().startsWith(OWN_PACKAGE) && !c.isEnum();
    }

    private static String typeName(Type t) {
        if (t instanceof Class<?> c) {
            if (c.isEnum()) {
                return c.getName().startsWith(OWN_PACKAGE)
                        ? "enum" + Arrays.toString(c.getEnumConstants()) : c.getName();
            }
            return isOwnBean(c) ? "bean" : c.getName();
        }
        if (t instanceof ParameterizedType p) {
            StringBuilder sb = new StringBuilder(((Class<?>) p.getRawType()).getName()).append('<');
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) {
                sb.append(i == 0 ? "" : ",").append(typeName(args[i]));
            }
            return sb.append('>').toString();
        }
        return t.getTypeName();
    }

    private static String kebab(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
            }
            sb.append(String.valueOf(c).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
