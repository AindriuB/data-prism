package io.github.aindriub.dataprism.pseudonymisation;

import java.util.List;

/**
 * The word lists synthetic values are drawn from.
 *
 * <p>Name pools are not a neutral choice. Matching the source's apparent
 * language keeps output readable and helps a model reason, and it also preserves
 * apparent ethnicity and gender — a protected attribute the pseudonym was
 * supposed to remove. This pool is deliberately mixed and small rather than
 * locale-matched, so nothing about a synthetic name implies anything about the
 * real one. Sizing a pool properly, and any licensing of a larger corpus, is S2.
 *
 * <p>Collisions inside a scope are handled by the discriminator suffix, not by
 * pool size. See docs/design-review.md §A2.
 */
final class SyntheticVocabulary {

    private SyntheticVocabulary() {
    }

    static final List<String> FIRST_NAMES = List.of(
            "Alex", "Robin", "Sam", "Jordan", "Casey", "Morgan", "Riley", "Quinn",
            "Avery", "Rowan", "Sage", "Emery", "Reese", "Finley", "Harper", "Kai",
            "Noor", "Ari", "Devon", "Ellis", "Frankie", "Gray", "Hayden", "Indigo",
            "Jules", "Kerry", "Lane", "Marlow", "Nico", "Oakley", "Payton", "Remy");

    static final List<String> LAST_NAMES = List.of(
            "Murphy", "Walsh", "Adeyemi", "Novak", "Okafor", "Larsen", "Duarte", "Haddad",
            "Ferreira", "Kowalski", "Nakamura", "Petrov", "Rossi", "Silva", "Tan", "Vargas",
            "Whitfield", "Yilmaz", "Zhang", "Ahmed", "Bergman", "Castellano", "Dubois", "Eriksen",
            "Fontaine", "Gallagher", "Hoffmann", "Ibrahim", "Jensen", "Keller", "Lindqvist", "Moreau");

    static final List<String> STREETS = List.of(
            "Aspen Way", "Birch Lane", "Cedar Rise", "Dovecote Road", "Elm Court", "Fern Walk",
            "Grange Close", "Hazel Street", "Ivy Terrace", "Juniper Row", "Kestrel Drive", "Linden Park",
            "Maple Grove", "Nightingale Road", "Orchard Mews", "Poplar Avenue");

    static final List<String> TOWNS = List.of(
            "Ashford", "Belmont", "Carrowmore", "Dunmore", "Eastvale", "Fairhaven",
            "Glenbrook", "Highfield", "Inverlea", "Jarrow", "Kingsmere", "Loxley",
            "Millbrook", "Northgate", "Oakhaven", "Pinehurst");
}
