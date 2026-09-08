package io.github.aindriub.dataprism.pseudonymisation.vocabulary;

/** The word lists a name set provides. */
public enum PoolKind {

    FIRST_NAME("firstNames"),
    LAST_NAME("lastNames"),
    STREET("streets"),
    TOWN("towns"),
    ORGANISATION("organisations");

    private final String key;

    PoolKind(String key) {
        this.key = key;
    }

    /** The key this pool appears under in a vocabulary file. */
    public String key() {
        return key;
    }
}
