package io.github.aindriub.dataprism.core.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLReadFeature;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hand-written, fail-closed YAML parsing shared by every configuration reader
 * ({@code SecurityPolicy}, {@code PrivacyProfiles}, {@code ModelDescriptors},
 * {@code RestSources}, {@code ConfiguredJsonSources}, {@code VocabularyRegistry}).
 *
 * <p>The readers parse a generic map rather than data-bound classes, so that an
 * unknown classification, action or other enum constant is a startup failure
 * naming the offending key rather than a null that binding would have produced
 * silently. This class carries the pieces of that contract that must not differ
 * between readers, each refused with a stable {@code UPPER_SNAKE_CODE: } prefix
 * (see {@code core.refusal.RefusalCodes}) in an {@link IllegalArgumentException}:
 *
 * <ul>
 *   <li>{@link #DUPLICATE_KEY} - a mapping with the same key twice, at any depth;</li>
 *   <li>{@link #UNKNOWN_KEY} - a fixed-schema mapping with a key outside its allowed set;</li>
 *   <li>{@link #TRAILING_CONTENT} - a second YAML document, or anything after the first;</li>
 *   <li>{@link #NON_STRING_SCALAR} - a string-typed field that is not a string;</li>
 *   <li>{@link #INVALID_BOOLEAN} - a boolean-typed field that is not exactly {@code true} or {@code false};</li>
 *   <li>{@link #LEADING_ZERO_NUMBER} - a numeric-typed field written with a leading zero.</li>
 * </ul>
 *
 * <p>A message names the document kind, the path and the key (truncated to
 * {@value #MAX_KEY_CHARS} characters), and never a value.
 */
public final class StrictYaml {

    public static final String DUPLICATE_KEY = "DUPLICATE_CONFIG_KEY";
    public static final String UNKNOWN_KEY = "UNKNOWN_CONFIG_KEY";
    public static final String TRAILING_CONTENT = "TRAILING_CONFIG_CONTENT";
    public static final String NON_STRING_SCALAR = "NON_STRING_CONFIG_SCALAR";
    public static final String INVALID_BOOLEAN = "INVALID_CONFIG_BOOLEAN";
    public static final String LEADING_ZERO_NUMBER = "LEADING_ZERO_CONFIG_NUMBER";

    /** A key longer than this is cut when it is named in a message. */
    public static final int MAX_KEY_CHARS = 64;

    // A plain empty value ("key:") is null, as it was under YAMLMapper's defaults; a quoted '' stays a string.
    private static final YAMLFactory FACTORY = YAMLFactory.builder()
            .enable(YAMLReadFeature.EMPTY_STRING_AS_NULL)
            .build();
    private static final Pattern LEADING_ZERO = Pattern.compile("[+-]?0[0-9].*", Pattern.DOTALL);

    private StrictYaml() {
    }

    /**
     * Parses one YAML document whose root is a mapping, refusing a duplicate key at
     * any depth and any second document or content after the first.
     *
     * <p>A bare {@code ---} after the document counts as a second (empty) document
     * and is refused; a {@code ...} document-end marker does not.
     *
     * @param kind what the document is, for messages ("security policy")
     * @return the mapping (nested mappings are {@code Map<String, Object>} in file
     *         order, sequences are {@code List<Object>}, scalars are
     *         {@code String}, {@code Boolean}, {@code Number} or {@code null}), or
     *         {@code null} if the stream holds no document at all
     * @throws UncheckedIOException if the text is not parseable YAML, or its root is
     *         not a mapping ("{@code <kind> could not be read}")
     */
    public static Map<String, Object> readMapping(InputStream in, String kind) {
        try (JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), in)) {
            JsonToken first = p.nextToken();
            if (first == null || first == JsonToken.VALUE_NULL) {
                return null;
            }
            if (first != JsonToken.START_OBJECT) {
                throw new UncheckedIOException(kind + " could not be read",
                        new IOException("the document root is not a mapping"));
            }
            Map<String, Object> root = mapping(p, kind, "");
            boolean more;
            try {
                more = p.nextToken() != null;
            } catch (JacksonException e) {
                more = true;
            }
            if (more) {
                throw new IllegalArgumentException(TRAILING_CONTENT + ": " + kind
                        + " has a second YAML document or content after the first");
            }
            return root;
        } catch (JacksonException e) {
            throw new UncheckedIOException(kind + " could not be read", new IOException(e.getMessage(), e));
        }
    }

    /** {@link #readMapping(InputStream, String)} over bytes already read. */
    public static Map<String, Object> readMapping(byte[] yaml, String kind) {
        return readMapping(new ByteArrayInputStream(yaml), kind);
    }

    private static Map<String, Object> mapping(JsonParser p, String kind, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (JsonToken t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
            String key = p.currentName();
            String child = path.isEmpty() ? key : path + "." + key;
            if (out.containsKey(key)) {
                throw new IllegalArgumentException(DUPLICATE_KEY + ": " + kind + " has a duplicate key '"
                        + shown(key) + "'" + inPath(path));
            }
            out.put(key, value(p, p.nextToken(), kind, child));
        }
        return out;
    }

    private static List<Object> sequence(JsonParser p, String kind, String path) {
        List<Object> out = new ArrayList<>();
        for (JsonToken t = p.nextToken(); t != JsonToken.END_ARRAY; t = p.nextToken()) {
            out.add(value(p, t, kind, path + "[" + out.size() + "]"));
        }
        return out;
    }

    private static Object value(JsonParser p, JsonToken t, String kind, String path) {
        return switch (t) {
            case START_OBJECT -> mapping(p, kind, shownPath(path));
            case START_ARRAY -> sequence(p, kind, shownPath(path));
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> p.getNumberValue();
            default -> p.getString();
        };
    }

    private static String shownPath(String path) {
        return path.length() <= MAX_KEY_CHARS * 4 ? path : path.substring(0, MAX_KEY_CHARS * 4);
    }

    private static String inPath(String path) {
        return path.isEmpty() ? "" : " in " + shownPath(path);
    }

    /**
     * Refuses a key of {@code present} that is not in {@code allowed}, with
     * {@code UNKNOWN_CONFIG_KEY: <where> has an unknown key '<key>'}.
     *
     * @param where what the mapping is, in words and path ("security policy", "profile p")
     */
    public static void requireOnlyKeys(Set<?> present, Set<String> allowed, String where) {
        for (Object key : present) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(UNKNOWN_KEY + ": " + where + " has an unknown key '"
                        + shown(key) + "'");
            }
        }
    }

    /**
     * A key as it may appear in a message: control characters replaced, cut to
     * {@value #MAX_KEY_CHARS} characters. A key is schema, not data, but someone
     * can paste a secret where one belongs.
     */
    public static String shown(Object key) {
        String text = String.valueOf(key);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < text.length() && count < MAX_KEY_CHARS; ) {
            int cp = text.codePointAt(i);
            out.appendCodePoint(Character.isISOControl(cp) ? '?' : cp);
            i += Character.charCount(cp);
            count++;
        }
        return out.toString();
    }

    /**
     * The string at {@code key}, or {@code null} if the key is absent. A scalar the
     * parser resolved to a boolean, a number or null is refused with
     * {@code NON_STRING_CONFIG_SCALAR}, so that it has to be quoted.
     *
     * @param where the path of the mapping, with its document kind
     */
    public static String optionalString(Map<String, Object> body, String key, String where) {
        return body.containsKey(key) ? text(body.get(key), where + "." + shown(key)) : null;
    }

    /** {@code raw} if it is a string; otherwise {@code NON_STRING_CONFIG_SCALAR: <where> must be a quoted string}. */
    public static String text(Object raw, String where) {
        if (raw instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(NON_STRING_SCALAR + ": " + where + " must be a quoted string");
    }

    /**
     * The boolean at {@code key}, or {@code null} if the key is absent. Only
     * {@code true} and {@code false} are accepted (YAML 1.2 reads {@code yes} and
     * {@code on} as text, and 0.5.x read them as booleans); anything else is
     * {@code INVALID_CONFIG_BOOLEAN}.
     */
    public static Boolean optionalBoolean(Map<String, Object> body, String key, String where) {
        if (!body.containsKey(key)) {
            return null;
        }
        Object raw = body.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(INVALID_BOOLEAN + ": " + where + "." + shown(key)
                + " must be exactly true or false");
    }

    /**
     * {@code raw} as a decimal. A number written with a leading zero ({@code 010},
     * {@code 0777}, {@code -01}) is {@code LEADING_ZERO_CONFIG_NUMBER}: the parser
     * cannot tell it from a quoted string, and 0.5.x read it as octal. A plain
     * {@code 0} and {@code 0.5} are fine.
     *
     * @param where the full location of the value, with its document kind
     * @throws NumberFormatException if it is not a number at all
     */
    public static BigDecimal decimal(Object raw, String where) {
        String text = numberText(raw, where);
        return new BigDecimal(text.trim());
    }

    /** As {@link #decimal} for an {@code int}. */
    public static int integer(Object raw, String where) {
        return Integer.parseInt(numberText(raw, where));
    }

    private static String numberText(Object raw, String where) {
        String text = String.valueOf(raw);
        if (raw instanceof String s && LEADING_ZERO.matcher(s.trim()).matches()) {
            throw new IllegalArgumentException(LEADING_ZERO_NUMBER + ": " + where
                    + " must not be written with a leading zero");
        }
        return text;
    }

    /**
     * Resolves {@code raw} against {@code type}, case- and whitespace-insensitively,
     * failing with an {@link IllegalArgumentException} naming the enum, the
     * offending value and {@code where} in the source document if it does not
     * match a constant. A boolean or number is refused as
     * {@code NON_STRING_CONFIG_SCALAR}, without the value.
     */
    public static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String where) {
        if (raw instanceof Boolean || raw instanceof Number) {
            throw new IllegalArgumentException(NON_STRING_SCALAR + ": " + where + " must be a quoted string");
        }
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown " + type.getSimpleName() + " '" + value + "' at " + where, e);
        }
    }

    /**
     * The enum at {@code key}, or {@code null} if the key is absent. A key that is
     * present with no value is {@code NON_STRING_CONFIG_SCALAR}, never "absent".
     */
    public static <E extends Enum<E>> E optionalEnum(Class<E> type, Map<String, Object> body, String key,
                                                     String where) {
        if (!body.containsKey(key)) {
            return null;
        }
        if (body.get(key) == null) {
            throw new IllegalArgumentException(NON_STRING_SCALAR + ": " + where + " must be a quoted string");
        }
        return enumValue(type, body.get(key), where);
    }
}
