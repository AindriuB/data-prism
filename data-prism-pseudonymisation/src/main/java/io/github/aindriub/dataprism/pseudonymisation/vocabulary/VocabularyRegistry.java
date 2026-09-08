package io.github.aindriub.dataprism.pseudonymisation.vocabulary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Holds the name sets available to a generator and resolves one per locale.
 *
 * <p>Ships broad defaults and expects them to be changed. An organisation can
 * replace a locale outright, extend one with its own entries, or register a
 * locale that is not bundled — all without touching the platform.
 *
 * <p>Locale is worth having because scripts differ in ways a model can see. A
 * dataset of Arabic, Mandarin and Irish names replaced by uniformly English
 * pseudonyms loses information the investigation may care about; keeping the
 * script means the pseudonym still reads as a name in the same writing system.
 *
 * <p>The cost is that a locale-matched pseudonym preserves something the
 * original disclosed, since a name's script correlates with attributes a
 * pseudonym is otherwise removing. That is why the default is one broad pool
 * rather than automatic per-record locale detection: choosing a locale should be
 * a deliberate configuration decision.
 */
public final class VocabularyRegistry {

    /** Bundled sets. Classpath directories cannot be listed portably, so they are named. */
    private static final List<String> BUILT_IN = List.of(
            "generic-v1", "western-v2", "european-v1", "irish-v1",
            "arabic-v1", "mandarin-v1", "cyrillic-v1");

    public static final String DEFAULT_LOCALE = "en";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Map<String, Vocabulary> byLocale;
    private final String defaultLocale;

    private VocabularyRegistry(Map<String, Vocabulary> byLocale, String defaultLocale) {
        this.byLocale = Map.copyOf(byLocale);
        this.defaultLocale = defaultLocale;
    }

    public static VocabularyRegistry withBuiltIns() {
        return builder().addBuiltIns().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves the closest set: the exact tag, then the language alone, then the
     * default. {@code ga-IE} finds an {@code ga} set; an unregistered locale
     * falls back rather than failing, because a missing locale should degrade to
     * a working pseudonym rather than break the request.
     */
    public Vocabulary resolve(String localeTag) {
        String tag = localeTag == null || localeTag.isBlank()
                ? defaultLocale
                : localeTag.trim();

        Vocabulary exact = byLocale.get(tag.toLowerCase(Locale.ROOT));
        if (exact != null) {
            return exact;
        }
        int dash = tag.indexOf('-');
        if (dash > 0) {
            Vocabulary language = byLocale.get(tag.substring(0, dash).toLowerCase(Locale.ROOT));
            if (language != null) {
                return language;
            }
        }
        Vocabulary fallback = byLocale.get(defaultLocale);
        if (fallback == null) {
            throw new IllegalStateException("no vocabulary registered for the default locale "
                    + defaultLocale);
        }
        return fallback;
    }

    public Set<String> locales() {
        return byLocale.keySet();
    }

    public static final class Builder {

        private final Map<String, NameSet> sets = new LinkedHashMap<>();
        private String defaultLocale = DEFAULT_LOCALE;

        public Builder addBuiltIns() {
            for (String name : BUILT_IN) {
                try (InputStream in = VocabularyRegistry.class
                        .getResourceAsStream("/vocabulary/" + name + ".yaml")) {
                    if (in == null) {
                        throw new IllegalStateException("bundled vocabulary missing: " + name);
                    }
                    add(read(in));
                } catch (IOException e) {
                    throw new UncheckedIOException("bundled vocabulary " + name + " unreadable", e);
                }
            }
            return this;
        }

        /** Registers a set, failing if its locale is already taken. */
        public Builder add(NameSet set) {
            String key = key(set.localeTag());
            if (sets.containsKey(key)) {
                throw new IllegalArgumentException("locale " + key + " already registered by "
                        + sets.get(key).id() + "; use replace or extend to say which you meant");
            }
            sets.put(key, set);
            return this;
        }

        /** Replaces whatever is registered for this set's locale. */
        public Builder replace(NameSet set) {
            sets.put(key(set.localeTag()), set);
            return this;
        }

        /**
         * Appends to the set already registered for this locale, or registers it
         * if there is none. Every pseudonym drawn from that locale changes, so
         * the resolved vocabulary id changes with it.
         */
        public Builder extend(NameSet set) {
            String key = key(set.localeTag());
            NameSet existing = sets.get(key);
            sets.put(key, existing == null ? set : existing.extendedWith(set));
            return this;
        }

        /** Reads a set from a vocabulary file and registers it, replacing any existing. */
        public Builder load(InputStream yaml) {
            return replace(read(yaml));
        }

        public Builder defaultLocale(String localeTag) {
            this.defaultLocale = key(Objects.requireNonNull(localeTag, "localeTag"));
            return this;
        }

        public VocabularyRegistry build() {
            if (!sets.containsKey(defaultLocale)) {
                throw new IllegalStateException("no name set registered for the default locale "
                        + defaultLocale + "; registered: " + sets.keySet());
            }
            Map<String, Vocabulary> resolved = new LinkedHashMap<>();
            sets.forEach((locale, set) -> resolved.put(locale, new ContentAddressed(set)));
            return new VocabularyRegistry(resolved, defaultLocale);
        }

        private static String key(String localeTag) {
            return localeTag.trim().toLowerCase(Locale.ROOT);
        }
    }

    @SuppressWarnings("unchecked")
    static NameSet read(InputStream yaml) {
        Map<String, Object> root;
        try {
            root = YAML.readValue(yaml, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("vocabulary file could not be read", e);
        }

        Map<PoolKind, List<String>> pools = new EnumMap<>(PoolKind.class);
        Object poolsNode = root.get("pools");
        if (poolsNode instanceof Map<?, ?> map) {
            for (PoolKind kind : PoolKind.values()) {
                Object entries = map.get(kind.key());
                if (entries instanceof List<?> list && !list.isEmpty()) {
                    pools.put(kind, list.stream().map(String::valueOf).toList());
                }
            }
        }
        return new NameSet(
                String.valueOf(root.getOrDefault("id", "unnamed")),
                Integer.parseInt(String.valueOf(root.getOrDefault("version", "1"))),
                String.valueOf(root.getOrDefault("locale", "und")),
                String.valueOf(root.getOrDefault("script", "Zyyy")),
                pools);
    }

    /** A vocabulary whose id is a digest of its contents. */
    private record ContentAddressed(NameSet set, String id) implements Vocabulary {

        ContentAddressed(NameSet set) {
            this(set, set.id() + "-v" + set.version() + "#" + digest(set));
        }

        @Override
        public String localeTag() {
            return set.localeTag();
        }

        @Override
        public String script() {
            return set.script();
        }

        @Override
        public List<String> pool(PoolKind kind) {
            return set.pool(kind);
        }

        private static String digest(NameSet set) {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                sha.update(set.id().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) set.version());
                sha.update(set.localeTag().getBytes(StandardCharsets.UTF_8));
                // Sorted so a reordered file is the same vocabulary; the order
                // entries appear in is not what selects a name, the index is.
                new TreeMap<>(set.pools()).forEach((kind, entries) -> {
                    sha.update(kind.key().getBytes(StandardCharsets.UTF_8));
                    entries.forEach(e -> sha.update(e.getBytes(StandardCharsets.UTF_8)));
                });
                return HexFormat.of().formatHex(sha.digest(), 0, 4);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}
