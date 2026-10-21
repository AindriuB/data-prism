package io.github.aindriub.dataprism.hazelcast.seal;

/**
 * The result of {@link Sealer#open}. One of three outcomes, and a constant message: no outcome carries an
 * exception text, a map key, an envelope field or plaintext other than an authentic value's own bytes.
 */
public final class Opened {

    /** What happened to the envelope. */
    public enum Outcome {
        /** The envelope authenticated; the key id and plaintext are set. */
        AUTHENTIC,
        /** It could not be authenticated: a changed field, another map, map key or key, or a provider fault. */
        TAMPERED,
        /** A format version, key id or salt this member cannot read, or a salt refused by the bounded cache. */
        UNSUPPORTED
    }

    /** The message of a {@link Outcome#TAMPERED} result. */
    public static final String UNAUTHENTIC_MESSAGE = "the entry could not be authenticated";
    /** The message of an {@link Outcome#UNSUPPORTED} result. */
    public static final String UNSUPPORTED_MESSAGE = "the entry format or key is not supported";

    private static final byte[] NONE = new byte[0];

    private final Outcome outcome;
    private final String keyId;
    private final byte[] plaintext;

    private Opened(Outcome outcome, String keyId, byte[] plaintext) {
        this.outcome = outcome;
        this.keyId = keyId;
        this.plaintext = plaintext;
    }

    static Opened authentic(String keyId, byte[] plaintext) {
        return new Opened(Outcome.AUTHENTIC, keyId, plaintext);
    }

    static Opened tampered() {
        return new Opened(Outcome.TAMPERED, null, NONE);
    }

    static Opened unsupported() {
        return new Opened(Outcome.UNSUPPORTED, null, NONE);
    }

    public Outcome outcome() {
        return outcome;
    }

    /** The id of the key that opened the value; null unless {@link #outcome()} is {@code AUTHENTIC}. */
    public String keyId() {
        return keyId;
    }

    /** A copy of the plaintext; empty unless {@link #outcome()} is {@code AUTHENTIC}. */
    public byte[] plaintext() {
        return plaintext.clone();
    }

    /** A constant description of the outcome. */
    public String message() {
        return switch (outcome) {
            case AUTHENTIC -> "authentic";
            case TAMPERED -> UNAUTHENTIC_MESSAGE;
            case UNSUPPORTED -> UNSUPPORTED_MESSAGE;
        };
    }

    @Override
    public String toString() {
        return "Opened[" + outcome + "]";
    }
}
