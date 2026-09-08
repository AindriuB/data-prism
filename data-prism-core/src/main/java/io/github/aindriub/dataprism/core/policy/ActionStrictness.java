package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.PrivacyAction;

import java.util.List;

/**
 * A total order over privacy actions, from most to least revealing.
 *
 * <p>Policy and annotation are combined by taking the stricter of the two, which
 * needs an ordering, and the ordering is a judgement rather than a fact. The one
 * used here:
 *
 * <pre>
 * PASS_THROUGH &lt; GENERALIZE &lt; SYNTHESIZE &lt; TOKENIZE &lt; HASH &lt; REDACT &lt; REMOVE
 * </pre>
 *
 * <p>The interesting placement is {@code SYNTHESIZE} below {@code REDACT}. A
 * synthetic value discloses nothing about the original, but it is stable within
 * the scope, so it lets anything downstream link records — which is the whole
 * point of it, and also strictly more than redaction reveals. {@code GENERALIZE}
 * sits lower still because a bucket retains real information about the value.
 *
 * <p>{@code REMOVE} is strictest: a redacted field still tells the reader the
 * field existed and had a value.
 */
public final class ActionStrictness {

    private static final List<PrivacyAction> ORDER = List.of(
            PrivacyAction.PASS_THROUGH,
            PrivacyAction.GENERALIZE,
            PrivacyAction.SYNTHESIZE,
            PrivacyAction.TOKENIZE,
            PrivacyAction.HASH,
            PrivacyAction.REDACT,
            PrivacyAction.REMOVE);

    private ActionStrictness() {
    }

    public static int rank(PrivacyAction action) {
        int index = ORDER.indexOf(action);
        if (index < 0) {
            // A new action with no ranking must not silently become the weakest.
            throw new IllegalStateException("unranked privacy action: " + action);
        }
        return index;
    }

    public static PrivacyAction stricter(PrivacyAction a, PrivacyAction b) {
        return rank(a) >= rank(b) ? a : b;
    }
}
