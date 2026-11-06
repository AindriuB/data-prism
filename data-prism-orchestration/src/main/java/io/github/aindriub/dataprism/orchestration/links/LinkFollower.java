package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkReason;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceLabels;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The decisions of a {@code follow} that do not read a source (sealed-links.md section 4 and 5): open the link
 * through the gate, re-check the target, the relation and the payload against the live deployment, call the
 * resolver, order its keys and combine the statuses of a one-to-many read. The orchestrator makes every read,
 * scrub and validation, so no answer from here reaches a caller without the engine.
 *
 * <p>Every refusal is a {@link Refused}: the client sees its code and fixed text, the audit its
 * {@code auditValue}. The post-authentication checks here never touch the failed-attempt limiter; only the gate
 * does. Nothing here logs, and no {@code toString} shows a token, a key or a label.
 */
public final class LinkFollower {

    /** The text the client sees for every {@code LINK_INVALID}, whatever the reason. */
    public static final String INVALID_TEXT = "The link is not valid.";

    private static final String INVALID = "LINK_INVALID";
    private static final String EXHAUSTED_TEXT = "Too many invalid links. Try again later.";
    private static final String UNAVAILABLE_TEXT = "The link check is unavailable. Try again later.";

    /** The answer to a link: authorised to read, or refused. */
    public sealed interface Verdict permits Authorized, Refused {
    }

    /**
     * A refusal: {@code clientCode} and {@code message} go to the client, {@code auditValue} to the audit under
     * the {@code link} disposition key.
     */
    public record Refused(String clientCode, String message, String auditValue) implements Verdict {
        public Refused {
            Objects.requireNonNull(clientCode, "clientCode");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(auditValue, "auditValue");
        }
    }

    /**
     * A link that opened and still stands. Holds the chained key for the length of one call: it never leaves
     * through a {@code toString}.
     *
     * @param target          the permitted target source
     * @param label           the target's label in this call's setting
     * @param relation        {@code DIRECT} or {@code TRANSLATED}
     * @param keyNamespace    the namespace of the sealed key
     * @param targetNamespace the namespace the target reads; equal to {@code keyNamespace} when direct
     * @param resolver        the one resolver; null when direct
     * @param key             the sealed key
     * @param labels          the labels of this call
     */
    public record Authorized(SourceCatalog.Entry target, String label, LinkRelation relation,
                             KeyNamespace keyNamespace, KeyNamespace targetNamespace, KeyResolver resolver,
                             String key, SourceLabels labels) implements Verdict {
        @Override
        public String toString() {
            return "Authorized[redacted]";
        }
    }

    private final LinkAttemptGate gate;
    private final KeyResolvers resolvers;
    private final LinkKeys keys;
    private final ResolverInvoker invoker;
    private final int maxTargets;
    private final Duration timeout;

    public LinkFollower(LinkAttemptGate gate, KeyResolvers resolvers, LinkKeys keys, ResolverInvoker invoker,
                        int maxTargets, Duration timeout) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.maxTargets = maxTargets;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** A refusal for a reason decided here, answered {@code LINK_INVALID}. */
    public static Refused refused(LinkReason reason) {
        return new Refused(INVALID, INVALID_TEXT, reason.auditValue());
    }

