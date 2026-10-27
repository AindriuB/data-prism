package io.github.aindriub.dataprism.core.engine.spoof;

/** A public user class in a separate package, for tests that need a class the engine does not own. */
public class Spoof {
    public String value() {
        return "invented";
    }
}
