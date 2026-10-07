package io.github.aindriub.dataprism.audit;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides what a caller-supplied {@code entityType} is allowed to look like in
 * an audit record.
 *
 * <p>The tool argument is free text, so it can carry personal data into the
 * hash-chained log, its JSON projection and the log line. The audit field
 * holds the argument only when it is acceptable, and the fixed
 * {@link #UNREGISTERED} sentinel otherwise. The caller's own response is not
 * affected: this class only shapes what is audited.
 *
 * <p>There are two modes.
 * <ul>
 *   <li><b>List mode</b>, from {@link #of(Collection)} with at least one name:
 *       only an exact, case-sensitive member of the list is audited verbatim.</li>
 *   <li><b>Shape mode</b>, from {@link #shape()} or an empty collection: the
 *       value is audited verbatim only if it matches
 *       {@code [A-Z][A-Z0-9_]{0,63}}. An upper-case token such as
 *       {@code ACC123} passes, so operators are advised to configure a list.</li>
 * </ul>
 */
public final class AuditedEntityTypes {

    /**
     * What is audited in place of an entity type that is not acceptable. The
     * value is {@code <unregistered>} and is stable: log queries may match on
     * it. It cannot collide with a configured name, because
     * {@link #of(Collection)} refuses any name containing {@code <}.
     */
    public static final String UNREGISTERED = "<unregistered>";

    /** What a configured name must look like. */
    public static final Pattern REGISTERED_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** What an entity type must look like to be audited in shape mode. */
    public static final Pattern SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final AuditedEntityTypes SHAPE_MODE = new AuditedEntityTypes(Set.of());

    private final Set<String> registered;

    private AuditedEntityTypes(Set<String> registered) {
        this.registered = registered;
    }

    /** Shape mode: the default used wherever no list was supplied. */
    public static AuditedEntityTypes shape() {
        return SHAPE_MODE;
    }

    /**
     * Exact list mode. An empty collection means shape mode.
     *
     * @throws IllegalArgumentException led by {@code INVALID_AUDIT_ENTITY_TYPE} when a name is null
     *                                  or does not match {@link #REGISTERED_NAME}; the message never
     *                                  includes the offending value
     */
    public static AuditedEntityTypes of(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return SHAPE_MODE;
        }
        Set<String> accepted = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || !REGISTERED_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("INVALID_AUDIT_ENTITY_TYPE: each entry must match "
                        + REGISTERED_NAME.pattern());
            }
            accepted.add(name);
        }
        return new AuditedEntityTypes(Set.copyOf(accepted));
    }

    /** True when no list was supplied and the shape fallback is in force. */
    public boolean isShapeMode() {
        return registered.isEmpty();
    }

    /** {@code raw} when it is acceptable under this mode, otherwise {@link #UNREGISTERED}. */
    public String audited(String raw) {
        if (raw == null) {
            return UNREGISTERED;
        }
        if (registered.isEmpty()) {
            return SHAPE.matcher(raw).matches() ? raw : UNREGISTERED;
        }
        return registered.contains(raw) ? raw : UNREGISTERED;
    }
}
