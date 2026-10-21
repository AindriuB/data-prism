package io.github.aindriub.dataprism.hazelcast.seal;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The keys a member holds: the current cluster key, an optional {@code next} (accepted for reading, never
 * written) and {@code previous} (read, and written only under the write-key rule), and the separate name key.
 * See docs/plan/design/cluster-security.md 4.6.
 *
 * <p>Every key is at least 32 bytes. Ids are pairwise distinct across all four keys and contain no {@code .},
 * so an envelope splits unambiguously on {@code .}. The name-key material differs from every cluster key. The
 * constructor refuses with {@link IllegalArgumentException}; a message names the field ({@code current},
 * {@code next}, {@code previous}, {@code name}) and never a key id or any material. Material is copied in and
 * copied out, and {@code toString} shows ids only.
 */
public final class ClusterKeys {

    /** The minimum strength, as for the privacy HMAC key. */
    public static final int MIN_KEY_BYTES = 32;

    /** One held cluster key. */
    public static final class Key {
        private final String id;
        private final byte[] material;

        private Key(String id, byte[] material) {
            this.id = id;
            this.material = material.clone();
        }

        public String id() {
            return id;
        }

        /** A copy of the key material. */
        public byte[] material() {
            return material.clone();
        }

        /** The material without a copy, for use inside this package. */
        byte[] bytes() {
            return material;
        }

        @Override
        public String toString() {
            return "Key[" + id + "]";
        }
    }

    private final Key current;
    private final Key next;
    private final Key previous;
    private final String nameKeyId;
    private final byte[] nameKey;

    /**
     * @param nextId       with {@code next}, both null when there is no {@code next} key
     * @param previousId   with {@code previous}, both null when there is no {@code previous} key
     */
    public ClusterKeys(String currentId, byte[] current, String nextId, byte[] next,
                       String previousId, byte[] previous, String nameKeyId, byte[] nameKey) {
        requireId("current", currentId);
        requireMaterial("current", current);
        requirePair("next", nextId, next);
        requirePair("previous", previousId, previous);
        requireId("name", nameKeyId);
        requireMaterial("name", nameKey);

        if (nextId != null && nextId.equals(currentId)) {
            throw new IllegalArgumentException("next key id duplicates the current key id");
        }
        if (previousId != null && previousId.equals(currentId)) {
            throw new IllegalArgumentException("previous key id duplicates the current key id");
        }
        if (previousId != null && previousId.equals(nextId)) {
            throw new IllegalArgumentException("previous key id duplicates the next key id");
        }
        if (nameKeyId.equals(currentId) || nameKeyId.equals(nextId) || nameKeyId.equals(previousId)) {
            throw new IllegalArgumentException("name key id duplicates a cluster key id");
        }
        if (same(nameKey, current) || (next != null && same(nameKey, next))
                || (previous != null && same(nameKey, previous))) {
            throw new IllegalArgumentException("name key material equals a cluster key");
        }

        this.current = new Key(currentId, current);
        this.next = next == null ? null : new Key(nextId, next);
        this.previous = previous == null ? null : new Key(previousId, previous);
        this.nameKeyId = nameKeyId;
        this.nameKey = nameKey.clone();
    }

    private static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private static void requireId(String field, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(field + " key id is required");
        }
        if (id.indexOf('.') >= 0) {
            throw new IllegalArgumentException(field + " key id must not contain a dot");
        }
    }

    private static void requireMaterial(String field, byte[] material) {
        if (material == null || material.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(field + " key must be at least " + MIN_KEY_BYTES + " bytes");
        }
    }

    private static void requirePair(String field, String id, byte[] material) {
        if (id == null && material == null) {
            return;
        }
        if (id == null || material == null) {
            throw new IllegalArgumentException(field + " key needs both an id and material, or neither");
        }
        requireId(field, id);
        requireMaterial(field, material);
    }

    public Key current() {
        return current;
    }

    public Optional<Key> next() {
        return Optional.ofNullable(next);
    }

    public Optional<Key> previous() {
        return Optional.ofNullable(previous);
    }

    public String nameKeyId() {
        return nameKeyId;
    }

    /** A copy of the name key material. */
    public byte[] nameKeyMaterial() {
        return nameKey.clone();
    }

    byte[] nameKeyBytes() {
        return nameKey;
    }

    /** Every cluster key this member holds, in the order current, next, previous. Reads accept all of them. */
    public List<Key> held() {
        List<Key> out = new ArrayList<>(3);
        out.add(current);
        if (next != null) {
            out.add(next);
        }
        if (previous != null) {
            out.add(previous);
        }
        return List.copyOf(out);
    }

    /** The held cluster key with this id, if any. The name key is not a cluster key. */
    public Optional<Key> find(String id) {
        for (Key key : held()) {
            if (key.id.equals(id)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "ClusterKeys[current=" + current.id + ", next=" + (next == null ? "-" : next.id)
                + ", previous=" + (previous == null ? "-" : previous.id) + ", name=" + nameKeyId + "]";
    }
}