    /**
     * Opens the link through the gate (inputs, lock, authentication, counted failure, atomically), then checks the
     * live deployment: the target is still permitted, still accepts the namespace, and for a translated link the
     * resolver is still declared for it.
     *
     * @param labels the labels of this call; read only after the link authenticated, so a forger learns nothing
     *               from a failure to build them
     */
    public Verdict authorize(String token, LinkBinding binding, Supplier<SourceLabels> labels) {
        LinkAttemptGate.GateDecision decision = gate.open(token, binding,
                Set.of(LinkRelation.DIRECT, LinkRelation.TRANSLATED));
        if (decision instanceof LinkAttemptGate.Refusal refusal) {
            return new Refused(refusal.clientCode(), textFor(refusal.clientCode()), refusal.auditValue());
        }
        if (!(decision instanceof LinkAttemptGate.Proceed proceed)
                || !(proceed.opened().payload() instanceof FollowPayload payload)) {
            return refused(LinkReason.PAYLOAD_MISMATCH);
        }
        KeyNamespace keyNamespace;
        try {
            keyNamespace = new KeyNamespace(payload.keyNamespace());
        } catch (IllegalArgumentException malformed) {
            return refused(LinkReason.PAYLOAD_MISMATCH);
        }
        SourceLabels live = Objects.requireNonNull(labels.get(), "labels");
        if (!(live.resolve(payload.target()) instanceof SourceLabels.Found found)) {
            return refused(LinkReason.TARGET_NOT_PERMITTED);
        }
        SourceCatalog.Entry entry = found.entry();
        String resolverName = payload.resolverName().orElse(null);
        Optional<LinkTargets.Target> declared = LinkTargets.forNamespace(keyNamespace, "", live, resolvers).stream()
                .filter(t -> t.name().equals(entry.name()) && t.relation() == payload.relation()
                        && Objects.equals(t.resolverName(), resolverName))
                .findFirst();
        if (declared.isEmpty()) {
            return refused(LinkReason.RELATION_UNDECLARED);
        }
        String key = new String(payload.chainedKey(), StandardCharsets.UTF_8);
        if (payload.relation() == LinkRelation.DIRECT) {
            return new Authorized(entry, payload.target(), LinkRelation.DIRECT, keyNamespace, keyNamespace, null,
                    key, live);
        }
        KeyNamespace to = declared.get().toNamespace();
        Optional<KeyResolver> resolver = resolvers.from(keyNamespace).stream()
                .filter(candidate -> candidate.to().equals(to)).findFirst();
        if (resolver.isEmpty()) {
            return refused(LinkReason.RELATION_UNDECLARED);
        }
        return new Authorized(entry, payload.target(), LinkRelation.TRANSLATED, keyNamespace, to, resolver.get(),
                key, live);
    }

    private static String textFor(String clientCode) {
        return switch (clientCode) {
            case "LINK_ATTEMPTS_EXHAUSTED" -> EXHAUSTED_TEXT;
            case "LINK_CHECK_UNAVAILABLE" -> UNAVAILABLE_TEXT;
            default -> INVALID_TEXT;
        };
    }

    /** The scope's fingerprint key; throws when the current link key is unavailable, before anything is charged. */
    public LinkKeys.ScopeKeys scopeKeys(String scopeId) {
        return keys.forScope(scopeId);
    }

    /** Calls the one resolver, bounded, with the configured cap and timeout. */
    public ResolverOutcome resolve(Authorized authorized, ResolutionContext context) {
        return invoker.invoke(authorized.resolver(), authorized.key(), context, maxTargets, timeout);
    }

    /**
     * The keys in ascending keyed-fingerprint order, an equal key once. Real keys decide nothing about the order,
     * so it discloses nothing about them.
     */
    public static List<String> ordered(LinkKeys.ScopeKeys scopeKeys, List<String> resolved) {
        TreeMap<byte[], String> byFingerprint = new TreeMap<>(Comparator.comparing(
                (byte[] fingerprint) -> fingerprint, Arrays::compareUnsigned));
        for (String key : resolved) {
            byFingerprint.putIfAbsent(scopeKeys.fingerprint(key.getBytes(StandardCharsets.UTF_8)), key);
        }
        return List.copyOf(byFingerprint.values());
    }

    /**
     * The status of a one-to-many source from its items that were not refused (sealed-links.md section 5, OD-9):
     * {@code ANSWERED} if any answered, otherwise the one status they share, otherwise {@code FAILED}. No items
     * is {@code NO_DATA}.
     */
    public static String outerStatus(List<String> itemStatuses) {
        if (itemStatuses.isEmpty()) {
            return "NO_DATA";
        }
        if (itemStatuses.contains("ANSWERED")) {
            return "ANSWERED";
        }
        String first = itemStatuses.get(0);
        return itemStatuses.stream().allMatch(first::equals) ? first : "FAILED";
    }

    @Override
    public String toString() {
        return "LinkFollower[redacted]";
    }
}
