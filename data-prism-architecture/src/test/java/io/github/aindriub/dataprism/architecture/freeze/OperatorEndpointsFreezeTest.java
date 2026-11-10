package io.github.aindriub.dataprism.architecture.freeze;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Freezes the operator HTTP surface (register section 3.4): every request mapping of every controller in
 * {@code data-prism-server}'s {@code operator} package, as {@code <METHOD> <path>}. It reads the source, not the
 * classes, because the server module is repackaged as a Spring Boot jar by {@code verify} and its classes are then
 * not on a plain classpath. The class-level path, if any, prefixes each method's; a mapping with no method is
 * {@code ANY}; placeholders such as the error path stay as written. Regenerate with
 * {@code -Dfreeze.write=<directory>}.
 */
class OperatorEndpointsFreezeTest {

    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\\(([^)]*)\\))?");
    private static final Pattern FIRST_STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern EXPLICIT_METHOD = Pattern.compile("RequestMethod\\.([A-Z]+)");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass\\s+\\w+");

    @Test
    void theOperatorEndpointsMatchTheFrozenList() throws IOException {
        List<String> actual = new ArrayList<>();
        for (MainSources.Source source : MainSources.all()) {
            String path = source.relative().toString().replace('\\', '/');
            if (!source.module().equals("data-prism-server") || !source.isJava()
                    || !path.contains("/server/operator/")
                    || !(source.text().contains("@RestController") || source.text().contains("@Controller"))) {
                continue;
            }
            String text = source.text();
            Matcher declaration = CLASS_DECLARATION.matcher(text);
            int classStart = declaration.find() ? declaration.start() : text.length();
            String prefix = "";
            Matcher matcher = MAPPING.matcher(text);
            while (matcher.find()) {
                String arguments = matcher.group(2) == null ? "" : matcher.group(2);
                Matcher string = FIRST_STRING.matcher(arguments);
                String mapped = string.find() ? string.group(1) : "";
                if (matcher.start() < classStart) {
                    prefix = mapped;
                    continue;
                }
                String method = matcher.group(1).toUpperCase();
                if (method.equals("REQUEST")) {
                    Matcher explicit = EXPLICIT_METHOD.matcher(arguments);
                    method = explicit.find() ? explicit.group(1) : "ANY";
                }
                actual.add(method + " " + prefix + mapped);
            }
        }
        actual.sort(null);
        FreezeLists.assertMatches("operator-endpoints.txt", actual);
    }
}
