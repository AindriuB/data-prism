package io.github.aindriub.dataprism.architecture.freeze;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Freezes the operator HTTP surface (register section 3.4): every request mapping of every controller in
 * {@code data-prism-server}'s {@code operator} package, as {@code <METHOD> <path>}. The class-level path, if any, is
 * prefixed; a mapping with no method is {@code ANY}. Placeholders such as the error path stay as written.
 * Regenerate with {@code -Dfreeze.write=<directory>}.
 */
class OperatorEndpointsFreezeTest {

    private static final String PACKAGE = "io.github.aindriub.dataprism.server.operator";

    @Test
    void theOperatorEndpointsMatchTheFrozenList() throws IOException {
        List<String> actual = new ArrayList<>();
        for (JavaClass type : MainSources.mainClasses()) {
            if (!type.getPackageName().equals(PACKAGE)) {
                continue;
            }
            Class<?> controller = type.reflect();
            if (!AnnotatedElementUtils.hasAnnotation(controller, Controller.class)) {
                continue;
            }
            RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            List<String> prefixes = onClass == null || paths(onClass).isEmpty() ? List.of("") : paths(onClass);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                List<String> methods = mapping.method().length == 0 ? List.of("ANY")
                        : Arrays.stream(mapping.method()).map(RequestMethod::name).toList();
                List<String> paths = paths(mapping).isEmpty() ? List.of("") : paths(mapping);
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        for (String httpMethod : methods) {
                            actual.add(httpMethod + " " + prefix + path);
                        }
                    }
                }
            }
        }
        actual.sort(null);
        FreezeLists.assertMatches("operator-endpoints.txt", actual);
    }

    private static List<String> paths(RequestMapping mapping) {
        return mapping.path().length > 0 ? Arrays.asList(mapping.path()) : Arrays.asList(mapping.value());
    }
}
