package io.github.aindriub.dataprism.core.spi;

import java.util.List;
import java.util.Objects;

/**
 * Translates one key from one key namespace into keys of another. The only way a link may reach a source whose
 * input key is in a different namespace (D-RM-5); see docs/plan/design/key-resolvers.md section 6.
 *
 * <p>Callers go through the bounded invoker in orchestration, never directly: it times the call, caps the result
 * and turns every contract violation into a closed reason.
 */
public interface KeyResolver {

    /** Stable name; appears in the audit, never to the client. Grammar {@code [a-z][a-z0-9-]{0,31}}. */
    String name();

    /** The namespace of the keys this resolver accepts. */
    KeyNamespace from();

    /** The namespace of the keys it returns. Must differ from {@link #from()}. */
    KeyNamespace to();

    /**
     * Translates one key into zero or more keys. An empty list means no translation is known; it is not an error.
     * Throws {@link KeyResolutionException} on any failure. Never returns null and never more than
     * {@code maxResults} keys; if more exist, says so with {@code more = true}.
     */
    Resolution resolve(String key, ResolutionContext context, int maxResults);

    /**
     * The translated keys in the resolver's own stable order, and whether more exist. The constructor does not
     * reject {@code more} with no keys: that is a contract violation the invoker classifies as invalid output.
     */
    record Resolution(List<String> keys, boolean more) {
        public Resolution {
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        }
    }
}
