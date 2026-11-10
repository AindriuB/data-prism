package io.github.aindriub.dataprism.architecture.freeze;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A signature snapshot of the public types in the SPI packages (register section 15, "SPI interfaces and
 * annotations"): {@code core.spi}, {@code validation}, {@code security} and {@code audit}, the audit contract
 * package (its {@code verify} and {@code retention} subpackages are not part of it). For each public type, nested
 * public types included: its declaration with supertypes, then every public or protected constructor, method and
 * field, each as {@code Class#toGenericString} and friends print it with the common package prefix removed.
 * Bridge and synthetic members are skipped. Regenerate with {@code -Dfreeze.write=<directory>}.
 */
class SpiSignaturesFreezeTest {

    private static final String ROOT = "io.github.aindriub.dataprism.";
    private static final Set<String> PACKAGES = Set.of(ROOT + "core.spi", ROOT + "validation",
            ROOT + "security", ROOT + "audit");

    @Test
    void theSpiSignaturesMatchTheFrozenSnapshot() throws IOException {
        List<String> actual = new ArrayList<>();
        for (JavaClass type : MainSources.mainClasses()) {
            if (PACKAGES.contains(type.getPackageName()) && type.getEnclosingClass().isEmpty()) {
                describe(type.reflect(), actual);
            }
        }
        actual.replaceAll(line -> line.replace(ROOT, ""));
        actual.sort(null);
        FreezeLists.assertMatches("spi-signatures.txt", actual);
    }

    private static void describe(Class<?> type, List<String> out) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return;
        }
        String name = type.getName();
        out.add(name + " | type | " + type.toGenericString());
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
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (exposed(field.getModifiers()) && !field.isSynthetic()) {
                out.add(name + " | field | " + field.toGenericString());
            }
        }
        Arrays.stream(type.getDeclaredClasses()).forEach(nested -> describe(nested, out));
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
